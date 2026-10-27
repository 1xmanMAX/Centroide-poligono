package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decisiones puras de "Color natural" (sin OpenCV; probadas en JVM).
 */
internal object NaturalMath {
    /** Rampa lineal 0..1 entre [lo] y [hi]. */
    fun ramp(v: Double, lo: Double, hi: Double): Double = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)

    /** Factor de saturación según el croma CIE: lo casi neutro (tinta negra/gris, papel) pierde el tinte; el color, x[sat]. */
    fun chromaFactor(chroma: Double, sat: Double = NaturalColor.SATURATION): Double =
        ramp(chroma, NaturalColor.NEUTRAL_LO, NaturalColor.NEUTRAL_HI) * sat

    /** Ganancias del balance de blancos: el papel medido (por canal) pasa a blanco; acotadas (una página de color no es papel). */
    fun whiteGains(paper: DoubleArray): DoubleArray {
        val g = DoubleArray(3) { 255.0 / max(1.0, paper[it]) }
        val m = g.average()
        // dominante residual acotada a ±12 % alrededor de la media
        return DoubleArray(3) { (g[it]).coerceIn(m * 0.88, m * 1.12).coerceIn(0.9, 1.8) }
    }

    /** Croma desde el que un píxel es tinta de color: ~2.5 veces el croma del papel (ruido de color de la cámara), 5..12. */
    fun chromaLow(paperChromaP95: Double): Double = (2.5 * paperChromaP95).coerceIn(5.0, 12.0)

    /** Oscuridad relativa desde la que un trazo tenue (lápiz, tinta pálida) es candidato: >= 5σ del papel, >= 0.08. */
    fun faintLow(noise: Double): Double = max(0.08, 5.0 * noise / 255.0)

    /**
     * Refuerzo de la densidad de la tinta (Beer-Lambert: más concentración de la misma tinta, mismo matiz): exponente
     * k sobre la transmitancia, tal que la densidad óptica [od] de la luminancia llegue a [target]; 1 si ya es densa.
     */
    fun densityGain(od: Double, target: Double = NaturalColor.OD_TARGET, kMax: Double = NaturalColor.K_MAX): Double =
        if (od <= 1e-3) kMax else (target / od).coerceIn(1.0, kMax)

    /**
     * ¿Componente añadida (tenue) lo bastante fuerte? Oscuridad media >= [GHOST_K] x la de la tinta típica del B/N, o con
     * color propio (croma medio >= 0.8 x el p95 del croma del papel, >= 3). La transparencia del reverso y las manchas grises no pasan.
     */
    fun strongEnough(meanDark: Double, inkDark: Double, meanChroma: Double, paperChroma: Double): Boolean =
        meanDark >= NaturalColor.GHOST_K * inkDark || meanChroma >= max(3.0, 0.8 * paperChroma)

    /**
     * ¿Componente sin tinta en el B/N lo bastante clara como escritura? Color vivo (croma medio >= VIVID_K·cLo), oscura
     * (>= VIVID_KD·tF), o -si tiene tamaño de palabra y no está junto al borde (6 %)- tenue con algo de color propio
     * (resaltador, tinta pálida en sombra).
     */
    fun vivid(meanC: Double, meanD: Double, cLo: Double, tF: Double, paperC: Double, wordSized: Boolean): Boolean =
        meanC >= NaturalColor.VIVID_K * cLo || meanD >= NaturalColor.VIVID_KD * tF ||
            (wordSized && ((meanD >= 1.25 * tF && meanC >= max(3.0, paperC)) || meanC >= 1.1 * cLo))

    /** ¿La caja toca el borde de la imagen (1 px de margen)? */
    fun touchesBorder(x: Int, y: Int, w: Int, h: Int, imgW: Int, imgH: Int): Boolean =
        x <= 1 || y <= 1 || x + w >= imgW - 1 || y + h >= imgH - 1

    /** ¿La caja está en la franja del 3 % junto al borde de la imagen? */
    fun nearBorder(x: Int, y: Int, w: Int, h: Int, imgW: Int, imgH: Int, frac: Double = 0.03): Boolean {
        val mx = frac * imgW; val my = frac * imgH
        return x < mx || y < my || x + w > imgW - mx || y + h > imgH - my
    }

    /** Punto blanco tras el balance: el grano del papel (2.5σ) queda en blanco puro. */
    fun whitePoint(noise: Double): Double = (255.0 - 2.5 * noise).coerceIn(215.0, 250.0)

    /** Punto negro moderado: la tinta más oscura (percentil 3 %) baja ~un 30 %; nunca más que [MAX_BLACK]. */
    fun blackPoint(inkP3: Double?): Double = if (inkP3 == null) 0.0 else (inkP3 * 0.7).coerceIn(0.0, NaturalColor.MAX_BLACK)

    /**
     * Clase de una componente de croma (a la escala de análisis, [lh] = altura de letra a esa escala).
     * 0 = descartar (mota, rejilla/renglón largo y fino), 1 = trazo de color, 2 = figura/foto/logo (pasa entera).
     */
    fun colorComponentClass(w: Int, h: Int, area: Int, lh: Double, inkFrac: Double = 1.0, thickness: Double = 0.0, vivid: Boolean = true, colorful: Boolean = true): Int {
        val l = max(2.0, lh)
        if (area < max(3.0, 0.02 * l * l)) return 0
        val long = max(w, h).toDouble(); val short = min(w, h).toDouble()
        val fill = area.toDouble() / max(1, w * h)
        // Renglón / recta de la cuadrícula: largo y muy fino
        if (long > 6 * l && area / long < 0.14 * l) return 0
        // Retícula (cuadrícula entera conectada): enorme, casi vacía y sin tinta en el B/N (unas palabras fundidas sí la tienen)
        if (w > 6 * l && h > 6 * l && fill < 0.18 && inkFrac < 0.05) return 0
        // Mancha gruesa (figura, resaltador, banda impresa del cuaderno): sólo si el B/N vio contenido dentro (texto
        // resaltado, foto o logo en gris); una banda de color vacía (margen, cabecera del cuaderno) no es escritura
        // Grosor = 2 x máxima distancia al borde (una palabra con las letras fundidas sigue siendo fina; una banda no)
        val thick = thickness > 1.2 * l
        // Figura (foto, logo): gruesa, compacta y de color en conjunto (una tabla con bandas de color no lo es: sus letras
        // siguen el tratamiento de la tinta)
        if (colorful && thickness >= 2.5 * l && area >= 25 * l * l && fill >= 0.35 && short >= 3 * l) return if (inkFrac >= 0.04) 2 else 0
        if (thick && inkFrac < 0.02 && ((long > 8 * l && fill > 0.5) || !vivid)) return 0
        // Sin nada de tinta en el B/N, sólo si su color u oscuridad media es clara (resaltador, tinta de color): las motas
        // del papel, de una banda impresa o los agujeros de la espiral, débiles, no
        if (inkFrac < 0.01 && !vivid) return 0
        return 1
    }
}

