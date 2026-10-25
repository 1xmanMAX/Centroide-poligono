package com.scannerpromax.ai.docgen

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.core.ObjectMappers
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaThinkingConfigDisabled
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolChoiceAuto
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.scannerpromax.ai.cloud.ClaudeHaikuProvider
import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.CloudUsageTokens
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.toByteString
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Claude (Haiku 5.5 = versión RÁPIDA, Sonnet 5.5 = ALTA CALIDAD) con el SDK OFICIAL `com.anthropic` (cliente asíncrono
 * OkHttp, cancelable desde la corrutina), endpoint beta de Messages para poder pedir los respaldos del servidor.
 *
 *  - Herramientas con `strict: true` y `tool_choice: auto` (Sonnet 5.5 rechaza `any`/`tool` con un 400): la
 *    instrucción de terminar con finish_document va en el prompt y el motor insiste si no llega.
 *  - Sonnet 5.5: pensamiento adaptativo + effort `medium`; respaldo del servidor `fallbacks: "default"` con la beta
 *    `server-side-fallback-2026-07-01` (solo en la API de Claude: es la que usa la app). Los bloques de pensamiento se
 *    devuelven SIN tocar (BetaMessage.toParam()), y el historial solo crece (append-only).
 *  - Haiku 5.5: sin respaldo en el servidor (no existe para Haiku) -> `refusal` se trata en el cliente.
 *    Bucle: pensamiento adaptativo + effort `low`; modo rápido: pensamiento desactivado + effort `low` (transcribir).
 *  - Sin streaming (max_tokens 16k, por debajo del límite de tiempo de las peticiones normales): por eso NO se usa
 *    `eager_input_streaming`; las entradas de las herramientas se validan igualmente en [DocToolExecutor].
 *  - Caché de prompt automática (`cache_control` de nivel superior): cada turno relee el historial desde la caché.
 *  - Sin reintentos en el SDK: los hace el motor con espera creciente.
 */
