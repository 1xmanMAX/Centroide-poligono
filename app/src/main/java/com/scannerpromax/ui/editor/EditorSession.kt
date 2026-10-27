package com.scannerpromax.ui.editor

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.EraseStroke
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.DeviceProfiler
import com.scannerpromax.imaging.ImageEnhancer
import com.scannerpromax.imaging.PerspectiveCorrector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * Estado y trabajo en segundo plano del editor.
 *
 * - [edits] SIEMPRE en coordenadas de la imagen ORIGINAL (las que se guardan). El editor trabaja con una copia
 *   reducida del original ([source], limitada según el tier) y escala el quad con [workScale].
 * - Todo acceso nativo a los bitmaps pasa por [work] (Mutex): así se pueden reciclar con seguridad al salir
 *   aunque haya una vista previa a medio calcular, y en gama baja nunca hay dos procesos pesados a la vez.
 * - Las vistas previas usan collectLatest + debounce: los cambios rápidos de sliders cancelan los obsoletos.
 * - Vista previa PROGRESIVA: primero una versión pequeña (480 px en gama baja) del filtro sobre una geometría
 *   ya recortada en caché ([fastGeo], sin redecodificar ni rehacer la perspectiva), en ~decenas de ms; si el
 *   usuario deja de mover el control, se refina con la tubería completa a [previewSide].
 * - Las miniaturas de filtros se generan de una en una en un hilo de prioridad baja y ceden el paso a la
 *   vista previa (lo que el usuario está mirando).
 * - ÚNICA FUENTE DE VERDAD: al abrir se muestra al instante el PROCESADO guardado (el mismo archivo que la
 *   miniatura, el visor y la exportación), sin reprocesar; las vistas previas (aproximadas: tubería a resolución de
 *   pantalla) solo se calculan cuando el usuario cambia algo. Si las ediciones vuelven a las guardadas se muestra
 *   otra vez el procesado. En equipos con memoria, tras unos instantes sin tocar nada se calcula en segundo plano el
 *   RENDER FINAL real ([com.scannerpromax.data.DocumentRepository.renderDraft]) y se muestra en lugar de la
 *   aproximación ([previewFinal]); al guardar esas mismas ediciones se reutiliza sin volver a procesar.
 */
