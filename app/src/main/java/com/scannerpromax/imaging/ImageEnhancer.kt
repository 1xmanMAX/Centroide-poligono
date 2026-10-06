package com.scannerpromax.imaging

import android.graphics.Bitmap
import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.FilterType
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Filtros de mejora "casi mágicos".
 *
 * Base común de los filtros de documento: estimación del fondo a baja resolución (papel + iluminación,
 * robusta a fotos y bloques de color dentro del documento) y división -> quita sombras y equilibra el blanco.
 * Después: niveles adaptativos al ruido medido (punto blanco = papel - 2σ, así el ruido del papel desaparece),
 * gamma para dar cuerpo a la tinta, saturación selectiva (sólo píxeles con color real) y máscara de enfoque.
 * Para cámaras baratas: des-ruido según el ruido medido (bilateral / NLM en gama alta), des-ruido cromático
 * a media resolución y, en MAGIC_PRO, realce de bordes con umbral (sin re-escalar).
 */
object ImageEnhancer {

    /** Opciones internas: gama del equipo, modo rápido (vista previa) y límite de píxeles. */
    internal data class Options(val highEnd: Boolean, val fast: Boolean, val maxPixels: Int) {
        companion object {
            fun default(): Options {
                val maxMem = Runtime.getRuntime().maxMemory()
                return Options(highEnd = maxMem >= 384L * 1024 * 1024, fast = false, maxPixels = 8_000_000)
            }
            fun of(tier: DeviceTier, fast: Boolean) =
                Options(DeviceProfiler.isHighEnd(tier), fast, tier.maxWorkingPixels)
        }
    }

    fun apply(src: Bitmap, filter: FilterType, adjustments: Adjustments = Adjustments()): Bitmap =
        applyBitmap(src, filter, adjustments, Options.default())

    /** Variante con perfil del dispositivo: [fast] = vista previa (sin des-ruido costoso). */
    fun apply(src: Bitmap, filter: FilterType, adjustments: Adjustments, tier: DeviceTier, fast: Boolean): Bitmap =
        applyBitmap(src, filter, adjustments, Options.of(tier, fast))

    private fun applyBitmap(src: Bitmap, filter: FilterType, adj: Adjustments, opt: Options): Bitmap {
        val rgb = Cv.toRgb(src)
        val out = try { applyMat(rgb, filter, adj, opt) } finally { rgb.release() }
        val bmp = Cv.toBitmap(out)
        out.release()
        return bmp
    }

    /** Aplica el filtro sobre un Mat RGB 8UC3 (no se modifica). Devuelve Mat nuevo (8UC3 o 8UC1). */
    internal fun applyMat(rgb: Mat, filter: FilterType, adj: Adjustments, opt: Options): Mat {
        val src = if (rgb.channels() == 3) rgb else Cv.ensureRgb(rgb)
        try {
            val out = when (filter) {
                FilterType.ORIGINAL -> src.clone()
                FilterType.MAGIC -> magic(src, opt, pro = false)
                FilterType.MAGIC_PRO -> magic(src, opt, pro = true)
                FilterType.NO_SHADOW -> noShadow(src, opt)
                FilterType.GRAYSCALE -> grayscale(src, opt)
                FilterType.BLACK_WHITE -> binarize(src, opt, eco = false)
                FilterType.ECO_INK -> binarize(src, opt, eco = true)
                FilterType.LIGHTEN -> lighten(src, opt)
                FilterType.VIVID -> vivid(src, opt)
                FilterType.WHITEBOARD -> whiteboard(src, opt)
            }
            applyAdjustments(out, adj)
            return out
        } finally {
            if (src !== rgb) src.release()
        }
    }

    // =====================================================================================
    // Filtros
    // =====================================================================================

