package com.scannerpromax.ai.docgen

/**
 * MODELO INTERMEDIO del documento que la IA construye con sus herramientas (o en el modo rápido, con un JSON por
 * página) y que se convierte a Word ([DocxRenderer]) y a PDF vectorial ([PdfDocRenderer]). Lógica pura (JVM).
 *
 * Coordenadas de las imágenes y fórmulas: caja 0..1000 relativa a la página escaneada (origen arriba-izquierda),
 * igual que en el resto de la IA en la nube.
 */
data class GenDocument(
    val title: String? = null,
    val page: PageSetup = PageSetup(),
    val header: HeaderFooter? = null,
    val footer: HeaderFooter? = null,
    val pageNumbers: PageNumbers = PageNumbers.NONE,
    val blocks: List<DocBlock> = emptyList(),
) {
    /** Texto llano del documento (para pruebas y para el resumen final). */
    val plainText: String get() = blocks.joinToString("\n") { it.plainText() }.trim()
}

enum class Paper(val widthMm: Float, val heightMm: Float, val label: String) {
    A4(210f, 297f, "A4"),
    LETTER(215.9f, 279.4f, "Carta"),
    LEGAL(215.9f, 355.6f, "Oficio"),
    A5(148f, 210f, "A5"),
}

data class Margins(val top: Float = 20f, val right: Float = 20f, val bottom: Float = 20f, val left: Float = 20f)

data class PageSetup(val paper: Paper = Paper.A4, val landscape: Boolean = false, val marginsMm: Margins = Margins()) {
    val widthMm: Float get() = if (landscape) paper.heightMm else paper.widthMm
    val heightMm: Float get() = if (landscape) paper.widthMm else paper.heightMm
    val contentWidthMm: Float get() = (widthMm - marginsMm.left - marginsMm.right).coerceAtLeast(20f)
    val contentHeightMm: Float get() = (heightMm - marginsMm.top - marginsMm.bottom).coerceAtLeast(20f)
}

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

enum class PageNumbers { NONE, FOOTER_CENTER, FOOTER_RIGHT, HEADER_RIGHT }

enum class Borders { ALL, NONE, HORIZONTAL }

data class HeaderFooter(val text: String, val align: Align = Align.CENTER)

/** Estilo de un bloque de texto. [sizePt] null = el tamaño por defecto del tipo de bloque. [color] = "RRGGBB". */
data class TextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val align: Align = Align.LEFT,
    val sizePt: Float? = null,
    val color: String? = null,
    /** Texto manuscrito en el original: se escribe con la fuente manuscrita. */
    val handwriting: Boolean = false,
)

data class ListItem(val text: String, val level: Int = 0)

data class TableCellSpec(
    val row: Int,
    val col: Int,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val text: String,
    val bold: Boolean = false,
    val align: Align? = null,
    /** Fondo "RRGGBB" o null. */
    val fill: String? = null,
)

/** Caja 0..1000 en la imagen de la página (x0 < x1, y0 < y1). */
data class Box1000(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
    fun toList() = listOf(x0, y0, x1, y1)
}

sealed class DocBlock {
    /** Página escaneada de la que sale el bloque (1..N) si se conoce: progreso y depuración. */
    abstract val sourcePage: Int?

    abstract fun plainText(): String

    data class Heading(val text: String, val level: Int, val style: TextStyle = TextStyle(bold = true), override val sourcePage: Int? = null) : DocBlock() {
        override fun plainText() = InlineRuns.strip(text)
    }

    data class Paragraph(val text: String, val style: TextStyle = TextStyle(), override val sourcePage: Int? = null) : DocBlock() {
        override fun plainText() = InlineRuns.strip(text)
    }

    data class ListBlock(val items: List<ListItem>, val ordered: Boolean, val style: TextStyle = TextStyle(), override val sourcePage: Int? = null) : DocBlock() {
        override fun plainText() = items.joinToString("\n") { "  ".repeat(it.level) + InlineRuns.strip(it.text) }
    }

    data class Table(
        val rows: Int,
        val cols: Int,
        val cells: List<TableCellSpec>,
        val headerRows: Int = 0,
        val borders: Borders = Borders.ALL,
        /** Anchos relativos de las columnas (suman lo que sea; null = automáticos). */
        val colWidths: List<Float>? = null,
        override val sourcePage: Int? = null,
    ) : DocBlock() {
        override fun plainText(): String {
            val grid = Array(rows) { Array(cols) { "" } }
            for (c in cells) if (c.row in 0 until rows && c.col in 0 until cols) grid[c.row][c.col] = InlineRuns.strip(c.text)
            return grid.joinToString("\n") { it.joinToString("\t") }
        }

        /** Celda que ocupa (r, c) (la de inicio o una combinada que la cubre). */
        fun cellAt(r: Int, c: Int): TableCellSpec? = cells.firstOrNull { r in it.row until it.row + it.rowSpan && c in it.col until it.col + it.colSpan }
    }

    data class Image(val page: Int, val bbox: Box1000, val caption: String? = null, val widthPercent: Int? = null, override val sourcePage: Int? = page) : DocBlock() {
        override fun plainText() = caption.orEmpty()
    }

