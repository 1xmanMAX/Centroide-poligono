package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Corrección de la lente: modelos (puros), traslado de la calibración de Camera2 a la foto, remap, autocalibrado con el
 * documento, aberración cromática y realce de bordes, con documentos sintéticos de distorsión CONOCIDA (valores típicos de
 * móvil). Las métricas (rectitud en px, franja de color en px, fidelidad de las letras de las esquinas, tiempos) se
 * imprimen como tabla del banco (ver /tmp/claude-0/bench/notes_lente.md).
 */
class LensTest {

    // ------------------------------------------------------------------------------------------------ modelos puros

    @Test fun brown_inversaYDirectaCoinciden() {
        val m = LensMap.Brown(4000, 3000, 3000.0, 3000.0, 2010.0, 1490.0, -0.06, 0.02, -0.003, 0.0004, -0.0003)
        val o = DoubleArray(2); val b = DoubleArray(2)
        for ((x, y) in listOf(0.0 to 0.0, 3999.0 to 2999.0, 2000.0 to 10.0, 150.0 to 2800.0, 2005.0 to 1500.0)) {
            m.undistort(x, y, o); m.source(o[0], o[1], b)
            assertEquals(x, b[0], 1e-3); assertEquals(y, b[1], 1e-3)
        }
        assertTrue(m.maxDisplacement() > 20)
        // Barril corregido: hay que reducir para no perder los bordes
        assertTrue(m.fitScale() < 1.0)
    }

    @Test fun division_inversaCerrada() {
        for (k in listOf(-0.08, -0.02, 0.03, 0.08)) {
            val m = LensMap.Division(1600, 1200, k)
            val o = DoubleArray(2); val b = DoubleArray(2)
            for ((x, y) in listOf(0.0 to 0.0, 1599.0 to 1199.0, 800.0 to 3.0, 30.0 to 700.0)) {
                m.source(x, y, o); m.undistort(o[0], o[1], b)
                assertEquals("k=$k", x, b[0], 1e-6); assertEquals(y, b[1], 1e-6)
            }
        }
    }

    @Test fun calibracionDelSensorALaFoto() {
        val intr = floatArrayOf(3000f, 3000f, 2020f, 1500f, 0f)
        val dist = floatArrayOf(-0.05f, 0.01f, 0f, 0f, 0f)
        val s = SensorLens(4032, 3024, intr, dist)
        // Foto 4:3 a 4000x3000: escala 4000/4032
        val m = s.toImage(4000, 3000) as LensMap.Brown
        assertEquals(2020 * 4000.0 / 4032, m.cx, 0.5)
        assertEquals(3000 * 4000.0 / 4032, m.fx, 0.5)
        // 16:9 a 1920x1080: recorte vertical centrado (3024 -> 2268 filas)
        val m2 = s.toImage(1920, 1080) as LensMap.Brown
        val sc = 1920.0 / 4032
        assertEquals((1500 - (3024 - 2268) / 2.0) * sc, m2.cy, 0.5)
        // Girada (vertical): centro de la imagen, sólo radial
        val m3 = s.toImage(3000, 4000) as LensMap.Brown
        assertEquals(1499.5, m3.cx, 1e-6); assertEquals(0.0, m3.p1, 0.0)
        // Zoom 2x (SCALER_CROP_REGION): la focal en px de la foto se duplica
        val z = s.copy(crop = intArrayOf(1008, 756, 2016, 1512)).toImage(4000, 3000) as LensMap.Brown
        assertEquals(3000 * 4000.0 / 2016, z.fx, 1.0)
        // Centro óptico absurdo -> no se usa
        assertNull(SensorLens(4032, 3024, floatArrayOf(3000f, 3000f, 300f, 1500f, 0f), dist).toImage(4000, 3000))
        // Coeficientes absurdos -> no se usa
        assertNull(SensorLens(4032, 3024, intr, floatArrayOf(5f, 0f, 0f, 0f, 0f)).toImage(4000, 3000))
    }

