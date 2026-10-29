package com.scannerpromax.ai.jev

import com.scannerpromax.domain.Document
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.DuplicatePages
import com.scannerpromax.imaging.NaturalColor
import com.scannerpromax.imaging.SyntheticScenes
import com.scannerpromax.ui.home.homeFolders
import com.scannerpromax.ui.review.ReviewNotices
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.random.Random

/** Ideas Jev integradas (captura, duplicados, firmas y sellos) con respuestas SIMULADAS: sin red. */
class JevIdeasTest {

    private val fit = JevFit(1.0, 1000, 750, 1000, 750)

    /** Respuesta simulada de la petición de captura. */
    private fun captureReply(
        doc: Double = 0.97, box: String = """{"left":0.2,"top":0.1,"right":0.8,"bottom":0.9}""", boxConf: Double = 0.9,
        typeA: String = "receipt", pA: Double = 0.92, typeB: String = "receipt", pB: Double = 0.88,
        folder: String = "factura", pF: Double = 0.8,
        glare: Double = 0.1, finger: Double = 0.1, cut: Double = 0.1, shadow: Double = 0.1,
    ) = """{"model":"jev-1.14.0","usage":{"input_tokens":2860,"output_tokens":90},"answers":{
        "doc":{"type":"noul","noul":$doc},
        "box":{"type":"box","box":$box,"confidence":$boxConf},
        "type_a":{"type":"choice","choice":"$typeA","probabilities":{"$typeA":$pA,"other":${1 - pA}}},
        "type_b":{"type":"choice","choice":"$typeB","probabilities":{"$typeB":$pB,"other":${1 - pB}}},
        "folder":{"type":"choice","choice":"$folder","probabilities":{"$folder":$pF}},
        "glare":{"type":"noul","noul":$glare},"finger":{"type":"noul","noul":$finger},
        "cut":{"type":"noul","noul":$cut},"shadow":{"type":"noul","noul":$shadow}}}"""

    private fun hints(json: String) = JevCapture.parse(JevResponse.parse(json), fit)

    @Test fun peticionCaptura_unaImagenYNuevePreguntas() {
        val img = JevImage(JevAsset("photo-abc", byteArrayOf(1, 2, 3)), fit)
        val req = JevCapture.request(img)
        assertEquals(9, req.questions.size)
        assertEquals("jev-1.14.0", req.toJson()["model"].let { (it as JsonPrimitive).content })
        assertEquals("photo-abc", (req.toJson()["state"]!!.jsonObject["photo"] as JsonPrimitive).content)
        assertEquals(1, (req.toJson()["assets"] as JsonObject).size)
        // la pregunta inversa: mismas opciones, orden al revés salvo "other" al final
        val a = (req.questions["type_a"] as JevQuestion.Choice).options.keys.toList()
        val b = (req.questions["type_b"] as JevQuestion.Choice).options.keys.toList()
        assertEquals(a.dropLast(1).reversed(), b.dropLast(1)); assertEquals("other", b.last())
        assertEquals(14, (req.questions["folder"] as JevQuestion.Choice).options.size)
        assertTrue(req.hasImages)
    }

    @Test fun clienteConTransporteSimulado_envíaCabeceraYModelo() {
        var sent: String? = null; var hdr: Map<String, String> = emptyMap()
        val cli = JevClient("k-test", { _, h, body -> sent = body; hdr = h; JevHttpReply(200, captureReply()) })
        val img = JevImage(JevAsset("photo-x", byteArrayOf(9)), fit)
        val h = JevCapture.parse(cli.ask(JevCapture.request(img)), img.fit)
        assertEquals("true", hdr["TypeSafe-Danger-Zone-Images"])
        assertTrue(sent!!.contains("\"jev-1.14.0\""))
        assertEquals(2860L, h.inputTokens)
        assertEquals("receipt", JevCapture.confidentType(h))
    }

    @Test fun tipo_soloConAcuerdoYConfianza() {
        assertEquals("receipt", JevCapture.confidentType(hints(captureReply())))
        assertNull(JevCapture.confidentType(hints(captureReply(typeB = "printed"))))
        assertNull(JevCapture.confidentType(hints(captureReply(pB = 0.6))))
        assertNull(JevCapture.confidentType(null))
    }

