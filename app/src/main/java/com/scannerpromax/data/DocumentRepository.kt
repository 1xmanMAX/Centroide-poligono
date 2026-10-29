package com.scannerpromax.data

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.scannerpromax.ai.jev.CaptureHints
import com.scannerpromax.ai.jev.JevBox
import com.scannerpromax.ai.jev.JevCapture
import com.scannerpromax.ai.jev.JevDuplicates
import com.scannerpromax.ai.jev.JevRenderHints
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.photoBoxes
import com.scannerpromax.domain.Quad
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.DeviceProfiler
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.Cv
import com.scannerpromax.imaging.DuplicatePages
import com.scannerpromax.imaging.PageProcessor
import com.scannerpromax.imaging.PerspectiveCorrector
import com.scannerpromax.imaging.ProgressCallback
import com.scannerpromax.ocr.OcrEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Almacenamiento local: filesDir/documents/{docId}/doc.json + original/, processed/, thumb/, ocr/.
 * Todas las funciones suspend corren en Dispatchers.IO / Default internamente.
 *
 * Diseño:
 *  - Estado en memoria (mapa docId -> Document) publicado en [documents]; doc.json se escribe de forma atómica.
 *  - Las escrituras de metadatos van bajo [writeMutex] (operaciones cortas).
 *  - El procesamiento pesado de imágenes va bajo [heavy] (1 permiso en gama baja para evitar OOM)
 *    y bajo un candado por página (no se renderiza dos veces la misma página a la vez).
 *  - Los archivos procesados/miniaturas llevan un hash de las ediciones en el nombre: si las ediciones
 *    no cambian no se regeneran, y Coil invalida su caché automáticamente cuando cambian.
 */
