package com.scannerpromax.imaging

import android.graphics.Bitmap
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Detección de bordes del documento. Thread-safe; reutiliza buffers internamente.
 *
 * Estrategias combinadas y puntuadas (a ~500 px de lado largo; en vivo 320-400 px):
 *  1. Canny con umbrales sacados del MÓDULO DEL GRADIENTE (percentil) sobre luminancia L (Lab), con CLAHE
 *     suave si hay poca luz.
 *  2. Bordes de color: saturación ponderada por brillo y canales a/b de Lab.
 *  3. Máscara de bordes sensible (Canny 20/50 en luminancia + Canny en crominancia) cerrada.
 *  4. Segmentación Otsu del papel (claro) + apertura morfológica.
 *  5. Segmentación por COLOR: Otsu en Cb y Cr (clase y su inversa, cierre amplio que une las dos páginas de
 *     un cuaderno). Las sombras duras de la mano o el celular cambian el brillo pero apenas el tono, y la
 *     madera clara se separa del papel/marco aunque tenga brillo parecido. En vivo usa los planos U/V.
 *  6. HoughLinesP extendidas (sólo foto completa o vivo sin crominancia).
 * Cada contorno grande -> casco convexo -> approxPolyDP / mejor cuadrilátero inscrito / cuadrilátero
 * ENVOLVENTE mínimo con lados sobre aristas del casco (recupera esquinas redondeadas o "mordidas" por una
 * sombra) / minAreaRect. Cada hipótesis se ajusta además a los bordes reales (máximo contraste a lo largo de
 * la normal, rectas por mitades de lado para páginas curvadas).
 * Puntuación: área, ángulos, y apoyo de CADA lado sobre bordes con contraste de color a ambos lados (la veta
 * de la madera, la cuadrícula o el texto no cuentan). Los tramos pegados al borde de la imagen son neutros:
 * un documento que se sale del encuadre queda con ese lado sobre el borde (nunca dos lados opuestos).
 */
class DocumentDetector(private val tier: DeviceTier) {

    private val detectSide = 500
    private val liveSide = if (tier.isLowRam) 320 else 400

    /** Buffers reutilizables de una pasada de detección. */
    private class Buffers {
        val blur = Mat(); val tmp = Mat(); val edges = Mat(); val edges2 = Mat()
        val otsu = Mat(); val emask = Mat(); val emaskClosed = Mat(); val hough = Mat(); val lines = Mat(); val hierarchy = Mat()
        val gx = Mat(); val gy = Mat(); val mag = Mat(); val mag2 = Mat(); val colorTmp = Mat()
        val hullIdx = MatOfInt(); val approx = MatOfPoint2f(); val hull2f = MatOfPoint2f()
        val chromaMasks = arrayOf(Mat(), Mat()); val chromaMasksInv = arrayOf(Mat(), Mat())
        val sideSupport = DoubleArray(5)
        val chromaBlur = arrayOf(Mat(), Mat())
        var emaskBytes = ByteArray(0)
        var lBytes = ByteArray(0)
        var cBytes = arrayOf(ByteArray(0), ByteArray(0))
        var nChroma = 0
        // Cuadriláteros ya evaluados en esta pasada (para no repetir puntuación y ajuste)
        private val seen = ArrayList<FloatArray>(48)
        fun resetSeen() = seen.clear()
        fun seenDuplicate(q: FloatArray): Boolean {
            val tol = 0.012f * (w + h)
            for (o in seen) {
                var same = true
                for (i in 0 until 4) {
                    if (abs(o[i * 2] - q[i * 2]) > tol || abs(o[i * 2 + 1] - q[i * 2 + 1]) > tol) { same = false; break }
                }
                if (same) return true
            }
            seen.add(q.copyOf())
            return false
        }
        var w = 0; var h = 0
        fun release() {
            blur.release(); tmp.release(); edges.release(); edges2.release(); otsu.release(); emask.release()
            emaskClosed.release(); hough.release(); lines.release(); hierarchy.release()
            gx.release(); gy.release(); mag.release(); mag2.release(); colorTmp.release()
            hullIdx.release(); approx.release(); hull2f.release()
            for (m in chromaMasks) m.release()
            for (m in chromaBlur) m.release()
            for (m in chromaMasksInv) m.release()
        }
    }

    /** Depuración (banco de pruebas): recibe cada cuadrilátero evaluado con su puntuación. */
    internal var debugCand: ((FloatArray, Double, String) -> Unit)? = null

