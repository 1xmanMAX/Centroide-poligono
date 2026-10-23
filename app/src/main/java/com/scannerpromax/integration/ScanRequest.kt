package com.scannerpromax.integration

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.ScanMode

/** Formato del archivo que se devuelve a la app que pidió el escaneo. */
enum class OutputFormat(val mime: String, val ext: String, val label: String) {
    PDF("application/pdf", "pdf", "PDF"),
    JPEG("image/jpeg", "jpg", "JPG"),
    PNG("image/png", "png", "PNG"),
    ;

    val isImage: Boolean get() = this != PDF
}

/** Formato por defecto cuando la otra app acepta cualquier cosa (cualquier tipo) — ajuste del usuario. */
enum class AnyFormatDefault(val label: String) {
    PDF("PDF (todas las páginas en un archivo)"),
    JPEG("Imágenes JPG"),
}

/** Por dónde llegó la petición. */
enum class RequestSource { IMAGE_CAPTURE, GET_CONTENT, SCAN }

/**
 * Petición de escaneo para otra app, ya validada.
 *  - [multiPage]: la cámara deja hacer varias páginas (si es false se pasa a la revisión tras la primera foto).
 *  - [filePerPage]: se devuelve un archivo por página (ClipData); si es false, un único archivo (PDF multipágina, o
 *    la primera página si es una imagen).
 *  - [outputUri]: EXTRA_OUTPUT de IMAGE_CAPTURE (content:// de la otra app) donde escribir el JPEG.
 *  - [wantsThumbnail]: IMAGE_CAPTURE sin EXTRA_OUTPUT → miniatura Bitmap en el extra "data" (contrato estándar).
 */
data class ScanRequest(
    val source: RequestSource,
    val format: OutputFormat,
    val multiPage: Boolean,
    val filePerPage: Boolean,
    val mode: ScanMode = ScanMode.DOCUMENT,
    val filter: FilterType? = null,
    val ocr: Boolean = false,
    val title: String? = null,
    val outputUri: String? = null,
    val wantsThumbnail: Boolean = false,
)

/** Hechos del Intent recibido, ya extraídos a tipos simples (así el análisis es JVM puro y testeable). */
data class IntentFacts(
    val action: String?,
    val type: String? = null,
    /** Intent.EXTRA_MIME_TYPES. */
    val extraMimeTypes: List<String> = emptyList(),
    /** Intent.EXTRA_ALLOW_MULTIPLE. */
    val allowMultiple: Boolean = false,
    /** MediaStore.EXTRA_OUTPUT como texto (null = no viene). */
    val outputUri: String? = null,
    /** Extras de [ScanContract] (claves completas; los alias cortos ya traducidos). Valores tal cual llegan. */
    val extras: Map<String, Any?> = emptyMap(),
)

sealed interface ParseResult {
    data class Ok(val request: ScanRequest) : ParseResult
    /** [reason]: código corto para el registro; [message]: texto para el usuario. */
    data class Rejected(val reason: String, val message: String) : ParseResult
}

/**
 * Análisis de los Intents de otras apps: qué formato y cuántos archivos se devuelven para cada combinación de
 * acción / MIME / extras. Sin dependencias de Android.
 */
object ScanRequestParser {
    const val ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE"
    const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"

    /**
     * [ownPackage]: paquete de esta app; se rechaza un EXTRA_OUTPUT que apunte a un proveedor propio (otra app podría
     * usarlo para que sobrescribiéramos nuestros propios archivos).
     */
    fun parse(facts: IntentFacts, anyDefault: AnyFormatDefault, ownPackage: String): ParseResult = when (facts.action) {
        ACTION_IMAGE_CAPTURE -> parseImageCapture(facts, ownPackage)
        ACTION_GET_CONTENT -> parseGetContent(facts, anyDefault)
        ScanContract.ACTION_SCAN -> parseScan(facts)
        else -> ParseResult.Rejected("unsupported_action", "Petición no compatible")
    }

    // ------------------------------------------------------------------------------------ IMAGE_CAPTURE

    private fun parseImageCapture(facts: IntentFacts, ownPackage: String): ParseResult {
        val out = facts.outputUri?.trim()?.takeIf { it.isNotEmpty() }
        if (out != null) {
            val problem = outputUriProblem(out, ownPackage)
            if (problem != null) return ParseResult.Rejected(problem, "La app pidió guardar la foto en una ubicación no permitida")
        }
        return ParseResult.Ok(
            ScanRequest(
                source = RequestSource.IMAGE_CAPTURE,
                format = OutputFormat.JPEG,
                multiPage = false,
                filePerPage = false,
                outputUri = out,
                wantsThumbnail = out == null,
            ),
        )
    }

