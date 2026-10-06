package com.scannerpromax.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.export.ImageCodec
import com.scannerpromax.imaging.ProgressCallback
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Una página a exportar: imagen procesada en disco y (opcional) su OCR para la capa de texto invisible. */
data class PdfPageInput(val imageFile: File, val ocr: OcrResult?)

/**
 * Exportador de PDF con pdfbox-android.
 *  - Página a página (memoria acotada; búfer de pdfbox en archivo temporal).
 *  - Imágenes casi binarias (filtros B/N, ahorro de tinta) -> 1 bit + Flate: nítidas y diminutas.
 *  - El resto -> JPEG con la calidad elegida; si el procesado ya es un JPEG de calidad suficiente y no
 *    hay que reducirlo, se incrusta tal cual (sin pérdida extra ni recodificación).
 *  - Capa de texto OCR invisible (PDF con búsqueda / copiar texto).
 */
class PdfExporter(private val context: Context) {

    /** Escribe el PDF en [output] (archivo). Devuelve el archivo. */
    suspend fun export(pages: List<PdfPageInput>, options: PdfOptions, output: File, progress: ProgressCallback? = null): File =
        withContext(Dispatchers.IO) {
            require(pages.isNotEmpty()) { "No hay páginas para exportar" }
            output.parentFile?.mkdirs()
            val tmp = File(output.parentFile, output.name + ".part")
            val doc = PDDocument(PdfSupport.tempMemorySetting(context))
            try {
                PdfSupport.applyMetadata(doc, output.nameWithoutExtension)
                val font: PDFont = PDType1Font.HELVETICA
                val sanitizer = TextSanitizer(font)
                pages.forEachIndexed { index, input ->
                    currentCoroutineContext().ensureActive()
                    addPage(doc, input, options, font, sanitizer)
                    progress?.invoke((index + 1f) / (pages.size + 1f))
                }
                options.password?.takeIf { it.isNotEmpty() }?.let { PdfSupport.protect(doc, it) }
                tmp.delete()
                doc.save(tmp)
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            } finally {
                doc.close()
            }
            PdfSupport.moveReplacing(tmp, output)
            progress?.invoke(1f)
            output
        }

    // -----------------------------------------------------------------------------------------

