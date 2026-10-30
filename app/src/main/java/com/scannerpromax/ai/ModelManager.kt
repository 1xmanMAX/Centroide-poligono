package com.scannerpromax.ai

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Estado de un modelo para la interfaz. */
sealed interface ModelState {
    data object NotDownloaded : ModelState
    /** Hay una descarga a medias en disco y nada en marcha: se puede reanudar. */
    data class Paused(val downloaded: Long, val total: Long) : ModelState
    /** En cola (p. ej. esperando Wi-Fi). */
    data class Queued(val downloaded: Long, val total: Long, val waitingForNetwork: Boolean) : ModelState
    data class Downloading(val downloaded: Long, val total: Long, val bytesPerSecond: Long, val fileName: String) : ModelState {
        val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Verifying(val fileName: String) : ModelState
    /** [warning]: listo en disco pero con un problema al usarlo (p. ej. ONNX Runtime no carga en este teléfono). */
    data class Ready(val diskBytes: Long, val warning: String? = null) : ModelState
    data class Failed(val message: String, val downloaded: Long, val total: Long) : ModelState
}

/**
 * Gestor de modelos descargables (Indra y la maquetación): estado, descarga en segundo plano (WorkManager con servicio
 * en primer plano y notificación de progreso, sobrevive a salir de la pantalla), comprobación de espacio, verificación
 * y borrado. Archivos en filesDir/models/<id>/.
 */
class ModelManager(context: Context) {
    private val appContext = context.applicationContext
    val storage = ModelStorage(File(appContext.filesDir, "models"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downloader = ResumableDownloader()

    /** Estado transitorio publicado por el worker en curso (mismo proceso). */
    private val live = ConcurrentHashMap<String, ModelState>()
    private val workStates = ConcurrentHashMap<String, WorkInfo.State>()
    private val workErrors = ConcurrentHashMap<String, String>()

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    init {
        // Restos de versiones anteriores que descargaban libonnxruntime.so: se borran (ya va en la app).
        scope.launch(Dispatchers.IO) { runCatching { storage.purgeLegacyNativeLibs() }; refresh() }
        scope.launch {
            runCatching {
                WorkManager.getInstance(appContext).getWorkInfosByTagFlow(WORK_TAG)
                    .catch { Log.w(TAG, "work infos", it) }
                    .collect { infos ->
                        workStates.clear()
                        // Puede haber varias ejecuciones históricas por modelo: manda la más reciente no terminada.
                        for (info in infos.sortedBy { if (it.state.isFinished) 0 else 1 }) {
                            val id = info.tags.firstOrNull { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX) ?: continue
                            workStates[id] = info.state
                            if (info.state == WorkInfo.State.FAILED) {
                                info.outputData.getString(ModelDownloadWorker.KEY_ERROR)?.let { workErrors[id] = it }
                            }
                        }
                        refresh()
                    }
            }.onFailure { Log.w(TAG, "WorkManager no disponible", it) }
        }
    }

    fun state(model: AiModel): ModelState = _states.value[model.id] ?: ModelState.NotDownloaded

    fun isReady(model: AiModel): Boolean = storage.isReady(model)

    /** Recalcula los estados a partir del disco, del worker en curso y de WorkManager. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val map = ModelCatalog.all.associate { m -> m.id to computeState(m) }
        _states.value = map
    }

    private fun computeState(m: AiModel): ModelState {
        live[m.id]?.let { return it }
        val total = storage.totalBytes(m)
        val done = storage.downloadedBytes(m)
        return when (workStates[m.id]) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelState.Queued(done, total, waitingForNetwork = true)
            WorkInfo.State.RUNNING -> ModelState.Downloading(done, total, 0, "")
            WorkInfo.State.FAILED -> if (storage.isReady(m)) readyState(m)
                else ModelState.Failed(workErrors[m.id] ?: "Error en la descarga", done, total)
            else -> when {
                storage.isReady(m) -> readyState(m)
                storage.hasPartial(m) || done > 0 -> ModelState.Paused(done, total)
                else -> ModelState.NotDownloaded
            }
        }
    }

    private fun readyState(m: AiModel) = ModelState.Ready(
        storage.diskUsage(m),
        warning = com.scannerpromax.ai.parts.OrtNative.lastError,
    )

    /** Bytes a bajar de [model] (solo datos: pesos y tablas). */
    fun downloadSize(model: AiModel): Long = storage.totalBytes(model)

    private fun publishLive(id: String, s: ModelState?) {
        if (s == null) live.remove(id) else live[id] = s
        val m = ModelCatalog.byId(id) ?: return
        _states.update { it + (id to computeState(m)) }
    }

    // --------------------------------------------------------------------------------------- acciones

    /**
     * Encola la descarga en segundo plano. Devuelve un mensaje de error (en español) si no se puede empezar
     * (p. ej. sin espacio), o null si quedó en cola.
     */
    fun enqueueDownload(model: AiModel, wifiOnly: Boolean): String? {
        val need = requiredFreeBytes(model)
        val free = freeBytes()
        if (need > free) return "Espacio insuficiente: hacen falta ${formatBytes(need)} y hay ${formatBytes(free)} libres"
        workErrors.remove(model.id)
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to model.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_TAG)
            .addTag(TAG_PREFIX + model.id)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, req)
        publishLive(model.id, null)
        _states.update { it + (model.id to ModelState.Queued(storage.downloadedBytes(model), storage.totalBytes(model), wifiOnly)) }
        return null
    }

