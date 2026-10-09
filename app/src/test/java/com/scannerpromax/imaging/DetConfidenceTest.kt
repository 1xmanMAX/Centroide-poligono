package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confianza por calidad de borde de [DocumentDetector.qualityConf]: rasgos = área, maxCos, apoyo, peor lado, marco. */
class DetConfidenceTest {

    private fun conf(af: Double, maxCos: Double, edge: Double, minSide: Double, border: Double = 0.0, prov: Double = 1.0) =
        DocumentDetector.qualityConf(doubleArrayOf(af, maxCos, edge, minSide, border), prov)

    @Test fun documentoPequenoConBordeNitidoEsFiable() {
        // Hoja al 10 % del encuadre con los cuatro lados apoyados: debe superar los umbrales de la app (0.35-0.45)
        assertTrue(conf(0.10, 0.2, 1.0, 1.0) >= 0.8)
        // ... igual que si ocupara el 50 %
        assertEquals(conf(0.5, 0.2, 1.0, 1.0), conf(0.10, 0.2, 1.0, 1.0), 1e-9)
    }

    @Test fun bordePobreNoEsFiable() {
        assertEquals(0.0, conf(0.5, 0.2, 0.6, 0.3), 1e-9)
        assertTrue(conf(0.3, 0.3, 0.8, 0.5) < 0.35)
    }

    @Test fun monotonaEnElApoyo() {
        var prev = -1.0
        for (e in listOf(0.7, 0.8, 0.9, 1.0)) { val c = conf(0.2, 0.1, e, e); assertTrue(c >= prev); prev = c }
    }

    @Test fun penalizaTramosSobreElMarcoYMuyPequenos() {
        assertTrue(conf(0.2, 0.1, 1.0, 1.0, border = 0.4) < conf(0.2, 0.1, 1.0, 1.0))
        assertTrue(conf(0.02, 0.1, 1.0, 1.0) < conf(0.2, 0.1, 1.0, 1.0))
        // Rectángulo mínimo sin ajustar a los bordes (procedencia 0.7): algo menos fiable
        assertTrue(conf(0.2, 0.1, 0.95, 0.9, prov = 0.7) < conf(0.2, 0.1, 0.95, 0.9))
    }
}
