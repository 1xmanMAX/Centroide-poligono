package com.scannerpromax.ai.jev

import com.scannerpromax.ai.cloud.CloudException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Respuesta HTTP en bruto. */
class JevHttpReply(val status: Int, val body: String, val retryAfterMs: Long? = null)

/** Transporte HTTP (bloqueante: se llama desde un hilo de E/S). Sustituible en los tests. */
fun interface JevTransport {
    /** Lanza [IOException] si no hay conexión. */
    fun post(url: String, headers: Map<String, String>, body: String): JevHttpReply
}

/** El mismo cliente que ai/cloud (OkHttp), con tiempos acotados: si Jev tarda, la app sigue sin él. */
class OkHttpJevTransport(client: OkHttpClient? = null) : JevTransport {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun post(url: String, headers: Map<String, String>, body: String): JevHttpReply {
        val b = Request.Builder().url(url).post(body.toRequestBody(JSON_TYPE))
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r ->
            return JevHttpReply(r.code, r.body?.string().orEmpty(), CloudException.parseRetryAfter(r.header("Retry-After")))
        }
    }

    private companion object { val JSON_TYPE = "application/json; charset=utf-8".toMediaType() }
}

/**
 * Cliente de Jev: arma la petición, reintenta 429/529/5xx/red con espera exponencial (o la de Retry-After) y
 * traduce los fallos a [CloudException]. Nunca registra la clave (solo viaja en la cabecera Authorization).
 */
class JevClient(
    private val apiKey: String,
    private val transport: JevTransport = OkHttpJevTransport(),
    private val url: String = JevApi.URL,
    private val maxTries: Int = 4,
    private val baseDelayMs: Long = 800,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Tokens de entrada acumulados por este cliente (coste: [JevApi.cost]). */
    @Volatile var inputTokens = 0L; private set
    @Volatile var requests = 0; private set

    fun ask(req: JevRequest): JevResponse {
        val body = req.toJson().toString()
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            put("Accept", "application/json")
            if (req.hasImages) put("TypeSafe-Danger-Zone-Images", "true")
        }
        val t0 = System.nanoTime()
        var last: CloudException? = null
        for (attempt in 0 until maxTries) {
            if (attempt > 0) {
                val wait = (last as? CloudException.RateLimited)?.retryAfterMs ?: (baseDelayMs shl (attempt - 1))
                sleeper(wait.coerceAtMost(20_000))
            }
            val reply = try {
                transport.post(url, headers, body)
            } catch (e: IOException) {
                last = CloudException.Network(e); continue
            }
            if (reply.status in 200..299) {
                val r = JevResponse.parse(reply.body, (System.nanoTime() - t0) / 1_000_000)
                synchronized(this) { inputTokens += r.inputTokens; requests++ }
                return r
            }
            val e = CloudException.fromStatus(reply.status, reply.retryAfterMs, JevResponse.errorDetail(reply.body))
            if (!e.retryable) throw e
            last = e
        }
        throw last ?: CloudException.Network(null)
    }
}
