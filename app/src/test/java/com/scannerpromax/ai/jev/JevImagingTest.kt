package com.scannerpromax.ai.jev

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.imaging.SyntheticScenes
import com.scannerpromax.imaging.TableLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.min

/** Partes de Jev con OpenCV: imagen ligera, ajuste local de fotos, tablas con zonas indicadas y OCR con respuestas simuladas. */
class JevImagingTest {

    @Test fun imagenLigera_dentroDeLimitesYDecodificable() {
        assumeTrue(SyntheticScenes.openCv)
        val m = Mat(3000, 2200, CvType.CV_8UC3, Scalar(200.0, 180.0, 160.0))
        val im = JevImages.encode(m, 1568)
        assertTrue(JevFit.valid(im.fit.canvasW, im.fit.canvasH))
        val d = Imgcodecs.imdecode(MatOfByte(*im.asset.bytes), Imgcodecs.IMREAD_COLOR)
        assertEquals(1568, d.rows()); assertEquals(im.fit.canvasW, d.cols())
        assertTrue(im.asset.ref.startsWith("page-") || im.asset.ref.startsWith("img-"))
        // recorte de una palabra: se amplía y se rellena hasta 280
        val c = JevImages.encodeCrop(m, Rect(100, 100, 120, 30), 0.3, 64)
        assertTrue(JevFit.valid(c.fit.canvasW, c.fit.canvasH))
        m.release()
    }

