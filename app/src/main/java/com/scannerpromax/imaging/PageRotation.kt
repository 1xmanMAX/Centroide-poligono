package com.scannerpromax.imaging

import com.scannerpromax.domain.EraseStroke
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.abs

/**
 * GIRAR SIN REPROCESAR (lógica pura salvo [rotateMat]; probada en la JVM).
 *
 * El giro de 90° de una página es el ÚLTIMO paso de la tubería: la geometría (recorte, enderezado, hoja curvada), el filtro,
 * la limpieza y los borrados se calculan con el giro [PageEdits.geoRotation] (el que tenía la página cuando se procesó) y
 * el procesado se gira después [delta] grados píxel a píxel (transposición: sin remuestrear ni volver a filtrar). Por eso
 * girar = girar el procesado y la miniatura que ya existen, y transformar las cajas del OCR: render(B) == girar(render(A)).
 * Los trazos de borrado se guardan en el marco FINAL (lo que ve el usuario) y se llevan al marco de la geometría al
 * renderizar; las cajas de foto de la maquetación, igual.
 */
object PageRotation {

    fun norm(deg: Int): Int = ((deg % 360) + 360) % 360 / 90 * 90

    /**
     * ¿Puede la orientación automática de PÁGINAS (cola de la IA) girar esta página? Solo si nunca se giró después de
     * procesarla y NO es una hoja de DNI compuesta ([PageEdits.idCard]): cada cara ya se orientó por separado
     * (imaging/CardOrientation) y en la hoja A4 las caras apaisadas engañan al voto de páginas (doc_ori giraba 270° la
     * hoja con una cara vertical en el banco).
     */
    fun autoOrientAllowed(e: PageEdits): Boolean = e.rotation == 0 && e.geoRotation == null && e.idCard == null

    /** Giro con el que se calcula la geometría (y lo que va antes del giro final). */
    fun geoRotation(e: PageEdits): Int = norm(e.geoRotation ?: e.rotation)

    /** Giro horario que se aplica al FINAL (0/90/180/270). */
    fun delta(e: PageEdits): Int = norm(e.rotation - geoRotation(e))

    /**
     * Ediciones con el giro [newRotation] SIN tocar la geometría: [PageEdits.geoRotation] se conserva (null si coincide
     * con el giro, así 4 giros devuelven exactamente las ediciones de partida) y los trazos de borrado se giran con la
     * página. [aspect] = ancho/alto de la imagen ACTUAL (antes de girar), para el radio de los trazos.
     */
    fun withRotation(e: PageEdits, newRotation: Int, aspect: Float): PageEdits {
        val target = norm(newRotation)
        val k = norm(target - norm(e.rotation)) / 90
        if (k == 0) return e
        val geo = geoRotation(e)
        return e.copy(
            rotation = target,
            geoRotation = geo.takeIf { it != target },
            eraseStrokes = rotateStrokes(e.eraseStrokes, k, aspect),
        )
    }

    /**
     * Trazos (normalizados 0..1, radio relativo al ANCHO) de una imagen de proporción [aspect] (ancho/alto) girada [k]
     * cuartos de vuelta en sentido horario.
     */
    fun rotateStrokes(strokes: List<EraseStroke>, k: Int, aspect: Float): List<EraseStroke> {
        val q = ((k % 4) + 4) % 4
        if (q == 0 || strokes.isEmpty()) return strokes
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        return strokes.map { s ->
            EraseStroke(
                points = s.points.map { p -> rotatePt(p, q) },
                // el ancho nuevo es el alto de antes en los giros de 90/270
                radius = if (q % 2 == 1) s.radius * a else s.radius,
                mode = s.mode,
            )
        }
    }

    /** Punto normalizado girado [q] cuartos de vuelta en sentido horario. */
    fun rotatePt(p: Pt, q: Int): Pt = when (((q % 4) + 4) % 4) {
        1 -> Pt(1f - p.y, p.x)
        2 -> Pt(1f - p.x, 1f - p.y)
        3 -> Pt(p.y, 1f - p.x)
        else -> p
    }

    /** Ediciones para la GEOMETRÍA y el filtro: giro = [geoRotation] y trazos llevados a ese marco ([finalW] x [finalH] = salida final). */
    fun geoEdits(e: PageEdits, finalW: Int, finalH: Int): PageEdits {
        val d = delta(e)
        if (d == 0) return e.copy(rotation = geoRotation(e), geoRotation = null)
        val back = (4 - d / 90) % 4
        return e.copy(
            rotation = geoRotation(e),
            geoRotation = null,
            eraseStrokes = rotateStrokes(e.eraseStrokes, back, finalW.toFloat() / finalH.coerceAtLeast(1)),
        )
    }

    /** Ediciones de la geometría sin los trazos (para estimar la geometría: no dependen de ellos). */
    fun geoOnly(e: PageEdits): PageEdits = e.copy(rotation = geoRotation(e), geoRotation = null)

