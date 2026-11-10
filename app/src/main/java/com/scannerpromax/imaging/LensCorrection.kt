package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Corrección de los defectos de la LENTE en la foto original (antes de detectar el documento y de la perspectiva, para que
 * los cantos y renglones rectos sigan rectos):
 *
 *  1. [undistort]: distorsión geométrica (barril/cojín) con un [LensMap] (calibración de Camera2 o autocalibrado). Remap
 *     por FRANJAS: el campo de desplazamiento se guarda en una rejilla gruesa (cada [GRID] px, minúscula, en caché por
 *     modelo y tamaño) y se interpola por franja con warpAffine (exacto en las esquinas de la rejilla); el desplazamiento
 *     (no la posición) es lo que se interpola, así que la cuantización de warpAffine no introduce error apreciable.
 *     Memoria: la foto de salida + unas franjas de mapas (no los 72 MB de un mapa completo de 12 MP).
 *  2. [chroma] / [fixChroma]: aberración cromática LATERAL (franjas rojas/azules en las letras de los bordes): el rojo y el
 *     azul salen ampliados/reducidos respecto del verde alrededor del centro óptico. Se mide el desplazamiento de los
 *     bordes de R y B respecto de G en teselas del anillo exterior (correlación de fase de los gradientes, subpíxel) y se
 *     ajusta una escala radial por canal; se corrige re-escalando R y B.
 *  3. [radialSharpen]: nitidez suave que crece hacia los bordes y esquinas (la lente de un móvil emborrona más allí).
 */
internal object LensCorrection {

    /** Paso de la rejilla del campo de desplazamiento (px). */
    const val GRID = 16
    private const val STRIP = 256

    /** Registro opcional (banco/depuración). */
    @Volatile internal var log: ((String) -> Unit)? = null

    // ------------------------------------------------------------------------------------------ distorsión geométrica

    private class GridEntry(val key: String, val grid: Mat, val gw: Int, val gh: Int, val minDy: FloatArray, val maxDy: FloatArray)

    /** Caché de la última rejilla (una cámara y una resolución: se repite foto tras foto). */
    @Volatile private var cached: GridEntry? = null

    /** Rejilla 2 canales (dx, dy) del desplazamiento source − destino en los nodos (i·GRID, j·GRID). */
    private fun gridFor(map: LensMap): GridEntry {
        val key = "${map.width}x${map.height} ${map.describe()}"
        cached?.takeIf { it.key == key }?.let { return it }
        val gw = (map.width - 1) / GRID + 2
        val gh = (map.height - 1) / GRID + 2
        val data = FloatArray(gw * gh * 2)
        val minDy = FloatArray(gh); val maxDy = FloatArray(gh)
        val o = DoubleArray(2)
        for (j in 0 until gh) {
            val y = (j * GRID).toDouble()
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (i in 0 until gw) {
                val x = (i * GRID).toDouble()
                map.source(x, y, o)
                val dx = (o[0] - x).toFloat(); val dy = (o[1] - y).toFloat()
                data[(j * gw + i) * 2] = dx; data[(j * gw + i) * 2 + 1] = dy
                mn = min(mn, dy); mx = max(mx, dy)
            }
            minDy[j] = mn; maxDy[j] = mx
        }
        val g = Mat(gh, gw, CvType.CV_32FC2)
        g.put(0, 0, data)
        val e = GridEntry(key, g, gw, gh, minDy, maxDy)
        cached?.grid?.release()
        cached = e
        return e
    }

    /** Libera la rejilla en caché (pruebas, poca memoria). */
    fun clearCache() { cached?.grid?.release(); cached = null }

