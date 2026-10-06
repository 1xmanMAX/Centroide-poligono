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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /** Escala imagen de trabajo / original. */
    var workScale = 1f
        private set
    val previewSide: Int = if (tier.isLowRam) min(900, DeviceProfiler.previewSide(tier)) else DeviceProfiler.previewSide(tier)

    private val editsFlow = MutableStateFlow<PageEdits?>(null)
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
                val file = container.documents.originalFile(docId, p)
                val maxPx = if (tier.isLowRam) 3_000_000 else min(tier.maxWorkingPixels, 6_000_000)
                val bmp = withContext(Dispatchers.Default) {
                    work.withLock { BitmapIO.decode(file.absolutePath, maxPx) }
                }
                if (disposed) {
                    bmp.recycle()
                    return@launch
                }
                source = bmp
                workScale = if (p.width > 0) bmp.width.toFloat() / p.width else 1f
                sourceImage = bmp.asImageBitmap()
                loading = false
                startPreviewPipeline()
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
        editsFlow.value = edits
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
                work.withLock { if (src.isRecycled) null else processor.detector.detect(src) }
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
            editsFlow.filterNotNull().collectLatest { e ->
                delay(PREVIEW_DEBOUNCE_MS)
                val src = source ?: return@collectLatest
                previewBusy = true
                try {
                    val working = e.copy(quad = e.quad?.let { scaleQuad(it, workScale) })
                    val bmp = withContext(Dispatchers.Default) {
                        work.withLock { if (src.isRecycled) null else processor.preview(src, working, previewSide) }
                    }
                    if (bmp != null) {
                        preview = bmp.asImageBitmap()
                        previewEdits = e
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "Fallo en la vista previa", t)
                } finally {
                    previewBusy = false
                }
            }
        }
    }

    /** Miniaturas de cada filtro (~200 px) en segundo plano. Solo se rehacen si cambia la geometría. */
    fun regenerateThumbs() {
        val e = edits
        val key = Triple(e.quad, e.rotation, source?.generationId)
        if (key == thumbsKey && filterThumbs.size == FilterType.entries.size) return
        thumbsKey = key
        thumbsJob?.cancel()
        thumbsJob = scope.launch {
            val src = source ?: return@launch
            val page = page ?: return@launch
            val base = try {
                withContext(Dispatchers.Default) {
                    work.withLock {
                        if (src.isRecycled) return@withLock null
                        val small = thumbSource ?: BitmapIO.thumbnail(src, THUMB_SOURCE_SIDE).also { thumbSource = it }
                        val f = if (page.width > 0) small.width.toFloat() / page.width else 1f
                        val geo = e.copy(quad = e.quad?.let { scaleQuad(it, f) }, autoDeskew = false)
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
                // Primero el filtro actual (lo que el usuario mira), luego el resto.
                val order = listOf(e.filter) + FilterType.entries.filter { it != e.filter }
                for (f in order) {
                    ensureActive()
                    val t = withContext(Dispatchers.Default) {
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
            // Libera la caché de vista previa antes del render completo (menos memoria en gama baja).
            withContext(Dispatchers.Default) { work.withLock { processor.clearPreviewCache() } }
            container.documents.updateEdits(docId, pageId, edits)
            savedEdits = edits
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
        val src = source
        val ts = thumbSource
        source = null
        thumbSource = null
        // Reciclar cuando termine cualquier trabajo nativo en curso.
        CoroutineScope(Dispatchers.Default).launch {
            work.withLock {
                processor.clearPreviewCache()
                ts?.recycle()
                src?.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "EditorSession"
        private const val PREVIEW_DEBOUNCE_MS = 110L
        private const val THUMB_SOURCE_SIDE = 280

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
