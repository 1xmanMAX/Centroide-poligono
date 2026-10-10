package com.scannerpromax.imaging

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * BORDES DE LA HOJA como guías del enderezado ([GridDewarp]).
 *
 * En la hoja real los cuatro cantos son rectos; en la foto de una hoja curvada, doblada o arrugada son curvas o
 * quebradas (cada pliegue es un cambio de pendiente del canto). Tras la perspectiva (el recorte es un cuadrilátero
 * recto), el canto real queda cerca del lado nominal del recorte pero no sobre él: hacia fuera (la hoja se sale del
 * recorte y se perdería) o hacia dentro (cuñas de mesa dentro del recorte).
 *
 * 1. **Detección** ([detect]) sobre el plano rectificado AMPLIADO (un margen alrededor del recorte, tomado de la foto):
 *    por cada lado, perfiles transversales a lo largo del lado nominal sobre la imagen sin texto (cierre morfológico)
 *    en Lab; la puntuación de un canto en cada posición es el contraste entre ambos lados multiplicada por lo "papel"
 *    que es el lado interior (luminosidad y croma cercanas a las del papel de la hoja: así el borde de una foto o de
 *    una banda de color impresa no se toma por el canto) y una ligera preferencia por lo más exterior. El camino de
 *    máxima puntuación con pendiente acotada se obtiene por programación dinámica ([DwPath.bestPath]); se acepta el
 *    lado si la mayor parte del camino tiene apoyo (canto nítido) y no deja fuera contenido de la hoja.
 * 2. **Modelo** ([fit]): los cantos entran en el mismo ajuste de malla que las líneas y renglones, como líneas con
 *    POSICIÓN impuesta en la salida (canto superior en v = 0, inferior en v = alto, izquierdo en u = 0, derecho en
 *    u = ancho). Las líneas de la hoja (tabla, renglones) siguen exigiendo rectitud; la placa delgada interpola entre
 *    cantos. El campo se invierte sobre el rectángulo de la hoja: la salida contiene la hoja entera (lo que se salía del
 *    recorte se recupera de la foto) y nada de la mesa.
 */
internal object DwBorder {

    /** Activable en el banco de pruebas. */
    internal var enabled = true

    /** Depuración (banco de pruebas): recibe el plano ampliado y los lados aceptados. */
    internal var debugSink: ((Input, List<Side>) -> Unit)? = null

    /** Fracción del lado del recorte que se amplía en cada lado para buscar el canto. */
    const val MARGIN = 0.07

    /** Lado de trabajo de la detección (px). */
    private const val WORK_SIDE = 800

    /** Entrada: plano rectificado ampliado (RGB 8UC3, px de estimación) con la hoja nominal en [mx, mx+w) x [my, my+h). */
    class Input(val img: Mat, val valid: Mat?, val mx: Int, val my: Int, val w: Int, val h: Int)

    /**
     * Canto encontrado. [index]: 0 superior, 1 derecho, 2 inferior, 3 izquierdo. [line] en px de estimación del marco
     * ampliado, con la posición impuesta (target). [dev] = desviación de su recta (px), [offset] = percentil 90 de la
     * distancia al lado nominal (px).
     */
    class Side(val index: Int, val line: DewarpMath.LineObs, val support: Double, val dev: Double, val offset: Double, val score: Double)

