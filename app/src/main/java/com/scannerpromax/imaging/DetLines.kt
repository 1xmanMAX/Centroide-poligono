package com.scannerpromax.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Hipótesis de documento a partir de RECTAS (sin OpenCV: lógica pura, probada en JVM).
 *
 * Los contornos cerrados fallan cuando el borde está interrumpido: dedos sobre una tarjeta, una esquina fuera de
 * la sombra, un póster detrás con el mismo color. Las rectas largas del borde siguen ahí: se agrupan los segmentos
 * (de HoughLinesP) en rectas, se forman pares de rectas casi paralelas (lados opuestos) y cada combinación de dos
 * pares con orientaciones distintas da un cuadrilátero (intersecciones). Quien llama puntúa cada hipótesis con el
 * apoyo real del borde, así que aquí sólo se descartan las geometrías imposibles.
 */
internal object DetLines {

    /** Recta agrupada: normal (cos θ, sin θ), distancia ρ al origen y longitud total de sus segmentos. */
    class Line(val theta: Double, val rho: Double, var length: Double)

    /**
     * Agrupa segmentos [segs] (x0,y0,x1,y1 consecutivos; [n] segmentos) en rectas: misma orientación (±[angTol]
     * rad) y misma distancia al origen (±[rhoTol] px). Devuelve como mucho [maxLines], las de mayor longitud.
     */
    fun cluster(segs: IntArray, n: Int, angTol: Double, rhoTol: Double, maxLines: Int): List<Line> {
        val order = (0 until n).sortedByDescending { segLen(segs, it) }
        val lines = ArrayList<Line>()
        for (i in order) {
            val x0 = segs[i * 4].toDouble(); val y0 = segs[i * 4 + 1].toDouble()
            val x1 = segs[i * 4 + 2].toDouble(); val y1 = segs[i * 4 + 3].toDouble()
            val len = hypot(x1 - x0, y1 - y0); if (len < 1e-6) continue
            // Normal con θ en [0, π)
            var th = atan2(x1 - x0, -(y1 - y0))
            var rho = ((x0 + x1) / 2) * cos(th) + ((y0 + y1) / 2) * sin(th)
            if (th < 0) { th += PI; rho = -rho }
            if (th >= PI) { th -= PI; rho = -rho }
            var merged = false
            for (l in lines) {
                var dth = abs(th - l.theta); var r = rho
                if (dth > PI / 2) { dth = PI - dth; r = -rho }   // θ cerca de 0 y de π: misma recta con ρ cambiado
                if (dth <= angTol && abs(r - l.rho) <= rhoTol) { l.length += len; merged = true; break }
            }
            if (!merged) lines.add(Line(th, rho, len))
        }
        return lines.sortedByDescending { it.length }.take(maxLines)
    }

    private fun segLen(s: IntArray, i: Int) =
        hypot((s[i * 4 + 2] - s[i * 4]).toDouble(), (s[i * 4 + 3] - s[i * 4 + 1]).toDouble())

    /** Diferencia de orientación entre dos rectas, en [0, π/2]. */
    fun angleDiff(a: Line, b: Line): Double { val d = abs(a.theta - b.theta); return min(d, PI - d) }

    /** Intersección de dos rectas (x, y) o null si son casi paralelas. */
    fun intersect(a: Line, b: Line): DoubleArray? {
        val ca = cos(a.theta); val sa = sin(a.theta); val cb = cos(b.theta); val sb = sin(b.theta)
        val det = ca * sb - sa * cb
        if (abs(det) < 1e-6) return null
        return doubleArrayOf((a.rho * sb - b.rho * sa) / det, (ca * b.rho - cb * a.rho) / det)
    }

