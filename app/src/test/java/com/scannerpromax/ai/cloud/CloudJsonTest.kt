package com.scannerpromax.ai.cloud

import com.scannerpromax.domain.BlockType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File

class CloudJsonTest {

    /** Respuesta tipo Claude (structured outputs: todos los campos, nulls explícitos). */
    private val claude = """
        {"doc_type":"receipt","blocks":[
         {"type":"title","bbox":[100,40,900,80],"lines":[{"text":"SUPERMERCADO EL ÁLAMO","bbox":[100,40,900,80]}],
          "style":{"bold":true,"italic":false,"align":"center","size":"large","color":"#000000"},"table":null,"chart":null,"latex":null},
         {"type":"table","bbox":[80,100,920,400],"lines":[],"style":null,
          "table":{"rows":3,"cols":2,"cells":[{"row":0,"col":0,"row_span":1,"col_span":2,"text":"Producto"},
            {"row":1,"col":0,"row_span":1,"col_span":1,"text":"Pan"},{"row":1,"col":1,"row_span":1,"col_span":1,"text":"1,20 €"},
            {"row":2,"col":0,"row_span":1,"col_span":1,"text":"Leche"},{"row":2,"col":1,"row_span":1,"col_span":1,"text":"0,95 €"}]},
          "chart":null,"latex":null},
         {"type":"handwriting","bbox":[100,450,600,520],"lines":[{"text":"pagado ✓","bbox":[100,450,600,520]}],
          "style":{"bold":false,"italic":false,"align":"left","size":"normal","color":"#1F3FBF"},"table":null,"chart":null,"latex":null},
         {"type":"formula","bbox":[100,600,500,650],"lines":[],"style":null,"table":null,"chart":null,"latex":"$${'$'}E=mc^2$${'$'}"},
         {"type":"figure","bbox":[600,600,900,900],"lines":[],"style":null,"table":null,"chart":null,"latex":null}
        ]}
    """.trimIndent()

    @Test fun parsesStructuredClaudeResponse() {
        val p = CloudJson.parse(claude)
        assertEquals("receipt", p.docType)
        assertFalse(p.repaired)
        assertEquals(listOf(BlockType.TITLE, BlockType.TABLE, BlockType.HANDWRITING, BlockType.FORMULA, BlockType.FIGURE), p.blocks.map { it.type })
        val title = p.blocks[0]
        assertEquals("SUPERMERCADO EL ÁLAMO", title.lines.single().text)
        assertTrue(title.style!!.bold)
        assertEquals("center", title.style!!.align)
        assertNull("negro = color por defecto", title.color)
        val t = p.blocks[1]
        assertEquals(3, t.tableRows); assertEquals(2, t.tableCols)
        assertEquals(2, t.cells.first().colSpan)
        assertEquals(listOf("Pan", "1,20 €"), t.tableRowsText()[1])
        assertEquals(0xFF1F3FBF.toInt(), p.blocks[2].color)
        assertEquals("E=mc^2", p.blocks[3].latex)
        assertNotNull(p.blocks[4].bbox)
        assertTrue(p.text.contains("Leche\t0,95 €"))
    }

    @Test fun toleratesFencesProseAndTrailingCommas() {
        val raw = "Here is the JSON:\n```json\n{\"doc_type\":\"printed\",\"blocks\":[{\"type\":\"text\",\"bbox\":[1,2,300,40],\"lines\":[{\"text\":\"Hola\",\"bbox\":[1,2,300,40]},],},]}\n```\nDone."
        val p = CloudJson.parse(raw)
        assertEquals("Hola", p.blocks.single().lines.single().text)
        assertFalse(p.repaired)
    }

    @Test fun repairsTruncatedJson() {
        val full = """{"doc_type":"printed","blocks":[{"type":"text","bbox":[10,10,500,60],"lines":[{"text":"Primera línea","bbox":[10,10,500,30]},{"text":"Segunda","bbox":[10,35,500,60]}]},{"type":"text","bbox":[10,100,500,140],"lines":[{"text":"Tercera línea cortada a mit"""
        val p = CloudJson.parse(full)
        assertTrue(p.repaired)
        assertEquals(listOf("Primera línea", "Segunda"), p.blocks[0].lines.map { it.text })
        // El segundo bloque se cierra en su último elemento completo (sin líneas -> se descarta por vacío).
        assertEquals(1, p.blocks.size)
    }

