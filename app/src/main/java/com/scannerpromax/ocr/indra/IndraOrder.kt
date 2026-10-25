package com.scannerpromax.ocr.indra

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * Orden de lectura y bloques de maquetación de Indra (puertos de order_post.py y layout_post.py): rasgos de línea para
 * order.onnx, decodificación del orden por pares, agrupación de líneas en bloques (enlace medio) y XY-cut de respaldo.
 * Cajas: FloatArray(4) x0, y0, x1, y1.
 */
object IndraOrder {
    val BLOCK_CLASSES = listOf(
        "Text", "Section-Header", "Page-Header", "Page-Footer", "List-Group", "Table", "Caption", "Footnote",
        "Table-Of-Contents", "Form",
    )
    val GRID = setOf(BLOCK_CLASSES.indexOf("Table"), BLOCK_CLASSES.indexOf("Form"), BLOCK_CLASSES.indexOf("Table-Of-Contents"))
    const val N_FEATS = 16
    private const val SENTENCE_END = ".!?:;。！？：；．"

    fun median(v: FloatArray): Float {
        if (v.isEmpty()) return 0f
        val s = v.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    private fun wide(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x20000..0x3FFFD

    fun displayLen(t: String): Int {
        var n = 0
        var i = 0
        while (i < t.length) { val cp = t.codePointAt(i); n += if (wide(cp)) 2 else 1; i += Character.charCount(cp) }
        return n
    }

    private fun isDigitStr(s: String) = s.isNotEmpty() && s.all { Character.isDigit(it) }

    /** [N, 16] rasgos (line_features). */
    fun lineFeatures(boxes: List<FloatArray>, pageW: Float, pageH: Float, texts: List<String>?): FloatArray {
        val n = boxes.size
        val out = FloatArray(n * N_FEATS)
        if (n == 0) return out
        val hs = FloatArray(n) { max(boxes[it][3] - boxes[it][1], 1e-3f) }
        val medH = median(hs)
        for (i in 0 until n) {
            val b = boxes[i]
            val w = max(b[2] - b[0], 1e-3f); val h = hs[i]
            val o = i * N_FEATS
            out[o] = b[0] / pageW; out[o + 1] = b[1] / pageH; out[o + 2] = b[2] / pageW; out[o + 3] = b[3] / pageH
            out[o + 4] = (b[0] + b[2]) / (2 * pageW); out[o + 5] = (b[1] + b[3]) / (2 * pageH)
            out[o + 6] = w / pageW
            out[o + 7] = ln(h / medH)
            out[o + 8] = ln(w / h) / 4f
            out[o + 9] = medH / pageH * 20f
            val t = texts?.get(i)?.trim() ?: continue
            if (t.isEmpty()) continue
            out[o + 10] = (ln1p(displayLen(t).toDouble()) / 5.0).toFloat()
            out[o + 11] = t.count { Character.isDigit(it) }.toFloat() / t.length
            val head = t.take(3).trimEnd('.', ')')
            out[o + 12] = if (t[0] in "•-–▪◦*·→" || (isDigitStr(head) && t.length > 3)) 1f else 0f
            out[o + 13] = if (t.endsWith("-")) 1f else 0f
            out[o + 14] = if (t.last() in SENTENCE_END) 1f else 0f
            out[o + 15] = if (Character.isUpperCase(t[0])) 1f else 0f
        }
        return out
    }

    /** Orden desde una matriz de logits por pares [n, n] (a[i, j] > 0: i antes que j) — decode_order. */
    fun decodeOrder(a: Array<DoubleArray>, sweeps: Int = 4): IntArray {
        val n = a.size
        if (n <= 1) return IntArray(n) { it }
        val lp = Array(n) { i -> DoubleArray(n) { j -> -softplus(-a[i][j]) } }
        val key = DoubleArray(n) { i -> var r = 0.0; var c = 0.0; for (j in 0 until n) { r += lp[i][j]; c += lp[j][i] }; -r + c }
        var order = (0 until n).sortedWith(compareBy<Int> { key[it] }.thenBy { it }).toIntArray()
        repeat(sweeps) {
            var improved = false
            for (x in order.copyOf()) {
                val rest = order.filter { it != x }.toIntArray()
                val r = rest.size
                val gains = DoubleArray(r + 1)
                var cum = 0.0
                val pre = DoubleArray(r + 1)
                for (k in 0 until r) { pre[k] = cum; cum += lp[rest[k]][x] }
                pre[r] = cum
                val suf = DoubleArray(r + 1)
                var s = 0.0
                for (k in r - 1 downTo 0) { s += lp[x][rest[k]]; suf[k] = s }
                suf[r] = 0.0
                var bestK = 0
                for (k in 0..r) { gains[k] = pre[k] + suf[k]; if (gains[k] > gains[bestK]) bestK = k }
                val cur = order.indexOf(x)
                if (gains[bestK] > gains[cur] + 1e-6) {
                    order = IntArray(r + 1).also { o ->
                        var p = 0
                        for (k in 0..r) { if (k == bestK) o[p++] = x; if (k < r) o[p++] = rest[k] }
                    }
                    improved = true
                }
            }
            if (!improved) return order
        }
        return order
    }

    private fun softplus(x: Double): Double = if (x > 0) x + ln1p(exp(-x)) else ln1p(exp(x))

    /** Filas de arriba abajo, de izquierda a derecha (order_top_down). */
    fun orderTopDown(boxes: List<FloatArray>): List<Int> {
        if (boxes.isEmpty()) return emptyList()
        val h = median(FloatArray(boxes.size) { boxes[it][3] - boxes[it][1] })
        val q = max(h * 0.5, 1e-3f).toDouble()
        return boxes.indices.sortedWith(compareBy<Int>({ Math.rint(((boxes[it][1] + boxes[it][3]) / 2) / q) }, { boxes[it][0] }))
    }

    private class Cut(val gap: Float, val k: Int, val o: List<Int>)

    private fun bestCut(b: List<FloatArray>, axis: Int): Cut {
        val lo = if (axis == 1) 1 else 0; val hi = if (axis == 1) 3 else 2
        val o = b.indices.sortedWith(compareBy<Int> { b[it][lo] }.thenBy { it })
        var end = b[o[0]][hi]
        var bestGap = 0f; var bestK = -1
        for (k in 1 until o.size) {
            val gap = b[o[k]][lo] - end
            if (gap > bestGap) { bestGap = gap; bestK = k }
            end = max(end, b[o[k]][hi])
        }
        return Cut(bestGap, bestK, o)
    }

    /** XY-cut recursivo (order_xycut). */
    fun orderXyCut(boxes: List<FloatArray>): List<Int> {
        fun rec(ids: List<Int>): List<Int> {
            if (ids.size <= 1) return ids
            val b = ids.map { boxes[it] }
            val cy = bestCut(b, 1); val cx = bestCut(b, 0)
            if (cy.k < 0 && cx.k < 0) return orderTopDown(b).map { ids[it] }
            val c = if (cy.gap >= cx.gap) cy else cx
            return rec(c.o.subList(0, c.k).map { ids[it] }) + rec(c.o.subList(c.k, c.o.size).map { ids[it] })
        }
        return rec(boxes.indices.toList())
    }

    /** Agrupación por enlace medio de logits "mismo grupo" (_cluster). */
    fun cluster(logits: Array<FloatArray>): List<List<Int>> {
        val n = logits.size
        if (n == 0) return emptyList()
        val S = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else logits[i][j].toDouble() } }
        val size = DoubleArray(n) { 1.0 }
        val alive = BooleanArray(n) { true }
        val members = Array(n) { mutableListOf(it) }
        val M = Array(n) { i -> DoubleArray(n) { j -> if (i == j) Double.NEGATIVE_INFINITY else S[i][j] } }
        val best = IntArray(n) { i -> argmax(M[i]) }
        val bestV = DoubleArray(n) { M[it][best[it]] }
        for (iter in 0 until n - 1) {
            var a = argmax(bestV)
            var b = best[a]
            if (bestV[a] <= 0) break
            val lo = min(a, b); val hi = max(a, b); a = lo; b = hi
            for (j in 0 until n) S[a][j] += S[b][j]
            for (j in 0 until n) S[j][a] += S[j][b]
            S[a][a] = 0.0
            size[a] += size[b]
            alive[b] = false
            members[a].addAll(members[b])
            val row = DoubleArray(n) { j -> if (!alive[j] || j == a) Double.NEGATIVE_INFINITY else S[a][j] / (size[a] * size[j]) }
            for (j in 0 until n) { M[a][j] = row[j]; M[j][a] = row[j] }
            for (j in 0 until n) { M[b][j] = Double.NEGATIVE_INFINITY; M[j][b] = Double.NEGATIVE_INFINITY }
            bestV[b] = Double.NEGATIVE_INFINITY
            best[a] = argmax(row)
            bestV[a] = row[best[a]]
            for (j in 0 until n) {
                if (!alive[j] || j == a) continue
                if (best[j] == a || best[j] == b) { best[j] = argmax(M[j]); bestV[j] = M[j][best[j]] }
            }
            for (j in 0 until n) {
                if (!alive[j] || j == a) continue
                if (row[j] > bestV[j]) { best[j] = a; bestV[j] = row[j] }
            }
        }
        return (0 until n).filter { alive[it] }.map { members[it].sorted() }
    }

