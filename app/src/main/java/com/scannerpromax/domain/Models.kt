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

/**
 * Filtros de mejora. Cada uno lo implementa [com.scannerpromax.imaging.ImageEnhancer].
 *
 * La interfaz SÓLO ofrece los de [FilterType.Companion.visible] (Blanco y negro, Color natural, Color original).
 * El resto se conserva porque los documentos guardados los usan (quitarlos rompería la deserialización) y siguen
 * renderizándose con su filtro; en la interfaz se muestran como su equivalente visible ([uiFilter]). Excepción:
 * "Texto resaltado" (MAGIC), sustituido por "Color natural", se MIGRA al cargar ([migrated]): se muestra y se
 * reprocesa como NATURAL (su render MAGIC sigue existiendo para el banco de pruebas).
 */
@Serializable
enum class FilterType(val label: String, val description: String = "") {
    AUTO("Auto inteligente"),   // clasifica el contenido (texto, color, foto, recibo, pizarra, poca luz, pantalla) y elige el procesamiento
    ORIGINAL("Color original", "Colores fieles a la foto: solo recorte, perspectiva y un toque de nitidez"),
    MAGIC("Texto resaltado", "Sin sombras, fondo blanco y tinta nítida conservando sus colores"),
    MAGIC_PRO("Mágico Pro"),    // MAGIC + super-resolución ligera + des-ruido fuerte (fotos de cámaras malas)
    NO_SHADOW("Sin sombras"),
    GRAYSCALE("Grises"),
    BLACK_WHITE("Blanco y negro", "Texto negro nítido sobre blanco puro, sin manchas ni cuadrícula de color"),
    ECO_INK("Ahorro tinta"),    // B/N fino, fondo puro blanco
    LIGHTEN("Aclarar"),
    VIVID("Vívido"),            // fotos / documentos a color
    WHITEBOARD("Pizarra"),
    NATURAL("Color natural", "Hoja blanca pura y la tinta con sus colores reales: el B/N recorta la escritura y deja pasar su color"),
    ;

    /** Filtro visible que representa a este en la interfaz (los antiguos se muestran como su equivalente). */
    val uiFilter: FilterType
        get() = when (this) {
            BLACK_WHITE, GRAYSCALE, ECO_INK -> BLACK_WHITE
            ORIGINAL, VIVID -> ORIGINAL
            AUTO, MAGIC, MAGIC_PRO, NO_SHADOW, LIGHTEN, WHITEBOARD, NATURAL -> NATURAL
        }

    /**
     * Filtro con el que se guarda/renderiza una página cargada de una versión anterior: "Texto resaltado" (MAGIC) ya
     * no existe en la interfaz y pasa a "Color natural"; el resto no cambia (su hash de procesado tampoco).
     */
    val migrated: FilterType
        get() = if (this == MAGIC) NATURAL else this

    /** true si la interfaz lo ofrece. */
    val isVisible: Boolean get() = this in visible

    companion object {
        /** Los únicos filtros que ofrece la interfaz, en este orden. */
        val visible: List<FilterType> = listOf(BLACK_WHITE, NATURAL, ORIGINAL)

        /** Filtro por defecto de los documentos nuevos ("Color natural", antes "Texto resaltado"). */
        val DEFAULT: FilterType = NATURAL
    }
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
    val filter: FilterType = FilterType.NATURAL,
    val adjustments: Adjustments = Adjustments(),
    val autoRemoveLines: Boolean = false,   // quitar rayas/líneas sueltas (dobleces, marcas de bolígrafo, bordes)
    val autoDenoise: Boolean = true,        // limpiar ruido/puntos
    val autoDeskew: Boolean = true,         // enderezar texto torcido unos grados
    val autoDewarp: Boolean = true,         // enderezar hoja curvada/combada (malla de líneas de tablas y cuadrículas)
    val keepRuling: Boolean = true,         // conservar la cuadrícula / renglones del cuaderno (limpia, tono claro) en Color natural y B/N
    val eraseStrokes: List<EraseStroke> = emptyList(),
    /**
     * Tipo de documento según la IA (antigua clasificación local, ya eliminada; se conserva por compatibilidad con documentos guardados: "receipt", "whiteboard", "screen"…) que orienta el filtro
     * AUTO (ver ImageEnhancer.withAiHint). null = ninguno; al ser null no se serializa (explicitNulls = false), así que
     * el hash de las ediciones ya guardadas NO cambia.
     */
    val aiHint: String? = null,
    /**
     * true = el usuario ajustó el recorte a mano: se respeta EXACTO (el enderezado de hoja curvada no busca los cantos
     * de la hoja fuera del recorte, que con hojas apiladas o funda tomaría el canto de lo de debajo). null = recorte
     * automático; al ser null no se serializa y el hash de las ediciones ya guardadas no cambia.
     */
    val manualQuad: Boolean? = null,
    /**
     * Fotos de la página en "Color natural": null = automático, 1 = recortadas (cada foto pasa entera, como rectángulo, con
     * su color), 2 = como el resto de la página (sin trato de foto). null no se serializa: el hash guardado no cambia.
     */
    val photoMode: Int? = null,
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
    val ocrEditedText: String? = null, // texto corregido por el usuario (sobrevive a un nuevo OCR)
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

/** Palabra reconocida (Indra) con su caja alineada a los ejes y confianza (-1 = desconocida). */
@Serializable
data class OcrWord(val text: String, val box: OcrRect, val confidence: Float = -1f) {
    /** Lectura dudosa (confianza conocida y bajo [OCR_DOUBT]): se muestra con un subrayado discreto. */
    val isDoubtful: Boolean get() = confidence >= 0f && confidence < OCR_DOUBT
}

/** Confianza (0..1) bajo la que una línea o palabra se considera DUDOSA. */
const val OCR_DOUBT = 0.5f

/**
 * Línea reconocida. [words] y [angle] (grados, positivo = horario en coordenadas de imagen; la caja es la envolvente
 * alineada a los ejes) son opcionales: los JSON antiguos no los tienen.
 */
@Serializable
data class OcrLine(
    val text: String,
    val box: OcrRect,
    val words: List<OcrWord> = emptyList(),
    val angle: Float = 0f,
    val confidence: Float = -1f,
) {
    /** Línea dudosa (confianza conocida y bajo [OCR_DOUBT]). */
    val isDoubtful: Boolean get() = confidence >= 0f && confidence < OCR_DOUBT
}

@Serializable
data class OcrBlock(val text: String, val box: OcrRect, val lines: List<OcrLine>)

/** Coordenadas en píxeles de la imagen PROCESADA sobre la que se corrió el OCR. */
@Serializable
data class OcrResult(
    val text: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val blocks: List<OcrBlock>,
    /** Texto corregido por el usuario (lo rellena el repositorio desde [Page.ocrEditedText] al cargar). */
    val editedText: String? = null,
    /**
     * Líneas DUDOSAS (confianza baja: manuscrito difícil, campos de formulario) que antes se descartaban. NO están en
     * [blocks] ni en [text] (exportaciones de texto, maquetación y reconstrucción siguen igual): se muestran marcadas
     * en Texto en vivo y van en la capa invisible del PDF buscable. En orden de lectura.
     */
    val doubtful: List<OcrLine> = emptyList(),
) {
    /** Texto a mostrar/exportar: el corregido si existe, si no el reconocido. */
    val displayText: String get() = editedText ?: text
}

// ---------- Exportación ----------

enum class PageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    AUTO("Ajustar a imagen", 0f, 0f),
    A4("A4", 595.28f, 841.89f),
    LETTER("Carta", 612f, 792f),
    LEGAL("Oficio", 612f, 1008f),
}

