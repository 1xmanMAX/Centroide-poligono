package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * "Color natural" ([NaturalColor]): el B/N como máscara SUAVE del color real de la tinta sobre blanco puro, con unión de
 * trazos rotos guiada por la foto y la tinta de color clara que el B/N pierde (máscara por croma).
 */
class NaturalColorTest {

    // ---------------------------------------------------------------------------------------- decisiones puras

    @Test fun densidad_tintaOscuraSinCambio_tenueReforzadaConTope() {
        assertEquals(1.0, NaturalMath.densityGain(1.2), 1e-9)            // tinta negra: no cambia
        val k = NaturalMath.densityGain(0.25)                            // tinta pálida
        assertTrue("k=$k", k > 2.5 && k <= NaturalColor.K_MAX)
        assertEquals(NaturalColor.K_MAX, NaturalMath.densityGain(0.05), 1e-9)
    }

    @Test fun balanceDeBlancos_elPapelPasaABlanco_conDominanteAcotada() {
        val g = NaturalMath.whiteGains(doubleArrayOf(230.0, 230.0, 230.0))
        for (v in g) assertEquals(255.0 / 230.0, v, 1e-9)
        // papel azulado: el azul se atenúa respecto al rojo, pero la corrección no pasa de ±12 % de la media
        val c = NaturalMath.whiteGains(doubleArrayOf(200.0, 215.0, 250.0))
        assertTrue(c[0] > c[2])
        val m = c.average()
        for (v in c) assertTrue(v in m * 0.879..m * 1.121)
    }

    @Test fun umbralesRelativosAlPapel() {
        assertEquals(5.0, NaturalMath.chromaLow(0.5), 1e-9)
        assertEquals(10.0, NaturalMath.chromaLow(4.0), 1e-9)
        assertEquals(12.0, NaturalMath.chromaLow(20.0), 1e-9)
        assertEquals(0.08, NaturalMath.faintLow(1.0), 1e-9)
        assertTrue(NaturalMath.faintLow(6.0) > 0.1)
        assertEquals(0.0, NaturalMath.chromaFactor(2.0), 1e-9)          // casi neutro: sin tinte
        assertEquals(NaturalColor.SATURATION, NaturalMath.chromaFactor(40.0), 1e-9)
    }

    @Test fun componentesDeColor_clasificacion() {
        val lh = 10.0
        assertEquals("mota", 0, NaturalMath.colorComponentClass(1, 1, 1, lh))
        assertEquals("renglón largo y fino", 0, NaturalMath.colorComponentClass(400, 2, 500, lh, inkFrac = 0.0))
        assertEquals("retícula vacía", 0, NaturalMath.colorComponentClass(300, 300, 9000, lh, inkFrac = 0.0))
        assertEquals("palabras fundidas con tinta en el B/N", 1, NaturalMath.colorComponentClass(300, 80, 3600, lh, inkFrac = 0.2, thickness = 4.0))
        assertEquals("trazo de color", 1, NaturalMath.colorComponentClass(40, 12, 120, lh, inkFrac = 0.3, thickness = 2.0))
        assertEquals("figura con contenido", 2, NaturalMath.colorComponentClass(80, 60, 4000, lh, inkFrac = 0.1, thickness = 40.0))
        assertEquals("banda de color vacía", 0, NaturalMath.colorComponentClass(80, 60, 4000, lh, inkFrac = 0.0, thickness = 40.0))
        assertEquals("estrella de resaltador sin tinta", 1, NaturalMath.colorComponentClass(14, 14, 120, lh, inkFrac = 0.0, thickness = 13.0, vivid = true))
        assertEquals("mota débil sin tinta", 0, NaturalMath.colorComponentClass(14, 14, 120, lh, inkFrac = 0.0, thickness = 5.0, vivid = false))
        assertTrue(NaturalMath.touchesBorder(0, 10, 5, 5, 100, 100))
        assertFalse(NaturalMath.touchesBorder(10, 10, 5, 5, 100, 100))
        assertTrue(NaturalMath.nearBorder(2, 40, 5, 5, 100, 100))
        assertFalse(NaturalMath.nearBorder(40, 40, 5, 5, 100, 100))
    }

    // ---------------------------------------------------------------------------------------- composición (OpenCV)

    private val W = 640; private val H = 420
    private val BLUE = Scalar(55.0, 75.0, 175.0)          // bolígrafo azul (RGB normalizado: papel = 230)
    private val GAP_BLUE = Scalar(120.0, 135.0, 195.0)    // el mismo trazo, más tenue donde el B/N lo rompió
    private val ORANGE = Scalar(225.0, 175.0, 140.0)      // tinta naranja clara que el B/N no ve

