package com.scannerpromax.pdf

/**
 * Maquetación pura (probada en JVM) de las páginas de "Texto reconocido": título, párrafos con ajuste de línea por
 * ancho real de la fuente y salto de página automático (con título "(cont.)" en las continuaciones).
 */
object TextPageLayout {

    enum class Kind { TITLE, BODY, NOTE }

    /** Una línea ya colocada: [y] es la línea base en puntos PDF (origen abajo-izquierda). */
    data class Line(val text: String, val x: Float, val y: Float, val size: Float, val kind: Kind)

    data class Style(
        val pageWidth: Float,
        val pageHeight: Float,
        val margin: Float = 56f,
        val titleSize: Float = 15f,
        val bodySize: Float = 11f,
        val leading: Float = 1.42f,
        val paragraphGap: Float = 0.6f,   // en líneas
    )

    /**
     * @param title título de la primera página (p. ej. "Texto reconocido – Página 3").
     * @param paragraphs párrafos (sin saltos de línea internos; si los hay se respetan).
     * @param emptyNote texto a mostrar si no hay párrafos.
     * @param width ancho en puntos de [text] a tamaño [size].
     * @return páginas, cada una con sus líneas.
     */
    fun layout(
        title: String,
        paragraphs: List<String>,
        style: Style,
        emptyNote: String,
        width: (text: String, size: Float) -> Float,
    ): List<List<Line>> {
        val pages = ArrayList<List<Line>>()
        val maxW = (style.pageWidth - 2 * style.margin).coerceAtLeast(40f)
        val bodyStep = style.bodySize * style.leading
        val bottomLimit = style.margin
        var current = ArrayList<Line>()
        var y = 0f

        fun newPage(continuation: Boolean) {
            if (current.isNotEmpty()) pages += current
            current = ArrayList()
            y = style.pageHeight - style.margin - style.titleSize
            val t = if (continuation) "$title (cont.)" else title
            // El título también se ajusta si es muy largo.
            for (part in wrap(t, maxW, style.titleSize, width)) {
                current += Line(part, style.margin, y, style.titleSize, Kind.TITLE)
                y -= style.titleSize * 1.3f
            }
            y -= style.titleSize * 0.7f
        }

        newPage(false)
        val paras = paragraphs.flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) {
            current += Line(emptyNote, style.margin, y, style.bodySize, Kind.NOTE)
            pages += current
            return pages
        }
        for ((pi, p) in paras.withIndex()) {
            for (line in wrap(p, maxW, style.bodySize, width)) {
                if (y < bottomLimit) newPage(true)
                current += Line(line, style.margin, y, style.bodySize, Kind.BODY)
                y -= bodyStep
            }
            if (pi < paras.size - 1) y -= bodyStep * style.paragraphGap
        }
        pages += current
        return pages
    }

    /** Ajuste de línea por palabras; las palabras más anchas que la línea se parten por caracteres. */
    fun wrap(text: String, maxWidth: Float, size: Float, width: (String, Float) -> Float): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        for (word in words) {
            val candidate = if (sb.isEmpty()) word else "$sb $word"
            if (width(candidate, size) <= maxWidth) {
                sb.setLength(0); sb.append(candidate)
                continue
            }
            if (sb.isNotEmpty()) {
                lines += sb.toString()
                sb.setLength(0)
            }
            if (width(word, size) <= maxWidth) {
                sb.append(word)
            } else {
                // Palabra larguísima (URL, código): se corta por caracteres.
                var chunk = StringBuilder()
                for (ch in word) {
                    if (chunk.isNotEmpty() && width(chunk.toString() + ch, size) > maxWidth) {
                        lines += chunk.toString()
                        chunk = StringBuilder()
                    }
                    chunk.append(ch)
                }
                sb.append(chunk)
            }
        }
        if (sb.isNotEmpty()) lines += sb.toString()
        return lines
    }
}
