package com.scannerpromax.export

// ARCHIVO DE CONTRATO (el agente de datos/PDF/OCR implementa los TODO()).

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.ImageFormat
import java.io.File

class ImageExporter(private val context: Context) {
    /** Guarda imágenes en la galería (MediaStore, Pictures/EscanerProMax). Devuelve las Uris creadas. */
    suspend fun saveToGallery(images: List<File>, format: ImageFormat, quality: ExportQuality, baseName: String): List<Uri> = TODO()

    /** Guarda un PDF en Descargas (MediaStore Downloads/EscanerProMax en API 29+). */
    suspend fun savePdfToDownloads(pdf: File, displayName: String): Uri = TODO()

    /** Intent para compartir archivos (FileProvider, authority "${applicationId}.fileprovider"). */
    fun shareIntent(files: List<File>, mime: String): Intent = TODO()
}
