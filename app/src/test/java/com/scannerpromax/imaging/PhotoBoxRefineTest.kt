package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Recuadro EXACTO de las fotografías ([PhotoBoxRefine]) y capa de fotos ([PhotoLayer]): queja de la v29 (pág. 25: la caja de
 * la foto de abajo abarcaba el pie, el blanco, la firma y el sello; la firma salía en un recuadro gris). Escenas sintéticas
 * con un panel de fotos (marco, foto y pie de foto con fondo claro), firma, sello y logo.
 */
class PhotoBoxRefineTest {

    private fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    /** Foto "de cámara" en [r] de [m] (RGB): cielo en degradado, bloques de colores variados, grano. */
    private fun photo(m: Mat, r: Rect, seed: Int) {
        val rnd = Random(seed)
        val ph = Mat(r.height, r.width, CvType.CV_8UC3)
        for (y in 0 until r.height) {
            val t = y.toDouble() / r.height
            Imgproc.line(ph, Point(0.0, y.toDouble()), Point(r.width.toDouble(), y.toDouble()), Scalar(90 + 80 * t, 140 + 40 * t, 210 - 90 * t), 1)
        }
        repeat(400) {
            val x = rnd.nextInt(r.width); val y = rnd.nextInt(r.height / 3, r.height)
            val c = Scalar(rnd.nextInt(40, 220).toDouble(), rnd.nextInt(40, 220).toDouble(), rnd.nextInt(30, 200).toDouble())
            Imgproc.rectangle(ph, Point(x.toDouble(), y.toDouble()), Point(x + 8.0 + rnd.nextInt(60), y + 6.0 + rnd.nextInt(40)), c, -1)
        }
        val f = Mat(); ph.convertTo(f, CvType.CV_16SC3)
        val nf = Mat(r.height, r.width, CvType.CV_16SC3); Core.randn(nf, 0.0, 28.0); Core.add(f, nf, f); f.convertTo(ph, CvType.CV_8UC3)
        Imgproc.GaussianBlur(ph, ph, Size(0.0, 0.0), 0.8)
        val sub = m.submat(r); ph.copyTo(sub); sub.release()
        listOf(ph, f, nf).forEach { it.release() }
    }

