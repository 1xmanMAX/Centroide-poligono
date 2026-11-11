package com.scannerpromax.imaging

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Locale

/**
 * BANCO de rendimiento de la cadena completa de una página (no forma parte de la batería normal: solo con -PperfBench=DIR).
 * Por cada foto de 12 MP (sintéticas deterministas de [PerfScenes] en DIR/scenes, generadas si faltan, más las *.jpg de
 * DIR/real si existen; nunca en el repo): decodificación → lente → detección → orientación (renglones) → render completo
 * (perspectiva, curvatura, nitidez, filtro, fotos, tablas, limpieza) → codificación JPEG 95 → miniatura. Mide cada etapa
 * ([PerfTrace]) con todos los núcleos y con 1 hilo de OpenCV (simula un equipo de gama baja) y compara la salida con la
 * DORADA (DIR/golden/<escena>_<filtro>.png: se crea si no existe; -PperfGolden=reset la rehace).
 *   ./gradlew testDebugUnitTest --tests '*PipelineBenchTest*' -PperfBench=/tmp/claude-0/bench/perf [-PperfLabel=antes] [-PperfReps=2]
 * Escribe DIR/<label>.md (tabla por etapa) y DIR/<label>.tsv.
 */
class PipelineBenchTest {

    private data class Case(val name: String, val file: File, val filter: FilterType, val quad: DoubleArray?)

