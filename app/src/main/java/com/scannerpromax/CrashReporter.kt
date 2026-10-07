package com.scannerpromax

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de cierres inesperados: guarda la traza del error en disco y, en el siguiente arranque, la app la
 * muestra para que el usuario pueda copiarla y enviarla. En Android 11+ también recupera los cierres nativos
 * (OpenCV/ML Kit) y por falta de memoria, que no pasan por el manejador de Java.
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_LAST_EXIT = "last_exit_ts"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "Hilo: ${thread.name}\n\n" + stackTrace(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Devuelve el informe pendiente (o null). Incluye cierres nativos/OOM registrados por el sistema. */
    fun pendingReport(context: Context): String? {
        val f = file(context)
        val javaReport = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
        return javaReport ?: systemExitReport(context)
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun systemExitReport(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_LAST_EXIT, 0L)
            prefs.edit().putLong(KEY_LAST_EXIT, info.timestamp).apply()
            if (info.timestamp <= seen) return null
            val reason = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Cierre nativo (librería C++)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Sin memoria (sistema)"
                ApplicationExitInfo.REASON_ANR -> "La app dejó de responder (ANR)"
                ApplicationExitInfo.REASON_CRASH -> "Error de Java/Kotlin"
                else -> return null
            }
            header(context) + "Motivo: $reason\nDescripción: ${info.description}\nPSS: ${info.pss / 1024} MB\n" +
                "Fecha: ${SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date(info.timestamp))}\n"
        }.getOrNull()
    }

    private fun write(context: Context, body: String) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(header(context) + body)
    }

    private fun header(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return "ESCÁNER PRO MAX $version\n" +
            "Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n" +
            "ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "Fecha: ${SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date())}\n\n"
    }

    private fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)
}
