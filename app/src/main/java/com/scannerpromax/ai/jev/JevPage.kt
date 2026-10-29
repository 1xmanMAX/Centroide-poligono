package com.scannerpromax.ai.jev

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.opencv.core.Mat
import java.io.File

/**
 * Lo que "Mejorar con Jev" sabe de una página rectificada: cajas de FOTO (ya ajustadas localmente) y de TABLA, en
 * fracciones (x0, y0, x1, y1) de la página. Fotos y tablas se preguntan en UNA petición (la imagen, ≈ 6 000 tokens a
 * 1568 px, se cobra una vez; las preguntas de más casi no cuestan).
 */
class JevPageResult(
    val photos: List<FloatArray>,
    val tables: List<FloatArray>,
    val inputTokens: Long = 0,
    val ms: Long = 0,
    /** Qué se preguntó (para no reutilizar una caché que no tiene lo que ahora se pide). */
    val askedPhotos: Boolean = true,
    val askedTables: Boolean = true,
) {
    fun toJson(): String = buildJsonObject {
        put("v", VERSION)
        put("photos", boxes(photos)); put("tables", boxes(tables))
        put("tokens", inputTokens); put("ms", ms)
        put("askedPhotos", askedPhotos); put("askedTables", askedTables)
    }.toString()

    fun covers(photos: Boolean, tables: Boolean) = (!photos || askedPhotos) && (!tables || askedTables)

    companion object {
        /** Sube si cambia el protocolo: las cachés viejas se ignoran. */
        const val VERSION = 2

        private fun boxes(l: List<FloatArray>): JsonArray = buildJsonArray { for (b in l) add(JsonArray(b.map { JsonPrimitive(it) })) }

        fun fromJson(s: String): JevPageResult? = runCatching {
            val o = JevApi.json.parseToJsonElement(s).jsonObject
            if ((o["v"] as? JsonPrimitive)?.longOrNull?.toInt() != VERSION) return null
            fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }?.takeIf { it.size == 4 || it.size == 6 }?.toFloatArray()
            }
            fun b(k: String) = (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
            JevPageResult(list("photos"), list("tables"), (o["tokens"] as? JsonPrimitive)?.longOrNull ?: 0, (o["ms"] as? JsonPrimitive)?.longOrNull ?: 0, b("askedPhotos"), b("askedTables"))
        }.getOrNull()

        fun read(f: File): JevPageResult? = if (f.isFile) runCatching { fromJson(f.readText()) }.getOrNull() else null

        fun write(f: File, r: JevPageResult) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(r.toJson())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}

object JevPage {

    /** Lado largo de la versión ligera que se envía (1568 = máximo de la API: mejor caja que a 1024, banco notes_jev.md). */
    const val SIDE = 1568

    /** Petición combinada (fotos y/o tablas) sobre [img]. */
    fun request(img: JevImage, photos: Boolean, tables: Boolean): JevRequest {
        require(photos || tables)
        val q = LinkedHashMap<String, JevQuestion>()
        if (photos) q += JevPhotos.request(img).questions
        if (tables) q += JevTables.request(img).questions
        val task = when {
            photos && tables -> "Find the pictures and the ruled tables on a photographed document page."
            photos -> "Find the pictures on a photographed document page."
            else -> "Find the ruled tables on a photographed document page."
        }
        return JevRequest(buildJsonObject { put("page", JsonPrimitive(img.asset.ref)); put("task", task) }, q, listOf(img.asset))
    }

    /**
     * Analiza [page] (RGB/RGBA rectificada, cualquier tamaño) con [client]. Lanza si falla la red o la API (quien llama
     * sigue sin Jev).
     */
    fun analyze(page: Mat, client: JevClient, photos: Boolean, tables: Boolean, side: Int = SIDE): JevPageResult {
        val img = JevImages.encode(page, side, 75, prefix = "page")
        val r = client.ask(request(img, photos, tables))
        val ph = if (photos) JevPhotos.refine(page, JevPhotos.parse(r, img.fit).boxes).map { it.toArray() } else emptyList()
        val tb = if (tables) JevTables.parse(r, img.fit).hints() else emptyList()
        return JevPageResult(ph, tb, r.inputTokens, r.ms, photos, tables)
    }

    /**
     * Cajas de foto finales: las de la maquetación (PP-DocLayoutV3) y, además, las de Jev que no se solapan con
     * ninguna (IoU < 0,3): banco notes_jev.md, PP+Jev 0,885 de IoU medio frente a 0,867 solo PP; sin maquetación,
     * Jev solo (0,75 frente a 0,48 de la detección clásica).
     */
    fun mergePhotos(layout: List<FloatArray>, jev: List<FloatArray>): List<FloatArray> {
        if (jev.isEmpty()) return layout
        fun box(b: FloatArray) = JevBox(b[0].toDouble(), b[1].toDouble(), b[2].toDouble(), b[3].toDouble())
        return layout + jev.filter { j -> layout.none { box(it).iou(box(j)) >= 0.3 } }
    }
}
