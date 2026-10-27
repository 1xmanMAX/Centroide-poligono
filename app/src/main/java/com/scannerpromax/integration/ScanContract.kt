package com.scannerpromax.integration

/**
 * Contrato público para que otras apps abran el escáner y reciban el documento (ver README, "Integración con otras
 * apps"). Las cadenas son API estable: no cambiarlas.
 *
 * Petición: `Intent(ScanContract.ACTION_SCAN)` lanzado con `registerForActivityResult(StartActivityForResult())`.
 * Extras opcionales (todos con valores por defecto razonables; un valor desconocido se ignora):
 *  - [EXTRA_MODE]      String: document | book | id | receipt | whiteboard | photo   (por defecto document)
 *  - [EXTRA_FORMAT]    String: pdf | jpeg (jpg) | png                               (por defecto pdf)
 *  - [EXTRA_MULTI_PAGE] Boolean: permitir varias páginas                             (por defecto true)
 *  - [EXTRA_FILTER]    String: auto | enhanced (Color natural) | natural | bw | original      (por defecto el de Ajustes)
 *  - [EXTRA_OCR]       Boolean: reconocer texto → EXTRA_TEXT y PDF buscable          (por defecto false)
 *  - [EXTRA_TITLE]     String: nombre del archivo/documento (máx. [MAX_TITLE] caracteres)
 *  - [EXTRA_REVIEW]    Boolean: mostrar la pantalla de revisión antes de adjuntar     (por defecto false = directo)
 *  - [EXTRA_CALLER_LABEL] String: nombre de la app que llama, si no se puede obtener por callingPackage
 *
 * Modo directo (por defecto): cámara → "Adjuntar (N)" (o la primera foto si MULTI_PAGE=false) → se genera el archivo
 * y se devuelve al instante, sin pantalla de revisión.
 *
 * Resultado (RESULT_OK): `data` = Uri content:// del primer archivo (con permiso de lectura temporal), `clipData` =
 * todos los archivos, `type` = MIME. Extras: [EXTRA_URIS] (ArrayList<Uri>), [EXTRA_MIME_TYPE], [EXTRA_PAGE_COUNT],
 * [EXTRA_FILE_COUNT], [EXTRA_RESULT_FORMAT], [EXTRA_DOCUMENT_ID] (id estable del documento guardado en la biblioteca
 * del escáner, para reabrirlo con [ACTION_EDIT]) y, si se pidió OCR, `Intent.EXTRA_TEXT`. Cancelado → RESULT_CANCELED.
 *
 * Edición: `Intent(ScanContract.ACTION_EDIT).putExtra(EXTRA_DOCUMENT_ID, id)` (+ los extras de formato de SCAN: FORMAT,
 * MULTI_PAGE, FILTER, OCR, TITLE, CALLER_LABEL) abre ESE documento en su pantalla de páginas (recortar, girar, filtros,
 * reordenar, quitar y añadir páginas con la cámara o la galería). "Guardar en <app>" devuelve RESULT_OK con el MISMO
 * formato de resultado que SCAN (incluido el mismo [EXTRA_DOCUMENT_ID]); "‹ Cancelar" o Atrás → RESULT_CANCELED (los
 * retoques quedan guardados en el escáner). Si el id no existe o no es válido: RESULT_CANCELED con [EXTRA_ERROR]
 * ([ERROR_DOCUMENT_NOT_FOUND] / [ERROR_INVALID_DOCUMENT_ID]) y un aviso al usuario.
 */
object ScanContract {
    const val ACTION_SCAN = "com.scannerpromax.action.SCAN"
    const val ACTION_EDIT = "com.scannerpromax.action.EDIT"

    const val EXTRA_MODE = "com.scannerpromax.extra.MODE"
    const val EXTRA_FORMAT = "com.scannerpromax.extra.FORMAT"
    const val EXTRA_MULTI_PAGE = "com.scannerpromax.extra.MULTI_PAGE"
    const val EXTRA_FILTER = "com.scannerpromax.extra.FILTER"
    const val EXTRA_OCR = "com.scannerpromax.extra.OCR"
    const val EXTRA_TITLE = "com.scannerpromax.extra.TITLE"
    const val EXTRA_REVIEW = "com.scannerpromax.extra.REVIEW"
    const val EXTRA_CALLER_LABEL = "com.scannerpromax.extra.CALLER_LABEL"

    /** String. Resultado de SCAN/EDIT (y de GET_CONTENT si el escaneo se guarda) y petición obligatoria de EDIT. */
    const val EXTRA_DOCUMENT_ID = "com.scannerpromax.extra.DOCUMENT_ID"

    /** Alias cortos aceptados también (más cómodos desde adb o apps de automatización). */
    val SHORT_ALIASES = mapOf(
        "mode" to EXTRA_MODE,
        "format" to EXTRA_FORMAT,
        "multiPage" to EXTRA_MULTI_PAGE,
        "filter" to EXTRA_FILTER,
        "ocr" to EXTRA_OCR,
        "title" to EXTRA_TITLE,
        "review" to EXTRA_REVIEW,
        "callerLabel" to EXTRA_CALLER_LABEL,
        "documentId" to EXTRA_DOCUMENT_ID,
    )

    const val EXTRA_URIS = "com.scannerpromax.extra.URIS"
    const val EXTRA_MIME_TYPE = "com.scannerpromax.extra.MIME_TYPE"
    const val EXTRA_PAGE_COUNT = "com.scannerpromax.extra.PAGE_COUNT"
    const val EXTRA_FILE_COUNT = "com.scannerpromax.extra.FILE_COUNT"
    const val EXTRA_RESULT_FORMAT = "com.scannerpromax.extra.RESULT_FORMAT"

    /** String en un RESULT_CANCELED: por qué no se pudo atender la petición (códigos ERROR_*). */
    const val EXTRA_ERROR = "com.scannerpromax.extra.ERROR"
    /** EDIT: el documento ya no existe en el escáner (borrado o app reinstalada). */
    const val ERROR_DOCUMENT_NOT_FOUND = "document_not_found"
    /** EDIT: falta EXTRA_DOCUMENT_ID o no tiene un formato válido. */
    const val ERROR_INVALID_DOCUMENT_ID = "invalid_document_id"
    /** Acción no compatible. Otros motivos posibles: "unsupported_mime", "file_uri", "own_authority"… */
    const val ERROR_UNSUPPORTED = "unsupported_action"

    /** Longitud máxima de un id de documento (los propios son 16 caracteres hexadecimales). */
    const val MAX_DOCUMENT_ID = 64

    const val MAX_TITLE = 100
    const val MAX_CALLER_LABEL = 40
}
