package com.scannerpromax.imaging

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lógica pura (sin OpenCV, probada en JVM) de [TableLines]: seguimiento subpíxel de una línea de tabla sobre una banda
 * de la página, estadística de continuidad, decisión conservadora (¿es una línea real de tabla?) y dibujo
 * anti-aliasing de la línea limpia sobre una banda de la salida.
 *
 * Convención de la banda: `at(i, j)` = brillo (0..255) en la posición `i` A LO LARGO de la línea (x para las
 * horizontales, y para las verticales) y `j` A TRAVÉS de ella (y para las horizontales), con `j` en `0 until across`.
 */
internal object TableLinesMath {

    /** Acceso a la banda (sin cajas: los enteros no se envuelven en cada lectura). */
    fun interface Band { fun at(i: Int, j: Int): Int }

    /** Medida de cada posición a lo largo: contraste [amp] (papel local - centro), centro subpíxel, anchura equivalente. */
    class Profile(val a0: Int, val n: Int) {
        val amp = DoubleArray(n)
        val center = DoubleArray(n) { Double.NaN }
        val width = DoubleArray(n)
        /** Oscuridad media a ambos lados de la línea (la menor de las dos): alta dentro de una foto o de un relleno. */
        val side = DoubleArray(n)
        /** Oscuridad del lado MÁS oscuro: un renglón de texto tiene las letras pegadas a un lado de su "línea". */
        val side2 = DoubleArray(n)
        /** Objeto oscuro grande sobre la línea (barra, relleno): ahí no se dibuja. */
        val blocked = BooleanArray(n)
        /** Bloqueada por un objeto que CRUZA la línea (oscuro a ambos lados). */
        val crossed = BooleanArray(n)
        fun valid(k: Int) = !center[k].isNaN()
    }

    /** Polinomio y(i) = c0 + c1·t + c2·t², t = (i - mid) / scale. */
    class Poly(val c0: Double, val c1: Double, val c2: Double, val mid: Double, val scale: Double) {
        fun at(i: Double): Double { val t = (i - mid) / scale; return c0 + c1 * t + c2 * t * t }
        /** Pendiente dy/di. */
        fun slope(i: Double): Double { val t = (i - mid) / scale; return (c1 + 2 * c2 * t) / scale }
        companion object { fun line(y0: Double, slope: Double, mid: Double) = Poly(y0, slope * 100.0, 0.0, mid, 100.0) }
    }

    /** Parámetros (px de la imagen fuente). */
    class Params(
        val search: Int,          // semiancho de búsqueda alrededor de la predicción
        val paperExtra: Int,      // margen extra a cada lado para medir el papel local
        val noiseAbs: Double,     // umbral absoluto de contraste (ruido)
        val maxThick: Double,     // grosor máximo de una línea de tabla
        val gapMax: Int,          // hueco máximo al prolongar los extremos
    )

    fun measure(at: Band, across: Int, nAlong: Int, a0: Int, a1: Int, pred: (Int) -> Double, search: Int, paperExtra: Int): Profile {
        val p = Profile(a0, a1 - a0 + 1)
        val win = DoubleArray(2 * (search + paperExtra) + 3)
        for (k in 0 until p.n) {
            val i = a0 + k
            measureOne(at, across, nAlong, i, pred(i), search, paperExtra, win)?.let { r ->
                if (r[5] > 0) { p.blocked[k] = true; if (r[5] > 1.5) p.crossed[k] = true; return@let }
                p.amp[k] = r[0]; p.center[k] = r[1]; p.width[k] = r[2]; p.side[k] = r[3]; p.side2[k] = r[4]
            }
        }
        return p
    }

