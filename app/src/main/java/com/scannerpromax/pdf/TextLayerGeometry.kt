package com.scannerpromax.pdf

import com.scannerpromax.domain.OcrRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Transformación de coordenadas de la imagen OCR (píxeles, y hacia abajo) a la página PDF (puntos, y hacia arriba):
 * pdfX = [x0] + x·[sx], pdfY = [yTop] − y·[sy].
 */
data class ImageToPage(val x0: Float, val yTop: Float, val sx: Float, val sy: Float) {
    fun x(px: Float) = x0 + px * sx
    fun y(py: Float) = yTop - py * sy
}

/**
 * Texto a dibujar: matriz de texto (origen en la línea base, rotación [angleRad] antihoraria en el PDF),
 * tamaño de letra y escala horizontal (Tz, en %).
 */
data class GlyphRun(val x: Float, val y: Float, val angleRad: Float, val fontSize: Float, val hScale: Float) {
    val cos: Float get() = cos(angleRad)
    val sin: Float get() = sin(angleRad)
}

/**
 * Geometría pura de la capa de texto invisible (probada en JVM).
 *
 * ML Kit da cajas alineadas a los ejes (envolventes) y el ángulo de la línea. Para un rectángulo girado de ancho W,
 * alto H y ángulo θ, la envolvente mide bw = W·cosθ + H·sinθ y bh = W·sinθ + H·cosθ; de ahí se recupera W y H.
 * Cada palabra se coloca en el centro de su caja, girada como su línea y con su ancho exacto (escala horizontal),
 * de modo que al seleccionar en un visor el resaltado cae justo encima de la palabra impresa.
 */
object TextLayerGeometry {

    /** Ángulo máximo que se respeta (más allá la recuperación es inestable; el OCR casi nunca devuelve más). */
    private const val MAX_ANGLE_DEG = 40f

    /** Alto real (no envolvente) de una línea girada [angleDeg] con envolvente [box]. */
    fun lineHeight(box: OcrRect, angleDeg: Float): Float {
        val bw = box.right - box.left
        val bh = box.bottom - box.top
        val a = Math.toRadians(abs(angleDeg.coerceIn(-MAX_ANGLE_DEG, MAX_ANGLE_DEG)).toDouble())
        val c = cos(a)
        val s = sin(a)
        val denom = c * c - s * s
        if (abs(angleDeg) < 0.5f || denom < 0.2) return bh
        val h = ((bh * c - bw * s) / denom).toFloat()
        return if (h > bh * 0.15f && h <= bh) h else bh
    }

    /**
     * Calcula el GlyphRun de un texto que debe ocupar exactamente la caja [box] (envolvente en px de imagen) de una
     * línea girada [angleDeg] (horario en la imagen) cuyo alto real es [lineH] px.
     *
     * @param textWidthEm ancho del texto en unidades de em (font.getStringWidth / 1000) a tamaño 1.
     * @param ascent ascendente de la fuente en em (p. ej. 0.905), [descent] descendente positivo (p. ej. 0.212).
     */
    fun place(
        box: OcrRect,
        angleDeg: Float,
        lineH: Float,
        textWidthEm: Float,
        map: ImageToPage,
        ascent: Float,
        descent: Float,
    ): GlyphRun? {
        val bw = box.right - box.left
        val bh = box.bottom - box.top
        if (bw <= 0f || bh <= 0f || textWidthEm <= 0f) return null
        val angle = if (abs(angleDeg) < 0.5f) 0f else angleDeg.coerceIn(-MAX_ANGLE_DEG, MAX_ANGLE_DEG)
        val rad = Math.toRadians(angle.toDouble())
        val c = cos(rad).toFloat()
        val s = abs(sin(rad).toFloat())
        val h = lineH.coerceIn(1f, bh)
        // Ancho real de la palabra a partir de su envolvente: bw = W·cosθ + H·sinθ.
        var w = if (angle == 0f) bw else (bw - h * s) / c
        if (w < bw * 0.2f) w = bw

        // Unidades PDF (escala anisótropa mínima: la imagen se coloca respetando su aspecto).
        val widthPt = w * map.sx
        val heightPt = h * map.sy
        val em = (ascent + descent).coerceAtLeast(0.5f)
        val fontSize = (heightPt / em).coerceAtLeast(0.5f)
        val hScale = (widthPt / (textWidthEm * fontSize) * 100f).coerceIn(1f, 2000f)

        // Centro de la caja en la página; θ horario en la imagen = −θ en el PDF (y hacia arriba).
        val cx = map.x((box.left + box.right) / 2f)
        val cy = map.y((box.top + box.bottom) / 2f)
        val phi = -rad.toFloat()
        val cp = cos(phi)
        val sp = sin(phi)
        // Origen de la línea base en coordenadas locales del rectángulo: (−W/2, −H/2 + descendente).
        val lu = -widthPt / 2f
        val lv = -heightPt / 2f + descent * fontSize
        val ox = cx + lu * cp - lv * sp
        val oy = cy + lu * sp + lv * cp
        return GlyphRun(ox, oy, phi, fontSize, hScale)
    }
}
