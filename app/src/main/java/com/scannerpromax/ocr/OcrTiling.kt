package com.scannerpromax.ocr

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrWord
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * OCR por MOSAICOS para letra pequeña y tablas densas (sin dependencias de Android: probado en la JVM).
 *
 * ML Kit reconoce mejor el texto a ~25-40 px de altura. Reducir una hoja densa entera a ~3000 px deja la letra en
 * 10-15 px (se pierden celdas y números); ampliarla entera no cabe en memoria. Se divide la página en mosaicos con
 * SOLAPE (>= ~3 alturas de línea: cada línea queda entera en al menos un mosaico), cada uno se amplía a la escala
 * buscada y se reconoce por separado, y los resultados (ya en coordenadas de la página) se fusionan:
 *  1. Duplicados en el solape (misma caja): se queda la línea que NO toca un borde interior de su mosaico (la otra
 *     puede estar cortada), y si no, la más larga y luego la más fiable.
 *  2. Línea larga partida entre dos mosaicos vecinos (misma altura, contiguas o solapadas, alguna tocando el borde
 *     interior): se unen sus palabras quitando las repetidas en el solape.
 *  3. Bloques rehechos por mosaico y bloque original (el orden de lectura los ordena después).
 */
internal object OcrTiling {

    /** Mosaico [x0, x1) x [y0, y1) en píxeles de la página. */
    data class Tile(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width: Int get() = x1 - x0
        val height: Int get() = y1 - y0
    }

    /**
     * Mosaicos que cubren [w]x[h] con lado <= [tileSide] (px de la página) y solape >= [overlap] entre vecinos.
     * Repartidos uniformemente (todos del mismo tamaño salvo redondeo). Una sola pieza si la página ya cabe.
     */
    fun plan(w: Int, h: Int, tileSide: Int, overlap: Int): List<Tile> {
        require(w > 0 && h > 0 && tileSide > 0)
        val ov = overlap.coerceIn(0, max(0, tileSide / 2))
        fun axis(len: Int): List<Pair<Int, Int>> {
            if (len <= tileSide) return listOf(0 to len)
            // n piezas de tamaño t con solape ov: n·t - (n-1)·ov >= len
            val n = ceil((len - ov).toDouble() / (tileSide - ov)).toInt().coerceAtLeast(2)
            val t = ceil((len + (n - 1).toDouble() * ov) / n).toInt().coerceAtMost(len)
            val step = (len - t).toDouble() / (n - 1)
            return (0 until n).map { i ->
                val a = if (i == n - 1) len - t else (i * step).toInt()
                a to min(len, a + t)
            }
        }
        val xs = axis(w); val ys = axis(h)
        return ys.flatMap { (y0, y1) -> xs.map { (x0, x1) -> Tile(x0, y0, x1, y1) } }
    }

    /** Desplaza un bloque (coordenadas del mosaico ya escaladas a px de la página) por ([dx], [dy]). */
    fun offset(b: OcrBlock, dx: Float, dy: Float): OcrBlock {
        fun r(o: OcrRect) = OcrRect(o.left + dx, o.top + dy, o.right + dx, o.bottom + dy)
        return b.copy(box = r(b.box), lines = b.lines.map { l ->
            l.copy(box = r(l.box), words = l.words.map { it.copy(box = r(it.box)) })
        })
    }

    private class Item(val line: OcrLine, val tile: Int, val block: Int, val order: Int) {
        var alive = true
        var merged: OcrLine = line
    }

