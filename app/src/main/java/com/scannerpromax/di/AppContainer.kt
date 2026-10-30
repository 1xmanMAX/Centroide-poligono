package com.scannerpromax.di

import android.content.Context
import com.scannerpromax.NativeLibs
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
import kotlinx.coroutines.launch

/**
 * Inyección de dependencias manual (sin Hilt para mantener el APK liviano y el arranque rápido).
 *
 * Construir el contenedor es barato: solo el perfil del dispositivo y los ajustes se crean en el acto. Todo lo
 * que toca OpenCV, PdfBox, ONNX Runtime o el disco es `lazy` sincronizado y se crea la primera vez que se usa (en la
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
            // «Encuadre IA»: árbitro neuronal del recorte (captura y Auto-detectar; no en la vista en vivo ni escaneos).
            p.detector.neural = neuralCorners
        }
    }

    /**
     * «Encuadre IA» (DocAligner lcnet100 en assets, 4,8 MB). Usa el ONNX Runtime que llega con Indra (o con la
     * maquetación); mientras no esté descargado no actúa. Sigue el ajuste [com.scannerpromax.data.AppSettings.neuralFraming].
     */
    private val neuralCornersLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.imaging.NeuralCorners(
            modelBytes = { appContext.assets.open(com.scannerpromax.imaging.NeuralCorners.ASSET).use { it.readBytes() } },
            ortReady = ::ortAvailableNow,
            threads = 2,
        ).also { n ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                settings.settings.collect { s -> n.enabled = s.neuralFraming }
            }
        }
    }

    val neuralCorners: com.scannerpromax.imaging.NeuralCorners by neuralCornersLazy

    /** El árbitro neuronal solo si ya existe (para liberar memoria sin crearlo). */
    fun neuralCornersIfCreated(): com.scannerpromax.imaging.NeuralCorners? = if (neuralCornersLazy.isInitialized()) neuralCorners else null

    /** ONNX Runtime (incluido en la app) arrancado o arrancable ahora. */
    private fun ortAvailableNow(): Boolean = com.scannerpromax.ai.parts.OrtNative.ensureLoaded()

    /** Modelo de orientación (doc_ori) para la tubería normal: se carga al primer uso y sólo si está descargado. */
    private val orientationEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.parts.LayoutEngine(models, threads = 2)
    }
    val pageProcessor: PageProcessor by pageProcessorLazy

    private val ocrLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OcrEngine(appContext, deviceTier).also { it.indra = indraEngine }
    }

    /** Indra, el único OCR (ONNX Runtime con hasta 4 hilos). Crearlo no carga nada: eso ocurre al primer OCR. */
    val indraEngine: com.scannerpromax.ocr.indra.IndraEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ocr.indra.IndraEngine({ models }, com.scannerpromax.ai.CpuInfo.recommendedThreads().coerceIn(1, 4)) {
            // Léxico español (hunspell es_ES, ~240 KB comprimido en assets): se carga al primer OCR.
            appContext.assets.open("lexicon/es_ES.aff.gz").use { aff ->
                appContext.assets.open("lexicon/es_ES.dic.gz").use { dic -> com.scannerpromax.ocr.lexicon.Hunspell.load(aff, dic) }
            }
        }.also { engine ->
            // "Mi vocabulario" y la corrección con diccionario siguen a los ajustes.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                settings.settings.collect { s ->
                    if (engine.vocabulary != s.userVocabulary) engine.vocabulary = s.userVocabulary
                    if (engine.spellCheck != s.spellCheck) engine.spellCheck = s.spellCheck
                }
            }
        }
    }

    /**
     * Descarga automática de Indra (primer arranque o cuando falte) y, al terminar, reconocimiento de las páginas
     * pendientes. [com.scannerpromax.ScannerApp] lo arranca tras cargar los documentos.
     */
    val indraProvisioner: com.scannerpromax.ocr.indra.IndraProvisioner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ocr.indra.IndraProvisioner(models, settings) {
            indraEngine.reset()
            aiQueue.resume()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { documents.requeueMissingOcr() }
        }
    }
    val ocr: OcrEngine by ocrLazy

    /** El motor OCR solo si ya existe (para liberar memoria sin crearlo). */
    fun ocrIfCreated(): OcrEngine? = if (ocrLazy.isInitialized()) ocr else null

    val documents: DocumentRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DocumentRepository(appContext, pageProcessor, deviceTier).also {
            it.attachOcrEngine(ocr)
            it.aiHook = aiQueue
            it.onOcrUnavailable = { indraProvisioner.ensure() }
            it.jev = jev
        }
    }

    /** "Mejorar con Jev" (TypeSafe; opcional, con la clave del usuario): fotos, tablas y OCR dudoso. */
    val jev: com.scannerpromax.ai.jev.JevEnhancer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.jev.JevEnhancer(appContext, cloudKeys)
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

    /** Modelos descargables: Indra y la maquetación (estado, descargas en segundo plano, borrado). */
    val models: ModelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ModelManager(appContext) }

    /**
     * Cola en segundo plano de "Volver a reconocer" y de la maquetación por partes (si está descargada). Crearla no
     * carga modelos.
     */
    val aiQueue: com.scannerpromax.ai.AiRecognitionQueue by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.scannerpromax.ai.AiRecognitionQueue(appContext, models, documents = { documents }, tier = deviceTier).also {
            it.ocrReady = { indraEngine.isReady() }
            it.onOcrMissing = { indraProvisioner.ensure() }
        }
    }

    /**
     * IA en la nube (opcional, con la clave del usuario): claves cifradas con el Keystore, contadores de uso y
     * proveedores. Ya no es un motor de reconocimiento (el texto lo pone Indra): se usa para la reconstrucción.
     */
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
