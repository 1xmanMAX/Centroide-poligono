package com.scannerpromax.ai.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CloudPricingPromptTest {

    @Test fun deepSeekPeakHours() {
        // 2026-10-12 es lunes.
        assertTrue(CloudPricing.isDeepSeekPeak(Instant.parse("2026-10-12T01:00:00Z")))
        assertTrue(CloudPricing.isDeepSeekPeak(Instant.parse("2026-10-12T03:59:59Z")))
        assertFalse(CloudPricing.isDeepSeekPeak(Instant.parse("2026-10-12T04:00:00Z")))
        assertFalse(CloudPricing.isDeepSeekPeak(Instant.parse("2026-10-12T05:30:00Z")))
        assertTrue(CloudPricing.isDeepSeekPeak(Instant.parse("2026-10-16T09:59:00Z"))) // viernes
        assertFalse(CloudPricing.isDeepSeekPeak(Instant.parse("2026-10-16T10:00:00Z")))
        assertFalse(CloudPricing.isDeepSeekPeak(Instant.parse("2026-10-17T02:00:00Z"))) // sábado
        assertFalse(CloudPricing.isDeepSeekPeak(Instant.parse("2026-10-18T07:00:00Z"))) // domingo
    }

    @Test fun costs() {
        val off = Instant.parse("2026-10-12T12:00:00Z")
        val peak = Instant.parse("2026-10-12T02:00:00Z")
        val u = CloudUsageTokens(inputTokens = 100_000, outputTokens = 1_000_000)
        assertEquals(0.01 + 0.50, CloudPricing.cost(CloudProviderId.CLAUDE_HAIKU, u, off), 1e-9)
        assertEquals(0.015 + 0.60, CloudPricing.cost(CloudProviderId.DEEPSEEK, u, off), 1e-9)
        assertEquals(0.03 + 1.20, CloudPricing.cost(CloudProviderId.DEEPSEEK, u, peak), 1e-9)
        // Página típica: entrada 2 000 (500 en caché DeepSeek) + salida 1 500.
        val page = CloudUsageTokens(inputTokens = 1_500, outputTokens = 1_500, cachedInputTokens = 500)
        assertEquals((1_500 * 0.15 + 500 * 0.003 + 1_500 * 0.60) / 1e6, CloudPricing.cost(CloudProviderId.DEEPSEEK, page, off), 1e-12)
        // Haiku: prompt de más de 100k tokens -> tarifa larga.
        val long = CloudUsageTokens(inputTokens = 150_000, outputTokens = 1_000)
        assertEquals((150_000 * 0.50 + 1_000 * 2.50) / 1e6, CloudPricing.cost(CloudProviderId.CLAUDE_HAIKU, long, off), 1e-12)
        assertTrue(CloudPricing.typicalPageCost(CloudProviderId.CLAUDE_HAIKU) in 0.0005..0.002)
        assertTrue(CloudPricing.typicalPageCost(CloudProviderId.DEEPSEEK, peak = true) > CloudPricing.typicalPageCost(CloudProviderId.DEEPSEEK))
    }

    @Test fun usageAccumulates() {
        val at = Instant.parse("2026-10-12T12:00:00Z")
        val u = CloudUsage().plus(CloudProviderId.CLAUDE_HAIKU, CloudUsageTokens(3000, 1500), at, page = true)
            .plus(CloudProviderId.CLAUDE_HAIKU, CloudUsageTokens(3000, 1500), at, page = false)
        assertEquals(1L, u.pages); assertEquals(2L, u.requests); assertEquals(6000L, u.inputTokens)
        assertEquals(2 * (3000 * 0.10 + 1500 * 0.50) / 1e6, u.costUsd, 1e-12)
    }

    /** Esquema estricto válido para structured outputs: objetos cerrados con todos sus campos requeridos. */
    @Test fun schemaIsStrict() {
        val schema = Json.parseToJsonElement(CloudPrompt.SCHEMA_JSON) as JsonObject
        var objects = 0
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    if ((e["type"] as? JsonPrimitive)?.content == "object") {
                        objects++
                        assertEquals("additionalProperties", "false", e["additionalProperties"].toString())
                        val props = (e["properties"] as JsonObject).keys
                        val req = (e["required"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()
                        assertEquals(props, req)
                    }
                    e.values.forEach { walk(it) }
                }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(schema)
        assertEquals(7, objects)
        assertTrue(CloudPrompt.SYSTEM.contains("EXACTLY"))
        assertTrue("DeepSeek JSON Output exige la palabra json", CloudPrompt.SCHEMA_REMINDER.contains("json"))
    }

    @Test fun retryAfterAndErrorClassification() {
        assertEquals(3000L, CloudException.parseRetryAfter("3"))
        assertEquals(120_000L, CloudException.parseRetryAfter("9999"))
        assertEquals(null, CloudException.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertTrue(CloudException.fromStatus(401, null, null) is CloudException.Auth)
        assertTrue(CloudException.fromStatus(402, null, null).stopsQueue)
        assertTrue(CloudException.fromStatus(529, null, null).retryable)
        assertTrue(CloudException.fromStatus(503, null, null) is CloudException.RateLimited)
        assertTrue(CloudException.fromStatus(500, null, null) is CloudException.Server)
        assertTrue(CloudException.fromStatus(422, null, null) is CloudException.BadRequest)
        assertEquals("clave sk-… mal", CloudException.safeDetail("clave sk-abcdef123456789 mal"))
    }

    @Test fun targetSizes() {
        assertEquals(1568 to 1109, CloudPipeline.targetSize(3000, 2122, 1568, 1568 * 1568))
        val (w, h) = CloudPipeline.targetSize(2480, 3508, 2048, 1_690_000)
        assertTrue(w * h <= 1_700_000 && h <= 2048)
        assertEquals(800 to 600, CloudPipeline.targetSize(800, 600, 1568, 1568 * 1568))
    }
}
