package com.scannerpromax.ai.jev

import com.scannerpromax.imaging.NaturalColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pregunta "¿fotografía de cámara?" de "Mejorar con Jev": en la misma petición de la página, por caja ordinal y por recorte. */
class JevCameraTest {

    private fun img(ref: String) = JevImage(JevAsset(ref, ByteArray(4)), JevFit.of(1000, 1400, 1568))

    @Test fun peticion_preguntaPorCadaCajaYPorCadaRecorte_enUnaSolaLlamada() {
        val req = JevPage.request(img("page-1"), photos = true, tables = false, inks = false, crops = listOf(img("crop-a"), img("crop-b")))
        for (i in 0 until JevPhotos.MAX_BOXES) assertTrue("cam$i", req.questions["cam$i"] is JevQuestion.Noul)
        assertTrue(req.questions["camc0"] is JevQuestion.Noul && req.questions["camc1"] is JevQuestion.Noul)
        assertEquals(listOf("page-1", "crop-a", "crop-b"), req.assets.map { it.ref })
        val q = (req.questions["camc1"] as JevQuestion.Noul).instructions
        assertTrue(q, q.contains("`crop1`") && q.contains("signature") && q.contains("aerial"))
        // sin fotos no hay preguntas de cámara ni recortes
        val r2 = JevPage.request(img("page-1"), photos = false, tables = true, crops = listOf(img("crop-a")))
        assertTrue(r2.questions.keys.none { it.startsWith("cam") }); assertEquals(1, r2.assets.size)
    }

    @Test fun opiniones_casanConLasCajasOrdinales_yLosRecortes() {
        val r = JevResponse.parse("""{"model":"m","usage":{"input_tokens":1,"output_tokens":0},"answers":{
            "count":{"type":"choice","choice":"2","probabilities":{}},"any":{"type":"noul","noul":0.9},
            "b0":{"type":"box","box":{"top":0.1,"bottom":0.4,"left":0.2,"right":0.8},"confidence":0.8},
            "b1":{"type":"box","box":{"top":0.1,"bottom":0.4,"left":0.2,"right":0.8},"confidence":0.3},
            "b2":{"type":"box","box":{"top":0.85,"bottom":0.95,"left":0.2,"right":0.4},"confidence":0.7},
            "cam0":{"type":"noul","noul":0.97},"cam1":{"type":"noul","noul":0.5},"cam2":{"type":"noul","noul":0.12},
            "camc0":{"type":"noul","noul":0.88}}}""")
        val fit = JevFit.of(1000, 1400, 1568)
        val found = JevPhotos.parse(r, fit)
        // la 2.ª ordinal repite la 1.ª: la caja aceptada en 2.º lugar es b2 -> su opinión es cam2, no cam1
        assertEquals(listOf(0, 2), found.ordinals)
        val v = JevPage.parseVerdicts(r, found, found.boxes, listOf(floatArrayOf(0.5f, 0.5f, 0.9f, 0.9f)))
        assertEquals(3, v.size)
        assertTrue(v.all { NaturalColor.isVerdict(it) })
        assertEquals(0.97f, v[0][5], 1e-6f); assertEquals(0.12f, v[1][5], 1e-6f); assertEquals(0.88f, v[2][5], 1e-6f)
        // las opiniones no son cajas de foto
        assertTrue(NaturalColor.layoutRects(v, 1000, 1400).isEmpty())
    }

    @Test fun cache_conOpiniones_idaYVuelta_yLaAnteriorSigueValiendo() {
        val old = JevPageResult(listOf(floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f)), emptyList(), askedPhotos = true, askedTables = true)
        assertTrue(old.covers(photos = true, tables = false) && old.verdicts.isEmpty())
        val nu = JevPageResult(emptyList(), emptyList(), verdicts = listOf(floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f, NaturalColor.VERDICT_FLAG, 0.9f)), askedCamera = true)
        val back = JevPageResult.fromJson(nu.toJson())!!
        assertTrue(back.covers(photos = true, tables = true))
        assertEquals(0.9f, back.verdicts[0][5], 1e-6f)
    }
}
