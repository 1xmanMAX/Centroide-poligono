package com.scannerpromax.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Matemática pura (sin OpenCV, probada en tests JVM) de la restauración de nitidez por zonas ([Deblur]).
 *
 * El desenfoque de cada zona se modela como una gaussiana ANISÓTROPA de covarianza Σ = [[a, b], [b, c]] (px²): cubre el
 * desenfoque de la óptica, el de profundidad de campo (isótropo) y un movimiento corto (alargado en su dirección), y su
 * transformada es exp(-2π² fᵀΣf), sin ceros: la deconvolución regularizada es estable.
 */
internal object DeblurMath {

    /** Covarianza 2x2 simétrica (px²). */
    data class Cov(val a: Double, val b: Double, val c: Double) {
        operator fun plus(o: Cov) = Cov(a + o.a, b + o.b, c + o.c)
        operator fun minus(o: Cov) = Cov(a - o.a, b - o.b, c - o.c)
        operator fun times(k: Double) = Cov(a * k, b * k, c * k)
        /** Varianza media (traza / 2). */
        val meanVar get() = (a + c) / 2
        val maxVar get() = eig().l1
    }

    /** Autovalores (l1 >= l2) y ángulo (rad) del autovector de l1. */
    data class Eig(val l1: Double, val l2: Double, val theta: Double)

    fun Cov.eig(): Eig {
        val tr = a + c; val det = a * c - b * b
        val disc = sqrt(max(tr * tr / 4 - det, 0.0))
        return Eig(tr / 2 + disc, tr / 2 - disc, 0.5 * atan2(2 * b, a - c))
    }

    fun fromEig(l1: Double, l2: Double, theta: Double): Cov {
        val cs = cos(theta); val sn = sin(theta)
        return Cov(l1 * cs * cs + l2 * sn * sn, (l1 - l2) * cs * sn, l1 * sn * sn + l2 * cs * cs)
    }

    /** Aplica f a cada autovalor (recortes, restas) conservando la orientación. */
    inline fun Cov.mapEig(f: (Double) -> Double): Cov { val e = eig(); return fromEig(f(e.l1), f(e.l2), e.theta) }

    /** Direcciones de la normal del borde (0°, 45°, 90°, 135°) de las sigmas direccionales medidas. */
    val DIRS = doubleArrayOf(0.0, PI / 4, PI / 2, 3 * PI / 4)

    /**
     * Covarianza que mejor explica las sigmas medidas en las 4 direcciones de la normal del borde:
     * σ²(φ) = a cos²φ + 2b cosφ sinφ + c sin²φ (mínimos cuadrados ponderados por √n). Con menos de 3 direcciones
     * con datos, isótropa con la media; sin datos, null.
     */
    fun fitCov(sig: DoubleArray, count: DoubleArray): Cov? {
        val rows = ArrayList<DoubleArray>(4); val rhs = ArrayList<Double>(4); val wts = ArrayList<Double>(4)
        for (k in 0 until 4) {
            if (count[k] <= 0 || !sig[k].isFinite()) continue
            val cs = cos(DIRS[k]); val sn = sin(DIRS[k])
            rows.add(doubleArrayOf(cs * cs, 2 * cs * sn, sn * sn)); rhs.add(sig[k] * sig[k]); wts.add(sqrt(count[k]))
        }
        if (rows.isEmpty()) return null
        if (rows.size < 3) {
            var s = 0.0; var ws = 0.0
            for (i in rows.indices) { s += rhs[i] * wts[i]; ws += wts[i] }
            val v = s / ws
            return Cov(v, 0.0, v)
        }
        val ata = Array(3) { DoubleArray(3) }; val atb = DoubleArray(3)
        for (i in rows.indices) {
            val w2 = wts[i] * wts[i]
            for (r in 0 until 3) {
                atb[r] += rows[i][r] * rhs[i] * w2
                for (c in 0 until 3) ata[r][c] += rows[i][r] * rows[i][c] * w2
            }
        }
        val x = solve(ata, atb) ?: return null
        return Cov(x[0], x[1], x[2])
    }

    /** Sigma de un píxel de borde a partir del cociente R = |∇I| / |∇(G_sb * I)|: σ = sb / √(R² - 1). */
    fun sigmaFromRatio(r: Double, sb: Double): Double = sb / sqrt(max(r * r - 1.0, 0.02))

