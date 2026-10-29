package com.scannerpromax.imaging

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MÁRGENES CURVOS: en una hoja combada (o tras una malla que no tenía guías en toda la página) los inicios de renglón
 * pueden quedar en ARCO aunque los renglones salgan rectos: el margen izquierdo de un texto alineado debe ser una recta
 * VERTICAL. [MarginAlign] sólo corrige la parte lineal (giro, cizalla, trapecio); aquí las columnas claras de inicios (y
 * los finales de un texto justificado) — las guías del texto ([TextGuides.alignedGuides]: cadenas de inicios suavizadas
 * con una curva local, no una recta) — se imponen como verticales: cada guía se lleva a la vertical por su mediana
 * con un desplazamiento horizontal suave d(x, y) que se compone en el mapa de la malla (un único remuestreo).
 *
 * d entre dos guías de la misma altura se interpola en x (margen izquierdo curvo y derecho recto: la fila se estira lo
 * justo); a la izquierda de la primera y a la derecha de la última, constante (la fila se mueve entera); por encima y
 * por debajo de las guías, el valor de la fila más próxima con guías; después se suaviza en vertical (± 1 renglón).
 *
 * Prudente: sólo guías estrictas de impreso (la letra a mano da guías "amplias", que no cuentan), con recorrido ≥ 4
 * renglones, sin desviaciones enormes (> 4 % del ancho: no es un margen), sin duplicados (dos guías a menos de 2,5
 * letras) ni guías que estirarían la fila más de un 6 % respecto de su vecina; nada si la desviación máxima no llega a
 * max(3 px a 1600, 0,3 letras) ni a 4 veces la dispersión de los inicios de su guía. Lógica pura (sin OpenCV; probada en JVM).
 */
internal object MarginCurve {

    /** Activable en el banco de pruebas. */
    @Volatile internal var enabled = true

