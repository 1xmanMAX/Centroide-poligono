package com.scannerpromax.imaging

import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detección de una tarjeta ID-1 (DNI, carné, tarjeta) POR SU COLOR: el color dominante de la tarjeta se estima en el
 * centro (lo que hay en el centro y NO en el borde de la foto) y se convierte en máscara; de esa máscara sólo cuenta el
 * contorno EXTERIOR (todo lo de dentro que no tiene el color —texto, foto, holograma, chip, banda MRZ— se rellena) y
 * los 4 lados se ajustan como rectas robustas (las esquinas redondeadas ISO se extrapolan).
 *
 * Priorizado para el DNI peruano (RENIEC): azul/celeste (DNI azul y DNIe de policarbonato) y amarillo (DNI de menores),
 * pero la estimación es por datos: vale para cualquier color de tarjeta distinto del fondo.
 *
 * Si la máscara pierde una franja que toca un canto (banda clara de la MRZ en el reverso, reflejo en un borde) la
 * proporción ID-1 (85,6 x 54 mm) y los otros 3 lados recuperan el lado perdido.
 *
 * Puro OpenCV (sin Bitmap): testeable en la JVM. Coordenadas en píxeles de la imagen recibida.
 */
object CardColorDetector {

    /** Proporción ISO/IEC 7810 ID-1. */
    const val ID1_RATIO = 85.6 / 54.0
    private const val WORK_SIDE = 1000
    /** Peso de la luminancia en la distancia de color: tolera sombras y la trama más oscura (guilloche). */
    private const val WL = 0.4

    /** Sólo tests/banco: recibe pasos intermedios (nombre, Mat) y el motivo de un descarte. */
    @Volatile internal var debug: ((String, Any) -> Unit)? = null
    private fun dbg(name: String, v: Any) { debug?.invoke(name, v) }

    class Result(
        /** 8 valores: TL, TR, BR, BL (x, y) en píxeles de la imagen de entrada. */
        val quad: DoubleArray,
        val confidence: Float,
        /** Color de la tarjeta en Lab (L 0..100, a, b). */
        val lab: DoubleArray,
        /** Lado recuperado con la proporción ID-1 (0 arriba, 1 derecha, 2 abajo, 3 izquierda) o -1. */
        val recoveredSide: Int,
        val threshold: Double,
    ) {
        fun toQuad(): Quad = Quad(
            Pt(quad[0].toFloat(), quad[1].toFloat()), Pt(quad[2].toFloat(), quad[3].toFloat()),
            Pt(quad[4].toFloat(), quad[5].toFloat()), Pt(quad[6].toFloat(), quad[7].toFloat()),
        )
    }

