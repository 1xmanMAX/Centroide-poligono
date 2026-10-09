package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Polaridad de la página: ¿escritura CLARA sobre fondo OSCURO (pizarra negra o verde, diapositiva o pantalla en
 * modo oscuro)?
 *
 * El fondo es lo mayoritario en cada entorno: se estima con una mediana grande (~6 % del lado, a ~600 px) y se
 * cuentan los píxeles claramente MÁS CLAROS (tiza) y MÁS OSCUROS (tinta) que él. En papel, pizarra blanca,
 * recibos o tarjetas domina la tinta oscura (relación claro/oscuro < 1.2 en todo el banco de pruebas); en una
 * pizarra de tiza, la clara (relación >= 2.6). La decisión no depende de la exposición: una foto oscura de papel
 * sigue teniendo la tinta más oscura que el papel.
 *
 * Los filtros de documento suponen tinta oscura sobre papel claro: con [invertLuma] la tiza pasa a ser tinta
 * oscura sobre blanco (conservando el tono de las tizas de color) y el resto de la tubería funciona igual.
 */
internal object PrintPolarity {

    /** Lado de trabajo de la estimación (barato: ~5-15 ms). */
    private const val SIDE = 600

    /** Diferencia mínima con el fondo local para contar un píxel como trazo claro u oscuro. */
    private const val DELTA = 25.0

    /** Relación mínima trazos claros / trazos oscuros para considerar la polaridad invertida. */
    const val MIN_RATIO = 2.0

    /** Fracción mínima de trazos claros (descarta escenas casi vacías: una pizarra sin nada escrito). */
    const val MIN_BRIGHT = 0.015

    data class Stats(val bright: Double, val dark: Double, val background: Double) {
        val ratio: Double get() = bright / (dark + 1e-3)
        val inverted: Boolean get() = isInverted(bright, dark)
    }

    /** Regla de decisión (pura, testeable). */
    fun isInverted(bright: Double, dark: Double): Boolean =
        bright >= MIN_BRIGHT && bright / (dark + 1e-3) >= MIN_RATIO

    fun analyze(rgb: Mat): Stats = MatBag().use { bag ->
        val q = bag.mat()
        Cv.downscale(rgb, q, SIDE)
        val g = if (q.channels() == 1) q else bag.add(Cv.gray(q))
        val k = Cv.odd(max(5, (max(g.cols(), g.rows()) * 0.06).roundToInt()))
        val med = bag.mat(); Imgproc.medianBlur(g, med, k)
        val dv = bag.mat(); val m = bag.mat()
        val total = max(1.0, g.total().toDouble())
        Core.subtract(g, med, dv); Core.compare(dv, Scalar(DELTA), m, Core.CMP_GT)
        val br = Core.countNonZero(m) / total
        Core.subtract(med, g, dv); Core.compare(dv, Scalar(DELTA), m, Core.CMP_GT)
        val dk = Core.countNonZero(m) / total
        Stats(br, dk, Cv.percentile(Cv.histogram(med), 0.5).toDouble())
    }

    /** Invierte la luminancia (Y de YCrCb) conservando el croma: tiza blanca -> negro, tiza amarilla -> ocre. Nuevo Mat. */
    fun invertLuma(rgb: Mat): Mat = MatBag().use { bag ->
        val ycc = bag.mat(); Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
        val y = bag.mat(); Core.extractChannel(ycc, y, 0)
        Core.bitwise_not(y, y)
        Core.insertChannel(y, ycc, 0)
        val out = Mat(); Imgproc.cvtColor(ycc, out, Imgproc.COLOR_YCrCb2RGB)
        out
    }
}