    /**
     * Jacobiano d(salida)/d(origen) (fila mayor: [dxo/dxs, dxo/dys, dyo/dxs, dyo/dys]) en el punto de salida (x, y)
     * de la homografía [hinv] salida -> origen (3x3 fila mayor).
     */
    fun jacobianOutFromSrc(hinv: DoubleArray, x: Double, y: Double): DoubleArray {
        val w = hinv[6] * x + hinv[7] * y + hinv[8]
        val sx = (hinv[0] * x + hinv[1] * y + hinv[2]) / w
        val sy = (hinv[3] * x + hinv[4] * y + hinv[5]) / w
        // d(origen)/d(salida)
        val j00 = (hinv[0] - sx * hinv[6]) / w; val j01 = (hinv[1] - sx * hinv[7]) / w
        val j10 = (hinv[3] - sy * hinv[6]) / w; val j11 = (hinv[4] - sy * hinv[7]) / w
        val det = j00 * j11 - j01 * j10
        if (abs(det) < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 1.0)
        return doubleArrayOf(j11 / det, -j01 / det, -j10 / det, j00 / det)
    }

    /**
     * Desenfoque de la salida que produce un desenfoque isótropo [sigmaSrc] del origen llevado por el jacobiano [j]
     * (d salida / d origen): Σ = σ² J Jᵀ. Donde la rectificación AMPLIÓ (zona alejada) el desenfoque crece.
     */
    fun geometricCov(j: DoubleArray, sigmaSrc: Double): Cov {
        val s2 = sigmaSrc * sigmaSrc
        return Cov(s2 * (j[0] * j[0] + j[1] * j[1]), s2 * (j[0] * j[2] + j[1] * j[3]), s2 * (j[2] * j[2] + j[3] * j[3]))
    }

    /**
     * Peso (0..1) de la medida según la fuerza de la perspectiva: cociente entre la mayor y la menor escala lineal local
     * de la rectificación; 0 por debajo de 1.08 (casi frontal), 1 desde 1.23.
     */
    fun perspectiveWeight(areaScale: DoubleArray): Double {
        val mn = areaScale.minOrNull() ?: return 0.0; val mx = areaScale.maxOrNull() ?: return 0.0
        if (mn <= 0) return 0.0
        return ((mx / mn - 1.08) / 0.15).coerceIn(0.0, 1.0)
    }

    /** Mayor valor singular de [j] (ampliación máxima local). */
    fun maxStretch(j: DoubleArray): Double {
        val a = j[0] * j[0] + j[2] * j[2]; val b = j[0] * j[1] + j[2] * j[3]; val c = j[1] * j[1] + j[3] * j[3]
        return sqrt(Cov(a, b, c).eig().l1.coerceAtLeast(0.0))
    }

    /**
     * Superficie cuadrática c0 + c1 x + c2 y + c3 x² + c4 xy + c5 y² ajustada con IRLS (Huber): el desenfoque por
     * profundidad o por movimiento varía suavemente sobre la hoja; las teselas atípicas (contenido distinto) pesan poco.
     * Devuelve los coeficientes, o null si el sistema es singular.
     */
    fun quadFit(xs: DoubleArray, ys: DoubleArray, v: DoubleArray, w0: DoubleArray, iters: Int = 3): DoubleArray? {
        val n = xs.size
        if (n < 6) return null
        val w = w0.copyOf()
        var sol: DoubleArray? = null
        val basis = { i: Int -> doubleArrayOf(1.0, xs[i], ys[i], xs[i] * xs[i], xs[i] * ys[i], ys[i] * ys[i]) }
        repeat(iters) {
            val ata = Array(6) { DoubleArray(6) }; val atb = DoubleArray(6)
            for (i in 0 until n) {
                val b = basis(i)
                for (r in 0 until 6) {
                    atb[r] += b[r] * v[i] * w[i]
                    for (c in 0 until 6) ata[r][c] += b[r] * b[c] * w[i]
                }
            }
            // Pequeña regularización (teselas alineadas en una sola fila o columna)
            for (r in 0 until 6) ata[r][r] += 1e-6 * (ata[0][0] + 1e-9)
            sol = solve(ata, atb) ?: return sol
            val res = DoubleArray(n) { i -> v[i] - evalQuad(sol!!, xs[i], ys[i]) }
            val absr = DoubleArray(n) { abs(res[it]) }
            val s = 1.4826 * median(absr) + 1e-6
            for (i in 0 until n) w[i] = w0[i] * min(1.0, 1.5 * s / max(absr[i], 1e-9))
        }
        return sol
    }

    fun evalQuad(c: DoubleArray, x: Double, y: Double) = c[0] + c[1] * x + c[2] * y + c[3] * x * x + c[4] * x * y + c[5] * y * y

