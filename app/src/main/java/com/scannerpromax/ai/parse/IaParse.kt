package com.scannerpromax.ai.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Parsers de las salidas estructuradas de PaddleOCR-VL-1.5 (Spotting, tablas OTSL, gráficos, fórmulas) y de Qwen3.5
 * (JSON con bbox_2d / point_2d). Puerto fiel de tools/ia_parse.py del banco IA (mismos tests con salidas reales).
 * Coordenadas del modelo: enteros 0..1000 normalizados al ancho (x) y alto (y) de la imagen enviada.
 */
object IaParse {

    // ------------------------------------------------------------------------------------------ Spotting

    /**
     * Fragmento de texto de "Spotting:". [quad] = vértices TL, TR, BR, BL (0..1000; menos de 4 si el modelo dio menos
     * coordenadas; null si no dio ninguna), [box] = envolvente x0, y0, x1, y1 (0..1000).
     */
    data class SpotItem(val text: String, val quad: List<FloatArray>?, val box: FloatArray?) {
        /** Vértices en píxeles de una imagen [w] x [h]. */
        fun quadPx(w: Int, h: Int): List<FloatArray>? = quad?.map { floatArrayOf(it[0] * w / 1000f, it[1] * h / 1000f) }
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val LOC_RE = Regex("<\\|LOC_(\\d+)\\|>")
    private val SPOT_FRAG = Regex("((?:(?!<\\|LOC_)[\\s\\S])*?)((?:<\\|LOC_\\d+\\|>\\s*)+)")

    /**
     * Salida "Spotting:" detokenizada con special=true: una línea por fragmento `TEXTO<|LOC_x1|><|LOC_y1|>…<|LOC_y4|>`.
     * Tolera `</s>`, entidades HTML, líneas sin LOC (quad null), 4 LOC (caja), 2 LOC (punto), valores > 1000 (se
     * recortan) y bucles dentro del texto.
     */
    fun parseSpotting(raw: String): List<SpotItem> {
        val txt = raw.replace("</s>", "").replace("<|end_of_sentence|>", "")
        val out = ArrayList<SpotItem>()
        var consumed = 0
        for (m in SPOT_FRAG.findAll(txt)) {
            if (m.range.first != consumed) break  // (los fragmentos son contiguos)
            consumed = m.range.last + 1
            val text = IaText.stripRepetition(IaText.htmlUnescape(m.groupValues[1]).trim())
            val vals = LOC_RE.findAll(m.groupValues[2]).map { min(1000, it.groupValues[1].toInt()).toFloat() }.toList()
            if (text.isEmpty() && vals.isEmpty()) continue
            val pts: List<FloatArray>? = when {
                vals.size >= 8 -> (0 until 8 step 2).map { floatArrayOf(vals[it], vals[it + 1]) }
                vals.size == 4 -> {
                    val (x0, y0, x1, y1) = vals
                    listOf(floatArrayOf(x0, y0), floatArrayOf(x1, y0), floatArrayOf(x1, y1), floatArrayOf(x0, y1))
                }
                vals.size >= 2 -> (0 until vals.size - 1 step 2).map { floatArrayOf(vals[it], vals[it + 1]) }
                else -> null
            }
            val box = pts?.let { p ->
                floatArrayOf(p.minOf { it[0] }, p.minOf { it[1] }, p.maxOf { it[0] }, p.maxOf { it[1] })
            }
            out.add(SpotItem(text, pts, box))
        }
        val tail = IaText.stripRepetition(IaText.htmlUnescape(LOC_RE.replace(txt.substring(consumed), "")).trim())
        if (tail.isNotEmpty()) out.add(SpotItem(tail, null, null))
        return out
    }

