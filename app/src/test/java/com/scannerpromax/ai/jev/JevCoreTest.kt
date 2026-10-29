package com.scannerpromax.ai.jev

import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.FakeHttp
import com.scannerpromax.data.PageFiles
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** Jev sin red: cuerpo de la petición, respuesta, reintentos, límites de imagen, caché y protocolos con respuestas simuladas. */
class JevCoreTest {

    private val img = JevImage(JevAsset("page-abc", byteArrayOf(1, 2, 3)), JevFit(1.0, 1000, 1400, 1000, 1400))

    @Test fun peticion_cuerpoYCabeceras() {
        val req = JevPage.request(img, photos = true, tables = true)
        val js = req.toJson()
        assertEquals(JevApi.MODEL, js["model"]!!.jsonPrimitive.content)
        assertEquals("page-abc", js["state"]!!.jsonObject["page"]!!.jsonPrimitive.content)
        val a = js["assets"]!!.jsonObject["page-abc"]!!.jsonObject
        assertEquals("image", a["type"]!!.jsonPrimitive.content)
        assertEquals("jpeg", a["format"]!!.jsonPrimitive.content)
        assertEquals("AQID", a["data_base64"]!!.jsonPrimitive.content)
        val q = js["questions"]!!.jsonObject
        for (k in listOf("count", "any", "b0", "b5", "ntab", "tany", "t0", "t2", "rows0", "cols0")) assertTrue("falta $k", k in q)
        assertEquals("box", q["b0"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(q["b1"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("2nd"))
        assertEquals(6, (q["count"]!!.jsonObject["criteria"] as JsonObject).size)
        assertTrue(req.hasImages)
        // score: criteria es una LISTA
        val s = JevQuestion.Score("¿cuánto?", listOf("poco", "medio", "mucho")).toJson()
        assertTrue(s["criteria"] is JsonArray)
    }

    @Test fun respuesta_tiposYCajaRecortada() {
        val r = JevResponse.parse(
            """{"model":"jev-1.14.0","usage":{"input_tokens":3044,"output_tokens":21},"answers":{
              "a":{"type":"noul","noul":0.98},
              "c":{"type":"choice","choice":"2","confidence":0.4,"probabilities":{"0":0.1,"2":0.9}},
              "s":{"type":"score","score":1.43,"confidence":0.35,"probabilities":{"0":0.0,"1":0.57}},
              "b":{"type":"box","box":{"top":0.25,"bottom":1.2,"left":-0.1,"right":0.84},"confidence":0.92}}}""",
        )
        assertEquals(3044, r.inputTokens); assertEquals("jev-1.14.0", r.model)
        assertEquals(0.98, r["a"]!!.noul!!, 1e-9)
        assertEquals("2", r["c"]!!.choice); assertEquals(0.9, r["c"]!!.p("2"), 1e-9)
        assertEquals(1.43, r["s"]!!.score!!, 1e-9)
        val b = r["b"]!!.box!!
        assertEquals(0.0, b.left, 0.0); assertEquals(1.0, b.bottom, 0.0)
        assertEquals(3044 * 0.042 / 1e6, r.cost, 1e-12)
    }

    @Test fun limites_ajusteYRelleno() {
        // grande: lado largo 1568
        JevFit.of(4000, 3000, 1568).let { assertEquals(1568, it.canvasW); assertTrue(JevFit.valid(it.canvasW, it.canvasH)) }
        // diminuto: se amplía hasta 280 en el lado corto
        JevFit.of(120, 40, 800).let { assertTrue(JevFit.valid(it.canvasW, it.canvasH)); assertTrue(it.contentH >= 270 || it.canvasH == 280) }
        // muy alargado: el lado corto se rellena hasta 280
        val f = JevFit.of(3000, 100, 1568)
        assertTrue(JevFit.valid(f.canvasW, f.canvasH))
        assertEquals(1568, f.contentW); assertTrue(f.contentH < 280); assertEquals(280, f.canvasH)
        // una caja del lienzo se lleva a la imagen original descontando el relleno
        val b = f.toSource(JevBox(0.0, 0.0, 0.5, f.contentH.toDouble() / f.canvasH))
        assertEquals(1.0, b.bottom, 1e-9); assertEquals(0.5, b.right, 1e-9)
        assertFalse(JevFit.valid(279, 500)); assertFalse(JevFit.valid(1569, 500))
    }

    private fun client(http: FakeHttp, sleeps: MutableList<Long> = ArrayList()) =
        JevClient("clave-secreta-123", OkHttpJevTransport(), url = http.base + "/v1/systemone", baseDelayMs = 10, sleeper = { sleeps += it })

    private val okBody = """{"model":"jev-1.14.0","usage":{"input_tokens":10,"output_tokens":5},"answers":{"x":{"type":"noul","noul":0.7}}}"""
    private val textReq = JevRequest(JsonPrimitive("hola"), mapOf("x" to JevQuestion.Noul("¿sí?")))

    @Test fun cliente_reintenta429ConRetryAfterYCabeceras() {
        FakeHttp().use { http ->
            http.enqueue(429, "{}", mapOf("Retry-After" to "2"))
            http.enqueue(529, "{}")
            http.enqueue(200, okBody)
            val sleeps = ArrayList<Long>()
            val c = client(http, sleeps)
            val r = c.ask(JevRequest(JsonPrimitive("hola"), mapOf("x" to JevQuestion.Box("la foto"))))
            assertEquals(0.0, r["x"]!!.noul!! - 0.7, 1e-9)
            assertEquals(listOf(2000L, 20L), sleeps)
            assertEquals(3, http.requests.size)
            val h = http.requests[0].headers
            assertEquals("Bearer clave-secreta-123", h["authorization"])
            assertEquals("true", h["typesafe-danger-zone-images"])
            assertEquals(10, c.inputTokens)
        }
    }

    @Test fun cliente_401NoReintentaYSinImagenesNoMandaCabecera() {
        FakeHttp().use { http ->
            http.enqueue(401, """{"detail":"invalid key"}""")
            try { client(http).ask(textReq); fail() } catch (e: CloudException.Auth) { assertEquals(401, e.status) }
            assertEquals(1, http.requests.size)
            assertNull(http.requests[0].headers["typesafe-danger-zone-images"])
            assertFalse(http.requests[0].body.contains("clave-secreta"))
        }
    }

    @Test fun cliente_sinRed_lanzaNetworkTrasLosIntentos() {
        var n = 0
        val c = JevClient("k", { _, _, _ -> n++; throw IOException("sin red") }, maxTries = 3, baseDelayMs = 1, sleeper = {})
        try { c.ask(textReq); fail() } catch (e: CloudException.Network) { assertEquals(3, n) }
    }

    @Test fun fotos_recuentoDuplicadosYPresencia() {
        val fit = JevFit(1.0, 1000, 1000, 1000, 1000)
        fun resp(count: String, any: Double) = JevResponse.parse("""{"model":"m","usage":{"input_tokens":1,"output_tokens":0},"answers":{
            "count":{"type":"choice","choice":"$count","probabilities":{}},"any":{"type":"noul","noul":$any},
            "b0":{"type":"box","box":{"top":0.1,"bottom":0.3,"left":0.1,"right":0.5},"confidence":0.8},
            "b1":{"type":"box","box":{"top":0.11,"bottom":0.3,"left":0.1,"right":0.51},"confidence":0.3},
            "b2":{"type":"box","box":{"top":0.5,"bottom":0.8,"left":0.2,"right":0.9},"confidence":0.7},
            "b3":{"type":"box","box":{"top":0.5,"bottom":0.8,"left":0.2,"right":0.9},"confidence":0.2}}}""")
        // la 2.ª ordinal repite la 1.ª: fuera; se toma la siguiente distinta
        JevPhotos.parse(resp("2", 0.95), fit).boxes.let { assertEquals(2, it.size); assertEquals(0.5, it[1].top, 1e-9) }
        JevPhotos.parse(resp("1", 0.95), fit).boxes.let { assertEquals(1, it.size) }
        assertTrue(JevPhotos.parse(resp("2", 0.2), fit).boxes.isEmpty())
        assertTrue(JevPhotos.parse(resp("0", 0.9), fit).boxes.isEmpty())
    }

    @Test fun tablas_cajasAmpliadasYRecuento() {
        val fit = JevFit(1.0, 1000, 1000, 1000, 1000)
        val r = JevResponse.parse("""{"model":"m","usage":{"input_tokens":1,"output_tokens":0},"answers":{
            "ntab":{"type":"choice","choice":"1","probabilities":{}},"tany":{"type":"noul","noul":0.9},
            "t0":{"type":"box","box":{"top":0.2,"bottom":0.6,"left":0.1,"right":0.9}},
            "rows0":{"type":"choice","choice":"12","probabilities":{}},"cols0":{"type":"choice","choice":"16+","probabilities":{}}}}""")
        val f = JevTables.parse(r, fit)
        assertEquals(1, f.boxes.size); assertEquals(12, f.rows); assertEquals(16, f.cols)
        assertEquals(0.2 - JevTables.PAD, f.boxes[0].top, 1e-9)
    }

    @Test fun cache_idaYVueltaYCobertura() {
        val r = JevPageResult(listOf(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)), emptyList(), 6000, 300, askedPhotos = true, askedTables = false)
        val f = File.createTempFile("jev", ".json"); f.deleteOnExit()
        JevPageResult.write(f, r)
        val b = JevPageResult.read(f)!!
        assertEquals(0.3f, b.photos[0][2], 1e-6f); assertEquals(6000, b.inputTokens)
        assertTrue(b.covers(photos = true, tables = false)); assertFalse(b.covers(photos = true, tables = true))
        assertNull(JevPageResult.fromJson("""{"v":0,"photos":[]}"""))
    }

    @Test fun fusionFotos_sinDuplicarLaMaquetacion() {
        val layout = listOf(floatArrayOf(0.1f, 0.1f, 0.5f, 0.4f))
        val jev = listOf(floatArrayOf(0.11f, 0.1f, 0.5f, 0.41f), floatArrayOf(0.6f, 0.6f, 0.9f, 0.9f))
        val m = JevPage.mergePhotos(layout, jev)
        assertEquals(2, m.size); assertEquals(0.6f, m[1][0], 0f)
        assertEquals(layout, JevPage.mergePhotos(layout, emptyList()))
    }

    @Test fun cacheDeLaPagina_soloDependeDeLaGeometria() {
        val q = Quad(Pt(1f, 2f), Pt(100f, 2f), Pt(100f, 200f), Pt(1f, 200f))
        val a = PageEdits(quad = q, filter = FilterType.NATURAL)
        val b = a.copy(filter = FilterType.BLACK_WHITE, keepRuling = false)
        assertEquals(PageFiles.jevCacheName("p1", a), PageFiles.jevCacheName("p1", b))
        assertNotEquals(PageFiles.jevCacheName("p1", a), PageFiles.jevCacheName("p1", a.copy(rotation = 90)))
        assertTrue(PageFiles.jevCacheName("p1", a).startsWith("jev/p1_"))
    }

    @Test fun ocr_variantesYOpciones() {
        val v = JevOcr.confusions("l0rn", max = 20)
        assertTrue("1" + "0rn" in v); assertTrue("lOrn" in v); assertTrue("l0m" in v)
        assertFalse("l0rn" in v)
        val o = JevOcr.options("hola", listOf("hola", "bola", "", "bola"))
        assertEquals(listOf("c0", "c1", "other"), o.keys.toList())
        assertTrue(o["c1"]!!.contains("«bola»"))
    }
}