    /**
     * k-medias determinista (inicio por el punto más lejano) sobre covarianzas (a, b, c): pocas clases de núcleo para
     * deconvolucionar la página con pocas transformadas. Devuelve (etiquetas, centros).
     */
    fun kMeans(pts: List<Cov>, k0: Int, iters: Int = 20): Pair<IntArray, List<Cov>> {
        val n = pts.size
        if (n == 0) return IntArray(0) to emptyList()
        val k = min(k0, n)
        fun d2(p: Cov, q: Cov) = (p.a - q.a) * (p.a - q.a) + 2 * (p.b - q.b) * (p.b - q.b) + (p.c - q.c) * (p.c - q.c)
        val centers = ArrayList<Cov>(k)
        // Primer centro: el de mayor varianza media (la zona más desenfocada queda representada exactamente)
        centers.add(pts.maxByOrNull { it.meanVar }!!)
        while (centers.size < k) {
            val far = pts.maxByOrNull { p -> centers.minOf { d2(p, it) } }!!
            if (centers.minOf { d2(far, it) } < 1e-6) break
            centers.add(far)
        }
        val lab = IntArray(n)
        repeat(iters) {
            var changed = false
            for (i in 0 until n) {
                var best = 0; var bd = Double.MAX_VALUE
                for (c in centers.indices) { val d = d2(pts[i], centers[c]); if (d < bd) { bd = d; best = c } }
                if (lab[i] != best) { lab[i] = best; changed = true }
            }
            for (c in centers.indices) {
                var a = 0.0; var b = 0.0; var cc = 0.0; var m = 0
                for (i in 0 until n) if (lab[i] == c) { a += pts[i].a; b += pts[i].b; cc += pts[i].c; m++ }
                if (m > 0) centers[c] = Cov(a / m, b / m, cc / m)
            }
            if (!changed && it > 0) return lab to centers
        }
        return lab to centers
    }

    /**
     * Ganancia del filtro de Wiener para la gaussiana [cov] en la frecuencia (fx, fy) (ciclos/px):
     * G = H / (H² + K), limitada a [gMax]. Por encima de [fCut] (la frecuencia más alta que el origen contenía, antes
     * de ampliarlo) no se refuerza nada: ahí sólo hay ruido e interpolación. Transición suave de 0.06 ciclos/px.
     */
    fun wienerGain(cov: Cov, fx: Double, fy: Double, k: Double, fCut: Double, gMax: Double): Double {
        val q = cov.a * fx * fx + 2 * cov.b * fx * fy + cov.c * fy * fy
        val h = exp(-2 * PI * PI * q)
        val g = min(h / (h * h + k), gMax)
        val fr = sqrt(fx * fx + fy * fy)
        val t = ((fr - fCut) / 0.06).coerceIn(0.0, 1.0)
        return g * (1 - t) + t * min(g, 1.0)
    }

    /**
     * Inversa de una curva monótona creciente [stat](σ) muestreada en [sig]: σ con stat(σ) = [v] (interpolación
     * lineal; por debajo del primer punto 0 / el primer σ, por encima el último σ).
     */
    fun invertCurve(v: Double, stat: DoubleArray, sig: DoubleArray): Double {
        if (v <= stat[0]) return sig[0]
        for (i in 1 until stat.size) {
            if (v <= stat[i]) {
                val d = stat[i] - stat[i - 1]
                return if (d <= 1e-12) sig[i] else sig[i - 1] + (sig[i] - sig[i - 1]) * (v - stat[i - 1]) / d
            }
        }
        return sig[sig.size - 1]
    }

    fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val s = a.copyOf(); s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    fun median(a: FloatArray, n: Int): Double {
        if (n <= 0) return 0.0
        val s = a.copyOf(n); s.sort()
        return if (n % 2 == 1) s[n / 2].toDouble() else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    /** Percentil [p] (0..1) por interpolación lineal. */
    fun percentile(a: DoubleArray, p: Double): Double {
        if (a.isEmpty()) return 0.0
        val s = a.copyOf(); s.sort()
        val x = p * (s.size - 1)
        val i = x.toInt().coerceIn(0, s.size - 1); val j = min(i + 1, s.size - 1)
        return s[i] + (s[j] - s[i]) * (x - i)
    }

    /** Eliminación gaussiana con pivoteo parcial (sistemas pequeños). */
    fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-12) return null
            if (p != c) { val t = a[p]; a[p] = a[c]; a[c] = t; val tb = b[p]; b[p] = b[c]; b[c] = tb }
            for (r in c + 1 until n) {
                val f = a[r][c] / a[c][c]
                if (f == 0.0) continue
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = s / a[r][r]
        }
        return x
    }
}
