package com.scannerpromax.ai

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.domain.ScanMode

/**
 * Decisiones puras (JVM) que la IA aplica a la imagen: mejora AUTO según el tipo de documento y rescate de encuadre.
 */
object AiEnhance {

    /**
     * Clases de Qwen en las que el banco IA lo encontró FIABLE (6/6 cada una) y que cambian la mejora: recibo
     * (realce de tique térmico de AUTO; AUTO clásico solo reconocía el 6 %), pizarra y foto de pantalla (anti-muaré).
     * Libro y formulario también son fiables pero ya se tratan bien con "Texto resaltado". Manuscrito y "sin documento"
     * NO son fiables: nunca deciden nada destructivo.
     */
    val ENHANCING_CLASSES = setOf("receipt", "whiteboard", "screen")

    /** Nombre en español del tipo de documento (para la interfaz y el DocLayout). */
    fun label(docClass: String?): String? = when (docClass) {
        "receipt" -> "Recibo"
        "id_card" -> "Documento de identidad"
        "handwritten" -> "Manuscrito"
        "whiteboard" -> "Pizarra"
        "screen" -> "Pantalla"
        "book" -> "Libro"
        "form" -> "Formulario"
        "printed" -> "Impreso"
        "no_document" -> "Sin documento"
        else -> null
    }

    /** Código en español para [com.scannerpromax.domain.DocLayout.docType] ("recibo", "manuscrito", "formulario"…). */
    fun docTypeCode(docClass: String?): String? = when (docClass) {
        "receipt" -> "recibo"
        "id_card" -> "identificacion"
        "handwritten" -> "manuscrito"
        "whiteboard" -> "pizarra"
        "screen" -> "pantalla"
        "book" -> "libro"
        "form" -> "formulario"
        "printed" -> "impreso"
        "no_document" -> "sin_documento"
        else -> null
    }

    /**
     * Nuevas ediciones con el filtro AUTO guiado por [docClass], o null si no hay que tocar nada: solo documentos
     * normales ([mode] DOCUMENT) que siguen con el filtro por defecto ("Texto resaltado") y sin pista previa.
     */
    fun decide(docClass: String, mode: ScanMode, edits: PageEdits): PageEdits? {
        if (docClass !in ENHANCING_CLASSES) return null
        if (mode != ScanMode.DOCUMENT) return null
        if (edits.aiHint != null || edits.filter != FilterType.DEFAULT) return null
        return edits.copy(filter = FilterType.AUTO, aiHint = docClass)
    }

    /**
     * ROI del rescate: caja de Qwen (0..1: izq., arr., der., abajo) ampliada un [margin] por lado y recortada a la
     * imagen [w] x [h]. Devuelve (x0, y0, x1, y1) en píxeles.
     */
    fun roi(box: FloatArray, w: Int, h: Int, margin: Float = 0.06f): IntArray {
        val bw = box[2] - box[0]; val bh = box[3] - box[1]
        val x0 = ((box[0] - bw * margin) * w).toInt().coerceIn(0, w - 1)
        val y0 = ((box[1] - bh * margin) * h).toInt().coerceIn(0, h - 1)
        val x1 = ((box[2] + bw * margin) * w).toInt().coerceIn(x0 + 1, w)
        val y1 = ((box[3] + bh * margin) * h).toInt().coerceIn(y0 + 1, h)
        return intArrayOf(x0, y0, x1, y1)
    }

    /** Rectángulo (x0, y0, x1, y1) -> Quad, en el espacio [sx], [sy] (escala a las dimensiones de la página). */
    fun rectQuad(r: IntArray, sx: Float, sy: Float): Quad = Quad(
        Pt(r[0] * sx, r[1] * sy), Pt(r[2] * sx, r[1] * sy), Pt(r[2] * sx, r[3] * sy), Pt(r[0] * sx, r[3] * sy),
    )

    /** Área de un cuadrilátero. */
    fun area(q: Quad): Float {
        val p = q.points()
        var a = 0f
        for (i in p.indices) { val u = p[i]; val v = p[(i + 1) % p.size]; a += u.x * v.y - v.x * u.y }
        return kotlin.math.abs(a) / 2f
    }
}
