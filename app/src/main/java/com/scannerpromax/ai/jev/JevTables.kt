package com.scannerpromax.ai.jev

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.opencv.core.Mat

/**
 * TABLAS con Jev, para que [com.scannerpromax.imaging.TableLines] reconstruya las líneas donde la luz las quemó: en
 * una petición, `ntab` (choice 0/1/2/3+), `tany` (noul), `t0`…`t2` (box ordinal), `h0`…`v2` (noul: ¿líneas entre filas / columnas?; si no, esa orientación no se relaja) y, de la primera, `rows0` / `cols0`
 * (choice con el recuento, para validar). Las cajas (fracciones de la página, algo ampliadas: Jev las da a 0,01 y
 * deben incluir el marco) entran como `ImageEnhancer.Options.tableHints`: dentro, TableLines usa un umbral más bajo,
 * acepta líneas más tenues y completa la rejilla a través de las zonas quemadas. Sin cajas, nada cambia.
 */
object JevTables {

    const val MAX_TABLES = 3
    private val ORD = listOf("1st", "2nd", "3rd")
    private const val WHAT = "tables (grids of rows and columns separated by printed ruling lines)"
    /** Margen añadido a cada lado de la caja de Jev (fracción de la página). */
    @JvmField var PAD = 0.008

    fun request(page: Mat, maxSide: Int = 1568): Pair<JevImage, JevRequest> {
        val img = JevImages.encode(page, maxSide, 75, prefix = "page")
        return img to request(img)
    }

    fun request(img: JevImage): JevRequest {
        val q = LinkedHashMap<String, JevQuestion>()
        q["ntab"] = JevQuestion.Choice("How many separate $WHAT are on the document page in `page`?",
            linkedMapOf("0" to "None.", "1" to "Exactly one.", "2" to "Exactly two.", "3+" to "Three or more."))
        q["tany"] = JevQuestion.Noul("Does the document page in `page` contain at least one of these $WHAT?")
        for (i in 0 until MAX_TABLES) q["t$i"] = JevQuestion.Box(
            "the ${ORD[i]} table in reading order (top to bottom) among the $WHAT on the page in `page`; " +
                "the box must include the table's outer border lines and header",
        )
        for (i in 0 until MAX_TABLES) {
            q["h$i"] = JevQuestion.Noul("Does the ${ORD[i]} table in reading order on the page in `page` have printed horizontal lines separating its rows (not only a header line)?")
            q["v$i"] = JevQuestion.Noul("Does the ${ORD[i]} table in reading order on the page in `page` have printed vertical lines separating its columns?")
        }
        val counts = (1..30).associate { "$it" to "$it" } + ("31+" to "More than 30.")
        q["rows0"] = JevQuestion.Choice("How many rows (including the header row) does the 1st table in reading order on the page in `page` have?", counts)
        q["cols0"] = JevQuestion.Choice("How many columns does the 1st table in reading order on the page in `page` have?",
            (1..15).associate { "$it" to "$it" } + ("16+" to "More than 15."))
        return JevRequest(buildJsonObject { put("page", JsonPrimitive(img.asset.ref)); put("task", "Find the ruled tables on a photographed document page.") }, q, listOf(img.asset))
    }

    class Found(
        val boxes: List<JevBox>, val count: Int, val pAny: Double, val rows: Int, val cols: Int,
        /** Por caja: ¿líneas impresas entre filas / entre columnas? (noul >= 0,5). */
        val hLines: List<Boolean> = boxes.map { true }, val vLines: List<Boolean> = boxes.map { true },
    ) {
        /** x0, y0, x1, y1, filas rayadas (0/1), columnas rayadas (0/1): el formato de `tableHints`. */
        fun hints(): List<FloatArray> = boxes.indices.map { i -> boxes[i].toArray() + floatArrayOf(if (hLines[i]) 1f else 0f, if (vLines[i]) 1f else 0f) }
    }

    fun parse(r: JevResponse, fit: JevFit, minAny: Double = 0.5): Found {
        val pAny = r["tany"]?.noul ?: 0.0
        val n = when (val c = r["ntab"]?.choice) { null -> 0; "3+" -> MAX_TABLES; else -> c.toIntOrNull() ?: 0 }
        val rows = r["rows0"]?.choice?.trimEnd('+')?.toIntOrNull() ?: 0
        val cols = r["cols0"]?.choice?.trimEnd('+')?.toIntOrNull() ?: 0
        if (n == 0 || pAny < minAny) return Found(emptyList(), n, pAny, rows, cols)
        val out = ArrayList<JevBox>(); val hl = ArrayList<Boolean>(); val vl = ArrayList<Boolean>()
        for (i in 0 until MAX_TABLES) {
            if (out.size >= n) break
            val b0 = fit.toSource(r["t$i"]?.box ?: continue)
            if (b0.width < 0.05 || b0.height < 0.02) continue
            val b = JevBox((b0.left - PAD).coerceAtLeast(0.0), (b0.top - PAD).coerceAtLeast(0.0), (b0.right + PAD).coerceAtMost(1.0), (b0.bottom + PAD).coerceAtMost(1.0))
            if (out.any { it.iou(b) > 0.5 }) continue
            out += b; hl += (r["h$i"]?.noul ?: 1.0) >= 0.5; vl += (r["v$i"]?.noul ?: 1.0) >= 0.5
        }
        return Found(out, n, pAny, rows, cols, hl, vl)
    }
}
