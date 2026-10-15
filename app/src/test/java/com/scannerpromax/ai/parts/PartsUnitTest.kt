package com.scannerpromax.ai.parts

import com.scannerpromax.domain.BlockStyle
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.LayoutLine
import com.scannerpromax.domain.TableCell
import com.scannerpromax.pdf.DocLayoutFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lógica pura de la tubería por partes (sin modelos): post-proceso, orientación, tinta, tubería con lector falso. */
class PartsUnitTest {

    private fun white(w: Int, h: Int) = Rgb(w, h, IntArray(w * h) { -1 })
    private fun Rgb.rect(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = argb }
    private val BLACK = 0xFF000000.toInt()

    // ------------------------------------------------------------------------------------------ PP-DocLayoutV3

    @Test
    fun paddleExportUmbralesYOrden() {
        // clase, score, x0, y0, x1, y1, orden
        val rows = floatArrayOf(
            22f, 0.9f, 10f, 100f, 500f, 200f, 5f,    // text (orden 5)
            6f, 0.5f, 10f, 10f, 500f, 50f, 1f,       // doc_title (orden 1)
            21f, 0.7f, 10f, 300f, 500f, 600f, 9f,    // table
            22f, 0.38f, 10f, 700f, 500f, 720f, 12f,  // text con 0,38 (umbral 0,4 → fuera)
            15f, 0.9f, 20f, 110f, 60f, 120f, 6f,     // inline_formula → fuera
            22f, 0.6f, 20f, 320f, 200f, 340f, 10f,   // texto dentro de la tabla → duplicado
            20f, 0.36f, 520f, 300f, 590f, 360f, 11f, // seal con 0,36 (umbral 0,35 → entra)
        )
        val dets = PpLayout.fromPaddle(rows, 7, 600, 800, 0.3f)
        assertEquals(7, dets.size)
        val regs = PpLayout.regions(dets)
        assertEquals(listOf("doc_title", "text", "table", "seal"), regs.map { it.label })
        assertEquals(BlockType.TITLE, regs[0].type)
        assertEquals(BlockType.TABLE, regs[2].type)
    }

