package com.scannerpromax.imaging

import kotlin.math.max

/**
 * Lógica pura (sin OpenCV, probada con tests JVM) para validar una fila de anillas de espiral candidatas.
 */
internal object HwBinding {

    /**
     * ¿Forman [along] (posición del centro a lo largo de la banda), [across] (extensión perpendicular a la banda) y
     * [alongExt] (extensión a lo largo) una fila de anillas de encuadernación?
     *  - [clean]: en la banda no hay letras sueltas (lo decide quien llama);
     *  - al menos 8 anillas;
     *  - el aro cruza la banda: mediana de across/alongExt >= 0.7;
     *  - banda estrecha: mediana de across <= 16 % de [acrossDim] (la página en perpendicular a la banda);
     *  - paso regular: >= 35 % de los huecos entre centros consecutivos dentro de 0.55..1.7 veces el paso típico
     *    (las anillas fundidas, partidas o tapadas estropean algunos huecos);
     *  - [strict] (banda paralela a los renglones y lejos del borde): paso MUY regular (>= 80 % de los huecos en
     *    0.75..1.35 veces el paso), como el de una espiral real y no el de un renglón de letras gruesas.
     * Los huecos se miden con las anillas FUNDIDAS separadas ([splitFused]): dos aros pegados son una sola mancha
     * del doble de largo cuyo centro deja dos huecos de 1.5 pasos.
     */
    fun isRingRow(
        along: List<Double>, across: List<Double>, alongExt: List<Double>, acrossDim: Double,
        clean: Boolean = true, strict: Boolean = false,
    ): Boolean {
        val n = along.size
        if (!clean || n < 8 || across.size != n || alongExt.size != n) return false
        val ratio = across.indices.map { across[it] / max(1.0, alongExt[it]) }.sorted()[n / 2]
        if (ratio < 0.7) return false
        val medAcross = across.sorted()[n / 2]
        if (medAcross > 0.16 * acrossDim) return false
        val pos = splitFused(along, alongExt).sorted()
        val gaps = (1 until n).map { pos[it] - pos[it - 1] }.sorted()
        // Paso típico: el percentil 30 de los huecos (anillas fundidas o que faltan no lo inflan)
        val step = gaps[(gaps.size * 0.3).toInt()]
        if (step <= 0.0) return false
        val regular = gaps.count { it in (0.55 * step)..(1.7 * step) }
        if (regular < 0.35 * gaps.size) return false
        if (!strict) return true
        val tight = gaps.count { it in (0.75 * step)..(1.35 * step) }
        return tight >= 0.8 * gaps.size
    }

    /**
     * Centros de las anillas con las fundidas separadas: una mancha de largo >= 1.6 veces la mediana se cuenta como
     * k = round(largo / mediana) anillas (2 o 3) repartidas a lo largo de su extensión.
     */
    fun splitFused(along: List<Double>, alongExt: List<Double>): List<Double> {
        val n = along.size
        if (n < 3 || alongExt.size != n) return along
        val med = alongExt.sorted()[n / 2]
        if (med <= 0.0) return along
        val out = ArrayList<Double>(n + 4)
        for (i in 0 until n) {
            val e = alongExt[i]
            val k = if (e >= 1.6 * med) Math.round(e / med).toInt().coerceIn(2, 3) else 1
            if (k == 1) { out.add(along[i]); continue }
            for (j in 0 until k) out.add(along[i] - e / 2 + e * (j + 0.5) / k)
        }
        return out
    }
}
