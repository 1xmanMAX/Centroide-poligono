package com.scannerpromax.ai.jev

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.opencv.core.Mat

/**
 * Zona gris de [com.scannerpromax.imaging.DuplicatePages]: ¿son `first` y `second` la misma hoja física? Las dos
 * páginas a 768 px en una petición (≈ 3 700 tokens). Banco i7: AUC 1,000; en la zona gris los duplicados dieron
 * 0,65–0,83 y las hojas distintas ≤ 0,49 → umbral [SAME].
 */
object JevDuplicates {

    const val SIDE = 768
    const val SAME = 0.6

    fun request(a: JevImage, b: JevImage): JevRequest = JevRequest(
        buildJsonObject { put("first", JsonPrimitive(a.asset.ref)); put("second", JsonPrimitive(b.asset.ref)) },
        mapOf("same" to JevQuestion.Noul(
            "Do `first` and `second` show the same document page (the same physical page with the same content, " +
                "possibly photographed differently), rather than two different pages?",
        )),
        listOf(a.asset, b.asset),
    )

    /** Probabilidad de que [a] y [b] (RGB/RGBA/gris) sean la misma hoja. Lanza si falla la red o la API. */
    fun samePage(a: Mat, b: Mat, client: JevClient): Pair<Double, Long> {
        val ia = JevImages.encode(a, SIDE, 75, prefix = "first")
        val ib = JevImages.encode(b, SIDE, 75, prefix = "second")
        val r = client.ask(request(ia, ib))
        return (r["same"]?.noul ?: 0.0) to r.inputTokens
    }
}
