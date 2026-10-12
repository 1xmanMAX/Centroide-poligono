package com.scannerpromax.export

import com.scannerpromax.domain.PageSize
import com.scannerpromax.pdf.PageFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PageFitTest {

    @Test fun nuncaRecortaNiDeformaEnNingunTamano() {
        val rnd = Random(7)
        repeat(400) {
            val w = rnd.nextInt(200, 9000).toFloat()
            val h = rnd.nextInt(200, 9000).toFloat()
            for (size in PageSize.entries) for (m in listOf(0f, 18f, 36f)) {
                val r = PageFit.layout(w, h, size, m)
                assertTrue("$size ${w}x$h m=$m -> $r", PageFit.fitsWithoutCrop(r, w, h))
                // Respeta el margen
                assertTrue(r.x >= m - 0.01f && r.y >= m - 0.01f)
            }
        }
    }

    @Test fun ajustarAImagenLlenaLaPaginaConLaMismaProporcion() {
        // Hoja de cuaderno de proporción no estándar: la página toma su proporción exacta (sin bandas).
        val r = PageFit.layout(3000f, 4100f, PageSize.AUTO)
        assertEquals(0f, r.x, 0.01f); assertEquals(0f, r.y, 0.01f)
        assertEquals(r.pageW, r.w, 0.01f); assertEquals(r.pageH, r.h, 0.01f)
        assertEquals(3000f / 4100f, r.pageW / r.pageH, 1e-4f)
        assertEquals(PageSize.A4.heightPt, r.pageH, 0.01f)
    }

    @Test fun proporcionA4ExactaUsaA4() {
        val r = PageFit.layout(2480f, 3508f, PageSize.AUTO)
        assertEquals(PageSize.A4.widthPt, r.pageW, 0.01f)
        assertEquals(PageSize.A4.heightPt, r.pageH, 0.01f)
    }

    @Test fun apaisadaEnA4GiraLaPaginaYEncaja() {
        val r = PageFit.layout(4000f, 3000f, PageSize.A4)
        assertEquals(PageSize.A4.heightPt, r.pageW, 0.01f) // página apaisada
        assertTrue(PageFit.fitsWithoutCrop(r, 4000f, 3000f))
    }
}
