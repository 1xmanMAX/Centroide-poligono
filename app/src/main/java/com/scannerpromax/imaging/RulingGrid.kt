package com.scannerpromax.imaging

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lógica pura (sin OpenCV, probada con tests JVM): reconstrucción de la cuadrícula / renglones de un cuaderno a partir
 * de la huella de las rectas claras ([TextRegions]), para dibujarla LIMPIA en el resultado
 * ([com.scannerpromax.domain.PageEdits.keepRuling]).
 *
 * En vez de copiar los píxeles de la rejilla (ruidosos, cortados por la escritura y las sombras, con el grosor y el
 * tono de cada zona) se localiza cada recta y se redibuja fina, continua y de tono uniforme:
 *  1. la máscara se corta en franjas perpendiculares a las rectas (~1/24 del lado); en cada franja, el perfil de
 *     cobertura (fracción de la franja ocupada por la huella en cada fila, sumada en 3 filas) da un pico por recta
 *     (posición sub-píxel por centroide);
 *  2. los picos de franjas consecutivas se enlazan en cadenas (predicción con la pendiente, hasta [MAX_SKIP] franjas
 *     sin pico: letras encima) y las cadenas colineales separadas por huecos largos se unen;
 *  3. tras el enderezado las rectas de una familia son casi paralelas: cada cadena se ajusta a una recta; las que se
 *     apartan de la pendiente de la familia (enlaces erróneos) se descartan y los duplicados se funden;
 *  4. en una rejilla REGULAR (paso parecido entre vecinas) las rectas que faltan entre dos vecinas (sombra, tachón,
 *     escritura densa) se interpolan, las cortas se prolongan por donde siguen sus vecinas o la otra familia
 *     ([crossExtend]) sin saltar el hueco entre dos páginas;
 *  5. los extremos se ajustan a donde la huella termina y cada cadena se suaviza.
 */
internal object RulingGrid {

    /** Recta reconstruida: [along] creciente (x en las horizontales, y en las verticales) y [cross] la posición perpendicular. */
    class Line(val along: DoubleArray, val cross: DoubleArray, val observed: Int = along.size) {
        val start: Double get() = along.first()
        val end: Double get() = along.last()
        val size: Int get() = along.size
        val span: Double get() = end - start

        /** Posición perpendicular en [t] (interpolación lineal; constante fuera del tramo). */
        fun at(t: Double): Double {
            if (t <= along.first()) return cross.first()
            if (t >= along.last()) return cross.last()
            var lo = 0; var hi = along.size - 1
            while (hi - lo > 1) { val m = (lo + hi) / 2; if (along[m] <= t) lo = m else hi = m }
            val f = (t - along[lo]) / max(1e-9, along[hi] - along[lo])
            return cross[lo] + f * (cross[hi] - cross[lo])
        }

        fun meanCross(): Double = cross.average()
    }

    /** Franjas sin pico toleradas dentro de una cadena. */
    const val MAX_SKIP = 3

    /** Cobertura mínima (suma de 3 filas) de un pico de recta en una franja. */
    const val MIN_COVER = 0.35

    /**
     * Cuadrícula completa: rectas horizontales y verticales de las máscaras [maskH] / [maskV] ([w]x[h], != 0 = huella;
     * null = sin esa familia). Coordenadas de la máscara.
     */
    /** Cuadrícula reconstruida: rectas horizontales y verticales y el paso de cada familia (0 si no es regular). */
    class Grid(val h: List<Line>, val v: List<Line>, val periodH: Double, val periodV: Double, val slopeH: Double = 0.0, val slopeV: Double = 0.0)

