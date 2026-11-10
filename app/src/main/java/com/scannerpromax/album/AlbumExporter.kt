package com.scannerpromax.album

import android.content.Context
import android.graphics.Bitmap
import com.scannerpromax.data.DocumentRepository
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.HeavyWork
import com.scannerpromax.ui.components.safeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Exportación del álbum: PDF (fondo PDF vectorial, imágenes sin recomprimir), imágenes JPEG a 300 ppp y «Crear
 * documento» (las hojas como páginas de un documento normal de la app, sin filtros, para compartirlas, unirlas a
 * otros documentos o pasarles OCR).
 */
class AlbumExporter(
    private val context: Context,
    private val store: AlbumStore,
    private val import: AlbumImport,
    private val tier: DeviceTier,
    private val ensurePdfBox: () -> Unit,
) {

    private fun outDir(): File = File(context.cacheDir, "share").apply { mkdirs() }

    suspend fun exportPdf(album: Album, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        ensurePdfBox()
        val out = File(outDir(), safeFileName(album.title, "Album") + ".pdf")
        val tmp = File(out.parentFile, ".${out.name}.tmp")
        tmp.outputStream().buffered(64 * 1024).use { os ->
            AlbumPdfWriter.write(album.pages, store.dir(album.id), os, album.title, progress)
        }
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        out
    }

    /** Renderiza la hoja a 300 ppp (o menos si no cabe en la memoria del equipo). Recicla quien llama. */
    suspend fun renderPage(album: Album, page: AlbumPage, dpi: Double = 300.0): Bitmap {
        val maxPx = min(tier.maxWorkingPixels, if (tier.isLowRam) 9_000_000 else 16_000_000)
        val k = AlbumImport.exportPxPerMm(page.widthMm.toDouble(), page.heightMm.toDouble(), maxPx, dpi)
        val bgBitmap = backgroundFor(album.id, page, k)
        return HeavyWork.run {
            // el renderizador recicla el fondo que le pide; si no lo pide (sin tamaño), se recicla aquí
            var called = false
            try {
                AlbumRenderer.render(page, store.dir(album.id), k, { _, _ -> called = true; bgBitmap })
            } finally {
                if (!called) bgBitmap?.recycle()
            }
        }
    }

    /** Fondo SIN girar decodificado al tamaño que ocupa a [pxPerMm] (null = blanco). */
    private suspend fun backgroundFor(albumId: String, page: AlbumPage, pxPerMm: Double): Bitmap? {
        val bg = page.background
        val rotated = (page.rotation / 90) % 2 == 1
        val wantW = ((if (rotated) page.heightMm else page.widthMm) * pxPerMm).toInt()
        val wantH = ((if (rotated) page.widthMm else page.heightMm) * pxPerMm).toInt()
        return when (bg.kind) {
            BackgroundKind.BLANK -> null
            BackgroundKind.PDF_PAGE -> import.renderPdfBackground(albumId, bg, wantW, wantH)
            BackgroundKind.IMAGE -> bg.file?.let { store.file(albumId, it) }?.takeIf { it.isFile }?.let { f ->
                withContext(Dispatchers.IO) { BitmapIO.decode(f.absolutePath, (wantW.toLong() * wantH).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
            }
        }
    }

    suspend fun exportImages(album: Album, progress: (Float) -> Unit = {}): List<File> {
        val base = safeFileName(album.title, "Album")
        return album.pages.mapIndexed { i, page ->
            currentCoroutineContext().ensureActive()
            val bmp = renderPage(album, page)
            try {
                val f = File(outDir(), if (album.pages.size == 1) "$base.jpg" else "${base}_${i + 1}.jpg")
                withContext(Dispatchers.IO) { BitmapIO.saveJpeg(bmp, f.absolutePath, 92) }
                progress((i + 1f) / album.pages.size)
                f
            } finally {
                bmp.recycle()
            }
        }
    }

    /** Crea un documento de la app con una página por hoja (sin filtros ni limpieza: la hoja tal cual). */
    suspend fun createDocument(album: Album, documents: DocumentRepository, progress: (Float) -> Unit = {}): String {
        val doc = documents.create(ScanMode.DOCUMENT, album.title)
        val edits = PageEdits(
            quad = null, filter = FilterType.ORIGINAL,
            autoRemoveLines = false, autoDenoise = false, autoDeskew = false, autoDewarp = false, keepRuling = false,
        )
        try {
            album.pages.forEachIndexed { i, page ->
                val bmp = renderPage(album, page)
                try {
                    documents.addPageFromBitmap(doc.id, bmp, edits)
                } finally {
                    bmp.recycle()
                }
                progress((i + 1f) / album.pages.size)
            }
        } catch (t: Throwable) {
            if (documents.get(doc.id)?.pages.isNullOrEmpty()) runCatching { documents.delete(doc.id) }
            throw t
        }
        return doc.id
    }
}