class ClaudeDocClient(
    apiKey: String,
    override val model: DocGenModel,
    baseUrl: String? = null,
) : DocGenClient {

    init { require(model.keyId == com.scannerpromax.ai.cloud.CloudProviderId.CLAUDE_HAIKU) }

    override val maxLongSide = 1568
    override val maxPixels = 1568 * 1568

    private val client: AnthropicClientAsync = AnthropicOkHttpClientAsync.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .maxRetries(0)
        .timeout(Duration.ofSeconds(240))
        .build()

    override fun close() { runCatching { client.close() } }

    private val isSonnet get() = model == DocGenModel.CLAUDE_SONNET

    override fun startSession(system: String, tools: List<ToolSpec>, firstUser: List<UserPart>): AgentSession = Session(system, tools, firstUser)

    override suspend fun structured(system: String, user: List<UserPart>, schema: JsonObject): StructuredReply {
        var withSchema = true
        while (true) {
            val params = structuredParams(system, user, schema, withSchema)
            val msg = try {
                call { client.beta().messages().create(params).await() }
            } catch (e: CloudException.BadRequest) {
                // Esquema demasiado complejo para structured outputs: se repite con el esquema solo en el prompt.
                if (withSchema && schemaProblem(e)) { withSchema = false; continue }
                throw e
            }
            val turn = toTurn(msg)
            if (turn.stop == StopKind.REFUSAL) throw CloudException.Refused(turn.refusalCategory)
            if (turn.text.isBlank()) throw CloudException.BadResponse(if (turn.stop == StopKind.MAX_TOKENS) "respuesta vacía (límite de tokens)" else "respuesta vacía")
            return StructuredReply(turn.text, turn.usage, turn.stop == StopKind.MAX_TOKENS, turn.servedModel)
        }
    }

    internal fun structuredParams(system: String, user: List<UserPart>, schema: JsonObject, withSchema: Boolean): MessageCreateParams {
        val b = base(system + if (withSchema) "" else "\n\n" + DocPrompts.FAST_SCHEMA_REMINDER)
        val oc = BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW)
        if (withSchema) oc.format(BetaJsonOutputFormat.builder().schema(jsonSchema(schema)).build())
        b.outputConfig(oc.build())
        if (isSonnet) b.thinking(BetaThinkingConfigAdaptive.builder().build())
        else b.thinking(BetaThinkingConfigDisabled.builder().build()) // documentado para Haiku 5.5 con effort <= high
        b.addUserMessageOfBetaContentBlockParams(blocks(user))
        return b.build()
    }

    private fun base(system: String): MessageCreateParams.Builder {
        val b = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(MAX_TOKENS)
            .system(system)
        if (isSonnet) {
            b.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            b.fallbacksDefault()
        }
        return b
    }

    private inner class Session(private val system: String, private val tools: List<ToolSpec>, firstUser: List<UserPart>) : AgentSession {
        private val messages = ArrayList<BetaMessageParam>()
        private var strict = true

        init {
            messages += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(blocks(firstUser)).build()
        }

        fun params(): MessageCreateParams {
            val b = base(system)
                .messages(messages.toList())
                .toolChoice(BetaToolChoiceAuto.builder().build())
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .thinking(BetaThinkingConfigAdaptive.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(if (isSonnet) BetaOutputConfig.Effort.MEDIUM else BetaOutputConfig.Effort.LOW).build())
            tools.forEach { b.addTool(tool(it, strict)) }
            return b.build()
        }

        override suspend fun next(): AgentTurn {
            while (true) {
                val msg = try {
                    call { client.beta().messages().create(params()).await() }
                } catch (e: CloudException.BadRequest) {
                    if (strict && schemaProblem(e)) { strict = false; continue }
                    throw e
                }
                val turn = toTurn(msg)
                // El turno del asistente entra en el historial TAL CUAL (incluidos pensamiento y respaldo).
                if (turn.stop != StopKind.REFUSAL) messages += msg.toParam()
                return turn
            }
        }

        override fun addToolResults(results: List<ToolOutcome>, followUp: String?) {
            val content = results.map { r ->
                BetaContentBlockParam.ofToolResult(
                    BetaToolResultBlockParam.builder().toolUseId(r.id).content(r.content).isError(r.isError).build(),
                )
            } + listOfNotNull(followUp?.let { BetaContentBlockParam.ofText(it) })
            messages += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(content).build()
        }

        override fun addUserText(text: String) {
            messages += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(text).build()
        }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        var t = e
        while ((t is CompletionException || t is ExecutionException) && t.cause != null) t = t.cause!!
        throw ClaudeHaikuProvider.map(t)
    }

    companion object {
        const val MAX_TOKENS = 16_000L

        /** 400 por el esquema (strict / structured outputs): se reintenta una vez sin él. */
        internal fun schemaProblem(e: CloudException.BadRequest): Boolean {
            val m = (e.message ?: "").lowercase()
            return e.status == 400 && ("schema" in m || "strict" in m || "output_config" in m || "format" in m || "too many" in m || "complex" in m)
        }

        fun blocks(parts: List<UserPart>): List<BetaContentBlockParam> = parts.map { p ->
            when (p) {
                is UserPart.Text -> BetaContentBlockParam.ofText(p.text)
                is UserPart.Image -> BetaContentBlockParam.ofImage(
                    BetaImageBlockParam.builder().source(
                        BetaBase64ImageSource.builder()
                            .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                            .data(p.jpeg.toByteString().base64())
                            .build(),
                    ).build(),
                )
            }
        }

        fun tool(spec: ToolSpec, strict: Boolean): BetaTool {
            val props = spec.schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = (spec.schema["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
            val pb = BetaTool.InputSchema.Properties.builder()
            props.forEach { (k, v) -> pb.putAdditionalProperty(k, JsonValue.from(ClaudeHaikuProvider.plain(v))) }
            return BetaTool.builder()
                .name(spec.name)
                .description(spec.description)
                .inputSchema(
                    BetaTool.InputSchema.builder()
                        .properties(pb.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build(),
                )
                .apply { if (strict) strict(true) }
                .build()
        }

        fun jsonSchema(schema: JsonObject): BetaJsonOutputFormat.Schema {
            val b = BetaJsonOutputFormat.Schema.builder()
            schema.forEach { (k, v) -> b.putAdditionalProperty(k, JsonValue.from(ClaudeHaikuProvider.plain(v))) }
            return b.build()
        }

        /** Respuesta del SDK -> turno neutro. Comprueba stop_reason ANTES de leer el contenido. */
        fun toTurn(m: BetaMessage): AgentTurn {
            val u = m.usage()
            val creation = u.cacheCreationInputTokens().orElse(0L)
            // Escribir en caché cuesta 1,25× la entrada normal: se cuenta así para que el coste salga bien.
            val usage = CloudUsageTokens(
                inputTokens = u.inputTokens() + (creation * 1.25).toLong(),
                outputTokens = u.outputTokens(),
                cachedInputTokens = u.cacheReadInputTokens().orElse(0L),
            )
            val served = runCatching { m.model().asString() }.getOrDefault("")
            val stop = when (m.stopReason().orElse(null)) {
                BetaStopReason.TOOL_USE -> StopKind.TOOL_USE
                BetaStopReason.END_TURN, BetaStopReason.STOP_SEQUENCE -> StopKind.END_TURN
                BetaStopReason.MAX_TOKENS -> StopKind.MAX_TOKENS
                BetaStopReason.REFUSAL -> StopKind.REFUSAL
                else -> StopKind.OTHER
            }
            if (stop == StopKind.REFUSAL) {
                val cat = m.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse(null)
                return AgentTurn("", emptyList(), stop, usage, served, cat)
            }
            val mapper = ObjectMappers.jsonMapper()
            val calls = m.content().mapNotNull { b ->
                b.toolUse().orElse(null)?.let { tu -> ToolCall(tu.id(), tu.name(), mapper.writeValueAsString(tu._input())) }
            }
            val text = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            return AgentTurn(text, calls, if (calls.isNotEmpty() && stop != StopKind.MAX_TOKENS) StopKind.TOOL_USE else stop, usage, served)
        }
    }
}