    private fun argmax(v: DoubleArray): Int {
        var k = 0
        for (i in 1 until v.size) if (v[i] > v[k]) k = i
        return k
    }

    /** Orden con las cabezas de maquetación: bloques y luego líneas dentro (decode_blocks_order). */
    fun decodeBlocksOrder(a: Array<FloatArray>, same: Array<FloatArray>, boxes: List<FloatArray>, cls: Array<FloatArray>?): IntArray {
        val n = a.size
        if (n <= 1) return IntArray(n) { it }
        val blocks = cluster(same)
        val inner = blocks.map { b ->
            val bx = b.map { boxes[it] }
            val local = if (cls != null && blockClass(cls, b) in GRID) orderTopDown(bx) else orderXyCut(bx)
            local.map { b[it] }
        }
        val m = blocks.size
        val ab = Array(m) { DoubleArray(m) }
        for (p in 0 until m) for (q in p + 1 until m) {
            var s = 0.0
            for (i in blocks[p]) for (j in blocks[q]) s += a[i][j]
            val v = s / (blocks[p].size.toDouble() * blocks[q].size)
            ab[p][q] = v.toFloat().toDouble(); ab[q][p] = -v.toFloat().toDouble()
        }
        return decodeOrder(ab).flatMap { inner[it] }.toIntArray()
    }

