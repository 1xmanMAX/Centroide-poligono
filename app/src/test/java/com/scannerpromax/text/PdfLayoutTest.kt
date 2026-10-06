package com.scannerpromax.text

import com.scannerpromax.domain.OcrRect
import com.scannerpromax.pdf.ImageToPage
import com.scannerpromax.pdf.TextLayerGeometry
import com.scannerpromax.pdf.TextPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PdfLayoutTest {

    private val ascent = 0.905f
    private val descent = 0.212f

    /** Imagen de 2000x1000 px en una página de 1000x500 pt (escala 0.5), sin márgenes. */
    private val map = ImageToPage(x0 = 0f, yTop = 500f, sx = 0.5f, sy = 0.5f)

    @Test fun palabraHorizontalOcupaSuCaja() {
        val box = OcrRect(200f, 100f, 600f, 160f) // 400x60 px -> 200x30 pt
        val run = TextLayerGeometry.place(box, 0f, 60f, textWidthEm = 2.5f, map = map, ascent = ascent, descent = descent)
        assertNotNull(run); run!!
        assertEquals(0f, run.angleRad, 1e-6f)
        // Tamaño: alto en pt / (ascendente + descendente).
        assertEquals(30f / (ascent + descent), run.fontSize, 1e-3f)
        // Ancho dibujado = textWidthEm * fontSize * hScale/100 = ancho de la caja.
        assertEquals(200f, 2.5f * run.fontSize * run.hScale / 100f, 1e-2f)
        // Origen: borde izquierdo de la caja; línea base = borde inferior + descendente.
        assertEquals(100f, run.x, 1e-3f)
        val bottomPt = 500f - 160f * 0.5f
        assertEquals(bottomPt + descent * run.fontSize, run.y, 1e-3f)
        // El "cuerpo" del texto (de -descendente a +ascendente) cubre exactamente la caja.
        assertEquals(500f - 100f * 0.5f, run.y + ascent * run.fontSize, 1e-3f)
    }

    @Test fun lineaInclinadaRecuperaAltoYGira() {
        // Rectángulo real 400x40 px girado 5° (horario en la imagen) -> envolvente.
        val w = 400f; val h = 40f; val deg = 5f
        val r = Math.toRadians(deg.toDouble())
        val bw = (w * cos(r) + h * sin(r)).toFloat()
        val bh = (w * sin(r) + h * cos(r)).toFloat()
        val box = OcrRect(1000f - bw / 2, 500f - bh / 2, 1000f + bw / 2, 500f + bh / 2)
        val lineH = TextLayerGeometry.lineHeight(box, deg)
        assertEquals(h, lineH, 0.5f)
        val run = TextLayerGeometry.place(box, deg, lineH, 4f, map, ascent, descent)!!
        // Horario en la imagen (y hacia abajo) = antihorario negativo en el PDF.
        assertEquals(-r.toFloat(), run.angleRad, 1e-5f)
        // El ancho recuperado coincide con el real.
        assertEquals(w * 0.5f, 4f * run.fontSize * run.hScale / 100f, 0.5f)
        // El centro del texto girado coincide con el centro de la caja.
        val widthPt = w * 0.5f
        val heightPt = h * 0.5f
        val cx = run.x + (widthPt / 2) * run.cos - (heightPt / 2 - descent * run.fontSize) * run.sin
        val cy = run.y + (widthPt / 2) * run.sin + (heightPt / 2 - descent * run.fontSize) * run.cos
        assertEquals(500f, cx, 0.3f)
        assertEquals(250f, cy, 0.3f)
    }

    @Test fun cajaVaciaNoSeDibuja() {
        assertEquals(null, TextLayerGeometry.place(OcrRect(1f, 1f, 1f, 5f), 0f, 4f, 1f, map, ascent, descent))
    }

    private val mono: (String, Float) -> Float = { s, size -> s.length * size * 0.5f }

    @Test fun ajusteDeLineaYPartidoDePalabrasLargas() {
        val lines = TextPageLayout.wrap("uno dos tres cuatro cinco", maxWidth = 50f, size = 10f, width = mono) // 10 caracteres por línea
        assertEquals(listOf("uno dos", "tres", "cuatro", "cinco"), lines)
        val long = TextPageLayout.wrap("abcdefghijklmnopqrstuvwxyz", 50f, 10f, mono)
        assertEquals(listOf("abcdefghij", "klmnopqrst", "uvwxyz"), long)
        lines.forEach { assertTrue(mono(it, 10f) <= 50f) }
    }

    @Test fun saltoDePaginaAutomaticoConTituloDeContinuacion() {
        val style = TextPageLayout.Style(pageWidth = 300f, pageHeight = 300f, margin = 40f, titleSize = 14f, bodySize = 10f)
        val paragraphs = (1..40).map { "Párrafo número $it con varias palabras para ocupar espacio" }
        val pages = TextPageLayout.layout("Texto reconocido – Página 1", paragraphs, style, "(vacío)", mono)
        assertTrue(pages.size > 1)
        fun titleOf(p: List<TextPageLayout.Line>) = p.filter { it.kind == TextPageLayout.Kind.TITLE }.joinToString(" ") { it.text }
        assertEquals("Texto reconocido – Página 1", titleOf(pages[0]))
        assertEquals("Texto reconocido – Página 1 (cont.)", titleOf(pages[1]))
        for (p in pages) for (l in p) {
            assertTrue("línea por debajo del margen: ${l.y}", l.y >= style.margin - 0.01f)
            assertTrue(l.y <= style.pageHeight - style.margin)
            assertTrue(mono(l.text, l.size) <= style.pageWidth - 2 * style.margin + 0.01f)
        }
        // No se pierde texto.
        val body = pages.flatten().filter { it.kind == TextPageLayout.Kind.BODY }.joinToString(" ") { it.text }
        assertEquals(paragraphs.joinToString(" "), body)
    }

    @Test fun paginaSinTextoMuestraNota() {
        val style = TextPageLayout.Style(pageWidth = 595f, pageHeight = 842f)
        val pages = TextPageLayout.layout("Texto reconocido", emptyList(), style, "(No se reconoció texto)", mono)
        assertEquals(1, pages.size)
        assertEquals(TextPageLayout.Kind.NOTE, pages[0].last().kind)
    }
}
