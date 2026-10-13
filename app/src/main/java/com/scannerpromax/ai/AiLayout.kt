package com.scannerpromax.ai

import com.scannerpromax.ai.parse.IaParse
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
import com.scannerpromax.domain.TableCell
import com.scannerpromax.ocr.ReadingOrder
import com.scannerpromax.ocr.TextAligner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lógica pura (JVM, sin Android) que convierte las salidas de la IA en los contratos de la app:
 *  - Spotting de Paddle -> [OcrResult] con bloques/líneas/palabras en píxeles (capa de texto del PDF buscable y
 *    resaltado actuales) + bloques de texto para [DocLayout];
 *  - texto sin posiciones (Paddle "OCR:", Qwen) -> alineado con las líneas de ML Kit ([TextAligner]);
 *  - regiones (tablas, figuras, gráficos, fórmulas) -> [LayoutBlock] y montaje del [DocLayout] en orden de lectura.
 */
object AiLayout {

    /** Bloque de texto con sus líneas (px) antes de pasar a [LayoutBlock]. */
    class TextBlockPx(val box: OcrRect, val lines: List<OcrLine>, val quads: List<List<FloatArray>?>, val lineH: Float)

    /** Resultado de pasar un Spotting a la app. */
    class SpotPage(val ocr: OcrResult, val blocks: List<TextBlockPx>, val items: List<IaParse.SpotItem>)

    // ------------------------------------------------------------------------------------------ Spotting -> OCR

    /**
     * Fragmentos de Spotting (0..1000) -> [OcrResult] en píxeles de una imagen [w] x [h]. Las palabras se reparten a lo
     * largo del cuadrilátero de la línea en proporción a sus caracteres (Paddle no da cajas por palabra).
     */
    fun spotToPage(items0: List<IaParse.SpotItem>, w: Int, h: Int): SpotPage {
        val items = IaParse.filterSpotLoops(items0).filter { it.box != null && it.text.isNotBlank() }
        val blocks = IaParse.spottingToBlocks(items)
        val out = ArrayList<TextBlockPx>()
        for (b in blocks) {
            val lines = ArrayList<OcrLine>()
            val quads = ArrayList<List<FloatArray>?>()
            for (it in b.items) {
                val q = it.quadPx(w, h)
                val line = lineFrom(it.text.replace('\n', ' ').trim(), q, it.box!!, w, h) ?: continue
                lines.add(line); quads.add(q?.takeIf { p -> p.size == 4 })
            }
            if (lines.isEmpty()) continue
            out.add(TextBlockPx(envelope(lines.map { it.box }), lines, quads, b.lineH * h / 1000f))
        }
        val ocrBlocks = out.map { OcrBlock(it.lines.joinToString("\n") { l -> l.text }, it.box, it.lines) }
        val text = ocrBlocks.joinToString("\n\n") { it.text }
        return SpotPage(OcrResult(text = text, imageWidth = w, imageHeight = h, blocks = ocrBlocks), out, items)
    }

    private fun lineFrom(text: String, q: List<FloatArray>?, box01: FloatArray, w: Int, h: Int): OcrLine? {
        if (text.isEmpty()) return null
        val box = OcrRect(
            (box01[0] * w / 1000f).coerceIn(0f, w.toFloat()), (box01[1] * h / 1000f).coerceIn(0f, h.toFloat()),
            (box01[2] * w / 1000f).coerceIn(0f, w.toFloat()), (box01[3] * h / 1000f).coerceIn(0f, h.toFloat()),
        )
        if (box.right - box.left < 1f || box.bottom - box.top < 1f) return null
        val quad = q?.takeIf { it.size == 4 }
        val angle = quad?.let { IaParse.quadAngle(it) }?.takeIf { abs(it) < 45f } ?: 0f
        return OcrLine(text = text, box = box, words = wordsAlong(text, quad, box), angle = angle)
    }

