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
     * 1) cierre morfológico (elimina tinta fina sin desplazar bordes) + mediana, 2) máscara de "papel" (claro respecto de su entorno y poco
     * saturado), 2b) SOMBRAS DURAS (mano, celular): zonas oscuras respecto del entorno pero lisas (papel sin
     * textura una vez quitada la tinta) y conectadas con el borde de la imagen también son papel -> se
     * normalizan en vez de rellenarse con el nivel del papel iluminado (antes quedaban como una mancha gris),
     * 3) relleno de zonas no-papel (fotos, bloques de color, regiones oscuras) por convolución
     * normalizada multiescala, 4) suavizado, 5) con [refine]: afinado con filtro guiado a resolución media
     * (guía = imagen sin tinta) para que el borde de las sombras quede nítido y sin halo, 6) re-escalado.
     * Funciona con 8UC1 u 8UC3 (RGB). Devuelve un Mat del mismo tamaño/tipo que [img], sin ceros.
     */
    fun estimateBackground(img: Mat, workSide: Int = 256, refine: Boolean = false, refineSide: Int = 512): Mat = MatBag().use { bag ->
        val color = img.channels() >= 3
        val sm = bag.mat()
        downscale(img, sm, workSide)
        val w = sm.cols(); val h = sm.rows(); val side = max(w, h)

        val k = oddAtLeast(side * 0.02, 3)
        val d = bag.mat()
        // CIERRE (dilatación + erosión) en vez de sólo dilatación: quita igual la tinta fina pero NO desplaza
        // los bordes de las sombras grandes (la dilatación metía ~k/2 px de nivel iluminado dentro de la sombra).
        Imgproc.morphologyEx(sm, d, Imgproc.MORPH_CLOSE, kernel(Imgproc.MORPH_ELLIPSE, k))
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
        // Saturación máxima del papel ADAPTATIVA: papel de color claro (cuadernos azulados, hojas recicladas)
        // tiene saturación propia ~60-70 y con un umbral fijo de 70 la mitad del papel se tomaba por "contenido".
        val paperSat = if (color) percentile(histogram(s, paper), 0.5).toDouble() else 0.0
        val satLimit = max(70.0, paperSat + 40.0)
        Core.compare(s, Scalar(satLimit), m2, Core.CMP_LT)
        Core.bitwise_and(paper, m2, paper)
        addShadowRegions(v, s, vmax, paper, bag, max(110.0, paperSat + 100.0))
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
            blurLarge(weighted, num, sig)
            blurLarge(mf, den, sig)
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
        // Ganancia acotada: en zonas casi negras (sin papel, tapa puesta, escena a oscuras) el fondo quedaba en
        // 1..10 y img/fondo·230 convertía ±3 de ruido en ±140 niveles (gris moteado). Suelo = max(16, 25 % del
        // percentil 90 del fondo) -> ganancia máxima ~x14 en el peor caso y ~x4 respecto al papel iluminado.
        Core.max(out8, Scalar.all(backgroundFloor(out8)), out8)
        val src8 = if (refine && min(img.cols(), img.rows()) >= 64) bag.add(refineBackground(img, out8, refineSide)) else out8
        val full = Mat()
        Imgproc.resize(src8, full, img.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        full
    }

    /** Suelo del fondo estimado (ver [estimateBackground]): max(16, 0.25·percentil 90 de su luminancia). */
    private fun backgroundFloor(bg8: Mat): Double {
        val g = if (bg8.channels() == 1) bg8 else gray(bg8)
        try {
            return max(16.0, 0.25 * percentile(histogram(g), 0.9))
        } finally {
            if (g !== bg8) g.release()
        }
    }

    /**
     * ¿Imagen prácticamente negra? (percentil 90 de la luminancia < [limit] a baja resolución). En ese caso no
     * hay papel que normalizar: dividir por el fondo sólo amplifica ruido.
     */
    fun isNearlyBlack(img: Mat, limit: Int = 20): Boolean = MatBag().use { bag ->
        val sm = bag.mat(); downscale(img, sm, 256)
        val g = if (sm.channels() == 1) sm else bag.add(gray(sm))
        percentile(histogram(g), 0.9) < limit
    }

    /**
     * Desenfoque gaussiano de sigma grande sobre un campo suave: para sigma > 6 se reduce la imagen ~sigma/3
     * veces, se difumina allí y se vuelve a ampliar (bilineal). Mismo resultado visual que el kernel completo
     * (cientos de taps a sigma = side/4) a una fracción del coste: la estimación de fondo pasa de ~17 a ~9 ms
     * a 256 px en un PC (más en un A53).
     */
    private fun blurLarge(src: Mat, dst: Mat, sigma: Double) {
        if (sigma <= 6.0) { Imgproc.GaussianBlur(src, dst, Size(0.0, 0.0), sigma); return }
        val f = sigma / 3.0
        val sw = max(4, (src.cols() / f).roundToInt()); val sh = max(4, (src.rows() / f).roundToInt())
        val sm = Mat()
        try {
            Imgproc.resize(src, sm, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(sm, sm, Size(0.0, 0.0), sigma * sw / src.cols())
            Imgproc.resize(sm, dst, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        } finally { sm.release() }
    }

    /**
     * Añade a [paper] las sombras duras: candidatas = más oscuras que el entorno ([vmax] ya escalado), poco
     * saturadas, LISAS (desviación local baja en la imagen sin tinta) y no negras; se aceptan las componentes
     * conexas grandes (>= 2 %) que tocan el borde (las sombras de la mano/celular entran desde fuera de la hoja;
     * los bloques oscuros impresos suelen estar dentro de los márgenes). Umbrales de saturación relativos al
     * papel (papel azulado/reciclado) y atenuación hasta 0.18 (sombras con sol directo).
     */
    private fun addShadowRegions(v: Mat, s: Mat, vmax: Mat, paper: Mat, bag: MatBag, satLimit: Double = 70.0) {
        val w = v.cols(); val h = v.rows()
        val vf = bag.mat(); v.convertTo(vf, CvType.CV_32F)
        val k = Size(5.0, 5.0)
        val m = bag.mat(); Imgproc.blur(vf, m, k)
        val sq = bag.mat(); Core.multiply(vf, vf, sq); Imgproc.blur(sq, sq, k)
        val m2 = bag.mat(); Core.multiply(m, m, m2)
        Core.subtract(sq, m2, sq); Core.max(sq, Scalar(0.0), sq); Core.sqrt(sq, sq)
        // Lisa = desviación local < max(5, 10 %): en la penumbra y bajo restos de escritura la sombra no es
        // perfectamente uniforme (con 6 %/4 se perdía media sombra y quedaba una mancha negra en B/N)
        val thr = bag.mat(); Core.multiply(m, Scalar(0.10), thr); Core.max(thr, Scalar(5.0), thr)
        val cand = bag.mat(); Core.compare(sq, thr, cand, Core.CMP_LT)
        val t = bag.mat()
        Core.compare(v, vmax, t, Core.CMP_LT); Core.bitwise_and(cand, t, cand)
        // En la sombra (luz del cielo, azulada) el papel gana saturación: límite relativo al papel iluminado
        Core.compare(s, Scalar(satLimit), t, Core.CMP_LT); Core.bitwise_and(cand, t, cand)
        Core.compare(v, Scalar(12.0), t, Core.CMP_GT); Core.bitwise_and(cand, t, cand)
        // Física de una sombra de mano/móvil: atenúa el papel a 0.2..0.75 de su nivel (con sol directo la
        // sombra llega a ~0.25), no lo deja casi negro.
        // [vmax] llega ya escalado por 0.62 -> v > 0.18·máximo local  <=>  v > vmax·(0.18/0.62).
        val vmin = bag.mat(); Core.multiply(vmax, Scalar(0.18 / 0.62), vmin)
        Core.compare(v, vmin, t, Core.CMP_GT); Core.bitwise_and(cand, t, cand)
        if (Core.countNonZero(cand) < 0.01 * w * h) return
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val n = Imgproc.connectedComponentsWithStats(cand, labels, stats, cents, 4, CvType.CV_32S)
        val minA = 0.01 * w * h
        val row = IntArray(5)
        val acc = bag.mat(); acc.create(v.size(), CvType.CV_8UC1); acc.setTo(Scalar(0.0))
        var any = false
        var accepted = 0.0
        val maxTotal = 0.45 * w * h
        // Las componentes grandes primero: si el total acumulado supera el 45 % de la imagen ya no es una sombra
        // (es mesa, fondo o una zona oscura impresa) y se dejan de aceptar regiones.
        val order = (1 until n).map { i -> stats.get(i, 0, row); i to row[4] }.sortedByDescending { it.second }
        for ((i, _) in order) {
            stats.get(i, 0, row)
            val x = row[0]; val y = row[1]; val ww = row[2]; val hh = row[3]; val a = row[4]
            if (a < minA) break
            // "Toca el borde" con margen: en un recorte de cuaderno/libro el marco o el canto de color (que no
            // es papel) separa la sombra del borde de la imagen aunque entre desde fuera de la hoja.
            val mg = (0.06 * max(w, h)).roundToInt()
            val touchesEdge = x == 0 || y == 0 || x + ww >= w || y + hh >= h
            val nearEdge = x <= mg || y <= mg || x + ww >= w - mg || y + hh >= h - mg
            // Cerca del borde pero sin tocarlo: sólo si es irregular (un bloque impreso es rectangular y lleno)
            val fillBox = a.toDouble() / max(1, ww * hh)
            if (!touchesEdge && !(nearEdge && fillBox < 0.8)) continue
            // Franja recta que recorre un lado de punta a punta (mesa sin recortar, lomo de libro, banda a
            // sangre): rellena casi todo su rectángulo. Las sombras de mano/móvil son irregulares.
            val spansW = ww >= 0.96 * w && (y == 0 || y + hh >= h)
            val spansH = hh >= 0.96 * h && (x == 0 || x + ww >= w)
            val fill = a.toDouble() / max(1, ww * hh)
            if ((spansW || spansH) && fill > 0.85) continue
            if (accepted + a > maxTotal) continue
            Core.compare(labels, Scalar(i.toDouble()), t, Core.CMP_EQ)
            Core.bitwise_or(acc, t, acc)
            accepted += a
            any = true
        }
        if (!any) return
        // Franja del borde de la sombra (no es "lisa" por el escalón): con el cierre morfológico su valor es
        // fiable -> también papel. Sin esto se rellenaba mezclando ambos lados y quedaba una banda gris.
        // (Sombra dura sintética: error en el borde 24.5 -> 11.9 niveles, en el papel 9.1 -> 6.3.)
        val band = bag.mat()
        Imgproc.dilate(acc, band, kernel(Imgproc.MORPH_RECT, 7))
        Core.compare(s, Scalar(satLimit), t, Core.CMP_LT); Core.bitwise_and(band, t, band)
        Core.compare(v, Scalar(12.0), t, Core.CMP_GT); Core.bitwise_and(band, t, band)
        Core.bitwise_or(acc, band, acc)
        Core.bitwise_or(paper, acc, paper)
    }

    /**
     * Afinado del fondo estimado a baja resolución con un filtro guiado conjunto a ~512 px: la guía es la
     * luminancia sin tinta (cierre morfológico + mediana), así los bordes de las sombras siguen a la imagen
     * real en lugar de un degradado borroso. Devuelve el fondo a resolución media (mismos canales que [bgSmall]).
     */
    private fun refineBackground(img: Mat, bgSmall: Mat, side0: Int): Mat = MatBag().use { bag ->
        val mid = bag.mat()
        downscale(img, mid, max(64, side0))
        val g = if (mid.channels() == 1) mid else bag.add(gray(mid))
        val side = max(g.cols(), g.rows())
        val guide8 = bag.mat()
        Imgproc.morphologyEx(g, guide8, Imgproc.MORPH_CLOSE, kernel(Imgproc.MORPH_ELLIPSE, oddAtLeast(side * 0.015, 3)))
        Imgproc.medianBlur(guide8, guide8, 5)
        val guide = bag.mat(); guide8.convertTo(guide, CvType.CV_32F, 1.0 / 255.0)
        val bgm8 = bag.mat()
        Imgproc.resize(bgSmall, bgm8, g.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val bgm = bag.mat(); bgm8.convertTo(bgm, CvType.CV_32F, 1.0 / 255.0)
        val q = bag.add(guidedFilter(guide, bgm, max(2, (side / 20.0).roundToInt()), 1e-4))
        val out = Mat()
        q.convertTo(out, bgSmall.type(), 255.0)
        Core.max(out, Scalar(1.0, 1.0, 1.0, 1.0), out)
        out
    }

    /**
     * Filtro guiado de He et al. (O(N) con boxFilter) con guía [guide] 32FC1 y entrada [p] 32FC1/32FC3.
     * Para imágenes pequeñas/medias (los intermedios son float del tamaño completo). Devuelve Mat nuevo 32F.
     */
    fun guidedFilter(guide: Mat, p: Mat, r: Int, eps: Double): Mat = MatBag().use { bag ->
        val k = Size(2.0 * r + 1, 2.0 * r + 1)
        val mI = bag.mat(); Imgproc.boxFilter(guide, mI, CvType.CV_32F, k)
        val varI = bag.mat(); Imgproc.sqrBoxFilter(guide, varI, CvType.CV_32F, k)
        val t = bag.mat(); Core.multiply(mI, mI, t); Core.subtract(varI, t, varI)
        Core.add(varI, Scalar(eps), varI)
        val chans = ArrayList<Mat>(3)
        if (p.channels() == 1) chans.add(p) else Core.split(p, chans)
        val outs = ArrayList<Mat>(chans.size)
        val mp = bag.mat(); val mIp = bag.mat(); val a = bag.mat(); val b = bag.mat()
        for (c in chans) {
            Imgproc.boxFilter(c, mp, CvType.CV_32F, k)
            Core.multiply(guide, c, mIp); Imgproc.boxFilter(mIp, mIp, CvType.CV_32F, k)
            Core.multiply(mI, mp, t); Core.subtract(mIp, t, mIp)       // cov(I, p)
            Core.divide(mIp, varI, a)
            Core.multiply(a, mI, t); Core.subtract(mp, t, b)
            Imgproc.boxFilter(a, a, CvType.CV_32F, k); Imgproc.boxFilter(b, b, CvType.CV_32F, k)
            val q = Mat(); Core.multiply(a, guide, q); Core.add(q, b, q)
            outs.add(q)
        }
        if (p.channels() != 1) for (c in chans) c.release()
        if (outs.size == 1) outs[0] else {
            val m = Mat(); Core.merge(outs, m); for (o in outs) o.release(); m
        }
    }

    /**
     * Filtro guiado AUTO-GUIADO sobre un canal 8U (des-ruido que preserva bordes de letras, O(N)).
     * a = var/(var+eps), b = media·(1-a): en el papel liso (var << eps) promedia; en los trazos (var >> eps)
     * conserva el píxel. Se procesa por BANDAS horizontales: memoria acotada (los float de la banda) y mejor
     * uso de caché (en un PC: 8 MP en ~90 ms frente a ~210 ms de una pasada completa, mismo resultado exacto).
     * [sub] = 2 calcula a, b a media resolución ("fast guided filter") para vistas previas.
     */
    fun guidedSelf(src: Mat, dst: Mat, r: Int, eps: Double, sub: Int = 1) {
        require(src.type() == CvType.CV_8UC1)
        val w = src.cols(); val h = src.rows()
        if (sub > 1) { guidedSelfSub(src, dst, r, eps, sub); return }
        if (dst !== src) dst.create(h, w, CvType.CV_8UC1)
        val pad = 2 * r + 1
        val band = max(64, min(h, 196_608 / max(1, w) * 2))
        val k = Size(2.0 * r + 1, 2.0 * r + 1)
        val m = Mat(); val m2 = Mat(); val t = Mat(); val a = Mat(); val b = Mat(); val sf = Mat(); val q8 = Mat()
        // Si dst === src hay que leer de una copia de las filas originales (las bandas se solapan)
        val source = if (dst === src) src.clone() else src
        try {
            var y = 0
            while (y < h) {
                val y1 = min(h, y + band)
                val ya = max(0, y - pad); val yb = min(h, y1 + pad)
                val roi = source.submat(ya, yb, 0, w)
                Imgproc.boxFilter(roi, m, CvType.CV_32F, k)
                Imgproc.sqrBoxFilter(roi, m2, CvType.CV_32F, k)
                Core.multiply(m, m, t); Core.subtract(m2, t, m2)            // var
                Core.add(m2, Scalar(eps), t); Core.divide(m2, t, a)         // a = var/(var+eps)
                Core.multiply(a, m, t); Core.subtract(m, t, b)              // b = m - a·m
                Imgproc.boxFilter(a, a, CvType.CV_32F, k); Imgproc.boxFilter(b, b, CvType.CV_32F, k)
                roi.convertTo(sf, CvType.CV_32F)
                roi.release()
                Core.multiply(a, sf, sf); Core.add(sf, b, sf)
                val core = sf.submat(y - ya, y - ya + (y1 - y), 0, w)
                core.convertTo(q8, CvType.CV_8U)
                core.release()
                val d = dst.submat(y, y1, 0, w); q8.copyTo(d); d.release()
                y = y1
            }
        } finally {
            if (source !== src) source.release()
            m.release(); m2.release(); t.release(); a.release(); b.release(); sf.release(); q8.release()
        }
    }

    private fun guidedSelfSub(src: Mat, dst: Mat, r: Int, eps: Double, sub: Int) = MatBag().use { bag ->
        val w = src.cols(); val h = src.rows()
        val small = bag.mat()
        Imgproc.resize(src, small, Size(max(1.0, (w / sub).toDouble()), max(1.0, (h / sub).toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
        val rs = max(1, (r.toDouble() / sub).roundToInt())
        val k = Size(2.0 * rs + 1, 2.0 * rs + 1)
        val m = bag.mat(); val m2 = bag.mat(); val t = bag.mat(); val a = bag.mat(); val b = bag.mat()
        Imgproc.boxFilter(small, m, CvType.CV_32F, k)
        Imgproc.sqrBoxFilter(small, m2, CvType.CV_32F, k)
        Core.multiply(m, m, t); Core.subtract(m2, t, m2)
        Core.add(m2, Scalar(eps), t); Core.divide(m2, t, a)
        Core.multiply(a, m, t); Core.subtract(m, t, b)
        Imgproc.boxFilter(a, a, CvType.CV_32F, k); Imgproc.boxFilter(b, b, CvType.CV_32F, k)
        val af = bag.mat(); val bf = bag.mat()
        Imgproc.resize(a, af, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.resize(b, bf, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val sf = bag.mat(); src.convertTo(sf, CvType.CV_32F)
        Core.multiply(af, sf, sf); Core.add(sf, bf, sf)
        sf.convertTo(dst, CvType.CV_8U)
    }

    /**
     * Des-ruido de LUMINANCIA con filtro guiado (in-place sobre RGB 8UC3 o gris 8UC1). [sigma] = ruido medido
     * del papel (0..255); eps = (2.2·sigma)² separa el grano (se alisa) de los bordes de las letras (se conservan).
     * Radio según resolución (1..3). Mucho más rápido que NLM y que un bilateral grande, con calidad comparable.
     */
    fun guidedDenoiseLuma(img: Mat, sigma: Double, fast: Boolean) {
        val long = max(img.cols(), img.rows())
        val r = (long / 1400.0).roundToInt().coerceIn(1, 3)
        val eps = (2.2 * max(2.0, sigma)).pow(2)
        val sub = if (fast && long > 600) 2 else 1
        if (img.channels() == 1) { guidedSelf(img, img, r, eps, sub); return }
        MatBag().use { bag ->
            val ycc = bag.mat(); Imgproc.cvtColor(img, ycc, Imgproc.COLOR_RGB2YCrCb)
            val y = bag.mat(); Core.extractChannel(ycc, y, 0)
            guidedSelf(y, y, r, eps, sub)
            Core.insertChannel(y, ycc, 0)
            Imgproc.cvtColor(ycc, img, Imgproc.COLOR_YCrCb2RGB)
        }
    }

    /**
     * Hilos de OpenCV según el equipo: en gama baja se deja un núcleo libre para que la interfaz (hilo
     * principal + RenderThread) siga a 60 fps mientras se procesa. Idempotente.
     */
    @Volatile private var threadsConfigured = -1
    @Volatile private var lastTier: DeviceTier? = null
    @Volatile private var cameraActive = false
    fun configureThreads(tier: DeviceTier) {
        lastTier = tier
        val cores = max(1, tier.cores)
        val lowEnd = tier.isLowRam || cores <= 4
        var n = if (lowEnd) max(1, cores - 1) else max(1, cores - 1).coerceAtMost(6)
        // Con la cámara abierta en gama baja: 2 hilos como máximo, para que la vista previa, el análisis en vivo
        // y el hilo principal no compitan con el procesado de la foto anterior.
        if (cameraActive && lowEnd) n = min(n, 2)
        if (threadsConfigured == n) return
        threadsConfigured = n
        try { Core.setNumThreads(n) } catch (_: Throwable) { }
    }

    /** La pantalla de cámara avisa al abrirse/cerrarse; en gama baja limita los hilos de OpenCV mientras tanto. */
    fun setCameraActive(active: Boolean, tier: DeviceTier) {
        cameraActive = active
        configureThreads(lastTier ?: tier)
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
    fun estimatePaperNoise(src: Mat): Double = estimatePaperNoiseLevel(src).first

    /**
     * Como [estimatePaperNoise], y además el factor nivelDelPapel/[PAPER_LEVEL] para pasar el ruido RELATIVO
     * a niveles reales de la imagen SIN normalizar: σ_abs = rel·factor. Los filtros que trabajan antes de dividir
     * por el fondo (filtro guiado, promedio robusto) deben usar σ_abs: con papel a 60 el relativo sale ~4x mayor.
     */
    fun estimatePaperNoiseLevel(src: Mat): Pair<Double, Double> = MatBag().use { bag ->
        val w = src.cols(); val h = src.rows()
        if (w < 16 || h < 16) return@use 0.0 to 1.0
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
        val level = percentile(histogram(bg), 0.5).toDouble()
        paperStats(histogram(n)).first to (level / PAPER_LEVEL).coerceIn(0.05, 1.15)
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

    /**
     * ¿[t] es falta de memoria (Java o nativa de OpenCV)? Estas excepciones deben propagarse: el repositorio
     * reintenta el procesado con menos píxeles.
     */
    fun isOutOfMemory(t: Throwable): Boolean {
        if (t is OutOfMemoryError) return true
        val msg = t.message ?: return false
        return t is org.opencv.core.CvException &&
            (msg.contains("Insufficient memory", ignoreCase = true) || msg.contains("Failed to allocate", ignoreCase = true))
    }
}
