package com.scannerpromax.ocr.indra

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Página en gris uint8 (fila a fila). */
class Gray(val w: Int, val h: Int, val px: ByteArray) {
    init { require(px.size == w * h) }

    operator fun get(x: Int, y: Int): Int = px[y * w + x].toInt() and 0xFF

    fun toMat(): Mat = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, px) }

    companion object {
        fun fromMat(m: Mat): Gray {
            val c = if (m.isContinuous) m else m.clone()
            val a = ByteArray(c.rows() * c.cols())
            c.get(0, 0, a)
            if (c !== m) c.release()
            return Gray(m.cols(), m.rows(), a)
        }
    }
}

/**
 * Pre/post-proceso de imagen de Indra (puertos de pipeline.py, det_post.py y rec_preproc.py) con OpenCV, como el
 * original (cv2): mismos redimensionados (INTER_AREA al reducir, INTER_LINEAR al ampliar) y giros (warpAffine).
 */
object IndraImage {
    const val PREVIEW_SIDE = 1600
    const val ORIENT_SIDE = 768
    const val DESKEW_MAX = 45.0
    const val DESKEW_MIN = 0.3
    const val INPUT_MULTIPLE = 32
    const val PAD_Y = 0.2
    const val PAD_X = 0.3
    const val WIDTH_MULTIPLE = 8

    fun resize(g: Gray, nw: Int, nh: Int, interp: Int): Gray {
        if (nw == g.w && nh == g.h) return g
        val src = g.toMat(); val dst = Mat()
        Imgproc.resize(src, dst, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, interp)
        return Gray.fromMat(dst).also { src.release(); dst.release() }
    }

    /** Python round() (a la par) para que los tamaños coincidan con el original. */
    fun pyRound(x: Double): Int {
        val r = Math.rint(x)
        return r.toInt()
    }

    /** La página reducida una vez a ≤ [side] px de lado (INTER_AREA): la leen el deskew y la orientación. */
    fun preview(g: Gray, side: Int = PREVIEW_SIDE): Gray {
        val s = side.toDouble() / max(g.w, g.h)
        if (s >= 1) return g
        return resize(g, max(1, pyRound(g.w * s)), max(1, pyRound(g.h * s)), Imgproc.INTER_AREA)
    }

    /** Mapa de tinta uint8 (255 = tinta) reducido a [side] px. */
    private fun inkThumb(g: Gray, side: Int): Mat {
        val s = side.toDouble() / max(g.w, g.h)
        val small = resize(g, max(1, pyRound(g.w * s)), max(1, pyRound(g.h * s)), Imgproc.INTER_AREA)
        val m = small.toMat()
        val inv = Mat()
        org.opencv.core.Core.bitwise_not(m, inv)
        m.release()
        return inv
    }

