package com.scannerpromax.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.scannerpromax.ai.parts.DocOrientation
import com.scannerpromax.ai.parts.LayoutEngine
import com.scannerpromax.ai.parts.PartsPipeline
import com.scannerpromax.data.DocumentRepository
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.ocr.OcrUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Cola en SEGUNDO PLANO de "Volver a reconocer" y de la MAQUETACIÓN POR PARTES (opcional):
 *
 *  - El texto de cada página lo pone siempre **Indra** (el único OCR; el OCR de fondo del repositorio lo calcula al
 *    añadir la página). Si está descargada la "Maquetación de páginas" (PP-DocLayoutV3 + doc_ori), las páginas nuevas o
 *    reeditadas llegan por [DocumentRepository.AiHook] y aquí se enderezan (orientación), se detectan sus regiones y
 *    se monta el [DocLayout] con las líneas de Indra ([PartsPipeline]) para el PDF reconstruido y la IA en la nube.
 *  - "Volver a reconocer" ([reread]) relee la página con Indra (y rehace la maquetación si el modelo está).
 *  - La cola se guarda en disco (`filesDir/ai/queue.json`) y la procesa [AiRecognitionWorker] (WorkManager con
 *    notificación); si Indra aún se está descargando, las páginas esperan ([PageStatus.WaitingOcr]) y [resume] las
 *    procesa al terminar la descarga.
 *  - (Los motores locales con LLM —PaddleOCR-VL, Qwen— y la nube como motor de reconocimiento se eliminaron.)
 */
class AiRecognitionQueue(
    context: Context,
    private val models: ModelManager,
    private val documents: () -> DocumentRepository,
    private val tier: DeviceTier,
) : DocumentRepository.AiHook {

    @Serializable
    data class AiJob(
        val docId: String,
        val pageId: String,
        /** Releer aunque ya haya resultado (botón "Volver a reconocer"). */
        val force: Boolean = false,
    ) {
        val key: String get() = "$docId/$pageId"
    }

    /** Estado visible de una página. */
    sealed interface PageStatus {
        data object Queued : PageStatus
        /** El lector de texto (Indra) aún se está descargando: la página se procesa sola al terminar. */
        data object WaitingOcr : PageStatus
        data class Running(val fraction: Float, val label: String) : PageStatus {
            val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 99)
        }
    }

    /** Progreso global (para la notificación del worker). */
    data class Overall(val done: Int, val total: Int, val fraction: Float, val label: String)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Maquetación de páginas + orientación (descarga opcional; sin ella solo se relee con Indra). */
    private val layoutEngine = LayoutEngine(models, threads = if (tier.isLowRam) 2 else 4)
    private val file = File(appContext.filesDir, "ai/queue.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val jobsLock = Any()
    private val jobs = LinkedHashMap<String, AiJob>()   // protegido por jobsLock
    private val drainMutex = Mutex()
    @Volatile private var current: Pair<String, Job>? = null
    /** Páginas que la propia cola está re-editando (enderezado): su aviso de "página cambiada" se ignora. */
    private val selfEditing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** ¿Está Indra listo? (lo asigna el contenedor; sin él se asume que sí y el repositorio dirá si no). */
    @Volatile var ocrReady: () -> Boolean = { true }
    /** Se llama cuando hay páginas esperando a Indra (el contenedor arranca su descarga si no está en marcha). */
    @Volatile var onOcrMissing: () -> Unit = {}

    private val _status = MutableStateFlow<Map<String, PageStatus>>(emptyMap())
    val status: StateFlow<Map<String, PageStatus>> = _status.asStateFlow()

    private val _overall = MutableStateFlow<Overall?>(null)
    val overall: StateFlow<Overall?> = _overall.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Avisos discretos (p. ej. "Página 3 enderezada"). */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    init {
        scope.launch {
            restore()
            if (pending() > 0) kick()
        }
    }

    // ------------------------------------------------------------------------------------------ API

    fun statusOf(docId: String, pageId: String): PageStatus? = _status.value["$docId/$pageId"]

    fun pending(): Int = synchronized(jobsLock) { jobs.size }

    /** ¿Está descargada la maquetación por partes? */
    fun layoutAvailable(): Boolean = layoutEngine.isReady()

    /** Botón "Volver a reconocer": relee con Indra (y rehace la maquetación si está). Mensaje en español o null. */
    fun reread(docId: String, pageId: String): String? {
        add(AiJob(docId, pageId, force = true))
        return if (ocrReady()) null else "El lector de texto se está descargando; la página se leerá sola al terminar."
    }

    /** Quita la página de la cola (y corta su proceso si está en curso). */
    fun cancel(docId: String, pageId: String) {
        val key = "$docId/$pageId"
        synchronized(jobsLock) { jobs.remove(key) }
        persist()
        current?.takeIf { it.first == key }?.second?.cancel()
        _status.update { it - key }
    }

    /** Vacía la cola y corta el proceso en curso. */
    fun cancelAll() {
        synchronized(jobsLock) { jobs.clear() }
        persist()
        current?.second?.cancel()
        _status.value = emptyMap()
    }

    /** Indra (o la maquetación) acaba de quedar listo: procesa lo que esperaba. */
    fun resume() {
        if (pending() > 0) kick()
    }

    override fun onPageAdded(docId: String, pageId: String) {
        if (layoutEngine.isReady()) add(AiJob(docId, pageId))
    }

    override fun onPageChanged(docId: String, pageId: String) {
        val key = "$docId/$pageId"
        if (key in selfEditing) return
        // El proceso en curso de la versión anterior ya no vale.
        current?.takeIf { it.first == key }?.second?.cancel()
        if (layoutEngine.isReady()) add(AiJob(docId, pageId))
    }

    // ------------------------------------------------------------------------------------------ cola

    private fun add(job: AiJob) {
        synchronized(jobsLock) {
            val old = jobs[job.key]
            jobs[job.key] = if (old == null) job else old.copy(force = old.force || job.force)
        }
        _status.update { if (it[job.key] is PageStatus.Running) it else it + (job.key to PageStatus.Queued) }
        persist()
        kick()
    }

    private fun remove(key: String) {
        synchronized(jobsLock) { jobs.remove(key) }
        _status.update { it - key }
        persist()
    }

    private fun snapshot(): List<AiJob> = synchronized(jobsLock) { jobs.values.toList() }

    private fun persist() {
        val list = snapshot()
        scope.launch(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(ListSerializer(AiJob.serializer()), list))
                tmp.renameTo(file)
            }.onFailure { Log.w(TAG, "No se pudo guardar la cola", it) }
        }
    }

    private suspend fun restore() = withContext(Dispatchers.IO) {
        val list = runCatching { json.decodeFromString(ListSerializer(AiJob.serializer()), file.readText()) }.getOrNull() ?: return@withContext
        synchronized(jobsLock) { list.forEach { jobs[it.key] = it } }
        _status.value = list.associate { it.key to PageStatus.Queued }
    }

    /** Arranca el worker (WorkManager); si no se puede, procesa en el proceso. */
    private fun kick() {
        try {
            val req = OneTimeWorkRequestBuilder<AiRecognitionWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(AiRecognitionWorker.TAG_WORK)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(AiRecognitionWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        } catch (t: Throwable) {
            Log.w(TAG, "WorkManager no disponible: cola en el proceso", t)
            scope.launch { runCatching { drain {} } }
        }
    }

    // ------------------------------------------------------------------------------------------ proceso

    /** Procesa la cola hasta vaciarla (lo llama el worker). Si Indra aún no está, deja las páginas esperando. */
    suspend fun drain(onOverall: (Overall) -> Unit) = drainMutex.withLock {
        var done = 0
        try {
            while (true) {
                val batch = snapshot()
                if (batch.isEmpty()) break
                if (!ocrReady()) { waitForOcr(); break }
                var waiting = false
                for (j in batch) {
                    if (snapshot().none { it.key == j.key }) continue
                    val total = done + snapshot().size
                    val outcome = runGuarded(j) {
                        process(j) { p ->
                            _status.update { it + (j.key to PageStatus.Running(p.fraction, p.label)) }
                            val o = Overall(done, total, p.fraction, p.label)
                            _overall.value = o
                            onOverall(o)
                        }
                    }
                    if (outcome == Outcome.WaitOcr) { waiting = true; break }
                    remove(j.key)
                    done++
                }
                if (waiting) { waitForOcr(); break }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { layoutEngine.close() }
                _overall.value = null
                _status.update { m -> m.filterValues { it !is PageStatus.Running } }
            }
        }
    }

    private fun waitForOcr() {
        val keys = snapshot().map { it.key }
        _status.update { m -> m + keys.associateWith { PageStatus.WaitingOcr } }
        onOcrMissing()
    }

    private enum class Outcome { Done, WaitOcr }

    /** Ejecuta [block] como trabajo cancelable de la página [j]; los errores no paran la cola. */
    private suspend fun runGuarded(j: AiJob, block: suspend () -> Unit): Outcome {
        var outcome = Outcome.Done
        try {
            coroutineScope {
                val job = launch {
                    try { block() } catch (e: OcrUnavailableException) { outcome = Outcome.WaitOcr }
                }
                current = j.key to job
                job.join()
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "página ${j.key}", t)
            _notices.tryEmit("No se pudo procesar una página (${t.message ?: t.javaClass.simpleName}).")
        } finally {
            current = null
        }
        return outcome
    }

    private suspend fun pageNumber(j: AiJob): Int = (documents().get(j.docId)?.pages?.indexOfFirst { it.id == j.pageId } ?: -1) + 1

    private suspend fun process(j: AiJob, onProgress: (AiRecognizer.Progress) -> Unit) {
        val repo = documents()
        val page0 = repo.getPage(j.docId, j.pageId) ?: return
        if (!layoutEngine.isReady()) {
            // Sin maquetación: solo "Volver a reconocer" con Indra.
            if (j.force) {
                onProgress(AiRecognizer.Progress(0.1f, "Reconociendo el texto"))
                repo.recognizePage(j.docId, j.pageId, keepEditedText = true)
            }
            return
        }
        if (!j.force && repo.hasAiLayout(j.docId, j.pageId)) return
        onProgress(AiRecognizer.Progress(0.01f, "Preparando"))
        val prep = prepare(j, page0, decodePixels(), onProgress) ?: return
        val result = try {
            onProgress(AiRecognizer.Progress(0.1f, "Reconociendo el texto"))
            val ocr = if (j.force) repo.recognizePage(j.docId, j.pageId, keepEditedText = true) else repo.ensureOcr(j.docId, j.pageId)
            if (ocr == null) {
                if (!ocrReady()) throw OcrUnavailableException("Indra no disponible")
                _notices.tryEmit("Página ${pageNumber(j)}: no se pudo reconocer el texto.")
                return
            }
            onProgress(AiRecognizer.Progress(0.4f, "Maquetando la página"))
            withContext(Dispatchers.Default) { layoutOf(prep.bmp, ocr, prep.fullW, prep.fullH, prep.rotation) } to ocr
        } finally {
            prep.bmp.recycle()
        }
        val saved = repo.saveAiResult(j.docId, j.pageId, prep.edits, result.second, result.first)
        if (!saved) Log.i(TAG, "${j.key}: la página cambió mientras se maquetaba; resultado descartado")
        onProgress(AiRecognizer.Progress(1f, "Listo"))
    }

    /** Maquetación por partes: regiones PP-DocLayoutV3 + líneas de Indra → [DocLayout] en px de la imagen completa. */
    private fun layoutOf(bmp: Bitmap, ocr: OcrResult, fullW: Int, fullH: Int, rotation: Int): DocLayout {
        val a = LayoutEngine.toRgb(bmp, LayoutEngine.ANALYSIS_SIDE)
        val regions = layoutEngine.regions(a).orEmpty()
        val out = PartsPipeline().run(
            PartsPipeline.Input(a, regions, null, ModelCatalog.PAGE_LAYOUT.id + "+" + ModelCatalog.FAST_READER.id, rotation, lines = linesOf(ocr, a.w, a.h)),
        )
        return PartsPipeline.scaleLayout(out.layout, fullW, fullH)
    }

    private fun decodePixels(): Int = if (tier.isLowRam) 4_000_000 else 6_000_000

    /** Página lista: ediciones, procesado decodificado (DERECHO), tamaño completo y giro aplicado aquí. */
    private class Prepared(val edits: PageEdits, val bmp: Bitmap, val fullW: Int, val fullH: Int, val rotation: Int)

    /**
     * Decodifica el procesado de la página y comprueba la ORIENTACIÓN (PP-LCNet doc_ori, voto de 5 recortes): una página
     * girada se endereza en sus ediciones (giro horario tras el recorte), así la imagen procesada, la miniatura, el PDF y
     * las coordenadas quedan derechos. Solo si la página no tiene ya un giro (respeta el del usuario).
     */
    private suspend fun prepare(j: AiJob, page0: Page, decodePx: Int, onProgress: (AiRecognizer.Progress) -> Unit): Prepared? {
        val repo = documents()
        var processed = repo.processedFile(j.docId, page0)
        var page = repo.getPage(j.docId, j.pageId) ?: return null
        var bmp = withContext(Dispatchers.IO) { BitmapIO.decode(processed.absolutePath, decodePx) }
        var rotation = 0
        try {
            if (page.edits.rotation == 0) {
                onProgress(AiRecognizer.Progress(0.02f, "Comprobando la orientación"))
                val o = withContext(Dispatchers.Default) { layoutEngine.orientation(bmp) }
                val ccw = o?.let { DocOrientation.correction(it) } ?: 0
                if (ccw != 0) {
                    val newEdits = page.edits.copy(rotation = DocOrientation.clockwise(ccw))
                    val updated = selfEdit(j) { repo.updateEdits(j.docId, j.pageId, newEdits) }
                    if (updated != null && updated.edits == newEdits) {
                        onProgress(AiRecognizer.Progress(0.03f, "Enderezando la página"))
                        processed = repo.processedFile(j.docId, updated)
                        page = repo.getPage(j.docId, j.pageId) ?: return null
                        bmp.recycle()
                        bmp = withContext(Dispatchers.IO) { BitmapIO.decode(processed.absolutePath, decodePx) }
                        rotation = ccw
                        _notices.tryEmit("Página ${pageNumber(j)} enderezada (estaba girada ${o!!.angle}°). Puedes deshacerlo en el editor.")
                    }
                }
            }
            val (fw, fh) = withContext(Dispatchers.IO) { BitmapIO.decodeBounds(processed.absolutePath) }
            return Prepared(page.edits, bmp, if (fw > 0) fw else bmp.width, if (fh > 0) fh else bmp.height, rotation)
        } catch (t: Throwable) {
            bmp.recycle()
            throw t
        }
    }

    private suspend fun <T> selfEdit(j: AiJob, block: suspend () -> T): T? {
        selfEditing.add(j.key)
        return try { block() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) {
            Log.w(TAG, "edición ${j.key}", t); null
        } finally { selfEditing.remove(j.key) }
    }

    companion object {
        private const val TAG = "AiRecognitionQueue"

        /**
         * Líneas del OCR de Indra (px de la imagen completa del OCR) → [PartsPipeline.Item] en px de la imagen analizada
         * [w] x [h] (cuadrilátero = caja de la línea).
         */
        fun linesOf(ocr: OcrResult, w: Int, h: Int): List<PartsPipeline.Item> {
            val o = AiRecognizer.scaleOcr(ocr, w, h)
            return o.blocks.flatMap { it.lines }.filter { it.text.isNotBlank() }.map { l ->
                val x0 = l.box.left; val y0 = l.box.top; val x1 = l.box.right; val y1 = l.box.bottom
                PartsPipeline.Item(l.text, floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), floatArrayOf(x0, y0, x1, y1))
            }
        }
    }
}
