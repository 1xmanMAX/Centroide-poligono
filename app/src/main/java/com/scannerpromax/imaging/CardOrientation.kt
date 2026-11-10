package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Orientación de UNA cara del DNI ya rectificada (JVM puro, con banco CardOrientationBenchTest). La cara SIEMPRE queda
 * apaisada (ID-1): si el rectificado sale vertical solo se duda entre 90° y 270°; si sale apaisado, entre 0° y 180°.
 * Señales, como puntos a favor de «derecha» (+) o «del revés» (−) sobre la cara apaisada:
 *  - MRZ ([CardColorDetector.mrzEdge]): 2-4 renglones negros junto a un canto largo; en el DNI peruano va SIEMPRE abajo
 *    (anverso del DNI azul/amarillo, reverso del DNIe). La más fiable.
 *  - CARA de la foto: piel (YCrCb) concentrada en un tercio lateral; en el anverso peruano (azul, amarillo, DNIe 2.0 y
 *    3.0) la foto va a la IZQUIERDA. El reverso no tiene foto en color (la huella es gris): se abstiene.
 *  - RENGLONES ([TextOrientation]): ascendentes frente a descendentes, solo si el análisis está seguro.
 *  - doc_ori (incluido en la app): el modelo de orientación de páginas sobre la cara. En el banco de caras acertó el
 *    sentido 80/80 (razón p0/p180 mínima 1,6): decide solo a partir de 2:1 y nunca contra la MRZ.
 * Voto conservador: se gira 180° solo si la suma pasa de [MIN_SCORE]; si no, la cara se queda como estaba en la foto
 * (lo que el usuario vio al encuadrar). Para 90/270 hay que elegir: el voto, o el giro del móvil al hacer la foto
 * ([deviceUp]) si las señales no deciden, o 270° (móvil girado a la izquierda, lo habitual) en último caso.
 */
object CardOrientation {

    class Decision(
        /** Giro HORARIO a aplicar al rectificado (0/90/180/270). */
        val rotation: Int,
        /** Suma de puntos a favor del giro elegido (≥ [MIN_SCORE] = seguro). */
        val score: Double,
        val why: String,
    ) {
        val sure: Boolean get() = score >= MIN_SCORE
        override fun toString() = "giro=$rotation puntos=%.2f %s".format(Locale.ROOT, score, why)
    }

    /** Puntos de cada señal (a favor de «derecha» si > 0) para el banco y el registro. */
    class Cues(val mrz: Double, val face: Double, val lines: Double, val model: Double, val probs: FloatArray? = null) {
        val total get() = mrz + face + lines + model
        override fun toString() = "mrz=%.1f cara=%.1f renglones=%.1f doc_ori=%.1f".format(Locale.ROOT, mrz, face, lines, model) +
            (probs?.let { p -> " (p0=%.2f p180=%.2f)".format(Locale.ROOT, p[0], p[2]) } ?: "")
    }

    /** Puntos mínimos para girar 180° una cara apaisada (con menos, se queda como en la foto). */
    const val MIN_SCORE = 1.0
    const val W_MRZ = 3.0
    const val W_FACE = 2.0
    const val W_LINES = 0.8
    const val W_MODEL = 1.5

    /** Registro de depuración (tests). */
    @Volatile internal var debug: ((String) -> Unit)? = null

    /** Lado largo de trabajo de la cara. */
    private const val WORK = 900

