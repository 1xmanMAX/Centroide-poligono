package com.scannerpromax.ai.jev

import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.FilterType
import com.scannerpromax.imaging.Cv
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.DocumentDetector
import com.scannerpromax.imaging.DuplicatePages
import com.scannerpromax.imaging.ImageEnhancer
import com.scannerpromax.imaging.NaturalColor
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/**
 * Banco de la integración de las ideas Jev con la API REAL (solo con -PjevIdeas=DIR; clave en /tmp/claude-0/bench/.jev_key,
 * nunca en el repo). Lee DIR/list.tsv (kind, id, ruta, etiqueta, extra; lo genera scratchpad jev_impl/py/mklist.py) y
 * escribe DIR/results.jsonl con lo que decide el código de la app; las métricas, jev_impl/py/score.py. Caché de
 * respuestas en /tmp/claude-0/bench/jev/cache_kt (repetir no cuesta).
 */
class JevIdeasEvalTest {

    private class Caching(private val dir: File, private val inner: JevTransport) : JevTransport {
        init { dir.mkdirs() }
        override fun post(url: String, headers: Map<String, String>, body: String): JevHttpReply {
            val h = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            val f = File(dir, "$h.json")
            if (f.isFile) return JevHttpReply(200, f.readText())
            return inner.post(url, headers, body).also { if (it.status == 200) f.writeText(it.body) }
        }
    }

    private fun readRgb(path: String, maxSide: Int): Mat? {
        val b = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
        if (b.empty()) return null
        val rgb = Mat(); Imgproc.cvtColor(b, rgb, Imgproc.COLOR_BGR2RGB); b.release()
        if (max(rgb.cols(), rgb.rows()) <= maxSide) return rgb
        val s = Mat(); Cv.downscale(rgb, s, maxSide); rgb.release(); return s
    }

    private val det = DocumentDetector(DeviceTier(false, 12_000_000, 8))

    /** Detector de la app sobre [rgb] (como SyntheticScenes.detect): quad en fracciones y confianza. */
    private fun detect(rgb: Mat): Pair<FloatArray?, Float?> {
        val gray = Mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val sm = Mat(); Cv.downscale(rgb, sm, 500)
        try {
            val r = det.detectScaled(sm, rgb.cols(), rgb.rows()) { x0, y0, rw, rh -> gray.submat(y0, y0 + rh, x0, x0 + rw).clone() } ?: return null to null
            val p = r.quad.points()
            val q = FloatArray(8) { i -> if (i % 2 == 0) p[i / 2].x / rgb.cols() else p[i / 2].y / rgb.rows() }
            return (if (r.confidence < 0.35f) null else q) to r.confidence
        } finally { gray.release(); sm.release() }
    }

    private fun area(q: FloatArray): Double {
        var a = 0.0
        for (i in 0 until 4) { val j = (i + 1) % 4; a += q[2 * i].toDouble() * q[2 * j + 1] - q[2 * j].toDouble() * q[2 * i + 1] }
        return kotlin.math.abs(a) / 2
    }

    private fun fa(q: FloatArray?) = q?.joinToString(",", "[", "]") { "%.4f".format(java.util.Locale.ROOT, it) } ?: "null"
    private fun fb(b: JevBox?) = b?.let { "[%.4f,%.4f,%.4f,%.4f]".format(java.util.Locale.ROOT, it.left, it.top, it.right, it.bottom) } ?: "null"

