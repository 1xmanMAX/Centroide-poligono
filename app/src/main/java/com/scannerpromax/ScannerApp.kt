package com.scannerpromax

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.imaging.SuperResolution
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.android.OpenCVLoader

/**
 * Arranque rápido:
 *  - Application.onCreate NO carga nada pesado: OpenCV (libopencv_java4.so, decenas de MB), PdfBox y la carga de
 *    documentos se hacen en un hilo de arranque ([warmUp]). En un Cortex-A53 eso eran varios cientos de ms de
 *    pantalla negra antes incluso de ver el splash.
 *  - [ready] pasa a true cuando todo está listo; MainActivity mantiene el splash hasta entonces
 *    (setKeepOnScreenCondition) y no compone la navegación antes, así que el hilo principal nunca espera.
 *  - Cualquier acceso anticipado es seguro igualmente: [NativeLibs] y las dependencias de [AppContainer] son
 *    `lazy` sincronizados (si alguien llega antes, espera a que termine la inicialización en curso).
 */
class ScannerApp : Application(), ImageLoaderFactory {

    /** Contenedor de dependencias. Su construcción es barata: lo pesado dentro es perezoso. */
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

    private val _ready = MutableStateFlow(false)

    /** true cuando las librerías nativas están cargadas y los documentos leídos del disco. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Ajustes leídos durante el arranque (evita un destello de tema claro/oscuro en el primer frame). */
    @Volatile var initialSettings: AppSettings? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (isDebuggable()) enableStrictMode()
        Thread(::warmUp, "app-init").start()
    }

    private fun warmUp() {
        val t0 = System.nanoTime()
        try {
            // Prioridad alta: el usuario está mirando el splash.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) }
            val c = container
            // PdfBox solo hace falta al exportar: fuera del camino del splash, en un hilo de baja prioridad.
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runCatching { NativeLibs.ensurePdfBox(this) }
            }, "pdfbox-init").start()
            // Los ajustes (DataStore) no dependen de OpenCV: se leen en paralelo con la carga de la .so.
            val settingsThread = Thread({
                runCatching {
                    runBlocking {
                        withTimeoutOrNull(STARTUP_DATA_TIMEOUT_MS) { initialSettings = c.settings.settings.first() }
                    }
                }
            }, "settings-init").also { it.start() }
            runBlocking {
                // Lista de documentos para la primera pantalla. Con tope de tiempo: si el disco va lentísimo se
                // muestra igual la interfaz (los datos llegan por Flow en cuanto estén).
                // DocumentRepository exige un PageProcessor (y por tanto OpenCV) en su constructor; el acceso a
                // c.documents carga OpenCV de forma perezosa en este mismo hilo.
                withTimeoutOrNull(STARTUP_DATA_TIMEOUT_MS) {
                    c.documents.get("")  // espera a que el repositorio termine de leer el disco
                }
            }
            runCatching { settingsThread.join(STARTUP_DATA_TIMEOUT_MS) }
        } catch (t: Throwable) {
            Log.e(TAG, "Error en la inicialización de arranque", t)
        } finally {
            _ready.value = true
            Log.i(TAG, "Arranque listo en ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    // ------------------------------------------------------------------------------------- Coil

    /**
     * Cargador de imágenes global (miniaturas). Caché en memoria acotada según la RAM (≈15 % del heap en gama
     * baja), RGB_565 en gama baja (las miniaturas son JPEG sin alfa: mitad de memoria), fundido corto y sin
     * cabeceras HTTP (solo archivos locales).
     */
    override fun newImageLoader(): ImageLoader {
        val tier = container.deviceTier
        val low = tier.isLowRam
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (low) 0.15 else 0.25)
                    .build()
            }
            .apply {
                if (low) {
                    bitmapConfig(Bitmap.Config.RGB_565)
                    allowRgb565(true)
                }
            }
            .crossfade(if (low) 120 else 180)
            .respectCacheHeaders(false)
            .build()
    }

    // ------------------------------------------------------------------------------------- memoria

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Coil ya recorta su caché por su cuenta (registra sus propios callbacks).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // La caché de vista previa del editor es un Mat nativo de varios MB: se recrea al volver a necesitarla.
            container.pageProcessorIfCreated()?.let { p -> Thread({ p.clearPreviewCache() }, "trim").start() }
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // App en segundo plano: liberar también las miniaturas en memoria.
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
            // La red FSRCNN de super-resolución (pesos nativos) se recarga sola al siguiente uso. release() toma
            // el candado del modelo (puede estar ocupado con una ampliación): en un hilo aparte.
            Thread({ runCatching { SuperResolution.release() } }, "trim-sr").start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION") super.onLowMemory()
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    // ------------------------------------------------------------------------------------- depuración

    private fun isDebuggable() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Solo en debug: registra en Logcat (tag StrictMode) cualquier acceso a disco/red en el hilo principal. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .apply { if (Build.VERSION.SDK_INT >= 26) detectContentUriWithoutPermission() }
                .penaltyLog()
                .build(),
        )
    }

    companion object {
        private const val TAG = "ScannerApp"
        private const val STARTUP_DATA_TIMEOUT_MS = 2_500L
    }
}

/**
 * Inicialización perezosa y segura entre hilos de las librerías nativas/recursos. Llamar a [ensureOpenCv] antes
 * de crear cualquier Mat fuera del flujo normal (p. ej. en pruebas o en un Worker); la app lo hace al arrancar.
 */
object NativeLibs {
    private val openCv: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val ok = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        if (!ok) Log.e("NativeLibs", "No se pudo cargar OpenCV")
        ok
    }

    @Volatile private var pdfBoxReady = false

    /** Carga OpenCV una sola vez (bloquea si otro hilo la está cargando). */
    fun ensureOpenCv(): Boolean = openCv

    /** Inicializa los recursos de PdfBox una sola vez. */
    fun ensurePdfBox(context: Context) {
        if (pdfBoxReady) return
        synchronized(this) {
            if (pdfBoxReady) return
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxReady = true
        }
    }
}
