package com.scannerpromax.ai.parts

import android.util.Log

/**
 * Arranque de ONNX Runtime (motor de Indra, del árbitro de encuadre y de la maquetación).
 *
 * libonnxruntime.so y su puente JNI (libonnxruntime4j_jni.so) van DENTRO del AAB/APK (dependencia
 * onnxruntime-android): Google Play entrega solo la ABI de cada teléfono. NUNCA se descarga ni se carga código nativo
 * desde fuera de la app (política de Google Play «Device and Network Abuse»: prohibido descargar código ejecutable).
 * Hasta v30 la .so se bajaba de Maven Central y se cargaba con System.load; eso se eliminó para publicar en Play.
 *
 * OnnxRuntime.init() (inicializador estático de OrtEnvironment) hace System.loadLibrary("onnxruntime4j_jni") y el
 * enlazador resuelve libonnxruntime.so desde las librerías de la propia app. Si falla (ABI rara, librería dañada),
 * se guarda [lastError] y el OCR / la maquetación quedan no disponibles sin cerrar la app.
 */
object OrtNative {
    private const val TAG = "OrtNative"

    @Volatile private var loaded = false
    @Volatile private var tried = false

    /** Último error de carga (en español), o null. Se muestra en Ajustes → Reconocimiento. */
    @Volatile var lastError: String? = null
        private set

    val isLoaded: Boolean get() = loaded

    /**
     * Comprueba que ORT arranca (OrtEnvironment) con las librerías incluidas en la app. true si se puede usar ORT.
     * Un fallo deja la clase OrtEnvironment rota en este proceso: no se reintenta hasta reiniciar la app.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (tried) return false
        tried = true
        return try {
            ai.onnxruntime.OrtEnvironment.getEnvironment()
            loaded = true
            lastError = null
            true
        } catch (t: Throwable) {  // UnsatisfiedLinkError, ExceptionInInitializerError, NoClassDefFoundError, IllegalStateException
            Log.w(TAG, "OrtEnvironment", t)
            lastError = "ONNX Runtime no arranca en este teléfono (${t.message ?: t.javaClass.simpleName}); " +
                "reconocimiento no disponible hasta reiniciar la app."
            false
        }
    }
}
