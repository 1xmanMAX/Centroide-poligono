package com.scannerpromax.data

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.DeviceProfiler
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.PageProcessor
import com.scannerpromax.imaging.PerspectiveCorrector
import com.scannerpromax.ocr.OcrEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    private val activeHeavy = AtomicInteger(0)
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

    /** Motor OCR: el contenedor puede asignar el suyo con [attachOcrEngine]; si no, se crea uno (el cliente ML Kit es compartido). */
    @Volatile private var ocrEngine: OcrEngine? = null
    private val ocrQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val queuedOcr: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val ocrLocks = ConcurrentHashMap<String, Mutex>()

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
                d.pages.filter { it.ocrFile == null }.forEach { enqueueOcr(d.id, it.id) }
            }
        }
    }

    val documents: StateFlow<List<Document>> get() = _documents.asStateFlow()

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
        withContext(Dispatchers.IO) { docDir(docId).deleteRecursively() }
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
     * dimensiones reales del archivo (con EXIF aplicado) y el quad se guarda en ese espacio. Para procesar se
     * decodifica reducido a [DeviceTier.maxWorkingPixels] y el quad se reescala.
     */
    suspend fun addPageFromFile(docId: String, file: File, autoDetect: Boolean = true, mode: ScanMode? = null): Page {
        loaded.await()
        val doc = get(docId) ?: throw IOException("Documento no encontrado")
        checkAlive(docId)
        val m = mode ?: doc.mode
        val pageId = newId()
        val dir = docDir(docId)
        val originalName = "$ORIGINAL_DIR/$pageId.jpg"
        val dst = File(dir, originalName)

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

                    val quad = (if (autoDetect && m != ScanMode.PHOTO) detectQuad(bmp) else null)
                        ?.let { toSpace(it, bmp.width, bmp.height, pw, ph) }
                    val edits = defaultEdits(m, quad)
                    val (processedName, thumbName) = renderAndSave(docId, pageId, bmp, edits, pw, ph)
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
                    bitmap?.recycle()
                }
            }
        }
        enqueueOcr(docId, page.id)
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

            val (processedName, thumbName) = heavyWork {
                withContext(Dispatchers.Default) {
                    val original = BitmapIO.decode(File(dir, current.originalFile).absolutePath, tier.maxWorkingPixels)
                    try {
                        renderAndSave(docId, pageId, original, edits, current.width, current.height)
                    } finally {
                        original.recycle()
                    }
                }
            }

            var result: Page = current
            mutatePages(docId) { pages ->
                pages.map { p ->
                    if (p.id != pageId) p else {
                        cleanupStale(dir, p, keepProcessed = processedName, keepThumb = thumbName)
                        // El OCR guardado deja de ser válido solo si la imagen cambió.
                        val editsChanged = p.edits != edits
                        if (editsChanged) p.ocrFile?.let { File(dir, it).delete() }
                        p.copy(
                            edits = edits,
                            processedFile = processedName,
                            thumbFile = thumbName,
                            ocrText = if (editsChanged) null else p.ocrText,
                            ocrFile = if (editsChanged) null else p.ocrFile,
                        ).also { result = it }
                    }
                }
            }
            if (result.ocrFile == null) enqueueOcr(docId, pageId)
            result
        }
    }

    suspend fun deletePage(docId: String, pageId: String) {
        mutatePages(docId) { pages -> pages.filterNot { it.id == pageId } }
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

    suspend fun saveOcr(docId: String, pageId: String, result: OcrResult) {
        loaded.await()
        val name = "$OCR_DIR/$pageId.json"
        withContext(Dispatchers.IO) {
            val f = File(docDir(docId), name)
            f.parentFile?.mkdirs()
            writeAtomic(f, json.encodeToString(OcrResult.serializer(), result))
        }
        mutatePages(docId) { pages ->
            pages.map { if (it.id == pageId) it.copy(ocrText = result.text, ocrFile = name) else it }
        }
    }

    suspend fun loadOcr(docId: String, pageId: String): OcrResult? {
        val page = findPage(docId, pageId) ?: return null
        val name = page.ocrFile ?: return null
        return withContext(Dispatchers.IO) {
            val f = File(docDir(docId), name)
            if (!f.exists()) return@withContext null
            try {
                json.decodeFromString(OcrResult.serializer(), f.readText())
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
                    mutatePagesIfPresent(docId) { pages ->
                        pages.map { if (it.id == current.id && it.edits == current.edits) it.copy(processedFile = expected) else it }
                    }
                }
                // Regenera la miniatura si se perdió (barato a partir del procesado).
                val thumb = current.thumbFile
                if (thumb == null || !File(dir, thumb).exists()) {
                    regenerateThumb(docId, current, expectedFile)
                }
                return@withLock expectedFile
            }

            val (processedName, thumbName) = heavyWork {
                withContext(Dispatchers.Default) {
                    val original = BitmapIO.decode(File(dir, current.originalFile).absolutePath, tier.maxWorkingPixels)
                    try {
                        renderAndSave(docId, current.id, original, current.edits, current.width, current.height)
                    } finally {
                        original.recycle()
                    }
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

    fun thumbFile(docId: String, page: Page): File? {
        val name = page.thumbFile ?: return null
        val f = File(docDir(docId), name)
        return if (f.exists()) f else null
    }

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
    private fun detectQuad(bmp: Bitmap): Quad? {
        val det = try {
            processor.detector.detect(bmp)
        } catch (t: Throwable) {
            Log.w(TAG, "Fallo en detección de bordes", t)
            null
        } ?: return null
        if (det.confidence < MIN_CONFIDENCE || det.frameWidth <= 0 || det.frameHeight <= 0) return null
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
            ScanMode.PHOTO -> PageEdits(quad = null, filter = FilterType.ORIGINAL, autoDenoise = false, autoDeskew = false)
            ScanMode.WHITEBOARD -> PageEdits(quad = quad, filter = FilterType.WHITEBOARD, autoDeskew = false)
            ScanMode.ID_CARD -> PageEdits(quad = quad, filter = docFilter ?: FilterType.MAGIC, autoDeskew = false)
            ScanMode.DOCUMENT, ScanMode.RECEIPT, ScanMode.BOOK -> PageEdits(
                quad = quad,
                filter = docFilter ?: FilterType.MAGIC,
                autoRemoveLines = defaultAutoRemoveLines,
            )
        }
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
    ): Pair<String, String> {
        val dir = docDir(docId)
        val processedName = processedName(pageId, edits)
        val thumbName = thumbName(pageId, edits)
        val workEdits = edits.quad?.let { q -> edits.copy(quad = toSpace(q, pageW, pageH, original.width, original.height)) } ?: edits
        val rendered = processor.render(original, workEdits)
        try {
            currentCoroutineContext().ensureActive()
            val thumb = BitmapIO.thumbnail(rendered, THUMB_SIDE)
            try {
                withContext(Dispatchers.IO) {
                    checkAlive(docId)
                    File(dir, PROCESSED_DIR).mkdirs()
                    File(dir, THUMB_DIR).mkdirs()
                    if (isBinaryFilter(edits.filter)) {
                        saveAtomic(rendered, File(dir, processedName), Bitmap.CompressFormat.PNG, 100)
                    } else {
                        saveJpegAtomic(rendered, File(dir, processedName), PROCESSED_QUALITY)
                    }
                    saveJpegAtomic(thumb, File(dir, thumbName), THUMB_QUALITY)
                }
            } finally {
                if (thumb !== rendered && thumb !== original) thumb.recycle()
            }
        } finally {
            if (rendered !== original) rendered.recycle()
        }
        return processedName to thumbName
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
            mutatePagesIfPresent(docId) { pages -> pages.map { if (it.id == page.id) it.copy(thumbFile = name) else it } }
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
        File(dir, PROCESSED_DIR).listFiles()?.forEach { f ->
            if (f.name.startsWith(page.id) && "$PROCESSED_DIR/${f.name}" != keepProcessed) f.delete()
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

    private suspend fun mutatePagesIfPresent(docId: String, transform: (List<Page>) -> List<Page>) {
        loaded.await()
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val doc = docs[docId]
                if (doc != null) {
                    val updated = doc.copy(pages = transform(doc.pages))
                    if (updated != doc) {
                        writeDocJson(updated)
                        docs[docId] = updated
                        publish()
                    }
                }
            }
        }
    }

    /** Aplica [transform] bajo el candado de escritura (en IO: el transform puede borrar archivos). */
    private suspend fun mutate(docId: String, transform: (Document) -> Document) {
        loaded.await()
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val doc = docs[docId] ?: throw IOException("Documento no encontrado")
                val updated = transform(doc).copy(updatedAt = System.currentTimeMillis())
                writeDocJson(updated)
                docs[docId] = updated
                publish()
            }
        }
    }

    private fun publish() {
        _documents.value = docs.values.sortedByDescending { it.updatedAt }
    }

    private fun writeDocJson(doc: Document) {
        val dir = docDir(doc.id)
        dir.mkdirs()
        writeAtomic(File(dir, DOC_JSON), json.encodeToString(Document.serializer(), doc))
    }

    /** Escritura atómica: archivo temporal + rename (nunca deja un JSON a medias). */
    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            try { out.fd.sync() } catch (_: Throwable) { }
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
                val pages = doc.pages.filter { File(dir, it.originalFile).exists() }
                return if (pages.size == doc.pages.size) doc else doc.copy(pages = pages)
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

    /** Usa el motor OCR del contenedor (comparte el cliente de ML Kit). Llamar una vez al crear el contenedor. */
    fun attachOcrEngine(engine: OcrEngine) {
        ocrEngine = engine
    }

    private fun ocr(): OcrEngine = ocrEngine ?: synchronized(this) {
        ocrEngine ?: OcrEngine(context).also { ocrEngine = it }
    }

    /**
     * Devuelve el OCR guardado de la página o lo calcula (sobre la imagen procesada) y lo guarda.
     * null si la página no existe o el reconocimiento falla. Lo usan exportar/compartir y el OCR de fondo.
     */
    suspend fun ensureOcr(docId: String, pageId: String): OcrResult? {
        loadOcr(docId, pageId)?.let { return it }
        return ocrLock(docId, pageId).withLock {
            loadOcr(docId, pageId)?.let { return@withLock it }
            val page = findPage(docId, pageId) ?: return@withLock null
            try {
                val file = processedFile(docId, page)
                val current = findPage(docId, pageId) ?: return@withLock null
                val maxPx = if (tier.isLowRam) OCR_LOW_RAM_PIXELS else tier.maxWorkingPixels
                val bmp = withContext(Dispatchers.Default) { BitmapIO.decode(file.absolutePath, maxPx) }
                val result = try {
                    ocr().recognize(bmp)
                } finally {
                    bmp.recycle()
                }
                // Solo se guarda si la página no cambió mientras se reconocía.
                saveOcrIfCurrent(docId, pageId, current.edits, result)
                result
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "OCR fallido en $docId/$pageId", t)
                null
            }
        }
    }

    private fun ocrLock(docId: String, pageId: String): Mutex = ocrLocks.getOrPut("$docId/$pageId") { Mutex() }

    private suspend fun saveOcrIfCurrent(docId: String, pageId: String, edits: PageEdits, result: OcrResult) {
        val current = findPage(docId, pageId) ?: return
        if (current.edits != edits || docId in deletedIds) return
        saveOcr(docId, pageId, result)
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
                // Cede el paso a capturas/ediciones (en gama baja OCR + render a la vez agota la memoria).
                while (activeHeavy.get() > 0) delay(OCR_IDLE_POLL_MS)
                if (!backgroundOcrEnabled || docId in deletedIds) continue
                val page = findPage(docId, pageId) ?: continue
                if (page.ocrFile != null) continue
                ensureOcr(docId, pageId)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "OCR en segundo plano fallido", t)
            }
        }
    }

    // ------------------------------------------------------------------ utilidades internas

    private suspend fun <T> heavyWork(block: suspend () -> T): T {
        activeHeavy.incrementAndGet()
        try {
            return heavy.withPermit { block() }
        } finally {
            activeHeavy.decrementAndGet()
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
        private val SUBDIRS = listOf(ORIGINAL_DIR, PROCESSED_DIR, THUMB_DIR, OCR_DIR)

        private const val ORIGINAL_QUALITY = 95
        private const val PROCESSED_QUALITY = 92
        private const val THUMB_QUALITY = 82
        private const val THUMB_SIDE = 480
        private const val MIN_CONFIDENCE = 0.35f
        private const val BUFFER = 64 * 1024
        private const val OCR_LOW_RAM_PIXELS = 6_000_000
        private const val OCR_IDLE_POLL_MS = 1_500L
        private const val STARTUP_OCR_DELAY_MS = 8_000L
        private val AGED_CACHE_DIRS = listOf("exports", "share", "compress_out")
        private val WIPED_CACHE_DIRS = listOf("compress", "pdfbox")

        private fun isBinaryFilter(f: FilterType) = f == FilterType.BLACK_WHITE || f == FilterType.ECO_INK

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        /** Hash estable (entre ejecuciones) de las ediciones: se usa en el nombre de archivo para cachear. */
        private fun editsHash(edits: PageEdits): String =
            Integer.toHexString(json.encodeToString(PageEdits.serializer(), edits).hashCode())

        private fun processedName(pageId: String, edits: PageEdits) =
            "$PROCESSED_DIR/${pageId}_${editsHash(edits)}.${if (isBinaryFilter(edits.filter)) "png" else "jpg"}"
        private fun thumbName(pageId: String, edits: PageEdits) = "$THUMB_DIR/${pageId}_${editsHash(edits)}.jpg"

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
