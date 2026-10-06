package com.scannerpromax.ocr

import com.scannerpromax.domain.OcrResult

/**
 * Utilidades de texto puras (probadas en JVM): unión de guiones de fin de línea, reflujo de párrafos y textos por
 * página para las exportaciones (.txt, .docx, páginas de texto del PDF).
 */
object OcrText {

    private val HYPHENS = charArrayOf('-', '­', '‐', '‑')
    private val BULLET = Regex("^\\s*([•·▪◦*\\-–—]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)])\\s+")

    /**
     * Une una línea con la siguiente cuando la primera termina en guion de corte de palabra ("infor-" + "mación")
     * y la siguiente empieza en minúscula. Devuelve null si no hay que unir con guion.
     */
    fun joinHyphen(current: String, next: String): String? {
        val a = current.trimEnd()
        val b = next.trimStart()
        if (a.length < 2 || b.isEmpty()) return null
        val last = a.last()
        if (last !in HYPHENS) return null
        val beforeHyphen = a[a.length - 2]
        if (!beforeHyphen.isLetter()) return null
        if (!b.first().isLowerCase()) return null
        return a.substring(0, a.length - 1) + b
    }

    /** Une guiones de fin de línea conservando los saltos de línea restantes. */
    fun joinHyphenatedLines(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            var cur = lines[i]
            while (i + 1 < lines.size) {
                val joined = joinHyphen(cur, lines[i + 1]) ?: break
                // El resto de la línea siguiente queda unido a la palabra completada.
                cur = joined
                i++
            }
            out += cur
            i++
        }
        return out
    }

    /**
     * Convierte texto con saltos de línea "de escaneo" en párrafos legibles:
     *  - párrafos separados por líneas en blanco;
     *  - dentro de un párrafo, las líneas largas se unen con espacio (y los cortes con guion sin espacio);
     *  - una línea claramente más corta que las del párrafo, que termina en ':' o una viñeta/numeración
     *    al inicio de la siguiente fuerza un salto (listas, direcciones, recibos).
     */
    fun reflow(text: String): List<String> {
        val paragraphs = ArrayList<String>()
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (chunk in raw) {
            val lines = chunk.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val maxLen = lines.maxOf { it.length }
            val sb = StringBuilder(lines[0])
            for (i in 1 until lines.size) {
                val prev = lines[i - 1]
                val next = lines[i]
                val hyphenJoined = joinHyphen(sb.toString(), next)
                val breakHere = prev.length < maxLen * SHORT_LINE_RATIO ||
                    prev.endsWith(":") ||
                    BULLET.containsMatchIn(next) ||
                    maxLen < MIN_REFLOW_LENGTH
                when {
                    hyphenJoined != null && !breakHere -> { sb.setLength(0); sb.append(hyphenJoined) }
                    breakHere -> { paragraphs += sb.toString(); sb.setLength(0); sb.append(next) }
                    else -> sb.append(' ').append(next)
                }
            }
            paragraphs += sb.toString()
        }
        return paragraphs
    }

    /** Párrafos de una página OCR: por bloques (texto reconocido) o reflujo del texto corregido. */
    fun paragraphs(ocr: OcrResult?): List<String> {
        if (ocr == null) return emptyList()
        ocr.editedText?.let { return reflow(it) }
        if (ocr.blocks.isEmpty()) return reflow(ocr.text)
        return ocr.blocks.flatMap { b -> reflow(b.lines.joinToString("\n") { it.text }) }
    }

    /** Texto plano legible de una página (para .txt): guiones de fin de línea unidos, saltos conservados. */
    fun plainText(ocr: OcrResult?): String {
        if (ocr == null) return ""
        val source = ocr.displayText
        return source.replace("\r\n", "\n").split("\n\n").joinToString("\n\n") { para ->
            joinHyphenatedLines(para.split('\n')).joinToString("\n")
        }.trim()
    }

    /** Texto de todo el documento con separadores de página (para copiar / .txt). */
    fun documentText(pages: List<OcrResult?>, pageHeader: (Int) -> String = { "— Página $it —" }): String {
        val sb = StringBuilder()
        pages.forEachIndexed { i, r ->
            if (pages.size > 1) sb.append(pageHeader(i + 1)).append('\n')
            sb.append(plainText(r).ifEmpty { "(sin texto)" })
            if (i < pages.size - 1) sb.append("\n\n")
        }
        return sb.toString()
    }

    private const val SHORT_LINE_RATIO = 0.6f
    private const val MIN_REFLOW_LENGTH = 25
}
