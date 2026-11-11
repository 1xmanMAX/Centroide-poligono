package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Páginas sintéticas para el banco de FOTOS frente a ZONAS OSCURAS ([PhotoBoxRefine]): hoja con texto (y a veces una tabla)
 * con fotos de cámara pegadas (positivos) o con zonas oscuras que NO son fotos (negativos): sombra del móvil o de la mano,
 * viñeteado, sombra del doblez, celdas oscuras, cabecera negra, dedo, fondo oscuro en un canto, firma/sello/logo. Después,
 * efectos de la captura (luz desigual, ruido, desenfoque leve). Todas en RGB 8UC3.
 */
internal object PhotoScenes {

    enum class Neg(val label: String) {
        SHADOW("sombra del móvil"), HAND("sombra de la mano"), VIGNETTE("viñeteado"), FOLD("sombra del doblez"), CELLS("celdas oscuras"),
        HEADER("cabecera negra"), FINGER("dedo"), BORDER("fondo oscuro en el canto"), INKS("firma, sello y logo"), DARK_TEXT("bloque gris con texto"),
        HARD_SHADOW("sombra dura (luz directa)"), DEEP_CORNER("sombra muy oscura en la esquina"), DARK_OBJECT("objeto oscuro sobre la hoja"),
    }

    /** Escena: imagen, recuadros de las fotos de verdad (px) y zona oscura (px; null en los positivos puros). */
    class Scene(val img: Mat, val photos: List<Rect>, val dark: Rect?, val name: String)

    private val WORDS = "el la de que y en los se del las un por con no una su para es al lo como mas pero sus le ya o fue este ha si porque esta son entre cuando muy sin sobre ser tiene tambien me hasta hay donde quien desde todo nos durante todos uno les ni contra otros ese eso ante ellos e esto mi antes algunos que unos yo otro otras otra el tanto esa estos mucho quienes nada muchos cual poco ella estar estas algunas algo nosotros obra informe avance metrado partida concreto acero zapata columna viga losa".split(" ")

