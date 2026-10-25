package com.scannerpromax.ai.docgen

import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.CloudUsageTokens
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Clientes reales (SDK de Claude / HTTP de DeepSeek) contra un servidor HTTP local: sin claves ni llamadas reales. */
class DocGenProvidersTest {
    private val http = DocFakeHttp()

    @After fun close() = http.close()

    private fun body(i: Int): JsonObject = Json.parseToJsonElement(http.requests[i].body).jsonObject

    private fun pages(n: Int) = (1..n).map { PageInput(it, byteArrayOf(1, 2, 3), 800, 1100, listOf(OcrLineRef("Texto $it", Box1000(50, 50, 950, 80), 0.9f))) }

    // ------------------------------------------------------------------ Claude

    private fun claudeMsg(model: String, content: String, stop: String, details: String = "null") = """
        {"id":"msg_01","type":"message","role":"assistant","model":"$model","content":$content,
         "stop_reason":"$stop","stop_sequence":null,"stop_details":$details,
         "usage":{"input_tokens":3000,"output_tokens":400,"cache_read_input_tokens":2000,"cache_creation_input_tokens":800}}
    """.trimIndent()

    private fun toolUse(id: String, name: String, input: String) = """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""

    @Test fun claudeSonnetAgentLoopEndToEnd() = runBlocking {
        http.enqueue(200, claudeMsg("claude-sonnet-5-5",
            """[{"type":"thinking","thinking":"","signature":"sig-abc"},""" + toolUse("toolu_1", "add_heading", """{"text":"Título","level":1}""") + "," +
                toolUse("toolu_2", "add_table", """{"rows":1,"cols":1,"cells":[{"r":0,"c":0,"text":"x"},{"r":0,"c":0,"text":"y"}]}""") + "]", "tool_use"))
        http.enqueue(200, claudeMsg("claude-sonnet-5-5", "[" + toolUse("toolu_3", "finish_document", """{"formats":["docx"],"filename":"doc"}""") + "]", "tool_use"))
        val client = ClaudeDocClient("sk-ant-test", DocGenModel.CLAUDE_SONNET, http.base)
        var finished: GenDocument? = null
        val out = DocGenEngine(
            client, pages(1), DocGenRequest(DocGenMode.QUALITY, setOf(OutputFormat.DOCX), "doc", "Doc"),
            finisher = { d, r -> finished = d; listOf(OutputFile(OutputFormat.DOCX, "/x", 1)) },
            sleep = {},
        ).run()
        client.close()
        assertEquals(2, http.requests.size)
        val seen = http.requests[0]
        assertEquals("/v1/messages", seen.path)
        assertEquals("sk-ant-test", seen.headers["x-api-key"])
        assertTrue(seen.headers["anthropic-beta"].orEmpty(), seen.headers["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))
        val b0 = body(0)
        assertEquals("claude-sonnet-5-5", b0["model"]!!.jsonPrimitive.content)
        assertEquals("default", b0["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("auto", b0["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("adaptive", b0["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", b0["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", b0["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(b0["temperature"])
        val tools = b0["tools"]!!.jsonArray
        assertEquals(DocTools.ALL.map { it.name }, tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        tools.forEach { t ->
            assertEquals("true", t.jsonObject["strict"]!!.jsonPrimitive.content)
            assertEquals("false", t.jsonObject["input_schema"]!!.jsonObject["additionalProperties"]!!.jsonPrimitive.content)
        }
        val firstMsg = b0["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("image", firstMsg.first { it.jsonObject["type"]!!.jsonPrimitive.content == "image" }.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(firstMsg.any { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("[50,50,950,80] Texto 1") == true })
        // Segundo turno: historial append-only con el pensamiento intacto y los resultados de AMBAS herramientas.
        val msgs = body(1)["messages"]!!.jsonArray
        assertEquals(3, msgs.size)
        val assistant = msgs[1].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", assistant[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig-abc", assistant[0].jsonObject["signature"]!!.jsonPrimitive.content)
        val results = msgs[2].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("toolu_1", "toolu_2"), results.map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content })
        assertEquals("true", results[1].jsonObject["is_error"]!!.jsonPrimitive.content) // celda solapada rechazada
        assertEquals(listOf("Título"), finished!!.blocks.map { it.plainText() })
        // Uso: creación de caché a 1,25×.
        assertEquals(CloudUsageTokens(2 * (3000 + 1000), 800, 4000), out.usage)
        assertEquals(setOf("claude-sonnet-5-5"), out.servedModels)
    }

    @Test fun claudeHaikuFastStructuredAndRefusal() = runBlocking {
        val page = """{"blocks":[{"type":"paragraph","text":"Hola"}]}"""
        http.enqueue(200, claudeMsg("claude-haiku-5-5", """[{"type":"text","text":${JsonPrimitive(page)}}]""", "end_turn"))
        val c = ClaudeDocClient("k", DocGenModel.CLAUDE_HAIKU, http.base)
        val r = c.structured(DocPrompts.FAST_SYSTEM, DocPrompts.fastUser(pages(1)[0], 1, "Doc"), DocPrompts.FAST_SCHEMA)
        assertEquals(page, r.text)
        val b = body(0)
        assertNull("Haiku sin respaldo del servidor", b["fallbacks"])
        assertNull(http.requests[0].headers["anthropic-beta"])
        assertEquals("disabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("low", b["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", b["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(DocPrompts.FAST_SCHEMA, b["output_config"]!!.jsonObject["format"]!!.jsonObject["schema"])

        http.enqueue(200, claudeMsg("claude-haiku-5-5", "[]", "refusal", """{"type":"refusal","category":"general_harms","explanation":"no"}"""))
        try { c.structured("s", listOf(UserPart.Text("x")), DocPrompts.FAST_SCHEMA); fail() } catch (e: CloudException.Refused) { assertTrue(e.message!!.contains("general_harms")) }
        c.close()
    }

    @Test fun claudeRetriesWithoutSchemaOn400AndMapsErrors() = runBlocking {
        val c = ClaudeDocClient("k", DocGenModel.CLAUDE_HAIKU, http.base)
        http.enqueue(400, """{"type":"error","error":{"type":"invalid_request_error","message":"output_config.format: schema is too complex"}}""")
        http.enqueue(200, claudeMsg("claude-haiku-5-5", """[{"type":"text","text":"{\"blocks\":[]}"}]""", "end_turn"))
        c.structured("s", listOf(UserPart.Text("x")), DocPrompts.FAST_SCHEMA)
        assertNull(body(1)["output_config"]!!.jsonObject["format"])
        assertTrue(body(1)["system"]!!.jsonPrimitive.content.contains("JSON shape example"))
        http.enqueue(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        try { c.structured("s", listOf(UserPart.Text("x")), DocPrompts.FAST_SCHEMA); fail() } catch (_: CloudException.Auth) {}
        http.enqueue(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow"}}""", mapOf("retry-after" to "3"))
        try { c.structured("s", listOf(UserPart.Text("x")), DocPrompts.FAST_SCHEMA); fail() } catch (e: CloudException.RateLimited) { assertEquals(3000L, e.retryAfterMs) }
        c.close()
    }

    // ------------------------------------------------------------------ DeepSeek

    private fun dsMsg(content: String?, toolCalls: String?, finish: String) = """
        {"id":"x","object":"chat.completion","model":"deepseek-flash","choices":[{"index":0,
          "message":{"role":"assistant","content":${content?.let { JsonPrimitive(it).toString() } ?: "null"}${toolCalls?.let { ",\"tool_calls\":$it" } ?: ""}},
          "finish_reason":"$finish"}],
         "usage":{"prompt_tokens":5000,"completion_tokens":300,"prompt_cache_hit_tokens":4000,"prompt_cache_miss_tokens":1000}}
    """.trimIndent()

    private fun dsCall(id: String, name: String, args: String) =
        """{"id":"$id","type":"function","function":{"name":"$name","arguments":${JsonPrimitive(args)}}}"""

    @Test fun deepSeekToolCallsLoopEndToEnd() = runBlocking {
        http.enqueue(200, dsMsg(null, "[" + dsCall("call_1", "add_list", """{"items":[{"text":"uno"},{"text":"dos"}],"ordered":false}""") + "," +
            dsCall("call_2", "add_paragraph", """{"text": "roto""") + "]", "tool_calls"))
        http.enqueue(200, dsMsg("Hecho.", null, "stop"))
        http.enqueue(200, dsMsg(null, "[" + dsCall("call_3", "finish_document", """{"formats":["pdf"],"filename":"d"}""") + "]", "tool_calls"))
        val c = DeepSeekDocClient("sk-test", http.base)
        var doc: GenDocument? = null
        val out = DocGenEngine(
            c, pages(1), DocGenRequest(DocGenMode.QUALITY, setOf(OutputFormat.PDF), "d", "D"),
            finisher = { d, _ -> doc = d; emptyList() }, sleep = {},
        ).run()
        assertEquals(3, http.requests.size)
        assertEquals("/chat/completions", http.requests[0].path)
        assertEquals("Bearer sk-test", http.requests[0].headers["authorization"])
        val b0 = body(0)
        assertEquals("deepseek-flash", b0["model"]!!.jsonPrimitive.content)
        assertEquals("auto", b0["tool_choice"]!!.jsonPrimitive.content)
        assertEquals("disabled", b0["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val t0 = b0["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", t0["type"]!!.jsonPrimitive.content)
        assertEquals("set_page", t0["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val user = b0["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        val img = user.first { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }.jsonObject["image_url"]!!.jsonObject
        assertTrue(img["url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,AQID"))
        // 2.º turno: mensaje del asistente con tool_calls + un mensaje role=tool por llamada.
        val m1 = body(1)["messages"]!!.jsonArray
        assertEquals("assistant", m1[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(2, m1[2].jsonObject["tool_calls"]!!.jsonArray.size)
        assertFalse("sin content null", m1[2].jsonObject.containsKey("content"))
        assertEquals(listOf("call_1", "call_2"), m1.drop(3).map { it.jsonObject["tool_call_id"]!!.jsonPrimitive.content })
        assertTrue(m1[4].jsonObject["content"]!!.jsonPrimitive.content.contains("INVALID_JSON"))
        // 3.er turno: recordatorio tras "Hecho." sin finish_document.
        val m2 = body(2)["messages"]!!.jsonArray
        assertTrue(m2.last().jsonObject["content"]!!.jsonPrimitive.content.contains("finish_document"))
        assertEquals("uno\ndos", doc!!.plainText)
        assertEquals(CloudUsageTokens(3000, 900, 12000), out.usage)
        c.close()
    }

    @Test fun deepSeekFastJsonObjectAndErrors() = runBlocking {
        http.enqueue(200, dsMsg("""{"blocks":[{"type":"heading","text":"Hola","level":2}]}""", null, "stop"))
        val c = DeepSeekDocClient("k", http.base)
        val r = c.structured(DocPrompts.FAST_SYSTEM, listOf(UserPart.Text("x")), DocPrompts.FAST_SCHEMA)
        assertTrue(r.text.contains("Hola"))
        val b = body(0)
        assertEquals("json_object", b["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("JSON shape example"))
        http.enqueue(200, dsMsg("", null, "content_filter"))
        try { c.structured("s", listOf(UserPart.Text("x")), DocPrompts.FAST_SCHEMA); fail() } catch (_: CloudException.Refused) {}
        http.enqueue(402, """{"error":{"message":"Insufficient Balance"}}""")
        try { c.structured("s", listOf(UserPart.Text("x")), DocPrompts.FAST_SCHEMA); fail() } catch (_: CloudException.Billing) {}
        c.close()
    }
}
