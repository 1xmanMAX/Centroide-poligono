package com.scannerpromax.imaging

import java.util.concurrent.ConcurrentHashMap

/**
 * Aceleradores del teléfono (estado y decisiones, sin tipos de ONNX Runtime: leerlo no carga ninguna librería nativa).
 *
 * GPU con OpenCV (T-API/OpenCL): la librería OpenCV 4.10 de Android que usa la app NO está compilada con OpenCL (su
 * getBuildInformation no tiene la línea «OpenCL:») y la API Java no expone UMat, así que no hay ruta de GPU por OpenCV;
 * su HAL Carotene (NEON) y el despacho NEON_DOTPROD/NEON_FP16 sí están. Ver [openClStatus].
 *
 * ONNX Runtime: el AAR onnxruntime-android trae XNNPACK y NNAPI; la elección por modelo la hace
 * [com.scannerpromax.ai.parts.OrtAccel] y queda en [decisions].
 */
object Accel {

    enum class Ep(val label: String) { CPU("CPU"), XNNPACK("XNNPACK"), NNAPI("NNAPI"), NNAPI_FP16("NNAPI FP16") }

    data class Decision(val ep: Ep, val ms: Double, val cpuMs: Double, val maxErr: Double) {
        fun text(): String = when {
            cpuMs <= 0.0 -> ep.label + " (guardado)"
            ep == Ep.CPU -> String.format(java.util.Locale.ROOT, "CPU %.0f ms", cpuMs)
            else -> String.format(java.util.Locale.ROOT, "%s %.0f ms (CPU %.0f ms)", ep.label, ms, cpuMs)
        }
    }

    /** Almacén de decisiones (clave -> nombre del EP); lo conecta la app (SharedPreferences). null = sin caché. */
    interface Store { fun get(key: String): String?; fun put(key: String, value: String) }

    @Volatile var store: Store? = null

    /** Decisiones por modelo (diagnóstico de Ajustes › Rendimiento). */
    val decisions = ConcurrentHashMap<String, Decision>()

    /** ¿Probar NNAPI? (Android 8.1+; en Android 15 está obsoleto pero los controladores siguen funcionando). */
    @Volatile var nnapiAllowed: Boolean = runCatching { android.os.Build.VERSION.SDK_INT >= 27 }.getOrDefault(false)

    /** Huella del equipo para la caché (cambia con una actualización del sistema: los controladores NNAPI cambian). */
    @Volatile var fingerprint: String = runCatching { android.os.Build.FINGERPRINT }.getOrNull() ?: "jvm"

    @Volatile private var openCl: String? = null

    /** Estado de OpenCL en la OpenCV de la app (se lee de su información de compilación). */
    fun openClStatus(): String = openCl ?: runCatching {
        val info = org.opencv.core.Core.getBuildInformation()
        val line = info.lineSequence().firstOrNull { it.trim().startsWith("OpenCL:") }?.substringAfter(':')?.trim()
        val par = info.lineSequence().firstOrNull { it.contains("Parallel framework") }?.substringAfter(':')?.trim()
        val hal = info.lineSequence().firstOrNull { it.contains("Custom HAL") }?.substringAfter(':')?.trim()
        (if (line == null) "no (OpenCV sin OpenCL)" else "OpenCL $line") +
            (par?.let { " · CPU: $it" } ?: "") + (hal?.let { " · HAL $it" } ?: "")
    }.getOrDefault("desconocido").also { openCl = it }

    /** Proveedor elegido por modelo. */
    fun ortStatus(): String = if (decisions.isEmpty()) "aún sin modelos cargados" else
        decisions.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key}: ${it.value.text()}" }
}
