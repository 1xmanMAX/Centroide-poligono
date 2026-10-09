package com.scannerpromax.imaging

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Guías del propio TEXTO para el enderezado de hojas sin tabla ([GridDewarp]): en la hoja real los renglones son
 * horizontales y sus inicios (margen izquierdo, bordes de columna, tabuladores) y, si el texto está justificado, sus
 * finales están alineados en verticales. Estas guías entran en el mismo modelo de malla que las líneas de una tabla.
 *
 * Lógica pura (sin OpenCV; probada en JVM):
 * - [rowEdges]: línea BASE y línea MEDIA (altura de la x) de un renglón por perfiles de tinta en ventanas a lo largo del
 *   renglón (el salto de densidad del cuerpo de las letras; ascendentes, mayúsculas y descendentes no lo mueven).
 * - [splitRow]: tramos de un renglón separados por huecos grandes (calle entre columnas, tabulador).
 * - [linkAligned]: enlaza inicios (o finales) de tramos de renglones consecutivos que caen sobre una curva suave.
 * - [alignedGuides]: valida cada cadena como guía (soporte entre los renglones de su zona, dispersión, longitud): las
 *   sangrías de párrafo, viñetas, títulos centrados y renglones cortos quedan fuera; los finales sólo cuentan si el
 *   texto está justificado (dispersión pequeña); en manuscrito la tolerancia es mayor y el peso menor.
 * - [skewFromGuides]: ángulo global (renglones + guías verticales).
 */
internal object TextGuides {

    internal var HALF = 2f
    internal var STEP = 1.5f

    /** Tramo de renglón: inicio (xs, ys) y final (xe, ye) en px; [row] = índice del renglón. */
    class Seg(val row: Int, val xs: Float, val ys: Float, val xe: Float, val ye: Float)

    /** Guía vertical: puntos (x, y) ya suavizados, ordenados por y. */
    class Guide(
        val x: FloatArray,
        val y: FloatArray,
        val weight: Double,
        /** Dispersión (rms, px) de los inicios/finales respecto de la curva. */
        val resid: Double,
        /** Fracción de los renglones de su zona que caen sobre la guía. */
        val support: Double,
        /** true = inicios (margen izquierdo / borde izquierdo de columna); false = finales (justificado). */
        val start: Boolean,
        /** Tolerancia amplia (escritura a mano): no cuenta para decidir si la hoja es plana. */
        val loose: Boolean,
    ) {
        val size get() = x.size
    }

    /**
     * Bordes de un renglón por perfiles: [prof] = tinta por fila relativa al centro del renglón (índice 0 = desplazamiento
     * −[band]). Devuelve (desplazamiento de la línea media, desplazamiento de la línea base) o null si el perfil no tiene
     * cuerpo claro. La base es el mayor descenso de densidad por debajo del máximo (las descendentes son pocas), la línea
     * media el mayor ascenso por encima (las ascendentes y mayúsculas también son minoría).
     */
    fun profileEdges(prof: FloatArray, band: Int): Pair<Float, Float>? {
        val n = prof.size
        if (n < 5) return null
        val p = FloatArray(n)
        for (i in 0 until n) {
            val a = prof[max(0, i - 1)]; val b = prof[i]; val c = prof[min(n - 1, i + 1)]
            p[i] = (a + 2 * b + c) / 4f
        }
        var peak = 0f; var ip = -1
        for (i in 0 until n) if (p[i] > peak) { peak = p[i]; ip = i }
        if (peak <= 0f) return null
        // Base: mayor descenso p[i] - p[i+1] en la mitad inferior (desde el máximo), sobre zona de cuerpo
        var bestD = 0f; var ib = -1
        for (i in max(ip, band - band / 3) until n - 1) {
            if (p[i] < 0.45f * peak) continue
            val d = p[i] - p[i + 1]
            if (d > bestD) { bestD = d; ib = i }
        }
        var bestU = 0f; var it = -1
        for (i in min(ip, band + band / 3) downTo 1) {
            if (p[i] < 0.45f * peak) continue
            val d = p[i] - p[i - 1]
            if (d > bestU) { bestU = d; it = i }
        }
        if (ib < 0 || it < 0) return null
        if (bestD < 0.18f * peak || bestU < 0.18f * peak) return null
        // Posición sub-píxel del borde: centro de gravedad del descenso en el entorno
        fun edge(i: Int, down: Boolean): Float {
            var s = 0f; var sw = 0f
            for (k in i - 1..i + 1) {
                if (k < 0 || k >= n - 1) continue
                val d = if (down) p[k] - p[k + 1] else p[k + 1] - p[k]
                if (d > 0) { s += d * (k + 0.5f); sw += d }
            }
            return if (sw > 0) s / sw else i + 0.5f
        }
        val base = edge(ib, true) - band
        val top = edge(it - 1, false) - band
        if (base - top < 2f) return null
        return top to base
    }

