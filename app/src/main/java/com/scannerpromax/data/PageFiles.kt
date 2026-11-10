package com.scannerpromax.data

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.imaging.PageRotation
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

    /**
     * ¿[a] y [b] dan el procesado con la MISMA geometría (recorte, giro, enderezado)? Entonces las coordenadas de la
     * maquetación de una valen para la otra aunque cambien el filtro, los ajustes, los borrados o la cuadrícula.
     */
    fun sameGeometry(a: PageEdits, b: PageEdits): Boolean =
        a.quad == b.quad && a.rotation == b.rotation && PageRotation.geoRotation(a) == PageRotation.geoRotation(b) &&
            a.autoDeskew == b.autoDeskew && a.autoDewarp == b.autoDewarp && a.manualQuad == b.manualQuad && a.idCard == b.idCard

    /** Qué hace DocumentRepository.updateEdits con unas ediciones nuevas. */
    enum class EditPlan {
        /** El resultado ya está en disco (render o giro hecho en segundo plano por el editor). */
        REUSE,
        /** Solo cambia el giro: se giran el procesado, la miniatura y el OCR actuales, sin reprocesar NADA. */
        ROTATE,
        /** Tubería completa desde el original. */
        RENDER,
    }

    /**
     * [draftReady] = procesado y miniatura de las ediciones nuevas ya existen; [rotationOnly] = las nuevas solo cambian el
     * giro respecto de las actuales ([PageRotation.rotationOnly]) y el procesado actual existe.
     */
    fun editPlan(draftReady: Boolean, rotationOnly: Boolean): EditPlan = when {
        draftReady -> EditPlan.REUSE
        rotationOnly -> EditPlan.ROTATE
        else -> EditPlan.RENDER
    }

    /** Hash estable (entre ejecuciones) de las ediciones: va en el nombre de archivo, así cada versión tiene el suyo. */
    fun editsHash(edits: PageEdits): String =
        Integer.toHexString(hashJson.encodeToString(PageEdits.serializer(), edits).hashCode())

    /** MISMA configuración que DocumentRepository.json (el hash de los archivos ya guardados no cambia). */
    private val hashJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    const val JEV_DIR = "jev"

    /**
     * Caché de "Mejorar con Jev" de la página: depende SOLO de la geometría, no del filtro, de los ajustes ni del giro
     * final sin pérdidas (Jev ve la página en el marco de la geometría, [PageRotation.geoRotation]):
     * `jev/<página>_<hash de la geometría>.json`.
     */
    fun jevCacheName(pageId: String, edits: PageEdits) =
        "$JEV_DIR/${pageId}_${editsHash(PageEdits(quad = edits.quad, rotation = PageRotation.geoRotation(edits), autoDeskew = edits.autoDeskew, autoDewarp = edits.autoDewarp, manualQuad = edits.manualQuad, idCard = edits.idCard))}.json"

    /**
     * Foto ORIGINAL de una cara del DNI compuesto ([PageEdits.idCard]): `original/<página>_idfront.jpg` / `_idback.jpg`.
     * Empieza por el id de la página: se borra y se copia con ella (borrado y fusión de documentos).
     */
    fun idSourceName(pageId: String, back: Boolean) = "original/${pageId}_${if (back) "idback" else "idfront"}.jpg"

    /**
     * Caché de UNA cara del DNI ya renderizada (recorte + giro + espejo + mejora): `original/<página>_idr_<clave>.jpg`
     * ([idSideKey]). Recomponer la hoja tras editar una cara reutiliza la de la otra sin volver a procesarla. Empieza por
     * el id de la página: se borra con ella; al copiar la página a otro documento no hace falta (se regenera).
     */
    fun idRenderName(pageId: String, key: String) = "original/${pageId}_idr_$key.jpg"

    /** Prefijo de las cachés de caras de la página (para borrar las que ya no se usan). */
    fun idRenderPrefix(pageId: String) = "${pageId}_idr_"

    /**
     * Clave de la cara renderizada: depende SOLO de la foto de origen ([sourceFingerprint], su contenido) y de las
     * ediciones de ESA cara (recorte, giro, espejo y su mejora efectiva con la común [common]); no de la otra cara ni de
     * la marca «manual». Así cambiar el anverso no invalida el reverso, e intercambiarlas reutiliza las dos.
     */
    fun idSideKey(side: com.scannerpromax.domain.IdCardSide, common: FilterType?, sourceFingerprint: String): String {
        val norm = com.scannerpromax.domain.IdCardSide(
            quad = side.quad,
            rotation = side.turn.takeIf { it != 0 },
            mirror = side.mirror?.takeIf { it },
            filter = side.effectiveFilter(common) ?: FilterType.ORIGINAL,
        )
        val j = hashJson.encodeToString(com.scannerpromax.domain.IdCardSide.serializer(), norm) + "|" + sourceFingerprint
        val crc = java.util.zip.CRC32().apply { update(j.toByteArray()) }.value
        return java.lang.Long.toHexString(crc) + Integer.toHexString(j.hashCode())
    }

    /** Huella del CONTENIDO de un archivo (tamaño + CRC32): la misma foto movida o copiada da la misma. */
    fun fingerprint(f: File): String {
        if (!f.isFile) return "none"
        val crc = java.util.zip.CRC32()
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; crc.update(buf, 0, n) }
        }
        return "${f.length()}-${java.lang.Long.toHexString(crc.value)}"
    }

    /** Respuestas de "Mejorar con Jev" sobre la FOTO capturada (tipo, carpeta, encuadre, calidad): no dependen de las ediciones. */
    fun jevCaptureName(pageId: String) = "$JEV_DIR/${pageId}_capture.json"

    fun processedName(pageId: String, edits: PageEdits) =
        "$PROCESSED_DIR/${pageId}_${editsHash(edits)}.${if (isBinaryFilter(edits.filter)) "png" else "jpg"}"

    /**
     * Migración de una página guardada con un filtro retirado ("Texto resaltado" -> "Color natural",
     * [FilterType.migrated]): el resto de ediciones (recorte, giro, ajustes, borrados, renglones…) se conserva tal cual.
     * El hash de las ediciones cambia, así que el procesado y la miniatura se regeneran al abrirla (el resultado CAMBIA:
     * MAGIC y NATURAL son renders distintos); las páginas con otros filtros no cambian de hash y no se reprocesan.
     * El OCR y la maquetación de la IA no dependen del filtro (Indra lee la versión sólo con geometría y la geometría de
     * salida es la misma), así que se renombran al hash nuevo en vez de repetirlos; el texto corregido por el usuario
     * está en la propia página.
     */
    fun migrateFilter(dir: File, page: Page): Page {
        val f = page.edits.filter.migrated
        if (f == page.edits.filter) return page
        val edits = page.edits.copy(filter = f)
        fun move(from: String, to: String): Boolean {
            val a = File(dir, from); val b = File(dir, to)
            return b.exists() || (a.exists() && a.renameTo(b))
        }
        move(layoutName(page.id, page.edits), layoutName(page.id, edits))
        val ocr = page.ocrFile?.let { old -> if (old == ocrName(page.id, page.edits) && move(old, ocrName(page.id, edits))) ocrName(page.id, edits) else old }
        return page.copy(edits = edits, ocrFile = ocr)
    }

    fun thumbName(pageId: String, edits: PageEdits) = "$THUMB_DIR/${pageId}_${editsHash(edits)}.jpg"

    fun ocrName(pageId: String, edits: PageEdits) = "$OCR_DIR/${pageId}_${editsHash(edits)}.json"

    /**
     * Maquetación reconocida por la IA ([com.scannerpromax.domain.DocLayout]) de la versión [edits] de la página:
     * `processed/{id}_{hash}.{jpg|png}.layout.json`, junto al procesado de esa versión (misma ruta que
     * [com.scannerpromax.pdf.DocLayoutFiles.fileFor], que es quien la lee). Si existe, el OCR guardado lo produjo la IA.
     */
    fun layoutName(pageId: String, edits: PageEdits) = processedName(pageId, edits) + LAYOUT_SUFFIX

    const val LAYOUT_SUFFIX = ".layout.json"

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
