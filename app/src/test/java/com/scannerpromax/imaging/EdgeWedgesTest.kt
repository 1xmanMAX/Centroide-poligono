package com.scannerpromax.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sin

class EdgeWedgesTest {

    private val n = 400
    private val cross = 300

    /** Profundidades de una cuña: borde curvo de la hoja que se separa del recorte entre [a] y [b]. */
    private fun wedge(a: Int, b: Int, depth: Double) = IntArray(n) { t ->
        if (t <= a || t >= b) 0 else (depth * sin(Math.PI * (t - a) / (b - a))).roundToInt()
    }

    @Test
    fun depthsCountRunFromEachBorderToleratingLead() {
        // 6 x 4: columna 0 pintada de papel (lead), columnas 1-2 fondo en las filas 1-2
        val w = 6; val h = 4
        val np = ByteArray(w * h)
        for (y in 1..2) for (x in 1..2) np[y * w + x] = -1
        assertArrayEquals(intArrayOf(0, 0, 0, 0), EdgeWedges.depths(np, w, h, 0, lead = 0))
        assertArrayEquals(intArrayOf(0, 3, 3, 0), EdgeWedges.depths(np, w, h, 0, lead = 2))
        // Desde la derecha no hay fondo en los primeros 2 px
        assertArrayEquals(intArrayOf(0, 0, 0, 0), EdgeWedges.depths(np, w, h, 1, lead = 2))
        // Desde arriba (columna 1): la fila 0 es papel y se tolera
        assertEquals(3, EdgeWedges.depths(np, w, h, 2, lead = 1)[1])
    }

    @Test
    fun taperingWedgeIsFilled() {
        val d = wedge(50, 350, 60.0)
        val acc = EdgeWedges.accept(d, cross)
        for (t in 60 until 340) assertEquals(d[t], acc[t])
    }

    @Test
    fun abruptBlockLikePhotoIsKept() {
        // Foto pegada al borde: empieza y acaba a toda profundidad
        val d = IntArray(n) { if (it in 100..250) 60 else 0 }
        assertTrue(EdgeWedges.accept(d, cross).all { it == 0 })
    }

    @Test
    fun constantBandAlongWholeSideIsKeptButCurvedEdgeIsFilled() {
        val band = IntArray(n) { 40 }
        assertTrue(EdgeWedges.accept(band, cross).all { it == 0 })
        // Borde curvo a lo largo de todo el lado: casi 0 en el centro, hondo en los extremos
        val curved = IntArray(n) { t -> (5 + 80 * ((t - n / 2.0) / (n / 2.0)).let { it * it }).roundToInt() }
        val acc = EdgeWedges.accept(curved, cross)
        assertEquals(curved[0], acc[0])
        assertEquals(curved[n - 1], acc[n - 1])
    }

    @Test
    fun tooDeepOrSliverIsKept() {
        assertTrue(EdgeWedges.accept(wedge(50, 350, cross * 0.5), cross).all { it == 0 })
        assertTrue(EdgeWedges.accept(wedge(50, 350, cross * 0.03), cross).all { it == 0 })
    }

    @Test
    fun shortGapsAreBridged() {
        val d = intArrayOf(0, 10, 12, 0, 0, 14, 10, 0, 0, 0, 0, 0, 9)
        val r = EdgeWedges.bridgeGaps(d, zero = 1, maxGap = 2)
        assertArrayEquals(intArrayOf(0, 10, 12, 12, 12, 14, 10, 0, 0, 0, 0, 0, 9), r)
    }

    @Test
    fun cornerPositionsAreDelegated() {
        // Las primeras 30 posiciones recorren el lado vecino entero (fondo de dos lados unido en la esquina)
        val d = wedge(-200, 350, 60.0)
        for (t in 0 until 30) d[t] = cross
        val acc = EdgeWedges.accept(d, cross)
        assertEquals(30, EdgeWedges.cornerRun(acc, fromStart = true))
        assertEquals(0, EdgeWedges.cornerRun(acc, fromStart = false))
        assertEquals(d[100], acc[100])
    }
}
