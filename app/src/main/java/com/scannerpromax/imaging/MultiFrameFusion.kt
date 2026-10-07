package com.scannerpromax.imaging

import android.graphics.Bitmap
import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Fusión multi-cuadro para POCA LUZ y ANTI-REFLEJOS: 3-4 fotos casi iguales (ráfaga a pulso) -> una sola con
 * ~la mitad de ruido y sin brillos especulares.
 *
 * 1. Referencia = el cuadro más nítido (varianza del Laplaciano a baja resolución): descarta el movido.
 * 2. Alineación de cada cuadro con la referencia: ORB + emparejamiento con test de razón + homografía RANSAC a
 *    baja resolución (640-1024 px), validada (desplazamiento/escala razonables) y aplicada a resolución completa.
 *    Si ORB falla (escena oscura/lisa): ECC euclídeo a 320 px. Cuadros no alineables se descartan.
 * 3. Promedio ROBUSTO por bandas horizontales (memoria acotada): peso por píxel w = 1 / (1 + (d/c)²) con
 *    d = |Y_i/g_i - Y_ref| suavizada 3x3 (g_i = ganancia del cuadro: AE/AWB pueden variar entre fotos) y
 *    c = clamp(3σ, 4, 25) con σ en niveles REALES, medido de las diferencias entre cuadros -> el ruido se
 *    promedia (σ/√N) pero las zonas desalineadas o con movimiento no dejan "fantasmas".
 * 4. Anti-reflejos: reflejo = brillo ANÓMALO respecto al papel local (no un umbral absoluto, que en una hoja
 *    blanca bien iluminada cubría la página entera). Dentro de la máscara suavizada se promedian los cuadros que
 *    no superan el papel local + 3σ en ese píxel (el reflejo se desplaza entre cuadros al mover un poco el
 *    móvil); sólo donde todos están deslumbrados se toma el más oscuro.
 *
 * Medido (PC, 1 hilo, 4 cuadros de 3 MP, ruido σ=7): ruido del papel 7.9 -> 4.1 (x1.9), reflejo 251 -> 75 de
 * luminancia (valor real 71). Memoria: N cuadros RGB de 8 bits + 2 máscaras a [maxPixels] (nada en float a
 * tamaño completo).
 */
object MultiFrameFusion {

    private const val TAG = "MultiFrameFusion"

    /** Resultado de una fusión. */
    data class Stats(
        val inputFrames: Int,
        val usedFrames: Int,
        val glareCorrected: Boolean,
        val noiseSigma: Double,
        val width: Int,
        val height: Int,
    )

    /** Cuadros recomendados para la ráfaga: 3 en gama baja (memoria/tiempo), 4 en el resto. */
    fun recommendedFrameCount(tier: DeviceTier): Int = if (tier.isLowRam || tier.cores <= 4) 3 else 4

    /** Píxeles de trabajo de la fusión: ~3 MP en gama baja, hasta 6 MP en el resto. */
    fun recommendedMaxPixels(tier: DeviceTier): Int =
        if (tier.isLowRam || tier.cores <= 4) min(tier.maxWorkingPixels, 3_000_000) else min(tier.maxWorkingPixels, 6_000_000)