    /** Detecta los cantos de la hoja en [inp]. Lista (posiblemente vacía) de lados aceptados. */
    fun detect(inp: Input, trace: Boolean = false): List<Side> = MatBag().use { bag ->
        val W = inp.img.cols(); val H = inp.img.rows()
        val s = min(1.0, WORK_SIDE.toDouble() / max(W, H))
        val sm = bag.mat()
        if (s < 1.0) Imgproc.resize(inp.img, sm, Size((W * s).roundToInt().toDouble(), (H * s).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        else inp.img.copyTo(sm)
        val ws = sm.cols(); val hs = sm.rows()
        // Sin texto: cierre (los trazos oscuros finos desaparecen) y suavizado
        val k = Cv.odd(max(5, min(ws, hs) / 90))
        Imgproc.morphologyEx(sm, sm, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, k))
        Imgproc.GaussianBlur(sm, sm, Size(3.0, 3.0), 0.0)
        val lab = bag.mat()
        Imgproc.cvtColor(sm, lab, Imgproc.COLOR_RGB2Lab)
        val px = ByteArray(ws * hs * 3); lab.get(0, 0, px)
        val vb: ByteArray? = inp.valid?.let { v ->
            val vs = bag.mat()
            Imgproc.resize(v, vs, Size(ws.toDouble(), hs.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            ByteArray(ws * hs).also { vs.get(0, 0, it) }
        }
        val x0 = inp.mx * s; val y0 = inp.my * s; val iw = inp.w * s; val ih = inp.h * s
        // Color del papel: lo claro del interior (sin el 12 % junto a los lados)
        val paper = paperLab(px, ws, hs, x0 + 0.12 * iw, y0 + 0.12 * ih, x0 + 0.88 * iw, y0 + 0.88 * ih) ?: return@use emptyList()
        val band = max(6, (min(iw, ih) * (MARGIN * 1.6)).roundToInt())
        val out = ArrayList<Side>()
        for (side in 0..3) {
            val horiz = side == 0 || side == 2
            val len = if (horiz) iw else ih
            val tA = 0.06 * len; val tB = 0.94 * len
            val step = 2.0
            val nT = ((tB - tA) / step).toInt() + 1
            if (nT < 20) continue
            val nS = 2 * band + 1
            val L = FloatArray(nT * nS); val A = FloatArray(nT * nS); val B = FloatArray(nT * nS); val ok = BooleanArray(nT * nS)
            for (ti in 0 until nT) {
                val t = tA + ti * step
                for (j in 0 until nS) {
                    val d = (j - band).toDouble()  // + hacia dentro
                    val (fx, fy) = when (side) {
                        0 -> (x0 + t) to (y0 + d)
                        2 -> (x0 + t) to (y0 + ih - 1 - d)
                        3 -> (x0 + d) to (y0 + t)
                        else -> (x0 + iw - 1 - d) to (y0 + t)
                    }
                    val xi = fx.roundToInt(); val yi = fy.roundToInt()
                    val q = ti * nS + j
                    if (xi < 0 || yi < 0 || xi >= ws || yi >= hs) continue
                    val p = yi * ws + xi
                    if (vb != null && (vb[p].toInt() and 0xFF) < 250) continue
                    ok[q] = true
                    L[q] = (px[3 * p].toInt() and 0xFF).toFloat()
                    A[q] = (px[3 * p + 1].toInt() and 0xFF).toFloat()
                    B[q] = (px[3 * p + 2].toInt() and 0xFF).toFloat()
                }
            }
            val score = DwPath.edgeScores(L, A, B, ok, nT, nS, paper[0], paper[1], paper[2], win = 3)
            val path = DwPath.bestPath(score, nT, nS, maxStep = 2, penalty = 1.5f)
            val sc = FloatArray(nT) { score[it * nS + path[it]] }
            val supported = BooleanArray(nT) { sc[it] >= SUPPORT_SCORE }
            val support = supported.count { it }.toDouble() / nT
            val meanScore = sc.average()
            if (trace) println("  canto $side: apoyo=%.2f media=%.1f".format(support, meanScore))
            if (support < 0.6 || meanScore < 14.0) continue
            // Curva en px de estimación (1 px hacia dentro: mejor perder un hilo de margen que traer la mesa)
            val ts = ArrayList<Float>(); val cs = ArrayList<Float>()
            for (ti in 0 until nT) if (supported[ti]) {
                val t = tA + ti * step
                val d = path[ti] - band + 1.0
                val c = when (side) { 0 -> y0 + d; 2 -> y0 + ih - 1 - d; 3 -> x0 + d; else -> x0 + iw - 1 - d }
                val tt = if (horiz) x0 + t else y0 + t
                ts.add((tt / s).toFloat()); cs.add((c / s).toFloat())
            }
            val raw = DewarpMath.Chain(ts.toFloatArray(), cs.toFloatArray())
            val sm1 = DewarpMath.robustSmooth(raw, win = (12 / s).toFloat(), outTol = (2.0 / s).toFloat(), minInlier = 0.6f) ?: continue
            val ch = DewarpMath.quadSmooth(sm1, (24 / s).toFloat())
            val nominal = when (side) { 0 -> inp.my.toDouble(); 2 -> inp.my + inp.h - 1.0; 3 -> inp.mx.toDouble(); else -> inp.mx + inp.w - 1.0 }
            val line = if (horiz) DewarpMath.LineObs(true, ch.t, ch.c, weight = BORDER_WEIGHT, target = nominal)
            else DewarpMath.LineObs(false, ch.c, ch.t, weight = BORDER_WEIGHT, target = nominal)
            val offs = ch.c.map { abs(it - nominal) }.sorted()
            val off = offs[(offs.size * 0.9).toInt().coerceAtMost(offs.size - 1)]
            out.add(Side(side, line, support, DewarpMath.straightnessDev(line), off, meanScore))
        }
        debugSink?.invoke(inp, out)
        out
    }

    private const val SUPPORT_SCORE = 10f
    private const val BORDER_WEIGHT = 3.0

    /** Lab medio de lo claro (percentil ≥ 60 de L) del rectángulo dado. */
    internal fun paperLab(px: ByteArray, ws: Int, hs: Int, ax: Double, ay: Double, bx: Double, by: Double): DoubleArray? {
        val x0 = ax.toInt().coerceIn(0, ws - 1); val x1 = bx.toInt().coerceIn(0, ws - 1)
        val y0 = ay.toInt().coerceIn(0, hs - 1); val y1 = by.toInt().coerceIn(0, hs - 1)
        if (x1 <= x0 || y1 <= y0) return null
        val hist = IntArray(256); var n = 0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) { hist[px[3 * (y * ws + x)].toInt() and 0xFF]++; n++ }
        var acc = 0; var thr = 0
        while (thr < 255 && acc + hist[thr] < 0.6 * n) { acc += hist[thr]; thr++ }
        var sl = 0.0; var sa = 0.0; var sb = 0.0; var m = 0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) {
            val p = 3 * (y * ws + x)
            val l = px[p].toInt() and 0xFF
            if (l < thr) continue
            sl += l; sa += px[p + 1].toInt() and 0xFF; sb += px[p + 2].toInt() and 0xFF; m++
        }
        if (m < 50 || sl / m < 60) return null
        return doubleArrayOf(sl / m, sa / m, sb / m)
    }

    /**
     * Quita los lados que dejarían fuera CONTENIDO de la hoja: puntos de tinta fina rodeada de papel ([content]: tríos
     * x, y, componente en px del plano NO ampliado) entre el lado nominal y el canto detectado (canto hacia dentro).
     */
    fun dropLossy(sides: List<Side>, content: FloatArray, mx: Int, my: Int, maxLost: Int = 25): List<Side> = sides.filter { sd ->
        val l = sd.line
        val ch = if (l.horizontal) DewarpMath.Chain(l.x, l.y) else DewarpMath.Chain(l.y, l.x)
        var lost = 0
        for (i in content.indices step 3) {
            val x = content[i] + mx; val y = content[i + 1] + my
            val along = if (l.horizontal) x else y
            if (along < ch.t0 || along > ch.t1) continue
            val c = DewarpMath.interp(ch, along)
            val v = if (l.horizontal) y else x
            val outside = when (sd.index) { 0, 3 -> v < c - 1f; else -> v > c + 1f }
            if (outside) lost++
        }
        lost <= maxLost
    }

    /**
     * Quita los lados que CORTAN líneas de la hoja: una línea perpendicular al lado (renglón, regla, borde de una banda
     * impresa; [lines] ya en el marco ampliado) pertenece a la hoja, así que el canto debe quedar fuera de ella. Si
     * algún tramo de una línea queda más de [tol] px al otro lado del canto (fuera de la hoja), el "canto" es un cambio
     * de tono dentro de la propia hoja (sombra, rizo del borde, banda de color) y el lado se descarta.
     */
    fun dropCutting(sides: List<Side>, lines: List<DewarpMath.LineObs>, tol: Float = 3f, minPts: Int = 3): List<Side> = sides.filter { sd ->
        val l = sd.line
        val ch = if (l.horizontal) DewarpMath.Chain(l.x, l.y) else DewarpMath.Chain(l.y, l.x)
        lines.none { ln ->
            if (ln.horizontal == l.horizontal || ln.loose) return@none false
            var out = 0
            for (i in 0 until ln.size) {
                // a lo largo del canto / transversal al canto
                val along = if (l.horizontal) ln.x[i] else ln.y[i]
                val cross = if (l.horizontal) ln.y[i] else ln.x[i]
                if (along < ch.t0 || along > ch.t1) continue
                val c = DewarpMath.interp(ch, along)
                val outside = when (sd.index) { 0, 3 -> cross < c - tol; else -> cross > c + tol }
                if (outside) out++
            }
            out >= minPts
        }
    }

    /**
     * Ajuste del campo con los cantos (posición impuesta) y las líneas de la hoja (rectitud), en px de estimación del
     * marco ampliado ([W] x [H]); la hoja nominal es [mx, mx+w) x [my, my+h). [lines] ya desplazadas al marco ampliado.
     * Devuelve el modelo (salida = rectángulo de la hoja, [GridDewarp.Model.beyond]) o null con el motivo en [why].
     */
    fun fit(
        W: Double, H: Double, mx: Double, my: Double, w: Double, h: Double,
        sides: List<Side>, lines0: List<DewarpMath.LineObs>, textRows: Boolean, why: StringBuilder,
        linesOnly: Double = Double.NaN, lineBoost: Double = 1.0,
    ): GridDewarp.Model? {
        val longSide = max(W, H)
        val cells = 30.0
        val nx = max(4, (W / longSide * cells).roundToInt() + 1)
        val ny = max(4, (H / longSide * cells).roundToInt() + 1)
        val hx = W / (nx - 1); val hy = H / (ny - 1)
        val step = 0.45 * min(hx, hy)
        // Lados sin canto: el lado nominal del recorte (como sin este modelo); así el campo no extrapola más allá
        val nominal = (0..3).filter { i -> sides.none { it.index == i } }.map { nominalSide(it, mx, my, w, h) }
        val borders = (sides.map { it.line } + nominal).mapNotNull { DewarpMath.resample(it, step) }
        var lines = lines0.mapNotNull { l ->
            DewarpMath.resample(if (lineBoost == 1.0) l else DewarpMath.LineObs(l.horizontal, l.x, l.y, l.weight * lineBoost, l.target, l.loose, l.noise, l.guide), step)
        }
        val wt = DewarpMath.Weights(tps = if (textRows) GridDewarp.textTps else 3.0, target = TARGET_WEIGHT)
        var field = DewarpMath.solveField(W, H, nx, ny, borders + lines, wt)
        // Descarte de líneas atípicas (los cantos no se descartan: ya se validaron)
        repeat(2) {
            if (lines.isEmpty()) return@repeat
            val res = lines.map { DewarpMath.lineResidual(field, it) }
            val sorted = res.sorted()
            val lim = max(1.5, 3.0 * sorted[sorted.size / 2])
            val bad = BooleanArray(lines.size) { res[it] > lim && res[it] > 0.4 * DewarpMath.straightnessDev(lines[it]) }
            if (bad.none { it }) return@repeat
            lines = lines.filterIndexed { i, _ -> !bad[i] }
            field = DewarpMath.solveField(W, H, nx, ny, borders + lines, wt)
        }
        // Cantos en su sitio
        val o = DoubleArray(6)
        var bErr = 0.0
        for (b in borders) {
            val comp = if (b.horizontal) 1 else 0
            for (i in 0 until b.size) { field.eval(b.x[i].toDouble(), b.y[i].toDouble(), o); bErr = max(bErr, abs(o[comp] - b.target)) }
        }
        val judged = lines.filter { !it.loose }
        fun p90(l: List<Double>) = if (l.isEmpty()) 0.0 else l.sorted()[(l.size * 0.9).toInt().coerceAtMost(l.size - 1)]
        val before = p90(judged.map { DewarpMath.straightnessDev(it) })
        val after = p90(judged.map { DewarpMath.lineResidual(field, it) })
        val info = "cantos=${sides.joinToString("") { "${"sdil"[it.index]}" }} H=${lines.count { it.horizontal }} V=${lines.count { !it.horizontal }} error_canto=%.1f líneas antes=%.2f después=%.2f".format(bErr, before, after)
        why.append(info)
        if (bErr > max(3.0, 0.004 * longSide)) { why.insert(0, "cantos no encajan; "); return null }
        // Las líneas de la hoja no pueden quedar claramente más torcidas que antes (hoja plana) ni, si ya había un
        // modelo de sólo líneas ([linesOnly] = su rectitud), que con él: los cantos no deben ondular el texto
        val ref = if (linesOnly.isNaN()) before else min(before, linesOnly)
        if (judged.isNotEmpty() && after > max(ref * 1.25, ref + 0.4)) {
            // Segundo intento con más peso para las líneas (siguen mandando dentro de la hoja; los cantos, en el borde)
            if (lineBoost == 1.0) {
                why.setLength(0)
                return fit(W, H, mx, my, w, h, sides, lines0, textRows, why, linesOnly, LINE_BOOST)
            }
            why.insert(0, "líneas peor (ref. %.2f); ".format(ref)); return null
        }
        // Jacobiano dentro de la hoja
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        val gx = max(8, (w / 20).toInt()); val gy = max(8, (h / 20).toInt())
        for (j in 0..gy) for (i in 0..gx) {
            field.eval(mx + w * i / gx, my + h * j / gy, o)
            val det = o[2] * o[5] - o[3] * o[4]
            for (v in doubleArrayOf(o[2], o[5], det)) { lo = min(lo, v); hi = max(hi, v) }
        }
        if (lo < 0.5 || hi > 2.0) { why.insert(0, "jacobiano %.2f..%.2f; ".format(lo, hi)); return null }
        val u0 = mx; val v0 = my; val u1 = mx + w - 1; val v1 = my + h - 1
        val gw = max(2, ceil((u1 - u0) / 8.0).toInt() + 1)
        val gh = max(2, ceil((v1 - v0) / 8.0).toInt() + 1)
        val inv = DewarpMath.invert(field, gw, gh, u0, v0, u1, v1) ?: run { why.insert(0, "inversión; "); return null }
        val map = FloatArray(gw * gh * 2)
        for (i in 0 until gw * gh) {
            map[2 * i] = ((inv[2 * i] - mx) / (w - 1)).toFloat()
            map[2 * i + 1] = ((inv[2 * i + 1] - my) / (h - 1)).toFloat()
        }
        val conf = min(1.0, sides.size / 4.0)
        return GridDewarp.Model(gw, gh, map, conf, info, beyond = true, sheetSides = sides.fold(0) { acc, sd -> acc or (1 shl sd.index) })
    }

    private const val TARGET_WEIGHT = 0.08

    /** Peso extra de las líneas de la hoja en el segundo intento del ajuste con cantos. */
    private const val LINE_BOOST = 4.0

    /** Lado [index] nominal del recorte (recto, en su sitio) como línea con posición impuesta. */
    fun nominalSide(index: Int, mx: Double, my: Double, w: Double, h: Double): DewarpMath.LineObs {
        val n = 40
        val horiz = index == 0 || index == 2
        // 1 px hacia dentro, como los cantos detectados: la interpolación en el lado del recorte trae algo del fondo
        val c = when (index) { 0 -> my + 1; 2 -> my + h - 2; 3 -> mx + 1; else -> mx + w - 2 }
        val a0 = if (horiz) mx else my
        val len = if (horiz) w - 1 else h - 1
        val along = FloatArray(n) { (a0 + len * it / (n - 1)).toFloat() }
        val cross = FloatArray(n) { c.toFloat() }
        val tg = when (index) { 0 -> my; 2 -> my + h - 1; 3 -> mx; else -> mx + w - 1 }
        return if (horiz) DewarpMath.LineObs(true, along, cross, weight = BORDER_WEIGHT, target = tg)
        else DewarpMath.LineObs(false, cross, along, weight = BORDER_WEIGHT, target = tg)
    }

    /** Desviación característica de los cantos (px): máx. de la curvatura y del desplazamiento respecto del recorte. */
    fun significance(sides: List<Side>): Double = sides.maxOfOrNull { max(it.dev, it.offset) } ?: 0.0

    /** Plano rectificado ampliado: tamaño del margen (px) para un lado de [n] px. */
    fun marginFor(n: Int): Int = max(8, (n * MARGIN).roundToInt())

}

/**
 * Lógica pura de la detección de cantos (sin OpenCV; probada en JVM): puntuación de canto en perfiles transversales y
 * camino óptimo por programación dinámica.
 */
internal object DwPath {

