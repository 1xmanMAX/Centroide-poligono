package com.scannerpromax.imaging

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MÁRGENES RECTOS: en un documento impreso los renglones alineados a la izquierda empiezan todos en la misma x (y, si
 * el texto está justificado, terminan en la misma x). Tras la perspectiva y el enderezado puede quedar una cizalla o un
 * trapecio residual (esquinas del recorte algo desplazadas, hoja algo inclinada respecto de la cámara): los renglones
 * salen horizontales pero el margen izquierdo se desplaza al bajar. El giro solo no lo arregla.
 *
 * Sobre los tramos de renglón de [GridDewarp] (imagen de estimación, ya rectificada):
 * 1. Ángulo de los renglones (mediana ponderada; sólo si son coherentes) y giro que los deja horizontales.
 * 2. **Columna principal** de inicios: recta x = a + b·y con más renglones dentro de una tolerancia de 0.4 letras
 *    (barrido de pendientes + ventana de intercepto, sin azar); sangrías, viñetas, títulos centrados y tablas son
 *    atípicos. Se exige soporte entre los renglones de la columna, poca dispersión (impreso; la letra a mano no la
 *    cumple) y que una SEGUNDA columna de inicios, si la hay con peso parecido, tenga la misma pendiente.
 * 3. **Canto derecho**: igual con los finales, sólo si el texto está claramente justificado.
 * 4. Corrección: con sólo el margen izquierdo, cizalla x' = x − b·(y − yc); con ambos márgenes, homografía que
 *    conserva las horizontales y lleva las dos rectas a verticales (trapecio). Se compone con el giro en UNA matriz
 *    ([Result.m]) que [PageProcessor] encadena con la perspectiva: un único remuestreo.
 *
 * Conservador: nada con pocos renglones, columnas que discrepan, letra a mano o desviaciones mínimas o enormes. Con
 * modelo de malla (hoja curvada) la columna entra como guía vertical impuesta ([guidePoints]).
 *
 * Lógica pura (sin OpenCV; probada en JVM).
 */
internal object MarginAlign {

    /** Activable en el banco de pruebas. */
    @Volatile internal var enabled = true

    /** La columna principal entra como guía vertical en el ajuste de malla (banco de pruebas). */
    @Volatile internal var meshGuide = true

    /** Recta x = a + b·y (marco con los renglones horizontales) ajustada a [idx] puntos. */
    class Line(
        val a: Double, val b: Double,
        /** Renglones distintos sobre la recta. */
        val n: Int,
        /** Dispersión (rms, px) de los inicios/finales respecto de la recta. */
        val rms: Double,
        val y0: Double, val y1: Double,
        /** Error típico de la pendiente. */
        val seB: Double,
        /** Fracción de los renglones de la columna que caen sobre la recta. */
        val support: Double,
        val idx: IntArray,
    ) {
        fun x(y: Double) = a + b * y
        val span get() = y1 - y0
    }

    class Result(
        /** Ángulo de los renglones (grados, convención de `getRotationMatrix2D`, como [Cleanup.estimateSkew]). */
        val angle: Double,
        val left: Line?,
        val right: Line?,
        /** Corrección (giro + cizalla/trapecio) en px de la imagen de estimación ([w] x [h]); null = no actuar. */
        val m: DoubleArray?,
        val w: Double, val h: Double,
        /** Inicios de la columna principal en coordenadas de la imagen de estimación (x, y intercalados, por y). */
        val guidePoints: FloatArray?,
        val info: String,
        /**
         * Dos columnas de inicios rectas pero con pendientes distintas (cizalla LOCAL: p. ej. la cabecera torcida y los
         * párrafos rectos): no hay una corrección global; la malla con las columnas como verticales impuestas sí.
         */
        val local: Boolean = false,
        /** Inicios de la segunda columna (como [guidePoints]) si discrepa. */
        val guide2: FloatArray? = null,
    ) {
        val act get() = m != null
    }