    @Test fun tipo_aModoYFiltro_soloSiElUsuarioNoEligio() {
        val base = PageEdits(filter = FilterType.DEFAULT)
        assertEquals(FilterType.AUTO, JevCapture.typeEdits("receipt", ScanMode.DOCUMENT, base)!!.filter)
        assertEquals("receipt", JevCapture.typeEdits("receipt", ScanMode.DOCUMENT, base)!!.aiHint)
        val wb = JevCapture.typeEdits("whiteboard", ScanMode.DOCUMENT, base)!!
        assertFalse(wb.autoDeskew); assertFalse(wb.autoDewarp)
        assertEquals(FilterType.ORIGINAL, JevCapture.typeEdits("photo", ScanMode.DOCUMENT, base)!!.filter)
        assertEquals("id_card", JevCapture.typeEdits("id_card", ScanMode.DOCUMENT, base)!!.aiHint)
        // impreso, manuscrito, libro: Color natural ya es lo mejor
        for (t in listOf("printed", "handwritten", "book", "other")) assertNull(JevCapture.typeEdits(t, ScanMode.DOCUMENT, base))
        // el usuario eligió otro filtro, otro modo, o ya hay pista
        assertNull(JevCapture.typeEdits("receipt", ScanMode.DOCUMENT, base.copy(filter = FilterType.BLACK_WHITE)))
        assertNull(JevCapture.typeEdits("receipt", ScanMode.RECEIPT, base))
        assertNull(JevCapture.typeEdits("receipt", ScanMode.DOCUMENT, base.copy(aiHint = "screen")))
        assertNull(JevCapture.typeEdits(null, ScanMode.DOCUMENT, base))
    }

    @Test fun pistaDni_daColorVivo() {
        val a = com.scannerpromax.imaging.ImageEnhancer.ContentAnalysis(
            com.scannerpromax.imaging.ImageEnhancer.ContentKind.TEXT, FilterType.MAGIC, false, false, 0.8f, 0.1f, 0.05f, 230f,
        )
        val r = com.scannerpromax.imaging.ImageEnhancer.withAiHint(a, "id_card")
        assertEquals(com.scannerpromax.imaging.ImageEnhancer.ContentKind.PHOTO, r.kind)
        assertEquals(FilterType.VIVID, r.recommended)
        assertEquals(a, com.scannerpromax.imaging.ImageEnhancer.withAiHint(a, "printed"))
    }

    @Test fun carpeta_soloConConfianzaYNuncaOtro() {
        assertEquals("factura", JevCapture.suggestedFolder(hints(captureReply())))
        assertNull(JevCapture.suggestedFolder(hints(captureReply(pF = 0.5))))
        assertNull(JevCapture.suggestedFolder(hints(captureReply(folder = "otro", pF = 0.99))))
        assertEquals("Facturas", JevCapture.folderLabel("factura"))
        assertNull(JevCapture.folderLabel("otro"))
    }

    @Test fun encuadre_decisiones() {
        val good = JevBox(0.21, 0.11, 0.79, 0.88)
        // sin Jev: siempre el detector
        assertEquals(JevCapture.Framing.Keep, JevCapture.framing(null, null, null, false))
        // no hay documento: página completa aunque el detector esté seguro
        assertEquals(JevCapture.Framing.FullPage, JevCapture.framing(hints(captureReply(doc = 0.2)), good, 1f, false))
        // detector seguro y de acuerdo con Jev: se queda
        assertEquals(JevCapture.Framing.Keep, JevCapture.framing(hints(captureReply()), good, 0.9f, false))
        // detector débil, sin quad o casi toda la foto: zona de Jev
        for ((q, c, full) in listOf(Triple(good, 0.3f, false), Triple(null, null, false), Triple(good, 0.9f, true))) {
            val f = JevCapture.framing(hints(captureReply()), q, c, full)
            assertTrue(f is JevCapture.Framing.Roi)
            f as JevCapture.Framing.Roi
            assertEquals(0.12, f.roi.left, 1e-9); assertEquals(0.98, f.roi.bottom, 1e-9)   // +8 %
            assertEquals(0.19, f.fallback.left, 1e-9); assertEquals(0.91, f.fallback.bottom, 1e-9)   // +1 %
        }
        // detector seguro pero en desacuerdo con una caja de Jev segura: zona de Jev
        assertTrue(JevCapture.framing(hints(captureReply()), JevBox(0.0, 0.0, 0.5, 0.4), 1f, false) is JevCapture.Framing.Roi)
        // ...con la caja de Jev poco segura, no
        assertEquals(JevCapture.Framing.Keep, JevCapture.framing(hints(captureReply(boxConf = 0.3)), JevBox(0.0, 0.0, 0.5, 0.4), 1f, false))
        // documento probable pero no seguro: no se toca
        assertEquals(JevCapture.Framing.Keep, JevCapture.framing(hints(captureReply(doc = 0.8)), null, null, false))
        // caja diminuta: no ayuda
        assertEquals(JevCapture.Framing.Keep, JevCapture.framing(hints(captureReply(box = """{"left":0.5,"top":0.5,"right":0.52,"bottom":0.6}""")), null, null, false))
    }