    private fun magic(rgb: Mat, opt: Options, pro: Boolean): Mat = MatBag().use { bag ->
        val rel0 = Cv.estimatePaperNoise(rgb)
        val den = bag.add(denoiseColor(rgb, rel0, opt, strong = pro))
        chromaDenoise(den, rel0, opt)
        val bg = bag.add(Cv.estimateBackground(den, bgSide(opt)))
        val bgLevel = Core.mean(bg).`val`.let { (it[0] + it[1] + it[2]) / 3.0 }
        val n = Mat()
        // Poca luz: división en coma flotante (evita posterización por la gran ganancia)
        if (bgLevel < 80) Cv.divideByBackgroundSmooth(den, bg, n, if (opt.fast) 0.0 else 0.7)
        else Cv.divideByBackground(den, bg, n)

        val g = bag.add(Cv.gray(n))
        val hist = Cv.histogram(g)
        val (rel, pm) = Cv.paperStats(hist)
        val black = min(Cv.percentile(hist, 0.005), 110) * 0.9
        val white = (pm - 2.5 * rel).coerceIn(170.0, 250.0)
        // Gamma adaptativa: fotos oscuras -> tinta con menos contraste -> más gamma
        val gamma = when {
            pro && bgLevel < 90 -> 1.5
            pro -> 1.35
            else -> 1.3
        }
        Cv.applyLut(n, Cv.levelsLut(black, white, gamma), n)
        boostSaturation(n, if (pro) 1.35 else 1.3, lowCut = true)
        val long = max(n.cols(), n.rows())
        if (pro) {
            // Realce consciente de bordes a resolución nativa (sustituye a la antigua "súper-resolución" x2,
            // que no añadía información y cuadruplicaba memoria/tamaño). Igual en vista previa y final.
            // El umbral se adapta al ruido medido: el grano del papel no se amplifica.
            Cv.unsharp(n, max(0.8, long / 2500.0), 0.55)
            edgeAwareSharpen(n, max(1.0, long / 1800.0), 0.6, threshold = max(6.0, rel * 2.5))
        } else {
            Cv.unsharp(n, max(0.8, long / 2500.0), 0.5)
        }
        n
    }

    /**
     * Máscara de enfoque con umbral: sólo se refuerzan los píxeles cuyo detalle (|img - blur| en luminancia)
     * supera [threshold]; el papel liso y el ruido fino quedan intactos. In-place.
     */
    private fun edgeAwareSharpen(img: Mat, sigma: Double, amount: Double, threshold: Double) {
        if (amount <= 0) return
        MatBag().use { bag ->
            val blur = bag.mat(); Imgproc.GaussianBlur(img, blur, Size(0.0, 0.0), sigma)
            val diff = bag.mat(); Core.absdiff(img, blur, diff)
            val dg = if (diff.channels() == 1) diff else bag.add(Cv.gray(diff))
            val flat = bag.mat(); Imgproc.threshold(dg, flat, threshold, 255.0, Imgproc.THRESH_BINARY_INV)
            // Transición suave entre zonas realzadas y lisas (sin bordes duros en la máscara)
            Imgproc.dilate(flat, flat, Cv.kernel(Imgproc.MORPH_RECT, 3))
            val orig = bag.add(img.clone())
            Core.addWeighted(img, 1.0 + amount, blur, -amount, 0.0, img)
            orig.copyTo(img, flat)
        }
    }

    /** Lado de trabajo de la estimación de fondo: más fino en gama alta (sombras duras con menos halo). */
    private fun bgSide(opt: Options): Int = if (opt.highEnd && !opt.fast) 384 else 256

    private fun noShadow(rgb: Mat, opt: Options): Mat = MatBag().use { bag ->
        val rel0 = Cv.estimatePaperNoise(rgb)
        val den = bag.add(if (rel0 > 5) denoiseColor(rgb, rel0, opt, strong = false) else rgb.clone())
        if (rel0 > 5) chromaDenoise(den, rel0, opt)
        val bg = bag.add(Cv.estimateBackground(den, bgSide(opt)))
        val n = Mat()
        Cv.divideByBackground(den, bg, n)
        val g = bag.add(Cv.gray(n))
        val hist = Cv.histogram(g)
        val (rel, pm) = Cv.paperStats(hist)
        val black = Cv.percentile(hist, 0.002) * 0.5
        val white = (pm - 1.5 * rel).coerceIn(200.0, 252.0)
        Cv.applyLut(n, Cv.levelsLut(black, white, 1.05), n)
        n
    }

