package com.scannerpromax.ai.cloud

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * TABLA DE PRECIOS (único sitio que hay que tocar si cambian). USD por millón de tokens, verificados en las páginas
 * oficiales el 2026-10-15:
 *  - Claude Haiku 5.5: $0,10 entrada / $0,50 salida con prompts de hasta 100k tokens (más largos: $0,50 / $2,50).
 *  - DeepSeek deepseek-flash (V4.1-Flash): fuera de punta $0,15 entrada (fallo de caché) / $0,003 (acierto) /
 *    $0,60 salida; EN PUNTA el doble ($0,30 / $0,006 / $1,20). Punta: 01:00–04:00 y 06:00–10:00 UTC de lunes a
 *    viernes, salvo festivos chinos (no se tienen en cuenta: la estimación puede salir algo alta esos días).
 *  - Claude Sonnet 5.5 (reconstrucción de documentos en ALTA CALIDAD): $2 entrada / $10 salida, lectura de caché $0,20.
 */
object CloudPricing {

    data class Rates(val inputPerM: Double, val outputPerM: Double, val cachedInputPerM: Double = inputPerM)

    val HAIKU = Rates(inputPerM = 0.10, outputPerM = 0.50, cachedInputPerM = 0.01)
    val HAIKU_LONG = Rates(inputPerM = 0.50, outputPerM = 2.50, cachedInputPerM = 0.05)
    const val HAIKU_LONG_PROMPT_TOKENS = 100_000L

    val SONNET = Rates(inputPerM = 2.0, outputPerM = 10.0, cachedInputPerM = 0.20)

    val DEEPSEEK_OFF_PEAK = Rates(inputPerM = 0.15, outputPerM = 0.60, cachedInputPerM = 0.003)
    val DEEPSEEK_PEAK = Rates(inputPerM = 0.30, outputPerM = 1.20, cachedInputPerM = 0.006)

    /** Franjas de punta de DeepSeek (horas UTC [desde, hasta)), lunes a viernes. */
    private val DEEPSEEK_PEAK_HOURS = listOf(1 until 4, 6 until 10)

    fun isDeepSeekPeak(at: Instant): Boolean {
        val t = at.atOffset(ZoneOffset.UTC)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        return DEEPSEEK_PEAK_HOURS.any { t.hour in it }
    }

    fun rates(provider: CloudProviderId, promptTokens: Long, at: Instant): Rates = when (provider) {
        CloudProviderId.CLAUDE_HAIKU -> if (promptTokens > HAIKU_LONG_PROMPT_TOKENS) HAIKU_LONG else HAIKU
        CloudProviderId.DEEPSEEK -> if (isDeepSeekPeak(at)) DEEPSEEK_PEAK else DEEPSEEK_OFF_PEAK
    }

    /**
     * Tarifas por MODELO (la clave de Claude sirve para Haiku y Sonnet): "claude-sonnet-…" -> [SONNET],
     * "claude-haiku-…" -> Haiku, "deepseek-…" -> DeepSeek (punta / fuera de punta).
     */
    fun ratesForModel(model: String, promptTokens: Long, at: Instant): Rates = when {
        model.startsWith("claude-sonnet") -> SONNET
        model.startsWith("claude") -> if (promptTokens > HAIKU_LONG_PROMPT_TOKENS) HAIKU_LONG else HAIKU
        else -> if (isDeepSeekPeak(at)) DEEPSEEK_PEAK else DEEPSEEK_OFF_PEAK
    }

    /** Coste en USD de una respuesta de [model]. */
    fun costForModel(model: String, usage: CloudUsageTokens, at: Instant): Double {
        val r = ratesForModel(model, usage.inputTokens + usage.cachedInputTokens, at)
        return (usage.inputTokens * r.inputPerM + usage.cachedInputTokens * r.cachedInputPerM + usage.outputTokens * r.outputPerM) / 1e6
    }

    /** Coste en USD de una respuesta con su [usage] (tokens que devuelve la API). */
    fun cost(provider: CloudProviderId, usage: CloudUsageTokens, at: Instant): Double {
        val r = rates(provider, usage.inputTokens + usage.cachedInputTokens, at)
        return (usage.inputTokens * r.inputPerM + usage.cachedInputTokens * r.cachedInputPerM + usage.outputTokens * r.outputPerM) / 1e6
    }

    /** Coste típico por página para mostrar en Ajustes (≈2,3k tokens de imagen + prompt Claude / ≈1,6k DeepSeek, ≈1,5k de salida). */
    fun typicalPageCost(provider: CloudProviderId, peak: Boolean = false): Double = when (provider) {
        CloudProviderId.CLAUDE_HAIKU -> (3_200 * HAIKU.inputPerM + 1_500 * HAIKU.outputPerM) / 1e6
        CloudProviderId.DEEPSEEK -> (if (peak) DEEPSEEK_PEAK else DEEPSEEK_OFF_PEAK).let { (2_600 * it.inputPerM + 1_500 * it.outputPerM) / 1e6 }
    }
}

/** Tokens facturables de una respuesta. [inputTokens] = entrada SIN contar la leída de caché ([cachedInputTokens]). */
data class CloudUsageTokens(val inputTokens: Long, val outputTokens: Long, val cachedInputTokens: Long = 0)