    @Test
    fun exportTransformersVotosDeOrden() {
        // 2 consultas, 2 clases: q0 = clase 1 (0,9), q1 = clase 0 (0,8); q1 va antes que q0 según order_logits.
        val logits = floatArrayOf(-5f, 2.2f, 1.4f, -5f)
        val boxes = floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, 0.5f, 0.1f, 0.4f, 0.1f)
        // sc[i][j] = P(i antes que j): sc[0][1] bajo → q1 antes
        val order = floatArrayOf(0f, -4f, 4f, 0f)
        val d = PpLayout.fromLogits(logits, boxes, order, 2, 2, 1000, 1000, 0.5f)
        assertEquals(2, d.size)
        assertEquals("abstract", d[0].label)   // q1 (clase 0) primero
        assertEquals(300f, d[0].box[0], 0.5f); assertEquals(50f, d[0].box[1], 0.5f)
    }

    @Test
    fun preprocesoBicubico800() {
        val img = white(100, 50).also { it.rect(0, 0, 50, 50, BLACK) }
        val x = PpLayout.preprocess(img)
        assertEquals(3 * 800 * 800, x.size)
        assertEquals(0f, x[400 * 800 + 100], 0.01f)       // zona negra
        assertEquals(1f, x[400 * 800 + 700], 0.01f)       // zona blanca
    }

    // ------------------------------------------------------------------------------------------ orientación

    @Test
    fun orientacionVotoYGiro() {
        val img = white(400, 600)
        assertEquals(5, DocOrientation.voteCrops(400, 600).size)
        var n = 0
        val r = DocOrientation.predict(img) { chw -> assertEquals(3 * 224 * 224, chw.size); n++; floatArrayOf(0.1f, 0.7f, 0.1f, 0.1f) }
        assertEquals(5, n)
        assertEquals(90, r.angle)
        assertEquals(90, DocOrientation.correction(r))
        assertEquals(270, DocOrientation.clockwise(90))
        assertEquals(0, DocOrientation.correction(DocOrientation.Result(180, 0.3f)))
        assertEquals(0, DocOrientation.clockwise(0))
    }

    // ------------------------------------------------------------------------------------------ tinta

    @Test
    fun rayasFondosYColor() {
        val img = white(600, 800)
        img.rect(50, 100, 550, 102, BLACK)          // raya horizontal de formulario
        img.rect(300, 200, 302, 700, BLACK)         // raya vertical
        img.rect(60, 300, 260, 360, 0xFFCCE0FF.toInt())  // fondo azul claro
        img.rect(100, 500, 200, 520, 0xFF102070.toInt()) // "texto" azul oscuro
        val rules = PageInk.detectRules(img, emptyList())
        assertEquals(2, rules.size)
        assertTrue(rules.any { it[1] == it[3] && it[2] - it[0] > 450 })
        val fills = PageInk.detectFills(img)
        assertEquals(1, fills.size)
        assertEquals(60f, fills[0].box[0], 6f)
        val c = PageInk.inkColor(img, floatArrayOf(100f, 500f, 200f, 500f, 200f, 520f, 100f, 520f))
        assertNotNull(c)
        assertTrue((c!! and 0xFF) > ((c shr 16) and 0xFF))  // azul
        assertNull(PageInk.inkColor(img, floatArrayOf(50f, 99f, 550f, 99f, 550f, 103f, 50f, 103f)))  // negro
    }

    @Test
    fun dibujosSinTextoYSinBordes() {
        val img = white(800, 1000)
        // estrella/flecha suelta (trazos) en medio
        for (k in 0 until 60) { img.rect(400 + k, 500 + k / 2, 403 + k, 503 + k / 2, BLACK); img.rect(400 + k, 560 - k / 2, 403 + k, 563 - k / 2, BLACK) }
        // borde de madera pegado al canto (textura grande)
        for (y in 0 until 1000) for (x in 0 until 60) if ((x + y) % 3 != 0) img.px[y * 800 + x] = 0xFF704020.toInt()
        // texto (línea conocida): no debe salir como dibujo
        img.rect(200, 200, 600, 215, BLACK)
        val d = PageInk.drawings(img, listOf(floatArrayOf(200f, 200f, 600f, 215f)), emptyList())
        assertEquals(1, d.size)
        assertTrue(d[0][0] >= 380f && d[0][2] <= 480f)
        // recorte solo-tinta: el papel ligeramente gris pasa a blanco y la tinta se queda
        val crop = Rgb(10, 1, IntArray(10) { if (it == 5) BLACK else 0xFFE8E8E8.toInt() })
        PageInk.inkOnly(crop.px, 10, 1, 25, 232.0)
        assertEquals(-1, crop.px[0]); assertEquals(BLACK, crop.px[5])
    }

    @Test
    fun zonasDeTintaNoCubiertas() {
        val img = white(600, 800)
        img.rect(50, 50, 550, 70, BLACK)    // cubierta por una región
        for (k in 0 until 16) img.rect(80 + k * 20, 400, 92 + k * 20, 415, BLACK)  // "palabras" fuera de regiones
        val z = PageInk.uncoveredZones(img, listOf(floatArrayOf(40f, 40f, 560f, 80f)))
        assertEquals(1, z.size)
        assertTrue(z[0][1] > 380f && z[0][3] < 440f)
    }

    // ------------------------------------------------------------------------------------------ tubería

    /** Spotting con <|LOC_n|> (0..1000) de una línea en la caja [b] (0..1000 del recorte). */
    private fun spot(text: String, b: IntArray) = "$text<|LOC_${b[0]}|><|LOC_${b[1]}|><|LOC_${b[2]}|><|LOC_${b[1]}|><|LOC_${b[2]}|><|LOC_${b[3]}|><|LOC_${b[0]}|><|LOC_${b[3]}|>"

    private class FakeReader(val page: String?, val crop: (FloatArray) -> String, val tables: (List<PartsReader.Piece>) -> PartsReader.Out = { PartsReader.Out("", true) }) : PartsReader {
        val calls = ArrayList<String>()
        override suspend fun spotting(crop: FloatArray?, maxTokens: Int): PartsReader.Out {
            calls += if (crop == null) "pagina" else "recorte"
            return if (crop == null) PartsReader.Out(page ?: "", page == null) else PartsReader.Out(crop(crop), false)
        }
        override suspend fun table(pieces: List<PartsReader.Piece>) = tables(pieces).also { calls += "tabla${pieces.size}" }
        override suspend fun chart(crop: FloatArray) = PartsReader.Out("", false)
        override suspend fun formula(crop: FloatArray) = PartsReader.Out("\\frac{a}{b}", false).also { calls += "formula" }
    }

    @Test
    fun paginaImpresaConRegionesYEstilo() = runBlocking {
        val img = white(1000, 1000)
        img.rect(100, 100, 900, 130, BLACK); img.rect(100, 300, 900, 315, BLACK); img.rect(100, 330, 600, 345, BLACK)
        val regions = listOf(
            PpLayout.Region("doc_title", BlockType.TITLE, 0.9f, floatArrayOf(90f, 90f, 910f, 140f), 0),
            PpLayout.Region("text", BlockType.TEXT, 0.9f, floatArrayOf(90f, 290f, 910f, 350f), 1),
            PpLayout.Region("text", BlockType.TEXT, 0.9f, floatArrayOf(90f, 500f, 910f, 520f), 2),
            PpLayout.Region("display_formula", BlockType.FORMULA, 0.9f, floatArrayOf(300f, 700f, 700f, 760f), 3),
        )
        val page = listOf(spot("Título", intArrayOf(100, 100, 900, 130)), spot("Primera línea del párrafo", intArrayOf(100, 300, 900, 315)),
            spot("segunda", intArrayOf(100, 330, 600, 345))).joinToString("\n")
        val r = FakeReader(page, { spot("Texto recortado", intArrayOf(5, 5, 995, 995)) })
        val out = PartsPipeline(r).run(PartsPipeline.Input(img, regions))
        val l = out.layout
        assertEquals("pagina", out.meta.mode)
        val title = l.blocks.first { it.type == BlockType.TITLE }
        assertEquals(true, title.bold)
        val para = l.blocks.first { it.role == "text" && it.lines.size == 2 }
        assertEquals("LEFT", para.align)
        assertNotNull(para.sizePx)
        // región de texto sin líneas → Spotting del recorte
        assertTrue(r.calls.count { it == "recorte" } >= 1)
        assertTrue(l.blocks.any { it.text == "Texto recortado" })
        assertEquals("\\frac{a}{b}", l.blocks.first { it.type == BlockType.FORMULA }.latex)
        assertEquals(listOf(0, 1, 2, 3, 4).take(l.blocks.size), l.blocks.map { it.order })
        // capa de texto y escala a la imagen completa
        val full = PartsPipeline.scaleLayout(l, 2000, 2000)
        assertEquals(l.blocks[0].box.left * 2, full.blocks[0].box.left, 0.01f)
        assertTrue(PartsPipeline.ocrOf(full).text.contains("Primera línea"))
    }

    @Test
    fun bucleDePaginaPasaARegiones() = runBlocking {
        val img = white(800, 800)
        img.rect(100, 100, 700, 120, BLACK); img.rect(100, 600, 500, 620, BLACK)  // la 2.ª fuera de regiones
        val regions = (0 until 3).map { PpLayout.Region("text", BlockType.TEXT, 0.9f, floatArrayOf(90f, 90f + it * 2, 710f, 130f + it * 2), it) }
        val r = FakeReader(null, { spot("hola mundo", intArrayOf(20, 20, 980, 980)) })
        val out = PartsPipeline(r).run(PartsPipeline.Input(img, regions.take(1)))
        assertEquals("regiones", out.meta.mode)
        // manuscrito → por regiones desde el principio (sin Spotting de página)
        val r2 = FakeReader("x", { spot("hola", intArrayOf(20, 20, 980, 980)) })
        val out2 = PartsPipeline(r2).run(PartsPipeline.Input(img, regions, docType = "manuscrito"))
        assertEquals("regiones", out2.meta.mode)
        assertFalse("pagina" in r2.calls)
        assertTrue(out2.layout.blocks.filter { it.lines.isNotEmpty() }.all { it.type == BlockType.HANDWRITING })
    }

    @Test
    fun tablaAnchaPorBandasConColumnaClave() = runBlocking {
        val img = white(2000, 400)
        // 12 columnas separadas por huecos
        val items = (0 until 12).flatMap { c -> (0 until 6).map { r -> intArrayOf(20 + c * 160, 20 + r * 60, 120 + c * 160, 50 + r * 60) } }
        val page = items.withIndex().joinToString("\n") { (k, b) -> spot("val" + "abcdefghijklmnopqrstuvwxyz"[k % 26] + (k / 26), intArrayOf(b[0] / 2, b[1] * 1000 / 400, b[2] / 2, b[3] * 1000 / 400)) }
        val pieces = ArrayList<Int>()
        val r = FakeReader(page, { "" }, tables = { p ->
            pieces += p.size
            if (p.size == 1 && p[0].src[2] - p[0].src[0] > 1500) PartsReader.Out("", true)  // la tabla entera hace bucle
            else {
                val n = if (p.size == 1) 8 else 5   // banda 1: 8 columnas; resto: clave + 4
                PartsReader.Out((0 until 6).joinToString("") { "<fcel>c" + "<fcel>c".repeat(n - 1) + "<nl>" }, false)
            }
        })
        val regions = listOf(PpLayout.Region("table", BlockType.TABLE, 0.9f, floatArrayOf(10f, 10f, 1990f, 390f), 0)) + textRegions(400, 2000f)
        val out = PartsPipeline(r).run(PartsPipeline.Input(img, regions))
        val t = out.layout.blocks.first { it.type == BlockType.TABLE }
        assertEquals(12, t.tableCols)
        assertEquals(6, t.tableRows)
        assertEquals(listOf(1, 1, 2), pieces)  // entera (bucle) + banda 1 + banda 2 con la columna clave
        assertEquals(false, t.ruled)
        assertEquals(listOf(0 until 8, 8 until 12), PartsPipeline.bands(12, 8))
    }

    /** 3 regiones de texto vacías abajo del todo (para que la página se lea con Spotting de página). */
    private fun textRegions(h: Int, w: Float = 800f) = (0 until 3).map { PpLayout.Region("text", BlockType.TEXT, 0.9f, floatArrayOf(10f + it * w / 3, h - 8f, (it + 1) * w / 3 - 10f, h - 2f), 10 + it) }

    @Test
    fun formulaConFrasesEsTexto() = runBlocking {
        val img = white(800, 800)
        val regions = listOf(PpLayout.Region("display_formula", BlockType.FORMULA, 0.9f, floatArrayOf(50f, 50f, 750f, 200f), 0)) + textRegions(800)
        val page = listOf(spot("buscar investigaciones sobre", intArrayOf(70, 70, 900, 120)), spot("whatsapp usado mucho", intArrayOf(70, 150, 900, 220))).joinToString("\n")
        val out = PartsPipeline(FakeReader(page, { "" })).run(PartsPipeline.Input(img, regions))
        assertTrue(out.layout.blocks.none { it.type == BlockType.FORMULA })
        assertTrue(out.layout.blocks.any { it.type == BlockType.TEXT && it.lines.size == 2 })
    }

    @Test
    fun alineacionYAgrupado() {
        fun ln(l: Float, r: Float, y: Float) = LayoutLine("x", LayoutBox(l, y, r, y + 10))
        val bx = floatArrayOf(0f, 0f, 100f, 50f)
        assertEquals("JUSTIFY", PartsPipeline.alignOf(listOf(ln(0f, 100f, 0f), ln(0f, 100f, 12f), ln(0f, 60f, 24f)), bx))
        assertEquals("CENTER", PartsPipeline.alignOf(listOf(ln(20f, 80f, 0f), ln(30f, 70f, 12f)), bx))
        assertEquals("RIGHT", PartsPipeline.alignOf(listOf(ln(20f, 100f, 0f), ln(50f, 100f, 12f)), bx))
        val it1 = PartsPipeline.Item("a", FloatArray(8), floatArrayOf(10f, 10f, 200f, 30f))
        val it2 = PartsPipeline.Item("b", FloatArray(8), floatArrayOf(10f, 35f, 180f, 55f))
        val it3 = PartsPipeline.Item("c", FloatArray(8), floatArrayOf(500f, 400f, 700f, 420f))
        assertEquals(2, PartsPipeline.groupFree(listOf(it1, it2, it3), 1000, 1000).size)
    }

    // ------------------------------------------------------------------------------------------ texto / híbrido

    @Test
    fun parecidoComoDifflib() {
        assertEquals(0.75f, TextSim.sim("abcd", "bcde"), 1e-4f)
        assertEquals(1f, TextSim.sim("Hola  Mundo", "hola mundo"), 1e-4f)
        assertEquals(0f, TextSim.sim("", "x"), 0f)
        // difflib.SequenceMatcher(None, 'printed text', 'pr1nted test').ratio() = 0.8333
        assertEquals(0.8333f, TextSim.sim("printed text", "pr1nted test"), 1e-3f)
    }

    @Test
    fun repartoDePalabras() {
        val parts = HybridFuse.splitWords("el perro come mucho pan".split(" "), listOf("el perro", "come mucho pan"))
        assertEquals(listOf("el perro", "come mucho pan"), parts)
    }

    private fun tb(type: BlockType, l: Float, t: Float, r: Float, b: Float, vararg lines: Pair<String, FloatArray>, role: String? = null) =
        LayoutBlock(type = type, box = LayoutBox(l, t, r, b), lines = lines.map { (s, x) -> LayoutLine(s, LayoutBox(x[0], x[1], x[2], x[3])) },
            text = lines.joinToString("\n") { it.first }, role = role)

    @Test
    fun hibridoGeometriaLocalTextoDeLaNube() {
        val local = DocLayout(1000, 1000, listOf(
            tb(BlockType.TEXT, 100f, 100f, 900f, 160f, "Tbe quick brown fax" to floatArrayOf(100f, 100f, 900f, 120f), "jumps ovor the dog" to floatArrayOf(100f, 140f, 700f, 160f)),
            tb(BlockType.FIGURE, 300f, 500f, 400f, 540f, role = "dibujo"),
            tb(BlockType.TABLE, 100f, 700f, 900f, 800f, "Total 12" to floatArrayOf(100f, 700f, 400f, 720f)),
        ), rules = listOf(listOf(0f, 650f, 1000f, 650f, 2f)))
        val cloud = DocLayout(1000, 1000, listOf(
            tb(BlockType.TEXT, 90f, 95f, 910f, 170f, "The quick brown fox" to floatArrayOf(90f, 95f, 910f, 125f), "jumps over the dog" to floatArrayOf(90f, 135f, 710f, 170f))
                .copy(style = BlockStyle(bold = true, align = "center")),
            tb(BlockType.TEXT, 290f, 495f, 410f, 545f, "Nota a mano" to floatArrayOf(290f, 495f, 410f, 545f)),
            LayoutBlock(type = BlockType.TABLE, box = LayoutBox(100f, 690f, 900f, 810f), tableRows = 1, tableCols = 2,
                cells = listOf(TableCell(0, 0, text = "Total"), TableCell(0, 1, text = "12,00")), text = "Total\t12,00"),
            tb(BlockType.TEXT, 100f, 900f, 300f, 920f, "Pie añadido" to floatArrayOf(100f, 900f, 300f, 920f)),
        ))
        val (f, st) = HybridFuse.fuse(local, cloud)
        val txt = f.blocks.first { it.type == BlockType.TEXT && it.lines.size == 2 }
        assertEquals("The quick brown fox", txt.lines[0].text)
        assertEquals(100f, txt.lines[0].box.left, 0f)          // geometría local
        assertEquals("center", txt.style?.align)              // estilo de la nube como pista
        assertEquals("CENTER", txt.effectiveAlign)
        assertTrue(f.blocks.any { it.role == "dibujo→nube" && it.text == "Nota a mano" })
        val t = f.blocks.first { it.type == BlockType.TABLE }
        assertEquals(2, t.cells.size)
        assertTrue(f.blocks.any { it.role == "nube" && it.text == "Pie añadido" })
        assertEquals(1, f.rules.size)
        assertTrue(st.linesTextMatch >= 2)
    }

    @Test
    fun hibridoImpresoMismaPosicionPrefiereLaNube() {
        // Paddle trocea una línea de código en fragmentos; la nube la da entera y bien: misma posición → la de la nube.
        val lines = (0 until 6).map { i -> "linea correcta numero $i" to floatArrayOf(100f, 100f + 40 * i, 800f, 120f + 40 * i) }
        val localLines = lines.mapIndexed { i, (s, b) -> (if (i == 3) "rn d1sk 1d st4tvs" else s) to b }
        val local = DocLayout(1000, 1000, listOf(tb(BlockType.TEXT, 100f, 100f, 800f, 340f, *localLines.toTypedArray())))
        val cloudLines = lines.mapIndexed { i, (s, b) -> (if (i == 3) "mdisk_id mdisk_name status" else s) to floatArrayOf(b[0] + 8, b[1] + 6, b[2] + 8, b[3] + 6) }
        val cloud = DocLayout(1000, 1000, listOf(tb(BlockType.TEXT, 100f, 100f, 800f, 340f, *cloudLines.toTypedArray())))
        val (f, _) = HybridFuse.fuse(local, cloud)
        assertEquals("mdisk_id mdisk_name status", f.blocks[0].lines[3].text)
        assertTrue(TextSim.sim("rn d1sk 1d st4tvs", "mdisk_id mdisk_name status") < 0.5f)
        val (g, _) = HybridFuse.fuse(local, cloud, positional = false)
        assertEquals("rn d1sk 1d st4tvs", g.blocks[0].lines[3].text)   // sin 1c se quedaba el texto local
    }

    // ------------------------------------------------------------------------------------------ contrato

    @Test
    fun docLayoutRetrocompatibleYCamposNuevos() {
        val old = """{"imageWidth":100,"imageHeight":200,"blocks":[{"type":"TEXT","box":{"left":1,"top":2,"right":3,"bottom":4},"text":"a"}]}"""
        val l = DocLayoutFiles.parse(old)!!
        assertEquals(0, l.rotation); assertTrue(l.rules.isEmpty()); assertNull(l.blocks[0].align); assertFalse(l.blocks[0].hasStyle)
        val n = l.copy(rotation = 90, rules = listOf(listOf(0f, 1f, 2f, 1f, 1f)), blocks = l.blocks.map { it.copy(align = "JUSTIFY", sizePx = 12f, color = 0xFF2040C0.toInt(), role = "text") })
        val back = DocLayoutFiles.parse(DocLayoutFiles.encode(n))!!
        assertEquals(n, back)
        val cloudOnly = l.blocks[0].copy(style = BlockStyle(bold = true, align = "right", size = "large"))
        assertEquals("RIGHT", cloudOnly.effectiveAlign); assertEquals(true, cloudOnly.effectiveBold)
        assertEquals("LEFT", cloudOnly.copy(align = "left").effectiveAlign)  // lo local manda
        assertEquals(1.3f, LayoutBlock.styleSizeFactor("large")!!, 0f)
    }
}
