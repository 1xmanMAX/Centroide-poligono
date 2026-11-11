package com.scannerpromax.imaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas de imagen natural de [PhotoMath] (sin OpenCV) con rasgos MEDIDOS en el banco de zonas oscuras
 * (PhotoDarkZoneBenchTest, /tmp/claude-0/bench/fotos_oscuras): lo que antes salía como foto y no lo es (sombra muy
 * oscura en la esquina, huella, texto de un recibo, código de barras, dedo) se descarta; las fotos de verdad no.
 */
class PhotoNaturalRulesTest {

    private fun nat(midT: Double, low: Double, hue: Double, croma: Double, hv: Double, tinta: Double, mf: Double) =
        PhotoBoxRefine.Natural(midT, low, hue, croma, hv, tinta, mf)

    @Test fun zonasOscurasYTexto_noSonFotos() {
        // sombra muy oscura en la esquina (grano del sensor amplificado, gris, sin formas)
        assertNotNull(PhotoMath.naturalVeto(nat(0.375, 10.6, 0.0, 0.005, 0.39, 0.23, 1.47)))
        assertNotNull(PhotoMath.naturalVeto(nat(0.329, 13.9, 0.0, 0.0, 0.38, 0.20, 1.43)))
        // fondo oscuro con texto (captura real)
        assertNotNull(PhotoMath.naturalVeto(nat(0.343, 10.35, 0.0, 0.005, 0.52, 0.365, 1.46)))
        // huella dactilar del DNI
        assertNotNull(PhotoMath.naturalVeto(nat(0.382, 15.1, 0.0, 0.0, 0.36, 0.80, 0.40)))
        // recibo / cabecera con letras blancas / código de barras
        assertNotNull(PhotoMath.naturalVeto(nat(0.206, 20.7, 0.0, 0.0, 0.52, 0.72, 1.63)))
        assertNotNull(PhotoMath.naturalVeto(nat(0.152, 11.0, 0.0, 0.0, 0.48, 0.17, 2.59)))
        assertNotNull(PhotoMath.naturalVeto(nat(0.143, 15.0, 0.0, 0.0, 0.98, 0.66, 1.46)))
        // emblema / dedo: un solo tono liso
        assertNotNull(PhotoMath.naturalVeto(nat(0.346, 7.46, 0.05, 0.965, 0.35, 0.42, 2.25)))
    }

    @Test fun fotosDeVerdad_noSeDescartan() {
        // cara de un carné, foto en gris (cámara), coches, hierba (textura uniforme), dientes en un artículo
        assertNull(PhotoMath.naturalVeto(nat(0.289, 37.1, 0.005, 0.367, 0.33, 0.58, 2.05)))
        assertNull(PhotoMath.naturalVeto(nat(0.389, 32.6, 0.0, 0.0, 0.36, 0.20, 2.68)))
        assertNull(PhotoMath.naturalVeto(nat(0.471, 14.65, 0.615, 0.853, 0.445, 0.49, 2.5)))
        assertNull(PhotoMath.naturalVeto(nat(0.49, 3.3, 0.0, 0.0, 0.31, 0.62, 2.21)))
        assertNull(PhotoMath.naturalVeto(nat(0.379, 24.7, 0.0, 0.0, 0.60, 0.696, 1.37)))
    }

    @Test fun fotoLisaDudosa_seConfirmaSoloConFormas() {
        val m = PhotoBoxRefine.Metrics(0.97, 0.01, 0.24, 0.73, 11, 0.74, 0.57)
        assertTrue(PhotoMath.naturalPhoto(m, nat(0.62, 25.9, 0.0, 0.0, 0.23, 0.02, 2.6)))
        // sin estructura grande (zona lisa) o con papel: no
        assertFalse(PhotoMath.naturalPhoto(m, nat(0.62, 6.0, 0.0, 0.0, 0.23, 0.02, 2.6)))
        assertFalse(PhotoMath.naturalPhoto(PhotoBoxRefine.Metrics(0.8, 0.2, 0.3, 0.5, 11, 0.5, 0.5), nat(0.62, 25.9, 0.0, 0.0, 0.23, 0.02, 2.6)))
    }
}
