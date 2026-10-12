package com.scannerpromax.export

import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class ExportPlanTest {

    @Test fun maximaIncrustaElJpegProcesadoTalCual() {
        val plan = ExportPlan.pdfImage(4000, 5600, ExportQuality.MAX, binary = false, isJpeg = true)
        assertEquals(ExportPlan.PdfImage.PassthroughJpeg, plan)
    }

    @Test fun maximaConPngDeBnEsSinPerdida() {
        // B/N: en "Máxima" los mismos píxeles del procesado (cuadrícula clara y bordes suavizados incluidos), no 1 bit.
        assertEquals(ExportPlan.PdfImage.Lossless, ExportPlan.pdfImage(3000, 4200, ExportQuality.MAX, binary = true, isJpeg = false))
    }

    @Test fun altaSinReducirEsPassthrough() {
        assertEquals(ExportPlan.PdfImage.PassthroughJpeg, ExportPlan.pdfImage(2480, 3508, ExportQuality.HIGH, binary = false, isJpeg = true))
    }

    @Test fun reduccionConservaLaProporcion() {
        val sizes = listOf(4000 to 5600, 3024 to 4032, 4032 to 3024, 1999 to 7001, 3517 to 4961, 5000 to 5000)
        for ((w, h) in sizes) for (q in listOf(ExportQuality.SMALL, ExportQuality.BALANCED, ExportQuality.HIGH)) {
            val plan = ExportPlan.pdfImage(w, h, q, binary = false, isJpeg = true)
            val (tw, th) = when (plan) {
                is ExportPlan.PdfImage.Jpeg -> plan.width to plan.height
                ExportPlan.PdfImage.PassthroughJpeg -> w to h
                else -> error("plan inesperado $plan")
            }
            assertTrue("lado largo ${max(tw, th)} > ${q.maxLongSide}", max(tw, th) <= q.maxLongSide)
            assertAspect(w, h, tw, th)
        }
    }

    @Test fun bnSeReduceSoloHastaA4a300ppp() {
        val plan = ExportPlan.pdfImage(4000, 5656, ExportQuality.SMALL, binary = true, isJpeg = false)
        plan as ExportPlan.PdfImage.Bilevel
        assertEquals(ExportPlan.BINARY_MIN_LONG_SIDE, max(plan.width, plan.height))
        assertAspect(4000, 5656, plan.width, plan.height)
        // Nunca amplía
        val small = ExportPlan.pdfImage(1000, 1400, ExportQuality.SMALL, binary = true, isJpeg = false) as ExportPlan.PdfImage.Bilevel
        assertEquals(1000, small.width); assertEquals(1400, small.height)
    }

    @Test fun scaledSizeNuncaAmpliaYFijaElLadoLargo() {
        assertEquals(800 to 600, ExportPlan.scaledSize(800, 600, 1600))
        assertEquals(1600 to 1200, ExportPlan.scaledSize(3200, 2400, 1600))
        assertEquals(1143 to 1600, ExportPlan.scaledSize(2000, 2800, 1600))
        assertEquals(10 to 10, ExportPlan.scaledSize(10, 10, Int.MAX_VALUE))
    }

    @Test fun imagenesPassthroughSoloSiNoHayQueReducir() {
        assertTrue(ExportPlan.imagePassthrough(4000, 5600, ExportQuality.MAX, ImageFormat.JPEG, isJpeg = true, isPng = false))
        assertTrue(ExportPlan.imagePassthrough(4000, 5600, ExportQuality.MAX, ImageFormat.PNG, isJpeg = false, isPng = true))
        assertFalse(ExportPlan.imagePassthrough(4000, 5600, ExportQuality.HIGH, ImageFormat.JPEG, isJpeg = true, isPng = false))
        assertFalse(ExportPlan.imagePassthrough(2000, 2800, ExportQuality.BALANCED, ImageFormat.JPEG, isJpeg = true, isPng = false))
        assertFalse(ExportPlan.imagePassthrough(2000, 2800, ExportQuality.MAX, ImageFormat.WEBP, isJpeg = true, isPng = false))
        assertFalse(ExportPlan.imagePassthrough(2000, 2800, ExportQuality.MAX, ImageFormat.JPEG, isJpeg = false, isPng = true))
    }

    private fun assertAspect(w: Int, h: Int, tw: Int, th: Int) {
        // Error de proporción por debajo de 1 px en el lado corto.
        val expectedShort = if (w >= h) h.toDouble() * tw / w else w.toDouble() * th / h
        val short = if (w >= h) th else tw
        assertTrue("proporción ${w}x$h -> ${tw}x$th", abs(short - expectedShort) <= 0.5 + 1e-9)
    }
}
