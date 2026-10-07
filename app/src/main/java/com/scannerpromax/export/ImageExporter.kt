package com.scannerpromax.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.ImageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.math.max

class ImageExporter(private val context: Context) {

    /** Guarda imágenes en la galería (MediaStore, Pictures/EscanerProMax). Devuelve las Uris creadas. */
    suspend fun saveToGallery(images: List<File>, format: ImageFormat, quality: ExportQuality, baseName: String): List<Uri> =
        withContext(Dispatchers.IO) {
            val base = sanitizeName(baseName).ifEmpty { "Escaneo" }
            images.mapIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val name = if (images.size == 1) "$base.${format.ext}" else "${base}_${index + 1}.${format.ext}"
                writeToPictures(name, format.mime) { out -> encode(file, format, quality, out) }
            }
        }

    /** Guarda un PDF en Descargas (MediaStore Downloads/EscanerProMax en API 29+). */
    suspend fun savePdfToDownloads(pdf: File, displayName: String): Uri = saveToDownloads(pdf, displayName, MIME_PDF, "pdf")

    /**
     * Guarda cualquier archivo (PDF, .txt, .docx…) en Descargas/EscanerProMax. [extension] sin punto; se añade al
     * nombre si falta.
     */
    suspend fun saveToDownloads(file: File, displayName: String, mime: String, extension: String): Uri = withContext(Dispatchers.IO) {
        var name = sanitizeName(displayName).ifEmpty { "Escaneo" }
        if (!name.endsWith(".$extension", ignoreCase = true)) name += ".$extension"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            insertAndWrite(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) { out ->
                file.inputStream().use { it.copyTo(out, BUFFER) }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            writeLegacy(dir, name, mime) { out -> file.inputStream().use { it.copyTo(out, BUFFER) } }
        }
    }

    /** Intent para compartir archivos (FileProvider, authority "${applicationId}.fileprovider"). */
    fun shareIntent(files: List<File>, mime: String): Intent {
        require(files.isNotEmpty()) { "No hay archivos para compartir" }
        val authority = "${context.packageName}.fileprovider"
        val uris = files.map { FileProvider.getUriForFile(context, authority, shareable(it)) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.type = mime
        val clip = ClipData.newUri(context.contentResolver, files.first().name, uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /** Selector del sistema listo para startActivity (conserva permisos de lectura). */
    fun shareChooser(files: List<File>, mime: String, title: String = "Compartir"): Intent =
        Intent.createChooser(shareIntent(files, mime), title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * Exporta las imágenes re-codificadas a una carpeta de caché (para compartirlas en el formato/calidad
     * elegidos sin pasar por la galería). Devuelve los archivos creados.
     */
    suspend fun exportToCache(images: List<File>, format: ImageFormat, quality: ExportQuality, baseName: String): List<File> =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
            val base = sanitizeName(baseName).ifEmpty { "Escaneo" }
            images.mapIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val name = if (images.size == 1) "$base.${format.ext}" else "${base}_${index + 1}.${format.ext}"
                val out = File(dir, name)
                out.outputStream().buffered(BUFFER).use { encode(file, format, quality, it) }
                out
            }
        }

    // ------------------------------------------------------------------------------------------

    /**
     * Re-codifica [file] en el formato/calidad pedidos (lado largo <= maxLongSide de la calidad). La reducción de
     * resolución ocurre SOLO aquí. Sin recodificar (copia exacta de bytes) cuando no hay que reducir y el formato
     * ya coincide: JPEG procesado -> JPG en "Alta"/"Máxima (HD)", y PNG (B/N) -> PNG en cualquier calidad.
     */
    private fun encode(file: File, format: ImageFormat, quality: ExportQuality, out: OutputStream) {
        val (w, h) = ImageCodec.bounds(file)
        if (w <= 0 || h <= 0) throw IOException("Imagen ilegible: ${file.name}")
        val fits = quality.isFullResolution || max(w, h) <= quality.maxLongSide
        val passthrough = fits && when (format) {
            ImageFormat.JPEG -> (quality.isFullResolution || quality.jpegQuality >= 90) && ImageCodec.isJpeg(file)
            ImageFormat.PNG -> ImageCodec.isPng(file)
            ImageFormat.WEBP -> false
        }
        if (passthrough) {
            file.inputStream().use { it.copyTo(out, BUFFER) }
            return
        }
        val bmp = ImageCodec.decodeScaled(file, quality.maxLongSide)
        try {
            val toWrite = if (format == ImageFormat.JPEG) ImageCodec.flattenOnWhite(bmp) else bmp
            try {
                // "Máxima (HD)" en WEBP = sin pérdida (Android 11+); en JPEG, 95.
                val q = if (format == ImageFormat.WEBP && quality.isFullResolution) 100 else quality.jpegQuality
                val ok = toWrite.compress(ImageCodec.compressFormat(format, q), q, out)
                if (!ok) throw IOException("No se pudo codificar la imagen")
            } finally {
                if (toWrite !== bmp) toWrite.recycle()
            }
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun writeToPictures(name: String, mime: String, writer: (OutputStream) -> Unit): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            insertAndWrite(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values, writer)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            writeLegacy(dir, name, mime, writer)
        }

    /** API 29+: inserta pendiente, escribe y publica; si falla, borra la entrada. */
    private fun insertAndWrite(collection: Uri, values: ContentValues, writer: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear el archivo de destino")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("No se pudo escribir el archivo")
            stream.buffered(BUFFER).use { writer(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (t: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) { }
            throw t
        }
    }

    /** API 24-28: escribe en el directorio público (requiere WRITE_EXTERNAL_STORAGE) y lo indexa. */
    private suspend fun writeLegacy(dir: File, name: String, mime: String, writer: (OutputStream) -> Unit): Uri {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("No se pudo acceder al almacenamiento. Concede el permiso de almacenamiento.")
        }
        val file = uniqueFile(dir, name)
        try {
            file.outputStream().buffered(BUFFER).use { writer(it) }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return scan(file, mime) ?: Uri.fromFile(file)
    }

    private suspend fun scan(file: File, mime: String): Uri? = suspendCancellableCoroutine { cont ->
        try {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
                if (cont.isActive) cont.resume(uri)
            }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) f = File(dir, "$stem ($i)$ext").also { i++ }
        return f
    }

    /** FileProvider solo cubre files/documents y cache: si el archivo está en otro sitio se copia a la caché. */
    private fun shareable(file: File): File {
        val path = file.canonicalPath
        val docs = File(context.filesDir, "documents").canonicalPath
        val cache = context.cacheDir.canonicalPath
        if (path.startsWith(docs + File.separator) || path.startsWith(cache + File.separator)) return file
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val copy = File(dir, file.name)
        file.copyTo(copy, overwrite = true)
        return copy
    }

    private fun sanitizeName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).trim()

    private companion object {
        const val FOLDER = "EscanerProMax"
        const val MIME_PDF = "application/pdf"
        const val BUFFER = 64 * 1024
    }
}
