package com.scannerpromax.imaging

import kotlin.math.max

/** Lógica pura (sin OpenCV, probada con tests JVM): estadísticas robustas para la segmentación de la escritura. */
internal object HwStats {
    /** Percentil [p] (0..1) de [values] ponderado por [weights]; null si no hay peso. */
    fun weightedPercentile(values: List<Double>, weights: List<Double>, p: Double): Double? {
        if (values.isEmpty() || values.size != weights.size) return null
        val idx = values.indices.filter { weights[it] > 0 }.sortedBy { values[it] }
        val total = idx.sumOf { weights[it] }
        if (total <= 0) return null
        val target = total * p.coerceIn(0.0, 1.0)
        var acc = 0.0
        for (i in idx) { acc += weights[i]; if (acc >= target) return values[i] }
        return values[idx.last()]
    }

    /** Resultado de [twoInk]: nivel de la tinta principal y fracción (por área) de la tinta débil. */
    data class TwoInk(val front: Double, val faintFrac: Double, val faintMed: Double, val detected: Boolean)

    /**
     * ¿Hay dos poblaciones de tinta (principal + transparencia del reverso o manchas)? [strength] = oscuridad
     * máxima de cada componente, [area] = su área. Tinta principal = percentil 70 ponderado por área; débil =
     * componentes con fuerza < [FAINT_REL] de ella. Se detecta si la débil ocupa >= 20 % del área y su mediana es
     * <= [FAINT_MED] de la principal (dos poblaciones claramente separadas, no un degradado continuo).
     */
    fun twoInk(strength: List<Double>, area: List<Double>): TwoInk {
        val front = weightedPercentile(strength, area, 0.7) ?: return TwoInk(0.0, 0.0, 0.0, false)
        val fi = strength.indices.filter { strength[it] < FAINT_REL * front }
        val tot = area.sum()
        val ff = if (tot > 0) fi.sumOf { area[it] } / tot else 0.0
        val fm = weightedPercentile(fi.map { strength[it] }, fi.map { area[it] }, 0.5) ?: 0.0
        return TwoInk(front, ff, fm / max(1.0, front), front >= 40 && ff >= 0.2 && fm <= FAINT_MED * front)
    }

    const val FAINT_REL = 0.4
    const val FAINT_MED = 0.25

    /**
     * ¿Escritura clara sobre fondo oscuro (pizarra, pantalla en modo oscuro)? [bright] / [dark] = fracción de píxeles
     * mucho más claros / mucho más oscuros que su entorno. En papel la escritura es lo oscuro (bright/dark <= ~1.2
     * en el banco, incluso con brillos y sombras); en una pizarra, lo claro (>= ~3). Se exige además contenido
     * claro apreciable (>= 2.5 % de la imagen: la escritura de una pizarra), no unos reflejos o los detalles claros
     * de una foto oscura.
     */
    fun isLightOnDark(bright: Double, dark: Double): Boolean =
        bright >= 0.025 && bright >= LIGHT_ON_DARK_RATIO * max(dark, 1e-4)

    const val LIGHT_ON_DARK_RATIO = 2.8
}
