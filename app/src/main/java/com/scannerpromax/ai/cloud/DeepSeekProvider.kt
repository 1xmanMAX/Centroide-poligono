package com.scannerpromax.ai.cloud

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek `deepseek-flash` (V4.1-Flash, el único modelo con visión) por HTTP directo: DeepSeek no tiene SDK oficial
 * para Java/Kotlin. API compatible con OpenAI: `POST {base}/chat/completions`, `Authorization: Bearer <clave>`.
 *  - Imagen como bloque `image_url` con data URL JPEG y `detail: high` (`low` = 512x512, inútil para leer texto);
 *    el servidor la reescala a ~1300x1300 px de área (máx. 1024 tokens), así que se envía ya a ese tamaño.
 *  - `thinking: disabled` (transcribir no necesita razonar: más rápido y barato), `response_format: json_object`
 *    (DeepSeek no admite JSON Schema: el esquema va en el prompt) y `max_tokens` holgado para páginas densas.
 */
class DeepSeekProvider(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    client: OkHttpClient? = null,
) : CloudProvider {

    override val id = CloudProviderId.DEEPSEEK
    override val maxLongSide = 2048
    override val maxPixels = 1_690_000

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(request: CloudRequest): CloudResponse {
        val body = buildRequestJson(request.jpeg).toString()
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val (code, text, retryAfter) = execute(req)
        if (code !in 200..299) throw CloudException.fromStatus(code, retryAfter, errorMessage(text))
        return parseResponse(text)
    }

    override suspend fun testKey() {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val (code, text, retryAfter) = execute(req)
        if (code !in 200..299) throw CloudException.fromStatus(code, retryAfter, errorMessage(text))
    }

    private suspend fun execute(req: Request): Triple<Int, String, Long?> = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(CloudException.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val r = try {
                    response.use { Triple(it.code, it.body?.string().orEmpty(), CloudException.parseRetryAfter(it.header("Retry-After"))) }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(CloudException.Network(e))
                    return
                }
                if (cont.isActive) cont.resume(r)
            }
        })
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val MAX_TOKENS = 16_000
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Cuerpo exacto de la petición (probado en JVM). */
        fun buildRequestJson(jpeg: ByteArray): JsonObject = buildJsonObject {
            put("model", CloudProviderId.DEEPSEEK.model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", CloudPrompt.SYSTEM + "\n\n" + CloudPrompt.SCHEMA_REMINDER)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/jpeg;base64," + jpeg.toByteString().base64())
                                put("detail", "high")
                            }
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", CloudPrompt.USER)
                        })
                    })
                })
            })
            putJsonObject("thinking") { put("type", "disabled") }
            putJsonObject("response_format") { put("type", "json_object") }
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0.0)
            put("stream", false)
        }

        /** choices[0].message.content + usage + finish_reason -> [CloudResponse]. */
        fun parseResponse(text: String): CloudResponse {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw CloudException.BadResponse("no es JSON") }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw CloudException.BadResponse("sin choices")
            val finish = (choice["finish_reason"] as? JsonPrimitive)?.content
            val content = ((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            when (finish) {
                "content_filter" -> throw CloudException.Refused("filtro de contenido")
                "insufficient_system_resource" -> throw CloudException.RateLimited(null, 503)
            }
            val usage = root["usage"] as? JsonObject
            fun u(k: String) = (usage?.get(k) as? JsonPrimitive)?.longOrNull
            val prompt = u("prompt_tokens") ?: 0L
            val hit = u("prompt_cache_hit_tokens") ?: 0L
            val miss = u("prompt_cache_miss_tokens") ?: (prompt - hit).coerceAtLeast(0)
            val tokens = CloudUsageTokens(inputTokens = miss, outputTokens = u("completion_tokens") ?: 0L, cachedInputTokens = hit)
            if (content.isBlank()) {
                if (finish == "length") throw CloudException.BadResponse("respuesta vacía (límite de tokens)")
                throw CloudException.BadResponse("respuesta vacía")
            }
            return CloudResponse(content, tokens, truncated = finish == "length")
        }

        /** Mensaje de error del cuerpo `{"error":{"message":…}}` (sin datos sensibles). */
        fun errorMessage(text: String): String? = runCatching {
            val e = json.parseToJsonElement(text).jsonObject["error"]
            when (e) {
                is JsonObject -> (e["message"] as? JsonPrimitive)?.content
                is JsonPrimitive -> e.content
                else -> null
            }
        }.getOrNull().let { CloudException.safeDetail(it) }
    }
}