    /** Reparte las palabras de [text] a lo largo del cuadrilátero (o de la caja) en proporción a sus caracteres. */
    fun wordsAlong(text: String, quad: List<FloatArray>?, box: OcrRect): List<OcrWord> {
        val words = TextAligner.tokenize(text)
        if (words.isEmpty()) return emptyList()
        val total = text.trim().length.coerceAtLeast(1).toFloat()
        val tl = quad?.get(0) ?: floatArrayOf(box.left, box.top)
        val tr = quad?.get(1) ?: floatArrayOf(box.right, box.top)
        val br = quad?.get(2) ?: floatArrayOf(box.right, box.bottom)
        val bl = quad?.get(3) ?: floatArrayOf(box.left, box.bottom)
        fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        val out = ArrayList<OcrWord>(words.size)
        var pos = 0
        val trimmed = text.trim()
        for (wd in words) {
            val start = trimmed.indexOf(wd, pos).let { if (it < 0) pos else it }
            val end = start + wd.length
            pos = end
            val t0 = start / total
            val t1 = end / total
            val pts = listOf(lerp(tl, tr, t0), lerp(tl, tr, t1), lerp(bl, br, t1), lerp(bl, br, t0))
            out.add(OcrWord(wd, OcrRect(pts.minOf { it[0] }, pts.minOf { it[1] }, pts.maxOf { it[0] }, pts.maxOf { it[1] })))
        }
        return out
    }

    fun envelope(r: List<OcrRect>): OcrRect =
        OcrRect(r.minOf { it.left }, r.minOf { it.top }, r.maxOf { it.right }, r.maxOf { it.bottom })

    // ------------------------------------------------------------------------------------------ texto sin posiciones

    /**
     * Texto reconocido SIN posiciones (Paddle "OCR:", Qwen) sobre las líneas de [base] (ML Kit): cada línea conserva
     * su caja y recibe las palabras alineadas ([TextAligner]); las líneas que se quedan vacías se quitan. El texto
     * completo de la página es [text] tal cual (también lo que no se pudo colocar). null si no hay líneas base.
     */
    fun alignToLines(base: OcrResult?, text: String): OcrResult? {
        if (base == null || base.blocks.isEmpty() || text.isBlank()) return null
        val lines = base.blocks.flatMap { it.lines }
        val aligned = TextAligner.align(lines.map { l -> if (l.words.isNotEmpty()) l.words.map { it.text } else TextAligner.tokenize(l.text) }, text)
        var k = 0
        val blocks = base.blocks.mapNotNull { b ->
            val ls = b.lines.mapNotNull { l ->
                val ws = aligned.getOrNull(k++).orEmpty()
                if (ws.isEmpty()) null
                else {
                    val t = ws.joinToString(" ")
                    val words = if (l.words.size == ws.size) l.words.mapIndexed { i, w -> w.copy(text = ws[i], confidence = -1f) }
                    else wordsAlong(t, null, l.box)
                    l.copy(text = t, words = words, confidence = -1f)
                }
            }
            if (ls.isEmpty()) null else OcrBlock(ls.joinToString("\n") { it.text }, envelope(ls.map { it.box }), ls)
        }
        if (blocks.isEmpty()) return null
        return OcrResult(text = text.trim(), imageWidth = base.imageWidth, imageHeight = base.imageHeight, blocks = ReadingOrder.sort(blocks))
    }

    // ------------------------------------------------------------------------------------------ calidad

    /**
     * ¿La salida de la IA es basura frente a ML Kit? (casi vacía cuando ML Kit sí encontró texto, o con un bucle que se
     * comió la mayor parte de la página). [aiChars] / [mlkitChars] = letras y dígitos.
     */
    fun isGarbage(aiChars: Int, mlkitChars: Int, looped: Boolean): Boolean = when {
        aiChars < 3 && mlkitChars >= 20 -> true
        looped && mlkitChars >= 60 && aiChars < mlkitChars * 0.3 -> true
        else -> false
    }

    fun letters(s: String): Int = s.count { it.isLetterOrDigit() }

    // ------------------------------------------------------------------------------------------ tipos de bloque

