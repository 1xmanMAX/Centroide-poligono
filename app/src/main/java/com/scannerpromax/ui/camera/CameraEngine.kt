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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Modo de flash de la cámara. */
internal enum class FlashSetting(val label: String) { OFF("Flash apagado"), AUTO("Flash automático"), ON("Flash encendido"), TORCH("Linterna") }

/** Detección en vivo (coordenadas del frame de análisis YA rotado). */
internal data class LiveDetection(
    val quad: Quad,
    val confidence: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val timestamp: Long,
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

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scan-analyzer").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
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
            bindUseCases(p, owner, previewView)
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
        imageAnalysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (!detectionEnabled || released || now - lastAnalysisAt < minIntervalMs) return@setAnalyzer
                lastAnalysisAt = now
                val plane = image.planes[0]
                val rotation = image.imageInfo.rotationDegrees
                val det = detector.detectLive(plane.buffer, image.width, image.height, plane.rowStride, rotation)
                _detection.value = det?.let { LiveDetection(it.quad, it.confidence, it.frameWidth, it.frameHeight, now) }
                // La luz cambia despacio: medirla cada pocos frames basta.
                if (frameCounter++ % 6 == 0) {
                    val q = QualityAnalyzer.analyzeLuma(plane.buffer, image.width, image.height, plane.rowStride)
                    _tooDark.value = q.isTooDark
                }
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

        try {
            p.unbindAll()
            camera = try {
                p.bindToLifecycle(owner, selector, preview, capture, imageAnalysis).also { analysis = imageAnalysis }
            } catch (t: Throwable) {
                // Algunos equipos antiguos no admiten 3 casos de uso simultáneos: sin detección en vivo.
                Log.w(TAG, "Sin análisis en vivo (combinación no soportada)", t)
                imageAnalysis.clearAnalyzer()
                analysis = null
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, capture)
            }
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
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        camera = null
        imageCapture = null
        analysis = null
        try {
            // En el mismo hilo del analizador: garantiza que no hay una detección en curso.
            analysisExecutor.execute { detector.releaseLiveBuffers() }
        } catch (_: Throwable) {
        }
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "CameraEngine"
    }
}
