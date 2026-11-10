package com.scannerpromax.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.scannerpromax.ai.parts.DocOrientation
import com.scannerpromax.ai.parts.Rgb
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale

/**
 * BANCO de orientación automática (no forma parte de la batería normal: solo con -PoriBench=DIR). Cada imagen DERECHA de
 * `DIR/src` (escaneos y fotos públicos; nunca en el repo) se gira 0/90/180/270 y se mide qué giro propone cada señal:
 *  - renglones ([TextOrientation], sin modelo);
 *  - doc_ori solo (p ≥ 0,4, como hacía la cola de la IA; y p ≥ 0,7);
 *  - la cadena ANTERIOR de la app (renglones + modelo al crear la página y, si quedaba en 0, la cola de la IA con doc_ori ≥ 0,4);
 *  - el voto NUEVO ([OrientationVote]) con renglones + modelo (Jev no se mide sin clave).
 * Escribe `DIR/resultados.tsv` (una fila por imagen y giro) y un resumen por pantalla.
 *   ./gradlew testDebugUnitTest --tests '*OrientationBenchTest*' -PoriBench=/tmp/claude-0/bench/datasets/ori -PoriModel=…/doc_ori.onnx
 */
class OrientationBenchTest {

    private class Ort(path: String) {
        val env: OrtEnvironment = OrtEnvironment.getEnvironment()
        val s: OrtSession = env.createSession(path, OrtSession.SessionOptions())
        val input: String = s.inputNames.first()
        fun run(chw: FloatArray): FloatArray {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, 224, 224)).use { t ->
                s.run(mapOf(input to t)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val v = r[0].value
                    val out = when (v) {
                        is Array<*> -> (v[0] as FloatArray)
                        is FloatArray -> v
                        else -> error("salida $v")
                    }
                    if (out.all { it in 0f..1f } && kotlin.math.abs(out.sum() - 1f) < 0.05f) return out
                    val m = out.max(); val e = out.map { kotlin.math.exp((it - m).toDouble()) }; val sm = e.sum()
                    return FloatArray(out.size) { (e[it] / sm).toFloat() }
                }
            }
        }
    }

    private fun toRgb(bgr: Mat, side: Int): Rgb {
        val m = Mat()
        if (maxOf(bgr.cols(), bgr.rows()) > side) Cv.downscale(bgr, m, side) else bgr.copyTo(m)
        val rgb = Mat(); Imgproc.cvtColor(m, rgb, Imgproc.COLOR_BGR2RGB); m.release()
        val w = rgb.cols(); val h = rgb.rows()
        val b = ByteArray(w * h * 3); rgb.get(0, 0, b); rgb.release()
        val px = IntArray(w * h) { i -> (0xFF shl 24) or ((b[i * 3].toInt() and 0xFF) shl 16) or ((b[i * 3 + 1].toInt() and 0xFF) shl 8) or (b[i * 3 + 2].toInt() and 0xFF) }
        return Rgb(w, h, px)
    }

    private class Tally { var ok = 0; var wrong = 0; var abstain = 0; var n = 0 }

    @Test fun banco() {
        val dir = System.getProperty("ori.bench")?.let(::File)
        assumeTrue("sin -PoriBench", dir != null && File(dir, "src").isDirectory)
        assumeTrue("sin OpenCV de escritorio", SyntheticScenes.openCv)
        val ort = System.getProperty("ori.model")?.takeIf { File(it).isFile }?.let { Ort(it) }
        val exclude = File(dir, "exclude.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        val scans = File(dir, "src").listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }!!.sortedBy { it.name }
        // fotos con las 4 esquinas (DocCornerDataset): se rectifican como en la app; prefijo "foto"
        val corners = File(dir, "photos/corners.tsv").takeIf { it.isFile }?.readLines()?.mapNotNull { l ->
            val p = l.split('\t'); if (p.size < 9) null else p[0] to p.drop(1).map { it.toDouble() }
        }?.distinctBy { it.first } ?: emptyList()
        val rectDir = File(dir, "photos_rect").also { it.mkdirs() }
        val files: List<Pair<String, () -> Mat>> = scans.filter { it.name !in exclude }.map { f -> f.name to { Imgcodecs.imread(f.absolutePath) } } +
            corners.filter { "foto_" + it.first !in exclude && File(dir, "photos/" + it.first).isFile }.map { (n, c) ->
                ("foto_$n") to {
                    val im = Imgcodecs.imread(File(dir, "photos/$n").absolutePath)
                    val w = im.cols().toDouble(); val h = im.rows().toDouble()
                    val q = (0 until 4).map { org.opencv.core.Point(c[it * 2] * w, c[it * 2 + 1] * h) }
                    fun dist(a: org.opencv.core.Point, b: org.opencv.core.Point) = kotlin.math.hypot(a.x - b.x, a.y - b.y)
                    val ow = maxOf(dist(q[0], q[1]), dist(q[3], q[2])); val oh = maxOf(dist(q[0], q[3]), dist(q[1], q[2]))
                    val m = Imgproc.getPerspectiveTransform(org.opencv.core.MatOfPoint2f(*q.toTypedArray()),
                        org.opencv.core.MatOfPoint2f(org.opencv.core.Point(0.0, 0.0), org.opencv.core.Point(ow, 0.0), org.opencv.core.Point(ow, oh), org.opencv.core.Point(0.0, oh)))
                    val out = Mat(); Imgproc.warpPerspective(im, out, m, org.opencv.core.Size(ow, oh), Imgproc.INTER_CUBIC)
                    im.release(); m.release()
                    val rf = File(rectDir, n); if (!rf.exists()) { val s = Mat(); Cv.downscale(out, s, 400); Imgcodecs.imwrite(rf.absolutePath, s); s.release() }
                    out
                }
            }
        val methods = listOf("renglones", "doc_ori>=0.4", "doc_ori>=0.7", "anterior", "nuevo", "nuevo_sin_doc_ori")
        val tallies = methods.associateWith { mapOf("0" to Tally(), "90/270" to Tally(), "180" to Tally(), "todo" to Tally()) }
        val bySrc = HashMap<String, MutableMap<String, Tally>>()
        val out = StringBuilder("imagen\tgiro\tverdad\t" + methods.joinToString("\t") + "\trenglones_det\tp_has\n")
        var tLines = 0L; var tModel = 0L; var cases = 0
        for ((fname, load) in files) {
            val img = load()
            if (img.empty()) continue
            val page0 = Mat(); if (maxOf(img.cols(), img.rows()) > 1600) Cv.downscale(img, page0, 1600) else img.copyTo(page0)
            img.release()
            for (r in intArrayOf(0, 90, 180, 270)) {
                val page = Mat()
                when (r) {
                    90 -> Core.rotate(page0, page, Core.ROTATE_90_CLOCKWISE)
                    180 -> Core.rotate(page0, page, Core.ROTATE_180)
                    270 -> Core.rotate(page0, page, Core.ROTATE_90_COUNTERCLOCKWISE)
                    else -> page0.copyTo(page)
                }
                val truth = (360 - r) % 360
                val gray = Mat(); Imgproc.cvtColor(page, gray, Imgproc.COLOR_BGR2GRAY)
                var t0 = System.nanoTime()
                val lr = TextOrientation.estimate(gray)
                tLines += System.nanoTime() - t0
                gray.release()
                t0 = System.nanoTime()
                val p = ort?.let { o -> DocOrientation.predictProbs(toRgb(page, 1600)) { o.run(it) } }
                tModel += System.nanoTime() - t0
                page.release(); cases++
                val linesOnly = TextOrientation.decide(lr, null)
                fun model(th: Float): Int = p?.let { DocOrientation.clockwise(DocOrientation.correction(DocOrientation.Result((0 until 4).maxByOrNull { p[it] }!! * 90, p.max()), th)) } ?: 0
                // cadena anterior: al crear la página, el modelo sólo se consultaba si podía cambiar la decisión
                val atCreate = TextOrientation.decide(lr, if (lr.vertical == true || lr.rotation == 180 || !lr.sure) p else null)
                val before = if (atCreate != 0) atCreate else model(0.4f)
                val now = OrientationVote.decide(lr, p, null).rotation
                val nowLines = OrientationVote.decide(lr, null, null).rotation
                val res = listOf(linesOnly, model(0.4f), model(0.7f), before, now, nowLines)
                val cls = when (truth) { 0 -> "0"; 180 -> "180"; else -> "90/270" }
                val src = fname.substringBefore('_')
                for ((i, m) in methods.withIndex()) {
                    for (t in listOf(tallies.getValue(m).getValue(cls), tallies.getValue(m).getValue("todo"), bySrc.getOrPut("$src|$m") { HashMap() }.getOrPut("todo") { Tally() })) {
                        t.n++
                        when {
                            res[i] == truth -> t.ok++
                            res[i] == 0 -> t.abstain++
                            else -> t.wrong++
                        }
                    }
                }
                out.append("$fname\t$r\t$truth\t${res.joinToString("\t")}\t$lr\t${p?.joinToString(",") { "%.2f".format(Locale.ROOT, it) } ?: "-"}\n")
            }
            page0.release()
        }
        File(dir, "resultados.tsv").writeText(out.toString())
        val sb = StringBuilder("\nBANCO ORIENTACIÓN: ${files.size} imágenes x 4 giros = $cases casos; renglones ${tLines / 1_000_000 / maxOf(1, cases)} ms/caso, doc_ori ${tModel / 1_000_000 / maxOf(1, cases)} ms/caso\n")
        sb.append("método           | clase  | acierto | giro erróneo | sin girar\n")
        for (m in methods) for (c in listOf("0", "90/270", "180", "todo")) {
            val t = tallies.getValue(m).getValue(c)
            sb.append("%-16s | %-6s | %5.1f %% | %5.1f %% | %5.1f %%   (n=%d)\n".format(Locale.ROOT, m, c, 100.0 * t.ok / maxOf(1, t.n), 100.0 * t.wrong / maxOf(1, t.n), 100.0 * t.abstain / maxOf(1, t.n), t.n))
        }
        sb.append("por fuente (acierto / erróneo):\n")
        for (k in bySrc.keys.sorted()) { val t = bySrc.getValue(k).getValue("todo"); sb.append("  %-24s %5.1f %% / %5.1f %%\n".format(Locale.ROOT, k, 100.0 * t.ok / t.n, 100.0 * t.wrong / t.n)) }
        println(sb)
        File(dir, "resumen.txt").writeText(sb.toString())
    }
}
