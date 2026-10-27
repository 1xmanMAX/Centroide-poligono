package com.scannerpromax.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Líneas de tabla reconstruidas ([TableLines], [TableLinesMath]): seguimiento subpíxel de una línea rota, decisión
 * conservadora (tablas sí; subrayados, renglones de texto, rayas discontinuas impresas y rejillas de gráficos no) y
 * dibujo continuo que sólo oscurece.
 */
class TableLinesTest {

    // ------------------------------------------------------------------------------------- utilidades de banda

    /** Banda [n] x [across] de papel 230 con una línea horizontal de grosor [t] centrada en [y] (anti-aliasing). */
    private fun band(n: Int, across: Int, y: (Int) -> Double, t: Double, dark: Double, ink: (Int) -> Boolean): Array<IntArray> =
        Array(n) { i ->
            IntArray(across) { j ->
                if (!ink(i)) 230 else {
                    val cov = (min(j + 0.5, y(i) + t / 2) - max(j - 0.5, y(i) - t / 2)).coerceIn(0.0, 1.0)
                    (230 - dark * cov).roundToInt()
                }
            }
        }

    private fun prm(maxThick: Double = 6.0) = TableLinesMath.Params(search = 4, paperExtra = 8, noiseAbs = 6.0, maxThick = maxThick, gapMax = 60)

    @Test fun seguimiento_lineaRotaIrregular_seExtiendeYCentroSubpixel() {
        val y0 = 20.3
        // raya tenue rota de forma irregular (como la que la cámara deja), con un poco de inclinación
        val gaps = listOf(30..37, 80..95, 150..152, 210..240, 300..306)
        val b = band(400, 41, { y0 + it * 0.004 }, 2.0, 60.0) { i -> i in 10..390 && gaps.none { i in it } }
        val at = TableLinesMath.Band { i, j -> b[i][j] }
        val seed = TableLinesMath.Poly.line(y0 + 0.5, 0.0, 200.0)
        val tr = TableLinesMath.track(at, 41, 400, seed, 120, 180, prm())
        assertNotNull(tr); tr!!
        assertTrue("extremos ${tr.a0}..${tr.a1}", tr.a0 in 8..14 && tr.a1 in 386..392)
        for (i in listOf(50, 200, 350)) assertEquals("centro en $i", y0 + i * 0.004, tr.centerAt(i.toDouble()), 0.25)
        assertEquals(2.0, tr.thick, 0.7)
        assertTrue("tinta ${tr.inkFrac}", tr.inkFrac in 0.8..0.95)
        assertFalse(tr.dashed)
        assertTrue(tr.irregular)
        assertTrue("rms ${tr.rms}", tr.rms < 0.3)
    }

    @Test fun rayaDiscontinuaImpresa_regularYLimpia_seReconoce() {
        val b = band(400, 41, { 20.0 }, 2.0, 150.0) { i -> i in 10..390 && (i - 10) % 18 < 10 }
        val tr = TableLinesMath.track(TableLinesMath.Band { i, j -> b[i][j] }, 41, 400, TableLinesMath.Poly.line(20.0, 0.0, 200.0), 100, 300, prm())
        assertNotNull(tr)
        assertTrue("discontinua", tr!!.dashed)
    }

    @Test fun trazosRegulares_puntosGuia_noSonIrregulares() {
        assertFalse(TableLinesMath.irregularRuns(List(12) { 4 }, List(12) { 6 }))
        assertTrue(TableLinesMath.irregularRuns(listOf(40, 12, 90, 7, 30, 55), listOf(3, 15, 2, 9, 30, 4)))
    }

    // ------------------------------------------------------------------------------------------- decisión

    private fun cand(hor: Boolean, a0: Double, a1: Double, c: Double, ink: Double = 0.95, rms: Double = 0.2, run: Int = -1,
                     out: Double = 0.9, dashed: Boolean = false, touch: Double = 0.0, irregular: Boolean = true,
                     cross: Double = 0.0, crossRuns: Int = 0): TableLinesMath.Cand {
        val n = (a1 - a0).toInt() + 1
        val tr = TableLinesMath.Track(a0.toInt(), a1.toInt(), DoubleArray(n) { c }, 2.0, 60.0, ink, 5, dashed, rms, 0.0, touch,
            if (run < 0) n else run, irregular, null, cross, crossRuns)
        return TableLinesMath.Cand(hor, a0, a1, c, tr, out)
    }

