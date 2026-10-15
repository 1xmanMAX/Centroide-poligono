package com.scannerpromax.ai.cloud

import com.scannerpromax.ai.parse.IaText

/**
 * Instrucciones (en inglés, para el modelo; el documento puede estar en cualquier idioma) y JSON Schema de la salida.
 * El mismo prompt para los dos proveedores: Claude recibe además el esquema como structured output estricto
 * (`output_config.format`); DeepSeek no admite esquemas (solo `response_format: json_object`), así que el esquema va
 * en el propio prompt.
 */
object CloudPrompt {

    val BLOCK_TYPES = listOf("title", "text", "handwriting", "table", "chart", "formula", "figure", "header", "footer")

    val SYSTEM: String = """
You are a document transcription and layout engine. You receive ONE image of a document page (photo or scan, any language) and return ONLY a JSON object describing it, so the page can be searched and rebuilt with the same layout.

Transcription rules (most important):
- Copy every piece of text EXACTLY as written: same language, spelling, accents, capitalization, punctuation, numbers, currency symbols and line breaks. Never translate, correct, normalize, summarize, complete or invent. Keep spelling mistakes. Use "?" for characters you cannot read.
- Include all text: headings, body, handwriting, numbers, table contents, labels, stamps, page numbers, headers and footers.

Layout rules:
- "blocks" in natural reading order (multi-column pages: finish a column before the next).
- "bbox" = [x0, y0, x1, y1], integers 0-1000 relative to the image width and height, origin top-left, tight around the ink.
- type: "title" (headings), "text" (printed paragraphs, list items, labels), "handwriting" (handwritten text), "table", "chart" (plots with data), "formula" (math), "figure" (photos, drawings, logos, signatures, stamps, arrows; bbox only), "header"/"footer" (running page header/footer, page numbers).
- "lines": one entry per VISUAL line of the block, top to bottom, each with its own bbox. Do not merge or re-wrap lines, do not join hyphenated words. Tables: lines = [] (use cells). Figures: lines only for text printed inside them.
- "style": bold, italic, align (left/center/right/justify), size relative to the body text (small/normal/large/xlarge), color = ink color as "#RRGGBB" ("#000000" for black). null for figures.
- "table": every cell with zero-based row/col, row_span/col_span for merged cells, exact text ("" if empty). Include header rows. null if not a table.
- "chart": kind, title, header (column names) and rows with the data values read from the chart, as strings. null if not a chart.
- "latex": LaTeX (no $ delimiters) for formulas, null otherwise.
- "doc_type": one of ${IaText.DOC_CLASSES.joinToString(", ")} ("printed" for ordinary printed documents, "handwritten" for handwritten notes).
""".trim()

    const val USER = "Transcribe this page into the JSON object described above."

    /** Recordatorio con el esquema para proveedores sin structured outputs (DeepSeek: JSON Output exige la palabra "json"). */
    val SCHEMA_REMINDER: String get() = "Output must be valid json that follows this JSON Schema exactly:\n$SCHEMA_JSON"

    /**
     * JSON Schema estricto (compatible con structured outputs de Claude: todos los campos requeridos,
     * additionalProperties false, opcionales como anyOf con null, sin restricciones numéricas).
     */
    val SCHEMA_JSON: String = """
{"type":"object","additionalProperties":false,"required":["doc_type","blocks"],"properties":{
"doc_type":{"type":"string","enum":[${IaText.DOC_CLASSES.joinToString(",") { "\"$it\"" }}]},
"blocks":{"type":"array","items":{"type":"object","additionalProperties":false,
"required":["type","bbox","lines","style","table","chart","latex"],"properties":{
"type":{"type":"string","enum":[${BLOCK_TYPES.joinToString(",") { "\"$it\"" }}]},
"bbox":{"type":"array","items":{"type":"integer"}},
"lines":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["text","bbox"],"properties":{
"text":{"type":"string"},"bbox":{"type":"array","items":{"type":"integer"}}}}},
"style":{"anyOf":[{"type":"null"},{"type":"object","additionalProperties":false,"required":["bold","italic","align","size","color"],"properties":{
"bold":{"type":"boolean"},"italic":{"type":"boolean"},
"align":{"type":"string","enum":["left","center","right","justify"]},
"size":{"type":"string","enum":["small","normal","large","xlarge"]},
"color":{"type":"string"}}}]},
"table":{"anyOf":[{"type":"null"},{"type":"object","additionalProperties":false,"required":["rows","cols","cells"],"properties":{
"rows":{"type":"integer"},"cols":{"type":"integer"},
"cells":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["row","col","row_span","col_span","text"],"properties":{
"row":{"type":"integer"},"col":{"type":"integer"},"row_span":{"type":"integer"},"col_span":{"type":"integer"},"text":{"type":"string"}}}}}}]},
"chart":{"anyOf":[{"type":"null"},{"type":"object","additionalProperties":false,"required":["kind","title","header","rows"],"properties":{
"kind":{"type":"string","enum":["bar","line","pie","scatter","other"]},"title":{"type":"string"},
"header":{"type":"array","items":{"type":"string"}},
"rows":{"type":"array","items":{"type":"array","items":{"type":"string"}}}}}]},
"latex":{"anyOf":[{"type":"null"},{"type":"string"}]}
}}}}}
""".trim()
}
