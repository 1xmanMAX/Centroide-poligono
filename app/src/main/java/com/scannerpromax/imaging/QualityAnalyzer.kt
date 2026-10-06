package com.scannerpromax.imaging

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Evaluación rápida de la captura (nitidez, brillo, reflejos dentro del documento) a ~640 px. */
object QualityAnalyzer {

    private const val WORK_SIDE = 640

    fun analyze(bitmap: Bitmap): QualityReport {
        val w = bitmap.width; val h = bitmap.height
        if (w < 8 || h < 8) return report(0.0, 0.0, 0.0)
        // Reducción sin aliasing (el bilineal x6 inflaba la varianza del Laplaciano con ruido de texto)
        val rgba = Cv.toRgbaScaled(bitmap, WORK_SIDE).first
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        try { return analyzeGray(g) } finally { g.release() }
    }

    // Buffers reutilizados entre frames de la cámara (evita un ByteArray y un Mat nuevos por frame)
    private val lumaLock = Any()
    private var lumaBytes = ByteArray(0)
    private var lumaFull: Mat? = null
    private var lumaRoi: Mat? = null
    private var lumaSmall: Mat? = null

    /**
     * Variante para la cámara en vivo a partir del plano Y (no asigna Bitmaps y reutiliza sus buffers).
     * Si el mismo frame ya pasó por [DocumentDetector.detectLive], es más barato [DocumentDetector.analyzeLastFrame].
     */
    fun analyzeLuma(yPlane: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): QualityReport {
        if (width < 8 || height < 8 || rowStride < width) return report(0.0, 0.0, 0.0)
        synchronized(lumaLock) {
            val need = rowStride * height
            if (lumaBytes.size != need) lumaBytes = ByteArray(need)
            val dup = yPlane.duplicate(); dup.rewind()
            dup.get(lumaBytes, 0, min(dup.remaining(), need))
            var full = lumaFull
            if (full == null || full.rows() != height || full.cols() != rowStride || lumaRoi?.cols() != width) {
                lumaRoi?.release(); full?.release()
                full = Mat(height, rowStride, CvType.CV_8UC1)
                lumaFull = full
                lumaRoi = full.submat(0, height, 0, width)
            }
            full.put(0, 0, lumaBytes)
            val small = lumaSmall ?: Mat().also { lumaSmall = it }
            Cv.downscale(lumaRoi!!, small, WORK_SIDE)
            return analyzeGray(small)
        }
    }

    /** Libera los buffers de [analyzeLuma] (al cerrar la cámara). */
    fun releaseLiveBuffers() {
        synchronized(lumaLock) {
            lumaRoi?.release(); lumaRoi = null
            lumaFull?.release(); lumaFull = null
            lumaSmall?.release(); lumaSmall = null
            lumaBytes = ByteArray(0)
        }
    }

    /**
     * [docPts] = esquinas del documento (x0,y0..x3,y3) en coordenadas de [g], o null. El reflejo se evalúa SÓLO
     * dentro del documento y como manchas compactas (ver [glareBlobs]); sin documento no se marca reflejo.
     */
    internal fun analyzeGray(g: Mat, docPts: FloatArray? = null): QualityReport {
        val lap = Mat()
        val mean = MatOfDouble(); val sd = MatOfDouble()
        try {
            Imgproc.Laplacian(g, lap, CvType.CV_16S, 3)
            Core.meanStdDev(lap, mean, sd)
            val variance = sd.toArray()[0].let { it * it }
            val brightness = Core.mean(g).`val`[0] / 255.0
            val (glare, hasGlare) = try { glareBlobs(g, docPts) } catch (_: Throwable) { 0.0 to false }
            return report(variance, brightness, glare, hasGlare)
        } finally {
            lap.release(); mean.release(); sd.release()
        }
    }

    /**
     * Reflejo especular = manchas quemadas (Y ≥ 250) DENTRO del documento, compactas (0,2 %..15 % del área del
     * documento) y rodeadas de papel claramente más oscuro (contraste ≥ 15 con un anillo alrededor). Una mesa
     * blanca, una ventana o una lámpara fuera de la hoja, o un papel uniformemente sobreexpuesto, no cuentan.
     * Devuelve (fracción del documento con reflejo, ¿hay reflejo?).
     */
    private fun glareBlobs(g: Mat, docPts: FloatArray?): Pair<Double, Boolean> = MatBag().use { bag ->
        val total = max(1.0, g.total().toDouble())
        val burned = bag.mat()
        Core.compare(g, Scalar(250.0), burned, Core.CMP_GE)
        if (docPts == null || docPts.size < 8) return@use Core.countNonZero(burned) / total to false
        val doc = bag.add(Mat.zeros(g.size(), CvType.CV_8UC1))
        val poly = bag.add(MatOfPoint(*Array(4) { Point(docPts[2 * it].toDouble(), docPts[2 * it + 1].toDouble()) }))
        Imgproc.fillPoly(doc, listOf(poly), Scalar(255.0))
        val docArea = Core.countNonZero(doc).toDouble()
        if (docArea < 0.05 * total) return@use 0.0 to false
        Core.bitwise_and(burned, doc, burned)
        val nb = Core.countNonZero(burned)
        if (nb < 0.002 * docArea) return@use nb / docArea to false
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val n = Imgproc.connectedComponentsWithStats(burned, labels, stats, cents, 8, CvType.CV_32S)
        val row = IntArray(5)
        val comps = (1 until n).map { i -> stats.get(i, 0, row); i to row.copyOf() }
            .filter { (_, r) -> r[4] >= 0.002 * docArea && r[4] <= 0.15 * docArea }
            .sortedByDescending { it.second[4] }
            .take(6)
        var valid = 0.0
        val comp = bag.mat(); val dil = bag.mat(); val ring = bag.mat()
        for ((i, r) in comps) {
            val rad = (sqrt(r[4].toDouble()) / 2).toInt().coerceIn(3, 12)
            val x0 = max(0, r[0] - rad); val y0 = max(0, r[1] - rad)
            val x1 = min(g.cols(), r[0] + r[2] + rad); val y1 = min(g.rows(), r[1] + r[3] + rad)
            val lab = labels.submat(y0, y1, x0, x1); val gr = g.submat(y0, y1, x0, x1); val dr = doc.submat(y0, y1, x0, x1)
            try {
                Core.compare(lab, Scalar(i.toDouble()), comp, Core.CMP_EQ)
                Imgproc.dilate(comp, dil, Cv.kernel(Imgproc.MORPH_ELLIPSE, 2 * rad + 1))
                Core.subtract(dil, comp, ring)
                Core.bitwise_and(ring, dr, ring)
                if (Core.countNonZero(ring) < 8) continue
                val around = Core.mean(gr, ring).`val`[0]
                if (around <= 250.0 - 15.0) valid += r[4]
            } finally { lab.release(); gr.release(); dr.release() }
        }
        valid / docArea to (valid > 0)
    }

    private fun report(lapVar: Double, brightness: Double, glare: Double, hasGlare: Boolean = false): QualityReport {
        // Varianza del Laplaciano a 640 px: < ~60 borroso, > ~600 muy nítido
        val sharp = (lapVar / 600.0).coerceIn(0.0, 1.0).toFloat()
        return QualityReport(
            sharpness = sharp,
            brightness = brightness.coerceIn(0.0, 1.0).toFloat(),
            glare = glare.coerceIn(0.0, 1.0).toFloat(),
            isBlurry = lapVar < 60.0,
            isTooDark = brightness < 0.22,
            hasGlare = hasGlare,
        )
    }
}
