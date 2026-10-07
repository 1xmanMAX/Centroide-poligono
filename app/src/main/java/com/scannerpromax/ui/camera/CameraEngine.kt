package com.scannerpromax.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.DocumentDetector
import com.scannerpromax.imaging.QualityAnalyzer
import com.scannerpromax.imaging.QualityReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Modo de flash de la cámara. */
internal enum class FlashSetting(val label: String) { OFF("Flash apagado"), AUTO("Flash automático"), ON("Flash encendido"), TORCH("Linterna") }

/**
 * Detección en vivo (coordenadas del frame de análisis YA rotado).
 * [cropLeft]..[cropBottom]: zona del frame (normalizada 0..1, ya rotada) que coincide con lo que muestra
 * la vista previa. Sin ViewPort (la foto no se recorta) es el frame completo.
 */
internal data class LiveDetection(
    val quad: Quad,
    val confidence: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val timestamp: Long,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
)

/** Calidad en vivo para guiar al usuario y frenar la autocaptura con fotos movidas o con reflejos. */
internal data class LiveQuality(
    val tooDark: Boolean = false,
    val blurry: Boolean = false,
    val glare: Boolean = false,
    val sharpness: Float = 0f,
)

/** De dónde sale la detección en vivo. */
internal enum class LiveSource {
    /** ImageAnalysis YUV 640x480 (lo normal). */
    ANALYSIS,

    /** Captura de la vista previa (PreviewView.bitmap) a baja frecuencia: cuando el análisis rebajaría la foto o la extensión no lo admite. */
    PREVIEW,
}

/** Resolución REAL de la foto tras enlazar la cámara (lo que se muestra en el chip de la cámara). */
internal data class CaptureInfo(
    val width: Int,
    val height: Int,
    /** Mayor tamaño 4:3 (<= 17 MP) que anuncia la cámara en su modo normal (null si no se pudo leer). */
    val maxWidth: Int?,
    val maxHeight: Int?,
    /** Extensión del fabricante activa ("Auto", "HDR") o null (CameraX normal). */
    val extension: String?,
    val liveSource: LiveSource,
    val hardwareLevel: String,
) {
    val megapixels: Double get() = width.toLong() * height / 1e6

    /** Texto corto del chip: "12 MP · HDR". */
    val chipLabel: String
        get() {
            val mp = megapixels
            val mpText = if (mp >= 9.95) "${mp.roundToInt()} MP" else "%.1f MP".format(mp)
            return if (extension != null) "$mpText · $extension" else mpText
        }

    val details: String
        get() = buildString {
            append("Foto: ${width}×$height (${"%.1f".format(megapixels)} MP)")
            if (maxWidth != null && maxHeight != null && (maxWidth != width || maxHeight != height)) {
                append(" · máximo de la cámara ${maxWidth}×$maxHeight")
            }
            append(if (extension != null) " · procesado $extension del fabricante" else " · procesado de alta calidad")
            if (liveSource == LiveSource.PREVIEW) append(" · detección desde la vista previa")
        }
}

/** Estado del zoom (ratio actual y límites de la cámara enlazada). */
internal data class ZoomInfo(val ratio: Float = 1f, val min: Float = 1f, val max: Float = 1f) {
    val supported: Boolean get() = max > min + 0.05f
}

/**
 * Envoltorio de CameraX: vista previa + captura + detección en vivo (bordes y luz).
 *
 * PRIORIDAD: la calidad de la FOTO. Orden de configuraciones que se prueban al enlazar (la primera cuya foto no
 * baje de resolución gana):
 *  1. Extensión del fabricante (AUTO, si no HDR: multi-cuadro, reducción de ruido y nitidez del ISP, como la
 *     app de cámara de Samsung) + análisis en vivo (si la extensión lo admite).
 *  2. Extensión sin análisis (la detección en vivo se hace con capturas de la vista previa).
 *  3. CameraX normal (MAXIMIZE_QUALITY, peticiones Camera2 de alta calidad) + análisis.
 *  4. CameraX normal sin análisis.
 *
 * Optimizado para gama baja: el análisis corre en un único hilo de baja prioridad, a ~640x480 y con
 * límite de FPS; solo se conserva el último frame (STRATEGY_KEEP_ONLY_LATEST).
 */
