package com.scannerpromax.ai.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Puerto de los 28 tests de /tmp/claude-0/bench/tools/test_ia_parse.py (salidas REALES de PaddleOCR-VL-1.5 /
 * Qwen3.5-0.8B con llama.cpp b11580) + pruebas sobre muestras crudas en src/test/resources/ia_samples y la
 * detección de bucles en streaming. Los valores esperados de las muestras se sacaron del parser de referencia.
 */
class IaParseTest {

    companion object {
        const val SPOT_REAL = "NEW COMPETITIVE PRODUCTS<|LOC_403|><|LOC_97|><|LOC_665|><|LOC_97|><|LOC_665|><|LOC_111|><|LOC_403|><|LOC_111|>\n" +
            "B &amp; W<|LOC_332|><|LOC_281|><|LOC_381|><|LOC_281|><|LOC_381|><|LOC_292|><|LOC_332|><|LOC_292|>\n" +
            "82837252<|LOC_894|><|LOC_793|><|LOC_916|><|LOC_793|><|LOC_914|><|LOC_876|><|LOC_892|><|LOC_876|></s>"
        const val OTSL_REAL = "<fcel>Asset Category<fcel>Fair Value Measurements as of December 31, 2019<lcel><lcel><lcel><lcel><nl>" +
            "<ucel><fcel>Level 1<fcel>Level 2<fcel>Level 3<fcel>Total<ecel><nl>" +
            "<fcel>Cash and cash equivalents<fcel>$ 20<fcel>$ —<fcel>$ —<fcel>$ —<fcel>20<nl></s>"
        const val OTSL_SYNTH = "<fcel>Producto<fcel>Cantidad<fcel>Precio<fcel>Total<nl><fcel>Manzanas<fcel>3<fcel>1,20<fcel>3,60<nl>" +
            "<fcel>Pan integral<fcel>1<fcel>2,10<fcel>2,10<nl></s>"
        const val CHART_BAR = "months | miles\nEnero | 12\nFebrero | 19\nMarzo | 7\nAbril | 15\nMayo | 22</s>"
        const val CHART_LINE = "| 2020 | 2021 | 2022 | 2023 | 2024\nNorte | 3.1 | 3.8 | 4.6 | 5.0 | 5.9\nSur | 2.0 | 2.4 | 2.2 | 3.1 | 3.5</s>"
        const val FORMULA_TAG = "\\[\\frac{\\partial\\Psi\\left(x,t\\right)}{\\partial t}=i\\frac{\\hbar}{2m}\\eqno(2)\\]</s>"
        const val FORMULA_QQ = "\\[\\Psi_{I}\\left(x,0\\right)=\\sin\\left(k x\\right)\\qquad(1)\\]</s>"
        const val QWEN_LAYOUT = "```json\n[\n\t{\"bbox_2d\": [10, 35, 987, 146], \"label\": \"title\"},\n\t{\"bbox_2d\": [10, 146, 987, 999], \"label\": \"text\"},\n" +
            "\t{\"bbox_2d\": [10, 35, 987, 146], \"label\": \"header\"},\n\t{\"bbox_2d\": [10, 146, 987, 999], \"label\": \"table\"},\n" +
            "\t{\"bbox_2d\": [10, 35, 987, 146], \"label\": \"header\"}\n]\n```"
        const val QWEN_CORNERS_DEGEN = "```json\n[\n\t{\"point_2d\": [10, 231], \"label\": \"top-left\"},\n\t{\"point_2d\": [963, 231], \"label\": \"top-right\"},\n" +
            "\t{\"point_2d\": [963, 231], \"label\": \"bottom-right\"},\n\t{\"point_2d\": [10, 231], \"label\": \"bottom-left\"}\n]\n```"

        fun sample(name: String): String =
            IaParseTest::class.java.classLoader!!.getResourceAsStream("ia_samples/$name")!!.bufferedReader().readText()
    }

    // ------------------------------------------------------------------ TestOCR (10)

    @Test fun loopUnderscores() {
        val t = "ACTION ...\n" + "_____\n".repeat(500)
        assertTrue(IaText.cleanOcr(t).split("_____").size - 1 <= 3)
    }

    @Test fun inlineLoop() {
        val t = "Total 12,00 " + "abc def ".repeat(50)
        assertTrue(IaText.stripRepetition(t).length < 60)
    }

