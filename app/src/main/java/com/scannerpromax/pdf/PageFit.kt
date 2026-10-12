package com.scannerpromax.pdf

import com.scannerpromax.domain.PageSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Encaje de una imagen en una página PDF SIN RECORTAR nunca su contenido (lógica pura, testeable en la JVM).
 *
 *  - [PageSize.AUTO] ("Ajustar a imagen", por defecto): la página toma la proporción EXACTA de la imagen (si coincide
 *    con A4/Carta/Oficio se usa ese tamaño, que tiene la misma proporción) más los márgenes: la imagen la llena.
 *  - Tamaño fijo (A4, Carta, Oficio): la página se orienta como la imagen y la imagen se ENCAJA dentro (escala
 *    uniforme = min de ambos ejes, centrada); sobran bandas blancas, nunca se corta nada.
 *
 * La imagen siempre se dibuja con su proporción original (sin estirar).
 */
object PageFit {

    /** Página de [pageW] x [pageH] pt; la imagen va en ([x], [y]) (origen PDF abajo-izquierda) con [w] x [h] pt. */
    data class Result(val pageW: Float, val pageH: Float, val x: Float, val y: Float, val w: Float, val h: Float)

    fun layout(imgW: Float, imgH: Float, pageSize: PageSize, marginPt: Float = 0f): Result {
        require(imgW > 0f && imgH > 0f) { "Imagen vacía" }
        val margin = marginPt.coerceAtLeast(0f)
        val aspect = imgW / imgH
        val landscape = imgW > imgH
        val (pw, ph) = when (pageSize) {
            PageSize.AUTO -> autoSize(aspect).let { (w, h) -> (w + 2 * margin) to (h + 2 * margin) }
            else -> {
                val a = pageSize.widthPt
                val b = pageSize.heightPt
                if (landscape) b to a else a to b
            }
        }
        val availW = (pw - 2 * margin).coerceAtLeast(1f)
        val availH = (ph - 2 * margin).coerceAtLeast(1f)
        val scale = min(availW / imgW, availH / imgH)
        val dw = imgW * scale
        val dh = imgH * scale
        return Result(pw, ph, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh)
    }

    /**
     * AUTO: si la proporción coincide (±0,5 %) con A4/Carta/Oficio se usa ese tamaño; si no, lado largo = A4 y el corto
     * con la proporción EXACTA de la imagen (así la imagen llena la página sin bandas ni recorte).
     */
    fun autoSize(aspect: Float): Pair<Float, Float> {
        val portrait = aspect <= 1f
        val a = if (portrait) aspect else 1f / aspect
        val long = PageSize.A4.heightPt
        val standard = listOf(PageSize.A4, PageSize.LETTER, PageSize.LEGAL)
            .firstOrNull { abs(it.widthPt / it.heightPt - a) / a < STANDARD_TOLERANCE }
        val (w, h) = if (standard != null) standard.widthPt to standard.heightPt else (long * a) to long
        return if (portrait) w to h else h to w
    }

    /** Tolerancia para dar por estándar una proporción (antes 3 %: dejaba bandas blancas de hasta 3 % del ancho). */
    const val STANDARD_TOLERANCE = 0.005f

    /** ¿La imagen se dibuja entera dentro de la página (sin recorte) y con su proporción? Para pruebas y asertos. */
    fun fitsWithoutCrop(r: Result, imgW: Float, imgH: Float, eps: Float = 0.01f): Boolean =
        r.x >= -eps && r.y >= -eps && r.x + r.w <= r.pageW + eps && r.y + r.h <= r.pageH + eps &&
            abs(r.w / r.h - imgW / imgH) <= max(1e-4f, imgW / imgH * 1e-4f)
}
