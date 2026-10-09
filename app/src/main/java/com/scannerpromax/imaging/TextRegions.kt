package com.scannerpromax.imaging

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
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
 * Segmentación de la escritura en recuadros ("Texto resaltado" y "Blanco y negro").
 *
 * 1. Iluminación normalizada (fondo estimado a baja resolución, sin sombras) -> mapa de TINTA a resolución
 *    completa: oscuridad del canal MÁXIMO (la cuadrícula azul clara y el marco azul del cuaderno casi no
 *    oscurecen el canal azul; lápiz, bolígrafo y tinta negra sí) + oscuridad del canal mínimo para tintas no
 *    azuladas (rojo, verde, naranja). Las rectas largas claras (cuadrícula, renglones) se borran del mapa.
 * 2. A resolución de trabajo (<= [WORK_SIDE]): componentes conexas con histéresis (umbral bajo conectado a
 *    umbral alto, ambos relativos al ruido local), filtro de motas y restos rectos de la rejilla, manchas
 *    gruesas que no son letras (espiral, huecos, bordes oscuros) -> zona en blanco; fotos y bloques de color
 *    grandes -> recuadro IMAGEN.
 * 3. Agrupación de componentes cercanas en palabras / líneas / bloques ([BoxGrouping]), con margen holgado
 *    según la altura de letra estimada.
 * 4. Render a resolución completa: fuera de los recuadros, blanco puro; dentro, sólo la tinta (alfa suave
 *    según su oscuridad -> bordes anti-aliasing, sin halos), con contraste ajustado POR RECUADRO (el lápiz claro
 *    se refuerza); en los recuadros IMAGEN, una mejora suave del original normalizado.
 */
object TextRegions {
    enum class Kind { TEXT, IMAGE }