    @Test fun tabla_filasYColumnas_seAceptan() {
        val c = ArrayList<TableLinesMath.Cand>()
        for (k in 0..4) c.add(cand(true, 100.0, 900.0, 300.0 + 60 * k))
        for (x in listOf(100.0, 400.0, 900.0)) c.add(cand(false, 300.0, 540.0, x))
        val ok = TableLinesMath.accept(c, 1000, 1400, 20.0, 6.0)
        assertTrue(ok.all { it })
    }

    @Test fun subrayadoCorto_renglonDeTexto_yRayaDiscontinua_seRechazan() {
        val c = ArrayList<TableLinesMath.Cand>()
        for (k in 0..3) c.add(cand(true, 100.0, 900.0, 300.0 + 60 * k))
        c.add(cand(true, 300.0, 380.0, 700.0))                         // palabra subrayada (corta)
        c.add(cand(true, 100.0, 900.0, 520.0, rms = 1.6))              // renglón de texto (centro que salta)
        c.add(cand(true, 100.0, 900.0, 560.0, ink = 0.55, dashed = true))   // raya discontinua impresa
        c.add(cand(true, 100.0, 900.0, 600.0, ink = 0.5, run = 8, irregular = false))   // puntos guía
        val ok = TableLinesMath.accept(c, 1000, 1400, 20.0, 6.0)
        for (k in 0..3) assertTrue("fila $k", ok[k])
        for (k in 4..7) assertFalse("negativo $k", ok[k])
    }

    @Test fun rejillaDeGrafico_cruzadaPorBarras_seDejaComoEsta() {
        val c = ArrayList<TableLinesMath.Cand>()
        for (k in 0..4) c.add(cand(true, 100.0, 900.0, 300.0 + 60 * k, cross = 0.3, crossRuns = 4))
        val ok = TableLinesMath.accept(c, 1000, 1400, 20.0, 6.0)
        assertTrue(ok.none { it })
    }

    @Test fun lineaInvisibleEnLaSalida_soloDentroDeUnaTabla() {
        val c = ArrayList<TableLinesMath.Cand>()
        for (k in 0..3) c.add(cand(true, 100.0, 900.0, 300.0 + 60 * k, out = 0.0))   // tenues, la salida no las muestra
        c.add(cand(true, 100.0, 900.0, 1200.0, out = 0.0))                           // sola: no
        val ok = TableLinesMath.accept(c, 1000, 1400, 20.0, 6.0, inkRef = 200.0)
        for (k in 0..3) assertTrue("fila $k", ok[k])
        assertFalse(ok[4])
    }

    // ----------------------------------------------------------------------------------------------- dibujo

    @Test fun dibujo_continuo_soloOscurece_yRespetaHuecos() {
        val bw = 200; val bh = 20
        val buf = ByteArray(bw * bh) { 255.toByte() }
        buf[10 * bw + 50] = 0   // trazo de texto encima de la línea: sigue negro
        TableLinesMath.drawBand(buf, bw, bh, 1, true, 5.0, 190.0, { 10.25 }, 2.0, intArrayOf(60)) { it in 120..129 }
        fun px(x: Int, y: Int) = buf[y * bw + x].toInt() and 0xFF
        for (x in 5..119) assertTrue("x=$x", px(x, 10) < 120)
        for (x in 120..129) assertEquals(255, px(x, 10))
        assertEquals(0, px(50, 10))
        assertEquals(255, px(100, 5)); assertEquals(255, px(100, 15))
        assertEquals(255, px(2, 10)); assertEquals(255, px(195, 10))
    }

    // ------------------------------------------------------------------------------- integración (OpenCV)