    /**
     * Prolonga cada recta (recta, con su pendiente si es larga o la de la familia) mientras siga dentro de la zona de
     * escritura [inside] (x, y): la cuadrícula cubre toda la página aunque la huella se perdiera bajo una sombra.
     */
    fun extendWithin(lines: List<Line>, horizontal: Boolean, slope: Double, alongLen: Int, inside: (Int, Int) -> Boolean): List<Line> =
        lines.map { l ->
            val n = l.size
            val sl = if (l.span >= 0.3 * alongLen && n >= 4) fitLine(l).second else slope
            fun ok(t: Double, c: Double): Boolean {
                val a = t.roundToInt(); val cc = c.roundToInt()
                return if (horizontal) inside(a, cc) else inside(cc, a)
            }
            var s = l.start
            while (s - 1 >= 0 && ok(s - 1, l.cross[0] + sl * (s - 1 - l.start))) s -= 1.0
            var e = l.end
            while (e + 1 < alongLen && ok(e + 1, l.cross[n - 1] + sl * (e + 1 - l.end))) e += 1.0
            if (s >= l.start - 1 && e <= l.end + 1) return@map l
            val a = ArrayList<Double>(); val c = ArrayList<Double>()
            var t = s
            while (t < l.start - 4) { a.add(t); c.add(l.cross[0] + sl * (t - l.start)); t += 8.0 }
            for (q in 0 until n) { a.add(l.along[q]); c.add(l.cross[q]) }
            t = l.end + 8.0
            while (t < e) { a.add(t); c.add(l.cross[n - 1] + sl * (t - l.end)); t += 8.0 }
            if (e > l.end + 1) { a.add(e); c.add(l.cross[n - 1] + sl * (e - l.end)) }
            Line(a.toDoubleArray(), c.toDoubleArray(), l.observed)
        }

    fun extractGrid(maskH: ByteArray?, maskV: ByteArray?, w: Int, h: Int): Grid {
        val fh = if (maskH != null) family(maskH, w, h, true) else Family.EMPTY
        val fv = if (maskV != null) family(maskV, w, h, false) else Family.EMPTY
        var hs = fh.lines; var vs = fv.lines
        repeat(2) {
            hs = fillRegular(hs, fh.period); vs = fillRegular(vs, fv.period)
            val h2 = crossExtend(hs, vs, fh.slope, w.toDouble()); val v2 = crossExtend(vs, hs, fv.slope, h.toDouble())
            hs = h2; vs = v2
        }
        hs = dedupe(fillRegular(hs, fh.period), fh.period); vs = dedupe(fillRegular(vs, fv.period), fv.period)
        return Grid(hs.map { smooth(it) }, vs.map { smooth(it) }, fh.period, fv.period, fh.slope, fv.slope)
    }

    /**
     * Rectas fiables para delimitar la zona de escritura: observadas (>= 3 muestras, no interpoladas) y sin las de los
     * extremos cuya separación con la vecina no es el paso (el canto de la hoja o el borde del marco impreso).
     */
    fun trimToPeriod(lines: List<Line>, period: Double): List<Line> {
        val ls = lines.filter { it.observed >= 3 }.sortedBy { it.meanCross() }.toMutableList()
        if (period <= 0) return ls
        fun ok(a: Line, b: Line) = abs(b.meanCross() - a.meanCross()) in 0.7 * period..1.35 * period
        while (ls.size >= 2 && !ok(ls[0], ls[1])) ls.removeAt(0)
        while (ls.size >= 2 && !ok(ls[ls.size - 2], ls[ls.size - 1])) ls.removeAt(ls.size - 1)
        return ls
    }

    /** Una sola familia (renglones sin verticales, o pruebas). */
    fun extract(mask: ByteArray, w: Int, h: Int, horizontal: Boolean): List<Line> {
        val f = family(mask, w, h, horizontal)
        return dedupe(fillRegular(f.lines, f.period), f.period).map { smooth(it) }
    }

    /** Rectas de una familia, su pendiente común y su paso (0 si no es regular). */
    class Family(val lines: List<Line>, val slope: Double, val period: Double) {
        companion object { val EMPTY = Family(emptyList(), 0.0, 0.0) }
    }

