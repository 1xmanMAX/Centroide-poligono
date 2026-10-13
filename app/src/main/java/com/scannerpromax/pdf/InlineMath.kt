package com.scannerpromax.pdf

/**
 * Limpieza de LaTeX en línea que el modelo mete a veces en el texto de una línea (`\( \text{H}_2\text{O} \)`,
 * `$\alpha$`): en el PDF reconstruido se muestra como texto llano ("H2O", "α"). Lógica pura.
 */
object InlineMath {
    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "theta" to "θ", "lambda" to "λ",
        "mu" to "µ", "pi" to "π", "sigma" to "σ", "omega" to "ω", "Delta" to "Δ", "Omega" to "Ω", "Sigma" to "Σ",
        "times" to "×", "cdot" to "·", "pm" to "±", "div" to "÷", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "rightarrow" to "→", "to" to "→", "leftarrow" to "←",
        "Rightarrow" to "⇒", "infty" to "∞", "degree" to "°", "circ" to "°", "%" to "%", "euro" to "€", "sqrt" to "√",
        "quad" to " ", "qquad" to " ", "," to " ", ";" to " ", "!" to "", " " to " ",
    )
    private val TEXT_CMD = Regex("""\\(?:text|mathrm|mathbf|mathit|operatorname|textbf|textit)\s*\{([^{}]*)\}""")
    private val CMD = Regex("""\\([A-Za-z]+|[%,;! ])""")
    private val SCRIPT = Regex("""[_^]\{([^{}]*)\}|[_^](\S)""")

    fun needsCleaning(s: String): Boolean = s.contains("\\(") || s.contains("\\[") || (s.contains('$') && s.contains('\\'))

    fun plain(s: String): String {
        if (!needsCleaning(s)) return s
        var t = s.replace("\\(", "").replace("\\)", "").replace("\\[", "").replace("\\]", "").replace("$", "")
        repeat(3) { t = TEXT_CMD.replace(t) { it.groupValues[1] } }
        t = SCRIPT.replace(t) { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        t = CMD.replace(t) { m -> SYMBOLS[m.groupValues[1]] ?: "" }
        t = t.replace("{", "").replace("}", "")
        return t.replace(Regex("""\s+"""), " ").trim()
    }
}