    /**
     * Cajas normalizadas (izq., arriba, der., abajo, [extras…]) del marco FINAL al de la geometría (giro inverso de
     * [delta] horario). Los valores extra (p. ej. la marca de tinta) se conservan.
     */
    fun boxesToGeo(boxes: List<FloatArray>, delta: Int): List<FloatArray> {
        val back = (4 - norm(delta) / 90) % 4
        if (back == 0) return boxes
        return boxes.map { b -> if (b.size < 4) b else rotateBoxNorm(b, back) }
    }

    /** Caja normalizada girada [q] cuartos de vuelta en sentido horario (conserva los valores tras los 4 primeros). */
    fun rotateBoxNorm(b: FloatArray, q: Int): FloatArray {
        val o = b.copyOf()
        when (((q % 4) + 4) % 4) {
            1 -> { o[0] = 1f - b[3]; o[1] = b[0]; o[2] = 1f - b[1]; o[3] = b[2] }
            2 -> { o[0] = 1f - b[2]; o[1] = 1f - b[3]; o[2] = 1f - b[0]; o[3] = 1f - b[1] }
            3 -> { o[0] = b[1]; o[1] = 1f - b[2]; o[2] = b[3]; o[3] = 1f - b[0] }
        }
        return o
    }

    /**
     * ¿[b] es [a] con OTRO giro y nada más? Misma geometría ([geoRotation]), mismo filtro, ajustes, recorte…, y los trazos
     * de [a] girados coinciden con los de [b]. Entonces el procesado de [b] es el de [a] girado [delta] (sin reprocesar).
     * [aspectA] = ancho/alto del procesado de [a] (solo para comparar el radio de los trazos).
     */
    fun rotationOnly(a: PageEdits, b: PageEdits, aspectA: Float = 1f): Boolean {
        if (norm(a.rotation) == norm(b.rotation)) return false
        if (geoRotation(a) != geoRotation(b)) return false
        val strip = { e: PageEdits -> e.copy(rotation = 0, geoRotation = null, eraseStrokes = emptyList()) }
        if (strip(a) != strip(b)) return false
        if (a.eraseStrokes.size != b.eraseStrokes.size) return false
        if (a.eraseStrokes.isEmpty()) return true
        val k = norm(b.rotation - a.rotation) / 90
        val ra = rotateStrokes(a.eraseStrokes, k, aspectA)
        return ra.indices.all { i ->
            val x = ra[i]; val y = b.eraseStrokes[i]
            x.mode == y.mode && x.points.size == y.points.size &&
                abs(x.radius - y.radius) <= 0.06f * maxOf(x.radius, y.radius) + 1e-4f &&
                x.points.indices.all { j -> abs(x.points[j].x - y.points[j].x) <= 2e-3f && abs(x.points[j].y - y.points[j].y) <= 2e-3f }
        }
    }

    /** Giro horario de [b] respecto de [a] (0/90/180/270). */
    fun between(a: PageEdits, b: PageEdits): Int = norm(b.rotation - a.rotation)

    // ------------------------------------------------------------------ OCR

    /**
     * OCR de una imagen [OcrResult.imageWidth] x [OcrResult.imageHeight] llevado a esa imagen girada [delta] grados en
     * sentido horario: cajas de bloques, líneas y palabras (envolventes alineadas a los ejes), dimensiones intercambiadas
     * y ángulo de cada línea + [delta] (horario positivo, en (−180, 180]). El texto no cambia.
     */
    fun rotateOcr(ocr: OcrResult, delta: Int): OcrResult {
        val d = norm(delta)
        if (d == 0) return ocr
        val w = ocr.imageWidth.toFloat(); val h = ocr.imageHeight.toFloat()
        fun box(r: OcrRect): OcrRect = when (d) {
            90 -> OcrRect(h - r.bottom, r.left, h - r.top, r.right)
            180 -> OcrRect(w - r.right, h - r.bottom, w - r.left, h - r.top)
            else -> OcrRect(r.top, w - r.right, r.bottom, w - r.left)
        }
        fun ang(a: Float): Float { var x = a + d; while (x > 180f) x -= 360f; while (x <= -180f) x += 360f; return x }
        fun line(l: OcrLine) = l.copy(box = box(l.box), words = l.words.map { OcrWord(it.text, box(it.box), it.confidence) }, angle = ang(l.angle))
        val swap = d != 180
        return ocr.copy(
            imageWidth = if (swap) ocr.imageHeight else ocr.imageWidth,
            imageHeight = if (swap) ocr.imageWidth else ocr.imageHeight,
            blocks = ocr.blocks.map { b -> OcrBlock(b.text, box(b.box), b.lines.map(::line)) },
            doubtful = ocr.doubtful.map(::line),
        )
    }

    // ------------------------------------------------------------------ píxeles

    /** [src] girado [delta] grados en sentido horario SIN pérdidas (transposición + volteo; nuevo Mat, [src] intacto). */
    fun rotateMat(src: Mat, delta: Int): Mat {
        val out = Mat()
        when (norm(delta)) {
            90 -> Core.rotate(src, out, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, out, Core.ROTATE_180)
            270 -> Core.rotate(src, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(out)
        }
        return out
    }
}
