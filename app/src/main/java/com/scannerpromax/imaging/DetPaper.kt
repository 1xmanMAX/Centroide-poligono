package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hoja por MATERIAL de papel (foto completa, ~500 px): para fotos de hojas blancas con texto sobre madera, telas de
 * color, fondos oscuros, con objetos encima (teclado, carteles) o con otras hojas debajo, donde los bordes no cierran
 * un contorno o la veta y los recuadros impresos engañan al detector de bordes.
 *
 *  1. Papel = poco croma (|a|+|b| de Lab) y claro respecto del papel de alrededor de la TINTA (el color de referencia se
 *     mide junto al texto, así una sombra sobre la hoja sigue siendo papel y la madera clara, con croma, no lo es).
 *  2. La hoja = la componente de papel con más tinta (la de arriba con texto, no un trozo de mesa blanca), rellena.
 *  3. Cuadrilátero = el que mejor se AJUSTA a esa máscara: +1 por píxel de papel dentro, −[K_BG] por píxel ajeno dentro
 *     (teclado, madera, cartel), −[K_INK] por píxel de texto fuera. Descenso por coordenadas de las esquinas sobre
 *     sumas por filas (cada evaluación cuesta O(alto)). Un objeto que tapa un borde queda fuera si quitarlo no deja
 *     texto fuera; si tapa una esquina, el lado sigue la recta del borde visible.
 *
 * [DocumentDetector] lo usa para ARBITRAR: si el candidato por bordes falta, tiene poca confianza, mete fondo ajeno,
 * es un recuadro impreso/dibujado dentro de la hoja o corta la hoja dejando texto fuera, y el ajuste por papel es
 * limpio, se usa éste. Coordenadas de trabajo.
 */
internal object DetPaper {

    class Model(
        val w: Int, val h: Int,
        /** Hoja (componente principal rellena), componente sin rellenar y tinta interior de la hoja: 1/0 por píxel. */
        val filled: ByteArray, val comp: ByteArray, val ink: ByteArray,
        val filledCount: Int, val compCount: Int, val inkCount: Int,
        /** Croma y luminancia de referencia del papel. */
        val paperChroma: Double, val paperL: Double,
        /** Casco convexo de la hoja (para la hipótesis inicial). */
        val hull: Array<Point>,
    ) {
        // Sumas por filas (prefijos) del peso de ajuste y de la tinta
        internal val wPref = Array(h) { FloatArray(w + 1) }
        internal val iPref = Array(h) { IntArray(w + 1) }
        internal val fPref = Array(h) { IntArray(w + 1) }
        internal val cPref = Array(h) { IntArray(w + 1) }
        init {
            for (y in 0 until h) {
                val wr = wPref[y]; val ir = iPref[y]; val fr = fPref[y]; val cr = cPref[y]
                val o = y * w
                for (x in 0 until w) {
                    val f = filled[o + x].toInt() != 0
                    wr[x + 1] = wr[x] + if (f) 1f else -K_BG
                    ir[x + 1] = ir[x] + ink[o + x]
                    fr[x + 1] = fr[x] + (if (f) 1 else 0)
                    cr[x + 1] = cr[x] + comp[o + x]
                }
            }
        }
    }

    /** Rasgos de un cuadrilátero frente a la hoja: tinta dentro, fondo ajeno dentro, hoja cubierta, papel crudo dentro. */
    class Fit(val inkIn: Double, val bgIn: Double, val cov: Double, val paperIn: Double, val area: Double)

    /** Peso (negativo) de un píxel ajeno dentro del cuadrilátero y de un píxel de texto fuera. */
    const val K_BG = 1.5f
    const val K_INK = 4f

    /** Papel de referencia: croma máxima (|a|+|b|) y luminancia mínima (L de Lab, 0..255). */
    private const val MAX_PAPER_CHROMA = 20.0
    private const val MIN_PAPER_L = 100.0
    /** Hoja mínima (fracción del encuadre) y tinta mínima (fracción de píxeles) para fiarse de la máscara. */
    private const val MIN_SHEET_AREA = 0.12
    private const val MIN_INK_FRAC = 0.002

