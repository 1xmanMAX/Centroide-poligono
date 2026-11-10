package com.scannerpromax.ui.camera

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.IdCardEdits
import com.scannerpromax.domain.IdCardSide
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.BookSplitter
import com.scannerpromax.imaging.CardColorDetector
import com.scannerpromax.imaging.CardOrientation
import com.scannerpromax.data.PageFiles
import com.scannerpromax.imaging.Cv
import com.scannerpromax.imaging.HeavyWork
import com.scannerpromax.imaging.IdCardComposer
import com.scannerpromax.imaging.ImageEnhancer
import com.scannerpromax.imaging.MultiFrameFusion
import com.scannerpromax.imaging.PerspectiveCorrector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Procesa las capturas según el modo (libro -> 2 páginas, DNI -> anverso+reverso en una hoja).
 * El trabajo pesado pasa por la puerta única [HeavyWork] (1 hilo de prioridad BACKGROUND, de uno en uno) para no
 * disparar la memoria en gama baja ni quitarle CPU a la vista previa. Nunca se llama al repositorio con el
 * permiso tomado (el repositorio tiene su propio limitador y podría adoptar la misma puerta).
 */
/** Cara del DNI aceptada y aún sin componer: su foto (temporal) y el recorte NORMALIZADO 0..1. */
internal class CardSource(val file: File, val side: IdCardSide) {
    constructor(file: File, quad: Quad, manual: Boolean) : this(file, IdCardSide(quad, manual.takeIf { it }))
    val quad: Quad get() = side.quad
    fun toSide() = side
}

/**
 * Detección de una cara del DNI: foto, recorte normalizado (ya orientado: la esquina inicial es la superior izquierda de
 * la cara), confianza, vista reducida opcional para el editor y la decisión de orientación (para el registro).
 */
internal class CardDraft(val file: File, val quad: Quad, val confidence: Float, val preview: Bitmap?, val orientation: String? = null)

internal class CaptureProcessor(private val container: AppContainer) {

    private val tier get() = container.deviceTier

    init {
        // Contexto para cargar el modelo de super-resolución (MAGIC_PRO) desde assets
        ImageEnhancer.init(container.appContext)
    }

