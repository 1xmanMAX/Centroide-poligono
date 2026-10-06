package com.scannerpromax.text

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.ocr.ReadingOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingOrderTest {

    /** Bloque [name] con [lines] líneas de 20 px de alto empezando en (x, y) y ancho w. */
    private fun block(name: String, x: Float, y: Float, w: Float, lines: Int): OcrBlock {
        val ls = (0 until lines).map { i -> OcrLine("$name$i", OcrRect(x, y + i * 28f, x + w, y + i * 28f + 20f)) }
        return OcrBlock(name, OcrRect(x, y, x + w, y + (lines - 1) * 28f + 20f), ls)
    }

    @Test fun dosColumnasConTitulo() {
        val title = block("T", 100f, 50f, 1000f, 1)
        val l1 = block("L1", 100f, 150f, 450f, 6)
        val l2 = block("L2", 100f, 340f, 450f, 6)   // párrafo 2 de la columna izquierda
        val r1 = block("R1", 650f, 150f, 450f, 6)
        val r2 = block("R2", 650f, 340f, 450f, 6)
        val footer = block("F", 100f, 700f, 1000f, 1)
        val shuffled = listOf(r2, footer, l2, title, r1, l1)
        val order = ReadingOrder.sort(shuffled).map { it.text }
        assertEquals(listOf("T", "L1", "L2", "R1", "R2", "F"), order)
    }

    @Test fun columnasConParrafosAlineadosNoSeLeenPorFilas() {
        // Los huecos entre párrafos coinciden en ambas columnas: aun así se lee columna a columna.
        val l1 = block("L1", 100f, 100f, 450f, 4)
        val r1 = block("R1", 650f, 100f, 450f, 4)
        val l2 = block("L2", 100f, 260f, 450f, 4)
        val r2 = block("R2", 650f, 260f, 450f, 4)
        val order = ReadingOrder.sort(listOf(r1, r2, l1, l2)).map { it.text }
        assertEquals(listOf("L1", "L2", "R1", "R2"), order)
    }

    @Test fun formularioSeLeePorFilas() {
        // Pares "Etiqueta: valor" de una línea: no son columnas de párrafo.
        val a = block("Nombre:", 100f, 100f, 200f, 1)
        val av = block("Juan", 600f, 102f, 200f, 1)
        val b = block("Fecha:", 100f, 160f, 200f, 1)
        val bv = block("06-10-2026", 600f, 158f, 200f, 1)
        val order = ReadingOrder.sort(listOf(bv, av, b, a)).map { it.text }
        assertEquals(listOf("Nombre:", "Juan", "Fecha:", "06-10-2026"), order)
    }

    @Test fun unaColumnaDeArribaAAbajo() {
        val a = block("A", 100f, 100f, 800f, 3)
        val b = block("B", 100f, 220f, 800f, 3)
        val c = block("C", 100f, 340f, 800f, 3)
        assertEquals(listOf("A", "B", "C"), ReadingOrder.sort(listOf(c, a, b)).map { it.text })
    }
}
