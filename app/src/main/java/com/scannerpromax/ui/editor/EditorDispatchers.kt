package com.scannerpromax.ui.editor

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Hilos auxiliares del editor. */
internal object EditorDispatchers {
    /**
     * Un único hilo de prioridad baja para trabajo "de relleno" (miniaturas de filtros): nunca compite con el
     * hilo principal ni con la vista previa que el usuario está mirando. Vive mientras viva el proceso.
     */
    val lowPriority: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }, "editor-thumbs").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
