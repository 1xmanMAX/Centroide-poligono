package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowMathTest {

    @Test
    fun fuerzaDeLaSombra() {
        // Luz plena y viñeteo leve: sin sombra
        assertEquals(0.0, ShadowMath.strength(1.0), 1e-9)
        assertEquals(0.0, ShadowMath.strength(0.95), 1e-9)
        // Sombra de mano (luz al 30-80 %): plena
        assertEquals(1.0, ShadowMath.strength(0.80), 1e-9)
        assertEquals(1.0, ShadowMath.strength(0.30), 1e-9)
        // Penumbra: rampa monótona
        val a = ShadowMath.strength(0.90); val b = ShadowMath.strength(0.85)
        assertTrue(a in 0.01..0.99 && b in 0.01..0.99 && b > a)
    }

    @Test
    fun refuerzoDelColorCalido() {
        // Fuera de la sombra no cambia nada
        assertEquals(1.0, ShadowMath.warmGain(1.0), 1e-9)
        // Sombra típica (luz 0.55): ~x1.6 (estrella amarilla 56-60 en luz frente a 22-35 en sombra)
        assertEquals(1.6, ShadowMath.warmGain(0.55), 0.02)
        // Acotado: nunca más de MAX_CHROMA_GAIN aunque la sombra sea muy oscura, y monótono
        assertEquals(ShadowMath.MAX_CHROMA_GAIN, ShadowMath.warmGain(0.1), 1e-9)
        assertTrue(ShadowMath.warmGain(0.8) < ShadowMath.warmGain(0.7))
    }

    @Test
    fun residuoDeLuzValido() {
        // Papel en sombra con residuo leve y casi neutro: peso 1
        assertEquals(1.0, ShadowMath.residualWeight(0.95, 0.03), 1e-9)
        assertEquals(1.0, ShadowMath.residualWeight(1.10, 0.10), 1e-9)
        // Bloque oscuro (tinta gruesa, foto) o de color (resaltador): no es papel -> se rellena con el entorno
        assertEquals(0.0, ShadowMath.residualWeight(0.50, 0.02), 1e-9)
        assertEquals(0.0, ShadowMath.residualWeight(0.95, 0.30), 1e-9)
        assertEquals(0.0, ShadowMath.residualWeight(1.50, 0.02), 1e-9)
        // Transición suave (sin bordes nuevos donde el residuo cruza el umbral)
        val m = ShadowMath.residualWeight(0.66, 0.02)
        assertTrue(m > 0.0 && m < 1.0)
        assertFalse(ShadowMath.residualWeight(0.62, 0.02) > ShadowMath.residualWeight(0.70, 0.02))
    }

    @Test
    fun ruidoDesdePasoAlto() {
        // Ruido gaussiano σ: E|Y - Gauss(Y,1)| ≈ 0.64·σ
        assertEquals(5.0, ShadowMath.noiseSigma(0.64 * 5.0), 0.05)
        assertEquals(0.0, ShadowMath.noiseSigma(0.0), 1e-9)
    }

    @Test
    fun pesoDelColor() {
        // El ruido de color del papel (|croma| < 4) no se refuerza; el color claro (>= 10) sí, del todo
        assertEquals(0.0, ShadowMath.chromaWeight(2.0), 1e-9)
        assertEquals(1.0, ShadowMath.chromaWeight(12.0), 1e-9)
        assertEquals(0.5, ShadowMath.chromaWeight(7.0), 1e-9)
    }

    @Test
    fun nucleoProfundo() {
        // Papel en una sombra profunda de móvil (residuo 0.3): fuera del núcleo no es papel, en el núcleo sí
        assertEquals(0.0, ShadowMath.residualWeight(0.30, 0.02), 1e-9)
        assertEquals(1.0, ShadowMath.residualWeight(0.30, 0.02, deep = 1.0), 1e-9)
        // Bloque de color: tampoco en el núcleo
        assertEquals(0.0, ShadowMath.residualWeight(0.30, 0.30, deep = 1.0), 1e-9)
        // Cota inferior del residuo
        assertEquals(0.6, ShadowMath.residualFloor(0.0), 1e-9)
        assertEquals(0.15, ShadowMath.residualFloor(1.0), 1e-9)
        assertEquals(0.15, ShadowMath.residualFloor(3.0), 1e-9)
    }

    @Test
    fun sombraProyectada() {
        // Entra desde el borde y no es diminuta, o es muy grande: sombra; mancha pequeña interior: no
        assertTrue(ShadowMath.isCastShadow(0.02, touchesEdge = true))
        assertFalse(ShadowMath.isCastShadow(0.002, touchesEdge = true))
        assertFalse(ShadowMath.isCastShadow(0.05, touchesEdge = false))
        assertTrue(ShadowMath.isCastShadow(0.2, touchesEdge = false))
    }

    @Test
    fun cromaSinSaturar() {
        // Rojo vivo (|Cr - 128| = 110): casi sin margen -> no gira a naranja al reforzar
        assertEquals(127.0 / 110.0 - 1.0, ShadowMath.chromaHeadroom(110.0), 1e-9)
        assertEquals(0.0, ShadowMath.chromaHeadroom(127.0), 1e-9)
        assertTrue(ShadowMath.chromaHeadroom(20.0) > 5.0)
    }
}
