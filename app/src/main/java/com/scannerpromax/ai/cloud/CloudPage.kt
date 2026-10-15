package com.scannerpromax.ai.cloud

import com.scannerpromax.domain.BlockStyle
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.TableCell
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Página tal como la devuelve un modelo en la nube (ya validada y normalizada). Coordenadas 0..1000 relativas a la
 * imagen enviada (origen arriba-izquierda). Lógica pura (JVM).
 */
data class CloudPage(
    /** Una de [com.scannerpromax.ai.parse.IaText.DOC_CLASSES] o null. */
    val docType: String?,
    val blocks: List<CloudBlock>,
    /** El JSON venía cortado (max_tokens) y se reparó: puede faltar el final de la página. */
    val repaired: Boolean = false,
    /** Giro horario que el modelo cree necesario para poner el texto derecho (0/90/180/270). */
    val rotation: Int = 0,
) {
    /** Texto de la página en orden de lectura (para validar frente al OCR básico). */
    val text: String get() = blocks.joinToString("\n\n") { it.plainText() }.trim()
}

data class CloudLine(val text: String, val bbox: FloatArray?)

data class CloudBlock(
    val type: BlockType,
    val bbox: FloatArray?,
    val lines: List<CloudLine>,
    val style: BlockStyle? = null,
    /** Color de tinta ARGB (null = negro / desconocido). */
    val color: Int? = null,
    val tableRows: Int = 0,
    val tableCols: Int = 0,
    val cells: List<TableCell> = emptyList(),
    /** Gráfico: primera fila = cabecera. */
    val chart: List<List<String>> = emptyList(),
    val chartTitle: String? = null,
    val latex: String? = null,
) {
    fun plainText(): String = when {
        type == BlockType.TABLE && cells.isNotEmpty() -> tableRowsText().joinToString("\n") { it.joinToString("\t") }
        type == BlockType.FORMULA && lines.isEmpty() -> latex.orEmpty()
        type == BlockType.CHART && lines.isEmpty() -> listOfNotNull(chartTitle).plus(chart.map { it.joinToString("\t") }).joinToString("\n")
        else -> lines.joinToString("\n") { it.text }
    }

    /** Filas de la tabla con el texto de cada celda en su columna de inicio (celdas fusionadas una sola vez). */
    fun tableRowsText(): List<List<String>> {
        if (tableRows <= 0 || tableCols <= 0) return emptyList()
        val grid = List(tableRows) { MutableList(tableCols) { "" } }
        for (c in cells) if (c.row in 0 until tableRows && c.col in 0 until tableCols) grid[c.row][c.col] = c.text
        return grid
    }
}

