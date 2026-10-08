package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Enderezado de hojas curvadas por MALLA DE LÍNEAS ("dewarping").
 *
 * Supuesto: en la hoja real cada línea de la tabla / cuadrícula (o cada renglón de texto, como respaldo) es
 * recta y horizontal o vertical. Sobre la imagen ya rectificada por perspectiva:
 *
 * 1. **Líneas curvas**: mapa de tinta relativo al fondo local (canal mínimo: sirve igual para tinta negra que
 *    para la cuadrícula azul clara), aperturas morfológicas con segmentos largos a -12°, 0° y 12° (tolerantes a
 *    curvatura gracias a una dilatación transversal previa), muestreo por columnas (filas para las verticales) de
 *    los centros de cada trazo y **seguimiento** con predicción de pendiente ([DewarpMath.linkColumns]); los tramos
 *    cortados por texto o celdas se **enlazan** por continuidad de posición y pendiente
 *    ([DewarpMath.mergeChains]) y cada cadena se suaviza con una regresión local robusta (descarta atípicos).
 *    Si no hay suficientes líneas se usan los **renglones de texto** (componentes de letra unidos en horizontal).
 * 2. **Modelo**: campo directo suave (u, v) = F(x, y) sobre una rejilla de control bilineal (~28 celdas en el lado
 *    largo) por mínimos cuadrados ([DewarpMath.solveField]): v constante a lo largo de cada línea horizontal, u
 *    constante a lo largo de cada vertical, energía de placa delgada (interpolación/extrapolación suave hasta los
 *    bordes), término de Cauchy-Riemann débil (sin líneas verticales la hoja gira en vez de cizallarse) y un ancla
 *    muy débil a la identidad. Las líneas que no encajan (subrayados a mano, ruido) se descartan y se vuelve a
 *    resolver. En cuadrículas REGULARES (cuaderno) el espaciado se iguala: los intervalos que la curvatura comprime
 *    junto a la espiral recuperan el paso de la zona más plana; en tablas con columnas de anchos distintos sólo se
 *    exige rectitud.
 * 3. **Seguridad**: se rechaza (imagen intacta) si hay pocas líneas, si la hoja ya es plana (las líneas ya son
 *    rectas: el enderezado normal se encarga del giro), si la corrección no deja las líneas claramente más rectas o
 *    si el campo pliega/estira en exceso (jacobiano).
 * 4. **Aplicación**: el campo se invierte en una rejilla de salida de ~8 px ([DewarpMath.invert]) y se guarda
 *    NORMALIZADO ([Model]): el mismo modelo sirve para la vista previa y el render final (coherencia de los trazos
 *    de borrado). A resolución completa se interpola por franjas y se compone con la homografía de la perspectiva:
 *    un ÚNICO remuestreo (`remap` cúbico) desde la foto original.
 */
object GridDewarp {

    /** Igualar el paso de cuadrículas regulares (desactivable en el banco de pruebas). */
    internal var regularize = true

    /** Lado largo de la imagen de estimación. */
    const val EST_SIDE = 1600

    /** Paso (px de estimación) de la rejilla inversa guardada en el modelo. */
    private const val INV_STEP = 8.0

    /**
     * Mapa inverso normalizado: para cada nodo (a, b) de una rejilla regular gw x gh sobre la SALIDA
     * (u = a/(gw-1), v = b/(gh-1)), la posición fuente normalizada (x, y) en [map] (intercalado x, y).
     */
    class Model(
        val gw: Int,
        val gh: Int,
        val map: FloatArray,
        val confidence: Double,
        val info: String,
        /**
         * Extensión de la SALIDA respecto del plano rectificado (fracciones de su ancho/alto): [0,1] = mismo lienzo.
         * Si el campo lleva contenido del borde de la hoja más allá del lienzo, éste se amplía (x0 < 0, x1 > 1...) para
         * que nada se pierda; lo que queda fuera de la hoja se rellena con el color del papel.
         */
        val x0: Double = 0.0,
        val y0: Double = 0.0,
        val x1: Double = 1.0,
        val y1: Double = 1.0,
        /**
         * Máscara de HOJA del plano rectificado ([paperW] x [paperH]; 255 hoja, 0 lo demás): en la franja ampliada
         * sólo se conserva lo que es hoja; el fondo que quedó en el recorte junto a un borde curvado (mesa, hueco
         * oscuro bajo la hoja) se pinta del color del papel.
         */
        val paper: ByteArray? = null,
        val paperW: Int = 0,
        val paperH: Int = 0,
    ) {
        /** Tamaño de la salida para un plano rectificado de [w] x [h]. */
        fun outWidth(w: Int): Int = max(1, (w * (x1 - x0)).roundToInt())
        fun outHeight(h: Int): Int = max(1, (h * (y1 - y0)).roundToInt())
    }

    /** Datos de depuración (banco de pruebas). */
    internal class Debug {
        var width = 0
        var height = 0
        var scale = 1.0
        val hLines = ArrayList<DewarpMath.LineObs>()
        val vLines = ArrayList<DewarpMath.LineObs>()
        val rejected = ArrayList<DewarpMath.LineObs>()
        var field: DewarpMath.Field? = null
        var beforeDev = 0.0
        var afterDev = 0.0
        var textRows = false
        var reason = ""
        var msLines = 0.0
        var msSolve = 0.0
        var threshold = 0.0
        var ink: Mat? = null
        var bin: Mat? = null
    }

    // =====================================================================================
    // Estimación
    // =====================================================================================

    /** Estima el modelo sobre [rgb] (RGB 8UC3 ya rectificado, cualquier tamaño). null = no corregir. */
    internal fun estimate(rgb: Mat, debug: Debug? = null): Model? = MatBag().use { bag ->
        val t0 = System.nanoTime()
        val sm = bag.mat()
        val scale = Cv.downscale(rgb, sm, EST_SIDE)
        val w = sm.cols(); val h = sm.rows()
        debug?.apply { width = w; height = h; this.scale = scale }
        if (w < 200 || h < 200) return@use null.also { debug?.reason = "imagen pequeña" }
        val ink = inkMap(sm, bag)
        val bin = bag.mat()
        val thr = inkThreshold(ink)
        Imgproc.threshold(ink, bin, thr, 255.0, Imgproc.THRESH_BINARY)
        debug?.apply { threshold = thr; this.ink = ink.clone(); this.bin = bin.clone() }
        val inkFrac = Core.countNonZero(bin).toDouble() / bin.total()
        if (inkFrac < 0.002 || inkFrac > 0.45) return@use null.also { debug?.reason = "tinta %.3f".format(inkFrac) }

        val longSide = max(w, h).toDouble()
        val hc = familyLines(bin, horizontal = true, longSide = longSide, bag = bag)
        val vc = familyLines(bin, horizontal = false, longSide = longSide, bag = bag)
        var textRows = false
        var hl = hc; val vl = vc
        // Respaldo: renglones de texto si las líneas horizontales no cubren la hoja
        if (coverage(hl, h.toDouble()) < 0.35 && coverage(vl, w.toDouble()) < 0.35) {
            val rows = textRowLines(bin, longSide, bag)
            if (rows.size >= 4) { hl = rows; textRows = true }
        }
        debug?.apply { msLines = (System.nanoTime() - t0) / 1e6; this.textRows = textRows }
        val t1 = System.nanoTime()
        val content = contentPoints(sm, bin, bag)
        val res = fitModel(w.toDouble(), h.toDouble(), hl, vl, textRows, debug, content, paperMask(sm, bag))
        debug?.msSolve = (System.nanoTime() - t1) / 1e6
        res
    }

