package com.scannerpromax.ai.cloud

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudProvidersTest {
    private val http = FakeHttp()
    private val jpeg = byteArrayOf(1, 2, 3)
    private val req = CloudRequest(jpeg, 1000, 1400)
    private val pageJson = """{"doc_type":"printed","blocks":[{"type":"text","bbox":[100,100,900,150],"lines":[{"text":"Hola mundo","bbox":[100,100,900,150]}],"style":null,"table":null,"chart":null,"latex":null}]}"""

    @After fun tearDown() = http.close()

    private inline fun <reified T : CloudException> expect(block: () -> Unit): T {
        try { block() } catch (e: CloudException) {
            assertTrue("esperaba ${T::class.simpleName}, llegó ${e.javaClass.simpleName}: ${e.message}", e is T)
            return e as T
        }
        fail("no lanzó ${T::class.simpleName}")
        throw AssertionError()
    }

    // ------------------------------------------------------------------------------------------ DeepSeek

    @Test fun deepSeekRequestIsExact() {
        val expected = Json.parseToJsonElement(
            """
            {"model":"deepseek-flash",
             "messages":[
               {"role":"system","content":${JsonPrimitive(CloudPrompt.SYSTEM + "\n\n" + CloudPrompt.SCHEMA_REMINDER)}},
               {"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID","detail":"high"}},
                  {"type":"text","text":${JsonPrimitive(CloudPrompt.USER)}}]}],
             "thinking":{"type":"disabled"},
             "response_format":{"type":"json_object"},
             "max_tokens":16000,
             "temperature":0.0,
             "stream":false}
            """,
        )
        assertEquals(expected, DeepSeekProvider.buildRequestJson(jpeg))
        // Orden de claves estable (útil para la caché de prefijo de DeepSeek: el prompt va antes que la imagen... del usuario).
        assertTrue(DeepSeekProvider.buildRequestJson(jpeg).toString().startsWith("{\"model\":\"deepseek-flash\",\"messages\":[{\"role\":\"system\""))
    }

    private fun dsOk(content: String, finish: String = "stop") = buildJsonObject {
        put("id", "x"); put("object", "chat.completion"); put("model", "deepseek-flash")
        put("choices", JsonArray(listOf(buildJsonObject {
            put("index", 0); put("finish_reason", finish)
            put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
        })))
        put("usage", buildJsonObject {
            put("prompt_tokens", 2100); put("completion_tokens", 800); put("total_tokens", 2900)
            put("prompt_cache_hit_tokens", 1100); put("prompt_cache_miss_tokens", 1000)
        })
    }.toString()

    @Test fun deepSeekSuccess() = runBlocking {
        http.enqueue(200, dsOk(pageJson))
        val r = DeepSeekProvider("sk-test-123456", http.base).recognize(req)
        assertEquals(pageJson, r.text)
        assertEquals(CloudUsageTokens(inputTokens = 1000, outputTokens = 800, cachedInputTokens = 1100), r.usage)
        assertFalse(r.truncated)
        val seen = http.requests.single()
        assertEquals("POST", seen.method)
        assertEquals("/chat/completions", seen.path)
        assertEquals("Bearer sk-test-123456", seen.headers["authorization"])
        assertEquals(DeepSeekProvider.buildRequestJson(jpeg), Json.parseToJsonElement(seen.body))
    }

    @Test fun deepSeekTruncatedAndFiltered() = runBlocking {
        http.enqueue(200, dsOk(pageJson.dropLast(30), finish = "length"))
        assertTrue(DeepSeekProvider("k", http.base).recognize(req).truncated)
        http.enqueue(200, dsOk("", finish = "content_filter"))
        expect<CloudException.Refused> { runBlocking { DeepSeekProvider("k", http.base).recognize(req) } }
        http.enqueue(200, dsOk(""))
        expect<CloudException.BadResponse> { runBlocking { DeepSeekProvider("k", http.base).recognize(req) } }
        Unit
    }

    @Test fun deepSeekErrors() {
        val p = DeepSeekProvider("sk-secret-abcdef", http.base)
        http.enqueue(401, """{"error":{"message":"Authentication Fails, Your api key: sk-secret-abcdef is invalid","type":"authentication_error"}}""")
        val auth = expect<CloudException.Auth> { runBlocking { p.recognize(req) } }
        assertFalse("la clave no aparece en el mensaje", auth.message!!.contains("abcdef"))
        assertTrue(auth.stopsQueue)
        http.enqueue(402, """{"error":{"message":"Insufficient Balance"}}""")
        expect<CloudException.Billing> { runBlocking { p.recognize(req) } }
        http.enqueue(429, """{"error":{"message":"Rate Limit Reached"}}""", mapOf("Retry-After" to "7"))
        assertEquals(7000L, expect<CloudException.RateLimited> { runBlocking { p.recognize(req) } }.retryAfterMs)
        http.enqueue(503, """{"error":{"message":"Server Overloaded"}}""")
        expect<CloudException.RateLimited> { runBlocking { p.recognize(req) } }
        http.enqueue(500, "oops")
        expect<CloudException.Server> { runBlocking { p.recognize(req) } }
        http.enqueue(422, """{"error":{"message":"Invalid Parameters"}}""")
        expect<CloudException.BadRequest> { runBlocking { p.recognize(req) } }
        // Puerto cerrado -> red.
        expect<CloudException.Network> { runBlocking { DeepSeekProvider("k", "http://127.0.0.1:1").recognize(req) } }
    }

    @Test fun deepSeekTestKeyUsesFreeModelsEndpoint() = runBlocking {
        http.enqueue(200, """{"object":"list","data":[{"id":"deepseek-flash","object":"model"}]}""")
        DeepSeekProvider("k", http.base).testKey()
        assertEquals("GET", http.requests.single().method)
        assertEquals("/models", http.requests.single().path)
        http.enqueue(401, """{"error":{"message":"bad key"}}""")
        expect<CloudException.Auth> { runBlocking { DeepSeekProvider("k", http.base).testKey() } }
        Unit
    }

    @Test fun retriesWithBackoffThenSucceeds() = runBlocking {
        http.enqueue(429, """{"error":{"message":"slow down"}}""", mapOf("Retry-After" to "5"))
        http.enqueue(500, "x")
        http.enqueue(200, dsOk(pageJson))
        val waits = ArrayList<Long>()
        val p = DeepSeekProvider("k", http.base)
        val r = CloudPipeline.withRetries(sleep = { waits += it }) { p.recognize(req) }
        assertEquals(pageJson, r.text)
        assertEquals(listOf(5000L, 6000L), waits)
        assertEquals(3, http.requests.size)
    }

    @Test fun retriesGiveUpAndAuthDoesNotRetry() = runBlocking {
        repeat(4) { http.enqueue(500, "x") }
        val waits = ArrayList<Long>()
        val p = DeepSeekProvider("k", http.base)
        expect<CloudException.Server> { runBlocking { CloudPipeline.withRetries(sleep = { waits += it }) { p.recognize(req) } } }
        assertEquals(3, waits.size)
        http.requests.clear()
        http.enqueue(401, """{"error":{"message":"no"}}""")
        expect<CloudException.Auth> { runBlocking { CloudPipeline.withRetries(sleep = { fail("no debe esperar") }) { p.recognize(req) } } }
        assertEquals(1, http.requests.size)
    }

    // ------------------------------------------------------------------------------------------ Claude (SDK oficial)

    private fun claudeMsg(text: String, stop: String = "end_turn", details: String = "null") = """
        {"id":"msg_01","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[{"type":"text","text":${JsonPrimitive(text)}}],
         "stop_reason":"$stop","stop_sequence":null,"stop_details":$details,
         "usage":{"input_tokens":2400,"output_tokens":900,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}
    """.trimIndent()

    @Test fun claudeRequestThroughSdk() = runBlocking {
        http.enqueue(200, claudeMsg(pageJson))
        val p = ClaudeHaikuProvider("sk-ant-test-key", http.base)
        val r = p.recognize(req)
        assertEquals(pageJson, r.text)
        assertEquals(CloudUsageTokens(2400, 900, 0), r.usage)
        val seen = http.requests.single()
        assertEquals("/v1/messages", seen.path)
        assertEquals("sk-ant-test-key", seen.headers["x-api-key"])
        assertNotNull(seen.headers["anthropic-version"])
        val body = Json.parseToJsonElement(seen.body).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("16000", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull("Haiku 5.5: sin temperature", body["temperature"])
        val oc = body["output_config"]!!.jsonObject
        assertEquals("low", oc["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", oc["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(CloudPrompt.SCHEMA_JSON), oc["format"]!!.jsonObject["schema"])
        assertEquals(CloudPrompt.SYSTEM, body["system"]!!.jsonPrimitive.content)
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val img = content[0].jsonObject
        assertEquals("image", img["type"]!!.jsonPrimitive.content)
        assertEquals("base64", img["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", img["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("AQID", img["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(CloudPrompt.USER, content[1].jsonObject["text"]!!.jsonPrimitive.content)
        p.close(); Unit
    }

    @Test fun claudeRefusalAndMaxTokens() = runBlocking {
        val p = ClaudeHaikuProvider("k", http.base)
        http.enqueue(200, claudeMsg("", stop = "refusal", details = """{"type":"refusal","category":"cyber","explanation":"no"}"""))
        val e = expect<CloudException.Refused> { runBlocking { p.recognize(req) } }
        assertTrue(e.message!!, e.message!!.contains("cyber"))
        http.enqueue(200, claudeMsg(pageJson.dropLast(40), stop = "max_tokens"))
        assertTrue(p.recognize(req).truncated)
        p.close(); Unit
    }

    @Test fun claudeErrorsMapToCloudExceptions() {
        val p = ClaudeHaikuProvider("sk-ant-secret-zzzzzz", http.base)
        http.enqueue(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        expect<CloudException.Auth> { runBlocking { p.recognize(req) } }
        http.enqueue(403, """{"type":"error","error":{"type":"permission_error","message":"no access"}}""")
        expect<CloudException.Auth> { runBlocking { p.recognize(req) } }
        http.enqueue(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow"}}""", mapOf("retry-after" to "3"))
        assertEquals(3000L, expect<CloudException.RateLimited> { runBlocking { p.recognize(req) } }.retryAfterMs)
        http.enqueue(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        expect<CloudException.RateLimited> { runBlocking { p.recognize(req) } }
        http.enqueue(500, """{"type":"error","error":{"type":"api_error","message":"boom"}}""")
        expect<CloudException.Server> { runBlocking { p.recognize(req) } }
        http.enqueue(400, """{"type":"error","error":{"type":"billing_error","message":"Your credit balance is too low"}}""")
        expect<CloudException.Billing> { runBlocking { p.recognize(req) } }
        http.enqueue(400, """{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}""")
        expect<CloudException.BadRequest> { runBlocking { p.recognize(req) } }
        assertEquals("el SDK no reintenta (lo hace CloudPipeline)", 7, http.requests.size)
        expect<CloudException.Network> { runBlocking { ClaudeHaikuProvider("k", "http://127.0.0.1:1").recognize(req) } }
        p.close()
    }

    @Test fun claudeTestKey() = runBlocking {
        http.enqueue(200, """{"id":"claude-haiku-5-5","type":"model","display_name":"Claude Haiku 5.5","created_at":"2026-09-01T00:00:00Z"}""")
        val p = ClaudeHaikuProvider("k", http.base)
        p.testKey()
        assertEquals("/v1/models/claude-haiku-5-5", http.requests.single().path)
        assertEquals("GET", http.requests.single().method)
        p.close(); Unit
    }

    // ------------------------------------------------------------------------------------------ tubería completa

    private class FakeProvider(val replies: ArrayDeque<() -> CloudResponse>) : CloudProvider {
        override val id = CloudProviderId.CLAUDE_HAIKU
        override val maxLongSide = 1568
        override val maxPixels = 1568 * 1568
        var calls = 0
        override suspend fun recognize(request: CloudRequest): CloudResponse { calls++; return replies.removeFirst()() }
        override suspend fun testKey() = Unit
    }

    private val mlkit = OcrResult(
        "Hola rnundo", 2000, 2800,
        listOf(OcrBlock("Hola rnundo", OcrRect(200f, 280f, 1800f, 420f), listOf(OcrLine("Hola rnundo", OcrRect(200f, 280f, 1800f, 420f))))),
    )

    @Test fun pipelineEndToEnd() = runBlocking {
        val fake = FakeProvider(ArrayDeque(listOf(
            { throw CloudException.RateLimited(null, 429) },
            { CloudResponse(pageJson, CloudUsageTokens(2400, 900), truncated = false) },
        )))
        val recorded = ArrayList<CloudUsageTokens>()
        val labels = ArrayList<String>()
        val r = CloudPipeline.run(fake, req, 2000, 2800, mlkit, record = { recorded += it }, onProgress = { labels += it.label }, sleep = {})
        assertNotNull(r)
        assertEquals("Hola mundo", r!!.ocr.text)
        assertEquals(OcrRect(200f, 280f, 1800f, 420f), r.ocr.blocks.single().lines.single().box)
        assertEquals("nube:claude-haiku-5-5", r.layout.engine)
        assertEquals("impreso", r.layout.docType)
        assertEquals(listOf(CloudUsageTokens(2400, 900)), recorded)
        assertEquals(2, fake.calls)
        assertTrue(labels.any { it.startsWith("Reintentando") })
    }

    @Test fun pipelineGarbageAndBadJson() = runBlocking {
        val bigMl = mlkit.copy(text = "Un texto bastante largo que ML Kit sí encontró en la página")
        val empty = FakeProvider(ArrayDeque(listOf({ CloudResponse("""{"doc_type":"printed","blocks":[]}""", CloudUsageTokens(1, 1), false) })))
        assertNull(CloudPipeline.run(empty, req, 2000, 2800, bigMl, record = {}, onProgress = {}, sleep = {}))
        var recorded = 0
        val prose = FakeProvider(ArrayDeque(listOf({ CloudResponse("I cannot help with that.", CloudUsageTokens(1, 1), false) })))
        expect<CloudException.BadResponse> { runBlocking { CloudPipeline.run(prose, req, 2000, 2800, bigMl, record = { recorded++ }, onProgress = {}, sleep = {}) } }
        assertEquals("los tokens se cuentan aunque la respuesta no sirva", 1, recorded)
    }
}
