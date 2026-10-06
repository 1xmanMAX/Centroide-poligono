package com.scannerpromax.ocr

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * OCR en el dispositivo con ML Kit (latino, empaquetado: funciona sin internet).
 *
 * Estrategia de máxima precisión:
 *  1. Normaliza la escala: imágenes pequeñas se amplían (lado largo ~2400 px) y enormes se reducen (~3000 px).
 *  2. Si tras la primera pasada las líneas son muy bajas (< 20 px), hace una segunda pasada ampliando
 *     para que el texto mida ~30 px de alto y se queda con el resultado que reconozca más texto.
 *  3. Todas las coordenadas se devuelven en píxeles de la imagen recibida.
 */
class OcrEngine(private val context: Context) {


    private val lowRam: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am == null || am.isLowRamDevice || am.memoryClass <= 192
    }

    /** OCR de alta calidad: pre-procesa (escala a ~ altura de letra óptima, contraste) y reconoce en el dispositivo. */
    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        require(!bitmap.isRecycled) { "Bitmap reciclado" }
        val w = bitmap.width
        val h = bitmap.height
        val long = max(w, h)
        val maxLong = if (lowRam) 2600 else 3400
        val maxPixels = if (lowRam) 6_000_000 else 11_000_000

        // Primera pasada a escala normalizada.
        val target = when {
            long < MIN_LONG_SIDE -> MIN_LONG_SIDE
            long > MAX_LONG_SIDE -> MAX_LONG_SIDE
            else -> long
        }
        var scale = target.toFloat() / long
        scale = clampScale(scale, w, h, maxLong, maxPixels)
        var best = runPass(bitmap, scale)

        // Segunda pasada si el texto es diminuto (letra pequeña en hoja completa, recibos lejanos...).
        val medianLine = medianLineHeight(best.text)
        if (medianLine in 1f..SMALL_TEXT_PX) {
            val wanted = scale * (TARGET_TEXT_PX / medianLine)
            val second = clampScale(wanted, w, h, maxLong, maxPixels)
            if (second > scale * 1.25f) {
                val pass2 = try { runPass(bitmap, second) } catch (e: OutOfMemoryError) { null }
                if (pass2 != null && letterCount(pass2.text) >= letterCount(best.text)) {
                    best = pass2
                }
            }
        }
        toResult(best.text, best.scale, w, h)
    }

    /**
     * Libera el reconocedor compartido (se vuelve a crear de forma perezosa si se usa otra vez).
     * Ojo: el cliente es único en el proceso; no llamar mientras otro OCR esté en curso.
     */
    fun close() {
        synchronized(Shared) {
            Shared.recognizer?.close()
            Shared.recognizer = null
        }
    }

    // ---------------------------------------------------------------------------------

    private class Pass(val text: Text, val scale: Float)

    /** Un único cliente ML Kit por proceso (el modelo empaquetado ocupa memoria: no se duplica entre instancias). */
    private object Shared {
        @Volatile var recognizer: TextRecognizer? = null
    }

    private fun client(): TextRecognizer = Shared.recognizer ?: synchronized(Shared) {
        Shared.recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { Shared.recognizer = it }
    }

    private suspend fun runPass(src: Bitmap, scale: Float): Pass {
        val input: Bitmap = if (kotlin.math.abs(scale - 1f) < 0.02f) src else {
            val nw = (src.width * scale).roundToInt().coerceAtLeast(1)
            val nh = (src.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, nw, nh, true)
        }
        val realScale = input.width.toFloat() / src.width
        try {
            // NonCancellable: ML Kit lee los píxeles en su propio hilo. Si dejáramos de esperar al cancelar, el
            // llamador reciclaría [src] (o nosotros [input]) mientras ML Kit aún lo usa -> "recycled bitmap"/crash.
            val text = withContext(NonCancellable) {
                client().process(InputImage.fromBitmap(input, 0)).await()
            }
            currentCoroutineContext().ensureActive()
            return Pass(text, realScale)
        } finally {
            if (input !== src) input.recycle()
        }
    }

    private fun clampScale(scale: Float, w: Int, h: Int, maxLong: Int, maxPixels: Int): Float {
        var s = scale
        val long = max(w, h)
        if (long * s > maxLong) s = maxLong.toFloat() / long
        val px = w.toFloat() * h * s * s
        if (px > maxPixels) s = sqrt(maxPixels / (w.toFloat() * h))
        return min(s, MAX_UPSCALE)
    }

    private fun medianLineHeight(text: Text): Float {
        val heights = text.textBlocks.flatMap { b -> b.lines.mapNotNull { it.boundingBox?.height()?.toFloat() } }
        if (heights.isEmpty()) return 0f
        val sorted = heights.sorted()
        return sorted[sorted.size / 2]
    }

    private fun letterCount(text: Text): Int = text.text.count { it.isLetterOrDigit() }

    private fun toResult(text: Text, scale: Float, w: Int, h: Int): OcrResult {
        val inv = 1f / scale
        fun map(r: Rect?): OcrRect {
            if (r == null) return OcrRect(0f, 0f, 0f, 0f)
            return OcrRect(
                (r.left * inv).coerceIn(0f, w.toFloat()),
                (r.top * inv).coerceIn(0f, h.toFloat()),
                (r.right * inv).coerceIn(0f, w.toFloat()),
                (r.bottom * inv).coerceIn(0f, h.toFloat()),
            )
        }
        val blocks = text.textBlocks
            .map { b ->
                val lines = b.lines
                    .filter { it.text.isNotBlank() }
                    .map { l -> OcrLine(l.text.trim(), map(l.boundingBox)) }
                OcrBlock(lines.joinToString("\n") { it.text }, map(b.boundingBox), lines)
            }
            .filter { it.lines.isNotEmpty() }
            .let { sortReadingOrder(it) }
        val full = blocks.joinToString("\n\n") { it.text }
        return OcrResult(text = full, imageWidth = w, imageHeight = h, blocks = blocks)
    }

    /**
     * Orden de lectura: de arriba a abajo; bloques que comparten franja vertical (columnas) de izquierda a derecha.
     */
    private fun sortReadingOrder(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        val sorted = blocks.sortedBy { it.box.top }
        val rows = ArrayList<MutableList<OcrBlock>>()
        for (b in sorted) {
            val row = rows.lastOrNull()
            val ref = row?.first()
            val overlap = if (ref == null) 0f else {
                val top = max(ref.box.top, b.box.top)
                val bottom = min(ref.box.bottom, b.box.bottom)
                val hMin = min(ref.box.bottom - ref.box.top, b.box.bottom - b.box.top).coerceAtLeast(1f)
                (bottom - top) / hMin
            }
            if (row != null && overlap > 0.5f) row += b else rows += mutableListOf(b)
        }
        return rows.flatMap { r -> r.sortedBy { it.box.left } }
    }

    private companion object {
        const val MIN_LONG_SIDE = 2400
        const val MAX_LONG_SIDE = 3000
        const val MAX_UPSCALE = 3f
        const val SMALL_TEXT_PX = 20f
        const val TARGET_TEXT_PX = 30f
    }
}

/** Espera una Task de Google Play Services sin depender de kotlinx-coroutines-play-services. */
internal suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }
