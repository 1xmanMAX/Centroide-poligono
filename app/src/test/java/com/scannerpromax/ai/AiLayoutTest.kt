package com.scannerpromax.ai

import com.scannerpromax.ai.parse.IaParse
import com.scannerpromax.ai.parse.IaParseTest
import com.scannerpromax.data.PageFiles
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ocr.TextAligner
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLayoutTest {

    @Test fun spottingRealToOcrInPixels() {
        val items = IaParse.parseSpotting(IaParseTest.sample("spotting_funsd13.txt"))
        val page = AiLayout.spotToPage(items, 1508, 2000)  // procesado al doble de la imagen enviada
        assertEquals(1508, page.ocr.imageWidth)
        val lines = page.ocr.blocks.flatMap { it.lines }
        assertEquals(42, lines.size)
        val first = lines.first { it.text == "NEW COMPETITIVE PRODUCTS" }
        assertEquals(403f * 1508 / 1000, first.box.left, 0.5f)
        assertEquals(97f * 2000 / 1000, first.box.top, 0.5f)
        for (l in lines) {
            // una caja por palabra (lo que espera la capa invisible del PDF) dentro de la línea
            assertEquals(TextAligner.tokenize(l.text).size, l.words.size)
            assertTrue(l.words.all { it.box.left >= l.box.left - 1 && it.box.right <= l.box.right + 1 })
        }
        assertTrue(page.ocr.text.startsWith("NEW COMPETITIVE PRODUCTS"))
        val layout = AiLayout.assemble(1508, 2000, page.blocks, emptyList(), "formulario", "paddleocr-vl-1.5")
        assertEquals(page.blocks.size, layout.blocks.size)
        assertEquals(layout.blocks.indices.toList(), layout.blocks.map { it.order })
        assertTrue(layout.blocks.all { it.lines.isNotEmpty() && it.lines.all { l -> l.quad.size == 8 } })
    }

    @Test fun tableRegionTakesItsLinesAndFormulaLinesAreRemoved() {
        fun line(t: String, x0: Float, y0: Float, x1: Float, y1: Float) = OcrLine(t, OcrRect(x0, y0, x1, y1))
        val text = listOf(
            AiLayout.TextBlockPx(OcrRect(10f, 10f, 500f, 30f), listOf(line("Título del informe", 10f, 10f, 500f, 30f)), listOf(null), 20f),
            AiLayout.TextBlockPx(OcrRect(10f, 100f, 500f, 160f), listOf(line("A 1", 10f, 100f, 200f, 120f), line("B 2", 10f, 140f, 200f, 160f)), listOf(null, null), 20f),
            AiLayout.TextBlockPx(OcrRect(10f, 300f, 500f, 320f), listOf(line("E = mc^2", 10f, 300f, 200f, 320f)), listOf(null), 20f),
        )
        val regions = listOf(
            LayoutBlock(type = BlockType.TABLE, box = LayoutBox(0f, 90f, 600f, 200f)),
            LayoutBlock(type = BlockType.FORMULA, box = LayoutBox(5f, 295f, 210f, 325f), latex = "E = mc^2"),
        )
        val l = AiLayout.assemble(600, 800, text, regions, null, "x")
        val table = l.blocks.single { it.type == BlockType.TABLE }
        assertEquals(listOf("A 1", "B 2"), table.lines.map { it.text })
        assertEquals("A 1\nB 2", table.text)
        assertEquals(1, l.blocks.count { it.type == BlockType.FORMULA })
        assertFalse(l.blocks.any { it.type != BlockType.FORMULA && it.text.contains("mc^2") })
        assertEquals(3, l.blocks.size)
    }

    @Test fun alignTextWithoutPositionsToMlKitLines() {
        val base = OcrResult(
            "Totl 12,0O\nGracias", 100, 100,
            listOf(OcrBlock("Totl 12,0O\nGracias", OcrRect(0f, 0f, 90f, 40f), listOf(
                OcrLine("Totl 12,0O", OcrRect(0f, 0f, 90f, 15f)),
                OcrLine("Gracias", OcrRect(0f, 20f, 60f, 35f)),
            ))),
        )
        val r = AiLayout.alignToLines(base, "Total 12,00\nGracias")!!
        assertEquals(listOf("Total 12,00", "Gracias"), r.blocks.flatMap { it.lines }.map { it.text })
        assertEquals(OcrRect(0f, 0f, 90f, 15f), r.blocks[0].lines[0].box)
        assertNull(AiLayout.alignToLines(null, "x"))
    }

    @Test fun formulaHeuristic() {
        assertTrue(AiLayout.looksLikeFormula("\\( \\frac{a}{b} \\)"))
        assertTrue(AiLayout.looksLikeFormula("x² + y² = r² ± 1"))
        assertFalse(AiLayout.looksLikeFormula("Total a pagar = 12,50 euros por persona"))
        assertFalse(AiLayout.looksLikeFormula("Hola"))
    }

    @Test fun garbage() {
        assertTrue(AiLayout.isGarbage(0, 200, false))
        assertTrue(AiLayout.isGarbage(30, 300, true))
        assertFalse(AiLayout.isGarbage(250, 300, true))
        assertFalse(AiLayout.isGarbage(2, 5, false))
    }

    @Test fun columnBandsAndMerge() {
        assertEquals(listOf(0 until 8), AiLayout.columnBands(8))
        val b = AiLayout.columnBands(25)
        assertEquals(0 until 10, b[0])
        assertEquals(25, b.sumOf { it.count() })
        val t1 = IaParse.parseOtsl("<fcel>Prod<fcel>A<fcel>B<nl><fcel>x<fcel>1<fcel>2<nl>")
        val t2 = IaParse.parseOtsl("<fcel>Prod<fcel>C<nl><fcel>x<fcel>3<nl>")
        val m = AiLayout.mergeBands(listOf(t1, t2))!!
        assertEquals(4, m.nCols)
        assertEquals(listOf("x", "1", "2", "3"), m.rows[1])
        assertEquals(listOf(0, 1, 2, 3), m.cells.filter { it.r == 0 }.map { it.c })
    }

    @Test fun figureRegionsIgnoreTextAndSmallSpecks() {
        val gw = 20; val gh = 30
        val content = BooleanArray(gw * gh)
        val text = BooleanArray(gw * gh)
        for (y in 2..5) for (x in 1..18) { content[y * gw + x] = true; text[y * gw + x] = true }  // párrafo
        for (y in 10..20) for (x in 3..15) content[y * gw + x] = true  // foto
        content[25 * gw + 2] = true  // mota
        val f = AiLayout.figureRegions(content, text, gw, gh, emptyList())
        assertEquals(1, f.size)
        assertEquals(AiLayout.IRect(3, 10, 16, 21), f[0])
        assertTrue(AiLayout.figureRegions(content, text, gw, gh, listOf(AiLayout.IRect(0, 9, 20, 22))).isEmpty())
    }

    @Test fun ruledTableDetectedNotebookGridIgnored() {
        val w = 600; val h = 800
        fun page(lineGray: Int, rows: List<Int>, cols: List<Int>, x0: Int, x1: Int, y0: Int, y1: Int): PageRegions {
            val g = ByteArray(w * h) { 250.toByte() }
            for (y in rows) for (x in x0..x1) for (t in 0..1) g[(y + t) * w + x] = lineGray.toByte()
            for (x in cols) for (y in y0..y1) for (t in 0..1) g[y * w + x + t] = lineGray.toByte()
            return PageRegions(w, h, g, ByteArray(w * h))
        }
        val rows = listOf(100, 150, 200, 250, 300)
        val cols = listOf(50, 200, 350, 500)
        val r = page(20, rows, cols, 50, 501, 100, 301).ruledRegions()
        assertEquals(1, r.size)
        assertEquals(PageRegions.Ruled.Kind.TABLE, r[0].kind)
        assertEquals(5, r[0].rows.size)
        assertEquals(4, r[0].cols.size)
        assertEquals(3, r[0].nCols)
        // cuadrícula de cuaderno en tono claro: no es tabla
        val lightRows = (20 until 780 step 25).toList(); val lightCols = (20 until 580 step 25).toList()
        assertTrue(page(190, lightRows, lightCols, 20, 579, 20, 779).ruledRegions().isEmpty())
        // aunque fuese oscura: papel cuadriculado regular por toda la página
        assertTrue(page(30, lightRows, lightCols, 20, 579, 20, 779).ruledRegions().none { it.kind == PageRegions.Ruled.Kind.TABLE })
    }

    @Test fun documentBoxForRescue() {
        val boxes = IaParse.parseLayoutJson("```json\n[{\"bbox_2d\": [0, 0, 1000, 1000], \"label\": \"document\"}, {\"bbox_2d\": [120, 80, 880, 930], \"label\": \"paper\"}]\n```")
        val b = AiRecognizer.pickDocumentBox(boxes)!!
        assertEquals(0.12f, b[0], 1e-4f); assertEquals(0.93f, b[3], 1e-4f)
        assertNull(AiRecognizer.pickDocumentBox(IaParse.parseLayoutJson("[{\"bbox_2d\": [0, 0, 1000, 1000], \"label\": \"x\"}]")))
        val roi = AiEnhance.roi(b, 1000, 2000)
        assertTrue(roi[0] < 120 && roi[2] > 880 && roi[1] < 160 && roi[3] > 1860)
    }

    @Test fun enhancementDecision() {
        val e = PageEdits(filter = FilterType.DEFAULT)
        assertEquals(FilterType.AUTO, AiEnhance.decide("receipt", ScanMode.DOCUMENT, e)!!.filter)
        assertEquals("whiteboard", AiEnhance.decide("whiteboard", ScanMode.DOCUMENT, e)!!.aiHint)
        assertNull(AiEnhance.decide("handwritten", ScanMode.DOCUMENT, e))  // no fiable
        assertNull(AiEnhance.decide("book", ScanMode.DOCUMENT, e))
        assertNull(AiEnhance.decide("receipt", ScanMode.RECEIPT, e))
        assertNull(AiEnhance.decide("receipt", ScanMode.DOCUMENT, e.copy(filter = FilterType.BLACK_WHITE)))
        assertNull(AiEnhance.decide("screen", ScanMode.DOCUMENT, e.copy(filter = FilterType.AUTO, aiHint = "screen")))
        assertEquals("manuscrito", AiEnhance.docTypeCode("handwritten"))
    }

    @Test fun aiHintDoesNotChangeHashOfSavedEdits() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        assertFalse(json.encodeToString(PageEdits.serializer(), PageEdits()).contains("aiHint"))
        val e = PageEdits()
        assertTrue(PageFiles.editsHash(e) != PageFiles.editsHash(e.copy(aiHint = "receipt")))
        assertEquals(PageFiles.processedName("p", e) + ".layout.json", PageFiles.layoutName("p", e))
        assertNotNull(PageFiles.layoutName("p", e))
    }

    @Test fun textGridTablesOnlyWhereThereAreColumns() {
        // Formulario FUNSD (pares etiqueta: valor) y texto corrido: ninguna tabla
        assertTrue(AiLayout.textGridTables(IaParse.parseSpotting(IaParseTest.sample("spotting_funsd13.txt"))).size <= 1)
        fun item(t: String, x0: Float, y0: Float, x1: Float) = IaParse.SpotItem(t, null, floatArrayOf(x0, y0, x1, y0 + 10))
        val rows = (0 until 6).flatMap { r ->
            val y = 100f + r * 15
            listOf(item("Producto $r", 20f, y, 150f), item("${r + 1}", 400f, y, 420f), item("${r * 3},50", 600f, y, 650f), item("${r * 7},00", 850f, y, 900f))
        }
        val para = (0 until 5).map { item("Línea de texto corrido número $it del párrafo", 20f, 300f + it * 15, 900f) }
        val t = AiLayout.textGridTables(rows + para)
        assertEquals(1, t.size)
        assertEquals(6, t[0].rows)
        assertEquals(4, t[0].colSeps.size - 1)
        assertEquals(100f, t[0].box[1])
    }
}
