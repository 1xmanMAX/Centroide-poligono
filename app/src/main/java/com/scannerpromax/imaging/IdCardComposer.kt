package com.scannerpromax.imaging

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import kotlin.math.max

object IdCardComposer {

    /** A4 a 300 dpi. */
    const val PAGE_W = 2480
    const val PAGE_H = 3508
    /** ID-1 (85.6 x 54 mm) a 300 dpi. */
    const val CARD_LONG = 1011
    const val CARD_SHORT = 638
    /** Radio de esquina ISO 7810 (3.18 mm) a 300 dpi. */
    private const val CORNER = 37f
    /** Separación entre anverso y reverso (~1.5 cm). */
    private const val GAP = 180

    /**
     * Compone anverso y reverso de un DNI/tarjeta (ya recortados) en una sola hoja A4 a 300 dpi,
     * a tamaño real ISO/IEC 7810 ID-1 (85.6 x 54 mm), uno encima del otro, centrados.
     */
    fun compose(front: Bitmap, back: Bitmap?): Bitmap {
        // En equipos con muy poca memoria, RGB_565 (la mitad de bytes, sin transparencia necesaria)
        val lowMem = Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024
        val page = Bitmap.createBitmap(PAGE_W, PAGE_H, if (lowMem) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)

        val cards = listOfNotNull(front, back)
        val sizes = cards.map { cardSize(it) }
        val totalH = sizes.sumOf { it.second } + GAP * (cards.size - 1)
        var y = (PAGE_H - totalH) / 2f
        for ((i, card) in cards.withIndex()) {
            val (cw, ch) = sizes[i]
            val x = (PAGE_W - cw) / 2f
            drawCard(canvas, card, RectF(x, y, x + cw, y + ch))
            y += ch + GAP
        }
        return page
    }

    /** Tamaño real en la hoja, respetando si la tarjeta es vertical u horizontal. */
    private fun cardSize(b: Bitmap): Pair<Int, Int> =
        if (b.height > b.width) CARD_SHORT to CARD_LONG else CARD_LONG to CARD_SHORT

    /** Dibuja la imagen "cubriendo" el rectángulo (recorte centrado) con esquinas redondeadas y borde sutil. */
    private fun drawCard(canvas: Canvas, src: Bitmap, dst: RectF) {
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE
        val bmp = if (isHardware) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val scale = max(dst.width() / bmp.width, dst.height() / bmp.height)
        val dx = dst.left + (dst.width() - bmp.width * scale) / 2f
        val dy = dst.top + (dst.height() - bmp.height * scale) / 2f
        val m = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        canvas.drawRoundRect(dst, CORNER, CORNER, paint)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(70, 0, 0, 0)
        }
        canvas.drawRoundRect(dst, CORNER, CORNER, border)
        if (bmp !== src) bmp.recycle()
    }
}
