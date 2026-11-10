package com.scannerpromax.album

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Quad
import kotlinx.serialization.Serializable

/**
 * «Álbum / Diseño libre»: hojas (con o sin fondo) en las que el usuario dibuja recuadros ([AlbumSlot]) donde se
 * «pegan» los escaneos ([Sticker]), como los cromos de un álbum.
 *
 * Todo vive en `filesDir/albums/<id>/` ([AlbumStore]): `album.json` + los archivos relativos que nombran los modelos.
 * La geometría de los recuadros va en coordenadas NORMALIZADAS de la hoja (ver [AlbumSlot]) para que todo siga siendo
 * editable (mover, girar la hoja, cambiar el fondo) sin perder calidad.
 */
@Serializable
data class Album(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<AlbumPage> = emptyList(),
)

/**
 * Hoja del álbum. [widthMm]×[heightMm] = tamaño de la hoja TAL COMO SE VE (ya girada). [rotation] = giro horario del
 * fondo (0, 90, 180, 270) respecto a su orientación original; al girar la hoja se giran también los recuadros
 * ([AlbumGeometry.rotatePage]), así que estos siempre están en el espacio de la hoja visible.
 */
@Serializable
data class AlbumPage(
    val id: String,
    val widthMm: Float,
    val heightMm: Float,
    val background: SheetBackground = SheetBackground(),
    val rotation: Int = 0,
    val slots: List<AlbumSlot> = emptyList(),
)

@Serializable
enum class BackgroundKind { BLANK, IMAGE, PDF_PAGE }

/**
 * Fondo de una hoja. [file] relativo a la carpeta del álbum:
 *  - IMAGE: JPEG (de la galería o de una página escaneada de la app), ya con el EXIF aplicado.
 *  - PDF_PAGE: PDF de UNA página (extraída del original al elegirla) que se exporta como vectorial; [preview] = JPEG a
 *    resolución de pantalla para el editor.
 * [srcWidth]×[srcHeight] = tamaño del fondo SIN girar (px de la imagen o pt de la página PDF ya con su /Rotate).
 */
@Serializable
data class SheetBackground(
    val kind: BackgroundKind = BackgroundKind.BLANK,
    val file: String? = null,
    val preview: String? = null,
    val srcWidth: Float = 0f,
    val srcHeight: Float = 0f,
    /** Página del PDF original (1..n), solo informativo. */
    val pdfPage: Int? = null,
    /** Nombre del origen (archivo PDF o documento) para mostrarlo. */
    val label: String? = null,
)

/**
 * Recuadro donde va un cromo. Centro ([cx], [cy]) normalizado por el ancho y el alto de la hoja; [w] normalizado por
 * el ANCHO y [h] por el ALTO de la hoja (en mm: w·W × h·H). [angle] = giro horario en grados alrededor del centro
 * (el rectángulo se gira en el espacio físico, sin deformarse). [keepAspect] = redimensionar proporcional.
 */
@Serializable
data class AlbumSlot(
    val id: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val angle: Float = 0f,
    val keepAspect: Boolean = true,
    val label: String? = null,
    val sticker: Sticker? = null,
)

/** Cómo llena el cromo su recuadro. */
@Serializable
enum class StickerFit(val label: String) {
    COVER("Rellenar"),  // ocupa todo el recuadro (recorta lo que sobra)
    FIT("Encajar"),     // entero dentro del recuadro (puede dejar márgenes)
}

/**
 * Cromo pegado en un recuadro. [source] = foto ORIGINAL (relativa a la carpeta del álbum) y [quad] = recorte
 * NORMALIZADO 0..1 sobre ella (editable: «Recortar» vuelve a ella). [rendered] = el cromo ya rectificado y mejorado
 * ([renderedW]×[renderedH] px), lo que se dibuja y se exporta. [rotation] = giro del cromo DENTRO del recuadro.
 */
@Serializable
data class Sticker(
    val source: String,
    val quad: Quad,
    val rendered: String,
    val renderedW: Int,
    val renderedH: Int,
    val filter: FilterType? = FilterType.VIVID,
    val fit: StickerFit = StickerFit.COVER,
    val rotation: Int = 0,
    val manualQuad: Boolean = false,
)

/** Tamaños de hoja en blanco (mm, vertical). */
enum class PaperSize(val label: String, val widthMm: Float, val heightMm: Float) {
    A4("A4", 210f, 297f),
    LETTER("Carta", 215.9f, 279.4f),
    LEGAL("Oficio", 215.9f, 355.6f),
    A5("A5", 148f, 210f),
}

/** Medidas habituales para un recuadro (mm, ancho × alto en horizontal salvo las fotos). */
enum class SlotPreset(val label: String, val widthMm: Float, val heightMm: Float) {
    ID1("DNI / tarjeta (85,6 × 54 mm)", 85.6f, 54f),
    PASSPORT("Foto pasaporte (35 × 45 mm)", 35f, 45f),
    CARNET("Foto carnet (25 × 30 mm)", 25f, 30f),
    STICKER("Cromo (50 × 70 mm)", 50f, 70f),
    PHOTO_10x15("Foto 10 × 15 cm", 100f, 150f),
    PHOTO_13x18("Foto 13 × 18 cm", 130f, 180f),
    A6("A6 (105 × 148 mm)", 105f, 148f),
}
