package com.scannerpromax.pdf

import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.LayoutLine
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.TableCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/** Fuente ficticia: cada carácter mide 0,5 em; la negrita y la manuscrita solo tienen latín básico + Latin-1. */
internal object FakeMetrics : ReFontMetrics {
    override fun widthEm(font: ReFont, text: String) = text.length * 0.5f
    override fun capHeight(font: ReFont) = 0.7f
    override fun xHeight(font: ReFont) = 0.5f
    override fun descent(font: ReFont) = 0.2f
    override fun clean(font: ReFont, text: String): String = text
        .map { if (it == '\n' || it == '\t') ' ' else it }
        .filter { !it.isISOControl() && (font == ReFont.REGULAR || it.code <= 0x17F) }
        .joinToString("").trim()
}

class ReconstructionLayoutTest {

    private val map = ImageToPage(x0 = 0f, yTop = 1000f, sx = 1f, sy = 1f)

    private fun line(text: String, l: Float, t: Float, r: Float, b: Float) = LayoutLine(text, LayoutBox(l, t, r, b))

    /** Ancho que ocupa el texto dibujado (avance real con Tz y Tc). */
    private fun drawnWidth(op: ReOp.Text) =
        (FakeMetrics.widthEm(op.font, op.text) * op.size + op.charSpacing * op.text.length) * op.hScale / 100f

    @Test
    fun lineaRectaLlenaSuCaja() {
        // "Hola mundo" (10 car. = 5 em); caja de 100 x 20 px.
        val op = ReconstructionLayout.placeLine("Hola mundo", ReFont.REGULAR, line("Hola mundo", 10f, 100f, 110f, 120f), map, FakeMetrics, 0)
        assertNotNull(op); op!!
        // Sin descendentes: tamaño = alto / (0,7 + 0 + relleno).
        assertEquals(20f / (0.7f + ReconstructionLayout.LINE_PAD_EM), op.size, 0.01f)
        assertEquals(100f, drawnWidth(op), 0.5f)
        assertEquals(10f, op.x, 0.01f)
        // Línea base dentro de la caja (y PDF entre 880 y 900).
        assertTrue(op.y in 880f..900f)
        assertEquals(1f, op.cos, 1e-6f)
        assertEquals(0f, op.sin, 1e-6f)
    }

    @Test
    fun descendentesBajanLaLineaBaseYReducenLaLetra() {
        val a = ReconstructionLayout.placeLine("HOLA", ReFont.REGULAR, line("HOLA", 0f, 0f, 40f, 20f), map, FakeMetrics, 0)!!
        val b = ReconstructionLayout.placeLine("Jugo", ReFont.REGULAR, line("Jugo", 0f, 0f, 40f, 20f), map, FakeMetrics, 0)!!
        assertTrue(b.size < a.size)
        assertTrue(b.y > a.y) // con descendentes la base sube (más espacio debajo)
    }

    @Test
    fun cuadrilateroGiradoSeRespeta() {
        // Línea girada ~5° (horario en la imagen): en el PDF el ángulo es negativo.
        val ang = Math.toRadians(5.0)
        val c = Math.cos(ang).toFloat(); val s = Math.sin(ang).toFloat()
        val len = 200f; val h = 20f
        val x0 = 100f; val y0 = 100f
        val quad = listOf(
            x0, y0,
            x0 + len * c, y0 + len * s,
            x0 + len * c - h * s, y0 + len * s + h * c,
            x0 - h * s, y0 + h * c,
        )
        val ln = LayoutLine("Texto inclinado", LayoutBox(0f, 0f, 1f, 1f), quad)
        val op = ReconstructionLayout.placeLine("Texto inclinado", ReFont.REGULAR, ln, map, FakeMetrics, 0)!!
        val got = Math.toDegrees(atan2(op.sin, op.cos).toDouble())
        assertEquals(-5.0, got, 0.05)
        assertEquals(len, drawnWidth(op), 1f)
        val g = ReconstructionLayout.quadGeometryPx(ln)
        assertEquals(h, g.height, 0.01f)
        assertEquals(len, g.length, 0.01f)
    }

