package com.scannerpromax.integration

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué formato y cuántos archivos se devuelven para cada combinación de acción / MIME / extras. */
class ScanRequestParserTest {
    private val pkg = "com.scannerpromax"

    private fun ok(facts: IntentFacts, any: AnyFormatDefault = AnyFormatDefault.PDF): ScanRequest {
        val r = ScanRequestParser.parse(facts, any, pkg)
        assertTrue("esperaba Ok y fue $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).request
    }

    private fun rejected(facts: IntentFacts): String {
        val r = ScanRequestParser.parse(facts, AnyFormatDefault.PDF, pkg)
        assertTrue("esperaba Rejected y fue $r", r is ParseResult.Rejected)
        return (r as ParseResult.Rejected).reason
    }

    private fun getContent(type: String?, multiple: Boolean = false, extraMimes: List<String> = emptyList()) =
        IntentFacts(ScanRequestParser.ACTION_GET_CONTENT, type, extraMimes, multiple)

    // ------------------------------------------------------------------ IMAGE_CAPTURE

    @Test fun imageCapture_withContentOutput_writesJpeg() {
        val r = ok(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "content://com.whatsapp.provider.media/item/1"))
        assertEquals(RequestSource.IMAGE_CAPTURE, r.source)
        assertEquals(OutputFormat.JPEG, r.format)
        assertFalse(r.multiPage)
        assertFalse(r.filePerPage)
        assertFalse(r.wantsThumbnail)
        assertEquals("content://com.whatsapp.provider.media/item/1", r.outputUri)
    }

