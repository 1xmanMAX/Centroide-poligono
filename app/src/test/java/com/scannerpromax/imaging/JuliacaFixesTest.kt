package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.random.Random

/**
 * Defectos de las fotos reales de un informe impreso (juliaca2): paneles de fotos girados 90°, fotos de tonos apagados,
 * páginas de una misma plantilla, cabeceras de tabla con texto claro, rayas de tabla finas y claras, hoja con mucho
 * blanco abajo. Escenas sintéticas que reproducen cada caso.
 */
class JuliacaFixesTest {

    @Before fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    /** Foto impresa "apagada" (pared beige con ladrillos y tierra gris, sin color saturado) en [r] de [m] (RGB). */
    private fun palePhoto(m: Mat, r: Rect, seed: Int) {
        val rnd = Random(seed)
        val ph = Mat(r.height, r.width, CvType.CV_8UC3, Scalar(195.0, 187.0, 175.0))
        Imgproc.rectangle(ph, Point(0.0, r.height * 0.6), Point(r.width.toDouble(), r.height.toDouble()), Scalar(170.0, 162.0, 150.0), -1)
        // ladrillos, piedras y grietas de tonos parecidos
        repeat(260) {
            val x = rnd.nextInt(r.width); val y = rnd.nextInt(r.height); val d = rnd.nextInt(-30, 31).toDouble()
            val c = if (y > r.height * 0.6) 160.0 else 186.0
            Imgproc.rectangle(ph, Point(x.toDouble(), y.toDouble()), Point(x + 10.0 + rnd.nextInt(40), y + 6.0 + rnd.nextInt(16)), Scalar(c + d + 8, c + d, c + d - 10), -1)
        }
        val noise = Mat(r.height, r.width, CvType.CV_8UC3); Core.randn(noise, 0.0, 8.0)
        val f = Mat(); ph.convertTo(f, CvType.CV_16SC3); val nf = Mat(); noise.convertTo(nf, CvType.CV_16SC3)
        Core.randn(nf, 0.0, 22.0); Core.add(f, nf, f); f.convertTo(ph, CvType.CV_8UC3)
        Imgproc.GaussianBlur(ph, ph, Size(0.0, 0.0), 0.8)
        val sub = m.submat(r); ph.copyTo(sub); sub.release()
        listOf(ph, noise, f, nf).forEach { it.release() }
    }

    /** Panel de fotos: membrete, dos fotos apagadas con su pie y texto; RGB [w]x[h]. */
    private fun photoPanel(w: Int = 1000, h: Int = 1400): Pair<Mat, List<Rect>> {
        val m = SyntheticScenes.textPage(w, h, 0.7)
        // vertical: una foto encima de otra; apaisado (panel de A4 girado): una al lado de la otra
        val photos = if (h >= w) listOf(Rect(150, 260, 700, 430), Rect(150, 760, 700, 430)) else listOf(Rect(130, 200, 520, 460), Rect(750, 200, 520, 460))
        for ((k, r) in photos.withIndex()) {
            Imgproc.rectangle(m, Point(r.x - 3.0, r.y - 3.0), Point(r.x + r.width + 3.0, r.y + r.height + 3.0), Scalar(230.0, 230.0, 230.0), -1)
            palePhoto(m, r, k + 1)
        }
        return m to photos
    }

    // ---------------------------------------------------------------------------------------- fotos por textura

    @Test fun fotosApagadas_seEncuentranEnteras_porTextura() {
        val (m, photos) = photoPanel()
        val out = MatBag().use { bag -> PrintedPage.textureBlocks(m, 236.0, m.cols(), m.rows(), bag) }
        assertEquals("fotos: $out", 2, out.size)
        for (p in photos) {
            val best = out.maxOf { r -> val ix = maxOf(0, minOf(r.x + r.width, p.x + p.width) - maxOf(r.x, p.x)); val iy = maxOf(0, minOf(r.y + r.height, p.y + p.height) - maxOf(r.y, p.y)); ix * iy.toDouble() / p.area() }
            assertTrue("foto $p cubierta al %.2f".format(best), best >= 0.9)
        }
        m.release()
    }

