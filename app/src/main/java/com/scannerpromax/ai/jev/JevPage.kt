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
 * Lo que "Mejorar con Jev" sabe de una página rectificada: cajas de FOTO (ya ajustadas localmente), de TABLA y de FIRMA o
 * SELLO (tinta que conserva su color), en fracciones (x0, y0, x1, y1) de la página. Todo se pregunta en UNA petición
 * (la imagen, ≈ 6 000 tokens a 1568 px, se cobra una vez; las preguntas de más casi no cuestan).
 */
class JevPageResult(
    val photos: List<FloatArray>,
    val tables: List<FloatArray>,
    val inputTokens: Long = 0,
    val ms: Long = 0,
    /** Qué se preguntó (para no reutilizar una caché que no tiene lo que ahora se pide). */
    val askedPhotos: Boolean = true,
    val askedTables: Boolean = true,
    /** Firmas y sellos (zonas de tinta que pasan con su color, sin binarizar ni trocear). */
    val inks: List<FloatArray> = emptyList(),
    val askedInks: Boolean = false,
) {
    fun toJson(): String = buildJsonObject {
        put("v", VERSION)
        put("photos", boxes(photos)); put("tables", boxes(tables)); put("inks", boxes(inks))
        put("tokens", inputTokens); put("ms", ms)
        put("askedPhotos", askedPhotos); put("askedTables", askedTables); put("askedInks", askedInks)
    }.toString()

    fun covers(photos: Boolean, tables: Boolean, inks: Boolean = false) =
        (!photos || askedPhotos) && (!tables || askedTables) && (!inks || askedInks)

    companion object {
        /** Sube si cambia el protocolo: las cachés viejas se ignoran (las de antes de las firmas valen: askedInks = false). */
        const val VERSION = 2

        private fun boxes(l: List<FloatArray>): JsonArray = buildJsonArray { for (b in l) add(JsonArray(b.map { JsonPrimitive(it) })) }

        fun fromJson(s: String): JevPageResult? = runCatching {
            val o = JevApi.json.parseToJsonElement(s).jsonObject
            if ((o["v"] as? JsonPrimitive)?.longOrNull?.toInt() != VERSION) return null
            fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }?.takeIf { it.size == 4 || it.size == 6 }?.toFloatArray()
            }
            fun b(k: String, def: Boolean = true) = (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: def
            JevPageResult(list("photos"), list("tables"), (o["tokens"] as? JsonPrimitive)?.longOrNull ?: 0, (o["ms"] as? JsonPrimitive)?.longOrNull ?: 0,
                b("askedPhotos"), b("askedTables"), list("inks"), b("askedInks", false))
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

/** Lo que la página aporta al render ([com.scannerpromax.imaging.PageProcessor.render]). */
class JevRenderHints(val photos: List<FloatArray>, val tables: List<FloatArray>, val inks: List<FloatArray> = emptyList())

object JevPage {

    /** Lado largo de la versión ligera que se envía (1568 = máximo de la API: mejor caja que a 1024, banco notes_jev.md). */
    const val SIDE = 1568

    /** Firma: noul ≥ [SIG_T] (banco i6: TPR 0,89, FPR 0,17; el detector local no tiene firmas). */
    const val SIG_T = 0.9
    /** Sello: noul ≥ [STAMP_T] (banco i6, sellos sintéticos: TPR 0,93, FPR 0). */
    const val STAMP_T = 0.8
    /** Margen de la caja de la firma (fracción de su tamaño por lado; IoU mediano 0,58: la caja de Jev se queda corta). */
    const val SIG_PAD = 0.25
    const val STAMP_PAD = 0.1

    /** Petición combinada (fotos, tablas y/o firmas y sellos) sobre [img]. */
    fun request(img: JevImage, photos: Boolean, tables: Boolean, inks: Boolean = false): JevRequest {
        require(photos || tables || inks)
        val q = LinkedHashMap<String, JevQuestion>()
        if (photos) q += JevPhotos.request(img).questions
        if (tables) q += JevTables.request(img).questions
        if (inks) {
            q["has_sig"] = JevQuestion.Noul("Does `page` contain a handwritten signature?")
            q["sig_box"] = JevQuestion.Box("Locate the handwritten signature in `page`.")
            q["has_stamp"] = JevQuestion.Noul("Does `page` contain an ink stamp or seal (a rubber stamp or embossed/printed official seal)?")
            q["stamp_box"] = JevQuestion.Box("Locate the ink stamp or seal in `page`.")
        }
        val what = listOfNotNull("the pictures".takeIf { photos }, "the ruled tables".takeIf { tables }, "the signatures and stamps".takeIf { inks })
        val task = "Find " + (if (what.size == 1) what[0] else what.dropLast(1).joinToString(", ") + " and " + what.last()) + " on a photographed document page."
        return JevRequest(buildJsonObject { put("page", JsonPrimitive(img.asset.ref)); put("task", task) }, q, listOf(img.asset))
    }

    /** Firmas y sellos de la respuesta (cajas ampliadas, fracciones de la página). */
    fun parseInks(r: JevResponse, fit: JevFit): List<FloatArray> {
        val out = ArrayList<FloatArray>(2)
        fun add(p: String, b: String, t: Double, pad: Double) {
            if ((r[p]?.noul ?: 0.0) < t) return
            val bx = fit.toSource(r[b]?.box ?: return)
            if (bx.width < 0.01 || bx.height < 0.005 || bx.area > 0.25) return
            val px = bx.width * pad; val py = bx.height * pad
            val e = JevBox((bx.left - px).coerceAtLeast(0.0), (bx.top - py).coerceAtLeast(0.0), (bx.right + px).coerceAtMost(1.0), (bx.bottom + py).coerceAtMost(1.0))
            if (out.none { JevBox(it[0].toDouble(), it[1].toDouble(), it[2].toDouble(), it[3].toDouble()).iou(e) > 0.5 }) out += e.toArray()
        }
        add("has_sig", "sig_box", SIG_T, SIG_PAD)
        add("has_stamp", "stamp_box", STAMP_T, STAMP_PAD)
        return out
    }

    /**
     * Analiza [page] (RGB/RGBA rectificada, cualquier tamaño) con [client]. Lanza si falla la red o la API (quien llama
     * sigue sin Jev).
     */
    fun analyze(page: Mat, client: JevClient, photos: Boolean, tables: Boolean, inks: Boolean = false, side: Int = SIDE): JevPageResult {
        val img = JevImages.encode(page, side, 75, prefix = "page")
        val r = client.ask(request(img, photos, tables, inks))
        val ph = if (photos) JevPhotos.refine(page, JevPhotos.parse(r, img.fit).boxes).map { it.toArray() } else emptyList()
        val tb = if (tables) JevTables.parse(r, img.fit).hints() else emptyList()
        val ik = if (inks) parseInks(r, img.fit) else emptyList()
        return JevPageResult(ph, tb, r.inputTokens, r.ms, photos, tables, ik, inks)
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