    @Test fun encuadre_quadDentroDeLaZona() {
        val roi = JevBox(0.1, 0.1, 0.9, 0.9); val box = JevBox(0.18, 0.18, 0.82, 0.82)
        val ok = floatArrayOf(0.2f, 0.2f, 0.8f, 0.19f, 0.81f, 0.8f, 0.19f, 0.81f)
        assertTrue(JevCapture.roiQuadOk(ok, roi, box))
        val whole = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        assertFalse(JevCapture.roiQuadOk(whole, roi, box))   // cayó al recorte entero
        val tiny = floatArrayOf(0.4f, 0.4f, 0.5f, 0.4f, 0.5f, 0.5f, 0.4f, 0.5f)
        assertFalse(JevCapture.roiQuadOk(tiny, roi, box))
        val off = floatArrayOf(0.1f, 0.1f, 0.6f, 0.1f, 0.6f, 0.6f, 0.1f, 0.6f)
        assertFalse(JevCapture.roiQuadOk(off, roi, box))     // no coincide con la caja de Jev
    }

    @Test fun calidad_umbralesAltosYCorteSoloEnElBorde() {
        assertEquals(emptyList<String>(), JevCapture.issues(hints(captureReply(glare = 0.85, finger = 0.8, shadow = 0.75)), null))
        assertEquals(listOf(PageIssue.GLARE, PageIssue.SHADOW), JevCapture.issues(hints(captureReply(glare = 0.95, shadow = 0.85)), null))
        assertEquals(listOf(PageIssue.FINGER), JevCapture.issues(hints(captureReply(finger = 0.9)), null))
        // cortada: el documento toca un borde de la foto
        val cut = hints(captureReply(cut = 0.7, box = """{"left":0.1,"top":0.2,"right":0.9,"bottom":1.0}"""))
        assertEquals(listOf(PageIssue.CUT), JevCapture.issues(cut, null))
        // ...pero no si llena la foto (escaneo) ni si no toca ninguno
        val full = hints(captureReply(cut = 0.95, box = """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}"""))
        assertEquals(emptyList<String>(), JevCapture.issues(full, JevBox(0.0, 0.0, 1.0, 1.0)))
        assertEquals(emptyList<String>(), JevCapture.issues(hints(captureReply(cut = 0.95)), JevBox(0.2, 0.1, 0.8, 0.9)))
        // sin documento: sin avisos
        assertEquals(emptyList<String>(), JevCapture.issues(hints(captureReply(doc = 0.1, glare = 0.99)), null))
        assertEquals("Reflejo y sombra fuerte", PageIssue.sentence(listOf(PageIssue.GLARE, PageIssue.SHADOW)))
        assertEquals("Dedo en la foto", PageIssue.sentence(listOf(PageIssue.FINGER)))
    }

    @Test fun cacheCaptura_idaYVuelta() {
        val h = hints(captureReply(glare = 0.93))
        val back = CaptureHints.fromJson(h.toJson())
        assertEquals(h, back)
        assertNull(CaptureHints.fromJson("""{"v":99}"""))
        val f = File.createTempFile("cap", ".json"); f.delete()
        CaptureHints.write(f, h); assertEquals(h, CaptureHints.read(f)); f.delete()
    }