    /** (amp, centro, anchura, lado) en la posición [i] o null si no hay pico aislado (borde de un relleno, fuera). */
    fun measureOne(at: Band, across: Int, nAlong: Int, i: Int, yp: Double, search: Int, paperExtra: Int, buf: DoubleArray? = null): DoubleArray? {
        val c = yp.roundToInt()
        val lo = c - search - paperExtra; val hi = c + search + paperExtra
        if (c - search < 0 || c + search >= across) return null
        val n = hi - lo + 1
        val v = if (buf != null && buf.size >= n) buf else DoubleArray(n)
        val im = max(0, i - 1); val ip = min(nAlong - 1, i + 1)
        var paper = 0.0
        for (q in 0 until n) {
            val j = lo + q
            if (j < 0 || j >= across) { v[q] = Double.NaN; continue }
            val s = (at.at(im, j) + at.at(i, j) + at.at(ip, j)) / 3.0
            v[q] = s
            if (s > paper) paper = s
        }
        // pico de oscuridad dentro de la búsqueda
        val s0 = search + paperExtra - search; val s1 = search + paperExtra + search   // índices de [c-search, c+search]
        var best = -1.0; var bq = -1
        for (q in s0..s1) { val d = paper - v[q]; if (d > best) { best = d; bq = q } }
        if (bq < 0 || best <= 0) return null
        val half = best * 0.5
        var r0 = bq; while (r0 - 1 >= 0 && !v[r0 - 1].isNaN() && paper - v[r0 - 1] >= half) r0--
        var r1 = bq; while (r1 + 1 < n && !v[r1 + 1].isNaN() && paper - v[r1 + 1] >= half) r1++
        // la región a media altura no debe tocar el borde de la búsqueda (escalón de un relleno, foto)
        // región oscura que llena la ventana (barra de un gráfico, relleno, letra pegada): posición BLOQUEADA
        val lowT = r0 == 0 || r0 < s0 - paperExtra / 2; val highT = r1 >= n - 1 || r1 > s1 + paperExtra / 2
        // (2 = el objeto cruza la línea: oscuro a ambos lados, como la barra de un gráfico)
        if (lowT || highT) return doubleArrayOf(best, Double.NaN, 0.0, best, best, if (lowT && highT) 2.0 else 1.0)
        // anchura equivalente: área (hasta 1/4 del pico) / pico; centro = centroide de esa misma región (el de la
        // región a media altura se sesga hacia el píxel más cubierto)
        var e0 = r0; while (e0 - 1 >= 0 && !v[e0 - 1].isNaN() && paper - v[e0 - 1] >= best * 0.25) e0--
        var e1 = r1; while (e1 + 1 < n && !v[e1 + 1].isNaN() && paper - v[e1 + 1] >= best * 0.25) e1++
        var area = 0.0; var sj = 0.0
        for (q in e0..e1) { val d = max(0.0, paper - v[q]); area += d; sj += d * q }
        val center = lo + sj / area
        // oscuridad de los lados (más allá de la línea)
        fun sideMean(qa: Int, qb: Int): Double {
            var s = 0.0; var m = 0
            for (q in max(0, qa)..min(n - 1, qb)) if (!v[q].isNaN()) { s += paper - v[q]; m++ }
            return if (m == 0) 0.0 else s / m
        }
        val gap = 2
        val above = sideMean(e0 - gap - paperExtra, e0 - gap)
        val below = sideMean(e1 + gap, e1 + gap + paperExtra)
        return doubleArrayOf(best, center, area / best, min(above, below), max(above, below), 0.0)
    }

