package com.scannerpromax.ui.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.scannerpromax.imaging.SensorLens

/**
 * Lente de la cámara (Camera2 a través de CameraX):
 *  - Pide al ISP la corrección de DISTORSIÓN geométrica (DISTORTION_CORRECTION_MODE, API 28+) si la cámara la anuncia:
 *    alta calidad en la foto y, para que la vista previa y el análisis tengan la MISMA geometría que la foto (la
 *    superposición del cuadrilátero coincide), rápida (o alta) también en ellos.
 *  - Recoge el resultado de cada foto (modo de distorsión y de aberración aplicados, LENS_DISTORTION, calibración
 *    intrínseca, recorte del zoom) y lo traduce a [SensorLens] para la corrección por software cuando el ISP no corrige.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal class CameraLens {

    /** Último resultado de una foto (no de la vista previa) y cuándo llegó. */
    @Volatile private var lastStill: TotalCaptureResult? = null
    @Volatile private var lastStillAt = 0L

    /** true si se pidió la corrección de distorsión del ISP en la foto. */
    @Volatile var requestedIspDistortion = false
        private set

    val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) == CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                lastStill = result
                lastStillAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Modos de distorsión que anuncia la cámara (vacío antes de API 28 o si no hay). */
    private fun distortionModes(c2: Camera2CameraInfo): IntArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { c2.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) }.getOrNull() ?: IntArray(0)
        } else IntArray(0)

    /**
     * Peticiones de la FOTO: corrección de distorsión de alta calidad y la retrollamada que recoge el resultado.
     * Solo sin extensión del fabricante (con extensión CameraX no admite opciones Camera2).
     */
    fun applyStill(ext: Camera2Interop.Extender<*>, c2: Camera2CameraInfo, enabled: Boolean) {
        ext.setSessionCaptureCallback(callback)
        requestedIspDistortion = false
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val modes = distortionModes(c2)
        val mode = when {
            CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY in modes -> CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            CameraMetadata.DISTORTION_CORRECTION_MODE_FAST in modes -> CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
            else -> return
        }
        setDistortion(ext, mode)
        requestedIspDistortion = true
    }

    /** Foto sin opciones Camera2 (extensión del fabricante): no se pidió la corrección del ISP. */
    fun noStillRequest() { requestedIspDistortion = false }

    /** Vista previa / análisis: la misma corrección (rápida si la hay) para que su geometría coincida con la foto. */
    fun applyStream(ext: Camera2Interop.Extender<*>, c2: Camera2CameraInfo, enabled: Boolean) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val modes = distortionModes(c2)
        val mode = when {
            CameraMetadata.DISTORTION_CORRECTION_MODE_FAST in modes -> CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
            CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY in modes -> CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            else -> return
        }
        setDistortion(ext, mode)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setDistortion(ext: Camera2Interop.Extender<*>, mode: Int) {
        ext.setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, mode)
    }

    /** Olvida el último resultado (antes de cada disparo). */
    fun markCaptureStart(): Long = SystemClock.elapsedRealtime()

    /**
     * Calibración para la foto tomada desde [since] ([markCaptureStart]). [c2] = cámara enlazada; [zoom] = zoom actual;
     * [extension] = foto de una extensión del fabricante (sin resultado Camera2: los datos de las características no se
     * dan por buenos sin comprobarlos con la foto). null si la cámara no informa de su lente.
     */
    fun sensorLens(c2: Camera2CameraInfo?, since: Long, zoom: Float, extension: Boolean): SensorLens? = try {
        c2?.let { build(it, lastStill?.takeIf { lastStillAt >= since && !extension }, zoom, extension) }
    } catch (t: Throwable) {
        Log.w(TAG, "Calibración de la lente no disponible", t)
        null
    }

    private fun build(c2: Camera2CameraInfo, res: TotalCaptureResult?, zoom: Float, extension: Boolean): SensorLens? {
        val api28 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        // ¿Corrigió el ISP? Por el resultado (lo aplicado de verdad) o, sin él, por lo pedido
        val ispDist = if (api28) {
            val applied = res?.get(android.hardware.camera2.CaptureResult.DISTORTION_CORRECTION_MODE)
            if (applied != null) applied != CameraMetadata.DISTORTION_CORRECTION_MODE_OFF else requestedIspDistortion && !extension
        } else false
        val caMode = res?.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_ABERRATION_MODE)
        val ispCa = caMode == CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        // Calibración: la del resultado (cambia con el enfoque y la cámara física activa) o la de las características
        val intr = res?.get(android.hardware.camera2.CaptureResult.LENS_INTRINSIC_CALIBRATION)
            ?: c2.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        var legacy = false
        val dist: FloatArray? = if (api28) {
            res?.get(android.hardware.camera2.CaptureResult.LENS_DISTORTION)
                ?: c2.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION)
        } else {
            legacy = true
            @Suppress("DEPRECATION")
            res?.get(android.hardware.camera2.CaptureResult.LENS_RADIAL_DISTORTION)
                ?: @Suppress("DEPRECATION") c2.getCameraCharacteristic(CameraCharacteristics.LENS_RADIAL_DISTORTION)
        }
        // Sin datos: sólo se informa de lo que hace el ISP (útil para no repetir la corrección)
        val array = (if (api28) c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) else null)
            ?: c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (array == null) return null
        // Cámara lógica con zoom < 1 (gran angular) sin resultado: la calibración no es de la cámara física que dispara
        val logical = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        val usable = intr != null && dist != null && !(logical && res == null && (zoom < 0.95f || zoom > 1.9f))
        // Recorte del zoom: SCALER_CROP_REGION (y CONTROL_ZOOM_RATIO en API 30+) en coordenadas del array
        var crop: IntArray? = null
        val cr = res?.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
        val zr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) res?.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO) else null
        if (cr != null) {
            var l = cr.left.toDouble(); var t = cr.top.toDouble(); var w = cr.width().toDouble(); var h = cr.height().toDouble()
            if (zr != null && zr > 1.01f) {
                val nw = w / zr; val nh = h / zr
                l += (w - nw) / 2; t += (h - nh) / 2; w = nw; h = nh
            }
            // Con la corrección del ISP en OFF, SCALER_CROP_REGION ya va en el sistema previo a la corrección (el de la
            // calibración); con ella activa la calibración no se usa
            crop = intArrayOf(l.toInt(), t.toInt(), w.toInt(), h.toInt())
        } else if (zoom > 1.01f) {
            val w = array.width() / zoom; val h = array.height() / zoom
            crop = intArrayOf(((array.width() - w) / 2).toInt(), ((array.height() - h) / 2).toInt(), w.toInt(), h.toInt())
        }
        val lens = SensorLens(
            arrayW = array.width(), arrayH = array.height(),
            intrinsics = if (usable) intr!! else FloatArray(0),
            distortion = if (usable) dist!! else FloatArray(0),
            legacy = legacy,
            crop = crop,
            ispCorrected = ispDist,
            ispChromaCorrected = ispCa,
            trusted = res != null && !extension,
            source = when {
                res != null -> "resultado"
                extension -> "características (extensión)"
                else -> "características"
            },
        )
        Log.i(TAG, "Lente: ${lens.describe()}")
        return lens
    }

    private companion object {
        const val TAG = "CameraLens"
    }
}
