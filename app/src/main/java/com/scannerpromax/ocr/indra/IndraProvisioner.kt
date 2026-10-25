package com.scannerpromax.ocr.indra

import android.util.Log
import com.scannerpromax.ai.ModelCatalog
import com.scannerpromax.ai.ModelManager
import com.scannerpromax.ai.ModelState
import com.scannerpromax.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Indra es OBLIGATORIO (único OCR) y SIN FRICCIÓN: este gestor descarga solo sus pesos (~25 MB) + ONNX Runtime (~13 MB)
 *  - en el primer arranque ([start]) y cada vez que haga falta ([ensure]: una página no se pudo reconocer porque falta),
 *    respetando "Descargas solo con Wi-Fi" (si no hay red, WorkManager espera y la página queda pendiente);
 *  - con progreso discreto: notificación silenciosa de la descarga (ModelDownloadWorker) y [state] para la interfaz;
 *  - y al terminar ([onReady]) se reconocen solas las páginas pendientes y sigue la cola de maquetación.
 *
 * Pesos dentro del APK: descartado (APK arm64 ~20 MB + 24,7 MB de pesos + ORT, que hoy también se descarga, superaría
 * los 30 MB; además la licencia OpenRAIL-M de los pesos pide trasladar sus restricciones si se redistribuyen).
 */
class IndraProvisioner(
    private val models: ModelManager,
    private val settings: SettingsRepository,
    /** Se llama cuando Indra queda listo (reconocer pendientes, reanudar la cola). */
    private val onReady: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    @Volatile private var lastEnsure = 0L

    private val _message = MutableStateFlow<String?>(null)
    /** Último problema al empezar la descarga (sin espacio, ABI no compatible…), o null. */
    val message: StateFlow<String?> = _message.asStateFlow()

    val model get() = ModelCatalog.FAST_READER

    fun isReady(): Boolean = models.isReady(model)

    /** Estado de la descarga de Indra (para "Reconocimiento" en Ajustes y el aviso discreto del Documento). */
    fun state(): ModelState = models.state(model)

    /** Primer arranque / cada arranque: descarga Indra si falta y vigila cuándo queda listo. Idempotente. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Vigila la transición a "listo" (descarga terminada aquí o reinstalación desde Ajustes).
            var wasReady = isReady()
            models.states.map { m -> m[model.id]?.let { it is ModelState.Ready } ?: isReady() }.distinctUntilChanged().collect { ready ->
                if (ready && !wasReady) {
                    Log.i(TAG, "Indra listo: reconociendo páginas pendientes")
                    runCatching { onReady() }.onFailure { Log.w(TAG, "onReady", it) }
                }
                wasReady = ready
            }
        }
        scope.launch {
            models.refresh()
            ensureNow(force = true)
        }
    }

    /** Hace falta el OCR y no está: arranca la descarga si no está ya en marcha (con un mínimo de 30 s entre intentos). */
    fun ensure() {
        val now = System.currentTimeMillis()
        if (now - lastEnsure < RETRY_MS) return
        lastEnsure = now
        scope.launch { ensureNow(force = false) }
    }

    private suspend fun ensureNow(force: Boolean) {
        if (isReady()) return
        val st = models.state(model)
        val busy = st is ModelState.Queued || st is ModelState.Downloading || st is ModelState.Verifying
        if (busy && !force) return
        val wifiOnly = runCatching { settings.settings.first().aiWifiOnly }.getOrDefault(true)
        val err = runCatching { models.enqueueDownload(model, wifiOnly) }.getOrElse { it.message ?: "No se pudo iniciar la descarga" }
        _message.value = err
        if (err != null) Log.w(TAG, "descarga de Indra: $err")
    }

    companion object {
        private const val TAG = "IndraProvisioner"
        private const val RETRY_MS = 30_000L
    }
}