    /**
     * Fusiona los JPEG de [paths] y escribe el resultado (JPEG) en [outPath]. Decodifica de uno en uno a
     * [maxPixels]. Si sólo hay un cuadro válido, el resultado es ese cuadro. Lanza excepción si no se pudo leer
     * ninguno. No borra las entradas.
     */
    fun fuseFiles(
        paths: List<String>,
        outPath: String,
        maxPixels: Int,
        removeGlare: Boolean = true,
        jpegQuality: Int = 95,
        lowEnd: Boolean = true,
        progress: ProgressCallback? = null,
    ): Stats {
        require(paths.isNotEmpty())
        val mats = ArrayList<Mat>(paths.size)
        try {
            for ((i, p) in paths.withIndex()) {
                val bmp = try { BitmapIO.decode(p, maxPixels) } catch (t: Throwable) {
                    // Falta de memoria: se propaga (quien llama reintenta con menos píxeles), no es un cuadro ilegible
                    if (Cv.isOutOfMemory(t)) throw t
                    Log.w(TAG, "No se pudo leer $p", t); null
                } ?: continue
                try {
                    val m = Cv.toRgb(bmp)
                    // Todos al tamaño del primero (misma configuración de cámara: normalmente ya coinciden)
                    if (mats.isNotEmpty() && (m.cols() != mats[0].cols() || m.rows() != mats[0].rows())) {
                        val r = Mat(); Imgproc.resize(m, r, mats[0].size(), 0.0, 0.0, Imgproc.INTER_AREA); m.release(); mats.add(r)
                    } else mats.add(m)
                } finally { bmp.recycle() }
                progress?.invoke(0.25f * (i + 1) / paths.size)
            }
            if (mats.isEmpty()) throw java.io.IOException("No se pudo leer ninguna foto de la ráfaga")
            val inputs = mats.size
            val (out, stats) = fuseMats(mats, removeGlare, lowEnd) { f -> progress?.invoke(0.25f + 0.65f * f) }
            mats.clear() // fuseMats libera las entradas
            try {
                val bmp = Cv.toBitmap(out)
                try { BitmapIO.saveJpeg(bmp, outPath, jpegQuality) } finally { bmp.recycle() }
            } finally { out.release() }
            progress?.invoke(1f)
            return stats.copy(inputFrames = inputs)
        } finally {
            for (m in mats) m.release()
        }
    }

    /** Variante con Bitmaps (no se reciclan). Devuelve un Bitmap nuevo. */
    fun fuse(frames: List<Bitmap>, removeGlare: Boolean = true, lowEnd: Boolean = true, progress: ProgressCallback? = null): Bitmap {
        require(frames.isNotEmpty())
        val mats = ArrayList<Mat>(frames.size)
        for (b in frames) {
            val m = Cv.toRgb(b)
            if (mats.isNotEmpty() && (m.cols() != mats[0].cols() || m.rows() != mats[0].rows())) {
                val r = Mat(); Imgproc.resize(m, r, mats[0].size(), 0.0, 0.0, Imgproc.INTER_AREA); m.release(); mats.add(r)
            } else mats.add(m)
        }
        val (out, _) = fuseMats(mats, removeGlare, lowEnd, progress)
        try { return Cv.toBitmap(out) } finally { out.release() }
    }

    // =====================================================================================

