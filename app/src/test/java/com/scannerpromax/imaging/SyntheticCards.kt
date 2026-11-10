package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tarjetas ID-1 SINTÉTICAS con la paleta y la maquetación del DNI peruano (RENIEC), sin datos reales: DNI azul
 * (celeste con trama azul más oscura), DNIe (policarbonato celeste en degradado, chip dorado, zonas blancas, ventana
 * holográfica) y DNI amarillo de menores; anverso (foto, textos, holograma) y reverso (huella, código de barras y la
 * banda MRZ TD1 clara de 3 x 30 caracteres «I<PER…» que toca el canto inferior). Se pegan con perspectiva, giro,
 * esquinas redondeadas ISO, sombras y reflejos sobre fondos variados. Todo inventado.
 */
internal object SyntheticCards {

    enum class Palette { AZUL, DNIE, AMARILLO, DNIE3 }
    enum class Background { MADERA, MESA_BLANCA, OSCURO, ESTAMPADO, TURQUESA, VIOLETA, AZUL_MARINO }

    class Scene(val rgb: Mat, val gt: DoubleArray, val guide: DoubleArray, val name: String)

    private fun s(r: Int, g: Int, b: Int) = Scalar(r.toDouble(), g.toDouble(), b.toDouble())

    /** Cara de la tarjeta (RGB, [w] x w/1.586) sin esquinas redondeadas (las pone [scene]). */
    fun card(p: Palette, back: Boolean, w: Int, rnd: Random): Mat {
        val h = (w / CardColorDetector.ID1_RATIO).toInt()
        val m = Mat(h, w, CvType.CV_8UC3)
        // DNI azul: celeste/cian intenso con trama azul; DNIe 2.0: celeste; amarillo de menores; DNIe 3.0: verde grisáceo
        // con acentos amarillos, violetas y azules (policarbonato multicolor)
        val base = when (p) { Palette.AZUL -> s(118, 198, 226); Palette.DNIE -> s(160, 200, 232); Palette.AMARILLO -> s(246, 222, 122); Palette.DNIE3 -> s(176, 190, 168) }
        val trama = when (p) { Palette.AZUL -> s(64, 150, 200); Palette.DNIE -> s(110, 155, 210); Palette.AMARILLO -> s(226, 178, 84); Palette.DNIE3 -> s(150, 120, 180) }
        // DNI azul y amarillo (no electrónicos): la MRZ va en el ANVERSO, abajo, sobre el color de la tarjeta; el reverso
        // lleva la cuadrícula de constancias de sufragio, la huella y un código PDF417 abajo. DNIe: MRZ en el reverso.
        val mrzFront = p == Palette.AZUL || p == Palette.AMARILLO
        m.setTo(base)
        if (p == Palette.DNIE) {
            // degradado del policarbonato hacia un celeste más claro
            for (x in 0 until w step 4) {
                val t = x.toDouble() / w
                Imgproc.rectangle(m, Point(x.toDouble(), 0.0), Point(x + 4.0, h.toDouble()),
                    Scalar(base.`val`[0] + 45 * t, base.`val`[1] + 28 * t, base.`val`[2] + 10 * t), -1)
            }
        }
        // guilloche: ondas finas
        val step = max(3, w / 120)
        var k = 0
        while (k < h + w / 3) {
            val pts = (0..60).map { i -> val x = w * i / 60.0; Point(x, k + 10 * sin(x / w * 12 + k * 0.1)) }
            Imgproc.polylines(m, listOf(MatOfPoint(*pts.toTypedArray())), false, trama, 1, Imgproc.LINE_AA)
            k += step * 3
        }
        for (r in 1..6) Imgproc.circle(m, Point(w * 0.62, h * 0.55), (h * 0.07 * r).toInt(), trama, 1, Imgproc.LINE_AA)
        if (p == Palette.DNIE3) {
            Imgproc.ellipse(m, Point(w * 0.75, h * 0.35), Size(w * 0.2, h * 0.15), 0.0, 0.0, 360.0, s(225, 205, 110), -1)
            Imgproc.ellipse(m, Point(w * 0.55, h * 0.75), Size(w * 0.15, h * 0.1), 0.0, 0.0, 360.0, s(120, 150, 200), -1)
        }
        val mrzLines = listOf("I<PER00000000<0<<<<<<<<<<<<<<<", "0001011M3001012PER<<<<<<<<<<<0", "PRUEBA<<SINTETICA<EJEMPLO<<<<<")
        val ink = s(28, 38, 70)
        val fs = w / 900.0
        if (!back) {
            Imgproc.putText(m, "REPUBLICA DEL PERU", Point(w * 0.05, h * 0.10), Imgproc.FONT_HERSHEY_DUPLEX, 0.9 * fs, ink, max(1, (2 * fs).toInt()))
            Imgproc.putText(m, "DOCUMENTO NACIONAL DE IDENTIDAD", Point(w * 0.05, h * 0.17), Imgproc.FONT_HERSHEY_SIMPLEX, 0.7 * fs, ink, 1)
            // foto (gris/piel, inventada) que casi toca el canto izquierdo
            val px0 = (w * 0.04).toInt(); val py0 = (h * 0.24).toInt(); val px1 = (w * 0.30).toInt(); val py1 = (h * 0.88).toInt()
            Imgproc.rectangle(m, Point(px0.toDouble(), py0.toDouble()), Point(px1.toDouble(), py1.toDouble()), s(205, 200, 195), -1)
            Imgproc.ellipse(m, Point((px0 + px1) / 2.0, py0 + (py1 - py0) * 0.42), Size((px1 - px0) * 0.28, (py1 - py0) * 0.25), 0.0, 0.0, 360.0, s(190, 150, 125), -1)
            Imgproc.ellipse(m, Point((px0 + px1) / 2.0, py1.toDouble()), Size((px1 - px0) * 0.45, (py1 - py0) * 0.3), 0.0, 180.0, 360.0, s(40, 40, 50), -1)
            val labels = listOf("Primer Apellido", "PRUEBA", "Segundo Apellido", "SINTETICA", "Pre Nombres", "EJEMPLO DE TARJETA", "Fecha de Nacimiento", "01 01 2000")
            for ((i, t) in labels.withIndex()) {
                val big = i % 2 == 1
                Imgproc.putText(m, t, Point(w * 0.35, h * (0.30 + i * 0.075)), Imgproc.FONT_HERSHEY_SIMPLEX, (if (big) 0.85 else 0.55) * fs, ink, if (big) max(1, (2 * fs).toInt()) else 1)
            }
            Imgproc.putText(m, "00000000-0", Point(w * 0.70, h * 0.10), Imgproc.FONT_HERSHEY_DUPLEX, 1.0 * fs, s(160, 30, 40), max(1, (2 * fs).toInt()))
            // holograma iridiscente sobre la foto
            val hc = Point(w * 0.27, h * 0.78)
            for (r in 0 until 8) {
                val c = Scalar(200 + 50 * sin(r * 0.9), 200 + 50 * sin(r * 0.9 + 2.0), 200 + 50 * sin(r * 0.9 + 4.0))
                Imgproc.ellipse(m, hc, Size(h * 0.12 - r * 4 * fs, h * 0.09 - r * 3 * fs), 20.0, 0.0, 360.0, c, max(1, (3 * fs).toInt()))
            }
            if (p == Palette.DNIE) {
                // chip dorado y zona blanca
                Imgproc.rectangle(m, Point(w * 0.36, h * 0.82 - h * 0.14), Point(w * 0.46, h * 0.82), s(212, 182, 98), -1)
                Imgproc.rectangle(m, Point(w * 0.37, h * 0.70), Point(w * 0.45, h * 0.80), s(170, 140, 70), 1)
                Imgproc.rectangle(m, Point(w * 0.78, h * 0.62), Point(w * 0.96, h * 0.92), s(242, 245, 247), -1)
                Imgproc.putText(m, "FIRMA", Point(w * 0.80, h * 0.80), Imgproc.FONT_HERSHEY_SCRIPT_SIMPLEX, 0.9 * fs, ink, 1)
            }
            if (mrzFront) {
                // franja inferior con la MRZ impresa sobre el color de la tarjeta
                val my0 = h * 0.70
                Imgproc.rectangle(m, Point(0.0, my0), Point(w.toDouble(), h.toDouble()), base, -1)
                for (kk in 0 until 5) {
                    val y0 = my0 + kk * h * 0.06
                    val pts = (0..60).map { i -> val x = w * i / 60.0; Point(x, y0 + 6 * sin(x / w * 14 + kk)) }
                    Imgproc.polylines(m, listOf(MatOfPoint(*pts.toTypedArray())), false, trama, 1, Imgproc.LINE_AA)
                }
                for ((i, t) in mrzLines.withIndex()) Imgproc.putText(m, t, Point(w * 0.04, my0 + h * (0.09 + i * 0.09)), Imgproc.FONT_HERSHEY_PLAIN, 2.3 * fs, s(25, 25, 30), max(1, (2 * fs).toInt()))
            }
        } else if (mrzFront) {
            // cuadrícula de constancias de sufragio, huella y PDF417 abajo
            for (r in 0 until 2) for (c in 0 until 4) {
                val x0 = w * (0.04 + c * 0.17); val y0 = h * (0.06 + r * 0.16)
                Imgproc.rectangle(m, Point(x0, y0), Point(x0 + w * 0.16, y0 + h * 0.15), s(30, 60, 110), max(1, (1.5 * fs).toInt()))
                Imgproc.putText(m, "CONSTANCIA", Point(x0 + w * 0.01, y0 + h * 0.08), Imgproc.FONT_HERSHEY_SIMPLEX, 0.4 * fs, s(30, 60, 110), 1)
            }
            val fc = Point(w * 0.85, h * 0.28)
            for (r in 1..14) Imgproc.ellipse(m, fc, Size(r * 4.5 * fs, r * 6.0 * fs), 10.0, 0.0, 330.0, s(70, 70, 80), max(1, (1.5 * fs).toInt()))
            Imgproc.putText(m, "Direccion de prueba sintetica", Point(w * 0.04, h * 0.48), Imgproc.FONT_HERSHEY_SIMPLEX, 0.55 * fs, ink, 1)
            Imgproc.rectangle(m, Point(w * 0.04, h * 0.70), Point(w * 0.80, h * 0.95), s(250, 250, 250), -1)
            var by = h * 0.71
            while (by < h * 0.94) {
                var bx = w * 0.05
                while (bx < w * 0.79) {
                    val bw = (2 + rnd.nextInt(6)) * fs
                    if (rnd.nextBoolean()) Imgproc.rectangle(m, Point(bx, by), Point(bx + bw, by + h * 0.02), s(15, 15, 20), -1)
                    bx += bw
                }
                by += h * 0.02
            }
        } else {
            // huella (elipses concéntricas), código de barras, textos
            val fc = Point(w * 0.83, h * 0.30)
            for (r in 1..14) Imgproc.ellipse(m, fc, Size(r * 4.5 * fs, r * 6.0 * fs), 10.0, 0.0, 330.0, s(70, 70, 80), max(1, (1.5 * fs).toInt()))
            var x = (w * 0.05).toInt()
            while (x < w * 0.45) {
                val bw = 1 + rnd.nextInt(4)
                Imgproc.rectangle(m, Point(x.toDouble(), h * 0.08), Point((x + bw).toDouble(), h * 0.22), s(20, 20, 25), -1)
                x += bw + 1 + rnd.nextInt(3)
            }
            for (i in 0 until 4) Imgproc.putText(m, "Domicilio de prueba sintetico $i", Point(w * 0.05, h * (0.33 + i * 0.07)), Imgproc.FONT_HERSHEY_SIMPLEX, 0.55 * fs, ink, 1)
            // banda MRZ clara que toca el canto inferior (3 x 30)
            val my0 = h * 0.68
            Imgproc.rectangle(m, Point(0.0, my0), Point(w.toDouble(), h.toDouble()), s(240, 242, 238), -1)
            for ((i, t) in mrzLines.withIndex()) Imgproc.putText(m, t, Point(w * 0.04, my0 + h * (0.10 + i * 0.095)), Imgproc.FONT_HERSHEY_PLAIN, 2.3 * fs, s(25, 25, 30), max(1, (2 * fs).toInt()))
        }
        return m
    }

