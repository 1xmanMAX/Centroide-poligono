package com.scannerpromax.ocr.indra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Paridad del puerto Kotlin de Indra con el Python original (indra-ocr 1.1.2, solo latín) y prueba con imágenes
 * sintéticas. Usa los pesos y las referencias del banco si existen (no se copian al repo):
 *  - pesos: /tmp/claude-0/bench/indra/wlatin (o -PindraDir=…): det/rec/order/orient.onnx, meta.json, lm.npz
 *  - referencias: /tmp/claude-0/bench/indra/parity (o -PindraParity=…): <id>.png + <id>.json de scripts/parity_ref.py
 * Sin ellos los tests se saltan. -PindraEval=DIR además lee el subconjunto OCR del banco y escribe DIR/<id>.txt.
 */
class IndraParityTest {

    companion object {
        private val dir = File(System.getProperty("indra.dir") ?: "/tmp/claude-0/bench/indra/wlatin")
        private val parity = File(System.getProperty("indra.parity") ?: "/tmp/claude-0/bench/indra/parity")
        private var ocr: IndraOcr? = null
        private val openCv: Boolean by lazy { runCatching { nu.pattern.OpenCV.loadLocally(); Mat(1, 1, 0).release(); true }.getOrDefault(false) }

        @BeforeClass @JvmStatic fun load() {
            if (openCv && IndraOcr.FILES.all { File(dir, it).isFile }) ocr = IndraOcr(dir, 4)
        }

        @AfterClass @JvmStatic fun close() { ocr?.close() }

        fun cer(a: String, b: String): Double {
            if (b.isEmpty()) return if (a.isEmpty()) 0.0 else 1.0
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1); cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.length].toDouble() / b.length
        }

        fun iou(a: FloatArray, b: FloatArray): Float {
            val iw = min(a[2], b[2]) - max(a[0], b[0]); val ih = min(a[3], b[3]) - max(a[1], b[1])
            if (iw <= 0 || ih <= 0) return 0f
            val inter = iw * ih
            return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)
        }

        fun readGray(f: File): Gray {
            val m = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
            return Gray.fromMat(m).also { m.release() }
        }
    }

    private fun need(): IndraOcr { assumeTrue("pesos de Indra no disponibles en $dir", ocr != null); return ocr!! }

    private fun box(e: kotlinx.serialization.json.JsonElement) = e.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()

    @Test fun modeloDeLenguajeIgualQuePython() {
        need()
        val f = File(parity, "lm_keys.json"); assumeTrue(f.isFile)
        val j = Json.parseToJsonElement(f.readText()).jsonObject
        val lm = IndraLm.load(File(dir, "lm.npz"))
        assertEquals(j.getValue("max_logp").jsonPrimitive.double, lm.maxLogp, 1e-5)
        val keys = j.getValue("keys").jsonArray.map { it.jsonPrimitive.long }
        val ref = j.getValue("logp").jsonArray.map { it.jsonPrimitive.double }
        for (i in keys.indices) assertEquals("clave ${keys[i]}", ref[i], lm.logpKey(keys[i]), 1e-5)
    }

    /** Página por página: orientación, inclinación, detección, reconocimiento y resultado final frente a Python. */
    @Test fun paridadConPython() {
        val o = need()
        val ids = parity.listFiles { f -> f.name.endsWith(".json") && f.name != "lm_keys.json" }?.map { it.name.removeSuffix(".json") }?.sorted().orEmpty()
        assumeTrue("sin referencias en $parity", ids.isNotEmpty())
        var refChars = 0; var errChars = 0.0; var errSorted = 0.0; var sameLines = 0; var allLines = 0; var detIou = 0.0; var detN = 0
        val report = StringBuilder()
        for (id in ids) {
            val j = Json.parseToJsonElement(File(parity, "$id.json").readText()) as JsonObject
            val g = readGray(File(parity, "$id.png"))
            val t0 = System.currentTimeMillis()
            val page = o.run(g)
            val ms = System.currentTimeMillis() - t0
            // orientación + inclinación
            val rot = j.getValue("rotation").jsonPrimitive.int
            val skew = j.getValue("skew").jsonPrimitive.double
            assertEquals("$id: giro", rot, page.rotation)
            // En el umbral DESKEW_MIN (0,3°) un lado puede quedarse en 0.
            val skewOk = abs(page.skew - skew) <= 0.15 || (min(abs(page.skew), abs(skew)) == 0.0 && max(abs(page.skew), abs(skew)) <= 0.45)
            assertTrue("$id: inclinación ${page.skew} vs $skew", skewOk)
            // detección y reconocimiento sobre la página enderezada como en Python
            var gg = IndraImage.rot90(g, -rot / 90)
            if (skew != 0.0) gg = IndraImage.rotatePage(gg, skew)
            val det = o.detect(gg)
            val pyDet = j.getValue("det").jsonArray.map { box(it) }
            for (b in pyDet) { detIou += det.maxOfOrNull { iou(it, b) } ?: 0f; detN++ }
            val rec = o.recognize(gg, det)
            val pyRec = j.getValue("rec").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
            val pyConf = j.getValue("rec").jsonArray.map { it.jsonObject.getValue("conf").jsonPrimitive.double }
            if (System.getProperty("indra.debug") != null) rec.zip(pyRec).withIndex().filter { it.value.first.text != it.value.second }.take(6).forEach { (i, p) ->
                println("  DIF $id[$i] kt='${p.first.text}' (${p.first.conf}) py='${p.second}' (${pyConf[i]}) box=${det[i].toList()} py=${pyDet.getOrNull(i)?.toList()}")
            }
            // final
            val pyLines = j.getValue("lines").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content.trim() }
            val ktText = page.lines.joinToString("\n") { it.text }
            val pyText = pyLines.joinToString("\n")
            val c = cer(ktText, pyText)
            // Sin depender del orden de lectura: líneas ordenadas alfabéticamente.
            val pySorted = pyLines.sorted().joinToString("\n")
            val cs = cer(page.lines.map { it.text }.sorted().joinToString("\n"), pySorted)
            refChars += pyText.length; errChars += c * pyText.length; errSorted += cs * pyText.length
            val pySet = pyLines.groupingBy { it }.eachCount().toMutableMap()
            for (l in page.lines) { val n = pySet[l.text] ?: 0; if (n > 0) { sameLines++; pySet[l.text] = n - 1 } }
            allLines += pyLines.size
            val pyBlocks = j.getValue("blocks").jsonArray.size
            if (System.getProperty("indra.debug") != null) {
                val kt = page.lines.map { it.text }
                println("  SOLO-KT $id: ${(kt - pyLines.toSet()).take(8)}")
                println("  SOLO-PY $id: ${(pyLines - kt.toSet()).take(8)}")
                println("  VACIAS $id: kt ${kt.count { it.isBlank() }} py ${pyLines.count { it.isBlank() }}; kt=${kt.take(12)}; py=${pyLines.take(12)}")
            }
            report.append("$id: ${ms} ms, det ${det.size}/${pyDet.size}, rec iguales ${rec.zip(pyRec).count { it.first.text == it.second }}/${pyRec.size}, " +
                "líneas ${page.lines.size}/${pyLines.size}, bloques ${page.blocks.size}/$pyBlocks, CER vs Python ${"%.3f".format(c)}\n")
        }
        println(report)
        val cerAll = errChars / max(1, refChars)
        val cerSorted = errSorted / max(1, refChars)
        println("TOTAL: CER Kotlin vs Python ${"%.4f".format(cerAll)} (sin orden ${"%.4f".format(cerSorted)}), líneas idénticas $sameLines/$allLines, IoU medio de detección ${"%.3f".format(detIou / max(1, detN))}")
        // ORT de escritorio 1.31 (Java) frente a 1.29 (Python) y OpenCV 4.9 frente a 5.0: diferencias de décimas de
        // píxel que cambian alguna lectura dudosa (manuscrito con LM) o el corte de una línea; el texto debe coincidir.
        assertTrue("CER sin orden Kotlin vs Python $cerSorted", cerSorted < 0.04)
        assertTrue("CER Kotlin vs Python $cerAll", cerAll < 0.03)
        assertTrue("líneas idénticas $sameLines/$allLines", sameLines >= 0.9 * allLines)
        assertTrue("IoU de detección", detIou / max(1, detN) > 0.95)
    }

    /** Imagen sintética dibujada aquí (sin referencias de Python): texto impreso, girada 90° y algo inclinada. */
    @Test fun sinteticaSeLeeBien() {
        val o = need()
        val lines = listOf("INVOICE 2026-10-25", "Total: 152.40 EUR", "Thank you for your visit")
        val m = Mat(420, 1400, 0, Scalar(250.0))
        lines.forEachIndexed { i, t -> Imgproc.putText(m, t, Point(40.0, 90.0 + 110 * i), Imgproc.FONT_HERSHEY_SIMPLEX, 1.6, Scalar(20.0), 3, Imgproc.LINE_AA) }
        val g = Gray.fromMat(m); m.release()
        val page = o.run(g)
        assertEquals(lines, page.lines.map { it.text })
        assertEquals(0, page.rotation)
        // Palabras con caja dentro de su línea, en orden.
        val w = page.lines[1].words
        assertEquals(listOf("Total:", "152.40", "EUR"), w.map { it.text })
        assertTrue(w.zipWithNext().all { (a, b) -> a.box[2] <= b.box[0] + 2 })
        // Girada 90° (contenido en sentido antihorario): la orientación la endereza y las cajas vuelven a la imagen original.
        val m9 = Mat(1100, 1400, 0, Scalar(250.0))
        val lines9 = lines + lines + lines
        lines9.forEachIndexed { i, t -> Imgproc.putText(m9, t, Point(40.0, 90.0 + 110 * i), Imgproc.FONT_HERSHEY_SIMPLEX, 1.6, Scalar(20.0), 3, Imgproc.LINE_AA) }
        val rotated = IndraImage.rot90(Gray.fromMat(m9), 1)
        m9.release()
        // orient.onnx solo gira por encima de 0,95 (con letra Hershey da ~0,87): se comprueba que acierta la clase y
        // se usa el giro como pista (quarterTurns, como haría la orientación de la app).
        val probs = o.orientationProbs(IndraImage.preview(rotated))!!
        assertEquals(1, probs.indices.maxByOrNull { probs[it] })
        val p2 = o.run(rotated, quarterTurns = 1)
        assertEquals(90, p2.rotation)
        assertEquals(lines9, p2.lines.map { it.text })
        val r = IndraResult.toOcrResult(p2, rotated.w, rotated.h)
        val first = r.blocks.first().lines.first()
        // En la imagen girada la primera línea es una columna vertical a la izquierda: alta y estrecha.
        assertTrue(first.box.bottom - first.box.top > 3 * (first.box.right - first.box.left))
        assertTrue(first.box.left < rotated.w * 0.4f)
        assertEquals(-90f, first.angle, 0.5f)
    }

    /** Subconjunto OCR del banco (54 imágenes con referencia) → DIR/<id>.txt para tools/ia_score.py. */
    @Test fun evaluacionBanco() {
        val out = System.getProperty("indra.eval") ?: return
        val o = need()
        val csv = File("/tmp/claude-0/bench/ia_eval/subset.csv"); assumeTrue(csv.isFile)
        File(out).mkdirs()
        fun fields(line: String): List<String> {  // CSV con comillas
            val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
            for (ch in line) when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            out += sb.toString()
            return out
        }
        val rows = csv.readLines().drop(1).map { fields(it) }.filter { it.size > 7 && (it[6] == "texto" || it[6] == "palabras") }
        var total = 0L
        for (r in rows) {
            val m = Imgcodecs.imread(r[1], Imgcodecs.IMREAD_GRAYSCALE)
            var g = Gray.fromMat(m); m.release()
            if (max(g.w, g.h) > IndraEngine.MAX_SIDE) {
                val s = IndraEngine.MAX_SIDE.toDouble() / max(g.w, g.h)
                g = IndraImage.resize(g, IndraImage.pyRound(g.w * s), IndraImage.pyRound(g.h * s), Imgproc.INTER_AREA)
            }
            val t0 = System.currentTimeMillis()
            val page = o.run(g)
            val ms = System.currentTimeMillis() - t0
            total += ms
            File(out, "${r[0]}.txt").writeText(page.text)
            File(out, "${r[0]}.json").writeText("{\"wall_s\": ${ms / 1000.0}}")
        }
        println("Indra Kotlin: ${rows.size} imágenes, media ${total / max(1, rows.size)} ms")
    }
}