    /** Fusiona [frames] (RGB 8UC3, mismo tamaño). LIBERA las entradas. Devuelve (resultado, estadísticas). */
    internal fun fuseMats(frames: MutableList<Mat>, removeGlare: Boolean, lowEnd: Boolean, progress: ProgressCallback?): Pair<Mat, Stats> {
        val w = frames[0].cols(); val h = frames[0].rows()
        if (frames.size == 1) {
            val only = frames.removeAt(0)
            return only to Stats(1, 1, false, 0.0, w, h)
        }
        val alignSide = if (lowEnd) 640 else 960
        // --- Grises reducidos + nitidez para elegir la referencia ---
        val smallG = ArrayList<Mat>(frames.size)
        var scale = 1.0
        val sharp = DoubleArray(frames.size)
        for ((i, f) in frames.withIndex()) {
            val g = Cv.gray(f)
            val s = Mat(); scale = Cv.downscale(g, s, alignSide); g.release()
            smallG.add(s)
            sharp[i] = laplacianVar(s)
        }
        val refIdx = sharp.indices.maxByOrNull { sharp[it] } ?: 0
        val ref = frames[refIdx]
        val aligned = ArrayList<Mat>(frames.size - 1)
        try {
            var done = 0
            for (i in frames.indices) {
                if (i == refIdx) continue
                val hm = estimateHomography(smallG[refIdx], smallG[i], scale)
                if (hm != null) {
                    val m = Mat(3, 3, CvType.CV_64F); m.put(0, 0, *hm)
                    val wf = Mat()
                    Imgproc.warpPerspective(frames[i], wf, m, ref.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
                    m.release()
                    aligned.add(wf)
                } else Log.i(TAG, "Cuadro $i descartado: no se pudo alinear")
                // El cuadro original ya no hace falta: liberar cuanto antes (pico de memoria)
                frames[i].release()
                done++
                progress?.invoke(0.6f * done / (frames.size - 1))
            }
            for (s in smallG) s.release()
            smallG.clear()
            frames.clear()
            if (aligned.isEmpty()) return ref to Stats(0, 1, false, 0.0, w, h)

            // Ruido en NIVELES REALES (no relativo al papel normalizado a 230: con poca luz el papel está a 50-80
            // y el relativo salía 3-4,5x mayor, el peso robusto ya no rechazaba nada y el texto salía doble).
            // Se mide de las propias diferencias entre cuadros alineados; además, la ganancia de cada cuadro
            // respecto a la referencia (AE/AWB no bloqueados entre fotos).
            val refY = Cv.gray(ref)
            val (sigma, gains) = try {
                noiseAndGains(refY, aligned) ?: run {
                    val (rel, lvl) = Cv.estimatePaperNoiseLevel(ref)
                    max(1.0, rel * lvl) to DoubleArray(aligned.size) { 1.0 }
                }
            } finally { refY.release() }
            val out = merge(ref, aligned, sigma, gains, removeGlare) { f -> progress?.invoke(0.6f + 0.4f * f) }
            val glare = out.second
            ref.release()
            return out.first to Stats(0, aligned.size + 1, glare, sigma, w, h)
        } catch (t: Throwable) {
            ref.release()
            throw t
        } finally {
            for (a in aligned) a.release()
            for (s in smallG) s.release()
            for (f in frames) f.release()
        }
    }

    private fun laplacianVar(g: Mat): Double {
        val lap = Mat(); val mean = org.opencv.core.MatOfDouble(); val sd = org.opencv.core.MatOfDouble()
        try {
            Imgproc.Laplacian(g, lap, CvType.CV_16S, 3)
            Core.meanStdDev(lap, mean, sd)
            val s = sd.toArray()[0]
            return s * s
        } finally { lap.release(); mean.release(); sd.release() }
    }

    /**
     * Homografía 3x3 (fila mayor) que lleva coordenadas de [img] a [ref] A RESOLUCIÓN COMPLETA (los grises están
     * reducidos por [scale]). Null si no hay una alineación fiable.
     */
    internal fun estimateHomography(ref: Mat, img: Mat, scale: Double): DoubleArray? {
        val small = orbHomography(ref, img) ?: eccEuclidean(ref, img) ?: return null
        // H_full = S^-1 · H_small · S  con S = diag(scale, scale, 1)
        val s = scale; val inv = 1.0 / scale
        val hf = doubleArrayOf(
            small[0], small[1], small[2] * inv,
            small[3], small[4], small[5] * inv,
            small[6] * s, small[7] * s, small[8],
        )
        return if (plausible(hf, ref.cols() * inv, ref.rows() * inv)) hf else null
    }

    /** Desplazamiento < 12 % del lado, escala 0.85..1.18, perspectiva leve. */
    private fun plausible(hm: DoubleArray, w: Double, h: Double): Boolean {
        if (hm.any { it.isNaN() || it.isInfinite() }) return false
        val det = hm[0] * hm[4] - hm[1] * hm[3]
        if (det < 0.72 || det > 1.4) return false
        if (abs(hm[6]) * w > 0.08 || abs(hm[7]) * h > 0.08) return false
        // Desplazamiento del centro
        val cx = w / 2; val cy = h / 2
        val z = hm[6] * cx + hm[7] * cy + hm[8]
        if (abs(z) < 1e-6) return false
        val x = (hm[0] * cx + hm[1] * cy + hm[2]) / z
        val y = (hm[3] * cx + hm[4] * cy + hm[5]) / z
        return hypot(x - cx, y - cy) < 0.12 * max(w, h)
    }

    private fun orbHomography(ref: Mat, img: Mat): DoubleArray? {
        val orb = ORB.create(900)
        val k1 = MatOfKeyPoint(); val k2 = MatOfKeyPoint(); val d1 = Mat(); val d2 = Mat()
        val empty = Mat()
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val knn = ArrayList<MatOfDMatch>()
        val src = MatOfPoint2f(); val dst = MatOfPoint2f(); val inl = Mat()
        try {
            orb.detectAndCompute(ref, empty, k1, d1)
            orb.detectAndCompute(img, empty, k2, d2)
            if (d1.rows() < 20 || d2.rows() < 20) return null
            matcher.knnMatch(d2, d1, knn, 2)
            val kp1: Array<KeyPoint> = k1.toArray(); val kp2: Array<KeyPoint> = k2.toArray()
            val ps = ArrayList<Point>(); val pd = ArrayList<Point>()
            for (mm in knn) {
                val m: Array<DMatch> = mm.toArray()
                if (m.size == 2 && m[0].distance < 0.8f * m[1].distance) {
                    ps.add(kp2[m[0].queryIdx].pt); pd.add(kp1[m[0].trainIdx].pt)
                }
            }
            if (ps.size < 20) return null
            src.fromList(ps); dst.fromList(pd)
            val hm = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3.0, inl, 2000, 0.995)
            if (hm.empty()) { hm.release(); return null }
            val inliers = Core.countNonZero(inl)
            val out = DoubleArray(9); hm.get(0, 0, out); hm.release()
            if (inliers < 15) return null
            return out
        } catch (t: Throwable) {
            if (Cv.isOutOfMemory(t)) throw t
            Log.w(TAG, "ORB falló", t)
            return null
        } finally {
            k1.release(); k2.release(); d1.release(); d2.release(); empty.release()
            for (m in knn) m.release()
            src.release(); dst.release(); inl.release()
        }
    }

