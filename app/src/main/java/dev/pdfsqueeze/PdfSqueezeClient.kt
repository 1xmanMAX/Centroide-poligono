package dev.pdfsqueeze

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Sobre JSON que devuelve la librería nativa. */
@Serializable
data class SqueezeEnvelope(
    val status: Int = STATUS_INTERNAL,
    val message: String = "",
    @SerialName("returned_original") val returnedOriginal: Boolean = false,
    val threads: Int = 0,
    val report: SqueezeReport? = null,
    val analysis: SqueezeAnalysis? = null,
) {
    val ok: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = 0
        const val STATUS_BAD_INPUT = 1
        const val STATUS_ENCRYPTED = 2
        const val STATUS_INTERNAL = 3
        const val STATUS_CANCELLED = 4
        const val STATUS_IO = 5
    }
}

/** Subconjunto del `Report` de pdfsqueeze-core que usa la app. */
@Serializable
data class SqueezeReport(
    @SerialName("input_bytes") val inputBytes: Long = 0,
    @SerialName("output_bytes") val outputBytes: Long = 0,
    val pages: Int = 0,
    val profile: String = "",
    @SerialName("elapsed_ms") val elapsedMs: Long = 0,
    val images: List<SqueezeImage> = emptyList(),
    val warnings: List<String> = emptyList(),
    @SerialName("returned_original") val returnedOriginal: Boolean = false,
    val verified: Boolean = false,
)

@Serializable
data class SqueezeImage(
    val width: Int = 0,
    val height: Int = 0,
    val kind: String = "",
    @SerialName("before_bytes") val beforeBytes: Long = 0,
    @SerialName("after_bytes") val afterBytes: Long = 0,
    @SerialName("after_filter") val afterFilter: String = "",
    val ssim: Double? = null,
    val action: String = "",
)

/** Subconjunto del `Analysis` de pdfsqueeze-core. */
@Serializable
data class SqueezeAnalysis(
    @SerialName("file_bytes") val fileBytes: Long = 0,
    val pages: Int = 0,
    val budget: Map<String, Long> = emptyMap(),
    val images: List<SqueezeImageInfo> = emptyList(),
    val encrypted: Boolean = false,
)

@Serializable
data class SqueezeImageInfo(
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    @SerialName("effective_dpi") val effectiveDpi: Float? = null,
    @SerialName("is_mask") val isMask: Boolean = false,
)

/**
 * Cliente con corrutinas de [PdfSqueeze]: progreso por sondeo (sin adjuntar hilos nativos a la JVM) y
 * cancelación al cancelar la corrutina (la llamada nativa termina pronto y el job se libera siempre).
 */
object PdfSqueezeClient {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    fun parse(text: String): SqueezeEnvelope = try {
        json.decodeFromString(SqueezeEnvelope.serializer(), text)
    } catch (t: Throwable) {
        SqueezeEnvelope(status = SqueezeEnvelope.STATUS_INTERNAL, message = "respuesta no válida del motor")
    }

    suspend fun analyze(input: File): SqueezeEnvelope = withContext(Dispatchers.IO) {
        parse(PdfSqueeze.analyzeFile(input.absolutePath))
    }

    /**
     * Comprime [input] → [output]. [onProgress] recibe 0..1 (cada ~150 ms). Lanza [CancellationException] si la
     * corrutina se cancela (tras esperar a que el motor se detenga, para no liberar el job en uso).
     */
    suspend fun compress(
        input: File,
        output: File,
        optionsJson: String,
        threads: Int,
        nice: Int = 10,
        onProgress: ((Float) -> Unit)? = null,
    ): SqueezeEnvelope = withContext(Dispatchers.IO) {
        val job = PdfSqueeze.jobNew()
        try {
            coroutineScope {
                val work = async(Dispatchers.IO) {
                    PdfSqueeze.compressFile(job, input.absolutePath, output.absolutePath, optionsJson, threads.coerceAtLeast(1), nice)
                }
                try {
                    while (withTimeoutOrNull(POLL_MS) { work.join() } == null) {
                        onProgress?.invoke(PdfSqueeze.jobProgress(job) / 100f)
                    }
                    onProgress?.invoke(PdfSqueeze.jobProgress(job) / 100f)
                    parse(work.await())
                } catch (c: CancellationException) {
                    PdfSqueeze.jobCancel(job)
                    withContext(NonCancellable) { work.join() }
                    throw c
                }
            }
        } finally {
            // work ya terminó (await/join): el job no está en uso.
            PdfSqueeze.jobFree(job)
        }
    }

    private const val POLL_MS = 150L
}