    @Test fun autocalibrado_puroRecuperaK() {
        val w = 1280; val h = 960
        for (k in listOf(-0.05, 0.04)) {
            val d = LensMap.Division(w, h, k)
            val o = DoubleArray(2)
            val chains = ArrayList<FloatArray>()
            // Rectas reales (corregidas) en varias posiciones -> muestreadas en la foto distorsionada
            for (yy in listOf(60.0, 200.0, 760.0, 900.0)) chains += FloatArray(2 * 100) { i ->
                val x = 40 + (w - 80) * (i / 2) / 99.0
                d.source(x, yy, o); if (i % 2 == 0) o[0].toFloat() else o[1].toFloat()
            }
            for (xx in listOf(60.0, 1220.0)) chains += FloatArray(2 * 80) { i ->
                val y = 40 + (h - 80) * (i / 2) / 79.0
                d.source(xx, y, o); if (i % 2 == 0) o[0].toFloat() else o[1].toFloat()
            }
            val f = LensSelfCal.fit(chains, w, h)
            assertTrue("k=$k: ${f.reason}", f.accepted)
            assertEquals(k, f.k, abs(k) * 0.1)
        }
        // Curvatura NO radial (hoja abarquillada: todas las líneas se arquean hacia abajo): no se acepta
        val curl = ArrayList<FloatArray>()
        for (yy in listOf(60.0, 200.0, 760.0, 900.0)) curl += FloatArray(200) { i ->
            val x = 40 + (w - 80) * (i / 2) / 99.0
            val t = (x - w / 2.0) / (w / 2.0)
            if (i % 2 == 0) x.toFloat() else (yy + 14 * (1 - t * t)).toFloat()
        }
        for (xx in listOf(60.0, 1220.0)) curl += FloatArray(160) { i ->
            val y = 40 + (h - 80) * (i / 2) / 79.0
            if (i % 2 == 0) xx.toFloat() else y.toFloat()
        }
        assertTrue(!LensSelfCal.fit(curl, w, h).accepted)
    }

    @Test fun ajusteRadialDeLaAberracion() {
        val obs = ArrayList<DoubleArray>()
        val s = 0.0012
        for (i in 0 until 30) {
            val vx = (i % 6 - 2.5) * 500; val vy = (i / 6 - 2.0) * 500
            obs += doubleArrayOf(vx, vy, s * vx + (i % 3 - 1) * 0.05, s * vy, 0.5)
        }
        obs += doubleArrayOf(1000.0, 0.0, -4.0, 3.0, 0.5)   // atípico
        val f = LensCorrection.fitRadial(obs)!!
        assertEquals(s, f.first, 1e-4)
    }

    // ------------------------------------------------------------------------------------------------ OpenCV

    private fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    /**
     * Documento sintético [w]x[h] (RGB): fondo oscuro, hoja blanca (6–94 %), reglas ROJAS de tabla (las mide la rectitud)
     * y renglones de texto negro entre ellas (incluidos los de los bordes).
     */
    private fun doc(w: Int, h: Int): Mat {
        val m = Mat(h, w, CvType.CV_8UC3, Scalar(70.0, 64.0, 58.0))
        val x0 = w * 0.06; val y0 = h * 0.06; val x1 = w * 0.94; val y1 = h * 0.94
        Imgproc.rectangle(m, Point(x0, y0), Point(x1, y1), Scalar(238.0, 236.0, 230.0), -1)
        val th = max(2, w / 600)
        val fs = w / 1600.0
        val rows = 9
        for (r in 0..rows) {
            val y = y0 + (y1 - y0) * (0.04 + 0.92 * r / rows)
            Imgproc.line(m, Point(x0 + 20, y), Point(x1 - 20, y), Scalar(200.0, 20.0, 20.0), th, Imgproc.LINE_AA)
            if (r < rows) {
                val step = (y1 - y0) * 0.92 / rows
                var ty = y + step * 0.38
                while (ty < y + step - 10 * fs) {
                    Imgproc.putText(m, "Lente de movil: las letras de los bordes salen curvas y con color", Point(x0 + 40 * fs, ty),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.95 * fs, Scalar(30.0, 30.0, 34.0), max(1, (2 * fs).toInt()), Imgproc.LINE_AA)
                    ty += 34 * fs
                }
            }
        }
        for (c in 0..4) {
            val x = x0 + (x1 - x0) * (0.03 + 0.94 * c / 4)
            Imgproc.line(m, Point(x, y0 + 20), Point(x, y1 - 20), Scalar(200.0, 20.0, 20.0), th, Imgproc.LINE_AA)
        }
        return m
    }

