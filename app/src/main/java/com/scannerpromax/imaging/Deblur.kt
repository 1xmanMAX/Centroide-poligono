package com.scannerpromax.imaging

import com.scannerpromax.imaging.DeblurMath.Cov
import com.scannerpromax.imaging.DeblurMath.eig
import com.scannerpromax.imaging.DeblurMath.mapEig
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Restauración de nitidez POR ZONAS tras la rectificación (zonas alejadas de la cámara en perspectiva fuerte).
 *
 * Causas tratadas:
 *  1. ESCALA LOCAL: al rectificar, la zona lejana se AMPLÍA (pocos píxeles de la foto para muchos de la salida) y su
 *     desenfoque nativo crece con el jacobiano de la homografía: Σ_geo = σ² J Jᵀ (exacto, conocido por la geometría;
 *     anisótropo: la dirección escorzada se amplía más). Donde la salida se reduce no se hace nada.
 *  2. DESENFOQUE MEDIDO (profundidad de campo, movimiento corto): por teselas, en los máximos locales del gradiente
 *     ("bordes" de los trazos) se mide σ con el cociente |∇I| / |∇(G_2 * I)| por dirección de la normal (0/45/90/135°)
 *     y se ajusta una covarianza anisótropa (el movimiento alarga el desenfoque en su dirección). Lo que la geometría no
 *     explica se suaviza con una superficie cuadrática robusta (varía suavemente sobre la hoja) y se toma RELATIVO a la
 *     zona más nítida de la propia página: los sesgos del estimador (trazos finos, realce del teléfono) se cancelan.
 *  3. DECONVOLUCIÓN de Wiener (gaussiana anisótropa: sin ceros en su espectro, estable) con pocas clases de núcleo
 *     (k-medias) mezcladas suavemente entre teselas; ganancia limitada y nada por encima del Nyquist del origen.
 *  4. SIN HALOS NI RUIDO: la corrección sólo se aplica cerca de bordes reales (el grano del papel no se amplifica) y se
 *     limita al mínimo/máximo local de la imagen (no puede crear contornos claros ni "fantasmas" oscuros).
 *
 * Sólo la luminancia (el mismo ΔY en los tres canales: el color de la tinta se conserva). Por franjas: memoria acotada.
 * Si la confianza es baja o nada lo necesita, no se toca la imagen.
 */
internal object Deblur {

    /** Parámetros (expuestos para el banco y los tests). */
    data class Params(
        /** Desenfoque nativo de la foto a escala 1:1 (px): lo que la ampliación de la zona lejana multiplica. */
        val sigmaSrc: Double = 0.6,
        /** Peso del exceso medido (profundidad de campo, movimiento) sobre el geométrico. */
        val measured: Double = 1.0,
        /** Exceso medido máximo (σ, px). */
        val measuredCap: Double = 2.0,
        /** σ máxima deconvolucionada (px). */
        val maxSigma: Double = 3.0,
        /** σ mínima para deconvolucionar una tesela (px). */
        val minSigma: Double = 0.45,
        /** Regularización de Wiener (ruido/señal). */
        val k: Double = 0.015,
        val gainMax: Double = 4.0,
        val classes: Int = 2,
        /** Margen de la limitación anti-halo (fracción del rango local) hacia oscuro / claro. */
        val clampDark: Double = 0.0,
        val clampBright: Double = 0.0,
        val tile: Int = 256,
    )

    /** Resultado (para el banco/registro). */
    data class Report(val applied: Boolean, val classes: List<Cov>, val maxSigma: Double, val ms: Long)

    /** Registro opcional (banco/depuración). */
    @Volatile internal var log: ((String) -> Unit)? = null

    private const val SB = 2.0

