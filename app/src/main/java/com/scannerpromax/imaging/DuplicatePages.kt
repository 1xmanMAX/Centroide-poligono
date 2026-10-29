package com.scannerpromax.imaging

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
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
 * Además, el duplicado seguro exige que el CONTENIDO coincida ([Match.similarity] ≥ [SIM_SURE]): las páginas distintas
 * de un mismo informe (membrete, logo, pie, firma, marcos de foto y pies de foto iguales y en el mismo sitio) daban
 * 40–460 inliers y se avisaban como duplicadas (fotos reales juliaca2: 11 pares falsos -> 0). Con la página B llevada
 * al marco de A, el detalle (paso banda) de las fotos y del texto distinto no se parece: plantillas ≤ 0,59, la misma
 * hoja 0,9. Banco i7 procesado por la app (30 + 45 pares): duplicados seguros 22 -> 14, 0 falsos; el resto pasa a la
 * zona gris (Jev, si está activo).
 */
object DuplicatePages {

    const val SURE = 40
    const val GRAY_LO = 12
    const val SIDE = 1000
    /** Lado de la miniatura con la que se compara el contenido ([similarity]) y paso banda (σ en px de la miniatura). */
    @Volatile internal var THUMB = 500
    @Volatile internal var SIM_HI = 2.0
    @Volatile internal var SIM_LO = 8.0
    /** Parecido del contenido mínimo para el duplicado seguro. */
    const val SIM_SURE = 0.6

    enum class Verdict { DIFFERENT, GRAY, DUPLICATE }

    fun verdict(inliers: Int): Verdict = when {
        inliers >= SURE -> Verdict.DUPLICATE
        inliers >= GRAY_LO -> Verdict.GRAY
        else -> Verdict.DIFFERENT
    }

