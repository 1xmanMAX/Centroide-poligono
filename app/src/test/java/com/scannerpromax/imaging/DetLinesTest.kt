package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DetLinesTest {

    /** Segmentos de los 4 lados de un rectángulo (x0,y0)-(x1,y1), cada lado partido en dos tramos con un hueco. */
    private fun rectSegs(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
        return intArrayOf(
            x0, y0, mx - 5, y0, mx + 5, y0, x1, y0,       // arriba (dos tramos)
            x1, y0, x1, my - 5, x1, my + 5, x1, y1,       // derecha
            x1, y1, mx + 5, y1, mx - 5, y1, x0, y1,       // abajo
            x0, y1, x0, my + 5, x0, my - 5, x0, y0,       // izquierda
        )
    }

    @Test fun agrupaTramosColinealesEnUnaRecta() {
        val segs = rectSegs(50, 40, 250, 300)
        val lines = DetLines.cluster(segs, segs.size / 4, Math.toRadians(2.5), 3.0, 20)
        assertEquals(4, lines.size)
        // cada recta suma la longitud de sus dos tramos
        val horiz = lines.filter { abs(it.theta - Math.PI / 2) < 0.05 }
        assertEquals(2, horiz.size)
        for (l in horiz) assertEquals(190.0, l.length, 1.0)
    }

    @Test fun rectaConThetaCercaDeCeroYDePiEsLaMisma() {
        // Vertical x = 100 trazada hacia abajo y hacia arriba con una inclinación mínima a ambos lados
        val segs = intArrayOf(100, 10, 101, 200, 101, 220, 100, 400)
        val lines = DetLines.cluster(segs, 2, Math.toRadians(2.5), 3.0, 10)
        assertEquals(1, lines.size)
    }

    @Test fun cuatroRectasDanElRectangulo() {
        val segs = rectSegs(50, 40, 250, 300)
        val lines = DetLines.cluster(segs, segs.size / 4, Math.toRadians(2.5), 3.0, 20)
        val quads = DetLines.quads(lines, 320, 400)
        assertEquals(1, quads.size)
        val q = quads[0]
        val expected = floatArrayOf(50f, 40f, 250f, 40f, 250f, 300f, 50f, 300f)
        for (i in 0 until 8) assertEquals(expected[i], q[i], 1.5f)
    }

    @Test fun descartaCuadrilaterosFueraDeLaImagenOPequenos() {
        val segs = rectSegs(50, 40, 250, 300)
        val lines = DetLines.cluster(segs, segs.size / 4, Math.toRadians(2.5), 3.0, 20)
        // Imagen más pequeña: las esquinas quedan fuera (más allá de la holgura)
        assertTrue(DetLines.quads(lines, 200, 200).isEmpty())
        // Área mínima mayor que la del rectángulo
        assertTrue(DetLines.quads(lines, 320, 400, minArea = 0.5).isEmpty())
    }

    @Test fun ordenConvexo() {
        val o = DetLines.order(floatArrayOf(250f, 300f, 50f, 40f, 50f, 300f, 250f, 40f))
        assertNotNull(o)
        val expected = floatArrayOf(50f, 40f, 250f, 40f, 250f, 300f, 50f, 300f)
        for (i in 0 until 8) assertEquals(expected[i], o!![i], 1e-4f)
        // Tres puntos alineados: no es un cuadrilátero
        assertNull(DetLines.order(floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f, 10f, 10f)))
    }

    @Test fun interseccion() {
        val a = DetLines.Line(0.0, 30.0, 1.0)            // x = 30
        val b = DetLines.Line(Math.PI / 2, 70.0, 1.0)    // y = 70
        val p = DetLines.intersect(a, b)!!
        assertEquals(30.0, p[0], 1e-9); assertEquals(70.0, p[1], 1e-9)
        assertNull(DetLines.intersect(a, DetLines.Line(0.0, 80.0, 1.0)))
    }
}
