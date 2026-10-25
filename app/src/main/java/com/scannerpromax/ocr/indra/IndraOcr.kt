package com.scannerpromax.ocr.indra

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Indra OCR (datalab-to/indra, v1.1.2 / pesos v1.1.0) en Kotlin con ONNX Runtime: puerto de indra_ocr/pipeline.py para
 * el reconocedor latino (rec.onnx, incluye manuscrito latino) con detector de líneas (det.onnx), orientación
 * (orient.onnx), orden de lectura + bloques de maquetación (order.onnx) y modelo de lenguaje n-grama (lm.npz) para las
 * líneas dudosas. Sin los reconocedores CJK/RTL/world, sin matemáticas y sin markdown (la app usa líneas, palabras
 * y bloques con cajas en píxeles).
 *
 * Banco (notes_indra.md, 54 imágenes): CER 27,7 / %palabras 76,2 frente a 53,8 / 46,5 de Tesseract (proxy de ML Kit)
 * y 28,8 / 80,3 de PaddleOCR-VL Q8, en ~0,4 s por página en el PC (Paddle ~110 s).
 *
 * No es seguro entre hilos (las sesiones ORT sí, pero el caché del LM no): un reconocimiento a la vez.
 */
class IndraOcr(dir: File, threads: Int = 4, private val orientMin: Float = ORIENT_MIN) : Closeable {

    /** Ficha CTC: clase, x en la página y probabilidad de la clase elegida en su paso (confianza por carácter). */
    class Token(val id: Int, val x: Float, val p: Float = 1f)
    /** Palabra con su confianza (media de las probabilidades de sus caracteres). */
    class Word(val text: String, val box: FloatArray, val conf: Float = 1f, val charP: FloatArray? = null)
    /**
     * Línea. [doubtful]: confianza bajo [MIN_CONF] (antes se vaciaba): su texto se conserva si no es basura evidente
     * ([IndraDoubt.keep]) para mostrarlo como DUDOSO.
     */
    class Line(val box: FloatArray, val text: String, val conf: Float, val words: List<Word>, val doubtful: Boolean = false)
    class Block(val label: String, val box: FloatArray, val lines: List<Int>)

