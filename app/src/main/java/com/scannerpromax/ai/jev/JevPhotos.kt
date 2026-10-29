package com.scannerpromax.ai.jev

import com.scannerpromax.imaging.Cv
import com.scannerpromax.imaging.MatBag
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * FOTOS de la página rectificada con Jev. Box da UNA caja por pregunta, así que en UNA sola petición (las preguntas
 * extra cuestan ~22 tokens; la imagen, ~3 000, se cobra una vez) se pregunta:
 *  - `count` (choice 0/1/2/3/4/5+): cuántas fotos/figuras hay;
 *  - `any` (noul): ¿hay alguna? (Box siempre devuelve una caja aunque no haya nada);
 *  - `b0`…`b5` (box): la 1.ª, 2.ª… en orden de lectura (instrucciones ordinales).
 * Se aceptan las [count] primeras cajas distintas y cada una se AJUSTA localmente ([refine]) contra el contenido
 * real (la caja de Jev viene cuantizada a 0,01 y algo holgada). El resultado (fracciones x0, y0, x1, y1) entra por
 * `layoutImages` -> `NaturalColor.Inputs.images` (filtro de foto, sin trocear).
 */
object JevPhotos {

    const val MAX_BOXES = 6
    /** Densidad de contenido mínima para ajustar los bordes localmente ([refine]). */
    @JvmField var FULL_DENSITY = 0.6
    private val ORD = listOf("1st", "2nd", "3rd", "4th", "5th", "6th")
    private const val WHAT = "pictures (photographs, illustrations, drawings, diagrams, charts, maps, logos or stamps; " +
        "not plain text, not tables, not empty colored boxes)"

    /** Página reducida ([page] RGB/RGBA rectificada) -> imagen + petición. */
    fun request(page: Mat, maxSide: Int = 1024): Pair<JevImage, JevRequest> {
        val img = JevImages.encode(page, maxSide, 75, prefix = "page")
        return img to request(img)
    }

    fun request(img: JevImage): JevRequest {
        val q = LinkedHashMap<String, JevQuestion>()
        q["count"] = JevQuestion.Choice(
            "How many separate $WHAT are on the document page in `page`?",
            linkedMapOf("0" to "None.", "1" to "Exactly one.", "2" to "Exactly two.", "3" to "Exactly three.", "4" to "Exactly four.", "5+" to "Five or more."),
        )
        q["any"] = JevQuestion.Noul("Does the document page in `page` contain at least one of these $WHAT?")
        for (i in 0 until MAX_BOXES) q["b$i"] = JevQuestion.Box(
            "the ${ORD[i]} picture in reading order (top to bottom; pictures side by side count left to right) " +
                "among the $WHAT on the page in `page`; the box must cover the whole picture and nothing else",
        )
        return JevRequest(buildJsonObject { put("page", JsonPrimitive(img.asset.ref)); put("task", "Find the pictures on a scanned document page.") }, q, listOf(img.asset))
    }

    /** Resultado: cajas en fracciones de la página ORIGINAL (sin ajustar) y lo que se decidió. */
    class Found(val boxes: List<JevBox>, val count: Int, val pAny: Double, val confidences: List<Double>)

    /** Respuesta -> cajas (antes del ajuste local). */
    fun parse(r: JevResponse, fit: JevFit, minAny: Double = 0.5): Found {
        val cnt = r["count"]
        val pAny = r["any"]?.noul ?: 0.0
        val n = when (val c = cnt?.choice) { null -> 0; "5+" -> MAX_BOXES; else -> c.toIntOrNull() ?: 0 }
        if (n == 0 || pAny < minAny) return Found(emptyList(), n, pAny, emptyList())
        val out = ArrayList<JevBox>(); val conf = ArrayList<Double>()
        for (i in 0 until MAX_BOXES) {
            if (out.size >= n) break
            val a = r["b$i"] ?: continue
            val b = fit.toSource(a.box ?: continue)
            if (b.width < 0.02 || b.height < 0.01 || b.area > 0.92) continue
            // una ordinal inexistente repite otra caja: fuera los duplicados
            if (out.any { it.iou(b) > 0.5 || contains(it, b, 0.85) }) continue
            out += b; conf += a.confidence ?: 0.0
        }
        return Found(out, n, pAny, conf)
    }

