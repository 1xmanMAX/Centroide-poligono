package com.scannerpromax.ocr

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTilingTest {

    /** Línea con palabras de [wordW] px separadas 10 px, alto 20, empezando en (x, y). */
    private fun line(text: String, x: Float, y: Float, wordW: Float = 60f, conf: Float = 0.9f): OcrLine {
        val words = text.split(" ").mapIndexed { i, t -> OcrWord(t, OcrRect(x + i * (wordW + 10f), y, x + i * (wordW + 10f) + wordW, y + 20f), conf) }
        return OcrLine(text, OcrRect(words.first().box.left, y, words.last().box.right, y + 20f), words, 0f, conf)
    }

    private fun block(vararg lines: OcrLine) = OcrBlock(
        lines.joinToString("\n") { it.text },
        OcrRect(lines.minOf { it.box.left }, lines.minOf { it.box.top }, lines.maxOf { it.box.right }, lines.maxOf { it.box.bottom }),
        lines.toList(),
    )

    private fun allLines(blocks: List<OcrBlock>) = blocks.flatMap { it.lines }.map { it.text }.sorted()

    @Test fun planCubreLaPaginaConSolape() {
        val tiles = OcrTiling.plan(5000, 3500, 2000, 100)
        // cubre todo
        assertEquals(0, tiles.minOf { it.x0 }); assertEquals(0, tiles.minOf { it.y0 })
        assertEquals(5000, tiles.maxOf { it.x1 }); assertEquals(3500, tiles.maxOf { it.y1 })
        for (t in tiles) assertTrue(t.width <= 2000 && t.height <= 2000)
        // solape horizontal >= 100 entre vecinos de la misma fila
        val row = tiles.filter { it.y0 == 0 }.sortedBy { it.x0 }
        assertEquals(3, row.size)
        for (i in 1 until row.size) assertTrue(row[i - 1].x1 - row[i].x0 >= 100)
        val col = tiles.filter { it.x0 == 0 }.sortedBy { it.y0 }
        assertEquals(2, col.size)
        assertTrue(col[0].y1 - col[1].y0 >= 100)
    }

    @Test fun planUnaPiezaSiCabe() {
        val tiles = OcrTiling.plan(1500, 1200, 2000, 100)
        assertEquals(listOf(OcrTiling.Tile(0, 0, 1500, 1200)), tiles)
    }

    @Test fun duplicadoEnElSolapeSeQuedaUnaVez() {
        // Mosaicos [0,1100) y [900,2000): la línea en x 950..1080 la ven los dos
        val a = OcrTiling.Tile(0, 0, 1100, 1000); val b = OcrTiling.Tile(900, 0, 2000, 1000)
        val dup = line("12,50", 950f, 400f)
        val parts = listOf(
            a to listOf(block(line("SALDO", 100f, 100f), dup)),
            b to listOf(block(dup.copy(confidence = 0.8f), line("TOTAL", 1500f, 100f))),
        )
        val out = OcrTiling.merge(parts, 2000, 1000)
        assertEquals(listOf("12,50", "SALDO", "TOTAL"), allLines(out))
    }

    @Test fun lineaCortadaPorElBordeSeSustituyePorLaCompleta() {
        // Mosaicos verticales [0,600) y [500,1200). Una línea en y 585..605: en el primero sale cortada (toca el borde
        // inferior interior) y con menos letras; en el segundo, entera.
        val a = OcrTiling.Tile(0, 0, 1000, 600); val b = OcrTiling.Tile(0, 500, 1000, 1200)
        val cut = OcrLine("ENTRAD", OcrRect(100f, 585f, 300f, 600f), emptyList(), 0f, 0.6f)
        val full = OcrLine("ENTRADAS", OcrRect(100f, 585f, 310f, 605f), emptyList(), 0f, 0.9f)
        val out = OcrTiling.merge(listOf(a to listOf(block(cut)), b to listOf(block(full))), 1000, 1200)
        assertEquals(listOf("ENTRADAS"), allLines(out))
    }

    @Test fun lineaLargaPartidaEntreMosaicosSeUneSinRepetir() {
        // Mosaicos [0,1000) y [800,1800). Línea "uno dos tres cuatro cinco seis" con palabras de 60 px cada 70 px
        // desde x=600: uno 600-660, dos 670-730, tres 740-800, cuatro 810-870, cinco 880-940, seis 950-1010.
        // El mosaico A ve hasta "cinco" (seis queda cortada en 1000); el B ve desde "cuatro".
        val a = OcrTiling.Tile(0, 0, 1000, 500); val b = OcrTiling.Tile(800, 0, 1800, 500)
        val whole = line("uno dos tres cuatro cinco seis", 600f, 200f)
        val left = whole.words.take(5)
        val right = whole.words.drop(3)
        fun mk(ws: List<OcrWord>) = OcrLine(ws.joinToString(" ") { it.text }, OcrRect(ws.first().box.left, 200f, ws.last().box.right, 220f), ws, 0f, 0.9f)
        val out = OcrTiling.merge(listOf(a to listOf(block(mk(left))), b to listOf(block(mk(right)))), 1800, 500)
        assertEquals(listOf("uno dos tres cuatro cinco seis"), allLines(out))
    }

    @Test fun lineasLejosDelSolapeSeConservanYLosBloquesSeRehacen() {
        val a = OcrTiling.Tile(0, 0, 1100, 1000); val b = OcrTiling.Tile(900, 0, 2000, 1000)
        val parts = listOf(
            a to listOf(block(line("a1", 100f, 100f), line("a2", 100f, 140f)), block(line("a3", 100f, 600f))),
            b to listOf(block(line("b1", 1400f, 100f))),
        )
        val out = OcrTiling.merge(parts, 2000, 1000)
        assertEquals(3, out.size)
        assertEquals("a1\na2", out[0].text)
        assertEquals(OcrRect(100f, 100f, 160f, 160f), out[0].box)
    }

    @Test fun desplazamientoACoordenadasDePagina() {
        val b = block(line("x", 10f, 20f))
        val o = OcrTiling.offset(b, 100f, 200f)
        assertEquals(OcrRect(110f, 220f, 170f, 240f), o.lines[0].box)
        assertEquals(OcrRect(110f, 220f, 170f, 240f), o.lines[0].words[0].box)
        assertEquals(110f, o.box.left)
    }
}
