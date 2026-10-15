package com.scannerpromax.ai.cloud

import com.scannerpromax.ai.AiLayout
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.LayoutLine
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.ocr.TextAligner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Diseño HÍBRIDO: TEXTO de la nube + POSICIONES locales. Los modelos en la nube transcriben muy bien (texto,
 * manuscrito, tablas) pero sus cajas son aproximadas (banco recon2/haiku: recall de bloques 0,40, cobertura de tinta
 * 0,74), así que sus bbox solo sirven de PISTA:
 *
 *  1. Cada línea de ML Kit (posición fiable) se asigna al bloque de la nube con el que más coincide por solape con la
 *     caja del modelo (ampliada) + parecido de texto (palabras en común); si ninguna caja la cubre, por texto solo.
 *  2. Con las parejas línea ML Kit ↔ línea del modelo se ajusta una corrección lineal por eje (escala + desplazamiento,
 *     robusta, acotada) de las cajas del modelo, y se repite la asignación con margen estrecho.
 *  3. Dentro de cada bloque, el texto EXACTO de la nube se reparte sobre las líneas de ML Kit ([TextAligner], LCS de
 *     palabras): cajas por línea y palabra fiables para el PDF buscable. Las líneas del modelo que ML Kit no vio
 *     (p. ej. manuscrito tenue) se quedan con la caja del modelo corregida.
 *  4. Tablas: celdas de la nube; posición = caja corregida ∪ líneas ML Kit de dentro (o la región local si la hay).
 *  5. Líneas de ML Kit que la nube omitió y no caen en ningún bloque: bloque de texto propio (no se pierde texto).
 *
 * PUNTO DE EXTENSIÓN: [LocalRegion] = regiones de la maquetación local (PP-DocLayoutV3, otro agente). Si llegan, las
 * tablas/figuras/gráficos/fórmulas toman la caja local y las figuras locales que la nube no nombró se añaden.
 *
 * Sin ML Kit: cajas del modelo tal cual (aviso de posiciones aproximadas). Lógica pura (JVM).
 */
object CloudAlign {

    /** Región de la maquetación local (detector en el dispositivo) en píxeles de la imagen procesada. */
    data class LocalRegion(val type: BlockType, val box: OcrRect, val score: Float = 1f)

    class Result(
        val ocr: OcrResult,
        val layout: DocLayout,
        /** Fracción de letras de ML Kit asignadas a bloques de la nube (diagnóstico de la alineación). */
        val mlMatched: Float,
        val warning: String?,
    )

    private val TEXTUAL = setOf(BlockType.TITLE, BlockType.TEXT, BlockType.HANDWRITING, BlockType.HEADER, BlockType.FOOTER)

    private class Ml(val line: OcrLine, val tokens: List<String>, val group: Int) {
        var owner = -1
        val letters = line.text.count { it.isLetterOrDigit() }
    }

    private class Blk(val src: CloudBlock, var box: OcrRect?, var lineBoxes: List<OcrRect?>) {
        val tokens: Set<String> = tokensOf(src.plainText() + "\n" + (src.chartTitle ?: "")).toSet()
        val lineTokens: List<List<String>> = src.lines.map { tokensOf(it.text) }
    }

