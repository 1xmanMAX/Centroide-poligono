package com.scannerpromax.ai.parts

import com.scannerpromax.ai.parse.IaParse
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.LayoutFill
import com.scannerpromax.domain.LayoutLine
import com.scannerpromax.domain.TableCell
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lectores (modelo de IA) que usa la tubería por partes. Las cajas van en píxeles de la imagen analizada ([Rgb] de
 * [PartsPipeline.Input]); el lector recorta (y reduce) la imagen él mismo. null = herramienta no disponible (p. ej.
 * modo nube: solo hay líneas del OCR básico).
 */
interface PartsReader {
    /** "Spotting:" de la página ([crop] = null) o de un recorte. */
    suspend fun spotting(crop: Box?, maxTokens: Int): PartsReader.Out?
    /** "Table Recognition:" sobre una imagen compuesta por [pieces] puestas de izquierda a derecha (fondo blanco). */
    suspend fun table(pieces: List<Piece>): Out?
    suspend fun chart(crop: Box): Out?
    suspend fun formula(crop: Box): Out?

    /** Salida cruda del modelo y si hizo bucle (o agotó los tokens). */
    class Out(val text: String, val looped: Boolean)

    /** Trozo [src] de la imagen analizada colocado en x = [dstX] (px de esa misma escala) de la imagen compuesta. */
    class Piece(val src: Box, val dstX: Float)
}

/**
 * Tubería de reconstrucción "POR PARTES" (puerto fiel de tools/recon2_new.py del banco recon2; en el banco: bloques
 * DocLayNet recall/precisión 0,34/0,35 → 0,72/0,72, página pintada como imagen 24 % → 10 %, formularios con rayas):
 *
 *  regiones PP-DocLayoutV3 (ya detectadas) → líneas con posición: Spotting de la página o, si hace bucle, es manuscrito
 *  o hay pocas regiones, Spotting POR REGIÓN + zonas de tinta no cubiertas → líneas a regiones (> 50 %) → libres
 *  agrupadas → herramienta por región (tabla OTSL con bandas de ≤ 8 columnas repitiendo la primera, gráfico, fórmula;
 *  "fórmula" con frases → texto) → tinta residual = FIGURE role=dibujo (recorte solo-tinta) → rayas y fondos por
 *  morfología → estilo por bloque (alineación, tamaño, negrita, color) → orden de lectura.
 *
 * Lógica pura (JVM): el modelo va detrás de [PartsReader]. Coordenadas de salida = px de la imagen analizada.
 */
class PartsPipeline(private val reader: PartsReader) {

    /** Línea de texto con su cuadrilátero (x0,y0,…,x3,y3) y caja, en px de la imagen analizada. */
    class Item(val text: String, val quad: FloatArray, val bx: Box)

    class Input(
        /** Página analizada (derecha: la orientación ya se corrigió). */
        val img: Rgb,
        /** Regiones del detector de maquetación ([PpLayout.regions]); vacía si no hay modelo. */
        val regions: List<PpLayout.Region>,
        val docType: String? = null,
        val engine: String = "",
        val rotation: Int = 0,
        /** Líneas ya conocidas (OCR básico en modo nube): sin Spotting; null = leer con [PartsReader.spotting]. */
        val presetLines: List<Item>? = null,
    )

    class Meta(
        val mode: String,
        val pageLooped: Boolean,
        val regions: Int,
        val freeBlocks: Int,
        val rules: Int,
        val fills: Int,
        val drawings: Int,
        val calls: Int,
    )

    class Output(val layout: DocLayout, val items: List<Item>, val meta: Meta)

    private var calls = 0

    private class Reg(val r: PpLayout.Region, var type: BlockType, var label: String) {
        val lines = ArrayList<Item>()
        val box get() = r.box
    }

