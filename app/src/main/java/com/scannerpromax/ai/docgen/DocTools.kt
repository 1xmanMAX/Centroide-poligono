package com.scannerpromax.ai.docgen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** Formatos de salida que la app sabe generar. */
enum class OutputFormat(val ext: String, val mime: String, val label: String) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    PDF("pdf", "application/pdf", "PDF"),
}

/** Definición de una herramienta para la IA: nombre, descripción (en inglés, para el modelo) y JSON Schema. */
data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

/**
 * HERRAMIENTAS que la IA usa para construir el documento (function calling / tool use). Esquemas compatibles con
 * `strict: true` de Claude (todos los objetos con `additionalProperties: false`, sin minimum/maximum: los rangos se
 * validan en [DocToolExecutor]) y con las tools de DeepSeek (formato OpenAI).
 */
object DocTools {
    const val SET_PAGE = "set_page"
    const val ADD_HEADING = "add_heading"
    const val ADD_PARAGRAPH = "add_paragraph"
    const val ADD_LIST = "add_list"
    const val ADD_TABLE = "add_table"
    const val ADD_IMAGE = "add_image"
    const val ADD_FORMULA = "add_formula"
    const val PAGE_BREAK = "page_break"
    const val SET_HEADER_FOOTER = "set_header_footer"
    const val READ_PAGE_TEXT = "read_page_text"
    const val FINISH_DOCUMENT = "finish_document"