    @Test fun shortPeriodLoop() {
        val t = "zhāpèdo. → Nàquercold.\n\n" + "1. ".repeat(400)
        assertTrue(IaText.cleanOcr(t).length < 40)
    }

    @Test fun counterRun() {
        val t = "- Comprar computadora\n\n" + (1 until 80).joinToString("\n\n")
        assertEquals("- Comprar computadora", IaText.cleanOcr(t))
        val t2 = "Impresão:\n\n" + (1 until 40).flatMap { a -> listOf("00", "30").map { b -> "${a}9.$b" } }.joinToString("\n")
        assertEquals("Impresão:", IaText.cleanOcr(t2))
    }

    @Test fun numberedLoop() {
        val t = "1. 1000\n" + (2 until 80).joinToString("\n") { "$it. 15" }
        val o = IaText.cleanOcr(t)
        assertTrue(o.split(". 15").size - 1 <= 3)
        assertTrue("1. 1000" in o)
    }

    @Test fun inlineCounter() {
        val t = "Hecho.\n" + (300 until 420).joinToString(" ") { "$it." }
        assertEquals("Hecho.", IaText.cleanOcr(t))
    }

    @Test fun shortNumericListKept() {
        val t = "Notas\n1\n2\n3\n4\nFin"
        assertEquals(t, IaText.cleanOcr(t))
    }

    @Test fun entitiesAndMarkdown() {
        assertEquals("Título\nB & W", IaText.cleanOcr("## Título\n**B &amp; W**"))
    }

    @Test fun thinkAndFence() {
        assertEquals("Hola", IaText.cleanOcr("<think>\n\n</think>\n\n```\nHola\n```"))
    }

    @Test fun otslInOcr() {
        assertTrue("Manzanas 3 1,20 3,60" in IaText.cleanOcr(OTSL_SYNTH))
    }

    // ------------------------------------------------------------------ TestSpotting (3)

    @Test fun spottingReal() {
        val it = IaParse.parseSpotting(SPOT_REAL)
        assertEquals(3, it.size)
        assertEquals("NEW COMPETITIVE PRODUCTS", it[0].text)
        assertEquals(listOf(listOf(403f, 97f), listOf(665f, 97f), listOf(665f, 111f), listOf(403f, 111f)), it[0].quad!!.map { p -> p.toList() })
        assertEquals("B & W", it[1].text)
        assertEquals(403f * 754 / 1000, it[0].quadPx(754, 1000)!![0][0], 1e-3f)
    }

    @Test fun spottingOddCounts() {
        val it = IaParse.parseSpotting("A<|LOC_1|><|LOC_2|><|LOC_3|><|LOC_4|>\nB<|LOC_5|><|LOC_6|>\nsuelto")
        assertEquals(listOf(listOf(1f, 2f), listOf(3f, 2f), listOf(3f, 4f), listOf(1f, 4f)), it[0].quad!!.map { p -> p.toList() })
        assertEquals(listOf(listOf(5f, 6f)), it[1].quad!!.map { p -> p.toList() })
        assertEquals("suelto", it[2].text)
        assertNull(it[2].quad)
    }

    @Test fun spottingClamp() {
        val it = IaParse.parseSpotting("X<|LOC_1200|><|LOC_0|><|LOC_5|><|LOC_0|><|LOC_5|><|LOC_9|><|LOC_1|><|LOC_9|>")
        assertEquals(1000f, it[0].quad!![0][0])
    }

    // ------------------------------------------------------------------ TestTable (4)

    @Test fun tableSynthExact() {
        val t = IaParse.parseOtsl(OTSL_SYNTH)
        assertEquals(listOf("Manzanas", "3", "1,20", "3,60"), t.rows[1])
        assertEquals(3 to 4, t.nRows to t.nCols)
    }

    @Test fun tableSpans() {
        val t = IaParse.parseOtsl(OTSL_REAL)
        val c0 = t.cells[0]; val c1 = t.cells[1]
        assertEquals(2 to 1, c0.rowSpan to c0.colSpan)
        assertEquals(5, c1.colSpan)
        assertEquals("Fair Value Measurements as of December 31, 2019", c1.text)
        assertTrue("colspan=\"5\"" in t.html)
    }

    @Test fun tableLcelLoop() {
        val t = IaParse.parseOtsl("<fcel>Titulo" + "<lcel>".repeat(3000))
        assertEquals(1, t.nRows)
        assertTrue(t.nCols <= 64)
    }

