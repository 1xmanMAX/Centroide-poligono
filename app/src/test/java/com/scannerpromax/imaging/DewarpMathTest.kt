package com.scannerpromax.imaging

import com.scannerpromax.imaging.DewarpMath.Chain
import com.scannerpromax.imaging.DewarpMath.LineObs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class DewarpMathTest {

    /** Columnas muestreadas cada 2 px con las posiciones de [lines] (funciones de t), con huecos opcionales. */
    private fun columns(n: Int, lines: List<(Float) -> Float>, gap: (Int, Float) -> Boolean = { _, _ -> false }): Pair<FloatArray, Array<FloatArray>> {
        val ts = FloatArray(n) { it * 2f }
        val cols = Array(n) { k ->
            lines.mapIndexedNotNull { i, f -> if (gap(i, ts[k])) null else f(ts[k]) }.sorted().toFloatArray()
        }
        return ts to cols
    }

    @Test
    fun linkColumnsFollowsCurvedParallelLines() {
        val curves = (0 until 5).map { i -> { t: Float -> 40f * i + 20f + 6f * sin(t / 120f) } }
        val (ts, cols) = columns(300, curves)
        val chains = DewarpMath.linkColumns(ts, cols, tol = 1.6f, maxGap = 14f, maxSlope = 0.45f)
        assertEquals(5, chains.size)
        for (c in chains) {
            assertEquals(300, c.size)
            // Cada cadena sigue una sola curva (sin saltar a la vecina)
            val i = ((c.c[0] - 20f) / 40f).toInt()
            for (k in 0 until c.size) assertEquals(curves[i](c.t[k]), c.c[k], 1e-3f)
        }
    }

    @Test
    fun mergeChainsBridgesGapsButNotNeighbours() {
        // Dos líneas rectas inclinadas cortadas por "texto" entre t = 200 y 260
        val l0 = { t: Float -> 50f + 0.05f * t }
        val l1 = { t: Float -> 90f + 0.05f * t }
        val (ts, cols) = columns(300, listOf(l0, l1)) { _, t -> t in 200f..260f }
        val raw = DewarpMath.linkColumns(ts, cols, tol = 1.6f, maxGap = 14f, maxSlope = 0.45f)
        assertEquals(4, raw.size)
        val merged = DewarpMath.mergeChains(raw, maxBridge = 120f, tol = 2f, fitLen = 50f)
        assertEquals(2, merged.size)
        for (c in merged) {
            assertTrue(c.t0 < 1f && c.t1 > 590f)
            val f = if (c.c[0] < 70f) l0 else l1
            for (k in 0 until c.size) assertEquals(f(c.t[k]), c.c[k], 1e-3f)
        }
    }

    @Test
    fun mergeChainsRejectsMisalignedSegments() {
        val a = Chain(FloatArray(50) { it * 2f }, FloatArray(50) { 100f })
        val b = Chain(FloatArray(50) { 160f + it * 2f }, FloatArray(50) { 108f })   // 8 px más abajo
        assertEquals(2, DewarpMath.mergeChains(listOf(a, b), maxBridge = 120f, tol = 2f, fitLen = 50f).size)
    }

    @Test
    fun robustSmoothDropsOutliersAndKeepsCurve() {
        val t = FloatArray(200) { it * 2f }
        val c = FloatArray(200) { 30f + 0.0005f * (t[it] - 200f) * (t[it] - 200f) }
        for (k in 90 until 96) c[k] += 7f   // trazo de escritura pegado a la línea
        val s = DewarpMath.robustSmooth(Chain(t, c), win = 28f, outTol = 1.5f, minInlier = 0.7f)
        assertNotNull(s)
        assertEquals(194, s!!.size)
        for (k in 0 until s.size) assertEquals(30f + 0.0005f * (s.t[k] - 200f) * (s.t[k] - 200f), s.c[k], 0.35f)
        // Una cadena que es casi toda ruido se rechaza
        val noisy = FloatArray(200) { if (it % 2 == 0) 0f else 9f }
        assertNull(DewarpMath.robustSmooth(Chain(t, noisy), win = 28f, outTol = 1.5f, minInlier = 0.7f))
    }

    @Test
    fun polyFitRecoversCubic() {
        val t = FloatArray(60) { it * 10f }
        val c = FloatArray(60) { val x = t[it].toDouble(); (3 + 0.2 * x - 1e-3 * x * x + 2e-6 * x * x * x).toFloat() }
        val p = DewarpMath.polyFit(t, c, 3)
        for (x in listOf(0.0, 155.0, 590.0)) assertEquals(3 + 0.2 * x - 1e-3 * x * x + 2e-6 * x * x * x, p.eval(x), 1e-2)
        assertEquals(0.0, DewarpMath.straightnessDev(LineObs(true, t, FloatArray(60) { 5f + 0.1f * t[it] })), 1e-4)
    }

    /** Líneas observadas de una hoja deformada por y' = y + A·sin(πx/W) (curvatura tipo libro). */
    private fun bowedSheet(w: Double, h: Double, amp: Double): List<LineObs> {
        val out = ArrayList<LineObs>()
        var y = 60.0
        while (y < h - 40) {
            val xs = FloatArray(60) { (40 + it * (w - 80) / 59).toFloat() }
            out.add(LineObs(true, xs, FloatArray(60) { (y + amp * sin(Math.PI * xs[it] / w)).toFloat() }))
            y += 60.0
        }
        var x = 60.0
        while (x < w - 40) {
            val ys = FloatArray(40) { (40 + it * (h - 80) / 39).toFloat() }
            out.add(LineObs(false, FloatArray(40) { x.toFloat() }, FloatArray(40) { (ys[it] + amp * sin(Math.PI * x / w)).toFloat() }))
            x += 80.0
        }
        return out.mapNotNull { DewarpMath.resample(it, 15.0) }
    }

    @Test
    fun solveFieldStraightensBowedLinesAndIsMonotone() {
        val w = 1200.0; val h = 900.0
        val lines = bowedSheet(w, h, 18.0)
        val before = lines.maxOf { DewarpMath.straightnessDev(it) }
        assertTrue("antes $before", before > 8)
        val f = DewarpMath.solveField(w, h, 29, 22, lines, DewarpMath.Weights())
        val after = lines.maxOf { DewarpMath.lineResidual(f, it) }
        assertTrue("residuo $after", after < 0.6)
        val (lo, hi) = DewarpMath.jacobianRange(f)
        assertTrue("jacobiano $lo..$hi", lo > 0.8 && hi < 1.25)
    }

    @Test
    fun solveFieldOnFlatSheetIsNearIdentity() {
        val w = 1000.0; val h = 1400.0
        val lines = bowedSheet(w, h, 0.0)
        val f = DewarpMath.solveField(w, h, 21, 29, lines, DewarpMath.Weights())
        val o = DoubleArray(6)
        var maxD = 0.0
        for (y in 0..14) for (x in 0..10) {
            f.eval(x * 100.0, y * 100.0, o)
            maxD = maxOf(maxD, abs(o[0] - x * 100.0), abs(o[1] - y * 100.0))
        }
        assertTrue("desplazamiento $maxD", maxD < 0.05)
    }

    @Test
    fun invertComposesToIdentity() {
        val w = 1200.0; val h = 900.0
        val f = DewarpMath.solveField(w, h, 29, 22, bowedSheet(w, h, 18.0), DewarpMath.Weights())
        val gw = 151; val gh = 113
        val inv = DewarpMath.invert(f, gw, gh)
        assertNotNull(inv)
        val o = DoubleArray(6)
        for (b in 0 until gh step 7) for (a in 0 until gw step 7) {
            f.eval(inv!![2 * (b * gw + a)], inv[2 * (b * gw + a) + 1], o)
            assertEquals(a * w / (gw - 1), o[0], 0.01)
            assertEquals(b * h / (gh - 1), o[1], 0.01)
        }
        // Monótona: x crece a lo largo de cada fila e y a lo largo de cada columna (sin pliegues)
        for (b in 0 until gh) for (a in 1 until gw) assertTrue(inv!![2 * (b * gw + a)] > inv[2 * (b * gw + a - 1)])
        for (a in 0 until gw) for (b in 1 until gh) assertTrue(inv!![2 * (b * gw + a) + 1] > inv[2 * ((b - 1) * gw + a) + 1])
    }

    @Test
    fun gridRunsHandleCompressionMissingAndSpuriousLines() {
        // Cuadrícula de paso 40 comprimida progresivamente hacia el final (lomo), con una línea perdida y una espuria
        val pos = ArrayList<Double>()
        var p = 0.0; var step = 40.0
        for (k in 0 until 16) { if (k != 5) pos.add(p); p += step; if (k > 9) step *= 0.94 }
        pos.add(3, pos[2] + 17.0)   // trazo espurio entre dos líneas
        val runs = DewarpMath.gridRuns(pos)
        assertEquals(1, runs.size)
        val r = runs[0]
        assertEquals(15, r.idx.size)          // 16 líneas - 1 perdida; la espuria no entra
        assertTrue(3 !in r.idx.toList())
        assertEquals(15, r.k.last())          // la perdida cuenta como paso
        // Tabla con columnas de anchos distintos: no es una cuadrícula regular
        assertTrue(DewarpMath.gridRuns(listOf(0.0, 60.0, 360.0, 440.0, 520.0, 620.0, 750.0, 1000.0)).isEmpty())
    }

    @Test
    fun thinInkFractionSeparatesLinesFromText() {
        val w = 200; val h = 60
        val bin = ByteArray(w * h)
        for (x in 0 until w) for (y in 20..21) bin[y * w + x] = 1          // línea de 2 px
        for (x in 0 until w step 9) for (y in 34..50) for (dx in 0..2) if (x + dx < w) bin[y * w + x + dx] = 1  // "letras"
        val line = Chain(FloatArray(100) { it * 2f }, FloatArray(100) { 20.5f })
        val text = Chain(FloatArray(100) { it * 2f }, FloatArray(100) { 42f })
        assertTrue(DewarpMath.thinInkFraction(bin, w, h, line, 4) > 0.95)
        assertTrue(DewarpMath.thinInkFraction(bin, w, h, text, 4) < 0.1)
    }
}