    /**
     * Puntuación de canto en cada posición j de cada perfil t (matrices nT x nS en fila mayor, j creciente = hacia dentro
     * de la hoja): contraste Lab entre las ventanas exterior [j-win, j) e interior (j, j+win] por lo "papel" del interior
     * (luminosidad ≥ ~0.5 de la del papel y croma cercana: [pl], [pa], [pb]) y una ligera preferencia por lo exterior.
     * Posiciones con algún píxel no válido en las ventanas: 0.
     */
    fun edgeScores(L: FloatArray, A: FloatArray, B: FloatArray, ok: BooleanArray, nT: Int, nS: Int, pl: Double, pa: Double, pb: Double, win: Int): FloatArray {
        val out = FloatArray(nT * nS)
        val cl = DoubleArray(nS + 1); val ca = DoubleArray(nS + 1); val cb = DoubleArray(nS + 1); val cbad = IntArray(nS + 1)
        val outPaper = FloatArray(nS)
        for (t in 0 until nT) {
            val r = t * nS
            // Papel MÁS ALLÁ de cada posición (hacia fuera): el borde inferior de una banda impresa junto al canto (cabecera
            // de color, foto) tiene papel al otro lado de la banda; el canto real tiene fuera sólo fondo
            var run = 0f
            for (j in 0 until nS) {
                val q = r + j
                val p = if (ok[q]) paperLike(L[q].toDouble(), A[q].toDouble(), B[q].toDouble(), pl, pa, pb).toFloat() else 0f
                // máximo de 3 píxeles consecutivos (una mota clara aislada no cuenta)
                val m3 = if (j >= 2 && ok[q - 1] && ok[q - 2]) minOf(p, paperLike(L[q - 1].toDouble(), A[q - 1].toDouble(), B[q - 1].toDouble(), pl, pa, pb).toFloat(),
                    paperLike(L[q - 2].toDouble(), A[q - 2].toDouble(), B[q - 2].toDouble(), pl, pa, pb).toFloat()) else 0f
                run = max(run, m3)
                outPaper[j] = run
            }
            for (j in 0 until nS) {
                val q = r + j
                cl[j + 1] = cl[j] + L[q]; ca[j + 1] = ca[j] + A[q]; cb[j + 1] = cb[j] + B[q]
                cbad[j + 1] = cbad[j] + if (ok[q]) 0 else 1
            }
            for (j in win until nS - win) {
                // exterior [j-win, j-1], interior [j+1, j+win]
                if (cbad[j + win + 1] - cbad[j - win] > 0) continue
                val oL = (cl[j] - cl[j - win]) / win; val oA = (ca[j] - ca[j - win]) / win; val oB = (cb[j] - cb[j - win]) / win
                val iL = (cl[j + win + 1] - cl[j + 1]) / win; val iA = (ca[j + win + 1] - ca[j + 1]) / win; val iB = (cb[j + win + 1] - cb[j + 1]) / win
                val inner = paperLike(iL, iA, iB, pl, pa, pb)
                val dL = iL - oL; val dA = iA - oA; val dB = iB - oB
                val contrast = sqrt(dL * dL + 4 * dA * dA + 4 * dB * dB).coerceAtMost(80.0)
                val outward = 1.0 + 0.25 * (nS - 1 - j).toDouble() / (nS - 1)
                val beyond = if (j - win - 1 >= 0) outPaper[j - win - 1] else 0f
                out[r + j] = (inner * contrast * outward * (1.0 - 0.85 * beyond)).toFloat()
            }
        }
        return out
    }

