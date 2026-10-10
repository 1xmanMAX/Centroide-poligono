package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Documentos IMPRESOS (tablas, formularios, informes): "Texto resaltado" y "Blanco y negro" tipo fotocopiadora.
 *
 * La segmentación por recuadros de [TextRegions] está pensada para escritura a mano en cuadernos: blanquea todo lo
 * que no reconoce como trazo, borra las rectas claras como si fueran la cuadrícula del papel y descarta componentes
 * pequeñas. En una hoja impresa densa eso perdía letras pequeñas, líneas finas de la tabla, sombreados y logos.
 *
 * Aquí se conserva TODO el contenido:
 *  1. [analyze] (a <= 1200 px, misma decisión en la vista previa y en el render final): ¿impreso? = muchas rectas
 *     largas, finas y OSCURAS en ambas direcciones (tabla/formulario) o mucho texto pequeño y regular de tinta
 *     oscura y neutra sin cuadrícula de color; además la altura de letra típica.
 *  2. Iluminación normalizada (fondo estimado a baja resolución: las columnas sombreadas y las barras de color,
 *     estrechas respecto del núcleo del cierre, no forman parte del fondo y se conservan).
 *  3. COLOR: niveles adaptativos al ruido (papel -> blanco puro), gamma suave para dar cuerpo al trazo fino,
 *     color real reforzado (sombreados azules, barras naranjas, logos: aclarados pero presentes) y máscara de
 *     enfoque con umbral sólo sobre la luminancia.
 *  4. BLANCO Y NEGRO: binarización LOCAL suave (Sauvola con rampa de anti-aliasing) sobre la luminancia normalizada:
 *     el fondo de una celda sombreada queda blanco y el texto encima, negro; las zonas muy oscuras siempre negras y
 *     el papel siempre blanco. Los logos/fotos (bloques compactos de color) se conservan en gris.
 *  5. Letra pequeña (< [SMALL_LETTER] px): ampliación de calidad (hasta x2, dentro del límite de píxeles) con
 *     realce de bordes antes de binarizar/realzar, todo por franjas (memoria acotada).
 */
internal object PrintedPage {

    /** Altura de letra (px de la imagen completa) por debajo de la cual se amplía el render final. */
    const val SMALL_LETTER = 18.0

    private const val ANALYSIS_SIDE = 1200

    /** Píxeles por franja del render (intermedios en coma flotante acotados). */
    private const val STRIP_PIXELS = 1_500_000

    internal var log: ((String) -> Unit)? = null

    /**
     * [printed] = usar este render (todo lo que no es una hoja de cuaderno con rayado, ver [PrintClassifier]);
     * [notebook] = hoja de cuaderno -> recuadros de escritura. [letterHeight] en px de la imagen analizada (completa).
     */
    class Stats(
        val printed: Boolean,
        val linesH: Int,
        val linesV: Int,
        val textComps: Int,
        val letterHeight: Double,
        val colorGrid: Boolean,
        val notebook: Boolean = !printed,
        val align: Double = 0.0,
        /** Tipo de página ([PrintClassifier.pageType]). */
        val type: PrintClassifier.PageType = if (printed) PrintClassifier.PageType.TEXT else PrintClassifier.PageType.NOTEBOOK,
        /** Fracción de la página ocupada por fotos, tramas, rellenos oscuros o bloques de color. */
        val pictureFrac: Double = 0.0,
        /** Recuadros (x0, y0, x1, y1 relativos) de fotos / tramas interiores (en B/N se conservan en gris si [type] es ILLUSTRATED). */
        val pictures: List<DoubleArray> = emptyList(),
    ) {
        /** Las mismas estadísticas para la imagen ampliada [k] veces (super-resolución previa al render). */
        fun scaled(k: Double) = Stats(printed, linesH, linesV, textComps, letterHeight * k, colorGrid, notebook, align, type, pictureFrac, pictures)
    }

