package com.scannerpromax.ai.parts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * PP-DocLayoutV3 con ONNX Runtime (API Java: la misma en Android y en la JVM de los tests). Admite el export oficial
 * de Paddle (entradas image/im_shape/scale_factor; salida N x 7) y el de transformers (pixel_values → logits,
 * pred_boxes, order_logits). Solo pide las salidas de cajas (las máscaras no se calculan si el grafo lo permite).
 */
class PpLayoutOrt(modelPath: String, threads: Int = 4) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })
    private val inputNames = session.inputNames.toList()
    private val outputNames = session.outputNames.toList()
    private val hf = "logits" in outputNames

    /** Última inferencia (ms), para el registro. */
    var lastMs: Long = 0
        private set

    /** Detecciones (px de [img]) con score ≥ [thr] en orden de lectura. */
    fun detect(img: Rgb, thr: Float = 0.3f): List<PpLayout.Detection> {
        val x = PpLayout.preprocess(img)
        val tensors = ArrayList<OnnxTensor>()
        try {
            val feed = HashMap<String, OnnxTensor>()
            for (n in inputNames) {
                val t = when {
                    n == "image" || n == "pixel_values" || n == "x" ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, PpLayout.SIZE.toLong(), PpLayout.SIZE.toLong()))
                    "shape" in n -> OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(PpLayout.SIZE.toFloat(), PpLayout.SIZE.toFloat())), longArrayOf(1, 2))
                    "scale" in n -> OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(PpLayout.SIZE.toFloat() / img.h, PpLayout.SIZE.toFloat() / img.w)), longArrayOf(1, 2))
                    else -> continue
                }
                tensors += t; feed[n] = t
            }
            val t0 = System.currentTimeMillis()
            val want = if (hf) setOf("logits", "pred_boxes", "order_logits") else setOf(outputNames[0])
            session.run(feed, want).use { res ->
                lastMs = System.currentTimeMillis() - t0
                return if (hf) {
                    val lg = res.get("logits").get() as OnnxTensor
                    val bx = res.get("pred_boxes").get() as OnnxTensor
                    val ol = res.get("order_logits").get() as OnnxTensor
                    val shape = lg.info.shape
                    val q = shape[1].toInt(); val c = shape[2].toInt()
                    PpLayout.fromLogits(floats(lg), floats(bx), floats(ol), q, c, img.w, img.h, thr)
                } else {
                    val out = res.get(0) as OnnxTensor
                    val shape = out.info.shape
                    val stride = shape.last().toInt()
                    PpLayout.fromPaddle(floats(out), stride, img.w, img.h, thr)
                }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    private fun floats(t: OnnxTensor): FloatArray {
        val b = t.floatBuffer
        val a = FloatArray(b.remaining())
        b.get(a)
        return a
    }

    override fun close() {
        session.close()
    }
}
