package com.scannerpromax.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sin

/** «Encuadre IA» en vivo: filtro One Euro, IoU de convexos, acuerdo red/clásico y conversión YUV → ARGB girada. */
class LiveNeuralTest {

    @Test fun oneEuroQuietoFiltraYEnMovimientoSigue() {
        val f = OneEuroFilter(0.3, 8.0)
        // Señal quieta con temblor de ±0,01: la salida tiembla mucho menos
        var maxDev = 0.0
        for (i in 0 until 60) {
            val y = f.filter(0.5 + 0.01 * sin(i * 2.1), i * 0.3)
            if (i > 5) maxDev = maxOf(maxDev, abs(y - 0.5))
        }
        assertTrue("temblor residual $maxDev", maxDev < 0.006)
        // Salto grande: alcanza el nuevo valor en pocas muestras (beta sube el corte)
        f.reset()
        f.filter(0.2, 0.0)
        var y = 0.0
        for (i in 1..4) y = f.filter(0.8, i * 0.3)
        assertEquals(0.8, y, 0.05)
    }

    @Test fun iouDeConvexos() {
        val a = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertEquals(1.0, LiveNeuralTracker.convexIou(a, a), 1e-9)
        val b = floatArrayOf(0.5f, 0f, 1.5f, 0f, 1.5f, 1f, 0.5f, 1f)
        assertEquals(1.0 / 3, LiveNeuralTracker.convexIou(a, b), 1e-6)
        val c = floatArrayOf(2f, 2f, 3f, 2f, 3f, 3f, 2f, 3f)
        assertEquals(0.0, LiveNeuralTracker.convexIou(a, c), 1e-9)
        // Sentido contrario (antihorario) da lo mismo
        val br = floatArrayOf(0.5f, 0f, 0.5f, 1f, 1.5f, 1f, 1.5f, 0f)
        assertEquals(1.0 / 3, LiveNeuralTracker.convexIou(a, br), 1e-6)
        // No convexo → 0
        assertEquals(0.0, LiveNeuralTracker.convexIou(floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f), a), 1e-9)
    }

    @Test fun acuerdoRedClasico() {
        val t = LiveNeuralTracker(300)
        val cls = floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f)
        assertNull("sin inferencias: desconocido", t.agreement(cls, 1f, 1000))
        assertTrue(t.due(1000)); assertFalse(t.due(1100))
        t.update(NeuralQuad(floatArrayOf(41f, 39f, 160f, 41f, 161f, 159f, 40f, 160f), 0.9f), 200, 200, 1000)
        assertEquals(true, t.agreement(cls, 1f, 1100))
        t.update(NeuralQuad(floatArrayOf(0f, 0f, 60f, 0f, 60f, 60f, 0f, 60f), 0.9f), 200, 200, 1300)
        assertEquals(false, t.agreement(cls, 1f, 1350))
        t.update(NeuralQuad(floatArrayOf(0f, 0f, 60f, 0f, 60f, 60f, 0f, 60f), 0.1f), 200, 200, 1600)
        assertEquals("la red no ve documento", false, t.agreement(cls, 1f, 1650))
        assertNull("inferencia vieja", t.agreement(cls, 1f, 1600 + LiveNeuralTracker.STALE_MS + 1))
    }

    @Test fun rotacionYConversionYuv() {
        // 3x2: 0 1 2 / 3 4 5
        val src = IntArray(6) { it }
        assertArrayEquals(intArrayOf(3, 0, 4, 1, 5, 2), LiveNeuralTracker.rotate(src, 3, 2, 90).third)
        assertArrayEquals(intArrayOf(5, 4, 3, 2, 1, 0), LiveNeuralTracker.rotate(src, 3, 2, 180).third)
        assertArrayEquals(intArrayOf(2, 5, 1, 4, 0, 3), LiveNeuralTracker.rotate(src, 3, 2, 270).third)
        // YUV gris (U=V=128): ARGB gris con Y medio 2x2; girado 90 → dimensiones intercambiadas
        val w = 8; val h = 4
        val y = ByteBuffer.wrap(ByteArray(w * h) { (it % w * 20).toByte() })
        val uv = ByteBuffer.wrap(ByteArray(w / 2 * h / 2) { 128.toByte() })
        val (rw, rh, px) = LiveNeuralTracker.yuvToArgbHalf(y, w, uv, uv.duplicate(), w / 2, 1, w, h, 90)
        assertEquals(2, rw); assertEquals(4, rh)
        val g = px[0] and 0xFF
        assertEquals(10, g)   // columna 0 del original: (0+20+0+20)/4
        assertEquals(g, (px[0] shr 8) and 0xFF); assertEquals(g, (px[0] shr 16) and 0xFF)
    }
}
