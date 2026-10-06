package com.scannerpromax

import com.scannerpromax.imaging.DocumentDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.opencv.core.Point

class CornerOrderTest {
    private val expected = floatArrayOf(10f, 20f, 210f, 25f, 205f, 320f, 5f, 310f)

    private fun pts(vararg idx: Int): Array<Point> {
        val all = arrayOf(Point(10.0, 20.0), Point(210.0, 25.0), Point(205.0, 320.0), Point(5.0, 310.0))
        return Array(4) { all[idx[it]] }
    }

    @Test fun ordenYaCorrecto() = assertArrayEquals(expected, DocumentDetector.orderPoints(pts(0, 1, 2, 3)), 1e-4f)

    @Test fun ordenAntihorario() = assertArrayEquals(expected, DocumentDetector.orderPoints(pts(0, 3, 2, 1)), 1e-4f)

    @Test fun ordenMezclado() = assertArrayEquals(expected, DocumentDetector.orderPoints(pts(2, 0, 3, 1)), 1e-4f)

    @Test fun best4EligeCuadrilateroMaximo() {
        // Octágono-ish: los 4 extremos más un punto interior casi colineal
        val p = arrayOf(
            Point(10.0, 20.0), Point(110.0, 21.0), Point(210.0, 25.0), Point(205.0, 320.0), Point(5.0, 310.0),
        )
        assertArrayEquals(expected, DocumentDetector.best4(p), 1e-4f)
    }
}
