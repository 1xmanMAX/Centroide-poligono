package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintToneTest {

    @Test
    fun darkInkKeepsBlackPoint() =
        // Trazo típico de 180 niveles de contraste sobre papel 230: ya es oscuro, el negro no cambia
        assertEquals(20.0, PrintTone.faintBlack(c90 = 180.0, white = 230.0, black = 20.0, target = 0.15), 1e-9)

    @Test
    fun faintInkRaisesBlackPoint() {
        // Recibo desvaído: el trazo típico sólo baja 100 niveles -> el negro sube hasta dejarlo al 15 % del blanco
        val b = PrintTone.faintBlack(c90 = 100.0, white = 230.0, black = 20.0, target = 0.15)
        assertEquals(230.0 - 100.0 / 0.85, b, 1e-9)
        assertTrue(b > 20.0)
    }

    @Test
    fun faintBlackNeverCollapsesRange() =
        // Casi sin trazos: se conserva un rango mínimo (el papel no se vuelve tinta)
        assertEquals(230.0 - PrintTone.MIN_RANGE, PrintTone.faintBlack(c90 = 5.0, white = 230.0, black = 20.0, target = 0.15), 1e-9)

    @Test
    fun castOnlyWhenInkSharesTheLightHue() {
        assertEquals(1.0, PrintTone.castSaturation(doubleArrayOf(5.0, 0.9)), 1e-9)      // luz casi blanca
        assertEquals(1.0, PrintTone.castSaturation(doubleArrayOf(30.0, -0.8)), 1e-9)    // bolígrafo azul, papel amarillo
        assertEquals(0.25, PrintTone.castSaturation(doubleArrayOf(30.0, 0.9)), 1e-9)    // luz verde, tinta verdosa
    }

    @Test
    fun thinBorderBandIsOutside() {
        assertTrue(PrintTone.isOutsideBand(0, 50, 1000, 1200))          // banda de mesa de 50 px
        assertFalse(PrintTone.isOutsideBand(0, 400, 1000, 1200))        // foto a sangre que entra un tercio
        assertFalse(PrintTone.isOutsideBand(5, 50, 1000, 1200))         // no toca el borde
    }

    @Test
    fun deepPlainBackgroundVersusContentBlock() {
        assertTrue(PrintTone.isPlainBackground(0.002))                   // mesa negra lisa alrededor del libro
        assertFalse(PrintTone.isPlainBackground(0.08))                   // bloque negro de anuncio con texto blanco
    }
}
