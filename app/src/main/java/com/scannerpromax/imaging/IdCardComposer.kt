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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * «Tamaño del DNI en la hoja» (Ajustes › Escaneo): escala de cada cara respecto del tamaño real ID-1. Las dos caras y su
 * separación caben SIEMPRE en el A4 vertical con márgenes ([IdCardComposer.layout] reduce la escala si no caben).
 */
enum class IdCardSize(val label: String, val scale: Float, val description: String) {
    REAL("Real (100 %)", 1.0f, "Tamaño real de la tarjeta (85,6 × 54 mm): al imprimir mide lo mismo que el original"),
    LARGE("Grande (140 %)", 1.4f, "Más grande y legible en la pantalla y al imprimir"),
    XLARGE("Muy grande (175 %)", 1.75f, "Casi el ancho de la hoja, con márgenes"),
    ;

    companion object {
        val DEFAULT = LARGE
    }
}

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
    const val GAP = 180
    /** Margen mínimo de la hoja (≈ 12,7 mm) que las caras nunca invaden. */
    const val MARGIN = 150

    /**
     * Rectángulos (izq., arriba, der., abajo; px de la hoja A4 a 300 dpi) de las caras de proporciones [cards]
     * (ancho, alto de cada imagen: vertical u horizontal) a la escala [scale] del tamaño real, una encima de la otra y
     * centradas. Si no caben con [MARGIN] y [GAP], la escala se reduce hasta que quepan.
     */
    fun layout(cards: List<Pair<Int, Int>>, scale: Float): List<IntArray> {
        if (cards.isEmpty()) return emptyList()
        val base = cards.map { (w, h) -> if (h > w) CARD_SHORT to CARD_LONG else CARD_LONG to CARD_SHORT }
        val maxW = base.maxOf { it.first }
        val sumH = base.sumOf { it.second }
        val gaps = GAP * (cards.size - 1)
        val fitW = (PAGE_W - 2.0 * MARGIN) / maxW
        val fitH = (PAGE_H - 2.0 * MARGIN - gaps) / sumH
        val s = min(scale.toDouble().coerceAtLeast(0.1), min(fitW, fitH))
        val sizes = base.map { (w, h) -> (w * s).roundToInt() to (h * s).roundToInt() }
        val totalH = sizes.sumOf { it.second } + gaps
        var y = (PAGE_H - totalH) / 2
        return sizes.map { (cw, ch) ->
            val x = (PAGE_W - cw) / 2
            intArrayOf(x, y, x + cw, y + ch).also { y += ch + GAP }
        }
    }

    /**
     * Compone anverso y reverso de un DNI/tarjeta (ya recortados) en una sola hoja A4 a 300 dpi, uno encima del otro,
     * centrados, a [size] del tamaño real ISO/IEC 7810 ID-1 (85.6 x 54 mm; por defecto [IdCardSize.DEFAULT]).
     */
    fun compose(front: Bitmap, back: Bitmap?, size: IdCardSize = IdCardSize.DEFAULT): Bitmap {
        // En equipos con muy poca memoria, RGB_565 (la mitad de bytes, sin transparencia necesaria)
        val lowMem = Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024
        val page = Bitmap.createBitmap(PAGE_W, PAGE_H, if (lowMem) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)

        val cards = listOfNotNull(front, back)
        val rects = layout(cards.map { it.width to it.height }, size.scale)
        for ((i, card) in cards.withIndex()) {
            val r = rects[i]
            val k = (r[2] - r[0]).toFloat() / max(1, if (card.height > card.width) CARD_SHORT else CARD_LONG)
            drawCard(canvas, card, RectF(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat()), CORNER * k, 2f * max(1f, k))
        }
        return page
    }

    /** Dibuja la imagen "cubriendo" el rectángulo (recorte centrado) con esquinas redondeadas y borde sutil. */
    private fun drawCard(canvas: Canvas, src: Bitmap, dst: RectF, corner: Float, stroke: Float) {
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE
        val bmp = if (isHardware) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val scale = max(dst.width() / bmp.width, dst.height() / bmp.height)
        val dx = dst.left + (dst.width() - bmp.width * scale) / 2f
        val dy = dst.top + (dst.height() - bmp.height * scale) / 2f
        val m = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        canvas.drawRoundRect(dst, corner, corner, paint)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.argb(70, 0, 0, 0)
        }
        canvas.drawRoundRect(dst, corner, corner, border)
        if (bmp !== src) bmp.recycle()
    }
}
