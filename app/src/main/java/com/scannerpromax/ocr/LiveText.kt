package com.scannerpromax.ocr

import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Texto en vivo" del visor (lógica pura, probada en JVM): palabras del [OcrResult] guardado en orden de lectura,
 * selección por rango de palabras con dos tiradores y mapeo de coordenadas OCR ↔ pantalla con zoom/desplazamiento.
 *
 * Las coordenadas de las palabras son las del OCR (píxeles de la imagen PROCESADA tal como se reconoció, que puede
 * estar reducida respecto al archivo: [OcrResult.imageWidth] x [OcrResult.imageHeight]).
 */
object LiveText {

    /**
     * Palabra seleccionable: texto, caja (coordenadas OCR) e índices de su línea y bloque (globales). [doubtful]:
     * lectura dudosa (línea o palabra con confianza baja): se marca con un subrayado discreto.
     */
    data class Word(val text: String, val box: OcrRect, val line: Int, val block: Int, val doubtful: Boolean = false)

    /** Rango de palabras seleccionado (índices en la lista de [words], ambos incluidos, [start] <= [end]). */
    data class Range(val start: Int, val end: Int) {
        init {
            require(start <= end) { "start > end" }
        }

        operator fun contains(i: Int): Boolean = i in start..end
        val size: Int get() = end - start + 1
    }

    enum class Handle { START, END }

    /**
     * Palabras en orden de lectura: bloques en el orden del OCR (ya ordenados con [ReadingOrder]), líneas y palabras
     * en el orden del OCR. Las líneas sin palabras (JSON antiguos) se reparten en palabras con cajas proporcionales
     * al número de caracteres a lo largo del eje mayor de la línea.
     */
    fun words(result: OcrResult?): List<Word> {
        if (result == null) return emptyList()
        val out = ArrayList<Word>()
        var lineIndex = 0
        // Las líneas DUDOSAS (fuera de los bloques) entran como bloques propios delante del primer bloque que empieza
        // más abajo que ellas: así quedan seleccionables en un orden de lectura razonable.
        val pending = result.doubtful.filter { it.text.isNotBlank() }.sortedBy { it.box.top }.toMutableList()
        var bi = 0
        fun addLine(line: OcrLine, block: Int, doubtLine: Boolean) {
            val ws = line.words.filter { it.text.isNotBlank() }
            if (ws.isNotEmpty()) {
                ws.forEach { out += Word(it.text, it.box, lineIndex, block, doubtLine || it.isDoubtful) }
            } else {
                splitLine(line.text, line.box).forEach { (t, b) -> out += Word(t, b, lineIndex, block, doubtLine) }
            }
            lineIndex++
        }
        for (block in result.blocks) {
            while (pending.isNotEmpty() && pending[0].box.top < block.box.top) addLine(pending.removeAt(0), bi++, true)
            for (line in block.lines) addLine(line, bi, line.isDoubtful)
            bi++
        }
        for (line in pending) addLine(line, bi++, true)
        return out
    }

    /** Cajas (coordenadas OCR) de las palabras dudosas, para el subrayado discreto del visor. */
    fun doubtfulBoxes(words: List<Word>): List<OcrRect> = words.filter { it.doubtful }.map { it.box }

    /** Reparte [text] en palabras con cajas proporcionales a sus caracteres (espacios incluidos). */
    internal fun splitLine(text: String, box: OcrRect): List<Pair<String, OcrRect>> {
        val total = text.length
        if (total == 0 || text.isBlank()) return emptyList()
        val vertical = (box.bottom - box.top) > (box.right - box.left) * 1.5f && total > 2
        val out = ArrayList<Pair<String, OcrRect>>()
        val regex = Regex("\\S+")
        for (m in regex.findAll(text)) {
            val a = m.range.first.toFloat() / total
            val b = (m.range.last + 1).toFloat() / total
            val r = if (vertical) {
                val h = box.bottom - box.top
                OcrRect(box.left, box.top + a * h, box.right, box.top + b * h)
            } else {
                val w = box.right - box.left
                OcrRect(box.left + a * w, box.top, box.left + b * w, box.bottom)
            }
            out += m.value to r
        }
        return out
    }