    /** Cuánto se parece un color Lab al papel ([pl], [pa], [pb]): luminosidad ≥ ~0.5 de la del papel y croma cercana. */
    fun paperLike(l: Double, a: Double, b: Double, pl: Double, pa: Double, pb: Double): Double {
        val plL = ((l / max(1.0, pl) - 0.45) / 0.3).coerceIn(0.0, 1.0)
        val plC = (1.0 - (hypot(a - pa, b - pb) - 6.0) / 18.0).coerceIn(0.0, 1.0)
        return plL * plC
    }

    /**
     * Camino j(t) que maximiza Σ score(t, j(t)) − [penalty]·|j(t) − j(t−1)| con |j(t) − j(t−1)| ≤ [maxStep]
     * (programación dinámica, O(nT·nS·maxStep)).
     */
    fun bestPath(score: FloatArray, nT: Int, nS: Int, maxStep: Int, penalty: Float): IntArray {
        val acc = FloatArray(nS); val nxt = FloatArray(nS)
        val from = Array(nT) { IntArray(nS) }
        for (j in 0 until nS) acc[j] = score[j]
        for (t in 1 until nT) {
            val r = t * nS
            for (j in 0 until nS) {
                var best = Float.NEGATIVE_INFINITY; var bj = j
                for (d in -maxStep..maxStep) {
                    val k = j + d
                    if (k < 0 || k >= nS) continue
                    val v = acc[k] - penalty * abs(d)
                    if (v > best) { best = v; bj = k }
                }
                nxt[j] = best + score[r + j]; from[t][j] = bj
            }
            System.arraycopy(nxt, 0, acc, 0, nS)
        }
        var bj = 0
        for (j in 1 until nS) if (acc[j] > acc[bj]) bj = j
        val path = IntArray(nT)
        path[nT - 1] = bj
        for (t in nT - 1 downTo 1) path[t - 1] = from[t][path[t]]
        return path
    }
}
