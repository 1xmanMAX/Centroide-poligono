package com.scannerpromax.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Matemática pura de [Deghost] (sin OpenCV: probada en los tests de JVM). Núcleos cuadrados (2R+1)² en fila mayor con
 * el origen en el centro.
 */
internal object DeghostMath {

    /**
     * Gradiente conjugado para A x = b con A[i, j] = ac[d_j - d_i] (+ λ·ac[0] en la diagonal), d en el soporte
     * (2R+1)²; [ac] son los retardos (4R+1)² de la autocorrelación. [matvec] calcula Σ_j ac[d_i - d_j]·x_j (por FFT en
     * [Deghost]; [directMatvec] en los tests). Devuelve x (sin restricciones: se limpian después).
     */
    fun solveCg(ac: DoubleArray, b: DoubleArray, r: Int, lam: Double, iters: Int = 60, matvec: (DoubleArray) -> DoubleArray): DoubleArray {
        val n = 2 * r + 1; val l = 2 * r; val m = 4 * r + 1
        val lamV = lam * ac[l * m + l]
        fun ax(x: DoubleArray): DoubleArray { val y = matvec(x); for (i in y.indices) y[i] += lamV * x[i]; return y }
        val x = DoubleArray(n * n)
        val res = b.copyOf(); val p = res.copyOf()
        var rs = dot(res, res); val bb = rs
        if (bb <= 0) return x
        repeat(iters) {
            val ap = ax(p)
            val al = rs / max(1e-30, dot(p, ap))
            for (i in x.indices) { x[i] += al * p[i]; res[i] -= al * ap[i] }
            val rn = dot(res, res)
            if (rn < 1e-10 * bb) return x
            val be = rn / rs
            for (i in p.indices) p[i] = res[i] + be * p[i]
            rs = rn
        }
        return x
    }

    /** Producto A·x directo (lento: sólo para tests y soportes pequeños). */
    fun directMatvec(ac: DoubleArray, r: Int): (DoubleArray) -> DoubleArray = { x ->
        val n = 2 * r + 1; val l = 2 * r; val m = 4 * r + 1
        DoubleArray(n * n) { i ->
            val yi = i / n; val xi = i % n
            var s = 0.0
            for (j in 0 until n * n) {
                val v = x[j]; if (v == 0.0) continue
                s += ac[(l + yi - j / n) * m + (l + xi - j % n)] * v
            }
            s
        }
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }

    /**
     * Limpieza del núcleo estimado: sin negativos, sin valores < [thr]·máx (ruido de la estimación), suma 1 y
     * DESPLAZADO para que su pico (suavizado 3x3) quede en el centro: el núcleo nítido del trazo no se mueve al
     * restaurar (sin dobles bordes entre zonas). null si no queda nada.
     */
    fun cleanKernel(k: DoubleArray, r: Int, thr: Double = 0.08): DoubleArray? {
        val n = 2 * r + 1
        val c = DoubleArray(n * n) { max(0.0, k[it]) }
        val mx = c.max()
        if (mx <= 0) return null
        for (i in c.indices) if (c[i] < thr * mx) c[i] = 0.0
        val s = c.sum(); if (s <= 0) return null
        for (i in c.indices) c[i] /= s
        // pico del núcleo suavizado (1 2 1)/4 x (1 2 1)/4
        var best = -1.0; var py = r; var px = r
        for (y in 0 until n) for (x in 0 until n) {
            var v = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy; val xx = x + dx
                if (yy < 0 || yy >= n || xx < 0 || xx >= n) continue
                v += c[yy * n + xx] * (if (dy == 0) 2 else 1) * (if (dx == 0) 2 else 1)
            }
            if (v > best) { best = v; py = y; px = x }
        }
        val sy = r - py; val sx = r - px
        val out = DoubleArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val ny = y + sy; val nx = x + sx
            if (ny in 0 until n && nx in 0 until n) out[ny * n + nx] = c[y * n + x]
        }
        val so = out.sum(); if (so <= 0) return null
        for (i in out.indices) out[i] /= so
        return out
    }

    /** Ancho del núcleo: raíz del segundo momento respecto del centro (px). */
    fun width(k: DoubleArray, r: Int): Double {
        val n = 2 * r + 1; var s = 0.0
        for (y in 0 until n) for (x in 0 until n) { val dy = y - r; val dx = x - r; s += k[y * n + x] * (dx * dx + dy * dy) }
        return sqrt(s)
    }

    /** Masa del núcleo en el anillo exterior del soporte (r >= R - 1.5): si es apreciable el soporte no basta o la estimación divergió. */
    fun ringMass(k: DoubleArray, r: Int): Double {
        val n = 2 * r + 1; var s = 0.0; val lim = (r - 1.5) * (r - 1.5)
        for (y in 0 until n) for (x in 0 until n) { val dy = y - r; val dx = x - r; if (dx * dx + dy * dy >= lim) s += k[y * n + x] }
        return s
    }

    /** Media de [ks] simetrizada respecto del centro (k(d) = k(-d)) y normalizada: el desenfoque de referencia de la página. */
    fun symmetricMean(ks: List<DoubleArray>): DoubleArray {
        val sz = ks[0].size; val m = DoubleArray(sz)
        for (k in ks) for (i in 0 until sz) m[i] += k[i]
        val out = DoubleArray(sz) { 0.5 * (m[it] + m[sz - 1 - it]) }
        val s = out.sum(); for (i in out.indices) out[i] /= s
        return out
    }

    /** Peso de la corrección de una zona según su exceso de ancho sobre la referencia (rampa [dw0, dw1] px). */
    fun zoneWeight(w: Double, wRef: Double, dw0: Double, dw1: Double): Double = ((w - wRef - dw0) / (dw1 - dw0)).coerceIn(0.0, 1.0)

    /** Cociente λmín/λmáx del tensor de estructura (sxx, syy, sxy): 0 = una sola orientación, 1 = isótropo. */
    fun isotropy(sxx: Double, syy: Double, sxy: Double): Double {
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        val l1 = tr / 2 + sqrt(max(0.0, tr * tr / 4 - det)); val l2 = tr - l1
        return if (l1 <= 0) 0.0 else min(1.0, max(0.0, l2 / l1))
    }

    /**
     * Peso de la zona [z] (de tamaño [zs], [nz] zonas) en la coordenada [v]: 1 en su interior, rampas lineales de ancho
     * zs/2 centradas en sus fronteras (partición de la unidad entre zonas vecinas), plano hacia el borde de la imagen.
     */
    fun partition(v: Double, z: Int, zs: Double, nz: Int): Double {
        val m = 0.25 * zs
        val lo = z * zs; val hi = (z + 1) * zs
        val up = if (z == 0) 1.0 else ((v - (lo - m)) / (2 * m)).coerceIn(0.0, 1.0)
        val dn = if (z == nz - 1) 1.0 else (((hi + m) - v) / (2 * m)).coerceIn(0.0, 1.0)
        return min(up, dn)
    }
}
