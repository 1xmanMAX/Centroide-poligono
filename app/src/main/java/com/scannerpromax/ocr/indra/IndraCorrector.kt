package com.scannerpromax.ocr.indra

import com.scannerpromax.ocr.lexicon.Hunspell
import com.scannerpromax.ocr.lexicon.UserVocabulary
import com.scannerpromax.ocr.lexicon.UserVocabulary.Companion.key
import kotlin.math.max
import kotlin.math.min

/**
 * Corrección de palabras mal leídas por Indra con el léxico español (hunspell es_ES, [Hunspell]) y "Mi vocabulario"
 * ([UserVocabulary]), SOLO cuando la corrección es clara:
 *  - la palabra no está en el léxico ni en el vocabulario, no lleva dígitos (códigos como U20191A543, importes y
 *    fechas no se tocan), tiene ≥ 4 letras y su confianza es baja (< [maxConf]);
 *  - el candidato está a una edición (léxico) o a una distancia pequeña ponderada por la confianza de cada carácter
 *    (vocabulario: cambiar un carácter dudoso cuesta poco, uno seguro casi una edición entera; una tilde 0,1);
 *  - es único (o el modelo de lenguaje de Indra lo prefiere con margen);
 *  - el léxico español solo se usa si la página es española (≥ 40 % de sus palabras están en el léxico).
 * El vocabulario del usuario tiene prioridad y además sesga la búsqueda en haz del LM ([bias]). Un término del
 * vocabulario con dígitos ("E030") se corrige solo por confusiones letra/dígito (O↔0, I/l↔1, S↔5, B↔8).
 */
