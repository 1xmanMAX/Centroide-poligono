package com.scannerpromax.integration

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.scannerpromax.ui.attach.attachButtonLabel
import com.scannerpromax.ui.attach.attachSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Armado del Intent de resultado y lectura de los Intents recibidos (Robolectric: Intent/ClipData reales). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScanResultIntentTest {
    private val pkg = "com.scannerpromax"
    private fun req(facts: IntentFacts, any: AnyFormatDefault = AnyFormatDefault.PDF) =
        (ScanRequestParser.parse(facts, any, pkg) as ParseResult.Ok).request

    private fun uri(name: String) = Uri.parse("content://com.scannerpromax.fileprovider/cache/attach/1/$name")

    @Test fun pdf_singleUriWithGrantAndMetadata() {
        val plan = DeliveryPlanner.plan(req(IntentFacts(ScanRequestParser.ACTION_GET_CONTENT, "application/pdf")), 3)
        val i = ScanResultIntent.build(plan, listOf(uri("Escaneo.pdf")), pageCount = 3)
        assertEquals(uri("Escaneo.pdf"), i.data)
        assertEquals("application/pdf", i.type)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, i.clipData!!.itemCount)
        assertEquals(uri("Escaneo.pdf"), i.clipData!!.getItemAt(0).uri)
        assertEquals("application/pdf", i.clipData!!.description.getMimeType(0))
        assertEquals(3, i.getIntExtra(ScanContract.EXTRA_PAGE_COUNT, -1))
        assertEquals(1, i.getIntExtra(ScanContract.EXTRA_FILE_COUNT, -1))
        assertEquals("application/pdf", i.getStringExtra(ScanContract.EXTRA_MIME_TYPE))
        assertEquals("pdf", i.getStringExtra(ScanContract.EXTRA_RESULT_FORMAT))
        assertFalse(i.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test fun images_multiple_clipDataOnePerPage() {
        val plan = DeliveryPlanner.plan(req(IntentFacts(ScanRequestParser.ACTION_GET_CONTENT, "image/*", allowMultiple = true)), 3)
        val uris = listOf(uri("a_1.jpg"), uri("a_2.jpg"), uri("a_3.jpg"))
        val i = ScanResultIntent.build(plan, uris, pageCount = 3)
        assertEquals(3, i.clipData!!.itemCount)
        assertEquals(uris, (0 until 3).map { i.clipData!!.getItemAt(it).uri })
        assertEquals(uris[0], i.data)
        assertEquals("image/jpeg", i.type)
        @Suppress("DEPRECATION")
        assertEquals(uris, i.getParcelableArrayListExtra<Uri>(ScanContract.EXTRA_URIS))
        @Suppress("DEPRECATION")
        assertEquals(uris, i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(3, i.getIntExtra(ScanContract.EXTRA_FILE_COUNT, -1))
    }

    @Test fun scan_withOcr_includesText() {
        val r = req(IntentFacts(ScanContract.ACTION_SCAN, extras = mapOf(ScanContract.EXTRA_OCR to true)))
        val plan = DeliveryPlanner.plan(r, 1)
        val i = ScanResultIntent.build(plan, listOf(uri("x.pdf")), 1, text = "Hola mundo")
        assertEquals("Hola mundo", i.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun imageCapture_withOutput_emptyResult() {
        val plan = DeliveryPlanner.plan(req(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE, outputUri = "content://media/external/images/media/5")), 1)
        val i = ScanResultIntent.build(plan, emptyList(), 1)
        assertNull(i.data)
        assertNull(i.clipData)
        assertEquals(0, i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun imageCapture_withoutOutput_thumbnailInData() {
        val plan = DeliveryPlanner.plan(req(IntentFacts(ScanRequestParser.ACTION_IMAGE_CAPTURE)), 1)
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val i = ScanResultIntent.build(plan, emptyList(), 1, thumbnail = bmp)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Bitmap>("data")
        assertNotNull(data)
        assertEquals(32, data!!.width)
    }

    // ------------------------------------------------------------------ lectura del Intent recibido

    @Test fun reader_getContent() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "application/pdf"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val f = IntentFactsReader.read(i)
        assertEquals(Intent.ACTION_GET_CONTENT, f.action)
        assertEquals("*/*", f.type)
        assertEquals(listOf("image/png", "application/pdf"), f.extraMimeTypes)
        assertTrue(f.allowMultiple)
    }

    @Test fun reader_imageCaptureOutput() {
        val out = Uri.parse("content://com.example.files/photo.jpg")
        val f = IntentFactsReader.read(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, out))
        assertEquals(out.toString(), f.outputUri)
        val file = IntentFactsReader.read(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, Uri.parse("file:///sdcard/a.jpg")))
        assertEquals("file_uri", (ScanRequestParser.parse(file, AnyFormatDefault.PDF, pkg) as ParseResult.Rejected).reason)
    }

    @Test fun reader_scanExtras_fullKeysAndAliases_ignoresOtherTypes() {
        val i = Intent(ScanContract.ACTION_SCAN).apply {
            putExtra("mode", "book")
            putExtra(ScanContract.EXTRA_FORMAT, "png")
            putExtra("format", "jpeg") // la clave completa gana
            putExtra(ScanContract.EXTRA_OCR, true)
            putExtra(ScanContract.EXTRA_TITLE, Uri.parse("content://x/y")) // tipo no admitido: se ignora
            putExtra("otra.cosa", "nada")
        }
        val f = IntentFactsReader.read(i)
        assertEquals("book", f.extras[ScanContract.EXTRA_MODE])
        assertEquals("png", f.extras[ScanContract.EXTRA_FORMAT])
        assertEquals(true, f.extras[ScanContract.EXTRA_OCR])
        assertFalse(f.extras.containsKey(ScanContract.EXTRA_TITLE))
        assertFalse(f.extras.containsKey("otra.cosa"))
        val r = req(f)
        assertEquals(OutputFormat.PNG, r.format)
        assertTrue(r.ocr)
    }

    // ------------------------------------------------------------------ textos y limpieza

    @Test fun buttonAndSummary() {
        assertEquals("Adjuntar a PixPin", attachButtonLabel("PixPin", true))
        assertEquals("Adjuntar", attachButtonLabel(null, true))
        assertEquals("Guardar en mis documentos", attachButtonLabel("PixPin", false))
        val pdf = DeliveryPlanner.plan(req(IntentFacts(ScanRequestParser.ACTION_GET_CONTENT, "application/pdf")), 2)
        assertEquals("PDF · 2 páginas", attachSummary(pdf, 2))
        val one = DeliveryPlanner.plan(req(IntentFacts(ScanRequestParser.ACTION_GET_CONTENT, "image/*")), 2)
        assertTrue(attachSummary(one, 2).startsWith("1 imagen JPG"))
        val many = DeliveryPlanner.plan(req(IntentFacts(ScanRequestParser.ACTION_GET_CONTENT, "image/*", allowMultiple = true)), 3)
        assertEquals("3 imágenes JPG", attachSummary(many, 3))
    }

    @Test fun cleanup_removesOnlyOldDeliveries() {
        val cache = Files.createTempDirectory("attach").toFile()
        try {
            val now = 10_000_000_000L
            val old = AttachFiles.newDeliveryDir(cache, now - AttachFiles.MAX_AGE_MS - 5_000)
            File(old, "a.pdf").apply { writeText("x"); setLastModified(now - AttachFiles.MAX_AGE_MS - 5_000) }
            old.setLastModified(now - AttachFiles.MAX_AGE_MS - 5_000)
            val fresh = AttachFiles.newDeliveryDir(cache, now - 1_000)
            File(fresh, "b.pdf").apply { writeText("y"); setLastModified(now - 1_000) }
            fresh.setLastModified(now - 1_000)
            assertEquals(1, AttachFiles.cleanup(cache, now))
            assertFalse(old.exists())
            assertTrue(fresh.exists())
            // Dos entregas en el mismo milisegundo: carpetas distintas.
            assertTrue(AttachFiles.newDeliveryDir(cache, 5L) != AttachFiles.newDeliveryDir(cache, 5L))
            assertEquals("Factura", AttachFiles.baseName("Factura", "Documento 1"))
            assertEquals("Documento 1", AttachFiles.baseName(null, "Documento 1"))
            assertEquals("Escaneo", AttachFiles.baseName("///", null))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test fun sdkSanity() {
        assertEquals(34, Build.VERSION.SDK_INT)
    }
}
