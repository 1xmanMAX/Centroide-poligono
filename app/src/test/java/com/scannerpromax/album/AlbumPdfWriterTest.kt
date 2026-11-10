package com.scannerpromax.album

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exportación a PDF con fondo PDF (vectorial) en la JVM: páginas, fondo conservado y colocación de cada cosa. */
class AlbumPdfWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    /** PDF de origen de 2 páginas 300×400 pt con un rectángulo de marca; la 2.ª con /Rotate 90. */
    private fun sourcePdf(): File {
        val f = tmp.newFile("origen.pdf")
        PDDocument().use { doc ->
            repeat(2) { i ->
                val page = PDPage(PDRectangle(300f, 400f))
                if (i == 1) page.rotation = 90
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.setNonStrokingColor(0.1f, 0.2f, 0.9f)
                    cs.addRect(10f, 300f, 120f, 90f)
                    cs.fill()
                }
            }
            doc.save(f)
        }
        return f
    }

    private fun jpeg(dir: File, rel: String, w: Int, h: Int, rgb: Int): String {
        nu.pattern.OpenCV.loadLocally()
        val m = org.opencv.core.Mat(h, w, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar((rgb and 0xFF).toDouble(), ((rgb shr 8) and 0xFF).toDouble(), ((rgb shr 16) and 0xFF).toDouble()))
        val f = File(dir, rel).apply { parentFile.mkdirs() }
        val buf = org.opencv.core.MatOfByte()
        org.opencv.imgcodecs.Imgcodecs.imencode(".jpg", m, buf)
        f.writeBytes(buf.toArray())
        m.release(); buf.release()
        return rel
    }

    /** CTM vigente en cada «Do» de la página (nombre del XObject -> matriz). */
    private fun ctmAtDo(page: PDPage): List<Pair<String, Affine>> {
        val parser = PDFStreamParser(page)
        parser.parse()
        val out = ArrayList<Pair<String, Affine>>()
        val stack = ArrayDeque<Affine>()
        var ctm = Affine()
        val args = ArrayList<Any>()
        for (t in parser.tokens) {
            if (t is Operator) {
                when (t.name) {
                    "q" -> stack.addLast(ctm)
                    "Q" -> ctm = stack.removeLast()
                    "cm" -> {
                        val v = args.map { (it as COSNumber).floatValue().toDouble() }
                        ctm = Affine(v[0], v[1], v[2], v[3], v[4], v[5]).then(ctm)
                    }
                    "Do" -> out += (args[0] as COSName).name to ctm
                }
                args.clear()
            } else {
                args += t
            }
        }
        return out
    }

    private fun near(a: P, b: P, tol: Double = 0.05) = kotlin.math.abs(a.x - b.x) < tol && kotlin.math.abs(a.y - b.y) < tol

    @Test
    fun pdfBackgroundStaysVectorAndStickersGoOnTop() {
        val dir = tmp.newFolder("album")
        val src = sourcePdf()
        val bgRel = "bg/p1.pdf"
        val (dw, dh) = AlbumPdfWriter.extractPage(src, 0, File(dir, bgRel).apply { parentFile.mkdirs() })
        assertEquals(300.0, dw, 1e-6); assertEquals(400.0, dh, 1e-6)
        val (rw, rh) = AlbumPdfWriter.extractPage(src, 1, File(dir, "bg/p2.pdf"))
        assertEquals(400.0, rw, 1e-6); assertEquals(300.0, rh, 1e-6) // /Rotate 90

        val stk = jpeg(dir, "stk/a.jpg", 160, 100, 0xD02020)
        val bgImg = jpeg(dir, "bg/img.jpg", 80, 120, 0x20A040)
        val unit = 25.4 / 72
        val sticker = Sticker("src/a.jpg", com.scannerpromax.domain.Quad.full(1, 1), stk, 160, 100)
        val pdfPage = AlbumOps.pageWith(SheetBackground(BackgroundKind.PDF_PAGE, bgRel, null, 300f, 400f)).let { p ->
            AlbumOps.addSlot(p, Box(50.0, 60.0, 40.0, 25.0, 15.0)).first.let { q ->
                q.copy(slots = q.slots.map { it.copy(sticker = sticker) })
            }
        }
        val rotatedSrc = AlbumOps.pageWith(SheetBackground(BackgroundKind.PDF_PAGE, "bg/p2.pdf", null, 400f, 300f))
        val turned = AlbumGeometry.rotatePage(AlbumOps.pageWith(SheetBackground(BackgroundKind.PDF_PAGE, bgRel, null, 300f, 400f)))
        val blank = AlbumStore.blankPage(PaperSize.A4, false)
        val image = AlbumOps.pageWith(SheetBackground(BackgroundKind.IMAGE, bgImg, null, 80f, 120f))
        assertEquals(300 * unit, pdfPage.widthMm.toDouble(), 1e-3)

        val out = tmp.newFile("album.pdf")
        out.outputStream().use { AlbumPdfWriter.write(listOf(pdfPage, rotatedSrc, turned, blank, image), dir, it, "Prueba") }

        PDDocument.load(out).use { doc ->
            assertEquals(5, doc.numberOfPages)
            val p0 = doc.getPage(0)
            assertEquals(300f, p0.mediaBox.width, 0.01f); assertEquals(400f, p0.mediaBox.height, 0.01f)
            val names = p0.resources.xObjectNames.toList()
            val forms = names.filter { p0.resources.getXObject(it) is PDFormXObject }
            val images = names.filter { p0.resources.getXObject(it) is PDImageXObject }
            assertEquals(1, forms.size); assertEquals(1, images.size)
            // el fondo sigue siendo vectorial: el contenido original (rectángulo relleno) está en el Form XObject
            val form = p0.resources.getXObject(forms[0]) as PDFormXObject
            val formOps = PDFStreamParser(form).also { it.parse() }.tokens.filterIsInstance<Operator>().map { it.name }
            assertTrue(formOps.containsAll(listOf("re", "f")))
            // primero el fondo, encima el cromo (JPEG sin recomprimir: DCTDecode)
            val dos = ctmAtDo(p0)
            assertEquals(listOf(forms[0].name, images[0].name), dos.map { it.first })
            val img = p0.resources.getXObject(images[0]) as PDImageXObject
            assertEquals(160, img.width)
            assertTrue(img.cosObject.getDictionaryObject(COSName.FILTER).toString().contains("DCTDecode"))
            // el fondo ocupa la página exacta
            val fm = dos[0].second
            assertTrue(near(fm.map(P(0.0, 0.0)), P(0.0, 0.0)))
            assertTrue(near(fm.map(P(300.0, 400.0)), P(300.0, 400.0)))
            // el centro del cromo cae en el centro del recuadro (mm -> pt, y hacia arriba)
            val c = dos[1].second.map(P(0.5, 0.5))
            assertTrue(near(c, P(50.0 / unit, 400.0 - 60.0 / unit), 0.1))

            // página con /Rotate 90 propio: 400×300 y la esquina superior izquierda SIN girar queda arriba a la derecha
            val p1 = doc.getPage(1)
            assertEquals(400f, p1.mediaBox.width, 0.01f); assertEquals(300f, p1.mediaBox.height, 0.01f)
            val m1 = ctmAtDo(p1).single().second
            assertTrue(near(m1.map(P(0.0, 400.0)), P(400.0, 300.0)))
            assertTrue(near(m1.map(P(300.0, 0.0)), P(0.0, 0.0)))

            // hoja girada por el usuario: igual resultado que el /Rotate 90 del PDF
            val p2 = doc.getPage(2)
            assertEquals(400f, p2.mediaBox.width, 0.01f)
            val m2 = ctmAtDo(p2).single().second
            assertTrue(near(m2.map(P(0.0, 400.0)), P(400.0, 300.0)))

            // hoja en blanco: nada dibujado; fondo imagen: una imagen a página completa
            assertEquals(0, doc.getPage(3).resources.xObjectNames.count())
            val p4 = doc.getPage(4)
            val m4 = ctmAtDo(p4).single().second
            assertTrue(near(m4.map(P(0.0, 1.0)), P(0.0, p4.mediaBox.height.toDouble()), 0.1))
            assertTrue(near(m4.map(P(1.0, 0.0)), P(p4.mediaBox.width.toDouble(), 0.0), 0.1))
            assertEquals(297.0 / unit, p4.mediaBox.height.toDouble(), 0.1)
        }
    }

    @Test
    fun jpegInfoReadsSof() {
        val dir = tmp.newFolder("j")
        val rel = jpeg(dir, "a.jpg", 37, 21, 0x123456)
        val info = JpegInfo.read(File(dir, rel).readBytes())!!
        assertEquals(37, info.width); assertEquals(21, info.height); assertEquals(3, info.components)
    }
}