    /**
     * Restaura [rgb] (8UC3, in situ). [hinv] = homografía salida -> foto (3x3 fila mayor) de la rectificación, o null.
     * [done] = fracción ya corregida por [Deghost] en el píxel (x, y): ahí el núcleo se reduce en esa proporción (el
     * núcleo estimado por Deghost ya incluye el desenfoque geométrico y el medido). Devuelve el informe.
     */
    fun restore(rgb: Mat, hinv: DoubleArray?, p: Params = Params(), done: ((Double, Double) -> Double)? = null): Report {
        val t0 = System.nanoTime()
        val w = rgb.cols(); val h = rgb.rows()
        val none = { Report(false, emptyList(), 0.0, (System.nanoTime() - t0) / 1_000_000) }
        if (rgb.channels() != 3 || w < 3 * p.tile || h < 3 * p.tile) return none()
        val nty = max(1, (h.toDouble() / p.tile).roundToInt()); val ntx = max(1, (w.toDouble() / p.tile).roundToInt())
        val th = h.toDouble() / nty; val tw = w.toDouble() / ntx
        MatBag().use { bag ->
            val y8 = bag.add(Cv.gray(rgb))
            val ns = noiseSigma(y8, bag)
            // 1) Geometría por tesela
            val geo = Array(nty) { arrayOfNulls<Cov>(ntx) }
            val stretch = Array(nty) { DoubleArray(ntx) { 1.0 } }
            val areaScale = DoubleArray(nty * ntx) { 1.0 }
            for (ty in 0 until nty) for (tx in 0 until ntx) {
                val cx = (tx + 0.5) * tw; val cy = (ty + 0.5) * th
                val j = if (hinv != null) DeblurMath.jacobianOutFromSrc(hinv, cx, cy) else doubleArrayOf(1.0, 0.0, 0.0, 1.0)
                geo[ty][tx] = DeblurMath.geometricCov(j, p.sigmaSrc)
                stretch[ty][tx] = DeblurMath.maxStretch(j)
                areaScale[ty * ntx + tx] = sqrt(kotlin.math.abs(j[0] * j[3] - j[1] * j[2]))
            }
            // 2) Desenfoque medido, RELATIVO a la zona más nítida y AUTOCALIBRADO
            // Sólo con perspectiva apreciable: las zonas sólo pueden diferir en desenfoque si están a distinta distancia
            // de la cámara (en una captura de pantalla o un escaneo frontal las diferencias medidas son del contenido)
            val persp = DeblurMath.perspectiveWeight(areaScale)
            val extra = if (p.measured > 0 && persp > 0) measuredExcess(y8, nty, ntx, th, tw, ns, p.copy(measured = p.measured * persp)) else null
            // 3) Núcleo a deconvolucionar por tesela: el exceso medido o, si es menor (o no hay medida), el geométrico
            //    (desenfoque nativo ampliado por la rectificación menos el nativo a 1:1)
            val t2 = p.sigmaSrc * p.sigmaSrc
            val maxV = p.maxSigma * p.maxSigma
            val dcov = Array(nty) { ty -> Array(ntx) { tx ->
                val g = geo[ty][tx]!!.mapEig { (it - t2).coerceAtLeast(0.0) }
                val x = extra?.get(ty)?.get(tx)
                val pick = if (x != null && x.meanVar >= g.meanVar) x else g
                val keep = if (done != null) 1.0 - done((tx + 0.5) * tw, (ty + 0.5) * th).coerceIn(0.0, 1.0) else 1.0
                pick.mapEig { it.coerceIn(0.0, maxV) } * keep
            } }
            val needIdx = ArrayList<Int>(); val needPts = ArrayList<Cov>()
            for (ty in 0 until nty) for (tx in 0 until ntx) {
                if (dcov[ty][tx].meanVar > p.minSigma * p.minSigma) { needIdx.add(ty * ntx + tx); needPts.add(dcov[ty][tx]) }
            }
            if (needPts.isEmpty()) return none()
            val (lab0, centers0) = DeblurMath.kMeans(needPts, p.classes)
            // Clases cuyo núcleo medio es casi nulo: identidad (no compensan su coste ni se notarían)
            val keep = centers0.map { it.meanVar >= p.minSigma * p.minSigma }
            if (keep.none { it }) return none()
            val remap = IntArray(centers0.size) { -1 }; var nk = 0
            for (c in centers0.indices) if (keep[c]) remap[c] = nk++
            val centers = centers0.filterIndexed { c, _ -> keep[c] }
            val lab = IntArray(lab0.size) { remap[lab0[it]] }
            val cls = IntArray(nty * ntx) { -1 }
            for (i in needIdx.indices) cls[needIdx[i]] = lab[i]
            // Frecuencia útil por clase: Nyquist del origen en la tesela más ampliada de la clase
            val fCut = DoubleArray(centers.size) { 0.5 }
            for (i in needIdx.indices) {
                if (lab[i] < 0) continue
                val ty = needIdx[i] / ntx; val tx = needIdx[i] % ntx
                fCut[lab[i]] = min(fCut[lab[i]], 0.5 / max(1.0, stretch[ty][tx]))
            }
            val sMax = sqrt(centers.maxOf { it.maxVar })
            deconvolve(rgb, y8, cls, nty, ntx, th, tw, centers, fCut, ns, p, bag)
            val rep = Report(true, centers, sMax, (System.nanoTime() - t0) / 1_000_000)
            log?.invoke("deblur ${w}x$h tiles=${ntx}x$nty need=${needIdx.size} classes=" +
                centers.joinToString(";") { "%.2f/%.2f/%.2f".format(java.util.Locale.ROOT, it.a, it.b, it.c) } + " ns=%.2f persp=%.2f ms=${rep.ms}".format(java.util.Locale.ROOT, ns, persp))
            return rep
        }
    }

