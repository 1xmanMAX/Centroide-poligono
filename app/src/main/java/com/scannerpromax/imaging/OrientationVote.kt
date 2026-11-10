package com.scannerpromax.imaging

/**
 * Orientación automática de una página: VOTO de las señales disponibles (JVM puro, con tests y banco
 * OrientationBenchTest). Señales:
 *  - RENGLONES ([TextOrientation]): eje por los vecinos de cada carácter (o, más débil, por tiras de renglón) y sentido por
 *    ascendentes frente a descendentes. Cuando está SEGURO casi nunca se equivoca, pero en fotos se abstiene a menudo.
 *  - doc_ori (PP-LCNet, opcional): probabilidades de que la página TENGA un giro horario de 0/90/180/270. La señal más
 *    fuerte del banco (≈ 98 % de aciertos a p ≥ 0,4, con ≈ 1 % de giros erróneos).
 *  - Jev (opcional, solo con «Mejorar con Jev» activo, consentimiento y clave): probabilidades de cada sentido sobre la
 *    foto ([com.scannerpromax.ai.jev.JevCapture.orientation]), ya como giro que endereza. Sin medir con la API.
 *
 * Reglas (en este orden):
 *  1. Los modelos (doc_ori y Jev; si están los dos, producto normalizado de sus probabilidades) proponen su giro más
 *     probable si llega a [MIN_P].
 *  2. Si los renglones están SEGUROS (por vecinos, no por tiras) y dicen otra cosa, mandan ellos salvo que los modelos
 *     superen [VETO_P] (y nunca para poner la página del revés: en ese caso no se gira).
 *  3. Sin modelos: solo los renglones seguros (lo de siempre).
 * Banco (175 páginas derechas ×4 giros, escaneos + fotos): cadena anterior 98,1 % aciertos / 1,3 % giros erróneos; esta
 * 97,4 % / 0,6 % (la mitad de giros erróneos). Sin modelos ni Jev: igual que antes (63,6 % / 0,7 %).
 */
object OrientationVote {

    /** [rotation] = giro horario a aplicar (0 = no girar); [confidence] = probabilidad del ganador; [why] = para el registro. */
    class Decision(val rotation: Int, val confidence: Double, val why: String) {
        override fun toString() = "giro=$rotation p=%.2f %s".format(java.util.Locale.ROOT, confidence, why)
    }

    /** Probabilidad mínima del giro propuesto por los modelos (también para 180°). */
    const val MIN_P = 0.45
    /** Los modelos se imponen a unos renglones seguros solo por encima de esto. */
    const val VETO_P = 0.7

    /**
     * [lines] = análisis de renglones (null si no se pudo); [modelHas] = doc_ori (prob. de que la imagen TENGA giro
     * horario 0/90/180/270) o null; [jevFix] = Jev (prob. de que el giro que ENDEREZA sea 0/90/180/270) o null.
     */
    internal fun decide(lines: TextOrientation.Result?, modelHas: FloatArray?, jevFix: FloatArray?): Decision {
        val model = valid(modelHas)?.let { p -> DoubleArray(4) { k -> p[(4 - k) % 4].toDouble() }.normalized() }
        val jev = valid(jevFix)?.let { p -> DoubleArray(4) { p[it].toDouble() }.normalized() }
        val q = when {
            model != null && jev != null -> DoubleArray(4) { model[it] * jev[it] }.normalized()
            else -> model ?: jev
        }
        // opinión de los renglones: solo cuando el análisis está seguro
        val linesSure = lines?.takeIf { it.sure && it.vertical != null }
        val strongLines = linesSure?.takeIf { !it.fromStrips }
        val src = listOfNotNull(model?.let { "doc_ori=" + fmt(it) }, jev?.let { "jev=" + fmt(it) }, lines?.let { "renglones=(${it.rotation}${if (it.sure) " seguro" else ""}${if (it.fromStrips) " tiras" else ""})" })
            .joinToString(" ")
        if (q == null) {
            val r = linesSure?.rotation ?: 0
            return Decision(r, if (linesSure != null) 1.0 else 0.0, "$src -> solo renglones")
        }
        var k = 0; for (i in 1..3) if (q[i] > q[k]) k = i
        val pk = q[k]
        val proposed = if (pk >= MIN_P) k * 90 else 0
        if (strongLines != null && strongLines.rotation != proposed) {
            if (pk >= VETO_P && proposed != 180) return Decision(proposed, pk, "$src -> modelos (contra los renglones)")
            val r = strongLines.rotation.takeIf { it != 180 } ?: 0
            return Decision(r, pk, "$src -> renglones (contra los modelos)")
        }
        return Decision(proposed, pk, "$src -> modelos")
    }

    private fun valid(p: FloatArray?): FloatArray? = p?.takeIf { it.size >= 4 && it.all { v -> v.isFinite() && v >= 0f } && it.sum() > 0f }

    private fun DoubleArray.normalized(): DoubleArray { val s = sum(); return if (s <= 0) DoubleArray(size) { 0.25 } else DoubleArray(size) { this[it] / s } }

    private fun fmt(d: DoubleArray) = d.joinToString(",", "[", "]") { "%.2f".format(java.util.Locale.ROOT, it) }
}
