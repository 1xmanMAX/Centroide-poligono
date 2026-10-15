package com.scannerpromax.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix

/**
 * Métricas reales de las fuentes PDF (TTF incrustados como subconjunto) para [ReconstructionLayout].
 * Sin Android: funciona también en los tests JVM.
 */
class PdfFontMetrics(private val fonts: Map<ReFont, PDFont>) : ReFontMetrics {
    private val sanitizers = HashMap<ReFont, TextSanitizer>()
    private val cap = HashMap<ReFont, Float>()
    private val xh = HashMap<ReFont, Float>()
    private val desc = HashMap<ReFont, Float>()

    fun font(f: ReFont): PDFont = fonts[f] ?: fonts.getValue(ReFont.REGULAR)

    init {
        for (f in ReFont.entries) {
            val d = font(f).fontDescriptor
            cap[f] = d?.capHeight?.takeIf { it > 0f }?.div(1000f) ?: 0.70f
            xh[f] = d?.xHeight?.takeIf { it > 0f }?.div(1000f) ?: 0.52f
            desc[f] = d?.descent?.takeIf { it < 0f }?.let { -it / 1000f }?.coerceAtMost(0.35f) ?: 0.21f
        }
    }

    override fun widthEm(font: ReFont, text: String): Float = try {
        font(font).getStringWidth(text) / 1000f
    } catch (_: Exception) {
        0f
    }

    override fun capHeight(font: ReFont) = cap.getValue(font)
    override fun xHeight(font: ReFont) = xh.getValue(font)
    override fun descent(font: ReFont) = desc.getValue(font)

    override fun clean(font: ReFont, text: String): String =
        sanitizers.getOrPut(font) { TextSanitizer(font(font)) }.clean(text)
}

/**
 * Dibuja un [RePlan] en una página nueva de [doc] con pdfbox (sin Android). Las imágenes las da [image] (recorte ya
 * convertido en XObject; null = se omite).
 */
object ReconstructionRenderer {

    fun render(doc: PDDocument, plan: RePlan, metrics: PdfFontMetrics, image: (ReOp.Image) -> PDImageXObject?): PDPage {
        val page = PDPage(PDRectangle(plan.pageW, plan.pageH))
        doc.addPage(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
            // Fondo blanco explícito (algunos visores pintan las páginas sin fondo con el tema oscuro).
            cs.setNonStrokingColor(1f, 1f, 1f)
            cs.addRect(0f, 0f, plan.pageW, plan.pageH)
            cs.fill()
            for (op in plan.ops) {
                when (op) {
                    is ReOp.Image -> image(op)?.let { cs.drawImage(it, op.x, op.y, op.w, op.h) }
                    is ReOp.Lines -> {
                        if (op.segments.isEmpty()) continue
                        cs.setStrokingColor(rgbF(op.rgb, 16), rgbF(op.rgb, 8), rgbF(op.rgb, 0))
                        cs.setLineWidth(op.width)
                        cs.setLineCapStyle(2) // extremo cuadrado: esquinas de la rejilla cerradas
                        for (s in op.segments) {
                            cs.moveTo(s.x0, s.y0)
                            cs.lineTo(s.x1, s.y1)
                        }
                        cs.stroke()
                    }
                    is ReOp.Text -> drawText(cs, op, metrics)
                    is ReOp.Fill -> {
                        cs.setNonStrokingColor(rgbF(op.rgb, 16), rgbF(op.rgb, 8), rgbF(op.rgb, 0))
                        cs.addRect(op.x, op.y, op.w, op.h)
                        cs.fill()
                    }
                }
            }
        }
        return page
    }

    private fun drawText(cs: PDPageContentStream, t: ReOp.Text, metrics: PdfFontMetrics) {
        if (t.text.isEmpty() || t.size <= 0f) return
        try {
            cs.beginText()
            cs.setRenderingMode(if (t.visible) RenderingMode.FILL else RenderingMode.NEITHER)
            cs.setNonStrokingColor(rgbF(t.rgb, 16), rgbF(t.rgb, 8), rgbF(t.rgb, 0))
            cs.setFont(metrics.font(t.font), t.size)
            cs.setHorizontalScaling(t.hScale)
            cs.setCharacterSpacing(t.charSpacing)
            cs.setTextMatrix(Matrix(t.cos, t.sin, -t.sin, t.cos, t.x, t.y))
            cs.showText(t.text)
        } catch (_: IllegalArgumentException) {
            // Carácter no codificable que escapó al filtro: se omite la línea.
        } finally {
            cs.endText()
        }
    }

    private fun rgbF(rgb: Int, shift: Int) = ((rgb shr shift) and 0xFF) / 255f
}