    /**
     * Clasificación impreso / manuscrito. Trabaja a <= [ANALYSIS_SIDE] px (INTER_AREA): la vista previa (~1200 px)
     * y el render final deciden igual.
     */
    fun analyze(rgb: Mat): Stats = MatBag().use { bag ->
        val sm = bag.mat()
        val s = Cv.downscale(rgb, sm, ANALYSIS_SIDE)
        val w = sm.cols(); val h = sm.rows()
        // Imagen diminuta: nada que clasificar -> la ruta que conserva todo
        if (min(w, h) < 120) return@use Stats(true, 0, 0, 0, 0.0, false)
        val side = max(w, h).toDouble()
        val ch = ArrayList<Mat>(3); Core.split(sm, ch); for (c in ch) bag.add(c)
        // Oscuridad del canal MÁXIMO (la cuadrícula azul clara de un cuaderno apenas lo oscurece; la tinta negra o
        // gris de una tabla impresa, sí) y del canal mínimo (color)
        val v = bag.mat(); Core.max(ch[0], ch[1], v); Core.max(v, ch[2], v)
        val mn = bag.mat(); Core.min(ch[0], ch[1], mn); Core.min(mn, ch[2], mn)
        val bg = bag.add(Cv.estimateBackground(v, 256))
        val n = bag.mat(); Cv.divideByBackground(v, bg, n)
        val nm = bag.mat(); Cv.divideByBackground(mn, bg, nm)
        val (noise, pm) = Cv.paperStats(Cv.histogram(n))
        val d = bag.mat(); Core.bitwise_not(n, d); Core.subtract(d, Scalar(255.0 - pm), d)
        // Croma de cada píxel (V - min, normalizado) respecto del croma típico del papel
        val chroma = bag.mat(); Core.subtract(n, nm, chroma)
        val paperMask = bag.mat(); Core.compare(d, Scalar(max(6.0, 3.0 * noise)), paperMask, Core.CMP_LT)
        val paperChroma = Cv.percentile(Cv.histogram(chroma, paperMask), 0.5).toDouble()

        val lineThr = max(28.0, 5.0 * noise)
        val lines = bag.mat()
        val (lh, lv) = countLines(d, lineThr, side, bag, lines)
        // Rectas de COLOR (cuadrícula o renglones azules de un cuaderno, aunque oscurezcan algo el canal máximo):
        // las de una tabla impresa son negras o grises (neutras)
        val lineChroma = if (Core.countNonZero(lines) > 0) Core.mean(chroma, lines).`val`[0] - paperChroma else 0.0
        val lineDark = if (Core.countNonZero(lines) > 0) Core.mean(d, lines).`val`[0] else 0.0
        val colorGrid = lh + lv >= 6 && lineChroma > 10.0
        // Rectas CLARAS (renglones o cuadrícula de cuaderno, a lápiz o de color): umbral bajo
        val linesLow = bag.mat()
        val lowExt = DoubleArray(1)
        val (llh, llv) = countLines(d, max(10.0, 3.5 * noise), side, bag, linesLow, lowExt)
        val lowCnt = Core.countNonZero(linesLow)
        val lowThick = lowCnt / max(1.0, lowExt[0])
        val lowChroma = if (lowCnt > 0) Core.mean(chroma, linesLow).`val`[0] - paperChroma else 0.0
        val lowDark = if (lowCnt > 0) Core.mean(d, linesLow).`val`[0] else 0.0

        // Componentes de tinta oscura (texto): tamaño y regularidad
        val ink = bag.mat(); Core.compare(d, Scalar(max(60.0, 8.0 * noise)), ink, Core.CMP_GT)
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(ink, labels, stats, cents, 8, CvType.CV_32S)
        val st = IntArray(max(0, nc) * 5); if (nc > 0) stats.get(0, 0, st)
        val hs = ArrayList<Int>()
        val minH = max(3, (side * 0.0025).roundToInt()); val maxH = max(minH + 2, (side * 0.025).roundToInt())
        // Para la alineación de líneas base: también palabras enlazadas (más anchas) y letra algo mayor
        val maxH4 = max(minH + 2, (side * 0.04).roundToInt())
        val boxes = IntArray(max(0, nc) * 4); var nb = 0
        val bh = ArrayList<Int>()
        for (c in 1 until nc) {
            val cw = st[c * 5 + 2]; val chh = st[c * 5 + 3]; val a = st[c * 5 + 4]
            val tall = max(cw, chh)
            if (tall in minH..maxH && a >= 4 && min(cw, chh) * 8 >= tall) hs.add(tall)
            if (chh in minH..maxH4 && cw <= maxH4 * 6 && a >= 4) {
                boxes[nb * 4] = st[c * 5]; boxes[nb * 4 + 1] = st[c * 5 + 1]; boxes[nb * 4 + 2] = cw; boxes[nb * 4 + 3] = chh
                nb++; bh.add(chh)
            }
        }
        bh.sort()
        val align = if (nb < 8) 0.0 else PrintClassifier.baselineAlignment(boxes.copyOf(nb * 4), bh[bh.size / 2].toDouble())
        hs.sort()
        val med = if (hs.isEmpty()) 0.0 else hs[hs.size / 2].toDouble()
        val iqr = if (hs.size < 8) 99.0 else (hs[hs.size * 3 / 4] - hs[hs.size / 4]).toDouble() / max(1.0, med)
        // Tabla impresa: muchas rectas largas OSCURAS en ambas direcciones (si son de color es la cuadrícula de un cuaderno)
        val table = min(lh, lv) >= 6 && lh + lv >= 16
        // Texto impreso sin tabla: mucho texto pequeño (letra < 1.1 % del lado) y regular, sin cuadrícula de color
        val text = !colorGrid && hs.size >= 600 && med in 1.0..(side * 0.011) && iqr <= 0.6
        val notebook = PrintClassifier.isNotebook(PrintClassifier.Features(
            fullSide = max(rgb.cols(), rgb.rows()), rulingLines = llh + llv, rulingDark = lowDark, rulingChroma = lowChroma,
            rulingThick = lowThick, align = align, table = table && !colorGrid, denseText = text,
        ))
        val printed = !notebook
        val inkFrac = Core.countNonZero(ink) / max(1.0, ink.total().toDouble())
        val (rh, rv) = countRules(d, lineThr, side, bag)
        val pics = pictureFraction(d, chroma, paperChroma, bag)
        val picture = pics.inner
        val pf = PrintClassifier.PageFeatures(
            notebook = notebook, table = table && !colorGrid, denseText = text, linesH = rh, linesV = rv,
            textComps = hs.size, boxes = nb, align = align, letterRel = med / side, inkFrac = inkFrac,
            pictureFrac = picture, aspect = rgb.rows().toDouble() / max(1, rgb.cols()),
        )
        val type = PrintClassifier.pageType(pf)
        log?.invoke("printed=$printed type=$type notebook=$notebook llh=$llh llv=$llv lowChroma=%.1f lowDark=%.1f lowThick=%.2f align=%.2f ".format(lowChroma, lowDark, lowThick, align) + "lines H=$lh V=$lv lineChroma=%.1f (papel %.1f) dark=%.0f comps=${hs.size} boxes=$nb med=%.1f iqr=%.2f noise=%.1f table=$table text=$text ink=%.4f pic=%.3f picAll=%.3f nPic=${pics.rects.size} aspect=%.2f rh=$rh rv=$rv".format(lineChroma, paperChroma, lineDark, med, iqr, noise, inkFrac, picture, pics.frac, pf.aspect))
        Stats(printed, lh, lv, hs.size, med / s, colorGrid, notebook, align, type, picture, pics.rects)
    }

    /**
     * Fracción de la página ocupada por IMÁGENES (fotos, tramas de semitono, rellenos oscuros, bloques de color) a
     * ~[PICTURE_SIDE] px: allí la oscuridad media en ventanas de ~2 % del lado es alta (el texto, aunque sea denso,
     * deja mucho papel entre letras: 10-35 %) o el croma es alto en bloque. Sólo cuentan las zonas grandes
     * (>= 0.4 % de la página), no los titulares ni los logos pequeños.
     */
    private fun pictureFraction(d: Mat, chroma: Mat, paperChroma: Double, bag: MatBag): Pictures {
        val q = bag.mat(); Cv.downscale(d, q, PICTURE_SIDE)
        val solid = bag.mat(); Core.compare(q, Scalar(PICTURE_SOLID), solid, Core.CMP_GT)   // casi negro macizo
        val k = Cv.odd(max(3, (max(q.cols(), q.rows()) * 0.02).roundToInt()))
        Imgproc.blur(q, q, Size(k.toDouble(), k.toDouble()))
        val m = bag.mat(); Core.compare(q, Scalar(PICTURE_DARK), m, Core.CMP_GT)
        val c = bag.mat(); Cv.downscale(chroma, c, PICTURE_SIDE)
        Imgproc.blur(c, c, Size(k.toDouble(), k.toDouble()))
        val cm = bag.mat(); Core.compare(c, Scalar(paperChroma + PICTURE_CHROMA), cm, Core.CMP_GT)
        Core.bitwise_or(m, cm, m)
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(m, labels, stats, cents, 8, CvType.CV_32S)
        val st = IntArray(max(0, nc) * 5); if (nc > 0) stats.get(0, 0, st)
        val total = max(1.0, m.total().toDouble())
        val w = m.cols(); val h = m.rows()
        var area = 0.0; var inner = 0.0
        val rects = ArrayList<DoubleArray>()
        for (i in 1 until nc) {
            val a = st[i * 5 + 4]; if (a < 0.004 * total) continue
            val x = st[i * 5]; val y = st[i * 5 + 1]; val bw = st[i * 5 + 2]; val bh = st[i * 5 + 3]
            // Relleno negro macizo (tachón, banda, recuadro de un nombre censurado): no es una imagen
            val lr = labels.submat(y, y + bh, x, x + bw); val cmk = bag.mat(); Core.compare(lr, Scalar(i.toDouble()), cmk, Core.CMP_EQ); lr.release()
            val sr = solid.submat(y, y + bh, x, x + bw); val solidFrac = Core.mean(sr, cmk).`val`[0] / 255.0; sr.release(); cmk.release()
            if (solidFrac > PrintTone.PICTURE_MAX_SOLID) continue
            area += a
            // Las que tocan el borde suelen ser mesa o fondo que el recorte dejó dentro (o una foto a sangre)
            if (x <= 1 || y <= 1 || x + bw >= w - 1 || y + bh >= h - 1) continue
            inner += a
            if (a >= PrintTone.PICTURE_KEEP_MIN * total && a >= PrintTone.PICTURE_KEEP_FILL * bw * bh)
                rects.add(doubleArrayOf(x.toDouble() / w, y.toDouble() / h, (x + bw).toDouble() / w, (y + bh).toDouble() / h))
        }
        return Pictures(area / total, inner / total, rects)
    }