    /** Máscara de la hoja; null si la escena no parece una hoja clara con texto. [rgb] RGB 8UC3 a ~500 px. */
    fun analyze(rgb: Mat): Model? = MatBag().use { bag ->
        val w = rgb.cols(); val h = rgb.rows(); val n = w * h
        if (min(w, h) < 48) return@use null
        val side = max(w, h)
        val lab = bag.mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val l = bag.mat(); Core.extractChannel(lab, l, 0)
        Imgproc.GaussianBlur(l, l, org.opencv.core.Size(3.0, 3.0), 0.0)
        val a = bag.mat(); Core.extractChannel(lab, a, 1)
        val b = bag.mat(); Core.extractChannel(lab, b, 2)
        val ad = bag.mat(); Core.absdiff(a, Scalar(128.0), ad)
        val bd = bag.mat(); Core.absdiff(b, Scalar(128.0), bd)
        val c = bag.mat(); Core.add(ad, bd, c)
        Imgproc.GaussianBlur(c, c, org.opencv.core.Size(5.0, 5.0), 0.0)
        val k = Cv.odd(max(5, (side * 0.03).toInt()))
        val closed = bag.mat(); Imgproc.morphologyEx(l, closed, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, k))
        Imgproc.medianBlur(closed, closed, 5)
        val lb = ByteArray(n); l.get(0, 0, lb)
        val cb = ByteArray(n); c.get(0, 0, cb)
        val kb = ByteArray(n); closed.get(0, 0, kb)
        // Tinta: más oscura que el papel de alrededor (sobre algo claro)
        val inkB = ByteArray(n)
        for (i in 0 until n) {
            val cl = kb[i].toInt() and 0xFF; val lv = lb[i].toInt() and 0xFF
            if (cl > 90 && cl - lv > max(12.0, 0.12 * cl)) inkB[i] = 1
        }
        val inkM = bag.mat(); inkM.create(h, w, CvType.CV_8UC1); inkM.put(0, 0, inkB)
        val ring = bag.mat(); Imgproc.dilate(inkM, ring, Cv.kernel(Imgproc.MORPH_RECT, 5))
        val rb = ByteArray(n); ring.get(0, 0, rb)
        // Papel de referencia: junto a la tinta, sin tinta, poco croma
        val chHist = IntArray(256); val lHist = IntArray(256); var nc = 0
        for (i in 0 until n) {
            if (rb[i].toInt() == 0 || inkB[i].toInt() != 0) continue
            val cv = cb[i].toInt() and 0xFF
            if (cv >= 30) continue
            chHist[cv]++; lHist[kb[i].toInt() and 0xFF]++; nc++
        }
        if (nc < 100) return@use null
        val pc = pctOf(chHist, nc, 0.5).toDouble(); val pl = pctOf(lHist, nc, 0.75).toDouble()
        if (pc > MAX_PAPER_CHROMA || pl < MIN_PAPER_L) return@use null
        val cMax = pc + max(8.0, 0.6 * pc); val lMin = 0.55 * pl
        val pb = ByteArray(n)
        for (i in 0 until n) if ((cb[i].toInt() and 0xFF) <= cMax && (kb[i].toInt() and 0xFF) >= lMin) pb[i] = 1
        val paper = bag.mat(); paper.create(h, w, CvType.CV_8UC1); paper.put(0, 0, pb)
        Imgproc.morphologyEx(paper, paper, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_RECT, 5))
        Imgproc.morphologyEx(paper, paper, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, 7))
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val ncomp = Imgproc.connectedComponentsWithStats(paper, labels, stats, cents, 4, CvType.CV_32S)
        if (ncomp < 2) return@use null
        val lab32 = IntArray(n); labels.get(0, 0, lab32)
        val inkOf = IntArray(ncomp)
        for (i in 0 until n) if (inkB[i].toInt() != 0) inkOf[lab32[i]]++
        var main = 0
        for (cc in 1 until ncomp) if (inkOf[cc] > inkOf[main] || main == 0) main = cc
        if (main == 0 || inkOf[main] == 0) return@use null
        val compB = ByteArray(n); var compCount = 0
        for (i in 0 until n) if (lab32[i] == main) { compB[i] = 1; compCount++ }
        // Rellenar huecos: contorno exterior de la componente
        val compM = bag.mat(); compM.create(h, w, CvType.CV_8UC1); compM.put(0, 0, compB)
        val cnts = ArrayList<MatOfPoint>(); val hier = bag.mat()
        Imgproc.findContours(compM, cnts, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
        val outer = cnts.maxByOrNull { Imgproc.contourArea(it) } ?: return@use null
        val filledM = bag.mat(); filledM.create(h, w, CvType.CV_8UC1); filledM.setTo(Scalar(0.0))
        Imgproc.drawContours(filledM, listOf(outer), 0, Scalar(1.0), -1)
        val hullIdx = MatOfInt(); Imgproc.convexHull(outer, hullIdx, false)
        val op = outer.toArray()
        val hull = hullIdx.toArray().map { op[it] }.toTypedArray()
        hullIdx.release(); for (m in cnts) m.release()
        val filledB = ByteArray(n); filledM.get(0, 0, filledB)
        var filledCount = 0; for (v in filledB) if (v.toInt() != 0) filledCount++
        // Tinta de la hoja, sin la franja del borde (sombra o canto de la propia hoja)
        val er = bag.mat(); Imgproc.erode(filledM, er, Cv.kernel(Imgproc.MORPH_RECT, 7))
        val eb = ByteArray(n); er.get(0, 0, eb)
        var inkCount = 0
        val sink = ByteArray(n)
        for (i in 0 until n) if (inkB[i].toInt() != 0 && eb[i].toInt() != 0) { sink[i] = 1; inkCount++ }
        if (filledCount < MIN_SHEET_AREA * n || inkCount < MIN_INK_FRAC * n || hull.size < 3) return@use null
        Model(w, h, filledB, compB, sink, filledCount, compCount, inkCount, pc, pl, hull)
    }

    private fun pctOf(hist: IntArray, total: Int, p: Double): Int {
        val target = p * total; var acc = 0
        for (i in hist.indices) { acc += hist[i]; if (acc >= target) return i }
        return hist.size - 1
    }

    /** Extremos [xmin, xmax] de la fila [y] dentro del cuadrilátero convexo [q] (tl,tr,br,bl); false si no la corta. */
    private fun span(q: FloatArray, y: Double, out: DoubleArray): Boolean {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 0 until 4) {
            val x0 = q[i * 2].toDouble(); val y0 = q[i * 2 + 1].toDouble()
            val x1 = q[((i + 1) % 4) * 2].toDouble(); val y1 = q[((i + 1) % 4) * 2 + 1].toDouble()
            if ((y < min(y0, y1)) || (y > max(y0, y1))) continue
            val x = if (abs(y1 - y0) < 1e-9) { lo = min(lo, min(x0, x1)); hi = max(hi, max(x0, x1)); continue } else x0 + (x1 - x0) * (y - y0) / (y1 - y0)
            lo = min(lo, x); hi = max(hi, x)
        }
        if (lo > hi) return false
        out[0] = lo; out[1] = hi
        return true
    }

    /** Recorre las filas del cuadrilátero: [f](fila, x0, x1) con x0..x1 inclusivos dentro de la imagen. */
    private inline fun rows(m: Model, q: FloatArray, f: (Int, Int, Int) -> Unit) {
        var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until 4) { y0 = min(y0, q[i * 2 + 1].toDouble()); y1 = max(y1, q[i * 2 + 1].toDouble()) }
        val sp = DoubleArray(2)
        for (y in max(0, ceil(y0).toInt())..min(m.h - 1, floor(y1).toInt())) {
            if (!span(q, y.toDouble(), sp)) continue
            val a = max(0, ceil(sp[0] - 1e-6).toInt()); val b = min(m.w - 1, floor(sp[1] + 1e-6).toInt())
            if (b >= a) f(y, a, b)
        }
    }

    private fun score(m: Model, q: FloatArray): Double {
        var s = 0.0; var inkIn = 0
        rows(m, q) { y, a, b -> s += m.wPref[y][b + 1] - m.wPref[y][a]; inkIn += m.iPref[y][b + 1] - m.iPref[y][a] }
        return s - K_INK * (m.inkCount - inkIn)
    }

    fun fit(m: Model, q: FloatArray): Fit {
        var area = 0; var f = 0; var ink = 0; var c = 0
        rows(m, q) { y, a, b ->
            area += b - a + 1
            f += m.fPref[y][b + 1] - m.fPref[y][a]
            ink += m.iPref[y][b + 1] - m.iPref[y][a]
            c += m.cPref[y][b + 1] - m.cPref[y][a]
        }
        val ar = max(1, area).toDouble()
        return Fit(
            inkIn = ink.toDouble() / max(1, m.inkCount), bgIn = (area - f) / ar, cov = f.toDouble() / max(1, m.filledCount),
            paperIn = c / ar, area = area.toDouble() / (m.w.toDouble() * m.h),
        )
    }

    private fun convexOk(q: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[((i + 3) % 4) * 2] - q[i * 2]; val ay = q[((i + 3) % 4) * 2 + 1] - q[i * 2 + 1]
            val bx = q[((i + 1) % 4) * 2] - q[i * 2]; val by = q[((i + 1) % 4) * 2 + 1] - q[i * 2 + 1]
            val cr = ax * by - ay * bx
            val sg = if (cr > 0) 1 else if (cr < 0) -1 else return false
            if (sign == 0) sign = sg else if (sg != sign) return false
            if (hypot(ax.toDouble(), ay.toDouble()) < 8 || hypot(bx.toDouble(), by.toDouble()) < 8) return false
        }
        return true
    }

    /** Máximo |coseno| de los ángulos del cuadrilátero (0 = rectángulo). */
    fun maxCos(q: FloatArray): Double {
        var mc = 0.0
        for (i in 0 until 4) {
            val ax = (q[((i + 3) % 4) * 2] - q[i * 2]).toDouble(); val ay = (q[((i + 3) % 4) * 2 + 1] - q[i * 2 + 1]).toDouble()
            val bx = (q[((i + 1) % 4) * 2] - q[i * 2]).toDouble(); val by = (q[((i + 1) % 4) * 2 + 1] - q[i * 2 + 1]).toDouble()
            mc = max(mc, abs((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by) + 1e-9)))
        }
        return mc
    }

    /**
     * Cuadrilátero (tl,tr,br,bl) ajustado a la hoja: parte del casco simplificado a 4 vértices (o del rectángulo mínimo)
     * y mueve cada esquina 16, 8, 4, 2, 1 px mientras mejore la puntuación. Null si no sale un cuadrilátero convexo.
     */
    fun quad(m: Model): FloatArray? {
        val w = m.w; val h = m.h
        val hull2f = MatOfPoint2f(*m.hull)
        val approx = MatOfPoint2f()
        try {
            val peri = Imgproc.arcLength(hull2f, true)
            Imgproc.approxPolyDP(hull2f, approx, 0.02 * peri, true)
            val ap = approx.toArray()
            val init: Array<Point> = if (ap.size == 4) ap else {
                val rr = Imgproc.minAreaRect(hull2f)
                arrayOfNulls<Point>(4).also { rr.points(it) }.map { it!! }.toTypedArray()
            }
            val q = DocumentDetector.orderPoints(init)
            for (i in 0 until 4) { q[i * 2] = q[i * 2].coerceIn(0f, w - 1f); q[i * 2 + 1] = q[i * 2 + 1].coerceIn(0f, h - 1f) }
            if (!convexOk(q)) return null
            var best = score(m, q)
            val cand = FloatArray(8)
            for (step in intArrayOf(16, 8, 4, 2, 1)) {
                var improved = true; var guard = 0
                while (improved && guard++ < 200) {
                    improved = false
                    for (i in 0 until 4) for (d in 0 until 4) {
                        System.arraycopy(q, 0, cand, 0, 8)
                        val dx = when (d) { 0 -> step; 1 -> -step; else -> 0 }; val dy = when (d) { 2 -> step; 3 -> -step; else -> 0 }
                        cand[i * 2] = (cand[i * 2] + dx).coerceIn(0f, w - 1f); cand[i * 2 + 1] = (cand[i * 2 + 1] + dy).coerceIn(0f, h - 1f)
                        if (!convexOk(cand)) continue
                        val s = score(m, cand)
                        if (s > best + 1e-6) { best = s; System.arraycopy(cand, 0, q, 0, 8); improved = true }
                    }
                }
            }
            return q
        } finally { hull2f.release(); approx.release() }
    }
}
