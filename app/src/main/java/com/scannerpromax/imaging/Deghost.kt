package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * IMAGEN DOBLE / ESTELA POR MOVIMIENTO en las zonas alejadas (rectificadas): cada trazo con un núcleo nítido y una
 * "sombra" débil desplazada unos píxeles (el móvil se movió durante la exposición; la zona lejana además se amplía al
 * rectificar). El núcleo de esa mancha no es gaussiano ni simétrico (impulso + cola, ecos), así que [Deblur] no lo
 * modela: aquí se ESTIMA sin forma paramétrica, por zonas, y se ecualiza con la zona más nítida de la propia página.
 *
 *  1. PREDICCIÓN BITONAL: un documento es papel + tinta. En cada tesela con trazos, el contraste de tinta
 *     c = fondo − Y se umbraliza al 50 % de su nivel típico: S = trazos nítidos a contraste pleno (la estela, más
 *     débil, queda fuera de S y por tanto en el núcleo).
 *  2. NÚCLEO por mínimos cuadrados en el dominio del gradiente: min Σ|k ∗ ∇S − ∇c|² + λ|k|², soporte (2R+1)²,
 *     acumulando por zona las autocorrelaciones / correlaciones cruzadas (FFT por tesela) y resolviendo con gradiente
 *     conjugado (convolución por FFT). Limpieza: sin negativos ni ruido, centrado en su pico (el trazo no se mueve).
 *     Se descarta la zona si los trazos no tienen orientaciones variadas (núcleo indeterminado: una foto, rayas) o si
 *     el núcleo llega al borde del soporte.
 *  3. RELATIVO: la referencia es el núcleo (simetrizado) de las zonas más nítidas de la página; sólo se corrigen las
 *     zonas cuyo núcleo es claramente más ancho, y se llevan al desenfoque de la referencia:
 *     F = K_ref · conj(K) / (|K|² + reg) (ganancia limitada). Sesgos del estimador comunes a toda la página se cancelan
 *     y una página uniforme (escaneo, captura) no se toca.
 *  4. Mezcla bilineal entre zonas, compuerta de bordes según el ruido y límite anti-halo (no puede aclarar por encima
 *     del papel local ni oscurecer más de una fracción del rango local). Sólo luminancia (ΔY en los tres canales).
 */
internal object Deghost {

    data class Params(
        val radius: Int = 18,
        val zone: Int = 640,
        val tile: Int = 256,
        val lam: Double = 0.01,
        val reg: Double = 0.015,
        val gainMax: Double = 4.0,
        val thr: Double = 0.08,
        /** Exceso de ancho del núcleo (px) sobre la referencia: desde [dw0] se corrige, desde [dw1] por completo. */
        val dw0: Double = 0.6,
        val dw1: Double = 1.2,
        val clampDark: Double = 0.3,
        val minIso: Double = 0.25,
        val maxRing: Double = 0.05,
        /** Máximo de teselas analizadas por zona (las de más bordes): acota el tiempo. */
        val maxTiles: Int = 4,
        /** Ampliación relativa a la referencia: desde [magLo] se puede corregir, desde [magHi] sin atenuar. */
        val magLo: Double = 1.1,
        val magHi: Double = 1.3,
        /** Exceso de ancho adicional exigido a las zonas no ampliadas (px). */
        val dw0Flat: Double = 0.6,
        /** Dilatación de la compuerta de bordes (px, impar; 0 = el ancho del núcleo). */
        val gateDilate: Int = 9,
    )

    /** Resultado: peso de la corrección por zona (rejilla [nzy] x [nzx] de [zh] x [zw] px). */
    class Report(val applied: Boolean, val nzy: Int, val nzx: Int, val zh: Double, val zw: Double, val wt: DoubleArray, val ms: Long) {
        /** Peso de la corrección en el píxel (x, y) (la misma partición de la unidad que la mezcla). */
        fun weightAt(x: Double, y: Double): Double {
            if (!applied) return 0.0
            var s = 0.0
            for (zy in 0 until nzy) {
                val wy = DeghostMath.partition(y, zy, zh, nzy); if (wy <= 0) continue
                for (zx in 0 until nzx) { val wx = DeghostMath.partition(x, zx, zw, nzx); if (wx > 0) s += wy * wx * wt[zy * nzx + zx] }
            }
            return s
        }
    }