    /**
     * Fusiona los resultados de cada mosaico ([parts]: mosaico + bloques en coordenadas de la PÁGINA [pageW]x[pageH]).
     */
    fun merge(parts: List<Pair<Tile, List<OcrBlock>>>, pageW: Int, pageH: Int): List<OcrBlock> {
        val tiles = parts.map { it.first }
        val items = ArrayList<Item>()
        var order = 0
        for ((ti, p) in parts.withIndex()) for ((bi, b) in p.second.withIndex()) for (l in b.lines) items.add(Item(l, ti, bi, order++))

        // Lados del mosaico que son INTERIORES (hay otro mosaico al otro lado), con un margen de ~media línea
        fun touches(it: Item, box: OcrRect = it.merged.box): BooleanArray {
            val t = tiles[it.tile]
            val m = max(3f, (box.bottom - box.top) * 0.5f)
            return booleanArrayOf(
                t.x0 > 0 && box.left <= t.x0 + m,
                t.y0 > 0 && box.top <= t.y0 + m,
                t.x1 < pageW && box.right >= t.x1 - m,
                t.y1 < pageH && box.bottom >= t.y1 - m,
            )
        }
        fun cut(it: Item) = touches(it).any { b -> b }

        // 1. Duplicados (la misma línea vista por dos mosaicos)
        for (i in items.indices) for (j in i + 1 until items.size) {
            val a = items[i]; val b = items[j]
            if (!a.alive || !b.alive || a.tile == b.tile) continue
            val ba = a.merged.box; val bb = b.merged.box
            if (overlapRatio(ba, bb) < 0.6f) continue
            // Cada una aporta texto que la otra no tiene (trozos de una línea larga que se solapan): lo resuelve la
            // unión (paso 2); aquí sólo si una queda casi dentro de la otra
            val uw = max(ba.right, bb.right) - min(ba.left, bb.left)
            if (uw > 1.1f * max(ba.right - ba.left, bb.right - bb.left)) continue
            val loser = pickLoser(a, b, cut(a), cut(b))
            loser.alive = false
        }
        // 2. Líneas partidas entre mosaicos vecinos (izquierda/derecha)
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in items.indices) for (j in items.indices) {
                if (i == j) continue
                val a = items[i]; val b = items[j]
                if (!a.alive || !b.alive || a.tile == b.tile) continue
                val la = a.merged; val lb = b.merged
                if (la.box.left > lb.box.left) continue          // a = la de la izquierda
                val ha = la.box.bottom - la.box.top; val hb = lb.box.bottom - lb.box.top
                if (ha <= 0f || hb <= 0f || max(ha, hb) > 1.7f * min(ha, hb)) continue
                val yOv = min(la.box.bottom, lb.box.bottom) - max(la.box.top, lb.box.top)
                if (yOv < 0.5f * min(ha, hb)) continue
                val gap = lb.box.left - la.box.right
                if (gap > 0.6f * min(ha, hb)) continue
                if (lb.box.right <= la.box.right) continue        // b dentro de a: ya era duplicado
                val ta = touches(a); val tb = touches(b)
                if (!ta[2] && !tb[0]) continue                    // ninguna está cortada por el borde interior
                a.merged = join(la, lb, tiles[a.tile], tiles[b.tile])
                b.alive = false
                changed = true
                break@loop
            }
        }
        // 3. Bloques por (mosaico, bloque original)
        val groups = LinkedHashMap<Pair<Int, Int>, MutableList<Item>>()
        for (it in items.filter { it.alive }.sortedBy { it.order }) groups.getOrPut(it.tile to it.block) { ArrayList() }.add(it)
        return groups.values.map { g ->
            val lines = g.map { it.merged }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
            OcrBlock(lines.joinToString("\n") { it.text }, union(lines.map { it.box }), lines)
        }
    }

    private fun letters(s: String) = s.count { it.isLetterOrDigit() }

    private fun pickLoser(a: Item, b: Item, cutA: Boolean, cutB: Boolean): Item {
        if (cutA != cutB) return if (cutA) a else b
        val na = letters(a.merged.text); val nb = letters(b.merged.text)
        if (na != nb) return if (na < nb) a else b
        return if (a.merged.confidence < b.merged.confidence) a else b
    }

    /** Une dos trozos de una misma línea ([l] a la izquierda): palabras de ambos sin las repetidas del solape. */
    private fun join(l: OcrLine, r: OcrLine, il: Tile, ir: Tile): OcrLine {
        val box = union(listOf(l.box, r.box))
        if (l.words.isEmpty() || r.words.isEmpty()) {
            // Sin palabras: texto de la izquierda + lo que la derecha añade más allá del final de la izquierda
            return l.copy(text = (l.text + " " + r.text).trim(), box = box, words = l.words + r.words)
        }
        val words = ArrayList<Pair<OcrWord, Tile>>()
        for (w in l.words) words.add(w to il)
        for (w in r.words) {
            val dup = words.indexOfFirst { (o, _) -> overlapRatio(o.box, w.box) >= 0.5f }
            if (dup < 0) { words.add(w to ir); continue }
            // Repetida en el solape: la que está más lejos del borde de su mosaico (no cortada) o la más larga
            val (o, oi) = words[dup]
            val keepNew = when {
                letters(w.text) != letters(o.text) -> letters(w.text) > letters(o.text)
                else -> edgeDistance(w.box, ir) > edgeDistance(o.box, oi)
            }
            if (keepNew) words[dup] = w to ir
        }
        words.sortBy { it.first.box.left }
        val ws = words.map { it.first }
        val conf = listOf(l.confidence, r.confidence).filter { it >= 0f }.let { if (it.isEmpty()) -1f else it.average().toFloat() }
        return OcrLine(ws.joinToString(" ") { it.text }, box, ws, l.angle, conf)
    }

    /** Distancia horizontal de [b] al borde más cercano del mosaico [t]. */
    private fun edgeDistance(b: OcrRect, t: Tile): Float = min(abs(b.left - t.x0), abs(t.x1 - b.right))

    fun overlapRatio(p: OcrRect, q: OcrRect): Float {
        val iw = min(p.right, q.right) - max(p.left, q.left)
        val ih = min(p.bottom, q.bottom) - max(p.top, q.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val m = min(area(p), area(q))
        return if (m <= 0f) 0f else iw * ih / m
    }

    private fun area(r: OcrRect) = max(0f, r.right - r.left) * max(0f, r.bottom - r.top)

    private fun union(rs: List<OcrRect>): OcrRect =
        if (rs.isEmpty()) OcrRect(0f, 0f, 0f, 0f)
        else OcrRect(rs.minOf { it.left }, rs.minOf { it.top }, rs.maxOf { it.right }, rs.maxOf { it.bottom })
}