    @Test fun paginaDeTextoDenso_yTabla_sinFotos() {
        val m = SyntheticScenes.textPage(1000, 1400, 0.6)
        // tabla con rayas finas y renglones de texto
        for (y in 300..1100 step 40) Imgproc.line(m, Point(100.0, y.toDouble()), Point(900.0, y.toDouble()), Scalar(150.0, 150.0, 160.0), 1)
        for (x in listOf(100, 300, 600, 900)) Imgproc.line(m, Point(x.toDouble(), 300.0), Point(x.toDouble(), 1100.0), Scalar(150.0, 150.0, 160.0), 1)
        val out = MatBag().use { bag -> PrintedPage.textureBlocks(m, 236.0, m.cols(), m.rows(), bag) }
        assertTrue("falsas fotos: $out", out.isEmpty())
        m.release()
    }

    // ---------------------------------------------------------------------------------------- bandas rellenas

    @Test fun cabeceraDeTablaConTextoClaro_esUnaBandaEntera() {
        val m = SyntheticScenes.textPage(1000, 1400, 0.6)
        Imgproc.rectangle(m, Point(100.0, 400.0), Point(900.0, 440.0), Scalar(70.0, 120.0, 190.0), -1)
        for ((k, t) in listOf("Fecha", "Dia", "Jornada", "Detalle de la actividad").withIndex())
            Imgproc.putText(m, t, Point(120.0 + 190 * k, 430.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, Scalar(250.0, 250.0, 250.0), 2)
        val out = MatBag().use { bag -> PrintedPage.bandBlocks(m, m.cols(), m.rows(), bag) }
        assertEquals("bandas: $out", 1, out.size)
        val r = out[0]
        assertTrue("banda $r", r.x <= 110 && r.x + r.width >= 890 && r.y <= 405 && r.y + r.height >= 435 && r.height <= 70)
        m.release()
    }

    @Test fun renglonesDeTexto_noSonBandas() {
        val m = SyntheticScenes.textPage(1000, 1400, 0.6)
        val out = MatBag().use { bag -> PrintedPage.bandBlocks(m, m.cols(), m.rows(), bag) }
        assertTrue("bandas falsas: $out", out.isEmpty())
        m.release()
    }

    // ---------------------------------------------------------------------------------------- orientación

    @Test fun panelDeFotosGirado90_seEndereza() {
        val (m, _) = photoPanel(1400, 1000)
        val g = Mat(); Imgproc.cvtColor(m, g, Imgproc.COLOR_RGB2GRAY)
        val ccw = Mat(); Core.rotate(g, ccw, Core.ROTATE_90_COUNTERCLOCKWISE)
        val cw = Mat(); Core.rotate(g, cw, Core.ROTATE_90_CLOCKWISE)
        val rc = TextOrientation.estimate(ccw)
        assertEquals("eje: $rc", true, rc.vertical)
        assertNotEquals(270, TextOrientation.decide(rc, null))
        assertEquals("eje: ${TextOrientation.estimate(cw)}", true, TextOrientation.estimate(cw).vertical)
        assertNotEquals(90, TextOrientation.decide(TextOrientation.estimate(cw), null))
        listOf(m, g, ccw, cw).forEach { it.release() }
    }

    @Test fun panelDeFotosDerecho_noSeGira() {
        val (m, _) = photoPanel(1000, 1400)
        val g = Mat(); Imgproc.cvtColor(m, g, Imgproc.COLOR_RGB2GRAY)
        assertEquals(0, TextOrientation.decide(TextOrientation.estimate(g), null))
        m.release(); g.release()
    }

    // ---------------------------------------------------------------------------------------- duplicados

    /** Página de plantilla: membrete y pie iguales ([0]), cuerpo distinto según [seed] (gris 8U). */
    private fun templatePage(seed: Int): Mat {
        val m = Mat(1400, 1000, CvType.CV_8UC1, Scalar(235.0))
        fun text(y0: Int, y1: Int, s: Int) {
            val r = Random(s); var y = y0
            while (y < y1) {
                var x = 50
                while (x < 930) {
                    val w = 20 + r.nextInt(70)
                    Imgproc.putText(m, (1..(w / 14).coerceAtLeast(1)).map { ('a' + r.nextInt(26)) }.joinToString(""), Point(x.toDouble(), y.toDouble()),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, Scalar(25.0), 2)
                    x += w + 18
                }
                y += 38
            }
        }
        text(60, 140, 0)            // membrete común
        text(1290, 1350, 0)         // pie común
        text(200, 1240, seed)       // cuerpo propio de cada página
        return m
    }

    @Test fun paginasDeUnaMismaPlantilla_noSonDuplicadas_yLaMismaHojaSi() {
        val a = templatePage(1); val c = templatePage(2)
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(1000.0, 0.0), Point(1000.0, 1400.0), Point(0.0, 1400.0))
        val dst = MatOfPoint2f(Point(20.0, 12.0), Point(985.0, 25.0), Point(992.0, 1390.0), Point(8.0, 1376.0))
        val h = Imgproc.getPerspectiveTransform(src, dst)
        val b = Mat(); Imgproc.warpPerspective(a, b, h, Size(1000.0, 1400.0), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(235.0))
        b.convertTo(b, -1, 0.92, 12.0)
        val fa = DuplicatePages.features(a); val fb = DuplicatePages.features(b); val fc = DuplicatePages.features(c)
        val same = DuplicatePages.match(fa, fb); val tpl = DuplicatePages.match(fa, fc)
        assertEquals("misma hoja: ${same.inliers} sim=${same.similarity}", DuplicatePages.Verdict.DUPLICATE, DuplicatePages.verdict(same))
        assertNotEquals("plantilla: ${tpl.inliers} sim=${tpl.similarity}", DuplicatePages.Verdict.DUPLICATE, DuplicatePages.verdict(tpl))
        listOf(fa, fb, fc).forEach { it.release() }; listOf(a, b, c).forEach { it.release() }
    }

