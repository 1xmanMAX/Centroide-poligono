package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CAPA DE FOTOS: composición en dos capas.
 *  1. Capa base: la página entera con el procesado normal del filtro, como si no hubiera fotos (texto, sellos, firmas y
 *     fondo blanco intactos; las fotos pueden salir "quemadas": se tapan).
 *  2. Capa de fotos: de la imagen rectificada ORIGINAL (misma geometría, antes de cualquier filtro) se recortan sólo los
 *     recuadros exactos de las fotografías de cámara ([PhotoBoxRefine]) y se les aplica el filtro de foto "natural"
 *     ([filter]): balance de blancos con el papel de la página, curva suave sin quemar las luces, algo de saturación y
 *     vibrance, enfoque ligero. En B/N, lo mismo en escala de grises.
 *  3. Cada foto se pega encima en su sitio con un fundido de [FEATHER] px HACIA DENTRO: fuera de los recuadros la salida
 *     es la capa base píxel a píxel.
 * Sólo se procesan los recortes (coste proporcional al área de las fotos).
 */
object PhotoLayer {

    /** Fundido del borde (px de salida, hacia dentro del recuadro). */
    const val FEATHER = 1
    /** El papel de referencia queda en este nivel (no 255: las luces de la foto conservan margen y no se queman). */
    const val PAPER_TARGET = 246.0
    /** Contraste de la curva suave (mezcla con smoothstep). */
    const val CURVE = 0.18
    /** Hombro de las luces: por encima de [SHOULDER] se comprime en vez de recortar. */
    const val SHOULDER = 0.86
    const val SATURATION = 1.06
    /** Vibrance: más saturación a los colores apagados que a los ya vivos. */
    const val VIBRANCE = 0.22
    const val SHARP_AMOUNT = 0.4
    const val SHARP_SIGMA = 1.0