    /**
     * Resultado: [lines] en orden de lectura, [blocks] con índices a [lines]. Cajas en px de la página GIRADA
     * [rotation] grados (antihorario; la página que entró se giró en sentido horario para enderezarla), ya sin la
     * pequeña inclinación [skew] (grados antihorario que la enderezaron). [width]×[height] = página girada.
     */
    class Page(
        val lines: List<Line>, val blocks: List<Block>, val rotation: Int, val skew: Double,
        val width: Int, val height: Int, val timings: Map<String, Long>,
    ) {
        val text: String get() = lines.joinToString("\n") { it.text }
    }

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Sin arena: con lotes de forma cambiante crecía sin parar (pipeline.py, ORT 1.31).
        setCPUArenaAllocator(false)
    }
    private val det: OrtSession = env.createSession(File(dir, "det.onnx").path, opts)
    private val rec: OrtSession = env.createSession(File(dir, "rec.onnx").path, opts)
    private val order: OrtSession? = File(dir, "order.onnx").takeIf { it.isFile }?.let { env.createSession(it.path, opts) }
    private val orient: OrtSession? = File(dir, "orient.onnx").takeIf { it.isFile }?.let { env.createSession(it.path, opts) }
    val vocab: IndraVocab
    private val longSide: Int
    private val height: Int
    private val window: Int
    private val lm: IndraLm?
    private var lmAlpha = LM_ALPHA
    private var lmBeta = LM_BETA
    private var lmGate = LM_GATE

    init {
        val meta = Json.parseToJsonElement(File(dir, "meta.json").readText()) as JsonObject
        vocab = IndraVocab.fromJson(meta.getValue("vocab").jsonPrimitive.content)
        longSide = meta.getValue("det_long_side").jsonPrimitive.int
        height = meta.getValue("rec_height").jsonPrimitive.int
        window = meta["rec_window"]?.jsonPrimitive?.int ?: 1
        val lmFile = File(dir, "lm.npz")
        lm = if (lmFile.isFile && rec.outputNames.containsAll(OUT_LM)) IndraLm.load(lmFile) else null
        (meta["lm_params"] as? JsonObject)?.get("base")?.jsonObject?.let { p ->
            p["alpha"]?.jsonPrimitive?.double?.let { lmAlpha = it }
            p["beta"]?.jsonPrimitive?.double?.let { lmBeta = it }
            p["gate"]?.jsonPrimitive?.double?.let { lmGate = it }
        }
    }

    val hasLm: Boolean get() = lm != null

    /** Sesgo de palabras en la búsqueda en haz (vocabulario del usuario, léxico): null = el LM de Indra tal cual. */
    @Volatile var bias: IndraLm.WordBias? = null

    /**
     * Confianza mínima para conservar una línea como dudosa (bajo [MIN_CONF]); por encima de 1 = comportamiento
     * original (líneas dudosas vacías).
     */
    @Volatile var doubtFloor: Float = DOUBT_FLOOR

    /** Modelo de lenguaje (null sin lm.npz): lo usa el corrector para desempatar. */
    val languageModel: IndraLm? get() = lm

    // ------------------------------------------------------------------ página completa

    /**
     * OCR de una página en gris. [turn] = false: se sabe que está derecha (no se usa orient.onnx).
     * [quarterTurns] fuerza el giro (cuartos de vuelta en sentido antihorario que TIENE el contenido; p. ej. de la
     * orientación de la app) en vez del modelo.
     */
    fun run(gray: Gray, turn: Boolean = true, quarterTurns: Int? = null): Page {
        val t = LinkedHashMap<String, Long>()
        var t0 = System.nanoTime()
        val small = IndraImage.preview(gray)
        var a = IndraImage.estimateSkew(small)
        var k = quarterTurns?.let { ((it % 4) + 4) % 4 } ?: if (turn) orientation(if (abs(a) >= IndraImage.DESKEW_MIN) IndraImage.rotatePage(small, a) else small) else 0
        // _turn: medio giro conserva los renglones; tras un cuarto de vuelta se mide otra vez el ángulo.
        var g = gray
        if (k != 0) g = IndraImage.rot90(g, -k)
        if (k == 1 || k == 3) a = IndraImage.estimateSkew(IndraImage.rot90(small, -k))
        val skew = if (abs(a) >= IndraImage.DESKEW_MIN) a else 0.0
        if (skew != 0.0) g = IndraImage.rotatePage(g, skew)
        t["orient_ms"] = ms(t0)
        t0 = System.nanoTime()
        val boxes = detect(g)
        t["det_ms"] = ms(t0)
        t0 = System.nanoTime()
        val recs = recognize(g, boxes)
        t["rec_ms"] = ms(t0)
        t0 = System.nanoTime()
        var page = assemble(g.w, g.h, boxes, recs)
        t["order_ms"] = ms(t0)
        if (skew != 0.0) {
            fun un(b: FloatArray) = IndraImage.unrotateBox(b, skew, g.w, g.h)
            page = Page(
                page.lines.map { l -> Line(un(l.box), l.text, l.conf, l.words.map { Word(it.text, un(it.box), it.conf, it.charP) }, l.doubtful) },
                page.blocks.map { Block(it.label, un(it.box), it.lines) },
                0, 0.0, g.w, g.h, emptyMap(),
            )
        }
        t["total_ms"] = t.values.sum()
        return Page(page.lines, page.blocks, 90 * k, skew, g.w, g.h, t)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** Probabilidades de orient.onnx (0, 90, 180, 270 antihorario, vertical) o null sin el modelo. */
    fun orientationProbs(g: Gray): FloatArray? {
        val s = orient ?: return null
        val x = IndraImage.orientInput(g)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 1, IndraImage.ORIENT_SIDE.toLong(), IndraImage.ORIENT_SIDE.toLong())).use { tx ->
            s.run(mapOf("x" to tx)).use { r -> return floats(r.get(0) as OnnxTensor) }
        }
    }

    /** Cuartos de vuelta (antihorario) del contenido; 0 si ninguno supera [orientMin] o es una página vertical. */
    fun orientation(g: Gray): Int {
        val p = orientationProbs(g) ?: return 0
        var k = 0
        for (i in p.indices) if (p[i] > p[k]) k = i
        return if (p[k] > orientMin && k < 4) k else 0
    }

    // ------------------------------------------------------------------ detección (det_post.decode)

    private fun detectAt(g: Gray, ls: Int): Pair<List<FloatArray>, Double> {
        val x = IndraImage.pageToInput(g, ls)
        val out: FloatArray
        val oh: Int; val ow: Int
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x.data), longArrayOf(1, 1, x.h.toLong(), x.w.toLong())).use { tx ->
            det.run(mapOf("x" to tx)).use { r ->
                val ot = r.get(0) as OnnxTensor
                val shape = ot.info.shape
                oh = shape[2].toInt(); ow = shape[3].toInt()
                out = floats(ot)
            }
        }
        return decode(out, ow, oh) to x.scale
    }

    /** Cajas de línea [x0, y0, x1, y1] en px de la entrada del detector. */
    private fun decode(out: FloatArray, w: Int, h: Int, thresh: Float = 0.5f, minArea: Int = 2): List<FloatArray> {
        val plane = w * h
        val mask = ByteArray(plane)
        val prob = FloatArray(plane)
        for (i in 0 until plane) { val p = 1f / (1f + exp(-out[i])); prob[i] = p; if (p > thresh) mask[i] = 1 }
        val m = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, mask) }
        val lab = Mat(); val stats = Mat(); val cent = Mat()
        val n = Imgproc.connectedComponentsWithStats(m, lab, stats, cent, 4, CvType.CV_32S)
        try {
            if (n <= 1) return emptyList()
            val labels = IntArray(plane).also { lab.get(0, 0, it) }
            val st = IntArray(n * 5).also { stats.get(0, 0, it) }
            val cnt = DoubleArray(n); val y0 = DoubleArray(n); val y1 = DoubleArray(n)
            for (i in 0 until plane) {
                val l = labels[i]
                if (l <= 0) continue
                val ys = ((i / w) + 0.5) * OUT_STRIDE
                cnt[l] += 1.0
                y0[l] += ys - out[plane + i]
                y1[l] += ys + out[2 * plane + i]
            }
            val res = ArrayList<FloatArray>()
            for (l in 1 until n) {
                if (st[l * 5 + Imgproc.CC_STAT_AREA] < minArea) continue
                val c = max(cnt[l], 1.0)
                val top = y0[l] / c; val bot = y1[l] / c
                val lh = max(1.0, bot - top)
                val ex = min(CORE_X_SHRINK * lh, 0.5 * lh)
                val left = st[l * 5 + Imgproc.CC_STAT_LEFT]; val width = st[l * 5 + Imgproc.CC_STAT_WIDTH]
                res += floatArrayOf((left * OUT_STRIDE - ex).toFloat(), top.toFloat(), ((left + width) * OUT_STRIDE + ex).toFloat(), bot.toFloat())
            }
            return res
        } finally {
            m.release(); lab.release(); stats.release(); cent.release()
        }
    }

    /** Líneas en px de la página (detect: segunda pasada a más resolución si la letra es diminuta). */
    fun detect(g: Gray): List<FloatArray> {
        var (boxes, s) = detectAt(g, longSide)
        if (boxes.size >= 8) {
            val medH = IndraOrder.median(FloatArray(boxes.size) { boxes[it][3] - boxes[it][1] })
            if (medH < ADAPTIVE_MIN_H) {
                val ls = (min(ADAPTIVE_MAX_SIDE.toDouble(), longSide * ADAPTIVE_TARGET_H / max(medH.toDouble(), 1.0)).toInt()) / 32 * 32
                if (ls > longSide) { val r = detectAt(g, ls); boxes = r.first; s = r.second }
            }
        }
        return boxes.map { b ->
            floatArrayOf(
                (b[0] / s).toFloat().coerceIn(0f, g.w.toFloat()), (b[1] / s).toFloat().coerceIn(0f, g.h.toFloat()),
                (b[2] / s).toFloat().coerceIn(0f, g.w.toFloat()), (b[3] / s).toFloat().coerceIn(0f, g.h.toFloat()),
            )
        }
    }

    // ------------------------------------------------------------------ reconocimiento CTC

    /**
     * Lectura de una línea. Bajo [MIN_CONF] [text]/[tokens] quedan vacíos (como el original: no cambian el corte de
     * líneas ni el orden de lectura) y la lectura va en [doubtText]/[doubtTokens].
     */
    class Rec(val text: String, val conf: Float, val tokens: List<Token>, val doubtText: String = "", val doubtTokens: List<Token> = emptyList())

    fun recognize(g: Gray, boxes: List<FloatArray>, batch: Int = 64): List<Rec> {
        val n = boxes.size
        val out = MutableList(n) { Rec("", 0f, emptyList()) }
        if (n == 0) return out
        val dark = if (window > 1) BooleanArray(n) { IndraImage.isDarkLine(g, boxes[it]) } else null
        val crops = List(n) { IndraImage.cropLine(g, boxes[it], height, 1, dark?.get(it)) }
        val tall = if (window > 1) List(n) { IndraImage.cropLine(g, boxes[it], height, window, dark?.get(it)) } else crops
        val want = if (lm != null) OUT_GREEDY + OUT_LM else OUT_GREEDY
        for (idx in IndraImage.widthBatches(IntArray(n) { crops[it].w }, batch)) {
            val b = idx.size
            val hh = tall[idx[0]].h
            val wm = (ceil(idx.maxOf { tall[it].w } / IndraImage.WIDTH_MULTIPLE.toDouble()) * IndraImage.WIDTH_MULTIPLE).toInt()
            val arr = FloatArray(b * hh * wm)
            val widths = LongArray(b)
            for ((k, j) in idx.withIndex()) {
                val c = tall[j]
                for (y in 0 until hh) System.arraycopy(c.data, y * c.w, arr, (k * hh + y) * wm, c.w)
                widths[k] = c.w.toLong()
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), longArrayOf(b.toLong(), 1, hh.toLong(), wm.toLong())).use { tx ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(widths), longArrayOf(b.toLong())).use { tw ->
                    rec.run(mapOf("x" to tx, "widths" to tw), want.toSet()).use { r ->
                        val idsT = r.get("ids").get() as OnnxTensor
                        val tSteps = idsT.info.shape[1].toInt()
                        val ids = longs(idsT)
                        val pmax = floats(r.get("conf").get() as OnnxTensor)
                        val lengths = longs(r.get("lengths").get() as OnnxTensor)
                        var blank: FloatArray? = null; var topv: FloatArray? = null; var topi: LongArray? = null; var kTop = 0
                        if (lm != null) {
                            blank = floats(r.get("blank").get() as OnnxTensor)
                            val tv = r.get("topv").get() as OnnxTensor
                            kTop = tv.info.shape[2].toInt()
                            topv = floats(tv)
                            topi = longs(r.get("topi").get() as OnnxTensor)
                        }
                        val stride = wm.toDouble() / max(tSteps, 1)
                        for ((k, j) in idx.withIndex()) {
                            val len = lengths[k].toInt()
                            val keepT = ArrayList<Int>()
                            var confSum = 0.0
                            var prev = -1L
                            for (s in 0 until len) {
                                val v = ids[k * tSteps + s]
                                if (v != 0L && (s == 0 || v != prev)) { keepT += s; confSum += pmax[k * tSteps + s] }
                                prev = v
                            }
                            val conf = if (keepT.isNotEmpty()) (confSum / keepT.size).toFloat() else 0f
                            var tokIds = IntArray(keepT.size) { ids[k * tSteps + keepT[it]].toInt() }
                            var steps = keepT.toIntArray()
                            val floor = min(MIN_CONF, doubtFloor)
                            var probs = FloatArray(keepT.size) { pmax[k * tSteps + keepT[it]] }
                            if (lm != null && conf >= floor && conf < lmGate) {
                                val bl = FloatArray(len) { blank!![k * tSteps + it] }
                                val tv = FloatArray(len * kTop) { topv!![(k * tSteps) * kTop + it] }
                                val ti = LongArray(len * kTop) { topi!![(k * tSteps) * kTop + it] }
                                val (bi, bs) = lm.beamSearch(bl, tv, ti, kTop, len, lmAlpha, lmBeta, LM_BEAM, candFloor = LM_CAND_FLOOR, bias = bias?.takeIf { it.active }, vocab = vocab)
                                tokIds = bi; steps = bs
                                // Probabilidad de la clase elegida en su paso (entre las kTop mejores; si no está, casi nula).
                                probs = FloatArray(bi.size) { q ->
                                    val s0 = bs[q]
                                    var pr = 0.01f
                                    for (jj in 0 until kTop) if (ti[s0 * kTop + jj].toInt() == bi[q]) { pr = exp(tv[s0 * kTop + jj]); break }
                                    pr
                                }
                            }
                            val bx = boxes[j]
                            val h = max(1.0, (bx[3] - bx[1]).toDouble())
                            val ix0 = floor(bx[0] - IndraImage.PAD_X * h)
                            val cw = ceil(bx[2] + IndraImage.PAD_X * h) - ix0
                            val f = cw / crops[j].w
                            val toks = List(tokIds.size) { Token(tokIds[it], (ix0 + (steps[it] + 0.5) * stride * f).toFloat(), probs[it]) }
                            out[j] = when {
                                conf >= MIN_CONF -> Rec(vocab.decode(tokIds), conf, toks)
                                conf >= floor -> Rec("", conf, emptyList(), vocab.decode(tokIds), toks)
                                else -> Rec("", conf, emptyList())
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ ensamblado (assemble)

    private fun isSpace(tk: Token) = tk.id == vocab.spaceId

    private fun decodeToks(toks: List<Token>): String = vocab.decode(IntArray(toks.size) { toks[it].id })

    private fun gutterCuts(boxes: List<FloatArray>, recs: List<Rec>): List<List<Float>> = boxes.indices.map { i ->
        val b = boxes[i]
        val h = max(1f, b[3] - b[1])
        val xs = recs[i].tokens.filterNot { isSpace(it) }.map { it.x }
        val mine = ArrayList<Float>()
        for (q in 0 until xs.size - 1) {
            val a = xs[q]; val c = xs[q + 1]
            if (c - a <= GUTTER_GAP * h) continue
            val gl = a + 0.35f * h; val gr = c - 0.35f * h
            var near = 0
            for (o in boxes.indices) if (o != i && (abs(boxes[o][2] - gl) < 0.5f * h || abs(boxes[o][0] - gr) < 0.5f * h)) near++
            if (near >= GUTTER_EDGES) mine += (a + c) / 2
        }
        mine
    }

    private class Seg(val box: FloatArray, val text: String, val tokens: List<Token>)

    private fun splitLine(box: FloatArray, toks: List<Token>, gap: Float, at: List<Float>): List<Seg> {
        val h = max(1f, box[3] - box[1])
        val segs = arrayListOf(ArrayList<Token>())
        var prev: Float? = null
        val cuts = at.sorted()
        for (tk in toks) {
            if (!isSpace(tk)) {
                val p = prev
                if (p != null && (tk.x - p > gap * h || cuts.any { p < it && it < tk.x })) segs.add(ArrayList())
                prev = tk.x
            }
            segs.last() += tk
        }
        if (segs.size == 1) return listOf(Seg(box.copyOf(), decodeToks(toks), toks))
        val out = ArrayList<Seg>()
        for (sg in segs) {
            val text = decodeToks(sg).trim()
            val xs = sg.filterNot { isSpace(it) }.map { it.x }
            if (text.isNotEmpty() && xs.isNotEmpty()) {
                out += Seg(floatArrayOf(max(box[0], xs.min() - 0.35f * h), box[1], min(box[2], xs.max() + 0.35f * h), box[3]), text, sg)
            }
        }
        return out
    }

    private class LineRec(var box: FloatArray, var text: String, val conf: Float, var tokens: List<Token>, val src: Int = -1)

    /** Líneas sin texto propio (rellenos de puntos/rayas) fuera; viñetas sueltas unidas a su elemento (tidy_lines). */
    private fun tidyLines(lines: MutableList<LineRec>): List<LineRec> {
        val keep = BooleanArray(lines.size) { true }
        for ((i, l) in lines.withIndex()) {
            val core = l.text.replace(" ", "")
            if (core.isNotEmpty() && core.all { it in LEADER_CHARS }) {
                val w = l.box[2] - l.box[0]; val h = l.box[3] - l.box[1]
                if (core.length >= 3 || w >= 3 * h || core.none { it in DASHES }) keep[i] = false
            }
        }
        // Alturas de antes de unir (como tidy_lines: h se calcula una vez).
        val hs = FloatArray(lines.size) { lines[it].box[3] - lines[it].box[1] }
        for (i in lines.indices) {
            val t = lines[i].text.trim()
            if (!keep[i] || !((t.length == 1 && t[0] in BULLETS) || ENUMERATOR.matches(t))) continue
            val bi = lines[i].box
            val hi = hs[i]
            var best = -1; var bestGap = 0f
            for (j in lines.indices) {
                if (j == i || !keep[j] || lines[j].text.isBlank()) continue
                val bj = lines[j].box
                val hj = hs[j]
                val ov = min(bi[3], bj[3]) - max(bi[1], bj[1])
                if (ov < 0.5f * min(hi, hj)) continue
                val gap = bj[0] - bi[2]
                if (gap >= -0.3f * hi && gap <= MARKER_GAP * max(hi, hj) && (best < 0 || gap < bestGap)) { best = j; bestGap = gap }
            }
            if (best < 0) continue
            val lj = lines[best]
            val sp = vocab.spaceId
            if (lines[i].tokens.isNotEmpty() && lj.tokens.isNotEmpty() && sp != null) {
                val x = (bi[2] + lj.box[0]) / 2
                lj.tokens = lines[i].tokens + Token(sp, x) + lj.tokens
            }
            lj.text = "$t ${lj.text.trim()}"
            lj.box = floatArrayOf(min(bi[0], lj.box[0]), min(bi[1], lj.box[1]), max(bi[2], lj.box[2]), max(bi[3], lj.box[3]))
            keep[i] = false
        }
        return lines.filterIndexed { i, _ -> keep[i] }
    }

    /** Palabras de una línea: tramos entre espacios con caja desde las posiciones de los picos CTC. */
    private fun words(box: FloatArray, toks: List<Token>): List<Word> {
        val h = max(1f, box[3] - box[1])
        val out = ArrayList<Word>()
        var cur = ArrayList<Token>()
        fun flush() {
            if (cur.isEmpty()) return
            val text = decodeToks(cur).trim()
            if (text.isNotEmpty()) {
                val x0 = max(box[0], cur.minOf { it.x } - 0.35f * h)
                val x1 = min(box[2], cur.maxOf { it.x } + 0.35f * h)
                var ps = 0f
                for (t in cur) ps += t.p
                val cp = if (text.length == cur.size) FloatArray(cur.size) { cur[it].p } else null
                out += Word(text, floatArrayOf(x0, box[1], max(x0 + 1f, x1), box[3]), ps / cur.size, cp)
            }
            cur = ArrayList()
        }
        for (tk in toks) if (isSpace(tk)) flush() else cur += tk
        flush()
        return out
    }

    fun assemble(w: Int, h: Int, boxes: List<FloatArray>, recs: List<Rec>): Page {
        val cuts = if (boxes.isNotEmpty()) gutterCuts(boxes, recs) else emptyList()
        val split = ArrayList<LineRec>()
        for (i in boxes.indices) for (s in splitLine(boxes[i], recs[i].tokens, SPLIT_GAP, cuts[i])) split += LineRec(s.box, s.text, recs[i].conf, s.tokens, i)
        val lines = tidyLines(split)
        val bx = lines.map { it.box }
        val texts = lines.map { it.text }
        var cls: Array<FloatArray>? = null
        var same: Array<FloatArray>? = null
        val ord: IntArray = if (lines.size < 2 || order == null) {
            IndraOrder.orderXyCut(bx).toIntArray()
        } else {
            val n = lines.size
            val feats = IndraOrder.lineFeatures(bx, w.toFloat(), h.toFloat(), texts)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), longArrayOf(1, n.toLong(), IndraOrder.N_FEATS.toLong())).use { tf ->
                OnnxTensor.createTensor(env, arrayOf(BooleanArray(n) { true })).use { tm ->
                    val names = order.outputNames
                    val want = listOf("a", "cls", "same").filter { it in names }.toSet()
                    order.run(mapOf("feats" to tf, "mask" to tm), want).use { r ->
                        val a = square(floats(r.get("a").get() as OnnxTensor), n)
                        if (want.size >= 3) {
                            val c = floats(r.get("cls").get() as OnnxTensor)
                            val nc = c.size / n
                            cls = Array(n) { i -> FloatArray(nc) { c[i * nc + it] } }
                            same = square(floats(r.get("same").get() as OnnxTensor), n)
                            IndraOrder.decodeBlocksOrder(a, same!!, bx, cls)
                        } else {
                            IndraOrder.decodeOrder(Array(n) { i -> DoubleArray(n) { j -> a[i][j].toDouble() } })
                        }
                    }
                }
            }
        }
        val pos = IntArray(lines.size).also { p -> ord.forEachIndexed { k, i -> p[i] = k } }
        val medH = if (lines.isEmpty()) 1f else IndraOrder.median(FloatArray(lines.size) { lines[it].box[3] - lines[it].box[1] })
        val outLines = ord.map { i ->
            val l = lines[i]
            val r = recs.getOrNull(l.src)
            if (l.text.isBlank() && r != null && r.doubtTokens.isNotEmpty()) {
                // Línea dudosa: su lectura, salvo basura evidente (rayas, casillas, sueltas de 1 carácter).
                val t = r.doubtText.trim()
                val bh = l.box[3] - l.box[1]
                val keep = IndraDoubt.keep(t, l.conf, bh / max(1f, medH), (l.box[2] - l.box[0]) / max(1f, bh), doubtFloor)
                if (keep) Line(l.box.map { round1(it) }.toFloatArray(), t, l.conf, words(l.box, r.doubtTokens), doubtful = true)
                else Line(l.box.map { round1(it) }.toFloatArray(), "", l.conf, emptyList())
            } else {
                Line(l.box.map { round1(it) }.toFloatArray(), l.text.trim(), l.conf, words(l.box, l.tokens))
            }
        }
        val blocks = if (cls != null && same != null) {
            IndraOrder.pageBlocks(bx, ord, cls!!, same!!).map { b -> Block(b.label, b.box, b.lines.map { pos[it] }) }
        } else if (lines.isNotEmpty()) {
            // Sin cabezas de maquetación: bloques por XY-cut.
            IndraOrder.xyCutBlocks(bx).map { blk ->
                val bb = floatArrayOf(blk.minOf { bx[it][0] }, blk.minOf { bx[it][1] }, blk.maxOf { bx[it][2] }, blk.maxOf { bx[it][3] })
                Block("Text", bb, blk.map { pos[it] }.sorted())
            }
        } else emptyList()
        return Page(outLines, blocks, 0, 0.0, w, h, emptyMap())
    }

    private fun round1(v: Float) = (Math.round(v * 10.0) / 10.0).toFloat()

    private fun square(a: FloatArray, n: Int): Array<FloatArray> {
        val m = a.size / n // la última dimensión puede ser N2 = N
        return Array(n) { i -> FloatArray(n) { j -> a[i * m + j] } }
    }

    private fun floats(t: OnnxTensor): FloatArray { val b = t.floatBuffer; return FloatArray(b.remaining()).also { b.get(it) } }
    private fun longs(t: OnnxTensor): LongArray { val b = t.longBuffer; return LongArray(b.remaining()).also { b.get(it) } }

    override fun close() {
        det.close(); rec.close(); order?.close(); orient?.close(); opts.close()
    }

    companion object {
        /** Archivos que necesita (latín): el resto del paquete de Indra (CJK, RTL, world, matemáticas) no se usa. */
        val FILES = listOf("det.onnx", "rec.onnx", "order.onnx", "orient.onnx", "meta.json", "lm.npz")

        const val ADAPTIVE_MIN_H = 9.0f
        const val ADAPTIVE_TARGET_H = 12.0
        const val ADAPTIVE_MAX_SIDE = 2048
        const val SPLIT_GAP = 3.0f
        const val GUTTER_GAP = 1.6f
        const val GUTTER_EDGES = 3
        const val ORIENT_MIN = 0.95f
        const val MIN_CONF = 0.5f
        /**
         * Líneas entre esta confianza y [MIN_CONF] se conservan como dudosas (si [IndraDoubt.keep]). Banco: bajo 0,3
         * casi todo es basura (casillas, rayas, "1"/"I" sueltos); entre 0,3 y 0,5 con el filtro, %palabras igual y
         * precisión de palabras −0,8 pp (las dudosas van marcadas y fuera del texto plano).
         */
        const val DOUBT_FLOOR = 0.3f
        const val LM_GATE = 0.9
        const val LM_ALPHA = 0.5
        const val LM_BETA = 1.5
        const val LM_BEAM = 8
        const val LM_CAND_FLOOR = 3e-3
        const val OUT_STRIDE = 2
        const val CORE_X_SHRINK = 0.15
        const val MARKER_GAP = 2.5f
        val OUT_GREEDY = listOf("ids", "conf", "lengths")
        val OUT_LM = listOf("blank", "topv", "topi")
        private const val LEADER_CHARS = ".·…‥_-–—="
        private const val DASHES = "-–—"
        private const val BULLETS = "•◦▪▫●○■□‣⁃∙*"
        private val ENUMERATOR = Regex("(\\d{1,3}|[A-Za-z])[.)]|\\((\\d{1,3}|[A-Za-z]|[ivxlcIVXLC]{1,5})\\)")
    }
}
