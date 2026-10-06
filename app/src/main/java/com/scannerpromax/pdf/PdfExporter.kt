package com.scannerpromax.pdf

// ARCHIVO DE CONTRATO (el agente de datos/PDF/OCR implementa los TODO()).

import android.content.Context
import android.net.Uri
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.imaging.ProgressCallback
import java.io.File

/** Una página a exportar: imagen procesada en disco y (opcional) su OCR para la capa de texto invisible. */
data class PdfPageInput(val imageFile: File, val ocr: OcrResult?)

class PdfExporter(private val context: Context) {
    /** Escribe el PDF en [output] (archivo). Devuelve el archivo. */
    suspend fun export(pages: List<PdfPageInput>, options: PdfOptions, output: File, progress: ProgressCallback? = null): File = TODO()
}

data class CompressionResult(val output: File, val originalBytes: Long, val compressedBytes: Long, val pageCount: Int)

class PdfCompressor(private val context: Context) {
    /** Comprime un PDF arbitrario (de cualquier app) re-renderizando y re-codificando las páginas. */
    suspend fun compress(input: Uri, level: com.scannerpromax.domain.CompressionLevel, output: File, progress: ProgressCallback? = null): CompressionResult = TODO()

    /** Comprime un PDF a un tamaño objetivo aproximado (búsqueda binaria de calidad/dpi). */
    suspend fun compressToTarget(input: Uri, targetBytes: Long, output: File, progress: ProgressCallback? = null): CompressionResult = TODO()
}
