package com.scannerpromax.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class PdfboxJvmProbeTest {
    @Test
    fun probe() {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val font = PDType0Font.load(doc, File("src/main/assets/fonts/EpmSans-Bold.ttf")); val f2 = PDType0Font.load(doc, File("src/main/assets/fonts/EpmHand-Regular.ttf")); val f3 = PDType0Font.load(doc, File("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"))
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText(); cs.setFont(font, 14f); cs.setTextMatrix(Matrix.getTranslateInstance(50f, 700f))
            cs.showText("Año €12 ¿Qué?"); cs.setFont(f2, 12f); cs.showText(" mano ñ"); cs.setFont(f3, 12f); cs.showText(" normal"); cs.endText()
        }
        val bos = ByteArrayOutputStream(); doc.save(bos); doc.close()
        val d2 = PDDocument.load(bos.toByteArray())
        val t = PDFTextStripper().getText(d2)
        d2.close()
        println("TEXTO=" + t)
        assertTrue(t.contains("Año"))
    }
}
