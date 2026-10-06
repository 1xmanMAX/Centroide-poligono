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
 * - [dispatcher]: 1 hilo con THREAD_PRIORITY_BACKGROUND (como EditorDispatchers): el procesado justo después de
 *   una foto no compite en igualdad con el hilo principal, el RenderThread ni la vista previa de la cámara.
 *   Los hilos del pool de OpenCV que se creen desde aquí heredan esa prioridad ([post] con
 *   [Cv.setCameraActive]).
 * - [run]: 1 permiso; [isBusy] cuenta también lo que espera turno, para que el trabajo de fondo (OCR) ceda.
 *
 * IMPORTANTE: no anidar [run] ni llamar desde dentro a código que también pase por esta puerta (deadlock).
 * CaptureProcessor nunca llama al repositorio con el permiso tomado, así que DocumentRepository puede adoptar
 * esta misma puerta para sus renders.
 */
object HeavyWork {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) { }
            r.run()
        }, "imaging-heavy").apply { isDaemon = true }
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
