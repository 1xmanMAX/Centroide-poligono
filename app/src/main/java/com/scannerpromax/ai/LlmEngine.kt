package com.scannerpromax.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.scannerpromax.imaging.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Motor de inferencia local (llama.cpp + mtmd) para modelos de visión-lenguaje GGUF.
 *
 * - Una sola sesión (modelo cargado) a la vez; [load], [generate], [unload] y [quantize] se serializan con un
 *   [Mutex]. El trabajo nativo corre en [Dispatchers.Default] (es CPU puro y bloqueante).
 * - Cancelar la corrutina de [generate] (o llamar a [cancel]) detiene la generación en el siguiente token o en el
 *   siguiente trozo del prompt.
 * - [isAvailable] es false si la biblioteca nativa no se pudo cargar en esta ABI (p. ej. un armeabi-v7a sin
 *   soporte): mostrar [unavailableReason] al usuario.
 */
class LlmEngine(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile private var handle: Long = 0L
    private var loadedConfig: LlmLoadConfig? = null

    private val _loadedModelId = MutableStateFlow<String?>(null)
    /** Id del modelo cargado ahora mismo (null = ninguno). */
    val loadedModelId: StateFlow<String?> = _loadedModelId.asStateFlow()

    val isAvailable: Boolean get() = LlmNative.available && backendInfo != null
    val unavailableReason: String? get() = LlmNative.unavailableReason

    /** Descripción de la CPU detectada por ggml (null si el nativo no está disponible). */
    val backendInfo: String? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!LlmNative.available) null
        else runCatching { LlmNative.nativeInit(appContext.applicationInfo.nativeLibraryDir) }
            .onFailure { Log.e(TAG, "nativeInit", it) }
            .getOrNull()
    }

    /** Carga (o reutiliza si ya está cargado con la misma configuración) un modelo. Lanza [LlmException]. */
    suspend fun load(config: LlmLoadConfig) = mutex.withLock {
        withContext(Dispatchers.Default) { loadLocked(config) }
    }

    private fun loadLocked(config: LlmLoadConfig) {
        if (!isAvailable) throw LlmException(unavailableReason ?: "Motor de IA no disponible en este dispositivo")
        if (handle != 0L && loadedConfig == config) return
        freeLocked()
        if (!File(config.modelPath).isFile) throw LlmException("Falta el archivo del modelo: ${config.modelPath}")
        config.mmprojPath?.let { if (!File(it).isFile) throw LlmException("Falta el proyector de visión: $it") }
        val threads = if (config.threads > 0) config.threads else CpuInfo.recommendedThreads()
        val h = try {
            LlmNative.nativeLoad(
                config.modelPath, config.mmprojPath, config.contextSize, config.batchSize,
                threads, if (config.batchThreads > 0) config.batchThreads else threads,
                config.useMmap, config.imageMinTokens, config.imageMaxTokens,
            )
        } catch (e: IllegalStateException) {
            throw LlmException(e.message ?: "No se pudo cargar el modelo", e)
        }
        if (h == 0L) throw LlmException("No se pudo cargar el modelo")
        handle = h
        loadedConfig = config
        _loadedModelId.value = config.modelId
        Log.i(TAG, "cargado ${config.modelId} hilos=$threads: ${LlmNative.nativeInfo(h)}")
    }

    /** Información del modelo cargado (arquitectura, parámetros, contexto...). */
    suspend fun modelInfo(): String? = mutex.withLock { handle.takeIf { it != 0L }?.let { LlmNative.nativeInfo(it) } }

    /**
     * Genera una respuesta. [onToken] recibe cada trozo de texto (en el hilo de inferencia: no tocar la UI
     * directamente; publicar en un StateFlow o similar). Lanza [LlmException] si no hay modelo o falla.
     */
    suspend fun generate(request: LlmRequest, onToken: (String) -> Unit = {}): LlmResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val h = handle
            if (h == 0L) throw LlmException("No hay ningún modelo cargado")
            val bitmaps = prepareImages(request)
            val job = currentCoroutineContext()[Job]
            // Vigilante: si se cancela la corrutina mientras el nativo está bloqueado (p. ej. codificando la imagen
            // o el prompt), pide al nativo que pare.
            val finished = AtomicBoolean(false)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) LlmNative.nativeCancel(h)
                }
            }
            val sb = StringBuilder()
            val t0 = System.nanoTime()
            try {
                val stats = try {
                    LlmNative.nativeGenerate(
                        h, request.prompt, request.system, bitmaps.toTypedArray().takeIf { it.isNotEmpty() },
                        request.useChatTemplate, request.enableThinking, request.maxTokens, request.temperature,
                        request.topK, request.topP, request.minP, request.repeatPenalty, request.repeatLastN,
                        request.seed, request.stop.toTypedArray().takeIf { it.isNotEmpty() },
                    ) { piece ->
                        sb.append(piece)
                        onToken(piece)
                        job?.isActive != false
                    }
                } catch (e: IllegalStateException) {
                    throw LlmException(e.message ?: "Error en la generación", e)
                }
                currentCoroutineContext().ensureActive()
                LlmResult(
                    text = sb.toString(),
                    promptTokens = stats[0].toInt(),
                    imageTokens = stats[1].toInt(),
                    generatedTokens = stats[2].toInt(),
                    imageMs = stats[3].toLong(),
                    promptMs = stats[4].toLong(),
                    generationMs = stats[5].toLong(),
                    totalMs = (System.nanoTime() - t0) / 1_000_000,
                    stopReason = StopReason.entries.getOrElse(stats[6].toInt()) { StopReason.EOS },
                )
            } finally {
                finished.set(true)
                watcher.cancel()
                bitmaps.forEachIndexed { i, b -> if (b !== request.images.getOrNull(i)) b.recycle() }
            }
        }
    }

    /** Igual que [generate] pero como Flow de eventos (trozos de texto y resultado final). */
    fun generateStream(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        val job = launch {
            try {
                val r = generate(request) { trySend(LlmEvent.Token(it)) }
                send(LlmEvent.Done(r))
            } catch (e: LlmException) {
                send(LlmEvent.Error(e.message ?: "Error"))
            }
            close()
        }
        awaitClose { job.cancel() }
    }

    /** Pide parar la generación en curso (seguro desde cualquier hilo). */
    fun cancel() {
        val h = handle
        if (h != 0L) LlmNative.nativeCancel(h)
    }

    /** Libera el modelo y su memoria. Espera a que termine la generación en curso (si se quiere cortar, [cancel]). */
    suspend fun unload() = mutex.withLock { withContext(Dispatchers.Default) { freeLocked() } }

    private fun freeLocked() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            LlmNative.nativeFree(h)
        }
        loadedConfig = null
        _loadedModelId.value = null
    }

    /**
     * Cuantiza un GGUF de texto (no usar con el mmproj). Descarga el modelo cargado antes (memoria).
     * Lanza [LlmException] si falla.
     */
    suspend fun quantize(input: File, output: File, type: QuantType, threads: Int = 0) = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!isAvailable) throw LlmException(unavailableReason ?: "Motor de IA no disponible en este dispositivo")
            freeLocked()
            val err = LlmNative.nativeQuantize(
                input.absolutePath, output.absolutePath, type.ftype,
                if (threads > 0) threads else CpuInfo.recommendedThreads(),
            )
            if (err != null) {
                output.delete()
                throw LlmException(err)
            }
        }
    }

    /** Prompt final tras la plantilla de chat del GGUF (depuración). */
    suspend fun formatPrompt(request: LlmRequest, nImages: Int = request.images.size + request.imagePaths.size): String =
        mutex.withLock {
            val h = handle
            if (h == 0L) throw LlmException("No hay ningún modelo cargado")
            LlmNative.nativeFormatPrompt(h, request.prompt, request.system, nImages, request.useChatTemplate, request.enableThinking)
        }

    private fun prepareImages(request: LlmRequest): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        for (b in request.images) out += fitBitmap(b, request.maxImageSide)
        for (f in request.imagePaths) {
            val decoded = try {
                BitmapIO.decodeThumbnail(f.absolutePath, request.maxImageSide)
            } catch (t: Throwable) {
                out.forEachIndexed { i, b -> if (b !== request.images.getOrNull(i)) b.recycle() }
                throw LlmException("No se pudo abrir la imagen ${f.name}", t)
            }
            val fitted = fitBitmap(decoded, request.maxImageSide)
            if (fitted !== decoded) decoded.recycle()
            out += fitted
        }
        return out
    }

    companion object {
        private const val TAG = "LlmEngine"

        /** Reduce el lado mayor a [maxSide] y garantiza ARGB_8888 (lo que exige el JNI). Puede devolver [src]. */
        fun fitBitmap(src: Bitmap, maxSide: Int): Bitmap {
            val long = max(src.width, src.height)
            var b = src
            if (maxSide in 1 until long) {
                val s = maxSide.toFloat() / long
                b = Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true)
            }
            if (b.config != Bitmap.Config.ARGB_8888) {
                val c = b.copy(Bitmap.Config.ARGB_8888, false)
                if (b !== src) b.recycle()
                b = c
            }
            return b
        }
    }
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class LlmLoadConfig(
    val modelId: String,
    val modelPath: String,
    val mmprojPath: String? = null,
    val contextSize: Int = 4096,
    val batchSize: Int = 512,
    /** 0 = automático (núcleos grandes). */
    val threads: Int = 0,
    val batchThreads: Int = 0,
    val useMmap: Boolean = true,
    val imageMinTokens: Int = 0,
    /** 0 = lo del GGUF. Limitarlo reduce memoria y tiempo con imágenes grandes (gama baja). */
    val imageMaxTokens: Int = 0,
)

