package com.scannerpromax.export

import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.ImageFormat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Qué se hace con el archivo PROCESADO de cada página al exportar (lógica pura, testeable en la JVM).
 *
 * Única fuente de verdad: el procesado a resolución completa que muestran el visor y las miniaturas. La exportación
 * nunca vuelve a procesar ni recorta: o copia los bytes tal cual (passthrough) o lo reduce CONSERVANDO LA PROPORCIÓN.
 */
object ExportPlan {

    /** Cómo se incrusta la imagen de una página en el PDF. */
    sealed class PdfImage {
        /** El JPEG procesado tal cual (mismos bytes, sin recomprimir). */
        object PassthroughJpeg : PdfImage()
        /** Sin pérdida a resolución completa (PNG de B/N en "Máxima"): mismos píxeles que el procesado. */
        object Lossless : PdfImage()
        /** 1 bit (B/N) a [width] x [height]. */
        data class Bilevel(val width: Int, val height: Int) : PdfImage()
        /** JPEG recodificado a [width] x [height] con calidad [quality]. */
        data class Jpeg(val width: Int, val height: Int, val quality: Int) : PdfImage()
    }

    /** Lado largo mínimo de las imágenes de 1 bit (A4 a 300 ppp): reducir más deja el texto dentado. */
    const val BINARY_MIN_LONG_SIDE = 3508

    /**
     * Plan para la página: [binary] = página binaria (B/N, Ahorro tinta); [isJpeg] = el procesado es JPEG.
     *  - "Máxima (HD)": passthrough del JPEG o, si es PNG (B/N), sin pérdida: idéntico al procesado.
     *  - Resto: JPEG passthrough si no hay que reducir y la calidad es alta; si no, reducción con la misma proporción.
     */
    fun pdfImage(srcW: Int, srcH: Int, quality: ExportQuality, binary: Boolean, isJpeg: Boolean): PdfImage {
        require(srcW > 0 && srcH > 0) { "Imagen vacía" }
        val long = max(srcW, srcH)
        val needsResize = !quality.isFullResolution && long > quality.maxLongSide
        if (quality.isFullResolution) {
            return if (isJpeg) PdfImage.PassthroughJpeg else PdfImage.Lossless
        }
        if (binary) {
            val target = min(long, max(quality.maxLongSide, BINARY_MIN_LONG_SIDE))
            val (w, h) = scaledSize(srcW, srcH, target)
            return PdfImage.Bilevel(w, h)
        }
        if (!needsResize && quality.jpegQuality >= 90 && isJpeg) return PdfImage.PassthroughJpeg
        val (w, h) = scaledSize(srcW, srcH, quality.maxLongSide)
        return PdfImage.Jpeg(w, h, quality.jpegQuality)
    }

    /**
     * ¿Se copia el archivo de imagen tal cual? Cuando no hay que reducirlo y el formato coincide (JPEG procesado ->
     * JPG en "Alta"/"Máxima", PNG de B/N -> PNG). WEBP siempre se codifica.
     */
    fun imagePassthrough(w: Int, h: Int, quality: ExportQuality, format: ImageFormat, isJpeg: Boolean, isPng: Boolean): Boolean {
        val fits = quality.isFullResolution || max(w, h) <= quality.maxLongSide
        return fits && when (format) {
            ImageFormat.JPEG -> (quality.isFullResolution || quality.jpegQuality >= 90) && isJpeg
            ImageFormat.PNG -> isPng
            ImageFormat.WEBP -> false
        }
    }

    /**
     * Tamaño reducido con lado largo <= [maxLongSide] CONSERVANDO LA PROPORCIÓN: el lado largo se fija y el corto se
     * deriva de él (redondeado), así el error de proporción es < 1 px. Nunca amplía.
     */
    fun scaledSize(w: Int, h: Int, maxLongSide: Int): Pair<Int, Int> {
        require(w > 0 && h > 0) { "Imagen vacía" }
        val long = max(w, h)
        if (maxLongSide <= 0 || long <= maxLongSide) return w to h
        return if (w >= h) {
            maxLongSide to (h.toDouble() * maxLongSide / w).roundToInt().coerceAtLeast(1)
        } else {
            (w.toDouble() * maxLongSide / h).roundToInt().coerceAtLeast(1) to maxLongSide
        }
    }
}
