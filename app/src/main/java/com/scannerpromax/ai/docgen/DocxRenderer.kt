package com.scannerpromax.ai.docgen

import com.scannerpromax.export.DocxWriter
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Recorte JPEG de una página escaneada (lo prepara la app Android; en tests, bytes cualesquiera). */
class CropImage(val jpeg: ByteArray, val widthPx: Int, val heightPx: Int)

/** Da el recorte [box] (0..1000) de la página [page] (1..N), o null si no se puede. */
fun interface CropSource {
    fun crop(page: Int, box: Box1000): CropImage?
}

/**
 * [GenDocument] -> Word (.docx) con estilos de verdad (Título 1–6, Normal, Pie de figura, Párrafo de lista,
 * Cuadrícula de tabla), listas con numeración real (viñetas y números que reinician en cada lista), tablas con
 * celdas combinadas (gridSpan / vMerge), fila de encabezado repetida, sombreado, imágenes recortadas de la página,
 * fórmulas (recorte + LaTeX como texto alternativo), saltos de página, cabecera/pie con número de página y el tamaño
 * de papel y márgenes elegidos. Lógica pura (JVM); el paquete lo escribe [DocxWriter.writePackage].
 */
object DocxRenderer {

    private const val TWIPS_PER_MM = 56.6929f
    private const val EMU_PER_MM = 36_000L

    fun write(doc: GenDocument, crops: CropSource, out: OutputStream) {
        DocxWriter.writePackage(build(doc, crops), out)
    }

    fun build(doc: GenDocument, crops: CropSource): DocxWriter.Package {
        val ctx = Ctx(doc, crops)
        val body = StringBuilder(16_384)
        body.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        body.append("<w:document $NS><w:body>")
        for (b in doc.blocks) ctx.block(body, b)
        // Word exige un párrafo detrás de una tabla final.
        if (doc.blocks.lastOrNull() is DocBlock.Table || doc.blocks.isEmpty()) body.append("<w:p/>")
        body.append(ctx.sectPr())
        body.append("</w:body></w:document>")
        return DocxWriter.Package(
            documentXml = body.toString(),
            stylesXml = STYLES,
            numberingXml = ctx.numbering(),
            parts = ctx.parts,
            media = ctx.media,
            rels = ctx.rels,
            title = doc.title,
        )
    }

    private class Ctx(val doc: GenDocument, val crops: CropSource) {
        val media = ArrayList<DocxWriter.Media>()
        val rels = ArrayList<DocxWriter.Rel>()
        val parts = ArrayList<DocxWriter.Part>()
        /** numId de cada lista numerada (las de viñetas comparten numId 1). */
        var orderedLists = 0
        var drawingId = 1
        val page = doc.page
        val contentWidthMm = page.contentWidthMm
        val contentHeightMm = page.contentHeightMm

        fun block(sb: StringBuilder, b: DocBlock) {
            when (b) {
                is DocBlock.Heading -> paragraph(sb, b.text, b.style, "Heading${b.level.coerceIn(1, 6)}", defaultSize = null)
                is DocBlock.Paragraph -> paragraph(sb, b.text, b.style, null, defaultSize = null)
                is DocBlock.ListBlock -> list(sb, b)
                is DocBlock.Table -> table(sb, b)
                is DocBlock.Image -> image(sb, b.page, b.bbox, b.caption, b.widthPercent, alt = b.caption ?: "Imagen")
                is DocBlock.Formula -> formula(sb, b)
                is DocBlock.PageBreak -> sb.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            }
        }

        fun paragraph(sb: StringBuilder, text: String, style: TextStyle, pStyle: String?, defaultSize: Float?, numPr: String? = null) {
            sb.append("<w:p><w:pPr>")
            if (pStyle != null) sb.append("""<w:pStyle w:val="$pStyle"/>""")
            if (numPr != null) sb.append(numPr)
            sb.append("""<w:jc w:val="${jc(style.align)}"/>""")
            sb.append("</w:pPr>")
            runs(sb, text, style, defaultSize)
            sb.append("</w:p>")
        }