    data class Formula(val latex: String, val page: Int? = null, val bbox: Box1000? = null, val text: String? = null, override val sourcePage: Int? = page) : DocBlock() {
        override fun plainText() = text ?: LatexText.linear(latex)
    }

    data class PageBreak(override val sourcePage: Int? = null) : DocBlock() {
        override fun plainText() = ""
    }
}

/** Trozo de texto con formato dentro de un párrafo. */
data class Run(val text: String, val bold: Boolean, val italic: Boolean, val underline: Boolean)

/**
 * Formato en línea MÍNIMO que la IA puede usar dentro de un párrafo, celda o elemento de lista:
 * `**negrita**`, `*cursiva*`, `__subrayado__`; `\*` = asterisco literal. Un marcador sin su pareja se deja como
 * texto (un asterisco de nota al pie no rompe nada). Saltos de línea `\n` se conservan dentro del texto.
 */
object InlineRuns {
    private data class Tok(val marker: String?, val text: String)

    fun parse(text: String, base: TextStyle = TextStyle()): List<Run> {
        val toks = tokenize(text)
        // Solo cuentan los marcadores que tienen pareja: se emparejan por orden (pila por tipo).
        val active = BooleanArray(toks.size)
        val open = HashMap<String, Int>()
        for ((i, t) in toks.withIndex()) {
            val m = t.marker ?: continue
            val j = open.remove(m)
            if (j != null) { active[i] = true; active[j] = true } else open[m] = i
        }
        val out = ArrayList<Run>()
        var b = base.bold; var it = base.italic; var u = base.underline
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                val prev = out.lastOrNull()
                if (prev != null && prev.bold == b && prev.italic == it && prev.underline == u) out[out.lastIndex] = prev.copy(text = prev.text + sb)
                else out += Run(sb.toString(), b, it, u)
                sb.setLength(0)
            }
        }
        for ((i, t) in toks.withIndex()) {
            if (t.marker != null && active[i]) {
                flush()
                when (t.marker) {
                    "**" -> b = !b
                    "*" -> it = !it
                    "__" -> u = !u
                }
            } else sb.append(t.text)
        }
        flush()
        return out
    }

    /** Texto sin marcadores. */
    fun strip(text: String): String = parse(text).joinToString("") { it.text }

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        val sb = StringBuilder()
        fun text() { if (sb.isNotEmpty()) { out += Tok(null, sb.toString()); sb.setLength(0) } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && (s[i + 1] == '*' || s[i + 1] == '_') -> { sb.append(s[i + 1]); i += 2; continue }
                s.startsWith("**", i) -> { text(); out += Tok("**", "**"); i += 2; continue }
                s.startsWith("__", i) -> { text(); out += Tok("__", "__"); i += 2; continue }
                c == '*' -> { text(); out += Tok("*", "*"); i++; continue }
                else -> sb.append(c)
            }
            i++
        }
        text()
        return out
    }
}

/** LaTeX -> texto lineal legible (para el texto de las fórmulas sin recorte y la capa buscable). */
object LatexText {
    private val SYMBOLS = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε", "\\theta" to "θ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\pi" to "π", "\\sigma" to "σ", "\\phi" to "φ", "\\omega" to "ω",
        "\\Delta" to "Δ", "\\Sigma" to "Σ", "\\Omega" to "Ω", "\\pm" to "±", "\\times" to "×", "\\cdot" to "·",
        "\\div" to "÷", "\\leq" to "≤", "\\geq" to "≥", "\\le" to "≤", "\\ge" to "≥", "\\neq" to "≠", "\\approx" to "≈",
        "\\infty" to "∞", "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←", "\\sum" to "Σ", "\\int" to "∫",
        "\\partial" to "∂", "\\sqrt" to "√", "\\degree" to "°", "\\circ" to "°", "\\%" to "%", "\\," to " ", "\\;" to " ",
        "\\quad" to "  ", "\\left" to "", "\\right" to "",
    )

    fun linear(latex: String): String {
        var s = latex.trim().removePrefix("$$").removeSuffix("$$").removePrefix("$").removeSuffix("$")
            .removePrefix("\\[").removeSuffix("\\]").removePrefix("\\(").removeSuffix("\\)")
        // \frac{a}{b} -> (a)/(b)
        val frac = Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""")
        repeat(4) { s = frac.replace(s) { m -> "(${m.groupValues[1]})/(${m.groupValues[2]})" } }
        s = Regex("""\\(?:text|mathrm|mathbf|mathit|operatorname)\{([^{}]*)\}""").replace(s) { it.groupValues[1] }
        for ((k, v) in SYMBOLS.entries.sortedByDescending { it.key.length }) s = s.replace(k, v)
        s = Regex("""\^\{([^{}]*)\}""").replace(s) { "^(" + it.groupValues[1] + ")" }
        s = Regex("""_\{([^{}]*)\}""").replace(s) { "_(" + it.groupValues[1] + ")" }
        s = s.replace("{", "").replace("}", "").replace(Regex("""\\([A-Za-z]+)"""), "$1")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
