package com.scannerpromax.imaging

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.Locale

/**
 * Rendimiento del procesado según el PROCESADOR del teléfono ([SocProfile]: Kirin, Exynos, Snapdragon, Dimensity/Helio,
 * Tensor, Unisoc...) y el modo de Ajustes › Rendimiento ([PerfMode]; por defecto Máximo). Aplica la [PerfPolicy]:
 *  - hilos de OpenCV (TBB) y de los bucles propios ([Par], teselas de [NaturalColor]);
 *  - prioridad de los hilos de procesado mientras el usuario espera (antes el trabajo pesado iba en un hilo de prioridad
 *    BACKGROUND, que Android confina a los núcleos pequeños y a poca CPU);
 *  - sesión de PerformanceHint (Android 12+) que pide al gobernador frecuencias altas durante el procesado;
 *  - hilos de ONNX Runtime ([ortThreads]) y de pdfsqueeze ([squeezeThreads]); páginas a la vez ([parallelPages]).
 * Sin [init] (tests JVM) todo queda con valores por defecto razonables.
 */
object DevicePerf {

    private const val TAG = "DevicePerf"

    @Volatile var soc: SocProfile = SocProfile.generic(Runtime.getRuntime().availableProcessors())
        private set
    @Volatile var mode: PerfMode = PerfMode.MAX
        private set
    @Volatile var policy: PerfPolicy = PerfPolicy.of(soc, mode, 4L shl 30, 8L shl 30, 12_600_000, false)
        private set
    @Volatile private var tier: DeviceTier? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var cameraActive = false
    @Volatile private var initialized = false

    /** Hilos de ONNX Runtime (intra-op) para los modelos de la app. */
    val ortThreads: Int get() = policy.ortThreads
    /** Hilos de pdfsqueeze. */
    val squeezeThreads: Int get() = policy.squeezeThreads
    /** ¿La app inició la política? (en los tests JVM no). */
    val initializedForApp: Boolean get() = initialized

    /** Renders completos a la vez. */
    val parallelPages: Int get() = policy.parallelPages

    /** Detecta el procesador y aplica la política (idempotente; barato: lectura de /proc y /sys). */
    @Synchronized
    fun init(context: Context, tier: DeviceTier) {
        if (initialized) return
        appContext = context.applicationContext
        this.tier = tier
        soc = runCatching { SocProfile.detect() }.getOrElse { SocProfile.generic(tier.cores) }
        initialized = true
        recompute()
        Log.i(TAG, "SoC ${soc.name} · ${soc.clustersText()} · $policy")
    }

    /** Cambia el modo (Ajustes). */
    fun setMode(m: PerfMode) {
        if (m == mode && initialized) return
        mode = m
        recompute()
    }

    /** La cámara está abierta: en equipos de pocos núcleos se deja uno libre para la vista previa. */
    fun setCameraActive(active: Boolean) {
        cameraActive = active
        applyThreads()
    }

    @Synchronized
    private fun recompute() {
        val t = tier
        val am = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { m -> runCatching { am?.getMemoryInfo(m) } }
        val avail = if (mi.availMem > 0) mi.availMem else 2L shl 30
        val total = if (mi.totalMem > 0) mi.totalMem else 4L shl 30
        policy = PerfPolicy.of(soc, mode, avail, total, t?.maxWorkingPixels ?: 12_600_000, t?.isLowRam ?: false)
        applyThreads()
    }

    private fun applyThreads() {
        val p = policy
        var cv = p.cvThreads; var bands = p.bandThreads
        // Cámara abierta en equipos de <= 4 núcleos: uno libre para la vista previa y el análisis en vivo
        if (cameraActive && soc.totalCores <= 4) { cv = minOf(cv, maxOf(2, soc.totalCores - 1)); bands = minOf(bands, maxOf(2, soc.totalCores - 1)) }
        Par.threads = bands
        Par.applyPriority(p.workerPriority)
        // Teselas de "Color natural": mismos hilos que los bucles propios (solo cambia el reparto, no el cálculo)
        NaturalColor.maxThreads = bands
        NaturalColor.lowEnd = tier?.let { it.isLowRam } ?: false
        Cv.setThreads(cv)
        HeavyWork.applyPriority(p.workerPriority)
    }

    /**
     * Ejecuta [block] (trabajo que el usuario espera: render de una página, detección, lente) con el hilo actual a la
     * prioridad de la política y una sesión de PerformanceHint para él y los trabajadores de [Par]. Restaura al salir.
     */
    inline fun <T> boost(block: () -> T): T {
        val b = beginBoost()
        try { return block() } finally { endBoost(b) }
    }

    class Boost internal constructor(internal val tid: Int, internal val oldPrio: Int?, internal val hint: Any?)

    @PublishedApi internal fun beginBoost(): Boost? {
        if (!initialized) return null
        val tid = runCatching { Process.myTid() }.getOrNull() ?: return null
        val old = runCatching { Process.getThreadPriority(tid) }.getOrNull()
        val want = policy.workerPriority
        // solo se SUBE la prioridad (en Ahorro no se baja la de un hilo ajeno, p. ej. el de la interfaz)
        if (old != null && want < old) runCatching { Process.setThreadPriority(want) }
        // (anidado: la sesión de fuera ya informa)
        val nested = PerfTrace.current?.hint != null
        val hint = if (mode == PerfMode.SAVER || nested) null else PerfHint.begin(appContext, intArrayOf(tid) + Par.workerTids(), mode)
        if (hint != null) PerfTrace.current?.hint = { ns: Long -> PerfHint.report(hint, ns) }
        return Boost(tid, if (old != null && want < old) old else null, hint)
    }

