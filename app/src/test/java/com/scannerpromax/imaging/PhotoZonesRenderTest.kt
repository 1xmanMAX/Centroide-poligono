package com.scannerpromax.imaging

import com.scannerpromax.domain.PhotoZone
import com.scannerpromax.domain.PhotoZones
import com.scannerpromax.domain.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.random.Random

/**
 * Zonas de imagen a mano en el render real ([PrintedPage] + [PhotoLayer]): «Aquí no hay imágenes» deja la capa base,
 * «No es una imagen» quita sólo esa caja, una zona a mano trata como foto sólo su recuadro (o su contorno), y la
 * RECOMPOSICIÓN desde las capas guardadas da exactamente el mismo resultado que el render completo con esas zonas.
 */
class PhotoZonesRenderTest {

    private fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    /** Página con una foto en (170, 300, 660x450) y texto. */
    private fun page(): Pair<Mat, Rect> {
        val rnd = Random(7)
        val p = Rect(170, 300, 660, 450)
        val m = PhotoScenes.textPage(1000, 1400, rnd, skip = listOf(Rect(p.x - 20, p.y - 20, p.width + 40, p.height + 40)))
        val ph = PhotoScenes.synthPhoto(p.width, p.height, 3)
        val sub = m.submat(p); ph.copyTo(sub); sub.release(); ph.release()
        PhotoScenes.capture(m, rnd)
        return m to p
    }

    private fun render(m: Mat, zones: PhotoZones?, report: PhotoReport? = null): Mat =
        PrintedPage.render(m, TextRegions.Style.NATURAL, fast = false, maxPixels = 4_000_000, photoZones = zones, photoReport = report)

    private fun diffCount(a: Mat, b: Mat, mask: Mat? = null): Int {
        val d = Mat(); Core.absdiff(a, b, d)
        val g = if (d.channels() == 3) Mat().also { Imgproc.cvtColor(d, it, Imgproc.COLOR_RGB2GRAY) } else d
        val t = Mat(); Imgproc.threshold(g, t, 0.0, 255.0, Imgproc.THRESH_BINARY); t.convertTo(t, CvType.CV_8U)
        if (mask != null) Core.bitwise_and(t, mask, t)
        return Core.countNonZero(t).also { d.release(); g.release(); t.release() }
    }

    @Test fun zonasAMano_enElRender_yRecomposicionIdentica() {
        cv()
        val (m, p) = page()
        val rep = PhotoReport(keepLayers = true)
        val auto = render(m, null, rep)
        try {
            assertTrue("capas", rep.valid && rep.base != null && rep.orig != null)
            assertEquals("cajas automáticas ${rep.auto.map { it.toList() }}", 1, rep.auto.size)
            val base = rep.base!!
            // con la foto automática: algo cambia respecto de la base
            assertTrue(diffCount(auto, base) > 0.2 * p.area())

            // «Aquí no hay imágenes»: la capa base exacta
            val none = render(m, PhotoZoneMath.noPhotos())
            assertEquals(0, diffCount(none, base))
            // «No es una imagen» sobre la caja automática: igual
            val excl = render(m, PhotoZoneMath.excludeAuto(null, PhotoZoneMath.zone(rep.auto[0])))
            assertEquals(0, diffCount(excl, base))

            // sub-selección: sólo la mitad izquierda de la foto (ajuste de la caja automática) + una zona en el texto con lazo
            val a = rep.auto[0]
            val half = PhotoZone(a[0], a[1], (a[0] + a[2]) / 2, a[3])
            val lasso = PhotoZone(0.1f, 0.05f, 0.4f, 0.15f, poly = listOf(Pt(0.1f, 0.05f), Pt(0.4f, 0.05f), Pt(0.1f, 0.15f)))
            val zs = PhotoZoneMath.addManual(PhotoZoneMath.adjustAuto(null, PhotoZoneMath.zone(a), half), lasso)
            val full = render(m, zs)
            val w = full.cols(); val h = full.rows()
            // fuera de la mitad izquierda y del lazo: la base
            val outside = Mat(h, w, CvType.CV_8U, Scalar(255.0))
            Imgproc.rectangle(outside, Point((half.x0 * w).toDouble(), (half.y0 * h).toDouble()), Point((half.x1 * w).toDouble(), (half.y1 * h).toDouble()), Scalar(0.0), -1)
            Imgproc.rectangle(outside, Point((lasso.x0 * w).toDouble(), (lasso.y0 * h).toDouble()), Point((lasso.x1 * w).toDouble(), (lasso.y1 * h).toDouble()), Scalar(0.0), -1)
            assertEquals(0, diffCount(full, base, outside))
            // dentro del recuadro del lazo pero fuera del triángulo: la base
            val corner = Mat(h, w, CvType.CV_8U, Scalar(0.0))
            Imgproc.rectangle(corner, Point(0.36 * w, 0.13 * h), Point(0.4 * w - 1, 0.15 * h - 1), Scalar(255.0), -1)
            assertEquals(0, diffCount(full, base, corner))

            // RECOMPOSICIÓN desde las capas guardadas = render completo con esas zonas
            for (zz in listOf(zs, PhotoZoneMath.noPhotos(), null, PhotoZoneMath.addManual(null, PhotoZone(0.05f, 0.05f, 0.3f, 0.12f, normal = true)))) {
                val rb = base.clone()
                PhotoLayer.recompose(rb, rep.orig!!, rep.auto.map { it.toList() }, zz, rep.white)
                val ref = render(m, zz)
                assertEquals("recompuesta != render con $zz", 0, diffCount(rb, ref))
                rb.release(); ref.release()
            }
            listOf(none, excl, full, outside, corner).forEach { it.release() }
        } finally { rep.release(); auto.release(); m.release() }
    }

    @Test fun ajustesYPizarra_invalidanLasCapas() {
        cv()
        val (m, _) = page()
        val rep = PhotoReport(keepLayers = true)
        val out = ImageEnhancer.applyMat(m, com.scannerpromax.domain.FilterType.NATURAL, com.scannerpromax.domain.Adjustments(brightness = 0.3f),
            ImageEnhancer.Options.default().copy(photoReport = rep))
        assertTrue(rep.composed)
        assertTrue("con ajustes la base guardada no vale", !rep.valid)
        out.release(); rep.release(); m.release()
    }
}