class IndraCorrector(
    private val lexicon: Hunspell?,
    private val vocab: UserVocabulary,
    private val lm: IndraLm? = null,
    private val indraVocab: IndraVocab? = null,
    private val maxConf: Float = MAX_CONF,
) {
    class Change(val from: String, val to: String, val source: String)

    class Result(val page: IndraOcr.Page, val changes: List<Change>, val spanish: Boolean)

    private val cache = HashMap<String, Boolean>()

    fun inLexicon(w: String): Boolean {
        val lx = lexicon ?: return false
        return cache.getOrPut(w) { lx.contains(w) }.also { if (cache.size > 20_000) cache.clear() }
    }

    /** ¿Página en español? (fracción de palabras de ≥ 3 letras que están en el léxico). */
    fun spanishShare(page: IndraOcr.Page): Pair<Int, Int> {
        var n = 0; var hit = 0
        for (l in page.lines) {
            if (l.doubtful) continue
            for (w in l.words) {
                val c = core(w.text)
                if (c.length < 3 || !c.all { it.isLetter() }) continue
                n++
                if (inLexicon(c)) hit++
            }
        }
        return hit to n
    }

    fun correct(page: IndraOcr.Page): Result {
        if (lexicon == null && vocab.isEmpty) return Result(page, emptyList(), false)
        val (hit, n) = spanishShare(page)
        val spanish = lexicon != null && n >= MIN_WORDS && hit >= SPANISH_SHARE * n
        val changes = ArrayList<Change>()
        val lines = page.lines.map { l -> fixLine(l, spanish, changes) }
        return Result(if (changes.isEmpty()) page else IndraOcr.Page(lines, page.blocks, page.rotation, page.skew, page.width, page.height, page.timings), changes, spanish)
    }

    private fun fixLine(l: IndraOcr.Line, spanish: Boolean, changes: MutableList<Change>): IndraOcr.Line {
        if (l.words.isEmpty()) return l
        val parts = l.text.split(' ')
        if (parts.count { it.isNotEmpty() } != l.words.size) return l
        var changed = false
        val newWords = l.words.map { w ->
            val fixed = fixWord(w, spanish)
            if (fixed != null && fixed.first != w.text) {
                changes += Change(w.text, fixed.first, fixed.second)
                changed = true
                IndraOcr.Word(fixed.first, w.box, w.conf, null)
            } else w
        }
        if (!changed) return l
        var k = 0
        val text = parts.joinToString(" ") { p -> if (p.isEmpty()) p else newWords[k++].text }
        return IndraOcr.Line(l.box, text, l.conf, newWords, l.doubtful)
    }

    /** (palabra corregida con su puntuación, origen) o null. */
    fun fixWord(w: IndraOcr.Word, spanish: Boolean): Pair<String, String>? {
        val t = w.text
        val start = t.indexOfFirst { it.isLetterOrDigit() }
        if (start < 0) return null
        val end = t.indexOfLast { it.isLetterOrDigit() } + 1
        val c = t.substring(start, end)
        val pre = t.substring(0, start); val post = t.substring(end)
        val probs = w.charP?.let { p -> if (p.size == t.length) p.copyOfRange(start, end) else null }
        if (c.any { it.isDigit() }) {
            // Solo términos del vocabulario con dígitos, por confusiones letra/dígito.
            if (c.length < 3) return null
            val alias = digitAlias(c)
            for (v in vocab.words.values) if (v.any { it.isDigit() } && v.length == c.length && v != c && digitAlias(v) == alias) return pre + v + post to "vocabulario"
            return null
        }
        if (c.length < MIN_LEN || !c.all { it.isLetter() }) return null
        vocab.words[key(c)]?.let { v ->
            // Ya es un término del vocabulario: solo se le ponen sus tildes ("sismica" → "sísmica").
            val fixed = matchCase(c, v)
            return if (fixed != c && fixed.lowercase() != c.lowercase()) pre + fixed + post to "vocabulario" else null
        }
        if (w.conf >= maxConf) return null
        // Una palabra española correcta solo admite su forma con tildes del vocabulario (aunque la página no sea española).
        val inLex = inLexicon(c)
        // 1) Vocabulario del usuario (prioridad): distancia ponderada pequeña.
        var best: String? = null; var bestCost = Double.MAX_VALUE; var second = Double.MAX_VALUE
        for (v in vocab.words.values) {
            if (v.any { it.isDigit() } || v.length < MIN_LEN) continue
            if (inLex && key(v) != key(c)) continue
            if (editDistance(key(c), key(v)) > maxVocabEdits(c.length)) continue
            val cost = weightedDistance(c, v, probs)
            if (cost < bestCost) { second = bestCost; bestCost = cost; best = v } else if (cost < second) second = cost
        }
        if (best != null && bestCost <= vocabMaxCost(c.length) && second - bestCost >= MARGIN) return pre + matchCase(c, best) + post to "vocabulario"
        // 2) Léxico español: una edición, candidato único. Tildes/diéresis: basta con que sea único. Otras ediciones
        //    (más arriesgadas: el léxico tiene muchas palabras raras y no hay frecuencias): palabra de ≥ 6 letras y el
        //    LM de Indra debe preferir claramente el candidato a lo leído (más aún si empieza por mayúscula: nombres).
        if (!spanish || inLex || lexicon == null) return null
        if (c.length > 1 && c.all { it.isUpperCase() } && c.length <= 4) return null // siglas
        val lower = c.lowercase()
        val cands = HashMap<String, Double>()
        for (cand in edits1(lower)) {
            if (cands.containsKey(cand)) continue
            if (!inLexicon(cand)) continue
            cands[cand] = weightedDistance(c, cand, probs)
        }
        if (cands.isEmpty()) return null
        val sorted = cands.entries.sortedBy { it.value }
        var top = sorted[0]
        if (top.value > LEX_MAX_COST) return null
        if (sorted.size > 1 && sorted[1].value - top.value < MARGIN) {
            // Empate: decide el LM de Indra si hay un margen claro.
            val ties = sorted.filter { it.value - top.value < MARGIN }
            val scored = ties.map { it to lmScore(it.key) }.sortedByDescending { it.second }
            if (scored[0].second.isNaN() || scored[0].second - scored[1].second < LM_MARGIN) return null
            top = scored[0].first
        }
        val accentOnly = key(top.key) == key(lower)
        val gain = lmScore(top.key) - lmScore(lower)
        if (!accentOnly) {
            if (c.length < MIN_LEN_EDIT || gain.isNaN()) return null
            if (gain < (if (c[0].isUpperCase()) lmGainUpper else lmGain)) return null
        }
        return pre + matchCase(c, top.key) + post to (if (accentOnly) "tilde" else "léxico %.1f".format(java.util.Locale.ROOT, gain))
    }

    /**
     * Candidatas para una palabra dudosa (verificación con Jev): términos del vocabulario cercanos y palabras del léxico a
     * una edición, ordenadas por distancia ponderada (y LM). Conserva la puntuación y la mayúscula de [t]. Sin decidir:
     * quien llama elige mirando la imagen.
     */
    fun suggestions(t: String, max: Int = 4): List<String> {
        val start = t.indexOfFirst { it.isLetterOrDigit() }
        if (start < 0) return emptyList()
        val end = t.indexOfLast { it.isLetterOrDigit() } + 1
        val c = t.substring(start, end); val pre = t.substring(0, start); val post = t.substring(end)
        if (c.length < 3 || !c.all { it.isLetter() }) return emptyList()
        val scored = HashMap<String, Double>()
        for (v in vocab.words.values) if (!v.any { it.isDigit() } && editDistance(key(c), key(v)) <= maxVocabEdits(c.length)) scored[matchCase(c, v)] = weightedDistance(c, v, null) - 0.5
        if (lexicon != null && !inLexicon(c)) {
            for (cand in edits1(c.lowercase())) if (!scored.containsKey(cand) && inLexicon(cand)) {
                val lm = lmScore(cand)
                scored[matchCase(c, cand)] = weightedDistance(c, cand, null) - (if (lm.isNaN()) 0.0 else 0.02 * lm)
            }
        }
        return scored.entries.sortedBy { it.value }.map { pre + it.key + post }.filter { it != t }.distinct().take(max)
    }

    /** log P de la palabra (con espacio delante y detrás) según el LM de Indra; NaN sin LM. */
    private fun lmScore(w: String): Double {
        val m = lm ?: return Double.NaN
        val v = indraVocab ?: return Double.NaN
        val ids = ArrayList<Int>()
        v.spaceId?.let { ids += it }
        for (ch in w) ids += v.index[ch.toString()] ?: return Double.NaN
        v.spaceId?.let { ids += it }
        var hist = 0L
        val b = m.bits
        val hmask = (1L shl (b * (m.order - 1))) - 1
        var s = 0.0
        for (id in ids) {
            val key = (hist shl b) or (id + 1).toLong()
            s += m.logpKey(key)
            hist = key and hmask
        }
        return s
    }

    /** Sesgo para la búsqueda en haz del LM: prefijos/palabras del vocabulario y (suave) palabras del léxico. */
    fun bias(lexWeight: Double = LEX_BIAS, vocabWeight: Double = VOCAB_BIAS, vocabEnd: Double = VOCAB_END): IndraLm.WordBias = object : IndraLm.WordBias {
        override val active: Boolean = !vocab.isEmpty || (lexicon != null && lexWeight > 0)
        override val maxStep: Double = vocabWeight + vocabEnd + lexWeight
        override fun partial(w: String): Double = if (!vocab.isEmpty && key(w) in vocab.prefixes) vocabWeight * w.length else 0.0
        override fun complete(w: String): Double {
            val k = key(w)
            if (!vocab.isEmpty && vocab.words.containsKey(k)) return vocabWeight * w.length + vocabEnd
            if (lexWeight > 0 && w.length >= 3 && w.all { it.isLetter() } && inLexicon(w)) return lexWeight
            return 0.0
        }
    }

    companion object {
        const val MIN_LEN = 4
        /** Ediciones que no son tildes: palabras de al menos 6 letras. */
        const val MIN_LEN_EDIT = 6
        /** Ventaja mínima (nats) del candidato frente a lo leído según el LM de Indra (minúscula / Mayúscula inicial). */
        @JvmField var lmGain = 5.0
        @JvmField var lmGainUpper = 10.0
        const val MIN_WORDS = 5
        const val SPANISH_SHARE = 0.4
        /** Palabras con confianza (media de sus caracteres) desde aquí no se corrigen. */
        const val MAX_CONF = 0.9f
        const val MARGIN = 0.15
        const val LM_MARGIN = 1.0
        const val LEX_MAX_COST = 0.75
        /** Bonificación del LM por palabra completa del léxico (banco: manuscrito español AK137 20,5 → 23,2 % palabras). */
        const val LEX_BIAS = 1.0
        const val VOCAB_BIAS = 0.5
        const val VOCAB_END = 1.0
        private const val INS_COST = 0.6
        private const val ACCENT_COST = 0.1
        private const val LETTERS = "abcdefghijklmnopqrstuvwxyzñáéíóúü"

        fun vocabMaxCost(len: Int): Double = min(2.0, 0.3 + 0.12 * len)

        /** Ediciones (sin tildes) permitidas hacia un término del vocabulario: 1 hasta 9 letras, 2 desde 10, 3 desde 15. */
        fun maxVocabEdits(len: Int): Int = max(1, len / 5)

        fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1); cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.length]
        }

        fun core(t: String): String = t.trim { !it.isLetterOrDigit() }

        private fun digitAlias(s: String): String = buildString {
            for (ch in s.uppercase()) append(when (ch) { 'O', 'Q', 'D' -> '0'; 'I', 'L', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; 'G' -> '6'; else -> ch })
        }

        /** Copia mayúsculas del original: TODO EN MAYÚSCULAS, Capitalizada o tal cual el candidato. */
        fun matchCase(orig: String, cand: String): String = when {
            orig.length > 1 && orig.all { !it.isLetter() || it.isUpperCase() } -> cand.uppercase()
            orig.first().isUpperCase() -> cand.replaceFirstChar { it.uppercaseChar() }
            cand.first().isUpperCase() -> cand // nombre propio del léxico / del vocabulario
            else -> cand
        }

        /** Formas a una edición (borrado, cambio, inserción, trasposición) en minúsculas. */
        fun edits1(w: String): Set<String> {
            val out = HashSet<String>()
            for (i in w.indices) {
                out += w.removeRange(i, i + 1)
                for (ch in LETTERS) if (ch != w[i]) out += w.substring(0, i) + ch + w.substring(i + 1)
                if (i + 1 < w.length) out += w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2)
            }
            for (i in 0..w.length) for (ch in LETTERS) out += w.substring(0, i) + ch + w.substring(i)
            out.remove(w)
            return out
        }

        /**
         * Distancia de edición ponderada de lo leído [o] (con la probabilidad de cada carácter [p]) a [c]: cambiar o
         * borrar un carácter cuesta 0,2 + 0,8·p (uno dudoso es barato), una tilde/diéresis 0,1, insertar 0,6.
         * Sin mayúsculas/minúsculas.
         */
        fun weightedDistance(o: String, c: String, p: FloatArray?): Double {
            val a = o.lowercase(); val b = c.lowercase()
            val n = a.length; val m = b.length
            fun pc(i: Int) = 0.2 + 0.8 * (p?.getOrNull(i)?.toDouble() ?: 0.8)
            var prev = DoubleArray(m + 1) { it * INS_COST }
            for (i in 1..n) {
                val cur = DoubleArray(m + 1)
                cur[0] = prev[0] + pc(i - 1)
                for (j in 1..m) {
                    val x = a[i - 1]; val y = b[j - 1]
                    val sub = when {
                        x == y -> 0.0
                        key(x.toString()) == key(y.toString()) -> ACCENT_COST
                        else -> pc(i - 1)
                    }
                    cur[j] = minOf(prev[j - 1] + sub, prev[j] + pc(i - 1), cur[j - 1] + INS_COST)
                }
                prev = cur
            }
            return prev[m]
        }
    }
}
