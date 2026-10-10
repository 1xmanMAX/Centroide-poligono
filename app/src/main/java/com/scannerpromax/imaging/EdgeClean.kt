package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Limpieza de los BORDES del resultado de "Texto resaltado" y "Blanco y negro" (manuscrito e impreso): lo que queda
 * junto al borde de la imagen y no es texto ni contenido (sombra del canto, mesa o fondo dentro del recorte, restos de
 * la espiral, manchas, líneas del borde de la hoja o de una cuña) se pinta de blanco puro.
 *
 * Sobre la salida ya renderizada (fondo blanco), a <= [WORK] px: componentes de tinta (histéresis) y, para cada una
 * pegada al borde, su forma ([EdgeComp]): una FRANJA a lo largo del borde (lo recorre pegada a él en la mayor parte de
 * su longitud, mucho más larga que una letra y estrecha), una MANCHA maciza (gruesa respecto del trazo típico de la
 * página en buena parte de su área: sombra, mesa, anilla) o una CUÑA de esquina se borran. Una letra cortada por el
 * borde ("I" de "Informe") es corta y de trazo fino: se conserva. Además, motas pequeñas y aisladas dentro de la banda
 * del borde (lejos de cualquier letra) también se borran. La decisión es lógica pura ([decide]).
 */
internal object EdgeClean {
    internal var log: ((String) -> Unit)? = null

    /** Lado largo de la resolución de análisis (y píxeles como máximo: una tira muy alargada no se reduce de más). */
    private const val WORK = 1600
    private const val WORK_PIXELS = 1_600_000.0

    /** Rasgos de una componente pegada al borde, en px de trabajo. */
    data class EdgeComp(
        val along: Int,          // extensión a lo largo del lado tocado
        val across: Int,         // extensión perpendicular al lado
        val hug: Double,         // fracción de su longitud pegada al lado (a <= margen)
        val thickFrac: Double,   // fracción del área con distancia al borde del trazo >= radio grueso
        val area: Int,
        val fill: Double,        // área / caja
        val corner: Boolean,     // toca dos lados contiguos
        val acrossDim: Int,      // dimensión de la imagen perpendicular al lado
        val alongDim: Int,
        val hugRuns: Int = 1,    // tramos pegados al lado (una franja: pocos; una línea de letras: uno por letra)
        val sheetEdge: Boolean = false,   // el lado es el canto de la hoja (enderezado por los cantos): no hay mesa
    )

    /** Escala de la página: altura de letra y semiancho del trazo típicos (px de trabajo). */
    data class PageScale(val letter: Double, val halfStroke: Double, val side: Int, val total: Long)

    /** ¿Es un resto del borde (no contenido)? */
    fun decide(c: EdgeComp, s: PageScale): Boolean {
        // Franja paralela al borde: recorre el lado pegada a él, mucho más larga que una letra y estrecha
        val band = c.along >= max(2.5 * s.letter, 0.03 * s.side) && c.hug >= 0.5 &&
            c.across <= max(2.0 * s.letter, 0.05 * c.acrossDim) && c.along >= 2.5 * c.across &&
            c.hugRuns <= max(3.0, c.along / (4.0 * s.letter))
        if (band) return true
        // En un canto de la hoja (la salida termina justo en ella) lo pegado al lado es contenido: cabecera de color,
        // foto o titular a sangre; sólo la franja fina (sombra del canto) es un resto
        if (c.sheetEdge) return false
        // Mancha maciza (sombra, mesa, anilla, hueco oscuro): gruesa en buena parte de su área; nunca una foto enorme
        // (pequeña -esquina, astilla, anilla- o alargada a lo largo del borde -sombra del canto-; una zona grande y
        // compacta pegada al borde puede ser la foto de una tarjeta y no se toca)
        // (maciza en al menos la mitad de su área: una mancha del papel con letras pegadas no lo es y se conserva)
        val mass = c.thickFrac >= 0.5 && c.hug >= 0.3 &&
            (c.area <= 0.04 * s.total || (c.area <= 0.25 * s.total && c.hug >= 0.6 && c.along >= 2 * c.across))
        if (mass) return true
        // Cuña de esquina (fondo entre la esquina redondeada de una tarjeta o la hoja girada y el recorte)
        val wedge = c.corner && c.fill >= 0.35 && c.thickFrac >= 0.2 && c.along <= 0.2 * c.alongDim && c.across <= 0.2 * c.acrossDim
        return wedge
    }

