package com.scannerpromax.ai

import com.scannerpromax.data.PageFiles
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.PageEdits
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLayoutTest {

    @Test fun alignTextWithoutPositionsToIndraLines() {
        val base = OcrResult(
            "Totl 12,0O\nGracias", 100, 100,
            listOf(OcrBlock("Totl 12,0O\nGracias", OcrRect(0f, 0f, 90f, 40f), listOf(
                OcrLine("Totl 12,0O", OcrRect(0f, 0f, 90f, 15f)),
                OcrLine("Gracias", OcrRect(0f, 20f, 60f, 35f)),
            ))),
        )
        val r = AiLayout.alignToLines(base, "Total 12,00\nGracias")!!
        assertEquals(listOf("Total 12,00", "Gracias"), r.blocks.flatMap { it.lines }.map { it.text })
        assertEquals(OcrRect(0f, 0f, 90f, 15f), r.blocks[0].lines[0].box)
        assertNull(AiLayout.alignToLines(null, "x"))
    }

    @Test fun wordsAlongBox() {
        val ws = AiLayout.wordsAlong("ab cd", null, OcrRect(0f, 0f, 50f, 10f))
        assertEquals(listOf("ab", "cd"), ws.map { it.text })
        assertTrue(ws[0].box.right <= ws[1].box.left + 0.01f)
        assertEquals(OcrRect(0f, 0f, 50f, 10f), AiLayout.envelope(listOf(OcrRect(0f, 0f, 10f, 5f), OcrRect(20f, 3f, 50f, 10f))))
    }

    @Test fun garbage() {
        assertTrue(AiLayout.isGarbage(0, 200, false))
        assertTrue(AiLayout.isGarbage(30, 300, true))
        assertFalse(AiLayout.isGarbage(250, 300, true))
        assertFalse(AiLayout.isGarbage(2, 5, false))
    }

    @Test fun aiHintDoesNotChangeHashOfSavedEdits() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        assertFalse(json.encodeToString(PageEdits.serializer(), PageEdits()).contains("aiHint"))
        val e = PageEdits()
        assertTrue(PageFiles.editsHash(e) != PageFiles.editsHash(e.copy(aiHint = "receipt")))
        assertEquals(PageFiles.processedName("p", e) + ".layout.json", PageFiles.layoutName("p", e))
        assertNotNull(PageFiles.layoutName("p", e))
        assertEquals("manuscrito", AiEnhance.docTypeCode("handwritten"))
    }
}
