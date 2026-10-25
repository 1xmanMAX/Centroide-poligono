package com.scannerpromax.imaging

import kotlin.math.PI
import kotlin.math.abs

/**
 * Filtro «One Euro» (Casiez, Roussel y Vogel, CHI 2012): paso bajo exponencial cuyo corte sube con la velocidad de la
 * señal — quieto filtra mucho (sin temblor), en movimiento poco (sin retraso). Implementación propia a partir de la
 * fórmula del artículo: α(fc) = 1 / (1 + τ/Te), τ = 1/(2π·fc), fc = [minCutoff] + [beta]·|dx̂|.
 */
class OneEuroFilter(private val minCutoff: Double, private val beta: Double, private val dCutoff: Double = 1.0) {
    private var x = Double.NaN
    private var dx = 0.0
    private var t = Double.NaN

    fun reset() { x = Double.NaN; dx = 0.0; t = Double.NaN }

    /** Valor filtrado de [value] medido en el instante [tSec] (segundos, creciente). */
    fun filter(value: Double, tSec: Double): Double {
        if (x.isNaN() || t.isNaN() || tSec <= t) {
            if (x.isNaN()) { x = value; dx = 0.0 }
            t = tSec
            return x
        }
        val te = tSec - t
        t = tSec
        val rawDx = (value - x) / te
        dx += alpha(dCutoff, te) * (rawDx - dx)
        val fc = minCutoff + beta * abs(dx)
        x += alpha(fc, te) * (value - x)
        return x
    }

    private fun alpha(cutoff: Double, te: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / te)
    }
}

/**
 * «Encuadre IA» en la vista en vivo: inferencia de la red cada [intervalMs] sobre el frame de análisis, cuadrilátero
 * suavizado con [OneEuroFilter] (coordenadas normalizadas 0..1 del frame ya rotado) y acuerdo con el detector clásico:
 * IoU ≥ [DocumentDetector.NN_AGREE] → contorno verde y autocaptura permitida; si discrepan, ámbar y la autocaptura
 * sólo dispara con el clásico muy seguro. Sin red reciente ([STALE_MS]) el acuerdo es null = comportamiento clásico.
 * Thread-safe (se actualiza desde el hilo de la red y se consulta desde el del análisis).
 */
class LiveNeuralTracker(val intervalMs: Long = 300L) {
    private val filters = Array(8) { OneEuroFilter(MIN_CUTOFF, BETA) }
    private var smoothed: FloatArray? = null
    private var at = 0L
    private var lastRun = 0L

    /** ¿Toca lanzar otra inferencia? (marca la hora si sí). */
    @Synchronized
    fun due(nowMs: Long): Boolean {
        if (nowMs - lastRun < intervalMs) return false
        lastRun = nowMs
        return true
    }

    @Synchronized
    fun clear() { smoothed = null; filters.forEach { it.reset() } }

    /** Nuevo resultado de la red ([q] en px de un frame de [w]x[h]); null o puntuación baja = sin documento. */
    @Synchronized
    fun update(q: NeuralQuad?, w: Int, h: Int, nowMs: Long) {
        if (q == null || q.score < MIN_SCORE || w <= 0 || h <= 0) { clear(); at = nowMs; return }
        val n = FloatArray(8) { if (it % 2 == 0) q.pts[it] / w else q.pts[it] / h }
        val prev = smoothed
        // Salto grande (otro documento, el móvil se movió mucho): se reinicia en vez de arrastrar el filtro
        if (prev == null || convexIou(prev, n, w.toFloat() / h) < 0.5) filters.forEach { it.reset() }
        val t = nowMs / 1000.0
        smoothed = FloatArray(8) { filters[it].filter(n[it].toDouble(), t).toFloat() }
        at = nowMs
    }

    /** Último cuadrilátero suavizado (normalizado) si es reciente, o null. */
    @Synchronized
    fun current(nowMs: Long): FloatArray? = smoothed?.takeIf { nowMs - at <= STALE_MS }?.copyOf()

    /**
     * Acuerdo con el cuadrilátero clásico [classicNorm] (normalizado, frame de aspecto [aspect] = ancho/alto): true si
     * coinciden, false si discrepan (o la red no ve documento), null si no hay inferencia reciente.
     */
    @Synchronized
    fun agreement(classicNorm: FloatArray, aspect: Float, nowMs: Long): Boolean? {
        if (lastRun == 0L || nowMs - at > STALE_MS) return null
        val s = smoothed ?: return false
        return convexIou(s, classicNorm, aspect) >= DocumentDetector.NN_AGREE
    }

    companion object {
        /** Sin red más reciente que esto, el acuerdo es desconocido (null). */
        const val STALE_MS = 1000L
        /** Por debajo de esta puntuación la red «no ve» documento. */
        const val MIN_SCORE = 0.3f
        /** Autocaptura con desacuerdo: sólo si la confianza clásica llega a esto. */
        const val CLASSIC_SURE = 0.85f
        // One Euro en coordenadas normalizadas a ~3 Hz: corte mínimo bajo (quieto = estable), beta moderada.
        private const val MIN_CUTOFF = 0.3
        private const val BETA = 8.0

        /**
         * IoU de dos cuadriláteros CONVEXOS (x,y normalizados; x se escala por [aspect]) por recorte de
         * Sutherland–Hodgman. No convexos → 0.
         */
        fun convexIou(a: FloatArray, b: FloatArray, aspect: Float = 1f): Double {
            val pa = DoubleArray(8) { if (it % 2 == 0) a[it] * aspect.toDouble() else a[it].toDouble() }
            val pb = DoubleArray(8) { if (it % 2 == 0) b[it] * aspect.toDouble() else b[it].toDouble() }
            if (!DocumentDetector.isConvexQuad(FloatArray(8) { pa[it].toFloat() }) || !DocumentDetector.isConvexQuad(FloatArray(8) { pb[it].toFloat() })) return 0.0
            val aa = abs(area(pa)); val ab = abs(area(pb))
            if (aa <= 0 || ab <= 0) return 0.0
            val inter = abs(area(clip(pa, pb)))
            return inter / (aa + ab - inter)
        }

        private fun area(p: DoubleArray): Double {
            val n = p.size / 2
            if (n < 3) return 0.0
            var s = 0.0
            for (i in 0 until n) { val j = (i + 1) % n; s += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1] }
            return s / 2
        }