    /** σ del ruido de la luminancia [y8] (8U) ([noiseSigma]). */
    internal fun noiseSigmaOf(y8: Mat): Double = MatBag().use { noiseSigma(y8, it) }

    /** σ del ruido de la luminancia (MAD del laplaciano en una ventana central <= 1024²). */
    private fun noiseSigma(y8: Mat, bag: MatBag): Double {
        val w = y8.cols(); val h = y8.rows()
        val cw = min(w, 1024); val ch = min(h, 1024)
        val roi = y8.submat((h - ch) / 2, (h - ch) / 2 + ch, (w - cw) / 2, (w - cw) / 2 + cw)
        val lap = bag.mat(); Imgproc.Laplacian(roi, lap, CvType.CV_32F, 3); roi.release()
        Core.absdiff(lap, Scalar(0.0), lap)
        val a = FloatArray(cw * ch); lap.get(0, 0, a)
        // Histograma con paso 0.5 hasta 256
        val hist = IntArray(512)
        for (v in a) hist[min(511, (v * 2).toInt())]++
        fun pct(hh: IntArray, total: Int, q: Double): Double {
            var acc = 0; val target = q * total
            for (i in hh.indices) { acc += hh[i]; if (acc >= target) return i / 2.0 }
            return 255.0
        }
        val p50 = pct(hist, a.size, 0.5)
        val lim = min(511, (p50 * 3 * 2).toInt())
        var n = 0
        for (i in 0..lim) n += hist[i]
        val sub = IntArray(lim + 1) { hist[it] }
        val med = pct(sub, n, 0.5)
        return max(0.5, med / 0.6745 / sqrt(20.0))
    }

    /** Desenfoque añadido (σ, px) de la curva de autocalibración. */
    private val CAL_SIGMAS = doubleArrayOf(0.0, 0.75, 1.5, 2.25, 3.0)

    /** Estadística de una tesela: mediana de la σ de borde por dirección de la normal y número de bordes. */
    private class TileStat(val med: DoubleArray, val cnt: IntArray) {
        val total get() = cnt.sum()
        fun iso(): Double { var s = 0.0; var n = 0; for (k in 0 until 4) if (cnt[k] >= MIN_DIR) { s += med[k]; n++ }; return if (n > 0) s / n else Double.NaN }
    }

    private const val MIN_DIR = 12
    private const val MIN_EDGES = 60
    private const val TILE_MARGIN = 16

