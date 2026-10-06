package com.scannerpromax.imaging

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Bolsa de Mats temporales: todo lo registrado se libera al cerrar.
 * Uso: `MatBag().use { bag -> val m = bag.mat() ... }`. El Mat que se devuelve NO debe estar en la bolsa.
 */
internal class MatBag : AutoCloseable {
    private val mats = ArrayList<Mat>(16)
    fun mat(): Mat = add(Mat())
    fun <M : Mat> add(m: M): M { mats.add(m); return m }
    override fun close() { for (m in mats) m.release(); mats.clear() }
}

/** Utilidades OpenCV compartidas por todo el paquete de imagen. */
internal object Cv {

    fun odd(v: Int): Int = if (v % 2 == 0) v + 1 else v
    fun oddAtLeast(v: Double, minV: Int): Int = odd(max(minV, v.roundToInt()))

    /** Bitmap -> Mat RGBA (8UC4). Convierte configuraciones no soportadas por OpenCV (HARDWARE, F16...). */
    fun toRgba(bmp: Bitmap): Mat {
        val m = Mat()
        if (bmp.config == Bitmap.Config.ARGB_8888 || bmp.config == Bitmap.Config.RGB_565) {
            Utils.bitmapToMat(bmp, m)
        } else {
            val tmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
            Utils.bitmapToMat(tmp, m)
            tmp.recycle()
        }
        return m
    }

    /** Bitmap -> Mat RGB (8UC3), formato de trabajo interno. */
    fun toRgb(bmp: Bitmap): Mat {
        val rgba = toRgba(bmp)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    /** Mat 8UC1/8UC3/8UC4 -> Bitmap ARGB_8888 nuevo. */
    fun toBitmap(m: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, m.cols()), max(1, m.rows()), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(m, bmp)
        return bmp
    }

    /** Escala de grises 8UC1 (nuevo Mat; si ya es gris devuelve copia). */
    fun gray(src: Mat): Mat {
        val g = Mat()
        when (src.channels()) {
            1 -> src.copyTo(g)
            3 -> Imgproc.cvtColor(src, g, Imgproc.COLOR_RGB2GRAY)
            else -> Imgproc.cvtColor(src, g, Imgproc.COLOR_RGBA2GRAY)
        }
        return g
    }

    /** Reduce [src] para que su lado largo sea <= [longSide] (INTER_AREA). Devuelve el factor aplicado. */
    fun downscale(src: Mat, dst: Mat, longSide: Int): Double {
        val ls = max(src.cols(), src.rows())
        if (ls <= longSide) { src.copyTo(dst); return 1.0 }
        val s = longSide.toDouble() / ls
        Imgproc.resize(
            src, dst,
            Size(max(1.0, (src.cols() * s).roundToInt().toDouble()), max(1.0, (src.rows() * s).roundToInt().toDouble())),
            0.0, 0.0, Imgproc.INTER_AREA,
        )
        return s
    }

    // Parámetros constantes de calcHist (se crean una vez; calcHist sólo los lee)
    private val HIST_CH: MatOfInt by lazy { MatOfInt(0) }
    private val HIST_SIZE: MatOfInt by lazy { MatOfInt(256) }
    private val HIST_RANGE: MatOfFloat by lazy { MatOfFloat(0f, 256f) }
    private val EMPTY_MASK: Mat by lazy { Mat() }

    /** Histograma de 256 bins de un Mat 8UC1 (opcionalmente con máscara). Sin asignaciones que dependan del GC. */
    fun histogram(gray: Mat, mask: Mat? = null): DoubleArray {
        val hist = Mat()
        try {
            Imgproc.calcHist(listOf(gray), HIST_CH, mask ?: EMPTY_MASK, hist, HIST_SIZE, HIST_RANGE)
            val out = DoubleArray(256)
            val f = FloatArray(256)
            hist.get(0, 0, f)
            for (i in 0 until 256) out[i] = f[i].toDouble()
            return out
        } finally {
            hist.release()
        }
    }

