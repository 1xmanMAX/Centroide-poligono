package com.scannerpromax.ai.docgen

import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.CloudPricing
import com.scannerpromax.ai.cloud.CloudUsageTokens
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Proveedor falso: turnos guionizados para el bucle y respuestas JSON para el modo rápido. */
private class ScriptedClient(
    override val model: DocGenModel = DocGenModel.CLAUDE_SONNET,
    val turns: ArrayDeque<AgentTurn> = ArrayDeque(),
    val structuredReplies: ArrayDeque<() -> StructuredReply> = ArrayDeque(),
) : DocGenClient {
    override val maxLongSide = 1568
    override val maxPixels = 1568 * 1568
    val sessions = ArrayList<List<UserPart>>()
    val toolResults = ArrayList<List<ToolOutcome>>()
    val followUps = ArrayList<String>()
    val userTexts = ArrayList<String>()

    override fun startSession(system: String, tools: List<ToolSpec>, firstUser: List<UserPart>): AgentSession {
        sessions += firstUser
        return object : AgentSession {
            override suspend fun next(): AgentTurn = turns.removeFirstOrNull() ?: AgentTurn("", emptyList(), StopKind.END_TURN, u(10, 1), model.id)
            override fun addToolResults(results: List<ToolOutcome>, followUp: String?) { toolResults += results; followUp?.let { followUps += it } }
            override fun addUserText(text: String) { userTexts += text }
        }
    }

    override suspend fun structured(system: String, user: List<UserPart>, schema: JsonObject): StructuredReply =
        structuredReplies.removeFirstOrNull()?.invoke() ?: error("sin respuesta preparada")
}

private fun u(i: Long, o: Long, c: Long = 0) = CloudUsageTokens(i, o, c)
private fun call(id: String, name: String, args: String) = ToolCall(id, name, args)
private fun tools(vararg c: ToolCall) = AgentTurn("", c.toList(), StopKind.TOOL_USE, u(1000, 200, 500), "claude-sonnet-5-5")

class DocGenEngineTest {

    private fun pages(n: Int) = (1..n).map { PageInput(it, byteArrayOf(1, 2, 3), 1000, 1414, listOf(OcrLineRef("Línea de la página $it", Box1000(100, 100, 900, 130)))) }

    private class Finished { var doc: GenDocument? = null; var req: FinishRequest? = null; var calls = 0 }

    private fun engine(
        client: DocGenClient, n: Int, mode: DocGenMode, f: Finished,
        formats: Set<OutputFormat> = setOf(OutputFormat.DOCX, OutputFormat.PDF),
        limits: DocGenLimits = DocGenLimits(retryBackoffMs = longArrayOf(1, 1, 1)),
        progress: MutableList<DocGenProgress> = ArrayList(),
        costs: MutableList<Double> = ArrayList(),
    ) = DocGenEngine(
        client, pages(n), DocGenRequest(mode, formats, "Mi documento", "Mi documento"),
        finisher = { d, r -> f.doc = d; f.req = r; f.calls++; r.formats.map { OutputFile(it, "/tmp/x.${it.ext}", 10) } },
        onProgress = { progress += it },
        onUsage = { m, t -> CloudPricing.costForModel(m, t, Instant.parse("2026-10-25T12:00:00Z")).also { costs += it } },
        limits = limits,
        sleep = {},
    )

