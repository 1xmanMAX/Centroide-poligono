package com.scannerpromax.ocr.indra

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord

/**
 * Página de Indra → [OcrResult] de la app (bloques → líneas → palabras con cajas en px de la imagen RECIBIDA, la que
 * se guarda y sobre la que se dibuja Texto en vivo y la capa del PDF buscable).
 *
 * Indra devuelve las cajas sobre la página enderezada (girada [IndraOcr.Page.rotation] grados) y ya sin la
 * inclinación pequeña (cajas envolventes en la página girada). Aquí se devuelven a la imagen original y el ángulo de
 * la línea (grados, horario positivo como en [OcrLine.angle]) = inclinación − giro.
 */
object IndraResult {

    /** [scale] = px de la imagen recibida por px de la imagen que vio Indra (si se redujo antes). */
    fun toOcrResult(page: IndraOcr.Page, imageW: Int, imageH: Int, scale: Float = 1f): OcrResult = toOcrResult(page, imageW, imageH, scale, scale)

    /** Con escalas distintas en x e y (Indra leyó otra versión de la página, p. ej. solo geometría, de otro tamaño). */
    fun toOcrResult(page: IndraOcr.Page, imageW: Int, imageH: Int, scaleX: Float, scaleY: Float): OcrResult {
        val k = ((page.rotation / 90) % 4 + 4) % 4
        // Dimensiones de la imagen que vio Indra (antes de girar).
        val w0 = if (k % 2 == 1) page.height else page.width
        val h0 = if (k % 2 == 1) page.width else page.height
        fun map(b: FloatArray): OcrRect {
            // La página se giró rot90(-k) (horario k veces): deshacer.
            val r = when (k) {
                1 -> floatArrayOf(b[1], h0 - b[2], b[3], h0 - b[0])
                2 -> floatArrayOf(w0 - b[2], h0 - b[3], w0 - b[0], h0 - b[1])
                3 -> floatArrayOf(w0 - b[3], b[0], w0 - b[1], b[2])
                else -> b
            }
            return OcrRect(
                (r[0] * scaleX).coerceIn(0f, imageW.toFloat()), (r[1] * scaleY).coerceIn(0f, imageH.toFloat()),
                (r[2] * scaleX).coerceIn(0f, imageW.toFloat()), (r[3] * scaleY).coerceIn(0f, imageH.toFloat()),
            )
        }
        var angle = (page.skew - 90.0 * k).toFloat()
        while (angle <= -180f) angle += 360f
        while (angle > 180f) angle -= 360f
        val lines = page.lines.map { l ->
            OcrLine(
                text = l.text,
                box = map(l.box),
                // Confianza por palabra (media de las probabilidades de sus caracteres).
                words = l.words.map { OcrWord(it.text, map(it.box), it.conf) },
                angle = angle,
                // Una línea dudosa conserva su confianza (< 0,5); las demás, al menos 0,5 (no se marcan como dudosas).
                confidence = if (l.doubtful) l.conf.coerceAtMost(0.499f) else l.conf.coerceAtLeast(0.5f),
            )
        }
        val doubtful = page.lines.indices.filter { page.lines[it].doubtful && page.lines[it].text.isNotBlank() }.map { lines[it] }
        val blocks = page.blocks.mapNotNull { b ->
            val ls = b.lines.filter { !page.lines[it].doubtful }.map { lines[it] }.filter { it.text.isNotBlank() }
            if (ls.isEmpty()) null else OcrBlock(ls.joinToString("\n") { it.text }, map(b.box), ls)
        }
        val text = blocks.joinToString("\n\n") { it.text }
        return OcrResult(text = text, imageWidth = imageW, imageHeight = imageH, blocks = blocks, doubtful = doubtful)
    }
}
