package com.scannerpromax.ai.parts

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Orientación de página con PP-LCNet_x1_0_doc_ori (ONNX 6,8 MB, Apache-2.0): pre-proceso y voto PUROS (JVM), puerto de
 * tools/recon2_orient.py del banco (voto de 5 recortes = página + 4 cuartos: 94/96 aciertos frente a ~86 % con uno).
 *
 * Etiqueta del modelo = giro HORARIO que TIENE la imagen (0/90/180/270); para enderezarla se gira ese ángulo en
 * sentido ANTIHORARIO. El modelo se ejecuta fuera (OpenCV DNN en la app, ORT en los tests) con [Runner].
 */
object DocOrientation {

    /** Ejecuta el modelo sobre un tensor 1x3x224x224 y devuelve las 4 probabilidades (0, 90, 180, 270). */
    fun interface Runner { fun run(chw: FloatArray): FloatArray }

    data class Result(val angle: Int, val confidence: Float)

    /** Umbral del banco con voto (cuaderno4 acertó con 0,41). */
    const val MIN_CONFIDENCE = 0.4f
    private val ANGLES = intArrayOf(0, 90, 180, 270)
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Página entera + 4 cuartos (x0, y0, x1, y1). */
    fun voteCrops(w: Int, h: Int): List<IntArray> {
        val hw = w / 2; val hh = h / 2
        val out = arrayListOf(intArrayOf(0, 0, w, h))
        for (y in intArrayOf(0, hh)) for (x in intArrayOf(0, hw)) out += intArrayOf(x, y, x + hw, y + hh)
        return out
    }

    /** Lado corto a 256 (bilineal, como cv2.INTER_LINEAR), recorte central 224, normalización ImageNet, CHW. */
    fun preprocess(img: Rgb, crop: IntArray): FloatArray {
        val cw = max(1, crop[2] - crop[0]); val ch = max(1, crop[3] - crop[1])
        val s = 256.0 / min(cw, ch)
        val nw = max(224, (cw * s).roundToInt()); val nh = max(224, (ch * s).roundToInt())
        val x0 = (nw - 224) / 2; val y0 = (nh - 224) / 2
        val sx = cw.toDouble() / nw; val sy = ch.toDouble() / nh
        val out = FloatArray(3 * 224 * 224)
        val plane = 224 * 224
        for (y in 0 until 224) {
            val fy = ((y + y0) + 0.5) * sy - 0.5
            val iy = floor(fy).toInt(); val ty = fy - iy
            val ya = (iy).coerceIn(0, ch - 1) + crop[1]; val yb = (iy + 1).coerceIn(0, ch - 1) + crop[1]
            for (x in 0 until 224) {
                val fx = ((x + x0) + 0.5) * sx - 0.5
                val ix = floor(fx).toInt(); val tx = fx - ix
                val xa = ix.coerceIn(0, cw - 1) + crop[0]; val xb = (ix + 1).coerceIn(0, cw - 1) + crop[0]
                val p00 = img.px[ya * img.w + xa]; val p01 = img.px[ya * img.w + xb]
                val p10 = img.px[yb * img.w + xa]; val p11 = img.px[yb * img.w + xb]
                for (c in 0 until 3) {
                    val sh = 16 - 8 * c
                    val v = ((p00 shr sh) and 0xFF) * (1 - tx) * (1 - ty) + ((p01 shr sh) and 0xFF) * tx * (1 - ty) +
                        ((p10 shr sh) and 0xFF) * (1 - tx) * ty + ((p11 shr sh) and 0xFF) * tx * ty
                    // cv2 redondea a uint8 tras el redimensionado
                    val u = v.roundToInt().coerceIn(0, 255)
                    out[c * plane + y * 224 + x] = (u / 255f - MEAN[c]) / STD[c]
                }
            }
        }
        return out
    }

    /** Voto de 5 recortes: media de probabilidades → (ángulo, confianza). */
    fun predict(img: Rgb, runner: Runner): Result {
        val p = FloatArray(4)
        val crops = voteCrops(img.w, img.h)
        for (c in crops) {
            val r = runner.run(preprocess(img, c))
            for (k in 0 until 4) p[k] += r.getOrElse(k) { 0f }
        }
        for (k in 0 until 4) p[k] /= crops.size
        val best = (0 until 4).maxByOrNull { p[it] } ?: 0
        return Result(ANGLES[best], p[best])
    }

    /** Giro antihorario a aplicar (0 si no hay que girar o la confianza no llega). */
    fun correction(r: Result, minConfidence: Float = MIN_CONFIDENCE): Int =
        if (r.angle != 0 && r.confidence >= minConfidence) r.angle else 0

    /** Giro HORARIO equivalente para las ediciones de la página (PageEdits.rotation es horario). */
    fun clockwise(ccw: Int): Int = (360 - ccw % 360) % 360
}
