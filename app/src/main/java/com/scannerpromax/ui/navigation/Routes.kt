package com.scannerpromax.ui.navigation

import com.scannerpromax.domain.ScanMode

/** Rutas de navegación (contrato compartido por todas las pantallas). */
object Routes {
    const val HOME = "home"
    const val CAMERA = "camera?docId={docId}&mode={mode}"
    const val REVIEW = "review/{docId}"
    const val EDITOR = "editor/{docId}/{pageId}"
    const val EXPORT = "export/{docId}"
    const val OCR = "ocr/{docId}/{pageId}"
    const val COMPRESS = "compress"
    const val SETTINGS = "settings"

    /** [docId] null = documento nuevo. */
    fun camera(mode: ScanMode = ScanMode.DOCUMENT, docId: String? = null) =
        "camera?docId=${docId ?: ""}&mode=${mode.name}"
    fun review(docId: String) = "review/$docId"
    fun editor(docId: String, pageId: String) = "editor/$docId/$pageId"
    fun export(docId: String) = "export/$docId"
    fun ocr(docId: String, pageId: String) = "ocr/$docId/$pageId"
}