    /**
     * Tipo de un bloque de texto: cabecera/pie si es pequeño y está en la franja superior/inferior del 6 %, título si
     * tiene 1–2 líneas con letra >= 1,45 veces la mediana de la página, manuscrito si la página se clasificó así.
     */
    fun textBlockType(b: TextBlockPx, pageH: Int, medianLineH: Float, handwritten: Boolean): BlockType {
        val small = b.lines.size <= 2
        return when {
            small && b.box.bottom <= pageH * 0.06f && b.lineH <= medianLineH * 1.3f -> BlockType.HEADER
            small && b.box.top >= pageH * 0.94f && b.lineH <= medianLineH * 1.3f -> BlockType.FOOTER
            small && medianLineH > 0 && b.lineH >= medianLineH * 1.45f -> BlockType.TITLE
            handwritten -> BlockType.HANDWRITING
            else -> BlockType.TEXT
        }
    }

    private val MATH_SYMBOLS = "=+−×÷±∑∫√∞≤≥≠≈∂∆∇πσμλθαβγ^_".toSet()
    private val TEX_CMD = Regex("\\\\(frac|sum|int|sqrt|alpha|beta|gamma|delta|theta|lambda|mu|pi|sigma|partial|cdot|times|leq|geq|neq|infty|left|right|mathrm|over|hat|bar|vec|lim)\\b")

