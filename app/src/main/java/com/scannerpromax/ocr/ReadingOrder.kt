package com.scannerpromax.ocr

import com.scannerpromax.domain.OcrBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Orden de lectura de bloques OCR (lógica pura, probada en JVM).
 *
 * Corte XY recursivo con preferencia por columnas:
 *  1. Si existe un "pasillo" vertical libre de bloques que recorre toda la región y a ambos lados hay texto de párrafo
 *     (bloques de 2+ líneas que ocupan buena parte de la altura), se separan columnas y se leen de izquierda a derecha.
 *  2. Si no, se busca un hueco horizontal (franja sin bloques) y se lee de arriba a abajo.
 *  3. Si no hay cortes posibles, filas por solape vertical y dentro de cada fila de izquierda a derecha
 *     (formularios "Etiqueta: valor" siguen leyéndose por filas).
 */
object ReadingOrder {

    fun sort(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        val heights = blocks.flatMap { b -> b.lines.map { it.box.bottom - it.box.top } }.filter { it > 0f }.sorted()
        val lineH = if (heights.isEmpty()) 12f else heights[heights.size / 2]
        val out = ArrayList<OcrBlock>(blocks.size)
        cut(blocks, lineH, out, 0)
        return out
    }

    private fun cut(region: List<OcrBlock>, lineH: Float, out: MutableList<OcrBlock>, depth: Int) {
        if (region.size < 2 || depth > 24) {
            out += rows(region)
            return
        }
        verticalSplit(region, lineH)?.let { split ->
            cut(split.left, lineH, out, depth + 1)
            cut(split.right, lineH, out, depth + 1)
            return
        }
        horizontalSplit(region, lineH)?.let { parts ->
            mergeColumnBands(parts, lineH).forEach { cut(it, lineH, out, depth + 1) }
            return
        }
        out += rows(region)
    }

    private class Split(val left: List<OcrBlock>, val right: List<OcrBlock>, val gutterStart: Float, val gutterEnd: Float)

    /**
     * Franjas horizontales consecutivas con el mismo pasillo de columnas (p. ej. los párrafos de ambas columnas
     * terminan a la misma altura) se vuelven a unir: así se lee toda la columna izquierda antes que la derecha.
     */
    private fun mergeColumnBands(parts: List<List<OcrBlock>>, lineH: Float): List<List<OcrBlock>> {
        val merged = ArrayList<List<OcrBlock>>(parts.size)
        var acc: List<OcrBlock> = parts[0]
        var accSplit = verticalSplit(acc, lineH, requireParagraph = false)
        for (i in 1 until parts.size) {
            val next = parts[i]
            val nextSplit = verticalSplit(next, lineH, requireParagraph = false)
            val a = accSplit
            val joined = if (a != null && nextSplit != null &&
                min(a.gutterEnd, nextSplit.gutterEnd) > max(a.gutterStart, nextSplit.gutterStart)
            ) acc + next else null
            // Solo se une si el conjunto forma columnas de párrafo de verdad (si no, se volvería a partir igual).
            val joinedSplit = joined?.let { verticalSplit(it, lineH) }
            if (joined != null && joinedSplit != null) {
                acc = joined
                accSplit = joinedSplit
            } else {
                merged += acc
                acc = next
                accSplit = nextSplit
            }
        }
        merged += acc
        return merged
    }

    /** Pasillo vertical más ancho que separa columnas de texto de párrafo. */
    private fun verticalSplit(region: List<OcrBlock>, lineH: Float, requireParagraph: Boolean = true): Split? {
        if (region.size < 2) return null
        val sorted = region.sortedBy { it.box.left }
        val top = region.minOf { it.box.top }
        val bottom = region.maxOf { it.box.bottom }
        val height = (bottom - top).coerceAtLeast(1f)
        var bestGap = 0f
        var bestIndex = -1
        var bestStart = 0f
        var reach = sorted[0].box.right
        for (i in 1 until sorted.size) {
            val gap = sorted[i].box.left - reach
            if (gap > bestGap) {
                bestGap = gap
                bestIndex = i
                bestStart = reach
            }
            reach = max(reach, sorted[i].box.right)
        }
        if (bestIndex <= 0 || bestGap < lineH * MIN_GUTTER_LINES) return null
        val left = sorted.subList(0, bestIndex)
        val right = sorted.subList(bestIndex, sorted.size)
        if (requireParagraph && (!isColumn(left, height) || !isColumn(right, height))) return null
        return Split(left, right, bestStart, bestStart + bestGap)
    }

    private fun isColumn(side: List<OcrBlock>, regionHeight: Float): Boolean {
        val paragraph = side.any { it.lines.size >= 2 }
        val covered = side.sumOf { (it.box.bottom - it.box.top).toDouble() }.toFloat()
        return paragraph && covered >= regionHeight * MIN_COLUMN_COVERAGE
    }

    /** Divide la región por todos los huecos horizontales (franjas sin texto) suficientemente altos. */
    private fun horizontalSplit(region: List<OcrBlock>, lineH: Float): List<List<OcrBlock>>? {
        val sorted = region.sortedBy { it.box.top }
        val parts = ArrayList<MutableList<OcrBlock>>()
        var current = mutableListOf(sorted[0])
        var reach = sorted[0].box.bottom
        for (i in 1 until sorted.size) {
            val b = sorted[i]
            if (b.box.top - reach > lineH * MIN_ROW_GAP_LINES) {
                parts += current
                current = mutableListOf()
            }
            current += b
            reach = max(reach, b.box.bottom)
        }
        parts += current
        return if (parts.size > 1) parts else null
    }

    /** Filas por solape vertical; cada fila de izquierda a derecha. */
    internal fun rows(blocks: List<OcrBlock>): List<OcrBlock> {
        val sorted = blocks.sortedBy { it.box.top }
        val rows = ArrayList<MutableList<OcrBlock>>()
        for (b in sorted) {
            val row = rows.lastOrNull()
            val ref = row?.first()
            val overlap = if (ref == null) 0f else {
                val t = max(ref.box.top, b.box.top)
                val btm = min(ref.box.bottom, b.box.bottom)
                val hMin = min(ref.box.bottom - ref.box.top, b.box.bottom - b.box.top).coerceAtLeast(1f)
                (btm - t) / hMin
            }
            if (row != null && overlap > 0.5f) row += b else rows += mutableListOf(b)
        }
        return rows.flatMap { r -> r.sortedBy { it.box.left } }
    }

    private const val MIN_GUTTER_LINES = 0.8f
    private const val MIN_ROW_GAP_LINES = 0.25f
    private const val MIN_COLUMN_COVERAGE = 0.4f
}
