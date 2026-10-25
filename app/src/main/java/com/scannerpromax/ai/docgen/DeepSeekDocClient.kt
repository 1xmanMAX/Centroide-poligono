package com.scannerpromax.ai.docgen

import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.CloudUsageTokens
import com.scannerpromax.ai.cloud.DeepSeekProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek `deepseek-flash` (el único modelo de DeepSeek con visión) por HTTP, API compatible con OpenAI
 * (`POST /chat/completions`), igual que [DeepSeekProvider]:
 *  - Bucle: `tools` (type function, JSON Schema), `tool_choice: auto`, respuesta con `tool_calls`
 *    (`function.arguments` es TEXTO JSON, puede venir mal formado: se valida antes de ejecutar) y resultados como
 *    mensajes `role: tool` con su `tool_call_id`. Pensamiento DESACTIVADO (así no hay `reasoning_content` que
 *    devolver en cada turno y es más rápido); sin `strict` (en DeepSeek es beta en otra URL): valida la app.
 *  - Modo rápido: `response_format: json_object` con el esquema en el prompt (DeepSeek no admite JSON Schema).
 *  - Imágenes: bloques `image_url` con data URL JPEG y `detail: high`.
 */
class DeepSeekDocClient(
    private val apiKey: String,
    private val baseUrl: String = DeepSeekProvider.DEFAULT_BASE_URL,
    client: OkHttpClient? = null,
) : DocGenClient {

    override val model = DocGenModel.DEEPSEEK_FLASH
    override val maxLongSide = 2048
    override val maxPixels = 1_690_000

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    override fun startSession(system: String, tools: List<ToolSpec>, firstUser: List<UserPart>): AgentSession = Session(system, tools, firstUser)

    override suspend fun structured(system: String, user: List<UserPart>, schema: JsonObject): StructuredReply {
        val body = buildJsonObject {
            put("model", model.id)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system + "\n\n" + DocPrompts.FAST_SCHEMA_REMINDER) })
                add(userMessage(user))
            })
            putJsonObject("thinking") { put("type", "disabled") }
            putJsonObject("response_format") { put("type", "json_object") }
            put("max_tokens", MAX_TOKENS)
            put("stream", false)
        }
        val root = post(body)
        val turn = parseTurn(root)
        if (turn.stop == StopKind.REFUSAL) throw CloudException.Refused("filtro de contenido")
        if (turn.text.isBlank()) throw CloudException.BadResponse(if (turn.stop == StopKind.MAX_TOKENS) "respuesta vacía (límite de tokens)" else "respuesta vacía")
        return StructuredReply(turn.text, turn.usage, turn.stop == StopKind.MAX_TOKENS, turn.servedModel)
    }

    private inner class Session(system: String, private val tools: List<ToolSpec>, firstUser: List<UserPart>) : AgentSession {
        private val messages = ArrayList<JsonElement>()

        init {
            messages += buildJsonObject { put("role", "system"); put("content", system) }
            messages += userMessage(firstUser)
        }

        fun body(): JsonObject = buildJsonObject {
            put("model", model.id)
            put("messages", JsonArray(messages.toList()))
            put("tools", JsonArray(tools.map(::toolJson)))
            put("tool_choice", "auto")
            putJsonObject("thinking") { put("type", "disabled") }
            put("max_tokens", MAX_TOKENS)
            put("stream", false)
        }

        override suspend fun next(): AgentTurn {
            val root = post(body())
            val turn = parseTurn(root)
            if (turn.stop != StopKind.REFUSAL) {
                val msg = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                // Mensaje del asistente tal cual (con tool_calls), sin campos nulos que la API pueda rechazar.
                messages += JsonObject((msg ?: buildJsonObject { put("role", "assistant"); put("content", turn.text) }).filterValues { it !is kotlinx.serialization.json.JsonNull })
            }
            return turn
        }

        override fun addToolResults(results: List<ToolOutcome>, followUp: String?) {
            for (r in results) messages += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", r.id)
                put("content", r.content)
            }
            followUp?.let { addUserText(it) }
        }

        override fun addUserText(text: String) {
            messages += buildJsonObject { put("role", "user"); put("content", text) }
        }
    }

    private suspend fun post(body: JsonObject): JsonObject {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val (code, text, retryAfter) = execute(req)
        if (code !in 200..299) throw CloudException.fromStatus(code, retryAfter, DeepSeekProvider.errorMessage(text))
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw CloudException.BadResponse("no es JSON") }
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
        const val MAX_TOKENS = 16_000
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun toolJson(spec: ToolSpec): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", spec.name)
                put("description", spec.description)
                put("parameters", spec.schema)
            }
        }

        fun userMessage(parts: List<UserPart>): JsonObject = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                parts.forEach { p ->
                    when (p) {
                        is UserPart.Text -> add(buildJsonObject { put("type", "text"); put("text", p.text) })
                        is UserPart.Image -> add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/jpeg;base64," + p.jpeg.toByteString().base64())
                                put("detail", "high")
                            }
                        })
                    }
                }
            })
        }

        /** choices[0] -> turno neutro (finish_reason: stop | length | tool_calls | content_filter | …). */
        fun parseTurn(root: JsonObject): AgentTurn {
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw CloudException.BadResponse("sin choices")
            val finish = (choice["finish_reason"] as? JsonPrimitive)?.content
            if (finish == "insufficient_system_resource") throw CloudException.RateLimited(null, 503)
            val msg = choice["message"] as? JsonObject
            val content = (msg?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val calls = (msg?.get("tool_calls") as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val f = o["function"] as? JsonObject ?: return@mapNotNull null
                val name = (f["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val args = when (val a = f["arguments"]) {
                    is JsonPrimitive -> a.content
                    is JsonObject -> a.toString()
                    else -> "{}"
                }
                ToolCall((o["id"] as? JsonPrimitive)?.content ?: "call_$name", name, args)
            }
            val usage = root["usage"] as? JsonObject
            fun u(k: String) = (usage?.get(k) as? JsonPrimitive)?.longOrNull
            val prompt = u("prompt_tokens") ?: 0L
            val hit = u("prompt_cache_hit_tokens") ?: 0L
            val miss = u("prompt_cache_miss_tokens") ?: (prompt - hit).coerceAtLeast(0)
            val tokens = CloudUsageTokens(inputTokens = miss, outputTokens = u("completion_tokens") ?: 0L, cachedInputTokens = hit)
            val stop = when {
                finish == "content_filter" -> StopKind.REFUSAL
                calls.isNotEmpty() && finish != "length" -> StopKind.TOOL_USE
                finish == "length" -> StopKind.MAX_TOKENS
                finish == "stop" -> StopKind.END_TURN
                else -> StopKind.OTHER
            }
            return AgentTurn(content, calls, stop, tokens, (root["model"] as? JsonPrimitive)?.content ?: "deepseek-flash")
        }
    }
}
