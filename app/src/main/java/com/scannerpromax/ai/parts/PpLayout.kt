package com.scannerpromax.ai.parts

import com.scannerpromax.domain.BlockType
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-DocLayoutV3 (RT-DETR con orden de lectura, Apache-2.0): pre y post-proceso PUROS (JVM), portados de
 * tools/recon2_pplayout.py + recon2_new.py del banco recon2 (en DocLayNet: recall@0,5 88 %, precisión 84 %).
 *
 * Entrada: 800x800 RGB / 255 (redimensionado bicúbico sin mantener proporción, como el exportador oficial).
 * Dos formatos de salida admitidos:
 *  - export oficial de Paddle (PaddlePaddle/PP-DocLayoutV3_onnx): filas [clase, score, x0, y0, x1, y1, orden] en px de
 *    la imagen original (top-300 con sigmoide y orden ya calculados dentro del grafo);
 *  - export de transformers (logits / pred_boxes / order_logits): sigmoide, top-300 sobre (consulta, clase) y orden
 *    por votos de order_logits ([fromLogits], réplica exacta de PPDocLayoutV3ImageProcessor).
 * Después: umbral 0,4 (0,35 tabla/imagen/gráfico/fórmula/sello), sin fórmulas en línea ni números de fórmula, sin
 * duplicados contenidos, ordenadas por el orden de lectura del modelo ([regions]).
 */
object PpLayout {

    const val SIZE = 800

    val LABELS = listOf(
        "abstract", "algorithm", "aside_text", "chart", "content", "display_formula", "doc_title", "figure_title",
        "footer", "footer_image", "footnote", "formula_number", "header", "header_image", "image", "inline_formula",
        "number", "paragraph_title", "reference", "reference_content", "seal", "table", "text", "vertical_text",
        "vision_footnote",
    )

    /** Clase del detector → tipo de bloque (lo que no aparece es texto). */
    fun typeOf(label: String): BlockType = when (label) {
        "doc_title", "paragraph_title" -> BlockType.TITLE
        "header" -> BlockType.HEADER
        "footer", "number" -> BlockType.FOOTER
        "header_image", "footer_image", "image", "seal" -> BlockType.FIGURE
        "table" -> BlockType.TABLE
        "chart" -> BlockType.CHART
        "display_formula" -> BlockType.FORMULA
        else -> BlockType.TEXT
    }

    data class Detection(val label: String, val score: Float, val box: Box, val order: Int)

    /** Región de maquetación ya filtrada (px de la imagen analizada). */
    data class Region(val label: String, val type: BlockType, val score: Float, val box: Box, val order: Int)

    // ------------------------------------------------------------------------------------------ pre-proceso

