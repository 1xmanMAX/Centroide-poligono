package com.scannerpromax.album

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AlbumStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sampleSticker(src: String, stk: String) = Sticker(
        source = src,
        quad = Quad(Pt(0.1f, 0.12f), Pt(0.9f, 0.1f), Pt(0.88f, 0.9f), Pt(0.12f, 0.92f)),
        rendered = stk, renderedW = 1011, renderedH = 638,
        filter = FilterType.NATURAL, fit = StickerFit.FIT, rotation = 270, manualQuad = true,
    )

    @Test
    fun roundTripKeepsEverythingEditable() = runBlocking {
        val root = tmp.newFolder("albums")
        val store = AlbumStore(root, startScope = null)
        val a = store.create("Mi álbum", AlbumStore.blankPage(PaperSize.LETTER, landscape = true))
        val (src, srcRel) = store.newFile(a.id, "src", "s1", "jpg")
        val (stk, stkRel) = store.newFile(a.id, "stk", "s1", "jpg")
        src.writeText("x"); stk.writeText("y")
        val pdfPage = AlbumPage(
            "p2", 215.9f, 279.4f,
            background = SheetBackground(BackgroundKind.PDF_PAGE, "bg/a.pdf", "bg/a_p.jpg", 612f, 792f, pdfPage = 3, label = "Formulario.pdf"),
            rotation = 90,
            slots = listOf(AlbumSlot("s1", 0.25f, 0.3f, 0.4f, 0.19f, angle = -7.5f, keepAspect = false, label = "DNI", sticker = sampleSticker(srcRel, stkRel))),
        )
        store.update(a.id) { it.copy(pages = it.pages + pdfPage) }

        // otro almacén sobre la misma carpeta = reinicio de la app
        val again = AlbumStore(root, startScope = null)
        val b = again.get(a.id)
        assertNotNull(b)
        assertEquals("Mi álbum", b!!.title)
        assertEquals(2, b.pages.size)
        assertEquals(279.4f, b.pages[0].widthMm) // carta horizontal
        assertEquals(pdfPage, b.pages[1])
        assertEquals(setOf("bg/a.pdf", "bg/a_p.jpg", srcRel, stkRel), AlbumStore.referencedFiles(b))
    }

    @Test
    fun compactDeletesOnlyUnreferencedOldFiles() = runBlocking {
        val root = tmp.newFolder("albums")
        val store = AlbumStore(root, startScope = null)
        val a = store.create("A", AlbumStore.blankPage(PaperSize.A4, false))
        val (keep, keepRel) = store.newFile(a.id, "stk", "k", "jpg")
        val (old, _) = store.newFile(a.id, "stk", "o", "jpg")
        val (fresh, _) = store.newFile(a.id, "src", "f", "jpg")
        listOf(keep, old, fresh).forEach { it.writeText("z") }
        keep.setLastModified(0); old.setLastModified(0)
        val slot = AlbumSlot("s", 0.5f, 0.5f, 0.2f, 0.1f, sticker = sampleSticker("src/none.jpg", keepRel))
        store.update(a.id) { it.copy(pages = listOf(it.pages[0].copy(slots = listOf(slot)))) }
        assertEquals(1, store.compact(a.id))
        assertTrue(keep.exists())
        assertFalse(old.exists())
        assertTrue(fresh.exists()) // recién creado: puede ser una captura en curso
    }

    @Test
    fun deleteAndDuplicate() = runBlocking {
        val root = tmp.newFolder("albums")
        val store = AlbumStore(root, startScope = null)
        val a = store.create("A", AlbumStore.blankPage(PaperSize.A5, false))
        File(store.dir(a.id), "bg/x.jpg").writeText("q")
        val c = store.duplicate(a.id)!!
        assertTrue(File(store.dir(c.id), "bg/x.jpg").exists())
        assertEquals(2, store.all.value.size)
        store.delete(a.id)
        assertFalse(store.dir(a.id).exists())
        assertEquals(listOf(c.id), store.all.value.map { it.id })
    }
}