        /** Polígono [subject] recortado por el convexo [clipper] (ambos en el mismo sentido o no: se normaliza). */
        private fun clip(subject: DoubleArray, clipper: DoubleArray): DoubleArray {
            val c = if (area(clipper) < 0) reverse(clipper) else clipper
            var out = subject.toMutableList()
            val m = c.size / 2
            for (i in 0 until m) {
                if (out.isEmpty()) break
                val ax = c[i * 2]; val ay = c[i * 2 + 1]; val bx = c[(i + 1) % m * 2]; val by = c[(i + 1) % m * 2 + 1]
                fun inside(x: Double, y: Double) = (bx - ax) * (y - ay) - (by - ay) * (x - ax) >= 0
                val inp = out; out = ArrayList(inp.size + 4)
                val k = inp.size / 2
                for (j in 0 until k) {
                    val px = inp[j * 2]; val py = inp[j * 2 + 1]
                    val qx = inp[(j + 1) % k * 2]; val qy = inp[(j + 1) % k * 2 + 1]
                    val pin = inside(px, py); val qin = inside(qx, qy)
                    if (pin) { out.add(px); out.add(py) }
                    if (pin != qin) {
                        val dx = qx - px; val dy = qy - py
                        val den = (bx - ax) * dy - (by - ay) * dx
                        if (den != 0.0) {
                            val t = ((by - ay) * (px - ax) - (bx - ax) * (py - ay)) / den
                            out.add(px + t * dx); out.add(py + t * dy)
                        }
                    }
                }
            }
            return out.toDoubleArray()
        }

        private fun reverse(p: DoubleArray): DoubleArray {
            val n = p.size / 2
            return DoubleArray(p.size) { i -> val k = n - 1 - i / 2; p[k * 2 + i % 2] }
        }

        /**
         * Frame YUV_420_888 de la cámara → ARGB a MITAD de resolución (media 2×2 de Y; U/V ya van a mitad) y ya girado
         * [rotation] grados (0/90/180/270). Devuelve (ancho, alto, píxeles). Conversión BT.601 de rango completo.
         */
        fun yuvToArgbHalf(
            y: java.nio.ByteBuffer, yRowStride: Int, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
            width: Int, height: Int, rotation: Int,
        ): Triple<Int, Int, IntArray> {
            val w = width / 2; val h = height / 2
            val src = IntArray(w * h)
            val yb = y.duplicate().apply { rewind() }; val ub = u.duplicate().apply { rewind() }; val vb = v.duplicate().apply { rewind() }
            val yLim = yb.limit(); val uLim = ub.limit(); val vLim = vb.limit()
            fun yAt(i: Int) = if (i < yLim) yb.get(i).toInt() and 0xFF else 0
            for (r in 0 until h) {
                val y0 = r * 2 * yRowStride; val y1 = y0 + yRowStride
                val uvRow = r * uvRowStride
                for (c in 0 until w) {
                    val x = c * 2
                    val yy = (yAt(y0 + x) + yAt(y0 + x + 1) + yAt(y1 + x) + yAt(y1 + x + 1) + 2) shr 2
                    val ui = uvRow + c * uvPixelStride
                    val uu = (if (ui < uLim) ub.get(ui).toInt() and 0xFF else 128) - 128
                    val vv = (if (ui < vLim) vb.get(ui).toInt() and 0xFF else 128) - 128
                    val rr = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                    val gg = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
                    val bb = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                    src[r * w + c] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            return rotate(src, w, h, rotation)
        }

        /** Gira una imagen ARGB [w]x[h] [rotation] grados en sentido horario. */
        fun rotate(src: IntArray, w: Int, h: Int, rotation: Int): Triple<Int, Int, IntArray> = when (((rotation % 360) + 360) % 360) {
            90 -> Triple(h, w, IntArray(w * h).also { d -> for (y in 0 until h) for (x in 0 until w) d[x * h + (h - 1 - y)] = src[y * w + x] })
            180 -> Triple(w, h, IntArray(w * h).also { d -> for (i in 0 until w * h) d[w * h - 1 - i] = src[i] })
            270 -> Triple(h, w, IntArray(w * h).also { d -> for (y in 0 until h) for (x in 0 until w) d[(w - 1 - x) * h + y] = src[y * w + x] })
            else -> Triple(w, h, src)
        }

        /** [NeuralCorners.Pixels] sobre un ARGB en memoria. */
        fun pixels(w: Int, h: Int, argb: IntArray): NeuralCorners.Pixels = object : NeuralCorners.Pixels(w, h) {
            override fun row(y: Int, dst: IntArray) { System.arraycopy(argb, y * w, dst, 0, w) }
        }

    }
}