/**
 * "Color natural": el resultado de "Blanco y negro" (fondo blanco puro, sólo la escritura) se usa como MÁSCARA
 * SUAVE sobre la foto normalizada (iluminación y sombras corregidas): lo negro deja pasar el color original de
 * la tinta y lo blanco queda blanco puro. Además:
 * - continuidad del trazo: cierre morfológico del alfa aceptado sólo donde la foto muestra tinta (gris o color) e
 *   histéresis (lo tenue pegado a un trazo firme entra) -> el texto no se ve fragmentado;
 * - tinta de color clara (resaltador, bolígrafo pálido, lápiz de color) que el B/N pierde: máscara por croma;
 * - figuras/fotos/logos de color: regiones grandes de color pasan enteras;
 * - realce natural: balance de blancos con el papel, punto negro/blanco moderados, saturación x[SATURATION] y
 *   lo casi neutro sin dominante; el color de cada píxel de borde es el del centro del trazo (sin halo claro).
 * Trabaja por teselas (memoria acotada; las teselas sin tinta salen blancas sin cálculo).
 */
internal object NaturalColor {
    internal var log: ((String) -> Unit)? = null
    /** Depuración (escritorio): recibe el alfa final (8UC1) de cada composición. */
    internal var debug: ((String, Mat) -> Unit)? = null
    private var dbgA: Mat? = null
    internal var logComps = false

    /** "Blanco y negro" también une los trazos rotos ([bridge]) con la foto normalizada. */
    @JvmField internal var BRIDGE_BW = false
    /** Nivel de [bridge] en el B/N (1 = cierre, 2 = cierre + histéresis + trazos tenues). */
    @JvmField internal var BRIDGE_LEVEL = 2

    const val ANALYSIS_SIDE = 1000
    const val TILE = 1024
    const val SATURATION = 1.0
    /** Densidad óptica (luminancia) objetivo de la tinta tenue y refuerzo máximo ([NaturalMath.densityGain]). */
    const val OD_TARGET = 0.9
    const val K_MAX = 2.8
    /** Cobertura del alfa (ventana de ~1 letra) desde la que una zona es color macizo (resaltador, banda): sin refuerzo. */
    const val FILL_LO = 0.55
    const val FILL_HI = 0.8
    const val NEUTRAL_LO = 4.0
    const val NEUTRAL_HI = 11.0
    const val MAX_BLACK = 70.0
    /** Rampa relativa al máximo local del trazo ([relRamp]). */
    /** Componente sin tinta en el B/N: su croma u oscuridad media debe ser >= VIVID_K veces el umbral. */
    const val VIVID_K = 1.6
    const val VIVID_KD = 2.0
    /** Componente tenue: oscuridad media mínima relativa a la tinta típica del B/N ([NaturalMath.strongEnough]). */
    const val GHOST_K = 0.5
    @JvmField var REL_LO = 0.35
    @JvmField var REL_HI = 0.7
    /** En el B/N ([bridge]) la evidencia relativa es más exigente: la transparencia del reverso y las manchas (20-40 % de la
     * oscuridad del texto) no deben unirse a la escritura; un tramo tenue del mismo trazo sí (>= ~la mitad). */
    @JvmField var REL_LO_BW = 0.5
    @JvmField var REL_HI_BW = 0.8
    /** Rampa relativa de la evidencia en las zonas candidatas (tinta tenue o de color que el B/N perdió). */
    @JvmField var REL_LO_SUP = 0.25
    @JvmField var REL_HI_SUP = 0.6
    /** Alfa por debajo del cual el píxel es papel (blanco puro). */
    const val ALPHA_MIN = 0.05

    class Inputs(
        val bw: Mat,                 // 8UC1 resultado B/N (255 papel), tamaño de salida
        val nrm: Mat,                // 8UC3 RGB normalizado (papel ≈ PAPER_LEVEL), mismo tamaño
        val letterHeight: Double,    // px de salida
        val strokeHalf: Double,      // semiancho típico del trazo (px de salida), 0 = desconocido
        val images: List<Rect> = emptyList(),      // bloques de imagen (pasan enteros, en color)
        val allowed: List<Rect>? = null,           // zonas donde puede haber color (null = toda la página)
        val exclude: Mat? = null,    // 8UC1 (cualquier tamaño): 255 = no es tinta (huella de la rejilla, fondo ajeno)
        val exclude2: Mat? = null,
        val excludeForeign: Boolean = false,       // [exclude] es fondo ajeno (mesa): lo pegado a él cuenta como borde   // ídem (p. ej. zonas en blanco de la segmentación, a resolución de trabajo)
        /** 0 = "Color natural"; 1 = sólo cierre de roturas (para el B/N); 2 = cierre + histéresis + trazos tenues junto a tinta del B/N. */
        val bridge: Int = 0,
        val gridColor: DoubleArray? = null,        // RGB normalizado de la cuadrícula clara (su tono no es tinta), o null
    ) { val bridgeOnly: Boolean get() = bridge > 0 }

