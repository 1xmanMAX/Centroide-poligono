package com.scannerpromax.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.export.ImageCodec
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF RECONSTRUIDO: a partir del [DocLayout] de la IA y de la imagen procesada genera una página VECTORIAL con la
 * misma disposición (texto real seleccionable y buscable, tablas como rejilla real, gráficos/figuras/fórmulas como
 * recorte de la imagen). La maquetación es pura ([ReconstructionLayout]); el dibujo, [ReconstructionRenderer].
 *
 * Fuentes (incrustadas como subconjunto): Liberation Sans (la trae pdfbox-android), "EPM Sans Bold" (subconjunto
 * latino de Liberation Sans Bold) y "EPM Hand" (subconjunto latino de Patrick Hand) en assets/fonts, licencia OFL.
 */
class PdfReconstructor(private val context: Context) {

    /** Fuentes cargadas en [doc] (una vez por documento). */
    fun loadFonts(doc: PDDocument): PdfFontMetrics {
        val regular = loadAsset(doc, LIBERATION_SANS) ?: PDType1Font.HELVETICA
        val bold = loadAsset(doc, BOLD_FONT) ?: regular
        val hand = loadAsset(doc, HAND_FONT) ?: regular
        return PdfFontMetrics(mapOf(ReFont.REGULAR to regular, ReFont.BOLD to bold, ReFont.HAND to hand))
    }

    private fun loadAsset(doc: PDDocument, path: String): PDFont? = try {
        context.assets.open(path).use { PDType0Font.load(doc, it, true) }
    } catch (t: Throwable) {
        Log.w(TAG, "No se pudo cargar la fuente $path", t)
        null
    }

    /**
     * Añade a [doc] la página reconstruida de [layout] (recortes de [imageFile] con la calidad de [options]).
     * Devuelve el tamaño de la página.
     */
    fun addPage(
        doc: PDDocument,
        metrics: PdfFontMetrics,
        layout: DocLayout,
        imageFile: File,
        options: PdfOptions,
        binaryHint: Boolean?,
    ): PDRectangle {
        val plan = ReconstructionLayout.plan(layout, options.pageSize, options.marginPt, metrics)
        val crops = plan.ops.filterIsInstance<ReOp.Image>()
        var bmp: Bitmap? = null
        try {
            if (crops.isNotEmpty() && imageFile.exists()) {
                val maxSide = if (options.quality.isFullResolution) Int.MAX_VALUE else options.quality.maxLongSide
                bmp = runCatching { ImageCodec.decodeScaled(imageFile, maxSide) }
                    .onFailure { Log.w(TAG, "No se pudo leer la imagen para los recortes", it) }
                    .getOrNull()
            }
            val source = bmp
            val k = if (source != null) source.width.toFloat() / layout.imageWidth else 1f
            val binary = binaryHint ?: false
            // Recortes SOLO-TINTA (dibujos sueltos): papel y longitud de raya de la PÁGINA, no del recorte.
            val ink = if (source != null && crops.any { it.inkOnly }) inkParams(source, layout, k) else null
            val page = ReconstructionRenderer.render(doc, plan, metrics) { op ->
                source?.let { cropImage(doc, it, op, k, binary, options.quality, ink) }
            }
            return page.mediaBox
        } finally {
            bmp?.recycle()
        }
    }

    /** (nivel del papel, longitud mínima de raya en px de [src]) para los recortes solo-tinta. */
    private fun inkParams(src: Bitmap, layout: DocLayout, k: Float): Pair<Double, Int> {
        val s = minOf(1f, 400f / maxOf(src.width, src.height))
        val small = if (s < 1f) Bitmap.createScaledBitmap(src, maxOf(1, (src.width * s).roundToInt()), maxOf(1, (src.height * s).roundToInt()), true) else src
        try {
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            val paper = com.scannerpromax.ai.parts.PageInk.percentile(IntArray(px.size) { com.scannerpromax.ai.parts.Rgb.lum(px[it]) }, 90.0)
            return paper to (maxOf(25f, 0.12f * minOf(layout.imageWidth, layout.imageHeight)) * k).roundToInt().coerceAtLeast(8)
        } finally {
            if (small !== src) small.recycle()
        }
    }

    private fun cropImage(
        doc: PDDocument, src: Bitmap, op: ReOp.Image, k: Float, binary: Boolean, quality: ExportQuality,
        ink: Pair<Double, Int>? = null,
    ): PDImageXObject? {
        val l = (op.src.left * k).roundToInt().coerceIn(0, src.width - 1)
        val t = (op.src.top * k).roundToInt().coerceIn(0, src.height - 1)
        val r = (op.src.right * k).roundToInt().coerceIn(l + 1, src.width)
        val b = (op.src.bottom * k).roundToInt().coerceIn(t + 1, src.height)
        val crop0 = Bitmap.createBitmap(src, l, t, r - l, b - t)
        val crop = if (op.inkOnly && ink != null) {
            val w = crop0.width; val h = crop0.height
            val px = IntArray(w * h)
            crop0.getPixels(px, 0, w, 0, 0, w, h)
            com.scannerpromax.ai.parts.PageInk.inkOnly(px, w, h, ink.second, ink.first)
            if (crop0 !== src) crop0.recycle()
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } else crop0
        return try {
            if (binary) PdfSupport.binaryImage(doc, crop) else PdfSupport.jpegImage(doc, crop, quality.jpegQuality)
        } catch (e: Exception) {
            Log.w(TAG, "Recorte no incrustado", e)
            null
        } finally {
            if (crop !== src) crop.recycle()
        }
    }

    /**
     * Vista previa: reconstruye la página en un PDF temporal y lo rasteriza con [PdfRenderer] en [out] (PNG), con el
     * lado largo ≤ [maxLongSide] px. Devuelve [out].
     */
    suspend fun renderPreview(
        layout: DocLayout,
        imageFile: File,
        binaryHint: Boolean?,
        out: File,
        maxLongSide: Int = 2400,
    ): File = withContext(Dispatchers.IO) {
        out.parentFile?.mkdirs()
        val pdf = File(out.parentFile, out.name + ".pdf")
        val doc = PDDocument(PdfSupport.tempMemorySetting(context))
        try {
            val metrics = loadFonts(doc)
            addPage(doc, metrics, layout, imageFile, PdfOptions(pageSize = PageSize.AUTO, quality = ExportQuality.BALANCED), binaryHint)
            doc.save(pdf)
        } finally {
            doc.close()
        }
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val scale = min(maxLongSide.toFloat() / max(page.width, page.height), 4f)
                        val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        try {
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val tmp = File(out.parentFile, out.name + ".part")
                            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            PdfSupport.moveReplacing(tmp, out)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw IOException("No se pudo mostrar la página reconstruida", e)
        } finally {
            pdf.delete()
        }
        out
    }

    companion object {
        private const val TAG = "PdfReconstructor"
        const val LIBERATION_SANS = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
        const val BOLD_FONT = "fonts/EpmSans-Bold.ttf"
        const val HAND_FONT = "fonts/EpmHand-Regular.ttf"
    }
}