    /** Guía (x en función de y creciente) ya en el marco de SALIDA, con su peso. */
    class Curve(val x: FloatArray, val y: FloatArray, val weight: Double, val noise: Double = 0.0) {
        val y0 get() = y[0]
        val y1 get() = y[y.size - 1]
        fun at(yy: Double): Double {
            val n = y.size
            if (yy <= y[0]) return x[0].toDouble()
            if (yy >= y[n - 1]) return x[n - 1].toDouble()
            var lo = 0; var hi = n - 1
            while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (y[m] <= yy) lo = m else hi = m }
            val f = (yy - y[lo]) / max(1e-6, (y[hi] - y[lo]).toDouble())
            return x[lo] + (x[hi] - x[lo]) * f
        }
    }

    /** Desplazamiento horizontal d (px) en una rejilla [gw] x [gh] sobre el lienzo [u0, u1] x [v0, v1] de la salida. */
    class Disp(val u0: Double, val v0: Double, val u1: Double, val v1: Double, val gw: Int, val gh: Int, val d: DoubleArray, val maxDev: Double) {
        /** d en (x, y) por interpolación bilineal (constante fuera del lienzo). */
        fun at(x: Double, y: Double): Double {
            val fx = ((x - u0) / (u1 - u0) * (gw - 1)).coerceIn(0.0, gw - 1.0)
            val fy = ((y - v0) / (v1 - v0) * (gh - 1)).coerceIn(0.0, gh - 1.0)
            val i = min(gw - 2, fx.toInt()).coerceAtLeast(0); val j = min(gh - 2, fy.toInt()).coerceAtLeast(0)
            val s = fx - i; val r = fy - j
            val a = d[j * gw + i]; val b = d[j * gw + i + 1]; val c = d[(j + 1) * gw + i]; val e = d[(j + 1) * gw + i + 1]
            return a * (1 - s) * (1 - r) + b * s * (1 - r) + c * (1 - s) * r + e * s * r
        }
    }

    /** Fracción máxima del ancho que puede recorrer una guía en x (más = no es un margen o la malla falló). */
    private const val MAX_DEV_FRAC = 0.04
    /** Estiramiento horizontal máximo entre dos guías de la misma altura. */
    private const val MAX_STRETCH = 0.06
    /** Desviación mínima respecto de la dispersión (rms) de los inicios de la guía. */
    private const val NOISE_K = 4.0

    /**
     * Desplazamiento que deja verticales las [curves] (marco de salida) o null, y el motivo. [med] = altura de letra,
     * [pitch] = paso entre renglones, [scale] = lado largo / 1600 (umbrales en px a 1600).
     */
    fun build(curves: List<Curve>, med: Double, pitch: Double, u0: Double, v0: Double, u1: Double, v1: Double, scale: Double): Pair<Disp?, String> {
        if (!enabled) return null to "apagado"
        val w = u1 - u0; val h = v1 - v0
        if (w < 50 || h < 50 || med <= 0) return null to "lienzo"
        val pt = max(pitch, 1.2 * med)
        val minSpan = max(4 * pt, 0.12 * h)
        // Guías válidas: recorrido suficiente, forma de curva SUAVE (polinomio de grado ≤ 3 con poca dispersión: una
        // guía que salta de una columna a otra o recoge un título no lo es) y desviación de su vertical acotada
        class G(val c: Curve, val xm: Double, val dev: Double, val p: DoubleArray, val ym: Double, val ys: Double) {
            fun at(y: Double): Double {
                val z = ((y.coerceIn(c.y0.toDouble(), c.y1.toDouble())) - ym) / ys
                var r = 0.0; for (k in p.indices.reversed()) r = r * z + p[k]; return r
            }
        }
        val cand = ArrayList<G>()
        var dropped = 0
        val maxRms = 0.12 * med + 0.5
        for (c0 in curves) {
            // Sin los extremos (± 1,5 renglones): el suavizado de la guía arrastra ahí un inicio mal enlazado (título,
            // otra columna) y la curva se doblaría en la punta
            val cut = 1.5 * pt
            val ii = c0.x.indices.filter { c0.y[it] >= c0.y0 + cut && c0.y[it] <= c0.y1 - cut }
            if (ii.size < 4) continue
            val c = Curve(FloatArray(ii.size) { c0.x[ii[it]] }, FloatArray(ii.size) { c0.y[ii[it]] }, c0.weight, c0.noise)
            if (c.y1 - c.y0 < minSpan) continue
            val span = (c.y1 - c.y0).toDouble()
            val deg = when { span >= 0.45 * h -> 3; span >= 8 * pt -> 2; else -> 1 }
            val fit = polyFit(c, deg, floor = max(1.0, 0.1 * med))
            if (DewarpMath.trace) println("  curva guía n=${c.x.size} ruido=%.2f grado=$deg rms=%s quedan=%s pts=".format(java.util.Locale.ROOT, c.noise, fit?.rms?.let { "%.2f".format(java.util.Locale.ROOT, it) }, fit?.n) + (c.x.indices step max(1, c.x.size / 25)).joinToString(" ") { "%.0f:%.1f".format(java.util.Locale.ROOT, c.y[it], c.x[it]) })
            // (muchos atípicos o dispersión de curva no suave: no es un margen limpio)
            if (fit == null || fit.rms > maxRms || fit.n < 0.8 * c.x.size) { dropped++; continue }
            // Tramo de la guía sin los atípicos de los extremos
            val idx = c.x.indices.filter { fit.keep[it] }
            val ct = Curve(FloatArray(idx.size) { c.x[idx[it]] }, FloatArray(idx.size) { c.y[idx[it]] }, c.weight, c.noise)
            if (ct.y1 - ct.y0 < minSpan) continue
            val ym = (c.y0 + c.y1) / 2.0; val ys = max(1.0, span / 2)
            val g0 = G(ct, 0.0, 0.0, fit.coef, ym, ys)
            val vals = DoubleArray(ct.x.size) { g0.at(ct.y[it].toDouble()) }.sorted()
            val xm = vals[vals.size / 2]
            var dev = 0.0
            for (v in vals) dev = max(dev, abs(v - xm))
            if (vals.last() - vals.first() > MAX_DEV_FRAC * w) { dropped++; continue }
            // Una desviación del orden de la dispersión de sus inicios (cursiva, sangrías de una letra) no cuenta: la guía
            // queda como columna recta (ancla, d = 0)
            if (dev < NOISE_K * c.noise) { cand.add(G(ct, xm, 0.0, doubleArrayOf(xm), ym, ys)); continue }
            cand.add(G(ct, xm, dev, fit.coef, ym, ys))
        }
        if (cand.isEmpty()) return null to "sin guías${if (dropped > 0) " ($dropped desviadas)" else ""}"
        // Duplicados y guías incompatibles (estirarían la fila): gana la de más peso
        val acc = ArrayList<G>()
        for (g in cand.sortedByDescending { it.c.weight * (it.c.y1 - it.c.y0) }) {
            var ok = true
            for (a in acc) {
                val lo = max(a.c.y0, g.c.y0); val hi = min(a.c.y1, g.c.y1)
                if (hi <= lo) continue
                val dx = abs(a.xm - g.xm)
                if (dx < 2.5 * med) { ok = false; break }
                var worst = 0.0
                var yy = lo.toDouble()
                while (yy <= hi) { worst = max(worst, abs((a.at(yy) - a.xm) - (g.at(yy) - g.xm))); yy += pt }
                if (worst > MAX_STRETCH * dx) { ok = false; break }
            }
            if (ok) acc.add(g) else dropped++
        }
        // Actúa sólo si una guía LARGA (≥ 6 renglones y ≥ 20 % del alto) está curva: una columna corta (anuncio, pie de
        // foto) acompaña la corrección pero no la decide
        val trigSpan = max(6 * pt, 0.2 * h)
        val maxDev = acc.filter { it.c.y1 - it.c.y0 >= trigSpan }.maxOfOrNull { it.dev } ?: 0.0
        val minDev = max(3.0 * scale, 0.3 * med)
        if (maxDev < minDev) return null to "recto (desv. %.1f px)".format(java.util.Locale.ROOT, maxDev)
        // Rejilla
        val step = max(4.0, 8.0 * scale)
        val gw = max(2, ceil(w / step).toInt() + 1); val gh = max(2, ceil(h / step).toInt() + 1)
        val d = DoubleArray(gw * gh)
        val has = BooleanArray(gh)
        val ext = 2 * pt
        val px = ArrayList<DoubleArray>()
        for (j in 0 until gh) {
            val y = v0 + j * h / (gh - 1)
            px.clear()
            for (g in acc) if (y >= g.c.y0 - ext && y <= g.c.y1 + ext) px.add(doubleArrayOf(g.xm, g.at(y) - g.xm))
            if (px.isEmpty()) continue
            has[j] = true
            px.sortBy { it[0] }
            for (i in 0 until gw) {
                val x = u0 + i * w / (gw - 1)
                d[j * gw + i] = when {
                    x <= px[0][0] -> px[0][1]
                    x >= px[px.size - 1][0] -> px[px.size - 1][1]
                    else -> {
                        var k = 0
                        while (px[k + 1][0] < x) k++
                        val f = (x - px[k][0]) / max(1e-9, px[k + 1][0] - px[k][0])
                        px[k][1] + (px[k + 1][1] - px[k][1]) * f
                    }
                }
            }
        }
        // Filas sin guías: la fila más próxima que las tiene
        val near = IntArray(gh) { -1 }
        for (j in 0 until gh) {
            if (has[j]) { near[j] = j; continue }
            var best = -1
            for (k in 0 until gh) if (has[k] && (best < 0 || abs(k - j) < abs(best - j))) best = k
            near[j] = best
        }
        for (j in 0 until gh) if (!has[j]) System.arraycopy(d, near[j] * gw, d, j * gw, gw)
        // Suavizado vertical (± 1 renglón): sin escalones donde cambia el juego de guías
        val r = max(1, (pt / (h / (gh - 1))).roundToInt())
        val sm = DoubleArray(d.size)
        for (i in 0 until gw) for (j in 0 until gh) {
            var s = 0.0; var n = 0
            for (k in max(0, j - r)..min(gh - 1, j + r)) { s += d[k * gw + i]; n++ }
            sm[j * gw + i] = s / n
        }
        val det = acc.joinToString(" ") { g -> "[x=%.0f y=%.0f..%.0f d=%.1f]".format(java.util.Locale.ROOT, g.xm, g.c.y0, g.c.y1, g.dev) }
        return Disp(u0, v0, u1, v1, gw, gh, sm, maxDev) to "guías=${acc.size}${if (dropped > 0) " (descartadas $dropped)" else ""} desv=%.1f px $det".format(java.util.Locale.ROOT, maxDev)
    }

    /** Ajuste de [polyFit]: coeficientes, puntos que quedan ([keep]) y su rms. */
    class PolyFit(val coef: DoubleArray, val keep: BooleanArray, val n: Int, val rms: Double)

    /**
     * Polinomio x(z) de grado [deg] (z = (y − centro)/semirrecorrido) por mínimos cuadrados con dos descartes de
     * atípicos (residuo > máx(3·1,48·MAD, [floor])): un inicio mal enlazado en un extremo no tuerce la curva. null si
     * quedan pocos puntos.
     */
    internal fun polyFit(c: Curve, deg: Int, floor: Double = 1.0): PolyFit? {
        val n = c.x.size
        val ym = (c.y0 + c.y1) / 2.0; val ys = max(1.0, (c.y1 - c.y0) / 2.0)
        val z = DoubleArray(n) { (c.y[it] - ym) / ys }
        var keep = BooleanArray(n) { true }
        var coef = DoubleArray(deg + 1); var rms = 0.0; var cnt = 0
        repeat(3) { pass ->
            val m = deg + 1
            val a = Array(m) { DoubleArray(m + 1) }
            cnt = 0
            for (i in 0 until n) {
                if (!keep[i]) continue
                cnt++
                val pw = DoubleArray(m); pw[0] = 1.0; for (k in 1 until m) pw[k] = pw[k - 1] * z[i]
                for (r in 0 until m) { for (q in 0 until m) a[r][q] += pw[r] * pw[q]; a[r][m] += pw[r] * c.x[i] }
            }
            if (cnt < deg + 3) return null
            coef = solve(a, m) ?: return null
            var ss = 0.0
            val res = DoubleArray(n)
            for (i in 0 until n) { var v = 0.0; for (k in coef.indices.reversed()) v = v * z[i] + coef[k]; res[i] = c.x[i] - v; if (keep[i]) ss += res[i] * res[i] }
            rms = kotlin.math.sqrt(ss / cnt)
            if (pass < 2) {
                val ab = (0 until n).filter { keep[it] }.map { abs(res[it]) }.sorted()
                val thr = max(3 * 1.4826 * ab[ab.size / 2], floor)
                keep = BooleanArray(n) { abs(res[it]) <= thr }
            }
        }
        return PolyFit(coef, keep, cnt, rms)
    }

    private fun solve(a: Array<DoubleArray>, m: Int): DoubleArray? {
        for (c in 0 until m) {
            var p = c; for (r in c + 1 until m) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (r in 0 until m) if (r != c) { val f = a[r][c] / a[c][c]; for (q in c..m) a[r][q] -= f * a[c][q] }
        }
        return DoubleArray(m) { a[it][m] / a[it][it] }
    }

    /**
     * Mapa inverso normalizado de una salida de [ow] x [oh] (rejilla [gw] x [gh], ver [GridDewarp.Model]) recompuesto con
     * [f]: nuevo mapa(P) = mapa(f(P)), f en px de esa salida; bilineal con extrapolación lineal fuera de la rejilla.
     */
    fun composeMap(map: FloatArray, gw: Int, gh: Int, ow: Double, oh: Double, f: (Double, Double, DoubleArray) -> Unit): FloatArray {
        val out = FloatArray(map.size)
        val o = DoubleArray(2)
        fun at(i: Int, j: Int, c: Int) = map[2 * (j * gw + i) + c].toDouble()
        for (b in 0 until gh) for (a in 0 until gw) {
            val px = a * (ow - 1) / (gw - 1); val py = b * (oh - 1) / (gh - 1)
            f(px, py, o)
            val gx = o[0] * (gw - 1) / (ow - 1); val gy = o[1] * (gh - 1) / (oh - 1)
            val i0 = kotlin.math.floor(gx).toInt().coerceIn(0, gw - 2); val j0 = kotlin.math.floor(gy).toInt().coerceIn(0, gh - 2)
            val fx = gx - i0; val fy = gy - j0
            for (c in 0..1) {
                val v = at(i0, j0, c) * (1 - fx) * (1 - fy) + at(i0 + 1, j0, c) * fx * (1 - fy) +
                    at(i0, j0 + 1, c) * (1 - fx) * fy + at(i0 + 1, j0 + 1, c) * fx * fy
                out[2 * (b * gw + a) + c] = v.toFloat()
            }
        }
        return out
    }
}
