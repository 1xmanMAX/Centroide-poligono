package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class DeghostMathTest {

    /** Trazos bitonales aleatorios (rectángulos) en una imagen [n] x [n]. */
    private fun strokes(n: Int, seed: Int): Array<DoubleArray> {
        val rnd = Random(seed); val s = Array(n) { DoubleArray(n) }
        repeat(40) {
            val horiz = rnd.nextBoolean()
            val x0 = rnd.nextInt(4, n - 20); val y0 = rnd.nextInt(4, n - 20)
            val len = rnd.nextInt(6, 16); val th = rnd.nextInt(1, 3)
            for (t in 0 until len) for (u in 0 until th) { if (horiz) s[y0 + u][x0 + t] = 1.0 else s[y0 + t][x0 + u] = 1.0 }
        }
        return s
    }

    @Test
    fun cgRecoversDoubleImageKernel() {
        // Imagen doble: 0.7·δ(0) + 0.3·δ(dx=3, dy=1); B = k ∗ S. Gradientes = diferencias hacia delante.
        val n = 96; val r = 4
        val s = strokes(n, 3)
        val b = Array(n) { y -> DoubleArray(n) { x -> 0.7 * s[y][x] + (if (y >= 1 && x >= 3) 0.3 * s[y - 1][x - 3] else 0.0) } }
        fun gx(a: Array<DoubleArray>, y: Int, x: Int) = if (x + 1 < n) a[y][x + 1] - a[y][x] else 0.0
        fun gy(a: Array<DoubleArray>, y: Int, x: Int) = if (y + 1 < n) a[y + 1][x] - a[y][x] else 0.0
        val l = 2 * r; val m = 4 * r + 1; val nc = 2 * r + 1
        val ac = DoubleArray(m * m); val cc = DoubleArray(nc * nc)
        for (y in 0 until n) for (x in 0 until n) {
            val sx = gx(s, y, x); val sy = gy(s, y, x)
            if (sx == 0.0 && sy == 0.0) continue
            for (dy in -l..l) for (dx in -l..l) {
                val yy = y + dy; val xx = x + dx
                if (yy !in 0 until n || xx !in 0 until n) continue
                ac[(dy + l) * m + dx + l] += sx * gx(s, yy, xx) + sy * gy(s, yy, xx)
                if (abs(dy) <= r && abs(dx) <= r) cc[(dy + r) * nc + dx + r] += sx * gx(b, yy, xx) + sy * gy(b, yy, xx)
            }
        }
        val k = DeghostMath.solveCg(ac, cc, r, 1e-4, 200, DeghostMath.directMatvec(ac, r))
        val kc = DeghostMath.cleanKernel(k, r)!!
        assertEquals(0.7, kc[r * nc + r], 0.05)
        assertEquals(0.3, kc[(r + 1) * nc + r + 3], 0.05)
        assertTrue(DeghostMath.width(kc, r) > 1.5)
    }

    @Test
    fun cleanKernelCentersOnPeakAndNormalizes() {
        val r = 3; val n = 2 * r + 1
        val k = DoubleArray(n * n)
        k[(r - 1) * n + r + 2] = 2.0; k[(r - 1) * n + r + 1] = 0.6; k[0] = -0.5; k[n * n - 1] = 0.05
        val c = DeghostMath.cleanKernel(k, r)!!
        assertEquals(1.0, c.sum(), 1e-12)
        assertTrue(c.all { it >= 0 })
        // el pico pasa al centro y el vecino conserva su posición relativa
        assertEquals(2.0 / 2.6, c[r * n + r], 1e-9)
        assertEquals(0.6 / 2.6, c[r * n + r - 1], 1e-9)
        assertNull(DeghostMath.cleanKernel(DoubleArray(n * n) { -1.0 }, r))
    }

    @Test
    fun widthRingAndWeights() {
        val r = 5; val n = 2 * r + 1
        val delta = DoubleArray(n * n).also { it[r * n + r] = 1.0 }
        assertEquals(0.0, DeghostMath.width(delta, r), 1e-12)
        assertEquals(0.0, DeghostMath.ringMass(delta, r), 1e-12)
        val edge = DoubleArray(n * n).also { it[r * n + r] = 0.5; it[r * n + n - 1] = 0.5 }
        assertEquals(0.5, DeghostMath.ringMass(edge, r), 1e-12)
        assertEquals(0.0, DeghostMath.zoneWeight(1.5, 1.2, 0.6, 1.2), 1e-12)
        assertEquals(0.5, DeghostMath.zoneWeight(2.1, 1.2, 0.6, 1.2), 1e-12)
        assertEquals(1.0, DeghostMath.zoneWeight(4.0, 1.2, 0.6, 1.2), 1e-12)
        assertEquals(0.0, DeghostMath.isotropy(10.0, 0.0, 0.0), 1e-12)
        assertEquals(1.0, DeghostMath.isotropy(3.0, 3.0, 0.0), 1e-12)
        val m = DeghostMath.symmetricMean(listOf(edge))
        assertEquals(m[r * n + n - 1], m[r * n], 1e-12)
        assertEquals(1.0, m.sum(), 1e-12)
        assertNotNull(m)
    }

    @Test
    fun partitionIsUnity() {
        val nz = 4; val zs = 300.0
        var v = 0.5
        while (v < nz * zs) {
            var s = 0.0
            for (z in 0 until nz) s += DeghostMath.partition(v, z, zs, nz)
            assertEquals(1.0, s, 1e-9)
            v += 7.3
        }
        assertEquals(1.0, DeghostMath.partition(150.0, 0, zs, nz), 1e-12)
        assertEquals(0.0, DeghostMath.partition(150.0, 1, zs, nz), 1e-12)
    }
}