    /** null = válida. Solo content:// de otro proveedor (nunca file://, ni nuestro propio FileProvider). */
    fun outputUriProblem(uri: String, ownPackage: String): String? {
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme == "file") return "file_uri"
        if (scheme != "content") return "bad_scheme"
        val authority = uri.substringAfter("://", missingDelimiterValue = "").substringBefore('/').substringAfter('@').lowercase()
        if (authority.isEmpty()) return "no_authority"
        val own = ownPackage.lowercase()
        if (authority == own || authority.startsWith("$own.")) return "own_authority"
        return null
    }

    // -------------------------------------------------------------------------------------- GET_CONTENT

    private fun parseGetContent(facts: IntentFacts, anyDefault: AnyFormatDefault): ParseResult {
        val format = chooseFormat(acceptedMimes(facts), anyDefault)
            ?: return ParseResult.Rejected("unsupported_mime", "La app pide un tipo de archivo que el escáner no genera")
        val multiple = facts.allowMultiple
        return ParseResult.Ok(
            ScanRequest(
                source = RequestSource.GET_CONTENT,
                format = format,
                // PDF: siempre se pueden añadir páginas (un único archivo multipágina). Imagen: solo si acepta varias.
                multiPage = format == OutputFormat.PDF || multiple,
                filePerPage = format.isImage && multiple,
            ),
        )
    }

    /** MIME aceptados: EXTRA_MIME_TYPES si viene (suele acompañar a "cualquier tipo"); si no, el type del Intent. */
    fun acceptedMimes(facts: IntentFacts): List<String> {
        val extra = facts.extraMimeTypes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (extra.isNotEmpty()) return extra
        return listOf(facts.type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "*/*")
    }

    /** Formato a devolver para la lista de MIME aceptados; null = ninguno de los que sabemos generar. */
    fun chooseFormat(mimes: List<String>, anyDefault: AnyFormatDefault): OutputFormat? {
        val pdf = mimes.any { matches(it, OutputFormat.PDF.mime) }
        val jpeg = mimes.any { matches(it, OutputFormat.JPEG.mime) || it == "image/jpg" }
        val png = mimes.any { matches(it, OutputFormat.PNG.mime) }
        return when {
            pdf && (jpeg || png) -> when {
                anyDefault == AnyFormatDefault.JPEG && jpeg -> OutputFormat.JPEG
                anyDefault == AnyFormatDefault.JPEG && png -> OutputFormat.PNG
                else -> OutputFormat.PDF
            }
            pdf -> OutputFormat.PDF
            jpeg -> OutputFormat.JPEG
            png -> OutputFormat.PNG
            else -> null
        }
    }

    /** ¿El patrón MIME [pattern] (p. ej. "image/" + "*") acepta el tipo concreto [mime]? */
    fun matches(pattern: String, mime: String): Boolean {
        val p = pattern.trim().lowercase().substringBefore(';').trim()
        val m = mime.lowercase()
        if (p == "*/*" || p == "*") return true
        val (pt, ps) = p.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (mt, ms) = m.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return pt == mt && (ps == "*" || ps == ms)
    }

    // ------------------------------------------------------------------------------------------- SCAN

    private fun parseScan(facts: IntentFacts): ParseResult {
        val e = facts.extras
        val format = when (string(e[ScanContract.EXTRA_FORMAT])?.lowercase()) {
            "jpeg", "jpg", "image/jpeg" -> OutputFormat.JPEG
            "png", "image/png" -> OutputFormat.PNG
            else -> OutputFormat.PDF
        }
        val mode = parseMode(string(e[ScanContract.EXTRA_MODE]))
        val multiPage = bool(e[ScanContract.EXTRA_MULTI_PAGE]) ?: true
        return ParseResult.Ok(
            ScanRequest(
                source = RequestSource.SCAN,
                format = format,
                multiPage = multiPage,
                filePerPage = format.isImage && multiPage,
                mode = mode,
                filter = parseFilter(string(e[ScanContract.EXTRA_FILTER])),
                ocr = bool(e[ScanContract.EXTRA_OCR]) ?: false,
                title = sanitizeTitle(string(e[ScanContract.EXTRA_TITLE])),
            ),
        )
    }

    fun parseMode(s: String?): ScanMode = when (s?.trim()?.lowercase()) {
        "book", "libro" -> ScanMode.BOOK
        "id", "id_card", "idcard", "card", "dni" -> ScanMode.ID_CARD
        "receipt", "recibo", "ticket" -> ScanMode.RECEIPT
        "whiteboard", "pizarra", "board" -> ScanMode.WHITEBOARD
        "photo", "foto" -> ScanMode.PHOTO
        else -> ScanMode.DOCUMENT
    }

    /** null = el filtro por defecto de Ajustes. */
    fun parseFilter(s: String?): FilterType? = when (s?.trim()?.lowercase()) {
        "bw", "b&w", "black_white", "blackwhite", "mono", "blanco_negro" -> FilterType.BLACK_WHITE
        "enhanced", "magic", "text", "auto", "color_text" -> FilterType.MAGIC
        "original", "color", "none" -> FilterType.ORIGINAL
        else -> null
    }

    /** Sin caracteres de control ni separadores de ruta; recortado a [ScanContract.MAX_TITLE]. null si queda vacío. */
    fun sanitizeTitle(s: String?): String? =
        s?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.trim('.')
            ?.take(ScanContract.MAX_TITLE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun string(v: Any?): String? = (v as? CharSequence)?.toString()

    /** Acepta Boolean o texto ("true"/"false"/"1"/"0"/"yes"/"no"); cualquier otra cosa = no indicado. */
    private fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is CharSequence -> when (v.toString().trim().lowercase()) {
            "true", "1", "yes", "si", "sí" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }
}