    /**
     * Bucles ENTRE fragmentos del Spotting (cuadrícula de cuaderno: "1", "2", "3"… cada uno con su caja; la misma línea
     * repetida): misma secuencia de reglas que [IaText.stripRepetition] aplicada a los textos de los fragmentos.
     */
    fun filterSpotLoops(items: List<SpotItem>, maxRepeats: Int = 3, maxCounterRun: Int = 12): List<SpotItem> {
        var out = ArrayList<SpotItem>()
        var prev: String? = null
        var cnt = 0
        for (it in items) {
            val k = it.text.trim()
            if (k.isNotEmpty() && k == prev) {
                cnt++
                if (cnt >= maxRepeats) continue
            } else { prev = k; cnt = 0 }
            out.add(it)
        }
        val num = Regex("^\\(?\\d{1,4}(?:[.,]\\d{1,3})?\\)?\\.?$")
        var runStart = 0; var runLen = 0; var last: Double? = null
        for ((j, it) in out.withIndex()) {
            val l = it.text.trim()
            if (num.matches(l)) {
                val v = l.replace(',', '.').filter { c -> c.isDigit() || c == '.' }.trim('.').toDoubleOrNull() ?: 0.0
                if (last != null && v > last) runLen++ else { runStart = j; runLen = 1 }
                last = v
                if (runLen > maxCounterRun) { out = ArrayList(out.subList(0, runStart)); break }
            } else { last = null; runLen = 0 }
        }
        val digits = Regex("\\d+")
        val res = ArrayList<SpotItem>()
        var prevN: String? = null; var cntN = 0
        for (it in out) {
            val k = digits.replace(it.text.trim(), "#")
            if (k == prevN && it.text.any { c -> c.isDigit() }) {
                cntN++
                if (cntN >= 3) continue
            } else { prevN = k; cntN = 0 }
            res.add(it)
        }
        return if (res.size < out.size - 5) res else out
    }