    @Volatile internal var log: ((String) -> Unit)? = null

    private class ZoneAcc(r: Int) {
        val ac = DoubleArray((4 * r + 1) * (4 * r + 1)); val cc = DoubleArray((2 * r + 1) * (2 * r + 1))
        var ne = 0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
    }

    /**
     * Restaura [rgb] (8UC3, in situ). [hinv] = homografía salida -> foto de la rectificación: sólo con perspectiva
     * apreciable (las zonas sólo difieren en desenfoque si están a distinta distancia) y sólo se corrigen las zonas
     * AMPLIADAS respecto de la referencia (más lejanas).
     */
    fun restore(rgb: Mat, hinv: DoubleArray?, p: Params = Params()): Report {
        val t0 = System.nanoTime()
        val w = rgb.cols(); val h = rgb.rows()
        val none = { Report(false, 1, 1, h.toDouble(), w.toDouble(), DoubleArray(1), (System.nanoTime() - t0) / 1_000_000) }
        if (hinv == null || rgb.channels() != 3 || w < 2 * p.zone || h < 2 * p.zone) return none()
        val r = p.radius
        val nzy = max(1, (h.toDouble() / p.zone).roundToInt()); val nzx = max(1, (w.toDouble() / p.zone).roundToInt())
        val nz = nzy * nzx
        val zh = h.toDouble() / nzy; val zw = w.toDouble() / nzx
        // Ampliación local de la rectificación por zona
        val mag = DoubleArray(nz); val area = DoubleArray(nz)
        for (i in 0 until nz) {
            val j = DeblurMath.jacobianOutFromSrc(hinv, (i % nzx + 0.5) * zw, (i / nzx + 0.5) * zh)
            mag[i] = DeblurMath.maxStretch(j); area[i] = sqrt(abs(j[0] * j[3] - j[1] * j[2]))
        }
        MatBag().use { bag ->
            val y8 = bag.add(Cv.gray(rgb))
            val bg8 = bag.add(background(y8))
            val kers = arrayOfNulls<DoubleArray>(nz)
            val widths = DoubleArray(nz) { Double.NaN }
            val solver = CgFft(r, bag)
            val buf = TileBuffers(p.tile, r, bag)
            fun analyze(i: Int) {
                val zy = i / nzx; val zx = i % nzx
                val y0 = (zy * zh).roundToInt(); val y1 = min(h, ((zy + 1) * zh).roundToInt())
                val x0 = (zx * zw).roundToInt(); val x1 = min(w, ((zx + 1) * zw).roundToInt())
                val acc = ZoneAcc(r)
                // Teselas de la zona, las de más bordes primero (tope [maxTiles])
                val tiles = ArrayList<IntArray>()
                var ty = y0
                while (ty < y1 - p.tile / 2) {
                    var tx = x0
                    while (tx < x1 - p.tile / 2) { tiles.add(intArrayOf(ty, min(y1, ty + p.tile), tx, min(x1, tx + p.tile))); tx += p.tile }
                    ty += p.tile
                }
                val scored = tiles.mapNotNull { t -> prepTile(y8, bg8, t, buf)?.let { t to it } }
                    .sortedByDescending { it.second.ne }.take(p.maxTiles)
                for ((t, pr) in scored) accumulate(y8, bg8, t, pr, r, acc, buf)
                if (acc.ne < 1500) return
                if (DeghostMath.isotropy(acc.sxx, acc.syy, acc.sxy) < p.minIso) return
                val raw = solver.solve(acc.ac, acc.cc, p.lam)
                val k = DeghostMath.cleanKernel(raw, r, p.thr) ?: return
                if (DeghostMath.ringMass(k, r) > p.maxRing) return
                kers[i] = k; widths[i] = DeghostMath.width(k, r)
            }
            // 1-2) Núcleo de cada zona; referencia = el cuarto más nítido
            for (i in 0 until nz) analyze(i)
            val ok = (0 until nz).filter { kers[it] != null }
            if (ok.size < 3) return none()
            val sorted = ok.sortedBy { widths[it] }
            val refW = sorted.take(max(1, ok.size / 4))
            val wRef = refW.map { widths[it] }.average()
            val kRef = DeghostMath.symmetricMean(refW.map { kers[it]!! })
            val mRef = refW.map { mag[it] }.average()
            // Umbral de exceso: menor en las zonas AMPLIADAS por la rectificación (más lejanas: su desenfoque crece de
            // forma esperable); en las no ampliadas sólo un exceso grande y compartido con una zona vecina (el
            // movimiento y la profundidad de campo varían suavemente sobre la hoja; un valor aislado es del contenido)
            val excess = DoubleArray(nz) { if (kers[it] != null) widths[it] - wRef else 0.0 }
            val wt = DoubleArray(nz) { i ->
                if (kers[i] == null) return@DoubleArray 0.0
                val g = ((mag[i] / mRef - p.magLo) / (p.magHi - p.magLo)).coerceIn(0.0, 1.0)
                val dw0 = p.dw0 + (1 - g) * p.dw0Flat; val dw1 = dw0 + (p.dw1 - p.dw0)
                val own = DeghostMath.zoneWeight(widths[i], wRef, dw0, dw1)
                val zy = i / nzx; val zx = i % nzx
                var nb = 0.0
                for ((dy, dx) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val y = zy + dy; val x = zx + dx
                    if (y in 0 until nzy && x in 0 until nzx) nb = max(nb, excess[y * nzx + x])
                }
                own * (g + (1 - g) * (nb / (0.5 * dw0)).coerceIn(0.0, 1.0))
            }
            val todo = wt.indices.filter { wt[it] > 0.02 }
            log?.invoke("deghost ${w}x$h zonas=${nzx}x$nzy ok=${ok.size} wref=%.2f ".format(java.util.Locale.ROOT, wRef) +
                (0 until nz).joinToString(" ") { if (widths[it].isNaN()) "-" else "%.1f@%.2f".format(java.util.Locale.ROOT, widths[it], mag[it] / mRef) } +
                " corr=" + todo.joinToString(",") { "$it:%.2f".format(java.util.Locale.ROOT, wt[it]) })
            if (todo.isEmpty()) return none()
            // 3-4) Restauración por zona sobre su soporte en la partición (zona + 1/4 por lado), en la caja que las contiene
            fun span(z: Int, zs: Double, nzz: Int, lim: Int): Pair<Int, Int> {
                val a = if (z == 0) 0 else max(0, ((z - 0.25) * zs).toInt())
                val b = if (z == nzz - 1) lim else min(lim, ceil((z + 1.25) * zs).toInt())
                return a to b
            }
            var by0 = h; var by1 = 0; var bx0 = w; var bx1 = 0
            for (i in todo) {
                val (a0, a1) = span(i / nzx, zh, nzy, h); val (b0, b1) = span(i % nzx, zw, nzx, w)
                by0 = min(by0, a0); by1 = max(by1, a1); bx0 = min(bx0, b0); bx1 = max(bx1, b1)
            }
            val bh = by1 - by0; val bw = bx1 - bx0
            val delta = bag.add(Mat.zeros(bh, bw, CvType.CV_32F))
            for (i in todo) {
                val zy = i / nzx; val zx = i % nzx
                val (a0, a1) = span(zy, zh, nzy, h); val (b0, b1) = span(zx, zw, nzx, w)
                val d = restoreZone(y8, a0, a1, b0, b1, kers[i]!!, kRef, r, p)
                val wy = Mat(a1 - a0, 1, CvType.CV_32F); wy.put(0, 0, FloatArray(a1 - a0) { (DeghostMath.partition(a0 + it + 0.5, zy, zh, nzy) * wt[i]).toFloat() })
                val wx = Mat(1, b1 - b0, CvType.CV_32F); wx.put(0, 0, FloatArray(b1 - b0) { DeghostMath.partition(b0 + it + 0.5, zx, zw, nzx).toFloat() })
                val wb = Mat(); Core.gemm(wy, wx, 1.0, Mat(), 0.0, wb); wy.release(); wx.release()
                Core.multiply(d, wb, d)
                val dst = delta.submat(a0 - by0, a1 - by0, b0 - bx0, b1 - bx0)
                Core.add(dst, d, dst); dst.release(); d.release(); wb.release()
            }
            applyDelta(rgb, y8, delta, by0, bx0, todo.maxOf { widths[it] }, p, bag)
            val rep = Report(true, nzy, nzx, zh, zw, wt, (System.nanoTime() - t0) / 1_000_000)
            log?.invoke("deghost ms=${rep.ms}")
            return rep
        }
    }