    @Test
    fun textoLargoReduceLaLetraYTextoEspaciadoReparteEspacio() {
        val long = "x".repeat(40) // 20 em
        val op = ReconstructionLayout.placeLine(long, ReFont.REGULAR, line(long, 0f, 0f, 100f, 20f), map, FakeMetrics, 0)!!
        assertEquals(100f, drawnWidth(op), 0.5f)
        assertTrue(op.size < 20f / 0.82f)
        val short = "AB"
        val sp = ReconstructionLayout.placeLine(short, ReFont.REGULAR, line(short, 0f, 0f, 300f, 20f), map, FakeMetrics, 0)!!
        assertTrue(sp.charSpacing > 0f)
        assertEquals(ReconstructionLayout.MAX_HSCALE * 100f, sp.hScale, 0.01f)
        assertEquals(300f, drawnWidth(sp), 0.5f)
    }

    @Test
    fun fuentesPorTipoDeBloqueYTituloPorAltura() {
        val body = (0 until 6).map { line("linea normal $it", 10f, 100f + it * 30f, 300f, 120f + it * 30f) }
        val tall = line("Gran titular", 10f, 20f, 400f, 60f) // 40 px = 2x la mediana
        val layout = DocLayout(
            600, 1000,
            listOf(
                LayoutBlock(BlockType.TEXT, LayoutBox(10f, 20f, 400f, 60f), lines = listOf(tall), order = 0),
                LayoutBlock(BlockType.TEXT, LayoutBox(10f, 100f, 300f, 300f), lines = body, order = 1),
                LayoutBlock(BlockType.TITLE, LayoutBox(10f, 400f, 300f, 430f), lines = listOf(line("Sección", 10f, 400f, 300f, 430f)), order = 2),
                LayoutBlock(BlockType.HANDWRITING, LayoutBox(10f, 500f, 300f, 530f), lines = listOf(line("nota a mano", 10f, 500f, 300f, 530f)), order = 3),
                // La negrita (subconjunto latino) no tiene griego: vuelve a la normal.
                LayoutBlock(BlockType.TITLE, LayoutBox(10f, 600f, 300f, 630f), lines = listOf(line("Ωmega", 10f, 600f, 300f, 630f)), order = 4),
            ),
        )
        val ops = ReconstructionLayout.plan(layout, PageSize.AUTO, 0f, FakeMetrics).ops.filterIsInstance<ReOp.Text>()
        fun fontOf(t: String) = ops.first { it.text == t }.font
        assertEquals(ReFont.BOLD, fontOf("Gran titular"))
        assertEquals(ReFont.REGULAR, fontOf("linea normal 0"))
        assertEquals(ReFont.BOLD, fontOf("Sección"))
        assertEquals(ReFont.HAND, fontOf("nota a mano"))
        // Lo que la negrita no tiene (griego) va en la normal, en el mismo renglón y seguido.
        assertEquals(ReFont.REGULAR, fontOf("Ω"))
        assertEquals(ReFont.BOLD, fontOf("mega"))
        val om = ops.first { it.text == "Ω" }; val me = ops.first { it.text == "mega" }
        assertEquals(om.y, me.y, 1e-3f)
        assertEquals(om.x + (0.5f * om.size + om.charSpacing) * om.hScale / 100f, me.x, 1e-3f)
    }

    @Test
    fun textoVerticalSeGira() {
        // Caja 20 x 200 px con "82837252" (sin cuadrilátero girado): se escribe hacia abajo.
        val ln = line("82837252", 500f, 100f, 520f, 300f)
        val op = ReconstructionLayout.placeLine("82837252", ReFont.REGULAR, ln, map, FakeMetrics, 0)!!
        assertEquals(0f, op.cos, 1e-5f)
        assertEquals(-1f, op.sin, 1e-5f)
        assertEquals(200f, drawnWidth(op), 0.5f)
        // Empieza arriba (y PDF = 1000 - 100) y la línea base queda dentro de la caja.
        assertEquals(900f, op.y, 0.01f)
        assertTrue(op.x in 500f..520f)
        // Un "1" alto y estrecho no se gira.
        assertNull(ReconstructionLayout.verticalQuad("1", line("1", 0f, 0f, 10f, 40f)))
    }

