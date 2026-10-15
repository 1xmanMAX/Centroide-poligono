package com.scannerpromax.domain

import kotlinx.serialization.Serializable

/**
 * Maquetación de una página reconocida por la IA en el dispositivo (contrato compartido entre la
 * tubería de reconocimiento, que lo produce, y la reconstrucción de PDF, que lo consume).
 *
 * Coordenadas en píxeles de la imagen PROCESADA de la página ([imageWidth] x [imageHeight]),
 * origen arriba-izquierda. Se guarda como JSON junto a la página (`<página>.layout.json`).
 */
@Serializable
data class DocLayout(
    val imageWidth: Int,
    val imageHeight: Int,
    val blocks: List<LayoutBlock>,
    /** Tipo de documento clasificado (p. ej. "recibo", "manuscrito", "formulario"); null si no se clasificó. */
    val docType: String? = null,
    /** Id del modelo que lo produjo (ModelCatalog), para invalidar si cambia el motor. */
    val engine: String = "",
)

@Serializable
enum class BlockType { TITLE, TEXT, HANDWRITING, TABLE, CHART, FORMULA, FIGURE, HEADER, FOOTER }

@Serializable
data class LayoutBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Línea de texto con su cuadrilátero (4 vértices: arriba-izq., arriba-der., abajo-der., abajo-izq.). */
@Serializable
data class LayoutLine(
    val text: String,
    val box: LayoutBox,
    /** x0,y0,x1,y1,x2,y2,x3,y3 en píxeles; vacío si solo hay caja recta. */
    val quad: List<Float> = emptyList(),
    /** Color de tinta medido (ARGB) o null = negro. */
    val color: Int? = null,
)

@Serializable
data class TableCell(
    val row: Int,
    val col: Int,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val text: String,
)

@Serializable
data class LayoutBlock(
    val type: BlockType,
    val box: LayoutBox,
    /** Texto del bloque en orden de lectura (líneas separadas por \n). */
    val text: String = "",
    val lines: List<LayoutLine> = emptyList(),
    /** Solo TABLE: celdas (OTSL ya resuelto a rejilla) y tamaño. */
    val tableRows: Int = 0,
    val tableCols: Int = 0,
    val cells: List<TableCell> = emptyList(),
    /** Solo CHART: datos extraídos como filas (la primera es la cabecera). */
    val chartData: List<List<String>> = emptyList(),
    /** Solo FORMULA: LaTeX sin delimitadores. */
    val latex: String? = null,
    /** Orden de lectura (0 = primero). */
    val order: Int = 0,
    /**
     * Estilo visto por la IA en la nube (opcional; null = desconocido). Añadido por el modo nube: la reconstrucción
     * puede usarlo (negrita/cursiva/alineación/tamaño relativo); los motores locales no lo rellenan.
     */
    val style: BlockStyle? = null,
)

/** Estilo de un bloque según la IA en la nube. [size] relativo al cuerpo del texto: small | normal | large | xlarge. */
@Serializable
data class BlockStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** left | center | right | justify (null = desconocida). */
    val align: String? = null,
    val size: String? = null,
)
