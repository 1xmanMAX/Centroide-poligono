package com.scannerpromax.imaging

import com.scannerpromax.imaging.DewarpMath.LineObs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** Cantos de la hoja ([DwBorder], [DwPath]): lógica pura. */
class DwBorderTest {

    /** Perfiles nT x nS con valores L/A/B según la posición j y el perfil t. */
    private fun profiles(nT: Int, nS: Int, f: (Int, Int) -> Triple<Float, Float, Float>): Array<FloatArray> {
        val l = FloatArray(nT * nS); val a = FloatArray(nT * nS); val b = FloatArray(nT * nS)
        for (t in 0 until nT) for (j in 0 until nS) { val v = f(t, j); l[t * nS + j] = v.first; a[t * nS + j] = v.second; b[t * nS + j] = v.third }
        return arrayOf(l, a, b)
    }

    @Test
    fun pathFollowsSlantedPageEdge() {
        val nT = 120; val nS = 61
        val truth = IntArray(nT) { 20 + it / 4 }
        val p = profiles(nT, nS) { t, j -> if (j > truth[t]) Triple(210f, 128f, 128f) else Triple(70f, 140f, 150f) }
        val ok = BooleanArray(nT * nS) { true }
        val sc = DwPath.edgeScores(p[0], p[1], p[2], ok, nT, nS, 210.0, 128.0, 128.0, win = 3)
        val path = DwPath.bestPath(sc, nT, nS, maxStep = 2, penalty = 1.5f)
        for (t in 0 until nT) assertTrue("t=$t ${path[t]} vs ${truth[t]}", abs(path[t] - truth[t]) <= 1)
    }

    @Test
    fun photoEdgeInsidePageIsNotTakenForTheBorder() {
        // fondo oscuro | papel | foto de color: el canto es fondo->papel aunque el borde de la foto contraste más
        val nT = 80; val nS = 61
        val p = profiles(nT, nS) { _, j ->
            when {
                j < 18 -> Triple(60f, 130f, 135f)
                j < 40 -> Triple(215f, 128f, 128f)
                else -> Triple(90f, 185f, 90f)
            }
        }
        val ok = BooleanArray(nT * nS) { true }
        val sc = DwPath.edgeScores(p[0], p[1], p[2], ok, nT, nS, 215.0, 128.0, 128.0, win = 3)
        val path = DwPath.bestPath(sc, nT, nS, maxStep = 2, penalty = 1.5f)
        for (t in 0 until nT) assertTrue(abs(path[t] - 17) <= 1)
    }

    @Test
    fun printedBandAtTheEdgeIsNotTakenForTheBorder() {
        // fondo | margen de papel fino | banda de color impresa (cabecera) | papel: el canto es fondo->margen
        val nT = 80; val nS = 61
        val p = profiles(nT, nS) { _, j ->
            when {
                j < 12 -> Triple(70f, 135f, 140f)
                j < 17 -> Triple(215f, 128f, 128f)
                j < 32 -> Triple(120f, 185f, 160f)
                else -> Triple(215f, 128f, 128f)
            }
        }
        val ok = BooleanArray(nT * nS) { true }
        val sc = DwPath.edgeScores(p[0], p[1], p[2], ok, nT, nS, 215.0, 128.0, 128.0, win = 3)
        val path = DwPath.bestPath(sc, nT, nS, maxStep = 2, penalty = 1.5f)
        for (t in 0 until nT) assertTrue("${path[t]}", abs(path[t] - 11) <= 1)
    }

    @Test
    fun invalidPixelsGiveNoScore() {
        val nT = 10; val nS = 31
        val p = profiles(nT, nS) { _, j -> if (j > 15) Triple(210f, 128f, 128f) else Triple(60f, 128f, 128f) }
        val ok = BooleanArray(nT * nS) { (it % nS) > 15 }  // el exterior cae fuera de la foto
        val sc = DwPath.edgeScores(p[0], p[1], p[2], ok, nT, nS, 210.0, 128.0, 128.0, win = 3)
        assertTrue(sc.all { it == 0f })
    }

    @Test
    fun bestPathRespectsMaxStep() {
        val nT = 40; val nS = 50
        val sc = FloatArray(nT * nS)
        for (t in 0 until nT) sc[t * nS + if (t < 20) 5 else 45] = 10f
        val path = DwPath.bestPath(sc, nT, nS, maxStep = 2, penalty = 0.1f)
        for (t in 1 until nT) assertTrue(abs(path[t] - path[t - 1]) <= 2)
    }

    @Test
    fun borderFitStraightensCurvedTopEdge() {
        // Marco ampliado 600x800, hoja nominal [40, 560) x [50, 750); canto superior combado hasta 14 px hacia dentro
        val mx = 40.0; val my = 50.0; val w = 520.0; val h = 700.0
        fun topY(x: Double) = my + 14.0 * sin(Math.PI * (x - mx) / w)
        val xs = FloatArray(60) { (mx + 10 + it * (w - 20) / 59).toFloat() }
        val top = LineObs(true, xs, FloatArray(60) { topY(xs[it].toDouble()).toFloat() }, weight = 3.0, target = my)
        val bottom = LineObs(true, xs, FloatArray(60) { (my + h - 1).toFloat() }, weight = 3.0, target = my + h - 1)
        val ys = FloatArray(60) { (my + 10 + it * (h - 20) / 59).toFloat() }
        val left = LineObs(false, FloatArray(60) { mx.toFloat() }, ys, weight = 3.0, target = mx)
        val right = LineObs(false, FloatArray(60) { (mx + w - 1).toFloat() }, ys, weight = 3.0, target = mx + w - 1)
        val sides = listOf(top, right, bottom, left).mapIndexed { i, l -> DwBorder.Side(i, l, 1.0, DewarpMath.straightnessDev(l), 14.0, 30.0) }
        val why = StringBuilder()
        val m = DwBorder.fit(600.0, 800.0, mx, my, w, h, sides, emptyList(), false, why)
        assertNotNull(why.toString(), m)
        m!!
        assertTrue(m.beyond)
        // Fila superior de la salida, centro: debe tomar la hoja en el canto combado (y ≈ my + 14)
        val a = m.gw / 2
        val ySrc = m.map[2 * a + 1] * (h - 1) + my
        assertEquals(topY(mx + w / 2), ySrc.toDouble(), 2.5)
        // Fila inferior: canto recto
        val b = m.gh - 1
        val yBot = m.map[2 * (b * m.gw + a) + 1] * (h - 1) + my
        assertEquals(my + h - 1, yBot.toDouble(), 1.5)
    }