    /** Ángulo (grados, horario en coordenadas de imagen) del borde superior de un cuadrilátero en píxeles. */
    fun quadAngle(q: List<FloatArray>): Float {
        if (q.size < 2) return 0f
        val dx = q[1][0] - q[0][0]
        val dy = q[1][1] - q[0][1]
        if (dx * dx + dy * dy < 1e-6f) return 0f
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    // ------------------------------------------------------------------------------------------ bloques de texto

    /** Bloque de párrafo a partir de líneas de Spotting. [bbox] en 0..1000; [items] en orden. */
    class TextBlock(var bbox: FloatArray, val items: MutableList<SpotItem>, var lineH: Float) {
        val lines: List<String> get() = items.map { it.text }
    }

    /**
     * Agrupa líneas de [parseSpotting] (0..1000) en párrafos: una línea se une al bloque cuyo último renglón está justo
     * encima (hueco <= [gapFactor]·altura mediana), con solape horizontal >= [minXOverlap] del más estrecho y altura
     * parecida (0,6..1,6). Devuelve los bloques en orden de lectura ([readingOrder]).
     */
    fun spottingToBlocks(items: List<SpotItem>, gapFactor: Float = 1.2f, minXOverlap: Float = 0.3f): List<TextBlock> {
        val lines = items.filter { it.box != null }
        if (lines.isEmpty()) return emptyList()
        val hs = lines.map { max(1f, it.box!![3] - it.box[1]) }.sorted()
        val hmed = hs[hs.size / 2]
        val sorted = lines.sortedWith(compareBy({ it.box!![1] }, { it.box!![0] }))
        class B(val tb: TextBlock, var last: FloatArray, val hs: MutableList<Float>)
        val blocks = ArrayList<B>()
        for (it in sorted) {
            val (x1, y1, x2, y2) = it.box!!.toList()
            val h = max(1f, y2 - y1)
            var best: Pair<Float, B>? = null
            for (b in blocks) {
                val (lx1, ly1, lx2, ly2) = b.last.toList()
                val gap = y1 - ly2
                val lh = max(1f, ly2 - ly1)
                val ov = min(x2, lx2) - max(x1, lx1)
                val narrow = max(1f, min(x2 - x1, lx2 - lx1))
                if (gap >= -0.5f * lh && gap <= gapFactor * max(hmed, lh) && ov / narrow >= minXOverlap && h / lh in 0.6f..1.6f) {
                    if (best == null || gap < best.first) best = gap to b
                }
            }
            if (best != null) {
                val b = best.second
                b.tb.items.add(it); b.last = it.box
                val bb = b.tb.bbox
                b.tb.bbox = floatArrayOf(min(bb[0], x1), min(bb[1], y1), max(bb[2], x2), max(bb[3], y2))
                b.hs.add(h)
            } else {
                blocks.add(B(TextBlock(it.box.copyOf(), mutableListOf(it), h), it.box, mutableListOf(h)))
            }
        }
        for (b in blocks) b.tb.lineH = b.hs.sorted()[b.hs.size / 2]
        return readingOrder(blocks.map { it.tb }) { it.bbox }
    }

    /**
     * Orden de lectura sobre cajas 0..1000: si hay 2 columnas (bloques estrechos < 48 % a ambos lados de x = 500 que
     * se solapan en vertical y suman >= 40 %), columna izquierda y luego derecha, con los bloques anchos como
     * separadores; si no, de arriba a abajo y de izquierda a derecha.
     */
    fun <T> readingOrder(blocks: List<T>, box: (T) -> FloatArray): List<T> {
        val l = blocks.filter { val b = box(it); b[2] <= 520 && b[2] - b[0] < 480 }
        val r = blocks.filter { val b = box(it); b[0] >= 480 && b[2] - b[0] < 480 }
        val two = l.size >= 2 && r.size >= 2 && (l.size + r.size) >= 0.4 * blocks.size &&
            l.any { a -> r.any { c -> min(box(a)[3], box(c)[3]) > max(box(a)[1], box(c)[1]) } }
        if (!two) return blocks.sortedWith(compareBy({ Math.rint(box(it)[1] / 8.0) }, { box(it)[0] }))
        val lSet = l.toIdentitySet(); val rSet = r.toIdentitySet()
        val wide = blocks.filter { it !in lSet && it !in rSet }.sortedBy { box(it)[1] }
        val out = ArrayList<T>()
        val added = java.util.IdentityHashMap<T, Boolean>()
        fun add(x: T) { if (added.put(x, true) == null) out.add(x) }
        var prev = -1f
        for (cut in wide.map { box(it)[1] } + 1e9f) {
            fun seg(col: List<T>) = col.filter { val y = box(it)[1]; prev <= y && y < cut }.sortedBy { box(it)[1] }
            seg(l).forEach(::add); seg(r).forEach(::add)
            wide.firstOrNull { box(it)[1] == cut && it !in added }?.let(::add)
            prev = cut
        }
        blocks.forEach(::add)
        return out
    }

    private fun <T> List<T>.toIdentitySet(): Set<T> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>()).also { it.addAll(this) }

    // ------------------------------------------------------------------------------------------ tablas (OTSL)

    data class OtslCell(val r: Int, val c: Int, var rowSpan: Int, var colSpan: Int, var text: String)

    data class OtslTable(val rows: List<List<String>>, val cells: List<OtslCell>, val html: String, val nRows: Int, val nCols: Int)

    private val OTSL_TOK = Regex("<(fcel|ecel|lcel|ucel|xcel|nl)>")

