package com.scannerpromax

import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.PerspectiveCorrector
import com.scannerpromax.ui.components.formatBytes
import com.scannerpromax.ui.components.pagesLabel
import com.scannerpromax.ui.components.safeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryAndFormatTest {
    @Test fun rectanguloFrontalConservaTamano() {
        val (w, h) = PerspectiveCorrector.estimateSize(Quad.full(2100, 2970), 2100, 2970)
        assertEquals(2100.0, w, 1.0)
        assertEquals(2970.0, h, 1.0)
    }

    @Test fun trapecioDaTamanoPositivoYRazonable() {
        val q = Quad(Pt(400f, 300f), Pt(2600f, 300f), Pt(2900f, 3700f), Pt(100f, 3700f))
        val (w, h) = PerspectiveCorrector.estimateSize(q, 3000, 4000)
        assertTrue(w > 0 && h > 0)
        val ratio = w / h
        assertTrue("ratio=$ratio", ratio in 0.5..1.2)
    }

    @Test fun formatosEnEspanol() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("1,50 MB", formatBytes(1_572_864))
        assertEquals("1 página", pagesLabel(1))
        assertEquals("3 páginas", pagesLabel(3))
    }

    @Test fun nombreDeArchivoSeguro() {
        assertEquals("a_b_c", safeFileName("a/b:c"))
        assertEquals("Escaneo", safeFileName("   "))
    }
}
