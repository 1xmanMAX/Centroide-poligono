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
 *  - [EXTRA_FILTER]    String: auto | enhanced (texto resaltado) | bw | original      (por defecto el de Ajustes)
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
 * [EXTRA_FILE_COUNT], [EXTRA_RESULT_FORMAT] y, si se pidió OCR, `Intent.EXTRA_TEXT`. Cancelado → RESULT_CANCELED.
 */
object ScanContract {
    const val ACTION_SCAN = "com.scannerpromax.action.SCAN"

    const val EXTRA_MODE = "com.scannerpromax.extra.MODE"
    const val EXTRA_FORMAT = "com.scannerpromax.extra.FORMAT"
    const val EXTRA_MULTI_PAGE = "com.scannerpromax.extra.MULTI_PAGE"
    const val EXTRA_FILTER = "com.scannerpromax.extra.FILTER"
    const val EXTRA_OCR = "com.scannerpromax.extra.OCR"
    const val EXTRA_TITLE = "com.scannerpromax.extra.TITLE"
    const val EXTRA_REVIEW = "com.scannerpromax.extra.REVIEW"
    const val EXTRA_CALLER_LABEL = "com.scannerpromax.extra.CALLER_LABEL"

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
    )

    const val EXTRA_URIS = "com.scannerpromax.extra.URIS"
    const val EXTRA_MIME_TYPE = "com.scannerpromax.extra.MIME_TYPE"
    const val EXTRA_PAGE_COUNT = "com.scannerpromax.extra.PAGE_COUNT"
    const val EXTRA_FILE_COUNT = "com.scannerpromax.extra.FILE_COUNT"
    const val EXTRA_RESULT_FORMAT = "com.scannerpromax.extra.RESULT_FORMAT"

    const val MAX_TITLE = 100
    const val MAX_CALLER_LABEL = 40
}
