package com.scannerpromax.ai.jev

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.ScanMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.opencv.core.Mat
import java.io.File

/**
 * Lo que "Mejorar con Jev" pregunta sobre la FOTO recién capturada o importada (la original, sin recortar, a 1024 px),
 * todo en UNA petición: la imagen (≈ 2 500–3 000 tokens) se cobra una vez y cada pregunta de más cuesta ≈ 22 tokens.
 *  - `doc` (noul) + `box` (box): ¿hay un documento? y dónde (rescate del encuadre; banco i9: AUC 0,998, IoU mediano en
 *    los fallos del detector 0,43 → 0,89);
 *  - `type_a` / `type_b` (choice, mismas opciones en orden inverso): tipo de documento → modo y filtro (banco i1:
 *    acierto 26,6 % → 93,5 %; con ambas de acuerdo y p ≥ 0,7, 97 %);
 *  - `folder` (choice): carpeta sugerida (banco i8: 84 %);
 *  - `glare`, `cut`, `finger`, `shadow` (noul): problemas de captura para sugerir «Reescanear» (banco i3, umbrales altos).
 * Los textos de las preguntas son los del banco (scratchpad jev_ideas/ideas/i1, i3, i8, i9): cambiarlos exige volver a medir.
 */
object JevCapture {

    /** Lado largo de la versión ligera (el banco usó 1024 px). */
    const val SIDE = 1024

    /** Tipos de documento (banco i1; 8 clases + "otro"). El orden importa: `type_b` las pregunta al revés. */
    val TYPES: Map<String, String> = linkedMapOf(
        "printed" to "A printed or typed document page: letter, form, article, report, invoice on A4/letter paper.",
        "handwritten" to "Handwritten notes, a notebook page, or a handwritten letter or manuscript.",
        "receipt" to "A shop receipt or till ticket, usually a narrow strip of thermal paper.",
        "id_card" to "An identity card, passport, driving licence, bank card or business card.",
        "whiteboard" to "A whiteboard, blackboard or chalkboard with writing or drawings.",
        "screen" to "A photo of a computer monitor, TV, laptop or phone screen.",
        "book" to "An open book or a page of a bound book or magazine.",
        "photo" to "A photograph of a scene, people or objects, with no document in it.",
        "other" to "Anything else.",
    )

    /** Carpetas (banco i8). "otro" nunca se sugiere. */
    val FOLDERS: Map<String, String> = linkedMapOf(
        "factura" to "Invoice or bill from a company, with amounts to pay.",
        "recibo" to "Shop receipt or till ticket.",
        "carta" to "Letter, memo, email or other correspondence.",
        "formulario" to "Form or questionnaire with fields to fill in.",
        "cv" to "Resume / curriculum vitae.",
        "informe" to "Report, article, scientific paper, news article or technical specification.",
        "finanzas" to "Budget, financial table or accounting sheet.",
        "publicidad" to "Advertisement, flyer or promotional material.",
        "presentacion" to "Presentation slide or overhead.",
        "examen" to "School exam, test or worksheet with questions and answers.",
        "contrato" to "Contract or legal agreement.",
        "identidad" to "Identity card, passport, driving licence or other ID card.",
        "apuntes" to "Handwritten notes or handwritten letter.",
        "otro" to "Something else (cover page, file folder, envelope, photo...).",
    )

    /** Nombre de la carpeta en la interfaz (plural, como una carpeta). */
    fun folderLabel(key: String?): String? = when (key) {
        "factura" -> "Facturas"
        "recibo" -> "Recibos"
        "carta" -> "Cartas"
        "formulario" -> "Formularios"
        "cv" -> "Currículums"
        "informe" -> "Informes"
        "finanzas" -> "Finanzas"
        "publicidad" -> "Publicidad"
        "presentacion" -> "Presentaciones"
        "examen" -> "Exámenes"
        "contrato" -> "Contratos"
        "identidad" -> "Identidad"
        "apuntes" -> "Apuntes"
        else -> null
    }

    private val QUALITY = linkedMapOf(
        "glare" to "Is there a bright glare or reflection spot on the document in `photo` that washes out part of the content?",
        "finger" to "Is a finger or hand covering part of the document in `photo`?",
        "cut" to "Is part of the document in `photo` cut off by the edge of the photo, so that some of the page is missing?",
        "shadow" to "Is there a strong shadow cast over part of the document in `photo`?",
    )