class DocumentRepository(
    private val context: Context,
    private val processor: PageProcessor,
    private val tier: DeviceTier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** "Mejorar con Jev" (Ajustes › IA en la nube); null o apagado = comportamiento de siempre. */
    @Volatile var jev: com.scannerpromax.ai.jev.JevEnhancer? = null
    private val root: File = File(context.filesDir, DOCUMENTS_DIR)
    private val captureDir: File = File(context.cacheDir, "captures")
    private val importDir: File = File(context.cacheDir, "imports")

    private val docs = LinkedHashMap<String, Document>()   // protegido por writeMutex
    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    private val loaded = CompletableDeferred<Unit>()
    private val writeMutex = Mutex()

    /**
     * Renders completos simultáneos. Cada render a 8-12 MP tiene un pico nativo de cientos de MB y OpenCV ya
     * paraleliza internamente, así que un segundo permiso solo compensa en gama alta con mucha RAM.
     */
    private val heavy = Semaphore(heavyPermits())
    /** Trabajos pesados en curso o esperando permiso (el OCR de fondo espera a que llegue a 0). */
    private val activeHeavy = MutableStateFlow(0)
    /** Pantallas que piden pausar el OCR de fondo (cámara abierta, editor con vistas previas, fusión...). */
    private val ocrPause = MutableStateFlow(0)
    /** Documentos borrados: los trabajos en curso no deben volver a crear su carpeta. */
    private val deletedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pageLocks = ConcurrentHashMap<String, Mutex>()
    private val editGenerations = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Filtro preferido por el usuario para modos de documento (ajustes). null = filtro por defecto del modo.
     * La UI / el contenedor pueden asignarlo leyendo [SettingsRepository].
     */
    @Volatile var preferredFilter: FilterType? = null

    /** Si es true, las páginas nuevas se crean con "quitar rayas" activado (ajustes). */
    @Volatile var defaultAutoRemoveLines: Boolean = false

    /**
     * OCR en segundo plano tras crear/editar cada página (búsqueda en Inicio y PDF buscable siempre listos).
     * Se procesa de una en una y solo cuando no hay procesamiento pesado en curso.
     */
    @Volatile var backgroundOcrEnabled: Boolean = true

    /**
     * Avisos para la maquetación por partes ([com.scannerpromax.ai.AiRecognitionQueue]): páginas nuevas o con la imagen
     * cambiada. El repositorio no sabe nada de modelos: solo avisa.
     */
    interface AiHook {
        /** Página nueva. */
        fun onPageAdded(docId: String, pageId: String)
        /** La imagen procesada de la página cambió (ediciones): su OCR/maquetación ya no vale. */
        fun onPageChanged(docId: String, pageId: String)
    }

    @Volatile var aiHook: AiHook? = null

    /**
     * Se llama (en segundo plano) cuando una página no se pudo reconocer porque el lector de texto (Indra) aún no está
     * descargado: el contenedor arranca la descarga. La página queda pendiente y [requeueMissingOcr] la reconoce al terminar.
     */
    @Volatile var onOcrUnavailable: (() -> Unit)? = null

    /** Motor OCR (Indra): el contenedor asigna el suyo con [attachOcrEngine]. */
    @Volatile private var ocrEngine: OcrEngine? = null
    private val ocrQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val queuedOcr: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val ocrLocks = ConcurrentHashMap<String, Mutex>()

    /** Escritura de doc.json: un candado por documento (fuera de [writeMutex]) y escrituras diferidas agrupadas. */
    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingWrites = ConcurrentHashMap<String, Job>()
    private val lastWritten = ConcurrentHashMap<String, Document>()

    init {
        scope.launch {
            try {
                loadAll()
                cleanupCache()
            } catch (t: Throwable) {
                Log.e(TAG, "Error cargando documentos", t)
            } finally {
                loaded.complete(Unit)
            }
        }
        scope.launch { ocrWorker() }
        scope.launch {
            // Al arrancar, OCR pendiente de páginas antiguas (con calma, para no competir con el arranque).
            loaded.await()
            delay(STARTUP_OCR_DELAY_MS)
            writeMutex.withLock { docs.values.toList() }.forEach { d ->
                d.pages.filter { it.ocrFile == null }.forEach { p ->
                    // Las páginas cuyo OCR ya falló varias veces no se reintentan en cada arranque.
                    if (withContext(Dispatchers.IO) { ocrFailures(d.id, p.id) } < OCR_MAX_BACKGROUND_ATTEMPTS) {
                        enqueueOcr(d.id, p.id)
                    }
                }
            }
        }
    }

    val documents: StateFlow<List<Document>> get() = _documents.asStateFlow()

    /**
     * Pausa el OCR de fondo mientras haya una pantalla sensible abierta (cámara: vista previa, análisis y ráfagas;
     * editor: vistas previas; fusión multi-foto). Es un contador: cada llamada debe ir seguida de
     * [resumeBackgroundOcr] (p. ej. en el onDispose de un DisposableEffect). Si hay un OCR de fondo en curso se
     * cancela al terminar la página en curso y la página se vuelve a encolar.
     */
    fun pauseBackgroundOcr() {
        ocrPause.update { it + 1 }
    }

    fun resumeBackgroundOcr() {
        ocrPause.update { (it - 1).coerceAtLeast(0) }
    }

    /** Escribe ya los doc.json con cambios diferidos (llamar al pasar a segundo plano, p. ej. en onStop). */
    suspend fun flushPendingWrites() {
        for (id in pendingWrites.keys.toList()) {
            pendingWrites.remove(id)?.cancel()
            writeLatest(id, sync = true)
        }
    }

    fun observe(docId: String): Flow<Document?> =
        _documents.map { list -> list.firstOrNull { it.id == docId } }.distinctUntilChanged()

    suspend fun get(docId: String): Document? {
        loaded.await()
        return writeMutex.withLock { docs[docId] }
    }

    suspend fun create(mode: ScanMode, title: String? = null): Document {
        loaded.await()
        val now = System.currentTimeMillis()
        val doc = Document(
            id = newId(),
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTitle(mode, now),
            createdAt = now,
            updatedAt = now,
            mode = mode,
        )
        withContext(Dispatchers.IO) {
            val dir = docDir(doc.id)
            SUBDIRS.forEach { File(dir, it).mkdirs() }
        }
        writeMutex.withLock {
            withContext(Dispatchers.IO) { writeDocJson(doc) }
            docs[doc.id] = doc
            publish()
        }
        return doc
    }

    suspend fun rename(docId: String, title: String) {
        val clean = title.trim().ifEmpty { return }
        mutate(docId) { it.copy(title = clean) }
    }

    suspend fun delete(docId: String) {
        loaded.await()
        deletedIds += docId
        writeMutex.withLock {
            docs.remove(docId)
            publish()
        }
        pendingWrites.remove(docId)?.cancel()
        fileLock(docId).withLock {
            withContext(Dispatchers.IO) { docDir(docId).deleteRecursively() }
        }
        lastWritten.remove(docId)
    }

    suspend fun duplicate(docId: String): Document {
        loaded.await()
        val source = get(docId) ?: throw IOException("Documento no encontrado")
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = newId(),
            title = "${source.title} (copia)",
            createdAt = now,
            updatedAt = now,
        )
        withContext(Dispatchers.IO) {
            val src = docDir(source.id)
            val dst = docDir(copy.id)
            src.copyRecursively(dst, overwrite = true)
            File(dst, DOC_JSON).delete()
            File(dst, DOC_JSON_TMP).delete()
        }
        writeMutex.withLock {
            withContext(Dispatchers.IO) { writeDocJson(copy) }
            docs[copy.id] = copy
            publish()
        }
        return copy
    }

    /**
     * Añade una página desde un JPEG capturado (se mueve/copia a original/). Detecta bordes automáticamente y aplica
     * el filtro por defecto del modo. [mode] = modo activo al capturar (null = modo del documento).
     *
     * El original se conserva a resolución completa (el disco no gasta RAM): [Page.width]/[Page.height] son las
     * dimensiones reales del archivo (con EXIF aplicado) y el quad se guarda en ese espacio. El archivo se MUEVE
     * tal cual (mismos bytes: ni se reescala ni se recomprime, el EXIF de orientación se conserva y se respeta al
     * decodificar). Para procesar se decodifica con el presupuesto [DeviceTier.maxWorkingPixels] (en gama media y
     * alta la foto completa de 12-16 MP cabe sin reducir) y el quad se reescala.
     * [presetEdits]: ediciones ya decididas (p. ej. páginas de un libro ya recortadas); si se dan, no se detecta.
     */
    suspend fun addPageFromFile(
        docId: String,
        file: File,
        autoDetect: Boolean = true,
        mode: ScanMode? = null,
        presetEdits: PageEdits? = null,
    ): Page {
        loaded.await()
        val doc = get(docId) ?: throw IOException("Documento no encontrado")
        checkAlive(docId)
        val m = mode ?: doc.mode
        val pageId = newId()
        val dir = docDir(docId)
        val originalName = "$ORIGINAL_DIR/$pageId.jpg"
        val dst = File(dir, originalName)

        var capture: CaptureHints? = null
        var docBox: JevBox? = null
        val page = heavyWork {
            withContext(Dispatchers.Default) {
                var bitmap: Bitmap? = null
                try {
                    withContext(Dispatchers.IO) {
                        checkAlive(docId)
                        dst.parentFile?.mkdirs()
                        moveOrCopy(file, dst)
                    }
                    val (fw, fh) = withContext(Dispatchers.IO) { BitmapIO.decodeBounds(dst.absolutePath) }
                    // Decodifica respetando el presupuesto de memoria del dispositivo (EXIF aplicado por BitmapIO).
                    val bmp = BitmapIO.decode(dst.absolutePath, tier.maxWorkingPixels)
                    bitmap = bmp
                    val pw = if (fw > 0 && fh > 0) fw else bmp.width
                    val ph = if (fw > 0 && fh > 0) fh else bmp.height
                    currentCoroutineContext().ensureActive()

                    val edits = presetEdits ?: run {
                        val det = if (autoDetect && m != ScanMode.PHOTO) detectQuadWithConfidence(bmp) else null
                        // "Mejorar con Jev" (opcional): tipo, carpeta, encuadre y calidad de la foto en UNA petición. Sin
                        // clave, sin red o si falla: null y todo como siempre.
                        val hints = if (autoDetect && m != ScanMode.PHOTO && m != ScanMode.ID_CARD) captureHints(bmp, dir, pageId) else null
                        capture = hints
                        var q0 = det?.first
                        when (val f = JevCapture.framing(hints, q0?.let { boxOf(it, bmp.width, bmp.height) }, det?.second, q0 != null && nearlyFull(q0, bmp.width, bmp.height))) {
                            JevCapture.Framing.Keep -> Unit
                            // no hay documento: sin recorte (antes se recortaba el 82 % de las fotos sin documento)
                            JevCapture.Framing.FullPage -> q0 = null
                            is JevCapture.Framing.Roi -> q0 = rescueQuad(bmp, f, hints?.box)
                        }
                        docBox = q0?.let { boxOf(it, bmp.width, bmp.height) }
                        val quad = q0?.let { toSpace(it, bmp.width, bmp.height, pw, ph) }
                        var base = defaultEdits(m, quad)
                        // tipo de documento -> modo y filtro (solo si el usuario no eligió otro filtro)
                        JevCapture.typeEdits(JevCapture.confidentType(hints), m, base)?.let { base = it }
                        // Hoja girada 90° (renglones verticales) o del revés: se endereza SOLO si es muy fiable; el giro
                        // queda en las ediciones (el usuario lo cambia o deshace en el editor y la IA no vuelve a girar)
                        val rot = if (autoDetect && (m == ScanMode.DOCUMENT || m == ScanMode.RECEIPT) && base.aiHint != "photo") {
                            runCatching { processor.autoOrientation(bmp, q0) }.getOrDefault(0)
                        } else 0
                        if (rot != 0) base.copy(rotation = rot) else base
                    }
                    val (processedName, thumbName) = try {
                        renderAndSave(docId, pageId, bmp, edits, pw, ph)
                    } catch (t: Throwable) {
                        if (!isOutOfMemory(t)) throw t
                        // Sin memoria a resolución completa: se libera el bitmap y se reintenta con menos píxeles.
                        bmp.recycle(); bitmap = null
                        renderFromOriginal(docId, pageId, dst, edits, pw, ph, (tier.maxWorkingPixels * 0.6).toInt())
                    }
                    val page = Page(
                        id = pageId,
                        originalFile = originalName,
                        processedFile = processedName,
                        thumbFile = thumbName,
                        width = pw,
                        height = ph,
                        edits = edits,
                        issues = JevCapture.issues(capture, docBox).takeIf { it.isNotEmpty() },
                    )
                    appendPage(docId, page)
                    page
                } catch (t: Throwable) {
                    withContext(NonCancellable + Dispatchers.IO) { discardPageFiles(docId, dir, pageId) }
                    throw t
                } finally {
                    bitmap?.recycle()
                }
            }
        }
        JevCapture.suggestedFolder(capture)?.let { suggestCategory(docId, it) }
        enqueueOcr(docId, page.id)
        checkDuplicate(docId, page.id)
        aiHook?.onPageAdded(docId, page.id)
        return page
    }

    /** Añade páginas importadas desde la galería. [mode] = modo con el que se importan (null = modo del documento). */
    suspend fun addPagesFromUris(docId: String, uris: List<Uri>, mode: ScanMode? = null): List<Page> {
        val pages = ArrayList<Page>(uris.size)
        var lastError: Throwable? = null
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val tmp = withContext(Dispatchers.IO) {
                importDir.mkdirs()
                val f = File(importDir, "imp_${System.nanoTime()}.jpg")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { input.copyTo(it, BUFFER) }
                    } ?: throw IOException("No se pudo abrir la imagen")
                    f
                } catch (t: Throwable) {
                    f.delete()
                    lastError = t
                    null
                }
            } ?: continue
            try {
                pages += addPageFromFile(docId, tmp, autoDetect = true, mode = mode)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo importar $uri", t)
                lastError = t
            } finally {
                tmp.delete()
            }
        }
        if (pages.isEmpty() && uris.isNotEmpty()) {
            throw IOException("No se pudo importar ninguna imagen", lastError)
        }
        return pages
    }

    /**
     * Añade una página ya generada (ej. libro dividido o DNI compuesto) cuyo original es el bitmap dado.
     * [edits] (quad incluido) está en coordenadas de [bitmap]. El original se guarda a tamaño completo; el render
     * se hace sobre una copia reducida a [DeviceTier.maxWorkingPixels] si hace falta. No recicla [bitmap].
     */
    suspend fun addPageFromBitmap(docId: String, bitmap: Bitmap, edits: PageEdits = PageEdits()): Page {
        loaded.await()
        get(docId) ?: throw IOException("Documento no encontrado")
        checkAlive(docId)
        val pageId = newId()
        val dir = docDir(docId)
        val originalName = "$ORIGINAL_DIR/$pageId.jpg"
        val pw = bitmap.width
        val ph = bitmap.height
        val page = heavyWork {
            withContext(Dispatchers.Default) {
                var work: Bitmap? = null
                try {
                    withContext(Dispatchers.IO) {
                        checkAlive(docId)
                        File(dir, ORIGINAL_DIR).mkdirs()
                        // Original GENERADO (DNI compuesto...): JPEG 100, la única codificación antes del procesado.
                        BitmapIO.saveJpeg(bitmap, File(dir, originalName).absolutePath, ORIGINAL_QUALITY)
                    }
                    val px = pw.toLong() * ph
                    val src = if (tier.maxWorkingPixels > 0 && px > tier.maxWorkingPixels) {
                        val s = kotlin.math.sqrt(tier.maxWorkingPixels.toDouble() / px)
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (pw * s).toInt().coerceAtLeast(1),
                            (ph * s).toInt().coerceAtLeast(1),
                            true,
                        ).also { if (it !== bitmap) work = it }
                    } else bitmap
                    val (processedName, thumbName) = renderAndSave(docId, pageId, src, edits, pw, ph)
                    val page = Page(
                        id = pageId,
                        originalFile = originalName,
                        processedFile = processedName,
                        thumbFile = thumbName,
                        width = pw,
                        height = ph,
                        edits = edits,
                    )
                    appendPage(docId, page)
                    page
                } catch (t: Throwable) {
                    withContext(NonCancellable + Dispatchers.IO) { discardPageFiles(docId, dir, pageId) }
                    throw t
                } finally {
                    work?.recycle()
                }
            }
        }
        enqueueOcr(docId, page.id)
        checkDuplicate(docId, page.id)
        aiHook?.onPageAdded(docId, page.id)
        return page
    }

    suspend fun updateEdits(docId: String, pageId: String, edits: PageEdits): Page {
        loaded.await()
        val key = "$docId/$pageId"
        val gen = editGenerations.getOrPut(key) { AtomicLong() }.incrementAndGet()
        return pageLock(key).withLock {
            val current = findPage(docId, pageId) ?: throw IOException("Página no encontrada")
            // Llegó una edición más nueva mientras esperábamos: ella hará el trabajo.
            if (editGenerations[key]?.get() != gen) return@withLock current

            val dir = docDir(docId)
            val newProcessed = processedName(pageId, edits)
            if (current.edits == edits && current.processedFile == newProcessed &&
                File(dir, newProcessed).exists()
            ) {
                return@withLock current
            }

            // Render final ya hecho en segundo plano por el editor para ESTAS ediciones ([renderDraft]): es exactamente
            // el mismo render (mismo original, presupuesto y tubería), así que se reutiliza sin volver a procesar.
            val draftThumb = thumbName(pageId, edits)
            val (processedName, thumbName) = if (File(dir, newProcessed).exists() && File(dir, draftThumb).exists()) {
                newProcessed to draftThumb
            } else heavyWork {
                withContext(Dispatchers.Default) {
                    renderFromOriginal(docId, pageId, File(dir, current.originalFile), edits, current.width, current.height)
                }
            }

            var result: Page = current
            mutatePages(docId) { pages ->
                pages.map { p ->
                    if (p.id != pageId) p else {
                        cleanupStale(dir, p, keepProcessed = processedName, keepThumb = thumbName)
                        // El OCR guardado deja de ser válido solo si la imagen cambió.
                        val editsChanged = p.edits != edits
                        if (editsChanged) {
                            p.ocrFile?.let { File(dir, it).delete() }
                            File(dir, PageFiles.layoutName(pageId, p.edits)).delete()
                            ocrFailFile(docId, pageId).delete() // imagen nueva: se vuelve a intentar
                        }
                        p.copy(
                            edits = edits,
                            processedFile = processedName,
                            thumbFile = thumbName,
                            // El texto corregido por el usuario se conserva (se realinea con el nuevo OCR); si no
                            // hay corrección se mantiene el texto anterior como aproximación para la búsqueda
                            // hasta que el nuevo OCR lo sustituya.
                            ocrText = p.ocrEditedText ?: p.ocrText,
                            ocrFile = if (editsChanged) null else p.ocrFile,
                        ).also { result = it }
                    }
                }
            }
            if (result.ocrFile == null) {
                enqueueOcr(docId, pageId)
                aiHook?.onPageChanged(docId, pageId)
            }
            result
        }
    }

    /**
     * Render FINAL (el mismo de [updateEdits]: PageProcessor.render a resolución completa) de [edits] sin guardarlas
     * todavía: el editor lo muestra como "resultado final" y, si el usuario guarda esas mismas ediciones,
     * [updateEdits] lo reutiliza sin volver a procesar. Devuelve el procesado. Cancelable entre fases del render.
     * Los borradores no guardados se borran con [discardDrafts] (o en el siguiente guardado).
     */
    suspend fun renderDraft(docId: String, pageId: String, edits: PageEdits): File {
        loaded.await()
        val current = findPage(docId, pageId) ?: throw IOException("Página no encontrada")
        val dir = docDir(docId)
        val pName = processedName(pageId, edits)
        val tName = thumbName(pageId, edits)
        if (File(dir, pName).exists() && File(dir, tName).exists()) return File(dir, pName)
        val job = currentCoroutineContext()[Job]
        val cancelCheck: ProgressCallback = { job?.ensureActive() }
        val (processed, _) = heavyWork {
            withContext(Dispatchers.Default) {
                renderFromOriginal(docId, pageId, File(dir, current.originalFile), edits, current.width, current.height, progress = cancelCheck)
            }
        }
        return File(dir, processed)
    }

    /** Borra los borradores de [renderDraft] que no se guardaron (conserva el procesado y la miniatura actuales). */
    suspend fun discardDrafts(docId: String, pageId: String) {
        loaded.await()
        pageLock("$docId/$pageId").withLock {
            val p = findPage(docId, pageId) ?: return@withLock
            withContext(Dispatchers.IO) {
                cleanupStale(docDir(docId), p, keepProcessed = processedName(p.id, p.edits), keepThumb = thumbName(p.id, p.edits))
            }
        }
    }

    suspend fun deletePage(docId: String, pageId: String) {
        // (el aviso de duplicado de otra página que apuntaba a esta ya no tiene sentido)
        mutatePages(docId) { pages -> pages.filterNot { it.id == pageId }.map { if (it.dupOf == pageId) it.copy(dupOf = null) else it } }
        withContext(Dispatchers.IO) { deletePageFiles(docDir(docId), pageId) }
        pageLocks.remove("$docId/$pageId")
        editGenerations.remove("$docId/$pageId")
    }

    suspend fun reorderPages(docId: String, orderedPageIds: List<String>) {
        mutatePages(docId) { pages ->
            val byId = pages.associateBy { it.id }
            val ordered = orderedPageIds.distinct().mapNotNull { byId[it] }
            val rest = pages.filter { it.id !in orderedPageIds }
            ordered + rest
        }
    }

    /**
     * Guarda el OCR de la página. El texto corregido por el usuario vive en [Page.ocrEditedText] (no en el JSON):
     * si [result] trae [OcrResult.editedText] distinto del reconocido se guarda como corrección; si no, se conserva
     * la corrección que ya tuviera la página (se realinea con las nuevas líneas al exportar).
     */
    suspend fun saveOcr(docId: String, pageId: String, result: OcrResult) {
        loaded.await()
        val page = findPage(docId, pageId) ?: return
        saveOcrFor(docId, pageId, page.edits, result)
    }

    /**
     * Guarda el OCR reconocido sobre la imagen de [edits]. El nombre del archivo lleva el hash de las ediciones y
     * la comprobación "las ediciones no cambiaron" se hace dentro de la transformación atómica: un OCR de una
     * versión anterior de la página nunca queda asociado a la nueva (capa invisible desplazada o del revés).
     * Es una actualización recuperable: doc.json se escribe diferido y sin fsync.
     */
    private suspend fun saveOcrFor(
        docId: String,
        pageId: String,
        edits: PageEdits,
        result: OcrResult,
        clearEdited: Boolean = false,
        fromAi: Boolean = false,
    ) {
        if (docId in deletedIds) return
        val dir = docDir(docId)
        val name = ocrName(pageId, edits)
        // El OCR de la IA (con su maquetación) manda: un OCR de fondo que termina después no lo pisa.
        if (!fromAi && withContext(Dispatchers.IO) { File(dir, PageFiles.layoutName(pageId, edits)).exists() }) return
        val edited = result.editedText?.takeIf { it.isNotBlank() && it != result.text }
        withContext(Dispatchers.IO) {
            val f = File(dir, name)
            f.parentFile?.mkdirs()
            writeAtomic(f, json.encodeToString(OcrResult.serializer(), result.copy(editedText = null)), sync = false)
            ocrFailFile(docId, pageId).delete()
        }
        var attached = false
        mutatePagesIfPresent(docId, durable = false) { pages ->
            pages.map {
                if (it.id != pageId || it.edits != edits) it else {
                    attached = true
                    it.ocrFile?.takeIf { old -> old != name }?.let { old -> File(dir, old).delete() }
                    val keepEdited = if (clearEdited) edited else edited ?: it.ocrEditedText
                    it.copy(ocrText = keepEdited ?: result.text, ocrFile = name, ocrEditedText = keepEdited)
                }
            }
        }
        if (!attached) withContext(Dispatchers.IO) { File(dir, name).delete() }
    }

    /**
     * Guarda (o borra con null/vacío) el texto corregido por el usuario. Se usa en el texto visible, .txt/.docx y,
     * alineado con las líneas detectadas, en la capa invisible del PDF.
     */
    suspend fun saveEditedText(docId: String, pageId: String, text: String?) {
        val recognized = loadOcr(docId, pageId)?.text
        val clean = text?.takeIf { it.isNotBlank() && it != recognized }
        mutatePages(docId) { pages ->
            pages.map { if (it.id == pageId) it.copy(ocrEditedText = clean, ocrText = clean ?: recognized ?: it.ocrText) else it }
        }
    }

    /** OCR guardado de la página, con el texto corregido del usuario en [OcrResult.editedText] (si lo hay). */
    suspend fun loadOcr(docId: String, pageId: String): OcrResult? {
        val page = findPage(docId, pageId) ?: return null
        val name = page.ocrFile ?: return null
        return withContext(Dispatchers.IO) {
            val f = File(docDir(docId), name)
            if (!f.exists()) return@withContext null
            try {
                json.decodeFromString(OcrResult.serializer(), f.readText()).copy(editedText = page.ocrEditedText)
            } catch (t: Throwable) {
                Log.w(TAG, "OCR corrupto en $f", t)
                null
            }
        }
    }

    fun docDir(docId: String): File = File(root, docId)

    fun originalFile(docId: String, page: Page): File = File(docDir(docId), page.originalFile)

    /** Archivo procesado (lo genera si falta o está desactualizado). */
    suspend fun processedFile(docId: String, page: Page): File {
        loaded.await()
        val key = "$docId/${page.id}"
        return pageLock(key).withLock {
            // Usa la versión más reciente de la página (la recibida puede estar desactualizada).
            val current = findPage(docId, page.id) ?: page
            val dir = docDir(docId)
            val expected = processedName(current.id, current.edits)
            val expectedFile = File(dir, expected)
            if (expectedFile.exists()) {
                if (current.processedFile != expected) {
                    mutatePagesIfPresent(docId, durable = false) { pages ->
                        pages.map { if (it.id == current.id && it.edits == current.edits) it.copy(processedFile = expected) else it }
                    }
                }
                // Regenera la miniatura si se perdió (barato a partir del procesado).
                // (también si la que figura es de otra versión de las ediciones: la miniatura sale SIEMPRE de este procesado)
                val thumb = current.thumbFile
                if (!PageFiles.thumbMatches(thumb, current.id, current.edits) || !File(dir, thumb!!).exists()) {
                    regenerateThumb(docId, current, expectedFile)
                }
                return@withLock expectedFile
            }

            val (processedName, thumbName) = heavyWork {
                withContext(Dispatchers.Default) {
                    renderFromOriginal(docId, current.id, File(dir, current.originalFile), current.edits, current.width, current.height)
                }
            }
            mutatePagesIfPresent(docId) { pages ->
                pages.map { p ->
                    if (p.id == current.id && p.edits == current.edits) {
                        cleanupStale(dir, p, keepProcessed = processedName, keepThumb = thumbName)
                        p.copy(processedFile = processedName, thumbFile = thumbName)
                    } else p
                }
            }
            File(dir, processedName)
        }
    }

    /** Miniaturas ya comprobadas por [upgradeSmallThumb] (clave = archivo de miniatura). */
    private val checkedThumbs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val thumbUpgrade = Mutex()

    /**
     * Documentos de versiones anteriores tienen miniaturas de 480 px (se ven borrosas en celdas grandes): si la
     * miniatura es claramente menor que [THUMB_SIDE] y el procesado da para más, se regenera (una a la vez, barato:
     * se decodifica el procesado a ~2 MP). Devuelve true si la regeneró.
     */
    suspend fun upgradeSmallThumb(docId: String, page: Page): Boolean {
        val name = page.thumbFile ?: return false
        val processedName = page.processedFile ?: return false
        if (!checkedThumbs.add("$docId/$name")) return false
        return thumbUpgrade.withLock {
            val dir = docDir(docId)
            val thumb = File(dir, name)
            val processed = File(dir, processedName)
            val small = withContext(Dispatchers.IO) {
                if (!thumb.exists() || !processed.exists()) return@withContext false
                val (tw, th) = BitmapIO.decodeBounds(thumb.absolutePath)
                val (pw, ph) = BitmapIO.decodeBounds(processed.absolutePath)
                val tl = maxOf(tw, th); val pl = maxOf(pw, ph)
                tl in 1 until (THUMB_SIDE * 9 / 10) && pl > tl * 11 / 10
            }
            if (!small) return@withLock false
            val current = findPage(docId, page.id) ?: return@withLock false
            if (current.thumbFile != name || current.processedFile != processedName) return@withLock false
            try {
                regenerateThumb(docId, current, processed)
                true
            } catch (c: kotlinx.coroutines.CancellationException) {
                checkedThumbs.remove("$docId/$name")
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo regenerar la miniatura", t)
                false
            }
        }
    }

    /**
     * Miniatura de la página SOLO si corresponde a su procesado actual (mismo hash de ediciones): una miniatura de
     * otra versión nunca se muestra (null -> [processedFile] la regenera desde el procesado).
     */
    fun thumbFile(docId: String, page: Page): File? {
        val name = page.thumbFile ?: return null
        if (!PageFiles.thumbMatches(name, page.id, page.edits)) return null
        val f = File(docDir(docId), name)
        return if (f.exists()) f else null
    }

    /** Procesado ACTUAL de la página si ya existe en disco (sin renderizar nunca; null si falta). */
    fun processedFileIfReady(docId: String, page: Page): File? {
        val f = File(docDir(docId), processedName(page.id, page.edits))
        return if (f.exists()) f else null
    }

    /**
     * Imagen para miniaturas/listas (llamar fuera del hilo principal): la miniatura del procesado actual o, si aún
     * no existe, el propio procesado. Nunca el original sin procesar ni una versión anterior. Con versión (fecha y
     * tamaño) para la clave de caché de Coil.
     */
    fun displayThumb(docId: String, page: Page): VersionedFile? =
        VersionedFile.of(thumbFile(docId, page)) ?: VersionedFile.of(processedFileIfReady(docId, page))

    /** Archivo temporal para capturas de cámara. */
    fun newCaptureFile(): File {
        captureDir.mkdirs()
        return File(captureDir, "cap_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.jpg")
    }

    // ------------------------------------------------------------------ extras públicos

    /** Página concreta del documento (null si no existe). */
    suspend fun getPage(docId: String, pageId: String): Page? = findPage(docId, pageId)

    /** Archivos procesados de todas las páginas, en orden (los genera si faltan). Útil para exportar. */
    suspend fun processedFiles(docId: String): List<File> {
        val doc = get(docId) ?: return emptyList()
        return doc.pages.map { processedFile(docId, it) }
    }

    /** Fusiona el documento [sourceId] al final de [targetId] (copia los archivos) y borra el origen. */
    suspend fun mergeInto(targetId: String, sourceId: String) {
        loaded.await()
        val source = get(sourceId) ?: return
        get(targetId) ?: throw IOException("Documento no encontrado")
        val copied = withContext(Dispatchers.IO) {
            val src = docDir(sourceId)
            val dst = docDir(targetId)
            source.pages.map { p ->
                val newId = newId()
                fun cp(name: String?): String? {
                    name ?: return null
                    val from = File(src, name)
                    if (!from.exists()) return null
                    val newName = name.replace(p.id, newId)
                    val to = File(dst, newName)
                    to.parentFile?.mkdirs()
                    from.copyTo(to, overwrite = true)
                    return newName
                }
                // La maquetación sigue a la página (mismo hash de ediciones).
                cp(PageFiles.layoutName(p.id, p.edits))
                p.copy(
                    id = newId,
                    originalFile = cp(p.originalFile) ?: p.originalFile,
                    processedFile = cp(p.processedFile),
                    thumbFile = cp(p.thumbFile),
                    ocrFile = cp(p.ocrFile),
                )
            }
        }
        mutatePages(targetId) { it + copied }
        delete(sourceId)
    }

    // ------------------------------------------------------------------ internos

    private fun pageLock(key: String): Mutex = pageLocks.getOrPut(key) { Mutex() }

    private suspend fun findPage(docId: String, pageId: String): Page? =
        get(docId)?.pages?.firstOrNull { it.id == pageId }

    /** Detecta el documento y devuelve el quad en coordenadas del bitmap, o null si la confianza es baja. */
    private fun detectQuad(bmp: Bitmap): Quad? = detectQuadWithConfidence(bmp)?.first

    // ------------------------------------------------------------------ "Mejorar con Jev" en la captura

    /** Preguntas de la captura sobre la foto [bmp] (caché `jev/<página>_capture.json`). null = sin Jev. */
    private fun captureHints(bmp: Bitmap, dir: File, pageId: String): CaptureHints? {
        val j = jev?.takeIf { it.captureEnabled() } ?: return null
        return runCatching {
            val (m, _) = Cv.toRgbaScaled(bmp, JevCapture.SIDE)
            try { j.captureHints(m, File(dir, PageFiles.jevCaptureName(pageId))) } finally { m.release() }
        }.getOrNull()
    }

    /** El cuadrilátero es (casi) la imagen entera: el detector "cayó" a la foto completa. */
    private fun nearlyFull(q: Quad, w: Int, h: Int): Boolean = polyArea(q) > 0.97f * w * h

    private fun polyArea(q: Quad): Float {
        val p = q.points()
        var a = 0f
        for (i in p.indices) { val u = p[i]; val v = p[(i + 1) % p.size]; a += u.x * v.y - v.x * u.y }
        return kotlin.math.abs(a) / 2f
    }

    /** Caja envolvente del quad en fracciones de la foto [w] x [h]. */
    private fun boxOf(q: Quad, w: Int, h: Int): JevBox {
        val p = q.points()
        return JevBox(p.minOf { it.x }.toDouble() / w, p.minOf { it.y }.toDouble() / h, p.maxOf { it.x }.toDouble() / w, p.maxOf { it.y }.toDouble() / h)
    }

    /**
     * Rescate del encuadre: vuelve a lanzar el detector clásico DENTRO de la zona de Jev ([JevCapture.Framing.Roi.roi], la
     * caja ampliada; fracciones de [bmp]) y, si tampoco da un cuadrilátero creíble ([JevCapture.roiQuadOk]), recorta por
     * la caja de Jev ([JevCapture.Framing.Roi.fallback]). Quad en coordenadas de [bmp].
     */
    private fun rescueQuad(bmp: Bitmap, f: JevCapture.Framing.Roi, box: JevBox?): Quad {
        val w = bmp.width; val h = bmp.height
        val roi = f.roi
        val x0 = (roi.left * w).toInt().coerceIn(0, w - 1); val y0 = (roi.top * h).toInt().coerceIn(0, h - 1)
        val x1 = (roi.right * w).toInt().coerceIn(x0 + 1, w); val y1 = (roi.bottom * h).toInt().coerceIn(y0 + 1, h)
        val fb = f.fallback
        val fx0 = (fb.left * w).toFloat(); val fy0 = (fb.top * h).toFloat(); val fx1 = (fb.right * w).toFloat(); val fy1 = (fb.bottom * h).toFloat()
        val rect = Quad(Pt(fx0, fy0), Pt(fx1, fy0), Pt(fx1, fy1), Pt(fx0, fy1))
        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
            val det = processor.detector.detect(crop, useNeural = true)
            if (det == null || det.confidence < MIN_CONFIDENCE || det.frameWidth <= 0) return rect
            val q = detQuadScaled(crop, det).let { c -> Quad.of(c.points().map { Pt(it.x + x0, it.y + y0) }) }
            val f = FloatArray(8) { i -> q.points()[i / 2].let { p -> if (i % 2 == 0) p.x / w else p.y / h } }
            if (JevCapture.roiQuadOk(f, roi, box)) q else rect
        } catch (t: Throwable) {
            if (isOutOfMemory(t)) throw t
            Log.w(TAG, "Fallo al volver a detectar en la zona de Jev", t)
            rect
        } finally {
            if (crop != null && crop !== bmp) crop.recycle()
        }
    }

    /** Cambia el documento bajo el candado sin tocar updatedAt (sugerencias, carpeta). */
    private suspend fun mutateDocIfPresent(docId: String, durable: Boolean, transform: (Document) -> Document) {
        loaded.await()
        val changed = writeMutex.withLock {
            val d = docs[docId] ?: return@withLock false
            val u = transform(d)
            if (u == d) return@withLock false
            docs[docId] = u; publish(); true
        }
        if (changed) persist(docId, durable)
    }

    /** Sugerencia de carpeta para el documento (solo si no tiene carpeta ni otra sugerencia, aceptada o descartada). */
    private suspend fun suggestCategory(docId: String, folder: String) = runCatching {
        mutateDocIfPresent(docId, durable = false) { d -> if (d.category != null || d.suggestedCategory != null) d else d.copy(suggestedCategory = folder) }
    }

    /** Pone (o quita, con null) la carpeta del documento; la sugerencia queda resuelta ("" = no se vuelve a sugerir). */
    suspend fun setCategory(docId: String, category: String?) =
        mutateDocIfPresent(docId, durable = true) { it.copy(category = category, suggestedCategory = "") }

    /** Descarta la carpeta sugerida (no se vuelve a sugerir). */
    suspend fun dismissSuggestedCategory(docId: String) =
        mutateDocIfPresent(docId, durable = true) { it.copy(suggestedCategory = "") }

    /** Quita los avisos de calidad de la página (el usuario los descartó). */
    suspend fun dismissIssues(docId: String, pageId: String) =
        mutatePagesIfPresent(docId) { pages -> pages.map { if (it.id == pageId) it.copy(issues = null) else it } }

    /** Descarta el aviso de duplicado de la página. */
    suspend fun dismissDuplicate(docId: String, pageId: String) =
        mutatePagesIfPresent(docId) { pages -> pages.map { if (it.id == pageId) it.copy(dupOf = null) else it } }

    /** Rasgos ORB de los procesados recientes (los duplicados se buscan entre las últimas páginas del lote). */
    private val dupFeatures = object : LinkedHashMap<String, DuplicatePages.Features>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, DuplicatePages.Features>?): Boolean =
            (size > DUP_CACHE).also { if (it) e?.value?.release() }
    }
    private val dupMutex = Mutex()

    /**
     * Páginas duplicadas del lote (en segundo plano, sin red): ORB+RANSAC de la página nueva contra las [DUP_WINDOW]
     * anteriores; ≥ 40 coincidencias = duplicado seguro; en la zona gris decide Jev si está activo (sin Jev, no se
     * avisa). Si es duplicado se marca [Page.dupOf] y la revisión avisa.
     */
    private fun checkDuplicate(docId: String, pageId: String) {
        scope.launch {
            try {
                awaitOcrIdle()
                val doc = get(docId) ?: return@launch
                val idx = doc.pages.indexOfFirst { it.id == pageId }
                if (idx <= 0) return@launch
                val page = doc.pages[idx]
                val others = doc.pages.subList(maxOf(0, idx - DUP_WINDOW), idx).reversed()
                dupMutex.withLock {
                    val fa = dupFeaturesOf(docId, page) ?: return@launch
                    for (o in others) {
                        val fb = dupFeaturesOf(docId, o) ?: continue
                        val dup = when (DuplicatePages.verdict(DuplicatePages.inliers(fa, fb))) {
                            DuplicatePages.Verdict.DUPLICATE -> true
                            DuplicatePages.Verdict.DIFFERENT -> false
                            DuplicatePages.Verdict.GRAY -> grayDuplicate(docId, page, o)
                        }
                        if (dup) {
                            mutatePagesIfPresent(docId, durable = false) { ps -> if (ps.none { it.id == o.id }) ps else ps.map { if (it.id == pageId) it.copy(dupOf = o.id) else it } }
                            break
                        }
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo buscar duplicados", t)
            }
        }
    }

    private suspend fun dupFeaturesOf(docId: String, p: Page): DuplicatePages.Features? {
        val f = processedFileIfReady(docId, p) ?: return null
        val key = f.absolutePath
        dupFeatures[key]?.let { return it }
        return withContext(Dispatchers.Default) {
            val b = BitmapIO.decode(f.absolutePath, DuplicatePages.SIDE * DuplicatePages.SIDE * 2)
            try {
                val rgba = Cv.toRgba(b)
                val gray = Cv.gray(rgba); rgba.release()
                try { DuplicatePages.features(gray) } finally { gray.release() }
            } finally { b.recycle() }
        }.also { dupFeatures[key] = it }
    }

    /** Zona gris: ¿la misma hoja según Jev? (false sin Jev o si falla). */
    private suspend fun grayDuplicate(docId: String, a: Page, b: Page): Boolean {
        val j = jev?.takeIf { it.settings.value.enabled && it.hasKey() } ?: return false
        val fa = processedFileIfReady(docId, a) ?: return false
        val fb = processedFileIfReady(docId, b) ?: return false
        return withContext(Dispatchers.Default) {
            val px = JevDuplicates.SIDE * JevDuplicates.SIDE * 2
            val ba = BitmapIO.decode(fa.absolutePath, px); val bb = BitmapIO.decode(fb.absolutePath, px)
            val ma = Cv.toRgba(ba); val mb = Cv.toRgba(bb)
            ba.recycle(); bb.recycle()
            try { (j.samePage(ma, mb) ?: 0.0) >= JevDuplicates.SAME } finally { ma.release(); mb.release() }
        }
    }


    /**
     * Como [detectQuad] pero con la confianza del detector: (quad o null si es baja, confianza). null si el detector
     * no devolvió nada (cae a "imagen completa").
     */
    private fun detectQuadWithConfidence(bmp: Bitmap): Pair<Quad?, Float?>? {
        val det = try {
            processor.detector.detect(bmp, useNeural = true)
        } catch (t: Throwable) {
            Log.w(TAG, "Fallo en detección de bordes", t)
            null
        } ?: return null
        if (det.confidence < MIN_CONFIDENCE || det.frameWidth <= 0 || det.frameHeight <= 0) return null to det.confidence
        return detQuadScaled(bmp, det) to det.confidence
    }

    private fun detQuadScaled(bmp: Bitmap, det: com.scannerpromax.imaging.DetectionResult): Quad {
        val sx = bmp.width.toFloat() / det.frameWidth
        val sy = bmp.height.toFloat() / det.frameHeight
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        fun scale(p: Pt) = Pt((p.x * sx).coerceIn(0f, w), (p.y * sy).coerceIn(0f, h))
        val q = det.quad
        return Quad(scale(q.tl), scale(q.tr), scale(q.br), scale(q.bl))
    }

    private fun defaultEdits(mode: ScanMode, quad: Quad?): PageEdits {
        val docFilter = preferredFilter
        return when (mode) {
            ScanMode.PHOTO -> PageEdits(quad = null, filter = FilterType.ORIGINAL, autoDenoise = false, autoDeskew = false, autoDewarp = false)
            ScanMode.WHITEBOARD -> PageEdits(quad = quad, filter = FilterType.WHITEBOARD, autoDeskew = false, autoDewarp = false)
            ScanMode.ID_CARD -> PageEdits(quad = quad, filter = docFilter ?: FilterType.DEFAULT, autoDeskew = false, autoDewarp = false)
            ScanMode.DOCUMENT, ScanMode.RECEIPT, ScanMode.BOOK -> PageEdits(
                quad = quad,
                filter = docFilter ?: FilterType.DEFAULT,
                autoRemoveLines = defaultAutoRemoveLines,
            )
        }
    }

    /**
     * Decodifica el original (EXIF respetado) con el presupuesto [budget] y renderiza procesado + miniatura.
     * Red de seguridad en gama baja: si el render se queda sin memoria (Java o nativa de OpenCV) se reintenta con
     * el 60 % de los píxeles, sin bajar de [MIN_RETRY_PIXELS]. El procesado sale siempre a la resolución del recorte
     * dentro del presupuesto (nunca se reduce "por si acaso").
     */
    private suspend fun renderFromOriginal(
        docId: String,
        pageId: String,
        originalFile: File,
        edits: PageEdits,
        pageW: Int,
        pageH: Int,
        budget: Int = tier.maxWorkingPixels,
        progress: ProgressCallback? = null,
    ): Pair<String, String> {
        var px = budget
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val original = BitmapIO.decode(originalFile.absolutePath, px)
                try {
                    return renderAndSave(docId, pageId, original, edits, pageW, pageH, progress)
                } finally {
                    original.recycle()
                }
            } catch (t: Throwable) {
                if (!isOutOfMemory(t) || px <= MIN_RETRY_PIXELS) throw t
                Log.w(TAG, "Sin memoria al procesar a ${px / 1_000_000f} MP; se reintenta con menos píxeles", t)
                System.gc()
                px = maxOf(MIN_RETRY_PIXELS, (px * 0.6).toInt())
            }
        }
    }

    /**
     * Cajas de foto (imagen, figura, gráfico, sello; normalizadas 0..1) de la maquetación de la IA guardada para la página
     * con la MISMA geometría que [edits] ([PageFiles.sameGeometry]: las coordenadas valen aunque cambien el filtro o los
     * ajustes): la de estas ediciones (se regenera el procesado) o la de la versión actual de la página. Vacía si no hay
     * (motor básico, aún en cola) o no se puede leer: comportamiento de siempre.
     */
    private suspend fun layoutPhotoBoxes(docId: String, pageId: String, edits: PageEdits): List<FloatArray> {
        val current = runCatching { findPage(docId, pageId)?.edits }.getOrNull()
        val dir = docDir(docId)
        return withContext(Dispatchers.IO) { runCatching {
            val cands = listOfNotNull(edits, current?.takeIf { it != edits && PageFiles.sameGeometry(it, edits) })
            for (e in cands) {
                val layout = com.scannerpromax.pdf.DocLayoutFiles.read(File(dir, PageFiles.processedName(pageId, e))) ?: continue
                return@runCatching layout.photoBoxes()
            }
            emptyList<FloatArray>()
        }.getOrDefault(emptyList()) }
    }

    /**
     * Renderiza procesado + miniatura y los guarda. NO recicla [original].
     * [edits] está en el espacio de la página ([pageW] x [pageH]); si [original] se decodificó a otro tamaño
     * el quad se reescala antes de renderizar (los trazos de borrado ya son normalizados).
     * Las páginas binarizadas (B/N, Ahorro tinta) se guardan en PNG: sin halos de JPEG y más ligeras.
     */
    private suspend fun renderAndSave(
        docId: String,
        pageId: String,
        original: Bitmap,
        edits: PageEdits,
        pageW: Int,
        pageH: Int,
        progress: ProgressCallback? = null,
    ): Pair<String, String> {
        val dir = docDir(docId)
        val processedName = processedName(pageId, edits)
        val thumbName = thumbName(pageId, edits)
        val workEdits = edits.quad?.let { q -> edits.copy(quad = toSpace(q, pageW, pageH, original.width, original.height)) } ?: edits
        // Cajas de foto de la maquetación guardada (PP-DocLayoutV3): "Color natural" las conserva como foto
        val photos = if (edits.filter == FilterType.NATURAL) layoutPhotoBoxes(docId, pageId, edits) else emptyList()
        val rendered = processor.render(original, workEdits, progress, photos, jev = jevHook(dir, pageId, edits))
        try {
            currentCoroutineContext().ensureActive()
            val thumb = BitmapIO.thumbnail(rendered, THUMB_SIDE)
            try {
                withContext(Dispatchers.IO) {
                    checkAlive(docId)
                    File(dir, PROCESSED_DIR).mkdirs()
                    File(dir, THUMB_DIR).mkdirs()
                    if (isBinaryFilter(edits.filter)) {
                        saveJpegAtomic(thumb, File(dir, thumbName), THUMB_QUALITY)
                        // El PNG tarda 1-3 s en un Cortex-A53: se suelta el permiso pesado antes de codificarlo
                        // para que la siguiente captura empiece su render (solo quedan vivos dos bitmaps).
                        currentCoroutineContext()[HeavyPermit]?.release()
                        saveAtomic(rendered, File(dir, processedName), Bitmap.CompressFormat.PNG, 100)
                    } else {
                        saveJpegAtomic(rendered, File(dir, processedName), PROCESSED_QUALITY)
                        saveJpegAtomic(thumb, File(dir, thumbName), THUMB_QUALITY)
                    }
                }
            } finally {
                if (thumb !== rendered && thumb !== original) thumb.recycle()
            }
        } finally {
            if (rendered !== original) rendered.recycle()
        }
        return processedName to thumbName
    }

    /**
     * "Mejorar con Jev" (opcional, con la clave del usuario): fotos (solo "Color natural") y tablas (con "Conservar
     * cuadrícula") de la página rectificada; caché en `jev/<página>_<hash de la geometría>.json` (no depende del filtro).
     * null = sin Jev (apagado, sin clave o filtro sin uso): render de siempre.
     */
    private fun jevHook(dir: File, pageId: String, edits: PageEdits): ((org.opencv.core.Mat) -> JevRenderHints?)? {
        val j = jev?.takeIf { it.settings.value.enabled && it.hasKey() } ?: return null
        val wantPhotos = edits.filter == FilterType.NATURAL
        val wantTables = edits.keepRuling && edits.filter.uiFilter != FilterType.ORIGINAL
        // firmas y sellos con su color: solo en "Color natural"
        val wantInks = edits.filter == FilterType.NATURAL
        if (!wantPhotos && !wantTables && !wantInks) return null
        val cache = File(dir, jevCacheName(pageId, edits))
        return { geo -> j.pageHints(geo, cache, wantPhotos, wantTables, wantInks)?.let { JevRenderHints(it.photos, it.tables, it.inks) } }
    }

    private suspend fun regenerateThumb(docId: String, page: Page, processed: File) {
        withContext(Dispatchers.Default) {
            val dir = docDir(docId)
            val name = thumbName(page.id, page.edits)
            val bmp = BitmapIO.decode(processed.absolutePath, THUMB_SIDE * THUMB_SIDE * 2)
            try {
                val thumb = BitmapIO.thumbnail(bmp, THUMB_SIDE)
                try {
                    withContext(Dispatchers.IO) {
                        checkAlive(docId)
                        File(dir, THUMB_DIR).mkdirs()
                        saveJpegAtomic(thumb, File(dir, name), THUMB_QUALITY)
                    }
                } finally {
                    if (thumb !== bmp) thumb.recycle()
                }
            } finally {
                bmp.recycle()
            }
            mutatePagesIfPresent(docId, durable = false) { pages -> pages.map { if (it.id == page.id) it.copy(thumbFile = name) else it } }
        }
    }

    private fun saveJpegAtomic(bmp: Bitmap, target: File, quality: Int) =
        saveAtomic(bmp, target, Bitmap.CompressFormat.JPEG, quality)

    private fun saveAtomic(bmp: Bitmap, target: File, format: Bitmap.CompressFormat, quality: Int) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        BitmapIO.save(bmp, tmp.absolutePath, format, quality)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Borra procesados/miniaturas antiguos de la página distintos de los que se conservan. */
    private fun cleanupStale(dir: File, page: Page, keepProcessed: String, keepThumb: String) {
        // caché de Jev de otras geometrías de la página
        val keepJev = jevCacheName(page.id, page.edits).substringAfter('/')
        File(dir, JEV_DIR).listFiles()?.forEach { f -> if (f.name.startsWith(page.id + "_") && f.name != keepJev) f.delete() }
        File(dir, PROCESSED_DIR).listFiles()?.forEach { f ->
            // (la maquetación de la IA del procesado que se conserva, `<procesado>.layout.json`, también se conserva)
            val rel = "$PROCESSED_DIR/${f.name}"
            if (f.name.startsWith(page.id) && rel != keepProcessed && rel != keepProcessed + PageFiles.LAYOUT_SUFFIX) f.delete()
        }
        File(dir, THUMB_DIR).listFiles()?.forEach { f ->
            if (f.name.startsWith(page.id) && "$THUMB_DIR/${f.name}" != keepThumb) f.delete()
        }
    }

    private fun deletePageFiles(dir: File, pageId: String) {
        SUBDIRS.forEach { sub ->
            val d = File(dir, sub)
            d.listFiles()?.forEach { f -> if (f.name.startsWith(pageId)) f.delete() }
            BitmapIO.cleanupTempFiles(d)
        }
    }

    private suspend fun appendPage(docId: String, page: Page) {
        mutatePages(docId) { it + page }
    }

    private suspend fun mutatePages(docId: String, transform: (List<Page>) -> List<Page>) {
        mutate(docId) { it.copy(pages = transform(it.pages)) }
    }

    /**
     * Como [mutatePages] pero sin fallar si el documento ya no existe y sin tocar updatedAt. [durable] = false para
     * actualizaciones recuperables (OCR, miniaturas, nombre del procesado): doc.json se escribe diferido, agrupado
     * y sin fsync, así las capturas en serie y el OCR de fondo no se bloquean entre sí en la eMMC.
     */
    private suspend fun mutatePagesIfPresent(docId: String, durable: Boolean = true, transform: (List<Page>) -> List<Page>) {
        loaded.await()
        val changed = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val doc = docs[docId]
                if (doc != null) {
                    val updated = doc.copy(pages = transform(doc.pages))
                    if (updated != doc) {
                        docs[docId] = updated
                        publish()
                        true
                    } else false
                } else false
            }
        }
        if (changed) persist(docId, durable)
    }

    /**
     * Aplica [transform] bajo el candado de escritura (en IO: el transform puede borrar archivos) y escribe doc.json
     * (fuera del candado global: solo bloquea a este documento).
     */
    private suspend fun mutate(docId: String, transform: (Document) -> Document) {
        loaded.await()
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val doc = docs[docId] ?: throw IOException("Documento no encontrado")
                val updated = transform(doc).copy(updatedAt = System.currentTimeMillis())
                docs[docId] = updated
                publish()
            }
        }
        persist(docId, durable = true)
    }

    private fun fileLock(docId: String): Mutex = fileLocks.getOrPut(docId) { Mutex() }

    private suspend fun persist(docId: String, durable: Boolean) {
        if (durable) writeLatest(docId, sync = true) else scheduleWrite(docId)
    }

    /** Escritura diferida y agrupada (debounce) de doc.json. */
    private fun scheduleWrite(docId: String) {
        pendingWrites.computeIfAbsent(docId) {
            scope.launch {
                delay(WRITE_DEBOUNCE_MS)
                pendingWrites.remove(docId)
                try {
                    writeLatest(docId, sync = false)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "No se pudo guardar $docId", t)
                }
            }
        }
    }

    /** Escribe la versión más reciente en memoria del documento (si cambió desde la última escritura). */
    private suspend fun writeLatest(docId: String, sync: Boolean) {
        fileLock(docId).withLock {
            val snap = writeMutex.withLock { docs[docId] } ?: return@withLock
            if (docId in deletedIds || lastWritten[docId] === snap) return@withLock
            withContext(Dispatchers.IO) { writeDocJson(snap, sync) }
            lastWritten[docId] = snap
        }
    }

    private fun publish() {
        _documents.value = docs.values.sortedByDescending { it.updatedAt }
    }

    private fun writeDocJson(doc: Document, sync: Boolean = true) {
        val dir = docDir(doc.id)
        dir.mkdirs()
        writeAtomic(File(dir, DOC_JSON), json.encodeToString(Document.serializer(), doc), sync)
    }

    /**
     * Escritura atómica: archivo temporal + rename (nunca deja un JSON a medias). [sync] = fsync antes del rename;
     * se omite en actualizaciones recuperables (OCR, miniaturas) para no pagar un fsync por evento en eMMC lentas.
     */
    private fun writeAtomic(target: File, text: String, sync: Boolean = true) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            if (sync) try { out.fd.sync() } catch (_: Throwable) { }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("No se pudo guardar ${target.name}")
        }
    }

    private suspend fun loadAll() {
        val loadedDocs = withContext(Dispatchers.IO) {
            root.mkdirs()
            root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
                // Temporales '.*.tmp' de escrituras interrumpidas (doc.json.tmp no empieza por '.', se conserva).
                SUBDIRS.forEach { BitmapIO.cleanupTempFiles(File(dir, it)) }
                readDoc(dir) ?: run {
                    // Carpeta huérfana (documento borrado mientras se procesaba una página): sin doc.json no se
                    // puede recuperar nada. Nada puede crear documentos antes de terminar la carga, así que es seguro.
                    if (!File(dir, DOC_JSON).exists() && !File(dir, DOC_JSON_TMP).exists()) {
                        Log.i(TAG, "Borrando carpeta huérfana ${dir.name}")
                        dir.deleteRecursively()
                    }
                    null
                }
            } ?: emptyList()
        }
        writeMutex.withLock {
            loadedDocs.forEach { docs[it.id] = it }
            publish()
        }
    }

    private fun readDoc(dir: File): Document? {
        val main = File(dir, DOC_JSON)
        val tmp = File(dir, DOC_JSON_TMP)
        for (f in listOf(main, tmp)) {
            if (!f.exists()) continue
            try {
                val doc = json.decodeFromString(Document.serializer(), f.readText())
                if (f === tmp) writeAtomic(main, f.readText())
                // Descarta páginas cuyo original desapareció.
                val kept = doc.pages.filter { File(dir, it.originalFile).exists() }
                // "Texto resaltado" -> "Color natural" (FilterType.migrated). Sólo esas páginas cambian de hash.
                val pages = kept.map { PageFiles.migrateFilter(dir, it) }
                if (pages != kept) runCatching { writeAtomic(main, json.encodeToString(Document.serializer(), doc.copy(pages = pages))) }
                    .onFailure { Log.w(TAG, "No se pudo guardar la migración de filtros en $dir", it) }
                return if (pages == doc.pages) doc else doc.copy(pages = pages)
            } catch (t: Throwable) {
                Log.w(TAG, "doc.json ilegible en $dir", t)
            }
        }
        return null
    }

    /**
     * Limpieza de temporales al arrancar: capturas, importaciones, exportaciones y PDF compartidos de más de 1 día;
     * los intermedios del compresor y los temporales de pdfbox se borran enteros (nada los usa al arrancar).
     */
    private fun cleanupCache() {
        val limit = System.currentTimeMillis() - 24L * 3600 * 1000
        val cache = context.cacheDir
        (listOf(captureDir, importDir) + AGED_CACHE_DIRS.map { File(cache, it) }).forEach { d ->
            d.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
        }
        WIPED_CACHE_DIRS.forEach { File(cache, it).listFiles()?.forEach { f -> f.deleteRecursively() } }
    }

    // ------------------------------------------------------------------ OCR

    /** Usa el motor OCR del contenedor (Indra). Llamar una vez al crear el contenedor. */
    fun attachOcrEngine(engine: OcrEngine) {
        // Mismo criterio de gama baja que el resto de la app (RAM total y núcleos, no solo memoryClass).
        engine.setDeviceTier(tier)
        ocrEngine = engine
    }

    private fun ocr(): OcrEngine = ocrEngine ?: synchronized(this) {
        ocrEngine ?: OcrEngine(context, tier).also { ocrEngine = it }
    }

    private val lowEndOcr: Boolean get() = tier.isLowRam || tier.cores <= 4

    /** Píxeles con los que se decodifica la página para el OCR (el motor no usa más que esto en gama baja). */
    private fun ocrDecodePixels(lightweight: Boolean): Int = when {
        lowEndOcr && lightweight -> OCR_BACKGROUND_PIXELS
        lowEndOcr -> OCR_LOW_RAM_PIXELS
        // Indra reduce a 1024 px para detectar y relee los recortes de cada línea: más de ~12 MP no aporta.
        else -> minOf(tier.maxWorkingPixels, OCR_MAX_PIXELS)
    }

    /**
     * Decodifica y reconoce la página procesada [file] con las ediciones [edits] y guarda el resultado. Si el
     * llamador cancela tras al menos una pasada completada, se guarda lo ya reconocido antes de propagar la
     * cancelación (en un A53 cada pasada son 3-6 s de CPU).
     */
    private suspend fun recognizeAndSave(
        docId: String,
        pageId: String,
        file: File,
        edits: PageEdits,
        lightweight: Boolean,
        clearEdited: Boolean = false,
        page: Page? = null,
    ): OcrResult {
        // Indra lee la página SOLO con geometría (sin el filtro que ve el usuario) si se puede: banco
        // notes_indra_afinado.md, mejor que Texto resaltado / B/N. Las cajas se expresan sobre la imagen procesada.
        val geo = if (page != null && !(lowEndOcr && lightweight)) indraSource(docId, page, edits, file, ocrDecodePixels(lightweight)) else null
        val bmp = geo?.first ?: withContext(Dispatchers.Default) { BitmapIO.decode(file.absolutePath, ocrDecodePixels(lightweight)) }
        val partial = OcrEngine.Partial()
        val result = try {
            val r0 = if (geo != null) ocr().recognize(bmp, geo.second.first, geo.second.second, partial)
            else ocr().recognize(bmp, edits.filter, lightweight, partial)
            // "Mejorar con Jev" › OCR: palabras/líneas dudosas verificadas con el recorte (si falla, r0 tal cual)
            jev?.takeIf { it.settings.value.let { s -> s.enabled && s.ocr } }?.let { j ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val m = com.scannerpromax.imaging.Cv.toRgba(bmp)
                        try { j.fixOcr(r0, m) { w -> ocr().indra?.suggestions(w).orEmpty() } } finally { m.release() }
                    }.getOrDefault(r0)
                }
            } ?: r0
        } catch (c: kotlinx.coroutines.CancellationException) {
            partial.result?.let { r ->
                withContext(NonCancellable) {
                    try { saveOcrFor(docId, pageId, edits, r) } catch (t: Throwable) { Log.w(TAG, "No se pudo guardar OCR parcial", t) }
                }
            }
            throw c
        } finally {
            bmp.recycle()
        }
        saveOcrFor(docId, pageId, edits, result, clearEdited)
        return result
    }

    /**
     * Versión de la página que lee Indra: la de SOLO geometría (recorte, perspectiva, enderezado) del original, sin el
     * filtro (Texto resaltado / B/N), a la escala de la procesada como mucho; con su tamaño de salida = el de la
     * procesada [processed] (las cajas se reescalan a ella). null = leer la procesada tal cual (filtro Color original,
     * trazos de borrado del usuario, original ausente, proporciones distintas o error).
     */
    private suspend fun indraSource(docId: String, page: Page, edits: PageEdits, processed: File, decodePx: Int): Pair<Bitmap, Pair<Int, Int>>? {
        if (edits.filter.uiFilter == FilterType.ORIGINAL || edits.eraseStrokes.isNotEmpty()) return null
        val orig = File(docDir(docId), page.originalFile)
        if (!orig.isFile) return null
        return try {
            withContext(Dispatchers.Default) {
                val (pw, ph) = BitmapIO.decodeBounds(processed.absolutePath)
                if (pw <= 0 || ph <= 0) return@withContext null
                val original = BitmapIO.decode(orig.absolutePath, decodePx)
                val geo = try {
                    val work = edits.quad?.let { q -> edits.copy(quad = toSpace(q, page.width, page.height, original.width, original.height)) } ?: edits
                    processor.geometryOnly(original, work, maxSide = minOf(maxOf(pw, ph), INDRA_GEO_MAX_SIDE))
                } finally {
                    original.recycle()
                }
                // La procesada puede estar ampliada (Texto resaltado/B/N) pero no con otras proporciones.
                val ra = geo.width.toFloat() / geo.height; val rb = pw.toFloat() / ph
                if (kotlin.math.abs(ra / rb - 1f) > 0.02f) { geo.recycle(); null } else geo to (pw to ph)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Sin versión de geometría para Indra; se lee la procesada", t)
            null
        }
    }

    /**
     * Devuelve el OCR guardado de la página o lo calcula (sobre la imagen procesada) y lo guarda.
     * null si la página no existe o el reconocimiento falla. Lo usan exportar/compartir y el OCR de fondo.
     */
    suspend fun ensureOcr(docId: String, pageId: String): OcrResult? = ensureOcr(docId, pageId, lightweight = false)

    private suspend fun ensureOcr(docId: String, pageId: String, lightweight: Boolean): OcrResult? {
        loadOcr(docId, pageId)?.let { return it }
        return ocrLock(docId, pageId).withLock {
            loadOcr(docId, pageId)?.let { return@withLock it }
            val page = findPage(docId, pageId) ?: return@withLock null
            try {
                val file = processedFile(docId, page)
                val current = findPage(docId, pageId) ?: return@withLock null
                // Solo se asocia a la página si sus ediciones no cambiaron mientras se reconocía.
                val result = recognizeAndSave(docId, pageId, file, current.edits, lightweight, page = current)
                result.copy(editedText = findPage(docId, pageId)?.ocrEditedText)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (u: com.scannerpromax.ocr.OcrUnavailableException) {
                // Indra aún no está descargado: la página queda pendiente (no cuenta como fallo).
                onOcrUnavailable?.invoke()
                null
            } catch (t: Throwable) {
                Log.w(TAG, "OCR fallido en $docId/$pageId", t)
                null
            }
        }
    }

    /**
     * Vuelve a reconocer la página aunque ya tenga OCR (botón "Volver a reconocer"). Lanza si falla.
     * Si [keepEditedText] es false se descarta el texto corregido por el usuario.
     */
    suspend fun recognizePage(docId: String, pageId: String, keepEditedText: Boolean = false): OcrResult =
        ocrLock(docId, pageId).withLock {
            val page = findPage(docId, pageId) ?: throw IOException("Página no encontrada")
            val file = processedFile(docId, page)
            val current = findPage(docId, pageId) ?: throw IOException("Página no encontrada")
            // "Volver a reconocer": la maquetación anterior ya no corresponde al texto nuevo.
            withContext(Dispatchers.IO) { File(docDir(docId), PageFiles.layoutName(pageId, current.edits)).delete() }
            // El texto corregido se descarta solo si el nuevo reconocimiento sale bien.
            val result = recognizeAndSave(docId, pageId, file, current.edits, lightweight = false, clearEdited = !keepEditedText, page = current)
            result.copy(editedText = if (keepEditedText) current.ocrEditedText else null)
        }

    /**
     * OCR de varias páginas (todas si [pageIds] es null) en orden y de una en una (un solo reconocedor: en gama baja
     * el OCR en paralelo agota la memoria). Las que ya tienen OCR se cargan sin recalcular. [onProgress] recibe
     * (hechas, total) y si la página se tuvo que reconocer. Cancelable entre páginas.
     */
    suspend fun ensureOcrAll(
        docId: String,
        pageIds: List<String>? = null,
        onProgress: (done: Int, total: Int, recognizing: Boolean) -> Unit = { _, _, _ -> },
    ): List<OcrResult?> {
        val ids = pageIds ?: get(docId)?.pages?.map { it.id }.orEmpty()
        val out = ArrayList<OcrResult?>(ids.size)
        ids.forEachIndexed { i, id ->
            currentCoroutineContext().ensureActive()
            val saved = loadOcr(docId, id)
            if (saved != null) {
                out += saved
            } else {
                onProgress(i, ids.size, true)
                out += ensureOcr(docId, id)
            }
            onProgress(i + 1, ids.size, false)
        }
        return out
    }

    // ------------------------------------------------------------------ IA (maquetación + OCR de la IA)

    /**
     * Guarda el resultado de la IA para la versión [edits] de la página: el [ocr] sustituye al OCR guardado (capa de
     * texto del PDF, búsqueda, pantalla de texto; el texto corregido por el usuario se conserva) y [layout] va a
     * `<procesado>.layout.json` (pdf/DocLayoutFiles). Si las ediciones cambiaron mientras la IA leía, no se guarda nada (false).
     */
    suspend fun saveAiResult(docId: String, pageId: String, edits: PageEdits, ocr: OcrResult, layout: DocLayout): Boolean {
        loaded.await()
        if (docId in deletedIds) return false
        val current = findPage(docId, pageId) ?: return false
        if (current.edits != edits) return false
        val lf = File(docDir(docId), PageFiles.layoutName(pageId, edits))
        withContext(Dispatchers.IO) {
            lf.parentFile?.mkdirs()
            writeAtomic(lf, com.scannerpromax.pdf.DocLayoutFiles.encode(layout), sync = false)
        }
        ocrLock(docId, pageId).withLock { saveOcrFor(docId, pageId, edits, ocr, fromAi = true) }
        val after = findPage(docId, pageId)
        if (after == null || after.edits != edits || after.ocrFile != PageFiles.ocrName(pageId, edits)) {
            withContext(Dispatchers.IO) { lf.delete() }
            return false
        }
        return true
    }

    /**
     * Maquetación reconocida por la IA de la versión ACTUAL de la página, o null si no hay (motor básico, aún en cola,
     * o la página se editó después). La reconstrucción del PDF la consume desde aquí.
     */
    suspend fun loadLayout(docId: String, pageId: String): DocLayout? {
        val page = findPage(docId, pageId) ?: return null
        return withContext(Dispatchers.IO) {
            com.scannerpromax.pdf.DocLayoutFiles.read(File(docDir(docId), PageFiles.processedName(pageId, page.edits)))
        }
    }

    /** ¿La versión actual de la página ya tiene OCR + maquetación de la IA? */
    suspend fun hasAiLayout(docId: String, pageId: String): Boolean {
        val page = findPage(docId, pageId) ?: return false
        return withContext(Dispatchers.IO) { File(docDir(docId), PageFiles.layoutName(pageId, page.edits)).exists() }
    }

    /** Páginas sin OCR guardado (para estimar el trabajo antes de exportar). */
    suspend fun pagesMissingOcr(docId: String, pageIds: List<String>? = null): Int = get(docId)?.pages?.filter { pageIds == null || it.id in pageIds }?.count { p ->
        val name = p.ocrFile
        name == null || !withContext(Dispatchers.IO) { File(docDir(docId), name).exists() }
    } ?: 0

    private fun ocrLock(docId: String, pageId: String): Mutex = ocrLocks.getOrPut("$docId/$pageId") { Mutex() }

    /** Archivo con el número de intentos fallidos de OCR de fondo de la página. */
    private fun ocrFailFile(docId: String, pageId: String) = File(docDir(docId), "$OCR_DIR/$pageId.fail")

    private fun ocrFailures(docId: String, pageId: String): Int = try {
        ocrFailFile(docId, pageId).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
    } catch (_: Throwable) {
        0
    }

    private fun markOcrFailure(docId: String, pageId: String) {
        if (docId in deletedIds) return
        try {
            val f = ocrFailFile(docId, pageId)
            f.parentFile?.mkdirs()
            f.writeText((ocrFailures(docId, pageId) + 1).toString())
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo marcar OCR fallido", t)
        }
    }

    /**
     * Vuelve a encolar el OCR de fondo de TODAS las páginas sin texto (p. ej. al terminar la descarga de Indra). Olvida
     * los intentos fallidos anteriores (pudieron fallar por no tener aún el lector).
     */
    suspend fun requeueMissingOcr() {
        loaded.await()
        writeMutex.withLock { docs.values.toList() }.forEach { d ->
            d.pages.filter { it.ocrFile == null }.forEach { p ->
                withContext(Dispatchers.IO) { runCatching { ocrFailFile(d.id, p.id).delete() } }
                enqueueOcr(d.id, p.id)
            }
        }
    }

    /** Espera a que no haya pantallas que pausen el OCR ni trabajo pesado, con un margen de calma (ráfagas). */
    private suspend fun awaitOcrIdle() {
        while (true) {
            ocrPause.first { it == 0 }
            activeHeavy.first { it == 0 }
            delay(OCR_SETTLE_MS)
            if (ocrPause.value == 0 && activeHeavy.value == 0) return
        }
    }

    private fun enqueueOcr(docId: String, pageId: String) {
        if (!backgroundOcrEnabled) return
        if (queuedOcr.add("$docId/$pageId")) ocrQueue.trySend(docId to pageId)
    }

    /** Consumidor de baja prioridad: una página a la vez y solo cuando no hay procesamiento pesado. */
    private suspend fun ocrWorker() {
        for ((docId, pageId) in ocrQueue) {
            // Se quita de la marca antes de procesar: si la página se edita mientras tanto, se vuelve a encolar.
            queuedOcr.remove("$docId/$pageId")
            try {
                // Cede el paso a la cámara, el editor y las capturas/ediciones (en gama baja OCR + render a la vez
                // agota la memoria y le quita CPU a la vista previa).
                awaitOcrIdle()
                if (!backgroundOcrEnabled || docId in deletedIds) continue
                val page = findPage(docId, pageId) ?: continue
                if (page.ocrFile != null) continue
                if (withContext(Dispatchers.IO) { ocrFailures(docId, pageId) } >= OCR_MAX_BACKGROUND_ATTEMPTS) continue
                // Sin Indra todavía: la página se queda pendiente (se reconoce sola al terminar la descarga).
                if (!ocr().isReady()) { onOcrUnavailable?.invoke(); continue }
                var interrupted = false
                val result = coroutineScope {
                    val work = async { ensureOcr(docId, pageId, lightweight = true) }
                    // Si se abre la cámara/editor a mitad, se corta tras la pasada actual (lo reconocido se guarda).
                    val watcher = launch {
                        ocrPause.first { it > 0 }
                        interrupted = true
                        work.cancel()
                    }
                    try {
                        work.await()
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        if (!interrupted) throw c
                        null
                    } finally {
                        watcher.cancel()
                    }
                }
                val now = findPage(docId, pageId)
                if (now != null && now.ocrFile == null) {
                    if (interrupted) enqueueOcr(docId, pageId)
                    else if (result == null && ocr().isReady()) withContext(Dispatchers.IO) { markOcrFailure(docId, pageId) }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "OCR en segundo plano fallido", t)
            }
        }
    }

    // ------------------------------------------------------------------ utilidades internas

    /** Permiso de [heavy] del trabajo en curso; se puede soltar antes de terminar (E/S lenta tras el render). */
    private class HeavyPermit(private val sem: Semaphore) : AbstractCoroutineContextElement(HeavyPermit) {
        companion object Key : CoroutineContext.Key<HeavyPermit>
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) sem.release()
        }
    }

    private suspend fun <T> heavyWork(block: suspend () -> T): T {
        activeHeavy.update { it + 1 }
        try {
            heavy.acquire()
            val permit = HeavyPermit(heavy)
            try {
                return withContext(permit) { block() }
            } finally {
                permit.release()
            }
        } finally {
            activeHeavy.update { it - 1 }
        }
    }

    private fun heavyPermits(): Int {
        if (!DeviceProfiler.isHighEnd(tier)) return 1
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 1
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        return if (totalGb >= 6.0 && !mi.lowMemory) 2 else 1
    }

    /** Lanza si el documento fue borrado (evita recrear su carpeta desde un trabajo en curso). */
    private fun checkAlive(docId: String) {
        if (docId in deletedIds) throw IOException("Documento eliminado")
    }

    /** Tras un fallo al añadir una página: borra sus archivos, o la carpeta entera si el documento se borró. */
    private fun discardPageFiles(docId: String, dir: File, pageId: String) {
        if (docId in deletedIds) dir.deleteRecursively() else deletePageFiles(dir, pageId)
    }

    /** Reescala un quad del espacio (fromW x fromH) al espacio (toW x toH). */
    private fun toSpace(q: Quad, fromW: Int, fromH: Int, toW: Int, toH: Int): Quad {
        if (fromW <= 0 || fromH <= 0 || (fromW == toW && fromH == toH)) return q
        return PerspectiveCorrector.scaleQuad(q, toW.toDouble() / fromW, toH.toDouble() / fromH)
    }

    private fun moveOrCopy(src: File, dst: File) {
        if (src.absolutePath == dst.absolutePath) return
        // Solo se "mueve" si el archivo es temporal nuestro (caché); si no, se copia.
        val isOurTemp = src.absolutePath.startsWith(context.cacheDir.absolutePath)
        if (isOurTemp && src.renameTo(dst)) return
        src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it, BUFFER) } }
        if (isOurTemp) src.delete()
    }

    companion object {
        private const val TAG = "DocumentRepository"
        const val DOCUMENTS_DIR = "documents"
        private const val DOC_JSON = "doc.json"
        private const val DOC_JSON_TMP = "doc.json.tmp"
        private const val ORIGINAL_DIR = "original"
        private const val PROCESSED_DIR = "processed"
        private const val THUMB_DIR = "thumb"
        private const val OCR_DIR = "ocr"
        private val SUBDIRS = listOf(ORIGINAL_DIR, PROCESSED_DIR, THUMB_DIR, OCR_DIR, PageFiles.JEV_DIR)
        /** Duplicados: páginas anteriores con las que se compara la nueva y rasgos ORB guardados en memoria. */
        private const val DUP_WINDOW = 6
        private const val DUP_CACHE = 12

        /** Solo para originales GENERADOS (DNI compuesto); las fotos de cámara/galería se mueven sin recodificar. */
        private const val ORIGINAL_QUALITY = 100
        /** El procesado se codifica una única vez con pérdida; la reducción de tamaño ocurre solo al exportar. */
        private const val PROCESSED_QUALITY = 95
        private const val THUMB_QUALITY = 90
        /** Miniaturas nítidas en las rejillas (celdas de ~600-900 px en pantallas QHD+). */
        private const val THUMB_SIDE = 960
        /** Mínimo al reintentar un render sin memoria (solo equipos muy justos). */
        private const val MIN_RETRY_PIXELS = 4_000_000
        private const val OCR_MAX_PIXELS = 12_600_000
        /** Lado máximo de la versión de solo geometría que lee Indra (su detector trabaja a 1024 px; los recortes no ganan más). */
        private const val INDRA_GEO_MAX_SIDE = 3200
        private const val MIN_CONFIDENCE = 0.35f
        private const val BUFFER = 64 * 1024
        private const val OCR_LOW_RAM_PIXELS = 4_000_000
        private const val OCR_BACKGROUND_PIXELS = 3_000_000
        private const val OCR_SETTLE_MS = 1_500L
        private const val OCR_MAX_BACKGROUND_ATTEMPTS = 2
        private const val WRITE_DEBOUNCE_MS = 400L
        private const val STARTUP_OCR_DELAY_MS = 8_000L
        private val AGED_CACHE_DIRS = listOf("exports", "share", "compress_out")
        private val WIPED_CACHE_DIRS = listOf("compress", "pdfbox")

        private fun isBinaryFilter(f: FilterType) = PageFiles.isBinaryFilter(f)

        /** OutOfMemoryError de Java o fallo de reserva de memoria nativa de OpenCV ("Insufficient memory"). */
        private fun isOutOfMemory(t: Throwable): Boolean {
            var c: Throwable? = t
            while (c != null) {
                if (c is OutOfMemoryError) return true
                if (c is org.opencv.core.CvException) {
                    val m = c.message.orEmpty()
                    if (m.contains("Insufficient memory", true) || m.contains("Failed to allocate", true) || m.contains("NoMemory", true)) return true
                }
                c = c.cause
            }
            return false
        }

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        private fun processedName(pageId: String, edits: PageEdits) = PageFiles.processedName(pageId, edits)
        private fun thumbName(pageId: String, edits: PageEdits) = PageFiles.thumbName(pageId, edits)
        private fun jevCacheName(pageId: String, edits: PageEdits) = PageFiles.jevCacheName(pageId, edits)
        private const val JEV_DIR = PageFiles.JEV_DIR
        /** OCR con el hash de las ediciones en el nombre: un OCR de otra versión de la imagen se invalida por nombre. */
        private fun ocrName(pageId: String, edits: PageEdits) = PageFiles.ocrName(pageId, edits)

        /** Título por defecto en español, p. ej. "Escaneo 06-10-2026 14:32" o "DNI 06-10-2026 14:32". */
        fun defaultTitle(mode: ScanMode, time: Long = System.currentTimeMillis()): String {
            val prefix = when (mode) {
                ScanMode.DOCUMENT -> "Escaneo"
                ScanMode.BOOK -> "Libro"
                ScanMode.ID_CARD -> "DNI"
                ScanMode.RECEIPT -> "Recibo"
                ScanMode.WHITEBOARD -> "Pizarra"
                ScanMode.PHOTO -> "Foto"
            }
            val fmt = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale("es", "ES"))
            return "$prefix ${fmt.format(Date(time))}"
        }
    }
}