    /** D(xd) = I(f(xd)) con [f] = punto ideal de cada píxel de la foto. */
    private fun warpBy(src: Mat, f: (Double, Double, DoubleArray) -> Unit): Mat {
        val w = src.cols(); val h = src.rows()
        val mx = FloatArray(w * h); val my = FloatArray(w * h)
        val o = DoubleArray(2)
        for (y in 0 until h) for (x in 0 until w) { f(x.toDouble(), y.toDouble(), o); mx[y * w + x] = o[0].toFloat(); my[y * w + x] = o[1].toFloat() }
        val a = Mat(h, w, CvType.CV_32F); a.put(0, 0, mx)
        val b = Mat(h, w, CvType.CV_32F); b.put(0, 0, my)
        val out = Mat()
        Imgproc.remap(src, out, a, b, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        a.release(); b.release()
        return out
    }

    /** Distorsiona [img] con el modelo de Brown [m] (foto = lo que vería la lente). */
    private fun distortBrown(img: Mat, m: LensMap) = warpBy(img) { x, y, o -> m.undistort(x, y, o) }

    /**
     * Rectitud: para cada regla roja (máscara R − G > 60), centroides por columna (horizontales) o fila (verticales) y
     * residuo máximo respecto de su recta. Devuelve (media de los máximos, peor) en px.
     */
    private fun straightness(img: Mat): Pair<Double, Double> {
        val w = img.cols(); val h = img.rows()
        val ch = ArrayList<Mat>(); Core.split(img, ch)
        val d = Mat(); Core.subtract(ch[0], ch[1], d)
        val mask = Mat(); Imgproc.threshold(d, mask, 60.0, 255.0, Imgproc.THRESH_BINARY)
        val bytes = ByteArray(w * h); mask.get(0, 0, bytes)
        ch.forEach { it.release() }; d.release(); mask.release()
        fun on(x: Int, y: Int) = bytes[y * w + x].toInt() != 0
        val res = ArrayList<Double>()
        for (vertical in listOf(false, true)) {
            // Semillas: perfil por la franja central
            val n = if (vertical) w else h
            val prof = IntArray(n)
            val a0 = (if (vertical) h else w) / 2 - 20; val a1 = a0 + 40
            for (i in 0 until n) for (t in a0 until a1) if (if (vertical) on(i, t) else on(t, i)) prof[i]++
            val seeds = ArrayList<Int>()
            var i = 0
            while (i < n) { if (prof[i] > 20) { var j = i; while (j < n && prof[j] > 0) j++; seeds += (i + j) / 2; i = j } else i++ }
            for (sd in seeds) {
                val pts = ArrayList<DoubleArray>()
                for (dir in listOf(1, -1)) {
                    var c = sd.toDouble()
                    var t = (if (vertical) h else w) / 2
                    while (t in 0 until (if (vertical) h else w)) {
                        var s = 0.0; var k = 0
                        for (q in (c - 8).toInt()..(c + 8).toInt()) {
                            if (q < 0 || q >= n) continue
                            if (if (vertical) on(q, t) else on(t, q)) { s += q; k++ }
                        }
                        if (k == 0 || k > 10) { if (k == 0) break }
                        if (k in 1..10) { c = s / k; pts += if (vertical) doubleArrayOf(c, t.toDouble()) else doubleArrayOf(t.toDouble(), c) }
                        t += dir * 6
                    }
                }
                if (pts.size < 30) continue
                // Recta TLS y residuo máximo
                val mx = pts.sumOf { it[0] } / pts.size; val my = pts.sumOf { it[1] } / pts.size
                var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                for (p in pts) { val a = p[0] - mx; val b = p[1] - my; sxx += a * a; syy += b * b; sxy += a * b }
                val th = 0.5 * Math.atan2(2 * sxy, sxx - syy)
                val nx = -Math.sin(th); val ny = Math.cos(th)
                res += pts.maxOf { abs((it[0] - mx) * nx + (it[1] - my) * ny) }
            }
        }
        return (if (res.isEmpty()) 0.0 else res.average()) to (res.maxOrNull() ?: 0.0)
    }

    @Test fun distorsionDeMetadatos_seEndereza() {
        cv()
        val w = 1600; val h = 1200
        val ideal = doc(w, h)
        val bench = StringBuilder()
        for ((k1, k2) in listOf(-0.05 to 0.01, 0.05 to -0.01, -0.02 to 0.0)) {
            val m = LensMap.Brown(w, h, 1250.0, 1250.0, 803.0, 597.0, k1, k2, 0.0, 0.0, 0.0)
            val photo = distortBrown(ideal, m)
            val before = straightness(photo)
            val fixed = LensCorrection.undistort(photo, m.withScale(m.fitScale()))
            val after = straightness(fixed)
            bench.append("metadatos k1=%+.2f k2=%+.2f: rectitud media %.2f -> %.2f px (peor %.2f -> %.2f)\n".format(k1, k2, before.first, after.first, before.second, after.second))
            assertTrue("antes $before", before.second > (if (abs(k1) < 0.03) 1.0 else 2.0))
            assertTrue("después $after", after.second < 1.0)
            photo.release(); fixed.release()
        }
        println(bench)
        ideal.release()
    }

    @Test fun autocalibrado_conElDocumento() {
        cv()
        val w = 1600; val h = 1200
        val ideal = doc(w, h)
        val bench = StringBuilder()
        // Sin distorsión: no se toca
        var fit: LensSelfCal.Fit? = null
        assertNull(LensSelfCal.estimate(ideal) { fit = it })
        bench.append("autocal sin distorsión: k=%.4f %s\n".format(fit!!.k, fit!!.reason))
        for (k in listOf(-0.06, -0.03, 0.03, 0.06)) {
            val d = LensMap.Division(w, h, k)
            val photo = warpBy(ideal) { x, y, o -> d.undistort(x, y, o) }
            val before = straightness(photo)
            var f: LensSelfCal.Fit? = null
            val est = LensSelfCal.estimate(photo) { f = it }
            assertNotNull("k=$k: ${f?.reason}", est)
            val fixed = LensCorrection.undistort(photo, est!!.withScale(est.fitScale()))
            val after = straightness(fixed)
            bench.append("autocal k=%+.2f: estimado %+.4f (%d curvas) rectitud %.2f -> %.2f px (peor %.2f -> %.2f)\n".format(k, est.k, f!!.chains, before.first, after.first, before.second, after.second))
            assertEquals(k, est.k, abs(k) * 0.3)
            assertTrue("después $after vs $before", after.second < before.second * 0.5)
            photo.release(); fixed.release()
        }
        // Hoja abarquillada (curvatura NO radial: todos los renglones se arquean hacia abajo): no se corrige como lente
        val curl = warpBy(ideal) { x, y, o -> val t = (x - w / 2.0) / (w / 2.0); o[0] = x; o[1] = y - 12 * (1 - t * t) }
        var fc: LensSelfCal.Fit? = null
        val ec = LensSelfCal.estimate(curl) { fc = it }
        bench.append("autocal hoja abarquillada: k=%.4f %s\n".format(fc!!.k, fc!!.reason))
        assertNull("abarquillada: ${fc?.reason}", ec)
        println(bench)
        ideal.release(); curl.release()
    }

    /** Aberración: R ampliado y B reducido alrededor del centro (como una lente sin corregir). */
    private fun addChroma(img: Mat, sR: Double, sB: Double): Mat {
        val w = img.cols(); val h = img.rows()
        val ch = ArrayList<Mat>(); Core.split(img, ch)
        for ((i, s) in listOf(0 to sR, 2 to sB)) {
            // Canal visto: C'(x) = C(c + (x − c) / (1 + s))  (ampliado por 1 + s)
            val a = 1.0 / (1 + s)
            val m = Mat(2, 3, CvType.CV_64F); m.put(0, 0, a, 0.0, (w - 1) / 2.0 * (1 - a), 0.0, a, (h - 1) / 2.0 * (1 - a))
            val o = Mat(); Imgproc.warpAffine(ch[i], o, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
            ch[i].release(); ch[i] = o; m.release()
        }
        val out = Mat(); Core.merge(ch, out); ch.forEach { it.release() }
        return out
    }

    /** Franja de color: |desplazamiento| medio de R y B respecto de G en las teselas de las esquinas (px). */
    private fun fringe(img: Mat): Double {
        val est = LensCorrection.chroma(img) ?: return 0.0
        return est.fringe(img.cols(), img.rows())
    }

    @Test fun aberracionCromatica_seMideYSeCorrige() {
        cv()
        val w = 1600; val h = 1200
        val ideal = doc(w, h)
        val sR = 0.0016; val sB = -0.0012   // ~1.6 px y ~1.2 px en la esquina (lente de móvil sin corregir)
        val photo = addChroma(ideal, sR, sB)
        val est = LensCorrection.chroma(photo)!!
        assertEquals(sR, est.sR, 0.0004); assertEquals(sB, est.sB, 0.0004)
        val f0 = est.fringe(w, h)
        assertTrue(LensCorrection.fixChroma(photo, est))
        val f1 = fringe(photo)
        println("aberración cromática: franja en la esquina %.2f -> %.2f px (sR %.5f/%.5f, sB %.5f/%.5f)".format(f0, f1, est.sR, sR, est.sB, sB))
        assertTrue("franja tras corregir $f1", f1 < 0.4)
        // Sin aberración: lo que mide es despreciable (no se corrige)
        val clean = LensCorrection.chroma(ideal)
        assertTrue((clean?.fringe(w, h) ?: 0.0) < 0.3)
        ideal.release(); photo.release()
    }

    /** Desenfoque que crece hacia las esquinas (r² ): mezcla con la versión desenfocada. */
    private fun edgeBlur(img: Mat, sigma: Double): Mat {
        val w = img.cols(); val h = img.rows()
        val b = Mat(); Imgproc.GaussianBlur(img, b, Size(0.0, 0.0), sigma)
        val wt = FloatArray(w * h)
        val r0 = hypot(w / 2.0, h / 2.0)
        for (y in 0 until h) for (x in 0 until w) { val r = hypot(x - w / 2.0, y - h / 2.0) / r0; wt[y * w + x] = (r * r).toFloat().coerceIn(0f, 1f) }
        val wm = Mat(h, w, CvType.CV_32F); wm.put(0, 0, wt)
        val w3 = Mat(); Imgproc.cvtColor(wm, w3, Imgproc.COLOR_GRAY2RGB)
        val a = Mat(); img.convertTo(a, CvType.CV_32FC3); val bb = Mat(); b.convertTo(bb, CvType.CV_32FC3)
        val diff = Mat(); Core.subtract(bb, a, diff); Core.multiply(diff, w3, diff); Core.add(a, diff, a)
        val out = Mat(); a.convertTo(out, CvType.CV_8UC3)
        for (m in listOf(b, wm, w3, a, bb, diff)) m.release()
        return out
    }

    /** Error medio absoluto respecto de la referencia en las esquinas (texto de los bordes). */
    private fun cornerError(img: Mat, ref: Mat): Double {
        val w = img.cols(); val h = img.rows()
        var s = 0.0
        for (r in listOf(Rect((w * 0.07).toInt(), (h * 0.08).toInt(), w / 5, h / 6), Rect((w * 0.73).toInt(), (h * 0.76).toInt(), w / 5, h / 6))) {
            val d = Mat(); Core.absdiff(img.submat(r), ref.submat(r), d)
            s += Core.mean(d).`val`.take(3).average(); d.release()
        }
        return s / 2
    }

    @Test fun realceRadial_mejoraLasEsquinas() {
        cv()
        val w = 1600; val h = 1200
        val ideal = doc(w, h)
        val soft = edgeBlur(ideal, 1.6)
        val e0 = cornerError(soft, ideal)
        val fixed = soft.clone()
        LensCorrection.radialSharpen(fixed, null, w, h)
        val e1 = cornerError(fixed, ideal)
        // El centro no cambia
        val c = Rect(w / 2 - 100, h / 2 - 60, 200, 120)
        val dc = Mat(); Core.absdiff(fixed.submat(c), soft.submat(c), dc)
        val centerChange = Core.mean(dc).`val`[0]
        println("realce radial: error de las letras de las esquinas %.2f -> %.2f (centro cambia %.3f)".format(e0, e1, centerChange))
        assertTrue("$e0 -> $e1", e1 < e0 * 0.92)
        assertTrue(centerChange < 0.05)
        ideal.release(); soft.release(); fixed.release(); dc.release()
    }

    /** Todo junto a 12 MP (4000x3000): tiempos en la JVM + mejora de la cadena completa (LensFix.correct). */
    @Test fun cadenaCompleta_12MP_tiempos() {
        cv()
        val w = 4000; val h = 3000
        val ideal = doc(w, h)
        val m = LensMap.Brown(w, h, 3100.0, 3100.0, 2006.0, 1497.0, -0.045, 0.012, 0.0, 0.0002, -0.0001)
        val photo0 = distortBrown(ideal, m)
        val photo = addChroma(photo0, 0.0010, -0.0008); photo0.release()
        val s0 = straightness(photo)
        val f0 = fringe(photo)
        // Calibración tal como la daría Camera2 (array 4032x3024)
        val sc = 4032.0 / 4000
        val lens = SensorLens(4032, 3024, floatArrayOf((3100 * sc).toFloat(), (3100 * sc).toFloat(), (2006 * sc).toFloat(), (1497 * sc).toFloat(), 0f),
            floatArrayOf(-0.045f, 0.012f, 0f, 0.0002f, -0.0001f))
        val t0 = System.nanoTime()
        LensCorrection.clearCache()
        val r = LensFix.correct(photo, lens)
        val tFirst = (System.nanoTime() - t0) / 1e6
        val out = r.out!!
        val s1 = straightness(out)
        val f1 = fringe(out)
        // Segunda foto con la misma cámara: la rejilla del mapa sale de la caché
        val t1 = System.nanoTime()
        val again = LensCorrection.undistort(photo, LensMap.Brown(w, h, 3100.0, 3100.0, 2006.0, 1497.0, -0.045, 0.012, 0.0, 0.0002, -0.0001).let { it.withScale(it.fitScale()) })
        val tRemap = (System.nanoTime() - t1) / 1e6
        val tRemap2 = System.nanoTime().let { a -> LensCorrection.undistort(photo, LensMap.Brown(w, h, 3100.0, 3100.0, 2006.0, 1497.0, -0.045, 0.012, 0.0, 0.0002, -0.0001).let { it.withScale(it.fitScale()) }).release(); (System.nanoTime() - a) / 1e6 }
        val t2 = System.nanoTime(); val ca = LensCorrection.chroma(photo); val tCa = (System.nanoTime() - t2) / 1e6
        val t3 = System.nanoTime(); LensSelfCal.estimate(photo); val tSelf = (System.nanoTime() - t3) / 1e6
        val sharp = out.clone()
        val t4 = System.nanoTime(); LensCorrection.radialSharpen(sharp, null, w, h); val tSharp = (System.nanoTime() - t4) / 1e6
        println(
            """
            |12 MP (4000x3000), JVM:
            |  cadena completa (verificación + CA + remap): %.0f ms · %s
            |  rectitud media %.2f -> %.2f px (peor %.2f -> %.2f) · franja de color %.2f -> %.2f px
            |  remap solo: %.0f ms (rejilla en caché: %.0f ms) · medir CA: %.0f ms · autocalibrado: %.0f ms · realce radial: %.0f ms
            """.trimMargin().format(tFirst, r.geometry + " | " + r.detail, s0.first, s1.first, s0.second, s1.second, f0, f1, tRemap, tRemap2, tCa, tSelf, tSharp),
        )
        assertTrue(r.geometry!!.startsWith("metadatos"))
        assertTrue("rectitud $s1", s1.second < 1.5)
        assertTrue("franja $f1", f1 < 0.5)
        assertNotNull(ca)
        for (x in listOf(ideal, photo, out, again, sharp)) x.release()
    }

    @Test fun verificacion_rechazaCalibracionDeUnaFotoYaCorregida() {
        cv()
        val w = 1600; val h = 1200
        val ideal = doc(w, h)   // el ISP ya la corrigió: rectas
        val m = LensMap.Brown(w, h, 1250.0, 1250.0, 800.0, 600.0, -0.06, 0.015, 0.0, 0.0, 0.0)
        assertEquals(false, LensFix.verify(ideal, m))
        val photo = distortBrown(ideal, m)
        assertEquals(true, LensFix.verify(photo, m))
        ideal.release(); photo.release()
    }

    /**
     * Tabla del banco: documento 2000x1500 con distorsión de barril/cojín de móvil + aberración cromática + bordes blandos,
     * corregido por cada capa (sin corrección / metadatos / autocalibrado) + CA + realce radial.
     */
    @Test fun banco_tablaAntesDespues() {
        cv()
        val w = 2000; val h = 1500
        val ideal = doc(w, h)
        val rows = StringBuilder("| escenario | rectitud media / peor (px) | franja CA (px) | error letras esquinas | ms |\n|---|---|---|---|---|\n")
        for ((k1, k2) in listOf(-0.045 to 0.012, 0.035 to -0.008)) {
            val m = LensMap.Brown(w, h, 1560.0, 1560.0, 1003.0, 748.0, k1, k2, 0.0, 0.0, 0.0)
            val soft = edgeBlur(ideal, 1.4)
            val dist = distortBrown(soft, m); soft.release()
            val photo = addChroma(dist, 0.0012, -0.0009); dist.release()
            fun row(name: String, img: Mat, ms: Long) {
                val st = straightness(img)
                rows.append("| k1=%+.3f %s | %.2f / %.2f | %.2f | %.2f | %d |\n".format(k1, name, st.first, st.second, fringe(img), cornerError(img, ideal), ms))
            }
            row("sin corrección", photo, 0)
            // Metadatos (escala 1 para comparar con la referencia píxel a píxel)
            run {
                val t = System.nanoTime()
                val img = photo.clone()
                LensCorrection.chroma(img)?.let { LensCorrection.fixChroma(img, it) }
                val o = LensCorrection.undistort(img, m.withScale(1.0)); img.release()
                val ms = (System.nanoTime() - t) / 1_000_000
                row("metadatos + CA", o, ms)
                val t2 = System.nanoTime(); LensCorrection.radialSharpen(o, null, w, h); val ms2 = (System.nanoTime() - t2) / 1_000_000
                row("metadatos + CA + realce", o, ms + ms2)
                o.release()
            }
            // Autocalibrado (teléfono que no informa de su lente)
            run {
                val t = System.nanoTime()
                val img = photo.clone()
                LensCorrection.chroma(img)?.let { LensCorrection.fixChroma(img, it) }
                var f: LensSelfCal.Fit? = null
                val est = LensSelfCal.estimate(img) { f = it }
                val o = if (est != null) LensCorrection.undistort(img, est.withScale(1.0)) else img.clone()
                img.release()
                val ms = (System.nanoTime() - t) / 1_000_000
                row("autocal (k=%+.4f %s) + CA".format(f?.k ?: 0.0, if (est != null) "aplica" else f?.reason ?: "-"), o, ms)
                o.release()
            }
            photo.release()
        }
        println(rows)
        ideal.release()
    }
}
