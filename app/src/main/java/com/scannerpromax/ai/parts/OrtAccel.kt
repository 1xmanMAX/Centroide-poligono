package com.scannerpromax.ai.parts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max
import com.scannerpromax.imaging.Accel

/**
 * Proveedor de ejecución (EP) de ONNX Runtime por modelo y por teléfono: CPU (MLAS), XNNPACK (núcleos ARM con NEON/dotprod)
 * o NNAPI (NPU/DSP/GPU del fabricante: Kirin NPU, Exynos NPU, Hexagon, APU de MediaTek...). El AAR estándar
 * onnxruntime-android (el de la app) trae los tres.
 *
 * La PRIMERA vez que se carga un modelo con [session] y una entrada de prueba ([probe]): se mide la CPU y cada acelerador
 * (2 pasadas tras una de calentamiento) y se comprueba que la salida coincide con la de la CPU ([TOLERANCE] relativa); se
 * queda el más rápido correcto ([choose]) y la decisión se guarda por modelo y equipo (huella de Build). Si un proveedor no
 * se puede crear o falla, CPU. Sin [probe] (modelos de forma variable sin entrada de prueba fiable): CPU.
 */
object OrtAccel {

    // (tipos sin ONNX Runtime en com.scannerpromax.imaging.Accel: el diagnóstico no debe cargar la librería nativa)

    /** Error máximo relativo al rango de la salida de la CPU para aceptar un acelerador. */
    const val TOLERANCE = 0.02

    /** Un acelerador solo se elige si es al menos este factor más rápido que la CPU. */
    const val MIN_GAIN = 0.85

    private val decisions get() = com.scannerpromax.imaging.Accel.decisions
    private val store get() = com.scannerpromax.imaging.Accel.store
    private val nnapiAllowed get() = com.scannerpromax.imaging.Accel.nnapiAllowed
    private val fingerprint get() = com.scannerpromax.imaging.Accel.fingerprint

    /** Opciones de sesión para [ep] con [threads] hilos (intra-op; inter-op 1). Lanza si el EP no está en este ORT. */
    fun options(ep: Accel.Ep, threads: Int, base: (OrtSession.SessionOptions) -> Unit = {}): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        try {
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            o.setInterOpNumThreads(1)
            base(o)
            when (ep) {
                Accel.Ep.CPU -> o.setIntraOpNumThreads(threads)
                Accel.Ep.XNNPACK -> {
                    // XNNPACK usa su propio grupo de hilos: el de la sesión a 1 y sin espera activa (recomendación de ORT)
                    o.setIntraOpNumThreads(1)
                    runCatching { o.addConfigEntry("session.intra_op.allow_spinning", "0") }
                    o.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                Accel.Ep.NNAPI, Accel.Ep.NNAPI_FP16 -> {
                    o.setIntraOpNumThreads(threads)   // nodos que NNAPI no admite: CPU
                    val flags = EnumSet.noneOf(ai.onnxruntime.providers.NNAPIFlags::class.java)
                    if (ep == Accel.Ep.NNAPI_FP16) flags.add(ai.onnxruntime.providers.NNAPIFlags.USE_FP16)
                    o.addNnapi(flags)
                }
            }
            return o
        } catch (t: Throwable) {
            o.close(); throw t
        }
    }