    suspend fun run(input: Input, progress: (Float, String) -> Unit = { _, _ -> }): Output {
        calls = 0
        val img = input.img
        val w = img.w; val h = img.h
        pageW = w; pageH = h
        val hand = input.docType == "manuscrito"
        val regs = input.regions.map { Reg(it, it.type, it.label) }

        // ---- líneas
        var mode = "pagina"
        var pageLooped = false
        var items: MutableList<Item>
        if (input.presetLines != null) {
            mode = "ocr"
            items = input.presetLines.filter { it.text.isNotBlank() }.toMutableList()
        } else {
            var letters = 0
            items = ArrayList()
            val regional = wantsRegional(input.regions, hand)
            if (!regional) {
                progress(0.05f, "Leyendo texto")
                val sp = spot(null, 3000)
                pageLooped = sp == null || sp.second
                items = sp?.first?.toMutableList() ?: ArrayList()
                letters = items.sumOf { it.text.count { c -> c.isLetterOrDigit() } }
            }
            if (regional || pageLooped || items.isEmpty() || letters < 10) {
                mode = "regiones"
                items = ArrayList()
                val zones = regs.filter { it.type in SPOT_TYPES }.map { it.box }.toMutableList()
                zones += PageInk.uncoveredZones(img, regs.map { it.box })
                zones.forEachIndexed { i, z ->
                    progress(0.05f + 0.55f * i / zones.size.coerceAtLeast(1), "Leyendo texto por partes")
                    items += spotCrop(z)
                }
            }
        }
        items = dedupeItems(items).toMutableList()

        // ---- líneas a regiones
        val free = ArrayList<Item>()
        for (it in items) {
            var best: Reg? = null; var bf = 0.5f
            for (r in regs) { val f = PageInk.insideFrac(it.bx, r.box); if (f > bf) { best = r; bf = f } }
            if (best == null) free += it else best.lines += it
        }
        // "fórmula" que contiene frases (manuscrito confundido con fórmula) → texto
        for (r in regs) if (r.type == BlockType.FORMULA) {
            val words = r.lines.sumOf { it.text.split(Regex("\\s+")).count { wd -> wd.count { c -> c.isLetter() } >= 4 } }
            if (words >= 3 && r.lines.size >= 2) { r.type = BlockType.TEXT; r.label = "text" }
        }
        // regiones de texto sin líneas (Spotting de página): Spotting del recorte
        if (mode == "pagina") for (r in regs) {
            if (r.type !in TEXT_TYPES || r.lines.isNotEmpty()) continue
            val its = spotCrop(r.box).filter { n -> items.none { o -> IaParse.iou(n.bx, o.bx) > 0.3f } }
            items += its
            r.lines += its
        }
        // líneas sueltas: fuera las de 1–2 caracteres mucho más altas que el texto (dibujos leídos como letras)
        val hsAll = items.map { it.bx[3] - it.bx[1] }.sorted().ifEmpty { listOf(1f) }
        val hmed = hsAll[hsAll.size / 2]
        val freeOk = free.filter { !(it.text.count { c -> c.isLetterOrDigit() } <= 2 && (it.bx[3] - it.bx[1]) > 2 * hmed) }
        val extra = groupFree(freeOk, w, h)

        // ---- herramienta por región
        progress(0.62f, "Leyendo tablas, gráficos y fórmulas")
        val blocks = ArrayList<B>()
        for (r in regs) {
            val bx = r.box
            val lines = r.lines.sortedWith(compareBy({ ((it.bx[1]) / max(1f, (it.bx[3] - it.bx[1]) * 0.6f)).roundToInt() }, { it.bx[0] }))
                .map { line(it, img) }
            when (r.type) {
                BlockType.TEXT, BlockType.TITLE, BlockType.HEADER, BlockType.FOOTER -> {
                    if (lines.isEmpty()) continue
                    val ty = if (r.type == BlockType.TEXT && hand) BlockType.HANDWRITING else r.type
                    val env = floatArrayOf(
                        min(bx[0], lines.minOf { it.box.left }), min(bx[1], lines.minOf { it.box.top }),
                        max(bx[2], lines.maxOf { it.box.right }), max(bx[3], lines.maxOf { it.box.bottom }),
                    )
                    blocks += B(styled(LayoutBlock(type = ty, box = lbox(env), text = lines.joinToString("\n") { it.text }, lines = lines, role = r.label), bx), r.r.order)
                }
                BlockType.TABLE -> blocks += B(table(r, lines, img), r.r.order)
                BlockType.CHART -> {
                    var b = LayoutBlock(type = BlockType.CHART, box = lbox(bx), lines = lines, role = r.label)
                    val out = reader.chart(pad(bx, w, h, 4f))?.also { calls++ }
                    if (out != null) {
                        val ch = IaParse.parseChart(out.text)
                        val data = ch.rows.flatMap { it.drop(1) }
                        if (ch.header.size >= 2 && ch.rows.isNotEmpty() && ch.rows.size <= 60 && data.isNotEmpty() &&
                            data.count { IaParse.toNumber(it) != null } >= 0.5f * data.size
                        ) b = b.copy(chartData = listOf(ch.header) + ch.rows)
                    }
                    blocks += B(b, r.r.order)
                }
                BlockType.FORMULA -> {
                    var latex: String? = null
                    val out = reader.formula(pad(bx, w, h, 4f))?.also { calls++ }
                    if (out != null && !out.looped) {
                        val fm = IaParse.parseFormula(out.text)
                        if (fm.latex.isNotBlank() && fm.latex.length <= 800) latex = fm.latex + (fm.tag?.let { " \\tag{$it}" } ?: "")
                    }
                    blocks += B(LayoutBlock(type = BlockType.FORMULA, box = lbox(bx), lines = lines, text = lines.joinToString(" ") { it.text }, latex = latex, role = r.label), r.r.order)
                }
                else -> blocks += B(LayoutBlock(type = BlockType.FIGURE, box = lbox(bx), lines = lines, text = lines.joinToString("\n") { it.text }, role = r.label), r.r.order)
            }
        }
        for (e in extra) {
            val lines = e.first.map { line(it, img) }
            blocks += B(styled(LayoutBlock(type = if (hand) BlockType.HANDWRITING else BlockType.TEXT, box = lbox(e.second), text = lines.joinToString("\n") { it.text }, lines = lines, role = "libre"), e.second), null)
        }

        // ---- dibujos/trazos sin texto → recortes solo-tinta
        progress(0.9f, "Rayas, fondos y dibujos")
        val assigned = regs.flatMap { it.lines }.toHashSet()
        val lb = items.filter { it in assigned || it in freeOk }.map { it.bx }
        val taken = blocks.filter { it.b.type in PICTURE_TYPES || it.b.type == BlockType.TABLE }.map { box(it.b.box) }
        val ink = PageInk.inkMask(img)
        val drw = PageInk.drawings(img, lb, taken, ink0 = ink)
        for (d in drw) blocks += B(LayoutBlock(type = BlockType.FIGURE, box = lbox(d), role = ROLE_DRAWING), null)
        val ordered = orderBlocks(blocks)

        val rules = PageInk.detectRules(img, ordered.filter { it.type in PICTURE_TYPES }.map { box(it.box) })
        val withRuled = ordered.map { b ->
            if (b.type != BlockType.TABLE) b else {
                val tb = floatArrayOf(b.box.left - 4, b.box.top - 4, b.box.right + 4, b.box.bottom + 4)
                val n = rules.count { r ->
                    PageInk.insideFrac(floatArrayOf(min(r[0], r[2]) - 1, min(r[1], r[3]) - 1, max(r[0], r[2]) + 1, max(r[1], r[3]) + 1), tb) > 0.8f
                }
                b.copy(ruled = n >= 2)
            }
        }
        val fills = PageInk.detectFills(img)
        val layout = DocLayout(
            imageWidth = w, imageHeight = h, blocks = withRuled, docType = input.docType, engine = input.engine,
            rotation = input.rotation, rules = rules.map { it.toList() }, fills = fills.map { LayoutFill(lbox(it.box), it.argb) },
        )
        progress(1f, "Listo")
        return Output(layout, items, Meta(mode, pageLooped, regs.size, extra.size, rules.size, fills.size, drw.size, calls))
    }