/**
 * Parser TOLERANTE de la respuesta: acepta texto alrededor, vallas ```json, comas finales, JSON cortado por max_tokens
 * (se cierra en el último elemento completo), números como texto y cajas en 0..1 o en píxeles. Valida y normaliza:
 * tipos desconocidos -> TEXT, cajas ordenadas y recortadas, tablas sin solapes, bloques vacíos fuera.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object CloudJson {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true; allowTrailingComma = true }

    class ParseException(message: String) : Exception(message)

    /**
     * @param sentW,sentH tamaño de la imagen enviada (para cajas que el modelo dé en píxeles en vez de 0..1000).
     * @throws ParseException si no hay ningún objeto JSON utilizable.
     */
    fun parse(raw: String, sentW: Int = 0, sentH: Int = 0): CloudPage {
        val (text, repaired) = extractObject(raw) ?: throw ParseException("La respuesta no contiene JSON")
        val root = runCatching { json.parseToJsonElement(text) }.getOrElse { throw ParseException("JSON no válido: ${it.message?.take(120)}") }
        val obj = root as? JsonObject ?: throw ParseException("La respuesta no es un objeto JSON")
        val blocksEl = obj["blocks"] ?: obj["elements"] ?: obj["regions"]
        val blocks = (blocksEl as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { b -> block(b, sentW, sentH) } }
        val docType = obj.str("doc_type") ?: obj.str("document_type")
        val rot = (obj["rotation"] as? JsonPrimitive)?.intOrNull?.let { ((it % 360) + 360) % 360 }?.takeIf { it % 90 == 0 } ?: 0
        return CloudPage(docType?.let { normalizeDocType(it) }, blocks, repaired, rot)
    }

    // ------------------------------------------------------------------------------------------ extracción

    /**
     * Primer objeto JSON de [raw] (sin vallas ni texto alrededor). Si está cortado, se recorta tras el último elemento
     * completo y se cierran las llaves/corchetes abiertos. Devuelve (json, reparado) o null.
     */
    fun extractObject(raw: String): Pair<String, Boolean>? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        val stack = ArrayDeque<Char>()
        var inStr = false
        var esc = false
        // Último punto de corte seguro: índice (exclusivo) + pila en ese momento.
        var safeEnd = -1
        var safeStack: List<Char> = emptyList()
        var i = start
        while (i < raw.length) {
            val c = raw[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> stack.addLast(c)
                    '}', ']' -> {
                        if (stack.isEmpty()) return null
                        stack.removeLast()
                        if (stack.isEmpty()) return raw.substring(start, i + 1) to false
                        safeEnd = i + 1; safeStack = stack.toList()
                    }
                    ',' -> { safeEnd = i; safeStack = stack.toList() }
                }
            }
            i++
        }
        // Cortado: cerrar en el último elemento completo.
        if (safeEnd < 0) return null
        var body = raw.substring(start, safeEnd).trimEnd()
        // Una clave sin valor ("...", "key") o una coma colgante no pueden quedar al final.
        while (body.endsWith(",") || body.endsWith(":")) body = body.dropLast(1).trimEnd()
        val closing = safeStack.reversed().joinToString("") { if (it == '{') "}" else "]" }
        return (body + closing) to true
    }

    // ------------------------------------------------------------------------------------------ bloques

    private fun block(b: JsonObject, sw: Int, sh: Int): CloudBlock? {
        val type = blockType(b.str("type") ?: b.str("category") ?: "text")
        val bbox = box(b["bbox"] ?: b["box"], sw, sh)
        val lines = (b["lines"] as? JsonArray).orEmpty().mapNotNull { l ->
            when (l) {
                is JsonObject -> l.str("text")?.let { t -> CloudLine(cleanText(t), box(l["bbox"] ?: l["box"], sw, sh)) }
                is JsonPrimitive -> l.contentOrNullSafe()?.let { CloudLine(cleanText(it), null) }
                else -> null
            }
        }.filter { it.text.isNotBlank() }.toMutableList()
        // Algunos modelos ponen el texto del bloque en "text" en vez de en "lines".
        if (lines.isEmpty()) b.str("text")?.takeIf { it.isNotBlank() }?.let { t ->
            cleanText(t).split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines += CloudLine(it, null) }
            if (lines.size == 1 && bbox != null) lines[0] = lines[0].copy(bbox = bbox)
        }
        val styleObj = b["style"] as? JsonObject
        val style = styleObj?.let {
            BlockStyle(
                bold = it.bool("bold") ?: false,
                italic = it.bool("italic") ?: false,
                align = it.str("align")?.lowercase()?.takeIf { a -> a in ALIGNS },
                size = it.str("size")?.lowercase()?.takeIf { s -> s in SIZES },
            )
        }
        val color = styleObj?.str("color")?.let { parseColor(it) }
        var rows = 0; var cols = 0; var cells = emptyList<TableCell>()
        // Tabla en "table": {rows, cols, cells} o, en la variante del banco (recon2/haiku/PROMPT.md), "cells" en el bloque.
        val tableObj = b["table"] as? JsonObject ?: (b["cells"] as? JsonArray)?.let { JsonObject(mapOf("cells" to it)) }
        tableObj?.let { t -> table(t).let { (r, c, cs) -> rows = r; cols = c; cells = cs } }
        val chartObj = b["chart"] as? JsonObject
            ?: (b["chart_data"] as? JsonArray)?.takeIf { it.size >= 2 }?.let { JsonObject(mapOf("header" to it[0], "rows" to JsonArray(it.drop(1)))) }
        val chart = chartObj?.let { chartRows(it) }.orEmpty()
        val latex = (b.str("latex") ?: b.str("formula"))?.let { stripMathDelims(it) }?.takeIf { it.isNotBlank() }

        val hasContent = lines.isNotEmpty() || cells.isNotEmpty() || chart.isNotEmpty() || latex != null
        if (!hasContent && (bbox == null || type != BlockType.FIGURE && type != BlockType.CHART && type != BlockType.TABLE)) return null
        val finalType = when {
            type == BlockType.TABLE && cells.isEmpty() && lines.isNotEmpty() -> BlockType.TEXT
            type == BlockType.CHART && chart.isEmpty() -> BlockType.FIGURE
            type == BlockType.FORMULA && latex == null && lines.isEmpty() -> return null
            else -> type
        }
        return CloudBlock(
            type = finalType, bbox = bbox ?: envelope(lines.mapNotNull { it.bbox }), lines = lines, style = style,
            color = color, tableRows = rows, tableCols = cols, cells = cells, chart = chart,
            chartTitle = chartObj?.str("title")?.takeIf { it.isNotBlank() }, latex = latex,
        )
    }

    /** Celdas válidas: dentro de la rejilla, spans recortados, sin solapes (gana la primera). */
    internal fun table(t: JsonObject): Triple<Int, Int, List<TableCell>> {
        val raw = (t["cells"] as? JsonArray).orEmpty().mapNotNull { c ->
            val o = c as? JsonObject ?: return@mapNotNull null
            val r = o.int("row") ?: return@mapNotNull null
            val col = o.int("col") ?: o.int("column") ?: return@mapNotNull null
            if (r < 0 || col < 0 || r > MAX_TABLE || col > MAX_TABLE) return@mapNotNull null
            TableCell(r, col, (o.int("row_span") ?: o.int("rowspan") ?: o.int("rowSpan") ?: 1).coerceIn(1, MAX_TABLE),
                (o.int("col_span") ?: o.int("colspan") ?: o.int("colSpan") ?: 1).coerceIn(1, MAX_TABLE), cleanText(o.str("text").orEmpty()))
        }
        if (raw.isEmpty()) return Triple(0, 0, emptyList())
        val rows = maxOf(t.int("rows") ?: 0, raw.maxOf { it.row + 1 }).coerceAtMost(MAX_TABLE)
        val cols = maxOf(t.int("cols") ?: t.int("columns") ?: 0, raw.maxOf { it.col + 1 }).coerceAtMost(MAX_TABLE)
        val taken = Array(rows) { BooleanArray(cols) }
        val out = ArrayList<TableCell>()
        for (c in raw.sortedWith(compareBy({ it.row }, { it.col }))) {
            if (c.row >= rows || c.col >= cols || taken[c.row][c.col]) continue
            var rs = minOf(c.rowSpan, rows - c.row)
            var cs = minOf(c.colSpan, cols - c.col)
            // Recorta el span si pisa una celda ya colocada.
            while (cs > 1 && (0 until rs).any { dr -> taken[c.row + dr][c.col + cs - 1] }) cs--
            while (rs > 1 && (0 until cs).any { dc -> taken[c.row + rs - 1][c.col + dc] }) rs--
            for (dr in 0 until rs) for (dc in 0 until cs) taken[c.row + dr][c.col + dc] = true
            out += c.copy(rowSpan = rs, colSpan = cs)
        }
        return Triple(rows, cols, out)
    }

    private fun chartRows(o: JsonObject): List<List<String>> {
        val header = (o["header"] as? JsonArray).orEmpty().map { it.asText() }
        val rows = (o["rows"] as? JsonArray).orEmpty().mapNotNull { r -> (r as? JsonArray)?.map { it.asText() } }
        if (rows.isEmpty()) return emptyList()
        val width = maxOf(header.size, rows.maxOf { it.size })
        fun pad(r: List<String>) = r + List(width - r.size) { "" }
        return listOf(pad(header.ifEmpty { List(width) { "" } })) + rows.map { pad(it) }
    }

    // ------------------------------------------------------------------------------------------ valores

    /**
     * Caja [x0,y0,x1,y1] a 0..1000. Acepta 0..1 (fracciones) y píxeles de la imagen enviada (> 1000 con [sw]/[sh]).
     * null si no hay 4 números o queda degenerada.
     */
    internal fun box(e: JsonElement?, sw: Int, sh: Int): FloatArray? {
        val arr = e as? JsonArray ?: return null
        if (arr.size < 4) return null
        val v = arr.take(4).map { (it as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.content.trim().toDoubleOrNull() } ?: return null }
        var x0 = v[0]; var y0 = v[1]; var x1 = v[2]; var y1 = v[3]
        val mx = v.maxOf { it }
        if (mx <= 1.0 && v.any { it > 0.0 }) { x0 *= 1000; y0 *= 1000; x1 *= 1000; y1 *= 1000 }
        else if (mx > 1000.0 && sw > 0 && sh > 0) { x0 *= 1000.0 / sw; x1 *= 1000.0 / sw; y0 *= 1000.0 / sh; y1 *= 1000.0 / sh }
        val l = minOf(x0, x1).coerceIn(0.0, 1000.0); val r = maxOf(x0, x1).coerceIn(0.0, 1000.0)
        val t = minOf(y0, y1).coerceIn(0.0, 1000.0); val b = maxOf(y0, y1).coerceIn(0.0, 1000.0)
        if (r - l < 0.5 || b - t < 0.5) return null
        return floatArrayOf(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    }

    internal fun envelope(boxes: List<FloatArray>): FloatArray? = if (boxes.isEmpty()) null else floatArrayOf(
        boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] },
    )

    internal fun blockType(s: String): BlockType = when (s.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "title", "heading", "section_header", "section_title", "subtitle" -> BlockType.TITLE
        "handwriting", "handwritten", "handwritten_text" -> BlockType.HANDWRITING
        "table" -> BlockType.TABLE
        "chart", "graph", "plot", "diagram_data" -> BlockType.CHART
        "formula", "equation", "math" -> BlockType.FORMULA
        "figure", "image", "picture", "photo", "logo", "signature", "stamp", "seal", "drawing", "diagram" -> BlockType.FIGURE
        "header", "page_header" -> BlockType.HEADER
        "footer", "page_footer", "page_number", "footnote" -> BlockType.FOOTER
        else -> BlockType.TEXT
    }

    internal fun normalizeDocType(s: String): String? {
        val t = s.trim().lowercase().replace('-', '_').replace(' ', '_')
        if (t in com.scannerpromax.ai.parse.IaText.DOC_CLASSES) return t
        return when (t) {
            "invoice", "ticket", "bill" -> "receipt"
            "id", "idcard", "card", "passport", "license" -> "id_card"
            "handwriting", "notes", "handwritten_notes", "notebook" -> "handwritten"
            "blackboard", "board" -> "whiteboard"
            "monitor", "screenshot", "screen_photo" -> "screen"
            "book_page", "page" -> "book"
            "table", "questionnaire", "application" -> "form"
            "letter", "article", "document", "report", "contract", "printed_text", "other" -> "printed"
            "none", "photo", "no_doc" -> "no_document"
            else -> null
        }
    }

    /** "#RRGGBB" / "RRGGBB" / "black" -> ARGB; casi negro -> null (el renderizador usa negro por defecto). */
    internal fun parseColor(s: String): Int? {
        val t = s.trim().lowercase()
        val named = mapOf("black" to "000000", "blue" to "1f3fbf", "red" to "c62828", "green" to "2e7d32", "gray" to "666666", "grey" to "666666")
        val hex = named[t] ?: t.removePrefix("#").takeIf { it.length == 6 && it.all { c -> c in "0123456789abcdef" } } ?: return null
        val rgb = hex.toInt(16)
        val r = rgb shr 16 and 0xff; val g = rgb shr 8 and 0xff; val b = rgb and 0xff
        if (maxOf(r, g, b) < 70) return null
        return (0xff shl 24) or rgb
    }

    private fun stripMathDelims(s: String): String {
        var t = s.trim()
        for ((a, b) in listOf("$$" to "$$", "\\[" to "\\]", "\\(" to "\\)", "$" to "$")) {
            if (t.length > a.length + b.length && t.startsWith(a) && t.endsWith(b)) { t = t.substring(a.length, t.length - b.length).trim(); break }
        }
        return t
    }

    private fun cleanText(s: String): String = s.replace("\r\n", "\n").replace(' ', ' ').trimEnd()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNullSafe()
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.content.trim().toIntOrNull() }
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.equals("true", true).takeIf { b -> b } }
    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
    private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.contentOrNullSafe().orEmpty()

    private val ALIGNS = setOf("left", "center", "right", "justify")
    private val SIZES = setOf("small", "normal", "large", "xlarge")
    private const val MAX_TABLE = 200
}