internal class CameraEngine(
    private val context: Context,
    private val detector: DocumentDetector,
    private val tier: DeviceTier,
) {
    private val _detection = MutableStateFlow<LiveDetection?>(null)
    val detection: StateFlow<LiveDetection?> = _detection.asStateFlow()

    private val _tooDark = MutableStateFlow(false)
    val tooDark: StateFlow<Boolean> = _tooDark.asStateFlow()

    private val _quality = MutableStateFlow(LiveQuality())
    val quality: StateFlow<LiveQuality> = _quality.asStateFlow()

    private val _hasFlash = MutableStateFlow(false)
    val hasFlash: StateFlow<Boolean> = _hasFlash.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _captureInfo = MutableStateFlow<CaptureInfo?>(null)
    val captureInfo: StateFlow<CaptureInfo?> = _captureInfo.asStateFlow()

    private val _zoom = MutableStateFlow(ZoomInfo())
    val zoom: StateFlow<ZoomInfo> = _zoom.asStateFlow()

    /** Si es false, el analizador descarta frames (p. ej. modo Foto o mientras se procesa). */
    @Volatile var detectionEnabled: Boolean = true

    private val lowEnd = tier.isLowRam || tier.cores <= 4

    /** Intervalo mínimo entre análisis: ~7 fps en gama baja, ~14 fps en el resto. */
    private val minIntervalMs = if (lowEnd) 140L else 70L

    /**
     * Si es true (hay capturas mejorándose en segundo plano), el análisis en vivo baja el ritmo a la mitad para
     * ceder CPU al procesamiento sin que la vista previa ni la interfaz pierdan fluidez.
     */
    @Volatile var backgroundBusy: Boolean = false
    @Volatile private var glareStreak = 0

    /** Media móvil (ms) del coste de cada análisis (detección + calidad), para el limitador adaptativo. */
    @Volatile private var analysisCostMs = 0.0

    /**
     * Limitador de FPS ADAPTATIVO: el hilo de análisis ocupa como mucho ~35 % de un núcleo en gama baja (50 % en
     * el resto). Si detectLive tarda 60 ms en un A53, el análisis baja a ~5 fps en vez de saturar la CPU (y
     * calentar el equipo) compitiendo con la vista previa y la composición.
     */
    private fun currentIntervalMs(): Long {
        val duty = if (lowEnd) 0.35 else 0.5
        var iv = max(minIntervalMs.toDouble(), analysisCostMs / duty)
        if (backgroundBusy) iv *= 2.0
        return iv.toLong().coerceIn(minIntervalMs, 600L)
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scan-analyzer").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var boundOwner: LifecycleOwner? = null
    private var previewViewRef: PreviewView? = null
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * Algunos HAL (emulador, equipos baratos) no devuelven nunca la foto en CAPTURE_MODE_MAXIMIZE_QUALITY (el disparo
     * de AF/precaptura deja la petición colgada). Si ocurre, en esta sesión se pasa a MINIMIZE_LATENCY (misma
     * resolución y JPEG 100, sin la secuencia de 3A previa). Solo se recuerda para siempre si se repite en
     * [HANG_LIMIT] sesiones: un único fallo puntual (antes bastaba uno) dejaba al equipo para siempre sin el
     * enfoque previo al disparo, con fotos de texto pequeño menos nítidas.
     */
    @Volatile private var qualityModeHangs = false

    /** Igual para las extensiones del fabricante: si una foto con extensión no llega, se vuelve a CameraX normal. */
    @Volatile private var extensionHangs = false
    @Volatile private var prefsLoaded = false

    init {
        // Lectura de preferencias fuera del hilo principal (StrictMode).
        analysisExecutor.execute {
            runCatching {
                qualityModeHangs = qualityModeHangs || prefs.getInt(KEY_QUALITY_HANG_COUNT, 0) >= HANG_LIMIT
                extensionHangs = extensionHangs || prefs.getInt(KEY_EXT_HANG_COUNT, 0) >= HANG_LIMIT
                // Clave antigua (un solo fallo = para siempre): se descarta.
                if (prefs.contains(LEGACY_KEY_QUALITY_HANGS)) prefs.edit().remove(LEGACY_KEY_QUALITY_HANGS).apply()
            }
            prefsLoaded = true
        }
    }

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var liveSource: LiveSource = LiveSource.ANALYSIS
    private var activeExtension: Int? = null
    private var zoomLive: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { z ->
        _zoom.value = ZoomInfo(z.zoomRatio, z.minZoomRatio, z.maxZoomRatio)
    }
    private var previewJob: Job? = null
    @Volatile private var previewBusy = false

    /** Pico reciente de nitidez (decae poco a poco): la borrosidad se mide relativa a la propia escena. */
    private var sharpPeak = 0f
    private var flash: FlashSetting = FlashSetting.OFF
    @Volatile private var released = false
    private var lastAnalysisAt = 0L
    private var frameCounter = 0

    // Último enfoque (para no repetirlo si ya está enfocado en el mismo sitio).
    @Volatile private var lastFocusAt = 0L
    @Volatile private var lastFocusOk = false
    @Volatile private var lastFocusX = 0f
    @Volatile private var lastFocusY = 0f
    @Volatile private var lastManualFocusAt = 0L

    fun bind(owner: LifecycleOwner, previewView: PreviewView, initialFlash: FlashSetting) {
        flash = initialFlash
        boundOwner = owner
        previewViewRef = previewView
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            val p = try {
                future.get()
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo obtener la cámara", t)
                _error.value = "No se pudo abrir la cámara"
                return@addListener
            }
            provider = p
            fun proceed() {
                if (released) return
                if (previewView.width > 0 && previewView.height > 0) bindAll()
                else previewView.doOnLayout { if (!released) bindAll() }
            }
            // Extensiones del fabricante: su inicialización carga la biblioteca del proveedor (puede tardar algo).
            val extFuture = try {
                ExtensionsManager.getInstanceAsync(context, p)
            } catch (t: Throwable) {
                Log.w(TAG, "Extensiones no disponibles", t)
                null
            }
            if (extFuture == null) {
                proceed()
            } else {
                extFuture.addListener({
                    extensions = runCatching { extFuture.get() }
                        .onFailure { Log.w(TAG, "Extensiones no disponibles", it) }
                        .getOrNull()
                    proceed()
                }, main)
            }
        }, main)
    }

    private class Attempt(val extension: Int?, val withAnalysis: Boolean)

    /**
     * Enlaza la mejor configuración posible (ver la documentación de la clase). Hilo principal. Devuelve true si
     * la cámara quedó enlazada.
     */
    private fun bindAll(): Boolean {
        val p = provider ?: return false
        val owner = boundOwner ?: return false
        val previewView = previewViewRef ?: return false
        if (released) return false
        _ready.value = false

        val base = try {
            if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
            else CameraSelector.DEFAULT_FRONT_CAMERA
        } catch (t: Throwable) {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val baseInfo = runCatching { base.filter(p.availableCameraInfos).firstOrNull() }.getOrNull()
        val best = bestCaptureSize(baseInfo)
        val level = hardwareLevel(baseInfo)

        val em = extensions
        val extMode: Int? = if (em == null || extensionHangs) null else try {
            when {
                em.isExtensionAvailable(base, ExtensionMode.AUTO) -> ExtensionMode.AUTO
                em.isExtensionAvailable(base, ExtensionMode.HDR) -> ExtensionMode.HDR
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudieron consultar las extensiones", t)
            null
        }
        val attempts = buildList {
            if (em != null && extMode != null) {
                val analysisOk = runCatching { em.isImageAnalysisSupported(base, extMode) }.getOrDefault(false)
                if (analysisOk) add(Attempt(extMode, true))
                add(Attempt(extMode, false))
            }
            add(Attempt(null, true))
            add(Attempt(null, false))
        }
        Log.i(TAG, "Cámara nivel $level, máximo 4:3 ${best?.let { "${it.width}x${it.height}" } ?: "?"}, extensión: ${extMode?.let { extName(it) } ?: "ninguna"}")

        for ((i, a) in attempts.withIndex()) {
            if (released) return false
            val last = i == attempts.lastIndex
            val got = tryBind(p, owner, previewView, base, baseInfo, a) ?: continue
            val gotPx = got.width.toLong() * got.height
            val bestPx = best?.let { it.width.toLong() * it.height } ?: 0L
            // La foto no debe bajar del 75 % del máximo de la cámara, ni de 8 MP si la cámara ofrece 8 MP o más.
            val acceptable = bestPx == 0L || gotPx == 0L || (gotPx >= bestPx * 3 / 4 && !(gotPx < MIN_GOOD_PIXELS && bestPx >= MIN_GOOD_PIXELS))
            if (!acceptable && !last) {
                Log.w(TAG, "Configuración ${describe(a)} da la foto a ${got.width}x${got.height} (máx. ${best?.width}x${best?.height}): se prueba la siguiente")
                continue
            }
            activeExtension = a.extension
            liveSource = if (analysis != null) LiveSource.ANALYSIS else LiveSource.PREVIEW
            val info = CaptureInfo(got.width, got.height, best?.width, best?.height, a.extension?.let { extName(it) }, liveSource, level)
            _captureInfo.value = info.takeIf { gotPx > 0 }
            Log.i(TAG, "Captura a ${got.width}x${got.height} (${"%.1f".format(info.megapixels)} MP) · ${describe(a)} · modo ${if (imageCapture?.captureMode == ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) "MAXIMIZE_QUALITY" else "MINIMIZE_LATENCY"} · nivel $level")
            onBound(owner)
            return true
        }
        Log.e(TAG, "No se pudo iniciar la cámara con ninguna configuración")
        _error.value = "No se pudo iniciar la cámara"
        return false
    }

    /** Enlaza una configuración; devuelve la resolución real de la foto o null si no se pudo. */
    private fun tryBind(
        p: ProcessCameraProvider,
        owner: LifecycleOwner,
        previewView: PreviewView,
        base: CameraSelector,
        baseInfo: CameraInfo?,
        a: Attempt,
    ): Size? {
        // Solo se desvinculan los casos de uso PROPIOS (otra instancia podría estar ya usando la cámara).
        unbindOwn(p)
        camera = null; preview = null; imageCapture = null; analysis = null
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val pv = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val mode = if (qualityModeHangs) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        val ic = buildCapture(mode, previewView.display?.rotation, if (a.extension == null) baseInfo else null)
        val ia = if (a.withAnalysis) buildAnalysis() else null
        val selector = try {
            if (a.extension != null) extensions?.getExtensionEnabledCameraSelector(base, a.extension) ?: return null else base
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo activar la extensión ${describe(a)}", t)
            return null
        }
        // SIN ViewPort: con él, CameraX recorta también la FOTO al aspecto de la vista (en pantallas alargadas o en
        // horizontal se perdería hasta un 40 % de los píxeles). La foto conserva siempre el campo de visión completo
        // del sensor 4:3. Vista previa, análisis y captura comparten aspecto 4:3, así que la superposición
        // (FrameMapping.fillCenter, igual que PreviewView FILL_CENTER) sigue coincidiendo con la foto.
        val group = UseCaseGroup.Builder().apply {
            addUseCase(pv); addUseCase(ic); ia?.let { addUseCase(it) }
        }.build()
        return try {
            val cam = p.bindToLifecycle(owner, selector, group)
            camera = cam; preview = pv; imageCapture = ic; analysis = ia
            ic.resolutionInfo?.resolution ?: Size(0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Configuración no soportada: ${describe(a)}", t)
            ia?.clearAnalyzer()
            runCatching { p.unbind(*listOfNotNull<UseCase>(pv, ic, ia).toTypedArray()) }
            null
        }
    }

    /** Tras enlazar: flash, zoom (se conserva el anterior) y detección en vivo desde la vista previa si hace falta. */
    private fun onBound(owner: LifecycleOwner) {
        val cam = camera ?: return
        _hasFlash.value = cam.cameraInfo.hasFlashUnit()
        applyFlash()
        val wantedZoom = _zoom.value.ratio
        zoomLive?.removeObserver(zoomObserver)
        zoomLive = cam.cameraInfo.zoomState.also { live ->
            live.value?.let { _zoom.value = ZoomInfo(it.zoomRatio, it.minZoomRatio, it.maxZoomRatio) }
            live.observe(owner, zoomObserver)
        }
        if (wantedZoom != 1f) setZoom(wantedZoom)
        if (liveSource == LiveSource.PREVIEW) startPreviewDetection()
        _ready.value = true
        _error.value = null
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun buildCapture(mode: Int, rotation: Int?, hqInfo: CameraInfo?): ImageCapture {
        // CAPTURA A CALIDAD MÁXIMA EN TODOS LOS EQUIPOS:
        //  - Resolución: la MAYOR 4:3 que ofrece la cámara en su modo normal, hasta [MAX_CAPTURE_PIXELS] (~17 MP):
        //    4000x3000 en un S24 Ultra (su sensor de 200 MP entrega por defecto el modo agrupado de 12 MP, mejor
        //    en ruido y rango dinámico). En sensores que anuncian 48-64 MP en el modo normal se usa el siguiente
        //    tamaño 4:3 <= 17 MP. Si ninguno cumple, se acepta el mayor disponible.
        //  - CAPTURE_MODE_MAXIMIZE_QUALITY y JPEG 100 del propio ISP: el archivo se guarda en original/ tal cual.
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .setResolutionFilter { sizes, _ ->
                sizes.filter { it.width.toLong() * it.height <= MAX_CAPTURE_PIXELS }.ifEmpty { sizes }
            }
            .build()
        val builder = ImageCapture.Builder()
            .setCaptureMode(mode)
            .setResolutionSelector(selector)
            .setJpegQuality(100)
            .setFlashMode(flashModeOf(flash))
            .apply { rotation?.let { setTargetRotation(it) } }
        // Sin extensión: pedir explícitamente el procesado de ALTA CALIDAD del ISP (reducción de ruido, nitidez de
        // bordes, aberración cromática, píxeles calientes) si la cámara lo anuncia. La plantilla STILL_CAPTURE suele
        // traerlo ya, pero algunos HAL usan FAST por defecto; con texto pequeño la diferencia se nota.
        if (hqInfo != null) applyHighQualityRequest(builder, hqInfo)
        return builder.build()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyHighQualityRequest(builder: ImageCapture.Builder, info: CameraInfo) {
        try {
            val c2 = Camera2CameraInfo.from(info)
            val ext = Camera2Interop.Extender(builder)
            fun has(key: CameraCharacteristics.Key<IntArray>, mode: Int) =
                c2.getCameraCharacteristic(key)?.contains(mode) == true
            if (has(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
            if (has(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
                ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            }
            if (has(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)) {
                ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            }
            if (has(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)) {
                ext.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
            }
            if (!lowEnd && has(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)) {
                ext.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudieron pedir las opciones de alta calidad", t)
        }
    }

    private fun buildAnalysis(): ImageAnalysis {
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        val qualityEvery = if (lowEnd) 4 else 2
        glareStreak = 0
        imageAnalysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (!detectionEnabled || released || now - lastAnalysisAt < currentIntervalMs()) return@setAnalyzer
                lastAnalysisAt = now
                val plane = image.planes[0]
                val rotation = image.imageInfo.rotationDegrees
                val crop = rotatedCrop(image.cropRect, image.width, image.height, rotation)
                // Planos de croma U/V: la segmentación por color no se deja engañar por sombras ni madera clara.
                val u = image.planes[1]; val v = image.planes[2]
                val det = detector.detectLive(plane.buffer, image.width, image.height, plane.rowStride, rotation,
                    u.buffer, v.buffer, u.rowStride, u.pixelStride)
                _detection.value = det?.let {
                    LiveDetection(it.quad, it.confidence, it.frameWidth, it.frameHeight, now, crop[0], crop[1], crop[2], crop[3])
                }
                // Nitidez/reflejos cada pocos frames (barato a 640 px); la luz cambia despacio.
                if (frameCounter++ % qualityEvery == 0) {
                    val q = detector.analyzeLastFrame()
                        ?: QualityAnalyzer.analyzeLuma(plane.buffer, image.width, image.height, plane.rowStride)
                    publishQuality(q)
                }
                val cost = (SystemClock.elapsedRealtime() - now).toDouble()
                analysisCostMs = if (analysisCostMs == 0.0) cost else analysisCostMs * 0.8 + cost * 0.2
            } catch (t: Throwable) {
                Log.w(TAG, "Fallo en el análisis en vivo", t)
            } finally {
                image.close()
            }
        }
        return imageAnalysis
    }

    /** Publica la calidad en vivo (hilo del analizador). */
    private fun publishQuality(q: QualityReport) {
        sharpPeak = maxOf(q.sharpness, sharpPeak * 0.97f)
        // Borrosa si cae claramente respecto al mejor enfoque reciente de la misma escena.
        val relBlur = sharpPeak > 0.02f && q.sharpness < sharpPeak * 0.55f
        _tooDark.value = q.isTooDark
        // Reflejo persistente (2 análisis seguidos): un destello suelto no convierte el disparo en ráfaga.
        glareStreak = if (q.hasGlare) glareStreak + 1 else 0
        // Nitidez cuantizada: el StateFlow sólo emite (y la pantalla sólo recompone) si algo visible cambia.
        _quality.value = LiveQuality(
            tooDark = q.isTooDark,
            blurry = q.isBlurry || relBlur,
            glare = glareStreak >= 2,
            sharpness = (q.sharpness * 10f).roundToInt() / 10f,
        )
    }

    /**
     * Detección en vivo SIN ImageAnalysis (cuando el análisis rebajaría la foto o la extensión no lo admite):
     * cada ~300-450 ms se toma la imagen de la vista previa (PreviewView.bitmap, hilo principal), se reduce a
     * ~480 px y se detecta en el hilo del analizador. Las coordenadas son las de la VISTA (lo que se ve), que en
     * la disposición vertical normal coincide con el frame 3:4 de la foto.
     */
    private fun startPreviewDetection() {
        if (previewJob?.isActive == true) return
        previewJob = scope.launch {
            var n = 0
            while (isActive) {
                delay(if (lowEnd) 450L else 300L)
                if (released) break
                if (liveSource != LiveSource.PREVIEW || !detectionEnabled || !_ready.value || previewBusy) continue
                val pv = previewViewRef ?: continue
                val full: Bitmap = try {
                    pv.bitmap
                } catch (t: Throwable) {
                    null
                } ?: continue
                previewBusy = true
                val doQuality = n++ % 2 == 0
                try {
                    analysisExecutor.execute {
                        var small: Bitmap? = null
                        try {
                            val long = max(full.width, full.height)
                            small = if (long > PREVIEW_DETECT_SIDE) {
                                val s = PREVIEW_DETECT_SIDE.toFloat() / long
                                Bitmap.createScaledBitmap(full, max(1, (full.width * s).roundToInt()), max(1, (full.height * s).roundToInt()), true)
                            } else full
                            if (small !== full) full.recycle()
                            val now = SystemClock.elapsedRealtime()
                            if (!released && detectionEnabled) {
                                val det = detector.detect(small)
                                _detection.value = det?.let { LiveDetection(it.quad, it.confidence, it.frameWidth, it.frameHeight, now) }
                                if (doQuality) publishQuality(QualityAnalyzer.analyze(small))
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Fallo en la detección desde la vista previa", t)
                        } finally {
                            small?.let { if (!it.isRecycled) it.recycle() }
                            if (!full.isRecycled) full.recycle()
                            previewBusy = false
                        }
                    }
                } catch (t: Throwable) {
                    // Ejecutor cerrado (release).
                    full.recycle()
                    previewBusy = false
                }
            }
        }
    }

    fun setFlash(setting: FlashSetting) {
        flash = setting
        applyFlash()
    }

    private fun applyFlash() {
        runCatching { imageCapture?.flashMode = flashModeOf(flash) }
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) camera?.cameraControl?.enableTorch(flash == FlashSetting.TORCH)
        } catch (_: Throwable) {
        }
    }

    private fun flashModeOf(f: FlashSetting) = when (f) {
        FlashSetting.OFF, FlashSetting.TORCH -> ImageCapture.FLASH_MODE_OFF
        FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
    }

    /** Zoom (se acota a los límites de la cámara). Si la cámara es lógica (varias lentes), el HAL cambia de lente solo. */
    fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val z = _zoom.value
        val r = if (z.supported) ratio.coerceIn(z.min, z.max) else 1f
        _zoom.value = z.copy(ratio = r)
        try {
            cam.cameraControl.setZoomRatio(r)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo cambiar el zoom", t)
        }
    }

    /** Enfoque y exposición en el punto tocado (coordenadas de la PreviewView). */
    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build()
            val now = SystemClock.elapsedRealtime()
            lastManualFocusAt = now
            lastFocusOk = false
            val future = cam.cameraControl.startFocusAndMetering(action)
            future.addListener({
                val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                lastFocusAt = now; lastFocusOk = ok; lastFocusX = x; lastFocusY = y
            }, ContextCompat.getMainExecutor(context))
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo enfocar", t)
        }
    }

    /**
     * Enfoque + medición (AF + AE) en el punto (coordenadas de la vista, normalmente el centro del documento) antes
     * de una foto, esperando al resultado como mucho [timeoutMs]. Para que el disparo siga siendo rápido NO se
     * repite si: el usuario tocó para enfocar hace poco (se respeta su elección) o ya hubo un enfoque correcto en
     * el mismo sitio hace menos de [REFOCUS_AFTER_MS] (el AF sigue bloqueado ahí). Devuelve true si está enfocado.
     */
    suspend fun focusBeforeCapture(previewView: PreviewView, x: Float, y: Float, timeoutMs: Long = 1_500L): Boolean {
        val cam = camera ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastManualFocusAt < MANUAL_FOCUS_HOLD_MS) return true
        val diag = hypot(previewView.width.toFloat(), previewView.height.toFloat()).coerceAtLeast(1f)
        if (lastFocusOk && now - lastFocusAt < REFOCUS_AFTER_MS && hypot(x - lastFocusX, y - lastFocusY) < diag * 0.08f) return true
        return try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build()
            val future = cam.cameraControl.startFocusAndMetering(action)
            val ok = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    future.addListener({
                        val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                        if (cont.isActive) cont.resume(ok)
                    }, ContextCompat.getMainExecutor(context))
                }
            } ?: false
            lastFocusAt = SystemClock.elapsedRealtime(); lastFocusOk = ok; lastFocusX = x; lastFocusY = y
            ok
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "No se pudo enfocar", t)
            false
        }
    }

    /**
     * Captura una foto en [file]. Suspende hasta que el JPEG está escrito.
     * Con extensión o en modo calidad máxima, si la cámara no entrega la foto en [QUALITY_CAPTURE_TIMEOUT_MS] (HAL
     * defectuoso), se vuelve a enlazar sin extensión / en MINIMIZE_LATENCY (misma resolución y JPEG 100) y se repite.
     */
    suspend fun capture(file: File, previewView: PreviewView?): File {
        val ic = imageCapture ?: throw IllegalStateException("La cámara aún no está lista")
        val guarded = activeExtension != null || ic.captureMode == ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        if (!guarded) return takePicture(ic, file, previewView)
        kotlinx.coroutines.withTimeoutOrNull(QUALITY_CAPTURE_TIMEOUT_MS) { takePicture(ic, file, previewView) }?.let { return it }
        val withExtension = activeExtension != null
        val key = if (withExtension) {
            Log.w(TAG, "La cámara no entregó la foto con la extensión ${activeExtension?.let { extName(it) }}: se vuelve a CameraX normal")
            extensionHangs = true
            KEY_EXT_HANG_COUNT
        } else {
            Log.w(TAG, "La cámara no entregó la foto en modo calidad máxima: se pasa a MINIMIZE_LATENCY")
            qualityModeHangs = true
            KEY_QUALITY_HANG_COUNT
        }
        runCatching { analysisExecutor.execute { runCatching { prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply() } } }
        // Con extensión hay que volver a enlazar todo (otro selector de cámara); sin ella basta con sustituir la
        // ImageCapture (más ligero: vista previa y análisis siguen abiertos, sin reconfigurar toda la sesión).
        val fallback = kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (withExtension) imageCapture.takeIf { bindAll() } else rebindCaptureOnly(previewView)
        } ?: throw IllegalStateException("La cámara no respondió")
        // Otro archivo: la petición colgada podría escribir o borrar [file] al cancelarse.
        val retry = File(file.parentFile, "retry_" + file.name)
        // También con límite: si la cámara sigue sin responder, se informa en vez de dejar el obturador colgado.
        // (más margen: en equipos muy lentos la primera foto tras reenlazar tarda en llegar).
        kotlinx.coroutines.withTimeoutOrNull(RETRY_CAPTURE_TIMEOUT_MS) { takePicture(fallback, retry, previewView) }
            ?: run {
                retry.delete()
                throw IllegalStateException("La cámara no respondió")
            }
        if (!retry.renameTo(file)) {
            retry.copyTo(file, overwrite = true)
            retry.delete()
        }
        return file
    }

    /** Sustituye solo la ImageCapture enlazada por otra en MINIMIZE_LATENCY (hilo principal). */
    private fun rebindCaptureOnly(previewView: PreviewView?): ImageCapture? {
        val p = provider ?: return null
        val owner = boundOwner ?: return null
        if (released) return null
        val selector = try {
            if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        } catch (t: Throwable) {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        return try {
            imageCapture?.let { runCatching { p.unbind(it) } }
            val info = runCatching { selector.filter(p.availableCameraInfos).firstOrNull() }.getOrNull()
            val ic = buildCapture(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, previewView?.display?.rotation, info)
            camera = p.bindToLifecycle(owner, selector, ic)
            imageCapture = ic
            applyFlash()
            ic
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo volver a enlazar la captura", t)
            null
        }
    }

    private suspend fun takePicture(ic: ImageCapture, file: File, previewView: PreviewView?): File = suspendCancellableCoroutine { cont ->
        previewView?.display?.rotation?.let { ic.targetRotation = it }
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                if (cont.isActive) cont.resume(file)
            }

            override fun onError(exception: ImageCaptureException) {
                // Solo si seguimos esperando: una petición abandonada (tiempo agotado) no toca el archivo.
                if (cont.isActive) {
                    file.delete()
                    cont.resumeWithException(exception)
                }
            }
        })
    }

    /** ¿Hay una extensión del fabricante (multi-cuadro) activa? Entonces la ráfaga propia de poca luz sobra. */
    val extensionActive: Boolean get() = activeExtension != null

    /**
     * Ráfaga para el modo poca luz / anti-reflejos: captura [files].size fotos seguidas (cada una espera a que
     * la anterior esté escrita, sin acumular buffers). [onShot] recibe cuántas van. Devuelve los archivos
     * capturados (al menos uno); si alguna falla se continúa con las demás.
     */
    suspend fun captureBurst(files: List<File>, previewView: PreviewView?, onShot: (Int) -> Unit = {}): List<File> {
        val ok = ArrayList<File>(files.size)
        var last: Throwable? = null
        for (f in files) {
            try {
                capture(f, previewView)
                ok.add(f)
                onShot(ok.size)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
                f.delete()
            }
        }
        if (ok.isEmpty()) throw last ?: IllegalStateException("No se pudo capturar la ráfaga")
        return ok
    }

    /** Limpia la última detección (tras capturar, para que la autocaptura no se dispare dos veces). */
    fun resetDetection() {
        _detection.value = null
    }

    /** Libera la cámara y los buffers nativos. Idempotente. */
    fun release() {
        if (released) return
        released = true
        scope.cancel()
        try {
            zoomLive?.removeObserver(zoomObserver)
            analysis?.clearAnalyzer()
            provider?.let { unbindOwn(it) }
        } catch (_: Throwable) {
        }
        zoomLive = null
        camera = null
        preview = null
        imageCapture = null
        analysis = null
        previewViewRef = null
        boundOwner = null
        try {
            // En el mismo hilo del analizador: garantiza que no hay una detección en curso.
            analysisExecutor.execute {
                detector.releaseLiveBuffers()
                QualityAnalyzer.releaseLiveBuffers()
            }
        } catch (_: Throwable) {
        }
        analysisExecutor.shutdown()
    }

    /** Desvincula solo los casos de uso de esta instancia (nunca unbindAll: no tocar otra cámara activa). */
    private fun unbindOwn(p: ProcessCameraProvider) {
        analysis?.clearAnalyzer()
        val own = listOfNotNull<UseCase>(preview, imageCapture, analysis)
        if (own.isNotEmpty()) runCatching { p.unbind(*own.toTypedArray()) }
    }

    private fun describe(a: Attempt) =
        (a.extension?.let { "extensión ${extName(it)}" } ?: "CameraX normal") + if (a.withAnalysis) " + análisis" else " sin análisis"

    private fun extName(mode: Int) = when (mode) {
        ExtensionMode.AUTO -> "Auto"
        ExtensionMode.HDR -> "HDR"
        ExtensionMode.NIGHT -> "Noche"
        else -> "Ext"
    }

    /**
     * Mayor tamaño JPEG 4:3 (o el mayor de cualquier aspecto si no hay 4:3) <= [MAX_CAPTURE_PIXELS] que anuncia la
     * cámara en su modo normal: es lo que debería elegir el ResolutionSelector de la captura.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bestCaptureSize(info: CameraInfo?): Size? = try {
        val c2 = info?.let { Camera2CameraInfo.from(it) }
        val map = c2?.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty()
        val fit = sizes.filter { it.width.toLong() * it.height <= MAX_CAPTURE_PIXELS }.ifEmpty { sizes }
        val r43 = fit.filter { kotlin.math.abs(max(it.width, it.height).toFloat() / minOf(it.width, it.height) - 4f / 3f) < 0.02f }
        r43.ifEmpty { fit }.maxByOrNull { it.width.toLong() * it.height }
    } catch (t: Throwable) {
        null
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun hardwareLevel(info: CameraInfo?): String = try {
        val c2 = info?.let { Camera2CameraInfo.from(it) }
        when (c2?.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
    } catch (t: Throwable) {
        "?"
    }

    /** cropRect del buffer (sin rotar) -> [l, t, r, b] normalizado en el frame ya rotado. */
    private fun rotatedCrop(r: android.graphics.Rect, w: Int, h: Int, rotation: Int): FloatArray {
        if (w <= 0 || h <= 0 || r.width() <= 0 || r.height() <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
        val l = r.left.toFloat() / w; val t = r.top.toFloat() / h
        val rr = r.right.toFloat() / w; val b = r.bottom.toFloat() / h
        val out = when (((rotation % 360) + 360) % 360) {
            90 -> floatArrayOf(1f - b, l, 1f - t, rr)
            180 -> floatArrayOf(1f - rr, 1f - b, 1f - l, 1f - t)
            270 -> floatArrayOf(t, 1f - rr, b, 1f - l)
            else -> floatArrayOf(l, t, rr, b)
        }
        for (i in out.indices) out[i] = out[i].coerceIn(0f, 1f)
        return out
    }

    private companion object {
        const val TAG = "CameraEngine"
        /** Tope de la captura: 16-17 MP (4736x3552 entra; 48-200 MP sin agrupar no). */
        const val MAX_CAPTURE_PIXELS = 17_000_000L
        /** Por debajo de esto (en una cámara que ofrece más) se renuncia al análisis/extensión para no perder letra pequeña. */
        const val MIN_GOOD_PIXELS = 8_000_000L
        /** Margen generoso: con flash, HDR y 3A lento un disparo real tarda 1-4 s incluso en gama baja. */
        const val QUALITY_CAPTURE_TIMEOUT_MS = 12_000L
        const val RETRY_CAPTURE_TIMEOUT_MS = 40_000L
        const val PREVIEW_DETECT_SIDE = 480
        const val MANUAL_FOCUS_HOLD_MS = 4_000L
        const val REFOCUS_AFTER_MS = 2_500L
        const val HANG_LIMIT = 2
        const val PREFS = "camera"
        const val KEY_QUALITY_HANG_COUNT = "quality_hang_count"
        const val KEY_EXT_HANG_COUNT = "extension_hang_count"
        const val LEGACY_KEY_QUALITY_HANGS = "quality_mode_hangs"
    }
}