    /** Página con texto, una tabla de líneas grises finas y una palabra subrayada; salida = B/N con las líneas rotas. */
    private fun scene(): Pair<Mat, Mat> {
        val w = 1400; val h = 1800
        val src = Mat(h, w, CvType.CV_8UC3, Scalar(232.0, 230.0, 226.0))
        val out = Mat(h, w, CvType.CV_8UC1, Scalar(255.0))
        var y = 140
        for (k in 0 until 6) {
            Imgproc.putText(src, "Informe de corte de la obra numero $k del periodo", Point(120.0, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(30.0, 30.0, 30.0), 2)
            Imgproc.putText(out, "Informe de corte de la obra numero $k del periodo", Point(120.0, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0), 2)
            y += 46
        }
        // subrayado corto bajo una palabra
        Imgproc.line(src, Point(120.0, 150.0), Point(260.0, 150.0), Scalar(30.0, 30.0, 30.0), 2)
        Imgproc.line(out, Point(120.0, 150.0), Point(260.0, 150.0), Scalar(0.0), 2)
        // tabla: 8 filas x 4 columnas, líneas grises de 2 px; en la salida sólo quedan trozos
        val x0 = 100; val x1 = 1300; val ty = 600; val rh = 90
        val rnd = java.util.Random(7)
        for (r in 0..8) {
            val yy = ty + r * rh
            Imgproc.line(src, Point(x0.toDouble(), yy.toDouble()), Point(x1.toDouble(), yy.toDouble()), Scalar(150.0, 150.0, 150.0), 2)
            var x = x0
            while (x < x1) { val len = 10 + rnd.nextInt(40); if (rnd.nextBoolean()) Imgproc.line(out, Point(x.toDouble(), yy.toDouble()), Point(min(x1, x + len).toDouble(), yy.toDouble()), Scalar(90.0), 2); x += len }
            if (r < 8) for (c in 0 until 4) Imgproc.putText(src, "dato $r$c", Point(x0 + 30.0 + c * 300, yy + 55.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, Scalar(30.0, 30.0, 30.0), 2)
        }
        for (c in 0..4) {
            val xx = x0 + c * 300
            Imgproc.line(src, Point(xx.toDouble(), ty.toDouble()), Point(xx.toDouble(), (ty + 8 * rh).toDouble()), Scalar(150.0, 150.0, 150.0), 2)
            var yy = ty
            while (yy < ty + 8 * rh) { val len = 10 + rnd.nextInt(40); if (rnd.nextBoolean()) Imgproc.line(out, Point(xx.toDouble(), yy.toDouble()), Point(xx.toDouble(), min(ty + 8 * rh, yy + len).toDouble()), Scalar(90.0), 2); yy += len }
        }
        Imgproc.GaussianBlur(src, src, org.opencv.core.Size(0.0, 0.0), 1.2)
        return src to out
    }

    @Test fun pagina_tablaRotaQuedaContinua_sinTocarElSubrayado() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        val (src, out) = scene()
        val drawn = ArrayList<DoubleArray>()
        TableLines.onDraw = { drawn.addAll(it) }
        try {
            val n = TableLines.restore(out, src, 26.0)
            assertTrue("dibujadas $n", n >= 12)
        } finally { TableLines.onDraw = null }
        val o = ByteArray(out.total().toInt()); out.get(0, 0, o)
        fun ink(x: Int, y: Int) = (y - 2..y + 2).any { (o[it * out.cols() + x].toInt() and 0xFF) < 128 }
        // filas: cubiertas casi por completo
        for (r in 0..8) {
            val yy = 600 + r * 90
            val cov = (110..1290).count { ink(it, yy) } / 1181.0
            assertTrue("fila $r cobertura $cov", cov > 0.97)
        }
        // el subrayado corto no se redibuja (ningún segmento dibujado en y≈150)
        assertTrue(drawn.none { it[0] == 1.0 && abs(it[2] - 150) < 6 })
        // nada dibujado fuera de la tabla salvo nada: todos los segmentos en la zona de la tabla
        for (d in drawn) assertTrue("segmento ${d.toList()}", min(d[2], d[4]) >= 590 && max(d[2], d[4]) <= 1330)
        src.release(); out.release()
    }

    @Test fun pagina_soloTexto_nadaDibujado() {
        assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)
        val src = SyntheticScenes.textPage(1200, 1600)
        val out = Mat(1600, 1200, CvType.CV_8UC1, Scalar(255.0))
        assertEquals(0, TableLines.restore(out, src, 22.0))
        src.release(); out.release()
    }
}
