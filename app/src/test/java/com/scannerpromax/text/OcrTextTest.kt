package com.scannerpromax.text

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.ocr.OcrText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrTextTest {

    @Test fun uneGuionDeFinDeLinea() {
        assertEquals("información", OcrText.joinHyphen("infor-", "mación"))
        assertEquals("La información es", OcrText.joinHyphen("La infor-", "mación es"))
        // Mayúscula tras el guion (nombres compuestos, listas): no se une.
        assertNull(OcrText.joinHyphen("Castilla-", "La Mancha"))
        // Guion suelto / número: no es corte de palabra.
        assertNull(OcrText.joinHyphen("Total -", "precio"))
        assertNull(OcrText.joinHyphen("2020-", "abc"))
        // Guion blando y guion tipográfico también.
        assertEquals("canción", OcrText.joinHyphen("can­", "ción"))
        assertEquals("canción", OcrText.joinHyphen("can‐", "ción"))
    }

    @Test fun lineasConGuionesConservanElRestoDeSaltos() {
        val out = OcrText.joinHyphenatedLines(listOf("El docu-", "mento dice", "Firma:", "Juan"))
        assertEquals(listOf("El documento dice", "Firma:", "Juan"), out)
    }

    @Test fun reflujoUneLineasLargasYRespetaListas() {
        val text = """
            El escáner reconoce el texto de las páginas y lo convier-
            te en párrafos legibles para el usuario, uniendo las líneas
            del mismo párrafo.

            Lista:
            • primero
            • segundo
        """.trimIndent()
        val paras = OcrText.reflow(text)
        assertEquals(
            "El escáner reconoce el texto de las páginas y lo convierte en párrafos legibles para el usuario, uniendo las líneas del mismo párrafo.",
            paras[0],
        )
        assertEquals(listOf("Lista:", "• primero", "• segundo"), paras.drop(1))
    }

    @Test fun reflujoNoUneDireccionesCortas() {
        assertEquals(listOf("Calle Mayor 5", "28013 Madrid", "España"), OcrText.reflow("Calle Mayor 5\n28013 Madrid\nEspaña"))
    }

    @Test fun textoPlanoUsaCorreccionYUneGuiones() {
        val r = OcrResult("Hola mun-\ndo", 10, 10, emptyList())
        assertEquals("Hola mundo", OcrText.plainText(r))
        assertEquals("Corregido", OcrText.plainText(r.copy(editedText = "Corregido")))
        val doc = OcrText.documentText(listOf(r, null))
        assertEquals("— Página 1 —\nHola mundo\n\n— Página 2 —\n(sin texto)", doc)
    }

    @Test fun parrafosPorBloques() {
        fun line(t: String) = OcrLine(t, OcrRect(0f, 0f, 1f, 1f))
        val r = OcrResult(
            "x", 10, 10,
            listOf(
                OcrBlock("t", OcrRect(0f, 0f, 1f, 1f), listOf(line("TÍTULO"))),
                OcrBlock("p", OcrRect(0f, 0f, 1f, 1f), listOf(line("Un párrafo bastante largo que se par-"), line("te en dos líneas del escaneo."))),
            ),
        )
        assertEquals(listOf("TÍTULO", "Un párrafo bastante largo que se parte en dos líneas del escaneo."), OcrText.paragraphs(r))
    }
}
