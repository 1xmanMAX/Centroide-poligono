package com.scannerpromax.imaging

import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Esquinas de una red (cuadrilátero TL,TR,BR,BL en px de la imagen de entrada) y su puntuación: el MÍNIMO de los picos
 * de los 4 mapas de calor (0..1). Lo usa [DocumentDetector] como árbitro del detector clásico.
 */
class NeuralQuad(val pts: FloatArray, val score: Float) {
    override fun toString() = "NeuralQuad(s=%.3f, %s)".format(java.util.Locale.ROOT, score, pts.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) })
}

/** Fuente de esquinas neuronales para [DocumentDetector.neural] (null = no disponible: el detector actúa como siempre). */
fun interface NeuralCornerSource {
    fun corners(src: NeuralCorners.Pixels): NeuralQuad?
}

/**
 * «Encuadre IA»: DocAligner lcnet100 (DocsaidLab, Apache-2.0; `assets/models/docaligner_lcnet100.onnx`, 4,8 MB) con
 * ONNX Runtime. Pre/postproceso reimplementados según el banco (github_alt, `scripts/models.py`: DocAlignerHeatmap +
 * Padded 15 %):
 *  1. marco negro del [PAD] (15 %) por lado — la hoja que toca el borde de la foto pasa a tener canto visible —;
 *  2. reducción BILINEAL (como cv2.resize INTER_LINEAR, sin conservar proporción) a 256×256, BGR/255, NCHW;
 *  3. salida 4×128×128 (ya con sigmoide): cada mapa se amplía ×4 (bilineal), se umbraliza a [THR] y la esquina es el
 *     centroide ponderado de la componente conexa más grande (sin componentes: el máximo);
 *  4. puntuación = mínimo de los 4 picos (máximos de los mapas originales).
 *
 * ORT no va en el APK: la `.so` llega con la descarga de Indra. Mientras no esté ([ortReady] = false) devuelve null y
 * el detector se comporta exactamente como sin la red. Sesión con 2 hilos, cargada al primer uso; [close] la libera
 * (poca memoria) y se recarga sola. Thread-safe (una inferencia a la vez).
 */