    @Test
    fun borderFitRejectsInconsistentBorders() {
        // Canto izquierdo que se cruza con el derecho: plegado (jacobiano) o cantos que no encajan
        val mx = 40.0; val my = 50.0; val w = 520.0; val h = 700.0
        val ys = FloatArray(40) { (my + 10 + it * (h - 20) / 39).toFloat() }
        val left = LineObs(false, FloatArray(40) { (mx + 450 * it / 39.0).toFloat() }, ys, weight = 3.0, target = mx)
        val right = LineObs(false, FloatArray(40) { (mx + w - 1).toFloat() }, ys, weight = 3.0, target = mx + w - 1)
        val sides = listOf(DwBorder.Side(1, right, 1.0, 0.0, 0.0, 30.0), DwBorder.Side(3, left, 1.0, DewarpMath.straightnessDev(left), 400.0, 30.0))
        assertNull(DwBorder.fit(600.0, 800.0, mx, my, w, h, sides, emptyList(), false, StringBuilder()))
    }

    @Test
    fun dropLossyRemovesSideThatCutsContent() {
        val mx = 10; val my = 10
        val xs = FloatArray(20) { (mx + it * 20).toFloat() }
        // canto superior detectado 30 px hacia dentro del lado nominal
        val top = LineObs(true, xs, FloatArray(20) { (my + 30).toFloat() }, target = my.toDouble())
        val side = DwBorder.Side(0, top, 1.0, 0.0, 30.0, 30.0)
        // contenido (en coordenadas del plano no ampliado) a y = 10: queda fuera del canto
        val content = FloatArray(3 * 40) { k -> when (k % 3) { 0 -> 50f + k; 1 -> 10f; else -> 1f } }
        assertTrue(DwBorder.dropLossy(listOf(side), content, mx, my).isEmpty())
        val inside = FloatArray(3 * 40) { k -> when (k % 3) { 0 -> 50f + k; 1 -> 60f; else -> 1f } }
        assertEquals(1, DwBorder.dropLossy(listOf(side), inside, mx, my).size)
    }

    @Test
    fun dropCuttingRemovesSideThatCutsALineOfThePage() {
        // canto derecho detectado en x = 300 (hacia dentro); un renglón de la hoja llega hasta x = 340
        val ys = FloatArray(30) { (20 + it * 20).toFloat() }
        val right = LineObs(false, FloatArray(30) { 300f }, ys, target = 360.0)
        val side = DwBorder.Side(1, right, 1.0, 0.0, 60.0, 30.0)
        val row = LineObs(true, FloatArray(40) { (40 + it * 7.5).toFloat() }, FloatArray(40) { 200f })
        assertTrue(DwBorder.dropCutting(listOf(side), listOf(row)).isEmpty())
        // renglón que termina antes del canto, o línea paralela al canto: el lado se conserva
        val short = LineObs(true, FloatArray(30) { (40 + it * 8).toFloat() }, FloatArray(30) { 200f })
        val vertical = LineObs(false, FloatArray(30) { 340f }, ys)
        assertEquals(1, DwBorder.dropCutting(listOf(side), listOf(short, vertical)).size)
        // las guías poco exactas (inicios de renglón a mano) no deciden
        val loose = LineObs(true, row.x, row.y, loose = true)
        assertEquals(1, DwBorder.dropCutting(listOf(side), listOf(loose)).size)
    }

    @Test
    fun borderFitReportsWhichSidesAreSheetEdges() {
        val mx = 40.0; val my = 50.0; val w = 520.0; val h = 700.0
        fun topY(x: Double) = my + 10.0 * sin(Math.PI * (x - mx) / w)
        val xs = FloatArray(60) { (mx + 10 + it * (w - 20) / 59).toFloat() }
        val top = LineObs(true, xs, FloatArray(60) { topY(xs[it].toDouble()).toFloat() }, weight = 3.0, target = my)
        val ys = FloatArray(60) { (my + 10 + it * (h - 20) / 59).toFloat() }
        val left = LineObs(false, FloatArray(60) { mx.toFloat() }, ys, weight = 3.0, target = mx)
        val sides = listOf(DwBorder.Side(0, top, 1.0, DewarpMath.straightnessDev(top), 10.0, 30.0), DwBorder.Side(3, left, 1.0, 0.0, 0.0, 30.0))
        val m = DwBorder.fit(600.0, 800.0, mx, my, w, h, sides, emptyList(), false, StringBuilder())
        assertNotNull(m)
        // superior (bit 0) e izquierdo (bit 3); los lados nominales del recorte no cuentan como cantos
        assertEquals(1 or 8, m!!.sheetSides)
    }
}
