package com.scannerpromax.album

import android.graphics.Bitmap
import android.util.Log
import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.Cv
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.HeavyWork
import com.scannerpromax.imaging.ImageEnhancer
import com.scannerpromax.imaging.PerspectiveCorrector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Convierte la foto de un cromo en el cromo «pegable»: recorte en perspectiva del cuadrilátero, proporción real (o la
 * del recuadro si se parece: [AlbumGeometry.stickerRatio]) y mejora con un filtro que conserva el color. El resultado
 * se guarda en `stk/` del álbum a la resolución que pide el recuadro a 300 ppp (con margen), nunca a más de
 * [MAX_SIDE] px.
 */
class StickerMaker(private val store: AlbumStore, private val tier: DeviceTier) {

    /** Filtros que se ofrecen para un cromo (null = sin mejora). */
    val filters: List<FilterType?> = listOf(FilterType.VIVID, null, FilterType.NATURAL, FilterType.BLACK_WHITE)

    /**
     * [source] = archivo de la foto (relativo al álbum), [quad] normalizado 0..1, [slotMm] = (ancho, alto) del
     * recuadro en mm. Devuelve el cromo con su archivo rectificado ya escrito.
     */
    suspend fun make(albumId: String, slotId: String, source: String, quad: Quad, filter: FilterType?, slotMm: Pair<Double, Double>, base: Sticker? = null, manual: Boolean = false): Sticker =
        withContext(Dispatchers.Default) {
            val srcFile = store.file(albumId, source)
            val bmp = HeavyWork.run {
                val src = BitmapIO.decode(srcFile.absolutePath, min(tier.maxWorkingPixels, 12_000_000))
                try {
                    warp(src, quad, slotMm)
                } finally {
                    src.recycle()
                }
            }
            var out = bmp
            if (filter != null && filter != FilterType.ORIGINAL) {
                try {
                    val e = HeavyWork.run { ImageEnhancer.apply(bmp, filter, Adjustments(), tier, false) }
                    if (e !== bmp) { bmp.recycle(); out = e }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) { bmp.recycle(); throw t }
                    Log.w(TAG, "No se pudo mejorar el cromo", t)
                }
            }
            try {
                val (f, name) = store.newFile(albumId, "stk", slotId, "jpg")
                withContext(Dispatchers.IO) { BitmapIO.saveJpeg(out, f.absolutePath, 92) }
                (base ?: Sticker(source, quad, name, out.width, out.height)).copy(
                    source = source, quad = quad, rendered = name, renderedW = out.width, renderedH = out.height,
                    filter = filter, manualQuad = manual,
                )
            } finally {
                out.recycle()
            }
        }

    private fun warp(src: Bitmap, quadN: Quad, slotMm: Pair<Double, Double>): Bitmap {
        val w = src.width; val h = src.height
        val q = Quad.of(quadN.points().map { Pt(it.x * w, it.y * h) })
        val (ew, eh) = PerspectiveCorrector.estimateSize(q, w, h)
        val ratio = AlbumGeometry.stickerRatio(ew / max(1.0, eh), slotMm.first / slotMm.second)
        // resolución: la del recuadro a 300 ppp ×1,25, sin inventar píxeles que la foto no tiene
        val needLong = (max(slotMm.first, slotMm.second) / 25.4 * 300 * 1.25)
        val haveLong = max(ew, eh)
        val long = min(MAX_SIDE.toDouble(), max(MIN_SIDE.toDouble(), min(needLong, haveLong * 1.1)))
        val (ow, oh) = if (ratio >= 1) long to long / ratio else long * ratio to long
        val outW = ow.roundToInt().coerceAtLeast(8); val outH = oh.roundToInt().coerceAtLeast(8)
        val rgba = Cv.toRgba(src)
        val from = MatOfPoint2f(Point(q.tl.x.toDouble(), q.tl.y.toDouble()), Point(q.tr.x.toDouble(), q.tr.y.toDouble()), Point(q.br.x.toDouble(), q.br.y.toDouble()), Point(q.bl.x.toDouble(), q.bl.y.toDouble()))
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(outW.toDouble(), 0.0), Point(outW.toDouble(), outH.toDouble()), Point(0.0, outH.toDouble()))
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        try {
            Imgproc.warpPerspective(rgba, out, m, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            return Cv.toBitmap(out)
        } finally {
            rgba.release(); from.release(); to.release(); m.release(); out.release()
        }
    }

    companion object {
        private const val TAG = "StickerMaker"
        const val MAX_SIDE = 3600
        const val MIN_SIDE = 600

        fun filterLabel(f: FilterType?): String = when (f) {
            null, FilterType.ORIGINAL -> "Original"
            FilterType.VIVID -> "Color mejorado"
            FilterType.NATURAL -> "Documento"
            FilterType.BLACK_WHITE -> "Blanco y negro"
            else -> f.label
        }
    }
}
