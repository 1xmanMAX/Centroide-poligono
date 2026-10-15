package com.scannerpromax.ai.parts

import com.scannerpromax.ai.cloud.CloudAlign
import com.scannerpromax.ai.cloud.CloudPage
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.LayoutLine
import com.scannerpromax.domain.TableCell
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modo HÍBRIDO (puerto de tools/recon2_fuse.py del banco recon2 + mejora del impreso): la GEOMETRÍA es siempre la de la
 * maquetación LOCAL (regiones PP-DocLayoutV3, líneas, rayas, fondos, dibujos, estilo) y el TEXTO el de la nube, que
 * transcribe mucho mejor pero sitúa mal (cajas aproximadas):
 *
 *  1a. cada línea local ↔ línea de la nube por parecido de texto (≥ 0,8, o ≥ 0,5 si está cerca), asignación voraz 1:1;
 *  1b. manuscrito: las líneas de la nube que quedan dentro del bloque local se reparten por palabras entre sus líneas;
 *      impreso: solo si una ventana de líneas de la nube se parece (> 0,6) al texto del bloque;
 *  1c. (mejora) impreso con la misma POSICIÓN pero texto distinto: con las parejas de 1a se corrigen las cajas de la
 *      nube (escala + desplazamiento por eje) y cada línea de la nube que cae sobre una fila de líneas locales sin pareja
 *      les da su texto (repartido por palabras): en impreso la nube acierta mucho más (banco: dln_5070 CER 0,2 frente a
 *      9,8 de Paddle);
 *  tablas: cada línea local recibe la celda de la nube más parecida (≥ 0,35) y la tabla toma las celdas de la nube;
 *  dibujo local (tinta sin texto) con texto de la nube encima → texto (renglón que el modelo local no pudo leer);
 *  texto de la nube sin pareja que no cae sobre nada local → bloque role=nube con la caja de la nube.
 *
 * Lógica pura (JVM). Ambas maquetaciones en los mismos píxeles.
 */
object HybridFuse {

    private const val PAD = 0.04f
    private val TXT = setOf(BlockType.TEXT, BlockType.TITLE, BlockType.HEADER, BlockType.FOOTER, BlockType.HANDWRITING)

    class Stats {
        var textBlocks = 0; var linesTextMatch = 0; var fused = 0; var positional = 0
        var tableLines = 0; var dibujoText = 0; var cloudAdded = 0
        override fun toString() = "bloques=$textBlocks 1a=$linesTextMatch 1b=$fused 1c=$positional tabla=$tableLines dibujo=$dibujoText nube=$cloudAdded"
    }

    /** Maquetación "cruda" de la nube en px de una imagen [w] x [h] (cajas tal cual, sin alinear). */
    fun cloudLayout(page: CloudPage, w: Int, h: Int): DocLayout {
        fun box(b: FloatArray?) = b?.let { LayoutBox(it[0] * w / 1000f, it[1] * h / 1000f, it[2] * w / 1000f, it[3] * h / 1000f) }
        val blocks = page.blocks.mapIndexedNotNull { i, b ->
            val bb = box(b.bbox) ?: return@mapIndexedNotNull null
            LayoutBlock(
                type = b.type, box = bb, text = b.plainText(),
                lines = b.lines.mapNotNull { l -> box(l.bbox)?.let { LayoutLine(l.text, it) } },
                tableRows = b.tableRows, tableCols = b.tableCols, cells = b.cells, chartData = b.chart, latex = b.latex,
                order = i, style = b.style, color = b.color,
            )
        }
        return DocLayout(w, h, blocks, page.docType)
    }

    private class CLine(val text: String, val b: FloatArray, val src: Int) { var used = false }

    private fun bb(b: LayoutBox) = floatArrayOf(b.left, b.top, b.right, b.bottom)
    private fun inter(a: FloatArray, b: FloatArray) = max(0f, min(a[2], b[2]) - max(a[0], b[0])) * max(0f, min(a[3], b[3]) - max(a[1], b[1]))
    private fun area(a: FloatArray) = max(1e-6f, (a[2] - a[0]) * (a[3] - a[1]))
    private fun grow(a: FloatArray, w: Int, h: Int, p: Float = PAD) = floatArrayOf(a[0] - p * w, a[1] - p * h, a[2] + p * w, a[3] + p * h)
    private fun sim(a: String, b: String) = TextSim.sim(a, b)
    private fun rowKey(b: FloatArray) = (b[1] / max(1f, (b[3] - b[1]) * 0.6f)).roundToInt()

