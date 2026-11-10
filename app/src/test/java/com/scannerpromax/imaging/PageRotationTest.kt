package com.scannerpromax.imaging

import com.scannerpromax.data.PageFiles
import com.scannerpromax.domain.EraseStroke
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs

/** Girar una página SIN reprocesar: píxeles exactos, OCR transformado, ediciones y nombres de archivo coherentes. */
class PageRotationTest {

    private val base = PageEdits(
        quad = Quad(Pt(10f, 20f), Pt(900f, 30f), Pt(880f, 1200f), Pt(15f, 1180f)),
        filter = FilterType.BLACK_WHITE,
        eraseStrokes = listOf(EraseStroke(listOf(Pt(0.1f, 0.2f), Pt(0.3f, 0.25f)), 0.02f)),
    )

    // ------------------------------------------------------------------ ediciones

    @Test fun cuatroGirosDevuelvenLasMismasEdiciones() {
        var e = PageEdits(filter = FilterType.NATURAL)
        val hash0 = PageFiles.editsHash(e)
        val seen = HashSet<String>()
        repeat(4) {
            e = PageRotation.withRotation(e, e.rotation + 90, 0.7f)
            seen += PageFiles.processedName("p", e)
        }
        assertEquals(PageEdits(filter = FilterType.NATURAL), e)
        assertNull(e.geoRotation)
        assertEquals(hash0, PageFiles.editsHash(e))
        // cada giro intermedio es otra versión del procesado (archivo propio)
        assertEquals(4, seen.size)
    }

    @Test fun elGiroNoTocaLaGeometria() {
        val r = PageRotation.withRotation(base, 90, 0.75f)
        assertEquals(90, r.rotation)
        assertEquals(0, r.geoRotation)
        assertEquals(0, PageRotation.geoRotation(r))
        assertEquals(90, PageRotation.delta(r))
        // la caché de Jev (página en el marco de la geometría) no se invalida; el procesado sí cambia de nombre
        assertEquals(PageFiles.jevCacheName("p", base), PageFiles.jevCacheName("p", r))
        assertNotEquals(PageFiles.processedName("p", base), PageFiles.processedName("p", r))
        // una página ya girada al crearla (sin geoRotation) es la de siempre: hash sin cambios
        val auto = base.copy(rotation = 270)
        assertEquals(0, PageRotation.delta(auto))
        assertEquals(PageFiles.editsHash(auto), PageFiles.editsHash(auto.copy(geoRotation = null)))
    }

    @Test fun soloGiro_seDetecta() {
        val aspect = 0.75f
        val r90 = PageRotation.withRotation(base, 90, aspect)
        assertTrue(PageRotation.rotationOnly(base, r90, aspect))
        // girar otra vez sobre la girada (proporción invertida)
        val r180 = PageRotation.withRotation(r90, 180, 1 / aspect)
        assertTrue(PageRotation.rotationOnly(r90, r180, 1 / aspect))
        assertTrue(PageRotation.rotationOnly(base, r180, aspect))
        // cambiar algo más (filtro, recorte, trazos) ya no es solo un giro
        assertFalse(PageRotation.rotationOnly(base, r90.copy(filter = FilterType.NATURAL), aspect))
        assertFalse(PageRotation.rotationOnly(base, r90.copy(quad = null), aspect))
        assertFalse(PageRotation.rotationOnly(base, r90.copy(eraseStrokes = emptyList()), aspect))
        assertFalse(PageRotation.rotationOnly(base, r90.copy(eraseStrokes = base.eraseStrokes), aspect))
        // girar con la geometría recalculada (geoRotation distinto) tampoco
        assertFalse(PageRotation.rotationOnly(base, r90.copy(geoRotation = null), aspect))
        assertFalse(PageRotation.rotationOnly(base, base, aspect))
    }

    @Test fun planDeEdicion_unGiroNoReprocesa() {
        val aspect = 0.75f
        val r = PageRotation.withRotation(base, 270, aspect)
        assertEquals(PageFiles.EditPlan.ROTATE, PageFiles.editPlan(draftReady = false, rotationOnly = PageRotation.rotationOnly(base, r, aspect)))
        assertEquals(PageFiles.EditPlan.REUSE, PageFiles.editPlan(draftReady = true, rotationOnly = true))
        assertEquals(PageFiles.EditPlan.RENDER, PageFiles.editPlan(false, PageRotation.rotationOnly(base, r.copy(filter = FilterType.NATURAL), aspect)))
    }

