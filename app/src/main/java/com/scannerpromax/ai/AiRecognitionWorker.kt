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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scannerpromax.ScannerApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Procesa la cola de lectura con IA ([AiRecognitionQueue.drain]) como servicio en primer plano con notificación
 * "Leyendo con IA… página x de n · 40 %" y botón Cancelar. Sobrevive a salir de la app; si el sistema lo detiene,
 * WorkManager lo vuelve a lanzar y la cola (guardada en disco) sigue donde iba.
 */
class AiRecognitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val queue = (applicationContext as ScannerApp).container.aiQueue
        if (queue.pending() == 0) return Result.success()
        ensureChannel()
        runCatching { setForeground(info("Preparando…", 0, 0, 0f)) }.onFailure { Log.w(TAG, "setForeground", it) }
        return try {
            coroutineScope {
                val progress = MutableStateFlow<AiRecognitionQueue.Overall?>(null)
                val notifier = launch {
                    progress.collect { o ->
                        if (o != null) runCatching { setForeground(info(o.label, o.done, o.total, o.fraction)) }
                        delay(1000)
                    }
                }
                try {
                    queue.drain { progress.value = it }
                } finally {
                    notifier.cancel()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            // Cancelado con el botón de la notificación: se vacía la cola. Si lo paró el sistema, la cola se conserva
            // en disco y WorkManager reintenta.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                queue.cancelAll()
            }
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "cola de IA", t)
            Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        return info("Preparando…", 0, 0, 0f)
    }

    private fun info(label: String, done: Int, total: Int, fraction: Float): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val pct = (fraction * 100).toInt().coerceIn(0, 99)
        val text = if (total > 0) "Página ${done + 1} de $total · $label · $pct %" else label
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Leyendo con IA…")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, pct, total == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lectura con IA", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso del reconocimiento de páginas con la IA del teléfono"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val TAG = "AiRecognitionWorker"
        const val WORK_NAME = "ai-recognition"
        const val TAG_WORK = "ai-recognition"
        const val CHANNEL_ID = "ai_recognition"
        private const val NOTIFICATION_ID = 0x4E01
    }
}
