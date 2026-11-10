package com.scannerpromax.imaging

import com.scannerpromax.imaging.SyntheticCards.Background
import com.scannerpromax.imaging.SyntheticCards.Palette
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Detección de tarjetas por color ([CardColorDetector]) con DNI peruanos SINTÉTICOS (azul, DNIe, amarillo; anverso y
 * reverso con MRZ) sobre fondos variados, comparada con el detector de documentos genérico (el de antes).
 */
class CardColorDetectorTest {

    private val tier = DeviceTier(false, 20_000_000, 8)

    @Before fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    private fun detectNew(sc: SyntheticCards.Scene) = CardColorDetector.detect(sc.rgb, sc.guide)

    @Test fun reversoConMrzSobreMesaBlanca_cubreLaTarjetaEntera() {
        val sc = SyntheticCards.scene(Palette.AZUL, back = true, b = Background.MESA_BLANCA, seed = 7)
        val r = detectNew(sc)
        assertNotNull("sin detección", r)
        val iou = SyntheticCards.iou(r!!.quad, sc.gt, sc.rgb.cols(), sc.rgb.rows())
        assertTrue("IoU ${"%.3f".format(iou)} (lado recuperado ${r.recoveredSide})", iou > 0.95)
    }

    @Test fun dniAmarilloSobreMadera() {
        val sc = SyntheticCards.scene(Palette.AMARILLO, back = false, b = Background.MADERA, seed = 3)
        val r = detectNew(sc)
        assertNotNull(r)
        val e = SyntheticCards.cornerError(r!!.quad, sc.gt)
        assertTrue("error de esquinas ${"%.1f".format(e)} px", e < 6.0)
        assertTrue("confianza ${r.confidence}", r.confidence >= 0.5f)
    }

    @Test fun dnieSobreFondoAzulDeOtroTono() {
        val sc = SyntheticCards.scene(Palette.DNIE, back = false, b = Background.VIOLETA, seed = 11, shadow = true)
        val r = detectNew(sc)
        assertNotNull(r)
        val iou = SyntheticCards.iou(r!!.quad, sc.gt, sc.rgb.cols(), sc.rgb.rows())
        assertTrue("IoU ${"%.3f".format(iou)}", iou > 0.95)
    }

    @Test fun rectificadoConProporcionId1() {
        val sc = SyntheticCards.scene(Palette.AZUL, back = false, b = Background.MADERA, seed = 5)
        val r = detectNew(sc)!!
        val out = CardColorDetector.warpCard(sc.rgb, r.quad, 1600)
        assertTrue("tamaño ${out.cols()}x${out.rows()}", out.cols() == 1600 && kotlin.math.abs(out.rows() - 1009) <= 1)
        out.release()
    }

    /** Banco sintético completo: tabla antes (detector genérico) / después (color + respaldo). */
    @Test fun bancoSinteticoDni() {
        val det = DocumentDetector(tier)
        class Row(val name: String, val iouOld: Double, val errOld: Double, val iouNew: Double, val errNew: Double, val iouColor: Double, val ms: Long, val conf: Float)
        val rows = ArrayList<Row>()
        var seed = 100L
        for (p in Palette.values()) for (back in listOf(false, true)) for (b in Background.values()) for (variant in 0..1) {
            seed++
            val sc = SyntheticCards.scene(p, back, b, seed, shadow = variant == 1, glare = variant == 1)
            val w = sc.rgb.cols(); val h = sc.rgb.rows()
            val guideQ = SyntheticCards.guideQuad(sc.guide, w, h)
            // antes: detector genérico (confianza >= 0,4), si no el marco guía
            val old = SyntheticScenes.detect(det, sc.rgb)?.takeIf { it.confidence >= 0.4f }?.let { SyntheticScenes.quadArray(it) } ?: guideQ
            val t0 = System.nanoTime()
            val col = detectNew(sc)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val nw = col?.takeIf { it.confidence >= CARD_MIN_CONF }?.quad ?: old
            rows += Row(
                sc.name,
                SyntheticCards.iou(old, sc.gt, w, h), SyntheticCards.cornerError(old, sc.gt),
                SyntheticCards.iou(nw, sc.gt, w, h), SyntheticCards.cornerError(nw, sc.gt),
                col?.let { SyntheticCards.iou(it.quad, sc.gt, w, h) } ?: 0.0, ms, col?.confidence ?: 0f,
            )
            sc.rgb.release()
        }
        fun stats(f: (Row) -> Double, e: (Row) -> Double): String {
            val ious = rows.map(f); val errs = rows.map(e).sorted()
            return "%.4f | %.1f %% | %.1f %% | %.1f | %.1f".format(
                ious.average(), 100.0 * ious.count { it > 0.95 } / rows.size, 100.0 * ious.count { it > 0.98 } / rows.size,
                errs[errs.size / 2], errs.average(),
            )
        }
        val sb = StringBuilder()
        sb.appendLine("| método | IoU medio | éxito IoU>0,95 | IoU>0,98 | error esquinas mediana (px) | error medio (px) |")
        sb.appendLine("|---|---|---|---|---|---|")
        sb.appendLine("| antes (detector genérico + marco) | ${stats({ it.iouOld }, { it.errOld })} |")
        sb.appendLine("| después (color + respaldo) | ${stats({ it.iouNew }, { it.errNew })} |")
        sb.appendLine()
        sb.appendLine("Escenas: ${rows.size} (1600x1200). Tiempo color: mediana ${rows.map { it.ms }.sorted()[rows.size / 2]} ms, máx ${rows.maxOf { it.ms }} ms (JVM).")
        sb.appendLine()
        sb.appendLine("| escena | IoU antes | IoU después | IoU color | conf | err antes | err después |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        for (r in rows) sb.appendLine("| ${r.name} | %.3f | %.3f | %.3f | %.2f | %.1f | %.1f |".format(r.iouOld, r.iouNew, r.iouColor, r.conf, r.errOld, r.errNew))
        println(sb)
        runCatching { File("/tmp/claude-0/bench").takeIf { it.isDirectory }?.let { File(it, "dni_synth_results.md").writeText(sb.toString()) } }
        val meanNew = rows.map { it.iouNew }.average(); val meanOld = rows.map { it.iouOld }.average()
        val okNew = rows.count { it.iouNew > 0.95 }.toDouble() / rows.size
        assertTrue("IoU medio después ${"%.4f".format(meanNew)} < antes ${"%.4f".format(meanOld)}", meanNew >= meanOld)
        assertTrue("éxito IoU>0,95 sólo ${"%.1f".format(okNew * 100)} %", okNew >= 0.85)
    }

    companion object {
        /** El mismo umbral que usa la app (CaptureProcessor). */
        const val CARD_MIN_CONF = 0.5f
    }
}
