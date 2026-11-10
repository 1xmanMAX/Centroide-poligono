package com.scannerpromax.album

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dibuja una hoja del álbum en un Bitmap (exportar como imagen, miniaturas, «Crear documento»). La vista del editor
 * NO usa esto: dibuja las vistas previas por separado para mover los recuadros sin re-renderizar.
 *
 * [background] da el fondo SIN girar ya decodificado al tamaño que convenga (o null = blanco); el renderizador lo gira
 * y lo escala. Los cromos se decodifican con submuestreo al tamaño que ocupan (poca memoria).
 */
object AlbumRenderer {

    fun render(
        page: AlbumPage,
        dir: File,
        pxPerMm: Double,
        background: (wantW: Int, wantH: Int) -> Bitmap?,
        drawEmptySlots: Boolean = false,
    ): Bitmap {
        val wMm = page.widthMm.toDouble(); val hMm = page.heightMm.toDouble()
        val outW = (wMm * pxPerMm).roundToInt().coerceAtLeast(1)
        val outH = (hMm * pxPerMm).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val toPx = Affine().scale(outW / wMm, outH / hMm)

        val bg = page.background
        if (bg.kind != BackgroundKind.BLANK && bg.srcWidth > 0f && bg.srcHeight > 0f) {
            // tamaño del fondo SIN girar que ocupa la hoja de salida
            val rotated = (page.rotation / 90) % 2 == 1
            val wantW = if (rotated) outH else outW
            val wantH = if (rotated) outW else outH
            background(wantW, wantH)?.let { b ->
                try {
                    val m = Affine().scale(bg.srcWidth.toDouble() / b.width, bg.srcHeight.toDouble() / b.height)
                        .then(AlbumGeometry.backgroundToSheet(bg.srcWidth.toDouble(), bg.srcHeight.toDouble(), page.rotation, wMm, hMm))
                        .then(toPx)
                    canvas.drawBitmap(b, matrix(m), paint)
                } finally {
                    b.recycle()
                }
            }
        }

        for (slot in page.slots) {
            val box = AlbumGeometry.box(slot, wMm, hMm)
            val corners = box.corners().map { toPx.map(it) }
            val path = Path().apply {
                moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
                for (k in 1..3) lineTo(corners[k].x.toFloat(), corners[k].y.toFloat())
                close()
            }
            val st = slot.sticker
            val f = st?.let { File(dir, it.rendered) }
            if (st == null || f == null || !f.isFile) {
                if (drawEmptySlots) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; color = Color.rgb(150, 150, 160); strokeWidth = max(1f, outW / 400f)
                    }
                    canvas.drawPath(path, p)
                }
                continue
            }
            // lado largo que ocupa en la salida (con margen para COVER) -> submuestreo
            val need = (max(box.w, box.h) * pxPerMm * 1.5).roundToInt().coerceAtLeast(16)
            val bmp = decodeSampled(f, need) ?: continue
            try {
                canvas.save()
                canvas.clipPath(path)
                val m = AlbumGeometry.stickerToSheet(bmp.width.toDouble(), bmp.height.toDouble(), st.rotation, st.fit, box).then(toPx)
                canvas.drawBitmap(bmp, matrix(m), paint)
                canvas.restore()
            } finally {
                bmp.recycle()
            }
        }
        return out
    }

    fun matrix(m: Affine) = Matrix().apply { setValues(m.toMatrixValues()) }

    /** Decodifica [f] con inSampleSize para que el lado largo no baje de [minLongSide] más de lo necesario. */
    fun decodeSampled(f: File, minLongSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        val long = max(o.outWidth, o.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= minLongSide) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