    /** Petición combinada sobre la foto [img]. */
    fun request(img: JevImage, type: Boolean = true, folder: Boolean = true, framing: Boolean = true, quality: Boolean = true): JevRequest {
        require(type || folder || framing || quality)
        val q = LinkedHashMap<String, JevQuestion>()
        if (framing) {
            q["doc"] = JevQuestion.Noul("Is there a document, sheet of paper, receipt, card or book page in `photo`?")
            q["box"] = JevQuestion.Box("Locate the document (sheet of paper, receipt, card or page) in `photo`.")
        }
        if (type) {
            val order = TYPES.keys.toList()
            val rev = order.dropLast(1).reversed() + order.last()
            q["type_a"] = JevQuestion.Choice("What kind of document is shown in `photo`?", order.associateWith { TYPES.getValue(it) })
            q["type_b"] = JevQuestion.Choice("What kind of document is shown in `photo`?", rev.associateWith { TYPES.getValue(it) })
        }
        if (folder) q["folder"] = JevQuestion.Choice("Which folder should the scanned document in `photo` be filed in?", FOLDERS)
        if (quality) QUALITY.forEach { (k, v) -> q[k] = JevQuestion.Noul(v) }
        return JevRequest(buildJsonObject { put("photo", JsonPrimitive(img.asset.ref)) }, q, listOf(img.asset))
    }

    fun parse(r: JevResponse, fit: JevFit): CaptureHints {
        val a = r["type_a"]; val b = r["type_b"]
        val type = a?.choice
        return CaptureHints(
            docP = r["doc"]?.noul,
            box = r["box"]?.box?.let { fit.toSource(it) },
            boxConf = r["box"]?.confidence,
            type = type,
            typeP = type?.let { a.p(it) } ?: 0.0,
            typeP2 = type?.let { b?.p(it) } ?: 0.0,
            typeAgree = type != null && b?.choice == type,
            folder = r["folder"]?.choice,
            folderP = r["folder"]?.let { f -> f.choice?.let { f.p(it) } } ?: 0.0,
            glare = r["glare"]?.noul, cut = r["cut"]?.noul, finger = r["finger"]?.noul, shadow = r["shadow"]?.noul,
            inputTokens = r.inputTokens, ms = r.ms,
        )
    }

    /** Analiza [photo] (RGB/RGBA, cualquier tamaño). Lanza si falla la red o la API. */
    fun analyze(photo: Mat, client: JevClient, side: Int = SIDE): CaptureHints {
        val img = JevImages.encode(photo, side, 80, prefix = "photo")
        return parse(client.ask(request(img)), img.fit)
    }

    // ------------------------------------------------------------------ decisiones (JVM puro, con tests)

    /** Sin documento si la presencia queda por debajo (banco i9: documentos ≥ 0,85, sin documento con quad 82 % → 5 %). */
    const val NO_DOC = 0.7
    /** Rescate con la caja solo si Jev está seguro de que hay documento. */
    const val DOC_SURE = 0.85
    /** Confianza del detector por debajo de la cual se rescata (la misma que usaba el rescate de la antigua IA local). */
    const val RESCUE_CONFIDENCE = 0.5f
    /** Ampliación de la caja de Jev (fracción de la foto por lado) antes de volver a detectar: contiene la hoja entera en el 97 %. */
    const val ROI_PAD = 0.08
    /** Tipo de documento: ambas preguntas de acuerdo y p ≥ [TYPE_MIN_P] en las dos. */
    const val TYPE_MIN_P = 0.7
    /** Carpeta sugerida solo con p ≥ [FOLDER_MIN_P] (banco i8: 80 % de los documentos con sugerencia, 92 % de acierto). */
    const val FOLDER_MIN_P = 0.7

    /** Umbrales de los avisos de calidad (altos: pocos falsos positivos; banco i3 / i3r). */
    const val GLARE_T = 0.9
    const val CUT_T = 0.6
    const val FINGER_T = 0.85
    const val SHADOW_T = 0.8

    /** Qué hacer con el encuadre. */
    sealed class Framing {
        /** El del detector clásico. */
        data object Keep : Framing()
        /** No hay documento: página completa, sin recorte. */
        data object FullPage : Framing()
        /**
         * Volver a detectar dentro de [roi] (la caja de Jev ampliada; fracciones x0, y0, x1, y1 de la foto); si tampoco
         * sale un cuadrilátero creíble, recortar por [fallback] (la caja de Jev casi tal cual: en el banco, IoU mediano
         * 0,84 con la verdad frente a 0,53 de la caja ampliada un 8 %).
         */
        data class Roi(val roi: JevBox, val fallback: JevBox) : Framing()
    }

    /** Desacuerdo: la caja del cuadrilátero del detector y la de Jev se solapan menos que esto (banco: aciertos ≥ 0,76). */
    const val DISAGREE_IOU = 0.6
    /** ...y Jev está seguro de su caja. */
    const val BOX_SURE = 0.6
    /** Margen de la caja de Jev cuando se usa como recorte (que no se coma el borde de la hoja). */
    const val BOX_PAD = 0.01

