package com.scannerpromax.imaging

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AUTOCALIBRADO de la distorsión de la lente con el propio documento (fotos de la galería, apps de cámara del sistema y
 * teléfonos que no informan de su calibración): en la foto de una hoja PLANA las rectas de verdad (cantos de la hoja,
 * líneas de tablas, renglones de texto) siguen siendo rectas con cualquier perspectiva; si salen curvadas de forma
 * coherente con una distorsión RADIAL (barril: se arquean hacia fuera en los dos lados opuestos del centro; cojín: hacia
 * dentro) se estima el modelo de división de un parámetro ([LensMap.Division]) que las endereza ("plumb-line").
 *
 * Aceptación CONSERVADORA (no hacer nada es siempre seguro):
 *  - suficientes curvas largas y algunas lejos del centro (donde la distorsión se nota);
 *  - el ajuste reduce mucho la curvatura total ([MIN_GAIN]) y el desplazamiento resultante en la foto es apreciable;
 *  - COHERENCIA RADIAL: mejora en lados OPUESTOS del centro (arriba y abajo, o izquierda y derecha) y ningún lado empeora.
 *    Una hoja curvada (libro, papel abarquillado) arquea los renglones de forma no radial (un solo lado, o en el mismo
 *    sentido a ambos lados): eso no lo toca este paso, lo endereza después [GridDewarp] con la página rectificada, que mide
 *    la curvatura que quede. Así no se corrige dos veces: lo que este paso endereza ya no lo ve GridDewarp.
 *
 * Lógica de ajuste pura ([fit], probada en JVM); extracción de curvas con OpenCV ([chains]) a ~[WORK_SIDE] px.
 */
internal object LensSelfCal {

    const val WORK_SIDE = 1280
    const val K_MAX = 0.15
    const val MIN_GAIN = 0.45

    /** Registro opcional (banco/depuración). */
    @Volatile internal var log: ((String) -> Unit)? = null

    /** Resultado del ajuste. [k] en el modelo de división normalizado por la semidiagonal (independiente de la escala). */
    data class Fit(
        val k: Double,
        val cost0: Double,
        val cost: Double,
        val chains: Int,
        val accepted: Boolean,
        val reason: String,
        /** Residuo rms medio (px de la imagen de trabajo) de las curvas antes/después. */
        val resid0: Double = 0.0,
        val resid: Double = 0.0,
    )

    /**
     * Estima la distorsión de [img] (RGB/RGBA/gris, cualquier tamaño). Devuelve el modelo para el tamaño de [img] o null
     * si no hay evidencia clara. [fitOut] recibe el detalle (banco).
     */
    fun estimate(img: Mat, fitOut: ((Fit) -> Unit)? = null): LensMap.Division? = MatBag().use { bag ->
        val w = img.cols(); val h = img.rows()
        if (w < 200 || h < 200) return null
        val g = bag.add(Cv.gray(img))
        val small = bag.mat()
        Cv.downscale(g, small, WORK_SIDE)
        val ch = chains(small)
        val fit = fit(ch, small.cols(), small.rows())
        // Desplazamiento a resolución completa (el k normalizado no depende de la escala)
        val map = LensMap.Division(w, h, fit.k)
        val disp = map.maxDisplacement()
        val ok = fit.accepted && disp >= 1.5
        val f2 = if (fit.accepted && !ok) fit.copy(accepted = false, reason = "desplazamiento %.1f px".format(disp)) else fit
        log?.invoke("lente: autocalibrado k=%.4f coste %.3f->%.3f curvas %d desplaz. %.1f px %s".format(f2.k, f2.cost0, f2.cost, f2.chains, disp, if (ok) "APLICA" else "no (${f2.reason})"))
        fitOut?.invoke(f2)
        if (ok) map else null
    }

    // ------------------------------------------------------------------------------------------- ajuste (puro)

