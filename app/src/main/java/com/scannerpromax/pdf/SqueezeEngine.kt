package com.scannerpromax.pdf

import android.app.ActivityManager
import android.content.Context
import com.scannerpromax.imaging.DeviceTier
import dev.pdfsqueeze.PdfSqueeze
import dev.pdfsqueeze.PdfSqueezeClient
import dev.pdfsqueeze.SqueezeAnalysis
import dev.pdfsqueeze.SqueezeEnvelope
import java.io.File

/** Motor pdfsqueeze visto por [PdfCompressor] y [PdfExporter] (interfaz para poder probar el respaldo en la JVM). */
interface SqueezeEngine {
    /** true si la librería nativa está y cargó. */
    val available: Boolean
    val tier: DeviceTier
    /** RAM disponible ahora mismo (bytes). */
    fun availableMemory(): Long
    suspend fun analyze(input: File): SqueezeEnvelope
    suspend fun compress(input: File, output: File, params: SqueezeParams, threads: Int, progress: ((Float) -> Unit)?): SqueezeEnvelope
}

/** Implementación real: JNI con libpdfsqueeze.so a prioridad de fondo (nice 10). */
class NativeSqueezeEngine(private val context: Context, override val tier: DeviceTier) : SqueezeEngine {
    override val available: Boolean get() = PdfSqueeze.isAvailable()

    override fun availableMemory(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        // Sin contar el margen que el sistema reserva antes de matar procesos.
        return (mi.availMem - mi.threshold).coerceAtLeast(0L)
    }

    override suspend fun analyze(input: File): SqueezeEnvelope = PdfSqueezeClient.analyze(input)

    override suspend fun compress(input: File, output: File, params: SqueezeParams, threads: Int, progress: ((Float) -> Unit)?): SqueezeEnvelope =
        PdfSqueezeClient.compress(input, output, params.toJson(), threads, nice = 10, onProgress = progress)
}

/** Lo que interesa del análisis para decidir hilos y punto de partida. */
internal data class SqueezeProbe(val maxImagePixels: Long, val imageBytes: Long, val pages: Int) {
    companion object {
        fun of(a: SqueezeAnalysis?): SqueezeProbe {
            if (a == null) return SqueezeProbe(DEFAULT_MAX_PIXELS, 0, 0)
            val maxPx = a.images.filter { !it.isMask }.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
            val imgBytes = a.budget.filterKeys { it.startsWith("image") }.values.sum()
            return SqueezeProbe(maxPx, imgBytes, a.pages)
        }

        /** Sin análisis se supone una foto de 12 MP. */
        const val DEFAULT_MAX_PIXELS = 12_000_000L
    }
}
