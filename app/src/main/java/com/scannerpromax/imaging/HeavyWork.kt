package com.scannerpromax.imaging

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Puerta ÚNICA de procesamiento pesado de imagen (fusión de ráfaga, libro, DNI...), compartida por todo el
 * proceso, y su hilo de baja prioridad.
 *
 * - [dispatcher]: 1 hilo cuya prioridad fija [DevicePerf] ([applyPriority]): alta en Máximo/Equilibrado (el usuario
 *   espera la foto procesada; los bucles internos se reparten con [Par] y OpenCV), BACKGROUND en Ahorro de batería.
 * - [run]: 1 permiso; [isBusy] cuenta también lo que espera turno, para que el trabajo de fondo (OCR) ceda.
 *
 * IMPORTANTE: no anidar [run] ni llamar desde dentro a código que también pase por esta puerta (deadlock).
 * CaptureProcessor nunca llama al repositorio con el permiso tomado, así que DocumentRepository puede adoptar
 * esta misma puerta para sus renders.
 */
object HeavyWork {

    /** Prioridad del hilo (la fija [DevicePerf] según el modo de rendimiento; sin él, la de antes: BACKGROUND). */
    @Volatile private var priority: Int = Process.THREAD_PRIORITY_BACKGROUND
    @Volatile private var tid: Int = -1

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            try { tid = Process.myTid(); Process.setThreadPriority(priority) } catch (_: Throwable) { }
            r.run()
        }, "imaging-heavy").apply { isDaemon = true }
    }

    /**
     * Prioridad del hilo pesado. En Máximo/Equilibrado ALTA: el usuario espera el resultado (fusión de ráfaga, lente, libro,
     * DNI) y la prioridad BACKGROUND de antes lo confinaba a los núcleos pequeños (cpuset/cgroup de segundo plano).
     */
    fun applyPriority(p: Int) {
        priority = p
        val t = tid
        if (t > 0) try { Process.setThreadPriority(t, p) } catch (_: Throwable) { }
    }

    /** Hilo único de baja prioridad para el procesado pesado. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val gate = Semaphore(1)
    private val active = AtomicInteger(0)

    /** ¿Hay trabajo pesado en curso o esperando turno? */
    val isBusy: Boolean get() = active.get() > 0

    /** Ejecuta [block] en [dispatcher] con el permiso único tomado. */
    suspend fun <T> run(block: suspend () -> T): T {
        active.incrementAndGet()
        try {
            return withContext(dispatcher) { gate.withPermit { block() } }
        } finally {
            active.decrementAndGet()
        }
    }

    /** Ejecuta [task] en el hilo de baja prioridad sin tomar el permiso (configuración rápida, p. ej. hilos). */
    fun post(task: () -> Unit) {
        executor.execute { try { task() } catch (_: Throwable) { } }
    }
}