    /**
     * Modo poca luz / anti-reflejos: fusiona la ráfaga [files] (MultiFrameFusion: alineación ORB/ECC, promedio
     * robusto y mínimo de luminancia en los brillos) en UN archivo que sigue el flujo normal. Con un solo
     * archivo lo devuelve tal cual. Borra las fotos de la ráfaga. Si la fusión falla, sigue con la primera foto.
     */
    suspend fun mergeBurst(files: List<File>, removeGlare: Boolean = true): File {
        if (files.size <= 1) return files.first()
        return HeavyWork.run {
                val out = container.documents.newCaptureFile()
                try {
                    val lowEnd = tier.isLowRam || tier.cores <= 4
                    // La foto fusionada pasa a ser el ORIGINAL de la página: JPEG 100 (una sola codificación antes
                    // del procesado) y la mayor resolución que la memoria permite fusionar (4 cuadros en RAM).
                    MultiFrameFusion.fuseFiles(
                        files.map { it.absolutePath }, out.absolutePath,
                        maxPixels = fusionMaxPixels(lowEnd),
                        removeGlare = removeGlare, jpegQuality = 100, lowEnd = lowEnd,
                    )
                    withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
                    out
                } catch (c: kotlinx.coroutines.CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) { out.delete(); files.forEach { it.delete() } }
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "No se pudo fusionar la ráfaga; se usa la primera foto", t)
                    withContext(NonCancellable + Dispatchers.IO) { out.delete(); files.drop(1).forEach { it.delete() } }
                    files.first()
                }
        }
    }

    /** Documento, recibo, pizarra, foto: el repositorio detecta bordes y aplica el filtro del modo. */
    suspend fun addStandard(docId: String, file: File, mode: ScanMode? = null) {
        try {
            container.documents.addPageFromFile(docId, file, mode = mode)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { if (file.exists()) file.delete() }
        }
    }

    /**
     * Libro abierto: recorta el libro completo, detecta el lomo y guarda las páginas izquierda y derecha.
     * Con el permiso pesado: decodificar (resolución completa dentro del presupuesto del equipo), recortar y
     * dividir; cada página se escribe UNA vez como JPEG 100 temporal y se libera. Sin el permiso: cada archivo se
     * entrega al repositorio, que lo MUEVE como original de la página (sin recodificar otra vez) y lo procesa.
     */
    suspend fun addBook(docId: String, file: File, edits: PageEdits) {
        var leftFile: File? = null
        var rightFile: File? = null
        try {
            HeavyWork.run {
                var bmp: Bitmap? = null
                var warped: Bitmap? = null
                var l: Bitmap? = null
                var r: Bitmap? = null
                try {
                    val src = BitmapIO.decode(file.absolutePath, tier.maxWorkingPixels)
                    bmp = src
                    val quad = detectQuad(src, minConfidence = 0.45f, minAreaFraction = 0.25f)
                    val base = if (quad != null) {
                        PerspectiveCorrector.warp(src, quad).also {
                            warped = it
                            // El original completo ya no hace falta: liberarlo antes de dividir baja el pico.
                            src.recycle(); bmp = null
                        }
                    } else src
                    val sp = BookSplitter.split(base)
                    l = sp.left; r = sp.right
                    warped?.recycle(); warped = null
                    bmp?.recycle(); bmp = null
                    val lf = container.documents.newCaptureFile().also { leftFile = it }
                    val rf = container.documents.newCaptureFile().also { rightFile = it }
                    withContext(Dispatchers.IO) {
                        BitmapIO.saveJpeg(sp.left, lf.absolutePath, 100)
                        sp.left.recycle(); l = null
                        BitmapIO.saveJpeg(sp.right, rf.absolutePath, 100)
                        sp.right.recycle(); r = null
                    }
                } finally {
                    listOfNotNull(bmp, warped, l, r).forEach { if (!it.isRecycled) it.recycle() }
                }
            }
            leftFile?.let { container.documents.addPageFromFile(docId, it, autoDetect = false, presetEdits = edits) }
            rightFile?.let { container.documents.addPageFromFile(docId, it, autoDetect = false, presetEdits = edits) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete(); leftFile?.delete(); rightFile?.delete() }
        }
    }

    /**
     * Detecta la tarjeta en la foto [file] de una cara del DNI SIN recortarla todavía (la foto se conserva: el usuario
     * confirma o corrige las esquinas, y la hoja se puede recomponer después). Orden: color de la tarjeta
     * ([CardColorDetector]), detector de documentos, marco guía [guide] (normalizado 0..1), foto completa.
     * [withPreview] = devolver además una vista reducida para el editor de esquinas (la recicla quien llama).
     */
    suspend fun detectCardSide(file: File, guide: RectF?, withPreview: Boolean, idCard: Boolean = true, deviceUp: Int = -1): CardDraft =
        HeavyWork.run {
            val maxPx = min(tier.maxWorkingPixels, 8_000_000)
            val src = BitmapIO.decode(file.absolutePath, maxPx)
            try {
                val (q, conf, why) = detectCardQuad(src, guide, idCard, deviceUp)
                val preview = if (withPreview) {
                    val t = BitmapIO.thumbnail(src, CARD_PREVIEW_SIDE)
                    if (t === src) src.copy(Bitmap.Config.ARGB_8888, false) else t
                } else null
                CardDraft(file, normalize(q, src.width, src.height), conf, preview, why)
            } finally {
                src.recycle()
            }
        }

    /** doc_ori (incluido en la app) sobre una cara RGB, para [CardOrientation]; null si no está. */
    private fun cardModel(): ((org.opencv.core.Mat) -> FloatArray?)? {
        val m = container.pageProcessor.orientationModel ?: return null
        return { mat ->
            val bmp = Cv.toBitmap(mat)
            try { m(bmp) } finally { bmp.recycle() }
        }
    }

    /**
     * Quad de la tarjeta en píxeles de [src], su confianza (0 = marco guía o foto completa) y la decisión de orientación.
     * [idCard] = cara de DNI (ID-1): se fuerza apaisada y derecha con [CardOrientation] (MRZ, cara de la foto, doc_ori;
     * [deviceUp] = posición del móvil, desempate); si no (cromos del Álbum), solo la MRZ como antes.
     */
    private fun detectCardQuad(src: Bitmap, guide: RectF?, idCard: Boolean, deviceUp: Int): Triple<Quad, Float, String?> {
        val g = guide?.let { doubleArrayOf(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()) }
        val rgb = try { Cv.toRgb(src) } catch (t: Throwable) { null }
        try {
            val color = try {
                rgb?.let { CardColorDetector.detect(it, g) }
            } catch (t: Throwable) {
                Log.w(TAG, "Fallo al detectar la tarjeta por color", t)
                null
            }
            // Cara del revés o de lado: se reordenan las esquinas para que el rectificado salga apaisado y derecho
            var why: String? = null
            fun oriented(q: Quad): Quad {
                rgb ?: return q
                val p = q.points()
                val arr = DoubleArray(8) { i -> (if (i % 2 == 0) p[i / 2].x else p[i / 2].y).toDouble() }
                val o = if (idCard) {
                    runCatching { CardOrientation.orient(rgb, arr, cardModel(), deviceUp) }
                        .onFailure { Log.w(TAG, "Fallo al orientar la tarjeta", it) }
                        .getOrNull()?.let { (oq, d) -> why = d.toString(); Log.i(TAG, "Orientación de la cara: $d"); oq } ?: arr
                } else CardColorDetector.orientByMrz(rgb, arr)
                return if (o === arr) q else Quad(Pt(o[0].toFloat(), o[1].toFloat()), Pt(o[2].toFloat(), o[3].toFloat()), Pt(o[4].toFloat(), o[5].toFloat()), Pt(o[6].toFloat(), o[7].toFloat()))
            }
            val edges = detectQuad(src, minConfidence = 0.4f, minAreaFraction = 0.04f)
            val edgesArr = edges?.points()?.let { p -> DoubleArray(8) { i -> (if (i % 2 == 0) p[i / 2].x else p[i / 2].y).toDouble() } }
            // árbitro: el recorte con más apoyo de bordes (el de color gana los empates)
            val chosen = if (rgb != null) CardColorDetector.choose(rgb, color, edgesArr, CARD_COLOR_MIN_CONF) else edgesArr
            if (chosen != null) {
                val conf = if (color != null && chosen === color.quad) color.confidence else 0.5f
                val q = Quad(Pt(chosen[0].toFloat(), chosen[1].toFloat()), Pt(chosen[2].toFloat(), chosen[3].toFloat()), Pt(chosen[4].toFloat(), chosen[5].toFloat()), Pt(chosen[6].toFloat(), chosen[7].toFloat()))
                val oq = oriented(q)
                return Triple(oq, conf, why)
            }
            // sin detección: el marco guía (o la foto completa), también orientado
            val r = guide ?: RectF(0f, 0f, 1f, 1f)
            val w = src.width.toFloat(); val h = src.height.toFloat()
            val l = r.left.coerceIn(0f, 1f) * w; val t = r.top.coerceIn(0f, 1f) * h
            val rr = r.right.coerceIn(0f, 1f) * w; val b = r.bottom.coerceIn(0f, 1f) * h
            val fq = Quad(Pt(l, t), Pt(rr, t), Pt(rr, b), Pt(l, b))
            val oq = if (idCard) oriented(fq) else fq
            return Triple(oq, 0f, why)
        } finally {
            rgb?.release()
        }
    }

    /**
     * Recorta una cara del DNI de su foto [file] con [quad] (normalizado) a la proporción EXACTA ID-1 (lado largo
     * [CARD_MAX_SIDE]; la hoja final es a 300 dpi) y la mejora con [enhance] (un filtro que conserve el color, p. ej.
     * VIVID) ANTES de componer la hoja: así el fondo de la tarjeta y la foto no se aplanan hacia blanco. No borra [file].
     */
    suspend fun renderCardSide(file: File, side: IdCardSide, enhance: FilterType?): Bitmap =
        renderCardSide(file, side.quad, side.effectiveFilter(enhance), side.turn, side.mirror == true)

    /**
     * [renderCardSide] con todo explícito: [enhance] = mejora (null = ninguna), [turn] = giro horario extra del usuario,
     * [mirror] = espejo. El recorte se lleva siempre al sentido horario (unas esquinas «cruzadas» a mano no dan espejo).
     */
    suspend fun renderCardSide(file: File, quad: Quad, enhance: FilterType?, turn: Int = 0, mirror: Boolean = false): Bitmap = withContext(Dispatchers.Default) {
        val side = HeavyWork.run {
            val maxPx = min(tier.maxWorkingPixels, 8_000_000)
            val src = BitmapIO.decode(file.absolutePath, maxPx)
            try {
                val p = denormalize(quad, src.width, src.height).points()
                val arr = CardOrientation.clockwise(DoubleArray(8) { i -> (if (i % 2 == 0) p[i / 2].x else p[i / 2].y).toDouble() })
                val rgba = Cv.toRgba(src)
                try {
                    val out = CardColorDetector.warpCard(rgba, arr, CARD_MAX_SIDE)
                    try {
                        turnAndMirror(out, turn, mirror)
                        Cv.toBitmap(out)
                    } finally { out.release() }
                } finally {
                    rgba.release()
                }
            } finally {
                src.recycle()
            }
        }
        var out = side
        if (enhance != null && enhance != FilterType.ORIGINAL) {
            try {
                val e = HeavyWork.run { ImageEnhancer.apply(side, enhance, Adjustments(), tier, false) }
                if (e !== side) { side.recycle(); out = e }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) { side.recycle(); throw t }
                Log.w(TAG, "No se pudo mejorar la cara de la tarjeta", t)
            }
        }
        out
    }

    /**
     * Cara ya renderizada (recorte + giro + espejo + mejora) desde la CACHÉ de la página si la hay (misma foto de origen
     * y mismas ediciones de la cara: [PageFiles.idSideKey]); si no, se renderiza y se guarda. Así recomponer la hoja
     * tras editar UNA cara no vuelve a procesar la otra. Devuelve el bitmap y su clave.
     */
    private suspend fun cachedCardSide(docId: String, pageId: String, source: File, side: IdCardSide, enhance: FilterType?): Pair<Bitmap, String> {
        val docs = container.documents
        val key = withContext(Dispatchers.IO) { PageFiles.idSideKey(side, enhance, PageFiles.fingerprint(source)) }
        val cache = docs.idRenderFile(docId, pageId, key)
        if (cache.exists()) {
            val b = withContext(Dispatchers.IO) { runCatching { BitmapIO.decode(cache.absolutePath, 0) }.getOrNull() }
            if (b != null) return b to key
        }
        val b = renderCardSide(source, side, enhance)
        saveCardCache(cache, b)
        return b to key
    }

    private suspend fun saveCardCache(f: File, b: Bitmap) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { f.parentFile?.mkdirs(); BitmapIO.saveJpeg(b, f.absolutePath, CARD_CACHE_QUALITY) }
            .onFailure { Log.w(TAG, "No se pudo guardar la cara en caché", it) }
    }

    /**
     * Compone el DNI (anverso [front] + reverso opcional [back]) en una hoja A4 y la añade como página, guardando las
     * fotos ORIGINALES de las caras y sus recortes ([PageEdits.idCard]) para poder corregirlos después en Revisión.
     * Las fotos temporales pasan al documento (o se borran si algo falla).
     */
    suspend fun addIdCard(docId: String, front: CardSource, back: CardSource?, enhance: FilterType?, edits: PageEdits) {
        var f: Bitmap? = null
        var b: Bitmap? = null
        try {
            val fb = renderCardSide(front.file, front.side, enhance).also { f = it }
            b = back?.let { renderCardSide(it.file, it.side, enhance) }
            // claves de la caché ANTES de mover las fotos al documento (huella del contenido)
            val keys = withContext(Dispatchers.IO) {
                listOfNotNull(front, back).map { s -> PageFiles.idSideKey(s.side, enhance, PageFiles.fingerprint(s.file)) }
            }
            // Componer con el permiso; entregar al repositorio sin él (no anidar puertas de trabajo pesado).
            val size = idCardSize()
            val page = HeavyWork.run { IdCardComposer.compose(fb, b, size) }
            val added = try {
                val card = IdCardEdits(front.toSide(), back?.toSide(), enhance)
                container.documents.addPageFromBitmap(docId, page, edits.copy(idCard = card), front.file, back?.file)
            } finally {
                page.recycle()
            }
            // caché de las caras ya renderizadas (editar una cara después no reprocesa la otra)
            saveCardCache(container.documents.idRenderFile(docId, added.id, keys[0]), fb)
            b?.let { saveCardCache(container.documents.idRenderFile(docId, added.id, keys[1]), it) }
        } finally {
            f?.recycle(); b?.recycle()
            withContext(NonCancellable + Dispatchers.IO) { front.file.delete(); back?.file?.delete() }
        }
    }

    /**
     * Corrige un DNI ya guardado con [card] (recorte, giro, espejo y mejora de cada cara) y recompone la hoja
     * ([com.scannerpromax.data.DocumentRepository.recomposeIdCard]). [sources] = foto de cada cara (anverso, reverso):
     * null = la guardada; otra = cara REEMPLAZADA (nueva foto) o caras intercambiadas. Solo se renderizan las caras que
     * cambiaron: las demás salen de la caché de la página ([cachedCardSide]).
     */
    suspend fun recomposeIdCard(docId: String, pageId: String, card: IdCardEdits, sources: List<File?>? = null) {
        val docs = container.documents
        var f: Bitmap? = null
        var b: Bitmap? = null
        try {
            val src0 = sources?.getOrNull(0) ?: docs.idSourceFile(docId, pageId, back = false)
            val src1 = card.back?.let { sources?.getOrNull(1) ?: docs.idSourceFile(docId, pageId, back = true) }
            val (fb, k0) = cachedCardSide(docId, pageId, src0, card.front, card.enhance).also { f = it.first }
            val r1 = if (card.back != null && src1 != null) cachedCardSide(docId, pageId, src1, card.back, card.enhance).also { b = it.first } else null
            val size = idCardSize()
            val page = HeavyWork.run { IdCardComposer.compose(fb, r1?.first, size) }
            try {
                docs.recomposeIdCard(docId, pageId, page, card, listOf(src0, src1), keepRenders = setOfNotNull(k0, r1?.second))
            } finally {
                page.recycle()
            }
        } finally {
            f?.recycle(); b?.recycle()
        }
    }

    /** «Tamaño del DNI en la hoja» (Ajustes › Escaneo); el de por defecto si no se puede leer. */
    private suspend fun idCardSize(): com.scannerpromax.imaging.IdCardSize =
        runCatching { container.settings.settings.first().idCardSize }.getOrDefault(com.scannerpromax.imaging.IdCardSize.DEFAULT)

    /** Galería: [uris] (1 o 2 imágenes: anverso y reverso) como DNI editable, con detección automática de cada cara. */
    suspend fun importIdCard(docId: String, uris: List<Uri>, enhance: FilterType?, edits: PageEdits) {
        val files = ArrayList<File>()
        try {
            val sources = uris.take(2).map { uri ->
                val file = copyToCaptureFile(uri).also { files += it }
                val d = detectCardSide(file, null, withPreview = false)
                CardSource(file, d.quad, manual = false)
            }
            addIdCard(docId, sources[0], sources.getOrNull(1), enhance, edits)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
        }
    }

    /** Copia un Uri (galería) a un archivo de captura temporal para procesarlo como una foto de la cámara. */
    suspend fun copyToCaptureFile(uri: Uri): File = withContext(Dispatchers.IO) {
        val f = container.documents.newCaptureFile()
        try {
            val input = container.appContext.contentResolver.openInputStream(uri) ?: throw java.io.IOException("No se pudo abrir la imagen")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            f
        } catch (t: Throwable) {
            f.delete()
            throw t
        }
    }

    /** Quad en coordenadas de [bmp] o null si no hay detección fiable / es casi la imagen completa. */
    private fun detectQuad(bmp: Bitmap, minConfidence: Float, minAreaFraction: Float): Quad? {
        val det = try {
            container.pageProcessor.detector.detect(bmp, useNeural = true)
        } catch (t: Throwable) {
            Log.w(TAG, "Fallo al detectar", t)
            null
        } ?: return null
        if (det.confidence < minConfidence || det.frameWidth <= 0 || det.frameHeight <= 0) return null
        val sx = bmp.width.toFloat() / det.frameWidth
        val sy = bmp.height.toFloat() / det.frameHeight
        val q = Quad.of(det.quad.points().map { Pt((it.x * sx).coerceIn(0f, bmp.width.toFloat()), (it.y * sy).coerceIn(0f, bmp.height.toFloat())) })
        val area = polygonArea(q)
        val total = bmp.width.toFloat() * bmp.height
        if (area < total * minAreaFraction) return null
        if (area > total * 0.985f) return null
        return q
    }

    private fun polygonArea(q: Quad): Float {
        val p = q.points()
        var s = 0f
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /**
     * Importa imágenes de la galería respetando el modo del documento: LIBRO divide cada foto en dos
     * páginas; DNI toma las imágenes de dos en dos (anverso + reverso, la impar va sola); el resto,
     * importación estándar del repositorio. [onProgress] recibe (hechas, total). Devuelve las imágenes
     * procesadas con éxito.
     */
    suspend fun importUris(
        docId: String,
        mode: ScanMode,
        uris: List<Uri>,
        settings: AppSettings,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        var ok = 0
        var done = 0
        val total = uris.size
        suspend fun guard(count: Int, block: suspend () -> Unit) {
            try {
                block()
                ok += count
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo importar una imagen", t)
            }
            done += count
            onProgress(done, total)
        }
        when (mode) {
            ScanMode.BOOK -> uris.forEach { uri ->
                guard(1) { addBook(docId, copyToCaptureFile(uri), composedEdits(mode, settings)) }
            }
            ScanMode.ID_CARD -> uris.chunked(2).forEach { pair ->
                guard(pair.size) { importIdCard(docId, pair, cardFilter(settings), composedEdits(mode, settings)) }
            }
            else -> uris.forEach { uri ->
                guard(1) { container.documents.addPagesFromUris(docId, listOf(uri), mode = mode) }
            }
        }
        return ok
    }

    /**
     * Píxeles de la fusión multi-cuadro (poca luz / anti-reflejos), que retiene 3-4 cuadros a la vez en memoria:
     * gama alta hasta 12.6 MP (la foto completa de 12 MP), gama media 8 MP. En gama baja (3 cuadros, 2-3 GB de
     * RAM) 6 MP: es la ÚNICA excepción al mínimo de 8 MP para documentos y solo afecta a las fotos fusionadas
     * (el disparo normal conserva la resolución completa).
     */
    private fun fusionMaxPixels(lowEnd: Boolean): Int = when {
        tier.isLowRam || lowEnd -> min(tier.maxWorkingPixels, 6_000_000)
        com.scannerpromax.imaging.DeviceProfiler.isHighEnd(tier) -> min(tier.maxWorkingPixels, 12_600_000)
        else -> min(tier.maxWorkingPixels, 8_000_000)
    }

    companion object {
        private const val TAG = "CaptureProcessor"
        private const val CARD_MAX_SIDE = 1600
        /** Calidad de la caché de caras renderizadas (una sola recodificación de una cara que no se edita). */
        private const val CARD_CACHE_QUALITY = 95
        /** Vista del editor de esquinas tras la captura (lado largo). */
        private const val CARD_PREVIEW_SIDE = 1600
        /** Confianza mínima del detector por color para fiarse de él sin mirar el detector de documentos. */
        const val CARD_COLOR_MIN_CONF = 0.5f

        /** Giro horario [turn] (90/180/270) y espejo horizontal de [m], en el sitio. */
        fun turnAndMirror(m: org.opencv.core.Mat, turn: Int, mirror: Boolean) {
            val code = when (((turn % 360) + 360) % 360) {
                90 -> org.opencv.core.Core.ROTATE_90_CLOCKWISE
                180 -> org.opencv.core.Core.ROTATE_180
                270 -> org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE
                else -> -1
            }
            if (code >= 0) {
                val t = org.opencv.core.Mat()
                org.opencv.core.Core.rotate(m, t, code)
                t.copyTo(m); t.release()
            }
            if (mirror) org.opencv.core.Core.flip(m, m, 1)
        }

        fun normalize(q: Quad, w: Int, h: Int): Quad =
            Quad.of(q.points().map { Pt((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) })

        fun denormalize(q: Quad, w: Int, h: Int): Quad = Quad.of(q.points().map { Pt(it.x * w, it.y * h) })

        /** Ediciones de las páginas que compone la cámara (libro y DNI); respeta el filtro de Ajustes. */
        fun composedEdits(mode: ScanMode, settings: AppSettings): PageEdits = when (mode) {
            // DNI: cada cara ya se mejoró por separado conservando el color; la hoja va sin filtro ni
            // limpieza (un filtro de documento aplanaría el fondo de la tarjeta y borraría sus líneas).
            ScanMode.ID_CARD -> PageEdits(
                quad = null,
                filter = FilterType.ORIGINAL,
                autoRemoveLines = false,
                autoDenoise = false,
                autoDeskew = false,
                autoDewarp = false,
            )
            else -> PageEdits(
                quad = null,
                filter = settings.defaultFilter,
                autoRemoveLines = settings.autoRemoveLines,
                autoDeskew = mode == ScanMode.BOOK,
            )
        }

        /** Filtro aplicado a cada cara del DNI antes de componer (conserva el color); null = sin mejora. */
        fun cardFilter(settings: AppSettings): FilterType? =
            if (settings.defaultFilter == FilterType.ORIGINAL) null else FilterType.VIVID
    }
}