    /** Clase de un bloque: argmax de la suma de logits de sus líneas. */
    fun blockClass(cls: Array<FloatArray>, block: List<Int>): Int {
        val c = cls[0].size
        val sum = DoubleArray(c)
        for (i in block) for (k in 0 until c) sum[k] += cls[i][k]
        return argmax(sum)
    }

    /** Tramos del orden de lectura con logit "mismo bloque" positivo entre vecinos (split_blocks). */
    fun splitBlocks(order: IntArray, same: Array<FloatArray>): List<List<Int>> {
        val out = ArrayList<MutableList<Int>>()
        for (i in order) {
            val last = out.lastOrNull()
            if (last != null && same[last.last()][i] > 0) last += i else out += mutableListOf(i)
        }
        return out
    }

    class Block(val label: String, val box: FloatArray, val lines: List<Int>)

    /** Bloques en orden de lectura (page_blocks): etiqueta, caja unión de sus líneas e índices de línea. */
    fun pageBlocks(boxes: List<FloatArray>, order: IntArray, cls: Array<FloatArray>, same: Array<FloatArray>): List<Block> =
        splitBlocks(order, same).map { blk ->
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in blk) { val b = boxes[i]; x0 = min(x0, b[0]); y0 = min(y0, b[1]); x1 = max(x1, b[2]); y1 = max(y1, b[3]) }
            Block(BLOCK_CLASSES[blockClass(cls, blk)], floatArrayOf(x0, y0, x1, y1), blk)
        }

    /** Bloques por XY-cut (sin modelo de orden): huecos de ≥ [minGap] alturas medianas (xycut_blocks). */
    fun xyCutBlocks(boxes: List<FloatArray>, minGap: Float = 1f): List<List<Int>> {
        if (boxes.isEmpty()) return emptyList()
        val h = median(FloatArray(boxes.size) { boxes[it][3] - boxes[it][1] })
        fun rec(ids: List<Int>): List<List<Int>> {
            val b = ids.map { boxes[it] }
            if (ids.size > 1) {
                val cy = bestCut(b, 1); val cx = bestCut(b, 0)
                val c = if (cy.gap >= cx.gap) cy else cx
                if (c.k >= 0 && c.gap >= minGap * h) return rec(c.o.subList(0, c.k).map { ids[it] }) + rec(c.o.subList(c.k, c.o.size).map { ids[it] })
            }
            return listOf(orderXyCut(b).map { ids[it] })
        }
        return rec(boxes.indices.toList())
    }

}
