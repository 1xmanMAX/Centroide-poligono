package com.scannerpromax.ai.docgen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scannerpromax.ScannerApp
import com.scannerpromax.ai.cloud.CloudException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.UUID

/** Estado de un trabajo de reconstrucción (lo ve la hoja aunque se salga y se vuelva a la pantalla). */
data class DocGenJobState(
    val jobId: String,
    val spec: DocGenSpec,
    val status: Status,
    val progress: DocGenProgress? = null,
    val files: List<OutputFile> = emptyList(),
    val costUsd: Double = 0.0,
    val warnings: List<String> = emptyList(),
    val partial: Boolean = false,
    val error: String? = null,
    /** Fallo de cuenta (clave/saldo): la hoja ofrece ir a Ajustes › IA en la nube. */
    val accountError: Boolean = false,
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }
    val active: Boolean get() = status == Status.QUEUED || status == Status.RUNNING
}

/** Preferencias de la reconstrucción (modelo de calidad, proveedor rápido, formatos, consentimiento por proveedor). */
class DocGenPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("docgen", Context.MODE_PRIVATE)

    var qualityModel: DocGenModel
        get() = runCatching { DocGenModel.valueOf(prefs.getString("quality_model", null) ?: "") }.getOrDefault(DocGenModel.CLAUDE_SONNET)
        set(v) = prefs.edit().putString("quality_model", v.name).apply()

    var fastModel: DocGenModel
        get() = runCatching { DocGenModel.valueOf(prefs.getString("fast_model", null) ?: "") }.getOrDefault(DocGenModel.CLAUDE_HAIKU)
        set(v) = prefs.edit().putString("fast_model", v.name).apply()

    var formats: Set<OutputFormat>
        get() = prefs.getStringSet("formats", null)?.mapNotNull { runCatching { OutputFormat.valueOf(it) }.getOrNull() }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: setOf(OutputFormat.DOCX)
        set(v) = prefs.edit().putStringSet("formats", v.map { it.name }.toSet()).apply()

    var mode: DocGenMode
        get() = runCatching { DocGenMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(DocGenMode.FAST)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    fun consented(m: DocGenModel): Boolean = prefs.getBoolean("consent_${m.keyId.name}", false)
    fun setConsent(m: DocGenModel) = prefs.edit().putBoolean("consent_${m.keyId.name}", true).apply()
}

/**
 * Cola de reconstrucciones: cada una es un trabajo de WorkManager en primer plano (notificación con progreso y
 * Cancelar) que sobrevive a salir de la pantalla. El estado se publica en [states] (el trabajo corre en el mismo
 * proceso). Si el sistema mata el proceso, el trabajo NO se repite solo (volvería a cobrar): queda como fallido.
 */
class DocGenJobs(context: Context) {
    private val app = context.applicationContext
    private val _states = MutableStateFlow<Map<String, DocGenJobState>>(emptyMap())
    val states: StateFlow<Map<String, DocGenJobState>> = _states.asStateFlow()

    fun latestFor(docId: String): DocGenJobState? = _states.value.values.filter { it.spec.docId == docId }.maxByOrNull { it.jobId.substringBefore('-').toLongOrNull() ?: 0L }

    fun start(spec: DocGenSpec): String {
        val jobId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        _states.update { it + (jobId to DocGenJobState(jobId, spec, DocGenJobState.Status.QUEUED)) }
        val data = Data.Builder()
            .putString(K_JOB, jobId)
            .putString(K_DOC, spec.docId)
            .putString(K_PAGES, spec.pageIds?.joinToString(","))
            .putString(K_FORMATS, spec.formats.joinToString(",") { it.name })
            .putString(K_MODE, spec.mode.name)
            .putString(K_MODEL, spec.model.name)
            .putString(K_FILENAME, spec.filename)
            .putString(K_TITLE, spec.title)
            .build()
        val req = OneTimeWorkRequestBuilder<DocGenWorker>()
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_WORK)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_PREFIX + jobId, ExistingWorkPolicy.KEEP, req)
        return jobId
    }

    fun cancel(jobId: String) {
        WorkManager.getInstance(app).cancelUniqueWork(WORK_PREFIX + jobId)
        _states.update { m -> m[jobId]?.takeIf { it.active }?.let { m + (jobId to it.copy(status = DocGenJobState.Status.CANCELLED)) } ?: m }
    }

    fun dismiss(jobId: String) = _states.update { it - jobId }

    internal fun update(jobId: String, spec: DocGenSpec?, f: (DocGenJobState) -> DocGenJobState) = _states.update { m ->
        val cur = m[jobId] ?: spec?.let { DocGenJobState(jobId, it, DocGenJobState.Status.RUNNING) } ?: return@update m
        m + (jobId to f(cur))
    }

    companion object {
        const val TAG_WORK = "docgen"
        const val WORK_PREFIX = "docgen-"
        internal const val K_JOB = "job"; internal const val K_DOC = "doc"; internal const val K_PAGES = "pages"
        internal const val K_FORMATS = "formats"; internal const val K_MODE = "mode"; internal const val K_MODEL = "model"
        internal const val K_FILENAME = "filename"; internal const val K_TITLE = "title"

        fun specFrom(d: Data): DocGenSpec? = runCatching {
            DocGenSpec(
                docId = d.getString(K_DOC)!!,
                pageIds = d.getString(K_PAGES)?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
                formats = d.getString(K_FORMATS)!!.split(',').map { OutputFormat.valueOf(it) }.toSet(),
                mode = DocGenMode.valueOf(d.getString(K_MODE)!!),
                model = DocGenModel.valueOf(d.getString(K_MODEL)!!),
                filename = d.getString(K_FILENAME) ?: "Documento",
                title = d.getString(K_TITLE) ?: "Documento",
            )
        }.getOrNull()

        /** Mensaje en español para un fallo. */
        fun message(t: Throwable): String = when (t) {
            is CloudException.Auth -> "La clave de API no es válida o no tiene permiso. Revísala en Ajustes › IA en la nube."
            is CloudException.Billing -> "La cuenta del proveedor no tiene saldo."
            is CloudException.RateLimited -> "El servicio está saturado o has llegado al límite de peticiones. Prueba en unos minutos."
            is CloudException.Network -> "Sin conexión con el servicio de IA."
            is CloudException.Refused -> "El modelo se negó a procesar el documento" + (t.message?.substringAfter("(", "")?.removeSuffix(")")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + "."
            is CloudException -> t.message ?: "Error del servicio de IA"
            is DocGenException -> t.message ?: "No se pudo reconstruir"
            else -> "No se pudo reconstruir el documento (${t.javaClass.simpleName})"
        }
    }
}

