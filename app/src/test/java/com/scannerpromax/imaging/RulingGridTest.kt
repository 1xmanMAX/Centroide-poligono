package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Reconstrucción de la cuadrícula conservada ([RulingGrid]) y reglas de la limpieza de bordes ([EdgeClean.decide]). */
class RulingGridTest {

    private val w = 600
    private val h = 400

    /** Máscara con rectas horizontales cada [step] px (2 px de grosor) y huecos opcionales (letras encima / sombra). */
    private fun hMask(step: Int, skipRows: Set<Int> = emptySet(), hole: IntArray? = null): ByteArray {
        val m = ByteArray(w * h)
        var y = step
        while (y < h - 2) {
            if (y !in skipRows) for (x in 20 until w - 20) {
                if (hole != null && x in hole[0] until hole[1] && y in hole[2] until hole[3]) continue
                m[y * w + x] = 1; m[(y + 1) * w + x] = 1
            }
            y += step
        }
        return m
    }

    @Test
    fun periodIsTheModeDespiteSpuriousGaps() {
        val gaps = List(40) { 30.0 + (it % 3) - 1 } + listOf(6.0, 7.5, 9.0, 11.0, 61.0, 90.0)
        val p = RulingGrid.regularPeriod(gaps)
        assertNotNull(p)
        assertEquals(30.0, p!!, 1.5)
        assertNull(RulingGrid.regularPeriod(listOf(5.0, 17.0, 33.0, 58.0, 71.0, 140.0)))
    }

    @Test
    fun straightLinesAreFoundAcrossGaps() {
        // Hueco de 120 px en medio de las rectas (escritura densa encima)
        val lines = RulingGrid.extract(hMask(25, hole = intArrayOf(250, 370, 0, h)), w, h, horizontal = true)
        assertEquals(15, lines.size)
        for (l in lines) {
            assertTrue("recta corta: ${l.start}..${l.end}", l.start <= 40 && l.end >= w - 40)
            val c = l.meanCross()
            for (q in 0 until l.size) assertTrue(abs(l.cross[q] - c) < 1.0)
        }
    }

    @Test
    fun missingLinesOfARegularGridAreInterpolated() {
        // Faltan dos rectas seguidas (sombra) en una rejilla de paso 25
        val lines = RulingGrid.extract(hMask(25, skipRows = setOf(150, 175)), w, h, horizontal = true)
        val pos = lines.map { it.meanCross() }.sorted()
        assertTrue(pos.any { abs(it - 150.5) < 1.5 })
        assertTrue(pos.any { abs(it - 175.5) < 1.5 })
    }

    @Test
    fun shortLinesAreExtendedWhereTheOtherFamilyContinues() {
        // Horizontales cortadas en x >= 300 (sombra) pero verticales por toda la página
        val mh = hMask(25, hole = intArrayOf(300, w, 0, h))
        val mv = ByteArray(w * h)
        var x = 25
        while (x < w - 20) { for (y in 20 until h - 20) { mv[y * w + x] = 1; mv[y * w + x + 1] = 1 }; x += 25 }
        val g = RulingGrid.extractGrid(mh, mv, w, h)
        assertTrue(g.h.size >= 14)
        assertTrue("las horizontales llegan a la última vertical", g.h.count { it.end >= 560 } >= g.h.size - 1)
    }

    @Test
    fun dedupeMergesParallelCopies() {
        val a = RulingGrid.Line(doubleArrayOf(0.0, 100.0, 200.0), doubleArrayOf(50.0, 50.0, 50.0))
        val b = RulingGrid.Line(doubleArrayOf(150.0, 300.0), doubleArrayOf(51.5, 51.5), observed = 1)
        val c = RulingGrid.Line(doubleArrayOf(0.0, 300.0), doubleArrayOf(80.0, 80.0))
        val out = RulingGrid.dedupe(listOf(a, b, c), 30.0)
        assertEquals(2, out.size)
        assertTrue(out.any { it.start <= 0.0 && it.end >= 300.0 && abs(it.meanCross() - 50.0) < 1.0 })
    }

    // ----------------------------------------------------------------------------------------------- EdgeClean

    private val page = EdgeClean.PageScale(letter = 30.0, halfStroke = 2.0, side = 1600, total = 1600L * 1200)

    @Test
    fun bandAlongTheEdgeIsRemoved() {
        // Sombra del canto: 600 px a lo largo del borde izquierdo, 12 de ancho, pegada en casi toda su longitud
        val c = EdgeClean.EdgeComp(along = 600, across = 12, hug = 0.9, thickFrac = 0.1, area = 6000, fill = 0.8,
            corner = false, acrossDim = 1600, alongDim = 1200)
        assertTrue(EdgeClean.decide(c, page))
    }

    @Test
    fun letterCutByTheEdgeIsKept() {
        // "I" de "Informe" cortada por el borde: corta (una letra) y de trazo fino
        val c = EdgeClean.EdgeComp(along = 34, across = 6, hug = 1.0, thickFrac = 0.0, area = 180, fill = 0.9,
            corner = false, acrossDim = 1600, alongDim = 1200)
        assertFalse(EdgeClean.decide(c, page))
        // Palabra manuscrita que roza el borde en un punto
        val w = EdgeClean.EdgeComp(along = 40, across = 150, hug = 0.1, thickFrac = 0.02, area = 900, fill = 0.15,
            corner = false, acrossDim = 1600, alongDim = 1200)
        assertFalse(EdgeClean.decide(w, page))
    }

    @Test
    fun lineOfTextTouchingTheEdgeIsKept() {
        // Renglón impreso pegado al borde inferior (tira muy alargada): largo y estrecho como una franja, pero pegado
        // al borde a tramos (uno por letra)
        val c = EdgeClean.EdgeComp(along = 900, across = 25, hug = 0.6, thickFrac = 0.0, area = 9000, fill = 0.4,
            corner = false, acrossDim = 400, alongDim = 1600, hugRuns = 80)
        assertFalse(EdgeClean.decide(c, page))
        assertTrue(EdgeClean.decide(c.copy(hugRuns = 2), page))
    }

    @Test
    fun massiveBlobAndCornerWedgeAreRemoved() {
        val blob = EdgeClean.EdgeComp(along = 200, across = 150, hug = 0.6, thickFrac = 0.7, area = 20000, fill = 0.7,
            corner = false, acrossDim = 1600, alongDim = 1200)
        assertTrue(EdgeClean.decide(blob, page))
        val wedge = EdgeClean.EdgeComp(along = 60, across = 60, hug = 0.2, thickFrac = 0.4, area = 1500, fill = 0.42,
            corner = true, acrossDim = 1600, alongDim = 1200)
        assertTrue(EdgeClean.decide(wedge, page))
        // Un número de página fino en la esquina no es una cuña
        assertFalse(EdgeClean.decide(wedge.copy(thickFrac = 0.05, along = 30, across = 40, area = 500), page))
        // Una foto enorme pegada al borde no se toca
        val photo = blob.copy(area = (0.4 * page.total).toInt(), along = 900, across = 700)
        assertFalse(EdgeClean.decide(photo, page))
    }
}
