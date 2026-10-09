package com.scannerpromax.imaging

import android.graphics.Bitmap
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tubería completa de una página: original -> recorte/perspectiva -> rotación -> enderezado de hoja curvada
 * ([GridDewarp]) o, si no aplica, deskew -> filtro -> limpieza (líneas/ruido) -> borrado manual.
 *
 * Internamente todo trabaja sobre Mats (una sola conversión Bitmap->Mat al inicio y Mat->Bitmap al final)
 * y libera cada intermedio en cuanto deja de usarse.
 */
class PageProcessor(val tier: DeviceTier) {
    val detector = DocumentDetector(tier)

    init {
        // Hilos de OpenCV acordes al equipo (en gama baja queda un núcleo libre para la interfaz)
        Cv.configureThreads(tier)
    }

    /** Caché de una entrada: el original reducido para vistas previas (los sliders llaman muchas veces). */
    private class PreviewSource(val ref: WeakReference<Bitmap>, val generation: Int, val scale: Double, val mat: Mat)
    private var previewSource: PreviewSource? = null
    private val previewLock = Any()

    /** Renderizado final a calidad completa. */
    fun render(original: Bitmap, edits: PageEdits, progress: ProgressCallback? = null): Bitmap {
        progress?.invoke(0f)
        val geo = geometryFull(original, edits, maxSide = 0)
        progress?.invoke(0.3f)
        val out = finish(geo, edits, ImageEnhancer.Options.of(tier, fast = false), progress)
        val bmp = Cv.toBitmap(out)
        out.release()
        progress?.invoke(1f)
        return bmp
    }

    /** Vista previa rápida (reduce a [maxSide] antes de procesar) para sliders/filtros en tiempo real. */
    fun preview(original: Bitmap, edits: PageEdits, maxSide: Int = 1200): Bitmap {
        val geo = geometryPreview(original, edits, maxSide)
        val out = finish(geo, edits, ImageEnhancer.Options.of(tier, fast = true), null)
        val bmp = Cv.toBitmap(out)
        out.release()
        return bmp
    }

    /** Imagen recortada+rotada SIN filtro (base donde el usuario dibuja los trazos de borrado). */
    fun geometryOnly(original: Bitmap, edits: PageEdits, maxSide: Int = 0): Bitmap {
        val geo = if (maxSide > 0) geometryPreview(original, edits, maxSide) else geometryFull(original, edits, 0)
        val bmp = Cv.toBitmap(geo)
        geo.release()
        return bmp
    }

    /** Libera la caché de vista previa (llamar al salir del editor). */
    fun clearPreviewCache() {
        synchronized(previewLock) {
            previewSource?.mat?.release()
            previewSource = null
        }
    }

    // =====================================================================================
    // Geometría
    // =====================================================================================

    /**
     * Geometría a resolución completa (limitada por el tier). Devuelve RGB 8UC3.
     *
     * Perspectiva + rotación de 90° + enderezado se COMPONEN en una sola matriz (M = Rdeskew · R90 · H) y se
     * aplica un único warpPerspective: un solo remuestreo (el texto fino no se ablanda dos veces) y una sola
     * copia a resolución completa en el pico de memoria. El ángulo de enderezado se estima sobre una versión
     * reducida ya rectificada (estimateSkew trabaja a 800 px de todos modos).
     */
    private fun geometryFull(original: Bitmap, edits: PageEdits, maxSide: Int): Mat =
        geometryFullMat(Cv.toRgba(original), edits, maxSide)