    /** Cancela (pausa) la descarga: lo bajado se conserva para reanudar. */
    fun cancelDownload(model: AiModel) {
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(model))
        publishLive(model.id, null)
        scope.launch { refresh() }
    }

    /** Borra el modelo del disco (y cancela su descarga). El llamador libera antes el motor que lo use. */
    suspend fun delete(model: AiModel) {
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(model))
        withContext(Dispatchers.IO) { storage.delete(model) }
        publishLive(model.id, null)
        workErrors.remove(model.id)
        refresh()
    }

    /**
     * Trabajo real de descarga (lo ejecuta [ModelDownloadWorker]). Lanza excepción con mensaje en español si falla.
     * [onProgress] se llama con (bajado, total) para la notificación.
     */
    suspend fun performDownload(
        model: AiModel,
        isCancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long, phase: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val need = requiredFreeBytes(model)
            val free = freeBytes()
            if (need > free) throw ModelDownloadException("Espacio insuficiente: hacen falta ${formatBytes(need)} y hay ${formatBytes(free)} libres")
            storage.dir(model).mkdirs()
            val total = storage.totalBytes(model)
            var speedT0 = System.nanoTime()
            var speedB0 = storage.downloadedBytes(model)
            var speed = 0L
            for (f in model.files) {
                if (storage.isComplete(model, f)) continue
                val before = model.files.takeWhile { it !== f }.sumOf { it.sizeBytes.coerceAtLeast(0) }
                try {
                    downloader.download(
                        url = f.url,
                        dest = storage.file(model, f),
                        expectedSize = f.sizeBytes,
                        sha256 = null,  // se verifica abajo con estado "Verificando"
                        isCancelled = isCancelled,
                    ) { done, _ ->
                        val now = System.nanoTime()
                        val overall = before + done
                        val dt = (now - speedT0) / 1e9
                        if (dt >= 2.0) {
                            speed = ((overall - speedB0) / dt).toLong().coerceAtLeast(0)
                            speedT0 = now
                            speedB0 = overall
                        }
                        publishLive(model.id, ModelState.Downloading(overall, total, speed, f.fileName))
                        onProgress(overall, total, "Descargando")
                    }
                    f.sha256?.let { sha ->
                        publishLive(model.id, ModelState.Verifying(f.fileName))
                        onProgress(before + f.sizeBytes, total, "Verificando")
                        val actual = ResumableDownloader.sha256Of(storage.file(model, f), isCancelled)
                        if (!actual.equals(sha, ignoreCase = true)) {
                            storage.file(model, f).delete()
                            throw ModelDownloadException("El archivo ${f.fileName} llegó dañado; vuelve a descargarlo")
                        }
                    }
                } catch (e: ResumableDownloader.CancelledException) {
                    throw e
                } catch (e: ResumableDownloader.VerificationException) {
                    throw ModelDownloadException("Verificación fallida de ${f.fileName}: ${e.message}", e)
                } catch (e: java.io.IOException) {
                    throw ModelDownloadException("Fallo de red al descargar ${f.fileName}: ${e.message ?: e.javaClass.simpleName}", e)
                }
            }
        } finally {
            publishLive(model.id, null)
        }
    }

    // --------------------------------------------------------------------------------------- utilidades

    fun requiredFreeBytes(model: AiModel): Long {
        val remaining = storage.remainingBytes(model)
        return remaining + SAFETY_MARGIN_BYTES
    }

    fun freeBytes(): Long = runCatching {
        storage.root.mkdirs()
        StatFs(storage.root.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    fun totalRamMb(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.totalMem / (1024 * 1024)).toInt()
    }

    private fun workName(model: AiModel) = "model-download-${model.id}"

    companion object {
        private const val TAG = "ModelManager"
        const val WORK_TAG = "model-download"
        const val TAG_PREFIX = "model-id:"
        const val SAFETY_MARGIN_BYTES = 50L * 1024 * 1024

        fun formatBytes(b: Long): String {
            val mb = b / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format(java.util.Locale("es"), "%.2f GB", mb / 1024)
            else String.format(java.util.Locale("es"), "%.0f MB", mb)
        }
    }
}

class ModelDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