    /**
     * @param w,h tamaño de la imagen procesada completa (las coordenadas de salida van en esos píxeles).
     * @param mlkit OCR básico de la misma página (cualquier escala; se reescala a w x h) o null.
     */
    fun align(
        page: CloudPage,
        w: Int,
        h: Int,
        mlkit: OcrResult?,
        docType: String?,
        engine: String,
        local: List<LocalRegion> = emptyList(),
    ): Result {
        val fx = w / 1000f
        val fy = h / 1000f
        fun px(b: FloatArray?) = b?.let { OcrRect(it[0] * fx, it[1] * fy, it[2] * fx, it[3] * fy) }
        val blocks = page.blocks.map { b -> Blk(b, px(b.bbox), b.lines.map { px(it.bbox) }) }
        val ml = mlkit?.let { scale(it, w, h) }?.blocks?.flatMapIndexed { gi, b ->
            b.lines.filter { it.text.isNotBlank() }.map { Ml(it, tokensOf(it.text), gi) }
        }.orEmpty()

        if (ml.isNotEmpty()) {
            val diag = max(w, h).toFloat()
            assign(blocks, ml, margin = 0.06f * diag)
            val t = calibrate(blocks, ml, w, h)
            if (t != null) {
                blocks.forEach { b -> b.box = b.box?.let(t); b.lineBoxes = b.lineBoxes.map { it?.let(t) } }
                ml.forEach { it.owner = -1 }
                assign(blocks, ml, margin = 0.025f * diag)
            }
        }

        // ---- bloques de salida en el orden de lectura de la nube
        data class Out(val layout: LayoutBlock, val ocrLines: List<OcrLine>, val text: String)
        val outs = ArrayList<Out>()
        for ((bi, b) in blocks.withIndex()) {
            val mine = ml.filter { it.owner == bi }.sortedWith(compareBy({ it.line.box.top }, { it.line.box.left }))
            val src = b.src
            val color = src.color
            when {
                src.type == BlockType.TABLE && src.cells.isNotEmpty() -> {
                    val rows = src.tableRowsText()
                    val lines = if (mine.isNotEmpty()) realign(mine, rows.joinToString("\n") { r -> r.filter { it.isNotBlank() }.joinToString(" ") })
                    else syntheticRows(rows, b.box)
                    val box = union(b.box, envelopeOrNull(lines.map { it.box })) ?: continue
                    outs += Out(
                        LayoutBlock(
                            type = BlockType.TABLE, box = lbox(box), text = rows.joinToString("\n") { it.joinToString("\t") },
                            lines = lines.map { layoutLine(it, color) }, tableRows = src.tableRows, tableCols = src.tableCols,
                            cells = src.cells, style = src.style,
                        ),
                        lines, src.plainText(),
                    )
                }
                src.lines.isNotEmpty() && (src.type in TEXTUAL || src.type == BlockType.FORMULA || src.type == BlockType.FIGURE || src.type == BlockType.TABLE || src.type == BlockType.CHART) -> {
                    val lines = textLines(b, mine)
                    if (lines.isEmpty()) continue
                    val box = envelopeOrNull(lines.map { it.box })!!.let { env ->
                        if (src.type in TEXTUAL) env else union(env, b.box)!!
                    }
                    outs += Out(
                        LayoutBlock(
                            type = src.type, box = lbox(box), text = lines.joinToString("\n") { it.text },
                            lines = lines.map { layoutLine(it, color) }, chartData = src.chart, latex = src.latex, style = src.style,
                        ),
                        lines, lines.joinToString("\n") { it.text },
                    )
                }
                else -> {
                    // Figura / gráfico / fórmula sin líneas: caja (corregida) ∪ etiquetas de ML Kit de dentro.
                    val lines = mine.map { it.line }
                    val box = union(b.box, envelopeOrNull(lines.map { it.box })) ?: continue
                    val text = when (src.type) {
                        BlockType.FORMULA -> src.latex.orEmpty()
                        BlockType.CHART -> src.plainText()
                        else -> lines.joinToString("\n") { it.text }
                    }
                    outs += Out(
                        LayoutBlock(
                            type = src.type, box = lbox(box), text = text, lines = lines.map { layoutLine(it, color) },
                            chartData = src.chart, latex = src.latex, style = src.style,
                        ),
                        lines, text,
                    )
                }
            }
        }

        // ---- punto de extensión: maquetación local (PP-DocLayoutV3)
        val withLocal = if (local.isEmpty()) outs.map { it.layout } else applyLocal(outs.map { it.layout }, local)
        val finalOuts = outs.mapIndexed { i, o -> o.copy(layout = withLocal[i]) }.toMutableList()
        val extraLocal = withLocal.drop(outs.size)

        // ---- líneas de ML Kit que la nube no cubrió
        val stray = ml.filter { it.owner < 0 && it.letters >= 3 }
            .filter { m -> finalOuts.none { o -> insideFraction(m.line.box, expand(rect(o.layout.box), 0.01f * max(w, h))) > 0.5f } }
        for ((_, group) in stray.groupBy { it.group }) {
            val lines = group.map { it.line }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
            val box = AiLayout.envelope(lines.map { it.box })
            val lb = LayoutBlock(type = BlockType.TEXT, box = lbox(box), text = lines.joinToString("\n") { it.text }, lines = lines.map { layoutLine(it, null) })
            val at = finalOuts.indexOfLast { it.layout.box.top <= box.top } + 1
            finalOuts.add(at, Out(lb, lines, lb.text))
        }
        val allLayout = finalOuts.map { it.layout } + extraLocal
        val ordered = allLayout.mapIndexed { i, b -> b.copy(order = i) }

        val ocrBlocks = finalOuts.filter { it.ocrLines.isNotEmpty() }.map { o ->
            OcrBlock(o.ocrLines.joinToString("\n") { it.text }, AiLayout.envelope(o.ocrLines.map { it.box }), o.ocrLines)
        }
        val text = finalOuts.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        val ocr = OcrResult(text = text, imageWidth = w, imageHeight = h, blocks = ocrBlocks)
        val totalLetters = ml.sumOf { it.letters }.coerceAtLeast(1)
        val matched = ml.filter { it.owner >= 0 }.sumOf { it.letters }.toFloat() / totalLetters
        val warning = when {
            page.repaired -> "La respuesta de la IA se cortó; puede faltar el final de la página"
            ml.isEmpty() -> "Posiciones aproximadas (no hubo OCR básico para situar el texto)"
            else -> null
        }
        return Result(ocr, DocLayout(w, h, ordered, docType, engine), if (ml.isEmpty()) 0f else matched, warning)
    }

