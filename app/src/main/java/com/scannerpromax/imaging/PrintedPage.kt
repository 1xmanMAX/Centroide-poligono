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
    ) {
        /** Las mismas estadísticas para la imagen ampliada [k] veces (super-resolución previa al render). */
        fun scaled(k: Double) = Stats(printed, linesH, linesV, textComps, letterHeight * k, colorGrid, notebook, align)
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
        log?.invoke("printed=$printed notebook=$notebook llh=$llh llv=$llv lowChroma=%.1f lowDark=%.1f lowThick=%.2f align=%.2f ".format(lowChroma, lowDark, lowThick, align) + "lines H=$lh V=$lv lineChroma=%.1f (papel %.1f) dark=%.0f comps=${hs.size} med=%.1f iqr=%.2f noise=%.1f table=$table text=$text".format(lineChroma, paperChroma, lineDark, med, iqr, noise))
        Stats(printed, lh, lv, hs.size, med / s, colorGrid, notebook, align)
    }

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
    fun render(rgb: Mat, style: TextRegions.Style, fast: Boolean, maxPixels: Int, stats: Stats? = null): Mat = MatBag().use { bag ->
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
        val (noise, pm0) = Cv.paperStats(hist)
        val pm = max(pm0, 1.0)
        val white = (pm - 2.5 * noise).coerceIn(170.0, 250.0)
        // Punto negro: lo más oscuro de la página (0.3 %)... salvo que TODA la tinta sea tenue (recibo térmico
        // desvaído, lápiz, fotocopia clara): en COLOR el trazo típico se lleva casi a negro (ver [faintBlack]).
        // En B/N no hace falta (la binarización local ya separa la tinta tenue) y empeoraba la lectura OCR.
        val darkest = min(Cv.percentile(hist, 0.003), 100) * 0.85
        val black = if (color) faintBlack(maxChannel(smN, bag), st.letterHeight * g0.cols() / w, white, darkest, noise, bag) else darkest
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
        val images = if (color) emptyList() else findImageBlocks(smN, ow, oh, bag)
        // Fondo que el recorte dejó dentro (mesa, hueco oscuro entre hojas, en los bordes): manchas oscuras, gruesas
        // y pegadas al borde de la imagen -> blanco (si no, en B/N quedan como manchas negras)
        val outside = outsideMask(smN, pm, bag)
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
        val satLut = Cv.lut { v -> when { v < 18 -> v * 0.4; v < 40 -> { val t = (v - 18) / 22.0; v * (0.4 + t * (1.3 - 0.4)) }; else -> min(255.0, v * 1.3) } }
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
            val res = if (color) colorStrip(work, levels, satLut, sharpSigma, noise, bag) else bwStrip(work, levels, win, sharpSigma, noise, bag)
            val src = res.submat(a0, a1, 0, ow)
            val dst = out.submat(y0, y0 + (a1 - a0), 0, ow)
            src.copyTo(dst); src.release(); dst.release()
            // Bloques de imagen dentro de la franja: niveles en gris del normalizado (sin binarizar)
            if (outsideFull != null && a1 > a0) {
                val oRoi = outsideFull.submat(y0, y0 + (a1 - a0), 0, ow)
                val dRoi = out.submat(y0, y0 + (a1 - a0), 0, ow)
                val m = bag.mat(); Imgproc.threshold(oRoi, m, 127.0, 255.0, Imgproc.THRESH_BINARY)
                dRoi.setTo(Scalar.all(255.0), m)
                oRoi.release(); dRoi.release(); m.release()
            }
            for (b in images) {
                val iy0 = max(b.y, y0); val iy1 = min(b.y + b.height, y0 + (a1 - a0))
                if (iy1 <= iy0) continue
                val gRoi = work.submat(iy0 - oy, iy1 - oy, b.x, b.x + b.width)
                val gg = bag.add(Cv.gray(gRoi)); gRoi.release()
                val o = out.submat(iy0, iy1, b.x, b.x + b.width)
                Core.LUT(gg, levels, o); o.release()
            }
            res.release()
            if (a1 <= a0) break
            y0 += (a1 - a0)
        }
        levels.release(); satLut.release(); tmp.release()
        log?.invoke("render ${style} ${w}x$h -> ${ow}x$oh f=%.2f lh=%.1f win=$win black=%.0f white=%.0f noise=%.1f images=${images.size}".format(f, lh, black, white, noise))
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
    private fun faintBlack(v: Mat, letter: Double, white: Double, black: Double, noise: Double, bag: MatBag): Double {
        val side = max(v.cols(), v.rows())
        val lhS = if (letter > 0) letter else side / 250.0
        val k = Cv.odd((2.5 * lhS).roundToInt().coerceIn(5, 61))
        val bh = bag.mat(); Imgproc.morphologyEx(v, bh, Imgproc.MORPH_BLACKHAT, Cv.kernel(Imgproc.MORPH_RECT, k))
        val minC = max(25.0, 6.0 * noise).roundToInt()
        val hist = Cv.histogram(bh)
        val strokes = DoubleArray(256) { if (it >= minC) hist[it] else 0.0 }
        if (strokes.sum() < 0.002 * v.total()) return black
        val c90 = Cv.percentile(strokes, 0.9).toDouble()
        val faint = white - c90 / 0.85
        return if (faint > black) min(faint, white - 60.0) else black
    }

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
    private fun bwStrip(n: Mat, levels: Mat, win: Int, sigma: Double, noise: Double, bag: MatBag): Mat {
        // Gris = media de la luminancia y del canal máximo: los fondos de COLOR (barra naranja, columna azul) quedan
        // claros y se separan bien del texto; la tinta de color (roja, azul) sigue oscura respecto del papel
        val g = bag.add(Cv.gray(n))
        val ch = ArrayList<Mat>(3); Core.split(n, ch)
        val v = bag.mat(); Core.max(ch[0], ch[1], v); Core.max(v, ch[2], v)
        for (c in ch) c.release()
        Core.addWeighted(g, 0.5, v, 0.5, 0.0, g)
        v.release()
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
    private fun outsideMask(smN: Mat, pm: Double, bag: MatBag): Mat? {
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
            if (x <= 1 || y <= 1 || x + bw >= w - 1 || y + bh >= h - 1) { keep[c] = true; any = true }
        }
        if (!any) return null
        val lab = IntArray(w * h); labels.get(0, 0, lab)
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