    private fun addPage(doc: PDDocument, input: PdfPageInput, options: PdfOptions, font: PDFont, sanitizer: TextSanitizer) {
        val file = input.imageFile
        if (!file.exists()) throw IOException("Falta la imagen de una página")
        val (srcW, srcH) = ImageCodec.bounds(file)
        if (srcW <= 0 || srcH <= 0) throw IOException("Imagen de página ilegible")

        val image = createImage(doc, file, srcW, srcH, options)
        // Las dimensiones en puntos se calculan sobre el aspecto de la imagen original.
        val (mediaBox, placement) = layout(srcW.toFloat(), srcH.toFloat(), options)
        val page = PDPage(mediaBox)
        doc.addPage(page)

        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
            cs.drawImage(image, placement.left, placement.top, placement.width(), placement.height())
            val ocr = input.ocr
            if (options.searchable && ocr != null && ocr.blocks.isNotEmpty()) {
                writeTextLayer(cs, ocr, placement, font, sanitizer)
            }
        }
    }

    /** Crea el XObject de imagen con la codificación más eficiente para su contenido. */
    private fun createImage(doc: PDDocument, file: File, srcW: Int, srcH: Int, options: PdfOptions): PDImageXObject {
        val maxLong = options.quality.maxLongSide
        val needsResize = max(srcW, srcH) > maxLong

        if (ImageCodec.isNearlyBinary(file)) {
            val bmp = ImageCodec.decodeScaled(file, maxLong)
            try {
                return PdfSupport.binaryImage(doc, bmp)
            } finally {
                bmp.recycle()
            }
        }

        // Incrustación directa del JPEG procesado (guardado a calidad 92) cuando no hace falta tocarlo.
        if (!needsResize && options.quality.jpegQuality >= 90 && ImageCodec.isJpeg(file)) {
            return JPEGFactory.createFromByteArray(doc, file.readBytes())
        }

        val bmp = ImageCodec.decodeScaled(file, maxLong)
        try {
            return PdfSupport.jpegImage(doc, bmp, options.quality.jpegQuality)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Calcula el tamaño de página (puntos) y el rectángulo donde va la imagen (origen PDF abajo-izquierda;
     * en el RectF devuelto top = y inferior para simplificar).
     */
    private fun layout(imgW: Float, imgH: Float, options: PdfOptions): Pair<PDRectangle, RectF> {
        val margin = options.marginPt.coerceAtLeast(0f)
        val aspect = imgW / imgH
        val landscape = imgW > imgH

        val (pw, ph) = when (options.pageSize) {
            PageSize.AUTO -> autoSize(aspect).let { (w, h) -> (w + 2 * margin) to (h + 2 * margin) }
            else -> {
                val a = options.pageSize.widthPt
                val b = options.pageSize.heightPt
                if (landscape) b to a else a to b
            }
        }
        val availW = (pw - 2 * margin).coerceAtLeast(1f)
        val availH = (ph - 2 * margin).coerceAtLeast(1f)
        val scale = min(availW / imgW, availH / imgH)
        val dw = imgW * scale
        val dh = imgH * scale
        val x = (pw - dw) / 2f
        val y = (ph - dh) / 2f
        return PDRectangle(pw, ph) to RectF(x, y, x + dw, y + dh)
    }

    /** AUTO: si la proporción coincide con A4/Carta/Oficio se usa ese tamaño; si no, lado largo = A4. */
    private fun autoSize(aspect: Float): Pair<Float, Float> {
        val portrait = aspect <= 1f
        val a = if (portrait) aspect else 1f / aspect
        val standard = listOf(PageSize.A4, PageSize.LETTER, PageSize.LEGAL)
            .firstOrNull { abs(it.widthPt / it.heightPt - a) / a < 0.03f }
        val (w, h) = if (standard != null) {
            standard.widthPt to standard.heightPt
        } else {
            val long = PageSize.A4.heightPt
            (long * a) to long
        }
        return if (portrait) w to h else h to w
    }

    /** Capa de texto invisible: cada línea OCR se ajusta (tamaño + escala horizontal) a su caja. */
    private fun writeTextLayer(cs: PDPageContentStream, ocr: OcrResult, place: RectF, font: PDFont, sanitizer: TextSanitizer) {
        if (ocr.imageWidth <= 0 || ocr.imageHeight <= 0) return
        val sx = place.width() / ocr.imageWidth
        val sy = place.height() / ocr.imageHeight
        val bottomY = place.top + place.height() // borde superior de la imagen en coords PDF
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (block in ocr.blocks) {
            for (line in block.lines) {
                val text = sanitizer.clean(line.text)
                if (text.isBlank()) continue
                val bw = (line.box.right - line.box.left) * sx
                val bh = (line.box.bottom - line.box.top) * sy
                if (bw < 1f || bh < 1f) continue
                val fontSize = bh * 0.92f
                val textWidth = try {
                    font.getStringWidth(text) / 1000f * fontSize
                } catch (_: Exception) {
                    continue
                }
                if (textWidth <= 0f) continue
                val hScale = (bw / textWidth * 100f).coerceIn(5f, 1000f)
                val x = place.left + line.box.left * sx
                val baseline = bottomY - line.box.bottom * sy + bh * 0.2f
                try {
                    cs.setFont(font, fontSize)
                    cs.setHorizontalScaling(hScale)
                    cs.setTextMatrix(Matrix.getTranslateInstance(x, baseline))
                    cs.showText(text)
                } catch (_: IllegalArgumentException) {
                    // Carácter no codificable que escapó al filtro: se omite la línea.
                }
            }
        }
        cs.endText()
    }
}

/** Deja solo caracteres codificables por la fuente (Helvetica/WinAnsi cubre todo el español). */
internal class TextSanitizer(private val font: PDFont) {
    private val cache = HashMap<Char, Char?>()

    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val mapped = cache.getOrPut(c) { mapChar(c) }
            if (mapped != null) sb.append(mapped)
        }
        return sb.toString().trim()
    }

    private fun mapChar(c: Char): Char? {
        if (c == '\t' || c == '\n' || c == '\r') return ' '
        if (c.isISOControl()) return null
        if (encodable(c)) return c
        // Quita diacríticos raros (ej. "ș" -> "s") antes de rendirse.
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            .firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        if (base != null && base != c && encodable(base)) return base
        return when (c) {
            '‘', '’', '‚' -> '\''
            '“', '”', '„' -> '"'
            '–', '—', '−' -> '-'
            else -> null
        }
    }

    private fun encodable(c: Char): Boolean = try {
        font.encode(c.toString()).isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

/** Utilidades compartidas por el exportador y el compresor. */
internal object PdfSupport {
    const val PRODUCER = "ESCÁNER PRO MAX"

    fun tempMemorySetting(context: Context): com.tom_roush.pdfbox.io.MemoryUsageSetting {
        val dir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        return com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().setTempDir(dir)
    }

    fun applyMetadata(doc: PDDocument, title: String?) {
        val info = doc.documentInformation
        val now = java.util.Calendar.getInstance()
        title?.let { info.setTitle(it) }
        info.setProducer(PRODUCER)
        info.setCreator(PRODUCER)
        info.setCreationDate(now)
        info.setModificationDate(now)
    }

    /** Cifrado AES-128 con la contraseña dada (usuario y propietario). */
    fun protect(doc: PDDocument, password: String) {
        val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
            password, password, com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission(),
        )
        policy.setEncryptionKeyLength(128)
        policy.setPreferAES(true)
        doc.protect(policy)
    }

    fun jpegImage(doc: PDDocument, bmp: Bitmap, quality: Int): PDImageXObject {
        val flat = ImageCodec.flattenOnWhite(bmp)
        try {
            val bos = ByteArrayOutputStream(flat.width * flat.height / 6)
            flat.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)
            return JPEGFactory.createFromByteArray(doc, bos.toByteArray())
        } finally {
            if (flat !== bmp) flat.recycle()
        }
    }

    /** Imagen de 1 bit comprimida con Flate (escaneos B/N: muy nítida y pequeña). */
    fun binaryImage(doc: PDDocument, bmp: Bitmap): PDImageXObject {
        val bos = ByteArrayOutputStream(bmp.width * bmp.height / 64)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            DeflaterOutputStream(bos, deflater, 64 * 1024).use { ImageCodec.packBits(bmp, it) }
        } finally {
            deflater.end()
        }
        return PDImageXObject(
            doc, ByteArrayInputStream(bos.toByteArray()), COSName.FLATE_DECODE,
            bmp.width, bmp.height, 1, PDDeviceGray.INSTANCE,
        )
    }

    fun moveReplacing(src: File, dst: File) {
        if (src.renameTo(dst)) return
        dst.delete()
        if (src.renameTo(dst)) return
        src.copyTo(dst, overwrite = true)
        src.delete()
    }
}