    fun background(b: Background, w: Int, h: Int, rnd: Random): Mat {
        val m: Mat = when (b) {
            Background.MADERA -> SyntheticScenes.wood(w, h)
            Background.MESA_BLANCA -> Mat(h, w, CvType.CV_8UC3, s(234, 233, 228))
            Background.OSCURO -> Mat(h, w, CvType.CV_8UC3, s(34, 32, 38))
            Background.ESTAMPADO -> Mat(h, w, CvType.CV_8UC3, s(200, 60, 50)).also { m ->
                val cols = listOf(s(240, 200, 40), s(40, 140, 70), s(230, 230, 220), s(200, 60, 50))
                for (y in 0 until h step 60) for (x in 0 until w step 60) {
                    Imgproc.rectangle(m, Point(x.toDouble(), y.toDouble()), Point(x + 60.0, y + 60.0), cols[((x + y) / 60) % 4], -1)
                }
            }
            Background.TURQUESA -> Mat(h, w, CvType.CV_8UC3, s(40, 128, 128))
            Background.VIOLETA -> Mat(h, w, CvType.CV_8UC3, s(98, 84, 170))
            Background.AZUL_MARINO -> Mat(h, w, CvType.CV_8UC3, s(28, 44, 100))
        }
        if (b == Background.TURQUESA || b == Background.VIOLETA || b == Background.AZUL_MARINO || b == Background.MESA_BLANCA || b == Background.OSCURO) {
            // textura de tela / mesa
            val f = Mat(); m.convertTo(f, CvType.CV_16SC3)
            val n16 = Mat(h, w, CvType.CV_16SC3); Core.randn(n16, 0.0, 5.0)
            for (i in 0 until 300) {
                val y = rnd.nextInt(h).toDouble()
                val d = rnd.nextInt(17) - 8.0
                Imgproc.line(n16, Point(0.0, y), Point(w.toDouble(), y + rnd.nextInt(40) - 20), Scalar(d, d, d), 1)
            }
            Core.add(f, n16, f); f.convertTo(m, CvType.CV_8UC3)
            f.release(); n16.release()
        }
        return m
    }