    /** Página con texto y una foto (textura) en [r]. */
    private fun photoPage(r: Rect): Mat {
        val m = Mat(1400, 1000, CvType.CV_8UC3, Scalar(235.0, 232.0, 225.0))
        var y = 80
        while (y < 1350) {
            Imgproc.putText(m, "texto de la pagina con palabras normales", Point(60.0, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, Scalar(30.0, 30.0, 30.0), 2)
            y += 40
        }
        val noise = Mat(r.height, r.width, CvType.CV_8UC3)
        Core.randu(noise, 20.0, 200.0)
        Imgproc.GaussianBlur(noise, noise, org.opencv.core.Size(0.0, 0.0), 3.0)
        noise.copyTo(m.submat(r)); noise.release()
        return m
    }

    @Test fun ajusteLocal_acercaLaCajaHolgadaALaFoto() {
        assumeTrue(SyntheticScenes.openCv)
        val r = Rect(200, 400, 500, 350)
        val m = photoPage(r)
        val truth = JevBox(0.2, 400 / 1400.0, 0.7, 750 / 1400.0)
        val loose = JevBox(0.18, 0.27, 0.72, 0.55)  // como la de Jev: cuantizada y holgada
        val out = JevPhotos.refine(m, listOf(loose))[0]
        assertTrue("IoU ${loose.iou(truth)} -> ${out.iou(truth)}", out.iou(truth) > loose.iou(truth) && out.iou(truth) > 0.9)
        m.release()
    }

    /** Tabla 8x4 en gris con un deslumbramiento circular que borra la mitad derecha de varias filas. */
    private fun glareTable(): Pair<Mat, Mat> {
        val src = Mat(1600, 1400, CvType.CV_8UC3, Scalar(225.0, 225.0, 225.0))
        val x0 = 100; val x1 = 1300; val ty = 400; val rh = 90
        for (r in 0..8) {
            val yy = ty + r * rh
            Imgproc.line(src, Point(x0.toDouble(), yy.toDouble()), Point(x1.toDouble(), yy.toDouble()), Scalar(120.0, 120.0, 120.0), 2)
            if (r < 8) for (c in 0 until 4) Imgproc.putText(src, "dato $r$c", Point(x0 + 30.0 + c * 300, yy + 55.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, Scalar(30.0, 30.0, 30.0), 2)
        }
        for (c in 0..4) Imgproc.line(src, Point(x0 + c * 300.0, ty.toDouble()), Point(x0 + c * 300.0, ty + 8.0 * rh), Scalar(120.0, 120.0, 120.0), 2)
        Imgproc.GaussianBlur(src, src, org.opencv.core.Size(0.0, 0.0), 1.0)
        // deslumbramiento: papel quemado (255) en un círculo sobre la parte derecha central
        Imgproc.circle(src, Point(1000.0, 760.0), 230, Scalar(255.0, 255.0, 255.0), -1)
        val out = Mat(1600, 1400, CvType.CV_8UC1, Scalar(255.0))
        return src to out
    }

    private fun rowCoverage(out: Mat, yy: Int): Double {
        val o = ByteArray(out.total().toInt()); out.get(0, 0, o)
        return (110..1290).count { x -> (yy - 3..yy + 3).any { (o[it * out.cols() + x].toInt() and 0xFF) < 160 } } / 1181.0
    }

    @Test fun tablas_conZonaIndicada_completaLaRejillaQuemada_ySinZonaNadaCambia() {
        assumeTrue(SyntheticScenes.openCv)
        val (src, out0) = glareTable()
        val out1 = out0.clone()
        TableLines.restore(out0, src, 26.0)
        TableLines.restore(out1, src, 26.0, hints = listOf(TableLines.Hint(doubleArrayOf(0.06, 0.24, 0.94, 0.71))))
        // filas que cruzan el deslumbramiento (y = 670, 760, 850): sin zona quedan cortadas, con zona completas
        for (yy in listOf(670, 760, 850)) {
            val a = rowCoverage(out0, yy); val b = rowCoverage(out1, yy)
            assertTrue("fila $yy: sin zona $a, con zona $b", b > 0.95 && b > a + 0.1)
        }
        // la salida sin zonas es idéntica a pasar una lista vacía
        val out2 = Mat(1600, 1400, CvType.CV_8UC1, Scalar(255.0))
        TableLines.restore(out2, src, 26.0, hints = emptyList())
        val d = Mat(); Core.absdiff(out0, out2, d)
        assertEquals(0, Core.countNonZero(d))
        src.release(); out0.release(); out1.release(); out2.release()
    }

    @Test fun ocr_cambiaSoloConProbabilidadAlta_yPromueveLineaVerificada() {
        assumeTrue(SyntheticScenes.openCv)
        val m = Mat(400, 800, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        Imgproc.putText(m, "Hola mundo", Point(20.0, 80.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(0.0, 0.0, 0.0), 3)
        val w1 = OcrWord("Ho1a", OcrRect(20f, 40f, 140f, 90f), 0.3f)
        val w2 = OcrWord("rnundo", OcrRect(160f, 40f, 330f, 90f), 0.35f)
        val line = OcrLine("Ho1a rnundo", OcrRect(20f, 40f, 330f, 90f), listOf(w1, w2), confidence = 0.6f)
        val doubt = OcrLine("segunda", OcrRect(20f, 200f, 300f, 260f), confidence = 0.3f)
        val res = OcrResult("Ho1a rnundo", 800, 400, listOf(OcrBlock("Ho1a rnundo", line.box, listOf(line))), doubtful = listOf(doubt))
        // respuestas: palabra 1 -> c1 ("Hola") con 0,93; palabra 2 -> c1 con 0,6 (no basta); línea -> sí 0,95
        val transport = JevTransport { _, _, body ->
            val ids = Regex("\"(w0_[01]|l0)\":\\{").findAll(body).map { it.groupValues[1] }.toList()
            val ans = ids.joinToString(",") { id ->
                when (id) {
                    "w0_0" -> "\"w0_0\":{\"type\":\"choice\",\"choice\":\"c1\",\"probabilities\":{\"c0\":0.05,\"c1\":0.93,\"other\":0.02}}"
                    "w0_1" -> "\"w0_1\":{\"type\":\"choice\",\"choice\":\"c1\",\"probabilities\":{\"c0\":0.3,\"c1\":0.6,\"other\":0.1}}"
                    else -> "\"l0\":{\"type\":\"noul\",\"noul\":0.95}"
                }
            }
            JevHttpReply(200, "{\"model\":\"jev-1.14.0\",\"usage\":{\"input_tokens\":1500,\"output_tokens\":30},\"answers\":{$ans}}")
        }
        val (out, rep) = JevOcr(suggest = { w -> if (w == "Ho1a") listOf("Hola") else listOf("mundo") }).apply(res, m, JevClient("k", transport))
        assertEquals(listOf("Ho1a" to "Hola"), rep.changed)
        assertTrue(out.text, out.text.startsWith("Hola rnundo"))
        assertTrue(out.text.contains("segunda"))
        assertTrue(out.doubtful.isEmpty())
        assertEquals(1500, rep.inputTokens)
        m.release()
    }

    @Suppress("unused") private fun clamp(v: Int) = min(255, v)
}
