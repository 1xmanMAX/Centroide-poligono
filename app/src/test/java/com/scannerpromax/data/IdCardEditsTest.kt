package com.scannerpromax.data

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.IdCardEdits
import com.scannerpromax.domain.IdCardSide
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.ui.camera.CaptureProcessor
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DNI editable: recortes de cada cara guardados en las ediciones sin cambiar el hash de las páginas antiguas. */
class IdCardEditsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val q = Quad(Pt(0.1f, 0.2f), Pt(0.9f, 0.18f), Pt(0.92f, 0.8f), Pt(0.08f, 0.82f))
    private val card = IdCardEdits(IdCardSide(q, manual = true), IdCardSide(q), FilterType.VIVID)

    @Test fun sinDniElHashNoCambia() {
        val e = PageEdits(filter = FilterType.ORIGINAL, autoDeskew = false)
        // JSON de una versión anterior (sin el campo idCard): mismo texto, mismo hash
        assertFalse(json.encodeToString(PageEdits.serializer(), e).contains("idCard"))
        val back = json.decodeFromString(PageEdits.serializer(), json.encodeToString(PageEdits.serializer(), e))
        assertEquals(PageFiles.editsHash(e), PageFiles.editsHash(back))
    }

    @Test fun recorteDelDniVaEnLaGeometria() {
        val e = PageEdits(filter = FilterType.ORIGINAL, idCard = card)
        val moved = e.copy(idCard = card.copy(front = IdCardSide(q.copy(tl = Pt(0.12f, 0.2f)), manual = true)))
        assertNotEquals(PageFiles.processedName("p", e), PageFiles.processedName("p", moved))
        assertFalse(PageFiles.sameGeometry(e, moved))
        assertNotEquals(PageFiles.jevCacheName("p", e), PageFiles.jevCacheName("p", moved))
        // ida y vuelta
        assertEquals(e, json.decodeFromString(PageEdits.serializer(), json.encodeToString(PageEdits.serializer(), e)))
    }

    @Test fun fotosDeOrigenEmpiezanPorElIdDeLaPagina() {
        // así se borran con la página (deletePageFiles) y se copian al fusionar documentos
        assertTrue(PageFiles.idSourceName("abc", back = false).startsWith("original/abc_"))
        assertTrue(PageFiles.idSourceName("abc", back = true).startsWith("original/abc_"))
        assertNotEquals(PageFiles.idSourceName("abc", false), PageFiles.idSourceName("abc", true))
    }

    @Test fun normalizarYVolver() {
        val px = Quad(Pt(100f, 50f), Pt(900f, 40f), Pt(950f, 600f), Pt(80f, 620f))
        val n = CaptureProcessor.normalize(px, 1000, 750)
        val back = CaptureProcessor.denormalize(n, 1000, 750)
        for ((a, b) in px.points().zip(back.points())) {
            assertEquals(a.x, b.x, 1e-3f); assertEquals(a.y, b.y, 1e-3f)
        }
    }
}
