package com.scannerpromax.pdf

import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.LayoutLine
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.TableCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renderer v2 (recon2): tamaño común por párrafo, estilo de la nube, rayas, fondos, dibujos solo-tinta, tablas sin rejilla. */
class ReconstructionV2Test {
    private fun line(text: String, l: Float, t: Float, r: Float, b: Float) = LayoutLine(text, LayoutBox(l, t, r, b))

    // ------------------------------------------------------------------------------------------ v2 (recon2)

    private fun plan(l: DocLayout) = ReconstructionLayout.plan(l, PageSize.AUTO, 0f, FakeMetrics)

    @Test
    fun v2SoloConEstiloYParrafoConTamanoComun() {
        val lines = listOf(line("Primera linea del parrafo", 0f, 0f, 500f, 20f), line("segunda mas alta", 0f, 30f, 500f, 54f), line("fin", 0f, 60f, 100f, 80f))
        val old = DocLayout(600, 800, listOf(LayoutBlock(type = BlockType.TEXT, box = LayoutBox(0f, 0f, 500f, 80f), lines = lines)))
        assertFalse(ReconstructionLayout.usesV2(old))
        val v2 = old.copy(blocks = old.blocks.map { it.copy(align = "LEFT", sizePx = 20f) })
        assertTrue(ReconstructionLayout.usesV2(v2))
        val sizes = plan(v2).ops.filterIsInstance<ReOp.Text>().map { it.size }.toSet()
        assertEquals(1, sizes.size)  // un tamaño para todo el párrafo
        // v2 no estira las letras más allá del 100 %
        assertTrue(plan(v2).ops.filterIsInstance<ReOp.Text>().all { it.hScale <= 100.01f })
        // estilo de la nube (BlockStyle) también activa v2: negrita y centrado
        val cloud = old.copy(blocks = old.blocks.map { it.copy(style = com.scannerpromax.domain.BlockStyle(bold = true, align = "center")) })
        assertTrue(ReconstructionLayout.usesV2(cloud))
        val ops = plan(cloud).ops.filterIsInstance<ReOp.Text>()
        assertTrue(ops.all { it.font == ReFont.BOLD })
        assertTrue(ops.last().x > 1f)  // "fin" centrado en su caja
    }

    @Test
    fun v2RayasFondosDibujoYTablaSinRejilla() {
        val table = LayoutBlock(type = BlockType.TABLE, box = LayoutBox(0f, 300f, 400f, 400f), tableRows = 1, tableCols = 2, ruled = false,
            cells = listOf(TableCell(0, 0, text = "Total"), TableCell(0, 1, text = "12")), lines = listOf(line("basura ocr", 0f, 300f, 300f, 320f)))
        val l = DocLayout(600, 800, listOf(
            table,
            LayoutBlock(type = BlockType.FIGURE, box = LayoutBox(100f, 500f, 200f, 560f), role = ReconstructionLayout.DRAWING_ROLE),
        ), rules = listOf(listOf(0f, 200f, 600f, 200f, 2f)), fills = listOf(com.scannerpromax.domain.LayoutFill(LayoutBox(0f, 300f, 400f, 350f), 0xFFCCE0FF.toInt())))
        val ops = plan(l).ops
        assertEquals(1, ops.filterIsInstance<ReOp.Fill>().size)
        assertTrue(ops.filterIsInstance<ReOp.Image>().single().inkOnly)
        // las líneas no traen las celdas → tabla por celdas, sin rejilla (ruled = false); la única raya es la de rules
        assertEquals(1, ops.filterIsInstance<ReOp.Lines>().sumOf { it.segments.size })
        assertTrue(ops.filterIsInstance<ReOp.Text>().any { it.text == "Total" })
        assertFalse(ReconstructionLayout.linesCoverCells(table))
        assertTrue(ReconstructionLayout.linesCoverCells(table.copy(lines = listOf(line("Total", 0f, 300f, 100f, 320f), line("12", 200f, 300f, 300f, 320f)))))
    }
}