    @Test fun firmasYSellos_soloConPresenciaAlta() {
        val pfit = JevFit(1.0, 1000, 1400, 1000, 1400)
        fun reply(sig: Double, stamp: Double) = JevResponse.parse("""{"model":"jev-1.14.0","usage":{"input_tokens":6400},"answers":{
            "has_sig":{"type":"noul","noul":$sig},"sig_box":{"type":"box","box":{"left":0.6,"top":0.8,"right":0.8,"bottom":0.84}},
            "has_stamp":{"type":"noul","noul":$stamp},"stamp_box":{"type":"box","box":{"left":0.1,"top":0.7,"right":0.25,"bottom":0.8}}}}""")
        val both = JevPage.parseInks(reply(0.95, 0.85), pfit)
        assertEquals(2, both.size)
        // firma ampliada un 50 % de su tamaño por lado
        assertEquals(0.5f, both[0][0], 1e-4f); assertEquals(0.9f, both[0][2], 1e-4f); assertEquals(0.78f, both[0][1], 1e-4f)
        assertEquals(1, JevPage.parseInks(reply(0.7, 0.85), pfit).size)
        assertEquals(0, JevPage.parseInks(reply(0.5, 0.5), pfit).size)
        // la petición de la página añade las 4 preguntas solo si se piden
        val img = JevImage(JevAsset("page-1", byteArrayOf(1)), pfit)
        assertTrue(JevPage.request(img, photos = true, tables = false, inks = true).questions.containsKey("stamp_box"))
        assertFalse(JevPage.request(img, photos = true, tables = true).questions.containsKey("has_sig"))
        assertEquals("Find the pictures and the ruled tables on a photographed document page.",
            (JevPage.request(img, photos = true, tables = true).state.jsonObject["task"] as JsonPrimitive).content)
    }

