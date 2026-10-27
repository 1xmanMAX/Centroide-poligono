package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Líneas de TABLA reconstruidas ("Color natural" y "Blanco y negro" de páginas impresas, con
 * [PageEdits.keepRuling]): las rayas finas o grises de una tabla fotografiada salían punteadas, rotas o casi
 * invisibles tras binarizar. Aquí se detectan en la página rectificada y cada línea real se vuelve a dibujar LIMPIA,
 * continua y en su sitio exacto sobre la salida:
 *
 *  1. Candidatas a <= [ANALYSIS_SIDE] px: canal mínimo normalizado por el fondo (la línea azul también cuenta),
 *     black-hat con núcleo transversal (sólo estructuras finas: las líneas y los trazos horizontales de las letras),
 *     apertura con un segmento de ~1 letra (fuera el texto), cierre a lo largo (une los trazos de una línea rota) y
 *     componentes largas y finas.
 *  2. Seguimiento a resolución completa ([TableLinesMath.track]): en cada posición, pico de oscuridad respecto del
 *     papel local con centro subpíxel y anchura; ajuste robusto (recta o parábola) + corrección local suave (hoja algo
 *     curvada), prolongación de los extremos mientras haya tinta alineada; estadística de continuidad, rayas
 *     discontinuas impresas (regulares y limpias -> no se tocan) y líneas dentro de fotos o rellenos.
 *  3. Decisión conservadora ([TableLinesMath.accept]): sólo líneas largas y rectas que forman una tabla (varias
 *     paralelas solapadas o cruzadas por perpendiculares) o reglas largas; nada dentro de fotos.
 *  4. Dibujo ([TableLinesMath.drawBand]) con el grosor medido y el color medido EN LA SALIDA a lo largo de la propia
 *     línea (gris oscuro/negro en B/N; en color, su color real: una línea azul sigue azul), anti-aliasing, sólo
 *     oscureciendo (el texto que la cruza queda encima).
 */
internal object TableLines {

    /** Desactivable en el banco de pruebas. */
    @Volatile var ENABLED = true

    internal var log: ((String) -> Unit)? = null

    /** Banco de pruebas: segmentos dibujados (hor, x0, y0, x1, y1) en px de la salida. */
    internal var onDraw: ((List<DoubleArray>) -> Unit)? = null

    /** Banco de pruebas: máscaras intermedias. */
    internal var debug: ((String, Mat) -> Unit)? = null

    private const val ANALYSIS_SIDE = 1600

