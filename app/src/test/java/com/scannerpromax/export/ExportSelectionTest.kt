package com.scannerpromax.export

import com.scannerpromax.domain.Document
import com.scannerpromax.domain.Page
import com.scannerpromax.ui.navigation.Routes
import com.scannerpromax.ui.review.PageSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSelectionTest {

    private val pages = List(10) { Page(id = "p$it", originalFile = "p$it.jpg", width = 100, height = 140) }
    private val doc = Document(id = "d1", title = "Doc", createdAt = 0L, updatedAt = 0L, pages = pages)

    @Test fun sinSeleccionSeExportanTodas() {
        assertEquals(pages, ExportSelection.pages(pages, null))
        assertEquals(doc, ExportSelection.apply(doc, null))
    }

    @Test fun subconjuntoEnElOrdenDelDocumento() {
        // Marcadas en otro orden (7, 2, 4): se exportan en el orden del documento.
        val out = ExportSelection.apply(doc, listOf("p7", "p2", "p4"))
        assertEquals(listOf("p2", "p4", "p7"), out.pages.map { it.id })
        assertEquals("Doc", out.title)
    }

    @Test fun idsInexistentesSeIgnoran() {
        assertEquals(listOf("p3"), ExportSelection.pages(pages, listOf("x", "p3", "borrada")).map { it.id })
        assertTrue(ExportSelection.pages(pages, listOf("x")).isEmpty())
    }

    @Test fun argumentoDeRutaIdaYVuelta() {
        val ids = listOf("a1b2-c3", "d4 e5", "f,6", "ü7")
        val arg = ExportSelection.encode(ids)!!
        assertFalse(arg.contains(' '))
        assertEquals(3, arg.count { it == ',' }) // la coma del id va codificada
        assertEquals(ids, ExportSelection.decode(arg))
    }

    @Test fun argumentoVacioEsTodas() {
        assertNull(ExportSelection.encode(null))
        assertNull(ExportSelection.encode(emptyList()))
        assertNull(ExportSelection.decode(null))
        assertNull(ExportSelection.decode(""))
        assertNull(ExportSelection.decode(",,"))
        assertEquals(listOf("p1"), ExportSelection.decode("p1,p1"))
    }

    @Test fun rutaExportarConYSinPaginas() {
        assertEquals("export/d1", Routes.export("d1"))
        assertEquals("export/d1", Routes.export("d1", emptyList()))
        assertEquals("export/d1?pages=p2%2Cp5", Routes.export("d1", listOf("p2", "p5")))
        // Navigation decodifica una vez el argumento: lo que llega a ExportScreen se decodifica bien.
        val delivered = java.net.URLDecoder.decode(Routes.export("d1", listOf("a,b", "c")).substringAfter("pages="), "UTF-8")
        assertEquals(listOf("a,b", "c"), ExportSelection.decode(delivered))
        assertEquals("export/{docId}?pages={pages}", Routes.EXPORT)
    }

    @Test fun etiquetaDeLaHoja() {
        assertEquals("3 de 10 páginas", ExportSelection.label(3, 10))
        assertEquals("1 de 10 páginas", ExportSelection.label(1, 10))
        assertEquals("10 páginas", ExportSelection.label(10, 10))
        assertEquals("1 página", ExportSelection.label(1, 1))
    }

    // ------------------------------------------------------------------ modo selección del Documento

    @Test fun marcarYDesmarcar() {
        var sel = emptyList<String>()
        sel = PageSelection.toggle(sel, "p3")
        sel = PageSelection.toggle(sel, "p1")
        assertEquals(listOf("p3", "p1"), sel)
        sel = PageSelection.toggle(sel, "p3")
        assertEquals(listOf("p1"), sel)
    }

    @Test fun seleccionarTodoYNinguna() {
        val all = pages.map { it.id }
        assertEquals(all, PageSelection.toggleAll(listOf("p1"), all))
        assertTrue(PageSelection.allSelected(all.reversed(), all))
        assertEquals(emptyList<String>(), PageSelection.toggleAll(all, all))
        assertFalse(PageSelection.allSelected(emptyList(), emptyList()))
    }

    @Test fun seleccionEnOrdenYPodaDeBorradas() {
        assertEquals(listOf("p0", "p9"), PageSelection.inOrder(pages, listOf("p9", "p0")).map { it.id })
        assertEquals(listOf("p2"), PageSelection.prune(listOf("p2", "p11"), pages.map { it.id }))
        val same = listOf("p1", "p2")
        assertTrue(same === PageSelection.prune(same, pages.map { it.id }))
    }

    @Test fun tituloDeLaCabecera() {
        assertEquals("Selecciona páginas", PageSelection.title(0))
        assertEquals("1 seleccionada", PageSelection.title(1))
        assertEquals("4 seleccionadas", PageSelection.title(4))
    }
}
