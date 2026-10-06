package com.scannerpromax.ui.home

import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.pdf.PdfPageInput
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
        val ocr = if (settings.searchablePdf) repo.loadOcr(doc.id, page.id) else null
        progress(0.3f * (i + 1) / doc.pages.size)
        PdfPageInput(image, ocr)
    }
    val dir = File(container.appContext.cacheDir, "share").apply { mkdirs() }
    val out = File(dir, safeFileName(doc.title) + ".pdf")
    val options = PdfOptions(
        pageSize = settings.pdfPageSize,
        quality = settings.exportQuality,
        searchable = settings.searchablePdf,
    )
    return container.pdfExporter.export(inputs, options, out) { p -> progress(0.3f + 0.7f * p) }
}