    /**
     * OTSL -> rejilla con spans. `<fcel>texto` celda, `<ecel>` vacía, `<lcel>` se une a la izquierda, `<ucel>` a la de
     * arriba, `<xcel>` ambas, `<nl>` fin de fila. Tolera `</s>`, texto antes del primer token, filas de longitud
     * distinta (se rellenan), bucles de `<lcel>` (máx. [maxCols] por fila) y última fila sin `<nl>`.
     */
    fun parseOtsl(raw: String, maxCols: Int = 64, maxRows: Int = 500): OtslTable {
        val txt = raw.replace("</s>", "").trim()
        val grid = ArrayList<MutableList<Pair<String, String>>>()
        var row = ArrayList<Pair<String, String>>()
        val ms = OTSL_TOK.findAll(txt).toList()
        val pre = (if (ms.isEmpty()) txt else txt.substring(0, ms[0].range.first)).trim()
        if (pre.isNotEmpty()) row.add("fcel" to pre)
        for ((i, m) in ms.withIndex()) {
            val tok = m.groupValues[1]
            val end = if (i + 1 < ms.size) ms[i + 1].range.first else txt.length
            val text = txt.substring(m.range.last + 1, end)
            if (tok == "nl") {
                grid.add(row); row = ArrayList()
                if (grid.size >= maxRows) break
                continue
            }
            if (row.size < maxCols) row.add(tok to IaText.htmlUnescape(text.trim()))
        }
        if (row.isNotEmpty()) grid.add(row)
        val nCol = grid.maxOfOrNull { it.size } ?: 0
        for (r in grid) while (r.size < nCol) r.add("ecel" to "")
        val owner = Array(grid.size) { IntArray(nCol) { -1 } }
        val cells = ArrayList<OtslCell>()
        for ((ri, r) in grid.withIndex()) {
            for ((ci, cell) in r.withIndex()) {
                val (tok, text) = cell
                val origin = tok == "fcel" || tok == "ecel" || (tok == "lcel" && ci == 0) || (tok == "ucel" && ri == 0) ||
                    (tok == "xcel" && (ri == 0 || ci == 0))
                if (origin) {
                    cells.add(OtslCell(ri, ci, 1, 1, text)); owner[ri][ci] = cells.size - 1
                } else {
                    val o = when (tok) {
                        "lcel" -> owner[ri][ci - 1]
                        "ucel" -> owner[ri - 1][ci]
                        else -> owner[ri - 1][ci - 1]
                    }
                    owner[ri][ci] = o
                    if (o < 0) continue
                    val cl = cells[o]
                    cl.colSpan = max(cl.colSpan, ci - cl.c + 1)
                    cl.rowSpan = max(cl.rowSpan, ri - cl.r + 1)
                    if (text.isNotEmpty()) cl.text = (cl.text + " " + text).trim()
                }
            }
        }
        val rows = List(grid.size) { MutableList(nCol) { "" } }
        for (cl in cells) rows[cl.r][cl.c] = cl.text
        val h = StringBuilder("<table>")
        for (ri in grid.indices) {
            h.append("<tr>")
            for (cl in cells) if (cl.r == ri) {
                val span = (if (cl.colSpan > 1) " colspan=\"${cl.colSpan}\"" else "") + (if (cl.rowSpan > 1) " rowspan=\"${cl.rowSpan}\"" else "")
                h.append("<td").append(span).append('>').append(IaText.htmlEscape(cl.text)).append("</td>")
            }
            h.append("</tr>")
        }
        h.append("</table>")
        return OtslTable(rows, cells, h.toString(), grid.size, nCol)
    }

    /**
     * true si [text] (en streaming) contiene, en su final, más de [limit] tokens OTSL iguales seguidos sin texto entre
     * ellos (bucle de `<lcel>`, `<ecel>`…: el modelo se atasca tras la fila del título).
     */
    fun otslLoop(text: String, limit: Int = 64): Boolean {
        val window = limit * 8 + 64
        val tail = if (text.length > window) text.substring(text.length - window) else text
        var run = 0
        var last: String? = null
        var lastEnd = -1
        for (m in OTSL_TOK.findAll(tail)) {
            val gapBlank = lastEnd >= 0 && tail.substring(lastEnd, m.range.first).isBlank()
            if (m.value == last && gapBlank) run++ else { last = m.value; run = 1 }
            lastEnd = m.range.last + 1
            if (run > limit) return true
        }
        return false
    }

    // ------------------------------------------------------------------------------------------ gráficos

    data class Chart(val header: List<String>, val rows: List<List<String>>)

    private val CHART_RULE = Regex("\\|?\\s*:?-{2,}.*")

