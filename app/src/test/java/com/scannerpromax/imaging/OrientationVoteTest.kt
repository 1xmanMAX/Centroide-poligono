package com.scannerpromax.imaging

import com.scannerpromax.ai.jev.CaptureHints
import com.scannerpromax.ai.jev.JevCapture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Voto de orientación: renglones + doc_ori + Jev (reglas de [OrientationVote]). */
class OrientationVoteTest {

    private fun lines(rot: Int, sure: Boolean, vertical: Boolean? = rot == 90 || rot == 270, strips: Boolean = false) =
        TextOrientation.Result(rot, vertical, 0.5, 20, sure, 0.1, 0.8, strips)

    /** doc_ori: probabilidades de que la página TENGA giro horario 0/90/180/270. */
    private fun has(vararg p: Float) = p

    /** Jev / modelo como giro que ENDEREZA. */
    private fun fix(vararg p: Float) = p

    @Test fun sinSenales_noSeGira() {
        assertEquals(0, OrientationVote.decide(null, null, null).rotation)
        assertEquals(0, OrientationVote.decide(lines(0, false, null), null, null).rotation)
    }

    @Test fun soloRenglones_comoAntes() {
        assertEquals(90, OrientationVote.decide(lines(90, true), null, null).rotation)
        assertEquals(0, OrientationVote.decide(lines(0, false, true), null, null).rotation)
        assertEquals(180, OrientationVote.decide(lines(180, true, false), null, null).rotation)
    }

    @Test fun modeloSolo() {
        // la página TIENE 90 horario -> se endereza girando 270
        assertEquals(270, OrientationVote.decide(null, has(0.1f, 0.6f, 0.1f, 0.2f), null).rotation)
        // poco seguro: no se gira
        assertEquals(0, OrientationVote.decide(null, has(0.3f, 0.35f, 0.15f, 0.2f), null).rotation)
        assertEquals(180, OrientationVote.decide(null, has(0.2f, 0.05f, 0.7f, 0.05f), null).rotation)
    }

    @Test fun renglonesSegurosFrenteAlModelo() {
        // los renglones (por vecinos) dicen derecha; el modelo propone 180 sin estar muy seguro -> no se gira
        assertEquals(0, OrientationVote.decide(lines(0, true, false), has(0.4f, 0.0f, 0.6f, 0.0f), null).rotation)
        // ...y nunca se pone del revés aunque el modelo esté muy seguro
        assertEquals(0, OrientationVote.decide(lines(0, true, false), has(0.05f, 0.0f, 0.95f, 0.0f), null).rotation)
        // renglones seguros 90; el modelo dice 270 flojo -> mandan los renglones
        assertEquals(90, OrientationVote.decide(lines(90, true), has(0.1f, 0.5f, 0.1f, 0.3f), null).rotation)
        // el modelo muy seguro de 270 se impone
        assertEquals(270, OrientationVote.decide(lines(90, true), has(0.05f, 0.85f, 0.05f, 0.05f), null).rotation)
        // renglones por TIRAS (eje débil): no vetan
        assertEquals(270, OrientationVote.decide(lines(90, true, strips = true), has(0.1f, 0.5f, 0.1f, 0.3f), null).rotation)
    }

    @Test fun jevSeSumaAlModelo() {
        // el modelo duda entre 90 y 270 (TIENE 270 → enderezar 90 / TIENE 90 → enderezar 270); Jev dice claro 90
        val p = has(0.1f, 0.4f, 0.05f, 0.45f)
        assertEquals(90, OrientationVote.decide(null, p, fix(0.05f, 0.85f, 0.05f, 0.05f)).rotation)
        // solo Jev
        assertEquals(180, OrientationVote.decide(null, null, fix(0.1f, 0.0f, 0.9f, 0.0f)).rotation)
        // valores no válidos se ignoran
        assertEquals(0, OrientationVote.decide(null, floatArrayOf(Float.NaN, 0f, 0f, 1f), floatArrayOf(0f, 0f)).rotation)
    }

    @Test fun jevDevuelveElGiroQueEndereza() {
        // orden de JevCapture.ORIENT: derecha, arriba a la izquierda (girar 90), del revés, arriba a la derecha (girar 270)
        assertEquals(listOf("upright", "top_left", "upside_down", "top_right"), JevCapture.ORIENT.keys.toList())
        val h = CaptureHints(orient = listOf(0.1, 0.7, 0.1, 0.1))
        assertArrayEquals(floatArrayOf(0.1f, 0.7f, 0.1f, 0.1f), JevCapture.orientation(h), 1e-6f)
        assertNull(JevCapture.orientation(CaptureHints()))
        // ida y vuelta por la caché
        assertEquals(h.orient, CaptureHints.fromJson(h.toJson())!!.orient)
        assertNull(CaptureHints.fromJson(CaptureHints().toJson())!!.orient)
    }
}
