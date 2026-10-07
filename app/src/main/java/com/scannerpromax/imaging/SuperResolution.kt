package com.scannerpromax.imaging

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Super-resolución x2 para cámaras de baja resolución.
 *
 * Modelo: FSRCNN x2 (Dong et al.) con pesos de Saafke/FSRCNN_Tensorflow (Apache-2.0), re-empaquetado en ONNX
 * (ver assets/models/README-FSRCNN.txt). Se ejecuta con OpenCV DNN sobre el canal Y (luminancia) en MOSAICOS
 * de tamaño fijo (192 px + 8 px de solape): la memoria de las activaciones queda acotada (~10 MB) sea cual sea
 * la imagen, y la red no se re-dimensiona entre mosaicos. La crominancia se amplía con bicúbico (es suave).
 * Gama baja: modelo pequeño (~1.5k MAC/píxel); resto: modelo completo (mejor, ~3x más lento).
 *
 * Si el modelo no se puede cargar, alternativa de calidad: Lanczos x2 + realce dirigido por bordes con
 * anti-halo (el realce sólo actúa sobre bordes y el resultado se limita al mínimo/máximo local).
 *
 * Contexto: [init] lo guarda (llamarlo desde Application/AppContainer); si no se llamó, se intenta obtener la
 * Application por reflexión y, si tampoco, se usa la alternativa sin modelo.
 */
object SuperResolution {

    private const val TAG = "SuperResolution"
    private const val TILE = 192
    private const val PAD = 8
    private const val MODEL_FULL = "models/fsrcnn_x2.onnx"
    private const val MODEL_SMALL = "models/fsrcnn_small_x2.onnx"
    private const val RETRY_MS = 60_000L

    @Volatile private var appContext: Context? = null
    private val lock = Any()
    private var net: Net? = null
    private var netName: String? = null

    /** Guarda el contexto de la aplicación (para leer el modelo de assets). Barato; idempotente. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** ¿Hay un modelo FSRCNN cargable? (lo carga si hace falta). */
    fun isModelAvailable(lowEnd: Boolean = false): Boolean = synchronized(lock) { obtainNet(lowEnd) != null }

    /** Libera la red (p. ej. con poca memoria). Se recarga sola en el siguiente uso. */
    fun release() = synchronized(lock) {
        net = null; netName = null
    }

    /** Amplía x2 un Bitmap (modelo o alternativa). Para usos sueltos; la tubería usa la variante Mat. */
    fun upscale2x(src: Bitmap, lowEnd: Boolean = true): Bitmap {
        val rgb = Cv.toRgb(src)
        try {
            val out = upscale2x(rgb, lowEnd, Int.MAX_VALUE)
            try { return Cv.toBitmap(out) } finally { out.release() }
        } finally { rgb.release() }
    }

