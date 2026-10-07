package com.scannerpromax.ocr

import android.graphics.Bitmap
import android.util.Log
import com.scannerpromax.NativeLibs
import com.scannerpromax.domain.FilterType
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pre-proceso de la imagen para el OCR (OpenCV, todo en gris de 8 bits para gastar poca memoria en gama baja).
 *  - [normalizeIllumination]: divide por el fondo estimado (cierre morfológico a baja resolución): quita sombras,
 *    viñeteado y papel amarillento de las fotos (filtros Original/Vívido/Aclarar) sin tocar la tinta.
 *  - [binarize]: variante de alto contraste (umbral adaptativo a la altura de letra) para el segundo intento.
 * Si OpenCV no está disponible devuelven null y el OCR usa la imagen tal cual.
 */
internal object OcrPreprocess {

    private const val TAG = "OcrPreprocess"

    /** Filtros cuya salida conserva la iluminación de la foto (sombras, degradados): conviene normalizar. */
    fun needsNormalization(filter: FilterType?): Boolean = when (filter) {
        null -> false
        FilterType.ORIGINAL, FilterType.VIVID, FilterType.LIGHTEN -> true
        else -> filter.name == "AUTO" // el modo automático puede dejar fotos casi sin tocar
    }

    fun normalizeIllumination(src: Bitmap): Bitmap? = withGray(src) { gray ->
        val norm = Mat()
        try {
            divideByBackground(gray, norm)
            stretch(norm)
            toBitmap(norm)
        } finally {
            norm.release()
        }
    }

    /** Binarización adaptativa; [lineHeightPx] = altura de línea típica en la imagen recibida (0 = desconocida). */
    fun binarize(src: Bitmap, lineHeightPx: Float): Bitmap? = withGray(src) { gray ->
        val norm = Mat()
        val bin = Mat()
        try {
            divideByBackground(gray, norm)
            val lh = if (lineHeightPx > 0f) lineHeightPx else max(src.width, src.height) / 80f
            var block = (lh * 1.6f).roundToInt().coerceIn(15, 101)
            if (block % 2 == 0) block++
            Imgproc.GaussianBlur(norm, norm, Size(3.0, 3.0), 0.0)
            Imgproc.adaptiveThreshold(norm, bin, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
            toBitmap(bin)
        } finally {
            norm.release()
            bin.release()
        }
    }

    /** Imagen en gris lista para ML Kit como NV21 (plano Y + croma 128). Dimensiones pares. */
    class Nv21Image(val nv21: ByteArray, val width: Int, val height: Int)

    /** Como [normalizeIllumination] pero devuelve NV21 (1.5 B/px en vez de 4 B/px de un Bitmap ARGB). */
    fun normalizeIlluminationNv21(src: Bitmap): Nv21Image? = withGrayAny(src) { gray ->
        val norm = Mat()
        try {
            divideByBackground(gray, norm)
            stretch(norm)
            toNv21(norm)
        } finally {
            norm.release()
        }
    }

    /**
     * Sólo gris (la página ya procesada: iluminación normalizada por el filtro) como NV21: el color de los
     * sombreados no distrae al reconocedor y ocupa 1.5 B/px. Para el OCR por mosaicos.
     */
    fun grayNv21(src: Bitmap): Nv21Image? = withGrayAny(src) { gray -> toNv21(gray) }

    /** Como [binarize] pero devuelve NV21. */
    fun binarizeNv21(src: Bitmap, lineHeightPx: Float): Nv21Image? = withGrayAny(src) { gray ->
        val norm = Mat()
        val bin = Mat()
        try {
            divideByBackground(gray, norm)
            val lh = if (lineHeightPx > 0f) lineHeightPx else max(src.width, src.height) / 80f
            var block = (lh * 1.6f).roundToInt().coerceIn(15, 101)
            if (block % 2 == 0) block++
            Imgproc.GaussianBlur(norm, norm, Size(3.0, 3.0), 0.0)
            Imgproc.adaptiveThreshold(norm, bin, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
            toNv21(bin)
        } finally {
            norm.release()
            bin.release()
        }
    }

    /** Plano Y (recortado a dimensiones pares, como exige NV21) seguido de croma neutro (128). */
    private fun toNv21(gray: Mat): Nv21Image? {
        val w = gray.cols() and 1.inv()
        val h = gray.rows() and 1.inv()
        if (w < 2 || h < 2) return null
        val ySize = w * h
        val out = ByteArray(ySize + ySize / 2)
        if (w == gray.cols() && gray.isContinuous) {
            val y = ByteArray(ySize)
            gray.get(0, 0, y)
            System.arraycopy(y, 0, out, 0, ySize)
        } else {
            val row = ByteArray(w)
            for (r in 0 until h) {
                gray.get(r, 0, row)
                System.arraycopy(row, 0, out, r * w, w)
            }
        }
        java.util.Arrays.fill(out, ySize, out.size, 128.toByte())
        return Nv21Image(out, w, h)
    }

    private inline fun <T : Any> withGrayAny(src: Bitmap, block: (Mat) -> T?): T? {
        if (!NativeLibs.ensureOpenCv()) return null
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            block(gray)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Sin memoria en el pre-proceso OCR", e)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Fallo en el pre-proceso OCR", t)
            null
        } finally {
            rgba.release()
            gray.release()
        }
    }

    private inline fun withGray(src: Bitmap, block: (Mat) -> Bitmap): Bitmap? {
        if (!NativeLibs.ensureOpenCv()) return null
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            block(gray)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Sin memoria en el pre-proceso OCR", e)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Fallo en el pre-proceso OCR", t)
            null
        } finally {
            rgba.release()
            gray.release()
        }
    }

    /** gray / fondo · 255, con el fondo estimado a ~1/8 de resolución (rápido en Cortex-A53). */
    private fun divideByBackground(gray: Mat, dst: Mat) {
        val small = Mat()
        val bg = Mat()
        try {
            val long = max(gray.cols(), gray.rows())
            val f = (320.0 / long).coerceAtMost(1.0)
            Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
            // Cierre (dilatar el blanco) con un núcleo mayor que el trazo: borra la tinta y deja el papel.
            val k = max(5, (long * f / 40.0).roundToInt()) or 1
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
            Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            Imgproc.medianBlur(small, small, 5)
            Imgproc.resize(small, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.divide(gray, bg, dst, 255.0, CvType.CV_8U)
        } finally {
            small.release()
            bg.release()
        }
    }

    /** Estira el contraste: percentil 1 % -> 0 y papel (percentil 90 %) -> 255. */
    private fun stretch(img: Mat) {
        val hist = IntArray(256)
        val buf = ByteArray(img.cols())
        val step = max(1, img.rows() / 400)
        var count = 0
        var r = 0
        while (r < img.rows()) {
            img.get(r, 0, buf)
            for (b in buf) hist[b.toInt() and 0xFF]++
            count += buf.size
            r += step
        }
        if (count == 0) return
        fun pct(p: Double): Int {
            var acc = 0
            val target = (count * p).toInt()
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
            return 255
        }
        val lo = pct(0.01)
        val hi = pct(0.90)
        if (hi - lo < 40) return
        val alpha = 255.0 / (hi - lo)
        img.convertTo(img, CvType.CV_8U, alpha, -lo * alpha)
    }

    private fun toBitmap(gray: Mat): Bitmap {
        val rgba = Mat()
        try {
            Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
            val bmp = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            return bmp
        } finally {
            rgba.release()
        }
    }
}
