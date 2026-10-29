package com.scannerpromax.ai.jev

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ajuste de una imagen a los límites de Jev (que RECHAZA, no redimensiona): 280..1568 px por lado y
 * 78 400..2 458 624 px en total. Se reduce el lado largo a [maxSide] y, si un lado queda por debajo de 280, se amplía
 * (hasta donde lo permita [maxSide]) y se RELLENA de blanco a la derecha / abajo. [scale] = px enviados por px
 * originales; el contenido ocupa (0, 0)–([contentW], [contentH]) del lienzo [canvasW] x [canvasH].
 */
data class JevFit(val scale: Double, val contentW: Int, val contentH: Int, val canvasW: Int, val canvasH: Int) {

    /** Caja en fracciones del LIENZO enviado -> fracciones de la imagen ORIGINAL (recortada a 0..1). */
    fun toSource(b: JevBox): JevBox {
        val fx = canvasW.toDouble() / contentW; val fy = canvasH.toDouble() / contentH
        return JevBox((b.left * fx).coerceIn(0.0, 1.0), (b.top * fy).coerceIn(0.0, 1.0), (b.right * fx).coerceIn(0.0, 1.0), (b.bottom * fy).coerceIn(0.0, 1.0))
    }

    companion object {
        const val MIN_SIDE = 280
        const val MAX_SIDE = 1568
        const val MIN_PIXELS = 78_400L
        const val MAX_PIXELS = 2_458_624L

        fun of(w: Int, h: Int, maxSide: Int = 1024, minSide: Int = MIN_SIDE): JevFit {
            require(w > 0 && h > 0)
            val ms = maxSide.coerceIn(MIN_SIDE, MAX_SIDE)
            var s = min(1.0, ms.toDouble() / max(w, h))
            // lado corto bajo el mínimo: ampliar (sin pasar del máximo en el lado largo)
            if (min(w, h) * s < minSide) s = min(ms.toDouble() / max(w, h), minSide.toDouble() / min(w, h))
            val cw = max(1, (w * s).roundToInt()); val ch = max(1, (h * s).roundToInt())
            val W = max(cw, MIN_SIDE); val H = max(ch, MIN_SIDE)
            check(W <= MAX_SIDE && H <= MAX_SIDE && W.toLong() * H in MIN_PIXELS..MAX_PIXELS)
            return JevFit(s, cw, ch, W, H)
        }

        /** ¿Cumple [w] x [h] los límites de la API? */
        fun valid(w: Int, h: Int) = w in MIN_SIDE..MAX_SIDE && h in MIN_SIDE..MAX_SIDE && w.toLong() * h in MIN_PIXELS..MAX_PIXELS
    }
}

/** Imagen lista para enviar: JPEG ligero + su ajuste. */
class JevImage(val asset: JevAsset, val fit: JevFit)

object JevImages {

    /**
     * [src] (RGB/RGBA 8UC3/8UC4 o gris 8UC1) -> JPEG ligero dentro de los límites. La referencia es un hash del
     * JPEG con prefijo (única y estable: misma imagen = misma petición, útil para la caché).
     */
    fun encode(src: Mat, maxSide: Int = 1024, quality: Int = 75, minSide: Int = JevFit.MIN_SIDE, prefix: String = "img"): JevImage {
        val fit = JevFit.of(src.cols(), src.rows(), maxSide, minSide)
        val rgb = Mat()
        when (src.channels()) {
            4 -> Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2BGR)
            3 -> Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGB2BGR)
            else -> Imgproc.cvtColor(src, rgb, Imgproc.COLOR_GRAY2BGR)
        }
        try {
            val r = Mat()
            Imgproc.resize(rgb, r, Size(fit.contentW.toDouble(), fit.contentH.toDouble()), 0.0, 0.0,
                if (fit.scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
            val canvas = if (fit.canvasW != fit.contentW || fit.canvasH != fit.contentH) {
                val c = Mat(fit.canvasH, fit.canvasW, r.type(), Scalar.all(255.0))
                r.copyTo(c.submat(Rect(0, 0, fit.contentW, fit.contentH))); r.release(); c
            } else r
            val buf = MatOfByte()
            Imgcodecs.imencode(".jpg", canvas, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality))
            canvas.release()
            val bytes = buf.toArray(); buf.release()
            return JevImage(JevAsset(prefix + "-" + sha(bytes).take(16), bytes, "jpeg"), fit)
        } finally { rgb.release() }
    }

    /** Recorte [r] (px) de [src] con margen relativo [pad] (fracción de la altura del recorte), como [encode]. */
    fun encodeCrop(src: Mat, r: Rect, pad: Double, targetH: Int, maxSide: Int = 800, quality: Int = 85, prefix: String = "crop"): JevImage {
        val p = (pad * r.height).roundToInt()
        val x0 = max(0, r.x - p); val y0 = max(0, r.y - p)
        val x1 = min(src.cols(), r.x + r.width + p); val y1 = min(src.rows(), r.y + r.height + p)
        val sub = src.submat(Rect(x0, y0, max(1, x1 - x0), max(1, y1 - y0)))
        try {
            // altura de la palabra ~[targetH] px (legible) sin pasar de maxSide
            val k = min(targetH.toDouble() / max(1, y1 - y0), maxSide.toDouble() / max(1, x1 - x0))
            val scaled = Mat()
            Imgproc.resize(sub, scaled, Size(max(1.0, (x1 - x0) * k), max(1.0, (y1 - y0) * k)), 0.0, 0.0, if (k < 1) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
            return try { encode(scaled, maxSide, quality, prefix = prefix) } finally { scaled.release() }
        } finally { sub.release() }
    }

    fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
