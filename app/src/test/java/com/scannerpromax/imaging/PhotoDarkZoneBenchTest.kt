package com.scannerpromax.imaging

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * BANCO de fotos frente a zonas oscuras ([PhotoBoxRefine] dentro del render real de [PrintedPage], «Color natural»).
 * Sólo corre con `-PfotosBench=salida.md` (fuera del repo):
 *   ./gradlew testDebugUnitTest --tests '*PhotoDarkZoneBenchTest*' -PfotosBench=/tmp/claude-0/bench/fotos_oscuras.md \
 *       -PfotosNat=/tmp/claude-0/bench/datasets/fotos/nat [-PfotosReal=/tmp/claude-0/bench/datasets/ori/photos_rect]
 * Positivos: fotos naturales (o sintéticas) pegadas en una hoja con texto (a veces con una zona oscura además).
 * Negativos: 10 tipos de zona oscura ([PhotoScenes.Neg]) × N semillas. Dos modos: sólo detección local y con una «pista»
 * de la maquetación (PP-DocLayoutV3 / Jev) justo sobre la zona oscura o la foto (el caso peor: la IA dice «imagen»).
 * Mide: falsos positivos (zona oscura tratada como foto), aciertos de las fotos (IoU ≥ 0,7), IoU medio.
 */
class PhotoDarkZoneBenchTest {

    private fun iou(a: Rect, b: Rect): Double {
        val iw = min(a.x + a.width, b.x + b.width) - max(a.x, b.x); val ih = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        if (iw <= 0 || ih <= 0) return 0.0
        val i = iw.toDouble() * ih
        return i / (a.area() + b.area() - i)
    }

    private fun frac(r: Rect, w: Int, h: Int) = floatArrayOf(r.x.toFloat() / w, r.y.toFloat() / h, (r.x + r.width).toFloat() / w, (r.y + r.height).toFloat() / h)

    /** Todas las cajas ajustadas del último [detect] (px de la entrada) con sus medidas. */
    private var lastAll: List<Pair<Rect, PhotoBoxRefine.Found>> = emptyList()
    private val tsv = StringBuilder("conjunto\tnombre\tetiqueta\tdecision\tfoto\tpapel\tgrano\tplano\tcolores\tmedio\ttop2\tmidT\tlow\thue\tcroma\thv\ttinta\tmf\tosc\n")

    private fun row(set: String, name: String, label: Int, f: PhotoBoxRefine.Found) {
        val m = f.m; val n = f.nat ?: return
        tsv.append("$set\t$name\t$label\t${if (f.photo) 1 else 0}\t%.3f\t%.3f\t%.3f\t%.3f\t${m.colors}\t%.3f\t%.3f\t%.3f\t%.2f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\n"
            .format(m.photo, m.paper, m.grain, m.flat, m.mid, m.top2, n.midT, n.lowStd, n.hueEnt, n.chroma, n.hv, n.ink, n.mf, n.dark))
    }

    /** Fotografías (px de la entrada) del render «Color natural» de [m] con las pistas [hints]; [log] = medidas de cada caja. */
    private fun detect(m: Mat, hints: List<Rect>, log: StringBuilder?): List<Rect> {
        var found: List<PhotoBoxRefine.Found> = emptyList(); var ow = m.cols(); var oh = m.rows()
        val prev = PrintedPage.onPhotoBoxes
        PrintedPage.onPhotoBoxes = { _, f, w, h -> found = f; ow = w; oh = h }
        try {
            PrintedPage.render(m, TextRegions.Style.NATURAL, fast = false, maxPixels = 2_000_000, layoutImages = hints.map { frac(it, m.cols(), m.rows()) }).release()
        } finally { PrintedPage.onPhotoBoxes = prev }
        val sx = m.cols().toDouble() / ow; val sy = m.rows().toDouble() / oh
        log?.let { l -> for (f in found) l.append("    ").append(if (f.photo) "FOTO " else "no   ").append(f.toString()).append('\n') }
        lastAll = found.map { Rect((it.rect.x * sx).toInt(), (it.rect.y * sy).toInt(), (it.rect.width * sx).toInt(), (it.rect.height * sy).toInt()) to it }
        return lastAll.filter { it.second.photo }.map { it.first }
    }

