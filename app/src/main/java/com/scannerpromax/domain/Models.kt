package com.scannerpromax.domain

import kotlinx.serialization.Serializable

/** Modo de escaneo elegido en la cámara. Determina recorte, composición y filtro por defecto. */
@Serializable
enum class ScanMode(val label: String) {
    DOCUMENT("Documento"),
    BOOK("Libro (2 páginas)"),
    ID_CARD("DNI / Tarjeta"),
    RECEIPT("Recibo"),
    WHITEBOARD("Pizarra"),
    PHOTO("Foto"),
}

/** Filtros de mejora. Cada uno lo implementa [com.scannerpromax.imaging.ImageEnhancer]. */
@Serializable
enum class FilterType(val label: String) {
    ORIGINAL("Original"),
    MAGIC("Mágico"),            // color limpio: fondo blanco, sin sombras, tinta saturada y nítida
    MAGIC_PRO("Mágico Pro"),    // MAGIC + super-resolución ligera + des-ruido fuerte (fotos de cámaras malas)
    NO_SHADOW("Sin sombras"),
    GRAYSCALE("Grises"),
    BLACK_WHITE("B/N"),         // binarización adaptativa (Sauvola-like) para texto
    ECO_INK("Ahorro tinta"),    // B/N fino, fondo puro blanco
    LIGHTEN("Aclarar"),
    VIVID("Vívido"),            // fotos / documentos a color
    WHITEBOARD("Pizarra"),
}

/** Punto en coordenadas de píxel de la imagen ORIGINAL. */
@Serializable
data class Pt(val x: Float, val y: Float)

/** Cuadrilátero del documento, orden: arriba-izq, arriba-der, abajo-der, abajo-izq (coords de la imagen original). */
@Serializable
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    fun points(): List<Pt> = listOf(tl, tr, br, bl)

    companion object {
        fun full(width: Int, height: Int) = Quad(
            Pt(0f, 0f), Pt(width.toFloat(), 0f),
            Pt(width.toFloat(), height.toFloat()), Pt(0f, height.toFloat()),
        )
        fun of(points: List<Pt>): Quad = Quad(points[0], points[1], points[2], points[3])
    }
}

/**
 * Trazo de borrado manual hecho por el usuario (para quitar rayas/manchas que el algoritmo no quitó).
 * Coordenadas NORMALIZADAS 0..1 sobre la imagen ya recortada+rotada (antes del filtro).
 * [radius] también normalizado respecto del ancho de la imagen.
 */
@Serializable
data class EraseStroke(
    val points: List<Pt>,
    val radius: Float,
    val mode: EraseMode = EraseMode.HEAL,
)

@Serializable
enum class EraseMode { HEAL /* inpainting inteligente */, WHITE /* pintar color de fondo */ }

/** Ajustes finos opcionales sobre el filtro (todos en rango -1..1, 0 = neutro). */
@Serializable
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val sharpness: Float = 0f,
)

/** Todas las ediciones NO destructivas de una página; se re-aplican desde el original. */
@Serializable
data class PageEdits(
    val quad: Quad? = null,             // null = imagen completa
    val rotation: Int = 0,              // 0, 90, 180, 270 (horario), aplicado tras el recorte
    val filter: FilterType = FilterType.MAGIC,
    val adjustments: Adjustments = Adjustments(),
    val autoRemoveLines: Boolean = false,   // quitar rayas/líneas sueltas (dobleces, marcas de bolígrafo, bordes)
    val autoDenoise: Boolean = true,        // limpiar ruido/puntos
    val autoDeskew: Boolean = true,         // enderezar texto torcido unos grados
    val eraseStrokes: List<EraseStroke> = emptyList(),
)

@Serializable
data class Page(
    val id: String,
    val originalFile: String,        // nombre de archivo relativo a la carpeta del documento
    val processedFile: String? = null,
    val thumbFile: String? = null,
    val width: Int,
    val height: Int,
    val edits: PageEdits = PageEdits(),
    val ocrText: String? = null,
    val ocrFile: String? = null,     // JSON con OcrResult (para capa de texto del PDF)
)

@Serializable
data class Document(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val mode: ScanMode = ScanMode.DOCUMENT,
    val pages: List<Page> = emptyList(),
)

// ---------- OCR ----------

@Serializable
data class OcrRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
data class OcrLine(val text: String, val box: OcrRect)

@Serializable
data class OcrBlock(val text: String, val box: OcrRect, val lines: List<OcrLine>)

/** Coordenadas en píxeles de la imagen PROCESADA sobre la que se corrió el OCR. */
@Serializable
data class OcrResult(val text: String, val imageWidth: Int, val imageHeight: Int, val blocks: List<OcrBlock>)

// ---------- Exportación ----------

enum class PageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    AUTO("Ajustar a imagen", 0f, 0f),
    A4("A4", 595.28f, 841.89f),
    LETTER("Carta", 612f, 792f),
    LEGAL("Oficio", 612f, 1008f),
}

enum class ExportQuality(val label: String, val jpegQuality: Int, val maxLongSide: Int) {
    SMALL("Pequeño", 60, 1600),
    BALANCED("Equilibrado", 80, 2480),
    HIGH("Alta", 92, 3508),
    MAX("Máxima (HD)", 98, 6000),
}

enum class ImageFormat(val label: String, val ext: String, val mime: String) {
    JPEG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WEBP", "webp", "image/webp"),
}

enum class CompressionLevel(val label: String, val dpi: Int, val jpegQuality: Int, val grayscale: Boolean) {
    LIGHT("Ligera (máxima calidad)", 200, 85, false),
    RECOMMENDED("Recomendada", 150, 72, false),
    STRONG("Fuerte", 110, 60, false),
    EXTREME("Extrema (B/N)", 96, 50, true),
}

data class PdfOptions(
    val pageSize: PageSize = PageSize.AUTO,
    val quality: ExportQuality = ExportQuality.HIGH,
    val searchable: Boolean = true,   // capa de texto OCR invisible
    val marginPt: Float = 0f,
    val password: String? = null,
)