    /**
     * ¿Parece una fórmula? LaTeX en el texto del Spotting (Paddle escribe `\( … \)` y comandos) o una línea corta con
     * mucha proporción de símbolos matemáticos y operadores (no frases).
     */
    fun looksLikeFormula(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3) return false
        if (TEX_CMD.containsMatchIn(t)) return true
        val words = t.split(Regex("\\s+")).count { w -> w.count { it.isLetter() } >= 4 }
        if (words >= 4) return false
        val sym = t.count { it in MATH_SYMBOLS }
        val hasEq = '=' in t
        return hasEq && sym >= 3 && sym.toFloat() / t.length >= 0.12f
    }

    // ------------------------------------------------------------------------------------------ figuras

    /** Rectángulo en coordenadas de una rejilla / imagen (enteros, [x1,y1) exclusivo). */
    data class IRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w get() = x1 - x0
        val h get() = y1 - y0
        val area get() = w.toLong() * h
        fun intersect(o: IRect): Long = max(0, min(x1, o.x1) - max(x0, o.x0)).toLong() * max(0, min(y1, o.y1) - max(y0, o.y0))
    }

    /**
     * Regiones grandes CON contenido y SIN texto (figuras, fotos, gráficos): [content] = celdas de una rejilla
     * [gw] x [gh] con tinta/color; [text] = celdas cubiertas por texto reconocido; [exclude] = rectángulos ya
     * asignados (tablas). Componentes 8-conexas tras unir huecos de 1 celda; se devuelven las de área >= [minArea]
     * (fracción de la página) y lados >= 8 % con < 25 % de su área ocupada por texto.
     */
    fun figureRegions(content: BooleanArray, text: BooleanArray, gw: Int, gh: Int, exclude: List<IRect>, minArea: Float = 0.025f): List<IRect> {
        val g = BooleanArray(gw * gh) { content[it] && !text[it] }
        for (r in exclude) for (y in max(0, r.y0) until min(gh, r.y1)) for (x in max(0, r.x0) until min(gw, r.x1)) g[y * gw + x] = false
        // cierre de 1 celda (dilatar) para unir trozos de la misma figura
        val d = BooleanArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            if (!g[y * gw + x]) continue
            for (yy in max(0, y - 1)..min(gh - 1, y + 1)) for (xx in max(0, x - 1)..min(gw - 1, x + 1)) d[yy * gw + xx] = true
        }
        val seen = BooleanArray(gw * gh)
        val out = ArrayList<IRect>()
        val stack = IntArray(gw * gh)
        for (start in 0 until gw * gh) {
            if (!d[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var x0 = gw; var y0 = gh; var x1 = 0; var y1 = 0; var n = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % gw; val y = p / gw
                if (g[p]) {
                    n++
                    // caja de las celdas reales (sin la dilatación)
                    x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x + 1); y1 = max(y1, y + 1)
                }
                for (yy in max(0, y - 1)..min(gh - 1, y + 1)) for (xx in max(0, x - 1)..min(gw - 1, x + 1)) {
                    val q = yy * gw + xx
                    if (d[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (n == 0) continue
            val r = IRect(x0, y0, x1, y1)
            if (r.area < minArea * gw * gh || r.w < 0.08f * gw || r.h < 0.08f * gh) continue
            if (n < 0.25f * r.area) continue  // contorno disperso (motas sueltas)
            var t = 0
            for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) if (text[y * gw + x]) t++
            if (t > 0.25f * r.area) continue
            out.add(r)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ tablas

    /** Bandas de columnas para una tabla de [nCols] columnas: <= [maxCols] por banda, la 1ª (clave) se repite. */
    fun columnBands(nCols: Int, maxCols: Int = 10): List<IntRange> {
        if (nCols <= maxCols + 2) return listOf(0 until nCols)
        val out = ArrayList<IntRange>()
        var c = 1
        val per = maxCols - 1
        out.add(0 until min(nCols, maxCols))
        c = maxCols
        while (c < nCols) {
            val end = min(nCols, c + per)
            out.add(c until end)
            c = end
        }
        return out
    }

    /**
     * Une tablas OTSL leídas por bandas de columnas: la primera banda entra entera; de las siguientes (que llevan la
     * columna clave repetida delante) se quita esa columna. Filas alineadas por índice (las que faltan, vacías).
     */
    fun mergeBands(bands: List<IaParse.OtslTable>): IaParse.OtslTable? {
        if (bands.isEmpty()) return null
        if (bands.size == 1) return bands[0]
        val nRows = bands.maxOf { it.nRows }
        val rows = List(nRows) { ArrayList<String>() }
        val cells = ArrayList<IaParse.OtslCell>()
        var colOff = 0
        for ((bi, t) in bands.withIndex()) {
            val skip = if (bi == 0) 0 else 1
            val nc = (t.nCols - skip).coerceAtLeast(0)
            for (r in 0 until nRows) {
                val src = t.rows.getOrNull(r)
                for (c in skip until skip + nc) rows[r].add(src?.getOrNull(c) ?: "")
            }
            for (cl in t.cells) {
                if (cl.c < skip) continue
                cells.add(IaParse.OtslCell(cl.r, cl.c - skip + colOff, cl.rowSpan, min(cl.colSpan, nc - (cl.c - skip)), cl.text))
            }
            colOff += nc
        }
        val nCols = colOff
        for (r in rows) while (r.size < nCols) r.add("")
        val html = StringBuilder("<table>")
        for (ri in 0 until nRows) {
            html.append("<tr>")
            for (cl in cells) if (cl.r == ri) html.append("<td>").append(com.scannerpromax.ai.parse.IaText.htmlEscape(cl.text)).append("</td>")
            html.append("</tr>")
        }
        html.append("</table>")
        return IaParse.OtslTable(rows, cells, html.toString(), nRows, nCols)
    }

    fun tableBlock(t: IaParse.OtslTable, box: LayoutBox, lines: List<LayoutLine>): LayoutBlock = LayoutBlock(
        type = BlockType.TABLE,
        box = box,
        text = t.rows.joinToString("\n") { r -> r.joinToString("\t") },
        lines = lines,
        tableRows = t.nRows,
        tableCols = t.nCols,
        cells = t.cells.map { TableCell(it.r, it.c, it.rowSpan, it.colSpan, it.text) },
    )

    // ------------------------------------------------------------------------------------------ montaje

    fun layoutLine(l: OcrLine, quad: List<FloatArray>?): LayoutLine = LayoutLine(
        text = l.text,
        box = LayoutBox(l.box.left, l.box.top, l.box.right, l.box.bottom),
        quad = quad?.flatMap { listOf(it[0], it[1]) } ?: emptyList(),
    )

    fun box(r: OcrRect) = LayoutBox(r.left, r.top, r.right, r.bottom)

    /** Fracción del área de [a] dentro de [b]. */
    fun insideFraction(a: LayoutBox, b: LayoutBox): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val area = max(1e-3f, a.width * a.height)
        return ix * iy / area
    }

    /**
     * Monta el [DocLayout]: los bloques de texto que caen (> 50 %) dentro de una tabla, figura o fórmula se quitan
     * (sus líneas pasan a esa región si es tabla/figura) y todo se ordena para la lectura.
     */
    fun assemble(
        w: Int,
        h: Int,
        text: List<TextBlockPx>,
        regions: List<LayoutBlock>,
        docType: String?,
        engine: String,
    ): DocLayout {
        val handwritten = docType == "manuscrito"
        val med = text.flatMap { b -> b.lines.map { it.box.bottom - it.box.top } }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val regs = regions.toMutableList()
        val textBlocks = ArrayList<LayoutBlock>()
        for (b in text) {
            // Cada LÍNEA que cae (> 50 %) dentro de una tabla/figura/gráfico pasa a esa región (la reconstrucción del
            // PDF usa las líneas de la tabla, con sus cajas, para alinear filas y columnas); las que caen en una
            // fórmula ya reconocida se quitan; el resto sigue como texto.
            val keep = ArrayList<Int>()
            for (i in b.lines.indices) {
                val lb = box(b.lines[i].box)
                val host = regs.indexOfFirst { insideFraction(lb, it.box) > 0.5f }
                if (host < 0) { keep.add(i); continue }
                val r = regs[host]
                if (r.type != BlockType.FORMULA) regs[host] = r.copy(lines = r.lines + layoutLine(b.lines[i], b.quads.getOrNull(i)))
            }
            if (keep.isEmpty()) continue
            val lines = keep.map { b.lines[it] }
            val sub = TextBlockPx(envelope(lines.map { it.box }), lines, keep.map { b.quads.getOrNull(it) }, b.lineH)
            textBlocks.add(
                LayoutBlock(
                    type = textBlockType(sub, h, med, handwritten),
                    box = box(sub.box),
                    text = lines.joinToString("\n") { it.text },
                    lines = lines.mapIndexed { i, l -> layoutLine(l, sub.quads.getOrNull(i)) },
                ),
            )
        }
        // Figuras/gráficos sin texto propio: su texto (etiquetas) queda en lines; tablas sin celdas: texto de sus líneas.
        for (i in regs.indices) {
            val r = regs[i]
            if (r.type == BlockType.TABLE && r.cells.isEmpty() && r.text.isEmpty() && r.lines.isNotEmpty()) {
                regs[i] = r.copy(text = r.lines.sortedWith(compareBy({ it.box.top }, { it.box.left })).joinToString("\n") { it.text })
            }
        }
        val all = textBlocks + regs
        val norm: (LayoutBlock) -> FloatArray = { b ->
            floatArrayOf(b.box.left * 1000f / w, b.box.top * 1000f / h, b.box.right * 1000f / w, b.box.bottom * 1000f / h)
        }
        val ordered = IaParse.readingOrder(all, norm).mapIndexed { i, b -> b.copy(order = i) }
        return DocLayout(imageWidth = w, imageHeight = h, blocks = ordered, docType = docType, engine = engine)
    }

    /** DocLayout mínimo a partir de un OCR sin maquetación (ML Kit o texto alineado): un bloque de texto por bloque. */
    fun fromOcr(ocr: OcrResult, docType: String?, engine: String): DocLayout {
        val text = ocr.blocks.filter { it.lines.isNotEmpty() }
            .map { b -> TextBlockPx(b.box, b.lines, b.lines.map { null }, b.lines.map { it.box.bottom - it.box.top }.sorted().let { it[it.size / 2] }) }
        return assemble(ocr.imageWidth, ocr.imageHeight, text, emptyList(), docType, engine)
    }
}
