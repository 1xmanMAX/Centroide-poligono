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
 *  7. (Foto completa) Rectas de Hough sobre bordes sin texto (mediana 5x5) agrupadas en lados y combinadas en
 *     cuadriláteros ([DetLines]): tarjetas sujetas con los dedos o bordes interrumpidos que no cierran contorno.
 * Puntuación: área, ángulos, y apoyo de CADA lado sobre bordes con contraste de color a ambos lados (la veta
 * de la madera, la cuadrícula o el texto no cuentan). Los tramos pegados al borde de la imagen son neutros:
 * un documento que se sale del encuadre queda con ese lado sobre el borde (nunca dos lados opuestos).
 * La puntuación ordena (premia el área); la CONFIANZA devuelta sale de la calidad del borde y no castiga a los
 * documentos pequeños en el encuadre. Después de ordenar se descartan los marcos o tablas impresos de una
 * página escaneada (papel igual a ambos lados y contenido fuera) y, si el mejor contorno tiene un borde pobre
 * (hoja + trozo de mesa o de póster), se prefiere un candidato interior con borde nítido.
 */
class DocumentDetector(private val tier: DeviceTier) {

    private val detectSide = 500
    private val liveSide = if (tier.isLowRam) 320 else 400

    /** Buffers reutilizables de una pasada de detección. */
    private class Buffers {
        val blur = Mat(); val tmp = Mat(); val edges = Mat(); val edges2 = Mat()
        val otsu = Mat(); val emask = Mat(); val emaskClosed = Mat(); val hough = Mat(); val lines = Mat(); val lines2 = Mat(); val hierarchy = Mat()
        val gx = Mat(); val gy = Mat(); val mag = Mat(); val mag2 = Mat(); val colorTmp = Mat()
        val hullIdx = MatOfInt(); val approx = MatOfPoint2f(); val hull2f = MatOfPoint2f()
        val chromaMasks = arrayOf(Mat(), Mat()); val chromaMasksInv = arrayOf(Mat(), Mat())
        val sideSupport = DoubleArray(5)
        /** Rasgos del último [scoreQuad] válido: área, maxCos, apoyo global, apoyo mínimo, fracción en el borde. */
        val feat = DoubleArray(5)
        val chromaBlur = arrayOf(Mat(), Mat())
        var emaskBytes = ByteArray(0)
        var lBytes = ByteArray(0)
        var cBytes = arrayOf(ByteArray(0), ByteArray(0))
        var nChroma = 0
        /** Mediana de la luminancia suavizada de toda la imagen de trabajo. */
        var lMedian = 128
        /** Máscara de tinta (oscuro respecto del papel de alrededor) y nivel del papel; se calculan a demanda. */
        val ink = Mat(); val quadMask = Mat(); var inkReady = false; var paperLevel = 0
        /** Canales de entrada de la pasada actual (no son de los búferes: no se liberan aquí) y bordes para rectas. */
        var lRaw: Mat = Mat(); var chromaRaw: List<Mat> = emptyList(); val lineEdges = Mat()
        /** Mejor candidato descartado por ser un marco impreso (para buscar la hoja a partir de él). */
        var frame: Candidate? = null
        /** Todas las hipótesis válidas evaluadas (foto completa) para la reordenación por material ([DetFeatures]). */
        val pool = ArrayList<Candidate>(64)
        /** Fracción de píxeles sin color (canales iguales) de la foto; -1 = desconocida (vivo). */
        var grayFrac = -1.0
        /** Escaneo o página digital: imagen sin color con mucho papel blanco saturado (ver [scanLike]). */
        var scanLike = false
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
            emaskClosed.release(); hough.release(); lines.release(); lines2.release(); hierarchy.release()
            gx.release(); gy.release(); mag.release(); mag2.release(); colorTmp.release()
            hullIdx.release(); approx.release(); hull2f.release()
            for (m in chromaMasks) m.release()
            for (m in chromaBlur) m.release()
            for (m in chromaMasksInv) m.release()
            ink.release(); quadMask.release(); lineEdges.release()
        }
    }

    /** Depuración (banco de pruebas): recibe cada cuadrilátero evaluado con su puntuación. */
    internal var debugCand: ((FloatArray, Double, String) -> Unit)? = null
    internal var debugFeat: ((FloatArray, DoubleArray, Double, String) -> Unit)? = null

    private val kernel3: Mat by lazy { Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)) }

    // ---- Estado de la detección en vivo (protegido por @Synchronized) ----
    private var liveBuf: Buffers? = null
    private var yBytes = ByteArray(0)
    private var yMat: Mat? = null
    private var yRoi: Mat? = null
    private var liveSmall: Mat? = null
    private var liveRot: Mat? = null
    private var liveClahe: org.opencv.imgproc.CLAHE? = null

    /**
     * Hipótesis de documento: [score] ordena los candidatos (incluye el área: entre dos bordes igual de buenos gana
     * la hoja exterior); [conf] es la confianza 0..1 que se devuelve (calidad del borde, sin castigar el tamaño);
     * [quality] la calidad del borde sola ([qualityConf]).
     */
    private class Candidate(
        val pts: FloatArray, val score: Double, val conf: Double = scoreToConf(score), val quality: Double = 0.0,
        /** Rasgos de [scoreQuad] (copia), peso de procedencia y si está ajustado a los bordes (para [DetFeatures.rankScore]). */
        val feat: DoubleArray? = null, val prov: Double = 1.0, val snapped: Boolean = false,
    ) {
        var rank = Double.NaN
    }

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
            val det0 = detectColor(rgbSmall, buf, bag)
            // El mejor contorno era un marco o tabla impresos: la hoja, si la hay, se busca a partir de él (foto de una
            // página sobre una mesa blanca); si no aparece, es un escaneo y vale la imagen completa
            val frame = buf.frame
            if (frame != null && sheetRefine && (det0 == null || det0.score < frame.score)) {
                // (la hoja encontrada tampoco puede tener papel con contenido alrededor: sería otro marco)
                val r = runCatching { refineSheet(rgbSmall, frame) }.getOrNull()?.takeIf { !printedFrame(it.pts, buf) && !(buf.scanLike && scanInside(it.pts, buf)) }
                if (r != null || det0 == null) {
                    val c = r ?: return null
                    val pts = scaleToFrame(c.pts, rgbSmall.cols(), rgbSmall.rows(), fullW, fullH)
                    if (grayCrop != null) refineCorners(fullW, fullH, grayCrop, pts)
                    clampPts(pts, fullW, fullH)
                    return DetectionResult(toQuad(pts), c.conf.toFloat(), fullW, fullH)
                }
            }
            debugLog?.invoke("core: " + (det0?.let { c -> c.pts.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) } + " s=%.3f".format(java.util.Locale.ROOT, c.score) } ?: "null"))
            // Sin candidato (blanco sobre blanco con poca luz: ningún borde con contraste suficiente): se intenta igualmente
            // la hoja a partir del contenido, exigiendo más rectas
            val cand0 = det0 ?: (if (sheetRefine) runCatching {
                val w0 = rgbSmall.cols() - 1f; val h0 = rgbSmall.rows() - 1f
                refineSheet(rgbSmall, Candidate(floatArrayOf(0f, 0f, w0, 0f, w0, h0, 0f, h0), 0.0), force = true)
            }.getOrNull()?.takeIf { !(buf.scanLike && scanInside(it.pts, buf)) } else null) ?: return null
            // (Un contorno con borde de contraste ya no se "corrige" buscando otra hoja a partir del contenido: en el banco
            // de pruebas eso desplazaba hacia dentro hojas correctas sobre mesas claras o tarjetas con texto alrededor.
            // Los marcos impresos y la falta de contorno sí pasan por [refineSheet], arriba.)
            val cand = cand0
            val pts = scaleToFrame(cand.pts, rgbSmall.cols(), rgbSmall.rows(), fullW, fullH)
            if (grayCrop != null) refineCorners(fullW, fullH, grayCrop, pts)
            clampPts(pts, fullW, fullH)
            return DetectionResult(toQuad(pts), cand.conf.toFloat(), fullW, fullH)
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
        buf.grayFrac = grayFraction(rgb, bag)
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
            val res = DetectionResult(toQuad(pts), cand.conf.toFloat(), rotW, rotH)
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
        b.lRaw = l; b.chromaRaw = chroma

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
        b.lMedian = Cv.percentile(Cv.histogram(b.blur), 0.5)
        b.scanLike = false
        if (!live && b.grayFrac >= SCAN_GRAY) {
            Imgproc.threshold(b.blur, b.tmp, SCAN_WHITE_L - 1.0, 255.0, Imgproc.THRESH_BINARY)
            b.scanLike = Core.countNonZero(b.tmp) >= SCAN_WHITE_FRAC * area
        }
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

        val cands = ArrayList<Candidate>()
        b.resetSeen()
        b.inkReady = false
        b.frame = null
        b.pool.clear()
        val minArea = (if (live) 0.05 else 0.03) * area
        for ((src, weight) in sources) {
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(src, contours, b.hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val withArea = contours.map { it to Imgproc.contourArea(it) }
                .filter { it.second >= minArea }
                .sortedByDescending { it.second }
                .take(if (live) 5 else 12)
            for ((c, _) in withArea) evaluateContour(c, b, weight, live)?.let { cands.add(it) }
            for (c in contours) c.release()
        }
        // Hipótesis por rectas (bordes interrumpidos): sólo desplazan a un contorno si su borde es claramente mejor
        if (!live && lineQuads) {
            val lc = ArrayList<Candidate>()
            lineCandidates(b, live, lc)
            if (lc.isNotEmpty()) {
                val bestContourQ = cands.maxOfOrNull { it.quality } ?: 0.0
                for (c in lc) if (c.quality >= bestContourQ + LINE_BETTER) cands.add(c)
            }
        }
        cands.sortByDescending { it.score }
        var res: Candidate? = null
        var frameChecks = 0
        for (c in cands) {
            // Puntuación baja (documento PEQUEÑO en el encuadre) sólo vale con un borde nítido y completo
            if (c.score < 0.55 && c.conf < ACCEPT_CONF) continue
            // Escaneo: un lado con papel blanco fuera es un marco, una tabla o un bloque de texto de la página
            if (b.scanLike && scanInside(c.pts, b)) continue
            // Marco o tabla IMPRESOS en una página escaneada (papel igual a ambos lados y contenido fuera): no es el
            // borde de la hoja. En vivo no se comprueba (coste; la cámara ve la mesa alrededor).
            if (!live) {
                // Agotadas las comprobaciones tras descartar marcos: no se acepta nada sin comprobar (escaneo)
                if (frameChecks >= MAX_FRAME_CHECKS) { if (b.frame != null) break }
                else frameChecks++
                if (printedFrame(c.pts, b)) { if (b.frame == null) b.frame = c; debugLog?.invoke("core: marco impreso descartado s=%.3f".format(java.util.Locale.ROOT, c.score)); continue }
            }
            res = c; break
        }
        if (res == null) return null
        res = nestedBetter(res, cands, b, live) ?: res
        if (!live && rerank) res = rerankByMaterial(res, b) ?: res
        return res
    }

    /**
     * El mejor candidato [a] (por puntuación, que premia el área) tiene un borde POBRE (lados que cruzan la mesa, el
     * césped o la mano) y dentro hay otro candidato con un borde mucho mejor: [a] es la hoja más un trozo del
     * entorno (objetos, sombras, un póster detrás de la tarjeta) y se prefiere el interior. Salvo que compartan un
     * lado (una página dentro del libro o cuaderno abierto completo): entonces [a] puede ser el documento entero.
     * Null = sin cambios.
     */
    private fun nestedBetter(a: Candidate, cands: List<Candidate>, b: Buffers, live: Boolean): Candidate? {
        if (a.quality >= NEST_WEAK) return null
        val area = b.w.toDouble() * b.h
        val aArea = polyArea(a.pts)
        val poly = MatOfPoint2f(*Array(4) { Point(a.pts[it * 2].toDouble(), a.pts[it * 2 + 1].toDouble()) })
        try {
            var best: Candidate? = null
            for (c in cands) {
                if (c === a || c.quality < max(NEST_STRONG, a.quality + 0.25) || c.conf < NEST_STRONG) continue
                if (best != null && c.score <= best.score) continue
                val ca = polyArea(c.pts)
                if (ca < 0.04 * area || ca > 0.8 * aArea) continue
                var inside = true
                for (i in 0 until 4) if (Imgproc.pointPolygonTest(poly, Point(c.pts[i * 2].toDouble(), c.pts[i * 2 + 1].toDouble()), true) < -2.0) { inside = false; break }
                if (!inside || sharesSide(a.pts, c.pts)) continue
                if (!live && printedFrame(c.pts, b)) continue
                best = c
            }
            if (best != null) debugLog?.invoke("core: interior con mejor borde q=%.2f (exterior q=%.2f)".format(java.util.Locale.ROOT, best.quality, a.quality))
            return best
        } finally { poly.release() }
    }

    /**
     * Reordenación por MATERIAL (foto completa): entre todas las hipótesis válidas evaluadas, la de mayor
     * [DetFeatures.rankScore] sustituye a [a] (la elegida por puntuación de contorno, que premia el área) si la supera
     * claramente ([DetFeatures.SWAP_MARGIN]). Corrige cuadriláteros que mezclan la hoja con un trozo de la mesa, del
     * teclado o de la mano (lados cuya banda interior no es del mismo papel) y los que encierran el documento con parte
     * del fondo. La sustituta debe ser utilizable por sí misma (confianza de la app) y no un marco impreso.
     */
    private fun rerankByMaterial(a: Candidate, b: Buffers): Candidate? {
        val pool = b.pool
        if (pool.size < 2 || b.lBytes.isEmpty()) return null
        val cb = if (b.nChroma > 0) b.cBytes[0] else null
        val cr = if (b.nChroma > 1) b.cBytes[1] else null
        val area = b.w.toDouble() * b.h
        val areas = DoubleArray(pool.size) { polyArea(pool[it].pts) }
        fun rankOf(i: Int): Double {
            val c = pool[i]
            if (!c.rank.isNaN()) return c.rank
            val f = c.feat ?: return Double.NEGATIVE_INFINITY
            val bands = DetFeatures.bands(c.pts, b.lBytes, cb, cr, b.w, b.h, BORDER_PX)
            val g = DetFeatures.interior(c.pts, b.lBytes, b.w, b.h)
            // Mejor candidato nítido contenido (el propio documento dentro de un contorno con fondo, o su contenido)
            var inner = 0.0
            for (j in pool.indices) {
                if (j == i) continue
                val o = pool[j]; val fo = o.feat ?: continue
                if (areas[j] >= 0.8 * areas[i] || areas[j] <= 0.04 * area) continue
                var dmax = 0f
                for (k in 0 until 8) dmax = max(dmax, abs(o.pts[k] - c.pts[k]))
                if (dmax < 5f) continue
                if (DetFeatures.quadInside(o.pts, c.pts)) inner = max(inner, 0.6 * fo[2] + 0.4 * fo[3])
            }
            c.rank = DetFeatures.rankScore(f[0], f[1], f[2], f[4], c.prov, c.snapped, bands, g[0], g[1], b.lMedian.toDouble(), inner)
            return c.rank
        }
        val ai = pool.indexOfFirst { it === a }.takeIf { it >= 0 }
            ?: pool.indices.minByOrNull { i -> (0 until 8).maxOf { abs(pool[i].pts[it] - a.pts[it]) } }?.takeIf { i -> (0 until 8).maxOf { abs(pool[i].pts[it] - a.pts[it]) } < 1f }
            ?: return null
        val ra = rankOf(ai)
        val order = pool.indices.sortedByDescending { rankOf(it) }
        val aArea = polyArea(a.pts)
        for (i in order) {
            val gain = rankOf(i) - ra
            if (gain <= DetFeatures.SWAP_MARGIN) break
            val c = pool[i]
            // Encoger el documento (una parte de la hoja: un pliegue, una sombra, un recuadro impreso) exige más ventaja
            if (areas[i] < 0.8 * aArea && gain <= DetFeatures.SHRINK_MARGIN) continue
            if (c.conf < SWAP_MIN_CONF) continue
            if (b.scanLike && scanInside(c.pts, b)) continue
            if (printedFrame(c.pts, b)) continue
            debugLog?.invoke("core: reordenado por material %.2f -> %.2f".format(java.util.Locale.ROOT, ra, rankOf(i)))
            return c
        }
        return null
    }

    /** Escaneo: ¿algún lado real de [q] tiene fuera papel blanco saturado (está DENTRO de la página)? */
    private fun scanInside(q: FloatArray, b: Buffers): Boolean {
        val cb = if (b.nChroma > 0) b.cBytes[0] else null
        val cr = if (b.nChroma > 1) b.cBytes[1] else null
        return DetFeatures.bands(q, b.lBytes, cb, cr, b.w, b.h, BORDER_PX).outWhite
    }

    /** Fracción de píxeles sin color (diferencia máxima entre canales <= 2): escaneos en grises, PDFs renderizados. */
    private fun grayFraction(rgb: Mat, bag: MatBag): Double {
        val ch = ArrayList<Mat>(3); Core.split(rgb, ch)
        for (m in ch) bag.add(m)
        val d1 = bag.mat(); val d2 = bag.mat(); val d3 = bag.mat()
        Core.absdiff(ch[0], ch[1], d1); Core.absdiff(ch[1], ch[2], d2); Core.absdiff(ch[0], ch[2], d3)
        Core.max(d1, d2, d1); Core.max(d1, d3, d1)
        Imgproc.threshold(d1, d1, 2.0, 255.0, Imgproc.THRESH_BINARY_INV)
        return Core.countNonZero(d1).toDouble() / max(1, rgb.rows() * rgb.cols())
    }

    /**
     * ¿[inner] tiene un lado sobre un lado de [outer]? Es el caso de una página de un libro o cuaderno abierto dentro
     * del pliego completo (comparten el canto exterior), o de la hoja dentro de hoja + franja de mesa: el exterior
     * prolonga el interior en una dirección y puede ser el documento completo.
     */
    private fun sharesSide(outer: FloatArray, inner: FloatArray): Boolean {
        val diag = hypot((outer[4] - outer[0]).toDouble(), (outer[5] - outer[1]).toDouble())
        val tol = NEST_SHARED_SIDE * diag
        for (i in 0 until 4) {
            val ax = inner[i * 2].toDouble(); val ay = inner[i * 2 + 1].toDouble()
            val bx = inner[((i + 1) % 4) * 2].toDouble(); val by = inner[((i + 1) % 4) * 2 + 1].toDouble()
            for (j in 0 until 4) {
                val px = outer[j * 2].toDouble(); val py = outer[j * 2 + 1].toDouble()
                val qx = outer[((j + 1) % 4) * 2].toDouble(); val qy = outer[((j + 1) % 4) * 2 + 1].toDouble()
                val len = hypot(qx - px, qy - py); if (len < 1) continue
                fun dist(x: Double, y: Double) = abs((qx - px) * (py - y) - (px - x) * (qy - py)) / len
                if (dist(ax, ay) <= tol && dist(bx, by) <= tol) return true
            }
        }
        return false
    }

    /**
     * ¿El cuadrilátero [q] es un MARCO O TABLA IMPRESOS dentro de una página (típico de escaneos y PDFs: la hoja
     * llena la imagen) y no el borde de la hoja? Sí cuando la mayoría de sus lados reales separan papel de papel
     * (mismo brillo y tono a ambos lados, ambos claros) y FUERA del cuadrilátero sigue habiendo contenido (tinta)
     * sobre ese papel. Una hoja sobre una mesa blanca lisa separa papel de "papel" pero sin tinta fuera.
     */
    private fun printedFrame(q: FloatArray, b: Buffers): Boolean {
        val w = b.w; val h = b.h
        if (!b.inkReady) {
            val side = max(w, h)
            val closed = Mat()
            try {
                Imgproc.morphologyEx(b.blur, closed, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd(max(5, (side * 0.02).roundToInt()))))
                b.paperLevel = Cv.percentile(Cv.histogram(closed), 0.9)
                Core.subtract(closed, b.blur, b.ink)
                Imgproc.threshold(b.ink, b.ink, max(12.0, 0.12 * b.paperLevel), 255.0, Imgproc.THRESH_BINARY)
            } finally { closed.release() }
            b.inkReady = true
        }
        val paper = b.paperLevel
        if (paper < 90) return false
        val m = BORDER_PX
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        var real = 0; var paperPaper = 0
        val vin = IntArray(24); val vout = IntArray(24); val cin = IntArray(24); val cout = IntArray(24)
        for (i in 0 until 4) {
            val x0 = q[i * 2]; val y0 = q[i * 2 + 1]; val x1 = q[((i + 1) % 4) * 2]; val y1 = q[((i + 1) % 4) * 2 + 1]
            val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat(); if (len < 10f) continue
            var nx = -(y1 - y0) / len; var ny = (x1 - x0) / len
            if (nx * ((x0 + x1) / 2 - cx) + ny * ((y0 + y1) / 2 - cy) < 0) { nx = -nx; ny = -ny }
            var n = 0
            for (k in 1..12) {
                val t = k / 13f
                val px = x0 + (x1 - x0) * t; val py = y0 + (y1 - y0) * t
                for (o in intArrayOf(5, 8)) {
                    val xo = (px + nx * o).roundToInt(); val yo = (py + ny * o).roundToInt()
                    val xi = (px - nx * o).roundToInt(); val yi = (py - ny * o).roundToInt()
                    if (xo < m || yo < m || xo >= w - m || yo >= h - m || xi < 0 || yi < 0 || xi >= w || yi >= h) continue
                    val io = yo * w + xo; val ii = yi * w + xi
                    vout[n] = b.lBytes[io].toInt() and 0xFF; vin[n] = b.lBytes[ii].toInt() and 0xFF
                    var co = 0; var ci = 0
                    for (k2 in 0 until b.nChroma) { co += b.cBytes[k2][io].toInt() and 0xFF; ci += b.cBytes[k2][ii].toInt() and 0xFF }
                    cout[n] = co; cin[n] = ci; n++
                }
            }
            if (n < 8) continue
            real++
            val lo = vout.copyOf(n).sorted()[n / 2]; val li = vin.copyOf(n).sorted()[n / 2]
            val dc = abs(cout.copyOf(n).sorted()[n / 2] - cin.copyOf(n).sorted()[n / 2])
            // Fuera: el papel claro de la página, del mismo tono que dentro (dentro puede haber sombreado de tabla)
            if (lo >= FRAME_OUT_PAPER * paper && li >= 0.6 * paper && lo - li <= 0.4 * paper && dc <= FRAME_PAPER_CHROMA) paperPaper++
        }
        if (real < 2 || paperPaper < max(2, real - 1)) return false
        // Tinta dentro y fuera del cuadrilátero (densidad por píxel)
        b.quadMask.create(h, w, CvType.CV_8UC1); b.quadMask.setTo(Scalar(0.0))
        Imgproc.fillPoly(b.quadMask, listOf(MatOfPoint(*Array(4) { Point(q[it * 2].toDouble(), q[it * 2 + 1].toDouble()) })), Scalar(255.0))
        val qa = Core.countNonZero(b.quadMask).toDouble()
        val inkAll = Core.countNonZero(b.ink).toDouble()
        val tmp = Mat()
        val inkIn = try { Core.bitwise_and(b.ink, b.quadMask, tmp); Core.countNonZero(tmp).toDouble() } finally { tmp.release() }
        val outA = w.toDouble() * h - qa
        if (outA < 0.02 * w * h || qa < 1) return false
        val dOut = (inkAll - inkIn) / outA; val dIn = inkIn / qa
        debugLog?.invoke("frame: paperPaper=$paperPaper/$real dIn=%.4f dOut=%.4f".format(java.util.Locale.ROOT, dIn, dOut))
        return dOut >= FRAME_INK_OUT_MIN && dOut >= 0.04 * dIn
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
            val c = considerQuad(q, b, wq, live) ?: return
            if (best == null || c.score > best!!.score) best = c
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
     * Puntúa la hipótesis [q] tal cual y ajustada a los bordes reales ([snapQuad]); devuelve la mejor de ambas o null
     * si ninguna es válida o ya se evaluó (las distintas fuentes suelen dar el mismo cuadrilátero).
     */
    private fun considerQuad(q: FloatArray, b: Buffers, wq: Double, live: Boolean): Candidate? {
        clampPts(q, b.w - 1, b.h - 1)
        if (b.seenDuplicate(q)) return null
        var best: Candidate? = null
        val s = scoreQuad(q, b, live)
        debugCand?.invoke(q, s * wq, "raw")
        if (s > 0) {
            debugFeat?.invoke(q, b.feat, wq, "raw")
            best = Candidate(q, s * wq, candidateConf(q, b, s * wq, wq), qualityConf(b.feat, wq), b.feat.copyOf(), wq, false)
            if (!live) b.pool.add(best)
        }
        // Misma hipótesis ajustada a los bordes reales (vértices del casco desplazados, esquinas redondeadas)
        val sq = snapQuad(q, b) ?: return best
        val s2 = scoreQuad(sq, b, live)
        // Ya apoyado en bordes reales: la procedencia (aproximación, rectángulo mínimo) importa menos
        val ws = max(wq, 0.95 * (1.0 + wq) / 2) * 1.02
        debugCand?.invoke(sq, s2 * ws, "snap")
        if (s2 > 0) {
            debugFeat?.invoke(sq, b.feat, ws, "snap")
            val c2 = Candidate(sq, s2 * ws, candidateConf(sq, b, s2 * ws, ws), qualityConf(b.feat, ws), b.feat.copyOf(), ws, true)
            if (!live) b.pool.add(c2)
            if (best == null || s2 * ws > best.score) best = c2
        }
        return best
    }

    /**
     * Hipótesis a partir de las RECTAS de Hough ([DetLines]): bordes interrumpidos (dedos sobre una tarjeta, una
     * esquina en sombra) que no cierran ningún contorno. Se puntúan todas sin ajustar y sólo las mejores se ajustan.
     */
    private fun lineCandidates(b: Buffers, live: Boolean, out: MutableList<Candidate>) {
        // Rectas sobre la máscara sensible (brillo + croma: el canto de una tarjeta pastel sobre madera apenas cambia
        // de brillo), con huecos cortos para no unir letras en diagonales
        val side0 = max(b.w, b.h)
        val e = b.lineEdges
        // Mediana 5x5: borra los trazos finos (texto, guilloches) y conserva los cantos entre regiones
        Imgproc.medianBlur(b.lRaw, b.tmp, 5)
        Imgproc.Canny(b.tmp, e, 25.0, 60.0)
        for (k in 0 until min(b.nChroma, b.chromaRaw.size)) {
            b.chromaRaw[k].convertTo(b.tmp, -1, 3.0, -256.0)
            Imgproc.medianBlur(b.tmp, b.tmp, 5)
            Imgproc.Canny(b.tmp, b.colorTmp, 30.0, 75.0)
            Core.bitwise_or(e, b.colorTmp, e)
        }
        Imgproc.HoughLinesP(e, b.lines2, 1.0, Math.PI / 180.0, max(20, (side0 * 0.05).roundToInt()), side0 * 0.08, side0 * 0.015)
        val n = min(b.lines2.rows(), MAX_LINE_SEGMENTS)
        if (n < 4) return
        // Sólo segmentos con CONTRASTE de color a ambos lados en la mayor parte de su longitud: la veta de la madera,
        // las líneas de texto o la cuadrícula dan rectas largas pero el mismo color a ambos lados
        val segs = IntArray(n * 4); val seg = IntArray(4); var m = 0
        for (i in 0 until n) {
            b.lines2.get(i, 0, seg)
            val dx = (seg[2] - seg[0]).toFloat(); val dy = (seg[3] - seg[1]).toFloat()
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat(); if (len < 4f) continue
            val nx = -dy / len; val ny = dx / len
            var ok = 0
            for (k in 0 until 12) {
                val t = (k + 0.5f) / 12f
                // (a ±6 px: los trazos finos del texto quedan con papel a ambos lados; el canto de la hoja, no)
                if (contrastAt(b, seg[0] + dx * t, seg[1] + dy * t, nx, ny, LINE_CONTRAST_OFFSET) >= CONTRAST_MIN) ok++
            }
            if (ok < 9) continue
            System.arraycopy(seg, 0, segs, m * 4, 4); m++
        }
        val side = max(b.w, b.h).toDouble()
        val lines = DetLines.cluster(segs, m, Math.toRadians(2.5), 0.008 * side, MAX_LINES)
        if (lines.size < 4) return
        val quads = DetLines.quads(lines, b.w, b.h, minArea = if (live) 0.1 else 0.03)
        val scored = ArrayList<Pair<Double, FloatArray>>()
        for (q0 in quads) {
            val q = orderPoints(Array(4) { Point(q0[it * 2].toDouble(), q0[it * 2 + 1].toDouble()) })
            val s = scoreQuad(q, b, live)
            if (s > 0) scored.add(s to q)
        }
        scored.sortByDescending { it.first }
        debugLog?.invoke("lines: segs=$n/$m lines=${lines.size} quads=${quads.size} valid=${scored.size}")
        for ((_, q) in scored.take(MAX_LINE_QUADS)) {
            val c = considerQuad(q, b, LINE_WEIGHT, live) ?: continue
            // Las rectas de renglones, celdas o columnas de texto también cierran cuadriláteros: sólo valen los que
            // tienen un borde nítido y separan materiales distintos en (casi) todos sus lados
            if (c.quality < LINE_MIN_QUALITY || !separatesMaterials(c.pts, b)) continue
            out.add(c)
        }
    }

    /**
     * ¿Cada lado real (no pegado al marco) de [q] separa dos materiales distintos? Mediana de luminancia y tono en
     * una banda a 5-9 px a cada lado: un renglón o una línea de tabla tienen el mismo papel a ambos lados, el canto
     * de una hoja o tarjeta no. Se tolera un lado dudoso (dedos encima, sombra) si hay cuatro reales.
     */
    private fun separatesMaterials(q: FloatArray, b: Buffers): Boolean {
        val w = b.w; val h = b.h; val m = BORDER_PX
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val lin = IntArray(40); val lout = IntArray(40); val cin = IntArray(40); val cout = IntArray(40)
        var real = 0; var sep = 0
        for (i in 0 until 4) {
            val x0 = q[i * 2]; val y0 = q[i * 2 + 1]; val x1 = q[((i + 1) % 4) * 2]; val y1 = q[((i + 1) % 4) * 2 + 1]
            val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat(); if (len < 10f) continue
            var nx = -(y1 - y0) / len; var ny = (x1 - x0) / len
            if (nx * ((x0 + x1) / 2 - cx) + ny * ((y0 + y1) / 2 - cy) < 0) { nx = -nx; ny = -ny }
            var n = 0
            for (k in 1..10) {
                val t = k / 11f
                val px = x0 + (x1 - x0) * t; val py = y0 + (y1 - y0) * t
                for (o in intArrayOf(5, 9)) {
                    val xo = (px + nx * o).roundToInt(); val yo = (py + ny * o).roundToInt()
                    val xi = (px - nx * o).roundToInt(); val yi = (py - ny * o).roundToInt()
                    if (xo < m || yo < m || xo >= w - m || yo >= h - m || xi < 0 || yi < 0 || xi >= w || yi >= h) continue
                    val io = yo * w + xo; val ii = yi * w + xi
                    lout[n] = b.lBytes[io].toInt() and 0xFF; lin[n] = b.lBytes[ii].toInt() and 0xFF
                    var co = 0; var ci = 0
                    for (k2 in 0 until b.nChroma) { co += b.cBytes[k2][io].toInt() and 0xFF; ci += b.cBytes[k2][ii].toInt() and 0xFF }
                    cout[n] = co; cin[n] = ci; n++
                }
            }
            if (n < 8) continue
            real++
            fun med(a: IntArray) = a.copyOf(n).sorted()[n / 2]
            if (abs(med(lout) - med(lin)) >= SEP_MIN_L || abs(med(cout) - med(cin)) >= SEP_MIN_C) sep++
        }
        return real >= 2 && sep >= (if (real == 4) 3 else real)
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
        if (af < (if (live) 0.1 else 0.035) || af > 0.995) return -1.0
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
        b.feat[0] = af; b.feat[1] = maxCos; b.feat[2] = edge; b.feat[3] = minSide; b.feat[4] = border
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

    // =====================================================================================
    // Hoja superior: blanco sobre blanco y cuadriláteros que cortan el contenido
    // =====================================================================================

    /**
     * Corrige el cuadrilátero elegido cuando (a) CORTA el contenido (p. ej. el marco de una tabla impresa: parte de
     * la tinta queda fuera) o (b) al menos dos de sus lados separan papel de papel (hoja sobre una pila de hojas o
     * sobre una mesa blanca: el borde exterior puede ser el de la pila). En esos casos el contenido (tinta oscura
     * sobre papel, sin rectas largas y finas como las sombras entre hojas) marca el INTERIOR de la hoja superior y
     * cada lado se busca hacia fuera desde el casco del contenido: la PRIMERA recta con gradiente consistente
     * (escalón de brillo/color o la línea de sombra fina entre hojas) a lo largo de todo el contenido, con ±8° de
     * libertad. Sin recta, el lado del cuadrilátero original paralelo (si deja dentro el contenido) o el borde de
     * la imagen. Null = sin cambios. Coordenadas de trabajo.
     */
    private fun refineSheet(rgb: Mat, cand: Candidate, force: Boolean = false): Candidate? = MatBag().use { bag ->
        val w = rgb.cols(); val h = rgb.rows(); val side = max(w, h).toDouble()
        if (min(w, h) < 64) return@use null
        val lab = bag.mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val l = bag.mat(); Core.extractChannel(lab, l, 0)
        Imgproc.GaussianBlur(l, l, Size(3.0, 3.0), 0.7)
        val lb = ByteArray(w * h); l.get(0, 0, lb)
        val ycc = bag.mat(); Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
        val cr = bag.mat(); val cb = bag.mat()
        Core.extractChannel(ycc, cr, 1); Core.extractChannel(ycc, cb, 2)
        Imgproc.GaussianBlur(cr, cr, Size(5.0, 5.0), 0.0); Imgproc.GaussianBlur(cb, cb, Size(5.0, 5.0), 0.0)
        val crb = ByteArray(w * h); cr.get(0, 0, crb)
        val cbb = ByteArray(w * h); cb.get(0, 0, cbb)
        fun lAt(x: Int, y: Int) = lb[y * w + x].toInt() and 0xFF
        fun cAt(x: Int, y: Int) = (crb[y * w + x].toInt() and 0xFF) + (cbb[y * w + x].toInt() and 0xFF)

        // --- Contenido: tinta oscura respecto del papel de alrededor, sobre papel claro
        val k = Cv.odd(max(5, (side * 0.03).roundToInt()))
        val closed = bag.mat(); Imgproc.morphologyEx(l, closed, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, k))
        Imgproc.medianBlur(closed, closed, 5)
        val paper = Cv.percentile(Cv.histogram(closed), 0.9).toDouble()
        if (paper < 60) return@use null
        val diff = bag.mat(); Core.subtract(closed, l, diff)
        // (umbral relativo al papel: fotos oscuras o borrosas tienen menos contraste)
        val ink = bag.mat(); Core.compare(diff, Scalar(max(12.0, min(SHEET_INK_DELTA, 0.15 * paper))), ink, Core.CMP_GT)
        val bright = bag.mat(); Core.compare(closed, Scalar(0.62 * paper), bright, Core.CMP_GT)
        Core.bitwise_and(ink, bright, ink)
        debugMat?.invoke("ink", ink)
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(ink, labels, stats, cents, 8, CvType.CV_32S)
        if (nc <= 1) return@use null
        val st = IntArray(nc * 5); stats.get(0, 0, st)
        // Croma media de cada componente (marcador de color, bolígrafo sobre otra hoja: no marcan la hoja impresa)
        val labA = IntArray(w * h); labels.get(0, 0, labA)
        val chromaSum = LongArray(nc)
        for (i in labA.indices) { val c = labA[i]; if (c > 0) chromaSum[c] += (abs((crb[i].toInt() and 0xFF) - 128) + abs((cbb[i].toInt() and 0xFF) - 128)).toLong() }
        val boxes = ArrayList<IntArray>()
        val extra = HashMap<Int, List<Point>>()   // casco de las componentes grandes (la caja de una tabla girada sobra)
        var inkArea = 0
        val sel = bag.mat(); val cnts = ArrayList<MatOfPoint>(); val hier = bag.mat(); val hi = MatOfInt()
        // Rectas largas y finas: sombra o canto entre hojas, doblez... salvo que vayan ACOMPAÑADAS de contenido a lo
        // largo (borde de una tabla, que con poca nitidez se separa de sus celdas)
        val isLine = BooleanArray(nc)
        for (c in 1 until nc) { val lg = max(st[c * 5 + 2], st[c * 5 + 3]); if (lg >= 0.08 * side && st[c * 5 + 4] <= 2.5 * lg) isLine[c] = true }
        val lineOk = BooleanArray(nc)
        if (isLine.any { it }) {
            val other = bag.mat(); other.create(h, w, CvType.CV_8UC1)
            val ob = ByteArray(w * h)
            for (i in labA.indices) { val c = labA[i]; if (c > 0 && !isLine[c] && st[c * 5 + 4] >= 3) ob[i] = -1 }
            other.put(0, 0, ob)
            Imgproc.dilate(other, other, Cv.kernel(Imgproc.MORPH_RECT, Cv.odd(max(3, (side * 0.025).roundToInt()))))
            val near = ByteArray(w * h); other.get(0, 0, near)
            val tot = IntArray(nc); val hit = IntArray(nc)
            for (i in labA.indices) { val c = labA[i]; if (c > 0 && isLine[c]) { tot[c]++; if (near[i].toInt() != 0) hit[c]++ } }
            for (c in 1 until nc) if (isLine[c] && hit[c] >= 0.6 * tot[c]) lineOk[c] = true
        }
        for (c in 1 until nc) {
            val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bh = st[c * 5 + 3]; val a = st[c * 5 + 4]
            if (a < 2) continue
            if (x <= 2 || y <= 2 || x + bw >= w - 2 || y + bh >= h - 2) continue
            val lg = max(bw, bh)
            if (isLine[c] && !lineOk[c]) continue
            if (lg < 0.15 * side && chromaSum[c].toDouble() / a > SHEET_INK_CHROMA) continue
            if (lg >= 0.05 * side) {
                Core.compare(labels, Scalar(c.toDouble()), sel, Core.CMP_EQ)
                cnts.clear(); Imgproc.findContours(sel, cnts, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                val all = cnts.flatMap { it.toList() }
                for (m in cnts) m.release()
                if (all.size >= 3) {
                    val mp = MatOfPoint(*all.toTypedArray()); Imgproc.convexHull(mp, hi, false)
                    extra[boxes.size] = hi.toArray().map { all[it] }; mp.release()
                }
            }
            boxes.add(intArrayOf(x, y, x + bw, y + bh, a)); inkArea += a
        }
        hi.release()
        if (boxes.size < 6 || inkArea < max(30.0, 0.0015 * w * h)) return@use null
        // Sólo el bloque de contenido PRINCIPAL (tinta agrupada con huecos <= ~3 % del lado): marcas sueltas en
        // otras hojas de la pila, bolígrafos o restos del fondo quedan fuera
        run {
            val bm = bag.mat(); bm.create(h, w, CvType.CV_8UC1); bm.setTo(Scalar(0.0))
            for (b0 in boxes) Imgproc.rectangle(bm, Point(b0[0].toDouble(), b0[1].toDouble()), Point(b0[2].toDouble(), b0[3].toDouble()), Scalar(255.0), -1)
            Imgproc.dilate(bm, bm, Cv.kernel(Imgproc.MORPH_RECT, Cv.odd(max(5, (side * 0.04).roundToInt()))))
            val bl = bag.mat(); val bs = bag.mat(); val bc = bag.mat()
            val nb = Imgproc.connectedComponentsWithStats(bm, bl, bs, bc, 8, CvType.CV_32S)
            if (nb <= 2) return@run
            val lab1 = IntArray(1)
            val inkOf = LongArray(nb)
            val owner = IntArray(boxes.size)
            for ((i, b0) in boxes.withIndex()) { bl.get((b0[1] + b0[3]) / 2, (b0[0] + b0[2]) / 2, lab1); owner[i] = lab1[0]; inkOf[lab1[0]] += b0[4].toLong() }
            var main = 1; for (c in 1 until nb) if (inkOf[c] > inkOf[main]) main = c
            val keepIdx = boxes.indices.filter { owner[it] == main }
            val keep = keepIdx.map { boxes[it] }
            val ex2 = HashMap<Int, List<Point>>(); for ((ni, oi) in keepIdx.withIndex()) extra[oi]?.let { ex2[ni] = it }
            extra.clear(); extra.putAll(ex2)
            boxes.clear(); boxes.addAll(keep); inkArea = keep.sumOf { it[4] }
        }
        if (boxes.size < 6 || inkArea < max(30.0, 0.0015 * w * h)) return@use null
        debugBoxes?.invoke(boxes)

        // --- ¿Hace falta? (a) contenido fuera del quad; (b) lados papel/papel
        val q = cand.pts
        val qcx = (q[0] + q[2] + q[4] + q[6]) / 4.0; val qcy = (q[1] + q[3] + q[5] + q[7]) / 4.0
        val poly = bag.add(MatOfPoint2f(*Array(4) { Point(q[it * 2].toDouble(), q[it * 2 + 1].toDouble()) }))
        var outA = 0
        for (b0 in boxes) {
            val p = Point((b0[0] + b0[2]) / 2.0, (b0[1] + b0[3]) / 2.0)
            if (Imgproc.pointPolygonTest(poly, p, true) < -2.0) outA += b0[4]
        }
        // (si casi todo el "contenido" queda fuera, no es la tinta de esta hoja: veta de la madera, otro objeto)
        val cuts = outA >= 0.03 * inkArea && outA <= 0.4 * inkArea
        var paperSides = 0
        for (i in 0 until 4) {
            val x0 = q[i * 2]; val y0 = q[i * 2 + 1]; val x1 = q[((i + 1) % 4) * 2]; val y1 = q[((i + 1) % 4) * 2 + 1]
            val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()); if (len < 10) continue
            var nx = -(y1 - y0) / len; var ny = (x1 - x0) / len
            // hacia fuera = alejándose del centro
            if (nx * ((x0 + x1) / 2.0 - qcx) + ny * ((y0 + y1) / 2.0 - qcy) < 0) { nx = -nx; ny = -ny }
            var ok = 0; var tot = 0
            for (s in 1..29) {
                val t = 0.1 + 0.8 * s / 30.0
                val px = x0 + (x1 - x0) * t; val py = y0 + (y1 - y0) * t
                val ox = (px + nx * 6).roundToInt(); val oy = (py + ny * 6).roundToInt()
                val ix = (px - nx * 6).roundToInt(); val iy = (py - ny * 6).roundToInt()
                if (ox !in 0 until w || oy !in 0 until h || ix !in 0 until w || iy !in 0 until h) continue
                tot++
                val lo = lAt(ox, oy); val li = lAt(ix, iy)
                if (lo >= 0.8 * paper && abs(lo - li) <= 28 && abs(cAt(ox, oy) - cAt(ix, iy)) <= 10) ok++
            }
            if (tot >= 10 && ok >= 0.6 * tot) paperSides++
        }
        debugLog?.invoke("sheet: cuts=$cuts (${outA}/$inkArea) paperSides=$paperSides comps=${boxes.size}")
        if (!force && !cuts && paperSides < 2) return@use null

        // --- Casco del contenido y orientación
        val hp = ArrayList<Point>(boxes.size * 4)
        for ((bi, b0) in boxes.withIndex()) { val e = extra[bi]; if (e != null) { hp.addAll(e); continue }; hp.add(Point(b0[0].toDouble(), b0[1].toDouble())); hp.add(Point(b0[2].toDouble(), b0[1].toDouble())); hp.add(Point(b0[2].toDouble(), b0[3].toDouble())); hp.add(Point(b0[0].toDouble(), b0[3].toDouble())) }
        val hpm = bag.add(MatOfPoint2f(*hp.toTypedArray()))
        val hullIdx = bag.add(MatOfInt())
        val hpi = bag.add(MatOfPoint(*hp.map { Point(it.x, it.y) }.toTypedArray()))
        Imgproc.convexHull(hpi, hullIdx, false)
        val hull = hullIdx.toArray().map { hp[it] }
        if (hull.size < 3) return@use null
        val hullArea = abs(Imgproc.contourArea(bag.add(MatOfPoint2f(*hull.toTypedArray()))))
        val rr = Imgproc.minAreaRect(hpm)
        val th = Math.toRadians(rr.angle)
        val ux = kotlin.math.cos(th); val uy = kotlin.math.sin(th)
        val normals = arrayOf(doubleArrayOf(-uy, ux), doubleArrayOf(ux, uy), doubleArrayOf(uy, -ux), doubleArrayOf(-ux, -uy))
        // Evidencia de borde en (x, y) con normal (nx, ny): cresta oscura fina (sombra entre hojas) o escalón
        fun edgeAt(x: Double, y: Double, nx: Double, ny: Double): Boolean {
            val xi = x.roundToInt(); val yi = y.roundToInt()
            val xa = (x + 2 * nx).roundToInt(); val ya = (y + 2 * ny).roundToInt()
            val xb = (x - 2 * nx).roundToInt(); val yb = (y - 2 * ny).roundToInt()
            if (min(min(xi, xa), xb) < 0 || min(min(yi, ya), yb) < 0 || max(max(xi, xa), xb) >= w || max(max(yi, ya), yb) >= h) return false
            val l0 = lAt(xi, yi); val la = lAt(xa, ya); val lbb = lAt(xb, yb)
            if (min(la, lbb) - l0 >= SHEET_RIDGE) return true
            return abs(la - lbb) + 2 * abs(cAt(xa, ya) - cAt(xb, yb)) >= SHEET_STEP
        }
        // Textura TRANSVERSAL junto a una recta candidata (rectas de celdas, texto): fracción de muestras con
        // gradiente fuerte orientado a lo largo de la recta, en franjas de ~8 px a ambos lados. El borde de una hoja
        // separa papel de papel liso, fondo o cantos PARALELOS de la pila; el marco de una tabla cuyo interior no
        // se detectó como contenido (foto borrosa) tiene celdas detrás -> no es el borde de la hoja.
        fun crossTexture(c: Double, nx: Double, ny: Double, tx: Double, ty: Double, u0: Double, u1: Double, cmin: Double): Double {
            var cross = 0; var tot = 0
            val m = max(8, ((u1 - u0) / 2).roundToInt())
            for (side2 in 0..1) {
                for (o in 2..9) {
                    val cc = if (side2 == 0) c + o else c - o
                    if (side2 == 1 && cc < cmin - 1) break
                    for (s in 0..m) {
                        val u = u0 + (u1 - u0) * s / m
                        val xi = (cc * nx + u * tx).roundToInt(); val yi = (cc * ny + u * ty).roundToInt()
                        if (xi < 1 || yi < 1 || xi >= w - 1 || yi >= h - 1) continue
                        tot++
                        val gx = (lAt(xi + 1, yi) - lAt(xi - 1, yi)).toDouble(); val gy = (lAt(xi, yi + 1) - lAt(xi, yi - 1)).toDouble()
                        if (abs(gx) + abs(gy) < SHEET_STEP) continue
                        if (abs(gx * tx + gy * ty) > 1.5 * abs(gx * nx + gy * ny)) cross++
                    }
                }
            }
            return if (tot == 0) 0.0 else cross.toDouble() / tot
        }
        val lines = ArrayList<DoubleArray>(4)   // (nx, ny, c): n·p = c
        val found = BooleanArray(4)
        for ((si, nb) in normals.withIndex()) {
            var best: DoubleArray? = null; var bestDist = Double.MAX_VALUE; var bestScore = 0.0
            for (dDeg in -8..8 step 2) {
                val a = Math.toRadians(dDeg.toDouble())
                val nx = nb[0] * kotlin.math.cos(a) - nb[1] * kotlin.math.sin(a)
                val ny = nb[0] * kotlin.math.sin(a) + nb[1] * kotlin.math.cos(a)
                val tx = -ny; val ty = nx
                var cmin = -1e9; var umin = 1e9; var umax = -1e9; var cRef = -1e9
                for (p in hull) { val c = p.x * nx + p.y * ny; if (c > cmin) cmin = c; val u = p.x * tx + p.y * ty; umin = min(umin, u); umax = max(umax, u) }
                // Distancia medida respecto de la dirección base (comparable entre ángulos)
                for (p in hull) cRef = max(cRef, p.x * nb[0] + p.y * nb[1])
                val ul = umax - umin; if (ul < 10) continue
                val u0 = umin + 0.12 * ul; val u1 = umax - 0.12 * ul
                val m = max(12, (u1 - u0).roundToInt())
                var c = cmin + 2
                while (c < cmin + 0.6 * side) {
                    var hits = 0; var inside = 0
                    for (s in 0..m) {
                        val u = u0 + (u1 - u0) * s / m
                        val x = c * nx + u * tx; val y = c * ny + u * ty
                        if (x < 2 || y < 2 || x > w - 3 || y > h - 3) continue
                        inside++
                        if (edgeAt(x, y, nx, ny) || edgeAt(x + nx, y + ny, nx, ny) || edgeAt(x - nx, y - ny, nx, ny)) hits++
                    }
                    if (inside < 0.5 * (m + 1)) break
                    val score = hits.toDouble() / inside
                    val ct = if (score >= SHEET_LINE_SCORE) crossTexture(c, nx, ny, tx, ty, u0, u1, cmin) else 1.0
                    if (score >= SHEET_LINE_SCORE && ct <= SHEET_CROSS_MAX) {
                        // Distancia en el centro del tramo, respecto del casco en la dirección base
                        val um = (u0 + u1) / 2; val mx = c * nx + um * tx; val my = c * ny + um * ty
                        val dist = mx * nb[0] + my * nb[1] - cRef
                        if (dist < bestDist - 1.5 || (dist < bestDist + 1.5 && score > bestScore)) { bestDist = dist; bestScore = score; best = doubleArrayOf(nx, ny, c) }
                        break
                    }
                    c += 1.0
                }
            }
            if (best != null) { lines.add(best); found[si] = true; continue }
            // Sin recta: lado del quad original casi paralelo que deja dentro el contenido, o borde de la imagen
            var fb: DoubleArray? = null
            for (i in 0 until 4) {
                val x0 = q[i * 2].toDouble(); val y0 = q[i * 2 + 1].toDouble(); val x1 = q[((i + 1) % 4) * 2].toDouble(); val y1 = q[((i + 1) % 4) * 2 + 1].toDouble()
                val len = hypot(x1 - x0, y1 - y0); if (len < 10) continue
                var nx = -(y1 - y0) / len; var ny = (x1 - x0) / len
                if (nx * ((x0 + x1) / 2 - qcx) + ny * ((y0 + y1) / 2 - qcy) < 0) { nx = -nx; ny = -ny }
                if (nx * nb[0] + ny * nb[1] < 0.95) continue
                val c = x0 * nx + y0 * ny
                if (hull.all { it.x * nx + it.y * ny <= c - 1 }) fb = doubleArrayOf(nx, ny, c)
            }
            if (fb == null) {
                // borde de la imagen más alineado con la normal
                val cands = arrayOf(doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, w - 1.0), doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 1.0, h - 1.0))
                fb = cands.maxByOrNull { it[0] * nb[0] + it[1] * nb[1] }!!
            }
            lines.add(fb)
        }
        debugLog?.invoke("sheet: lines=" + lines.joinToString { "(%.2f,%.2f,%.0f)".format(it[0], it[1], it[2]) } + " found=${found.toList()}")
        if (found.count { it } < (if (force) 3 else 2)) return@use null
        val pts = arrayOfNulls<Point>(4)
        for (i in 0 until 4) {
            val a = lines[i]; val b = lines[(i + 1) % 4]
            val den = a[0] * b[1] - a[1] * b[0]
            if (abs(den) < 1e-6) return@use null
            pts[i] = Point((a[2] * b[1] - a[1] * b[2]) / den, (a[0] * b[2] - a[2] * b[0]) / den)
        }
        // Ajuste por MITADES de cada lado (hoja curvada o caída en una esquina): cada esquina sale de los tramos que
        // llegan a ella. En cada mitad, recta a ±4° y ±6 px; entre las bien apoyadas, la más interior (la hoja de
        // encima, no los cantos de la pila que asoman justo detrás).
        fun segScore(nx: Double, ny: Double, c: Double, u0: Double, u1: Double): Double {
            val tx = -ny; val ty = nx
            val m = max(8, abs(u1 - u0).roundToInt())
            var hits = 0; var inside = 0
            for (s in 0..m) {
                val u = u0 + (u1 - u0) * s / m
                val x = c * nx + u * tx; val y = c * ny + u * ty
                if (x < 2 || y < 2 || x > w - 3 || y > h - 3) continue
                inside++
                if (edgeAt(x, y, nx, ny) || edgeAt(x + nx, y + ny, nx, ny) || edgeAt(x - nx, y - ny, nx, ny)) hits++
            }
            return if (inside < 0.5 * (m + 1)) 0.0 else hits.toDouble() / inside
        }
        val startL = Array(4) { lines[it] }; val endL = Array(4) { lines[it] }
        for (i in 0 until 4) {
            if (!found[i]) continue
            val ln = lines[i]; val tx = -ln[1]; val ty = ln[0]
            val pa = pts[(i + 3) % 4]!!; val pb = pts[i]!!
            val ua = pa.x * tx + pa.y * ty; val ub = pb.x * tx + pb.y * ty
            val len = ub - ua; if (abs(len) < 20) continue
            for (half in 0..1) {
                val h0 = if (half == 0) ua + 0.05 * len else ua + 0.5 * len
                val h1 = if (half == 0) ua + 0.5 * len else ub - 0.05 * len
                val hm = (h0 + h1) / 2
                val mx = ln[2] * ln[0] + hm * tx; val my = ln[2] * ln[1] + hm * ty
                var bestS = segScore(ln[0], ln[1], ln[2], h0, h1); var bestL = ln; var bestInner = Double.MAX_VALUE
                val base = bestS
                for (dDeg in -4..4) for (dc in -6..6) {
                    if (dDeg == 0 && dc == 0) continue
                    val a = Math.toRadians(dDeg.toDouble())
                    val nx = ln[0] * kotlin.math.cos(a) - ln[1] * kotlin.math.sin(a)
                    val ny = ln[0] * kotlin.math.sin(a) + ln[1] * kotlin.math.cos(a)
                    val px = mx + dc * ln[0]; val py = my + dc * ln[1]
                    val c = px * nx + py * ny
                    val t2x = -ny; val t2y = nx
                    // el mismo tramo proyectado sobre la recta girada
                    val q0 = (ln[2] * ln[0] + h0 * tx) * t2x + (ln[2] * ln[1] + h0 * ty) * t2y
                    val q1 = (ln[2] * ln[0] + h1 * tx) * t2x + (ln[2] * ln[1] + h1 * ty) * t2y
                    val sc = segScore(nx, ny, c, q0, q1)
                    val strong = sc >= 0.65
                    if (strong && (bestS < 0.65 || dc < bestInner)) { bestS = sc; bestL = doubleArrayOf(nx, ny, c); bestInner = dc.toDouble() }
                    else if (!strong && bestS < 0.65 && sc > bestS) { bestS = sc; bestL = doubleArrayOf(nx, ny, c) }
                }
                if (bestS >= 0.5 && bestS > base + 0.05) { if (half == 0) startL[i] = bestL else endL[i] = bestL }
            }
        }
        for (i in 0 until 4) {
            val a = endL[i]; val b = startL[(i + 1) % 4]
            val den = a[0] * b[1] - a[1] * b[0]
            if (abs(den) < 1e-6) continue
            val p = Point((a[2] * b[1] - a[1] * b[2]) / den, (a[0] * b[2] - a[2] * b[0]) / den)
            if (hypot(p.x - pts[i]!!.x, p.y - pts[i]!!.y) <= 0.08 * side) pts[i] = p
        }
        val res = orderPoints(pts.map { it!! }.toTypedArray())
        clampPts(res, w - 1, h - 1)
        debugLog?.invoke("sheet: quad " + (0 until 4).joinToString(" ") { "%.0f,%.0f".format(res[it * 2], res[it * 2 + 1]) })
        // Validación: convexo, ángulos razonables, contiene el contenido
        val area = polyArea(res)
        if (area < hullArea * 1.02 || area < 0.08 * w * h) { debugLog?.invoke("sheet: area $area hull $hullArea"); return@use null }
        val rp = bag.add(MatOfPoint2f(*Array(4) { Point(res[it * 2].toDouble(), res[it * 2 + 1].toDouble()) }))
        if (!Imgproc.isContourConvex(MatOfPoint(*Array(4) { Point(res[it * 2].toDouble(), res[it * 2 + 1].toDouble()) }))) { debugLog?.invoke("sheet: convex"); return@use null }
        for (i in 0 until 4) {
            val p0x = res[((i + 3) % 4) * 2] - res[i * 2]; val p0y = res[((i + 3) % 4) * 2 + 1] - res[i * 2 + 1]
            val p1x = res[((i + 1) % 4) * 2] - res[i * 2]; val p1y = res[((i + 1) % 4) * 2 + 1] - res[i * 2 + 1]
            val cs = abs((p0x * p1x + p0y * p1y) / (hypot(p0x.toDouble(), p0y.toDouble()) * hypot(p1x.toDouble(), p1y.toDouble()) + 1e-9))
            if (cs > 0.55) { debugLog?.invoke("sheet: angle"); return@use null }
        }
        var outside = 0
        for (p in hull) if (Imgproc.pointPolygonTest(rp, p, true) < -max(3.0, 0.03 * side)) outside++
        if (outside > 0) { debugLog?.invoke("sheet: outside $outside"); return@use null }
        debugLog?.invoke("sheet: found=${found.toList()} -> " + (0 until 4).joinToString(" ") { "%.0f,%.0f".format(res[it * 2], res[it * 2 + 1]) })
        // Confianza: sin contorno de partida (forzado) sólo es fiable con los cuatro lados encontrados; con tres queda
        // por debajo del umbral de la app (se usa la imagen completa, que el usuario puede ajustar)
        val nf = found.count { it }
        val conf = if (force) (if (nf == 4) SHEET_CONF_FORCED4 else SHEET_CONF_FORCED3) else scoreToConf(max(cand.score, 0.6))
        Candidate(res, max(cand.score, 0.6), conf)
    }

    /** Depuración (banco de pruebas). */
    internal var debugLog: ((String) -> Unit)? = null

    /** Hipótesis por rectas (desactivable en el banco de pruebas). */
    internal var lineQuads = true

    /** Reordenación por material (desactivable en el banco de pruebas). */
    internal var rerank = true

    /** Búsqueda de la hoja superior (desactivable en el banco de pruebas). */
    internal var sheetRefine = true
    internal var debugBoxes: ((List<IntArray>) -> Unit)? = null
    internal var debugMat: ((String, Mat) -> Unit)? = null

    /**
     * Confianza de un candidato: la mayor entre la de su puntuación (que premia el área) y la de la CALIDAD de su
     * borde (apoyo global, peor lado, ángulos, tramos sobre el marco), que no depende del tamaño: una hoja que ocupa
     * el 8 % del encuadre con los cuatro lados nítidos es tan fiable como una grande. La segunda exige además que
     * el interior parezca papel frente a su entorno (no una franja oscura del teclado o de un mueble).
     */
    private fun candidateConf(q: FloatArray, b: Buffers, score: Double, provenance: Double): Double {
        val c0 = scoreToConf(score)
        val qc = qualityConf(b.feat, provenance)
        // Documento muy pequeño en el encuadre (< 8 %): hace falta más evidencia (etiquetas, carteles, pantallas lejanas)
        val small = min(1.0, b.feat[0] / SMALL_DOC_AREA)
        if (qc <= c0) return c0 * small
        return max(c0, qc * paperFactor(q, b)) * small
    }

    /**
     * Factor 0.3..1 según el interior del cuadrilátero sea papel (claro respecto de lo que lo rodea). Mediana de la
     * luminancia en una malla interior frente a la de una banda exterior a cada lado.
     */
    private fun paperFactor(q: FloatArray, b: Buffers): Double {
        val w = b.w; val h = b.h; val lb = b.lBytes
        val inside = IntArray(49); var ni = 0
        for (iy in 0 until 7) for (ix in 0 until 7) {
            val u = 0.15f + 0.7f * ix / 6f; val v = 0.15f + 0.7f * iy / 6f
            val x = (q[0] * (1 - u) * (1 - v) + q[2] * u * (1 - v) + q[4] * u * v + q[6] * (1 - u) * v).roundToInt()
            val y = (q[1] * (1 - u) * (1 - v) + q[3] * u * (1 - v) + q[5] * u * v + q[7] * (1 - u) * v).roundToInt()
            if (x in 0 until w && y in 0 until h) inside[ni++] = lb[y * w + x].toInt() and 0xFF
        }
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val off = max(6f, 0.03f * max(w, h))
        val outside = IntArray(28); var no = 0
        for (i in 0 until 4) {
            val x0 = q[i * 2]; val y0 = q[i * 2 + 1]; val x1 = q[((i + 1) % 4) * 2]; val y1 = q[((i + 1) % 4) * 2 + 1]
            val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat(); if (len < 1f) continue
            var nx = -(y1 - y0) / len; var ny = (x1 - x0) / len
            if (nx * ((x0 + x1) / 2 - cx) + ny * ((y0 + y1) / 2 - cy) < 0) { nx = -nx; ny = -ny }
            for (k in 1..7) {
                val t = k / 8f
                val x = (x0 + (x1 - x0) * t + nx * off).roundToInt(); val y = (y0 + (y1 - y0) * t + ny * off).roundToInt()
                if (x in 0 until w && y in 0 until h) outside[no++] = lb[y * w + x].toInt() and 0xFF
            }
        }
        if (ni < 10 || no < 6) return 1.0
        val li = inside.copyOf(ni).sorted()[ni / 2]; val lo = outside.copyOf(no).sorted()[no / 2]
        if (li >= PAPER_BRIGHT) return 1.0
        // Más oscuro que su entorno inmediato o que la escena en conjunto: probablemente no es papel
        val ref = max(lo, b.lMedian)
        return (1.0 - (ref - PAPER_DARKER_TOL - li) / 40.0).coerceIn(0.3, 1.0)
    }

    companion object {
        /** Anidamiento: calidad de borde por debajo de la cual se busca un candidato interior mejor, y la mínima de éste. */
        private const val NEST_WEAK = 0.35
        private const val NEST_STRONG = 0.5
        /** Anidamiento: distancia máxima (fracción de la diagonal del exterior) para que dos lados coincidan. */
        private const val NEST_SHARED_SIDE = 0.04

        /** Hipótesis por rectas: segmentos de Hough usados, rectas agrupadas, cuadriláteros ajustados y su peso. */
        private const val MAX_LINE_SEGMENTS = 200
        private const val MAX_LINES = 18
        private const val MAX_LINE_QUADS = 8
        private const val LINE_WEIGHT = 0.93
        private const val LINE_CONTRAST_OFFSET = 6f
        private const val LINE_MIN_QUALITY = 0.5
        /** Ventaja de calidad de borde que necesita una hipótesis por rectas para competir con los contornos. */
        private const val LINE_BETTER = 0.15
        /** Diferencia mínima de luminancia o tono entre ambos lados para considerar materiales distintos. */
        private const val SEP_MIN_L = 18
        private const val SEP_MIN_C = 6

        /** Escaneo: fracción mínima de píxeles sin color y de papel blanco saturado (L >= [SCAN_WHITE_L]). */
        private const val SCAN_GRAY = 0.9
        private const val SCAN_WHITE_FRAC = 0.2
        private const val SCAN_WHITE_L = 245.0
        /** Fracción del encuadre por debajo de la cual la confianza se reduce en proporción al área. */
        private const val SMALL_DOC_AREA = 0.08
        /** Confianza mínima de la hipótesis que sustituye a la elegida en la reordenación por material. */
        private const val SWAP_MIN_CONF = 0.35

        /** Candidatos (por puntuación) en los que se comprueba si son un marco impreso. */
        private const val MAX_FRAME_CHECKS = 6
        /** Marco impreso: fuera del lado, papel casi tan claro como el de la página (no una mesa gris clara). */
        private const val FRAME_OUT_PAPER = 0.88
        /** Marco impreso: diferencia máxima de tono (Cb + Cr) entre ambos lados de un lado "papel/papel". */
        private const val FRAME_PAPER_CHROMA = 6
        /** Marco impreso: densidad mínima de tinta fuera del cuadrilátero (fracción de píxeles). */
        private const val FRAME_INK_OUT_MIN = 0.012

        /** Confianza mínima (de calidad de borde) para aceptar un candidato de puntuación baja. */
        private const val ACCEPT_CONF = 0.25

        /** Interior con esta luminancia (mediana) o más ya se considera papel aunque el entorno sea más claro. */
        private const val PAPER_BRIGHT = 150
        /** Tolerancia (niveles de L) de un interior más oscuro que su entorno antes de dudar de que sea papel. */
        private const val PAPER_DARKER_TOL = 8

        /** Confianza 0..1 a partir de la puntuación de ordenación (premia el área). */
        private fun scoreToConf(score: Double): Double = ((score - 0.45) / 0.4).coerceIn(0.0, 1.0)

        /**
         * Confianza 0..1 por la calidad del borde, independiente del área: [f] = rasgos de [scoreQuad] (área, maxCos,
         * apoyo global, apoyo del peor lado, fracción sobre el marco); [provenance] = peso de la hipótesis (1 si ya
         * está ajustada a los bordes reales, 0.7 un rectángulo mínimo sin ajustar).
         */
        internal fun qualityConf(f: DoubleArray, provenance: Double): Double {
            val af = f[0]; val maxCos = f[1]; val edge = f[2]; val minSide = f[3]; val border = f[4]
            var q = 0.5 * edge + 0.3 * minSide + 0.2 * (1.0 - maxCos / 0.55)
            q *= 1.0 - 0.5 * border
            q *= min(1.0, 0.75 + 0.25 * provenance)
            // Muy pequeño (< 10 % del encuadre): algo menos fiable (etiquetas, pantallas de móvil, tapas)
            q *= min(1.0, 0.7 + 3.0 * af)
            return ((q - 0.62) / 0.3).coerceIn(0.0, 1.0)
        }

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

        /** Hoja superior buscada sin contorno de partida: confianza con 4 y con 3 lados encontrados. */
        private const val SHEET_CONF_FORCED4 = 0.4
        private const val SHEET_CONF_FORCED3 = 0.3

        /** Hoja superior: oscuridad mínima de la tinta respecto del papel de alrededor (niveles de L). */
        private const val SHEET_INK_DELTA = 28.0
        /** Hoja superior: croma media máxima (|Cr-128| + |Cb-128|) de una componente de contenido. */
        private const val SHEET_INK_CHROMA = 22.0
        /** Hoja superior: profundidad mínima de la cresta oscura (sombra fina entre hojas) y escalón mínimo. */
        private const val SHEET_RIDGE = 4
        private const val SHEET_STEP = 8
        /** Hoja superior: fracción mínima del tramo con evidencia de borde para aceptar una recta. */
        private const val SHEET_LINE_SCORE = 0.55
        /** Hoja superior: textura transversal máxima junto a la recta (ver crossTexture). */
        private const val SHEET_CROSS_MAX = 0.04

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
