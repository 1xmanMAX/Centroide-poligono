package com.scannerpromax.ai.parts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.PageSize
import com.scannerpromax.pdf.PdfFontMetrics
import com.scannerpromax.pdf.PdfReconstructor
import com.scannerpromax.pdf.ReFont
import com.scannerpromax.pdf.ReOp
import com.scannerpromax.pdf.ReconstructionLayout
import com.scannerpromax.pdf.ReconstructionRenderer
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Utilidades de los tests con modelos y datos del banco (solo se usan si existen en /tmp/claude-0/bench). */
object BenchSupport {
    const val B = "/tmp/claude-0/bench"
    val LAYOUT_MODEL = File("$B/models/dl_layout/paddle_off/inference.onnx")
    val ORI_MODEL = File("$B/models/doc_ori/inference.onnx")

    /** doc_ori con ORT (en la app: OpenCV DNN). */
    class OrtOrientation(path: File) : DocOrientation.Runner, AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val s = env.createSession(path.path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
        override fun run(chw: FloatArray): FloatArray {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, 224, 224)).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    val o = r.get(0) as OnnxTensor
                    val b = o.floatBuffer; val a = FloatArray(b.remaining()); b.get(a); return a
                }
            }
        }
        override fun close() = s.close()
    }

    /** Cliente de llama-server (PaddleOCR-VL) con caché en disco por (prompt, tokens, imagen). */
    class Llm(val port: Int, val cache: File) {
        var calls = 0; var cached = 0; var wallMs = 0L
        fun ask(img: Rgb, prompt: String, maxTokens: Int): Pair<String, String> {
            val png = TestImages.pngBytes(img)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(prompt.toByteArray()); md.update(maxTokens.toString().toByteArray()); md.update(png)
            val key = md.digest().joinToString("") { "%02x".format(it) }.take(32)
            val cf = File(cache, "$key.json")
            if (cf.isFile) {
                val o = Json.parseToJsonElement(cf.readText()).jsonObject
                cached++
                return o["text"]!!.jsonPrimitive.content to o["finish"]!!.jsonPrimitive.content
            }
            val body = buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png)) }) })
                            add(buildJsonObject { put("type", "text"); put("text", prompt) })
                        })
                    })
                })
                put("temperature", 0); put("max_tokens", maxTokens); put("cache_prompt", false)
            }
            val t0 = System.currentTimeMillis()
            val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 3_600_000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = c.inputStream.bufferedReader().readText()
            wallMs += System.currentTimeMillis() - t0
            calls++
            val ch = Json.parseToJsonElement(resp).jsonObject["choices"]!!.jsonArray[0].jsonObject
            val text = (ch["message"]!!.jsonObject["content"] as? JsonPrimitive)?.content ?: ""
            val finish = (ch["finish_reason"] as? JsonPrimitive)?.content ?: "stop"
            cache.mkdirs()
            cf.writeText(buildJsonObject { put("text", text); put("finish", finish); put("prompt", prompt); put("w", img.w); put("h", img.h) }.toString())
            return text to finish
        }
    }

    /** Reducción por área a ≤ [maxPx] píxeles (como fit() del banco, que usa LANCZOS). */
    fun fit(img: Rgb, maxPx: Int): Rgb {
        val s = min(1.0, sqrt(maxPx.toDouble() / (img.w.toDouble() * img.h)))
        if (s >= 1.0) return img
        return img.resizeArea(max(1, (img.w * s).roundToInt()), max(1, (img.h * s).roundToInt()))
    }

    /** Lector de la tubería contra llama-server; Spotting de página sembrado con la caché del banco si se da. */
    class Reader(val page: Rgb, val llm: Llm, val seed: File? = null) : PartsReader {
        private fun crop(b: Box) = page.crop(b[0].toInt(), b[1].toInt(), b[2].toInt(), b[3].toInt())
        override suspend fun spotting(crop: Box?, maxTokens: Int): PartsReader.Out {
            if (crop == null && seed != null && seed.isFile) {
                val js = File(seed.path.removeSuffix(".txt") + ".json")
                val fin = if (js.isFile) (Json.parseToJsonElement(js.readText()).jsonObject["finish"] as? JsonPrimitive)?.content else null
                return PartsReader.Out(seed.readText(), fin == "length")
            }
            val img = fit(if (crop == null) page else crop(crop), 1_605_632)
            val (t, f) = llm.ask(img, "Spotting:", maxTokens)
            return PartsReader.Out(t, f == "length")
        }
        override suspend fun table(pieces: List<PartsReader.Piece>): PartsReader.Out {
            val parts = pieces.map { crop(it.src) }
            val w = max(1, pieces.indices.maxOf { (pieces[it].dstX + parts[it].w).toInt() })
            val h = parts.maxOf { it.h }
            val px = IntArray(w * h) { -1 }
            for ((i, p) in parts.withIndex()) {
                val dx = pieces[i].dstX.toInt()
                for (y in 0 until p.h) for (x in 0 until p.w) if (dx + x < w) px[y * w + dx + x] = p.px[y * p.w + x]
            }
            val (t, f) = llm.ask(Rgb(w, h, px), "Table Recognition:", 3000)
            return PartsReader.Out(t, f == "length")
        }
        override suspend fun chart(crop: Box): PartsReader.Out {
            val (t, f) = llm.ask(crop(crop), "Chart Recognition:", 512); return PartsReader.Out(t, f == "length")
        }
        override suspend fun formula(crop: Box): PartsReader.Out {
            val (t, f) = llm.ask(crop(crop), "Formula Recognition:", 256); return PartsReader.Out(t, f == "length")
        }
    }

    /** Líneas del OCR básico de escritorio (Tesseract, como sustituto de ML Kit en el banco) → Items. */
    fun tesseractLines(png: File): List<PartsPipeline.Item> {
        val p = ProcessBuilder("tesseract", png.path, "stdout", "-l", "spa+eng", "--psm", "3", "tsv").redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        class W(val line: String, val l: Int, val t: Int, val r: Int, val b: Int, val text: String)
        val words = out.lines().drop(1).mapNotNull { row ->
            val c = row.split('\t')
            if (c.size < 12 || c[0] != "5" || c[11].isBlank() || (c[10].toFloatOrNull() ?: -1f) < 0) return@mapNotNull null
            W("${c[2]}_${c[3]}_${c[4]}", c[6].toInt(), c[7].toInt(), c[6].toInt() + c[8].toInt(), c[7].toInt() + c[9].toInt(), c[11])
        }
        return words.groupBy { it.line }.values.map { ws ->
            val l = ws.minOf { it.l }.toFloat(); val t = ws.minOf { it.t }.toFloat(); val r = ws.maxOf { it.r }.toFloat(); val b = ws.maxOf { it.b }.toFloat()
            PartsPipeline.Item(ws.joinToString(" ") { it.text }, floatArrayOf(l, t, r, t, r, b, l, b), floatArrayOf(l, t, r, b))
        }
    }

    private fun fonts(doc: PDDocument): PdfFontMetrics {
        val regular = javaClass.getResourceAsStream("/" + PdfReconstructor.LIBERATION_SANS)!!.use { PDType0Font.load(doc, it, true) }
        val bold = File("src/main/assets/" + PdfReconstructor.BOLD_FONT).inputStream().use { PDType0Font.load(doc, it, true) }
        val hand = File("src/main/assets/" + PdfReconstructor.HAND_FONT).inputStream().use { PDType0Font.load(doc, it, true) }
        return PdfFontMetrics(mapOf(ReFont.REGULAR to regular, ReFont.BOLD to bold, ReFont.HAND to hand))
    }

    /** PDF reconstruido (como PdfReconstructor, recortes de [page] = la imagen del DocLayout) + PNG con pdftoppm. */
    fun render(layout: DocLayout, page: Rgb, pdf: File) {
        val doc = PDDocument()
        try {
            val m = fonts(doc)
            val plan = ReconstructionLayout.plan(layout, PageSize.AUTO, 0f, m)
            val sx = page.w.toFloat() / layout.imageWidth; val sy = page.h.toFloat() / layout.imageHeight
            ReconstructionRenderer.render(doc, plan, m) { op: ReOp.Image ->
                val c = page.crop((op.src.left * sx).toInt(), (op.src.top * sy).toInt(), (op.src.right * sx).roundToInt(), (op.src.bottom * sy).roundToInt())
                if (op.inkOnly) PageInk.inkOnly(c.px, c.w, c.h, max(25, (0.12f * min(page.w, page.h) * sx).toInt()), PageInk.percentile(page.gray(), 90.0))
                val f = File.createTempFile("crop", ".jpg")
                try {
                    TestImages.write(c, f, "jpg")
                    PDImageXObject(doc, ByteArrayInputStream(f.readBytes()), COSName.DCT_DECODE, c.w, c.h, 8, PDDeviceRGB.INSTANCE)
                } finally { f.delete() }
            }
            pdf.parentFile?.mkdirs()
            doc.save(pdf)
        } finally {
            doc.close()
        }
        ProcessBuilder("pdftoppm", "-r", "110", "-png", "-singlefile", pdf.path, pdf.path.removeSuffix(".pdf")).inheritIO().start().waitFor()
    }

    @Suppress("unused")
    private val unusedJson: JsonObject? = null
}
