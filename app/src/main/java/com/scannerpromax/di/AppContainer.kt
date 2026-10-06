package com.scannerpromax.di

import android.content.Context
import com.scannerpromax.data.DocumentRepository
import com.scannerpromax.data.SettingsRepository
import com.scannerpromax.export.ImageExporter
import com.scannerpromax.imaging.DeviceProfiler
import com.scannerpromax.imaging.PageProcessor
import com.scannerpromax.ocr.OcrEngine
import com.scannerpromax.pdf.PdfCompressor
import com.scannerpromax.pdf.PdfExporter

/** Inyección de dependencias manual (sin Hilt para mantener el APK liviano y el arranque rápido). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val deviceTier = DeviceProfiler.profile(appContext)
    val settings = SettingsRepository(appContext)
    val pageProcessor = PageProcessor(deviceTier)
    val documents = DocumentRepository(appContext, pageProcessor, deviceTier)
    val ocr = OcrEngine(appContext).also { documents.attachOcrEngine(it) }
    val pdfExporter = PdfExporter(appContext)
    val pdfCompressor = PdfCompressor(appContext)
    val imageExporter = ImageExporter(appContext)
}