    /**
     * Puntos de CONTENIDO junto a los bordes (px de estimación): píxeles de tinta de componentes
     * FINAS (escritura, tramos de la cuadrícula; sin núcleo grueso como el hueco oscuro entre la hoja curvada y la
     * mesa) que NO tocan el borde del recorte (lo que ya estaba cortado por el recorte, o los bordes de objetos del
     * fondo, no cuenta), en una franja del 8 % del lado junto a cada borde y rodeadas de papel (gris suavizado a
     * ~21 px >= 70 % del nivel del papel). Tríos (x, y, componente). El lienzo de salida se amplía sólo para que
     * las componentes que el campo saca del lienzo quepan ENTERAS.
     */
    internal fun contentPoints(sm: Mat, bin: Mat, bag: MatBag): FloatArray {
        val gray = bag.add(Cv.gray(sm))
        val w = gray.cols(); val h = gray.rows()
        // Núcleo grueso (>= ~9 px de estimación, más que cualquier trazo de escritura): manchas, no contenido
        val thick = bag.mat()
        Imgproc.morphologyEx(bin, thick, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_ELLIPSE, 9))
        // Sin las rectas largas (cuadrícula, renglones, líneas de tabla): unen la escritura con el borde
        val ink = bag.mat(); bin.copyTo(ink)
        val lines = bag.mat(); val len = Cv.odd(max(15, max(w, h) / 50))
        val kt = bag.mat()
        for (deg in doubleArrayOf(-10.0, -5.0, 0.0, 5.0, 10.0)) for (vertical in booleanArrayOf(false, true)) {
            val k = lineKernel(len, deg)
            if (vertical) Core.transpose(k, kt) else k.copyTo(kt)
            k.release()
            Imgproc.morphologyEx(bin, lines, Imgproc.MORPH_OPEN, kt)
            Imgproc.dilate(lines, lines, Cv.kernel(Imgproc.MORPH_RECT, 3))
            Core.subtract(ink, lines, ink)
        }
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(ink, labels, stats, cents, 8, CvType.CV_32S)
        if (nc <= 1) return FloatArray(0)
        val st = IntArray(nc * 5); stats.get(0, 0, st)
        val lab = IntArray(w * h); labels.get(0, 0, lab)
        val ok = BooleanArray(nc)
        for (c in 1 until nc) {
            val x = st[c * 5]; val y = st[c * 5 + 1]; val cw = st[c * 5 + 2]; val ch = st[c * 5 + 3]
            ok[c] = x > 2 && y > 2 && x + cw < w - 2 && y + ch < h - 2
        }
        val tb = ByteArray(w * h); thick.get(0, 0, tb)
        for (i in lab.indices) if (tb[i].toInt() != 0) ok[lab[i]] = false
        // motas (ruido, grano, restos sueltos del fondo): fuera
        for (c in 1 until nc) if (st[c * 5 + 4] < 12) ok[c] = false
        val g = gray
        val pl = Cv.percentile(Cv.histogram(g), 0.85).toDouble()
        Imgproc.blur(g, g, Size(21.0, 21.0))
        val gb = ByteArray(w * h); g.get(0, 0, gb)
        val band = max(4, (0.08 * min(w, h)).roundToInt())
        val lim = 0.7 * pl
        val out = ArrayList<Float>()
        for (y in 0 until h) {
            val nearY = y < band || y >= h - band
            var x = 0
            while (x < w) {
                if (!nearY && x == band) { x = w - band; continue }
                val i = y * w + x
                val l = lab[i]
                if (l > 0 && ok[l] && (gb[i].toInt() and 0xFF) >= lim) { out.add(x.toFloat()); out.add(y.toFloat()); out.add(l.toFloat()) }
                x++
            }
        }
        return out.toFloatArray()
    }

    /**
     * Máscara de hoja a <= 400 px: 255 = hoja (gris suavizado a ~21 px de estimación >= 70 % del nivel del papel: la
     * escritura no cuenta), 0 = lo demás. (bytes, ancho, alto)
     */
    private fun paperMask(sm: Mat, bag: MatBag): Triple<ByteArray, Int, Int> {
        val g = bag.add(Cv.gray(sm))
        val pl = Cv.percentile(Cv.histogram(g), 0.85).toDouble()
        Imgproc.blur(g, g, Size(21.0, 21.0))
        val s = bag.mat(); Cv.downscale(g, s, 400)
        Imgproc.threshold(s, s, 0.7 * pl, 255.0, Imgproc.THRESH_BINARY)
        val b = ByteArray(s.cols() * s.rows()); s.get(0, 0, b)
        return Triple(b, s.cols(), s.rows())
    }

    /** Fracción del eje transversal cubierta por líneas largas (cada línea "cubre" su vecindad). */
    private fun coverage(lines: List<DewarpMath.LineObs>, cross: Double): Double {
        if (lines.isEmpty()) return 0.0
        val bins = BooleanArray(20)
        for (l in lines) {
            val c = if (l.horizontal) l.y.average() else l.x.average()
            val b = (c / cross * bins.size).toInt().coerceIn(0, bins.size - 1)
            bins[b] = true
        }
        return bins.count { it }.toDouble() / bins.size
    }

    /**
     * Tinta relativa al fondo local en el canal mínimo (8UC1): 255·(fondo − mín)/fondo. La cuadrícula azul clara
     * (rojo bajo) y la tinta negra responden igual; la división hace la respuesta independiente de las sombras.
     */
    private fun inkMap(rgb: Mat, bag: MatBag): Mat {
        val ch = ArrayList<Mat>(3)
        Core.split(rgb, ch)
        val mn = bag.mat()
        Core.min(ch[0], ch[1], mn)
        Core.min(mn, ch[2], mn)
        for (c in ch) c.release()
        val k = Cv.odd(max(9, max(rgb.cols(), rgb.rows()) / 90))
        val bg = bag.mat()
        // Cierre (máx y luego mín): quita los trazos finos oscuros y conserva el nivel del papel
        Imgproc.morphologyEx(mn, bg, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, k))
        Imgproc.blur(bg, bg, Size(k.toDouble(), k.toDouble()))
        val mnF = bag.mat(); val bgF = bag.mat()
        mn.convertTo(mnF, CvType.CV_32F)
        bg.convertTo(bgF, CvType.CV_32F)
        val diff = bag.mat()
        Core.subtract(bgF, mnF, diff)
        Core.max(bgF, Scalar(40.0), bgF)
        Core.divide(diff, bgF, diff, 255.0)
        val ink = bag.mat()
        diff.convertTo(ink, CvType.CV_8U)
        return ink
    }

    /** Umbral de tinta: por encima del ruido del papel (la mediana del mapa es papel), entre 12 y 36. */
    private fun inkThreshold(ink: Mat): Double {
        val hist = Cv.histogram(ink)
        val p50 = Cv.percentile(hist, 0.5)
        val p75 = Cv.percentile(hist, 0.75)
        if (DewarpMath.trace) println("  tinta p50=$p50 p75=$p75 p90=${Cv.percentile(hist, 0.9)} p95=${Cv.percentile(hist, 0.95)}")
        return (p50 * 2.5 + 10.0).coerceIn(12.0, 36.0)
    }

    /**
     * Líneas de una familia sobre la máscara binaria. Las verticales se buscan sobre la traspuesta con el mismo
     * código (t = coordenada a lo largo de la línea, c = transversal).
     */
    private fun familyLines(bin: Mat, horizontal: Boolean, longSide: Double, bag: MatBag): List<DewarpMath.LineObs> {
        val src = if (horizontal) bin else bag.mat().also { Core.transpose(bin, it) }
        val w = src.cols(); val h = src.rows()
        val L = Cv.odd((longSide / 40).roundToInt().coerceIn(21, 61))
        val dy = 2
        val thick = bag.mat()
        // Engrosado transversal (tolera curvatura) y cierre de cortes cortos a lo largo (líneas punteadas por el
        // umbral en la cuadrícula clara)
        Imgproc.dilate(src, thick, Cv.kernel(Imgproc.MORPH_RECT, 3, 2 * dy + 1))
        val map = bag.mat()
        val tmp = bag.mat(); val op = bag.mat()
        for ((i, ang) in doubleArrayOf(0.0, -12.0, 12.0).withIndex()) {
            val k = lineKernel(L, ang)
            Imgproc.erode(thick, tmp, k)
            Imgproc.dilate(tmp, op, k)
            k.release()
            if (i == 0) op.copyTo(map) else Core.max(map, op, map)
        }
        val bytes = ByteArray(w * h)
        map.get(0, 0, bytes)
        val raw = ByteArray(w * h)
        src.get(0, 0, raw)
        val maxThin = max(3, (longSide / 300).roundToInt())
        val step = 2
        val maxRun = 2 * dy + max(4, (longSide / 220).roundToInt())
        val ts = ArrayList<Float>(); val cols = ArrayList<FloatArray>()
        val buf = FloatArray(h)
        var x = 0
        while (x < w) {
            var n = 0; var y = 0
            while (y < h) {
                if (bytes[y * w + x].toInt() != 0) {
                    val y0 = y
                    while (y < h && bytes[y * w + x].toInt() != 0) y++
                    if (y - y0 <= maxRun) buf[n++] = (y0 + y - 1) / 2f
                } else y++
            }
            ts.add(x.toFloat()); cols.add(buf.copyOf(n))
            x += step
        }
        val chains = DewarpMath.linkColumns(ts.toFloatArray(), cols.toTypedArray(), tol = 1.6f, maxGap = 14f, maxSlope = 0.45f)
            .filter { it.span >= 10f }
        val merged = DewarpMath.mergeChains(chains, maxBridge = (w * 0.12f).coerceAtLeast(60f), tol = 2.0f, fitLen = 50f)
        val minLen = max(60.0, w * 0.10).toFloat()
        val out = ArrayList<DewarpMath.LineObs>()
        for (c in merged) {
            if (c.span < minLen) continue
            val s = DewarpMath.robustSmooth(c, win = 28f, outTol = 1.5f, minInlier = 0.7f)?.let { DewarpMath.quadSmooth(it, 70f) } ?: continue
            if (s.span < minLen) continue
            // Pendiente global razonable (una "línea" muy inclinada no es de la malla)
            val (_, b) = DewarpMath.lineFit(s.t, s.c, 0, s.size)
            if (abs(b) > 0.35) continue
            // Una línea es tinta FINA y casi continua; el centro de un renglón de texto atraviesa trazos altos
            val thin = DewarpMath.thinInkFraction(raw, w, h, s, maxThin)
            if (DewarpMath.trace) println("    ${if (horizontal) "H" else "V"} c=%.0f largo=%.0f fina=%.2f".format(s.c[0], s.span, thin))
            if (thin < 0.65) continue
            out.add(if (horizontal) DewarpMath.LineObs(true, s.t, s.c) else DewarpMath.LineObs(false, s.c, s.t))
        }
        return out
    }

    /** Kernel con un segmento de longitud [len] a [deg] grados (8UC1). */
    private fun lineKernel(len: Int, deg: Double): Mat {
        val half = len / 2
        val dyMax = abs(tan(Math.toRadians(deg)) * half).roundToInt()
        val k = Mat.zeros(2 * dyMax + 1, len, CvType.CV_8U)
        val slope = tan(Math.toRadians(deg))
        for (i in 0 until len) {
            val yy = (dyMax + slope * (i - half)).roundToInt().coerceIn(0, 2 * dyMax)
            k.put(yy, i, byteArrayOf(1))
        }
        return k
    }

    /**
     * Respaldo sin líneas: renglones de texto. Componentes con tamaño de letra; se unen en horizontal (≈1.2 alturas
     * de letra) y se siguen los centros de cada renglón como si fueran líneas horizontales.
     */
    private fun textRowLines(bin: Mat, longSide: Double, bag: MatBag): List<DewarpMath.LineObs> {
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val n = Imgproc.connectedComponentsWithStats(bin, labels, stats, cents, 8, CvType.CV_32S)
        if (n < 20) return emptyList()
        val st = IntArray(n * 5)
        stats.get(0, 0, st)
        val hs = ArrayList<Int>()
        val maxH = (longSide / 25).toInt()
        for (i in 1 until n) {
            val hh = st[i * 5 + 3]; val ww = st[i * 5 + 2]
            if (hh in 5..maxH && ww < hh * 6) hs.add(hh)
        }
        if (hs.size < 20) return emptyList()
        hs.sort()
        val med = hs[hs.size / 2].toDouble()
        // Máscara sólo con componentes de letra
        val lut = ByteArray(n)
        for (i in 1 until n) {
            val hh = st[i * 5 + 3]; val ww = st[i * 5 + 2]
            if (hh >= med * 0.4 && hh <= med * 2.5 && ww < med * 8) lut[i] = 1
        }
        val w = bin.cols(); val h = bin.rows()
        val lab = IntArray(w * h)
        labels.get(0, 0, lab)
        val mask = ByteArray(w * h)
        for (p in lab.indices) if (lut[lab[p]].toInt() != 0) mask[p] = -1
        val m = bag.mat(); m.create(h, w, CvType.CV_8U); m.put(0, 0, mask)
        val k = Cv.odd((med * 1.3).roundToInt().coerceAtLeast(3))
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, k, 1))
        val bytes = ByteArray(w * h)
        m.get(0, 0, bytes)
        val step = 3
        val ts = ArrayList<Float>(); val cols = ArrayList<FloatArray>()
        val buf = FloatArray(h)
        val minRun = (med * 0.5).toInt(); val maxRun = (med * 2.2).toInt()
        var x = 0
        while (x < w) {
            var c = 0; var y = 0
            while (y < h) {
                if (bytes[y * w + x].toInt() != 0) {
                    val y0 = y
                    while (y < h && bytes[y * w + x].toInt() != 0) y++
                    if (y - y0 in minRun..maxRun) buf[c++] = (y0 + y - 1) / 2f
                } else y++
            }
            ts.add(x.toFloat()); cols.add(buf.copyOf(c))
            x += step
        }
        val tol = (med * 0.35).toFloat().coerceAtLeast(2f)
        val chains = DewarpMath.linkColumns(ts.toFloatArray(), cols.toTypedArray(), tol = tol, maxGap = (med * 1.5).toFloat(), maxSlope = 0.35f)
            .filter { it.span >= med * 2 }
        val merged = DewarpMath.mergeChains(chains, maxBridge = (med * 5).toFloat(), tol = tol, fitLen = (med * 6).toFloat())
        val minLen = max(med * 10, w * 0.15).toFloat()
        val centers = ArrayList<DewarpMath.Chain>()
        for (c in merged) {
            if (c.span < minLen) continue
            val s = DewarpMath.robustSmooth(c, win = (med * 3).toFloat(), outTol = (med * 0.3).toFloat().coerceAtLeast(1.5f), minInlier = 0.6f) ?: continue
            val (_, b) = DewarpMath.lineFit(s.t, s.c, 0, s.size)
            if (abs(b) > 0.3) continue
            centers.add(s)
        }
        // Línea base: el centro del renglón varía con mayúsculas/ascendentes; el pie de las letras es estable
        // (las descendentes se descartan como atípicas en la regresión robusta).
        val comps = ArrayList<FloatArray>()
        for (i in 1 until n) if (lut[i].toInt() != 0) {
            val l = st[i * 5]; val t = st[i * 5 + 1]; val ww = st[i * 5 + 2]; val hh = st[i * 5 + 3]
            comps.add(floatArrayOf(l + ww / 2f, t + hh / 2f, (t + hh).toFloat()))
        }
        val base = DewarpMath.baselines(centers, comps, (med * 0.6).toFloat())
        val out = ArrayList<DewarpMath.LineObs>()
        for (b in base) {
            if (b.size < 8 || b.span < minLen * 0.8f) continue
            val s = DewarpMath.robustSmooth(b, win = (med * 4).toFloat(), outTol = (med * 0.18).toFloat().coerceAtLeast(1.2f), minInlier = 0.5f) ?: continue
            out.add(DewarpMath.LineObs(true, s.t, s.c, weight = 0.5))
        }
        return out
    }

    /**
     * Ajuste del campo, descarte de líneas atípicas, igualado del paso en cuadrículas regulares, decisión de
     * seguridad e inversión. Coordenadas en px de la imagen de estimación ([w] x [h]).
     */
    internal fun fitModel(
        w: Double, h: Double,
        hLines: List<DewarpMath.LineObs>, vLines: List<DewarpMath.LineObs>,
        textRows: Boolean, debug: Debug?, content: FloatArray? = null, paper: Triple<ByteArray, Int, Int>? = null,
    ): Model? {
        val longSide = max(w, h)
        val cells = 28.0
        val nx = max(4, (w / longSide * cells).roundToInt() + 1)
        val ny = max(4, (h / longSide * cells).roundToInt() + 1)
        val hx = w / (nx - 1); val hy = h / (ny - 1)
        val step = 0.45 * min(hx, hy)
        var lines = (hLines + vLines).mapNotNull { DewarpMath.resample(it, step) }.toMutableList()
        val nH0 = lines.count { it.horizontal }; val nV0 = lines.size - nH0
        if (nH0 + nV0 < 3) return null.also { debug?.reason = "pocas líneas ($nH0 H, $nV0 V)" }
        // Curvatura inicial: desviación de cada línea respecto de su recta
        val wt = DewarpMath.Weights()
        var field = DewarpMath.solveField(w, h, nx, ny, lines, wt)
        // Descarte robusto (2 pasadas)
        val rejected = ArrayList<DewarpMath.LineObs>()
        repeat(2) {
            val res = lines.map { DewarpMath.lineResidual(field, it) }
            // Límite por familia (una familia puede encajar peor que la otra sin que sus líneas sean falsas)
            val lim = DoubleArray(lines.size)
            for (fam in listOf(true, false)) {
                val r = lines.indices.filter { lines[it].horizontal == fam }.map { res[it] }.sorted()
                if (r.isEmpty()) continue
                val l = max(1.5, 3.0 * r[r.size / 2])
                for (i in lines.indices) if (lines[i].horizontal == fam) lim[i] = l
            }
            // Una línea muy curvada que el campo sigue en gran parte NO es atípica (sólo le falta resolución);
            // un trazo recto que cruza la malla en otro ángulo sí (su residuo es comparable a toda su desviación).
            val bad = BooleanArray(lines.size) { res[it] > lim[it] && res[it] > 0.4 * DewarpMath.straightnessDev(lines[it]) }
            val keep = lines.indices.filter { !bad[it] }
            if (keep.size == lines.size || keep.size < 3) return@repeat
            for (i in lines.indices) if (bad[i]) rejected.add(lines[i])
            lines = keep.map { lines[it] }.toMutableList()
            field = DewarpMath.solveField(w, h, nx, ny, lines, wt)
        }
        // Recorte por muestra: tramos de una cadena que se desviaron por un trazo de escritura
        val trimmed = DewarpMath.trimSamples(field, lines, rejected)
        if (trimmed != null && trimmed.size >= 3) {
            lines = trimmed.toMutableList()
            field = DewarpMath.solveField(w, h, nx, ny, lines, wt)
        }
        // Cuadrícula regular (cuaderno): igualar el paso de cada familia
        if (!textRows && regularize) {
            val targeted = DewarpMath.regularTargets(field, lines)
            if (targeted != null) {
                lines = targeted.toMutableList()
                field = DewarpMath.solveField(w, h, nx, ny, lines, wt)
            }
        }
        val before = lines.map { DewarpMath.straightnessDev(it) }.sorted()
        val after = lines.map { DewarpMath.lineResidual(field, it) }.sorted()
        // Percentil 90: una esquina doblada afecta a pocas líneas pero debe corregirse
        val p80b = before[(before.size * 0.9).toInt().coerceAtMost(before.size - 1)]
        val p80a = after[(after.size * 0.9).toInt().coerceAtMost(after.size - 1)]
        val nH = lines.count { it.horizontal }; val nV = lines.size - nH
        debug?.apply {
            this.hLines.clear(); this.vLines.clear(); this.rejected.clear()
            lines.filter { it.horizontal }.forEach { this.hLines.add(it) }
            lines.filter { !it.horizontal }.forEach { this.vLines.add(it) }
            this.rejected.addAll(rejected)
            this.field = field; beforeDev = p80b; afterDev = p80a
        }
        val info = "H=$nH V=$nV texto=$textRows antes=%.2f después=%.2f".format(p80b, p80a)
        if (lines.size < 3) return null.also { debug?.reason = "pocas líneas tras descarte; $info" }
        // Hoja ya plana: nada que enderezar (el giro lo resuelve el enderezado normal)
        val flatLimit = 1.4 * longSide / EST_SIDE
        if (p80b < flatLimit) return null.also { debug?.reason = "plana; $info" }
        if (p80a > 0.55 * p80b || p80a > 3.0) return null.also { debug?.reason = "no mejora; $info" }
        val jac = DewarpMath.jacobianRange(field)
        if (jac.first < 0.45 || jac.second > 2.2) return null.also { debug?.reason = "jacobiano ${"%.2f..%.2f".format(jac.first, jac.second)}; $info" }
        // Lienzo de salida: el plano rectificado ampliado hasta contener (con 2 px de margen) la imagen por el campo
        // del contenido junto a los bordes: lo que el campo empuja hacia fuera no se recorta
        val rescued = if (content != null) DewarpMath.rescuedPoints(field, content, 6000) else FloatArray(0)
        val ext = DewarpMath.bounds(rescued, w, h)
        val tol = 0.5; val margin = 2.0
        val u0 = if (ext[0] < -tol) ext[0] - margin else 0.0
        val v0 = if (ext[1] < -tol) ext[1] - margin else 0.0
        val u1 = if (ext[2] > w + tol) ext[2] + margin else w
        val v1 = if (ext[3] > h + tol) ext[3] + margin else h
        // Inversión en rejilla de salida
        val gw = max(2, ceil((u1 - u0) / INV_STEP).toInt() + 1)
        val gh = max(2, ceil((v1 - v0) / INV_STEP).toInt() + 1)
        val inv = DewarpMath.invert(field, gw, gh, u0, v0, u1, v1) ?: return null.also { debug?.reason = "inversión; $info" }
        val map = FloatArray(gw * gh * 2)
        for (i in 0 until gw * gh) {
            map[2 * i] = (inv[2 * i] / (w - 1)).toFloat()
            map[2 * i + 1] = (inv[2 * i + 1] / (h - 1)).toFloat()
        }
        val cov = min(1.0, (nH + nV) / 12.0)
        val conf = (cov * (1.0 - p80a / max(1e-6, p80b))).coerceIn(0.0, 1.0)
        val extInfo = if (u0 < 0 || v0 < 0 || u1 > w || v1 > h) " lienzo %.1f,%.1f..%.1f,%.1f".format(u0, v0, u1 - w, v1 - h) else ""
        debug?.reason = "aplicado; $info$extInfo"
        return Model(gw, gh, map, conf, info + extInfo, u0 / w, v0 / h, u1 / w, v1 / h, paper?.first, paper?.second ?: 0, paper?.third ?: 0)
    }

    // =====================================================================================
    // Aplicación
    // =====================================================================================

    /** Aplica [model] a [src] (cualquier tipo/canales); salida de [Model.outWidth] x [Model.outHeight]. */
    fun apply(src: Mat, model: Model, interp: Int = Imgproc.INTER_CUBIC): Mat =
        remapComposed(src, model, src.cols(), src.rows(), null, interp)

    /**
     * Remuestreo por franjas: para cada píxel de salida se interpola la posición en el plano rectificado
     * ([outW] x [outH]) y, si hay [hinv] (3x3, rectificado -> [src]), se lleva a la foto original: perspectiva +
     * rotación + enderezado curvo en un único `remap`. La salida mide [Model.outWidth] x [Model.outHeight] (lienzo
     * ampliado si el campo saca contenido del plano); lo que cae fuera de la hoja se pinta del color del papel.
     */
    internal fun remapComposed(src: Mat, model: Model, outW0: Int, outH0: Int, hinv: DoubleArray?, interp: Int): Mat {
        val outW = model.outWidth(outW0); val outH = model.outHeight(outH0)
        val coarse = Mat(model.gh, model.gw, CvType.CV_32FC2)
        val sx = (outW0 - 1).toFloat(); val sy = (outH0 - 1).toFloat()
        val buf = FloatArray(model.map.size)
        for (i in 0 until model.gw * model.gh) {
            buf[2 * i] = model.map[2 * i] * sx
            buf[2 * i + 1] = model.map[2 * i + 1] * sy
        }
        coarse.put(0, 0, buf)
        val out = Mat(outH, outW, src.type())
        val hm = hinv?.let { Mat(3, 3, CvType.CV_64F).apply { put(0, 0, *it) } }
        val strip = Mat(); val strip2 = Mat(); val aff = Mat(2, 3, CvType.CV_64F)
        val expanded = model.x0 < 0 || model.y0 < 0 || model.x1 > 1 || model.y1 > 1
        // Lo que cae fuera del plano rectificado (fuera de la hoja recortada: con la perspectiva sería la mesa de la
        // foto) se pinta del color del papel, se amplíe o no el lienzo
        val outside = Mat(outH, outW, CvType.CV_8UC1, Scalar(0.0))
        // fondo (no hoja) de la franja ampliada según la máscara del modelo; sin máscara, toda la franja es hoja
        val zone = if (expanded) Mat(outH, outW, CvType.CV_8UC1, Scalar(0.0)) else null
        val pm = if (expanded && model.paper != null) Mat(model.paperH, model.paperW, CvType.CV_8UC1).apply { put(0, 0, model.paper) } else null
        val pmap = Mat(); val pz = Mat()
        val inside = Mat()
        try {
            val rowsPer = max(16, 1_000_000 / max(1, outW))
            val kx = (model.gw - 1).toDouble() / max(1, outW - 1)
            val ky = (model.gh - 1).toDouble() / max(1, outH - 1)
            var y0 = 0
            while (y0 < outH) {
                val rows = min(rowsPer, outH - y0)
                aff.put(0, 0, kx, 0.0, 0.0, 0.0, ky, y0 * ky)
                Imgproc.warpAffine(
                    coarse, strip, aff, Size(outW.toDouble(), rows.toDouble()),
                    Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE,
                )
                run {
                    // Fuera del plano rectificado (= fuera de la hoja recortada)
                    Core.inRange(strip, Scalar(-0.5, -0.5), Scalar(outW0 - 0.5, outH0 - 0.5), inside)
                    val o = outside.submat(y0, y0 + rows, 0, outW)
                    Core.bitwise_not(inside, o); o.release()
                    if (pm != null) {
                        Core.multiply(strip, Scalar((pm.cols() - 1.0) / max(1, outW0 - 1), (pm.rows() - 1.0) / max(1, outH0 - 1)), pmap)
                        Imgproc.remap(pm, pz, pmap, Mat(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
                        val z = zone!!.submat(y0, y0 + rows, 0, outW)
                        Imgproc.threshold(pz, z, 127.0, 255.0, Imgproc.THRESH_BINARY_INV); z.release()
                    }
                }
                val m = if (hm != null) { Core.perspectiveTransform(strip, strip2, hm); strip2 } else strip
                val dst = out.submat(y0, y0 + rows, 0, outW)
                Imgproc.remap(src, dst, m, Mat(), interp, Core.BORDER_REPLICATE)
                dst.release()
                y0 += rows
            }
            if (Core.countNonZero(outside) > 0) {
                // Margen de 1 px (la interpolación del borde trae algo de lo que hay fuera de la hoja)
                Imgproc.dilate(outside, outside, Cv.kernel(Imgproc.MORPH_RECT, 3))
            }
            if (zone != null) {
                // Franja ampliada (fuera del lienzo original): sólo lo que es hoja (ver [Model.paper]); la máscara de
                // fondo [zone] se fue acumulando por franjas
                val kx = (outW - 1) / max(1e-9, model.x1 - model.x0); val ky = (outH - 1) / max(1e-9, model.y1 - model.y0)
                val cx0 = (-model.x0 * kx).roundToInt(); val cy0 = (-model.y0 * ky).roundToInt()
                val cx1 = ((1 - model.x0) * kx).roundToInt(); val cy1 = ((1 - model.y0) * ky).roundToInt()
                Imgproc.rectangle(zone, Point(cx0.toDouble(), cy0.toDouble()), Point(cx1.toDouble(), cy1.toDouble()), Scalar(0.0), -1)
                Core.bitwise_or(outside, zone, outside)
            }
            if (Core.countNonZero(outside) > 0) out.setTo(Cv.paperColor(out), outside)
        } catch (t: Throwable) {
            out.release(); throw t
        } finally {
            coarse.release(); strip.release(); strip2.release(); aff.release(); hm?.release(); outside.release(); inside.release(); zone?.release(); pm?.release(); pmap.release(); pz.release()
        }
        return out
    }

    /** Dibuja las líneas detectadas (banco de pruebas): horizontales rojo, verticales azul, descartadas amarillo. */
    internal fun drawDebug(img: Mat, d: Debug) {
        val s = img.cols().toDouble() / max(1, d.width)
        val th = max(1, (img.cols() / 600.0).roundToInt())
        fun draw(l: DewarpMath.LineObs, col: Scalar) {
            for (k in 0 until l.size - 1) {
                Imgproc.line(img, Point(l.x[k] * s, l.y[k] * s), Point(l.x[k + 1] * s, l.y[k + 1] * s), col, th)
            }
        }
        for (l in d.rejected) draw(l, Scalar(255.0, 200.0, 0.0))
        for (l in d.hLines) draw(l, Scalar(230.0, 0.0, 0.0))
        for (l in d.vLines) draw(l, Scalar(0.0, 60.0, 255.0))
    }
}

/**
 * Lógica pura del enderezado (sin OpenCV; probada en JVM): seguimiento y enlace de líneas, suavizado robusto,
 * ajuste del campo por mínimos cuadrados en banda, inversión y métricas.
 */
internal object DewarpMath {
    var trace = false

    /** Cadena de puntos ordenada por t (coordenada a lo largo de la línea); c = coordenada transversal. */
    class Chain(val t: FloatArray, val c: FloatArray) {
        val size get() = t.size
        val t0 get() = t[0]
        val t1 get() = t[t.size - 1]
        val span get() = t1 - t0
    }

    /** Línea observada en coordenadas de imagen. [target] = posición transversal impuesta en la salida (o NaN). */
    class LineObs(
        val horizontal: Boolean,
        val x: FloatArray,
        val y: FloatArray,
        val weight: Double = 1.0,
        val target: Double = Double.NaN,
    ) {
        val size get() = x.size
        fun along(i: Int) = if (horizontal) x[i] else y[i]
        fun cross(i: Int) = if (horizontal) y[i] else x[i]
        fun withTarget(t: Double) = LineObs(horizontal, x, y, weight, t)
    }

    private class Builder {
        var t = FloatArray(32); var c = FloatArray(32); var n = 0
        var slope = 0f
        fun add(tt: Float, cc: Float) {
            if (n == t.size) { t = t.copyOf(n * 2); c = c.copyOf(n * 2) }
            t[n] = tt; c[n] = cc; n++
        }
        val lastT get() = t[n - 1]
        val lastC get() = c[n - 1]
        fun updateSlope(win: Float, maxSlope: Float) {
            var k = n - 1
            var s0 = 0.0; var st = 0.0; var sc = 0.0; var stt = 0.0; var stc = 0.0
            val tl = lastT
            while (k >= 0 && tl - t[k] <= win && n - 1 - k < 40) {
                val tt = (t[k] - tl).toDouble(); val cc = c[k].toDouble()
                s0 += 1; st += tt; sc += cc; stt += tt * tt; stc += tt * cc
                k--
            }
            if (s0 < 4) return
            val den = s0 * stt - st * st
            if (den < 1e-9) return
            slope = ((s0 * stc - st * sc) / den).toFloat().coerceIn(-maxSlope, maxSlope)
        }
        fun build() = Chain(t.copyOf(n), c.copyOf(n))
    }

    /**
     * Seguimiento de líneas a través de columnas muestreadas: [cols][k] son las posiciones transversales (ordenadas)
     * de los trazos en t = [ts][k]. Cada cadena activa predice su posición con la pendiente reciente; las
     * asignaciones se resuelven por menor distancia (cada punto a una sola cadena). Una cadena sin puntos durante
     * más de [maxGap] se cierra.
     */
    fun linkColumns(ts: FloatArray, cols: Array<FloatArray>, tol: Float, maxGap: Float, maxSlope: Float, slopeWin: Float = 30f): List<Chain> {
        val done = ArrayList<Chain>()
        var active = ArrayList<Builder>()
        val pairs = ArrayList<LongArray>()
        for (k in ts.indices) {
            val x = ts[k]
            val pts = cols[k]
            val keep = ArrayList<Builder>(active.size)
            for (b in active) if (x - b.lastT > maxGap) done.add(b.build()) else keep.add(b)
            active = keep
            // Candidatos (distancia cuantizada, cadena, punto)
            data class Cand(val d: Float, val a: Int, val p: Int)
            val cands = ArrayList<Cand>()
            for ((ai, b) in active.withIndex()) {
                val dt = x - b.lastT
                val pred = b.lastC + b.slope * dt
                val tl = tol + 0.04f * dt
                var lo = lowerBound(pts, pred - tl)
                while (lo < pts.size && pts[lo] <= pred + tl) {
                    cands.add(Cand(abs(pts[lo] - pred), ai, lo)); lo++
                }
            }
            cands.sortBy { it.d }
            val usedA = BooleanArray(active.size); val usedP = BooleanArray(pts.size)
            for (cd in cands) {
                if (usedA[cd.a] || usedP[cd.p]) continue
                usedA[cd.a] = true; usedP[cd.p] = true
                val b = active[cd.a]
                b.add(x, pts[cd.p]); b.updateSlope(slopeWin, maxSlope)
            }
            for (p in pts.indices) if (!usedP[p]) active.add(Builder().also { it.add(x, pts[p]) })
        }
        pairs.clear()
        for (b in active) done.add(b.build())
        return done
    }

    private fun lowerBound(a: FloatArray, v: Float): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    /** Recta c = a + b·t por mínimos cuadrados sobre [from, to). */
    fun lineFit(t: FloatArray, c: FloatArray, from: Int, to: Int): Pair<Double, Double> {
        var s0 = 0.0; var st = 0.0; var sc = 0.0; var stt = 0.0; var stc = 0.0
        for (i in from until to) {
            val tt = t[i].toDouble(); val cc = c[i].toDouble()
            s0 += 1; st += tt; sc += cc; stt += tt * tt; stc += tt * cc
        }
        if (s0 == 0.0) return 0.0 to 0.0
        val den = s0 * stt - st * st
        if (abs(den) < 1e-9) return sc / s0 to 0.0
        val b = (s0 * stc - st * sc) / den
        return (sc - b * st) / s0 to b
    }

    /**
     * Enlaza cadenas colineales separadas por huecos (texto que cruza la línea, celdas vacías, sombras): el final de
     * A extrapolado (recta de sus últimos [fitLen] px) debe caer sobre el inicio de B y viceversa, con tolerancia
     * [tol] + 1.5 % del hueco y pendientes parecidas. Varias pasadas voraces por menor error.
     */
    fun mergeChains(chains: List<Chain>, maxBridge: Float, tol: Float, fitLen: Float, maxSlopeDiff: Double = 0.08): List<Chain> {
        var list = chains.sortedBy { it.t0 }
        repeat(4) {
            val n = list.size
            if (n < 2) return list
            val endA = DoubleArray(n); val endB = DoubleArray(n)
            val stA = DoubleArray(n); val stB = DoubleArray(n)
            for ((i, ch) in list.withIndex()) {
                var j = ch.size - 1
                while (j > 0 && ch.t1 - ch.t[j - 1] <= fitLen) j--
                val (a1, b1) = lineFit(ch.t, ch.c, j, ch.size)
                endA[i] = a1; endB[i] = if (ch.size - j >= 3) b1 else 0.0
                var k = 0
                while (k < ch.size - 1 && ch.t[k + 1] - ch.t0 <= fitLen) k++
                val (a2, b2) = lineFit(ch.t, ch.c, 0, k + 1)
                stA[i] = a2; stB[i] = if (k + 1 >= 3) b2 else 0.0
            }
            data class Cand(val e: Double, val a: Int, val b: Int)
            val cands = ArrayList<Cand>()
            for (a in 0 until n) {
                val ca = list[a]
                for (b in 0 until n) {
                    if (a == b) continue
                    val cb = list[b]
                    if (cb.t0 > ca.t1 + maxBridge) continue
                    val gap = cb.t0 - ca.t1
                    if (gap < -6f || cb.t1 <= ca.t1 + 2f) continue
                    val g = max(0f, gap)
                    val pa = endA[a] + endB[a] * cb.t0
                    val pb = stA[b] + stB[b] * ca.t1
                    val e = max(abs(pa - cb.c[0]), abs(pb - ca.c[ca.size - 1]))
                    val allowed = tol + 0.015 * g
                    if (e > allowed) continue
                    if (abs(endB[a] - stB[b]) > maxSlopeDiff + 0.02) continue
                    cands.add(Cand(e / allowed + g / (maxBridge * 4.0), a, b))
                }
            }
            if (cands.isEmpty()) return list
            cands.sortBy { it.e }
            val succ = IntArray(n) { -1 }; val pred = IntArray(n) { -1 }
            for (cd in cands) {
                if (succ[cd.a] >= 0 || pred[cd.b] >= 0) continue
                // Evitar ciclos
                var x = cd.b; var cyc = false
                while (x >= 0) { if (x == cd.a) { cyc = true; break }; x = succ[x] }
                if (cyc) continue
                succ[cd.a] = cd.b; pred[cd.b] = cd.a
            }
            val out = ArrayList<Chain>()
            for (i in 0 until n) {
                if (pred[i] >= 0) continue
                if (succ[i] < 0) { out.add(list[i]); continue }
                val tl = ArrayList<Float>(); val cl = ArrayList<Float>()
                var x = i
                while (x >= 0) {
                    val ch = list[x]
                    val lastT = if (tl.isEmpty()) Float.NEGATIVE_INFINITY else tl[tl.size - 1]
                    for (k in 0 until ch.size) if (ch.t[k] > lastT) { tl.add(ch.t[k]); cl.add(ch.c[k]) }
                    x = succ[x]
                }
                out.add(Chain(tl.toFloatArray(), cl.toFloatArray()))
            }
            val changed = out.size != list.size
            list = out.sortedBy { it.t0 }
            if (!changed) return list
        }
        return list
    }

    /**
     * Regresión lineal local robusta (ventana ±[win]) con descarte iterativo de puntos a más de [outTol] px.
     * Devuelve los inliers con su valor suavizado, o null si quedan menos de [minInlier].
     */
    fun robustSmooth(ch: Chain, win: Float, outTol: Float, minInlier: Float): Chain? {
        val n = ch.size
        if (n < 4) return null
        val w = BooleanArray(n) { true }
        val fit = FloatArray(n)
        repeat(3) {
            var lo = 0; var hi = 0
            var s0 = 0.0; var st = 0.0; var sc = 0.0; var stt = 0.0; var stc = 0.0
            for (i in 0 until n) {
                val ti = ch.t[i]
                while (hi < n && ch.t[hi] <= ti + win) {
                    if (w[hi]) { val tt = (ch.t[hi] - ch.t0).toDouble(); val cc = ch.c[hi].toDouble(); s0 += 1; st += tt; sc += cc; stt += tt * tt; stc += tt * cc }
                    hi++
                }
                while (ch.t[lo] < ti - win) {
                    if (w[lo]) { val tt = (ch.t[lo] - ch.t0).toDouble(); val cc = ch.c[lo].toDouble(); s0 -= 1; st -= tt; sc -= cc; stt -= tt * tt; stc -= tt * cc }
                    lo++
                }
                val tq = (ti - ch.t0).toDouble()
                fit[i] = if (s0 < 2.5) ch.c[i] else {
                    val den = s0 * stt - st * st
                    if (abs(den) < 1e-6) (sc / s0).toFloat() else {
                        val b = (s0 * stc - st * sc) / den
                        ((sc - b * st) / s0 + b * tq).toFloat()
                    }
                }
            }
            for (i in 0 until n) w[i] = abs(ch.c[i] - fit[i]) <= outTol
        }
        val cnt = w.count { it }
        if (cnt < max(4, (n * minInlier).toInt())) return null
        val t = FloatArray(cnt); val c = FloatArray(cnt)
        var j = 0
        for (i in 0 until n) if (w[i]) { t[j] = ch.t[i]; c[j] = fit[i]; j++ }
        return Chain(t, c)
    }

    /**
     * Regresión CUADRÁTICA local (ventana ±[win]) sobre una cadena ya depurada: elimina el temblor que dejan las
     * letras que cruzan la línea sin aplanar la curvatura real (un ajuste lineal con ventana grande la recortaría).
     */
    fun quadSmooth(ch: Chain, win: Float): Chain {
        val n = ch.size
        if (n < 6) return ch
        val ps = Array(8) { DoubleArray(n + 1) }  // prefijos de z^0..z^4, c, c·z, c·z²
        for (i in 0 until n) {
            val z = (ch.t[i] - ch.t0) / 100.0; val c = ch.c[i].toDouble()
            val z2 = z * z
            val v = doubleArrayOf(1.0, z, z2, z2 * z, z2 * z2, c, c * z, c * z2)
            for (k in 0 until 8) ps[k][i + 1] = ps[k][i] + v[k]
        }
        val out = FloatArray(n)
        var lo = 0; var hi = 0
        val m = Array(3) { DoubleArray(4) }
        for (i in 0 until n) {
            while (hi < n && ch.t[hi] <= ch.t[i] + win) hi++
            while (ch.t[lo] < ch.t[i] - win) lo++
            fun sum(k: Int) = ps[k][hi] - ps[k][lo]
            if (hi - lo < 6) { out[i] = ch.c[i]; continue }
            for (r in 0..2) { for (q in 0..2) m[r][q] = sum(r + q); m[r][3] = sum(5 + r) }
            val sol = solveDense(m, 3)
            val z = (ch.t[i] - ch.t0) / 100.0
            val v = sol[0] + sol[1] * z + sol[2] * z * z
            out[i] = if (v.isFinite() && abs(v - ch.c[i]) < 4.0) v.toFloat() else ch.c[i]
        }
        return Chain(ch.t, out)
    }

    /**
     * Asigna cada componente de letra ([comps]: x central, y central, y inferior) al renglón [centers] más próximo
     * (|y central − centro del renglón| < [maxDist]) y devuelve, por renglón, los pies de sus letras ordenados por x.
     */
    fun baselines(centers: List<Chain>, comps: List<FloatArray>, maxDist: Float): List<Chain> {
        val pts = Array(centers.size) { ArrayList<FloatArray>() }
        for (cp in comps) {
            var best = -1; var bd = maxDist
            for ((k, ch) in centers.withIndex()) {
                if (cp[0] < ch.t0 - maxDist || cp[0] > ch.t1 + maxDist) continue
                val d = abs(cp[1] - interp(ch, cp[0]))
                if (d < bd) { bd = d; best = k }
            }
            if (best >= 0) pts[best].add(cp)
        }
        return pts.map { l ->
            l.sortBy { it[0] }
            Chain(FloatArray(l.size) { l[it][0] }, FloatArray(l.size) { l[it][2] })
        }.filter { it.size > 0 }
    }

    /** Valor de la cadena en t (interpolación lineal, constante fuera del tramo). */
    fun interp(ch: Chain, t: Float): Float {
        if (t <= ch.t0) return ch.c[0]
        if (t >= ch.t1) return ch.c[ch.size - 1]
        val i = lowerBound(ch.t, t).coerceIn(1, ch.size - 1)
        val f = (t - ch.t[i - 1]) / max(1e-6f, ch.t[i] - ch.t[i - 1])
        return ch.c[i - 1] + (ch.c[i] - ch.c[i - 1]) * f
    }

    /**
     * Fracción de puntos de la cadena (t = columna, c = fila en [bin], w x h, ≠0 = tinta) con tinta a ±1 px cuyo
     * trazo transversal mide ≤ [maxThin] px: ~1 en una línea (aunque esté punteada o la crucen otras), baja en el
     * centro de un renglón de texto.
     */
    fun thinInkFraction(bin: ByteArray, w: Int, h: Int, ch: Chain, maxThin: Int): Double {
        var n = 0; var thin = 0
        for (i in 0 until ch.size) {
            val x = ch.t[i].roundToInt()
            if (x < 0 || x >= w) continue
            n++
            val yc = ch.c[i].roundToInt()
            var y0 = -1
            for (d in intArrayOf(0, -1, 1)) {
                val y = yc + d
                if (y in 0 until h && bin[y * w + x].toInt() != 0) { y0 = y; break }
            }
            if (y0 < 0) continue
            var a = y0; var b = y0
            while (a > 0 && bin[(a - 1) * w + x].toInt() != 0 && b - a < maxThin + 1) a--
            while (b < h - 1 && bin[(b + 1) * w + x].toInt() != 0 && b - a < maxThin + 1) b++
            if (b - a + 1 <= maxThin) thin++
        }
        return if (n == 0) 0.0 else thin.toDouble() / n
    }

    /** Remuestrea una línea cada ~[step] px de longitud (puntos de restricción del campo). */
    fun resample(l: LineObs, step: Double): LineObs? {
        if (l.size < 2) return null
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        xs.add(l.x[0]); ys.add(l.y[0])
        var acc = 0.0
        for (i in 1 until l.size) {
            val seg = hypot((l.x[i] - l.x[i - 1]).toDouble(), (l.y[i] - l.y[i - 1]).toDouble())
            if (seg > step * 1.5) {
                // Hueco (tramo puenteado): puntos intermedios para que ninguna restricción salga de la banda
                val k = ceil(seg / step).toInt()
                for (q in 1 until k) {
                    val f = q.toFloat() / k
                    xs.add(l.x[i - 1] + (l.x[i] - l.x[i - 1]) * f); ys.add(l.y[i - 1] + (l.y[i] - l.y[i - 1]) * f)
                }
                xs.add(l.x[i]); ys.add(l.y[i]); acc = 0.0
                continue
            }
            acc += seg
            if (acc >= step || i == l.size - 1) {
                if (acc >= step * 0.3) { xs.add(l.x[i]); ys.add(l.y[i]) }
                acc = 0.0
            }
        }
        if (xs.size < 3) return null
        return LineObs(l.horizontal, xs.toFloatArray(), ys.toFloatArray(), l.weight, l.target)
    }

    /** Desviación máxima (px) de una línea respecto de su recta de ajuste (curvatura antes de corregir). */
    fun straightnessDev(l: LineObs): Double {
        val t = FloatArray(l.size) { l.along(it) }; val c = FloatArray(l.size) { l.cross(it) }
        val (a, b) = lineFit(t, c, 0, l.size)
        var m = 0.0
        for (i in 0 until l.size) m = max(m, abs(c[i] - (a + b * t[i])) / sqrt(1 + b * b))
        return m
    }

    /** Ajuste polinómico c ≈ Σ k_i·((t-m)/s)^i (mínimos cuadrados, ecuaciones normales con pivoteo). */
    class Poly(val mean: Double, val scale: Double, val k: DoubleArray) {
        fun eval(t: Double): Double { val z = (t - mean) / scale; var r = 0.0; for (i in k.indices.reversed()) r = r * z + k[i]; return r }
    }

    fun polyFit(t: FloatArray, c: FloatArray, deg: Int): Poly {
        val n = t.size
        val mean = t.average()
        var sc = 0.0
        for (v in t) sc = max(sc, abs(v - mean))
        if (sc < 1e-9) sc = 1.0
        val d = min(deg, max(0, n - 1))
        val m = d + 1
        val a = Array(m) { DoubleArray(m + 1) }
        for (i in 0 until n) {
            val z = (t[i] - mean) / sc
            val pw = DoubleArray(2 * m); pw[0] = 1.0
            for (p in 1 until 2 * m) pw[p] = pw[p - 1] * z
            for (r in 0 until m) { for (q in 0 until m) a[r][q] += pw[r + q]; a[r][m] += pw[r] * c[i] }
        }
        val x = solveDense(a, m)
        return Poly(mean, sc, x)
    }

    private fun solveDense(a: Array<DoubleArray>, m: Int): DoubleArray {
        for (col in 0 until m) {
            var piv = col
            for (r in col + 1 until m) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            val p = a[col][col]
            if (abs(p) < 1e-12) continue
            for (r in 0 until m) {
                if (r == col) continue
                val f = a[r][col] / p
                if (f == 0.0) continue
                for (q in col..m) a[r][q] -= f * a[col][q]
            }
        }
        return DoubleArray(m) { if (abs(a[it][it]) < 1e-12) 0.0 else a[it][m] / a[it][it] }
    }

    // ---------------------------------------------------------------------------------
    // Campo
    // ---------------------------------------------------------------------------------

    /** Pesos de la energía (unidades: px de imagen de estimación). */
    class Weights(
        val line: Double = 1.0,
        val tps: Double = 3.0,
        val cr: Double = 1e-4,
        val anchor: Double = 1e-9,
        val target: Double = 0.02,
    )

    /** Campo directo bilineal (u, v) = F(x, y) en una rejilla nx x ny sobre [0,w]x[0,h]. */
    class Field(val nx: Int, val ny: Int, val w: Double, val h: Double, val u: DoubleArray, val v: DoubleArray) {
        val hx = w / (nx - 1)
        val hy = h / (ny - 1)

        /** out = [u, v, du/dx, du/dy, dv/dx, dv/dy]; extrapola linealmente fuera de la rejilla. */
        fun eval(x: Double, y: Double, out: DoubleArray) {
            val fx = x / hx; val fy = y / hy
            val i = floor(fx).toInt().coerceIn(0, nx - 2)
            val j = floor(fy).toInt().coerceIn(0, ny - 2)
            val s = fx - i; val r = fy - j
            val n00 = j * nx + i; val n10 = n00 + 1; val n01 = n00 + nx; val n11 = n01 + 1
            val w00 = (1 - s) * (1 - r); val w10 = s * (1 - r); val w01 = (1 - s) * r; val w11 = s * r
            out[0] = w00 * u[n00] + w10 * u[n10] + w01 * u[n01] + w11 * u[n11]
            out[1] = w00 * v[n00] + w10 * v[n10] + w01 * v[n01] + w11 * v[n11]
            out[2] = ((u[n10] - u[n00]) * (1 - r) + (u[n11] - u[n01]) * r) / hx
            out[3] = ((u[n01] - u[n00]) * (1 - s) + (u[n11] - u[n10]) * s) / hy
            out[4] = ((v[n10] - v[n00]) * (1 - r) + (v[n11] - v[n01]) * r) / hx
            out[5] = ((v[n01] - v[n00]) * (1 - s) + (v[n11] - v[n10]) * s) / hy
        }

        fun value(x: Double, y: Double, comp: Int): Double {
            val o = DoubleArray(6); eval(x, y, o); return o[comp]
        }
    }

    /** Sistema simétrico definido positivo en banda (triángulo inferior, ancho [b]). */
    private class BandSys(val n: Int, val b: Int) {
        val a = DoubleArray(n * (b + 1))
        val rhs = DoubleArray(n)
        fun add(i: Int, j: Int, v: Double) {
            val d = i - j
            if (d < 0 || d > b) throw IllegalStateException("fuera de banda")
            a[i * (b + 1) + d] += v
        }
        /** Suma w·(Σ c_k·x_{idx_k} − r)². */
        fun residual(idx: IntArray, c: DoubleArray, m: Int, r: Double, w: Double) {
            for (k in 0 until m) {
                val ik = idx[k]; val ck = c[k] * w
                if (ck == 0.0) continue
                rhs[ik] += ck * r
                for (l in 0 until m) {
                    val il = idx[l]
                    if (il <= ik) add(ik, il, ck * c[l])
                }
            }
        }
        fun solve(): DoubleArray {
            val bw = b + 1
            for (i in 0 until n) {
                val j0 = max(0, i - b)
                for (j in j0..i) {
                    var s = a[i * bw + (i - j)]
                    val k0 = max(j0, j - b)
                    for (k in k0 until j) s -= a[i * bw + (i - k)] * a[j * bw + (j - k)]
                    if (i == j) a[i * bw] = sqrt(max(s, 1e-12)) else a[i * bw + (i - j)] = s / a[j * bw]
                }
            }
            val y = DoubleArray(n)
            for (i in 0 until n) {
                var s = rhs[i]
                for (k in max(0, i - b) until i) s -= a[i * bw + (i - k)] * y[k]
                y[i] = s / a[i * bw]
            }
            val x = DoubleArray(n)
            for (i in n - 1 downTo 0) {
                var s = y[i]
                for (k in i + 1..min(n - 1, i + b)) s -= a[k * bw + (k - i)] * x[k]
                x[i] = s / a[i * bw]
            }
            return x
        }
    }

    /**
     * Mínimos cuadrados del campo directo: incógnitas (u, v) por nodo intercaladas (banda ≈ 4·nx). Las muestras
     * consecutivas de cada línea deben quedar a la misma v (horizontales) o u (verticales); placa delgada en
     * ambas componentes; Cauchy-Riemann débil por celda; ancla débil a la identidad; objetivos de paso opcionales.
     */
    fun solveField(w: Double, h: Double, nx: Int, ny: Int, lines: List<LineObs>, wt: Weights): Field {
        val nn = nx * ny
        val sys = BandSys(2 * nn, 4 * nx + 7)
        val hx = w / (nx - 1); val hy = h / (ny - 1)
        val idx = IntArray(16); val c = DoubleArray(16)
        fun stencil(x: Double, y: Double, comp: Int, sign: Double, off: Int) {
            val fx = (x / hx).coerceIn(0.0, nx - 1.0); val fy = (y / hy).coerceIn(0.0, ny - 1.0)
            val i = floor(fx).toInt().coerceIn(0, nx - 2); val j = floor(fy).toInt().coerceIn(0, ny - 2)
            val s = fx - i; val r = fy - j
            val n00 = j * nx + i
            idx[off] = 2 * n00 + comp; c[off] = sign * (1 - s) * (1 - r)
            idx[off + 1] = 2 * (n00 + 1) + comp; c[off + 1] = sign * s * (1 - r)
            idx[off + 2] = 2 * (n00 + nx) + comp; c[off + 2] = sign * (1 - s) * r
            idx[off + 3] = 2 * (n00 + nx + 1) + comp; c[off + 3] = sign * s * r
        }
        // Líneas
        for (l in lines) {
            val comp = if (l.horizontal) 1 else 0
            for (k in 0 until l.size - 1) {
                val d = hypot((l.x[k + 1] - l.x[k]).toDouble(), (l.y[k + 1] - l.y[k]).toDouble())
                if (d < 1e-3) continue
                stencil(l.x[k + 1].toDouble(), l.y[k + 1].toDouble(), comp, 1.0, 0)
                stencil(l.x[k].toDouble(), l.y[k].toDouble(), comp, -1.0, 4)
                sys.residual(idx, c, 8, 0.0, wt.line * l.weight / d)
            }
            if (!l.target.isNaN()) {
                val len = (0 until l.size - 1).sumOf { hypot((l.x[it + 1] - l.x[it]).toDouble(), (l.y[it + 1] - l.y[it]).toDouble()) }
                val per = len / l.size
                for (k in 0 until l.size) {
                    stencil(l.x[k].toDouble(), l.y[k].toDouble(), comp, 1.0, 0)
                    sys.residual(idx, c, 4, l.target, wt.target * l.weight * per)
                }
            }
        }
        // Placa delgada
        val area = hx * hy
        val wxx = wt.tps * area / (hx * hx * hx * hx)
        val wyy = wt.tps * area / (hy * hy * hy * hy)
        val wxy = 2 * wt.tps * area / (hx * hx * hy * hy)
        for (comp in 0..1) {
            for (j in 0 until ny) for (i in 0 until nx) {
                val n = j * nx + i
                if (i in 1..nx - 2) {
                    idx[0] = 2 * (n - 1) + comp; c[0] = 1.0; idx[1] = 2 * n + comp; c[1] = -2.0; idx[2] = 2 * (n + 1) + comp; c[2] = 1.0
                    sys.residual(idx, c, 3, 0.0, wxx)
                }
                if (j in 1..ny - 2) {
                    idx[0] = 2 * (n - nx) + comp; c[0] = 1.0; idx[1] = 2 * n + comp; c[1] = -2.0; idx[2] = 2 * (n + nx) + comp; c[2] = 1.0
                    sys.residual(idx, c, 3, 0.0, wyy)
                }
                if (i < nx - 1 && j < ny - 1) {
                    idx[0] = 2 * n + comp; c[0] = 1.0; idx[1] = 2 * (n + 1) + comp; c[1] = -1.0
                    idx[2] = 2 * (n + nx) + comp; c[2] = -1.0; idx[3] = 2 * (n + nx + 1) + comp; c[3] = 1.0
                    sys.residual(idx, c, 4, 0.0, wxy)
                }
            }
        }
        // Cauchy-Riemann por celda: u_x − v_y = 0, u_y + v_x = 0
        for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            val n00 = j * nx + i; val n10 = n00 + 1; val n01 = n00 + nx; val n11 = n01 + 1
            val ax = 1.0 / (2 * hx); val ay = 1.0 / (2 * hy)
            // u_x - v_y
            idx[0] = 2 * n10; c[0] = ax; idx[1] = 2 * n11; c[1] = ax; idx[2] = 2 * n00; c[2] = -ax; idx[3] = 2 * n01; c[3] = -ax
            idx[4] = 2 * n01 + 1; c[4] = -ay; idx[5] = 2 * n11 + 1; c[5] = -ay; idx[6] = 2 * n00 + 1; c[6] = ay; idx[7] = 2 * n10 + 1; c[7] = ay
            sys.residual(idx, c, 8, 0.0, wt.cr * area)
            // u_y + v_x
            idx[0] = 2 * n01; c[0] = ay; idx[1] = 2 * n11; c[1] = ay; idx[2] = 2 * n00; c[2] = -ay; idx[3] = 2 * n10; c[3] = -ay
            idx[4] = 2 * n10 + 1; c[4] = ax; idx[5] = 2 * n11 + 1; c[5] = ax; idx[6] = 2 * n00 + 1; c[6] = -ax; idx[7] = 2 * n01 + 1; c[7] = -ax
            sys.residual(idx, c, 8, 0.0, wt.cr * area)
        }
        // Ancla a la identidad
        for (j in 0 until ny) for (i in 0 until nx) {
            val n = j * nx + i
            idx[0] = 2 * n; c[0] = 1.0; sys.residual(idx, c, 1, i * hx, wt.anchor * area)
            idx[0] = 2 * n + 1; c[0] = 1.0; sys.residual(idx, c, 1, j * hy, wt.anchor * area)
        }
        val x = sys.solve()
        return Field(nx, ny, w, h, DoubleArray(nn) { x[2 * it] }, DoubleArray(nn) { x[2 * it + 1] })
    }

    /** Residuo de una línea tras el campo: máx |v − media| (horizontal) o |u − media| (vertical). */
    fun lineResidual(f: Field, l: LineObs): Double {
        val comp = if (l.horizontal) 1 else 0
        val o = DoubleArray(6)
        val vals = DoubleArray(l.size) { f.eval(l.x[it].toDouble(), l.y[it].toDouble(), o); o[comp] }
        val m = vals.average()
        return vals.maxOf { abs(it - m) }
    }

    /**
     * Quita de cada línea las muestras cuyo residuo (respecto de la mediana de la línea) supera
     * máx(1.2 px, 0.35·desviación de la línea) y la parte en tramos (≥ 3 muestras). Las muestras quitadas se
     * añaden a [rejected] como tramos sueltos. null si no cambia nada.
     */
    fun trimSamples(f: Field, lines: List<LineObs>, rejected: MutableList<LineObs>?): List<LineObs>? {
        val out = ArrayList<LineObs>()
        var changed = false
        val o = DoubleArray(6)
        for (l in lines) {
            val comp = if (l.horizontal) 1 else 0
            val vals = DoubleArray(l.size) { f.eval(l.x[it].toDouble(), l.y[it].toDouble(), o); o[comp] }
            val med = vals.sorted()[vals.size / 2]
            val thr = max(1.2, 0.35 * straightnessDev(l))
            val ok = BooleanArray(l.size) { abs(vals[it] - med) <= thr }
            if (ok.all { it }) { out.add(l); continue }
            changed = true
            var i = 0
            while (i < l.size) {
                val good = ok[i]
                var j = i
                while (j < l.size && ok[j] == good) j++
                val seg = LineObs(l.horizontal, l.x.copyOfRange(i, j), l.y.copyOfRange(i, j), l.weight, l.target)
                if (good) { if (j - i >= 3) out.add(seg) } else rejected?.add(seg)
                i = j
            }
        }
        return if (changed) out else null
    }

    /**
     * Contenido que el campo saca del lienzo: de los tríos (x, y, componente) [pts], la imagen por el campo de TODOS
     * los puntos de cada componente con algún punto fuera de [0,w]x[0,h] (la letra entera, no sólo su punta).
     * x/y intercalados, a lo sumo ~[maxN] puntos.
     */
    fun rescuedPoints(f: Field, pts: FloatArray, maxN: Int): FloatArray {
        val o = DoubleArray(6)
        val n = pts.size / 3
        val mapped = FloatArray(2 * n)
        val out = HashSet<Int>()
        for (k in 0 until n) {
            f.eval(pts[3 * k].toDouble(), pts[3 * k + 1].toDouble(), o)
            mapped[2 * k] = o[0].toFloat(); mapped[2 * k + 1] = o[1].toFloat()
            if (o[0] < 0 || o[1] < 0 || o[0] > f.w || o[1] > f.h) out.add(pts[3 * k + 2].toInt())
        }
        if (out.isEmpty()) return FloatArray(0)
        val sel = ArrayList<Float>()
        for (k in 0 until n) if (pts[3 * k + 2].toInt() in out) { sel.add(mapped[2 * k]); sel.add(mapped[2 * k + 1]) }
        val m = sel.size / 2
        if (m <= maxN) return sel.toFloatArray()
        val step = m.toDouble() / maxN
        return FloatArray(2 * maxN) { k -> sel[2 * (k / 2 * step).toInt() + k % 2] }
    }

    /** Caja [uMin, vMin, uMax, vMax] de los puntos [pts] (x/y intercalados) unida a [0,w]x[0,h]. */
    fun bounds(pts: FloatArray, w: Double, h: Double): DoubleArray {
        val r = doubleArrayOf(0.0, 0.0, w, h)
        for (i in pts.indices step 2) {
            r[0] = min(r[0], pts[i].toDouble()); r[1] = min(r[1], pts[i + 1].toDouble())
            r[2] = max(r[2], pts[i].toDouble()); r[3] = max(r[3], pts[i + 1].toDouble())
        }
        return r
    }

    /** Rango [mín, máx] de u_x, v_y y del determinante del jacobiano en los centros de celda. */
    fun jacobianRange(f: Field): Pair<Double, Double> {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        val o = DoubleArray(6)
        for (j in 0 until f.ny - 1) for (i in 0 until f.nx - 1) {
            f.eval((i + 0.5) * f.hx, (j + 0.5) * f.hy, o)
            val det = o[2] * o[5] - o[3] * o[4]
            for (v in doubleArrayOf(o[2], o[5], det)) { lo = min(lo, v); hi = max(hi, v) }
        }
        return lo to hi
    }

    /**
     * Inversa del campo en una rejilla de salida gw x gh sobre [0,w]x[0,h]: para cada nodo (U, V) se resuelve
     * F(x, y) = (U, V) por Newton partiendo de la solución del vecino. Devuelve (x, y) intercalados o null si
     * no converge (campo plegado).
     */
    fun invert(f: Field, gw: Int, gh: Int, u0: Double = 0.0, v0: Double = 0.0, u1: Double = f.w, v1: Double = f.h): DoubleArray? {
        val out = DoubleArray(gw * gh * 2)
        val sx = (u1 - u0) / (gw - 1); val sy = (v1 - v0) / (gh - 1)
        val o = DoubleArray(6)
        var bad = 0
        for (b in 0 until gh) {
            for (a in 0 until gw) {
                val U = u0 + a * sx; val V = v0 + b * sy
                var x: Double; var y: Double
                when {
                    a > 0 -> { x = out[2 * (b * gw + a - 1)] + sx; y = out[2 * (b * gw + a - 1) + 1] }
                    b > 0 -> { x = out[2 * ((b - 1) * gw)]; y = out[2 * ((b - 1) * gw) + 1] + sy }
                    else -> { x = U; y = V }
                }
                var ok = false
                for (it in 0 until 20) {
                    f.eval(x, y, o)
                    val ru = o[0] - U; val rv = o[1] - V
                    if (abs(ru) < 1e-3 && abs(rv) < 1e-3) { ok = true; break }
                    val det = o[2] * o[5] - o[3] * o[4]
                    if (abs(det) < 1e-6) break
                    var dx = (o[5] * ru - o[3] * rv) / det
                    var dy = (-o[4] * ru + o[2] * rv) / det
                    val m = max(abs(dx), abs(dy))
                    val lim = max(f.hx, f.hy) * 0.5
                    if (m > lim) { dx *= lim / m; dy *= lim / m }
                    x -= dx; y -= dy
                }
                if (!ok) {
                    f.eval(x, y, o)
                    if (abs(o[0] - U) > 0.05 || abs(o[1] - V) > 0.05) bad++
                }
                out[2 * (b * gw + a)] = x; out[2 * (b * gw + a) + 1] = y
            }
        }
        return if (bad > gw * gh / 200) null else out
    }

    /**
     * Cuadrícula REGULAR (cuaderno): agrupa las líneas de cada familia por su posición en la salida y, si forman
     * corridas de paso casi constante (≥ 6 líneas, cada intervalo 0.7..1.4 veces el anterior, admite huecos de
     * una línea), impone a cada línea la posición ancla + k·paso, con el paso de la zona más plana (intervalos
     * mayores). Las tablas con columnas de anchos distintos no forman corridas y quedan como están.
     */
    fun regularTargets(f: Field, lines: List<LineObs>): List<LineObs>? {
        val out = lines.toMutableList()
        var any = false
        for (horiz in listOf(true, false)) {
            val comp = if (horiz) 1 else 0
            val o = DoubleArray(6)
            val famAll = lines.indices.filter { lines[it].horizontal == horiz }
            if (famAll.size < 6) continue
            for (famC in overlapClusters(lines, famAll)) {
            val pos = famC.map { i -> val l = lines[i]; i to (0 until l.size).map { f.eval(l.x[it].toDouble(), l.y[it].toDouble(), o); o[comp] }.average() }
                .sortedBy { it.second }
            // Tramos de la misma línea (misma posición en la salida) se agrupan
            val all = ArrayList<MutableList<Pair<Int, Double>>>()
            for (p in pos) {
                if (all.isNotEmpty() && abs(all.last().last().second - p.second) < 3.0) all.last().add(p) else all.add(mutableListOf(p))
            }
            // Sólo grupos largos: las líneas de la cuadrícula cruzan la página (aunque estén cortadas por la
            // escritura); los trazos de escritura sueltos no
            val cover = all.map { g -> g.sumOf { lineSpan(lines[it.first]) } }
            val maxCover = cover.maxOrNull() ?: 0.0
            val groups = all.filterIndexed { k, _ -> cover[k] >= 0.5 * maxCover }
            val gpos = groups.map { g -> g.map { it.second }.average() }
            if (gpos.size < 6) continue
            if (trace) println("  familia ${if (horiz) "H" else "V"}: ${famC.size} líneas, grupos largos ${groups.size}, pos=${gpos.map { it.roundToInt() }}")
            for (run in gridRuns(gpos)) {
                val per = ArrayList<Double>()
                for (q in 1 until run.idx.size) per.add((gpos[run.idx[q]] - gpos[run.idx[q - 1]]) / (run.k[q] - run.k[q - 1]))
                per.sort()
                val spread = per[per.size - 1] / max(1e-6, per[0])
                if (spread >= 2.0) continue
                // Paso uniforme que CONSERVA la extensión de la corrida (primera y última línea en su sitio): las
                // zonas comprimidas por la curvatura se abren y las planas ceden un poco, sin empujar el contenido
                // fuera de la imagen.
                val first = gpos[run.idx[0]]; val last = gpos[run.idx[run.idx.size - 1]]
                val p = (last - first) / (run.k[run.k.size - 1] - run.k[0])
                for (q in run.idx.indices) {
                    val tgt = first + (run.k[q] - run.k[0]) * p
                    for ((li, _) in groups[run.idx[q]]) out[li] = lines[li].withTarget(tgt)
                }
                any = true
                if (trace) println("  regular ${if (horiz) "H" else "V"}: líneas ${run.idx.size} paso %.2f mediana %.2f mín %.2f k=${run.k.toList()}".format(p, per[per.size / 2], per[0]))
            }
            }
        }
        return if (any) out else null
    }

    /** Corrida de una cuadrícula regular: índices (en la lista de posiciones) y número de paso de cada uno. */
    class Run(val idx: IntArray, val k: IntArray)

    /**
     * Corridas de paso casi constante en posiciones ordenadas [pos]: cada intervalo 0.84..1.19 veces el anterior
     * (la compresión por curvatura es gradual), huecos de 2..5 pasos casi enteros (líneas perdidas) y hasta dos
     * líneas espurias saltadas (trazos de escritura). Sólo corridas de ≥ 7 líneas.
     */
    fun gridRuns(pos: List<Double>): List<Run> {
        val n = pos.size
        if (n < 7) return emptyList()
        val gaps = (1 until n).map { pos[it] - pos[it - 1] }.sorted()
        val med = gaps[gaps.size / 2]
        if (med < 4.0) return emptyList()
        val runs = ArrayList<Run>()
        val used = BooleanArray(n)
        var start = 0
        while (start < n - 1) {
            if (used[start]) { start++; continue }
            val idx = arrayListOf(start); val ks = arrayListOf(0)
            var prev = -1.0
            var cur = start
            while (true) {
                var nextQ = -1; var nextSteps = 0
                // Entre las 3 siguientes, la que deja el hueco más cercano a un número entero de pasos
                var bestErr = Double.MAX_VALUE
                for (q in cur + 1..min(n - 1, cur + 3)) {
                    val g = pos[q] - pos[cur]
                    val ref = if (prev < 0) med else prev
                    val r = g / ref
                    val rs = r.roundToInt()
                    val steps = when {
                        prev < 0 -> if (r in 0.6..1.5) 1 else 0
                        r in 0.84..1.19 -> 1
                        rs in 2..8 && abs(r - rs) <= min(0.1 * rs, 0.45) -> rs
                        else -> 0
                    }
                    if (steps == 0) continue
                    val err = abs(r / steps - 1.0) + 0.02 * (q - cur - 1)
                    if (err < bestErr) { bestErr = err; nextQ = q; nextSteps = steps }
                }
                if (nextQ < 0) break
                prev = (pos[nextQ] - pos[cur]) / nextSteps
                idx.add(nextQ); ks.add(ks.last() + nextSteps)
                cur = nextQ
            }
            if (idx.size >= 7) {
                runs.add(Run(idx.toIntArray(), ks.toIntArray()))
                for (i in idx) used[i] = true
                start = idx.last()
            } else start++
        }
        return runs
    }

    /** Longitud del tramo de una línea a lo largo de su dirección. */
    fun lineSpan(l: LineObs): Double {
        var a = Double.MAX_VALUE; var b = -Double.MAX_VALUE
        for (q in 0 until l.size) { a = min(a, l.along(q).toDouble()); b = max(b, l.along(q).toDouble()) }
        return b - a
    }

    /**
     * Agrupa las líneas [idx] de una familia por solape de su tramo a lo largo (≥ 50 % del más corto): las dos
     * páginas de un cuaderno abierto forman grupos distintos y sus renglones no se mezclan.
     */
    fun overlapClusters(lines: List<LineObs>, idx: List<Int>): List<List<Int>> {
        val n = idx.size
        val par = IntArray(n) { it }
        fun find(a: Int): Int { var x = a; while (par[x] != x) { par[x] = par[par[x]]; x = par[x] }; return x }
        val lo = DoubleArray(n); val hi = DoubleArray(n)
        for ((k, i) in idx.withIndex()) {
            val l = lines[i]
            var a = Double.MAX_VALUE; var b = -Double.MAX_VALUE
            for (q in 0 until l.size) { a = min(a, l.along(q).toDouble()); b = max(b, l.along(q).toDouble()) }
            lo[k] = a; hi[k] = b
        }
        for (a in 0 until n) for (b in a + 1 until n) {
            val ov = min(hi[a], hi[b]) - max(lo[a], lo[b])
            if (ov > 0.5 * min(hi[a] - lo[a], hi[b] - lo[b])) par[find(a)] = find(b)
        }
        return (0 until n).groupBy { find(it) }.values.map { g -> g.map { idx[it] } }
    }
}
