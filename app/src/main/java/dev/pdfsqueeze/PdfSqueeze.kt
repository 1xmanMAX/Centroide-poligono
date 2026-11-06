package dev.pdfsqueeze

/**
 * Enlace JNI con libpdfsqueeze.so (puente propio native/pdfsqueeze-ffi sobre pdfsqueeze-core, MIT).
 *
 * La librería se carga de forma PEREZOSA y tolerante: si no está en el APK (compilación sin Rust/NDK) o no carga
 * en este equipo, [isAvailable] devuelve false y la app usa el compresor de respaldo. No llames a las funciones
 * `external` sin comprobar [isAvailable] antes.
 *
 * API por rutas (la entrada se mapea en memoria y la salida se escribe directamente a archivo; nada de `byte[]`
 * por el heap de Java) y con "jobs": cada llamada tiene su propio progreso y cancelación.
 * Las funciones devuelven un sobre JSON `{"status":N,"message":...}` (ver [PdfSqueezeClient]).
 */
object PdfSqueeze {
    @Volatile private var loaded: Boolean? = null
    @Volatile var loadError: String? = null
        private set

    /** true si libpdfsqueeze.so está y cargó. Seguro de llamar desde cualquier hilo (se intenta una sola vez). */
    fun isAvailable(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = try {
                System.loadLibrary("pdfsqueeze")
                true
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message ?: "UnsatisfiedLinkError"
                false
            } catch (e: SecurityException) {
                loadError = e.message ?: "SecurityException"
                false
            }
            loaded = ok
            return ok
        }
    }

    /** Crea el estado (progreso + cancelación) de una compresión. Liberar con [jobFree]. */
    @JvmStatic external fun jobNew(): Long

    /** Libera un job. Solo cuando ya no hay ninguna llamada en curso con él. */
    @JvmStatic external fun jobFree(job: Long)

    /** Progreso 0..100 del job. */
    @JvmStatic external fun jobProgress(job: Long): Int

    /** Pide cancelar la compresión en curso del job (la llamada termina pronto con status 4). */
    @JvmStatic external fun jobCancel(job: Long)

    /**
     * Comprime [input] → [output] (bloqueante). [optionsJson]: `{"profile":"small",...}` sobre el preset.
     * [threads] hilos propios para esta llamada, con prioridad de fondo [nice] (0..19).
     * Si nada mejora el original no se escribe [output] y el sobre trae `"returned_original":true`.
     */
    @JvmStatic external fun compressFile(job: Long, input: String, output: String, optionsJson: String, threads: Int, nice: Int): String

    /** Análisis sin comprimir: `{"status":0,"analysis":{...}}`. */
    @JvmStatic external fun analyzeFile(input: String): String

    /** Preset en JSON ("lossless" | "balanced" | "small" | "extreme"). */
    @JvmStatic external fun optionsJson(profile: String): String

    @JvmStatic external fun version(): String
}