    /**
     * Análisis y corrección. [segs] = tramos de renglón ([TextGuides.Seg], px de la imagen de estimación [w] x [h]),
     * [rowSlopes]/[rowLens] = pendiente dy/dx y longitud de cada renglón, [med] = altura de letra, [pitch] = paso entre
     * renglones. null si no hay renglones coherentes.
     */
    fun estimate(
        segs: List<TextGuides.Seg>, rowSlopes: DoubleArray, rowLens: DoubleArray,
        med: Double, pitch: Double, w: Double, h: Double,
    ): Result? {
        if (!enabled || rowSlopes.size < 8 || segs.size < 8) return null
        val ang = DoubleArray(rowSlopes.size) { Math.toDegrees(atan(rowSlopes[it])) }
        val angle = TextGuides.weightedMedian(ang, rowLens)
        val mad = TextGuides.weightedMedian(DoubleArray(ang.size) { abs(ang[it] - angle) }, rowLens)
        if (mad > 0.35 || abs(angle) > 10.0) return Result(angle, null, null, null, w, h, null, "renglones incoherentes (dispersión %.2f°)".format(mad))
        val rot = rotation(angle, w / 2, h / 2)
        val edge = 0.6 * med
        // Inicios y finales en el marco con los renglones horizontales (los tramos cortados por el borde no cuentan)
        val sIdx = segs.indices.filter { val s = segs[it]; s.xe - s.xs >= 1.5 * med && s.xs > edge }
        val eIdx = segs.indices.filter { val s = segs[it]; s.xe - s.xs >= 3 * med && s.xe < w - edge }
        fun pts(ix: List<Int>, start: Boolean): Triple<DoubleArray, DoubleArray, IntArray> {
            val xs = DoubleArray(ix.size); val ys = DoubleArray(ix.size); val rows = IntArray(ix.size)
            val o = DoubleArray(2)
            for ((k, i) in ix.withIndex()) {
                val s = segs[i]
                if (start) apply(rot, s.xs.toDouble(), s.ys.toDouble(), o) else apply(rot, s.xe.toDouble(), s.ye.toDouble(), o)
                xs[k] = o[0]; ys[k] = o[1]; rows[k] = s.row
            }
            return Triple(xs, ys, rows)
        }
        val (sx, sy, sr) = pts(sIdx, true)
        val (ex, ey, er) = pts(eIdx, false)
        val nRows = segs.map { it.row }.distinct().size
        val textTop = sy.minOrNull() ?: 0.0; val textBot = sy.maxOrNull() ?: 0.0
        val textH = max(1.0, textBot - textTop)
        val tol = max(0.4 * med, 1.5)
        val maxRms = 0.12 * med + 0.6
        val minN = max(6, (0.3 * nRows).toInt())
        val minSpan = max(4 * pitch, 0.25 * textH)
        val log = StringBuilder("renglones=$nRows ángulo=%.2f".format(angle))

        // Columna principal de inicios
        val l1 = fitColumn(sx, sy, sr, tol, minDy = 0.5 * pitch)
            ?.let { withSupport(it, segsX = segEnds(sIdx, segs, rot), start = true, med = med) }
        if (l1 == null) return Result(angle, null, null, null, w, h, null, "$log sin columna")
        log.append(" izq n=${l1.n} b=%.4f rms=%.2f sop=%.2f".format(l1.b, l1.rms, l1.support))
        val leftOk = l1.n >= minN && l1.span >= minSpan && l1.rms <= maxRms && l1.support >= 0.6
        if (!leftOk) return Result(angle, null, null, null, w, h, null, "$log columna dudosa")
        val guide = guidePointsOf(l1, sIdx, segs)
        // Canto derecho: sólo con justificación clara
        var right: Line? = null
        val r1 = fitColumn(ex, ey, er, tol, minDy = 0.5 * pitch)
            ?.let { withSupport(it, segsX = segEnds(eIdx, segs, rot), start = false, med = med) }
        if (r1 != null) {
            val yOver = min(r1.y1, l1.y1) - max(r1.y0, l1.y0)
            val sep = r1.x((r1.y0 + r1.y1) / 2) - l1.x((r1.y0 + r1.y1) / 2)
            val ok = r1.n >= max(6, (0.35 * nRows).toInt()) && r1.span >= minSpan && r1.rms <= maxRms && r1.support >= 0.65 &&
                yOver >= 0.5 * min(r1.span, l1.span) && sep >= 0.3 * w && abs(r1.b) <= 0.07
            log.append(" der n=${r1.n} b=%.4f rms=%.2f sop=%.2f${if (ok) "" else " (no)"}".format(r1.b, r1.rms, r1.support))
            if (ok) right = r1
        }
        // Segunda columna de inicios (tabulador, otra columna): misma pendiente o no se actúa
        var bL = l1.b
        val rest = sx.indices.filter { abs(sx[it] - l1.x(sy[it])) > 3 * med }
        if (rest.size >= minN) {
            val rx = DoubleArray(rest.size) { sx[rest[it]] }; val ry = DoubleArray(rest.size) { sy[rest[it]] }
            val rr = IntArray(rest.size) { sr[rest[it]] }
            val l2 = fitColumn(rx, ry, rr, tol, minDy = 0.5 * pitch)
            if (l2 != null && l2.n >= max(6, (0.4 * l1.n).toInt()) && l2.span >= 4 * pitch && l2.rms <= maxRms) {
                log.append(" col2 n=${l2.n} b=%.4f".format(l2.b))
                // Con trapecio la pendiente varía linealmente con x entre los dos márgenes
                val ym = (l2.y0 + l2.y1) / 2
                val exp = right?.let { r -> l1.b + (r.b - l1.b) * (l2.x(ym) - l1.x(ym)) / max(1.0, r.x(ym) - l1.x(ym)) } ?: l1.b
                if (abs(l2.b - exp) > max(0.006, 3 * (l1.seB + l2.seB))) {
                    val sub = rest.map { sIdx[it] }
                    return Result(angle, l1, right, null, w, h, guide, "$log columnas discrepan", local = true, guide2 = guidePointsOf(l2, sub, segs))
                }
                if (right == null && l2.n >= 0.6 * l1.n && l2.span >= minSpan) bL = (l1.b * l1.n + l2.b * l2.n) / (l1.n + l2.n)
            }
        }
        if (abs(bL) > 0.07) return Result(angle, l1, right, null, w, h, guide, "$log pendiente excesiva")
        // Pendientes no significativas: 0
        val sigL = abs(bL) >= 3 * l1.seB
        val bR = right?.let { if (abs(it.b) >= 3 * it.seB) it.b else 0.0 }
        val bl = if (sigL) bL else 0.0
        val span = max(l1.span, right?.span ?: 0.0)
        val drift = max(abs(bl), abs(bR ?: 0.0)) * span
        val minDrift = max(2.0 * max(w, h) / GridDewarp.EST_SIDE, 0.2 * med)
        if (drift < minDrift) return Result(angle, l1, right, null, w, h, guide, "$log recto (deriva %.1f px)".format(drift))
        // Corrección en el marco girado: cizalla (sólo izquierdo) o trapecio (ambos), centrada en la columna
        val yc = (l1.y0 + l1.y1) / 2
        val k = if (right != null && bR != null) {
            val xl = l1.x(yc); val xr = right.x(yc)
            if (xr - xl < 0.3 * w) return Result(angle, l1, right, null, w, h, guide, "$log columnas estrechas")
            keystone(xl, bl, xr, bR)
        } else keystone(0.0, bl, Double.NaN, Double.NaN)
        val kc = mul3(translate(0.0, yc), mul3(k, translate(0.0, -yc)))
        val m = mul3(kc, rot)
        // Seguridad: la corrección no deforma más de un 8 % en ningún punto de la hoja
        if (!moderate(m, rot, w, h)) return Result(angle, l1, right, null, w, h, guide, "$log deformación excesiva")
        log.append(" corrige deriva=%.1f px%s".format(drift, if (right != null) " trapecio" else " cizalla"))
        return Result(angle, l1, right, m, w, h, guide, log.toString())
    }

