package com.scannerpromax.ui.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.DeviceProfiler
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.DocumentDetector
import com.scannerpromax.imaging.QualityAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Modo de flash de la cámara. */
internal enum class FlashSetting(val label: String) { OFF("Flash apagado"), AUTO("Flash automático"), ON("Flash encendido"), TORCH("Linterna") }

/**
 * Detección en vivo (coordenadas del frame de análisis YA rotado).
 * [cropLeft]..[cropBottom]: zona del frame (normalizada 0..1, ya rotada) que coincide con lo que muestra
 * la vista previa (ViewPort compartido). Por defecto, el frame completo.
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

/**
 * Envoltorio de CameraX: vista previa + captura + análisis en vivo (detección de bordes y luz).
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

    /** Si es false, el analizador descarta frames (p. ej. modo Foto o mientras se procesa). */
    @Volatile var detectionEnabled: Boolean = true

    private val lowEnd = tier.isLowRam || tier.cores <= 4
    private val highEnd = DeviceProfiler.isHighEnd(tier)

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

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    /** Pico reciente de nitidez (decae poco a poco): la borrosidad se mide relativa a la propia escena. */
    private var sharpPeak = 0f
    private var flash: FlashSetting = FlashSetting.OFF
    @Volatile private var released = false
    private var lastAnalysisAt = 0L
    private var frameCounter = 0

    fun bind(owner: LifecycleOwner, previewView: PreviewView, initialFlash: FlashSetting) {
        flash = initialFlash
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
            // El ViewPort necesita la vista ya medida: si aún no lo está, se espera al primer layout.
            if (previewView.width > 0 && previewView.height > 0 && previewView.viewPort != null) {
                bindUseCases(p, owner, previewView)
            } else {
                previewView.doOnLayout { if (!released) bindUseCases(p, owner, previewView) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(p: ProcessCameraProvider, owner: LifecycleOwner, previewView: PreviewView) {
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // Resolución de captura acorde a la memoria del equipo: más píxeles no sirven si luego no caben.
        val capPixels = tier.maxWorkingPixels.coerceIn(3_000_000, 12_500_000).toDouble()
        val capH = sqrt(capPixels * 3.0 / 4.0)
        val capW = capH * 4.0 / 3.0
        val captureSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(ratio)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(capW.roundToInt(), capH.roundToInt()),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(
                if (highEnd || !lowEnd) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
            )
            .setResolutionSelector(captureSelector)
            .setJpegQuality(if (lowEnd) 90 else 96)
            .setFlashMode(flashModeOf(flash))
            .apply { previewView.display?.rotation?.let { setTargetRotation(it) } }
            .build()

        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(ratio)
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
                    sharpPeak = maxOf(q.sharpness, sharpPeak * 0.97f)
                    // Borrosa si cae claramente respecto al mejor enfoque reciente de la misma escena.
                    val relBlur = sharpPeak > 0.02f && q.sharpness < sharpPeak * 0.55f
                    _tooDark.value = q.isTooDark
                    // Nitidez cuantizada: el StateFlow sólo emite (y la pantalla sólo recompone) si algo
                    // visible cambia, no en cada frame por una variación mínima del valor.
                    // Reflejo persistente (2 análisis seguidos): un destello suelto no convierte el disparo en ráfaga.
                    glareStreak = if (q.hasGlare) glareStreak + 1 else 0
                    _quality.value = LiveQuality(
                        tooDark = q.isTooDark,
                        blurry = q.isBlurry || relBlur,
                        glare = glareStreak >= 2,
                        sharpness = (q.sharpness * 10f).roundToInt() / 10f,
                    )
                }
                val cost = (SystemClock.elapsedRealtime() - now).toDouble()
                analysisCostMs = if (analysisCostMs == 0.0) cost else analysisCostMs * 0.8 + cost * 0.2
            } catch (t: Throwable) {
                Log.w(TAG, "Fallo en el análisis en vivo", t)
            } finally {
                image.close()
            }
        }

        val selector = try {
            if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
            else CameraSelector.DEFAULT_FRONT_CAMERA
        } catch (t: Throwable) {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // ViewPort compartido: los tres casos de uso usan el mismo recorte que la vista previa, así que el
        // cuadrilátero detectado y la guía del DNI coinciden con lo que se ve aunque el análisis use otro aspecto.
        val viewPort = try { previewView.viewPort } catch (_: Throwable) { null }
        fun group(vararg cases: UseCase): UseCaseGroup = UseCaseGroup.Builder().apply {
            viewPort?.let { setViewPort(it) }
            cases.forEach { addUseCase(it) }
        }.build()

        try {
            if (released) return
            // Solo se desvinculan los casos de uso PROPIOS (otra instancia podría estar ya usando la cámara).
            unbindOwn(p)
            camera = try {
                p.bindToLifecycle(owner, selector, group(preview, capture, imageAnalysis)).also { analysis = imageAnalysis }
            } catch (t: Throwable) {
                // Algunos equipos antiguos no admiten 3 casos de uso simultáneos: sin detección en vivo.
                Log.w(TAG, "Sin análisis en vivo (combinación no soportada)", t)
                imageAnalysis.clearAnalyzer()
                analysis = null
                runCatching { p.unbind(preview, capture, imageAnalysis) }
                p.bindToLifecycle(owner, selector, group(preview, capture))
            }
            this.preview = preview
            imageCapture = capture
            _hasFlash.value = camera?.cameraInfo?.hasFlashUnit() == true
            applyFlash()
            _ready.value = true
            _error.value = null
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo iniciar la cámara", t)
            _error.value = "No se pudo iniciar la cámara"
        }
    }

    fun setFlash(setting: FlashSetting) {
        flash = setting
        applyFlash()
    }

    private fun applyFlash() {
        imageCapture?.flashMode = flashModeOf(flash)
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

    /** Enfoque y exposición en el punto tocado (coordenadas de la PreviewView). */
    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo enfocar", t)
        }
    }

    /**
     * Enfoca en el punto (coordenadas de la vista) y espera al resultado del AF, como mucho [timeoutMs].
     * Devuelve true si el AF confirmó el enfoque. Se usa antes de la autocaptura (cámaras baratas "cazan" el foco).
     */
    suspend fun focusAndWait(previewView: PreviewView, x: Float, y: Float, timeoutMs: Long = 900L): Boolean {
        val cam = camera ?: return false
        return try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build()
            val future = cam.cameraControl.startFocusAndMetering(action)
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    future.addListener({
                        val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                        if (cont.isActive) cont.resume(ok)
                    }, ContextCompat.getMainExecutor(context))
                }
            } ?: false
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "No se pudo enfocar", t)
            false
        }
    }

    /** Captura una foto en [file]. Suspende hasta que el JPEG está escrito. */
    suspend fun capture(file: File, previewView: PreviewView?): File = suspendCancellableCoroutine { cont ->
        val ic = imageCapture
        if (ic == null) {
            cont.resumeWithException(IllegalStateException("La cámara aún no está lista"))
            return@suspendCancellableCoroutine
        }
        previewView?.display?.rotation?.let { ic.targetRotation = it }
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                if (cont.isActive) cont.resume(file)
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                if (cont.isActive) cont.resumeWithException(exception)
            }
        })
    }

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
        try {
            analysis?.clearAnalyzer()
            provider?.let { unbindOwn(it) }
        } catch (_: Throwable) {
        }
        camera = null
        preview = null
        imageCapture = null
        analysis = null
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
        val own = listOfNotNull<UseCase>(preview, imageCapture, analysis)
        if (own.isNotEmpty()) runCatching { p.unbind(*own.toTypedArray()) }
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
    }
}
