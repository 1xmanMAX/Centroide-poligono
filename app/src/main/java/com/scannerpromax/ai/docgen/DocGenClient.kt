package com.scannerpromax.ai.docgen

import com.scannerpromax.ai.cloud.CloudProviderId
import com.scannerpromax.ai.cloud.CloudUsageTokens

/** Modelos que pueden reconstruir documentos. La clave es la del proveedor ([keyId]). */
enum class DocGenModel(val id: String, val keyId: CloudProviderId, val label: String, val short: String, val vision: Boolean = true) {
    CLAUDE_HAIKU("claude-haiku-5-5", CloudProviderId.CLAUDE_HAIKU, "Claude Haiku 5.5", "Haiku"),
    CLAUDE_SONNET("claude-sonnet-5-5", CloudProviderId.CLAUDE_HAIKU, "Claude Sonnet 5.5", "Sonnet"),
    DEEPSEEK_FLASH("deepseek-flash", CloudProviderId.DEEPSEEK, "DeepSeek (deepseek-flash)", "DeepSeek"),
}

/** RÁPIDA = una llamada por página con JSON estructurado; CALIDAD = bucle con herramientas que revisa y completa. */
enum class DocGenMode(val label: String) { FAST("Rápida"), QUALITY("Alta calidad") }

/** Llamada a herramienta pedida por el modelo. [argsJson] puede venir mal formado (se valida antes de ejecutar). */
data class ToolCall(val id: String, val name: String, val argsJson: String)

data class ToolOutcome(val id: String, val content: String, val isError: Boolean)

enum class StopKind { TOOL_USE, END_TURN, MAX_TOKENS, REFUSAL, OTHER }

/** Un turno del modelo: texto, llamadas a herramientas, por qué paró y el uso facturado. */
data class AgentTurn(
    val text: String,
    val calls: List<ToolCall>,
    val stop: StopKind,
    val usage: CloudUsageTokens,
    /** Modelo que respondió de verdad (puede ser el de respaldo del servidor). */
    val servedModel: String,
    val refusalCategory: String? = null,
)

/** Respuesta del modo rápido (JSON en texto). */
data class StructuredReply(val text: String, val usage: CloudUsageTokens, val truncated: Boolean, val servedModel: String)

/**
 * Conversación con herramientas. El cliente guarda el historial en el formato de su API (Claude: bloques del SDK
 * devueltos tal cual, con los de pensamiento; DeepSeek: mensajes OpenAI) para que cada turno sea append-only.
 */
interface AgentSession {
    /** Pide el siguiente turno. Lanza [com.scannerpromax.ai.cloud.CloudException] si falla. */
    suspend fun next(): AgentTurn

    /** Añade los resultados de TODAS las llamadas del último turno (y, opcionalmente, un texto del usuario detrás). */
    fun addToolResults(results: List<ToolOutcome>, followUp: String? = null)

    /** Añade un mensaje del usuario (solo tras un turno sin llamadas). */
    fun addUserText(text: String)
}

interface DocGenClient : AutoCloseable {
    val model: DocGenModel

    /** Lado largo / píxeles máximos de la imagen que aprovecha el proveedor. */
    val maxLongSide: Int
    val maxPixels: Int

    fun startSession(system: String, tools: List<ToolSpec>, firstUser: List<UserPart>): AgentSession

    suspend fun structured(system: String, user: List<UserPart>, schema: kotlinx.serialization.json.JsonObject): StructuredReply

    override fun close() {}
}