    /**
     * Línea media y línea base de un renglón ([center]: centro suavizado, t = x, c = y) sobre la máscara de letras
     * [mask] ([w] x [h], ≠0 = tinta): perfiles en ventanas de ±2 letras cada 1.5 letras ([med] = altura de letra),
     * enderezados con el centro del renglón. Devuelve (línea media, línea base) como cadenas sin suavizar (puede haber
     * huecos donde una ventana no tiene cuerpo claro) o null si salen menos de 3 ventanas.
     */
    fun rowEdges(mask: ByteArray, w: Int, h: Int, center: DewarpMath.Chain, med: Float): Pair<DewarpMath.Chain, DewarpMath.Chain>? {
        val band = max(4, (1.15f * med).toInt())
        val half = max(6f, HALF * med)
        val step = max(3f, STEP * med)
        val prof = FloatArray(2 * band + 1)
        val tt = ArrayList<Float>(); val top = ArrayList<Float>(); val base = ArrayList<Float>()
        val span = center.span
        // Ventanas recortadas al renglón: la primera y la última llegan a sus extremos
        val inset = min(span / 2, 0.5f * half)
        val nWin = max(1, ((span - 2 * inset) / step).toInt() + 1)
        val first = if (nWin == 1) center.t0 + span / 2 else center.t0 + inset
        val stepW = if (nWin == 1) 0f else (span - 2 * inset) / (nWin - 1)
        for (k in 0 until nWin) {
            val tc = first + k * stepW
            prof.fill(0f)
            val xa = max(0, max(center.t0, tc - half).toInt()); val xb = min(w - 1, min(center.t1, tc + half).toInt())
            var x = xa
            var rs = 0f; var rn = 0
            while (x <= xb) {
                val yc = DewarpMath.interp(center, x.toFloat())
                val ycr = Math.round(yc)
                rs += ycr - yc; rn++
                val y0 = ycr - band
                for (d in 0..2 * band) {
                    val y = y0 + d
                    if (y in 0 until h && mask[y * w + x].toInt() != 0) prof[d] += 1f
                }
                x++
            }
            val e = profileEdges(prof, band) ?: continue
            // (los perfiles van referidos al centro redondeado de cada columna: se suma el redondeo medio)
            val te = (xa + xb) / 2f
            val yc = DewarpMath.interp(center, te) + (if (rn > 0) rs / rn else 0f)
            tt.add(te); top.add(yc + e.first); base.add(yc + e.second)
        }
        if (tt.size < 3) return null
        val t = tt.toFloatArray()
        return DewarpMath.Chain(t, top.toFloatArray()) to DewarpMath.Chain(t.copyOf(), base.toFloatArray())
    }