    private val kernel3: Mat by lazy { Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)) }

    // ---- Estado de la detección en vivo (protegido por @Synchronized) ----
    private var liveBuf: Buffers? = null
    private var yBytes = ByteArray(0)
    private var yMat: Mat? = null
    private var yRoi: Mat? = null
    private var liveSmall: Mat? = null
    private var liveRot: Mat? = null
    private var liveClahe: org.opencv.imgproc.CLAHE? = null

    private class Candidate(val pts: FloatArray, val score: Double)

    // =====================================================================================
    // API pública
    // =====================================================================================

    /** Detección precisa sobre una foto completa (tras capturar). */
    fun detect(bitmap: Bitmap): DetectionResult? {
        val w = bitmap.width; val h = bitmap.height
        if (w < 32 || h < 32) return null
        val bag = MatBag()
        try {
            // Reducción SIN aliasing (mitades + INTER_AREA): createScaledBitmap x8 convertía el texto en
            // sal y pimienta, disparaba la densidad de bordes y creaba contornos espurios.
            val rgba = bag.add(Cv.toRgbaScaled(bitmap, detectSide).first)
            val rgb = bag.mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            return detectScaled(rgb, w, h) { x0, y0, rw, rh ->
                var crop: Bitmap? = null
                try {
                    crop = Bitmap.createBitmap(bitmap, x0, y0, rw, rh)
                    val m = Cv.toRgba(crop)
                    val g = Mat(); Imgproc.cvtColor(m, g, Imgproc.COLOR_RGBA2GRAY); m.release()
                    g
                } finally {
                    if (crop != null && crop !== bitmap) crop.recycle()
                }
            }
        } catch (t: Throwable) {
            return null
        } finally {
            bag.close()
        }
    }

    /**
     * Núcleo de [detect] sin dependencias de Bitmap: [rgbSmall] es la foto reducida (RGB, lado largo ~500 px)
     * de una imagen de [fullW]x[fullH]; [grayCrop] devuelve un recorte en grises a resolución completa (nuevo
     * Mat, lo libera quien llama) para el refinamiento sub-píxel de esquinas. Coordenadas del resultado en
     * píxeles de la imagen completa.
     */
    internal fun detectScaled(rgbSmall: Mat, fullW: Int, fullH: Int, grayCrop: ((Int, Int, Int, Int) -> Mat?)?): DetectionResult? {
        val buf = Buffers()
        val bag = MatBag()
        try {
            val cand = detectColor(rgbSmall, buf, bag) ?: return null
            val pts = scaleToFrame(cand.pts, rgbSmall.cols(), rgbSmall.rows(), fullW, fullH)
            if (grayCrop != null) refineCorners(fullW, fullH, grayCrop, pts)
            clampPts(pts, fullW, fullH)
            return DetectionResult(toQuad(pts), confidenceOf(cand.score), fullW, fullH)
        } finally {
            bag.close(); buf.release()
        }
    }

    /** Prepara los canales (L con CLAHE si hay poca luz, saturación ponderada, a/b) y ejecuta [detectCore]. */
    private fun detectColor(rgb: Mat, buf: Buffers, bag: MatBag): Candidate? {
        val lab = bag.mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val l = bag.mat(); Core.extractChannel(lab, l, 0)
        enhanceLowLight(l, null)
        val hsv = bag.mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val sat = bag.mat(); Core.extractChannel(hsv, sat, 1)
        val v = bag.mat(); Core.extractChannel(hsv, v, 2)
        // Saturación ponderada por brillo: evita el ruido cromático de zonas oscuras
        val sw8 = bag.mat(); Core.multiply(sat, v, sw8, 1.0 / 255.0)
        // Canales a/b de Lab amplificados (x3 alrededor de 128): separan tonos parecidos en brillo
        val ca = bag.mat(); Core.extractChannel(lab, ca, 1); ca.convertTo(ca, -1, 3.0, -256.0)
        val cb = bag.mat(); Core.extractChannel(lab, cb, 2); cb.convertTo(cb, -1, 3.0, -256.0)
        // Crominancia YCrCb (la misma que el plano U/V de la cámara): segmentación por color insensible a sombras
        val ycc = bag.mat(); Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
        val cr = bag.mat(); Core.extractChannel(ycc, cr, 1)
        val cbb = bag.mat(); Core.extractChannel(ycc, cbb, 2)
        return detectCore(l, listOf(sw8, ca, cb), listOf(cbb, cr), buf, live = false)
    }

    /**
     * Detección rápida para vista previa en vivo, desde el plano Y (luminancia) de un frame YUV de CameraX.
     * [rowStride] del plano. [rotationDegrees] la rotación del frame; el quad devuelto ya está en
     * coordenadas del frame ROTADO (ancho/alto intercambiados si 90/270).
     *
     * Si se pasan los planos de crominancia [uPlane]/[vPlane] (YUV_420_888: mitad de resolución, con
     * [uvRowStride] y [uvPixelStride]), se usa además la segmentación por color, que no se ve afectada por
     * las sombras (papel/marco de color frente a madera, sombras duras de la mano o el celular).
     */
    @Synchronized
    fun detectLive(
        yPlane: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, rotationDegrees: Int,
        uPlane: java.nio.ByteBuffer? = null, vPlane: java.nio.ByteBuffer? = null, uvRowStride: Int = 0, uvPixelStride: Int = 1,
    ): DetectionResult? {
        if (width < 32 || height < 32 || rowStride < width) return null
        liveFrameSeq++
        try {
            val need = rowStride * height
            if (yBytes.size != need) yBytes = ByteArray(need)
            val dup = yPlane.duplicate()
            dup.rewind()
            val n = min(dup.remaining(), need)
            dup.get(yBytes, 0, n)

            var ym = yMat
            if (ym == null || ym.rows() != height || ym.cols() != rowStride) {
                yRoi?.release(); ym?.release()
                ym = Mat(height, rowStride, CvType.CV_8UC1)
                yMat = ym
                yRoi = ym.submat(0, height, 0, width)
            }
            ym.put(0, 0, yBytes)
            val roi = yRoi!!

            val small = liveSmall ?: Mat().also { liveSmall = it }
            Cv.downscale(roi, small, liveSide)
            val rot = ((rotationDegrees % 360) + 360) % 360
            val work: Mat = when (rot) {
                90 -> (liveRot ?: Mat().also { liveRot = it }).also { Core.rotate(small, it, Core.ROTATE_90_CLOCKWISE) }
                180 -> (liveRot ?: Mat().also { liveRot = it }).also { Core.rotate(small, it, Core.ROTATE_180) }
                270 -> (liveRot ?: Mat().also { liveRot = it }).also { Core.rotate(small, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
                else -> small
            }
            val buf = liveBuf ?: Buffers().also { liveBuf = it }
            // Crominancia (U≈Cb, V≈Cr) a la resolución de trabajo y con la misma rotación
            val chroma = ArrayList<Mat>(2)
            if (uPlane != null && vPlane != null && uvRowStride > 0 && uvPixelStride in 1..2) {
                val cw = (width + 1) / 2; val chh = (height + 1) / 2
                val u = liveChromaPlane(0, uPlane, cw, chh, uvRowStride, uvPixelStride)
                val v = liveChromaPlane(1, vPlane, cw, chh, uvRowStride, uvPixelStride)
                if (u != null && v != null) {
                    for ((k, src) in listOf(u, v).withIndex()) {
                        val dst = liveChromaWork[k] ?: Mat().also { liveChromaWork[k] = it }
                        val tmp = liveChromaTmp[k] ?: Mat().also { liveChromaTmp[k] = it }
                        Imgproc.resize(src, tmp, small.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                        when (rot) {
                            90 -> Core.rotate(tmp, dst, Core.ROTATE_90_CLOCKWISE)
                            180 -> Core.rotate(tmp, dst, Core.ROTATE_180)
                            270 -> Core.rotate(tmp, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
                            else -> tmp.copyTo(dst)
                        }
                        chroma.add(dst)
                    }
                }
            }
            // Poca luz: CLAHE suave (instancia reutilizada entre frames). Se aplica sobre la copia reducida.
            if (Core.mean(work).`val`[0] < LOW_LIGHT_MEAN) {
                val c = liveClahe ?: Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).also { liveClahe = it }
                enhanceLowLight(work, c)
            }
            val cand = detectCore(work, emptyList(), chroma, buf, live = true) ?: return null
            val rotW = if (rot == 90 || rot == 270) height else width
            val rotH = if (rot == 90 || rot == 270) width else height
            val pts = scaleToFrame(cand.pts, work.cols(), work.rows(), rotW, rotH)
            val res = DetectionResult(toQuad(pts), confidenceOf(cand.score), rotW, rotH)
            rememberLiveQuad(pts, rot, width, height)
            return res
        } catch (t: Throwable) {
            return null
        } finally {
            if (lastLiveFrame != liveFrameSeq) lastLivePts = null
        }
    }

    // Planos de crominancia del frame en vivo (reutilizados)
    private val liveUvBytes = arrayOf(ByteArray(0), ByteArray(0))
    private val liveUvMat = arrayOfNulls<Mat>(2)
    private val liveUvRoi = arrayOfNulls<Mat>(2)
    private val liveUvPlane = arrayOfNulls<Mat>(2)
    private val liveChromaWork = arrayOfNulls<Mat>(2)
    private val liveChromaTmp = arrayOfNulls<Mat>(2)

    /** Copia un plano U o V (pixelStride 1 ó 2) a un Mat 8UC1 de [cw]x[chh]. Null si el búfer es inválido. */
    private fun liveChromaPlane(k: Int, plane: java.nio.ByteBuffer, cw: Int, chh: Int, rs: Int, ps: Int): Mat? {
        if (rs < cw * ps - (ps - 1)) return null
        // Filas completas de rs bytes; la última fila del búfer puede venir sin el relleno final
        val rowLen = if (rs % ps == 0) rs else rs + (ps - rs % ps)
        val need = rowLen * chh
        if (liveUvBytes[k].size != need) liveUvBytes[k] = ByteArray(need)
        val dup = plane.duplicate(); dup.rewind()
        val bytes = liveUvBytes[k]
        val n = min(dup.remaining(), rs * chh)
        if (n < rs * (chh - 1) + (cw - 1) * ps + 1) return null
        if (rowLen == rs) {
            dup.get(bytes, 0, n)
        } else {
            for (y in 0 until chh) {
                val len = min(rs, n - y * rs)
                if (len <= 0) break
                dup.position(y * rs); dup.get(bytes, y * rowLen, len)
            }
        }
        var m = liveUvMat[k]
        val cols = rowLen / ps
        if (m == null || m.rows() != chh || m.cols() != cols || m.channels() != ps) {
            liveUvRoi[k]?.release(); m?.release()
            m = Mat(chh, cols, if (ps == 2) CvType.CV_8UC2 else CvType.CV_8UC1)
            liveUvMat[k] = m
            liveUvRoi[k] = m.submat(0, chh, 0, cw)
        }
        m.put(0, 0, bytes)
        if (ps == 1) return liveUvRoi[k]
        val out = liveUvPlane[k] ?: Mat().also { liveUvPlane[k] = it }
        Core.extractChannel(liveUvRoi[k]!!, out, 0)
        return out
    }

    // Quad del último frame en vivo en coordenadas del frame SIN rotar (para medir el reflejo dentro del documento)
    private var liveFrameSeq = 0L
    private var lastLiveFrame = -1L
    private var lastLivePts: FloatArray? = null

    private fun rememberLiveQuad(rotPts: FloatArray, rot: Int, w: Int, h: Int) {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val xr = rotPts[2 * i]; val yr = rotPts[2 * i + 1]
            val (x, y) = when (rot) {
                90 -> yr to (h - xr)
                180 -> (w - xr) to (h - yr)
                270 -> (w - yr) to xr
                else -> xr to yr
            }
            out[2 * i] = x.coerceIn(0f, w.toFloat()); out[2 * i + 1] = y.coerceIn(0f, h.toFloat())
        }
        lastLivePts = out
        lastLiveFrame = liveFrameSeq
    }

    /** Libera los buffers nativos de la detección en vivo (llamar al cerrar la cámara). */
    @Synchronized
    fun releaseLiveBuffers() {
        liveBuf?.release(); liveBuf = null
        yRoi?.release(); yRoi = null
        yMat?.release(); yMat = null
        liveSmall?.release(); liveSmall = null
        liveRot?.release(); liveRot = null
        liveClahe = null
        for (k in 0..1) {
            liveUvRoi[k]?.release(); liveUvRoi[k] = null
            liveUvMat[k]?.release(); liveUvMat[k] = null
            liveUvPlane[k]?.release(); liveUvPlane[k] = null
            liveChromaWork[k]?.release(); liveChromaWork[k] = null
            liveChromaTmp[k]?.release(); liveChromaTmp[k] = null
            liveUvBytes[k] = ByteArray(0)
        }
        lastLivePts = null
        yBytes = ByteArray(0)
    }

    /**
     * Calidad (nitidez/brillo/reflejos) del ÚLTIMO frame pasado a [detectLive], reutilizando su luminancia
     * ya copiada (sin volver a copiar el plano Y ni asignar buffers). Null si aún no hay frame.
     */
    @Synchronized
    fun analyzeLastFrame(): QualityReport? {
        val roi = yRoi ?: return null
        if (roi.empty()) return null
        return try {
            // Los umbrales del analizador están calibrados a 640 px (reducción INTER_AREA)
            val tmp = Mat()
            try {
                Cv.downscale(roi, tmp, 640)
                // Reflejo sólo dentro del documento detectado en ese mismo frame (si lo hay)
                val pts = lastLivePts?.let { p ->
                    val sx = tmp.cols().toFloat() / roi.cols(); val sy = tmp.rows().toFloat() / roi.rows()
                    FloatArray(8) { i -> p[i] * if (i % 2 == 0) sx else sy }
                }
                QualityAnalyzer.analyzeGray(tmp, pts)
            } finally { tmp.release() }
        } catch (_: Throwable) { null }
    }

    // =====================================================================================
    // Núcleo
    // =====================================================================================

    private fun detectCore(l: Mat, colorChannels: List<Mat>, chroma: List<Mat>, b: Buffers, live: Boolean): Candidate? {
        val w = l.cols(); val h = l.rows()
        val area = w.toDouble() * h
        b.w = w; b.h = h

        Imgproc.GaussianBlur(l, b.blur, Size(5.0, 5.0), 0.0)

        // Máscara de bordes permisiva para puntuar (sin el marco de la imagen): luminancia + crominancia
        // (el borde marco de color/madera o papel/mesa puede tener poco contraste de brillo).
        Imgproc.Canny(b.blur, b.emask, 20.0, 50.0)
        for (ch in chroma) {
            // x3 alrededor de 128: los canales de croma tienen poco rango
            ch.convertTo(b.tmp, -1, 3.0, -256.0)
            Imgproc.GaussianBlur(b.tmp, b.tmp, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(b.tmp, b.colorTmp, 30.0, 75.0)
            Core.bitwise_or(b.emask, b.colorTmp, b.emask)
        }
        Imgproc.dilate(b.emask, b.emask, kernel3)
        Imgproc.rectangle(b.emask, Point(0.0, 0.0), Point(w - 1.0, h - 1.0), Scalar(0.0), 4)
        val n = w * h
        if (b.emaskBytes.size != n) b.emaskBytes = ByteArray(n)
        b.emask.get(0, 0, b.emaskBytes)
        // Imágenes suavizadas para medir el CONTRASTE entre ambos lados de cada lado candidato
        if (b.lBytes.size != n) b.lBytes = ByteArray(n)
        b.blur.get(0, 0, b.lBytes)
        b.nChroma = 0
        for ((k, ch) in chroma.withIndex()) {
            if (k >= 2) break
            Imgproc.GaussianBlur(ch, b.chromaBlur[k], Size(5.0, 5.0), 0.0)
            if (b.cBytes[k].size != n) b.cBytes[k] = ByteArray(n)
            b.chromaBlur[k].get(0, 0, b.cBytes[k])
            b.nChroma = k + 1
        }

        val sources = ArrayList<Pair<Mat, Double>>(10)
        // 1) Canny automático (percentil del gradiente) sobre L
        autoCanny(b.blur, b.edges, b)
        var primary: Mat? = null
        if (Core.countNonZero(b.edges) <= 0.12 * area) {
            Imgproc.morphologyEx(b.edges, b.edges, Imgproc.MORPH_CLOSE, kernel3, Point(-1.0, -1.0), 2)
            sources.add(b.edges to 1.0)
            primary = b.edges
        }
        // 2) Bordes de color: saturación ponderada y a/b de Lab (OR de los Canny de cada canal)
        if (colorChannels.isNotEmpty()) {
            b.edges2.create(h, w, CvType.CV_8UC1); b.edges2.setTo(Scalar(0.0))
            for (ch in colorChannels) {
                Imgproc.GaussianBlur(ch, b.tmp, Size(5.0, 5.0), 0.0)
                autoCanny(b.tmp, b.colorTmp, b)
                Core.bitwise_or(b.edges2, b.colorTmp, b.edges2)
            }
            if (Core.countNonZero(b.edges2) <= 0.12 * area) {
                Imgproc.morphologyEx(b.edges2, b.edges2, Imgproc.MORPH_CLOSE, kernel3, Point(-1.0, -1.0), 2)
                sources.add(b.edges2 to 1.0)
            }
        }
        // 3) Máscara sensible cerrada como fuente de contornos (bordes de bajo contraste)
        Imgproc.morphologyEx(b.emask, b.emaskClosed, Imgproc.MORPH_CLOSE, kernel3, Point(-1.0, -1.0), 2)
        if (Core.countNonZero(b.emaskClosed) <= 0.2 * area) sources.add(b.emaskClosed to 0.85)
        // 4) Segmentación Otsu (papel claro vs fondo; RETR_LIST también encuentra el caso inverso)
        Imgproc.GaussianBlur(l, b.otsu, Size(7.0, 7.0), 0.0)
        Imgproc.threshold(b.otsu, b.otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(b.otsu, b.otsu, Imgproc.MORPH_OPEN, kernel3, Point(-1.0, -1.0), 2)
        sources.add(b.otsu to 0.95)
        // 5) Segmentación por COLOR (Otsu en Cb y Cr): las sombras cambian el brillo pero apenas el tono, así
        //    que el papel/marco y la mesa siguen separados aunque una sombra dura cruce la hoja. Se usan la
        //    clase y su inversa, con cierre amplio para unir las dos páginas de un cuaderno (espiral neutra).
        for ((k, ch) in chroma.withIndex()) {
            if (k >= b.chromaMasks.size) break
            val m = b.chromaMasks[k]; val mi = b.chromaMasksInv[k]
            if (chromaSegment(ch, m, mi, b)) {
                sources.add(m to 1.0)
                sources.add(mi to 1.0)
            }
        }
        // 6) Líneas de Hough extendidas (cierra contornos rotos)
        val houghSrc = primary ?: b.emask
        // En vivo con crominancia, la segmentación por color ya cierra los contornos rotos y Hough es lo más caro
        val skipHough = live && chroma.isNotEmpty()
        if (!skipHough) Imgproc.HoughLinesP(houghSrc, b.lines, 1.0, Math.PI / 180.0, max(30, (w * 0.12).roundToInt()), w * 0.15, w * 0.04)
        if (!skipHough && !b.lines.empty()) {
            b.hough.create(h, w, CvType.CV_8UC1)
            b.hough.setTo(Scalar(0.0))
            val cnt = min(b.lines.rows(), 60)
            val seg = IntArray(4)
            val ext = 0.08 * w
            for (i in 0 until cnt) {
                b.lines.get(i, 0, seg)
                val dx = (seg[2] - seg[0]).toDouble(); val dy = (seg[3] - seg[1]).toDouble()
                val len = hypot(dx, dy) + 1e-6
                Imgproc.line(
                    b.hough,
                    Point(seg[0] - dx / len * ext, seg[1] - dy / len * ext),
                    Point(seg[2] + dx / len * ext, seg[3] + dy / len * ext),
                    Scalar(255.0), 2,
                )
            }
            sources.add(b.hough to 0.9)
        }

        var best: Candidate? = null
        b.resetSeen()
        val minArea = 0.05 * area
        for ((src, weight) in sources) {
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(src, contours, b.hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val withArea = contours.map { it to Imgproc.contourArea(it) }
                .filter { it.second >= minArea }
                .sortedByDescending { it.second }
                .take(if (live) 5 else 8)
            for ((c, _) in withArea) {
                val c2 = evaluateContour(c, b, weight, live)
                if (c2 != null && (best == null || c2.score > best.score)) best = c2
            }
            for (c in contours) c.release()
        }
        val res = best ?: return null
        if (res.score < 0.55) return null
        return res
    }

    /**
     * Otsu sobre un canal de crominancia suavizado. [dst] = clase alta, [dstInv] = clase baja, ambas limpiadas
     * (apertura) y cerradas con un núcleo ~3 % del lado para saltar la espiral o el lomo. Devuelve false si
     * las dos clases apenas difieren en tono (escena sin color útil: no aporta, sólo ruido).
     */
    private fun chromaSegment(ch: Mat, dst: Mat, dstInv: Mat, b: Buffers): Boolean {
        Imgproc.medianBlur(ch, b.tmp, 5)
        val t = Imgproc.threshold(b.tmp, dst, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val hi = Core.mean(b.tmp, dst).`val`[0]
        Core.bitwise_not(dst, dstInv)
        val lo = Core.mean(b.tmp, dstInv).`val`[0]
        if (!t.isFinite() || hi - lo < MIN_CHROMA_SPLIT) return false
        // Núcleo rectangular: separable (rápido en gama baja) y suficiente para unir ambas páginas
        val k = Cv.kernel(Imgproc.MORPH_RECT, Cv.odd(max(5, (max(b.w, b.h) * 0.03).roundToInt())))
        for (m in arrayOf(dst, dstInv)) {
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, kernel3, Point(-1.0, -1.0), 2)
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, k)
        }
        return true
    }

    private fun evaluateContour(c: MatOfPoint, b: Buffers, weight: Double, live: Boolean): Candidate? {
        val pts = c.toArray()
        if (pts.size < 4) return null
        Imgproc.convexHull(c, b.hullIdx, false)
        val idx = b.hullIdx.toArray()
        if (idx.size < 4) return null
        val hullPts = Array(idx.size) { pts[idx[it]] }
        val hull2f = b.hull2f
        hull2f.fromArray(*hullPts)
        val peri = Imgproc.arcLength(hull2f, true)
        var approx: Array<Point> = hullPts
        for (eps in doubleArrayOf(0.01, 0.02, 0.03, 0.05)) {
            Imgproc.approxPolyDP(hull2f, b.approx, eps * peri, true)
            approx = b.approx.toArray()
            if (approx.size <= 8) break
        }
        var best: Candidate? = null
        fun consider(q: FloatArray, wq: Double) {
            clampPts(q, b.w - 1, b.h - 1)
            // Las distintas fuentes suelen dar el mismo cuadrilátero: no repetir el trabajo
            if (b.seenDuplicate(q)) return
            val s = scoreQuad(q, b, live)
            debugCand?.invoke(q, s * wq, "raw")
            if (s > 0 && (best == null || s * wq > best!!.score)) best = Candidate(q, s * wq)
            // Misma hipótesis ajustada a los bordes reales (vértices del casco desplazados, esquinas redondeadas)
            val sq = snapQuad(q, b) ?: return
            val s2 = scoreQuad(sq, b, live)
            debugCand?.invoke(sq, s2, "snap")
            // Ya apoyado en bordes reales: la procedencia (aproximación, rectángulo mínimo) importa menos
            val ws = max(wq, 0.95 * (1.0 + wq) / 2) * 1.02
            if (s2 > 0 && (best == null || s2 * ws > best!!.score)) best = Candidate(sq, s2 * ws)
        }
        if (approx.size in 4..8) {
            val q = if (approx.size == 4) orderPoints(approx) else best4(approx)
            consider(q, weight * (if (approx.size == 4) 1.0 else 0.92))
        }
        // Cuadrilátero envolvente mínimo con lados apoyados en aristas del casco: recupera esquinas
        // redondeadas, tapadas por un dedo o "mordidas" por una sombra (las rectas de los lados largos se
        // cortan donde estaría la esquina real).
        var fine: Array<Point> = hullPts
        for (eps in doubleArrayOf(0.004, 0.008, 0.012, 0.02)) {
            Imgproc.approxPolyDP(hull2f, b.approx, eps * peri, true)
            fine = b.approx.toArray()
            if (fine.size <= 14) break
        }
        if (fine.size in 5..14) {
            val hullArea = abs(Imgproc.contourArea(b.approx))
            minEnclosingQuad(fine, hullArea)?.let { consider(it, weight * 0.95) }
        }
        // Rectángulo mínimo (documentos con esquinas dobladas/tapadas)
        val rr = Imgproc.minAreaRect(hull2f)
        val box = arrayOfNulls<Point>(4).also { rr.points(it) }.map { it!! }.toTypedArray()
        consider(orderPoints(box), weight * 0.7)
        return best
    }

    /**
     * Cuadrilátero de área mínima que contiene el polígono convexo [p] y cuyos 4 lados son rectas de aristas
     * de [p] (búsqueda exhaustiva, n <= 14). Null si añade más de un 20 % de área (la forma no es un cuadrilátero).
     */
    private fun minEnclosingQuad(p: Array<Point>, polyArea: Double): FloatArray? {
        val n = p.size
        if (n < 4 || polyArea <= 1.0) return null
        // Ángulo de cada arista en el orden del polígono
        val ang = DoubleArray(n) { val a = p[it]; val c = p[(it + 1) % n]; atan2(c.y - a.y, c.x - a.x) }
        var bestA = polyArea * 1.2
        var best: FloatArray? = null
        val ix = DoubleArray(4); val iy = DoubleArray(4)
        val sel = IntArray(4)
        for (a in 0 until n) for (b2 in a + 1 until n) for (c in b2 + 1 until n) for (d in c + 1 until n) {
            sel[0] = a; sel[1] = b2; sel[2] = c; sel[3] = d
            // Giro entre aristas consecutivas elegidas: cada uno en (0, π) para que el cuadrilátero sea cerrado
            var ok = true
            for (k in 0 until 4) {
                var dt = ang[sel[(k + 1) % 4]] - ang[sel[k]]
                while (dt <= 0) dt += 2 * Math.PI
                while (dt > 2 * Math.PI) dt -= 2 * Math.PI
                if (dt >= Math.PI - 0.05 || dt < 0.3) { ok = false; break }
            }
            if (!ok) continue
            for (k in 0 until 4) {
                val e1 = sel[k]; val e2 = sel[(k + 1) % 4]
                val p1 = p[e1]; val q1 = p[(e1 + 1) % n]; val p2 = p[e2]; val q2 = p[(e2 + 1) % n]
                val d1x = q1.x - p1.x; val d1y = q1.y - p1.y; val d2x = q2.x - p2.x; val d2y = q2.y - p2.y
                val den = d1x * d2y - d1y * d2x
                if (abs(den) < 1e-9) { ok = false; break }
                val t = ((p2.x - p1.x) * d2y - (p2.y - p1.y) * d2x) / den
                ix[k] = p1.x + d1x * t; iy[k] = p1.y + d1y * t
            }
            if (!ok) continue
            var ar = 0.0
            for (k in 0 until 4) { val j = (k + 1) % 4; ar += ix[k] * iy[j] - ix[j] * iy[k] }
            ar = abs(ar) / 2
            if (ar >= polyArea * 0.98 && ar < bestA) {
                bestA = ar
                best = orderPoints(Array(4) { Point(ix[it], iy[it]) })
            }
        }
        return best
    }

    /** Puntuación 0..1 de un cuadrilátero ordenado (tl,tr,br,bl) en coords de trabajo; -1 si inválido. */
    private fun scoreQuad(q: FloatArray, b: Buffers, live: Boolean): Double {
        val w = b.w; val h = b.h
        val a = polyArea(q)
        val af = a / (w.toDouble() * h)
        if (af < (if (live) 0.1 else 0.08) || af > 0.995) return -1.0
        // Convexidad: todos los productos cruz con el mismo signo
        var sign = 0
        var maxCos = 0.0
        for (i in 0 until 4) {
            val p0x = q[((i + 3) % 4) * 2]; val p0y = q[((i + 3) % 4) * 2 + 1]
            val p1x = q[i * 2]; val p1y = q[i * 2 + 1]
            val p2x = q[((i + 1) % 4) * 2]; val p2y = q[((i + 1) % 4) * 2 + 1]
            val ax = (p0x - p1x).toDouble(); val ay = (p0y - p1y).toDouble()
            val bx = (p2x - p1x).toDouble(); val by = (p2y - p1y).toDouble()
            val cross = ax * by - ay * bx
            val sg = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (sg == 0) return -1.0
            if (sign == 0) sign = sg else if (sg != sign) return -1.0
            val na = hypot(ax, ay); val nb = hypot(bx, by)
            if (na < 8 || nb < 8) return -1.0
            maxCos = max(maxCos, abs((ax * bx + ay * by) / (na * nb)))
        }
        val cosLimit = 0.55
        if (maxCos > cosLimit) return -1.0
        val sup = b.sideSupport
        val border = edgeSupport(q, b, sup)
        // Documento que se sale del encuadre: los tramos pegados al borde de la imagen no cuentan (ni a favor
        // ni en contra), pero al menos la mitad del perímetro y dos lados deben apoyarse en bordes reales.
        if (border > 0.5) return -1.0
        var realSides = 0; var minSide = 1.0
        for (i in 0 until 4) if (!sup[i].isNaN()) { realSides++; minSide = min(minSide, sup[i]) }
        if (realSides < 2) return -1.0
        // Dos lados OPUESTOS sobre el borde = una franja de lado a lado de la foto (teclado, mesa, ropa), no
        // una hoja; una esquina fuera del encuadre (dos lados contiguos) sí es válida.
        if ((sup[0].isNaN() && sup[2].isNaN()) || (sup[1].isNaN() && sup[3].isNaN())) return -1.0
        val edge = sup[4]
        if (edge < 0.35 || minSide < 0.2) return -1.0
        return (0.4 * min(1.0, af / 0.6) + 0.2 * (1.0 - maxCos / cosLimit) + 0.3 * edge + 0.1 * minSide) *
            (1.0 - 0.2 * border)
    }

    /**
     * Apoyo del perímetro del quad sobre bordes detectados. Rellena [out][0..3] con el apoyo de cada lado
     * (NaN si el lado va casi entero por el borde de la imagen) y [out][4] con el apoyo global, ambos sin
     * contar las muestras pegadas al borde de la imagen. Devuelve la fracción del perímetro sobre el borde.
     */
    private fun edgeSupport(q: FloatArray, b: Buffers, out: DoubleArray): Double {
        val w = b.w; val h = b.h; val bytes = b.emaskBytes
        val m = BORDER_PX
        var hit = 0; var total = 0; var onBorder = 0; var all = 0
        for (i in 0 until 4) {
            val x0 = q[i * 2]; val y0 = q[i * 2 + 1]
            val x1 = q[((i + 1) % 4) * 2]; val y1 = q[((i + 1) % 4) * 2 + 1]
            val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
            val steps = max(2, (len / 1.5).toInt())
            var sh = 0; var st = 0
            val nx = -(y1 - y0) / len.toFloat(); val ny = (x1 - x0) / len.toFloat()
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val fx = x0 + (x1 - x0) * t; val fy = y0 + (y1 - y0) * t
                val x = fx.roundToInt()
                val y = fy.roundToInt()
                all++
                if (x < m || y < m || x >= w - m || y >= h - m) { onBorder++; continue }
                st++
                // Borde detectado Y regiones distintas a ambos lados (la veta de la madera, el texto o la
                // cuadrícula dan bordes, pero el mismo color a ambos lados)
                var ok = false
                for (o in SUPPORT_OFFSETS) {
                    val sx = (fx + nx * o).roundToInt(); val sy = (fy + ny * o).roundToInt()
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h || bytes[sy * w + sx].toInt() == 0) continue
                    if (contrastAt(b, fx + nx * o, fy + ny * o, nx, ny, CONTRAST_OFFSET) >= CONTRAST_MIN) { ok = true; break }
                }
                if (ok) sh++
            }
            hit += sh; total += st
            out[i] = if (st < 0.3 * (steps + 1)) Double.NaN else sh.toDouble() / st
        }
        out[4] = if (total == 0) 0.0 else hit.toDouble() / total
        return if (all == 0) 1.0 else onBorder.toDouble() / all
    }

    /**
     * Diferencia de color entre dos puntos a ±[d] px de (x, y) en la dirección normal (nx, ny): |ΔL| más
     * 2·(|ΔCb| + |ΔCr|) sobre las imágenes suavizadas. Alta en el contorno real del documento.
     */
    private fun contrastAt(b: Buffers, x: Float, y: Float, nx: Float, ny: Float, d: Float): Int {
        val w = b.w; val h = b.h
        val xa = (x + nx * d).roundToInt().coerceIn(0, w - 1); val ya = (y + ny * d).roundToInt().coerceIn(0, h - 1)
        val xb = (x - nx * d).roundToInt().coerceIn(0, w - 1); val yb = (y - ny * d).roundToInt().coerceIn(0, h - 1)
        val ia = ya * w + xa; val ib = yb * w + xb
        var c = abs((b.lBytes[ia].toInt() and 0xFF) - (b.lBytes[ib].toInt() and 0xFF))
        for (k in 0 until b.nChroma) {
            val cb = b.cBytes[k]
            c += 2 * abs((cb[ia].toInt() and 0xFF) - (cb[ib].toInt() and 0xFF))
        }
        return c
    }

    /**
     * Ajuste fino de los lados a resolución de trabajo: en cada lado se busca, a lo largo de la normal
     * (±[SNAP_RADIUS] px), el punto de MÁXIMO CONTRASTE entre ambos lados; se ajusta una recta robusta (Huber)
     * y las esquinas pasan a ser las intersecciones de rectas consecutivas (esquinas redondeadas o tapadas
     * quedan donde se cruzan los bordes). Los lados pegados al borde de la imagen se quedan en ese borde.
     * Null si no se pudo ajustar (quien llama se queda con la mejor puntuación de ambos).
     */
    private fun snapQuad(q: FloatArray, b: Buffers): FloatArray? {
        // Dos pasadas: la segunda parte de lados ya casi alineados y corrige el resto (radio limitado)
        val r = SNAP_RADIUS * max(b.w, b.h) / 400f
        val first = snapOnce(q, b, 2 * r) ?: return null
        return snapOnce(first, b, r) ?: first
    }

    private fun snapOnce(q: FloatArray, b: Buffers, radius: Float): FloatArray? {
        val w = b.w; val h = b.h
        // Por lado, recta de su PRIMERA mitad y de su SEGUNDA mitad (px, py, dx, dy): con páginas curvadas
        // (libro abierto) cada esquina sale de los tramos que llegan a ella, no de un promedio de todo el lado.
        val startL = Array(4) { DoubleArray(4) }
        val endL = Array(4) { DoubleArray(4) }
        val m = BORDER_PX.toFloat()
        val pts = ArrayList<Point>(48); val ts = ArrayList<Float>(48)
        val sub = ArrayList<Point>(48)
        run {
            for (i in 0 until 4) {
                val x0 = q[i * 2]; val y0 = q[i * 2 + 1]
                val x1 = q[((i + 1) % 4) * 2]; val y1 = q[((i + 1) % 4) * 2 + 1]
                val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                if (len < 10f) return null
                val dx = (x1 - x0) / len; val dy = (y1 - y0) / len
                val nx = -dy; val ny = dx
                val orig = doubleArrayOf(x0.toDouble(), y0.toDouble(), dx.toDouble(), dy.toDouble())
                // Lado sobre el borde de la imagen: se fija a ese borde
                fun near(v: Float, lim: Float) = abs(v - lim) <= m
                val border = when {
                    near(x0, 0f) && near(x1, 0f) -> doubleArrayOf(0.0, 0.0, 0.0, 1.0)
                    near(x0, w - 1f) && near(x1, w - 1f) -> doubleArrayOf(w - 1.0, 0.0, 0.0, 1.0)
                    near(y0, 0f) && near(y1, 0f) -> doubleArrayOf(0.0, 0.0, 1.0, 0.0)
                    near(y0, h - 1f) && near(y1, h - 1f) -> doubleArrayOf(0.0, h - 1.0, 1.0, 0.0)
                    else -> null
                }
                if (border != null) { startL[i] = border; endL[i] = border; continue }
                pts.clear(); ts.clear()
                val samples = 40
                for (s in 2 until samples - 1) {
                    val t = s.toFloat() / samples
                    val bx = x0 + (x1 - x0) * t; val by = y0 + (y1 - y0) * t
                    var bestC = 0; var bestO = 0f
                    var o = -radius
                    while (o <= radius) {
                        val sx = bx + nx * o; val sy = by + ny * o
                        if (sx >= m && sy >= m && sx < w - m && sy < h - m) {
                            val cc = contrastAt(b, sx, sy, nx, ny, CONTRAST_OFFSET)
                            if (cc > bestC) { bestC = cc; bestO = o }
                        }
                        o += 1f
                    }
                    if (bestC >= CONTRAST_MIN) { pts.add(Point((bx + nx * bestO).toDouble(), (by + ny * bestO).toDouble())); ts.add(t) }
                }
                fun fit(lo: Float, hi: Float, minPts: Int): DoubleArray? {
                    sub.clear()
                    for (k in pts.indices) if (ts[k] in lo..hi) sub.add(pts[k])
                    if (sub.size < minPts) return null
                    val l = fitLineRobust(sub) ?: return null
                    // Dirección casi igual a la original (no girar el lado hacia otro borde)
                    if (abs(l[2] * dx + l[3] * dy) < 0.97) return null
                    return l
                }
                val full = fit(0f, 1f, 8) ?: orig
                startL[i] = fit(0f, 0.55f, 6) ?: full
                endL[i] = fit(0.45f, 1f, 6) ?: full
            }
        }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            // La esquina i está entre el lado i-1 (que termina en ella) y el lado i
            val l1 = endL[(i + 3) % 4]; val l2 = startL[i]
            val den = l1[2] * l2[3] - l1[3] * l2[2]
            if (abs(den) < 1e-6) return null
            val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / den
            val ix = l1[0] + l1[2] * t; val iy = l1[1] + l1[3] * t
            if (hypot(ix - q[i * 2], iy - q[i * 2 + 1]) > 3 * radius) return null
            out[i * 2] = ix.toFloat(); out[i * 2 + 1] = iy.toFloat()
        }
        clampPts(out, w - 1, h - 1)
        return out
    }

    /**
     * Recta por mínimos cuadrados totales (PCA) con una segunda pasada sin los puntos atípicos
     * (residuo > max(1.5 px, 2·mediana)). Devuelve (px, py, dx, dy) o null si hay pocos puntos.
     */
    private fun fitLineRobust(p: List<Point>): DoubleArray? {
        var keep = BooleanArray(p.size) { true }
        var res: DoubleArray? = null
        for (pass in 0 until 2) {
            var n = 0; var mx = 0.0; var my = 0.0
            for (i in p.indices) if (keep[i]) { mx += p[i].x; my += p[i].y; n++ }
            if (n < 4) return res
            mx /= n; my /= n
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (i in p.indices) if (keep[i]) {
                val dx = p[i].x - mx; val dy = p[i].y - my
                sxx += dx * dx; syy += dy * dy; sxy += dx * dy
            }
            val theta = 0.5 * atan2(2 * sxy, sxx - syy)
            val ux = kotlin.math.cos(theta); val uy = kotlin.math.sin(theta)
            res = doubleArrayOf(mx, my, ux, uy)
            if (pass == 1) break
            val r = DoubleArray(p.size) { abs(-(p[it].x - mx) * uy + (p[it].y - my) * ux) }
            val med = r.sortedArray()[r.size / 2]
            val lim = max(1.5, 2 * med)
            keep = BooleanArray(p.size) { r[it] <= lim }
        }
        return res
    }

    /**
     * Canny con umbrales derivados de la distribución del MÓDULO DEL GRADIENTE (norma L1 de Sobel, la misma
     * que usa Canny): hi = percentil 90, lo = 0.4·hi. Independiente del brillo medio de la escena.
     */
    private fun autoCanny(blurred: Mat, dst: Mat, b: Buffers) {
        Imgproc.Sobel(blurred, b.gx, CvType.CV_16S, 1, 0, 3)
        Imgproc.Sobel(blurred, b.gy, CvType.CV_16S, 0, 1, 3)
        // |gx|/8 + |gy|/8 en 8 bits (satura en 255 -> 2040 real, suficiente para el percentil)
        Core.convertScaleAbs(b.gx, b.mag, 0.125)
        Core.convertScaleAbs(b.gy, b.mag2, 0.125)
        Core.add(b.mag, b.mag2, b.mag)
        val p = Cv.percentile(Cv.histogram(b.mag), 0.90) * 8.0
        val hi = p.coerceIn(30.0, 400.0)
        val lo = max(10.0, 0.4 * hi)
        Imgproc.Canny(blurred, dst, lo, hi)
    }

    /** CLAHE suave sobre la luminancia si la escena es oscura (in-place). */
    private fun enhanceLowLight(l: Mat, clahe: org.opencv.imgproc.CLAHE?) {
        if (clahe == null && Core.mean(l).`val`[0] >= LOW_LIGHT_MEAN) return
        (clahe ?: Imgproc.createCLAHE(2.0, Size(8.0, 8.0))).apply(l, l)
    }

    /**
     * Refinamiento sub-píxel de las esquinas a resolución completa, con salvaguardas:
     *  - no se refinan esquinas pegadas al borde de la imagen (documento parcialmente fuera, ya recortado);
     *  - desplazamiento máximo = ventana;
     *  - el soporte de borde (gradiente medio) de los dos lados adyacentes, medido a resolución completa
     *    saltando la zona de la esquina (tarjetas con esquinas redondeadas), no puede empeorar; si empeora se
     *    conserva el punto original (evita saltar a texto, sombras o marcos).
     */
    private fun refineCorners(w: Int, h: Int, grayCrop: (Int, Int, Int, Int) -> Mat?, pts: FloatArray) {
        val win = (max(w, h) / 250).coerceIn(5, 20)
        val half = win * 3
        val orig = pts.copyOf()
        for (i in 0 until 4) {
            val px = pts[i * 2]; val py = pts[i * 2 + 1]
            if (px < 2f || py < 2f || px > w - 3f || py > h - 3f) continue
            val x0 = (px.roundToInt() - half).coerceIn(0, max(0, w - 1))
            val y0 = (py.roundToInt() - half).coerceIn(0, max(0, h - 1))
            val x1 = (px.roundToInt() + half).coerceIn(0, w)
            val y1 = (py.roundToInt() + half).coerceIn(0, h)
            val rw = x1 - x0; val rh = y1 - y0
            if (rw < 2 * win + 5 || rh < 2 * win + 5) continue
            val bag = MatBag()
            try {
                val g = bag.add(grayCrop(x0, y0, rw, rh) ?: continue)
                val p = bag.add(MatOfPoint2f(Point((px - x0).toDouble(), (py - y0).toDouble())))
                Imgproc.cornerSubPix(
                    g, p, Size(win.toDouble(), win.toDouble()), Size(-1.0, -1.0),
                    TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 0.05),
                )
                val r = p.toArray()[0]
                val nx = r.x + x0; val ny = r.y + y0
                if (nx.isFinite() && ny.isFinite() && hypot(nx - px, ny - py) <= win.toDouble()) {
                    // Gradiente del recorte (norma L1) para medir el soporte de los lados adyacentes
                    val gxm = bag.mat(); val gym = bag.mat(); val ax = bag.mat(); val ay = bag.mat(); val mag = bag.mat()
                    Imgproc.Sobel(g, gxm, CvType.CV_16S, 1, 0, 3)
                    Imgproc.Sobel(g, gym, CvType.CV_16S, 0, 1, 3)
                    Core.convertScaleAbs(gxm, ax, 0.25); Core.convertScaleAbs(gym, ay, 0.25)
                    Core.add(ax, ay, mag)
                    val mb = ByteArray(rw * rh); mag.get(0, 0, mb)
                    val prev = (i + 3) % 4; val next = (i + 1) % 4
                    fun support(cx: Double, cy: Double): Double {
                        var sum = 0.0; var n = 0
                        for (nb in intArrayOf(prev, next)) {
                            val dx = orig[nb * 2] - orig[i * 2]; val dy = orig[nb * 2 + 1] - orig[i * 2 + 1]
                            val len = hypot(dx.toDouble(), dy.toDouble())
                            if (len < 1) continue
                            val ux = dx / len; val uy = dy / len
                            var t = win.toDouble()
                            while (t < half) {
                                val sx = (cx + ux * t - x0).roundToInt(); val sy = (cy + uy * t - y0).roundToInt()
                                if (sx in 0 until rw && sy in 0 until rh) { sum += mb[sy * rw + sx].toInt() and 0xFF; n++ }
                                t += 1.0
                            }
                        }
                        return if (n == 0) 0.0 else sum / n
                    }
                    val before = support(px.toDouble(), py.toDouble())
                    val after = support(nx, ny)
                    if (after >= before * 0.95) {
                        pts[i * 2] = nx.toFloat(); pts[i * 2 + 1] = ny.toFloat()
                    }
                }
            } catch (_: Throwable) {
                // conservar la esquina original
            } finally {
                bag.close()
            }
        }
    }

    private fun confidenceOf(score: Double): Float = ((score - 0.45) / 0.4).coerceIn(0.0, 1.0).toFloat()

    companion object {
        /** Luminancia media por debajo de la cual se aplica CLAHE antes de buscar bordes. */
        private const val LOW_LIGHT_MEAN = 80.0

        /** Diferencia mínima de tono (niveles de Cb/Cr) entre las dos clases de Otsu para usar la segmentación. */
        private const val MIN_CHROMA_SPLIT = 6.0

        /** Margen (px de trabajo) que se considera "borde de la imagen" al medir el apoyo de los lados. */
        private const val BORDER_PX = 5

        /** Contraste mínimo (|ΔL| + 2·|Δcroma|) entre ambos lados de un borde para darlo por bueno. */
        private const val CONTRAST_MIN = 14

        /** Distancia (px de trabajo) a cada lado del borde a la que se mide el contraste. */
        private const val CONTRAST_OFFSET = 3f

        /** Radio de búsqueda del ajuste fino de los lados (px para un lado largo de 400; 1ª pasada el doble). */
        private const val SNAP_RADIUS = 7f

        /** Tolerancia (px de trabajo, a lo largo de la normal) al buscar el borde bajo cada muestra de un lado. */
        private val SUPPORT_OFFSETS = floatArrayOf(0f, -2f, 2f)

        /** Ordena 4 puntos como tl, tr, br, bl (sentido horario en coordenadas de imagen). */
        internal fun orderPoints(p: Array<Point>): FloatArray {
            val cx = p.sumOf { it.x } / p.size; val cy = p.sumOf { it.y } / p.size
            val sorted = p.sortedBy { atan2(it.y - cy, it.x - cx) } // ascendente = horario (y hacia abajo)
            var start = 0
            var bestSum = Double.MAX_VALUE
            for (i in sorted.indices) { val s = sorted[i].x + sorted[i].y; if (s < bestSum) { bestSum = s; start = i } }
            val o = Array(4) { sorted[(start + it) % 4] }
            val res = if (o[1].x < o[3].x) arrayOf(o[0], o[3], o[2], o[1]) else o
            return FloatArray(8) { i -> (if (i % 2 == 0) res[i / 2].x else res[i / 2].y).toFloat() }
        }

        /** Cuadrilátero de área máxima con vértices del polígono convexo (<= 8 puntos). */
        internal fun best4(p: Array<Point>): FloatArray {
            val n = p.size
            var bestA = -1.0
            var bi = intArrayOf(0, 1, 2, 3)
            for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
                val ar = abs(
                    (p[a].x * p[b].y - p[b].x * p[a].y) + (p[b].x * p[c].y - p[c].x * p[b].y) +
                        (p[c].x * p[d].y - p[d].x * p[c].y) + (p[d].x * p[a].y - p[a].x * p[d].y),
                ) / 2
                if (ar > bestA) { bestA = ar; bi = intArrayOf(a, b, c, d) }
            }
            return orderPoints(Array(4) { p[bi[it]] })
        }

        internal fun polyArea(q: FloatArray): Double {
            var s = 0.0
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                s += q[i * 2].toDouble() * q[j * 2 + 1] - q[j * 2].toDouble() * q[i * 2 + 1]
            }
            return abs(s) / 2
        }

        internal fun clampPts(p: FloatArray, w: Int, h: Int) {
            for (i in 0 until 4) {
                p[i * 2] = p[i * 2].coerceIn(0f, w.toFloat())
                p[i * 2 + 1] = p[i * 2 + 1].coerceIn(0f, h.toFloat())
            }
        }

        /**
         * Escala un quad de coordenadas de trabajo ([ww]x[wh]) a la imagen ([fw]x[fh]). Las esquinas pegadas al
         * borde de la imagen de trabajo (documento que se sale del encuadre) quedan EXACTAMENTE en el borde.
         */
        internal fun scaleToFrame(p: FloatArray, ww: Int, wh: Int, fw: Int, fh: Int): FloatArray {
            val fx = fw.toDouble() / ww; val fy = fh.toDouble() / wh
            val out = FloatArray(8)
            for (i in 0 until 4) {
                val x = p[i * 2]; val y = p[i * 2 + 1]
                out[i * 2] = when { x <= 0.75f -> 0f; x >= ww - 1.75f -> fw.toFloat(); else -> (x * fx).toFloat() }
                out[i * 2 + 1] = when { y <= 0.75f -> 0f; y >= wh - 1.75f -> fh.toFloat(); else -> (y * fy).toFloat() }
            }
            clampPts(out, fw, fh)
            return out
        }

        internal fun toQuad(p: FloatArray) = Quad(Pt(p[0], p[1]), Pt(p[2], p[3]), Pt(p[4], p[5]), Pt(p[6], p[7]))
    }
}
