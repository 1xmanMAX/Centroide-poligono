package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RECUADRO EXACTO de las fotografías de la página: paso ÚNICO por el que pasa toda caja de foto, venga de donde venga
 * (bloques de color de [PrintedPage], fotos por textura, bandas, Stats.pictures, PP-DocLayoutV3 o Jev), antes del render.
 *
 * Una foto impresa es un rectángulo de borde nítido con textura o tono medio en casi todo su interior; el papel blanco,
 * el texto, el pie de foto, una firma o un sello no lo son. Por eso:
 *  1. Evidencia propia de la imagen (escala de análisis): píxeles "de foto" = grano (desviación local ≥ 3× la del papel),
 *     más oscuros que el papel o con color; zonas DENSAS (≥ 80 % en una ventana de ~2 % del lado) en componentes.
 *  2. Cada caja candidata sólo SELECCIONA componentes densas que caen sobre todo dentro de ella (o de las que ella es un
 *     trozo): la caja de la pista nunca amplía nada por sí misma; la extensión la da la imagen.
 *  3. Ajuste por perfiles: filas y columnas con densidad de foto ≥ [ROW_T] en tramos continuos -> se recortan las franjas
 *     de papel, de texto y el pie de foto; si hay dos tramos separados por papel o texto, son dos fotos.
 *  4. Cada lado se lleva al salto foto -> papel (el borde nítido) en la imagen a resolución completa.
 *  5. ¿Es una FOTOGRAFÍA de cámara? Textura natural, tono medio en casi toda la caja, pocos tramos planos y muchos colores
 *     (no una firma -trazos finos sobre papel-, ni un logo o una cabecera -bloques planos de color-, ni un sello). Jev,
 *     si está, decide los casos dudosos y puede vetar ([Verdict]); nunca amplía una caja.
 *  6. Las cajas de varias fuentes se unen sin duplicar.
 *
 * Coordenadas: las candidatas y el resultado en px de la salida ([ow] x [oh]); el análisis, sobre la imagen normalizada
 * reducida (papel ≈ blanco) que se pasa.
 */
object PhotoBoxRefine {

    /** Fuentes de las candidatas (bits; sólo para el registro y la medición). */
    const val SRC_COLOR = 1
    const val SRC_PICT = 2
    const val SRC_TEX = 4
    const val SRC_LAYOUT = 8
    const val SRC_JEV = 16

    /** Caja candidata (px de salida) y su fuente. */
    class Cand(val rect: Rect, val src: Int)

    /**
     * Opinión de Jev sobre una caja (fracciones 0..1 de la página): [p] = probabilidad de que sea una FOTOGRAFÍA de cámara.
     */
    class Verdict(val box: FloatArray, val p: Double)

    /** Medidas del interior de una caja (escala de análisis). */
    class Metrics(
        /** Píxeles de foto (grano, tono medio o color). */
        val photo: Double,
        /** Papel liso (claro, sin grano ni color). */
        val paper: Double,
        /** Con grano (desviación local ≥ umbral). */
        val grain: Double,
        /** Tramos PLANOS que no son papel (bloque de color liso de un logo o de una cabecera). */
        val flat: Double,
        /** Colores distintos (celdas de un histograma 6x6x6 con ≥ 0,4 %). */
        val colors: Int,
        /** Tono medio (ni papel ni tinta negra: 0,15..0,9 del papel). */
        val mid: Double,
        /** Fracción de los píxeles en los DOS colores más frecuentes (un logo o una cabecera: casi todo; una foto: poco). */
        val top2: Double = 0.0,
    ) {
        override fun toString() = "foto=%.2f papel=%.2f grano=%.2f plano=%.2f colores=$colors medio=%.2f top2=%.2f".format(photo, paper, grain, flat, mid, top2)
    }

    /** Resultado: rectángulo (px de salida), fuentes que lo pidieron, ¿fotografía?, medidas y opinión de Jev (o null). */
    class Found(val rect: Rect, val src: Int, val photo: Boolean, val m: Metrics, val jevP: Double?, val why: String, val local: PhotoMath.Kind = PhotoMath.Kind.NOT, val big: Boolean = true) {
        override fun toString() = "$rect $m jev=$jevP -> $why"
    }