    /**
     * Limpia [out] (8UC1 u 8UC3, fondo blanco) en el sitio. Devuelve el número de componentes borradas.
     * [src]: imagen de entrada del filtro (RGB, mismo encuadre aunque otro tamaño) para reconocer el fondo ajeno a la
     * hoja; [letterHint]: altura de letra conocida (px de [out]) o 0. [sheetSides]: lados que son cantos de la hoja
     * (bits 0 superior, 1 derecho, 2 inferior, 3 izquierdo; [ImageEnhancer.Options.sheetSides]).
     */
    fun clean(out: Mat, src: Mat? = null, letterHint: Double = 0.0, inside: Mat? = null, sheetSides: Int = 0): Int = MatBag().use { bag ->
        val fw = out.cols(); val fh = out.rows()
        if (min(fw, fh) < 64) return@use 0
        // Oscuridad (luminancia: la tinta de color es oscura; los fondos pastel de una tarjeta o un sombreado, no, y no
        // deben unir el contenido en una sola componente)
        val d = bag.mat()
        if (out.channels() >= 3) Imgproc.cvtColor(out, d, if (out.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
        else out.copyTo(d)
        Core.bitwise_not(d, d)
        // Máscaras débil / fuerte a resolución completa y reducción por "cualquier píxel" (las líneas finas no se pierden)
        val k = min(1.0, max(WORK.toDouble() / max(fw, fh), kotlin.math.sqrt(WORK_PIXELS / (fw.toDouble() * fh))))
        val ws = Size(max(1.0, (fw * k).roundToInt().toDouble()), max(1.0, (fh * k).roundToInt().toDouble()))
        fun pooled(thr: Double): Mat {
            val m = bag.mat(); Imgproc.threshold(d, m, thr, 255.0, Imgproc.THRESH_BINARY)
            val r = bag.mat(); Imgproc.resize(m, r, ws, 0.0, 0.0, Imgproc.INTER_AREA); m.release()
            // (cobertura >= 40 %: las letras vecinas no se funden al reducir; una raya fina de 1-2 px sigue entera)
            Imgproc.threshold(r, r, 100.0, 255.0, Imgproc.THRESH_BINARY)
            return r
        }
        val weak = pooled(80.0)
        val strong = pooled(130.0)
        // Cerca de la tinta (también su halo tenue): se excluye al medir si una zona es papel o fondo ajeno
        val near = pooled(20.0)
        Imgproc.dilate(near, near, Cv.kernel(Imgproc.MORPH_RECT, 5))
        d.release()
        val W = weak.cols(); val H = weak.rows()
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(weak, labels, stats, cents, 8, CvType.CV_32S)
        if (nc <= 1) return@use 0
        val st = IntArray(nc * 5); stats.get(0, 0, st)
        val lab = IntArray(W * H); labels.get(0, 0, lab)
        val sb = ByteArray(W * H); strong.get(0, 0, sb)
        val dtm = bag.mat(); Imgproc.distanceTransform(weak, dtm, Imgproc.DIST_L2, 3)
        val dt = FloatArray(W * H); dtm.get(0, 0, dt)
        val nearB = ByteArray(W * H); near.get(0, 0, nearB); near.release()
        val hasStrong = BooleanArray(nc); val maxDt = FloatArray(nc)
        for (i in lab.indices) { val l = lab[i]; if (l == 0) continue; if (sb[i].toInt() != 0) hasStrong[l] = true; if (dt[i] > maxDt[l]) maxDt[l] = dt[i] }
        val side = max(W, H)
        fun dbg(t: String, l: Int) { if (log != null && st[l * 5 + 4] >= 200) log?.invoke("  quita $t ${st[l * 5]},${st[l * 5 + 1]} ${st[l * 5 + 2]}x${st[l * 5 + 3]} a=${st[l * 5 + 4]}") }
        val m = max(2, (0.015 * side).roundToInt())
        fun touches(l: Int): Boolean {
            val o = l * 5
            return st[o] <= m || st[o + 1] <= m || st[o] + st[o + 2] >= W - m || st[o + 1] + st[o + 3] >= H - m
        }
        /** ¿Toca el borde sólo por lados que son cantos de la hoja? */
        fun onlySheetSides(l: Int): Boolean {
            if (sheetSides == 0) return false
            val o = l * 5
            val tT = st[o + 1] <= m; val tR = st[o] + st[o + 2] >= W - m; val tB = st[o + 1] + st[o + 3] >= H - m; val tL = st[o] <= m
            return !((tT && sheetSides and 1 == 0) || (tR && sheetSides and 2 == 0) || (tB && sheetSides and 4 == 0) || (tL && sheetSides and 8 == 0))
        }
        // Escala de la página con las componentes interiores de tamaño de letra
        val hs = ArrayList<Int>(); val hws = ArrayList<Double>()
        for (l in 1 until nc) {
            if (!hasStrong[l] || touches(l)) continue
            val o = l * 5; val cw = st[o + 2]; val ch = st[o + 3]
            if (st[o + 4] < 6 || ch < 4 || ch > H / 10 || cw > 6 * ch) continue
            hs.add(ch); hws.add(maxDt[l].toDouble())
        }
        hs.sort(); hws.sort()
        val letter = if (letterHint > 0) (letterHint * k).coerceIn(6.0, side / 8.0)
            else if (hs.size >= 5) hs[(hs.size * 0.75).toInt()].toDouble().coerceAtLeast(6.0) else side / 60.0
        val half = if (hws.size >= 5) hws[hws.size / 2].coerceIn(1.0, letter / 3) else 1.5
        val scale = PageScale(letter, half, side, W.toLong() * H)
        val thickR = max(2.5 * half + 1.0, 3.0).toFloat()
        /**
         * ¿Lleva dentro varios huecos pequeños (letras o iconos en claro sobre un bloque de color: un bocadillo de chat,
         * un botón, un encabezado)? Una sombra, la mesa o una cuña son macizas; una anilla tiene un solo hueco grande.
         */
        fun hasTextHoles(l: Int, x0: Int, y0: Int, cw: Int, ch: Int): Boolean {
            if (cw < 8 || ch < 8) return false
            val m = Mat(ch + 2, cw + 2, CvType.CV_8UC1, Scalar(0.0))
            val b = ByteArray((cw + 2) * (ch + 2))
            for (y in 0 until ch) for (x in 0 until cw) if (lab[(y0 + y) * W + x0 + x] == l) b[(y + 1) * (cw + 2) + x + 1] = -1
            m.put(0, 0, b)
            // huecos = lo que no es la componente ni está conectado con el exterior de su caja
            val inv = Mat(); Core.bitwise_not(m, inv)
            val lb = Mat(); val stt = Mat(); val ct = Mat()
            val n = Imgproc.connectedComponentsWithStats(inv, lb, stt, ct, 4, CvType.CV_32S)
            val sa = IntArray(n * 5); if (n > 0) stt.get(0, 0, sa)
            val area = st[l * 5 + 4]
            // (huecos del tamaño de una letra pequeña, no los poros de una mancha moteada)
            val minHole = max(12.0, 0.03 * letter * letter)
            var small = 0
            for (c in 1 until n) {
                val o = c * 5
                if (sa[o] == 0 || sa[o + 1] == 0 || sa[o] + sa[o + 2] >= cw + 2 || sa[o + 1] + sa[o + 3] >= ch + 2) continue
                if (sa[o + 4] >= minHole && sa[o + 4] < 0.25 * area) small++
            }
            m.release(); inv.release(); lb.release(); stt.release(); ct.release()
            return small >= 2
        }
        // Rasgos por lado de las que tocan el borde (recorriendo su caja)
        val remove = BooleanArray(nc)
        var nRemoved = 0
        for (l in 1 until nc) {
            if (!touches(l)) continue
            val o = l * 5; val x0 = st[o]; val y0 = st[o + 1]; val cw = st[o + 2]; val ch = st[o + 3]; val area = st[o + 4]
            val tL = x0 <= m; val tR = x0 + cw >= W - m; val tT = y0 <= m; val tB = y0 + ch >= H - m
            val hugT = BooleanArray(cw); val hugB = BooleanArray(cw); val hugL = BooleanArray(ch); val hugR = BooleanArray(ch)
            var thick = 0
            for (y in y0 until y0 + ch) {
                var i = y * W + x0
                for (x in x0 until x0 + cw) {
                    if (lab[i] == l) {
                        if (dt[i] >= thickR) thick++
                        if (y <= m) hugT[x - x0] = true
                        if (y >= H - 1 - m) hugB[x - x0] = true
                        if (x <= m) hugL[y - y0] = true
                        if (x >= W - 1 - m) hugR[y - y0] = true
                    }
                    i++
                }
            }
            val tf = thick.toDouble() / max(1, area)
            val fill = area.toDouble() / max(1, cw * ch)
            val corner = (tL || tR) && (tT || tB)
            fun frac(b: BooleanArray) = b.count { it }.toDouble() / max(1, b.size)
            fun runs(b: BooleanArray): Int { var n = 0; var prev = false; for (v in b) { if (v && !prev) n++; prev = v }; return n }
            val sides = ArrayList<EdgeComp>()
            if (tT) sides.add(EdgeComp(cw, ch, frac(hugT), tf, area, fill, corner, H, W, hugRuns = runs(hugT), sheetEdge = sheetSides and 1 != 0))
            if (tB) sides.add(EdgeComp(cw, ch, frac(hugB), tf, area, fill, corner, H, W, hugRuns = runs(hugB), sheetEdge = sheetSides and 4 != 0))
            if (tL) sides.add(EdgeComp(ch, cw, frac(hugL), tf, area, fill, corner, W, H, hugRuns = runs(hugL), sheetEdge = sheetSides and 8 != 0))
            if (tR) sides.add(EdgeComp(ch, cw, frac(hugR), tf, area, fill, corner, W, H, hugRuns = runs(hugR), sheetEdge = sheetSides and 2 != 0))
            if (sides.any { decide(it, scale) } && !hasTextHoles(l, x0, y0, cw, ch)) { remove[l] = true; nRemoved++; dbg("decide", l) }
        }
        // Líneas del canto de la hoja algo separadas del borde (el recorte dejó una tira de fondo): larga, fina, paralela
        // al lado y dentro de la banda exterior (6 %), con FONDO ajeno a la hoja entre ella y el borde (más oscuro, de
        // otro color o texturado en la imagen de entrada) -> se borran la línea y la tira. Una raya del encabezado o el
        // marco de una tabla tienen papel a ambos lados y se conservan.
        val strips = ArrayList<IntArray>()
        // Fracción de cada componente dentro de la zona de escritura conocida ([inside])
        val inC = IntArray(nc)
        if (inside != null && !inside.empty()) {
            val im = bag.mat(); Imgproc.resize(inside, im, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            val ib = ByteArray(W * H); im.get(0, 0, ib); im.release()
            for (i in lab.indices) { val l = lab[i]; if (l != 0 && ib[i].toInt() != 0) inC[l]++ }
        }
        fun insideFrac(l: Int) = inC[l].toDouble() / max(1, st[l * 5 + 4])
        // Fondo ajeno a la hoja en la imagen de entrada: papel de referencia (zona central sin tinta) y, para una tira,
        // ¿es más oscura, de otro color o más texturada que el papel?
        var gb: ByteArray? = null; var tb: ByteArray? = null; var cbb: ByteArray? = null
        var pg = 0.0; var pt = 0.0; var pc = 0.0
        if (src != null && !src.empty()) {
            val sg = bag.mat(); val sm = bag.mat()
            Imgproc.resize(src, sm, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val chs = ArrayList<Mat>(); Core.split(sm, chs)
            if (sm.channels() == 1) sm.copyTo(sg) else Imgproc.cvtColor(sm, sg, if (sm.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            val chr = bag.mat()
            if (chs.size >= 3) { val mx = bag.mat(); Core.max(chs[0], chs[1], mx); Core.max(mx, chs[2], mx); Core.min(chs[0], chs[1], chr); Core.min(chr, chs[2], chr); Core.subtract(mx, chr, chr); mx.release() }
            else { chr.create(H, W, CvType.CV_8UC1); chr.setTo(Scalar(0.0)) }
            for (c in chs) c.release()
            val bl = bag.mat(); Imgproc.blur(sg, bl, Size(5.0, 5.0)); val tex = bag.mat(); Core.absdiff(sg, bl, tex)
            val g0 = ByteArray(W * H); sg.get(0, 0, g0); val t0 = ByteArray(W * H); tex.get(0, 0, t0); val c0 = ByteArray(W * H); chr.get(0, 0, c0)
            val gs = ArrayList<Int>(); var tSum = 0.0; var cSum = 0.0
            for (y in H / 5 until H * 4 / 5 step 2) for (x in W / 5 until W * 4 / 5 step 2) {
                val i = y * W + x; if (lab[i] != 0 || nearB[i].toInt() != 0) continue
                gs.add(g0[i].toInt() and 0xFF); tSum += t0[i].toInt() and 0xFF; cSum += c0[i].toInt() and 0xFF
            }
            if (gs.size > 100) {
                gs.sort()
                pg = gs[gs.size / 2].toDouble(); pt = tSum / gs.size; pc = cSum / gs.size
                gb = g0; tb = t0; cbb = c0
            }
        }
        /** ¿La tira [x0,x1)x[y0,y1) (sin tinta) es fondo ajeno a la hoja? null si no se puede medir. */
        fun foreignStrip(x0: Int, y0: Int, x1: Int, y1: Int): Boolean? {
            val g = gb ?: return null; val t = tb!!; val c = cbb!!
            var n = 0; var sg = 0.0; var st2 = 0.0; var sc = 0.0
            for (y in max(0, y0) until min(H, y1)) for (x in max(0, x0) until min(W, x1)) {
                val i = y * W + x; if (lab[i] != 0 || nearB[i].toInt() != 0) continue
                n++; sg += g[i].toInt() and 0xFF; st2 += t[i].toInt() and 0xFF; sc += c[i].toInt() and 0xFF
            }
            if (n < 40) return null
            sg /= n; st2 /= n; sc /= n
            // (sólo textura y color: una sombra sobre el papel es más oscura pero no es fondo ajeno)
            return st2 > 2.5 * pt + 6.0 || abs(sc - pc) > 25.0
        }
        if (gb != null) {
            val band6 = 0.06
            for (l in 1 until nc) {
                if (remove[l] || touches(l)) continue
                val o = l * 5; val x0 = st[o]; val y0 = st[o + 1]; val cw = st[o + 2]; val ch = st[o + 3]
                val cand = ArrayList<IntArray>()   // [lado, rx0, ry0, rx1, ry1] de la tira (exclusivo)
                val thinH = ch <= max(1.5 * letter, 0.02 * H) && cw >= 0.2 * W && cw >= 6 * ch
                val thinV = cw <= max(1.5 * letter, 0.02 * W) && ch >= 0.2 * H && ch >= 6 * cw
                if (thinH && y0 + ch <= band6 * H) cand.add(intArrayOf(0, x0, 0, x0 + cw, y0))
                if (thinH && y0 >= (1 - band6) * H) cand.add(intArrayOf(1, x0, y0 + ch, x0 + cw, H))
                if (thinV && x0 + cw <= band6 * W) cand.add(intArrayOf(2, 0, y0, x0, y0 + ch))
                if (thinV && x0 >= (1 - band6) * W) cand.add(intArrayOf(3, x0 + cw, y0, W, y0 + ch))
                for (r in cand) {
                    // (en un cuaderno con la zona de escritura conocida, una raya larga y fina paralela al borde fuera
                    // de la cuadrícula es el canto o el marco: no hace falta que la tira sea fondo)
                    val foreign = foreignStrip(r[1], r[2], r[3], r[4]) ?: continue
                    if (foreign || (inside != null && insideFrac(l) < 0.5)) {
                        remove[l] = true; nRemoved++; dbg("margen", l)
                        strips.add(when (r[0]) {
                            0 -> intArrayOf(x0, 0, x0 + cw, y0 + ch)
                            1 -> intArrayOf(x0, y0, x0 + cw, H)
                            2 -> intArrayOf(0, y0, x0 + cw, y0 + ch)
                            else -> intArrayOf(x0, y0, W, y0 + ch)
                        })
                        break
                    }
                }
            }
        }
        // Rayas rectas del canto dentro de componentes mayores (la línea del borde de la hoja unida a motas o a restos
        // de la rejilla): en la banda exterior (4.5 %), apertura con un segmento largo paralelo al lado; cada tramo largo
        // y fino que toca el borde, tiene fondo ajeno entre él y el borde o queda fuera de la cuadrícula se borra (sólo
        // sus píxeles y las motas de la tira).
        val linePix = ByteArray(W * H)
        var nLines = 0
        run {
            val wb = ByteArray(W * H); weak.get(0, 0, wb)
            val ib = if (inside != null && !inside.empty()) ByteArray(W * H).also {
                val im = bag.mat(); Imgproc.resize(inside, im, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST); im.get(0, 0, it); im.release()
            } else null
            for (sideIdx in 0..3) {
                val horiz = sideIdx < 2
                val bw = ((if (horiz) H else W) * 0.045).roundToInt().coerceAtLeast(m + 2)
                val rx0 = if (sideIdx == 3) W - bw else 0; val ry0 = if (sideIdx == 1) H - bw else 0
                val rw = if (horiz) W else bw; val rh = if (horiz) bw else H
                val sub = bag.mat(); weak.submat(ry0, ry0 + rh, rx0, rx0 + rw).copyTo(sub)
                Imgproc.dilate(sub, sub, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, if (horiz) Size(1.0, 7.0) else Size(7.0, 1.0)))
                // (cierre a lo largo: la raya del canto sale a trazos)
                val gapK = Cv.odd(max(3, (0.4 * letter).roundToInt())).toDouble()
                Imgproc.morphologyEx(sub, sub, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, if (horiz) Size(gapK, 1.0) else Size(1.0, gapK)))
                val len = max(3.0 * letter, 0.08 * (if (horiz) W else H)).roundToInt()
                Imgproc.morphologyEx(sub, sub, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, if (horiz) Size(len.toDouble(), 1.0) else Size(1.0, len.toDouble())))
                if (Core.countNonZero(sub) == 0) { sub.release(); continue }
                val lb = bag.mat(); val ss = bag.mat(); val cc = bag.mat()
                val n = Imgproc.connectedComponentsWithStats(sub, lb, ss, cc, 8, CvType.CV_32S)
                val sa = IntArray(n * 5); ss.get(0, 0, sa)
                val la = IntArray(rw * rh); lb.get(0, 0, la)
                for (c in 1 until n) {
                    val o = c * 5
                    val px0 = sa[o] + rx0; val py0 = sa[o + 1] + ry0; val pw = sa[o + 2]; val ph = sa[o + 3]
                    val along = if (horiz) pw else ph; val across = if (horiz) ph else pw
                    if (along < len || across > max(1.5 * letter, 0.02 * (if (horiz) H else W))) continue
                    // raya casi continua y FINA en la máscara original: un renglón de texto tiene huecos entre palabras y
                    // ocupa en cada columna la altura de la x o más
                    var cov = 0
                    val th = IntArray(along)
                    for (t in 0 until along) {
                        var cnt = 0
                        for (q in 0 until across) {
                            val x = if (horiz) px0 + t else px0 + q; val y = if (horiz) py0 + q else py0 + t
                            if (wb[y * W + x].toInt() != 0) cnt++
                        }
                        th[t] = cnt
                        if (cnt > 0) cov++
                    }
                    if (cov < 0.75 * along) continue
                    val thMed = th.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
                    if (thMed > max(3.0, 0.35 * letter)) continue
                    // un renglón de letra fina (tique, formulario) también es "fino" en la mediana, pero sus trazos
                    // verticales ocupan la altura de la letra en muchas columnas; una raya, en casi ninguna (sólo donde
                    // la cruza la rejilla)
                    // (el trazo vertical se sigue fuera de la caja del tramo: la apertura sólo deja la parte de abajo o
                    // de arriba de las letras)
                    val tallMin = max(6.0, 0.4 * letter)
                    var tall = 0
                    for (t in 0 until along) {
                        if (th[t] == 0) continue
                        var lo = Int.MAX_VALUE; var hi = -1
                        for (q in 0 until across) {
                            val x = if (horiz) px0 + t else px0 + q; val y = if (horiz) py0 + q else py0 + t
                            if (wb[y * W + x].toInt() != 0) { val v = if (horiz) y else x; lo = min(lo, v); hi = max(hi, v) }
                        }
                        val lim = if (horiz) H else W
                        fun ink(v: Int) = if (horiz) wb[v * W + px0 + t].toInt() != 0 else wb[(py0 + t) * W + v].toInt() != 0
                        while (lo > 0 && ink(lo - 1) && hi - lo < 4 * tallMin) lo--
                        while (hi < lim - 1 && ink(hi + 1) && hi - lo < 4 * tallMin) hi++
                        if (hi - lo + 1 >= tallMin) tall++
                    }
                    if (tall > 0.15 * cov) continue
                    // tira entre el tramo y el borde
                    val strip = when (sideIdx) {
                        0 -> intArrayOf(px0, 0, px0 + pw, py0)
                        1 -> intArrayOf(px0, py0 + ph, px0 + pw, H)
                        2 -> intArrayOf(0, py0, px0, py0 + ph)
                        else -> intArrayOf(px0 + pw, py0, W, py0 + ph)
                    }
                    val depth = if (horiz) strip[3] - strip[1] else strip[2] - strip[0]
                    var inPix = 0; var tot = 0
                    if (ib != null) for (y in py0 until py0 + ph) for (x in px0 until px0 + pw) {
                        if (la[(y - ry0) * rw + (x - rx0)] == c) { tot++; if (ib[y * W + x].toInt() != 0) inPix++ }
                    }
                    val outsideGrid = ib != null && inPix < 0.5 * max(1, tot)
                    // (o la tira hasta el borde está vacía: nada de tamaño de letra entre la raya y el borde)
                    var emptyStrip = true
                    for (l in 1 until nc) {
                        val q = l * 5
                        if (remove[l] || max(st[q + 2], st[q + 3]) < 0.6 * letter) continue
                        val cx = st[q] + st[q + 2] / 2; val cy = st[q + 1] + st[q + 3] / 2
                        if (cx >= strip[0] && cx < strip[2] && cy >= strip[1] && cy < strip[3]) { emptyStrip = false; break }
                    }
                    val ok = depth <= m || outsideGrid || emptyStrip || foreignStrip(strip[0], strip[1], strip[2], strip[3]) == true
                    if (!ok) continue
                    nLines++
                    for (y in py0 until py0 + ph) for (x in px0 until px0 + pw) {
                        if (la[(y - ry0) * rw + (x - rx0)] == c) linePix[y * W + x] = 1
                    }
                    // motas de la tira (lo que no es letra entre el tramo y el borde)
                    for (l in 1 until nc) {
                        if (remove[l]) continue
                        val q = l * 5
                        if (st[q] >= strip[0] && st[q + 1] >= strip[1] && st[q] + st[q + 2] <= strip[2] && st[q + 1] + st[q + 3] <= strip[3] &&
                            max(st[q + 2], st[q + 3]) < 0.6 * letter) { remove[l] = true; nRemoved++; dbg("tira", l) }
                    }
                }
                sub.release(); lb.release(); ss.release(); cc.release()
            }
            // Píxeles de las rayas: los de tinta del tramo con 2 px de margen perpendicular
            if (nLines > 0) {
                val lm = bag.mat(); lm.create(H, W, CvType.CV_8UC1)
                val bb = ByteArray(W * H); for (i in bb.indices) if (linePix[i].toInt() != 0) bb[i] = -1
                lm.put(0, 0, bb)
                Imgproc.dilate(lm, lm, Cv.kernel(Imgproc.MORPH_RECT, 5))
                lm.get(0, 0, bb)
                for (i in bb.indices) linePix[i] = if (bb[i].toInt() != 0 && wb[i].toInt() != 0) 1 else 0
                lm.release()
            }
        }
        // Restos pequeños pegados al borde rodeados de fondo ajeno (astilla de mesa, esquina de la tapa): una letra
        // cortada por el borde está rodeada de papel
        if (gb != null) {
            for (l in 1 until nc) {
                if (remove[l] || !touches(l) || onlySheetSides(l)) continue
                val o = l * 5; val x0 = st[o]; val y0 = st[o + 1]; val cw = st[o + 2]; val ch = st[o + 3]
                val pad = max(2, (0.3 * letter).roundToInt())
                if (max(cw, ch) > 3 * letter) continue
                if (foreignStrip(x0 - pad, y0 - pad, x0 + cw + pad, y0 + ch + pad) == true) { remove[l] = true; nRemoved++; dbg("borde-ajeno", l) }
            }
        }
        // Manchas macizas cerca del borde (banda del 3 %) sin tocarlo, rodeadas de fondo ajeno (trozo de mesa o de la
        // tapa en una esquina): un titular en negrita está rodeado de papel y se conserva
        if (gb != null) {
            val b3 = max(m + 1, (0.03 * side).roundToInt())
            for (l in 1 until nc) {
                if (remove[l] || touches(l)) continue
                val o = l * 5; val x0 = st[o]; val y0 = st[o + 1]; val cw = st[o + 2]; val ch = st[o + 3]
                if (!(x0 <= b3 || y0 <= b3 || x0 + cw >= W - b3 || y0 + ch >= H - b3)) continue
                if (maxDt[l] < thickR || max(cw, ch) > 0.15 * side) continue
                val pad = max(2, (0.3 * letter).roundToInt())
                // (o mancha maciza muy gruesa: ninguna letra tiene un núcleo así)
                val solid = maxDt[l] >= max(3.0 * thickR, 0.35 * letter) && st[o + 4] >= 0.5 * cw * ch
                if (solid || foreignStrip(x0 - pad, y0 - pad, x0 + cw + pad, y0 + ch + pad) == true) { remove[l] = true; nRemoved++; dbg("banda3", l) }
            }
        }
        // Contenido de tamaño de letra (con 1.5 letras de margen): las motas junto a él no se tocan
        val content = bag.mat(); content.create(H, W, CvType.CV_8UC1); content.setTo(Scalar(0.0))
        run {
            val cb = ByteArray(W * H)
            val big = BooleanArray(nc)
            for (l in 1 until nc) { val o = l * 5; if (!remove[l] && max(st[o + 2], st[o + 3]) >= 0.4 * letter && hasStrong[l]) big[l] = true }
            for (i in lab.indices) if (big[lab[i]]) cb[i] = -1
            content.put(0, 0, cb)
            Imgproc.dilate(content, content, Cv.kernel(Imgproc.MORPH_RECT, Cv.odd(max(3, (3.0 * letter).roundToInt()))))
        }
        val cb = ByteArray(W * H); content.get(0, 0, cb)
        // Fuera de la zona de escritura conocida ([inside]: cuadrícula del cuaderno): marco impreso, cantos de las hojas
        // de debajo, espiral, mesa. Allí sólo se conserva lo que parece escritura (trazo fino de tamaño de letra o mayor):
        // motas, rayas largas y finas (cantos, líneas del marco) y manchas gruesas se borran.
        if (inside != null && !inside.empty()) {
            val ob = 0.1 * side
            for (l in 1 until nc) {
                if (remove[l] || insideFrac(l) > 0.3) continue
                val o = l * 5; val x0 = st[o]; val y0 = st[o + 1]; val cw = st[o + 2]; val ch = st[o + 3]
                // sólo en la banda exterior (lo de dentro de la página, aunque la rejilla no se viera, no se toca)
                if (!(x0 + cw <= ob || y0 + ch <= ob || x0 >= W - ob || y0 >= H - ob)) continue
                val mx = max(cw, ch); val mn = min(cw, ch)
                val cx = (x0 + cw / 2).coerceIn(0, W - 1); val cy = (y0 + ch / 2).coerceIn(0, H - 1)
                val speck = mx < 0.4 * letter && cb[cy * W + cx].toInt() == 0
                val line = mx >= 4 * letter && mn <= 0.5 * letter
                val mass = maxDt[l] >= 2 * thickR
                // rodeada de fondo ajeno (marco de color del cuaderno, tapa, mesa) y no de papel: no es escritura
                val pad = max(2, letter.roundToInt())
                val surround = !speck && !line && !mass &&
                    foreignStrip(x0 - pad, y0 - pad, x0 + cw + pad, y0 + ch + pad) == true
                if (speck || line || mass || surround) { remove[l] = true; nRemoved++; dbg("fuera-rejilla", l) }
            }
        }
        // Motas aisladas en la banda del borde (3 % del lado), lejos de cualquier letra
        val bandW = max(m + 1, (0.03 * side).roundToInt())
        for (l in 1 until nc) {
            if (remove[l]) continue
            val o = l * 5; val x0 = st[o]; val y0 = st[o + 1]; val cw = st[o + 2]; val ch = st[o + 3]
            val inBand = x0 + cw <= bandW || y0 + ch <= bandW || x0 >= W - bandW || y0 >= H - bandW
            if (!inBand || max(cw, ch) >= 0.4 * letter) continue
            val cx = (x0 + cw / 2).coerceIn(0, W - 1); val cy = (y0 + ch / 2).coerceIn(0, H - 1)
            if (cb[cy * W + cx].toInt() != 0) continue
            remove[l] = true; nRemoved++; dbg("mota", l)
        }
        log?.invoke("bordes: letra=%.1f trazo=%.1f borradas=$nRemoved de ${nc - 1}, rayas=$nLines".format(letter, half))
        if (nRemoved == 0 && nLines == 0) return@use 0
        val rb = ByteArray(W * H)
        for (i in lab.indices) if (remove[lab[i]] || linePix[i].toInt() != 0) rb[i] = -1
        for (r in strips) for (y in r[1] until r[3]) for (x in r[0] until r[2]) rb[y * W + x] = -1
        val rm = bag.mat(); rm.create(H, W, CvType.CV_8UC1); rm.put(0, 0, rb)
        Imgproc.dilate(rm, rm, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val rf = bag.mat(); Imgproc.resize(rm, rf, Size(fw.toDouble(), fh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.threshold(rf, rf, 0.0, 255.0, Imgproc.THRESH_BINARY)
        out.setTo(Scalar.all(255.0), rf)
        nRemoved
    }
}
