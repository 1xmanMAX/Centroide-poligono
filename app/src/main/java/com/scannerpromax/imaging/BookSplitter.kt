package com.scannerpromax.imaging

import android.graphics.Bitmap
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

object BookSplitter {

    /** Detecta el lomo (gutter) de un libro abierto y devuelve las dos páginas, cada una aplanada. */
    fun split(src: Bitmap): BookSplit {
        val rgb = Cv.toRgb(src)
        try {
            val gx = findGutter(rgb)
            val w = rgb.cols(); val h = rgb.rows()
            // Pequeño margen a cada lado del lomo: elimina la sombra más profunda del pliegue
            val trim = max(1, (w * 0.004).roundToInt())
            val lw = (gx - trim).coerceIn(1, w - 1)
            val rx = (gx + trim).coerceIn(1, w - 1)
            // Página izquierda completa (hasta Bitmap) antes de crear la derecha: menor pico de memoria
            val left = flattenPage(rgb.submat(Rect(0, 0, lw, h)), isLeft = true)
            val lb = try { Cv.toBitmap(left) } finally { left.release() }
            val rb = try {
                val right = flattenPage(rgb.submat(Rect(rx, 0, w - rx, h)), isLeft = false)
                try { Cv.toBitmap(right) } finally { right.release() }
            } catch (t: Throwable) {
                lb.recycle(); throw t
            }
            return BookSplit(lb, rb, gx)
        } finally {
            rgb.release()
        }
    }

    /**
     * Posición X del lomo en píxeles de [rgb]. Busca en el tercio central del perfil de columnas la combinación
     * de: valle de brillo (sombra del pliegue), profundidad del valle respecto de su entorno y gradiente horizontal.
     */
    internal fun findGutter(rgb: Mat): Int = MatBag().use { bag ->
        val w = rgb.cols()
        val sm = bag.mat()
        val s = Cv.downscale(rgb, sm, 800)
        val g = bag.add(Cv.gray(sm))
        val sw = g.cols()
        if (sw < 30) return@use w / 2
        // Perfil de brillo por columna (filas centrales: evita el fondo arriba/abajo)
        val band = bag.add(g.submat(g.rows() / 6, g.rows() * 5 / 6, 0, sw))
        val colMean = bag.mat()
        Core.reduce(band, colMean, 0, Core.REDUCE_AVG, CvType.CV_32F)
        val gxm = bag.mat()
        Imgproc.Sobel(band, gxm, CvType.CV_32F, 1, 0, 3)
        Core.absdiff(gxm, Scalar(0.0), gxm)
        val colGrad = bag.mat()
        Core.reduce(gxm, colGrad, 0, Core.REDUCE_AVG, CvType.CV_32F)
        val k = Cv.odd(max(3, sw / 50))
        Imgproc.GaussianBlur(colMean, colMean, Size(k.toDouble(), 1.0), 0.0)
        Imgproc.GaussianBlur(colGrad, colGrad, Size(k.toDouble(), 1.0), 0.0)
        val b = FloatArray(sw); colMean.get(0, 0, b)
        val gr = FloatArray(sw); colGrad.get(0, 0, gr)

        val x0 = sw / 3; val x1 = sw * 2 / 3
        var bMin = Float.MAX_VALUE; var bMax = -Float.MAX_VALUE; var gMax = 1e-3f
        for (x in x0 until x1) { bMin = min(bMin, b[x]); bMax = max(bMax, b[x]); gMax = max(gMax, gr[x]) }
        if (bMax - bMin < 3f) return@use w / 2 // perfil plano: no hay lomo visible
        val win = max(3, sw / 12)
        var best = sw / 2; var bestScore = -1.0
        for (x in x0 until x1) {
            val dark = (bMax - b[x]) / (bMax - bMin)
            // Profundidad del valle: brillo de los vecinos a ±win menos el propio
            val l = b[max(0, x - win)]; val r = b[min(sw - 1, x + win)]
            val valley = ((min(l, r) - b[x]) / (bMax - bMin)).coerceIn(0f, 1f)
            val grad = gr[x] / gMax
            // Ligera preferencia por el centro
            val center = 1.0 - abs(x - sw / 2.0) / (sw / 2.0)
            val score = 0.45 * dark + 0.35 * valley + 0.1 * grad + 0.1 * center
            if (score > bestScore) { bestScore = score; best = x }
        }
        (best / s).roundToInt().coerceIn(1, w - 1)
    }

    /**
     * Endereza una página: corrige la inclinación del texto y compensa la curvatura vertical cerca del lomo
     * (las líneas se curvan hacia el pliegue) estimando el perfil del borde superior del bloque de texto.
     */
    private fun flattenPage(page: Mat, isLeft: Boolean): Mat {
        try {
            val dewarped = dewarpNearGutter(page, isLeft)
            val out = Cleanup.deskewMat(dewarped)
            dewarped.release()
            return out
        } finally {
            page.release()
        }
    }

