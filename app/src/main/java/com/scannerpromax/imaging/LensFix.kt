package com.scannerpromax.imaging

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.max

/**
 * Paso de LENTE sobre el archivo de la foto ORIGINAL, antes de que entre al flujo normal (detección del documento,
 * perspectiva, filtros). Corrige in situ el JPEG:
 *  1. aberración cromática lateral (si el ISP no la corrige en alta calidad);
 *  2. distorsión geométrica: con la calibración de Camera2 ([SensorLens], si el ISP no la corrigió ya) o, si no la hay,
 *     con el autocalibrado del propio documento ([LensSelfCal]).
 *
 * La corrección queda GRABADA en el original (con una marca EXIF [MARKER] y el modelo usado): reabrir, recortar de nuevo o
 * reprocesar la página parte siempre de la misma foto corregida, sin repetir la corrección ni depender del teléfono.
 * Si no hay nada apreciable que corregir el archivo no se toca (ni se recomprime).
 */
internal object LensFix {

    const val MARKER = "ESCANER-LENTE v1"
    private const val TAG = "LensFix"

    data class Options(
        val geometry: Boolean = true,
        /** Autocalibrado con el documento cuando no hay calibración (o la del teléfono no cuadra). */
        val selfCal: Boolean = true,
        val chroma: Boolean = true,
        /** Píxeles máximos que se pueden corregir en memoria (foto + salida); más grande -> no se toca. */
        val maxPixels: Int = 20_000_000,
    )

    data class Report(val geometry: String?, val chroma: Boolean, val ms: Long, val detail: String) {
        val changed: Boolean get() = geometry != null || chroma
    }

