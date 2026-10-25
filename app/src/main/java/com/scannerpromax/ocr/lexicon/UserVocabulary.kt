package com.scannerpromax.ocr.lexicon

import java.text.Normalizer

/**
 * "Mi vocabulario" (Ajustes › Reconocimiento): términos del usuario (nombres, cursos, términos técnicos como
 * "sismorresistente", "E030", "concreto armado") que tienen prioridad en la corrección y en la búsqueda en haz del
 * modelo de lenguaje de Indra. Un término de varias palabras aporta cada palabra.
 */
class UserVocabulary(terms: List<String>) {
    /** Términos tal como los escribió el usuario (sin duplicados ni vacíos). */
    val terms: List<String> = terms.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.distinct()

    /** Palabras de los términos (forma del usuario), por clave normalizada ([key]). */
    val words: Map<String, String> = LinkedHashMap<String, String>().apply {
        for (t in this@UserVocabulary.terms) for (w in t.split(' ')) {
            val core = w.trim { !it.isLetterOrDigit() }
            if (core.length >= MIN_LEN) putIfAbsent(key(core), core)
        }
    }

    /** Prefijos (normalizados) de las palabras: para la bonificación de la palabra a medio leer. */
    val prefixes: Set<String> = HashSet<String>().apply { for (k in words.keys) for (i in 1..k.length) add(k.substring(0, i)) }

    val isEmpty: Boolean get() = words.isEmpty()

    fun containsWord(w: String): Boolean = words.containsKey(key(w))

    companion object {
        /** Palabras más cortas no se usan (demasiado fácil confundirlas). */
        const val MIN_LEN = 3

        /** Minúsculas sin tildes ni diéresis (la eñe se conserva). */
        fun key(w: String): String {
            val lower = w.lowercase()
            val sb = StringBuilder(lower.length)
            for (ch in lower) {
                if (ch == 'ñ') { sb.append(ch); continue }
                val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                for (c in d) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
            }
            return sb.toString()
        }

        /** Texto del ajuste (un término por línea o separados por comas) → lista. */
        fun parse(text: String): List<String> = text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