    @Test fun trazosVuelvenAlMarcoDeLaGeometria() {
        // un trazo dibujado sobre la página girada vuelve exactamente a donde estaba en el marco de la geometría
        val w = 600; val h = 800
        for (rot in intArrayOf(90, 180, 270)) {
            val r = PageRotation.withRotation(base, rot, w.toFloat() / h)
            val (fw, fh) = if (rot == 180) w to h else h to w
            val g = PageRotation.geoEdits(r, fw, fh)
            assertEquals(0, g.rotation); assertNull(g.geoRotation)
            val a = base.eraseStrokes[0]; val b = g.eraseStrokes[0]
            assertEquals(a.radius, b.radius, 1e-5f)
            for ((p, q) in a.points.zip(b.points)) { assertEquals(p.x, q.x, 1e-5f); assertEquals(p.y, q.y, 1e-5f) }
        }
    }

    @Test fun cajasNormalizadasIdaYVuelta() {
        val b = floatArrayOf(0.1f, 0.2f, 0.4f, 0.5f, 7f)
        for (q in 1..3) {
            val there = PageRotation.rotateBoxNorm(b, q)
            val back = PageRotation.boxesToGeo(listOf(there), q * 90)[0]
            assertArrayEquals(b, back, 1e-6f)
            assertEquals(7f, there[4])
        }
        // giro horario: la esquina superior izquierda pasa arriba a la derecha
        assertArrayEquals(floatArrayOf(0.5f, 0.1f, 0.8f, 0.4f, 7f), PageRotation.rotateBoxNorm(b, 1), 1e-6f)
    }

    // ------------------------------------------------------------------ OCR

    private val ocr = OcrResult(
        text = "Hola mundo", imageWidth = 1000, imageHeight = 600,
        blocks = listOf(
            OcrBlock(
                "Hola mundo", OcrRect(100f, 50f, 400f, 90f),
                listOf(OcrLine("Hola mundo", OcrRect(100f, 50f, 400f, 90f), listOf(OcrWord("Hola", OcrRect(100f, 50f, 220f, 90f), 0.9f), OcrWord("mundo", OcrRect(240f, 50f, 400f, 90f), 0.8f)), angle = 1.5f, confidence = 0.9f)),
            ),
        ),
        doubtful = listOf(OcrLine("¿?", OcrRect(10f, 500f, 60f, 540f), angle = -90f)),
    )

    @Test fun ocrGiradoConLaPagina() {
        val r = PageRotation.rotateOcr(ocr, 90)
        assertEquals(600, r.imageWidth); assertEquals(1000, r.imageHeight)
        // horario 90: (x, y) -> (H - y, x)
        assertEquals(OcrRect(510f, 100f, 550f, 400f), r.blocks[0].lines[0].box)
        assertEquals(OcrRect(510f, 100f, 550f, 220f), r.blocks[0].lines[0].words[0].box)
        assertEquals(91.5f, r.blocks[0].lines[0].angle, 1e-4f)
        assertEquals(0f, r.doubtful[0].angle, 1e-4f)
        assertEquals("Hola mundo", r.text)
        assertEquals(0.8f, r.blocks[0].lines[0].words[1].confidence)
        val r180 = PageRotation.rotateOcr(ocr, 180)
        assertEquals(OcrRect(600f, 510f, 900f, 550f), r180.blocks[0].box)
        val r270 = PageRotation.rotateOcr(ocr, 270)
        assertEquals(OcrRect(50f, 600f, 90f, 900f), r270.blocks[0].box)
        // cuatro giros = el mismo OCR
        var c = ocr
        repeat(4) { c = PageRotation.rotateOcr(c, 90) }
        assertEquals(ocr, c)
        assertEquals(ocr, PageRotation.rotateOcr(PageRotation.rotateOcr(ocr, 90), 270))
    }

