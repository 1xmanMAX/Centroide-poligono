package com.scannerpromax.ai.docgen

import com.scannerpromax.pdf.PdfFontMetrics
import com.scannerpromax.pdf.PdfReconstructor
import com.scannerpromax.pdf.ReFont
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocRenderersTest {

    /** JPEG mínimo de verdad no hace falta: el DOCX y el PDF guardan los bytes tal cual (sin decodificar). */
    private val fakeCrops = CropSource { page, box -> CropImage(byteArrayOf(-1, -40, -1, -39, page.toByte()), box.width, box.height) }

    private fun sample(blocksExtra: List<DocBlock> = emptyList()) = GenDocument(
        title = "Informe & resumen",
        page = PageSetup(Paper.A4, landscape = false, Margins(15f, 15f, 15f, 15f)),
        header = HeaderFooter("Empresa S.A.", Align.LEFT),
        footer = HeaderFooter("Confidencial", Align.CENTER),
        pageNumbers = PageNumbers.FOOTER_RIGHT,
        blocks = listOf(
            DocBlock.Heading("Informe anual 2026", 1, TextStyle(bold = true, align = Align.CENTER)),
            DocBlock.Paragraph("Texto con **negrita**, *cursiva* y __subrayado__ <menor> & más. Ñandú, café, €.", TextStyle(align = Align.JUSTIFY, color = "1F3A93")),
            DocBlock.ListBlock(listOf(ListItem("Primero"), ListItem("Sub-punto", 1), ListItem("Segundo")), ordered = true),
            DocBlock.ListBlock(listOf(ListItem("Viñeta A"), ListItem("Viñeta B")), ordered = false),
            DocBlock.Table(
                3, 3,
                listOf(
                    TableCellSpec(0, 0, text = "Producto"), TableCellSpec(0, 1, colSpan = 2, text = "Importe"),
                    TableCellSpec(1, 0, rowSpan = 2, text = "Pan integral"), TableCellSpec(1, 1, text = "4,50"), TableCellSpec(1, 2, text = "EUR", fill = "FFEE00"),
                    TableCellSpec(2, 1, text = "3,00", align = Align.RIGHT),
                ),
                headerRows = 1,
            ),
            DocBlock.Image(1, Box1000(100, 100, 900, 500), caption = "Figura 1. Ventas"),
            DocBlock.Formula("E=mc^2", page = 1, bbox = Box1000(300, 600, 700, 650)),
            DocBlock.Formula("""\frac{a}{b}"""),
            DocBlock.PageBreak(),
            DocBlock.Paragraph("Manuscrito: hola", TextStyle(handwriting = true)),
        ) + blocksExtra,
    )

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() }
        }
        return out
    }

    @Test fun docxHasStylesListsTablesImagesHeaderFooter() {
        val bos = ByteArrayOutputStream()
        DocxRenderer.write(sample(), fakeCrops, bos)
        val parts = unzip(bos.toByteArray())
        for (n in listOf("[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "word/document.xml", "word/styles.xml", "word/numbering.xml",
            "word/_rels/document.xml.rels", "word/header1.xml", "word/footer1.xml", "word/media/image1.jpeg", "word/media/image2.jpeg")) {
            assertTrue("falta $n en ${parts.keys}", n in parts)
        }
        // Todo el XML bien formado.
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (n, b) ->
            runCatching { dbf.newDocumentBuilder().parse(ByteArrayInputStream(b)) }.onFailure { throw AssertionError("$n mal formado: ${it.message}") }
        }
        val doc = String(parts.getValue("word/document.xml"), Charsets.UTF_8)
        assertTrue(doc.contains("""<w:pStyle w:val="Heading1"/>"""))
        assertTrue(doc.contains("""<w:jc w:val="both"/>"""))
        assertTrue(doc.contains("<w:b/><w:bCs/>"))
        assertTrue(doc.contains("<w:i/><w:iCs/>"))
        assertTrue(doc.contains("""<w:u w:val="single"/>"""))
        assertTrue(doc.contains("""<w:color w:val="1F3A93"/>"""))
        assertTrue(doc.contains("&lt;menor&gt; &amp; más. Ñandú, café, €."))
        assertTrue(doc.contains("""<w:numId w:val="2"/>""")) // lista numerada propia
        assertTrue(doc.contains("""<w:numId w:val="1"/>""")) // viñetas
        assertTrue(doc.contains("""<w:ilvl w:val="1"/>"""))
        assertTrue(doc.contains("""<w:gridSpan w:val="2"/>"""))
        assertTrue(doc.contains("""<w:vMerge w:val="restart"/>"""))
        assertTrue(doc.contains("<w:vMerge/>"))
        assertTrue(doc.contains("<w:tblHeader/>"))
        assertTrue(doc.contains("""w:fill="FFEE00""""))
        assertTrue(doc.contains("""r:embed="rIdImg1""""))
        assertTrue(doc.contains("Figura 1. Ventas"))
        assertTrue(doc.contains("""descr="LaTeX: E=mc^2""""))
        assertTrue(doc.contains("(a)/(b)")) // fórmula sin recorte como texto
        assertTrue(doc.contains("""<w:br w:type="page"/>"""))
        assertTrue(doc.contains("Patrick Hand"))
        assertTrue(doc.contains("""<w:headerReference w:type="default" r:id="rIdHeader1"/>"""))
        assertTrue(doc.contains("""<w:pgSz w:w="11906" w:h="16838"/>"""))
        val rels = String(parts.getValue("word/_rels/document.xml.rels"))
        assertTrue(rels.contains("media/image1.jpeg"))
        assertTrue(rels.contains("relationships/numbering"))
        val footer = String(parts.getValue("word/footer1.xml"))
        assertTrue(footer.contains("Confidencial") && footer.contains(" PAGE "))
        val numbering = String(parts.getValue("word/numbering.xml"))
        assertTrue(numbering.contains("""<w:startOverride w:val="1"/>"""))
        val ct = String(parts.getValue("[Content_Types].xml"))
        assertTrue(ct.contains("""Extension="jpeg""""))
        assertTrue(String(parts.getValue("docProps/core.xml")).contains("Informe &amp; resumen"))
        // Fila 2 de la tabla: celda cubierta por la combinación vertical, luego "3,00".
        assertTrue(doc.indexOf("<w:vMerge/>") < doc.indexOf("3,00"))
    }

    @Test fun docxLandscapeAndNoImagesWhenCropFails() {
        val d = GenDocument(page = PageSetup(Paper.LETTER, landscape = true), blocks = listOf(DocBlock.Image(1, Box1000(0, 0, 500, 500), caption = "Pie")))
        val parts = unzip(ByteArrayOutputStream().also { DocxRenderer.write(d, { _, _ -> null }, it) }.toByteArray())
        val doc = String(parts.getValue("word/document.xml"))
        assertTrue(doc.contains("""w:orient="landscape""""))
        assertFalse(parts.keys.any { it.startsWith("word/media/") })
        assertTrue(doc.contains("Pie"))
    }

    // ------------------------------------------------------------------ PDF

    private fun fonts(doc: PDDocument): PdfFontMetrics {
        val regular = javaClass.getResourceAsStream("/" + PdfReconstructor.LIBERATION_SANS)!!.use { PDType0Font.load(doc, it, true) }
        val bold = File("src/main/assets/" + PdfReconstructor.BOLD_FONT).inputStream().use { PDType0Font.load(doc, it, true) }
        val hand = File("src/main/assets/" + PdfReconstructor.HAND_FONT).inputStream().use { PDType0Font.load(doc, it, true) }
        return PdfFontMetrics(mapOf(ReFont.REGULAR to regular, ReFont.BOLD to bold, ReFont.HAND to hand))
    }

    private fun renderPdf(d: GenDocument): ByteArray {
        val pdf = PDDocument()
        try {
            PdfDocRenderer(pdf, fonts(pdf), fakeCrops).render(d)
            val bos = ByteArrayOutputStream()
            pdf.save(bos)
            System.getProperty("docgen.out")?.let { dir -> File(dir).mkdirs(); File(dir, "docgen_${d.blocks.size}.pdf").writeBytes(bos.toByteArray()) }
            return bos.toByteArray()
        } finally {
            pdf.close()
        }
    }

    /** Texto (visible e invisible) de cada página, decodificado con el ToUnicode de cada fuente. */
    private fun pagesText(pdf: ByteArray): List<Pair<String, String>> = PDDocument.load(pdf).use { doc ->
        (0 until doc.numberOfPages).map { i ->
            val page = doc.getPage(i)
            val parser = PDFStreamParser(page); parser.parse()
            val vis = StringBuilder(); val inv = StringBuilder()
            var font: PDFont? = null; var mode = 0
            val ops = ArrayList<Any>()
            fun dec(s: COSString): String {
                val f = font ?: return ""
                val sb = StringBuilder(); val inp = ByteArrayInputStream(s.bytes)
                while (inp.available() > 0) sb.append(f.toUnicode(f.readCode(inp)) ?: "")
                return sb.toString()
            }
            for (t in parser.tokens) {
                if (t !is Operator) { ops += t; continue }
                when (t.name) {
                    "Tf" -> font = page.resources.getFont(ops[0] as COSName)
                    "Tr" -> mode = (ops[0] as com.tom_roush.pdfbox.cos.COSNumber).intValue()
                    "Tj" -> (if (mode == 3) inv else vis).append(dec(ops[0] as COSString)).append(' ')
                    "TJ" -> (ops[0] as COSArray).forEach { if (it is COSString) (if (mode == 3) inv else vis).append(dec(it)) }
                }
                ops.clear()
            }
            vis.toString() to inv.toString()
        }
    }

    @Test fun pdfHasRealTextTablesImagesAndHeaderFooter() {
        val bytes = renderPdf(sample())
        assertTrue(String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-")
        val pages = pagesText(bytes)
        assertEquals(2, pages.size) // salto de página explícito
        val p1 = pages[0].first
        for (w in listOf("Informe", "anual", "negrita", "cursiva", "Ñandú,", "café,", "€.", "1.", "a.", "2.", "Viñeta", "Producto", "Importe", "Pan", "integral", "4,50", "3,00", "Figura", "Empresa", "S.A.", "Confidencial", "(a)/(b)")) {
            assertTrue("falta '$w' en: $p1", p1.contains(w))
        }
        assertTrue(pages[0].second.contains("E=mc^2")) // LaTeX invisible buscable sobre el recorte
        assertTrue(pages[1].first.contains("Manuscrito:"))
        assertTrue(pages[1].first.contains("2")) // número de página
        PDDocument.load(bytes).use { d ->
            assertEquals(595, d.getPage(0).mediaBox.width.toInt())
            val xobjects = d.getPage(0).resources.xObjectNames.toList()
            assertEquals(2, xobjects.size) // figura + fórmula
        }
    }

    @Test fun pdfPaginatesLongTextAndRepeatsTableHeader() {
        val long = (1..60).map { DocBlock.Paragraph("Párrafo número $it. " + "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(3), TextStyle(align = Align.JUSTIFY)) }
        val bigTable = DocBlock.Table(
            80, 2,
            listOf(TableCellSpec(0, 0, text = "Clave", bold = true), TableCellSpec(0, 1, text = "Valor")) +
                (1 until 80).flatMap { r -> listOf(TableCellSpec(r, 0, text = "fila$r"), TableCellSpec(r, 1, text = "valor $r")) },
            headerRows = 1,
        )
        val d = GenDocument(page = PageSetup(Paper.A5), blocks = long + bigTable)
        val pages = pagesText(renderPdf(d))
        assertTrue("páginas: ${pages.size}", pages.size >= 8)
        val all = pages.joinToString(" ") { it.first }
        assertTrue(all.contains("número 60."))
        assertTrue(all.contains("fila79"))
        // La cabecera de la tabla aparece en varias páginas.
        assertTrue(pages.count { it.first.contains("Clave") } >= 2)
        // Ningún párrafo perdido.
        (1..60).forEach { assertTrue("falta $it", all.contains("número $it.")) }
    }

    @Test fun pdfLandscapeLetter() {
        val d = GenDocument(page = PageSetup(Paper.LETTER, landscape = true), blocks = listOf(DocBlock.Heading("Hola", 2)))
        PDDocument.load(renderPdf(d)).use { assertEquals(792, it.getPage(0).mediaBox.width.toInt()) }
    }
}