data class LlmRequest(
    val prompt: String,
    val images: List<Bitmap> = emptyList(),
    val imagePaths: List<File> = emptyList(),
    val system: String? = null,
    val maxTokens: Int = 1024,
    /** <= 0 = determinista (codicioso). */
    val temperature: Float = 0f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.0f,
    val repeatLastN: Int = 64,
    val seed: Int = -1,
    val stop: List<String> = emptyList(),
    val useChatTemplate: Boolean = true,
    val enableThinking: Boolean = false,
    /** Lado mayor máximo de cada imagen (se reduce antes de enviarla). */
    val maxImageSide: Int = 1280,
)

enum class StopReason { EOS, MAX_TOKENS, STOP_STRING, CANCELLED, CONTEXT_FULL, CALLBACK }

data class LlmResult(
    val text: String,
    val promptTokens: Int,
    val imageTokens: Int,
    val generatedTokens: Int,
    val imageMs: Long,
    val promptMs: Long,
    val generationMs: Long,
    val totalMs: Long,
    val stopReason: StopReason,
) {
    val tokensPerSecond: Double get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0
    val promptTokensPerSecond: Double get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
}

sealed interface LlmEvent {
    data class Token(val text: String) : LlmEvent
    data class Done(val result: LlmResult) : LlmEvent
    data class Error(val message: String) : LlmEvent
}

/** Núcleos "grandes" según la frecuencia máxima de cada CPU (big.LITTLE). */
object CpuInfo {
    fun recommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until cores).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        return threadsFor(cores, freqs)
    }

    /** Lógica pura (testeable): núcleos con frecuencia >= 75 % de la máxima, entre 2 y 6. */
    fun threadsFor(cores: Int, maxFreqs: List<Long>): Int {
        if (cores <= 2) return cores.coerceAtLeast(1)
        if (maxFreqs.size < cores || maxFreqs.all { it <= 0 }) return (cores / 2).coerceIn(2, 4)
        val top = maxFreqs.max()
        val big = maxFreqs.count { it >= top * 0.75 }
        return big.coerceIn(2, minOf(6, cores))
    }
}
