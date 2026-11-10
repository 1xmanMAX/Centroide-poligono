package com.scannerpromax.album

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.scannerpromax.imaging.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fondos de las hojas del álbum: imagen de la galería, página de un PDF o página escaneada de la app. Todo se copia a
 * la carpeta del álbum (si se borra el documento o el PDF original, el álbum sigue intacto).
 */
class AlbumImport(private val context: Context, private val store: AlbumStore, private val ensurePdfBox: () -> Unit) {

    /** PdfRenderer no admite dos páginas abiertas a la vez en el mismo archivo ni es seguro entre hilos. */
    private val pdfMutex = Mutex()

    /** Copia un Uri a la caché (PdfRenderer y BitmapIO necesitan un archivo). */
    suspend fun copyToCache(uri: Uri, ext: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "album_import").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 6 * 3_600_000L) it.delete() }
        val f = File(dir, "imp_${System.nanoTime()}.$ext")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        if (f.length() <= 0L) { f.delete(); throw IOException("El archivo está vacío") }
        f
    }

    /**
     * Fondo de imagen: se re-codifica a JPEG con el EXIF aplicado (lado largo ≤ [MAX_BG_SIDE] ≈ 400 ppp en A4) y se
     * guarda una vista previa para el editor.
     */
    suspend fun imageBackground(albumId: String, file: File, label: String? = null): SheetBackground = withContext(Dispatchers.Default) {
        val bmp = BitmapIO.decode(file.absolutePath, MAX_BG_PIXELS)
        try {
            val big = scaleToLongSide(bmp, MAX_BG_SIDE)
            val (f, name) = store.newFile(albumId, "bg", "img", "jpg")
            try {
                withContext(Dispatchers.IO) { BitmapIO.saveJpeg(big, f.absolutePath, 92) }
                val (pf, pname) = store.newFile(albumId, "bg", "img_p", "jpg")
                val prev = BitmapIO.thumbnail(big, PREVIEW_SIDE)
                try { withContext(Dispatchers.IO) { BitmapIO.saveJpeg(prev, pf.absolutePath, 88) } } finally { prev.recycle() }
                SheetBackground(BackgroundKind.IMAGE, name, pname, big.width.toFloat(), big.height.toFloat(), label = label)
            } finally {
                if (big !== bmp) big.recycle()
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Número de páginas de un PDF (lanza si está cifrado o dañado). */
    suspend fun pdfPageCount(pdf: File): Int = pdfMutex.withLock {
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
        }
    }

    /** Miniatura de la página [index] (0..n-1) de [pdf] con lado largo [maxSide], fondo blanco. */
    suspend fun pdfThumbnail(pdf: File, index: Int, maxSide: Int): Bitmap = pdfMutex.withLock {
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r -> r.openPage(index).use { p -> renderPage(p, maxSide.toDouble() / max(p.width, p.height)) } }
            }
        }
    }

    /**
     * Fondo de página PDF: se extrae la página [index] a un PDF propio de UNA página (se exporta como vectorial) y se
     * guarda su vista previa para el editor.
     */
    suspend fun pdfBackground(albumId: String, pdf: File, index: Int, label: String?): SheetBackground = withContext(Dispatchers.IO) {
        ensurePdfBox()
        val (f, name) = store.newFile(albumId, "bg", "pdf", "pdf")
        val (dw, dh) = AlbumPdfWriter.extractPage(pdf, index, f)
        val (pf, pname) = store.newFile(albumId, "bg", "pdf_p", "jpg")
        val prev = pdfThumbnail(f, 0, PREVIEW_SIDE)
        try { BitmapIO.saveJpeg(prev, pf.absolutePath, 88) } finally { prev.recycle() }
        SheetBackground(BackgroundKind.PDF_PAGE, name, pname, dw.toFloat(), dh.toFloat(), pdfPage = index + 1, label = label)
    }

    /**
     * Fondo de página PDF a resolución de exportación: [pxW]×[pxH] es el tamaño SIN girar que se quiere; la página se
     * dibuja en blanco y se devuelve (la gira quien llama).
     */
    suspend fun renderPdfBackground(albumId: String, bg: SheetBackground, pxW: Int, pxH: Int): Bitmap? {
        val f = bg.file?.let { store.file(albumId, it) }?.takeIf { it.isFile } ?: return null
        return pdfMutex.withLock {
            withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        r.openPage(0).use { p ->
                            val out = Bitmap.createBitmap(pxW.coerceAtLeast(1), pxH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            out.eraseColor(Color.WHITE)
                            val m = Matrix().apply { setScale(pxW.toFloat() / p.width, pxH.toFloat() / p.height) }
                            p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            out
                        }
                    }
                }
            }
        }
    }

    private fun renderPage(p: PdfRenderer.Page, scale: Double): Bitmap {
        val w = (p.width * scale).roundToInt().coerceAtLeast(1)
        val h = (p.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        p.render(out, null, Matrix().apply { setScale(w.toFloat() / p.width, h.toFloat() / p.height) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return out
    }

    private fun scaleToLongSide(b: Bitmap, side: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= side) return b
        val s = side.toDouble() / long
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }

    companion object {
        const val MAX_BG_SIDE = 4960
        /** ≈ 12 MP: una foto normal entra sin reducir y la memoria queda acotada (48 MB) en gama baja. */
        const val MAX_BG_PIXELS = 12_000_000
        const val PREVIEW_SIDE = 1600

        /** Píxeles a 300 ppp de una hoja de [wMm]×[hMm] mm, rebajados para no pasar de [maxPixels]. */
        fun exportPxPerMm(wMm: Double, hMm: Double, maxPixels: Int, dpi: Double = 300.0): Double {
            val k = dpi / 25.4
            val px = wMm * k * hMm * k
            return if (px <= maxPixels) k else k * sqrt(maxPixels / px)
        }
    }
}