    @Test fun imageCapture_withoutOutput_returnsThumbnail() {
        val r = ok(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE))
        assertTrue(r.wantsThumbnail)
        assertNull(r.outputUri)
    }

    @Test fun imageCapture_blankOutput_isTreatedAsMissing() {
        assertTrue(ok(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "  ")).wantsThumbnail)
    }

    @Test fun imageCapture_rejectsFileUri() {
        assertEquals("file_uri", rejected(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "file:///sdcard/DCIM/x.jpg")))
        assertEquals("file_uri", rejected(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "FILE:///sdcard/x.jpg")))
    }

    @Test fun imageCapture_rejectsOwnProviderAndOddSchemes() {
        assertEquals("own_authority", rejected(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "content://com.scannerpromax.fileprovider/documents/a/doc.json")))
        assertEquals("own_authority", rejected(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "content://com.scannerpromax/x")))
        assertEquals("bad_scheme", rejected(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "http://evil/x.jpg")))
        assertEquals("no_authority", rejected(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "content:///x")))
    }

    @Test fun outputUri_similarButForeignAuthorityIsAccepted() {
        // "com.scannerpromaxx" no es nuestro paquete.
        assertNull(ScanRequestParser.outputUriProblem("content://com.scannerpromaxx.files/x", pkg))
        assertNull(ScanRequestParser.outputUriProblem("content://media/external/images/media/12", pkg))
    }

    // ------------------------------------------------------------------ GET_CONTENT

    @Test fun getContent_pdf_singlePdfMultiPage() {
        val r = ok(getContent("application/pdf"))
        assertEquals(OutputFormat.PDF, r.format)
        assertTrue(r.multiPage)
        assertFalse(r.filePerPage)
    }

    @Test fun getContent_pdf_allowMultiple_stillOnePdf() {
        val r = ok(getContent("application/pdf", multiple = true))
        assertEquals(OutputFormat.PDF, r.format)
        assertFalse(r.filePerPage)
    }

    @Test fun getContent_images_single_isOneJpegOnePage() {
        val r = ok(getContent("image/*"))
        assertEquals(OutputFormat.JPEG, r.format)
        assertFalse(r.multiPage)
        assertFalse(r.filePerPage)
    }

    @Test fun getContent_images_multiple_isJpegPerPage() {
        val r = ok(getContent("image/*", multiple = true))
        assertEquals(OutputFormat.JPEG, r.format)
        assertTrue(r.multiPage)
        assertTrue(r.filePerPage)
    }

    @Test fun getContent_jpegOrPngOnly() {
        assertEquals(OutputFormat.JPEG, ok(getContent("image/jpeg")).format)
        assertEquals(OutputFormat.JPEG, ok(getContent("image/jpg")).format)
        assertEquals(OutputFormat.PNG, ok(getContent("image/png")).format)
    }

    @Test fun getContent_any_usesSetting() {
        assertEquals(OutputFormat.PDF, ok(getContent("*/*")).format)
        val jpg = ok(getContent("*/*", multiple = true), AnyFormatDefault.JPEG)
        assertEquals(OutputFormat.JPEG, jpg.format)
        assertTrue(jpg.filePerPage)
        // Sin type: como cualquier archivo.
        assertEquals(OutputFormat.PDF, ok(getContent(null)).format)
    }

    @Test fun getContent_applicationWildcard_isPdf() {
        assertEquals(OutputFormat.PDF, ok(getContent("application/*")).format)
    }

    @Test fun getContent_extraMimeTypes_overrideType() {
        assertEquals(OutputFormat.JPEG, ok(getContent("*/*", extraMimes = listOf("image/jpeg", "image/png"))).format)
        assertEquals(OutputFormat.PDF, ok(getContent("*/*", extraMimes = listOf("application/pdf", "text/plain"))).format)
        assertEquals(OutputFormat.PNG, ok(getContent("*/*", extraMimes = listOf("image/png")), AnyFormatDefault.JPEG).format)
        // Mezcla imagen + PDF: decide el ajuste.
        assertEquals(OutputFormat.PDF, ok(getContent("*/*", extraMimes = listOf("image/*", "application/pdf"))).format)
        assertEquals(OutputFormat.JPEG, ok(getContent("*/*", extraMimes = listOf("image/*", "application/pdf")), AnyFormatDefault.JPEG).format)
    }

    @Test fun getContent_unsupportedMime_isRejected() {
        assertEquals("unsupported_mime", rejected(getContent("video/*")))
        assertEquals("unsupported_mime", rejected(getContent("*/*", extraMimes = listOf("audio/*", "text/plain"))))
        assertEquals("unsupported_mime", rejected(getContent("image/gif")))
    }

    @Test fun mimeMatching() {
        assertTrue(ScanRequestParser.matches("image/*", "image/jpeg"))
        assertTrue(ScanRequestParser.matches("IMAGE/JPEG", "image/jpeg"))
        assertTrue(ScanRequestParser.matches("*/*", "application/pdf"))
        assertTrue(ScanRequestParser.matches("application/pdf; charset=binary", "application/pdf"))
        assertFalse(ScanRequestParser.matches("image/*", "application/pdf"))
        assertFalse(ScanRequestParser.matches("image/png", "image/jpeg"))
    }

    // ------------------------------------------------------------------ SCAN

    private fun scan(vararg extras: Pair<String, Any?>) = IntentFacts(ScanContract.ACTION_SCAN, extras = mapOf(*extras))

    @Test fun scan_defaults() {
        val r = ok(scan())
        assertEquals(RequestSource.SCAN, r.source)
        assertEquals(OutputFormat.PDF, r.format)
        assertEquals(ScanMode.DOCUMENT, r.mode)
        assertTrue(r.multiPage)
        assertFalse(r.filePerPage)
        assertFalse(r.ocr)
        assertNull(r.filter)
        assertNull(r.title)
    }

    @Test fun scan_allExtras() {
        val r = ok(
            scan(
                ScanContract.EXTRA_MODE to "receipt",
                ScanContract.EXTRA_FORMAT to "jpg",
                ScanContract.EXTRA_MULTI_PAGE to true,
                ScanContract.EXTRA_FILTER to "bw",
                ScanContract.EXTRA_OCR to true,
                ScanContract.EXTRA_TITLE to "Factura luz",
            ),
        )
        assertEquals(ScanMode.RECEIPT, r.mode)
        assertEquals(OutputFormat.JPEG, r.format)
        assertTrue(r.filePerPage)
        assertEquals(FilterType.BLACK_WHITE, r.filter)
        assertTrue(r.ocr)
        assertEquals("Factura luz", r.title)
    }

    @Test fun scan_imageSinglePage_isOneFile() {
        val r = ok(scan(ScanContract.EXTRA_FORMAT to "png", ScanContract.EXTRA_MULTI_PAGE to false))
        assertEquals(OutputFormat.PNG, r.format)
        assertFalse(r.multiPage)
        assertFalse(r.filePerPage)
    }

    @Test fun scan_modes() {
        assertEquals(ScanMode.BOOK, ok(scan(ScanContract.EXTRA_MODE to "book")).mode)
        assertEquals(ScanMode.ID_CARD, ok(scan(ScanContract.EXTRA_MODE to "id")).mode)
        assertEquals(ScanMode.WHITEBOARD, ok(scan(ScanContract.EXTRA_MODE to "WHITEBOARD")).mode)
        assertEquals(ScanMode.PHOTO, ok(scan(ScanContract.EXTRA_MODE to "photo")).mode)
        assertEquals(ScanMode.DOCUMENT, ok(scan(ScanContract.EXTRA_MODE to "nave espacial")).mode)
    }

    @Test fun scan_invalidTypes_fallBackToDefaults() {
        val r = ok(scan(ScanContract.EXTRA_MODE to 42, ScanContract.EXTRA_OCR to "quizá", ScanContract.EXTRA_MULTI_PAGE to "0", ScanContract.EXTRA_FORMAT to "tiff"))
        assertEquals(ScanMode.DOCUMENT, r.mode)
        assertFalse(r.ocr)
        assertFalse(r.multiPage)
        assertEquals(OutputFormat.PDF, r.format)
    }

    @Test fun scan_titleIsSanitized() {
        assertEquals("a b c", ok(scan(ScanContract.EXTRA_TITLE to "../a/b\nc")).title)
        assertNull(ok(scan(ScanContract.EXTRA_TITLE to "  /// ")).title)
        assertEquals(ScanContract.MAX_TITLE, ok(scan(ScanContract.EXTRA_TITLE to "x".repeat(500))).title?.length)
    }

    @Test fun filters() {
        assertEquals(FilterType.MAGIC, ScanRequestParser.parseFilter("enhanced"))
        assertEquals(FilterType.ORIGINAL, ScanRequestParser.parseFilter("original"))
        assertNull(ScanRequestParser.parseFilter("sepia"))
    }

    @Test fun unknownAction_isRejected() {
        assertEquals("unsupported_action", rejected(IntentFacts("android.intent.action.VIEW")))
        assertEquals("unsupported_action", rejected(IntentFacts(null)))
    }

    // ------------------------------------------------------------------ plan de entrega

    @Test fun plan_pdfAllPagesOneFile() {
        val p = DeliveryPlanner.plan(ok(getContent("application/pdf")), 3)
        assertEquals(listOf(0, 1, 2), p.pages)
        assertEquals(1, p.fileCount)
        assertFalse(p.filePerPage)
    }

    @Test fun plan_imagesPerPage() {
        val p = DeliveryPlanner.plan(ok(getContent("image/*", multiple = true)), 3)
        assertEquals(3, p.fileCount)
        assertTrue(p.filePerPage)
    }

    @Test fun plan_singleImage_usesChosenPage() {
        val req = ok(getContent("image/*"))
        assertEquals(listOf(0), DeliveryPlanner.plan(req, 3).pages)
        assertEquals(listOf(2), DeliveryPlanner.plan(req, 3, singleImagePage = 2).pages)
        assertEquals(listOf(2), DeliveryPlanner.plan(req, 3, singleImagePage = 9).pages)
        assertEquals(1, DeliveryPlanner.plan(req, 3).fileCount)
    }

    @Test fun plan_imageCapture() {
        val out = DeliveryPlanner.plan(ok(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "content://x.y/z")), 2)
        assertTrue(out.writeToOutput)
        assertEquals(0, out.fileCount)
        val thumb = DeliveryPlanner.plan(ok(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE)), 1)
        assertTrue(thumb.thumbnailOnly)
        assertFalse(thumb.writeToOutput)
    }

    @Test fun plan_textOnlyWhenOcrRequested() {
        assertTrue(DeliveryPlanner.plan(ok(scan(ScanContract.EXTRA_OCR to true)), 1).includeText)
        assertFalse(DeliveryPlanner.plan(ok(getContent("application/pdf")), 1).includeText)
    }

    @Test fun thumbSize() {
        assertEquals(320 to 240, ScanResultIntent.thumbSize(4000, 3000))
        assertEquals(240 to 320, ScanResultIntent.thumbSize(3000, 4000))
        assertEquals(100 to 50, ScanResultIntent.thumbSize(100, 50))
        assertEquals(1 to 1, ScanResultIntent.thumbSize(0, 10))
    }
}