    /** Tabla `a | b` por filas (1ª = cabecera; esquina vacía si empieza por "| "); quita separadores y filas repetidas. */
    fun parseChart(raw: String): Chart {
        val txt = IaText.stripRepetition(raw.replace("</s>", "").trim())
        val lines = ArrayList<List<String>>()
        for (l0 in txt.split('\n')) {
            var l = l0.trim()
            if (l.isEmpty() || CHART_RULE.matches(l)) continue
            if (l.startsWith("|") && l.endsWith("|") && l.length > 1) l = l.substring(1, l.length - 1)
            var cells = l.split('|').map { it.trim() }
            if (l.startsWith("|")) cells = listOf("") + l.substring(1).split('|').map { it.trim() }
            if (lines.isNotEmpty() && cells == lines.last()) continue
            lines.add(cells)
        }
        if (lines.isEmpty()) return Chart(emptyList(), emptyList())
        return Chart(lines[0], lines.drop(1))
    }

    private val NUM_CHARS = Regex("[-+]?[\\d.,]+")
    private val DEC_COMMA = Regex("[-+]?\\d+,\\d{1,2}")

    /** "1.234,5" / "1,234.5" / "40%" / "$ 20" / "(539)" -> número o null. */
    fun toNumber(s0: String): Double? {
        var s = s0.trim().replace('−', '-').replace("—", "").replace("$", "").replace("€", "")
            .replace("%", "").replace(" ", "")
        val neg = s.startsWith("(") && s.endsWith(")")
        s = s.trim('(', ')')
        if (s.isEmpty() || !NUM_CHARS.matches(s)) return null
        if (',' in s && '.' in s) {
            s = if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
        } else if (',' in s) {
            s = if (DEC_COMMA.matches(s)) s.replace(',', '.') else s.replace(",", "")
        }
        val v = s.toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    // ------------------------------------------------------------------------------------------ fórmulas

    data class Formula(val latex: String, val tag: String?)

    private val FORMULA_DELIM = Regex("\\\\\\[(.*)\\\\\\]|\\$\\$(.*)\\$\\$|\\\\\\((.*)\\\\\\)|\\$(.*)\\$", RegexOption.DOT_MATCHES_ALL)
    private val FORMULA_TAG = Regex("\\\\(?:eqno|tag)\\s*\\{?\\(?([^)}]*)\\)?\\}?\\s*$")
    private val FORMULA_QQUAD = Regex("\\\\qquad\\s*\\((\\w{1,4})\\)\\s*$")

    /** LaTeX sin delimitadores (\[..\], $$..$$, $..$, \(..\)); \eqno(n) / \tag{n} / \qquad(n) se devuelven aparte. */
    fun parseFormula(raw: String): Formula {
        var t = raw.replace("</s>", "").trim()
        FORMULA_DELIM.matchEntire(t)?.let { m -> t = m.groups.drop(1).first { it != null }!!.value.trim() }
        var tag: String? = null
        val mt = FORMULA_TAG.find(t)
        if (mt != null) {
            tag = mt.groupValues[1]; t = t.substring(0, mt.range.first).trim()
        } else {
            FORMULA_QQUAD.find(t)?.let { mq -> tag = mq.groupValues[1]; t = t.substring(0, mq.range.first).trim() }
        }
        return Formula(t, tag)
    }

    // ------------------------------------------------------------------------------------------ Qwen: JSON

    val LAYOUT_TYPES = listOf("title", "text", "table", "figure", "chart", "formula", "handwriting", "header", "footer")
    private val TYPE_ALIASES = mapOf(
        "paragraph" to "text", "plain text" to "text", "list" to "text", "list-item" to "text", "caption" to "text",
        "footnote" to "footer", "section-header" to "title", "heading" to "title", "section" to "title",
        "page-header" to "header", "page-footer" to "footer", "image" to "figure", "picture" to "figure",
        "photo" to "figure", "logo" to "figure", "graph" to "chart", "plot" to "chart", "equation" to "formula",
        "math" to "formula", "handwritten" to "handwriting", "signature" to "handwriting", "stamp" to "figure", "seal" to "figure",
    )

    private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }
    private val TRAILING_COMMA = Regex(",\\s*([\\]}])")
    private val LOOSE_OBJ = Regex("\\{[^{}]*\\}")

