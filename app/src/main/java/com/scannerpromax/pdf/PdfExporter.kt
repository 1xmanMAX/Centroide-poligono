package com.scannerpromax.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.domain.PdfTextMode
import com.scannerpromax.domain.TextPlacement
import com.scannerpromax.export.ImageCodec
import com.scannerpromax.imaging.ProgressCallback
import com.scannerpromax.ocr.OcrText
import com.scannerpromax.ocr.TextAligner
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
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

/**
 * Una página a exportar: imagen procesada en disco y (opcional) su OCR para la capa de texto invisible y las páginas
 * de texto reconocido. Si [OcrResult.editedText] está presente se usa el texto corregido por el usuario.
 */
data class PdfPageInput(
    val imageFile: File,
    val ocr: OcrResult?,
    /**
     * true = la página es binaria (filtros B/N, Ahorro tinta): se incrusta a 1 bit sin analizarla.
     * false = tiene grises/color: nunca se binariza (firmas a lápiz, sellos tenues, logos grises).
     * null = desconocido (PDF externos, compresor): se decide con [ImageCodec.isNearlyBinary].
     */
    val binaryHint: Boolean? = null,
)

/** [PdfPageInput.binaryHint] según el filtro de la página: B/N y Ahorro tinta son binarios; el resto no. */
fun binaryHintFor(filter: FilterType): Boolean = filter == FilterType.BLACK_WHITE || filter == FilterType.ECO_INK

/**
 * Exportador de PDF con pdfbox-android.
 *  - Página a página (memoria acotada; búfer de pdfbox en archivo temporal).
 *  - Imágenes casi binarias (filtros B/N, ahorro de tinta) -> 1 bit + Flate: nítidas y diminutas.
 *  - El resto -> JPEG con la calidad elegida; si el procesado ya es un JPEG de calidad suficiente y no
 *    hay que reducirlo, se incrusta tal cual (sin pérdida extra ni recodificación).
 *  - Modos de texto ([PdfTextMode]):
 *     · BUSCABLE: capa invisible palabra a palabra, con su ancho exacto y la inclinación de la línea, de modo que
 *       buscar/seleccionar/copiar en cualquier visor resalta justo encima del texto impreso.
 *     · BUSCABLE_CON_TEXTO: además, páginas "Texto reconocido – Página N" legibles (tras cada página o al final).
 *     · SOLO_TEXTO: solo las páginas de texto (muy liviano).
 *  - Fuente Liberation Sans (incluida en pdfbox-android) incrustada como subconjunto: soporte completo de
 *    español (á é í ó ú ñ ü ¿ ¡ €) tanto en la capa invisible como en el texto visible.
 */
class PdfExporter(private val context: Context) {