    /**
     * Página CON IMÁGENES (anuncio, revista): sus fotos y tramas interiores se conservan en gris en B/N (binarizadas
     * quedaban como un moteado ilegible). Sólo en ese tipo de página: en un impreso normal no se toca nada.
     */
    private fun pictureBlocks(st: Stats, ow: Int, oh: Int): List<Rect> =
        if (st.type != PrintClassifier.PageType.ILLUSTRATED) emptyList()
        else st.pictures.map { r ->
            Cv.clampRect(Rect((r[0] * ow).toInt(), (r[1] * oh).toInt(), ((r[2] - r[0]) * ow).roundToInt(), ((r[3] - r[1]) * oh).roundToInt()), ow, oh)
        }.filter { it.width > 0 && it.height > 0 }

    /**
     * Dominante de color de la LUZ que también tiñe la tinta: (croma de la luz, coseno entre el tono de la luz y
     * el tono medio de la tinta) en Lab. Luz = fondo estimado [bg]; tinta = píxeles oscuros de la imagen
     * normalizada [smN] (bajo el [white] - 60). Bolígrafo azul sobre papel amarillo: tonos opuestos (coseno < 0).
     */
    private fun colorCast(bg: Mat, smN: Mat, white: Double, bag: MatBag): DoubleArray {
        val bs = bag.mat(); Cv.downscale(bg, bs, 128)
        val lab = bag.mat(); Imgproc.cvtColor(bs, lab, Imgproc.COLOR_RGB2Lab)
        val lm = Core.mean(lab).`val`
        val la = lm[1] - 128.0; val lb = lm[2] - 128.0
        val lc = sqrt(la * la + lb * lb)
        val g = bag.add(Cv.gray(smN))
        val ink = bag.mat(); Core.compare(g, Scalar(white - 60.0), ink, Core.CMP_LT)
        if (Core.countNonZero(ink) < 0.002 * ink.total()) return doubleArrayOf(lc, 0.0)
        val nl = bag.mat(); Imgproc.cvtColor(smN, nl, Imgproc.COLOR_RGB2Lab)
        val im = Core.mean(nl, ink).`val`
        val ia = im[1] - 128.0; val ib = im[2] - 128.0
        val ic = sqrt(ia * ia + ib * ib)
        val cos = if (lc < 1e-6 || ic < 1e-6) 0.0 else (la * ia + lb * ib) / (lc * ic)
        return doubleArrayOf(lc, cos)
    }

    /** Imágenes de la página: fracción total, fracción sin las que tocan el borde y recuadros (relativos) interiores. */
    class Pictures(val frac: Double, val inner: Double, val rects: List<DoubleArray>)

    /**
     * Filetes y líneas de formulario (rectas impresas finas y largas, >= 1/8 del lado), horizontales y verticales,
     * sobre la máscara de tinta SIN dilatar: un renglón de texto tiene huecos entre letras y palabras y no
     * sobrevive a la apertura (en [countLines], pensado para cuadrículas tenues, sí puede contar). Grosor <= 4 px.
     */
    private fun countRules(d: Mat, thr: Double, side: Double, bag: MatBag): Pair<Int, Int> {
        val m = bag.mat(); Core.compare(d, Scalar(thr), m, Core.CMP_GT)
        val len = Cv.odd(max(25, (side / 8).roundToInt()))
        val maxThick = max(4.0, side * 0.004)
        val out = IntArray(2)
        val acc = bag.mat(); val tmp = bag.mat()
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        for ((i, vertical) in booleanArrayOf(false, true).withIndex()) {
            acc.create(m.size(), CvType.CV_8UC1); acc.setTo(Scalar(0.0))
            for (deg in intArrayOf(-1, 0, 1)) {
                val k = lineKernel(len, deg + if (vertical) 90 else 0)
                Imgproc.morphologyEx(m, tmp, Imgproc.MORPH_OPEN, k); k.release()
                Core.bitwise_or(acc, tmp, acc)
            }
            val nc = Imgproc.connectedComponentsWithStats(acc, labels, stats, cents, 8, CvType.CV_32S)
            val st = IntArray(max(0, nc) * 5); if (nc > 0) stats.get(0, 0, st)
            var cnt = 0
            for (c in 1 until nc) {
                val ext = if (vertical) st[c * 5 + 3] else st[c * 5 + 2]
                if (ext >= len && st[c * 5 + 4].toDouble() / max(1, ext) <= maxThick) cnt++
            }
            out[i] = cnt
        }
        return out[0] to out[1]
    }

    private const val PICTURE_SIDE = 300
    private const val PICTURE_DARK = 90.0
    private const val PICTURE_SOLID = 190.0
    private const val PICTURE_CHROMA = 40.0

