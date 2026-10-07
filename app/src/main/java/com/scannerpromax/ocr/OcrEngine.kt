package com.scannerpromax.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.imaging.DeviceProfiler
import com.scannerpromax.imaging.DeviceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * OCR en el dispositivo con ML Kit (latino, empaquetado: funciona sin internet).
 *
 * Estrategia de precisión, pensada para gama baja (un solo reconocedor, pasadas secuenciales):
 *  1. Escala normalizada (lado largo ~2400-3000 px). Si el filtro de la página conserva la iluminación de la foto
 *     (Original/Vívido/Aclarar/Auto) se normaliza la iluminación en gris antes de reconocer.
 *  2. Si las líneas son muy bajas (< 20 px) se vuelve a reconocer ampliando para que midan ~30 px; si la página
 *     entera ampliada no cabe en los límites (hoja densa con letra pequeña, tablas), por mosaicos solapados en gris
 *     a esa escala, fusionando los resultados sin duplicados ([OcrTiling]).
 *  3. Si la confianza media de las líneas es baja, segundo intento con una variante binarizada y se queda, línea a
 *     línea, con la de mayor confianza (en gama baja como mucho una pasada extra en total).
 *  4. Palabras con su caja (Text.Element) y ángulo de cada línea para la capa invisible precisa del PDF.
 *  5. Orden de lectura por columnas ([ReadingOrder]). Todas las coordenadas en píxeles de la imagen recibida.
 */
class OcrEngine(private val context: Context) {

    /** Con el perfil del dispositivo del contenedor (mismo criterio de gama baja que el resto de la app). */
    constructor(context: Context, tier: DeviceTier) : this(context) {
        deviceTier = tier
    }

    @Volatile private var deviceTier: DeviceTier? = null

    /** Asigna el perfil del dispositivo (lo llama el repositorio si el motor se creó sin él). */
    fun setDeviceTier(tier: DeviceTier) {
        deviceTier = tier
    }

    private fun tier(): DeviceTier = deviceTier ?: DeviceProfiler.profile(context).also { deviceTier = it }

    /** Gama baja con el mismo criterio que [DeviceTier] (RAM total y núcleos), no solo por memoryClass. */
    private val lowRam: Boolean get() = tier().let { it.isLowRam || it.cores <= 4 }

    /**
     * Resultado de la última pasada completada. Si el llamador cancela durante una pasada extra, aquí queda el
     * mejor resultado ya calculado para poder guardarlo (no se desperdician segundos de CPU).
     */
    class Partial {
        @Volatile var result: OcrResult? = null
    }

    /** OCR de alta calidad (sin información del filtro aplicado). */
    suspend fun recognize(bitmap: Bitmap): OcrResult = recognize(bitmap, null)

    /**
     * OCR de alta calidad. [filter] = filtro con el que se generó [bitmap] (decide el pre-proceso); null = ninguno.
     * No recicla [bitmap].
     */
    suspend fun recognize(bitmap: Bitmap, filter: FilterType?): OcrResult = recognize(bitmap, filter, false, null)

