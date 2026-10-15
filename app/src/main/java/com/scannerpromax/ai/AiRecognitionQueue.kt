package com.scannerpromax.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.scannerpromax.ai.cloud.CloudAlign
import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.CloudKeyStore
import com.scannerpromax.ai.cloud.CloudNetwork
import com.scannerpromax.ai.cloud.CloudProviderId
import com.scannerpromax.ai.cloud.CloudRecognizer
import com.scannerpromax.ai.cloud.CloudUsageStore
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.scannerpromax.data.AppSettings
import com.scannerpromax.data.DocumentRepository
import com.scannerpromax.data.SettingsRepository
import com.scannerpromax.ai.parts.DocOrientation
import com.scannerpromax.ai.parts.LayoutEngine
import com.scannerpromax.ai.parts.PartsPipeline
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.DocumentDetector
import com.scannerpromax.domain.Pt
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
import kotlinx.coroutines.flow.first
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
 * Cola de reconocimiento con IA en SEGUNDO PLANO (una página tarda 0,5–4 min en un móvil):
 *
 *  - Las páginas nuevas o reeditadas llegan por [DocumentRepository.AiHook]; el botón "Volver a leer con IA" llama
 *    a [reread]. La cola se guarda en disco (`filesDir/ai/queue.json`) y la procesa [AiRecognitionWorker]
 *    (WorkManager, servicio en primer plano con notificación de progreso): sobrevive a cambiar de pantalla y a
 *    salir de la app; si el sistema mata el proceso, WorkManager lo reanuda.
 *  - Fases por tanda: 1) con el Asistente visual (Qwen) cargado, rescate de encuadre y clasificación + mejora AUTO
 *    de las páginas que lo necesitan; 2) con el motor elegido cargado UNA vez para toda la tanda, lectura de cada
 *    página ([AiRecognizer]) -> OCR + DocLayout guardados por el repositorio. Al vaciarse la cola el modelo se
 *    descarga (unload) para liberar la RAM.
 *  - Fallback robusto: modelo no descargado, RAM insuficiente, fallo de carga o salida basura/bucle -> la página se
 *    queda con el OCR básico (ML Kit, que siempre corre antes) y se avisa discretamente por [notices].
 *  - Progreso por página en [status] ("Leyendo con IA… 40 %"); [cancel] / [cancelAll].
 */