    @PublishedApi internal fun endBoost(b: Boost?) {
        b ?: return
        b.hint?.let { PerfTrace.current?.hint = null; PerfHint.end(it) }
        b.oldPrio?.let { runCatching { Process.setThreadPriority(it) } }
    }

    /**
     * Calienta OpenCV desde un hilo de la prioridad de la política: los hilos de TBB se crean en el primer bucle paralelo y
     * heredan la prioridad del hilo que los crea. Llamar en el arranque (hilo de inicio) tras cargar OpenCV.
     */
    fun warmUpOpenCv() {
        val t = Thread({
            runCatching { Process.setThreadPriority(policy.workerPriority) }
            runCatching {
                val m = org.opencv.core.Mat(1200, 1600, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar(128.0, 128.0, 128.0))
                val o = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.GaussianBlur(m, o, org.opencv.core.Size(0.0, 0.0), 2.0)
                org.opencv.imgproc.Imgproc.resize(m, o, org.opencv.core.Size(800.0, 600.0), 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
                m.release(); o.release()
            }
            // los trabajadores de [Par] también (crearlos ahora evita su arranque en la primera página)
            runCatching { Par.forEach(Par.threads) { _ -> Thread.sleep(1) } }
        }, "imaging-warmup")
        t.start()
    }

    @Volatile private var sustainedOn = false

    /**
     * Modo de rendimiento sostenido de la ventana ([android.view.Window.setSustainedPerformanceMode]) para tandas LARGAS en
     * Máximo: el sistema fija frecuencias que se pueden mantener sin calentarse (menos pico, sin bajones). Solo si el equipo
     * lo admite; se apaga al terminar la tanda.
     */
    fun sustained(window: android.view.Window?, on: Boolean) {
        window ?: return
        val want = on && mode == PerfMode.MAX
        if (want == sustainedOn) return
        val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm?.isSustainedPerformanceModeSupported != true) return
        runCatching { window.setSustainedPerformanceMode(want); sustainedOn = want }
    }

    /** Líneas de diagnóstico para Ajustes › Rendimiento. */
    fun diagnostics(): List<Pair<String, String>> {
        val p = policy
        val s = soc
        val prio = when (p.workerPriority) {
            PerfPolicy.PRIO_DISPLAY -> "alta (pantalla)"
            PerfPolicy.PRIO_FOREGROUND -> "primer plano"
            PerfPolicy.PRIO_BACKGROUND -> "baja (segundo plano)"
            else -> p.workerPriority.toString()
        }
        return listOf(
            "Procesador" to s.name,
            "Núcleos" to "${s.totalCores}: ${s.clustersText()}",
            "Hilos" to "OpenCV ${p.cvThreads} · bucles ${p.bandThreads} · IA ${p.ortThreads} · PDF ${p.squeezeThreads} · ${p.parallelPages} ${if (p.parallelPages == 1) "página" else "páginas"} a la vez",
            "Prioridad" to prio + (if (Build.VERSION.SDK_INT >= 31 && mode != PerfMode.SAVER) " · PerformanceHint" else "") +
                (if ((appContext?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isSustainedPerformanceModeSupported == true) " · rendimiento sostenido en tandas" else ""),
            "GPU (OpenCL)" to Accel.openClStatus(),
            "IA (NNAPI / XNNPACK)" to Accel.ortStatus(),
            "Última página" to PerfTrace.summary(),
        )
    }

    /** Texto de una línea para registros. */
    fun oneLine(): String = String.format(Locale.ROOT, "%s | %s | %s", soc.name, soc.clustersText(), policy)
}

/**
 * Sesiones de PerformanceHintManager (Android 12+; ADPF): el gobernador sube la frecuencia de los núcleos de los hilos de
 * la sesión cuando el trabajo informado dura más que el objetivo. Todo por reflexión-libre con comprobación de versión.
 */
object PerfHint {
    private const val TAG = "PerfHint"

    fun begin(context: Context?, tids: IntArray, mode: PerfMode): Any? {
        if (Build.VERSION.SDK_INT < 31 || context == null || tids.isEmpty()) return null
        return try {
            val phm = context.getSystemService(android.os.PerformanceHintManager::class.java) ?: return null
            // objetivo corto: el trabajo informado (cientos de ms) lo supera siempre -> frecuencias altas
            val target = if (mode == PerfMode.MAX) 8_000_000L else 50_000_000L
            val s = phm.createHintSession(tids, target) ?: return null
            if (Build.VERSION.SDK_INT >= 35) runCatching { s.setPreferPowerEfficiency(false) }
            s
        } catch (t: Throwable) {
            Log.w(TAG, "sin sesión", t); null
        }
    }

    fun report(session: Any, ns: Long) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching { (session as android.os.PerformanceHintManager.Session).reportActualWorkDuration(ns.coerceAtLeast(1)) }
    }

    fun end(session: Any) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching { (session as android.os.PerformanceHintManager.Session).close() }
    }
}
