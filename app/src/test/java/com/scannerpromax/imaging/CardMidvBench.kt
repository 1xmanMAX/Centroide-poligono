package com.scannerpromax.imaging

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max

/**
 * Banco con un conjunto PÚBLICO de documentos de identidad con datos ficticios (MIDV-500, máscaras de verdad), fuera del
 * repo: CARD_BENCH_DIRS="dir1:dir2" con img_NNN.* + mask_NNN.png; CARD_BENCH_OUT = informe markdown. Sin la variable no
 * hace nada (no forma parte de la batería normal).
 */
class CardMidvBench {

    private val tier = DeviceTier(false, 20_000_000, 8)

    @Test fun banco() {
        val dirs = System.getenv("CARD_BENCH_DIRS") ?: return
        assumeTrue(SyntheticScenes.openCv)
        val det = DocumentDetector(tier)
        class R(val name: String, val iouOld: Double, val iouNew: Double, val iouColor: Double, val eOld: Double?, val eNew: Double?, val msOld: Long, val msNew: Long, val src: String)
        val rows = ArrayList<R>()
        for (d in dirs.split(':')) {
            val imgs = File(d).listFiles { f -> f.name.startsWith("img_") }?.sortedBy { it.name } ?: continue
            for (f in imgs) {
                val id = f.nameWithoutExtension.removePrefix("img_")
                val mf = File(d, "mask_$id.png").takeIf { it.exists() } ?: continue
                val bgr = Imgcodecs.imread(f.absolutePath)
                val mask = Imgcodecs.imread(mf.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                if (bgr.empty() || mask.empty()) continue
                Imgproc.threshold(mask, mask, 127.0, 255.0, Imgproc.THRESH_BINARY)
                val rgb = Mat(); Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB); bgr.release()
                val w = rgb.cols(); val h = rgb.rows()
                val gtQuad = maskQuad(mask)
                var t0 = System.nanoTime()
                val oldR = SyntheticScenes.detect(det, rgb)?.takeIf { it.confidence >= 0.4f }?.let { SyntheticScenes.quadArray(it) }
                val msOld = (System.nanoTime() - t0) / 1_000_000
                t0 = System.nanoTime()
                val col = CardColorDetector.detect(rgb, null)
                val newR = CardColorDetector.choose(rgb, col, oldR)
                val useColor = col != null && newR === col.quad
                val msNew = (System.nanoTime() - t0) / 1_000_000 + msOld
                val full = doubleArrayOf(0.0, 0.0, w.toDouble(), 0.0, w.toDouble(), h.toDouble(), 0.0, h.toDouble())
                fun iou(q: DoubleArray?): Double {
                    val qm = SyntheticScenes.polyMask(w, h, q ?: full)
                    val i = Mat(); Core.bitwise_and(qm, mask, i); val u = Mat(); Core.bitwise_or(qm, mask, u)
                    val r = Core.countNonZero(i).toDouble() / max(1, Core.countNonZero(u))
                    qm.release(); i.release(); u.release()
                    return r
                }
                rows += R(
                    "${File(d).name}/$id", iou(oldR), iou(newR), col?.let { iou(it.quad) } ?: 0.0,
                    gtQuad?.let { g -> oldR?.let { SyntheticCards.cornerError(it, g) } }, gtQuad?.let { g -> newR?.let { SyntheticCards.cornerError(it, g) } },
                    msOld, msNew, if (useColor) "color" else if (oldR != null) "bordes" else "nada",
                )
                rgb.release(); mask.release()
            }
        }
        if (rows.isEmpty()) return
        fun line(label: String, iou: (R) -> Double, err: (R) -> Double?, ms: (R) -> Long): String {
            val ious = rows.map(iou); val errs = rows.mapNotNull(err).sorted()
            val med = if (errs.isEmpty()) Double.NaN else errs[errs.size / 2]
            return "| $label | %.4f | %.1f %% | %.1f %% | %.1f | %.1f | %d |".format(
                ious.average(), 100.0 * ious.count { it > 0.95 } / rows.size, 100.0 * ious.count { it > 0.90 } / rows.size,
                med, if (errs.isEmpty()) Double.NaN else errs.average(), rows.map(ms).sorted()[rows.size / 2],
            )
        }
        val sb = StringBuilder()
        sb.appendLine("Imágenes: ${rows.size} (sin marco guía, como al importar de la galería). Color usado en ${rows.count { it.src == "color" }}.")
        sb.appendLine()
        sb.appendLine("| método | IoU medio | éxito IoU>0,95 | IoU>0,90 | err. esquinas mediana (px) | err. medio (px) | ms/imagen (mediana, JVM) |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        sb.appendLine(line("antes (detector genérico)", { it.iouOld }, { it.eOld }, { it.msOld }))
        sb.appendLine(line("después (color + respaldo)", { it.iouNew }, { it.eNew }, { it.msNew }))
        sb.appendLine()
        sb.appendLine("| imagen | IoU antes | IoU después | IoU color | método |")
        sb.appendLine("|---|---|---|---|---|")
        for (r in rows) sb.appendLine("| ${r.name} | %.3f | %.3f | %.3f | ${r.src} |".format(r.iouOld, r.iouNew, r.iouColor))
        println(sb)
        System.getenv("CARD_BENCH_OUT")?.let { File(it).writeText(sb.toString()) }
    }

    /** 4 esquinas de la máscara de verdad (null si no es un cuadrilátero claro, p. ej. tarjeta cortada por el borde). */
    private fun maskQuad(mask: Mat): DoubleArray? {
        val cs = ArrayList<MatOfPoint>(); val hi = Mat()
        Imgproc.findContours(mask.clone(), cs, hi, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val c = cs.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val c2 = MatOfPoint2f(*c.toArray())
        val peri = Imgproc.arcLength(c2, true)
        val ap = MatOfPoint2f(); Imgproc.approxPolyDP(c2, ap, 0.02 * peri, true)
        val p = ap.toArray()
        if (p.size != 4) return null
        return CardColorDetector.orderCorners(DoubleArray(8) { i -> if (i % 2 == 0) p[i / 2].x else p[i / 2].y })
    }
}
