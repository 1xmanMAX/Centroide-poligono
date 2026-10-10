package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintClassifierTest {

    /** Cuaderno cuadriculado del usuario (valores medidos en usuario_cuaderno1). */
    private val notebook = PrintClassifier.Features(
        fullSide = 4400, rulingLines = 148, rulingDark = 26.6, rulingChroma = 11.9, rulingThick = 2.0,
        align = 0.03, table = false, denseText = false,
    )

    @Test
    fun gridNotebookGoesToHandwritingRegions() = assertTrue(PrintClassifier.isNotebook(notebook))

    @Test
    fun tableOrDenseTextIsNeverNotebook() {
        assertFalse(PrintClassifier.isNotebook(notebook.copy(table = true)))
        assertFalse(PrintClassifier.isNotebook(notebook.copy(denseText = true)))
    }

    @Test
    fun lowResolutionPageKeepsEverything() =
        // Documento pequeño fotografiado de lejos: los renglones de texto borrosos parecen rayado
        assertFalse(PrintClassifier.isNotebook(notebook.copy(fullSide = 600)))

    @Test
    fun darkOrThickLinesAreNotRuling() {
        assertFalse(PrintClassifier.isNotebook(notebook.copy(rulingDark = 70.0)))          // líneas de tabla
        assertFalse(PrintClassifier.isNotebook(notebook.copy(rulingThick = 3.8)))          // texto antiguo / manchas
        assertTrue(PrintClassifier.isNotebook(notebook.copy(rulingDark = 55.0, rulingChroma = 90.0)))  // cuadrícula de color
    }

    @Test
    fun alignedPrintedTextIsNotNotebook() =
        assertFalse(PrintClassifier.isNotebook(notebook.copy(align = 0.7)))

    @Test
    fun plainPaperWithoutRulingKeepsEverything() =
        assertFalse(PrintClassifier.isNotebook(notebook.copy(rulingLines = 3)))

    /** Cajas (x, y, w, h) de [n] letras sueltas sobre la misma línea base. */
    private fun printedLine(x0: Int, base: Int, n: Int, h: Int = 10): IntArray {
        val out = IntArray(n * 4)
        for (i in 0 until n) {
            val hh = if (i % 3 == 0) h + 4 else h      // ascendentes: misma base, más altas
            out[i * 4] = x0 + i * 9; out[i * 4 + 1] = base - hh; out[i * 4 + 2] = 7; out[i * 4 + 3] = hh
        }
        return out
    }

    @Test
    fun printedLettersAreAligned() {
        val a = PrintClassifier.baselineAlignment(printedLine(0, 100, 20) + printedLine(0, 140, 20), 10.0)
        assertTrue("alineación $a", a > 0.85)
    }

    @Test
    fun scatteredWordsAreNotAligned() {
        // Palabras manuscritas: separadas y con líneas base distintas
        val boxes = IntArray(12 * 4)
        for (i in 0 until 12) {
            boxes[i * 4] = i * 60; boxes[i * 4 + 1] = 100 + (i % 4) * 7; boxes[i * 4 + 2] = 40; boxes[i * 4 + 3] = 18
        }
        assertEquals(0.0, PrintClassifier.baselineAlignment(boxes, 18.0), 1e-9)
    }

    @Test
    fun fewComponentsGiveZero() = assertEquals(0.0, PrintClassifier.baselineAlignment(printedLine(0, 50, 5), 10.0), 1e-9)

    @Test
    fun chalkboardPolarity() {
        // Medidos: pizarras de tiza claro/oscuro >= 2.6; papel, recibos, tarjetas y pantallas <= 1.2
        assertTrue(PrintPolarity.isInverted(bright = 0.086, dark = 0.006))
        assertTrue(PrintPolarity.isInverted(bright = 0.095, dark = 0.035))
        assertFalse(PrintPolarity.isInverted(bright = 0.136, dark = 0.116))   // foto de pantalla con burbujas
        assertFalse(PrintPolarity.isInverted(bright = 0.007, dark = 0.002))   // casi vacía
        assertFalse(PrintPolarity.isInverted(bright = 0.02, dark = 0.2))      // papel
    }

    /** Página de texto impreso normal (valores típicos de una carta de RVL-CDIP). */
    private val page = PrintClassifier.PageFeatures(
        notebook = false, table = false, denseText = true, linesH = 1, linesV = 0, textComps = 1500, boxes = 1400,
        align = 0.7, letterRel = 0.008, inkFrac = 0.06, pictureFrac = 0.0, aspect = 1.3,
    )

    @Test
    fun plainPrintedPageIsText() = assertEquals(PrintClassifier.PageType.TEXT, PrintClassifier.pageType(page))

    @Test
    fun notebookWins() = assertEquals(PrintClassifier.PageType.NOTEBOOK, PrintClassifier.pageType(page.copy(notebook = true)))

    @Test
    fun largeInteriorPictureMakesIllustratedPage() =
        assertEquals(PrintClassifier.PageType.ILLUSTRATED, PrintClassifier.pageType(page.copy(pictureFrac = 0.2)))

    @Test
    fun scatteredUnalignedStrokesAreHandwriting() = assertEquals(
        PrintClassifier.PageType.HANDWRITTEN,
        PrintClassifier.pageType(page.copy(denseText = false, align = 0.15, boxes = 400)),
    )

    @Test
    fun handwritingInsideATableIsForm() = assertEquals(
        PrintClassifier.PageType.FORM,
        PrintClassifier.pageType(page.copy(denseText = false, align = 0.15, boxes = 400, table = true)),
    )

    @Test
    fun manyRulesMakeForm() {
        assertEquals(PrintClassifier.PageType.FORM, PrintClassifier.pageType(page.copy(linesH = 8, linesV = 2)))
        assertEquals(PrintClassifier.PageType.FORM, PrintClassifier.pageType(page.copy(linesH = 12, linesV = 0)))
    }

    @Test
    fun tallNarrowPageIsReceiptButWideStripIsNot() {
        assertEquals(PrintClassifier.PageType.RECEIPT, PrintClassifier.pageType(page.copy(aspect = 3.0)))
        assertEquals(PrintClassifier.PageType.TEXT, PrintClassifier.pageType(page.copy(aspect = 1.0 / 3.0)))
    }
}