    /**
     * Foto corregida (nuevo Mat del mismo tipo y tamaño que [src]) según [map] (que debe tener el tamaño de [src]).
     * [interp]: INTER_CUBIC por defecto (texto nítido); los bordes se replican.
     */
    fun undistort(src: Mat, map: LensMap, interp: Int = Imgproc.INTER_CUBIC): Mat {
        val w = src.cols(); val h = src.rows()
        require(map.width == w && map.height == h) { "modelo ${map.width}x${map.height} != foto ${w}x$h" }
        val t0 = System.nanoTime()
        val e = gridFor(map)
        val dst = Mat(h, w, src.type())
        val rampX = Mat(1, w, CvType.CV_32F).also { r -> r.put(0, 0, FloatArray(w) { it.toFloat() }) }
        MatBag().use { bag ->
            val disp = bag.mat(); val mx = bag.mat(); val my = bag.mat()
            val chans = ArrayList<Mat>(2)
            val rampY = bag.mat()
            var y0 = 0
            while (y0 < h) {
                val y1 = min(h, y0 + STRIP)
                val sh = y1 - y0
                // Interpolación de la rejilla a la franja: dst(x, y) = grid(x / G, (y + y0) / G)
                val m = Mat(2, 3, CvType.CV_64F)
                m.put(0, 0, 1.0 / GRID, 0.0, 0.0, 0.0, 1.0 / GRID, y0.toDouble() / GRID)
                Imgproc.warpAffine(e.grid, disp, m, Size(w.toDouble(), sh.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                m.release()
                chans.clear(); Core.split(disp, chans)
                // Filas de la foto que necesita la franja (márgenes para el núcleo cúbico)
                val j0 = y0 / GRID; val j1 = min(e.gh - 1, (y1 - 1) / GRID + 1)
                var mnDy = Float.MAX_VALUE; var mxDy = -Float.MAX_VALUE
                for (j in j0..j1) { mnDy = min(mnDy, e.minDy[j]); mxDy = max(mxDy, e.maxDy[j]) }
                val sy0 = max(0, floor(y0 + mnDy - 3.0).toInt())
                val sy1 = min(h, ceil(y1 + mxDy + 3.0).toInt())
                if (sy1 <= sy0) {
                    // Franja totalmente fuera de la foto (no debería pasar): se replica la última fila
                    for (c in chans) c.release()
                    dst.submat(y0, y1, 0, w).setTo(Scalar(255.0, 255.0, 255.0, 255.0))
                    y0 = y1; continue
                }
                Core.add(chans[0], rampX.let { r -> Mat().also { Core.repeat(r, sh, 1, it) } }.also { bag.add(it) }, mx)
                // Y: rampa vertical (y0 + fila) − sy0 (la franja de origen empieza en sy0)
                if (rampY.rows() != sh) {
                    rampY.release()
                    Mat(sh, 1, CvType.CV_32F).also { r -> r.put(0, 0, FloatArray(sh) { it.toFloat() }) }.copyTo(rampY)
                }
                val ry = bag.mat(); Core.repeat(rampY, 1, w, ry)
                Core.add(ry, Scalar((y0 - sy0).toDouble()), ry)
                Core.add(chans[1], ry, my)
                for (c in chans) c.release()
                val srcStrip = src.submat(sy0, sy1, 0, w)
                val out = dst.submat(y0, y1, 0, w)
                Imgproc.remap(srcStrip, out, mx, my, interp, Core.BORDER_REPLICATE)
                srcStrip.release(); out.release(); ry.release()
                y0 = y1
            }
        }
        rampX.release()
        log?.invoke("lente: remap ${w}x$h ${map.describe()} en ${(System.nanoTime() - t0) / 1_000_000} ms")
        return dst
    }

    /**
     * Mapa de un punto (x, y) de la foto ORIGINAL al de la corregida (para llevar cuadriláteros/guías ya calculados sobre
     * la foto sin corregir). Identidad si [map] es null.
     */
    fun mapPoint(map: LensMap?, x: Double, y: Double): DoubleArray {
        val o = doubleArrayOf(x, y)
        map?.undistort(x, y, o)
        return o
    }

    // --------------------------------------------------------------------------------------- aberración cromática

    /** Escalas radiales de R y B respecto de G (R(x) ≈ G en c + (x − c)/(1 + sR)) y su fiabilidad. */
    data class Chroma(val sR: Double, val sB: Double, val cx: Double, val cy: Double, val tiles: Int, val fitR: Double, val fitB: Double) {
        /** Franja máxima (px) en la esquina más alejada del centro. */
        fun fringe(w: Int, h: Int): Double {
            val r = hypot(max(cx, w - cx), max(cy, h - cy))
            return max(abs(sR), abs(sB)) * r
        }
    }

    /**
     * Mide la aberración cromática lateral de [img] (RGB/RGBA 8 bits, a resolución completa: las franjas miden 1–3 px).
     * [cx], [cy] = centro óptico (centro de la foto si no se conoce). Teselas de [tile] px en el anillo exterior con
     * bordes fuertes; null si no hay bordes suficientes o las medidas no son coherentes con una escala radial.
     */
    fun chroma(img: Mat, cx: Double = (img.cols() - 1) / 2.0, cy: Double = (img.rows() - 1) / 2.0, tile: Int = 128): Chroma? {
        val w = img.cols(); val h = img.rows()
        if (img.channels() < 3 || w < 6 * tile || h < 6 * tile) return null
        val rMax = hypot(max(cx, w - cx), max(cy, h - cy))
        val obsR = ArrayList<DoubleArray>(); val obsB = ArrayList<DoubleArray>()
        MatBag().use { bag ->
            val chans = ArrayList<Mat>(4)
            Core.split(img, chans)
            for (c in chans) bag.add(c)
            val win = bag.mat()
            Imgproc.createHanningWindow(win, Size(tile.toDouble(), tile.toDouble()), CvType.CV_32F)
            val nx = w / tile; val ny = h / tile
            val gG = bag.mat(); val gR = bag.mat(); val gB = bag.mat()
            for (ty in 0 until ny) for (tx in 0 until nx) {
                val x0 = tx * tile; val y0 = ty * tile
                val tcx = x0 + tile / 2.0 - cx; val tcy = y0 + tile / 2.0 - cy
                val rr = hypot(tcx, tcy) / rMax
                if (rr < 0.45) continue
                val roi = Rect(x0, y0, tile, tile)
                grad(chans[1].submat(roi), gG)
                // Teselas sin bordes fuertes (papel liso): fuera
                val mean = Core.mean(gG).`val`[0]
                if (mean < 6.0) continue
                grad(chans[0].submat(roi), gR)
                grad(chans[2].submat(roi), gB)
                val respR = DoubleArray(1); val respB = DoubleArray(1)
                val sR = Imgproc.phaseCorrelate(gG, gR, win, respR)
                val sB = Imgproc.phaseCorrelate(gG, gB, win, respB)
                // Desplazamiento de R respecto de G (px) frente al vector radial del centro de la tesela
                if (respR[0] > 0.15 && abs(sR.x) < 6 && abs(sR.y) < 6) obsR += doubleArrayOf(tcx, tcy, sR.x, sR.y, respR[0])
                if (respB[0] > 0.15 && abs(sB.x) < 6 && abs(sB.y) < 6) obsB += doubleArrayOf(tcx, tcy, sB.x, sB.y, respB[0])
            }
        }
        val fr = fitRadial(obsR) ?: return null
        val fb = fitRadial(obsB) ?: return null
        val n = min(obsR.size, obsB.size)
        log?.invoke("lente: CA sR=%.5f (ajuste %.2f) sB=%.5f (ajuste %.2f) teselas %d".format(fr.first, fr.second, fb.first, fb.second, n))
        return Chroma(fr.first, fb.first, cx, cy, n, fr.second, fb.second)
    }

    /** Gradiente (|Sobel| x + y) en float de una tesela 8 bits (se libera [src]). */
    private fun grad(src: Mat, out: Mat) {
        val f = Mat(); src.convertTo(f, CvType.CV_32F)
        val gx = Mat(); val gy = Mat()
        Imgproc.Sobel(f, gx, CvType.CV_32F, 1, 0)
        Imgproc.Sobel(f, gy, CvType.CV_32F, 0, 1)
        Core.magnitude(gx, gy, out)
        f.release(); gx.release(); gy.release(); src.release()
    }

    /**
     * Ajuste robusto de d = s · v (d = desplazamiento medido, v = vector radial): s por mínimos cuadrados ponderados con
     * dos pasadas (fuera los residuos grandes). Devuelve (s, fracción de la varianza explicada) o null si es incoherente.
     */
    internal fun fitRadial(obs: List<DoubleArray>): Pair<Double, Double>? {
        if (obs.size < 6) return null
        var keep = obs
        var s = 0.0
        repeat(2) { pass ->
            var num = 0.0; var den = 0.0
            for (o in keep) { val wt = o[4]; num += wt * (o[2] * o[0] + o[3] * o[1]); den += wt * (o[0] * o[0] + o[1] * o[1]) }
            if (den <= 0) return null
            s = num / den
            if (pass == 0) {
                val res = keep.map { o -> hypot(o[2] - s * o[0], o[3] - s * o[1]) }.sorted()
                val lim = max(0.35, res[res.size / 2] * 2.5)
                keep = keep.filter { o -> hypot(o[2] - s * o[0], o[3] - s * o[1]) <= lim }
                if (keep.size < 6) return null
            }
        }
        var tot = 0.0; var res = 0.0
        for (o in keep) { tot += o[2] * o[2] + o[3] * o[3]; res += (o[2] - s * o[0]).let { it * it } + (o[3] - s * o[1]).let { it * it } }
        val explained = if (tot > 1e-9) 1 - res / tot else 0.0
        return s to explained
    }

    /**
     * Corrige in situ la aberración de [img] (RGB/RGBA): R y B se re-escalan alrededor de (cx, cy) para alinearse con G.
     * Sólo si es apreciable ([minFringe] px en la esquina) y el ajuste es coherente.
     */
    fun fixChroma(img: Mat, ca: Chroma, minFringe: Double = 0.5): Boolean {
        val w = img.cols(); val h = img.rows()
        if (ca.fringe(w, h) < minFringe) return false
        val chans = ArrayList<Mat>(4)
        Core.split(img, chans)
        try {
            for ((idx, s) in listOf(0 to ca.sR, 2 to ca.sB)) {
                val fit = if (idx == 0) ca.fitR else ca.fitB
                if (abs(s) * hypot(w / 2.0, h / 2.0) < minFringe * 0.6 || fit < 0.3) continue
                // R(x) muestreado en c + (x − c)(1 + s): su borde cae donde el de G
                val a = 1 + s
                val m = Mat(2, 3, CvType.CV_64F)
                m.put(0, 0, a, 0.0, ca.cx * (1 - a), 0.0, a, ca.cy * (1 - a))
                val out = Mat()
                Imgproc.warpAffine(chans[idx], out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                m.release()
                chans[idx].release(); chans[idx] = out
            }
            Core.merge(chans, img)
        } finally {
            for (c in chans) c.release()
        }
        return true
    }

    // ------------------------------------------------------------------------------------------- nitidez de bordes

    /**
     * Realce suave de la luminancia que crece con la distancia al centro de la FOTO (la lente emborrona más los bordes y las
     * esquinas). [img] = página (RGB 8UC3, in situ). [toPhoto] = homografía página -> foto (3x3, fila mayor) o null si la
     * página es la propia foto; [photoW]x[photoH] = tamaño de la foto. Peso 0 hasta el 45 % del radio y [amount] en la
     * esquina; limitado al mínimo/máximo local (sin halos).
     */
    fun radialSharpen(img: Mat, toPhoto: DoubleArray?, photoW: Int, photoH: Int, amount: Double = 0.7, sigma: Double = 1.1) {
        if (img.channels() != 3 || amount <= 0) return
        val w = img.cols(); val h = img.rows()
        // Mapa de pesos a baja resolución (suave) -> se amplía por franja
        val lw = max(2, w / 32); val lh = max(2, h / 32)
        val wt = FloatArray(lw * lh)
        val pcx = photoW / 2.0; val pcy = photoH / 2.0; val pr = hypot(pcx, pcy)
        for (j in 0 until lh) for (i in 0 until lw) {
            val x = (i + 0.5) * w / lw; val y = (j + 0.5) * h / lh
            var px = x; var py = y
            if (toPhoto != null) {
                val d = toPhoto[6] * x + toPhoto[7] * y + toPhoto[8]
                px = (toPhoto[0] * x + toPhoto[1] * y + toPhoto[2]) / d
                py = (toPhoto[3] * x + toPhoto[4] * y + toPhoto[5]) / d
            }
            val r = hypot(px - pcx, py - pcy) / pr
            val t = ((r - 0.45) / 0.55).coerceIn(0.0, 1.0)
            wt[j * lw + i] = (amount * t * t).toFloat()
        }
        if (wt.all { it < 0.02f }) return
        val wLow = Mat(lh, lw, CvType.CV_32F).also { it.put(0, 0, wt) }
        MatBag().use { bag ->
            val pad = 6
            var y0 = 0
            while (y0 < h) {
                val y1 = min(h, y0 + 512)
                val a0 = max(0, y0 - pad); val a1 = min(h, y1 + pad)
                val roi = img.submat(a0, a1, 0, w)
                val ycc = bag.mat(); Imgproc.cvtColor(roi, ycc, Imgproc.COLOR_RGB2GRAY)
                val yf = bag.mat(); ycc.convertTo(yf, CvType.CV_32F)
                val bl = bag.mat(); Imgproc.GaussianBlur(yf, bl, Size(0.0, 0.0), sigma)
                val det = bag.mat(); Core.subtract(yf, bl, det)
                // Pesos de la franja (de la rejilla baja a la franja completa)
                val wm = bag.mat()
                val m = Mat(2, 3, CvType.CV_64F)
                val sx = lw.toDouble() / w; val sy = lh.toDouble() / h
                m.put(0, 0, sx, 0.0, -0.5 + 0.5 * sx, 0.0, sy, a0 * sy - 0.5 + 0.5 * sy)
                Imgproc.warpAffine(wLow, wm, m, Size(w.toDouble(), (a1 - a0).toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                m.release()
                Core.multiply(det, wm, det)
                // Límite anti-halo: Y + ΔY dentro de [min, max] del vecindario 3x3
                val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                val lo = bag.mat(); val hi = bag.mat()
                Imgproc.erode(yf, lo, k); Imgproc.dilate(yf, hi, k); k.release()
                val ny = bag.mat(); Core.add(yf, det, ny)
                Core.max(ny, lo, ny); Core.min(ny, hi, ny)
                Core.subtract(ny, yf, det)
                // Mismo ΔY en los tres canales (el color de la tinta se conserva)
                val d8 = bag.mat(); Imgproc.cvtColor(det, d8, Imgproc.COLOR_GRAY2RGB)
                val rf = bag.mat(); roi.convertTo(rf, CvType.CV_32FC3)
                Core.add(rf, d8, rf)
                val o8 = bag.mat(); rf.convertTo(o8, CvType.CV_8UC3)
                val inner = Rect(0, y0 - a0, w, y1 - y0)
                val dstRoi = img.submat(y0, y1, 0, w)
                o8.submat(inner).copyTo(dstRoi)
                dstRoi.release(); roi.release()
                for (mm in listOf(ycc, yf, bl, det, wm, lo, hi, ny, d8, rf, o8)) mm.release()
                y0 = y1
            }
        }
        wLow.release()
    }

    /** Desenfoque medido (σ aprox., px) de los bordes de [gray] 8UC1: cociente de gradientes (como [Deblur]). Para el banco. */
    internal fun edgeSharpness(gray: Mat): Double = MatBag().use { bag ->
        val f = bag.mat(); gray.convertTo(f, CvType.CV_32F)
        val lap = bag.mat(); Imgproc.Laplacian(f, lap, CvType.CV_32F)
        val mean = bag.add(org.opencv.core.MatOfDouble()); val sd = bag.add(org.opencv.core.MatOfDouble())
        Core.meanStdDev(lap, mean, sd)
        sd.toArray()[0]
    }
}