    /**
     * Blanco del papel (RGB) en la imagen ORIGINAL [orig]: píxeles que en la normalizada [norm] (misma geometría, otra
     * escala) son papel liso (gris ≥ 0,95·[pm], casi sin color), percentil 60 por canal. Null si no hay bastante papel.
     */
    fun paperWhite(orig: Mat, norm: Mat, pm: Double): DoubleArray? = MatBag().use { bag ->
        val n = bag.mat(); Cv.downscale(norm, n, 600)
        val o = bag.mat(); Imgproc.resize(orig, o, n.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        val g = bag.add(Cv.gray(n))
        val m = bag.mat(); Imgproc.threshold(g, m, 0.95 * pm, 255.0, Imgproc.THRESH_BINARY); m.convertTo(m, CvType.CV_8U)
        val ch = ArrayList<Mat>(3); Core.split(n, ch); ch.forEach { bag.add(it) }
        val mx = bag.mat(); Core.max(ch[0], ch[1], mx); Core.max(mx, ch[2], mx)
        val mn = bag.mat(); Core.min(ch[0], ch[1], mn); Core.min(mn, ch[2], mn)
        val c = bag.mat(); Core.subtract(mx, mn, c)
        val lowC = bag.mat(); Imgproc.threshold(c, lowC, 14.0, 255.0, Imgproc.THRESH_BINARY_INV); lowC.convertTo(lowC, CvType.CV_8U)
        Core.bitwise_and(m, lowC, m)
        if (Core.countNonZero(m) < 0.03 * m.total()) return@use null
        val oc = ArrayList<Mat>(3); Core.split(o, oc); oc.forEach { bag.add(it) }
        DoubleArray(3) { k -> max(60.0, Cv.percentile(Cv.histogram(oc[k], m), 0.6).toDouble()) }
    }

    /**
     * Pega las fotografías [rects] (px de [out], 8UC3 RGB o 8UC1) recortadas de [orig] (RGB, misma geometría, cualquier
     * escala) con el filtro de foto; [white] = blanco del papel en [orig] (o null: el del propio recorte).
     */
    fun paste(out: Mat, orig: Mat, rects: List<Rect>, white: DoubleArray?) {
        for (r0 in rects) {
            val r = Cv.clampRect(r0, out.cols(), out.rows())
            if (r.width < 4 || r.height < 4) continue
            MatBag().use { bag ->
                // recorte con unos píxeles de contexto (filtros) que no se escriben
                val pad = 4
                val e = Cv.clampRect(Rect(r.x - pad, r.y - pad, r.width + 2 * pad, r.height + 2 * pad), out.cols(), out.rows())
                val sx = orig.cols().toDouble() / out.cols(); val sy = orig.rows().toDouble() / out.rows()
                val o = Cv.clampRect(Rect((e.x * sx).toInt(), (e.y * sy).toInt(), max(1, (e.width * sx).roundToInt()), max(1, (e.height * sy).roundToInt())), orig.cols(), orig.rows())
                if (o.width <= 0 || o.height <= 0) return@use
                val src = bag.mat()
                val os = bag.add(orig.submat(o))
                val o3 = if (os.channels() == 4) bag.mat().also { Imgproc.cvtColor(os, it, Imgproc.COLOR_RGBA2RGB) } else os
                Imgproc.resize(o3, src, e.size(), 0.0, 0.0, if (sx < 1 || sy < 1) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA)
                val f = filter(src, white ?: cropWhite(src), bag)
                val patch = if (out.channels() == 1) bag.mat().also { Imgproc.cvtColor(f, it, Imgproc.COLOR_RGB2GRAY) } else f
                val inner = bag.add(patch.submat(r.y - e.y, r.y - e.y + r.height, r.x - e.x, r.x - e.x + r.width))
                blendInto(out, r, inner, bag)
            }
        }
    }

    /** Copia [patch] en [r] de [out] con un fundido de [FEATHER] px hacia dentro (nada fuera de [r]). */
    private fun blendInto(out: Mat, r: Rect, patch: Mat, bag: MatBag) {
        val dst = bag.add(out.submat(r))
        if (FEATHER <= 0 || r.width <= 2 * FEATHER + 2 || r.height <= 2 * FEATHER + 2) { patch.copyTo(dst); return }
        val old = bag.mat(); dst.copyTo(old)
        patch.copyTo(dst)
        // bandas del borde: mezcla con la capa base (peso de la foto (k + 1) / (FEATHER + 1) en la k-ésima línea)
        for (k in 0 until FEATHER) {
            val a = (k + 1.0) / (FEATHER + 1)
            for (band in listOf(Rect(k, k, r.width - 2 * k, 1), Rect(k, r.height - 1 - k, r.width - 2 * k, 1),
                Rect(k, k + 1, 1, r.height - 2 * k - 2), Rect(r.width - 1 - k, k + 1, 1, r.height - 2 * k - 2))) {
                if (band.width <= 0 || band.height <= 0) continue
                val d = dst.submat(band); val p = patch.submat(band); val b = old.submat(band)
                Core.addWeighted(p, a, b, 1 - a, 0.0, d)
                d.release(); p.release(); b.release()
            }
        }
    }

    /** Blanco de reserva: percentil 99 por canal del propio recorte (sin papel de referencia). */
    private fun cropWhite(src: Mat): DoubleArray {
        val ch = ArrayList<Mat>(3); Core.split(src, ch)
        val w = DoubleArray(3) { k -> max(60.0, Cv.percentile(Cv.histogram(ch[k]), 0.99).toDouble()) }
        ch.forEach { it.release() }
        return w
    }

    /**
     * Filtro de foto "natural" sobre [src] (RGB 8UC3): balance de blancos (el papel [white] -> [PAPER_TARGET]), curva
     * suave con hombro en las luces (no se queman ni se lavan los cielos o las paredes claras), punto negro leve,
     * saturación y vibrance moderadas y enfoque ligero. Devuelve un Mat nuevo (en [bag]).
     */
    internal fun filter(src: Mat, white: DoubleArray, bag: MatBag): Mat {
        val f = bag.mat(); src.convertTo(f, CvType.CV_32FC3, 1.0 / 255)
        // des-ruido leve (grano de la cámara y del papel)
        val dn = bag.mat(); Imgproc.bilateralFilter(f, dn, 5, 0.05, 3.0)
        Core.multiply(dn, Scalar(PAPER_TARGET / white[0], PAPER_TARGET / white[1], PAPER_TARGET / white[2]), dn)
        // punto negro: percentil 0,5 % de la luminancia (como mucho 0,08)
        val g = bag.mat(); Imgproc.cvtColor(dn, g, Imgproc.COLOR_RGB2GRAY)
        val g8 = bag.mat(); g.convertTo(g8, CvType.CV_8U, 255.0)
        val bp = min(0.08, Cv.percentile(Cv.histogram(g8), 0.005) / 255.0 * 0.6)
        val n = dn.rows() * dn.cols()
        val px = FloatArray(n * 3); dn.get(0, 0, px)
        for (i in 0 until n * 3) px[i] = PhotoMath.tone(px[i].toDouble(), bp).toFloat()
        // saturación y vibrance en el plano de croma (alrededor de la luminancia)
        for (i in 0 until n) {
            val r = px[i * 3]; val gg = px[i * 3 + 1]; val b = px[i * 3 + 2]
            val l = 0.299f * r + 0.587f * gg + 0.114f * b
            val mx = max(r, max(gg, b)); val mn = min(r, min(gg, b))
            val s = if (mx > 1e-4f) (mx - mn) / mx else 0f
            val k = PhotoMath.chromaGain(s.toDouble()).toFloat()
            var nr = l + (r - l) * k; var ng = l + (gg - l) * k; var nb = l + (b - l) * k
            // (sin salirse del rango: se reduce la ganancia en vez de recortar un canal -cambiaría el tono-)
            val hi = max(nr, max(ng, nb)); val lo = min(nr, min(ng, nb))
            if (hi > 1f || lo < 0f) {
                val kk = min(if (hi > 1f) (1f - l) / max(1e-4f, hi - l) else 1f, if (lo < 0f) l / max(1e-4f, l - lo) else 1f).coerceIn(0f, 1f)
                nr = l + (nr - l) * kk; ng = l + (ng - l) * kk; nb = l + (nb - l) * kk
            }
            px[i * 3] = nr; px[i * 3 + 1] = ng; px[i * 3 + 2] = nb
        }
        dn.put(0, 0, px)
        // enfoque ligero (máscara de enfoque)
        val bl = bag.mat(); Imgproc.GaussianBlur(dn, bl, Size(0.0, 0.0), SHARP_SIGMA)
        Core.addWeighted(dn, 1.0 + SHARP_AMOUNT, bl, -SHARP_AMOUNT, 0.0, dn)
        val out = bag.mat(); dn.convertTo(out, CvType.CV_8UC3, 255.0)
        return out
    }
}

/** Partes puras (testeables sin OpenCV) del filtro de foto ([PhotoLayer.filter]). */
internal object PhotoTone {
    fun smooth(x: Double): Double = x * x * (3 - 2 * x)
}

/** Curvas del filtro de foto. */
internal fun PhotoMath.tone(x0: Double, blackPoint: Double): Double {
    // punto negro leve
    var x = ((x0 - blackPoint) / (1 - blackPoint))
    // hombro: las luces por encima de SHOULDER se comprimen hacia 1 (nunca se recortan de golpe)
    val s = PhotoLayer.SHOULDER
    if (x > s) x = s + (1 - s) * (1 - exp(-(x - s) / (1 - s)))
    x = x.coerceIn(0.0, 1.0)
    // curva suave (S ligera)
    return (1 - PhotoLayer.CURVE) * x + PhotoLayer.CURVE * PhotoTone.smooth(x)
}

/** Ganancia del croma para una saturación [s] (0..1): saturación global + vibrance (más a lo apagado). */
internal fun PhotoMath.chromaGain(s: Double): Double = PhotoLayer.SATURATION + PhotoLayer.VIBRANCE * (1 - s.coerceIn(0.0, 1.0))

