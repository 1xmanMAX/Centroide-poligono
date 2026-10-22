package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * Escenas sintéticas para los tests JVM de detección y orientación (OpenCV de escritorio): hoja con texto sobre madera
 * con vetas, tela roja o fondo oscuro, girada, con un "teclado" encima. Nada de fotos reales.
 */
internal object SyntheticScenes {

    /** OpenCV de escritorio disponible (org.openpnp:opencv en el classpath de test). */
    val openCv: Boolean by lazy { runCatching { nu.pattern.OpenCV.loadLocally(); Mat(1, 1, CvType.CV_8UC1).release(); true }.getOrDefault(false) }

    private val LINES = listOf(
        "Pregunta 1. Explique el comportamiento de la estructura",
        "bajo cargas sismicas y detalle el procedimiento de calculo",
        "Considere la rigidez lateral de las columnas y la losa.",
        "El edificio de concreto armado tiene planta rectangular",
        "y altura total de doce metros; justifique cada hipotesis.",
        "Indique la frecuencia natural y el periodo fundamental.",
        "Pregunta 2. Calcule el desplazamiento maximo del techo",
        "y la fuerza cortante en la base del portico principal.",
        "Utilice los datos de la tabla y redondee los resultados.",
        "Dibuje el diagrama de momentos flectores de la viga.",
        "Pregunta 3. Describa el proceso constructivo de la zapata",
        "desde el trazo y replanteo hasta el vaciado del concreto.",
        "Explique por que el refuerzo debe quedar bien anclado.",
        "Justifique la eleccion del tipo de cemento utilizado.",
    )

    /** Página blanca [w]x[h] (RGB) con renglones de texto horizontales (derecha). */
    fun textPage(w: Int, h: Int, scale: Double = 0.9, paper: Scalar = Scalar(236.0, 235.0, 230.0)): Mat {
        val m = Mat(h, w, CvType.CV_8UC3, paper)
        var y = (h * 0.1).toInt()
        val step = (34 * scale / 0.9).toInt()
        var i = 0
        while (y < h * 0.93) {
            Imgproc.putText(m, LINES[i % LINES.size], Point(w * 0.06, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, scale, Scalar(35.0, 35.0, 40.0), 2, Imgproc.LINE_AA)
            y += step; i++
        }
        return m
    }

    /** Página con sólo una cuadrícula (sin texto): la orientación no debe decidir nada. */
    fun gridPage(w: Int, h: Int): Mat {
        val m = Mat(h, w, CvType.CV_8UC3, Scalar(236.0, 235.0, 230.0))
        var x = 20; while (x < w) { Imgproc.line(m, Point(x.toDouble(), 0.0), Point(x.toDouble(), h.toDouble()), Scalar(120.0, 120.0, 130.0), 1); x += 22 }
        var y = 20; while (y < h) { Imgproc.line(m, Point(0.0, y.toDouble()), Point(w.toDouble(), y.toDouble()), Scalar(120.0, 120.0, 130.0), 1); y += 22 }
        return m
    }

    /** Madera clara con vetas (RGB). */
    fun wood(w: Int, h: Int): Mat {
        val b = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = 14 * sin(y * 0.045 + 2.5 * sin(x * 0.012)) + 6 * sin(y * 0.21 + x * 0.01)
            val o = (y * w + x) * 3
            b[o] = (200 + v).toInt().coerceIn(0, 255).toByte(); b[o + 1] = (152 + v * 0.8).toInt().coerceIn(0, 255).toByte(); b[o + 2] = (100 + v * 0.6).toInt().coerceIn(0, 255).toByte()
        }
        val m = Mat(h, w, CvType.CV_8UC3); m.put(0, 0, b)
        return m
    }

    /** Pega [page] en [scene] con la transformación afín (giro [deg] alrededor del centro y traslación a [cx],[cy]). */
    fun paste(scene: Mat, page: Mat, deg: Double, cx: Double, cy: Double): DoubleArray {
        val rm = Imgproc.getRotationMatrix2D(Point(page.cols() / 2.0, page.rows() / 2.0), deg, 1.0)
        val a = DoubleArray(6); rm.get(0, 0, a)
        a[2] += cx - page.cols() / 2.0; a[5] += cy - page.rows() / 2.0
        rm.put(0, 0, *a)
        val warped = Mat(); val mask = Mat()
        Imgproc.warpAffine(page, warped, rm, scene.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
        val ones = Mat(page.size(), CvType.CV_8UC1, Scalar(255.0))
        Imgproc.warpAffine(ones, mask, rm, scene.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
        warped.copyTo(scene, mask)
        val pw = page.cols().toDouble(); val ph = page.rows().toDouble()
        val corners = doubleArrayOf(0.0, 0.0, pw, 0.0, pw, ph, 0.0, ph)
        val out = DoubleArray(8)
        for (i in 0 until 4) {
            val x = corners[i * 2]; val y = corners[i * 2 + 1]
            out[i * 2] = a[0] * x + a[1] * y + a[2]; out[i * 2 + 1] = a[3] * x + a[4] * y + a[5]
        }
        rm.release(); warped.release(); mask.release(); ones.release()
        return out
    }

    /** "Teclado": rectángulo oscuro con teclas algo más claras. */
    fun keyboard(scene: Mat, x0: Int, y0: Int, x1: Int, y1: Int) {
        Imgproc.rectangle(scene, Point(x0.toDouble(), y0.toDouble()), Point(x1.toDouble(), y1.toDouble()), Scalar(38.0, 38.0, 44.0), -1)
        var y = y0 + 8
        while (y < y1 - 30) {
            var x = x0 + 8
            while (x < x1 - 30) {
                Imgproc.rectangle(scene, Point(x.toDouble(), y.toDouble()), Point(x + 34.0, y + 34.0), Scalar(55.0, 55.0, 62.0), -1)
                Imgproc.putText(scene, "K", Point(x + 10.0, y + 24.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, Scalar(200.0, 200.0, 200.0), 1)
                x += 42
            }
            y += 42
        }
    }

    fun polyMask(w: Int, h: Int, q: DoubleArray): Mat {
        val m = Mat(h, w, CvType.CV_8UC1, Scalar(0.0))
        Imgproc.fillPoly(m, listOf(MatOfPoint(*Array(4) { Point(q[it * 2], q[it * 2 + 1]) })), Scalar(255.0))
        return m
    }

    /** Ejecuta la detección de la app (foto reducida a 500 px + esquinas a resolución completa) sobre [rgb]. */
    fun detect(det: DocumentDetector, rgb: Mat): DetectionResult? {
        val gray = Mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val sm = Mat(); Cv.downscale(rgb, sm, 500)
        try {
            return det.detectScaled(sm, rgb.cols(), rgb.rows()) { x0, y0, rw, rh -> gray.submat(y0, y0 + rh, x0, x0 + rw).clone() }
        } finally { gray.release(); sm.release() }
    }

    fun quadArray(r: DetectionResult): DoubleArray {
        val p = r.quad.points()
        return DoubleArray(8) { i -> (if (i % 2 == 0) p[i / 2].x else p[i / 2].y).toDouble() }
    }

    @Suppress("unused")
    fun rot(x: Double, y: Double, deg: Double): Pair<Double, Double> {
        val t = Math.toRadians(deg); return (x * cos(t) - y * sin(t)) to (x * sin(t) + y * cos(t))
    }

    @Suppress("unused")
    val tiny = Size(1.0, 1.0)
}
