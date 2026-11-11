package com.scannerpromax.ai.parts

import ai.onnxruntime.OrtEnvironment
import com.scannerpromax.imaging.Accel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Elección del proveedor de ONNX Runtime (CPU / XNNPACK / NNAPI) y su vuelta a la CPU. */
class OrtAccelTest {

    @After fun clean() { Accel.store = null; Accel.decisions.clear() }

    private fun t(ep: Accel.Ep, ms: Double, err: Double) = OrtAccel.Trial(ep, ms, err)

    @Test fun eligeElMasRapidoCorrecto() {
        assertEquals(Accel.Ep.NNAPI, OrtAccel.choose(100.0, listOf(t(Accel.Ep.XNNPACK, 60.0, 0.001), t(Accel.Ep.NNAPI, 30.0, 0.005))))
    }

    @Test fun descartaSalidaIncorrecta() {
        // NNAPI FP16 rapidísimo pero con la salida lejos de la CPU: no vale
        assertEquals(Accel.Ep.XNNPACK, OrtAccel.choose(100.0, listOf(t(Accel.Ep.XNNPACK, 60.0, 0.001), t(Accel.Ep.NNAPI_FP16, 10.0, 0.2))))
    }

    @Test fun cpuSiNoHayGananciaClara() {
        assertEquals(Accel.Ep.CPU, OrtAccel.choose(100.0, listOf(t(Accel.Ep.XNNPACK, 92.0, 0.0))))
        assertEquals(Accel.Ep.CPU, OrtAccel.choose(100.0, emptyList()))
        assertEquals(Accel.Ep.CPU, OrtAccel.choose(100.0, listOf(t(Accel.Ep.NNAPI, Double.NaN, 0.0), t(Accel.Ep.XNNPACK, 50.0, Double.POSITIVE_INFINITY))))
    }

    @Test fun errorRelativo() {
        assertEquals(0.0, OrtAccel.relErr(floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 1f, 2f)), 1e-9)
        assertEquals(0.05, OrtAccel.relErr(floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 1.1f, 2f)), 1e-6)
        assertTrue(OrtAccel.relErr(floatArrayOf(0f, 1f), floatArrayOf(0f)).isInfinite())
        assertTrue(OrtAccel.relErr(floatArrayOf(0f, 1f), floatArrayOf(0f, Float.NaN)).isInfinite())
    }

    /** Con el modelo real de «Encuadre IA» (assets): la sesión elegida funciona y da lo mismo que la CPU; la decisión se guarda. */
    @Test fun sesionRealConVueltaALaCpu() {
        val f = listOf("src/main/assets/models/docaligner_lcnet100.onnx", "app/src/main/assets/models/docaligner_lcnet100.onnx").map(::File).firstOrNull { it.isFile }
        assumeTrue("sin el modelo de encuadre en assets", f != null)
        val bytes = f!!.readBytes()
        val env = OrtEnvironment.getEnvironment()
        val saved = HashMap<String, String>()
        Accel.store = object : Accel.Store {
            override fun get(key: String) = saved[key]
            override fun put(key: String, value: String) { saved[key] = value }
        }
        val probe = OrtAccel.imageProbe(env, longArrayOf(1, 3, 256, 256))
        val s = OrtAccel.session("prueba", 2, load = { o -> env.createSession(bytes, o) }, probe = probe)
        val ref = OrtAccel.options(Accel.Ep.CPU, 2).use { o -> env.createSession(bytes, o) }
        try {
            assertArrayEquals(probe(ref), probe(s), 1e-3f)
            assertTrue(Accel.decisions.containsKey("prueba"))
            assertEquals(1, saved.size)
            // segunda vez: la decisión guardada, sin medir
            Accel.decisions.clear()
            OrtAccel.session("prueba", 2, load = { o -> env.createSession(bytes, o) }, probe = probe).close()
            assertTrue(Accel.ortStatus().contains("prueba"))
        } finally { s.close(); ref.close() }
    }
}
