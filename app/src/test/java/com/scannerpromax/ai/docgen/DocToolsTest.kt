package com.scannerpromax.ai.docgen

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocToolsTest {

    private fun ex(strict: Boolean = true, pages: Int = 2) = DocToolExecutor(pages, { p -> """{"page":$p}""" }, strict)

    @Test fun schemasAreStrictCompatible() {
        assertEquals(11, DocTools.ALL.size)
        fun walk(e: JsonElement, path: String) {
            when (e) {
                is JsonObject -> {
                    if ((e["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "object") {
                        assertEquals("$path: additionalProperties", "false", e["additionalProperties"]?.jsonPrimitive?.content)
                        assertNotNull("$path: required", e["required"])
                        val props = e["properties"]!!.jsonObject.keys
                        e["required"]!!.jsonArray.forEach { assertTrue("$path requiere ${it.jsonPrimitive.content}", it.jsonPrimitive.content in props) }
                    }
                    for (bad in listOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) if (!path.endsWith(".properties")) assertNull("$path: $bad no soportado", e[bad])
                    e.forEach { (k, v) -> walk(v, "$path.$k") }
                }
                is JsonArray -> e.forEach { walk(it, path) }
                else -> Unit
            }
        }
        DocTools.ALL.forEach { walk(it.schema, it.name) }
        walk(DocPrompts.FAST_SCHEMA, "fast")
        DocTools.ALL.forEach { assertTrue(it.name, it.name.matches(Regex("[a-z_]{1,64}"))) }
    }

    @Test fun buildsDocumentWithAllTools() {
        val e = ex()
        assertFalse(e.execute("set_page", """{"size":"LETTER","orientation":"landscape","margins_mm":{"top":10,"right":12,"bottom":10,"left":12}}""").isError)
        assertFalse(e.execute("set_header_footer", """{"header":"Acta nº 3","footer":"Confidencial","align":"right","page_numbers":"footer_center"}""").isError)
        assertFalse(e.execute("add_heading", """{"text":"Informe anual","level":1,"style":{"align":"center","size_pt":18}}""").isError)
        assertFalse(e.execute("add_paragraph", """{"text":"Hola **mundo** y *más*.","style":{"align":"justify","color":"#1f3a93"},"source_page":1}""").isError)
        assertFalse(e.execute("add_list", """{"items":[{"text":"uno"},{"text":"dos","level":1}],"ordered":true}""").isError)
        assertFalse(e.execute("add_table", """{"rows":2,"cols":3,"header_rows":1,"cells":[{"r":0,"c":0,"text":"A","bold":true},{"r":0,"c":1,"colspan":2,"text":"BC"},{"r":1,"c":0,"text":"1"},{"r":1,"c":1,"text":"2"},{"r":1,"c":2,"text":"3","align":"right","fill":"ffee00"}]}""").isError)
        assertFalse(e.execute("page_break", "{}").isError)
        assertFalse(e.execute("add_image", """{"page":2,"bbox":[100,200,900,600],"caption":"Figura 1"}""").isError)
        assertFalse(e.execute("add_formula", """{"latex":"E=mc^2","page":2,"bbox":[300,700,700,760]}""").isError)
        val d = e.document()
        assertEquals(Paper.LETTER, d.page.paper)
        assertTrue(d.page.landscape)
        assertEquals(12f, d.page.marginsMm.left)
        assertEquals("Acta nº 3", d.header!!.text)
        assertEquals(Align.RIGHT, d.footer!!.align)
        assertEquals(PageNumbers.FOOTER_CENTER, d.pageNumbers)
        assertEquals(7, d.blocks.size)
        val p = d.blocks[1] as DocBlock.Paragraph
        assertEquals("1F3A93", p.style.color)
        assertEquals(Align.JUSTIFY, p.style.align)
        val t = d.blocks[3] as DocBlock.Table
        assertEquals(2, t.cells[1].colSpan)
        assertEquals("FFEE00", t.cells.last().fill)
        assertEquals(Box1000(100, 200, 900, 600), (d.blocks[5] as DocBlock.Image).bbox)
        assertEquals(2, e.currentPage)
    }

    @Test fun strictRejectsBadInputWithClearErrors() {
        val e = ex()
        fun err(name: String, args: String): String = e.execute(name, args).also { assertTrue("$name $args", it.isError) }.content
        assertTrue(err("add_heading", """{"text":"x","level":9}""").contains("level"))
        assertTrue(err("add_heading", """{"level":1}""").contains("text"))
        assertTrue(err("add_paragraph", """{"text":"x","style":{"size_pt":300}}""").contains("size_pt"))
        assertTrue(err("add_paragraph", """{"text":"x","style":{"color":"azul"}}""").contains("color"))
        assertTrue(err("add_table", """{"rows":2,"cols":2,"cells":[{"r":0,"c":0,"colspan":2,"text":"a"},{"r":0,"c":1,"text":"b"}]}""").contains("overlaps"))
        assertTrue(err("add_table", """{"rows":2,"cols":2,"cells":[{"r":3,"c":0,"text":"a"}]}""").contains("outside"))
        assertTrue(err("add_table", """{"rows":500,"cols":2,"cells":[]}""").contains("rows"))
        assertTrue(err("add_image", """{"page":3,"bbox":[0,0,10,10]}""").contains("page"))
        assertTrue(err("add_image", """{"page":1,"bbox":[0,0,1200,10]}""").contains("0..1000"))
        assertTrue(err("add_image", """{"page":1,"bbox":[0,0,100]}""").contains("4 numbers"))
        assertTrue(err("add_image", """{"page":1,"bbox":[10,10,12,12]}""").contains("too small"))
        assertTrue(err("set_page", """{"size":"B5","orientation":"portrait"}""").contains("size"))
        assertTrue(err("finish_document", """{"formats":["docx"],"filename":"x"}""").contains("empty"))
        assertTrue(err("read_page_text", """{"page":0}""").contains("between 1 and 2"))
        assertTrue(err("add_paragraph", """{"text": "sin cerrar""").contains("INVALID_JSON"))
        assertTrue(err("draw_rainbow", "{}").contains("Unknown tool"))
        assertEquals(0, e.blockCount)
    }

    @Test fun toolNamesAreCaseTolerantAndBboxIn01IsScaled() {
        val e = ex()
        assertFalse(e.execute("Add_Paragraph", """{"text":"x"}""").isError)
        assertFalse(e.execute("add_image", """{"page":1,"bbox":[0.1,0.2,0.5,0.6]}""").isError)
        assertEquals(Box1000(100, 200, 500, 600), (e.document().blocks[1] as DocBlock.Image).bbox)
    }

    @Test fun lenientModeNormalizes() {
        val e = ex(strict = false)
        assertFalse(e.execute("add_heading", """{"text":"x","level":9}""").isError)
        assertEquals(6, (e.document().blocks[0] as DocBlock.Heading).level)
        assertFalse(e.execute("add_table", """{"rows":2,"cols":2,"cells":[{"r":0,"c":0,"colspan":3,"text":"a"},{"r":0,"c":1,"text":"b"},{"r":1,"c":1,"text":"c"}]}""").isError)
        val t = e.document().blocks[1] as DocBlock.Table
        assertEquals(2, t.cells.size) // (0,1) solapaba la combinación recortada a 2 columnas
        assertEquals(2, t.cells[0].colSpan)
        assertTrue(e.warnings.isNotEmpty())
        assertFalse(e.execute("add_paragraph", """{"text":""}""").isError) // se omite sin error
        assertEquals(2, e.blockCount)
    }

    @Test fun finishAndReadPageText() {
        val e = ex()
        assertEquals("""{"page":2}""", e.execute("read_page_text", """{"page":2}""").content)
        e.execute("add_paragraph", """{"text":"x"}""")
        val r = e.execute("finish_document", """{"formats":["pdf","docx","odt"],"filename":"Informe: final.pdf","title":"Informe"}""")
        assertFalse(r.isError)
        assertEquals(setOf(OutputFormat.PDF, OutputFormat.DOCX), r.finish!!.formats)
        assertEquals("Informe_ final", r.finish!!.filename)
        assertEquals("Informe", e.document().title)
    }

    @Test fun inlineRunsAndLatex() {
        val runs = InlineRuns.parse("Hola **negrita** y *cursiva* con __sub__ y 3 * 4 = 12 \\*literal")
        assertEquals("Hola negrita y cursiva con sub y 3 * 4 = 12 *literal", runs.joinToString("") { it.text })
        assertTrue(runs.first { it.text == "negrita" }.bold)
        assertTrue(runs.first { it.text == "cursiva" }.italic)
        assertTrue(runs.first { it.text == "sub" }.underline)
        assertEquals("Nota*", InlineRuns.strip("Nota*"))
        assertEquals("x = (-b ± √(b^(2)-4ac))/(2a)", LatexText.linear("""x = \frac{-b \pm \sqrt{b^{2}-4ac}}{2a}"""))
        assertEquals("H_(2)O", LatexText.linear("""\(\text{H}_{2}O\)"""))
    }

    @Test fun pageTextFromIndraOcr() {
        val ocr = OcrResult(
            "Hola\nmundo", 2000, 1000,
            listOf(OcrBlock("Hola mundo", OcrRect(0f, 0f, 2000f, 1000f), listOf(
                OcrLine("Hola", OcrRect(200f, 100f, 600f, 150f), confidence = 0.95f),
                OcrLine("mundo", OcrRect(200f, 200f, 800f, 250f), confidence = 0.3f),
            ))),
        )
        val lines = PageText.lines(ocr)
        assertEquals(Box1000(100, 100, 300, 150), lines[0].box)
        val p = PageInput(1, ByteArray(1), 1000, 500, lines)
        val compact = PageText.compact(p)
        assertTrue(compact, compact.startsWith("[100,100,300,150] Hola"))
        assertTrue(compact.contains("(?) mundo"))
        val js = Json.parseToJsonElement(PageText.json(p)).jsonObject
        assertEquals(2, js["lines"]!!.jsonArray.size)
        assertEquals("0.95", js["lines"]!!.jsonArray[0].jsonObject["conf"]!!.jsonPrimitive.content)
        assertTrue(PageText.compact(PageInput(2, ByteArray(1), 1, 1)).contains("no text"))
    }

    @Test fun fastPageAppliesBlocksAndRepairsTruncatedJson() {
        val e = ex(strict = false)
        val full = """```json
            {"title":"Factura","page":{"size":"A4","orientation":"portrait"},"footer":"Gracias","page_numbers":"footer_right",
             "blocks":[{"type":"heading","text":"Factura 12","level":1},{"type":"paragraph","text":"Cliente: Ana"},
               {"type":"table","rows":1,"cols":2,"cells":[{"r":0,"c":0,"text":"Total"},{"r":0,"c":1,"text":"10 €"}]},
               {"type":"image","bbox":[10,10,300,200]},{"type":"desconocido","text":"resto"}]}
            ```""".trimIndent()
        val (obj, repaired) = FastPage.parse(full)!!
        assertFalse(repaired)
        assertEquals(5, FastPage.apply(obj, e, page = 2, first = true))
        val d = e.document()
        assertEquals("Factura", d.title)
        assertEquals("Gracias", d.footer!!.text)
        assertEquals(PageNumbers.FOOTER_RIGHT, d.pageNumbers)
        assertEquals(2, (d.blocks[3] as DocBlock.Image).page)
        assertTrue(d.blocks[4] is DocBlock.Paragraph)
        val cut = """{"blocks":[{"type":"paragraph","text":"uno"},{"type":"paragraph","text":"dos"},{"type":"paragraph","text":"tr"""
        val (o2, rep2) = FastPage.parse(cut)!!
        assertTrue(rep2)
        assertEquals(2, (o2["blocks"] as JsonArray).size)
        assertNull(FastPage.parse("lo siento, no puedo"))
    }
}
