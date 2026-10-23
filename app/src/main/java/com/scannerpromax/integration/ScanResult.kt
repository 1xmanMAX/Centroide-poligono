package com.scannerpromax.integration

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Qué se entrega a la otra app (independiente de Android, para testearlo):
 *  - [writeToOutput]: IMAGE_CAPTURE con EXTRA_OUTPUT → se escribe el JPEG en su Uri y el Intent de resultado va vacío.
 *  - [thumbnailOnly]: IMAGE_CAPTURE sin EXTRA_OUTPUT → solo una miniatura Bitmap en "data".
 *  - si no: [fileCount] archivos propios (FileProvider) con permiso de lectura; [pagesPerFile] páginas cada uno.
 */
data class DeliveryPlan(
    val format: OutputFormat,
    val writeToOutput: Boolean,
    val thumbnailOnly: Boolean,
    /** Índices de las páginas que se exportan (en orden). */
    val pages: List<Int>,
    /** true = un archivo por página; false = un único archivo con [pages]. */
    val filePerPage: Boolean,
    val includeText: Boolean,
) {
    val fileCount: Int get() = if (writeToOutput || thumbnailOnly) 0 else if (filePerPage) pages.size else 1
}

object DeliveryPlanner {
    /**
     * [pageCount] = páginas del escaneo (>= 1). Las imágenes sin varios archivos solo pueden llevar una página: se
     * entrega [singleImagePage] (por defecto la primera; la revisión deja elegir otra).
     */
    fun plan(request: ScanRequest, pageCount: Int, singleImagePage: Int = 0): DeliveryPlan {
        require(pageCount >= 1) { "Sin páginas" }
        val one = listOf(singleImagePage.coerceIn(0, pageCount - 1))
        val all = (0 until pageCount).toList()
        return when {
            request.source == RequestSource.IMAGE_CAPTURE -> DeliveryPlan(
                format = OutputFormat.JPEG,
                writeToOutput = request.outputUri != null,
                thumbnailOnly = request.outputUri == null,
                pages = one,
                filePerPage = false,
                includeText = false,
            )
            request.format == OutputFormat.PDF -> DeliveryPlan(request.format, false, false, all, false, request.ocr)
            request.filePerPage -> DeliveryPlan(request.format, false, false, all, true, request.ocr)
            else -> DeliveryPlan(request.format, false, false, one, false, request.ocr)
        }
    }
}

/** Arma el Intent de resultado (setResult) según [DeliveryPlan]. */
object ScanResultIntent {
    /** Lado largo máximo de la miniatura de IMAGE_CAPTURE (el Binder limita el tamaño de los extras). */
    const val THUMB_MAX_SIDE = 320

    /**
     * [uris]: archivos propios ya expuestos con FileProvider (uno por archivo del plan). [text]: texto reconocido
     * (solo si [DeliveryPlan.includeText]). [thumbnail]: miniatura para IMAGE_CAPTURE sin EXTRA_OUTPUT.
     */
    fun build(
        plan: DeliveryPlan,
        uris: List<Uri>,
        pageCount: Int,
        text: String? = null,
        thumbnail: Bitmap? = null,
        label: String = "Escaneo",
    ): Intent {
        if (plan.writeToOutput) {
            // Contrato estándar: el archivo ya está en EXTRA_OUTPUT; el Intent no lleva datos.
            return Intent()
        }
        if (plan.thumbnailOnly) {
            return Intent("inline-data").apply { if (thumbnail != null) putExtra("data", thumbnail) }
        }
        require(uris.isNotEmpty()) { "Sin archivos" }
        val mime = plan.format.mime
        val clip = ClipData(ClipDescription(label, arrayOf(mime)), ClipData.Item(uris[0]))
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        return Intent().apply {
            setDataAndType(uris[0], mime)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putParcelableArrayListExtra(ScanContract.EXTRA_URIS, ArrayList(uris))
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            else putExtra(Intent.EXTRA_STREAM, uris[0])
            putExtra(ScanContract.EXTRA_MIME_TYPE, mime)
            putExtra(ScanContract.EXTRA_RESULT_FORMAT, plan.format.ext)
            putExtra(ScanContract.EXTRA_PAGE_COUNT, pageCount)
            putExtra(ScanContract.EXTRA_FILE_COUNT, uris.size)
            if (plan.includeText && text != null) putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    /** Escala [w]x[h] para que el lado largo no pase de [max] (nunca amplía). */
    fun thumbSize(w: Int, h: Int, max: Int = THUMB_MAX_SIDE): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val s = max.toFloat() / long
        return maxOf(1, Math.round(w * s)) to maxOf(1, Math.round(h * s))
    }
}

/** Extrae [IntentFacts] de un Intent recibido. */
object IntentFactsReader {
    fun read(intent: Intent): IntentFacts {
        val extras = runCatching { intent.extras }.getOrNull()
        val map = HashMap<String, Any?>()
        if (extras != null) {
            val keys = runCatching { extras.keySet().toList() }.getOrDefault(emptyList())
            val wanted = ScanContract.SHORT_ALIASES.values.toSet()
            for (k in keys) {
                val full = ScanContract.SHORT_ALIASES[k] ?: k.takeIf { it in wanted } ?: continue
                // Solo tipos simples: cualquier otra cosa (Parcelable de otra app) se ignora sin deserializarla.
                @Suppress("DEPRECATION")
                val v = runCatching { extras.get(k) }.getOrNull()
                if (v is CharSequence || v is Boolean || v is Number) {
                    // La clave completa gana al alias corto si vienen las dos.
                    if (k == full || !map.containsKey(full)) map[full] = v
                }
            }
        }
        val output: String? = runCatching {
            val u: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
            }
            u?.toString() ?: intent.getStringExtra(MediaStore.EXTRA_OUTPUT)
        }.getOrNull()
        return IntentFacts(
            action = intent.action,
            type = intent.type,
            extraMimeTypes = runCatching { intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.filterNotNull() }.getOrNull().orEmpty(),
            allowMultiple = runCatching { intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) }.getOrDefault(false),
            outputUri = output,
            extras = map,
        )
    }
}
