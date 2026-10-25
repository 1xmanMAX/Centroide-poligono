package com.scannerpromax.ai.docgen

import com.scannerpromax.pdf.PdfFontMetrics
import com.scannerpromax.pdf.ReFont
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * [GenDocument] -> PDF VECTORIAL con maquetación de flujo propia (pdfbox, sin Android: probado en JVM): párrafos
 * con ajuste de línea y alineación (justificado incluido), negrita / cursiva (inclinación) / subrayado / color /
 * tamaño, manuscrito con la fuente EPM Hand, títulos que no se quedan solos al pie, listas con viñetas o números por
 * nivel, tablas con celdas combinadas que no se parten por la mitad (y repiten la cabecera en la página siguiente),
 * imágenes recortadas de la página con su pie, fórmulas (recorte + LaTeX invisible buscable), saltos de página,
 * cabecera/pie y números de página. Fuentes: las mismas OFL del PDF reconstruido ([PdfFontMetrics]).
 */
class PdfDocRenderer(
    private val pdf: PDDocument,
    private val metrics: PdfFontMetrics,
    private val crops: CropSource,
    private val imageFactory: (PDDocument, CropImage) -> PDImageXObject? = ::jpegXObject,
) {
    private lateinit var gd: GenDocument
    private var pageW = 0f
    private var pageH = 0f
    private var left = 0f
    private var right = 0f
    private var top = 0f
    private var bottom = 0f
    private val contentW get() = right - left

    private var cs: PDPageContentStream? = null
    private var y = 0f // distancia desde el borde superior
    private var pageHasContent = false
    var pageCount = 0
        private set

    fun render(doc: GenDocument) {
        gd = doc
        val mm = 72f / 25.4f
        pageW = doc.page.widthMm * mm; pageH = doc.page.heightMm * mm
        left = doc.page.marginsMm.left * mm; right = pageW - doc.page.marginsMm.right * mm
        top = doc.page.marginsMm.top * mm; bottom = pageH - doc.page.marginsMm.bottom * mm
        newPage()
        var prev: DocBlock? = null
        for (b in doc.blocks) {
            when (b) {
                is DocBlock.Heading -> heading(b)
                is DocBlock.Paragraph -> paragraph(b.text, b.style, b.style.sizePt ?: BODY, spaceAfter = 6f)
                is DocBlock.ListBlock -> list(b)
                is DocBlock.Table -> table(b)
                is DocBlock.Image -> image(b.page, b.bbox, b.caption, b.widthPercent, invisibleText = null)
                is DocBlock.Formula -> formula(b)
                is DocBlock.PageBreak -> if (pageHasContent || prev is DocBlock.PageBreak) newPage()
            }
            prev = b
        }
        cs?.close(); cs = null
    }

    // ------------------------------------------------------------------ páginas

    private fun newPage() {
        cs?.close()
        val page = PDPage(PDRectangle(pageW, pageH))
        pdf.addPage(page)
        pageCount++
        val s = PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.OVERWRITE, true)
        s.setNonStrokingColor(1f, 1f, 1f)
        s.addRect(0f, 0f, pageW, pageH)
        s.fill()
        cs = s
        y = top
        pageHasContent = false
        headerFooter()
    }

    private fun ensure(h: Float) {
        if (y + h > bottom && pageHasContent) newPage()
    }

    private fun headerFooter() {
        val size = 9f
        val gray = "666666"
        val pn = pageCount.toString()
        fun draw(text: String, align: Align, baseline: Float) {
            val pieces = pieces(listOf(Run(text, false, false, false)), size, TextStyle(color = gray))
            val w = pieces.sumOf { it.width.toDouble() }.toFloat()
            var x = when (align) { Align.CENTER -> (pageW - w) / 2; Align.RIGHT -> right - w; else -> left }
            for (p in pieces) { drawPiece(p, x, baseline); x += p.width }
        }
        val hb = gd.page.marginsMm.top * 72f / 25.4f * 0.55f
        val fb = pageH - gd.page.marginsMm.bottom * 72f / 25.4f * 0.45f
        gd.header?.let { draw(it.text, it.align, hb) }
        gd.footer?.let { draw(it.text, it.align, fb) }
        when (gd.pageNumbers) {
            PageNumbers.FOOTER_CENTER -> draw(pn, Align.CENTER, if (gd.footer?.align == Align.CENTER) fb + 11f else fb)
            PageNumbers.FOOTER_RIGHT -> draw(pn, Align.RIGHT, if (gd.footer?.align == Align.RIGHT) fb + 11f else fb)
            PageNumbers.HEADER_RIGHT -> draw(pn, Align.RIGHT, if (gd.header?.align == Align.RIGHT) hb + 11f else hb)
            PageNumbers.NONE -> Unit
        }
    }

    // ------------------------------------------------------------------ texto

    /** Palabra o espacio medido con su fuente; [br] = salto de línea forzado. */
    private class Piece(
        val text: String, val font: ReFont, val size: Float, val italic: Boolean, val underline: Boolean,
        val rgb: Int, val width: Float, val space: Boolean = false, val br: Boolean = false,
    )

    private class Line(val pieces: List<Piece>, val width: Float, val height: Float, val ascent: Float, val endsParagraph: Boolean, val spaces: Int)

    private fun fontFor(bold: Boolean, hand: Boolean) = when {
        hand -> ReFont.HAND
        bold -> ReFont.BOLD
        else -> ReFont.REGULAR
    }

    private fun pieces(runs: List<Run>, size: Float, style: TextStyle): List<Piece> {
        val rgb = style.color?.toIntOrNull(16) ?: 0
        val out = ArrayList<Piece>()
        for (r in runs) {
            val f = fontFor(r.bold, style.handwriting)
            val spaceW = metrics.widthEm(f, " ").takeIf { it > 0f }?.times(size) ?: (size * 0.28f)
            val lines = r.text.split('\n')
            lines.forEachIndexed { li, line ->
                if (li > 0) out += Piece("", f, size, r.italic, r.underline, rgb, 0f, br = true)
                for (tok in Regex("\\s+|[^\\s]+").findAll(line).map { it.value }) {
                    if (tok.isBlank()) out += Piece(" ", f, size, r.italic, r.underline, rgb, spaceW, space = true)
                    else {
                        val clean = metrics.clean(f, tok)
                        if (clean.isEmpty()) continue
                        out += Piece(clean, f, size, r.italic, r.underline, rgb, metrics.widthEm(f, clean) * size)
                    }
                }
            }
        }
        return out
    }

    private fun breakLines(pieces: List<Piece>, width: Float): List<Line> {
        val lines = ArrayList<Line>()
        var cur = ArrayList<Piece>()
        var w = 0f
        fun push(endsPara: Boolean) {
            while (cur.isNotEmpty() && cur.last().space) { w -= cur.last().width; cur.removeAt(cur.lastIndex) }
            val maxSize = (cur.maxOfOrNull { it.size } ?: pieces.firstOrNull()?.size ?: BODY)
            lines += Line(cur, w, maxSize * LINE, maxSize * 0.93f, endsPara, cur.count { it.space })
            cur = ArrayList(); w = 0f
        }
        for (p in pieces) {
            if (p.br) { push(true); continue }
            if (p.space) { if (cur.isEmpty()) continue; cur += p; w += p.width; continue }
            if (w + p.width <= width || cur.isEmpty() && p.width <= width) { cur += p; w += p.width; continue }
            if (cur.isNotEmpty()) push(false)
            if (p.width <= width) { cur += p; w = p.width; continue }
            // Palabra más ancha que la línea: se parte por caracteres.
            var rest = p.text
            while (rest.isNotEmpty()) {
                var n = rest.length
                while (n > 1 && metrics.widthEm(p.font, rest.substring(0, n)) * p.size > width) n--
                val part = rest.substring(0, n)
                val pw = metrics.widthEm(p.font, part) * p.size
                cur += Piece(part, p.font, p.size, p.italic, p.underline, p.rgb, pw)
                w = pw
                rest = rest.substring(n)
                if (rest.isNotEmpty()) push(false)
            }
        }
        if (cur.isNotEmpty() || lines.isEmpty()) push(true)
        return lines
    }

    private fun drawPiece(p: Piece, x: Float, baselineFromTop: Float) {
        val s = cs ?: return
        if (p.space || p.text.isEmpty()) return
        val by = pageH - baselineFromTop
        try {
            s.beginText()
            s.setRenderingMode(RenderingMode.FILL)
            s.setNonStrokingColor(((p.rgb shr 16) and 0xFF) / 255f, ((p.rgb shr 8) and 0xFF) / 255f, (p.rgb and 0xFF) / 255f)
            s.setFont(metrics.font(p.font), p.size)
            s.setTextMatrix(Matrix(1f, 0f, if (p.italic) 0.2f else 0f, 1f, x, by))
            s.showText(p.text)
        } catch (_: IllegalArgumentException) {
        } finally {
            s.endText()
        }
    }

    /** Dibuja líneas a partir de [x] con ancho [width]; salta de página entre líneas si hace falta. */
    private fun drawLines(lines: List<Line>, x: Float, width: Float, align: Align, paginate: Boolean = true) {
        for (l in lines) {
            if (paginate) ensure(l.height)
            drawLine(l, x, width, align, y)
            y += l.height
            pageHasContent = true
        }
    }

    private fun drawLine(l: Line, x: Float, width: Float, align: Align, lineTop: Float) {
        val baseline = lineTop + l.ascent
        val extra = width - l.width
        var cx = when (align) {
            Align.CENTER -> x + extra / 2
            Align.RIGHT -> x + extra
            else -> x
        }
        val justify = align == Align.JUSTIFY && !l.endsParagraph && l.spaces > 0 && extra > 0 && extra < width * 0.35f
        val add = if (justify) extra / l.spaces else 0f
        for (p in l.pieces) {
            drawPiece(p, cx, baseline)
            if (p.underline) {
                val s = cs!!
                s.setStrokingColor(((p.rgb shr 16) and 0xFF) / 255f, ((p.rgb shr 8) and 0xFF) / 255f, (p.rgb and 0xFF) / 255f)
                s.setLineWidth(max(0.5f, p.size * 0.06f))
                val uy = pageH - (baseline + p.size * 0.12f)
                s.moveTo(cx, uy); s.lineTo(cx + p.width + if (p.space) add else 0f, uy); s.stroke()
            }
            cx += p.width + if (p.space) add else 0f
        }
    }

    private fun paragraph(text: String, style: TextStyle, size: Float, spaceAfter: Float, x: Float = left, width: Float = contentW) {
        val lines = breakLines(pieces(InlineRuns.parse(text, style), size, style), width)
        drawLines(lines, x, width, style.align)
        y += spaceAfter
    }

    private fun heading(h: DocBlock.Heading) {
        val size = h.style.sizePt ?: HEADING_SIZES[h.level.coerceIn(1, 6) - 1]
        val lines = breakLines(pieces(InlineRuns.parse(h.text, h.style), size, h.style), contentW)
        val hh = lines.sumOf { it.height.toDouble() }.toFloat()
        val before = if (pageHasContent) size * 0.6f else 0f
        // Que el título no quede solo al pie: necesita sitio para él y unas 3 líneas más.
        if (y + before + hh + BODY * LINE * 3 > bottom && pageHasContent) newPage() else y += before
        drawLines(lines, left, contentW, h.style.align, paginate = false)
        y += size * 0.35f
    }

    private fun list(b: DocBlock.ListBlock) {
        val size = b.style.sizePt ?: BODY
        val counters = IntArray(5)
        var lastLevel = -1
        for (item in b.items) {
            val lv = item.level.coerceIn(0, 4)
            if (lv < lastLevel) for (k in lv + 1..4) counters[k] = 0
            counters[lv]++
            lastLevel = lv
            val indent = 14f + 16f * lv
            val label = if (b.ordered) when (lv % 3) {
                0 -> "${counters[lv]}."
                1 -> "${('a' + (counters[lv] - 1) % 26)}."
                else -> "${roman(counters[lv])}."
            } else BULLETS[lv]
            val labelStyle = TextStyle(color = b.style.color, handwriting = b.style.handwriting)
            val labelPieces = pieces(listOf(Run(label, false, false, false)), size, labelStyle)
            val textX = left + indent + max(12f, labelPieces.sumOf { it.width.toDouble() }.toFloat() + 5f)
            val lines = breakLines(pieces(InlineRuns.parse(item.text, b.style), size, b.style), right - textX)
            ensure(lines.first().height)
            val lineTop = y
            var lx = left + indent
            for (p in labelPieces) { drawPiece(p, lx, lineTop + lines.first().ascent); lx += p.width }
            drawLines(lines, textX, right - textX, if (b.style.align == Align.JUSTIFY) Align.JUSTIFY else Align.LEFT)
            y += 2f
        }
        y += 5f
    }

    // ------------------------------------------------------------------ tablas

    private fun table(t: DocBlock.Table) {
        val rel = t.colWidths ?: TableWidths.auto(t)
        val sum = rel.sum().takeIf { it > 0f } ?: 1f
        val colW = FloatArray(t.cols) { rel[it] / sum * contentW }
        val colX = FloatArray(t.cols + 1).also { for (i in 0 until t.cols) it[i + 1] = it[i] + colW[i] }
        val size = TABLE_SIZE
        val pad = 3f
        class Laid(val cell: TableCellSpec, val lines: List<Line>, val h: Float, val align: Align)
        val laid = t.cells.map { c ->
            val style = TextStyle(bold = c.bold || c.row < t.headerRows, align = c.align ?: Align.LEFT)
            val w = colX[c.col + c.colSpan] - colX[c.col] - 2 * pad
            val lines = breakLines(pieces(InlineRuns.parse(c.text, style), size, style), max(8f, w))
            Laid(c, lines, lines.sumOf { it.height.toDouble() }.toFloat() + 2 * pad, style.align)
        }
        val rowH = FloatArray(t.rows) { size * LINE + 2 * pad }
        for (l in laid) if (l.cell.rowSpan == 1) rowH[l.cell.row] = max(rowH[l.cell.row], l.h)
        for (l in laid.filter { it.cell.rowSpan > 1 }) {
            val r0 = l.cell.row; val r1 = r0 + l.cell.rowSpan - 1
            val have = (r0..r1).sumOf { rowH[it].toDouble() }.toFloat()
            if (have < l.h) rowH[r1] += l.h - have
        }
        // Grupos de filas inseparables (unidas por celdas combinadas en vertical).
        val groups = ArrayList<IntRange>()
        var g0 = 0
        var reach = 0
        for (r in 0 until t.rows) {
            reach = max(reach, t.cells.filter { it.row == r }.maxOfOrNull { it.row + it.rowSpan - 1 } ?: r)
            if (reach <= r) { groups += g0..r; g0 = r + 1; reach = r + 1 }
        }
        if (g0 < t.rows) groups += g0 until t.rows
        val headerRange = if (t.headerRows > 0) 0 until t.headerRows else IntRange.EMPTY
        val headerH = headerRange.sumOf { rowH[it].toDouble() }.toFloat()

        fun drawRows(rows: IntRange) {
            val rowTop = FloatArray(t.rows + 1)
            var yy = y
            for (r in rows) { rowTop[r] = yy; yy += rowH[r] }
            rowTop[rows.last + 1] = yy
            val s = cs!!
            for (l in laid) {
                val c = l.cell
                if (c.row !in rows) continue
                val x0 = left + colX[c.col]; val x1 = left + colX[c.col + c.colSpan]
                val lastRow = min(c.row + c.rowSpan - 1, rows.last)
                val yTop = rowTop[c.row]; val yBot = rowTop[lastRow + 1]
                val fill = c.fill ?: if (c.row < t.headerRows) "F2F2F2" else null
                if (fill != null) {
                    val rgb = fill.toIntOrNull(16) ?: 0xF2F2F2
                    s.setNonStrokingColor(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
                    s.addRect(x0, pageH - yBot, x1 - x0, yBot - yTop)
                    s.fill()
                }
                var ly = yTop + pad
                for (line in l.lines) { drawLine(line, x0 + pad, x1 - x0 - 2 * pad, l.align, ly); ly += line.height }
                s.setStrokingColor(0f, 0f, 0f)
                s.setLineWidth(0.6f)
                when (t.borders) {
                    Borders.ALL -> { s.addRect(x0, pageH - yBot, x1 - x0, yBot - yTop); s.stroke() }
                    Borders.HORIZONTAL -> {
                        s.moveTo(x0, pageH - yTop); s.lineTo(x1, pageH - yTop)
                        s.moveTo(x0, pageH - yBot); s.lineTo(x1, pageH - yBot); s.stroke()
                    }
                    Borders.NONE -> Unit
                }
            }
            // Celdas vacías (no listadas) con borde en ALL.
            if (t.borders == Borders.ALL) {
                for (r in rows) for (c in 0 until t.cols) if (t.cellAt(r, c) == null) {
                    s.addRect(left + colX[c], pageH - rowTop[r + 1], colW[c], rowH[r]); s.stroke()
                }
            }
            y = yy
            pageHasContent = true
        }

        y += 2f
        for (grp in groups) {
            val gh = grp.sumOf { rowH[it].toDouble() }.toFloat()
            if (y + gh > bottom && pageHasContent) {
                newPage()
                // Cabecera repetida en la página nueva (si el grupo no es la propia cabecera).
                if (!headerRange.isEmpty() && grp.first >= t.headerRows && headerH < (bottom - top) / 3) drawRows(headerRange)
            }
            if (y + gh > bottom) {
                // Grupo más alto que una página: fila a fila.
                for (r in grp) { if (y + rowH[r] > bottom && pageHasContent) newPage(); drawRows(r..r) }
            } else drawRows(grp)
        }
        y += 8f
    }

    // ------------------------------------------------------------------ imágenes y fórmulas

    /** Devuelve true si dibujó la imagen. */
    private fun image(pageNo: Int, box: Box1000, caption: String?, widthPercent: Int?, invisibleText: String?): Boolean {
        val crop = crops.crop(pageNo, box)
        val xo = crop?.let { runCatching { imageFactory(pdf, it) }.getOrNull() }
        var drawn = false
        if (crop != null && xo != null && crop.widthPx > 0 && crop.heightPx > 0) {
            var w = if (widthPercent != null) contentW * widthPercent / 100f else box.width / 1000f * pageW
            w = min(w, contentW)
            var h = w * crop.heightPx / crop.widthPx
            val maxH = (bottom - top) * 0.85f
            if (h > maxH) { w *= maxH / h; h = maxH }
            ensure(h + 4f)
            y += 2f
            val x = left + (contentW - w) / 2
            cs!!.drawImage(xo, x, pageH - (y + h), w, h)
            if (invisibleText != null) {
                val s = cs!!
                val f = metrics.font(ReFont.REGULAR)
                val clean = metrics.clean(ReFont.REGULAR, invisibleText)
                if (clean.isNotEmpty()) try {
                    s.beginText(); s.setRenderingMode(RenderingMode.NEITHER); s.setFont(f, 8f)
                    s.setTextMatrix(Matrix(1f, 0f, 0f, 1f, x, pageH - (y + h / 2))); s.showText(clean)
                } catch (_: IllegalArgumentException) {
                } finally { s.endText() }
            }
            y += h + 4f
            pageHasContent = true
            drawn = true
        }
        caption?.let { paragraph(it, TextStyle(italic = true, align = Align.CENTER, color = "444444"), 9f, spaceAfter = 6f) }
        if (caption == null && drawn) y += 4f
        return drawn
    }

    private fun formula(f: DocBlock.Formula) {
        val text = f.text ?: LatexText.linear(f.latex)
        val drawn = if (f.page != null && f.bbox != null) image(f.page, f.bbox, null, null, invisibleText = f.latex) else false
        if (!drawn) paragraph(text, TextStyle(italic = true, align = Align.CENTER), BODY + 1f, spaceAfter = 6f)
    }

    companion object {
        const val BODY = 11f
        const val TABLE_SIZE = 9.5f
        const val LINE = 1.25f
        private val HEADING_SIZES = floatArrayOf(20f, 16f, 14f, 12f, 11f, 11f)
        private val BULLETS = listOf("•", "–", "•", "–", "•")

        private fun roman(n: Int): String {
            val v = intArrayOf(10, 9, 5, 4, 1); val s = arrayOf("x", "ix", "v", "iv", "i")
            var x = n.coerceIn(1, 39); val sb = StringBuilder()
            for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
            return sb.toString()
        }

        /** XObject JPEG SIN decodificar (los bytes van tal cual con DCTDecode): vale en Android y en la JVM. */
        fun jpegXObject(doc: PDDocument, c: CropImage): PDImageXObject =
            PDImageXObject(doc, ByteArrayInputStream(c.jpeg), COSName.DCT_DECODE, c.widthPx, c.heightPx, 8, PDDeviceRGB.INSTANCE)
    }
}
