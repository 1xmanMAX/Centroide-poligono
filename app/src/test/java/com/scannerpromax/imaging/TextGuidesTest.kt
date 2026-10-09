package com.scannerpromax.imaging

import com.scannerpromax.imaging.TextGuides.Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

class TextGuidesTest {

    private val med = 12f
    private val pitch = 28f

    /**
     * Renglones de una columna: inicio en [x0](y) (+ ruido gaussiano [noise]), cada 6º renglón con sangría de 40 px, el
     * renglón 0 es un título centrado y el último de cada párrafo es corto; finales en bandera (o justificados).
     */
    private fun column(
        n: Int, x0: (Float) -> Float, x1: (Float) -> Float, rnd: Random, noise: Double = 0.3,
        justified: Boolean = false, row0: Int = 0, title: Boolean = true,
    ): List<Seg> {
        val out = ArrayList<Seg>()
        for (k in 0 until n) {
            val y = 100f + k * pitch
            val l = x0(y); val r = x1(y)
            if (title && k == 0) { out.add(Seg(row0 + k, (l + r) / 2 - 80f, y, (l + r) / 2 + 80f, y)); continue }
            val indent = if (k % 6 == 1) 40f else 0f
            val short = k % 6 == 0
            val xs = l + indent + (rnd.nextGaussian() * noise).toFloat()
            val xe = when {
                short -> l + 0.3f * (r - l)
                justified -> r + (rnd.nextGaussian() * noise).toFloat()
                else -> r - 10f - rnd.nextFloat() * 90f
            }
            out.add(Seg(row0 + k, xs, y, xe, y))
        }
        return out
    }

    @Test
    fun splitRowCutsOnlyAtLargeGaps() {
        val l = floatArrayOf(0f, 20f, 45f, 200f, 220f)
        val r = floatArrayOf(15f, 40f, 60f, 215f, 240f)
        val parts = TextGuides.splitRow(l, r, 30f)
        assertEquals(listOf(0..2, 3..4), parts)
    }

    @Test
    fun leftMarginIgnoresIndentsTitlesAndRaggedEnds() {
        val rnd = Random(3)
        val segs = column(40, { 100f + 0.02f * (it - 100f) + 8f * sin(it / 300f) }, { 900f }, rnd)
        val starts = TextGuides.alignedGuides(segs, med, pitch, 1000f, start = true)
        assertEquals(1, starts.size)
        val g = starts[0]
        assertTrue(!g.loose)
        assertTrue("soporte ${g.support}", g.support > 0.7)
        // La guía pasa por el margen verdadero (no por las sangrías ni por el título)
        for (i in 0 until g.size) {
            val truth = 100f + 0.02f * (g.y[i] - 100f) + 8f * sin(g.y[i] / 300f)
            assertEquals(truth, g.x[i], 1.0f)
        }
        // Finales en bandera: no hay margen derecho
        assertTrue(TextGuides.alignedGuides(segs, med, pitch, 1000f, start = false).isEmpty())
    }

    @Test
    fun justifiedTextGivesRightMargin() {
        val rnd = Random(5)
        val segs = column(40, { 100f }, { 880f + 0.03f * (it - 100f) }, rnd, justified = true)
        val ends = TextGuides.alignedGuides(segs, med, pitch, 1000f, start = false)
        assertEquals(1, ends.size)
        val g = ends[0]
        for (i in 0 until g.size) assertEquals(880f + 0.03f * (g.y[i] - 100f), g.x[i], 1.0f)
    }

    @Test
    fun twoColumnsGiveTwoLeftEdges() {
        val rnd = Random(7)
        val segs = column(36, { 80f }, { 460f }, rnd, title = false) + column(36, { 540f }, { 920f }, rnd, row0 = 100, title = false)
        val starts = TextGuides.alignedGuides(segs, med, pitch, 1000f, start = true).sortedBy { it.x[0] }
        assertEquals(2, starts.size)
        assertEquals(80f, starts[0].x[starts[0].size / 2], 1f)
        assertEquals(540f, starts[1].x[starts[1].size / 2], 1f)
    }

