package com.scannerpromax.ui.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.BookSplitter
import com.scannerpromax.imaging.IdCardComposer
import com.scannerpromax.imaging.ImageEnhancer
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
    suspend fun addStandard(docId: String, file: File, mode: ScanMode? = null) {
        try {
            container.documents.addPageFromFile(docId, file, mode = mode)
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
                val base = if (quad != null) {
                    PerspectiveCorrector.warp(src, quad).also {
                        warped = it
                        // El original completo ya no hace falta: liberarlo antes de dividir baja el pico de memoria.
                        src.recycle(); bmp = null
                    }
                } else src
                val split = BookSplitter.split(base)
                left = split.left
                right = split.right
                // Libera lo antes posible para reducir el pico de memoria antes de guardar.
                warped?.recycle(); warped = null
                bmp?.recycle(); bmp = null
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
    suspend fun cropCardSide(file: File, guide: RectF?): Bitmap = cropCardSide(file, guide, null)

    /**
     * Como [cropCardSide], y además mejora la cara con [enhance] (un filtro que conserve el color, p. ej.
     * VIVID) ANTES de componer la hoja: así el fondo de la tarjeta y la foto no se aplanan hacia blanco.
     * La cara se rellena con blanco hasta la proporción ID-1 para que la composición no recorte bordes.
     */
    suspend fun cropCardSide(file: File, guide: RectF?, enhance: FilterType?): Bitmap = withContext(Dispatchers.Default) {
        val side = cropCardSideRaw(file, guide)
        var out = side
        if (enhance != null && enhance != FilterType.ORIGINAL) {
            try {
                val e = heavy.withLock { ImageEnhancer.apply(side, enhance, Adjustments(), tier, false) }
                if (e !== side) { side.recycle(); out = e }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) { side.recycle(); throw t }
                Log.w(TAG, "No se pudo mejorar la cara de la tarjeta", t)
            }
        }
        val padded = padToCardRatio(out)
        if (padded !== out) out.recycle()
        padded
    }

    /** Copia un Uri (galería) a un archivo de captura temporal para procesarlo como una foto de la cámara. */
    suspend fun copyToCaptureFile(uri: Uri): File = withContext(Dispatchers.IO) {
        val f = container.documents.newCaptureFile()
        try {
            val input = container.appContext.contentResolver.openInputStream(uri) ?: throw java.io.IOException("No se pudo abrir la imagen")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            f
        } catch (t: Throwable) {
            f.delete()
            throw t
        }
    }

    private suspend fun cropCardSideRaw(file: File, guide: RectF?): Bitmap = withContext(Dispatchers.Default) {
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

    /** Rellena con blanco hasta la proporción ISO ID-1 si se desvía más de un 2 % (evita recortes al componer). */
    private fun padToCardRatio(b: Bitmap): Bitmap {
        val long = max(b.width, b.height).toFloat()
        val short = min(b.width, b.height).toFloat()
        if (short <= 0f) return b
        val ratio = long / short
        if (abs(ratio - ID_CARD_RATIO) / ID_CARD_RATIO < 0.02f) return b
        val landscape = b.width >= b.height
        val (newLong, newShort) = if (ratio < ID_CARD_RATIO) Pair(short * ID_CARD_RATIO, short) else Pair(long, long / ID_CARD_RATIO)
        val w = (if (landscape) newLong else newShort).roundToInt().coerceAtLeast(b.width)
        val h = (if (landscape) newShort else newLong).roundToInt().coerceAtLeast(b.height)
        return try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.WHITE)
                drawBitmap(b, (w - b.width) / 2f, (h - b.height) / 2f, null)
            }
            out
        } catch (_: OutOfMemoryError) {
            b
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

    /**
     * Importa imágenes de la galería respetando el modo del documento: LIBRO divide cada foto en dos
     * páginas; DNI toma las imágenes de dos en dos (anverso + reverso, la impar va sola); el resto,
     * importación estándar del repositorio. [onProgress] recibe (hechas, total). Devuelve las imágenes
     * procesadas con éxito.
     */
    suspend fun importUris(
        docId: String,
        mode: ScanMode,
        uris: List<Uri>,
        settings: AppSettings,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        var ok = 0
        var done = 0
        val total = uris.size
        suspend fun guard(count: Int, block: suspend () -> Unit) {
            try {
                block()
                ok += count
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo importar una imagen", t)
            }
            done += count
            onProgress(done, total)
        }
        when (mode) {
            ScanMode.BOOK -> uris.forEach { uri ->
                guard(1) { addBook(docId, copyToCaptureFile(uri), composedEdits(mode, settings)) }
            }
            ScanMode.ID_CARD -> uris.chunked(2).forEach { pair ->
                guard(pair.size) {
                    var f: Bitmap? = null
                    var b: Bitmap? = null
                    try {
                        val filter = cardFilter(settings)
                        f = cropCardSide(copyToCaptureFile(pair[0]), null, filter)
                        b = pair.getOrNull(1)?.let { cropCardSide(copyToCaptureFile(it), null, filter) }
                        addIdCard(docId, f, b, composedEdits(mode, settings))
                    } finally {
                        f?.recycle(); b?.recycle()
                    }
                }
            }
            else -> uris.forEach { uri ->
                guard(1) { container.documents.addPagesFromUris(docId, listOf(uri), mode = mode) }
            }
        }
        return ok
    }

    companion object {
        private const val TAG = "CaptureProcessor"
        private const val CARD_MAX_SIDE = 1600

        /** Ediciones de las páginas que compone la cámara (libro y DNI); respeta el filtro de Ajustes. */
        fun composedEdits(mode: ScanMode, settings: AppSettings): PageEdits = when (mode) {
            // DNI: cada cara ya se mejoró por separado conservando el color; la hoja va sin filtro ni
            // limpieza (un filtro de documento aplanaría el fondo de la tarjeta y borraría sus líneas).
            ScanMode.ID_CARD -> PageEdits(
                quad = null,
                filter = FilterType.ORIGINAL,
                autoRemoveLines = false,
                autoDenoise = false,
                autoDeskew = false,
            )
            else -> PageEdits(
                quad = null,
                filter = settings.defaultFilter,
                autoRemoveLines = settings.autoRemoveLines,
                autoDeskew = mode == ScanMode.BOOK,
            )
        }

        /** Filtro aplicado a cada cara del DNI antes de componer (conserva el color); null = sin mejora. */
        fun cardFilter(settings: AppSettings): FilterType? =
            if (settings.defaultFilter == FilterType.ORIGINAL) null else FilterType.VIVID
    }
}