    /**
     * Escena completa [sw] x [sh]: tarjeta [p] (anverso o reverso) sobre [b], girada [deg]°, con perspectiva
     * aleatoria, esquinas redondeadas, y opcionalmente sombra y reflejo.
     */
    fun scene(p: Palette, back: Boolean, b: Background, seed: Long, sw: Int = 1600, sh: Int = 1200, shadow: Boolean = false, glare: Boolean = false, deg: Double? = null, hand: Boolean = false, blur: Boolean = false): Scene {
        val rnd = Random(seed)
        val bg = background(b, sw, sh, rnd)
        val cw = 1000
        val card = card(p, back, cw, rnd)
        val ch = card.rows()
        // esquinas redondeadas ISO (3,18 mm sobre 85,6 mm)
        val alpha = Mat(ch, cw, CvType.CV_8UC1, Scalar(0.0))
        val r = (cw * 3.18 / 85.6).toInt()
        Imgproc.rectangle(alpha, Point(r.toDouble(), 0.0), Point((cw - r).toDouble(), ch.toDouble()), Scalar(255.0), -1)
        Imgproc.rectangle(alpha, Point(0.0, r.toDouble()), Point(cw.toDouble(), (ch - r).toDouble()), Scalar(255.0), -1)
        for ((x, y) in listOf(r to r, cw - r - 1 to r, cw - r - 1 to ch - r - 1, r to ch - r - 1)) Imgproc.circle(alpha, Point(x.toDouble(), y.toDouble()), r, Scalar(255.0), -1, Imgproc.LINE_AA)
        // destino: tamaño 45-65 % del ancho, giro y perspectiva
        val scale = (0.45 + 0.2 * rnd.nextDouble()) * sw / cw
        val a = Math.toRadians(deg ?: (rnd.nextDouble() * 50 - 25))
        val cx = sw / 2.0 + (rnd.nextDouble() - 0.5) * sw * 0.08; val cy = sh / 2.0 + (rnd.nextDouble() - 0.5) * sh * 0.08
        val src = doubleArrayOf(0.0, 0.0, cw.toDouble(), 0.0, cw.toDouble(), ch.toDouble(), 0.0, ch.toDouble())
        val gt = DoubleArray(8)
        for (i in 0 until 4) {
            val x = (src[i * 2] - cw / 2.0) * scale; val y = (src[i * 2 + 1] - ch / 2.0) * scale
            val jx = (rnd.nextDouble() - 0.5) * cw * scale * 0.08; val jy = (rnd.nextDouble() - 0.5) * cw * scale * 0.08
            gt[i * 2] = cx + x * cos(a) - y * sin(a) + jx; gt[i * 2 + 1] = cy + x * sin(a) + y * cos(a) + jy
        }
        val hm = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(*Array(4) { Point(src[it * 2], src[it * 2 + 1]) }),
            MatOfPoint2f(*Array(4) { Point(gt[it * 2], gt[it * 2 + 1]) }),
        )
        val warped = Mat(); val wa = Mat()
        Imgproc.warpPerspective(card, warped, hm, Size(sw.toDouble(), sh.toDouble()), Imgproc.INTER_LINEAR)
        Imgproc.warpPerspective(alpha, wa, hm, Size(sw.toDouble(), sh.toDouble()), Imgproc.INTER_LINEAR)
        // composición con alfa suave (borde antialias)
        blend(bg, warped, wa)
        // sombra suave de la tarjeta sobre el fondo y sombra de la mano
        if (shadow) {
            val sm = Mat(sh, sw, CvType.CV_8UC1, Scalar(0.0))
            val x0 = rnd.nextInt(sw / 2)
            Imgproc.fillPoly(sm, listOf(MatOfPoint(Point(x0.toDouble(), 0.0), Point(x0 + sw * 0.5, 0.0), Point(x0 + sw * 0.2, sh.toDouble()), Point(x0 - sw * 0.3, sh.toDouble()))), Scalar(255.0))
            Imgproc.GaussianBlur(sm, sm, Size(0.0, 0.0), 40.0)
            darken(bg, sm, 0.38)
            sm.release()
        }
        if (glare) {
            val gm = Mat(sh, sw, CvType.CV_8UC1, Scalar(0.0))
            // reflejo cerca de un canto de la tarjeta
            val t = rnd.nextDouble()
            val gx = gt[0] + (gt[2] - gt[0]) * t * 0.8 + (gt[6] - gt[0]) * 0.15; val gy = gt[1] + (gt[3] - gt[1]) * t * 0.8 + (gt[7] - gt[1]) * 0.15
            Imgproc.ellipse(gm, Point(gx, gy), Size(cw * scale * 0.12, cw * scale * 0.06), rnd.nextDouble() * 180, 0.0, 360.0, Scalar(255.0), -1)
            Imgproc.GaussianBlur(gm, gm, Size(0.0, 0.0), 18.0)
            lighten(bg, gm, 0.85)
            gm.release()
        }
        if (hand) {
            // mano que sujeta la tarjeta: palma y dedos (tono piel) tapando parte de un canto
            val e = rnd.nextInt(4); val t = 0.3 + 0.4 * rnd.nextDouble()
            val ax = gt[e * 2]; val ay = gt[e * 2 + 1]; val bx = gt[((e + 1) % 4) * 2]; val by = gt[((e + 1) % 4) * 2 + 1]
            val px = ax + (bx - ax) * t; val py = ay + (by - ay) * t
            val ccx = (gt[0] + gt[2] + gt[4] + gt[6]) / 4; val ccy = (gt[1] + gt[3] + gt[5] + gt[7]) / 4
            val dx = px - ccx; val dy = py - ccy; val dl = hypot(dx, dy)
            val ox = px + dx / dl * cw * scale * 0.18; val oy = py + dy / dl * cw * scale * 0.18
            val skin = s(214, 170, 140)
            Imgproc.ellipse(bg, Point(ox, oy), Size(cw * scale * 0.2, cw * scale * 0.14), Math.toDegrees(kotlin.math.atan2(dy, dx)), 0.0, 360.0, skin, -1, Imgproc.LINE_AA)
            for (f in -1..1) {
                val fx = px + (bx - ax) / hypot(bx - ax, by - ay) * f * cw * scale * 0.05
                val fy = py + (by - ay) / hypot(bx - ax, by - ay) * f * cw * scale * 0.05
                Imgproc.line(bg, Point(ox, oy), Point(fx - dx / dl * cw * scale * 0.03, fy - dy / dl * cw * scale * 0.03), s(205, 160, 132), (cw * scale * 0.035).toInt(), Imgproc.LINE_AA)
            }
        }
        Imgproc.GaussianBlur(bg, bg, Size(3.0, 3.0), 0.0)
        if (blur) Imgproc.GaussianBlur(bg, bg, Size(0.0, 0.0), 2.5)
        card.release(); alpha.release(); warped.release(); wa.release(); hm.release()
        // marco guía (como en la cámara): la caja de la verdad ampliada un 12 %
        val xs = (0 until 4).map { gt[it * 2] }; val ys = (0 until 4).map { gt[it * 2 + 1] }
        val bw = xs.max() - xs.min(); val bh = ys.max() - ys.min()
        val guide = doubleArrayOf(
            ((xs.min() - bw * 0.06) / sw).coerceIn(0.0, 1.0), ((ys.min() - bh * 0.06) / sh).coerceIn(0.0, 1.0),
            ((xs.max() + bw * 0.06) / sw).coerceIn(0.0, 1.0), ((ys.max() + bh * 0.06) / sh).coerceIn(0.0, 1.0),
        )
        val name = "${p.name.lowercase()}_${if (back) "reverso" else "anverso"}_${b.name.lowercase()}_$seed${if (shadow) "_sombra" else ""}${if (glare) "_reflejo" else ""}${if (hand) "_mano" else ""}${if (blur) "_borrosa" else ""}"
        return Scene(bg, gt, guide, name)
    }

    private fun blend(dst: Mat, src: Mat, alpha: Mat) {
        val n = dst.rows() * dst.cols()
        val d = ByteArray(n * 3); dst.get(0, 0, d)
        val sArr = ByteArray(n * 3); src.get(0, 0, sArr)
        val a = ByteArray(n); alpha.get(0, 0, a)
        for (i in 0 until n) {
            val av = (a[i].toInt() and 0xFF)
            if (av == 0) continue
            for (c in 0 until 3) {
                val o = i * 3 + c
                d[o] = (((d[o].toInt() and 0xFF) * (255 - av) + (sArr[o].toInt() and 0xFF) * av) / 255).toByte()
            }
        }
        dst.put(0, 0, d)
    }

    private fun darken(dst: Mat, m: Mat, amount: Double) {
        val n = dst.rows() * dst.cols()
        val d = ByteArray(n * 3); dst.get(0, 0, d); val a = ByteArray(n); m.get(0, 0, a)
        for (i in 0 until n) {
            val f = 1.0 - amount * (a[i].toInt() and 0xFF) / 255.0
            for (c in 0 until 3) { val o = i * 3 + c; d[o] = ((d[o].toInt() and 0xFF) * f).toInt().toByte() }
        }
        dst.put(0, 0, d)
    }

    private fun lighten(dst: Mat, m: Mat, amount: Double) {
        val n = dst.rows() * dst.cols()
        val d = ByteArray(n * 3); dst.get(0, 0, d); val a = ByteArray(n); m.get(0, 0, a)
        for (i in 0 until n) {
            val f = amount * (a[i].toInt() and 0xFF) / 255.0
            for (c in 0 until 3) { val o = i * 3 + c; val v = d[o].toInt() and 0xFF; d[o] = (v + (255 - v) * f).toInt().toByte() }
        }
        dst.put(0, 0, d)
    }

    // ------------------------------------------------------------------ métricas

    /** Error medio de esquinas (px) con el mejor emparejamiento cíclico (el orden de inicio puede variar). */
    fun cornerError(q: DoubleArray, gt: DoubleArray): Double {
        var best = Double.MAX_VALUE
        for (shift in 0 until 4) for (rev in listOf(false, true)) {
            var e = 0.0
            for (i in 0 until 4) {
                val j = if (rev) (shift - i + 8) % 4 else (shift + i) % 4
                e += hypot(q[j * 2] - gt[i * 2], q[j * 2 + 1] - gt[i * 2 + 1])
            }
            best = min(best, e / 4)
        }
        return best
    }

    fun iou(q: DoubleArray, gt: DoubleArray, w: Int, h: Int): Double {
        val a = SyntheticScenes.polyMask(w, h, q); val b = SyntheticScenes.polyMask(w, h, gt)
        val i = Mat(); Core.bitwise_and(a, b, i); val u = Mat(); Core.bitwise_or(a, b, u)
        val r = Core.countNonZero(i).toDouble() / max(1, Core.countNonZero(u))
        a.release(); b.release(); i.release(); u.release()
        return r
    }

    fun guideQuad(g: DoubleArray, w: Int, h: Int) = doubleArrayOf(g[0] * w, g[1] * h, g[2] * w, g[1] * h, g[2] * w, g[3] * h, g[0] * w, g[3] * h)
}