    /** Fondo (papel) a resolución completa: cierre morfológico a 1/4 + suavizado (8U). */
    private fun background(y8: Mat): Mat = MatBag().use { bag ->
        val s = bag.mat()
        Imgproc.resize(y8, s, Size(max(1.0, y8.cols() / 4.0), max(1.0, y8.rows() / 4.0)), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.morphologyEx(s, s, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, 9))
        Imgproc.GaussianBlur(s, s, Size(0.0, 0.0), 2.0)
        val out = Mat(); Imgproc.resize(s, out, y8.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); out
    }

    private class TilePrep(val lv: Float, val ne: Int)

    private class TileBuffers(t: Int, r: Int, bag: MatBag) {
        val c = FloatArray(t * t); val s = FloatArray(t * t)
        val m = Core.getOptimalDFTSize(t + 2 * r)
        val sp: Mat = bag.add(Mat(m, m, CvType.CV_32F)); val cp: Mat = bag.add(Mat(m, m, CvType.CV_32F))
        val fs: Mat = bag.mat(); val fc: Mat = bag.mat(); val fsw: Mat = bag.mat(); val prod: Mat = bag.mat(); val inv: Mat = bag.mat()
        val tmp: Mat = bag.mat()
        val gradW: Mat = bag.add(ccsRealMat(m, m) { fy, fx -> 4 * sin(PI * fx) * sin(PI * fx) + 4 * sin(PI * fy) * sin(PI * fy) })
        val out = FloatArray(m * m)
    }