    @Test fun tableRaggedAndPre() {
        val t = IaParse.parseOtsl("Cab<fcel>B<nl><fcel>1<nl><fcel>2<fcel>3<fcel>4")
        assertEquals(3, t.nCols)
        assertEquals("Cab", t.rows[0][0])
        assertEquals(listOf("1", "", ""), t.rows[1])
    }

    // ------------------------------------------------------------------ TestChartFormula (5)

    @Test fun chartBar() {
        val c = IaParse.parseChart(CHART_BAR)
        assertEquals(listOf("months", "miles"), c.header)
        assertEquals(listOf("Mayo", "22"), c.rows[4])
    }

    @Test fun chartLineEmptyCorner() {
        val c = IaParse.parseChart(CHART_LINE)
        assertEquals("", c.header[0])
        assertEquals(6, c.header.size)
        assertEquals("Sur", c.rows[1][0])
    }

    @Test fun chartRepeated() {
        val c = IaParse.parseChart("Parameter | Value\n" + "Number of Bands | 4\n".repeat(100))
        assertEquals(1, c.rows.size)
    }

    @Test fun numbers() {
        assertEquals(1234.5, IaParse.toNumber("1.234,5")!!, 1e-9)
        assertEquals(1234.5, IaParse.toNumber("1,234.5")!!, 1e-9)
        assertEquals(40.0, IaParse.toNumber("40%")!!, 1e-9)
        assertEquals(-539.0, IaParse.toNumber("(539)")!!, 1e-9)
        assertEquals(20.0, IaParse.toNumber("$ 20")!!, 1e-9)
        assertEquals(3.6, IaParse.toNumber("3,60")!!, 1e-9)
        assertNull(IaParse.toNumber("—"))
    }

    @Test fun formula() {
        var f = IaParse.parseFormula(FORMULA_TAG)
        assertEquals("2", f.tag); assertTrue(f.latex.startsWith("\\frac"))
        f = IaParse.parseFormula(FORMULA_QQ)
        assertEquals("1", f.tag); assertFalse(f.latex.endsWith("(1)"))
        assertEquals("a^2", IaParse.parseFormula("$\$a^2$$").latex)
    }

    // ------------------------------------------------------------------ TestQwen (6)

    @Test fun layoutReal() {
        assertEquals(listOf("title", "text", "header", "table"), IaParse.parseLayoutJson(QWEN_LAYOUT).map { it.type })
    }

    @Test fun layoutTruncatedAndAliases() {
        val l = IaParse.parseLayoutJson("[{\"bbox_2d\": [0,0,500,100], \"label\": \"Section-header\"}, {\"bbox_2d\": [0,120,500,300], \"label\": \"Picture\"}, {\"bbox_2d\": [0,3")
        assertEquals(listOf("title", "figure"), l.map { it.type })
    }

    @Test fun layoutDegenerate() {
        assertTrue(IaParse.parseLayoutJson("[{\"bbox_2d\": [0, 134, 1000, 134], \"label\": \"Table\"}]").isEmpty())
    }

    @Test fun cornersLabels() {
        val t = "{\"top-left\": [12, 30], \"top-right\": [80, 28], \"bottom-right\": [83, 90], \"bottom-left\": [10, 92]}"
        assertEquals(listOf(listOf(12f, 30f), listOf(80f, 28f), listOf(83f, 90f), listOf(10f, 92f)),
            IaParse.cornersFromLabels(IaParse.parsePoints(t))!!.map { it.toList() })
    }

    @Test fun cornersDegenerate() {
        assertNull(IaParse.cornersFromLabels(IaParse.parsePoints(QWEN_CORNERS_DEGEN)))
    }

    @Test fun cornersUnordered() {
        val t = "[[900, 910], [120, 80], [100, 930], [880, 95]]"
        assertEquals(listOf(listOf(120f, 80f), listOf(880f, 95f), listOf(900f, 910f), listOf(100f, 930f)),
            IaParse.cornersFromLabels(IaParse.parsePoints(t))!!.map { it.toList() })
    }

    // ------------------------------------------------------------------ muestras crudas reales

    @Test fun ocrFunsd33LoopRealSample() {
        val raw = sample("ocr_funsd33_loop.txt")
        val c = IaText.cleanOcr(raw)
        assertEquals(12214, raw.length)
        assertTrue("longitud ${c.length}", c.length in 600..760)  // referencia Python: 681
        assertTrue(c.endsWith("ACTION PLAN: _____\n_____\n_____\n_____"))
        assertTrue(LoopGuard.isLoop(LoopGuard.Mode.TEXT, raw))
    }

