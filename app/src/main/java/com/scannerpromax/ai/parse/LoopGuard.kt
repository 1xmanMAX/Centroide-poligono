package com.scannerpromax.ai.parse

/**
 * Detección de BUCLES EN STREAMING (especificación del banco IA §5.1): a temperatura 0 los modelos pequeños entran a
 * veces en bucle hasta agotar n_predict ("_____" ×500, contar las casillas de la cuadrícula, `<lcel>` ×3000…), lo que
 * en un móvil son 2–4 minutos tirados por página. Cada [checkEvery] trozos se aplica la misma limpieza que al
 * resultado final ([IaText.stripRepetition] / [IaParse.filterSpotLoops] / [IaParse.otslLoop]) y, si ya recortaría al
 * menos [minRemovedChars] caracteres (o un bucle OTSL), se pide parar la generación.
 *
 * Uso: `val guard = LoopGuard(LoopGuard.Mode.TEXT); engine.generate(req) { guard.feed(it) }` y, en el callback de
 * continuar, `!guard.looped`. El texto final se limpia igualmente con los parsers.
 */
class LoopGuard(
    private val mode: Mode,
    private val checkEvery: Int = 32,
    private val minRemovedChars: Int = 64,
) {
    enum class Mode { TEXT, SPOTTING, OTSL }

    private val sb = StringBuilder()
    private var pieces = 0

    /** true en cuanto se detecta el bucle (la generación debe pararse). */
    @Volatile var looped: Boolean = false
        private set

    val text: String get() = sb.toString()

    /** Añade un trozo; devuelve true si se puede seguir generando. */
    fun feed(piece: String): Boolean {
        if (looped) return false
        sb.append(piece)
        pieces++
        if (pieces % checkEvery == 0) looped = check(sb.toString())
        return !looped
    }

    /** Comprobación completa (pura; también se usa en los tests). */
    fun check(t: String): Boolean = isLoop(mode, t, minRemovedChars)

    companion object {
        private val LOC = Regex("<\\|LOC_\\d+\\|>")

        fun isLoop(mode: Mode, t: String, minRemovedChars: Int = 64): Boolean = when (mode) {
            Mode.OTSL -> IaParse.otslLoop(t)
            Mode.TEXT -> {
                val trimmed = t.trimEnd()
                trimmed.length - IaText.stripRepetition(trimmed).length >= minRemovedChars
            }
            Mode.SPOTTING -> {
                // Bucle dentro de un fragmento (texto) o entre fragmentos (misma línea, contadores de casillas).
                val plain = LOC.replace(t, "").trimEnd()
                if (plain.length - IaText.stripRepetition(plain).length >= minRemovedChars) true
                else {
                    val items = IaParse.parseSpotting(t)
                    val kept = IaParse.filterSpotLoops(items)
                    val removed = items.size - kept.size
                    removed >= 6 || items.sumOf { it.text.length } - kept.sumOf { it.text.length } >= minRemovedChars
                }
            }
        }
    }
}