    /**
     * [quadBox] = caja envolvente del cuadrilátero del detector (fracciones de la foto) o null si no dio uno usable;
     * [confidence] su confianza (null si no dio nada); [nearlyFull] = el quad es casi la foto entera. Se rescata si el
     * detector es débil o si su cuadrilátero contradice a la caja de Jev. Sin [h] (sin Jev): siempre [Framing.Keep].
     */
    fun framing(h: CaptureHints?, quadBox: JevBox?, confidence: Float?, nearlyFull: Boolean): Framing {
        val p = h?.docP ?: return Framing.Keep
        if (p < NO_DOC) return Framing.FullPage
        if (p < DOC_SURE) return Framing.Keep
        val b = h.box ?: return Framing.Keep
        // (una caja diminuta o que es toda la foto no ayuda)
        if (b.width < 0.08 || b.height < 0.08 || b.area > 0.97) return Framing.Keep
        val weak = quadBox == null || (confidence ?: 0f) < RESCUE_CONFIDENCE || nearlyFull
        val disagree = quadBox != null && (h.boxConf ?: 0.0) >= BOX_SURE && quadBox.iou(b) < DISAGREE_IOU
        if (!weak && !disagree) return Framing.Keep
        return Framing.Roi(pad(b, ROI_PAD), pad(b, BOX_PAD))
    }

    private fun pad(b: JevBox, d: Double) =
        JevBox((b.left - d).coerceAtLeast(0.0), (b.top - d).coerceAtLeast(0.0), (b.right + d).coerceAtMost(1.0), (b.bottom + d).coerceAtMost(1.0))

    /**
     * ¿Vale el cuadrilátero [q] (x0, y0 … x3, y3 en fracciones de la foto) que el detector clásico encontró dentro de
     * [roi]? Ni diminuto ni toda la zona (el detector "cayó" al recorte entero) y de acuerdo con la caja de Jev [box].
     */
    fun roiQuadOk(q: FloatArray, roi: JevBox, box: JevBox?): Boolean {
        if (q.size < 8 || roi.area <= 0) return false
        var a = 0.0
        for (i in 0 until 4) { val j = (i + 1) % 4; a += q[2 * i].toDouble() * q[2 * j + 1] - q[2 * j].toDouble() * q[2 * i + 1] }
        val f = kotlin.math.abs(a) / 2 / roi.area
        if (f < 0.2 || f > 0.97) return false
        if (box == null) return true
        val xs = (0 until 4).map { q[2 * it].toDouble() }; val ys = (0 until 4).map { q[2 * it + 1].toDouble() }
        return JevBox(xs.min(), ys.min(), xs.max(), ys.max()).iou(box) >= 0.5
    }

    /** Tipo de documento fiable (o null): las dos preguntas de acuerdo y ambas con p ≥ [TYPE_MIN_P]. */
    fun confidentType(h: CaptureHints?): String? =
        h?.type?.takeIf { h.typeAgree && h.typeP >= TYPE_MIN_P && h.typeP2 >= TYPE_MIN_P }

    /**
     * Ediciones según el tipo de documento (lo que haría el modo equivalente), o null si no hay que tocar nada: solo
     * documentos normales ([mode] DOCUMENT) con el filtro por defecto (el usuario no eligió otro) y sin pista previa.
     * - recibo / pizarra / pantalla: AUTO con la pista ([com.scannerpromax.imaging.ImageEnhancer.withAiHint]: realce de
     *   tique térmico, pizarra, anti-muaré), sin enderezar la pizarra (como el modo Pizarra);
     * - DNI/tarjeta: AUTO con la pista (color vivo, como cada cara del modo DNI), sin enderezar;
     * - foto sin documento: Color original, sin limpieza ni enderezado (como el modo Foto);
     * - impreso, manuscrito, libro, otro: nada (Color natural ya es lo mejor para ellos).
     */
    fun typeEdits(type: String?, mode: ScanMode, edits: PageEdits): PageEdits? {
        if (type == null || mode != ScanMode.DOCUMENT) return null
        if (edits.aiHint != null || edits.filter != FilterType.DEFAULT || edits.manualQuad == true) return null
        return when (type) {
            "receipt", "screen" -> edits.copy(filter = FilterType.AUTO, aiHint = type)
            "whiteboard", "id_card" -> edits.copy(filter = FilterType.AUTO, aiHint = type, autoDeskew = false, autoDewarp = false)
            "photo" -> edits.copy(filter = FilterType.ORIGINAL, aiHint = type, autoDenoise = false, autoDeskew = false, autoDewarp = false, autoRemoveLines = false)
            else -> null
        }
    }

    /** Carpeta que se sugiere (o null). */
    fun suggestedFolder(h: CaptureHints?): String? =
        h?.folder?.takeIf { it != "otro" && h.folderP >= FOLDER_MIN_P && folderLabel(it) != null }

