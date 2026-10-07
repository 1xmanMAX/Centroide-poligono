package com.scannerpromax.imaging

import com.scannerpromax.imaging.BoxGrouping.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxGroupingTest {

    /** Letras de una palabra: cajas de 20 px de alto separadas 6 px. */
    private fun word(x0: Int, y0: Int, letters: Int, w: Int = 14, gap: Int = 6, h: Int = 20) =
        (0 until letters).map { Box(x0 + it * (w + gap), y0, x0 + it * (w + gap) + w, y0 + h) }

    @Test
    fun lettersOfALineAreGroupedTogether() {
        val boxes = word(10, 100, 5) + word(140, 102, 4)   // dos palabras separadas 30 px (< gapX)
        val groups = BoxGrouping.group(boxes, gapX = 36, gapY = 8)
        assertEquals(1, groups.size)
        assertEquals(boxes.size, groups[0].second.size)
        assertEquals(Box(10, 100, 140 + 3 * 20 + 14, 122), groups[0].first)
    }

    @Test
    fun distantLinesStaySeparate() {
        val l1 = word(10, 100, 5)
        val l2 = word(10, 160, 5)       // 40 px por debajo: otra línea
        val groups = BoxGrouping.group(l1 + l2, gapX = 36, gapY = 8)
        assertEquals(2, groups.size)
        // Orden de lectura: primero la de arriba
        assertTrue(groups[0].first.y0 < groups[1].first.y0)
    }

    @Test
    fun accentAboveLetterJoinsByProximity() {
        val letter = Box(50, 100, 64, 120)
        val accent = Box(54, 92, 60, 96)   // 4 px por encima, sin solape vertical
        val groups = BoxGrouping.group(listOf(letter, accent), gapX = 36, gapY = 8)
        assertEquals(1, groups.size)
    }

    @Test
    fun noVerticalOverlapMeansDifferentLines() {
        val a = Box(0, 0, 20, 20)
        val b = Box(25, 30, 45, 50)   // cerca en x pero 10 px por debajo (> gapY)
        assertFalse(BoxGrouping.linked(a, b, gapX = 36, gapY = 8))
    }

    @Test
    fun wideBoxLinksToFarComponentsByOverlap() {
        // Un subrayado largo que empieza antes que la palabra: comparte la línea con la última letra
        val underline = Box(0, 118, 400, 122)
        val letter = Box(380, 100, 394, 121)
        assertTrue(BoxGrouping.linked(underline, letter, gapX = 36, gapY = 8))
        assertEquals(1, BoxGrouping.group(listOf(underline, letter), 36, 8).size)
    }

    @Test
    fun overlappingPaddedBoxesAreMerged() {
        val merged = BoxGrouping.mergeOverlapping(listOf(Box(0, 0, 10, 10), Box(8, 8, 20, 20), Box(100, 100, 110, 110), Box(19, 0, 30, 5)))
        assertEquals(2, merged.size)
        assertTrue(merged.contains(Box(0, 0, 30, 20)))
        assertTrue(merged.contains(Box(100, 100, 110, 110)))
    }

    @Test
    fun padIsClampedToImage() {
        assertEquals(Box(0, 0, 25, 30), Box(2, 3, 20, 25).pad(5, 25, 30))
    }

    @Test
    fun verticalTextIsDetected() {
        // Mismas palabras, giradas 90°: las letras se apilan en columnas
        val horiz = word(10, 100, 6) + word(10, 160, 6) + word(10, 220, 6)
        val vert = horiz.map { BoxGrouping.transpose(it) }
        assertFalse(BoxGrouping.isVerticalText(horiz, near = 8))
        assertTrue(BoxGrouping.isVerticalText(vert, near = 8))
        // Agrupar en vertical = agrupar las cajas traspuestas
        val g = BoxGrouping.group(vert.map { BoxGrouping.transpose(it) }, 36, 8)
        assertEquals(3, g.size)
    }

    @Test
    fun percentileOfEmptyListIsDefault() {
        assertEquals(7, BoxGrouping.percentile(IntArray(0), 0.5, 7))
        assertEquals(3, BoxGrouping.percentile(intArrayOf(5, 1, 3, 2, 4), 0.5, 0))
    }
}