    /** Foto normalizada (papel 230 con grano leve) con un trazo azul, un tramo tenue y un trazo naranja claro; y su B/N. */
    private fun scene(): Pair<Mat, Mat> {
        val nrm = Mat(H, W, CvType.CV_8UC3, Scalar.all(230.0))
        val noise = Mat(H, W, CvType.CV_8UC3); Core.randn(noise, 0.0, 1.5)
        val n16 = Mat(); nrm.convertTo(n16, CvType.CV_16SC3); noise.convertTo(noise, CvType.CV_16SC3); Core.add(n16, noise, n16); n16.convertTo(nrm, CvType.CV_8UC3)
        Imgproc.line(nrm, Point(100.0, 150.0), Point(500.0, 150.0), BLUE, 5)
        Imgproc.line(nrm, Point(295.0, 150.0), Point(305.0, 150.0), GAP_BLUE, 5)   // tramo tenue
        Imgproc.line(nrm, Point(150.0, 290.0), Point(450.0, 300.0), ORANGE, 5)
        for (x in 120..480 step 60) Imgproc.line(nrm, Point(x.toDouble(), 120.0), Point(x + 10.0, 180.0), BLUE, 4)   // "letras"
        // B/N: el trazo azul negro con bordes suaves, roto en el tramo tenue; el naranja no aparece
        val bw = Mat(H, W, CvType.CV_8UC1, Scalar(255.0))
        Imgproc.line(bw, Point(100.0, 150.0), Point(294.0, 150.0), Scalar(0.0), 5, Imgproc.LINE_AA)
        Imgproc.line(bw, Point(306.0, 150.0), Point(500.0, 150.0), Scalar(0.0), 5, Imgproc.LINE_AA)
        for (x in 120..480 step 60) Imgproc.line(bw, Point(x.toDouble(), 120.0), Point(x + 10.0, 180.0), Scalar(0.0), 4, Imgproc.LINE_AA)
        Imgproc.GaussianBlur(bw, bw, org.opencv.core.Size(0.0, 0.0), 0.7)
        return nrm to bw
    }

    private fun px(m: Mat, x: Int, y: Int): DoubleArray = m.get(y, x)

    @Test fun fondoBlancoPuro_colorReal_unionDeTrazos_yTintaDeColorClara() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        val (nrm, bw) = scene()
        var alpha: Mat? = null
        NaturalColor.debug = { k, m -> if (k == "alpha") alpha = m.clone() }
        val out = try { NaturalColor.compose(NaturalColor.Inputs(bw, nrm, 20.0, 2.5)) } finally { NaturalColor.debug = null }
        assertEquals(CvType.CV_8UC3, out.type())
        // 1) Papel: blanco PURO (255,255,255) lejos de la tinta, sin tinte ni grano
        for (r in listOf(Rect(10, 10, 80, 80), Rect(540, 330, 90, 80), Rect(200, 360, 200, 40))) {
            val roi = out.submat(r); val mn = Core.minMaxLoc(roi.reshape(1)).minVal
            assertEquals("papel en $r", 255.0, mn, 0.0); roi.release()
        }
        // 2) Color real: el trazo azul sigue azul (no negro ni gris) y bastante oscuro
        val core = px(out, 200, 150)
        assertTrue("azul: ${core.toList()}", core[2] > core[0] + 40 && core[2] < 230)
        // 3) Unión: el tramo que el B/N rompió (x 293..307) sale como tinta, con el color del trazo
        for (x in 296..304 step 2) {
            val p = px(out, x, 150)
            assertTrue("hueco en x=$x: ${p.toList()}", p.average() < 200 && p[2] > p[0])
        }
        // 4) Tinta naranja clara (ausente del B/N): presente y naranja (R > B), sobre blanco
        val o = px(out, 300, 295)
        assertTrue("naranja: ${o.toList()}", o[0] - o[2] > 25 && o.average() < 235)
        // 5) Máscara suave: el alfa tiene valores intermedios en los bordes (antialias), no sólo 0/255
        val a = alpha!!
        val band = a.submat(Rect(150, 140, 120, 20))
        val mid = Mat(); Core.inRange(band, Scalar(30.0), Scalar(225.0), mid)
        assertTrue("sin bordes suaves", Core.countNonZero(mid) > 40)
        mid.release(); band.release(); a.release(); out.release(); nrm.release(); bw.release()
    }

    @Test fun bridge_BN_uneElTrazoRotoSinEnsuciarElPapel() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        val (nrm, bw) = scene()
        val before = bw.get(150, 300)[0]
        NaturalColor.bridge(bw, nrm, 20.0, 2.5, level = 2)
        assertTrue("antes $before", before > 200)
        assertTrue("hueco sin unir: ${bw.get(150, 300)[0]}", bw.get(150, 300)[0] < before - 50)
        assertEquals("papel", 255.0, Core.minMaxLoc(bw.submat(Rect(10, 10, 80, 80))).minVal, 0.0)
        // el naranja claro NO entra en el B/N (sólo continuidad de trazos ya presentes)
        assertTrue(bw.get(295, 300)[0] > 200)
        nrm.release(); bw.release()
    }

    @Test fun rejilla_conTonoDeRejilla_noEntraComoColor() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        val (nrm, bw) = scene()
        // cuadrícula azul clara sobre toda la página (no está en el B/N)
        val grid = Scalar(185.0, 205.0, 228.0)
        for (x in 20 until W step 32) Imgproc.line(nrm, Point(x.toDouble(), 0.0), Point(x.toDouble(), H.toDouble()), grid, 2)
        for (y in 20 until H step 32) Imgproc.line(nrm, Point(0.0, y.toDouble()), Point(W.toDouble(), y.toDouble()), grid, 2)
        val out = NaturalColor.compose(NaturalColor.Inputs(bw, nrm, 20.0, 2.5, gridColor = doubleArrayOf(grid.`val`[0], grid.`val`[1], grid.`val`[2])))
        // una recta de la cuadrícula lejos de la tinta queda en blanco puro
        val roi = out.submat(Rect(560, 340, 60, 60))
        assertEquals(255.0, Core.minMaxLoc(roi.reshape(1)).minVal, 0.0)
        roi.release(); out.release(); nrm.release(); bw.release()
    }
}