@Stable
internal class EditorSession(
    private val container: AppContainer,
    val docId: String,
    val pageId: String,
) {
    private val processor = container.pageProcessor
    private val tier = container.deviceTier
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val work = Mutex()

    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var page by mutableStateOf<Page?>(null)
        private set
    var edits by mutableStateOf(PageEdits())
        private set
    var savedEdits by mutableStateOf(PageEdits())
        private set
    var sourceImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Ediciones con las que se generó [preview] (para saber qué trazos ya están reflejados). */
    var previewEdits by mutableStateOf<PageEdits?>(null)
        private set
    var previewBusy by mutableStateOf(false)
        private set
    /** true = [preview] es el resultado FINAL real (procesado guardado o render final en segundo plano). */
    var previewFinal by mutableStateOf(false)
        private set
    /** Calculando el render final de las ediciones actuales en segundo plano. */
    var finalizing by mutableStateOf(false)
        private set
    /** ¿Se calcula el render final en segundo plano? (no en gama baja: memoria y CPU; allí se hace al guardar). */
    val backgroundFinal: Boolean = !tier.isLowRam && tier.cores > 4
    var saving by mutableStateOf(false)
        private set
    var detecting by mutableStateOf(false)
        private set
    val filterThumbs = mutableStateMapOf<FilterType, ImageBitmap>()

    /** Pila de rehacer para los trazos de borrado. */
    private val redoStack = ArrayList<EraseStroke>()
    var canRedo by mutableStateOf(false)
        private set

    private var source: Bitmap? = null
    private var thumbSource: Bitmap? = null
    /** Original muy reducido para la vista previa rápida, y su geometría (recorte+rotación) en caché. */
    private var fastSource: Bitmap? = null
    private var fastGeo: Bitmap? = null
    private var fastGeoKey: Any? = null
    /** Hay una vista previa pedida que aún no terminó (las miniaturas esperan). Solo se usa en Main. */
    private var previewPending = false
    /** Escala imagen de trabajo / original. */
    var workScale = 1f
        private set
    /**
     * Lado de la vista previa refinada, acorde a la pantalla (no un valor fijo): ~1450 px en FHD+, ~1930 px en
     * QHD+, 900-1280 px en gama baja. Así la imagen se ve nítida sin reescalados grandes en la GPU.
     */
    val previewSide: Int = container.appContext.resources.displayMetrics.let {
        DeviceProfiler.previewSideForScreen(tier, it.widthPixels, it.heightPixels)
    }
    /** Lado máximo al ampliar con los dedos (el refinado se rehace a más resolución al soltar el zoom). */
    private val maxDetailSide: Int = if (tier.isLowRam || tier.cores <= 4) 2048 else 4096
    /** Lado pedido para la vista previa refinada (sube con el zoom). */
    private val sideFlow = MutableStateFlow(previewSide)
    /** Lado con el que se generó la última vista previa refinada. Solo se usa en Main. */
    private var renderedSide = 0
    /** Lado de la vista previa rápida (primer paso, mientras se mueven sliders o se cambia de filtro). */
    private val fastSide: Int = if (tier.isLowRam || tier.cores <= 4) FAST_SIDE_LOW else FAST_SIDE

    private val editsFlow = MutableStateFlow<PageEdits?>(null)
    /** Pausa antes de refinar: si llega otro cambio antes, el refinado (caro) se cancela sin haber empezado. */
    private val refineDelayMs: Long = if (tier.isLowRam) 220L else 120L
    /** Render final ya disponible por ediciones (procesado guardado + borradores). Solo se usa en Main. */
    private val finalFiles = HashMap<PageEdits, File>()
    private var draftJob: Job? = null
    private var draftEdits: PageEdits? = null
    private var thumbsJob: Job? = null
    private var thumbsKey: Any? = null
    private var disposed = false

    val hasChanges: Boolean get() = edits != savedEdits
    val sourceWidth: Int get() = source?.width ?: 0
    val sourceHeight: Int get() = source?.height ?: 0

    fun load() {
        scope.launch {
            try {
                val p = container.documents.getPage(docId, pageId) ?: throw IOException("Página no encontrada")
                page = p
                edits = p.edits
                savedEdits = p.edits
                // 1) Lo primero que se ve: el procesado guardado (resultado final real), decodificado a la resolución
                //    de pantalla en ~100-300 ms. Nada de reprocesar al abrir.
                val processed = withContext(Dispatchers.IO) { container.documents.processedFileIfReady(docId, p) }
                if (processed != null) {
                    finalFiles[p.edits] = processed
                    val shown = withContext(Dispatchers.IO) {
                        runCatching { decodeFinal(processed, previewSide) }.onFailure { Log.w(TAG, "No se pudo abrir el procesado", it) }.getOrNull()
                    }
                    if (disposed) {
                        shown?.first?.recycle()
                        return@launch
                    }
                    if (shown != null) {
                        preview = shown.first.asImageBitmap()
                        previewEdits = p.edits
                        previewFinal = true
                        renderedSide = shown.second
                    }
                }
                // 2) Imagen de trabajo (recorte, vistas previas al editar), en segundo plano mientras ya se ve la hoja.
                val file = container.documents.originalFile(docId, p)
                // Imagen de trabajo del editor (recorte con lupa, vista previa y zoom): en gama alta la foto completa
                // (hasta 12.6 MP), en gama media 8 MP y en gama baja ~3 MP (vista previa de <= 1280 px). Lado largo
                // <= 4096 px: es el tamaño máximo de textura de muchas GPU (si no, el dibujo fallaría).
                val maxPx = when {
                    tier.isLowRam -> 3_000_000
                    DeviceProfiler.isHighEnd(tier) -> min(tier.maxWorkingPixels, 12_600_000)
                    else -> min(tier.maxWorkingPixels, 8_000_000)
                }
                val bmp = withContext(Dispatchers.Default) {
                    work.withLock {
                        val d = BitmapIO.decode(file.absolutePath, maxPx)
                        if (maxOf(d.width, d.height) > MAX_TEXTURE_SIDE) {
                            BitmapIO.thumbnail(d, MAX_TEXTURE_SIDE).also { d.recycle() }
                        } else d
                    }
                }
                // Mipmaps: al dibujarse reducida en pantalla (recorte, original) la GPU filtra sin dientes de sierra.
                bmp.setHasMipMap(true)
                if (disposed) {
                    bmp.recycle()
                    return@launch
                }
                source = bmp
                workScale = if (p.width > 0) bmp.width.toFloat() / p.width else 1f
                sourceImage = bmp.asImageBitmap()
                loading = false
                startPreviewPipeline()
                // Con el procesado ya en pantalla esto no calcula nada (mismas ediciones = resultado final).
                requestPreview()
                regenerateThumbs()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo abrir la página", t)
                loadError = t.message ?: "No se pudo abrir la página"
                loading = false
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ediciones
    // ---------------------------------------------------------------------------------------------

    /** Cambia las ediciones; [refresh] = recalcular la vista previa (no durante el arrastre de esquinas). */
    fun update(newEdits: PageEdits, refresh: Boolean = true) {
        edits = newEdits
        if (refresh) requestPreview()
    }

    fun requestPreview() {
        // Mismas ediciones ya pedidas: el StateFlow no emitiría y [previewPending] se quedaría en true para siempre
        // (las miniaturas de filtros esperarían sin fin).
        if (editsFlow.value == edits) return
        previewPending = true
        editsFlow.value = edits
    }

    /**
     * Zoom de la vista previa (1 = ajustada a la pantalla). Al ampliar, la vista previa refinada se rehace a más
     * resolución (hasta [maxDetailSide]) para que el detalle se vea nítido; al volver a 1x no se rehace nada.
     */
    fun setZoomDetail(zoom: Float) {
        sideFlow.value = if (zoom <= 1.25f) previewSide
        else (previewSide * zoom).toInt().coerceIn(previewSide, maxOf(previewSide, maxDetailSide))
    }

    fun setFilter(f: FilterType) = update(edits.copy(filter = f))
    fun setAdjustments(a: Adjustments) = update(edits.copy(adjustments = a))

    /**
     * Proporción ancho/alto de la imagen recortada+rotada ACTUAL, calculada desde la geometría (no desde la
     * última vista previa, que puede ir un paso por detrás si se gira dos veces seguidas).
     */
    private fun currentAspect(): Float {
        val p = page
        val pw = p?.width?.takeIf { it > 0 } ?: sourceWidth.coerceAtLeast(1)
        val ph = p?.height?.takeIf { it > 0 } ?: sourceHeight.coerceAtLeast(1)
        val (w, h) = edits.quad?.let { q ->
            runCatching { PerspectiveCorrector.estimateSize(q, pw, ph) }
                .map { it.first.toFloat() to it.second.toFloat() }
                .getOrDefault(pw.toFloat() to ph.toFloat())
        } ?: (pw.toFloat() to ph.toFloat())
        val swapped = edits.rotation == 90 || edits.rotation == 270
        val aw = if (swapped) h else w
        val ah = if (swapped) w else h
        return if (ah <= 0f) 1f else aw / ah
    }

    /**
     * Activa/desactiva el enderezado. Cambia la geometría (rotación de unos grados), así que los trazos de
     * borrado dejarían de coincidir: se quitan. Devuelve true si se quitaron trazos.
     */
    fun setAutoDeskew(enabled: Boolean): Boolean {
        if (edits.autoDeskew == enabled) return false
        val hadStrokes = edits.eraseStrokes.isNotEmpty()
        redoStack.clear(); canRedo = false
        update(edits.copy(autoDeskew = enabled, eraseStrokes = emptyList()))
        regenerateThumbs()
        return hadStrokes
    }

    /**
     * Activa/desactiva el enderezado de hoja curvada (malla de líneas). Mueve la geometría como el enderezado:
     * se quitan los trazos de borrado. Devuelve true si se quitaron trazos.
     */
    fun setAutoDewarp(enabled: Boolean): Boolean {
        if (edits.autoDewarp == enabled) return false
        val hadStrokes = edits.eraseStrokes.isNotEmpty()
        redoStack.clear(); canRedo = false
        update(edits.copy(autoDewarp = enabled, eraseStrokes = emptyList()))
        regenerateThumbs()
        return hadStrokes
    }

    /** Rota 90° y transforma los trazos de borrado para que sigan sobre la misma zona. */
    fun rotate(clockwise: Boolean) {
        val aspect = currentAspect()
        val rot = ((edits.rotation + if (clockwise) 90 else 270) % 360 + 360) % 360
        val strokes = edits.eraseStrokes.map { s ->
            EraseStroke(
                points = s.points.map { p -> if (clockwise) Pt(1f - p.y, p.x) else Pt(p.y, 1f - p.x) },
                radius = s.radius * aspect,
                mode = s.mode,
            )
        }
        redoStack.clear(); canRedo = false
        update(edits.copy(rotation = rot, eraseStrokes = strokes))
        regenerateThumbs()
    }

    fun addStroke(stroke: EraseStroke) {
        redoStack.clear(); canRedo = false
        update(edits.copy(eraseStrokes = edits.eraseStrokes + stroke))
    }

    fun undoStroke() {
        val list = edits.eraseStrokes
        if (list.isEmpty()) return
        redoStack.add(list.last()); canRedo = true
        update(edits.copy(eraseStrokes = list.dropLast(1)))
    }

    fun redoStroke() {
        if (redoStack.isEmpty()) return
        val s = redoStack.removeAt(redoStack.size - 1)
        canRedo = redoStack.isNotEmpty()
        update(edits.copy(eraseStrokes = edits.eraseStrokes + s))
    }

    fun clearStrokes() {
        redoStack.clear(); canRedo = false
        update(edits.copy(eraseStrokes = emptyList()))
    }

    /** Quad en coordenadas de la imagen de trabajo (null = página completa). */
    fun workingQuad(): Quad? = edits.quad?.let { scaleQuad(it, workScale) }

    /** Fija el quad desde coordenadas de trabajo. */
    fun setWorkingQuad(q: Quad?, refresh: Boolean = false) {
        val original = q?.let { scaleQuad(it, 1f / workScale) }
        update(edits.copy(quad = original), refresh)
    }

    /** Resultado de validar el recorte antes de guardarlo. */
    enum class QuadCheck { OK, FIXED, INVALID }

    /**
     * Comprueba que el recorte sea un cuadrilátero convexo. Si las esquinas solo están desordenadas
     * (cruzadas), las reordena (tl, tr, br, bl); si ni así es válido devuelve INVALID y no toca nada.
     * Un quad inválido produciría un warp de perspectiva retorcido o espejado.
     */
    fun checkQuad(): QuadCheck {
        val q = edits.quad ?: return QuadCheck.OK
        if (isConvexQuad(q.points().map { androidx.compose.ui.geometry.Offset(it.x, it.y) })) return QuadCheck.OK
        val fixed = reorderQuad(q) ?: return QuadCheck.INVALID
        update(edits.copy(quad = fixed), refresh = false)
        return QuadCheck.FIXED
    }

    /** Detección automática de bordes sobre la imagen de trabajo. Devuelve true si encontró el documento. */
    suspend fun autoDetect(): Boolean {
        val src = source ?: return false
        detecting = true
        try {
            val det = withContext(Dispatchers.Default) {
                work.withLock { if (src.isRecycled) null else processor.detector.detect(src, useNeural = true) }
            } ?: return false
            if (det.confidence < 0.2f || det.frameWidth <= 0 || det.frameHeight <= 0) return false
            val sx = src.width.toFloat() / det.frameWidth
            val sy = src.height.toFloat() / det.frameHeight
            val q = Quad.of(det.quad.points().map { Pt((it.x * sx).coerceIn(0f, src.width.toFloat()), (it.y * sy).coerceIn(0f, src.height.toFloat())) })
            setWorkingQuad(q, refresh = false)
            return true
        } finally {
            detecting = false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Vista previa y miniaturas de filtros
    // ---------------------------------------------------------------------------------------------

    private fun startPreviewPipeline() {
        scope.launch {
            combine(editsFlow.filterNotNull(), sideFlow) { e, side -> e to side }.collectLatest { (e, side) ->
                // Render final en curso de OTRAS ediciones: ya no sirve.
                if (draftEdits != null && draftEdits != e) cancelDraft()
                // a) Resultado final ya disponible (procesado guardado o borrador): se muestra TAL CUAL.
                val final = finalFiles[e]?.takeIf { it.exists() }
                if (final != null) {
                    if (previewFinal && e == previewEdits && side <= renderedSide) {
                        previewPending = false
                        return@collectLatest
                    }
                    previewBusy = true
                    try {
                        val shown = withContext(Dispatchers.IO) { runCatching { decodeFinal(final, side) }.getOrNull() }
                        if (shown != null) {
                            preview = shown.first.asImageBitmap()
                            previewEdits = e
                            previewFinal = true
                            renderedSide = shown.second
                            previewPending = false
                            return@collectLatest
                        }
                        finalFiles.remove(e)
                    } finally {
                        previewBusy = false
                    }
                }
                // b) Vista previa aproximada (rápida + refinada) y, después, el render final en segundo plano.
                // Solo cambió el zoom y la vista previa actual ya tiene resolución suficiente: nada que hacer.
                if (e == previewEdits && side <= renderedSide) {
                    previewPending = false
                    return@collectLatest
                }
                delay(PREVIEW_DEBOUNCE_MS)
                val src = source ?: return@collectLatest
                previewBusy = true
                try {
                    // Paso 1 (rápido): solo filtro + ajustes sobre la geometría pequeña en caché. Se omite si hay
                    // trazos de borrado o "quitar rayas" (se verían aparecer y desaparecer).
                    val fastOk = e.eraseStrokes.isEmpty() && !e.autoRemoveLines && e != previewEdits
                    if (fastOk) {
                        val fast = withContext(Dispatchers.Default) {
                            work.withLock { if (src.isRecycled) null else fastPreview(src, e) }
                        }
                        if (fast != null) {
                            preview = fast.asImageBitmap()
                            previewFinal = false
                        }
                        // Si el usuario sigue moviendo el control, collectLatest cancela aquí y nunca se paga
                        // la vista previa completa de un valor intermedio.
                        delay(refineDelayMs)
                    }
                    // Paso 2 (refinado): tubería completa a la resolución de vista previa.
                    val working = e.copy(quad = e.quad?.let { scaleQuad(it, workScale) })
                    val bmp = withContext(Dispatchers.Default) {
                        work.withLock { if (src.isRecycled) null else processor.preview(src, working, side) }
                    }
                    if (bmp != null) {
                        bmp.setHasMipMap(true)
                        preview = bmp.asImageBitmap()
                        previewEdits = e
                        previewFinal = false
                        renderedSide = side
                    }
                    previewPending = false
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "Fallo en la vista previa", t)
                    previewPending = false
                } finally {
                    previewBusy = false
                }
                startDraft(e)
            }
        }
    }

    /**
     * Render FINAL de [e] en segundo plano tras [DRAFT_IDLE_MS] sin cambios (trabajo propio, fuera de collectLatest
     * para no bloquear la siguiente vista previa). Al terminar sustituye a la vista previa aproximada si las ediciones
     * siguen siendo esas.
     */
    private fun startDraft(e: PageEdits) {
        if (!backgroundFinal || disposed) return
        if (draftEdits == e && draftJob?.isActive == true) return
        cancelDraft()
        draftEdits = e
        draftJob = scope.launch {
            delay(DRAFT_IDLE_MS)
            finalizing = true
            try {
                val f = container.documents.renderDraft(docId, pageId, e)
                finalFiles[e] = f
                if (edits == e && !disposed) {
                    val side = sideFlow.value
                    val shown = withContext(Dispatchers.IO) { runCatching { decodeFinal(f, side) }.getOrNull() }
                    if (shown != null && edits == e) {
                        preview = shown.first.asImageBitmap()
                        previewEdits = e
                        previewFinal = true
                        renderedSide = shown.second
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo calcular el resultado final", t)
            } finally {
                if (draftEdits == e) {
                    finalizing = false
                    draftEdits = null
                }
            }
        }
    }

    private fun cancelDraft() {
        draftJob?.cancel()
        draftJob = null
        draftEdits = null
        finalizing = false
    }

    /**
     * Decodifica un render final a lado largo <= [side] (mipmaps para el zoom). Devuelve el bitmap y el lado
     * "cubierto" (si el archivo cabe entero en [side], ya está a resolución completa: no hace falta rehacerlo).
     */
    private fun decodeFinal(file: File, side: Int): Pair<Bitmap, Int> {
        val bmp = BitmapIO.decodeThumbnail(file.absolutePath, side.coerceAtMost(MAX_TEXTURE_SIDE))
        bmp.setHasMipMap(true)
        val (w, h) = BitmapIO.decodeBounds(file.absolutePath)
        val covered = if (maxOf(bmp.width, bmp.height) >= maxOf(w, h)) Int.MAX_VALUE else side
        return bmp to covered
    }

    /** Vista previa rápida: filtro sobre [fastGeo] (se rehace solo si cambia la geometría). Llamar bajo [work]. */
    private fun fastPreview(src: Bitmap, e: PageEdits): Bitmap {
        val key = listOf(e.quad, e.rotation, e.autoDeskew, e.autoDewarp)
        var geo = fastGeo
        if (geo == null || geo.isRecycled || fastGeoKey != key) {
            geo?.recycle()
            fastGeo = null
            val small = fastSource?.takeIf { !it.isRecycled }
                ?: BitmapIO.thumbnail(src, (fastSide * 1.25f).toInt()).also { fastSource = it }
            val pw = page?.width?.takeIf { it > 0 } ?: src.width
            val f = small.width.toFloat() / pw
            geo = processor.geometryOnly(small, e.copy(quad = e.quad?.let { scaleQuad(it, f) }), 0)
            fastGeo = geo
            fastGeoKey = key
        }
        return ImageEnhancer.apply(geo, e.filter, e.adjustments, tier, true, e.keepRuling)
    }

    /** Miniaturas de cada filtro (~200 px) en segundo plano. Solo se rehacen si cambia la geometría. */
    fun regenerateThumbs() {
        val e = edits
        val key = Triple(e.quad, e.rotation, source?.generationId)
        if (key == thumbsKey && FilterType.visible.all { filterThumbs.containsKey(it) }) return
        thumbsKey = key
        thumbsJob?.cancel()
        thumbsJob = scope.launch {
            val src = source ?: return@launch
            val page = page ?: return@launch
            while (previewPending) delay(THUMB_YIELD_MS)
            val base = try {
                withContext(EditorDispatchers.lowPriority) {
                    work.withLock {
                        if (src.isRecycled) return@withLock null
                        val small = thumbSource ?: BitmapIO.thumbnail(src, THUMB_SOURCE_SIDE).also { thumbSource = it }
                        val f = if (page.width > 0) small.width.toFloat() / page.width else 1f
                        val geo = e.copy(quad = e.quad?.let { scaleQuad(it, f) }, autoDeskew = false, autoDewarp = false)
                        processor.geometryOnly(small, geo, 0)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudieron crear las miniaturas", t)
                null
            } ?: return@launch
            try {
                // Sólo los filtros visibles; primero el actual (lo que el usuario mira), luego el resto.
                val current = e.filter.uiFilter
                val order = listOf(current) + FilterType.visible.filter { it != current }
                for (f in order) {
                    ensureActive()
                    // Cede el paso: mientras haya una vista previa pendiente no se compite por la CPU ni por [work].
                    while (previewPending) delay(THUMB_YIELD_MS)
                    val t = withContext(EditorDispatchers.lowPriority) {
                        work.withLock { ImageEnhancer.apply(base, f, Adjustments(), tier, true) }
                    }
                    filterThumbs[f] = t.asImageBitmap()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Fallo en miniatura de filtro", t)
            } finally {
                withContext(NonCancellable + Dispatchers.Default) { work.withLock { base.recycle() } }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Guardar / liberar
    // ---------------------------------------------------------------------------------------------

    /** Guarda las ediciones (render a calidad completa en el repositorio). */
    suspend fun save(): Boolean {
        if (saving) return false
        saving = true
        try {
            // Un render final en curso de ESTAS ediciones se espera (updateEdits lo reutiliza: guardar es instantáneo);
            // uno de otras ediciones se cancela.
            if (draftEdits == edits) draftJob?.join() else cancelDraft()
            // Libera la caché de vista previa antes del render completo (menos memoria en gama baja).
            withContext(Dispatchers.Default) { work.withLock { processor.clearPreviewCache() } }
            val saved = container.documents.updateEdits(docId, pageId, edits)
            savedEdits = edits
            page = saved
            withContext(Dispatchers.IO) { container.documents.processedFileIfReady(docId, saved) }?.let { finalFiles[edits] = it }
            return true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo guardar", t)
            return false
        } finally {
            saving = false
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        // Borradores del render final que no se guardaron (los del procesado actual se conservan).
        if (finalFiles.size > 1 || draftEdits != null) {
            val repo = container.documents
            CoroutineScope(Dispatchers.IO).launch { runCatching { repo.discardDrafts(docId, pageId) } }
        }
        val src = source
        val ts = thumbSource
        val fs = fastSource
        val fg = fastGeo
        source = null
        thumbSource = null
        fastSource = null
        fastGeo = null
        // Reciclar cuando termine cualquier trabajo nativo en curso.
        CoroutineScope(Dispatchers.Default).launch {
            work.withLock {
                processor.clearPreviewCache()
                fg?.recycle()
                fs?.recycle()
                ts?.recycle()
                src?.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "EditorSession"
        /** Debounce corto: el primer paso es barato, así que se puede reaccionar casi al instante. */
        private const val PREVIEW_DEBOUNCE_MS = 40L
        /** Sin cambios durante este tiempo -> se calcula el render final en segundo plano. */
        private const val DRAFT_IDLE_MS = 900L
        private const val THUMB_SOURCE_SIDE = 280
        private const val THUMB_YIELD_MS = 60L
        private const val FAST_SIDE_LOW = 480
        private const val FAST_SIDE = 640
        /** Tamaño máximo de textura seguro en GPU antiguas. */
        private const val MAX_TEXTURE_SIDE = 4096

        fun scaleQuad(q: Quad, f: Float): Quad = Quad.of(q.points().map { Pt(it.x * f, it.y * f) })

        /** Ordena 4 puntos por ángulo alrededor del centroide empezando por arriba-izquierda; null si no es convexo. */
        fun reorderQuad(q: Quad): Quad? {
            val pts = q.points()
            val cx = pts.sumOf { it.x.toDouble() } / 4.0
            val cy = pts.sumOf { it.y.toDouble() } / 4.0
            // En coordenadas de pantalla (y hacia abajo) atan2 creciente = sentido horario.
            val sorted = pts.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
            val ordered = List(4) { sorted[(start + it) % 4] }
            val ok = isConvexQuad(ordered.map { androidx.compose.ui.geometry.Offset(it.x, it.y) })
            return if (ok) Quad.of(ordered) else null
        }
    }
}
