package com.scannerpromax.ai

import android.graphics.Bitmap
import android.util.Log

/**
 * Puente JNI con libllm_jni.so (app/src/main/cpp). Usar siempre a través de [LlmEngine].
 *
 * Si la biblioteca nativa no existe para la ABI del móvil (o falla al cargar), [available] es false y
 * [unavailableReason] explica por qué: la app muestra "no disponible en este dispositivo" en vez de cerrarse.
 */
internal object LlmNative {
    private const val TAG = "LlmNative"

    @Volatile var unavailableReason: String? = null
        private set

    val available: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            System.loadLibrary("llm_jni")
            true
        } catch (t: UnsatisfiedLinkError) {
            unavailableReason = "Motor de IA no disponible en este dispositivo (${t.message})"
            Log.w(TAG, unavailableReason!!)
            false
        } catch (t: Throwable) {
            unavailableReason = "Motor de IA no disponible en este dispositivo (${t.javaClass.simpleName})"
            Log.w(TAG, "loadLibrary", t)
            false
        }
    }

    fun interface TextCallback {
        /** Trozo de texto UTF-8 completo. Devolver false detiene la generación. */
        fun onText(text: String): Boolean
    }

    @JvmStatic external fun nativeInit(libDir: String): String

    @JvmStatic external fun nativeLoad(
        model: String,
        mmproj: String?,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        mmap: Boolean,
        imageMinTokens: Int,
        imageMaxTokens: Int,
    ): Long

    @JvmStatic external fun nativeInfo(handle: Long): String

    @JvmStatic external fun nativeHasVision(handle: Long): Boolean

    @JvmStatic external fun nativeFormatPrompt(
        handle: Long,
        prompt: String,
        system: String?,
        nImages: Int,
        useTemplate: Boolean,
        thinking: Boolean,
    ): String

    /** Devuelve [promptTokens, imageTokens, genTokens, imageMs, promptMs, genMs, stopReason]. */
    @JvmStatic external fun nativeGenerate(
        handle: Long,
        prompt: String,
        system: String?,
        images: Array<Bitmap>?,
        useTemplate: Boolean,
        thinking: Boolean,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        presencePenalty: Float,
        special: Boolean,
        seed: Int,
        stops: Array<String>?,
        callback: TextCallback?,
    ): DoubleArray

    @JvmStatic external fun nativeCancel(handle: Long)

    @JvmStatic external fun nativeFree(handle: Long)

    /** null = bien; si no, el mensaje de error. */
    @JvmStatic external fun nativeQuantize(input: String, output: String, ftype: Int, nThreads: Int): String?
}
