package com.scannerpromax.imaging

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** Sólo para depurar a mano: DNI_DEBUG="PALETA,reverso(0/1),FONDO,semilla,variante(0/1);..." y DNI_DEBUG_DIR. */
class CardColorDebugTest {
    /** CARD_DEBUG_FILES="a.png:b.png" (fotos de un banco externo) + DNI_DEBUG_DIR. */
    @Test fun depurarArchivos() {
        val spec = System.getenv("CARD_DEBUG_FILES") ?: return
        assumeTrue(SyntheticScenes.openCv)
        val dir = File(System.getenv("DNI_DEBUG_DIR") ?: "/tmp").also { it.mkdirs() }
        for (path in spec.split(':')) {
            val bgr = Imgcodecs.imread(path)
            val rgb = Mat(); Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
            val name = File(path).parentFile.name + "_" + File(path).nameWithoutExtension
            println("== $name")
            CardColorDetector.debug = { n, x ->
                when (x) {
                    is Mat -> Imgcodecs.imwrite(File(dir, "${name}_$n.png").absolutePath, x)
                    is DoubleArray -> println("  $n: ${x.joinToString { "%.0f".format(it) }}")
                    else -> println("  $n: $x")
                }
            }
            val r = CardColorDetector.detect(rgb, null)
            CardColorDetector.debug = null
            r?.let { Imgproc.polylines(bgr, listOf(MatOfPoint(*Array(4) { i -> Point(it.quad[i * 2], it.quad[i * 2 + 1]) })), true, Scalar(0.0, 0.0, 255.0), 3) }
            val small = Mat(); Imgproc.resize(bgr, small, org.opencv.core.Size(bgr.cols() / 2.0, bgr.rows() / 2.0))
            Imgcodecs.imwrite(File(dir, "${name}_vis.jpg").absolutePath, small)
            println("  conf=${r?.confidence} rec=${r?.recoveredSide}")
        }
    }

    @Test fun depurar() {
        val spec = System.getenv("DNI_DEBUG") ?: return
        assumeTrue(SyntheticScenes.openCv)
        val dir = File(System.getenv("DNI_DEBUG_DIR") ?: "/tmp").also { it.mkdirs() }
        for (one in spec.split(';')) {
            val f = one.split(',')
            val v = f[4] == "1"
            val sc = SyntheticCards.scene(SyntheticCards.Palette.valueOf(f[0]), f[1] == "1", SyntheticCards.Background.valueOf(f[2]), f[3].toLong(), shadow = v, glare = v)
            println("== ${sc.name}")
            CardColorDetector.debug = { n, x ->
                when (x) {
                    is Mat -> Imgcodecs.imwrite(File(dir, "${sc.name}_$n.png").absolutePath, x)
                    is DoubleArray -> println("  $n: ${x.joinToString { "%.0f".format(it) }}")
                    else -> println("  $n: $x")
                }
            }
            val r = CardColorDetector.detect(sc.rgb, sc.guide)
            CardColorDetector.debug = null
            val vis = Mat(); Imgproc.cvtColor(sc.rgb, vis, Imgproc.COLOR_RGB2BGR)
            fun draw(q: DoubleArray, c: Scalar) = Imgproc.polylines(vis, listOf(MatOfPoint(*Array(4) { Point(q[it * 2], q[it * 2 + 1]) })), true, c, 2)
            draw(sc.gt, Scalar(0.0, 255.0, 0.0))
            r?.let { draw(it.quad, Scalar(0.0, 0.0, 255.0)) }
            Imgcodecs.imwrite(File(dir, "${sc.name}_vis.jpg").absolutePath, vis)
            println("  iou=${r?.let { SyntheticCards.iou(it.quad, sc.gt, sc.rgb.cols(), sc.rgb.rows()) }} conf=${r?.confidence} rec=${r?.recoveredSide}")
        }
    }
}
