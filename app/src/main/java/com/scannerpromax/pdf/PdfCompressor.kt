package com.scannerpromax.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.scannerpromax.domain.CompressionLevel
import com.scannerpromax.export.ImageCodec
import com.scannerpromax.imaging.ProgressCallback
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Resultado de compresión. [keptOriginal] = true si comprimir no reducía el tamaño y se devolvió
 * una copia del original (entonces compressedBytes == originalBytes).
 */
data class CompressionResult(
    val output: File,
    val originalBytes: Long,
    val compressedBytes: Long,
    val pageCount: Int,
    val keptOriginal: Boolean = false,
    /** true si las páginas se rasterizaron: el texto deja de ser seleccionable/buscable (avisar en la UI). */
    val rasterized: Boolean = false,
) {
    /** Porcentaje ahorrado (0..100). */
    val savedPercent: Int
        get() = if (originalBytes <= 0) 0 else ((1.0 - compressedBytes.toDouble() / originalBytes) * 100).roundToInt().coerceIn(0, 100)
}

/** El PDF está protegido con contraseña y no se puede abrir. */
class PdfPasswordException(message: String = "El PDF está protegido con contraseña. Quita la contraseña e inténtalo de nuevo.") :
    IOException(message)

/** El archivo no es un PDF válido o está dañado. */
class PdfInvalidException(message: String = "No se pudo abrir el PDF: el archivo está dañado o no es un PDF.", cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Compresor de PDF en dos estrategias:
 *  1. Estructural (preferida): abre el PDF con pdfbox y solo re-codifica las imágenes grandes (resolución del
 *     nivel, JPEG o 1 bit si son B/N). Texto, vectores, enlaces y capa OCR se conservan intactos.
 *  2. Rasterizado (respaldo): re-renderiza cada página con PdfRenderer y la guarda como JPEG. Solo se usa si la
 *     estructural no reduce lo suficiente; entonces el texto pasa a imagen y [CompressionResult.rasterized] = true.
 */
class PdfCompressor(private val context: Context) {

    private val lowRam: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am == null || am.isLowRamDevice || am.memoryClass <= 192
    }

    private data class Params(val dpi: Int, val quality: Int, val grayscale: Boolean)

    /** Comprime un PDF arbitrario (de cualquier app). Conserva el texto siempre que sea posible. */
    suspend fun compress(input: Uri, level: CompressionLevel, output: File, progress: ProgressCallback? = null): CompressionResult =
        withContext(Dispatchers.IO) {
            val source = copyToCache(input)
            val temps = ArrayList<File>()
            try {
                val originalBytes = source.length()
                val params = Params(level.dpi, level.jpegQuality, level.grayscale)

                // 1) Estructural: conserva texto y vectores.
                val structural = tempFile("s").also { temps += it }
                val structuralPages = try {
                    recompressImages(source, params, structural) { progress?.invoke(it * 0.45f) }
                } catch (e: PdfPasswordException) {
                    throw e
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    structural.delete()
                    -1
                }
                val structuralSize = if (structuralPages > 0) structural.length() else Long.MAX_VALUE
                if (structuralPages > 0 && structuralSize <= originalBytes * GOOD_ENOUGH_RATIO) {
                    val result = finish(source, structural, output, structuralPages, rasterized = false)
                    progress?.invoke(1f)
                    return@withContext result
                }

                // 2) Rasterizado como respaldo: solo se elige si es claramente más pequeño.
                val raster = tempFile("r").also { temps += it }
                val rasterPages = render(source, params, raster) { progress?.invoke(0.45f + it * 0.53f) }
                val rasterSize = raster.length()
                val result = if (structuralPages > 0 && structuralSize <= rasterSize * RASTER_PREFERENCE) {
                    finish(source, structural, output, structuralPages, rasterized = false)
                } else {
                    finish(source, raster, output, rasterPages, rasterized = true)
                }
                progress?.invoke(1f)
                result
            } finally {
                temps.forEach { it.delete() }
                source.delete()
            }
        }

    /**
     * Comprime un PDF a un tamaño objetivo aproximado (búsqueda binaria de calidad/dpi).
     * Primero intenta la vía estructural (conserva texto); si ni al mínimo alcanza el objetivo, rasteriza.
     * Todos los intentos intermedios se borran aunque se cancele o falle.
     */
    suspend fun compressToTarget(input: Uri, targetBytes: Long, output: File, progress: ProgressCallback? = null): CompressionResult =
        withContext(Dispatchers.IO) {
            val source = copyToCache(input)
            val temps = ArrayList<File>()
            try {
                val originalBytes = source.length()
                if (originalBytes <= targetBytes) {
                    val pages = pageCount(source)
                    copyReplacing(source, output)
                    progress?.invoke(1f)
                    return@withContext CompressionResult(output, originalBytes, originalBytes, pages, keptOriginal = true)
                }
                val initialS = (sqrt(targetBytes.toDouble() / originalBytes).toFloat() * 0.9f).coerceIn(0.15f, 0.7f)

                // Sonda estructural al mínimo: si ni así cabe, la vía estructural no sirve para este objetivo.
                val probe = tempFile("probe").also { temps += it }
                val probePages = try {
                    recompressImages(source, paramsFor(0f), probe) { progress?.invoke(it * 0.15f) }
                } catch (e: PdfPasswordException) {
                    throw e
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    -1
                }
                if (probePages > 0 && probe.length() <= targetBytes) {
                    val outcome = search(
                        initialS, targetBytes, temps, seed = SearchSeed(probe, 0f),
                        progress = { p -> progress?.invoke(0.15f + p * 0.83f) },
                    ) { params, out, cb -> recompressImages(source, params, out, cb) }
                    val chosen = outcome.bestFit ?: probe
                    val result = finish(source, chosen, output, probePages, rasterized = false)
                    progress?.invoke(1f)
                    return@withContext result
                }

                val outcome = search(
                    initialS, targetBytes, temps, seed = null,
                    progress = { p -> progress?.invoke(0.15f + p * 0.83f) },
                ) { params, out, cb -> render(source, params, out, cb) }
                // Si la estructural (sin cumplir) es aún más pequeña que el mejor raster, se prefiere (conserva texto).
                val rasterChoice = outcome.bestFit ?: outcome.smallest
                val result = when {
                    outcome.bestFit == null && probePages > 0 && probe.length() <= outcome.smallestSize ->
                        finish(source, probe, output, probePages, rasterized = false)
                    rasterChoice != null -> finish(source, rasterChoice, output, outcome.pageCount, rasterized = true)
                    else -> throw IOException("No se pudo comprimir el PDF")
                }
                progress?.invoke(1f)
                result
            } finally {
                temps.forEach { it.delete() }
                source.delete()
            }
        }

    private class SearchSeed(val file: File, val s: Float)
    private class SearchOutcome(val bestFit: File?, val smallest: File?, val smallestSize: Long, val pageCount: Int)

    /**
     * Búsqueda binaria sobre un factor de calidad s in [0,1] -> (dpi, calidad JPEG). Cada intento se registra en
     * [temps] (el llamador los borra en su finally). [seed] = intento previo conocido que ya cumple el objetivo.
     */
    private suspend fun search(
        initialS: Float,
        targetBytes: Long,
        temps: MutableList<File>,
        seed: SearchSeed?,
        progress: (Float) -> Unit,
        attemptFn: suspend (Params, File, (Float) -> Unit) -> Int,
    ): SearchOutcome {
        var lo = seed?.s ?: 0f
        var hi = 1f
        var bestFit: File? = seed?.file
        var smallest: File? = seed?.file
        var smallestSize = seed?.file?.length() ?: Long.MAX_VALUE
        var pageCount = 0
        var s = initialS.coerceAtLeast(lo)
        for (iter in 0 until MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            if (iter == MAX_ITERATIONS - 1 && bestFit == null) s = 0f // último recurso: mínimo
            val attempt = tempFile("t$iter").also { temps += it }
            val base = iter.toFloat() / MAX_ITERATIONS
            pageCount = attemptFn(paramsFor(s), attempt) { p -> progress(base + p / MAX_ITERATIONS) }
            val size = attempt.length()
            var keep = false
            if (size <= targetBytes) {
                bestFit?.takeIf { it != smallest && it != seed?.file }?.delete()
                bestFit = attempt
                keep = true
                lo = s
            } else {
                hi = s
            }
            if (size < smallestSize) {
                smallest?.takeIf { it != bestFit && it != seed?.file }?.delete()
                smallest = attempt
                smallestSize = size
                keep = true
            }
            if (!keep) attempt.delete()
            // Suficientemente cerca del objetivo (90-100%): no vale la pena seguir.
            if (size <= targetBytes && size >= targetBytes * 0.9) break
            if (hi - lo < 0.04f) break
            s = (lo + hi) / 2f
        }
        return SearchOutcome(bestFit, smallest, smallestSize, pageCount)
    }

    // ------------------------------------------------------------------------------------------

    private fun paramsFor(s: Float): Params {
        val dpi = (MIN_DPI + s * (MAX_DPI - MIN_DPI)).roundToInt()
        val q = (MIN_Q + s * (MAX_Q - MIN_Q)).roundToInt()
        return Params(dpi, q, grayscale = false)
    }

    /** Si el resultado no es más pequeño que el original se devuelve una copia del original. */
    private fun finish(source: File, compressed: File, output: File, pages: Int, rasterized: Boolean): CompressionResult {
        val originalBytes = source.length()
        val compressedBytes = compressed.length()
        output.parentFile?.mkdirs()
        return if (compressedBytes in 1 until originalBytes) {
            copyReplacing(compressed, output)
            CompressionResult(output, originalBytes, compressedBytes, pages, rasterized = rasterized)
        } else {
            copyReplacing(source, output)
            CompressionResult(output, originalBytes, originalBytes, pages, keptOriginal = true)
        }
    }

    /** Renderiza todas las páginas de [source] con [params] y escribe el PDF en [out]. Devuelve nº de páginas. */
    private suspend fun render(source: File, params: Params, out: File, progress: (Float) -> Unit): Int {
        val pfd = openPfd(source)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: SecurityException) {
            pfd.close()
            throw PdfPasswordException()
        } catch (e: IOException) {
            pfd.close()
            throw rendererError(source, e)
        }
        val doc = PDDocument(PdfSupport.tempMemorySetting(context))
        var bitmap: Bitmap? = null
        var grayBitmap: Bitmap? = null
        try {
            PdfSupport.applyMetadata(doc, null)
            val count = renderer.pageCount
            if (count <= 0) throw PdfInvalidException("El PDF no tiene páginas.")
            val grayPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                renderer.openPage(i).use { page ->
                    val wPt = page.width.toFloat().coerceAtLeast(1f)
                    val hPt = page.height.toFloat().coerceAtLeast(1f)
                    val (pxW, pxH) = pixelSize(wPt, hPt, params.dpi)
                    // Reutiliza el bitmap entre páginas del mismo tamaño (lo habitual): menos GC y menos picos.
                    val bmp = bitmap?.takeIf { it.width == pxW && it.height == pxH && !it.isRecycled }
                        ?: run {
                            bitmap?.recycle()
                            Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888).also { bitmap = it }
                        }
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val toEncode = if (params.grayscale) {
                        val g = grayBitmap?.takeIf { it.width == pxW && it.height == pxH && !it.isRecycled }
                            ?: run {
                                grayBitmap?.recycle()
                                Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888).also { grayBitmap = it }
                            }
                        Canvas(g).drawBitmap(bmp, 0f, 0f, grayPaint)
                        g
                    } else bmp

                    val image = PdfSupport.jpegImage(doc, toEncode, params.quality)
                    val pdPage = PDPage(PDRectangle(wPt, hPt))
                    doc.addPage(pdPage)
                    PDPageContentStream(doc, pdPage, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
                        cs.drawImage(image, 0f, 0f, wPt, hPt)
                    }
                }
                progress((i + 1f) / count)
            }
            out.delete()
            doc.save(out)
            return count
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            doc.close()
            renderer.close()
            pfd.close()
            bitmap?.recycle()
            grayBitmap?.recycle()
        }
    }

    /** Píxeles para renderizar a [dpi], limitados para no agotar la memoria en gama baja. */
    private fun pixelSize(wPt: Float, hPt: Float, dpi: Int): Pair<Int, Int> {
        var w = wPt / 72f * dpi
        var h = hPt / 72f * dpi
        val maxLong = if (lowRam) 2600f else 4200f
        val maxPixels = if (lowRam) 5_000_000f else 12_000_000f
        val long = max(w, h)
        if (long > maxLong) { val s = maxLong / long; w *= s; h *= s }
        if (w * h > maxPixels) { val s = sqrt(maxPixels / (w * h)); w *= s; h *= s }
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }

    private fun pageCount(file: File): Int = try {
        openPfd(file).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
    } catch (e: SecurityException) {
        throw PdfPasswordException()
    } catch (e: IOException) {
        throw rendererError(file, e)
    }

    /**
     * Muchas versiones de Android lanzan IOException ("not in PDF format or corrupted") con PDF cifrados.
     * Se confirma con pdfbox para mostrar el mensaje de contraseña en vez de "archivo dañado".
     */
    private fun rendererError(file: File, e: IOException): IOException {
        var doc: PDDocument? = null
        return try {
            doc = PDDocument.load(file, "", MemoryUsageSetting.setupTempFileOnly().setTempDir(pdfboxTempDir()))
            PdfInvalidException(cause = e)
        } catch (pw: InvalidPasswordException) {
            PdfPasswordException()
        } catch (_: Throwable) {
            PdfInvalidException(cause = e)
        } finally {
            try { doc?.close() } catch (_: Throwable) { }
        }
    }

    private fun pdfboxTempDir(): File = File(context.cacheDir, "pdfbox").apply { mkdirs() }

    // ------------------------------------------------------------------------------------------
    // Compresión estructural (conserva texto / vectores / capa OCR)
    // ------------------------------------------------------------------------------------------

    /**
     * Abre [source] con pdfbox, re-codifica solo las imágenes grandes y guarda en [out]. Devuelve el nº de páginas.
     * Cada imagen se reemplaza solo si la nueva ocupa claramente menos; las imágenes compartidas entre páginas se
     * procesan una vez. Lanza [PdfPasswordException] si el PDF necesita contraseña para abrirse.
     */
    private suspend fun recompressImages(source: File, params: Params, out: File, progress: (Float) -> Unit): Int {
        val doc = try {
            PDDocument.load(source, "", PdfSupport.tempMemorySetting(context))
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordException()
        } catch (e: IOException) {
            throw PdfInvalidException(cause = e)
        }
        try {
            // Solo restricciones de propietario (se abre sin contraseña): pdfbox exige quitarlas para guardar.
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val count = doc.numberOfPages
            if (count <= 0) throw PdfInvalidException("El PDF no tiene páginas.")
            val done = IdentityHashMap<COSBase, PDImageXObject?>()
            val visitedForms = IdentityHashMap<COSBase, Boolean>()
            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                val page = doc.getPage(i)
                val box = page.cropBox ?: page.mediaBox
                // Lado largo objetivo de una imagen que ocupase la página entera a los dpi del nivel.
                val targetLong = (max(box.width, box.height) / 72f * params.dpi).coerceAtLeast(300f)
                page.resources?.let { processResources(doc, it, targetLong, params, done, visitedForms, 0) }
                progress((i + 1f) / (count + 1f))
            }
            out.delete()
            doc.save(out)
            progress(1f)
            return count
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            doc.close()
        }
    }

    private suspend fun processResources(
        doc: PDDocument,
        res: PDResources,
        targetLong: Float,
        params: Params,
        done: IdentityHashMap<COSBase, PDImageXObject?>,
        visitedForms: IdentityHashMap<COSBase, Boolean>,
        depth: Int,
    ) {
        val names = try { res.xObjectNames.toList() } catch (_: Throwable) { return }
        for (name in names) {
            currentCoroutineContext().ensureActive()
            val xo = try { res.getXObject(name) } catch (_: Throwable) { null } ?: continue
            when (xo) {
                is PDImageXObject -> {
                    val key: COSBase = xo.cosObject
                    val replacement = if (done.containsKey(key)) done[key] else recompressImage(doc, xo, targetLong, params).also { done[key] = it }
                    if (replacement != null) res.put(name, replacement)
                }
                is PDFormXObject -> {
                    val key: COSBase = xo.cosObject
                    if (depth < MAX_FORM_DEPTH && visitedForms.put(key, true) == null) {
                        xo.resources?.let { processResources(doc, it, targetLong, params, done, visitedForms, depth + 1) }
                    }
                }
            }
        }
    }

    /** Nueva imagen más ligera, o null si conviene conservar la original (pequeña, con máscara, no decodificable...). */
    private fun recompressImage(doc: PDDocument, img: PDImageXObject, targetLong: Float, params: Params): PDImageXObject? {
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        var gray: Bitmap? = null
        try {
            val w = img.width
            val h = img.height
            if (w <= 0 || h <= 0 || w.toLong() * h < MIN_IMAGE_PIXELS) return null
            if (img.isStencil || img.bitsPerComponent == 1) return null
            // Transparencias / máscaras: re-codificar en JPEG las rompería.
            if (img.cosObject.getDictionaryObject(COSName.SMASK) != null) return null
            if (img.cosObject.getDictionaryObject(COSName.MASK) != null) return null
            val oldBytes = img.cosObject.length
            if (oldBytes <= 0) return null

            val long = max(w, h).toFloat()
            var scale = (targetLong / long).coerceAtMost(1f)
            // Presupuesto de memoria: nunca decodificar por encima de lo que el dispositivo aguanta.
            val maxPixels = if (lowRam) 5_000_000f else 12_000_000f
            if (w.toFloat() * h * scale * scale > maxPixels) scale = sqrt(maxPixels / (w.toFloat() * h))
            val tw = (w * scale).roundToInt().coerceAtLeast(1)
            val th = (h * scale).roundToInt().coerceAtLeast(1)
            var sub = 1
            while (w / (sub * 2) >= tw && h / (sub * 2) >= th) sub *= 2

            val bmp = (if (sub > 1) img.getImage(null, sub) else img.image) ?: return null
            decoded = bmp
            val sized = if (bmp.width > tw * 1.05f || bmp.height > th * 1.05f) {
                Bitmap.createScaledBitmap(bmp, tw, th, true).also { if (it !== bmp) scaled = it }
            } else bmp
            val toEncode = if (params.grayscale) {
                Bitmap.createBitmap(sized.width, sized.height, Bitmap.Config.ARGB_8888).also { g ->
                    gray = g
                    Canvas(g).drawBitmap(sized, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    })
                }
            } else sized

            val replacement = if (ImageCodec.isNearlyBinary(toEncode)) {
                PdfSupport.binaryImage(doc, toEncode)
            } else {
                PdfSupport.jpegImage(doc, toEncode, params.quality)
            }
            // Solo compensa si ahorra al menos un 10 %. (Los objetos descartados no se escriben al guardar.)
            return if (replacement.cosObject.length < oldBytes * 0.9) replacement else null
        } catch (oom: OutOfMemoryError) {
            return null
        } catch (t: Exception) {
            // Filtros no soportados en Android (JPX, JBIG2, CMYK...): se deja la imagen original.
            return null
        } finally {
            gray?.recycle()
            scaled?.recycle()
            decoded?.recycle()
        }
    }

    private fun openPfd(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    /** Copia el Uri a la caché (PdfRenderer necesita un descriptor con seek). */
    private fun copyToCache(uri: Uri): File {
        val f = tempFile("src")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("No se pudo abrir el archivo seleccionado")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        } catch (e: SecurityException) {
            f.delete()
            throw IOException("Sin permiso para leer el archivo seleccionado", e)
        } catch (t: Throwable) {
            f.delete()
            throw t
        }
        if (f.length() < 5 || !isPdf(f)) {
            f.delete()
            throw PdfInvalidException("El archivo seleccionado no es un PDF.")
        }
        return f
    }

    private fun isPdf(f: File): Boolean = try {
        f.inputStream().use { s ->
            val head = ByteArray(1024)
            val n = s.read(head)
            n > 4 && String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF")
        }
    } catch (_: Throwable) {
        false
    }

    private fun tempFile(tag: String): File {
        val dir = File(context.cacheDir, "compress").apply { mkdirs() }
        return File(dir, "${tag}_${System.nanoTime()}.pdf")
    }

    private fun copyReplacing(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        src.copyTo(tmp, overwrite = true)
        PdfSupport.moveReplacing(tmp, dst)
    }

    private companion object {
        const val MAX_ITERATIONS = 5
        const val MIN_DPI = 72f
        const val MAX_DPI = 200f
        const val MIN_Q = 35f
        const val MAX_Q = 88f
        const val MAX_FORM_DEPTH = 8
        const val MIN_IMAGE_PIXELS = 200_000L
        /** La compresión estructural se acepta directamente si deja el archivo en <= 85 % del original. */
        const val GOOD_ENOUGH_RATIO = 0.85
        /** Si no, se prefiere igualmente salvo que el rasterizado sea al menos un 30 % más pequeño. */
        const val RASTER_PREFERENCE = 1.0 / 0.7
    }
}
