package com.scannerpromax.ocr.lexicon

import java.io.BufferedReader
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Diccionario hunspell mínimo (solo consulta: ¿existe esta palabra?) para el léxico español de la corrección de OCR.
 * Admite lo que usa es_ES de LibreOffice (rla-es): `FLAG UTF-8`, reglas PFX/SFX con condición, producto cruzado
 * prefijo+sufijo y un nivel de sufijo de continuación (p. ej. "-ción/S" → "-ciones"). Sin compuestos ni
 * NEEDAFFIX/FORBIDDENWORD (es_ES no los usa).
 *
 * Se guarda la lista de raíces (58 mil) y las reglas, no las formas expandidas (≈1 M): ~2 MB de RAM en vez de ~80.
 */
class Hunspell private constructor(
    private val stems: HashMap<String, String>,
    private val sfxByAdd: HashMap<String, MutableList<Affix>>,
    private val pfxByAdd: HashMap<String, MutableList<Affix>>,
    private val maxSfx: Int,
    private val maxPfx: Int,
) {
    /** Regla: quitar [add] y reponer [strip]; [cond] sobre la raíz resultante; [cont] = banderas de continuación. */
    class Affix(val flag: Char, val strip: String, val add: String, val cond: Cond, val cross: Boolean, val cont: String)

    /** Condición hunspell ("[^aeiou]r", ".", "ecer") comprobada al final (sufijo) o al principio (prefijo). */
    class Cond(spec: String) {
        private val parts: List<Pair<Boolean, String>?> // null = cualquiera; (negada, conjunto)
        init {
            val out = ArrayList<Pair<Boolean, String>?>()
            var i = 0
            while (i < spec.length) {
                val c = spec[i]
                if (c == '[') {
                    val j = spec.indexOf(']', i)
                    val neg = spec.getOrNull(i + 1) == '^'
                    out += neg to spec.substring(if (neg) i + 2 else i + 1, j)
                    i = j + 1
                } else {
                    out += if (c == '.') null else (false to c.toString())
                    i++
                }
            }
            parts = out
        }
        val any = parts.all { it == null }

        private fun ok(p: Pair<Boolean, String>?, c: Char) = p == null || ((c in p.second) != p.first)

        fun matchesEnd(w: String): Boolean {
            if (any) return true
            if (w.length < parts.size) return false
            val o = w.length - parts.size
            for (k in parts.indices) if (!ok(parts[k], w[o + k])) return false
            return true
        }

        fun matchesStart(w: String): Boolean {
            if (any) return true
            if (w.length < parts.size) return false
            for (k in parts.indices) if (!ok(parts[k], w[k])) return false
            return true
        }
    }

    val size: Int get() = stems.size

    /** ¿Está la palabra (tal cual, en minúscula o con mayúscula inicial según su forma)? */
    fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        if (check(word)) return true
        val lower = word.lowercase()
        if (lower != word && check(lower)) return true
        // En MAYÚSCULAS puede ser un nombre propio ("MADRID" → "Madrid").
        if (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }) {
            val cap = lower.replaceFirstChar { it.uppercaseChar() }
            if (check(cap)) return true
        }
        return false
    }

    private fun has(stem: String, flag: Char): Boolean = stems[stem]?.indexOf(flag)?.let { it >= 0 } == true

    private fun check(w: String): Boolean {
        if (stems.containsKey(w)) return true
        if (suffixed(w, null, null)) return true
        // Prefijo (y prefijo + sufijo cruzados).
        for (l in 0..minOf(maxPfx, w.length)) {
            val rules = pfxByAdd[w.substring(0, l)] ?: continue
            val rest = w.substring(l)
            for (r in rules) {
                val stem = r.strip + rest
                if (stem.isEmpty() || !r.cond.matchesStart(stem)) continue
                if (has(stem, r.flag)) return true
                if (r.cross && suffixed(stem, r.flag, null)) return true
            }
        }
        return false
    }

    /**
     * ¿[w] = raíz + sufijo (o raíz + sufijo + sufijo de continuación)? [needFlag]: la raíz debe llevar además esa
     * bandera (prefijo cruzado). [contOf]: el sufijo debe estar permitido como continuación de esa regla.
     */
    private fun suffixed(w: String, needFlag: Char?, contFlag: Char?, depth: Int = 0): Boolean {
        for (l in 0..minOf(maxSfx, w.length)) {
            val rules = sfxByAdd[w.substring(w.length - l)] ?: continue
            val base = w.substring(0, w.length - l)
            for (r in rules) {
                if (needFlag != null && !r.cross) continue
                if (contFlag != null && r.flag != contFlag) continue
                val stem = base + r.strip
                if (stem.isEmpty() || !r.cond.matchesEnd(stem)) continue
                if (depth == 0) {
                    if (has(stem, r.flag) && (needFlag == null || has(stem, needFlag))) return true
                    // Sufijo de continuación: stem = raíz + sufijo1 (cuya regla permite r.flag).
                    if (contAllowed.contains(r.flag) && suffixedCont(stem, r.flag, needFlag)) return true
                } else if (has(stem, r.flag) && (needFlag == null || has(stem, needFlag))) return true
            }
        }
        return false
    }

    /** Banderas que aparecen como continuación de alguna regla. */
    private val contAllowed: Set<Char> = sfxByAdd.values.flatten().flatMap { it.cont.toList() }.toSet()

    private fun suffixedCont(w: String, flag2: Char, needFlag: Char?): Boolean {
        for (l in 0..minOf(maxSfx, w.length)) {
            val rules = sfxByAdd[w.substring(w.length - l)] ?: continue
            val base = w.substring(0, w.length - l)
            for (r in rules) {
                if (flag2 !in r.cont) continue
                if (needFlag != null && !r.cross) continue
                val stem = base + r.strip
                if (stem.isEmpty() || !r.cond.matchesEnd(stem)) continue
                if (has(stem, r.flag) && (needFlag == null || has(stem, needFlag))) return true
            }
        }
        return false
    }

    companion object {
        /** Carga .aff y .dic (texto UTF-8, opcionalmente .gz). */
        fun load(aff: InputStream, dic: InputStream): Hunspell {
            val sfx = HashMap<String, MutableList<Affix>>()
            val pfx = HashMap<String, MutableList<Affix>>()
            val conds = HashMap<String, Cond>()
            var maxS = 0; var maxP = 0
            val cross = HashMap<String, Boolean>()
            reader(aff).useLines { lines ->
                for (raw in lines) {
                    val f = raw.trim().split(Regex("\\s+"))
                    if (f.size < 4 || (f[0] != "SFX" && f[0] != "PFX")) continue
                    val key = f[0] + f[1]
                    if (f.size == 4 && (f[2] == "Y" || f[2] == "N")) { cross[key] = f[2] == "Y"; continue }
                    if (f.size < 5) continue
                    val strip = if (f[2] == "0") "" else f[2]
                    val addCont = f[3].split('/', limit = 2)
                    val add = if (addCont[0] == "0") "" else addCont[0]
                    val cont = addCont.getOrElse(1) { "" }
                    val cond = conds.getOrPut(f[4]) { Cond(f[4]) }
                    val a = Affix(f[1][0], strip, add, cond, cross[key] ?: false, cont)
                    if (f[0] == "SFX") { sfx.getOrPut(add) { ArrayList() } += a; maxS = maxOf(maxS, add.length) }
                    else { pfx.getOrPut(add) { ArrayList() } += a; maxP = maxOf(maxP, add.length) }
                }
            }
            val stems = HashMap<String, String>(80_000)
            reader(dic).useLines { lines ->
                var first = true
                for (raw in lines) {
                    if (first) { first = false; if (raw.trim().all { it.isDigit() }) continue }
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    // "palabra/BANDERAS [morfología]"; una barra escapada (\/) no se usa en es_ES.
                    val sp = line.indexOfFirst { it == ' ' || it == '\t' }
                    val entry = if (sp > 0) line.substring(0, sp) else line
                    val slash = entry.indexOf('/')
                    val w = if (slash >= 0) entry.substring(0, slash) else entry
                    val fl = if (slash >= 0) entry.substring(slash + 1) else ""
                    stems[w] = (stems[w] ?: "") + fl
                }
            }
            return Hunspell(stems, sfx, pfx, maxS, maxP)
        }

        private fun reader(input: InputStream): BufferedReader {
            val b = input.buffered()
            b.mark(2)
            val gz = b.read() == 0x1f && b.read() == 0x8b
            b.reset()
            return (if (gz) GZIPInputStream(b) else b).bufferedReader(Charsets.UTF_8)
        }
    }
}