    @Test fun spottingFunsd13RealSample() {
        val items = IaParse.parseSpotting(sample("spotting_funsd13.txt"))
        assertEquals(42, items.size)  // referencia Python: 42
        val blocks = IaParse.spottingToBlocks(items)
        assertEquals(22, blocks.size)  // referencia Python: 22
        assertEquals("NEW COMPETITIVE PRODUCTS", blocks[0].lines[0])
        assertEquals("BOBBY MILLS, REGIONAL SALES MGR., INDIANAPOLIS, IN", blocks[2].lines[0])
        assertFalse(LoopGuard.isLoop(LoopGuard.Mode.SPOTTING, sample("spotting_funsd13.txt")))
    }

    @Test fun tableRealSamples() {
        val t = IaParse.parseOtsl(sample("table_financial.txt"))
        assertEquals(7 to 5, t.nRows to t.nCols)
        assertEquals(listOf("Domestic", "$ 11,765", "$ 30,881", "$ 29,573", ""), t.rows[2])
        val loop = sample("table_loop_lcel.txt")
        val l = IaParse.parseOtsl(loop)
        assertEquals(1 to 64, l.nRows to l.nCols)
        assertTrue(IaParse.otslLoop(loop))
        assertFalse(IaParse.otslLoop(sample("table_financial.txt")))
    }

    @Test fun chartAndFormulaRealSamples() {
        val c = IaParse.parseChart(sample("chart_line.txt"))
        assertTrue(c.header.isNotEmpty() && c.rows.isNotEmpty())
        val junk = IaParse.parseChart(sample("chart_nochart_loop.txt"))
        assertEquals(listOf("Parameter", "Value"), junk.header)
        assertEquals(1, junk.rows.size)
        val f = IaParse.parseFormula(sample("formula_aligned.txt"))
        assertTrue(f.latex.startsWith("\\begin{aligned}"))
        assertNull(f.tag)
    }

    // ------------------------------------------------------------------ streaming

    @Test fun loopGuardAbortsInStreaming() {
        val g = LoopGuard(LoopGuard.Mode.TEXT, checkEvery = 8)
        var fed = 0
        g.feed("DIVISION: ")
        for (i in 0 until 2000) { fed++; if (!g.feed("_____\n")) break }
        assertTrue(g.looped)
        assertTrue("paró tarde: $fed", fed < 64)
    }

    @Test fun loopGuardSpottingCounter() {
        val sb = StringBuilder("Notas<|LOC_10|><|LOC_10|><|LOC_200|><|LOC_10|><|LOC_200|><|LOC_30|><|LOC_10|><|LOC_30|>\n")
        for (i in 1..40) sb.append("$i<|LOC_${i * 10}|><|LOC_500|><|LOC_${i * 10 + 8}|><|LOC_500|><|LOC_${i * 10 + 8}|><|LOC_520|><|LOC_${i * 10}|><|LOC_520|>\n")
        assertTrue(LoopGuard.isLoop(LoopGuard.Mode.SPOTTING, sb.toString()))
        val kept = IaParse.filterSpotLoops(IaParse.parseSpotting(sb.toString()))
        assertEquals(listOf("Notas"), kept.map { it.text })
    }

    @Test fun loopGuardNoFalsePositiveOnShortRepeats() {
        val t = "1 x Coca Cola 1,50\n1 x Coca Cola 1,50\n1 x Coca Cola 1,50\n1 x Coca Cola 1,50\nTOTAL 6,00"
        assertFalse(LoopGuard.isLoop(LoopGuard.Mode.TEXT, t))
        assertNotNull(IaText.cleanOcr(t))
    }

    @Test fun classes() {
        assertEquals("receipt", IaText.parseClass("receipt"))
        assertEquals("id_card", IaText.parseClass("ID card."))
        assertEquals("whiteboard", IaText.parseClass("<think>\n\n</think>\n\nBlackboard"))
        assertEquals("printed", IaText.parseClass("no sé"))
        assertEquals("no_document", IaText.parseClass("no_document"))
    }

    @Test fun mergeStripsRemovesOverlap() {
        val m = IaText.mergeStrips(listOf("Línea uno\nLínea dos\nLínea tres", "Línea tres\nLínea cuatro"))
        assertEquals("Línea uno\nLínea dos\nLínea tres\nLínea cuatro", m)
    }
}