class NeuralCorners(
    /** Bytes del modelo ONNX (assets en la app; archivo en los tests), o null si no hay. */
    private val modelBytes: () -> ByteArray?,
    /** true si ONNX Runtime nativo está cargado (o se puede cargar ahora) SIN descargar nada. */
    private val ortReady: () -> Boolean,
    private val threads: Int = 2,
) : NeuralCornerSource, Closeable {

    /** Ajuste «Encuadre IA» (Ajustes › Escaneo). false = null siempre. */
    @Volatile var enabled = true

    @Volatile private var session: Session? = null
    @Volatile private var failed = false

    /** Último tiempo de inferencia (ms), para registro y banco. */
    @Volatile var lastMs = 0L
        private set

    @Synchronized
    override fun corners(src: Pixels): NeuralQuad? {
        if (!enabled || failed) return null
        val s = session ?: run {
            if (!ortReady()) return null
            val bytes = runCatching { modelBytes() }.getOrNull() ?: run { failed = true; return null }
            try {
                Session(bytes, threads).also { session = it }
            } catch (t: Throwable) {
                Log.w(TAG, "no se pudo cargar el modelo de encuadre", t)
                failed = true
                return null
            }
        }
        return try {
            val t0 = System.nanoTime()
            val pad = padFor(src.width, src.height)
            val x = preprocess(src, pad)
            val hm = s.run(x)
            val q = postprocess(hm, src.width, src.height, pad)
            lastMs = (System.nanoTime() - t0) / 1_000_000
            q
        } catch (t: Throwable) {
            Log.w(TAG, "inferencia de encuadre falló", t)
            null
        }
    }

    /** Libera la sesión (≈10 MB nativos); se recarga sola al siguiente uso. */
    @Synchronized
    override fun close() {
        runCatching { session?.close() }
        session = null
    }

    /** Tras (re)descargar ONNX Runtime: permite reintentar una carga fallida. */
    @Synchronized
    fun reset() { failed = false; close() }

    /** Sesión ORT aislada en su clase: nada de ai.onnxruntime se toca antes de [ortReady] (ver OrtNative). */
    private class Session(bytes: ByteArray, threads: Int) : Closeable {
        private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        private val sess: ai.onnxruntime.OrtSession = ai.onnxruntime.OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(threads)
            o.setInterOpNumThreads(1)
            o.setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(bytes, o)
        }
        private val inputName = sess.inputNames.first()

        fun run(x: FloatArray): FloatArray {
            val shape = longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
            ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(x), shape).use { t ->
                sess.run(mapOf(inputName to t)).use { r ->
                    val out = r[0] as ai.onnxruntime.OnnxTensor
                    val fb = out.floatBuffer
                    val a = FloatArray(fb.remaining()); fb.get(a)
                    require(a.size == 4 * HM * HM) { "salida inesperada ${a.size}" }
                    return a
                }
            }
        }

        override fun close() { sess.close() }
    }

    /** Imagen de entrada como filas ARGB (sin copiarla entera: la foto puede tener 12–16 MP). */
    abstract class Pixels(val width: Int, val height: Int) {
        /** Copia la fila [y] (0 ≤ y < height) en [dst] (tamaño ≥ width) como ARGB. */
        abstract fun row(y: Int, dst: IntArray)

        companion object {
            fun of(b: Bitmap): Pixels = object : Pixels(b.width, b.height) {
                override fun row(y: Int, dst: IntArray) = b.getPixels(dst, 0, width, 0, y, width, 1)
            }

            /** Mat 8UC3 ([bgr] = true si el orden es BGR, como imread) u 8UC4 RGBA. */
            fun of(m: Mat, bgr: Boolean = false): Pixels {
                val ch = m.channels()
                require(m.depth() == CvType.CV_8U && (ch == 3 || ch == 4)) { "Mat no soportado" }
                return object : Pixels(m.cols(), m.rows()) {
                    private val buf = ByteArray(m.cols() * ch)
                    override fun row(y: Int, dst: IntArray) {
                        m.get(y, 0, buf)
                        for (x in 0 until width) {
                            val i = x * ch
                            val c0 = buf[i].toInt() and 0xFF; val c1 = buf[i + 1].toInt() and 0xFF; val c2 = buf[i + 2].toInt() and 0xFF
                            dst[x] = if (bgr) (0xFF shl 24) or (c2 shl 16) or (c1 shl 8) or c0 else (0xFF shl 24) or (c0 shl 16) or (c1 shl 8) or c2
                        }
                    }
                }
            }
        }
    }

    /** Marco añadido por lado (px), como `int(round(W * 0.15))` en Python. */
    data class Pad(val x: Int, val y: Int)

    companion object {
        private const val TAG = "NeuralCorners"
        const val ASSET = "models/docaligner_lcnet100.onnx"
        const val SIZE = 256
        const val HM = 128
        const val PAD = 0.15
        const val THR = 0.3f
        private const val UP = 4

        fun padFor(w: Int, h: Int) = Pad(pyRound(w * PAD), pyRound(h * PAD))

        /** round() de Python 3 (mitades al par), para reproducir el marco del banco al píxel. */
        private fun pyRound(v: Double): Int {
            val f = floor(v); val d = v - f
            return when {
                d > 0.5 -> f.toInt() + 1
                d < 0.5 -> f.toInt()
                else -> if (f.toInt() % 2 == 0) f.toInt() else f.toInt() + 1
            }
        }

        /**
         * Entrada de la red: la imagen con marco negro [pad] reducida a 256×256 con interpolación bilineal de centros de
         * píxel (la de cv2.resize INTER_LINEAR, incluido el recorte en los bordes) y redondeo a 8 bits; BGR/255 NCHW.
         */
        fun preprocess(src: Pixels, pad: Pad): FloatArray {
            val w = src.width; val h = src.height
            val pw = w + 2 * pad.x; val ph = h + 2 * pad.y
            // Muestras horizontales (índice en la imagen con marco y peso) — iguales para todas las filas
            val xi = IntArray(SIZE); val xf = FloatArray(SIZE)
            coords(pw, xi, xf)
            val yi = IntArray(SIZE); val yf = FloatArray(SIZE)
            coords(ph, yi, yf)
            val out = FloatArray(3 * SIZE * SIZE)
            val plane = SIZE * SIZE
            val rowA = IntArray(w); val rowB = IntArray(w)
            var cachedA = Int.MIN_VALUE; var cachedB = Int.MIN_VALUE
            // Lee la fila [py] (coordenada con marco) en [dst]; false = fila del marco (negra)
            fun load(py: Int, which: Int): Boolean {
                val y = py - pad.y
                if (y < 0 || y >= h) return false
                if (which == 0) { if (cachedA != y) { src.row(y, rowA); cachedA = y } }
                else { if (cachedB != y) { src.row(y, rowB); cachedB = y } }
                return true
            }
            for (oy in 0 until SIZE) {
                val y0 = yi[oy]; val fy = yf[oy]; val y1 = min(y0 + 1, ph - 1)
                val hasA = load(y0, 0)
                val hasB = if (fy > 0f) load(y1, 1) else false
                for (ox in 0 until SIZE) {
                    val x0 = xi[ox]; val fx = xf[ox]; val x1 = min(x0 + 1, pw - 1)
                    var r = 0f; var g = 0f; var b = 0f
                    fun acc(row: IntArray, px: Int, wgt: Float) {
                        val x = px - pad.x
                        if (x < 0 || x >= w || wgt == 0f) return
                        val c = row[x]
                        r += ((c shr 16) and 0xFF) * wgt; g += ((c shr 8) and 0xFF) * wgt; b += (c and 0xFF) * wgt
                    }
                    if (hasA) { acc(rowA, x0, (1 - fx) * (1 - fy)); acc(rowA, x1, fx * (1 - fy)) }
                    if (hasB) { acc(rowB, x0, (1 - fx) * fy); acc(rowB, x1, fx * fy) }
                    val i = oy * SIZE + ox
                    out[i] = b.roundToInt().coerceIn(0, 255) / 255f
                    out[plane + i] = g.roundToInt().coerceIn(0, 255) / 255f
                    out[2 * plane + i] = r.roundToInt().coerceIn(0, 255) / 255f
                }
            }
            return out
        }

        /** Índice de la 1.ª muestra y peso de la 2.ª para reducir [n] px a [SIZE] (cv2: centros de píxel, borde recortado). */
        private fun coords(n: Int, idx: IntArray, frac: FloatArray) {
            val scale = n.toDouble() / SIZE
            for (d in 0 until SIZE) {
                val f = (d + 0.5) * scale - 0.5
                var s = floor(f).toInt(); var a = (f - s).toFloat()
                if (s < 0) { s = 0; a = 0f }
                if (s >= n - 1) { s = n - 1; a = 0f }
                idx[d] = s; frac[d] = a
            }
        }

        /**
         * Mapas de calor [hm] (4×128×128, TL,TR,BR,BL) → esquinas en px de la imagen original de [w]×[h] con marco [pad].
         * Necesita OpenCV (ampliación bilineal y componentes conexas, como el banco).
         */
        fun postprocess(hm: FloatArray, w: Int, h: Int, pad: Pad): NeuralQuad {
            val pw = (w + 2 * pad.x).toDouble(); val ph = (h + 2 * pad.y).toDouble()
            val n = HM * UP
            val pts = FloatArray(8)
            var score = Float.MAX_VALUE
            val src = Mat(HM, HM, CvType.CV_32F); val up = Mat(); val mask = Mat(); val lab = Mat(); val st = Mat(); val cen = Mat()
            try {
                val upData = FloatArray(n * n); val labData = IntArray(n * n)
                for (c in 0 until 4) {
                    val off = c * HM * HM
                    var peak = -Float.MAX_VALUE
                    for (i in 0 until HM * HM) peak = max(peak, hm[off + i])
                    score = min(score, peak)
                    src.put(0, 0, hm.copyOfRange(off, off + HM * HM))
                    Imgproc.resize(src, up, Size(n.toDouble(), n.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                    up.get(0, 0, upData)
                    Core.compare(up, Scalar(THR.toDouble()), mask, Core.CMP_GE)
                    val k = Imgproc.connectedComponentsWithStats(mask, lab, st, cen, 8, CvType.CV_32S)
                    var px: Double; var py: Double
                    if (k <= 1) {
                        var best = 0
                        for (i in 1 until n * n) if (upData[i] > upData[best]) best = i
                        px = (best % n).toDouble(); py = (best / n).toDouble()
                    } else {
                        var bestK = 1; var bestA = -1
                        for (j in 1 until k) {
                            val a = st.get(j, Imgproc.CC_STAT_AREA)[0].toInt()
                            if (a > bestA) { bestA = a; bestK = j }
                        }
                        lab.get(0, 0, labData)
                        var sw = 0.0; var sx = 0.0; var sy = 0.0
                        for (i in 0 until n * n) if (labData[i] == bestK) {
                            val v = upData[i].toDouble(); sw += v; sx += (i % n) * v; sy += (i / n) * v
                        }
                        px = sx / sw; py = sy / sw
                    }
                    pts[c * 2] = ((px + 0.5) / n * pw - pad.x).toFloat()
                    pts[c * 2 + 1] = ((py + 0.5) / n * ph - pad.y).toFloat()
                }
            } finally {
                src.release(); up.release(); mask.release(); lab.release(); st.release(); cen.release()
            }
            return NeuralQuad(pts, score)
        }
    }
}