    // ------------------------------------------------------------------------------------------ asignación

    /** Asigna cada línea de ML Kit al mejor bloque (solape con la caja ampliada + palabras en común; si no, solo texto). */
    private fun assign(blocks: List<Blk>, ml: List<Ml>, margin: Float) {
        for (m in ml) {
            var best = -1
            var bestScore = 0f
            for ((bi, b) in blocks.withIndex()) {
                val box = b.box ?: continue
                val g = insideFraction(m.line.box, expand(box, margin))
                if (g < 0.5f) continue
                val sim = similarity(m.tokens, b.tokens)
                val inside = if (contains(box, cx(m.line.box), cy(m.line.box))) 0.5f else 0f
                // Bloques sin texto (figuras) solo se quedan con lo que no encaja en ningún bloque de texto.
                val textless = if (b.tokens.isEmpty()) -0.6f else 0f
                val score = 2f * sim + g + inside + textless
                if (score > bestScore) { bestScore = score; best = bi }
            }
            if (best < 0 && m.letters >= 4) {
                // Caja del modelo muy desviada (o ausente): solo por texto, si el parecido es claro y único.
                val sims = blocks.map { similarity(m.tokens, it.tokens) }
                val top = sims.indices.maxByOrNull { sims[it] }
                if (top != null && sims[top] >= 0.75f && sims.withIndex().none { (i, s) -> i != top && s > sims[top] - 0.2f }) best = top
            }
            m.owner = best
        }
    }

