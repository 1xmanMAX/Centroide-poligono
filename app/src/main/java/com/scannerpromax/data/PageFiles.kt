package com.scannerpromax.data

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Nombres de los archivos derivados de una página y claves de caché (lógica pura, testeable en la JVM).
 *
 * ÚNICA FUENTE DE VERDAD: el procesado `processed/{id}_{hash}.{jpg|png}` (render completo de PageProcessor.render
 * con las ediciones de hash `{hash}`). La miniatura `thumb/{id}_{hash}.jpg` es SIEMPRE una reducción de ese mismo
 * render (mismo hash); el visor y la exportación leen el procesado tal cual.
 */
object PageFiles {
    const val PROCESSED_DIR = "processed"
    const val THUMB_DIR = "thumb"
    const val OCR_DIR = "ocr"

    fun isBinaryFilter(f: FilterType) = f == FilterType.BLACK_WHITE || f == FilterType.ECO_INK

    /** Hash estable (entre ejecuciones) de las ediciones: va en el nombre de archivo, así cada versión tiene el suyo. */
    fun editsHash(edits: PageEdits): String =
        Integer.toHexString(hashJson.encodeToString(PageEdits.serializer(), edits).hashCode())

    /** MISMA configuración que DocumentRepository.json (el hash de los archivos ya guardados no cambia). */
    private val hashJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun processedName(pageId: String, edits: PageEdits) =
        "$PROCESSED_DIR/${pageId}_${editsHash(edits)}.${if (isBinaryFilter(edits.filter)) "png" else "jpg"}"

    fun thumbName(pageId: String, edits: PageEdits) = "$THUMB_DIR/${pageId}_${editsHash(edits)}.jpg"

    fun ocrName(pageId: String, edits: PageEdits) = "$OCR_DIR/${pageId}_${editsHash(edits)}.json"

    /**
     * Maquetación reconocida por la IA ([com.scannerpromax.domain.DocLayout]) de la versión [edits] de la página:
     * `processed/{id}_{hash}.{jpg|png}.layout.json`, junto al procesado de esa versión (misma ruta que
     * [com.scannerpromax.pdf.DocLayoutFiles.fileFor], que es quien la lee). Si existe, el OCR guardado lo produjo la IA.
     */
    fun layoutName(pageId: String, edits: PageEdits) = processedName(pageId, edits) + LAYOUT_SUFFIX

    const val LAYOUT_SUFFIX = ".layout.json"

    /** Tipo de documento clasificado por la IA (no depende de las ediciones): `ocr/{id}.aiclass`. */
    fun aiClassName(pageId: String) = "$OCR_DIR/$pageId.aiclass"

    /**
     * ¿La miniatura [thumbName] corresponde al procesado de [edits]? Una miniatura de otra versión (otras ediciones)
     * no se muestra nunca: se regenera desde el procesado actual.
     */
    fun thumbMatches(thumbName: String?, pageId: String, edits: PageEdits): Boolean =
        thumbName != null && thumbName == thumbName(pageId, edits)

    /**
     * Clave de caché (Coil, memoria) de un archivo de imagen: ruta + fecha de modificación + tamaño. Si el archivo se
     * regenera en el mismo sitio (miniatura ampliada, procesado rehecho) la clave cambia y no se muestra la vieja.
     */
    fun cacheKey(path: String, lastModified: Long, length: Long): String = "$path#$lastModified-$length"
}

/** Archivo de imagen con su versión (para claves de caché que cambian cuando cambia el archivo). */
data class VersionedFile(val file: File, val lastModified: Long, val length: Long) {
    val cacheKey: String get() = PageFiles.cacheKey(file.path, lastModified, length)

    companion object {
        /** Lee la versión del disco (llamar fuera del hilo principal). null si no existe. */
        fun of(file: File?): VersionedFile? {
            file ?: return null
            val len = file.length()
            if (len <= 0L || !file.exists()) return null
            return VersionedFile(file, file.lastModified(), len)
        }
    }
}
