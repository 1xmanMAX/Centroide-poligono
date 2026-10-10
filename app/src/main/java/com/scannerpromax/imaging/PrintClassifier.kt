package com.scannerpromax.imaging

import kotlin.math.abs
import kotlin.math.max

/**
 * Enrutado del tipo de página para "Texto resaltado" y "Blanco y negro" (lógica pura, sin OpenCV: testeable).
 *
 * Dos renders posibles:
 *  - [PrintedPage] ("fotocopiadora"): normaliza la iluminación y conserva TODO el contenido (texto pequeño,
 *    líneas de tablas, sellos, logos, letra a mano tenue, documentos antiguos con transparencia del reverso).
 *  - [TextRegions] (recuadros de escritura): pensado para APUNTES EN CUADERNO; su valor diferencial es borrar la
 *    cuadrícula o los renglones claros del papel y dejar la escritura sobre blanco puro. A cambio, todo lo que no
 *    reconoce como trazo se blanquea.
 *
 * Medido sobre 333 imágenes reales (DIBCO, FUNSD, CORD, SmartDoc, MIDV, WarpDoc, GNHK, archive.org, Openverse y las
 * fotos de cuaderno del usuario): con la ruta de impresos el FM de binarización de DIBCO sube de 43 a 74, la
 * pérdida de contenido baja del 23 % al 2 % y el OCR mejora en recibos y formularios; la ruta de recuadros sólo gana
 * donde hay un rayado de cuaderno que borrar (cuadrícula azul, renglones). Por eso la decisión ya no es
 * "¿es impreso?" sino "¿es una hoja de CUADERNO con rayado claro?": en la duda, la ruta que conserva todo.
 *
 * Hoja de cuaderno = todo esto a la vez:
 *  - suficientes rectas largas y CLARAS (rayado más tenue que la escritura: oscuridad media <= [RULING_MAX_DARK],
 *    o de color y algo más oscuro, <= [RULING_MAX_DARK_COLOR] con croma >= [RULING_MIN_CHROMA]);
 *  - FINAS (grosor medio <= [RULING_MAX_THICK] px a la escala de análisis): las "rectas" gruesas son renglones de
 *    texto impreso borrosos o manchas de documentos antiguos;
 *  - resolución suficiente (lado largo >= [MIN_SIDE] px): por debajo, el rayado no se distingue de un renglón de
 *    texto difuminado (documento pequeño fotografiado de lejos);
 *  - escritura sin la alineación de líneas base de la letra impresa ([align] < [MAX_ALIGN]);
 *  - ni tabla ni texto impreso denso detectados.
 */
internal object PrintClassifier {

    const val MIN_SIDE = 1000
    const val MIN_RULING_LINES = 10
    const val RULING_MAX_DARK = 40.0
    const val RULING_MAX_DARK_COLOR = 60.0
    const val RULING_MIN_CHROMA = 30.0
    const val RULING_MAX_THICK = 2.6
    const val MAX_ALIGN = 0.35

    data class Features(
        val fullSide: Int,          // lado largo de la imagen completa (px)
        val rulingLines: Int,       // rectas claras horizontales + verticales
        val rulingDark: Double,     // oscuridad media de esas rectas (0..255 sobre el papel)
        val rulingChroma: Double,   // croma medio de esas rectas respecto del papel
        val rulingThick: Double,    // grosor medio (px a la escala de análisis)
        val align: Double,          // fracción de componentes con vecina en la misma línea base
        val table: Boolean,
        val denseText: Boolean,
    )

    /** true = hoja de cuaderno con rayado -> recuadros de escritura ([TextRegions]); false = [PrintedPage]. */
    fun isNotebook(f: Features): Boolean {
        if (f.table || f.denseText) return false
        if (f.fullSide < MIN_SIDE) return false
        if (f.rulingLines < MIN_RULING_LINES) return false
        val light = f.rulingDark <= RULING_MAX_DARK ||
            (f.rulingChroma >= RULING_MIN_CHROMA && f.rulingDark <= RULING_MAX_DARK_COLOR)
        return light && f.rulingThick <= RULING_MAX_THICK && f.align < MAX_ALIGN
    }

    /** Tipo de página (decide detalles del render y se muestra como clase del contenido). */
    enum class PageType(val label: String) {
        NOTEBOOK("Cuaderno"),
        HANDWRITTEN("Manuscrito"),
        FORM("Formulario o tabla"),
        RECEIPT("Recibo o factura"),
        ILLUSTRATED("Con imágenes"),
        TEXT("Texto impreso"),
    }

