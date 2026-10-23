package com.scannerpromax.integration

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.ImageFormat
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.domain.PdfTextMode
import com.scannerpromax.export.ImageCodec
import com.scannerpromax.pdf.PdfPageInput
import com.scannerpromax.pdf.binaryHintFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Lo generado para la otra app. */
class Delivered(val uris: List<Uri>, val text: String?, val thumbnail: Bitmap?, val pageCount: Int)

/** Carpeta de archivos entregados y su limpieza (JVM puro). */
object AttachFiles {
    const val DIR = "attach"

    /**
     * Cuánto se conservan los archivos entregados: la otra app puede leer el Uri un rato después (subidas en segundo
     * plano, borradores de correo). Pasado ese tiempo se borran al abrir el flujo otra vez.
     */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun root(cacheDir: File) = File(cacheDir, DIR)

    /** Borra las entregas de más de [maxAgeMs]. Devuelve cuántas carpetas/archivos se borraron. */
    fun cleanup(cacheDir: File, now: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_AGE_MS): Int {
        val entries = root(cacheDir).listFiles() ?: return 0
        var n = 0
        for (f in entries) {
            val newest = if (f.isDirectory) (f.walkTopDown().maxOfOrNull { it.lastModified() } ?: f.lastModified()) else f.lastModified()
            if (now - newest > maxAgeMs && f.deleteRecursively()) n++
        }
        return n
    }

    /** Carpeta nueva y única para una entrega. */
    fun newDeliveryDir(cacheDir: File, now: Long = System.currentTimeMillis()): File {
        val root = root(cacheDir)
        var dir = File(root, now.toString())
        var i = 1
        while (dir.exists()) dir = File(root, "${now}_${i++}")
        dir.mkdirs()
        return dir
    }

    /** Nombre base del archivo: título pedido, o el del documento, o "Escaneo". */
    fun baseName(requested: String?, docTitle: String?): String =
        (ScanRequestParser.sanitizeTitle(requested) ?: ScanRequestParser.sanitizeTitle(docTitle) ?: "Escaneo")
}

/**
 * Genera los archivos para la otra app con los exportadores existentes (PdfExporter / ImageExporter, calidad y
 * tamaño de página de Ajustes), en la caché propia (cache/attach/<marca>) y expuestos con FileProvider.
 */
class AttachExporter(private val context: Context, private val container: AppContainer) {
    private val authority = "${context.packageName}.fileprovider"

    suspend fun deliver(
        request: ScanRequest,
        plan: DeliveryPlan,
        docId: String,
        settings: AppSettings,
        progress: (Float, String) -> Unit,
    ): Delivered {
        val repo = container.documents
        val doc = repo.get(docId) ?: throw IOException("El escaneo ya no existe")
        val pages = plan.pages.mapNotNull { doc.pages.getOrNull(it) }
        if (pages.isEmpty()) throw IOException("No hay páginas")
        withContext(Dispatchers.IO) { AttachFiles.cleanup(context.cacheDir) }

        val files = pages.mapIndexed { i, p ->
            progress(0.3f * i / pages.size, "Preparando página ${i + 1} de ${pages.size}…")
            repo.processedFile(docId, p)
        }
        val textMode = pdfTextMode(request, settings)
        val needOcr = plan.includeText || (plan.format == OutputFormat.PDF && textMode.needsOcr && !plan.writeToOutput && !plan.thumbnailOnly)
        val ocrs: List<OcrResult?> = if (needOcr) {
            repo.ensureOcrAll(docId, pages.map { it.id }) { done, total, recognizing ->
                progress(0.3f + 0.35f * done / total.coerceAtLeast(1), if (recognizing) "Reconociendo texto ${done + 1} de $total…" else "Texto listo")
            }
        } else {
            List(pages.size) { null }
        }
        val text = if (plan.includeText) {
            ocrs.mapNotNull { it?.displayText?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n\n").ifEmpty { null }
        } else null

        progress(0.7f, if (plan.format == OutputFormat.PDF) "Generando PDF…" else "Preparando imagen…")
        val dir = withContext(Dispatchers.IO) { AttachFiles.newDeliveryDir(context.cacheDir) }
        val base = AttachFiles.baseName(request.title, doc.title)
        val quality = settings.exportQuality

        // IMAGE_CAPTURE: JPEG en EXTRA_OUTPUT o miniatura.
        if (plan.writeToOutput || plan.thumbnailOnly) {
            val jpg = container.imageExporter.exportToDir(files, ImageFormat.JPEG, quality, base, dir).first()
            return if (plan.writeToOutput) {
                writeToOutput(jpg, Uri.parse(request.outputUri))
                Delivered(emptyList(), null, null, 1)
            } else {
                Delivered(emptyList(), null, thumbnail(jpg), 1)
            }
        }

        val outFiles: List<File> = when (plan.format) {
            OutputFormat.PDF -> {
                val out = File(dir, "$base.pdf")
                val options = PdfOptions(
                    pageSize = settings.pdfPageSize,
                    quality = quality,
                    searchable = textMode != PdfTextMode.SOLO_IMAGEN,
                    textMode = textMode,
                )
                container.pdfExporter.export(
                    pages.indices.map { PdfPageInput(files[it], ocrs[it], binaryHint = binaryHintFor(pages[it].edits.filter)) },
                    options,
                    out,
                ) { p -> progress(0.7f + 0.3f * p.coerceIn(0f, 1f), "Generando PDF…") }
                listOf(out)
            }
            OutputFormat.JPEG -> container.imageExporter.exportToDir(files, ImageFormat.JPEG, quality, base, dir)
            OutputFormat.PNG -> container.imageExporter.exportToDir(files, ImageFormat.PNG, quality, base, dir)
        }
        val uris = outFiles.map { FileProvider.getUriForFile(context, authority, it) }
        return Delivered(uris, text, null, pages.size)
    }

    /**
     * Modo de texto del PDF: con OCR pedido, buscable; si no, el de Ajustes. "Solo texto" y "Reconstruido" no tienen
     * sentido para un adjunto (se espera ver el documento tal cual): se usan como "Buscable".
     */
    private fun pdfTextMode(request: ScanRequest, settings: AppSettings): PdfTextMode = when {
        request.ocr -> if (settings.pdfTextMode == PdfTextMode.BUSCABLE_CON_TEXTO) PdfTextMode.BUSCABLE_CON_TEXTO else PdfTextMode.BUSCABLE
        settings.pdfTextMode == PdfTextMode.SOLO_TEXTO || settings.pdfTextMode == PdfTextMode.RECONSTRUIDO -> PdfTextMode.BUSCABLE
        else -> settings.pdfTextMode
    }

    /** Copia el JPEG al Uri de la otra app (permiso de escritura concedido por ella). */
    private suspend fun writeToOutput(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw SecurityException("Ubicación de salida no permitida")
        val resolver = context.contentResolver
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw IOException("No se pudo abrir la ubicación de la otra app")
        out.use { o -> file.inputStream().use { it.copyTo(o, 64 * 1024) } }
    }

    private suspend fun thumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageCodec.decodeScaled(file, ScanResultIntent.THUMB_MAX_SIDE * 2)
            val (w, h) = ScanResultIntent.thumbSize(bmp.width, bmp.height)
            if (w == bmp.width && h == bmp.height) bmp
            else Bitmap.createScaledBitmap(bmp, w, h, true).also { if (it !== bmp) bmp.recycle() }
        }.getOrNull()
    }
}