    /**
     * Exceso de desenfoque de cada tesela respecto de la zona más nítida de la página (null = sin medida fiable).
     *
     * El estimador por bordes (cociente de gradientes) depende del grosor de los trazos y del realce del teléfono, así
     * que no se usa su valor absoluto: se AUTOCALIBRA con la propia página. Las teselas más nítidas se desenfocan con
     * σ conocidas y se mide la curva estadística(σ) por dirección; cada tesela se invierte por esa curva (mismos trazos,
     * misma tinta, mismo procesado del móvil: los sesgos se cancelan). Las σ relativas por dirección dan una covarianza
     * (anisótropa con movimiento), que se suaviza con una superficie cuadrática robusta y se limita.
     */
    private fun measuredExcess(y8: Mat, nty: Int, ntx: Int, th: Double, tw: Double, ns: Double, p: Params): Array<Array<Cov>>? = MatBag().use { bag ->
        val w = y8.cols(); val h = y8.rows()
        val buf = StatBuffers((ceil(th) + 2 * TILE_MARGIN + 2).toInt() * (ceil(tw) + 2 * TILE_MARGIN + 2).toInt())
        val tileF = bag.mat(); val blurred = bag.mat()
        fun load(ty: Int, tx: Int): Pair<Mat, IntArray> {
            val r0 = (ty * th).roundToInt(); val r1 = min(h, ((ty + 1) * th).roundToInt())
            val c0 = (tx * tw).roundToInt(); val c1 = min(w, ((tx + 1) * tw).roundToInt())
            val a0 = max(0, r0 - TILE_MARGIN); val a1 = min(h, r1 + TILE_MARGIN)
            val b0 = max(0, c0 - TILE_MARGIN); val b1 = min(w, c1 + TILE_MARGIN)
            val roi = y8.submat(a0, a1, b0, b1)
            roi.convertTo(tileF, CvType.CV_32F); roi.release()
            // Región interior (sin margen) en coordenadas de la tesela cargada
            return tileF to intArrayOf(r0 - a0, r1 - a0, c0 - b0, c1 - b0)
        }
        val stats = Array(nty) { arrayOfNulls<TileStat>(ntx) }
        // Con muchas teselas basta la mitad (tablero de ajedrez): el campo se ajusta con una superficie suave
        val sparse = nty * ntx > 48
        for (ty in 0 until nty) for (tx in 0 until ntx) {
            if (sparse && (ty + tx) % 2 == 1) continue
            val (m, inner) = load(ty, tx)
            val st = tileStat(m, inner, ns, buf, bag)
            if (st.total >= MIN_EDGES && st.iso().isFinite()) stats[ty][tx] = st
        }
        val okIdx = (0 until nty * ntx).filter { stats[it / ntx][it % ntx] != null }
        if (okIdx.size < 6) return@use null
        // Referencias: las teselas más nítidas
        val nRef = min(12, max(3, okIdx.size / 5))
        val refs = okIdx.sortedBy { stats[it / ntx][it % ntx]!!.iso() }.take(nRef)
        // Curva estadística(σ añadida) por dirección (mediana sobre las referencias), monótona
        val curve = Array(CAL_SIGMAS.size) { DoubleArray(4) }
        for ((si, s) in CAL_SIGMAS.withIndex()) {
            val vals = Array(4) { ArrayList<Double>() }
            for (i in refs) {
                val st = if (s == 0.0) stats[i / ntx][i % ntx]!! else {
                    val (m, inner) = load(i / ntx, i % ntx)
                    Imgproc.GaussianBlur(m, blurred, Size(0.0, 0.0), s)
                    tileStat(blurred, inner, ns, buf, bag)
                }
                for (k in 0 until 4) if (st.cnt[k] >= MIN_DIR) vals[k].add(st.med[k])
            }
            for (k in 0 until 4) curve[si][k] = if (vals[k].isEmpty()) Double.NaN else DeblurMath.median(vals[k].toDoubleArray())
        }
        for (k in 0 until 4) {
            for (si in CAL_SIGMAS.indices) {
                if (!curve[si][k].isFinite()) curve[si][k] = if (si > 0) curve[si - 1][k] else 0.0
                if (si > 0) curve[si][k] = max(curve[si][k], curve[si - 1][k])
            }
        }
        // σ relativa por dirección -> covarianza por tesela
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val wts = ArrayList<Double>()
        val va = ArrayList<Double>(); val vb = ArrayList<Double>(); val vc = ArrayList<Double>()
        for (i in okIdx) {
            val st = stats[i / ntx][i % ntx]!!
            val sig = DoubleArray(4) { Double.NaN }; val cnt = DoubleArray(4)
            for (k in 0 until 4) {
                if (st.cnt[k] < MIN_DIR || curve[CAL_SIGMAS.size - 1][k] <= curve[0][k] + 1e-6) continue
                sig[k] = DeblurMath.invertCurve(st.med[k], DoubleArray(CAL_SIGMAS.size) { curve[it][k] }, CAL_SIGMAS)
                cnt[k] = st.cnt[k].toDouble()
            }
            val c = DeblurMath.fitCov(sig, cnt) ?: continue
            xs.add((i % ntx + 0.5) / ntx - 0.5); ys.add((i / ntx + 0.5) / nty - 0.5); wts.add(cnt.sum())
            va.add(c.a); vb.add(c.b); vc.add(c.c)
        }
        if (xs.size < 6) return@use null
        val xa = xs.toDoubleArray(); val ya = ys.toDoubleArray(); val wa = wts.toDoubleArray()
        val ca = DeblurMath.quadFit(xa, ya, va.toDoubleArray(), wa) ?: return@use null
        val cb = DeblurMath.quadFit(xa, ya, vb.toDoubleArray(), wa) ?: return@use null
        val cc = DeblurMath.quadFit(xa, ya, vc.toDoubleArray(), wa) ?: return@use null
        val cap = p.measuredCap * p.measuredCap
        Array(nty) { ty -> Array(ntx) { tx ->
            val x = (tx + 0.5) / ntx - 0.5; val y = (ty + 0.5) / nty - 0.5
            Cov(DeblurMath.evalQuad(ca, x, y), DeblurMath.evalQuad(cb, x, y), DeblurMath.evalQuad(cc, x, y)).mapEig { it.coerceIn(0.0, cap) } * p.measured
        } }
    }