    private fun parseJson(s: String): JsonElement? = try { JSON.parseToJsonElement(s) } catch (_: Throwable) { null }

    /** Extrae el JSON aunque venga con ```json, texto alrededor, comas finales, comillas simples o truncado. */
    internal fun jsonCandidates(txt: String): List<JsonElement> {
        var t = IaText.stripCodeFence(IaText.stripThink(txt))
        val starts = listOf(t.indexOf('['), t.indexOf('{')).filter { it >= 0 }
        if (starts.isEmpty()) return emptyList()
        t = t.substring(starts.min())
        t = TRAILING_COMMA.replace(t, "\$1")
        val alt = if ('}' in t) t.substring(0, t.lastIndexOf('}') + 1) + "]" else t
        for (cand in listOf(t, "$t]", "$t}]", alt)) {
            val v = parseJson(cand) ?: continue
            return if (v is JsonArray) v.toList() else listOf(v)
        }
        val out = ArrayList<JsonElement>()
        for (m in LOOSE_OBJ.findAll(t)) {
            (parseJson(m.value) ?: parseJson(m.value.replace('\'', '"')))?.let { out.add(it) }
        }
        return out
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.content

    private fun box(v: JsonElement?): FloatArray? {
        val a = v as? JsonArray ?: return null
        if (a.size < 4) return null
        val n = a.take(4).map { num(it) ?: return null }
        val (x1, x2) = listOf(n[0], n[2]).sorted()
        val (y1, y2) = listOf(n[1], n[3]).sorted()
        fun c(x: Double) = x.coerceIn(0.0, 1000.0).toFloat()
        return floatArrayOf(c(x1), c(y1), c(x2), c(y2))
    }

    data class LayoutBox(val type: String, val bbox: FloatArray)

    /**
     * Qwen3.5 bbox_2d -> lista de {type, bbox 0..1000}. Claves bbox_2d|bbox|box y label|type|category; tipos
     * normalizados (desconocido -> text), descarta cajas degeneradas y duplicados (IoU >= [dedupIou] con mismo tipo).
     */
    fun parseLayoutJson(txt: String, minSize: Float = 3f, dedupIou: Float = 0.9f): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        for (o in jsonCandidates(txt)) {
            if (o !is JsonObject) continue
            val b = box(o["bbox_2d"] ?: o["bbox"] ?: o["box"]) ?: continue
            if (b[2] - b[0] < minSize || b[3] - b[1] < minSize) continue
            val t0 = (str(o["label"]) ?: str(o["type"]) ?: str(o["category"]) ?: "text").trim().lowercase()
            val t = if (t0 in LAYOUT_TYPES) t0 else TYPE_ALIASES[t0] ?: "text"
            if (out.any { iou(b, it.bbox) >= dedupIou && it.type == t }) continue
            out.add(LayoutBox(t, b))
        }
        return out
    }

    fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = ix * iy
        val u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (u > 0) inter / u else 0f
    }

    data class LabeledPoint(val label: String, val pt: FloatArray)