    /**
     * Prolonga cada renglón ([centers], con sus componentes [rows]) por ambos extremos con las componentes de letra
     * [comps] (x central, y central, y inferior, izquierda, derecha) que nadie ha reclamado y caen sobre la recta del
     * extremo (±0.45 [med]) a menos de [gap] de la última letra: palabras cortas del principio o final del renglón que
     * el seguimiento dejó fuera (el inicio real del renglón es lo que marca el margen). Añade las componentes a [rows]
     * y devuelve los centros prolongados (rectos en la parte añadida).
     */
    fun extendRows(centers: List<DewarpMath.Chain>, comps: List<FloatArray>, rows: List<MutableList<FloatArray>>, med: Float, gap: Float): List<DewarpMath.Chain> {
        val used = java.util.IdentityHashMap<FloatArray, Boolean>()
        for (r in rows) for (c in r) used[c] = true
        val fitLen = 6f * med
        val tolY = 0.45f * med
        return centers.mapIndexed { k, ch ->
            if (ch.size < 2) return@mapIndexed ch
            val row = rows[k]
            // Recta de cada extremo
            var j = 0
            while (j < ch.size - 1 && ch.t[j + 1] - ch.t0 <= fitLen) j++
            val (aL, bL) = DewarpMath.lineFit(ch.t, ch.c, 0, max(2, j + 1).coerceAtMost(ch.size))
            var i = ch.size - 1
            while (i > 0 && ch.t1 - ch.t[i - 1] <= fitLen) i--
            val (aR, bR) = DewarpMath.lineFit(ch.t, ch.c, min(i, ch.size - 2), ch.size)
            var curL = ch.t0; var curR = ch.t1
            for (c in row) { curL = min(curL, c[3]); curR = max(curR, c[4]) }
            while (true) {
                var best: FloatArray? = null
                for (c in comps) {
                    if (used.containsKey(c) || c[0] >= curL || c[4] < curL - gap) continue
                    if (abs(c[1] - (aL + bL * c[0])) > tolY) continue
                    if (best == null || c[4] > best[4]) best = c
                }
                val b = best ?: break
                used[b] = true; row.add(b); curL = min(curL, b[3])
            }
            while (true) {
                var best: FloatArray? = null
                for (c in comps) {
                    if (used.containsKey(c) || c[0] <= curR || c[3] > curR + gap) continue
                    if (abs(c[1] - (aR + bR * c[0])) > tolY) continue
                    if (best == null || c[3] < best[3]) best = c
                }
                val b = best ?: break
                used[b] = true; row.add(b); curR = max(curR, b[4])
            }
            row.sortBy { it[0] }
            if (curL >= ch.t0 - 1f && curR <= ch.t1 + 1f) return@mapIndexed ch
            val tl = ArrayList<Float>(); val cl = ArrayList<Float>()
            var t = curL
            while (t < ch.t0 - 1f) { tl.add(t); cl.add((aL + bL * t).toFloat()); t += 3f }
            for (q in 0 until ch.size) { tl.add(ch.t[q]); cl.add(ch.c[q]) }
            t = ch.t1 + 3f
            while (t <= curR) { tl.add(t); cl.add((aR + bR * t).toFloat()); t += 3f }
            DewarpMath.Chain(tl.toFloatArray(), cl.toFloatArray())
        }
    }

    /**
     * Suavizado de una línea de renglón: descarte robusto de atípicos (regresión local lineal, ±[win]) y regresión
     * local CUADRÁTICA de los valores originales de los inliers (no aplana la curvatura real del renglón).
     */
    fun smoothRow(ch: DewarpMath.Chain, win: Float, outTol: Float, minInlier: Float): DewarpMath.Chain? {
        val r = DewarpMath.robustSmooth(ch, win, outTol, minInlier) ?: return null
        val raw = FloatArray(r.size)
        var j = 0
        for (i in 0 until r.size) {
            while (j < ch.size && ch.t[j] < r.t[i]) j++
            raw[i] = if (j < ch.size) ch.c[j] else r.c[i]
        }
        return DewarpMath.quadSmooth(DewarpMath.Chain(r.t, raw), win * 1.2f)
    }

