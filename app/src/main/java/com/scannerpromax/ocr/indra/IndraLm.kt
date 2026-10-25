package com.scannerpromax.ocr.indra

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Modelo de lenguaje n-grama de tokens de Indra (lm.npz) y búsqueda en haz CTC para las líneas dudosas
 * (puerto de indra_ocr/lm.py). lm.npz = ZIP de arrays .npy sin pickle: por longitud de historia n, claves ordenadas de
 * los pares (historia, token) con su log-probabilidad (k<n>, v<n>) y claves de historias con su peso de backoff
 * (hk<n>, hb<n>). Una clave empaqueta tokens de [bits] bits (0 = inicio de línea, t + 1 = token t).
 *
 * Banco (notes_indra.md): sin el LM el manuscrito inglés (GNHK) baja de 49,7 a 31,4 % de palabras.
 */
class IndraLm private constructor(
    val order: Int,
    val vocabSize: Int,
    val bits: Int,
    private val k: Array<LongArray>,
    private val v: Array<FloatArray>,
    private val hk: Array<LongArray>,
    private val hb: Array<FloatArray>,
) {
    private val cache = HashMap<Long, Double>()

    /** Cota superior de cualquier valor de [logpKey] (poda de la búsqueda en haz). */
    val maxLogp: Double = run {
        var top = -ln(vocabSize.toDouble())
        for (arr in v) for (x in arr) if (x > top) top = x.toDouble()
        var bo = 0.0
        for (arr in hb) if (arr.isNotEmpty()) bo += max(0.0, arr.max().toDouble())
        top + bo
    }

    private fun find(keys: LongArray, key: Long): Int {
        val i = java.util.Arrays.binarySearch(keys, key)
        return if (i >= 0) i else -1
    }

    /**
     * log P(token | historia) para una clave = las últimas order-1 fichas de la historia y luego el token, empaquetadas
     * (las historias más cortas son los bits bajos: los ceros de la izquierda cuentan como inicio de línea).
     * Mismo resultado que NgramLM.logp_keys.
     */
    fun logpKey(full: Long): Double {
        cache[full]?.let { return it }
        val b = bits
        val ct = full and ((1L shl b) - 1)
        val hist = full ushr b
        val n = order
        val hOk = BooleanArray(n)
        val hbv = DoubleArray(n)
        val pOk = BooleanArray(n)
        val pv = DoubleArray(n)
        for (i in 0 until n) {
            val mask = if (i == 0) 0L else (1L shl (b * i)) - 1
            val hq = hist and mask
            val hi = find(hk[i], hq)
            hOk[i] = hi >= 0
            hbv[i] = if (hi >= 0) hb[i][hi].toDouble() else 0.0
            val pi = find(k[i], (hq shl b) or ct)
            pOk[i] = pi >= 0
            pv[i] = if (pi >= 0) v[i][pi].toDouble() else 0.0
        }
        hOk[0] = true
        var m = 0
        for (i in 1 until n) { if (hOk[i]) m++ else break }
        var chosen = -1
        for (i in m downTo 0) if (pOk[i]) { chosen = i; break }
        val res = if (chosen >= 0) {
            var acc = 0.0
            for (i in chosen + 1..m) acc += hbv[i]
            acc + pv[chosen]
        } else {
            var acc = 0.0
            for (i in 0..m) acc += hbv[i]
            acc - ln(vocabSize.toDouble())
        }
        if (cache.size < CACHE_MAX) cache[full] = res
        return res
    }

    private class Beam(val b: Double, val nb: Double, val s: Double, val steps: List<Int>, val hk: Long)

    /**
     * Búsqueda en haz CTC por prefijos (beam_search de lm.py). [blank]: log-prob del blank por paso [T];
     * [topv]/[topi]: las [kTop] mejores clases por paso (blank incluido) y su log-prob, fila a fila ([T * kTop]).
     * Devuelve (ids de token, paso en que se emitió cada uno).
     */
    fun beamSearch(
        blank: FloatArray, topv: FloatArray, topi: LongArray, kTop: Int, steps: Int,
        alpha: Double, beta: Double, beam: Int = 8, blankSkip: Double = 0.999, candFloor: Double = 1e-3,
    ): Pair<IntArray, IntArray> {
        val b = bits
        val hmask = (1L shl (b * (order - 1))) - 1
        var beams = LinkedHashMap<List<Int>, Beam>()
        beams[emptyList()] = Beam(0.0, Double.NEGATIVE_INFINITY, 0.0, emptyList(), 0L)
        val logSkip = ln(blankSkip)
        val logFloor = ln(candFloor)
        val reach = beta + alpha * maxLogp
        for (t in 0 until steps) {
            val b0 = blank[t].toDouble()
            if (b0 > logSkip) {
                val nb = LinkedHashMap<List<Int>, Beam>()
                for ((p, e) in beams) nb[p] = Beam(lse(e.b, e.nb) + b0, Double.NEGATIVE_INFINITY, e.s, e.steps, e.hk)
                beams = nb
                continue
            }
            val nxt = LinkedHashMap<List<Int>, Beam>()
            fun add(prefix: List<Int>, bb: Double, nbb: Double, s: Double, st: List<Int>, h: Long) {
                val old = nxt[prefix]
                nxt[prefix] = if (old == null) Beam(bb, nbb, s, st, h) else Beam(lse(old.b, bb), lse(old.nb, nbb), old.s, old.steps, old.hk)
            }
            val candC = ArrayList<Int>(kTop)
            val candV = ArrayList<Double>(kTop)
            for (j in 0 until kTop) {
                val c = topi[t * kTop + j].toInt()
                val lv = topv[t * kTop + j].toDouble()
                if (c != 0 && lv >= logFloor) { candC += c; candV += lv }
            }
            for ((prefix, e) in beams) {
                add(prefix, lse(e.b, e.nb) + b0, Double.NEGATIVE_INFINITY, e.s, e.steps, e.hk)
                val last = prefix.lastOrNull()
                for (j in candC.indices) if (candC[j] == last) add(prefix, Double.NEGATIVE_INFINITY, e.nb + candV[j], e.s, e.steps, e.hk)
            }
            val floor = if (beams.size >= beam) nxt.values.minOf { lse(it.b, it.nb) + it.s } else Double.NEGATIVE_INFINITY
            class Ext(val prefix: List<Int>, val c: Int, val newFrom: Double, val s: Double, val st: List<Int>, val h: Long)
            val ext = ArrayList<Ext>()
            for ((prefix, e) in beams) {
                val tot = lse(e.b, e.nb)
                val last = prefix.lastOrNull()
                for (j in candC.indices) {
                    val c = candC[j]
                    val newFrom = if (c == last) e.b + candV[j] else tot + candV[j]
                    if (newFrom == Double.NEGATIVE_INFINITY) continue
                    val q = prefix + c
                    if (q in nxt) add(q, Double.NEGATIVE_INFINITY, newFrom, e.s, e.steps, e.hk)
                    else if (newFrom + e.s + reach >= floor - 1e-9) ext += Ext(prefix, c, newFrom, e.s, e.steps, e.hk)
                }
            }
            for (x in ext) {
                val lmp = logpKey((x.h shl b) or (x.c + 1).toLong())
                add(x.prefix + x.c, Double.NEGATIVE_INFINITY, x.newFrom, x.s + alpha * lmp + beta, x.st + t, ((x.h shl b) or (x.c + 1).toLong()) and hmask)
            }
            val sorted = nxt.entries.sortedByDescending { lse(it.value.b, it.value.nb) + it.value.s }
            beams = LinkedHashMap<List<Int>, Beam>().apply { for (en in sorted.take(beam)) put(en.key, en.value) }
        }
        var best: Map.Entry<List<Int>, Beam>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (en in beams) {
            val sc = lse(en.value.b, en.value.nb) + en.value.s
            if (best == null || sc > bestScore) { best = en; bestScore = sc }
        }
        return best!!.key.toIntArray() to best.value.steps.toIntArray()
    }

    companion object {
        private const val TAG_SHIFT = 61
        /** Tope del caché de log-probabilidades (~15 MB; Python usa 1M entradas, demasiado para gama baja). */
        private const val CACHE_MAX = 200_000

        fun lse(a: Double, b: Double): Double {
            if (a == Double.NEGATIVE_INFINITY) return b
            if (b == Double.NEGATIVE_INFINITY) return a
            val m = max(a, b)
            return m + ln(exp(a - m) + exp(b - m))
        }

        fun load(file: File): IndraLm {
            val arrays = HashMap<String, Npy>()
            ZipFile(file).use { z ->
                for (e in z.entries()) {
                    if (!e.name.endsWith(".npy")) continue
                    z.getInputStream(e).use { arrays[e.name.removeSuffix(".npy")] = Npy.read(it) }
                }
            }
            val order = arrays.getValue("order").long(0).toInt()
            val vocab = arrays.getValue("vocab_size").long(0).toInt()
            val bits = arrays["bits"]?.long(0)?.toInt() ?: 11
            require(order * bits <= TAG_SHIFT) { "orden $order × $bits bits no caben en una clave" }
            return IndraLm(
                order, vocab, bits,
                Array(order) { arrays.getValue("k$it").longs() },
                Array(order) { arrays.getValue("v$it").floats() },
                Array(order) { arrays.getValue("hk$it").longs() },
                Array(order) { arrays.getValue("hb$it").floats() },
            )
        }
    }
}

