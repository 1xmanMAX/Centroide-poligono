package com.scannerpromax.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rasgos de "material" de una hipótesis de documento y puntuación de ordenación calibrada (lógica pura, sin OpenCV:
 * probada en JVM). Trabaja sobre la luminancia y la crominancia (Cb, Cr) suavizadas de la imagen de trabajo.
 *
 * El borde de un documento separa DOS MATERIALES: dentro, el mismo papel (o plástico) a lo largo de los cuatro lados;
 * fuera, la mesa, la mano o el fondo. Un cuadrilátero que mezcla la hoja con un trozo de mesa (un lado sobre el canto
 * de la hoja y otro sobre el de la mesa) o que encierra un objeto de la escena tiene lados cuya banda interior es de
 * materiales distintos, o lados con el mismo color a ambos lados. Esos rasgos, junto con los de la calidad del borde,
 * forman la puntuación de [rankScore], ajustada como un modelo de elección (softmax por imagen) sobre fotos con las
 * esquinas verdaderas anotadas, con validación cruzada por colección de origen.
 */
internal object DetFeatures {

    /** Rasgos de las bandas a ambos lados de cada lado del cuadrilátero. */
    class Bands(
        /** Separación de color (|ΔL| + 2·(|ΔCb| + |ΔCr|), medianas) interior/exterior: mínima y media de los lados reales. */
        val sepMin: Double, val sepMean: Double,
        /** Diferencia máxima entre las bandas INTERIORES de dos lados: luminancia y tono (|ΔCb| + |ΔCr|). */
        val devL: Double, val devC: Double,
        /** Algún lado real tiene fuera papel blanco saturado (margen de un escaneo). */
        val outWhite: Boolean,
        /** Lados con muestras suficientes (los pegados al borde de la imagen no cuentan). */
        val nReal: Int,
    )

