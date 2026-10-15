package com.scannerpromax.ai.cloud

/** Proveedores de IA en la nube que el usuario puede elegir (con SU clave de API). */
enum class CloudProviderId(
    val label: String,
    val model: String,
    /** Dónde se procesan las páginas (aviso de privacidad). */
    val serverLocation: String,
    val company: String,
    val keyHint: String,
    val keysUrl: String,
) {
    DEEPSEEK(
        label = "DeepSeek (deepseek-flash)",
        model = "deepseek-flash",
        serverLocation = "China",
        company = "DeepSeek",
        keyHint = "sk-…",
        keysUrl = "https://platform.deepseek.com/api_keys",
    ),
    CLAUDE_HAIKU(
        label = "Claude Haiku 5.5 (Anthropic)",
        model = "claude-haiku-5-5",
        serverLocation = "EE. UU.",
        company = "Anthropic",
        keyHint = "sk-ant-…",
        keysUrl = "https://platform.claude.com/settings/keys",
    ),
}

/** Página a reconocer: JPEG ya reducido al tamaño que conviene al proveedor. */
class CloudRequest(val jpeg: ByteArray, val width: Int, val height: Int)

/** Respuesta en bruto (el texto JSON del modelo) con el uso facturado. */
class CloudResponse(
    val text: String,
    val usage: CloudUsageTokens,
    /** El modelo paró por el límite de tokens (JSON probablemente cortado). */
    val truncated: Boolean,
)

/**
 * Un proveedor de IA en la nube. Las implementaciones NO reintentan (lo hace [CloudRecognizer] con espera
 * creciente) y traducen todos los fallos a [CloudException]. Deben poder cancelarse (corrutina cancelada = petición
 * HTTP cancelada). Nunca registran la clave.
 */
interface CloudProvider {
    val id: CloudProviderId

    /** Envía la página y devuelve el JSON del modelo. */
    suspend fun recognize(request: CloudRequest): CloudResponse

    /** Comprueba la clave con una llamada gratuita (lista de modelos). Lanza [CloudException] si falla. */
    suspend fun testKey()

    /** Tamaño ideal de la imagen para este proveedor: lado largo máximo y píxeles máximos. */
    val maxLongSide: Int
    val maxPixels: Int
}

/** Fallos de la nube, ya clasificados para decidir qué hacer (reintentar, esperar red, parar la cola, usar ML Kit). */
sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401/403: clave incorrecta o sin permiso -> parar la cola y avisar. */
    class Auth(val status: Int, detail: String? = null) : CloudException("Clave rechazada ($status)" + (detail?.let { ": $it" } ?: ""))
    /** 402 / facturación: sin saldo o crédito -> parar la cola y avisar. */
    class Billing(detail: String? = null) : CloudException("Sin saldo en la cuenta" + (detail?.let { ": $it" } ?: ""))
    /** 429 / 529 / 503: demasiadas peticiones o servidor saturado -> reintentar con espera. */
    class RateLimited(val retryAfterMs: Long?, val status: Int) : CloudException("Límite de peticiones o servidor saturado ($status)")
    /** 5xx -> reintentar. */
    class Server(val status: Int) : CloudException("Error del servidor ($status)")
    /** Sin conexión, DNS, tiempo agotado -> reintentar y, si sigue, esperar red. */
    class Network(cause: Throwable?) : CloudException("Sin conexión con el servicio", cause)
    /** 400/404/422: petición no válida (no se arregla reintentando) -> ML Kit. */
    class BadRequest(val status: Int, detail: String? = null) : CloudException("Petición no válida ($status)" + (detail?.let { ": $it" } ?: ""))
    /** El modelo se negó a procesar la página (Claude stop_reason = refusal, DeepSeek content_filter) -> ML Kit. */
    class Refused(detail: String? = null) : CloudException("El servicio rechazó procesar la página" + (detail?.let { " ($it)" } ?: ""))
    /** Respuesta vacía o ilegible -> ML Kit. */
    class BadResponse(detail: String) : CloudException("Respuesta no válida: $detail")

    val retryable: Boolean get() = this is RateLimited || this is Server || this is Network
    /** Error de cuenta: no tiene sentido seguir enviando páginas. */
    val stopsQueue: Boolean get() = this is Auth || this is Billing

    companion object {
        /** Clasificación común por código HTTP. */
        fun fromStatus(status: Int, retryAfterMs: Long?, detail: String?): CloudException = when (status) {
            401, 403 -> Auth(status, detail)
            402 -> Billing(detail)
            429, 503, 529 -> RateLimited(retryAfterMs, status)
            in 500..599 -> Server(status)
            else -> BadRequest(status, detail)
        }

        /** Retry-After en segundos (o fecha HTTP, que se ignora) -> ms, acotado a 2 min. */
        fun parseRetryAfter(v: String?): Long? = v?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).toLong().coerceAtMost(120_000) }

        /** Mensaje del servidor sin datos sensibles (recortado). */
        fun safeDetail(s: String?): String? = s?.replace(Regex("sk-[A-Za-z0-9_\\-]{6,}"), "sk-…")?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
    }
}
