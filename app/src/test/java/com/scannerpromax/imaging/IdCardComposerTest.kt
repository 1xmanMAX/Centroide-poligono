package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Tamaño del DNI en la hoja»: las dos caras y su separación caben en el A4 vertical en todas las opciones. */
class IdCardComposerTest {

    private val landscape = 1600 to 1010
    private val portrait = 1010 to 1600

    private fun check(rects: List<IntArray>) {
        for (r in rects) {
            assertTrue(r.contentToString(), r[0] >= IdCardComposer.MARGIN && r[1] >= IdCardComposer.MARGIN)
            assertTrue(r.contentToString(), r[2] <= IdCardComposer.PAGE_W - IdCardComposer.MARGIN && r[3] <= IdCardComposer.PAGE_H - IdCardComposer.MARGIN)
        }
        for (i in 1 until rects.size) assertTrue("separación", rects[i][1] - rects[i - 1][3] >= IdCardComposer.GAP)
    }

    @Test fun tamanosHorizontales() {
        val real = IdCardComposer.layout(listOf(landscape, landscape), IdCardSize.REAL.scale)
        assertEquals(IdCardComposer.CARD_LONG, real[0][2] - real[0][0])
        assertEquals(IdCardComposer.CARD_SHORT, real[0][3] - real[0][1])
        val big = IdCardComposer.layout(listOf(landscape, landscape), IdCardSize.LARGE.scale)
        assertEquals((IdCardComposer.CARD_LONG * 1.4).toInt().toDouble(), (big[0][2] - big[0][0]).toDouble(), 1.0)
        val xl = IdCardComposer.layout(listOf(landscape, landscape), IdCardSize.XLARGE.scale)
        assertEquals((IdCardComposer.CARD_LONG * 1.75).toDouble(), (xl[0][2] - xl[0][0]).toDouble(), 1.0)
        for (r in listOf(real, big, xl)) check(r)
        // centradas
        assertEquals(IdCardComposer.PAGE_W.toDouble(), (xl[0][0] + xl[0][2]).toDouble(), 1.0)
        // el valor por defecto es «Grande»
        assertEquals(IdCardSize.LARGE, IdCardSize.DEFAULT)
    }

    @Test fun verticalesYUnaSolaCara_siempreCaben() {
        for (s in IdCardSize.entries) {
            check(IdCardComposer.layout(listOf(portrait, portrait), s.scale))
            check(IdCardComposer.layout(listOf(portrait, landscape), s.scale))
            check(IdCardComposer.layout(listOf(landscape), s.scale))
        }
        // dos caras verticales a 175 % no caben: se reduce la escala hasta que quepan
        val xl = IdCardComposer.layout(listOf(portrait, portrait), IdCardSize.XLARGE.scale)
        assertTrue(xl[0][3] - xl[0][1] < IdCardComposer.CARD_LONG * 1.75)
        assertTrue(xl[0][3] - xl[0][1] > IdCardComposer.CARD_LONG * 1.4)
    }
}