    /**
     * Puntos de la línea media ([top]) cuya distancia a la base ([base], mismas t) se aparta de la mediana del renglón
     * menos de [tol]: null si quedan menos de la mitad (renglón de mayúsculas o cifras: sin altura de x fiable).
     */
    fun consistentTop(top: DewarpMath.Chain, base: DewarpMath.Chain, tol: Float): DewarpMath.Chain? {
        val n = min(top.size, base.size)
        if (n < 3) return null
        val sep = FloatArray(n) { base.c[it] - top.c[it] }
        val med = sep.sorted()[n / 2]
        val keep = (0 until n).filter { abs(sep[it] - med) <= tol }
        if (keep.size < max(3, n / 2)) return null
        return DewarpMath.Chain(FloatArray(keep.size) { top.t[keep[it]] }, FloatArray(keep.size) { top.c[keep[it]] })
    }

    /** Tramos de un renglón: componentes ordenadas por su izquierda ([lefts], [rights]); se corta donde el hueco > [gap]. */
    fun splitRow(lefts: FloatArray, rights: FloatArray, gap: Float): List<IntRange> {
        val n = lefts.size
        if (n == 0) return emptyList()
        val out = ArrayList<IntRange>()
        var start = 0
        var reach = rights[0]
        for (i in 1 until n) {
            if (lefts[i] - reach > gap) { out.add(start until i); start = i }
            reach = max(reach, rights[i])
        }
        out.add(start until n)
        return out
    }

    /**
     * Enlaza puntos (x, y) en cadenas casi verticales: en orden de y, cada punto se une a la cadena activa cuya
     * predicción (último x + pendiente reciente · Δy) queda más cerca (≤ [tol] + 3 % de Δy, o + 12 % mientras la cadena
     * no tiene pendiente), con Δy entre [minDy] (no dos tramos del mismo renglón) y [maxGap] (se admiten renglones
     * sangrados o cortos por medio). Devuelve los índices de cada cadena (≥ 2 puntos).
     */
    fun linkAligned(x: FloatArray, y: FloatArray, tol: Float, maxGap: Float, minDy: Float, maxSlope: Float = 0.3f): List<IntArray> {
        val order = x.indices.sortedBy { y[it] }
        class B { val idx = ArrayList<Int>(); var slope = 0f; var hasSlope = false }
        val active = ArrayList<B>()
        val done = ArrayList<B>()
        for (p in order) {
            val px = x[p]; val py = y[p]
            val it = active.iterator()
            while (it.hasNext()) { val b = it.next(); if (py - y[b.idx.last()] > maxGap) { done.add(b); it.remove() } }
            var best: B? = null; var bestE = Float.MAX_VALUE
            for (b in active) {
                val l = b.idx.last()
                val dy = py - y[l]
                if (dy < minDy) continue
                val pred = x[l] + b.slope * dy
                val e = abs(px - pred)
                val allowed = tol + (if (b.hasSlope) 0.03f else 0.12f) * dy
                if (e <= allowed && e < bestE) { bestE = e; best = b }
            }
            val b = best ?: B().also { active.add(it) }
            b.idx.add(p)
            if (b.idx.size >= 2) {
                // Pendiente dx/dy de los últimos (≤ 6) puntos
                val k0 = max(0, b.idx.size - 6)
                var s0 = 0.0; var sy = 0.0; var sx = 0.0; var syy = 0.0; var sxy = 0.0
                for (k in k0 until b.idx.size) {
                    val q = b.idx[k]; val yy = y[q].toDouble(); val xx = x[q].toDouble()
                    s0 += 1; sy += yy; sx += xx; syy += yy * yy; sxy += yy * xx
                }
                val den = s0 * syy - sy * sy
                if (den > 1e-6) { b.slope = ((s0 * sxy - sy * sx) / den).toFloat().coerceIn(-maxSlope, maxSlope); b.hasSlope = true }
            }
        }
        done.addAll(active)
        return done.filter { it.idx.size >= 2 }.map { it.idx.toIntArray() }
    }