    /**
     * [rgb] 8UC3 (RGB). [guide] = marco guía normalizado (izq, arriba, der, abajo) 0..1 o null (centro de la imagen).
     * [refine] = afinar los lados a resolución completa. null si no hay una tarjeta de color fiable.
     */
    fun detect(rgb: Mat, guide: DoubleArray? = null, refine: Boolean = true): Result? {
        if (rgb.empty() || rgb.channels() != 3 || rgb.cols() < 64 || rgb.rows() < 64) return null
        val bag = MatBag()
        try {
            val fullW = rgb.cols(); val fullH = rgb.rows()
            val small = bag.mat(); Cv.downscale(rgb, small, WORK_SIDE)
            val w = small.cols(); val h = small.rows()
            val sx = w.toDouble() / fullW; val sy = h.toDouble() / fullH
            val blurred = bag.mat(); Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)
            val lab = bag.mat(); toLab(blurred, lab)
            val labArr = FloatArray(w * h * 3); lab.get(0, 0, labArr)

            val center = centerRect(w, h, guide)
            val color = estimateColor(labArr, w, h, center) ?: return null.also { dbg("motivo", "sin color") }
            val (l0, a0, b0, spread) = color
            // distancias del fondo (anillo exterior de la foto) al color elegido
            val border = borderSamples(labArr, w, h)
            val bd = DoubleArray(border.size / 3) { i -> dist(border[i * 3], border[i * 3 + 1], border[i * 3 + 2], l0, a0, b0) }
            bd.sort()
            val p15 = if (bd.isEmpty()) 99.0 else bd[(bd.size * 0.15).toInt().coerceAtMost(bd.size - 1)]
            var t = (3.0 * spread + 4.0).coerceIn(9.0, 30.0)
            t = min(t, max(7.0, 0.8 * p15))

            dbg("color", "L=%.1f a=%.1f b=%.1f spread=%.1f t=%.1f p15=%.1f".format(l0, a0, b0, spread, t, p15))
            val mask = bag.mat(); colorMask(lab, l0, a0, b0, t, mask, bag)
            dbg("mask0", mask)
            val long = max(w, h)
            morph(mask, Imgproc.MORPH_CLOSE, (long * 0.018).roundToInt())
            morph(mask, Imgproc.MORPH_OPEN, (long * 0.006).roundToInt())
            if (!pickAndFill(mask, center, bag)) return null.also { dbg("motivo", "sin componente 1") }
            morph(mask, Imgproc.MORPH_OPEN, (long * 0.015).roundToInt())
            if (!pickAndFill(mask, center, bag)) return null.also { dbg("motivo", "sin componente 2") }
            dbg("mask", mask)
            val contour = externalContour(mask) ?: return null.also { dbg("motivo", "sin contorno") }

            val coarse = fitQuad(contour, w, h) ?: return null.also { dbg("motivo", "sin quad") }
            dbg("coarse", coarse)
            if (!convex(coarse)) return null.also { dbg("motivo", "no convexo") }
            val quadArea = polyArea(coarse)
            val areaFrac = quadArea / (w.toDouble() * h)
            if (areaFrac < 0.015 || areaFrac > 0.97) return null.also { dbg("motivo", "área $areaFrac") }
            val qm = bag.add(polyMask(w, h, coarse))
            val maskArea = Core.countNonZero(mask).toDouble()
            val inter = bag.mat(); Core.bitwise_and(mask, qm, inter)
            val interA = Core.countNonZero(inter).toDouble()
            val fill = interA / max(1.0, quadArea)
            val spill = (maskArea - interA) / max(1.0, maskArea)

            // Lado perdido (banda MRZ clara, reflejo en un canto): proporción ID-1 + los otros 3 lados
            val gray = bag.mat(); Imgproc.cvtColor(blurred, gray, Imgproc.COLOR_RGB2GRAY)
            var quad = coarse
            var recovered = -1
            var snapped = false
            val r0 = ratioOf(quad, w, h)
            // (más del 20 %: la perspectiva sola rara vez desvía tanto; la banda MRZ quita ~30 % del alto)
            if (r0 / ID1_RATIO > 1.2 || ID1_RATIO / r0 > 1.2) {
                val rec = recoverSide(quad, w, h, gray, r0, labArr, palette(border), l0)
                if (rec != null) { quad = rec.q; recovered = rec.side; snapped = rec.snapped }
            }
            val r1 = ratioOf(quad, w, h)
            val ratioErr = abs(r1 / ID1_RATIO - 1.0)
            var conf = clip01((fill - 0.55) / 0.35) * clip01(1.0 - 3.0 * spill) * clip01(1.0 - ratioErr / 0.3) *
                clip01(0.55 + p15 / (3.0 * t))
            if (recovered >= 0) conf *= if (snapped) 0.92 else 0.8
            dbg("conf", "fill=%.3f spill=%.3f r0=%.3f r1=%.3f rec=%d conf=%.2f".format(fill, spill, r0, r1, recovered, conf))

            // a resolución completa
            val full = DoubleArray(8) { i -> if (i % 2 == 0) quad[i] / sx else quad[i] / sy }
            val out = if (refine) refineFull(rgb, full, l0, a0, b0, t, 1.0 / min(sx, sy), graySide = if (snapped) recovered else -1, skipSide = if (snapped) -1 else recovered) ?: full else full
            for (i in 0 until 4) {
                out[i * 2] = out[i * 2].coerceIn(0.0, fullW.toDouble()); out[i * 2 + 1] = out[i * 2 + 1].coerceIn(0.0, fullH.toDouble())
            }
            return Result(out, conf.toFloat(), doubleArrayOf(l0, a0, b0), recovered, t)
        } catch (t: Throwable) {
            dbg("motivo", t)
            return null
        } finally {
            bag.close()
        }
    }

    /**
     * Apoyo de bordes de un cuadrilátero [q] (píxeles de [rgb]): fracción de puntos de sus 4 lados (sin las esquinas)
     * sobre un canto real de la foto (gradiente de gris normal al lado), 0..1; 0,7 × media + 0,3 × el lado más débil.
     * Sirve de árbitro entre el detector por color y el de documentos.
     */
    fun edgeSupport(rgb: Mat, q: DoubleArray): Double = MatBag().use { bag ->
        val small = bag.mat(); val s = Cv.downscale(rgb, small, WORK_SIDE)
        val gray = bag.mat(); Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
        val w = gray.cols(); val h = gray.rows()
        val gx = bag.mat(); val gy = bag.mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3); Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
        val ga = FloatArray(w * h); gx.get(0, 0, ga); val gb = FloatArray(w * h); gy.get(0, 0, gb)
        val p = DoubleArray(8) { q[it] * s }
        val fr = DoubleArray(4)
        for (i in 0 until 4) {
            val ax = p[i * 2]; val ay = p[i * 2 + 1]; val bx = p[((i + 1) % 4) * 2]; val by = p[((i + 1) % 4) * 2 + 1]
            val len = max(1e-6, hypot(bx - ax, by - ay)); val nx = -(by - ay) / len; val ny = (bx - ax) / len
            var ok = 0; var n = 0
            for (j in 0 until 40) {
                val t = 0.08 + 0.84 * j / 39.0
                val px = ax + (bx - ax) * t; val py = ay + (by - ay) * t
                var best = 0.0; var inside = false
                for (d in -3..3) {
                    val x = (px + nx * d).roundToInt(); val y = (py + ny * d).roundToInt()
                    if (x !in 0 until w || y !in 0 until h) continue
                    inside = true
                    val k = y * w + x
                    best = max(best, abs(ga[k] * nx + gb[k] * ny))
                }
                if (!inside) continue   // fuera de la foto: no cuenta
                n++
                if (best > 40.0) ok++
            }
            fr[i] = if (n == 0) 0.0 else ok.toDouble() / n
        }
        0.7 * fr.average() + 0.3 * fr.min()
    }

    /**
     * Elige el recorte de la tarjeta entre el detector por color [color] y el de documentos [edges] (o null): el de más
     * apoyo de bordes ([edgeSupport]) y proporción ID-1; el de color, más preciso en el canto, gana los empates (más
     * cuanto mayor su confianza). null si ninguno vale.
     */
    fun choose(rgb: Mat, color: Result?, edges: DoubleArray?, minColorConf: Float = 0.5f): DoubleArray? {
        val c = color?.takeIf { it.confidence >= 0.3f }
        if (c == null) return edges
        if (edges == null) return c.quad
        val sc = edgeSupport(rgb, c.quad) - ratioPenalty(c.quad, rgb.cols(), rgb.rows()) + if (c.confidence >= minColorConf) 0.08 + 0.3 * (c.confidence - minColorConf) / (1f - minColorConf) else 0.0
        val se = edgeSupport(rgb, edges) - ratioPenalty(edges, rgb.cols(), rgb.rows())
        dbg("elegir", "color=%.2f (conf %.2f) bordes=%.2f".format(sc, c.confidence, se))
        return if (sc >= se) c.quad else edges
    }

    /** Penalización por no parecer ID-1 (más del 12 % de desvío de la proporción): la tarjeta lo es. */
    private fun ratioPenalty(q: DoubleArray, w: Int, h: Int): Double =
        0.6 * max(0.0, abs(ratioOf(q, w, h) / ID1_RATIO - 1.0) - 0.12)

    /**
     * Rectifica la tarjeta [q] (TL, TR, BR, BL en píxeles de [src]) a la proporción EXACTA ID-1, con el lado largo de
     * [longSide] px; apaisada o vertical según cómo esté la tarjeta en la foto. Devuelve un Mat nuevo del tipo de [src].
     */
    fun warpCard(src: Mat, q: DoubleArray, longSide: Int): Mat {
        val landscape = side(q, 0) + side(q, 2) >= side(q, 1) + side(q, 3)
        val shortSide = (longSide / ID1_RATIO).roundToInt()
        val w = if (landscape) longSide else shortSide
        val h = if (landscape) shortSide else longSide
        val from = MatOfPoint2f(*Array(4) { Point(q[it * 2], q[it * 2 + 1]) })
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(w.toDouble(), 0.0), Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble()))
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        Imgproc.warpPerspective(src, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        from.release(); to.release(); m.release()
        return out
    }

    /**
     * Orientación por la MRZ (reverso del DNI peruano: zona TD1 de 3 renglones de 30 caracteres «I<PER…» junto a un
     * canto LARGO): si la tarjeta [q] (en [rgb]) tiene la MRZ arriba (del revés) o en un lateral, devuelve las mismas
     * esquinas reordenadas para que el rectificado la deje abajo y apaisada. Sin MRZ clara devuelve [q] tal cual.
     */
    fun orientByMrz(rgb: Mat, q: DoubleArray): DoubleArray {
        val e = runCatching {
            val warped = warpCard(rgb, q, 1000)
            try { mrzEdge(warped) } finally { warped.release() }
        }.getOrDefault(-1)
        if (e < 0 || e == 2) return q
        val k = (e - 2 + 4) % 4
        return DoubleArray(8) { i -> q[((i / 2 + k) % 4) * 2 + i % 2] }
    }

    /**
     * Canto (0 arriba, 1 derecha, 2 abajo, 3 izquierda) junto al que está la MRZ en la tarjeta rectificada [card]
     * (RGB o RGBA), o -1. Sólo cantos largos; MRZ = 2-4 renglones de texto oscuro que ocupan casi todo el canto.
     */
    internal fun mrzEdge(card: Mat): Int {
        val gray = Mat()
        Imgproc.cvtColor(card, gray, if (card.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
        val bin = Mat()
        Imgproc.adaptiveThreshold(gray, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
        // tinta NEGRA (la MRZ se imprime en negro; la trama de seguridad es más clara)
        val ink = Mat(); Imgproc.threshold(gray, ink, 110.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Core.bitwise_and(bin, ink, bin)
        ink.release(); gray.release()
        val w = bin.cols(); val h = bin.rows()
        val px = ByteArray(w * h); bin.get(0, 0, px); bin.release()
        val landscape = w >= h
        val edges = if (landscape) intArrayOf(0, 2) else intArrayOf(1, 3)
        val short = min(w, h); val long = max(w, h)
        val band = (short * 0.4).toInt()
        val cell = max(8, long / 60)
        val found = ArrayList<Int>()
        for (e in edges) {
            // filas paralelas al canto, desde el canto hacia dentro
            var runs = 0; var inRun = false; var runLen = 0
            val minRun = max(3, (short * 0.02).toInt())
            for (d in 0 until band) {
                val cells = (long * 0.92 / cell).toInt()
                var covered = 0
                for (c in 0 until cells) {
                    val a0 = (long * 0.04).toInt() + c * cell
                    var any = false
                    for (a in a0 until min(a0 + cell, long)) {
                        val (x, y) = when (e) {
                            0 -> a to d
                            2 -> a to (h - 1 - d)
                            1 -> (w - 1 - d) to a
                            else -> d to a
                        }
                        if (px[y * w + x].toInt() != 0) { any = true; break }
                    }
                    if (any) covered++
                }
                val on = covered > cells * 0.55
                if (on) { runLen++; inRun = true } else {
                    if (inRun && runLen >= minRun) runs++
                    inRun = false; runLen = 0
                }
            }
            if (inRun && runLen >= minRun) runs++
            if (runs in 2..4) found.add(e)
        }
        return if (found.size == 1) found[0] else -1
    }

    // ------------------------------------------------------------------ color

    private fun toLab(rgb8: Mat, out: Mat) {
        val f = Mat(); rgb8.convertTo(f, CvType.CV_32FC3, 1.0 / 255.0)
        Imgproc.cvtColor(f, out, Imgproc.COLOR_RGB2Lab)
        f.release()
    }

    private fun dist(l: Float, a: Float, b: Float, l0: Double, a0: Double, b0: Double): Double {
        val dl = (l - l0) * WL; val da = a - a0; val db = b - b0
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Zona central donde se muestrea el color: el 60 % interior del marco guía, o el 24 % central de la foto. */
    internal fun centerRect(w: Int, h: Int, guide: DoubleArray?): Rect {
        val (cx, cy, rw, rh) = if (guide != null && guide[2] > guide[0] && guide[3] > guide[1]) {
            listOf((guide[0] + guide[2]) / 2 * w, (guide[1] + guide[3]) / 2 * h, (guide[2] - guide[0]) * w * 0.6, (guide[3] - guide[1]) * h * 0.6)
        } else listOf(w / 2.0, h / 2.0, w * 0.24, h * 0.24)
        val x0 = (cx - rw / 2).roundToInt().coerceIn(0, w - 2); val y0 = (cy - rh / 2).roundToInt().coerceIn(0, h - 2)
        val x1 = (cx + rw / 2).roundToInt().coerceIn(x0 + 1, w); val y1 = (cy + rh / 2).roundToInt().coerceIn(y0 + 1, h)
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    private fun borderSamples(lab: FloatArray, w: Int, h: Int): FloatArray {
        val band = max(3, (min(w, h) * 0.06).roundToInt())
        val out = ArrayList<Float>()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (x < band || y < band || x >= w - band || y >= h - band) {
                    val o = (y * w + x) * 3
                    out.add(lab[o]); out.add(lab[o + 1]); out.add(lab[o + 2])
                }
                x += 3
            }
            y += 3
        }
        return out.toFloatArray()
    }

    /**
     * Color dominante de la tarjeta: k-means (k = 3) de los píxeles del centro sin texto (muy oscuros), brillos (muy
     * claros) ni grises si hay suficiente color; gana el grupo que más ocupa el centro y MENOS aparece en el borde de la
     * foto (el fondo), con un ligero favor a los colores del DNI peruano (azul/celeste, luego amarillo).
     * Devuelve (L, a, b, dispersión).
     */
    internal fun estimateColor(lab: FloatArray, w: Int, h: Int, c: Rect): DoubleArray? {
        val valid = ArrayList<FloatArray>()
        val stride = max(1, sqrt(c.width.toDouble() * c.height / 6000.0).toInt())
        var y = c.y
        while (y < c.y + c.height) {
            var x = c.x
            while (x < c.x + c.width) {
                val o = (y * w + x) * 3
                val l = lab[o]
                if (l in 18f..97f) valid.add(floatArrayOf(l, lab[o + 1], lab[o + 2]))
                x += stride
            }
            y += stride
        }
        if (valid.size < 50) return null
        val chroma = valid.filter { hypot(it[1].toDouble(), it[2].toDouble()) >= 7.0 }
        val pts = if (chroma.size >= valid.size * 0.25) chroma else valid
        val k = 3
        val data = Mat(pts.size, 3, CvType.CV_32F)
        val flat = FloatArray(pts.size * 3)
        for ((i, p) in pts.withIndex()) { flat[i * 3] = (p[0] * WL).toFloat(); flat[i * 3 + 1] = p[1]; flat[i * 3 + 2] = p[2] }
        data.put(0, 0, flat)
        val labels = Mat(); val centers = Mat()
        Core.setRNGSeed(12345)
        Core.kmeans(data, k, labels, TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 25, 0.2), 2, Core.KMEANS_PP_CENTERS, centers)
        val lab2 = IntArray(pts.size); labels.get(0, 0, lab2)
        val cen = FloatArray(k * 3); centers.get(0, 0, cen)
        data.release(); labels.release(); centers.release()
        val border = borderSamples(lab, w, h)
        val nb = border.size / 3
        var best: DoubleArray? = null; var bestScore = 0.0
        for (ci in 0 until k) {
            val l0 = cen[ci * 3] / WL; val a0 = cen[ci * 3 + 1].toDouble(); val b0 = cen[ci * 3 + 2].toDouble()
            val ds = ArrayList<Double>()
            for (i in pts.indices) if (lab2[i] == ci) ds.add(dist(pts[i][0], pts[i][1], pts[i][2], l0, a0, b0))
            if (ds.size < 10) continue
            ds.sort()
            val spread = ds[ds.size / 2]
            val frac = ds.size.toDouble() / pts.size
            val tb = max(10.0, 2.5 * spread)
            var inBorder = 0
            for (i in 0 until nb) if (dist(border[i * 3], border[i * 3 + 1], border[i * 3 + 2], l0, a0, b0) < tb) inBorder++
            val borderFrac = if (nb == 0) 0.0 else inBorder.toDouble() / nb
            val score = frac * clip01(1.0 - 2.5 * borderFrac) * prior(a0, b0)
            if (score > bestScore) { bestScore = score; best = doubleArrayOf(l0, a0, b0, spread) }
        }
        return if (bestScore > 0.05) best else null
    }

    /** Favor a priori: azul/celeste (DNI azul, DNIe) y amarillo (DNI de menores); los grises, menos. */
    internal fun prior(a: Double, b: Double): Double {
        val c = hypot(a, b)
        if (c < 7.0) return 0.6
        val hue = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
        return when {
            hue in 200.0..300.0 -> 1.3   // azul / celeste / cian azulado
            hue in 65.0..105.0 -> 1.15   // amarillo
            else -> 1.0
        }
    }

    /** Máscara del color: distancia Lab < [t] o (mismo tono en HSV-Lab, croma parecida y distancia < 2 t). */
    private fun colorMask(lab: Mat, l0: Double, a0: Double, b0: Double, t: Double, out: Mat, bag: MatBag) {
        val ch = ArrayList<Mat>(); Core.split(lab, ch); ch.forEach { bag.add(it) }
        val dl = bag.mat(); Core.subtract(ch[0], Scalar(l0), dl); Core.multiply(dl, Scalar(WL), dl)
        val da = bag.mat(); Core.subtract(ch[1], Scalar(a0), da)
        val db = bag.mat(); Core.subtract(ch[2], Scalar(b0), db)
        val d = bag.mat(); Core.multiply(dl, dl, d)
        val tmp = bag.mat(); Core.multiply(da, da, tmp); Core.add(d, tmp, d)
        Core.multiply(db, db, tmp); Core.add(d, tmp, d)
        Core.sqrt(d, d)
        Core.compare(d, Scalar(t), out, Core.CMP_LT)
        val c0 = hypot(a0, b0)
        if (c0 >= 10.0) {
            // segundo voto: tono (ángulo en a,b) dentro de ±18° con croma parecida
            val cpx = bag.mat(); Core.magnitude(ch[1], ch[2], cpx)
            val dot = bag.mat(); Core.multiply(ch[1], Scalar(a0 / c0), dot)
            Core.multiply(ch[2], Scalar(b0 / c0), tmp); Core.add(dot, tmp, dot)   // = C cos(Δh)
            val cosLim = cos(Math.toRadians(18.0))
            val thr = bag.mat(); Core.multiply(cpx, Scalar(cosLim), thr)
            val v1 = bag.mat(); Core.compare(dot, thr, v1, Core.CMP_GT)
            val v2 = bag.mat(); Core.compare(cpx, Scalar(0.45 * c0), v2, Core.CMP_GT)
            val v3 = bag.mat(); Core.compare(cpx, Scalar(2.2 * c0 + 10), v3, Core.CMP_LT)
            val v4 = bag.mat(); Core.compare(d, Scalar(2.0 * t), v4, Core.CMP_LT)
            Core.bitwise_and(v1, v2, v1); Core.bitwise_and(v1, v3, v1); Core.bitwise_and(v1, v4, v1)
            Core.bitwise_or(out, v1, out)
            // tercer voto, la SOMBRA: al oscurecer, L y la croma bajan juntas (C/L casi constante) y el tono no cambia
            val cos15 = cos(Math.toRadians(15.0))
            Core.multiply(cpx, Scalar(cos15), thr)
            Core.compare(dot, thr, v1, Core.CMP_GT)
            val lp = bag.mat(); Core.add(ch[0], Scalar(1.0), lp)
            val cl = bag.mat(); Core.divide(cpx, lp, cl)
            val k0 = c0 / (l0 + 1.0)
            Core.compare(cl, Scalar(0.55 * k0), v2, Core.CMP_GT)
            Core.compare(cl, Scalar(1.8 * k0), v3, Core.CMP_LT)
            Core.compare(ch[0], Scalar(0.3 * l0), v4, Core.CMP_GT)
            Core.bitwise_and(v1, v2, v1); Core.bitwise_and(v1, v3, v1); Core.bitwise_and(v1, v4, v1)
            Core.compare(ch[0], Scalar(l0 + 45.0), v4, Core.CMP_LT); Core.bitwise_and(v1, v4, v1)
            Core.compare(cpx, Scalar(0.3 * c0), v4, Core.CMP_GT); Core.bitwise_and(v1, v4, v1)
            Core.bitwise_or(out, v1, out)
        }
    }

    // ------------------------------------------------------------------ máscara

    private fun morph(mask: Mat, op: Int, size: Int) {
        val k = Cv.odd(max(3, size))
        val ker = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.morphologyEx(mask, mask, op, ker)
        ker.release()
    }

    /**
     * Se queda con la componente que más ocupa la zona central [c] (o la mayor) y RELLENA todos sus huecos (contorno
     * exterior pintado lleno): el contenido de la tarjeta (texto, foto, holograma, MRZ) deja de importar.
     */
    private fun pickAndFill(mask: Mat, c: Rect, bag: MatBag): Boolean {
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, cents, 8, CvType.CV_32S)
        if (n <= 1) return false
        val sub = labels.submat(c)
        val arr = IntArray(c.width * c.height)
        val cont = sub.clone(); cont.get(0, 0, arr); cont.release(); sub.release()
        val hist = IntArray(n)
        for (v in arr) if (v > 0) hist[v]++
        var best = -1; var bestV = 0
        for (i in 1 until n) if (hist[i] > bestV) { bestV = hist[i]; best = i }
        if (best < 0) {
            var bestA = 0
            for (i in 1 until n) {
                val a = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
                if (a > bestA) { bestA = a; best = i }
            }
            if (bestA < mask.total() * 0.01) return false
        }
        val one = bag.mat(); Core.compare(labels, Scalar(best.toDouble()), one, Core.CMP_EQ)
        val contours = ArrayList<MatOfPoint>(); val hier = Mat()
        Imgproc.findContours(one, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hier.release()
        mask.setTo(Scalar(0.0))
        if (contours.isEmpty()) return false
        Imgproc.drawContours(mask, contours, -1, Scalar(255.0), -1)
        contours.forEach { it.release() }
        return true
    }

    private fun externalContour(mask: Mat): Array<Point>? {
        val contours = ArrayList<MatOfPoint>(); val hier = Mat()
        Imgproc.findContours(mask.clone(), contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
        hier.release()
        val best = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val pts = best.toArray()
        contours.forEach { it.release() }
        return if (pts.size >= 20) pts else null
    }

    // ------------------------------------------------------------------ geometría

    /**
     * 4 lados del contorno: aproximación de 4 vértices (envolvente convexa + approxPolyDP, o rectángulo mínimo), cada
     * punto del contorno se asigna a su lado (sin el 12 % de cada extremo: esquinas redondeadas) y cada lado se ajusta
     * como recta robusta (Huber); las esquinas son las intersecciones.
     */
    internal fun fitQuad(contour: Array<Point>, w: Int, h: Int): DoubleArray? {
        val cm = MatOfPoint(*contour)
        val hullIdx = MatOfInt(); Imgproc.convexHull(cm, hullIdx)
        val hull = hullIdx.toArray().map { contour[it] }
        hullIdx.release(); cm.release()
        val h2 = MatOfPoint2f(*hull.toTypedArray())
        val peri = Imgproc.arcLength(h2, true)
        var approx: Array<Point>? = null
        var eps = 0.01
        while (eps <= 0.08) {
            val ap = MatOfPoint2f(); Imgproc.approxPolyDP(h2, ap, eps * peri, true)
            val a = ap.toArray(); ap.release()
            if (a.size == 4) { approx = a; break }
            if (a.size < 4) break
            eps += 0.005
        }
        if (approx == null) {
            val rr = Imgproc.minAreaRect(MatOfPoint2f(*contour))
            val box = arrayOfNulls<Point>(4); rr.points(box)
            approx = box.requireNoNulls()
        }
        h2.release()
        val init = orderCorners(DoubleArray(8) { i -> if (i % 2 == 0) approx[i / 2].x else approx[i / 2].y })
        val lines = Array(4) { DoubleArray(4) }   // vx, vy, x0, y0
        val minSide = (0 until 4).minOf { i -> hypot(init[((i + 1) % 4) * 2] - init[i * 2], init[((i + 1) % 4) * 2 + 1] - init[i * 2 + 1]) }
        val band = max(4.0, 0.05 * minSide)
        val sidePts = Array(4) { ArrayList<Point>() }
        for (p in contour) {
            var bi = -1; var bd = band
            for (i in 0 until 4) {
                val ax = init[i * 2]; val ay = init[i * 2 + 1]
                val bx = init[((i + 1) % 4) * 2]; val by = init[((i + 1) % 4) * 2 + 1]
                val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
                if (len2 < 1) continue
                val t = ((p.x - ax) * dx + (p.y - ay) * dy) / len2
                if (t < 0.12 || t > 0.88) continue
                val d = abs((p.x - ax) * dy - (p.y - ay) * dx) / sqrt(len2)
                if (d < bd) { bd = d; bi = i }
            }
            if (bi >= 0) sidePts[bi].add(p)
        }
        for (i in 0 until 4) {
            val ax = init[i * 2]; val ay = init[i * 2 + 1]
            val bx = init[((i + 1) % 4) * 2]; val by = init[((i + 1) % 4) * 2 + 1]
            val fitted = if (sidePts[i].size >= 8) fitLine(sidePts[i]) else null
            lines[i] = fitted ?: run {
                val len = max(1e-6, hypot(bx - ax, by - ay)); doubleArrayOf((bx - ax) / len, (by - ay) / len, ax, ay)
            }
        }
        val out = intersectAll(lines) ?: return null
        val lim = max(w, h) * 0.25
        for (i in 0 until 4) if (out[i * 2] < -lim || out[i * 2] > w + lim || out[i * 2 + 1] < -lim || out[i * 2 + 1] > h + lim) return null
        return out
    }

    private fun fitLine(pts: List<Point>): DoubleArray? {
        val m = MatOfPoint2f(*pts.toTypedArray())
        val line = Mat()
        Imgproc.fitLine(m, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
        val v = DoubleArray(4); for (i in 0 until 4) v[i] = line.get(i, 0)[0]
        m.release(); line.release()
        return if (v.all { it.isFinite() }) v else null
    }

    /** Esquinas = intersección del lado i-1 con el lado i (lado i va de la esquina i a la i+1). */
    private fun intersectAll(lines: Array<DoubleArray>): DoubleArray? {
        val out = DoubleArray(8)
        for (i in 0 until 4) {
            val p = intersect(lines[(i + 3) % 4], lines[i]) ?: return null
            out[i * 2] = p.first; out[i * 2 + 1] = p.second
        }
        return out
    }

    private fun intersect(l1: DoubleArray, l2: DoubleArray): Pair<Double, Double>? {
        val (vx1, vy1, x1, y1) = l1.toList()
        val (vx2, vy2, x2, y2) = l2.toList()
        val den = vx1 * vy2 - vy1 * vx2
        if (abs(den) < 1e-6) return null
        val t = ((x2 - x1) * vy2 - (y2 - y1) * vx2) / den
        return (x1 + vx1 * t) to (y1 + vy1 * t)
    }

    /** Ordena 4 puntos (x,y planos) como TL, TR, BR, BL (horario empezando por el de menor x+y). */
    internal fun orderCorners(q: DoubleArray): DoubleArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
        val idx = (0 until 4).sortedBy { atan2(q[it * 2 + 1] - cy, q[it * 2] - cx) }
        val start = idx.indices.minByOrNull { q[idx[it] * 2] + q[idx[it] * 2 + 1] }!!
        val out = DoubleArray(8)
        for (k in 0 until 4) { val j = idx[(start + k) % 4]; out[k * 2] = q[j * 2]; out[k * 2 + 1] = q[j * 2 + 1] }
        return out
    }

    internal fun convex(q: DoubleArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[i * 2]; val ay = q[i * 2 + 1]
            val bx = q[((i + 1) % 4) * 2]; val by = q[((i + 1) % 4) * 2 + 1]
            val cx = q[((i + 2) % 4) * 2]; val cy = q[((i + 2) % 4) * 2 + 1]
            val cr = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (abs(cr) < 1e-6) return false
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    internal fun polyArea(q: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 4) { val j = (i + 1) % 4; s += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1] }
        return abs(s) / 2
    }

    internal fun polyMask(w: Int, h: Int, q: DoubleArray): Mat {
        val m = Mat(h, w, CvType.CV_8UC1, Scalar(0.0))
        val mp = MatOfPoint(*Array(4) { Point(q[it * 2], q[it * 2 + 1]) })
        Imgproc.fillPoly(m, listOf(mp), Scalar(255.0))
        mp.release()
        return m
    }

    private fun side(q: DoubleArray, i: Int): Double {
        val j = (i + 1) % 4
        return hypot(q[j * 2] - q[i * 2], q[j * 2 + 1] - q[i * 2 + 1])
    }

    /** Proporción lado largo / corto del cuadrilátero. */
    internal fun ratioOf(q: DoubleArray, w: Int, h: Int): Double {
        // promedio de lados opuestos (sin ajustar a formatos conocidos: aquí se compara con ID-1)
        val rw = (side(q, 0) + side(q, 2)) / 2; val rh = (side(q, 1) + side(q, 3)) / 2
        return max(rw, rh) / max(1e-6, min(rw, rh))
    }

    /**
     * Recupera el lado perdido: si la proporción no es ID-1, una franja que toca un canto quedó fuera de la máscara
     * (banda MRZ clara del reverso, reflejo). Candidatos: los dos lados que, desplazados a lo largo de sus lados
     * contiguos, devuelven la proporción ID-1; gana el que cae sobre un borde (gradiente) y cuya franja tiene contenido
     * (texto de la MRZ) frente a un fondo liso. Devuelve (quad, lado) o null.
     */
    private class Rec(val q: DoubleArray, val side: Int, val snapped: Boolean)

    /** Colores del fondo: k-means (k = 6) de las muestras del borde de la foto (L ponderada). */
    private fun palette(border: FloatArray): FloatArray {
        val n = border.size / 3
        if (n < 12) return FloatArray(0)
        val data = Mat(n, 3, CvType.CV_32F)
        val flat = FloatArray(n * 3)
        for (i in 0 until n) { flat[i * 3] = (border[i * 3] * WL).toFloat(); flat[i * 3 + 1] = border[i * 3 + 1]; flat[i * 3 + 2] = border[i * 3 + 2] }
        data.put(0, 0, flat)
        val labels = Mat(); val centers = Mat()
        Core.setRNGSeed(777)
        Core.kmeans(data, 6, labels, TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 15, 0.5), 1, Core.KMEANS_PP_CENTERS, centers)
        val c = FloatArray(18); centers.get(0, 0, c)
        data.release(); labels.release(); centers.release()
        for (i in 0 until 6) c[i * 3] = (c[i * 3] / WL).toFloat()
        return c
    }

    private fun recoverSide(q: DoubleArray, w: Int, h: Int, gray: Mat, r: Double, lab: FloatArray, bgPal: FloatArray, l0: Double): Rec? {
        if (r < 1.0) return null
        val horizontalLong = (side(q, 0) + side(q, 2)) >= (side(q, 1) + side(q, 3))
        val tooLong = r > ID1_RATIO   // el lado corto quedó corto: falta una franja a lo largo de un lado LARGO
        val longSides = if (horizontalLong) intArrayOf(0, 2) else intArrayOf(1, 3)
        val shortSides = if (horizontalLong) intArrayOf(1, 3) else intArrayOf(0, 2)
        val cands = if (tooLong) longSides else shortSides
        val k = if (tooLong) r / ID1_RATIO else ID1_RATIO / r
        if (k > 1.8) return null
        val gx = Mat(); val gy = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3); Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
        val gxa = FloatArray(w * h); gx.get(0, 0, gxa); val gya = FloatArray(w * h); gy.get(0, 0, gya)
        val ga = ByteArray(w * h); gray.get(0, 0, ga)
        gx.release(); gy.release()
        var best: DoubleArray? = null; var bestSide = -1; var bestScore = -1.0
        for (s in cands) {
            val i0 = s; val i1 = (s + 1) % 4
            val o0 = (s + 3) % 4; val o1 = (s + 2) % 4   // esquinas opuestas en los lados contiguos
            val nq = q.copyOf()
            nq[i0 * 2] = q[o0 * 2] + (q[i0 * 2] - q[o0 * 2]) * k; nq[i0 * 2 + 1] = q[o0 * 2 + 1] + (q[i0 * 2 + 1] - q[o0 * 2 + 1]) * k
            nq[i1 * 2] = q[o1 * 2] + (q[i1 * 2] - q[o1 * 2]) * k; nq[i1 * 2 + 1] = q[o1 * 2 + 1] + (q[i1 * 2 + 1] - q[o1 * 2 + 1]) * k
            val tol = 0.02 * max(w, h)
            if ((0 until 4).any { nq[it * 2] < -tol || nq[it * 2] > w + tol || nq[it * 2 + 1] < -tol || nq[it * 2 + 1] > h + tol }) continue
            // gradiente normal a lo largo de la nueva recta
            val ax = nq[i0 * 2]; val ay = nq[i0 * 2 + 1]; val bx = nq[i1 * 2]; val by = nq[i1 * 2 + 1]
            val len = max(1e-6, hypot(bx - ax, by - ay)); val nx = -(by - ay) / len; val ny = (bx - ax) / len
            var g = 0.0; var gn = 0
            for (j in 0 until 40) {
                val t = 0.12 + 0.76 * j / 39.0
                var bestG = 0.0
                for (dd in -2..2) {
                    val x = (ax + (bx - ax) * t + nx * dd).roundToInt(); val y = (ay + (by - ay) * t + ny * dd).roundToInt()
                    if (x < 0 || y < 0 || x >= w || y >= h) continue
                    val o = y * w + x
                    bestG = max(bestG, abs(gxa[o] * nx + gya[o] * ny))
                }
                g += bestG; gn++
            }
            g = if (gn > 0) g / gn else 0.0
            // contenido de la franja entre la recta vieja y la nueva (desviación local del gris)
            val cx0 = q[i0 * 2]; val cy0 = q[i0 * 2 + 1]; val cx1 = q[i1 * 2]; val cy1 = q[i1 * 2 + 1]
            val vals = ArrayList<Double>()
            var bgLike = 0
            var neutralLight = 0
            for (j in 0 until 30) for (m in 1..5) {
                val t = 0.12 + 0.76 * j / 29.0; val u = m / 6.0
                val px = cx0 + (cx1 - cx0) * t; val py = cy0 + (cy1 - cy0) * t
                val qx = ax + (bx - ax) * t; val qy = ay + (by - ay) * t
                val x = (px + (qx - px) * u).roundToInt(); val y = (py + (qy - py) * u).roundToInt()
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                vals.add((ga[y * w + x].toInt() and 0xFF).toDouble())
                val o = (y * w + x) * 3
                var md = Double.MAX_VALUE
                for (c in 0 until bgPal.size / 3) md = min(md, dist(lab[o], lab[o + 1], lab[o + 2], bgPal[c * 3].toDouble(), bgPal[c * 3 + 1].toDouble(), bgPal[c * 3 + 2].toDouble()))
                if (md < 8.0) bgLike++
                if (lab[o] > l0 && hypot(lab[o + 1].toDouble(), lab[o + 2].toDouble()) < 10.0) neutralLight++
            }
            val mean = vals.average().takeIf { it.isFinite() } ?: 0.0
            val sd = if (vals.size > 2) sqrt(vals.sumOf { (it - mean) * (it - mean) } / vals.size) else 0.0
            // la franja que falta es TARJETA (banda clara con texto), no fondo: sus colores no están en el borde de la foto
            val bgFrac = if (vals.isEmpty()) 1.0 else bgLike.toDouble() / vals.size
            // texto negro de la MRZ (caracteres OCR-B oscuros sobre una banda CLARA)
            val med = vals.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
            val dark = if (vals.isEmpty()) 0.0 else vals.count { it < med - 50.0 }.toDouble() / vals.size
            val neutral = if (vals.isEmpty()) 0.0 else neutralLight.toDouble() / vals.size
            val text = if (neutral >= 0.45 && dark in 0.03..0.45) 1.0 else 0.0
            val score = 2.0 * (1.0 - bgFrac) + min(g / 40.0, 1.5) + min(sd / 20.0, 1.5) + 2.0 * text
            dbg("rec", "lado=$s g=%.1f sd=%.1f bg=%.2f dark=%.2f neutro=%.2f score=%.2f".format(g, sd, bgFrac, dark, neutral, score))
            if (score > bestScore) { bestScore = score; best = nq; bestSide = s }
        }
        val chosen = best ?: return null
        val snapped = snapSide(chosen, bestSide, q, w, h, gxa, gya)
        return Rec(snapped ?: chosen, bestSide, snapped != null)
    }

    /**
     * Ajusta el lado recuperado [s] de [nq] (predicho con la proporción) al canto real si se ve: en cada punto del lado
     * busca, a lo largo de la normal y cerca de la predicción, el gradiente de gris más fuerte; si esos puntos forman
     * una recta casi paralela a la predicha, ésa es el canto (las esquinas se recalculan con los lados contiguos).
     */
    private fun snapSide(nq: DoubleArray, s: Int, q: DoubleArray, w: Int, h: Int, gxa: FloatArray, gya: FloatArray): DoubleArray? {
        val i0 = s; val i1 = (s + 1) % 4
        val ax = nq[i0 * 2]; val ay = nq[i0 * 2 + 1]; val bx = nq[i1 * 2]; val by = nq[i1 * 2 + 1]
        val len = max(1e-6, hypot(bx - ax, by - ay))
        var nx = -(by - ay) / len; var ny = (bx - ax) / len
        val omx = (q[i0 * 2] + q[i1 * 2]) / 2; val omy = (q[i0 * 2 + 1] + q[i1 * 2 + 1]) / 2
        val pmx = (ax + bx) / 2; val pmy = (ay + by) / 2
        if ((pmx - omx) * nx + (pmy - omy) * ny < 0) { nx = -nx; ny = -ny }
        val ext = hypot(pmx - omx, pmy - omy)
        if (ext < 4) return null
        // de la mitad de la franja predicha hasta algo más allá; se toma el canto fuerte MÁS EXTERIOR (el texto de la
        // MRZ queda dentro)
        val oFrom = -0.55 * ext; val oTo = 0.6 * ext
        val pts = ArrayList<Point>()
        val nSteps = ((oTo - oFrom) / 0.5).toInt() + 1
        val prof = DoubleArray(nSteps)
        for (j in 0 until 50) {
            val t = 0.1 + 0.8 * j / 49.0
            val px = ax + (bx - ax) * t; val py = ay + (by - ay) * t
            var pmax = 0.0
            for (k in 0 until nSteps) {
                val o = oFrom + k * 0.5
                val x = (px + nx * o).roundToInt(); val y = (py + ny * o).roundToInt()
                prof[k] = if (x in 0 until w && y in 0 until h) { val i = y * w + x; abs(gxa[i] * nx + gya[i] * ny).toDouble() } else 0.0
                pmax = max(pmax, prof[k])
            }
            val thr = max(12.0, 0.5 * pmax)
            var kk = -1
            for (k in nSteps - 2 downTo 1) if (prof[k] >= thr && prof[k] >= prof[k - 1] && prof[k] >= prof[k + 1]) { kk = k; break }
            if (kk >= 0) { val o = oFrom + kk * 0.5; pts.add(Point(px + nx * o, py + ny * o)) }
        }
        if (pts.size < 20) return null
        val line = fitLine(pts) ?: return null
        // casi paralela a la predicha y con la mayoría de puntos sobre ella
        val cosA = abs(line[0] * (bx - ax) / len + line[1] * (by - ay) / len)
        if (cosA < cos(Math.toRadians(4.0))) return null
        val inl = pts.count { p -> abs((p.x - line[2]) * line[1] - (p.y - line[3]) * line[0]) < 2.0 }
        if (inl < pts.size * 0.5) return null
        // lados contiguos (de la esquina opuesta a la del lado)
        val o0 = (s + 3) % 4; val o1 = (s + 2) % 4
        fun lineThrough(x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray {
            val l = max(1e-6, hypot(x1 - x0, y1 - y0)); return doubleArrayOf((x1 - x0) / l, (y1 - y0) / l, x0, y0)
        }
        val adj0 = lineThrough(q[o0 * 2], q[o0 * 2 + 1], q[i0 * 2], q[i0 * 2 + 1])
        val adj1 = lineThrough(q[o1 * 2], q[o1 * 2 + 1], q[i1 * 2], q[i1 * 2 + 1])
        val c0 = intersect(adj0, line) ?: return null
        val c1 = intersect(adj1, line) ?: return null
        val out = nq.copyOf()
        out[i0 * 2] = c0.first; out[i0 * 2 + 1] = c0.second; out[i1 * 2] = c1.first; out[i1 * 2 + 1] = c1.second
        if (!convex(out)) return null
        val r = ratioOf(out, w, h)
        dbg("snap", "pts=${pts.size} inl=$inl r=%.3f".format(r))
        return if (abs(r / ID1_RATIO - 1.0) < 0.2) out else null
    }

    // ------------------------------------------------------------------ resolución completa

    /**
     * Afina cada lado a resolución completa: en una banda estrecha a ambos lados de la recta busca, a lo largo de la
     * normal, el máximo del gradiente de la distancia de color (dentro = color de la tarjeta, fuera = fondo) con
     * precisión sub-píxel y reajusta la recta (Huber). [scale] = píxeles completos por píxel de trabajo.
     */
    private fun refineFull(rgb: Mat, q: DoubleArray, l0: Double, a0: Double, b0: Double, t: Double, scale: Double, graySide: Int, skipSide: Int): DoubleArray? {
        val w = rgb.cols(); val h = rgb.rows()
        val hw = max(3.0, 2.5 * scale)
        val lines = Array(4) { DoubleArray(4) }
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
        for (s in 0 until 4) {
            val ax = q[s * 2]; val ay = q[s * 2 + 1]; val bx = q[((s + 1) % 4) * 2]; val by = q[((s + 1) % 4) * 2 + 1]
            val len = max(1e-6, hypot(bx - ax, by - ay))
            val ux = (bx - ax) / len; val uy = (by - ay) / len
            var nx = -uy; var ny = ux
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            if ((mx - cx) * nx + (my - cy) * ny < 0) { nx = -nx; ny = -ny }   // normal hacia fuera
            lines[s] = doubleArrayOf(ux, uy, ax, ay)
            if (s == skipSide) continue
            val x0 = (min(ax, bx) - hw - 3).toInt().coerceIn(0, w - 1); val x1 = (max(ax, bx) + hw + 3).toInt().coerceIn(x0 + 1, w)
            val y0 = (min(ay, by) - hw - 3).toInt().coerceIn(0, h - 1); val y1 = (max(ay, by) + hw + 3).toInt().coerceIn(y0 + 1, h)
            val rw = x1 - x0; val rh = y1 - y0
            if (rw < 3 || rh < 3) continue
            val roi = rgb.submat(y0, y1, x0, x1)
            val isGray = s == graySide
            val d = Mat(); val tmp = Mat()
            val ch = ArrayList<Mat>()
            if (isGray) {
                // lado recuperado (banda clara de la MRZ): canto por el gradiente de gris, no por el color
                Imgproc.cvtColor(roi, tmp, Imgproc.COLOR_RGB2GRAY); tmp.convertTo(d, CvType.CV_32F)
                roi.release()
            } else {
                val lab = Mat(); toLab(roi, lab); roi.release()
                Core.split(lab, ch); lab.release()
                Core.subtract(ch[0], Scalar(l0), tmp); Core.multiply(tmp, Scalar(WL), tmp); Core.multiply(tmp, tmp, d)
                Core.subtract(ch[1], Scalar(a0), tmp); Core.multiply(tmp, tmp, tmp); Core.add(d, tmp, d)
                Core.subtract(ch[2], Scalar(b0), tmp); Core.multiply(tmp, tmp, tmp); Core.add(d, tmp, d)
                Core.sqrt(d, d)
            }
            val sig = max(0.8, 0.5 * scale)
            Imgproc.GaussianBlur(d, d, Size(0.0, 0.0), sig)
            val arr = FloatArray(rw * rh); d.get(0, 0, arr)
            ch.forEach { it.release() }; d.release(); tmp.release()
            fun at(x: Double, y: Double): Double? {
                val fx = x - x0; val fy = y - y0
                if (fx < 0 || fy < 0 || fx >= rw - 1 || fy >= rh - 1) return null
                val ix = fx.toInt(); val iy = fy.toInt(); val dx = fx - ix; val dy = fy - iy
                val o = iy * rw + ix
                return arr[o] * (1 - dx) * (1 - dy) + arr[o + 1] * dx * (1 - dy) + arr[o + rw] * (1 - dx) * dy + arr[o + rw + 1] * dx * dy
            }
            val edge = ArrayList<Point>()
            val n = 60
            val steps = hw.toInt()
            for (j in 0 until n) {
                val tt = 0.12 + 0.76 * j / (n - 1)
                val px = ax + (bx - ax) * tt; val py = ay + (by - ay) * tt
                val prof = DoubleArray(2 * steps + 3)
                var ok = true
                for (k in prof.indices) {
                    val dd = (k - steps - 1).toDouble()
                    prof[k] = at(px + nx * dd, py + ny * dd) ?: run { ok = false; 0.0 }
                    if (!ok) break
                }
                if (!ok) continue
                var bk = -1; var bg = if (isGray) 4.0 else 0.15 * t
                fun der(k: Int): Double { val g = (prof[k + 1] - prof[k - 1]) / 2; return if (isGray) abs(g) else g }
                for (k in 1 until prof.size - 1) {
                    val g = der(k)
                    if (g > bg) { bg = g; bk = k }
                }
                if (bk < 2 || bk > prof.size - 3) continue
                val gm = der(bk - 1); val gp = der(bk + 1)
                val den = gm - 2 * bg + gp
                val off = if (abs(den) > 1e-9) (0.5 * (gm - gp) / den).coerceIn(-0.5, 0.5) else 0.0
                val dd = bk - steps - 1 + off
                edge.add(Point(px + nx * dd, py + ny * dd))
            }
            if (edge.size >= 15) fitLine(edge)?.let { lines[s] = it }
        }
        val out = intersectAll(lines) ?: return null
        for (i in 0 until 4) if (hypot(out[i * 2] - q[i * 2], out[i * 2 + 1] - q[i * 2 + 1]) > 3 * hw + 2) return null
        return if (convex(out)) out else null
    }

    private fun clip01(v: Double) = v.coerceIn(0.0, 1.0)
}
