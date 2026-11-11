package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.sin

/**
 * Escenas SINTÉTICAS deterministas para el banco de rendimiento y el test dorado: hoja A4 con texto, encabezado de color,
 * foto, tabla y firma, combada, en perspectiva sobre madera, con sombra suave, iluminación desigual y ruido. Nada de fotos
 * reales (los datasets del banco nunca van al repo).
 */
internal object PerfScenes {

    private val WORDS = ("el la de que y en un ser se no haber por con su para como estar tener le lo todo pero mas hacer o poder " +
        "decir este ir otro ese si me ya ver porque dar cuando muy sin vez mucho saber que sobre mi alguno mismo yo tambien " +
        "hasta ano dos querer entre asi primero desde grande eso ni nos llegar pasar tiempo ella dia uno bien poco deber " +
        "entonces poner cosa tanto hombre parecer nuestro tan donde ahora parte despues vida quedar siempre creer hablar llevar").split(' ')

    /** Página A4 [w] x [h] RGB con texto, encabezado rojo, foto, tabla y firma azul. */
    fun page(w: Int, h: Int, seed: Long = 7, photo: Boolean = true, table: Boolean = true): Mat {
        val rnd = java.util.Random(seed)
        val m = Mat(h, w, CvType.CV_8UC3, Scalar(238.0, 236.0, 229.0))
        val s = w / 2480.0
        val ink = Scalar(30.0, 30.0, 38.0)
        Imgproc.putText(m, "INFORME TECNICO DE OBRA", Point(220 * s, 260 * s), Imgproc.FONT_HERSHEY_DUPLEX, 3.2 * s, Scalar(190.0, 30.0, 40.0), (6 * s).toInt().coerceAtLeast(1), Imgproc.LINE_AA)
        val lineH = (62 * s).toInt()
        var y = (420 * s).toInt()
        val photoRect = if (photo) Rect((1400 * s).toInt(), (700 * s).toInt(), (860 * s).toInt(), (640 * s).toInt()) else null
        val tableRect = if (table) Rect((220 * s).toInt(), (2050 * s).toInt(), (2040 * s).toInt(), (700 * s).toInt()) else null
        while (y < h - 260 * s) {
            if (tableRect != null && y in tableRect.y - lineH..tableRect.y + tableRect.height + lineH) { y += lineH; continue }
            val right = if (photoRect != null && y in photoRect.y - lineH..photoRect.y + photoRect.height + lineH) photoRect.x - 60 * s else w - 220 * s
            val sb = StringBuilder()
            while (true) {
                val wd = WORDS[rnd.nextInt(WORDS.size)]
                val cand = if (sb.isEmpty()) wd else "$sb $wd"
                val sz = Imgproc.getTextSize(cand, Imgproc.FONT_HERSHEY_SIMPLEX, 1.55 * s, (3 * s).toInt().coerceAtLeast(1), null)
                if (220 * s + sz.width > right) break
                sb.setLength(0); sb.append(cand)
            }
            Imgproc.putText(m, sb.toString(), Point(220 * s, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.55 * s, ink, (3 * s).toInt().coerceAtLeast(1), Imgproc.LINE_AA)
            y += lineH
        }
        if (photoRect != null) {
            val ph = m.submat(photoRect)
            val b = ByteArray(photoRect.width * photoRect.height * 3)
            for (yy in 0 until photoRect.height) for (xx in 0 until photoRect.width) {
                val o = (yy * photoRect.width + xx) * 3
                val sky = yy < photoRect.height * 0.45
                val v = 18 * sin(xx * 0.05) * sin(yy * 0.033) + rnd.nextGaussian() * 6
                b[o] = ((if (sky) 120 else 70) + v).toInt().coerceIn(0, 255).toByte()
                b[o + 1] = ((if (sky) 160 else 120) + v).toInt().coerceIn(0, 255).toByte()
                b[o + 2] = ((if (sky) 215 else 60) + v).toInt().coerceIn(0, 255).toByte()
            }
            ph.put(0, 0, b)
            Imgproc.circle(ph, Point(photoRect.width * 0.7, photoRect.height * 0.22), (photoRect.height * 0.1).toInt(), Scalar(250.0, 230.0, 120.0), -1, Imgproc.LINE_AA)
            Imgproc.rectangle(ph, Point(photoRect.width * 0.2, photoRect.height * 0.4), Point(photoRect.width * 0.45, photoRect.height * 0.9), Scalar(150.0, 70.0, 50.0), -1)
            ph.release()
        }
        if (tableRect != null) {
            val rows = 6; val cols = 5
            for (r in 0..rows) {
                val yy = tableRect.y + tableRect.height * r / rows.toDouble()
                Imgproc.line(m, Point(tableRect.x.toDouble(), yy), Point((tableRect.x + tableRect.width).toDouble(), yy), ink, (3 * s).toInt().coerceAtLeast(1))
            }
            for (c in 0..cols) {
                val xx = tableRect.x + tableRect.width * c / cols.toDouble()
                Imgproc.line(m, Point(xx, tableRect.y.toDouble()), Point(xx, (tableRect.y + tableRect.height).toDouble()), ink, (3 * s).toInt().coerceAtLeast(1))
            }
            for (r in 0 until rows) for (c in 0 until cols) {
                val txt = if (r == 0) "Col ${c + 1}" else "%.2f".format(rnd.nextDouble() * 1000)
                Imgproc.putText(m, txt, Point(tableRect.x + tableRect.width * c / cols.toDouble() + 30 * s, tableRect.y + tableRect.height * (r + 0.7) / rows), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3 * s, ink, (3 * s).toInt().coerceAtLeast(1), Imgproc.LINE_AA)
            }
        }
        // firma azul
        val pts = ArrayList<Point>()
        for (i in 0..80) {
            val t = i / 80.0
            pts += Point((1500 + 600 * t) * s, (h / s - 330 + 60 * sin(t * 9) + 30 * sin(t * 23)) * s)
        }
        for (i in 1 until pts.size) Imgproc.line(m, pts[i - 1], pts[i], Scalar(30.0, 50.0, 160.0), (4 * s).toInt().coerceAtLeast(1), Imgproc.LINE_AA)
        return m
    }

    /** Madera con vetas (rápida: por filas con Mat). */
    fun wood(w: Int, h: Int): Mat {
        val b = ByteArray(w * h * 3)
        for (y in 0 until h) {
            val o0 = y * w * 3
            for (x in 0 until w) {
                val v = 14 * sin(y * 0.011 + 2.5 * sin(x * 0.003)) + 6 * sin(y * 0.05 + x * 0.0025)
                val o = o0 + x * 3
                b[o] = (176 + v).toInt().coerceIn(0, 255).toByte(); b[o + 1] = (124 + v * 0.8).toInt().coerceIn(0, 255).toByte(); b[o + 2] = (78 + v * 0.6).toInt().coerceIn(0, 255).toByte()
            }
        }
        val m = Mat(h, w, CvType.CV_8UC3); m.put(0, 0, b)
        return m
    }

    /**
     * Foto de [page] sobre madera, [w] x [h] (RGB): combada ([bend] px de flecha), en perspectiva, con sombra en una
     * esquina, iluminación desigual, ligero desenfoque y ruido. Devuelve la escena y las 4 esquinas de la hoja.
     */
    fun photo(page: Mat, w: Int, h: Int, bend: Double, seed: Long = 3): Pair<Mat, DoubleArray> {
        val pw = page.cols(); val ph = page.rows()
        // combado: desplazamiento vertical que crece hacia el centro de la hoja (como una hoja que no apoya plana)
        val mapX = Mat(ph, pw, CvType.CV_32F); val mapY = Mat(ph, pw, CvType.CV_32F)
        val rx = FloatArray(pw); val ry = FloatArray(pw)
        for (y in 0 until ph) {
            for (x in 0 until pw) { rx[x] = x.toFloat(); ry[x] = (y - bend * sin(PI * x / pw) * (0.4 + 0.6 * y / ph)).toFloat() }
            mapX.put(y, 0, rx); mapY.put(y, 0, ry)
        }
        val bent = Mat()
        Imgproc.remap(page, bent, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(238.0, 236.0, 229.0))
        mapX.release(); mapY.release()
        val scene = wood(w, h)
        val q = doubleArrayOf(0.14 * w, 0.09 * h, 0.87 * w, 0.11 * h, 0.91 * w, 0.93 * h, 0.10 * w, 0.91 * h)
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(pw.toDouble(), 0.0), Point(pw.toDouble(), ph.toDouble()), Point(0.0, ph.toDouble()))
        val dst = MatOfPoint2f(Point(q[0], q[1]), Point(q[2], q[3]), Point(q[4], q[5]), Point(q[6], q[7]))
        val hm = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat(); val mask = Mat()
        Imgproc.warpPerspective(bent, warped, hm, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR)
        val ones = Mat(ph, pw, CvType.CV_8UC1, Scalar(255.0))
        Imgproc.warpPerspective(ones, mask, hm, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_NEAREST)
        warped.copyTo(scene, mask)
        listOf(bent, warped, mask, ones, hm, src, dst).forEach { it.release() }
        // iluminación: degradado + sombra elíptica suave
        val light = Mat(h / 8, w / 8, CvType.CV_32F)
        val lr = FloatArray(w / 8)
        for (y in 0 until h / 8) {
            for (x in 0 until w / 8) {
                val fx = x / (w / 8.0); val fy = y / (h / 8.0)
                var l = 1.05 - 0.25 * fx - 0.12 * fy
                val dx = (fx - 0.15) / 0.28; val dy = (fy - 0.85) / 0.22
                val d2 = dx * dx + dy * dy
                if (d2 < 1.6) l *= 1 - 0.38 * kotlin.math.exp(-d2 * 1.6)
                lr[x] = l.toFloat()
            }
            light.put(y, 0, lr)
        }
        val lf = Mat(); Imgproc.resize(light, lf, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC); light.release()
        val l3 = Mat(); Core.merge(listOf(lf, lf, lf), l3); lf.release()
        val sf = Mat(); scene.convertTo(sf, CvType.CV_32FC3)
        Core.multiply(sf, l3, sf); l3.release()
        Imgproc.GaussianBlur(sf, sf, Size(0.0, 0.0), 0.9)
        val noise = Mat(h, w, CvType.CV_32FC3)
        Core.setRNGSeed(seed.toInt())
        Core.randn(noise, 0.0, 4.0)
        Core.add(sf, noise, sf); noise.release()
        sf.convertTo(scene, CvType.CV_8UC3); sf.release()
        return scene to q
    }
}