    /** Panel: marco fino, foto [p] y pie de foto con fondo gris azulado claro y dos renglones. */
    private fun panel(m: Mat, p: Rect, seed: Int): Rect {
        val cap = Rect(p.x, p.y + p.height + 6, p.width, 70)
        Imgproc.rectangle(m, Point(p.x - 18.0, p.y - 18.0), Point(p.x + p.width + 18.0, cap.y + cap.height + 8.0), Scalar(60.0, 80.0, 120.0), 2)
        photo(m, p, seed)
        Imgproc.rectangle(m, cap.tl(), cap.br(), Scalar(218.0, 226.0, 236.0), -1)
        Imgproc.putText(m, "Fotografia N 08 | Sabado 12/09/2026", Point(cap.x + 8.0, cap.y + 26.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, Scalar(40.0, 80.0, 150.0), 2)
        Imgproc.putText(m, "Psje. Los Lirios - plantillado y marcado del nivel", Point(cap.x + 8.0, cap.y + 56.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, Scalar(30.0, 30.0, 30.0), 1)
        return cap
    }

    /** Firma azul (trazos finos) y sello circular de tinta. */
    private fun signatureAndStamp(m: Mat, x: Int, y: Int) {
        val pts = MatOfPoint(*Array(40) { i -> Point(x + i * 7.0, y + 30 * kotlin.math.sin(i * 0.7) + i * 1.5) })
        Imgproc.polylines(m, listOf(pts), false, Scalar(30.0, 50.0, 170.0), 3)
        Imgproc.line(m, Point(x - 20.0, y + 70.0), Point(x + 320.0, y + 10.0), Scalar(30.0, 50.0, 170.0), 2)
        Imgproc.putText(m, "TOPOGRAFO", Point(x + 20.0, y + 110.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, Scalar(30.0, 50.0, 150.0), 2)
        Imgproc.circle(m, Point(x + 500.0, y + 60.0), 70, Scalar(150.0, 60.0, 140.0), 3)
        Imgproc.putText(m, "SELLO", Point(x + 455.0, y + 68.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, Scalar(150.0, 60.0, 140.0), 2)
    }

    /** Logo / cabecera: bloque azul plano con texto blanco. */
    private fun logo(m: Mat): Rect {
        val r = Rect(90, 50, 420, 90)
        Imgproc.rectangle(m, r.tl(), r.br(), Scalar(50.0, 90.0, 170.0), -1)
        Imgproc.putText(m, "JULIACA", Point(140.0, 115.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.6, Scalar(250.0, 250.0, 250.0), 4)
        return r
    }

    /** Página de 1000x1400 con logo, un panel de foto y la firma con el sello. */
    private fun page(): Triple<Mat, Rect, Rect> {
        val m = Mat(1400, 1000, CvType.CV_8UC3, Scalar(236.0, 235.0, 230.0))
        val nz = Mat(m.size(), CvType.CV_8UC3); Core.randn(nz, 0.0, 2.0); Core.add(m, nz, m); nz.release()
        val lg = logo(m)
        val p = Rect(170, 300, 660, 450)
        panel(m, p, 3)
        signatureAndStamp(m, 250, 1120)
        return Triple(m, p, lg)
    }

    private fun iou(a: Rect, b: Rect): Double {
        val iw = min(a.x + a.width, b.x + b.width) - max(a.x, b.x); val ih = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        if (iw <= 0 || ih <= 0) return 0.0
        val i = iw.toDouble() * ih
        return i / (a.area() + b.area() - i)
    }

    private fun refine(m: Mat, cands: List<Rect>, verdicts: List<PhotoBoxRefine.Verdict> = emptyList()) =
        PhotoBoxRefine.refine(m, 236.0, cands.map { PhotoBoxRefine.Cand(it, PhotoBoxRefine.SRC_JEV) }, m.cols(), m.rows(), full = m, verdicts = verdicts)

    // ------------------------------------------------------------------------------------------------ recuadro exacto

    @Test fun cajaHolgada_queAbarcaPieFirmaYSello_seAjustaALaFoto() {
        cv()
        val (m, p, _) = page()
        // como la de la pág. 25: desde la foto hasta el pie de página (pie de foto, blanco, firma y sello)
        val found = refine(m, listOf(Rect(140, 270, 740, 1060)))
        val photos = PhotoBoxRefine.photos(found)
        assertEquals("fotos: $photos ($found)", 1, photos.size)
        assertTrue("IoU ${iou(photos[0], p)} ${photos[0]} vs $p", iou(photos[0], p) >= 0.95)
        // nada del pie de foto ni de la firma
        assertTrue("el pie de foto entra: ${photos[0]}", photos[0].y + photos[0].height <= p.y + p.height + 6)
        m.release()
    }

    @Test fun unaCajaConDosFotosSeparadasPorElPie_seDivide() {
        cv()
        val m = Mat(1400, 1000, CvType.CV_8UC3, Scalar(236.0, 235.0, 230.0))
        val a = Rect(170, 120, 660, 420); val b = Rect(170, 700, 660, 420)
        panel(m, a, 1); panel(m, b, 2)
        val photos = PhotoBoxRefine.photos(refine(m, listOf(Rect(120, 80, 760, 1200))))
        assertEquals("fotos: $photos", 2, photos.size)
        for (t in listOf(a, b)) assertTrue("foto $t: $photos", photos.any { iou(it, t) >= 0.95 })
        m.release()
    }

    @Test fun trozoDeLaFoto_daLaFotoEntera_yUnaCajaSobrePapelNoAmplia() {
        cv()
        val (m, p, _) = page()
        val frag = Rect(p.x + 100, p.y + 200, 120, 80)
        val photos = PhotoBoxRefine.photos(refine(m, listOf(frag)))
        assertEquals("fotos: $photos", 1, photos.size)
        assertTrue("IoU ${iou(photos[0], p)}", iou(photos[0], p) >= 0.95)
        // una pista sobre papel blanco (abajo a la derecha, sin nada) no crea nada
        assertTrue(PhotoBoxRefine.photos(refine(m, listOf(Rect(600, 900, 300, 150)))).isEmpty())
        m.release()
    }

    @Test fun firmaSelloYLogo_noSonFotografias() {
        cv()
        val (m, _, lg) = page()
        val found = refine(m, listOf(Rect(200, 1080, 400, 180), Rect(660, 1090, 200, 180), lg))
        assertTrue("fotos falsas: ${found.filter { it.photo }.map { "${it.rect} ${it.m}" }}", PhotoBoxRefine.photos(found).isEmpty())
        m.release()
    }

    @Test fun jev_vetaUnaFoto_yConfirmaUnaDudosa_peroNuncaAmplia() {
        cv()
        val (m, p, _) = page()
        fun fr(r: Rect) = floatArrayOf(r.x / 1000f, r.y / 1400f, (r.x + r.width) / 1000f, (r.y + r.height) / 1400f)
        val veto = PhotoBoxRefine.photos(refine(m, listOf(p), listOf(PhotoBoxRefine.Verdict(fr(p), 0.1))))
        assertTrue("vetada: $veto", veto.isEmpty())
        // "sí" de Jev sobre una caja enorme: la foto sigue con su recuadro exacto
        val big = Rect(100, 200, 800, 1150)
        val yes = PhotoBoxRefine.photos(refine(m, listOf(big), listOf(PhotoBoxRefine.Verdict(fr(big), 0.99))))
        assertEquals(1, yes.size)
        assertTrue("IoU ${iou(yes[0], p)}", iou(yes[0], p) >= 0.95)
        m.release()
    }

    // ------------------------------------------------------------------------------------------------ dos capas

    /** Render del filtro con la capa de fotos o sin ella; devuelve también las fotografías. */
    private fun render(m: Mat, style: TextRegions.Style, layer: Boolean): Pair<Mat, List<Rect>> {
        var photos: List<Rect> = emptyList()
        val prevLayer = PrintedPage.PHOTO_LAYER; val prevHook = PrintedPage.onPhotoBoxes
        PrintedPage.PHOTO_LAYER = layer
        PrintedPage.onPhotoBoxes = { _, f, _, _ -> photos = PhotoBoxRefine.photos(f) }
        try {
            val out = PrintedPage.render(m, style, fast = false, maxPixels = 4_000_000, layoutImages = listOf(floatArrayOf(0.1f, 0.15f, 0.9f, 0.95f)))
            return out to photos
        } finally { PrintedPage.PHOTO_LAYER = prevLayer; PrintedPage.onPhotoBoxes = prevHook }
    }

    @Test fun fueraDeLasFotos_laSalidaEsLaCapaBasePixelAPixel_naturalYBN() {
        cv()
        val (m, p, _) = page()
        for (style in listOf(TextRegions.Style.NATURAL, TextRegions.Style.BLACK_WHITE)) {
            val (withPh, photos) = render(m, style, true)
            val (base, photos2) = render(m, style, false)
            assertEquals("$style: fotos $photos", 1, photos.size)
            assertEquals(photos.map { it.toString() }, photos2.map { it.toString() })
            val sx = withPh.cols().toDouble() / m.cols()
            assertTrue("$style: IoU ${iou(photos[0], Rect((p.x * sx).toInt(), (p.y * sx).toInt(), (p.width * sx).toInt(), (p.height * sx).toInt()))}",
                iou(photos[0], Rect((p.x * sx).toInt(), (p.y * sx).toInt(), (p.width * sx).toInt(), (p.height * sx).toInt())) >= 0.93)
            // máscara de fuera de las fotos
            val outside = Mat(withPh.size(), CvType.CV_8UC1, Scalar(255.0))
            for (r in photos) Imgproc.rectangle(outside, r.tl(), Point(r.x + r.width - 1.0, r.y + r.height - 1.0), Scalar(0.0), -1)
            val diff = Mat(); Core.absdiff(withPh, base, diff)
            val d1 = if (diff.channels() == 3) Mat().also { Imgproc.cvtColor(diff, it, Imgproc.COLOR_RGB2GRAY) } else diff
            val dm = Mat(); Imgproc.threshold(d1, dm, 0.0, 255.0, Imgproc.THRESH_BINARY); dm.convertTo(dm, CvType.CV_8U)
            val outDiff = Mat(); Core.bitwise_and(dm, outside, outDiff)
            assertEquals("$style: píxeles distintos FUERA de las fotos", 0, Core.countNonZero(outDiff))
            // dentro sí cambia (la foto pegada) y el papel de alrededor de la firma sigue blanco
            val inDiff = Core.countNonZero(dm)
            assertTrue("$style: la foto no se pegó", inDiff > 0.3 * photos[0].area())
            listOf(withPh, base, outside, diff, dm, outDiff).forEach { it.release() }
        }
        m.release()
    }

    @Test fun colorNatural_firmaYSello_sobreBlancoPuro_sinRecuadro() {
        cv()
        val (m, _, _) = page()
        // la firma y el sello marcados como zona de tinta de Jev y como caja de foto de la maquetación (PP los daba)
        val lay = listOf(floatArrayOf(0.2f, 0.78f, 0.62f, 0.93f, NaturalColor.INK_FLAG), floatArrayOf(0.2f, 0.78f, 0.62f, 0.93f))
        val out = PrintedPage.render(m, TextRegions.Style.NATURAL, fast = false, maxPixels = 4_000_000, layoutImages = lay)
        val sx = out.cols().toDouble() / m.cols()
        // papel entre los trazos de la firma (franja sin tinta bajo el texto): blanco puro
        val r = Rect((260 * sx).toInt(), (1250 * sx).toInt(), (250 * sx).toInt(), (20 * sx).toInt())
        val sub = out.submat(r); val mean = Core.mean(sub); sub.release()
        assertTrue("fondo de la firma ${mean.`val`.toList()}", mean.`val`.take(3).all { it >= 250.0 })
        out.release(); m.release()
    }

    @Test fun capaDeFotos_noTocaNadaFueraDelRecuadro_yFundeComoMucho1px() {
        cv()
        val base = Mat(300, 400, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        val orig = Mat(600, 800, CvType.CV_8UC3, Scalar(120.0, 90.0, 60.0))
        val r = Rect(100, 80, 150, 120)
        val before = base.clone()
        PhotoLayer.paste(base, orig, listOf(r), doubleArrayOf(230.0, 230.0, 230.0))
        val outside = Mat(base.size(), CvType.CV_8UC1, Scalar(255.0)); Imgproc.rectangle(outside, r.tl(), Point(r.x + r.width - 1.0, r.y + r.height - 1.0), Scalar(0.0), -1)
        val diff = Mat(); Core.absdiff(base, before, diff); Imgproc.cvtColor(diff, diff, Imgproc.COLOR_RGB2GRAY)
        Core.bitwise_and(diff, outside, diff)
        assertEquals(0, Core.countNonZero(diff))
        // a 2 px del borde, ya la foto entera (sin mezcla)
        val inner = base.get(r.y + 2, r.x + 2); val center = base.get(r.y + 60, r.x + 75)
        for (c in 0 until 3) assertEquals(center[c], inner[c], 2.0)
        assertFalse("no se pegó", center[0] >= 250.0)
        listOf(base, orig, before, outside, diff).forEach { it.release() }
    }

    // ------------------------------------------------------------------------------------------------ reglas puras

    @Test fun tramos_puentesYRayas() {
        // perfil: raya del marco (2), hueco (3), foto (20) con franja pálida (5) y foto (20), pie corto (3) tras hueco de papel
        val d = DoubleArray(80) { 0.0 }
        for (i in 2..3) d[i] = 1.0
        for (i in 7..26) d[i] = 0.9
        for (i in 32..51) d[i] = 0.9
        for (i in 60..62) d[i] = 0.9
        val paper = BooleanArray(80) { it in 53..59 || it < 2 }
        val r = PhotoBoxRefine.runs(0, 80, 0.62, 4, 10, { paper[it] }, { a, b -> b - a <= 8 && (a until b).none { paper[it] } }, { it[1] - it[0] >= 6 }) { d[it] }
        assertEquals(1, r.size)
        assertEquals(7, r[0][0]); assertEquals(52, r[0][1])
    }

    @Test fun clasificacion_fotoFirmaLogo() {
        // (valores medidos en las fotos reales juliaca2)
        assertEquals(PhotoMath.Kind.PHOTO, PhotoMath.classify(PhotoBoxRefine.Metrics(0.95, 0.03, 0.9, 0.05, 15, 0.6, 0.38)))
        assertEquals(PhotoMath.Kind.PHOTO, PhotoMath.classify(PhotoBoxRefine.Metrics(0.83, 0.16, 0.82, 0.01, 13, 0.3, 0.70)))  // calzada pálida
        assertEquals(PhotoMath.Kind.NOT, PhotoMath.classify(PhotoBoxRefine.Metrics(0.4, 0.6, 0.35, 0.05, 5, 0.1, 0.8)))       // firma
        assertEquals(PhotoMath.Kind.NOT, PhotoMath.classify(PhotoBoxRefine.Metrics(0.99, 0.01, 0.42, 0.57, 15, 0.85, 0.72)))  // logo plano
        assertEquals(PhotoMath.Kind.DOUBTFUL, PhotoMath.classify(PhotoBoxRefine.Metrics(0.75, 0.22, 0.7, 0.05, 10, 0.3, 0.5)))
        assertTrue(PhotoMath.sameBox(intArrayOf(0, 0, 10, 10), intArrayOf(1, 1, 9, 9)))
        assertFalse(PhotoMath.sameBox(intArrayOf(0, 0, 10, 10), intArrayOf(20, 0, 30, 10)))
    }

    @Test fun filtroDeFoto_noQuemaLasLuces_yRealzaLoApagado() {
        // las luces se comprimen (nunca 1 de golpe) y la curva es monótona
        var prev = -1.0
        for (i in 0..120) { val v = PhotoMath.tone(i / 100.0, 0.02); assertTrue(v >= prev); prev = v }
        assertTrue(PhotoMath.tone(1.1, 0.0) < 1.0)
        assertTrue(PhotoMath.chromaGain(0.1) > PhotoMath.chromaGain(0.9))
        assertTrue(PhotoMath.chromaGain(0.9) >= 1.0)
    }
}
