package com.scannerpromax.ocr.indra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Vocabulario del reconocedor CTC de Indra (puerto de indra_ocr/vocab.py, solo lo que usa la inferencia).
 * Clase 0 = blank; clase i + 1 = [chars][i] (un carácter o un comando LaTeX como `\alpha`).
 */
class IndraVocab(val chars: List<String>) {
    /** token → id de clase (i + 1). */
    val index: Map<String, Int> = HashMap<String, Int>(chars.size * 2).apply { chars.forEachIndexed { i, c -> put(c, i + 1) } }
    private val hasCommands = chars.any { it.length > 1 && it.codePointCount(0, it.length) > 1 }
    private val jamo = "ᄀ" in index

    /** Id del espacio (para cortar palabras y celdas), o null. */
    val spaceId: Int? = index[" "]

    fun decode(ids: IntArray): String {
        val toks = ArrayList<String>(ids.size)
        for (i in ids) if (i in 1..chars.size) toks += chars[i - 1]
        if (jamo) return java.text.Normalizer.normalize(toks.joinToString(""), java.text.Normalizer.Form.NFC)
        if (!hasCommands) return toks.joinToString("")
        val sb = StringBuilder()
        for (k in toks.indices) {
            val t = toks[k]
            sb.append(t)
            // "\alpha" + "x" no debe leerse como "\alphax"
            val nxt = toks.getOrNull(k + 1) ?: ""
            if (t.length > 2 && t[0] == '\\' && t.substring(1).all { it.isLetter() } && nxt.isNotEmpty() && nxt[0].isLetter()) sb.append(' ')
        }
        return sb.toString()
    }

    companion object {
        /** Desde la cadena JSON `{"blank": 0, "chars": [...]}` de meta.json. */
        fun fromJson(s: String): IndraVocab {
            val o = Json.parseToJsonElement(s) as JsonObject
            val blank = o["blank"]?.jsonPrimitive?.int ?: 0
            require(blank == 0) { "solo vocabularios con blank=0" }
            return IndraVocab(o["chars"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }
}
