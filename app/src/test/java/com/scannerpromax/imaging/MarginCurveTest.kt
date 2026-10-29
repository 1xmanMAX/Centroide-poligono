package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Márgenes curvos ([MarginCurve]): guías del texto impuestas como verticales con un desplazamiento suave. */
class MarginCurveTest {

    private val med = 14.0
    private val pitch = 32.0
    private val w = 1100.0
    private val h = 1600.0

    /** Guía de inicios entre [y0] e [y1] (un punto por renglón): x = [x0] + [f](y) + ruido. */
    private fun guide(x0: Double, y0: Double = 200.0, y1: Double = 1400.0, noise: Double = 0.0, seed: Long = 3, weight: Double = 1.0, f: (Double) -> Double = { 0.0 }): MarginCurve.Curve {
        val r = Random(seed)
        val ys = ArrayList<Float>(); val xs = ArrayList<Float>()
        var y = y0
        while (y <= y1) { ys.add(y.toFloat()); xs.add((x0 + f(y) + noise * r.nextGaussian()).toFloat()); y += pitch }
        return MarginCurve.Curve(xs.toFloatArray(), ys.toFloatArray(), weight, noise)
    }

    private fun arc(a: Double) = { y: Double -> a * sin(PI * (y - 200) / 1200) }

    private fun build(vararg c: MarginCurve.Curve) = MarginCurve.build(c.toList(), med, pitch, 0.0, 0.0, w, h, 1.0)

    @Test
    fun margenesRectosNoSeTocan() {
        val (d, why) = build(guide(150.0, noise = 0.3), guide(950.0, noise = 0.3, seed = 5))
        assertNull(why, d)
        assertTrue(why, why.startsWith("recto"))
    }

    @Test
    fun margenCurvoQuedaVertical() {
        val g = guide(150.0, f = arc(18.0))
        val (d, why) = build(g)
        assertNotNull(why, d); d!!
        // En la salida, la columna está en una vertical: (X, y) muestrea el punto de la curva en la foto
        val xs = g.x.map { it.toDouble() }.sorted(); val xm = xs[xs.size / 2]
        var worst = 0.0
        for (k in g.y.indices) {
            val y = g.y[k].toDouble()
            if (y < 200 + 2 * pitch || y > 1400 - 2 * pitch) continue
            worst = maxOf(worst, abs(xm + d.at(xm, y) - g.x[k]))
        }
        assertTrue("residuo $worst ($why)", worst < 1.5)
        // Una sola columna: la fila entera se mueve igual (sin estirar el renglón)
        assertEquals(d.at(xm, 800.0), d.at(900.0, 800.0), 1e-6)
    }

    @Test
    fun izquierdoCurvoDerechoRectoSeInterpola() {
        val (d, why) = build(guide(150.0, f = arc(15.0)), guide(950.0, seed = 7))
        assertNotNull(why, d); d!!
        assertTrue(abs(d.at(150.0, 800.0) - d.at(150.0, 280.0)) > 10)
        assertEquals(0.0, d.at(950.0, 800.0), 0.6)
        // a medio camino, la mitad
        assertEquals(d.at(150.0, 800.0) / 2, d.at(550.0, 800.0), 1.0)
    }

    @Test
    fun desviacionEnormeNoEsMargen() {
        // Recorrido de 5 % del ancho: no es un margen curvo (o la malla falló): no se usa
        val (d, why) = build(guide(150.0, f = arc(55.0)))
        assertNull(why, d)
    }

    @Test
    fun ondulacionDelOrdenDelRuidoNoCuenta() {
        // Inicios dispersos (cursiva, letra irregular): desviación pequeña respecto de su ruido
        val (d, why) = build(guide(150.0, noise = 1.6, f = { y -> 4.5 * sin(PI * (y - 200) / 1200) }))
        assertNull(why, d)
    }

    @Test
    fun escalonEntreDosColumnasNoEsCurva() {
        // Etiquetas a 1,5 letras del margen de los párrafos enlazadas en la misma "guía": escalón, no curva suave
        val (d, why) = build(guide(150.0, f = { y -> if (y < 800) 21.0 else 0.0 }))
        assertNull(why, d)
    }

    @Test
    fun guiasDuplicadasYEstiramientoExcesivo() {
        // Dos guías a 1 letra: sólo cuenta una (la de más peso, curva); una tercera que estiraría la fila > 6 % se descarta
        val curved = guide(150.0, f = arc(15.0), weight = 1.0)
        val dup = guide(164.0, weight = 0.5, seed = 9)
        val far = guide(300.0, f = arc(-15.0), weight = 0.4, seed = 11)
        val (d, why) = build(curved, dup, far)
        assertNotNull(why, d); d!!
        assertTrue(why, why.contains("descartadas 2"))
        assertEquals(d.at(150.0, 800.0), d.at(600.0, 800.0), 1e-6)
    }

    @Test
    fun puntaMalEnlazadaNoTuerceLaGuia() {
        // El primer inicio de la guía pertenece a un título (10 px a la derecha): margen recto
        val g = guide(150.0)
        g.x[0] += 10f; g.x[1] += 3f
        val (d, why) = build(g)
        assertNull(why, d)
    }

    @Test
    fun componerMapaConDesplazamientoNulo() {
        val gw = 5; val gh = 4
        val map = FloatArray(gw * gh * 2) { i -> if (i % 2 == 0) ((i / 2) % gw) / (gw - 1f) else ((i / 2) / gw) / (gh - 1f) }
        val out = MarginCurve.composeMap(map, gw, gh, 401.0, 301.0) { x, y, o -> o[0] = x; o[1] = y }
        for (i in map.indices) assertEquals(map[i], out[i], 1e-5f)
        // desplazamiento de 100 px (un cuarto del ancho): el mapa se corre una columna de la rejilla
        val sh = MarginCurve.composeMap(map, gw, gh, 401.0, 301.0) { x, y, o -> o[0] = x + 100.0; o[1] = y }
        assertEquals(map[2 * 1], sh[2 * 0], 1e-5f)
    }
}