    /** Puntos de Qwen: {"point_2d":[x,y],"label":..}, {"x":..,"y":..}, [[x,y],…], {"top-left":[x,y],…}, bbox_2d (centro). */
    fun parsePoints(txt: String): List<LabeledPoint> {
        val out = ArrayList<LabeledPoint>()
        fun pair(v: JsonElement?): FloatArray? {
            val a = v as? JsonArray ?: return null
            if (a.size < 2) return null
            val x = num(a[0]) ?: return null
            val y = num(a[1]) ?: return null
            return floatArrayOf(x.toFloat(), y.toFloat())
        }
        for (o in jsonCandidates(txt)) {
            when (o) {
                is JsonObject -> {
                    val lab = (str(o["label"]) ?: str(o["name"]) ?: str(o["corner"]) ?: "").trim().lowercase()
                    val p2 = pair(o["point_2d"])
                    if (p2 != null) { out.add(LabeledPoint(lab, p2)); continue }
                    if (o.containsKey("x") && o.containsKey("y")) {
                        val x = num(o["x"]); val y = num(o["y"])
                        if (x != null && y != null) { out.add(LabeledPoint(lab, floatArrayOf(x.toFloat(), y.toFloat()))); continue }
                    }
                    val bb = o["bbox_2d"]
                    if (bb != null && box(bb) != null) {
                        val a = (bb as JsonArray).map { num(it)!!.toFloat() }
                        out.add(LabeledPoint(lab, floatArrayOf((a[0] + a[2]) / 2f, (a[1] + a[3]) / 2f))); continue
                    }
                    for ((k, v) in o) {
                        if (v is JsonArray && v.size in 2..3 && v.take(2).all { num(it) != null }) {
                            out.add(LabeledPoint(k.lowercase(), floatArrayOf(num(v[0])!!.toFloat(), num(v[1])!!.toFloat())))
                        } else if (v is JsonArray && v.isNotEmpty() && v.all { p -> pair(p) != null }) {
                            v.forEach { p -> out.add(LabeledPoint("", pair(p)!!)) }
                        }
                    }
                }
                is JsonArray -> pair(o)?.let { out.add(LabeledPoint("", it)) }
                else -> {}
            }
        }
        return out
    }

    /** 4 puntos -> TL, TR, BR, BL (por ángulo alrededor del centroide; TL = menor x+y). null si hay < 4 distintos. */
    fun orderQuad(pts: List<FloatArray>): List<FloatArray>? {
        val uniq = ArrayList<FloatArray>()
        for (p in pts) if (uniq.all { q -> kotlin.math.abs(p[0] - q[0]) + kotlin.math.abs(p[1] - q[1]) > 1e-6f }) uniq.add(p)
        if (uniq.size < 4) return null
        val q = uniq.take(4).toMutableList()
        val cx = q.sumOf { it[0].toDouble() } / 4
        val cy = q.sumOf { it[1].toDouble() } / 4
        q.sortBy { atan2(it[1] - cy, it[0] - cx) }
        val k = (0 until 4).minBy { q[it][0] + q[it][1] }
        return q.subList(k, 4) + q.subList(0, k)
    }

    fun quadArea(q: List<FloatArray>): Float {
        var s = 0f
        for (i in 0 until 4) s += q[i][0] * q[(i + 1) % 4][1] - q[(i + 1) % 4][0] * q[i][1]
        return kotlin.math.abs(s) / 2f
    }

    private val CORNER_KEYS = mapOf(
        "tl" to 0, "top-left" to 0, "top left" to 0, "topleft" to 0, "upper-left" to 0, "upper left" to 0,
        "tr" to 1, "top-right" to 1, "top right" to 1, "topright" to 1, "upper-right" to 1, "upper right" to 1,
        "br" to 2, "bottom-right" to 2, "bottom right" to 2, "bottomright" to 2, "lower-right" to 2, "lower right" to 2,
        "bl" to 3, "bottom-left" to 3, "bottom left" to 3, "bottomleft" to 3, "lower-left" to 3, "lower left" to 3,
    )

    /** Usa las etiquetas (top-left…) si están; si no, [orderQuad]. null si degenerado (puntos repetidos/colineales). */
    fun cornersFromLabels(items: List<LabeledPoint>): List<FloatArray>? {
        val res = arrayOfNulls<FloatArray>(4)
        for (it in items) {
            val l = it.label.replace('_', '-').replace(" corner", "").trim()
            val k = CORNER_KEYS[l] ?: continue
            if (res[k] == null) res[k] = it.pt
        }
        fun degenerate(q: List<FloatArray>): Boolean {
            val m = max(1f, q.maxOf { max(kotlin.math.abs(it[0]), kotlin.math.abs(it[1])) })
            return quadArea(q) < 1e-4f * m * m
        }
        if (res.all { it != null }) {
            val r = res.map { it!! }
            val oq = orderQuad(r) ?: return null
            if (degenerate(r)) return null
            return oq
        }
        val q = orderQuad(items.map { it.pt }) ?: return null
        return if (degenerate(q)) null else q
    }
}
