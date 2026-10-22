package com.scannerpromax.imaging

import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Detección por material de papel y orientación por renglones con escenas SINTÉTICAS (como las fotos de exámenes del
 * usuario: hoja girada 90° sobre madera con un teclado tapando un lado; hoja con un recuadro dibujado sobre fondo rojo).
 */
class PaperDetectionTest {

    private val tier = DeviceTier(false, 20_000_000, 8)

    @Before fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    /** Hoja apaisada con texto, fotografiada GIRADA 90° sobre madera con vetas y un teclado tapando su borde derecho. */
    private fun rotatedSheetOnWood(): Triple<Mat, DoubleArray, IntArray> {
        val w = 720; val h = 1280
        val scene = SyntheticScenes.wood(w, h)
        val page = SyntheticScenes.textPage(1000, 700)
        val rotated = Mat(); Core.rotate(page, rotated, Core.ROTATE_90_CLOCKWISE)   // renglones verticales, leídos de arriba abajo
        val sheet = SyntheticScenes.paste(scene, rotated, 2.0, 330.0, 640.0)
        val kb = intArrayOf(640, 380, 720, 1280)
        SyntheticScenes.keyboard(scene, kb[0], kb[1], kb[2], kb[3])
        page.release(); rotated.release()
        return Triple(scene, sheet, kb)
    }

    private fun count(m: Mat) = Core.countNonZero(m).toDouble()

    @Test fun hojaGiradaSobreMaderaConTeclado_recortaLaHojaSinElTeclado() {
        val (scene, sheet, kb) = rotatedSheetOnWood()
        val det = DocumentDetector(tier)
        val r = SyntheticScenes.detect(det, scene)
        assertNotNull("sin detección", r)
        assertTrue("confianza baja: ${r!!.confidence}", r.confidence >= 0.35f)
        val q = SyntheticScenes.quadArray(r)
        val w = scene.cols(); val h = scene.rows()
        val qm = SyntheticScenes.polyMask(w, h, q)
        val sm = SyntheticScenes.polyMask(w, h, sheet)
        // hoja visible = hoja sin el teclado
        Imgproc.rectangle(sm, Point(kb[0].toDouble(), kb[1].toDouble()), Point(kb[2].toDouble(), kb[3].toDouble()), Scalar(0.0), -1)
        val inter = Mat(); Core.bitwise_and(qm, sm, inter)
        val notSheet = Mat(); Core.bitwise_not(sm, notSheet); Core.bitwise_and(notSheet, qm, notSheet)
        val coverage = count(inter) / count(sm)
        val foreign = count(notSheet) / count(qm)
        assertTrue("hoja visible cubierta sólo al ${"%.2f".format(coverage)}", coverage >= 0.93)
        assertTrue("madera/teclado dentro del recorte: ${"%.3f".format(foreign)}", foreign <= 0.06)
        listOf(scene, qm, sm, inter, notSheet).forEach { it.release() }
    }

    @Test fun hojaGirada_seEnderezaConElGiroCorrecto() {
        val (scene, _, _) = rotatedSheetOnWood()
        val proc = PageProcessor(tier)
        val r = SyntheticScenes.detect(proc.detector, scene)
        val quad = r?.let { val p = it.quad.points(); Quad(p[0], p[1], p[2], p[3]) }
        // renglones de arriba abajo: se endereza girando 270° en sentido horario
        assertEquals(270, proc.autoOrientationMat(scene, quad))
        scene.release()
    }

    @Test fun paginaDerecha_yCuadricula_noSeGiran() {
        val page = SyntheticScenes.textPage(900, 1270)
        val g = Mat(); Imgproc.cvtColor(page, g, Imgproc.COLOR_RGB2GRAY)
        val r = TextOrientation.estimate(g)
        assertEquals(false, r.vertical)
        assertEquals(0, TextOrientation.decide(r, null))
        val grid = SyntheticScenes.gridPage(900, 1270)
        Imgproc.cvtColor(grid, g, Imgproc.COLOR_RGB2GRAY)
        assertEquals(0, TextOrientation.decide(TextOrientation.estimate(g), null))
        page.release(); grid.release(); g.release()
    }

    @Test fun paginaGirada90_enAmbosSentidos() {
        val page = SyntheticScenes.textPage(1000, 700)
        val g = Mat(); Imgproc.cvtColor(page, g, Imgproc.COLOR_RGB2GRAY)
        val cw = Mat(); Core.rotate(g, cw, Core.ROTATE_90_CLOCKWISE)
        val ccw = Mat(); Core.rotate(g, ccw, Core.ROTATE_90_COUNTERCLOCKWISE)
        assertEquals(270, TextOrientation.decide(TextOrientation.estimate(cw), null))
        assertEquals(90, TextOrientation.decide(TextOrientation.estimate(ccw), null))
        listOf(page, g, cw, ccw).forEach { it.release() }
    }

    /** Hoja que llena casi toda la foto sobre tela roja con un recuadro dibujado dentro: el recorte es la hoja, no el recuadro. */
    @Test fun recuadroDibujadoDentroDeLaHoja_noSeTomaPorLaHoja() {
        val w = 1192; val h = 1600
        val scene = Mat(h, w, CvType.CV_8UC3, Scalar(170.0, 30.0, 40.0))
        val page = SyntheticScenes.textPage(1150, 1560)
        // la mitad inferior sin texto y con un recuadro grande dibujado (con su propia rejilla), como un ejercicio de dibujo
        Imgproc.rectangle(page, Point(0.0, 800.0), Point(1150.0, 1470.0), Scalar(236.0, 235.0, 230.0), -1)
        Imgproc.rectangle(page, Point(60.0, 820.0), Point(1090.0, 1450.0), Scalar(40.0, 40.0, 60.0), 6)
        var x = 60; while (x < 1090) { Imgproc.line(page, Point(x.toDouble(), 820.0), Point(x.toDouble(), 1450.0), Scalar(90.0, 90.0, 110.0), 1); x += 40 }
        val sheet = SyntheticScenes.paste(scene, page, 1.0, w / 2.0, h / 2.0)
        val r = SyntheticScenes.detect(DocumentDetector(tier), scene)
        val used = r?.takeIf { it.confidence >= 0.35f }?.let { SyntheticScenes.quadArray(it) } ?: doubleArrayOf(0.0, 0.0, w.toDouble(), 0.0, w.toDouble(), h.toDouble(), 0.0, h.toDouble())
        val qm = SyntheticScenes.polyMask(w, h, used); val sm = SyntheticScenes.polyMask(w, h, sheet)
        val inter = Mat(); Core.bitwise_and(qm, sm, inter)
        val coverage = count(inter) / count(sm)
        assertTrue("el recorte deja fuera parte de la hoja: ${"%.2f".format(coverage)}", coverage >= 0.95)
        listOf(scene, page, qm, sm, inter).forEach { it.release() }
    }

    @Suppress("unused") private fun pt(x: Double, y: Double) = Pt(x.toFloat(), y.toFloat())
}
