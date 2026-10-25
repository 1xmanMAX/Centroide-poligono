package com.scannerpromax.ai

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.ocr.ReadingOrder
import com.scannerpromax.ocr.TextAligner

/**
 * Utilidades puras (JVM) de geometría de texto que comparten la maquetación por partes y la IA en la nube: palabras a lo
 * largo de una línea, envolventes, texto sin posiciones sobre las líneas de Indra ([alignToLines]) y control de calidad.
 * (Las partes de Spotting/tablas/figuras de PaddleOCR-VL se eliminaron con los motores locales con LLM.)
 */
object AiLayout {

    /** Reparte las palabras de [text] a lo largo del cuadrilátero (o de la caja) en proporción a sus caracteres. */
    fun wordsAlong(text: String, quad: List<FloatArray>?, box: OcrRect): List<OcrWord> {
        val words = TextAligner.tokenize(text)
        if (words.isEmpty()) return emptyList()
        val total = text.trim().length.coerceAtLeast(1).toFloat()
        val tl = quad?.get(0) ?: floatArrayOf(box.left, box.top)
        val tr = quad?.get(1) ?: floatArrayOf(box.right, box.top)
        val br = quad?.get(2) ?: floatArrayOf(box.right, box.bottom)
        val bl = quad?.get(3) ?: floatArrayOf(box.left, box.bottom)
        fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        val out = ArrayList<OcrWord>(words.size)
        var pos = 0
        val trimmed = text.trim()
        for (wd in words) {
            val start = trimmed.indexOf(wd, pos).let { if (it < 0) pos else it }
            val end = start + wd.length
            pos = end
            val t0 = start / total
            val t1 = end / total
            val pts = listOf(lerp(tl, tr, t0), lerp(tl, tr, t1), lerp(bl, br, t1), lerp(bl, br, t0))
            out.add(OcrWord(wd, OcrRect(pts.minOf { it[0] }, pts.minOf { it[1] }, pts.maxOf { it[0] }, pts.maxOf { it[1] })))
        }
        return out
    }

    fun envelope(r: List<OcrRect>): OcrRect =
        OcrRect(r.minOf { it.left }, r.minOf { it.top }, r.maxOf { it.right }, r.maxOf { it.bottom })

    // ------------------------------------------------------------------------------------------ texto sin posiciones

    /**
     * Texto reconocido SIN posiciones (p. ej. de la nube) sobre las líneas de [base] (Indra): cada línea conserva
     * su caja y recibe las palabras alineadas ([TextAligner]); las líneas que se quedan vacías se quitan. El texto
     * completo de la página es [text] tal cual (también lo que no se pudo colocar). null si no hay líneas base.
     */
    fun alignToLines(base: OcrResult?, text: String): OcrResult? {
        if (base == null || base.blocks.isEmpty() || text.isBlank()) return null
        val lines = base.blocks.flatMap { it.lines }
        val aligned = TextAligner.align(lines.map { l -> if (l.words.isNotEmpty()) l.words.map { it.text } else TextAligner.tokenize(l.text) }, text)
        var k = 0
        val blocks = base.blocks.mapNotNull { b ->
            val ls = b.lines.mapNotNull { l ->
                val ws = aligned.getOrNull(k++).orEmpty()
                if (ws.isEmpty()) null
                else {
                    val t = ws.joinToString(" ")
                    val words = if (l.words.size == ws.size) l.words.mapIndexed { i, w -> w.copy(text = ws[i], confidence = -1f) }
                    else wordsAlong(t, null, l.box)
                    l.copy(text = t, words = words, confidence = -1f)
                }
            }
            if (ls.isEmpty()) null else OcrBlock(ls.joinToString("\n") { it.text }, envelope(ls.map { it.box }), ls)
        }
        if (blocks.isEmpty()) return null
        return OcrResult(text = text.trim(), imageWidth = base.imageWidth, imageHeight = base.imageHeight, blocks = ReadingOrder.sort(blocks))
    }

    // ------------------------------------------------------------------------------------------ calidad

    /**
     * ¿La salida de la IA es basura frente al OCR local (Indra)? (casi vacía cuando Indra sí encontró texto, o con un
     * bucle que se comió la mayor parte de la página). [aiChars] / [mlkitChars] = letras y dígitos (nombre histórico:
     * [mlkitChars] = letras del OCR local).
     */
    fun isGarbage(aiChars: Int, mlkitChars: Int, looped: Boolean): Boolean = when {
        aiChars < 3 && mlkitChars >= 20 -> true
        looped && mlkitChars >= 60 && aiChars < mlkitChars * 0.3 -> true
        else -> false
    }

    fun letters(s: String): Int = s.count { it.isLetterOrDigit() }
}