    @Test
    fun colorDeTinta() {
        val ln = LayoutLine("azul", LayoutBox(0f, 0f, 40f, 20f), color = 0xFF1428A0.toInt())
        val layout = DocLayout(100, 100, listOf(LayoutBlock(BlockType.HANDWRITING, ln.box, lines = listOf(ln))))
        val op = ReconstructionLayout.plan(layout, PageSize.AUTO, 0f, FakeMetrics).ops.single() as ReOp.Text
        assertEquals(0x1428A0, op.rgb)
    }

    @Test
    fun paginaConLaProporcionDeLaImagenYTamanoFijoSinRecortar() {
        val layout = DocLayout(754, 1000, emptyList())
        val auto = ReconstructionLayout.plan(layout, PageSize.AUTO, 0f, FakeMetrics)
        assertEquals(754f / 1000f, auto.pageW / auto.pageH, 0.002f)
        val a4 = ReconstructionLayout.plan(DocLayout(2000, 1000, emptyList()), PageSize.A4, 0f, FakeMetrics)
        assertTrue(a4.pageW > a4.pageH) // apaisada como la imagen
    }

    @Test
    fun graficoFiguraYFormulaSonRecortesConDatosInvisibles() {
        val layout = DocLayout(
            1000, 1000,
            listOf(
                LayoutBlock(BlockType.CHART, LayoutBox(100f, 100f, 500f, 400f), chartData = listOf(listOf("mes", "miles"), listOf("Enero", "12")), order = 0),
                LayoutBlock(BlockType.FORMULA, LayoutBox(100f, 500f, 600f, 560f), latex = "x={\\frac{-b}{2a}}", order = 1),
                LayoutBlock(BlockType.FIGURE, LayoutBox(100f, 600f, 300f, 900f), order = 2),
            ),
        )
        val plan = ReconstructionLayout.plan(layout, PageSize.AUTO, 0f, FakeMetrics)
        val imgs = plan.ops.filterIsInstance<ReOp.Image>()
        assertEquals(listOf(BlockType.CHART, BlockType.FORMULA, BlockType.FIGURE), imgs.map { it.kind })
        assertEquals(PxRect(100, 100, 500, 400), imgs[0].src)
        val hidden = plan.ops.filterIsInstance<ReOp.Text>()
        assertTrue(hidden.all { !it.visible })
        assertTrue(plan.allText.contains("Enero | 12"))
        assertTrue(plan.allText.contains("x={\\frac{-b}{2a}}"))
        // El recorte ocupa en la página el mismo sitio (proporción) que en la imagen.
        val k = plan.pageW / 1000f
        assertEquals(100f * k, imgs[0].x, 0.01f)
        assertEquals(400f * k, imgs[0].w, 0.01f)
    }

    @Test
    fun parrafoSinLineasSeAjustaASuCaja() {
        val r = ReconstructionLayout.PtRect(0f, 0f, 100f, 60f)
        val ops = ReconstructionLayout.fitParagraph("una frase bastante larga que hay que partir en varias lineas", ReFont.REGULAR, r, FakeMetrics, 20f, true)
        assertTrue(ops.size > 1)
        for (o in ops) {
            assertTrue(drawnWidth(o) <= 100.5f)
            assertTrue(o.y >= r.y - 0.01f && o.y <= r.y + r.h)
        }
        assertEquals("una frase bastante larga que hay que partir en varias lineas", ops.joinToString(" ") { it.text })
    }

    @Test
    fun latexEnLineaComoTextoLlano() {
        assertEquals("H2O 2300", InlineMath.plain("\\( \\text{H}_2\\text{O} \\) 2300"))
        assertEquals("α + β ≤ 3", InlineMath.plain("\\( \\alpha + \\beta \\leq 3 \\)"))
        assertEquals("Precio 3,50 €", InlineMath.plain("Precio 3,50 €"))
    }
}

class TableGridTest {

    private fun cell(r: Int, c: Int, rs: Int = 1, cs: Int = 1, t: String = "") = TableCell(r, c, rs, cs, t)