    /**
     * Corrección lineal por eje de las cajas del modelo (x' = ax·x + bx; y' = ay·y + by) a partir de las parejas línea
     * ML Kit ↔ línea del modelo con texto parecido. Mínimos cuadrados, se descartan atípicos y se vuelve a ajustar.
     * null si hay pocas parejas o sale una corrección inverosímil.
     */
    private fun calibrate(blocks: List<Blk>, ml: List<Ml>, w: Int, h: Int): ((OcrRect) -> OcrRect)? {
        val xs = ArrayList<Pair<Float, Float>>()
        val ys = ArrayList<Pair<Float, Float>>()
        for (m in ml) {
            val b = blocks.getOrNull(m.owner) ?: continue
            var bestI = -1; var bestS = 0f
            b.lineTokens.forEachIndexed { i, toks -> val s = dice(m.tokens, toks); if (s > bestS) { bestS = s; bestI = i } }
            val mb = b.lineBoxes.getOrNull(bestI) ?: continue
            if (bestS < 0.6f) continue
            val r = m.line.box
            xs += mb.left to r.left; xs += mb.right to r.right
            ys += mb.top to r.top; ys += mb.bottom to r.bottom
        }
        if (xs.size < 8) return null
        val fxFit = robustFit(xs, w) ?: return null
        val fyFit = robustFit(ys, h) ?: return null
        val (ax, bx) = fxFit
        val (ay, by) = fyFit
        return { r -> OcrRect(ax * r.left + bx, ay * r.top + by, ax * r.right + bx, ay * r.bottom + by) }
    }

    internal fun robustFit(pts: List<Pair<Float, Float>>, size: Int): Pair<Float, Float>? {
        fun fit(p: List<Pair<Float, Float>>): Pair<Float, Float>? {
            val n = p.size.toFloat()
            val mx = p.sumOf { it.first.toDouble() } / n
            val my = p.sumOf { it.second.toDouble() } / n
            val sxx = p.sumOf { (it.first - mx) * (it.first - mx) }
            if (sxx < (size * 0.05) * (size * 0.05) * n) return null   // puntos sin dispersión: sin escala fiable
            val sxy = p.sumOf { (it.first - mx) * (it.second - my) }
            val a = (sxy / sxx).toFloat()
            return a to (my - a * mx).toFloat()
        }
        var f = fit(pts) ?: return null
        val res = pts.map { abs(f.first * it.first + f.second - it.second) }
        val med = res.sorted()[res.size / 2]
        val keep = pts.filterIndexed { i, _ -> res[i] <= max(2.5f * med, 0.004f * size) }
        if (keep.size >= 6) f = fit(keep) ?: f
        val (a, b) = f
        if (a !in 0.8f..1.25f || abs(b) > 0.12f * size) return null
        // Cambio despreciable: no merece una segunda pasada.
        if (abs(a - 1f) < 0.004f && abs(b) < 0.002f * size) return null
        return f
    }

    // ------------------------------------------------------------------------------------------ texto