    /**
     * [lightweight] = OCR de fondo: entrada limitada (~3 MP en gama baja) y sin pasadas extra.
     * [partial] recibe el mejor resultado tras cada pasada completada (ver [Partial]).
     */
    suspend fun recognize(bitmap: Bitmap, filter: FilterType?, lightweight: Boolean, partial: Partial?): OcrResult = Gate.lock.withLock {
        withContext(Dispatchers.Default) {
            require(!bitmap.isRecycled) { "Bitmap reciclado" }
            val w = bitmap.width
            val h = bitmap.height
            val long = max(w, h)
            val low = lowRam
            val maxLong = if (low) 2400 else 3400
            val maxPixels = when {
                low && lightweight -> 3_000_000
                low -> 4_000_000
                else -> 11_000_000
            }
            val normalize = OcrPreprocess.needsNormalization(filter)
            var extraPasses = 0
            val maxExtra = when {
                lightweight && low -> 0
                low || lightweight -> 1
                else -> 2
            }

            val target = when {
                long < MIN_LONG_SIDE -> MIN_LONG_SIDE
                long > MAX_LONG_SIDE -> MAX_LONG_SIDE
                else -> long
            }
            var scale = clampScale(target.toFloat() / long, w, h, maxLong, maxPixels)
            var best = runPass(bitmap, scale, if (normalize) Variant.NORMALIZED else Variant.PLAIN)
            partial?.result = build(best.blocks, w, h)
            currentCoroutineContext().ensureActive()

            // Letra diminuta (hoja completa con letra pequeña, tablas densas, recibos lejanos...): ampliar. Si la página
            // entera ampliada no cabe (lado o píxeles), por MOSAICOS solapados a la escala buscada ([OcrTiling]).
            val medianLine = medianLineHeight(best.blocks)
            if (extraPasses < maxExtra && medianLine > 0f && medianLine * scale in 1f..SMALL_TEXT_PX) {
                val wanted = min(MAX_UPSCALE, TARGET_TEXT_PX / medianLine)
                val second = clampScale(wanted, w, h, maxLong, maxPixels)
                if (wanted > second * 1.2f && wanted > scale * 1.25f && !lightweight) {
                    currentCoroutineContext().ensureActive()
                    val tiled = try { runTiled(bitmap, wanted, medianLine, normalize, low, partial, w, h) } catch (e: OutOfMemoryError) { null }
                    extraPasses++
                    if (tiled != null && letterCount(tiled.blocks) >= letterCount(best.blocks)) {
                        best = tiled
                        scale = wanted
                        partial?.result = build(best.blocks, w, h)
                    }
                    currentCoroutineContext().ensureActive()
                } else if (second > scale * 1.25f) {
                    currentCoroutineContext().ensureActive()
                    val pass2 = try { runPass(bitmap, second, if (normalize) Variant.NORMALIZED else Variant.PLAIN) } catch (e: OutOfMemoryError) { null }
                    extraPasses++
                    if (pass2 != null && letterCount(pass2.blocks) >= letterCount(best.blocks)) {
                        best = pass2
                        scale = second
                        partial?.result = build(best.blocks, w, h)
                    }
                    currentCoroutineContext().ensureActive()
                }
            }

            // Confianza baja: variante binarizada y fusión línea a línea.
            val conf = meanConfidence(best.blocks)
            if (conf in 0f..LOW_CONFIDENCE && extraPasses < maxExtra) {
                currentCoroutineContext().ensureActive()
                val lineH = medianLineHeight(best.blocks) // en px de la imagen original
                val vScale = clampScale(scale, w, h, maxLong, maxPixels)
                val variant = try { runPass(bitmap, vScale, Variant.BINARIZED, lineH * vScale) } catch (e: OutOfMemoryError) { null }
                if (variant != null && variant.blocks.isNotEmpty()) {
                    best = Pass(mergeByConfidence(best.blocks, variant.blocks))
                    partial?.result = build(best.blocks, w, h)
                }
            }
            currentCoroutineContext().ensureActive()
            partial?.result ?: build(best.blocks, w, h)
        }
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

    private enum class Variant { PLAIN, GRAY, NORMALIZED, BINARIZED }

    /** Bloques en coordenadas de la imagen ORIGINAL recibida. */
    private class Pass(val blocks: List<OcrBlock>)

    /** Un único cliente ML Kit por proceso (el modelo empaquetado ocupa memoria: no se duplica entre instancias). */
    private object Shared {
        @Volatile var recognizer: TextRecognizer? = null
    }

    /** Un OCR a la vez en todo el proceso: en gama baja dos reconocimientos simultáneos agotan RAM y CPU. */
    private object Gate {
        val lock = Mutex()
    }

    private fun client(): TextRecognizer = Shared.recognizer ?: synchronized(Shared) {
        Shared.recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { Shared.recognizer = it }
    }

    /**
     * Una pasada de ML Kit. No comprueba la cancelación al terminar: el llamador guarda primero el resultado en
     * [Partial] y luego llama a ensureActive (así una cancelación no tira un reconocimiento ya pagado).
     */
    private suspend fun runPass(src: Bitmap, scale: Float, variant: Variant, lineHeightPx: Float = 0f): Pass {
        val scaled: Bitmap = if (abs(scale - 1f) < 0.02f) src else {
            val nw = (src.width * scale).roundToInt().coerceAtLeast(1)
            val nh = (src.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, nw, nh, true)
        }
        try {
            // Variantes en gris: se pasan a ML Kit como NV21 (plano Y + croma neutro, 1.5 B/px) en vez de un
            // Bitmap ARGB (4 B/px): menos memoria y menos copias en gama baja.
            val gray = when (variant) {
                Variant.PLAIN -> null
                Variant.GRAY -> OcrPreprocess.grayNv21(scaled)
                Variant.NORMALIZED -> OcrPreprocess.normalizeIlluminationNv21(scaled)
                Variant.BINARIZED -> OcrPreprocess.binarizeNv21(scaled, lineHeightPx)
            }
            val image: InputImage
            val realScale: Float
            if (gray != null) {
                if (scaled !== src) scaled.recycle()
                image = InputImage.fromByteArray(gray.nv21, gray.width, gray.height, 0, InputImage.IMAGE_FORMAT_NV21)
                realScale = gray.width.toFloat() / src.width
            } else {
                image = InputImage.fromBitmap(scaled, 0)
                realScale = scaled.width.toFloat() / src.width
            }
            // NonCancellable: ML Kit lee los píxeles en su propio hilo. Si dejáramos de esperar al cancelar, el
            // llamador reciclaría [src] (o nosotros [scaled]) mientras ML Kit aún lo usa -> "recycled bitmap"/crash.
            val text = withContext(NonCancellable) { client().process(image).await() }
            return Pass(convert(text, realScale, src.width, src.height))
        } finally {
            if (scaled !== src && !scaled.isRecycled) scaled.recycle()
        }
    }

    /**
     * Reconocimiento por mosaicos a [scale] (texto a ~[TARGET_TEXT_PX] px): cada mosaico mide como mucho
     * [TILE_SIDE] px ya ampliado y se solapa ~3 alturas de línea con sus vecinos; entrada en gris (normalizada si el
     * filtro conserva la iluminación). Los resultados se fusionan sin duplicados ([OcrTiling.merge]).
     */
    private suspend fun runTiled(
        src: Bitmap, scale: Float, lineH: Float, normalize: Boolean, low: Boolean, partial: Partial?, w: Int, h: Int,
    ): Pass? {
        var s = scale
        var tileSrc = (TILE_SIDE / s).toInt()
        val overlap = (lineH * 3f).toInt().coerceAtLeast(48)
        var tiles = OcrTiling.plan(w, h, tileSrc, overlap)
        // Presupuesto de tiempo: como mucho N mosaicos (menos escala si hiciera falta, nunca por debajo de 1.2x la base)
        val maxTiles = if (low) MAX_TILES_LOW else MAX_TILES
        while (tiles.size > maxTiles && s > 0.5f) {
            s *= 0.85f
            tileSrc = (TILE_SIDE / s).toInt()
            tiles = OcrTiling.plan(w, h, tileSrc, overlap)
        }
        if (tiles.size <= 1) return null
        val parts = ArrayList<Pair<OcrTiling.Tile, List<OcrBlock>>>(tiles.size)
        for (t in tiles) {
            currentCoroutineContext().ensureActive()
            val crop = Bitmap.createBitmap(src, t.x0, t.y0, t.width, t.height)
            try {
                val pass = runPass(crop, s, if (normalize) Variant.NORMALIZED else Variant.GRAY)
                parts.add(t to pass.blocks.map { OcrTiling.offset(it, t.x0.toFloat(), t.y0.toFloat()) })
            } finally {
                if (crop !== src) crop.recycle()
            }
        }
        return Pass(OcrTiling.merge(parts, w, h))
    }

    private fun clampScale(scale: Float, w: Int, h: Int, maxLong: Int, maxPixels: Int): Float {
        var s = scale
        val long = max(w, h)
        if (long * s > maxLong) s = maxLong.toFloat() / long
        val px = w.toFloat() * h * s * s
        if (px > maxPixels) s = sqrt(maxPixels / (w.toFloat() * h))
        return min(s, MAX_UPSCALE)
    }

    /** Altura mediana de línea en px de la imagen original (alto real, no envolvente). */
    private fun medianLineHeight(blocks: List<OcrBlock>): Float {
        val heights = blocks.flatMap { b -> b.lines.map { lineHeight(it) } }.filter { it > 0f }
        if (heights.isEmpty()) return 0f
        val sorted = heights.sorted()
        return sorted[sorted.size / 2]
    }

    private fun lineHeight(l: OcrLine): Float = com.scannerpromax.pdf.TextLayerGeometry.lineHeight(l.box, l.angle)

    private fun letterCount(blocks: List<OcrBlock>): Int =
        blocks.sumOf { b -> b.lines.sumOf { l -> l.text.count { it.isLetterOrDigit() } } }

    /** Confianza media ponderada por letras; -1 si el modelo no la informa. */
    private fun meanConfidence(blocks: List<OcrBlock>): Float {
        var sum = 0.0
        var n = 0
        for (b in blocks) for (l in b.lines) {
            if (l.confidence <= 0f) continue
            val letters = l.text.count { it.isLetterOrDigit() }.coerceAtLeast(1)
            sum += l.confidence * letters
            n += letters
        }
        return if (n == 0) -1f else (sum / n).toFloat()
    }

    /**
     * Fusiona dos reconocimientos de la misma imagen: cada línea de [a] se sustituye por la línea de [b] que la
     * solapa (IoU > 0.5) si esta tiene más confianza; las líneas de [b] sin pareja y muy fiables se añaden.
     */
    private fun mergeByConfidence(a: List<OcrBlock>, b: List<OcrBlock>): List<OcrBlock> {
        val bLines = b.flatMap { it.lines }
        val used = BooleanArray(bLines.size)
        val merged = a.map { block ->
            val lines = block.lines.map { la ->
                var bestIdx = -1
                var bestIou = 0.5f
                bLines.forEachIndexed { i, lb ->
                    if (used[i]) return@forEachIndexed // cada línea de [b] sustituye como mucho a una de [a]
                    val iou = iou(la.box, lb.box)
                    if (iou > bestIou) { bestIou = iou; bestIdx = i }
                }
                if (bestIdx >= 0) {
                    used[bestIdx] = true
                    val lb = bLines[bestIdx]
                    if (lb.confidence > la.confidence + 0.03f) lb else la
                } else la
            }
            block.copy(text = lines.joinToString("\n") { it.text }, lines = lines)
        }
        val extra = bLines.filterIndexed { i, l ->
            !used[i] && l.confidence >= ADD_CONFIDENCE && merged.none { blk -> blk.lines.any { overlapRatio(it.box, l.box) > 0.3f } }
        }.map { OcrBlock(it.text, it.box, listOf(it)) }
        return merged + extra
    }

    private fun iou(p: OcrRect, q: OcrRect): Float {
        val inter = interArea(p, q)
        val union = area(p) + area(q) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun overlapRatio(p: OcrRect, q: OcrRect): Float {
        val m = min(area(p), area(q))
        return if (m <= 0f) 0f else interArea(p, q) / m
    }

    private fun interArea(p: OcrRect, q: OcrRect): Float {
        val w = min(p.right, q.right) - max(p.left, q.left)
        val h = min(p.bottom, q.bottom) - max(p.top, q.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    private fun area(r: OcrRect) = max(0f, r.right - r.left) * max(0f, r.bottom - r.top)

    /** Convierte el resultado de ML Kit a bloques en coordenadas de la imagen original. */
    private fun convert(text: Text, scale: Float, w: Int, h: Int): List<OcrBlock> {
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
        return text.textBlocks.mapNotNull { b ->
            val lines = b.lines.filter { it.text.isNotBlank() }.map { l ->
                val words = l.elements.filter { it.text.isNotBlank() }.map { e ->
                    OcrWord(e.text.trim(), map(e.boundingBox), safeConfidence { e.confidence })
                }
                OcrLine(
                    text = l.text.trim(),
                    box = map(l.boundingBox),
                    words = words,
                    angle = lineAngle(l),
                    confidence = safeConfidence { l.confidence },
                )
            }
            if (lines.isEmpty()) null else OcrBlock(lines.joinToString("\n") { it.text }, map(b.boundingBox), lines)
        }
    }

    /** Ángulo (grados, horario positivo en la imagen) desde las esquinas reales; si faltan, el de ML Kit. */
    private fun lineAngle(l: Text.Line): Float {
        val pts = l.cornerPoints
        if (pts != null && pts.size >= 2) {
            val dx = (pts[1].x - pts[0].x).toDouble()
            val dy = (pts[1].y - pts[0].y).toDouble()
            if (dx * dx + dy * dy > 4.0) return Math.toDegrees(atan2(dy, dx)).toFloat()
        }
        return safeConfidence { l.angle }.takeIf { it.isFinite() && it > -90f && it < 90f } ?: 0f
    }

    /** Las versiones antiguas del modelo pueden no implementar confianza/ángulo: -1 = desconocido. */
    private inline fun safeConfidence(block: () -> Float): Float = try {
        block().takeIf { it.isFinite() } ?: -1f
    } catch (_: Throwable) {
        -1f
    }

    private fun build(blocks: List<OcrBlock>, w: Int, h: Int): OcrResult {
        val ordered = ReadingOrder.sort(blocks)
        val full = ordered.joinToString("\n\n") { it.text }
        return OcrResult(text = full, imageWidth = w, imageHeight = h, blocks = ordered)
    }

    private companion object {
        const val MIN_LONG_SIDE = 2400
        const val MAX_LONG_SIDE = 3000
        const val MAX_UPSCALE = 3f
        const val SMALL_TEXT_PX = 20f
        const val TARGET_TEXT_PX = 30f
        const val LOW_CONFIDENCE = 0.72f
        const val ADD_CONFIDENCE = 0.85f
        /** Lado máximo de un mosaico YA ampliado (px que recibe ML Kit). */
        const val TILE_SIDE = 2048f
        const val MAX_TILES = 16
        const val MAX_TILES_LOW = 6
    }
}

/** Espera una Task de Google Play Services sin depender de kotlinx-coroutines-play-services. */
internal suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }
