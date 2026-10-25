package com.scannerpromax.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.scannerpromax.ScannerApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Descarga un modelo en segundo plano, como servicio en primer plano de tipo dataSync
 * con notificación de progreso. Sobrevive a salir de la pantalla; si el sistema mata el proceso, WorkManager lo
 * reintenta y la descarga se reanuda desde el `.part` (HTTP Range).
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val model = ModelCatalog.byId(id) ?: return Result.failure(workDataOf(KEY_ERROR to "Modelo desconocido: $id"))
        val manager = (applicationContext as ScannerApp).container.models
        ensureChannel()
        runCatching { setForeground(foregroundInfo(model, "Preparando…", 0, 0)) }
            .onFailure { Log.w(TAG, "setForeground", it) }

        return try {
            coroutineScope {
                // Notificación como mucho cada ~1 s (Android limita la frecuencia de actualizaciones).
                val progress = MutableStateFlow(Triple(0L, 0L, "Descargando"))
                val notifier = launch {
                    progress.collect { (done, total, phase) ->
                        runCatching { setForeground(foregroundInfo(model, phase, done, total)) }
                        kotlinx.coroutines.delay(1000)
                    }
                }
                try {
                    manager.performDownload(model, isCancelled = { isStopped }) { d, t, phase ->
                        progress.value = Triple(d, t, phase)
                    }
                } finally {
                    notifier.cancel()
                }
            }
            manager.refresh()
            Result.success()
        } catch (e: ResumableDownloader.CancelledException) {
            manager.refresh()
            Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelDownloadException) {
            Log.w(TAG, "descarga ${model.id}", e)
            val retryable = e.cause is java.io.IOException && e.cause !is ResumableDownloader.VerificationException
            manager.refresh()
            if (retryable && runAttemptCount < 5) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Error en la descarga")))
        } catch (t: Throwable) {
            Log.e(TAG, "descarga ${model.id}", t)
            manager.refresh()
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: t.javaClass.simpleName)))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val model = ModelCatalog.byId(inputData.getString(KEY_MODEL_ID) ?: "") ?: ModelCatalog.all.first()
        ensureChannel()
        return foregroundInfo(model, "Preparando…", 0, 0)
    }

    private fun foregroundInfo(model: AiModel, phase: String, done: Long, total: Long): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val text = if (total > 0) "$phase · ${ModelManager.formatBytes(done)} de ${ModelManager.formatBytes(total)}" else phase
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Descargando ${model.name}")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, pct, total <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancel)
            .build()
        val notifId = NOTIFICATION_BASE + (model.id.hashCode() and 0xFFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Descargas del lector de texto", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso de la descarga del lector de texto Indra y de la maquetación"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_BASE = 0x4D00
    }
}