/** Lector mínimo de .npy (v1/v2/v3, little-endian, u8/i8/f4/f8, orden C). */
internal class Npy(val descr: String, val count: Int, val data: ByteBuffer) {
    fun long(i: Int): Long = when (descr.substring(1)) {
        "u8", "i8" -> data.getLong(i * 8)
        "i4", "u4" -> data.getInt(i * 4).toLong()
        else -> throw IllegalArgumentException("npy $descr no es entero")
    }

    fun longs(): LongArray = LongArray(count).also { data.asLongBuffer().get(it) }

    fun floats(): FloatArray = when (descr.substring(1)) {
        "f4" -> FloatArray(count).also { data.asFloatBuffer().get(it) }
        "f8" -> FloatArray(count) { data.getDouble(it * 8).toFloat() }
        else -> throw IllegalArgumentException("npy $descr no es real")
    }

    companion object {
        fun read(input: InputStream): Npy {
            val din = DataInputStream(input.buffered())
            val magic = ByteArray(6).also { din.readFully(it) }
            require(magic[0] == 0x93.toByte() && String(magic, 1, 5, Charsets.US_ASCII) == "NUMPY") { "no es .npy" }
            val major = din.readUnsignedByte()
            din.readUnsignedByte()
            val hlen = if (major == 1) {
                val a = din.readUnsignedByte(); val b = din.readUnsignedByte(); a or (b shl 8)
            } else {
                val bb = ByteArray(4).also { din.readFully(it) }
                ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int
            }
            val header = String(ByteArray(hlen).also { din.readFully(it) }, Charsets.ISO_8859_1)
            val descr = Regex("'descr':\\s*'([^']+)'").find(header)?.groupValues?.get(1) ?: error("npy sin descr")
            require(descr[0] == '<' || descr[0] == '|') { "npy big-endian no soportado" }
            require(!Regex("'fortran_order':\\s*True").containsMatchIn(header)) { "npy en orden Fortran" }
            val shape = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1) ?: error("npy sin shape")
            val dims = shape.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toLong() }
            val count = dims.fold(1L) { a, d -> a * d }.toInt()
            val size = descr.substring(2).toInt()
            val bytes = ByteArray(count * size).also { din.readFully(it) }
            return Npy(descr, count, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        }
    }
}