    /**
     * Mejor recta casi vertical x = a + b·y: barrido de pendientes |b| ≤ 0.1 (paso 0.0005) y, para cada una, la
     * ventana de intercepto de ancho 2·[tol] con más renglones distintos; luego mínimos cuadrados sobre los puntos
     * dentro de la ventana (2 iteraciones). [minDy]: dos puntos del mismo renglón cuentan una vez.
     */
    fun fitColumn(x: DoubleArray, y: DoubleArray, rows: IntArray, tol: Double, minDy: Double): Line? {
        val n = x.size
        if (n < 4) return null
        var bestScore = -1.0; var bestB = 0.0; var bestA = 0.0
        val ints = DoubleArray(n)
        // Renglones distintos en la ventana: contador por renglón mantenido al deslizarla
        val cnt = IntArray((rows.maxOrNull() ?: 0) + 1)
        var b = -0.1
        while (b <= 0.1 + 1e-9) {
            for (i in 0 until n) ints[i] = x[i] - b * y[i]
            val ord = (0 until n).sortedBy { ints[it] }
            cnt.fill(0)
            var lo = 0; var distinct = 0
            for (hi in 0 until n) {
                if (cnt[rows[ord[hi]]]++ == 0) distinct++
                while (ints[ord[hi]] - ints[ord[lo]] > 2 * tol) { if (--cnt[rows[ord[lo]]] == 0) distinct--; lo++ }
                // Desempate: ventana más compacta
                val sc = distinct - 1e-3 * (ints[ord[hi]] - ints[ord[lo]]) / tol
                if (sc > bestScore) { bestScore = sc; bestB = b; bestA = (ints[ord[hi]] + ints[ord[lo]]) / 2 }
            }
            b += 0.0005
        }
        if (bestScore < 3) return null
        var a = bestA; var bb = bestB
        var line: Line? = null
        repeat(3) {
            val idx = (0 until n).filter { abs(x[it] - (a + bb * y[it])) <= tol }
            // Un punto por renglón (el más cercano a la recta)
            val byRow = HashMap<Int, Int>()
            for (i in idx) { val p = byRow[rows[i]]; if (p == null || abs(x[i] - (a + bb * y[i])) < abs(x[p] - (a + bb * y[p]))) byRow[rows[i]] = i }
            val sel = byRow.values.sortedBy { y[it] }
            if (sel.size < 3) return line
            var my = 0.0; var mx = 0.0
            for (i in sel) { my += y[i]; mx += x[i] }
            my /= sel.size; mx /= sel.size
            var syy = 0.0; var sxy = 0.0
            for (i in sel) { syy += (y[i] - my) * (y[i] - my); sxy += (y[i] - my) * (x[i] - mx) }
            if (syy < minDy * minDy) return line
            bb = (sxy / syy).coerceIn(-0.12, 0.12); a = mx - bb * my
            var ss = 0.0
            for (i in sel) { val r = x[i] - (a + bb * y[i]); ss += r * r }
            val rms = sqrt(ss / sel.size)
            val se = if (sel.size > 2) sqrt(ss / (sel.size - 2) / syy) else Double.MAX_VALUE
            line = Line(a, bb, sel.size, rms, y[sel.first()], y[sel.last()], se, 0.0, sel.toIntArray())
        }
        return line
    }

