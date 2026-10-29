package com.scannerpromax.ai.jev

import com.scannerpromax.ai.cloud.CloudException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Jev (TypeSafe), modelo de DECISIÓN alojado: `POST https://api.typesafe.ai/v1/systemone` con un `state` y preguntas
 * tipadas (noul = probabilidad de sí, choice = una opción de un conjunto, score = nivel, box = UNA caja en fracciones
 * de la imagen). Las imágenes van en `assets` (base64) y se citan por su referencia dentro del `state`; hace falta la
 * cabecera `TypeSafe-Danger-Zone-Images: true`. Precio: solo la entrada ([PRICE_PER_M_INPUT]); la salida es gratis.
 * Medido (2026-10-29): una página a 1024 px ≈ 3 000 tokens, cada pregunta ≈ 22 más; ~0,45 s por petición.
 */
object JevApi {
    const val URL = "https://api.typesafe.ai/v1/systemone"
    /** Versión fija: Box exige 1.14 y así las respuestas no cambian por debajo del banco. */
    const val MODEL = "jev-1.14.0"
    /** USD por millón de tokens de entrada (verificado en docs.typesafe.ai/models el 2026-10-29). */
    const val PRICE_PER_M_INPUT = 0.042

    fun cost(inputTokens: Long): Double = inputTokens * PRICE_PER_M_INPUT / 1e6

    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

/** Una pregunta tipada. */
sealed class JevQuestion {
    abstract val instructions: String
    abstract fun toJson(): JsonObject

    /** Probabilidad 0..1 de "sí". */
    data class Noul(override val instructions: String, val whenTrue: String? = null, val whenFalse: String? = null) : JevQuestion() {
        override fun toJson() = buildJsonObject {
            put("type", "noul"); put("instructions", instructions)
            if (whenTrue != null || whenFalse != null) putJsonObject("criteria") {
                whenTrue?.let { put("true", it) }; whenFalse?.let { put("false", it) }
            }
        }
    }

    /** Una opción de [options] (nombre -> descripción; hasta 255). */
    data class Choice(override val instructions: String, val options: Map<String, String>) : JevQuestion() {
        init { require(options.size in 2..255) { "choice: 2..255 opciones" } }
        override fun toJson() = buildJsonObject {
            put("type", "choice"); put("instructions", instructions)
            putJsonObject("criteria") { options.forEach { (k, v) -> put(k, v) } }
        }
    }

    /** Posición en la escala de [levels] (2..10 descripciones; el nivel = su índice). La respuesta es un número. */
    data class Score(override val instructions: String, val levels: List<String>) : JevQuestion() {
        init { require(levels.size in 2..10) { "score: 2..10 niveles" } }
        override fun toJson() = buildJsonObject {
            put("type", "score"); put("instructions", instructions)
            put("criteria", kotlinx.serialization.json.JsonArray(levels.map { JsonPrimitive(it) }))
        }
    }

    /** UNA caja (siempre devuelve una aunque no haya nada: acompañar de una [Noul] de presencia). Requiere 1.14. */
    data class Box(override val instructions: String) : JevQuestion() {
        override fun toJson() = buildJsonObject { put("type", "box"); put("instructions", instructions) }
    }
}

/** Caja en fracciones (0..1) de la imagen ENVIADA, desde la esquina superior izquierda. */
data class JevBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width.coerceAtLeast(0.0) * height.coerceAtLeast(0.0)

    fun iou(o: JevBox): Double {
        val iw = minOf(right, o.right) - maxOf(left, o.left); val ih = minOf(bottom, o.bottom) - maxOf(top, o.top)
        if (iw <= 0 || ih <= 0) return 0.0
        val i = iw * ih
        return i / (area + o.area - i).coerceAtLeast(1e-12)
    }

