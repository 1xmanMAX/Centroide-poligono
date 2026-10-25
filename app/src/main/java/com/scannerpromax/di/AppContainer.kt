package com.scannerpromax.di

import android.content.Context
import com.scannerpromax.NativeLibs
import com.scannerpromax.ai.LlmEngine
import com.scannerpromax.ai.ModelManager
import com.scannerpromax.data.DocumentRepository
import com.scannerpromax.data.SettingsRepository
import com.scannerpromax.export.ImageExporter
import com.scannerpromax.imaging.DeviceProfiler
import com.scannerpromax.imaging.ImageEnhancer
import com.scannerpromax.imaging.PageProcessor
import com.scannerpromax.ocr.OcrEngine
import com.scannerpromax.pdf.PdfCompressor
import com.scannerpromax.pdf.PdfExporter

/**
 * Inyección de dependencias manual (sin Hilt para mantener el APK liviano y el arranque rápido).
 *
 * Construir el contenedor es barato: solo el perfil del dispositivo y los ajustes se crean en el acto. Todo lo
 * que toca OpenCV, PdfBox, ML Kit o el disco es `lazy` sincronizado y se crea la primera vez que se usa (en la
 * práctica, en el hilo de arranque de [com.scannerpromax.ScannerApp] antes de que se vea la primera pantalla).
 * La API pública (propiedades) no cambia.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val deviceTier = DeviceProfiler.profile(appContext)
    val settings = SettingsRepository(appContext)

    private val pageProcessorLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeLibs.ensureOpenCv()
        // Registra el contexto para que la super-resolución (FSRCNN en assets) pueda cargarse al primer uso.
        ImageEnhancer.init(appContext)
        PageProcessor(deviceTier).also { p ->
            // Orientación automática de páginas nuevas: si está descargada la "Maquetación de páginas", el modelo
            // doc_ori apoya a la orientación por renglones (sin el modelo, sólo renglones; nunca se descarga aquí)
            p.orientationModel = { bmp -> orientationEngine.takeIf { it.isReady() }?.orientationProbs(bmp) }
        }
    }

    /** Modelo de orientación (doc_ori) para la tubería normal: se carga al primer uso y sólo si está descargado. */
    private val orientationEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.parts.LayoutEngine(models, threads = 2)
    }
    val pageProcessor: PageProcessor by pageProcessorLazy

    private val ocrLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OcrEngine(appContext, deviceTier).also {
            // Lector rápido Indra (si está descargado sustituye a ML Kit); ONNX Runtime con hasta 4 hilos.
            it.fastReader = com.scannerpromax.ocr.indra.IndraEngine({ models }, com.scannerpromax.ai.CpuInfo.recommendedThreads().coerceIn(1, 4))
        }
    }
    val ocr: OcrEngine by ocrLazy

    /** El motor OCR solo si ya existe (para liberar memoria sin crearlo). */
    fun ocrIfCreated(): OcrEngine? = if (ocrLazy.isInitialized()) ocr else null

    val documents: DocumentRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DocumentRepository(appContext, pageProcessor, deviceTier).also {
            it.attachOcrEngine(ocr)
            it.aiHook = aiQueue
        }
    }

    val pdfExporter: PdfExporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeLibs.ensurePdfBox(appContext)
        PdfExporter(appContext)
    }

    val pdfCompressor: PdfCompressor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeLibs.ensurePdfBox(appContext)
        PdfCompressor(appContext)
    }

    val imageExporter: ImageExporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ImageExporter(appContext) }

    /** Motor de IA local (llama.cpp). Crearlo no carga la biblioteca nativa: eso ocurre al primer uso. */
    val llm: LlmEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { LlmEngine(appContext) }

    /** Modelos de IA descargables (estado, descargas en segundo plano, borrado). */
    val models: ModelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ModelManager(appContext) { llm } }

    /**
     * Cola de lectura con IA en segundo plano (motor principal de reconocimiento cuando el usuario elige un modelo
     * descargado; además clasificación y rescate de encuadre con el Asistente visual). Crearla no carga modelos.
     */
    val aiQueue: com.scannerpromax.ai.AiRecognitionQueue by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.AiRecognitionQueue(
            appContext, llm, models, settings,
            documents = { documents },
            detector = { pageProcessor.detector },
            tier = deviceTier,
            cloud = cloudRecognizer,
            cloudKeys = cloudKeys,
            cloudUsage = cloudUsage,
        )
    }

    /** IA en la nube (opcional): claves cifradas con el Keystore, contadores de uso y lector. */
    val cloudKeys: com.scannerpromax.ai.cloud.CloudKeyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.cloud.CloudKeyStore(appContext)
    }
    val cloudUsage: com.scannerpromax.ai.cloud.CloudUsageStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.cloud.CloudUsageStore(appContext)
    }
    val cloudRecognizer: com.scannerpromax.ai.cloud.CloudRecognizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.cloud.CloudRecognizer(cloudKeys, cloudUsage)
    }

    /** Reconstrucción de documentos con IA (Word/PDF con herramientas): cola en segundo plano, preferencias y ejecutor. */
    val docGenJobs: com.scannerpromax.ai.docgen.DocGenJobs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.docgen.DocGenJobs(appContext)
    }
    val docGenPrefs: com.scannerpromax.ai.docgen.DocGenPrefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.docgen.DocGenPrefs(appContext)
    }
    val docGenRunner: com.scannerpromax.ai.docgen.DocGenRunner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.docgen.DocGenRunner(appContext, documents, cloudKeys, cloudUsage, imageExporter)
    }

    /** El procesador solo si ya existe (para liberar cachés sin crearlo por accidente). */
    fun pageProcessorIfCreated(): PageProcessor? = if (pageProcessorLazy.isInitialized()) pageProcessor else null
}
