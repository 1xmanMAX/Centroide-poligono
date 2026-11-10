package com.scannerpromax.imaging

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Orientación automática de una página: VOTO de las señales disponibles (JVM puro, con tests y banco
 * [com.scannerpromax.imaging.OrientationBenchTest]). Cada señal da una distribución sobre el giro HORARIO que endereza la
 * página (0/90/180/270) y un peso; se suman los logaritmos y se gira sólo si el ganador tiene probabilidad alta:
 *  - RENGLONES ([TextOrientation]): eje (vecinos de cada carácter; las tiras de renglón valen menos) y sentido (ascendentes
 *    frente a descendentes, más fiable cuantos más renglones);
 *  - doc_ori (PP-LCNet, opcional): probabilidades de que la página TENGA un giro horario de 0/90/180/270;
 *  - Jev (opcional, solo con «Mejorar con Jev» activo, consentimiento y clave): probabilidades de cada sentido de lectura
 *    sobre la foto ([com.scannerpromax.ai.jev.JevCapture.orientation]), ya como giro que endereza.
 * Sin señales claras: 0 (no girar). 180° exige más (una página derecha puesta del revés se nota mucho) y los renglones
 * horizontales seguros y derechos sólo los contradicen dos señales a la vez.
 */
object OrientationVote {

    /** [rotation] = giro horario a aplicar (0 = no girar); [confidence] = probabilidad del ganador; [why] = para el registro. */
    class Decision(val rotation: Int, val confidence: Double, val probs: DoubleArray, val why: String) {
        override fun toString() = "giro=$rotation p=%.2f %s".format(java.util.Locale.ROOT, confidence, why)
    }

    /**
     * Probabilidad mínima del ganador para girar 90/270 y 180 (banco de orientación, OrientationBenchTest: ajustadas
     * penalizando 4 veces un giro erróneo frente a uno que falta).
     */
    const val MIN_P = 0.65
    const val MIN_P_180 = 0.65
    /** Pesos (exponentes) de cada señal en el voto. */
    const val W_LINES = 1.0
    const val W_MODEL = 1.5
    const val W_JEV = 1.5
    /** Fiabilidad del eje de los renglones: por vecinos de los caracteres y por tiras de renglón. */
    const val AXIS_NEIGHBORS = 0.90
    const val AXIS_STRIPS = 0.75
    private const val SENSE_MAX = 0.48
    private const val EPS = 0.02

    /**
     * [lines] = análisis de renglones (null si no se pudo); [modelHas] = doc_ori (prob. de que la imagen TENGA giro
     * horario 0/90/180/270) o null; [jevFix] = Jev (prob. de que el giro que ENDEREZA sea 0/90/180/270) o null.
     */
    internal fun decide(lines: TextOrientation.Result?, modelHas: FloatArray?, jevFix: FloatArray?): Decision {
        val score = DoubleArray(4)
        val parts = ArrayList<String>()
        var signals = 0
        lines?.let { linesDist(it) }?.let { d -> add(score, d, W_LINES); signals++; parts += "renglones=" + fmt(d) }
        modelHas?.takeIf { it.size >= 4 && it.all { v -> v.isFinite() && v >= 0f } && it.sum() > 0f }?.let { p ->
            // la imagen TIENE k·90 horario -> se endereza girando (360 − k·90)
            val d = DoubleArray(4) { k -> p[(4 - k) % 4].toDouble() }.normalized()
            add(score, d, W_MODEL); signals++; parts += "doc_ori=" + fmt(d)
        }
        jevFix?.takeIf { it.size >= 4 && it.all { v -> v.isFinite() && v >= 0f } && it.sum() > 0f }?.let { p ->
            val d = DoubleArray(4) { p[it].toDouble() }.normalized()
            add(score, d, W_JEV); signals++; parts += "jev=" + fmt(d)
        }
        if (signals == 0) return Decision(0, 0.0, doubleArrayOf(1.0, 0.0, 0.0, 0.0), "sin señales")
        val m = score.max()
        val post = DoubleArray(4) { kotlin.math.exp(score[it] - m) }.normalized()
        var k = 0; for (i in 1..3) if (post[i] > post[k]) k = i
        val need = if (k == 2) MIN_P_180 else MIN_P
        // 180° con SOLO los renglones: además el análisis tiene que estar seguro (muchos renglones, sin tiras)
        val alone180 = k == 2 && signals == 1 && lines != null && !(lines.rotation == 180 && lines.sure)
        val rot = if (k != 0 && post[k] >= need && !alone180) k * 90 else 0
        return Decision(rot, post[k], post, parts.joinToString(" ") + " -> " + fmt(post))
    }

    /**
     * Distribución de los renglones: eje (vertical / horizontal / dudoso) × sentido. Con eje dudoso no aporta nada (null):
     * así la decisión queda en manos de los modelos (y sin ellos, no se gira).
     */
    internal fun linesDist(r: TextOrientation.Result): DoubleArray? {
        val v = r.vertical ?: return null
        // fiabilidad del eje: vecinos de los caracteres (mayoría clara) o tiras de renglón (más débil)
        val axis = if (r.fromStrips) AXIS_STRIPS else AXIS_NEIGHBORS
        // sentido: evidencia de ascendentes (|up|) y número de renglones medidos
        val strength = min(1.0, abs(r.up) / 0.5) * min(1.0, sqrt(r.lines / 16.0))
        val sense = 0.5 + SENSE_MAX * strength
        val pos = r.up > 0
        val d = DoubleArray(4)
        if (v) {
            // renglones verticales: up > 0 -> girar 90 (horario), si no 270
            d[1] = axis * (if (pos) sense else 1 - sense); d[3] = axis * (if (pos) 1 - sense else sense)
            d[0] = (1 - axis) / 2; d[2] = (1 - axis) / 2
        } else {
            d[0] = axis * (if (pos) sense else 1 - sense); d[2] = axis * (if (pos) 1 - sense else sense)
            d[1] = (1 - axis) / 2; d[3] = (1 - axis) / 2
        }
        return d.normalized()
    }

    private fun add(score: DoubleArray, d: DoubleArray, w: Double) { for (i in 0..3) score[i] += w * ln(d[i] + EPS) }

    private fun DoubleArray.normalized(): DoubleArray { val s = sum(); return if (s <= 0) DoubleArray(size) { 0.25 } else DoubleArray(size) { this[it] / s } }

    private fun fmt(d: DoubleArray) = d.joinToString(",", "[", "]") { "%.2f".format(java.util.Locale.ROOT, it) }
}
