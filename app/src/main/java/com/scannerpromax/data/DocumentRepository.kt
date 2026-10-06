package com.scannerpromax.data

// ARCHIVO DE CONTRATO (el agente de datos/PDF/OCR implementa los TODO()).

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.PageProcessor
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Almacenamiento local: filesDir/documents/{docId}/doc.json + original/, processed/, thumb/, ocr/.
 * Todas las funciones suspend corren en Dispatchers.IO / Default internamente.
 */
class DocumentRepository(
    private val context: Context,
    private val processor: PageProcessor,
    private val tier: DeviceTier,
) {
    val documents: StateFlow<List<Document>> get() = TODO()

    fun observe(docId: String): kotlinx.coroutines.flow.Flow<Document?> = TODO()
    suspend fun get(docId: String): Document? = TODO()
    suspend fun create(mode: ScanMode, title: String? = null): Document = TODO()
    suspend fun rename(docId: String, title: String) { TODO() }
    suspend fun delete(docId: String) { TODO() }
    suspend fun duplicate(docId: String): Document = TODO()

    /** Añade una página desde un JPEG capturado (se mueve/copia a original/). Detecta bordes automáticamente y aplica filtro por defecto del modo. */
    suspend fun addPageFromFile(docId: String, file: File, autoDetect: Boolean = true): Page = TODO()
    /** Añade páginas importadas desde la galería. */
    suspend fun addPagesFromUris(docId: String, uris: List<Uri>): List<Page> = TODO()
    /** Añade una página ya generada (ej. libro dividido o DNI compuesto) cuyo original es el bitmap dado. */
    suspend fun addPageFromBitmap(docId: String, bitmap: Bitmap, edits: PageEdits = PageEdits()): Page = TODO()

    suspend fun updateEdits(docId: String, pageId: String, edits: PageEdits): Page = TODO()
    suspend fun deletePage(docId: String, pageId: String) { TODO() }
    suspend fun reorderPages(docId: String, orderedPageIds: List<String>) { TODO() }
    suspend fun saveOcr(docId: String, pageId: String, result: OcrResult) { TODO() }
    suspend fun loadOcr(docId: String, pageId: String): OcrResult? = TODO()

    fun docDir(docId: String): File = TODO()
    fun originalFile(docId: String, page: Page): File = TODO()
    /** Archivo procesado (lo genera si falta o está desactualizado). */
    suspend fun processedFile(docId: String, page: Page): File = TODO()
    fun thumbFile(docId: String, page: Page): File? = TODO()
    /** Archivo temporal para capturas de cámara. */
    fun newCaptureFile(): File = TODO()
}

/** Preferencias (DataStore). */
class SettingsRepository(private val context: Context) {
    val settings: kotlinx.coroutines.flow.Flow<AppSettings> get() = TODO()
    suspend fun update(transform: (AppSettings) -> AppSettings) { TODO() }
}

data class AppSettings(
    val defaultFilter: com.scannerpromax.domain.FilterType = com.scannerpromax.domain.FilterType.MAGIC,
    val autoCapture: Boolean = true,
    val autoRemoveLines: Boolean = false,
    val pdfPageSize: com.scannerpromax.domain.PageSize = com.scannerpromax.domain.PageSize.AUTO,
    val exportQuality: com.scannerpromax.domain.ExportQuality = com.scannerpromax.domain.ExportQuality.HIGH,
    val searchablePdf: Boolean = true,
    val darkTheme: Boolean? = null, // null = seguir el sistema
    val dynamicColor: Boolean = false,
)
