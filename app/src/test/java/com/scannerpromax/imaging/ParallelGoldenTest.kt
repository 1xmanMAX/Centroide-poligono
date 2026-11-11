package com.scannerpromax.imaging

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Test DORADO del reparto en paralelo: la misma página (foto sintética combada, con foto, tabla, sombra y ruido) procesada
 * con TODO en serie (1 hilo de OpenCV, [Par] y [NaturalColor] en serie: la cadena de antes) y con todo en paralelo da
 * exactamente los mismos píxeles en cada filtro. (El banco PipelineBenchTest compara además con las salidas de antes a 12 MP.)
 */
class ParallelGoldenTest {

    private val savedPar = Par.threads
    private val savedNc = NaturalColor.maxThreads

    @Before fun cv() = assumeTrue("OpenCV de escritorio no disponible", SyntheticScenes.openCv)

    @After fun restore() {
        Par.threads = savedPar; NaturalColor.maxThreads = savedNc
        Core.setNumThreads(Runtime.getRuntime().availableProcessors())
    }

    private fun scene(): Pair<Mat, Quad> {
        val page = PerfScenes.page(1240, 1754)
        val (rgb, q) = PerfScenes.photo(page, 1500, 2000, 30.0)
        page.release()
        return rgb to Quad(Pt(q[0].toFloat(), q[1].toFloat()), Pt(q[2].toFloat(), q[3].toFloat()), Pt(q[4].toFloat(), q[5].toFloat()), Pt(q[6].toFloat(), q[7].toFloat()))
    }

    private fun render(rgb: Mat, quad: Quad, filter: FilterType, threads: Int): Mat {
        Core.setNumThreads(threads); Par.threads = threads; NaturalColor.maxThreads = threads
        val rgba = Mat(); Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        val proc = PageProcessor(DeviceTier(false, 20_000_000, 8))
        Core.setNumThreads(threads)
        return proc.renderMat(rgba, PageEdits(quad = quad, filter = filter))
    }

    @Test fun serieYParaleloDanLosMismosPixeles() {
        val (rgb, quad) = scene()
        try {
            for (f in listOf(FilterType.NATURAL, FilterType.BLACK_WHITE, FilterType.MAGIC, FilterType.ORIGINAL)) {
                val a = render(rgb, quad, f, 1)
                val b = render(rgb, quad, f, 4)
                try {
                    assertEquals("$f: tamaño", a.size(), b.size())
                    assertEquals("$f: canales", a.channels(), b.channels())
                    val d = Mat(); Core.absdiff(a, b, d)
                    val flat = d.reshape(1)
                    assertEquals("$f: valores distintos", 0, Core.countNonZero(flat))
                    flat.release(); d.release()
                } finally { a.release(); b.release() }
            }
        } finally { rgb.release() }
    }
}