    /**
     * Líneas de un bloque de texto: el texto EXACTO del modelo sobre las líneas de ML Kit asignadas; las líneas del
     * modelo sin ninguna palabra en ML Kit (huérfanas) conservan la caja del modelo (corregida).
     */
    private fun textLines(b: Blk, mine: List<Ml>): List<OcrLine> {
        val src = b.src
        if (mine.isEmpty()) return modelLines(b, src.lines.indices.toList())
        val mlWords = mine.map { m -> m.line.words.map { it.text }.ifEmpty { TextAligner.tokenize(m.line.text) } }
        val mlNorm = mlWords.flatten().map { TextAligner.normalize(it) }
        val modelWords = ArrayList<String>(); val modelLineOf = ArrayList<Int>()
        src.lines.forEachIndexed { li, l -> TextAligner.tokenize(l.text).forEach { wd -> modelWords += TextAligner.normalize(wd); modelLineOf += li } }
        val matched = BooleanArray(src.lines.size)
        if (modelWords.size.toLong() * mlNorm.size <= 2_000_000L) {
            TextAligner.lcs(modelWords, mlNorm).forEach { (mi, _) -> matched[modelLineOf[mi]] = true }
        } else matched.fill(true)
        val orphans = src.lines.indices.filter { i ->
            !matched[i] && src.lines[i].text.any { it.isLetterOrDigit() } &&
                // Una línea huérfana cuya caja cae encima de una línea de ML Kit es una mala lectura de ML Kit, no algo que
                // ML Kit no vio: su texto va a esa línea.
                b.lineBoxes.getOrNull(i)?.let { box -> mine.none { m -> overlap(box, m.line.box) > 0.5f } } ?: false
        }.toSet()
        val text = src.lines.indices.filter { it !in orphans }.joinToString("\n") { src.lines[it].text }
        val placed = if (text.isBlank()) emptyList() else realign(mine, text)
        return (placed + modelLines(b, orphans.toList())).sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /** Reparte [text] sobre las líneas de ML Kit [mine] (en orden): cajas de ML Kit, palabras reutilizadas si cuadran. */
    private fun realign(mine: List<Ml>, text: String): List<OcrLine> {
        val words = mine.map { m -> m.line.words.map { it.text }.ifEmpty { TextAligner.tokenize(m.line.text) } }
        val aligned = TextAligner.align(words, text)
        return mine.mapIndexedNotNull { i, m ->
            val ws = aligned.getOrNull(i).orEmpty()
            if (ws.isEmpty()) return@mapIndexedNotNull null
            val t = ws.joinToString(" ")
            val l = m.line
            val newWords = if (l.words.size == ws.size) l.words.mapIndexed { k, wd -> wd.copy(text = ws[k], confidence = -1f) }
            else AiLayout.wordsAlong(t, null, l.box)
            l.copy(text = t, words = newWords, confidence = -1f)
        }
    }

    /** Líneas con la caja del modelo (corregida); sin caja de línea: franjas iguales dentro de la caja del bloque. */
    private fun modelLines(b: Blk, idx: List<Int>): List<OcrLine> {
        val src = b.src
        val n = src.lines.size.coerceAtLeast(1)
        return idx.sorted().mapNotNull { i ->
            val t = src.lines[i].text.trim()
            if (t.isEmpty()) return@mapNotNull null
            val box = b.lineBoxes.getOrNull(i) ?: b.box?.let { bb ->
                val hh = (bb.bottom - bb.top) / n
                OcrRect(bb.left, bb.top + i * hh, bb.right, bb.top + (i + 1) * hh)
            } ?: return@mapNotNull null
            OcrLine(text = t, box = box, words = AiLayout.wordsAlong(t, null, box), confidence = -1f)
        }
    }

    /** Tabla sin ML Kit: una línea por fila (franjas iguales) para que el texto sea buscable. */
    private fun syntheticRows(rows: List<List<String>>, box: OcrRect?): List<OcrLine> {
        if (box == null || rows.isEmpty()) return emptyList()
        val hh = (box.bottom - box.top) / rows.size
        return rows.mapIndexedNotNull { i, r ->
            val t = r.filter { it.isNotBlank() }.joinToString(" ")
            if (t.isEmpty()) null else OcrRect(box.left, box.top + i * hh, box.right, box.top + (i + 1) * hh).let { lb ->
                OcrLine(text = t, box = lb, words = AiLayout.wordsAlong(t, null, lb), confidence = -1f)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ maquetación local

    private fun kind(t: BlockType) = when (t) {
        BlockType.TABLE -> 1
        BlockType.FIGURE, BlockType.CHART -> 2
        BlockType.FORMULA -> 3
        else -> 0
    }

    /**
     * Regiones locales: tablas/figuras/gráficos/fórmulas de la nube toman la caja local del mismo tipo que más se solapa
     * (IoU >= 0,3); las figuras locales que la nube no nombró se añaden al final (devueltas tras los bloques).
     */
    internal fun applyLocal(blocks: List<LayoutBlock>, local: List<LocalRegion>): List<LayoutBlock> {
        val used = BooleanArray(local.size)
        val out = blocks.map { b ->
            val k = kind(b.type)
            if (k == 0) return@map b
            val r = rect(b.box)
            val best = local.indices.filter { !used[it] && kind(local[it].type) == k }.maxByOrNull { iou(r, local[it].box) }
            if (best == null || iou(r, local[best].box) < 0.3f) return@map b
            used[best] = true
            val nb = union(local[best].box, envelopeOrNull(b.lines.map { rect(it.box) }))!!
            b.copy(box = lbox(nb))
        }
        val extra = local.indices.filter { !used[it] && local[it].type == BlockType.FIGURE }
            .filter { i -> out.none { o -> insideFraction(local[i].box, rect(o.box)) > 0.3f } }
            .map { LayoutBlock(type = BlockType.FIGURE, box = lbox(local[it].box)) }
        return out + extra
    }

    // ------------------------------------------------------------------------------------------ utilidades

    internal fun tokensOf(s: String): List<String> =
        TextAligner.tokenize(s).map { TextAligner.normalize(it) }.filter { t -> t.any { it.isLetterOrDigit() } }

    /** Fracción de palabras de [line] presentes en el bloque. */
    internal fun similarity(line: List<String>, block: Set<String>): Float =
        if (line.isEmpty() || block.isEmpty()) 0f else line.count { it in block }.toFloat() / line.size

    /** Dice sobre bolsas de palabras. */
    internal fun dice(a: List<String>, b: List<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val cb = b.groupingBy { it }.eachCount().toMutableMap()
        var common = 0
        for (t in a) { val c = cb[t] ?: 0; if (c > 0) { common++; cb[t] = c - 1 } }
        return 2f * common / (a.size + b.size)
    }

    private fun scale(o: OcrResult, w: Int, h: Int): OcrResult {
        if (o.imageWidth == w && o.imageHeight == h || o.imageWidth <= 0 || o.imageHeight <= 0) return o
        val sx = w.toFloat() / o.imageWidth; val sy = h.toFloat() / o.imageHeight
        fun r(b: OcrRect) = OcrRect(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)
        return o.copy(imageWidth = w, imageHeight = h, blocks = o.blocks.map { b ->
            OcrBlock(b.text, r(b.box), b.lines.map { l -> l.copy(box = r(l.box), words = l.words.map { OcrWord(it.text, r(it.box), it.confidence) }) })
        })
    }

    private fun layoutLine(l: OcrLine, color: Int?) = LayoutLine(text = l.text, box = lbox(l.box), color = color)
    private fun lbox(r: OcrRect) = LayoutBox(r.left, r.top, r.right, r.bottom)
    private fun rect(b: LayoutBox) = OcrRect(b.left, b.top, b.right, b.bottom)
    private fun cx(r: OcrRect) = (r.left + r.right) / 2
    private fun cy(r: OcrRect) = (r.top + r.bottom) / 2
    private fun contains(r: OcrRect, x: Float, y: Float) = x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    private fun expand(r: OcrRect, m: Float) = OcrRect(r.left - m, r.top - m, r.right + m, r.bottom + m)
    private fun envelopeOrNull(r: List<OcrRect>) = if (r.isEmpty()) null else AiLayout.envelope(r)
    private fun union(a: OcrRect?, b: OcrRect?): OcrRect? = when {
        a == null -> b
        b == null -> a
        else -> OcrRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    }

    private fun interArea(a: OcrRect, b: OcrRect): Float =
        max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))

    private fun area(a: OcrRect) = max(1e-3f, (a.right - a.left) * (a.bottom - a.top))

    /** Fracción del área de [a] dentro de [b]. */
    internal fun insideFraction(a: OcrRect, b: OcrRect): Float = interArea(a, b) / area(a)

    /** Solape relativo a la menor de las dos cajas. */
    private fun overlap(a: OcrRect, b: OcrRect): Float = interArea(a, b) / min(area(a), area(b))

    private fun iou(a: OcrRect, b: OcrRect): Float { val i = interArea(a, b); return i / (area(a) + area(b) - i) }
}