    /** [geometryFull] sobre un Mat RGBA (que se libera). */
    internal fun geometryFullMat(input: Mat, edits: PageEdits, maxSide: Int): Mat {
        var rgba: Mat? = input
        try {
            val src = rgba!!
            val w = src.cols(); val h = src.rows()
            val quad = edits.quad?.takeIf { !PerspectiveCorrector.isFullFrame(it, w, h) }
            val rot = ((edits.rotation % 360) + 360) % 360
            // Estimaciones sobre una versión reducida ya rectificada y rotada: malla de líneas (hoja curvada) y,
            // si no aplica, ángulo de enderezado. El enderezado curvo ya deja las líneas a escuadra: no se
            // combina con el deskew.
            var model: GridDewarp.Model? = null
            var angle = 0.0
            if (edits.autoDewarp || edits.autoDeskew) {
                val red = reducedRectified(src, quad, rot, if (edits.autoDewarp) GridDewarp.EST_SIDE else 1000)
                try {
                    if (edits.autoDewarp) model = dewarpModel(red, quadKey(quad, w, h), rot, cacheable = true)
                    if (model == null && edits.autoDeskew) angle = Cleanup.estimateSkew(red)
                } finally {
                    red.release()
                }
            }
            if (quad == null && angle == 0.0 && model == null) {
                // Sin warp: reducción INTER_AREA (si hace falta) + rotación sin pérdidas
                val warped = cropMat(src, null, tier.maxWorkingPixels, maxSide)
                src.release(); rgba = null
                return orient(warped, edits.copy(autoDeskew = false, autoDewarp = false), null)
            }
            // 1) Homografía (o escala) a la resolución de trabajo
            val hm: DoubleArray; var ow: Int; var oh: Int; val interp: Int
            if (quad != null) {
                val hg = PerspectiveCorrector.homography(quad, tier.maxWorkingPixels, maxSide, w, h)
                hm = hg.m; ow = hg.width; oh = hg.height; interp = hg.interp
            } else {
                var sc = 1.0
                val mp = tier.maxWorkingPixels
                if (mp > 0 && w.toLong() * h > mp) sc = min(sc, sqrt(mp.toDouble() / (w.toDouble() * h)))
                if (maxSide > 0 && max(w, h) > maxSide) sc = min(sc, maxSide.toDouble() / max(w, h))
                hm = doubleArrayOf(sc, 0.0, 0.0, 0.0, sc, 0.0, 0.0, 0.0, 1.0)
                ow = max(1, (w * sc).roundToInt()); oh = max(1, (h * sc).roundToInt())
                interp = if (sc < 0.6) Imgproc.INTER_LINEAR else Imgproc.INTER_CUBIC
            }
            // 2) Rotación de 90° (misma convención que Core.rotate)
            val r90 = when (rot) {
                90 -> doubleArrayOf(0.0, -1.0, oh - 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
                180 -> doubleArrayOf(-1.0, 0.0, ow - 1.0, 0.0, -1.0, oh - 1.0, 0.0, 0.0, 1.0)
                270 -> doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, ow - 1.0, 0.0, 0.0, 1.0)
                else -> null
            }
            var mTot = hm
            if (r90 != null) {
                mTot = mul3(r90, mTot)
                if (rot == 90 || rot == 270) { val t = ow; ow = oh; oh = t }
            }
            if (model != null) {
                // Perspectiva + rotación + hoja curvada: un único remap desde el original (por franjas)
                val out = GridDewarp.remapComposed(src, model, ow, oh, inv3(mTot), interp)
                src.release(); rgba = null
                val rgb = Mat()
                try {
                    Imgproc.cvtColor(out, rgb, Imgproc.COLOR_RGBA2RGB)
                } finally {
                    out.release()
                }
                if (quad != null) cleanWedges(rgb, edits)
                return rgb
            }
            // 3) Enderezado alrededor del centro con el lienzo AMPLIADO (como Cleanup.rotateExpand): las esquinas del
            //    documento no se recortan (el texto pegado al borde se conservaba sólo a medias)
            var rd: DoubleArray? = null
            val preW = ow; val preH = oh
            if (angle != 0.0) {
                val rm = Imgproc.getRotationMatrix2D(org.opencv.core.Point(ow / 2.0, oh / 2.0), angle, 1.0)
                val a = DoubleArray(6); rm.get(0, 0, a); rm.release()
                val (nw, nh) = Cleanup.rotatedCanvas(ow, oh, angle)
                a[2] += (nw - ow) / 2.0; a[5] += (nh - oh) / 2.0
                ow = nw; oh = nh
                rd = doubleArrayOf(a[0], a[1], a[2], a[3], a[4], a[5], 0.0, 0.0, 1.0)
                mTot = mul3(rd, mTot)
            }
            val m = Mat(3, 3, CvType.CV_64F); m.put(0, 0, *mTot)
            val out = Mat(oh, ow, src.type())
            Imgproc.warpPerspective(src, out, m, Size(ow.toDouble(), oh.toDouble()), interp, Core.BORDER_REPLICATE)
            m.release()
            src.release(); rgba = null
            val rgb = Mat()
            Imgproc.cvtColor(out, rgb, Imgproc.COLOR_RGBA2RGB)
            out.release()
            // Esquinas que el enderezado deja fuera del documento -> color del papel (no la mesa del fondo)
            if (rd != null) fillOutsideRotated(rgb, rd, preW, preH)
            if (quad != null) cleanWedges(rgb, edits)
            return rgb
        } finally {
            rgba?.release()
        }
    }

