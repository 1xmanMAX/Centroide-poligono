package com.scannerpromax.album

import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Exporta un álbum a PDF con pdfbox (sin android.graphics: se prueba en la JVM).
 *  - Fondo PDF: la página ORIGINAL como Form XObject (vectorial, texto seleccionable) vía [LayerUtility].
 *  - Fondo imagen y cromos: los JPEG guardados se incrustan TAL CUAL (DCTDecode, sin recomprimir).
 *  - Cada cromo se recorta con el contorno de su recuadro (girado si hace falta).
 * Los recuadros vacíos no se dibujan.
 */
object AlbumPdfWriter {

    /** pt por mm. */
    const val PT_PER_MM = 72.0 / 25.4

    fun write(pages: List<AlbumPage>, dir: File, out: OutputStream, title: String? = null, progress: (Float) -> Unit = {}) {
        if (pages.isEmpty()) throw IOException("El álbum no tiene hojas")
        val doc = PDDocument()
        val sources = ArrayList<PDDocument>()
        try {
            title?.let { doc.documentInformation.title = it }
            doc.documentInformation.creator = "ESCÁNER PRO MAX"
            val layer = LayerUtility(doc)
            pages.forEachIndexed { i, page ->
                addPage(doc, layer, page, dir, sources)
                progress((i + 1f) / pages.size)
            }
            doc.save(out)
        } finally {
            doc.close()
            sources.forEach { runCatching { it.close() } }
        }
    }

    private fun addPage(doc: PDDocument, layer: LayerUtility, page: AlbumPage, dir: File, sources: MutableList<PDDocument>) {
        val wMm = page.widthMm.toDouble(); val hMm = page.heightMm.toDouble()
        val pdPage = PDPage(PDRectangle((wMm * PT_PER_MM).toFloat(), (hMm * PT_PER_MM).toFloat()))
        doc.addPage(pdPage)
        // mm (y hacia abajo) -> pt de PDF (y hacia arriba)
        val flip = Affine(a = PT_PER_MM, d = -PT_PER_MM, f = hMm * PT_PER_MM)
        PDPageContentStream(doc, pdPage).use { cs ->
            val bg = page.background
            when (bg.kind) {
                BackgroundKind.BLANK -> Unit
                BackgroundKind.IMAGE -> bg.file?.let { File(dir, it) }?.takeIf { it.isFile }?.let { f ->
                    val img = jpegImage(doc, f)
                    val m = unitToPixels(img.width.toDouble(), img.height.toDouble())
                        .then(Affine().scale(bg.srcWidth / img.width.toDouble(), bg.srcHeight / img.height.toDouble()))
                        .then(AlbumGeometry.backgroundToSheet(bg.srcWidth.toDouble(), bg.srcHeight.toDouble(), page.rotation, wMm, hMm))
                        .then(flip)
                    cs.drawImage(img, matrix(m))
                }
                BackgroundKind.PDF_PAGE -> bg.file?.let { File(dir, it) }?.takeIf { it.isFile }?.let { f ->
                    val src = PDDocument.load(f).also { sources += it }
                    val srcPage = src.getPage(0)
                    val form = layer.importPageAsForm(src, srcPage)
                    // la transformación la ponemos nosotros (giro propio de la página + giro de la hoja + escala)
                    form.setMatrix(AffineTransform())
                    val crop = srcPage.cropBox
                    form.bBox = crop
                    val m = pdfPageToDisplayed(crop, srcPage.rotation)
                        .then(AlbumGeometry.backgroundToSheet(displayedW(crop, srcPage.rotation), displayedH(crop, srcPage.rotation), page.rotation, wMm, hMm))
                        .then(flip)
                    cs.saveGraphicsState()
                    cs.transform(matrix(m))
                    cs.drawForm(form)
                    cs.restoreGraphicsState()
                }
            }
            for (slot in page.slots) {
                val st = slot.sticker ?: continue
                val f = File(dir, st.rendered)
                if (!f.isFile) continue
                val img = jpegImage(doc, f)
                val box = AlbumGeometry.box(slot, wMm, hMm)
                cs.saveGraphicsState()
                val c = box.corners().map { flip.map(it) }
                cs.moveTo(c[0].x.toFloat(), c[0].y.toFloat())
                for (k in 1..3) cs.lineTo(c[k].x.toFloat(), c[k].y.toFloat())
                cs.closePath()
                cs.clip()
                val m = unitToPixels(img.width.toDouble(), img.height.toDouble())
                    .then(AlbumGeometry.stickerToSheet(img.width.toDouble(), img.height.toDouble(), st.rotation, st.fit, box))
                    .then(flip)
                cs.drawImage(img, matrix(m))
                cs.restoreGraphicsState()
            }
        }
    }

    /** Cuadrado unidad de la imagen en PDF (origen abajo a la izquierda) -> píxeles con la y hacia abajo. */
    private fun unitToPixels(w: Double, h: Double) = Affine(a = w, d = -h, f = h)

    /** Espacio de usuario de la página PDF -> página como se ve (pt, y hacia abajo), con su /Rotate. */
    internal fun pdfPageToDisplayed(crop: PDRectangle, rotate: Int): Affine {
        val cw = crop.width.toDouble(); val ch = crop.height.toDouble()
        val r = ((rotate % 360) + 360) % 360
        val dw = if (r % 180 == 0) cw else ch
        val dh = if (r % 180 == 0) ch else cw
        return Affine(a = 1.0, d = -1.0, e = -crop.lowerLeftX.toDouble(), f = crop.upperRightY.toDouble())
            .translate(-cw / 2, -ch / 2).rotate(r.toDouble()).translate(dw / 2, dh / 2)
    }

    internal fun displayedW(crop: PDRectangle, rotate: Int): Double =
        if (((rotate % 360) + 360) % 180 == 0) crop.width.toDouble() else crop.height.toDouble()

    internal fun displayedH(crop: PDRectangle, rotate: Int): Double =
        if (((rotate % 360) + 360) % 180 == 0) crop.height.toDouble() else crop.width.toDouble()

    private fun matrix(m: Affine) = Matrix(m.a.toFloat(), m.b.toFloat(), m.c.toFloat(), m.d.toFloat(), m.e.toFloat(), m.f.toFloat())

    /** JPEG incrustado sin recomprimir. */
    private fun jpegImage(doc: PDDocument, f: File): PDImageXObject {
        val bytes = f.readBytes()
        val info = JpegInfo.read(bytes) ?: throw IOException("JPEG ilegible: ${f.name}")
        val cs = if (info.components == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
        return PDImageXObject(doc, ByteArrayInputStream(bytes), COSName.DCT_DECODE, info.width, info.height, 8, cs)
    }
}

/** Tamaño y componentes de un JPEG leyendo su marcador SOF (sin decodificarlo). */
internal data class JpegInfo(val width: Int, val height: Int, val components: Int) {
    companion object {
        fun read(b: ByteArray): JpegInfo? {
            if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
            var i = 2
            while (i + 9 < b.size) {
                if ((b[i].toInt() and 0xFF) != 0xFF) { i++; continue }
                val marker = b[i + 1].toInt() and 0xFF
                if (marker == 0xFF) { i++; continue }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (sof) {
                    val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                    val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                    val n = b[i + 9].toInt() and 0xFF
                    return if (w > 0 && h > 0) JpegInfo(w, h, n) else null
                }
                if (marker == 0xD9 || marker == 0xDA) return null
                i += 2 + len
            }
            return null
        }
    }
}