/**
 * Calidad de exportación. La app guarda siempre el escaneo a resolución completa; la reducción ocurre SOLO aquí.
 * [maxLongSide] = lado largo máximo en píxeles ([Int.MAX_VALUE] = sin límite). Referencia A4 (297 mm):
 * 1600 px ≈ 137 ppp, 2480 px ≈ 212 ppp, 3508 px = 300 ppp.
 */
enum class ExportQuality(val label: String, val jpegQuality: Int, val maxLongSide: Int, val hint: String) {
    SMALL("Pequeño", 60, 1600, "≈ 140 ppp en A4 · ideal para enviar por chat o correo"),
    BALANCED("Equilibrado", 80, 2480, "≈ 210 ppp en A4 · buena lectura y tamaño moderado"),
    HIGH("Alta", 92, 3508, "300 ppp en A4 · recomendada para imprimir y archivar"),
    MAX("Máxima (HD)", 95, Int.MAX_VALUE, "Resolución completa del escaneo, sin recomprimir · el archivo más grande"),
    ;

    /** true = sin reducción: el procesado se incrusta/copia tal cual. */
    val isFullResolution: Boolean get() = maxLongSide == Int.MAX_VALUE

    /** Texto corto de la resolución: "hasta 3508 px" o "resolución completa". */
    val resolutionLabel: String get() = if (isFullResolution) "resolución completa" else "hasta $maxLongSide px"
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
    val searchable: Boolean = true,   // capa de texto OCR invisible (API antigua; ver textMode)
    val marginPt: Float = 0f,
    val password: String? = null,
    /** Modo de texto. null = se deduce de [searchable] (true -> BUSCABLE, false -> SOLO_IMAGEN). */
    val textMode: PdfTextMode? = null,
    /** Dónde van las páginas de texto visible en [PdfTextMode.BUSCABLE_CON_TEXTO]. */
    val textPlacement: TextPlacement = TextPlacement.AFTER_EACH_PAGE,
) {
    val effectiveTextMode: PdfTextMode
        get() = textMode ?: if (searchable) PdfTextMode.BUSCABLE else PdfTextMode.SOLO_IMAGEN
}

/** Qué texto lleva el PDF. */
enum class PdfTextMode(val label: String, val description: String, val needsOcr: Boolean, val hasImages: Boolean) {
    SOLO_IMAGEN("Solo imagen", "PDF de imágenes, sin texto", false, true),
    BUSCABLE("Buscable (recomendado)", "Imagen + texto invisible: buscar, seleccionar y copiar", true, true),
    BUSCABLE_CON_TEXTO("Buscable + texto reconocido", "Además añade páginas con el texto reconocido legible", true, true),
    SOLO_TEXTO("Solo texto", "Solo el texto reconocido maquetado: muy liviano", true, false),
    /**
     * PDF reconstruido por la IA (DocLayout): texto real con la misma disposición, tablas como rejilla, gráficos y
     * figuras como recorte. Las páginas sin reconocer con IA van como imagen. No usa el OCR clásico.
     */
    RECONSTRUIDO("PDF reconstruido", "Texto real con la misma disposición (páginas reconocidas con IA)", false, true),
}

/** Ubicación de las páginas de texto reconocido. */
enum class TextPlacement(val label: String) {
    AFTER_EACH_PAGE("Tras cada página"),
    END_OF_DOCUMENT("Al final del documento"),
}