    /** Valor de la curva poligonal (x en función de y, [gy] creciente) en [y]; NaN fuera de su tramo (± [ext]). */
    fun curveAt(gx: FloatArray, gy: FloatArray, y: Float, ext: Float = 0f): Float {
        val n = gy.size
        if (n == 0 || y < gy[0] - ext || y > gy[n - 1] + ext) return Float.NaN
        if (y <= gy[0]) return gx[0]
        if (y >= gy[n - 1]) return gx[n - 1]
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (gy[m] <= y) lo = m else hi = m }
        val f = (y - gy[lo]) / max(1e-6f, gy[hi] - gy[lo])
        return gx[lo] + (gx[hi] - gx[lo]) * f
    }

    /**
     * Guías verticales a partir de los tramos [segs]: inicios ([start] = true) o finales. [med] = altura típica de letra
     * (px), [pitch] = paso entre renglones, [w] = ancho de la imagen (los tramos cortados por el borde no cuentan).
     * Primero con tolerancia estricta (impreso); si no sale ninguna guía de inicios, con tolerancia amplia (manuscrito:
     * peso menor y sólo si la dispersión sigue siendo moderada).
     */
    fun alignedGuides(segs: List<Seg>, med: Float, pitch: Float, w: Float, start: Boolean): List<Guide> {
        val strict = guidesWithTol(segs, med, pitch, w, start, loose = false)
        if (strict.isNotEmpty() || !start) return strict
        return guidesWithTol(segs, med, pitch, w, start, loose = true)
    }

    private fun guidesWithTol(segs: List<Seg>, med: Float, pitch: Float, w: Float, start: Boolean, loose: Boolean): List<Guide> {
        val edge = 0.6f * med
        val pts = segs.filter { s ->
            val minLen = 3f * med
            s.xe - s.xs >= minLen && (if (start) s.xs > edge else s.xe < w - edge)
        }
        if (pts.size < 4) return emptyList()
        val px = FloatArray(pts.size) { if (start) pts[it].xs else pts[it].xe }
        val py = FloatArray(pts.size) { if (start) pts[it].ys else pts[it].ye }
        val tol = (if (loose) 0.9f else 0.4f) * med
        val chains = linkAligned(px, py, tol, maxGap = 4.5f * pitch, minDy = 0.5f * pitch)
        val minN = 6
        val out = ArrayList<Guide>()
        for (ch in chains) {
            if (ch.size < minN) continue
            val sorted = ch.sortedBy { py[it] }
            val ys = FloatArray(sorted.size) { py[sorted[it]] }
            val xs = FloatArray(sorted.size) { px[sorted[it]] }
            if (ys.last() - ys.first() < 3f * pitch) continue
            // Suavizado robusto x(y): regresión local lineal (±2.5 renglones) con descarte de atípicos y, sobre los
            // inliers, regresión local CUADRÁTICA (±5 renglones): un margen es una curva suave; quita el ruido de los
            // inicios de letra sin aplanar ondulaciones reales
            val sm0 = DewarpMath.robustSmooth(DewarpMath.Chain(ys, xs), win = 2.6f * pitch, outTol = tol, minInlier = 0.7f) ?: continue
            if (sm0.size < minN) continue
            val raw = FloatArray(sm0.size) { curveAt(xs, ys, sm0.t[it]) }
            val sm = DewarpMath.quadSmooth(DewarpMath.Chain(sm0.t, raw), 5.2f * pitch)
            var ss = 0.0
            for (i in 0 until sm.size) ss += (raw[i] - sm.c[i]).toDouble().let { it * it }
            val resid = sqrt(ss / sm.size)
            // Soporte: renglones de la zona (± 2 renglones más allá de la cadena) que empiezan (terminan) cerca de la
            // guía y pertenecen a su columna
            var cand = 0; var inl = 0
            val near = ArrayList<Float>()
            for (s in segs) {
                val yy = if (start) s.ys else s.ye
                val g = curveAt(sm.c, sm.t, yy, 2f * pitch)
                if (g.isNaN()) continue
                val d = if (start) {
                    if (s.xs < g - 3f * med || s.xs > g + 8f * med || s.xe < g + 3f * med) continue
                    s.xs - g
                } else {
                    if (s.xe > g + 3f * med || s.xe < g - 8f * med || s.xs > g - 3f * med) continue
                    s.xe - g
                }
                cand++; if (abs(d) <= tol) inl++
                if (abs(d) <= 2f * med) near.add(abs(d))
            }
            val support = if (cand == 0) 0.0 else inl.toDouble() / cand
            // Finales: sólo con texto JUSTIFICADO (los finales en bandera se dispersan varias letras)
            val minSupport = if (start) 0.6 else 0.65
            if (support < minSupport) continue
            // Dispersión de TODOS los renglones que empiezan (terminan) cerca, no sólo de los enlazados: una racha
            // casual de inicios parecidos en letra a mano muy dispersa no es un margen
            near.sort()
            val spread = if (near.isEmpty()) 0f else near[near.size / 2]
            if (spread > (if (loose) 0.35f else 0.25f) * med) continue
            // Dispersión: impreso ≤ 0.07 letras + 0.5 px; hasta 0.4 se acepta como guía amplia (manuscrito, peso menor)
            if (resid > 0.4 * med) continue
            val isLoose = loose || resid > 0.07 * med + 0.5
            val nF = min(1.0, sm.size / 10.0)
            val rF = 1.0 / (1.0 + (resid / (0.12 * med)).let { it * it })
            val base = if (isLoose) 0.35 else 1.0
            val wgt = base * nF * (0.4 + 0.6 * rF) * min(1.0, support / 0.8)
            out.add(Guide(sm.c, sm.t, wgt, resid, support, start, isLoose))
        }
        return out
    }

    /**
     * Ángulo (grados, positivo = girar en sentido antihorario para enderezar; convención de
     * `Imgproc.getRotationMatrix2D`) a partir de las pendientes de los renglones ([rowSlopes] dy/dx, con su peso
     * [rowW]) y de las guías verticales ([vSlopes] dx/dy, peso [vW]). null si hay pocos renglones o no son coherentes
     * (dispersión > 0.35°) o si las verticales discrepan (> 0.8°) de los renglones.
     */
    fun skewFromGuides(rowSlopes: DoubleArray, rowW: DoubleArray, vSlopes: DoubleArray, vW: DoubleArray): Double? {
        if (rowSlopes.size < 6) return null
        val ang = DoubleArray(rowSlopes.size) { Math.toDegrees(atan(rowSlopes[it])) }
        val med = weightedMedian(ang, rowW)
        val dev = DoubleArray(ang.size) { abs(ang[it] - med) }
        val mad = weightedMedian(dev, rowW)
        if (mad > 0.35) return null
        var a = med
        if (vSlopes.isNotEmpty()) {
            val va = DoubleArray(vSlopes.size) { -Math.toDegrees(atan(vSlopes[it])) }
            val vm = weightedMedian(va, vW)
            if (abs(vm - med) > 0.8) return null
            val wr = rowW.sum().coerceAtLeast(1e-9); val wv = vW.sum()
            // Las verticales son pocas pero largas: pesan como mucho la mitad
            val f = min(0.5, wv / (wr + wv))
            a = med * (1 - f) + vm * f
        }
        return a
    }

    fun weightedMedian(v: DoubleArray, w: DoubleArray): Double {
        val idx = v.indices.sortedBy { v[it] }
        val tot = idx.sumOf { w[it] }
        var acc = 0.0
        for (i in idx) { acc += w[i]; if (acc >= tot / 2) return v[i] }
        return v[idx.last()]
    }
}
