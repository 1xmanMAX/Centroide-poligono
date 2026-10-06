package com.scannerpromax.text

import com.scannerpromax.ocr.TextAligner
import org.junit.Assert.assertEquals
import org.junit.Test

class TextAlignerTest {

    private fun lines(vararg l: String) = l.map { TextAligner.tokenize(it) }

    @Test fun mismoNumeroDeLineasReemplazaLineaALinea() {
        val out = TextAligner.align(lines("Hoia mundo", "segunda linea"), "Hola mundo\nsegunda línea corregida")
        assertEquals(listOf(listOf("Hola", "mundo"), listOf("segunda", "línea", "corregida")), out)
    }

    @Test fun diffConservaPalabrasEnSuLinea() {
        // El usuario unió las dos líneas en una (cambia el número de líneas) y corrigió una palabra.
        val ocr = lines("El veloz murcielago", "hindú comía feliz")
        val out = TextAligner.align(ocr, "El veloz murciélago hindú comia felíz cardillo")
        assertEquals(listOf("El", "veloz", "murciélago"), out[0])
        assertEquals(listOf("hindú", "comia", "felíz", "cardillo"), out[1])
    }

    @Test fun sustitucionSeRepartePorLineas() {
        val ocr = lines("aaa bbb", "ccc ddd", "eee")
        // "bbb ccc" (fin de línea 1 + inicio de línea 2) reemplazado por cuatro palabras nuevas.
        val out = TextAligner.align(ocr, "aaa w1 w2 w3 w4 ddd eee")
        assertEquals(listOf("aaa", "w1", "w2"), out[0])
        assertEquals(listOf("w3", "w4", "ddd"), out[1])
        assertEquals(listOf("eee"), out[2])
        // No se pierde ni duplica ninguna palabra.
        assertEquals("aaa w1 w2 w3 w4 ddd eee", out.flatten().joinToString(" "))
    }

    @Test fun insercionAlInicioVaALaPrimeraLinea() {
        val out = TextAligner.align(lines("uno dos", "tres"), "cero uno dos tres")
        assertEquals(listOf("cero", "uno", "dos"), out[0])
        assertEquals(listOf("tres"), out[1])
    }

    @Test fun borradoDejaLineaVacia() {
        val out = TextAligner.align(lines("uno", "basura", "dos", "tres"), "uno dos tres")
        assertEquals(listOf(listOf("uno"), emptyList(), listOf("dos"), listOf("tres")), out)
    }

    @Test fun normalizaTildesYPuntuacion() {
        assertEquals("cancion", TextAligner.normalize("¡Canción!"))
    }
}
