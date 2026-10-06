package com.scannerpromax.imaging

import android.graphics.Bitmap
import com.scannerpromax.domain.EraseMode
import com.scannerpromax.domain.EraseStroke
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Cleanup {

    /** Quita rayas/líneas largas aisladas (dobleces, marcas, bordes de hoja) preservando texto. */
    fun removeLines(src: Bitmap): Bitmap = viaMat(src) { removeLinesMat(it) }

    /** Quita ruido de sal y pimienta, puntos y motas, preservando bordes de letras. */
    fun denoise(src: Bitmap): Bitmap = viaMat(src) { denoiseMat(it) }

    /** Endereza unos grados el contenido según la orientación dominante de las líneas de texto. */
    fun deskew(src: Bitmap): Bitmap = viaMat(src) { deskewMat(it) }

    /** Aplica los trazos de borrado manual (inpainting o blanco) sobre [src]. */
    fun applyEraseStrokes(src: Bitmap, strokes: List<EraseStroke>): Bitmap =
        viaMat(src) { applyEraseStrokesMat(it, strokes) }

    /** Ángulo (grados) que [deskew] aplicaría; 0 si no hace falta. Útil para la UI. */
    fun estimateSkewAngle(src: Bitmap): Float {
        val m = Cv.toRgb(src)
        try { return estimateSkew(m).toFloat() } finally { m.release() }
    }

    private inline fun viaMat(src: Bitmap, op: (Mat) -> Mat): Bitmap {
        val m = Cv.toRgb(src)
        val out = try { op(m) } finally { m.release() }
        val bmp = Cv.toBitmap(out)
        out.release()
        return bmp
    }

    // =====================================================================================
    // Rayas / líneas
    // =====================================================================================

    /**
     * 1) Máscara de tinta (umbral adaptativo). 2) Candidatas: apertura morfológica con kernels largos
     * (horizontal/vertical) y HoughLinesP (cualquier ángulo), ambas a resolución reducida (~1200 px, mucho
     * más barato que kernels de w/14 a 12 MP). 3) Se CONSERVAN las estructuras del documento: líneas que forman
     * rejilla (tablas), subrayados (texto justo encima) y renglones de formulario (≥3 paralelas semejantes).
     * 4) Las líneas de Hough se verifican a resolución completa sobre la tinta sin fusionar (cobertura continua,
     * tramos largos): los renglones de texto grueso no pasan. 5) Relleno con inpainting TELEA por regiones.
     * Entrada 8UC1 u 8UC3; devuelve Mat nuevo del mismo tipo.
     */
    internal fun removeLinesMat(img: Mat): Mat = MatBag().use { bag ->
        val w = img.cols(); val h = img.rows()
        val out = img.clone()
        if (w < 32 || h < 32) return@use out
        val gray = bag.add(Cv.gray(img))
        val long = max(w, h)
        val ink = bag.mat()
        Imgproc.adaptiveThreshold(
            gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
            Cv.odd(max(15, long / 50)), 12.0,
        )
        gray.release()
        val maxThick = max(3.0, long * 0.006)

        // --- Tinta a resolución reducida (común a morfología y Hough) ---
        val s = min(1.0, 1200.0 / long)
        val inkS = bag.mat()
        if (s < 1.0) {
            Imgproc.resize(ink, inkS, Size(max(1.0, (w * s).roundToInt().toDouble()), max(1.0, (h * s).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.threshold(inkS, inkS, 50.0, 255.0, Imgproc.THRESH_BINARY)
        } else ink.copyTo(inkS)
        val sw = inkS.cols(); val sh = inkS.rows()
        val bytes = ByteArray(sw * sh)
        inkS.get(0, 0, bytes)
        val maxThickS = maxThick * s + 1.0

        // --- Morfología: líneas exactamente horizontales/verticales ---
        val hLen = max(12, sw / 14); val vLen = max(12, sh / 14)
        val hm = bag.mat(); val vm = bag.mat()
        Imgproc.morphologyEx(inkS, hm, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_RECT, hLen, 1))
        Imgproc.morphologyEx(inkS, vm, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_RECT, 1, vLen))
        // Intersecciones h·v (dilatadas): indican rejilla/tabla
        val inter = bag.mat(); val tmp = bag.mat()
        Imgproc.dilate(hm, inter, Cv.kernel(Imgproc.MORPH_RECT, 5))
        Imgproc.dilate(vm, tmp, Cv.kernel(Imgproc.MORPH_RECT, 5))
        Core.bitwise_and(inter, tmp, inter)
        val interBytes = ByteArray(sw * sh); inter.get(0, 0, interBytes)
        val interI = integralOf(interBytes, sw, sh)
        val inkI = integralOf(bytes, sw, sh)
        fun rsum(ii: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val a0 = x0.coerceIn(0, sw); val b0 = y0.coerceIn(0, sh); val a1 = x1.coerceIn(a0, sw); val b1 = y1.coerceIn(b0, sh)
            val W = sw + 1
            return ii[b1 * W + a1] - ii[b0 * W + a1] - ii[b1 * W + a0] + ii[b0 * W + a0]
        }

        class Cand(val r: Rect, val horizontal: Boolean)
        val cands = ArrayList<Cand>()
        for ((mask, horizontal) in listOf(hm to true, vm to false)) {
            val cs = ArrayList<MatOfPoint>()
            val hier = bag.mat()
            Imgproc.findContours(mask, cs, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            for (c in cs) {
                val r = Imgproc.boundingRect(c)
                val thick = if (horizontal) r.height else r.width
                val len = if (horizontal) r.width else r.height
                if (thick <= maxThickS && len >= (if (horizontal) hLen else vLen)) cands.add(Cand(r, horizontal))
                c.release()
            }
        }
        val lineGap = max(4, (long / 60.0 * s).roundToInt()) // ~ un interlineado
        val structural = BooleanArray(cands.size)
        for ((i, c) in cands.withIndex()) {
            val r = c.r
            // Rejilla: cruza alguna línea perpendicular
            if (rsum(interI, r.x - 3, r.y - 3, r.x + r.width + 3, r.y + r.height + 3) > 0) { structural[i] = true; continue }
            if (c.horizontal) {
                // Subrayado / línea de formulario rellenada: texto pegado por encima
                val y0 = r.y - lineGap; val y1 = r.y - 1
                val area = max(1, r.width * (y1 - max(0, y0)))
                val frac = rsum(inkI, r.x, y0, r.x + r.width, y1).toDouble() / area
                if (frac > 0.06 && r.width < 0.75 * sw) { structural[i] = true; continue }
            }
        }
        // Renglones de formulario / pautas: >= 3 paralelas de longitud y posición semejantes
        for ((i, c) in cands.withIndex()) {
            if (structural[i]) continue
            var similar = 0
            for ((j, o) in cands.withIndex()) {
                if (i == j || o.horizontal != c.horizontal) continue
                val la = if (c.horizontal) c.r.width else c.r.height
                val lb = if (o.horizontal) o.r.width else o.r.height
                if (lb < 0.75 * la || lb > 1.33 * la) continue
                val a0 = if (c.horizontal) c.r.x else c.r.y; val a1 = a0 + la
                val b0 = if (o.horizontal) o.r.x else o.r.y; val b1 = b0 + lb
                val ov = min(a1, b1) - max(a0, b0)
                if (ov >= 0.6 * min(la, lb)) similar++
            }
            if (similar >= 2) structural[i] = true
        }
        val lineMaskS = bag.mat(); lineMaskS.create(sh, sw, CvType.CV_8UC1); lineMaskS.setTo(Scalar(0.0))
        val keepS = bag.mat(); keepS.create(sh, sw, CvType.CV_8UC1); keepS.setTo(Scalar(0.0))
        for ((i, c) in cands.withIndex()) {
            val r = c.r
            Imgproc.rectangle(
                if (structural[i]) keepS else lineMaskS,
                Point(r.x.toDouble(), r.y.toDouble()), Point((r.x + r.width - 1).toDouble(), (r.y + r.height - 1).toDouble()),
                Scalar(255.0), -1,
            )
        }
        // La máscara "rectángulo" incluye tinta pegada: se recorta luego con la tinta real
        val hv = bag.mat(); Core.bitwise_or(hm, vm, hv)
        Core.bitwise_and(lineMaskS, hv, lineMaskS)
        hv.release()
        Imgproc.dilate(keepS, keepS, Cv.kernel(Imgproc.MORPH_RECT, 5))
        val keepBytes = ByteArray(sw * sh); keepS.get(0, 0, keepBytes)
        hm.release(); vm.release(); inter.release(); tmp.release()

        // --- Hough: líneas inclinadas/diagonales (verificadas a resolución completa) ---
        val lineMask = bag.mat()
        lineMask.create(h, w, CvType.CV_8UC1); lineMask.setTo(Scalar(0.0))
        val lines = bag.mat()
        val minLen = 0.25 * min(sw, sh)
        Imgproc.HoughLinesP(inkS, lines, 1.0, Math.PI / 180.0, max(60, (minLen * 0.6).roundToInt()), minLen, 4.0)
        val seg = IntArray(4)
        val count = min(lines.rows(), 400)
        val strip = bag.mat()
        var verified = 0
        val tol = kotlin.math.ceil(1.0 / s).toInt() + kotlin.math.ceil(maxThick / 2).toInt() + 1
        for (i in 0 until count) {
            lines.get(i, 0, seg)
            if (onMask(keepBytes, sw, sh, seg) > 0.5) continue      // es parte de una tabla/estructura conservada
            val t = lineThickness(bytes, sw, sh, seg)
            if (t < 0 || t > maxThickS) continue
            if (verified >= 150) break
            verified++
            if (!continuousAtFullRes(ink, seg[0] / s, seg[1] / s, seg[2] / s, seg[3] / s, tol, strip)) continue
            Imgproc.line(
                lineMask,
                Point(seg[0] / s, seg[1] / s), Point(seg[2] / s, seg[3] / s),
                Scalar(255.0), max(2, ((t + 2) / s).roundToInt()),
            )
        }
        // Morfológicas: subir la máscara reducida a resolución completa
        if (Core.countNonZero(lineMaskS) > 0) {
            Imgproc.dilate(lineMaskS, lineMaskS, Cv.kernel(Imgproc.MORPH_RECT, 3))
            val up = bag.mat()
            Imgproc.resize(lineMaskS, up, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            Core.bitwise_or(lineMask, up, lineMask)
            up.release()
        }
        // Sólo píxeles que realmente son tinta (más un halo por el antialiasing)
        val inkD = bag.mat()
        Imgproc.dilate(ink, inkD, Cv.kernel(Imgproc.MORPH_RECT, 3))
        ink.release()
        Core.bitwise_and(lineMask, inkD, lineMask)
        inkD.release()
        if (Core.countNonZero(lineMask) == 0) return@use out
        Imgproc.dilate(lineMask, lineMask, Cv.kernel(Imgproc.MORPH_ELLIPSE, 3))
        fillMasked(out, lineMask, radius = 3.0)
        out
    }

    /** Fracción de puntos del segmento (coords reducidas) que caen sobre [mask]. */
    private fun onMask(mask: ByteArray, w: Int, h: Int, seg: IntArray): Double {
        val len = hypot((seg[2] - seg[0]).toDouble(), (seg[3] - seg[1]).toDouble())
        val steps = max(2, len.toInt())
        var hit = 0; var tot = 0
        for (k in 0..steps) {
            val t = k.toDouble() / steps
            val x = (seg[0] + (seg[2] - seg[0]) * t).roundToInt(); val y = (seg[1] + (seg[3] - seg[1]) * t).roundToInt()
            if (x !in 0 until w || y !in 0 until h) continue
            tot++
            if (mask[y * w + x].toInt() != 0) hit++
        }
        return if (tot == 0) 0.0 else hit.toDouble() / tot
    }

    /**
     * Verifica a resolución completa que el segmento es una línea continua de tinta: extrae una franja
     * alineada con la línea (un warpAffine pequeño, sin copiar la imagen) y exige cobertura alta, huecos
     * cortos y tramos largos (un renglón de texto tiene muchos huecos entre letras y palabras).
     */
    private fun continuousAtFullRes(ink: Mat, x0: Double, y0: Double, x1: Double, y1: Double, tol: Int, strip: Mat): Boolean {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 20) return false
        val l = len.roundToInt()
        val hgt = 2 * tol + 1
        val dx = (x1 - x0) / len; val dy = (y1 - y0) / len
        val nx = -dy; val ny = dx
        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, dx, nx, x0 - tol * nx, dy, ny, y0 - tol * ny)
        try {
            Imgproc.warpAffine(
                ink, strip, m, Size(l.toDouble(), hgt.toDouble()),
                Imgproc.INTER_NEAREST or Imgproc.WARP_INVERSE_MAP, Core.BORDER_CONSTANT, Scalar(0.0),
            )
        } finally {
            m.release()
        }
        val b = ByteArray(l * hgt)
        strip.get(0, 0, b)
        val hitCol = BooleanArray(l)
        for (v in 0 until hgt) { val base = v * l; for (u in 0 until l) if (b[base + u].toInt() != 0) hitCol[u] = true }
        // Cerrar micro-huecos (antialiasing / JPEG)
        val smallGap = max(3, l / 200)
        var u = 0
        var hits = 0; var runs = 0; var runLenSum = 0; var maxGap = 0
        while (u < l) {
            if (hitCol[u]) {
                var e = u
                while (true) {
                    while (e < l && hitCol[e]) e++
                    var g = e
                    while (g < l && !hitCol[g]) g++
                    if (g < l && g - e <= smallGap) e = g else break
                }
                runs++; runLenSum += e - u; hits += e - u
                u = e
            } else {
                var g = u
                while (g < l && !hitCol[g]) g++
                maxGap = max(maxGap, g - u)
                u = g
            }
        }
        if (runs == 0) return false
        val coverage = hits.toDouble() / l
        val meanRun = runLenSum.toDouble() / runs
        return coverage >= 0.8 && meanRun >= 0.2 * l && maxGap <= max(8, (0.08 * l).roundToInt())
    }

    /**
     * Grosor (en px) de la línea [seg] en la máscara binaria; -1 si no es una línea fina y continua.
     * Se mide la cobertura sobre la línea y a distancias perpendiculares crecientes.
     */
    private fun lineThickness(bytes: ByteArray, w: Int, h: Int, seg: IntArray): Double {
        val x0 = seg[0].toDouble(); val y0 = seg[1].toDouble(); val x1 = seg[2].toDouble(); val y1 = seg[3].toDouble()
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 10) return -1.0
        val nx = -(y1 - y0) / len; val ny = (x1 - x0) / len
        val steps = len.toInt()
        fun coverage(off: Double): Double {
            var hit = 0; var tot = 0
            for (k in 0..steps step 1) {
                val t = k.toDouble() / steps
                val x = (x0 + (x1 - x0) * t + nx * off).roundToInt()
                val y = (y0 + (y1 - y0) * t + ny * off).roundToInt()
                if (x !in 0 until w || y !in 0 until h) continue
                tot++
                if (bytes[y * w + x].toInt() != 0) hit++
            }
            return if (tot == 0) 0.0 else hit.toDouble() / tot
        }
        if (coverage(0.0) < 0.85) return -1.0
        // Ampliar hacia ambos lados mientras siga habiendo cobertura alta
        var thick = 1.0
        var d = 1.0
        while (d <= 12) {
            val a = coverage(d); val b = coverage(-d)
            if (a > 0.6 || b > 0.6) thick += 1.0 else break
            d += 1.0
        }
        // Lado "exterior" de la línea: debe ser mayormente papel (descarta renglones de texto)
        val outer = max(coverage(d + 2), coverage(-d - 2))
        if (outer > 0.35) return -1.0
        return thick
    }

    /**
     * Rellena los píxeles de [mask] en [img] (in-place): inpainting TELEA por componentes (región acotada),
     * o color de fondo estimado para regiones muy grandes (barato en gama baja).
     */
    private fun fillMasked(img: Mat, mask: Mat, radius: Double) {
        val w = img.cols(); val h = img.rows()
        val cs = ArrayList<MatOfPoint>()
        val hier = Mat()
        val mcopy = mask.clone()
        Imgproc.findContours(mcopy, cs, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mcopy.release(); hier.release()
        var bg: Mat? = null
        val pad = 8
        try {
            for ((i, c) in cs.withIndex()) {
                val r0 = Imgproc.boundingRect(c)
                val r = Cv.clampRect(Rect(r0.x - pad, r0.y - pad, r0.width + 2 * pad, r0.height + 2 * pad), w, h)
                if (r.width <= 2 || r.height <= 2) continue
                val roiImg = img.submat(r); val roiMask = mask.submat(r)
                try {
                    if (r.area() <= 3_000_000 && i < 300) {
                        val res = Mat()
                        Photo.inpaint(roiImg, roiMask, res, radius, Photo.INPAINT_TELEA)
                        res.copyTo(roiImg, roiMask)
                        res.release()
                    } else {
                        val b = bg ?: Cv.estimateBackground(img).also { bg = it }
                        val roiBg = b.submat(r)
                        roiBg.copyTo(roiImg, roiMask)
                        roiBg.release()
                    }
                } finally {
                    roiImg.release(); roiMask.release()
                }
            }
        } finally {
            for (c in cs) c.release()
            bg?.release()
        }
    }

    // =====================================================================================
    // Ruido / motas
    // =====================================================================================

    /**
     * Motas aisladas: componentes pequeños de la máscara de tinta SIN tinta "grande" cerca
     * (así se conservan puntos de la i, tildes y signos de puntuación) y rodeados de papel -> color de fondo.
     *
     * Implementación barata en gama baja: connectedComponentsWithStats (estadísticas leídas de una vez),
     * las consultas de vecindad se hacen con imágenes integrales calculadas en Kotlin a resolución reducida
     * (sin una llamada JNI por componente) y el fondo sólo se estima si hay algo que borrar.
     * NUNCA se aplica mediana a imágenes binarias: borraría trazos de 1-2 px (firmas, lápiz, letra pequeña).
     */
    internal fun denoiseMat(img: Mat): Mat = MatBag().use { bag ->
        val out = img.clone()
        val w = img.cols(); val h = img.rows()
        if (w < 32 || h < 32) return@use out
        val long = max(w, h)
        val gray = bag.add(Cv.gray(img))
        val ink = bag.mat()
        Imgproc.adaptiveThreshold(
            gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
            Cv.odd(max(15, long / 40)), 14.0,
        )
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nLab = Imgproc.connectedComponentsWithStats(ink, labels, stats, cents, 8, CvType.CV_32S)
        cents.release()
        if (nLab <= 1 || nLab > 400_000) { labels.release(); return@use finishDenoise(out) }
        val st = IntArray(nLab * 5)
        stats.get(0, 0, st)
        stats.release()
        val sp = max(3.0, long / 400.0)          // tamaño máx. de una mota
        val isSmall = BooleanArray(nLab)
        var nSmall = 0
        for (i in 1 until nLab) {
            if (st[i * 5 + Imgproc.CC_STAT_WIDTH] <= sp && st[i * 5 + Imgproc.CC_STAT_HEIGHT] <= sp) { isSmall[i] = true; nSmall++ }
        }
        if (nSmall == 0) { labels.release(); return@use finishDenoise(out) }

        // Máscara de tinta "grande" (fila a fila: memoria Java O(ancho))
        val bigMask = bag.mat(); bigMask.create(h, w, CvType.CV_8UC1)
        val lrow = IntArray(w); val brow = ByteArray(w)
        for (y in 0 until h) {
            labels.get(y, 0, lrow)
            for (x in 0 until w) { val l = lrow[x]; brow[x] = if (l != 0 && !isSmall[l]) -1 else 0 }
            bigMask.put(y, 0, brow)
        }
        // Consultas a resolución reducida (conservador: reducir con INTER_AREA conserva cualquier tinta)
        val s = min(1.0, 1024.0 / long)
        val qw = max(1, (w * s).roundToInt()); val qh = max(1, (h * s).roundToInt())
        val d = Cv.odd(max(5, long / 150))
        val dS = Cv.odd(max(1, (d * s).roundToInt()))
        val bigS = bag.mat()
        Imgproc.resize(bigMask, bigS, Size(qw.toDouble(), qh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        bigMask.release()
        Imgproc.threshold(bigS, bigS, 0.0, 255.0, Imgproc.THRESH_BINARY)
        if (dS > 1) Imgproc.dilate(bigS, bigS, Cv.kernel(Imgproc.MORPH_ELLIPSE, dS))
        // Papel: brillo normalizado alto (no borrar "motas" dentro de fotos o bloques de color)
        val gS = bag.mat()
        Imgproc.resize(gray, gS, Size(qw.toDouble(), qh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bgS = bag.add(Cv.estimateBackground(gS))
        val normS = bag.mat(); Cv.divideByBackground(gS, bgS, normS)
        val paperS = bag.mat()
        Imgproc.threshold(normS, paperS, Cv.PAPER_LEVEL * 0.8, 255.0, Imgproc.THRESH_BINARY)
        val bigI = integralOf(bigS, qw, qh)
        val papI = integralOf(paperS, qw, qh)
        fun sum(ii: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Int {
            // Rectángulo [x0,x1) x [y0,y1) en coordenadas reducidas
            val W = qw + 1
            return ii[y1 * W + x1] - ii[y0 * W + x1] - ii[y1 * W + x0] + ii[y0 * W + x0]
        }
        val remove = BooleanArray(nLab)
        var nRemove = 0
        for (i in 1 until nLab) {
            if (!isSmall[i]) continue
            val o = i * 5
            val rx = st[o + Imgproc.CC_STAT_LEFT]; val ry = st[o + Imgproc.CC_STAT_TOP]
            val rw = st[o + Imgproc.CC_STAT_WIDTH]; val rh = st[o + Imgproc.CC_STAT_HEIGHT]
            val x0 = (rx * s).toInt().coerceIn(0, qw - 1); val y0 = (ry * s).toInt().coerceIn(0, qh - 1)
            val x1 = kotlin.math.ceil((rx + rw) * s).toInt().coerceIn(x0 + 1, qw)
            val y1 = kotlin.math.ceil((ry + rh) * s).toInt().coerceIn(y0 + 1, qh)
            if (sum(bigI, x0, y0, x1, y1) != 0) continue
            val ex = max(1, (d * s).roundToInt())
            val ax0 = max(0, x0 - ex); val ay0 = max(0, y0 - ex)
            val ax1 = min(qw, x1 + ex); val ay1 = min(qh, y1 + ex)
            val area = (ax1 - ax0) * (ay1 - ay0)
            if (area <= 0) continue
            val paperFrac = sum(papI, ax0, ay0, ax1, ay1).toDouble() / area
            if (paperFrac >= 0.7) { remove[i] = true; nRemove++ }
        }
        if (nRemove > 0) {
            val rm = bag.mat(); rm.create(h, w, CvType.CV_8UC1)
            for (y in 0 until h) {
                labels.get(y, 0, lrow)
                for (x in 0 until w) brow[x] = if (remove[lrow[x]]) -1 else 0
                rm.put(y, 0, brow)
            }
            labels.release()
            Imgproc.dilate(rm, rm, Cv.kernel(Imgproc.MORPH_RECT, 3))
            val bg = bag.add(Cv.estimateBackground(out))
            bg.copyTo(out, rm)
        }
        labels.release()
        finishDenoise(out)
    }

    /** Sal y pimienta residual sólo en imágenes en color muy ruidosas (nunca en binarias ni en gris). */
    private fun finishDenoise(out: Mat): Mat {
        if (out.channels() >= 3 && Cv.estimatePaperNoise(out) > 5.0) Imgproc.medianBlur(out, out, 3)
        return out
    }

    /** Imagen integral (w+1)x(h+1) de una máscara 0/255 contando píxeles no nulos. */
    private fun integralOf(mask: Mat, w: Int, h: Int): IntArray {
        val bytes = ByteArray(w * h)
        mask.get(0, 0, bytes)
        return integralOf(bytes, w, h)
    }

    private fun integralOf(bytes: ByteArray, w: Int, h: Int): IntArray {
        val W = w + 1
        val ii = IntArray(W * (h + 1))
        for (y in 0 until h) {
            var row = 0
            val base = y * w
            for (x in 0 until w) {
                if (bytes[base + x].toInt() != 0) row++
                ii[(y + 1) * W + x + 1] = ii[y * W + x + 1] + row
            }
        }
        return ii
    }

    // =====================================================================================
    // Enderezado
    // =====================================================================================

    /** Corrige la inclinación si 0.3° <= |ángulo| <= 10°. Devuelve Mat nuevo. */
    internal fun deskewMat(img: Mat): Mat {
        val angle = estimateSkew(img)
        if (angle == 0.0) return img.clone()
        return rotateKeepSize(img, angle)
    }

    /**
     * Ángulo dominante del texto por perfil de proyección: se rota la máscara de tinta (a 800 px fijos,
     * así el resultado es idéntico en vista previa y render final) y se maximiza la varianza de las sumas
     * por fila. Búsqueda gruesa (0.5°) + fina (0.1°). Devuelve 0 si no hay texto suficiente o no hace falta.
     */
    internal fun estimateSkew(img: Mat): Double = MatBag().use { bag ->
        val sm = bag.mat()
        Cv.downscale(img, sm, 800)
        val g = bag.add(Cv.gray(sm))
        if (g.cols() < 64 || g.rows() < 64) return@use 0.0
        val ink = bag.mat()
        Imgproc.adaptiveThreshold(g, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
        val frac = Core.countNonZero(ink).toDouble() / (ink.total().toDouble())
        if (frac < 0.005 || frac > 0.4) return@use 0.0
        // Recorte central (evita bordes/sombras del margen)
        val mx = ink.cols() / 12; val my = ink.rows() / 12
        val core = bag.add(ink.submat(my, ink.rows() - my, mx, ink.cols() - mx))
        val rot = bag.mat(); val rows = bag.mat()
        val center = Point(core.cols() / 2.0, core.rows() / 2.0)
        val size = core.size()
        fun score(a: Double): Double {
            val m = Imgproc.getRotationMatrix2D(center, a, 1.0)
            Imgproc.warpAffine(core, rot, m, size, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
            m.release()
            Core.reduce(rot, rows, 1, Core.REDUCE_SUM, CvType.CV_32F)
            val f = FloatArray(rows.rows())
            rows.get(0, 0, f)
            // Varianza de las diferencias entre filas consecutivas: pico nítido cuando los renglones están rectos
            var s = 0.0
            for (i in 1 until f.size) { val d = (f[i] - f[i - 1]).toDouble(); s += d * d }
            return s
        }
        val s0 = score(0.0)
        var bestA = 0.0; var bestS = s0
        var a = -10.0
        while (a <= 10.0001) {
            if (abs(a) > 1e-6) { val sc = score(a); if (sc > bestS) { bestS = sc; bestA = a } }
            a += 0.5
        }
        var fine = bestA - 0.4
        val coarse = bestA
        while (fine <= coarse + 0.4001) {
            val sc = score(fine); if (sc > bestS) { bestS = sc; bestA = fine }
            fine += 0.1
        }
        if (s0 <= 0) return@use 0.0
        val gain = bestS / s0
        if (abs(bestA) < 0.3 || abs(bestA) > 10.0 || gain < 1.05) 0.0 else bestA
    }

    /** Rota [img] [angle] grados (convención OpenCV, positivo = antihorario) sin cambiar tamaño; rellena con color de papel. */
    internal fun rotateKeepSize(img: Mat, angle: Double): Mat {
        val center = Point(img.cols() / 2.0, img.rows() / 2.0)
        val m = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val fillColor = Cv.paperColor(img)
        val out = Mat()
        Imgproc.warpAffine(img, out, m, img.size(), Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, fillColor)
        m.release()
        return out
    }

    // =====================================================================================
    // Borrado manual
    // =====================================================================================

    /**
     * Trazos normalizados (0..1) -> máscara con líneas gruesas de extremos redondeados.
     * HEAL: inpainting TELEA en la región del trazo (a resolución reducida si la región es grande, luego se compone).
     * WHITE: color de fondo local estimado (máximo local = papel alrededor), sin manchas.
     */
    internal fun applyEraseStrokesMat(img: Mat, strokes: List<EraseStroke>): Mat {
        val out = img.clone()
        if (strokes.isEmpty()) return out
        val w = img.cols(); val h = img.rows()
        for (mode in EraseMode.entries) {
            val group = strokes.filter { it.mode == mode && it.points.isNotEmpty() }
            if (group.isEmpty()) continue
            val mask = Mat(h, w, CvType.CV_8UC1, Scalar(0.0))
            var maxR = 1
            try {
                for (st in group) {
                    val r = max(1, (st.radius * w).roundToInt())
                    maxR = max(maxR, r)
                    val pts = st.points.map { Point((it.x * w).toDouble(), (it.y * h).toDouble()) }
                    if (pts.size == 1) {
                        Imgproc.circle(mask, pts[0], r, Scalar(255.0), -1)
                    } else {
                        for (i in 1 until pts.size) Imgproc.line(mask, pts[i - 1], pts[i], Scalar(255.0), 2 * r, Imgproc.LINE_8)
                        Imgproc.circle(mask, pts.first(), r, Scalar(255.0), -1)
                        Imgproc.circle(mask, pts.last(), r, Scalar(255.0), -1)
                    }
                }
                if (Core.countNonZero(mask) == 0) continue
                when (mode) {
                    EraseMode.HEAL -> healRegions(out, mask, maxR)
                    EraseMode.WHITE -> whiteRegions(out, mask, maxR)
                }
            } finally {
                mask.release()
            }
        }
        return out
    }

    private fun regionsOf(mask: Mat, pad: Int): List<Rect> {
        val cs = ArrayList<MatOfPoint>()
        val hier = Mat()
        val copy = mask.clone()
        Imgproc.findContours(copy, cs, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        copy.release(); hier.release()
        val rects = cs.map { val r = Imgproc.boundingRect(it); Rect(r.x - pad, r.y - pad, r.width + 2 * pad, r.height + 2 * pad) }
        for (c in cs) c.release()
        return rects.map { Cv.clampRect(it, mask.cols(), mask.rows()) }.filter { it.width > 2 && it.height > 2 }
    }

    private fun healRegions(img: Mat, mask: Mat, maxR: Int) {
        val pad = max(8, maxR * 2)
        for (r in regionsOf(mask, pad)) {
            val roi = img.submat(r); val roiMask = mask.submat(r)
            val bag = MatBag()
            try {
                val area = r.area()
                val limit = 1_200_000.0
                if (area <= limit) {
                    val res = bag.mat()
                    Photo.inpaint(roi, roiMask, res, max(3.0, min(12.0, maxR / 2.0)), Photo.INPAINT_TELEA)
                    res.copyTo(roi, roiMask)
                } else {
                    // Región grande: inpainting a resolución reducida y composición con máscara suavizada
                    val f = sqrt(limit / area)
                    val sz = Size(max(1.0, r.width * f), max(1.0, r.height * f))
                    val sImg = bag.mat(); val sMask = bag.mat(); val sRes = bag.mat(); val up = bag.mat()
                    Imgproc.resize(roi, sImg, sz, 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.resize(roiMask, sMask, sz, 0.0, 0.0, Imgproc.INTER_NEAREST)
                    Imgproc.dilate(sMask, sMask, Cv.kernel(Imgproc.MORPH_RECT, 3))
                    Photo.inpaint(sImg, sMask, sRes, max(3.0, min(12.0, maxR * f / 2.0)), Photo.INPAINT_TELEA)
                    Imgproc.resize(sRes, up, roi.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
                    blendMasked(roi, up, roiMask, bag)
                }
            } finally {
                bag.close(); roi.release(); roiMask.release()
            }
        }
    }

    private fun whiteRegions(img: Mat, mask: Mat, maxR: Int) {
        val pad = max(12, maxR * 3)
        for (r in regionsOf(mask, pad)) {
            val roi = img.submat(r); val roiMask = mask.submat(r)
            val bag = MatBag()
            try {
                // Fondo local: máximo local (quita tinta, también bajo el trazo) a baja resolución + suavizado
                val small = bag.mat()
                val f = Cv.downscale(roi, small, 256)
                val k = Cv.odd(max(5, ((2 * maxR + 6) * f).roundToInt()))
                val dil = bag.mat()
                Imgproc.dilate(small, dil, Cv.kernel(Imgproc.MORPH_ELLIPSE, k))
                Imgproc.GaussianBlur(dil, dil, Size(0.0, 0.0), max(1.0, k / 2.0))
                val bg = bag.mat()
                Imgproc.resize(dil, bg, roi.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                blendMasked(roi, bg, roiMask, bag)
            } finally {
                bag.close(); roi.release(); roiMask.release()
            }
        }
    }

    /** roi = roi*(1-a) + src*a con a = máscara suavizada (bordes del trazo sin escalón). */
    private fun blendMasked(roi: Mat, src: Mat, mask: Mat, bag: MatBag) {
        val a = bag.mat()
        mask.convertTo(a, CvType.CV_32F, 1.0 / 255.0)
        Imgproc.GaussianBlur(a, a, Size(5.0, 5.0), 0.0)
        // Dentro del trazo siempre 1 (no dejar restos)
        a.setTo(Scalar(1.0), mask)
        val ch = roi.channels()
        val aC = bag.mat()
        if (ch == 1) a.copyTo(aC) else Core.merge(List(ch) { a }, aC)
        val rf = bag.mat(); val sf = bag.mat()
        roi.convertTo(rf, CvType.CV_32F); src.convertTo(sf, CvType.CV_32F)
        val inv = bag.mat()
        aC.convertTo(inv, -1, -1.0, 1.0) // inv = 1 - a
        Core.multiply(rf, inv, rf)
        Core.multiply(sf, aC, sf)
        Core.add(rf, sf, rf)
        rf.convertTo(roi, roi.type())
    }
}
