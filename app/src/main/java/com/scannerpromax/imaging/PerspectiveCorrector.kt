package com.scannerpromax.imaging

import android.graphics.Bitmap
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object PerspectiveCorrector {

    /** Límite por defecto del API público (el pipeline pasa el del tier). */
    private const val DEFAULT_MAX_PIXELS = 16_000_000

    /** Recorta y endereza [quad] a un rectángulo con la relación de aspecto real estimada. */
    fun warp(src: Bitmap, quad: Quad): Bitmap {
        val rgba = Cv.toRgba(src)
        try {
            val out = warpMat(rgba, quad, DEFAULT_MAX_PIXELS, 0, src.width, src.height)
            val bmp = Cv.toBitmap(out)
            out.release()
            return bmp
        } finally {
            rgba.release()
        }
    }

    /**
     * Tamaño (ancho, alto) en píxeles del documento rectificado.
     * Relación de aspecto por el método de Zhang & He ("Whiteboard scanning and image enhancement"):
     * se estima la focal a partir de la homografía del rectángulo; si es degenerado (casi frontal) o
     * inverosímil, se usa el promedio de lados opuestos.
     * [imgW]/[imgH] son las dimensiones de la imagen ORIGINAL (centro óptico supuesto en su centro).
     */
    fun estimateSize(quad: Quad, imgW: Int, imgH: Int): Pair<Double, Double> {
        val (tl, tr, br, bl) = listOf(quad.tl, quad.tr, quad.br, quad.bl)
        val wTop = d(tl, tr); val wBot = d(bl, br); val hL = d(tl, bl); val hR = d(tr, br)
        val w0 = max(wTop, wBot); val h0 = max(hL, hR)
        if (w0 < 1 || h0 < 1) return max(1.0, w0) to max(1.0, h0)
        val fallbackRatio = ((wTop + wBot) / 2) / max(1e-6, (hL + hR) / 2)
        // Zhang sólo cuando la perspectiva es apreciable y con tolerancia estrecha: en imágenes recortadas o
        // con zoom digital el centro óptico supuesto es falso y un error del 20-40% estiraría la hoja.
        val ratio0 = zhangRatio(tl, tr, bl, br, imgW, imgH)?.takeIf {
            it.isFinite() && it > 0 && abs(it / fallbackRatio - 1.0) < 0.18
        } ?: fallbackRatio
        val ratio = snapToKnownRatio(ratio0)
        return if (ratio >= w0 / h0) w0 to (w0 / ratio) else (h0 * ratio) to h0
    }

    /** Proporciones (lado largo / corto) de formatos habituales: A4/A5, Carta, Oficio, ID-1 (DNI/tarjeta). */
    private val KNOWN_RATIOS = doubleArrayOf(1.4142, 1.2941, 1.6471, 1.5858)

    /** Ajusta [ratio] (ancho/alto) al formato conocido más cercano si está a menos del 3.5 %. */
    internal fun snapToKnownRatio(ratio: Double): Double {
        if (!ratio.isFinite() || ratio <= 0) return ratio
        val portrait = ratio < 1.0
        val r = if (portrait) 1.0 / ratio else ratio
        var best = r; var bestErr = 0.035
        for (k in KNOWN_RATIOS) {
            val e = abs(r / k - 1.0)
            if (e < bestErr) { bestErr = e; best = k }
        }
        return if (portrait) 1.0 / best else best
    }

    private fun zhangRatio(tl: Pt, tr: Pt, bl: Pt, br: Pt, imgW: Int, imgH: Int): Double? {
        val u0 = imgW / 2.0; val v0 = imgH / 2.0
        val m1 = doubleArrayOf(tl.x - u0, tl.y - v0, 1.0)
        val m2 = doubleArrayOf(tr.x - u0, tr.y - v0, 1.0)
        val m3 = doubleArrayOf(bl.x - u0, bl.y - v0, 1.0)
        val m4 = doubleArrayOf(br.x - u0, br.y - v0, 1.0)
        val c14 = cross(m1, m4)
        val den2 = dot(cross(m2, m4), m3)
        val den3 = dot(cross(m3, m4), m2)
        if (abs(den2) < 1e-9 || abs(den3) < 1e-9) return null
        val k2 = dot(c14, m3) / den2
        val k3 = dot(c14, m2) / den3
        // Casi frontal: los lados opuestos son paralelos y la focal queda indeterminada (o poco fiable)
        if (abs(k2 - 1) < 0.05 && abs(k3 - 1) < 0.05) return null
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val longSide = max(imgW, imgH).toDouble()
        var f2 = Double.NaN
        if (abs(n2[2] * n3[2]) > 1e-12) f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        // Focal verosímil para cámaras de celular: 0.4..3 x lado largo; si no, suponer ~26 mm equivalente
        if (!f2.isFinite() || f2 <= 0 || sqrt(f2) < 0.4 * longSide || sqrt(f2) > 3.0 * longSide) {
            val f = 0.8 * longSide
            f2 = f * f
        }
        val num = (n2[0] * n2[0] + n2[1] * n2[1]) / f2 + n2[2] * n2[2]
        val den = (n3[0] * n3[0] + n3[1] * n3[1]) / f2 + n3[2] * n3[2]
        if (num <= 0 || den <= 0) return null
        return sqrt(num / den)
    }

    /**
     * Rectificación sobre Mat (RGBA, RGB o gris). Devuelve un Mat nuevo del mismo tipo.
     * [maxPixels] y [maxSide] (0 = sin límite) acotan la salida. [imgW]/[imgH] son las dimensiones de la
     * imagen original a la que se refieren las coordenadas del quad ANTES de escalar (para el centro óptico).
     */
    internal fun warpMat(src: Mat, quad: Quad, maxPixels: Int, maxSide: Int, imgW: Int = src.cols(), imgH: Int = src.rows()): Mat {
        val hg = homography(quad, maxPixels, maxSide, imgW, imgH)
        val m = Mat(3, 3, CvType.CV_64F); m.put(0, 0, *hg.m)
        val out = Mat(hg.height, hg.width, src.type())
        Imgproc.warpPerspective(src, out, m, Size(hg.width.toDouble(), hg.height.toDouble()), hg.interp, Core.BORDER_REPLICATE)
        m.release()
        return out
    }

    /** Homografía 3x3 (fila mayor) quad -> rectángulo [width]x[height] e interpolación recomendada. */
    internal class Homography(val m: DoubleArray, val width: Int, val height: Int, val interp: Int)

    /**
     * Calcula la homografía de rectificación sin aplicarla (para componerla con rotaciones y hacer un
     * único remuestreo a resolución completa). Mismos parámetros que [warpMat].
     */
    internal fun homography(quad: Quad, maxPixels: Int, maxSide: Int, imgW: Int, imgH: Int): Homography {
        val (ew, eh) = estimateSize(quad, imgW, imgH)
        var w = ew; var h = eh
        if (maxPixels > 0 && w * h > maxPixels) { val s = sqrt(maxPixels / (w * h)); w *= s; h *= s }
        if (maxSide > 0 && max(w, h) > maxSide) { val s = maxSide / max(w, h); w *= s; h *= s }
        val ow = max(8, w.roundToInt()); val oh = max(8, h.roundToInt())
        val srcPts = MatOfPoint2f(
            Point(quad.tl.x.toDouble(), quad.tl.y.toDouble()), Point(quad.tr.x.toDouble(), quad.tr.y.toDouble()),
            Point(quad.br.x.toDouble(), quad.br.y.toDouble()), Point(quad.bl.x.toDouble(), quad.bl.y.toDouble()),
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(ow - 1.0, 0.0), Point(ow - 1.0, oh - 1.0), Point(0.0, oh - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val arr = DoubleArray(9)
        m.get(0, 0, arr)
        srcPts.release(); dstPts.release(); m.release()
        // Si se reduce mucho, INTER_AREA no existe para warp: INTER_LINEAR evita el "ringing" del cúbico
        val shrink = (ew * eh) / (ow.toDouble() * oh)
        val interp = if (shrink > 2.5) Imgproc.INTER_LINEAR else Imgproc.INTER_CUBIC
        return Homography(arr, ow, oh, interp)
    }

    /** ¿El quad equivale (casi) a la imagen completa? Evita un warp inútil. */
    internal fun isFullFrame(quad: Quad, w: Int, h: Int, tol: Float = 1.5f): Boolean {
        val f = Quad.full(w, h)
        return quad.points().zip(f.points()).all { (a, b) -> abs(a.x - b.x) <= tol && abs(a.y - b.y) <= tol }
    }

    /** Escala un quad por (sx, sy). */
    internal fun scaleQuad(q: Quad, sx: Double, sy: Double): Quad = Quad.of(
        q.points().map { Pt((it.x * sx).toFloat(), (it.y * sy).toFloat()) },
    )

    /** Lados máximos del quad (ancho, alto) en píxeles. */
    internal fun quadExtent(q: Quad): Pair<Double, Double> =
        max(d(q.tl, q.tr), d(q.bl, q.br)) to max(d(q.tl, q.bl), d(q.tr, q.br))

    private fun d(a: Pt, b: Pt) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
