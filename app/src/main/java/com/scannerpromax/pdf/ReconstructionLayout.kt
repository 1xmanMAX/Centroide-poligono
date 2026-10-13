package com.scannerpromax.pdf

import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.LayoutLine
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.TableCell
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Fuentes de la página reconstruida. */
enum class ReFont { REGULAR, BOLD, HAND }

/**
 * Métricas de las fuentes (en em: 1 = tamaño de letra). En la app salen de los TTF ([PdfFontMetrics]); en los tests,
 * de una fuente ficticia.
 */
interface ReFontMetrics {
    /** Ancho del texto a tamaño 1 (0 si no se puede medir). */
    fun widthEm(font: ReFont, text: String): Float
    fun capHeight(font: ReFont): Float
    fun xHeight(font: ReFont): Float
    /** Descendente positivo (p. ej. 0,21). */
    fun descent(font: ReFont): Float
    /** Texto limpio con solo caracteres que la fuente puede dibujar (sin controles; espacios normalizados). */
    fun clean(font: ReFont, text: String): String
}

/** Operaciones de dibujo de la página reconstruida (coordenadas PDF: puntos, origen abajo-izquierda). */
sealed class ReOp {
    /**
     * Texto con origen en su línea base ([x], [y]), girado ([cos], [sin]), tamaño [size], escala horizontal [hScale]
     * (Tz, en %) y espaciado entre caracteres [charSpacing] (Tc). [visible] = false → invisible pero buscable.
     */
    data class Text(
        val text: String,
        val font: ReFont,
        val size: Float,
        val hScale: Float,
        val charSpacing: Float,
        val x: Float,
        val y: Float,
        val cos: Float,
        val sin: Float,
        val rgb: Int,
        val visible: Boolean = true,
    ) : ReOp()

    /** Recorte [src] (px de la imagen del DocLayout) de la imagen procesada, dibujado en ([x], [y], [w], [h]). */
    data class Image(val src: PxRect, val x: Float, val y: Float, val w: Float, val h: Float, val kind: BlockType) : ReOp()

    /** Segmentos rectos (rejilla de tabla). */
    data class Lines(val segments: List<Seg>, val width: Float, val rgb: Int = 0x000000) : ReOp()
}

