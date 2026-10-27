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

    @Test fun zonas_rellenoAjenoNeutraYCantos() {
        val lh = 6.0
        // relleno liso: banda gruesa, contorno nítido; una sombra (contorno difuso) o junto al borde, no
        assertTrue(NaturalMath.isFlatFill(5000, 0.5, 9.0, lh, 16.0, 2.0, false))
        assertFalse("contorno difuso", NaturalMath.isFlatFill(5000, 0.5, 9.0, lh, 16.0, 12.0, false))
        assertFalse("junto al borde", NaturalMath.isFlatFill(5000, 0.5, 9.0, lh, 16.0, 2.0, true))
        assertFalse("línea fina", NaturalMath.isFlatFill(5000, 0.5, 4.0, lh, 16.0, 2.0, false))
        assertFalse("pequeño", NaturalMath.isFlatFill(300, 0.9, 9.0, lh, 16.0, 2.0, false))
        // fondo ajeno: otra cromaticidad y más oscuro (madera); una sombra conserva la cromaticidad; un resaltador es claro
        assertTrue(NaturalMath.isForeignColor(0.04, 0.78))
        assertFalse("sombra", NaturalMath.isForeignColor(0.005, 0.3))
        assertFalse("resaltador", NaturalMath.isForeignColor(0.06, 1.0))
        assertTrue("vetas", NaturalMath.foreignIsHard(0.3, 18.0)); assertTrue("liso", NaturalMath.foreignIsHard(0.0, 3.0))
        assertFalse("con escritura", NaturalMath.foreignIsHard(0.06, 18.0))
        assertFalse("página densa lisa", NaturalMath.foreignIsHard(0.3, 5.0))
        assertTrue("madera", NaturalMath.foreignHue(0.99, 7.0, 18.0))
        assertFalse("sombra cálida lisa", NaturalMath.foreignHue(1.0, 18.0, 5.5))
        assertFalse("ilustración", NaturalMath.foreignHue(0.55, 32.0, 14.0))
        // canto: largo y fino en la franja del 5 %; una letra junto al borde no
        assertTrue(NaturalMath.borderStroke(980, 100, 6, 400, 1200, 1000, 1000, lh))
        assertFalse(NaturalMath.borderStroke(980, 100, 6, 8, 40, 1000, 1000, lh))
        assertFalse("en el interior", NaturalMath.borderStroke(500, 100, 6, 400, 1500, 1000, 1000, lh))
        // pureza a partir de la cual la tinta es de color
        assertEquals(0.07, NaturalMath.neutralLo(1.0), 1e-9)
        assertTrue(NaturalMath.neutralLo(6.0) > 0.07)
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

    @Test fun rellenoLiso_pasaConSuColor_yTintaGrisANegro_yAzulSigueAzul() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        val nrm = Mat(H, W, CvType.CV_8UC3, Scalar.all(230.0))
        val bw = Mat(H, W, CvType.CV_8UC1, Scalar(255.0))
        // banda de tabla gris azulada (RGB normalizado) con texto gris oscuro encima; el B/N sólo tiene el texto
        val band = Scalar(185.0, 195.0, 210.0)
        Imgproc.rectangle(nrm, Point(60.0, 60.0), Point(580.0, 140.0), band, -1)
        for (x in 100..520 step 40) {
            Imgproc.line(nrm, Point(x.toDouble(), 80.0), Point(x + 8.0, 120.0), Scalar(70.0, 72.0, 75.0), 4)
            Imgproc.line(bw, Point(x.toDouble(), 80.0), Point(x + 8.0, 120.0), Scalar(0.0), 4, Imgproc.LINE_AA)
        }
        // texto gris "plomo" (casi neutro) y texto azul sobre el papel
        for (x in 100..520 step 40) {
            Imgproc.line(nrm, Point(x.toDouble(), 220.0), Point(x + 8.0, 260.0), Scalar(105.0, 105.0, 108.0), 4)
            Imgproc.line(bw, Point(x.toDouble(), 220.0), Point(x + 8.0, 260.0), Scalar(0.0), 4, Imgproc.LINE_AA)
            Imgproc.line(nrm, Point(x.toDouble(), 320.0), Point(x + 8.0, 360.0), BLUE, 4)
            Imgproc.line(bw, Point(x.toDouble(), 320.0), Point(x + 8.0, 360.0), Scalar(0.0), 4, Imgproc.LINE_AA)
        }
        val out = NaturalColor.compose(NaturalColor.Inputs(bw, nrm, 20.0, 2.0))
        // dentro de la banda, entre letras: el color de la banda (no blanco), sin halo blanco junto a la letra
        val inBand = px(out, 120, 100)
        assertTrue("banda: ${inBand.toList()}", inBand.average() < 245 && inBand[2] > inBand[0])
        val nearLetter = px(out, 107, 100)
        assertTrue("halo: ${nearLetter.toList()}", nearLetter.average() < 248)
        // texto gris -> casi negro y neutro
        val g = px(out, 104, 240)
        assertTrue("gris->negro: ${g.toList()}", g.max()!! < 80 && g.max()!! - g.min()!! < 12)
        // azul sigue azul
        val b = px(out, 104, 340)
        assertTrue("azul: ${b.toList()}", b[2] > b[0] + 30)
        // papel blanco puro
        assertEquals(255.0, Core.minMaxLoc(out.submat(Rect(10, 380, 80, 30)).reshape(1)).minVal, 0.0)
        out.release(); nrm.release(); bw.release()
    }

    // ---------------------------------------------------------------------------------------- fase 3

    @Test fun fase3_decisionesPuras() {
        assertEquals(100.0, NaturalMath.lightness(1.0), 1e-6)
        assertEquals(0.0, NaturalMath.lightness(0.0), 1e-6)
        assertEquals(53.4, NaturalMath.lightness(0.5), 0.2)   // gris sRGB medio
        // canto de la hoja: escalón en casi todo el lado, papel dentro y sin escritura del otro lado
        assertTrue(NaturalMath.sheetEdgeOk(20.0, 0.95, 0.9, 0))
        assertFalse("raya impresa (papel a ambos lados)", NaturalMath.sheetEdgeOk(2.0, 0.1, 1.0, 0))
        assertFalse("cruza la hoja", NaturalMath.sheetEdgeOk(20.0, 0.5, 0.9, 0))
        assertFalse("texto del otro lado", NaturalMath.sheetEdgeOk(20.0, 0.95, 0.9, 8))
        assertFalse("sin papel dentro", NaturalMath.sheetEdgeOk(20.0, 0.95, 0.3, 0))
        // cajas de la maquetación -> px
        val r = NaturalColor.layoutRects(listOf(floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f), floatArrayOf(0.5f, 0.5f, 0.5f, 0.9f)), 1000, 2000)
        assertEquals(1, r.size)
        assertEquals(Rect(100, 400, 400, 800), r[0])
    }

    /** Escena con tinta en toda la página (rejilla de "letras") sobre papel normalizado 230; [ink] color de la tinta por columna. */
    private fun textPage(ink: (Int) -> Scalar): Pair<Mat, Mat> {
        val nrm = Mat(H, W, CvType.CV_8UC3, Scalar.all(230.0))
        val bw = Mat(H, W, CvType.CV_8UC1, Scalar(255.0))
        for (y in 40 until H - 40 step 30) for (x in 40 until W - 60 step 24) {
            Imgproc.line(nrm, Point(x.toDouble(), y.toDouble()), Point(x + 8.0, y + 16.0), ink(x), 3)
            Imgproc.line(bw, Point(x.toDouble(), y.toDouble()), Point(x + 8.0, y + 16.0), Scalar(0.0), 3, Imgproc.LINE_AA)
        }
        return nrm to bw
    }

    private fun chroma(rgb: DoubleArray): Double {
        val m = Mat(1, 1, CvType.CV_32FC3); m.put(0, 0, floatArrayOf((rgb[0] / 255).toFloat(), (rgb[1] / 255).toFloat(), (rgb[2] / 255).toFloat()))
        Imgproc.cvtColor(m, m, Imgproc.COLOR_RGB2Lab); val v = m.get(0, 0); m.release()
        return Math.hypot(v[1], v[2])
    }

    @Test fun sombraCalida_laTintaNegraNoSaleMarron() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        // media hoja con luz cálida: en la original el papel es anaranjado; en la normalizada el papel es neutro pero la tinta
        // negra conserva la dominante de la luz (marrón)
        val x0 = 400
        val (nrm, bw) = textPage { x -> if (x >= x0) Scalar(95.0, 70.0, 40.0) else Scalar(60.0, 60.0, 60.0) }
        val orig = Mat(H, W, CvType.CV_8UC3, Scalar(215.0, 215.0, 212.0))
        Imgproc.rectangle(orig, Point(x0 - 5.0, 0.0), Point(W.toDouble(), H.toDouble()), Scalar(170.0, 135.0, 85.0), -1)
        val ref = NaturalColor.compose(NaturalColor.Inputs(bw, nrm, 20.0, 1.5))
        val out = NaturalColor.compose(NaturalColor.Inputs(bw, nrm, 20.0, 1.5, orig = orig))
        // trazo en la sombra cálida (centro de una "letra" de la derecha)
        val xs = 40 + 24 * ((x0 + 60 - 40) / 24); val p = px(out, xs + 4, 40 + 30 * 4 + 8); val p0 = px(ref, xs + 4, 40 + 30 * 4 + 8)
        assertTrue("sin luz local: ${p0.toList()} croma ${chroma(p0)}", chroma(p0) > 8)
        assertTrue("con luz local: ${p.toList()} croma ${chroma(p)}", chroma(p) < 0.5 * chroma(p0))
        // la tinta de la parte iluminada no cambia
        val q = px(out, 40 + 4, 40 + 30 * 4 + 8); val q0 = px(ref, 40 + 4, 40 + 30 * 4 + 8)
        for (c in 0 until 3) assertEquals(q0[c], q[c], 3.0)
        out.release(); ref.release(); orig.release(); nrm.release(); bw.release()
    }

    @Test fun fueraDeLaHoja_blanco_yElTextoJuntoAlMargenSeConserva() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        val (nrm, bw) = textPage { Scalar(50.0, 50.0, 55.0) }
        // la hoja termina en x = 590: más allá, madera (original) con vetas que el B/N y la normalizada conservan
        val orig = Mat(H, W, CvType.CV_8UC3, Scalar(215.0, 215.0, 212.0))
        Imgproc.rectangle(orig, Point(590.0, 0.0), Point(W.toDouble(), H.toDouble()), Scalar(120.0, 80.0, 45.0), -1)
        for (y in 10 until H step 23) {
            Imgproc.line(bw, Point(600.0, y.toDouble()), Point(630.0, y + 4.0), Scalar(30.0), 2)
            Imgproc.line(nrm, Point(600.0, y.toDouble()), Point(630.0, y + 4.0), Scalar(110.0, 75.0, 40.0), 2)
        }
        val out = NaturalColor.compose(NaturalColor.Inputs(bw, nrm, 20.0, 1.5, orig = orig))
        val wood = out.submat(Rect(596, 0, 44, H))
        assertEquals("fuera de la hoja", 255.0, Core.minMaxLoc(wood.reshape(1)).minVal, 0.0)
        // la última columna de "letras" (x ~ 568-576, a 15 px del canto) sigue ahí
        val last = 40 + 24 * ((W - 60 - 1 - 40) / 24)
        assertTrue("letra junto al margen: ${px(out, last + 4, 40 + 8).toList()}", px(out, last + 4, 40 + 8).average() < 140)
        wood.release(); out.release(); orig.release(); nrm.release(); bw.release()
    }

    @Test fun tintaGrisMuyClara_conservaSuGris_laOscuraPasaANegro() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        // texto gris oscuro (plomo) en casi toda la página y unos trazos gris claro (transparencia del reverso) que el B/N tiene
        val (nrm, bw) = textPage { x -> if (x in 280..330) Scalar(185.0, 185.0, 185.0) else Scalar(95.0, 95.0, 95.0) }
        val out = NaturalColor.compose(NaturalColor.Inputs(bw, nrm, 20.0, 1.5))
        val dark = px(out, 40 + 4, 40 + 30 * 3 + 8)
        assertTrue("plomo -> negro: ${dark.toList()}", dark.max()!! < 70)
        val xs = 40 + 24 * ((300 - 40) / 24)
        val light = px(out, xs + 4, 40 + 30 * 3 + 8)
        assertTrue("reverso conserva su gris: ${light.toList()}", light.average() > 85 && light.average() > dark.max()!! + 30)
        out.release(); nrm.release(); bw.release()
    }

    @Test fun cuaderno_fueraDeLaZonaDeEscritura_sinColorAnadido() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        val (nrm, bw) = scene()
        // dos trazos naranjas claros (el B/N no los ve): uno dentro de la zona de escritura y otro en el margen
        Imgproc.line(nrm, Point(560.0, 40.0), Point(600.0, 380.0), ORANGE, 5)
        val zone = Mat(H, W, CvType.CV_8UC1, Scalar(0.0))
        Imgproc.rectangle(zone, Point(40.0, 20.0), Point(530.0, 400.0), Scalar(255.0), -1)
        val out = NaturalColor.compose(NaturalColor.Inputs(bw, nrm, 20.0, 2.5, writing = zone))
        assertTrue("naranja dentro: ${px(out, 300, 295).toList()}", px(out, 300, 295).average() < 245)
        val margin = out.submat(Rect(545, 30, 70, 360))
        assertEquals("margen", 255.0, Core.minMaxLoc(margin.reshape(1)).minVal, 0.0)
        margin.release(); zone.release(); out.release(); nrm.release(); bw.release()
    }
}
