package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Combinación renglones + modelo (sin OpenCV) y dirección de los renglones de [DetPaper.textAxis]. */
class TextOrientationDecideTest {

    private fun res(rot: Int, vertical: Boolean?, sure: Boolean) = TextOrientation.Result(rot, vertical, if (rot == 270) -0.5 else 0.5, 20, sure, 0.1, 0.8)
    // probabilidades de que la imagen TENGA un giro horario 0/90/180/270
    private fun has(p0: Float, p90: Float, p180: Float, p270: Float) = floatArrayOf(p0, p90, p180, p270)

    @Test fun sinModelo_seUsaElAnalisisSoloSiEsSeguro() {
        assertEquals(270, TextOrientation.decide(res(270, true, true), null))
        assertEquals(0, TextOrientation.decide(res(0, true, false), null))
        assertEquals(0, TextOrientation.decide(res(0, null, false), null))
        assertEquals(180, TextOrientation.decide(res(180, false, true), null))
    }

    @Test fun modeloContundenteEnContra_vetaElGiro() {
        // el análisis dice girar 270 (la imagen TIENE 90); el modelo dice con fuerza que TIENE 270 (habría que girar 90)
        assertEquals(0, TextOrientation.decide(res(270, true, true), has(0.02f, 0.05f, 0.03f, 0.9f)))
        assertEquals(270, TextOrientation.decide(res(270, true, true), has(0.1f, 0.7f, 0.1f, 0.1f)))
    }

    @Test fun renglonesVerticalesSinSentido_decideElModeloSiEsClaro() {
        assertEquals(90, TextOrientation.decide(res(0, true, false), has(0.05f, 0.05f, 0.1f, 0.8f)))
        assertEquals(270, TextOrientation.decide(res(0, true, false), has(0.05f, 0.75f, 0.1f, 0.1f)))
        assertEquals(0, TextOrientation.decide(res(0, true, false), has(0.3f, 0.35f, 0.05f, 0.3f)))
        // eje dudoso: nunca se gira aunque el modelo lo pida
        assertEquals(0, TextOrientation.decide(res(0, null, false), has(0.0f, 0.95f, 0.0f, 0.05f)))
    }

    @Test fun ciento80_necesitaAcuerdoDelModelo() {
        assertEquals(0, TextOrientation.decide(res(180, false, true), has(0.8f, 0.05f, 0.1f, 0.05f)))
        assertEquals(180, TextOrientation.decide(res(180, false, true), has(0.1f, 0.05f, 0.8f, 0.05f)))
    }

    /** Renglones sintéticos (trazos cortos alineados) inclinados [deg]: textAxis devuelve la inclinación módulo 90. */
    private fun lines(w: Int, h: Int, deg: Double): ByteArray {
        val b = ByteArray(w * h)
        val t = Math.toRadians(deg); val c = cos(t); val s = sin(t)
        var row = 0
        while (row < 24) {
            val v0 = 30.0 + row * 18
            var u = 30.0
            while (u < w - 60) {
                // "palabra": trazo de 25 px de largo y 4 de alto
                for (du in 0 until 25) for (dv in 0 until 4) {
                    val x = ((u + du) * c - (v0 + dv) * s + w * 0.1).toInt(); val y = ((u + du) * s + (v0 + dv) * c).toInt()
                    if (x in 0 until w && y in 0 until h) b[y * w + x] = 1
                }
                u += 32
            }
            row++
        }
        return b
    }

    @Test fun ejeDeLosRenglones() {
        for (deg in listOf(0.0, 3.0, -4.0, 88.0, 92.0)) {
            val a = DetPaper.textAxis(lines(500, 500, deg), 500, 500)
            assertNotNull(a)
            var d = abs(a!! - deg) % 90.0; d = minOf(d, 90 - d)
            assertTrue("eje $a para $deg", d <= 0.75)
        }
        assertNull(DetPaper.textAxis(ByteArray(500 * 500), 500, 500))
    }
}
