package com.scannerpromax.ai.parts

import android.util.Log
import java.io.File

/**
 * Carga de ONNX Runtime nativo descargado con la maquetación (libonnxruntime.so ya no va en el APK).
 *
 * ORT 1.31 (java/src/main/java/ai/onnxruntime/OnnxRuntime.java, init()/load()): en Android NO lee la propiedad
 * "onnxruntime.native.path" (solo en escritorio) y solo hace System.loadLibrary("onnxruntime4j_jni"); el puente JNI
 * (que sigue en el APK, ~36 KB) tiene DT_NEEDED libonnxruntime.so. Por eso, ANTES de tocar cualquier clase de
 * ai.onnxruntime (el inicializador estático de OrtEnvironment llama a OnnxRuntime.init() y, si falla, la clase queda
 * inservible hasta reiniciar la app), se precarga libonnxruntime.so con System.load(ruta absoluta): el enlazador
 * de Android (API ≥ 23) resuelve después la dependencia del puente por su soname ("libonnxruntime.so") con la
 * librería ya cargada. Orden: 1) libonnxruntime.so (System.load), 2) libonnxruntime4j_jni.so (lo hace ORT).
 *
 * Si algo falla (UnsatisfiedLinkError, archivo dañado, ABI equivocada) se guarda [lastError] y la maquetación
 * queda no disponible: la tubería sigue con la maquetación clásica, como sin el modelo.
 */
object OrtNative {
    private const val TAG = "OrtNative"
    const val LIB_FILE = "libonnxruntime.so"

    @Volatile private var loadedFrom: String? = null

    /** Último error de carga (en español), o null. Se muestra en Ajustes → Reconocimiento. */
    @Volatile var lastError: String? = null
        private set

    /** Tras volver a descargar las librerías: permite reintentar. */
    fun resetError() { if (loadedFrom == null) lastError = null }

    val isLoaded: Boolean get() = loadedFrom != null

    /**
     * Precarga libonnxruntime.so de [libDir] y comprueba que ORT arranca (OrtEnvironment). true si se puede usar ORT.
     * Un fallo de ORT tras precargar bien (puente JNI) deja la clase OrtEnvironment rota en este proceso: no se
     * reintenta hasta reiniciar la app.
     */
    @Synchronized
    fun ensureLoaded(libDir: File): Boolean {
        if (loadedFrom != null) return true
        if (lastError != null && ortTouched) return false
        val lib = File(libDir, LIB_FILE)
        if (!lib.isFile) {
            lastError = "Falta el motor ONNX Runtime ($LIB_FILE): vuelve a descargar «Maquetación de páginas»"
            return false
        }
        try {
            System.load(lib.absolutePath)
        } catch (t: Throwable) {  // UnsatisfiedLinkError, SecurityException
            Log.w(TAG, "System.load ${lib.absolutePath}", t)
            lastError = "ONNX Runtime no carga en este teléfono (${t.message ?: t.javaClass.simpleName}); " +
                "maquetación de páginas no disponible. Prueba a borrar y volver a descargar el modelo."
            return false
        }
        ortTouched = true
        return try {
            // Fuerza OnnxRuntime.init(): System.loadLibrary("onnxruntime4j_jni") + comprobación de versión de la API.
            ai.onnxruntime.OrtEnvironment.getEnvironment()
            loadedFrom = lib.absolutePath
            lastError = null
            Log.i(TAG, "ONNX Runtime cargado desde ${lib.absolutePath}")
            true
        } catch (t: Throwable) {  // UnsatisfiedLinkError, ExceptionInInitializerError, NoClassDefFoundError, IllegalStateException
            Log.w(TAG, "OrtEnvironment", t)
            lastError = "ONNX Runtime no arranca (${t.message ?: t.javaClass.simpleName}); maquetación de páginas no " +
                "disponible hasta reiniciar la app."
            false
        }
    }

    @Volatile private var ortTouched = false
}
