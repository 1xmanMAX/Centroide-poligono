package com.scannerpromax.imaging

import com.scannerpromax.imaging.TextGuides.Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

class MarginAlignTest {

    private val med = 14.0
    private val pitch = 32.0
    private val w = 1100.0
    private val h = 1600.0

    /**
     * Página de texto: [n] renglones desde y = 150; inicio x = [xl] + [bl]·(y − 800) (+ ruido), final justificado
     * x = [xr] + [br]·(y − 800) o en bandera; cada 6º renglón con sangría y el último de cada párrafo corto. Todo girado
     * [deg] grados alrededor del centro (los renglones salen con pendiente).
     */
    private fun page(
        n: Int = 36, xl: Double = 150.0, bl: Double = 0.0, xr: Double = 950.0, br: Double = 0.0,
        justified: Boolean = true, noise: Double = 0.3, deg: Double = 0.0, seed: Long = 1,
    ): Triple<List<Seg>, DoubleArray, DoubleArray> {
        val rnd = Random(seed)
        val a = Math.toRadians(deg); val c = cos(a); val s = sin(a)
        fun rot(x: Double, y: Double): Pair<Float, Float> {
            val dx = x - w / 2; val dy = y - h / 2
            return ((c * dx - s * dy + w / 2).toFloat()) to ((s * dx + c * dy + h / 2).toFloat())
        }
        val segs = ArrayList<Seg>()
        for (k in 0 until n) {
            val y = 150.0 + k * pitch
            val l = xl + bl * (y - 800); val r = xr + br * (y - 800)
            val indent = if (k % 6 == 1) 40.0 else 0.0
            val xs = l + indent + rnd.nextGaussian() * noise
            val xe = when {
                k % 6 == 0 -> l + 0.4 * (r - l)
                justified -> r + rnd.nextGaussian() * noise
                else -> r - 10 - rnd.nextDouble() * 120
            }
            val (x0, y0) = rot(xs, y); val (x1, y1) = rot(xe, y)
            segs.add(Seg(k, x0, y0, x1, y1))
        }
        val slopes = DoubleArray(n) { Math.tan(a) + rnd.nextGaussian() * 0.0005 }
        val lens = DoubleArray(n) { 600.0 }
        return Triple(segs, slopes, lens)
    }

    private fun est(p: Triple<List<Seg>, DoubleArray, DoubleArray>) = MarginAlign.estimate(p.first, p.second, p.third, med, pitch, w, h)

    /** Pendiente dx/dy de los puntos de inicio (o final) tras aplicar [m]. */
    private fun slopeAfter(m: DoubleArray, segs: List<Seg>, start: Boolean, skip: (Int) -> Boolean): Double {
        val o = DoubleArray(2)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (s in segs) {
            if (skip(s.row)) continue
            if (start) MarginAlign.apply(m, s.xs.toDouble(), s.ys.toDouble(), o) else MarginAlign.apply(m, s.xe.toDouble(), s.ye.toDouble(), o)
            xs.add(o[0]); ys.add(o[1])
        }
        val my = ys.average(); val mx = xs.average()
        var syy = 0.0; var sxy = 0.0
        for (i in xs.indices) { syy += (ys[i] - my) * (ys[i] - my); sxy += (ys[i] - my) * (xs[i] - mx) }
        return sxy / syy
    }

    @Test
    fun paginaRectaNoSeToca() {
        val r = est(page())
        assertNotNull(r)
        assertFalse(r!!.info, r.act)
        assertNotNull(r.left)
        assertEquals(150.0, r.left!!.x(800.0), 1.0)
    }

    @Test
    fun cizallaDelMargenIzquierdoSeCorrige() {
        val p = page(bl = 0.025, justified = false)
        val r = est(p)!!
        assertTrue(r.info, r.act)
        assertNull("bandera: sin canto derecho", r.right)
        // Inicios alineados (sin sangrías ni renglones cortos de título) verticales tras la corrección
        val b = slopeAfter(r.m!!, p.first, true) { it % 6 == 1 }
        assertEquals(0.0, b, 0.002)
    }

    @Test
    fun trapecioConTextoJustificado() {
        val p = page(bl = 0.02, br = -0.02)
        val r = est(p)!!
        assertTrue(r.info, r.act)
        assertNotNull(r.right)
        assertEquals(0.0, slopeAfter(r.m!!, p.first, true) { it % 6 == 1 }, 0.002)
        assertEquals(0.0, slopeAfter(r.m!!, p.first, false) { it % 6 == 0 }, 0.002)
    }

    @Test
    fun giroMasCizalla() {
        val p = page(bl = -0.03, br = -0.03, deg = 2.0)
        val r = est(p)!!
        assertTrue(r.info, r.act)
        assertEquals(2.0, r.angle, 0.1)
        assertEquals(0.0, slopeAfter(r.m!!, p.first, true) { it % 6 == 1 }, 0.002)
        // Los renglones siguen horizontales: los dos extremos de un renglón quedan a la misma altura
        val o1 = DoubleArray(2); val o2 = DoubleArray(2)
        val s = p.first[10]
        MarginAlign.apply(r.m!!, s.xs.toDouble(), s.ys.toDouble(), o1); MarginAlign.apply(r.m!!, s.xe.toDouble(), s.ye.toDouble(), o2)
        assertEquals(o1[1], o2[1], 0.5)
    }

    @Test
    fun letraAManoNoSeToca() {
        val r = est(page(bl = 0.03, noise = 6.0, justified = false))
        assertTrue(r == null || !r.act)
    }