    @Test fun evaluacion() {
        val dir = System.getProperty("jev.ideas") ?: return
        val keyFile = File("/tmp/claude-0/bench/.jev_key")
        val cv = runCatching { nu.pattern.OpenCV.loadLocally(); Mat(1, 1, 0).release(); true }.getOrDefault(false)
        assumeTrue(cv && keyFile.isFile && File(dir, "list.tsv").isFile)
        val only = System.getProperty("jev.opts")?.split(',')?.toSet()
        val client = JevClient(keyFile.readText().trim(), Caching(File("/tmp/claude-0/bench/jev/cache_kt"), OkHttpJevTransport()))
        val out = File(dir, if (only != null) "results_${only.sorted().joinToString("_")}.jsonl" else "results.jsonl").printWriter()
        val renders = File(dir, "renders").apply { mkdirs() }
        var nRender = 0
        val lines = File(dir).listFiles { f -> f.name.startsWith("list") && f.name.endsWith(".tsv") }!!.sortedBy { it.name }.flatMap { it.readLines() }
        for (line in lines) {
            val f = line.split('\t')
            if (f.size < 4) continue
            val (kind, id, path, label) = f
            val extra = f.getOrElse(4) { "" }
            if (only != null && kind !in only) continue
            val sb = StringBuilder("{\"kind\":\"$kind\",\"id\":\"$id\",\"label\":\"$label\"")
            try {
                when (kind) {
                    "type", "folder", "frame", "quality", "quality_real" -> {
                        val rgb = readRgb(path, 2000) ?: continue
                        val small = Mat(); Cv.downscale(rgb, small, JevCapture.SIDE)
                        val h = JevCapture.analyze(small, client); small.release()
                        sb.append(",\"hints\":").append(h.toJson())
                        sb.append(",\"ctype\":\"${JevCapture.confidentType(h)}\",\"cfolder\":\"${JevCapture.suggestedFolder(h)}\"")
                        if (kind == "frame" || kind.startsWith("quality")) {
                            val (q, conf) = detect(rgb)
                            val nearly = q != null && area(q) > 0.97
                            val qBox = q?.let { qq -> JevBox((0 until 4).minOf { qq[2 * it].toDouble() }, (0 until 4).minOf { qq[2 * it + 1].toDouble() }, (0 until 4).maxOf { qq[2 * it].toDouble() }, (0 until 4).maxOf { qq[2 * it + 1].toDouble() }) }
                            val fr = JevCapture.framing(h, qBox, conf, nearly)
                            var fin: FloatArray? = q
                            var how = "keep"
                            when (fr) {
                                JevCapture.Framing.Keep -> Unit
                                JevCapture.Framing.FullPage -> { fin = null; how = "full" }
                                is JevCapture.Framing.Roi -> {
                                    val r = fr.roi; val W = rgb.cols(); val H = rgb.rows()
                                    val x0 = (r.left * W).toInt(); val y0 = (r.top * H).toInt()
                                    val rect = Rect(x0, y0, ((r.right * W).toInt() - x0).coerceAtLeast(1), ((r.bottom * H).toInt() - y0).coerceAtLeast(1))
                                    val crop = rgb.submat(rect).clone()
                                    val (q2, c2) = detect(crop); crop.release()
                                    val g = q2?.let { qq -> FloatArray(8) { i -> if (i % 2 == 0) (x0 + qq[i] * rect.width).toFloat() / W else (y0 + qq[i] * rect.height).toFloat() / H } }
                                    if (g != null && (c2 ?: 0f) >= 0.35f && JevCapture.roiQuadOk(g, r, h.box)) { fin = g; how = "roi" }
                                    else { val b = fr.fallback; fin = floatArrayOf(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), b.left.toFloat(), b.bottom.toFloat()); how = "box" }
                                    sb.append(",\"roi\":${fb(r)}")
                                }
                            }
                            sb.append(",\"app\":${fa(q)},\"conf\":${conf ?: -1},\"final\":${fa(fin)},\"how\":\"$how\",\"W\":${rgb.cols()},\"H\":${rgb.rows()}")
                            val docBox = fin?.let { qq -> JevBox((0 until 4).minOf { qq[2 * it].toDouble() }, (0 until 4).minOf { qq[2 * it + 1].toDouble() }, (0 until 4).maxOf { qq[2 * it].toDouble() }, (0 until 4).maxOf { qq[2 * it + 1].toDouble() }) }
                            sb.append(",\"issues\":" + JevCapture.issues(h, docBox).joinToString(",", "[", "]") { "\"$it\"" })
                        }
                        rgb.release()
                    }
                    "dup" -> {
                        val (pa, pb) = path.split('|')
                        val ga = Imgcodecs.imread(pa, Imgcodecs.IMREAD_GRAYSCALE); val gb = Imgcodecs.imread(pb, Imgcodecs.IMREAD_GRAYSCALE)
                        val a = DuplicatePages.features(ga); val b = DuplicatePages.features(gb)
                        val n = DuplicatePages.inliers(a, b); a.release(); b.release(); ga.release(); gb.release()
                        val v = DuplicatePages.verdict(n)
                        sb.append(",\"inliers\":$n,\"verdict\":\"$v\"")
                        if (v == DuplicatePages.Verdict.GRAY) {
                            val ra = readRgb(pa, 1600)!!; val rb = readRgb(pb, 1600)!!
                            val (p, tok) = JevDuplicates.samePage(ra, rb, client); ra.release(); rb.release()
                            sb.append(",\"jev\":$p,\"tokens\":$tok")
                        }
                    }
                    "inkgt" -> {
                        // tinta tenue con su caja verdadera: "Color natural" sin y con el tratamiento suave
                        val rgb = readRgb(path, 2000) ?: continue
                        val b = extra.split(' ').map { it.toFloat() }
                        val opt = ImageEnhancer.Options.of(DeviceTier(false, 12_000_000, 8), fast = false)
                        val a = ImageEnhancer.applyMat(rgb, FilterType.NATURAL, Adjustments(), opt)
                        val c = ImageEnhancer.applyMat(rgb, FilterType.NATURAL, Adjustments(), opt.copy(layoutImages = listOf(floatArrayOf(b[0], b[1], b[2], b[3], NaturalColor.INK_FLAG))))
                        for ((n, m) in listOf("a" to a, "b" to c)) {
                            val bgr = Mat(); Imgproc.cvtColor(m, bgr, if (m.channels() == 1) Imgproc.COLOR_GRAY2BGR else Imgproc.COLOR_RGB2BGR)
                            Imgcodecs.imwrite(File(renders, "${id}_$n.jpg").path, bgr); bgr.release(); m.release()
                        }
                        sb.append(",\"inks\":[" + fa(b.toFloatArray()) + "]")
                        rgb.release()
                    }
                    "ink" -> {
                        val rgb = readRgb(path, 2000) ?: continue
                        val img = JevImages.encode(rgb, JevPage.SIDE, 75, prefix = "page")
                        val r = client.ask(JevPage.request(img, photos = true, tables = true, inks = true))
                        val inks = JevPage.parseInks(r, img.fit)
                        sb.append(",\"has_sig\":${r["has_sig"]?.noul},\"sig_box\":${fb(r["sig_box"]?.box?.let { img.fit.toSource(it) })}")
                        sb.append(",\"has_stamp\":${r["has_stamp"]?.noul},\"stamp_box\":${fb(r["stamp_box"]?.box?.let { img.fit.toSource(it) })}")
                        sb.append(",\"inks\":" + inks.joinToString(",", "[", "]") { fa(it) } + ",\"tokens\":${r.inputTokens},\"gt\":\"$extra\"")
                        // muestra visual: "Color natural" sin y con las zonas de tinta
                        if (inks.isNotEmpty() && (nRender < 8 || (label == "stamp" && nRender < 14))) {
                            nRender++
                            val opt = ImageEnhancer.Options.of(DeviceTier(false, 12_000_000, 8), fast = false)
                            val a = ImageEnhancer.applyMat(rgb, FilterType.NATURAL, Adjustments(), opt)
                            val b = ImageEnhancer.applyMat(rgb, FilterType.NATURAL, Adjustments(), opt.copy(layoutImages = inks.map { floatArrayOf(it[0], it[1], it[2], it[3], NaturalColor.INK_FLAG) }))
                            for ((n, m) in listOf("a" to a, "b" to b)) {
                                val bgr = Mat(); Imgproc.cvtColor(m, bgr, if (m.channels() == 1) Imgproc.COLOR_GRAY2BGR else Imgproc.COLOR_RGB2BGR)
                                Imgcodecs.imwrite(File(renders, "${id}_$n.jpg").path, bgr); bgr.release(); m.release()
                            }
                        }
                        rgb.release()
                    }
                }
            } catch (t: Throwable) {
                sb.append(",\"err\":\"${t.toString().replace("\"", "'").take(200)}\"")
            }
            sb.append("}")
            out.println(sb); out.flush()
        }
        out.close()
        println("Jev: ${client.requests} peticiones, ${client.inputTokens} tokens de entrada")
    }
}
