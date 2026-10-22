package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Orientación de una página YA RECTIFICADA por la dirección de sus renglones (sin modelos; JVM + OpenCV):
 *
 *  1. Caracteres = tinta oscura respecto del papel de alrededor, componentes pequeñas (sin rayas, recuadros ni dibujos).
 *  2. EJE: el vecino más próximo de cada carácter está en su mismo renglón (los caracteres de una línea están más juntos
 *     que dos líneas entre sí): mayoría clara de vecinos en horizontal → renglones horizontales; en vertical → la hoja
 *     está girada 90°. Una cuadrícula o un dibujo no dan mayoría y no se decide nada.
 *  3. SENTIDO: con los renglones en horizontal (enderezados unos grados por proyección o por los vecinos, lo que deje más
 *     renglones limpios), en cada renglón la línea base y la línea de la x son las MODAS del borde inferior y superior
 *     por columnas; en español/inglés asoman muchas más columnas por ARRIBA (b d f h k l t, mayúsculas, tildes, puntos)
 *     que por abajo (g j p q y). Se mide la imagen y la imagen girada 180° (deben dar signos opuestos) y se combinan.
 *
 * Sólo devuelve un giro cuando todo es claro ([Result.sure]); si no, 0 (no girar). En el banco (600 páginas derechas
 * giradas 0/90/180/270) no gira por error ninguna página que no estuviera ya girada en el propio conjunto de datos.
 */
internal object TextOrientation {

    /**
     * [rotation] = giro HORARIO que endereza la página (0/90/180/270; 0 también cuando no hay certeza); [vertical] = los
     * renglones van en vertical (null = eje dudoso); [up] = evidencia de sentido (−1..1, >0 = ascendentes arriba tras
     * poner los renglones en horizontal girando 90° horario si eran verticales); [lines] = renglones medidos.
     */
    class Result(val rotation: Int, val vertical: Boolean?, val up: Double, val lines: Int, val sure: Boolean, val fh: Double, val fv: Double) {
        override fun toString() = "rot=$rotation vertical=$vertical up=%.3f lines=$lines sure=$sure fh=%.2f fv=%.2f".format(java.util.Locale.ROOT, up, fh, fv)
    }

    private const val WORK_SIDE = 1400
    /** Eje: fracción mínima de vecinos en la dirección dominante y ventaja sobre la otra. */
    private const val AXIS_MIN = 0.45
    private const val AXIS_RATIO = 2.0
    /** Sentido (90/270): renglones medidos (imagen + girada) y evidencia mínima. */
    private const val MIN_LINES = 10
    private const val MIN_UP = 0.25
    /** Sentido (180, más arriesgado: una página derecha girada por error se nota mucho). */
    private const val MIN_LINES_180 = 14
    private const val MIN_UP_180 = 0.4

