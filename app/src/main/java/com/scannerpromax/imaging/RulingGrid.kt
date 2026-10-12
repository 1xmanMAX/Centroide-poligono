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

    /**
     * Cuadrícula como RED REGULAR ([lattice]): en cada familia las rectas están a un paso casi constante; sólo se aceptan
     * las rectas en posiciones de la red (las candidatas a medio paso, duplicadas o trazos de escritura alineados se
     * descartan) y se completan las que faltan. Las rectas cortas se prolongan por donde las cruza la otra familia.
     * Una familia sin red fiable queda vacía (mejor sin cuadrícula que con una red errónea).
     */
    fun extractGrid(maskH: ByteArray?, maskV: ByteArray?, w: Int, h: Int, dark: ByteArray? = null, level: Double = 0.0): Grid {
        val fh = if (maskH != null) lattice(maskH, w, h, true, dark, level) else Family.EMPTY
        val fv = if (maskV != null) lattice(maskV, w, h, false, dark, level) else Family.EMPTY
        var hs = fh.lines; var vs = fv.lines
        if (hs.isNotEmpty() && vs.isNotEmpty()) {
            val h2 = crossExtend(hs, vs, fh.slope, w.toDouble()); val v2 = crossExtend(vs, hs, fv.slope, h.toDouble())
            hs = h2; vs = v2
        }
        hs = dedupe(hs, fh.period); vs = dedupe(vs, fv.period)
        return Grid(hs.map { smooth(it) }, vs.map { smooth(it) }, fh.period, fv.period, fh.slope, fv.slope)
    }

    // =====================================================================================
    // Red regular
    // =====================================================================================

    /**
     * Tramo de red en una franja: picos observados [k] -> [pos] (k entero relativo) y ajuste pos ≈ [a] + [b]·k.
     * [lo]..[hi]: extensión perpendicular cubierta (de la primera a la última recta observada).
     */
    class Seg(val strip: Int, val k: IntArray, val pos: DoubleArray, val a: Double, val b: Double) {
        val kMin: Int get() = k.first()
        val kMax: Int get() = k.last()
        val lo: Double get() = pos.first()
        val hi: Double get() = pos.last()

        /** Posición de la recta k: la observada si la hay; si no, interpolada entre las observadas vecinas (sigue la
         *  perspectiva local) o extrapolada con el paso. */
        fun at(kk: Int): Double {
            val i = k.binarySearch(kk)
            if (i >= 0) return pos[i]
            val ins = -i - 1
            return when {
                ins == 0 -> pos[0] - b * (k[0] - kk)
                ins >= k.size -> pos[k.size - 1] + b * (kk - k[k.size - 1])
                else -> { val f = (kk - k[ins - 1]).toDouble() / (k[ins] - k[ins - 1]); pos[ins - 1] + f * (pos[ins] - pos[ins - 1]) }
            }
        }

        fun observed(kk: Int) = k.binarySearch(kk) >= 0
    }

    /** Tolerancia (fracción del paso) para aceptar un pico como recta de la red. */
    const val LATTICE_TOL = 0.2

    /** Máximo de rectas seguidas sin pico dentro de un tramo de red de una franja. */
    const val LATTICE_MAX_MISSING = 2

    /**
     * Tramos de red de los picos [peaks] (ordenados) de una franja con paso aproximado [p0]: hasta 3 redes (dos páginas,
     * cada una con su fase). Para cada una se busca el paso (±8 %) y la fase con más picos a <= [LATTICE_TOL]·paso de
     * fase + k·paso (un pico por k), se ajusta por mínimos cuadrados y se parte donde faltan más de
     * [LATTICE_MAX_MISSING] rectas seguidas. Los picos fuera de la red dentro de un tramo (medias rectas inventadas,
     * escritura) se descartan.
     */
    fun stripSegments(peaks: DoubleArray, p0: Double, strip: Int = 0): List<Seg> {
        val out = ArrayList<Seg>()
        var rem = peaks.sorted()
        repeat(3) {
            if (rem.size < 3) return out
            var bestScore = 0.0; var bestP = 0.0; var bestPh = 0.0
            var pp = 0.92 * p0
            while (pp <= 1.0801 * p0) {
                for (anchor in rem) {
                    val byK = HashMap<Int, Double>()
                    for (x in rem) {
                        val kk = Math.round((x - anchor) / pp).toInt()
                        val r = abs(x - anchor - kk * pp) / pp
                        if (r <= LATTICE_TOL) { val o = byK[kk]; if (o == null || r < o) byK[kk] = r }
                    }
                    var sc = 0.0
                    for (r in byK.values) sc += 1.0 - 1.5 * r
                    if (sc > bestScore) { bestScore = sc; bestP = pp; bestPh = anchor }
                }
                pp += 0.01 * p0
            }
            if (bestScore < 2.5) return out
            // Crecimiento desde el ancla: cada recta siguiente se predice con el paso LOCAL (media móvil de las
            // separaciones observadas): sigue un paso que varía suavemente y se detiene donde faltan más de
            // LATTICE_MAX_MISSING rectas seguidas (fin de la página o de la cuadrícula)
            val obsK = java.util.TreeMap<Int, Double>()
            obsK[0] = bestPh
            for (dir in intArrayOf(1, -1)) {
                var bl = bestP; var lastK = 0; var lastX = bestPh; var miss = 0; var kk = dir; var nObs = 1
                while (miss <= LATTICE_MAX_MISSING) {
                    val pred = lastX + bl * (kk - lastK)
                    // (con el paso ya medido, la tolerancia se estrecha: entre rectas vecinas el paso casi no cambia)
                    var best = Double.NaN; var bd = (if (nObs >= 2) 0.12 else LATTICE_TOL) * bl
                    for (x in rem) { val d = abs(x - pred); if (d <= bd) { bd = d; best = x } }
                    if (!best.isNaN()) {
                        val sp = (best - lastX) / (kk - lastK)
                        bl = (0.6 * bl + 0.4 * sp).coerceIn(0.95 * bl, 1.05 * bl).coerceIn(0.85 * p0, 1.15 * p0)
                        obsK[kk] = best; lastK = kk; lastX = best; miss = 0; nObs++
                    } else miss++
                    kk += dir
                }
            }
            val ks0 = obsK.keys.toIntArray()
            if (ks0.size < 3) {
                rem = rem.filter { abs(it - bestPh) > 0.5 * bestP }
                return@repeat
            }
            val k0 = ks0[0]
            val sk = IntArray(ks0.size) { ks0[it] - k0 }
            val sx = DoubleArray(ks0.size) { obsK[ks0[it]]!! }
            val mk = sk.average(); val mx = sx.average()
            var skk = 0.0; var skx = 0.0
            for (i in sk.indices) { skk += (sk[i] - mk) * (sk[i] - mk); skx += (sk[i] - mk) * (sx[i] - mx) }
            val bb = if (skk > 0) (skx / skk).coerceIn(0.85 * p0, 1.15 * p0) else bestP
            out.add(Seg(strip, sk, sx, mx - bb * mk, bb))
            val lo = sx.first() - 0.5 * bb; val hi = sx.last() + 0.5 * bb
            rem = rem.filter { x -> x < lo || x > hi }
        }
        return out
    }

    /**
     * Familia como red regular: franjas a lo largo (~1/32 del lado), tramos de red por franja ([stripSegments]),
     * enlace de los tramos de franjas vecinas con la misma fase (índice k común: posición ≈ fase + k·paso, con paso y
     * fase que varían suavemente) en ZONAS (una por página / bloque de cuadrícula) y, en cada zona, una recta por cada k
     * entre la primera y la última observada: la posición observada donde hay pico y la interpolada de las vecinas
     * donde no. Zonas con paso incoherente (dispersión > 8 %) o poco apoyo se descartan.
     */
    fun lattice(mask: ByteArray, w: Int, h: Int, horizontal: Boolean, dark: ByteArray? = null, level: Double = 0.0): Family {
        val alongLen = if (horizontal) w else h
        val crossLen = if (horizontal) h else w
        if (alongLen < 32 || crossLen < 16) return Family.EMPTY
        val stripW = max(10, alongLen / 32)
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
        val gaps = ArrayList<Double>()
        for (p in peaks) for (i in 1 until p.size) gaps.add(p[i] - p[i - 1])
        val p0 = regularPeriod(gaps) ?: return Family.EMPTY
        // Con la oscuridad completa: picos de su perfil por franja (las rectas que la huella no recogió -sombras,
        // tramos tenues- también), unidos a los de la huella
        if (dark != null && level > 0) {
            val cap = (3.0 * level + 10.0).toInt()
            val r = max(2, (0.35 * p0).roundToInt())
            for (s in 0 until nStrips) {
                val a0 = s * stripW; val a1 = min(alongLen, a0 + stripW)
                java.util.Arrays.fill(prof, 0.0)
                if (horizontal) {
                    for (c in 0 until crossLen) { var sum = 0; val row = c * w; for (a in a0 until a1) sum += min(cap, dark[row + a].toInt() and 0xff); prof[c] = sum.toDouble() }
                } else {
                    for (a in a0 until a1) { val row = a * w; for (c in 0 until crossLen) prof[c] += min(cap, dark[row + c].toInt() and 0xff).toDouble() }
                }
                val len = (a1 - a0).toDouble()
                for (c in 0 until crossLen) prof[c] /= len
                val dp = darkPeaks(prof, r, max(1.5, 0.15 * level))
                peaks[s] = mergePeaks(peaks[s], dp)
            }
        }
        val segs = ArrayList<Seg>()
        for (s in 0 until nStrips) segs.addAll(stripSegments(peaks[s], p0, s))
        if (segs.isEmpty()) return Family.EMPTY
        val zones = linkSegments(segs, centers, p0, maxSkip = max(2, (0.25 * alongLen / stripW).toInt()))
        val lines = ArrayList<Line>()
        val periods = ArrayList<Double>()
        for (z in zones) {
            val zl = zoneLines(z, centers, p0) ?: continue
            for (sg in z.keys) periods.add(sg.b)
            lines.addAll(zl.map { l -> refineEnds(l, stripW) { a, c -> a in 0 until alongLen && c in 0 until crossLen && on(a, c) } })
        }
        if (lines.size < 2) return Family.EMPTY
        val period = periods.sorted()[periods.size / 2]
        val slope = familySlope(lines, alongLen.toDouble())
        return Family(lines, slope, period)
    }

    /**
     * Agrupa los tramos en zonas de red continua: el tramo B (franja posterior, hasta [maxSkip] franjas sin tramo entre
     * medias) continúa al A si se solapan en perpendicular, tienen paso parecido (±10 %) y una recta de A cae a
     * <= 0.25 pasos de una de B; el desfase de índices queda fijado por esa coincidencia. Devuelve, por zona, el
     * desplazamiento de índice de cada tramo (k global = k local + desplazamiento).
     */
    fun linkSegments(segs: List<Seg>, centers: DoubleArray, p0: Double, maxSkip: Int = 2): List<Map<Seg, Int>> {
        val n = segs.size
        val parent = IntArray(n) { it }; val off = IntArray(n)   // off: índice relativo al padre
        fun find(i: Int): Int {
            val p = parent[i]
            if (p == i) return i
            val r = find(p)
            off[i] += off[p]; parent[i] = r
            return r
        }
        fun offOf(i: Int): Int { find(i); return if (parent[i] == i) 0 else off[i] }
        val byStrip = segs.indices.groupBy { segs[it].strip }
        val strips = byStrip.keys.sorted()
        // candidatos (A, B, dk = kB_global - kA_global relación) ordenados por calidad
        class Cand(val i: Int, val j: Int, val dk: Int, val q: Double)
        val cands = ArrayList<Cand>()
        for (sa in strips) {
            for (i in byStrip[sa]!!) {
                val A = segs[i]
                for (sb in strips) {
                    if (sb <= sa || sb - sa > maxSkip + 1) continue
                    for (j in byStrip[sb]!!) {
                        val B = segs[j]
                        if (abs(A.b - B.b) > 0.1 * p0) continue
                        val lo = max(A.lo, B.lo); val hi = min(A.hi, B.hi)
                        if (hi - lo < 1.5 * p0) continue
                        val c = (lo + hi) / 2
                        val ka = Math.round((c - A.a) / A.b).toInt()
                        val xa = A.at(ka)
                        val kbf = (xa - B.a) / B.b
                        val kb = Math.round(kbf).toInt()
                        val d = abs(xa - B.at(kb))
                        // tolerancia: 0.25 pasos (algo más si hay franjas sin tramo entre medias: la recta se inclina)
                        val tol = (0.18 + 0.01 * (sb - sa - 1)).coerceAtMost(0.25) * p0
                        if (d > tol) continue
                        cands.add(Cand(i, j, ka - kb, d / p0 + 0.05 * (sb - sa - 1)))
                    }
                }
            }
        }
        cands.sortBy { it.q }
        for (cd in cands) {
            val ri = find(cd.i); val rj = find(cd.j)
            val oi = offOf(cd.i); val oj = offOf(cd.j)
            // k_glob(i) = k + oi (relativo a ri); queremos k_glob(j, ka-dk... ) : kB + dk ≡ kA  -> offset_j = offset_i + dk
            if (ri == rj) continue   // ya enlazados (si el desfase no coincide, se ignora)
            // unir rj bajo ri: off[rj] tal que oj + off[rj] = oi + dk
            parent[rj] = ri; off[rj] = oi + cd.dk - oj
        }
        val zones = HashMap<Int, HashMap<Seg, Int>>()
        for (i in 0 until n) { val r = find(i); zones.getOrPut(r) { HashMap() }[segs[i]] = offOf(i) }
        return zones.values.toList()
    }

    /**
     * Modelo suave de la red de una zona: pos(k, t) = a0 + a1·u + a2·u² + k·(b0 + b1·u + b2·u²) + c·k² (u = t
     * normalizado a -1..1): fase y paso que varían suavemente a lo largo (curvatura, perspectiva residual) y paso que
     * cambia poco entre la primera y la última recta.
     */
    class ZoneModel(private val coef: DoubleArray, private val t0: Double, private val ts: Double) {
        fun at(k: Int, t: Double): Double {
            val u = (t - t0) / ts
            return coef[0] + coef[1] * u + coef[2] * u * u + k * (coef[3] + coef[4] * u + coef[5] * u * u) + coef[6] * k * k
        }
        fun period(t: Double): Double { val u = (t - t0) / ts; return coef[3] + coef[4] * u + coef[5] * u * u }
    }

    private fun fitModel(obs: List<DoubleArray>, w: BooleanArray, t0: Double, ts: Double): ZoneModel? {
        val n = 7
        val a = Array(n) { DoubleArray(n + 1) }
        var cnt = 0
        for ((i, o) in obs.withIndex()) {
            if (!w[i]) continue
            cnt++
            val k = o[0]; val u = (o[1] - t0) / ts
            val f = doubleArrayOf(1.0, u, u * u, k, k * u, k * u * u, k * k)
            for (r in 0 until n) { for (q in 0 until n) a[r][q] += f[r] * f[q]; a[r][n] += f[r] * o[2] }
        }
        if (cnt < 8) return null
        // regularización leve (los términos de orden alto no se disparan con pocas franjas)
        for (r in intArrayOf(2, 5, 6)) a[r][r] += 1e-3 * cnt
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            if (abs(a[col][col]) < 1e-12) return null
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (q in col..n) a[r][q] -= f * a[col][q]
            }
        }
        return ZoneModel(DoubleArray(n) { a[it][n] / a[it][it] }, t0, ts)
    }

    /**
     * Ajuste robusto del modelo de la zona y corrección de los enlaces: el tramo cuya numeración no encaja (mediana del
     * residuo > 0.15 pasos) se renumera ±1 si así encaja, o se descarta (picos de otra cosa: borde de una sombra,
     * escritura). Devuelve el modelo y los desplazamientos corregidos, o null.
     */
    fun fitZone(z0: Map<Seg, Int>, centers: DoubleArray, p0: Double): Pair<ZoneModel, Map<Seg, Int>>? {
        val z = HashMap(z0)
        val ts0 = z.keys.minOf { centers[it.strip] }; val ts1 = z.keys.maxOf { centers[it.strip] }
        val t0 = (ts0 + ts1) / 2; val ts = max(1.0, (ts1 - ts0) / 2)
        var model: ZoneModel? = null
        repeat(3) { iter ->
            val keys = z.keys.toList()
            val obs = ArrayList<DoubleArray>()
            for (sg in keys) { val o = z[sg]!!; for (i in sg.k.indices) obs.add(doubleArrayOf((sg.k[i] + o).toDouble(), centers[sg.strip], sg.pos[i])) }
            var w = BooleanArray(obs.size) { true }
            var m: ZoneModel? = null
            repeat(3) {
                m = fitModel(obs, w, t0, ts) ?: return null
                val r = DoubleArray(obs.size) { abs(obs[it][2] - m!!.at(obs[it][0].toInt(), obs[it][1])) }
                val med = r.sorted()[r.size / 2]
                val lim = max(0.12 * p0, 3.0 * med)
                w = BooleanArray(obs.size) { r[it] <= lim }
            }
            model = m
            if (iter == 2) return@repeat
            var changed = false
            for (sg in keys) {
                val o = z[sg]!!
                fun medRes(d: Int): Double {
                    val r = DoubleArray(sg.k.size) { abs(sg.pos[it] - m!!.at(sg.k[it] + o + d, centers[sg.strip])) }
                    r.sort(); return r[r.size / 2]
                }
                val r0 = medRes(0)
                if (r0 <= 0.15 * p0) continue
                val rm = medRes(-1); val rp = medRes(1)
                val (best, d) = listOf(rm to -1, rp to 1).minByOrNull { it.first }!!
                if (best <= 0.15 * p0) z[sg] = o + d else z.remove(sg)
                changed = true
            }
            if (z.keys.map { it.strip }.toSet().size < 3) return null
            if (!changed) return model!! to z
        }
        return model?.let { it to z }
    }

    /**
     * Rectas de una zona: una por k global entre el mínimo y el máximo observados. En cada franja de la zona en la que
     * la recta k cae dentro de un tramo (±1 recta) toma la posición observada si el pico encaja con el modelo
     * (<= 0.2 pasos) y, si no, la del modelo corregida con el residuo de las rectas observadas vecinas; entre franjas,
     * la recta es continua. null si la zona no es fiable (pocas franjas o rectas, paso incoherente).
     */
    fun zoneLines(z0: Map<Seg, Int>, centers: DoubleArray, p0: Double): List<Line>? {
        if (z0.keys.map { it.strip }.toSet().size < 3) return null
        val (model, z) = fitZone(z0, centers, p0) ?: return null
        val bs = z.keys.map { it.b }.sorted()
        val med = bs[bs.size / 2]
        val dev = bs.map { abs(it - med) }.sorted()[bs.size / 2]
        if (dev > 0.08 * med) return null
        var kLo = Int.MAX_VALUE; var kHi = Int.MIN_VALUE; var obs = 0
        for ((sg, o) in z) { kLo = min(kLo, sg.kMin + o); kHi = max(kHi, sg.kMax + o); obs += sg.k.size }
        if (kHi - kLo < 2 || obs < 8) return null
        // por franja, los tramos de la zona (puede haber dos: rectas tapadas por la escritura entre medias)
        val byStrip = java.util.TreeMap<Int, MutableList<Pair<Seg, Int>>>()
        for ((sg, o) in z) byStrip.getOrPut(sg.strip) { ArrayList() }.add(sg to o)
        val out = ArrayList<Line>()
        for (K in kLo..kHi) {
            val al = ArrayList<Double>(); val cr = ArrayList<Double>(); var nObs = 0
            for ((s, list) in byStrip) {
                val t = centers[s]
                var cover: Pair<Seg, Int>? = null
                for ((sg, o) in list) { val kk = K - o; if (kk >= sg.kMin - 1 && kk <= sg.kMax + 1) { cover = sg to o; if (sg.observed(kk)) break } }
                val (sg, o) = cover ?: continue
                val kk = K - o
                val mv = model.at(K, t)
                val i = sg.k.binarySearch(kk)
                val v = if (i >= 0 && abs(sg.pos[i] - mv) <= 0.2 * p0) { nObs++; sg.pos[i] } else {
                    // residuo de las observadas vecinas (hasta 2 por cada lado) del mismo tramo
                    var sr = 0.0; var nr = 0
                    for (j in sg.k.indices) {
                        if (abs(sg.k[j] - kk) > 2) continue
                        val r = sg.pos[j] - model.at(sg.k[j] + o, t)
                        if (abs(r) <= 0.2 * p0) { sr += r; nr++ }
                    }
                    mv + if (nr > 0) sr / nr else 0.0
                }
                al.add(t); cr.add(v)
            }
            // (las rectas sin ningún pico, entre dos observadas, también: la red se completa)
            if (al.size < 2) continue
            // Sin temblor: el residuo respecto del modelo (la curvatura real que el modelo no recoge) se suaviza a lo
            // largo (1-2-3-2-1); el pico de cada franja tiene ±1 px de ruido y la recta dibujada ondulaba
            val n = al.size
            val res = DoubleArray(n) { cr[it] - model.at(K, al[it]) }
            val wts = doubleArrayOf(1.0, 2.0, 3.0, 2.0, 1.0)
            val crs = DoubleArray(n) { i ->
                var sw = 0.0; var sr = 0.0
                for (d in -2..2) { val j = i + d; if (j in 0 until n) { sw += wts[d + 2]; sr += wts[d + 2] * res[j] } }
                model.at(K, al[i]) + sr / sw
            }
            out.add(Line(al.toDoubleArray(), crs, nObs))
        }
        return out
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

    /**
     * Picos de un perfil de OSCURIDAD media por fila: realce sobre el fondo local (mínimo en ±[r]) >= [thr], máximo local
     * en ±2, posición por centroide del realce. Las rectas finas dan picos estrechos; la escritura, bultos anchos que la
     * red descarta después.
     */
    fun darkPeaks(prof: DoubleArray, r: Int, thr: Double): DoubleArray {
        val n = prof.size
        val e = DoubleArray(n)
        // mínimo local (ventana deslizante simple: perfiles de <= ~2000 valores)
        for (c in 0 until n) { var m = Double.MAX_VALUE; for (j in max(0, c - r)..min(n - 1, c + r)) if (prof[j] < m) m = prof[j]; e[c] = prof[c] - m }
        val out = ArrayList<Double>()
        var c = 0
        while (c < n) {
            val v = e[c]
            var isMax = v >= thr
            if (isMax) for (d in -2..2) { val j = c + d; if (d != 0 && j in 0 until n && (e[j] > v || (d < 0 && e[j] == v))) { isMax = false; break } }
            if (isMax) {
                var sw = 0.0; var sp = 0.0
                for (d in -2..2) { val j = c + d; if (j in 0 until n) { val ww = max(0.0, e[j] - 0.3 * v); sw += ww; sp += ww * j } }
                out.add(if (sw > 0) sp / sw else c.toDouble())
                c += 3; continue
            }
            c++
        }
        return out.toDoubleArray()
    }

    /** Unión de dos listas de picos ordenadas: los de [b] a menos de 2.5 px de uno de [a] se descartan (manda [a]). */
    fun mergePeaks(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = ArrayList<Double>(a.size + b.size)
        for (x in a) out.add(x)
        for (x in b) if (a.none { abs(it - x) < 2.5 }) out.add(x)
        out.sort()
        return out.toDoubleArray()
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