    private fun grayscale(rgb: Mat, opt: Options): Mat = MatBag().use { bag ->
        val g = bag.add(Cv.gray(rgb))
        val rel0 = Cv.estimatePaperNoise(g)
        if (rel0 > 4) Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), min(1.6, 0.4 + rel0 / 20.0))
        val bg = bag.add(Cv.estimateBackground(g, bgSide(opt)))
        val n = Mat()
        Cv.divideByBackground(g, bg, n)
        if (rel0 < 4) {
            val clahe = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
            clahe.apply(n, n)
        }
        val hist = Cv.histogram(n)
        val (rel, pm) = Cv.paperStats(hist)
        val black = min(Cv.percentile(hist, 0.005), 110) * 0.9
        val white = (pm - 2.0 * rel).coerceIn(180.0, 250.0)
        Cv.applyLut(n, Cv.levelsLut(black, white, 1.2), n)
        Cv.unsharp(n, max(0.8, max(n.cols(), n.rows()) / 2500.0), 0.4)
        n
    }

    /**
     * Binarización tipo Sauvola sobre la imagen con iluminación normalizada.
     * Media y desviación local se calculan a resolución reducida (son suaves) y el umbral se re-escala:
     * mucho menos memoria y tiempo que imágenes integrales a resolución completa.
     */
    private fun binarize(rgb: Mat, opt: Options, eco: Boolean): Mat = MatBag().use { bag ->
        val g = bag.add(Cv.gray(rgb))
        val rel0 = Cv.estimatePaperNoise(g)
        if (rel0 > 4) Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), min(1.6, 0.4 + rel0 / 20.0))
        val bg = bag.add(Cv.estimateBackground(g, bgSide(opt)))
        val n = bag.mat()
        Cv.divideByBackground(g, bg, n)
        val (relN, pm) = Cv.paperStats(Cv.histogram(n))

        val long = max(n.cols(), n.rows())
        val win = Cv.odd(max(15, long / 40))
        val r = max(1.0, win / 9.0)
        val smallSize = Size(max(1.0, (n.cols() / r).roundToInt().toDouble()), max(1.0, (n.rows() / r).roundToInt().toDouble()))
        // Media y E[x²] reducidos con INTER_AREA (promedios exactos por bloque). Calcular la varianza
        // sobre la imagen ya reducida la subestimaría; x² cabe en 16 bits (255² = 65025).
        val f = bag.mat(); val sqS = bag.mat()
        if (r > 1.0) {
            val small = bag.mat()
            Imgproc.resize(n, small, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
            small.convertTo(f, CvType.CV_32F)
            val n16 = bag.mat(); n.convertTo(n16, CvType.CV_16U)
            Core.multiply(n16, n16, n16)
            val sq16 = bag.mat()
            Imgproc.resize(n16, sq16, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
            n16.release()
            sq16.convertTo(sqS, CvType.CV_32F)
        } else {
            n.convertTo(f, CvType.CV_32F)
            Core.multiply(f, f, sqS)
        }
        val winS = Cv.odd(max(7, (win / r).roundToInt()))
        val boxSz = Size(winS.toDouble(), winS.toDouble())
        val mean = bag.mat(); Imgproc.boxFilter(f, mean, CvType.CV_32F, boxSz)
        val sq = bag.mat(); Imgproc.boxFilter(sqS, sq, CvType.CV_32F, boxSz)
        val m2 = bag.mat(); Core.multiply(mean, mean, m2)
        val sd = bag.mat(); Core.subtract(sq, m2, sd)
        Core.max(sd, Scalar(0.0), sd)
        Core.sqrt(sd, sd)
        // El fondo ya está normalizado (papel ≈ PAPER_LEVEL): un k bajo conserva trazos tenues (lápiz,
        // bolígrafo claro, impresión desvaída) sin falsos positivos en el papel. Eco algo más agresivo.
        val k = if (eco) 0.2 else 0.12
        val rr = 128.0
        // Sauvola: T = m*(1-k) + m*sd*(k/R)
        val msd = bag.mat(); Core.multiply(mean, sd, msd, k / rr)
        val t = bag.mat(); Core.addWeighted(mean, 1.0 - k, msd, 1.0, 0.0, t)
        // Umbral nunca por encima del papel menos ~3σ del ruido (evita "nieve" en papel liso)
        Core.min(t, Scalar(max(1.0, pm - 3.0 * relN)), t)
        val t8 = bag.mat(); t.convertTo(t8, CvType.CV_8U)
        val tFull = bag.mat()
        if (r > 1.0) Imgproc.resize(t8, tFull, n.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) else t8.copyTo(tFull)

        val bin = Mat()
        Core.compare(n, tFull, bin, Core.CMP_GT) // 255 = papel
        if (!eco) {
            // Regiones oscuras grandes (fotos, bloques) donde Sauvola falla por baja varianza
            val dark = bag.mat()
            Core.compare(n, Scalar(pm * 0.55), dark, Core.CMP_LT)
            bin.setTo(Scalar(0.0), dark)
        }
        // Motas mínimas
        val minArea = max(2.0, (long / 1000.0).pow(2))
        removeSmallInk(bin, minArea)
        if (eco) {
            // Ahorro de tinta: vaciar el interior de zonas sólidas dejando el contorno
            val ink = bag.mat(); Core.bitwise_not(bin, ink)
            val ks = Cv.odd(max(5, long / 120))
            val core = bag.mat()
            Imgproc.erode(ink, core, Cv.kernel(Imgproc.MORPH_ELLIPSE, ks))
            bin.setTo(Scalar(255.0), core)
        }
        bin
    }

    private fun lighten(rgb: Mat, opt: Options): Mat = MatBag().use { bag ->
        val rel0 = Cv.estimatePaperNoise(rgb)
        val den = bag.add(if (rel0 > 5) denoiseColor(rgb, rel0, opt, strong = false) else rgb.clone())
        val bg = bag.add(Cv.estimateBackground(den, bgSide(opt)))
        val n = bag.mat()
        Cv.divideByBackground(den, bg, n)
        // Mezcla: mayoría normalizada (sin sombras) + algo del original aclarado (conserva el tono)
        val lift = bag.mat()
        Cv.applyLut(den, Cv.lut { 255.0 * (it / 255.0).pow(0.6) }, lift)
        val out = Mat()
        Core.addWeighted(lift, 0.25, n, 0.75, 0.0, out)
        val g = bag.add(Cv.gray(out))
        val hist = Cv.histogram(g)
        val (rel, pm) = Cv.paperStats(hist)
        val black = Cv.percentile(hist, 0.003) * 0.5
        val white = (pm - 1.5 * rel).coerceIn(170.0, 250.0)
        Cv.applyLut(out, Cv.levelsLut(black, white, 0.85), out)
        out
    }

    private fun vivid(rgb: Mat, opt: Options): Mat {
        val rel0 = Cv.estimatePaperNoise(rgb)
        val out = if (rel0 > 4) denoiseColor(rgb, rel0, opt, strong = false) else rgb.clone()
        MatBag().use { bag ->
            // Gamma adaptativa para fotos oscuras
            val g = bag.add(Cv.gray(out))
            val mean = Core.mean(g).`val`[0]
            if (mean in 1.0..115.0) {
                val gamma = (ln(0.5) / ln(mean / 255.0)).coerceIn(0.5, 1.0)
                Cv.applyLut(out, Cv.lut { 255.0 * (it / 255.0).pow(gamma) }, out)
            }
            // Balance de blancos "gray world" suave (50%)
            val m = Core.mean(out).`val`
            val avg = (m[0] + m[1] + m[2]) / 3.0
            if (m[0] > 1 && m[1] > 1 && m[2] > 1) {
                val sc = DoubleArray(3) { (1.0 + 0.5 * (avg / m[it] - 1.0)).coerceIn(0.8, 1.25) }
                Core.multiply(out, Scalar(sc[0], sc[1], sc[2]), out)
            }
            // CLAHE en L (Lab)
            val lab = bag.mat(); Imgproc.cvtColor(out, lab, Imgproc.COLOR_RGB2Lab)
            val l = bag.mat(); Core.extractChannel(lab, l, 0)
            Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(l, l)
            Core.insertChannel(l, lab, 0)
            Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        }
        boostSaturation(out, 1.35, lowCut = false)
        Cv.unsharp(out, max(0.8, max(out.cols(), out.rows()) / 2500.0), 0.35)
        return out
    }

    private fun whiteboard(rgb: Mat, opt: Options): Mat = MatBag().use { bag ->
        val rel0 = Cv.estimatePaperNoise(rgb)
        val den = bag.add(denoiseColor(rgb, rel0, opt, strong = false))
        chromaDenoise(den, rel0, opt)
        val bg = bag.add(Cv.estimateBackground(den, bgSide(opt)))
        val n = Mat()
        Cv.divideByBackground(den, bg, n)
        val g = bag.add(Cv.gray(n))
        val hist = Cv.histogram(g)
        val (rel, pm) = Cv.paperStats(hist)
        val black = Cv.percentile(hist, 0.01) * 0.8
        val white = (pm - 3.0 * rel).coerceIn(160.0, 235.0)
        Cv.applyLut(n, Cv.levelsLut(black, white, 1.6), n)
        // Fondo blanco puro (sin tinte) y marcadores muy saturados
        val hsv = bag.mat(); Imgproc.cvtColor(n, hsv, Imgproc.COLOR_RGB2HSV)
        val s = bag.mat(); Core.extractChannel(hsv, s, 1)
        Cv.applyLut(s, Cv.lut { if (it < 25) 0.0 else min(255.0, it * 1.8) }, s)
        Core.insertChannel(s, hsv, 1)
        Imgproc.cvtColor(hsv, n, Imgproc.COLOR_HSV2RGB)
        Cv.unsharp(n, max(0.8, max(n.cols(), n.rows()) / 2500.0), 0.6)
        n
    }

    // =====================================================================================
    // Bloques comunes
    // =====================================================================================

    /** Des-ruido según el ruido relativo medido [rel] y la gama del equipo. Devuelve Mat nuevo. */
    private fun denoiseColor(rgb: Mat, rel: Double, opt: Options, strong: Boolean): Mat {
        val out = Mat()
        when {
            opt.fast -> if (rel > 6) Imgproc.GaussianBlur(rgb, out, Size(3.0, 3.0), 0.0) else rgb.copyTo(out)
            rel <= 3.0 && !strong -> rgb.copyTo(out)
            strong && opt.highEnd && rgb.total() <= 2_500_000 -> {
                val h = (rel * 0.8).coerceIn(3.0, 12.0).toFloat()
                Photo.fastNlMeansDenoisingColored(rgb, out, h, h, 7, 15)
            }
            opt.highEnd -> Imgproc.bilateralFilter(rgb, out, 7, max(20.0, rel * 4), 5.0)
            rel > 5 || strong -> Imgproc.bilateralFilter(rgb, out, 5, max(15.0, rel * 3.5), 3.0)
            else -> Imgproc.GaussianBlur(rgb, out, Size(3.0, 3.0), 0.6)
        }
        return out
    }

    /** Des-ruido cromático: suaviza a, b (Lab) a media resolución. Elimina las manchas de color de sensores baratos. */
    private fun chromaDenoise(rgb: Mat, rel: Double, opt: Options) {
        if (opt.fast && rel <= 6) return
        if (rel <= 2.0) return
        MatBag().use { bag ->
            val lab = bag.mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            val half = Size(max(1.0, rgb.cols() / 2.0), max(1.0, rgb.rows() / 2.0))
            val c = bag.mat(); val cs = bag.mat()
            for (ch in 1..2) {
                Core.extractChannel(lab, c, ch)
                Imgproc.resize(c, cs, half, 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.medianBlur(cs, cs, 5)
                Imgproc.GaussianBlur(cs, cs, Size(3.0, 3.0), 0.0)
                Imgproc.resize(cs, c, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                Core.insertChannel(c, lab, ch)
            }
            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
        }
    }

    /**
     * Saturación: con [lowCut] los píxeles casi grises (ruido cromático del papel) se desaturan
     * y sólo se realza el color real (sellos, firmas, subrayados).
     */
    private fun boostSaturation(rgb: Mat, boost: Double, lowCut: Boolean) {
        MatBag().use { bag ->
            val hsv = bag.mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val s = bag.mat(); Core.extractChannel(hsv, s, 1)
            val lut = Cv.lut { v ->
                if (!lowCut) min(255.0, v * boost)
                else when {
                    v < 18 -> v * 0.35
                    v < 40 -> { val t = (v - 18) / 22.0; v * (0.35 + t * (boost - 0.35)) }
                    else -> min(255.0, v * boost)
                }
            }
            Cv.applyLut(s, lut, s)
            Core.insertChannel(s, hsv, 1)
            Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        }
    }

    /** Elimina componentes de tinta (negro sobre blanco) con área < [minArea]. In-place. */
    internal fun removeSmallInk(bin: Mat, minArea: Double) {
        val inv = Mat()
        Core.bitwise_not(bin, inv)
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(inv, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        inv.release(); hier.release()
        if (contours.size in 1..400_000) {
            val small = contours.filter {
                val r = Imgproc.boundingRect(it)
                r.width * r.height <= minArea * 2 && Imgproc.contourArea(it) <= minArea
            }
            if (small.isNotEmpty()) Imgproc.drawContours(bin, small, -1, Scalar(255.0), -1)
        }
        for (c in contours) c.release()
    }

    /** Brillo/contraste (LUT) y nitidez finales. In-place. */
    internal fun applyAdjustments(img: Mat, adj: Adjustments) {
        val b = adj.brightness.coerceIn(-1f, 1f).toDouble()
        val c = adj.contrast.coerceIn(-1f, 1f).toDouble()
        val s = adj.sharpness.coerceIn(-1f, 1f).toDouble()
        if (b != 0.0 || c != 0.0) {
            val alpha = if (c >= 0) 1.0 + c else 1.0 + c * 0.7
            val beta = b * 70.0
            Cv.applyLut(img, Cv.lut { (it - 128.0) * alpha + 128.0 + beta }, img)
        }
        val long = max(img.cols(), img.rows())
        if (s > 0) Cv.unsharp(img, max(1.0, long / 2000.0), s * 1.5)
        else if (s < 0) Imgproc.GaussianBlur(img, img, Size(0.0, 0.0), -s * 2.0 * max(1.0, long / 2000.0))
    }
}