    /** x0, y0, x1, y1 (el formato de [com.scannerpromax.imaging.ImageEnhancer.Options.layoutImages]). */
    fun toArray() = floatArrayOf(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

/** Respuesta a una pregunta. Solo se rellenan los campos de su tipo. */
data class JevAnswer(
    val type: String,
    val noul: Double? = null,
    val choice: String? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    /** Score: posición 0..n-1 (puede caer entre dos niveles). */
    val score: Double? = null,
    val box: JevBox? = null,
    val confidence: Double? = null,
) {
    /** Probabilidad de la opción [k] (choice/score). */
    fun p(k: String): Double = probabilities[k] ?: 0.0
}

/** Imagen ya preparada (JPEG/PNG dentro de los límites) con su referencia única. */
class JevAsset(val ref: String, val bytes: ByteArray, val format: String = "jpeg")

/** Petición: [state] (con las referencias de [assets] dentro) y preguntas por id. */
class JevRequest(
    val state: JsonElement,
    val questions: Map<String, JevQuestion>,
    val assets: List<JevAsset> = emptyList(),
    val model: String = JevApi.MODEL,
) {
    val hasImages: Boolean get() = assets.isNotEmpty() || questions.values.any { it is JevQuestion.Box }

    fun toJson(): JsonObject = buildJsonObject {
        put("model", model)
        put("state", state)
        if (assets.isNotEmpty()) putJsonObject("assets") {
            for (a in assets) putJsonObject(a.ref) {
                put("type", "image"); put("format", a.format)
                put("data_base64", java.util.Base64.getEncoder().encodeToString(a.bytes))
            }
        }
        putJsonObject("questions") { questions.forEach { (k, q) -> put(k, q.toJson()) } }
    }
}

class JevResponse(
    val answers: Map<String, JevAnswer>,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Duración de la llamada (con reintentos), ms. */
    val ms: Long = 0,
) {
    val cost: Double get() = JevApi.cost(inputTokens)

    operator fun get(id: String): JevAnswer? = answers[id]

    companion object {
        /** `{answers, model, usage:{input_tokens, output_tokens}}` -> [JevResponse]. Probado en JVM. */
        fun parse(text: String, ms: Long = 0): JevResponse {
            val root = runCatching { JevApi.json.parseToJsonElement(text).jsonObject }.getOrElse { throw CloudException.BadResponse("no es JSON") }
            val answers = root["answers"] as? JsonObject ?: throw CloudException.BadResponse("sin answers")
            val usage = root["usage"] as? JsonObject
            fun u(k: String) = (usage?.get(k) as? JsonPrimitive)?.longOrNull ?: 0L
            return JevResponse(
                answers.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to parseAnswer(it) } }.toMap(),
                (root["model"] as? JsonPrimitive)?.content.orEmpty(), u("input_tokens"), u("output_tokens"), ms,
            )
        }

        private fun d(e: JsonElement?): Double? = (e as? JsonPrimitive)?.doubleOrNull

        private fun parseAnswer(o: JsonObject): JevAnswer {
            val type = (o["type"] as? JsonPrimitive)?.content.orEmpty()
            val probs = (o["probabilities"] as? JsonObject)?.mapNotNull { (k, v) -> d(v)?.let { k to it } }?.toMap().orEmpty()
            val box = (o["box"] as? JsonObject)?.let { b ->
                val l = d(b["left"]); val t = d(b["top"]); val r = d(b["right"]); val bt = d(b["bottom"])
                if (l == null || t == null || r == null || bt == null) null
                else JevBox(l.coerceIn(0.0, 1.0), t.coerceIn(0.0, 1.0), r.coerceIn(0.0, 1.0), bt.coerceIn(0.0, 1.0))
            }
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content
            return JevAnswer(
                type = type,
                noul = d(o["noul"]),
                choice = if (type == "choice") s("choice") else null,
                probabilities = probs,
                score = if (type == "score") d(o["score"]) else null,
                box = box,
                confidence = d(o["confidence"]),
            )
        }

        /** Mensaje de error del servidor (recortado, sin datos sensibles). */
        fun errorDetail(text: String): String? = runCatching {
            when (val e = JevApi.json.parseToJsonElement(text)) {
                is JsonObject -> (e["detail"] ?: e["error"] ?: e["message"])?.let { if (it is JsonPrimitive) it.content else it.toString() }
                is JsonArray -> e.toString()
                else -> null
            }
        }.getOrNull().let { CloudException.safeDetail(it) }
    }
}
