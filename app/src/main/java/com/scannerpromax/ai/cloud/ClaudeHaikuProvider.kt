package com.scannerpromax.ai.cloud

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.ErrorType
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigDisabled
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.toByteString
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Claude Haiku 5.5 con el SDK OFICIAL de Anthropic para Java (`com.anthropic:anthropic-java`), cliente asíncrono
 * OkHttp (cancelable desde la corrutina).
 *  - Imagen JPEG en base64 (lado largo <= 1568 px) + instrucción; salida con structured outputs ESTRICTOS
 *    (`output_config.format` = JSON Schema de [CloudPrompt.SCHEMA_JSON]).
 *  - Pensamiento DESACTIVADO con effort `low` (documentado para Haiku 5.5: `disabled` solo vale con effort <= high):
 *    transcribir no requiere razonar y así no se pagan tokens de pensamiento. Sin temperature/top_p (400 en Haiku 5.5).
 *  - Sin fallback de rechazo en servidor para Haiku 5.5: se comprueba `stop_reason == refusal` (y `max_tokens`) antes
 *    de leer el contenido.
 *  - Reintentos del SDK a 0: los hace [CloudRecognizer] (espera creciente, "esperando red", parar con error de clave).
 */
class ClaudeHaikuProvider(
    apiKey: String,
    baseUrl: String? = null,
) : CloudProvider {

    override val id = CloudProviderId.CLAUDE_HAIKU
    override val maxLongSide = 1568
    override val maxPixels = 1568 * 1568

    private val client: AnthropicClientAsync = AnthropicOkHttpClientAsync.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .maxRetries(0)
        .timeout(Duration.ofSeconds(180))
        .build()

    override suspend fun recognize(request: CloudRequest): CloudResponse {
        val params = buildParams(request.jpeg)
        val message = call { client.messages().create(params).await() }
        return toResponse(message)
    }

    override suspend fun testKey() {
        // Gratis: consulta del modelo (comprueba la clave y que el modelo está disponible para la cuenta).
        call { client.models().retrieve(CloudProviderId.CLAUDE_HAIKU.model).await() }
    }

    fun close() = runCatching { client.close() }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        throw map(unwrap(e))
    }

    companion object {
        const val MAX_TOKENS = 16_000L

        fun buildParams(jpeg: ByteArray): MessageCreateParams = MessageCreateParams.builder()
            .model(CloudProviderId.CLAUDE_HAIKU.model)
            .maxTokens(MAX_TOKENS)
            .system(CloudPrompt.SYSTEM)
            .thinking(ThinkingConfigDisabled.builder().build())
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema()).build())
                    .build(),
            )
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(jpeg.toByteString().base64())
                                    .build(),
                            )
                            .build(),
                    ),
                    ContentBlockParam.ofText(CloudPrompt.USER),
                ),
            )
            .build()

        private fun schema(): JsonOutputFormat.Schema {
            val obj = Json.parseToJsonElement(CloudPrompt.SCHEMA_JSON) as JsonObject
            val b = JsonOutputFormat.Schema.builder()
            obj.forEach { (k, v) -> b.putAdditionalProperty(k, JsonValue.from(plain(v))) }
            return b.build()
        }

        /** kotlinx JsonElement -> Map/List/String/Number/Boolean/null (lo que acepta JsonValue.from). */
        internal fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValues { plain(it.value) }
            is JsonArray -> e.map { plain(it) }
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null -> e.longOrNull
                else -> e.content.toDoubleOrNull()
            }
        }

        fun toResponse(m: Message): CloudResponse {
            val stop = m.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) {
                val cat = m.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse(null)
                throw CloudException.Refused(cat)
            }
            val text = m.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
            val u = m.usage()
            val tokens = CloudUsageTokens(
                inputTokens = u.inputTokens() + (u.cacheCreationInputTokens().orElse(0L)),
                outputTokens = u.outputTokens(),
                cachedInputTokens = u.cacheReadInputTokens().orElse(0L),
            )
            if (text.isBlank()) throw CloudException.BadResponse(if (stop == StopReason.MAX_TOKENS) "respuesta vacía (límite de tokens)" else "respuesta vacía")
            return CloudResponse(text, tokens, truncated = stop == StopReason.MAX_TOKENS)
        }

        private fun unwrap(e: Throwable): Throwable {
            var t = e
            while ((t is CompletionException || t is ExecutionException) && t.cause != null) t = t.cause!!
            return t
        }

        /** Excepciones tipadas del SDK -> [CloudException] (de la más concreta a la más general). */
        internal fun map(e: Throwable): Throwable = when (e) {
            is kotlinx.coroutines.CancellationException -> e
            is CloudException -> e
            is UnauthorizedException -> CloudException.Auth(401, CloudException.safeDetail(e.message))
            is PermissionDeniedException -> CloudException.Auth(403, CloudException.safeDetail(e.message))
            is RateLimitException -> CloudException.RateLimited(retryAfter(e), 429)
            is InternalServerException -> if (e.statusCode() == 529) CloudException.RateLimited(retryAfter(e), 529) else CloudException.Server(e.statusCode())
            is AnthropicServiceException -> when {
                e.errorType().orElse(null) == ErrorType.BILLING_ERROR -> CloudException.Billing(CloudException.safeDetail(e.message))
                else -> CloudException.fromStatus(e.statusCode(), retryAfter(e), CloudException.safeDetail(e.message))
            }
            is AnthropicIoException -> CloudException.Network(e)
            is java.io.IOException -> CloudException.Network(e)
            else -> CloudException.BadResponse(e.javaClass.simpleName + (e.message?.let { ": " + CloudException.safeDetail(it) } ?: ""))
        }

        private fun retryAfter(e: AnthropicServiceException): Long? =
            CloudException.parseRetryAfter(runCatching { e.headers().values("retry-after").firstOrNull() }.getOrNull())
    }
}