    /**
     * Corrección leve de curvatura: para cada franja vertical se mide el desplazamiento del texto
     * (centro de masa de la tinta por fila, a baja resolución) respecto de la franja más alejada del lomo
     * y se re-mapea con remap. Si la estimación no es fiable devuelve una copia.
     */
    private fun dewarpNearGutter(page: Mat, isLeft: Boolean): Mat = MatBag().use { bag ->
        val w = page.cols(); val h = page.rows()
        if (w < 64 || h < 64) return@use page.clone()
        val sm = bag.mat()
        val s = Cv.downscale(page, sm, 600)
        val g = bag.add(Cv.gray(sm))
        val ink = bag.mat()
        Imgproc.adaptiveThreshold(g, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
        val sw = ink.cols(); val sh = ink.rows()
        val strips = 8
        val stripW = sw / strips
        if (stripW < 8) return@use page.clone()
        // Perfil vertical de tinta por franja -> desplazamiento por correlación con la franja de referencia
        val profiles = Array(strips) { FloatArray(sh) }
        val col = bag.mat()
        for (i in 0 until strips) {
            val sub = ink.submat(0, sh, i * stripW, (i + 1) * stripW)
            Core.reduce(sub, col, 1, Core.REDUCE_AVG, CvType.CV_32F)
            sub.release()
            col.get(0, 0, profiles[i])
        }
        // Franjas desde el borde exterior hacia el lomo; desplazamiento acumulado entre franjas vecinas
        // (búsqueda pequeña: menor que medio interlineado, evita engancharse al renglón vecino)
        val order = if (isLeft) (0 until strips).toList() else (strips - 1 downTo 0).toList()
        val maxShift = max(2, sh / 80)
        val shifts = FloatArray(strips)
        var reliable = true
        for (k in 1 until order.size) {
            val a = profiles[order[k - 1]]; val b = profiles[order[k]]
            var bestS = 0; var bestC = -Double.MAX_VALUE
            for (d in -maxShift..maxShift) {
                var c = 0.0
                for (y in maxShift until sh - maxShift) c += a[y] * b[y + d]
                if (c > bestC) { bestC = c; bestS = d }
            }
            if (bestC <= 0) reliable = false
            shifts[order[k]] = shifts[order[k - 1]] + bestS
        }
        // Sólo corregir si el desplazamiento crece de forma monótona hacia el lomo (curvatura real)
        var prev = 0f
        var monotonic = true
        val sign = shifts[order.last()].let { if (it >= 0) 1f else -1f }
        for (i in order) { if ((shifts[i] - prev) * sign < -1f) monotonic = false; prev = shifts[i] }
        val maxAbs = shifts.maxOf { abs(it) }
        if (!reliable || !monotonic || maxAbs < 1.5f) return@use page.clone()

        // Remapeo por bandas horizontales: dy depende sólo de x, así que los mapas de una banda (band x w, float)
        // sirven para todas. Antes eran 4 mapas float a resolución completa (16 B/píxel, 64-100 MB por página).
        val dyRow = FloatArray(w)
        val xs = FloatArray(w) { it.toFloat() }
        for (x in 0 until w) {
            val fx = (x * s / stripW) - 0.5
            val i0 = fx.toInt().coerceIn(0, strips - 1); val i1 = (i0 + 1).coerceIn(0, strips - 1)
            val t = (fx - i0).coerceIn(0.0, 1.0)
            dyRow[x] = ((shifts[i0] * (1 - t) + shifts[i1] * t) / s).toFloat()
        }
        val band = min(h, 256)
        val out = Mat(h, w, page.type())
        val tmpM = MatBag()
        try {
            val rowX = tmpM.add(Mat(1, w, CvType.CV_32F)); rowX.put(0, 0, xs)
            val mx = tmpM.mat(); Core.repeat(rowX, band, 1, mx)
            val rowDy = tmpM.add(Mat(1, w, CvType.CV_32F)); rowDy.put(0, 0, dyRow)
            val base = tmpM.mat(); Core.repeat(rowDy, band, 1, base)
            val ys = tmpM.add(Mat(band, 1, CvType.CV_32F)); ys.put(0, 0, FloatArray(band) { it.toFloat() })
            val yRep = tmpM.mat(); Core.repeat(ys, 1, w, yRep)
            Core.add(base, yRep, base)          // base(y, x) = y + dy(x), y relativo a la banda
            yRep.release()
            val my = tmpM.mat(); val bandOut = tmpM.mat()
            var y0 = 0
            while (y0 < h) {
                val bh = min(band, h - y0)
                val baseB = base.submat(0, bh, 0, w); val mxB = mx.submat(0, bh, 0, w)
                Core.add(baseB, Scalar(y0.toDouble()), my)
                Imgproc.remap(page, bandOut, mxB, my, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
                val dst = out.submat(y0, y0 + bh, 0, w)
                bandOut.copyTo(dst)
                dst.release(); baseB.release(); mxB.release()
                y0 += bh
            }
        } catch (t: Throwable) {
            out.release(); throw t
        } finally {
            tmpM.close()
        }
        out
    }
}