    /** Hoja con renglones de texto (y una tabla con [table]) en [w] x [h]; zonas [skip] sin texto (donde va una foto). */
    fun textPage(w: Int, h: Int, rnd: Random, skip: List<Rect> = emptyList(), table: Boolean = false): Mat {
        val paper = Scalar(232.0 + rnd.nextInt(8), 230.0 + rnd.nextInt(8), 224.0 + rnd.nextInt(8))
        val m = Mat(h, w, CvType.CV_8UC3, paper)
        val lh = 26 + rnd.nextInt(10)
        var y = 70 + rnd.nextInt(30)
        val x0 = 60 + rnd.nextInt(20); val x1 = w - 60
        val tb = if (table) Rect(x0, (h * 0.55).toInt(), x1 - x0, (h * 0.2).toInt()) else null
        while (y < h - 60) {
            if (tb != null && y > tb.y - lh && y < tb.y + tb.height + lh) { y += lh; continue }
            var x = x0
            while (x < x1 - 40) {
                val wd = WORDS[rnd.nextInt(WORDS.size)]
                val sz = Imgproc.getTextSize(wd, Imgproc.FONT_HERSHEY_SIMPLEX, lh / 42.0, 1, null)
                if (x + sz.width > x1) break
                val r = Rect(x, y - lh, sz.width.toInt(), lh + 4)
                if (skip.none { PhotoMath.overlaps(it, r) }) Imgproc.putText(m, wd, Point(x.toDouble(), y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, lh / 42.0, Scalar(25.0, 25.0, 30.0), if (rnd.nextInt(5) == 0) 2 else 1, Imgproc.LINE_AA)
                x += sz.width.toInt() + 10
            }
            y += lh
        }
        if (tb != null) {
            val rows = 5; val cols = 4
            for (i in 0..rows) Imgproc.line(m, Point(tb.x.toDouble(), tb.y + i * tb.height / rows.toDouble()), Point((tb.x + tb.width).toDouble(), tb.y + i * tb.height / rows.toDouble()), Scalar(40.0, 40.0, 40.0), 2)
            for (j in 0..cols) Imgproc.line(m, Point(tb.x + j * tb.width / cols.toDouble(), tb.y.toDouble()), Point(tb.x + j * tb.width / cols.toDouble(), (tb.y + tb.height).toDouble()), Scalar(40.0, 40.0, 40.0), 2)
            for (i in 0 until rows) for (j in 0 until cols) Imgproc.putText(m, "%.2f".format(rnd.nextDouble() * 900), Point(tb.x + j * tb.width / cols + 12.0, tb.y + (i + 0.7) * tb.height / rows), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, Scalar(25.0, 25.0, 30.0), 1, Imgproc.LINE_AA)
        }
        return m
    }

    /** Pega la foto natural [ph] (RGB) en [r] de [m] con aspecto de impresión (contraste algo menor, desenfoque leve). */
    fun pastePhoto(m: Mat, ph: Mat, r: Rect, rnd: Random, gray: Boolean) {
        val s = Mat(); Imgproc.resize(ph, s, r.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        if (gray) { val g = Mat(); Imgproc.cvtColor(s, g, Imgproc.COLOR_RGB2GRAY); Imgproc.cvtColor(g, s, Imgproc.COLOR_GRAY2RGB); g.release() }
        s.convertTo(s, -1, 0.88 + 0.08 * rnd.nextDouble(), 10.0)
        Imgproc.GaussianBlur(s, s, Size(0.0, 0.0), 0.7)
        val sub = m.submat(r); s.copyTo(sub); sub.release(); s.release()
    }

    /** Foto sintética "de cámara" (cielo en degradado, bloques de colores, grano) del tamaño [w] x [h]. */
    fun synthPhoto(w: Int, h: Int, seed: Int): Mat {
        val rnd = Random(seed)
        val ph = Mat(h, w, CvType.CV_8UC3)
        for (y in 0 until h) { val t = y.toDouble() / h; Imgproc.line(ph, Point(0.0, y.toDouble()), Point(w.toDouble(), y.toDouble()), Scalar(90 + 80 * t, 140 + 40 * t, 210 - 90 * t), 1) }
        repeat(400) {
            val x = rnd.nextInt(w); val y = rnd.nextInt(h / 3, h)
            Imgproc.rectangle(ph, Point(x.toDouble(), y.toDouble()), Point(x + 8.0 + rnd.nextInt(60), y + 6.0 + rnd.nextInt(40)), Scalar(rnd.nextInt(40, 220).toDouble(), rnd.nextInt(40, 220).toDouble(), rnd.nextInt(30, 200).toDouble()), -1)
        }
        noise(ph, 20.0)
        Imgproc.GaussianBlur(ph, ph, Size(0.0, 0.0), 0.8)
        return ph
    }

    private fun noise(m: Mat, sd: Double) {
        val f = Mat(); m.convertTo(f, CvType.CV_16SC3)
        val nf = Mat(m.size(), CvType.CV_16SC3); Core.randn(nf, 0.0, sd); Core.add(f, nf, f); f.convertTo(m, CvType.CV_8UC3)
        f.release(); nf.release()
    }

    /** Multiplica [m] por el campo [k] (CV_32F, 0..1+). */
    private fun multiply(m: Mat, k: Mat) {
        val f = Mat(); m.convertTo(f, CvType.CV_32FC3)
        val k3 = Mat(); Core.merge(listOf(k, k, k), k3)
        Core.multiply(f, k3, f); f.convertTo(m, CvType.CV_8UC3)
        f.release(); k3.release()
    }

    /** Campo de oscurecimiento a partir de una máscara 0..1 [mask] (CV_32F) con fuerza [depth] (0,5 = la mitad de luz). */
    private fun darken(m: Mat, mask: Mat, depth: Double) {
        val k = Mat(); mask.convertTo(k, CvType.CV_32F, -depth, 1.0)
        multiply(m, k); k.release()
    }

    /** Efectos de la captura: luz desigual suave, ruido del sensor y desenfoque leve; JPEG no. */
    fun capture(m: Mat, rnd: Random) {
        val w = m.cols(); val h = m.rows()
        val k = Mat(h, w, CvType.CV_32F)
        val ax = rnd.nextDouble(-0.12, 0.12); val ay = rnd.nextDouble(-0.12, 0.12)
        val row = FloatArray(w)
        for (y in 0 until h) { for (x in 0 until w) row[x] = (0.94 + ax * (x.toDouble() / w - 0.5) + ay * (y.toDouble() / h - 0.5)).toFloat(); k.put(y, 0, row) }
        multiply(m, k); k.release()
        noise(m, 2.5 + rnd.nextDouble() * 2)
        Imgproc.GaussianBlur(m, m, Size(0.0, 0.0), 0.6)
    }

    /** Negativo [t] sobre la hoja [m]; devuelve el recuadro de la zona oscura. */
    fun negative(m: Mat, t: Neg, rnd: Random): Rect {
        val w = m.cols(); val h = m.rows()
        return when (t) {
            Neg.SHADOW, Neg.HAND -> {
                // sombra del móvil (polígono desde un canto) o de la mano (palma + dedos), borde blando
                val mk = Mat.zeros(h, w, CvType.CV_32F)
                val pts: List<Point>
                if (t == Neg.SHADOW) {
                    val d = rnd.nextDouble(0.25, 0.5)
                    pts = when (rnd.nextInt(3)) {
                        0 -> listOf(Point(0.0, h.toDouble()), Point(0.0, h * (1 - d)), Point(w * 0.6, h * (1 - d * 0.8)), Point(w * 0.8, h.toDouble()))
                        1 -> listOf(Point(w.toDouble(), 0.0), Point(w * (1 - d), 0.0), Point(w * (1 - d * 1.2), h * 0.55), Point(w.toDouble(), h * 0.7))
                        else -> listOf(Point(0.0, 0.0), Point(w * 0.7, 0.0), Point(w * 0.5, h * d), Point(0.0, h * d * 1.1))
                    }
                    Imgproc.fillPoly(mk, listOf(MatOfPoint(*pts.toTypedArray())), Scalar(1.0))
                } else {
                    val cx = w * rnd.nextDouble(0.3, 0.7); val cy = h * 0.88
                    Imgproc.ellipse(mk, Point(cx, cy), Size(w * 0.22, h * 0.14), 0.0, 0.0, 360.0, Scalar(1.0), -1)
                    for (i in 0 until 4) Imgproc.ellipse(mk, Point(cx - w * 0.15 + i * w * 0.1, cy - h * 0.2), Size(w * 0.035, h * 0.12), 0.0, 0.0, 360.0, Scalar(1.0), -1)
                    pts = listOf(Point(cx - w * 0.22, cy - h * 0.32), Point(cx + w * 0.22, h.toDouble()))
                }
                Imgproc.GaussianBlur(mk, mk, Size(0.0, 0.0), w * rnd.nextDouble(0.015, 0.04))
                darken(m, mk, rnd.nextDouble(0.35, 0.62))
                // sombra con algo de tinte azulado (luz de techo fría)
                mk.release()
                bbox(pts, w, h)
            }
            Neg.HARD_SHADOW, Neg.DEEP_CORNER -> {
                // sombra de borde nítido (luz directa: móvil o cabeza) o muy profunda en una esquina, con el texto debajo
                val mk = Mat.zeros(h, w, CvType.CV_32F)
                val hard = t == Neg.HARD_SHADOW
                val d = rnd.nextDouble(0.3, 0.55)
                val pts = if (hard) listOf(Point(0.0, h * (1 - d)), Point(w * rnd.nextDouble(0.5, 0.9), h * (1 - d * 0.7)), Point(w * rnd.nextDouble(0.6, 1.0), h.toDouble()), Point(0.0, h.toDouble()))
                else listOf(Point(w * (1 - d), 0.0), Point(w.toDouble(), 0.0), Point(w.toDouble(), h * d), Point(w * (1 - d * 0.4), h * d * 0.8))
                Imgproc.fillPoly(mk, listOf(MatOfPoint(*pts.toTypedArray())), Scalar(1.0))
                Imgproc.GaussianBlur(mk, mk, Size(0.0, 0.0), if (hard) rnd.nextDouble(1.5, 4.0) else w * 0.02)
                darken(m, mk, if (hard) rnd.nextDouble(0.5, 0.72) else rnd.nextDouble(0.75, 0.88)); mk.release()
                bbox(pts, w, h)
            }
            Neg.DARK_OBJECT -> {
                // objeto oscuro (funda del móvil, estuche) apoyado en la hoja: casi uniforme, degradado suave y ruido
                val r = Rect((w * rnd.nextDouble(0.1, 0.5)).toInt(), (h * rnd.nextDouble(0.2, 0.6)).toInt(), (w * rnd.nextDouble(0.25, 0.4)).toInt(), (h * rnd.nextDouble(0.15, 0.3)).toInt())
                val base = rnd.nextDouble(25.0, 70.0)
                val tint = if (rnd.nextBoolean()) Scalar(base, base, base + 8) else Scalar(base + 15, base + 5, base)
                val o = Mat(r.size(), CvType.CV_8UC3, tint)
                for (y in 0 until r.height) { val k = 1.0 + 0.35 * y / r.height; Imgproc.line(o, Point(0.0, y.toDouble()), Point(r.width.toDouble(), y.toDouble()), Scalar(tint.`val`[0] * k, tint.`val`[1] * k, tint.`val`[2] * k), 1) }
                noise(o, 5.0)
                val mk = Mat.zeros(r.size(), CvType.CV_8U)
                Imgproc.rectangle(mk, Point(0.0, 0.0), Point(r.width - 1.0, r.height - 1.0), Scalar(255.0), -1)
                val sub = m.submat(r); o.copyTo(sub, mk); sub.release(); o.release(); mk.release()
                r
            }
            Neg.VIGNETTE -> {
                val mk = Mat(h, w, CvType.CV_32F); val row = FloatArray(w)
                val depth = rnd.nextDouble(0.4, 0.65)
                for (y in 0 until h) { for (x in 0 until w) { val dx = (x - w / 2.0) / (w / 2.0); val dy = (y - h / 2.0) / (h / 2.0); row[x] = (max(0.0, sqrt(dx * dx + dy * dy) - 0.75) / 0.66).coerceIn(0.0, 1.0).toFloat() }; mk.put(y, 0, row) }
                darken(m, mk, depth); mk.release()
                Rect(0, 0, w, h)
            }
            Neg.FOLD -> {
                val cx = w * rnd.nextDouble(0.35, 0.65); val sg = w * rnd.nextDouble(0.03, 0.07)
                val mk = Mat(h, w, CvType.CV_32F); val row = FloatArray(w) { x -> exp(-(x - cx) * (x - cx) / (2 * sg * sg)).toFloat() }
                for (y in 0 until h) mk.put(y, 0, row)
                darken(m, mk, rnd.nextDouble(0.4, 0.6)); mk.release()
                Rect((cx - 2 * sg).toInt(), 0, (4 * sg).toInt(), h)
            }
            Neg.CELLS -> {
                val r = Rect((w * 0.1).toInt(), (h * rnd.nextDouble(0.2, 0.5)).toInt(), (w * 0.8).toInt(), (h * 0.22).toInt())
                Imgproc.rectangle(m, r.tl(), r.br(), Scalar(250.0, 250.0, 250.0), -1)
                val rows = 4; val cols = 4
                for (i in 0 until rows) for (j in 0 until cols) {
                    val c = Rect(r.x + j * r.width / cols, r.y + i * r.height / rows, r.width / cols, r.height / rows)
                    val v = if ((i + j) % 2 == 0 || i == 0) rnd.nextDouble(70.0, 150.0) else 235.0
                    Imgproc.rectangle(m, c.tl(), c.br(), Scalar(v, v, v + 6), -1)
                    Imgproc.rectangle(m, c.tl(), c.br(), Scalar(20.0, 20.0, 20.0), 2)
                    Imgproc.putText(m, if (i == 0) "TOTAL" else "%.1f".format(rnd.nextDouble() * 99), Point(c.x + 10.0, c.y + c.height * 0.65), Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, if (v < 110) Scalar(250.0, 250.0, 250.0) else Scalar(15.0, 15.0, 15.0), 2, Imgproc.LINE_AA)
                }
                r
            }
            Neg.HEADER -> {
                val r = Rect(0, (h * rnd.nextDouble(0.0, 0.06)).toInt(), w, (h * rnd.nextDouble(0.07, 0.13)).toInt())
                val c = when (rnd.nextInt(3)) { 0 -> Scalar(20.0, 20.0, 22.0); 1 -> Scalar(25.0, 35.0, 70.0); else -> Scalar(55.0, 55.0, 55.0) }
                Imgproc.rectangle(m, r.tl(), r.br(), c, -1)
                Imgproc.putText(m, "INFORME MENSUAL DE OBRA N 07", Point(w * 0.08, r.y + r.height * 0.62), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, Scalar(245.0, 245.0, 245.0), 3, Imgproc.LINE_AA)
                r
            }
            Neg.FINGER -> {
                // dedo en el canto: elipse de piel con sombreado, desenfocada
                val side = rnd.nextInt(3)
                val cx = if (side == 0) 0.0 else if (side == 1) w.toDouble() else w * rnd.nextDouble(0.3, 0.7)
                val cy = if (side == 2) h.toDouble() else h * rnd.nextDouble(0.3, 0.8)
                val ax = if (side == 2) w * 0.07 else w * 0.16; val ay = if (side == 2) h * 0.12 else h * 0.045
                val f = Mat(h, w, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
                val mk = Mat.zeros(h, w, CvType.CV_8U)
                Imgproc.ellipse(mk, Point(cx, cy), Size(ax, ay), 0.0, 0.0, 360.0, Scalar(255.0), -1)
                val base = Scalar(rnd.nextDouble(150.0, 210.0), rnd.nextDouble(100.0, 140.0), rnd.nextDouble(80.0, 115.0))
                for (y in 0 until h) {
                    val t = ((y - (cy - ay)) / (2 * ay)).coerceIn(0.0, 1.0)
                    val k = 0.75 + 0.35 * sin(t * Math.PI)
                    Imgproc.line(f, Point(0.0, y.toDouble()), Point(w.toDouble(), y.toDouble()), Scalar(base.`val`[0] * k, base.`val`[1] * k, base.`val`[2] * k), 1)
                }
                Imgproc.GaussianBlur(f, f, Size(0.0, 0.0), 3.0)
                val mb = Mat(); Imgproc.GaussianBlur(mk, mb, Size(0.0, 0.0), 6.0)
                val fm = Mat(); mb.convertTo(fm, CvType.CV_32F, 1 / 255.0)
                // mezcla
                val a = Mat(); m.convertTo(a, CvType.CV_32FC3); val b = Mat(); f.convertTo(b, CvType.CV_32FC3)
                val k3 = Mat(); Core.merge(listOf(fm, fm, fm), k3)
                val inv = Mat(); Core.subtract(Mat.ones(k3.size(), k3.type()), k3, inv)
                Core.multiply(b, k3, b); Core.multiply(a, inv, a); Core.add(a, b, a); a.convertTo(m, CvType.CV_8UC3)
                listOf(f, mk, mb, fm, a, b, k3, inv).forEach { it.release() }
                Cv.clampRect(Rect((cx - ax).toInt(), (cy - ay).toInt(), (2 * ax).toInt(), (2 * ay).toInt()), w, h)
            }
            Neg.BORDER -> {
                // fondo oscuro (mesa gris oscura con textura o madera) que el recorte dejó en un canto
                val bw = (w * rnd.nextDouble(0.05, 0.12)).toInt()
                val left = rnd.nextBoolean()
                val r = if (left) Rect(0, 0, bw, h) else Rect(w - bw, 0, bw, h)
                val t = Mat(h, bw, CvType.CV_8UC3, if (rnd.nextBoolean()) Scalar(60.0, 55.0, 50.0) else Scalar(110.0, 70.0, 40.0))
                noise(t, 14.0)
                Imgproc.GaussianBlur(t, t, Size(0.0, 0.0), 1.2)
                val sub = m.submat(r); t.copyTo(sub); sub.release(); t.release()
                r
            }
            Neg.INKS -> {
                val x = (w * 0.15).toInt(); val y = (h * 0.75).toInt()
                val pts = MatOfPoint(*Array(40) { i -> Point(x + i * 7.0, y + 30 * sin(i * 0.7) + i * 1.5) })
                Imgproc.polylines(m, listOf(pts), false, Scalar(30.0, 50.0, 170.0), 3)
                Imgproc.circle(m, Point(x + 500.0, y + 60.0), 70, Scalar(150.0, 60.0, 140.0), 3)
                Imgproc.putText(m, "SELLO", Point(x + 455.0, y + 68.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, Scalar(150.0, 60.0, 140.0), 2)
                val lg = Rect((w * 0.08).toInt(), 40, 380, 90)
                Imgproc.rectangle(m, lg.tl(), lg.br(), Scalar(50.0, 90.0, 170.0), -1)
                Imgproc.putText(m, "JULIACA", Point(lg.x + 40.0, lg.y + 62.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.6, Scalar(250.0, 250.0, 250.0), 4)
                Rect(x - 20, y - 40, 650, 180)
            }
            Neg.DARK_TEXT -> {
                // recuadro gris medio (nota destacada) con texto negro encima, como una fotocopia oscura
                val r = Rect((w * 0.1).toInt(), (h * rnd.nextDouble(0.25, 0.55)).toInt(), (w * 0.8).toInt(), (h * 0.16).toInt())
                val sub = m.submat(r)
                val v = rnd.nextDouble(120.0, 170.0)
                val g = Mat(sub.size(), CvType.CV_8UC3, Scalar(v, v, v)); noise(g, 6.0)
                // el texto de debajo se conserva (oscuro sobre gris)
                val dark = Mat(); Core.min(sub, g, dark); dark.copyTo(sub)
                listOf(sub, g, dark).forEach { it.release() }
                r
            }
        }
    }

    private fun bbox(pts: List<Point>, w: Int, h: Int): Rect {
        val x0 = pts.minOf { it.x }; val y0 = pts.minOf { it.y }; val x1 = pts.maxOf { it.x }; val y1 = pts.maxOf { it.y }
        return Cv.clampRect(Rect(x0.toInt(), y0.toInt(), (x1 - x0).toInt(), (y1 - y0).toInt()), w, h)
    }

    /** Página de 1000 x 1400 con la foto [ph] (o una sintética) en un sitio al azar; con [neg], además, una zona oscura. */
    fun positive(ph: Mat?, seed: Int, neg: Neg? = null): Scene {
        val rnd = Random(seed)
        val w = 1000; val h = 1400
        val pw = (w * rnd.nextDouble(0.3, 0.6)).toInt()
        val asp = if (ph != null) ph.rows().toDouble() / ph.cols() else 0.7
        val phh = min((pw * asp).toInt(), (h * 0.45).toInt())
        val r = Rect(rnd.nextInt(70, w - 70 - pw), rnd.nextInt(150, h - 150 - phh), pw, phh)
        val m = textPage(w, h, rnd, skip = listOf(Rect(r.x - 20, r.y - 20, r.width + 40, r.height + 40)))
        val src = ph ?: synthPhoto(pw, phh, seed)
        pastePhoto(m, src, r, rnd, gray = rnd.nextInt(4) == 0)
        if (ph == null) src.release()
        val dark = neg?.let { negative(m, it, rnd) }
        capture(m, rnd)
        return Scene(m, listOf(r), dark, "pos_$seed" + (neg?.let { "_" + it.name } ?: ""))
    }

    fun negativeScene(t: Neg, seed: Int): Scene {
        val rnd = Random(seed)
        val m = textPage(1000, 1400, rnd, table = rnd.nextInt(3) == 0)
        val dark = negative(m, t, rnd)
        capture(m, rnd)
        return Scene(m, emptyList(), dark, "neg_${t.name}_$seed")
    }
}
