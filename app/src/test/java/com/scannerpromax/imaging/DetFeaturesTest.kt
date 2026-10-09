package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetFeaturesTest {

    private val w = 200; private val h = 160

    /** Imagen sintética: fondo [bg] y un rectángulo (x0,y0)-(x1,y1) de luminancia [fg]; crominancia neutra o la dada. */
    private fun img(bg: Int, fg: Int, x0: Int, y0: Int, x1: Int, y1: Int): ByteArray {
        val l = ByteArray(w * h) { bg.toByte() }
        for (y in y0 until y1) for (x in x0 until x1) l[y * w + x] = fg.toByte()
        return l
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    @Test fun medianaYPercentilComoNumpy() {
        assertEquals(2.5, DetFeatures.median(doubleArrayOf(4.0, 1.0, 3.0, 2.0), 4), 1e-9)
        assertEquals(3.0, DetFeatures.median(doubleArrayOf(5.0, 1.0, 3.0), 3), 1e-9)
        // numpy.percentile([1,2,3,4,5], 25) = 2
        assertEquals(2.0, DetFeatures.percentile(doubleArrayOf(5.0, 4.0, 3.0, 2.0, 1.0), 5, 0.25), 1e-9)
        assertEquals(1.75, DetFeatures.percentile(doubleArrayOf(1.0, 2.0, 3.0, 4.0), 4, 0.25), 1e-9)
    }

    @Test fun hojaSobreMesaSeparaMateriales() {
        val l = img(60, 220, 40, 30, 160, 130)
        val b = DetFeatures.bands(quad(40f, 30f, 160f, 130f), l, null, null, w, h)
        assertEquals(4, b.nReal)
        assertEquals(160.0, b.sepMin, 1e-9)
        assertEquals(0.0, b.devL, 1e-9)
        assertFalse(b.outWhite)
    }

    @Test fun cuadrilateroQueMezclaHojaYMesaTieneBandasInterioresDistintas() {
        // La hoja ocupa sólo la mitad izquierda del cuadrilátero: la banda interior del lado derecho es mesa
        val l = img(60, 220, 40, 30, 100, 130)
        val b = DetFeatures.bands(quad(40f, 30f, 160f, 130f), l, null, null, w, h)
        assertTrue(b.devL > 100)
        assertTrue(b.sepMin < 10)
    }

    @Test fun marcoImpresoEnEscaneoTienePapelBlancoFuera() {
        // Página blanca saturada con un recuadro gris dentro (tabla): fuera de sus lados hay papel blanco
        val l = img(252, 200, 50, 40, 150, 120)
        val b = DetFeatures.bands(quad(50f, 40f, 150f, 120f), l, null, null, w, h)
        assertTrue(b.outWhite)
    }

    @Test fun ladosPegadosAlMarcoNoCuentan() {
        // Documento que se sale por arriba y por la izquierda: esos lados no tienen muestras exteriores
        val l = img(60, 220, 0, 0, 120, 100)
        val b = DetFeatures.bands(quad(0f, 0f, 120f, 100f), l, null, null, w, h)
        assertEquals(2, b.nReal)
        assertEquals(160.0, b.sepMin, 1e-9)
    }

    @Test fun interiorPercentilYMediana() {
        val l = img(30, 200, 40, 30, 160, 130)
        val g = DetFeatures.interior(quad(40f, 30f, 160f, 130f), l, w, h)
        assertEquals(200.0, g[0], 1e-9); assertEquals(200.0, g[1], 1e-9)
    }

    @Test fun contencionConTolerancia() {
        val outer = quad(10f, 10f, 190f, 150f)
        assertTrue(DetFeatures.quadInside(quad(20f, 20f, 100f, 100f), outer))
        assertTrue(DetFeatures.quadInside(quad(8f, 8f, 100f, 100f), outer))     // 2 px fuera: tolerado
        assertFalse(DetFeatures.quadInside(quad(0f, 0f, 100f, 100f), outer))
        // también con la orientación contraria de los vértices
        val ccw = floatArrayOf(10f, 10f, 10f, 150f, 190f, 150f, 190f, 10f)
        assertTrue(DetFeatures.insideTol(ccw, 100.0, 80.0, 0.0))
        assertFalse(DetFeatures.insideTol(ccw, 195.0, 80.0, 3.0))
    }

    @Test fun puntuacionPrefiereBordeNitidoYMaterialHomogeneo() {
        val good = DetFeatures.Bands(150.0, 150.0, 5.0, 2.0, false, 4)
        val mixed = DetFeatures.Bands(5.0, 60.0, 120.0, 30.0, false, 4)
        val a = DetFeatures.rankScore(0.3, 0.05, 0.95, 0.0, 1.0, true, good, 190.0, 200.0, 120.0, 0.0)
        val b = DetFeatures.rankScore(0.5, 0.05, 0.85, 0.0, 1.0, true, mixed, 120.0, 160.0, 120.0, 0.0)
        assertTrue(a > b + DetFeatures.SWAP_MARGIN)
        // tramos sobre el marco de la imagen penalizan
        val c = DetFeatures.rankScore(0.3, 0.05, 0.95, 0.3, 1.0, true, good, 190.0, 200.0, 120.0, 0.0)
        assertTrue(c < a)
    }
}
