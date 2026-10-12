package com.scannerpromax.imaging

import com.scannerpromax.imaging.DeblurMath.Cov
import com.scannerpromax.imaging.DeblurMath.eig
import com.scannerpromax.imaging.DeblurMath.mapEig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class DeblurMathTest {

    /** σ direccional exacta de una covarianza en la dirección de la normal φ. */
    private fun dirSigma(c: Cov, phi: Double) = sqrt(c.a * cos(phi) * cos(phi) + 2 * c.b * cos(phi) * sin(phi) + c.c * sin(phi) * sin(phi))

    @Test
    fun fitCovRecoversAnisotropicMotionBlur() {
        // Movimiento a 30°: σ 2.0 a lo largo, 0.7 a lo ancho
        val truth = DeblurMath.fromEig(4.0, 0.49, PI / 6)
        val sig = DoubleArray(4) { dirSigma(truth, DeblurMath.DIRS[it]) }
        val fit = DeblurMath.fitCov(sig, doubleArrayOf(100.0, 80.0, 120.0, 90.0))!!
        assertEquals(truth.a, fit.a, 1e-9); assertEquals(truth.b, fit.b, 1e-9); assertEquals(truth.c, fit.c, 1e-9)
        val e = fit.eig()
        assertEquals(4.0, e.l1, 1e-9); assertEquals(0.49, e.l2, 1e-9); assertEquals(PI / 6, e.theta, 1e-9)
    }

    @Test
    fun fitCovFallsBackToIsotropicAndNull() {
        val iso = DeblurMath.fitCov(doubleArrayOf(1.0, Double.NaN, 2.0, Double.NaN), doubleArrayOf(10.0, 0.0, 10.0, 0.0))!!
        assertEquals(2.5, iso.a, 1e-9); assertEquals(0.0, iso.b, 1e-12); assertEquals(2.5, iso.c, 1e-9)
        assertNull(DeblurMath.fitCov(DoubleArray(4) { Double.NaN }, DoubleArray(4)))
    }

    @Test
    fun mapEigClampsKeepingOrientation() {
        val c = DeblurMath.fromEig(5.0, -1.0, 0.4)
        val m = c.mapEig { it.coerceIn(0.0, 4.0) }
        val e = m.eig()
        assertEquals(4.0, e.l1, 1e-9); assertEquals(0.0, e.l2, 1e-9); assertEquals(0.4, e.theta, 1e-9)
    }

    @Test
    fun jacobianOfScaleAndProjectiveMaps() {
        // salida -> foto = mitad: la salida está ampliada x2
        val j = DeblurMath.jacobianOutFromSrc(doubleArrayOf(0.5, 0.0, 3.0, 0.0, 0.5, 7.0, 0.0, 0.0, 1.0), 100.0, 50.0)
        assertArrayEquals(doubleArrayOf(2.0, 0.0, 0.0, 2.0), j, 1e-12)
        assertEquals(2.0, DeblurMath.maxStretch(j), 1e-12)
        val g = DeblurMath.geometricCov(j, 0.6)
        assertEquals(1.44, g.a, 1e-12); assertEquals(0.0, g.b, 1e-12); assertEquals(1.44, g.c, 1e-12)
        // Proyectiva: comparar con diferencias finitas del mapa directo
        val h = doubleArrayOf(0.9, 0.05, 12.0, -0.02, 1.1, 5.0, 1e-4, 2e-4, 1.0)
        fun f(x: Double, y: Double): DoubleArray { val w = h[6] * x + h[7] * y + h[8]; return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w) }
        val x = 800.0; val y = 1200.0; val e = 1e-3
        val dxs = DoubleArray(2) { (f(x + e, y)[it] - f(x - e, y)[it]) / (2 * e) }
        val dys = DoubleArray(2) { (f(x, y + e)[it] - f(x, y - e)[it]) / (2 * e) }
        // d(origen)/d(salida) = [[dxs0, dys0], [dxs1, dys1]]; su inversa debe ser el jacobiano devuelto
        val jo = DeblurMath.jacobianOutFromSrc(h, x, y)
        val p00 = jo[0] * dxs[0] + jo[1] * dxs[1]; val p01 = jo[0] * dys[0] + jo[1] * dys[1]
        val p10 = jo[2] * dxs[0] + jo[3] * dxs[1]; val p11 = jo[2] * dys[0] + jo[3] * dys[1]
        assertEquals(1.0, p00, 1e-6); assertEquals(0.0, p01, 1e-6); assertEquals(0.0, p10, 1e-6); assertEquals(1.0, p11, 1e-6)
    }

    @Test
    fun quadFitIgnoresOutlierTiles() {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val v = ArrayList<Double>()
        val c = doubleArrayOf(0.5, 0.3, -1.2, 0.4, 0.1, 0.8)
        for (i in 0 until 8) for (j in 0 until 6) {
            val x = j / 5.0 - 0.5; val y = i / 7.0 - 0.5
            xs.add(x); ys.add(y); v.add(DeblurMath.evalQuad(c, x, y))
        }
        v[10] += 5.0   // tesela atípica (contenido distinto)
        val fit = DeblurMath.quadFit(xs.toDoubleArray(), ys.toDoubleArray(), v.toDoubleArray(), DoubleArray(xs.size) { 1.0 }, iters = 6)!!
        for (k in 0 until 6) assertEquals(c[k], fit[k], 0.08)
    }

    @Test
    fun kMeansSeparatesBlurClasses() {
        val pts = List(10) { Cov(0.3 + it * 0.01, 0.0, 0.3) } + List(6) { Cov(2.5, 0.2, 3.0 + it * 0.02) }
        val (lab, centers) = DeblurMath.kMeans(pts, 2)
        assertEquals(2, centers.size)
        assertTrue((0 until 10).all { lab[it] == lab[0] })
        assertTrue((10 until 16).all { lab[it] == lab[10] && lab[it] != lab[0] })
        // Un solo punto distinto: k se reduce
        assertEquals(1, DeblurMath.kMeans(List(4) { Cov(1.0, 0.0, 1.0) }, 3).second.size)
    }

    @Test
    fun wienerGainIsBoundedAndStopsAboveSourceNyquist() {
        val c = Cov(2.0, 0.0, 2.0)
        val k = 0.015; val gMax = 4.0
        assertEquals(1.0 / (1.0 + k), DeblurMath.wienerGain(c, 0.0, 0.0, k, 0.5, gMax), 1e-12)
        var prev = 0.0; var peak = 0.0
        for (i in 0..50) {
            val f = i / 100.0
            val g = DeblurMath.wienerGain(c, f, 0.0, k, 0.5, gMax)
            assertTrue(g <= gMax + 1e-12)
            peak = maxOf(peak, g)
            if (f < 0.1) assertTrue(g >= prev - 1e-12)   // refuerza las frecuencias medias
            prev = g
        }
        assertTrue(peak > 3.0)
        // Por encima del Nyquist del origen ampliado (fCut + 0.06) nunca refuerza
        for (i in 0..20) assertTrue(DeblurMath.wienerGain(c, 0.25 + i * 0.01, 0.1, k, 0.15, gMax) <= 1.0 + 1e-12)
        // Sin desenfoque: ganancia casi plana (ningún realce)
        assertEquals(1.0 / (1.0 + k), DeblurMath.wienerGain(Cov(0.0, 0.0, 0.0), 0.3, 0.2, k, 0.5, gMax), 1e-12)
        // Anisótropa: refuerza más a lo largo del movimiento
        val m = DeblurMath.fromEig(3.0, 0.1, 0.0)
        assertTrue(DeblurMath.wienerGain(m, 0.12, 0.0, k, 0.5, gMax) > 1.8 * DeblurMath.wienerGain(m, 0.0, 0.12, k, 0.5, gMax))
        // Coherencia con la transformada de la gaussiana
        val h = exp(-2 * PI * PI * 2.0 * 0.05 * 0.05)
        assertEquals(h / (h * h + k), DeblurMath.wienerGain(c, 0.05, 0.0, k, 0.5, gMax), 1e-12)
    }

    @Test
    fun invertCurveAndPerspectiveWeight() {
        val stat = doubleArrayOf(0.6, 0.76, 1.17, 1.48, 2.13)
        val sig = doubleArrayOf(0.0, 0.75, 1.5, 2.25, 3.0)
        assertEquals(0.0, DeblurMath.invertCurve(0.5, stat, sig), 1e-12)
        assertEquals(0.75, DeblurMath.invertCurve(0.76, stat, sig), 1e-12)
        assertEquals(1.125, DeblurMath.invertCurve((0.76 + 1.17) / 2, stat, sig), 1e-9)
        assertEquals(3.0, DeblurMath.invertCurve(5.0, stat, sig), 1e-12)
        // Escaneo frontal: sin peso; perspectiva fuerte: peso completo
        assertEquals(0.0, DeblurMath.perspectiveWeight(doubleArrayOf(1.0, 1.02, 1.05)), 1e-12)
        assertEquals(1.0, DeblurMath.perspectiveWeight(doubleArrayOf(0.8, 1.0, 1.3)), 1e-12)
        val mid = DeblurMath.perspectiveWeight(doubleArrayOf(1.0, 1.155))
        assertTrue(abs(mid - 0.5) < 1e-9)
    }

    @Test
    fun solveAndPercentile() {
        val x = DeblurMath.solve(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)), doubleArrayOf(3.0, 5.0))
        assertNotNull(x)
        assertEquals(0.8, x!![0], 1e-12); assertEquals(1.4, x[1], 1e-12)
        assertNull(DeblurMath.solve(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)), doubleArrayOf(1.0, 2.0)))
        assertEquals(2.0, DeblurMath.percentile(doubleArrayOf(4.0, 1.0, 3.0, 2.0, 0.0), 0.5), 1e-12)
        assertEquals(2.5, DeblurMath.median(floatArrayOf(1f, 4f, 2f, 3f, 99f), 4), 1e-12)
    }
}