data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class Seg(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** Página reconstruida: tamaño y operaciones en orden de lectura. */
data class RePlan(val pageW: Float, val pageH: Float, val ops: List<ReOp>) {
    /** Texto (visible e invisible) en el orden en que se dibuja: para tests y depuración. */
    val allText: String get() = ops.filterIsInstance<ReOp.Text>().joinToString("\n") { it.text }
}

/**
 * Maquetación PURA (testeable en la JVM) de la página reconstruida a partir del [DocLayout]: misma disposición que el
 * escaneo, texto real.
 *
 *  - Página: [PageFit] (proporción de la imagen o el tamaño elegido, encajando sin recortar).
 *  - Línea de texto: en su cuadrilátero (o caja), con el tamaño deducido del alto de la línea (según tenga
 *    mayúsculas/ascendentes y descendentes), girada como el cuadrilátero y ajustada al ancho con escala horizontal
 *    acotada; si sobra espacio se reparte con espaciado entre letras y si falta se reduce la letra.
 *  - Fuente: negrita en TITLE o en líneas mucho más altas que el texto normal; manuscrita en HANDWRITING; la normal si
 *    la fuente elegida no puede dibujar algún carácter.
 *  - Tabla: rejilla real con celdas combinadas (spans); columnas/filas colocadas donde el texto de las celdas aparece en
 *    las líneas reconocidas dentro del bloque (si las hay) o repartidas según el contenido.
 *  - Gráfico, figura y fórmula: recorte de la imagen original (no se inventa nada) + sus datos / LaTeX como texto
 *    invisible buscable.
 */
object ReconstructionLayout {

    /** Relleno vertical (en em) que suelen dejar las cajas de línea del OCR por encima y por debajo de la tinta. */
    const val LINE_PAD_EM = 0.12f
    const val MIN_HSCALE = 0.75f
    const val MAX_HSCALE = 1.30f
    /** Una línea con el alto ≥ este factor × la mediana del texto normal se toma por título (negrita). */
    const val HEADING_FACTOR = 1.45f
    const val MIN_FONT_PT = 2f
    const val LEADING = 1.18f
    /** Por debajo de este giro (grados) la línea se pone recta (ruido del cuadrilátero). */
    const val MIN_ANGLE_DEG = 0.35f

    private const val BLACK = 0x000000

    fun plan(
        layout: DocLayout,
        pageSize: PageSize,
        marginPt: Float,
        metrics: ReFontMetrics,
    ): RePlan {
        val lw = layout.imageWidth.toFloat().coerceAtLeast(1f)
        val lh = layout.imageHeight.toFloat().coerceAtLeast(1f)
        val fit = PageFit.layout(lw, lh, pageSize, marginPt)
        val map = ImageToPage(x0 = fit.x, yTop = fit.y + fit.h, sx = fit.w / lw, sy = fit.h / lh)
        val ops = ArrayList<ReOp>()
        val bodyLineH = medianBodyLineHeight(layout)
        val blocks = layout.blocks.withIndex().sortedWith(compareBy({ it.value.order }, { it.index })).map { it.value }
        val bodySizePt = bodyLineH?.let { it * map.sy / (0.7f + 0.21f + LINE_PAD_EM) }
        for (b in blocks) {
            when (b.type) {
                BlockType.TABLE -> planTable(b, map, metrics, bodySizePt, ops)
                BlockType.FIGURE, BlockType.CHART, BlockType.FORMULA -> planPicture(b, layout, map, metrics, ops)
                else -> planTextBlock(b, map, metrics, bodyLineH, bodySizePt, ops)
            }
        }
        return RePlan(fit.pageW, fit.pageH, ops)
    }

    // ------------------------------------------------------------------------------------------------ texto

    /** Alto (px) mediano de las líneas de texto normal; null si hay muy pocas para decidir títulos por altura. */
    fun medianBodyLineHeight(layout: DocLayout): Float? {
        val hs = layout.blocks
            .filter { it.type == BlockType.TEXT || it.type == BlockType.HEADER || it.type == BlockType.FOOTER }
            .flatMap { it.lines }
            .map { quadGeometryPx(it).height }
            .filter { it > 0f }
            .sorted()
        if (hs.size < 4) return null
        return hs[hs.size / 2]
    }

    private fun fontFor(b: LayoutBlock, line: LayoutLine?, bodyLineH: Float?): ReFont = when (b.type) {
        BlockType.TITLE -> ReFont.BOLD
        BlockType.HANDWRITING -> ReFont.HAND
        BlockType.TEXT -> {
            val h = line?.let { quadGeometryPx(it).height } ?: 0f
            if (bodyLineH != null && line != null && h >= bodyLineH * HEADING_FACTOR && line.text.length <= 80) ReFont.BOLD
            else ReFont.REGULAR
        }
        else -> ReFont.REGULAR
    }

    /** Fuente elegida o la normal si la elegida pierde caracteres (subconjuntos latinos de negrita y manuscrita). */
    fun usableFont(wanted: ReFont, text: String, metrics: ReFontMetrics): Pair<ReFont, String> {
        val regular = metrics.clean(ReFont.REGULAR, text)
        if (wanted == ReFont.REGULAR) return ReFont.REGULAR to regular
        val other = metrics.clean(wanted, text)
        return if (other.length >= regular.length) wanted to other else ReFont.REGULAR to regular
    }

    private fun planTextBlock(
        b: LayoutBlock,
        map: ImageToPage,
        metrics: ReFontMetrics,
        bodyLineH: Float?,
        bodySizePt: Float?,
        ops: MutableList<ReOp>,
    ) {
        if (b.lines.isNotEmpty()) {
            for (line in b.lines) {
                val (font, text) = usableFont(fontFor(b, line, bodyLineH), line.text, metrics)
                if (text.isEmpty()) continue
                placeLine(text, font, line, map, metrics, rgbOf(line.color))?.let { ops += it }
            }
            return
        }
        if (b.text.isBlank()) return
        // Bloque sin líneas: el texto se reparte en su caja (como un párrafo).
        val (font, _) = usableFont(fontFor(b, null, bodyLineH), b.text, metrics)
        val r = pageRect(b.box, map)
        val maxSize = (bodySizePt ?: (r.h / max(1, b.text.lines().size) / LEADING)).let { if (font == ReFont.BOLD) it * 1.3f else it }
        ops += fitParagraph(b.text, font, r, metrics, maxSize, visible = true)
    }

    /** Geometría de una línea en px de imagen (y hacia abajo): longitud, alto y ángulo (horario, radianes). */
    data class LineGeom(val length: Float, val height: Float, val angle: Float)

    fun quadGeometryPx(line: LayoutLine): LineGeom {
        val q = line.quad
        if (q.size < 8) return LineGeom(line.box.width, line.box.height, 0f)
        val tdx = q[2] - q[0]; val tdy = q[3] - q[1]
        val bdx = q[4] - q[6]; val bdy = q[5] - q[7]
        val dx = tdx + bdx; val dy = tdy + bdy
        val len = (hypot(tdx, tdy) + hypot(bdx, bdy)) / 2f
        val ang = atan2(dy, dx)
        // Normal hacia abajo (y crece hacia abajo): n = (−sin, cos).
        val nx = -sin(ang); val ny = cos(ang)
        val hl = (q[6] - q[0]) * nx + (q[7] - q[1]) * ny
        val hr = (q[4] - q[2]) * nx + (q[5] - q[3]) * ny
        return LineGeom(len, ((hl + hr) / 2f).coerceAtLeast(0f), ang)
    }

    private val ASCENDER_CHARS = "bdfhiklt0123456789!?\"'/\\|()[]{}#$%&@ÁÉÍÓÚÑÜáéíóúüñ¿¡€£"
    private val DESCENDER_CHARS = "gjpqyQ,;()[]{}|ç@"

    /** Fracción de em ocupada por encima de la línea base: mayúsculas/ascendentes o solo minúsculas cortas. */
    fun inkTop(text: String, font: ReFont, m: ReFontMetrics): Float =
        if (text.any { it.isUpperCase() || it in ASCENDER_CHARS || (it.isLetter() && it.code > 0x24F) }) max(m.capHeight(font), 0.70f)
        else m.xHeight(font).takeIf { it > 0f } ?: 0.52f

    fun inkBottom(text: String, font: ReFont, m: ReFontMetrics): Float =
        if (text.any { it in DESCENDER_CHARS }) m.descent(font) else 0f

    /**
     * Coloca [text] en la línea [line]: tamaño por el alto, giro del cuadrilátero, ancho ajustado. null si no cabe nada.
     */
    fun placeLine(
        text: String,
        font: ReFont,
        line: LayoutLine,
        map: ImageToPage,
        m: ReFontMetrics,
        rgb: Int,
        visible: Boolean = true,
    ): ReOp.Text? {
        // Vértices en la página (y hacia arriba).
        val q = if (line.quad.size >= 8) line.quad else with(line.box) {
            listOf(left, top, right, top, right, bottom, left, bottom)
        }
        val p = FloatArray(8) { i -> if (i % 2 == 0) map.x(q[i]) else map.y(q[i]) }
        // p0 = arriba-izq., p1 = arriba-der., p2 = abajo-der., p3 = abajo-izq.
        val dx = (p[2] - p[0]) + (p[4] - p[6])
        val dy = (p[3] - p[1]) + (p[5] - p[7])
        var ang = atan2(dy, dx)
        if (abs(Math.toDegrees(ang.toDouble())) < MIN_ANGLE_DEG) ang = 0f
        val c = cos(ang); val s = sin(ang)
        val length = (hypot(p[2] - p[0], p[3] - p[1]) + hypot(p[4] - p[6], p[5] - p[7])) / 2f
        // Normal hacia arriba en la página: (−s, c).
        val hl = (p[0] - p[6]) * -s + (p[1] - p[7]) * c
        val hr = (p[2] - p[4]) * -s + (p[3] - p[5]) * c
        val height = (hl + hr) / 2f
        if (length <= 0.5f || height <= 0.3f) return null
        val widthEm = m.widthEm(font, text)
        if (widthEm <= 0f) return null

        val top = inkTop(text, font, m)
        val bottom = inkBottom(text, font, m)
        var size = (height / (top + bottom + LINE_PAD_EM)).coerceAtLeast(MIN_FONT_PT)
        var natural = widthEm * size
        var hScale = (length / natural).coerceIn(MIN_HSCALE, MAX_HSCALE)
        var charSpacing = 0f
        if (natural * MIN_HSCALE > length) {
            // Texto más largo que la caja incluso comprimido: letra más pequeña.
            size = (length / (widthEm * MIN_HSCALE)).coerceAtLeast(MIN_FONT_PT * 0.5f)
            natural = widthEm * size
            hScale = (length / natural).coerceIn(MIN_HSCALE * 0.5f, MAX_HSCALE)
        } else if (natural * MAX_HSCALE < length) {
            // Sobra espacio (texto espaciado): se reparte entre letras. Avance total = (natural + Tc·n)·Th.
            val n = text.length.coerceAtLeast(1)
            charSpacing = ((length / hScale - natural) / n).coerceAtLeast(0f)
        }
        // Línea base centrada en el alto de la caja según la tinta del texto.
        val off = (height - (top + bottom) * size) / 2f + bottom * size
        val ox = p[6] + -s * off
        val oy = p[7] + c * off
        return ReOp.Text(text, font, size, hScale * 100f, charSpacing, ox, oy, c, s, rgb, visible)
    }

    // ------------------------------------------------------------------------------------------------ párrafos

    /** Rectángulo en la página (x, y = esquina inferior izquierda). */
    data class PtRect(val x: Float, val y: Float, val w: Float, val h: Float)

    fun pageRect(b: LayoutBox, map: ImageToPage): PtRect {
        val x0 = map.x(b.left); val x1 = map.x(b.right)
        val yTop = map.y(b.top); val yBot = map.y(b.bottom)
        return PtRect(min(x0, x1), min(yTop, yBot), abs(x1 - x0), abs(yTop - yBot))
    }

    /**
     * Ajusta un texto (párrafos separados por \n) dentro de [r]: el mayor tamaño ≤ [maxSize] con el que, partiendo por
     * palabras, cabe en alto; las líneas que siguen sin caber de ancho se comprimen horizontalmente.
     */
    fun fitParagraph(
        text: String,
        font: ReFont,
        r: PtRect,
        m: ReFontMetrics,
        maxSize: Float,
        visible: Boolean,
        rgb: Int = BLACK,
        alignRight: Boolean = false,
        centerVertically: Boolean = false,
    ): List<ReOp.Text> {
        val paragraphs = text.split('\n').map { m.clean(font, it) }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty() || r.w <= 0.5f || r.h <= 0.5f) return emptyList()
        var size = min(maxSize, r.h / LEADING).coerceAtLeast(MIN_FONT_PT)
        var lines: List<String>
        while (true) {
            lines = paragraphs.flatMap { wrap(it, font, size, r.w, m) }
            if (lines.size * size * LEADING <= r.h + 0.01f || size <= MIN_FONT_PT) break
            size = max(MIN_FONT_PT, size * 0.92f)
        }
        val asc = max(m.capHeight(font), 0.7f)
        val blockH = lines.size * size * LEADING
        val topGap = if (centerVertically) max(0f, (r.h - blockH) / 2f) else 0f
        val out = ArrayList<ReOp.Text>(lines.size)
        lines.forEachIndexed { i, l ->
            val w = m.widthEm(font, l) * size
            if (w <= 0f) return@forEachIndexed
            val hs = if (w > r.w) (r.w / w).coerceAtLeast(0.4f) else 1f
            val drawnW = w * hs
            val x = if (alignRight) r.x + r.w - drawnW else r.x
            val y = r.y + r.h - topGap - i * size * LEADING - (size * (LEADING - 1f) / 2f) - asc * size
            out += ReOp.Text(l, font, size, hs * 100f, 0f, x, y, 1f, 0f, rgb, visible)
        }
        return out
    }

    /** Parte [text] por palabras para que cada línea mida ≤ [width] a [size] (una palabra sola nunca se parte). */
    fun wrap(text: String, font: ReFont, size: Float, width: Float, m: ReFontMetrics): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var cur = StringBuilder(words[0])
        for (w in words.drop(1)) {
            val candidate = "$cur $w"
            if (m.widthEm(font, candidate) * size <= width) cur.append(' ').append(w)
            else { out += cur.toString(); cur = StringBuilder(w) }
        }
        out += cur.toString()
        return out
    }

    // ------------------------------------------------------------------------------------------------ imágenes

    private fun planPicture(b: LayoutBlock, layout: DocLayout, map: ImageToPage, m: ReFontMetrics, ops: MutableList<ReOp>) {
        val src = PxRect(
            floor(b.box.left).toInt().coerceIn(0, layout.imageWidth),
            floor(b.box.top).toInt().coerceIn(0, layout.imageHeight),
            kotlin.math.ceil(b.box.right).toInt().coerceIn(0, layout.imageWidth),
            kotlin.math.ceil(b.box.bottom).toInt().coerceIn(0, layout.imageHeight),
        )
        if (src.width < 2 || src.height < 2) return
        val r = pageRect(LayoutBox(src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat()), map)
        ops += ReOp.Image(src, r.x, r.y, r.w, r.h, b.type)
        // Datos buscables (invisibles) sobre el recorte: no se dibuja nada inventado.
        val hidden = when (b.type) {
            BlockType.FORMULA -> b.latex?.takeIf { it.isNotBlank() } ?: b.text
            BlockType.CHART -> if (b.chartData.isNotEmpty()) b.chartData.joinToString("\n") { row -> row.joinToString(" | ") } else b.text
            else -> b.text
        }
        if (hidden.isNotBlank()) {
            val lines = hidden.lines().map { m.clean(ReFont.REGULAR, it) }.filter { it.isNotEmpty() }.take(200)
            if (lines.isNotEmpty()) {
                val lh = r.h / lines.size
                lines.forEachIndexed { i, l ->
                    val w = m.widthEm(ReFont.REGULAR, l)
                    if (w <= 0f) return@forEachIndexed
                    val size = (lh * 0.8f).coerceAtLeast(0.5f)
                    val hs = (r.w / (w * size)).coerceIn(0.01f, 50f)
                    ops += ReOp.Text(l, ReFont.REGULAR, size, hs * 100f, 0f, r.x, r.y + r.h - (i + 1) * lh + lh * 0.2f, 1f, 0f, BLACK, visible = false)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ tablas

    private fun planTable(b: LayoutBlock, map: ImageToPage, m: ReFontMetrics, bodySizePt: Float?, ops: MutableList<ReOp>) {
        val cells = TableGrid.normalize(b.tableRows, b.tableCols, b.cells)
        val rows = max(b.tableRows, cells.maxOfOrNull { it.row + it.rowSpan } ?: 0)
        val cols = max(b.tableCols, cells.maxOfOrNull { it.col + it.colSpan } ?: 0)
        if (rows <= 0 || cols <= 0) {
            // Tabla sin celdas: se trata como texto.
            planTextBlock(b.copy(type = BlockType.TEXT), map, m, null, bodySizePt, ops)
            return
        }
        val r = pageRect(b.box, map)
        // Fronteras en px de imagen (para usar las líneas reconocidas) y luego a la página.
        val colWeights = TableGrid.columnWeights(cols, cells) { t -> m.widthEm(ReFont.REGULAR, m.clean(ReFont.REGULAR, t)) }
        val xs = TableGrid.boundaries(b.box.left, b.box.right, colWeights)
        val ys = TableGrid.boundaries(b.box.top, b.box.bottom, FloatArray(rows) { 1f })
        TableGrid.refineFromLines(xs, ys, cells, b.lines)
        val px = FloatArray(xs.size) { map.x(xs[it]) }
        val py = FloatArray(ys.size) { map.y(ys[it]) } // decreciente (y hacia arriba)

        // Rejilla: contorno + fronteras internas respetando las celdas combinadas.
        val segs = ArrayList<Seg>()
        segs += Seg(px.first(), py.first(), px.last(), py.first())
        segs += Seg(px.first(), py.last(), px.last(), py.last())
        segs += Seg(px.first(), py.first(), px.first(), py.last())
        segs += Seg(px.last(), py.first(), px.last(), py.last())
        for (s in TableGrid.verticalSegments(rows, cols, cells)) segs += Seg(px[s.fixed], py[s.from], px[s.fixed], py[s.to])
        for (s in TableGrid.horizontalSegments(rows, cols, cells)) segs += Seg(px[s.from], py[s.fixed], px[s.to], py[s.fixed])
        val lineW = (min(r.w, r.h) * 0.002f).coerceIn(0.4f, 1.0f)
        ops += ReOp.Lines(segs, lineW)

        // Texto de las celdas: un tamaño común (el que cabe en la mayoría) acotado por el de cada celda.
        val rowH = r.h / rows
        val maxSize = min(rowH * 0.62f, (bodySizePt ?: rowH * 0.62f) * 1.1f).coerceAtLeast(MIN_FONT_PT)
        val cellRects = cells.filter { it.text.isNotBlank() }.map { c ->
            val x0 = px[c.col]; val x1 = px[c.col + c.colSpan]
            val yT = py[c.row]; val yB = py[c.row + c.rowSpan]
            val pad = min(1.5f + lineW, (x1 - x0) * 0.08f)
            c to PtRect(x0 + pad, yB + pad * 0.5f, (x1 - x0) - 2 * pad, (yT - yB) - pad)
        }
        val fitSizes = cellRects.map { (c, rr) -> bestFitSize(c.text, ReFont.REGULAR, rr, m, maxSize) }.sorted()
        val common = if (fitSizes.isEmpty()) maxSize else fitSizes[(fitSizes.size * 0.25f).toInt().coerceIn(0, fitSizes.size - 1)]
        cellRects.forEachIndexed { i, (c, rr) ->
            val numeric = isNumeric(c.text)
            ops += fitParagraph(c.text, ReFont.REGULAR, rr, m, min(common, maxSize), visible = true, alignRight = numeric, centerVertically = true)
        }
    }

    private fun isNumeric(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 24) return false
        val digits = t.count { it.isDigit() }
        return digits > 0 && t.all { it.isDigit() || it in " .,-+%$€()" } && digits >= t.length / 2
    }

    /** Mayor tamaño ≤ [maxSize] al que [text] cabe en [r] (partiendo por palabras). */
    fun bestFitSize(text: String, font: ReFont, r: PtRect, m: ReFontMetrics, maxSize: Float): Float {
        val paragraphs = text.split('\n').map { m.clean(font, it) }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty() || r.w <= 0f || r.h <= 0f) return maxSize
        var size = min(maxSize, r.h / LEADING).coerceAtLeast(MIN_FONT_PT)
        while (size > MIN_FONT_PT) {
            val lines = paragraphs.flatMap { wrap(it, font, size, r.w, m) }
            val fitsW = lines.all { m.widthEm(font, it) * size <= r.w * 1.02f }
            if (fitsW && lines.size * size * LEADING <= r.h + 0.01f) break
            size = max(MIN_FONT_PT, size * 0.92f)
        }
        return size
    }

    fun rgbOf(argb: Int?): Int = (argb ?: BLACK) and 0xFFFFFF
}

/**
 * Rejilla de tabla con celdas combinadas (lógica pura). Filas/columnas indexadas desde 0; las fronteras van de 0 a
 * rows/cols (0 y el último = contorno).
 */
object TableGrid {

    /** Segmento de frontera interna: [fixed] = índice de la frontera; de [from] a [to] (índices de frontera). */
    data class GridSeg(val fixed: Int, val from: Int, val to: Int)

    /**
     * Celdas válidas: spans recortados a la rejilla (si se conoce) y sin solapes (gana la primera). Si [rows]/[cols]
     * son 0 se deducen de las celdas.
     */
    fun normalize(rows: Int, cols: Int, cells: List<TableCell>): List<TableCell> {
        val maxR = if (rows > 0) rows else cells.maxOfOrNull { it.row + max(1, it.rowSpan) } ?: 0
        val maxC = if (cols > 0) cols else cells.maxOfOrNull { it.col + max(1, it.colSpan) } ?: 0
        if (maxR <= 0 || maxC <= 0 || maxR > 2000 || maxC > 200) return emptyList()
        val used = Array(maxR) { BooleanArray(maxC) }
        val out = ArrayList<TableCell>()
        for (c in cells) {
            if (c.row !in 0 until maxR || c.col !in 0 until maxC) continue
            var rs = max(1, c.rowSpan).coerceAtMost(maxR - c.row)
            var cs = max(1, c.colSpan).coerceAtMost(maxC - c.col)
            if (used[c.row][c.col]) continue
            // Recorta el span donde choque con celdas ya colocadas.
            for (dc in 1 until cs) if (used[c.row][c.col + dc]) { cs = dc; break }
            outer@ for (dr in 1 until rs) for (dc in 0 until cs) if (used[c.row + dr][c.col + dc]) { rs = dr; break@outer }
            for (dr in 0 until rs) for (dc in 0 until cs) used[c.row + dr][c.col + dc] = true
            out += c.copy(rowSpan = rs, colSpan = cs)
        }
        return out
    }

    /** Matriz de propietarios: índice de la celda que ocupa cada posición (−1 = vacía). */
    fun owners(rows: Int, cols: Int, cells: List<TableCell>): Array<IntArray> {
        val o = Array(rows) { IntArray(cols) { -1 } }
        cells.forEachIndexed { i, c ->
            for (r in c.row until min(rows, c.row + c.rowSpan)) for (k in c.col until min(cols, c.col + c.colSpan)) o[r][k] = i
        }
        return o
    }

    /** Fronteras verticales internas (columna c = entre c−1 y c), unidas en tramos continuos de filas. */
    fun verticalSegments(rows: Int, cols: Int, cells: List<TableCell>): List<GridSeg> {
        val o = owners(rows, cols, cells)
        val out = ArrayList<GridSeg>()
        for (c in 1 until cols) {
            var start = -1
            for (r in 0..rows) {
                val draw = r < rows && (o[r][c - 1] == -1 || o[r][c - 1] != o[r][c])
                if (draw && start < 0) start = r
                if (!draw && start >= 0) { out += GridSeg(c, start, r); start = -1 }
            }
        }
        return out
    }

    /** Fronteras horizontales internas (fila r = entre r−1 y r), unidas en tramos continuos de columnas. */
    fun horizontalSegments(rows: Int, cols: Int, cells: List<TableCell>): List<GridSeg> {
        val o = owners(rows, cols, cells)
        val out = ArrayList<GridSeg>()
        for (r in 1 until rows) {
            var start = -1
            for (c in 0..cols) {
                val draw = c < cols && (o[r - 1][c] == -1 || o[r - 1][c] != o[r][c])
                if (draw && start < 0) start = c
                if (!draw && start >= 0) { out += GridSeg(r, start, c); start = -1 }
            }
        }
        return out
    }

    /**
     * Peso de cada columna según el texto más ancho de sus celdas sin combinar (raíz para no exagerar; mínimo para
     * columnas vacías).
     */
    fun columnWeights(cols: Int, cells: List<TableCell>, widthOf: (String) -> Float): FloatArray {
        val w = FloatArray(cols)
        for (c in cells) {
            if (c.colSpan != 1 || c.col !in 0 until cols) continue
            val longest = c.text.split('\n').maxOfOrNull { widthOf(it) } ?: 0f
            w[c.col] = max(w[c.col], longest)
        }
        val positive = w.filter { it > 0f }
        val floorW = if (positive.isEmpty()) 1f else positive.sorted()[positive.size / 2] * 0.35f
        return FloatArray(cols) { sqrt(max(w[it], floorW).coerceAtLeast(0.1f)) + 0.6f }
    }

    /** Fronteras (n+1) repartiendo [start]..[end] según [weights]. */
    fun boundaries(start: Float, end: Float, weights: FloatArray): FloatArray {
        val total = weights.sum().takeIf { it > 0f } ?: 1f
        val out = FloatArray(weights.size + 1)
        out[0] = start
        var acc = 0f
        for (i in weights.indices) {
            acc += weights[i]
            out[i + 1] = start + (end - start) * acc / total
        }
        out[weights.size] = end
        return out
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Ajusta las fronteras internas ([xs], [ys], en px) a donde está el texto de cada celda en las líneas reconocidas
     * dentro de la tabla: frontera = punto medio entre el final de una columna y el principio de la siguiente. Solo
     * cambia las fronteras con datos coherentes (orden creciente); el resto queda como estaba.
     */
    fun refineFromLines(xs: FloatArray, ys: FloatArray, cells: List<TableCell>, lines: List<LayoutLine>) {
        if (lines.isEmpty()) return
        val cols = xs.size - 1
        val rows = ys.size - 1
        val minL = FloatArray(cols) { Float.MAX_VALUE }; val maxR = FloatArray(cols) { -Float.MAX_VALUE }
        val minT = FloatArray(rows) { Float.MAX_VALUE }; val maxB = FloatArray(rows) { -Float.MAX_VALUE }
        val used = BooleanArray(lines.size)
        val keys = lines.map { norm(it.text) }
        for (c in cells) {
            val k = norm(c.text)
            if (k.length < 1) continue
            var best = -1
            for (i in lines.indices) {
                if (used[i] || keys[i].isEmpty()) continue
                if (keys[i] == k) { best = i; break }
                if (best < 0 && k.length >= 3 && keys[i].length >= 3 && (keys[i].contains(k) || k.contains(keys[i]))) best = i
            }
            if (best < 0) continue
            used[best] = true
            val b = lines[best].box
            if (c.colSpan == 1) { minL[c.col] = min(minL[c.col], b.left); maxR[c.col] = max(maxR[c.col], b.right) }
            if (c.rowSpan == 1) { minT[c.row] = min(minT[c.row], b.top); maxB[c.row] = max(maxB[c.row], b.bottom) }
        }
        adjust(xs, minL, maxR)
        adjust(ys, minT, maxB)
    }

    private fun adjust(bounds: FloatArray, lo: FloatArray, hi: FloatArray) {
        val n = bounds.size - 1
        val proposed = bounds.copyOf()
        for (i in 1 until n) {
            val a = hi[i - 1]; val b = lo[i]
            if (a > -Float.MAX_VALUE && b < Float.MAX_VALUE && a <= b) proposed[i] = (a + b) / 2f
            else if (a > -Float.MAX_VALUE && a > bounds[i]) proposed[i] = a + 1f
            else if (b < Float.MAX_VALUE && b < bounds[i]) proposed[i] = b - 1f
        }
        // Solo si queda estrictamente creciente y dentro del contorno.
        for (i in 1..n) if (proposed[i] <= proposed[i - 1] + 0.5f) return
        for (i in 1 until n) bounds[i] = proposed[i]
    }
}