    /** Contraste de tinta de la tesela [t] en buf.c; null si no tiene trazos útiles (poca/mucha tinta, sin contraste). */
    private fun prepTile(y8: Mat, bg8: Mat, t: IntArray, buf: TileBuffers): TilePrep? {
        val th = t[1] - t[0]; val tw = t[3] - t[2]
        val ry = y8.submat(t[0], t[1], t[2], t[3]); val rb = bg8.submat(t[0], t[1], t[2], t[3])
        Core.subtract(rb, ry, buf.tmp); ry.release(); rb.release()      // 8U: satura en 0
        val b = ByteArray(th * tw); buf.tmp.get(0, 0, b)
        val hist = IntArray(256); var nInk = 0
        for (i in 0 until th * tw) { val v = b[i].toInt() and 0xFF; buf.c[i] = v.toFloat(); if (v > 6) { hist[v]++; nInk++ } }
        val frac = nInk.toDouble() / (th * tw)
        if (frac < 0.01 || frac > 0.45) return null
        var acc = 0; var lv = 255
        val target = 0.9 * nInk
        for (v in 7..255) { acc += hist[v]; if (acc >= target) { lv = v; break } }
        if (lv < 20) return null
        val half = 0.5f * lv; var ne = 0
        for (i in 0 until th * tw) buf.s[i] = if (buf.c[i] > half) lv.toFloat() else 0f
        for (y in 0 until th - 1) for (x in 0 until tw - 1) {
            val i = y * tw + x
            if (buf.s[i] != buf.s[i + 1] || buf.s[i] != buf.s[i + tw]) ne++
        }
        if (ne < 300) return null
        return TilePrep(lv.toFloat(), ne)
    }