    fun family(mask: ByteArray, w: Int, h: Int, horizontal: Boolean): Family {
        val alongLen = if (horizontal) w else h
        val crossLen = if (horizontal) h else w
        if (alongLen < 32 || crossLen < 16) return Family.EMPTY
        val stripW = max(12, alongLen / 24)
        val nStrips = ceil(alongLen.toDouble() / stripW).toInt()
        fun on(a: Int, c: Int) = mask[if (horizontal) c * w + a else a * w + c].toInt() != 0
        val centers = DoubleArray(nStrips)
        val peaks = ArrayList<DoubleArray>(nStrips)
        val prof = DoubleArray(crossLen)
        for (s in 0 until nStrips) {
            val a0 = s * stripW; val a1 = min(alongLen, a0 + stripW)
            centers[s] = (a0 + a1 - 1) / 2.0
            java.util.Arrays.fill(prof, 0.0)
            if (horizontal) {
                for (c in 0 until crossLen) { var n = 0; val row = c * w; for (a in a0 until a1) if (mask[row + a].toInt() != 0) n++; prof[c] = n.toDouble() }
            } else {
                for (a in a0 until a1) { val row = a * w; for (c in 0 until crossLen) if (mask[row + c].toInt() != 0) prof[c] += 1.0 }
            }
            val len = (a1 - a0).toDouble()
            for (c in 0 until crossLen) prof[c] /= len
            peaks.add(profilePeaks(prof))
        }
        // Paso aproximado: separación típica entre picos consecutivos de una franja
        val gaps = ArrayList<Double>()
        for (p in peaks) for (i in 1 until p.size) gaps.add(p[i] - p[i - 1])
        val p0 = regularPeriod(gaps) ?: 0.0
        val tol = if (p0 > 0) min(2.5, 0.25 * p0) else 2.5
        var lines = chain(centers, peaks, tol)
        val slope0 = familySlope(lines, alongLen.toDouble())
        lines = mergeCollinear(lines, tol, slope0, if (p0 > 0) 0.3 * p0 else 6.0)
        lines = lines.filter { it.size >= 3 }
        val slope = familySlope(lines, alongLen.toDouble())
        // Enlaces erróneos: cadenas cortas que se apartan de la pendiente común
        lines = lines.filter { l -> l.span >= 0.3 * alongLen || abs(fitLine(l).second - slope) <= 0.03 }
        lines = lines.map { refineEnds(it, stripW) { a, c -> a in 0 until alongLen && c in 0 until crossLen && on(a, c) } }
        // (paso: el de los picos de cada franja, robusto con dos páginas cuyas rectas se intercalan)
        val period = if (p0 > 0) p0 else regularPeriod(neighbourSpacings(lines)) ?: 0.0
        return Family(dedupe(lines, period), slope, period)
    }

    /** Picos (posición sub-píxel) de un perfil de cobertura: máximos locales de la suma en 3 filas >= [MIN_COVER]. */
    fun profilePeaks(prof: DoubleArray): DoubleArray {
        val n = prof.size
        val s3 = DoubleArray(n) { (if (it > 0) prof[it - 1] else 0.0) + prof[it] + (if (it < n - 1) prof[it + 1] else 0.0) }
        val out = ArrayList<Double>()
        var lastC = -100; var lastV = 0.0
        for (c in 0 until n) {
            val v = s3[c]
            if (v < MIN_COVER) continue
            var isMax = true
            for (d in -2..2) {
                if (d == 0) continue
                val j = c + d
                if (j < 0 || j >= n) continue
                // (meseta: gana el primero)
                if (s3[j] > v || (d < 0 && s3[j] == v)) { isMax = false; break }
            }
            if (!isMax) continue
            var sw = 0.0; var sp = 0.0
            for (d in -2..2) { val j = c + d; if (j in 0 until n) { sw += prof[j]; sp += prof[j] * j } }
            val pos = if (sw > 0) sp / sw else c.toDouble()
            if (c - lastC <= 3 && out.isNotEmpty()) {
                if (v > lastV) { out[out.size - 1] = pos; lastC = c; lastV = v }
                continue
            }
            out.add(pos); lastC = c; lastV = v
        }
        return out.toDoubleArray()
    }

