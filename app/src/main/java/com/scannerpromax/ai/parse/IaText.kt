package com.scannerpromax.ai.parse

import kotlin.math.ceil
import kotlin.math.max

/**
 * Limpieza de las salidas de texto de PaddleOCR-VL-1.5 y Qwen3.5-0.8B (puerto de tools/ia_parse.py del banco IA,
 * mismas reglas y mismos tests con salidas reales). Lógica pura: se prueba en la JVM.
 */
object IaText {

    // ------------------------------------------------------------------------------------------ utilidades comunes

    private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    /** Quita bloques <think>…</think> (Qwen3.5 con razonamiento) y un </think> suelto inicial. */
    fun stripThink(txt: String): String {
        var t = THINK_BLOCK.replace(txt, "")
        val i = t.indexOf("</think>")
        if (i >= 0) t = t.substring(i + "</think>".length)
        return t.trim()
    }

    private val FENCE = Regex("```(?:json|html|latex|markdown|md|text)?\\s*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val OPEN_FENCE = Regex("^```\\w*\\s*")

    fun stripCodeFence(txt: String): String {
        FENCE.find(txt)?.let { return it.groupValues[1].trim() }
        return OPEN_FENCE.replace(txt.trim(), "")  // valla abierta sin cerrar (salida truncada)
    }

    /** Entidades HTML más comunes (&amp; &lt; &#39; &#x41; …), como html.unescape para lo que sale en la práctica. */
    fun htmlUnescape(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp(it) } ?: m.value
                else -> NAMED[e] ?: m.value
            }
        }
    }

    private fun cp(code: Int): String =
        if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else "�"

    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#\\d{1,7}|[a-zA-Z]{2,8});")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "euro" to "€", "copy" to "©", "reg" to "®", "deg" to "°", "middot" to "·", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "laquo" to "«", "raquo" to "»", "iexcl" to "¡", "iquest" to "¿", "times" to "×", "divide" to "÷",
    )

    fun htmlEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#x27;")
            else -> append(c)
        }
    }

    // ------------------------------------------------------------------------------------------ bucles

    private val NUM_LINE = Regex("^\\(?\\d{1,4}(?:[.,]\\d{1,3})?\\)?\\.?$")
    private val DIGITS = Regex("\\d+")
    private val HAS_DIGIT = Regex("\\d")
    private val INLINE_COUNTER = Regex("(?:(?<=\\s)|^)(\\d{1,5})[.,)]?(?:\\s+(\\d{1,5})[.,)]?){12,}")
    private val NUM_TOKEN = Regex("\\d{1,5}")

    /**
     * Corta bucles típicos de temperatura 0 (también sirve para detectarlos en streaming y abortar):
     *  1. la misma línea (no vacía) repetida > [maxLineRepeats] veces seguidas (se conservan [maxLineRepeats]);
     *  2. un sufijo formado por un bloque de p caracteres repetido >= max(4, ceil(24/p)) veces (p = 1..200; "1. 1. 1.");
     *  3. una racha de > [maxCounterRun] líneas que solo son números crecientes (1, 2, 3… o 19.00, 19.30…): se trunca al
     *     principio de la racha (Paddle "cuenta" las casillas de la cuadrícula);
     *  2b. la misma línea salvo los dígitos ("12. 15", "13. 15"…) repetida: se dejan 3;
     *  3b. contador en línea "359. 360. 361. …" (> 12 números consecutivos): se corta.
     */
    fun stripRepetition(txt: String, maxLineRepeats: Int = 3, maxCounterRun: Int = 12): String {
        var out = ArrayList<String>()
        var prev: String? = null
        var cnt = 0
        for (line in txt.split('\n')) {
            val k = line.trim()
            if (k.isNotEmpty() && k == prev) {
                cnt++
                if (cnt >= maxLineRepeats) continue
            } else {
                prev = k; cnt = 0
            }
            out.add(line)
        }
        // (3) rachas de contadores numéricos (ignorando líneas vacías)
        var cutAt: Int? = null
        var runStart = 0
        var runLen = 0
        var last: Double? = null
        for ((j, raw) in out.withIndex()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (NUM_LINE.matches(l)) {
                val v = l.replace(',', '.').filter { it.isDigit() || it == '.' }.trim('.').toDoubleOrNull() ?: 0.0
                if (last != null && v > last) runLen++ else { runStart = j; runLen = 1 }
                last = v
                if (runLen > maxCounterRun) { cutAt = runStart; break }
            } else {
                last = null; runLen = 0
            }
        }
        if (cutAt != null) out = ArrayList(out.subList(0, cutAt))
        // (2b) misma línea salvo los dígitos repetida -> se dejan 3
        val res2 = ArrayList<String>()
        var prevN: String? = null
        var cntN = 0
        for (line in out) {
            val k = DIGITS.replace(line.trim(), "#")
            if (k.isEmpty()) { res2.add(line); continue }
            if (k == prevN && HAS_DIGIT.containsMatchIn(line)) {
                cntN++
                if (cntN >= 3) continue
            } else {
                prevN = k; cntN = 0
            }
            res2.add(line)
        }
        if (res2.size < out.size - 5) out = res2
        var t = out.joinToString("\n")
        // (3b) contador en línea
        for (m in INLINE_COUNTER.findAll(t)) {
            val nums = NUM_TOKEN.findAll(m.value).map { it.value.toInt() }.toList()
            if (nums.zipWithNext().all { (a, b) -> b - a == 1 }) {
                t = t.substring(0, m.range.first).trimEnd()
                break
            }
        }
        // (2) bloque repetido al final
        val tail = if (t.length > 3000) t.substring(t.length - 3000) else t
        for (p in 1..200) {
            val reps = max(4, ceil(24.0 / p).toInt())
            if (tail.length < reps * p) break
            val blk = tail.substring(tail.length - p)
            if (blk.isNotBlank() && endsWithRepeated(tail, blk, reps)) {
                var i = t.length
                while (i - p >= 0 && t.regionMatches(i - p, blk, 0, p)) i -= p
                return t.substring(0, minOf(t.length, i + p)).trimEnd()
            }
        }
        return t.trimEnd()
    }

    private fun endsWithRepeated(s: String, blk: String, reps: Int): Boolean {
        val p = blk.length
        var end = s.length
        repeat(reps) {
            if (end - p < 0 || !s.regionMatches(end - p, blk, 0, p)) return false
            end -= p
        }
        return true
    }

    // ------------------------------------------------------------------------------------------ OCR

    private val OTSL_TOKENS = Regex("<(fcel|ecel|lcel|ucel|xcel|nl|ched|rhed|srow)>")
    private val LOC = Regex("<\\|LOC_\\d+\\|>")
    private val TD_CLOSE = Regex("</t[dh]>\\s*")
    private val TR_CLOSE = Regex("</tr>\\s*")
    private val ANY_TAG = Regex("<[^>]+>")
    private val MD_HEADING = Regex("^\\s*#{1,6}\\s*", RegexOption.MULTILINE)
    private val MD_BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val MD_RULE = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$", RegexOption.MULTILINE)
    private val MD_PIPE = Regex("\\s*\\|\\s*")
    private val TEX_TEXT = Regex("\\\\text\\{([^}]*)\\}")
    private val TEX_INLINE = Regex("\\$([^$\\n]{1,40})\\$")
    private val SPACES = Regex("[ \\t]+")
    private val BLANKS = Regex("\\n{3,}")

    /** Salida de "OCR:" (Paddle) u OCR libre (Qwen) -> texto plano por líneas. */
    fun cleanOcr(raw: String): String {
        var t = stripThink(raw.replace("</s>", "").replace("<|end_of_sentence|>", ""))
        if ("```" in t) t = stripCodeFence(t)
        t = stripRepetition(t)
        if ("<t" in t) {
            t = TD_CLOSE.replace(t, " ")
            t = TR_CLOSE.replace(t, "\n")
            t = ANY_TAG.replace(t, "")
        }
        if (OTSL_TOKENS.containsMatchIn(t)) {
            t = t.replace("<nl>", "\n")
            t = OTSL_TOKENS.replace(t, " ")
        }
        t = LOC.replace(t, "")
        t = MD_HEADING.replace(t, "")
        t = MD_BOLD.replace(t, "\$1")
        t = MD_RULE.replace(t, "")
        t = MD_PIPE.replace(t, " ")
        t = TEX_TEXT.replace(t, "\$1")
        t = TEX_INLINE.replace(t, "\$1")
        t = htmlUnescape(t)
        t = SPACES.replace(t, " ")
        t = BLANKS.replace(t, "\n\n")
        return t.trim()
    }