    /** Extremos (xs, ys, xe, ye) de los tramos [ix] en el marco girado. */
    private fun segEnds(ix: List<Int>, segs: List<TextGuides.Seg>, rot: DoubleArray): Array<DoubleArray> {
        val o = DoubleArray(2)
        return Array(ix.size) { k ->
            val s = segs[ix[k]]
            apply(rot, s.xs.toDouble(), s.ys.toDouble(), o); val a0 = o[0]; val a1 = o[1]
            apply(rot, s.xe.toDouble(), s.ye.toDouble(), o)
            doubleArrayOf(a0, a1, o[0], o[1])
        }
    }

    /**
     * Soporte de la recta: entre los tramos de SU columna (empiezan entre 3 letras a la izquierda y 8 a la derecha de la
     * recta y siguen más allá; finales simétricos) en su tramo vertical (± 2 renglones), fracción que cae sobre ella.
     */
    private fun withSupport(l: Line, segsX: Array<DoubleArray>, start: Boolean, med: Double): Line {
        var cand = 0; var inl = 0
        val tol = max(0.4 * med, 1.5)
        for (s in segsX) {
            val xx = if (start) s[0] else s[2]; val yy = if (start) s[1] else s[3]
            if (yy < l.y0 - 3 * med || yy > l.y1 + 3 * med) continue
            val g = l.x(yy)
            val d = if (start) {
                if (xx < g - 3 * med || xx > g + 8 * med || s[2] < g + 3 * med) continue
                xx - g
            } else {
                if (xx > g + 3 * med || xx < g - 8 * med || s[0] > g - 3 * med) continue
                xx - g
            }
            cand++; if (abs(d) <= tol) inl++
        }
        val sup = if (cand == 0) 0.0 else inl.toDouble() / cand
        return Line(l.a, l.b, l.n, l.rms, l.y0, l.y1, l.seB, sup, l.idx)
    }

    /** Inicios de la columna en coordenadas de estimación (sin girar), ordenados por y. */
    private fun guidePointsOf(l: Line, sIdx: List<Int>, segs: List<TextGuides.Seg>): FloatArray {
        val sel = l.idx.map { segs[sIdx[it]] }.sortedBy { it.ys }
        val out = FloatArray(sel.size * 2)
        for ((k, s) in sel.withIndex()) { out[2 * k] = s.xs; out[2 * k + 1] = s.ys }
        return out
    }

