package com.scannerpromax.pdf

import android.content.Context
import android.net.Uri
import com.scannerpromax.domain.CompressionLevel
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.ProgressCallback
import dev.pdfsqueeze.SqueezeEnvelope
import dev.pdfsqueeze.SqueezeReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** Informe del motor pdfsqueeze para la pantalla de resultado. */
data class EngineReport(
    /** SSIM medio / mínimo de las imágenes recodificadas con pérdida (null si no hubo ninguna). */
    val ssimMean: Double?,
    val ssimMin: Double?,
    val imagesOptimized: Int,
    val imagesTotal: Int,
    /** El motor verificó páginas, texto e imágenes del resultado. */
    val verified: Boolean,
    val elapsedMs: Long,
    val threads: Int,
    /** Pasadas hechas (más de una en el modo de tamaño objetivo). */
    val passes: Int = 1,
)

/**
 * Resultado de compresión. [keptOriginal] = true si comprimir no reducía el tamaño y se devolvió
 * una copia del original (entonces compressedBytes == originalBytes).
 */
data class CompressionResult(
    val output: File,
    val originalBytes: Long,
    val compressedBytes: Long,
    val pageCount: Int,
    val keptOriginal: Boolean = false,
    /** true si las páginas se rasterizaron: el texto deja de ser seleccionable/buscable (avisar en la UI). */
    val rasterized: Boolean = false,
    /** Informe de pdfsqueeze; null si se usó el compresor de respaldo. */
    val engine: EngineReport? = null,
    /** Por qué se usó el compresor de respaldo (null = no hizo falta). */
    val fallbackReason: String? = null,
) {
    /** Porcentaje ahorrado (0..100). */
    val savedPercent: Int
        get() = if (originalBytes <= 0) 0 else ((1.0 - compressedBytes.toDouble() / originalBytes) * 100).roundToInt().coerceIn(0, 100)
}

/** El PDF está protegido con contraseña y no se puede abrir. */
class PdfPasswordException(message: String = "El PDF está protegido con contraseña. Quita la contraseña e inténtalo de nuevo.") :
    IOException(message)

/** El archivo no es un PDF válido o está dañado. */
class PdfInvalidException(message: String = "No se pudo abrir el PDF: el archivo está dañado o no es un PDF.", cause: Throwable? = null) :
    IOException(message, cause)

/** Compresor de respaldo (pdfbox / PdfRenderer) sobre copias locales. */
interface FallbackPdfCompressor {
    suspend fun compressFile(source: File, level: CompressionLevel, output: File, progress: ProgressCallback?): CompressionResult
    suspend fun compressFileToTarget(source: File, targetBytes: Long, output: File, progress: ProgressCallback?): CompressionResult
}

/**
 * Compresor de PDF. Motor principal: pdfsqueeze (Rust; limpieza sin pérdida de la estructura, carrera de códecs
 * por imagen bajo un SSIM mínimo, capas MRC para escaneos, JBIG2/G4 exacto para B/N y verificación del
 * resultado). Texto, vectores, enlaces y capa OCR se conservan siempre.
 *
 * Respaldo ([LegacyPdfCompressor]) si: la librería nativa no está, el PDF está cifrado, el motor falla o no hay
 * memoria suficiente; y en el modo de tamaño objetivo, si ni al mínimo se alcanza conservando el texto.
 * El resultado nunca es mayor que el original.
 */
