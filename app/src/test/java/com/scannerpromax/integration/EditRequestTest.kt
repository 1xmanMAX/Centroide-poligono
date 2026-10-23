package com.scannerpromax.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acción EDIT: análisis de la petición, id de documento y conservación en la biblioteca. */
class EditRequestTest {
    private val pkg = "com.scannerpromax"

    private fun edit(vararg extras: Pair<String, Any?>) =
        ScanRequestParser.parse(IntentFacts(ScanContract.ACTION_EDIT, extras = mapOf(*extras)), AnyFormatDefault.PDF, pkg)

    @Test fun edit_withId_defaultsToPdfAllPages() {
        val r = (edit(ScanContract.EXTRA_DOCUMENT_ID to "0123456789abcdef") as ParseResult.Ok).request
        assertEquals(RequestSource.EDIT, r.source)
        assertEquals("0123456789abcdef", r.documentId)
        assertEquals(OutputFormat.PDF, r.format)
        assertTrue(r.multiPage)
        assertFalse(r.filePerPage)
        val plan = DeliveryPlanner.plan(r, 4)
        assertEquals(listOf(0, 1, 2, 3), plan.pages)
        assertEquals(1, plan.fileCount)
    }

    @Test fun edit_sameFormatExtrasAsScan() {
        val r = (
            edit(
                ScanContract.EXTRA_DOCUMENT_ID to " abc_DEF-9 ",
                ScanContract.EXTRA_FORMAT to "jpg",
                ScanContract.EXTRA_OCR to "true",
                ScanContract.EXTRA_TITLE to "Factura/2",
                ScanContract.EXTRA_CALLER_LABEL to "PixPin",
                ScanContract.EXTRA_FILTER to "bw",
            ) as ParseResult.Ok
            ).request
        assertEquals("abc_DEF-9", r.documentId)
        assertEquals(OutputFormat.JPEG, r.format)
        assertTrue(r.filePerPage)
        assertTrue(r.ocr)
        assertEquals("Factura 2", r.title)
        assertEquals("PixPin", r.callerLabel)
        // El filtro pedido no se aplica al editar (pisaría los retoques del usuario).
        assertNull(r.filter)
        assertEquals(3, DeliveryPlanner.plan(r, 3).fileCount)
    }

    @Test fun edit_withoutOrBadId_rejected() {
        for (bad in listOf(null, "", "   ", "../otro", "a/b", "x".repeat(65), "id con espacios", 123)) {
            val r = edit(ScanContract.EXTRA_DOCUMENT_ID to bad)
            assertTrue("$bad → $r", r is ParseResult.Rejected)
            assertEquals(ScanContract.ERROR_INVALID_DOCUMENT_ID, (r as ParseResult.Rejected).reason)
        }
    }

    @Test fun sanitizeDocumentId() {
        assertEquals("0123456789abcdef", ScanRequestParser.sanitizeDocumentId("0123456789abcdef"))
        assertNull(ScanRequestParser.sanitizeDocumentId(".."))
        assertNull(ScanRequestParser.sanitizeDocumentId(null))
    }

    @Test fun alias_documentId() {
        assertEquals(ScanContract.EXTRA_DOCUMENT_ID, ScanContract.SHORT_ALIASES["documentId"])
    }

    @Test fun keepInLibrary_scanAndEditAlways() {
        val scan = (ScanRequestParser.parse(IntentFacts(ScanContract.ACTION_SCAN), AnyFormatDefault.PDF, pkg) as ParseResult.Ok).request
        val editReq = (edit(ScanContract.EXTRA_DOCUMENT_ID to "abc") as ParseResult.Ok).request
        val get = (ScanRequestParser.parse(IntentFacts(ScanRequestParser.ACTION_GET_CONTENT, "application/pdf"), AnyFormatDefault.PDF, pkg) as ParseResult.Ok).request
        assertTrue(DeliveryPlanner.keepInLibrary(scan, saveCopySetting = false, hasCaller = true))
        assertTrue(DeliveryPlanner.keepInLibrary(editReq, saveCopySetting = false, hasCaller = true))
        assertFalse(DeliveryPlanner.keepInLibrary(get, saveCopySetting = false, hasCaller = true))
        assertTrue(DeliveryPlanner.keepInLibrary(get, saveCopySetting = true, hasCaller = true))
        assertTrue(DeliveryPlanner.keepInLibrary(get, saveCopySetting = false, hasCaller = false))
    }
}