    // ------------------------------------------------------------------ mini DSL de JSON Schema
    private fun t(type: String, desc: String? = null) = buildJsonObject { put("type", type); desc?.let { put("description", it) } }
    private fun enumOf(values: List<String>, desc: String? = null) = buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        desc?.let { put("description", it) }
    }
    private fun arr(items: JsonObject, desc: String? = null) = buildJsonObject {
        put("type", "array"); put("items", items); desc?.let { put("description", it) }
    }
    internal fun obj(props: Map<String, JsonObject>, required: List<String>, desc: String? = null) = buildJsonObject {
        put("type", "object")
        desc?.let { put("description", it) }
        put("properties", JsonObject(props))
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    val STYLE: JsonObject = obj(
        linkedMapOf(
            "bold" to t("boolean"),
            "italic" to t("boolean"),
            "underline" to t("boolean"),
            "align" to enumOf(listOf("left", "center", "right", "justify")),
            "size_pt" to t("number", "Font size in points as printed in the original (body text is usually 10-12)."),
            "color" to t("string", "Text color as hex RRGGBB, only when it is not black."),
            "handwriting" to t("boolean", "True if the original text is handwritten."),
        ),
        emptyList(),
        "Visual style of the whole block. Omit properties that are default (not bold, left aligned, black).",
    )

    private val SOURCE_PAGE = t("integer", "Number (1-based) of the scanned page this content comes from.")
    private val BBOX = arr(t("integer"), "[x0, y0, x1, y1] in 0..1000 coordinates of the scanned page image (origin top-left).")

    val ALL: List<ToolSpec> = listOf(
        ToolSpec(
            SET_PAGE,
            "Set the paper size, orientation and margins of the output document. Call it once at the start, matching the scanned original.",
            obj(
                linkedMapOf(
                    "size" to enumOf(listOf("A4", "LETTER", "LEGAL", "A5")),
                    "orientation" to enumOf(listOf("portrait", "landscape")),
                    "margins_mm" to obj(
                        linkedMapOf("top" to t("number"), "right" to t("number"), "bottom" to t("number"), "left" to t("number")),
                        listOf("top", "right", "bottom", "left"),
                        "Page margins in millimetres (0-60).",
                    ),
                ),
                listOf("size", "orientation"),
            ),
        ),
        ToolSpec(
            ADD_HEADING,
            "Append a title or heading. level 1 = document title / main heading, 2-6 = sub-headings.",
            obj(
                linkedMapOf(
                    "text" to t("string", "Exact heading text as in the original."),
                    "level" to t("integer", "1-6"),
                    "style" to STYLE,
                    "source_page" to SOURCE_PAGE,
                ),
                listOf("text", "level"),
            ),
        ),
        ToolSpec(
            ADD_PARAGRAPH,
            "Append one paragraph of running text. Inline formatting inside the text: **bold**, *italic*, __underline__ " +
                "(write \\* for a literal asterisk). Use \\n only for hard line breaks that exist in the original (addresses, verses).",
            obj(
                linkedMapOf("text" to t("string"), "style" to STYLE, "source_page" to SOURCE_PAGE),
                listOf("text"),
            ),
        ),
        ToolSpec(
            ADD_LIST,
            "Append a bulleted or numbered list. Do not include the bullet characters or numbers in the item text.",
            obj(
                linkedMapOf(
                    "items" to arr(
                        obj(linkedMapOf("text" to t("string"), "level" to t("integer", "Nesting level 0-4 (0 = top level).")), listOf("text")),
                    ),
                    "ordered" to t("boolean", "true = numbered list, false = bullets."),
                    "style" to STYLE,
                    "source_page" to SOURCE_PAGE,
                ),
                listOf("items", "ordered"),
            ),
        ),
        ToolSpec(
            ADD_TABLE,
            "Append a real table. Give every non-empty cell with its 0-based row r and column c; merged cells use rowspan/colspan " +
                "from their top-left cell (cells covered by a merge are not listed). Empty cells may be omitted.",
            obj(
                linkedMapOf(
                    "rows" to t("integer", "Number of rows (1-200)."),
                    "cols" to t("integer", "Number of columns (1-30)."),
                    "cells" to arr(
                        obj(
                            linkedMapOf(
                                "r" to t("integer"), "c" to t("integer"),
                                "rowspan" to t("integer"), "colspan" to t("integer"),
                                "text" to t("string", "Exact cell text; inline **bold**/*italic* allowed; \\n for line breaks."),
                                "bold" to t("boolean"),
                                "align" to enumOf(listOf("left", "center", "right")),
                                "fill" to t("string", "Background color hex RRGGBB if the cell is shaded."),
                            ),
                            listOf("r", "c", "text"),
                        ),
                    ),
                    "header_rows" to t("integer", "Number of header rows at the top (0 if none)."),
                    "borders" to enumOf(listOf("all", "none", "horizontal")),
                    "col_widths" to arr(t("number"), "Optional relative column widths, one per column."),
                    "source_page" to SOURCE_PAGE,
                ),
                listOf("rows", "cols", "cells"),
            ),
        ),
        ToolSpec(
            ADD_IMAGE,
            "Append a picture cropped from the scanned page: photos, drawings, logos, charts/diagrams, signatures, stamps. " +
                "The app crops the rectangle bbox from the page image.",
            obj(
                linkedMapOf(
                    "page" to t("integer", "Scanned page number (1-based)."),
                    "bbox" to BBOX,
                    "caption" to t("string", "Caption text printed under the figure in the original, if any."),
                    "width_percent" to t("integer", "Width in the output as % of the text width (10-100). Omit to keep the original proportion."),
                ),
                listOf("page", "bbox"),
            ),
        ),
        ToolSpec(
            ADD_FORMULA,
            "Append a display (stand-alone) mathematical formula. Give it in LaTeX; with page+bbox the app also inserts the " +
                "cropped original so the formula looks exactly as printed.",
            obj(
                linkedMapOf(
                    "latex" to t("string"),
                    "page" to t("integer"),
                    "bbox" to BBOX,
                    "text" to t("string", "Optional plain-text rendering of the formula."),
                ),
                listOf("latex"),
            ),
        ),
        ToolSpec(PAGE_BREAK, "Start a new page in the output (use it between scanned pages).", obj(emptyMap(), emptyList())),
        ToolSpec(
            SET_HEADER_FOOTER,
            "Set the running header/footer repeated on every output page and where page numbers go.",
            obj(
                linkedMapOf(
                    "header" to t("string"),
                    "footer" to t("string"),
                    "align" to enumOf(listOf("left", "center", "right")),
                    "page_numbers" to enumOf(listOf("none", "footer_center", "footer_right", "header_right")),
                ),
                emptyList(),
            ),
        ),
        ToolSpec(
            READ_PAGE_TEXT,
            "Return the text recognised on a scanned page by the on-device OCR: lines with their boxes (0..1000) and confidence, " +
                "plus layout regions when available. Use it as the reference for exact wording; fix obvious OCR errors using the image.",
            obj(linkedMapOf("page" to t("integer", "Scanned page number (1-based).")), listOf("page")),
        ),
        ToolSpec(
            FINISH_DOCUMENT,
            "Generate and save the final files. ALWAYS call this exactly once, when every page has been reproduced.",
            obj(
                linkedMapOf(
                    "formats" to arr(enumOf(listOf("docx", "pdf"))),
                    "filename" to t("string", "File name without extension."),
                    "title" to t("string", "Document title (metadata)."),
                ),
                listOf("formats", "filename"),
            ),
        ),
    )

    val NAMES: Set<String> = ALL.map { it.name }.toSet()
}

