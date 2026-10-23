package com.scannerpromax.ui.review

import com.scannerpromax.ai.AiRecognitionQueue.PageStatus
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewLabelsTest {

    @Test fun subtituloSinTextoReconocido() {
        val pages = List(3) { Page(id = "p$it", originalFile = "p$it.jpg", width = 100, height = 140, ocrText = "texto") }
        assertEquals("3 páginas · ${ScanMode.DOCUMENT.label}", reviewSubtitle(pages, ScanMode.DOCUMENT))
    }

    @Test fun progresoIaVacioEsNull() {
        assertNull(aiProgressLabel(emptyList()))
    }

    @Test fun progresoIaLeyendo() {
        assertEquals("Leyendo con IA… 40 %", aiProgressLabel(listOf(PageStatus.Running(0.4f, "Leyendo texto"))))
        assertEquals(
            "Leyendo con IA… 40 % · 3 páginas pendientes",
            aiProgressLabel(listOf(PageStatus.Queued, PageStatus.Running(0.4f, "x"), PageStatus.Queued)),
        )
    }

    @Test fun progresoIaEnColaYSinRed() {
        assertEquals("1 página en cola para leer con IA", aiProgressLabel(listOf(PageStatus.Queued)))
        assertEquals("2 páginas en cola para leer con IA", aiProgressLabel(listOf(PageStatus.Queued, PageStatus.WaitingNetwork)))
        assertEquals("Esperando conexión para leer con IA", aiProgressLabel(listOf(PageStatus.WaitingNetwork, PageStatus.WaitingNetwork)))
    }
}