    /** Fila/columna "de foto": densidad mínima de píxeles de foto en el tramo. */
    const val ROW_T = 0.62
    /** Densidad de la zona densa (ventana de [DENSE_WIN] del lado largo). */
    private const val DENSE_T = 0.8
    private const val DENSE_WIN = 0.02
    /** Lado mínimo de una foto (fracción del lado largo de la página) y área mínima (fracción de la página). */
    const val MIN_SIDE = 0.075
    const val MIN_AREA = 0.012
    /** Hueco máximo que se salta dentro de una foto (filas claras: cielo blanco, una raya) (fracción del lado largo). */
    private const val GAP = 0.006
    /** Borde exacto ([snapFull]): búsqueda de ±[SNAP_R] px de análisis y escalón mínimo de textura (niveles de gris). */
    private const val SNAP_R = 16.0
    private const val SNAP_STEP = 8.0
    private const val SNAP_PAPER = 30.0
    /** Subida mínima de la textura de fuera a dentro (una raya del marco o de la cabecera apenas la sube). */
    private const val SNAP_TEX = 0.6
    /** ... o todo el lado de dentro al menos tantos niveles más oscuro que el papel. */
    private const val SNAP_DARK = 20.0
    /**
     * Franja pálida dentro de una foto (calzada, pared clara) que une dos tramos: como mucho [BRIDGE_MAX] del lado largo,
     * ninguna fila con ≥ [BRIDGE_PAPER] de papel liso y ≥ [BRIDGE_PHOTO] de píxeles de foto. El papel o el pie de foto
     * entre dos fotos no la cumplen.
     */
    private const val BRIDGE_MAX = 0.08
    private const val BRIDGE_PAPER = 0.75
    private const val BRIDGE_PHOTO = 0.25
    /** Prolongación por líneas pálidas ([extend]): densidad mínima de foto, papel máximo y largo máximo (fracción de la caja). */
    private const val EXT_D = 0.3
    private const val EXT_PAPER = 0.6
    private const val EXT_MAX = 0.15
    /** Largo mínimo (fracción del lado largo) de un tramo unido por un puente en un extremo del grupo (no un pie de foto). */
    private const val BRIDGE_RUN = 0.04
    /**
     * Jev ("¿fotografía de cámara?", JevPage.cameraQuestion): probabilidad mínima para aceptar como fotografía una caja
     * DUDOSA y máxima para vetar una caja local. Calibrado en las 13 fotos reales juliaca2: fotografías 0,79–0,99 (aéreas
     * 0,79–0,88), firmas, sellos, logos y cabeceras 0,03–0,58 -> umbral alto en el hueco.
     */
    const val JEV_YES = 0.75
    const val JEV_NO = 0.3

    @Volatile var log: ((String) -> Unit)? = null
    private val DBG = System.getenv("PBR_DBG") != null

    /**
     * Cajas finales. [an] RGB 8UC3 normalizado (papel ≈ [pm]) a la escala de análisis; [full] RGB 8UC3 a resolución de
     * entrada (para el borde exacto) o null; [cands] en px de una salida [ow] x [oh]; [verdicts] de Jev (fracciones).
     * Devuelve TODAS las cajas ajustadas (fotos y no fotos, para registro); las fotografías son las de [Found.photo].
     */
    fun refine(an: Mat, pm: Double, cands: List<Cand>, ow: Int, oh: Int, full: Mat? = null, verdicts: List<Verdict> = emptyList()): List<Found> =
        MatBag().use { bag ->
            if (cands.isEmpty()) return@use emptyList()
            val mp = maps(an, pm, bag) ?: return@use emptyList()
            val w = mp.w; val h = mp.h; val side = max(w, h)
            val kx = ow.toDouble() / w; val ky = oh.toDouble() / h
            // componentes densas
            val comps = mp.comps
            // 1) cada candidata SELECCIONA las componentes densas que caen sobre todo dentro de ella (o de las que ella es un
            //    trozo); semilla = su rectángulo común. 2) ajuste por perfiles de la semilla (puede dividirse en varias fotos)
            val boxes = ArrayList<Pair<IntArray, Int>>()
            val seen = HashMap<List<Int>, List<IntArray>>()
            for (c in cands) {
                val r = Cv.clampRect(Rect((c.rect.x / kx).toInt(), (c.rect.y / ky).toInt(), max(1, (c.rect.width / kx).roundToInt()), max(1, (c.rect.height / ky).roundToInt())), w, h)
                if (r.width <= 0 || r.height <= 0) continue
                val sel = comps.indices.filter { i -> val inter = mp.denseIn(i, r); inter > 0 && (inter >= 0.5 * comps[i].area || inter >= 0.5 * r.area()) }
                if (sel.isEmpty()) continue
                val seed = intArrayOf(sel.minOf { comps[it].box[0] }, sel.minOf { comps[it].box[1] }, sel.maxOf { comps[it].box[2] }, sel.maxOf { comps[it].box[3] })
                for (b in seen.getOrPut(seed.toList()) { split(mp, seed, side, 0).map { s0 -> extend(mp, s0).also { if (DBG) log?.invoke("semilla ${seed.toList()} -> ${s0.toList()} -> ${it.toList()}") } } }) boxes += b.clone() to c.src
            }
            // 3) unión sin duplicar (la misma foto desde dos componentes)
            val merged = dedupe(boxes)
            val out = ArrayList<Found>()
            for ((b, src) in merged) {
                // borde exacto (fracciones) y la caja de análisis que le corresponde (medidas)
                val fr0 = if (full != null) snapFull(full, b, w, h) else doubleArrayOf(b[0].toDouble() / w, b[1].toDouble() / h, b[2].toDouble() / w, b[3].toDouble() / h)
                if (DBG) log?.invoke("borde ${b.toList()} -> ${fr0.map { "%.4f".format(it) }}")
                val bb = intArrayOf((fr0[0] * w).roundToInt(), (fr0[1] * h).roundToInt(), (fr0[2] * w).roundToInt(), (fr0[3] * h).roundToInt())
                val m = metrics(mp, bb)
                val bw = bb[2] - bb[0]; val bh = bb[3] - bb[1]
                val big = min(bw, bh) >= MIN_SIDE * side && bw.toDouble() * bh >= MIN_AREA * w * h
                val rx0 = (fr0[0] * ow).roundToInt(); val ry0 = (fr0[1] * oh).roundToInt()
                val rect = Cv.clampRect(Rect(rx0, ry0, (fr0[2] * ow).roundToInt() - rx0, (fr0[3] * oh).roundToInt() - ry0), ow, oh)
                val fr = floatArrayOf(fr0[0].toFloat(), fr0[1].toFloat(), fr0[2].toFloat(), fr0[3].toFloat())
                val jev = verdicts.map { iou(it.box, fr) to it.p }.filter { it.first >= 0.5 }.maxByOrNull { it.first }?.second
                val local = PhotoMath.classify(m)
                val (photo, why) = when {
                    !big -> false to "pequeña"
                    local == PhotoMath.Kind.NOT -> false to "no es foto (local)"
                    jev != null && jev < JEV_NO -> false to "Jev: no es foto"
                    local == PhotoMath.Kind.PHOTO -> true to "foto (local)"
                    jev != null && jev >= JEV_YES -> true to "foto dudosa confirmada por Jev"
                    else -> false to "dudosa"
                }
                log?.invoke("caja %d,%d %dx%d src=$src %s jev=%s -> $why".format(rect.x, rect.y, rect.width, rect.height, m, jev?.let { "%.2f".format(it) } ?: "-"))
                if (rect.width > 0 && rect.height > 0) out += Found(rect, src, photo, m, jev, why, local, big)
            }
            out
        }

