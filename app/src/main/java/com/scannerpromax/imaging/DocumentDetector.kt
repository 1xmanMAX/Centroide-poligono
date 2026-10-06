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
 * Estrategias combinadas y puntuadas (a ~500 px de lado largo):
 *  1. Canny con umbrales sacados del MÓDULO DEL GRADIENTE (percentil) sobre luminancia L (Lab), con CLAHE
 *     suave si hay poca luz. (La mediana de intensidad fallaba en escenas claras: papel sobre mesa clara.)
 *  2. Bordes de color: saturación ponderada por brillo y canales a/b de Lab (tonos parecidos).
 *  3. Máscara de bordes sensible (Canny 20/50) cerrada morfológicamente.
 *  4. Segmentación Otsu del papel (claro) + apertura morfológica.
 *  5. HoughLinesP extendidas para cerrar documentos cuyo contorno está cortado (sombras, dedos).
 * Cada contorno grande -> casco convexo -> approxPolyDP (o mejor cuadrilátero inscrito / minAreaRect).
 * Puntuación: área, ángulos ~90°, convexidad y fracción del perímetro apoyada sobre bordes reales.
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
        var emaskBytes = ByteArray(0)
        var w = 0; var h = 0
        fun release() {
            blur.release(); tmp.release(); edges.release(); edges2.release(); otsu.release(); emask.release()
            emaskClosed.release(); hough.release(); lines.release(); hierarchy.release()
            gx.release(); gy.release(); mag.release(); mag2.release(); colorTmp.release()
            hullIdx.release(); approx.release(); hull2f.release()
        }
    }

    private val kernel3: Mat by lazy { Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)) }

    // ---- Estado de la detección en vivo (protegido por @Synchronized) ----
    private var liveBuf: Buffers? = null
    private var yBytes = ByteArray(0)
    private var yMat: Mat? = null
    private var yRoi: Mat? = null
    private var liveSmall: Mat? = null
    private var liveRot: Mat? = null
    private var liveClahe: org.opencv.imgproc.CLAHE? = null

    private data class Candidate(val pts: FloatArray, val score: Double)

    // =====================================================================================
    // API pública
    // =====================================================================================

    /** Detección precisa sobre una foto completa (tras capturar). */
    fun detect(bitmap: Bitmap): DetectionResult? {
        val w = bitmap.width; val h = bitmap.height
        if (w < 32 || h < 32) return null
        val buf = Buffers()
        val bag = MatBag()
        try {
            // Reducción SIN aliasing (mitades + INTER_AREA): createScaledBitmap x8 convertía el texto en
            // sal y pimienta, disparaba la densidad de bordes y creaba contornos espurios.
            val rgba = bag.add(Cv.toRgbaScaled(bitmap, detectSide).first)
            val rgb = bag.mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
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

            val cand = detectCore(l, listOf(sw8, ca, cb), buf, live = false) ?: return null
            val fx = w.toDouble() / l.cols(); val fy = h.toDouble() / l.rows()
            val pts = FloatArray(8) { i -> (cand.pts[i] * if (i % 2 == 0) fx else fy).toFloat() }
            clampPts(pts, w, h)
            refineCorners(bitmap, pts)
            clampPts(pts, w, h)
            return DetectionResult(toQuad(pts), confidenceOf(cand.score), w, h)
        } catch (t: Throwable) {
            return null
        } finally {
            bag.close(); buf.release()
        }
    }

    /**
     * Detección rápida para vista previa en vivo, desde el plano Y (luminancia) de un frame YUV de CameraX.
     * [rowStride] del plano. [rotationDegrees] la rotación del frame; el quad devuelto ya está en
     * coordenadas del frame ROTADO (ancho/alto intercambiados si 90/270).
     */
    @Synchronized
    fun detectLive(yPlane: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, rotationDegrees: Int): DetectionResult? {
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
            // Poca luz: CLAHE suave (instancia reutilizada entre frames). Se aplica sobre la copia reducida.
            if (Core.mean(work).`val`[0] < LOW_LIGHT_MEAN) {
                val c = liveClahe ?: Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).also { liveClahe = it }
                enhanceLowLight(work, c)
            }
            val cand = detectCore(work, emptyList(), buf, live = true) ?: return null
            val rotW = if (rot == 90 || rot == 270) height else width
            val rotH = if (rot == 90 || rot == 270) width else height
            val fx = rotW.toDouble() / work.cols(); val fy = rotH.toDouble() / work.rows()
            val pts = FloatArray(8) { i -> (cand.pts[i] * if (i % 2 == 0) fx else fy).toFloat() }
            clampPts(pts, rotW, rotH)
            val res = DetectionResult(toQuad(pts), confidenceOf(cand.score), rotW, rotH)
            rememberLiveQuad(pts, rot, width, height)
            return res
        } catch (t: Throwable) {
            return null
        } finally {
            if (lastLiveFrame != liveFrameSeq) lastLivePts = null
        }
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

    private fun detectCore(l: Mat, colorChannels: List<Mat>, b: Buffers, live: Boolean): Candidate? {
        val w = l.cols(); val h = l.rows()
        val area = w.toDouble() * h
        b.w = w; b.h = h

        Imgproc.GaussianBlur(l, b.blur, Size(5.0, 5.0), 0.0)

        // Máscara de bordes permisiva para puntuar (sin el marco de la imagen)
        Imgproc.Canny(b.blur, b.emask, 20.0, 50.0)
        Imgproc.dilate(b.emask, b.emask, kernel3)
        Imgproc.rectangle(b.emask, Point(0.0, 0.0), Point(w - 1.0, h - 1.0), Scalar(0.0), 4)
        val n = w * h
        if (b.emaskBytes.size != n) b.emaskBytes = ByteArray(n)
        b.emask.get(0, 0, b.emaskBytes)

        val sources = ArrayList<Pair<Mat, Double>>(6)
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
        // 5) Líneas de Hough extendidas (cierra contornos rotos)
        val houghSrc = primary ?: b.emask
        Imgproc.HoughLinesP(houghSrc, b.lines, 1.0, Math.PI / 180.0, max(30, (w * 0.12).roundToInt()), w * 0.15, w * 0.04)
        if (!b.lines.empty()) {
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
        if (approx.size in 4..8) {
            val q = if (approx.size == 4) orderPoints(approx) else best4(approx)
            val s = scoreQuad(q, b, live)
            if (s > 0) best = Candidate(q, s * weight * (if (approx.size == 4) 1.0 else 0.92))
        }
        // Rectángulo mínimo (documentos con esquinas dobladas/tapadas)
        val rr = Imgproc.minAreaRect(hull2f)
        val box = arrayOfNulls<Point>(4).also { rr.points(it) }.map { it!! }.toTypedArray()
        val qr = orderPoints(box)
        val sr = scoreQuad(qr, b, live)
        if (sr > 0) {
            val cr = Candidate(qr, sr * weight * 0.7)
            if (best == null || cr.score > best.score) best = cr
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
        val edge = edgeSupport(q, b)
        if (edge < 0.35) return -1.0
        return 0.4 * min(1.0, af / 0.6) + 0.2 * (1.0 - maxCos / cosLimit) + 0.4 * edge
    }

    /** Fracción del perímetro del quad que cae sobre bordes detectados. */
    private fun edgeSupport(q: FloatArray, b: Buffers): Double {
        val w = b.w; val h = b.h; val bytes = b.emaskBytes
        var hit = 0; var total = 0
        for (i in 0 until 4) {
            val x0 = q[i * 2]; val y0 = q[i * 2 + 1]
            val x1 = q[((i + 1) % 4) * 2]; val y1 = q[((i + 1) % 4) * 2 + 1]
            val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
            val steps = max(2, (len / 1.5).toInt())
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val x = (x0 + (x1 - x0) * t).roundToInt()
                val y = (y0 + (y1 - y0) * t).roundToInt()
                total++
                if (x in 0 until w && y in 0 until h && bytes[y * w + x].toInt() != 0) hit++
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
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
    private fun refineCorners(bitmap: Bitmap, pts: FloatArray) {
        val w = bitmap.width; val h = bitmap.height
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
            var crop: Bitmap? = null
            val bag = MatBag()
            try {
                crop = Bitmap.createBitmap(bitmap, x0, y0, rw, rh)
                val rgba = bag.add(Cv.toRgba(crop))
                val g = bag.mat(); Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
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
                if (crop != null && crop !== bitmap) crop.recycle()
            }
        }
    }

    private fun confidenceOf(score: Double): Float = ((score - 0.45) / 0.4).coerceIn(0.0, 1.0).toFloat()

    companion object {
        /** Luminancia media por debajo de la cual se aplica CLAHE antes de buscar bordes. */
        private const val LOW_LIGHT_MEAN = 80.0

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

        internal fun toQuad(p: FloatArray) = Quad(Pt(p[0], p[1]), Pt(p[2], p[3]), Pt(p[4], p[5]), Pt(p[6], p[7]))
    }
}
