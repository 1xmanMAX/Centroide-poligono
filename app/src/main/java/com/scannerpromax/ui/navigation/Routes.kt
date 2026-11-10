package com.scannerpromax.ui.navigation

import com.scannerpromax.domain.ScanMode

/** Rutas de navegación (contrato compartido por todas las pantallas). */
object Routes {
    const val HOME = "home"
    const val CAMERA = "camera?docId={docId}&mode={mode}&replace={replace}"
    const val REVIEW = "review/{docId}"
    const val EDITOR = "editor/{docId}/{pageId}"
    const val ID_CROP = "idcrop/{docId}/{pageId}"
    const val EXPORT = "export/{docId}?pages={pages}"
    const val COMPRESS = "compress"
    const val SETTINGS = "settings"
    const val AI_MODELS = "settings/ai-models"
    const val CLOUD_AI = "settings/cloud-ai"
    const val ALBUMS = "albums"
    const val ALBUM = "album/{albumId}"
    const val ALBUM_CAMERA = "album/{albumId}/camera/{pageId}/{slotId}"
    const val ALBUM_CROP = "album/{albumId}/crop/{pageId}/{slotId}?src={src}"

    /** [docId] null = documento nuevo. */
    /** [replacePageId] = volver a escanear esa página: una sola captura que ocupa su lugar. */
    fun camera(mode: ScanMode = ScanMode.DOCUMENT, docId: String? = null, replacePageId: String? = null) =
        "camera?docId=${docId ?: ""}&mode=${mode.name}&replace=${replacePageId ?: ""}"
    fun review(docId: String) = "review/$docId"
    fun editor(docId: String, pageId: String) = "editor/$docId/$pageId"
    fun idCrop(docId: String, pageId: String) = "idcrop/$docId/$pageId"
    /**
     * [pageIds] = solo esas páginas (modo selección del Documento); null = todas. El valor se codifica otra vez
     * porque Navigation decodifica los argumentos una vez antes de entregarlos.
     */
    fun export(docId: String, pageIds: Collection<String>? = null) =
        com.scannerpromax.export.ExportSelection.encode(pageIds)
            ?.let { "export/$docId?pages=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "export/$docId"

    fun album(albumId: String) = "album/$albumId"
    fun albumCamera(albumId: String, pageId: String, slotId: String) = "album/$albumId/camera/$pageId/$slotId"
    /** [src] = imagen nueva (relativa al álbum) que recortar; null = recortar otra vez el cromo pegado. */
    fun albumCrop(albumId: String, pageId: String, slotId: String, src: String? = null) =
        "album/$albumId/crop/$pageId/$slotId" + (src?.let { "?src=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
}
