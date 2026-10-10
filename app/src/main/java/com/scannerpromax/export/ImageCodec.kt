package com.scannerpromax.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.scannerpromax.domain.ImageFormat
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Utilidades de decodificación/codificación para exportar (PDF e imágenes) cuidando la memoria:
 * inSampleSize + escalado exacto, presupuesto de píxeles según la memoria libre real del proceso.
 */
/** Decisión pura de 1 bit (testeable): umbral fijo, trama ordenada 4x4 para los grises claros aislados. */
internal object BinaryDither {
    /** Hasta este nivel un gris claro aislado se trama (por encima, papel: blanco). */
    const val LIGHT_MAX = 230
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun white(lum: Int, x: Int, y: Int, darkNear: Boolean, threshold: Int = 150): Boolean {
        if (lum < threshold) return false
        if (darkNear || lum >= LIGHT_MAX) return true
        // fracción de blanco = (lum - umbral) / (LIGHT_MAX - umbral): gris 195 -> ~56 % blanco (línea punteada clara)
        val f = (lum - threshold).toDouble() / (LIGHT_MAX - threshold)
        return f * 16 > BAYER[(y and 3) * 4 + (x and 3)] + 0.5
    }
}

internal object ImageCodec {

    /** Dimensiones del archivo sin decodificar píxeles. */
    fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    fun isJpeg(file: File): Boolean = try {
        file.inputStream().use { s -> s.read() == 0xFF && s.read() == 0xD8 }
    } catch (_: Throwable) {
        false
    }

    fun isPng(file: File): Boolean = try {
        file.inputStream().use { s -> s.read() == 0x89 && s.read() == 0x50 && s.read() == 0x4E && s.read() == 0x47 }
    } catch (_: Throwable) {
        false
    }

    /** Píxeles que podemos permitirnos decodificar ahora mismo (ARGB_8888, con margen para copias). */
    fun safePixelBudget(copies: Int = 3): Int {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val free = (rt.maxMemory() - used).coerceAtLeast(16L * 1024 * 1024)
        return (free / 4 / copies).coerceIn(2_000_000L, 40_000_000L).toInt()
    }

    /** Tamaño objetivo: lado largo <= [maxLongSide] y píxeles <= presupuesto. */
    fun targetSize(w: Int, h: Int, maxLongSide: Int, maxPixels: Int = safePixelBudget()): Pair<Int, Int> {
        var s = 1f
        val long = max(w, h)
        if (long > maxLongSide) s = maxLongSide.toFloat() / long
        val px = w.toDouble() * h * s * s
        if (px > maxPixels) s *= sqrt(maxPixels / px).toFloat()
        return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
    }

    /**
     * Decodifica [file] al tamaño exacto calculado por [targetSize]. Usa inSampleSize para no cargar
     * el archivo completo en memoria y luego un escalado bilineal de alta calidad.
     */
    fun decodeScaled(file: File, maxLongSide: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val (w, h) = bounds(file)
        require(w > 0 && h > 0) { "Imagen ilegible: ${file.name}" }
        val (tw, th) = targetSize(w, h, maxLongSide)
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("No se pudo decodificar ${file.name}")
        if (decoded.width == tw && decoded.height == th) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, tw, th, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /**
     * Analiza una muestra reducida de la imagen: ¿es prácticamente blanco y negro (texto binarizado)?
     * Si lo es, se puede guardar en 1 bit sin pérdida visible y ocupa muchísimo menos.
     */
    fun isNearlyBinary(file: File): Boolean {
        val (w, h) = bounds(file)
        if (w <= 0 || h <= 0) return false
        var sample = 1
        while (max(w, h) / (sample * 2) >= 500) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return false
        try {
            return isNearlyBinary(bmp)
        } finally {
            bmp.recycle()
        }
    }

    fun isNearlyBinary(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var extreme = 0L
        var colored = 0L
        var total = 0L
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (c in row) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val mx = max(r, max(g, b))
                val mn = minOf(r, g, b)
                if (mx - mn > 40) colored++
                val lum = (r * 77 + g * 150 + b * 29) shr 8
                if (lum < 60 || lum > 200) extreme++
                total++
            }
        }
        if (total == 0L) return false
        return colored.toDouble() / total < 0.004 && extreme.toDouble() / total > 0.985
    }

    /** Empaqueta el bitmap en 1 bit por píxel (1 = blanco), filas alineadas a byte, como espera PDF /DeviceGray /BPC 1. */
    /**
     * 1 bit por píxel (1 = blanco) para el PDF. Umbral fijo [threshold]; los grises CLAROS aislados (la cuadrícula o
     * los renglones conservados en gris claro, sin tinta oscura alrededor) se traman ([BinaryDither]) para que no
     * desaparezcan; los bordes suavizados de las letras (junto a tinta oscura) siguen con el umbral fijo.
     */
    fun packBits(bmp: Bitmap, out: OutputStream, threshold: Int = 150) {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        val packed = ByteArray((w + 7) / 8)
        fun lumRow(y: Int, dst: IntArray) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                dst[x] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            }
        }
        var prev = IntArray(w); var cur = IntArray(w); var next = IntArray(w)
        if (h > 0) lumRow(0, cur)
        for (y in 0 until h) {
            if (y + 1 < h) lumRow(y + 1, next)
            java.util.Arrays.fill(packed, 0)
            for (x in 0 until w) {
                var darkNear = false
                for (dx in -1..1) {
                    val xx = x + dx; if (xx < 0 || xx >= w) continue
                    if (cur[xx] < threshold || (y > 0 && prev[xx] < threshold) || (y + 1 < h && next[xx] < threshold)) { darkNear = true; break }
                }
                if (BinaryDither.white(cur[x], x, y, darkNear, threshold)) {
                    val i = x shr 3
                    packed[i] = (packed[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
            out.write(packed)
            val t = prev; prev = cur; cur = next; next = t
        }
    }

    @Suppress("DEPRECATION")
    fun compressFormat(format: ImageFormat, quality: Int): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else Bitmap.CompressFormat.WEBP
    }

    /** Copia un bitmap con alfa sobre fondo blanco (JPEG no admite transparencia). */
    fun flattenOnWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
