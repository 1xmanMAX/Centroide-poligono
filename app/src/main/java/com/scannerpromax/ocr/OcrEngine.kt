package com.scannerpromax.ocr

// ARCHIVO DE CONTRATO (el agente de datos/PDF/OCR implementa los TODO()).

import android.content.Context
import android.graphics.Bitmap
import com.scannerpromax.domain.OcrResult

class OcrEngine(private val context: Context) {
    /** OCR de alta calidad: pre-procesa (escala a ~ altura de letra óptima, contraste) y reconoce en el dispositivo. */
    suspend fun recognize(bitmap: Bitmap): OcrResult = TODO()
}
