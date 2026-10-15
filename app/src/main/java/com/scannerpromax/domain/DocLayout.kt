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
    /**
     * Giro (grados, ANTIHORARIO) que la IA aplicó a la página antes de reconocerla (orientación PP-LCNet doc_ori).
     * El giro ya está en las ediciones de la página (la imagen procesada sale derecha y estas coordenadas son de ella):
     * es informativo (0 = ninguno).
     */
    val rotation: Int = 0,
    /** Rayas rectas de la página (formularios, subrayados, bordes de tabla): x0,y0,x1,y1,grosor (px). */
    val rules: List<List<Float>> = emptyList(),
    /** Fondos de color lisos (bandas de tabla, recuadros sombreados). */
    val fills: List<LayoutFill> = emptyList(),
)

/** Fondo de color liso: caja (px) + color ARGB. */
@Serializable
data class LayoutFill(val box: LayoutBox, val color: Int)

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
    /**
     * Clase original del detector de maquetación (p. ej. "paragraph_title", "figure_title", "seal") o el origen del
     * bloque: "libre" (líneas fuera de las regiones), "dibujo" (tinta sin texto: recorte solo-tinta), "nube".
     */
    val role: String? = null,
    /** Alineación del párrafo: LEFT, CENTER, RIGHT, JUSTIFY (null = desconocida; ver [effectiveAlign]). */
    val align: String? = null,
    /** Alto de línea típico del bloque (px de imagen): tamaño de letra común del párrafo. */
    val sizePx: Float? = null,
    /** Negrita medida localmente (null = desconocida; ver [effectiveBold]). */
    val bold: Boolean? = null,
    /** Color de tinta dominante del bloque (ARGB) si las líneas no lo traen. */
    val color: Int? = null,
    /** Solo TABLE: true si la tabla tiene reglado propio (las rayas van en [DocLayout.rules]); false = sin rejilla. */
    val ruled: Boolean? = null,
) {
    /**
     * Alineación a usar: la medida localmente ([align]) o, si no hay, la que vio la IA en la nube ([style]).
     * Siempre en mayúsculas (LEFT/CENTER/RIGHT/JUSTIFY) o null.
     */
    val effectiveAlign: String?
        get() = (align ?: style?.align)?.uppercase()?.takeIf { it in ALIGNS }

    /** Negrita: la medida localmente o, si no hay, la de la nube (solo cuando la nube dice que sí). */
    val effectiveBold: Boolean?
        get() = bold ?: style?.bold?.takeIf { it }

    /** ¿Trae alguna pista de estilo (local o de la nube)? */
    val hasStyle: Boolean
        get() = align != null || sizePx != null || bold != null || style != null

    companion object {
        val ALIGNS = setOf("LEFT", "CENTER", "RIGHT", "JUSTIFY")

        /** Factor de tamaño de [BlockStyle.size] respecto al cuerpo del texto (null = desconocido). */
        fun styleSizeFactor(size: String?): Float? = when (size?.lowercase()) {
            "small" -> 0.85f
            "normal" -> 1f
            "large" -> 1.3f
            "xlarge" -> 1.7f
            else -> null
        }
    }
}

/** Estilo de un bloque según la IA en la nube. [size] relativo al cuerpo del texto: small | normal | large | xlarge. */
@Serializable
data class BlockStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** left | center | right | justify (null = desconocida). */
    val align: String? = null,
    val size: String? = null,
)