    /** Enlaza los picos de franjas consecutivas en cadenas (predicción con la pendiente reciente). */
    fun chain(centers: DoubleArray, peaks: List<DoubleArray>, tol: Double): List<Line> {
        class Ch { val a = ArrayList<Double>(); val c = ArrayList<Double>(); var lastS = 0 }
        val active = ArrayList<Ch>(); val done = ArrayList<Ch>()
        for (s in centers.indices) {
            val it = active.iterator()
            while (it.hasNext()) { val ch = it.next(); if (s - ch.lastS > MAX_SKIP + 1) { done.add(ch); it.remove() } }
            val pk = peaks[s]
            val t = centers[s]
            // pares (cadena, pico) por distancia a la predicción, voraz
            val pairs = ArrayList<Triple<Double, Int, Int>>()
            for ((ci, ch) in active.withIndex()) {
                val n = ch.a.size
                val k = max(0, n - 4)
                val slope = if (n >= 2) ((ch.c[n - 1] - ch.c[k]) / max(1.0, ch.a[n - 1] - ch.a[k])).coerceIn(-0.1, 0.1) else 0.0
                val pred = ch.c[n - 1] + slope * (t - ch.a[n - 1])
                val tl = tol * (1.0 + 0.3 * (s - ch.lastS - 1))
                for ((pi, p) in pk.withIndex()) { val d = abs(p - pred); if (d <= tl) pairs.add(Triple(d, ci, pi)) }
            }
            pairs.sortBy { it.first }
            val usedC = BooleanArray(active.size); val usedP = BooleanArray(pk.size)
            for ((_, ci, pi) in pairs) {
                if (usedC[ci] || usedP[pi]) continue
                usedC[ci] = true; usedP[pi] = true
                val ch = active[ci]; ch.a.add(t); ch.c.add(pk[pi]); ch.lastS = s
            }
            for ((pi, p) in pk.withIndex()) if (!usedP[pi]) active.add(Ch().also { it.a.add(t); it.c.add(p); it.lastS = s })
        }
        done.addAll(active)
        return done.filter { it.a.size >= 2 }.map { Line(it.a.toDoubleArray(), it.c.toDoubleArray()) }
    }

    /** Recta de ajuste (ordenada en el origen, pendiente) por mínimos cuadrados con un descarte de atípicos. */
    fun fitLine(l: Line): Pair<Double, Double> {
        val n = l.size
        if (n < 2) return l.cross.first() to 0.0
        var w = BooleanArray(n) { true }
        var res = 0.0 to 0.0
        repeat(2) {
            var sw = 0.0; var st = 0.0; var sc = 0.0
            for (i in 0 until n) if (w[i]) { sw++; st += l.along[i]; sc += l.cross[i] }
            if (sw < 2) return res
            val mt = st / sw; val mc = sc / sw
            var stt = 0.0; var stc = 0.0
            for (i in 0 until n) if (w[i]) { val dt = l.along[i] - mt; stt += dt * dt; stc += dt * (l.cross[i] - mc) }
            val b = if (stt > 1e-9) stc / stt else 0.0
            res = (mc - b * mt) to b
            val r = DoubleArray(n) { abs(l.cross[it] - (res.first + b * l.along[it])) }
            val lim = max(1.0, 3.0 * r.sorted()[n / 2])
            w = BooleanArray(n) { r[it] <= lim }
        }
        return res
    }

    /** Pendiente común de la familia: mediana de las pendientes ponderada por longitud (cadenas largas). */
    fun familySlope(lines: List<Line>, alongLen: Double): Double {
        val long = lines.filter { it.span >= 0.15 * alongLen && it.size >= 3 }
        if (long.isEmpty()) return 0.0
        return HwStats.weightedPercentile(long.map { fitLine(it).second }, long.map { it.span }, 0.5) ?: 0.0
    }