    @Test fun cachePagina_conFirmas_yCompatibleConLaAnterior() {
        val r = JevPageResult(emptyList(), emptyList(), 6400, 200, true, true, listOf(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)), true)
        val b = JevPageResult.fromJson(r.toJson())!!
        assertEquals(1, b.inks.size); assertTrue(b.covers(true, true, true))
        // caché de antes de las firmas: vale para fotos/tablas, no para firmas
        val old = JevPageResult.fromJson("""{"v":2,"photos":[],"tables":[],"tokens":1,"ms":1,"askedPhotos":true,"askedTables":true}""")!!
        assertTrue(old.covers(true, true)); assertFalse(old.covers(true, true, true))
    }

    @Test fun tintaEnLayoutImages_noEsFoto() {
        val boxes = listOf(floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f), floatArrayOf(0.6f, 0.8f, 0.9f, 0.9f, NaturalColor.INK_FLAG))
        assertEquals(1, NaturalColor.layoutRects(boxes, 1000, 1000).size)
        assertEquals(1, NaturalColor.inkRects(boxes, 1000, 1000).size)
        assertEquals(600, NaturalColor.inkRects(boxes, 1000, 1000)[0].x)
    }

    @Test fun duplicados_zonas() {
        assertEquals(DuplicatePages.Verdict.DUPLICATE, DuplicatePages.verdict(40))
        assertEquals(DuplicatePages.Verdict.GRAY, DuplicatePages.verdict(12))
        assertEquals(DuplicatePages.Verdict.GRAY, DuplicatePages.verdict(39))
        assertEquals(DuplicatePages.Verdict.DIFFERENT, DuplicatePages.verdict(11))
    }

    /** Página de "texto" aleatorio (bloques y letras) en gris. */
    private fun page(seed: Int): Mat {
        val r = Random(seed)
        val m = Mat(1400, 1000, CvType.CV_8UC1, Scalar(235.0))
        var y = 60
        while (y < 1350) {
            var x = 50
            while (x < 930) {
                val w = 20 + r.nextInt(70)
                Imgproc.putText(m, (1..(w / 14).coerceAtLeast(1)).map { ('a' + r.nextInt(26)) }.joinToString(""), Point(x.toDouble(), y.toDouble()),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, Scalar(25.0), 2)
                x += w + 18
            }
            y += 38
        }
        return m
    }

    @Test fun duplicados_orbLocal() {
        assumeTrue(SyntheticScenes.openCv)
        val a = page(1)
        // la misma hoja fotografiada otra vez: perspectiva, luz y desenfoque
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(1000.0, 0.0), Point(1000.0, 1400.0), Point(0.0, 1400.0))
        val dst = MatOfPoint2f(Point(40.0, 25.0), Point(960.0, 60.0), Point(990.0, 1380.0), Point(10.0, 1350.0))
        val h = Imgproc.getPerspectiveTransform(src, dst)
        val b = Mat(); Imgproc.warpPerspective(a, b, h, Size(1000.0, 1400.0), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_CONSTANT, Scalar(120.0))
        Imgproc.GaussianBlur(b, b, Size(0.0, 0.0), 0.8); b.convertTo(b, -1, 0.9, 10.0)
        val c = page(2)
        val fa = DuplicatePages.features(a); val fb = DuplicatePages.features(b); val fc = DuplicatePages.features(c)
        val same = DuplicatePages.inliers(fa, fb); val diff = DuplicatePages.inliers(fa, fc)
        assertTrue("misma hoja: $same", same >= DuplicatePages.SURE)
        assertTrue("hojas distintas: $diff", diff < DuplicatePages.GRAY_LO)
        listOf(fa, fb, fc).forEach { it.release() }; listOf(a, b, c).forEach { it.release() }
    }

    @Test fun peticionDuplicados_dosImagenes() {
        val a = JevImage(JevAsset("first-1", byteArrayOf(1)), fit); val b = JevImage(JevAsset("second-2", byteArrayOf(2)), fit)
        val r = JevDuplicates.request(a, b)
        assertEquals(2, r.assets.size); assertEquals(setOf("same"), r.questions.keys)
        assertEquals("second-2", (r.state.jsonObject["second"] as JsonPrimitive).content)
    }

    @Test fun avisosRevision_duplicadoYCarpeta() {
        val p = listOf(Page("a", "o", width = 1, height = 1), Page("b", "o", width = 1, height = 1), Page("c", "o", width = 1, height = 1, dupOf = "b"))
        val d = ReviewNotices.duplicate(p)!!
        assertEquals(2, d.first); assertEquals(3, d.second); assertEquals("c", d.third.id)
        assertEquals("Las páginas 2 y 3 parecen la misma.", ReviewNotices.duplicateText(2, 3))
        assertNull(ReviewNotices.duplicate(p.take(2)))
        // la página repetida ya no está: sin aviso
        assertNull(ReviewNotices.duplicate(listOf(p[0], p[2])))
        val doc = Document("d", "t", 0, 0, suggestedCategory = "factura")
        assertEquals("Facturas", ReviewNotices.folder(doc))
        assertNull(ReviewNotices.folder(doc.copy(suggestedCategory = "")))
        assertNull(ReviewNotices.folder(doc.copy(category = "contrato")))
    }

    @Test fun carpetasInicio_conRecuento() {
        val docs = listOf(
            Document("1", "a", 0, 0, category = "factura"), Document("2", "b", 0, 0, category = "factura"),
            Document("3", "c", 0, 0, category = "contrato"), Document("4", "d", 0, 0),
        )
        assertEquals(listOf("factura" to "Facturas · 2", "contrato" to "Contratos · 1"), homeFolders(docs))
        assertEquals(emptyList<Pair<String, String>>(), homeFolders(docs.takeLast(1)))
    }

    @Test fun modelo_camposNuevosNoSeSerializanSiSonNulos() {
        val json = com.scannerpromax.data.DocumentRepository.json
        val s = json.encodeToString(Page.serializer(), Page("a", "o", width = 1, height = 1))
        assertFalse(s.contains("issues")); assertFalse(s.contains("dupOf"))
        val d = json.decodeFromString(Document.serializer(), """{"id":"x","title":"t","createdAt":0,"updatedAt":0}""")
        assertNull(d.category); assertNull(d.suggestedCategory)
        assertNotNull(json.decodeFromString(Page.serializer(), """{"id":"a","originalFile":"o","width":1,"height":1,"issues":["glare"]}""").issues)
    }
}
