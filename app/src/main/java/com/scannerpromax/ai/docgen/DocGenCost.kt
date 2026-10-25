package com.scannerpromax.ai.docgen

import com.scannerpromax.ai.cloud.CloudPricing
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * COSTE ESTIMADO antes de empezar (tokens estimados × [CloudPricing]). Lógica pura.
 *  - Imagen: Claude ≈ ancho×alto/750 tokens con la imagen ya reducida a ≤ 1568 px (tope ≈ 1,6k); DeepSeek ≤ 1024.
 *  - Texto del OCR que se manda de referencia: ≈ 3,5 caracteres por token (+ cajas).
 *  - Salida: el documento en JSON/llamadas ≈ 1,4× el texto + estructura; Sonnet añade pensamiento.
 *  - Calidad: cada paso del bucle relee el historial (desde la caché, al 10 % del precio en Claude).
 * Se da un rango [low, high] (high = 1,7× low): el coste real depende de lo denso que sea el documento.
 */
object DocGenCost {

    data class PageInfo(val width: Int, val height: Int, val ocrChars: Int, val ocrLines: Int)

    data class Estimate(val low: Double, val high: Double, val inputTokens: Long, val cachedTokens: Long, val outputTokens: Long)

    private const val AGENT_PREFIX = 3_800L // prompt de sistema + definiciones de herramientas
    private const val FAST_PREFIX = 1_600L

    fun imageTokens(model: DocGenModel, w: Int, h: Int, maxLongSide: Int = if (model == DocGenModel.DEEPSEEK_FLASH) 2048 else 1568): Long {
        if (w <= 0 || h <= 0) return 1_200
        var s = min(1.0, maxLongSide.toDouble() / max(w, h))
        val maxPx = if (model == DocGenModel.DEEPSEEK_FLASH) 1_690_000.0 else 1568.0 * 1568.0
        if (w * h * s * s > maxPx) s = sqrt(maxPx / (w.toDouble() * h))
        val px = w * h * s * s
        return if (model == DocGenModel.DEEPSEEK_FLASH) min(1_024L, (px / 1_600).roundToLong()) else min(1_600L, (px / 750).roundToLong())
    }

    private fun textTokens(p: PageInfo) = (p.ocrChars / 3.5).roundToLong() + p.ocrLines * 9L

    private fun outTokens(model: DocGenModel, mode: DocGenMode, p: PageInfo): Long {
        val base = 350L + (p.ocrChars / 3.5 * 1.4).roundToLong()
        val thinking = if (model == DocGenModel.CLAUDE_SONNET && mode == DocGenMode.QUALITY) 700L else 0L
        val toolOverhead = if (mode == DocGenMode.QUALITY) 250L else 0L
        return max(400L, base + thinking + toolOverhead)
    }

    fun estimate(model: DocGenModel, mode: DocGenMode, pages: List<PageInfo>, chunkPages: Int = 4, at: Instant = Instant.now()): Estimate {
        if (pages.isEmpty()) return Estimate(0.0, 0.0, 0, 0, 0)
        var input = 0L; var cached = 0L; var output = 0L
        when (mode) {
            DocGenMode.FAST -> for (p in pages) {
                input += FAST_PREFIX + imageTokens(model, p.width, p.height) + textTokens(p)
                output += outTokens(model, mode, p)
            }
            DocGenMode.QUALITY -> for (chunk in pages.chunked(chunkPages.coerceAtLeast(1))) {
                val prefix = AGENT_PREFIX + chunk.sumOf { imageTokens(model, it.width, it.height) + textTokens(it) + 40 }
                val out = chunk.sumOf { outTokens(model, mode, it) }
                val steps = 2 + 2 * chunk.size
                // Claude (cache_control) y DeepSeek (caché de prefijo automática) releen el historial desde la caché;
                // la escritura en caché de Claude cuesta 1,25× y lo que escribe el modelo se relee una vez como nuevo.
                input += (prefix * 1.25).roundToLong() + out
                cached += (steps - 1) * prefix + (steps - 1) * out / 2
                output += out
            }
        }
        val r = CloudPricing.ratesForModel(model.id, input + cached, at)
        val low = (input * r.inputPerM + cached * r.cachedInputPerM + output * r.outputPerM) / 1e6
        return Estimate(low, low * 1.7, input, cached, output)
    }

    /** "≈ 0,3 céntimos" / "≈ 2 céntimos" / "≈ $0,12" (en USD, como cobra el proveedor). */
    fun format(usd: Double): String = when {
        usd <= 0.0 -> "$0"
        usd < 0.01 -> "≈ " + String.format(java.util.Locale("es", "ES"), "%.1f", usd * 100) + " céntimos de $"
        usd < 1.0 -> "≈ " + String.format(java.util.Locale("es", "ES"), "%.0f", usd * 100) + " céntimos de $"
        else -> "≈ $" + String.format(java.util.Locale("es", "ES"), "%.2f", usd)
    }

    fun formatRange(e: Estimate): String = if (e.high < 0.01) format(e.high).replace("≈ ", "≈ hasta ") else
        format(e.low) + " – " + format(e.high).removePrefix("≈ ")
}
