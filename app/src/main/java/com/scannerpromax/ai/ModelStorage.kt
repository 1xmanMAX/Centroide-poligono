package com.scannerpromax.ai

import java.io.File
import java.io.IOException

/**
 * Archivos de los modelos en disco: `<root>/<id>/<archivo>` (root = filesDir/models). Java puro: testeable en JVM.
 *
 * Un modelo está listo cuando todos sus archivos están completos (tamaño esperado). Si el modelo admite
 * cuantización en el dispositivo y existe la versión cuantizada, esa sustituye al LLM original (que se borra tras
 * cuantizar para ahorrar espacio).
 */
class ModelStorage(val root: File) {

    fun dir(model: AiModel): File = File(root, model.id)

    fun file(model: AiModel, f: ModelFile): File = File(dir(model), f.fileName)

    fun partFile(model: AiModel, f: ModelFile): File = File(dir(model), f.fileName + ".part")

    fun quantizedFile(model: AiModel): File? = model.onDeviceQuantization?.let { File(dir(model), it.outputFileName) }

    /** Archivo cuantizado a medio escribir (si el proceso muere al cuantizar). */
    fun quantizedTempFile(model: AiModel): File? = model.onDeviceQuantization?.let { File(dir(model), it.outputFileName + ".tmp") }

    fun isComplete(model: AiModel, f: ModelFile): Boolean {
        val file = file(model, f)
        return file.isFile && (f.sizeBytes <= 0 || file.length() == f.sizeBytes)
    }

    fun hasQuantized(model: AiModel): Boolean = quantizedFile(model)?.let { it.isFile && it.length() > 0 } ?: false

    fun isReady(model: AiModel): Boolean = model.files.all { f ->
        isComplete(model, f) || (f.kind == FileKind.LLM && f.fileName == model.onDeviceQuantization?.sourceFileName && hasQuantized(model))
    }

    /** Bytes ya descargados (completos + parciales) de los archivos que aún hacen falta, sobre el total. */
    fun downloadedBytes(model: AiModel): Long = model.files.sumOf { f ->
        when {
            isComplete(model, f) -> f.sizeBytes.coerceAtLeast(file(model, f).length())
            f.kind == FileKind.LLM && hasQuantized(model) && f.fileName == model.onDeviceQuantization?.sourceFileName -> f.sizeBytes
            else -> partFile(model, f).takeIf { it.isFile }?.length() ?: 0L
        }
    }

    fun hasPartial(model: AiModel): Boolean = model.files.any { partFile(model, it).isFile }

    /** Bytes que faltan por bajar. */
    fun remainingBytes(model: AiModel): Long = (model.totalDownloadBytes - downloadedBytes(model)).coerceAtLeast(0)

    /** Espacio ocupado en disco por el modelo. */
    fun diskUsage(model: AiModel): Long = dir(model).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /** Rutas para cargar el modelo (prefiere el LLM cuantizado en el dispositivo si existe). */
    fun resolvePaths(model: AiModel): Pair<File, File?>? {
        if (!isReady(model) || !model.isLlm) return null
        val llm = quantizedFile(model)?.takeIf { hasQuantized(model) } ?: file(model, model.llmFile)
        val mm = model.mmprojFile?.let { file(model, it) }
        return llm to mm
    }

    /** Archivo descargado [fileName] del modelo si está completo (modelos ONNX). */
    fun readyFile(model: AiModel, fileName: String): File? =
        model.files.firstOrNull { it.fileName == fileName }?.takeIf { isComplete(model, it) }?.let { file(model, it) }

    fun delete(model: AiModel): Boolean {
        val d = dir(model)
        if (!d.exists()) return true
        return d.deleteRecursively()
    }

    /** Tras cuantizar: renombra el temporal al definitivo y borra el original. */
    fun commitQuantized(model: AiModel) {
        val tmp = quantizedTempFile(model) ?: return
        val out = quantizedFile(model) ?: return
        if (!tmp.isFile || tmp.length() == 0L) throw IOException("cuantización sin salida")
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw IOException("no se pudo guardar el modelo cuantizado")
        model.onDeviceQuantization?.let { q -> File(dir(model), q.sourceFileName).delete() }
    }

    /** Limpia temporales de cuantización abandonados. */
    fun cleanupTemp(model: AiModel) {
        quantizedTempFile(model)?.delete()
    }
}
