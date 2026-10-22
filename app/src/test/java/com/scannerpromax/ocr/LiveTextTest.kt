package com.scannerpromax.ocr

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.ocr.LiveText.Handle
import com.scannerpromax.ocr.LiveText.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LiveTextTest {

    /** Línea con palabras de 40 px de ancho separadas 10 px, a partir de x = [x0]. */
    private fun line(y: Float, x0: Float, vararg ws: String): OcrLine {
        val words = ws.mapIndexed { i, t -> OcrWord(t, OcrRect(x0 + i * 50f, y, x0 + i * 50f + 40f, y + 20f)) }
        return OcrLine(ws.joinToString(" "), OcrRect(x0, y, x0 + ws.size * 50f - 10f, y + 20f), words)
    }

    private fun block(vararg lines: OcrLine): OcrBlock {
        val l = lines.minOf { it.box.left }; val t = lines.minOf { it.box.top }
        val r = lines.maxOf { it.box.right }; val b = lines.maxOf { it.box.bottom }
        return OcrBlock(lines.joinToString("\n") { it.text }, OcrRect(l, t, r, b), lines.toList())
    }

    // Bloque 1: "Hola mundo bonito" / "segunda línea"; bloque 2 (columna derecha, se lee después): "Otro bloque".
    private val result = OcrResult(
        text = "",
        imageWidth = 1000,
        imageHeight = 1400,
        blocks = listOf(
            block(line(100f, 100f, "Hola", "mundo", "bonito"), line(130f, 100f, "segunda", "línea")),
            block(line(100f, 600f, "Otro", "bloque")),
        ),
    )
    private val words = LiveText.words(result)

    @Test fun palabrasEnOrdenDeLectura() {
        assertEquals(listOf("Hola", "mundo", "bonito", "segunda", "línea", "Otro", "bloque"), words.map { it.text })
        assertEquals(listOf(0, 0, 0, 1, 1, 2, 2), words.map { it.line })
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1), words.map { it.block })
    }

    @Test fun lineasSinPalabrasSeRepartenProporcionalmente() {
        val r = OcrResult("", 100, 100, listOf(OcrBlock("ab cd", OcrRect(0f, 0f, 50f, 10f), listOf(OcrLine("ab cd", OcrRect(0f, 0f, 50f, 10f))))))
        val ws = LiveText.words(r)
        assertEquals(listOf("ab", "cd"), ws.map { it.text })
        assertEquals(0f, ws[0].box.left, 1e-3f)
        assertEquals(20f, ws[0].box.right, 1e-3f)
        assertEquals(30f, ws[1].box.left, 1e-3f)
        assertEquals(50f, ws[1].box.right, 1e-3f)
    }

    @Test fun resultadoNuloOVacio() {
        assertTrue(LiveText.words(null).isEmpty())
        assertNull(LiveText.selectAll(emptyList()))
        assertEquals("", LiveText.text(emptyList(), Range(0, 0)))
    }

    @Test fun pulsacionSobreUnaPalabra() {
        assertEquals(1, LiveText.hitTest(words, 170f, 110f)) // "mundo" (150..190)
        // Entre "Hola" (100..140) y "mundo" (150..190): sin tolerancia no hay palabra; con tolerancia, la más cercana.
        assertNull(LiveText.hitTest(words, 143f, 110f))
        assertEquals(0, LiveText.hitTest(words, 143f, 110f, tolerance = 6f))
        assertNull(LiveText.hitTest(words, 500f, 700f, tolerance = 6f))
    }

    @Test fun palabraMasCercanaParaArrastrar() {
        assertEquals(4, LiveText.nearest(words, 260f, 145f)) // al final de "segunda línea"
        assertEquals(6, LiveText.nearest(words, 900f, 105f)) // a la derecha de "bloque"
        assertNull(LiveText.nearest(emptyList(), 0f, 0f))
    }

    @Test fun rangoEntreAnclaYDedoEnAmbosSentidos() {
        assertEquals(Range(1, 4), LiveText.between(1, 4))
        assertEquals(Range(1, 4), LiveText.between(4, 1))
        assertEquals(Range(3, 3), LiveText.between(3, 3))
    }

    @Test fun tiradoresAmplianYReducen() {
        val r = Range(1, 3)
        assertEquals(Range(0, 3) to Handle.START, LiveText.dragHandle(r, Handle.START, 0))
        assertEquals(Range(2, 3) to Handle.START, LiveText.dragHandle(r, Handle.START, 2))
        assertEquals(Range(1, 5) to Handle.END, LiveText.dragHandle(r, Handle.END, 5))
        assertEquals(Range(1, 1) to Handle.END, LiveText.dragHandle(r, Handle.END, 1))
    }

    @Test fun tiradoresSeIntercambianAlCruzarse() {
        // El inicio arrastrado más allá del fin pasa a ser el fin (y el dedo sigue moviendo ese extremo).
        assertEquals(Range(3, 5) to Handle.END, LiveText.dragHandle(Range(1, 3), Handle.START, 5))
        assertEquals(Range(0, 1) to Handle.START, LiveText.dragHandle(Range(1, 3), Handle.END, 0))
    }

    @Test fun textoSeleccionadoConLineasYBloques() {
        assertEquals("mundo bonito", LiveText.text(words, Range(1, 2)))
        assertEquals("bonito\nsegunda", LiveText.text(words, Range(2, 3)))
        assertEquals("Hola mundo bonito\nsegunda línea\n\nOtro bloque", LiveText.text(words, LiveText.selectAll(words)))
    }

    @Test fun textoUneCortesConGuion() {
        val r = OcrResult("", 1000, 1000, listOf(block(line(0f, 0f, "la", "infor-"), line(30f, 0f, "mación", "útil"))))
        val ws = LiveText.words(r)
        assertEquals("la información útil", LiveText.text(ws, LiveText.selectAll(ws)))
    }

    @Test fun rectangulosDeResaltadoPorLinea() {
        val rects = LiveText.lineRects(words, Range(1, 3))
        assertEquals(2, rects.size)
        assertEquals(OcrRect(150f, 100f, 240f, 120f), rects[0]) // "mundo bonito"
        assertEquals(OcrRect(100f, 130f, 140f, 150f), rects[1]) // "segunda"
    }

    // ------------------------------------------------------------------ mapeo con zoom

    @Test fun mapeoSinZoomAjustaYCentra() {
        // Vista 1000x2000, imagen 2000x2800 (ajuste por ancho: 0.5), OCR a la mitad de resolución (1000x1400).
        val m = LiveTextMapping(1000f, 2000f, 2000f, 2800f, 1000f, 1400f)
        // La imagen ocupa 1000x1400 centrada en vertical: top = 300.
        assertEquals(0f, m.toViewX(0f), 1e-3f)
        assertEquals(300f, m.toViewY(0f), 1e-3f)
        assertEquals(1000f, m.toViewX(1000f), 1e-3f)
        assertEquals(1700f, m.toViewY(1400f), 1e-3f)
        assertEquals(1f, m.pxPerOcr, 1e-4f)
    }

    @Test fun mapeoConZoomYDesplazamiento() {
        val m = LiveTextMapping(1000f, 2000f, 2000f, 2800f, 1000f, 1400f, scale = 2f, offsetX = 100f, offsetY = -50f)
        // ds = 1 px de pantalla por px del archivo; left = 500 + 100 - 1000 = -400; top = 1000 - 50 - 1400 = -450.
        assertEquals(-400f, m.toViewX(0f), 1e-3f)
        assertEquals(-450f, m.toViewY(0f), 1e-3f)
        assertEquals(2f, m.pxPerOcr, 1e-4f)
        // Punto central de la vista -> coordenadas OCR -> vuelta: ida y vuelta exacta.
        val ox = m.toOcrX(500f); val oy = m.toOcrY(1000f)
        assertEquals(450f, ox, 1e-3f)
        assertEquals(725f, oy, 1e-3f)
        assertEquals(500f, m.toViewX(ox), 1e-3f)
        assertEquals(1000f, m.toViewY(oy), 1e-3f)
    }

    @Test fun mapeoIdaYVueltaConEscalasVariadas() {
        for (s in listOf(1f, 1.7f, 3.3f, 5f)) for ((vw, vh) in listOf(1080f to 2200f, 2200f to 1080f)) {
            val m = LiveTextMapping(vw, vh, 3000f, 4000f, 1500f, 2000f, s, 37f, -91f)
            for ((x, y) in listOf(0f to 0f, 750f to 1000f, 1499f to 1999f, 123.4f to 1711.1f)) {
                assertTrue(abs(m.toOcrX(m.toViewX(x)) - x) < 1e-2f)
                assertTrue(abs(m.toOcrY(m.toViewY(y)) - y) < 1e-2f)
            }
        }
    }

    @Test fun pulsacionEnPantallaConZoomEncuentraLaPalabra() {
        val m = LiveTextMapping(1000f, 2000f, 2000f, 2800f, 1000f, 1400f, scale = 3f, offsetX = 400f, offsetY = 600f)
        // Centro de "mundo" en OCR (170, 110) llevado a la pantalla y de vuelta: se encuentra la palabra.
        val vx = m.toViewX(170f); val vy = m.toViewY(110f)
        assertEquals(1, LiveText.hitTest(words, m.toOcrX(vx), m.toOcrY(vy)))
        val box = m.toView(words[1].box)
        assertEquals(40f * m.pxPerOcr, box.right - box.left, 1e-3f)
    }
}