    /** Bloque con el orden del detector (null = bloque libre / dibujo). */
    private class B(val b: LayoutBlock, val ppOrder: Int?)

    // ------------------------------------------------------------------------------------------ Spotting

    /** Spotting de la página o recorte → líneas en px de la imagen analizada (+ bucle). null = no disponible. */
    private suspend fun spot(crop: IntArray?, maxTokens: Int): Pair<List<Item>, Boolean>? {
        val out = reader.spotting(crop?.let { floatArrayOf(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }, maxTokens) ?: return null
        calls++
        val x0 = crop?.get(0) ?: 0; val y0 = crop?.get(1) ?: 0
        val cw = crop?.let { it[2] - it[0] } ?: pageW
        val ch = crop?.let { it[3] - it[1] } ?: pageH
        val parsed = IaParse.filterSpotLoops(IaParse.parseSpotting(out.text))
        val items = parsed.mapNotNull { it0 ->
            val q = it0.quadPx(cw, ch)?.takeIf { it.size == 4 } ?: return@mapNotNull null
            val text = it0.text.replace('\n', ' ').trim()
            if (text.isEmpty()) return@mapNotNull null
            val quad = FloatArray(8) { i -> if (i % 2 == 0) q[i / 2][0] + x0 else q[i / 2][1] + y0 }
            Item(text, quad, quadBox(quad))
        }
        return items to (out.looped || looped(out.text))
    }

    private var pageW = 0
    private var pageH = 0

    private suspend fun spotCrop(r: Box, padPx: Int = 6): List<Item> {
        val x0 = max(0, (r[0] - padPx).toInt()); val y0 = max(0, (r[1] - padPx).toInt())
        val x1 = min(pageW, (r[2] + padPx).toInt()); val y1 = min(pageH, (r[3] + padPx).toInt())
        if (x1 - x0 < 8 || y1 - y0 < 8) return emptyList()
        val n = min(3000, (200 + (x1 - x0).toLong() * (y1 - y0) / 400).toInt())
        return spot(intArrayOf(x0, y0, x1, y1), n)?.first.orEmpty()
    }

    /**
     * ¿Leer por regiones desde el principio? (evita el Spotting de página que en manuscrito/cuaderno hace bucle: 3/7
     * fotos del usuario en el banco, ~4 min tirados): manuscrito, pocas regiones de texto o confianza media baja.
     */
    private fun wantsRegional(regions: List<PpLayout.Region>, hand: Boolean): Boolean {
        if (hand) return true
        if (regions.isEmpty()) return false  // sin maquetación: no hay regiones que leer (Spotting de página)
        val text = regions.filter { it.type in TEXT_TYPES }
        if (text.size < MIN_TEXT_REGIONS) return true
        return text.map { it.score }.average() < MIN_MEAN_SCORE
    }

    // ------------------------------------------------------------------------------------------ tablas

    private suspend fun table(r: Reg, lines: List<LayoutLine>, img: Rgb): LayoutBlock {
        val bx = r.box
        var b = LayoutBlock(type = BlockType.TABLE, box = lbox(bx), lines = lines, role = r.label)
        var cells: IaParse.OtslTable? = null
        val out = reader.table(listOf(PartsReader.Piece(pad(bx, img.w, img.h, 4f), 0f)))?.also { calls++ }
        val available = out != null
        if (out != null && !out.looped && !IaParse.otslLoop(out.text)) {
            val t = IaParse.parseOtsl(out.text)
            if (t.nRows >= 1 && t.nCols >= 1) cells = t
        }
        if (cells == null && available && r.lines.size >= 6) {
            cells = tableBands(bx, r.lines, PER_BAND) ?: tableBands(bx, r.lines, PER_BAND_RETRY)
        }
        if (cells != null) {
            b = b.copy(
                tableRows = cells.nRows, tableCols = cells.nCols,
                text = cells.rows.joinToString("\n") { it.joinToString("\t") },
                cells = cells.cells.map { TableCell(it.r, it.c, it.rowSpan, it.colSpan, it.text) },
            )
        } else if (available) {
            // sin estructura: se dibuja como imagen + texto de sus líneas
            b = b.copy(type = BlockType.FIGURE, text = lines.joinToString("\n") { it.text })
        } else {
            // sin lector de tablas (modo nube): tabla con sus líneas (la nube pondrá las celdas)
            b = b.copy(text = lines.joinToString("\n") { it.text })
        }
        return b
    }

    /**
     * Columnas por huecos verticales de las líneas del Spotting dentro de la tabla ([separators]) y lectura por bandas
     * de ≤ [per] columnas repitiendo la primera (clave). null si hay < 3 columnas o alguna banda hace bucle.
     */
    private suspend fun tableBands(bx: Box, items: List<Item>, per: Int): IaParse.OtslTable? {
        val seps = separators(bx, items) ?: return null
        val ncol = seps.size - 1
        if (ncol < 3) return null
        val bands = bands(ncol, per)
        if (bands.size == 1 && per != PER_BAND) return null  // el reintento solo tiene sentido si trocea más
        val x0 = bx[0].toInt(); val y0 = bx[1].toInt(); val y1 = bx[3].toInt()
        val parts = ArrayList<IaParse.OtslTable>()
        for ((i, band) in bands.withIndex()) {
            val xa = x0 + seps[band.first]; val xb = x0 + seps[band.last + 1]
            val kw = if (i > 0) seps[1] - seps[0] else 0
            val pieces = ArrayList<PartsReader.Piece>()
            if (kw > 0) pieces += PartsReader.Piece(floatArrayOf((x0 + seps[0]).toFloat(), y0.toFloat(), (x0 + seps[1]).toFloat(), y1.toFloat()), 0f)
            pieces += PartsReader.Piece(floatArrayOf(xa.toFloat(), y0.toFloat(), xb.toFloat(), y1.toFloat()), kw.toFloat())
            val out = reader.table(pieces)?.also { calls++ } ?: return null
            if (out.looped || IaParse.otslLoop(out.text)) return null
            parts += IaParse.parseOtsl(out.text)
        }
        return mergeBands(parts)
    }

    // ------------------------------------------------------------------------------------------ líneas → bloques

    private fun line(it: Item, img: Rgb): LayoutLine = LayoutLine(
        text = it.text.replace('\n', ' ').trim(),
        box = lbox(it.bx),
        quad = it.quad.map { v -> (v * 10f).roundToInt() / 10f },
        color = PageInk.inkColor(img, it.quad),
    )

    /** Orden de lectura: el del detector para sus regiones; libres/dibujos tras la región que tienen justo encima. */
    private fun orderBlocks(blocks: List<B>): List<LayoutBlock> {
        val seq = blocks.filter { it.ppOrder != null }.sortedBy { it.ppOrder!! }.toMutableList()
        val free = blocks.filter { it.ppOrder == null }.sortedWith(compareBy({ it.b.box.top }, { it.b.box.left }))
        for (f in free) {
            var best: Float? = null; var bi = seq.size
            for ((i, b) in seq.withIndex()) {
                if (b.b.box.top <= f.b.box.top && (best == null || b.b.box.top > best)) { best = b.b.box.top; bi = i + 1 }
            }
            if (best == null) bi = if (seq.isEmpty()) 0 else seq.indexOfFirst { it.b.box.top > f.b.box.top }.let { if (it < 0) seq.size else it }
            seq.add(bi, f)
        }
        return seq.mapIndexed { i, b -> b.b.copy(order = i) }
    }

    companion object {
        /** Lector vacío: solo líneas dadas (modo nube con OCR básico), sin herramientas de tabla/gráfico/fórmula. */
        val NoReader: PartsReader = object : PartsReader {
            override suspend fun spotting(crop: Box?, maxTokens: Int): PartsReader.Out? = null
            override suspend fun table(pieces: List<PartsReader.Piece>): PartsReader.Out? = null
            override suspend fun chart(crop: Box): PartsReader.Out? = null
            override suspend fun formula(crop: Box): PartsReader.Out? = null
        }

        const val ROLE_DRAWING = "dibujo"
        const val PER_BAND = 8
        const val PER_BAND_RETRY = 5
        const val MIN_TEXT_REGIONS = 3
        const val MIN_MEAN_SCORE = 0.6
        private val TEXT_TYPES = setOf(BlockType.TEXT, BlockType.TITLE, BlockType.HEADER, BlockType.FOOTER)
        private val SPOT_TYPES = TEXT_TYPES + BlockType.TABLE
        private val PICTURE_TYPES = setOf(BlockType.FIGURE, BlockType.CHART, BlockType.FORMULA)

        fun lbox(b: Box) = LayoutBox((b[0] * 10f).roundToInt() / 10f, (b[1] * 10f).roundToInt() / 10f, (b[2] * 10f).roundToInt() / 10f, (b[3] * 10f).roundToInt() / 10f)
        fun box(b: LayoutBox): Box = floatArrayOf(b.left, b.top, b.right, b.bottom)
        fun pad(b: Box, w: Int, h: Int, p: Float): Box = floatArrayOf(max(0f, b[0] - p), max(0f, b[1] - p), min(w.toFloat(), b[2] + p), min(h.toFloat(), b[3] + p))
        fun quadBox(q: FloatArray): Box = floatArrayOf(
            min(min(q[0], q[2]), min(q[4], q[6])), min(min(q[1], q[3]), min(q[5], q[7])),
            max(max(q[0], q[2]), max(q[4], q[6])), max(max(q[1], q[3]), max(q[5], q[7])),
        )

        /** Bucle del Spotting: lo que quitaría la limpieza de repeticiones es > 40 % del texto. */
        fun looped(t0: String): Boolean {
            val t = t0.replace("</s>", "")
            if (t.isEmpty()) return false
            return com.scannerpromax.ai.parse.IaText.stripRepetition(t).length < 0.6 * t.length
        }

        /** Líneas duplicadas (Spotting de recortes solapados): la menor contenida (> 70 %) con texto incluido fuera. */
        fun dedupeItems(items: List<Item>): List<Item> {
            val out = ArrayList<Item>()
            for (it in items.sortedByDescending { (it.bx[2] - it.bx[0]) * (it.bx[3] - it.bx[1]) }) {
                if (out.any { o -> PageInk.insideFrac(it.bx, o.bx) > 0.7f && (o.text.contains(it.text) || it.text.contains(o.text)) }) continue
                out += it
            }
            return out
        }

        /**
         * Líneas sueltas → bloques (como spotting_to_blocks): una línea se une al bloque cuyo último renglón está justo
         * encima (hueco ≤ 1,2 alturas), con solape horizontal ≥ 30 % del más estrecho y altura parecida (0,6–1,6).
         * Coordenadas normalizadas 0..1000 como el banco. Devuelve (líneas, caja px).
         */
        fun groupFree(free: List<Item>, w: Int, h: Int): List<Pair<List<Item>, Box>> {
            if (free.isEmpty()) return emptyList()
            class N(val src: Item, val b: FloatArray)
            val its = free.map { N(it, floatArrayOf(it.bx[0] * 1000f / w, it.bx[1] * 1000f / h, it.bx[2] * 1000f / w, it.bx[3] * 1000f / h)) }
            val hs = its.map { max(1f, it.b[3] - it.b[1]) }.sorted()
            val hmed = hs[hs.size / 2]
            class G(val items: MutableList<Item>, var last: FloatArray)
            val groups = ArrayList<G>()
            for (it in its.sortedWith(compareBy({ it.b[1] }, { it.b[0] }))) {
                val (x1, y1, x2, y2) = it.b.toList()
                val hh = max(1f, y2 - y1)
                var best: Pair<Float, G>? = null
                for (g in groups) {
                    val (lx1, ly1, lx2, ly2) = g.last.toList()
                    val gap = y1 - ly2; val lh = max(1f, ly2 - ly1)
                    val ov = min(x2, lx2) - max(x1, lx1); val narrow = max(1f, min(x2 - x1, lx2 - lx1))
                    if (gap >= -0.5f * lh && gap <= 1.2f * max(hmed, lh) && ov / narrow >= 0.3f && hh / lh in 0.6f..1.6f) {
                        if (best == null || gap < best.first) best = gap to g
                    }
                }
                if (best != null) { best.second.items += it.src; best.second.last = it.b } else groups += G(mutableListOf(it.src), it.b)
            }
            return groups.map { g ->
                g.items.toList() to floatArrayOf(g.items.minOf { it.bx[0] }, g.items.minOf { it.bx[1] }, g.items.maxOf { it.bx[2] }, g.items.maxOf { it.bx[3] })
            }
        }

        /** Alineación del párrafo por los márgenes de sus líneas dentro de la caja [bx]. */
        fun alignOf(lines: List<LayoutLine>, bx: Box): String {
            if (lines.size < 2) return "LEFT"
            val wd = bx[2] - bx[0]
            if (wd <= 0) return "LEFT"
            val ls = lines.map { it.box.left - bx[0] }; val rs = lines.map { bx[2] - it.box.right }
            val tol = 0.03f * wd + 3
            val full = ls.indices.count { ls[it] < tol && rs[it] < tol }
            if (lines.size >= 3 && full >= 0.6f * (lines.size - 1)) return "JUSTIFY"
            val cen = ls.indices.count { abs(ls[it] - rs[it]) < tol }
            if (cen >= 0.8f * lines.size && ls.max() > tol) return "CENTER"
            if (ls.count { it < tol } >= 0.8f * lines.size) return "LEFT"
            if (rs.count { it < tol } >= 0.8f * lines.size) return "RIGHT"
            return "LEFT"
        }

        /** Estilo del bloque: alineación, alto de línea típico, negrita (títulos), color dominante. */
        fun styled(b: LayoutBlock, bx: Box): LayoutBlock {
            val hs = b.lines.map { it.box.bottom - it.box.top }.sorted()
            val cs = b.lines.mapNotNull { it.color }
            val color = if (cs.isNotEmpty() && cs.size >= 0.5f * b.lines.size) cs.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key else null
            return b.copy(
                align = alignOf(b.lines, bx),
                sizePx = if (hs.isEmpty()) null else (hs[hs.size / 2] * 10f).roundToInt() / 10f,
                bold = if (b.type == BlockType.TITLE) true else b.bold,
                color = color ?: b.color,
            )
        }

        /** Separadores de columna (px relativos al borde izquierdo de [bx]) por huecos verticales de las líneas. */
        fun separators(bx: Box, items: List<Item>): List<Int>? {
            if (items.isEmpty()) return null
            val x0 = bx[0].toInt(); val x1 = bx[2].toInt()
            val occ = IntArray(max(1, x1 - x0))
            val hs = items.map { it.bx[3] - it.bx[1] }.sorted(); val hmed = hs[hs.size / 2]
            for (it in items) {
                val a = max(x0.toFloat(), it.bx[0]).toInt() - x0; val b = min(x1.toFloat(), it.bx[2]).toInt() - x0
                for (k in max(0, a) until min(occ.size, b)) occ[k]++
            }
            val seps = arrayListOf(0)
            var run = 0
            for ((i, v) in occ.withIndex()) {
                if (v == 0) run++
                else {
                    if (run >= max(3f, 0.4f * hmed) && i - run > 0) seps += i - run / 2
                    run = 0
                }
            }
            seps += occ.size
            return seps
        }

        /** Maquetación hecha sobre la imagen analizada → píxeles de la imagen completa [w] x [h]. */
        fun scaleLayout(l: DocLayout, w: Int, h: Int): DocLayout {
            if (l.imageWidth == w && l.imageHeight == h) return l
            val sx = w.toFloat() / l.imageWidth; val sy = h.toFloat() / l.imageHeight
            fun b(x: LayoutBox) = LayoutBox(x.left * sx, x.top * sy, x.right * sx, x.bottom * sy)
            return l.copy(
                imageWidth = w, imageHeight = h,
                blocks = l.blocks.map { bl ->
                    bl.copy(
                        box = b(bl.box),
                        lines = bl.lines.map { ln -> ln.copy(box = b(ln.box), quad = ln.quad.mapIndexed { i, v -> if (i % 2 == 0) v * sx else v * sy }) },
                        sizePx = bl.sizePx?.let { it * sy },
                    )
                },
                rules = l.rules.map { r -> if (r.size >= 5) listOf(r[0] * sx, r[1] * sy, r[2] * sx, r[3] * sy, r[4] * (sx + sy) / 2) else r },
                fills = l.fills.map { it.copy(box = b(it.box)) },
            )
        }

        /**
         * Capa de texto (PDF buscable, búsqueda, pantalla de texto) a partir de la maquetación: un bloque de OCR por bloque
         * con líneas, palabras repartidas a lo largo de su cuadrilátero.
         */
        fun ocrOf(l: DocLayout): com.scannerpromax.domain.OcrResult {
            val blocks = l.blocks.filter { it.lines.isNotEmpty() }.map { bl ->
                val lines = bl.lines.filter { it.text.isNotBlank() }.map { ln ->
                    val r = com.scannerpromax.domain.OcrRect(ln.box.left, ln.box.top, ln.box.right, ln.box.bottom)
                    val q = ln.quad.takeIf { it.size >= 8 }?.let { v -> List(4) { floatArrayOf(v[2 * it], v[2 * it + 1]) } }
                    val ang = q?.let { IaParse.quadAngle(it) }?.takeIf { abs(it) < 45f } ?: 0f
                    com.scannerpromax.domain.OcrLine(ln.text, r, com.scannerpromax.ai.AiLayout.wordsAlong(ln.text, q, r), ang)
                }
                com.scannerpromax.domain.OcrBlock(lines.joinToString("\n") { it.text }, com.scannerpromax.domain.OcrRect(bl.box.left, bl.box.top, bl.box.right, bl.box.bottom), lines)
            }.filter { it.lines.isNotEmpty() }
            val text = l.blocks.sortedBy { it.order }.mapNotNull { bl ->
                when {
                    bl.type == BlockType.TABLE && bl.cells.isNotEmpty() -> bl.text
                    bl.lines.isNotEmpty() -> bl.lines.joinToString("\n") { it.text }
                    else -> bl.text.takeIf { it.isNotBlank() }
                }
            }.joinToString("\n\n")
            return com.scannerpromax.domain.OcrResult(text = text, imageWidth = l.imageWidth, imageHeight = l.imageHeight, blocks = blocks)
        }

        /** Bandas de ≤ [per] columnas: la primera entera; las siguientes de per−1 (la clave se repite delante). */
        fun bands(ncol: Int, per: Int): List<IntRange> {
            val out = arrayListOf(0 until min(ncol, per))
            var c = per
            while (c < ncol) { out += c until min(ncol, c + per - 1); c += per - 1 }
            return out
        }

        /** Une las tablas de las bandas (sin la columna clave repetida), filas por índice. */
        fun mergeBands(parts: List<IaParse.OtslTable>): IaParse.OtslTable? {
            if (parts.isEmpty()) return null
            if (parts.size == 1) return parts[0]
            val nr = parts.maxOf { it.nRows }
            val cells = ArrayList<IaParse.OtslCell>()
            var off = 0
            for ((bi, t) in parts.withIndex()) {
                val skip = if (bi == 0) 0 else 1
                val nc = max(0, t.nCols - skip)
                for (cl in t.cells) {
                    if (cl.c < skip) continue
                    cells += IaParse.OtslCell(cl.r, cl.c - skip + off, cl.rowSpan, min(cl.colSpan, nc - (cl.c - skip)), cl.text)
                }
                off += nc
            }
            val rows = List(nr) { MutableList(off) { "" } }
            for (cl in cells) if (cl.r in 0 until nr && cl.c in 0 until off) rows[cl.r][cl.c] = cl.text
            return IaParse.OtslTable(rows, cells, "", nr, off)
        }
    }
}