    /**
     * Reconstruye in situ las líneas de tabla de [out] (8UC1 o 8UC3, tamaño = [src] x k) a partir de [src] (RGB 8UC3,
     * la página que se renderizó). [letterHeight] en px de [src] (0 = desconocida). [avoid] = recuadros relativos
     * (x0, y0, x1, y1 en 0..1) de fotos/figuras donde no se dibuja. Devuelve el número de líneas dibujadas.
     */
    fun restore(out: Mat, src: Mat, letterHeight: Double, avoid: List<DoubleArray> = emptyList()): Int = MatBag().use { bag ->
        if (!ENABLED || src.empty() || out.empty() || src.channels() < 3) return@use 0
        val w = src.cols(); val h = src.rows()
        if (min(w, h) < 200) return@use 0
        val side = max(w, h)
        val lh = if (letterHeight > 0) letterHeight else side / 250.0
        // canal mínimo a resolución completa (la línea de color también oscurece)
        val t0 = System.nanoTime()
        val ch = ArrayList<Mat>(3); Core.split(src, ch); for (c in ch) bag.add(c)
        val vFull = bag.mat(); Core.min(ch[0], ch[1], vFull); Core.min(vFull, ch[2], vFull)
        // análisis
        val vs = bag.mat(); val s = Cv.downscale(vFull, vs, ANALYSIS_SIDE)
        val bgS = bag.add(Cv.estimateBackground(vs, 256))
        val nv = bag.mat(); Cv.divideByBackground(vs, bgS, nv)
        val hist = Cv.histogram(nv)
        val (noise, pm) = Cv.paperStats(hist)
        // contraste de la tinta de la página (texto): papel - percentil 0.5 %
        val inkRef = max(40.0, pm - Cv.percentile(hist, 0.005))
        // grosor máximo de una línea de tabla: fina respecto de la letra (el núcleo transversal del black-hat debe ser
        // menor que la altura de letra: si no, las letras enteras responden como "estructura fina")
        val maxThick = max(3.0, min(0.0035 * side, 0.3 * lh))
        val lhA = lh * s
        val kt = Cv.odd(max((0.4 * lhA).roundToInt().coerceIn(5, 21), (2 * maxThick * s + 1).roundToInt()))
        val lo = Cv.odd(max(9, (1.2 * lhA).roundToInt()))
        val gap = Cv.odd(max(9, (2.0 * lhA).roundToInt()))
        val thr = max(8.0, 3.0 * noise)
        val cands = ArrayList<TableLinesMath.Cand>()
        val nvB = ByteArray(nv.total().toInt()); nv.get(0, 0, nvB)
        val prm = TableLinesMath.Params(
            search = ceil(2.0 / s).toInt() + 2,
            paperExtra = max(4, (0.6 * lh).roundToInt()),
            noiseAbs = max(6.0, 3.0 * noise * 0.8),
            maxThick = maxThick,
            gapMax = max((2.5 * lh).roundToInt(), (0.02 * side).roundToInt()),
        )
        val avoidRects = avoid.map { r -> doubleArrayOf(r[0] * w, r[1] * h, r[2] * w, r[3] * h) }
        val t1 = System.nanoTime()
        var tTrack = 0L; var tMorph = 0L
        for (hor in booleanArrayOf(true, false)) {
            val tm0 = System.nanoTime()
            val bh = bag.mat()
            Imgproc.morphologyEx(nv, bh, Imgproc.MORPH_BLACKHAT, if (hor) Cv.kernel(Imgproc.MORPH_RECT, 1, kt) else Cv.kernel(Imgproc.MORPH_RECT, kt, 1))
            val m = bag.mat(); Imgproc.threshold(bh, m, thr, 255.0, Imgproc.THRESH_BINARY)
            // puntos/trazos de una línea punteada o rota: cierre corto a lo largo antes de la apertura
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, if (hor) Cv.kernel(Imgproc.MORPH_RECT, 5, 1) else Cv.kernel(Imgproc.MORPH_RECT, 1, 5))
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, if (hor) Cv.kernel(Imgproc.MORPH_RECT, lo, 1) else Cv.kernel(Imgproc.MORPH_RECT, 1, lo))
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, if (hor) Cv.kernel(Imgproc.MORPH_RECT, gap, 1) else Cv.kernel(Imgproc.MORPH_RECT, 1, gap))
            debug?.invoke(if (hor) "mH" else "mV", m); debug?.invoke(if (hor) "bhH" else "bhV", bh)
            val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
            tMorph += System.nanoTime() - tm0
            val nc = Imgproc.connectedComponentsWithStats(m, labels, stats, cents, 8, CvType.CV_32S)
            val st = IntArray(max(0, nc) * 5); if (nc > 0) stats.get(0, 0, st)
            val aw = nv.cols(); val ah = nv.rows()
            val minLenA = if (hor) max(4.0 * lhA, 0.05 * aw) else max(2.5 * lhA, 0.025 * ah)
            // banda de la imagen completa para el seguimiento (filas para horizontales, columnas para verticales)
            val nAlong = if (hor) w else h; val nAcrossAll = if (hor) h else w
            for (c in 1 until nc) {
                val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bhh = st[c * 5 + 3]; val area = st[c * 5 + 4]
                val ext = if (hor) bw else bhh; val thin = if (hor) bhh else bw
                if (ext < minLenA || area.toDouble() / ext > maxThick * s + 2 || thin > ext * 0.08 + maxThick * s * 2 + 4) continue
                // semilla: recta por los centros de la componente (media por columna)
                val seed = seedLine(labels, c, x, y, bw, bhh, hor, s) ?: continue
                val a0 = floor(((if (hor) x else y) + 0.5) / s - 0.5).toInt(); val a1 = ceil(((if (hor) x + bw else y + bhh) - 0.5) / s - 0.5).toInt()
                // banda transversal que cubre la semilla + búsqueda + papel
                val ys = doubleArrayOf(seed.at(a0.toDouble()), seed.at(a1.toDouble()), seed.at((a0 + a1) / 2.0))
                val pad = prm.search + prm.paperExtra + 3 + (0.03 * (a1 - a0)).roundToInt() + 8
                val c0 = max(0, floor(ys.min()).toInt() - pad); val c1 = min(nAcrossAll, ceil(ys.max()).toInt() + pad + 1)
                if (c1 - c0 < 8) continue
                val band = bag.add(if (hor) vFull.submat(c0, c1, 0, w).clone() else vFull.submat(0, h, c0, c1).clone())
                val bb = ByteArray((band.total()).toInt()); band.get(0, 0, bb); band.release()
                val across = c1 - c0
                val at = if (hor) TableLinesMath.Band { i, j -> bb[j * w + i].toInt() and 0xFF } else TableLinesMath.Band { i, j -> bb[i * across + j].toInt() and 0xFF }
                val seedB = TableLinesMath.Poly(seed.c0 - c0, seed.c1, seed.c2, seed.mid, seed.scale)
                for (tr in TableLinesMath.trackAll(at, across, nAlong, seedB, a0, a1, prm)) {
                // de la banda a coordenadas de la página
                val centers = DoubleArray(tr.centers.size) { tr.centers[it] + c0 }
                // objetos oscuros que CRUZAN la línea (barras de un gráfico, columnas sombreadas): oscuro a ambos lados
                // en la imagen normalizada, a ~0.3 letras del centro
                val dA = max(2.0, 0.3 * lhA + tr.thick * s)
                fun crossing(k: Double) = BooleanArray(centers.size) { q ->
                    val ia = ((tr.a0 + q + 0.5) * s - 0.5).roundToInt(); val ca = (centers[q] + 0.5) * s - 0.5
                    fun dark(off: Double): Boolean {
                        val j = (ca + off).roundToInt()
                        val x = if (hor) ia else j; val y = if (hor) j else ia
                        if (x < 0 || y < 0 || x >= aw || y >= ah) return false
                        return (nvB[y * aw + x].toInt() and 0xFF) < pm - k * inkRef
                    }
                    dark(-dA) && dark(dA)
                }
                // oscuro (no se pinta encima) / algo oscuro (barras claras: cuentan para reconocer un gráfico)
                val crossed = crossing(0.5)
                val (cf, cr) = TableLinesMath.crossedRuns(crossing(0.3), max(3, (0.4 * lh).roundToInt()))
                val nd = (tr.noDraw ?: BooleanArray(centers.size)).copyOf()
                val dil = ceil(tr.thick).toInt() + 2
                for (q in crossed.indices) if (crossed[q]) for (z in max(0, q - dil)..min(nd.size - 1, q + dil)) nd[z] = true
                val t2 = TableLinesMath.Track(tr.a0, tr.a1, centers, tr.thick, tr.amp, tr.inkFrac, tr.maxGap, tr.dashed, tr.rms, tr.embedded, tr.touching, tr.maxRun, tr.irregular, nd, cf, cr)
                cands.add(TableLinesMath.Cand(hor, tr.a0.toDouble(), tr.a1.toDouble(), t2.mean, t2))
                }
            }
        }
        val t2 = System.nanoTime()
        log?.invoke("tiempos: prep=%.0f morf=%.0f seguimiento=%.0f ms".format((t1 - t0) / 1e6, tMorph / 1e6, (t2 - t1 - tMorph) / 1e6))
        if (cands.isEmpty()) return@use 0
        // duplicados (dos semillas de la misma línea): la más larga
        val uniq = dedupe(cands)
        // fuera de fotos / figuras
        val kept = uniq.filter { l -> avoidRects.none { r -> insideFrac(l, r) > 0.3 } }
        // lo que ya muestra la salida y su color
        val k = out.cols().toDouble() / w
        val colors = HashMap<TableLinesMath.Cand, IntArray?>()
        for (l in kept) {
            val (cov, col) = withOutBand(out, l, k, write = false) { buf, bw, bh, chn, u0, u1, ctr, _ ->
                TableLinesMath.sampleBand(buf, bw, bh, chn, l.hor, u0, u1, ctr, max(1.5, l.tr.thick * k / 2 + 1.5 * k))
            } ?: (0.0 to null)
            l.outCov = cov; colors[l] = col
        }
        val ok = TableLinesMath.accept(kept, w, h, lh, maxThick, inkRef)
        val drawn = ArrayList<DoubleArray>()
        val chn = out.channels()
        for ((idx, l) in kept.withIndex()) {
            log?.invoke("cand ${if (l.hor) "H" else "V"} a=%.0f..%.0f c=%.1f len=%.0f thick=%.2f amp=%.0f ink=%.2f gap=${l.tr.maxGap} dash=${l.tr.dashed} rms=%.2f emb=%.2f touch=%.2f run=${l.tr.maxRun} irr=${l.tr.irregular} blk=%.2f/${l.tr.blockedRuns} out=%.2f -> ${ok[idx]}".format(l.a0, l.a1, l.c, l.len, l.tr.thick, l.tr.amp, l.tr.inkFrac, l.tr.rms, l.tr.embedded, l.tr.touching, l.tr.blockedFrac, l.outCov))
            if (!ok[idx]) continue
            val color = lineColor(colors[l] ?: (if (chn >= 3) sourceColor(src, l, lh) else null), chn)
            val tOut = max(1.0, l.tr.thick * k).coerceAtMost(maxThick * k)
            withOutBand(out, l, k, write = true) { buf, bw, bh, cc, u0, u1, ctr, i0 ->
                TableLinesMath.drawBand(buf, bw, bh, cc, l.hor, u0, u1, ctr, tOut, color) { u -> !l.tr.drawable((u + i0 + 0.5) / k - 0.5) }
            }
            val ca = (l.tr.centerAt(l.a0) + 0.5) * k - 0.5; val cb = (l.tr.centerAt(l.a1) + 0.5) * k - 0.5
            val pa = (l.a0 + 0.5) * k - 0.5; val pb = (l.a1 + 0.5) * k - 0.5
            drawn.add(if (l.hor) doubleArrayOf(1.0, pa, ca, pb, cb) else doubleArrayOf(0.0, ca, pa, cb, pb))
        }
        log?.invoke("fin %.0f ms".format((System.nanoTime() - t2) / 1e6))
        log?.invoke("tabla: candidatas=${cands.size} únicas=${kept.size} dibujadas=${drawn.size} kt=$kt lo=$lo gap=$gap thr=%.1f noise=%.1f lh=%.1f inkRef=%.0f".format(thr, noise, lh, inkRef))
        onDraw?.invoke(drawn)
        drawn.size
    }

    /** Color de la línea: el medido en la salida (núcleo), oscurecido si queda muy claro; por defecto gris oscuro. */
    /**
     * Color real de la línea en la foto (para una línea que la salida en color no muestra): mediana, a lo largo de la
     * línea, de píxel / papel vecino por canal (el papel, a media letra a cada lado: el más claro de los dos).
     */
    private fun sourceColor(src: Mat, l: TableLinesMath.Cand, lh: Double): IntArray? {
        val w = src.cols(); val h = src.rows()
        val off = l.tr.thick / 2 + 0.5 * lh
        val rs = ArrayList<DoubleArray>()
        val px = ByteArray(3); val pa = ByteArray(3); val pb = ByteArray(3)
        var i = l.tr.a0
        while (i <= l.tr.a1) {
            val c = l.tr.centerAt(i.toDouble())
            fun at(acr: Double, buf: ByteArray): Boolean {
                val j = acr.roundToInt(); val x = if (l.hor) i else j; val y = if (l.hor) j else i
                if (x < 0 || y < 0 || x >= w || y >= h) return false
                src.get(y, x, buf); return true
            }
            if (l.tr.drawable(i.toDouble()) && at(c, px) && at(c - off, pa) && at(c + off, pb)) {
                fun lum(b: ByteArray) = (b[0].toInt() and 0xFF) + (b[1].toInt() and 0xFF) + (b[2].toInt() and 0xFF)
                val p = if (lum(pa) >= lum(pb)) pa else pb
                rs.add(DoubleArray(3) { q -> (px[q].toInt() and 0xFF) / max(1.0, (p[q].toInt() and 0xFF).toDouble()) })
            }
            i += 4
        }
        if (rs.size < 8) return null
        return IntArray(3) { q -> val v = rs.map { it[q] }.sorted(); (255 * v[v.size / 2]).roundToInt().coerceIn(0, 255) }
    }

    private fun lineColor(measured: IntArray?, ch: Int): IntArray {
        val c = measured ?: IntArray(ch) { 70 }
        val lum = if (ch >= 3) (c[0] * 299 + c[1] * 587 + c[2] * 114) / 1000.0 else c[0].toDouble()
        val maxLum = if (ch >= 3) 130.0 else 90.0
        if (lum <= maxLum || lum <= 0) return c
        val f = maxLum / lum
        return IntArray(ch) { (c[it] * f).roundToInt().coerceIn(0, 255) }
    }

    /**
     * Banda de [out] alrededor de la línea [l] (k = escala salida/fuente): llama a [f] con los bytes de la banda, su
     * tamaño, canales, extremos a lo largo (px de banda) y el centro (px de banda) en función de la posición a lo
     * largo. Si [write], escribe la banda de vuelta.
     */
    private fun <T> withOutBand(out: Mat, l: TableLinesMath.Cand, k: Double, write: Boolean, f: (ByteArray, Int, Int, Int, Double, Double, (Double) -> Double, Int) -> T): T? {
        val ow = out.cols(); val oh = out.rows()
        val u0 = (l.a0 + 0.5) * k - 0.5; val u1 = (l.a1 + 0.5) * k - 0.5
        var cmin = Double.MAX_VALUE; var cmax = -Double.MAX_VALUE
        for (v in l.tr.centers) { cmin = min(cmin, v); cmax = max(cmax, v) }
        val pad = (l.tr.thick * k).roundToInt() + (3 * k).roundToInt() + 3
        val acrossN = if (l.hor) oh else ow; val alongN = if (l.hor) ow else oh
        val j0 = max(0, floor((cmin + 0.5) * k - 0.5).toInt() - pad); val j1 = min(acrossN, ceil((cmax + 0.5) * k - 0.5).toInt() + pad + 1)
        val i0 = max(0, floor(u0).toInt() - 2); val i1 = min(alongN, ceil(u1).toInt() + 3)
        if (j1 <= j0 || i1 <= i0) return null
        val r = if (l.hor) Rect(i0, j0, i1 - i0, j1 - j0) else Rect(j0, i0, j1 - j0, i1 - i0)
        val sub = out.submat(r)
        try {
            val chn = out.channels()
            val buf = ByteArray(r.width * r.height * chn)
            sub.get(0, 0, buf)
            val ctr: (Double) -> Double = { u -> (l.tr.centerAt((u + i0 + 0.5) / k - 0.5) + 0.5) * k - 0.5 - j0 }
            val res = f(buf, r.width, r.height, chn, u0 - i0, u1 - i0, ctr, i0)
            if (write) sub.put(0, 0, buf)
            return res
        } finally { sub.release() }
    }

    /** Recta semilla (coordenadas de la imagen completa) por los centros de la componente [c] (mínimos cuadrados). */
    private fun seedLine(labels: Mat, c: Int, x: Int, y: Int, bw: Int, bh: Int, hor: Boolean, s: Double): TableLinesMath.Poly? {
        val roi = labels.submat(y, y + bh, x, x + bw)
        val lab = IntArray(bw * bh); val tmp = Mat(); roi.copyTo(tmp); tmp.get(0, 0, lab); tmp.release(); roi.release()
        val nA = if (hor) bw else bh
        val xs = DoubleArray(nA); val ys = DoubleArray(nA); val ws = DoubleArray(nA); var m = 0
        for (a in 0 until nA) {
            var sum = 0.0; var cnt = 0
            val nC = if (hor) bh else bw
            for (q in 0 until nC) {
                val v = if (hor) lab[q * bw + a] else lab[a * bw + q]
                if (v == c) { sum += q; cnt++ }
            }
            if (cnt > 0) {
                val along = (if (hor) x else y) + a; val acr = (if (hor) y else x) + sum / cnt
                xs[m] = (along + 0.5) / s - 0.5; ys[m] = (acr + 0.5) / s - 0.5; ws[m] = 1.0; m++
            }
        }
        if (m < 4) return null
        val mid = (xs[0] + xs[m - 1]) / 2; val sc = max(1.0, (xs[m - 1] - xs[0]) / 2)
        return TableLinesMath.fit(xs, ys, ws, m, 1, mid, sc)
    }

    private fun dedupe(c: List<TableLinesMath.Cand>): List<TableLinesMath.Cand> {
        val sorted = c.sortedByDescending { it.len }
        val out = ArrayList<TableLinesMath.Cand>()
        for (l in sorted) {
            val dup = out.any { o ->
                o.hor == l.hor && min(o.a1, l.a1) - max(o.a0, l.a0) >= 0.5 * l.len &&
                    abs(o.tr.centerAt((max(o.a0, l.a0) + min(o.a1, l.a1)) / 2) - l.tr.centerAt((max(o.a0, l.a0) + min(o.a1, l.a1)) / 2)) <= max(2.0, max(o.tr.thick, l.tr.thick) * 1.5)
            }
            if (!dup) out.add(l)
        }
        return out
    }

    /** Fracción de la línea dentro del recuadro [r] (x0, y0, x1, y1 en px). */
    private fun insideFrac(l: TableLinesMath.Cand, r: DoubleArray): Double {
        val (p0, p1, q0, q1) = if (l.hor) listOf(r[0], r[2], r[1], r[3]) else listOf(r[1], r[3], r[0], r[2])
        if (l.c < q0 || l.c > q1) return 0.0
        return max(0.0, min(l.a1, p1) - max(l.a0, p0)) / max(1.0, l.len)
    }
}
