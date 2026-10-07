package com.scannerpromax.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * Lógica pura (sin OpenCV ni Android, probada con tests JVM) para agrupar las componentes de tinta en
 * recuadros de palabras / líneas / bloques.
 *
 * Una caja es [x0, x1) x [y0, y1) en píxeles.
 */
internal object BoxGrouping {

    data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w: Int get() = x1 - x0
        val h: Int get() = y1 - y0
        fun union(o: Box) = Box(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))
        fun pad(px: Int, maxW: Int, maxH: Int) =
            Box(max(0, x0 - px), max(0, y0 - px), min(maxW, x1 + px), min(maxH, y1 + px))
        fun overlaps(o: Box) = x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1
    }

    /** Unión-búsqueda con compresión de caminos. */
    private class Dsu(n: Int) {
        val p = IntArray(n) { it }
        fun find(a: Int): Int { var x = a; while (p[x] != x) { p[x] = p[p[x]]; x = p[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) p[max(ra, rb)] = min(ra, rb) }
    }

    /**
     * ¿Se unen dos componentes? Misma línea (solape vertical >= 30 % de la más baja) y separadas
     * horizontalmente <= [gapX]; o muy cerca en ambas direcciones (<= [gapY]: tildes, puntos de la i, trazos
     * partidos, letras de la línea siguiente pegadas).
     */
    fun linked(a: Box, b: Box, gapX: Int, gapY: Int): Boolean {
        val gx = max(0, max(a.x0, b.x0) - min(a.x1, b.x1))
        val gy = max(0, max(a.y0, b.y0) - min(a.y1, b.y1))
        if (gx <= gapY && gy <= gapY) return true
        if (gx > gapX) return false
        val ov = min(a.y1, b.y1) - max(a.y0, b.y0)
        return ov >= 0.3 * max(1, min(a.h, b.h))
    }

    /**
     * Agrupa [boxes] por enlace simple ([linked]). Devuelve, por cada grupo, la caja envolvente y los índices
     * de sus miembros. Orden de lectura aproximado (arriba-abajo, izquierda-derecha).
     */
    fun group(boxes: List<Box>, gapX: Int, gapY: Int): List<Pair<Box, IntArray>> {
        val n = boxes.size
        if (n == 0) return emptyList()
        val order = (0 until n).sortedBy { boxes[it].x0 }
        val dsu = Dsu(n)
        val reach = max(gapX, gapY)
        for (ii in order.indices) {
            val i = order[ii]; val a = boxes[i]
            var jj = ii + 1
            while (jj < order.size) {
                val j = order[jj]; val b = boxes[j]
                // Ordenadas por x0: las siguientes empiezan aún más a la derecha (separación > reach)
                if (b.x0 > a.x1 + reach) break
                if (linked(a, b, gapX, gapY)) dsu.union(i, j)
                jj++
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) groups.getOrPut(dsu.find(i)) { ArrayList() }.add(i)
        val out = groups.values.map { idx ->
            var b = boxes[idx[0]]
            for (k in 1 until idx.size) b = b.union(boxes[idx[k]])
            b to idx.toIntArray()
        }
        return out.sortedWith(compareBy({ it.first.y0 / max(1, gapX) }, { it.first.x0 }))
    }

    /** Fusiona cajas que se solapan (tras añadir el margen) hasta que no quede ninguna superpuesta. */
    fun mergeOverlapping(boxes: List<Box>): List<Box> {
        var cur = boxes.toMutableList()
        var changed = true
        while (changed && cur.size > 1) {
            changed = false
            val n = cur.size
            val dsu = Dsu(n)
            val order = (0 until n).sortedBy { cur[it].x0 }
            for (ii in order.indices) {
                val a = cur[order[ii]]
                for (jj in ii + 1 until n) {
                    val b = cur[order[jj]]
                    if (b.x0 >= a.x1) break
                    if (a.overlaps(b)) { dsu.union(order[ii], order[jj]); changed = true }
                }
            }
            if (changed) {
                val g = LinkedHashMap<Int, Box>()
                for (i in 0 until n) { val r = dsu.find(i); g[r] = g[r]?.union(cur[i]) ?: cur[i] }
                cur = g.values.toMutableList()
            }
        }
        return cur
    }

    /** Caja traspuesta (x <-> y): agrupa texto vertical con las mismas reglas que el horizontal. */
    fun transpose(b: Box) = Box(b.y0, b.x0, b.y1, b.x1)

    /**
     * ¿Texto escrito en vertical (página girada 90°)? Cuenta pares de componentes vecinas muy próximas
     * (separación <= [near]) y bien solapadas (>= 50 %) en horizontal (misma línea de texto horizontal) y en
     * vertical; vertical si estos últimos superan claramente (x1.3) a los primeros.
     */
    fun isVerticalText(boxes: List<Box>, near: Int): Boolean {
        fun count(bs: List<Box>): Int {
            val order = bs.indices.sortedBy { bs[it].x0 }
            var n = 0
            for (ii in order.indices) {
                val a = bs[order[ii]]
                for (jj in ii + 1 until order.size) {
                    val b = bs[order[jj]]
                    if (b.x0 > a.x1 + near) break
                    val gx = max(0, max(a.x0, b.x0) - min(a.x1, b.x1))
                    if (gx > near) continue
                    val ov = min(a.y1, b.y1) - max(a.y0, b.y0)
                    if (ov >= 0.5 * max(1, min(a.h, b.h))) n++
                }
            }
            return n
        }
        val horiz = count(boxes)
        val vert = count(boxes.map { transpose(it) })
        return vert > 1.3 * horiz + 2
    }

    /** Mediana robusta (percentil [p]) de una lista de enteros; [default] si está vacía. */
    fun percentile(values: IntArray, p: Double, default: Int): Int {
        if (values.isEmpty()) return default
        val s = values.sortedArray()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }
}
