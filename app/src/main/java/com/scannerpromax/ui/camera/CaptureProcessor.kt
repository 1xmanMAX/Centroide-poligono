package com.scannerpromax.ui.camera

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.BookSplitter
import com.scannerpromax.imaging.IdCardComposer
import com.scannerpromax.imaging.PerspectiveCorrector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Procesa las capturas según el modo (libro -> 2 páginas, DNI -> anverso+reverso en una hoja).
 * Todo en Dispatchers.Default y de uno en uno (Mutex) para no disparar la memoria en gama baja.
 */
internal class CaptureProcessor(private val container: AppContainer) {

    private val heavy = Mutex()
    private val tier get() = container.deviceTier

    /** Documento, recibo, pizarra, foto: el repositorio detecta bordes y aplica el filtro del modo. */
    suspend fun addStandard(docId: String, file: File) {
        try {
            container.documents.addPageFromFile(docId, file)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { if (file.exists()) file.delete() }
        }
    }

    /** Libro abierto: recorta el libro completo, detecta el lomo y guarda las páginas izquierda y derecha. */
    suspend fun addBook(docId: String, file: File, edits: PageEdits) = withContext(Dispatchers.Default) {
        heavy.withLock {
            var bmp: Bitmap? = null
            var warped: Bitmap? = null
            var left: Bitmap? = null
            var right: Bitmap? = null
            try {
                val src = BitmapIO.decode(file.absolutePath, tier.maxWorkingPixels)
                bmp = src
                val quad = detectQuad(src, minConfidence = 0.45f, minAreaFraction = 0.25f)
                val base = if (quad != null) PerspectiveCorrector.warp(src, quad).also { warped = it } else src
                val split = BookSplitter.split(base)
                left = split.left
                right = split.right
                // Libera lo antes posible para reducir el pico de memoria antes de guardar.
                warped?.recycle(); warped = null
                src.recycle(); bmp = null
                container.documents.addPageFromBitmap(docId, split.left, edits)
                container.documents.addPageFromBitmap(docId, split.right, edits)
            } finally {
                listOfNotNull(bmp, warped, left, right).forEach { if (!it.isRecycled) it.recycle() }
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }
    }

    /**
     * Recorta un lado del DNI/tarjeta. Si no se detecta la tarjeta usa [guide] (rectángulo guía normalizado
     * 0..1 sobre la imagen). Devuelve un bitmap compacto (lado largo <= 1600 px; la hoja final es a 300 dpi).
     */
    suspend fun cropCardSide(file: File, guide: RectF?): Bitmap = withContext(Dispatchers.Default) {
        heavy.withLock {
            val maxPx = min(tier.maxWorkingPixels, 8_000_000)
            val src = BitmapIO.decode(file.absolutePath, maxPx)
            try {
                val quad = detectQuad(src, minConfidence = 0.4f, minAreaFraction = 0.04f)
                val cropped: Bitmap = when {
                    quad != null -> PerspectiveCorrector.warp(src, quad)
                    guide != null -> cropRect(src, guide)
                    else -> src.copy(Bitmap.Config.ARGB_8888, false)
                }
                if (max(cropped.width, cropped.height) > CARD_MAX_SIDE) {
                    val small = BitmapIO.thumbnail(cropped, CARD_MAX_SIDE)
                    if (small !== cropped) cropped.recycle()
                    small
                } else cropped
            } finally {
                src.recycle()
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }
    }

    /** Compone anverso (+ reverso opcional) en una hoja A4 y la añade como página. No recicla las entradas. */
    suspend fun addIdCard(docId: String, front: Bitmap, back: Bitmap?, edits: PageEdits) = withContext(Dispatchers.Default) {
        heavy.withLock {
            val page = IdCardComposer.compose(front, back)
            try {
                container.documents.addPageFromBitmap(docId, page, edits)
            } finally {
                page.recycle()
            }
        }
    }

    private fun cropRect(src: Bitmap, n: RectF): Bitmap {
        val l = (n.left.coerceIn(0f, 1f) * src.width).roundToInt()
        val t = (n.top.coerceIn(0f, 1f) * src.height).roundToInt()
        val r = (n.right.coerceIn(0f, 1f) * src.width).roundToInt()
        val b = (n.bottom.coerceIn(0f, 1f) * src.height).roundToInt()
        val w = (r - l).coerceAtLeast(1)
        val h = (b - t).coerceAtLeast(1)
        if (l + w > src.width || t + h > src.height) return src.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(src, l, t, w, h)
        // createBitmap puede devolver el mismo objeto si el recorte es completo: siempre devolver uno nuevo
        return if (out === src) src.copy(Bitmap.Config.ARGB_8888, false) else out
    }

    /** Quad en coordenadas de [bmp] o null si no hay detección fiable / es casi la imagen completa. */
    private fun detectQuad(bmp: Bitmap, minConfidence: Float, minAreaFraction: Float): Quad? {
        val det = try {
            container.pageProcessor.detector.detect(bmp)
        } catch (t: Throwable) {
            Log.w(TAG, "Fallo al detectar", t)
            null
        } ?: return null
        if (det.confidence < minConfidence || det.frameWidth <= 0 || det.frameHeight <= 0) return null
        val sx = bmp.width.toFloat() / det.frameWidth
        val sy = bmp.height.toFloat() / det.frameHeight
        val q = Quad.of(det.quad.points().map { Pt((it.x * sx).coerceIn(0f, bmp.width.toFloat()), (it.y * sy).coerceIn(0f, bmp.height.toFloat())) })
        val area = polygonArea(q)
        val total = bmp.width.toFloat() * bmp.height
        if (area < total * minAreaFraction) return null
        if (area > total * 0.985f) return null
        return q
    }

    private fun polygonArea(q: Quad): Float {
        val p = q.points()
        var s = 0f
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    private companion object {
        const val TAG = "CaptureProcessor"
        const val CARD_MAX_SIDE = 1600
    }
}
