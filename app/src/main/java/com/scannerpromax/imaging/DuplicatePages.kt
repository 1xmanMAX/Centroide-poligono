package com.scannerpromax.imaging

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import kotlin.math.max

/**
 * Páginas DUPLICADAS en un lote (la misma hoja capturada dos veces), en local y sin red: puntos ORB de las dos páginas
 * (a ~1000 px), emparejamiento con la prueba del cociente y homografía RANSAC; el número de coincidencias que la
 * cumplen (inliers) mide si son la misma hoja. Banco i7 (90 pares): ≥ [SURE] inliers = duplicado seguro (TPR 0,89,
 * FPR 0); < [GRAY_LO] = distintas; en medio, zona gris (plantillas iguales, misma hoja con otra luz): ahí decide Jev si
 * está activo ([com.scannerpromax.ai.jev.JevDuplicates]); sin Jev no se avisa.
 */
object DuplicatePages {

    const val SURE = 40
    const val GRAY_LO = 12
    const val SIDE = 1000

    enum class Verdict { DIFFERENT, GRAY, DUPLICATE }

    fun verdict(inliers: Int): Verdict = when {
        inliers >= SURE -> Verdict.DUPLICATE
        inliers >= GRAY_LO -> Verdict.GRAY
        else -> Verdict.DIFFERENT
    }

    /** Puntos y descriptores de una página (gris 8UC1 a cualquier tamaño: se reduce a [SIDE]). Liberar con [release]. */
    class Features internal constructor(val points: List<Point>, val desc: Mat) {
        val empty: Boolean get() = desc.empty() || points.isEmpty()
        fun release() = desc.release()
    }

    fun features(gray: Mat): Features {
        val g = Mat()
        try {
            if (max(gray.cols(), gray.rows()) > SIDE) Cv.downscale(gray, g, SIDE) else gray.copyTo(g)
            val orb = ORB.create(2000)
            val kp = MatOfKeyPoint(); val desc = Mat()
            orb.detectAndCompute(g, Mat(), kp, desc)
            val pts = kp.toArray().map { it.pt }
            kp.release()
            return Features(pts, desc)
        } finally { g.release() }
    }

    /** Coincidencias ORB que cumplen una homografía RANSAC entre [a] y [b] (0 si no hay bastantes). */
    fun inliers(a: Features, b: Features): Int {
        if (a.empty || b.empty || a.desc.rows() < 2 || b.desc.rows() < 2) return 0
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(a.desc, b.desc, knn, 2)
        val pa = ArrayList<Point>(); val pb = ArrayList<Point>()
        for (m in knn) {
            val d = m.toArray(); m.release()
            if (d.size == 2 && d[0].distance < 0.75f * d[1].distance) { pa += a.points[d[0].queryIdx]; pb += b.points[d[0].trainIdx] }
        }
        if (pa.size < 8) return 0
        val ma = MatOfPoint2f(*pa.toTypedArray()); val mb = MatOfPoint2f(*pb.toTypedArray()); val mask = Mat()
        try {
            val h = Calib3d.findHomography(ma, mb, Calib3d.RANSAC, 8.0, mask)
            val n = if (h.empty() || mask.empty()) 0 else org.opencv.core.Core.countNonZero(mask)
            h.release()
            return n
        } finally { ma.release(); mb.release(); mask.release() }
    }
}