        fun runs(sb: StringBuilder, text: String, style: TextStyle, defaultSize: Float?) {
            for (run in InlineRuns.parse(text, style)) {
                sb.append("<w:r><w:rPr>")
                if (style.handwriting) sb.append("""<w:rFonts w:ascii="Patrick Hand" w:hAnsi="Patrick Hand" w:cs="Patrick Hand"/>""")
                if (run.bold) sb.append("<w:b/><w:bCs/>")
                if (run.italic) sb.append("<w:i/><w:iCs/>")
                if (run.underline) sb.append("""<w:u w:val="single"/>""")
                style.color?.let { sb.append("""<w:color w:val="$it"/>""") }
                (style.sizePt ?: defaultSize)?.let { val hp = (it * 2).roundToInt(); sb.append("""<w:sz w:val="$hp"/><w:szCs w:val="$hp"/>""") }
                sb.append("</w:rPr>")
                run.text.split('\n').forEachIndexed { i, line ->
                    if (i > 0) sb.append("<w:br/>")
                    sb.append("""<w:t xml:space="preserve">""").append(DocxWriter.escape(line)).append("</w:t>")
                }
                sb.append("</w:r>")
            }
        }

        fun list(sb: StringBuilder, b: DocBlock.ListBlock) {
            val numId = if (b.ordered) 2 + orderedLists++ else 1
            for (item in b.items) {
                val lvl = item.level.coerceIn(0, 4)
                paragraph(
                    sb, item.text, b.style, "ListParagraph", null,
                    numPr = """<w:numPr><w:ilvl w:val="$lvl"/><w:numId w:val="$numId"/></w:numPr>""",
                )
            }
        }