    // ------------------------------------------------------------------ píxeles (OpenCV de escritorio)

    private fun randomMat(w: Int, h: Int, type: Int): Mat {
        val m = Mat(h, w, type)
        Core.randu(m, 0.0, 256.0)
        return m
    }

    private fun same(a: Mat, b: Mat): Boolean {
        if (a.size() != b.size() || a.type() != b.type()) return false
        val d = Mat(); Core.absdiff(a, b, d)
        val n = Core.countNonZero(d.reshape(1)); d.release()
        return n == 0
    }

    @Test fun giroSinPerdidas_cuatroVecesIdentico() {
        assumeTrue(SyntheticScenes.openCv)
        for (type in intArrayOf(CvType.CV_8UC4, CvType.CV_8UC3, CvType.CV_8UC1)) {
            val src = randomMat(317, 211, type)
            var cur = src.clone()
            repeat(4) { val n = PageRotation.rotateMat(cur, 90); cur.release(); cur = n }
            assertTrue(same(src, cur))
            val a = PageRotation.rotateMat(src, 90); val b = PageRotation.rotateMat(a, 270)
            assertTrue(same(src, b))
            assertEquals(src.cols(), a.rows()); assertEquals(src.rows(), a.cols())
            // un píxel concreto: (x, y) -> (H - 1 - y, x) en el giro horario
            val p = src.get(5, 7); val q = a.get(7, src.rows() - 1 - 5)
            assertArrayEquals(p, q, 0.0)
            val c = PageRotation.rotateMat(src, 180); val d = PageRotation.rotateMat(c, 180)
            assertTrue(same(src, d))
            listOf(src, cur, a, b, c, d).forEach { it.release() }
        }
    }

    /**
     * Tiempos (JVM, una página de 12 MP): el giro de píxeles es lo único que hace el «girar» nuevo; el JPEG se decodifica
     * y se vuelve a codificar (calidad 95). Pérdida por recodificar 4 veces medida en PSNR. Se imprime para el informe.
     */
    @Test fun tiempos12Mp() {
        assumeTrue(SyntheticScenes.openCv)
        val page = SyntheticScenes.textPage(3000, 4000)
        val rgba = Mat(); org.opencv.imgproc.Imgproc.cvtColor(page, rgba, org.opencv.imgproc.Imgproc.COLOR_RGB2RGBA)
        // calentar
        repeat(2) { PageRotation.rotateMat(rgba, 90).release() }
        val n = 10
        var t0 = System.nanoTime()
        repeat(n) { PageRotation.rotateMat(rgba, 90).release() }
        val rotMs = (System.nanoTime() - t0) / 1e6 / n
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95)
        val enc = MatOfByte(); Imgcodecs.imencode(".jpg", page, enc, params)
        t0 = System.nanoTime()
        val dec = Imgcodecs.imdecode(enc, Imgcodecs.IMREAD_COLOR)
        val r = PageRotation.rotateMat(dec, 90)
        val enc2 = MatOfByte(); Imgcodecs.imencode(".jpg", r, enc2, params)
        val fileMs = (System.nanoTime() - t0) / 1e6
        // generación: 4 giros de 90° con recodificación JPEG 95 frente a la primera codificación
        var cur = Imgcodecs.imdecode(enc, Imgcodecs.IMREAD_COLOR)
        val first = cur.clone()
        repeat(4) {
            val rr = PageRotation.rotateMat(cur, 90); cur.release()
            val e = MatOfByte(); Imgcodecs.imencode(".jpg", rr, e, params); rr.release()
            cur = Imgcodecs.imdecode(e, Imgcodecs.IMREAD_COLOR); e.release()
        }
        val psnr = Core.PSNR(first, cur)
        println("GIRO 12 MP: píxeles %.1f ms; decodificar+girar+codificar JPEG %.0f ms; PSNR tras 4 giros con JPEG 95 = %.1f dB".format(java.util.Locale.ROOT, rotMs, fileMs, psnr))
        assertTrue("giro de píxeles $rotMs ms", rotMs < 150)
        assertTrue("PSNR $psnr", psnr > 38)
        listOf(page, rgba, dec, r, first, cur).forEach { it.release() }
    }
}