    /** Puntos y descriptores de una página (gris 8UC1 a cualquier tamaño: se reduce a [SIDE]). Liberar con [release]. */
    class Features internal constructor(val points: List<Point>, val desc: Mat, internal val thumb: Mat? = null, internal val thumbScale: Double = 1.0) {
        val empty: Boolean get() = desc.empty() || points.isEmpty()
        fun release() { desc.release(); thumb?.release() }
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
            // miniatura para comparar el CONTENIDO de toda la página tras la homografía ([similarity])
            val th = Mat(); val ts = Cv.downscale(g, th, THUMB)
            return Features(pts, desc, th, ts)
        } finally { g.release() }
    }

    /**
     * Resultado del emparejamiento: [inliers] = coincidencias que cumplen la homografía; [similarity] = parecido del
     * contenido de toda la página con esa homografía ([similarity], −1..1; 0 si no se calcula).
     */
    class Match(val inliers: Int, val similarity: Double = 0.0)

    /** Veredicto con el contenido: muchas coincidencias de una plantilla común con contenido distinto -> zona gris. */
    fun verdict(m: Match): Verdict = when {
        m.inliers >= SURE && m.similarity >= SIM_SURE -> Verdict.DUPLICATE
        m.inliers >= GRAY_LO -> Verdict.GRAY
        else -> Verdict.DIFFERENT
    }

    /** Coincidencias ORB que cumplen una homografía RANSAC entre [a] y [b] (0 si no hay bastantes). */
    fun inliers(a: Features, b: Features): Int = match(a, b).inliers

    /** [inliers] y parecido del contenido ([Match]). */
    fun match(a: Features, b: Features): Match {
        if (a.empty || b.empty || a.desc.rows() < 2 || b.desc.rows() < 2) return Match(0)
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(a.desc, b.desc, knn, 2)
        val pa = ArrayList<Point>(); val pb = ArrayList<Point>()
        for (m in knn) {
            val d = m.toArray(); m.release()
            if (d.size == 2 && d[0].distance < 0.75f * d[1].distance) { pa += a.points[d[0].queryIdx]; pb += b.points[d[0].trainIdx] }
        }
        if (pa.size < 8) return Match(0)
        val ma = MatOfPoint2f(*pa.toTypedArray()); val mb = MatOfPoint2f(*pb.toTypedArray()); val mask = Mat()
        try {
            val h = Calib3d.findHomography(ma, mb, Calib3d.RANSAC, 8.0, mask)
            if (h.empty() || mask.empty()) { h.release(); return Match(0) }
            val n = Core.countNonZero(mask)
            val sim = if (n >= SURE) similarity(a, b, h) else 0.0
            h.release()
            return Match(n, sim)
        } finally { ma.release(); mb.release(); mask.release() }
    }

    /**
     * Parecido del CONTENIDO de toda la página: la miniatura de [b] llevada al marco de [a] con la homografía [h]
     * (A → B, px de los puntos) y correlación de Pearson con la de [a] en la zona común.
     */
    internal fun similarity(a: Features, b: Features, h: Mat): Double {
        val ta = a.thumb ?: return 0.0; val tb = b.thumb ?: return 0.0
        val saInv = Mat.eye(3, 3, CvType.CV_64F); saInv.put(0, 0, 1.0 / a.thumbScale); saInv.put(1, 1, 1.0 / a.thumbScale)
        val sb = Mat.eye(3, 3, CvType.CV_64F); sb.put(0, 0, b.thumbScale); sb.put(1, 1, b.thumbScale)
        val t1 = Mat(); val ht = Mat(); val wb = Mat(); val valid = Mat()
        val ones = Mat(tb.size(), CvType.CV_8UC1, Scalar(255.0))
        val ta32 = Mat(); val tb32 = Mat()
        val fa = Mat(); val fb = Mat(); val pr = Mat()
        try {
            Core.gemm(h, saInv, 1.0, Mat(), 0.0, t1)
            Core.gemm(sb, t1, 1.0, Mat(), 0.0, ht)          // miniatura A -> miniatura B
            Imgproc.warpPerspective(tb, wb, ht, ta.size(), Imgproc.INTER_AREA or Imgproc.WARP_INVERSE_MAP, Core.BORDER_CONSTANT, Scalar(0.0))
            Imgproc.warpPerspective(ones, valid, ht, ta.size(), Imgproc.INTER_NEAREST or Imgproc.WARP_INVERSE_MAP, Core.BORDER_CONSTANT, Scalar(0.0))
            Imgproc.erode(valid, valid, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(4 * SIM_LO + 1, 4 * SIM_LO + 1)))
            // ya en el mismo marco y escala: paso banda (la maquetación -rectángulos de foto, márgenes- no cuenta; el
            // detalle de letras y fotos, sí)
            if (Core.countNonZero(valid) < 0.3 * ta.total()) return 0.0
            bandPass(ta, ta32); bandPass(wb, tb32)
            val ma = MatOfDouble(); val sda = MatOfDouble(); val mb = MatOfDouble(); val sdb = MatOfDouble()
            Core.meanStdDev(ta32, ma, sda, valid); Core.meanStdDev(tb32, mb, sdb, valid)
            ta32.convertTo(fa, CvType.CV_32F, 1.0, -ma.get(0, 0)[0]); tb32.convertTo(fb, CvType.CV_32F, 1.0, -mb.get(0, 0)[0])
            Core.multiply(fa, fb, pr)
            val den = sda.get(0, 0)[0] * sdb.get(0, 0)[0]
            return if (den < 1e-6) 0.0 else Core.mean(pr, valid).`val`[0] / den
        } finally { listOf(saInv, sb, t1, ht, wb, valid, ones, fa, fb, pr, ta32, tb32).forEach { it.release() } }
    }

    private fun bandPass(src: Mat, dst: Mat) {
        val f = Mat(); src.convertTo(f, CvType.CV_32F)
        val hi = Mat(); val lo = Mat()
        Imgproc.GaussianBlur(f, hi, Size(0.0, 0.0), SIM_HI)
        if (SIM_LO > 0) { Imgproc.GaussianBlur(f, lo, Size(0.0, 0.0), SIM_LO); Core.subtract(hi, lo, dst) } else hi.copyTo(dst)
        f.release(); hi.release(); lo.release()
    }
}