    /** [gray] 8UC1 de la página rectificada (cualquier tamaño; se reduce a ~[WORK_SIDE]). */
    fun estimate(gray: Mat): Result = MatBag().use { bag ->
        val none = Result(0, null, 0.0, 0, false, 0.0, 0.0)
        val g = bag.mat()
        if (max(gray.cols(), gray.rows()) > WORK_SIDE) Cv.downscale(gray, g, WORK_SIDE) else gray.copyTo(g)
        val w = g.cols(); val h = g.rows()
        if (min(w, h) < 100) return@use none
        // 1) Caracteres
        val k = Cv.odd(max(9, (max(w, h) * 0.012).toInt()))
        val bg = bag.mat(); Imgproc.morphologyEx(g, bg, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, k))
        val n = w * h
        val gb = ByteArray(n); g.get(0, 0, gb)
        val bb = ByteArray(n); bg.get(0, 0, bb)
        val raw = ByteArray(n)
        for (i in 0 until n) {
            val b = bb[i].toInt() and 0xFF; val v = gb[i].toInt() and 0xFF
            if (b > 80 && b - v > max(18.0, 0.14 * b)) raw[i] = 1
        }
        val rawM = bag.mat(); rawM.create(h, w, CvType.CV_8UC1); rawM.put(0, 0, raw)
        val lbl = bag.mat(); val st = bag.mat(); val ce = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(rawM, lbl, st, ce, 8, CvType.CV_32S)
        val s = IntArray(nc * 5); if (nc > 0) st.get(0, 0, s)
        val big = 0.05 * max(w, h)
        val keep = BooleanArray(nc)
        var nk = 0
        for (c in 1 until nc) {
            val cw = s[c * 5 + 2]; val chh = s[c * 5 + 3]; val a = s[c * 5 + 4]
            if (a >= 3 && cw <= big && chh <= big && max(cw, chh) >= 3) { keep[c] = true; nk++ }
        }
        if (nk < 30) return@use none
        val la = IntArray(n); lbl.get(0, 0, la)
        val ink = ByteArray(n)
        for (i in 0 until n) if (keep[la[i]]) ink[i] = 1
        val cx = DoubleArray(nk); val cy = DoubleArray(nk); val hs = IntArray(nk); val ws = IntArray(nk)
        var j = 0
        for (c in 1 until nc) if (keep[c]) {
            cx[j] = s[c * 5] + s[c * 5 + 2] / 2.0; cy[j] = s[c * 5 + 1] + s[c * 5 + 3] / 2.0
            ws[j] = s[c * 5 + 2]; hs[j] = s[c * 5 + 3]; j++
        }
        // 2) Eje por vecinos más próximos
        val cell = max(8.0, 3.0 * median(hs))
        val vec = nnVectors(cx, cy, cell)
        var hz = 0; var vt = 0; var tot = 0
        for (v in vec) {
            if (v == null) continue
            tot++
            val ang = Math.toDegrees(atan2(abs(v[1]), abs(v[0])))
            if (ang < 20) hz++ else if (ang > 70) vt++
        }
        if (tot < 30) return@use none
        val fh = hz.toDouble() / tot; val fv = vt.toDouble() / tot
        val vertical = fv >= AXIS_MIN && fv >= AXIS_RATIO * fh
        val horizontal = fh >= AXIS_MIN && fh >= AXIS_RATIO * fv
        if (!vertical && !horizontal) return@use Result(0, null, 0.0, 0, false, fh, fv)
        // 3) Renglones en horizontal (90° horario si eran verticales) y sentido
        val inkM = bag.mat(); inkM.create(h, w, CvType.CV_8UC1); inkM.put(0, 0, ink)
        val im = bag.mat()
        if (vertical) Core.rotate(inkM, im, Core.ROTATE_90_CLOCKWISE) else inkM.copyTo(im)
        val c = max(3.0, median(if (vertical) ws else hs))
        var best: DoubleArray? = null   // (up, upFlip, nLines)
        val angles = linkedSetOf(Math.round(projSkew(im) * 100) / 100.0, Math.round(nnSkew(im, bag) * 100) / 100.0)
        for (ang in angles) {
            val im2 = bag.mat()
            if (abs(ang) > 0.1) {
                val rm = Imgproc.getRotationMatrix2D(Point(im.cols() / 2.0, im.rows() / 2.0), ang, 1.0)
                Imgproc.warpAffine(im, im2, rm, im.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0)); rm.release()
            } else im.copyTo(im2)
            val a = ascTest(im2, c, bag)
            val fl = bag.mat(); Core.rotate(im2, fl, Core.ROTATE_180)
            val b = ascTest(fl, c, bag)
            if (best == null || a[2] + b[2] > best[2]) best = doubleArrayOf(a[0], b[0], a[2] + b[2])
        }
        val bst = best ?: return@use Result(0, vertical, 0.0, 0, false, fh, fv)
        val up = (bst[0] - bst[1]) / 2; val nl = bst[2].toInt()
        val consistent = bst[0] * -bst[1] > 0
        if (vertical) {
            val sure = consistent && nl >= MIN_LINES && abs(up) >= MIN_UP
            Result(if (sure) (if (up > 0) 90 else 270) else 0, true, up, nl, sure, fh, fv)
        } else {
            val sure = consistent && nl >= MIN_LINES_180 && up <= -MIN_UP_180
            Result(if (sure) 180 else 0, false, up, nl, sure || (consistent && nl >= MIN_LINES && up >= MIN_UP), fh, fv)
        }
    }

    /**
     * Giro horario final combinando el análisis de renglones [r] con el modelo de orientación opcional: [probs] = 4
     * probabilidades de que la página TENGA un giro horario de 0/90/180/270 (PP-LCNet doc_ori), o null sin modelo.
     *  - Renglones verticales seguros: se gira salvo que el modelo diga con fuerza el sentido contrario.
     *  - Renglones verticales sin sentido claro: decide el modelo entre 90 y 270 si es contundente.
     *  - 180°: sólo con el análisis seguro y, si hay modelo, que esté de acuerdo.
     *  - Eje dudoso (cuadrícula, dibujo, poco texto): nunca se gira.
     */
    fun decide(r: Result, probs: FloatArray?): Int {
        val p = probs?.takeIf { it.size >= 4 && it.all { v -> v.isFinite() } }
        return when (r.vertical) {
            true -> {
                if (p == null) return r.rotation
                val n90 = p[3].toDouble(); val n270 = p[1].toDouble()
                if (r.sure) {
                    if ((r.rotation == 90 && n270 > MODEL_VETO) || (r.rotation == 270 && n90 > MODEL_VETO)) 0 else r.rotation
                } else {
                    val hi = max(n90, n270); val lo = min(n90, n270)
                    if (hi >= MODEL_ALONE && hi >= 3 * lo) (if (n90 > n270) 90 else 270) else 0
                }
            }
            false -> if (r.rotation == 180 && (p == null || p[2] >= 0.5f)) 180 else 0
            null -> 0
        }
    }

    /** Modelo contra el análisis seguro (veto) y modelo solo entre 90/270 con renglones verticales. */
    private const val MODEL_VETO = 0.8
    private const val MODEL_ALONE = 0.6

    private fun median(v: IntArray): Double { if (v.isEmpty()) return 0.0; val c = v.copyOf(); c.sort(); return c[c.size / 2].toDouble() }

    /** Vector al vecino más próximo de cada punto (rejilla de celdas de lado [cell]); null si no hay vecino cerca. */
    private fun nnVectors(x: DoubleArray, y: DoubleArray, cell: Double): Array<DoubleArray?> {
        val n = x.size
        val step = max(1, n / 2500)
        val idx = (0 until n step step).toList()
        var maxX = 0.0; var maxY = 0.0
        for (i in 0 until n) { maxX = max(maxX, x[i]); maxY = max(maxY, y[i]) }
        val gw = (maxX / cell).toInt() + 1; val gh = (maxY / cell).toInt() + 1
        val buckets = HashMap<Int, MutableList<Int>>()
        for (i in idx) buckets.getOrPut((y[i] / cell).toInt() * gw + (x[i] / cell).toInt()) { ArrayList(4) }.add(i)
        return Array(idx.size) { k ->
            val i = idx[k]
            val gx = (x[i] / cell).toInt(); val gy = (y[i] / cell).toInt()
            var bestD = Double.MAX_VALUE; var bj = -1
            for (r in 1..2) {
                for (yy in gy - r..gy + r) for (xx in gx - r..gx + r) {
                    if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue
                    val l = buckets[yy * gw + xx] ?: continue
                    for (o in l) if (o != i) {
                        val d = (x[o] - x[i]) * (x[o] - x[i]) + (y[o] - y[i]) * (y[o] - y[i])
                        if (d < bestD) { bestD = d; bj = o }
                    }
                }
                if (bj >= 0) break
            }
            if (bj < 0) null else doubleArrayOf(x[bj] - x[i], y[bj] - y[i])
        }
    }

    /** Inclinación (grados, ±8) que maximiza la energía del perfil de filas de la tinta. */
    private fun projSkew(ink: Mat): Double {
        val w = ink.cols(); val h = ink.rows()
        val b = ByteArray(w * h); ink.get(0, 0, b)
        var cnt = 0; for (v in b) if (v.toInt() != 0) cnt++
        if (cnt < 100) return 0.0
        val stride = max(1, cnt / 40000)
        val xs = IntArray(cnt / stride + 1); val ys = IntArray(cnt / stride + 1); var m = 0; var seen = 0
        for (i in b.indices) if (b[i].toInt() != 0) { if (seen++ % stride == 0 && m < xs.size) { xs[m] = i % w; ys[m] = i / w; m++ } }
        val hist = IntArray(w + h + 4)
        var bestA = 0.0; var bestS = -1.0
        var deg = -8.0
        while (deg <= 8.0001) {
            val t = Math.toRadians(deg); val ct = cos(t); val stt = sin(t)
            java.util.Arrays.fill(hist, 0)
            val off = w * abs(stt) + 1
            for (k in 0 until m) {
                val v = (ys[k] * ct - xs[k] * stt + off).toInt().coerceIn(0, hist.size - 1)
                hist[v]++
            }
            var sc = 0.0; for (v in hist) sc += v.toDouble() * v
            if (sc > bestS) { bestS = sc; bestA = deg }
            deg += 0.25
        }
        return bestA
    }

    /** Inclinación (grados) como mediana de las direcciones casi horizontales entre caracteres vecinos. */
    private fun nnSkew(ink: Mat, bag: MatBag): Double {
        val lbl = bag.mat(); val st = bag.mat(); val ce = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(ink, lbl, st, ce, 8, CvType.CV_32S)
        if (nc < 21) return 0.0
        val s = IntArray(nc * 5); st.get(0, 0, s)
        val x = DoubleArray(nc - 1) { s[(it + 1) * 5] + s[(it + 1) * 5 + 2] / 2.0 }
        val y = DoubleArray(nc - 1) { s[(it + 1) * 5 + 1] + s[(it + 1) * 5 + 3] / 2.0 }
        val hs = IntArray(nc - 1) { s[(it + 1) * 5 + 3] }
        val vec = nnVectors(x, y, max(8.0, 3.0 * median(hs)))
        val a = ArrayList<Double>()
        for (v in vec) {
            if (v == null) continue
            var dx = v[0]; var dy = v[1]
            if (dx < 0) { dx = -dx; dy = -dy }
            val ang = Math.toDegrees(atan2(dy, dx))
            if (abs(ang) < 15) a.add(ang)
        }
        if (a.size < 10) return 0.0
        a.sort()
        return a[a.size / 2]
    }

    /**
     * Renglones horizontales de [ink] (cierre horizontal de ~1,6 caracteres): (evidencia −1..1, —, renglones). Evidencia
     * = (columnas que asoman por arriba de la línea de la x − columnas que asoman por debajo de la base) / total.
     */
    private fun ascTest(ink: Mat, c: Double, bag: MatBag): DoubleArray {
        val w = ink.cols(); val h = ink.rows()
        val kl = Cv.odd(max(3, (1.6 * c).roundToInt()))
        val sm = bag.mat(); Imgproc.morphologyEx(ink, sm, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, kl, 1))
        val lbl = bag.mat(); val st = bag.mat(); val ce = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(sm, lbl, st, ce, 8, CvType.CV_32S)
        if (nc < 2) return doubleArrayOf(0.0, 0.0, 0.0)
        val s = IntArray(nc * 5); st.get(0, 0, s)
        val la = IntArray(w * h); lbl.get(0, 0, la)
        val ib = ByteArray(w * h); ink.get(0, 0, ib)
        var up = 0L; var dn = 0L; var lines = 0
        for (cc in 1 until nc) {
            val x0 = s[cc * 5]; val y0 = s[cc * 5 + 1]; val bw = s[cc * 5 + 2]; val bh = s[cc * 5 + 3]
            if (bw < 6 * c || bh > 1.9 * c || bh < 0.7 * c) continue
            val tops = IntArray(bw); val bots = IntArray(bw); var nCols = 0
            for (x in x0 until x0 + bw) {
                var t = -1; var bt = -1
                for (y in y0 until y0 + bh) {
                    val i = y * w + x
                    if (ib[i].toInt() != 0 && la[i] == cc) { if (t < 0) t = y - y0; bt = y - y0 }
                }
                if (t >= 0) { tops[nCols] = t; bots[nCols] = bt; nCols++ }
            }
            if (nCols < 4 * c) continue
            val xl = mode(tops, nCols, bh); val bl = mode(bots, nCols, bh)
            val xh = bl - xl
            if (xh < max(2.0, 0.3 * c) || xh > 1.05 * c) continue
            val thr = max(1.0, 0.35 * xh)
            var u = 0; var d = 0
            for (k in 0 until nCols) { if (tops[k] < xl - thr) u++; if (bots[k] > bl + thr) d++ }
            if (u + d < 3) continue
            up += u; dn += d; lines++
        }
        if (lines == 0) return doubleArrayOf(0.0, 0.0, 0.0)
        return doubleArrayOf((up - dn).toDouble() / max(1L, up + dn), 0.0, lines.toDouble())
    }

    private fun mode(v: IntArray, n: Int, range: Int): Int {
        val hgm = IntArray(range + 1)
        for (k in 0 until n) hgm[v[k].coerceIn(0, range)]++
        var b = 0; for (i in hgm.indices) if (hgm[i] > hgm[b]) b = i
        return b
    }
}