    @Test
    fun rejillaConCeldasCombinadas() {
        // Como la tabla financiera real: (0,0) ocupa 2 filas; (0,1) ocupa 3 columnas.
        val cells = listOf(
            cell(0, 0, rs = 2, t = "Categoría"), cell(0, 1, cs = 3, t = "Valor razonable"),
            cell(1, 1, t = "N1"), cell(1, 2, t = "N2"), cell(1, 3, t = "Total"),
            cell(2, 0, t = "Caja"), cell(2, 1, t = "20"), cell(2, 2, t = "—"), cell(2, 3, t = "20"),
        )
        val v = TableGrid.verticalSegments(3, 4, cells)
        // Frontera 1 (entre col 0 y 1): todas las filas. Fronteras 2 y 3: solo filas 1..3 (la fila 0 está combinada).
        assertTrue(v.contains(TableGrid.GridSeg(1, 0, 3)))
        assertTrue(v.contains(TableGrid.GridSeg(2, 1, 3)))
        assertTrue(v.contains(TableGrid.GridSeg(3, 1, 3)))
        assertEquals(3, v.size)
        val h = TableGrid.horizontalSegments(3, 4, cells)
        // Frontera de fila 1: no cruza la celda (0,0) combinada → solo columnas 1..4. Fila 2: completa.
        assertTrue(h.contains(TableGrid.GridSeg(1, 1, 4)))
        assertTrue(h.contains(TableGrid.GridSeg(2, 0, 4)))
        assertEquals(2, h.size)
    }

    @Test
    fun celdasSolapadasYFueraDeRangoSeDescartan() {
        val cells = listOf(cell(0, 0, cs = 3, t = "A"), cell(0, 1, t = "B"), cell(5, 0, t = "fuera"), cell(1, 0, rs = 9, t = "C"))
        val n = TableGrid.normalize(2, 3, cells)
        assertEquals(listOf("A", "C"), n.map { it.text })
        assertEquals(1, n[1].rowSpan) // recortado a la rejilla
    }

    @Test
    fun columnasSegunElContenidoYAjusteConLineas() {
        val cells = listOf(cell(0, 0, t = "Descripción del artículo"), cell(0, 1, t = "3"))
        val w = TableGrid.columnWeights(2, cells) { it.length.toFloat() }
        assertTrue(w[0] > w[1])
        val xs = TableGrid.boundaries(0f, 100f, floatArrayOf(1f, 1f))
        assertEquals(50f, xs[1], 0.001f)
        // Las líneas reconocidas dicen que la columna 0 acaba en 70 y la 1 empieza en 80 → frontera en 75.
        val lines = listOf(
            LayoutLine("Descripción del artículo", LayoutBox(2f, 2f, 70f, 10f)),
            LayoutLine("3", LayoutBox(80f, 2f, 85f, 10f)),
        )
        val ys = floatArrayOf(0f, 20f)
        TableGrid.refineFromLines(xs, ys, cells, lines)
        assertEquals(75f, xs[1], 0.001f)
    }

    @Test
    fun tablaEnElPlanDibujaRejillaYTexto() {
        val cells = listOf(cell(0, 0, t = "Producto"), cell(0, 1, t = "Total"), cell(1, 0, t = "Pan integral"), cell(1, 1, t = "2,10"))
        val layout = DocLayout(
            1000, 1000,
            listOf(LayoutBlock(BlockType.TABLE, LayoutBox(100f, 100f, 900f, 300f), tableRows = 2, tableCols = 2, cells = cells)),
        )
        val plan = ReconstructionLayout.plan(layout, PageSize.AUTO, 0f, FakeMetrics)
        val grid = plan.ops.filterIsInstance<ReOp.Lines>().single()
        assertEquals(4 + 1 + 1, grid.segments.size) // contorno + 1 vertical + 1 horizontal
        val texts = plan.ops.filterIsInstance<ReOp.Text>()
        assertEquals(setOf("Producto", "Total", "Pan integral", "2,10"), texts.map { it.text }.toSet())
        assertTrue(texts.all { it.visible })
        // El número va alineado a la derecha de su celda.
        val num = texts.first { it.text == "2,10" }
        val k = plan.pageW / 1000f
        assertTrue(num.x > 600f * k)
        assertFalse(texts.any { it.size <= 0f })
        // Las celdas no se salen de la tabla.
        for (t in texts) assertTrue(abs(t.x - 500f * k) < 400f * k)
    }
}