    /** Tensor 1x3x800x800 (CHW, RGB / 255) de [img] con redimensionado bicúbico (cv2.INTER_CUBIC). */
    fun preprocess(img: Rgb): FloatArray {
        val out = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        val r = resizeCubic(img, SIZE, SIZE)
        for (i in 0 until plane) {
            val p = r[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[plane + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        return out
    }

    private fun cubicW(x: Double): Double {
        val a = -0.75
        val t = kotlin.math.abs(x)
        return when {
            t <= 1 -> ((a + 2) * t - (a + 3)) * t * t + 1
            t < 2 -> ((a * t - 5 * a) * t + 8 * a) * t - 4 * a
            else -> 0.0
        }
    }

    /** Redimensionado bicúbico como cv2.INTER_CUBIC (a = −0,75, bordes replicados, sin antialias). ARGB. */
    fun resizeCubic(img: Rgb, nw: Int, nh: Int): IntArray {
        val w = img.w; val h = img.h
        val sx = w.toDouble() / nw; val sy = h.toDouble() / nh
        val xi = IntArray(nw * 4); val xw = DoubleArray(nw * 4)
        for (x in 0 until nw) {
            val fx = (x + 0.5) * sx - 0.5; val x0 = floor(fx).toInt(); val t = fx - x0
            for (k in 0 until 4) { xi[x * 4 + k] = (x0 - 1 + k).coerceIn(0, w - 1); xw[x * 4 + k] = cubicW(t - (k - 1)) }
        }
        // pasada horizontal → 3 planos double
        val tr = DoubleArray(nw * h); val tg = DoubleArray(nw * h); val tb = DoubleArray(nw * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until nw) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (k in 0 until 4) {
                    val p = img.px[row + xi[x * 4 + k]]; val wt = xw[x * 4 + k]
                    r += ((p shr 16) and 0xFF) * wt; g += ((p shr 8) and 0xFF) * wt; b += (p and 0xFF) * wt
                }
                tr[y * nw + x] = r; tg[y * nw + x] = g; tb[y * nw + x] = b
            }
        }
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val fy = (y + 0.5) * sy - 0.5; val y0 = floor(fy).toInt(); val t = fy - y0
            val ys = IntArray(4) { (y0 - 1 + it).coerceIn(0, h - 1) }
            val ws = DoubleArray(4) { cubicW(t - (it - 1)) }
            for (x in 0 until nw) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (k in 0 until 4) {
                    val i = ys[k] * nw + x
                    r += tr[i] * ws[k]; g += tg[i] * ws[k]; b += tb[i] * ws[k]
                }
                out[y * nw + x] = (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ post-proceso

    /** Export oficial de Paddle: [rows] = N x [stride] (clase, score, x0, y0, x1, y1, orden, ...) en px de w x h. */
    fun fromPaddle(rows: FloatArray, stride: Int, w: Int, h: Int, thr: Float): List<Detection> {
        val out = ArrayList<Detection>()
        val n = rows.size / stride
        for (i in 0 until n) {
            val o = i * stride
            val cls = rows[o].toInt(); val score = rows[o + 1]
            if (score < thr || cls < 0 || cls >= LABELS.size) continue
            val box = floatArrayOf(max(0f, rows[o + 2]), max(0f, rows[o + 3]), min(w.toFloat(), rows[o + 4]), min(h.toFloat(), rows[o + 5]))
            val order = if (stride > 6) rows[o + 6].roundToInt() else i
            out += Detection(LABELS[cls], score, box, order)
        }
        return out.sortedBy { it.order }
    }

    private fun sig(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    /**
     * Export de transformers: [logits] Q x C, [boxes] Q x 4 (cx, cy, w, h relativos), [orderLogits] Q x Q.
     * Réplica de PPDocLayoutV3ImageProcessor.post_process_object_detection (sin máscaras).
     */
    fun fromLogits(logits: FloatArray, boxes: FloatArray, orderLogits: FloatArray, q: Int, c: Int, w: Int, h: Int, thr: Float): List<Detection> {
        // Orden de lectura: votos de la matriz de orden (triangular superior + complemento de la inferior traspuesta).
        val votes = DoubleArray(q)
        for (j in 0 until q) {
            var s = 0.0
            for (i in 0 until j) s += sig(orderLogits[i * q + j])           // triu(sc, 1).sum(0)
            for (i in j + 1 until q) s += 1.0 - sig(orderLogits[j * q + i])  // tril(1 − scᵀ, −1).sum(0)
            votes[j] = s
        }
        val ptr = (0 until q).sortedWith(compareBy({ votes[it] }, { it }))
        val order = IntArray(q)
        ptr.forEachIndexed { rank, idx -> order[idx] = rank }
        val flat = FloatArray(q * c) { sig(logits[it]) }
        val top = flat.indices.sortedWith(compareBy({ -flat[it] }, { it })).take(q)
        val out = ArrayList<Detection>()
        for (t in top) {
            if (flat[t] < thr) break
            val qi = t / c; val l = t % c
            val cx = boxes[qi * 4]; val cy = boxes[qi * 4 + 1]; val bw = boxes[qi * 4 + 2]; val bh = boxes[qi * 4 + 3]
            val b = floatArrayOf(max(0f, (cx - bw / 2) * w), max(0f, (cy - bh / 2) * h), min(w.toFloat(), (cx + bw / 2) * w), min(h.toFloat(), (cy + bh / 2) * h))
            if (l < LABELS.size) out += Detection(LABELS[l], flat[t], b, order[qi])
        }
        return out.sortedBy { it.order }
    }

    /** Umbral por clase (las regiones no textuales se detectan con menos confianza). */
    fun threshold(label: String): Float = if (label in LOW_THR) 0.35f else 0.4f
    private val LOW_THR = setOf("table", "image", "chart", "display_formula", "seal")

    /**
     * Regiones útiles: umbral por clase, sin fórmulas en línea/números de fórmula, sin duplicados (región contenida
     * > 80 % en otra mejor del mismo tipo, o texto dentro de tabla/figura/gráfico), en orden de lectura.
     */
    fun regions(dets: List<Detection>): List<Region> {
        val regs = dets.filter { it.label != "inline_formula" && it.label != "formula_number" && it.score >= threshold(it.label) }
            .map { Region(it.label, typeOf(it.label), it.score, it.box, it.order) }
            .sortedByDescending { it.score }
        val keep = ArrayList<Region>()
        for (r in regs) {
            val dup = keep.any { k ->
                val f = PageInk.insideFrac(r.box, k.box)
                f > 0.8f && (r.type == k.type || k.type in CONTAINERS && r.type in TEXTY)
            }
            if (!dup) keep += r
        }
        return keep.sortedBy { it.order }
    }

    private val CONTAINERS = setOf(BlockType.TABLE, BlockType.FIGURE, BlockType.CHART)
    private val TEXTY = setOf(BlockType.TEXT, BlockType.TITLE)

    /** Escala las cajas de detecciones hechas sobre otra resolución de la misma página. */
    fun scale(dets: List<Detection>, sx: Float, sy: Float): List<Detection> =
        dets.map { it.copy(box = floatArrayOf(it.box[0] * sx, it.box[1] * sy, it.box[2] * sx, it.box[3] * sy)) }
}
