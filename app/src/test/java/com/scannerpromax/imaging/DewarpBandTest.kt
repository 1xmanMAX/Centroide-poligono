package com.scannerpromax.imaging

import com.scannerpromax.imaging.DewarpMath.LineObs
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regresión de "IllegalStateException: fuera de banda": muestras consecutivas de una línea a varias celdas. */
class DewarpBandTest {

    @Test
    fun farApartSamplesDoNotLeaveTheBand() {
        val w = 1200.0; val h = 900.0
        val lines = ArrayList<LineObs>()
        // Recta con dos muestras muy separadas e inclinadas (enlace a través de un hueco grande)
        lines.add(LineObs(true, floatArrayOf(10f, 1150f), floatArrayOf(20f, 820f)))
        lines.add(LineObs(false, floatArrayOf(30f, 1100f), floatArrayOf(10f, 880f)))
        // y unas rectas normales
        for (k in 1..8) lines.add(LineObs(true, FloatArray(40) { it * 30f }, FloatArray(40) { k * 100f }))
        val f = DewarpMath.solveField(w, h, 29, 22, lines, DewarpMath.Weights())
        val o = DoubleArray(6)
        for (y in 0..9) for (x in 0..12) {
            f.eval(x * 100.0, y * 100.0, o)
            assertTrue(o[0].isFinite() && o[1].isFinite())
        }
    }
}
