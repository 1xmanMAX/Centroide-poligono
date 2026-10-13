package com.scannerpromax.pdf

import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.PageSize
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF reconstruido de extremo a extremo con pdfbox-android en la JVM: DocLayouts de prueba (salidas reales de
 * PaddleOCR-VL del banco convertidas a JSON + una página compuesta), fuentes reales (Liberation Sans de pdfbox y las
 * de assets/fonts) y extracción del texto del PDF guardado.
 *
 * Con -PreconOut=DIR guarda los PDF en DIR y con -PreconImages=DIR incrusta los recortes de DIR/<nombre>.png.
 */
class ReconstructionPdfTest {

    private fun layout(name: String): DocLayout {
        val text = javaClass.getResourceAsStream("/reconstruccion/$name.layout.json")!!.bufferedReader().readText()
        return DocLayoutFiles.parse(text) ?: error("JSON de prueba ilegible: $name")
    }

    private fun loadFonts(doc: PDDocument): PdfFontMetrics {
        val regular = javaClass.getResourceAsStream("/" + PdfReconstructor.LIBERATION_SANS)!!.use { PDType0Font.load(doc, it, true) }
        val bold = File("src/main/assets/" + PdfReconstructor.BOLD_FONT).inputStream().use { PDType0Font.load(doc, it, true) }
        val hand = File("src/main/assets/" + PdfReconstructor.HAND_FONT).inputStream().use { PDType0Font.load(doc, it, true) }
        return PdfFontMetrics(mapOf(ReFont.REGULAR to regular, ReFont.BOLD to bold, ReFont.HAND to hand))
    }

    /**
     * Recortes JPEG preparados por el banco (tools/recon_layouts.py → <nombre>_<l>_<t>_<r>_<b>.jpg, píxeles de la
     * imagen del DocLayout); solo si se pasa -PreconImages (la JVM de Android no trae java.awt/ImageIO).
     */
    private fun cropper(doc: PDDocument, name: String): (ReOp.Image) -> PDImageXObject? {
        val dir = System.getProperty("recon.images") ?: return { null }
        return { op ->
            val f = File(dir, "${name}_${op.src.left}_${op.src.top}_${op.src.right}_${op.src.bottom}.jpg")
            if (f.isFile) {
                PDImageXObject(doc, ByteArrayInputStream(f.readBytes()), COSName.DCT_DECODE, op.src.width, op.src.height, 8, PDDeviceRGB.INSTANCE)
            } else {
                null
            }
        }
    }

    private fun build(name: String, pageSize: PageSize = PageSize.AUTO, layout: DocLayout = layout(name)): ByteArray {
        val doc = PDDocument()
        try {
            val metrics = loadFonts(doc)
            val plan = ReconstructionLayout.plan(layout, pageSize, 0f, metrics)
            ReconstructionRenderer.render(doc, plan, metrics, cropper(doc, name))
            val bos = ByteArrayOutputStream()
            doc.save(bos)
            System.getProperty("recon.out")?.let { dir ->
                File(dir).mkdirs()
                File(dir, if (pageSize == PageSize.AUTO) "$name.pdf" else "${name}_$pageSize.pdf").writeBytes(bos.toByteArray())
            }
            return bos.toByteArray()
        } finally {
            doc.close()
        }
    }

    /** Texto mostrado (Tj/TJ) de la página, decodificado con el ToUnicode de cada fuente; visibles e invisibles aparte. */
    private data class Extracted(val visible: String, val invisible: String, val fonts: Set<String>)

    private fun extract(pdf: ByteArray): Extracted {
        PDDocument.load(pdf).use { doc ->
            val page: PDPage = doc.getPage(0)
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens = parser.tokens
            val vis = StringBuilder(); val inv = StringBuilder(); val fonts = HashSet<String>()
            var font: PDFont? = null
            var mode = 0
            val operands = ArrayList<Any>()
            fun decode(s: COSString): String {
                val f = font ?: return ""
                val out = StringBuilder()
                val input = ByteArrayInputStream(s.bytes)
                while (input.available() > 0) {
                    val code = f.readCode(input)
                    out.append(f.toUnicode(code) ?: "")
                }
                return out.toString()
            }
            for (t in tokens) {
                if (t !is Operator) { operands += t; continue }
                when (t.name) {
                    "Tf" -> { font = page.resources.getFont(operands[0] as COSName); fonts += font!!.name }
                    "Tr" -> mode = (operands[0] as COSNumber).intValue()
                    "Tj" -> (if (mode == 3) inv else vis).append(decode(operands[0] as COSString)).append('\n')
                    "TJ" -> (operands[0] as COSArray).forEach { if (it is COSString) (if (mode == 3) inv else vis).append(decode(it)) }
                }
                operands.clear()
            }
            return Extracted(vis.toString(), inv.toString(), fonts)
        }
    }

