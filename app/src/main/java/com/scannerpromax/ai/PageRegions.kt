package com.scannerpromax.ai

import com.scannerpromax.ai.AiLayout.IRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detección CLÁSICA de regiones no textuales sobre la página procesada reducida (lógica pura, JVM): tablas con
 * reglado (rectas horizontales y verticales oscuras que se cruzan), ejes de gráfico (una vertical a la izquierda y una
 * horizontal abajo) y celdas con contenido (para figuras: [AiLayout.figureRegions]). Es lo que el banco IA recomienda
 * para tablas/figuras: Paddle no las localiza y Qwen 0.8B tampoco (1/29).
 *
 * [gray] y [chroma] = luminancia y croma (max-min RGB) 0..255 de una imagen [w] x [h] (lado largo ~1000–1400 px).
 */
class PageRegions(val w: Int, val h: Int, private val gray: ByteArray, private val chroma: ByteArray) {

    /** Región de reglado encontrada. [rows]/[cols] = posiciones (px) de las rectas horizontales/verticales. */
    data class Ruled(val rect: IRect, val rows: List<Int>, val cols: List<Int>, val kind: Kind) {
        enum class Kind { TABLE, CHART_AXES }
        val nCols: Int get() = (cols.size - 1).coerceAtLeast(1)
    }

    private val paper: Int by lazy {
        val hist = IntArray(256)
        for (b in gray) hist[b.toInt() and 0xFF]++
        var acc = 0; val target = (gray.size * 0.9).toInt(); var p = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= target) { p = i; break } }
        p
    }

    /** Píxel de tinta para el reglado: oscuro en absoluto (la cuadrícula del cuaderno se dibuja en tono claro). */
    private val darkLevel: Int get() = min(150, paper - 70)

    private fun dark(i: Int) = (gray[i].toInt() and 0xFF) < darkLevel

    /**
     * Máscara de tramos rectos largos: en cada fila (o columna) los tramos oscuros (tolerando huecos de [gap] px) de
     * longitud >= [minLen].
     */
    private fun runs(horizontal: Boolean, minLen: Int, gap: Int = 2): BooleanArray {
        val m = BooleanArray(w * h)
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            var start = -1; var lastDark = -1
            for (k in 0..inner) {
                val isDark = k < inner && dark(if (horizontal) o * w + k else k * w + o)
                if (isDark) {
                    if (start < 0) start = k
                    lastDark = k
                } else if (start >= 0 && k - lastDark > gap) {
                    if (lastDark - start + 1 >= minLen) {
                        for (t in start..lastDark) m[if (horizontal) o * w + t else t * w + o] = true
                    }
                    start = -1
                }
            }
        }
        return m
    }

    /** Tablas con reglado y ejes de gráfico. */
    fun ruledRegions(): List<Ruled> {
        val minH = max(20, (w * 0.06f).toInt())
        val minV = max(16, (h * 0.03f).toInt())
        val hm = runs(true, minH)
        val vm = runs(false, minV)
        // Componentes de la unión dilatada (3 px) de ambas familias.
        val u = BooleanArray(w * h)
        val r = 2
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!hm[i] && !vm[i]) continue
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) u[yy * w + xx] = true
        }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Ruled>()
        for (s in u.indices) {
            if (!u[s] || seen[s]) continue
            var sp = 0; stack[sp++] = s; seen[s] = true
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y
                if (x > 0) { val q = p - 1; if (u[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (u[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (u[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (u[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
            }
            val rect = IRect(x0, y0, x1 + 1, y1 + 1)
            if (rect.w < w * 0.15f || rect.h < h * 0.04f) continue
            val rows = lines(hm, rect, horizontal = true)
            val cols = lines(vm, rect, horizontal = false)
            val kind = classify(rect, rows, cols) ?: continue
            out.add(Ruled(rect, rows, cols, kind))
        }
        return out
    }

    /** Posiciones de las rectas de una familia que recorren >= 50 % de la región (agrupando filas contiguas). */
    private fun lines(m: BooleanArray, r: IRect, horizontal: Boolean): List<Int> {
        val span = if (horizontal) r.w else r.h
        val pos = ArrayList<Int>()
        val n = if (horizontal) r.h else r.w
        var runStart = -1
        for (k in 0..n) {
            var cov = 0
            if (k < n) {
                if (horizontal) { val y = r.y0 + k; for (x in r.x0 until r.x1) if (m[y * w + x]) cov++ }
                else { val x = r.x0 + k; for (y in r.y0 until r.y1) if (m[y * w + x]) cov++ }
            }
            val on = k < n && cov >= span * 0.5f
            if (on && runStart < 0) runStart = k
            if (!on && runStart >= 0) {
                pos.add((if (horizontal) r.y0 else r.x0) + (runStart + k - 1) / 2)
                runStart = -1
            }
        }
        return pos
    }

    private fun classify(rect: IRect, rows: List<Int>, cols: List<Int>): Ruled.Kind? {
        // Papel cuadriculado / pautado: muchas rectas a paso regular en ambas familias por toda la página.
        if (rows.size >= 12 && cols.size >= 12 && regular(rows) && regular(cols) && rect.area > 0.5 * w * h) return null
        if (rows.size >= 2 && cols.size >= 2 && (rows.size >= 3 || cols.size >= 3) && rect.area >= 0.015 * w * h) return Ruled.Kind.TABLE
        // Ejes de gráfico: una vertical en el borde izquierdo y una horizontal abajo, sin rejilla.
        if (rows.size in 1..2 && cols.size in 1..2 && rect.area >= 0.02 * w * h) {
            val left = cols.first() - rect.x0 <= rect.w * 0.15f
            val bottom = rect.y1 - rows.last() <= rect.h * 0.15f
            if (left && bottom) return Ruled.Kind.CHART_AXES
        }
        return null
    }

    private fun regular(p: List<Int>): Boolean {
        if (p.size < 4) return false
        val d = p.zipWithNext { a, b -> (b - a).toDouble() }
        val mean = d.average()
        if (mean <= 0) return false
        val sd = sqrt(d.sumOf { (it - mean) * (it - mean) } / d.size)
        return sd / mean < 0.2
    }

    /**
     * Rejilla de celdas de [cell] px con contenido (tinta o color en >= 4 % de los píxeles). Devuelve (máscara,
     * columnas, filas).
     */
    fun contentGrid(cell: Int): Triple<BooleanArray, Int, Int> {
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val cnt = IntArray(gw * gh)
        val tot = IntArray(gw * gh)
        val inkLevel = paper - 45
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = (y / cell) * gw + x / cell
            tot[c]++
            if ((gray[i].toInt() and 0xFF) < inkLevel || (chroma[i].toInt() and 0xFF) > 60) cnt[c]++
        }
        return Triple(BooleanArray(gw * gh) { tot[it] > 0 && cnt[it] >= tot[it] * 0.04f }, gw, gh)
    }

    /** Fracción de croma alta (color) dentro de un rectángulo (para distinguir gráficos de color de fotos/texto). */
    fun colorFraction(r: IRect): Float {
        var n = 0; var c = 0
        for (y in max(0, r.y0) until min(h, r.y1)) for (x in max(0, r.x0) until min(w, r.x1)) {
            n++; if ((chroma[y * w + x].toInt() and 0xFF) > 60) c++
        }
        return if (n == 0) 0f else c.toFloat() / n
    }
}