    /**
     * Ajuste del modelo de división a las curvas [chains] (x0,y0,x1,y1,… en px de una imagen [w]x[h]) que deberían ser
     * rectas. Búsqueda en rejilla de k ∈ [−K_MAX, K_MAX] y refinado parabólico; aceptación en [Fit.accepted].
     */
    fun fit(chains: List<FloatArray>, w: Int, h: Int): Fit {
        val minSide = min(w, h).toDouble()
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        val norm = hypot(w / 2.0, h / 2.0)
        val use = chains.filter { it.size >= 16 && chordLen(it) >= 0.18 * minSide }
        if (use.size < 4) return Fit(0.0, 0.0, 0.0, use.size, false, "pocas curvas (${use.size})")
        // Curvas lejos del centro: su punto medio a más del 45 % de la semidiagonal
        val outer = use.count { c -> val m = mid(c); hypot(m[0] - cx, m[1] - cy) / norm > 0.45 }
        if (outer < 2) return Fit(0.0, 0.0, 0.0, use.size, false, "sin curvas en los bordes")
        val wts = DoubleArray(use.size) { chordLen(use[it]) / minSide }
        // Curvas que cuentan (tras la primera pasada, fuera las que no son rectas con ningún k: texto suelto, sombras)
        val active = BooleanArray(use.size) { true }
        fun costOf(k: Double, per: DoubleArray? = null): Double {
            var s = 0.0
            for ((i, c) in use.withIndex()) {
                if (!active[i] && per == null) continue
                val r = residual(c, k, cx, cy, norm)
                per?.set(i, r)
                s += wts[i] * r * r
            }
            return s
        }
        val c0All = costOf(0.0)
        if (c0All <= 1e-9) return Fit(0.0, c0All, c0All, use.size, false, "ya rectas")
        var c0Active = c0All
        var bestK = 0.0; var best = c0All
        val steps = 120
        val ks = DoubleArray(steps + 1) { -K_MAX + 2 * K_MAX * it / steps }
        val cs = DoubleArray(ks.size) { costOf(ks[it]) }
        var bi = 0
        for (i in cs.indices) if (cs[i] < cs[bi]) bi = i
        if (cs[bi] < best) { best = cs[bi]; bestK = ks[bi] }
        // Refinado parabólico alrededor del mínimo
        if (bi in 1 until ks.lastIndex) {
            val a = cs[bi - 1]; val b = cs[bi]; val c = cs[bi + 1]
            val den = a - 2 * b + c
            if (den > 1e-12) {
                val kk = ks[bi] + 0.5 * (a - c) / den * (ks[1] - ks[0])
                val ck = costOf(kk)
                if (ck < best) { best = ck; bestK = kk }
            }
        }
        // Segunda pasada: sólo las curvas que el modelo explica (residuo <= 2.5 x mediana) y búsqueda fina (sección áurea)
        if (bi in 1 until ks.lastIndex) {
            val pr = DoubleArray(use.size); costOf(bestK, pr)
            val med = pr.sorted()[pr.size / 2]
            for (i in use.indices) active[i] = pr[i] <= max(0.8, 2.5 * med)
            if (active.count { it } >= 4) {
                var lo = bestK - 0.012; var hi = bestK + 0.012
                val g = (sqrt(5.0) - 1) / 2
                var x1 = hi - g * (hi - lo); var x2 = lo + g * (hi - lo)
                var f1 = costOf(x1); var f2 = costOf(x2)
                repeat(28) {
                    if (f1 < f2) { hi = x2; x2 = x1; f2 = f1; x1 = hi - g * (hi - lo); f1 = costOf(x1) }
                    else { lo = x1; x1 = x2; f1 = f2; x2 = lo + g * (hi - lo); f2 = costOf(x2) }
                }
                bestK = (lo + hi) / 2
            } else active.fill(true)
            best = costOf(bestK)
            c0Active = costOf(0.0)
        }
        val per0 = DoubleArray(use.size); val per1 = DoubleArray(use.size)
        costOf(0.0, per0); costOf(bestK, per1)
        val r0 = per0.average(); val r1 = per1.average()
        val c0 = c0Active
        if (bi == 0 || bi == ks.lastIndex) return Fit(bestK, c0, best, use.size, false, "k en el límite", r0, r1)
        if (best > c0 * (1 - MIN_GAIN)) return Fit(bestK, c0, best, use.size, false, "poca mejora", r0, r1)
        // Coherencia radial por lados: curvas horizontales arriba/abajo del centro, verticales a izquierda/derecha
        val side0 = DoubleArray(4); val side1 = DoubleArray(4); val cnt = IntArray(4)
        for ((i, c) in use.withIndex()) {
            if (!active[i]) continue
            val m = mid(c)
            val dx = c[c.size - 2] - c[0]; val dy = c[c.size - 1] - c[1]
            val horiz = abs(dx) >= abs(dy)
            val off = if (horiz) (m[1] - cy) / norm else (m[0] - cx) / norm
            if (abs(off) < 0.2) continue   // por el centro la distorsión apenas curva: no informa del lado
            val s = if (horiz) (if (off < 0) 0 else 1) else (if (off < 0) 2 else 3)
            side0[s] += wts[i] * per0[i] * per0[i]; side1[s] += wts[i] * per1[i] * per1[i]; cnt[s]++
        }
        fun improves(s: Int) = cnt[s] > 0 && side1[s] <= side0[s] * 0.75
        fun worsens(s: Int) = cnt[s] > 0 && side1[s] > side0[s] * 1.1
        val opposite = (improves(0) && improves(1)) || (improves(2) && improves(3))
        if (!opposite) return Fit(bestK, c0, best, use.size, false, "no radial (lados ${cnt.toList()})", r0, r1)
        if ((0..3).any { worsens(it) }) return Fit(bestK, c0, best, use.size, false, "empeora un lado", r0, r1)
        return Fit(bestK, c0, best, use.size, true, "ok", r0, r1)
    }

