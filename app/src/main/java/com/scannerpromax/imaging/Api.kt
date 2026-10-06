package com.scannerpromax.imaging

// ARCHIVO DE CONTRATO: las firmas públicas de abajo son el API que usan UI/datos.
// El agente de algoritmos reemplaza cada TODO() con la implementación real
// (puede mover cada objeto/clase a su propio archivo, conservando paquete, nombre y firmas).

import android.content.Context
import android.graphics.Bitmap
import com.scannerpromax.domain.EraseStroke
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Quad

object DeviceProfiler {
    fun profile(context: Context): DeviceTier = TODO()
}

/** Detección de bordes del documento. Thread-safe; reutiliza buffers internamente. */
class DocumentDetector(private val tier: DeviceTier) {
    /** Detección precisa sobre una foto completa (tras capturar). */
    fun detect(bitmap: Bitmap): DetectionResult? = TODO()

    /**
     * Detección rápida para vista previa en vivo, desde el plano Y (luminancia) de un frame YUV de CameraX.
     * [rowStride] del plano. [rotationDegrees] la rotación del frame; el quad devuelto ya está en
     * coordenadas del frame ROTADO (ancho/alto intercambiados si 90/270).
     */
    fun detectLive(yPlane: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, rotationDegrees: Int): DetectionResult? = TODO()
}

object PerspectiveCorrector {
    /** Recorta y endereza [quad] a un rectángulo con la relación de aspecto real estimada. */
    fun warp(src: Bitmap, quad: Quad): Bitmap = TODO()
}

object ImageEnhancer {
    fun apply(src: Bitmap, filter: FilterType, adjustments: com.scannerpromax.domain.Adjustments = com.scannerpromax.domain.Adjustments()): Bitmap = TODO()
}

object Cleanup {
    /** Quita rayas/líneas largas aisladas (dobleces, marcas, bordes de hoja) preservando texto. */
    fun removeLines(src: Bitmap): Bitmap = TODO()
    /** Quita ruido de sal y pimienta, puntos y motas, preservando bordes de letras. */
    fun denoise(src: Bitmap): Bitmap = TODO()
    /** Endereza unos grados el contenido según la orientación dominante de las líneas de texto. */
    fun deskew(src: Bitmap): Bitmap = TODO()
    /** Aplica los trazos de borrado manual (inpainting o blanco) sobre [src]. */
    fun applyEraseStrokes(src: Bitmap, strokes: List<EraseStroke>): Bitmap = TODO()
}

object QualityAnalyzer {
    fun analyze(bitmap: Bitmap): QualityReport = TODO()
}

object BookSplitter {
    /** Detecta el lomo (gutter) de un libro abierto y devuelve las dos páginas, cada una aplanada. */
    fun split(src: Bitmap): BookSplit = TODO()
}

object IdCardComposer {
    /**
     * Compone anverso y reverso de un DNI/tarjeta (ya recortados) en una sola hoja A4 a 300 dpi,
     * a tamaño real ISO/IEC 7810 ID-1 (85.6 x 54 mm), uno encima del otro, centrados.
     */
    fun compose(front: Bitmap, back: Bitmap?): Bitmap = TODO()
}

/** Carga/guardado de bitmaps cuidando la memoria (inSampleSize, EXIF, RGB_565 cuando conviene). */
object BitmapIO {
    fun decode(path: String, maxPixels: Int): Bitmap = TODO()
    fun decodeBounds(path: String): Pair<Int, Int> = TODO()
    fun saveJpeg(bitmap: Bitmap, path: String, quality: Int = 92) { TODO() }
    fun thumbnail(src: Bitmap, maxSide: Int = 480): Bitmap = TODO()
}

/**
 * Tubería completa de una página: original -> recorte/perspectiva -> rotación -> deskew ->
 * filtro -> limpieza (líneas/ruido) -> borrado manual.
 */
class PageProcessor(val tier: DeviceTier) {
    val detector = DocumentDetector(tier)

    /** Renderizado final a calidad completa. */
    fun render(original: Bitmap, edits: PageEdits, progress: ProgressCallback? = null): Bitmap = TODO()

    /** Vista previa rápida (reduce a [maxSide] antes de procesar) para sliders/filtros en tiempo real. */
    fun preview(original: Bitmap, edits: PageEdits, maxSide: Int = 1200): Bitmap = TODO()

    /** Imagen recortada+rotada SIN filtro (base donde el usuario dibuja los trazos de borrado). */
    fun geometryOnly(original: Bitmap, edits: PageEdits, maxSide: Int = 0): Bitmap = TODO()
}