    /**
     * Avisos de calidad (códigos [PageIssue]). [box] = caja del recorte final (fracciones de la foto) o null: "cortada"
     * solo cuenta si el documento (ese recorte o la caja de Jev) toca algún borde de la foto pero no los cuatro (una
     * página escaneada que llena la foto no está cortada).
     */
    fun issues(h: CaptureHints?, box: JevBox?): List<String> {
        if (h == null || (h.docP ?: 1.0) < NO_DOC) return emptyList()
        val out = ArrayList<String>(3)
        if ((h.glare ?: 0.0) >= GLARE_T) out += PageIssue.GLARE
        if ((h.finger ?: 0.0) >= FINGER_T) out += PageIssue.FINGER
        if ((h.cut ?: 0.0) >= CUT_T) {
            val bs = listOfNotNull(box, h.box)
            val e = EDGE
            val touched = listOf(
                bs.any { it.left <= e }, bs.any { it.top <= e }, bs.any { it.right >= 1 - e }, bs.any { it.bottom >= 1 - e },
            ).count { it }
            if (touched in 1..3) out += PageIssue.CUT
        }
        if ((h.shadow ?: 0.0) >= SHADOW_T) out += PageIssue.SHADOW
        return out
    }

    /** Distancia al borde de la foto (fracción) a la que el documento "toca" el borde. */
    const val EDGE = 0.02
}

/** Códigos de los avisos de calidad de una página ([com.scannerpromax.domain.Page.issues]). */
object PageIssue {
    const val GLARE = "glare"
    const val FINGER = "finger"
    const val CUT = "cut"
    const val SHADOW = "shadow"

    /** Texto corto del aviso. */
    fun label(code: String): String? = when (code) {
        GLARE -> "Reflejo"
        FINGER -> "Dedo en la foto"
        CUT -> "Página cortada"
        SHADOW -> "Sombra fuerte"
        else -> null
    }

    /** Frase del aviso para varias causas: «Reflejo y sombra fuerte». */
    fun sentence(codes: List<String>): String? {
        val l = codes.mapNotNull { label(it) }
        if (l.isEmpty()) return null
        val s = if (l.size == 1) l[0] else l.dropLast(1).joinToString(", ") + " y " + l.last().lowercase()
        return s
    }
}

/** Respuestas sobre la foto capturada ([JevCapture]); la caja, en fracciones de la foto ORIGINAL. */
data class CaptureHints(
    val docP: Double? = null,
    val box: JevBox? = null,
    val boxConf: Double? = null,
    val type: String? = null,
    val typeP: Double = 0.0,
    val typeP2: Double = 0.0,
    val typeAgree: Boolean = false,
    val folder: String? = null,
    val folderP: Double = 0.0,
    val glare: Double? = null,
    val cut: Double? = null,
    val finger: Double? = null,
    val shadow: Double? = null,
    val inputTokens: Long = 0,
    val ms: Long = 0,
) {
    fun toJson(): String = buildJsonObject {
        put("v", VERSION)
        docP?.let { put("doc", it) }
        box?.let { b -> put("box", buildJsonObject { put("l", b.left); put("t", b.top); put("r", b.right); put("b", b.bottom) }) }
        boxConf?.let { put("boxConf", it) }
        type?.let { put("type", it) }
        put("typeP", typeP); put("typeP2", typeP2); put("typeAgree", typeAgree)
        folder?.let { put("folder", it) }
        put("folderP", folderP)
        glare?.let { put("glare", it) }; cut?.let { put("cut", it) }; finger?.let { put("finger", it) }; shadow?.let { put("shadow", it) }
        put("tokens", inputTokens); put("ms", ms)
    }.toString()

    companion object {
        const val VERSION = 1

        fun fromJson(s: String): CaptureHints? = runCatching {
            val o = JevApi.json.parseToJsonElement(s).jsonObject
            if ((o["v"] as? JsonPrimitive)?.longOrNull?.toInt() != VERSION) return null
            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val box = (o["box"] as? JsonObject)?.let { b ->
                fun bd(k: String) = (b[k] as? JsonPrimitive)?.doubleOrNull
                val l = bd("l"); val t = bd("t"); val r = bd("r"); val bt = bd("b")
                if (l == null || t == null || r == null || bt == null) null else JevBox(l, t, r, bt)
            }
            CaptureHints(
                d("doc"), box, d("boxConf"), str("type"), d("typeP") ?: 0.0, d("typeP2") ?: 0.0,
                (o["typeAgree"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                str("folder"), d("folderP") ?: 0.0, d("glare"), d("cut"), d("finger"), d("shadow"),
                (o["tokens"] as? JsonPrimitive)?.longOrNull ?: 0, (o["ms"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }.getOrNull()

        fun read(f: File): CaptureHints? = if (f.isFile) runCatching { fromJson(f.readText()) }.getOrNull() else null

        fun write(f: File, h: CaptureHints) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(h.toJson())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
