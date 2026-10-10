package com.scannerpromax.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryDitherTest {
    private fun whiteFraction(lum: Int, darkNear: Boolean): Double {
        var n = 0
        for (y in 0 until 4) for (x in 0 until 4) if (BinaryDither.white(lum, x, y, darkNear)) n++
        return n / 16.0
    }

    @Test fun tintaYPapel() {
        assertFalse(BinaryDither.white(40, 0, 0, false))
        assertFalse(BinaryDither.white(149, 3, 1, true))
        assertTrue(BinaryDither.white(255, 0, 0, false))
        assertTrue(BinaryDither.white(BinaryDither.LIGHT_MAX, 0, 0, false))
    }

    @Test fun cuadriculaGrisClaraSeTrama() {
        // la cuadrícula conservada en B/N (tono ~195) no desaparece: parte de sus píxeles salen negros
        val f = whiteFraction(195, false)
        assertTrue("fracción $f", f > 0.3 && f < 0.75)
    }

    @Test fun bordeDeLetraSinTrama() {
        // junto a tinta oscura, umbral fijo (sin motas alrededor de las letras)
        assertEquals(1.0, whiteFraction(195, true), 0.0)
    }
}