    private class Global(
        val gain: DoubleArray, val black: Double, val white: Double, val t0: Double,
        val support: Mat, val figures: Mat, val scale: Double, val cLo: Double, val tF: Double,
    ) { fun release() { support.release(); figures.release() } }

    /** Compone "Color natural" (8UC3 nuevo). */
    fun compose(inp: Inputs): Mat = MatBag().use { bag ->
        val W = inp.bw.cols(); val H = inp.bw.rows()
        require(inp.nrm.cols() == W && inp.nrm.rows() == H) { "tamaños distintos" }
        val t0 = System.nanoTime()
        val g = analyzeGlobal(inp, bag)
        try {
            val out = Mat(H, W, if (inp.bridgeOnly) CvType.CV_8UC1 else CvType.CV_8UC3, Scalar.all(255.0))
            val lh = max(4.0, inp.letterHeight)
            val sh = if (inp.strokeHalf > 0) inp.strokeHalf else max(1.0, lh * 0.06)
            val r = (sh + 1.0).roundToInt().coerceIn(1, 6)                  // ventana del color del centro del trazo
            val kc = Cv.odd((2.0 * sh + 1.0).roundToInt().coerceIn(3, 11))   // cierre de huecos / roturas
            val kn = Cv.odd(min((0.25 * lh).roundToInt(), (3.0 * sh + 1.0).roundToInt()).coerceIn(3, 13))   // vecindad de la histéresis (~1.5 anchos de trazo)
            val kl = Cv.odd((0.6 * lh).roundToInt().coerceIn(5, 31))         // máximo local (contraste relativo al trazo)
            val pad = kc + kn + kl + r + 4
            val sw = g.support.cols(); val shh = g.support.rows()
            val supB = ByteArray(sw * shh); g.support.get(0, 0, supB)
            val figB = ByteArray(sw * shh); g.figures.get(0, 0, figB)
            var tiles = 0; var skipped = 0
            if (debug != null && !inp.bridgeOnly) dbgA = Mat(H, W, CvType.CV_8UC1, Scalar(0.0))
            var y = 0
            while (y < H) {
                val y1 = min(H, y + TILE)
                var x = 0
                while (x < W) {
                    val x1 = min(W, x + TILE)
                    tiles++
                    // Tesela sin tinta en el B/N, sin color y sin figura -> blanca (ya lo es)
                    val bwT = inp.bw.submat(y, y1, x, x1)
                    val mn = Core.minMaxLoc(bwT).minVal; bwT.release()
                    val any = mn < 250 || anyIn(supB, sw, shh, g.scale, x, y, x1, y1) || anyIn(figB, sw, shh, g.scale, x, y, x1, y1)
                    if (any) tile(inp, g, out, Rect(x, y, x1 - x, y1 - y), pad, r, kc, kn, kl, lh)
                    else skipped++
                    x = x1
                }
                y = y1
            }
            dbgA?.let { debug?.invoke("alpha", it); it.release(); dbgA = null }
            log?.invoke("compose ${W}x$H tiles=$tiles vacías=$skipped gain=%.2f,%.2f,%.2f black=%.0f white=%.0f t0=%.3f %.0f ms".format(
                g.gain[0], g.gain[1], g.gain[2], g.black, g.white, g.t0, (System.nanoTime() - t0) / 1e6))
            out
        } finally { g.release() }
    }

    /** ¿Hay algún píxel activo de la máscara a baja resolución dentro del rectángulo de salida? */
    private fun anyIn(b: ByteArray, w: Int, h: Int, s: Double, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        val a0 = (x0 * s).toInt().coerceIn(0, w - 1); val a1 = (x1 * s).toInt().coerceIn(a0, w - 1)
        val b0 = (y0 * s).toInt().coerceIn(0, h - 1); val b1 = (y1 * s).toInt().coerceIn(b0, h - 1)
        for (yy in b0..b1) { val o = yy * w; for (xx in a0..a1) if (b[o + xx].toInt() != 0) return true }
        return false
    }