    // ---------------------------------------------------------------------------------------- detección y tablas

    @Test fun redQueCortaUnaHojaLimpia_seQuedaElClasico() {
        // hoja entera dentro (toda la tinta, sin fondo); la red deja fuera la mitad del papel y algo de texto
        assertTrue(DocumentDetector.nnCutsCleanSheet(1.0, 0.0, 0.98, 0.93, 0.49))
        // la red recorta lo que sobra de un contorno con fondo ajeno: se respeta la red
        assertFalse(DocumentDetector.nnCutsCleanSheet(0.97, 0.20, 0.95, 0.96, 0.60))
        // la red apenas quita papel: decide la red
        assertFalse(DocumentDetector.nnCutsCleanSheet(1.0, 0.0, 0.98, 0.95, 0.90))
    }

    private fun cand(hor: Boolean, a0: Double, a1: Double, c: Double, ink: Double, rms: Double, run: Int, out: Double, amp: Double): TableLinesMath.Cand {
        val n = (a1 - a0).toInt() + 1
        val tr = TableLinesMath.Track(a0.toInt(), a1.toInt(), DoubleArray(n) { c }, 2.0, amp, ink, 5, false, rms, 0.0, 0.0, run, true, null, 0.0, 0)
        return TableLinesMath.Cand(hor, a0, a1, c, tr, out)
    }

    @Test fun rayasLargasFinasYClaras_rotas_seRedibujan_peroNoUnRenglon() {
        val c = ArrayList<TableLinesMath.Cand>()
        // filas de una tabla de 2700 px: tinta a trozos (tramos cortos), centro ruidoso, la salida casi no las muestra
        for (k in 0..4) c.add(cand(true, 400.0, 3100.0, 1000.0 + 90 * k, ink = 0.85, rms = 1.25, run = 200, out = 0.03, amp = 17.0))
        // un renglón de texto corto con el mismo aspecto: no
        c.add(cand(true, 400.0, 900.0, 2500.0, ink = 0.85, rms = 1.25, run = 60, out = 0.5, amp = 60.0))
        val ok = TableLinesMath.accept(c, 3500, 5000, 25.0, 7.0, inkRef = 150.0)
        for (k in 0..4) assertTrue("fila $k", ok[k])
        assertFalse("renglón", ok[5])
    }
}