    /** Sólo los rectángulos de las fotografías. */
    fun photos(found: List<Found>): List<Rect> = found.filter { it.photo }.map { it.rect }

    // ------------------------------------------------------------------------------------------------------- mapas

    internal class Comp(val box: IntArray, val area: Int, val label: Int)

    internal class Maps(
        val w: Int, val h: Int,
        val g: ByteArray, val rgb: ByteArray,
        /** 1 = píxel de foto. */
        val ph: ByteArray,
        /** 1 = papel liso. */
        val paper: ByteArray,
        /** 1 = grano. */
        val grain: ByteArray,
        /** 1 = tono medio (0,15..0,85 del papel). */
        val mid: ByteArray,
        val pm: Double,
        val labels: IntArray, val comps: List<Comp>,
    ) {
        /** Integral de [ph] ((w+1) x (h+1)). */
        val ii = IntArray((w + 1) * (h + 1)).also { a ->
            for (y in 0 until h) { var s = 0; for (x in 0 until w) { s += ph[y * w + x]; a[(y + 1) * (w + 1) + x + 1] = a[y * (w + 1) + x + 1] + s } }
        }
        /** Píxeles de foto en [x0, x1) x [y0, y1). */
        fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val a = x0.coerceIn(0, w); val b = x1.coerceIn(0, w); val c = y0.coerceIn(0, h); val d = y1.coerceIn(0, h)
            if (b <= a || d <= c) return 0
            return ii[d * (w + 1) + b] - ii[c * (w + 1) + b] - ii[d * (w + 1) + a] + ii[c * (w + 1) + a]
        }
        private fun integral(m: ByteArray) = IntArray((w + 1) * (h + 1)).also { a ->
            for (y in 0 until h) { var s = 0; for (x in 0 until w) { s += m[y * w + x]; a[(y + 1) * (w + 1) + x + 1] = a[y * (w + 1) + x + 1] + s } }
        }
        val mi = integral(mid)
        val gi = integral(grain)
        fun grainSum(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val a = x0.coerceIn(0, w); val b = x1.coerceIn(0, w); val c = y0.coerceIn(0, h); val d = y1.coerceIn(0, h)
            if (b <= a || d <= c) return 0
            return gi[d * (w + 1) + b] - gi[c * (w + 1) + b] - gi[d * (w + 1) + a] + gi[c * (w + 1) + a]
        }
        fun midSum(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val a = x0.coerceIn(0, w); val b = x1.coerceIn(0, w); val c = y0.coerceIn(0, h); val d = y1.coerceIn(0, h)
            if (b <= a || d <= c) return 0
            return mi[d * (w + 1) + b] - mi[c * (w + 1) + b] - mi[d * (w + 1) + a] + mi[c * (w + 1) + a]
        }
        /** Integral de [paper]. */
        val pi = IntArray((w + 1) * (h + 1)).also { a ->
            for (y in 0 until h) { var s = 0; for (x in 0 until w) { s += paper[y * w + x]; a[(y + 1) * (w + 1) + x + 1] = a[y * (w + 1) + x + 1] + s } }
        }
        fun paperSum(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val a = x0.coerceIn(0, w); val b = x1.coerceIn(0, w); val c = y0.coerceIn(0, h); val d = y1.coerceIn(0, h)
            if (b <= a || d <= c) return 0
            return pi[d * (w + 1) + b] - pi[c * (w + 1) + b] - pi[d * (w + 1) + a] + pi[c * (w + 1) + a]
        }
        fun rowP(y: Int, x0: Int, x1: Int) = paperSum(x0, y, x1, y + 1).toDouble() / max(1, min(w, x1) - max(0, x0))
        fun colP(x: Int, y0: Int, y1: Int) = paperSum(x, y0, x + 1, y1).toDouble() / max(1, min(h, y1) - max(0, y0))
        fun rowD(y: Int, x0: Int, x1: Int) = sum(x0, y, x1, y + 1).toDouble() / max(1, min(w, x1) - max(0, x0))
        fun colD(x: Int, y0: Int, y1: Int) = sum(x, y0, x + 1, y1).toDouble() / max(1, min(h, y1) - max(0, y0))
        /** Píxeles de la componente [i] dentro de [r]. */
        fun denseIn(i: Int, r: Rect): Int {
            val cp = comps[i]
            val x0 = max(r.x, cp.box[0]); val y0 = max(r.y, cp.box[1]); val x1 = min(r.x + r.width, cp.box[2]); val y1 = min(r.y + r.height, cp.box[3])
            if (x1 <= x0 || y1 <= y0) return 0
            var k = 0
            for (y in y0 until y1) { val o = y * w; for (x in x0 until x1) if (labels[o + x] == cp.label) k++ }
            return k
        }
    }

    internal fun maps(an: Mat, pm: Double, bag: MatBag): Maps? {
        val w = an.cols(); val h = an.rows(); val n = w * h
        if (w < 50 || h < 50) return null
        val g = bag.add(Cv.gray(an))
        val f = bag.mat(); g.convertTo(f, CvType.CV_32F)
        val mu = bag.mat(); Imgproc.blur(f, mu, Size(5.0, 5.0))
        val sq = bag.mat(); Core.multiply(f, f, sq)
        val mu2 = bag.mat(); Imgproc.blur(sq, mu2, Size(5.0, 5.0))
        val m2 = bag.mat(); Core.multiply(mu, mu, m2)
        val sd = bag.mat(); Core.subtract(mu2, m2, sd); Core.max(sd, Scalar(0.0), sd); Core.sqrt(sd, sd)
        val gb = ByteArray(n); g.get(0, 0, gb)
        val sdA = FloatArray(n); sd.get(0, 0, sdA)
        val rgb = ByteArray(n * 3); an.get(0, 0, rgb)
        // ruido del papel: mediana de la desviación local en el papel
        val pv = ArrayList<Float>()
        for (i in 0 until n step 3) if ((gb[i].toInt() and 0xFF) > 0.94 * pm) pv.add(sdA[i])
        val noise = if (pv.size < 100) 2.0 else { pv.sort(); pv[pv.size / 2].toDouble() }
        val tThr = max(4.0, 3.0 * noise)
        val ph = ByteArray(n); val paper = ByteArray(n); val grain = ByteArray(n); val mid = ByteArray(n)
        for (i in 0 until n) {
            val v = gb[i].toInt() and 0xFF
            val r = rgb[i * 3].toInt() and 0xFF; val gg = rgb[i * 3 + 1].toInt() and 0xFF; val b = rgb[i * 3 + 2].toInt() and 0xFF
            val chroma = max(r, max(gg, b)) - min(r, min(gg, b))
            val gr = sdA[i] > tThr
            if (gr) grain[i] = 1
            if (v > 0.15 * pm && v < 0.85 * pm) mid[i] = 1
            if (gr || v < 0.83 * pm || chroma > PhotoMath.CHROMA_T) ph[i] = 1
            if (!gr && v > 0.9 * pm && chroma <= PhotoMath.CHROMA_T / 2) paper[i] = 1
        }
        // zonas densas
        val tm = bag.mat(); tm.create(h, w, CvType.CV_8U); tm.put(0, 0, ph)
        val tf = bag.mat(); tm.convertTo(tf, CvType.CV_32F)
        val k = max(5, (DENSE_WIN * max(w, h)).roundToInt()).toDouble()
        val dens = bag.mat(); Imgproc.blur(tf, dens, Size(k, k))
        val dm = bag.mat(); Imgproc.threshold(dens, dm, DENSE_T, 255.0, Imgproc.THRESH_BINARY); dm.convertTo(dm, CvType.CV_8U)
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(dm, labels, stats, cents, 8, CvType.CV_32S)
        val st = IntArray(max(0, nc) * 5); if (nc > 0) stats.get(0, 0, st)
        val lb = IntArray(n); labels.get(0, 0, lb)
        val side = max(w, h)
        val comps = ArrayList<Comp>()
        for (c in 1 until nc) {
            val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bh = st[c * 5 + 3]; val a = st[c * 5 + 4]
            if (min(bw, bh) < 0.5 * MIN_SIDE * side) continue
            comps += Comp(intArrayOf(x, y, x + bw, y + bh), a, c)
        }
        return Maps(w, h, gb, rgb, ph, paper, grain, mid, pm, lb, comps)
    }

    // ------------------------------------------------------------------------------------------------ perfiles

    /**
     * Tramos [a, b) de [lo, hi) con [d] ≥ [t], saltando huecos de ≤ [gap] sin líneas de papel ([isPaper]) y de largo ≥
     * [minLen]. Dos tramos se unen además si [bridge] acepta la franja entre ellos; en los extremos de lo unido sólo quedan
     * tramos que pasan [runOk] (largos: el pie de foto pegado a la foto por su fondo claro se suelta).
     */
    internal fun runs(lo: Int, hi: Int, t: Double, gap: Int, minLen: Int, isPaper: (Int) -> Boolean = { false }, bridge: (Int, Int) -> Boolean = { _, _ -> false },
                      runOk: (IntArray) -> Boolean = { true }, d: (Int) -> Double): List<IntArray> {
        val raw = ArrayList<IntArray>()
        var a = -1; var last = -1; var paperSince = false
        for (i in lo until hi) {
            if (d(i) >= t) {
                if (a < 0) a = i
                else if (i - last - 1 > gap || paperSince) { raw += intArrayOf(a, last + 1); a = i }
                last = i; paperSince = false
            } else if (a >= 0 && isPaper(i)) paperSince = true
        }
        if (a >= 0) raw += intArrayOf(a, last + 1)
        // tramos separados por una franja que no es papel (zona pálida de la foto: calzada, pared clara) -> una sola foto;
        // en los extremos del grupo no quedan tramos cortos unidos por un puente (el pie de foto pegado a la foto)
        val groups = ArrayList<ArrayList<IntArray>>()
        for (r in raw) {
            val g = groups.lastOrNull()
            if (g != null && bridge(g.last()[1], r[0])) g += r else groups += arrayListOf(r)
        }
        val out = ArrayList<IntArray>()
        for (g in groups) {
            while (g.size > 1 && !runOk(g.last())) g.removeAt(g.size - 1)
            while (g.size > 1 && !runOk(g.first())) g.removeAt(0)
            out += trimLines(intArrayOf(g.first()[0], g.last()[1]), t, max(2, gap * 2 / 3), d)
        }
        return out.filter { it[1] - it[0] >= minLen }
    }

    /**
     * Quita de los extremos del tramo [r] las RAYAS finas (≤ [lineMax] líneas densas seguidas de un hueco): el marco del
     * panel o el filete del pie de foto, a unos píxeles de la foto, no son parte de ella.
     */
    internal fun trimLines(r: IntArray, t: Double, lineMax: Int, d: (Int) -> Double): IntArray {
        var a = r[0]; var b = r[1]
        while (true) {
            var k = a; while (k < b && d(k) >= t) k++
            if (k - a > lineMax || k >= b) break
            var j = k; while (j < b && d(j) < t) j++
            if (j >= b) break
            a = j
        }
        while (true) {
            var k = b - 1; while (k >= a && d(k) >= t) k--
            if (b - 1 - k > lineMax || k < a) break
            var j = k; while (j >= a && d(j) < t) j--
            if (j < a) break
            b = j + 1
        }
        return intArrayOf(a, b)
    }

    /**
     * Ajuste de la caja [b] (x0, y0, x1, y1 exclusivo, escala de análisis) por perfiles de filas y columnas: tramos de
     * foto; varios tramos = varias fotos. Dos pasadas (las columnas con las filas ya ajustadas y al revés).
     */
    private fun split(mp: Maps, b: IntArray, side: Int, depth: Int): List<IntArray> {
        val e = (0.01 * side).roundToInt()
        val gap = max(2, (GAP * side).roundToInt()); val minLen = max(4, (MIN_SIDE * side * 0.8).roundToInt())
        val out = ArrayList<IntArray>()
        val bw = b[2] - b[0]
        val xa = b[0] + (0.05 * bw).roundToInt(); val xb = b[2] - (0.05 * bw).roundToInt()
        val big = (BRIDGE_MAX * side).roundToInt(); val bridgeRun = (BRIDGE_RUN * side).roundToInt()
        // (franja entre dos tramos: corta, sin filas de papel y con algo de foto)
        val rowBridge = { a: Int, c: Int -> c - a <= big && (a until c).none { mp.rowP(it, xa, xb) >= BRIDGE_PAPER } && mp.sum(xa, a, xb, c).toDouble() / max(1, (xb - xa) * (c - a)) >= BRIDGE_PHOTO }
        if (depth == 0 && System.getenv("PBR_PROF") != null) log?.invoke("perfil filas ${b.toList()}: " + (max(0, b[1] - e) until min(mp.h, b[3] + e)).joinToString(" ") { "$it:%.2f/%.2f".format(mp.rowD(it, xa, xb), mp.rowP(it, xa, xb)) })
        for (ry in runs(max(0, b[1] - e), min(mp.h, b[3] + e), ROW_T, gap, minLen, { mp.rowP(it, xa, xb) >= BRIDGE_PAPER }, rowBridge, { r -> r[1] - r[0] >= bridgeRun }) { mp.rowD(it, xa, xb) }) {
            val rh = ry[1] - ry[0]
            val ya = ry[0] + (0.05 * rh).roundToInt(); val yb = ry[1] - (0.05 * rh).roundToInt()
            if (depth == 0 && System.getenv("PBR_PROF") != null) log?.invoke("perfil cols ${b.toList()}: " + (max(0, b[0] - e) until min(mp.w, b[2] + e)).joinToString(" ") { "$it:%.2f/%.2f".format(mp.colD(it, ya, yb), mp.colP(it, ya, yb)) })
            val colBridge = { a: Int, c: Int -> c - a <= big && (a until c).none { mp.colP(it, ya, yb) >= BRIDGE_PAPER } && mp.sum(a, ya, c, yb).toDouble() / max(1, (yb - ya) * (c - a)) >= BRIDGE_PHOTO }
            for (cx in runs(max(0, b[0] - e), min(mp.w, b[2] + e), ROW_T, gap, minLen, { mp.colP(it, ya, yb) >= BRIDGE_PAPER }, colBridge, { r -> r[1] - r[0] >= bridgeRun }) { mp.colD(it, ya, yb) }) {
                val nb = intArrayOf(cx[0], ry[0], cx[1], ry[1])
                // otra pasada con la caja nueva (las filas medidas sólo en las columnas de esta foto)
                if (depth < 2 && (nb[0] - b[0] > 2 || b[2] - nb[2] > 2 || nb[1] - b[1] > 2 || b[3] - nb[3] > 2)) out += split(mp, nb, side, depth + 1)
                else out += nb
            }
        }
        return out
    }

    /**
     * Prolonga cada lado de [b] por las líneas PÁLIDAS de la propia foto (calzada, pared, suelo claros: algo de grano y sin
     * ser papel liso) que los perfiles dejaron fuera; se para en la primera línea de papel o sin grano (el margen, el fondo
     * del pie de foto). Como mucho un [EXT_MAX] de la caja por lado. Dos pasadas.
     */
    private fun extend(mp: Maps, b0: IntArray): IntArray {
        val b = b0.clone()
        repeat(2) {
            val bw = b[2] - b[0]; val bh = b[3] - b[1]
            val xa = b[0] + (0.05 * bw).toInt(); val xb = b[2] - (0.05 * bw).toInt()
            val ya = b[1] + (0.05 * bh).toInt(); val yb = b[3] - (0.05 * bh).toInt()
            fun rowOk(y: Int) = y in 0 until mp.h && mp.grainSum(xa, y, xb, y + 1) >= EXT_D * (xb - xa) && mp.rowP(y, xa, xb) < EXT_PAPER
            fun colOk(x: Int) = x in 0 until mp.w && mp.grainSum(x, ya, x + 1, yb) >= EXT_D * (yb - ya) && mp.colP(x, ya, yb) < EXT_PAPER
            val my = (EXT_MAX * bh).toInt(); val mx = (EXT_MAX * bw).toInt()
            var k = 0; while (k < my && rowOk(b[1] - 1)) { b[1]--; k++ }
            k = 0; while (k < my && rowOk(b[3])) { b[3]++; k++ }
            k = 0; while (k < mx && colOk(b[0] - 1)) { b[0]--; k++ }
            k = 0; while (k < mx && colOk(b[2])) { b[2]++; k++ }
        }
        return b
    }

    /** Une las cajas que son la misma foto (IoU ≥ 0,5 o una dentro de otra en ≥ 80 %): se queda la mayor. */
    private fun dedupe(l: List<Pair<IntArray, Int>>): List<Pair<IntArray, Int>> {
        val s = l.sortedByDescending { (it.first[2] - it.first[0]).toLong() * (it.first[3] - it.first[1]) }
        val out = ArrayList<Pair<IntArray, Int>>()
        for ((b, src) in s) {
            val k = out.indexOfFirst { (o, _) -> PhotoMath.sameBox(o, b) }
            if (k >= 0) out[k] = out[k].first to (out[k].second or src) else out += b to src
        }
        return out
    }

    /**
     * Cada lado al borde REAL de la foto en la imagen a resolución completa [full]. Perfil de TEXTURA a lo largo del lado
     * (media de la diferencia entre vecinos EN la dirección del lado, en el 70 % central): alto dentro de la foto, bajo
     * en el papel, en el fondo liso del panel y en una raya del marco (uniforme a lo largo de ella). El borde es la línea
     * donde más sube la textura de fuera a dentro, a menos de [SNAP_R] px de análisis de la caja (o del 3 % de su lado).
     * Devuelve la caja en fracciones (x0, y0, x1, y1) de la página; sin escalón claro, el lado se queda donde estaba.
     */
    private fun snapFull(full: Mat, b: IntArray, w: Int, h: Int): DoubleArray = MatBag().use { bag ->
        val fw = full.cols(); val fh = full.rows()
        val sx = fw.toDouble() / w; val sy = fh.toDouble() / h
        val x0 = b[0] * sx; val y0 = b[1] * sy; val x1 = b[2] * sx; val y1 = b[3] * sy
        val keep = doubleArrayOf(b[0].toDouble() / w, b[1].toDouble() / h, b[2].toDouble() / w, b[3].toDouble() / h)
        val k = max(3, (1.5 * max(sx, sy)).roundToInt())
        val rx = max(SNAP_R * max(sx, sy), 0.03 * (x1 - x0)).roundToInt() + k
        val ry = max(SNAP_R * max(sx, sy), 0.03 * (y1 - y0)).roundToInt() + k
        val roi = Cv.clampRect(Rect((x0 - rx).toInt(), (y0 - ry).toInt(), (x1 - x0 + 2 * rx).roundToInt(), (y1 - y0 + 2 * ry).roundToInt()), fw, fh)
        if (roi.width < 8 * k || roi.height < 8 * k) return@use keep
        val g = bag.add(Cv.gray(bag.add(full.submat(roi))))
        val rw = roi.width; val rh = roi.height
        val gb = ByteArray(rw * rh); g.get(0, 0, gb)
        fun v(x: Int, y: Int) = gb[y * rw + x].toInt() and 0xFF
        // tramo central de la caja (en coordenadas del recorte)
        val bx0 = (x0 - roi.x).roundToInt(); val bx1 = (x1 - roi.x).roundToInt(); val by0 = (y0 - roi.y).roundToInt(); val by1 = (y1 - roi.y).roundToInt()
        val ca = (bx0 + 0.15 * (bx1 - bx0)).toInt().coerceIn(0, rw - 2); val cb = (bx1 - 0.15 * (bx1 - bx0)).toInt().coerceIn(ca + 1, rw - 1)
        val ra = (by0 + 0.15 * (by1 - by0)).toInt().coerceIn(0, rh - 2); val rb = (by1 - 0.15 * (by1 - by0)).toInt().coerceIn(ra + 1, rh - 1)
        // filas: textura a lo largo de la fila (diferencias horizontales); columnas: a lo largo de la columna (verticales)
        val rowT = DoubleArray(rh) { y -> var s = 0.0; for (x in ca until cb) s += abs(v(x + 1, y) - v(x, y)); s / max(1, cb - ca) }
        val colT = DoubleArray(rw) { x -> var s = 0.0; for (y in ra until rb) s += abs(v(x, y + 1) - v(x, y)); s / max(1, rb - ra) }
        val rowM = DoubleArray(rh) { y -> var s = 0.0; for (x in ca until cb) s += v(x, y); s / max(1, cb - ca) }
        val colM = DoubleArray(rw) { x -> var s = 0.0; for (y in ra until rb) s += v(x, y); s / max(1, rb - ra) }
        // escalón = salto de gris medio + 2 x salto de textura, con ventanas de ~3 px de análisis (una raya fina del marco
        // apenas mueve la media de la ventana; el borde de la foto, sí)
        val kk = max(6, (3 * max(sx, sy)).roundToInt())
        // nivel del papel en el recorte (percentil 90 del gris)
        val pLevel = Cv.percentile(Cv.histogram(g), 0.9).toDouble()
        fun edge(t: DoubleArray, m: DoubleArray, at: Int, r: Int, inside: Int): Int? {
            var best = -1; var bv = 0.0
            for (x in max(kk, at - r)..min(t.size - kk, at + r)) {
                var tIn = 0.0; var tOut = 0.0; var mIn = 0.0; var mOut = 0.0; var inMax = 0.0
                for (j in 0 until kk) {
                    val i = if (inside > 0) x + j else x - 1 - j; val o = if (inside > 0) x - 1 - j else x + j
                    tIn += t[i]; tOut += t[o]; mIn += m[i]; mOut += m[o]
                }
                // (lo más claro de una franja de dentro 3 veces más ancha: una raya o una banda estrecha no la llena)
                for (j in 0 until 3 * kk) {
                    val i = if (inside > 0) x + j else x - 1 - j
                    if (i < 0 || i >= m.size) break
                    inMax = max(inMax, m[i])
                }
                // (fuera tiene que ser papel: claro; una cabecera o una banda oscura de fuera no es el borde de la foto)
                // dentro: más textura (foto con grano) o todo él claramente más oscuro que el papel (foto lisa: pared, cielo
                // oscuro); una raya fina del marco no cumple ninguna de las dos (detrás de ella vuelve el papel)
                if (mOut / kk < pLevel - SNAP_PAPER || ((tIn - tOut) / kk < SNAP_TEX && inMax > pLevel - SNAP_DARK)) continue
                val st = abs(mIn - mOut) / kk + 2 * (tIn - tOut) / kk
                if (st > bv) { bv = st; best = x }
            }
            return if (best >= 0 && bv >= SNAP_STEP) best else null
        }
        val ex0 = edge(colT, colM, bx0, rx, 1); val ex1 = edge(colT, colM, bx1, rx, -1)
        val ey0 = edge(rowT, rowM, by0, ry, 1); val ey1 = edge(rowT, rowM, by1, ry, -1)
        val out = doubleArrayOf(ex0?.let { (roi.x + it).toDouble() / fw } ?: keep[0], ey0?.let { (roi.y + it).toDouble() / fh } ?: keep[1],
            ex1?.let { (roi.x + it).toDouble() / fw } ?: keep[2], ey1?.let { (roi.y + it).toDouble() / fh } ?: keep[3])
        if (out[2] - out[0] < 0.5 * (keep[2] - keep[0]) || out[3] - out[1] < 0.5 * (keep[3] - keep[1])) keep else out
    }

    /** Medidas del interior (sin un 4 % de borde) de [b]. */
    internal fun metrics(mp: Maps, b: IntArray): Metrics {
        val bw = b[2] - b[0]; val bh = b[3] - b[1]
        val x0 = b[0] + (0.04 * bw).toInt(); val x1 = b[2] - (0.04 * bw).toInt()
        val y0 = b[1] + (0.04 * bh).toInt(); val y1 = b[3] - (0.04 * bh).toInt()
        var n = 0; var ph = 0; var pa = 0; var gr = 0; var fl = 0; var mid = 0
        val hist = IntArray(216)
        for (y in max(0, y0) until min(mp.h, y1)) for (x in max(0, x0) until min(mp.w, x1)) {
            val i = y * mp.w + x; n++
            ph += mp.ph[i]; pa += mp.paper[i]; gr += mp.grain[i]
            if (mp.ph[i].toInt() == 1 && mp.grain[i].toInt() == 0) fl++
            val v = mp.g[i].toInt() and 0xFF
            if (v > 0.15 * mp.pm && v < 0.9 * mp.pm) mid++
            val r = (mp.rgb[i * 3].toInt() and 0xFF) * 6 / 256; val g = (mp.rgb[i * 3 + 1].toInt() and 0xFF) * 6 / 256; val bl = (mp.rgb[i * 3 + 2].toInt() and 0xFF) * 6 / 256
            hist[(r * 6 + g) * 6 + bl]++
        }
        if (n == 0) return Metrics(0.0, 1.0, 0.0, 0.0, 0, 0.0)
        val colors = hist.count { it >= 0.004 * n }
        val top = hist.sortedDescending()
        return Metrics(ph.toDouble() / n, pa.toDouble() / n, gr.toDouble() / n, fl.toDouble() / n, colors, mid.toDouble() / n, (top[0] + top[1]).toDouble() / n)
    }

    private fun iou(a: FloatArray, b: FloatArray): Double {
        val iw = min(a[2], b[2]) - max(a[0], b[0]); val ih = min(a[3], b[3]) - max(a[1], b[1])
        if (iw <= 0 || ih <= 0) return 0.0
        val i = iw.toDouble() * ih
        return i / ((a[2] - a[0]).toDouble() * (a[3] - a[1]) + (b[2] - b[0]).toDouble() * (b[3] - b[1]) - i)
    }
}

