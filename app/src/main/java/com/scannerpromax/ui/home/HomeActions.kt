package com.scannerpromax.ui.home

import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.domain.PdfTextMode
import com.scannerpromax.pdf.PdfPageInput
import com.scannerpromax.pdf.binaryHintFor
import com.scannerpromax.ui.components.safeFileName
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/**
 * Genera un PDF temporal (en caché) del documento para compartirlo, usando las páginas procesadas
 * y el OCR guardado (capa de texto buscable) con las preferencias de exportación del usuario.
 */
internal suspend fun buildSharePdf(container: AppContainer, doc: Document, progress: (Float) -> Unit): File {
    if (doc.pages.isEmpty()) throw IOException("El documento no tiene páginas")
    val settings = container.settings.settings.first()
    val repo = container.documents
    val inputs = doc.pages.mapIndexed { i, page ->
        val image = repo.processedFile(doc.id, page)
        val ocr = if (settings.pdfTextMode.needsOcr) repo.ensureOcr(doc.id, page.id) else null
        progress(0.3f * (i + 1) / doc.pages.size)
        PdfPageInput(image, ocr, binaryHint = binaryHintFor(page.edits.filter))
    }
    if (settings.pdfTextMode == PdfTextMode.SOLO_TEXTO && inputs.all { it.ocr == null || it.ocr.displayText.isBlank() }) {
        throw IOException("No hay texto reconocido para un PDF de solo texto")
    }
    val dir = File(container.appContext.cacheDir, "share").apply { mkdirs() }
    val out = File(dir, safeFileName(doc.title) + ".pdf")
    val options = PdfOptions(
        pageSize = settings.pdfPageSize,
        quality = settings.exportQuality,
        searchable = settings.pdfTextMode.needsOcr,
        textMode = settings.pdfTextMode,
    )
    return container.pdfExporter.export(inputs, options, out) { p -> progress(0.3f + 0.7f * p) }
}
