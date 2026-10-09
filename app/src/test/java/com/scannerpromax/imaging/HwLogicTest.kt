package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lógica pura de la segmentación de la escritura: [HwBinding] (espirales) y [HwStats] (estadísticas, dos tintas). */
class HwLogicTest {

    // ----------------------------------------------------------------------------------------------- HwBinding

    /** Fila de [n] anillas cada [step] px, de [w] px de ancho perpendicular y [h] px a lo largo. */
    private fun rings(n: Int, step: Double = 60.0, w: Double = 100.0, h: Double = 30.0) =
        Triple(List(n) { 50.0 + it * step }, List(n) { w }, List(n) { h })

    @Test
    fun regularRowOfRingsIsABinding() {
        val (al, ac, ext) = rings(15)
        assertTrue(HwBinding.isRingRow(al, ac, ext, acrossDim = 1500.0))
    }

    @Test
    fun ringRowWithLettersInsideIsRejected() {
        val (al, ac, ext) = rings(15)
        assertFalse(HwBinding.isRingRow(al, ac, ext, acrossDim = 1500.0, clean = false))
    }

    @Test
    fun tooFewRingsAreRejected() {
        val (al, ac, ext) = rings(6)
        assertFalse(HwBinding.isRingRow(al, ac, ext, acrossDim = 1500.0))
    }

    @Test
    fun wideBandIsNotABinding() {
        // Anillas de 300 px en una página de 1500 (20 %): bloques de texto, no una espiral
        val (al, ac, ext) = rings(15, w = 300.0)
        assertFalse(HwBinding.isRingRow(al, ac, ext, acrossDim = 1500.0))
    }

    @Test
    fun elongatedAlongTheBandIsNotARing() {
        // Más largas a lo largo de la banda que anchas: palabras de un renglón, no aros que cruzan la banda
        val (al, ac, ext) = rings(15, step = 200.0, w = 60.0, h = 150.0)
        assertFalse(HwBinding.isRingRow(al, ac, ext, acrossDim = 1500.0))
    }

    @Test
    fun strictModeNeedsVeryRegularSpacing() {
        // Paso irregular (alterna 40 y 90 px): basta en modo normal, no en el estricto (banda paralela a los renglones)
        val al = ArrayList<Double>(); var p = 0.0
        repeat(14) { al.add(p); p += if (it % 2 == 0) 40.0 else 90.0 }
        val ac = List(al.size) { 100.0 }; val ext = List(al.size) { 30.0 }
        assertTrue(HwBinding.isRingRow(al, ac, ext, 1500.0))
        assertFalse(HwBinding.isRingRow(al, ac, ext, 1500.0, strict = true))
        val (al2, ac2, ext2) = rings(14)
        assertTrue(HwBinding.isRingRow(al2, ac2, ext2, 1500.0, strict = true))
    }

    // ----------------------------------------------------------------------------------------------- HwStats

    @Test
    fun weightedPercentileFollowsTheWeights() {
        val v = listOf(1.0, 2.0, 10.0)
        assertEquals(1.0, HwStats.weightedPercentile(v, listOf(1.0, 1.0, 1.0), 0.3)!!, 1e-9)
        assertEquals(2.0, HwStats.weightedPercentile(v, listOf(1.0, 1.0, 1.0), 0.6)!!, 1e-9)
        // Un valor con mucho peso domina
        assertEquals(10.0, HwStats.weightedPercentile(v, listOf(1.0, 1.0, 20.0), 0.2)!!, 1e-9)
        assertNull(HwStats.weightedPercentile(emptyList(), emptyList(), 0.5))
        assertNull(HwStats.weightedPercentile(v, listOf(0.0, 0.0, 0.0), 0.5))
    }

    @Test
    fun showThroughIsDetectedAsTwoInks() {
        // Tinta principal ~200 (60 % del área) + transparencia del reverso ~35 (40 %)
        val s = List(30) { 190.0 + it % 20 } + List(20) { 30.0 + it % 10 }
        val a = List(50) { 100.0 }
        val r = HwStats.twoInk(s, a)
        assertTrue(r.detected)
        assertTrue(r.front in 190.0..210.0)
        assertEquals(0.4, r.faintFrac, 0.01)
    }

    @Test
    fun continuousRangeOfInkIsNotTwoInks() {
        // Lápiz de presión variable: fuerzas repartidas de 40 a 200 sin dos poblaciones separadas
        val s = List(50) { 40.0 + it * 3.2 }
        val r = HwStats.twoInk(s, List(50) { 100.0 })
        assertFalse(r.detected)
    }

    @Test
    fun fewFaintMarksAreNotTwoInks() {
        // Unas pocas motas claras (10 % del área) junto a la escritura: no se toca nada
        val s = List(45) { 200.0 } + List(5) { 30.0 }
        assertFalse(HwStats.twoInk(s, List(50) { 100.0 }).detected)
    }

    @Test
    fun faintPageAloneIsNotTwoInks() {
        // Página entera a lápiz claro: la "tinta principal" es el propio lápiz (nivel < 40) -> no se toca
        val s = List(50) { 25.0 + it % 10 }
        assertFalse(HwStats.twoInk(s, List(50) { 100.0 }).detected)
    }

    @Test
    fun chalkboardIsLightOnDark() {
        // Pizarra: 4 % de trazos claros, casi nada oscuro
        assertTrue(HwStats.isLightOnDark(bright = 0.04, dark = 0.002))
        assertTrue(HwStats.isLightOnDark(bright = 0.03, dark = 0.0))
    }

    @Test
    fun paperIsNotLightOnDark() {
        // Papel: la escritura es lo oscuro; brillos sueltos o una foto con detalles claros no bastan
        assertFalse(HwStats.isLightOnDark(bright = 0.005, dark = 0.04))
        assertFalse(HwStats.isLightOnDark(bright = 0.05, dark = 0.045))   // pantalla con burbujas: mixto
        assertFalse(HwStats.isLightOnDark(bright = 0.0004, dark = 0.0001)) // casi sin contenido
        assertFalse(HwStats.isLightOnDark(bright = 0.018, dark = 0.003))   // detalles claros de una foto oscura
    }
}