    /**
     * Giro que endereza la cara rectificada [card] (RGB o RGBA, vertical u horizontal). [model] = doc_ori sobre una cara
     * RGB apaisada (probabilidades de que TENGA giro 0/90/180/270) o null. [deviceUp] = giro horario que pedía la
     * posición del móvil al hacer la foto (0/90/180/270; -1 = desconocido): solo desempata.
     */
    fun decide(card: Mat, model: ((Mat) -> FloatArray?)? = null, deviceUp: Int = -1): Decision {
        val small = Mat()
        try {
            Cv.downscale(card, small, WORK)
            val rgb = Mat()
            when (small.channels()) {
                4 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
                1 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_GRAY2RGB)
                else -> small.copyTo(rgb)
            }
            val portrait = rgb.rows() > rgb.cols()
            // se lleva a apaisada girando 90° horario: los candidatos son base y base+180
            val land = Mat()
            if (portrait) Core.rotate(rgb, land, Core.ROTATE_90_CLOCKWISE) else rgb.copyTo(land)
            rgb.release()
            try {
                val base = if (portrait) 90 else 0
                val c = cues(land, model)
                val s = c.total
                // posición del móvil traducida al voto de la cara apaisada
                val dev = if (deviceUp < 0) 0.0 else when ((deviceUp - base + 360) % 360) { 0 -> 0.6; 180 -> -0.6; else -> 0.0 }
                return when {
                    s >= MIN_SCORE -> Decision(base, s, "$c -> derecha")
                    s <= -MIN_SCORE -> Decision((base + 180) % 360, -s, "$c -> del revés")
                    !portrait -> Decision(0, abs(s), "$c -> sin señal: como en la foto")
                    // vertical: hay que elegir uno de los dos
                    s + dev > 0.0 -> Decision(90, s + dev, "$c móvil=$deviceUp -> 90")
                    s + dev < 0.0 -> Decision(270, -(s + dev), "$c móvil=$deviceUp -> 270")
                    else -> Decision(270, 0.0, "$c -> sin señal: 270 (móvil girado a la izquierda)")
                }
            } finally {
                land.release()
            }
        } finally {
            small.release()
        }
    }

    /** Señales sobre la cara apaisada [land] (RGB). */
    internal fun cues(land: Mat, model: ((Mat) -> FloatArray?)?): Cues {
        val mrz = when (runCatching { CardColorDetector.mrzEdge(land) }.getOrDefault(-1)) { 2 -> W_MRZ; 0 -> -W_MRZ; else -> 0.0 }
        val face = runCatching { faceSide(land) }.getOrDefault(0.0) * W_FACE
        val lines = runCatching {
            val g = Cv.gray(land)
            try {
                val r = TextOrientation.estimate(g)
                if (r.sure && r.vertical == false) (if (r.rotation == 0) W_LINES else if (r.rotation == 180) -W_LINES else 0.0) else 0.0
            } finally { g.release() }
        }.getOrDefault(0.0)
        val probs = model?.let { f -> runCatching { f(land) }.getOrNull()?.takeIf { p -> p.size >= 4 && p.all { it.isFinite() } } }
        val m = probs?.let { p -> modelScore(p[0].toDouble(), p[2].toDouble()) } ?: 0.0
        return Cues(mrz, face, lines, m, probs)
    }

    /**
     * Puntos de doc_ori: log(p(0°)/p(180°)) con 1 punto (= [MIN_SCORE], decide solo) a una razón de 2:1 y como mucho
     * [W_MODEL]; nada si ninguno de los dos llega a 0,45. Banco de caras: 80/80 aciertos, razón mínima 1,6.
     */
    internal fun modelScore(p0: Double, p180: Double): Double =
        if (max(p0, p180) < 0.45) 0.0 else (ln((p0 + 1e-3) / (p180 + 1e-3)) / ln(2.0)).coerceIn(-W_MODEL, W_MODEL)

    /**
     * ¿En qué lado está la cara de la foto? +1 = a la izquierda (anverso derecho), −1 = a la derecha (del revés),
     * entre medias según lo claro que sea, 0 = sin cara. Piel en YCrCb (Cr 136-175, Cb 80-128), mancha compacta.
     */
    internal fun faceSide(land: Mat): Double {
        val w = land.cols(); val h = land.rows()
        if (w < 60 || h < 40) return 0.0
        val ycc = Mat(); Imgproc.cvtColor(land, ycc, Imgproc.COLOR_RGB2YCrCb)
        val mask = Mat()
        Core.inRange(ycc, Scalar(45.0, 136.0, 80.0), Scalar(240.0, 175.0, 128.0), mask)
        val yccPx = ByteArray(w * h * 3); ycc.get(0, 0, yccPx)
        ycc.release()
        /** ¿Fondo de FOTO alrededor de la caja (casi sin color, como el fondo claro de las fotos de RENIEC; la sombra lo oscurece)? */
        fun photoBackdrop(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            val mx = (x1 - x0) / 4; val my = (y1 - y0) / 6
            val ax = max(0, x0 - mx); val bx = min(w - 1, x1 + mx); val ay = max(0, y0 - my); val by = min(h - 1, y1 + my)
            var n = 0; var sy = 0.0; var sc = 0.0
            for (y in ay..by step 2) for (x in ax..bx step 2) {
                if (x in x0..x1 && y in y0..y1) continue
                val o = (y * w + x) * 3
                sy += yccPx[o].toInt() and 0xFF
                sc += abs((yccPx[o + 1].toInt() and 0xFF) - 128) + abs((yccPx[o + 2].toInt() and 0xFF) - 128)
                n++
            }
            if (n == 0) return false
            debug?.invoke("  fondo: Y=%.0f croma=%.0f".format(Locale.ROOT, sy / n, sc / n))
            return sy / n > 80 && sc / n < 28
        }
        val k = max(3, w / 150)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        val labels = Mat(); val stats = Mat(); val cent = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, cent, 8, CvType.CV_32S)
        mask.release(); labels.release()
        val area = w.toDouble() * h
        var left = 0.0; var right = 0.0
        try {
            for (i in 1 until n) {
                val a = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (a < area * 0.006) continue
                val bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]; val bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]
                // compacta (una cara, no una franja de color de la tarjeta) y no enorme
                if (bw > w * 0.35 || bh > h * 0.85 || a < 0.35 * bw * bh || a > area * 0.2) continue
                // la cara de la foto está DENTRO de la tarjeta; los dedos que la sujetan tocan el canto (piel también)
                val bx = stats.get(i, Imgproc.CC_STAT_LEFT)[0]; val by = stats.get(i, Imgproc.CC_STAT_TOP)[0]
                if (bx < w * 0.025 || by < h * 0.04 || bx + bw > w * 0.975 || by + bh > h * 0.96) continue
                // forma de cara: elipse (relleno ~0,79, no un rectángulo como el chip dorado) más alta que ancha (no una
                // franja de texto rojo); en la cara apaisada la altura de la foto va en el eje corto
                val fill = a / (bw * bh)
                if (fill < 0.55 || fill > 0.9 || bh < 1.15 * bw) continue
                // y alrededor, el fondo claro de la foto (una huella sobre la tarjeta amarilla, desenfocada, también es «piel»)
                if (!photoBackdrop(bx.toInt(), by.toInt(), (bx + bw).toInt(), (by + bh).toInt())) continue
                val cx = cent.get(i, 0)[0] / w
                debug?.invoke("piel: área=%.3f caja=%.2fx%.2f en (%.2f,%.2f) relleno=%.2f".format(Locale.ROOT, a / area, bw / w, bh / h, bx / w, by / h, a / (bw * bh)))
                when {
                    cx < 0.4 -> left += a
                    cx > 0.6 -> right += a
                }
            }
        } finally {
            stats.release(); cent.release()
        }
        val tot = left + right
        if (tot < area * 0.012) return 0.0
        val d = (left - right) / tot
        return if (abs(d) < 0.5) 0.0 else d
    }

    /**
     * Esquinas [q] (TL, TR, BR, BL; píxeles de [rgb]) reordenadas para que el rectificado salga apaisado y derecho
     * según [decide]. Devuelve el par (esquinas, decisión); sin cambios devuelve el mismo [q].
     */
    fun orient(rgb: Mat, q: DoubleArray, model: ((Mat) -> FloatArray?)? = null, deviceUp: Int = -1): Pair<DoubleArray, Decision> {
        val cw = clockwise(q)
        val warped = CardColorDetector.warpCard(rgb, cw, WORK)
        val d = try { decide(warped, model, deviceUp) } finally { warped.release() }
        return shift(cw, d.rotation) to d
    }

    /** Las mismas esquinas empezando por otra: el rectificado sale girado [rotation]° en sentido horario. */
    fun shift(q: DoubleArray, rotation: Int): DoubleArray {
        val k = (4 - ((rotation / 90) % 4 + 4) % 4) % 4
        if (k == 0) return q
        return DoubleArray(8) { i -> q[((i / 2 + k) % 4) * 2 + i % 2] }
    }

    /**
     * Mismo recorte en sentido HORARIO (en la imagen, y hacia abajo) conservando la esquina inicial: un recorte en
     * sentido antihorario (esquinas arrastradas «cruzadas» a mano) daría la cara en ESPEJO.
     */
    fun clockwise(q: DoubleArray): DoubleArray {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        if (s >= 0) return q
        // TL, BL, BR, TR -> TL, TR, BR, BL
        val order = intArrayOf(0, 3, 2, 1)
        return DoubleArray(8) { i -> q[order[i / 2] * 2 + i % 2] }
    }

    /** Lado mínimo de una cara para analizarla. */
    internal fun minSide(m: Mat) = min(m.cols(), m.rows())
}