/** Reglas puras (testeables sin OpenCV) de [PhotoBoxRefine]. */
object PhotoMath {
    /** Croma (máx - mín de RGB) a partir del cual un píxel tiene color. */
    const val CHROMA_T = 28

    enum class Kind { PHOTO, DOUBTFUL, NOT }

    /**
     * ¿Fotografía de cámara? Una foto tiene tono medio o color en casi toda la caja, casi nada de papel liso, grano en buena
     * parte y muchos colores. Una firma o un sello (trazos sobre papel): mucho papel. Un logo o una cabecera: casi todo en
     * dos colores planos y pocos colores. (Umbrales: 13 fotos reales juliaca2 + banco de fotos de Jev, notes_fotos_exactas.md.)
     */
    fun classify(m: PhotoBoxRefine.Metrics): Kind = when {
        m.paper > 0.25 || m.photo < 0.6 || m.colors < 6 || m.grain < 0.2 -> Kind.NOT
        // logo, cabecera, banda de color
        m.top2 >= 0.6 && m.grain < 0.45 && m.colors <= 18 -> Kind.NOT
        // foto con grano en casi todo, o con zonas lisas (cielo, pared) pero de muchos tonos
        m.photo >= 0.8 && m.paper <= 0.2 && m.colors >= 8 && (m.grain >= 0.6 || (m.grain >= 0.28 && m.top2 <= 0.6)) -> Kind.PHOTO
        else -> Kind.DOUBTFUL
    }

    /** ¿Se tocan los rectángulos (intersección no vacía)? */
    fun overlaps(a: Rect, b: Rect): Boolean =
        min(a.x + a.width, b.x + b.width) > max(a.x, b.x) && min(a.y + a.height, b.y + b.height) > max(a.y, b.y)

    /** ¿La misma foto? IoU ≥ 0,5 o una dentro de la otra en ≥ 80 %. */
    fun sameBox(a: IntArray, b: IntArray): Boolean {
        val iw = min(a[2], b[2]) - max(a[0], b[0]); val ih = min(a[3], b[3]) - max(a[1], b[1])
        if (iw <= 0 || ih <= 0) return false
        val i = iw.toDouble() * ih
        val aa = (a[2] - a[0]).toDouble() * (a[3] - a[1]); val ab = (b[2] - b[0]).toDouble() * (b[3] - b[1])
        return i / (aa + ab - i) >= 0.5 || i >= 0.8 * min(aa, ab)
    }
}