    /** Rectas largas (>= 1/12 del lado) y finas en [d] > [thr], horizontales y verticales (±4°). */
    private fun countLines(d: Mat, thr: Double, side: Double, bag: MatBag, accepted: Mat, extent: DoubleArray? = null): Pair<Int, Int> {
        val m = bag.mat(); Core.compare(d, Scalar(thr), m, Core.CMP_GT)
        // Las rectas finas de 1 px se cortan por el ruido o se curvan con la hoja: cierre leve
        val raw = bag.add(m.clone())
        Imgproc.dilate(m, m, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val len = Cv.odd(max(25, (side / 12).roundToInt()))
        val out = IntArray(2)
        val acc = bag.mat(); val tmp = bag.mat()
        accepted.create(m.size(), CvType.CV_8UC1); accepted.setTo(Scalar(0.0))
        val sel = bag.mat()
        for ((i, vertical) in booleanArrayOf(false, true).withIndex()) {
            acc.create(m.size(), CvType.CV_8UC1); acc.setTo(Scalar(0.0))
            for (deg in intArrayOf(-4, -2, 0, 2, 4)) {
                val k = lineKernel(len, deg + if (vertical) 90 else 0)
                Imgproc.morphologyEx(m, tmp, Imgproc.MORPH_OPEN, k); k.release()
                Core.bitwise_or(acc, tmp, acc)
            }
            val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
            val nc = Imgproc.connectedComponentsWithStats(acc, labels, stats, cents, 8, CvType.CV_32S)
            val st = IntArray(max(0, nc) * 5); if (nc > 0) stats.get(0, 0, st)
            var cnt = 0
            for (c in 1 until nc) {
                val ext = if (vertical) st[c * 5 + 3] else st[c * 5 + 2]
                val thick = st[c * 5 + 4].toDouble() / max(1, ext)
                if (ext >= len && thick <= max(6.0, side * 0.006)) {
                    cnt++
                    if (extent != null) extent[0] += ext.toDouble()
                    Core.compare(labels, Scalar(c.toDouble()), sel, Core.CMP_EQ)
                    Core.bitwise_or(accepted, sel, accepted)
                }
            }
            out[i] = cnt
        }
        Core.bitwise_and(accepted, raw, accepted)
        return out[0] to out[1]
    }

    // =====================================================================================
    // Render
    // =====================================================================================

    /**
     * Render de [rgb] (8UC3) con [style]. [fast] = vista previa (sin ampliación). [maxPixels] = límite de píxeles
     * de la salida (la ampliación de la letra pequeña no lo supera). COLOR -> 8UC3, BLACK_WHITE -> 8UC1.
     */
    fun render(rgb: Mat, style: TextRegions.Style, fast: Boolean, maxPixels: Int, stats: Stats? = null, sheetSides: Int = 0): Mat = MatBag().use { bag ->
        val st = stats ?: analyze(rgb)
        val w = rgb.cols(); val h = rgb.rows()
        val color = style == TextRegions.Style.COLOR
        // Fondo (papel + iluminación) a baja resolución
        val bg = bag.add(Cv.estimateBackground(rgb, if (fast) 256 else 384, refine = true, refineSide = if (fast) 320 else 1024))
        // Estadística global (papel, ruido, tinta) sobre la imagen normalizada reducida: igual en todas las franjas
        val smN = bag.mat()
        run {
            val sm = bag.mat(); val sb = bag.mat()
            val k = Cv.downscale(rgb, sm, 1200)
            Imgproc.resize(bg, sb, sm.size(), 0.0, 0.0, Imgproc.INTER_AREA)
            Cv.divideByBackground(sm, sb, smN)
        }
        val g0 = bag.add(Cv.gray(smN))
        val hist = Cv.histogram(g0)
        var (noise, pm0) = Cv.paperStats(hist)
        var pm = max(pm0, 1.0)
        // Fondo que el recorte dejó dentro (mesa, hueco oscuro entre hojas, en los bordes): manchas oscuras, gruesas
        // y pegadas al borde de la imagen -> blanco (si no, en B/N quedan como manchas negras)
        // (en los lados recortados por los cantos de la hoja no hay mesa: lo pegado a ellos es contenido)
        var outside = if (sheetSides == 15) null else outsideMask(smN, pm, bag, sheetSides)
        if (outside != null && Core.countNonZero(outside) > PrintTone.OUTSIDE_RESTAT_FRAC * outside.total()) {
            // Mucho fondo ajeno (libro sobre una mesa negra sin recorte): el papel y su ruido se miden sin él (con
            // él, la mitad oscura de la imagen pasaba por papel, el blanco quedaba bajo y el texto, lavado)
            val (n2, p2) = Cv.paperStats(Cv.histogram(g0, notMask(outside, bag)))
            if (p2 > pm) { noise = n2; pm = max(p2, 1.0); outside = outsideMask(smN, pm, bag, sheetSides) ?: outside }
        }
        val white = (pm - 2.5 * noise).coerceIn(170.0, 250.0)
        // Punto negro: lo más oscuro de la HOJA (0.3 %, sin el fondo ajeno del recorte, que fijaba el negro en la
        // mesa y dejaba toda la tinta gris)... salvo que TODA la tinta sea tenue (recibo térmico desvaído, foto
        // borrosa, lápiz, fotocopia clara): el trazo típico se lleva hacia el negro (ver [faintBlack]). En B/N
        // también: sin ello la binarización local (umbral ~0.8 de la media local con poco contraste) partía o
        // borraba los trazos grises.
        val darkest = min(Cv.percentile(if (outside == null) hist else Cv.histogram(g0, notMask(outside, bag)), 0.003), 100) * 0.85
        val black = faintBlack(maxChannel(smN, bag), st.letterHeight * g0.cols() / w, white, darkest, noise, bag, if (color) FAINT_TARGET_COLOR else FAINT_TARGET_BW, 0.9)
        // Ampliación para letra pequeña (sólo render final; dentro del límite de píxeles)
        var f = 1.0
        if (!fast && st.letterHeight > 0 && st.letterHeight < SMALL_LETTER) {
            val want = min(2.0, 22.0 / st.letterHeight)
            val cap = if (maxPixels > 0) sqrt(maxPixels.toDouble() / (w.toDouble() * h)) else 2.0
            f = min(want, cap)
            if (f < 1.2) f = 1.0
        }
        val ow = if (f > 1.0) (w * f).roundToInt() else w
        val oh = if (f > 1.0) (h * f).roundToInt() else h
        val lh = max(4.0, if (st.letterHeight > 0) st.letterHeight * f else oh / 250.0)
        // Bloques de imagen (logos, fotos: compactos y de color) -> en B/N se conservan en gris
        val images = if (color) emptyList() else findImageBlocks(smN, ow, oh, bag) + pictureBlocks(st, ow, oh)
        val outsideFull = if (outside == null) null else bag.mat().also { Imgproc.resize(outside, it, Size(ow.toDouble(), oh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR) }
        // Sombras: la división por el fondo amplifica el ruido del papel (ganancia g) -> el punto blanco local baja
        // con el ruido local (σ·g^0.85) para que el papel en sombra quede igual de blanco que el iluminado. Se
        // aplica como factor multiplicativo (campo suave a baja resolución): la tinta apenas cambia.
        val lift = bag.mat()
        run {
            val bs = bag.mat(); Cv.downscale(bg, bs, 256)
            val bgl = bag.add(Cv.gray(bs))
            bgl.convertTo(lift, CvType.CV_32F)
            Core.max(lift, Scalar(4.0), lift)
            Core.divide(Cv.PAPER_LEVEL, lift, lift)          // ganancia
            Core.max(lift, Scalar(1.0), lift); Core.min(lift, Scalar(8.0), lift)
            Core.pow(lift, 0.85, lift)
            // factor = (pm - 2.5σ) / (pm - 2.5σ·g^0.85)
            lift.convertTo(lift, -1, -2.5 * noise, pm)
            Core.max(lift, Scalar(pm * 0.75), lift)
            Core.divide(pm - 2.5 * noise, lift, lift)
            Core.max(lift, Scalar(1.0), lift)
        }
        val liftOn = Core.minMaxLoc(lift).maxVal > 1.005
        val out = Mat(oh, ow, if (color) CvType.CV_8UC3 else CvType.CV_8UC1, Scalar.all(255.0))
        val levels = Cv.levelsLut(black, white, if (color) 1.25 else 1.0)
        // Luz de color (lámpara cálida, pantalla, foto con dominante): la tinta toma el tono de la luz y el color
        // reforzado la dejaba azulada o verdosa (y los textos claros sobre barras oscuras, invisibles) -> en COLOR
        // se apaga el color si la tinta tiene el mismo tono que la luz
        val satK = if (color) PrintTone.castSaturation(colorCast(bg, smN, white, bag)) else 1.0
        val satLut = Cv.lut { v -> satK * when { v < 18 -> v * 0.4; v < 40 -> { val t = (v - 18) / 22.0; v * (0.4 + t * (1.3 - 0.4)) }; else -> min(255.0, v * 1.3) } }
        // Ventana de la binarización local: ~2.5 alturas de letra (impar)
        val win = Cv.odd((2.5 * lh).roundToInt().coerceIn(15, 151))
        val sharpSigma = max(0.7, lh * 0.06)
        val margin = max(win, (sharpSigma * 4).roundToInt() + 2) + 2   // filas de contexto (px de salida)
        val stripH = max(32, STRIP_PIXELS / max(1, ow))
        val tmp = bag.mat(); val nS = bag.mat(); val big = bag.mat()
        var y0 = 0
        while (y0 < oh) {
            val y1 = min(oh, y0 + stripH)
            // Filas de entrada con contexto
            val ey0 = max(0, y0 - margin); val ey1 = min(oh, y1 + margin)
            val sy0 = max(0, kotlin.math.floor(ey0 / f).toInt() - 2); val sy1 = min(h, kotlin.math.ceil(ey1 / f).toInt() + 2)
            val sRoi = rgb.submat(sy0, sy1, 0, w); val bRoi = bg.submat(sy0, sy1, 0, w)
            Cv.divideByBackground(sRoi, bRoi, nS)
            sRoi.release(); bRoi.release()
            if (liftOn) {
                val lr0 = (sy0.toDouble() / h * lift.rows()); val lr1 = (sy1.toDouble() / h * lift.rows())
                val la = kotlin.math.floor(lr0).toInt().coerceIn(0, lift.rows() - 1); val lb = kotlin.math.ceil(lr1).toInt().coerceIn(la + 1, lift.rows())
                val lRoi = lift.submat(la, lb, 0, lift.cols())
                val lf = bag.mat(); Imgproc.resize(lRoi, lf, Size(w.toDouble(), ((lb - la).toDouble() / lift.rows() * h).roundToInt().coerceAtLeast(1).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                lRoi.release()
                // filas de [lf] que corresponden a sy0..sy1
                val off = (sy0 - la.toDouble() / lift.rows() * h).roundToInt().coerceIn(0, max(0, lf.rows() - 1))
                val take = min(sy1 - sy0, lf.rows() - off)
                if (take > 0) {
                    val l3 = bag.mat(); val lsub = lf.submat(off, off + take, 0, w)
                    Core.merge(listOf(lsub, lsub, lsub), l3); lsub.release()
                    val part = nS.submat(0, take, 0, w)
                    Core.multiply(part, l3, part, 1.0, nS.type())
                    part.release(); l3.release()
                }
                lf.release()
            }
            // Ampliación de calidad de la franja (bicúbica; el realce posterior recupera el borde)
            val work: Mat
            val oy: Int   // fila de salida correspondiente a la primera fila de [work]
            if (f > 1.0) {
                val bh = ((sy1 - sy0) * f).roundToInt()
                Imgproc.resize(nS, big, Size(ow.toDouble(), bh.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
                work = big; oy = (sy0 * f).roundToInt()
            } else { work = nS; oy = sy0 }
            val a0 = (y0 - oy).coerceIn(0, work.rows()); val a1 = (y1 - oy).coerceIn(a0, work.rows())
            val res = if (color) colorStrip(work, levels, satLut, sharpSigma, noise, bag) else bwStrip(work, levels, win, sharpSigma, noise, lh, bag)
            val src = res.submat(a0, a1, 0, ow)
            val dst = out.submat(y0, y0 + (a1 - a0), 0, ow)
            src.copyTo(dst); src.release(); dst.release()
            // Bloques de imagen dentro de la franja: niveles en gris del normalizado (sin binarizar)
            for (b in images) {
                val iy0 = max(b.y, y0); val iy1 = min(b.y + b.height, y0 + (a1 - a0))
                if (iy1 <= iy0) continue
                val gRoi = work.submat(iy0 - oy, iy1 - oy, b.x, b.x + b.width)
                val gg = bag.add(Cv.gray(gRoi)); gRoi.release()
                val o = out.submat(iy0, iy1, b.x, b.x + b.width)
                Core.LUT(gg, levels, o); o.release()
            }
            // Fondo ajeno -> blanco (después de los bloques de imagen: un relleno oscuro de la mesa no es un logo)
            if (outsideFull != null && a1 > a0) {
                val oRoi = outsideFull.submat(y0, y0 + (a1 - a0), 0, ow)
                val dRoi = out.submat(y0, y0 + (a1 - a0), 0, ow)
                val m = bag.mat(); Imgproc.threshold(oRoi, m, 127.0, 255.0, Imgproc.THRESH_BINARY)
                dRoi.setTo(Scalar.all(255.0), m)
                oRoi.release(); dRoi.release(); m.release()
            }
            res.release()
            if (a1 <= a0) break
            y0 += (a1 - a0)
        }
        levels.release(); satLut.release(); tmp.release()
        log?.invoke("render ${style} ${w}x$h -> ${ow}x$oh f=%.2f lh=%.1f win=$win black=%.0f white=%.0f noise=%.1f images=${images.size} satK=%.2f".format(f, lh, black, white, noise, satK))
        out
    }

    /**
     * Punto negro para páginas de tinta TENUE. Contraste de cada trazo = black-hat (cierre - imagen) del canal
     * máximo [v] (la tinta gris o negra lo oscurece; los sombreados de color no) con un núcleo de ~2.5 alturas de
     * letra: sólo estructuras finas (letras, líneas), no bloques. Si el percentil 90 de ese contraste entre los
     * píxeles de trazo es bajo (toda la página es tenue; un logo o un sello oscuros son pocos píxeles y no lo
     * cambian), el negro sube hasta que ese trazo típico quede al 15 % del blanco. En una página con tinta oscura
     * (aunque tenga transparencia del reverso o manchas tenues) el percentil 90 es alto y no cambia nada.
     */
    private fun faintBlack(v: Mat, letter: Double, white: Double, black: Double, noise: Double, bag: MatBag, target: Double, pct: Double): Double {
        val side = max(v.cols(), v.rows())
        val lhS = if (letter > 0) letter else side / 250.0
        val k = Cv.odd((2.5 * lhS).roundToInt().coerceIn(5, 61))
        val bh = bag.mat(); Imgproc.morphologyEx(v, bh, Imgproc.MORPH_BLACKHAT, Cv.kernel(Imgproc.MORPH_RECT, k))
        val minC = max(25.0, 6.0 * noise).roundToInt()
        val hist = Cv.histogram(bh)
        val strokes = DoubleArray(256) { if (it >= minC) hist[it] else 0.0 }
        if (strokes.sum() < 0.002 * v.total()) return black
        val c90 = Cv.percentile(strokes, pct).toDouble()
        return PrintTone.faintBlack(c90, white, black, target)
    }

    private fun notMask(m: Mat, bag: MatBag): Mat { val r = bag.mat(); Core.bitwise_not(m, r); return r }

    /**
     * Fracción del blanco a la que queda el trazo típico de una página tenue. En B/N menos agresivo (0.25): con
     * 0.15 la transparencia del reverso de un manuscrito antiguo se oscurecía y se binarizaba como texto; con 0.4
     * los recibos térmicos fotografiados perdían letras.
     */
    private const val FAINT_TARGET_COLOR = 0.15
    private const val FAINT_TARGET_BW = 0.25

    /** Canal máximo (V) de un RGB: los fondos de color claros (sombreados azules, naranjas) apenas lo oscurecen. */
    private fun maxChannel(rgb: Mat, bag: MatBag): Mat {
        val ch = ArrayList<Mat>(3); Core.split(rgb, ch)
        val v = bag.mat(); Core.max(ch[0], ch[1], v); Core.max(v, ch[2], v)
        for (c in ch) c.release()
        return v
    }

    /** COLOR: niveles (papel -> blanco), saturación del color real y realce de la luminancia. Devuelve Mat nuevo. */
    private fun colorStrip(n: Mat, levels: Mat, satLut: Mat, sigma: Double, noise: Double, bag: MatBag): Mat {
        val o = Mat()
        Core.LUT(n, levels, o)
        val hsv = bag.mat(); Imgproc.cvtColor(o, hsv, Imgproc.COLOR_RGB2HSV)
        val s = bag.mat(); Core.extractChannel(hsv, s, 1)
        Core.LUT(s, satLut, s); Core.insertChannel(s, hsv, 1)
        Imgproc.cvtColor(hsv, o, Imgproc.COLOR_HSV2RGB)
        // Realce SÓLO de la luminancia (sin halos de color) y con umbral (el grano del papel no se refuerza)
        val ycc = bag.mat(); Imgproc.cvtColor(o, ycc, Imgproc.COLOR_RGB2YCrCb)
        val y = bag.mat(); Core.extractChannel(ycc, y, 0)
        sharpen(y, sigma, 0.9, max(4.0, 3.0 * noise), bag)
        Core.insertChannel(y, ycc, 0)
        Imgproc.cvtColor(ycc, o, Imgproc.COLOR_YCrCb2RGB)
        hsv.release(); s.release(); ycc.release(); y.release()
        return o
    }

    /**
     * BLANCO Y NEGRO: Sauvola suave sobre la luminancia con niveles. T = m·(1 + k·(s/128 - 1)) en una ventana de
     * [win] px; salida = rampa suave de ±[RAMP] niveles alrededor de T (bordes anti-aliasing); muy oscuro -> negro,
     * casi papel -> blanco. Devuelve Mat 8UC1 nuevo.
     */
    private fun bwStrip(n: Mat, levels: Mat, win: Int, sigma: Double, noise: Double, lh: Double, bag: MatBag): Mat {
        // Gris: la LUMINANCIA para los trazos (la tinta azul, roja o naranja fina queda tan oscura como se ve) y la
        // media de luminancia y canal máximo sólo en los BLOQUES de color (barra naranja, columna azul sombreada,
        // más gruesos que un trazo: sobreviven a una apertura de ~0.45 alturas de letra), que así quedan claros y
        // se separan bien del texto. Con el canal máximo también en los trazos, el bolígrafo azul tenue y el texto
        // de color de las revistas se partían o desaparecían en B/N.
        val g = bag.add(Cv.gray(n))
        val ch = ArrayList<Mat>(3); Core.split(n, ch)
        val v = bag.mat(); Core.max(ch[0], ch[1], v); Core.max(v, ch[2], v)
        val mn = bag.mat(); Core.min(ch[0], ch[1], mn); Core.min(mn, ch[2], mn)
        for (c in ch) c.release()
        val blk = bag.mat()
        Core.subtract(v, mn, blk)                                    // croma (max - min)
        Imgproc.threshold(blk, blk, PrintTone.BLOCK_CHROMA, 255.0, Imgproc.THRESH_BINARY)
        val ok = Cv.odd(max(3, (lh * PrintTone.BLOCK_MIN_LH).roundToInt()))
        // Rayado de color (renglones rojos o azules de una hoja que no se reconoció como cuaderno, cuadrícula):
        // trazos de color FINOS pero rectos y LARGOS (>= ~4 alturas de letra) -> como los bloques, claros
        val thin = bag.mat(); blk.copyTo(thin)
        Imgproc.morphologyEx(blk, blk, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_ELLIPSE, ok))
        val rl = Cv.odd(max(15, (lh * PrintTone.RULE_MIN_LH).roundToInt()))
        val rule = bag.mat()
        Imgproc.morphologyEx(thin, rule, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(rl.toDouble(), 1.0)))
        Core.bitwise_or(blk, rule, blk)
        Imgproc.morphologyEx(thin, rule, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, rl.toDouble())))
        Core.bitwise_or(blk, rule, blk)
        thin.release(); rule.release()
        val mix = bag.mat(); Core.addWeighted(g, 0.5, v, 0.5, 0.0, mix)
        // Tinta de color desvaída (roja, verde) en trazos finos -> más oscura; los bloques y el rayado de color
        // largo conservan después la mezcla clara (mix se calculó antes del realce)
        Cv.darkenColorStrokes(n, g, win / 2.5)
        if (Core.countNonZero(blk) > 0) {
            Imgproc.dilate(blk, blk, Cv.kernel(Imgproc.MORPH_ELLIPSE, 3))
            mix.copyTo(g, blk)
        }
        v.release(); mn.release(); blk.release(); mix.release()
        Core.LUT(g, levels, g)
        // Ruido del sensor: suavizado leve antes de umbralizar (sin motas en los fondos sombreados)
        Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), 0.6)
        sharpen(g, sigma, 0.6, max(4.0, 3.0 * noise), bag)
        val gf = bag.mat(); g.convertTo(gf, CvType.CV_32F)
        val m = bag.mat(); val m2 = bag.mat(); val sq = bag.mat()
        val ks = Size(win.toDouble(), win.toDouble())
        Imgproc.boxFilter(gf, m, CvType.CV_32F, ks, Point(-1.0, -1.0), true, Core.BORDER_REFLECT)
        Core.multiply(gf, gf, sq)
        Imgproc.boxFilter(sq, m2, CvType.CV_32F, ks, Point(-1.0, -1.0), true, Core.BORDER_REFLECT)
        Core.multiply(m, m, sq); Core.subtract(m2, sq, m2); Core.max(m2, Scalar(0.0), m2); Core.sqrt(m2, m2)
        // T = m·(1 + k·(s/R - 1))
        m2.convertTo(m2, -1, SAUVOLA_K / 128.0, 1.0 - SAUVOLA_K)
        Core.multiply(m, m2, m)
        // alfa = (g - (T - RAMP)) / (2·RAMP) en [0,1], suavizado (smoothstep) -> blanco = 1
        Core.subtract(gf, m, gf)
        gf.convertTo(gf, -1, 1.0 / (2 * RAMP), 0.5)
        Core.min(gf, Scalar(1.0), gf); Core.max(gf, Scalar(0.0), gf)
        Core.multiply(gf, gf, sq); gf.convertTo(gf, -1, -2.0, 3.0); Core.multiply(gf, sq, gf)
        val o = Mat(); gf.convertTo(o, CvType.CV_8U, 255.0)
        // Absolutos: casi papel -> blanco; muy oscuro -> negro (relleno negro de un logo o una barra no se vacía)
        val t = bag.mat()
        Core.compare(g, Scalar(WHITE_ABS), t, Core.CMP_GE); o.setTo(Scalar(255.0), t)
        Core.compare(g, Scalar(BLACK_ABS), t, Core.CMP_LE); o.setTo(Scalar(0.0), t)
        g.release(); gf.release(); m.release(); m2.release(); sq.release(); t.release()
        return o
    }