    /** ¿El archivo ya pasó por este paso? */
    fun isCorrected(file: File): Boolean = runCatching {
        ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_USER_COMMENT)?.startsWith(MARKER) == true
    }.getOrDefault(false)

    /**
     * Corrige [file] en su sitio. [lens] = calibración de la cámara con que se tomó (null = desconocida: galería, app de
     * cámara del sistema). Nunca lanza (salvo cancelación/OOM se registra y se deja la foto como estaba).
     */
    fun processFile(file: File, lens: SensorLens?, opt: Options = Options()): Report {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        if (!opt.geometry && !opt.chroma) return Report(null, false, 0, "desactivado")
        if (isCorrected(file)) return Report(null, false, ms(), "ya corregida")
        var img: Mat? = null
        try {
            val (bw, bh) = BitmapIO.decodeBounds(file.absolutePath)
            if (bw <= 0 || bh <= 0 || bw.toLong() * bh > opt.maxPixels) return Report(null, false, ms(), "tamaño ${bw}x$bh")
            val bytes = file.readBytes()
            val buf = MatOfByte(*bytes)
            // Píxeles tal como están en el archivo (sin la orientación EXIF: la calibración es del sensor)
            img = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
            buf.release()
            val src = img ?: return Report(null, false, ms(), "no se pudo leer")
            if (src.empty()) return Report(null, false, ms(), "no se pudo leer")
            val res = correct(src, lens, opt)
            if (res.out == null && !res.chroma) return Report(null, false, ms(), res.detail)
            val outMat = res.out ?: src
            val enc = MatOfByte()
            Imgcodecs.imencode(".jpg", outMat, enc, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 100))
            if (res.out != null) res.out.release()
            val tmp = File(file.parentFile, file.name + ".lente")
            tmp.writeBytes(enc.toArray())
            enc.release()
            copyExif(file, tmp, "$MARKER ${res.geometry ?: "geo=no"} ca=${if (res.chroma) "si" else "no"} ${res.detail}")
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            val r = Report(res.geometry, res.chroma, ms(), res.detail)
            Log.i(TAG, "Lente: ${r.geometry ?: "sin distorsión"} · CA ${if (r.chroma) "corregida" else "no"} · ${r.ms} ms · ${r.detail}")
            return r
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "No se pudo corregir la lente", t)
            return Report(null, false, ms(), "error: ${t.message}")
        } finally {
            img?.release()
        }
    }

    /** Resultado de [correct]: [out] = nueva imagen si hubo corrección geométrica; la de CA se aplica in situ. */
    class Result(val out: Mat?, val geometry: String?, val chroma: Boolean, val detail: String)

    /**
     * Corrección en memoria de [src] (BGR/RGB 8UC3, píxeles en la orientación del archivo). La aberración se corrige IN SITU
     * en [src]; la distorsión devuelve un Mat nuevo en [Result.out]. Público para el banco y los tests.
     */
    fun correct(src: Mat, lens: SensorLens?, opt: Options = Options()): Result {
        val w = src.cols(); val h = src.rows()
        val notes = ArrayList<String>()
        // 1) Modelo geométrico
        var map: LensMap? = null
        var how: String? = null
        var chromaAhead: Par.Deferred<LensCorrection.Chroma?>? = null
        if (opt.geometry) {
            if (lens != null && lens.ispCorrected) notes += "ISP corrige la distorsión"
            val meta = if (lens != null && !lens.ispCorrected) lens.toImage(w, h) else null
            if (lens != null && !lens.ispCorrected && meta == null) notes += "calibración no aplicable"
            if (meta != null) {
                val disp = meta.maxDisplacement()
                if (disp < 1.0) notes += "calibración despreciable (%.2f px)".format(disp)
                else when (val v = verify(src, meta)) {
                    false -> notes += "calibración rechazada por las rectas de la foto"
                    null -> if (lens?.trusted == true) { map = meta; how = "metadatos"; notes += "desplaz. %.1f px".format(disp) }
                        else notes += "calibración sin verificar (sin rectas)"
                    true -> { map = meta; how = "metadatos verificados"; notes += "desplaz. %.1f px".format(disp) }
                }
            }
            if (map == null && opt.selfCal && (lens == null || !lens.ispCorrected)) {
                // El autocalibrado da un modelo de división (centro = centro de la foto): la estimación de la aberración
                // cromática, que solo LEE la foto con ese mismo centro, se calcula a la vez ([Par.async]); se aplica después
                val wantCa = opt.chroma && lens?.ispChromaCorrected != true
                chromaAhead = if (wantCa) Par.async { runCatching { LensCorrection.chroma(src, (w - 1) / 2.0, (h - 1) / 2.0) }.getOrNull() } else null
                try {
                    LensSelfCal.estimate(src) { f -> notes += "autocal k=%.4f %s".format(f.k, f.reason) }?.let { map = it; how = "autocalibrado" }
                } catch (t: Throwable) {
                    chromaAhead?.join(); throw t   // (la estimación en paralelo aún lee la foto)
                }
            }
        }
        // 2) Aberración cromática (antes del remap: el modelo es radial alrededor del centro óptico de la foto)
        var ca = false
        if (opt.chroma && lens?.ispChromaCorrected != true) {
            val (cx, cy) = (map as? LensMap.Brown)?.let { it.cx to it.cy } ?: ((w - 1) / 2.0 to (h - 1) / 2.0)
            val ahead = chromaAhead
            val est = if (ahead != null && map !is LensMap.Brown) runCatching { ahead.await() }.getOrNull()
                else { ahead?.join(); runCatching { LensCorrection.chroma(src, cx, cy) }.getOrNull() }
            if (est != null) {
                ca = LensCorrection.fixChroma(src, est)
                notes += "CA %.2f px".format(est.fringe(w, h))
            }
        }
        val m = map ?: return Result(null, null, ca, notes.joinToString("; "))
        val fitted = m.withScale(m.fitScale())
        val out = LensCorrection.undistort(src, fitted)
        return Result(out, "$how ${fitted.describe()}", ca, notes.joinToString("; "))
    }

    /**
     * Comprueba la calibración del teléfono con las rectas de la propia foto: true = las endereza (o no las empeora), false =
     * las curva más (la foto ya venía corregida, o la calibración es de otra cámara física), null = sin rectas para juzgar.
     */
    internal fun verify(src: Mat, meta: LensMap): Boolean? = MatBag().use { bag ->
        val g = bag.add(Cv.gray(src))
        val small = bag.mat()
        val sc = Cv.downscale(g, small, LensSelfCal.WORK_SIDE)
        val ch = LensSelfCal.chains(small)
        if (ch.size < 4) return null
        val o = DoubleArray(2)
        fun cost(useMeta: Boolean): Double {
            var s = 0.0
            for (c in ch) {
                val u = if (!useMeta) c else FloatArray(c.size).also { u ->
                    for (i in 0 until c.size / 2) {
                        meta.undistort(c[2 * i] / sc, c[2 * i + 1] / sc, o)
                        u[2 * i] = (o[0] * sc).toFloat(); u[2 * i + 1] = (o[1] * sc).toFloat()
                    }
                }
                val r = LensSelfCal.residual(u, 0.0, small.cols() / 2.0, small.rows() / 2.0, 1.0)
                s += r * r * max(1.0, c.size / 2.0)
            }
            return s
        }
        val c0 = cost(false); val c1 = cost(true)
        LensCorrection.log?.invoke("lente: verificación metadatos coste %.3f -> %.3f".format(c0, c1))
        c1 <= c0 * 1.05
    }

    /** Copia las etiquetas EXIF útiles (orientación, fecha, cámara, exposición, GPS) de [from] a [to] y añade [comment]. */
    private fun copyExif(from: File, to: File, comment: String) {
        runCatching {
            val a = ExifInterface(from.absolutePath)
            val b = ExifInterface(to.absolutePath)
            for (t in EXIF_TAGS) a.getAttribute(t)?.let { b.setAttribute(t, it) }
            b.setAttribute(ExifInterface.TAG_USER_COMMENT, comment.take(500))
            b.saveAttributes()
        }.onFailure { Log.w(TAG, "EXIF no copiado", it) }
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )
}