    /** Palabra bajo el punto (coordenadas OCR) con una tolerancia de [tolerance] px OCR; null si no hay ninguna. */
    fun hitTest(words: List<Word>, x: Float, y: Float, tolerance: Float = 0f): Int? {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in words.indices) {
            val d = distance(words[i].box, x, y)
            if (d <= tolerance && d < bestD) {
                best = i
                bestD = d
                if (d == 0f) break
            }
        }
        return best.takeIf { it >= 0 }
    }

    /**
     * Palabra más cercana al punto (para arrastrar un tirador); null si no hay palabras. La distancia vertical pesa
     * [VERTICAL_WEIGHT] veces más: pasado el final de una línea se elige su última palabra, no una de la línea de
     * arriba que quede más cerca en diagonal (como al seleccionar texto en iOS).
     */
    fun nearest(words: List<Word>, x: Float, y: Float): Int? {
        if (words.isEmpty()) return null
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in words.indices) {
            val b = words[i].box
            val dx = max(0f, max(b.left - x, x - b.right))
            val dy = max(0f, max(b.top - y, y - b.bottom))
            val d = dy * VERTICAL_WEIGHT + dx
            if (d < bestD) {
                best = i
                bestD = d
                if (d == 0f) break
            }
        }
        return best
    }

    /** Distancia euclídea del punto a la caja (0 dentro). */
    fun distance(b: OcrRect, x: Float, y: Float): Float {
        val dx = max(0f, max(b.left - x, x - b.right))
        val dy = max(0f, max(b.top - y, y - b.bottom))
        return sqrt(dx * dx + dy * dy)
    }

    /** Rango entre la palabra ancla (donde empezó la pulsación larga) y la palabra actual, en cualquier sentido. */
    fun between(anchor: Int, current: Int): Range = Range(min(anchor, current), max(anchor, current))

    /**
     * Mueve el tirador [handle] a la palabra [target]. Si lo cruza con el otro tirador, se intercambian (como en
     * iOS): devuelve el nuevo rango y qué tirador está arrastrando ahora el dedo.
     */
    fun dragHandle(range: Range, handle: Handle, target: Int): Pair<Range, Handle> = when (handle) {
        Handle.START -> if (target <= range.end) Range(target, range.end) to Handle.START else Range(range.end, target) to Handle.END
        Handle.END -> if (target >= range.start) Range(range.start, target) to Handle.END else Range(target, range.start) to Handle.START
    }

    private const val VERTICAL_WEIGHT = 4f

    fun selectAll(words: List<Word>): Range? = if (words.isEmpty()) null else Range(0, words.size - 1)

    /**
     * Texto del rango: palabras de la misma línea separadas por espacio, líneas por salto de línea (uniendo los cortes
     * de palabra con guion) y bloques por línea en blanco.
     */
    fun text(words: List<Word>, range: Range?): String {
        if (range == null || words.isEmpty()) return ""
        val s = range.start.coerceIn(0, words.size - 1)
        val e = range.end.coerceIn(0, words.size - 1)
        val blocks = ArrayList<MutableList<String>>()
        var curBlock = -1
        var curLine = -1
        val sb = StringBuilder()
        fun flushLine() {
            if (sb.isNotEmpty()) blocks.last().add(sb.toString())
            sb.setLength(0)
        }
        for (i in s..e) {
            val w = words[i]
            if (w.block != curBlock) {
                if (curBlock != -1) flushLine()
                blocks += ArrayList<String>()
                curBlock = w.block
                curLine = w.line
            } else if (w.line != curLine) {
                flushLine()
                curLine = w.line
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w.text)
        }
        flushLine()
        return blocks.filter { it.isNotEmpty() }.joinToString("\n\n") { OcrText.joinHyphenatedLines(it).joinToString("\n") }
    }

    /** Una caja por línea con la envolvente de las palabras seleccionadas de esa línea (para el resaltado). */
    fun lineRects(words: List<Word>, range: Range?): List<OcrRect> {
        if (range == null || words.isEmpty()) return emptyList()
        val out = ArrayList<OcrRect>()
        var cur: OcrRect? = null
        var curLine = -1
        for (i in range.start.coerceAtLeast(0)..range.end.coerceAtMost(words.size - 1)) {
            val w = words[i]
            val c = cur
            if (c == null || w.line != curLine) {
                c?.let { out += it }
                cur = w.box
                curLine = w.line
            } else {
                cur = OcrRect(min(c.left, w.box.left), min(c.top, w.box.top), max(c.right, w.box.right), max(c.bottom, w.box.bottom))
            }
        }
        cur?.let { out += it }
        return out
    }
}

/**
 * Mapeo entre coordenadas OCR y la vista del visor. La imagen ([imageW] x [imageH], tamaño real del archivo) se
 * ajusta a la vista ([viewW] x [viewH]) y se amplía [scale] veces alrededor del centro desplazado [offsetX]/[offsetY]
 * (igual que ZoomState del visor). Las coordenadas OCR ([ocrW] x [ocrH]) se reescalan al tamaño del archivo.
 */
data class LiveTextMapping(
    val viewW: Float,
    val viewH: Float,
    val imageW: Float,
    val imageH: Float,
    val ocrW: Float,
    val ocrH: Float,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    private val fit: Float = if (imageW > 0f && imageH > 0f) min(viewW / imageW, viewH / imageH) else 0f
    /** Píxeles de pantalla por píxel del archivo. */
    private val ds: Float = fit * scale
    private val left: Float = viewW / 2f + offsetX - imageW * ds / 2f
    private val top: Float = viewH / 2f + offsetY - imageH * ds / 2f
    private val sx: Float = if (ocrW > 0f) imageW / ocrW * ds else 0f
    private val sy: Float = if (ocrH > 0f) imageH / ocrH * ds else 0f

    /** Píxeles de pantalla por unidad OCR (horizontal). */
    val pxPerOcr: Float get() = sx

    fun toViewX(x: Float): Float = left + x * sx
    fun toViewY(y: Float): Float = top + y * sy
    fun toOcrX(vx: Float): Float = if (sx == 0f) 0f else (vx - left) / sx
    fun toOcrY(vy: Float): Float = if (sy == 0f) 0f else (vy - top) / sy

    /** Caja OCR en coordenadas de pantalla. */
    fun toView(r: OcrRect): OcrRect = OcrRect(toViewX(r.left), toViewY(r.top), toViewX(r.right), toViewY(r.bottom))
}
