package com.scannerpromax.pdf

import com.scannerpromax.domain.CompressionLevel
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.imaging.DeviceTier
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Parámetros de una pasada de pdfsqueeze. [profile]: "lossless" | "balanced" | "small" | "extreme".
 * [maxDpi] null = conservar la resolución. [allowLossy] false = solo transformaciones sin pérdida (B/N → JBIG2/G4
 * exacto, recompresión de flujos, limpieza de estructura).
 */
data class SqueezeParams(
    val profile: String,
    val minSsim: Double,
    val maxDpi: Float?,
    val allowLossy: Boolean = true,
) {
    /** Opciones para la librería nativa: el preset + nuestros ajustes. Zopfli desactivado (lento y caro en batería). */
    fun toJson(): String = buildString {
        append("{\"profile\":\"").append(profile).append('"')
        append(",\"allow_lossy\":").append(allowLossy)
        append(",\"min_ssim\":").append(String.format(java.util.Locale.ROOT, "%.3f", minSsim))
        append(",\"max_dpi\":").append(maxDpi?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "null")
        append(",\"zopfli_iterations\":0")
        append(",\"no_regression\":true,\"verify\":true,\"threads\":0}")
    }
}

/** Cómo traducir lo que pide la app (nivel, tamaño objetivo, exportación) a pasadas de pdfsqueeze. */
object SqueezePlan {

    fun forLevel(level: CompressionLevel): SqueezeParams = when (level) {
        CompressionLevel.LIGHT -> SqueezeParams("balanced", 0.97, 200f)
        CompressionLevel.RECOMMENDED -> SqueezeParams("small", 0.94, 150f)
        CompressionLevel.STRONG -> SqueezeParams("small", 0.91, 120f)
        CompressionLevel.EXTREME -> SqueezeParams("extreme", 0.86, 96f)
    }

    /**
     * «Optimizar tamaño» al exportar: conserva la resolución elegida (sin max_dpi), SSIM ≥ 0,97 y las páginas B/N a
     * 1 bit sin pérdida (JBIG2/G4). Con «Máxima (HD)» solo sin pérdida: nada de recodificar con pérdida.
     */
    fun forExport(quality: ExportQuality): SqueezeParams =
        if (quality == ExportQuality.MAX) SqueezeParams("lossless", 1.0, null, allowLossy = false)
        else SqueezeParams("balanced", 0.97, null)

    // ------------------------------------------------------------------------------------ tamaño objetivo

    /** Punto de la búsqueda: s ∈ [0,1] (0 = lo más pequeño, 1 = máxima calidad) → perfil, SSIM y dpi. */
    fun forQuality(s: Float): SqueezeParams {
        val q = s.coerceIn(0f, 1f)
        val ssim = MIN_SSIM + q * (MAX_SSIM - MIN_SSIM)
        val dpi = (MIN_DPI + q * (MAX_DPI - MIN_DPI)).roundToInt().toFloat()
        val profile = when {
            q < 0.25f -> "extreme"
            q < 0.75f -> "small"
            else -> "balanced"
        }
        return SqueezeParams(profile, (ssim * 1000).roundToInt() / 1000.0, dpi)
    }

    /**
     * Punto de partida de la búsqueda a partir del análisis: los bytes que no son imagen apenas bajan (≈ 10 % por
     * la limpieza sin pérdida), así que la fracción que deben ocupar las imágenes decide el punto. Las imágenes
     * escalan aprox. con dpi², de ahí la raíz.
     */
    fun initialQuality(originalBytes: Long, imageBytes: Long, targetBytes: Long): Float {
        if (originalBytes <= 0) return 0.5f
        val img = imageBytes.coerceIn(0, originalBytes)
        val other = (originalBytes - img) * 0.9
        if (img <= 0) return 1f // sin imágenes: solo hay pasada sin pérdida; la calidad no cambia el tamaño
        val needed = ((targetBytes - other) / img).coerceIn(0.0, 1.0)
        return (sqrt(needed).toFloat() * 0.95f).coerceIn(0f, 1f)
    }

    /** Búsqueda binaria sobre s: estado + siguiente punto. Como mucho [MAX_PASSES] pasadas. */
    class TargetSearch(private val targetBytes: Long, initial: Float) {
        private var lo = 0f
        private var hi = 1f
        var passes = 0
            private set
        var next: Float? = initial.coerceIn(0f, 1f)
            private set
        /** Mejor s que cumple el objetivo (el de más calidad), o null. */
        var bestFit: Float? = null
            private set
        var bestFitSize: Long = -1
            private set

        /** Registra el tamaño obtenido con [s] y calcula el siguiente punto (null = terminar). */
        fun record(s: Float, size: Long) {
            passes++
            if (size in 1..targetBytes) {
                if (bestFit == null || s > bestFit!!) { bestFit = s; bestFitSize = size }
                lo = maxOf(lo, s)
            } else {
                hi = minOf(hi, s)
            }
            next = when {
                passes >= MAX_PASSES -> null
                // Suficientemente cerca (90–100 % del objetivo): no compensa otra pasada.
                size in 1..targetBytes && size >= targetBytes * 0.9 -> null
                // Lo más pequeño posible y aún no cabe: no hay nada mejor que probar.
                s <= 0f && size > targetBytes -> null
                // Penúltima/última oportunidad sin nada que cumpla: ir directo al mínimo.
                bestFit == null && passes == MAX_PASSES - 1 -> 0f
                hi - lo < 0.04f -> null
                else -> (lo + hi) / 2f
            }?.takeIf { cand -> cand != s }
        }
    }

    // ------------------------------------------------------------------------------------ hilos y memoria

    /** Hilos según la gama: baja 1, media 2, alta la mitad de los núcleos (máx. 4). */
    fun threadsFor(tier: DeviceTier): Int = when {
        tier.isLowRam -> 1
        tier.cores >= 6 && tier.maxWorkingPixels >= 16_000_000 -> (tier.cores / 2).coerceIn(2, 4)
        else -> 2
    }

    /** Pico estimado de memoria nativa para [threads] hilos con la imagen más grande de [maxImagePixels]. */
    fun estimatedPeakBytes(maxImagePixels: Long, threads: Int, fileBytes: Long): Long =
        maxImagePixels * BYTES_PER_PIXEL * threads + fileBytes * 3

    /**
     * Reduce los hilos hasta que el pico estimado quepa en la mitad de la RAM disponible.
     * Devuelve 0 si ni con un hilo cabe (entonces se usa el compresor de respaldo).
     */
    fun fitThreads(wanted: Int, maxImagePixels: Long, fileBytes: Long, availableBytes: Long): Int {
        val budget = availableBytes / 2
        var t = wanted.coerceAtLeast(1)
        while (t >= 1) {
            if (estimatedPeakBytes(maxImagePixels, t, fileBytes) <= budget) return t
            t--
        }
        return 0
    }

    const val MAX_PASSES = 5
    /** Bytes por píxel y por hilo (decodificado + candidatos + SSIM): medido ~24–30. */
    const val BYTES_PER_PIXEL = 28L
    private const val MIN_SSIM = 0.84f
    private const val MAX_SSIM = 0.97f
    private const val MIN_DPI = 72f
    private const val MAX_DPI = 200f
}