    /** Escribe el PDF en [output] (archivo). Devuelve el archivo. */
    suspend fun export(pages: List<PdfPageInput>, options: PdfOptions, output: File, progress: ProgressCallback? = null): File =
        withContext(Dispatchers.IO) {
            require(pages.isNotEmpty()) { "No hay páginas para exportar" }
            output.parentFile?.mkdirs()
            val tmp = File(output.parentFile, output.name + ".part")
            val doc = PDDocument(PdfSupport.tempMemorySetting(context))
            val mode = options.effectiveTextMode
            try {
                PdfSupport.applyMetadata(doc, output.nameWithoutExtension)
                val fonts = Fonts(doc)
                val deferredText = ArrayList<Pair<Int, OcrResult?>>()
                pages.forEachIndexed { index, input ->
                    currentCoroutineContext().ensureActive()
                    val pageNo = index + 1
                    when (mode) {
                        PdfTextMode.SOLO_TEXTO -> addTextPages(doc, titleFor(pageNo, pages.size), input.ocr, textPageSize(options, null), fonts)
                        else -> {
                            val box = addImagePage(doc, input, options, mode, fonts)
                            if (mode == PdfTextMode.BUSCABLE_CON_TEXTO) {
                                if (options.textPlacement == TextPlacement.AFTER_EACH_PAGE) {
                                    addTextPages(doc, titleFor(pageNo, pages.size), input.ocr, textPageSize(options, box), fonts)
                                } else {
                                    deferredText += pageNo to input.ocr
                                }
                            }
                        }
                    }
                    progress?.invoke((index + 1f) / (pages.size + 1f))
                }
                for ((pageNo, ocr) in deferredText) {
                    currentCoroutineContext().ensureActive()
                    addTextPages(doc, titleFor(pageNo, pages.size), ocr, textPageSize(options, null), fonts)
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

    /** Fuentes del documento (se cargan solo si hacen falta). */
    private inner class Fonts(private val doc: PDDocument) {
        private var loaded: PDFont? = null
        private var sanitizerCache: TextSanitizer? = null
        var ascent = 0.905f
            private set
        var descent = 0.212f
            private set

        val font: PDFont
            get() = loaded ?: loadUnicode().also { f ->
                loaded = f
                f.fontDescriptor?.let { d ->
                    if (d.ascent > 0f) ascent = d.ascent / 1000f
                    if (d.descent < 0f) descent = -d.descent / 1000f
                }
            }

        val sanitizer: TextSanitizer
            get() = sanitizerCache ?: TextSanitizer(font).also { sanitizerCache = it }

        private fun loadUnicode(): PDFont = try {
            context.assets.open(LIBERATION_SANS).use { PDType0Font.load(doc, it, true) }
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo cargar Liberation Sans; se usa Helvetica", t)
            PDType1Font.HELVETICA
        }

        fun widthEm(text: String): Float = try {
            font.getStringWidth(text) / 1000f
        } catch (_: Exception) {
            0f
        }
    }

    private fun titleFor(pageNo: Int, total: Int) =
        if (total == 1) "Texto reconocido" else "Texto reconocido – Página $pageNo"

    /**
     * Tamaño de las páginas de texto: el de la página de imagen (vertical) solo si es una hoja estándar; si es
     * estrecha o con proporción rara (recibos, recortes) se usa el tamaño elegido o A4, para que el texto no quede
     * partido casi palabra a palabra.
     */
    private fun textPageSize(options: PdfOptions, imageBox: PDRectangle?): PDRectangle {
        if (imageBox != null) {
            val w = min(imageBox.width, imageBox.height)
            val h = max(imageBox.width, imageBox.height)
            val aspect = w / h
            val standard = listOf(PageSize.A4, PageSize.LETTER, PageSize.LEGAL)
                .any { abs(it.widthPt / it.heightPt - aspect) / aspect < 0.08f }
            if (w >= MIN_TEXT_PAGE_WIDTH && standard) return PDRectangle(w, h)
        }
        val size = if (options.pageSize == PageSize.AUTO) PageSize.A4 else options.pageSize
        return PDRectangle(size.widthPt, size.heightPt)
    }

    /** Página de imagen (+ capa invisible si el modo lo pide). Devuelve su tamaño. */
    private fun addImagePage(doc: PDDocument, input: PdfPageInput, options: PdfOptions, mode: PdfTextMode, fonts: Fonts): PDRectangle {
        val file = input.imageFile
        if (!file.exists()) throw IOException("Falta la imagen de una página")
        val (srcW, srcH) = ImageCodec.bounds(file)
        if (srcW <= 0 || srcH <= 0) throw IOException("Imagen de página ilegible")

        val image = createImage(doc, file, srcW, srcH, options, input.binaryHint)
        // Las dimensiones en puntos se calculan sobre el aspecto de la imagen original.
        val (mediaBox, placement) = layout(srcW.toFloat(), srcH.toFloat(), options)
        val page = PDPage(mediaBox)
        doc.addPage(page)

        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
            cs.drawImage(image, placement.left, placement.top, placement.width(), placement.height())
            val ocr = input.ocr
            val wantsLayer = mode == PdfTextMode.BUSCABLE || mode == PdfTextMode.BUSCABLE_CON_TEXTO
            if (wantsLayer && ocr != null && ocr.blocks.isNotEmpty() && sameAspect(ocr, srcW, srcH)) {
                val map = ImageToPage(
                    x0 = placement.left,
                    yTop = placement.top + placement.height(),
                    sx = placement.width() / ocr.imageWidth,
                    sy = placement.height() / ocr.imageHeight,
                )
                writeTextLayer(cs, ocr, map, fonts)
            }
        }
        return mediaBox
    }

    /** El OCR debe corresponder a esta imagen (mismo aspecto; si no, es de una versión anterior y se omite). */
    private fun sameAspect(ocr: OcrResult, w: Int, h: Int): Boolean {
        if (ocr.imageWidth <= 0 || ocr.imageHeight <= 0) return false
        val a = w.toFloat() / h
        val b = ocr.imageWidth.toFloat() / ocr.imageHeight
        return abs(a - b) / a < 0.03f
    }

    /** Crea el XObject de imagen con la codificación más eficiente para su contenido. */
    private fun createImage(
        doc: PDDocument,
        file: File,
        srcW: Int,
        srcH: Int,
        options: PdfOptions,
        binaryHint: Boolean?,
    ): PDImageXObject {
        val maxLong = options.quality.maxLongSide
        val needsResize = max(srcW, srcH) > maxLong

        // El filtro conocido evita una decodificación extra + recorrido de píxeles por página (y falsos positivos).
        val binary = binaryHint ?: ImageCodec.isNearlyBinary(file)
        if (binary) {
            // A 1 bit reducir apenas ahorra espacio pero deja el texto dentado: se conserva hasta ~300 ppp en A4.
            val binaryLong = min(max(srcW, srcH), max(maxLong, BINARY_MIN_LONG_SIDE))
            val bmp = ImageCodec.decodeScaled(file, binaryLong)
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

    /**
     * Capa de texto invisible de precisión.
     *  - Nivel de palabra cuando el OCR trae palabras: cada una en el centro de su caja, girada como su línea y con
     *    su ancho exacto (escala horizontal); un espacio tras cada palabra para que copiar/extraer separe bien.
     *  - Texto corregido por el usuario: alineado con las líneas detectadas ([TextAligner]); si una línea conserva
     *    el número de palabras se reutilizan sus cajas, si no se ajusta la línea completa a su caja.
     */
    private fun writeTextLayer(cs: PDPageContentStream, ocr: OcrResult, map: ImageToPage, fonts: Fonts) {
        val lines: List<OcrLine> = ocr.blocks.flatMap { it.lines }
        if (lines.isEmpty()) return
        val original = lines.map { wordsOf(it) }
        val corrected = ocr.editedText?.let { TextAligner.align(original, it) }
        val font = fonts.font
        val sanitizer = fonts.sanitizer
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        var currentSize = -1f
        var currentScale = -1f

        fun show(text: String, trailingSpace: Boolean, box: com.scannerpromax.domain.OcrRect, angle: Float, lineH: Float) {
            val clean = sanitizer.clean(text)
            if (clean.isEmpty()) return
            val run = TextLayerGeometry.place(box, angle, lineH, fonts.widthEm(clean), map, fonts.ascent, fonts.descent) ?: return
            try {
                if (run.fontSize != currentSize) { cs.setFont(font, run.fontSize); currentSize = run.fontSize }
                if (run.hScale != currentScale) { cs.setHorizontalScaling(run.hScale); currentScale = run.hScale }
                cs.setTextMatrix(Matrix(run.cos, run.sin, -run.sin, run.cos, run.x, run.y))
                cs.showText(if (trailingSpace) "$clean " else clean)
            } catch (_: IllegalArgumentException) {
                // Carácter no codificable que escapó al filtro: se omite.
            }
        }

        lines.forEachIndexed { i, line ->
            val words = corrected?.get(i) ?: original[i]
            if (words.isEmpty()) return@forEachIndexed
            val lineH = TextLayerGeometry.lineHeight(line.box, line.angle)
            if (line.words.isNotEmpty() && words.size == line.words.size) {
                line.words.forEachIndexed { k, w ->
                    show(words[k], trailingSpace = true, box = w.box, angle = line.angle, lineH = lineH)
                }
            } else {
                show(words.joinToString(" "), trailingSpace = true, box = line.box, angle = line.angle, lineH = lineH)
            }
        }
        cs.endText()
    }

    private fun wordsOf(line: OcrLine): List<String> =
        if (line.words.isNotEmpty()) line.words.map { it.text } else TextAligner.tokenize(line.text)

    /** Páginas visibles "Texto reconocido": título, párrafos y salto de página automático. */
    private fun addTextPages(doc: PDDocument, title: String, ocr: OcrResult?, size: PDRectangle, fonts: Fonts) {
        val font = fonts.font
        val sanitizer = fonts.sanitizer
        val paragraphs = OcrText.paragraphs(ocr).map { sanitizer.clean(it) }.filter { it.isNotBlank() }
        val style = TextPageLayout.Style(pageWidth = size.width, pageHeight = size.height)
        val pagesLayout = TextPageLayout.layout(
            title = sanitizer.clean(title),
            paragraphs = paragraphs,
            style = style,
            emptyNote = sanitizer.clean("(No se reconoció texto en esta página)"),
        ) { text, fs -> fonts.widthEm(text) * fs }
        for (lines in pagesLayout) {
            val page = PDPage(PDRectangle(size.width, size.height))
            doc.addPage(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
                // Filete bajo el título.
                lines.lastOrNull { it.kind == TextPageLayout.Kind.TITLE }?.let { t ->
                    cs.setStrokingColor(0.42f, 0.36f, 0.95f)
                    cs.setLineWidth(1.2f)
                    val y = t.y - t.size * 0.55f
                    cs.moveTo(style.margin, y)
                    cs.lineTo(size.width - style.margin, y)
                    cs.stroke()
                }
                cs.beginText()
                for (l in lines) {
                    when (l.kind) {
                        TextPageLayout.Kind.TITLE -> cs.setNonStrokingColor(0.17f, 0.14f, 0.45f)
                        TextPageLayout.Kind.BODY -> cs.setNonStrokingColor(0.08f, 0.08f, 0.1f)
                        TextPageLayout.Kind.NOTE -> cs.setNonStrokingColor(0.45f, 0.45f, 0.5f)
                    }
                    try {
                        cs.setFont(font, l.size)
                        cs.setTextMatrix(Matrix.getTranslateInstance(l.x, l.y))
                        cs.showText(l.text)
                    } catch (_: IllegalArgumentException) {
                    }
                }
                // Pie discreto.
                cs.setNonStrokingColor(0.55f, 0.55f, 0.6f)
                try {
                    cs.setFont(font, 7.5f)
                    cs.setTextMatrix(Matrix.getTranslateInstance(style.margin, style.margin / 2f))
                    cs.showText(sanitizer.clean("Texto reconocido automáticamente (OCR) · ${PdfSupport.PRODUCER}"))
                } catch (_: IllegalArgumentException) {
                }
                cs.endText()
            }
        }
    }

    private companion object {
        const val TAG = "PdfExporter"
        const val LIBERATION_SANS = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
        /** Ancho mínimo (pt) para reutilizar el tamaño de la imagen en las páginas de texto. */
        const val MIN_TEXT_PAGE_WIDTH = 420f
        /** Lado largo mínimo de las imágenes de 1 bit (A4 a 300 ppp). */
        const val BINARY_MIN_LONG_SIDE = 3508
    }
}

/** Deja solo caracteres codificables por la fuente (Liberation Sans cubre latín ampliado, griego y cirílico). */
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