    /** Recuadro [left, right) x [top, bottom) en píxeles de la imagen analizada. */
    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int, val kind: Kind) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Lado largo máximo de la resolución de trabajo de la segmentación (las ESTIMACIONES; el render es completo). */
    const val WORK_SIDE = 2000

    /** Croma (V - min) por debajo del cual el color no cuenta como tinta (cuadrícula azul clara, papel). */
    private const val CHROMA_GRID = 55.0

    /** Los umbrales de tinta crecen con ganancia^exp de la normalización (ruido amplificado en las sombras). */
    private const val NOISE_GAIN_EXP = 0.85

    /** Un píxel de la huella está "explicado por la recta" si su oscuridad <= EXPL_K·O + EXPL_C (O = la de la recta). */
    private const val EXPL_K = 1.25
    private const val EXPL_C = 4.0

    /** Límite local de lo que se borra como rejilla: LIM_K·(oscuridad media de la rejilla del entorno) + LIM_C. */
    private const val LIM_K = 1.35
    private const val LIM_C = 7.0

    /** Núcleo de mancha gruesa con oscuridad media < SOFT_CORE·tHigh: fondo de color claro, no zona en blanco. */
    private const val SOFT_CORE = 4.0

    /** Píxeles por franja en el render (acota la memoria de los intermedios en coma flotante). */
    private const val STRIP_PIXELS = 1_000_000

    /** Longitud mínima (fracción del lado) de una recta para tomarla por rejilla (con la hoja curvada se parten). */
    private const val LINE_MIN_FRACTION = 0.12

    internal var debug: ((String, Mat) -> Unit)? = null
    internal var log: ((String) -> Unit)? = null

    /**
     * Recuadros de escritura (TEXT) e imágenes (IMAGE) de [bitmap], en sus coordenadas. Pensado para que la UI
     * los dibuje. Trabaja a <= [WORK_SIDE] px (~100-300 ms en un móvil medio). Thread-safe.
     */
    fun detect(bitmap: Bitmap): List<Region> {
        val (rgba, s) = Cv.toRgbaScaled(bitmap, WORK_SIDE)
        val rgb = Mat()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            rgba.release()
            val lay = analyze(rgb)
            return lay.regions.map { scaleRegion(it, 1.0 / s, bitmap.width, bitmap.height) }
        } finally {
            rgba.release(); rgb.release()
        }
    }

    /** Igual que [detect] sobre un Mat RGB (coordenadas de [rgb]). */
    internal fun detectMat(rgb: Mat): List<Region> = analyze(rgb).regions

    private fun scaleRegion(r: Region, k: Double, w: Int, h: Int) = Region(
        (r.left * k).toInt().coerceIn(0, w), (r.top * k).toInt().coerceIn(0, h),
        kotlin.math.ceil(r.right * k).toInt().coerceIn(0, w), kotlin.math.ceil(r.bottom * k).toInt().coerceIn(0, h), r.kind,
    )

    // =====================================================================================
    // 1. Mapa de tinta
    // =====================================================================================

    internal class Prepared(
        val n: Mat,          // RGB normalizado (papel ≈ PAPER_LEVEL), resolución completa
        val dark: Mat,       // 8UC1 oscuridad de tinta, sin rectas claras, resolución completa
        val gain: Mat,       // 32FC1 ganancia de la normalización (papel / fondo), a baja resolución
        val ruling: Mat,     // 8UC1 huella fina de las rectas claras, resolución completa (puede estar vacío)
        val paper: Double,
        val noise: Double,
    ) {
        fun release() { n.release(); dark.release(); gain.release(); ruling.release() }
    }

    internal fun prepare(rgb0: Mat, bgSide: Int = 384, refineSide: Int = 512): Prepared = MatBag().use { bag ->
        // Pizarra / pantalla en modo oscuro: escritura CLARA sobre fondo oscuro -> se invierte (tiza -> tinta oscura
        // sobre blanco) y el resto de la tubería no cambia
        val rgb = if (lightOnDark(rgb0, bag)) bag.mat().also { Core.bitwise_not(rgb0, it); log?.invoke("polaridad invertida") } else rgb0
        val bg = bag.add(Cv.estimateBackground(rgb, bgSide, refine = true, refineSide = refineSide))
        val n = Mat()
        Cv.divideByBackground(rgb, bg, n)
        // Ganancia local (sombras: el ruido del papel normalizado crece con ella)
        val bgs = bag.mat(); Cv.downscale(bg, bgs, 256)
        bg.release()
        val bgl = bag.add(Cv.gray(bgs))
        val gain = Mat(); bgl.convertTo(gain, CvType.CV_32F)
        Core.max(gain, Scalar(4.0), gain)
        Core.divide(Cv.PAPER_LEVEL, gain, gain)
        Core.max(gain, Scalar(1.0), gain)
        Core.min(gain, Scalar(8.0), gain)

        val ch = ArrayList<Mat>(3); Core.split(n, ch)
        for (c in ch) bag.add(c)
        val v = bag.mat(); Core.max(ch[0], ch[1], v); Core.max(v, ch[2], v)
        // (papel >= 1: con una página casi negra -pizarra, texto claro sobre fondo oscuro- el nivel puede salir 0 y
        // las rampas relativas a él darían 0/0)
        val (noise, pm0) = Cv.paperStats(Cv.histogram(v))
        val pm = max(pm0, 1.0)
        val dark = Mat()
        Core.bitwise_not(v, dark)
        Core.subtract(dark, Scalar(255.0 - pm), dark)
        // Tinta de color (bolígrafo azul, rojo, verde): su croma (V - min) es mucho mayor que el de la cuadrícula
        // azul clara (~20-35) -> se suma el croma que excede ~55, sólo en píxeles algo más oscuros que el papel
        // (los resaltadores y el marco azul claro del cuaderno, casi tan claros como el papel, no cuentan).
        val mn = bag.mat(); Core.min(ch[0], ch[1], mn); Core.min(mn, ch[2], mn)
        val chroma = bag.mat(); Core.subtract(v, mn, chroma)
        Core.subtract(chroma, Scalar(CHROMA_GRID), chroma)
        val wv = bag.mat()
        Cv.applyLut(v, Cv.lut { x -> ((0.97 * pm - x) / (0.1 * pm)).coerceIn(0.0, 1.0) * 255.0 }, wv)
        Core.multiply(chroma, wv, chroma, 1.0 / 255.0)
        Core.add(dark, chroma, dark)
        // Ruido del sensor (fuerte en las sombras, donde la normalización lo amplifica): suavizado leve; los trazos
        // (>= 3 px) apenas cambian y la rampa del alfa los vuelve a dejar nítidos
        Imgproc.GaussianBlur(dark, dark, Size(0.0, 0.0), 0.9)
        // Memoria: los intermedios a resolución completa se liberan antes del paso más pesado
        for (c in ch) c.release()
        v.release(); mn.release(); chroma.release(); wv.release()
        val ruling = suppressRuling(dark, gain, bag)
        Prepared(n, dark, gain, ruling, pm, noise)
    }

    /**
     * ¿Escritura clara sobre fondo oscuro? A ~1000 px, en gris: diferencia con el fondo local (mediana 31x31);
     * se comparan las fracciones de píxeles mucho más claros (> +40) y mucho más oscuros (< -40) que su entorno
     * ([HwStats.isLightOnDark]).
     */
    private fun lightOnDark(rgb: Mat, bag: MatBag): Boolean {
        val g = bag.mat(); Cv.downscale(rgb, g, 1000)
        val gray = bag.mat(); Imgproc.cvtColor(g, gray, if (g.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
        g.release()
        if (min(gray.cols(), gray.rows()) < 64) return false
        val bg = bag.mat(); Imgproc.medianBlur(gray, bg, 31)
        val d = bag.mat()
        Core.subtract(gray, bg, d); Imgproc.threshold(d, d, 40.0, 255.0, Imgproc.THRESH_BINARY)
        val bright = Core.countNonZero(d).toDouble() / d.total()
        Core.subtract(bg, gray, d); Imgproc.threshold(d, d, 40.0, 255.0, Imgproc.THRESH_BINARY)
        val dark = Core.countNonZero(d).toDouble() / d.total()
        log?.invoke("polaridad claro=%.4f oscuro=%.4f".format(bright, dark))
        gray.release(); bg.release(); d.release()
        return HwStats.isLightOnDark(bright, dark)
    }

    /**
     * Borra de [dark] (in-place) las rectas largas CLARAS (cuadrícula, renglones) y devuelve su huella fina (8UC1,
     * resolución completa; vacía si no hay):
     *  1. a 1/4 de resolución, reducción por MÁXIMO 5x5 centrado (una línea de 1 px no se pierde ni se ensancha) y máscara
     *     de "algo oscuro" (> 8);
     *  2. aperturas BINARIAS con elementos lineales largos (~1/25 del lado) a -6..6° de la horizontal y de la
     *     vertical (tras un cierre de 3 px en la misma dirección para los cortes del ruido): sólo sobreviven las
     *     rectas largas; las palabras (aunque sean cursivas) no contienen tramos rectos tan largos;
     *  3. si la rejilla es típicamente oscura (mediana >= 60: tabla o formulario impreso) no se toca nada;
     *  4. a media resolución, oscuridad propia de cada recta (apertura con un elemento de ~1/80 del lado, más
     *     largo que cualquier letra) y límite LOCAL = 1.35·(media de la rejilla del entorno) + 7;
     *  5. a resolución completa se borran los píxeles de la huella explicados por la recta (<= 1.25·O + 4 y
     *     <= límite): un trazo que la cruza o va encima y es más oscuro, o una recta mucho más oscura que la
     *     rejilla de alrededor (línea de un diagrama a bolígrafo o lápiz marcado), se conserva.
     */
    private fun suppressRuling(dark: Mat, gain: Mat, bag: MatBag): Mat {
        val w = dark.cols(); val h = dark.rows()
        val lines = Mat()
        if (min(w, h) < 200) return lines
        val qs = Size(max(1.0, (w / 4.0).roundToInt().toDouble()), max(1.0, (h / 4.0).roundToInt().toDouble()))
        val dm = bag.mat(); Imgproc.dilate(dark, dm, Cv.kernel(Imgproc.MORPH_RECT, 5))
        val q = bag.mat(); Imgproc.resize(dm, q, qs, 0.0, 0.0, Imgproc.INTER_NEAREST)
        dm.release()
        // "Algo oscuro": > 8 en el papel bien iluminado, más en las sombras (ruido amplificado)
        val thr = bag.mat(); Imgproc.resize(gain, thr, qs, 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.pow(thr, 0.5, thr); Core.multiply(thr, Scalar(8.0), thr)
        val qf = bag.mat(); q.convertTo(qf, CvType.CV_32F)
        val m = bag.mat(); Core.compare(qf, thr, m, Core.CMP_GT)
        val len = Cv.odd(max(15, (max(qs.width, qs.height) / 25).roundToInt()))
        lines.create(q.size(), CvType.CV_8UC1); lines.setTo(Scalar(0.0))
        val linesV = bag.mat(); linesV.create(q.size(), CvType.CV_8UC1); linesV.setTo(Scalar(0.0))
        val tmp = bag.mat()
        for (deg in intArrayOf(-6, -3, 0, 3, 6)) {
            for (vertical in booleanArrayOf(false, true)) {
                val a = deg + if (vertical) 90 else 0
                val ks = lineKernel(3, a)
                Imgproc.morphologyEx(m, tmp, Imgproc.MORPH_CLOSE, ks); ks.release()
                val k = lineKernel(len, a)
                Imgproc.morphologyEx(tmp, tmp, Imgproc.MORPH_OPEN, k); k.release()
                Core.bitwise_or(if (vertical) linesV else lines, tmp, if (vertical) linesV else lines)
            }
        }
        // Sólo rectas largas (>= 12 % del lado): la rejilla y los renglones (que se parten donde la hoja se curva);
        // trazos rectos más cortos se conservan
        keepLong(lines, horizontal = true, bag)
        keepLong(linesV, horizontal = false, bag)
        val linesH = bag.add(lines.clone())
        Core.bitwise_or(lines, linesV, lines)
        if (Core.countNonZero(lines) == 0) return lines
        // gl = nivel típico de la rejilla (mediana en la huella gruesa; umbrales de detección)
        val gl = Cv.percentile(Cv.histogram(q, lines), 0.5).toDouble()
        // Rectas típicamente OSCURAS (tablas, formularios impresos): no es una rejilla clara de cuaderno
        if (gl >= 60.0) { log?.invoke("ruling dark gl=$gl"); lines.setTo(Scalar(0.0)); return lines }
        val remove = bag.mat(); remove.create(dark.size(), CvType.CV_8UC1); remove.setTo(Scalar(0.0))
        val band = bag.mat(); val o = bag.mat(); val t = bag.mat()
        // Media resolución por máximo 3x3 centrado (la línea fina conserva su oscuridad); elementos a -9..9° (la hoja
        // curvada inclina la rejilla)
        val ot = bag.mat()
        val hs = Size(max(1.0, (w / 2.0).roundToInt().toDouble()), max(1.0, (h / 2.0).roundToInt().toDouble()))
        val half = bag.mat(); Imgproc.dilate(dark, ot, Cv.kernel(Imgproc.MORPH_RECT, 3)); Imgproc.resize(ot, half, hs, 0.0, 0.0, Imgproc.INTER_NEAREST)
        val longLen = Cv.odd(max(15, max(w, h) / 80))
        // Umbrales relativos al ruido local (ganancia^0.85: en las sombras el ruido normalizado crece)
        val gh = bag.mat(); Imgproc.resize(gain, gh, hs, 0.0, 0.0, Imgproc.INTER_LINEAR); Core.pow(gh, NOISE_GAIN_EXP, gh)
        val ohf = bag.mat(); val thrH = bag.mat()
        val angles = intArrayOf(-9, -6, -3, 0, 3, 6, 9)
        // Oscuridad propia de la recta: apertura LARGA (~1/80 del lado, más que cualquier letra) a media
        // resolución -> la letra que va encima o cruza la línea no cuenta (la ventana incluye rejilla limpia).
        // Huella (media resolución) = huella gruesa de 1/4 donde esa apertura es apreciable.
        val ohs = arrayOf(bag.mat(), bag.mat()); val bands = arrayOf(bag.mat(), bag.mat())
        for ((i, vertical) in booleanArrayOf(false, true).withIndex()) {
            val oh = ohs[i]
            oh.create(hs, CvType.CV_8UC1); oh.setTo(Scalar(0.0))
            for (deg in angles) {
                val k = lineKernel(longLen, deg + if (vertical) 90 else 0)
                Imgproc.morphologyEx(half, ot, Imgproc.MORPH_OPEN, k); k.release()
                Core.max(oh, ot, oh)
            }
            // (la huella gruesa se ensancha 1 px de 1/4: el muestreo por vecino más próximo la desplaza ~1.5 px)
            Imgproc.dilate(if (vertical) linesV else linesH, ot, Cv.kernel(Imgproc.MORPH_RECT, 3))
            Imgproc.resize(ot, bands[i], hs, 0.0, 0.0, Imgproc.INTER_NEAREST)
            oh.convertTo(ohf, CvType.CV_32F)
            Core.multiply(gh, Scalar(max(4.0, 0.35 * gl)), thrH)
            Core.compare(ohf, thrH, ot, Core.CMP_GE)
            Core.bitwise_and(bands[i], ot, bands[i])
        }
        // Límite LOCAL de lo que se borra: oscuridad media de la rejilla en un entorno de ~1/8 de la página
        // (media de la apertura sobre la huella, ambas direcciones) x1.35 + 7. La rejilla más marcada en una
        // zona (mejor iluminada, más saturada, en sombra) se borra; una línea de bolígrafo o de lápiz marcado,
        // mucho más oscura que la rejilla de su alrededor, se conserva aunque sea larga y recta.
        val limH = bag.mat()
        run {
            val num = bag.mat(); val den = bag.mat(); val f = bag.mat(); val mf = bag.mat()
            num.create(hs, CvType.CV_32F); num.setTo(Scalar(0.0)); den.create(hs, CvType.CV_32F); den.setTo(Scalar(0.0))
            for (i in 0..1) {
                ohs[i].convertTo(f, CvType.CV_32F); bands[i].convertTo(mf, CvType.CV_32F, 1.0 / 255.0)
                Core.multiply(f, mf, f); Core.add(num, f, num); Core.add(den, mf, den)
            }
            val win = max(9.0, max(hs.width, hs.height) / 8.0)
            // Desenfoque de caja grande a 1/8 de la media resolución (campo suave)
            val ss = Size(max(4.0, hs.width / 8.0).roundToInt().toDouble(), max(4.0, hs.height / 8.0).roundToInt().toDouble())
            val ns = bag.mat(); val ds = bag.mat()
            Imgproc.resize(num, ns, ss, 0.0, 0.0, Imgproc.INTER_AREA); Imgproc.resize(den, ds, ss, 0.0, 0.0, Imgproc.INTER_AREA)
            val kw = Cv.odd(max(3, (win / 8.0).roundToInt()))
            Imgproc.blur(ns, ns, Size(kw.toDouble(), kw.toDouble())); Imgproc.blur(ds, ds, Size(kw.toDouble(), kw.toDouble()))
            val lowSupport = bag.mat(); Core.compare(ds, Scalar(0.004), lowSupport, Core.CMP_LT)
            Core.max(ds, Scalar(1e-6), ds)
            Core.divide(ns, ds, ns)
            ns.setTo(Scalar(gl), lowSupport)
            ns.convertTo(ns, -1, LIM_K, LIM_C)
            Core.min(ns, Scalar(70.0), ns); Core.max(ns, Scalar(min(70.0, gl * LIM_K + LIM_C)), ns)
            ns.convertTo(limH, CvType.CV_8U)
        }
        val limFull = bag.mat(); Imgproc.resize(limH, limFull, dark.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val bandAll = bag.mat(); bandAll.create(dark.size(), CvType.CV_8UC1); bandAll.setTo(Scalar(0.0))
        val fine = bag.mat()
        val bandFine = bag.mat(); bandFine.create(dark.size(), CvType.CV_8UC1); bandFine.setTo(Scalar(0.0))
        for (i in 0..1) {
            val oh = ohs[i]
            Imgproc.resize(oh, o, dark.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            // Huella fina (para reconocer después restos de la rejilla: sólo el núcleo de la línea) y banda de
            // borrado (más permisiva: borra sólo lo que la propia recta explica, ver abajo)
            oh.convertTo(ohf, CvType.CV_32F)
            Core.multiply(gh, Scalar(max(6.0, 0.6 * gl)), thrH)
            Core.compare(ohf, thrH, ot, Core.CMP_GE)
            Core.bitwise_and(bands[i], ot, ot)
            Imgproc.resize(ot, fine, dark.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
            Imgproc.resize(bands[i], band, dark.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
            Imgproc.dilate(band, band, Cv.kernel(Imgproc.MORPH_RECT, 3))
            Core.compare(o, limFull, t, Core.CMP_LE); Core.bitwise_and(band, t, band)
            Core.bitwise_or(bandAll, band, bandAll)   // sólo donde la recta es clara (no renglones de texto impreso)
            Core.bitwise_and(fine, t, fine)
            Core.bitwise_or(bandFine, fine, bandFine)
            Core.compare(o, Scalar(0.35 * gl), t, Core.CMP_GE); Core.bitwise_and(band, t, band)
            o.convertTo(o, -1, EXPL_K, EXPL_C)
            Core.min(o, limFull, o)   // nunca se borra nada más oscuro que una línea clara de la rejilla
            Core.compare(dark, o, t, Core.CMP_LE); Core.bitwise_and(band, t, band)
            Core.bitwise_or(remove, band, remove)
        }
        val limit = gl * LIM_K + LIM_C
        // Restos tenues junto a las rectas (bordes de la línea fuera de la banda, escalones por la inclinación):
        // en una franja algo más ancha se borra lo que no supera el nivel de la rejilla
        Imgproc.dilate(bandAll, bandAll, Cv.kernel(Imgproc.MORPH_RECT, 3))
        Core.compare(dark, Scalar(max(0.45 * min(gl, 40.0), 6.0)), t, Core.CMP_LE)
        Core.bitwise_and(bandAll, t, bandAll)
        Core.bitwise_or(remove, bandAll, remove)
        dark.setTo(Scalar(0.0), remove)
        // Huella devuelta: la banda fina de las rectas claras, a resolución completa (un tachón o subrayado oscuro
        // no es rejilla; las letras junto a la línea quedan fuera)
        lines.release()
        bandFine.copyTo(lines)
        log?.invoke("ruling gl=$gl limit=$limit")
        return lines
    }

    /** Deja en [m] sólo las componentes cuya extensión (horizontal o vertical) es >= [LINE_MIN_FRACTION] de la imagen. */
    private fun keepLong(m: Mat, horizontal: Boolean, bag: MatBag) {
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(m, labels, stats, cents, 8, CvType.CV_32S)
        if (nc <= 1) return
        val st = IntArray(nc * 5); stats.get(0, 0, st)
        val minLen = LINE_MIN_FRACTION * if (horizontal) m.cols() else m.rows()
        val drop = BooleanArray(nc); var any = false
        for (c in 1 until nc) { val ext = if (horizontal) st[c * 5 + 2] else st[c * 5 + 3]; if (ext < minLen) { drop[c] = true; any = true } }
        if (!any) return
        val lab = IntArray(m.cols() * m.rows()); labels.get(0, 0, lab)
        val b = ByteArray(lab.size); m.get(0, 0, b)
        for (i in lab.indices) if (drop[lab[i]]) b[i] = 0
        m.put(0, 0, b)
    }

    private fun lineKernel(len: Int, deg: Int): Mat {
        val a = Math.toRadians(deg.toDouble())
        val dx = kotlin.math.cos(a); val dy = kotlin.math.sin(a)
        val hw = (abs(dx) * (len - 1) / 2).roundToInt(); val hh = (abs(dy) * (len - 1) / 2).roundToInt()
        val k = Mat.zeros(2 * hh + 1, 2 * hw + 1, CvType.CV_8UC1)
        val c = org.opencv.core.Point(hw.toDouble(), hh.toDouble())
        val half = (len - 1) / 2.0
        Imgproc.line(k, org.opencv.core.Point(c.x - dx * half, c.y - dy * half), org.opencv.core.Point(c.x + dx * half, c.y + dy * half), Scalar(1.0), 1)
        return k
    }

    // =====================================================================================
    // 2-3. Componentes, zonas y recuadros (resolución de trabajo)
    // =====================================================================================

    /** Resultado de la segmentación. [regions] y [blank] en coordenadas de la imagen completa. */
    internal class Layout(
        val regions: List<Region>,
        val letterHeight: Double,   // px de la imagen completa
        val blank: Mat,             // 8UC1 a resolución de trabajo: zonas que deben quedar en blanco (espiral...)
        val scale: Double,          // resolución de trabajo / completa
        val tLow: Double,
        val inkLevel: Mat,          // 8UC1 a 1/4 de la resolución de trabajo: oscuridad típica de la tinta del entorno
        val keep: Mat,              // 8UC1 a resolución de trabajo: tinta aceptada (con margen)
        val strokeHalf: Double,     // semiancho típico del trazo, px de la imagen completa
        val coherent: Mat,          // 8UC1 a resolución de trabajo: 255 = trazo de tinta firme (se uniformiza), 0 = tenue
    ) {
        fun release() { blank.release(); inkLevel.release(); keep.release(); coherent.release() }
    }

    internal fun analyze(rgb: Mat): Layout {
        val p = prepare(rgb)
        try { return layout(p) } finally { p.release() }
    }

    internal class Comp(
        val x: Int, val y: Int, val w: Int, val h: Int, val area: Int,
        val meanDark: Double, val halfWidth: Float, val onRuling: Double, val label: Int,
        /** Oscuridad máxima en el mapa sin normalizar (0 si no se pidió). */
        val maxRaw: Int = 0,
    )

    internal fun layout(p: Prepared): Layout = MatBag().use { bag ->
        val fw = p.dark.cols(); val fh = p.dark.rows()
        val dw = bag.mat()
        val s = Cv.downscale(p.dark, dw, WORK_SIDE)
        val W = dw.cols(); val H = dw.rows()
        Imgproc.GaussianBlur(dw, dw, Size(0.0, 0.0), 0.7)
        // Ruido local: umbral ∝ ganancia^0.85 (el ruido normalizado crece con la ganancia en las sombras)
        val g = bag.mat(); Imgproc.resize(p.gain, g, dw.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.pow(g, NOISE_GAIN_EXP, g)
        val dn = bag.mat(); dw.convertTo(dn, CvType.CV_32F); Core.divide(dn, g, dn)
        // Ruido del mapa ya suavizado (~0.6 del medido en el papel)
        val sigma = max(1.5, p.noise * 0.6)
        val tLow = max(5.0, 2.2 * sigma)
        val tHigh = max(11.0, 4.0 * sigma)
        // Textura del papel (pergamino, papel viejo con manchas, grano grueso, transparencias tenues): el ruido medido
        // en el histograma no la ve, y con el umbral bajo el papel entero se une en una sola componente con la
        // escritura (y luego se borra como "mancha" o como "borde"). Donde la textura local del papel supera el
        // ruido, la oscuridad se divide por ese exceso: los umbrales pasan a ser relativos a la textura local, igual
        // que a la ganancia de las sombras ([paperTexture]).
        val texF = paperTexture(dn, tLow, tHigh, bag)
        val dn8 = bag.mat(); dn.convertTo(dn8, CvType.CV_8U)
        log?.invoke("texF=%.2f".format(texF))
        // Huella de la rejilla a resolución de trabajo
        val rul = bag.mat()
        if (!p.ruling.empty()) {
            Imgproc.resize(p.ruling, rul, dw.size(), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.threshold(rul, rul, 100.0, 255.0, Imgproc.THRESH_BINARY)
        } else { rul.create(dw.size(), CvType.CV_8UC1); rul.setTo(Scalar(0.0)) }

        val weak = bag.mat(); Core.compare(dn8, Scalar(tLow), weak, Core.CMP_GT)
        // Une los trozos de un mismo trazo (cortes por el ruido o donde cruzaba la cuadrícula borrada)
        Imgproc.morphologyEx(weak, weak, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, 3))
        val rb = ByteArray(W * H); rul.get(0, 0, rb)
        val db = ByteArray(W * H); dn8.get(0, 0, db)
        val lab = IntArray(W * H)
        val dtf = FloatArray(W * H)
        val rawb = ByteArray(W * H); dw.get(0, 0, rawb)
        val (nc, comps) = extractComps(weak, db, rb, lab, tHigh, bag, dtf, rawb)
        // Grosor típico del trazo y altura de letra (componentes medianas, sin motas ni restos de rejilla)
        val sized = comps.filter { it.area >= 12 && max(it.w, it.h) >= 5 }
        val hw50 = if (sized.isEmpty()) 1.0 else sized.map { it.halfWidth.toDouble() }.sorted()[sized.size / 2]
        val hw80c = if (sized.isEmpty()) 1.5 else sized.map { it.halfWidth.toDouble() }.sorted()[(sized.size * 0.8).toInt().coerceAtMost(sized.size - 1)]
        // Grosor de la tinta FIRME ponderado por longitud: cientos de motas finas (textura del papel, transparencias)
        // no deben fijar el límite de "trazo grueso" por debajo del trazo real de la pluma o el rotulador
        // (sin las que tocan el borde ni las enormes: marcos, cantos, sombras; a lo sumo x2 del percentil por número)
        val firm = sized.filter {
            it.meanDark >= 2.0 * tHigh && it.x > 2 && it.y > 2 && it.x + it.w < W - 2 && it.y + it.h < H - 2 &&
                max(it.w, it.h) <= max(W, H) / 6
        }
        // (peso = longitud del trazo ~ área / semiancho: los trazos largos y finos mandan; las anillas de una
        // espiral o los borrones, macizos y cortos, no)
        val hw80 = min(2.0 * hw80c, max(hw80c, HwStats.weightedPercentile(firm.map { it.halfWidth.toDouble() },
            firm.map { it.area / max(1.0, it.halfWidth.toDouble()) }, 0.6) ?: 0.0))
        val thickLim = max(4.0, 2.4 * max(hw80, 1.0))
        // Fracción de cada componente con distancia al borde >= thickLim/2: una mancha maciza (espiral, borde
        // oscuro, sombra) es gruesa en buena parte de su área; una palabra con algún borrón de tinta, no
        // (sólo las candidatas a mancha, recorriendo su caja)
        val thickFrac = FloatArray(nc)
        run {
            val lim = (0.5 * thickLim).toFloat()
            for (c in comps) {
                if (c.halfWidth <= thickLim) continue
                var cnt = 0
                for (y in c.y until c.y + c.h) {
                    var i = y * W + c.x
                    for (x in 0 until c.w) { if (lab[i] == c.label && dtf[i] >= lim) cnt++; i++ }
                }
                thickFrac[c.label] = cnt.toFloat() / max(1, c.area)
            }
        }
        // Letras: trazos finos, claramente marcados y fuera de la rejilla
        val letterCand = sized.filter {
            it.halfWidth <= thickLim && it.h >= 6 && it.h <= H / 6 && it.onRuling < 0.5 && it.meanDark >= 1.4 * tHigh &&
                max(it.w, it.h) <= 6 * min(it.w, it.h)
        }
        // Orientación del texto (página girada 90° sin corregir): la altura de letra es el lado perpendicular
        val lh0 = if (letterCand.size < 3) max(12.0, W / 60.0)
        else BoxGrouping.percentile(letterCand.map { max(it.w, it.h) }.toIntArray(), 0.75, 20).toDouble().coerceIn(10.0, W / 8.0)
        val vertical = letterCand.size >= 8 && BoxGrouping.isVerticalText(
            letterCand.map { BoxGrouping.Box(it.x, it.y, it.x + it.w, it.y + it.h) }, max(2, (lh0 * 0.4).roundToInt()))
        val lh = if (letterCand.size < 3) lh0
        else BoxGrouping.percentile(letterCand.map { if (vertical) it.w else it.h }.toIntArray(), 0.5, 20).toDouble().coerceIn(10.0, W / 8.0)
        log?.invoke("work ${W}x$H s=%.3f tLow=%.1f tHigh=%.1f hw50=%.2f hw80c=%.2f hw80=%.2f thickLim=%.1f lh=%.1f vertical=$vertical comps=${comps.size}".format(s, tLow, tHigh, hw50, hw80c, hw80, thickLim, lh))

        // --- Manchas gruesas que no son letras (espiral, huecos, bordes oscuros) -> zona en blanco
        val blobLabels = BooleanArray(nc)
        val softLevel = FloatArray(nc)
        var anyBlob = false; var anySoft = false
        val touch = 2
        for (c in comps) {
            if (c.halfWidth <= thickLim || thickFrac[c.label] < BLOB_THICK_FRAC) continue
            // Trazo grueso pero no oscuro ni enorme (palabra tachada, letras apiñadas, marcador): es escritura
            val edge = c.x <= 2 || c.y <= 2 || c.x + c.w >= W - 2 || c.y + c.h >= H - 2
            if (!edge && c.meanDark < 3.5 * tHigh && c.halfWidth <= 2 * thickLim) continue
            val big = max(c.w, c.h).toDouble()
            val fill = c.area.toDouble() / max(1, c.w * c.h)
            val atBorder = c.x <= touch || c.y <= touch || c.x + c.w >= W - touch || c.y + c.h >= H - touch
            // Letra gruesa (titular en negrita, marcador): alargada respecto del grosor, no maciza, tamaño de letra
            val glyph = !atBorder && big >= 5 * c.halfWidth && big <= 3.5 * lh * 3 && fill in 0.12..0.8 && c.halfWidth <= max(thickLim * 2.5, lh * 0.25)
            if (glyph) continue
            if (!atBorder && c.meanDark < 3.0 * tHigh) {
                // Mancha CLARA y gruesa dentro de la página (fondo de color de una celda o encabezado, borde de una
                // sombra mal normalizado): no es una zona en blanco; se quita su parte tenue y la tinta que lleve
                // encima (mucho más oscura) se conserva
                softLevel[c.label] = (2.0 * c.meanDark).toFloat(); anySoft = true
            } else { blobLabels[c.label] = true; anyBlob = true }
        }
        val blank = Mat(); blank.create(dw.size(), CvType.CV_8UC1); blank.setTo(Scalar(0.0))
        if (anyBlob) {
            val bb = ByteArray(W * H)
            for (i in lab.indices) if (blobLabels[lab[i]]) bb[i] = -1
            blank.put(0, 0, bb)
            // Núcleo grueso (apertura) para no borrar trazos finos pegados a la mancha (líneas de un diagrama)
            // (morfología a 1/MF de la resolución de trabajo: los elementos grandes son caros y las zonas son suaves)
            val mf = if (max(W, H) >= 1200) 3 else 1
            val ms = Size(max(1.0, (W / mf).toDouble()), max(1.0, (H / mf).toDouble()))
            val r = Cv.odd(max(3, (thickLim * 1.6 / mf).roundToInt()))
            val core = bag.mat(); val bs = bag.mat()
            Imgproc.resize(blank, bs, ms, 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.threshold(bs, bs, 127.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(bs, bs, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_ELLIPSE, r))
            Imgproc.resize(bs, core, dw.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
            Core.bitwise_and(core, blank, core)
            // Núcleos CLAROS (fondo de color de una celda o encabezado, borde de una sombra mal normalizado):
            // no son zona en blanco; se quita sólo su parte tenue y la tinta que lleven encima se conserva
            run {
                val cl = bag.mat(); val cs = bag.mat(); val cc = bag.mat()
                val n2 = Imgproc.connectedComponentsWithStats(core, cl, cs, cc, 8, CvType.CV_32S)
                if (n2 > 1) {
                    val cla = IntArray(W * H); cl.get(0, 0, cla)
                    val sum = LongArray(n2); val cnt = IntArray(n2)
                    for (i in cla.indices) { val l = cla[i]; if (l > 0) { sum[l] += (db[i].toInt() and 0xFF).toLong(); cnt[l]++ } }
                    val soft = BooleanArray(n2); var anyS = false
                    for (l in 1 until n2) { val m = sum[l].toDouble() / max(1, cnt[l]); if (m < SOFT_CORE * tHigh) { soft[l] = true; anyS = true } }
                    if (anyS) {
                        val cb = ByteArray(W * H); core.get(0, 0, cb)
                        val wb = ByteArray(W * H); weak.get(0, 0, wb)
                        for (i in cla.indices) {
                            val l = cla[i]
                            if (l > 0 && soft[l]) {
                                cb[i] = 0
                                val lvl = 2.0 * sum[l] / max(1, cnt[l])
                                if ((db[i].toInt() and 0xFF) <= lvl) wb[i] = 0
                            }
                        }
                        core.put(0, 0, cb); weak.put(0, 0, wb); anySoft = true
                    }
                }
            }
            // Zona: el núcleo cerrado a escala de letra (rellena la banda de la espiral) y con margen
            val kc = Cv.odd(max(3, (lh * 0.8 / mf).roundToInt()))
            Imgproc.resize(core, bs, ms, 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.threshold(bs, bs, 0.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(bs, bs, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, kc))
            Imgproc.dilate(bs, bs, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd(max(3, (lh * 0.35 / mf).roundToInt()))))
            Imgproc.resize(bs, blank, dw.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.threshold(blank, blank, 127.0, 255.0, Imgproc.THRESH_BINARY)
        }
        // --- Espiral / anillas: columna (o fila) de >= 8 manchas grandes y macizas, alineadas, de ancho parecido,
        // con paso regular, sin letras entre ellas y repartidas por buena parte de la página -> banda en blanco
        // (con sus alambres y sombras)
        for (band in findBindings(comps, lh, W, H, tHigh, hw50, vertical, letterCand)) {
            log?.invoke("espiral ${band.x0.roundToInt()},${band.y0.roundToInt()}-${band.x1.roundToInt()},${band.y1.roundToInt()} th=${band.thickness}")
            Imgproc.line(blank, org.opencv.core.Point(band.x0, band.y0), org.opencv.core.Point(band.x1, band.y1), Scalar(255.0), band.thickness)
        }
        // Segunda pasada sin las zonas en blanco: los trazos finos pegados a una mancha (línea de un diagrama que
        // llega a la espiral o a una sombra) se separan de ella y se conservan
        val comps2: List<Comp>
        if (anySoft) {
            val wb = ByteArray(W * H); weak.get(0, 0, wb)
            for (i in lab.indices) { val l = lab[i]; if (l > 0 && softLevel[l] > 0f && (db[i].toInt() and 0xFF) <= softLevel[l]) wb[i] = 0 }
            weak.put(0, 0, wb)
        }
        if (Core.countNonZero(blank) > 0 || anySoft) {
            val nb = bag.mat(); Core.bitwise_not(blank, nb)
            Core.bitwise_and(weak, nb, weak)
            comps2 = extractComps(weak, db, rb, lab, tHigh, bag, raw = rawb).second
        } else comps2 = comps.filter { !blobLabels[it.label] }

        // --- Imágenes: zonas grandes y macizas de contenido no-papel (fotos, bloques de color)
        val images = findImages(dw, p, s, blank, lh, bag)

        // --- Filtro de componentes
        val minArea = max(4.0, (0.08 * lh) * (0.08 * lh))
        val thin = max(2.0, 2.2 * hw50 + 1)
        val keepBoxes = ArrayList<BoxGrouping.Box>()
        val keepComps = ArrayList<Comp>()
        for (c in comps2) {
            if (c.area < minArea) continue
            // Resto recto de la rejilla: fino, alargado, sobre la huella de las rectas y claro
            val sh = min(c.w, c.h); val lg = max(c.w, c.h)
            if (sh <= thin && lg >= 3 * sh && c.onRuling >= 0.5 && c.meanDark < 3.5 * tHigh) continue
            if (lg >= 3.5 * sh && c.onRuling >= 0.7 && c.meanDark < 2.5 * tHigh) continue
            // Restos de la rejilla (cruces, tramos): casi todo sobre su huella y no mucho más oscuros que ella
            if (c.onRuling >= 0.75 && c.meanDark < 2.0 * tHigh) continue
            // Rayitas rectas muy tenues (tramos de la rejilla que no se reconocieron como recta)
            if (lg >= 4 * sh && c.meanDark < 1.3 * tHigh && c.halfWidth <= 1.5 * hw50 + 1) continue
            // Cruces de la cuadrícula y motas sobre ella: pequeñas, claras, sobre la huella de las rectas
            if (lg < 0.5 * lh && c.onRuling >= 0.5 && c.meanDark < 3.0 * tHigh) continue
            val x1 = c.x + c.w; val y1 = c.y + c.h
            // Bordes de la hoja / sombra del canto: pegados al borde y alargados a lo largo de él, o gruesos
            val touchLR = c.x <= 1 || x1 >= W - 1; val touchTB = c.y <= 1 || y1 >= H - 1
            // (largos a lo largo del borde: una letra cortada por el borde -"I" de "Informe", el resto de una palabra
            // al pie de la hoja- es corta o poco maciza y se conserva)
            val fillC = c.area.toDouble() / max(1, c.w * c.h)
            // (y estrechos -una franja-: un bloque de escritura fundido por la textura que llega al borde no lo es)
            val stripLR = c.w <= 0.2 * W || fillC >= 0.5
            val stripTB = c.h <= 0.2 * H || fillC >= 0.5
            if ((touchLR && stripLR && c.h >= 3 * c.w && (c.h >= 2.5 * lh || fillC >= 0.5 && c.h >= 1.6 * lh)) ||
                (touchTB && stripTB && c.w >= 3 * c.h && (c.w >= 2.5 * lh || fillC >= 0.5 && c.w >= 1.6 * lh))) continue
            if (((touchLR && c.w <= 2 * lh) || (touchTB && c.h <= 2 * lh)) && c.halfWidth > 1.6 * hw80) continue
            // Dentro de una imagen: ya se conserva entera
            if (images.any { c.x >= it.x0 && c.y >= it.y0 && x1 <= it.x1 && y1 <= it.y1 }) continue
            keepBoxes.add(BoxGrouping.Box(c.x, c.y, x1, y1))
            keepComps.add(c)
        }
        // Motas pequeñas y claras aisladas (lejos de otra tinta): ruido
        val gapX = max(3, (lh * 0.9).roundToInt())
        val gapY = max(2, (lh * 0.3).roundToInt())
        val groups = if (!vertical) BoxGrouping.group(keepBoxes, gapX, gapY)
        else BoxGrouping.group(keepBoxes.map { BoxGrouping.transpose(it) }, gapX, gapY).map { (b, idx) -> BoxGrouping.transpose(b) to idx }
        val pad = max(3, (lh * 0.3).roundToInt())
        val boxes = ArrayList<BoxGrouping.Box>()
        val finalLabels = HashSet<Int>()
        for ((box, idx) in groups) {
            var area = 0; var maxDark = 0.0; var maxLg = 0
            for (i in idx) { val c = keepComps[i]; area += c.area; maxDark = max(maxDark, c.meanDark); maxLg = max(maxLg, max(c.w, c.h)) }
            val small = max(box.w, box.h) < 0.35 * lh
            if (small && (area < 2.5 * minArea || maxDark < 2.2 * tHigh)) continue
            // Cadena de motas claras sin ninguna letra (cruces de la cuadrícula alineados a paso regular): ruido
            if (maxLg < 0.5 * lh && maxDark < 2.5 * tHigh) continue
            boxes.add(box.pad(pad, W, H))
            for (i in idx) finalLabels.add(keepComps[i].label)
        }
        // Máscara de la tinta aceptada (resolución de trabajo, con margen para los bordes suaves): el render sólo
        // pinta los trazos de estas componentes; motas y restos de la rejilla dentro de un recuadro no salen
        val keep = Mat(H, W, CvType.CV_8UC1)
        run {
            val kb = ByteArray(W * H)
            if (finalLabels.isNotEmpty()) {
                val maxL = finalLabels.max()
                val ok = BooleanArray(maxL + 1); for (l in finalLabels) ok[l] = true
                for (i in lab.indices) { val l = lab[i]; if (l in 1..maxL && ok[l]) kb[i] = -1 }
            }
            keep.put(0, 0, kb)
            Imgproc.dilate(keep, keep, Cv.kernel(Imgproc.MORPH_ELLIPSE, 5))
        }
        val merged = BoxGrouping.mergeOverlapping(boxes)
        val k = 1.0 / s
        val regions = ArrayList<Region>()
        for (b in merged) regions.add(scaleRegion(Region(b.x0, b.y0, b.x1, b.y1, Kind.TEXT), k, fw, fh))
        for (b in images) regions.add(scaleRegion(Region(b.x0, b.y0, b.x1, b.y1, Kind.IMAGE), k, fw, fh))
        debug?.let { dbg ->
            val vis = bag.mat(); Imgproc.cvtColor(dw, vis, Imgproc.COLOR_GRAY2RGB)
            Core.bitwise_not(vis, vis)
            val tint = bag.mat(); vis.copyTo(tint); tint.setTo(Scalar(255.0, 200.0, 200.0), blank)
            Core.addWeighted(vis, 0.6, tint, 0.4, 0.0, vis)
            val kept = keepComps.map { it.label }.toHashSet()
            val all = comps.filter { blobLabels[it.label] } + comps2
            for (c in all) {
                val col = when { c in comps && blobLabels[c.label] -> Scalar(255.0, 0.0, 0.0); c.label in kept -> Scalar(0.0, 170.0, 0.0); else -> Scalar(230.0, 160.0, 0.0) }
                Imgproc.rectangle(vis, org.opencv.core.Point(c.x.toDouble(), c.y.toDouble()), org.opencv.core.Point((c.x + c.w).toDouble(), (c.y + c.h).toDouble()), col, 1)
            }
            for (b in merged) Imgproc.rectangle(vis, org.opencv.core.Point(b.x0.toDouble(), b.y0.toDouble()), org.opencv.core.Point(b.x1.toDouble(), b.y1.toDouble()), Scalar(200.0, 0.0, 255.0), 2)
            for (b in images) Imgproc.rectangle(vis, org.opencv.core.Point(b.x0.toDouble(), b.y0.toDouble()), org.opencv.core.Point(b.x1.toDouble(), b.y1.toDouble()), Scalar(0.0, 120.0, 255.0), 3)
            dbg("layout", vis)
        }
        // Oscuridad típica de la tinta alrededor de cada punto (máximo local a escala de letra, suavizado): el
        // contraste se ajusta por zonas (lápiz claro junto a bolígrafo oscuro en el mismo recuadro)
        // (a 1/4 de la resolución de trabajo, reducción por máximo: es un campo suave)
        val ink = Mat()
        run {
            val f = 4.0
            val t3 = bag.mat(); Imgproc.dilate(dw, t3, Cv.kernel(Imgproc.MORPH_RECT, 5))
            Imgproc.resize(t3, ink, Size(max(1.0, (W / f).roundToInt().toDouble()), max(1.0, (H / f).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_NEAREST)
            Imgproc.dilate(ink, ink, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd(max(3, (lh / f).roundToInt()))))
            Imgproc.GaussianBlur(ink, ink, Size(0.0, 0.0), max(1.0, lh * 0.6 / f))
        }
        // Dos tintas (transparencia del reverso, manchas): fuerza de cada componente aceptada = su oscuridad máxima
        val twoInk = run {
            val cs = keepComps.filter { it.label in finalLabels && max(it.w, it.h) >= 0.5 * lh }
            HwStats.twoInk(cs.map { it.maxRaw.toDouble() }, cs.map { it.area.toDouble() })
        }
        log?.invoke("twoInk $twoInk")
        // Con dos tintas, la tinta débil (transparencia del reverso, manchas) no se toma por trazo firme allí donde
        // no hay tinta principal cerca: el nivel de la zona tiene un suelo del 60 % de la tinta principal (la débil
        // sale en gris claro, proporcional a su oscuridad, en vez de negra y uniforme)
        if (twoInk.detected) Core.max(ink, Scalar(TWO_INK_FLOOR * twoInk.front), ink)
        val smallLabels = HashSet<Int>()
        for (c in keepComps) if (max(c.w, c.h) < 0.4 * lh) smallLabels.add(c.label)
        val coherent = coherenceMap(lab, finalLabels, smallLabels, dw, ink, W, H, bag)
        val half = strokeHalfWidth(dw, ink, keep, tHigh, bag) ?: max(1.0, hw50)
        log?.invoke("strokeHalf work=%.2f (hw50=%.2f)".format(half, hw50))
        Layout(regions.sortedWith(compareBy({ it.top }, { it.left })), lh / s, blank, s, tLow, ink, keep, half / s, coherent)
    }

    /**
     * Normaliza [dn] (32F, in-place) por la textura local del papel; devuelve el factor máximo aplicado (1 = sin
     * textura). Textura = alta frecuencia del papel fuera de la tinta, por bloques de 8x8 y mediana en ~1/10 de la
     * página; factor = clamp([TEX_K]·textura / tLow, 1, [TEX_MAX]), como campo suave.
     */
    private fun paperTexture(dn: Mat, tLow: Double, tHigh: Double, bag: MatBag): Double {
        val W = dn.cols(); val H = dn.rows()
        // Sólo la parte de alta frecuencia (|d - suavizado|, recortada a 3·tLow): el grano y las fibras del papel la
        // tienen; el halo de un texto desenfocado o una sombra suave, no (no deben bajar la sensibilidad)
        val hp = bag.mat(); Imgproc.GaussianBlur(dn, hp, Size(0.0, 0.0), TEX_SIGMA)
        Core.absdiff(dn, hp, hp)
        Core.min(hp, Scalar(3.0 * tLow), hp)
        // ...y sólo FUERA de la tinta: los bordes de las letras también son alta frecuencia. Peso = 1 lejos de los
        // píxeles claramente oscuros (> 2·tHigh, con 2 px de margen)
        val ink = bag.mat(); Core.compare(dn, Scalar(2.0 * tHigh), ink, Core.CMP_GT)
        Imgproc.dilate(ink, ink, Cv.kernel(Imgproc.MORPH_RECT, 5))
        val w = bag.mat(); Core.bitwise_not(ink, ink); ink.convertTo(w, CvType.CV_32F, 1.0 / 255.0)
        ink.release()
        Core.multiply(hp, w, hp)
        // Media por bloques de 8x8 sobre el papel; bloques casi todo tinta -> sin textura medible (0)
        val ss = Size(max(4.0, (W / 8.0).roundToInt().toDouble()), max(4.0, (H / 8.0).roundToInt().toDouble()))
        val t = bag.mat(); Imgproc.resize(hp, t, ss, 0.0, 0.0, Imgproc.INTER_AREA)
        val ws = bag.mat(); Imgproc.resize(w, ws, ss, 0.0, 0.0, Imgproc.INTER_AREA)
        hp.release(); w.release()
        val few = bag.mat(); Core.compare(ws, Scalar(0.3), few, Core.CMP_LT)
        Core.max(ws, Scalar(1e-3), ws); Core.divide(t, ws, t)
        t.setTo(Scalar(0.0), few)
        // MEDIANA de los bloques en el entorno (~1/10 de la página): manchas sueltas no cuentan
        val t8 = bag.mat(); t.convertTo(t8, CvType.CV_8U, 4.0)
        val k = Cv.odd(max(3, (max(ss.width, ss.height) / 10.0).roundToInt())).coerceAtMost(31)
        Imgproc.medianBlur(t8, t8, k)
        // factor = clamp(TEX_K·T / tLow, 1, TEX_MAX), campo suave
        t8.convertTo(t, CvType.CV_32F, TEX_K / (4.0 * tLow))
        Imgproc.blur(t, t, Size(k.toDouble(), k.toDouble()))
        Core.max(t, Scalar(1.0), t); Core.min(t, Scalar(TEX_MAX), t)
        val mx = Core.minMaxLoc(t).maxVal
        if (mx <= 1.02) return 1.0
        val tf = bag.mat(); Imgproc.resize(t, tf, dn.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.divide(dn, tf, dn)
        return mx
    }

    /** Textura del papel: la oscuridad se normaliza donde su alta frecuencia media supera tLow / TEX_K. */
    private const val TEX_K = 2.5
    private const val TEX_MAX = 4.0
    private const val TEX_SIGMA = 2.0

    /** Área máxima de letras sueltas dentro de una banda de espiral (fracción del área de las anillas). */
    private const val BINDING_LETTERS = 0.15

    /** Suelo del nivel de tinta de la zona (fracción de la tinta principal) cuando hay dos tintas. */
    private const val TWO_INK_FLOOR = 0.6

    /** Fracción mínima de área gruesa (distancia al borde >= thickLim/2) para tomar una componente por mancha. */
    private const val BLOB_THICK_FRAC = 0.15f

    /**
     * Trazos FIRMES (se les da intensidad uniforme en el render) frente a manchas tenues (transparencias del reverso,
     * borrones; mantienen su tono relativo a la tinta del entorno). Cada componente aceptada recibe su oscuridad
     * MÁXIMA y se propaga 3 px (los tramos débiles de un trazo separados por un fallo de tinta heredan la fuerza del
     * trazo); se compara con la tinta típica de la zona ([ink], escala de letra): >= ~40 % -> firme. Las motas
     * ([small]) necesitan más fuerza.
     * 8UC1 a resolución de trabajo (0..255 = peso de la uniformización).
     */
    private fun coherenceMap(lab: IntArray, labels: Set<Int>, small: Set<Int>, dw: Mat, ink: Mat, W: Int, H: Int, bag: MatBag): Mat {
        val out = Mat(H, W, CvType.CV_8UC1, Scalar(0.0))
        if (labels.isEmpty()) return out
        val db = ByteArray(W * H); dw.get(0, 0, db)
        val maxL = labels.max()
        val mx = IntArray(maxL + 1) { -1 }
        for (l in labels) mx[l] = 0
        for (i in lab.indices) { val l = lab[i]; if (l in 1..maxL && mx[l] >= 0) { val d = db[i].toInt() and 0xFF; if (d > mx[l]) mx[l] = d } }
        // Motas (más pequeñas que media letra): su fuerza cuenta x0.6 (sólo se uniformizan si son claramente tinta:
        // el punto de una i sí, el grano del papel o los restos de la cuadrícula no)
        for (l in small) if (l in 1..maxL && mx[l] > 0) mx[l] = (mx[l] * 0.6).toInt()
        val sb = ByteArray(W * H)
        for (i in lab.indices) { val l = lab[i]; if (l in 1..maxL && mx[l] > 0) sb[i] = mx[l].toByte() }
        val st = bag.mat(); st.create(H, W, CvType.CV_8UC1); st.put(0, 0, sb)
        Imgproc.dilate(st, st, Cv.kernel(Imgproc.MORPH_ELLIPSE, 7))
        val lv = bag.mat(); Imgproc.resize(ink, lv, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val sf = bag.mat(); st.convertTo(sf, CvType.CV_32F)
        val lf = bag.mat(); lv.convertTo(lf, CvType.CV_32F); Core.max(lf, Scalar(20.0), lf)
        Core.divide(sf, lf, sf)
        // rampa 0.22..0.42 del nivel de la zona
        sf.convertTo(sf, -1, 1.0 / 0.20, -0.22 / 0.20)
        Core.min(sf, Scalar(1.0), sf); Core.max(sf, Scalar(0.0), sf)
        sf.convertTo(out, CvType.CV_8U, 255.0)
        return out
    }

    /**
     * Semiancho típico del trazo (px de trabajo): mediana de la transformada de distancia en las crestas (máximos
     * locales) del núcleo de la tinta aceptada (oscuridad >= 45 % de la tinta de la zona). null si hay poca tinta.
     */
    private fun strokeHalfWidth(dw: Mat, ink: Mat, keep: Mat, tHigh: Double, bag: MatBag): Double? {
        val lv = bag.mat(); Imgproc.resize(ink, lv, dw.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val thr = bag.mat(); lv.convertTo(thr, CvType.CV_8U, 0.45); Core.max(thr, Scalar(tHigh), thr)
        val core = bag.mat(); Core.compare(dw, thr, core, Core.CMP_GE)
        Core.bitwise_and(core, keep, core)
        val dt = bag.mat(); Imgproc.distanceTransform(core, dt, Imgproc.DIST_L2, 3)
        val mx = bag.mat(); Imgproc.dilate(dt, mx, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val ridge = bag.mat(); Core.compare(dt, mx, ridge, Core.CMP_GE)
        val pos = bag.mat(); Core.compare(dt, Scalar(0.5), pos, Core.CMP_GT); Core.bitwise_and(ridge, pos, ridge)
        val n = Core.countNonZero(ridge)
        if (n < 200) return null
        // Mediana por histograma (medios píxeles)
        val dt8 = bag.mat(); dt.convertTo(dt8, CvType.CV_8U, 2.0, -0.5)   // (suelo de 2·dt)
        return max(1.0, (Cv.percentile(Cv.histogram(dt8, ridge), 0.5) + 0.5) / 2.0)
    }

    /** Componentes de [weak] con algún píxel > [tHigh] (histéresis). Rellena [lab] con las etiquetas. */
    private fun extractComps(
        weak: Mat, db: ByteArray, rb: ByteArray, lab: IntArray, tHigh: Double, bag: MatBag,
        dtOut: FloatArray? = null, raw: ByteArray? = null,
    ): Pair<Int, List<Comp>> {
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(weak, labels, stats, cents, 8, CvType.CV_32S)
        val dt = bag.mat(); Imgproc.distanceTransform(weak, dt, Imgproc.DIST_L2, 3)
        labels.get(0, 0, lab)
        val dtf = dtOut ?: FloatArray(lab.size); dt.get(0, 0, dtf)
        dt.release(); labels.release(); cents.release()
        val maxDt = FloatArray(nc); val sumD = LongArray(nc); val strong = BooleanArray(nc); val onR = IntArray(nc)
        val mxR = IntArray(nc)
        val th = tHigh.toInt()
        for (i in lab.indices) {
            val c = lab[i]; if (c == 0) continue
            val d = db[i].toInt() and 0xFF
            sumD[c] += d.toLong()
            if (d > th) strong[c] = true
            if (dtf[i] > maxDt[c]) maxDt[c] = dtf[i]
            if (rb[i].toInt() != 0) onR[c]++
            if (raw != null) { val r = raw[i].toInt() and 0xFF; if (r > mxR[c]) mxR[c] = r }
        }
        val st = IntArray(nc * 5); if (nc > 0) stats.get(0, 0, st)
        stats.release()
        val comps = ArrayList<Comp>()
        for (c in 1 until nc) {
            if (!strong[c]) continue
            val o = c * 5
            val a = st[o + 4]
            comps.add(Comp(st[o], st[o + 1], st[o + 2], st[o + 3], a, sumD[c].toDouble() / a, maxDt[c], onR[c].toDouble() / a, c, mxR[c]))
        }
        return nc to comps
    }

    /**
     * Bandas de encuadernación (espiral): componentes grandes (lado largo >= 3.5 % de la página, corto >= 1.5 %), macizas
     * (>= 20 % de su caja), oscuras y gruesas; >= 8 alineadas (centro a <= 4 % de la página), casi contiguas, con paso
     * regular, sin letras sueltas dentro de la banda ([HwBinding.isRingRow]), que
     * cubren >= 35 % de la página. Devuelve las bandas (con margen) en coordenadas de trabajo.
     */
    internal class Band(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val thickness: Int)

    internal fun findBindings(comps: List<Comp>, lh: Double, W: Int, H: Int, tHigh: Double, hw50: Double, textVertical: Boolean = false, letters: List<Comp> = emptyList()): List<Band> {
        val side = max(W, H).toDouble()
        // Anillas: oscuras (sombra y hueco) y gruesas; una línea de texto larga (escrita en vertical) no lo es
        val big = comps.filter {
            max(it.w, it.h) >= 0.035 * side && min(it.w, it.h) >= 0.015 * side && it.area >= 0.2 * it.w * it.h &&
                it.meanDark >= max(35.0, 3.5 * tHigh) && it.halfWidth >= 2.5 * hw50
        }
        val out = ArrayList<Band>()
        val margin = lh * 0.8
        val usedAll = BooleanArray(big.size)   // una anilla pertenece a una sola banda
        for (vertical in booleanArrayOf(true, false)) {
            // Banda vertical: anillas más anchas que altas; se sigue una recta (la espiral puede verse inclinada)
            // (las anillas pueden aparecer fundidas entre sí: no se exige que sean alargadas)
            val cand = big
            fun along(c: Comp) = if (vertical) c.y + c.h / 2.0 else c.x + c.w / 2.0
            fun across(c: Comp) = if (vertical) c.x + c.w / 2.0 else c.y + c.h / 2.0
            val used = usedAll.copyOf()
            for (i in cand.indices) {
                if (used[i]) continue
                var a = 0.0; var b = across(cand[i])   // across = a·along + b
                var members: List<Int> = emptyList()
                repeat(3) {
                    members = cand.indices.filter { !used[it] && abs(across(cand[it]) - (a * along(cand[it]) + b)) <= 0.04 * side }
                    if (members.size >= 2) {
                        // Mínimos cuadrados
                        val n = members.size.toDouble()
                        val mx = members.sumOf { along(cand[it]) } / n; val my = members.sumOf { across(cand[it]) } / n
                        var sxx = 0.0; var sxy = 0.0
                        for (m in members) { val dx = along(cand[m]) - mx; sxx += dx * dx; sxy += dx * (across(cand[m]) - my) }
                        a = if (sxx > 1e-6) (sxy / sxx).coerceIn(-0.25, 0.25) else 0.0
                        b = my - a * mx
                    }
                }
                if (members.size < 4) continue
                // Anillas de una misma espiral: tamaño parecido (ancho perpendicular a la banda dentro de x0.55..1.8
                // de la mediana); el resto de condiciones (paso, estrechez, sin letras dentro) en [HwBinding.isRingRow]
                fun acrossExt(c: Comp) = if (vertical) c.w else c.h
                fun alongExt(c: Comp) = if (vertical) c.h else c.w
                val medAcross = members.map { acrossExt(cand[it]).toDouble() }.sorted()[members.size / 2]
                members = members.filter { acrossExt(cand[it]).toDouble() in (0.55 * medAcross)..(1.8 * medAcross) }
                if (members.size < 4) continue
                var halfB = 0.0
                for (m in members) {
                    val c = cand[m]; val center = a * along(c) + b
                    val e0 = if (vertical) c.x.toDouble() else c.y.toDouble()
                    val e1 = if (vertical) (c.x + c.w).toDouble() else (c.y + c.h).toDouble()
                    halfB = max(halfB, max(center - e0, e1 - center))
                }
                // Dentro de la banda no hay letras: las anillas son manchas gruesas y la zona entre ellas es papel o
                // alambre. Una "fila de anillas" hecha de palabras fundidas (manuscrito antiguo, letra gruesa) lleva
                // dentro muchas letras sueltas de tamaño normal. Se descartan las anillas con letras a su altura y se
                // exige que queden casi todas (y pocas letras en toda la banda)
                val memberSet = members.map { cand[it].label }.toHashSet()
                val lo0 = members.minOf { if (vertical) cand[it].y else cand[it].x }
                val hi0 = members.maxOf { if (vertical) cand[it].y + cand[it].h else cand[it].x + cand[it].w }
                val inBand = letters.filter { c ->
                    val al = along(c)
                    c.label !in memberSet && al >= lo0 && al <= hi0 && abs(across(c) - (a * al + b)) <= halfB
                }
                var ringArea = 0.0; for (m in members) ringArea += cand[m].area
                val letterArea = inBand.sumOf { it.area.toDouble() }
                val n0 = members.size
                members = members.filter { m ->
                    val c = cand[m]
                    val a0 = if (vertical) c.y else c.x; val a1 = if (vertical) c.y + c.h else c.x + c.w
                    inBand.filter { val al = along(it); al >= a0 && al <= a1 }.sumOf { it.area.toDouble() } < BINDING_LETTERS * c.area
                }
                val clean = letterArea < 2 * BINDING_LETTERS * ringArea && members.size >= 0.7 * n0
                if (members.size < 4) continue
                // Una espiral paralela a los renglones va en el borde (bloc de anillas arriba) o es muy regular y
                // limpia (doble página con la escritura girada): un renglón de letras gruesas no lo es
                val parallel = vertical == textVertical
                if (!HwBinding.isRingRow(members.map { along(cand[it]) }, members.map { acrossExt(cand[it]).toDouble() },
                        members.map { alongExt(cand[it]).toDouble() }, (if (vertical) W else H).toDouble(), clean,
                        strict = parallel && run {
                            val dim = (if (vertical) W else H).toDouble()
                            val mid = a * (if (vertical) H else W) / 2.0 + b
                            mid > 0.12 * dim && mid < 0.88 * dim
                        })) continue
                val lo = members.minOf { if (vertical) cand[it].y else cand[it].x }
                val hi = members.maxOf { if (vertical) cand[it].y + cand[it].h else cand[it].x + cand[it].w }
                if (hi - lo < 0.35 * (if (vertical) H else W)) continue
                // Las anillas se suceden casi sin huecos: su extensión a lo largo de la banda cubre >= 45 % del tramo
                val cover = members.sumOf { if (vertical) cand[it].h else cand[it].w }.toDouble() / (hi - lo)
                if (cover < 0.45) continue
                for (m in members) { used[m] = true; usedAll[m] = true }
                // Semiancho: extremo de las anillas respecto de la recta
                var half = 0.0
                for (m in members) {
                    val c = cand[m]; val center = a * along(c) + b
                    val e0 = if (vertical) c.x.toDouble() else c.y.toDouble()
                    val e1 = if (vertical) (c.x + c.w).toDouble() else (c.y + c.h).toDouble()
                    half = max(half, max(center - e0, e1 - center))
                }
                val len = (if (vertical) H else W).toDouble()
                val th = (2 * (half + margin)).roundToInt()
                out.add(if (vertical) Band(b, 0.0, a * len + b, len, th) else Band(0.0, b, len, a * len + b, th))
            }
        }
        return out
    }

    /**
     * Fotos / bloques de color: a 1/8 de la resolución de trabajo, bloques con oscuridad media alta (>= 40) o
     * color saturado y oscuro; cierre; componentes grandes (>= 1.2 % de la página, lado >= 6 %), macizas
     * (>= 55 % de su caja) y no alargadas (las bandas de la espiral o de un borde sí lo son; un bloque de color
     * alargado se acepta si es colorido y casi lleno). Las que tocan el borde sólo si son enormes (página-foto).
     */
    private fun findImages(dw: Mat, p: Prepared, s: Double, blank: Mat, lh: Double, bag: MatBag): List<BoxGrouping.Box> {
        val W = dw.cols(); val H = dw.rows()
        val cw = max(8, W / 8); val ch = max(8, H / 8)
        val dc = bag.mat(); Imgproc.resize(dw, dc, Size(cw.toDouble(), ch.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val ns = bag.mat(); Imgproc.resize(p.n, ns, Size(cw.toDouble(), ch.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val hsv = bag.mat(); Imgproc.cvtColor(ns, hsv, Imgproc.COLOR_RGB2HSV)
        val sat = bag.mat(); Core.extractChannel(hsv, sat, 1)
        val cand = bag.mat(); Core.compare(dc, Scalar(40.0), cand, Core.CMP_GE)
        val m2 = bag.mat(); Core.compare(sat, Scalar(90.0), m2, Core.CMP_GE)
        val m3 = bag.mat(); Core.compare(dc, Scalar(18.0), m3, Core.CMP_GE)
        Core.bitwise_and(m2, m3, m2); Core.bitwise_or(cand, m2, cand)
        Imgproc.morphologyEx(cand, cand, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(cand, labels, stats, cents, 8, CvType.CV_32S)
        val out = ArrayList<BoxGrouping.Box>()
        val total = cw.toDouble() * ch
        val row = IntArray(5)
        val sel = bag.mat()
        for (c in 1 until nc) {
            stats.get(c, 0, row)
            val x = row[0]; val y = row[1]; val w = row[2]; val h = row[3]; val a = row[4].toDouble()
            if (a < 0.012 * total || min(w.toDouble() / cw, h.toDouble() / ch) < 0.06) continue
            val fill = a / (w * h)
            if (fill < 0.55) continue
            val aspect = max(w, h).toDouble() / max(1, min(w, h))
            Core.compare(labels, Scalar(c.toDouble()), sel, Core.CMP_EQ)
            val meanSat = Core.mean(sat, sel).`val`[0]
            if (aspect > 4.5 && !(meanSat >= 80 && fill >= 0.85)) continue
            val atBorder = x <= 0 || y <= 0 || x + w >= cw || y + h >= ch
            if (atBorder && a < 0.25 * total) continue
            val k = W.toDouble() / cw
            val kh = H.toDouble() / ch
            out.add(BoxGrouping.Box((x * k).toInt(), (y * kh).toInt(), min(W, ((x + w) * k).roundToInt()), min(H, ((y + h) * kh).roundToInt())))
        }
        return out
    }

    // =====================================================================================
    // 4. Render
    // =====================================================================================

    enum class Style { COLOR, BLACK_WHITE }

    /**
     * Render del filtro sobre [rgb] (8UC3, resolución completa; no se modifica). COLOR: 8UC3 (tinta con su
     * color reforzado sobre blanco puro); BLACK_WHITE: 8UC1 (tinta negra con bordes suaves sobre blanco puro).
     */
    internal fun render(rgb: Mat, style: Style, fast: Boolean = false): Mat = MatBag().use { bag ->
        val p = prepare(rgb, bgSide = if (fast) 256 else 384, refineSide = if (fast) 320 else 512)
        try {
            val lay = layout(p)
            try {
                val t0 = System.nanoTime()
                compose(p, lay, style, bag).also { log?.invoke("compose $style %.0f ms".format((System.nanoTime() - t0) / 1e6)) }
            } finally { lay.release() }
        } finally { p.release() }
    }

    private fun compose(p: Prepared, lay: Layout, style: Style, bag: MatBag): Mat {
        val fw = p.dark.cols(); val fh = p.dark.rows()
        val color = style == Style.COLOR
        val out = Mat(fh, fw, if (color) CvType.CV_8UC3 else CvType.CV_8UC1, Scalar.all(255.0))
        val full = Size(fw.toDouble(), fh.toDouble())
        // Tinta aceptada, nivel de tinta de la zona y peso de la uniformización, a resolución completa
        val keepFull = bag.mat(); Imgproc.resize(lay.keep, keepFull, full, 0.0, 0.0, Imgproc.INTER_LINEAR)
        val inkFull = bag.mat(); Imgproc.resize(lay.inkLevel, inkFull, full, 0.0, 0.0, Imgproc.INTER_LINEAR)
        val cohFull = bag.mat(); Imgproc.resize(lay.coherent, cohFull, full, 0.0, 0.0, Imgproc.INTER_LINEAR)
        val pm = p.paper
        val sigma = max(1.5, p.noise)
        val gw = p.gain.cols(); val gh = p.gain.rows()
        val gRow = FloatArray(1)
        // Radio del máximo local "a escala de trazo" (algo más que el semiancho: el centro del trazo entra en la
        // ventana de los píxeles del borde) y de la ventana del color de la tinta
        val rad = max(2, (lay.strokeHalf + 1.0).roundToInt()).coerceAtMost(10)
        log?.invoke("render strokeHalf=%.2f rad=$rad".format(lay.strokeHalf))
        // Radio de "fondo local" (mínimo): mayor que el semiancho de cualquier trazo normal
        val radBg = max(rad + 2, (2.5 * lay.strokeHalf + 2.0).roundToInt()).coerceAtMost(16)
        val pad = max(3 * rad, radBg) + 2
        val sm = StrokeMats(bag)
        for (r in lay.regions) {
            if (r.kind != Kind.TEXT || r.width <= 0 || r.height <= 0) continue
            // Umbral de tinta según el ruido local (sombras), del centro del recuadro
            p.gain.get(((r.top + r.bottom) / 2.0 / fh * gh).toInt().coerceIn(0, gh - 1), ((r.left + r.right) / 2.0 / fw * gw).toInt().coerceIn(0, gw - 1), gRow)
            val t0 = max(5.0, 2.2 * sigma) * Math.pow(gRow[0].toDouble(), NOISE_GAIN_EXP)
            // Por franjas de <= ~1 MP (con margen para las ventanas locales): los intermedios en coma flotante quedan
            // acotados aunque el recuadro sea la página entera a 12-20 MP
            val stripH = max(16, STRIP_PIXELS / max(1, r.width))
            var y0 = r.top
            while (y0 < r.bottom) {
                val y1 = min(r.bottom, y0 + stripH)
                val inner = Rect(r.left, y0, r.width, y1 - y0)
                y0 = y1
                // Margen sólo en los cortes entre franjas: el recuadro ya deja ~0.3 letras de papel alrededor de la tinta
                val px0 = inner.x; val px1 = inner.x + inner.width
                val py0 = if (inner.y > r.top) max(0, inner.y - pad) else inner.y
                val py1 = if (inner.y + inner.height < r.bottom) min(fh, inner.y + inner.height + pad) else inner.y + inner.height
                val outer = Rect(px0, py0, px1 - px0, py1 - py0)
                val crop = Rect(inner.x - px0, inner.y - py0, inner.width, inner.height)
                strokeStrip(p, sm, outer, crop, inkFull, keepFull, cohFull, t0, rad, radBg, color, pm, out.submat(inner))
            }
        }
        // Imágenes: mejora suave del original normalizado
        for (r in lay.regions) {
            if (r.kind != Kind.IMAGE || r.width <= 0 || r.height <= 0) continue
            val rect = Rect(r.left, r.top, r.width, r.height)
            val nRoi = p.n.submat(rect)
            val oRoi = out.submat(rect)
            val src = if (color) nRoi else bag.add(Cv.gray(nRoi))
            val gq = if (color) bag.add(Cv.gray(nRoi)) else src
            val hq = Cv.histogram(gq)
            val black = Cv.percentile(hq, 0.005) * 0.9
            val white = max(black + 40.0, min(pm, Cv.percentile(hq, 0.995).toDouble()))
            Cv.applyLut(src, Cv.levelsLut(black, white, 1.1), oRoi)
            nRoi.release(); oRoi.release()
        }
        return out
    }

    /** Intermedios reutilizados entre franjas del render. */
    private class StrokeMats(bag: MatBag) {
        val d = bag.mat(); val dc = bag.mat(); val lm = bag.mat(); val f = bag.mat(); val g = bag.mat()
        val lvl = bag.mat(); val t0m = bag.mat(); val den = bag.mat(); val tmp = bag.mat(); val tmp3 = bag.mat()
        val aOld = bag.mat(); val aNew = bag.mat(); val gate = bag.mat(); val q = bag.mat(); val k8 = bag.mat()
        val d3 = bag.mat(); val w = bag.mat(); val dmax = bag.mat(); val num = bag.mat(); val wsum = bag.mat()
        val qf = bag.mat(); val af = bag.mat(); val nf = bag.mat()
        /** smoothstep x²(3-2x) de 0..255 a 0..255 */
        val smooth: Mat = bag.add(Cv.lut { i -> val x = i / 255.0; x * x * (3 - 2 * x) * 255.0 })
        /** refuerzo de la tinta tenue por zona: clamp(150 / max(tinta, 20), 1, 4) · 63.75 */
        val boost: Mat = bag.add(Cv.lut { i -> (150.0 / max(i, 20)).coerceIn(1.0, 4.0) * 63.75 })
    }

    /** dst(8U) = smoothstep(clamp(a·src + b)) · 255 (src en coma flotante u 8 bits). */
    private fun smooth8(src: Mat, a: Double, b: Double, dst: Mat, s: StrokeMats) {
        src.convertTo(dst, CvType.CV_8U, 255.0 * a, 255.0 * b)
        Core.LUT(dst, s.smooth, dst)
    }

    /**
     * Una franja de un recuadro de escritura: calcula sobre [outer] (franja + margen en los cortes) y escribe [crop]
     * (la franja, en coordenadas de [outer]) en [dst].
     *
     * Alfa de la tinta, mezcla de dos modelos según [cohFull] (peso de trazo firme):
     *  - ANTIGUO (manchas tenues: transparencias, borrones): rampa relativa a la tinta típica de la zona (12 %..90 %);
     *  - UNIFORME (trazos firmes): cierre de 3 px (une fallos de tinta de 1-2 px y el grano del papel dentro del
     *    trazo) y oscuridad relativa al MÁXIMO LOCAL a escala de trazo ([rad]): el centro de un tramo débil del
     *    trazo (poca presión, lápiz suave) vale lo mismo que el de un tramo fuerte, y el borde conserva su
     *    cobertura parcial (anti-aliasing). Una compuerta absoluta (t0..2·t0) deja fuera el ruido del papel. Sólo en
     *    estructuras finas (en el interior de una mancha ancha manda el modelo antiguo).
     * COLOR: los trazos firmes se pintan con el color de la tinta del entorno (media ponderada a escala de trazo,
     * saturación reforzada) y una intensidad única (>= [MIN_TONE]): sin tramos claros ni desaturados.
     * Las rampas y productos van en 8 bits con tablas (rápido en gama baja); sólo los cocientes en coma flotante.
     */
    private fun strokeStrip(
        p: Prepared, s: StrokeMats, outer: Rect, crop: Rect, inkFull: Mat, keepFull: Mat, cohFull: Mat,
        t0: Double, rad: Int, radBg: Int, color: Boolean, pm: Double, dst: Mat,
    ) {
        val dRoi = p.dark.submat(outer); dRoi.copyTo(s.d); dRoi.release()
        val inkRoi = inkFull.submat(outer); inkRoi.convertTo(s.lvl, CvType.CV_32F); inkRoi.release()
        Core.max(s.lvl, Scalar(20.0), s.lvl)
        // --- Alfa antiguo: rampa t0' = max(t0, 12 % de la tinta) .. 90 % de la tinta
        Core.multiply(s.lvl, Scalar(0.12), s.t0m); Core.max(s.t0m, Scalar(t0), s.t0m)
        Core.multiply(s.lvl, Scalar(0.9), s.den); Core.add(s.t0m, Scalar(10.0), s.tmp); Core.max(s.den, s.tmp, s.den); Core.subtract(s.den, s.t0m, s.den)
        s.d.convertTo(s.f, CvType.CV_32F); Core.subtract(s.f, s.t0m, s.f); Core.divide(s.f, s.den, s.f)
        smooth8(s.f, 1.0, 0.0, s.aOld, s)
        // --- Alfa uniforme: cierre 3 px, oscuridad relativa al máximo local a escala de trazo
        Imgproc.morphologyEx(s.d, s.dc, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, 3))
        Imgproc.dilate(s.dc, s.lm, Cv.kernel(Imgproc.MORPH_RECT, 2 * rad + 1))
        val n0 = 0.5 * t0
        // denominador = max(máx. local - n0, 2.5·t0, 12 % de la tinta de la zona): el ruido aislado no llega a tinta plena
        s.lm.convertTo(s.den, CvType.CV_32F, 1.0, -n0)
        Core.multiply(s.lvl, Scalar(0.12), s.t0m); Core.max(s.t0m, Scalar(2.5 * t0), s.t0m)
        Core.max(s.den, s.t0m, s.den)
        s.dc.convertTo(s.f, CvType.CV_32F, 1.0, -n0); Core.divide(s.f, s.den, s.f)
        // r 0.2..0.65 -> 0..1 (cobertura del borde, sin engordar el trazo) · compuerta absoluta t0..2·t0
        smooth8(s.f, 1.0 / 0.45, -0.2 / 0.45, s.aNew, s)
        smooth8(s.dc, 1.0 / t0, -1.0, s.gate, s)
        Core.multiply(s.aNew, s.gate, s.aNew, 1.0 / 255.0)
        // --- Peso de la uniformización: trazo firme ([cohFull]) y fino (oscuridad sobre el mínimo local a ~2.5
        // semianchos, relativa a la propia; en el interior de una mancha ancha el mínimo local es la propia mancha)
        Imgproc.erode(s.dc, s.lm, Cv.kernel(Imgproc.MORPH_RECT, 2 * radBg + 1))
        Core.subtract(s.dc, s.lm, s.tmp3)                         // 8U, satura en 0
        s.tmp3.convertTo(s.g, CvType.CV_32F)
        s.dc.convertTo(s.den, CvType.CV_32F); Core.max(s.den, Scalar(2.0 * t0), s.den)
        Core.divide(s.g, s.den, s.g)
        smooth8(s.g, 1.0 / 0.4, -0.3 / 0.4, s.q, s)
        val cRoi = cohFull.submat(outer); Core.multiply(s.q, cRoi, s.q, 1.0 / 255.0); cRoi.release()
        // --- Tinta aceptada
        val kRoi = keepFull.submat(outer)
        Core.multiply(s.aOld, kRoi, s.aOld, 1.0 / 255.0); Core.multiply(s.aNew, kRoi, s.aNew, 1.0 / 255.0)
        kRoi.release()
        // Pesos de cada modelo (8 bits): (1-q)·aOld y q·aNew
        Core.bitwise_not(s.q, s.gate)
        Core.multiply(s.aOld, s.gate, s.aOld, 1.0 / 255.0)
        Core.multiply(s.aNew, s.q, s.gate, 1.0 / 255.0)          // gate = q·aNew (aNew se conserva para el color)
        if (!color) {
            // a = (1-q)·aOld + q·aNew  ->  255 - a
            Core.add(s.aOld, s.gate, s.k8)
            Core.bitwise_not(s.k8, s.k8)
            val a8c = s.k8.submat(crop)
            Core.min(dst, a8c, dst)
            a8c.release(); dst.release()
            return
        }
        // --- COLOR. Oscuridad por canal respecto del papel (8UC3, 0..pm)
        val nRoi = p.n.submat(outer)
        Core.bitwise_not(nRoi, s.tmp3); nRoi.release()
        Core.subtract(s.tmp3, Scalar.all(255.0 - pm), s.tmp3)
        val sc = 255.0 / pm
        val full = s.d.size()
        val half = Size(max(1.0, ((full.width + 1) / 2).toDouble()), max(1.0, ((full.height + 1) / 2).toDouble()))
        // Uniforme: color medio de la tinta del entorno ponderado por (alfa·oscuridad)² (mandan los píxeles firmes), a
        // media resolución (campo suave de ventana ~6 semianchos)
        val ch = ArrayList<Mat>(3); Core.split(s.tmp3, ch)
        Core.max(ch[0], ch[1], s.dc); Core.max(s.dc, ch[2], s.dc)
        for (c in ch) c.release()
        ch.clear()
        Core.multiply(s.aNew, s.dc, s.k8, 1.0 / 255.0)
        Imgproc.resize(s.k8, s.lm, half, 0.0, 0.0, Imgproc.INTER_AREA)
        s.lm.convertTo(s.w, CvType.CV_32F); Core.multiply(s.w, s.w, s.w)
        Imgproc.resize(s.tmp3, s.d3, half, 0.0, 0.0, Imgproc.INTER_AREA)
        s.d3.convertTo(s.d3, CvType.CV_32FC3, sc)
        val w3 = ArrayList<Mat>(3); repeat(3) { w3.add(s.w) }
        Core.merge(w3, s.nf)
        Core.multiply(s.d3, s.nf, s.d3)
        val kh = Cv.odd(max(3, 3 * rad)).toDouble()
        Imgproc.boxFilter(s.d3, s.d3, -1, Size(kh, kh))
        Imgproc.boxFilter(s.w, s.wsum, -1, Size(kh, kh))
        Core.max(s.wsum, Scalar(1e-3), s.wsum)
        Core.split(s.d3, ch)
        for (c in ch) Core.divide(c, s.wsum, c)
        // Intensidad: la del entorno, al menos MIN_TONE -> factor k; la media del tono se escala por k
        Core.max(ch[0], ch[1], s.num); Core.max(s.num, ch[2], s.num)
        Core.max(s.num, Scalar(1.0), s.tmp)
        Core.max(s.num, Scalar(MIN_TONE), s.num); Core.min(s.num, Scalar(MAX_TONE), s.num)
        Core.divide(s.num, s.tmp, s.num)                         // k
        val mean = s.wsum
        Core.add(ch[0], ch[1], mean); Core.add(mean, ch[2], mean); Core.multiply(mean, Scalar(1.0 / 3.0), mean)
        // croma: x k si la tinta es claramente de color (croma relativo (máx-mín)/máx >= 0.35: bolígrafo azul, rojo),
        // x min(k, 1.3) si es casi gris (<= 0.15: lápiz, tinta negra con el tinte de una sombra); x1.35 de saturación
        Core.min(ch[0], ch[1], s.f); Core.min(s.f, ch[2], s.f)
        Core.max(ch[0], ch[1], s.g); Core.max(s.g, ch[2], s.g)
        Core.subtract(s.g, s.f, s.f); Core.max(s.g, Scalar(1.0), s.g); Core.divide(s.f, s.g, s.f)
        s.f.convertTo(s.f, -1, 1.0 / 0.2, -0.15 / 0.2); Core.min(s.f, Scalar(1.0), s.f); Core.max(s.f, Scalar(0.0), s.f)
        Core.min(s.num, Scalar(1.3), s.tmp)
        Core.subtract(s.num, s.tmp, s.g); Core.multiply(s.g, s.f, s.g); Core.add(s.tmp, s.g, s.tmp)
        Core.multiply(s.tmp, Scalar(1.35), s.tmp)
        Core.multiply(mean, s.num, s.num)                         // media escalada
        for (c in ch) { Core.subtract(c, mean, c); Core.multiply(c, s.tmp, c); Core.add(c, s.num, c) }
        Core.merge(ch, s.d3)
        for (c in ch) c.release()
        Imgproc.resize(s.d3, s.nf, full, 0.0, 0.0, Imgproc.INTER_LINEAR)
        s.nf.convertTo(s.af, CvType.CV_8UC3)                       // tono uniforme (8UC3)
        // Antiguo: oscuridad del propio píxel con saturación 1.35 (d_c = 1.35·d_c - 0.35·media) y refuerzo por zona
        // 150 / tinta (x1..x4, codificado x63.75)
        val sat = Mat(3, 3, CvType.CV_32F)
        val dg = 1.35 - 0.35 / 3; val og = -0.35 / 3
        sat.put(0, 0, dg * sc, og * sc, og * sc, og * sc, dg * sc, og * sc, og * sc, og * sc, dg * sc)
        Core.transform(s.tmp3, s.g, sat); sat.release()   // 8UC3 saturado
        val inkRoi2 = inkFull.submat(outer); Core.LUT(inkRoi2, s.boost, s.k8); inkRoi2.release()
        Core.multiply(s.aOld, s.k8, s.aOld, 1.0 / 255.0)       // (1-q)·aOld·refuerzo·63.75
        val a3 = ArrayList<Mat>(3)
        repeat(3) { a3.add(s.aOld) }; Core.merge(a3, s.d3); a3.clear()
        Core.multiply(s.g, s.d3, s.g, 1.0 / 63.75)
        repeat(3) { a3.add(s.gate) }; Core.merge(a3, s.d3)
        Core.multiply(s.af, s.d3, s.af, 1.0 / 255.0)
        Core.add(s.g, s.af, s.g)
        Core.bitwise_not(s.g, s.g)
        val oc = s.g.submat(crop)
        Core.min(dst, oc, dst)
        oc.release(); dst.release()
    }

    /** Intensidad mínima/máxima (oscuridad del canal más absorbido, 0..255) de un trazo firme en "Texto resaltado". */
    private const val MIN_TONE = 210.0
    private const val MAX_TONE = 240.0
}