    private fun rowSharpness(ink: Mat, a: Double): Double {
        val w = ink.cols(); val h = ink.rows()
        val rot = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), a, 1.0)
        val r = Mat()
        Imgproc.warpAffine(ink, r, rot, Size(w.toDouble(), h.toDouble()))
        val buf = ByteArray(w * h)
        r.get(0, 0, buf)
        r.release(); rot.release()
        var prev = -1L
        var acc = 0.0
        for (y in 0 until h) {
            var s = 0L
            val o = y * w
            for (x in 0 until w) s += buf[o + x].toInt() and 0xFF
            if (prev >= 0) { val d = (s - prev).toDouble(); acc += d * d }
            prev = s
        }
        return acc
    }

    /** Elemento [i] de np.arange(start, …, step): numpy usa delta = (start + step) − start y start + i·delta. */
    private fun npArange(start: Double, step: Double, i: Int): Double = start + i * ((start + step) - start)

    /** Grados (antihorario) que enderezan los renglones (estimate_skew). */
    fun estimateSkew(g: Gray, side: Int = 800): Double {
        val fine = inkThumb(g, side)
        try {
            val mean = org.opencv.core.Core.mean(fine).`val`[0]
            if (mean < 0.003 * 255) return 0.0
            val coarse = inkThumb(g, side / 2)
            val grid = ArrayList<Double>()
            var a = -DESKEW_MAX
            while (a <= DESKEW_MAX + 1e-6) { grid += a; a += 2.0 }
            val scores = grid.map { rowSharpness(coarse, it) }
            coarse.release()
            // np.argsort(scores)[-2:] (orden estable: en empate gana el índice mayor en la última posición)
            val idx = scores.indices.sortedWith(compareBy<Int> { scores[it] }.thenBy { it })
            val starts = LinkedHashSet<Double>().apply { add(0.0); add(grid[idx[idx.size - 2]]); add(grid[idx[idx.size - 1]]) }
            val near = ArrayList<Double>()
            for (a0 in starts) for (k in 0..8) near += npArange(a0 - 2.0, 0.5, k)
            val seen = LinkedHashMap<Double, Double>()
            for (x in near) if (x !in seen) seen[x] = rowSharpness(fine, x)
            var a1 = near[0]; var b1 = seen.getValue(a1)
            for (x in near) { val v = seen.getValue(x); if (v > b1) { b1 = v; a1 = x } }
            var best = a1 - 0.5; var bv = Double.NEGATIVE_INFINITY
            for (k in 0..10) { // np.arange(a1 - 0.5, a1 + 0.5 + 1e-6, 0.1)
                val x = npArange(a1 - 0.5, 0.1, k)
                val v = rowSharpness(fine, x)
                if (v > bv) { bv = v; best = x }
            }
            return best
        } finally {
            fine.release()
        }
    }

    /** Página girada [degrees] (antihorario) sobre su centro, mismo tamaño, borde replicado (rotate_page). */
    fun rotatePage(g: Gray, degrees: Double): Gray {
        val src = g.toMat(); val dst = Mat()
        val m = Imgproc.getRotationMatrix2D(Point(g.w / 2.0, g.h / 2.0), degrees, 1.0)
        Imgproc.warpAffine(src, dst, m, Size(g.w.toDouble(), g.h.toDouble()), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE, Scalar(0.0))
        return Gray.fromMat(dst).also { src.release(); dst.release(); m.release() }
    }

    /** np.rot90(g, k): k > 0 antihorario, k < 0 horario. */
    fun rot90(g: Gray, k: Int): Gray {
        val kk = ((k % 4) + 4) % 4
        if (kk == 0) return g
        val w = g.w; val h = g.h
        return when (kk) {
            1 -> { // antihorario: out(x', y') con out W=h, H=w; out[y'][x'] = in[x'][w-1-y']
                val o = ByteArray(w * h)
                for (yy in 0 until w) for (xx in 0 until h) o[yy * h + xx] = g.px[xx * w + (w - 1 - yy)]
                Gray(h, w, o)
            }
            2 -> {
                val o = ByteArray(w * h)
                for (i in 0 until w * h) o[i] = g.px[w * h - 1 - i]
                Gray(w, h, o)
            }
            else -> { // horario: out[y'][x'] = in[h-1-x'][y']
                val o = ByteArray(w * h)
                for (yy in 0 until w) for (xx in 0 until h) o[yy * h + xx] = g.px[(h - 1 - xx) * w + yy]
                Gray(h, w, o)
            }
        }
    }

    /** Caja de la página girada por rotate_page(.., degrees) devuelta a la página anterior (unrotate_box). */
    fun unrotateBox(b: FloatArray, degrees: Double, w: Int, h: Int): FloatArray {
        val rad = Math.toRadians(-degrees)
        val al = Math.cos(rad); val be = Math.sin(rad)
        val cx = w / 2.0; val cy = h / 2.0
        // getRotationMatrix2D(center, angle, 1): [[a, b, (1-a)cx - b cy], [-b, a, b cx + (1-a) cy]]
        val tx = (1 - al) * cx - be * cy; val ty = be * cx + (1 - al) * cy
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for ((px, py) in listOf(b[0] to b[1], b[2] to b[1], b[0] to b[3], b[2] to b[3])) {
            val X = al * px + be * py + tx
            val Y = -be * px + al * py + ty
            x0 = min(x0, X); x1 = max(x1, X); y0 = min(y0, Y); y1 = max(y1, Y)
        }
        fun r1(v: Double, hi: Int) = (Math.round(v.coerceIn(0.0, hi.toDouble()) * 10) / 10.0).toFloat()
        return floatArrayOf(r1(x0, w), r1(y0, h), r1(x1, w), r1(y1, h))
    }

    /** Entrada de orient.onnx: [side, side] tinta (1 = tinta), lado largo a [side], relleno (orient_input). */
    fun orientInput(g: Gray, side: Int = ORIENT_SIDE): FloatArray {
        val s = side.toDouble() / max(g.w, g.h)
        val small = resize(g, max(1, pyRound(g.w * s)), max(1, pyRound(g.h * s)), Imgproc.INTER_AREA)
        val out = FloatArray(side * side)
        for (y in 0 until min(side, small.h)) for (x in 0 until min(side, small.w)) out[y * side + x] = 1f - small[x, y] / 255f
        return out
    }

    /** Entrada del detector (page_to_input): lado largo a [longSide], relleno a múltiplo de 32. Devuelve (mapa, pw, ph, escala). */
    class DetInput(val data: FloatArray, val w: Int, val h: Int, val scale: Double)

    fun pageToInput(g: Gray, longSide: Int): DetInput {
        val scale = longSide.toDouble() / max(g.w, g.h)
        val nh = max(1, pyRound(g.h * scale)); val nw = max(1, pyRound(g.w * scale))
        val r = if (nh != g.h || nw != g.w) resize(g, nw, nh, if (scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR) else g
        val ph = ceil(nh / INPUT_MULTIPLE.toDouble()).toInt() * INPUT_MULTIPLE
        val pw = ceil(nw / INPUT_MULTIPLE.toDouble()).toInt() * INPUT_MULTIPLE
        val out = FloatArray(ph * pw)
        for (y in 0 until nh) for (x in 0 until nw) out[y * pw + x] = 1f - r[x, y] / 255f
        return DetInput(out, pw, ph, scale)
    }

    // ------------------------------------------------------------------ recortes de línea (rec_preproc.py)

    private fun percentiles(g: Gray, x0: Int, y0: Int, x1: Int, y1: Int): DoubleArray {
        val hist = LongArray(256)
        var n = 0L
        for (y in y0 until y1) for (x in x0 until x1) { hist[g[x, y]]++; n++ }
        val cum = LongArray(256)
        var c = 0L
        for (i in 0 until 256) { c += hist[i]; cum[i] = c }
        fun searchRight(v: Long): Double { // np.searchsorted(cum, v, side="right")
            var lo = 0; var hi = 256
            while (lo < hi) { val m = (lo + hi) ushr 1; if (cum[m] <= v) lo = m + 1 else hi = m }
            return lo.toDouble()
        }
        return doubleArrayOf(0.02, 0.5, 0.98).map { q ->
            val x = q * (n - 1)
            val i = floor(x).toLong()
            val gg = x - i
            val lo = searchRight(i)
            val hi = searchRight(min(i + 1, n - 1))
            val d = hi - lo
            if (gg >= 0.5) hi - d * (1 - gg) else lo + d * gg
        }.toDoubleArray()
    }

    /** Texto claro sobre fondo oscuro (is_dark_line). */
    fun isDarkLine(g: Gray, box: FloatArray): Boolean {
        val x0 = max(0, box[0].toInt()); val y0 = max(0, box[1].toInt())
        val x1 = min(g.w, box[2].toInt() + 1); val y1 = min(g.h, box[3].toInt() + 1)
        if (x1 <= x0 || y1 <= y0) return false
        val (lo, med, hi) = percentiles(g, x0, y0, x1, y1).let { Triple(it[0], it[1], it[2]) }
        return hi - med > 2 * (med - lo) + 8
    }

    /** Recorte de una línea a [height]·[window] px de alto como mapa de tinta (crop_line). Devuelve (datos, ancho). */
    class Crop(val data: FloatArray, val w: Int, val h: Int)

    fun cropLine(g: Gray, box: FloatArray, height: Int, window: Int = 1, dark: Boolean? = null, maxWidth: Int = 4096): Crop {
        val padY = (window * (1 + 2 * PAD_Y) - 1) / 2
        val hh = height * window
        val x0 = box[0].toDouble(); val y0 = box[1].toDouble(); val x1 = box[2].toDouble(); val y1 = box[3].toDouble()
        val h = max(1.0, y1 - y0)
        val X0 = x0 - PAD_X * h; val X1 = x1 + PAD_X * h
        val Y0 = y0 - padY * h; val Y1 = y1 + padY * h
        val ix0 = floor(X0).toInt(); val iy0 = floor(Y0).toInt()
        val ix1 = ceil(X1).toInt(); val iy1 = ceil(Y1).toInt()
        val cw = max(1, ix1 - ix0); val ch = max(1, iy1 - iy0)
        val sx0 = max(0, ix0); val sy0 = max(0, iy0); val sx1 = min(g.w, ix1); val sy1 = min(g.h, iy1)
        val dk = dark ?: isDarkLine(g, box)
        val crop = ByteArray(cw * ch) { if (dk) 0 else 255.toByte() }
        if (sx1 > sx0 && sy1 > sy0) {
            for (y in sy0 until sy1) System.arraycopy(g.px, y * g.w + sx0, crop, (y - iy0) * cw + (sx0 - ix0), sx1 - sx0)
        }
        val outW = max(WIDTH_MULTIPLE, min(maxWidth, pyRound(cw * hh.toDouble() / ch)))
        val interp = if (ch > hh) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
        val r = resize(Gray(cw, ch, crop), outW, hh, interp)
        val out = FloatArray(outW * hh)
        for (i in out.indices) {
            val v = (r.px[i].toInt() and 0xFF) / 255f
            out[i] = if (dk) v else 1f - v
        }
        return Crop(out, outW, hh)
    }

    /** Lotes de anchos parecidos (width_batches). */
    fun widthBatches(widths: IntArray, maxBatch: Int = 64, maxRatio: Double = 1.3, minWidth: Int = 64, maxArea: Int = 64 * 640): List<List<Int>> {
        val order = widths.indices.sortedWith(compareBy<Int> { widths[it] }.thenBy { it })
        val batches = ArrayList<MutableList<Int>>()
        for (i in order) {
            val last = batches.lastOrNull()
            if (last != null && last.size < maxBatch &&
                max(widths[i], minWidth) <= maxRatio * max(widths[last[0]], minWidth) &&
                (last.size + 1) * max(widths[i], minWidth) <= maxArea
            ) last += i else batches += mutableListOf(i)
        }
        return batches
    }
}
