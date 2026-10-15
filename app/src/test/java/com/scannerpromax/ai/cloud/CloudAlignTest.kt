package com.scannerpromax.ai.cloud

import com.scannerpromax.ai.AiLayout
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CloudAlignTest {
    private val w = 2000
    private val h = 2800

    private fun line(text: String, l: Float, t: Float, r: Float, b: Float): OcrLine {
        val box = OcrRect(l, t, r, b)
        return OcrLine(text, box, AiLayout.wordsAlong(text, null, box))
    }

    /** ML Kit (posiciones exactas, texto con errores típicos). */
    private val mlkit = OcrResult(
        text = "", imageWidth = w, imageHeight = h,
        blocks = listOf(
            OcrBlock("", OcrRect(500f, 100f, 1500f, 160f), listOf(line("RESUMEN EJECUTlVO", 500f, 100f, 1500f, 160f))),
            OcrBlock("", OcrRect(200f, 200f, 1800f, 360f), listOf(
                line("El inforrne anual de la ernpresa", 200f, 200f, 1700f, 240f),
                line("muestra un crecimiento del 12%", 200f, 260f, 1650f, 300f),
                line("respecto al ano anterior.", 200f, 320f, 1200f, 360f),
            )),
            OcrBlock("", OcrRect(200f, 600f, 1300f, 660f), listOf(line("Revisar con Ana el lunes", 200f, 600f, 1300f, 660f))),
            OcrBlock("", OcrRect(300f, 950f, 1700f, 1250f), listOf(
                line("Producto Precio", 300f, 950f, 1700f, 1000f),
                line("Pan 1.20", 300f, 1050f, 1700f, 1100f),
                line("Leche 0,95", 300f, 1150f, 1700f, 1200f),
            )),
            OcrBlock("", OcrRect(800f, 2600f, 1200f, 2650f), listOf(line("Página 3 de 10", 800f, 2600f, 1200f, 2650f))),
        ),
    )

    /** Caja del modelo: desviada (x escalada 0,96 y +20; y +25 en 0..1000) como las de los LLM. */
    private fun mb(l: Float, t: Float, r: Float, b: Float): String {
        fun x(v: Float) = (v / w * 1000f * 0.96f + 20f).toInt()
        fun y(v: Float) = (v / h * 1000f + 25f).toInt()
        return "[${x(l)},${y(t)},${x(r)},${y(b)}]"
    }

    private val modelJson = """
    {"doc_type":"printed","blocks":[
     {"type":"title","bbox":${mb(500f, 100f, 1500f, 160f)},"lines":[{"text":"RESUMEN EJECUTIVO","bbox":${mb(500f, 100f, 1500f, 160f)}}],"style":{"bold":true,"italic":false,"align":"center","size":"large","color":"#000000"}},
     {"type":"text","bbox":${mb(200f, 200f, 1800f, 360f)},"lines":[
        {"text":"El informe anual de la empresa","bbox":${mb(200f, 200f, 1700f, 240f)}},
        {"text":"muestra un crecimiento del 12 %","bbox":${mb(200f, 260f, 1650f, 300f)}},
        {"text":"respecto al año anterior.","bbox":${mb(200f, 320f, 1200f, 360f)}}]},
     {"type":"handwriting","bbox":${mb(200f, 600f, 1300f, 760f)},"lines":[
        {"text":"Revisar con Ana el lunes","bbox":${mb(200f, 600f, 1300f, 660f)}},
        {"text":"llamar al 600 123 456","bbox":${mb(200f, 700f, 1100f, 760f)}}],"style":{"bold":false,"italic":false,"align":"left","size":"normal","color":"#1F3FBF"}},
     {"type":"table","bbox":${mb(280f, 930f, 1720f, 1270f)},"lines":[],"table":{"rows":3,"cols":2,"cells":[
        {"row":0,"col":0,"row_span":1,"col_span":1,"text":"Producto"},{"row":0,"col":1,"row_span":1,"col_span":1,"text":"Precio"},
        {"row":1,"col":0,"row_span":1,"col_span":1,"text":"Pan"},{"row":1,"col":1,"row_span":1,"col_span":1,"text":"1,20"},
        {"row":2,"col":0,"row_span":1,"col_span":1,"text":"Leche"},{"row":2,"col":1,"row_span":1,"col_span":1,"text":"0,95"}]}},
     {"type":"figure","bbox":${mb(1400f, 1500f, 1900f, 2000f)},"lines":[]}
    ]}
    """

    @Test fun cloudTextOnLocalPositions() {
        val page = CloudJson.parse(modelJson)
        val r = CloudAlign.align(page, w, h, mlkit, "impreso", "nube:test")
        val text = r.ocr.text
        assertTrue(text, text.contains("El informe anual de la empresa"))
        assertTrue(text.contains("RESUMEN EJECUTIVO"))
        assertTrue(!text.contains("inforrne") && !text.contains("EJECUTlVO"))

        // Líneas del párrafo: texto de la nube con las cajas EXACTAS de ML Kit.
        val para = r.ocr.blocks.first { it.text.startsWith("El informe") }
        assertEquals(3, para.lines.size)
        assertEquals(OcrRect(200f, 200f, 1700f, 240f), para.lines[0].box)
        assertEquals("respecto al año anterior.", para.lines[2].text)
        assertEquals("palabras con caja", 4, para.lines[2].words.size)

        // Manuscrito: la línea que ML Kit no vio conserva la caja del modelo CORREGIDA (calibración).
        val hw = r.layout.blocks.first { it.type == BlockType.HANDWRITING }
        val orphan = hw.lines.first { it.text.startsWith("llamar") }
        assertTrue("x0=${orphan.box.left}", abs(orphan.box.left - 200f) < 25f)
        assertTrue("y0=${orphan.box.top}", abs(orphan.box.top - 700f) < 25f)
        assertEquals(0xFF1F3FBF.toInt(), orphan.color)

        // Tabla: celdas de la nube + líneas de ML Kit con el texto de la nube.
        val table = r.layout.blocks.first { it.type == BlockType.TABLE }
        assertEquals(6, table.cells.size)
        assertEquals(listOf("Producto Precio", "Pan 1,20", "Leche 0,95"), table.lines.map { it.text })
        assertTrue(table.box.left <= 300f && table.box.bottom >= 1200f)

        // Texto que solo vio ML Kit (pie de página): no se pierde.
        val stray = r.layout.blocks.last { it.type == BlockType.TEXT }
        assertEquals("Página 3 de 10", stray.text)

        // Orden de lectura de la nube y estilos.
        assertEquals(BlockType.TITLE, r.layout.blocks.first().type)
        assertTrue(r.layout.blocks.first().style!!.bold)
        assertEquals((0 until r.layout.blocks.size).toList(), r.layout.blocks.map { it.order })
        assertTrue("mlMatched=${r.mlMatched}", r.mlMatched > 0.85f)
        assertEquals("impreso", r.layout.docType)
        assertNull(r.warning)
    }

    @Test fun badBoxesAreMatchedByText() {
        // Caja del párrafo totalmente desplazada (otra zona): se asigna por texto.
        val json = """{"blocks":[{"type":"text","bbox":[0,800,300,900],"lines":[
            {"text":"El informe anual de la empresa"},{"text":"muestra un crecimiento del 12 %"},{"text":"respecto al año anterior."}]}]}"""
        val r = CloudAlign.align(CloudJson.parse(json), w, h, mlkit, null, "t")
        val b = r.layout.blocks.first { it.text.startsWith("El informe") }
        assertEquals(3, b.lines.size)
        assertEquals("El informe anual de la empresa", b.lines[0].text)
        assertEquals(200f, b.lines[0].box.top, 0.01f)
    }

    @Test fun withoutMlKitUsesModelBoxes() {
        val r = CloudAlign.align(CloudJson.parse(modelJson), w, h, null, null, "t")
        assertNotNull(r.warning)
        val title = r.layout.blocks.first()
        assertEquals("RESUMEN EJECUTIVO", title.text)
        val expectedLeft = ((500f / w * 1000f * 0.96f + 20f).toInt()) * w / 1000f
        assertEquals(expectedLeft, title.box.left, 1f)
        val table = r.layout.blocks.first { it.type == BlockType.TABLE }
        assertEquals("filas sintéticas buscables", 3, table.lines.size)
        assertTrue(r.ocr.text.contains("Leche\t0,95"))
    }

    @Test fun localLayoutExtensionPoint() {
        val local = listOf(
            CloudAlign.LocalRegion(BlockType.TABLE, OcrRect(250f, 920f, 1750f, 1280f)),
            CloudAlign.LocalRegion(BlockType.FIGURE, OcrRect(1380f, 1490f, 1910f, 2010f)),
            CloudAlign.LocalRegion(BlockType.FIGURE, OcrRect(100f, 2100f, 600f, 2400f)), // que la nube no nombró
        )
        val r = CloudAlign.align(CloudJson.parse(modelJson), w, h, mlkit, null, "t", local)
        val table = r.layout.blocks.first { it.type == BlockType.TABLE }
        assertEquals(250f, table.box.left, 0.01f)
        assertEquals(1280f, table.box.bottom, 0.01f)
        val figs = r.layout.blocks.filter { it.type == BlockType.FIGURE }
        assertEquals(2, figs.size)
        assertEquals(1380f, figs[0].box.left, 0.01f)
        assertEquals(100f, figs[1].box.left, 0.01f)
    }

    /**
     * Salidas REALES de Haiku del banco (si existen): las líneas del propio modelo hacen de "ML Kit" (posición exacta,
     * texto con errores m->rn) y las cajas del modelo se desvían como las de un LLM. La alineación debe recuperar las
     * posiciones y conservar el texto de la nube.
     */
    @Test fun realHaikuOutputsAlignToLocalLines() {
        val files = java.io.File("/tmp/claude-0/bench/recon2/haiku").listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        org.junit.Assume.assumeTrue("sin salidas del banco", files.isNotEmpty())
        val W = 1700; val H = 2200
        for (f in files) {
            val clean = CloudJson.parse(f.readText())
            val lines = clean.blocks.flatMap { b -> b.lines.filter { it.bbox != null } }
            if (lines.size < 3) continue
            val ml = OcrResult("", W, H, lines.map { l ->
                val b = l.bbox!!
                val ln = line(l.text.replace("m", "rn"), b[0] * W / 1000f, b[1] * H / 1000f, b[2] * W / 1000f, b[3] * H / 1000f)
                OcrBlock(ln.text, ln.box, listOf(ln))
            })
            fun warp(b: FloatArray?) = b?.let { floatArrayOf(it[0] * 0.97f + 15f, it[1] + 18f, it[2] * 0.97f + 15f, it[3] + 18f) }
            val warped = clean.copy(blocks = clean.blocks.map { b -> b.copy(bbox = warp(b.bbox), lines = b.lines.map { it.copy(bbox = warp(it.bbox)) }) })
            val r = CloudAlign.align(warped, W, H, ml, null, "t")
            assertTrue("${f.name}: mlMatched=${r.mlMatched}", r.mlMatched >= 0.85f)
            val words = clean.blocks.flatMap { b -> b.lines.flatMap { com.scannerpromax.ocr.TextAligner.tokenize(it.text) } }
            val outWords = com.scannerpromax.ocr.TextAligner.tokenize(r.ocr.text).toHashSet()
            val kept = words.count { it in outWords }.toFloat() / words.size.coerceAtLeast(1)
            assertTrue("${f.name}: palabras de la nube conservadas=$kept", kept >= 0.9f)
            // Las líneas colocadas sobre ML Kit tienen su caja exacta (no la desviada del modelo).
            // (Las tablas sin líneas en la salida del modelo llevan filas sintéticas: fuera de la cuenta.)
            val mlBoxes = ml.blocks.map { com.scannerpromax.domain.LayoutBox(it.box.left, it.box.top, it.box.right, it.box.bottom) }.toHashSet()
            val textLines = r.layout.blocks.filter { it.type != BlockType.TABLE }.flatMap { it.lines }
            val placed = textLines.count { it.box in mlBoxes }.toFloat() / textLines.size.coerceAtLeast(1)
            assertTrue("${f.name}: líneas con caja local=$placed", placed >= 0.85f)
        }
    }

    @Test fun robustFitRejectsNonsense() {
        val pts = (0..10).map { i -> (i * 100f) to (i * 100f * 1.02f + 30f) }
        val f = CloudAlign.robustFit(pts, 1000)!!
        assertEquals(1.02f, f.first, 0.001f); assertEquals(30f, f.second, 0.5f)
        assertNull("sin dispersión", CloudAlign.robustFit(List(10) { 500f to 510f }, 1000))
        assertNull("escala absurda", CloudAlign.robustFit((0..10).map { i -> (i * 100f) to (i * 200f) }, 1000))
    }
}