    private class Tally { var n = 0; var fp = 0; var tp = 0; var extra = 0; var iouSum = 0.0 }

    @Test fun banco() {
        val out = System.getProperty("fotos.bench")
        assumeTrue("sin -PfotosBench", out != null)
        assumeTrue(SyntheticScenes.openCv)
        val seeds = (System.getProperty("fotos.seeds") ?: "6").toInt()
        val nat = System.getProperty("fotos.nat")?.let { d -> File(d).listFiles()?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.sortedBy { it.name } }.orEmpty()
        val detail = StringBuilder()
        val rows = LinkedHashMap<String, Tally>()
        fun t(k: String) = rows.getOrPut(k) { Tally() }
        val only = System.getProperty("fotos.only") ?: ""
        for (hint in if (only.isEmpty()) listOf(false, true) else listOf(false)) {
            val mode = if (hint) "pista" else "local"
            // negativos
            for (neg in PhotoScenes.Neg.entries) for (s in 0 until seeds) {
                val sc = PhotoScenes.negativeScene(neg, 1000 + s * 17 + neg.ordinal)
                detail.append("[$mode] ${sc.name} oscura=${sc.dark}\n")
                val ph = detect(sc.img, if (hint && sc.dark != null) listOf(sc.dark) else emptyList(), detail)
                if (!hint) for ((_, f) in lastAll) row("neg_${neg.name}", sc.name, 0, f)
                val k = t("$mode|neg|${neg.label}"); k.n++; if (ph.isNotEmpty()) k.fp++
                val all = t("$mode|neg|TODOS"); all.n++; if (ph.isNotEmpty()) all.fp++
                sc.img.release()
            }
            // positivos (fotos naturales; si no hay carpeta, sintéticas)
            val srcs: List<File?> = if (only == "neg") emptyList() else nat.ifEmpty { List(seeds * 3) { null } }
            for ((i, f) in srcs.withIndex()) {
                val ph = f?.let { file -> Imgcodecs.imread(file.absolutePath).also { if (!it.empty()) Imgproc.cvtColor(it, it, Imgproc.COLOR_BGR2RGB) } }?.takeIf { !it.empty() }
                if (f != null && ph == null) continue
                // la mitad, con una zona oscura en la misma página (sombra o cabecera)
                val neg = when (i % 4) { 1 -> PhotoScenes.Neg.SHADOW; 3 -> PhotoScenes.Neg.HEADER; else -> null }
                val sc = PhotoScenes.positive(ph, 5000 + i, neg)
                ph?.release()
                detail.append("[$mode] ${sc.name} ${f?.name ?: "sintetica"} foto=${sc.photos} oscura=${sc.dark}\n")
                val found = detect(sc.img, if (hint) sc.photos + listOfNotNull(sc.dark) else emptyList(), detail)
                if (!hint) for ((r, fd) in lastAll) row("pos", sc.name + "_" + (f?.name ?: "s"), if (iou(r, sc.photos[0]) >= 0.5) 1 else 0, fd)
                val k = t("$mode|pos|" + (if (neg == null) "foto" else "foto + ${neg.label}")); val all = t("$mode|pos|TODAS")
                for (kk in listOf(k, all)) {
                    kk.n++
                    val best = found.maxOfOrNull { iou(it, sc.photos[0]) } ?: 0.0
                    if (best >= 0.7) { kk.tp++; kk.iouSum += best }
                    kk.extra += found.count { iou(it, sc.photos[0]) < 0.3 }
                    if (sc.dark != null && found.any { b -> iou(b, sc.photos[0]) < 0.3 && PhotoMath.overlaps(b, sc.dark) }) kk.fp++
                }
                sc.img.release()
            }
        }
        // páginas REALES (capturas rectificadas, casi todas sin fotos): páginas con alguna «foto»
        val real = if (only == "neg") emptyList() else System.getProperty("fotos.real")?.let { d -> File(d).listFiles()?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.sortedBy { it.name } }.orEmpty()
        val realLines = StringBuilder()
        var realWith = 0
        // etiquetas: <nombre>\t<x> <y> <w> <h> de cada FOTOGRAFÍA de verdad (px del archivo); lo demás marcado = falso
        val labels = System.getProperty("fotos.realLabels")?.let { File(it) }?.takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { l -> val p = l.split('\t'); if (p.size < 2) null else p[0] to p[1].trim().split(' ').map { it.toInt() }.let { Rect(it[0], it[1], it[2], it[3]) } }
            .groupBy({ it.first }, { it.second })
        var realFp = 0; var realFpPages = 0; var realTp = 0; val realTruth = labels.values.sumOf { it.size }
        val crops = File(File(out).parentFile, File(out).nameWithoutExtension + "_reales").also { if (real.isNotEmpty()) it.mkdirs() }
        for (f in real) {
            val m = Imgcodecs.imread(f.absolutePath); if (m.empty()) continue
            Imgproc.cvtColor(m, m, Imgproc.COLOR_BGR2RGB)
            // a ~1400 px de lado largo (como el render de la app tras la reducción de trabajo)
            val s = 1400.0 / max(m.cols(), m.rows())
            if (s < 1) Imgproc.resize(m, m, org.opencv.core.Size(m.cols() * s, m.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
            detail.append("[real] ${f.name}\n")
            val ph = detect(m, emptyList(), detail)
            val truth = labels[f.name].orEmpty()
            fun isTrue(r: Rect) = truth.any { t -> iou(r, t) >= 0.3 || t.contains(org.opencv.core.Point(r.x + r.width / 2.0, r.y + r.height / 2.0)) }
            for ((r, fd) in lastAll) row("real", f.name, if (isTrue(r)) 1 else 0, fd)
            val fpHere = ph.count { !isTrue(it) }
            realFp += fpHere; if (fpHere > 0) realFpPages++
            realTp += truth.count { t -> ph.any { r -> iou(r, t) >= 0.3 || t.contains(org.opencv.core.Point(r.x + r.width / 2.0, r.y + r.height / 2.0)) } }
            if (ph.isNotEmpty()) {
                realWith++
                realLines.append("- ${f.name}: ${ph.joinToString { "${it.x},${it.y} ${it.width}x${it.height}" }}\n")
                val dbg = m.clone()
                for (r in ph) Imgproc.rectangle(dbg, r.tl(), r.br(), org.opencv.core.Scalar(255.0, 0.0, 0.0), 4)
                Imgproc.cvtColor(dbg, dbg, Imgproc.COLOR_RGB2BGR)
                Imgcodecs.imwrite(File(crops, f.nameWithoutExtension + ".jpg").absolutePath, dbg); dbg.release()
            }
            m.release()
        }
        val md = StringBuilder("# Banco fotos / zonas oscuras\n\nSemillas por tipo: $seeds; fotos naturales: ${nat.size}; reales: ${real.size}\n\n")
        md.append("| modo | conjunto | caso | n | falsos (zona oscura como foto) | fotos acertadas | IoU medio | cajas extra |\n|---|---|---|---|---|---|---|---|\n")
        for ((k, v) in rows) {
            val (mode, set, case) = k.split("|")
            val fp = "%d (%.1f %%)".format(v.fp, 100.0 * v.fp / max(1, v.n))
            val rec = if (set == "pos") "%d/%d (%.1f %%)".format(v.tp, v.n, 100.0 * v.tp / max(1, v.n)) else "-"
            val io = if (set == "pos" && v.tp > 0) "%.3f".format(v.iouSum / v.tp) else "-"
            md.append("| $mode | $set | $case | ${v.n} | $fp | $rec | $io | ${if (set == "pos") v.extra.toString() else "-"} |\n")
        }
        if (real.isNotEmpty()) md.append("\nReales con alguna «foto»: $realWith / ${real.size}; cajas FALSAS: $realFp (en $realFpPages páginas); fotos de verdad acertadas: $realTp / $realTruth\n\n").append(realLines)
        File(File(out).parentFile, File(out).nameWithoutExtension + "_cajas.tsv").writeText(tsv.toString())
        File(out).writeText(md.toString())
        File(File(out).parentFile, File(out).nameWithoutExtension + "_detalle.txt").writeText(detail.toString())
        println(md)
    }
}
