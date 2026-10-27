package com.scannerpromax.data

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PageFilesTest {

    private val edits = PageEdits(quad = Quad(Pt(10f, 20f), Pt(900f, 15f), Pt(910f, 1300f), Pt(5f, 1290f)))

    @Test fun hashIgualAlDeVersionesAnteriores() {
        // Misma serialización que DocumentRepository.json: los procesados ya guardados conservan su nombre.
        val old = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val expected = Integer.toHexString(old.encodeToString(PageEdits.serializer(), edits).hashCode())
        assertEquals(expected, PageFiles.editsHash(edits))
        assertEquals("processed/p1_$expected.jpg", PageFiles.processedName("p1", edits))
        assertEquals("thumb/p1_$expected.jpg", PageFiles.thumbName("p1", edits))
    }

    @Test fun bnSeGuardaEnPng() {
        assertTrue(PageFiles.processedName("p", PageEdits(filter = FilterType.BLACK_WHITE)).endsWith(".png"))
        assertTrue(PageFiles.processedName("p", PageEdits(filter = FilterType.NATURAL)).endsWith(".jpg"))
    }

    @Test fun cadaEdicionTieneSuPropioProcesadoYMiniatura() {
        val rotated = edits.copy(rotation = 90)
        assertNotEquals(PageFiles.processedName("p", edits), PageFiles.processedName("p", rotated))
        assertNotEquals(PageFiles.thumbName("p", edits), PageFiles.thumbName("p", rotated))
    }

    @Test fun miniaturaDeOtraVersionNoCoincide() {
        val thumb = PageFiles.thumbName("p", edits)
        assertTrue(PageFiles.thumbMatches(thumb, "p", edits))
        assertFalse(PageFiles.thumbMatches(thumb, "p", edits.copy(filter = FilterType.BLACK_WHITE)))
        assertFalse(PageFiles.thumbMatches(thumb, "otra", edits))
        assertFalse(PageFiles.thumbMatches(null, "p", edits))
    }

    @Test fun claveDeCacheCambiaAlRegenerarElArchivo() {
        val a = PageFiles.cacheKey("/d/thumb/p_1.jpg", 1000L, 5000L)
        assertEquals(a, PageFiles.cacheKey("/d/thumb/p_1.jpg", 1000L, 5000L))
        assertNotEquals(a, PageFiles.cacheKey("/d/thumb/p_1.jpg", 2000L, 5000L)) // mismo nombre, regenerado
        assertNotEquals(a, PageFiles.cacheKey("/d/thumb/p_1.jpg", 1000L, 5100L))
        assertNotEquals(a, PageFiles.cacheKey("/d/thumb/p_2.jpg", 1000L, 5000L))
    }

    @Test fun versionedFileLeeFechaYTamano() {
        val f = java.io.File.createTempFile("thumb", ".jpg")
        try {
            f.writeBytes(ByteArray(10) { 1 })
            val v = VersionedFile.of(f)!!
            assertEquals(10L, v.length)
            f.writeBytes(ByteArray(20) { 2 })
            assertNotEquals(v.cacheKey, VersionedFile.of(f)!!.cacheKey)
            f.writeBytes(ByteArray(0))
            assertEquals(null, VersionedFile.of(f)) // vacío = aún escribiéndose
        } finally {
            f.delete()
        }
        assertEquals(null, VersionedFile.of(null))
    }

    @Test fun textoResaltadoSeMigraConservandoEdicionesOcrYMaquetacion() {
        val dir = java.nio.file.Files.createTempDirectory("mig").toFile()
        try {
            val old = edits.copy(filter = FilterType.MAGIC, rotation = 90, keepRuling = false)
            File(dir, "ocr").mkdirs(); File(dir, "processed").mkdirs()
            File(dir, PageFiles.ocrName("p", old)).writeText("{}")
            File(dir, PageFiles.layoutName("p", old)).writeText("{}")
            val page = Page(id = "p", originalFile = "original/p.jpg", width = 10, height = 10, edits = old,
                ocrFile = PageFiles.ocrName("p", old), ocrEditedText = "corregido")
            val m = PageFiles.migrateFilter(dir, page)
            assertEquals(old.copy(filter = FilterType.NATURAL), m.edits)   // sólo cambia el filtro
            assertEquals("corregido", m.ocrEditedText)
            assertEquals(PageFiles.ocrName("p", m.edits), m.ocrFile)
            assertTrue(File(dir, m.ocrFile!!).exists())
            assertTrue(File(dir, PageFiles.layoutName("p", m.edits)).exists())
            assertNotEquals(PageFiles.processedName("p", old), PageFiles.processedName("p", m.edits))   // se reprocesa
            // Otros filtros: la página no cambia (ni su hash)
            val bw = page.copy(edits = old.copy(filter = FilterType.BLACK_WHITE))
            assertTrue(bw === PageFiles.migrateFilter(dir, bw))
        } finally { dir.deleteRecursively() }
    }

    @Test fun recorteManualCambiaElHashYElAutomaticoNo() {
        val q = Quad.of(listOf(Pt(10f, 10f), Pt(90f, 10f), Pt(90f, 120f), Pt(10f, 120f)))
        val auto = PageEdits(quad = q)
        assertEquals(PageFiles.editsHash(auto), PageFiles.editsHash(auto.copy(manualQuad = null)))
        assertNotEquals(PageFiles.editsHash(auto), PageFiles.editsHash(auto.copy(manualQuad = true)))
    }
}
