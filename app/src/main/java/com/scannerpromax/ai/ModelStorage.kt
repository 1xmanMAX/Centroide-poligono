package com.scannerpromax.ai

import java.io.File

/**
 * Archivos de los modelos en disco: `<root>/<id>/<archivo>` (root = filesDir/models). Java puro: testeable en JVM.
 *
 * Un modelo está listo cuando todos sus archivos están completos (tamaño esperado).
 *
 * Si el modelo trae librerías nativas ([AiModel.nativeLibs], p. ej. ONNX Runtime de la maquetación), solo está listo
 * cuando también están las de la ABI de este proceso en `<id>/lib/<abi>/` (se escriben tras verificar el sha256).
 */
class ModelStorage(val root: File, val abi: String? = NativeAbi.current) {

    fun dir(model: AiModel): File = File(root, model.id)

    fun file(model: AiModel, f: ModelFile): File = File(dir(model), f.fileName)

    fun partFile(model: AiModel, f: ModelFile): File = File(dir(model), f.fileName + ".part")

    fun isComplete(model: AiModel, f: ModelFile): Boolean {
        val file = file(model, f)
        return file.isFile && (f.sizeBytes <= 0 || file.length() == f.sizeBytes)
    }

    fun isReady(model: AiModel): Boolean = model.files.all { f -> isComplete(model, f) } && nativeLibsReady(model)

    // ------------------------------------------------------------------ librerías nativas (ONNX Runtime)

    /** Librerías nativas de la ABI de este proceso (vacío si el modelo no trae; null si esta ABI no está soportada). */
    fun nativeLibs(model: AiModel): List<NativeLib>? = model.nativeLibs?.let { it.forAbi(abi) } ?: if (model.nativeLibs == null) emptyList() else null

    /** Carpeta de las librerías nativas de la ABI de este proceso: `<id>/lib/<abi>/`. */
    fun nativeLibDir(model: AiModel): File = File(File(dir(model), "lib"), abi ?: "desconocida")

    fun nativeLibFile(model: AiModel, lib: NativeLib): File = File(File(File(dir(model), "lib"), lib.abi), lib.fileName)

    fun isNativeLibComplete(model: AiModel, lib: NativeLib): Boolean = nativeLibFile(model, lib).let { it.isFile && it.length() == lib.sizeBytes }

    /**
     * La misma librería nativa ya bajada por OTRO modelo (p. ej. ONNX Runtime de la Maquetación para Indra):
     * `<root>/<otro>/lib/<abi>/<archivo>` con el tamaño esperado, o null. El llamador verifica el sha256 al copiarla.
     */
    fun sharedNativeLib(model: AiModel, lib: NativeLib): File? = root.listFiles()?.asSequence()
        ?.filter { it.isDirectory && it.name != model.id }
        ?.map { File(File(File(it, "lib"), lib.abi), lib.fileName) }
        ?.firstOrNull { it.isFile && it.length() == lib.sizeBytes }

    /** ¿Están todas las librerías nativas que necesita el modelo en esta ABI? (true si no necesita ninguna). */
    fun nativeLibsReady(model: AiModel): Boolean = nativeLibs(model)?.all { isNativeLibComplete(model, it) } ?: false

    /** Bytes comprimidos ya bajados de las librerías nativas (completas + `.zpart`). */
    private fun nativeDownloadedBytes(model: AiModel): Long = nativeLibs(model).orEmpty().sumOf { lib ->
        if (isNativeLibComplete(model, lib)) lib.compressedBytes
        else nativeLibFile(model, lib).let { File(it.parentFile, it.name + ".zpart") }.takeIf { it.isFile }?.length()?.coerceAtMost(lib.compressedBytes) ?: 0L
    }

    /** Bytes ya descargados (completos + parciales) de los archivos que aún hacen falta, sobre el total. */
    fun downloadedBytes(model: AiModel): Long = model.files.sumOf { f ->
        when {
            isComplete(model, f) -> f.sizeBytes.coerceAtLeast(file(model, f).length())
            else -> partFile(model, f).takeIf { it.isFile }?.length() ?: 0L
        }
    } + nativeDownloadedBytes(model)

    fun hasPartial(model: AiModel): Boolean = model.files.any { partFile(model, it).isFile } ||
        nativeLibs(model).orEmpty().any { lib -> nativeLibFile(model, lib).let { File(it.parentFile, it.name + ".zpart") }.isFile }

    /** Bytes que faltan por bajar. */
    fun remainingBytes(model: AiModel): Long = (totalBytes(model) - downloadedBytes(model)).coerceAtLeast(0)

    /** Bytes a bajar para este modelo en esta ABI (archivos + librerías nativas comprimidas). */
    fun totalBytes(model: AiModel): Long = model.downloadBytes(abi)

    /** Bytes que ocuparán las librerías nativas que faltan, ya descomprimidas (para comprobar el espacio libre). */
    fun nativeUnpackedRemaining(model: AiModel): Long =
        nativeLibs(model).orEmpty().filterNot { isNativeLibComplete(model, it) }.sumOf { it.sizeBytes }

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
