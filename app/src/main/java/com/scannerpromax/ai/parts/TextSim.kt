package com.scannerpromax.ai.parts

/**
 * Parecido de textos como difflib.SequenceMatcher(None, a, b).ratio() de Python (Ratcliff/Obershelp con la heurística
 * "autojunk" para textos de ≥ 200 caracteres), sobre el texto normalizado (minúsculas, espacios simples). Es la
 * medida que usó el banco recon2 para casar líneas locales con las de la nube.
 */
object TextSim {

    fun norm(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** 0..1; 0 si alguno está vacío. */
    fun sim(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() && y.isEmpty()) return 1f
        val m = matches(x, y)
        return (2.0 * m / (x.length + y.length)).toFloat()
    }

    /** Caracteres en bloques coincidentes (suma de get_matching_blocks). */
    fun matches(a: String, b: String): Int {
        val n = b.length
        // b2j con autojunk
        val b2j = HashMap<Char, MutableList<Int>>()
        for (i in 0 until n) b2j.getOrPut(b[i]) { ArrayList() }.add(i)
        if (n >= 200) {
            val ntest = n / 100 + 1
            val popular = b2j.filter { it.value.size > ntest }.keys
            for (c in popular) b2j.remove(c)
        }
        var total = 0
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(0, a.length, 0, n))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeFirst()
            val (i, j, k) = longest(a, b, b2j, alo, ahi, blo, bhi)
            if (k > 0) {
                total += k
                if (alo < i && blo < j) queue.add(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.add(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        return total
    }

    private fun longest(a: String, b: String, b2j: Map<Char, List<Int>>, alo: Int, ahi: Int, blo: Int, bhi: Int): IntArray {
        var besti = alo; var bestj = blo; var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            val idx = b2j[a[i]] ?: emptyList()
            for (j in idx) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newj2len[j] = k
                if (k > bestsize) { besti = i - k + 1; bestj = j - k + 1; bestsize = k }
            }
            j2len = newj2len
        }
        // extensión como difflib (sin basura explícita: cualquier carácter igual, incluidos los "populares")
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) { besti--; bestj--; bestsize++ }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) bestsize++
        return intArrayOf(besti, bestj, bestsize)
    }
}