    /** Estadística global a la escala de análisis: balance de blancos, puntos negro/blanco, máscaras de color y figuras. */
    private fun analyzeGlobal(inp: Inputs, bag: MatBag): Global {
        val sm = bag.mat(); val s = Cv.downscale(inp.nrm, sm, ANALYSIS_SIDE)
        val bws = bag.mat(); Imgproc.resize(inp.bw, bws, sm.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        val sw = sm.cols(); val shh = sm.rows()
        // Papel: blanco en el B/N y poco croma
        val ch = ArrayList<Mat>(3); Core.split(sm, ch); ch.forEach { bag.add(it) }
        val mx = bag.mat(); Core.max(ch[0], ch[1], mx); Core.max(mx, ch[2], mx)
        val mn = bag.mat(); Core.min(ch[0], ch[1], mn); Core.min(mn, ch[2], mn)
        val chr = bag.mat(); Core.subtract(mx, mn, chr)
        val paper = bag.mat(); val t = bag.mat()
        Imgproc.threshold(bws, paper, 252.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.threshold(chr, t, 30.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Core.bitwise_and(paper, t, paper)
        for (exM in listOf(inp.exclude, inp.exclude2)) excludeMask(exM, sm.size(), bag)?.let { ex -> Core.bitwise_not(ex, t); Core.bitwise_and(paper, t, paper) }
        val enough = Core.countNonZero(paper) > 0.05 * sm.total()
        val pc = DoubleArray(3) { c -> if (enough) Cv.percentile(Cv.histogram(ch[c], paper), 0.6).toDouble() else Cv.PAPER_LEVEL }
        val gain = NaturalMath.whiteGains(pc)
        // Imagen balanceada (8 bits) a la escala de análisis
        val smw = bag.mat(); Core.multiply(sm, Scalar(gain[0], gain[1], gain[2]), smw)
        val lum = bag.add(Cv.gray(smw))
        val noise = if (enough) Cv.paperStats(Cv.histogram(lum, paper)).first else 3.0
        val white = NaturalMath.whitePoint(noise)
        val ink = bag.mat(); Imgproc.threshold(bws, ink, 100.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val nInk = Core.countNonZero(ink)
        val black = NaturalMath.blackPoint(if (nInk > 50) Cv.percentile(Cv.histogram(lum, ink), 0.03).toDouble() else null)
        val t0 = max(0.07, 3.0 * noise / 255.0)
        // Croma del papel (ruido de color de la cámara): umbral de la tinta de color relativo a él
        val chroma = chromaOf(smw, bag)
        val c8 = bag.mat(); chroma.convertTo(c8, CvType.CV_8U, 4.0)
        val cPaper = if (enough) Cv.percentile(Cv.histogram(c8, paper), 0.95) / 4.0 else 3.0
        val cLo = NaturalMath.chromaLow(cPaper)
        val tF = NaturalMath.faintLow(noise)
        // Candidatos a escritura que el B/N pudo perder (tenue o de color): sobre la imagen ERODIDA a doble escala
        // (los trazos finos no se diluyen con el papel al reducir)
        val er = bag.mat()
        run {
            val big = bag.mat(); Cv.downscale(inp.nrm, big, 2 * ANALYSIS_SIDE)
            Imgproc.erode(big, big, Cv.kernel(Imgproc.MORPH_RECT, 3))
            Imgproc.resize(big, er, sm.size(), 0.0, 0.0, Imgproc.INTER_AREA)
            Core.multiply(er, Scalar(gain[0], gain[1], gain[2]), er)
        }
        val cm = bag.mat(); val cEr = chromaOf(er, bag)
        Imgproc.threshold(cEr, cm, cLo, 255.0, Imgproc.THRESH_BINARY); cm.convertTo(cm, CvType.CV_8U)
        val lumEr = bag.add(Cv.gray(er))
        Imgproc.threshold(lumEr, t, 255.0 * (1 - tF), 255.0, Imgproc.THRESH_BINARY_INV)
        Core.bitwise_or(cm, t, cm)
        // Tono de la cuadrícula clara: no es escritura
        inp.gridColor?.let { gc ->
            val erf = bag.mat(); er.convertTo(erf, CvType.CV_32F)
            gridLike(erf, gc, gain, bag)?.let { gl ->
                val g8 = bag.mat(); Imgproc.threshold(gl, g8, 0.5, 255.0, Imgproc.THRESH_BINARY); g8.convertTo(g8, CvType.CV_8U)
                Core.bitwise_and(cm, g8, cm)
            }
        }
        for (exM in listOf(inp.exclude, inp.exclude2)) excludeMask(exM, sm.size(), bag)?.let { ex -> Core.bitwise_not(ex, t); Core.bitwise_and(cm, t, cm) }
        inp.allowed?.let { rs ->
            val al = bag.add(Mat.zeros(sm.size(), CvType.CV_8U))
            for (r in rs) Imgproc.rectangle(al, org.opencv.core.Point(r.x * s, r.y * s), org.opencv.core.Point((r.x + r.width) * s, (r.y + r.height) * s), Scalar(255.0), -1)
            Core.bitwise_and(cm, al, cm)
        }
        val lhS = max(2.0, inp.letterHeight * s)
        val support = Mat.zeros(sm.size(), CvType.CV_8U)
        val figures = Mat.zeros(sm.size(), CvType.CV_8U)
        if (inp.bridge != 1) {
            val labels = bag.mat(); val stats = bag.mat(); val cent = bag.mat()
            val n = Imgproc.connectedComponentsWithStats(cm, labels, stats, cent, 8, CvType.CV_32S)
            if (n > 1) {
                val st = IntArray(n * 5); stats.get(0, 0, st)
                val lb = IntArray(sw * shh); labels.get(0, 0, lb)
                // Tinta del B/N dentro de cada componente
                val inkM = bag.mat(); Imgproc.threshold(bws, inkM, 200.0, 255.0, Imgproc.THRESH_BINARY_INV)
                val inkB = ByteArray(sw * shh); inkM.get(0, 0, inkB)
                val inkN = IntArray(n); for (i in lb.indices) if (inkB[i].toInt() != 0) inkN[lb[i]]++
                // Grosor máximo de cada componente (transformada de distancia)
                val dt = bag.mat(); Imgproc.distanceTransform(cm, dt, Imgproc.DIST_L2, 3)
                val dtA = FloatArray(sw * shh); dt.get(0, 0, dtA)
                val thk = DoubleArray(n); for (i in lb.indices) { val v = 2.0 * dtA[i]; if (v > thk[lb[i]]) thk[lb[i]] = v }
                // Pegado al fondo ajeno (mesa, madera que el recorte dejó dentro): como si tocara el borde
                val foreign = BooleanArray(n)
                if (inp.excludeForeign) excludeMask(inp.exclude, sm.size(), bag)?.let { fm ->
                    Imgproc.dilate(fm, fm, Cv.kernel(Imgproc.MORPH_RECT, 5))
                    val fb = ByteArray(sw * shh); fm.get(0, 0, fb)
                    for (i in lb.indices) if (fb[i].toInt() != 0) foreign[lb[i]] = true
                }
                // Croma y oscuridad medias de cada componente
                val cA = FloatArray(sw * shh); cEr.get(0, 0, cA)
                val lA = ByteArray(sw * shh); lumEr.get(0, 0, lA)
                val sC = DoubleArray(n); val sD = DoubleArray(n)
                for (i in lb.indices) { val l = lb[i]; sC[l] += cA[i]; sD[l] += 1.0 - (lA[i].toInt() and 0xFF) / 255.0 }
                // Oscuridad típica de la tinta del B/N: lo gris y mucho más claro que ella (transparencia del reverso, manchas)
                // no se añade; la tinta de color clara sí (croma)
                val inkD = if (nInk > 50) 1.0 - Cv.percentile(Cv.histogram(lumEr, ink), 0.5) / 255.0 else 0.0
                // (lo que toca el borde de la imagen es mesa, madera o el canto: el B/N ya decidió qué contenido hay allí)
                val cls = IntArray(n) {
                    val x = st[it * 5]; val y = st[it * 5 + 1]; val w = st[it * 5 + 2]; val h = st[it * 5 + 3]; val ar = st[it * 5 + 4]
                    val inkF = inkN[it].toDouble() / max(1, ar)
                    if (it == 0 || foreign[it] || NaturalMath.touchesBorder(x, y, w, h, sw, shh)) 0
                    else {
                        val c = NaturalMath.colorComponentClass(w, h, ar, lhS, inkF, thk[it],
                            vivid = NaturalMath.vivid(sC[it] / max(1, ar), sD[it] / max(1, ar), cLo, tF, cPaper, max(w, h) < 8 * lhS && !NaturalMath.nearBorder(x, y, w, h, sw, shh, 0.06)),
                            colorful = sC[it] / max(1, ar) >= 2.0 * cLo)
                        // Junto al borde (restos del canto, agujeros, mesa, madera): sólo trazos finos con tinta en el B/N
                        if (NaturalMath.nearBorder(x, y, w, h, sw, shh) && (inkF < 0.01 || ((c == 2 || thk[it] > 1.2 * lhS) && inkF < 0.05))) 0
                        // (B/N: sólo lo que continúa tinta que el B/N ya tiene; ni color suelto ni figuras)
                        else if (inp.bridge == 2 && (c == 2 || inkF < 0.1)) 0
                        else if (c == 1 && inkF < 0.5 && !NaturalMath.strongEnough(sD[it] / max(1, ar), inkD, sC[it] / max(1, ar), cPaper)) 0
                        else c
                    }
                }
                val sb = ByteArray(sw * shh); val fb = ByteArray(sw * shh)
                if (logComps) for (it in 1 until n) if (st[it * 5 + 4] > 15) log?.invoke("comp cls=${cls[it]} ${st[it*5]},${st[it*5+1]} ${st[it*5+2]}x${st[it*5+3]} a=${st[it*5+4]} ink=%.3f thk=%.1f C=%.1f D=%.3f lh=%.1f".format(inkN[it].toDouble()/st[it*5+4], thk[it], sC[it]/st[it*5+4], sD[it]/st[it*5+4], lhS))
                for (i in lb.indices) { when (cls[lb[i]]) { 1 -> sb[i] = -1; 2 -> fb[i] = -1 } }
                support.put(0, 0, sb); figures.put(0, 0, fb)
                // Trazos de color: algo de margen (bordes del trazo); figuras: margen y huecos rellenos
                Imgproc.dilate(support, support, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd((0.3 * lhS).roundToInt().coerceIn(3, 9))))
                if (Core.countNonZero(figures) > 0) {
                    Imgproc.dilate(figures, figures, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd((0.5 * lhS).roundToInt().coerceIn(3, 15))))
                    fillHoles(figures, bag)
                }
            }
            if (!inp.bridgeOnly) for (r in inp.images) Imgproc.rectangle(figures, org.opencv.core.Point(r.x * s, r.y * s), org.opencv.core.Point((r.x + r.width) * s - 1, (r.y + r.height) * s - 1), Scalar(255.0), -1)
        }
        log?.invoke("global cPaper=%.1f cLo=%.1f tF=%.3f paper=%.0f,%.0f,%.0f noise=%.1f ink=$nInk inkFrac=%.3f color=%.4f fig=%.4f".format(cPaper, cLo, tF, pc[0], pc[1], pc[2], noise,
            nInk.toDouble() / sm.total(), Core.countNonZero(support).toDouble() / sm.total(), Core.countNonZero(figures).toDouble() / sm.total()))
        debug?.let { d -> d("cand", cm); d("support", support); d("figures", figures) }
        return Global(gain, black, white, t0, support, figures, s, cLo, tF)
    }

    private fun excludeMask(ex: Mat?, size: Size, bag: MatBag): Mat? {
        if (ex == null || ex.empty()) return null
        val m = bag.mat(); Imgproc.resize(ex, m, size, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.threshold(m, m, 20.0, 255.0, Imgproc.THRESH_BINARY)
        return m
    }

    private fun fillHoles(m: Mat, bag: MatBag) {
        val cs = ArrayList<MatOfPoint>(); val hier = bag.mat()
        Imgproc.findContours(m.clone().also { bag.add(it) }, cs, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        Imgproc.drawContours(m, cs, -1, Scalar(255.0), -1)
        cs.forEach { it.release() }
    }

    /** Croma CIE (32FC1) de un RGB 8UC3 o 32FC3 (0..255). */
    private fun chromaOf(rgb: Mat, bag: MatBag): Mat {
        val f = bag.mat()
        rgb.convertTo(f, CvType.CV_32F, 1.0 / 255.0)
        Core.min(f, Scalar.all(1.0), f)
        val lab = bag.mat(); Imgproc.cvtColor(f, lab, Imgproc.COLOR_RGB2Lab)
        val a = bag.mat(); val b = bag.mat()
        Core.extractChannel(lab, a, 1); Core.extractChannel(lab, b, 2)
        val c = Mat(); Core.magnitude(a, b, c)
        return bag.add(c)
    }

    /** Rampa 0..1 en coma flotante (en el sitio). */
    private fun rampF(m: Mat, lo: Double, hi: Double) {
        Core.subtract(m, Scalar(lo), m); Core.multiply(m, Scalar(1.0 / (hi - lo)), m)
        Imgproc.threshold(m, m, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(m, m, 0.0, 0.0, Imgproc.THRESH_TOZERO)
    }

    /**
     * Evidencia de tinta RELATIVA al trazo del entorno: rampa entre max(suelo, REL_LO·máx local) y REL_HI·máx local.
     * Un trazo tenue llega a 1 en su centro igual que uno oscuro, y el desenfoque de su borde (aura) no cuenta.
     */
    private fun relRamp(x: Mat, floor: Double, kl: Int, dst: Mat, bag: MatBag, relLo: Double = REL_LO, relHi: Double = REL_HI, localMax: Mat? = null) {
        val mx = localMax ?: bag.mat().also { Imgproc.dilate(x, it, Cv.kernel(Imgproc.MORPH_ELLIPSE, kl)) }
        val lo = bag.mat(); mx.convertTo(lo, -1, relLo); Core.max(lo, Scalar(floor), lo)
        val hi = bag.mat(); mx.convertTo(hi, -1, relHi); Core.max(hi, lo, hi); Core.add(hi, Scalar(floor * 0.25 + 1e-4), hi)
        Core.subtract(x, lo, dst); Core.subtract(hi, lo, hi); Core.divide(dst, hi, dst)
        Imgproc.threshold(dst, dst, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(dst, dst, 0.0, 0.0, Imgproc.THRESH_TOZERO)
    }

    /**
     * 1 - "parece cuadrícula" (32F): desviación respecto al papel con el mismo matiz que la de la rejilla (coseno >= 0.95)
     * y no mucho más intensa (hasta 2-3 veces: también las bandas y marcos impresos del mismo color). null si la rejilla es casi neutra (no se distingue por el tono).
     */
    private fun gridLike(nw: Mat, gc: DoubleArray, gain: DoubleArray, bag: MatBag): Mat? {
        val gv = DoubleArray(3) { 255.0 - gc[it] * gain[it] }
        val gn = kotlin.math.sqrt(gv.sumOf { it * it })
        if (gn < 8.0 || (gv.maxOrNull()!! - gv.minOrNull()!!) < 0.25 * gn) return null
        val d = bag.mat(); nw.convertTo(d, -1, -1.0, 255.0)
        val dot = bag.mat(); Core.transform(d, dot, Mat(1, 3, CvType.CV_32F).also { bag.add(it); it.put(0, 0, gv[0] / gn, gv[1] / gn, gv[2] / gn) })
        val sq = bag.mat(); Core.multiply(d, d, sq)
        val nrm = bag.mat(); Core.transform(sq, nrm, Mat(1, 3, CvType.CV_32F).also { bag.add(it); it.put(0, 0, 1.0, 1.0, 1.0) }); Core.sqrt(nrm, nrm)
        // coseno -> rampa 0.9..0.97; intensidad -> rampa inversa 2..3 veces la de la rejilla
        val cs = bag.mat(); Core.add(nrm, Scalar(1e-3), sq); Core.divide(dot, sq, cs)
        rampF(cs, 0.90, 0.97)
        val it = bag.mat(); nrm.convertTo(it, -1, -1.0 / gn, 3.0)   // 1 en <= 2·gn, 0 en >= 3·gn (bandas impresas del mismo azul)
        Imgproc.threshold(it, it, 1.0, 1.0, Imgproc.THRESH_TRUNC); Imgproc.threshold(it, it, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        Core.multiply(cs, it, cs)
        cs.convertTo(cs, -1, -1.0, 1.0)
        return cs
    }

    /** Máscara de baja resolución -> 0..1 (32F) en la zona [outer] de salida. */
    private fun upMask(lo: Mat, s: Double, outer: Rect, bag: MatBag): Mat {
        val m = bag.mat()
        val x0 = outer.x * s; val y0 = outer.y * s
        // remapeo afín: salida (u,v) -> baja resolución (x0 + u·s, y0 + v·s)
        val aff = Mat(2, 3, CvType.CV_64F); bag.add(aff)
        aff.put(0, 0, s, 0.0, x0 - 0.5 + 0.5 * s, 0.0, s, y0 - 0.5 + 0.5 * s)
        Imgproc.warpAffine(lo, m, aff, Size(outer.width.toDouble(), outer.height.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
        m.convertTo(m, CvType.CV_32F, 1.0 / 255.0)
        return m
    }

    /** Una tesela [inner] (con [pad] de contexto) -> escribe en [out]. */
    private fun tile(inp: Inputs, g: Global, out: Mat, inner: Rect, pad: Int, r: Int, kc: Int, kn: Int, kl: Int, lhT: Double) = MatBag().use { bag ->
        val W = inp.bw.cols(); val H = inp.bw.rows()
        val ox0 = max(0, inner.x - pad); val oy0 = max(0, inner.y - pad)
        val ox1 = min(W, inner.x + inner.width + pad); val oy1 = min(H, inner.y + inner.height + pad)
        val outer = Rect(ox0, oy0, ox1 - ox0, oy1 - oy0)
        val crop = Rect(inner.x - ox0, inner.y - oy0, inner.width, inner.height)
        // Color balanceado (float 0..255+)
        val nw = bag.mat(); inp.nrm.submat(outer).also { bag.add(it) }.convertTo(nw, CvType.CV_32F)
        Core.multiply(nw, Scalar(g.gain[0], g.gain[1], g.gain[2]), nw)
        // Oscuridad (luminancia) relativa al papel blanco
        val lum = bag.mat(); Core.transform(nw, lum, Mat(1, 3, CvType.CV_32F).also { bag.add(it); it.put(0, 0, 0.299, 0.587, 0.114) })
        val dk = bag.mat(); lum.convertTo(dk, -1, -1.0 / 255.0, 1.0)
        // (oscuridad algo suavizada: el grano no parte el trazo tenue)
        val dks = bag.mat(); Imgproc.GaussianBlur(dk, dks, Size(0.0, 0.0), 1.0)
        val rLo = if (inp.bridgeOnly) REL_LO_BW else REL_LO; val rHi = if (inp.bridgeOnly) REL_HI_BW else REL_HI
        val mxD = bag.mat(); Imgproc.dilate(dks, mxD, Cv.kernel(Imgproc.MORPH_ELLIPSE, kl))
        val e = bag.mat(); relRamp(dks, g.t0, kl, e, bag, rLo, rHi, mxD)
        // Croma a media resolución (la conversión a Lab es lo más caro) y ampliado: también sirve para el color
        val hs = Size(max(1.0, (outer.width / 2.0).roundToInt().toDouble()), max(1.0, (outer.height / 2.0).roundToInt().toDouble()))
        val nwH = bag.mat(); Imgproc.resize(nw, nwH, hs, 0.0, 0.0, Imgproc.INTER_AREA)
        val chH = chromaOf(nwH, bag)
        val chroma = bag.mat(); Imgproc.resize(chH, chroma, outer.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val ec = bag.mat(); relRamp(chroma, g.cLo, kl, ec, bag, REL_LO_SUP, REL_HI_SUP)
        // Trazo tenue (lápiz, tinta pálida) dentro de las zonas candidatas
        // (en las zonas candidatas ya validadas -componentes con tinta, color propio o bastante oscuras- la rampa es más permisiva)
        val ef = bag.mat(); relRamp(dks, g.tF, kl, ef, bag, if (inp.bridgeOnly) rLo else REL_LO_SUP, if (inp.bridgeOnly) rHi else REL_HI_SUP, mxD)
        // Tono de la cuadrícula clara (mismo matiz que ella y poco más oscuro): ni color ni trazo tenue
        inp.gridColor?.let { gc -> gridLike(nw, gc, g.gain, bag)?.let { gl -> Core.multiply(ec, gl, ec); Core.multiply(ef, gl, ef) } }
        // Huella de la rejilla / fondo ajeno: ni tinta ni color
        for (exM in listOf(inp.exclude, inp.exclude2)) {
            if (exM == null || exM.empty()) continue
            val ex = if (exM.cols() == W && exM.rows() == H) bag.mat().also { exM.submat(outer).also { m -> bag.add(m) }.convertTo(it, CvType.CV_32F, 1.0 / 255.0) }
                else upMask(exM, exM.cols().toDouble() / W, outer, bag)
            ex.convertTo(ex, -1, -1.0, 1.0)
            Core.multiply(e, ex, e); Core.multiply(ec, ex, ec); Core.multiply(ef, ex, ef)
        }
        // Alfa del B/N y continuidad del trazo
        val aBW = bag.mat(); inp.bw.submat(outer).also { bag.add(it) }.convertTo(aBW, CvType.CV_32F, -1.0 / 255.0, 1.0)
        val ev = bag.mat()
        if (inp.bridgeOnly) e.copyTo(ev) else Core.max(e, ec, ev)
        val closeA = bag.mat(); Imgproc.morphologyEx(aBW, closeA, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, kc))
        Core.min(closeA, ev, closeA)
        val a = bag.mat(); Core.max(aBW, closeA, a)
        if (inp.bridge == 2) {
            val strong = bag.mat(); Imgproc.threshold(aBW, strong, 0.5, 1.0, Imgproc.THRESH_BINARY)
            Imgproc.dilate(strong, strong, Cv.kernel(Imgproc.MORPH_ELLIPSE, kn))
            Core.multiply(strong, e, strong); Core.max(a, strong, a)
            val sup = upMask(g.support, g.scale, outer, bag)
            Core.multiply(sup, ef, sup); Core.max(a, sup, a)
        }
        if (!inp.bridgeOnly) {
            // Histéresis: lo tenue (gris o color) pegado a un trazo firme
            val strong = bag.mat(); Imgproc.threshold(aBW, strong, 0.5, 1.0, Imgproc.THRESH_BINARY)
            Imgproc.dilate(strong, strong, Cv.kernel(Imgproc.MORPH_ELLIPSE, kn))
            Core.multiply(strong, ev, strong); Core.max(a, strong, a)
            // Trazos tenues o de color claros que el B/N perdió (zonas candidatas) y figuras enteras
            val sup = upMask(g.support, g.scale, outer, bag)
            val evs = bag.mat(); Core.max(ef, ec, evs)
            Core.multiply(sup, evs, sup); Core.max(a, sup, a)
        }
        val fig = if (inp.bridgeOnly) null else upMask(g.figures, g.scale, outer, bag)
        if (fig != null) Core.max(a, fig, a)
        Imgproc.threshold(a, a, ALPHA_MIN, 0.0, Imgproc.THRESH_TOZERO)

        if (inp.bridgeOnly) {
            // Sólo continuidad (para "Blanco y negro"): B/N = 255·(1 - alfa)
            val o = bag.mat(); a.convertTo(o, CvType.CV_8U, -255.0, 255.0)
            val dst = out.submat(inner); o.submat(crop).copyTo(dst); dst.release()
            return@use
        }
        dbgA?.let { d -> val o = bag.mat(); a.convertTo(o, CvType.CV_8U, 255.0); val dd = d.submat(inner); o.submat(crop).copyTo(dd); dd.release() }
        if (Core.countNonZero(a) == 0) return@use   // (tesela sin tinta: queda blanca)
        // El COLOR se calcula a media resolución (varía despacio dentro del trazo; el borde nítido lo da el alfa a
        // resolución completa): ~4 veces menos trabajo en la parte más cara
        val figH = if (fig != null && Core.countNonZero(fig) > 0) bag.mat().also { Imgproc.resize(fig, it, hs, 0.0, 0.0, Imgproc.INTER_AREA) } else null
        // Color del centro del trazo: media ponderada por oscuridad^3 en una ventana pequeña (el borde, mezcla de tinta
        // y papel, toma el color del núcleo: sin aura clara ni bordes lavados). Los pesos y productos se forman a
        // resolución completa y se REDUCEN (suma por áreas): el trazo fino no se diluye con el papel al bajar a media resolución
        val wgt = bag.mat(); val cw = bag.mat(); chroma.convertTo(cw, -1, 1.0 / 120.0)
        Core.max(dk, cw, wgt); Imgproc.threshold(wgt, wgt, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        Core.pow(wgt, 3.0, wgt); Core.add(wgt, Scalar(1e-5), wgt)
        val w3f = bag.mat(); Core.merge(listOf(wgt, wgt, wgt), w3f)
        val numF = bag.mat(); Core.multiply(nw, w3f, numF)
        val num = bag.mat(); Imgproc.resize(numF, num, hs, 0.0, 0.0, Imgproc.INTER_AREA)
        val w3 = bag.mat(); Imgproc.resize(w3f, w3, hs, 0.0, 0.0, Imgproc.INTER_AREA)
        numF.release(); w3f.release()
        val rh = max(1, (r + 1) / 2)
        val ks = Size((2 * rh + 1).toDouble(), (2 * rh + 1).toDouble())
        Imgproc.boxFilter(num, num, -1, ks); val den = bag.mat(); Imgproc.boxFilter(w3, den, -1, ks)
        val col = bag.mat(); Core.divide(num, den, col)
        if (figH != null) {
            // En las figuras, el color propio de cada píxel
            val f3 = bag.mat(); Core.merge(listOf(figH, figH, figH), f3)
            val d = bag.mat(); Core.subtract(nwH, col, d); Core.multiply(d, f3, d); Core.add(col, d, col)
        }
        // Densidad: la tinta tenue se refuerza como si tuviera más concentración (mismo matiz, más vivo); la oscura no cambia
        run {
            val lc = bag.mat(); Core.transform(col, lc, Mat(1, 3, CvType.CV_32F).also { bag.add(it); it.put(0, 0, 0.299 / 255, 0.587 / 255, 0.114 / 255) })
            Core.max(lc, Scalar(1e-3), lc); Core.min(lc, Scalar(1.0), lc)
            Core.log(lc, lc); lc.convertTo(lc, -1, -1.0)          // densidad óptica
            Core.max(lc, Scalar(1e-3), lc)
            val k = bag.mat(); Core.divide(OD_TARGET, lc, k)
            Imgproc.threshold(k, k, K_MAX, K_MAX, Imgproc.THRESH_TRUNC); Core.max(k, Scalar(1.0), k)
            // (sin refuerzo en figuras ni en zonas de color gruesas: un resaltador o una banda de tabla no son tinta tenue)
            val noK = figH ?: Mat.zeros(hs, CvType.CV_32F).also { bag.add(it) }
            // Zona de color maciza = el alfa cubre casi toda una ventana de ~1 altura de letra (un trazo, aunque sea
            // grueso o esté en un párrafo denso, cubre < ~50 %)
            run {
                val aH = bag.mat(); Imgproc.resize(a, aH, hs, 0.0, 0.0, Imgproc.INTER_AREA)
                val kw = Cv.odd((lhT / 2.0).roundToInt().coerceIn(3, 41))
                Imgproc.boxFilter(aH, aH, -1, Size(kw.toDouble(), kw.toDouble()))
                rampF(aH, FILL_LO, FILL_HI); Core.max(noK, aH, noK)
            }
            run { val nf = bag.mat(); noK.convertTo(nf, -1, -1.0, 1.0); Core.subtract(k, Scalar(1.0), k); Core.multiply(k, nf, k); Core.add(k, Scalar(1.0), k) }
            val k3 = bag.mat(); Core.merge(listOf(k, k, k), k3)
            Core.max(col, Scalar.all(0.255), col); Core.multiply(col, Scalar.all(1.0 / 255), col); Core.min(col, Scalar.all(1.0), col)
            Core.log(col, col); Core.multiply(col, k3, col); Core.exp(col, col); Core.multiply(col, Scalar.all(255.0), col)
        }
        // Realce: niveles (negro/blanco) iguales en los tres canales y saturación en Lab (lo casi neutro, sin dominante)
        Core.subtract(col, Scalar.all(g.black), col)
        Core.multiply(col, Scalar.all(1.0 / (g.white - g.black)), col)
        Core.min(col, Scalar.all(1.0), col); Core.max(col, Scalar.all(0.0), col)
        val lab = bag.mat(); Imgproc.cvtColor(col, lab, Imgproc.COLOR_RGB2Lab)
        val L = bag.mat(); val A = bag.mat(); val B = bag.mat()
        Core.extractChannel(lab, L, 0); Core.extractChannel(lab, A, 1); Core.extractChannel(lab, B, 2)
        val c = bag.mat(); Core.magnitude(A, B, c)
        rampF(c, NEUTRAL_LO, NEUTRAL_HI); Core.multiply(c, Scalar(SATURATION), c)
        Core.multiply(A, c, A); Core.multiply(B, c, B)
        Core.merge(listOf(L, A, B), lab)
        Imgproc.cvtColor(lab, col, Imgproc.COLOR_Lab2RGB)
        Imgproc.resize(col, col, outer.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        // Composición sobre blanco: out = 1 - a·(1 - color)
        col.convertTo(col, -1, -1.0, 1.0)
        val a3 = bag.mat(); Core.merge(listOf(a, a, a), a3)
        Core.multiply(col, a3, col)
        val o = bag.mat(); col.convertTo(o, CvType.CV_8UC3, -255.0, 255.0)
        val dst = out.submat(inner); o.submat(crop).copyTo(dst); dst.release()
    }

    /**
     * Continuidad del trazo para "Blanco y negro" (en el sitio): cierre del alfa sólo donde la foto normalizada
     * muestra tinta. [nrm] RGB normalizado del mismo tamaño que [bw].
     */
    fun bridge(bw: Mat, nrm: Mat, letterHeight: Double, strokeHalf: Double, exclude: Mat? = null, keep: List<Rect> = emptyList(), level: Int = BRIDGE_LEVEL, exclude2: Mat? = null, gridColor: DoubleArray? = null) {
        if (level <= 0) return
        val r = compose(Inputs(bw, nrm, letterHeight, strokeHalf, exclude = exclude, exclude2 = exclude2, gridColor = gridColor, bridge = level))
        try {
            // (los bloques de imagen en gris del B/N quedan como estaban)
            for (k in keep) { val c = Cv.clampRect(k, bw.cols(), bw.rows()); if (c.width > 0 && c.height > 0) { val s = r.submat(c); s.setTo(Scalar(255.0)); s.release() } }
            Core.min(bw, r, bw)
        } finally { r.release() }
    }
}