    private class StatBuffers(n: Int) {
        val m = FloatArray(n); val b = FloatArray(n); val d = FloatArray(n); val a = FloatArray(n)
        val sig = Array(4) { FloatArray(n) }
    }

    /**
     * Estadística de bordes de la región [inner] (r0, r1, c0, c1) de la tesela [yf] (32F, con margen): en los máximos
     * locales del gradiente por encima del ruido, σ = 2 / √(R² - 1) con R = |∇I| / |∇(G_2 * I)|, por sector de la normal.
     */
    private fun tileStat(yf: Mat, inner: IntArray, ns: Double, buf: StatBuffers, bag: MatBag): TileStat {
        val gx = bag.mat(); val gy = bag.mat(); val yb = bag.mat(); val bx = bag.mat(); val by = bag.mat()
        val mag = bag.mat(); val magB = bag.mat(); val dil = bag.mat(); val ang = bag.mat()
        Imgproc.Sobel(yf, gx, CvType.CV_32F, 1, 0, 3, 1.0 / 8, 0.0)
        Imgproc.Sobel(yf, gy, CvType.CV_32F, 0, 1, 3, 1.0 / 8, 0.0)
        Imgproc.GaussianBlur(yf, yb, Size(0.0, 0.0), SB)
        Imgproc.Sobel(yb, bx, CvType.CV_32F, 1, 0, 3, 1.0 / 8, 0.0)
        Imgproc.Sobel(yb, by, CvType.CV_32F, 0, 1, 3, 1.0 / 8, 0.0)
        Core.magnitude(gx, gy, mag); Core.magnitude(bx, by, magB)
        Imgproc.dilate(mag, dil, Cv.kernel(Imgproc.MORPH_RECT, 3))
        Core.phase(gx, gy, ang, true)
        val tw = yf.cols()
        mag.get(0, 0, buf.m); magB.get(0, 0, buf.b); dil.get(0, 0, buf.d); ang.get(0, 0, buf.a)
        for (mm in listOf(gx, gy, yb, bx, by, mag, magB, dil, ang)) mm.release()
        val (r0, r1, c0, c1) = inner.toList()
        // Percentil 99.5 del gradiente de la región (histograma de paso 0.25 hasta 128)
        val hist = IntArray(513); var cntAll = 0
        for (y in r0 until r1) { val base = y * tw; for (x in c0 until c1) { hist[min(512, (buf.m[base + x] * 4).toInt())]++; cntAll++ } }
        var acc = 0; var p995 = 128.0
        val target = 0.995 * cntAll
        for (i in hist.indices) { acc += hist[i]; if (acc >= target) { p995 = i / 4.0; break } }
        val thr = max(max(1.875 * ns, 0.35 * p995), 3.0).toFloat()
        val cnt = IntArray(4)
        for (y in r0 until r1) {
            val base = y * tw
            for (x in c0 until c1) {
                val i = base + x
                val m = buf.m[i]
                if (m <= thr || m < buf.d[i]) continue
                val r = m / max(buf.b[i], 1e-3f)
                var a = buf.a[i]; if (a >= 180f) a -= 180f
                val k = ((a + 22.5f) / 45f).toInt() and 3
                buf.sig[k][cnt[k]++] = DeblurMath.sigmaFromRatio(r.toDouble(), SB).toFloat()
            }
        }
        return TileStat(DoubleArray(4) { k -> if (cnt[k] >= MIN_DIR) DeblurMath.median(buf.sig[k], cnt[k]) else Double.NaN }, cnt)
    }

