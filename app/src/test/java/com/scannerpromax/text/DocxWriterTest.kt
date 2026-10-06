package com.scannerpromax.text

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.export.DocxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxWriterTest {

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes()
            }
        }
        return out
    }

    private fun parse(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(xml))

    @Test fun zipConEntradasObligatoriasYXmlBienFormado() {
        val bos = ByteArrayOutputStream()
        DocxWriter.write(
            "Escaneo ñandú",
            listOf(
                DocxWriter.Section("Página 1", listOf("¿Qué tal? ¡Hola! Año 2026: 15 € <b>&\"x\"", "Pingüino")),
                DocxWriter.Section("Página 2", listOf("Control \u0001 eliminado 😀 emoji")),
            ),
            bos,
        )
        val files = entries(bos.toByteArray())
        assertTrue(files.containsKey("[Content_Types].xml"))
        assertTrue(files.containsKey("_rels/.rels"))
        assertTrue(files.containsKey("word/document.xml"))
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { parse(it) } // no lanza

        val doc = parse(files.getValue("word/document.xml"))
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val texts = doc.getElementsByTagNameNS(w, "t")
        val all = (0 until texts.length).joinToString("|") { (texts.item(it) as Element).textContent }
        assertTrue(all.contains("Escaneo ñandú"))
        assertTrue(all.contains("¿Qué tal? ¡Hola! Año 2026: 15 € <b>&\"x\""))
        assertTrue(all.contains("Pingüino"))
        assertTrue(all.contains("Control  eliminado 😀 emoji"))
        // La segunda sección empieza en página nueva.
        assertEquals(1, doc.getElementsByTagNameNS(w, "pageBreakBefore").length)

        val types = String(files.getValue("[Content_Types].xml"), Charsets.UTF_8)
        assertTrue(types.contains("/word/document.xml"))
        val rels = parse(files.getValue("_rels/.rels"))
        val targets = rels.getElementsByTagName("Relationship")
        assertTrue((0 until targets.length).any { (targets.item(it) as Element).getAttribute("Target") == "word/document.xml" })
    }

    @Test fun seccionesDesdeOcrConCorreccion() {
        val r = OcrResult(
            text = "infor-\nmación útil",
            imageWidth = 100, imageHeight = 100,
            blocks = listOf(OcrBlock("x", OcrRect(0f, 0f, 1f, 1f), listOf(OcrLine("infor-", OcrRect(0f, 0f, 1f, 1f))))),
            editedText = "Texto corregido por el usuario",
        )
        val sections = com.scannerpromax.export.TextExporter.sections(listOf(r, null))
        assertEquals("Página 1", sections[0].title)
        assertEquals(listOf("Texto corregido por el usuario"), sections[0].paragraphs)
        assertEquals(listOf("(sin texto)"), sections[1].paragraphs)
    }
}