    @Test
    fun pocosRenglonesNoSeToca() {
        assertNull(est(page(n = 6, bl = 0.03)))
    }

    @Test
    fun columnasQueDiscrepanSonCizallaLocal() {
        // Cabecera con su columna de valores (x = 450) inclinada y párrafos rectos (x = 150): sin corrección global
        val a = page(n = 30, bl = 0.0, justified = false)
        val segs = ArrayList(a.first)
        val rnd = Random(3)
        for (k in 0 until 18) {
            val y = 150.0 + k * pitch
            segs.add(Seg(100 + k, (450 + 0.04 * (y - 400) + rnd.nextGaussian() * 0.3).toFloat(), y.toFloat(), 900f, y.toFloat()))
        }
        val r = MarginAlign.estimate(segs, a.second + DoubleArray(18), a.third + DoubleArray(18) { 600.0 }, med, pitch, w, h)!!
        assertFalse(r.info, r.act)
        assertTrue(r.info, r.local)
        assertNotNull(r.guide2)
    }

    @Test
    fun trapecioLlevaAmbasRectasAVerticales() {
        val k = MarginAlign.keystone(100.0, 0.02, 900.0, -0.03)
        val o = DoubleArray(2)
        for (y in listOf(-500.0, -100.0, 0.0, 300.0, 700.0)) {
            MarginAlign.apply(k, 100 + 0.02 * y, y, o); assertEquals(100.0, o[0], 1e-6)
            MarginAlign.apply(k, 900 - 0.03 * y, y, o); assertEquals(900.0, o[0], 1e-6)
        }
        // Las horizontales siguen horizontales
        MarginAlign.apply(k, 100.0, 200.0, o); val y1 = o[1]
        MarginAlign.apply(k, 800.0, 200.0, o); assertEquals(y1, o[1], 1e-9)
    }

    @Test
    fun lienzoSinAmpliarConRecorteManual() {
        val p = page(bl = 0.03, br = 0.03)
        val r = est(p)!!
        assertTrue(r.info, r.act)
        val (rw, rh) = Cleanup.rotatedCanvas(2200, 3200, r.angle)
        val fixed = MarginAlign.warpFor(r, 2200, 3200, expand = false)!!
        assertEquals(rw, fixed.w); assertEquals(rh, fixed.h)
        val grown = MarginAlign.warpFor(r, 2200, 3200, expand = true)!!
        assertTrue(grown.w >= fixed.w)
        // Con lienzo ampliado las cuatro esquinas de la hoja caben
        val o = DoubleArray(2)
        for ((x, y) in listOf(0.0 to 0.0, 2200.0 to 0.0, 2200.0 to 3200.0, 0.0 to 3200.0)) {
            MarginAlign.apply(grown.m, x, y, o)
            assertTrue(o[0] >= -1 && o[0] <= grown.w + 1 && o[1] >= -1 && o[1] <= grown.h + 1)
        }
    }

    @Test
    fun correccionCompuestaEnElMapaDeLaMalla() {
        // Mapa identidad (salida = plano) 11 x 15 sobre 1000 x 1400 px; corrección = cizalla
        val gw = 11; val gh = 15; val ow = 1000.0; val oh = 1400.0
        val map = FloatArray(gw * gh * 2) { i -> val n = i / 2; if (i % 2 == 0) (n % gw).toFloat() / (gw - 1) else (n / gw).toFloat() / (gh - 1) }
        val k = MarginAlign.keystone(0.0, 0.02, Double.NaN, Double.NaN).let { s ->
            // centrada en y = 700
            MarginAlign.mul3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 700.0, 0.0, 0.0, 1.0), MarginAlign.mul3(s, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, -700.0, 0.0, 0.0, 1.0)))
        }
        val m2 = MarginAlign.composeMap(map, gw, gh, ow, oh, k)!!
        // Cada nodo de salida toma la fuente k⁻¹·P (con extrapolación lineal en los bordes)
        val ki = MarginAlign.inv3(k)!!
        val o = DoubleArray(2)
        for ((a, b) in listOf(0 to 0, 5 to 7, 10 to 14, 3 to 12)) {
            MarginAlign.apply(ki, a * (ow - 1) / (gw - 1), b * (oh - 1) / (gh - 1), o)
            assertEquals(o[0] / (ow - 1), m2[2 * (b * gw + a)].toDouble(), 1e-4)
            assertEquals(o[1] / (oh - 1), m2[2 * (b * gw + a) + 1].toDouble(), 1e-4)
        }
        assertTrue(MarginAlign.small(k, ow, oh, 0.02))
        assertFalse(MarginAlign.small(k, ow, oh, 0.01))
    }

    @Test
    fun inclinacionDeUnaGuiaCuentaEnLaMalla() {
        val ys = FloatArray(20) { 100f + it * 30f }
        val xs = FloatArray(20) { 200f + 0.03f * ys[it] }
        val g = DewarpMath.LineObs(false, xs, ys, guide = true)
        assertEquals(0.0, DewarpMath.straightnessDev(g), 1e-3)
        assertEquals(0.0, GridDewarp.beforeDev(g, null), 1e-3)
        assertEquals(0.03 * 570 / 2, GridDewarp.beforeDev(g, 0.0), 0.05)
        // Respecto de renglones con la misma inclinación (pendiente −0.03) la guía ya es perpendicular
        assertEquals(0.0, GridDewarp.beforeDev(g, -0.03), 0.05)
        assertTrue(abs(atan(0.03)) > 0)
    }
}