    /**
     * Homografía (coordenadas x, Y = y − yc) que conserva las horizontales y la fila Y = 0 y lleva x = xl + bl·Y a x = xl
     * y x = xr + br·Y a x = xr: x' = (x + β·Y) / (1 + δ·Y), Y' = Y / (1 + δ·Y), δ = (br − bl)/(xr − xl), β = xl·δ − bl.
     * Con [xr] NaN, sólo cizalla (δ = 0, β = −bl).
     */
    fun keystone(xl: Double, bl: Double, xr: Double, br: Double): DoubleArray {
        if (xr.isNaN()) return doubleArrayOf(1.0, -bl, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val d = (br - bl) / (xr - xl)
        val beta = xl * d - bl
        return doubleArrayOf(1.0, beta, 0.0, 0.0, 1.0, 0.0, 0.0, d, 1.0)
    }

    /** Giro de `getRotationMatrix2D(centro, ángulo, 1)` como 3x3. */
    fun rotation(angleDeg: Double, cx: Double, cy: Double): DoubleArray {
        val a = Math.toRadians(angleDeg); val al = cos(a); val be = sin(a)
        return doubleArrayOf(al, be, (1 - al) * cx - be * cy, -be, al, be * cx + (1 - al) * cy, 0.0, 0.0, 1.0)
    }

    private fun translate(tx: Double, ty: Double) = doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0)

    fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** Aplica la homografía [m] a (x, y) -> [out]. */
    fun apply(m: DoubleArray, x: Double, y: Double, out: DoubleArray) {
        val z = m[6] * x + m[7] * y + m[8]
        out[0] = (m[0] * x + m[1] * y + m[2]) / z
        out[1] = (m[3] * x + m[4] * y + m[5]) / z
    }

    /** La corrección (sin el giro) mueve cada esquina de la hoja menos del 8 % de su lado y no invierte nada. */
    private fun moderate(m: DoubleArray, rot: DoubleArray, w: Double, h: Double): Boolean {
        val o1 = DoubleArray(2); val o2 = DoubleArray(2)
        for ((x, y) in listOf(0.0 to 0.0, w to 0.0, w to h, 0.0 to h)) {
            apply(m, x, y, o1); apply(rot, x, y, o2)
            if (abs(o1[0] - o2[0]) > 0.08 * w || abs(o1[1] - o2[1]) > 0.08 * h) return false
            if (m[6] * x + m[7] * y + m[8] <= 0.2) return false
        }
        return true
    }

    /** Matriz para una imagen de [w] x [h] (mismo encuadre que la de estimación) y su lienzo. */
    class Warp(val m: DoubleArray, val w: Int, val h: Int)

    /**
     * [Result.m] llevada a una imagen de [w] x [h] del mismo plano (otra escala) con su lienzo: con [expand] el lienzo
     * contiene la hoja entera (como el giro con lienzo ampliado; tope +12 %); sin él (recorte a mano: no se amplía) el
     * del giro solo, centrado.
     */
    fun warpFor(r: Result, w: Int, h: Int, expand: Boolean): Warp? {
        val m0 = r.m ?: return null
        val sx = r.w / w; val sy = r.h / h
        val s = doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0)
        val si = doubleArrayOf(1 / sx, 0.0, 0.0, 0.0, 1 / sy, 0.0, 0.0, 0.0, 1.0)
        val m = mul3(si, mul3(m0, s))
        val o = DoubleArray(2)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for ((x, y) in listOf(0.0 to 0.0, w.toDouble() to 0.0, w.toDouble() to h.toDouble(), 0.0 to h.toDouble())) {
            apply(m, x, y, o)
            x0 = min(x0, o[0]); y0 = min(y0, o[1]); x1 = max(x1, o[0]); y1 = max(y1, o[1])
        }
        val (rw, rh) = Cleanup.rotatedCanvas(w, h, r.angle)
        var nw = rw; var nh = rh
        if (expand) {
            nw = ceil(x1 - x0 - 0.2).toInt().coerceIn(1, (rw * 1.12).toInt().coerceAtLeast(rw))
            nh = ceil(y1 - y0 - 0.2).toInt().coerceIn(1, (rh * 1.12).toInt().coerceAtLeast(rh))
        }
        // Con lienzo ampliado, la hoja entera dentro; si no, su centro al centro del lienzo
        val t = if (expand && nw >= x1 - x0 - 1 && nh >= y1 - y0 - 1) translate(-x0, -y0) else {
            apply(m, w / 2.0, h / 2.0, o)
            translate(nw / 2.0 - o[0], nh / 2.0 - o[1])
        }
        return Warp(mul3(t, m), nw, nh)
    }
}