class AiRecognitionQueue(
    context: Context,
    private val llm: LlmEngine,
    private val models: ModelManager,
    private val settings: SettingsRepository,
    private val documents: () -> DocumentRepository,
    private val detector: () -> DocumentDetector,
    private val tier: DeviceTier,
    /** IA en la nube (opcional): lectura con la clave del usuario, sin modelo local. */
    private val cloud: CloudRecognizer,
    private val cloudKeys: CloudKeyStore,
    private val cloudUsage: CloudUsageStore,
) : DocumentRepository.AiHook {

    @Serializable
    data class AiJob(
        val docId: String,
        val pageId: String,
        /** El detector clásico no dio bordes fiables al añadir la página: pedir la caja a Qwen. */
        val rescue: Boolean = false,
        val detConfidence: Float? = null,
        /** Releer aunque ya haya resultado de IA (botón). */
        val force: Boolean = false,
        /** Fase Qwen (rescate / clasificación) ya hecha. */
        val qwenDone: Boolean = false,
    ) {
        val key: String get() = "$docId/$pageId"
    }

    /** Estado visible de una página. */
    sealed interface PageStatus {
        data object Queued : PageStatus
        /** IA en la nube: sin conexión (o sin Wi-Fi si se pidió); se lee sola al volver la red. */
        data object WaitingNetwork : PageStatus
        data class Running(val fraction: Float, val label: String) : PageStatus {
            val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 99)
        }
    }

    /** Progreso global (para la notificación del worker). */
    data class Overall(val done: Int, val total: Int, val fraction: Float, val label: String)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recognizer = AiRecognizer(llm)
    /** Maquetación de páginas + orientación (descarga opcional; sin ella, todo como antes). */
    private val layoutEngine = LayoutEngine(models, threads = if (tier.isLowRam) 2 else 4)
    private val file = File(appContext.filesDir, "ai/queue.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val jobsLock = Any()
    private val jobs = LinkedHashMap<String, AiJob>()   // protegido por jobsLock
    private val drainMutex = Mutex()
    @Volatile private var current: Pair<String, Job>? = null
    /** Páginas que la propia cola está re-editando (rescate / mejora): su aviso de "página cambiada" se ignora. */
    private val selfEditing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _status = MutableStateFlow<Map<String, PageStatus>>(emptyMap())
    val status: StateFlow<Map<String, PageStatus>> = _status.asStateFlow()

    private val _overall = MutableStateFlow<Overall?>(null)
    val overall: StateFlow<Overall?> = _overall.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Avisos discretos (p. ej. "Página 3: se usó el OCR básico (la IA no pudo leerla)"). */
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

    /** Botón "Volver a leer con IA". Devuelve un mensaje de error en español si no se puede (modelo no listo…). */
    suspend fun reread(docId: String, pageId: String): String? {
        val s = settings.settings.first()
        s.recognitionEngine.cloud?.let { id ->
            cloudProblem(s, id)?.let { return it }
            add(AiJob(docId, pageId, force = true))
            return null
        }
        val model = s.recognitionEngine.role?.let { ModelCatalog.forRole(it) }
            ?: return "Elige un motor de IA en Ajustes → Modelos IA"
        if (!models.isReady(model)) return "Descarga primero el ${model.name} en Ajustes → Modelos IA"
        if (!llm.isAvailable) return llm.unavailableReason ?: "IA no disponible en este dispositivo"
        add(AiJob(docId, pageId, force = true))
        return null
    }

    /** Quita la página de la cola (y corta su lectura si está en curso). */
    fun cancel(docId: String, pageId: String) {
        val key = "$docId/$pageId"
        synchronized(jobsLock) { jobs.remove(key) }
        persist()
        current?.takeIf { it.first == key }?.second?.cancel()
        _status.update { it - key }
    }

    /** Vacía la cola y corta la lectura en curso. */
    fun cancelAll() {
        synchronized(jobsLock) { jobs.clear() }
        persist()
        current?.second?.cancel()
        _status.value = emptyMap()
    }

    override fun onPageAdded(docId: String, pageId: String, rescueFrame: Boolean, detConfidence: Float?) {
        scope.launch {
            val s = settings.settings.first()
            if (!wantsAny(s, rescueFrame)) return@launch
            add(AiJob(docId, pageId, rescue = rescueFrame, detConfidence = detConfidence))
        }
    }

    override fun onPageChanged(docId: String, pageId: String) {
        val key = "$docId/$pageId"
        if (key in selfEditing) return
        // La lectura en curso de la versión anterior ya no vale.
        current?.takeIf { it.first == key }?.second?.cancel()
        scope.launch {
            val s = settings.settings.first()
            if (!wantsAny(s, false)) return@launch
            add(AiJob(docId, pageId, qwenDone = true))
        }
    }

    // ------------------------------------------------------------------------------------------ cola

    /** Mensaje en español si la IA en la nube [id] no se puede usar (sin clave o sin consentimiento), o null. */
    private fun cloudProblem(s: AppSettings, id: CloudProviderId): String? = when {
        !cloudKeys.has(id) -> "Guarda tu clave de ${id.company} en Ajustes → IA en la nube"
        !s.cloudConsent(id) -> "Acepta el aviso de privacidad de ${id.company} en Ajustes → IA en la nube"
        else -> null
    }

    private fun wantsAny(s: AppSettings, rescue: Boolean): Boolean {
        if (s.recognitionEngine.cloud?.let { cloudProblem(s, it) == null } == true) return true
        if (!llm.isAvailable) return false
        val rec = s.recognitionEngine.role?.let { ModelCatalog.forRole(it) }?.let { models.isReady(it) } == true
        val qwen = models.isReady(ModelCatalog.VISUAL_ASSISTANT) && (s.aiAutoEnhance || (rescue && s.aiFrameRescue))
        return rec || qwen
    }

    private fun add(job: AiJob) {
        synchronized(jobsLock) {
            val old = jobs[job.key]
            jobs[job.key] = if (old == null) job else old.copy(
                force = old.force || job.force,
                rescue = old.rescue || job.rescue,
                qwenDone = old.qwenDone && job.qwenDone,
            )
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

    private fun update(key: String, f: (AiJob) -> AiJob) {
        synchronized(jobsLock) { jobs[key]?.let { jobs[key] = f(it) } }
        persist()
    }

    private fun persist() {
        val list = snapshot()
        scope.launch(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(ListSerializer(AiJob.serializer()), list))
                tmp.renameTo(file)
            }.onFailure { Log.w(TAG, "No se pudo guardar la cola de IA", it) }
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
            Log.w(TAG, "WorkManager no disponible: cola de IA en el proceso", t)
            scope.launch { runCatching { drain {} } }
        }
    }

    /** Vuelve a lanzar la cola cuando haya red (Wi-Fi/no medida si se pidió). */
    private fun kickWhenNetwork(unmetered: Boolean) {
        try {
            val req = OneTimeWorkRequestBuilder<AiRecognitionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .addTag(AiRecognitionWorker.TAG_WORK)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(AiRecognitionWorker.WORK_NAME_NETWORK, ExistingWorkPolicy.REPLACE, req)
        } catch (t: Throwable) {
            Log.w(TAG, "WorkManager no disponible para esperar la red", t)
        }
    }

    // ------------------------------------------------------------------------------------------ proceso

    /**
     * Procesa la cola hasta vaciarla (lo llama el worker). [onOverall] recibe el progreso global. Al terminar
     * (o cancelarse) descarga el modelo.
     */
    suspend fun drain(onOverall: (Overall) -> Unit) = drainMutex.withLock {
        var done = 0
        try {
            while (true) {
                val batch = snapshot()
                if (batch.isEmpty()) break
                val s = settings.settings.first()
                val repo = documents()

                // ---- Fase 1: Qwen (rescate de encuadre, clasificación -> mejora AUTO)
                val qwenModel = ModelCatalog.VISUAL_ASSISTANT
                val qwenOk = llm.isAvailable && models.isReady(qwenModel) && ramOk(qwenModel)
                val needQwen = batch.filter { j ->
                    !j.qwenDone && qwenOk && ((j.rescue && s.aiFrameRescue) || (s.aiAutoEnhance && repo.aiDocType(j.docId, j.pageId) == null))
                }
                batch.filter { !it.qwenDone && it !in needQwen }.forEach { j -> update(j.key) { it.copy(qwenDone = true) } }
                if (needQwen.isNotEmpty() && loadModel(qwenModel)) {
                    for (j in needQwen) {
                        if (snapshot().none { it.key == j.key }) continue
                        runGuarded(j) { qwenPhase(j, s) }
                        update(j.key) { it.copy(qwenDone = true) }
                    }
                } else needQwen.forEach { j -> update(j.key) { it.copy(qwenDone = true) } }

                // ---- Fase 2: lectura con el motor elegido
                val recJobs = snapshot().filter { it.qwenDone }
                if (recJobs.isEmpty()) continue
                val cloudId = s.recognitionEngine.cloud
                if (cloudId != null) {
                    when (drainCloud(recJobs, s, cloudId, done, onOverall)) {
                        CloudOutcome.Done -> { done += recJobs.size; continue }
                        CloudOutcome.WaitNetwork -> break
                    }
                }
                val recModel = s.recognitionEngine.role?.let { ModelCatalog.forRole(it) }
                if (recModel == null) {
                    // Motor básico: la cola solo hacía la fase de Qwen.
                    recJobs.forEach { remove(it.key) }
                    continue
                }
                val reason = when {
                    !models.isReady(recModel) -> "el ${recModel.name} no está descargado"
                    !llm.isAvailable -> llm.unavailableReason ?: "IA no disponible en este dispositivo"
                    !ramOk(recModel) -> "memoria insuficiente (${models.totalRamMb()} MB; mínimo ${recModel.minRamMb} MB)"
                    !loadModel(recModel) -> "no se pudo cargar el ${recModel.name}"
                    else -> null
                }
                if (reason != null) {
                    _notices.tryEmit("Lectura con IA no disponible: $reason. Se usa el OCR básico.")
                    recJobs.forEach { remove(it.key) }
                    continue
                }
                for ((i, j) in recJobs.withIndex()) {
                    if (snapshot().none { it.key == j.key }) continue
                    val total = done + snapshot().size
                    runGuarded(j) {
                        recognize(j, s, recModel) { p ->
                            _status.update { it + (j.key to PageStatus.Running(p.fraction, p.label)) }
                            val o = Overall(done, total, p.fraction, p.label)
                            _overall.value = o
                            onOverall(o)
                        }
                    }
                    remove(j.key)
                    done++
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { llm.unload() }
                runCatching { layoutEngine.close() }
                _overall.value = null
                _status.update { m -> m.filterValues { it !is PageStatus.Running } }
            }
        }
    }

    /** Ejecuta [block] como trabajo cancelable de la página [j]; los errores no paran la cola. */
    private suspend fun runGuarded(j: AiJob, block: suspend () -> Unit) {
        try {
            coroutineScope {
                val job = launch { block() }
                current = j.key to job
                job.join()
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "IA en ${j.key}", t)
            _notices.tryEmit("No se pudo leer una página con IA (${t.message ?: t.javaClass.simpleName}); se usa el OCR básico.")
        } finally {
            current = null
        }
    }

    private fun ramOk(m: AiModel): Boolean {
        val total = models.totalRamMb()
        return total <= 0 || total >= m.minRamMb
    }

    private suspend fun loadModel(m: AiModel): Boolean {
        val cfg = models.loadConfig(m) ?: return false
        return try {
            llm.load(cfg)
            true
        } catch (e: LlmException) {
            Log.w(TAG, "carga ${m.id}", e)
            false
        }
    }

    // ------------------------------------------------------------------------------------------ nube

    private enum class CloudOutcome {
        /** Tanda terminada (leída, con fallback a ML Kit o descartada por error de cuenta). */
        Done,
        /** Sin red: las páginas siguen en la cola ("esperando red") y se relanza al volver la conexión. */
        WaitNetwork,
    }

    /**
     * Lee [jobs] con la IA en la nube [id]: sin red -> espera; clave/saldo rechazados -> aviso claro y se vacía la cola
     * (las páginas se quedan con ML Kit); otros fallos -> esa página con ML Kit y se sigue.
     */
    private suspend fun drainCloud(jobs: List<AiJob>, s: AppSettings, id: CloudProviderId, done0: Int, onOverall: (Overall) -> Unit): CloudOutcome {
        cloudProblem(s, id)?.let { msg ->
            _notices.tryEmit("Lectura en la nube no disponible: $msg. Se usa el OCR básico.")
            jobs.forEach { remove(it.key) }
            return CloudOutcome.Done
        }
        var done = done0
        for (j in jobs) {
            if (snapshot().none { it.key == j.key }) continue
            if (!CloudNetwork.isAvailable(appContext, s.cloudWifiOnly)) return waitForNetwork(s)
            val total = done + snapshot().size
            var failure: CloudException? = null
            runGuarded(j) {
                try {
                    recognizeCloud(j, id) { p ->
                        _status.update { it + (j.key to PageStatus.Running(p.fraction, p.label)) }
                        val o = Overall(done, total, p.fraction, p.label)
                        _overall.value = o
                        onOverall(o)
                    }
                } catch (e: CloudException) {
                    failure = e
                }
            }
            val f = failure
            when {
                f == null -> Unit
                f.stopsQueue -> {
                    val msg = when (f) {
                        is CloudException.Billing -> "${id.company}: la cuenta no tiene saldo. Recárgala y usa «Volver a leer con IA»."
                        else -> "${id.company} rechazó la clave de API (${f.message}). Revísala en Ajustes → IA en la nube."
                    }
                    cloudUsage.setError(id, msg)
                    _notices.tryEmit("$msg Las páginas se quedan con el OCR básico.")
                    snapshot().forEach { remove(it.key) }
                    return CloudOutcome.Done
                }
                f is CloudException.Network -> return waitForNetwork(s)
                else -> {
                    _notices.tryEmit("Página ${pageNumber(j)}: la IA en la nube no pudo leerla (${f.message}); se mantiene el OCR básico.")
                }
            }
            remove(j.key)
            done++
        }
        return CloudOutcome.Done
    }

    private fun waitForNetwork(s: AppSettings): CloudOutcome {
        val keys = snapshot().map { it.key }
        _status.update { m -> m + keys.associateWith { PageStatus.WaitingNetwork } }
        _notices.tryEmit(
            if (s.cloudWifiOnly) "Lectura en la nube en pausa: esperando Wi-Fi (${keys.size} pág.)."
            else "Lectura en la nube en pausa: sin conexión (${keys.size} pág.). Seguirá sola al volver la red.",
        )
        kickWhenNetwork(s.cloudWifiOnly)
        return CloudOutcome.WaitNetwork
    }

    private suspend fun pageNumber(j: AiJob): Int = (documents().get(j.docId)?.pages?.indexOfFirst { it.id == j.pageId } ?: -1) + 1

    private suspend fun recognizeCloud(j: AiJob, id: CloudProviderId, onProgress: (AiRecognizer.Progress) -> Unit) {
        val repo = documents()
        val page0 = repo.getPage(j.docId, j.pageId) ?: return
        if (!j.force && repo.hasAiLayout(j.docId, j.pageId)) return
        onProgress(AiRecognizer.Progress(0.01f, "Preparando"))
        val prep = prepare(j, page0, CLOUD_DECODE_PIXELS, onProgress) ?: return
        val edits = prep.edits
        val bmp = prep.bmp
        val fullW = prep.fullW
        val fullH = prep.fullH
        val result = try {
            // OCR básico de la misma versión: POSICIONES fiables sobre las que se coloca el texto de la nube.
            val mlkit = runCatching { repo.ensureOcr(j.docId, j.pageId) }.getOrNull()
            // Maquetación LOCAL (regiones PP-DocLayoutV3 si están descargadas + líneas del OCR básico + rayas, fondos,
            // dibujos y estilo): la geometría del modo híbrido. La nube solo pone el texto.
            val local = withContext(Dispatchers.Default) {
                runCatching { localLayoutForCloud(bmp, mlkit, fullW, fullH, prep.rotation, repo.aiDocType(j.docId, j.pageId)) }
                    .onFailure { Log.w(TAG, "maquetación local para la nube", it) }.getOrNull()
            }
            cloud.read(id, bmp, fullW, fullH, mlkit, onProgress, local?.first, local?.second.orEmpty())
        } finally {
            bmp.recycle()
        }
        val number = pageNumber(j)
        if (result == null) {
            _notices.tryEmit("Página $number: la IA en la nube devolvió muy poco texto; se mantiene el OCR básico.")
            return
        }
        val saved = repo.saveAiResult(j.docId, j.pageId, edits, result.ocr, result.layout)
        if (!saved) Log.i(TAG, "${j.key}: la página cambió mientras se leía en la nube; resultado descartado")
        result.warning?.let { _notices.tryEmit("Página $number: $it") }
    }

    // ------------------------------------------------------------------------------------------ fase Qwen

    private suspend fun qwenPhase(j: AiJob, s: AppSettings) {
        val repo = documents()
        val doc = repo.get(j.docId) ?: return
        var page = doc.pages.firstOrNull { it.id == j.pageId } ?: return
        val orig = repo.originalFile(j.docId, page)
        if (!orig.exists()) return
        _status.update { it + (j.key to PageStatus.Running(0.02f, "Analizando la foto")) }

        if (j.rescue && s.aiFrameRescue) {
            val newQuad = rescueFrame(orig, page.width, page.height, page.edits.quad, j.detConfidence)
            if (newQuad != null) {
                val cur = repo.getPage(j.docId, j.pageId)
                if (cur != null && cur.edits.quad == page.edits.quad) {
                    page = selfEdit(j) { repo.updateEdits(j.docId, j.pageId, cur.edits.copy(quad = newQuad)) } ?: page
                }
            }
        }
        if (s.aiAutoEnhance && repo.aiDocType(j.docId, j.pageId) == null) {
            val thumb = withContext(Dispatchers.IO) { BitmapIO.decodeThumbnail(orig.absolutePath, CLASSIFY_SIDE) }
            val cls = try { recognizer.classify(thumb) } finally { thumb.recycle() }
            repo.saveAiDocType(j.docId, j.pageId, cls)
            Log.i(TAG, "${j.key}: tipo = $cls")
            val cur = repo.getPage(j.docId, j.pageId) ?: return
            AiEnhance.decide(cls, doc.mode, cur.edits)?.let { e ->
                selfEdit(j) { repo.updateEdits(j.docId, j.pageId, e) }
                _notices.tryEmit("Mejora automática para ${AiEnhance.label(cls)?.lowercase() ?: cls} aplicada")
            }
        }
    }

    private suspend fun <T> selfEdit(j: AiJob, block: suspend () -> T): T? {
        selfEditing.add(j.key)
        return try { block() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) {
            Log.w(TAG, "edición IA ${j.key}", t); null
        } finally { selfEditing.remove(j.key) }
    }

    /**
     * Rescate de encuadre: caja del documento según Qwen (foto <= 896 px) -> ROI ampliada un 6 % -> detector
     * clásico dentro de la ROI. Si el detector la encuentra (confianza >= 0,35, área razonable) se usa su quad; si
     * no, la caja de Qwen (error medido 4,5–10 % frente al 33–36 % de "imagen completa"), pero solo si la página
     * no tenía ya un quad. Devuelve el quad en el espacio de la página ([pw] x [ph]) o null.
     */
    private suspend fun rescueFrame(orig: File, pw: Int, ph: Int, currentQuad: Quad?, detConf: Float?): Quad? {
        val photo = withContext(Dispatchers.IO) { BitmapIO.decodeThumbnail(orig.absolutePath, DOCBOX_SIDE) }
        val box = try { recognizer.locateDocument(photo) } finally { photo.recycle() } ?: return null
        val work = withContext(Dispatchers.IO) { BitmapIO.decode(orig.absolutePath, RESCUE_PIXELS) }
        try {
            val r = AiEnhance.roi(box, work.width, work.height)
            val sx = pw.toFloat() / work.width
            val sy = ph.toFloat() / work.height
            val crop = Bitmap.createBitmap(work, r[0], r[1], r[2] - r[0], r[3] - r[1])
            try {
                val det = withContext(Dispatchers.Default) { runCatching { detector().detect(crop) }.getOrNull() }
                if (det != null && det.confidence >= 0.35f && det.frameWidth > 0 && det.frameHeight > 0) {
                    val fx = crop.width.toFloat() / det.frameWidth
                    val fy = crop.height.toFloat() / det.frameHeight
                    val q = Quad.of(det.quad.points().map { p -> Pt((p.x * fx + r[0]) * sx, (p.y * fy + r[1]) * sy) })
                    val a = AiEnhance.area(q) / (sx * sy)
                    val roiArea = (r[2] - r[0]).toFloat() * (r[3] - r[1])
                    if (a >= 0.3f * roiArea && a <= 0.985f * work.width * work.height &&
                        (currentQuad == null || det.confidence > (detConf ?: 0f))
                    ) return q
                }
                if (currentQuad != null) return null
                val tight = AiEnhance.roi(box, work.width, work.height, margin = 0.01f)
                return AiEnhance.rectQuad(tight, sx, sy)
            } finally {
                if (crop !== work) crop.recycle()
            }
        } finally {
            work.recycle()
        }
    }

    // ------------------------------------------------------------------------------------------ lectura

    private suspend fun recognize(j: AiJob, s: AppSettings, model: AiModel, onProgress: (AiRecognizer.Progress) -> Unit) {
        val repo = documents()
        val page0 = repo.getPage(j.docId, j.pageId) ?: return
        if (!j.force && repo.hasAiLayout(j.docId, j.pageId)) return
        onProgress(AiRecognizer.Progress(0.01f, "Preparando"))
        val prep = prepare(j, page0, decodePixels(), onProgress) ?: return
        val edits = prep.edits
        val bmp = prep.bmp
        val fullW = prep.fullW
        val fullH = prep.fullH
        val docType = AiEnhance.docTypeCode(repo.aiDocType(j.docId, j.pageId))
        val result = try {
            // OCR básico de la misma versión (rápido): base para validar la IA, orientar tablas y alinear el texto de Qwen.
            val mlkit = runCatching { repo.ensureOcr(j.docId, j.pageId) }.getOrNull()
            when (model.role) {
                ModelRole.DOCUMENT_READER -> {
                    // Por partes si está la maquetación de páginas; si no (o no sale nada), la tubería de siempre.
                    val analysis = if (layoutEngine.isReady()) withContext(Dispatchers.Default) { LayoutEngine.toRgb(bmp, LayoutEngine.ANALYSIS_SIDE) } else null
                    val regions = analysis?.let { a -> withContext(Dispatchers.Default) { layoutEngine.regions(a) } }
                    val parts = if (analysis != null && regions != null) {
                        recognizer.readByParts(analysis, regions, fullW, fullH, mlkit, docType, prep.rotation, onProgress)
                    } else null
                    parts ?: recognizer.readWithPaddle(bmp, fullW, fullH, mlkit, docType, onProgress)
                }
                ModelRole.VISUAL_ASSISTANT -> recognizer.readWithQwen(bmp, fullW, fullH, mlkit, docType, onProgress)
                ModelRole.PAGE_LAYOUT -> null  // no es un motor de lectura
            }
        } finally {
            bmp.recycle()
        }
        val number = (repo.get(j.docId)?.pages?.indexOfFirst { it.id == j.pageId } ?: -1) + 1
        if (result == null) {
            _notices.tryEmit("Página $number: la IA no pudo leerla bien; se mantiene el OCR básico.")
            return
        }
        // El texto corregido por el usuario se conserva (saveAiResult no lo toca).
        val saved = repo.saveAiResult(j.docId, j.pageId, edits, result.ocr, result.layout)
        if (!saved) Log.i(TAG, "${j.key}: la página cambió mientras se leía; resultado descartado")
        result.warning?.let { _notices.tryEmit("Página $number: $it") }
    }

    private fun decodePixels(): Int = if (tier.isLowRam) 4_000_000 else 6_000_000

    /** Página lista para leer: ediciones, procesado decodificado (DERECHO), tamaño completo y giro aplicado por la IA. */
    private class Prepared(val edits: PageEdits, val bmp: Bitmap, val fullW: Int, val fullH: Int, val rotation: Int)

    /**
     * Decodifica el procesado de la página y, si está la "Maquetación de páginas", comprueba la ORIENTACIÓN
     * (PP-LCNet doc_ori, voto de 5 recortes; 20–90 ms): una página girada se endereza en sus ediciones (giro horario
     * tras el recorte), así la imagen procesada, la miniatura, el PDF y las coordenadas de la maquetación quedan
     * derechos y coherentes. Se hace aquí (antes de la IA) y no en la tubería de página porque en fotos reales acierta
     * el 82–94 %: un giro equivocado se nota mucho y solo compensa cuando la IA va a leer (Paddle lee en vertical
     * inventando texto). Solo si la página no tiene ya un giro (respeta el del usuario y evita girar dos veces).
     */
    private suspend fun prepare(j: AiJob, page0: Page, decodePx: Int, onProgress: (AiRecognizer.Progress) -> Unit): Prepared? {
        val repo = documents()
        var processed = repo.processedFile(j.docId, page0)
        var page = repo.getPage(j.docId, j.pageId) ?: return null
        var bmp = withContext(Dispatchers.IO) { BitmapIO.decode(processed.absolutePath, decodePx) }
        var rotation = 0
        try {
            if (page.edits.rotation == 0 && layoutEngine.isReady()) {
                onProgress(AiRecognizer.Progress(0.02f, "Comprobando la orientación"))
                val o = withContext(Dispatchers.Default) { layoutEngine.orientation(bmp) }
                val ccw = o?.let { DocOrientation.correction(it) } ?: 0
                Log.i(TAG, "${j.key}: orientación ${o?.angle}° (conf ${o?.confidence}) → giro $ccw°")
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

    /**
     * Maquetación local para el modo nube híbrido: regiones PP-DocLayoutV3 (si está la descarga) + líneas del OCR básico
     * (posiciones fiables) + rayas, fondos, dibujos y estilo ([PartsPipeline] sin modelo de lectura). Devuelve la
     * maquetación (px de la imagen completa) y las regiones para [CloudAlign.LocalRegion].
     */
    private suspend fun localLayoutForCloud(bmp: Bitmap, mlkit: OcrResult?, fullW: Int, fullH: Int, rotation: Int, aiDocType: String?): Pair<DocLayout, List<CloudAlign.LocalRegion>> {
        val a = LayoutEngine.toRgb(bmp, LayoutEngine.ANALYSIS_SIDE)
        val regions = if (layoutEngine.isReady()) layoutEngine.regions(a).orEmpty() else emptyList()
        val sx = a.w.toFloat() / fullW; val sy = a.h.toFloat() / fullH
        val lines = mlkit?.let { AiRecognizer.scaleOcr(it, fullW, fullH) }?.blocks?.flatMap { it.lines }.orEmpty()
            .filter { it.text.isNotBlank() }
            .map { l ->
                val x0 = l.box.left * sx; val y0 = l.box.top * sy; val x1 = l.box.right * sx; val y1 = l.box.bottom * sy
                PartsPipeline.Item(l.text, floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), floatArrayOf(x0, y0, x1, y1))
            }
        val docType = AiEnhance.docTypeCode(aiDocType)
        val out = PartsPipeline(PartsPipeline.NoReader).run(PartsPipeline.Input(a, regions, docType, ModelCatalog.PAGE_LAYOUT.id, rotation, presetLines = lines))
        val layout = PartsPipeline.scaleLayout(out.layout, fullW, fullH)
        val local = regions.map { r ->
            CloudAlign.LocalRegion(r.type, OcrRect(r.box[0] / sx, r.box[1] / sy, r.box[2] / sx, r.box[3] / sy), r.score)
        }
        return layout to local
    }

    companion object {
        private const val TAG = "AiRecognitionQueue"
        private const val CLASSIFY_SIDE = 512
        private const val DOCBOX_SIDE = 896
        private const val RESCUE_PIXELS = 3_000_000
        /** La nube recibe como mucho ~2,5 MP (Claude 1568 px de lado): no hace falta decodificar más. */
        private const val CLOUD_DECODE_PIXELS = 3_000_000
    }
}