    /** Histograma de 256 bins de bytes de luminancia ya leídos (sin JNI ni Mats). */
    fun histogram(bytes: ByteArray, count: Int = bytes.size): DoubleArray {
        val h = IntArray(256)
        for (i in 0 until min(count, bytes.size)) h[bytes[i].toInt() and 0xFF]++
        return DoubleArray(256) { h[it].toDouble() }
    }

    // Kernels morfológicos cacheados por (forma, ancho, alto): se usan sólo como lectura y nunca se liberan.
    private val kernels = java.util.concurrent.ConcurrentHashMap<Long, Mat>()

    /** Elemento estructurante cacheado (no liberar). */
    fun kernel(shape: Int, w: Int, h: Int = w): Mat {
        val key = (shape.toLong() shl 40) or (w.toLong() shl 20) or h.toLong()
        kernels[key]?.let { return it }
        val k = Imgproc.getStructuringElement(shape, Size(w.toDouble(), h.toDouble()))
        val prev = kernels.putIfAbsent(key, k)
        if (prev != null) { k.release(); return prev }
        // Evitar crecimiento ilimitado (tamaños dependientes de la resolución)
        if (kernels.size > 96) kernels.clear()
        return k
    }

    /**
     * Bitmap -> Mat RGBA con lado largo <= [longSide] SIN aliasing: reducción progresiva por mitades
     * (bilineal exacto a 1/2 = media 2x2) hasta <= 2x el objetivo y paso final INTER_AREA.
     * Devuelve también el factor real aplicado (ancho).
     */
    fun toRgbaScaled(bmp: Bitmap, longSide: Int): Pair<Mat, Double> {
        val w = bmp.width; val h = bmp.height
        val long = max(w, h)
        if (long <= longSide) return toRgba(bmp) to 1.0
        var cur = bmp
        while (max(cur.width, cur.height) > longSide * 2) {
            val nw = max(1, cur.width / 2); val nh = max(1, cur.height / 2)
            val next = Bitmap.createScaledBitmap(cur, nw, nh, true)
            if (cur !== bmp && next !== cur) cur.recycle()
            cur = next
        }
        val m = toRgba(cur)
        if (cur !== bmp) cur.recycle()
        val s = longSide.toDouble() / long
        val tw = max(1, (w * s).roundToInt()); val th = max(1, (h * s).roundToInt())
        if (m.cols() != tw || m.rows() != th) {
            val r = Mat()
            Imgproc.resize(m, r, Size(tw.toDouble(), th.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            m.release()
            return r to tw.toDouble() / w
        }
        return m to tw.toDouble() / w
    }

    /** Percentil (0..1) de un histograma. */
    fun percentile(hist: DoubleArray, p: Double): Int {
        val total = hist.sum()
        if (total <= 0) return 0
        val target = total * p
        var acc = 0.0
        for (i in 0 until 256) { acc += hist[i]; if (acc >= target) return i }
        return 255
    }

    /** LUT 1x256 8U de niveles: negro->0, blanco->255, con gamma (>1 oscurece medios tonos). */
    fun levelsLut(black: Double, white: Double, gamma: Double = 1.0): Mat {
        val lut = ByteArray(256)
        val range = max(1.0, white - black)
        for (i in 0 until 256) {
            val t = ((i - black) / range).coerceIn(0.0, 1.0)
            lut[i] = (t.pow(gamma) * 255.0).roundToInt().coerceIn(0, 255).toByte()
        }
        val m = Mat(1, 256, CvType.CV_8UC1)
        m.put(0, 0, lut)
        return m
    }

    /** LUT genérica a partir de una función 0..255 -> 0..255. */
    fun lut(f: (Int) -> Double): Mat {
        val lut = ByteArray(256)
        for (i in 0 until 256) lut[i] = f(i).roundToInt().coerceIn(0, 255).toByte()
        val m = Mat(1, 256, CvType.CV_8UC1)
        m.put(0, 0, lut)
        return m
    }

    fun applyLut(src: Mat, lut: Mat, dst: Mat) { Core.LUT(src, lut, dst); lut.release() }

    /** Máscara de enfoque (unsharp mask) in-place: dst = src*(1+a) - blur*a. */
    fun unsharp(img: Mat, sigma: Double, amount: Double) {
        if (amount <= 0.0) return
        val blur = Mat()
        Imgproc.GaussianBlur(img, blur, Size(0.0, 0.0), sigma)
        Core.addWeighted(img, 1.0 + amount, blur, -amount, 0.0, img)
        blur.release()
    }

    /**
     * Estima el fondo (papel + iluminación) de la imagen a baja resolución.
     * 1) dilatación (elimina tinta fina) + mediana, 2) máscara de "papel" (claro respecto de su entorno y poco
     * saturado), 3) relleno de zonas no-papel (fotos, bloques de color, regiones oscuras) por convolución
     * normalizada multiescala, 4) suavizado y re-escalado al tamaño completo.
     * Funciona con 8UC1 u 8UC3 (RGB). Devuelve un Mat del mismo tamaño/tipo que [img], sin ceros.
     */
    fun estimateBackground(img: Mat, workSide: Int = 256): Mat = MatBag().use { bag ->
        val color = img.channels() >= 3
        val sm = bag.mat()
        downscale(img, sm, workSide)
        val w = sm.cols(); val h = sm.rows(); val side = max(w, h)

        val k = oddAtLeast(side * 0.02, 3)
        val d = bag.mat()
        Imgproc.dilate(sm, d, kernel(Imgproc.MORPH_ELLIPSE, k))
        Imgproc.medianBlur(d, d, oddAtLeast(side * 0.02, 3))

        // Canales V (brillo) y S (saturación)
        val v = bag.mat(); val s = bag.mat()
        if (color) {
            val hsv = bag.mat()
            Imgproc.cvtColor(d, hsv, Imgproc.COLOR_RGB2HSV)
            Core.extractChannel(hsv, s, 1)
            Core.extractChannel(hsv, v, 2)
        } else {
            d.copyTo(v)
            s.create(v.size(), CvType.CV_8UC1); s.setTo(Scalar(0.0))
        }
        // Máximo local amplio: nivel esperado del papel alrededor
        val big = oddAtLeast(side * 0.25, 3)
        val vmax = bag.mat()
        Imgproc.dilate(v, vmax, kernel(Imgproc.MORPH_RECT, big))
        Imgproc.blur(vmax, vmax, Size(big.toDouble(), big.toDouble()))
        Core.multiply(vmax, Scalar(0.62), vmax)
        val paper = bag.mat(); val m2 = bag.mat()
        Core.compare(v, vmax, paper, Core.CMP_GE)
        Core.compare(s, Scalar(70.0), m2, Core.CMP_LT)
        Core.bitwise_and(paper, m2, paper)
        if (Core.countNonZero(paper) < 0.15 * w * h) paper.setTo(Scalar(255.0))
        Imgproc.erode(paper, paper, kernel(Imgproc.MORPH_RECT, 3))
        if (Core.countNonZero(paper) == 0) paper.setTo(Scalar(255.0))

        // Convolución normalizada multiescala para rellenar huecos (no-papel)
        val ch = if (color) 3 else 1
        val df = bag.mat(); d.convertTo(df, CvType.CV_32F)
        val mf = bag.mat(); paper.convertTo(mf, CvType.CV_32F, 1.0 / 255.0)
        val out = bag.mat(); df.copyTo(out)
        val hole = bag.mat(); Core.compare(paper, Scalar(0.0), hole, Core.CMP_EQ)
        val num = bag.mat(); val den = bag.mat(); val denC = bag.mat(); val fill = bag.mat()
        val weighted = bag.mat(); val mC = bag.mat(); val ok = bag.mat(); val sel = bag.mat()
        for (sig in doubleArrayOf(side / 40.0, side / 12.0, side / 4.0)) {
            if (Core.countNonZero(hole) == 0) break
            if (ch == 3) Core.merge(listOf(mf, mf, mf), mC) else mf.copyTo(mC)
            Core.multiply(out, mC, weighted)
            Imgproc.GaussianBlur(weighted, num, Size(0.0, 0.0), sig)
            Imgproc.GaussianBlur(mf, den, Size(0.0, 0.0), sig)
            Core.max(den, Scalar(1e-4), den)
            if (ch == 3) Core.merge(listOf(den, den, den), denC) else den.copyTo(denC)
            Core.divide(num, denC, fill)
            // Rellenar sólo donde hay soporte suficiente
            Core.compare(den, Scalar(0.02), ok, Core.CMP_GT)
            Core.bitwise_and(hole, ok, sel)
            fill.copyTo(out, sel)
            Core.subtract(hole, sel, hole)
            mf.setTo(Scalar(1.0), sel)
        }
        if (Core.countNonZero(hole) > 0) {
            val mean = Core.mean(out, paper)
            out.setTo(mean, hole)
        }
        Imgproc.GaussianBlur(out, out, Size(0.0, 0.0), max(1.0, side / 80.0))
        val out8 = bag.mat()
        out.convertTo(out8, if (color) CvType.CV_8UC3 else CvType.CV_8UC1)
        Core.max(out8, Scalar(1.0, 1.0, 1.0, 1.0), out8)
        val full = Mat()
        Imgproc.resize(out8, full, img.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        full
    }

    /** Nivel al que queda el papel tras normalizar (por debajo de 255 para no recortar el ruido y poder medirlo). */
    const val PAPER_LEVEL = 230.0

    /** Normaliza iluminación: img / fondo * [PAPER_LEVEL] (quita sombras y equilibra el blanco del papel). */
    fun divideByBackground(img: Mat, bg: Mat, dst: Mat) {
        Core.divide(img, bg, dst, PAPER_LEVEL)
    }

    /**
     * Variante para fotos OSCURAS (fondo < ~80): la ganancia de la división (x3..x6) posteriza los
     * gradientes si se hace en 8 bits. Aquí cada banda se suaviza ligeramente y se divide en coma flotante
     * (los valores intermedios fraccionarios rellenan los escalones) y sólo al final vuelve a 8 bits.
     * Se procesa por bandas horizontales para no duplicar la imagen en float (4 B/canal/píxel).
     */
    fun divideByBackgroundSmooth(img: Mat, bg: Mat, dst: Mat, sigma: Double = 0.7) {
        val w = img.cols(); val h = img.rows()
        dst.create(h, w, img.type())
        val pad = max(2, kotlin.math.ceil(sigma * 3).toInt())
        val band = max(32, (1_000_000 / max(1, w)))
        val fi = Mat(); val fb = Mat(); val o8 = Mat()
        try {
            var y = 0
            while (y < h) {
                val y1 = min(h, y + band)
                val ya = max(0, y - pad); val yb = min(h, y1 + pad)
                val si = img.submat(ya, yb, 0, w); val sb = bg.submat(ya, yb, 0, w)
                si.convertTo(fi, CvType.CV_32F)
                sb.convertTo(fb, CvType.CV_32F)
                si.release(); sb.release()
                if (sigma > 0) Imgproc.GaussianBlur(fi, fi, Size(0.0, 0.0), sigma)
                Core.divide(fi, fb, fi, PAPER_LEVEL)
                val core = fi.submat(y - ya, y - ya + (y1 - y), 0, w)
                core.convertTo(o8, img.type())
                core.release()
                val dRoi = dst.submat(y, y1, 0, w)
                o8.copyTo(dRoi)
                dRoi.release()
                y = y1
            }
        } finally {
            fi.release(); fb.release(); o8.release()
        }
    }

    /** Estadística del papel en una imagen ya normalizada (8UC1): (ruido sigma, nivel del papel). */
    fun paperStats(hist: DoubleArray): Pair<Double, Double> {
        val median = percentile(hist, 0.5)
        // Píxeles claros (>= mediana) = papel
        var total = 0.0
        for (i in median..255) total += hist[i]
        if (total <= 0) return 0.0 to 255.0
        var acc = 0.0
        var pm = 255
        for (i in median..255) { acc += hist[i]; if (acc >= total / 2) { pm = i; break } }
        // MAD alrededor del nivel del papel
        val dev = DoubleArray(256)
        for (i in median..255) dev[abs(i - pm)] += hist[i]
        acc = 0.0
        var mad = 0
        for (i in 0 until 256) { acc += dev[i]; if (acc >= total / 2) { mad = i; break } }
        return (1.4826 * max(0.5, mad.toDouble())) to pm.toDouble()
    }

    /**
     * Ruido relativo del papel estimado sobre un recorte central (normalizado por su propio fondo).
     * Captura también ruido correlacionado (demosaico/JPEG) de cámaras baratas.
     */
    fun estimatePaperNoise(src: Mat): Double = MatBag().use { bag ->
        val w = src.cols(); val h = src.rows()
        if (w < 16 || h < 16) return@use 0.0
        val roi = Rect(w / 4, h / 4, max(8, w / 2), max(8, h / 2))
        val crop = bag.add(src.submat(roi))
        val g = bag.add(gray(crop))
        // Limitar tamaño del recorte para que sea barato (sin promediar: usar submuestreo por paso)
        val gs = bag.mat()
        if (g.cols() * g.rows() > 1_000_000) {
            val st = kotlin.math.sqrt(g.cols() * g.rows() / 1_000_000.0)
            Imgproc.resize(g, gs, Size(g.cols() / st, g.rows() / st), 0.0, 0.0, Imgproc.INTER_NEAREST)
        } else g.copyTo(gs)
        val bg = bag.add(estimateBackground(gs, 128))
        val n = bag.mat()
        divideByBackground(gs, bg, n)
        paperStats(histogram(n)).first
    }

    /** Color medio del papel: media de los píxeles más claros (percentil >= 85). */
    fun paperColor(img: Mat): Scalar = MatBag().use { bag ->
        val sm = bag.mat()
        downscale(img, sm, 400)
        val g = bag.add(gray(sm))
        val t = percentile(histogram(g), 0.85)
        val mask = bag.mat()
        Imgproc.threshold(g, mask, max(0, t - 1).toDouble(), 255.0, Imgproc.THRESH_BINARY)
        if (Core.countNonZero(mask) == 0) Scalar(255.0, 255.0, 255.0, 255.0) else Core.mean(sm, mask)
    }

    /** Convierte un Mat de 1 o 4 canales a RGB 8UC3 (nuevo). */
    fun ensureRgb(src: Mat): Mat {
        val out = Mat()
        when (src.channels()) {
            1 -> Imgproc.cvtColor(src, out, Imgproc.COLOR_GRAY2RGB)
            4 -> Imgproc.cvtColor(src, out, Imgproc.COLOR_RGBA2RGB)
            else -> src.copyTo(out)
        }
        return out
    }

    fun clampRect(r: Rect, w: Int, h: Int): Rect {
        val x0 = r.x.coerceIn(0, w); val y0 = r.y.coerceIn(0, h)
        val x1 = (r.x + r.width).coerceIn(0, w); val y1 = (r.y + r.height).coerceIn(0, h)
        return Rect(x0, y0, max(0, x1 - x0), max(0, y1 - y0))
    }

    fun minOf3(a: Int, b: Int, c: Int) = min(a, min(b, c))
}
