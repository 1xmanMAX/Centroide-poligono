package com.scannerpromax.imaging

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Evaluación rápida de la captura (nitidez, brillo, reflejos) a ~640 px. */
object QualityAnalyzer {

    private const val WORK_SIDE = 640

    fun analyze(bitmap: Bitmap): QualityReport {
        val w = bitmap.width; val h = bitmap.height
        if (w < 8 || h < 8) return report(0.0, 0.0, 0.0)
        val s = min(1.0, WORK_SIDE.toDouble() / max(w, h))
        val small = if (s < 1.0) Bitmap.createScaledBitmap(bitmap, max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()), true) else bitmap
        val rgba = Cv.toRgba(small)
        if (small !== bitmap) small.recycle()
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        try { return analyzeGray(g) } finally { g.release() }
    }

    /** Variante para la cámara en vivo a partir del plano Y (no asigna Bitmaps). */
    fun analyzeLuma(yPlane: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): QualityReport {
        if (width < 8 || height < 8 || rowStride < width) return report(0.0, 0.0, 0.0)
        val need = rowStride * height
        val bytes = ByteArray(need)
        val dup = yPlane.duplicate(); dup.rewind()
        dup.get(bytes, 0, min(dup.remaining(), need))
        val full = Mat(height, rowStride, CvType.CV_8UC1)
        full.put(0, 0, bytes)
        val roi = full.submat(0, height, 0, width)
        val small = Mat()
        try {
            Cv.downscale(roi, small, WORK_SIDE)
            return analyzeGray(small)
        } finally {
            small.release(); roi.release(); full.release()
        }
    }

    internal fun analyzeGray(g: Mat): QualityReport {
        val lap = Mat()
        val mean = MatOfDouble(); val sd = MatOfDouble()
        try {
            Imgproc.Laplacian(g, lap, CvType.CV_16S, 3)
            Core.meanStdDev(lap, mean, sd)
            val variance = sd.toArray()[0].let { it * it }
            val brightness = Core.mean(g).`val`[0] / 255.0
            val burned = Mat()
            Core.compare(g, Scalar(250.0), burned, Core.CMP_GE)
            val glare = Core.countNonZero(burned).toDouble() / max(1.0, g.total().toDouble())
            burned.release()
            return report(variance, brightness, glare)
        } finally {
            lap.release(); mean.release(); sd.release()
        }
    }

    private fun report(lapVar: Double, brightness: Double, glare: Double): QualityReport {
        // Varianza del Laplaciano a 640 px: < ~60 borroso, > ~600 muy nítido
        val sharp = (lapVar / 600.0).coerceIn(0.0, 1.0).toFloat()
        return QualityReport(
            sharpness = sharp,
            brightness = brightness.coerceIn(0.0, 1.0).toFloat(),
            glare = glare.coerceIn(0.0, 1.0).toFloat(),
            isBlurry = lapVar < 60.0,
            isTooDark = brightness < 0.22,
            hasGlare = glare > 0.02,
        )
    }
}