    /** Suma a [acc] la autocorrelación del gradiente de S y su correlación cruzada con el de c (tesela [t]). */
    private fun accumulate(y8: Mat, bg8: Mat, t: IntArray, pr: TilePrep, r: Int, acc: ZoneAcc, buf: TileBuffers) {
        prepTile(y8, bg8, t, buf)   // recarga c y S de esta tesela en los búferes
        val th = t[1] - t[0]; val tw = t[3] - t[2]
        // Tensor de estructura de ∇S
        for (y in 0 until th - 1) for (x in 0 until tw - 1) {
            val i = y * tw + x
            val gx = (buf.s[i + 1] - buf.s[i]).toDouble(); val gy = (buf.s[i + tw] - buf.s[i]).toDouble()
            acc.sxx += gx * gx; acc.syy += gy * gy; acc.sxy += gx * gy
        }
        acc.ne += pr.ne
        val m = buf.m
        buf.sp.setTo(Scalar(0.0)); buf.cp.setTo(Scalar(0.0))
        val sRoi = buf.sp.submat(0, th, 0, tw); val cRoi = buf.cp.submat(0, th, 0, tw)
        sRoi.put(0, 0, buf.s.copyOf(th * tw)); cRoi.put(0, 0, buf.c.copyOf(th * tw)); sRoi.release(); cRoi.release()
        Core.dft(buf.sp, buf.fs); Core.dft(buf.cp, buf.fc)
        // Ponderación |D(f)|² = energía del gradiente (equivale a trabajar con ∇S y ∇c)
        Core.multiply(buf.fs, buf.gradW, buf.fsw)
        val l = 2 * r; val ma = 4 * r + 1; val nc = 2 * r + 1
        Core.mulSpectrums(buf.fsw, buf.fs, buf.prod, 0, true)
        Core.idft(buf.prod, buf.inv, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
        buf.inv.get(0, 0, buf.out)
        for (dy in -l..l) for (dx in -l..l) acc.ac[(dy + l) * ma + (dx + l)] += buf.out[((dy + m) % m) * m + (dx + m) % m]
        // cc[d] = Σ ∇S(p)·∇c(p + d)  ->  F⁻¹(Fc · conj(Fs·W))
        Core.mulSpectrums(buf.fc, buf.fsw, buf.prod, 0, true)
        Core.idft(buf.prod, buf.inv, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
        buf.inv.get(0, 0, buf.out)
        for (dy in -r..r) for (dx in -r..r) acc.cc[(dy + r) * nc + (dx + r)] += buf.out[((dy + m) % m) * m + (dx + m) % m]
    }

    /** Gradiente conjugado con el producto A·x por FFT (rejilla cuadrada >= 6R+1). */
    private class CgFft(val r: Int, bag: MatBag) {
        val n = 2 * r + 1; val g = Core.getOptimalDFTSize(6 * r + 2)
        val xa: Mat = bag.add(Mat(g, g, CvType.CV_32F)); val fx: Mat = bag.mat(); val fa: Mat = bag.mat(); val pr: Mat = bag.mat(); val iv: Mat = bag.mat()
        val arr = FloatArray(g * g)
        fun solve(ac: DoubleArray, cc: DoubleArray, lam: Double): DoubleArray {
            val l = 2 * r; val ma = 4 * r + 1
            // ac colocado con su centro en (0, 0) (envuelto)
            java.util.Arrays.fill(arr, 0f)
            for (dy in -l..l) for (dx in -l..l) arr[((dy + g) % g) * g + (dx + g) % g] = ac[(dy + l) * ma + (dx + l)].toFloat()
            val am = Mat(g, g, CvType.CV_32F); am.put(0, 0, arr); Core.dft(am, fa); am.release()
            val mv: (DoubleArray) -> DoubleArray = { x ->
                java.util.Arrays.fill(arr, 0f)
                for (y in 0 until n) for (xx in 0 until n) arr[y * g + xx] = x[y * n + xx].toFloat()
                xa.put(0, 0, arr); Core.dft(xa, fx)
                Core.mulSpectrums(fx, fa, pr, 0, false)
                Core.idft(pr, iv, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
                iv.get(0, 0, arr)
                // y[i] = Σ_j ac[i - j] x[j]: x en [0, n) -> salida en [0, n) con el mismo origen
                DoubleArray(n * n) { arr[(it / n) * g + it % n].toDouble() }
            }
            return DeghostMath.solveCg(ac, cc, r, lam, 60, mv)
        }
    }

    /**
     * ΔY de una zona: región [a0, a1) x [b0, b1) de la luminancia filtrada con F = K_ref·conj(K)/(|K|²+reg) (|F| <=
     * gainMax), con margen reflejado. Devuelve Δ (32F) = Y_F − Y.
     */
    private fun restoreZone(y8: Mat, a0: Int, a1: Int, b0: Int, b1: Int, k: DoubleArray, kRef: DoubleArray, r: Int, p: Params): Mat = MatBag().use { bag ->
        val hh = a1 - a0; val ww = b1 - b0; val pad = r + 2
        val roi = y8.submat(a0, a1, b0, b1)
        val yp = bag.mat()
        Core.copyMakeBorder(roi, yp, pad, pad, pad, pad, Core.BORDER_REFLECT); roi.release()
        val dh = Core.getOptimalDFTSize(yp.rows()); val dw = Core.getOptimalDFTSize(yp.cols())
        val yf = bag.mat(); val t = bag.mat()
        Core.copyMakeBorder(yp, t, 0, dh - yp.rows(), 0, dw - yp.cols(), Core.BORDER_REFLECT)
        t.convertTo(yf, CvType.CV_32F)
        val fy = bag.mat(); Core.dft(yf, fy)
        fun kSpec(kk: DoubleArray): Mat {
            val n = 2 * r + 1; val a = FloatArray(dh * dw)
            for (y in 0 until n) for (x in 0 until n) { val v = kk[y * n + x]; if (v != 0.0) a[((y - r + dh) % dh) * dw + (x - r + dw) % dw] = v.toFloat() }
            val m = Mat(dh, dw, CvType.CV_32F); m.put(0, 0, a); val f = bag.mat(); Core.dft(m, f); m.release(); return f
        }
        val fk = kSpec(k); val fr = kSpec(kRef)
        val num = bag.mat(); val den = bag.mat()
        Core.mulSpectrums(fr, fk, num, 0, true)
        Core.mulSpectrums(fk, fk, den, 0, true)
        val na = FloatArray(dh * dw); val da = FloatArray(dh * dw); num.get(0, 0, na); den.get(0, 0, da)
        val ga = FloatArray(dh * dw)
        val reg = p.reg.toFloat(); val gm = p.gainMax.toFloat()
        forEachCcs(dh, dw) { re, im ->
            val d = da[re] + reg
            var gr = na[re] / d; var gi = if (im >= 0) na[im] / d else 0f
            val mg = sqrt(gr * gr + gi * gi)
            if (mg > gm) { gr *= gm / mg; gi *= gm / mg }
            ga[re] = gr; if (im >= 0) ga[im] = gi
        }
        val gmat = bag.add(Mat(dh, dw, CvType.CV_32F)); gmat.put(0, 0, ga)
        val prod = bag.mat(); Core.mulSpectrums(fy, gmat, prod, 0, false)
        val inv = bag.mat(); Core.idft(prod, inv, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
        val out = Mat()
        val ir = inv.submat(pad, pad + hh, pad, pad + ww); val yr = yf.submat(pad, pad + hh, pad, pad + ww)
        Core.subtract(ir, yr, out); ir.release(); yr.release()
        out
    }

    /** Compuerta de bordes (según el ruido), límite anti-halo y ΔY a los tres canales de la caja [delta] en (by0, bx0). */
    private fun applyDelta(rgb: Mat, y8: Mat, delta: Mat, by0: Int, bx0: Int, wMax: Double, p: Params, bag: MatBag) {
        val bh = delta.rows(); val bw = delta.cols()
        val ns = Deblur.noiseSigmaOf(y8)
        val t1 = max(1.5 * ns, 1.5); val t2 = 3 * t1
        val e = 8
        val a0 = max(0, by0 - e); val a1 = min(y8.rows(), by0 + bh + e)
        val c0 = max(0, bx0 - e); val c1 = min(y8.cols(), bx0 + bw + e)
        val ext = bag.mat(); y8.submat(a0, a1, c0, c1).convertTo(ext, CvType.CV_32F)
        val gx = bag.mat(); val gy = bag.mat()
        Imgproc.Sobel(ext, gx, CvType.CV_32F, 1, 0, 3, 1.0 / 8, 0.0)
        Imgproc.Sobel(ext, gy, CvType.CV_32F, 0, 1, 3, 1.0 / 8, 0.0)
        Core.magnitude(gx, gy, gx)
        Core.subtract(gx, Scalar(t1), gx); Core.multiply(gx, Scalar(1.0 / (t2 - t1)), gx)
        Core.min(gx, Scalar(1.0), gx); Core.max(gx, Scalar(0.0), gx)
        val rk = 2 * ceil(wMax).toInt() + 1
        val kr = Cv.kernel(Imgproc.MORPH_RECT, rk)
        if (p.gateDilate > 0) Imgproc.dilate(gx, gx, Cv.kernel(Imgproc.MORPH_RECT, p.gateDilate)) else Imgproc.dilate(gx, gx, kr)
        Imgproc.GaussianBlur(gx, gx, Size(0.0, 0.0), 1.5)
        val lo = bag.mat(); val hi = bag.mat()
        Imgproc.erode(ext, lo, kr); Imgproc.dilate(ext, hi, kr)
        val oy = by0 - a0; val ox = bx0 - c0
        val gR = gx.submat(oy, oy + bh, ox, ox + bw); val loR = lo.submat(oy, oy + bh, ox, ox + bw)
        val hiR = hi.submat(oy, oy + bh, ox, ox + bw); val yR = ext.submat(oy, oy + bh, ox, ox + bw)
        val yr = bag.mat(); val tmp = bag.mat(); val rng = bag.mat()
        Core.multiply(delta, gR, yr); Core.add(yr, yR, yr)
        Core.subtract(hiR, loR, rng)
        Core.scaleAdd(rng, -p.clampDark, loR, tmp); Core.max(yr, tmp, yr)
        Core.min(yr, hiR, yr)
        Core.subtract(yr, yR, yr)
        val f3 = bag.mat(); val d3 = bag.mat()
        val band = rgb.submat(by0, by0 + bh, bx0, bx0 + bw)
        band.convertTo(f3, CvType.CV_32FC3)
        Core.merge(listOf(yr, yr, yr), d3)
        Core.add(f3, d3, f3)
        f3.convertTo(band, CvType.CV_8UC3)
        for (m in listOf(band, gR, loR, hiR, yR)) m.release()
    }

    /**
     * Recorre las frecuencias de un espectro CCS empaquetado de [Core.dft] (real, [dh] x [dw]): índices de la parte real
     * e imaginaria (-1 si la frecuencia es real pura). Columnas 0 y (si [dw] es par) dw-1: empaquetadas por filas
     * (fila 0 real, filas 2k-1/2k, última fila real si [dh] es par). Resto: columnas 2j-1/2j en cada fila.
     */
    private inline fun forEachCcs(dh: Int, dw: Int, f: (Int, Int) -> Unit) {
        val evenW = dw % 2 == 0; val evenH = dh % 2 == 0
        for (y in 0 until dh) {
            val base = y * dw
            var x = 1
            val lastPair = if (evenW) dw - 2 else dw - 1
            while (x < lastPair) { f(base + x, base + x + 1); x += 2 }
        }
        val cols = if (evenW) intArrayOf(0, dw - 1) else intArrayOf(0)
        for (c in cols) {
            f(c, -1)
            var y = 1
            val lastPair = if (evenH) dh - 2 else dh - 1
            while (y < lastPair) { f(y * dw + c, (y + 1) * dw + c); y += 2 }
            if (evenH) f((dh - 1) * dw + c, -1)
        }
    }

    /** Matriz CCS (real, [dh] x [dw]) con una ganancia REAL g(fy, fx) (ciclos/px, par) por frecuencia. */
    private fun ccsRealMat(dh: Int, dw: Int, g: (Double, Double) -> Double): Mat {
        val out = FloatArray(dh * dw)
        val evenW = dw % 2 == 0; val evenH = dh % 2 == 0
        for (y in 0 until dh) {
            val fy = (if (y <= dh / 2) y else y - dh).toDouble() / dh
            val base = y * dw
            for (x in 1 until dw) {
                if (evenW && x == dw - 1) continue
                out[base + x] = g(fy, ((x + 1) / 2).toDouble() / dw).toFloat()
            }
        }
        val cols = if (evenW) intArrayOf(0, dw - 1) else intArrayOf(0)
        for (c in cols) {
            val fx = if (c == 0) 0.0 else 0.5
            for (y in 0 until dh) {
                val iy = when { y == 0 -> 0; evenH && y == dh - 1 -> dh / 2; else -> (y + 1) / 2 }
                out[y * dw + c] = g(iy.toDouble() / dh, fx).toFloat()
            }
        }
        val m = Mat(dh, dw, CvType.CV_32F); m.put(0, 0, out); return m
    }
}