    @Test fun banco() {
        val dir = System.getProperty("perf.bench")?.let(::File)
        assumeTrue("sin -PperfBench", dir != null)
        assumeTrue("sin OpenCV de escritorio", SyntheticScenes.openCv)
        dir!!.mkdirs()
        val label = System.getProperty("perf.label") ?: "medida"
        val reps = System.getProperty("perf.reps")?.toIntOrNull() ?: 2
        val resetGolden = System.getProperty("perf.golden") == "reset"
        val only = System.getProperty("perf.only")
        val scenes = File(dir, "scenes").also { it.mkdirs() }
        val cases = ArrayList<Case>()
        fun synth(name: String, bend: Double, photo: Boolean, table: Boolean): File {
            val f = File(scenes, "$name.jpg")
            if (!f.isFile) {
                val page = PerfScenes.page(2480, 3508, photo = photo, table = table)
                val (scene, q) = PerfScenes.photo(page, 3000, 4000, bend)
                val bgr = Mat(); Imgproc.cvtColor(scene, bgr, Imgproc.COLOR_RGB2BGR)
                Imgcodecs.imwrite(f.absolutePath, bgr, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95))
                File(scenes, "$name.quad").writeText(q.joinToString(","))
                listOf(page, scene, bgr).forEach { it.release() }
            }
            return f
        }
        val a = synth("combada_foto_tabla", 60.0, photo = true, table = true)
        val b = synth("plana_texto", 0.0, photo = false, table = false)
        cases += Case("combada_foto_tabla", a, FilterType.NATURAL, null)
        cases += Case("combada_foto_tabla", a, FilterType.BLACK_WHITE, null)
        cases += Case("combada_foto_tabla", a, FilterType.MAGIC, null)
        cases += Case("plana_texto", b, FilterType.NATURAL, null)
        cases += Case("plana_texto", b, FilterType.ORIGINAL, null)
        File(dir, "real").listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg") }?.sortedBy { it.name }?.forEach {
            cases += Case(it.nameWithoutExtension, it, FilterType.NATURAL, null)
        }
        val run = cases.filter { only == null || it.name.contains(only) || it.filter.name.contains(only) }
        val golden = File(dir, "golden").also { it.mkdirs() }
        val outDir = File(dir, "out_$label").also { it.mkdirs() }
        // Configuraciones: OpenCV con N hilos o con 1 (en el teléfono muchas funciones van por la HAL Carotene en un solo
        // hilo, y en gama baja hay pocos núcleos rápidos) x reparto propio ([Par], NaturalColor) apagado (= cadena anterior)
        // o encendido. -PperfCfg=nombre,nombre filtra.
        val n = Runtime.getRuntime().availableProcessors()
        data class Cfg(val name: String, val cv: Int, val par: Int, val nc: Int)
        val all = listOf(
            Cfg("antes-N", n, 1, 0), Cfg("después-N", n, n, n),
            Cfg("antes-cv1", 1, 1, 0), Cfg("después-cv1", 1, n, n),
        )
        val wanted = System.getProperty("perf.cfg")?.split(',')?.map { it.trim() }?.toSet()
        val configs = all.filter { wanted == null || it.name in wanted }
        val tier = DeviceTier(false, 20_000_000, 8)
        Core.getBuildInformation().lineSequence().filter { "Parallel" in it || "IPP" in it || "OpenCL" in it }.forEach { println("[opencv] $it") }
        run {   // ¿paraleliza OpenCV en este equipo? (GaussianBlur 12 MP)
            val m = Mat(4000, 3000, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar(120.0, 130.0, 140.0)); val o = Mat()
            for (t in listOf(1, Runtime.getRuntime().availableProcessors())) {
                Core.setNumThreads(t); Imgproc.GaussianBlur(m, o, org.opencv.core.Size(0.0, 0.0), 3.0)
                val t0 = System.nanoTime(); repeat(3) { Imgproc.GaussianBlur(m, o, org.opencv.core.Size(0.0, 0.0), 3.0) }
                println("[opencv] GaussianBlur 12 MP sigma 3 con $t hilos: ${(System.nanoTime() - t0) / 3_000_000} ms (getNumThreads=${Core.getNumThreads()})")
            }
            m.release(); o.release()
        }
        val rows = ArrayList<Triple<String, String, Map<String, Long>>>()
        val quality = StringBuilder()
        for (cf in configs) {
            val cfg = cf.name
            for (c in run) {
                var best: Map<String, Long>? = null
                for (r in 0 until reps + 1) {   // 1 de calentamiento
                    val proc = PageProcessor(tier)
                    Core.setNumThreads(cf.cv); Par.threads = cf.par; NaturalColor.maxThreads = cf.nc
                    val (times, out) = once(proc, c)
                    if (r == 0) {
                        val key = "${c.name}_${c.filter.name}"
                        val g = File(golden, "$key.png")
                        Imgcodecs.imwrite(File(outDir, "$key.png").absolutePath, out)
                        if (!g.isFile || resetGolden) {
                            Imgcodecs.imwrite(g.absolutePath, out); quality.append("| $key · $cfg | dorada creada |\n")
                        } else {
                            val ref = Imgcodecs.imread(g.absolutePath, Imgcodecs.IMREAD_UNCHANGED)
                            quality.append("| $key · $cfg | ${compare(ref, out)} |\n")
                            ref.release()
                        }
                    }
                    out.release()
                    if (r > 0 && (best == null || (times["total"] ?: 0) < (best["total"] ?: Long.MAX_VALUE))) best = times
                }
                rows += Triple(cfg, "${c.name} · ${c.filter.name}", best!!)
                println("[$label][$cfg] ${c.name} ${c.filter}: ${best}")
            }
        }
        Core.setNumThreads(n); Par.threads = n; NaturalColor.maxThreads = 0
        // tabla: etapas en filas, casos en columnas (por configuración)
        val md = StringBuilder("# Banco de rendimiento — $label (${Runtime.getRuntime().availableProcessors()} núcleos, JVM)\n\n")
        for (cf in configs) {
            val cfg = cf.name
            val rs = rows.filter { it.first == cfg }
            val stages = LinkedHashSet<String>(); rs.forEach { stages.addAll(it.third.keys) }
            md.append("## $cfg\n\n| etapa (ms) | ").append(rs.joinToString(" | ") { it.second }).append(" |\n|---|").append(rs.joinToString("") { "---:|" }).append("\n")
            for (s in stages.sortedBy { if (it == "total") 1 else 0 }) md.append("| $s | ").append(rs.joinToString(" | ") { (it.third[s] ?: 0).toString() }).append(" |\n")
            md.append("\n")
        }
        md.append("## Resumen: total por página (ms)\n\n| caso | ").append(configs.joinToString(" | ") { it.name }).append(" |\n|---|").append(configs.joinToString("") { "---:|" }).append("\n")
        for (key in rows.map { it.second }.distinct()) {
            md.append("| $key | ").append(configs.joinToString(" | ") { cf -> rows.firstOrNull { it.first == cf.name && it.second == key }?.third?.get("total")?.toString() ?: "" }).append(" |\n")
        }
        md.append("\n")
        md.append("## Salida frente a la dorada\n\n| caso | PSNR (dB) · máx. dif. · % píxeles distintos |\n|---|---|\n").append(quality)
        File(dir, "$label.md").writeText(md.toString())
        File(dir, "$label.tsv").writeText(rows.joinToString("\n") { (cfg, k, m) -> "$cfg\t$k\t" + m.entries.joinToString("\t") { "${it.key}=${it.value}" } })
        println(md)
    }

    /** Una pasada completa; devuelve los tiempos y la salida (BGR/gris para guardar). */
    private fun once(proc: PageProcessor, c: Case): Pair<Map<String, Long>, Mat> {
        val s = PerfTrace.begin()
        try {
            val bgr = PerfTrace.stage("decodificación") { Imgcodecs.imread(c.file.absolutePath, Imgcodecs.IMREAD_COLOR) }
            // corrección de lente (en la app: LensStep sobre el archivo, con autocalibrado en documentos)
            val lens = PerfTrace.stage("lente") { LensFix.correct(bgr, null, LensFix.Options()) }
            if (lens.out != null) {
                PerfTrace.stage("lente (recodificar q100)") { val e = MatOfByte(); Imgcodecs.imencode(".jpg", lens.out, e, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 100)); e.release() }
                lens.out.copyTo(bgr); lens.out.release()
            }
            val rgb = Mat(); Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB); bgr.release()
            val det = PerfTrace.stage("detección") { SyntheticScenes.detect(proc.detector, rgb) }
            val quad = det?.takeIf { it.confidence >= 0.35f }?.let { val p = it.quad.points(); Quad(p[0], p[1], p[2], p[3]) }
                ?: c.quad?.let { q -> Quad(Pt(q[0].toFloat(), q[1].toFloat()), Pt(q[2].toFloat(), q[3].toFloat()), Pt(q[4].toFloat(), q[5].toFloat()), Pt(q[6].toFloat(), q[7].toFloat())) }
            val rot = PerfTrace.stage("orientación") { proc.autoOrientationMat(rgb, quad) }
            val rgba = PerfTrace.stage("bitmap→mat") { Mat().also { Imgproc.cvtColor(rgb, it, Imgproc.COLOR_RGB2RGBA) } }
            rgb.release()
            val edits = PageEdits(quad = quad, filter = c.filter, rotation = rot)
            val out = PerfTrace.stage("render") { proc.renderMat(rgba, edits) }
            val save = Mat()
            if (out.channels() == 3) Imgproc.cvtColor(out, save, Imgproc.COLOR_RGB2BGR) else out.copyTo(save)
            out.release()
            PerfTrace.stage("codificación") {
                val e = MatOfByte()
                if (c.filter == FilterType.BLACK_WHITE) Imgcodecs.imencode(".png", save, e) else Imgcodecs.imencode(".jpg", save, e, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95))
                e.release()
            }
            PerfTrace.stage("miniatura") {
                val t = Mat(); Cv.downscale(save, t, 960)
                val e = MatOfByte(); Imgcodecs.imencode(".jpg", t, e, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90)); e.release(); t.release()
            }
            PerfTrace.end(s)
            return PerfTrace.last to save
        } finally {
            PerfTrace.end(s)
        }
    }

    private fun compare(a: Mat, b: Mat): String {
        if (a.size() != b.size() || a.channels() != b.channels()) return "TAMAÑO DISTINTO ${a.size()}x${a.channels()} vs ${b.size()}x${b.channels()}"
        val d = Mat(); Core.absdiff(a, b, d)
        val g = if (d.channels() == 1) d else Mat().also { Imgproc.cvtColor(d, it, Imgproc.COLOR_BGR2GRAY) }
        val mx = Core.minMaxLoc(g).maxVal
        val nz = Core.countNonZero(g) * 100.0 / g.total()
        val psnr = Core.PSNR(a, b)
        d.release(); if (g !== d) g.release()
        return String.format(Locale.ROOT, "%s · %.0f · %.3f %%", if (psnr.isInfinite() || psnr > 360) "idéntica" else "%.2f".format(Locale.ROOT, psnr), mx, nz)
    }
}
