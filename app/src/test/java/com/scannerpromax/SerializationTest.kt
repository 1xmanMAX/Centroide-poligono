package com.scannerpromax

import com.scannerpromax.domain.Document
import com.scannerpromax.domain.EraseMode
import com.scannerpromax.domain.EraseStroke
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.domain.ScanMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test fun documentoIdaYVuelta() {
        val doc = Document(
            id = "d1", title = "Escaneo ñandú", createdAt = 1L, updatedAt = 2L, mode = ScanMode.BOOK,
            pages = listOf(
                Page(
                    id = "p1", originalFile = "original/p1.jpg", width = 3000, height = 4000,
                    edits = PageEdits(
                        quad = Quad.full(3000, 4000), rotation = 90, filter = FilterType.MAGIC_PRO,
                        eraseStrokes = listOf(EraseStroke(listOf(Pt(0.1f, 0.2f), Pt(0.3f, 0.4f)), 0.02f, EraseMode.WHITE)),
                    ),
                    ocrText = "Hola",
                ),
            ),
        )
        val back = json.decodeFromString<Document>(json.encodeToString(doc))
        assertEquals(doc, back)
    }

    @Test fun keepRulingCompatibleConJsonAntiguo() {
        // Ediciones guardadas antes de existir el campo: se conserva la cuadrícula (valor por defecto)
        val old = json.decodeFromString<PageEdits>("""{"rotation":90,"autoDewarp":false}""")
        assertEquals(true, old.keepRuling)
        val off = PageEdits(keepRuling = false)
        assertEquals(false, json.decodeFromString<PageEdits>(json.encodeToString(off)).keepRuling)
    }

    @Test fun camposDesconocidosSeIgnoran() {
        val s = """{"id":"x","title":"t","createdAt":0,"updatedAt":0,"futuro":true}"""
        val d = json.decodeFromString<Document>(s)
        assertEquals(ScanMode.DOCUMENT, d.mode)
        assertEquals(0, d.pages.size)
    }
}