class PdfCompressor internal constructor(
    private val engine: SqueezeEngine,
    private val fallback: FallbackPdfCompressor,
    private val workDir: () -> File,
    private val copier: (Uri) -> File,
) {
    constructor(context: Context, tier: DeviceTier, legacy: LegacyPdfCompressor = LegacyPdfCompressor(context)) : this(
        NativeSqueezeEngine(context, tier),
        legacy,
        { File(context.cacheDir, "compress").apply { mkdirs() } },
        legacy::copyToCache,
    )

    suspend fun compress(input: Uri, level: CompressionLevel, output: File, progress: ProgressCallback? = null): CompressionResult =
        withSource(input) { compressFile(it, level, output, progress) }

    suspend fun compressToTarget(input: Uri, targetBytes: Long, output: File, progress: ProgressCallback? = null): CompressionResult =
        withSource(input) { compressFileToTarget(it, targetBytes, output, progress) }

    private suspend fun <T> withSource(input: Uri, block: suspend (File) -> T): T {
        val source = withContext(Dispatchers.IO) { copier(input) }
        try {
            return block(source)
        } finally {
            source.delete()
        }
    }

    /** Preparación común: ¿se puede usar el motor? Devuelve los hilos o el motivo del respaldo. */
    private sealed interface Prep {
        data class Go(val threads: Int, val probe: SqueezeProbe) : Prep
        data class Fallback(val reason: String) : Prep
    }

    private suspend fun prepare(source: File): Prep {
        if (!engine.available) return Prep.Fallback(REASON_NO_LIBRARY)
        val a = try {
            engine.analyze(source)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return Prep.Fallback(REASON_ENGINE_FAILED)
        }
        if (a.status == SqueezeEnvelope.STATUS_ENCRYPTED || a.analysis?.encrypted == true) return Prep.Fallback(REASON_ENCRYPTED)
        if (!a.ok) return Prep.Fallback(REASON_ENGINE_FAILED)
        val probe = SqueezeProbe.of(a.analysis)
        val threads = SqueezePlan.fitThreads(SqueezePlan.threadsFor(engine.tier), probe.maxImagePixels, source.length(), engine.availableMemory())
        if (threads <= 0) return Prep.Fallback(REASON_MEMORY)
        return Prep.Go(threads, probe)
    }

    /** Comprime una copia local [source] (no se borra) con el nivel [level]. */
    suspend fun compressFile(source: File, level: CompressionLevel, output: File, progress: ProgressCallback? = null): CompressionResult =
        withContext(Dispatchers.IO) {
            val prep = prepare(source)
            if (prep is Prep.Fallback) return@withContext legacy(source, level, output, progress, prep.reason)
            prep as Prep.Go
            progress?.invoke(0.03f)
            val tmp = tempFile("q")
            try {
                val env = runEngine(source, tmp, SqueezePlan.forLevel(level), prep.threads) { p -> progress?.invoke(0.03f + 0.97f * p) }
                if (!env.ok) return@withContext legacy(source, level, output, progress, reasonFor(env))
                val result = finish(source, tmp.takeUnless { env.returnedOriginal }, output, env, prep, passes = 1)
                progress?.invoke(1f)
                result
            } finally {
                tmp.delete()
            }
        }

    /**
     * Tamaño objetivo: búsqueda binaria (≤ 5 pasadas) sobre SSIM + dpi empezando por la estimación del análisis.
     * Si ni al mínimo cabe conservando el texto, el respaldo rasteriza (salvo que su resultado sea mayor).
     */
    suspend fun compressFileToTarget(source: File, targetBytes: Long, output: File, progress: ProgressCallback? = null): CompressionResult =
        withContext(Dispatchers.IO) {
            val originalBytes = source.length()
            val prep = prepare(source)
            if (prep is Prep.Fallback) return@withContext legacyTarget(source, targetBytes, output, progress, prep.reason)
            prep as Prep.Go
            if (originalBytes <= targetBytes) {
                copyReplacing(source, output)
                progress?.invoke(1f)
                return@withContext CompressionResult(output, originalBytes, originalBytes, prep.probe.pages, keptOriginal = true)
            }
            val search = SqueezePlan.TargetSearch(targetBytes, SqueezePlan.initialQuality(originalBytes, prep.probe.imageBytes, targetBytes))
            var best: Pair<File, SqueezeEnvelope>? = null // el que cumple con más calidad
            var smallest: Pair<File, SqueezeEnvelope>? = null
            var smallestSize = Long.MAX_VALUE
            val temps = ArrayList<File>()
            try {
                while (true) {
                    val s = search.next ?: break
                    currentCoroutineContext().ensureActive()
                    val base = search.passes.toFloat() / SqueezePlan.MAX_PASSES
                    val out = tempFile("t${search.passes}").also { temps += it }
                    val env = runEngine(source, out, SqueezePlan.forQuality(s), prep.threads) { p ->
                        progress?.invoke(0.03f + 0.9f * (base + p / SqueezePlan.MAX_PASSES))
                    }
                    if (!env.ok) {
                        if (best == null && smallest == null) return@withContext legacyTarget(source, targetBytes, output, progress, reasonFor(env))
                        break
                    }
                    val size = if (env.returnedOriginal || !out.isFile) originalBytes else out.length()
                    search.record(s, size)
                    if (size <= targetBytes && search.bestFit == s) best = out to env
                    if (size < smallestSize && !env.returnedOriginal) { smallest = out to env; smallestSize = size }
                    if (env.returnedOriginal) break // sin margen para mejorar: no vale la pena seguir
                }
                best?.let { (file, env) ->
                    val r = finish(source, file, output, env, prep, passes = search.passes)
                    progress?.invoke(1f)
                    return@withContext r
                }
                // No se alcanza conservando el texto: el respaldo puede rasterizar.
                val legacyOut = tempFile("l").also { temps += it }
                val legacyResult = try {
                    fallback.compressFileToTarget(source, targetBytes, legacyOut) { p -> progress?.invoke(0.93f + 0.07f * p) }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    null
                }
                val small = smallest
                val r = if (legacyResult != null && (small == null || legacyResult.compressedBytes < smallestSize)) {
                    copyReplacing(legacyOut, output)
                    legacyResult.copy(output = output, fallbackReason = REASON_TARGET)
                } else if (small != null) {
                    finish(source, small.first, output, small.second, prep, passes = search.passes)
                } else {
                    copyReplacing(source, output)
                    CompressionResult(output, originalBytes, originalBytes, prep.probe.pages, keptOriginal = true)
                }
                progress?.invoke(1f)
                r
            } finally {
                temps.forEach { it.delete() }
            }
        }

    private suspend fun runEngine(source: File, out: File, params: SqueezeParams, threads: Int, progress: (Float) -> Unit): SqueezeEnvelope =
        try {
            engine.compress(source, out, params, threads, progress)
        } catch (c: CancellationException) {
            out.delete()
            throw c
        } catch (oom: OutOfMemoryError) {
            out.delete()
            SqueezeEnvelope(status = SqueezeEnvelope.STATUS_INTERNAL, message = "memoria")
        } catch (t: Throwable) {
            out.delete()
            SqueezeEnvelope(status = SqueezeEnvelope.STATUS_INTERNAL, message = t.message.orEmpty())
        }

    private fun reasonFor(env: SqueezeEnvelope): String = when (env.status) {
        SqueezeEnvelope.STATUS_ENCRYPTED -> REASON_ENCRYPTED
        SqueezeEnvelope.STATUS_CANCELLED -> throw CancellationException("cancelado")
        else -> REASON_ENGINE_FAILED
    }

    private suspend fun legacy(source: File, level: CompressionLevel, output: File, progress: ProgressCallback?, reason: String): CompressionResult =
        fallback.compressFile(source, level, output, progress).copy(fallbackReason = reason)

    private suspend fun legacyTarget(source: File, target: Long, output: File, progress: ProgressCallback?, reason: String): CompressionResult =
        fallback.compressFileToTarget(source, target, output, progress).copy(fallbackReason = reason)

    /** [compressed] null o no más pequeño → copia del original ([CompressionResult.keptOriginal]). */
    private fun finish(source: File, compressed: File?, output: File, env: SqueezeEnvelope, prep: Prep.Go, passes: Int): CompressionResult {
        val originalBytes = source.length()
        val report = env.report
        val pages = report?.pages?.takeIf { it > 0 } ?: prep.probe.pages
        val engineReport = summarize(report, env.threads.takeIf { it > 0 } ?: prep.threads, passes)
        val size = compressed?.takeIf { it.isFile }?.length() ?: 0L
        return if (size in 1 until originalBytes) {
            copyReplacing(compressed!!, output)
            CompressionResult(output, originalBytes, size, pages, engine = engineReport)
        } else {
            copyReplacing(source, output)
            CompressionResult(output, originalBytes, originalBytes, pages, keptOriginal = true, engine = engineReport)
        }
    }

    private fun tempFile(tag: String): File = File(workDir(), "${tag}_${System.nanoTime()}.pdf")

    private fun copyReplacing(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        const val REASON_NO_LIBRARY = "El motor pdfsqueeze no está disponible en esta versión; se usó el compresor clásico."
        const val REASON_ENCRYPTED = "El PDF está cifrado; se usó el compresor clásico."
        const val REASON_ENGINE_FAILED = "El motor pdfsqueeze no pudo procesar este PDF; se usó el compresor clásico."
        const val REASON_MEMORY = "No hay memoria libre suficiente para el motor pdfsqueeze; se usó el compresor clásico."
        const val REASON_TARGET = "Conservando el texto no se alcanzaba el tamaño pedido; las páginas se convirtieron en imágenes."

        /** Resumen del informe de pdfsqueeze para la UI. */
        fun summarize(report: SqueezeReport?, threads: Int, passes: Int): EngineReport {
            val images = report?.images.orEmpty()
            val ssims = images.mapNotNull { img -> img.ssim?.takeIf { img.afterBytes < img.beforeBytes } }
            val optimized = images.count { it.afterBytes < it.beforeBytes && !it.action.startsWith("keep") }
            return EngineReport(
                ssimMean = ssims.takeIf { it.isNotEmpty() }?.average(),
                ssimMin = ssims.minOrNull(),
                imagesOptimized = optimized,
                imagesTotal = images.size,
                verified = report?.verified == true,
                elapsedMs = report?.elapsedMs ?: 0L,
                threads = threads,
                passes = passes,
            )
        }
    }
}