    private fun contains(outer: JevBox, inner: JevBox, frac: Double): Boolean {
        val iw = min(outer.right, inner.right) - max(outer.left, inner.left)
        val ih = min(outer.bottom, inner.bottom) - max(outer.top, inner.top)
        return iw > 0 && ih > 0 && iw * ih >= frac * inner.area
    }

    // ------------------------------------------------------------------------------------------------ ajuste local

    /**
     * Ajusta [b] (fracciones) al contenido real de [page] (RGB/RGBA): en una ventana algo mayor que la caja, máscara
     * de "no papel" (oscuro respecto del fondo estimado o con color), cierre para unir la textura de la foto, y unión de
     * las componentes GRANDES que caen sobre todo en el núcleo de la caja (el texto vecino son componentes pequeñas o
     * fuera del núcleo). Si el ajuste se aleja demasiado de la caja de Jev, se queda la de Jev.
     */
    fun refine(page: Mat, boxes: List<JevBox>): List<JevBox> {
        if (boxes.isEmpty()) return boxes
        return MatBag().use { bag ->
            val sm = bag.mat(); Cv.downscale(page, sm, 1000)
            val rgb = if (sm.channels() == 4) bag.mat().also { Imgproc.cvtColor(sm, it, Imgproc.COLOR_RGBA2RGB) } else sm
            val mask = contentMask(rgb, bag)
            val w = mask.cols(); val h = mask.rows()
            // puntitos sueltos fuera; textura de la foto unida
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, 3, 3))
            val px = ByteArray(w * h); mask.get(0, 0, px)
            boxes.map { b -> refineOne(px, w, h, b) ?: b }
        }
    }

    /** 255 = contenido (no papel). */
    internal fun contentMask(rgb: Mat, bag: MatBag): Mat {
        val gray = bag.mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val bg = bag.add(Cv.estimateBackground(gray, 128))
        val nv = bag.mat(); Cv.divideByBackground(gray, bg, nv)
        val dark = bag.mat(); Imgproc.threshold(nv, dark, 215.0, 255.0, Imgproc.THRESH_BINARY_INV)
        // color: croma en Lab
        val lab = bag.mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>(3); Core.split(lab, ch); ch.forEach { bag.add(it) }
        val a = bag.mat(); Core.absdiff(ch[1], org.opencv.core.Scalar(128.0), a)
        val bb = bag.mat(); Core.absdiff(ch[2], org.opencv.core.Scalar(128.0), bb)
        val chroma = bag.mat(); Core.add(a, bb, chroma)
        val col = bag.mat(); Imgproc.threshold(chroma, col, 22.0, 255.0, Imgproc.THRESH_BINARY)
        val m = bag.mat(); Core.bitwise_or(dark, col, m)
        return m
    }

    /**
     * Ajuste de cada borde por separado: perfil de densidad de contenido a lo largo de las filas (o columnas) del 70 %
     * central de la caja; el borde va donde más sube la densidad de fuera (papel / texto: baja) a dentro (foto: alta),
     * fuera casi vacío (<= 20 %), a menos de 1,2 % de la página + 7 % de la caja del borde de Jev. Sin un escalón claro se queda el de Jev
     * (gráficos de líneas finas, fotos claras). Dos pasadas (los bordes horizontales usan los verticales ya ajustados).
     */
    private fun refineOne(px: ByteArray, w: Int, h: Int, b: JevBox): JevBox? {
        var x0 = b.left * w; var y0 = b.top * h; var x1 = b.right * w; var y1 = b.bottom * h
        if (x1 - x0 < 12 || y1 - y0 < 12) return null
        // solo fotos "llenas" (densidad alta en el 60 % central): en gráficos, dibujos y productos sobre blanco los
        // escalones interiores engañan y la caja de Jev es mejor
        run {
            val xa = (x0 + 0.2 * (x1 - x0)).toInt().coerceIn(0, w - 1); val xb = (x1 - 0.2 * (x1 - x0)).toInt().coerceIn(0, w - 1)
            val ya = (y0 + 0.2 * (y1 - y0)).toInt().coerceIn(0, h - 1); val yb = (y1 - 0.2 * (y1 - y0)).toInt().coerceIn(0, h - 1)
            var c = 0; var n = 0
            for (y in ya..yb step 2) for (x in xa..xb step 2) { n++; if (px[y * w + x].toInt() != 0) c++ }
            if (n == 0 || c < FULL_DENSITY * n) return null
        }
        repeat(2) {
            val ia = (x0 + 0.15 * (x1 - x0)).toInt(); val ib = (x1 - 0.15 * (x1 - x0)).toInt()
            val ny0 = snap(px, w, h, y0, 1, ia, ib, y1 - y0, true); val ny1 = snap(px, w, h, y1, -1, ia, ib, y1 - y0, true)
            val ja = (ny0 + 0.15 * (ny1 - ny0)).toInt(); val jb = (ny1 - 0.15 * (ny1 - ny0)).toInt()
            val nx0 = snap(px, w, h, x0, 1, ja, jb, x1 - x0, false); val nx1 = snap(px, w, h, x1, -1, ja, jb, x1 - x0, false)
            if (ny1 - ny0 >= 8 && nx1 - nx0 >= 8) { y0 = ny0; y1 = ny1; x0 = nx0; x1 = nx1 }
        }
        val out = JevBox(x0 / w, y0 / h, x1 / w, y1 / h)
        return if (out.iou(b) >= 0.5) out else null
    }

    /** Nueva posición del borde [e] (px). [dir] = +1 si "dentro" está hacia valores mayores. [hor] = borde horizontal. */
    private fun snap(px: ByteArray, w: Int, h: Int, e: Double, dir: Int, sa: Int, sb: Int, dim: Double, hor: Boolean): Double {
        val n = if (hor) h else w; val span = if (hor) w else h
        val a = sa.coerceIn(0, span - 1); val bb = sb.coerceIn(0, span - 1)
        if (bb - a < 4) return e
        val m = (0.012 * n + 0.07 * dim).roundToInt()
        val k = max(4, min((0.015 * n).roundToInt(), (dim / 3).roundToInt()))
        val lo = max(0, e.roundToInt() - m - k); val hi = min(n - 1, e.roundToInt() + m + k)
        if (hi - lo < 2 * k + 2) return e
        // densidad por fila/columna (prefijos para medias por ventana)
        val pre = DoubleArray(hi - lo + 2)
        for (t in lo..hi) {
            var c = 0
            if (hor) { val row = t * w; for (q in a..bb) if (px[row + q].toInt() != 0) c++ }
            else for (q in a..bb) if (px[q * w + t].toInt() != 0) c++
            pre[t - lo + 1] = pre[t - lo] + c.toDouble() / (bb - a + 1)
        }
        fun mean(f: Int, t: Int): Double { val u = max(lo, f); val v = min(hi, t); return if (v < u) 0.0 else (pre[v - lo + 1] - pre[u - lo]) / (v - u + 1) }
        var best = e; var bestStep = 0.3
        for (t in max(lo + k, e.roundToInt() - m)..min(hi - k, e.roundToInt() + m)) {
            val inside = if (dir > 0) mean(t, t + k - 1) else mean(t - k + 1, t)
            val outside = if (dir > 0) mean(t - k, t - 1) else mean(t + 1, t + k)
            val step = inside - outside
            if (inside >= 0.35 && outside <= 0.2 && step > bestStep) { bestStep = step; best = if (dir > 0) t.toDouble() else t + 1.0 }
        }
        return best
    }
}