    @Test
    fun handwrittenStartsGetLowWeightOrNothing() {
        val rnd = Random(9)
        // Dispersión moderada (≈ 0.2 letras): guía amplia, peso bajo
        val mild = TextGuides.alignedGuides(column(40, { 100f }, { 900f }, rnd, noise = 2.2, title = false), med, pitch, 1000f, start = true)
        assertTrue(mild.isNotEmpty())
        for (g in mild) { assertTrue(g.loose); assertTrue(g.weight < 0.5) }
        // Dispersión grande (≈ 1 letra): no se fuerza ningún margen
        val wild = TextGuides.alignedGuides(column(40, { 100f }, { 900f }, rnd, noise = 12.0, title = false), med, pitch, 1000f, start = true)
        assertTrue(wild.joinToString { "n=${it.size} r=${it.resid} s=${it.support} w=${it.weight} l=${it.loose}" }, wild.all { it.loose && it.weight < 0.3 } || wild.isEmpty())
    }

    @Test
    fun linkAlignedSkipsIndentedRows() {
        val y = FloatArray(12) { it * 30f }
        val x = FloatArray(12) { if (it == 4 || it == 8) 150f else 100f }
        val chains = TextGuides.linkAligned(x, y, tol = 5f, maxGap = 140f, minDy = 15f)
        val main = chains.maxByOrNull { it.size }!!
        assertEquals(10, main.size)
        assertTrue(main.none { it == 4 || it == 8 })
    }

    @Test
    fun profileEdgesFindsBaseAndXHeight() {
        // Perfil de un renglón: ascendentes (filas 0-5, poca tinta), cuerpo (6-15, mucha), descendentes (16-20, poca)
        val band = 10
        val p = FloatArray(2 * band + 1) { i -> when (i) { in 0..5 -> 3f; in 6..15 -> 20f; else -> 2f } }
        val e = TextGuides.profileEdges(p, band)
        assertNotNull(e)
        assertEquals(5.5f - band, e!!.first, 0.6f)
        assertEquals(15.5f - band, e.second, 0.6f)
        assertNull(TextGuides.profileEdges(FloatArray(21) { 5f }, band))
    }

    @Test
    fun consistentTopDropsCapitalWindows() {
        val t = FloatArray(10) { it * 10f }
        val base = DewarpMath.Chain(t, FloatArray(10) { 50f })
        val top = DewarpMath.Chain(t, FloatArray(10) { if (it == 3 || it == 7) 38f else 42f })
        val ct = TextGuides.consistentTop(top, base, 1.2f)!!
        assertEquals(8, ct.size)
        assertTrue(ct.c.all { it == 42f })
    }

    @Test
    fun extendRowsClaimsShortFirstWord() {
        val ch = DewarpMath.Chain(FloatArray(50) { 100f + it * 4f }, FloatArray(50) { 60f })
        val inRow = arrayListOf(floatArrayOf(120f, 60f, 66f, 110f, 130f))
        // "y" al principio del renglón (no seguido) y una letra de otro renglón
        val first = floatArrayOf(80f, 61f, 66f, 74f, 86f)
        val other = floatArrayOf(60f, 90f, 96f, 55f, 65f)
        val comps = listOf(inRow[0], first, other)
        val ext = TextGuides.extendRows(listOf(ch), comps, listOf(inRow), med, 30f)
        assertTrue(inRow.contains(first))
        assertTrue(!inRow.contains(other))
        assertTrue(ext[0].t0 <= 75f)
    }

    @Test
    fun skewFromRowsAndMargin() {
        val a = 2.0
        val rows = DoubleArray(20) { tan(Math.toRadians(a)) + (it % 3 - 1) * 1e-4 }
        val w = DoubleArray(20) { 500.0 }
        val s = TextGuides.skewFromGuides(rows, w, doubleArrayOf(-tan(Math.toRadians(a))), doubleArrayOf(800.0))
        assertNotNull(s)
        assertEquals(a, s!!, 0.05)
        // El margen discrepa de los renglones: no se fía
        assertNull(TextGuides.skewFromGuides(rows, w, doubleArrayOf(tan(Math.toRadians(3.0))), doubleArrayOf(800.0)))
        // Renglones incoherentes
        val bad = DoubleArray(21) { tan(Math.toRadians(it % 3 * 2.0)) }
        assertNull(TextGuides.skewFromGuides(bad, DoubleArray(21) { 500.0 }, DoubleArray(0), DoubleArray(0)))
        assertTrue(abs(TextGuides.weightedMedian(doubleArrayOf(1.0, 5.0, 2.0), doubleArrayOf(1.0, 1.0, 1.0)) - 2.0) < 1e-9)
    }
}