    data class PageFeatures(
        val notebook: Boolean,      // [isNotebook]
        val table: Boolean,         // rectas oscuras largas en ambas direcciones
        val denseText: Boolean,     // mucho texto pequeño y regular
        val linesH: Int,            // rectas oscuras largas horizontales
        val linesV: Int,            // ... verticales
        val textComps: Int,         // componentes del tamaño de una letra
        val boxes: Int,             // componentes para la alineación de líneas base
        val align: Double,          // [baselineAlignment]
        val letterRel: Double,      // altura de letra típica / lado largo
        val inkFrac: Double,        // fracción de tinta oscura
        val pictureFrac: Double,    // fracción de fotos / tramas / rellenos / bloques de color
        val aspect: Double,         // alto / ancho (un tique es alto y estrecho; una tira apaisada no)
    )

    const val PICTURE_MIN = 0.06
    const val HW_MAX_ALIGN = 0.30
    const val HW_MIN_ALIGN = 0.03
    const val HW_MIN_BOXES = 100
    const val HW_MAX_RULES_V = 3
    const val FORM_MIN_H = 6
    const val FORM_MIN_H_ALONE = 10
    const val RECEIPT_MIN_ASPECT = 2.0

    /**
     * Tipo de página (puro). En la duda, TEXT (render de impresos sin ajustes especiales). Orden:
     *  - cuaderno con rayado claro ([isNotebook]);
     *  - con imágenes: fotos, tramas o rellenos grandes (>= [PICTURE_MIN] de la página);
     *  - manuscrito: muchas componentes sin la alineación de líneas base de la letra impresa, sin texto impreso
     *    denso y sin filetes verticales de tabla (los renglones de una carta rayada sí se admiten);
     *  - formulario o tabla: rectas oscuras largas en ambas direcciones, o filetes horizontales con alguno
     *    vertical, o muchos filetes horizontales (líneas para rellenar);
     *  - recibo: tique alto y estrecho (alto >= [RECEIPT_MIN_ASPECT] x ancho);
     *  - texto impreso.
     */
    fun pageType(f: PageFeatures): PageType = when {
        f.notebook -> PageType.NOTEBOOK
        f.pictureFrac >= PICTURE_MIN -> PageType.ILLUSTRATED
        !f.denseText && f.boxes >= HW_MIN_BOXES && f.align < HW_MAX_ALIGN && f.align >= HW_MIN_ALIGN &&
            f.linesV <= HW_MAX_RULES_V && !f.table -> PageType.HANDWRITTEN
        f.table || (f.linesH >= FORM_MIN_H && f.linesV >= 1) || f.linesH >= FORM_MIN_H_ALONE -> PageType.FORM
        f.aspect >= RECEIPT_MIN_ASPECT -> PageType.RECEIPT
        else -> PageType.TEXT
    }

    /**
     * Alineación de líneas base: fracción de componentes (x, y, w, h por fila en [boxes], 4 enteros cada una) que
     * tienen a su derecha, a menos de ~1 altura de letra [letter], otra de tamaño parecido cuyo borde inferior
     * coincide (±12 % de la altura). La letra impresa (letras sueltas sobre una línea base) da 0.5-0.85; la
     * manuscrita (palabras enlazadas, líneas base irregulares) 0.02-0.35.
     */
    fun baselineAlignment(boxes: IntArray, letter: Double): Double {
        val n = boxes.size / 4
        if (n < 8 || letter <= 0) return 0.0
        val cell = max(2.0, letter)
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        for (i in 0 until n) {
            val kx = (boxes[i * 4] / cell).toInt(); val ky = ((boxes[i * 4 + 1] + boxes[i * 4 + 3]) / cell).toInt()
            grid.getOrPut(key(kx, ky)) { ArrayList(4) }.add(i)
        }
        var aligned = 0
        for (i in 0 until n) {
            val x = boxes[i * 4]; val w = boxes[i * 4 + 2]; val h = boxes[i * 4 + 3]
            val right = x + w; val bot = boxes[i * 4 + 1] + h
            val lo = right - 0.3 * letter; val hi = right + 1.0 * letter
            val ky = (bot / cell).toInt()
            var found = false
            loop@ for (kx in (lo / cell).toInt()..(hi / cell).toInt()) for (dy in -1..1) {
                val lst = grid[key(kx, ky + dy)] ?: continue
                for (j in lst) {
                    if (j == i) continue
                    val ox = boxes[j * 4]; val oh = boxes[j * 4 + 3]
                    if (ox < lo || ox > hi) continue
                    val ob = boxes[j * 4 + 1] + oh
                    if (abs(ob - bot) <= 0.12 * max(h, oh) + 1.0 && oh * 2 >= h && h * 2 >= oh) { found = true; break@loop }
                }
            }
            if (found) aligned++
        }
        return aligned.toDouble() / n
    }
}
