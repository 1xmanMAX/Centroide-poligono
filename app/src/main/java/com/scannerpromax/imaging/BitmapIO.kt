package com.scannerpromax.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Carga/guardado de bitmaps cuidando la memoria (ImageDecoder/inSampleSize, EXIF, guardado atómico). */
object BitmapIO {

    /**
     * Decodifica [path] con a lo sumo [maxPixels] píxeles, ya rotado según EXIF.
     * - API 28+: ImageDecoder con setTargetSize (decodifica directamente al tamaño exacto y aplica EXIF:
     *   una sola copia en memoria).
     * - API < 28: inSampleSize (potencia de 2) + inDensity/inTargetDensity para el ajuste fino DURANTE la
     *   decodificación (sin copia escalada aparte) y rotación EXIF reciclando el intermedio.
     * Ante OutOfMemoryError en cualquier paso se reintenta con la mitad de píxeles.
     */
    fun decode(path: String, maxPixels: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw IOException("No se pudo leer la imagen: $path")
        var budget = if (maxPixels > 0) min(maxPixels.toLong(), w.toLong() * h) else w.toLong() * h
        var lastOom: OutOfMemoryError? = null
        repeat(4) {
            try {
                return decodeOnce(path, w, h, budget)
            } catch (oom: OutOfMemoryError) {
                lastOom = oom
                System.gc()
                budget = max(64L * 64L, budget / 2)
            }
        }
        throw IOException("Memoria insuficiente para abrir la imagen", lastOom)
    }

    private fun decodeOnce(path: String, w: Int, h: Int, limit: Long): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return decodeWithImageDecoder(path, limit)
            } catch (e: IOException) {
                // Formato no soportado por ImageDecoder: probar con BitmapFactory
            }
        }
        return decodeLegacy(path, w, h, limit)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(path: String, limit: Long): Bitmap {
        val source = ImageDecoder.createSource(File(path))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val iw = info.size.width; val ih = info.size.height
            val px = iw.toLong() * ih
            if (px > limit && iw > 0 && ih > 0) {
                // Escala uniforme sobre el tamaño que informa el propio decodificador (mismos ejes)
                val sc = sqrt(limit.toDouble() / px)
                decoder.setTargetSize(max(1, (iw * sc).toInt()), max(1, (ih * sc).toInt()))
            }
        }
    }

    private fun decodeLegacy(path: String, w: Int, h: Int, limit: Long): Bitmap {
        var sample = 1
        while ((w.toLong() / (sample * 2)) * (h.toLong() / (sample * 2)) >= limit) sample *= 2
        val sw = (w + sample - 1) / sample; val sh = (h + sample - 1) / sample
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val px = sw.toLong() * sh
        if (px > limit) {
            // Ajuste fino en la propia decodificación: escala = inTargetDensity / inDensity
            val sc = sqrt(limit.toDouble() / px)
            opts.inScaled = true
            opts.inDensity = sw
            opts.inTargetDensity = max(1, (sw * sc).toInt())
        }
        var bmp = BitmapFactory.decodeFile(path, opts) ?: throw IOException("Formato de imagen no soportado: $path")
        // Seguridad: si el decodificador ignoró la densidad, escalar aquí (reciclando el intermedio)
        val got = bmp.width.toLong() * bmp.height
        if (got > limit * 1.05) {
            val sc = sqrt(limit.toDouble() / got)
            val scaled = try {
                Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * sc).roundToInt()), max(1, (bmp.height * sc).roundToInt()), true)
            } catch (oom: OutOfMemoryError) { bmp.recycle(); throw oom }
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        // Orientación EXIF
        val matrix = exifMatrix(path)
        if (matrix != null) {
            val rotated = try {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            } catch (oom: OutOfMemoryError) { bmp.recycle(); throw oom }
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    /** Dimensiones (ancho, alto) tal como se verán, es decir, ya aplicada la orientación EXIF. */
    fun decodeBounds(path: String): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        val w = max(0, o.outWidth); val h = max(0, o.outHeight)
        return when (orientation(path)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> h to w
            else -> w to h
        }
    }

    /** Guarda en JPEG de forma atómica (archivo temporal + renombrado). */
    fun saveJpeg(bitmap: Bitmap, path: String, quality: Int = 92) {
        save(bitmap, path, Bitmap.CompressFormat.JPEG, quality)
    }

    /** Guarda en el formato indicado de forma atómica. */
    fun save(bitmap: Bitmap, path: String, format: Bitmap.CompressFormat, quality: Int) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        BufferedOutputStream(FileOutputStream(tmp), 64 * 1024).use { out ->
            if (!bitmap.compress(format, quality.coerceIn(1, 100), out)) throw IOException("No se pudo codificar la imagen")
            out.flush()
        }
        // rename(2) sustituye el destino de forma ATÓMICA dentro del mismo sistema de archivos: nunca hay un
        // instante sin archivo (importante al reescribir el original de una página, su única copia).
        if (tmp.renameTo(file)) return
        // Sólo si el renombrado directo falla: borrar y reintentar; como último recurso, copiar.
        if (file.exists() && !file.delete()) { tmp.delete(); throw IOException("No se pudo reemplazar $path") }
        if (!tmp.renameTo(file)) {
            try { tmp.copyTo(file, overwrite = true) } finally { tmp.delete() }
        }
    }

    /** Archivo temporal que usa [save] para [path] (empieza por '.', p. ej. para limpiezas). */
    fun tempFileFor(path: String): File { val f = File(path); return File(f.parentFile, ".${f.name}.tmp") }

    /** Borra los temporales '.*.tmp' huérfanos de [dir] (escrituras interrumpidas). Devuelve cuántos borró. */
    fun cleanupTempFiles(dir: File): Int {
        val list = dir.listFiles { f -> f.isFile && f.name.startsWith(".") && f.name.endsWith(".tmp") } ?: return 0
        var n = 0
        for (f in list) if (f.delete()) n++
        return n
    }

    /** Miniatura con lado largo <= [maxSide]. Siempre devuelve un Bitmap NUEVO (se puede reciclar). */
    fun thumbnail(src: Bitmap, maxSide: Int = 480): Bitmap {
        val long = max(src.width, src.height)
        if (long <= maxSide) return src.copy(Bitmap.Config.ARGB_8888, false)
        val s = maxSide.toDouble() / long
        val tw = max(1, (src.width * s).roundToInt()); val th = max(1, (src.height * s).roundToInt())
        // Reducción en dos pasos si es muy grande (mejor calidad que un único bilineal)
        if (s < 0.25) {
            val mid = Bitmap.createScaledBitmap(src, tw * 2, th * 2, true)
            val out = Bitmap.createScaledBitmap(mid, tw, th, true)
            if (mid !== src && mid !== out) mid.recycle()
            return out
        }
        val out = Bitmap.createScaledBitmap(src, tw, th, true)
        return if (out === src) src.copy(Bitmap.Config.ARGB_8888, false) else out
    }

    /** Decodifica directamente una miniatura desde archivo (sin cargar la imagen completa). */
    fun decodeThumbnail(path: String, maxSide: Int = 480): Bitmap {
        val (w, h) = decodeBounds(path)
        val budget = (maxSide.toLong() * maxSide * max(1, minOf(w, h)) / max(1, max(w, h))).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bmp = decode(path, max(1, budget))
        if (max(bmp.width, bmp.height) <= maxSide) return bmp
        val t = thumbnail(bmp, maxSide)
        bmp.recycle()
        return t
    }

    private fun orientation(path: String): Int = try {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun exifMatrix(path: String): Matrix? {
        val m = Matrix()
        when (orientation(path)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }
}