    @Test fun qualityLoopFixesRejectedTableAndFinishes() = runBlocking {
        val c = ScriptedClient()
        c.turns += tools(
            call("t1", "set_page", """{"size":"A4","orientation":"portrait"}"""),
            call("t2", "add_heading", """{"text":"Acta","level":1,"source_page":1}"""),
            call("t3", "add_table", """{"rows":1,"cols":2,"cells":[{"r":0,"c":0,"colspan":2,"text":"a"},{"r":0,"c":1,"text":"b"}]}"""),
        )
        c.turns += tools(
            call("t4", "read_page_text", """{"page":2}"""),
            call("t5", "add_table", """{"rows":1,"cols":2,"cells":[{"r":0,"c":0,"text":"a"},{"r":0,"c":1,"text":"b"}],"source_page":1}"""),
            call("t6", "page_break", "{}"),
            call("t7", "add_paragraph", """{"text":"Página dos","source_page":2}"""),
        )
        c.turns += tools(call("t8", "finish_document", """{"formats":["pdf"],"filename":"otro nombre"}"""))
        val f = Finished()
        val progress = ArrayList<DocGenProgress>(); val costs = ArrayList<Double>()
        val out = engine(c, 2, DocGenMode.QUALITY, f, progress = progress, costs = costs).run()
        assertEquals(1, c.sessions.size) // 2 páginas caben en una tanda
        val first = c.toolResults[0]
        assertEquals(listOf("t1", "t2", "t3"), first.map { it.id })
        assertTrue(first[2].isError && first[2].content.contains("overlaps"))
        assertTrue(c.toolResults[1][0].content.contains("\"page\":2")) // read_page_text
        assertEquals(2, c.toolResults.size) // tras finish_document no se manda nada más
        assertEquals(1, f.calls)
        assertEquals(setOf(OutputFormat.DOCX, OutputFormat.PDF), f.req!!.formats) // manda lo que eligió el usuario
        assertEquals("Mi documento", f.req!!.filename)
        val d = f.doc!!
        assertEquals(listOf("Acta", "a\tb", "", "Página dos"), d.blocks.map { it.plainText() })
        assertEquals(2, out.files.size)
        assertFalse(out.partial)
        assertEquals(CloudUsageTokens(3000, 600, 1500), out.usage)
        // Sonnet: 3 × (1000×2 + 500×0,2 + 200×10)/1e6
        assertEquals(3 * (1000 * 2.0 + 500 * 0.2 + 200 * 10.0) / 1e6, out.costUsd, 1e-9)
        assertEquals(1f, progress.last().fraction)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.fraction >= a.fraction })
        // Mensaje inicial: imagen + líneas del OCR de cada página.
        val parts = c.sessions[0]
        assertEquals(2, parts.count { it is UserPart.Image })
        assertTrue(parts.filterIsInstance<UserPart.Text>().any { it.text.contains("[100,100,900,130] Línea de la página 2") })
    }

    @Test fun nudgesWhenModelStopsWithoutFinishAndThenAutoFinishes() = runBlocking {
        val c = ScriptedClient()
        c.turns += tools(call("a", "add_paragraph", """{"text":"Hola"}"""))
        c.turns += AgentTurn("Listo, ya está.", emptyList(), StopKind.END_TURN, u(10, 5), "claude-sonnet-5-5")
        c.turns += AgentTurn("De verdad.", emptyList(), StopKind.END_TURN, u(10, 5), "claude-sonnet-5-5")
        c.turns += AgentTurn("Fin.", emptyList(), StopKind.END_TURN, u(10, 5), "claude-sonnet-5-5")
        val f = Finished()
        val out = engine(c, 1, DocGenMode.QUALITY, f).run()
        assertEquals(2, c.userTexts.size)
        assertTrue(c.userTexts[0].contains("finish_document"))
        assertEquals(1, f.calls)
        assertTrue(out.warnings.any { it.contains("sin finish_document") })
    }

    @Test fun chunksLongDocumentsIntoSessions() = runBlocking {
        val c = ScriptedClient()
        repeat(3) { i ->
            c.turns += tools(call("p$i", "add_paragraph", """{"text":"Tanda $i"}"""), call("f$i", "finish_document", """{"formats":["docx"],"filename":"x"}"""))
        }
        val f = Finished()
        engine(c, 9, DocGenMode.QUALITY, f).run()
        assertEquals(3, c.sessions.size) // 4 + 4 + 1 páginas
        val second = c.sessions[1].filterIsInstance<UserPart.Text>().first().text
        assertTrue(second, second.contains("Pages 1 to 4 were already added") && second.contains("Tanda 0"))
        assertEquals(4, c.sessions[1].count { it is UserPart.Image })
        assertEquals(1, f.calls)
        assertEquals(3, f.doc!!.blocks.size)
    }

    @Test fun iterationLimitStopsRunawayLoop() = runBlocking {
        val c = ScriptedClient()
        repeat(100) { c.turns += tools(call("r$it", "read_page_text", """{"page":1}""")) }
        c.turns.addFirst(tools(call("x", "add_paragraph", """{"text":"algo"}""")))
        val f = Finished()
        val out = engine(c, 1, DocGenMode.QUALITY, f, limits = DocGenLimits(baseIterations = 2, iterationsPerPage = 1, retryBackoffMs = longArrayOf())).run()
        assertTrue(out.partial)
        assertEquals(3, c.toolResults.size)
        assertEquals(1, f.calls)
    }

    @Test fun refusalAndAuthFailFastRateLimitRetries() = runBlocking {
        val refused = ScriptedClient().apply { turns += AgentTurn("", emptyList(), StopKind.REFUSAL, u(5, 0), "claude-sonnet-5-5", "cyber") }
        try { engine(refused, 1, DocGenMode.QUALITY, Finished()).run(); fail() } catch (e: CloudException.Refused) { assertTrue(e.message!!.contains("cyber")) }

        var calls = 0
        val flaky = object : DocGenClient by ScriptedClient(DocGenModel.CLAUDE_HAIKU) {
            override suspend fun structured(system: String, user: List<UserPart>, schema: JsonObject): StructuredReply {
                calls++
                if (calls < 3) throw CloudException.RateLimited(10, 429)
                return StructuredReply("""{"blocks":[{"type":"paragraph","text":"ok"}]}""", u(100, 10), false, "claude-haiku-5-5")
            }
        }
        val f = Finished()
        engine(flaky, 1, DocGenMode.FAST, f).run()
        assertEquals(3, calls)
        val auth = object : DocGenClient by ScriptedClient(DocGenModel.CLAUDE_HAIKU) {
            override suspend fun structured(system: String, user: List<UserPart>, schema: JsonObject): StructuredReply { calls++; throw CloudException.Auth(401) }
        }
        calls = 0
        try { engine(auth, 1, DocGenMode.FAST, Finished()).run(); fail() } catch (_: CloudException.Auth) {}
        assertEquals(1, calls)
    }

    @Test fun fastModeOneCallPerPageWithPageBreaksAndFallbacks() = runBlocking {
        val c = ScriptedClient(DocGenModel.DEEPSEEK_FLASH)
        c.structuredReplies += { StructuredReply("""{"title":"Examen","blocks":[{"type":"heading","text":"Examen","level":1},{"type":"list","ordered":true,"items":[{"text":"¿2+2?"}]}]}""", u(2000, 300), false, "deepseek-flash") }
        c.structuredReplies += { StructuredReply("no es json", u(2000, 10), false, "deepseek-flash") }
        c.structuredReplies += { StructuredReply("""{"blocks":[{"type":"paragraph","text":"Página 3 completa"},{"type":"paragraph","text":"cort""", u(2000, 16000), true, "deepseek-flash") }
        val f = Finished()
        val out = engine(c, 3, DocGenMode.FAST, f).run()
        val d = f.doc!!
        assertEquals("Mi documento", d.title) // el título del usuario manda
        assertEquals(listOf("Examen", "¿2+2?", "", "Línea de la página 2", "", "Página 3 completa"), d.blocks.map { it.plainText() })
        assertTrue(out.warnings.any { it.contains("página 2") })
        assertTrue(out.warnings.any { it.contains("página 3") && it.contains("cortó") })
    }

    @Test fun costEstimates() {
        val p = List(3) { DocGenCost.PageInfo(2480, 3508, 2500, 50) }
        val haiku = DocGenCost.estimate(DocGenModel.CLAUDE_HAIKU, DocGenMode.FAST, p)
        val sonnet = DocGenCost.estimate(DocGenModel.CLAUDE_SONNET, DocGenMode.QUALITY, p)
        val ds = DocGenCost.estimate(DocGenModel.DEEPSEEK_FLASH, DocGenMode.FAST, p, at = Instant.parse("2026-10-25T12:00:00Z"))
        assertTrue(haiku.low > 0 && haiku.low < 0.01) // fracciones de céntimo por página
        assertTrue(sonnet.low > haiku.low * 10)
        assertTrue(sonnet.low < 0.5)
        assertEquals(haiku.low * 1.7, haiku.high, 1e-12)
        assertTrue(ds.low > 0)
        assertEquals(1600L, DocGenCost.imageTokens(DocGenModel.CLAUDE_HAIKU, 2480, 3508))
        assertEquals(1024L, DocGenCost.imageTokens(DocGenModel.DEEPSEEK_FLASH, 2480, 3508))
        assertTrue(DocGenCost.formatRange(haiku).contains("céntimos"))
        // Precios por modelo (MTok): Sonnet $2/$10, Haiku $0,10/$0,50, DeepSeek punta ×2.
        val t = CloudUsageTokens(1_000_000, 1_000_000, 0)
        assertEquals(12.0, CloudPricing.costForModel("claude-sonnet-5-5", t, Instant.now()), 1e-9)
        assertEquals(0.06, CloudPricing.costForModel("claude-haiku-5-5", CloudUsageTokens(100_000, 100_000, 0), Instant.now()), 1e-9)
        assertEquals(3.0, CloudPricing.costForModel("claude-haiku-5-5", t, Instant.now()), 1e-9) // >100k de prompt: tarifa larga
        assertEquals(0.75, CloudPricing.costForModel("deepseek-flash", t, Instant.parse("2026-10-25T12:00:00Z")), 1e-9)
        assertEquals(1.50, CloudPricing.costForModel("deepseek-flash", t, Instant.parse("2026-10-26T07:00:00Z")), 1e-9)
    }
}
