package com.scannerpromax.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.scannerpromax.ai.parts.DocOrientation
import com.scannerpromax.ai.parts.Rgb
import com.scannerpromax.imaging.SyntheticCards.Background
import com.scannerpromax.imaging.SyntheticCards.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale
import java.util.Random
import kotlin.math.hypot

/**
 * Orientación de las caras del DNI ([CardOrientation]) con DNI peruanos SINTÉTICOS ([SyntheticCards]): anverso y
 * reverso de las 4 paletas, fotografiados a 0/90/180/270° (± 12°) sobre fondos variados. «Antes» = la cadena anterior
 * (esquinas desde la de menor x+y y solo la MRZ, sin forzar apaisado); «después» = [CardOrientation.orient].
 * Acierto = la cara rectificada sale APAISADA y DERECHA. El banco completo (-PcardOriBench=salida.md) escribe la tabla;
 * la batería normal corre un subconjunto con umbrales.
 */
class CardOrientationBenchTest {

    @Before fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    /** doc_ori incluido en la app (ONNX Runtime de escritorio en los tests). */
    private val docOri: ((Mat) -> FloatArray?)? by lazy {
        val f = listOf(File("src/main/assets/models/pp_lcnet_doc_ori.onnx"), File("app/src/main/assets/models/pp_lcnet_doc_ori.onnx")).firstOrNull { it.isFile }
            ?: return@lazy null
        runCatching {
            val env = OrtEnvironment.getEnvironment()
            val s = env.createSession(f.absolutePath, OrtSession.SessionOptions())
            val input = s.inputNames.first()
            val runner = DocOrientation.Runner { chw ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, 224, 224)).use { t ->
                    s.run(mapOf(input to t)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val v = r[0].value
                        val out = when (v) { is Array<*> -> v[0] as FloatArray; is FloatArray -> v; else -> error("salida") }
                        if (out.all { it in 0f..1f } && kotlin.math.abs(out.sum() - 1f) < 0.05f) out else {
                            val m = out.max(); val e = out.map { kotlin.math.exp((it - m).toDouble()) }; val sm = e.sum()
                            FloatArray(out.size) { (e[it] / sm).toFloat() }
                        }
                    }
                }
            }
            val fn: (Mat) -> FloatArray? = { m -> DocOrientation.predictProbs(rgbOf(m), runner) }
            fn
        }.getOrNull()
    }

    private fun rgbOf(m: Mat): Rgb {
        val w = m.cols(); val h = m.rows()
        val b = ByteArray(w * h * 3); m.get(0, 0, b)
        return Rgb(w, h, IntArray(w * h) { i -> (0xFF shl 24) or ((b[i * 3].toInt() and 0xFF) shl 16) or ((b[i * 3 + 1].toInt() and 0xFF) shl 8) or (b[i * 3 + 2].toInt() and 0xFF) })
    }

    /** ¿El rectificado de [q] sale apaisado y con la esquina superior izquierda de la tarjeta ([gt] TL) arriba a la izquierda? */
    private fun upright(q: DoubleArray, gt: DoubleArray): Boolean {
        val landscape = hypot(q[2] - q[0], q[3] - q[1]) + hypot(q[6] - q[4], q[7] - q[5]) >= hypot(q[4] - q[2], q[5] - q[3]) + hypot(q[0] - q[6], q[1] - q[7])
        val near = (0 until 4).minByOrNull { hypot(q[it * 2] - gt[0], q[it * 2 + 1] - gt[1]) }
        return landscape && near == 0
    }

    private class Case(val name: String, val palette: Palette, val back: Boolean, val rot: Int, val before: Boolean, val after: Boolean, val afterNoModel: Boolean, val why: String)

    private fun scenes(full: Boolean): List<Triple<Palette, Boolean, Pair<Background, Int>>> {
        val bgs = if (full) listOf(Background.MADERA, Background.MESA_BLANCA, Background.OSCURO, Background.AZUL_MARINO, Background.VIOLETA) else listOf(Background.MADERA, Background.OSCURO)
        val out = ArrayList<Triple<Palette, Boolean, Pair<Background, Int>>>()
        for (p in Palette.entries) for (back in listOf(false, true)) for (b in bgs) for (r in intArrayOf(0, 90, 180, 270)) out += Triple(p, back, b to r)
        return out
    }

    private fun run(full: Boolean, withModel: Boolean): List<Case> {
        val rnd = Random(11)
        val out = ArrayList<Case>()
        var seed = 500L
        for ((p, back, br) in scenes(full)) {
            val (b, r) = br
            val deg = r + (rnd.nextDouble() * 24 - 12)
            val sc = SyntheticCards.scene(p, back, b, seed++, deg = deg, shadow = seed % 3 == 0L, glare = seed % 5 == 0L)
            try {
                val det = CardColorDetector.detect(sc.rgb, null) ?: continue
                if (SyntheticCards.iou(det.quad, sc.gt, sc.rgb.cols(), sc.rgb.rows()) < 0.85) continue
                val before = CardColorDetector.orientByMrz(sc.rgb, det.quad)
                val (after, d) = CardOrientation.orient(sc.rgb, det.quad, if (withModel) docOri else null)
                val (afterNm, _) = if (withModel) CardOrientation.orient(sc.rgb, det.quad, null) else after to d
                out += Case(sc.name + "_r$r", p, back, r, upright(before, sc.gt), upright(after, sc.gt), upright(afterNm, sc.gt), d.toString())
            } finally {
                sc.rgb.release()
            }
        }
        return out
    }

    @Test fun subconjunto_derechasYApaisadas() {
        val cases = run(full = false, withModel = false)
        assertTrue("pocas escenas: ${cases.size}", cases.size >= 50)
        val ok = cases.count { it.after }
        val before = cases.count { it.before }
        val fails = cases.filter { !it.after }.joinToString("\n") { "${it.name}: ${it.why}" }
        assertTrue("después $ok/${cases.size} (antes $before)\n$fails", ok >= cases.size * 0.85)
        assertTrue("no mejora: antes $before, después $ok", ok > before)
    }

    /** La hoja compuesta (2 caras derechas y apaisadas en un A4) NO debe girarse con el voto de páginas. */
    @Test fun hojaCompuestaNoSeGira() {
        val sheets = composedSheets()
        try {
            for ((name, sheet) in sheets) {
                val g = Cv.gray(sheet)
                val lines = try { TextOrientation.estimate(g) } finally { g.release() }
                val linesOnly = OrientationVote.decide(lines, null, null)
                assertEquals("$name: renglones giran la hoja ($linesOnly)", 0, linesOnly.rotation)
            }
        } finally {
            sheets.forEach { it.second.release() }
        }
    }

    /** Hojas A4 (RGB, 1240 x 1754) con anverso + reverso derechos, como IdCardComposer (a 150 ppp). */
    private fun composedSheets(portraitFront: Boolean = false): List<Pair<String, Mat>> {
        val out = ArrayList<Pair<String, Mat>>()
        val rnd = Random(3)
        for (p in Palette.entries) {
            val f = SyntheticCards.card(p, false, 800, rnd)
            val b = SyntheticCards.card(p, true, 800, rnd)
            val ff = if (portraitFront) Mat().also { Core.rotate(f, it, Core.ROTATE_90_CLOCKWISE); f.release() } else f
            val cards = listOf(ff, b)
            val rects = IdCardComposer.layout(cards.map { it.cols() to it.rows() }, IdCardSize.LARGE.scale)
            val sheet = Mat(IdCardComposer.PAGE_H / 2, IdCardComposer.PAGE_W / 2, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
            for ((i, c) in cards.withIndex()) {
                val r = rects[i].map { it / 2 }
                val rs = Mat(); Imgproc.resize(c, rs, Size((r[2] - r[0]).toDouble(), (r[3] - r[1]).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                rs.copyTo(sheet.submat(Rect(r[0], r[1], r[2] - r[0], r[3] - r[1])))
                rs.release(); c.release()
            }
            out += p.name.lowercase() to sheet
        }
        return out
    }

    /** Banco completo con tabla: -PcardOriBench=/tmp/claude-0/bench/dni2_orient.md */
    @Test fun banco() {
        val outPath = System.getProperty("card.ori.bench")
        assumeTrue("sin -PcardOriBench", outPath != null)
        val model = docOri
        val t0 = System.nanoTime()
        val cases = run(full = true, withModel = model != null)
        val ms = (System.nanoTime() - t0) / 1e6 / cases.size.coerceAtLeast(1)
        val sb = StringBuilder()
        fun pct(n: Int, d: Int) = if (d == 0) "-" else "%.1f %%".format(Locale.ROOT, 100.0 * n / d)
        sb.appendLine("# Orientación de las caras del DNI (sintético peruano)")
        sb.appendLine()
        sb.appendLine("Escenas detectadas: ${cases.size} (4 paletas × 2 caras × 5 fondos × 4 giros ±12°). doc_ori: ${if (model != null) "sí" else "no"}. ${"%.0f".format(Locale.ROOT, ms)} ms/escena (detección + orientación, JVM).")
        sb.appendLine()
        sb.appendLine("| grupo | n | antes | después | después sin doc_ori |")
        sb.appendLine("|---|---|---|---|---|")
        fun row(label: String, c: List<Case>) = sb.appendLine("| $label | ${c.size} | ${pct(c.count { it.before }, c.size)} | ${pct(c.count { it.after }, c.size)} | ${pct(c.count { it.afterNoModel }, c.size)} |")
        row("todas", cases)
        for (r in intArrayOf(0, 90, 180, 270)) row("giro $r°", cases.filter { it.rot == r })
        for (p in Palette.entries) for (back in listOf(false, true)) row("${p.name.lowercase()} ${if (back) "reverso" else "anverso"}", cases.filter { it.palette == p && it.back == back })
        // señales por separado sobre la cara bien recortada con la VERDAD de cada escena (derecha y del revés)
        sb.appendLine()
        sb.appendLine("## Señales sobre la cara apaisada (escenas, recorte de verdad, derecha y del revés)")
        sb.appendLine()
        val names = listOf("mrz", "cara", "renglones", "doc_ori", "suma")
        val tally = names.associateWith { IntArray(3) }
        val probs = StringBuilder()
        var seed = 900L
        for ((p, back, br) in scenes(true).filter { it.third.second == 0 }) {
            val sc = SyntheticCards.scene(p, back, br.first, seed++, shadow = seed % 3 == 0L, glare = seed % 5 == 0L)
            for (flip in listOf(false, true)) {
                val w = CardColorDetector.warpCard(sc.rgb, if (flip) CardOrientation.shift(sc.gt, 180) else sc.gt, 900)
                val cu = CardOrientation.cues(w, model)
                if (model != null) { val pr = model(w)!!; probs.append("${sc.name}${if (flip) "_180" else ""}: p0=%.2f p180=%.2f cara=%.2f mrz=%.0f\n".format(Locale.ROOT, pr[0], pr[2], cu.face, cu.mrz)) }
                w.release()
                val want = if (flip) -1 else 1
                for ((n, v) in names.zip(listOf(cu.mrz, cu.face, cu.lines, cu.model, cu.total))) {
                    val t = tally.getValue(n)
                    val s = if (n == "suma") (if (v >= CardOrientation.MIN_SCORE) 1 else if (v <= -CardOrientation.MIN_SCORE) -1 else 0) else (if (v > 0) 1 else if (v < 0) -1 else 0)
                    when { s == 0 -> t[2]++; s == want -> t[0]++; else -> t[1]++ }
                }
            }
            sc.rgb.release()
        }
        sb.appendLine("| señal | aciertos | errores | abstención |")
        sb.appendLine("|---|---|---|---|")
        for (n in names) { val t = tally.getValue(n); sb.appendLine("| $n | ${t[0]} | ${t[1]} | ${t[2]} |") }
        // hoja compuesta: ¿el voto de páginas la giraría?
        sb.appendLine()
        sb.appendLine("## Hoja compuesta (A4 con las 2 caras) ante el voto de orientación de PÁGINAS (OrientationVote)")
        sb.appendLine()
        sb.appendLine("| hoja | caras apaisadas: renglones | + doc_ori | anverso vertical: renglones | + doc_ori |")
        sb.appendLine("|---|---|---|---|---|")
        val a = composedSheets(false); val v = composedSheets(true)
        for (i in a.indices) {
            fun vote(m: Mat): Pair<Int, Int> {
                val g = Cv.gray(m); val l = try { TextOrientation.estimate(g) } finally { g.release() }
                val pr = model?.invoke(m)
                return OrientationVote.decide(l, null, null).rotation to OrientationVote.decide(l, pr, null).rotation
            }
            val (l1, m1) = vote(a[i].second); val (l2, m2) = vote(v[i].second)
            sb.appendLine("| ${a[i].first} | $l1° | ${if (model != null) "$m1°" else "-"} | $l2° | ${if (model != null) "$m2°" else "-"} |")
        }
        (a + v).forEach { it.second.release() }
        sb.appendLine()
        sb.appendLine("## Fallos (después)")
        sb.appendLine()
        for (c in cases.filter { !it.after }) sb.appendLine("- ${c.name}: ${c.why}")
        sb.appendLine()
        sb.appendLine("## doc_ori por cara (p de que TENGA 0° / 180°)")
        sb.appendLine()
        sb.appendLine("```")
        sb.append(probs)
        sb.appendLine("```")
        File(outPath!!).writeText(sb.toString())
        println(sb)
    }
}
