package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Lienzo ampliado: el enderezado y la hoja curvada no deben recortar el contenido junto a los bordes. */
class CanvasExpansionTest {

    /** Campo de traslación pura: (u, v) = (x + dx, y + dy) sobre [0,w]x[0,h]. */
    private fun shift(w: Double, h: Double, dx: Double, dy: Double) = DewarpMath.Field(
        2, 2, w, h,
        doubleArrayOf(dx, w + dx, dx, w + dx),
        doubleArrayOf(dy, dy, h + dy, h + dy),
    )

    @Test
    fun rescuedPointsKeepWholeComponentsPushedOut() {
        val f = shift(200.0, 100.0, -10.0, 0.0)
        // Componente 1: una letra junto al borde izquierdo (sus primeros píxeles salen del lienzo); componente 2: lejos
        val pts = floatArrayOf(
            5f, 50f, 1f, 8f, 52f, 1f, 20f, 50f, 1f,
            100f, 50f, 2f, 120f, 40f, 2f,
        )
        val r = DewarpMath.rescuedPoints(f, pts, 100)
        assertEquals(6, r.size)   // los 3 puntos de la componente 1, enteros
        assertEquals(-5.0, r[0].toDouble(), 1e-6)
        assertEquals(10.0, r[4].toDouble(), 1e-6)
        val b = DewarpMath.bounds(r, 200.0, 100.0)
        assertEquals(-5.0, b[0], 1e-6)
        assertEquals(0.0, b[1], 1e-6)
        assertEquals(200.0, b[2], 1e-6)
        assertEquals(100.0, b[3], 1e-6)
        // Nada sale: no hay rescate
        assertEquals(0, DewarpMath.rescuedPoints(shift(200.0, 100.0, 0.0, 0.0), pts, 100).size)
    }

    @Test
    fun invertCoversExpandedCanvas() {
        val f = shift(200.0, 100.0, -10.0, 4.0)
        val gw = 23; val gh = 12
        val inv = DewarpMath.invert(f, gw, gh, -10.0, 0.0, 200.0, 104.0)
        assertNotNull(inv)
        // Nodo (0,0) de la salida ampliada = U -10, V 0 -> fuente (0, -4); último nodo = (200, 104) -> (210, 100)
        assertEquals(0.0, inv!![0], 1e-3); assertEquals(-4.0, inv[1], 1e-3)
        val last = 2 * (gw * gh - 1)
        assertEquals(210.0, inv[last], 1e-3); assertEquals(100.0, inv[last + 1], 1e-3)
    }

    @Test
    fun rotatedCanvasContainsTheWholeRotatedPage() {
        assertEquals(2000 to 2700, Cleanup.rotatedCanvas(2000, 2700, 0.0))
        assertEquals(2700 to 2000, Cleanup.rotatedCanvas(2000, 2700, 90.0))
        for (deg in doubleArrayOf(-10.0, -3.5, -0.4, 0.7, 2.0, 6.0)) {
            val w = 2000; val h = 2700
            val (nw, nh) = Cleanup.rotatedCanvas(w, h, deg)
            assertTrue(nw >= w && nh >= h)
            // Esquinas giradas alrededor del centro y centradas en el lienzo nuevo: todas dentro (tolerancia 0.5 px)
            val a = Math.toRadians(deg); val c = cos(a); val s = sin(a)
            for ((x, y) in listOf(0.0 to 0.0, w.toDouble() to 0.0, 0.0 to h.toDouble(), w.toDouble() to h.toDouble())) {
                val dx = x - w / 2.0; val dy = y - h / 2.0
                val rx = c * dx + s * dy + nw / 2.0; val ry = -s * dx + c * dy + nh / 2.0
                assertTrue("$deg: $rx", rx >= -0.5 && rx <= nw + 0.5)
                assertTrue("$deg: $ry", ry >= -0.5 && ry <= nh + 0.5)
            }
        }
    }
}
