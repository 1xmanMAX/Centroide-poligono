package com.scannerpromax.ai

/**
 * Tipos de documento (los que devuelve la IA en la nube en "doc_type") → nombre y código en español para el
 * [com.scannerpromax.domain.DocLayout]. (La clasificación local con Qwen y su mejora AUTO se eliminaron.)
 */
object AiEnhance {

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
}
