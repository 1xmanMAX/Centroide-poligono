package com.scannerpromax.ai.parts

import com.scannerpromax.ai.cloud.CloudJson
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.pdf.DocLayoutFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Híbrido con las salidas REALES de Claude Haiku del banco (recon2/haiku/<n>.json) sobre la maquetación local del banco
 * (recon2/new/<n>.layout.json: PP-DocLayoutV3 + Paddle). Solo si existen (no se copian al repo: son textos de
 * datasets/fotos del usuario). Comprueba que el texto fusionado se parece más al de la nube que el local y que la
 * geometría (cajas de las líneas) sigue siendo la local.
 */
class HybridHaikuRealTest {
    private val b = File("/tmp/claude-0/bench/recon2")

    private fun text(l: DocLayout) = l.blocks.sortedBy { it.order }.filter { it.type != BlockType.FIGURE || it.lines.isNotEmpty() }
        .joinToString("\n") { bl -> if (bl.lines.isNotEmpty()) bl.lines.joinToString("\n") { it.text } else bl.text }

    @Test
    fun impresoYManuscritoReales() {
        var checked = 0
        for (n in listOf("dln_5070", "dln_5383", "dln_4175", "u_cuaderno1", "u_colores_sombra1")) {
            val hj = File(b, "haiku/$n.json"); val lj = File(b, "new/$n.layout.json")
            if (!hj.isFile || !lj.isFile) continue
            val local = DocLayoutFiles.parse(lj.readText()) ?: continue
            val page = CloudJson.parse(hj.readText())
            val cloud = HybridFuse.cloudLayout(page, local.imageWidth, local.imageHeight)
            val (fused, st) = HybridFuse.fuse(local, cloud)
            val ct = text(cloud)
            val before = TextSim.sim(text(local), ct)
            val after = TextSim.sim(text(fused), ct)
            println("HAIKU $n antes=${"%.3f".format(before)} después=${"%.3f".format(after)} $st")
            assertTrue("$n: el híbrido debe acercarse al texto de la nube ($before → $after)", after >= before)
            // geometría local: todas las cajas de líneas locales siguen (salvo fragmentos absorbidos y celdas encogidas)
            val localBoxes = local.blocks.flatMap { it.lines }.map { it.box.left to it.box.top }.toSet()
            val kept = fused.blocks.filter { it.role != "nube" && it.role != "dibujo→nube" }.flatMap { it.lines }.count { (it.box.left to it.box.top) in localBoxes }
            assertTrue(kept >= 0.8f * fused.blocks.filter { it.role != "nube" && it.role != "dibujo→nube" }.sumOf { it.lines.size })
            checked++
        }
        println("HAIKU páginas comprobadas: $checked")
    }
}