    /**
     * Une los textos OCR de tiras consecutivas (de arriba a abajo) quitando las líneas repetidas por el solape:
     * compara las últimas k líneas de una tira con las primeras k de la siguiente (similitud >= [thr]).
     */
    fun mergeStrips(texts: List<String>, maxOverlapLines: Int = 6, thr: Double = 0.8): String {
        fun sim(a: String, b: String): Double {
            val x = a.trim().lowercase(); val y = b.trim().lowercase()
            if (x.isEmpty() || y.isEmpty()) return 0.0
            return SeqRatio.ratio(x, y)
        }
        val out = ArrayList<String>()
        for (t in texts) {
            var lines: List<String> = cleanOcr(t).split('\n')
            if (out.isNotEmpty()) {
                var best = 0
                for (k in 1..minOf(maxOverlapLines, out.size, lines.size)) {
                    val tail = out.subList(out.size - k, out.size)
                    val head = lines.subList(0, k)
                    if (tail.zip(head).all { (x, y) -> sim(x, y) >= thr }) best = k
                }
                if (best == 0 && lines.isNotEmpty() && sim(out.last(), lines[0]) >= 0.6) best = 1
                lines = lines.drop(best)
            }
            out.addAll(lines)
        }
        return out.joinToString("\n").trim()
    }