    /**
     * Reparte [words] (de la nube) entre las líneas locales [local] en orden: para cada línea el corte que más se parece
     * a su texto local; si el parecido es bajo (< 0,45), corte proporcional a la longitud local.
     */
    fun splitWords(words: List<String>, local: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val totalLocal = local.sumOf { max(1, it.length) }.toFloat()
        val totalCloud = words.sumOf { it.length + 1 }.toFloat()
        for ((k, l) in local.withIndex()) {
            if (k == local.size - 1) { out += words.subList(min(i, words.size), words.size).joinToString(" "); break }
            val rest = local.size - k - 1
            val propChars = max(1, l.length) / totalLocal * totalCloud
            var best = -1f; var bj = i; var acc = 0; var jprop = i
            for (j in i..(words.size - rest)) {
                if (j > i) acc += words[j - 1].length + 1
                if (acc <= propChars) jprop = j
                if (j == i) continue
                val s = sim(l, words.subList(i, j).joinToString(" "))
                if (s > best) { best = s; bj = j }
            }
            val j = if (best >= 0.45f) bj else max(jprop, if (words.size - i > rest) i + 1 else i)
            out += words.subList(min(i, words.size), min(j, words.size)).joinToString(" ")
            i = max(i, j)
        }
        return out
    }

    fun fuse(local: DocLayout, cloud0: DocLayout, positional: Boolean = true): Pair<DocLayout, Stats> {
        val w = local.imageWidth; val h = local.imageHeight
        val sx = w.toFloat() / cloud0.imageWidth; val sy = h.toFloat() / cloud0.imageHeight
        fun sc(b: LayoutBox) = floatArrayOf(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)
        val cb = cloud0.blocks
        val cbox = cb.map { sc(it.box) }
        val cUsed = BooleanArray(cb.size)
        val st = Stats()
        val hand = local.docType == "manuscrito"

        // copias mutables de los textos de las líneas locales
        val blocks = local.blocks.toMutableList()
        val texts = blocks.map { b -> b.lines.map { it.text }.toMutableList() }
        val fuente = BooleanArray(blocks.size)
        val tIdx = blocks.indices.filter { blocks[it].type in TXT && blocks[it].lines.isNotEmpty() }
        st.textBlocks = tIdx.size

        // 1) líneas de texto de la nube (bloques sin líneas: franjas iguales con su texto)
        val clines = ArrayList<CLine>()
        for ((ci, c) in cb.withIndex()) {
            if (c.type == BlockType.FIGURE || c.type == BlockType.CHART || c.type == BlockType.TABLE || c.text.isBlank()) continue
            if (c.lines.isNotEmpty()) for (l in c.lines) clines += CLine(l.text, sc(l.box), ci)
            else {
                val tl = c.text.split('\n').filter { it.isNotBlank() }
                val bx = cbox[ci]; val hh = (bx[3] - bx[1]) / max(1, tl.size)
                tl.forEachIndexed { i, t -> clines += CLine(t, floatArrayOf(bx[0], bx[1] + i * hh, bx[2], bx[1] + (i + 1) * hh), ci) }
            }
        }
        // (las líneas de figuras/gráficos también reciben el texto de la nube en 1a: es su texto buscable)
        val figIdx = blocks.indices.filter { (blocks[it].type == BlockType.FIGURE || blocks[it].type == BlockType.CHART) && blocks[it].lines.isNotEmpty() }
        val ll = (tIdx + figIdx).flatMap { bi -> blocks[bi].lines.indices.map { li -> bi to li } }
        fun lbox(p: Pair<Int, Int>) = bb(blocks[p.first].lines[p.second].box)
        fun near(lb: FloatArray, cl: FloatArray, gy: Float, gx: Float): Boolean {
            val g = floatArrayOf(lb[0] - gx * w, lb[1] - gy * h, lb[2] + gx * w, lb[3] + gy * h)
            val cy = (cl[1] + cl[3]) / 2
            return cy >= g[1] && cy <= g[3] && min(g[2], cl[2]) > max(g[0], cl[0])
        }
        // 1a) por TEXTO con plausibilidad geométrica
        data class Cand(val s: Float, val i: Int, val j: Int)
        val cand = ArrayList<Cand>()
        for ((i, p) in ll.withIndex()) {
            val lt = blocks[p.first].lines[p.second].text
            val lb = lbox(p)
            for ((j, c) in clines.withIndex()) {
                val s = sim(lt, c.text)
                if (s >= 0.8f || (s >= 0.5f && near(lb, c.b, 0.15f, 0.1f))) cand += Cand(s, i, j)
            }
        }
        val doneL = HashSet<Int>(); val doneC = HashSet<Int>()
        val pairs = ArrayList<Pair<FloatArray, FloatArray>>()  // (caja nube, caja local) para calibrar 1c
        for (c in cand.sortedWith(compareByDescending<Cand> { it.s }.thenByDescending { it.i }.thenByDescending { it.j })) {
            if (c.i in doneL || c.j in doneC) continue
            val p = ll[c.i]
            texts[p.first][p.second] = clines[c.j].text
            doneL += c.i; doneC += c.j; clines[c.j].used = true; cUsed[clines[c.j].src] = true
            fuente[p.first] = true
            pairs += clines[c.j].b to lbox(p)
        }
        st.linesTextMatch = doneL.size

        // 1b) bloques con líneas sin pareja
        for (bi in tIdx) {
            val idx = ll.indices.filter { ll[it].first == bi && it !in doneL }
            if (idx.isEmpty()) continue
            val lines = idx.map { ll[it] }
            val env = floatArrayOf(lines.minOf { lbox(it)[0] }, lines.minOf { lbox(it)[1] }, lines.maxOf { lbox(it)[2] }, lines.maxOf { lbox(it)[3] })
            if (!(hand || blocks[bi].type == BlockType.HANDWRITING)) {
                // impreso: solo si una ventana de líneas de la nube cercanas se parece MUCHO al texto local
                val g = grow(env, w, h, 0.05f)
                val mine = clines.filter { !it.used && inter(g, it.b) > 0.3f * area(it.b) }.let { m -> PartsPipeline.readingSort(m) { it.b } }
                val localText = lines.joinToString(" ") { texts[it.first][it.second] }
                var best = 0.6f; var bw: Pair<Int, Int>? = null
                for (i0 in mine.indices) for (i1 in (i0 + 1)..min(mine.size, i0 + 2 * lines.size + 2)) {
                    val s = sim(localText, mine.subList(i0, i1).joinToString(" ") { it.text })
                    if (s > best) { best = s; bw = i0 to i1 }
                }
                val win = bw ?: continue
                val sel = mine.subList(win.first, win.second)
                val words = sel.flatMap { it.text.split(Regex("\\s+")).filter { s -> s.isNotEmpty() } }
                sel.forEach { it.used = true; cUsed[it.src] = true }
                splitWords(words, lines.map { texts[it.first][it.second] }).forEachIndexed { k, t -> if (t.isNotBlank()) texts[lines[k].first][lines[k].second] = t }
                idx.forEach { doneL += it }
                fuente[bi] = true; st.fused++
                continue
            }
            val g = grow(env, w, h, 0.01f)
            val mine = clines.filter { !it.used && inter(g, it.b) > 0.5f * area(it.b) }
            if (mine.isEmpty()) continue
            val sorted = mine.let { m -> PartsPipeline.readingSort(m) { it.b } }
            val words = sorted.flatMap { it.text.split(Regex("\\s+")).filter { s -> s.isNotEmpty() } }
            sorted.forEach { it.used = true; cUsed[it.src] = true }
            splitWords(words, lines.map { texts[it.first][it.second] }).forEachIndexed { k, t -> if (t.isNotBlank()) texts[lines[k].first][lines[k].second] = t }
            idx.forEach { doneL += it }
            fuente[bi] = true; st.fused++
        }

        // 1c) impreso: misma posición, texto distinto → el de la nube
        if (positional) {
            val calibrated = pairs.size >= 4
            val fx = if (calibrated) CloudAlign.robustFit(pairs.flatMap { (c, l) -> listOf(c[0] to l[0], c[2] to l[2]) }, w) else null
            val fy = if (calibrated) CloudAlign.robustFit(pairs.flatMap { (c, l) -> listOf(c[1] to l[1], c[3] to l[3]) }, h) else null
            fun cal(b: FloatArray): FloatArray {
                val (ax, bx) = fx ?: (1f to 0f); val (ay, by) = fy ?: (1f to 0f)
                return floatArrayOf(ax * b[0] + bx, ay * b[1] + by, ax * b[2] + bx, ay * b[3] + by)
            }
            for (c in clines) {
                if (c.used || c.text.isBlank()) continue
                val b = cal(c.b)
                val ch = b[3] - b[1]
                if (ch <= 0f) continue
                // filas de líneas locales sin pareja (impresas) cuyo centro cae en la franja de la línea de la nube
                val row = ll.indices.filter { it !in doneL }.filter { k ->
                    val p = ll[k]
                    val blk = blocks[p.first]
                    if (blk.type !in TXT) return@filter false
                    if (hand || blk.type == BlockType.HANDWRITING) return@filter false
                    val lb = lbox(p); val lh = lb[3] - lb[1]
                    val cy = (lb[1] + lb[3]) / 2; val cx = (lb[0] + lb[2]) / 2
                    val vOv = max(0f, min(lb[3], b[3]) - max(lb[1], b[1])) / max(1f, min(lh, ch))
                    cy >= b[1] - 0.3f * ch && cy <= b[3] + 0.3f * ch && cx >= b[0] - 0.02f * w && cx <= b[2] + 0.02f * w &&
                        vOv >= 0.5f && lh / ch in 0.4f..2.5f
                }.sortedBy { lbox(ll[it])[0] }
                if (row.isEmpty()) continue
                val localText = row.joinToString(" ") { texts[ll[it].first][ll[it].second] }
                val s = sim(localText, c.text)
                // misma posición: se acepta con parecido moderado, o sin parecido si la calibración es fiable y las
                // anchuras cuadran (la línea local cubre la mayor parte de la de la nube)
                val lw = lbox(ll[row.last()])[2] - lbox(ll[row.first()])[0]
                val cover = lw / max(1f, b[2] - b[0])
                if (!(s >= 0.3f || (calibrated && cover in 0.6f..1.6f))) continue
                val words = c.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                splitWords(words, row.map { texts[ll[it].first][ll[it].second] }).forEachIndexed { k, t ->
                    if (t.isNotBlank()) texts[ll[row[k]].first][ll[row[k]].second] = t
                }
                row.forEach { doneL += it; fuente[ll[it].first] = true }
                c.used = true; cUsed[c.src] = true
                st.positional++
            }
        }

        // reconstruir bloques de texto con los textos nuevos
        for (bi in blocks.indices) {
            val b = blocks[bi]
            if (b.lines.isEmpty() || texts[bi] == b.lines.map { it.text }) continue
            val lines = b.lines.mapIndexed { li, l -> l.copy(text = texts[bi][li]) }
            blocks[bi] = b.copy(lines = lines, text = if (b.type in TXT) lines.joinToString("\n") { it.text } else b.text,
                role = b.role)
        }

        // tablas
        for (bi in blocks.indices) {
            val b = blocks[bi]
            if (b.type != BlockType.TABLE || b.lines.isEmpty()) continue
            val g = grow(bb(b.box), w, h)
            val hosts = cb.indices.filter { cb[it].type == BlockType.TABLE && inter(g, cbox[it]) > 0.3f * area(cbox[it]) }
            val cells = hosts.flatMap { cb[it].cells.filter { c -> c.text.isNotBlank() } }
            hosts.forEach { cUsed[it] = true }
            val lt = b.lines.map { it.text }.toMutableList()
            val usedC = HashSet<Int>(); val doneT = HashSet<Int>()
            val cands = ArrayList<Cand>()
            for (i in lt.indices) for (j in cells.indices) cands += Cand(sim(lt[i], cells[j].text), i, j)
            for (c in cands.sortedWith(compareByDescending<Cand> { it.s }.thenByDescending { it.i }.thenByDescending { it.j })) {
                if (c.s < 0.35f) break
                if (c.i in doneT || c.j in usedC) continue
                lt[c.i] = cells[c.j].text; doneT += c.i; usedC += c.j; st.tableLines++
            }
            var nb = b.copy(lines = b.lines.mapIndexed { i, l -> l.copy(text = lt[i]) })
            if (cells.isNotEmpty() && b.cells.isEmpty()) {
                // celdas de la nube (de la tabla que más se solapa si hay varias, para que filas/columnas cuadren)
                val main = hosts.maxByOrNull { inter(g, cbox[it]) }!!
                val cc = cb[main].cells
                nb = nb.copy(
                    cells = cc.map { TableCell(it.row, it.col, it.rowSpan, it.colSpan, it.text) },
                    tableRows = max(cb[main].tableRows, 1 + (cc.maxOfOrNull { it.row } ?: 0)),
                    tableCols = max(cb[main].tableCols, 1 + (cc.maxOfOrNull { it.col } ?: 0)),
                    text = cb[main].text,
                )
            }
            blocks[bi] = nb
        }

        // dibujos locales donde la nube leyó texto
        val out = ArrayList<LayoutBlock>()
        for (b in blocks) {
            if (b.type == BlockType.FIGURE && b.role == PartsPipeline.ROLE_DRAWING) {
                val g = bb(b.box)
                val mate = cb.indices.firstOrNull { !cUsed[it] && cb[it].type in TXT && cb[it].text.length >= 5 && inter(g, cbox[it]) > 0.5f * area(g) }
                if (mate != null) {
                    cUsed[mate] = true; st.dibujoText++
                    val t = cb[mate].text.replace('\n', ' ')
                    out += LayoutBlock(
                        type = if (hand) BlockType.HANDWRITING else BlockType.TEXT, box = b.box, text = t,
                        lines = listOf(LayoutLine(t, b.box)), order = b.order, role = "dibujo→nube", sizePx = b.box.height,
                        style = cb[mate].style, color = cb[mate].color,
                    )
                    continue
                }
            }
            out += b
        }
        // estilo de la nube como pista en los bloques locales que la casan (negrita/alineación/tamaño relativo)
        for (i in out.indices) {
            val b = out[i]
            if (b.type !in TXT || b.style != null) continue
            val g = bb(b.box)
            val best = cb.indices.filter { cb[it].style != null && cb[it].type in TXT }.maxByOrNull { inter(g, cbox[it]) / area(g) }
            if (best != null && inter(g, cbox[best]) > 0.5f * area(g)) out[i] = b.copy(style = cb[best].style)
        }
        // texto de la nube sin pareja (y que no cae sobre nada local): se añade con su caja
        val occupied = out.map { bb(it.box) }
        for ((ci, c) in cb.withIndex()) {
            if (cUsed[ci] || c.type !in TXT || c.text.isBlank()) continue
            val g = cbox[ci]
            if (occupied.any { inter(g, it) > 0.3f * area(g) }) continue
            val lines = if (c.lines.isNotEmpty()) c.lines.map { l -> val b = sc(l.box); LayoutLine(l.text, LayoutBox(b[0], b[1], b[2], b[3])) }
            else listOf(LayoutLine(c.text.replace('\n', ' '), LayoutBox(g[0], g[1], g[2], g[3])))
            out += LayoutBlock(
                type = c.type, box = LayoutBox(g[0], g[1], g[2], g[3]), text = c.text, lines = lines, order = out.size, role = "nube",
                sizePx = lines.minOf { it.box.height }, style = c.style, color = c.color,
            )
            st.cloudAdded++
        }
        val engine = listOf(local.engine, cloud0.engine).filter { it.isNotBlank() }.joinToString("+").ifEmpty { "hibrido" }
        // manuscrito (según la clasificación local o la de la nube): letra manuscrita en los párrafos
        val handFinal = hand || cloud0.docType == "manuscrito"
        val typed = if (!handFinal) out else out.map { if (it.type == BlockType.TEXT) it.copy(type = BlockType.HANDWRITING) else it }
        return local.copy(blocks = typed.mapIndexed { i, b -> b.copy(order = i) }, engine = engine, docType = local.docType ?: cloud0.docType) to st
    }
}
