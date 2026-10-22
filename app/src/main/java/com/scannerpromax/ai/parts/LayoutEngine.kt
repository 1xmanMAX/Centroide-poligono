package com.scannerpromax.ai.parts

import android.graphics.Bitmap
import android.util.Log
import com.scannerpromax.ai.ModelCatalog
import com.scannerpromax.ai.ModelManager
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modelos de maquetación en el dispositivo (descarga "Maquetación de páginas"): PP-DocLayoutV3 con ONNX Runtime y
 * PP-LCNet doc_ori con OpenCV DNN (ya en la app; el banco comprobó que OpenCV 4.10 lo carga: 18 ms). Se cargan al
 * primer uso y se liberan con [close] (la cola los suelta al terminar). Si el modelo no está, todo devuelve null y la
 * tubería sigue como antes.
 */
class LayoutEngine(private val models: ModelManager, private val threads: Int = 4) : Closeable {

    private var layout: PpLayoutOrt? = null
    private var orientation: Net? = null

    fun isReady(): Boolean = models.isReady(ModelCatalog.PAGE_LAYOUT)

    /** Orientación con voto de 5 recortes (null si no hay modelo o falla). */
    @Synchronized
    fun orientation(page: Bitmap): DocOrientation.Result? {
        val p = orientationProbs(page) ?: return null
        val best = (0 until 4).maxByOrNull { p[it] } ?: 0
        return DocOrientation.Result(best * 90, p[best])
    }

    /**
     * Probabilidades (voto de 5 recortes) de que la página TENGA un giro horario de 0/90/180/270; null si no hay modelo o
     * falla. La tubería normal las usa como apoyo de la orientación por renglones ([com.scannerpromax.imaging.PageProcessor.orientationModel]).
     */
    @Synchronized
    fun orientationProbs(page: Bitmap): FloatArray? {
        val f = models.storage.readyFile(ModelCatalog.PAGE_LAYOUT, ModelCatalog.ORIENTATION_FILE) ?: return null
        return try {
            val net = orientation ?: Dnn.readNetFromONNX(f.absolutePath).also {
                it.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV); it.setPreferableTarget(Dnn.DNN_TARGET_CPU)
                orientation = it
            }
            val img = toRgb(page, ORIENTATION_SIDE)
            DocOrientation.predictProbs(img) { chw ->
                val blob = Mat(intArrayOf(1, 3, 224, 224), CvType.CV_32F)
                try {
                    blob.put(intArrayOf(0, 0, 0, 0), chw)
                    net.setInput(blob)
                    val out = net.forward()
                    try {
                        val r = FloatArray(4)
                        out.reshape(1, 1).get(0, 0, r)
                        softmaxIfLogits(r)
                    } finally { out.release() }
                } finally { blob.release() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "orientación", t); null
        }
    }

    /** Regiones de maquetación de [img] (px de [img]); null si no hay modelo o falla. */
    @Synchronized
    fun regions(img: Rgb): List<PpLayout.Region>? {
        val f = models.storage.readyFile(ModelCatalog.PAGE_LAYOUT, ModelCatalog.LAYOUT_FILE) ?: return null
        // ONNX Runtime nativo descargado con el modelo: precargar ANTES de tocar ai.onnxruntime (ver OrtNative).
        if (!OrtNative.ensureLoaded(models.storage.nativeLibDir(ModelCatalog.PAGE_LAYOUT))) {
            Log.w(TAG, "maquetación no disponible: ${OrtNative.lastError}")
            return null
        }
        return try {
            val m = layout ?: PpLayoutOrt(f.absolutePath, threads).also { layout = it }
            val dets = m.detect(img, 0.3f)
            Log.i(TAG, "PP-DocLayoutV3: ${dets.size} detecciones en ${m.lastMs} ms")
            PpLayout.regions(dets)
        } catch (t: Throwable) {
            Log.w(TAG, "maquetación", t); null
        }
    }

    @Synchronized
    override fun close() {
        runCatching { layout?.close() }
        layout = null
        orientation = null
    }

    companion object {
        private const val TAG = "LayoutEngine"
        /** Lado largo de la imagen analizada (como el banco: páginas ≤ 1600 px). */
        const val ANALYSIS_SIDE = 1600
        private const val ORIENTATION_SIDE = ANALYSIS_SIDE

        /** El export ONNX de doc_ori ya trae softmax; si no (valores fuera de 0..1), se aplica. */
        private fun softmaxIfLogits(r: FloatArray): FloatArray {
            if (r.all { it in 0f..1f } && kotlin.math.abs(r.sum() - 1f) < 0.05f) return r
            val m = r.max(); val e = r.map { kotlin.math.exp((it - m).toDouble()) }; val s = e.sum()
            return FloatArray(r.size) { (e[it] / s).toFloat() }
        }

        /** Bitmap → [Rgb] con el lado largo ≤ [maxSide] (filtrado bilineal). */
        fun toRgb(src: Bitmap, maxSide: Int): Rgb {
            val s = min(1f, maxSide.toFloat() / max(src.width, src.height))
            val w = max(1, (src.width * s).roundToInt()); val h = max(1, (src.height * s).roundToInt())
            val b = if (s < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
            try {
                val px = IntArray(w * h)
                b.getPixels(px, 0, w, 0, 0, w, h)
                return Rgb(w, h, px)
            } finally {
                if (b !== src) b.recycle()
            }
        }
    }
}
