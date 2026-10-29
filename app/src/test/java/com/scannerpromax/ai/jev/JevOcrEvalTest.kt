package com.scannerpromax.ai.jev

import com.scannerpromax.ocr.indra.Gray
import com.scannerpromax.ocr.indra.IndraCorrector
import com.scannerpromax.ocr.indra.IndraEngine
import com.scannerpromax.ocr.indra.IndraImage
import com.scannerpromax.ocr.indra.IndraOcr
import com.scannerpromax.ocr.indra.IndraPrep
import com.scannerpromax.ocr.indra.IndraResult
import com.scannerpromax.ocr.lexicon.Hunspell
import com.scannerpromax.ocr.lexicon.UserVocabulary
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/**
 * Banco del OCR con Jev (solo con -PjevOcr=DIR; usa la red y la clave de /tmp/claude-0/bench/.jev_key). Mismo
 * subconjunto que IndraAfinadoEvalTest (texto de ia_eval/subset.csv, imagen del dataset, prep + léxico). Escribe
 * DIR/base/<id>.txt (Indra + corrector), DIR/jev/<id>.txt (con Jev) y DIR/report.tsv (cambios, promovidas, tokens).
 * Opciones -PjevOpts="minp=0.85;minline=0.9;lines=1;words=1". Caché de respuestas en /tmp/claude-0/bench/jev/cache_kt.
 */
class JevOcrEvalTest {

    private class Caching(private val dir: File, private val inner: JevTransport) : JevTransport {
        init { dir.mkdirs() }
        override fun post(url: String, headers: Map<String, String>, body: String): JevHttpReply {
            val h = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            val f = File(dir, "$h.json")
            if (f.isFile) return JevHttpReply(200, f.readText())
            return inner.post(url, headers, body).also { if (it.status == 200) f.writeText(it.body) }
        }
    }

    @Test fun evaluacion() {
        val out = System.getProperty("jev.ocr") ?: return
        val o = (System.getProperty("jev.opts") ?: "").split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val keyFile = File("/tmp/claude-0/bench/.jev_key")
        val dir = File("/tmp/claude-0/bench/indra/wlatin")
        val cv = runCatching { nu.pattern.OpenCV.loadLocally(); Mat(1, 1, 0).release(); true }.getOrDefault(false)
        assumeTrue(cv && keyFile.isFile && IndraOcr.FILES.all { File(dir, it).isFile })
        val ocr = IndraOcr(dir, 4)
        val a = File("src/main/assets/lexicon")
        val lex = File(a, "es_ES.aff.gz").inputStream().use { aff -> File(a, "es_ES.dic.gz").inputStream().use { dic -> Hunspell.load(aff, dic) } }
        val corr = IndraCorrector(lex, UserVocabulary(emptyList()), ocr.languageModel, ocr.vocab)
        ocr.bias = corr.bias().takeIf { it.active }
        val client = JevClient(keyFile.readText().trim(), Caching(File("/tmp/claude-0/bench/jev/cache_kt"), OkHttpJevTransport()))
        val jev = JevOcr(
            suggest = { w -> corr.suggestions(w) },
            minP = o["minp"]?.toDouble() ?: JevOcr.MIN_P,
            minLineP = if (o["lines"] == "0") 2.0 else o["minline"]?.toDouble() ?: JevOcr.MIN_LINE_P,
        )
        val items = ArrayList<Pair<String, String>>()
        for (line in File("/tmp/claude-0/bench/ia_eval/subset.csv").readLines().drop(1)) {
            val f = line.split(',')
            if (f.size > 7 && (f[6] == "texto" || f[6] == "palabras")) items += f[0] to f[1]
        }
        File(out, "base").mkdirs(); File(out, "jev").mkdirs()
        val rep = StringBuilder("id\twords\tlines\tchanged\tpromoted\ttokens\tms\tdetail\n")
        var tok = 0L
        for ((id, path) in items) {
            var m = Imgcodecs.imread(path, Imgcodecs.IMREAD_GRAYSCALE)
            if (m.empty()) continue
            var g = Gray.fromMat(m)
            if (max(g.w, g.h) > IndraEngine.MAX_SIDE) {
                val s = IndraEngine.MAX_SIDE.toDouble() / max(g.w, g.h)
                g = IndraImage.resize(g, IndraImage.pyRound(g.w * s), IndraImage.pyRound(g.h * s), org.opencv.imgproc.Imgproc.INTER_AREA)
                m.release(); m = g.toMat()
            }
            val page = corr.correct(ocr.run(IndraPrep.prepare(g))).page
            val res = IndraResult.toOcrResult(page, g.w, g.h)
            File(out, "base/$id.txt").writeText(res.text)
            val t0 = System.currentTimeMillis()
            val (fixed, r) = runCatching { jev.apply(res, m, client) }.getOrElse { e -> println("$id JEV ERROR $e"); res to null }
            val ms = System.currentTimeMillis() - t0
            File(out, "jev/$id.txt").writeText(fixed.text)
            if (r != null) {
                File(out, "dec").mkdirs(); File(out, "dec/$id.tsv").writeText(r.decisions.joinToString("\n"))
                tok += r.inputTokens
                rep.append("$id\t${r.words}\t${r.lines}\t${r.changed.size}\t${r.promoted.size}\t${r.inputTokens}\t$ms\t" +
                    r.changed.joinToString(" | ") { "${it.first}->${it.second}" } + " || " + r.promoted.joinToString(" | ") + "\n")
            }
            m.release()
        }
        File(out, "report.tsv").writeText(rep.toString())
        println("Jev OCR ($o): ${items.size} imágenes, $tok tokens")
        ocr.close()
    }
}