/** Resultado de ejecutar una herramienta: texto para la IA (JSON) y si fue un error de validación. */
data class ToolExec(val content: String, val isError: Boolean, val finish: FinishRequest? = null)

data class FinishRequest(val formats: Set<OutputFormat>, val filename: String, val title: String?)

/**
 * Ejecuta las herramientas sobre el documento en construcción. Valida TODAS las entradas (tipos, rangos, celdas que
 * se salen o se solapan). [strict] = true (bucle agéntico): una entrada mala se rechaza con un error claro para que la
 * IA la corrija; false (modo rápido, sin segunda oportunidad): se normaliza lo que se pueda y se descarta lo demás.
 */
class DocToolExecutor(
    val pageCount: Int,
    private val pageText: (Int) -> String,
    private val strict: Boolean = true,
) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    var title: String? = null
        private set
    private var page = PageSetup()
    private var header: HeaderFooter? = null
    private var footer: HeaderFooter? = null
    private var pageNumbers = PageNumbers.NONE
    private val blocks = ArrayList<DocBlock>()

    /** Última página escaneada mencionada (progreso; página por defecto de imágenes en el modo rápido). */
    var currentPage: Int = 1
    /** Avisos de normalización (modo rápido). */
    val warnings = ArrayList<String>()
    /** Páginas leídas con read_page_text o con contenido añadido. */
    val touchedPages = sortedSetOf<Int>()

    val blockCount: Int get() = blocks.size

    fun document(): GenDocument = GenDocument(title, page, header, footer, pageNumbers, blocks.toList())

    fun setTitleIfMissing(t: String?) { if (title.isNullOrBlank() && !t.isNullOrBlank()) title = t.trim().take(300) }

    private class Invalid(msg: String) : Exception(msg)

    fun execute(rawName: String, argsJson: String): ToolExec {
        val name = rawName.trim().lowercase()
        if (name !in DocTools.NAMES) return err("Unknown tool '$rawName'. Available: ${DocTools.NAMES.joinToString()}")
        val args = try {
            if (argsJson.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(argsJson) as? JsonObject
                ?: return err("Arguments must be a JSON object")
        } catch (e: Exception) {
            return err("INVALID_JSON: the arguments could not be parsed (${e.message?.take(120)}). Send the call again with valid JSON.")
        }
        return execute(name, args)
    }

    fun execute(name: String, args: JsonObject): ToolExec = try {
        when (name) {
            DocTools.SET_PAGE -> setPage(args)
            DocTools.ADD_HEADING -> add(heading(args))
            DocTools.ADD_PARAGRAPH -> add(paragraph(args))
            DocTools.ADD_LIST -> add(list(args))
            DocTools.ADD_TABLE -> add(table(args))
            DocTools.ADD_IMAGE -> add(image(args))
            DocTools.ADD_FORMULA -> add(formula(args))
            DocTools.PAGE_BREAK -> add(DocBlock.PageBreak(currentPage))
            DocTools.SET_HEADER_FOOTER -> setHeaderFooter(args)
            DocTools.READ_PAGE_TEXT -> {
                val p = int(args, "page", required = true)!!
                checkPage(p)
                currentPage = p
                touchedPages += p
                ToolExec(pageText(p), false)
            }
            DocTools.FINISH_DOCUMENT -> finish(args)
            else -> err("Unknown tool '$name'")
        }
    } catch (e: Invalid) {
        err(e.message ?: "invalid input")
    }

    private fun err(msg: String) = ToolExec("""{"ok":false,"error":${JsonPrimitive(msg)}}""", true)

    private fun ok(extra: String = "") = ToolExec("""{"ok":true,"blocks":${blocks.size}$extra}""", false)

    private fun add(b: DocBlock?): ToolExec {
        if (b == null) return ok(""","skipped":true""")
        blocks += b
        b.sourcePage?.let { if (it in 1..pageCount) { currentPage = it; touchedPages += it } }
        return ok()
    }

    // ------------------------------------------------------------------ herramientas

    private fun setPage(a: JsonObject): ToolExec {
        val size = enumStr(a, "size", listOf("A4", "LETTER", "LEGAL", "A5"), "A4", ignoreCase = true)!!.uppercase()
        val orient = enumStr(a, "orientation", listOf("portrait", "landscape"), "portrait")!!
        val m = a["margins_mm"] as? JsonObject
        val margins = if (m == null) page.marginsMm else Margins(
            top = num(m, "top", 20f, 0f, 60f), right = num(m, "right", 20f, 0f, 60f),
            bottom = num(m, "bottom", 20f, 0f, 60f), left = num(m, "left", 20f, 0f, 60f),
        )
        page = PageSetup(Paper.valueOf(size), orient == "landscape", margins)
        return ToolExec("""{"ok":true,"page":"$size $orient"}""", false)
    }

    private fun setHeaderFooter(a: JsonObject): ToolExec {
        val align = when (enumStr(a, "align", listOf("left", "center", "right"), "center")) {
            "left" -> Align.LEFT; "right" -> Align.RIGHT; else -> Align.CENTER
        }
        str(a, "header")?.let { header = it.trim().takeIf { s -> s.isNotEmpty() }?.let { s -> HeaderFooter(s.take(500), align) } }
        str(a, "footer")?.let { footer = it.trim().takeIf { s -> s.isNotEmpty() }?.let { s -> HeaderFooter(s.take(500), align) } }
        enumStr(a, "page_numbers", listOf("none", "footer_center", "footer_right", "header_right"), null)?.let {
            pageNumbers = PageNumbers.valueOf(it.uppercase())
        }
        return ToolExec("""{"ok":true}""", false)
    }

    private fun heading(a: JsonObject): DocBlock? {
        val text = text(a, "text", 2_000) ?: return null
        var level = int(a, "level", required = strict) ?: 1
        if (level !in 1..6) { if (strict) throw Invalid("level must be 1-6"); level = level.coerceIn(1, 6) }
        val style = style(a["style"], TextStyle(bold = true))
        return DocBlock.Heading(text, level, style, sourcePage(a))
    }

    private fun paragraph(a: JsonObject): DocBlock? {
        val text = text(a, "text", 20_000) ?: return null
        return DocBlock.Paragraph(text, style(a["style"], TextStyle()), sourcePage(a))
    }

    private fun list(a: JsonObject): DocBlock? {
        val raw = a["items"] as? JsonArray ?: throw Invalid("items must be an array")
        val items = raw.mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.contentOrNullSafe()?.let { ListItem(it, 0) }
                is JsonObject -> {
                    val t = str(e, "text") ?: return@mapNotNull null
                    var lv = int(e, "level", required = false) ?: 0
                    if (lv !in 0..4) { if (strict) throw Invalid("list item level must be 0-4"); lv = lv.coerceIn(0, 4) }
                    ListItem(t, lv)
                }
                else -> null
            }
        }.filter { it.text.isNotBlank() }
        if (items.isEmpty()) { if (strict) throw Invalid("items is empty"); return null }
        if (items.size > 1_000) throw Invalid("too many items (max 1000)")
        val ordered = bool(a, "ordered") ?: false
        return DocBlock.ListBlock(items, ordered, style(a["style"], TextStyle()), sourcePage(a))
    }

    private fun table(a: JsonObject): DocBlock? {
        var rows = int(a, "rows", required = true)!!
        var cols = int(a, "cols", required = true)!!
        if (rows !in 1..200 || cols !in 1..30) {
            if (strict) throw Invalid("rows must be 1-200 and cols 1-30 (got $rows x $cols)")
            rows = rows.coerceIn(1, 200); cols = cols.coerceIn(1, 30)
        }
        val raw = a["cells"] as? JsonArray ?: throw Invalid("cells must be an array")
        val problems = ArrayList<String>()
        val cells = ArrayList<TableCellSpec>()
        val taken = Array(rows) { BooleanArray(cols) }
        for ((i, e) in raw.withIndex()) {
            val o = e as? JsonObject ?: run { problems += "cells[$i] is not an object"; null } ?: continue
            val r = int(o, "r", required = false); val c = int(o, "c", required = false)
            if (r == null || c == null) { problems += "cells[$i] needs integer r and c"; continue }
            if (r !in 0 until rows || c !in 0 until cols) { problems += "cells[$i] ($r,$c) is outside the ${rows}x$cols table"; continue }
            var rs = int(o, "rowspan", required = false) ?: 1
            var cs = int(o, "colspan", required = false) ?: 1
            if (rs < 1 || cs < 1) { problems += "cells[$i] rowspan/colspan must be >= 1"; rs = rs.coerceAtLeast(1); cs = cs.coerceAtLeast(1) }
            if (r + rs > rows || c + cs > cols) {
                problems += "cells[$i] ($r,$c) span ${rs}x$cs goes past the table edge"
                rs = rs.coerceAtMost(rows - r); cs = cs.coerceAtMost(cols - c)
            }
            var overlap = false
            for (rr in r until r + rs) for (cc in c until c + cs) if (taken[rr][cc]) overlap = true
            if (overlap) { problems += "cells[$i] ($r,$c) overlaps another cell or merge"; continue }
            for (rr in r until r + rs) for (cc in c until c + cs) taken[rr][cc] = true
            val align = when (o["align"]?.let { (it as? JsonPrimitive)?.contentOrNullSafe()?.lowercase() }) {
                "center" -> Align.CENTER; "right" -> Align.RIGHT; "left" -> Align.LEFT; else -> null
            }
            cells += TableCellSpec(r, c, rs, cs, (str(o, "text") ?: "").take(5_000), bool(o, "bold") ?: false, align, color(o, "fill"))
        }
        if (strict && problems.isNotEmpty()) throw Invalid("Table rejected, fix and resend: " + problems.take(8).joinToString("; "))
        warnings += problems.map { "tabla: $it" }
        var header = int(a, "header_rows", required = false) ?: 0
        header = header.coerceIn(0, rows)
        val borders = when (enumStr(a, "borders", listOf("all", "none", "horizontal"), "all")) {
            "none" -> Borders.NONE; "horizontal" -> Borders.HORIZONTAL; else -> Borders.ALL
        }
        val widths = (a["col_widths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }
            ?.takeIf { it.size == cols && it.all { w -> w > 0f } }
        return DocBlock.Table(rows, cols, cells, header, borders, widths, sourcePage(a))
    }

    private fun image(a: JsonObject): DocBlock? {
        val p = int(a, "page", required = strict) ?: currentPage
        checkPage(p)
        val box = bbox(a["bbox"], required = true)!!
        val wp = int(a, "width_percent", required = false)?.let {
            if (it !in 10..100) { if (strict) throw Invalid("width_percent must be 10-100"); it.coerceIn(10, 100) } else it
        }
        return DocBlock.Image(p, box, str(a, "caption")?.trim()?.takeIf { it.isNotEmpty() }?.take(1_000), wp)
    }

    private fun formula(a: JsonObject): DocBlock? {
        val latex = text(a, "latex", 5_000) ?: return null
        val p = int(a, "page", required = false)
        val box = bbox(a["bbox"], required = false)
        if ((p == null) != (box == null)) {
            if (strict) throw Invalid("give both page and bbox, or neither")
        }
        if (p != null) checkPage(p)
        val usePage = if (box != null) p ?: currentPage else null
        return DocBlock.Formula(latex, usePage, if (usePage != null) box else null, str(a, "text")?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun finish(a: JsonObject): ToolExec {
        val raw = a["formats"] as? JsonArray ?: throw Invalid("formats must be an array with \"docx\" and/or \"pdf\"")
        val formats = raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe()?.lowercase() }.mapNotNull {
            when (it) { "docx", "word" -> OutputFormat.DOCX; "pdf" -> OutputFormat.PDF; else -> null }
        }.toSet()
        if (formats.isEmpty()) throw Invalid("formats must contain \"docx\" and/or \"pdf\"")
        val name = (str(a, "filename") ?: "").trim().ifEmpty { title ?: "Documento" }
        val t = str(a, "title")?.trim()?.takeIf { it.isNotEmpty() }
        setTitleIfMissing(t)
        if (blocks.isEmpty()) throw Invalid("The document is empty: add the page content before finish_document")
        return ToolExec("""{"ok":true}""", false, FinishRequest(formats, sanitizeFileName(name), t ?: title))
    }

    // ------------------------------------------------------------------ lectura y validación de argumentos

    private fun checkPage(p: Int) {
        if (p !in 1..pageCount) throw Invalid("page must be between 1 and $pageCount (got $p)")
    }

    private fun sourcePage(a: JsonObject): Int? = int(a, "source_page", required = false)?.takeIf { it in 1..pageCount } ?: currentPage

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    private fun str(o: JsonObject, k: String): String? = when (val v = o[k]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> throw Invalid("$k must be a string")
    }

    private fun text(o: JsonObject, k: String, max: Int): String? {
        val s = str(o, k)
        if (s.isNullOrBlank()) { if (strict) throw Invalid("$k is required and must not be empty"); return null }
        if (s.length > max) { if (strict) throw Invalid("$k is too long (max $max characters): split it into several calls"); return s.take(max) }
        return s
    }

    private fun int(o: JsonObject, k: String, required: Boolean): Int? {
        val v = o[k]
        if (v == null || v is JsonNull) { if (required) throw Invalid("$k is required"); return null }
        val p = v as? JsonPrimitive ?: throw Invalid("$k must be an integer")
        val d = p.content.toDoubleOrNull() ?: throw Invalid("$k must be an integer (got ${p.content.take(20)})")
        return d.roundToInt()
    }

    private fun num(o: JsonObject, k: String, def: Float, min: Float, max: Float): Float {
        val v = (o[k] as? JsonPrimitive)?.content?.toFloatOrNull() ?: return def
        if (v !in min..max) { if (strict) throw Invalid("$k must be between $min and $max"); return v.coerceIn(min, max) }
        return v
    }

    private fun bool(o: JsonObject, k: String): Boolean? = (o[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.equals("true", true) }

    private fun enumStr(o: JsonObject, k: String, allowed: List<String>, def: String?, ignoreCase: Boolean = true): String? {
        val v = str(o, k)?.trim() ?: return def
        val hit = allowed.firstOrNull { it.equals(v, ignoreCase) }
        if (hit == null) { if (strict) throw Invalid("$k must be one of ${allowed.joinToString()} (got '$v')"); return def }
        return hit
    }

    private fun color(o: JsonObject, k: String): String? {
        val v = str(o, k)?.trim()?.removePrefix("#") ?: return null
        if (!v.matches(Regex("[0-9A-Fa-f]{6}"))) { if (strict) throw Invalid("$k must be a hex color RRGGBB"); return null }
        return v.uppercase()
    }

    private fun bbox(e: JsonElement?, required: Boolean): Box1000? {
        if (e == null || e is JsonNull) { if (required) throw Invalid("bbox is required: [x0, y0, x1, y1] in 0..1000"); return null }
        val a = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
        if (a == null || a.size != 4) throw Invalid("bbox must be 4 numbers [x0, y0, x1, y1] in 0..1000")
        // Cajas en 0..1 -> 0..1000 (modo tolerante y también en estricto: no es ambiguo).
        val scale = if (a.all { it in 0.0..1.0 } && a.any { it > 0.0 }) 1000.0 else 1.0
        var (x0, y0, x1, y1) = a.map { (it * scale).roundToInt() }
        if (listOf(x0, y0, x1, y1).any { it !in 0..1000 }) {
            if (strict) throw Invalid("bbox values must be within 0..1000")
            x0 = x0.coerceIn(0, 1000); y0 = y0.coerceIn(0, 1000); x1 = x1.coerceIn(0, 1000); y1 = y1.coerceIn(0, 1000)
        }
        if (x1 < x0) { val t = x0; x0 = x1; x1 = t }
        if (y1 < y0) { val t = y0; y0 = y1; y1 = t }
        if (x1 - x0 < 5 || y1 - y0 < 5) throw Invalid("bbox is too small (needs x1 > x0 + 5 and y1 > y0 + 5)")
        return Box1000(x0, y0, x1, y1)
    }

    private fun style(e: JsonElement?, def: TextStyle): TextStyle {
        val o = e as? JsonObject ?: return def
        val align = when (enumStr(o, "align", listOf("left", "center", "right", "justify"), null)) {
            "center" -> Align.CENTER; "right" -> Align.RIGHT; "justify" -> Align.JUSTIFY; "left" -> Align.LEFT; else -> def.align
        }
        val size = (o["size_pt"] as? JsonPrimitive)?.content?.toFloatOrNull()?.let {
            if (it !in 5f..96f) { if (strict) throw Invalid("size_pt must be between 5 and 96"); it.coerceIn(5f, 96f) } else it
        }
        return TextStyle(
            bold = bool(o, "bold") ?: def.bold,
            italic = bool(o, "italic") ?: def.italic,
            underline = bool(o, "underline") ?: def.underline,
            align = align,
            sizePt = size ?: def.sizePt,
            color = color(o, "color")?.takeIf { it != "000000" } ?: def.color,
            handwriting = bool(o, "handwriting") ?: def.handwriting,
        )
    }

    companion object {
        fun sanitizeFileName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\.(docx|pdf)$", RegexOption.IGNORE_CASE), "")
                .trim().take(100).ifEmpty { "Documento" }
    }
}