    /**
     * Crea la sesión de [key] con el EP más rápido y correcto. [load] crea la sesión con unas opciones; [probe] la ejecuta
     * con una entrada fija y devuelve la salida aplanada (null = sin prueba: CPU o la decisión guardada).
     */
    fun session(
        key: String, threads: Int, load: (OrtSession.SessionOptions) -> OrtSession,
        probe: ((OrtSession) -> FloatArray)? = null, base: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession {
        val sk = "ep:$key:$fingerprint"
        val cached = store?.get(sk)?.let { n -> Accel.Ep.entries.firstOrNull { it.name == n } }
        if (cached != null) {
            create(cached, threads, load, base)?.let { s -> decisions.putIfAbsent(key, Accel.Decision(cached, 0.0, 0.0, 0.0)); return s }
        }
        val cpu = create(Accel.Ep.CPU, threads, load, base) ?: return load(options(Accel.Ep.CPU, threads, base))
        if (probe == null) { decisions[key] = Accel.Decision(Accel.Ep.CPU, 0.0, 0.0, 0.0); return cpu }
        val (ref, cpuMs) = try { timed(cpu, probe) } catch (t: Throwable) { decisions[key] = Accel.Decision(Accel.Ep.CPU, 0.0, 0.0, 0.0); return cpu }
        val results = ArrayList<Trial>()
        val sessions = HashMap<Accel.Ep, OrtSession>()
        for (ep in candidates()) {
            val s = create(ep, threads, load, base) ?: continue
            val r = try { timed(s, probe) } catch (t: Throwable) { null }
            if (r == null) { s.close(); continue }
            results += Trial(ep, r.second, relErr(ref, r.first))
            sessions[ep] = s
        }
        val best = choose(cpuMs, results)
        for ((ep, s) in sessions) if (ep != best) s.close()
        val chosen = if (best == Accel.Ep.CPU) cpu else { cpu.close(); sessions.getValue(best) }
        val tr = results.firstOrNull { it.ep == best }
        decisions[key] = Accel.Decision(best, tr?.ms ?: cpuMs, cpuMs, tr?.err ?: 0.0)
        store?.put(sk, best.name)
        android.util.Log.i("OrtAccel", "$key: CPU %.1f ms; ".format(cpuMs) + results.joinToString { "${it.ep.label} %.1f ms err %.4f".format(it.ms, it.err) } + " -> ${best.label}")
        return chosen
    }

    data class Trial(val ep: Accel.Ep, val ms: Double, val err: Double)

    /** Elección pura (tests): el más rápido entre los correctos ([TOLERANCE]) que gane al menos un 15 % a la CPU. */
    fun choose(cpuMs: Double, trials: List<Trial>): Accel.Ep {
        val ok = trials.filter { it.err.isFinite() && it.err <= TOLERANCE && it.ms.isFinite() && it.ms > 0 && it.ms <= cpuMs * MIN_GAIN }
        return ok.minByOrNull { it.ms }?.ep ?: Accel.Ep.CPU
    }

    /** Error máximo de [b] respecto de [a], relativo al rango de [a] (Inf si los tamaños difieren). */
    fun relErr(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return Double.POSITIVE_INFINITY
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var e = 0.0
        for (i in a.indices) {
            val v = a[i]; if (v < mn) mn = v; if (v > mx) mx = v
            val d = abs(v - b[i]).toDouble(); if (d.isNaN()) return Double.POSITIVE_INFINITY; if (d > e) e = d
        }
        return e / max(1e-6, (mx - mn).toDouble())
    }

    private fun candidates(): List<Accel.Ep> = buildList {
        add(Accel.Ep.XNNPACK)
        if (nnapiAllowed) { add(Accel.Ep.NNAPI); add(Accel.Ep.NNAPI_FP16) }
    }

    private fun create(ep: Accel.Ep, threads: Int, load: (OrtSession.SessionOptions) -> OrtSession, base: (OrtSession.SessionOptions) -> Unit): OrtSession? =
        try { options(ep, threads, base).use { load(it) } } catch (t: Throwable) { null }

    private fun timed(s: OrtSession, probe: (OrtSession) -> FloatArray): Pair<FloatArray, Double> {
        val out = probe(s)   // calentamiento (y salida de referencia)
        var best = Double.MAX_VALUE
        repeat(2) {
            val t0 = System.nanoTime(); probe(s); best = minOf(best, (System.nanoTime() - t0) / 1e6)
        }
        return out to best
    }

    /**
     * Prueba genérica: entradas float con la forma de la sesión ([dims] para las dimensiones variables, en orden) rellenas
     * con un patrón fijo; devuelve la primera salida float aplanada.
     */
    fun imageProbe(env: OrtEnvironment, dims: LongArray): (OrtSession) -> FloatArray = { s ->
        val tensors = ArrayList<OnnxTensor>()
        try {
            val feed = HashMap<String, OnnxTensor>()
            for ((name, info) in s.inputInfo) {
                val ti = info.info as? ai.onnxruntime.TensorInfo ?: error("entrada no tensor")
                var k = 0
                val shape = LongArray(ti.shape.size) { i -> if (ti.shape[i] > 0) ti.shape[i] else dims.getOrElse(k++) { 1L } }
                val n = shape.fold(1L) { a, b -> a * b }.toInt()
                val data = FloatArray(n) { i -> (((i * 2654435761L) ushr 7) and 0xFF).toFloat() / 255f }
                val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
                tensors += t; feed[name] = t
            }
            s.run(feed).use { r ->
                val o = r[0] as OnnxTensor
                val fb = o.floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        } finally { tensors.forEach { it.close() } }
    }

}
