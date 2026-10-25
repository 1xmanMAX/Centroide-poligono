package com.scannerpromax.ocr.indra

/**
 * Filtro de basura evidente para las líneas DUDOSAS (confianza entre [IndraOcr.DOUBT_FLOOR] y [IndraOcr.MIN_CONF]).
 * En campos vacíos, casillas y rayas el reconocedor lee "-", "I", "1", "•" o letras sueltas con confianza baja: eso se
 * descarta; una lectura con al menos dos letras/dígitos y altura de renglón se conserva (marcada como dudosa).
 *
 * Banco (154 líneas bajo 0,5 en 54 imágenes; scripts/garbage.py, mismo criterio): pasan 34. Con ellas en el texto,
 * %palabras igual (76,2) y precisión de palabras 63,3 → 62,5; en la app no entran en el texto plano (solo en Texto en
 * vivo, marcadas, y en la capa del PDF buscable).
 */
object IndraDoubt {
    private const val JUNK = "Il1|!-–—_=~.,:;'\"`•·*()[]{}<>/\\^°ºoO0 "

    /**
     * [hRel] = alto de la línea / mediana de la página; [wRel] = ancho / alto de la propia línea (no se usa: la
     * escritura a mano suelta es corta).
     */
    @Suppress("UNUSED_PARAMETER")
    fun keep(text: String, conf: Float, hRel: Float, wRel: Float, floor: Float = IndraOcr.DOUBT_FLOOR): Boolean {
        val t = text.trim()
        if (conf < floor || t.isEmpty()) return false
        if (t.count { it.isLetterOrDigit() } < 2) return false
        if (t.all { it in JUNK }) return false
        // Trazos/rayas (muy bajos) o varias líneas fundidas (muy altos).
        if (hRel < 0.45f || hRel > 2.6f) return false
        // Solo palabras de 1–2 letras ("a ", "in", "on") a muy baja confianza: ruido de casillas.
        val toks = t.split(' ').filter { it.isNotEmpty() }
        if (conf < 0.4f && toks.all { it.length <= 2 } && t.none { it.isDigit() }) return false
        return true
    }
}