    // ------------------------------------------------------------------------------------------ clasificación (Qwen)

    val DOC_CLASSES = listOf("receipt", "id_card", "handwritten", "whiteboard", "screen", "book", "form", "printed", "no_document")
    private val CLS_ALIASES = mapOf(
        "invoice" to "receipt", "ticket" to "receipt", "id" to "id_card", "card" to "id_card", "idcard" to "id_card",
        "id card" to "id_card", "handwriting" to "handwritten", "notes" to "handwritten", "blackboard" to "whiteboard",
        "board" to "whiteboard", "monitor" to "screen", "book page" to "book", "table" to "form", "document" to "printed",
        "letter" to "printed", "none" to "no_document", "no document" to "no_document", "nodocument" to "no_document",
        "photo" to "no_document",
    )

    /** Respuesta libre -> una clase: coincidencia exacta, luego como palabra, luego alias; si no, [default]. */
    fun parseClass(txt: String, classes: List<String> = DOC_CLASSES, default: String = "printed"): String {
        var t = stripThink(txt).trim().lowercase().trim('`', '"', '\'', '.', ' ', '\n')
        t = t.replace('-', '_')
        if (t in classes) return t
        for (c in classes.sortedByDescending { it.length }) {
            if (Regex("\\b" + Regex.escape(c) + "\\b").containsMatchIn(t)) return c
        }
        val t2 = t.replace('_', ' ')
        for (k in CLS_ALIASES.keys.sortedByDescending { it.length }) {
            if (Regex("\\b" + Regex.escape(k) + "\\b").containsMatchIn(t2)) return CLS_ALIASES.getValue(k)
        }
        return default
    }
}

/** Similitud tipo difflib.SequenceMatcher.ratio() (2·M / T con bloques coincidentes más largos, sin "junk"). */
internal object SeqRatio {
    fun ratio(a: String, b: String): Double {
        val t = a.length + b.length
        if (t == 0) return 1.0
        return 2.0 * matches(a, 0, a.length, b, 0, b.length) / t
    }

    private fun matches(a: String, alo: Int, ahi: Int, b: String, blo: Int, bhi: Int): Int {
        if (alo >= ahi || blo >= bhi) return 0
        var bestI = alo; var bestJ = blo; var bestK = 0
        var prev = IntArray(bhi - blo + 1)
        for (i in alo until ahi) {
            val cur = IntArray(bhi - blo + 1)
            for (j in blo until bhi) {
                if (a[i] == b[j]) {
                    val k = prev[j - blo] + 1
                    cur[j - blo + 1] = k
                    if (k > bestK) { bestK = k; bestI = i - k + 1; bestJ = j - k + 1 }
                }
            }
            prev = cur
        }
        if (bestK == 0) return 0
        return bestK + matches(a, alo, bestI, b, blo, bestJ) + matches(a, bestI + bestK, ahi, b, bestJ + bestK, bhi)
    }
}
