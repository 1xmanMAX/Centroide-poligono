package com.scannerpromax.ai.parse

/** Tipos de documento que puede devolver la IA en la nube ("doc_type"). (Los parsers de PaddleOCR-VL/Qwen se eliminaron.) */
object IaText {
    val DOC_CLASSES = listOf("receipt", "id_card", "handwritten", "whiteboard", "screen", "book", "form", "printed", "no_document")
}