    @Test fun repairsCutInsideKeyOrNumber() {
        for (cut in listOf(
            """{"blocks":[{"type":"text","lines":[{"text":"A","bbox":[1,1,9,9]}]},{"type":"te""",
            """{"blocks":[{"type":"text","lines":[{"text":"A","bbox":[1,1,9,9]}]},{"type":"text","bbox":[1,2,3""",
            """{"blocks":[{"type":"text","lines":[{"text":"A","bbox":[1,1,9,9]}]},{"type":"text","lines":[{"text":"B\""",
        )) {
            val p = CloudJson.parse(cut)
            assertTrue(cut, p.repaired)
            assertEquals(cut, "A", p.blocks.first().lines.single().text)
        }
    }

    @Test fun noJsonThrows() {
        try { CloudJson.parse("Lo siento, no puedo ayudar con eso."); fail() } catch (_: CloudJson.ParseException) {}
        try { CloudJson.parse("{\"blocks\": ["); fail() } catch (_: CloudJson.ParseException) {}
    }

    @Test fun normalizesBoxesFractionsPixelsAndOrder() {
        val p = CloudJson.parse(
            """{"blocks":[
              {"type":"text","bbox":[0.1,0.2,0.5,0.3],"lines":[{"text":"frac"}]},
              {"type":"text","bbox":[1600,400,200,100],"lines":[{"text":"px"}]},
              {"type":"text","bbox":["10","20","30","40"],"lines":[{"text":"str"}]},
              {"type":"text","bbox":[5,5,5,90],"lines":[{"text":"degenerada"}]}
            ]}""",
            sentW = 2000, sentH = 1000,
        )
        assertEquals(listOf(100f, 200f, 500f, 300f), p.blocks[0].bbox!!.toList())
        assertEquals(listOf(100f, 100f, 800f, 400f), p.blocks[1].bbox!!.toList())
        assertEquals(listOf(10f, 20f, 30f, 40f), p.blocks[2].bbox!!.toList())
        assertNull(p.blocks[3].bbox)
    }

    @Test fun unknownTypesAndTextVariants() {
        val p = CloudJson.parse(
            """{"document_type":"Invoice","blocks":[
              {"type":"Section-Header","text":"Capítulo 1","bbox":[0,0,100,10]},
              {"type":"paragraph","text":"línea uno\nlínea dos"},
              {"type":"signature","bbox":[500,900,700,950]},
              {"type":"table","lines":[{"text":"sin celdas"}]},
              {"type":"chart","bbox":[1,1,50,50]},
              {"type":"text","lines":[]}
            ]}""",
        )
        assertEquals("receipt", p.docType)
        assertEquals(listOf(BlockType.TITLE, BlockType.TEXT, BlockType.FIGURE, BlockType.TEXT, BlockType.FIGURE), p.blocks.map { it.type })
        assertEquals(listOf("línea uno", "línea dos"), p.blocks[1].lines.map { it.text })
        assertEquals("caja de la única línea = la del bloque", listOf(0f, 0f, 100f, 10f), p.blocks[0].lines[0].bbox!!.toList())
    }

    @Test fun tableCellsAreSanitized() {
        val p = CloudJson.parse(
            """{"blocks":[{"type":"table","bbox":[0,0,1000,500],"cells":[
              {"row":0,"col":0,"rowspan":1,"colspan":3,"text":"Cabecera"},
              {"row":0,"col":1,"text":"pisada"},
              {"row":1,"col":0,"row_span":5,"col_span":1,"text":"A"},
              {"row":1,"col":1,"text":"B"},{"row":1,"col":2,"text":"C"},
              {"row":-1,"col":0,"text":"fuera"}
            ]}]}""",
        )
        val t = p.blocks.single()
        assertEquals(BlockType.TABLE, t.type)
        assertEquals(2, t.tableRows); assertEquals(3, t.tableCols)
        assertEquals(listOf("Cabecera", "A", "B", "C"), t.cells.map { it.text })
        assertEquals(1, t.cells[1].rowSpan) // recortado a la rejilla
    }

    @Test fun chartDataVariant() {
        val p = CloudJson.parse("""{"blocks":[{"type":"chart","bbox":[0,0,500,500],"chart_data":[["Mes","Ventas"],["Ene","10"],["Feb","12","extra"]]}]}""")
        val c = p.blocks.single()
        assertEquals(BlockType.CHART, c.type)
        assertEquals(listOf("Mes", "Ventas", ""), c.chart[0])
        assertEquals(3, c.chart.size)
    }

    /** Salidas REALES de Haiku del banco (recon2/haiku); no se copian al repo: se leen si existen. */
    @Test fun parsesRealHaikuBenchOutputs() {
        val dir = File("/tmp/claude-0/bench/recon2/haiku")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        Assume.assumeTrue("sin salidas del banco", files.isNotEmpty())
        for (f in files) {
            val raw = f.readText()
            val p = CloudJson.parse(raw)
            val src = Json.parseToJsonElement(raw) as JsonObject
            val srcBlocks = (src["blocks"] as JsonArray).map { it as JsonObject }
            assertNotNull(f.name, p.docType)
            assertTrue(f.name, p.blocks.size >= srcBlocks.count { hasContent(it) } - 1)
            assertTrue(f.name, p.text.length > 20)
            // Las tablas con celdas siguen siendo tablas y conservan todas las celdas.
            val srcCells = srcBlocks.sumOf { (it["cells"] as? JsonArray)?.size ?: 0 }
            if (srcCells > 0) assertTrue(f.name, p.blocks.sumOf { it.cells.size } >= srcCells * 9 / 10)
            // Truncado artificial a la mitad: se repara y no lanza.
            val half = CloudJson.parse(raw.substring(0, raw.length / 2))
            assertTrue(f.name, half.repaired)
        }
    }

    private fun hasContent(b: JsonObject): Boolean {
        fun nonEmpty(e: JsonElement?) = (e as? JsonArray)?.isNotEmpty() == true || (e as? JsonPrimitive)?.content?.isNotBlank() == true && e !is kotlinx.serialization.json.JsonNull
        return nonEmpty(b["lines"]) || nonEmpty(b["text"]) || nonEmpty(b["cells"]) || (b["type"] as? JsonPrimitive)?.content == "figure"
    }
}
