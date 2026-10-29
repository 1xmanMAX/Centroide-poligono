package com.scannerpromax.ai.jev

import com.scannerpromax.domain.OCR_DOUBT
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OCR dudoso de Indra verificado con Jev, mirando el RECORTE:
 *  - palabras con confianza baja dentro de líneas normales: `choice` entre la lectura de Indra, las sugerencias del
 *    léxico / vocabulario ([suggest]) y variantes de confusión típicas (l/1/I, O/0, rn/m, cl/d, 5/S…), más "otra";
 *    se cambia SOLO si Jev elige otra candidata con probabilidad >= [minP];
 *  - líneas dudosas ([OcrResult.doubtful], fuera del texto): `noul` "¿el recorte dice exactamente …?"; si p >= [minLineP]
 *    la línea pasa al texto.
 * Cada recorte va como imagen propia en las instrucciones de su pregunta (≈ 500 tokens a 280 px); hasta [BATCH] por
 * petición.
 */
class JevOcr(
    private val suggest: (String) -> List<String> = { emptyList() },
    private val minP: Double = MIN_P,
    private val minLineP: Double = MIN_LINE_P,
) {
    companion object {
        const val MIN_P = 0.85
        const val MIN_LINE_P = 0.9
        const val BATCH = 24
        const val MAX_WORDS = 60
        const val MAX_LINES = 20
        const val MAX_OPTIONS = 9

        private val SWAPS = listOf(
            "l" to "1", "1" to "l", "I" to "1", "1" to "I", "l" to "I", "I" to "l", "O" to "0", "0" to "O", "o" to "0", "0" to "o",
            "rn" to "m", "m" to "rn", "cl" to "d", "d" to "cl", "vv" to "w", "5" to "S", "S" to "5", "8" to "B", "B" to "8",
            "," to ".", "." to ",", "e" to "c", "c" to "e", "n" to "u", "u" to "n", "a" to "á", "e" to "é", "i" to "í", "o" to "ó", "u" to "ú",
        )

        /** Variantes por UNA confusión típica (como mucho [max], las del léxico primero si [known]). */
        fun confusions(w: String, max: Int = 4, known: (String) -> Boolean = { false }): List<String> {
            val out = LinkedHashSet<String>()
            for ((a, b) in SWAPS) {
                var i = w.indexOf(a)
                while (i >= 0) {
                    out += w.substring(0, i) + b + w.substring(i + a.length)
                    i = w.indexOf(a, i + 1)
                }
            }
            out.remove(w)
            return out.sortedByDescending { known(it) }.take(max)
        }

        /** Opciones del choice: c0 = lectura, c1… = alternativas, "other". Las alternativas repetidas fuera. */
        fun options(reading: String, alts: List<String>): LinkedHashMap<String, String> {
            val o = LinkedHashMap<String, String>()
            val seen = HashSet<String>()
            for (t in listOf(reading) + alts) {
                if (t.isBlank() || !seen.add(t) || o.size >= MAX_OPTIONS - 1) continue
                o["c${o.size}"] = "The image shows exactly this text: «$t»"
            }
            o["other"] = "None of these: the image shows different text, or it is unreadable."
            return o
        }
    }

    /** Qué se verificó y qué cambió. */
    class Report(val words: Int, val lines: Int, val changed: List<Pair<String, String>>, val promoted: List<String>, val inputTokens: Long, val requests: Int,
        /** Banco: por duda, candidatas y probabilidades de Jev. */
        val decisions: List<String> = emptyList())

    private class Target(val id: String, val img: JevImage, val q: JevQuestion, val texts: List<String>, val line: Int, val word: Int)

    /**
     * Verifica las dudas de [res] (coordenadas de [image], la página que leyó el OCR, gris o RGB) con [client] y devuelve
     * el resultado corregido (o el mismo si no hay nada que cambiar o falla la red: la excepción se propaga y quien
     * llama se queda con [res]).
     */
    fun apply(res: OcrResult, image: Mat, client: JevClient): Pair<OcrResult, Report> {
        val sx = image.cols().toDouble() / max(1, res.imageWidth); val sy = image.rows().toDouble() / max(1, res.imageHeight)
        fun rect(b: OcrRect): Rect? {
            val x0 = (b.left * sx).roundToInt().coerceIn(0, image.cols() - 1); val y0 = (b.top * sy).roundToInt().coerceIn(0, image.rows() - 1)
            val x1 = (b.right * sx).roundToInt().coerceIn(x0 + 1, image.cols()); val y1 = (b.bottom * sy).roundToInt().coerceIn(y0 + 1, image.rows())
            return if (x1 - x0 < 4 || y1 - y0 < 4) null else Rect(x0, y0, x1 - x0, y1 - y0)
        }
        val lines = res.blocks.flatMap { it.lines }
        val targets = ArrayList<Target>()
        // palabras dudosas de líneas normales
        var nw = 0
        for ((li, l) in lines.withIndex()) for ((wi, w) in l.words.withIndex()) {
            if (nw >= MAX_WORDS) break
            if (!(w.confidence >= 0f && w.confidence < OCR_DOUBT) || w.text.isBlank()) continue
            val r = rect(w.box) ?: continue
            val alts = (suggest(w.text) + confusions(w.text)).filter { it != w.text }
            if (alts.isEmpty()) continue
            val opts = options(w.text, alts)
            val img = JevImages.encodeCrop(image, r, 0.35, 64, prefix = "w$li-$wi")
            targets += Target("w${li}_$wi", img, JevQuestion.Choice("Which option is exactly the word or number in this cropped image from a document: ${img.asset.ref}", opts),
                opts.keys.filter { it != "other" }.map { k -> opts[k]!!.substringAfter('«').substringBeforeLast('»') }, li, wi)
            nw++
        }
        // líneas dudosas
        for ((di, l) in res.doubtful.take(MAX_LINES).withIndex()) {
            if (l.text.isBlank()) continue
            val r = rect(l.box) ?: continue
            val img = JevImages.encodeCrop(image, r, 0.25, 56, maxSide = 1200, prefix = "l$di")
            targets += Target("l$di", img, JevQuestion.Noul("Does this cropped image from a document show exactly the text «${l.text}» (same characters, ignoring letter spacing)? ${img.asset.ref}"),
                listOf(l.text), di, -1)
        }
        if (targets.isEmpty()) return res to Report(0, 0, emptyList(), emptyList(), 0, 0)
        val answers = HashMap<String, JevAnswer>()
        var tokens = 0L; var reqs = 0
        for (chunk in targets.chunked(BATCH)) {
            val r = client.ask(JevRequest(buildJsonObject { put("task", JsonPrimitive("Check uncertain OCR readings against crops of a scanned document.")) },
                chunk.associate { it.id to it.q }, chunk.map { it.img.asset }))
            answers += r.answers; tokens += r.inputTokens; reqs++
        }
        // aplicar
        val newWords = HashMap<Pair<Int, Int>, String>()
        val changed = ArrayList<Pair<String, String>>()
        val promoted = ArrayList<Int>()
        val decisions = targets.map { t ->
            val a = answers[t.id]
            if (t.word >= 0) t.texts.withIndex().joinToString("\t", "W\t") { (k, x) -> "$x=%.2f".format(a?.p("c$k") ?: -1.0) } + "\tother=%.2f".format(a?.p("other") ?: -1.0)
            else "L\t${t.texts[0]}=%.2f".format(a?.noul ?: -1.0)
        }
        for (t in targets) {
            val a = answers[t.id] ?: continue
            if (t.word >= 0) {
                val ch = a.choice ?: continue
                if (ch == "other" || ch == "c0" || a.p(ch) < minP) continue
                val k = ch.removePrefix("c").toIntOrNull() ?: continue
                val txt = t.texts.getOrNull(k) ?: continue
                newWords[t.line to t.word] = txt
                changed += lines[t.line].words[t.word].text to txt
            } else if ((a.noul ?: 0.0) >= minLineP) promoted += t.line
        }
        if (newWords.isEmpty() && promoted.isEmpty()) return res to Report(nw, targets.size - nw, changed, emptyList(), tokens, reqs, decisions)
        // reconstruir líneas / bloques / texto
        var li = 0
        val blocks = res.blocks.map { b ->
            val ls = b.lines.map { l ->
                val idx = li++
                if (newWords.keys.none { it.first == idx }) l else {
                    val words = l.words.mapIndexed { wi, w -> newWords[idx to wi]?.let { OcrWord(it, w.box, max(w.confidence, OCR_DOUBT)) } ?: w }
                    val parts = l.text.split(' ')
                    val text = if (parts.count { it.isNotEmpty() } == l.words.size) { var k = 0; parts.joinToString(" ") { p -> if (p.isEmpty()) p else words[k++].text } }
                        else words.joinToString(" ") { it.text }
                    l.copy(text = text, words = words)
                }
            }
            OcrBlock(ls.joinToString("\n") { it.text }, b.box, ls)
        }.toMutableList()
        val promotedLines = promoted.map { res.doubtful[it] }.map { it.copy(confidence = max(it.confidence, OCR_DOUBT)) }
        for (pl in promotedLines.sortedBy { it.box.top }) {
            // bloque propio, en orden de lectura aproximado (por la parte superior, misma columna)
            val nb = OcrBlock(pl.text, pl.box, listOf(pl))
            val at = blocks.indexOfFirst { b -> b.box.top > pl.box.top && overlapX(b.box, pl.box) }
            if (at < 0) blocks.add(nb) else blocks.add(at, nb)
        }
        val out = res.copy(text = blocks.joinToString("\n\n") { it.text }, blocks = blocks,
            doubtful = res.doubtful.filterIndexed { i, _ -> i !in promoted })
        return out to Report(nw, targets.size - nw, changed, promotedLines.map { it.text }, tokens, reqs, decisions)
    }

    private fun overlapX(a: OcrRect, b: OcrRect) = min(a.right, b.right) - max(a.left, b.left) > 0.3 * min(a.right - a.left, b.right - b.left)
}