    /**
     * Amplía x2 un Mat RGB 8UC3 (o gris 8UC1). Si el resultado supera [maxPixels], se reduce (INTER_AREA) hasta
     * caber: aun así gana nitidez frente a no ampliar. Devuelve Mat nuevo.
     */
    internal fun upscale2x(src: Mat, lowEnd: Boolean, maxPixels: Int): Mat {
        val out = MatBag().use { bag ->
            val color = src.channels() >= 3
            val y = bag.mat()
            val ycc = bag.mat()
            if (color) {
                Imgproc.cvtColor(src, ycc, Imgproc.COLOR_RGB2YCrCb)
                Core.extractChannel(ycc, y, 0)
            } else src.copyTo(y)
            val y2 = synchronized(lock) { obtainNet(lowEnd)?.let { n -> runCatching { runTiled(n, y) }.onFailure { if (Cv.isOutOfMemory(it)) throw it; Log.w(TAG, "Fallo DNN; se usa la alternativa", it) }.getOrNull() } }
                ?: fallbackLuma(y)
            bag.add(y2)
            if (!color) return@use y2.clone()
            val big = Size(y2.cols().toDouble(), y2.rows().toDouble())
            val c = bag.mat(); val cBig = bag.mat()
            val ycc2 = bag.add(Mat(y2.rows(), y2.cols(), CvType.CV_8UC3))
            Core.insertChannel(y2, ycc2, 0)
            for (ch in 1..2) {
                Core.extractChannel(ycc, c, ch)
                Imgproc.resize(c, cBig, big, 0.0, 0.0, Imgproc.INTER_CUBIC)
                Core.insertChannel(cBig, ycc2, ch)
            }
            val rgb = Mat()
            Imgproc.cvtColor(ycc2, rgb, Imgproc.COLOR_YCrCb2RGB)
            rgb
        }
        val px = out.total()
        if (maxPixels > 0 && px > maxPixels) {
            val s = sqrt(maxPixels.toDouble() / px)
            val r = Mat()
            Imgproc.resize(out, r, Size(max(1.0, (out.cols() * s).roundToInt().toDouble()), max(1.0, (out.rows() * s).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
            out.release()
            return r
        }
        return out
    }

    // =====================================================================================

    /**
     * Red para el tier pedido; si su modelo no carga, el otro modelo; si tampoco, null (alternativa Lanczos).
     * El fallo se recuerda POR MODELO y se reintenta pasado [RETRY_MS] (un OOM puntual leyendo assets en gama
     * baja ya no deja la sesión entera sin super-resolución).
     */
    private fun obtainNet(lowEnd: Boolean): Net? {
        val wanted = if (lowEnd) MODEL_SMALL else MODEL_FULL
        val other = if (lowEnd) MODEL_FULL else MODEL_SMALL
        net?.let { if (netName == wanted) return it }
        loadModel(wanted)?.let { return it }
        // El modelo alternativo: si ya está en memoria, se usa; si no, se intenta cargar.
        net?.let { if (netName == other) return it }
        return loadModel(other)
    }

    private val failedAt = HashMap<String, Long>()

    private fun loadModel(name: String): Net? {
        val ctx = appContext ?: return null // lo registra AppContainer vía ImageEnhancer.init()
        val now = android.os.SystemClock.elapsedRealtime()
        failedAt[name]?.let { if (now - it < RETRY_MS) return null }
        return try {
            // Se copia el asset UNA vez a la caché y OpenCV lo lee de disco: sin ByteArray + MatOfByte (dos
            // copias completas del modelo en el heap de Java y en nativo a la vez).
            val file = modelFile(ctx, name)
            val n = Dnn.readNetFromONNX(file.absolutePath)
            if (n.empty()) throw IllegalStateException("red vacía")
            n.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
            n.setPreferableTarget(Dnn.DNN_TARGET_CPU)
            net = n; netName = name
            failedAt.remove(name)
            n
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo cargar $name; se probará el otro modelo o Lanczos + realce de bordes", t)
            failedAt[name] = now
            null
        }
    }

    /** Copia (si falta o cambió de tamaño) el modelo de assets a cacheDir/models y devuelve el archivo. */
    private fun modelFile(ctx: Context, name: String): java.io.File {
        val dst = java.io.File(ctx.cacheDir, name)
        val expected = try { ctx.assets.openFd(name).use { it.length } } catch (_: Throwable) { -1L }
        // Tras actualizar la app el modelo podría cambiar: se vuelve a copiar si la copia es anterior a la instalación.
        val installed = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }
        if (dst.exists() && dst.length() > 0 && dst.lastModified() >= installed &&
            (expected < 0 || dst.length() == expected)
        ) return dst
        dst.parentFile?.mkdirs()
        val tmp = java.io.File(dst.parentFile, dst.name + ".tmp")
        ctx.assets.open(name).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw java.io.IOException("No se pudo guardar $name") }
        return dst
    }

    /** Inferencia por mosaicos de tamaño fijo sobre Y 8UC1. Devuelve Y x2 8UC1. */
    private fun runTiled(n: Net, y: Mat): Mat = MatBag().use { bag ->
        val h = y.rows(); val w = y.cols()
        val ph = (TILE - h % TILE) % TILE; val pw = (TILE - w % TILE) % TILE
        val padded = bag.mat()
        Core.copyMakeBorder(y, padded, PAD, PAD + ph, PAD, PAD + pw, Core.BORDER_REFLECT_101)
        val out = Mat(2 * h, 2 * w, CvType.CV_8UC1)
        val imgs = ArrayList<Mat>(1)
        val o8 = bag.mat()
        try {
            var ty = 0
            while (ty < h) {
                var tx = 0
                while (tx < w) {
                    val tile = padded.submat(ty, ty + TILE + 2 * PAD, tx, tx + TILE + 2 * PAD)
                    val blob = Dnn.blobFromImage(tile, 1.0 / 255.0)
                    tile.release()
                    n.setInput(blob)
                    val res = n.forward()
                    blob.release()
                    imgs.clear()
                    Dnn.imagesFromBlob(res, imgs)
                    res.release()
                    val sr = imgs[0]
                    val cw = min(2 * TILE, 2 * (w - tx)); val chh = min(2 * TILE, 2 * (h - ty))
                    val core = sr.submat(2 * PAD, 2 * PAD + chh, 2 * PAD, 2 * PAD + cw)
                    core.convertTo(o8, CvType.CV_8U, 255.0)
                    core.release()
                    for (m in imgs) m.release()
                    val dst = out.submat(2 * ty, 2 * ty + chh, 2 * tx, 2 * tx + cw)
                    o8.copyTo(dst); dst.release()
                    tx += TILE
                }
                ty += TILE
            }
            out
        } catch (t: Throwable) {
            out.release()
            throw t
        }
    }

    /**
     * Alternativa sin modelo: Lanczos x2 + realce dirigido por bordes con anti-halo.
     * peso = |gradiente| normalizado (sólo bordes reales; el papel liso y el ruido fino no se tocan),
     * detalle = Y - Gauss(Y), resultado limitado al [mín, máx] local 3x3 de la imagen ampliada (sin halos).
     */
    internal fun fallbackLuma(y: Mat): Mat = MatBag().use { bag ->
        val up = bag.mat()
        Imgproc.resize(y, up, Size(2.0 * y.cols(), 2.0 * y.rows()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)
        val f = bag.mat(); up.convertTo(f, CvType.CV_32F)
        val blur = bag.mat(); Imgproc.GaussianBlur(f, blur, Size(0.0, 0.0), 1.1)
        val detail = bag.mat(); Core.subtract(f, blur, detail)
        val gx = bag.mat(); val gy = bag.mat()
        Imgproc.Sobel(blur, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(blur, gy, CvType.CV_32F, 0, 1, 3)
        val mag = bag.mat(); Core.magnitude(gx, gy, mag)
        // Peso 0..1: arranca en ~12 niveles de gradiente (por encima del ruido) y satura en ~60
        Core.subtract(mag, Scalar(12.0), mag)
        Core.multiply(mag, Scalar(1.0 / 48.0), mag)
        Core.min(mag, Scalar(1.0), mag); Core.max(mag, Scalar(0.0), mag)
        Core.multiply(detail, mag, detail, 1.1)
        Core.add(f, detail, f)
        val lo = bag.mat(); val hi = bag.mat()
        Imgproc.erode(up, lo, Cv.kernel(Imgproc.MORPH_RECT, 3))
        Imgproc.dilate(up, hi, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val lof = bag.mat(); val hif = bag.mat()
        lo.convertTo(lof, CvType.CV_32F); hi.convertTo(hif, CvType.CV_32F)
        Core.max(f, lof, f); Core.min(f, hif, f)
        val out = Mat()
        f.convertTo(out, CvType.CV_8U)
        out
    }
}
