package com.scannerpromax.imaging

import kotlin.math.min

/**
 * Reglas de tono de [PrintedPage] (lógica pura, testeable sin OpenCV).
 */
internal object PrintTone {

    /** Rango mínimo blanco - negro que se conserva (el papel no se vuelve tinta). */
    const val MIN_RANGE = 60.0

    /**
     * Punto negro para páginas de tinta TENUE. [c90] = percentil 90 del contraste de los trazos respecto del papel;
     * el negro sube hasta que ese trazo típico quede a la fracción [target] del blanco (0.15 -> casi negro).
     * Si la tinta ya es oscura ([black] mayor) no cambia nada; nunca deja menos de [MIN_RANGE] niveles de rango.
     */
    fun faintBlack(c90: Double, white: Double, black: Double, target: Double): Double {
        val faint = white - c90 / (1.0 - target.coerceIn(0.0, 0.9))
        return if (faint > black) min(faint, white - MIN_RANGE) else black
    }

    /** Profundidad máxima (fracción del lado) de una mancha oscura de borde para tratarla como fondo ajeno. */
    const val OUTSIDE_MAX_DEPTH = 0.15

    /**
     * ¿Mancha oscura que es FONDO AJENO a la hoja (mesa, hueco entre hojas, cuña junto a un borde curvo)? Debe
     * tocar el borde de la imagen ([minBorderDist] <= 1 px) y no entrar hacia dentro más de [OUTSIDE_MAX_DEPTH] del
     * lado ([maxBorderDist] = mayor distancia al borde de la imagen de sus píxeles): un marco de mesa alrededor
     * de la hoja, una banda o una cuña. Una foto a sangre, el fondo negro de un anuncio o un bloque oscuro que
     * llega al borde de una página escaneada entran mucho más: son contenido y se conservan.
     */
    fun isOutsideBand(minBorderDist: Int, maxBorderDist: Int, w: Int, h: Int): Boolean =
        minBorderDist <= 1 && maxBorderDist <= OUTSIDE_MAX_DEPTH * maxOf(w, h)

    /** Croma (max - min de RGB normalizado) a partir del cual un píxel puede ser de un bloque de color (B/N). */
    const val BLOCK_CHROMA = 40.0

    /** Grosor mínimo (en alturas de letra) de un bloque de color para no tratarlo como trazo de tinta (B/N). */
    const val BLOCK_MIN_LH = 0.45

    /** Fracción máxima de huecos pequeños (texto claro, detalles) de una mancha de borde profunda que es fondo liso. */
    const val PLAIN_MAX_HOLES = 0.01

    /** ¿Mancha oscura profunda pegada al borde que es FONDO liso (mesa, tela) y no un bloque de contenido? */
    fun isPlainBackground(smallHoleFrac: Double): Boolean = smallHoleFrac < PLAIN_MAX_HOLES

    /** Fracción de fondo ajeno a partir de la cual el papel se vuelve a medir sin él. */
    const val OUTSIDE_RESTAT_FRAC = 0.15

    /** Área mínima (fracción de la página) de una imagen interior que el B/N conserva en gris (páginas con imágenes). */
    const val PICTURE_KEEP_MIN = 0.02

    /** Relleno mínimo de su recuadro (una foto es compacta; una columna de texto en zigzag, no). */
    const val PICTURE_KEEP_FILL = 0.5

    /** Fracción máxima de negro macizo de una imagen (más = tachón, banda o recuadro relleno, no una foto). */
    const val PICTURE_MAX_SOLID = 0.8

    /** Croma (Lab) de la luz a partir del cual se considera luz de color. */
    const val CAST_MIN_CHROMA = 12.0

    /** Coseno mínimo entre el tono de la luz y el de la tinta para atribuir el color de la tinta a la luz. */
    const val CAST_MIN_COS = 0.6

    /**
     * Factor de saturación del render en COLOR según la dominante [cast] = (croma de la luz, coseno tono luz/tinta):
     * 1 sin dominante; baja hasta 0.25 con luz muy coloreada cuyo tono comparte la tinta.
     */
    fun castSaturation(cast: DoubleArray): Double {
        val c = cast[0]; val cos = cast[1]
        if (c < CAST_MIN_CHROMA || cos < CAST_MIN_COS) return 1.0
        val t = ((c - CAST_MIN_CHROMA) / CAST_MIN_CHROMA).coerceIn(0.0, 1.0)
        return 1.0 - 0.75 * t
    }

    /** Longitud mínima (en alturas de letra) de un trazo de color recto para tratarlo como rayado del papel (B/N). */
    const val RULE_MIN_LH = 4.0
}