    private fun chordLen(c: FloatArray) = hypot((c[c.size - 2] - c[0]).toDouble(), (c[c.size - 1] - c[1]).toDouble())
    private fun mid(c: FloatArray): DoubleArray { val n = c.size / 2; val i = (n / 2) * 2; return doubleArrayOf(c[i].toDouble(), c[i + 1].toDouble()) }

    /**
     * Residuo rms (px) de la curva [c] respecto de su recta de mínimos cuadrados totales tras corregirla con k, re-escalado
     * a la longitud ORIGINAL de la curva (la corrección amplía/reduce y no debe premiar encoger).
     */
    internal fun residual(c: FloatArray, k: Double, cx: Double, cy: Double, norm: Double): Double {
        val n = c.size / 2
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) {
            val dx = (c[2 * i] - cx) / norm; val dy = (c[2 * i + 1] - cy) / norm
            val d = 1 + k * (dx * dx + dy * dy)
            xs[i] = dx / d * norm; ys[i] = dy / d * norm
            mx += xs[i]; my += ys[i]
        }
        mx /= n; my /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) { val a = xs[i] - mx; val b = ys[i] - my; sxx += a * a; syy += b * b; sxy += a * b }
        // Menor autovalor de la covarianza = varianza perpendicular a la recta
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        val lmin = max(0.0, tr / 2 - sqrt(max(0.0, tr * tr / 4 - det)))
        val rms = sqrt(lmin / n)
        val lenU = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])
        val lenD = chordLen(c)
        return if (lenU > 1e-6) rms * lenD / lenU else rms
    }

    // ------------------------------------------------------------------------------------- curvas (OpenCV)

    /**
     * Curvas que deberían ser rectas en [gray] (8UC1, ~[WORK_SIDE] px): tramos largos y casi rectos de los bordes (cantos de
     * la hoja, líneas de tablas y subrayados) y líneas centrales de los renglones de texto (horizontales y verticales).
     */
    fun chains(gray: Mat): List<FloatArray> = MatBag().use { bag ->
        val w = gray.cols(); val h = gray.rows()
        val minSide = min(w, h).toDouble()
        val out = ArrayList<FloatArray>()
        // A) Bordes: Canny + contornos + partición por vértices de approxPolyDP (las esquinas de la hoja cortan)
        val bl = bag.mat(); Imgproc.GaussianBlur(gray, bl, Size(0.0, 0.0), 1.2)
        val ed = bag.mat(); Imgproc.Canny(bl, ed, 40.0, 120.0)
        val contours = ArrayList<MatOfPoint>()
        val hier = bag.mat()
        Imgproc.findContours(ed, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
        val minLen = 0.18 * minSide
        val eps = 0.012 * hypot(w.toDouble(), h.toDouble())
        for (ct in contours) {
            val n = ct.rows()
            if (n < minLen) { ct.release(); continue }
            val pts = IntArray(n * 2); ct.get(0, 0, pts)
            val c2 = MatOfPoint2f(*ct.toArray().map { org.opencv.core.Point(it.x, it.y) }.toTypedArray())
            val ap = MatOfPoint2f()
            Imgproc.approxPolyDP(c2, ap, eps, false)
            val verts = ap.toArray()
            c2.release(); ap.release(); ct.release()
            // Índices de los vértices en el contorno (approxPolyDP conserva puntos del contorno)
            val idx = ArrayList<Int>()
            var from = 0
            for (v in verts) {
                var j = from
                while (j < n && (pts[2 * j] != v.x.toInt() || pts[2 * j + 1] != v.y.toInt())) j++
                if (j < n) { idx += j; from = j }
            }
            for (t in 0 until idx.size - 1) {
                val a = idx[t]; val b = idx[t + 1]
                if (b - a < minLen) continue
                val ch = sampleChain(pts, a, b, 3)
                val chord = chordLen(ch)
                if (chord < minLen) continue
                // Casi recta (la distorsión de un móvil arquea poco): flecha < 4 % de la cuerda
                if (sagitta(ch) > 0.04 * chord) continue
                out += ch
            }
        }
        // B) Renglones de texto: tinta (umbral adaptativo) cerrada a lo largo del renglón; línea central de cada mancha
        val ink = bag.mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, Cv.odd((minSide / 25).toInt().coerceAtLeast(15)), 12.0)
        for (vertical in listOf(false, true)) {
            val klen = (minSide / 45).toInt().coerceAtLeast(9)
            val ker = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, if (vertical) Size(1.0, klen.toDouble()) else Size(klen.toDouble(), 1.0))
            val cl = bag.mat(); Imgproc.morphologyEx(ink, cl, Imgproc.MORPH_CLOSE, ker)
            // Separar renglones que se tocan (ascendentes/descendentes): apertura en la dirección perpendicular
            val k2 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, if (vertical) Size(3.0, 1.0) else Size(1.0, 3.0))
            Imgproc.morphologyEx(cl, cl, Imgproc.MORPH_OPEN, k2)
            ker.release(); k2.release()
            val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
            val nl = Imgproc.connectedComponentsWithStats(cl, labels, stats, cents, 8, CvType.CV_32S)
            val st = IntArray(nl * 5); if (nl > 0) stats.get(0, 0, st)
            val lab = IntArray(w * h); labels.get(0, 0, lab)
            for (l in 1 until nl) {
                val bx = st[l * 5]; val by = st[l * 5 + 1]; val bw = st[l * 5 + 2]; val bh = st[l * 5 + 3]
                val along = if (vertical) bh else bw; val across = if (vertical) bw else bh
                if (along < minLen || along < 8 * across || across < 3) continue
                // Línea central: media de la coordenada perpendicular por cada paso a lo largo
                val pts = ArrayList<Float>()
                val step = 3
                var t = if (vertical) by else bx
                val tEnd = if (vertical) by + bh else bx + bw
                while (t < tEnd) {
                    var s = 0.0; var c = 0
                    if (vertical) { for (x in bx until bx + bw) if (lab[t * w + x] == l) { s += x; c++ } }
                    else { for (y in by until by + bh) if (lab[y * w + t] == l) { s += y; c++ } }
                    if (c > 0) {
                        if (vertical) { pts += (s / c).toFloat(); pts += t.toFloat() } else { pts += t.toFloat(); pts += (s / c).toFloat() }
                    }
                    t += step
                }
                val ch = pts.toFloatArray()
                if (ch.size >= 16 && sagitta(ch) < 0.04 * chordLen(ch)) out += ch
            }
        }
        // Las más largas primero; tope para el tiempo
        out.sortByDescending { chordLen(it) }
        if (out.size > 80) out.subList(80, out.size).clear()
        out
    }

    private fun sampleChain(pts: IntArray, a: Int, b: Int, step: Int): FloatArray {
        val n = (b - a) / step + 1
        val o = FloatArray(n * 2)
        for (i in 0 until n) { val j = min(b, a + i * step); o[2 * i] = pts[2 * j].toFloat(); o[2 * i + 1] = pts[2 * j + 1].toFloat() }
        return o
    }

    /** Flecha máxima (px) de la curva respecto de su cuerda. */
    internal fun sagitta(c: FloatArray): Double {
        val x0 = c[0].toDouble(); val y0 = c[1].toDouble()
        val x1 = c[c.size - 2].toDouble(); val y1 = c[c.size - 1].toDouble()
        val l = hypot(x1 - x0, y1 - y0).takeIf { it > 1e-9 } ?: return 0.0
        var m = 0.0
        for (i in 0 until c.size / 2) m = max(m, abs((c[2 * i] - x0) * (y1 - y0) - (c[2 * i + 1] - y0) * (x1 - x0)) / l)
        return m
    }
}