    /**
     * Une cadenas colineales separadas por un hueco (escritura densa encima de la recta): el inicio de B debe caer,
     * con la pendiente de la familia, a <= tol + 0.3 % del hueco del final de A (y nunca a más de [maxD]).
     */
    fun mergeCollinear(lines: List<Line>, tol: Double, slope: Double, maxD: Double): List<Line> {
        val ls = lines.sortedBy { it.start }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in ls.indices) {
                val a = ls[i]
                var best = -1; var bestD = Double.MAX_VALUE
                for (j in ls.indices) {
                    if (j == i) continue
                    val b = ls[j]
                    if (b.start <= a.end) continue
                    val gap = b.start - a.end
                    val d = abs(a.cross.last() + slope * gap - b.cross.first())
                    if (d <= min(maxD, tol + 0.003 * gap) && d < bestD) { bestD = d; best = j }
                }
                if (best >= 0) {
                    val b = ls[best]
                    val m = Line(a.along + b.along, a.cross + b.cross)
                    val hi = max(i, best); val lo = min(i, best)
                    ls.removeAt(hi); ls.removeAt(lo); ls.add(m); ls.sortBy { it.start }
                    merged = true
                    break@loop
                }
            }
        }
        return ls
    }

    /** Ajusta los extremos a donde la huella termina (se recorren hasta [reach] px hacia fuera con huecos <= 6 px). */
    fun refineEnds(l: Line, reach: Int, on: (Int, Int) -> Boolean): Line {
        fun probe(t0: Double, c0: Double, dir: Int): Double {
            var last = t0; var gap = 0
            var t = t0.roundToInt() + dir
            var steps = 0
            while (steps < reach && gap <= 6) {
                val c = c0.roundToInt()
                var hit = false
                for (d in -2..2) if (on(t, c + d)) { hit = true; break }
                if (hit) { last = t.toDouble(); gap = 0 } else gap++
                t += dir; steps++
            }
            return last
        }
        val s = probe(l.start, l.cross.first(), -1)
        val e = probe(l.end, l.cross.last(), 1)
        val a = ArrayList<Double>(); val c = ArrayList<Double>()
        if (s < l.start - 0.5) { a.add(s); c.add(l.cross.first()) }
        for (i in 0 until l.size) { a.add(l.along[i]); c.add(l.cross[i]) }
        if (e > l.end + 0.5) { a.add(e); c.add(l.cross.last()) }
        return Line(a.toDoubleArray(), c.toDoubleArray(), l.observed)
    }

    /** Paso típico de la rejilla (o null si no es regular) a partir de las separaciones entre rectas vecinas. */
    fun regularPeriod(spacings: List<Double>): Double? {
        if (spacings.size < 4) return null
        val s = spacings.filter { it > 2.0 }.sorted()
        if (s.size < 4) return null
        // Moda: la separación con más separaciones a ±12 % (los picos espurios se reparten; los múltiplos del paso
        // -rectas que faltan- tienen menos apoyo que el propio paso)
        var best = 0; var bestP = 0.0
        var lo = 0; var hi = 0
        for (i in s.indices) {
            val p = s[i]
            while (s[lo] < 0.88 * p) lo++
            while (hi < s.size && s[hi] <= 1.12 * p) hi++
            val n = hi - lo
            if (n > best) { best = n; bestP = p }
        }
        if (best < 3 || best < 0.3 * s.size) return null
        val near = s.filter { it in 0.88 * bestP..1.12 * bestP }
        return near[near.size / 2]
    }

    private fun overlap(a: Line, b: Line): Pair<Double, Double>? {
        val s = max(a.start, b.start); val e = min(a.end, b.end)
        return if (e > s && e - s >= 0.3 * min(a.span, b.span)) s to e else null
    }

    private fun spacing(a: Line, b: Line, r: Pair<Double, Double>): Double {
        val n = 9
        val v = DoubleArray(n) { val t = r.first + (r.second - r.first) * it / (n - 1); b.at(t) - a.at(t) }
        v.sort(); return v[n / 2]
    }

    /** Separación con la siguiente recta (por posición) que la solapa, para cada recta. */
    private fun neighbourSpacings(lines: List<Line>): List<Double> {
        val ls = lines.sortedBy { it.meanCross() }
        val out = ArrayList<Double>()
        for (i in ls.indices) for (j in i + 1 until ls.size) { val r = overlap(ls[i], ls[j]) ?: continue; out.add(spacing(ls[i], ls[j], r)); break }
        return out
    }

    /**
     * Funde las rectas casi coincidentes (a menos de 0.35 pasos, o 3 px, donde se solapan): la recta partida en dos
     * cadenas paralelas o una interpolada encima de una observada. Se queda la de más muestras observadas y se
     * prolonga al tramo de la otra.
     */
    fun dedupe(lines: List<Line>, period: Double): List<Line> {
        val lim = if (period > 0) max(3.0, 0.35 * period) else 3.0
        val ls = lines.sortedBy { it.meanCross() }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in ls.indices) for (j in i + 1 until min(ls.size, i + 4)) {
                val a = ls[i]; val b = ls[j]
                val s = max(a.start, b.start); val e = min(a.end, b.end)
                if (e < s) continue
                val d = if (e - s < 1) abs(a.at(s) - b.at(s)) else spacing(a, b, s to e)
                if (abs(d) > lim) continue
                val (keep, other) = if (a.observed >= b.observed) a to b else b to a
                val al = ArrayList<Double>(); val cr = ArrayList<Double>()
                val off = keep.at((s + e) / 2) - other.at((s + e) / 2)
                for (q in 0 until other.size) if (other.along[q] < keep.start - 0.5) { al.add(other.along[q]); cr.add(other.cross[q] + off) }
                for (q in 0 until keep.size) { al.add(keep.along[q]); cr.add(keep.cross[q]) }
                for (q in 0 until other.size) if (other.along[q] > keep.end + 0.5) { al.add(other.along[q]); cr.add(other.cross[q] + off) }
                ls[i] = Line(al.toDoubleArray(), cr.toDoubleArray(), keep.observed + other.observed)
                ls.removeAt(j)
                ls.sortBy { it.meanCross() }
                changed = true
                break@outer
            }
        }
        return ls
    }

    /**
     * Rejilla regular ([period] > 0): interpola las rectas que faltan entre dos vecinas a k pasos (k = 2..6) y prolonga
     * las rectas cortas por donde sus dos vecinas (a un paso) siguen. Las rectas que no encajan con el paso no se tocan.
     */
    fun fillRegular(lines0: List<Line>, period0: Double = 0.0): List<Line> {
        if (lines0.size < 5) return lines0
        var lines = lines0.sortedBy { it.meanCross() }
        val pairs = ArrayList<Triple<Int, Int, Pair<Double, Double>>>()
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                val r = overlap(lines[i], lines[j]) ?: continue
                pairs.add(Triple(i, j, r)); break
            }
        }
        val period = if (period0 > 0) period0 else regularPeriod(pairs.map { spacing(lines[it.first], lines[it.second], it.third) }) ?: return lines
        val added = ArrayList<Line>()
        for ((i, j, r) in pairs) {
            val a = lines[i]; val b = lines[j]
            val sp = spacing(a, b, r)
            val k = (sp / period).roundToInt()
            if (k < 2 || k > 6 || abs(sp - k * period) > 0.2 * period) continue
            val n = max(2, ((r.second - r.first) / max(4.0, period)).roundToInt() + 1)
            for (q in 1 until k) {
                val f = q.toDouble() / k
                val al = DoubleArray(n) { r.first + (r.second - r.first) * it / (n - 1) }
                added.add(Line(al, DoubleArray(n) { a.at(al[it]) + f * (b.at(al[it]) - a.at(al[it])) }, observed = 0))
            }
        }
        lines = dedupe(lines + added, period)
        // Prolongación de las cortas entre dos vecinas a un paso
        val out = ArrayList<Line>(lines)
        for (i in 1 until lines.size - 1) {
            val l = lines[i]; val a = lines[i - 1]; val b = lines[i + 1]
            val s = max(a.start, b.start); val e = min(a.end, b.end)
            if (e - s < 4 * period) continue
            if (l.start <= s + period && l.end >= e - period) continue
            val mid = (l.start + l.end) / 2
            val ga = l.at(mid) - a.at(mid); val gb = b.at(mid) - l.at(mid)
            if (abs(ga - period) > 0.2 * period || abs(gb - period) > 0.2 * period) continue
            val f = ga / (ga + gb)
            val al = ArrayList<Double>(); val cr = ArrayList<Double>()
            val step = max(4.0, period)
            var t = s
            while (t < l.start - 0.5 * step) { al.add(t); cr.add(a.at(t) + f * (b.at(t) - a.at(t))); t += step }
            for (q in 0 until l.size) { al.add(l.along[q]); cr.add(l.cross[q]) }
            t = l.end + step
            while (t <= e) { al.add(t); cr.add(a.at(t) + f * (b.at(t) - a.at(t))); t += step }
            if (al.size > l.size) out[i] = Line(al.toDoubleArray(), cr.toDoubleArray(), l.observed)
        }
        return out
    }

    /**
     * Prolonga cada recta de [lines] por donde la cruzan las rectas de la otra familia [others] de forma continua
     * (separación entre cruces consecutivos <= 1.6 pasos, también entre el extremo de la recta y el primer cruce): la
     * rejilla sigue bajo la sombra o el texto aunque la huella se perdiera, pero no salta el hueco entre las dos
     * páginas de un cuaderno abierto. La prolongación es recta, con la pendiente de la propia recta si es larga o la
     * de la familia ([slope]) si es corta.
     */
    fun crossExtend(lines: List<Line>, others: List<Line>, slope: Double, alongLen: Double): List<Line> {
        if (others.size < 4) return lines
        return lines.map { l ->
            val c = l.meanCross()
            val pos = others.filter { c >= it.start - 2 && c <= it.end + 2 }.map { it.at(c) }.sorted()
            if (pos.size < 4) return@map l
            val period = regularPeriod((1 until pos.size).map { pos[it] - pos[it - 1] }) ?: return@map l
            var s = l.start
            for (p in pos.asReversed()) { if (p >= s) continue; if (s - p <= 1.6 * period) s = p else break }
            var e = l.end
            for (p in pos) { if (p <= e) continue; if (p - e <= 1.6 * period) e = p else break }
            if (s >= l.start - 1 && e <= l.end + 1) return@map l
            val n = l.size
            val sl = if (l.span >= 0.3 * alongLen && l.size >= 4) fitLine(l).second else slope
            val step = max(4.0, period)
            val a = ArrayList<Double>(); val cr = ArrayList<Double>()
            var t = s
            while (t < l.start - 0.5 * step) { a.add(t); cr.add(l.cross[0] + sl * (t - l.start)); t += step }
            for (q in 0 until n) { a.add(l.along[q]); cr.add(l.cross[q]) }
            t = l.end + step
            while (t < e) { a.add(t); cr.add(l.cross[n - 1] + sl * (t - l.end)); t += step }
            if (e > l.end + 1) { a.add(e); cr.add(l.cross[n - 1] + sl * (e - l.end)) }
            Line(a.toDoubleArray(), cr.toDoubleArray(), l.observed)
        }
    }

    /** Suavizado: cuadrática robusta si la cadena es casi recta (residuo <= 1 px), si no media móvil de 3 puntos. */
    fun smooth(l: Line): Line {
        val n = l.size
        if (n < 3) return l
        if (n >= 5) {
            var w = DoubleArray(n) { 1.0 }
            var coef = DoubleArray(3)
            repeat(3) {
                coef = quadFit(l.along, l.cross, w)
                val res = DoubleArray(n) { abs(l.cross[it] - evalQ(coef, l.along[it])) }
                val med = res.sorted()[n / 2]
                val lim = max(0.6, 3.0 * med)
                w = DoubleArray(n) { if (res[it] <= lim) 1.0 else 0.0 }
            }
            val good = (0 until n).filter { w[it] > 0 }
            if (good.size >= 0.7 * n && good.all { abs(l.cross[it] - evalQ(coef, l.along[it])) <= 1.0 }) {
                return Line(l.along.copyOf(), DoubleArray(n) { evalQ(coef, l.along[it]) }, l.observed)
            }
        }
        return Line(l.along.copyOf(), DoubleArray(n) { i ->
            if (i == 0 || i == n - 1) l.cross[i] else (l.cross[i - 1] + 2 * l.cross[i] + l.cross[i + 1]) / 4.0
        }, l.observed)
    }

    private fun evalQ(c: DoubleArray, t: Double) = c[0] + c[1] * t + c[2] * t * t

    /** Mínimos cuadrados ponderados de c ≈ k0 + k1·t + k2·t² (centrado para estabilidad numérica). */
    private fun quadFit(t: DoubleArray, c: DoubleArray, w: DoubleArray): DoubleArray {
        var sw = 0.0; var st = 0.0
        for (i in t.indices) { sw += w[i]; st += w[i] * t[i] }
        if (sw <= 0) return doubleArrayOf(c.average(), 0.0, 0.0)
        val m = st / sw
        var sc = 1.0
        for (i in t.indices) sc = max(sc, abs(t[i] - m))
        val a = Array(3) { DoubleArray(4) }
        for (i in t.indices) {
            if (w[i] <= 0) continue
            val z = (t[i] - m) / sc
            val p = doubleArrayOf(1.0, z, z * z)
            for (r in 0..2) { for (q in 0..2) a[r][q] += w[i] * p[r] * p[q]; a[r][3] += w[i] * p[r] * c[i] }
        }
        for (col in 0..2) {
            var piv = col
            for (r in col + 1..2) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            if (abs(a[col][col]) < 1e-12) continue
            for (r in 0..2) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (q in col..3) a[r][q] -= f * a[col][q]
            }
        }
        val z = DoubleArray(3) { if (abs(a[it][it]) < 1e-12) 0.0 else a[it][3] / a[it][it] }
        val k2 = z[2] / (sc * sc)
        val k1 = z[1] / sc - 2 * k2 * m
        val k0 = z[0] - z[1] * m / sc + k2 * m * m
        return doubleArrayOf(k0, k1, k2)
    }
}