    /** Alternativa para escenas con pocas esquinas: ECC euclídeo a ~320 px. Devuelve img -> ref (3x3). */
    private fun eccEuclidean(ref: Mat, img: Mat): DoubleArray? {
        val r = Mat(); val i = Mat(); val warp = Mat.eye(2, 3, CvType.CV_32F); val noMask = Mat()
        try {
            val s = Cv.downscale(ref, r, 320); Cv.downscale(img, i, 320)
            Video.findTransformECC(r, i, warp, Video.MOTION_EUCLIDEAN,
                TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-5), noMask, 3)
            val a = FloatArray(6); warp.get(0, 0, a)
            // warp: ref -> img (a escala s). Invertir la afín y re-escalar a la resolución de [ref].
            val det = a[0].toDouble() * a[4] - a[1].toDouble() * a[3]
            if (abs(det) < 1e-6) return null
            val i00 = a[4] / det; val i01 = -a[1] / det; val i10 = -a[3] / det; val i11 = a[0] / det
            val tx = -(i00 * a[2] + i01 * a[5]); val ty = -(i10 * a[2] + i11 * a[5])
            val inv = 1.0 / s
            return doubleArrayOf(i00, i01, tx * inv, i10, i11, ty * inv, 0.0, 0.0, 1.0)
        } catch (t: Throwable) {
            if (Cv.isOutOfMemory(t)) throw t
            return null
        } finally { r.release(); i.release(); warp.release(); noMask.release() }
    }

    /**
     * σ del ruido (niveles reales de Y) y ganancia de cada cuadro respecto a [refY], medidos sobre una rejilla
     * de píxeles (vecino más cercano: sin promediar, el ruido se conserva) en la mitad clara de la imagen
     * (papel). σ = mediana(|g⁻¹·Y_i − Y_ref|) / (0,6745·√2) (la diferencia lleva el ruido de los dos cuadros);
     * los bordes desalineados quedan fuera por ser minoría. Null si no hay suficientes muestras.
     */
    private fun noiseAndGains(refY: Mat, frames: List<Mat>): Pair<Double, DoubleArray>? = MatBag().use { bag ->
        val w = refY.cols(); val h = refY.rows()
        val step = max(1.0, kotlin.math.sqrt(w.toDouble() * h / 250_000.0))
        val sz = Size(max(8.0, kotlin.math.floor(w / step)), max(8.0, kotlin.math.floor(h / step)))
        val rs = bag.mat(); Imgproc.resize(refY, rs, sz, 0.0, 0.0, Imgproc.INTER_NEAREST)
        val rHist = Cv.histogram(rs)
        val p50 = Cv.percentile(rHist, 0.5)
        val mask = bag.mat(); val t = bag.mat()
        Core.compare(rs, Scalar(max(8, p50).toDouble()), mask, Core.CMP_GE)
        Core.compare(rs, Scalar(250.0), t, Core.CMP_LT); Core.bitwise_and(mask, t, mask)
        if (Core.countNonZero(mask) < 2000) return@use null
        val medR = max(1, Cv.percentile(Cv.histogram(rs, mask), 0.5)).toDouble()
        val fsRgb = bag.mat(); val fs = bag.mat(); val r16 = bag.mat(); val f16 = bag.mat(); val d8 = bag.mat()
        rs.convertTo(r16, CvType.CV_16S)
        val gains = DoubleArray(frames.size)
        val sig = ArrayList<Double>(frames.size)
        for ((i, f) in frames.withIndex()) {
            Imgproc.resize(f, fsRgb, sz, 0.0, 0.0, Imgproc.INTER_NEAREST)
            Imgproc.cvtColor(fsRgb, fs, Imgproc.COLOR_RGB2GRAY)
            val medF = max(1, Cv.percentile(Cv.histogram(fs, mask), 0.5)).toDouble()
            val g = (medF / medR).coerceIn(0.75, 1.33)
            gains[i] = g
            fs.convertTo(f16, CvType.CV_16S, 1.0 / g)
            Core.subtract(f16, r16, f16)
            Core.absdiff(f16, Scalar(0.0), f16)
            f16.convertTo(d8, CvType.CV_8U)
            val mad = fractionalMedian(Cv.histogram(d8, mask))
            sig.add(max(0.5, mad) / (0.6745 * kotlin.math.sqrt(2.0)))
        }
        sig.sort()
        max(1.0, sig[sig.size / 2]) to gains
    }

    /** Mediana de |d| (enteros) interpolada dentro del bin: evita el sesgo de cuantización con σ pequeños. */
    private fun fractionalMedian(hist: DoubleArray): Double {
        val target = hist.sum() / 2
        if (target <= 0) return 0.0
        var acc = 0.0
        for (i in 0 until 256) {
            if (acc + hist[i] >= target) {
                val lo = if (i == 0) 0.0 else i - 0.5
                val width = if (i == 0) 0.5 else 1.0
                return lo + width * (target - acc) / max(hist[i], 1.0)
            }
            acc += hist[i]
        }
        return 255.0
    }

    /**
     * Máscara de REFLEJO: brillo anómalo respecto al papel LOCAL (no un umbral absoluto: en una hoja blanca bien
     * iluminada el propio papel supera 235 y la máscara cubría toda la página). Fondo local = mediana amplia a
     * baja resolución (rechaza manchas de hasta ~la mitad de la ventana). Núcleo = Y ≥ fondo + 20, o Y ≥ 250 y
     * ≥ fondo + 10. Si el núcleo supera el 25 % de la página no es un reflejo (papel sobreexpuesto): null.
     * Devuelve (máscara suave 8U dilatada y difuminada, fondo local 8U a resolución completa).
     */
    private fun glareMask(refY: Mat): Pair<Mat, Mat>? = MatBag().use { bag ->
        val w = refY.cols(); val h = refY.rows()
        val sm = bag.mat(); Cv.downscale(refY, sm, 192)
        val side = max(sm.cols(), sm.rows())
        val bgS = bag.mat(); Imgproc.medianBlur(sm, bgS, Cv.odd(max(5, side / 4)))
        Imgproc.GaussianBlur(bgS, bgS, Size(0.0, 0.0), max(1.0, side / 60.0))
        val bg = Mat(); Imgproc.resize(bgS, bg, refY.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val diff = bag.mat(); Core.subtract(refY, bg, diff) // saturado a 0
        val core = bag.mat(); val t = bag.mat()
        Core.compare(diff, Scalar(20.0), core, Core.CMP_GE)
        Core.compare(diff, Scalar(10.0), t, Core.CMP_GE)
        val t2 = bag.mat(); Core.compare(refY, Scalar(250.0), t2, Core.CMP_GE)
        Core.bitwise_and(t, t2, t); Core.bitwise_or(core, t, core)
        val count = Core.countNonZero(core).toDouble()
        val total = w.toDouble() * h
        if (count < 0.0005 * total || count > 0.25 * total) { bg.release(); return@use null }
        val long = max(w, h)
        val gm = Mat()
        Imgproc.dilate(core, gm, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd(max(5, long / 300))))
        Imgproc.GaussianBlur(gm, gm, Size(0.0, 0.0), max(2.0, long / 500.0))
        gm to bg
    }

    /**
     * Promedio robusto + anti-reflejos por bandas. [sigma] en niveles reales de Y; [gains] = ganancia de cada
     * cuadro respecto a la referencia (se compensa antes de comparar y de promediar).
     * Devuelve (resultado RGB 8UC3, ¿se corrigieron reflejos?).
     */
    private fun merge(
        ref: Mat, frames: List<Mat>, sigma: Double, gains: DoubleArray, removeGlare: Boolean, progress: ProgressCallback?,
    ): Pair<Mat, Boolean> = MatBag().use { bag ->
        val w = ref.cols(); val h = ref.rows()
        val refY = bag.add(Cv.gray(ref))
        var glareSoft: Mat? = null
        var localBg: Mat? = null
        if (removeGlare) glareMask(refY)?.let { (m, b) -> glareSoft = bag.add(m); localBg = bag.add(b) }
        val c = (3.0 * sigma).coerceIn(4.0, 25.0)
        // Dentro del reflejo cuentan los cuadros cuyo píxel NO está por encima del papel local + margen de ruido
        val glareMargin = max(8.0, 3.0 * sigma)
        val out = Mat(h, w, CvType.CV_8UC3)
        val band = max(32, min(h, 600_000 / max(1, w)))
        val acc = bag.mat(); val wsum = bag.mat(); val wgt = bag.mat(); val w3 = bag.mat(); val ff = bag.mat()
        val yb = bag.mat(); val d = bag.mat(); val df = bag.mat(); val best = bag.mat(); val bestY = bag.mat()
        val cmp = bag.mat(); val thr = bag.mat(); val o8 = bag.mat(); val g3 = bag.mat(); val gf = bag.mat()
        val gAcc = bag.mat(); val gCnt = bag.mat(); val vf = bag.mat(); val v3 = bag.mat(); val lim = bag.mat()
        var y = 0
        while (y < h) {
            val y1 = min(h, y + band)
            val refB = ref.submat(y, y1, 0, w); val refYB = refY.submat(y, y1, 0, w)
            refB.convertTo(acc, CvType.CV_32FC3)
            wsum.create(y1 - y, w, CvType.CV_32FC1); wsum.setTo(Scalar(1.0))
            val glareB = glareSoft?.submat(y, y1, 0, w)
            val doGlare = glareB != null && Core.countNonZero(glareB) > 0
            if (doGlare) {
                refB.copyTo(best); refYB.copyTo(bestY)
                val bgB = localBg!!.submat(y, y1, 0, w)
                Core.add(bgB, Scalar(glareMargin), lim)
                bgB.release()
                // La referencia también cuenta donde no está deslumbrada
                Core.compare(refYB, lim, cmp, Core.CMP_LE)
                cmp.convertTo(vf, CvType.CV_32F, 1.0 / 255.0)
                vf.copyTo(gCnt)
                Core.merge(listOf(vf, vf, vf), v3)
                Core.multiply(acc, v3, gAcc)
            }
            for ((fi, f) in frames.withIndex()) {
                val inv = 1.0 / gains[fi]
                val fb = f.submat(y, y1, 0, w)
                Imgproc.cvtColor(fb, yb, Imgproc.COLOR_RGB2GRAY)
                if (inv != 1.0) yb.convertTo(yb, -1, inv)
                Core.absdiff(yb, refYB, d)
                Imgproc.blur(d, d, Size(3.0, 3.0))
                d.convertTo(df, CvType.CV_32F, 1.0 / c)
                Core.multiply(df, df, df)
                Core.add(df, Scalar(1.0), df)
                Core.divide(1.0, df, wgt)                      // w = 1/(1+(d/c)²)
                Core.add(wsum, wgt, wsum)
                Core.merge(listOf(wgt, wgt, wgt), w3)
                fb.convertTo(ff, CvType.CV_32FC3, inv)
                if (doGlare) {
                    // Media de los cuadros no deslumbrados en ese píxel (la tinta, muy por debajo del papel,
                    // siempre cuenta: no se elige "el más oscuro" y los trazos no engordan con desajustes).
                    Core.compare(yb, lim, cmp, Core.CMP_LE)
                    cmp.convertTo(vf, CvType.CV_32F, 1.0 / 255.0)
                    Core.add(gCnt, vf, gCnt)
                    Core.merge(listOf(vf, vf, vf), v3)
                    Core.multiply(ff, v3, v3)
                    Core.add(gAcc, v3, gAcc)
                    // Respaldo donde TODOS los cuadros están deslumbrados: el más oscuro
                    Core.subtract(bestY, Scalar(8.0), thr)
                    Core.compare(yb, thr, cmp, Core.CMP_LT)
                    fb.copyTo(best, cmp)
                    yb.copyTo(bestY, cmp)
                }
                Core.multiply(ff, w3, ff)
                Core.add(acc, ff, acc)
                fb.release()
            }
            Core.merge(listOf(wsum, wsum, wsum), w3)
            Core.divide(acc, w3, acc)
            if (doGlare) {
                // gAvg = media de los válidos, o el más oscuro donde no hay ninguno válido
                Core.compare(gCnt, Scalar(0.5), cmp, Core.CMP_LT)
                Core.max(gCnt, Scalar(1.0), gCnt)
                Core.merge(listOf(gCnt, gCnt, gCnt), v3)
                Core.divide(gAcc, v3, gAcc)
                best.convertTo(ff, CvType.CV_32FC3)
                ff.copyTo(gAcc, cmp)
                // out = avg·(1-g) + gAvg·g
                glareB!!.convertTo(gf, CvType.CV_32F, 1.0 / 255.0)
                Core.merge(listOf(gf, gf, gf), g3)
                Core.subtract(gAcc, acc, ff)
                Core.multiply(ff, g3, ff)
                Core.add(acc, ff, acc)
            }
            acc.convertTo(o8, CvType.CV_8UC3)
            val dst = out.submat(y, y1, 0, w); o8.copyTo(dst); dst.release()
            glareB?.release(); refB.release(); refYB.release()
            y = y1
            progress?.invoke(y.toFloat() / h)
        }
        out to (glareSoft != null)
    }
}
