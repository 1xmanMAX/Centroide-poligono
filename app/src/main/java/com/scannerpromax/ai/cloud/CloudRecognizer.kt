package com.scannerpromax.ai.cloud

import android.graphics.Bitmap
import android.util.Log
import com.scannerpromax.ai.AiEnhance
import com.scannerpromax.ai.AiLayout
import com.scannerpromax.ai.AiRecognizer
import com.scannerpromax.domain.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lectura de una página con la IA en la nube elegida (Android: codifica la imagen). La lógica de red/reintentos/
 * parseo/alineación es [CloudPipeline] (pura, probada en JVM).
 */
class CloudRecognizer(private val keys: CloudKeyStore, private val usage: CloudUsageStore) {

    private var cached: Triple<CloudProviderId, Int, CloudProvider>? = null

    /** Proveedor con la clave guardada (se reutiliza mientras la clave no cambie). */
    fun provider(id: CloudProviderId): CloudProvider {
        val key = keys.get(id) ?: throw CloudException.Auth(0, "no hay clave guardada")
        val hash = key.hashCode()
        cached?.let { (cid, h, p) -> if (cid == id && h == hash) return p }
        (cached?.third as? ClaudeHaikuProvider)?.close()
        val p = create(id, key)
        cached = Triple(id, hash, p)
        return p
    }

    /**
     * [page] = procesado (puede venir reducido) de una imagen de [fullW] x [fullH]; [mlkit] = OCR básico de la misma
     * página para situar el texto. null = salida inservible (el llamador conserva ML Kit). Lanza [CloudException]
     * para los fallos que el llamador gestiona (clave, saldo, red agotada).
     */
    suspend fun read(
        id: CloudProviderId,
        page: Bitmap,
        fullW: Int,
        fullH: Int,
        mlkit: OcrResult?,
        onProgress: (AiRecognizer.Progress) -> Unit,
    ): AiRecognizer.PageResult? {
        val provider = provider(id)
        onProgress(AiRecognizer.Progress(0.05f, "Preparando la imagen"))
        val req = withContext(Dispatchers.Default) { encode(page, provider.maxLongSide, provider.maxPixels) }
        return CloudPipeline.run(
            provider, req, fullW, fullH, mlkit,
            record = { t -> usage.record(id, t, page = true) },
            onProgress = onProgress,
        )
    }

    companion object {
        private const val TAG = "CloudRecognizer"
        private const val JPEG_QUALITY = 88

        fun create(id: CloudProviderId, key: String): CloudProvider = when (id) {
            CloudProviderId.DEEPSEEK -> DeepSeekProvider(key)
            CloudProviderId.CLAUDE_HAIKU -> ClaudeHaikuProvider(key)
        }

        /** JPEG reducido a lo que el proveedor aprovecha (más resolución solo encarece y retrasa la subida). */
        fun encode(src: Bitmap, maxLongSide: Int, maxPixels: Int): CloudRequest {
            val (w, h) = CloudPipeline.targetSize(src.width, src.height, maxLongSide, maxPixels)
            val scaled = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
            try {
                val out = ByteArrayOutputStream(w * h / 4)
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                Log.d(TAG, "imagen ${w}x$h, ${out.size() / 1024} KB")
                return CloudRequest(out.toByteArray(), w, h)
            } finally {
                if (scaled !== src) scaled.recycle()
            }
        }
    }
}

/** Red + reintentos + parseo + alineación (lógica pura, JVM). */
object CloudPipeline {

    /** Esperas entre intentos (ms) para 429/5xx/red; se respeta Retry-After si es mayor (tope 60 s). */
    val BACKOFF_MS = longArrayOf(2_000, 6_000, 20_000)

    fun targetSize(w: Int, h: Int, maxLongSide: Int, maxPixels: Int): Pair<Int, Int> {
        var s = min(1.0, maxLongSide.toDouble() / max(w, h))
        if (w.toDouble() * h * s * s > maxPixels) s = sqrt(maxPixels.toDouble() / (w.toDouble() * h))
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /**
     * Ejecuta [block] con reintentos para los fallos reintentables ([CloudException.retryable]); los demás salen al
     * momento. Si se agotan, relanza el último.
     */
    suspend fun <T> withRetries(
        backoff: LongArray = BACKOFF_MS,
        sleep: suspend (Long) -> Unit = { delay(it) },
        onRetry: (attempt: Int, e: CloudException) -> Unit = { _, _ -> },
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CloudException) {
                if (!e.retryable || attempt >= backoff.size) throw e
                val wait = max(backoff[attempt], (e as? CloudException.RateLimited)?.retryAfterMs ?: 0L).coerceAtMost(60_000)
                onRetry(attempt + 1, e)
                sleep(wait)
                attempt++
            }
        }
    }

    suspend fun run(
        provider: CloudProvider,
        req: CloudRequest,
        fullW: Int,
        fullH: Int,
        mlkit: OcrResult?,
        record: (CloudUsageTokens) -> Unit,
        onProgress: (AiRecognizer.Progress) -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): AiRecognizer.PageResult? {
        val name = provider.id.company
        onProgress(AiRecognizer.Progress(0.15f, "Enviando a $name"))
        val resp = withRetries(sleep = sleep, onRetry = { n, e ->
            onProgress(AiRecognizer.Progress(0.15f, "Reintentando ($n) · ${e.message}"))
        }) { provider.recognize(req) }
        record(resp.usage)
        onProgress(AiRecognizer.Progress(0.85f, "Colocando el texto"))
        val page = try {
            CloudJson.parse(resp.text, req.width, req.height)
        } catch (e: CloudJson.ParseException) {
            throw CloudException.BadResponse(e.message ?: "JSON")
        }
        val mlChars = mlkit?.let { AiLayout.letters(it.text) } ?: 0
        if (AiLayout.isGarbage(AiLayout.letters(page.text), mlChars, looped = false)) return null
        val docType = AiEnhance.docTypeCode(page.docType)
        val r = CloudAlign.align(page, fullW, fullH, mlkit, docType, engine = "nube:" + provider.id.model)
        onProgress(AiRecognizer.Progress(1f, "Listo"))
        val warning = r.warning ?: page.rotation.takeIf { it != 0 }?.let {
            "La IA cree que la página está girada ($it°); gírala en el editor y vuelve a leerla"
        }
        return AiRecognizer.PageResult(r.ocr, r.layout, warning)
    }
}