    /**
     * Wiener por clases y por franjas: una transformada directa por franja y una inversa por clase presente; mezcla con
     * los pesos de las teselas interpolados (bilineal); compuerta de bordes y limitación anti-halo; ΔY a los tres canales.
     */
    private fun deconvolve(
        rgb: Mat, y8: Mat, cls: IntArray, nty: Int, ntx: Int, th: Double, tw: Double,
        centers: List<Cov>, fCut: DoubleArray, ns: Double, p: Params, bag: MatBag,
    ) {
        val w = rgb.cols(); val h = rgb.rows()
        val sMax = sqrt(centers.maxOf { it.maxVar })
        val pad = (ceil(4 * sMax) + 8).toInt()
        val bandH = max(64, 900_000 / max(1, w))
        // Mapas de pesos por clase en la rejilla de teselas
        val nCls = centers.size
        val wMaps = Array(nCls) { c ->
            val m = bag.add(Mat(nty, ntx, CvType.CV_32F))
            m.put(0, 0, FloatArray(nty * ntx) { if (cls[it] == c) 1f else 0f }); m
        }
        // Ventana de la limitación anti-halo: media σ (más ancha dejaría pasar los halos entre extremos lejanos)
        val rr = 2 * ceil(0.5 * sMax).toInt() + 1
        val kClamp = Cv.kernel(Imgproc.MORPH_RECT, rr)
        val k3 = Cv.kernel(Imgproc.MORPH_RECT, 3)
        val gainCache = HashMap<Long, Mat>()
        val yIn = bag.mat(); val yf = bag.mat(); val spec = bag.mat(); val prod = bag.mat(); val inv = bag.mat()
        val acc = bag.mat(); val wb = bag.mat(); val tmp = bag.mat(); val gate = bag.mat(); val lo = bag.mat(); val hi = bag.mat()
        val gxm = bag.mat(); val gym = bag.mat(); val ys = bag.mat(); val f3 = bag.mat(); val d3 = bag.mat()
        val t1 = max(1.5 * ns, 1.5); val t2 = 3 * t1
        try {
            // Franjas de altura IGUAL (la última se solapa con la anterior); en cada franja sólo las columnas de las
            // teselas que necesitan deconvolución (con una tesela de margen por la mezcla)
            var next = 0
            while (next < h) {
                val b1 = min(h, next + bandH)
                val b0 = max(0, b1 - bandH)
                val wFrom = next - b0          // primera fila de la franja aún no escrita
                next = b1
                val ty0 = max(0, (b0 / th).toInt() - 1); val ty1 = min(nty - 1, ((b1 - 1) / th).toInt() + 1)
                val present = BooleanArray(nCls)
                var tx0 = ntx; var tx1 = -1
                for (ty in ty0..ty1) for (tx in 0 until ntx) {
                    val c = cls[ty * ntx + tx]
                    if (c >= 0) { present[c] = true; tx0 = min(tx0, tx); tx1 = max(tx1, tx) }
                }
                if (tx1 < 0) continue
                val c0 = max(0, ((tx0 - 1) * tw).toInt()); val c1 = min(w, ((tx1 + 2) * tw).toInt() + 1)
                val bw = c1 - c0; val bh = b1 - b0
                // Luminancia con margen (la de la propia imagen donde la hay; reflejada en los bordes)
                val a0 = max(0, b0 - pad); val a1 = min(h, b1 + pad)
                val l0 = max(0, c0 - pad); val l1 = min(w, c1 + pad)
                val roi = y8.submat(a0, a1, l0, l1)
                Core.copyMakeBorder(roi, yIn, pad - (b0 - a0), pad - (a1 - b1), pad - (c0 - l0), pad - (l1 - c1), Core.BORDER_REFLECT)
                roi.release()
                val hh = yIn.rows(); val ww = yIn.cols()
                val dh = Core.getOptimalDFTSize(hh); val dw = Core.getOptimalDFTSize(ww)
                Core.copyMakeBorder(yIn, tmp, 0, dh - hh, 0, dw - ww, Core.BORDER_REFLECT)
                tmp.convertTo(yf, CvType.CV_32F)
                Core.dft(yf, spec)   // formato CCS empaquetado (real): la mitad de datos
                val yBand = yf.submat(pad, pad + bh, pad, pad + bw)
                acc.create(bh, bw, CvType.CV_32F); acc.setTo(Scalar(0.0))   // Σ w_c·(Yd_c - Y) = Δ
                for (c in 0 until nCls) {
                    if (!present[c]) continue
                    val key = (c.toLong() shl 40) or (dh.toLong() shl 20) or dw.toLong()
                    // Sólo se guardan las ganancias del tamaño de transformada actual (memoria acotada)
                    if (gainCache.isNotEmpty() && (gainCache.keys.first() and 0xFFFFFFFFFFL) != (key and 0xFFFFFFFFFFL)) {
                        for (gm in gainCache.values) gm.release()
                        gainCache.clear()
                    }
                    val g = gainCache.getOrPut(key) { gainMat(centers[c], dh, dw, p.k, fCut[c], p.gainMax) }
                    Core.multiply(spec, g, prod)
                    Core.idft(prod, inv, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
                    val dRoi = inv.submat(pad, pad + bh, pad, pad + bw)
                    bandWeights(wMaps[c], b0, bh, c0, bw, th, tw, wb)
                    Core.subtract(dRoi, yBand, tmp)
                    Core.multiply(tmp, wb, tmp); Core.add(acc, tmp, acc)
                    dRoi.release()
                }
                // Compuerta de bordes (con margen para que los filtros no vean el corte), a media resolución: es un mapa
                // suave (gradiente por píxel completo = 1/16 del Sobel a media)
                val e8 = min(pad, 8)
                val ext = yf.submat(pad - e8, pad + bh + e8, pad - e8, pad + bw + e8)
                Imgproc.resize(ext, ys, Size(max(1.0, ext.cols() / 2.0), max(1.0, ext.rows() / 2.0)), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.Sobel(ys, gxm, CvType.CV_32F, 1, 0, 3, 1.0 / 16, 0.0)
                Imgproc.Sobel(ys, gym, CvType.CV_32F, 0, 1, 3, 1.0 / 16, 0.0)
                Core.magnitude(gxm, gym, gxm)
                Core.subtract(gxm, Scalar(t1), gxm); Core.multiply(gxm, Scalar(1.0 / (t2 - t1)), gxm)
                Core.min(gxm, Scalar(1.0), gxm); Core.max(gxm, Scalar(0.0), gxm)
                Imgproc.dilate(gxm, gxm, k3)
                Imgproc.GaussianBlur(gxm, gxm, Size(0.0, 0.0), 0.75)
                Imgproc.resize(gxm, gate, ext.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                val gRoi = gate.submat(e8, e8 + bh, e8, e8 + bw)
                Core.multiply(acc, gRoi, acc); gRoi.release()
                // Anti-halo: Y + Δ dentro de [mín local - m·rango, máx local + m·rango]
                Imgproc.erode(ext, lo, kClamp); Imgproc.dilate(ext, hi, kClamp)
                val loR = lo.submat(e8, e8 + bh, e8, e8 + bw); val hiR = hi.submat(e8, e8 + bh, e8, e8 + bw)
                Core.add(acc, yBand, acc)                                   // Y restaurada
                if (p.clampDark > 0 || p.clampBright > 0) {
                    val rng = bag.mat(); Core.subtract(hiR, loR, rng)
                    Core.scaleAdd(rng, -p.clampDark, loR, tmp); Core.max(acc, tmp, acc)
                    Core.scaleAdd(rng, p.clampBright, hiR, tmp); Core.min(acc, tmp, acc)
                    rng.release()
                } else {
                    Core.max(acc, loR, acc); Core.min(acc, hiR, acc)
                }
                Core.subtract(acc, yBand, acc)                              // Δ final
                loR.release(); hiR.release(); ext.release()
                // ΔY a los tres canales
                val rgbBand = rgb.submat(b0 + wFrom, b1, c0, c1)
                val dNew = acc.submat(wFrom, bh, 0, bw)
                rgbBand.convertTo(f3, CvType.CV_32FC3)
                Core.merge(listOf(dNew, dNew, dNew), d3)
                Core.add(f3, d3, f3)
                f3.convertTo(rgbBand, CvType.CV_8UC3)
                rgbBand.release(); yBand.release(); dNew.release()
            }
        } finally {
            for (g in gainCache.values) g.release()
        }
    }

    /** Pesos de las filas [b0, b0+bh) y columnas [c0, c0+bw) interpolando bilinealmente la rejilla de teselas [grid] (32F). */
    private fun bandWeights(grid: Mat, b0: Int, bh: Int, c0: Int, bw: Int, th: Double, tw: Double, dst: Mat) {
        // A 1/4 de resolución (el mapa es suave a escala de tesela) y ampliado: la interpolación por píxel es lo caro
        val q = 4.0
        val sw = max(2, ceil(bw / q).toInt()); val sh = max(2, ceil(bh / q).toInt())
        val m = Mat(2, 3, CvType.CV_64F)
        // píxel reducido (i, j) -> centro en el original (c0 + (i+0.5)q - 0.5, b0 + (j+0.5)q - 0.5) -> rejilla de teselas
        m.put(0, 0, q / tw, 0.0, (c0 + 0.5 * q) / tw - 0.5, 0.0, q / th, (b0 + 0.5 * q) / th - 0.5)
        val small = Mat()
        Imgproc.warpAffine(grid, small, m, Size(sw.toDouble(), sh.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
        m.release()
        val big = Mat()
        Imgproc.resize(small, big, Size(sw * q, sh * q), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        val roi = big.submat(0, bh, 0, bw); roi.copyTo(dst); roi.release(); big.release()
    }

    /**
     * Ganancia de Wiener ([DeblurMath.wienerGain]) para un espectro en formato CCS empaquetado de [Core.dft] (real,
     * [dh] x [dw]): cada elemento (parte real o imaginaria de una frecuencia) se multiplica por la ganancia REAL de su
     * frecuencia. Columna 0 (y la última si [dw] es par: fx = dw/2): fila 0 -> fy = 0, filas 2k-1 y 2k -> fy = k, última
     * fila si [dh] es par -> fy = dh/2. Resto: columnas 2j-1 y 2j -> fx = j, fila y -> fy = y (envuelta).
     */
    private fun gainMat(cov: Cov, dh: Int, dw: Int, k: Double, fCut: Double, gMax: Double): Mat {
        val out = FloatArray(dh * dw)
        val evenW = dw % 2 == 0; val evenH = dh % 2 == 0
        fun g(iy: Int, ix: Int) = DeblurMath.wienerGain(cov, ix.toDouble() / dw, iy.toDouble() / dh, k, fCut, gMax).toFloat()
        val rowG = FloatArray(dw / 2 + 1)
        val half = dw / 2
        val pi2 = Math.PI * Math.PI
        val ex = DoubleArray(half + 1) { val fx = it.toDouble() / dw; kotlin.math.exp(-2 * pi2 * cov.a * fx * fx) }
        val fx2 = DoubleArray(half + 1) { val fx = it.toDouble() / dw; fx * fx }
        val lo2 = fCut * fCut; val hi2 = (fCut + 0.06) * (fCut + 0.06)
        for (y in 0 until dh) {
            val iy = if (y <= dh / 2) y else y - dh
            // Ganancias de la fila para fx = 0..dw/2 (G es par: G(-f) = G(f)). H = ex(fx)·ey(fy)·r^j sin exponenciales
            // por elemento; la ganancia es la de [DeblurMath.wienerGain]
            val fy = iy.toDouble() / dh
            val ey = kotlin.math.exp(-2 * pi2 * cov.c * fy * fy)
            val r = kotlin.math.exp(-4 * pi2 * cov.b * fy / dw)
            var cr = 1.0
            val fy2 = fy * fy
            for (j in 0..half) {
                val h = ex[j] * ey * cr; cr *= r
                var gg = min(h / (h * h + k), gMax)
                val fr2 = fx2[j] + fy2
                if (fr2 > lo2) {
                    val t = if (fr2 >= hi2) 1.0 else (sqrt(fr2) - fCut) / 0.06
                    gg = gg * (1 - t) + t * min(gg, 1.0)
                }
                rowG[j] = gg.toFloat()
            }
            val base = y * dw
            for (x in 1 until dw) {
                if (evenW && x == dw - 1) continue
                out[base + x] = rowG[(x + 1) / 2]
            }
        }
        // Columnas especiales (fx = 0 y fx = dw/2)
        val specialCols = if (evenW) intArrayOf(0, dw - 1) else intArrayOf(0)
        for (col in specialCols) {
            val ix = if (col == 0) 0 else dw / 2
            for (y in 0 until dh) {
                val iy = when {
                    y == 0 -> 0
                    evenH && y == dh - 1 -> dh / 2
                    else -> (y + 1) / 2
                }
                out[y * dw + col] = g(iy, ix)
            }
        }
        val m = Mat(dh, dw, CvType.CV_32F); m.put(0, 0, out)
        return m
    }
}