    @Test
    fun paginaCompuestaConTituloTablaGraficoYFormula() {
        val pdf = build("compuesta")
        assertTrue(String(pdf, 0, 8, Charsets.ISO_8859_1).startsWith("%PDF-"))
        val t = extract(pdf)
        // Texto visible con acentos, eñe y euro.
        assertTrue(t.visible, t.visible.contains("Informe de compras — octubre 2026") || t.visible.contains("Informe de compras - octubre 2026"))
        assertTrue(t.visible.contains("Ñuñoa, 13/10/2026."))
        assertTrue(t.visible.contains("euros (€)"))
        // Celdas de la tabla (salida real OTSL del modelo).
        for (c in listOf("Producto", "Cantidad", "Pan integral", "Café molido", "4,50")) assertTrue(c, t.visible.contains(c))
        // Datos del gráfico y LaTeX de la fórmula: invisibles pero buscables.
        assertTrue(t.invisible, t.invisible.contains("Febrero | 19"))
        assertTrue(t.invisible.contains("\\frac{-b\\pm{\\sqrt{b^{2}-4a c}}}{2a}"))
        // Negrita (título), normal y manuscrita incrustadas como subconjunto.
        assertTrue(t.fonts.toString(), t.fonts.any { it.contains("EPMSans-Bold") })
        assertTrue(t.fonts.toString(), t.fonts.any { it.contains("EPMHand") })
        assertTrue(t.fonts.toString(), t.fonts.any { it.contains("LiberationSans") })
        assertTrue(t.fonts.all { it.matches(Regex("[A-Z]{6}\\+.*")) }) // prefijo de subconjunto
    }

    @Test
    fun formularioRealDeSpotting() {
        val t = extract(build("funsd_13"))
        for (s in listOf("NEW COMPETITIVE PRODUCTS", "REPORTED BY:", "B & W", "VICEROY KING BOX AND VICEROY LIGHTS KING BOX")) {
            assertTrue(s, t.visible.contains(s))
        }
        assertEquals("", t.invisible)
    }

    @Test
    fun manuscritoRealConColorYTamanoFijo() {
        val pdf = build("cuaderno1", PageSize.A4)
        val t = extract(pdf)
        assertTrue(t.visible.contains("PL-320 4 L/min → 38/unidad → 152") || t.visible.contains("PL-320 4 L/min"))
        assertTrue(t.visible.contains("H2O 2300")) // LaTeX en línea limpiado
        PDDocument.load(pdf).use { d ->
            val box = d.getPage(0).mediaBox
            // A4 apaisado (la imagen es más ancha que alta), sin recortar.
            assertEquals(841.89f, box.width, 0.5f)
            assertEquals(595.28f, box.height, 0.5f)
        }
    }

    /** Con -PreconImages: PDF de TODAS las maquetaciones del banco de ese directorio (para revisarlas a ojo). */
    @Test
    fun muestrasDelBanco() {
        val dir = System.getProperty("recon.images") ?: return
        val files = File(dir).listFiles { f -> f.name.endsWith(DocLayoutFiles.SUFFIX) }.orEmpty()
        for (f in files.sortedBy { it.name }) {
            val name = f.name.removeSuffix(DocLayoutFiles.SUFFIX)
            val l = DocLayoutFiles.parse(f.readText()) ?: error("ilegible: $f")
            val t = extract(build(name, layout = l))
            assertTrue(name, t.visible.isNotBlank() || t.invisible.isNotBlank())
        }
    }

    @Test
    fun layoutJsonDelContrato() {
        val l = layout("compuesta")
        assertEquals(1240, l.imageWidth)
        assertTrue(l.blocks.any { it.type == com.scannerpromax.domain.BlockType.TABLE && it.cells.isNotEmpty() })
        assertTrue(DocLayoutFiles.sameAspect(l, 620, 877))
        assertTrue(!DocLayoutFiles.sameAspect(l, 877, 620))
        assertEquals(null, DocLayoutFiles.parse("{no es json"))
        assertEquals("p.jpg.layout.json", DocLayoutFiles.fileFor(File("/x/p.jpg")).name)
    }
}