        fun table(sb: StringBuilder, t: DocBlock.Table) {
            val totalTw = (contentWidthMm * TWIPS_PER_MM).roundToInt()
            val widths = columnWidths(t, totalTw)
            sb.append("<w:tbl><w:tblPr>")
            sb.append("""<w:tblW w:w="$totalTw" w:type="dxa"/><w:tblLayout w:type="fixed"/>""")
            sb.append("<w:tblBorders>")
            val line = """w:val="single" w:sz="4" w:space="0" w:color="000000""""
            val none = """w:val="nil""""
            fun border(name: String, on: Boolean) = sb.append("<w:$name ${if (on) line else none}/>")
            val all = t.borders == Borders.ALL; val hor = t.borders != Borders.NONE
            border("top", hor); border("left", all); border("bottom", hor); border("right", all)
            border("insideH", hor); border("insideV", all)
            sb.append("</w:tblBorders>")
            sb.append("""<w:tblCellMar><w:left w:w="85" w:type="dxa"/><w:right w:w="85" w:type="dxa"/></w:tblCellMar>""")
            sb.append("</w:tblPr><w:tblGrid>")
            widths.forEach { sb.append("""<w:gridCol w:w="$it"/>""") }
            sb.append("</w:tblGrid>")
            val start = HashMap<Long, TableCellSpec>()
            for (c in t.cells) start[key(c.row, c.col)] = c
            for (r in 0 until t.rows) {
                sb.append("<w:tr>")
                if (r < t.headerRows) sb.append("<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>") else sb.append("<w:trPr><w:cantSplit/></w:trPr>")
                var c = 0
                while (c < t.cols) {
                    val cell = start[key(r, c)]
                    if (cell != null) {
                        val w = (c until c + cell.colSpan).sumOf { widths[it] }
                        sb.append("<w:tc><w:tcPr>")
                        sb.append("""<w:tcW w:w="$w" w:type="dxa"/>""")
                        if (cell.colSpan > 1) sb.append("""<w:gridSpan w:val="${cell.colSpan}"/>""")
                        if (cell.rowSpan > 1) sb.append("""<w:vMerge w:val="restart"/>""")
                        val fill = cell.fill ?: if (r < t.headerRows) "F2F2F2" else null
                        fill?.let { sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="$it"/>""") }
                        sb.append("</w:tcPr>")
                        val style = TextStyle(bold = cell.bold || r < t.headerRows, align = cell.align ?: Align.LEFT)
                        val lines = cell.text.split("\n\n")
                        lines.forEach { para -> paragraph(sb, para, style, "TableText", null) }
                        sb.append("</w:tc>")
                        c += cell.colSpan
                        continue
                    }
                    val cover = t.cells.firstOrNull { r in it.row + 1 until it.row + it.rowSpan && c in it.col until it.col + it.colSpan }
                    if (cover != null && cover.col == c) {
                        val w = (c until c + cover.colSpan).sumOf { widths[it] }
                        sb.append("<w:tc><w:tcPr>")
                        sb.append("""<w:tcW w:w="$w" w:type="dxa"/>""")
                        if (cover.colSpan > 1) sb.append("""<w:gridSpan w:val="${cover.colSpan}"/>""")
                        sb.append("<w:vMerge/></w:tcPr><w:p/></w:tc>")
                        c += cover.colSpan
                        continue
                    }
                    sb.append("""<w:tc><w:tcPr><w:tcW w:w="${widths[c]}" w:type="dxa"/></w:tcPr><w:p/></w:tc>""")
                    c++
                }
                sb.append("</w:tr>")
            }
            sb.append("</w:tbl>")
            // Separación tras la tabla (y evita dos tablas pegadas que Word fundiría).
            sb.append("""<w:p><w:pPr><w:spacing w:after="60"/></w:pPr></w:p>""")
        }

        fun columnWidths(t: DocBlock.Table, totalTw: Int): IntArray {
            val rel = t.colWidths ?: TableWidths.auto(t)
            val sum = rel.sum().takeIf { it > 0f } ?: 1f
            val w = IntArray(t.cols) { (rel[it] / sum * totalTw).roundToInt().coerceAtLeast(200) }
            return w
        }

        fun image(sb: StringBuilder, pageNo: Int, box: Box1000, caption: String?, widthPercent: Int?, alt: String) {
            val crop = crops.crop(pageNo, box)
            if (crop == null || crop.widthPx <= 0 || crop.heightPx <= 0) {
                caption?.let { paragraph(sb, it, TextStyle(italic = true, align = Align.CENTER), "Caption", null) }
                return
            }
            val idx = media.size + 1
            val name = "image$idx.jpeg"
            media += DocxWriter.Media(name, crop.jpeg)
            val rid = "rIdImg$idx"
            rels += DocxWriter.Rel(rid, "image", "media/$name")
            // Ancho: el que ocupaba en la página (ancho del papel) o el % pedido, sin pasar del ancho útil.
            var wMm = if (widthPercent != null) contentWidthMm * widthPercent / 100f else box.width / 1000f * page.widthMm
            wMm = min(wMm, contentWidthMm)
            var hMm = wMm * crop.heightPx / crop.widthPx
            val maxH = contentHeightMm * 0.85f
            if (hMm > maxH) { wMm *= maxH / hMm; hMm = maxH }
            val cx = (wMm * EMU_PER_MM).roundToLong(); val cy = (hMm * EMU_PER_MM).roundToLong()
            val id = drawingId++
            sb.append("""<w:p><w:pPr><w:pStyle w:val="Figure"/><w:jc w:val="center"/></w:pPr><w:r><w:drawing>""")
            sb.append("""<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/>""")
            sb.append("""<wp:docPr id="$id" name="Imagen $id" descr="${DocxWriter.escape(alt)}"/>""")
            sb.append("""<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>""")
            sb.append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>""")
            sb.append("""<pic:nvPicPr><pic:cNvPr id="$id" name="$name"/><pic:cNvPicPr/></pic:nvPicPr>""")
            sb.append("""<pic:blipFill><a:blip r:embed="$rid"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""")
            sb.append("""<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""")
            sb.append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
            caption?.let { paragraph(sb, it, TextStyle(italic = true, align = Align.CENTER), "Caption", null) }
        }

        fun formula(sb: StringBuilder, f: DocBlock.Formula) {
            val text = f.text ?: LatexText.linear(f.latex)
            val before = media.size
            if (f.page != null && f.bbox != null) image(sb, f.page, f.bbox, null, null, alt = "LaTeX: " + f.latex)
            if (media.size == before) {
                // Sin recorte: la fórmula como texto (Cambria Math, centrada).
                sb.append("""<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Cambria Math" w:hAnsi="Cambria Math"/><w:i/></w:rPr>""")
                sb.append("""<w:t xml:space="preserve">""").append(DocxWriter.escape(text)).append("</w:t></w:r></w:p>")
            }
        }

        fun numbering(): String {
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            sb.append("""<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
            val bullets = listOf("•", "◦", "▪", "•", "◦")
            sb.append("""<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>""")
            for (l in 0..4) {
                sb.append("""<w:lvl w:ilvl="$l"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="${bullets[l]}"/><w:lvlJc w:val="left"/>""")
                sb.append("""<w:pPr><w:ind w:left="${360 + 360 * (l + 1)}" w:hanging="360"/></w:pPr></w:lvl>""")
            }
            sb.append("</w:abstractNum>")
            val fmts = listOf("decimal", "lowerLetter", "lowerRoman", "decimal", "lowerLetter")
            sb.append("""<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>""")
            for (l in 0..4) {
                sb.append("""<w:lvl w:ilvl="$l"><w:start w:val="1"/><w:numFmt w:val="${fmts[l]}"/><w:lvlText w:val="%${l + 1}."/><w:lvlJc w:val="left"/>""")
                sb.append("""<w:pPr><w:ind w:left="${360 + 360 * (l + 1)}" w:hanging="360"/></w:pPr></w:lvl>""")
            }
            sb.append("</w:abstractNum>")
            sb.append("""<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>""")
            for (i in 0 until orderedLists) {
                sb.append("""<w:num w:numId="${2 + i}"><w:abstractNumId w:val="1"/>""")
                for (l in 0..4) sb.append("""<w:lvlOverride w:ilvl="$l"><w:startOverride w:val="1"/></w:lvlOverride>""")
                sb.append("</w:num>")
            }
            sb.append("</w:numbering>")
            return sb.toString()
        }

        fun headerFooterXml(root: String, hf: HeaderFooter?, pageNumber: Align?): String {
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            sb.append("<w:$root $NS>")
            val pn = """<w:fldSimple w:instr=" PAGE "><w:r><w:t>1</w:t></w:r></w:fldSimple>"""
            if (hf != null) {
                sb.append("""<w:p><w:pPr><w:pStyle w:val="${if (root == "hdr") "Header" else "Footer"}"/><w:jc w:val="${jc(hf.align)}"/></w:pPr>""")
                runs(sb, hf.text, TextStyle(), 9f)
                if (pageNumber != null && pageNumber == hf.align) sb.append("""<w:r><w:t xml:space="preserve">  </w:t></w:r>""").append(pn)
                sb.append("</w:p>")
            }
            if (pageNumber != null && (hf == null || pageNumber != hf.align)) {
                sb.append("""<w:p><w:pPr><w:jc w:val="${jc(pageNumber)}"/></w:pPr>$pn</w:p>""")
            }
            if (hf == null && pageNumber == null) sb.append("<w:p/>")
            sb.append("</w:$root>")
            return sb.toString()
        }

        fun sectPr(): String {
            val headerPn = if (doc.pageNumbers == PageNumbers.HEADER_RIGHT) Align.RIGHT else null
            val footerPn = when (doc.pageNumbers) { PageNumbers.FOOTER_CENTER -> Align.CENTER; PageNumbers.FOOTER_RIGHT -> Align.RIGHT; else -> null }
            val sb = StringBuilder("<w:sectPr>")
            if (doc.header != null || headerPn != null) {
                parts += DocxWriter.Part("header1.xml", headerFooterXml("hdr", doc.header, headerPn), DocxWriter.CT_HEADER)
                rels += DocxWriter.Rel("rIdHeader1", "header", "header1.xml")
                sb.append("""<w:headerReference w:type="default" r:id="rIdHeader1"/>""")
            }
            if (doc.footer != null || footerPn != null) {
                parts += DocxWriter.Part("footer1.xml", headerFooterXml("ftr", doc.footer, footerPn), DocxWriter.CT_FOOTER)
                rels += DocxWriter.Rel("rIdFooter1", "footer", "footer1.xml")
                sb.append("""<w:footerReference w:type="default" r:id="rIdFooter1"/>""")
            }
            val w = (page.widthMm * TWIPS_PER_MM).roundToInt(); val h = (page.heightMm * TWIPS_PER_MM).roundToInt()
            sb.append("""<w:pgSz w:w="$w" w:h="$h"${if (page.landscape) " w:orient=\"landscape\"" else ""}/>""")
            val m = page.marginsMm
            fun tw(mm: Float) = (mm * TWIPS_PER_MM).roundToInt()
            sb.append("""<w:pgMar w:top="${tw(m.top)}" w:right="${tw(m.right)}" w:bottom="${tw(m.bottom)}" w:left="${tw(m.left)}" """)
            sb.append("""w:header="${tw(max(5f, m.top / 2))}" w:footer="${tw(max(5f, m.bottom / 2))}" w:gutter="0"/>""")
            sb.append("</w:sectPr>")
            return sb.toString()
        }

        private fun key(r: Int, c: Int) = r.toLong() shl 32 or c.toLong()
    }

    private fun jc(a: Align) = when (a) { Align.LEFT -> "left"; Align.CENTER -> "center"; Align.RIGHT -> "right"; Align.JUSTIFY -> "both" }

    private const val NS = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
        """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
        """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture""""

    private fun headingStyle(level: Int, sizePt: Int, before: Int) =
        """<w:style w:type="paragraph" w:styleId="Heading$level"><w:name w:val="heading $level"/><w:basedOn w:val="Normal"/>""" +
            """<w:next w:val="Normal"/><w:uiPriority w:val="9"/><w:qFormat/><w:pPr><w:keepNext/><w:keepLines/>""" +
            """<w:spacing w:before="$before" w:after="80"/><w:outlineLvl w:val="${level - 1}"/></w:pPr>""" +
            """<w:rPr><w:b/><w:bCs/><w:sz w:val="${sizePt * 2}"/><w:szCs w:val="${sizePt * 2}"/></w:rPr></w:style>"""

    private val STYLES: String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/>""" +
            """<w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="es-ES"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            headingStyle(1, 20, 240) + headingStyle(2, 16, 200) + headingStyle(3, 14, 180) +
            headingStyle(4, 12, 160) + headingStyle(5, 11, 140) + headingStyle(6, 11, 120) +
            """<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/>""" +
            """<w:pPr><w:spacing w:after="40"/><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="caption"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/>""" +
            """<w:pPr><w:spacing w:after="200"/></w:pPr><w:rPr><w:i/><w:iCs/><w:color w:val="444444"/><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Figure"><w:name w:val="Figure"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="60" w:after="60"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="TableText"><w:name w:val="Table Text"/><w:basedOn w:val="Normal"/>""" +
            """<w:pPr><w:spacing w:before="20" w:after="20" w:line="240" w:lineRule="auto"/></w:pPr><w:rPr><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Header"><w:name w:val="header"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0"/></w:pPr></w:style>""" +
            "</w:styles>"
}

/** Anchos automáticos de columnas: según el texto más largo de cada columna (acotado), como hace Word. Pura. */
object TableWidths {
    fun auto(t: DocBlock.Table): List<Float> {
        val w = FloatArray(t.cols) { 1f }
        for (c in t.cells) {
            if (c.colSpan != 1) continue
            val longestLine = InlineRuns.strip(c.text).split('\n').maxOfOrNull { it.length } ?: 0
            val longestWord = InlineRuns.strip(c.text).split(Regex("\\s+")).maxOfOrNull { it.length } ?: 0
            w[c.col] = max(w[c.col], max(longestWord.toFloat(), min(longestLine.toFloat(), 40f)))
        }
        val median = w.sorted()[w.size / 2].coerceAtLeast(1f)
        return w.map { it.coerceIn(median * 0.45f, median * 3.5f) }
    }
}
