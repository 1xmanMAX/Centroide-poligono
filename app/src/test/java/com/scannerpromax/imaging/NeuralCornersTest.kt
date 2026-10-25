package com.scannerpromax.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * «Encuadre IA» (DocAligner lcnet100): postproceso con mapas sintéticos, preproceso frente a OpenCV, reglas de fusión con
 * cuadriláteros falsos y paridad con la referencia Python del banco (github_alt, `res/da_lcnet100_pad15.jsonl`) si el
 * modelo y las imágenes están en /tmp (no se copian al repo; sin ellos ese test se salta).
 */
class NeuralCornersTest {

    @Before fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    /** Mapa 4×128×128 con una gaussiana por canal en [centers] (px del mapa) de altura [peaks]. */
    private fun heatmap(centers: List<Pair<Double, Double>>, peaks: FloatArray, sigma: Double = 2.0): FloatArray {
        val n = NeuralCorners.HM
        val hm = FloatArray(4 * n * n)
        for (c in 0 until 4) {
            val (cx, cy) = centers[c]
            for (y in 0 until n) for (x in 0 until n) {
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                hm[c * n * n + y * n + x] = (peaks[c] * exp(-d2 / (2 * sigma * sigma))).toFloat()
            }
        }
        return hm
    }

    @Test fun postprocessRecuperaLosCentrosDeLasGaussianas() {
        val w = 1000; val h = 750
        val pad = NeuralCorners.padFor(w, h)
        assertEquals(NeuralCorners.Pad(150, 112), pad)   // round(112.5) = 112 (Python: mitades al par)
        val pw = w + 2.0 * pad.x; val ph = h + 2.0 * pad.y
        // Esquinas deseadas en px de la imagen → centro en px del mapa (centro de píxel: (u + 0.5) / 128 * pw)
        val want = doubleArrayOf(100.0, 80.0, 900.0, 60.0, 940.0, 700.0, 60.0, 690.0)
        val centers = (0 until 4).map { c -> ((want[c * 2] + pad.x) / pw * 128 - 0.5) to ((want[c * 2 + 1] + pad.y) / ph * 128 - 0.5) }
        val q = NeuralCorners.postprocess(heatmap(centers, floatArrayOf(0.9f, 0.8f, 0.95f, 0.7f)), w, h, pad)
        for (i in 0 until 8) assertEquals("coord $i", want[i], q.pts[i].toDouble(), 3.0)
        assertEquals(0.7f, q.score, 0.01f)   // mínimo de los picos
    }

    @Test fun postprocessIgnoraComponentesPequenasYUsaElMaximoSiNoHay() {
        val w = 512; val h = 512
        val pad = NeuralCorners.padFor(w, h)
        val centers = listOf(30.0 to 30.0, 98.0 to 30.0, 98.0 to 98.0, 30.0 to 98.0)
        val hm = heatmap(centers, floatArrayOf(0.9f, 0.9f, 0.9f, 0.9f))
        // Canal 0: una mancha estrecha más alta en otro sitio (componente pequeña) → gana la componente más GRANDE
        val n = NeuralCorners.HM
        hm[0 * n * n + 100 * n + 10] = 0.99f
        // Canal 3: mapa bajo, sin nada por encima del umbral → máximo
        for (i in 0 until n * n) hm[3 * n * n + i] *= 0.2f
        val q = NeuralCorners.postprocess(hm, w, h, pad)
        val pw = w + 2.0 * pad.x
        fun toImg(u: Double) = (u + 0.5) / 128 * pw - pad.x
        assertEquals(toImg(30.0), q.pts[0].toDouble(), 3.0)
        assertEquals(toImg(30.0), q.pts[1 * 2 + 1].toDouble(), 3.0)
        assertEquals(toImg(30.0), q.pts[3 * 2].toDouble(), 4.0)
        assertEquals(toImg(98.0), q.pts[3 * 2 + 1].toDouble(), 4.0)
        assertEquals(0.18f, q.score, 0.01f)
    }