/** Trabajo en primer plano que ejecuta una reconstrucción. */
class DocGenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val jobId: String get() = inputData.getString(DocGenJobs.K_JOB) ?: id.toString()

    override suspend fun doWork(): Result {
        val container = (applicationContext as ScannerApp).container
        val jobs = container.docGenJobs
        val spec = DocGenJobs.specFrom(inputData) ?: return Result.failure()
        if (runAttemptCount > 0) {
            // Reinicio tras matar el proceso: no se repite sola (cobraría otra vez).
            jobs.update(jobId, spec) { it.copy(status = DocGenJobState.Status.FAILED, error = "Se interrumpió (el sistema cerró la app). Vuelve a lanzarla.") }
            return Result.failure()
        }
        ensureChannel()
        runCatching { setForeground(info("Preparando…", 0f)) }.onFailure { Log.w(TAG, "setForeground", it) }
        jobs.update(jobId, spec) { it.copy(status = DocGenJobState.Status.RUNNING) }
        return try {
            val outcome = coroutineScope {
                val latest = MutableStateFlow<DocGenProgress?>(null)
                val notifier = launch {
                    latest.collect { p -> if (p != null) runCatching { setForeground(info(p.message, p.fraction)) }; delay(1000) }
                }
                try {
                    container.docGenRunner.run(spec, jobId) { p ->
                        latest.value = p
                        jobs.update(jobId, spec) { it.copy(progress = p, costUsd = p.costUsd) }
                    }
                } finally {
                    notifier.cancel()
                }
            }
            jobs.update(jobId, spec) {
                it.copy(
                    status = DocGenJobState.Status.DONE, files = outcome.files, costUsd = outcome.costUsd,
                    warnings = outcome.warnings, partial = outcome.partial,
                )
            }
            notifyDone(spec, outcome)
            Result.success()
        } catch (e: CancellationException) {
            jobs.update(jobId, spec) { it.copy(status = DocGenJobState.Status.CANCELLED) }
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "reconstrucción fallida", t)
            jobs.update(jobId, spec) {
                it.copy(status = DocGenJobState.Status.FAILED, error = DocGenJobs.message(t), accountError = t is CloudException && t.stopsQueue || t is DocGenException && (t.message?.contains("clave") == true))
            }
            notifyFailed(DocGenJobs.message(t))
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        return info("Preparando…", 0f)
    }

    private fun info(text: String, fraction: Float): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val pct = (fraction * 100).toInt().coerceIn(0, 99)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Reconstruyendo con IA…")
            .setContentText("$text · $pct %")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, pct, fraction <= 0f)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private fun notifyDone(spec: DocGenSpec, o: DocGenOutcome) {
        val first = o.files.firstOrNull() ?: return
        val open = runCatching {
            val uri = FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.fileprovider", File(first.path))
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, first.format.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(applicationContext, jobId.hashCode(), Intent.createChooser(view, "Abrir con").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }.getOrNull()
        val text = o.files.joinToString(" y ") { it.format.label } + " guardado en Descargas/EscanerProMax"
        post(
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("«${spec.title}» reconstruido")
                .setContentText(text)
                .setAutoCancel(true)
                .apply { if (open != null) setContentIntent(open) }
                .build(),
        )
    }

    private fun notifyFailed(msg: String) = post(
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("No se pudo reconstruir")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .build(),
    )

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(applicationContext).notify(DONE_ID_BASE + (jobId.hashCode() and 0xFFF), n) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Reconstrucción con IA", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso y resultado de los documentos Word/PDF reconstruidos con IA en la nube"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val TAG = "DocGenWorker"
        const val CHANNEL_ID = "docgen"
        private const val NOTIFICATION_ID = 0x4E21
        private const val DONE_ID_BASE = 0x5000
    }
}
