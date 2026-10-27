package com.scannerpromax.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cajas de foto de la maquetación para "Color natural" ([photoBoxes]). */
class DocLayoutPhotoBoxesTest {

    private fun block(type: BlockType, role: String?, l: Float, t: Float, r: Float, b: Float) =
        LayoutBlock(type = type, box = LayoutBox(l, t, r, b), role = role)

    @Test fun imagenFiguraGraficoYSello_normalizadas_sinDibujosNiPaginaEntera() {
        val layout = DocLayout(
            1000, 2000,
            listOf(
                block(BlockType.FIGURE, "image", 100f, 200f, 500f, 600f),
                block(BlockType.CHART, "chart", 0f, 1000f, 1000f, 1400f),
                block(BlockType.FIGURE, "seal", 800f, 1800f, 950f, 1950f),
                block(BlockType.FIGURE, "dibujo", 100f, 100f, 300f, 300f),      // tinta: se binariza
                block(BlockType.TEXT, "text", 100f, 700f, 900f, 900f),
                block(BlockType.FIGURE, "image", 0f, 0f, 1000f, 1900f),         // casi toda la página
            ),
        )
        val boxes = layout.photoBoxes()
        assertEquals(3, boxes.size)
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.5f, 0.3f), boxes[0], 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.7f), boxes[1], 1e-6f)
        assertEquals(0, DocLayout(0, 0, layout.blocks).photoBoxes().size)
    }
}