    @Test fun preprocesoIgualQueCopyMakeBorderMasResizeBilineal() {
        val w = 731; val h = 1013
        val bgr = Mat(h, w, CvType.CV_8UC3)
        Core.randu(bgr, 0.0, 255.0)
        Imgproc.GaussianBlur(bgr, bgr, Size(5.0, 5.0), 0.0)
        val pad = NeuralCorners.padFor(w, h)
        val x = NeuralCorners.preprocess(NeuralCorners.Pixels.of(bgr, bgr = true), pad)
        val padded = Mat(); Core.copyMakeBorder(bgr, padded, pad.y, pad.y, pad.x, pad.x, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
        val ref = Mat(); Imgproc.resize(padded, ref, Size(256.0, 256.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val buf = ByteArray(256 * 256 * 3); ref.get(0, 0, buf)
        var maxD = 0
        for (y in 0 until 256) for (xx in 0 until 256) for (c in 0 until 3) {
            val r = buf[(y * 256 + xx) * 3 + c].toInt() and 0xFF
            val k = Math.round(x[c * 256 * 256 + y * 256 + xx] * 255f)
            maxD = max(maxD, abs(r - k))
        }
        assertTrue("diferencia máxima $maxD niveles", maxD <= 1)
        bgr.release(); padded.release(); ref.release()
    }

    // ------------------------------------------------------------------ reglas de fusión

    private val fw = 2000; private val fh = 1500; private val sw = 500; private val sh = 375

    /** Rectángulo en px de la imagen de trabajo (500×375). */
    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
    /** Lo mismo escalado a la imagen completa (×4) como cuadrilátero de la red. */
    private fun nn(q: FloatArray, s: Float) = NeuralQuad(FloatArray(8) { q[it] * 4f }, s)
    private fun decide(e: FloatArray?, conf: Double?, q: NeuralQuad) = DocumentDetector.arbitrate(e, conf, q, sw, sh, fw, fh)

    @Test fun reglasDeFusion() {
        val doc = rect(100f, 60f, 400f, 320f)
        val otro = rect(20f, 20f, 200f, 150f)
        // Sin clásico usable: red con s ≥ T1 y área razonable → red; s baja → clásico (nada)
        assertEquals(NeuralDecision.NEURAL, decide(null, null, nn(doc, 0.55f)))
        assertEquals(NeuralDecision.CLASSIC, decide(null, null, nn(doc, 0.45f)))
        assertEquals(NeuralDecision.NEURAL, decide(otro, 0.30, nn(doc, 0.55f)))
        // La red dice «toda la foto» (> AMAX) → se deja como está
        assertEquals(NeuralDecision.CLASSIC, decide(null, null, nn(rect(2f, 2f, 498f, 373f), 0.95f)))
        // Coinciden (IoU ≥ 0,85) → clásico, aunque la red esté muy segura
        assertEquals(NeuralDecision.CLASSIC, decide(rect(104f, 62f, 398f, 318f), 0.8, nn(doc, 0.99f)))
        // Discrepan: s ≥ T2 → red; s entre TV y T2 → clásico
        assertEquals(NeuralDecision.NEURAL, decide(otro, 0.9, nn(doc, 0.75f)))
        assertEquals(NeuralDecision.CLASSIC, decide(otro, 0.9, nn(doc, 0.6f)))
        // Veto: red muy insegura, sin solape y clásico flojo → sin cuadrilátero; con clásico seguro no hay veto
        assertEquals(NeuralDecision.NONE, decide(otro, 0.5, nn(rect(300f, 200f, 480f, 360f), 0.1f)))
        assertEquals(NeuralDecision.CLASSIC, decide(otro, 0.7, nn(rect(300f, 200f, 480f, 360f), 0.1f)))
        // Cuadrilátero de la red no convexo (esquinas cruzadas) → no se usa
        val cruzado = floatArrayOf(100f, 60f, 400f, 320f, 400f, 60f, 100f, 320f)
        assertEquals(NeuralDecision.CLASSIC, decide(null, null, nn(cruzado, 0.9f)))
        assertEquals(NeuralDecision.CLASSIC, decide(otro, 0.9, nn(cruzado, 0.9f)))
    }

    @Test fun sinRedElDetectorDaLoMismo() {
        val det = DocumentDetector(DeviceTier(false, 20_000_000, 8))
        val scene = SyntheticScenes.wood(900, 1200)
        val page = SyntheticScenes.textPage(600, 800)
        SyntheticScenes.paste(scene, page, 4.0, 450.0, 600.0)
        val a = SyntheticScenes.detect(det, scene)
        // Red disponible pero devolviendo null (ORT sin descargar): idéntico
        val gray = Mat(); Imgproc.cvtColor(scene, gray, Imgproc.COLOR_RGB2GRAY)
        val sm = Mat(); Cv.downscale(scene, sm, 500)
        val b = det.detectScaled(sm, scene.cols(), scene.rows(), neural = { null }) { x0, y0, rw, rh -> gray.submat(y0, y0 + rh, x0, x0 + rw).clone() }
        // Red que coincide con el clásico: también idéntico (se queda el clásico)
        val c = det.detectScaled(sm, scene.cols(), scene.rows(), neural = {
            NeuralQuad(FloatArray(8) { i -> SyntheticScenes.quadArray(a!!)[i].toFloat() }, 0.99f)
        }) { x0, y0, rw, rh -> gray.submat(y0, y0 + rh, x0, x0 + rw).clone() }
        assertNotNull(a); assertNotNull(b); assertNotNull(c)
        assertArrayEquals(SyntheticScenes.quadArray(a!!), SyntheticScenes.quadArray(b!!), 1e-6)
        assertEquals(a.confidence, b.confidence, 0f)
        assertArrayEquals(SyntheticScenes.quadArray(a), SyntheticScenes.quadArray(c!!), 1e-6)
        gray.release(); sm.release(); scene.release(); page.release()
    }

    @Test fun desactivadoOSinOrtDevuelveNull() {
        var asked = false
        val n = NeuralCorners({ asked = true; ByteArray(0) }, { false })
        val px = NeuralCorners.Pixels.of(Mat(10, 10, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)))
        assertNull(n.corners(px))
        assertTrue("no debe leer el modelo sin ORT", !asked)
        n.enabled = false
        assertNull(NeuralCorners({ ByteArray(0) }, { true }).also { it.enabled = false }.corners(px))
    }

    // ------------------------------------------------------------------ paridad con Python (banco)

    @Test fun paridadConLaReferenciaPython() {
        val model = File(System.getProperty("docaligner.model") ?: "/tmp/claude-0/bench/github_alt/models/docaligner/lcnet100_h_e_bifpn_256_fp32.onnx")
        val ref = File("/tmp/claude-0/bench/github_alt/res/da_lcnet100_pad15.jsonl")
        val manifest = File("/tmp/claude-0/bench/manifest_v3.csv")
        assumeTrue("modelo/referencias del banco no disponibles", model.isFile && ref.isFile && manifest.isFile)
        val paths = HashMap<String, String>()
        manifest.readLines().let { lines ->
            val head = lines.first().split(","); val iId = head.indexOf("id"); val iPath = head.indexOf("path")
            for (l in lines.drop(1)) { val f = l.split(","); if (f.size > max(iId, iPath)) paths[f[iId]] = f[iPath] }
        }
        val reId = Regex("\"id\": \"([^\"]+)\""); val reQ = Regex("\"q\": \\[([^\\]]+)\\]"); val reS = Regex("\"score\": ([-0-9.eE]+)")
        val rows = ref.readLines().mapNotNull { l ->
            val id = reId.find(l)?.groupValues?.get(1) ?: return@mapNotNull null
            val q = reQ.find(l)?.groupValues?.get(1)?.split(",")?.map { it.trim().toFloat() } ?: return@mapNotNull null
            val s = reS.find(l)?.groupValues?.get(1)?.toFloat() ?: return@mapNotNull null
            val p = paths[id]?.takeIf { File(it).isFile } ?: return@mapNotNull null
            Triple(p, q, s)
        }.filterIndexed { i, _ -> i % 97 == 0 }.take(10)
        assumeTrue("sin imágenes del banco", rows.isNotEmpty())
        val n = NeuralCorners({ model.readBytes() }, { true }, threads = 2)
        var worst = 0.0; var worstS = 0f
        for ((p, q, s) in rows) {
            val bgr = Imgcodecs.imread(p, Imgcodecs.IMREAD_COLOR)
            val r = n.corners(NeuralCorners.Pixels.of(bgr, bgr = true))!!
            val diag = hypot(bgr.cols().toDouble(), bgr.rows().toDouble())
            for (k in 0 until 4) worst = max(worst, hypot((r.pts[k * 2] - q[k * 2]).toDouble(), (r.pts[k * 2 + 1] - q[k * 2 + 1]).toDouble()) / diag)
            worstS = max(worstS, abs(r.score - s))
            bgr.release()
        }
        n.close()
        println("paridad DocAligner: ${rows.size} imágenes, error máx. de esquina ${"%.4f".format(worst * 100)} % diag, |Δs| máx. ${"%.4f".format(worstS)}")
        assertTrue("esquinas: ${worst * 100} % diag", worst < 0.005)
        assertTrue("puntuación: $worstS", worstS < 0.02f)
    }
}
