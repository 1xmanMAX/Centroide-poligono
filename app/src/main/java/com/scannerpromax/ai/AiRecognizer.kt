package com.scannerpromax.ai

import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord

/**
 * Tipos comunes del reconocimiento de una página (progreso y resultado OCR + maquetación) que usan la cola
 * ([AiRecognitionQueue]) y la IA en la nube (ai/cloud). Los lectores locales con LLM (PaddleOCR-VL, Qwen) se eliminaron:
 * el texto lo pone siempre Indra.
 */
object AiRecognizer {

    data class Progress(val fraction: Float, val label: String)

    class PageResult(val ocr: OcrResult, val layout: DocLayout, val warning: String? = null)

    /** Reescala un OCR a otras dimensiones de la misma imagen. */
    fun scaleOcr(o: OcrResult, w: Int, h: Int): OcrResult {
        if (o.imageWidth == w && o.imageHeight == h || o.imageWidth <= 0 || o.imageHeight <= 0) return o
        val sx = w.toFloat() / o.imageWidth; val sy = h.toFloat() / o.imageHeight
        fun r(b: OcrRect) = OcrRect(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)
        return o.copy(
            imageWidth = w, imageHeight = h,
            blocks = o.blocks.map { b ->
                OcrBlock(b.text, r(b.box), b.lines.map { l -> OcrLine(l.text, r(l.box), l.words.map { OcrWord(it.text, r(it.box), it.confidence) }, l.angle, l.confidence) })
            },
        )
    }
}