    /**
     * Cuadriláteros (tl,tr,br,bl como en [DocumentDetector.orderPoints]) formados por dos pares de rectas casi
     * paralelas ([parTol] rad) con orientaciones separadas al menos [crossMin] rad, en una imagen de [w]x[h]:
     * convexos, con las esquinas dentro de la imagen (holgura [margin] px; luego se recortan), lados >= [minSide] px
     * y área entre [minArea] y [maxArea] (fracción de la imagen). Como mucho [maxQuads], de mayor perímetro de
     * rectas que los apoyan.
     */
    fun quads(
        lines: List<Line>, w: Int, h: Int, parTol: Double = 0.45, crossMin: Double = 0.6,
        margin: Double = 0.03 * max(w, h), minSide: Double = 0.06 * max(w, h),
        minArea: Double = 0.02, maxArea: Double = 0.97, maxQuads: Int = 400,
    ): List<FloatArray> {
        val pairs = ArrayList<IntArray>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            if (angleDiff(lines[i], lines[j]) > parTol) continue
            // Separación entre ambas (en el punto medio de la imagen): lados opuestos, no la misma recta
            val pi = pointOn(lines[i], w / 2.0, h / 2.0); val dist = distTo(lines[j], pi[0], pi[1])
            if (dist < minSide) continue
            pairs.add(intArrayOf(i, j))
        }
        val out = ArrayList<Pair<Double, FloatArray>>()
        val total = w.toDouble() * h
        val p = arrayOfNulls<DoubleArray>(4)
        for (a in pairs.indices) for (b in a + 1 until pairs.size) {
            val pa = pairs[a]; val pb = pairs[b]
            if (pa[0] == pb[0] || pa[0] == pb[1] || pa[1] == pb[0] || pa[1] == pb[1]) continue
            val la1 = lines[pa[0]]; val la2 = lines[pa[1]]; val lb1 = lines[pb[0]]; val lb2 = lines[pb[1]]
            if (min(min(angleDiff(la1, lb1), angleDiff(la1, lb2)), min(angleDiff(la2, lb1), angleDiff(la2, lb2))) < crossMin) continue
            p[0] = intersect(la1, lb1); p[1] = intersect(lb1, la2); p[2] = intersect(la2, lb2); p[3] = intersect(lb2, la1)
            var ok = true
            for (k in 0 until 4) {
                val q = p[k]
                if (q == null || q[0] < -margin || q[1] < -margin || q[0] > w - 1 + margin || q[1] > h - 1 + margin) { ok = false; break }
            }
            if (!ok) continue
            val pts = FloatArray(8)
            for (k in 0 until 4) {
                pts[k * 2] = p[k]!![0].coerceIn(0.0, w - 1.0).toFloat(); pts[k * 2 + 1] = p[k]!![1].coerceIn(0.0, h - 1.0).toFloat()
            }
            val ordered = order(pts) ?: continue
            val ar = area(ordered) / total
            if (ar < minArea || ar > maxArea) continue
            var shortest = Double.MAX_VALUE
            for (k in 0 until 4) {
                val k2 = (k + 1) % 4
                shortest = min(shortest, hypot((ordered[k2 * 2] - ordered[k * 2]).toDouble(), (ordered[k2 * 2 + 1] - ordered[k * 2 + 1]).toDouble()))
            }
            if (shortest < minSide) continue
            out.add((la1.length + la2.length + lb1.length + lb2.length) to ordered)
        }
        return out.sortedByDescending { it.first }.take(maxQuads).map { it.second }
    }

    private fun pointOn(l: Line, x: Double, y: Double): DoubleArray {
        // proyección de (x, y) sobre la recta
        val c = cos(l.theta); val s = sin(l.theta); val d = x * c + y * s - l.rho
        return doubleArrayOf(x - d * c, y - d * s)
    }

    private fun distTo(l: Line, x: Double, y: Double) = abs(x * cos(l.theta) + y * sin(l.theta) - l.rho)

    /** Ordena 4 puntos (tl,tr,br,bl) si forman un cuadrilátero convexo; null si no. */
    fun order(q: FloatArray): FloatArray? {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4.0; val cy = (q[1] + q[3] + q[5] + q[7]) / 4.0
        val idx = (0 until 4).sortedBy { atan2(q[it * 2 + 1] - cy, q[it * 2] - cx) }
        var start = 0; var best = Double.MAX_VALUE
        for ((k, i) in idx.withIndex()) { val s = (q[i * 2] + q[i * 2 + 1]).toDouble(); if (s < best) { best = s; start = k } }
        val o = FloatArray(8)
        for (k in 0 until 4) { val i = idx[(start + k) % 4]; o[k * 2] = q[i * 2]; o[k * 2 + 1] = q[i * 2 + 1] }
        // Convexo: productos cruz del mismo signo
        var sign = 0
        for (k in 0 until 4) {
            val a = (k + 3) % 4; val c = (k + 1) % 4
            val cross = (o[a * 2] - o[k * 2]).toDouble() * (o[c * 2 + 1] - o[k * 2 + 1]) - (o[a * 2 + 1] - o[k * 2 + 1]).toDouble() * (o[c * 2] - o[k * 2])
            val sg = if (cross > 1e-6) 1 else if (cross < -1e-6) -1 else 0
            if (sg == 0) return null
            if (sign == 0) sign = sg else if (sg != sign) return null
        }
        return o
    }

    fun area(q: FloatArray): Double {
        var s = 0.0
        for (i in 0 until 4) { val j = (i + 1) % 4; s += q[i * 2].toDouble() * q[j * 2 + 1] - q[j * 2].toDouble() * q[i * 2 + 1] }
        return abs(s) / 2
    }
}