    /** Ajuste por mínimos cuadrados ponderados de grado [deg] (0..2). */
    fun fit(xs: DoubleArray, ys: DoubleArray, ws: DoubleArray, m: Int, deg: Int, mid: Double, scale: Double): Poly? {
        if (m < deg + 2) return null
        val a = Array(3) { DoubleArray(3) }; val b = DoubleArray(3)
        val d = deg + 1
        for (k in 0 until m) {
            val t = (xs[k] - mid) / scale; val w = ws[k]
            val pw = doubleArrayOf(1.0, t, t * t)
            for (r in 0 until d) { b[r] += w * pw[r] * ys[k]; for (c in 0 until d) a[r][c] += w * pw[r] * pw[c] }
        }
        val sol = solve(a, b, d) ?: return null
        return Poly(sol[0], if (d > 1) sol[1] else 0.0, if (d > 2) sol[2] else 0.0, mid, scale)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray, n: Int): DoubleArray? {
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** Resultado del seguimiento de una línea. */
    class Track(
        val a0: Int, val a1: Int,
        /** Centro (px fuente, coordenada transversal) en cada posición a0..a1 (ajuste + corrección local suave). */
        val centers: DoubleArray,
        val thick: Double,
        val amp: Double,
        /** Fracción de posiciones con tinta de la línea en la FOTO (contraste suficiente y centrado). */
        val inkFrac: Double,
        /** Hueco más largo (px) sin tinta. */
        val maxGap: Int,
        /** Raya discontinua impresa a propósito (trazos y huecos regulares y limpios). */
        val dashed: Boolean,
        /** Desviación típica (px) de los centros respecto del ajuste global (las rayas a mano se apartan). */
        val rms: Double,
        /** Fracción de posiciones con los dos lados oscuros (dentro de una foto o de un relleno). */
        val embedded: Double,
        /** Fracción de posiciones con tinta pegada a un lado (renglón de texto tomado por línea). */
        val touching: Double = 0.0,
        /** Trazo continuo más largo (px). */
        val maxRun: Int = 0,
        /** Trazos y huecos de longitudes irregulares (raya continua rota por la cámara, no puntos guía ni texto alineado). */
        val irregular: Boolean = true,
        /** Posiciones (a0..a1) donde no se dibuja. */
        val noDraw: BooleanArray? = null,
        /** Fracción de posiciones cruzadas por objetos oscuros grandes y número de tramos cruzados. */
        val blockedFrac: Double = 0.0,
        val blockedRuns: Int = 0,
    ) {
        /** Cruzada por objetos oscuros grandes (barras de un gráfico, columnas sombreadas). */
        val chartLike get() = blockedRuns >= 2 && blockedFrac >= 0.06
        fun drawable(i: Double): Boolean { val nd = noDraw ?: return true; val k = (i - a0).roundToInt(); return k !in nd.indices || !nd[k] }
        val length get() = a1 - a0 + 1
        fun centerAt(i: Double): Double {
            val k = (i - a0).coerceIn(0.0, (centers.size - 1).toDouble())
            val k0 = floor(k).toInt(); val k1 = min(centers.size - 1, k0 + 1); val f = k - k0
            return centers[k0] * (1 - f) + centers[k1] * f
        }
        val mean get() = centers.average()
    }

    /**
     * Sigue la línea sembrada por [seed] entre [s0]..[s1] (posiciones a lo largo): mide, ajusta (robusto), prolonga los
     * extremos mientras siga habiendo tinta alineada (huecos <= [Params.gapMax]) y vuelve a medir con búsqueda
     * estrecha alrededor del ajuste. null si no hay suficiente apoyo.
     */
    fun track(at: Band, across: Int, nAlong: Int, seed: Poly, s0: Int, s1: Int, prm: Params): Track? =
        trackAll(at, across, nAlong, seed, s0, s1, prm).maxByOrNull { it.inkFrac * it.length }

    /** Como [track], pero devuelve cada tramo separado por huecos grandes (p. ej. dos tablas alineadas) por separado. */
    fun trackAll(at: Band, across: Int, nAlong: Int, seed: Poly, s0: Int, s1: Int, prm: Params): List<Track> {
        val b0 = s0.coerceIn(0, nAlong - 1); val b1 = s1.coerceIn(b0, nAlong - 1)
        var prof = measure(at, across, nAlong, b0, b1, { seed.at(it.toDouble()) }, prm.search, prm.paperExtra)
        var poly = robustFit(prof, prm, seed, 2.0 * prm.search) ?: return emptyList()
        // prolongación de los extremos
        val one = DoubleArray(2 * (prm.search + prm.paperExtra) + 3)
        val thr0 = threshold(prof, prm)
        var lo = b0; var hi = b1
        run {
            // extremos reales dentro del tramo sembrado
            var f = -1; var l = -1
            for (k in 0 until prof.n) if (isInk(prof, k, thr0, poly, prm.search.toDouble())) { if (f < 0) f = k; l = k }
            if (f < 0) return emptyList()
            lo = b0 + f; hi = b0 + l
        }
        val tight = max(2, ceil(prm.maxThick / 2).toInt() + 1)
        for (dir in intArrayOf(-1, 1)) {
            val end = if (dir < 0) lo else hi
            val sl = poly.slope(end.toDouble()); val y0 = poly.at(end.toDouble())
            // marcha más allá del extremo hasta un hueco > gapMax; el nuevo extremo es el más lejano con >= 50 % de
            // tinta entre el extremo anterior y él (un palo de una letra alineado tras un hueco no prolonga la línea)
            val flags = ArrayList<Boolean>()
            var last = 0; var d = 1
            while (end + dir * d in 0 until nAlong && d - last <= prm.gapMax) {
                val i = end + dir * d
                val yp = y0 + sl * (i - end)
                val r = measureOne(at, across, nAlong, i, yp, tight, prm.paperExtra, one)
                val ok = r != null && r[0] >= thr0 && abs(r[1] - yp) <= tight && r[2] <= prm.maxThick * 1.5 && r[3] < r[0] * 0.5 && r[4] < r[0] * 0.5
                flags.add(ok); if (ok) last = d
                d++
            }
            var best = 0; var cnt = 0
            for (q in flags.indices) { if (flags[q]) { cnt++; if (cnt * 2 >= q + 1) best = q + 1 } }
            if (dir < 0) lo = end - best else hi = end + best
        }
        // medida final estrecha alrededor del ajuste (extrapolado linealmente fuera del tramo ajustado)
        val pf = poly; val fa = b0.toDouble(); val fb = b1.toDouble()
        val pred: (Int) -> Double = { i ->
            when {
                i < fa -> pf.at(fa) + pf.slope(fa) * (i - fa)
                i > fb -> pf.at(fb) + pf.slope(fb) * (i - fb)
                else -> pf.at(i.toDouble())
            }
        }
        return finish(at, across, nAlong, lo, hi, pred, tight, prm, true)
    }

    private fun finish(at: Band, across: Int, nAlong: Int, lo: Int, hi: Int, pred: (Int) -> Double, tight: Int, prm: Params, split: Boolean): List<Track> {
        if (!split) return listOfNotNull(finishOne(at, across, nAlong, lo, hi, pred, tight, prm, null))
        val pieces = ArrayList<IntArray>()
        val t = finishOne(at, across, nAlong, lo, hi, pred, tight, prm, pieces)
        if (pieces.isEmpty()) return listOfNotNull(t)
        return pieces.mapNotNull { finishOne(at, across, nAlong, lo + it[0], lo + it[1], pred, tight, prm, null) }
    }

    /**
     * Medida final de la línea entre [lo] y [hi] alrededor de [pred]. Con [split], si un hueco SIN tinta supera
     * [Params.gapMax] (dos tablas alineadas una sobre otra, un palo de letra alineado más allá del borde), se queda el
     * tramo con más tinta y se vuelve a medir: una línea no salta de una tabla a otra.
     */
    private fun finishOne(at: Band, across: Int, nAlong: Int, lo: Int, hi: Int, pred: (Int) -> Double, tight: Int, prm: Params, pieces: MutableList<IntArray>?): Track? {
        val prof = measure(at, across, nAlong, lo, hi, pred, tight, prm.paperExtra)
        val thr = threshold(prof, prm)
        val n = prof.n
        val ink = BooleanArray(n)
        val xs = DoubleArray(n); val ys = DoubleArray(n); val ws = DoubleArray(n); var m = 0
        val widths = ArrayList<Double>(); val amps = ArrayList<Double>()
        var emb = 0; var touch = 0; var cand = 0
        for (k in 0 until n) {
            if (!prof.valid(k)) continue
            val i = lo + k
            if (prof.amp[k] >= thr && abs(prof.center[k] - pred(i)) <= tight && prof.width[k] <= prm.maxThick * 1.5) {
                cand++
                if (prof.side[k] >= prof.amp[k] * 0.5) { emb++; prof.blocked[k] = true }
                // tinta pegada a un lado (letras): esa posición no cuenta como línea (renglones, bordes de columnas
                // de texto alineado, tachados)
                if (prof.side2[k] >= prof.amp[k] * TOUCH_K) { touch++; continue }
                ink[k] = true; xs[m] = i.toDouble(); ys[m] = prof.center[k]; ws[m] = prof.amp[k]; m++
                widths.add(prof.width[k]); amps.add(prof.amp[k])
            }
        }
        if (m < 8) return null
        if (pieces != null) {
            // tramos de tinta separados por huecos > gapMax (cada uno se mide aparte); también recorta los extremos
            // sin tinta que la prolongación pudo dejar
            var a = -1; var last = -1; var cnt = 0
            val ps = ArrayList<IntArray>()
            for (k in 0..n) {
                val isInk = k < n && ink[k]
                if (k == n || (isInk && last >= 0 && k - last > prm.gapMax)) {
                    if (a >= 0 && cnt >= 8) ps.add(intArrayOf(a, last))
                    a = -1; cnt = 0
                }
                if (isInk) { if (a < 0) a = k; last = k; cnt++ }
            }
            if (ps.size > 1 || (ps.size == 1 && (ps[0][0] > 0 || ps[0][1] < n - 1))) { pieces.addAll(ps); return null }
        }
        val mid = (lo + hi) / 2.0; val sc = max(1.0, (hi - lo) / 2.0)
        val deg = if (hi - lo >= nAlong * 0.2) 2 else 1
        var g = fit(xs, ys, ws, m, deg, mid, sc) ?: return null
        // reajuste robusto: fuera los centros apartados (texto que toca la línea)
        run {
            val keep = BooleanArray(m) { abs(ys[it] - g.at(xs[it])) <= max(1.0, prm.maxThick / 3) }
            var q = 0
            val x2 = DoubleArray(m); val y2 = DoubleArray(m); val w2 = DoubleArray(m)
            for (k in 0 until m) if (keep[k]) { x2[q] = xs[k]; y2[q] = ys[k]; w2[q] = ws[k]; q++ }
            if (q >= 8) fit(x2, y2, w2, q, deg, mid, sc)?.let { g = it }
        }
        var ss = 0.0; var sn = 0
        val res = DoubleArray(n) { Double.NaN }
        for (k in 0 until m) {
            val r = ys[k] - g.at(xs[k])
            if (abs(r) <= max(1.5, prm.maxThick / 2)) { res[(xs[k] - lo).toInt()] = r; ss += r * r; sn++ }
        }
        val rms = if (sn > 0) sqrt(ss / sn) else 99.0
        // corrección local suave (curvatura residual): media móvil de los residuos, acotada
        val win = max(15, (hi - lo) / 20)
        val corr = smoothResiduals(res, win, 2.0)
        // dispersión respecto de la curva suavizada (un renglón de texto salta de letra en letra; una línea, no)
        var sl2 = 0.0; var sn2 = 0
        for (k in 0 until m) { val q = (xs[k] - lo).toInt(); val e = (ys[k] - g.at(xs[k]) - corr[q]).coerceIn(-3.0, 3.0); sl2 += e * e; sn2++ }
        val rmsLocal = if (sn2 > 0) sqrt(sl2 / sn2) else 99.0
        val centers = DoubleArray(n) { g.at((lo + it).toDouble()) + corr[it] }
        // continuidad: huecos y regularidad de los trazos
        // (huecos de 1-2 px dentro de un trazo no lo parten)
        val inkC = ink.copyOf()
        run {
            var k = 0
            while (k < n) {
                if (!inkC[k]) { var e = k; while (e < n && !inkC[e]) e++; if (k > 0 && e < n && e - k <= 2) for (q in k until e) inkC[q] = true; k = e } else k++
            }
        }
        var maxGap = 0; var run = 0
        val dashes = ArrayList<Int>(); val gaps = ArrayList<Int>()
        var cur = inkC[0]; var len = 0
        for (k in 0 until n) {
            if (!inkC[k]) { run++; maxGap = max(maxGap, run) } else run = 0
            if (inkC[k] == cur) len++ else { (if (cur) dashes else gaps).add(len); cur = inkC[k]; len = 1 }
        }
        (if (cur) dashes else gaps).add(len)
        val maxRun = dashes.maxOrNull() ?: 0
        val irregular = irregularRuns(dashes, gaps)
        val inkFrac = m.toDouble() / n
        widths.sort(); amps.sort()
        val thick = widths[widths.size / 2]
        val amp = amps[amps.size / 2]
        // amplitud en los huecos (limpios = raya discontinua impresa)
        var gs = 0.0; var gn = 0
        for (k in 0 until n) if (!ink[k]) { gs += if (prof.valid(k)) prof.amp[k] else 0.0; gn++ }
        val gapAmp = if (gn > 0) gs / gn else 0.0
        val dashed = isRegularDash(dashes, gaps, thick, inkFrac, gapAmp, amp)
        // posiciones bloqueadas (ensanchadas el grosor + 2 px): la línea no se pinta encima de barras ni rellenos
        val noDraw = BooleanArray(n)
        val dil = ceil(thick).toInt() + 2
        for (k in 0 until n) if (prof.blocked[k]) for (q in max(0, k - dil)..min(n - 1, k + dil)) noDraw[q] = true
        return Track(lo, hi, centers, thick, amp, inkFrac, maxGap, dashed, rmsLocal, emb.toDouble() / max(1, cand), touch.toDouble() / max(1, cand), maxRun, irregular, noDraw)
    }

    /** (fracción, número) de tramos de [flags] de al menos [minRun] posiciones seguidas. */
    fun crossedRuns(flags: BooleanArray, minRun: Int): Pair<Double, Int> {
        var tot = 0; var runs = 0; var k = 0
        while (k < flags.size) {
            if (!flags[k]) { k++; continue }
            var e = k; while (e < flags.size && flags[e]) e++
            if (e - k >= minRun) { tot += e - k; runs++ }
            k = e
        }
        return (if (flags.isEmpty()) 0.0 else tot.toDouble() / flags.size) to runs
    }

    private fun isInk(p: Profile, k: Int, thr: Double, poly: Poly, tol: Double): Boolean =
        p.valid(k) && p.amp[k] >= thr && abs(p.center[k] - poly.at((p.a0 + k).toDouble())) <= tol

    /** Umbral de tinta de la línea: el del ruido o el 30 % del contraste típico de la línea (lo mayor). */
    fun threshold(p: Profile, prm: Params): Double {
        val a = ArrayList<Double>()
        for (k in 0 until p.n) if (p.valid(k) && p.amp[k] >= prm.noiseAbs) a.add(p.amp[k])
        if (a.isEmpty()) return prm.noiseAbs
        a.sort()
        return max(prm.noiseAbs, 0.3 * a[(a.size * 0.6).toInt().coerceAtMost(a.size - 1)])
    }

    private fun robustFit(p: Profile, prm: Params, seed: Poly, tol: Double): Poly? {
        val thr = threshold(p, prm)
        val xs = DoubleArray(p.n); val ys = DoubleArray(p.n); val ws = DoubleArray(p.n); var m = 0
        for (k in 0 until p.n) if (isInk(p, k, thr, seed, tol) && p.width[k] <= prm.maxThick * 1.5) {
            xs[m] = (p.a0 + k).toDouble(); ys[m] = p.center[k]; ws[m] = p.amp[k]; m++
        }
        if (m < 8) return null
        val mid = p.a0 + p.n / 2.0; val sc = max(1.0, p.n / 2.0)
        var g = fit(xs, ys, ws, m, 1, mid, sc) ?: return null
        repeat(2) {
            var q = 0
            val x2 = DoubleArray(m); val y2 = DoubleArray(m); val w2 = DoubleArray(m)
            for (k in 0 until m) if (abs(ys[k] - g.at(xs[k])) <= max(1.5, prm.maxThick / 2)) { x2[q] = xs[k]; y2[q] = ys[k]; w2[q] = ws[k]; q++ }
            if (q < 8) return null
            g = fit(x2, y2, w2, q, 1, mid, sc) ?: return null
        }
        return g
    }

    /** Media móvil (ventana [win]) de los residuos conocidos, interpolada en los huecos y acotada a ±[cap]. */
    fun smoothResiduals(res: DoubleArray, win: Int, cap: Double): DoubleArray {
        val n = res.size
        val cs = DoubleArray(n + 1); val cn = IntArray(n + 1)
        for (k in 0 until n) { cs[k + 1] = cs[k] + (if (res[k].isNaN()) 0.0 else res[k]); cn[k + 1] = cn[k] + (if (res[k].isNaN()) 0 else 1) }
        val h = win / 2
        return DoubleArray(n) { k ->
            val a = max(0, k - h); val b = min(n, k + h + 1)
            val c = cn[b] - cn[a]
            if (c < max(3, win / 4)) 0.0 else ((cs[b] - cs[a]) / c).coerceIn(-cap, cap)
        }
    }

    /**
     * Raya discontinua IMPRESA: >= 6 trazos, trazos y huecos de longitud regular (CV < 0.3), huecos de al menos el
     * grosor y LIMPIOS (sin tinta tenue: la raya continua pálida que la cámara rompe sí tiene contraste en los huecos).
     */
    /**
     * Trazos/huecos IRREGULARES: los puntos guía (".....") y los palos de letras alineadas en columna se repiten con
     * longitudes casi iguales; una raya continua que la cámara o la binarización rompió, no.
     */
    fun irregularRuns(dashes: List<Int>, gaps: List<Int>): Boolean {
        val d = dashes.drop(1).dropLast(1); val g = gaps.drop(1).dropLast(1)
        if (d.size < 4 || g.size < 4) return true
        fun cv(l: List<Int>): Double { val m = l.average(); if (m <= 0) return 9.0; return sqrt(l.sumOf { (it - m) * (it - m) } / l.size) / m }
        return cv(d) >= 0.5 || cv(g) >= 0.5
    }

    fun isRegularDash(dashes: List<Int>, gaps: List<Int>, thick: Double, inkFrac: Double, gapAmp: Double, amp: Double): Boolean {
        if (inkFrac > 0.8 || dashes.size < 6) return false
        val d = dashes.drop(1).dropLast(1); val g = gaps.drop(1).dropLast(1)
        if (d.size < 4 || g.size < 4) return false
        fun cv(l: List<Int>): Double { val m = l.average(); if (m <= 0) return 9.0; return sqrt(l.sumOf { (it - m) * (it - m) } / l.size) / m }
        return cv(d) < 0.3 && cv(g) < 0.3 && g.average() >= max(2.0, thick) && gapAmp < 0.2 * amp
    }

    // ------------------------------------------------------------------------------------------------- decisión

    /** Línea candidata con su medida y lo que ya hay en la salida. */
    class Cand(
        val hor: Boolean, val a0: Double, val a1: Double, val c: Double, val tr: Track,
        /** Fracción de la línea que la salida ya muestra con tinta (antes de redibujar). */
        var outCov: Double = 0.0,
    ) { val len get() = a1 - a0 }

    /**
     * ¿Qué candidatas son líneas reales de tabla? (conservador). [w], [h] = tamaño de la página, [lh] = altura de letra.
     * Una línea vale si es recta (rms bajo), fina, no está dentro de una foto/relleno, tiene apoyo en la foto y:
     *  - es una regla LARGA (>= 30 % del lado) con apoyo alto, o
     *  - forma parte de una tabla: >= 2 paralelas solapadas (filas/columnas) o cruza >= 2 perpendiculares aceptables.
     * Las rayas discontinuas impresas a propósito no se tocan. Una línea de la que la salida no muestra casi nada sólo
     * se redibuja si es inequívoca (casi continua, contraste >= [FAINT_MIN] de la tinta) y está dentro de la estructura
     * de una tabla (>= 2 paralelas o >= 2 perpendiculares); la transparencia del reverso es más pálida y no pasa.
     */
    /** Tinta a un lado (fracción del contraste de la línea) a partir de la cual una posición no cuenta como línea. */
    @JvmField var TOUCH_K = 0.4

    /** Dispersión máxima (px) de los centros alrededor de la curva suavizada. */
    @JvmField var RMS_MAX = 0.7
    @JvmField var RMS_MAX_CONT = 1.2
    /** Contraste mínimo (fracción del de la tinta de la página) de una línea que la salida no muestra. */
    @JvmField var FAINT_MIN = 0.12

    /** ¿El centro de [l] está dentro del rectángulo [z] (px x0, y0, x1, y1)? */
    fun inside(l: Cand, z: DoubleArray): Boolean {
        val m = (l.a0 + l.a1) / 2
        val x = if (l.hor) m else l.c; val y = if (l.hor) l.c else m
        return x >= z[0] && x <= z[2] && y >= z[1] && y <= z[3]
    }

    private fun tr0(l: Cand) = l.tr.inkFrac >= 0.6 && l.tr.maxRun >= 0.3 * l.len

    fun accept(c: List<Cand>, w: Int, h: Int, lh: Double, maxThick: Double, inkRef: Double = 0.0, zones: List<DoubleArray> = emptyList()): BooleanArray {
        val n = c.size
        // dentro de una tabla indicada (Jev; zonas en px x0, y0, x1, y1): reglas más permisivas, la tabla ya está probada
        val zoned = BooleanArray(n) { k ->
            val l = c[k]; val m = (l.a0 + l.a1) / 2
            val x = if (l.hor) m else l.c; val y = if (l.hor) l.c else m
            zones.any { z -> x >= z[0] && x <= z[2] && y >= z[1] && y <= z[3] &&
                // tabla de rayas discontinuas impresas: sus trazos sueltos no deben unirse en líneas continuas
                c.none { d -> d.tr.dashed && inside(d, z) } }
        }
        // tenue pero inequívoca: casi continua en la foto y con contraste apreciable respecto de la tinta de la página
        // (la transparencia del reverso es mucho más pálida)
        val strong = BooleanArray(n) { k -> val tr = c[k].tr; tr.inkFrac >= 0.85 && tr.maxRun >= 0.5 * c[k].len && tr.amp >= FAINT_MIN * inkRef }
        val basic = BooleanArray(n) { k ->
            val l = c[k]; val tr = l.tr
            val side = if (l.hor) w else h
            val minLen = if (l.hor) max(4.0 * lh, 0.06 * w) else max(2.5 * lh, 0.025 * h)
            // trazo continuo largo, o raya rota de forma irregular con bastante tinta
            val solid = tr.maxRun >= 2.5 * lh || tr.maxRun >= 0.85 * l.len
            // una línea casi continua admite más dispersión (contraste bajo = centro más ruidoso); el texto no lo es
            val rmsMax = if (tr.inkFrac >= 0.85 && tr.maxRun >= 0.5 * l.len) RMS_MAX_CONT else RMS_MAX
            l.len >= minLen && !tr.dashed && tr.thick <= maxThick && tr.rms <= rmsMax &&
                tr.embedded < 0.3 && tr.touching < 0.7 && tr.inkFrac >= 0.35 && l.len <= side &&
                (solid || (tr.irregular && tr.inkFrac >= 0.45 && tr.maxRun >= lh && l.len >= 6 * lh))
        }
        val tolOf = { l: Cand -> max(3.0 * l.tr.thick, 0.6 * lh) }
        fun crosses(a: Cand, b: Cand): Boolean {
            if (a.hor == b.hor) return false
            val t = max(tolOf(a), tolOf(b))
            return b.c >= a.a0 - t && b.c <= a.a1 + t && a.c >= b.a0 - t && a.c <= b.a1 + t
        }
        // filas/columnas de una misma tabla: extensión parecida (una palabra subrayada bajo una tabla no lo es) y cerca
        fun parallel(a: Cand, b: Cand): Boolean {
            if (a.hor != b.hor || abs(a.c - b.c) < 2 * max(a.tr.thick, b.tr.thick) + 2 || abs(a.c - b.c) > 15 * lh) return false
            val ov = min(a.a1, b.a1) - max(a.a0, b.a0)
            return ov >= 0.6 * max(a.len, b.len)
        }
        // tenue o rota por la luz dentro de una tabla indicada: recta, fina, sin tocar texto, con algo de tinta seguida
        for (k in 0 until n) if (zoned[k] && !basic[k]) {
            val l = c[k]; val tr = l.tr
            // (una palabra subrayada junto a la tabla no debe pasar: horizontales algo más largas)
            val minLen = if (l.hor) max(4.0 * lh, 0.05 * w) else max(2.5 * lh, 0.03 * h)
            basic[k] = l.len >= minLen && !tr.dashed && tr.thick <= maxThick && tr.rms <= RMS_MAX_CONT && tr.embedded < 0.3 &&
                tr.touching < 0.5 && tr.inkFrac >= 0.25 && (tr.maxRun >= 1.5 * lh || tr.maxRun >= 0.6 * l.len)
        }
        val visible = BooleanArray(n) { basic[it] && c[it].outCov >= 0.1 }
        val support = BooleanArray(n) { visible[it] || (basic[it] && (strong[it] || zoned[it])) }
        val ok = BooleanArray(n)
        for (k in 0 until n) {
            if (!basic[k]) continue
            val l = c[k]
            val side = if (l.hor) w else h
            var par = 0; var cr = 0
            var parS = 0; var crS = 0; var crLong = 0; var parChart = 0
            for (q in 0 until n) if (q != k) {
                if (visible[q]) { if (parallel(l, c[q])) par++; if (crosses(l, c[q])) { cr++; if (c[q].len >= 8 * lh) crLong++ } }
                if (c[q].tr.chartLike && c[q].hor == l.hor && abs(c[q].c - l.c) <= 15 * lh &&
                    min(c[q].a1, l.a1) - max(c[q].a0, l.a0) >= 0.3 * min(c[q].len, l.len)) parChart++
                if (support[q]) { if (parallel(l, c[q])) parS++; if (crosses(l, c[q])) crS++ }
            }
            val longRule = l.len >= 0.3 * side && l.tr.inkFrac >= 0.6
            val table = par >= 2 || cr >= 2
            ok[k] = when {
                // rejilla de un gráfico (barras que la cruzan y ninguna línea fina perpendicular): no se toca. Una
                // tabla con columnas sombreadas sí tiene sus verticales finas
                (l.tr.chartLike || parChart >= 2) && crLong < 2 -> false
                zoned[k] && parS + crS >= 1 && (visible[k] || (tr0(l) && l.tr.amp >= 0.5 * FAINT_MIN * inkRef)) -> true
                visible[k] -> longRule || table
                // la salida no la muestra: sólo dentro de la estructura de una tabla
                strong[k] -> parS >= 2 || crS >= 2
                // ni se ve ni es inequívoca (transparencia del reverso): no
                else -> false
            }
        }
        return ok
    }

    // ------------------------------------------------------------------------------------------------- dibujo

    /**
     * Dibuja la línea limpia sobre una banda de la salida [buf] ([bw] x [bh], [ch] canales intercalados, fila mayor).
     * [hor]: la línea va a lo largo de x. [center](u) = posición transversal (px de la banda) del centro en la
     * posición u a lo largo (px de la banda), para u en [u0, u1]. [thick] = grosor (px de salida). [color] por canal.
     * Anti-aliasing por cobertura; sólo oscurece (el texto que cruza queda encima).
     */
    fun drawBand(buf: ByteArray, bw: Int, bh: Int, ch: Int, hor: Boolean, u0: Double, u1: Double, center: (Double) -> Double, thick: Double, color: IntArray, skip: ((Int) -> Boolean)? = null) {
        val nAlong = if (hor) bw else bh; val nAcross = if (hor) bh else bw
        val half = thick / 2
        val ia = max(0, floor(u0 - 0.5).toInt()); val ib = min(nAlong - 1, ceil(u1 + 0.5).toInt())
        for (u in ia..ib) {
            val endCov = (min(u + 0.5, u1 + 0.5) - max(u - 0.5, u0 - 0.5)).coerceIn(0.0, 1.0)
            if (endCov <= 0 || skip?.invoke(u) == true) continue
            val cy = center(u.toDouble().coerceIn(u0, u1))
            // dentro de un relleno (los dos lados ya oscuros en la salida) no se dibuja
            if (sideDark(buf, bw, ch, hor, u, cy - half - 3, nAcross) && sideDark(buf, bw, ch, hor, u, cy + half + 3, nAcross)) continue
            val ja = max(0, floor(cy - half - 1).toInt()); val jb = min(nAcross - 1, ceil(cy + half + 1).toInt())
            for (j in ja..jb) {
                // cobertura del píxel [j-0.5, j+0.5] por la banda [cy-half, cy+half]
                val cov = (min(j + 0.5, cy + half) - max(j - 0.5, cy - half)).coerceIn(0.0, 1.0) * endCov
                if (cov <= 0.01) continue
                val x = if (hor) u else j; val y = if (hor) j else u
                val o = (y * bw + x) * ch
                for (q in 0 until ch) {
                    val p = buf[o + q].toInt() and 0xFF
                    val nv = (p + (color[q] - p) * cov).roundToInt()
                    if (nv < p) buf[o + q] = nv.toByte()
                }
            }
        }
    }

    private fun sideDark(buf: ByteArray, bw: Int, ch: Int, hor: Boolean, u: Int, j: Double, nAcross: Int): Boolean {
        val jj = j.roundToInt(); if (jj < 0 || jj >= nAcross) return false
        val x = if (hor) u else jj; val y = if (hor) jj else u
        val o = (y * bw + x) * ch
        val l = if (ch >= 3) ((buf[o].toInt() and 0xFF) * 299 + (buf[o + 1].toInt() and 0xFF) * 587 + (buf[o + 2].toInt() and 0xFF) * 114) / 1000 else buf[o].toInt() and 0xFF
        return l < 190
    }

    /**
     * Muestras de la salida a lo largo de la línea: (fracción de posiciones con tinta, color mediano de la tinta).
     * Tinta = luminancia < [inkLum]. Color null si hay menos de [minSamples] píxeles de tinta.
     */
    fun sampleBand(buf: ByteArray, bw: Int, bh: Int, ch: Int, hor: Boolean, u0: Double, u1: Double, center: (Double) -> Double, reach: Double, inkLum: Int = 200, minSamples: Int = 12): Pair<Double, IntArray?> {
        val nAlong = if (hor) bw else bh; val nAcross = if (hor) bh else bw
        val ia = max(0, u0.roundToInt()); val ib = min(nAlong - 1, u1.roundToInt())
        if (ib < ia) return 0.0 to null
        val samples = ArrayList<IntArray>()
        var hit = 0
        for (u in ia..ib) {
            val cy = center(u.toDouble())
            val ja = max(0, floor(cy - reach).toInt()); val jb = min(nAcross - 1, ceil(cy + reach).toInt())
            var any = false
            var bestL = 999; var best: IntArray? = null
            for (j in ja..jb) {
                val x = if (hor) u else j; val y = if (hor) j else u
                val o = (y * bw + x) * ch
                val px = IntArray(ch) { buf[o + it].toInt() and 0xFF }
                val l = if (ch >= 3) (px[0] * 299 + px[1] * 587 + px[2] * 114) / 1000 else px[0]
                if (l < inkLum) { any = true; if (l < bestL) { bestL = l; best = px } }
            }
            if (any) { hit++; samples.add(best!!) }
        }
        val frac = hit.toDouble() / (ib - ia + 1)
        if (samples.size < minSamples) return frac to null
        // color mediano por luminancia (el píxel más oscuro de cada posición: el núcleo de la línea)
        samples.sortBy { if (ch >= 3) it[0] * 299 + it[1] * 587 + it[2] * 114 else it[0] }
        return frac to samples[samples.size / 2]
    }
}