    /** Mediana (media de los dos centrales si n es par), como numpy. */
    fun median(a: DoubleArray, n: Int): Double {
        if (n <= 0) return 0.0
        val s = a.copyOf(n); s.sort()
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Percentil con interpolación lineal (como numpy). */
    fun percentile(a: DoubleArray, n: Int, p: Double): Double {
        if (n <= 0) return 0.0
        val s = a.copyOf(n); s.sort()
        val x = p * (n - 1); val i = x.toInt(); val f = x - i
        return if (i + 1 < n) s[i] * (1 - f) + s[i + 1] * f else s[n - 1]
    }

    private fun px(b: ByteArray?, i: Int): Double = if (b == null) 128.0 else (b[i].toInt() and 0xFF).toDouble()

    /**
     * Bandas a 4 y 8 px de cada lado (10 posiciones por lado) del cuadrilátero [q] (tl, tr, br, bl) sobre la imagen
     * [w]x[h]: [l] luminancia, [cb]/[cr] crominancia (null = neutra). Las muestras exteriores a menos de [margin] px del
     * borde de la imagen se descartan; un lado con menos de 8 muestras no es "real".
     */
    fun bands(q: FloatArray, l: ByteArray, cb: ByteArray?, cr: ByteArray?, w: Int, h: Int, margin: Int = 5): Bands {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4.0; val cy = (q[1] + q[3] + q[5] + q[7]) / 4.0
        val inL = DoubleArray(4); val inB = DoubleArray(4); val inR = DoubleArray(4)
        val seps = DoubleArray(4); var nReal = 0; var outWhite = false
        val sl = DoubleArray(20); val sb = DoubleArray(20); val sr = DoubleArray(20)
        val ol = DoubleArray(20); val ob = DoubleArray(20); val or = DoubleArray(20)
        for (i in 0 until 4) {
            val x0 = q[i * 2].toDouble(); val y0 = q[i * 2 + 1].toDouble()
            val x1 = q[((i + 1) % 4) * 2].toDouble(); val y1 = q[((i + 1) % 4) * 2 + 1].toDouble()
            val dx = x1 - x0; val dy = y1 - y0; val len = hypot(dx, dy)
            if (len < 10) continue
            var nx = -dy / len; var ny = dx / len
            if (nx * ((x0 + x1) / 2 - cx) + ny * ((y0 + y1) / 2 - cy) < 0) { nx = -nx; ny = -ny }
            var n = 0
            for (k in 1..10) {
                val px0 = x0 + dx * k / 11.0; val py0 = y0 + dy * k / 11.0
                for (o in intArrayOf(4, 8)) {
                    val xo = (px0 + nx * o).roundToInt(); val yo = (py0 + ny * o).roundToInt()
                    val xi = (px0 - nx * o).roundToInt(); val yi = (py0 - ny * o).roundToInt()
                    if (xo < margin || yo < margin || xo >= w - margin || yo >= h - margin) continue
                    if (xi < 0 || yi < 0 || xi >= w || yi >= h) continue
                    val io = yo * w + xo; val ii = yi * w + xi
                    sl[n] = px(l, ii); sb[n] = px(cb, ii); sr[n] = px(cr, ii)
                    ol[n] = px(l, io); ob[n] = px(cb, io); or[n] = px(cr, io)
                    n++
                }
            }
            if (n < 8) continue
            val aL = median(sl, n); val aB = median(sb, n); val aR = median(sr, n)
            val bL = median(ol, n); val bB = median(ob, n); val bR = median(or, n)
            inL[nReal] = aL; inB[nReal] = aB; inR[nReal] = aR
            seps[nReal] = abs(aL - bL) + 2 * (abs(aB - bB) + abs(aR - bR))
            if (bL >= OUT_WHITE_L) outWhite = true
            nReal++
        }
        if (nReal == 0) return Bands(0.0, 0.0, 0.0, 0.0, false, 0)
        var sMin = Double.MAX_VALUE; var sSum = 0.0; var dL = 0.0; var dC = 0.0
        for (i in 0 until nReal) {
            sMin = min(sMin, seps[i]); sSum += seps[i]
            for (j in 0 until nReal) {
                dL = max(dL, abs(inL[i] - inL[j]))
                dC = max(dC, abs(inB[i] - inB[j]) + abs(inR[i] - inR[j]))
            }
        }
        return Bands(sMin, sSum / nReal, dL, dC, outWhite, nReal)
    }

    /**
     * Interior del cuadrilátero: malla 7x7 (interpolación bilineal entre esquinas, del 15 % al 85 %) de la luminancia.
     * Devuelve (percentil 25, mediana).
     */
    fun interior(q: FloatArray, l: ByteArray, w: Int, h: Int): DoubleArray {
        val v = DoubleArray(49); var n = 0
        for (iy in 0 until 7) for (ix in 0 until 7) {
            val u = 0.15 + 0.7 * ix / 6; val t = 0.15 + 0.7 * iy / 6
            val x = (q[0] * (1 - u) * (1 - t) + q[2] * u * (1 - t) + q[4] * u * t + q[6] * (1 - u) * t).roundToInt()
            val y = (q[1] * (1 - u) * (1 - t) + q[3] * u * (1 - t) + q[5] * u * t + q[7] * (1 - u) * t).roundToInt()
            if (x in 0 until w && y in 0 until h) v[n++] = px(l, y * w + x)
        }
        return doubleArrayOf(percentile(v, n, 0.25), median(v, n))
    }

    /** ¿El punto (x, y) está dentro del polígono convexo [q] (4 vértices) o a menos de [tol] px de él? */
    fun insideTol(q: FloatArray, x: Double, y: Double, tol: Double): Boolean {
        // Signo del área para la orientación
        var area = 0.0
        for (i in 0 until 4) { val j = (i + 1) % 4; area += q[i * 2].toDouble() * q[j * 2 + 1] - q[j * 2].toDouble() * q[i * 2 + 1] }
        val sg = if (area >= 0) 1.0 else -1.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val ax = q[i * 2].toDouble(); val ay = q[i * 2 + 1].toDouble(); val bx = q[j * 2].toDouble(); val by = q[j * 2 + 1].toDouble()
            val len = hypot(bx - ax, by - ay); if (len < 1e-9) continue
            // distancia con signo (positiva = dentro)
            val d = sg * ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) / len
            if (d < -tol) return false
        }
        return true
    }

    /** ¿Las cuatro esquinas de [inner] están dentro de [outer] (tolerancia 3 px)? */
    fun quadInside(inner: FloatArray, outer: FloatArray): Boolean {
        for (i in 0 until 4) if (!insideTol(outer, inner[i * 2].toDouble(), inner[i * 2 + 1].toDouble(), 3.0)) return false
        return true
    }

    /**
     * Puntuación de ordenación (sin término independiente: sólo se comparan candidatos de la misma imagen).
     * [af] fracción de área, [maxCos] peor coseno de esquina, [edge] apoyo global del borde, [border] fracción del
     * perímetro sobre el marco, [provenance] peso de la hipótesis, [snapped] si está ajustada a los bordes reales,
     * [b] bandas, [g25]/[gMed] interior, [lMedian] mediana de la luminancia de la imagen, [inner] calidad del mejor
     * candidato contenido (0..1; 0 si no hay).
     */
    fun rankScore(
        af: Double, maxCos: Double, edge: Double, border: Double, provenance: Double, snapped: Boolean,
        b: Bands, g25: Double, gMed: Double, lMedian: Double, inner: Double,
    ): Double {
        val x = doubleArrayOf(
            ln(max(af, 1e-3)), maxCos / 0.55, edge, border, provenance, if (snapped) 1.0 else 0.0,
            min(b.sepMin, 150.0) / 100, min(b.sepMean, 150.0) / 100, min(b.devL, 150.0) / 100, min(b.devC, 60.0) / 50,
            g25 / 255, (gMed - lMedian) / 100, inner,
        )
        var s = 0.0
        for (i in x.indices) s += RANK_W[i] * x[i]
        return s
    }

    /**
     * Pesos de [rankScore] (rasgos en el orden de allí). Más área, borde nítido y completo, lados que separan
     * materiales, interior homogéneo y claro, y candidatos interiores nítidos (el contenido de la hoja) suben; esquinas
     * torcidas y tramos sobre el marco bajan.
     */
    private val RANK_W = doubleArrayOf(2.11, -1.13, 4.84, -9.13, 1.88, 1.18, 1.96, 0.54, -0.87, -1.12, 4.0, 0.49, 0.96)

    /** Ventaja mínima de [rankScore] para cambiar el candidato elegido por la lógica de contornos. */
    const val SWAP_MARGIN = 1.0

    /** Ventaja mínima cuando la sustituta tiene menos del 80 % del área: encoger a una parte de la hoja es el error caro. */
    const val SHRINK_MARGIN = 3.0

    /** Luminancia (L de Lab) a partir de la cual el exterior de un lado es papel blanco saturado. */
    const val OUT_WHITE_L = 240.0
}
