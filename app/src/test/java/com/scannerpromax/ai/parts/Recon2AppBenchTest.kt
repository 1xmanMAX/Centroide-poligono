package com.scannerpromax.ai.parts

import com.scannerpromax.ai.cloud.CloudAlign
import com.scannerpromax.ai.cloud.CloudJson
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.pdf.DocLayoutFiles
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Banco recon2 con el CÓDIGO REAL de la app (solo con -Pr2appOut=DIR; necesita los modelos del banco y, si no hay
 * caché, llama-server con PaddleOCR-VL en -Pr2appPort):
 *
 *  - local: orientación (doc_ori, voto de 5) → PP-DocLayoutV3 (ORT) → [PartsPipeline] → DIR/new + PDF en DIR/new_pdf;
 *  - hyb:   [HybridFuse] de esa maquetación local con las salidas reales de Haiku (recon2/haiku) → DIR/hyb;
 *  - hybml: como en el MODO NUBE de la app: regiones PP + líneas del OCR básico (Tesseract en el banco, ML Kit en el
 *           móvil) + Haiku → DIR/hybml.
 * Las métricas y hojas comparativas las saca tools/recon2_app_eval.py.
 */
class Recon2AppBenchTest {

    private val out = System.getProperty("r2app.out")?.let { File(it) }
    private val only = System.getProperty("r2app.only")?.split(',')?.filter { it.isNotBlank() }?.toSet()
    private val port = System.getProperty("r2app.port")?.toInt() ?: 8094
    private val stages = (System.getProperty("r2app.stage") ?: "local,hyb,hybml").split(',').toSet()
    private val set = File("${BenchSupport.B}/recon2/set")
    private val haiku = File("${BenchSupport.B}/recon2/haiku")

    @Test
    fun banco() {
        val dir = out ?: return
        if (!BenchSupport.LAYOUT_MODEL.isFile || !set.isDirectory) return
        val names = set.listFiles()!!.filter { it.name.endsWith(".png") }.map { it.name.removeSuffix(".png") }.sorted()
            .filter { only == null || it in only }
        val llm = BenchSupport.Llm(port, File(dir, "llmcache"))
        PpLayoutOrt(BenchSupport.LAYOUT_MODEL.path, 4).use { pp ->
            BenchSupport.OrtOrientation(BenchSupport.ORI_MODEL).use { ori ->
                for (n in names) runBlocking { page(n, dir, pp, ori, llm) }
            }
        }
        println("R2APP llamadas=${llm.calls} caché=${llm.cached} s=${llm.wallMs / 1000}")
    }

    private suspend fun page(n: String, dir: File, pp: PpLayoutOrt, ori: BenchSupport.OrtOrientation, llm: BenchSupport.Llm) {
        val t0 = System.currentTimeMillis()
        val scan = TestImages.read(File(set, "$n.png"))
        val o = DocOrientation.predict(scan, ori)
        val rot = DocOrientation.correction(o)
        val page = if (rot != 0) TestImages.rotateCcw(scan, rot) else scan
        val newDir = File(dir, "new").also { it.mkdirs() }
        TestImages.write(page, File(newDir, "$n.png"))
        val dets = pp.detect(page, 0.3f)
        val regions = PpLayout.regions(dets)
        val hand = n.startsWith("u_") && n != "u_tabla1"   // como el banco (--hand): lo que Qwen clasificaría manuscrito
        val docType = if (hand) "manuscrito" else null
        val localFile = File(newDir, "$n.layout.json")
        val local: DocLayout
        if ("local" in stages || !localFile.isFile) {
            val seed = File("${BenchSupport.B}/recon2/spot_set/$n.txt").takeIf { rot == 0 }
            val res = PartsPipeline(BenchSupport.Reader(page, llm, seed)).run(
                PartsPipeline.Input(page, regions, docType, "recon2-app", rot),
            )
            local = res.layout
            localFile.writeText(DocLayoutFiles.encode(local))
            val m = res.meta
            File(newDir, "$n.meta.txt").writeText(
                "rot=$rot conf=${"%.2f".format(o.confidence)} modo=${m.mode} bucle=${m.pageLooped} regiones=${m.regions} libres=${m.freeBlocks} " +
                    "rayas=${m.rules} fondos=${m.fills} dibujos=${m.drawings} llamadas=${m.calls} pp=${pp.lastMs}ms total=${(System.currentTimeMillis() - t0) / 1000}s\n",
            )
            println("R2APP $n " + File(newDir, "$n.meta.txt").readText().trim())
            BenchSupport.render(local, page, File(dir, "new_pdf/$n.pdf"))
        } else {
            local = DocLayoutFiles.parse(localFile.readText())!!
            if ("render" in stages) BenchSupport.render(local, page, File(dir, "new_pdf/$n.pdf"))
        }
        val hj = File(haiku, "$n.json")
        if (!hj.isFile) return
        val cloudPage = CloudJson.parse(hj.readText())
        val cloud = HybridFuse.cloudLayout(cloudPage, page.w, page.h)
        if ("hyb" in stages) {
            val (fused, st) = HybridFuse.fuse(local, cloud)
            File(dir, "hyb").mkdirs()
            File(dir, "hyb/$n.layout.json").writeText(DocLayoutFiles.encode(fused))
            TestImages.write(page, File(dir, "hyb/$n.png"))
            println("R2APP $n híbrido: $st")
            BenchSupport.render(fused, page, File(dir, "hyb_pdf/$n.pdf"))
        }
        if ("hybml" in stages) {
            // Modo nube de la app: regiones PP + líneas del OCR básico; la nube pone el texto.
            val png = File(newDir, "$n.png")
            val lines = BenchSupport.tesseractLines(png)
            val loc = PartsPipeline(PartsPipeline.NoReader).run(PartsPipeline.Input(page, regions, docType, "recon2-app-ocr", rot, presetLines = lines)).layout
            val ocr = OcrResult(text = lines.joinToString("\n") { it.text }, imageWidth = page.w, imageHeight = page.h,
                blocks = listOf(OcrBlock("", OcrRect(0f, 0f, page.w.toFloat(), page.h.toFloat()), lines.map { OcrLine(it.text, OcrRect(it.bx[0], it.bx[1], it.bx[2], it.bx[3])) })))
            // (CloudAlign con las regiones locales: OCR de la capa de texto; aquí solo se comprueba que no falla)
            CloudAlign.align(cloudPage, page.w, page.h, ocr, docType, "nube", regions.map { CloudAlign.LocalRegion(it.type, OcrRect(it.box[0], it.box[1], it.box[2], it.box[3]), it.score) })
            val (fused, st) = HybridFuse.fuse(loc, cloud)
            File(dir, "hybml").mkdirs()
            File(dir, "hybml/$n.layout.json").writeText(DocLayoutFiles.encode(fused))
            File(dir, "hybml/$n.local.layout.json").writeText(DocLayoutFiles.encode(loc))
            TestImages.write(page, File(dir, "hybml/$n.png"))
            println("R2APP $n híbrido-ocr: $st")
            BenchSupport.render(fused, page, File(dir, "hybml_pdf/$n.pdf"))
        }
    }
}