    /**
     * Máscara (a la resolución de [smN]) del fondo ajeno a la hoja que quedó en el recorte: zonas muy oscuras
     * (< 55 % del papel), gruesas (sobreviven a una apertura de ~0.8 % del lado) y conectadas con el borde de la
     * imagen. Null si no hay.
     */
    private fun outsideMask(smN: Mat, pm: Double, bag: MatBag, sheetSides: Int = 0): Mat? {
        val g = bag.add(Cv.gray(smN))
        val w = g.cols(); val h = g.rows(); val side = max(w, h)
        val m = bag.mat(); Core.compare(g, Scalar(0.55 * pm), m, Core.CMP_LT)
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd(max(5, (side * 0.008).roundToInt()))))
        if (Core.countNonZero(m) == 0) return null
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(m, labels, stats, cents, 8, CvType.CV_32S)
        val st = IntArray(nc * 5); stats.get(0, 0, st)
        val keep = BooleanArray(nc); var any = false
        for (c in 1 until nc) {
            val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bh = st[c * 5 + 3]
            // (sólo los lados que no son cantos de la hoja: [sheetSides])
            val touches = (y <= 1 && (sheetSides and 1) == 0) || (x + bw >= w - 1 && (sheetSides and 2) == 0) ||
                (y + bh >= h - 1 && (sheetSides and 4) == 0) || (x <= 1 && (sheetSides and 8) == 0)
            if (touches) { keep[c] = true; any = true }
        }
        if (!any) return null
        val lab = IntArray(w * h); labels.get(0, 0, lab)
        // Profundidad de cada mancha de borde (mayor distancia al borde de la imagen): sólo bandas y cuñas finas
        val depth = IntArray(nc)
        for (yy in 0 until h) {
            val dy = min(yy, h - 1 - yy); val row = yy * w
            for (xx in 0 until w) {
                val l = lab[row + xx]
                if (l > 0 && keep[l]) { val dd = min(dy, min(xx, w - 1 - xx)); if (dd > depth[l]) depth[l] = dd }
            }
        }
        any = false
        for (c in 1 until nc) if (keep[c]) {
            // Mancha profunda: fondo ajeno sólo si es lisa (mesa, tela, fondo negro de estudio). Un bloque oscuro de
            // contenido (anuncio en negativo, foto a sangre) lleva texto claro o detalles dentro: muchos huecos pequeños
            keep[c] = PrintTone.isOutsideBand(0, depth[c], w, h) ||
                PrintTone.isPlainBackground(smallHoleFraction(labels, c, st, w, h, bag))
            any = any || keep[c]
        }
        if (!any) return null
        val b = ByteArray(w * h)
        for (i in lab.indices) if (keep[lab[i]] && lab[i] > 0) b[i] = -1
        val out = bag.mat(); out.create(h, w, CvType.CV_8UC1); out.put(0, 0, b)
        // Reconstrucción geodésica dentro de una máscara más permisiva (< 70 % del papel): las puntas finas y la
        // penumbra de la mancha también salen, pero no el texto (no está conectado con ella)
        val loose = bag.mat(); Core.compare(g, Scalar(0.7 * pm), loose, Core.CMP_LT)
        val k3 = Cv.kernel(Imgproc.MORPH_RECT, 3)
        repeat(max(4, side / 150)) { Imgproc.dilate(out, out, k3); Core.bitwise_and(out, loose, out) }
        // margen
        Imgproc.dilate(out, out, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd(max(3, (side * 0.004).roundToInt()))))
        return out
    }

    /**
     * Huecos PEQUEÑOS (< 1 % de la imagen: letras claras, detalles) de la componente [c] de [labels], en fracción
     * del área de la componente. Los huecos grandes (la hoja rodeada por la mesa) no cuentan.
     */
    private fun smallHoleFraction(labels: Mat, c: Int, st: IntArray, w: Int, h: Int, bag: MatBag): Double {
        val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bh = st[c * 5 + 3]; val area = st[c * 5 + 4]
        val roi = labels.submat(y, y + bh, x, x + bw)
        val cm = bag.mat(); Core.compare(roi, Scalar(c.toDouble()), cm, Core.CMP_NE); roi.release()   // no-componente
        val pad = bag.mat(); Core.copyMakeBorder(cm, pad, 1, 1, 1, 1, Core.BORDER_CONSTANT, Scalar(255.0))
        val l2 = bag.mat(); val s2 = bag.mat(); val c2 = bag.mat()
        val n2 = Imgproc.connectedComponentsWithStats(pad, l2, s2, c2, 4, CvType.CV_32S)
        val ss = IntArray(max(0, n2) * 5); if (n2 > 0) s2.get(0, 0, ss)
        val maxHole = 0.01 * w * h
        var holes = 0.0
        for (i in 1 until n2) {
            val hx = ss[i * 5]; val hy = ss[i * 5 + 1]; val hw = ss[i * 5 + 2]; val hh = ss[i * 5 + 3]; val ha = ss[i * 5 + 4]
            if (hx == 0 || hy == 0 || hx + hw >= pad.cols() || hy + hh >= pad.rows()) continue   // exterior
            if (ha < maxHole) holes += ha
        }
        return holes / max(1, area)
    }

    private const val SAUVOLA_K = 0.22
    private const val RAMP = 12.0
    private const val WHITE_ABS = 236.0
    private const val BLACK_ABS = 70.0

    /** Máscara de enfoque con umbral sobre un canal 8UC1 (in-place). */
    private fun sharpen(img: Mat, sigma: Double, amount: Double, threshold: Double, bag: MatBag) {
        val blur = bag.mat(); Imgproc.GaussianBlur(img, blur, Size(0.0, 0.0), sigma)
        val diff = bag.mat(); Core.absdiff(img, blur, diff)
        val flat = bag.mat(); Imgproc.threshold(diff, flat, threshold, 255.0, Imgproc.THRESH_BINARY_INV)
        Imgproc.erode(flat, flat, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val orig = bag.add(img.clone())
        Core.addWeighted(img, 1.0 + amount, blur, -amount, 0.0, img)
        orig.copyTo(img, flat)
        blur.release(); diff.release(); flat.release(); orig.release()
    }

    /**
     * Logos y fotos sobre la imagen normalizada reducida [smN]: bloques de color saturado (o muy oscuros y
     * texturados) compactos -> rectángulos en coordenadas de salida ([ow]x[oh]). Las barras y columnas
     * sombreadas (alargadas) no cuentan: allí manda la binarización local.
     */
    private fun findImageBlocks(smN: Mat, ow: Int, oh: Int, bag: MatBag): List<Rect> {
        val q = bag.mat(); Cv.downscale(smN, q, 300)
        val hsv = bag.mat(); Imgproc.cvtColor(q, hsv, Imgproc.COLOR_RGB2HSV)
        val s = bag.mat(); Core.extractChannel(hsv, s, 1)
        val m = bag.mat(); Core.compare(s, Scalar(70.0), m, Core.CMP_GE)
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, 5))
        // Rellenos OSCUROS macizos (encabezado con texto blanco, banda oscura): la binarización local los vaciaría
        // (sólo quedaría el contorno) -> también en gris. Apertura: el texto y las líneas finas no cuentan.
        val gq = bag.add(Cv.gray(q))
        val dk = bag.mat(); Core.compare(gq, Scalar(150.0), dk, Core.CMP_LT)
        Imgproc.morphologyEx(dk, dk, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_RECT, 5))
        val dkBlocks = blocks(dk, q, ow, oh, maxAspect = 30.0, minFill = 0.6, bag = bag)
        return blocks(m, q, ow, oh, maxAspect = 3.0, minFill = 0.3, bag = bag) + dkBlocks
    }

    private fun blocks(m: Mat, q: Mat, ow: Int, oh: Int, maxAspect: Double, minFill: Double, bag: MatBag): List<Rect> {
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(m, labels, stats, cents, 8, CvType.CV_32S)
        val st = IntArray(max(0, nc) * 5); if (nc > 0) stats.get(0, 0, st)
        val total = q.total().toDouble()
        val kx = ow.toDouble() / q.cols(); val ky = oh.toDouble() / q.rows()
        val out = ArrayList<Rect>()
        for (c in 1 until nc) {
            val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bh = st[c * 5 + 3]; val a = st[c * 5 + 4]
            if (a < 0.002 * total || a > 0.4 * total) continue
            val aspect = max(bw, bh).toDouble() / max(1, min(bw, bh))
            if (aspect > maxAspect || a < minFill * bw * bh) continue
            val px = max(1, (bw * 0.08).roundToInt()); val py = max(1, (bh * 0.08).roundToInt())
            val r = Cv.clampRect(Rect(((x - px) * kx).toInt(), ((y - py) * ky).toInt(), ((bw + 2 * px) * kx).roundToInt(), ((bh + 2 * py) * ky).roundToInt()), ow, oh)
            if (r.width > 0 && r.height > 0) out.add(r)
        }
        return out
    }

    private fun lineKernel(len: Int, deg: Int): Mat {
        val a = Math.toRadians(deg.toDouble())
        val dx = kotlin.math.cos(a); val dy = kotlin.math.sin(a)
        val hw = (abs(dx) * (len - 1) / 2).roundToInt(); val hh = (abs(dy) * (len - 1) / 2).roundToInt()
        val k = Mat.zeros(2 * hh + 1, 2 * hw + 1, CvType.CV_8UC1)
        val half = (len - 1) / 2.0
        Imgproc.line(k, Point(hw - dx * half, hh - dy * half), Point(hw + dx * half, hh + dy * half), Scalar(1.0), 1)
        return k
    }
}
