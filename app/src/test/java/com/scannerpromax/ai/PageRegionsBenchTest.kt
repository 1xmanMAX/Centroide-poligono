package com.scannerpromax.ai

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * MEDICIÓN (no regresión) del detector clásico de tablas/figuras sobre páginas del banco convertidas a gris/croma
 * crudos (`AI_REGIONS_RAW` o el scratchpad del agente; ver notes_ia_fase3a.md). Se salta si no existen. Usa las cajas
 * de texto de la referencia DocLayNet como si fueran el Spotting.
 */
class PageRegionsBenchTest {

    private val dir = File(
        System.getenv("AI_REGIONS_RAW")
            ?: "/tmp/claude-0/-home-user-Centroide-poligono/fd1fa83a-ce0b-5e57-90bd-06b9887dce8c/scratchpad/raw",
    )

    @Test fun regionsOnBenchPages() {
        assumeTrue(dir.isDirectory)
        var tGt = 0; var tHit = 0; var tDet = 0; var tOk = 0
        var fGt = 0; var fHit = 0; var fDet = 0; var fOk = 0
        val re = Regex("(.+)\\.(\\d+)x(\\d+)\\.g")
        for (gf in dir.listFiles()!!.filter { it.name.endsWith(".g") }.sortedBy { it.name }) {
            val m = re.matchEntire(gf.name) ?: continue
            val name = m.groupValues[1]; val w = m.groupValues[2].toInt(); val h = m.groupValues[3].toInt()
            val pr = PageRegions(w, h, gf.readBytes(), File(dir, "$name.${w}x$h.c").readBytes())
            val t0 = System.nanoTime()
            val ruled = pr.ruledRegions()
            val gtFile = File(dir, "$name.gt")
            val gt = if (gtFile.exists()) gtFile.readLines().filter { it.isNotBlank() }.map { l ->
                val p = l.split('\t'); p[0] to floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
            } else emptyList()
            val cell = max(8, max(w, h) / 48)
            val (content, gw, gh) = pr.contentGrid(cell)
            val text = BooleanArray(gw * gh)
            for ((cat, b) in gt) {
                if (cat == "Table" || cat == "Picture" || cat == "Formula") continue
                val x0 = (b[0] * w / cell).toInt().coerceIn(0, gw - 1); val x1 = (b[2] * w / cell).toInt().coerceIn(0, gw - 1)
                val y0 = (b[1] * h / cell).toInt().coerceIn(0, gh - 1); val y1 = (b[3] * h / cell).toInt().coerceIn(0, gh - 1)
                for (y in y0..y1) for (x in x0..x1) text[y * gw + x] = true
            }
            val taken = ruled.map { AiLayout.IRect(it.rect.x0 / cell, it.rect.y0 / cell, (it.rect.x1 + cell - 1) / cell, (it.rect.y1 + cell - 1) / cell) }
            val figs = AiLayout.figureRegions(content, text, gw, gh, taken)
                .map { floatArrayOf(it.x0 * cell / w.toFloat(), it.y0 * cell / h.toFloat(), min(1f, it.x1 * cell / w.toFloat()), min(1f, it.y1 * cell / h.toFloat())) }
            val ms = (System.nanoTime() - t0) / 1e6
            val tables = ruled.filter { it.kind == PageRegions.Ruled.Kind.TABLE }
            val tb = tables.map { floatArrayOf(it.rect.x0 / w.toFloat(), it.rect.y0 / h.toFloat(), it.rect.x1 / w.toFloat(), it.rect.y1 / h.toFloat()) }
            println("$name: tablas=${tables.map { "${it.rows.size}x${it.cols.size}" }} ejes=${ruled.count { it.kind == PageRegions.Ruled.Kind.CHART_AXES }} figuras=${figs.size} | gt tablas=${gt.count { it.first == "Table" }} figuras=${gt.count { it.first == "Picture" }} | %.0f ms".format(ms))
            if (!gtFile.exists()) continue
            val gtT = gt.filter { it.first == "Table" }.map { it.second }
            val gtF = gt.filter { it.first == "Picture" }.map { it.second }
            tGt += gtT.size; tDet += tb.size; fGt += gtF.size; fDet += figs.size
            tHit += gtT.count { g -> tb.any { iou(g, it) >= 0.5f } }
            tOk += tb.count { d -> gtT.any { iou(it, d) >= 0.5f } }
            fHit += gtF.count { g -> figs.any { iou(g, it) >= 0.5f } }
            fOk += figs.count { d -> gtF.any { iou(it, d) >= 0.5f } }
        }
        println("TABLAS DocLayNet: recall $tHit/$tGt, precisión $tOk/$tDet")
        println("FIGURAS DocLayNet: recall $fHit/$fGt, precisión $fOk/$fDet")
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0])); val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val i = ix * iy; val u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i
        return if (u > 0) i / u else 0f
    }
}
