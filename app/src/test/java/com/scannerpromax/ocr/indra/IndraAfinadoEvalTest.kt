package com.scannerpromax.ocr.indra

import com.scannerpromax.ocr.lexicon.Hunspell
import com.scannerpromax.ocr.lexicon.UserVocabulary
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max

/**
 * Banco del afinado de Indra (notes_indra_afinado.md), solo con -PindraEval=DIR. Opciones -PindraOpts="k=v;…":
 *  - src: raw (imagen del dataset) | GEO | ORIGINAL | MAGIC | BLACK_WHITE (salidas de la tubería real de la app en
 *    /tmp/claude-0/bench/indra_afinado/app/img)
 *  - prep: 1 = [IndraPrep] (por defecto) · doubt: suelo de las dudosas (2 = sin dudosas) · lex: 1 = léxico es_ES
 *  - lexbias / vocabbias: pesos del sesgo del LM · vocab: términos separados por comas · set: bench | user | all
 * Escribe DIR/<id>.txt (texto de la app: sin dudosas), DIR/dud/<id>.txt (con dudosas, en orden de lectura) y
 * DIR/<id>.json (líneas con confianza, dudosas, correcciones) para scripts/score.py y las hojas visuales.
 */
class IndraAfinadoEvalTest {

    private fun opts(): Map<String, String> = (System.getProperty("indra.opts") ?: "").split(';').filter { '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    @Test fun evaluacion() {
        val out = System.getProperty("indra.eval") ?: return
        val o = opts()
        val dir = File(System.getProperty("indra.dir") ?: "/tmp/claude-0/bench/indra/wlatin")
        val cv = runCatching { nu.pattern.OpenCV.loadLocally(); Mat(1, 1, 0).release(); true }.getOrDefault(false)
        assumeTrue(cv && IndraOcr.FILES.all { File(dir, it).isFile })
        val ocr = IndraOcr(dir, 4)
        ocr.doubtFloor = o["doubt"]?.toFloat() ?: IndraOcr.DOUBT_FLOOR
        val lex = if (o["lex"] == "1") {
            val a = File("src/main/assets/lexicon")
            File(a, "es_ES.aff.gz").inputStream().use { aff -> File(a, "es_ES.dic.gz").inputStream().use { dic -> Hunspell.load(aff, dic) } }
        } else null
        val vocab = UserVocabulary(o["vocabfile"]?.let { File(it).readLines(Charsets.UTF_8) } ?: o["vocab"]?.split(',')?.map { it.trim() }.orEmpty())
        val corr = IndraCorrector(lex, vocab, ocr.languageModel, ocr.vocab)
        o["lmgain"]?.let { IndraCorrector.lmGain = it.toDouble() }
        o["lmgainup"]?.let { IndraCorrector.lmGainUpper = it.toDouble() }
        val bias = corr.bias(o["lexbias"]?.toDouble() ?: IndraCorrector.LEX_BIAS, o["vocabbias"]?.toDouble() ?: IndraCorrector.VOCAB_BIAS)
        ocr.bias = bias.takeIf { it.active }
        val src = o["src"] ?: "GEO"
        val set = o["set"] ?: "all"
        val items = ArrayList<Pair<String, String>>()
        if (set != "user") {
            val csv = File("/tmp/claude-0/bench/ia_eval/subset.csv")
            for (line in csv.readLines().drop(1)) {
                val f = fields(line)
                if (f.size > 7 && (f[6] == "texto" || f[6] == "palabras")) items += f[0] to f[1]
            }
        }
        if (set != "bench") for (l in File("/tmp/claude-0/bench/indra_afinado/work/jobs_user.tsv").readLines()) {
            val f = l.split('\t'); if (f.size >= 2) items += f[0] to f[1]
        }
        File(out, "dud").mkdirs()
        var total = 0L; var nChanges = 0; var nDoubt = 0
        for ((id, path) in items) {
            val p = if (src == "raw") path else "/tmp/claude-0/bench/indra_afinado/app/img/${id}__$src." + if (src == "BLACK_WHITE") "png" else "jpg"
            val m = Imgcodecs.imread(p, Imgcodecs.IMREAD_GRAYSCALE)
            if (m.empty()) continue
            var g = Gray.fromMat(m); m.release()
            if (max(g.w, g.h) > IndraEngine.MAX_SIDE) {
                val s = IndraEngine.MAX_SIDE.toDouble() / max(g.w, g.h)
                g = IndraImage.resize(g, IndraImage.pyRound(g.w * s), IndraImage.pyRound(g.h * s), Imgproc.INTER_AREA)
            }
            val t0 = System.currentTimeMillis()
            val gp = if (o["prep"] != "0") IndraPrep.prepare(g) else g
            val page0 = ocr.run(gp)
            val r = corr.correct(page0)
            val page = r.page
            total += System.currentTimeMillis() - t0
            nChanges += r.changes.size
            val res = IndraResult.toOcrResult(page, g.w, g.h)
            nDoubt += res.doubtful.size
            File(out, "$id.txt").writeText(res.text)
            File(out, "dud/$id.txt").writeText(page.lines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text })
            val js = StringBuilder("{\"spanish\": ${r.spanish}, \"changes\": [")
            js.append(r.changes.joinToString(",") { "[${q(it.from)}, ${q(it.to)}, ${q(it.source)}]" })
            js.append("], \"lines\": [")
            js.append(page.lines.filter { it.text.isNotBlank() }.joinToString(",") { l ->
                "{\"box\": [${l.box.joinToString(",")}], \"text\": ${q(l.text)}, \"conf\": ${l.conf}, \"doubtful\": ${l.doubtful}, " +
                    "\"words\": [${l.words.joinToString(",") { w -> "[${q(w.text)}, ${w.conf}]" }}]}"
            })
            js.append("], \"skew\": ${page.skew}, \"rot\": ${page.rotation}, \"hw\": [${g.h}, ${g.w}]}")
            File(out, "$id.json").writeText(js.toString())
        }
        println("Indra afinado ($o): ${items.size} imágenes, ${total / max(1, items.size)} ms/img, $nChanges correcciones, $nDoubt líneas dudosas")
        ocr.close()
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private fun fields(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when {
            ch == '"' -> q = !q
            ch == ',' && !q -> { out += sb.toString(); sb.clear() }
            else -> sb.append(ch)
        }
        out += sb.toString()
        return out
    }
}