    /**
     * Versión reducida (INTER_AREA, lado largo del documento ≈ [side]) rectificada y rotada: base de las
     * estimaciones (hoja curvada, ángulo). Devuelve RGB.
     */
    private fun reducedRectified(rgba: Mat, quad: Quad?, rot: Int, side: Int): Mat = MatBag().use { bag ->
        val w = rgba.cols(); val h = rgba.rows()
        val (qw, qh) = quad?.let { PerspectiveCorrector.quadExtent(it) } ?: (w.toDouble() to h.toDouble())
        val sc = min(1.0, side / max(1.0, max(qw, qh)))
        val small = bag.mat()
        if (sc < 1.0) {
            Imgproc.resize(rgba, small, Size(max(1.0, (w * sc).roundToInt().toDouble()), max(1.0, (h * sc).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
        } else rgba.copyTo(small)
        val q = quad?.let { PerspectiveCorrector.scaleQuad(it, small.cols().toDouble() / w, small.rows().toDouble() / h) }
        val warped = bag.add(cropMat(small, q, 0, side))
        val rgb = bag.mat()
        Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)
        val code = when (rot) { 90 -> Core.ROTATE_90_CLOCKWISE; 180 -> Core.ROTATE_180; 270 -> Core.ROTATE_90_COUNTERCLOCKWISE; else -> -1 }
        if (code >= 0) Mat().also { Core.rotate(rgb, it, code) } else rgb.clone()
    }

    // ---------------------------------------------------------------------------------
    // Caché del modelo de hoja curvada: vista previa, base de los trazos y render final usan EL MISMO modelo
    // (normalizado), así lo que se ve y lo que se borra coincide con el resultado.
    // ---------------------------------------------------------------------------------

    private class DewarpEntry(val quad: List<Int>?, val rot: Int, val sig: FloatArray, val model: GridDewarp.Model?)
    private val dewarpCache = ArrayDeque<DewarpEntry>()
    private val dewarpLock = Any()

    /** Quad normalizado al tamaño de la imagen (milésimas): igual para el original y sus versiones reducidas. */
    private fun quadKey(quad: Quad?, w: Int, h: Int): List<Int>? = quad?.points()?.flatMap {
        listOf((it.x / w * 1000).roundToInt(), (it.y / h * 1000).roundToInt())
    }

    /** Firma 8x8 de grises del documento rectificado (distingue páginas con el mismo recorte). */
    private fun signature(rgb: Mat): FloatArray = MatBag().use { bag ->
        val g = bag.add(Cv.gray(rgb))
        val s = bag.mat()
        Imgproc.resize(g, s, Size(8.0, 8.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val b = ByteArray(64); s.get(0, 0, b)
        FloatArray(64) { (b[it].toInt() and 0xFF).toFloat() }
    }

    /**
     * Modelo de [GridDewarp] para el documento rectificado [rgb] (null = no corregir). Se reutiliza el de la caché
     * si coinciden recorte, rotación y firma; sólo se guardan estimaciones hechas con resolución suficiente (las
     * miniaturas no fijan el modelo del render final).
     */
    private fun dewarpModel(rgb: Mat, quad: List<Int>?, rot: Int, cacheable: Boolean): GridDewarp.Model? {
        val sig = signature(rgb)
        synchronized(dewarpLock) {
            dewarpCache.firstOrNull { e ->
                e.rot == rot && e.quad == quad && e.sig.indices.all { abs(e.sig[it] - sig[it]) <= 8f }
            }?.let { return it.model }
        }
        val model = GridDewarp.estimate(rgb)
        if (cacheable && max(rgb.cols(), rgb.rows()) >= 900) {
            synchronized(dewarpLock) {
                dewarpCache.addFirst(DewarpEntry(quad, rot, sig, model))
                while (dewarpCache.size > 6) dewarpCache.removeLast()
            }
        }
        return model
    }

    /**
     * Cuñas del fondo que el recorte recto deja junto a los bordes curvos de la hoja ([EdgeWedges]): sólo en páginas
     * RECORTADAS (sin recorte no hay cuñas: el fondo es parte de la foto que el usuario eligió) con el enderezado
     * automático activo (no en DNI/tarjetas, cuyo diseño llega hasta el canto).
     */
    private fun cleanWedges(rgb: Mat, edits: PageEdits) {
        if (edits.autoDewarp || edits.autoDeskew) runCatching { Cleanup.fillEdgeWedges(rgb) }
    }

    /** Rellena con el color del papel lo que queda fuera del rectángulo [srcW] x [srcH] girado por [rd] (3x3). */
    private fun fillOutsideRotated(img: Mat, rd: DoubleArray, srcW: Int, srcH: Int) {
        val w = img.cols(); val h = img.rows()
        val sw = srcW.toDouble(); val sh = srcH.toDouble()
        val corners = arrayOf(0.0 to 0.0, sw - 1.0 to 0.0, sw - 1.0 to sh - 1.0, 0.0 to sh - 1.0)
        val pts = corners.map { (x, y) -> org.opencv.core.Point(rd[0] * x + rd[1] * y + rd[2], rd[3] * x + rd[4] * y + rd[5]) }
        val mask = Mat(h, w, CvType.CV_8UC1, org.opencv.core.Scalar(255.0))
        val poly = org.opencv.core.MatOfPoint(*pts.toTypedArray())
        try {
            Imgproc.fillConvexPoly(mask, poly, org.opencv.core.Scalar(0.0))
            // Pequeño margen: la interpolación del borde trae algo de la mesa
            Imgproc.dilate(mask, mask, Cv.kernel(Imgproc.MORPH_RECT, 3))
            if (Core.countNonZero(mask) > 0) img.setTo(Cv.paperColor(img), mask)
        } finally {
            mask.release(); poly.release()
        }
    }

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** Inversa de una matriz 3x3 (homografía) por adjuntos. */
    private fun inv3(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val c0 = e * i - f * h; val c1 = -(d * i - f * g); val c2 = d * h - e * g
        val k = 1.0 / (a * c0 + b * c1 + c * c2)
        return doubleArrayOf(
            c0 * k, -(b * i - c * h) * k, (b * f - c * e) * k,
            c1 * k, (a * i - c * g) * k, -(a * f - c * d) * k,
            c2 * k, -(a * h - b * g) * k, (a * e - b * d) * k,
        )
    }

    /**
     * Geometría para vista previa: el original se reduce UNA vez (INTER_AREA, en caché) a la escala justa para que
     * la zona del documento mida ~[maxSide]; así el warp no produce aliasing y es muy rápido.
     */
    private fun geometryPreview(original: Bitmap, edits: PageEdits, maxSide: Int): Mat {
        val ow = original.width; val oh = original.height
        val (qw, qh) = edits.quad?.let { PerspectiveCorrector.quadExtent(it) } ?: (ow.toDouble() to oh.toDouble())
        val wanted = min(1.0, maxSide * 1.1 / max(1.0, max(qw, qh)))
        // Cuantizar la escala para reutilizar la caché aunque el quad cambie un poco
        val scale = if (wanted >= 0.999) 1.0 else (wanted * 20).let { kotlin.math.ceil(it) / 20.0 }.coerceAtMost(1.0)
        val warped = synchronized(previewLock) {
            val src = previewSourceFor(original, scale)
            val q = edits.quad?.let { PerspectiveCorrector.scaleQuad(it, src.cols().toDouble() / ow, src.rows().toDouble() / oh) }
            cropMat(src, q, maxPixels = 0, maxSide = maxSide)
        }
        val qk = quadKey(edits.quad?.takeIf { !PerspectiveCorrector.isFullFrame(it, ow, oh) }, ow, oh)
        return orient(warped, edits, qk)
    }

    private fun previewSourceFor(original: Bitmap, scale: Double): Mat {
        val cur = previewSource
        if (cur != null && cur.ref.get() === original && cur.generation == original.generationId &&
            cur.scale == scale && !cur.mat.empty()
        ) return cur.mat
        cur?.mat?.release()
        val rgba = Cv.toRgba(original)
        val mat = if (scale < 1.0) {
            val m = Mat()
            Imgproc.resize(
                rgba, m,
                Size(max(1.0, (original.width * scale).roundToInt().toDouble()), max(1.0, (original.height * scale).roundToInt().toDouble())),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            rgba.release()
            m
        } else rgba
        previewSource = PreviewSource(WeakReference(original), original.generationId, scale, mat)
        return mat
    }

    /**
     * Recorte con perspectiva (o reducción si no hay quad). [src] RGBA; devuelve RGBA nuevo.
     */
    private fun cropMat(src: Mat, quad: Quad?, maxPixels: Int, maxSide: Int): Mat {
        val w = src.cols(); val h = src.rows()
        if (quad != null && !PerspectiveCorrector.isFullFrame(quad, w, h)) {
            // Centro óptico supuesto en el centro de [src]; la escala uniforme no altera la relación de aspecto
            return PerspectiveCorrector.warpMat(src, quad, maxPixels, maxSide, w, h)
        }
        var s = 1.0
        if (maxPixels > 0 && w.toLong() * h > maxPixels) s = min(s, sqrt(maxPixels.toDouble() / (w.toDouble() * h)))
        if (maxSide > 0 && max(w, h) > maxSide) s = min(s, maxSide.toDouble() / max(w, h))
        if (s >= 0.999) return src.clone()
        val out = Mat()
        Imgproc.resize(src, out, Size(max(1.0, (w * s).roundToInt().toDouble()), max(1.0, (h * s).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    /**
     * RGBA -> RGB, rotación de 90°, hoja curvada ([GridDewarp], con el modelo en caché si lo hay) o enderezado
     * automático. [quadKey] identifica el recorte (ver [quadKey]). Libera [rgba].
     */
    private fun orient(rgba: Mat, edits: PageEdits, quadKey: List<Int>?): Mat {
        var cur = Mat()
        Imgproc.cvtColor(rgba, cur, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val code = when (((edits.rotation % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> -1
        }
        if (code >= 0) {
            val r = Mat()
            Core.rotate(cur, r, code)
            cur.release(); cur = r
        }
        val rot = ((edits.rotation % 360) + 360) % 360
        val model = if (edits.autoDewarp) dewarpModel(cur, quadKey, rot, cacheable = true) else null
        if (model != null) {
            val d = GridDewarp.apply(cur, model)
            cur.release(); cur = d
        } else if (edits.autoDeskew) {
            val d = Cleanup.deskewMat(cur)
            cur.release(); cur = d
        }
        if (quadKey != null) cleanWedges(cur, edits)
        return cur
    }

    // =====================================================================================
    // Filtro + limpieza + borrado manual
    // =====================================================================================

    /** Libera [geo]; devuelve el Mat final (8UC3 u 8UC1). */
    private fun finish(geo: Mat, edits: PageEdits, opt: ImageEnhancer.Options, progress: ProgressCallback?): Mat {
        // AUTO se clasifica una sola vez (a 256 px) y se usa tanto para el filtro como para decidir la limpieza
        val analysis = if (edits.filter == FilterType.AUTO) runCatching { ImageEnhancer.analyzeMat(geo) }.getOrNull() else null
        val effective = ImageEnhancer.effectiveFilter(edits.filter, analysis)
        var cur = try {
            ImageEnhancer.applyMat(geo, edits.filter, edits.adjustments, opt, analysis)
        } finally {
            geo.release()
        }
        progress?.invoke(0.65f)
        if (edits.autoRemoveLines) {
            val r = Cleanup.removeLinesMat(cur)
            cur.release(); cur = r
        }
        progress?.invoke(0.8f)
        // Los filtros segmentados ya dejan blanco puro fuera de la escritura (la limpieza de motas sólo costaría tiempo)
        if (edits.autoDenoise && effective != FilterType.ORIGINAL && effective != FilterType.VIVID &&
            !ImageEnhancer.isSegmented(edits.filter, analysis)
        ) {
            val r = Cleanup.denoiseMat(cur)
            cur.release(); cur = r
        }
        progress?.invoke(0.9f)
        if (edits.eraseStrokes.isNotEmpty()) {
            val r = Cleanup.applyEraseStrokesMat(cur, edits.eraseStrokes)
            cur.release(); cur = r
        }
        return cur
    }
}
