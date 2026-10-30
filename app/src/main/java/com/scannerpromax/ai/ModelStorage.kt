package com.scannerpromax.ai

import java.io.File

/**
 * Archivos de los modelos en disco: `<root>/<id>/<archivo>` (root = filesDir/models). Java puro: testeable en JVM.
 *
 * Un modelo está listo cuando todos sus archivos están completos (tamaño esperado).
 *
 * Solo se guardan DATOS (pesos ONNX, tablas): ningún código ejecutable se descarga (ONNX Runtime nativo va en el
 * AAB/APK; política de Google Play sobre descarga de código).
 */
class ModelStorage(val root: File) {

    fun dir(model: AiModel): File = File(root, model.id)

    fun file(model: AiModel, f: ModelFile): File = File(dir(model), f.fileName)

    fun partFile(model: AiModel, f: ModelFile): File = File(dir(model), f.fileName + ".part")

    fun isComplete(model: AiModel, f: ModelFile): Boolean {
        val file = file(model, f)
        return file.isFile && (f.sizeBytes <= 0 || file.length() == f.sizeBytes)
    }

    fun isReady(model: AiModel): Boolean = model.files.all { f -> isComplete(model, f) }

    /**
     * Borra las librerías nativas que versiones anteriores (≤ v30, fuera de Play) descargaban en `<id>/lib/`: ya no se
     * usan (ONNX Runtime va en la app) y no deben quedarse en el almacenamiento interno. Devuelve los bytes liberados.
     */
    fun purgeLegacyNativeLibs(): Long = root.listFiles()?.sumOf { d ->
        val lib = File(d, "lib")
        if (!d.isDirectory || !lib.isDirectory) 0L
        else lib.walkTopDown().filter { it.isFile }.sumOf { it.length() }.also { lib.deleteRecursively() }
    } ?: 0L

    /** Bytes ya descargados (completos + parciales) de los archivos que aún hacen falta, sobre el total. */
    fun downloadedBytes(model: AiModel): Long = model.files.sumOf { f ->
        when {
            isComplete(model, f) -> f.sizeBytes.coerceAtLeast(file(model, f).length())
            else -> partFile(model, f).takeIf { it.isFile }?.length() ?: 0L
        }
    }

    fun hasPartial(model: AiModel): Boolean = model.files.any { partFile(model, it).isFile }

    /** Bytes que faltan por bajar. */
    fun remainingBytes(model: AiModel): Long = (totalBytes(model) - downloadedBytes(model)).coerceAtLeast(0)

    /** Bytes a bajar para este modelo (solo archivos de datos). */
    fun totalBytes(model: AiModel): Long = model.totalDownloadBytes

    /** Espacio ocupado en disco por el modelo. */
    fun diskUsage(model: AiModel): Long = dir(model).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Archivo descargado [fileName] del modelo si está completo (modelos ONNX). */
    fun readyFile(model: AiModel, fileName: String): File? =
        model.files.firstOrNull { it.fileName == fileName }?.takeIf { isComplete(model, it) }?.let { file(model, it) }

    fun delete(model: AiModel): Boolean {
        val d = dir(model)
        if (!d.exists()) return true
        return d.deleteRecursively()
    }
}
