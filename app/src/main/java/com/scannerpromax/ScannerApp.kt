package com.scannerpromax

import android.app.Application
import com.scannerpromax.di.AppContainer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class ScannerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        OpenCVLoader.initLocal()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
    }
}
