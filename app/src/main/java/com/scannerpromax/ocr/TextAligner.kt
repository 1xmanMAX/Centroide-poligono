package com.scannerpromax.ocr

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Alinea el texto corregido por el usuario con las líneas detectadas por el OCR (lógica pura, probada en JVM),
 * para que la capa invisible del PDF contenga las palabras corregidas en el sitio correcto.
 *
 *  - Mismo número de líneas (no vacías) -> reemplazo línea a línea.
 *  - Si no -> diff de palabras (LCS sobre palabras normalizadas): las coincidencias se quedan en su línea y cada
 *    tramo de palabras nuevas se reparte proporcionalmente entre las líneas de las palabras OCR que sustituye.
 */
object TextAligner {

    /**
     * @param ocrLines palabras de cada línea OCR (en orden de lectura).
     * @param corrected texto corregido completo.
     * @return palabras corregidas por línea (misma cantidad de líneas que [ocrLines]; puede haber líneas vacías).
     */
    fun align(ocrLines: List<List<String>>, corrected: String): List<List<String>> {
        if (ocrLines.isEmpty()) return emptyList()
        val correctedLines = corrected.replace("\r\n", "\n").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (correctedLines.size == ocrLines.size) return correctedLines.map { tokenize(it) }

        val newWords = correctedLines.flatMap { tokenize(it) }
        val oldWords = ArrayList<String>()
        val oldLine = ArrayList<Int>()
        ocrLines.forEachIndexed { li, ws -> ws.forEach { w -> oldWords += w; oldLine += li } }
        val result = List(ocrLines.size) { ArrayList<String>() }
        if (newWords.isEmpty()) return result
        if (oldWords.isEmpty()) {
            distribute(newWords, (ocrLines.indices).toList(), List(ocrLines.size) { 1 }, result)
            return result
        }
        if (oldWords.size.toLong() * newWords.size > MAX_LCS_CELLS) {
            // Texto enorme: reparto proporcional global (memoria acotada en gama baja).
            distribute(newWords, ocrLines.indices.toList(), ocrLines.map { max(1, it.size) }, result)
            return result
        }

        val pairs = lcs(oldWords.map { normalize(it) }, newWords.map { normalize(it) })
        var prevOld = -1
        var prevNew = -1
        for ((oi, ni) in pairs + (oldWords.size to newWords.size)) {
            // Tramo sin coincidencia: viejas (prevOld+1 until oi), nuevas (prevNew+1 until ni).
            val gapNew = newWords.subList(prevNew + 1, ni)
            if (gapNew.isNotEmpty()) {
                if (oi - prevOld > 1) {
                    val lines = ArrayList<Int>()
                    val weights = ArrayList<Int>()
                    for (k in prevOld + 1 until oi) {
                        val l = oldLine[k]
                        if (lines.isNotEmpty() && lines.last() == l) weights[weights.size - 1]++ else { lines += l; weights += 1 }
                    }
                    distribute(gapNew, lines, weights, result)
                } else {
                    // Inserción pura: va a la línea de la palabra anterior (o de la siguiente si es el inicio).
                    val l = if (prevOld >= 0) oldLine[prevOld] else oldLine[oi.coerceAtMost(oldWords.size - 1)]
                    result[l].addAll(gapNew)
                }
            }
            if (oi < oldWords.size && ni < newWords.size) result[oldLine[oi]] += newWords[ni]
            prevOld = oi
            prevNew = ni
        }
        return result
    }

    fun tokenize(s: String): List<String> = s.split(WS).filter { it.isNotEmpty() }

    /** Reparte [words] (en orden) entre [lines] según [weights] (redondeo acumulado: sin perder ni duplicar). */
    private fun distribute(words: List<String>, lines: List<Int>, weights: List<Int>, out: List<MutableList<String>>) {
        val total = weights.sum().coerceAtLeast(1)
        var acc = 0
        var start = 0
        for (i in lines.indices) {
            acc += weights[i]
            val end = if (i == lines.size - 1) words.size else (words.size.toDouble() * acc / total).roundToInt().coerceIn(start, words.size)
            out[lines[i]].addAll(words.subList(start, end))
            start = end
        }
    }

    /** Subsecuencia común más larga: pares (índice viejo, índice nuevo) en orden. */
    private fun lcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        // Tabla de longitudes con sufijos: dp[i][j] = LCS(a[i..], b[j..]) (acotada por MAX_LCS_CELLS).
        val width = m + 1
        val dp = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            val row = i * width
            val next = (i + 1) * width
            for (j in m - 1 downTo 0) {
                dp[row + j] = if (a[i] == b[j]) dp[next + j + 1] + 1 else max(dp[next + j], dp[row + j + 1])
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += i to j; i++; j++ }
                dp[(i + 1) * width + j] >= dp[i * width + j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    /** Normaliza para comparar: minúsculas, sin tildes ni signos de puntuación en los extremos. */
    internal fun normalize(w: String): String {
        val lower = w.lowercase()
        val noMarks = Normalizer.normalize(lower, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return noMarks.trim { !it.isLetterOrDigit() }.ifEmpty { noMarks }
    }

    private val WS = Regex("\\s+")
    private const val MAX_LCS_CELLS = 2_000_000L
}
