package com.scannerpromax.ui.review

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.scannerpromax.ui.theme.LocalPerf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.ui.graphics.FilterQuality
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.camera.CaptureProcessor
import com.scannerpromax.ui.components.ConfirmDialog
import com.scannerpromax.ui.components.RenameDialog
import com.scannerpromax.ui.components.pagesLabel
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.CheckRow
import com.scannerpromax.ui.components.EmptyMessage
import com.scannerpromax.ui.components.FloatingBar
import com.scannerpromax.ui.components.FloatingBarContentPadding
import com.scannerpromax.ui.components.FloatingBarItem
import com.scannerpromax.ui.components.GroupArrangement
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.IosSheet
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.MoreMenu
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.ProgressHud
import com.scannerpromax.ui.components.SeparatorInsetIcon
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.SheetHeader
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.ui
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.scannerpromax.data.VersionedFile
import com.scannerpromax.ai.AiRecognitionQueue
import com.scannerpromax.ai.RecognitionEngine

/** Ámbito de proceso para borrados diferidos (Deshacer): el borrado se completa aunque se salga de la pantalla. */
private val reviewAppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    container: AppContainer,
    docId: String,
    onBack: () -> Unit,
    onEditPage: (pageId: String) -> Unit,
    onAddPages: (ScanMode) -> Unit,
    /** [pageIds] = solo esas páginas (modo selección), en el orden del documento; null = todas. */
    onExport: (pageIds: List<String>?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val repo = container.documents
    val snackbar = remember { SnackbarHostState() }

    val doc by remember(docId) { repo.observe(docId) }.collectAsState(initial = null)
    var loaded by remember { mutableStateOf(false) }
    var seenDoc by remember { mutableStateOf(false) }
    LaunchedEffect(docId) {
        val d = repo.get(docId)
        loaded = true
        if (d == null) {
            Toast.makeText(context, "El documento ya no existe", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
    // Si el documento se borra o se fusiona en otro mientras esta pantalla sigue abierta, se sale.
    LaunchedEffect(doc, loaded) {
        if (doc != null) seenDoc = true
        else if (loaded && seenDoc) {
            Toast.makeText(context, "El documento ya no existe", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    // Orden local (permite arrastrar sin esperar al disco).
    val order = remember { mutableStateListOf<Page>() }
    /** Página abierta en el visor a pantalla completa (índice en [order]), o null. */
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    /**
     * Página desde cuyo visor se abrió el editor: al volver se reabre el visor en ella y se ve el NUEVO procesado
     * (rememberSaveable: sobrevive a que la pantalla salga de la composición mientras el editor está abierto).
     */
    var reopenViewerPageId by rememberSaveable { mutableStateOf<String?>(null) }
    // Páginas eliminadas a la espera de "Deshacer" (no se muestran aunque el repositorio aún las tenga).
    val pendingDeletes = remember { mutableStateListOf<String>() }
    // Modo selección (estilo Fotos de iOS): pulsación larga en una página. Sobrevive a ir a Exportar y volver.
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf(emptyList<String>()) }
    val gridState = rememberLazyGridState()
    val reorder = remember(gridState) {
        GridReorderState(gridState) { fromKey, toKey ->
            val from = order.indexOfFirst { it.id == fromKey }
            val to = order.indexOfFirst { it.id == toKey }
            if (from >= 0 && to >= 0 && from != to) order.add(to, order.removeAt(from))
        }
    }
    fun syncOrder() {
        val pages = doc?.pages ?: return
        order.clear()
        order.addAll(pages.filter { it.id !in pendingDeletes })
    }
    LaunchedEffect(doc?.pages, pendingDeletes.size) {
        if (reorder.draggingKey == null) syncOrder()
        // Las páginas que ya no existen dejan de estar seleccionadas.
        if (doc != null) selectedIds = PageSelection.prune(selectedIds, order.map { it.id })
    }

    // Auto-scroll al arrastrar una página cerca del borde superior/inferior de la rejilla.
    LaunchedEffect(reorder.draggingKey) {
        if (reorder.draggingKey == null) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val maxSpeed = with(density) { 14.dp.toPx() }
        while (isActive && reorder.draggingKey != null) {
            val h = gridState.layoutInfo.viewportSize.height.toFloat()
            val y = reorder.pointer.y
            val speed = when {
                h <= 0f -> 0f
                y < edge -> -maxSpeed * ((edge - y) / edge).coerceIn(0f, 1f)
                y > h - edge -> maxSpeed * ((y - (h - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) {
                gridState.scrollBy(speed)
                reorder.drag(Offset.Zero)
            }
            delay(16)
        }
    }

    var renaming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0f) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showApplyAll by remember { mutableStateOf(false) }
    var showMerge by remember { mutableStateOf(false) }
    var mergeSource by remember { mutableStateOf<Document?>(null) }
    var bulkProgress by remember { mutableStateOf<Float?>(null) }
    var bulkMessage by remember { mutableStateOf("") }
    var bulkJob by remember { mutableStateOf<Job?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun commitOrder(ids: List<String>) {
        val current = doc?.pages?.map { it.id }?.filter { it !in pendingDeletes }
        if (current == ids) return
        scope.launch {
            try {
                // Las páginas pendientes de borrar van al final (se eliminarán enseguida).
                repo.reorderPages(docId, ids + pendingDeletes.toList())
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudo reordenar")
            }
        }
    }

    /** Borrado optimista (una o varias páginas) con "Deshacer" en lugar de un diálogo de confirmación. */
    fun deleteWithUndo(pages: List<Page>) {
        val ids = pages.map { it.id }.filter { it !in pendingDeletes }.distinct()
        if (ids.isEmpty()) return
        pendingDeletes.addAll(ids)
        order.removeAll { it.id in ids }
        scope.launch {
            var undone = false
            try {
                snackbar.currentSnackbarData?.dismiss()
                val msg = if (ids.size == 1) "Página eliminada" else "${ids.size} páginas eliminadas"
                val r = snackbar.showSnackbar(msg, actionLabel = "Deshacer", duration = SnackbarDuration.Short)
                undone = r == SnackbarResult.ActionPerformed
            } finally {
                if (undone) {
                    pendingDeletes.removeAll(ids)
                } else {
                    // Fuera del ciclo de vida de la pantalla: el borrado se completa aunque se salga.
                    reviewAppScope.launch {
                        var failed = 0
                        for (id in ids) {
                            try {
                                repo.deletePage(docId, id)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (_: Throwable) {
                                failed++
                            } finally {
                                pendingDeletes.remove(id)
                            }
                        }
                        if (failed > 0) {
                            val m = if (failed == 1) "No se pudo eliminar la página" else "No se pudieron eliminar $failed páginas"
                            Toast.makeText(context.applicationContext, m, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    val processor = remember { CaptureProcessor(container) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val mode = doc?.mode ?: ScanMode.DOCUMENT
        importJob = scope.launch {
            importing = true
            importProgress = 0f
            try {
                val settings = runCatching { container.settings.settings.first() }.getOrDefault(AppSettings())
                // Libro: cada foto se divide en dos páginas; DNI: anverso + reverso de dos en dos.
                val ok = processor.importUris(docId, mode, uris, settings) { done, total ->
                    importProgress = done.toFloat() / total.coerceAtLeast(1)
                }
                if (ok < uris.size) toast("Se importaron $ok de ${uris.size} imágenes")
            } finally {
                importing = false
                importJob = null
            }
        }
    }

    fun openGallery() {
        runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { toast("No hay una galería disponible") }
    }

    /** Aplica un filtro (y opcionalmente la limpieza de rayas) a [targets] (todas por defecto), con progreso. */
    fun applyToAll(filter: FilterType, removeLines: Boolean?, targets: List<Page> = order.toList()) {
        if (bulkJob != null) return
        val pages = targets
        if (pages.isEmpty()) return
        bulkJob = scope.launch {
            bulkProgress = 0f
            var failed = 0
            try {
                pages.forEachIndexed { i, p ->
                    bulkMessage = "Aplicando a la página ${i + 1} de ${pages.size}…"
                    val current = repo.getPage(docId, p.id) ?: p
                    val e = current.edits
                    val updated = e.copy(filter = filter, autoRemoveLines = removeLines ?: e.autoRemoveLines)
                    if (updated != e) {
                        try {
                            repo.updateEdits(docId, p.id, updated)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            failed++
                        }
                    }
                    bulkProgress = (i + 1f) / pages.size
                }
                toast(if (failed == 0) "Filtro aplicado a ${pagesLabel(pages.size)}" else "No se pudo aplicar en $failed páginas")
            } finally {
                bulkProgress = null
                bulkJob = null
            }
        }
    }

    fun merge(source: Document) {
        if (bulkJob != null) return
        bulkJob = scope.launch {
            try {
                bulkMessage = "Uniendo documentos…"
                bulkProgress = 0.5f
                withContext(NonCancellable) { repo.mergeInto(docId, source.id) }
                toast("Se añadieron ${pagesLabel(source.pages.size)} de «${source.title}»")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudieron unir: ${t.message ?: "error"}")
            } finally {
                bulkProgress = null
                bulkJob = null
            }
        }
    }

    fun selectedPages(): List<Page> = PageSelection.inOrder(order, selectedIds)

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptyList()
    }

    /** Páginas a las que se aplica la hoja de filtro: en modo selección solo las marcadas; null = todas. */
    var filterTargets by remember { mutableStateOf<List<String>?>(null) }

    // ---- Lectura con IA (antes en "Texto reconocido"): se encola en segundo plano (con notificación cancelable) para
    // todas las páginas o las seleccionadas; el progreso se ve bajo la cabecera con "Cancelar".
    val aiStatusMap by container.aiQueue.status.collectAsState()
    val aiDocStatuses = aiStatusMap.filterKeys { it.startsWith("$docId/") }
    val engine by remember { container.settings.settings }.collectAsState(initial = null)
    val aiEngineOn = engine?.recognitionEngine?.let { it != RecognitionEngine.BASIC } == true

    fun readWithAi(targets: List<Page>) {
        if (targets.isEmpty()) return
        scope.launch {
            repo.flushPendingWrites()
            var queued = 0
            for (p in targets) {
                val err = container.aiQueue.reread(docId, p.id)
                if (err != null) {
                    toast(err)
                    break
                }
                queued++
            }
            if (queued > 0) toast(if (queued == 1) "La página se leerá con IA en segundo plano" else "$queued páginas se leerán con IA en segundo plano")
        }
    }

    fun cancelAi() {
        aiDocStatuses.keys.forEach { container.aiQueue.cancel(docId, it.substringAfter('/')) }
    }

    BackHandler(enabled = selectionMode && !importing) { exitSelection() }
    BackHandler(enabled = importing) { importJob?.cancel() }

    val d = doc

    val docMenuGroups = listOf(
        listOf(
            MoreMenuItem("Renombrar", AppIcons.Rename, enabled = d != null) { renaming = true },
            MoreMenuItem("Seleccionar páginas", AppIcons.Select, enabled = order.isNotEmpty()) { selectionMode = true },
        ),
        listOf(
            MoreMenuItem("Filtro para todas las páginas", AppIcons.Wand, enabled = order.isNotEmpty()) { showApplyAll = true },
            MoreMenuItem("Unir con otro documento…", AppIcons.Duplicate, enabled = d != null) { showMerge = true },
        ),
    ) + if (aiEngineOn || aiDocStatuses.isNotEmpty()) listOf(
        listOf(
            MoreMenuItem("Volver a leer con IA", AppIcons.Sparkles, enabled = order.isNotEmpty() && aiDocStatuses.isEmpty()) {
                readWithAi(order.toList())
            },
        ),
    ) else emptyList()
    // ••• del modo selección: solo lo que no está ya en la barra inferior (leer con IA las páginas marcadas).
    val selectionMenuGroups = if (aiEngineOn || aiDocStatuses.isNotEmpty()) listOf(
        listOf(
            MoreMenuItem(
                if (selectedIds.size > 1) "Volver a leer con IA (${selectedIds.size})" else "Volver a leer con IA",
                AppIcons.Sparkles,
                enabled = selectedIds.isNotEmpty() && aiDocStatuses.isEmpty(),
            ) { readWithAi(selectedPages()); exitSelection() },
        ),
    ) else emptyList()

    ReviewContent(
        title = d?.title,
        subtitle = d?.let { reviewSubtitle(order, it.mode) },
        pages = order,
        loading = d == null || !loaded,
        showEmpty = order.isEmpty() && pendingDeletes.isEmpty(),
        gridState = gridState,
        draggingKey = reorder.draggingKey,
        displacementFor = { reorder.displacementFor(it) },
        gridGestures = Modifier.pointerInput(reorder) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    reorder.pointer = pos
                    if (reorder.start(pos)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // Pulsación larga fuera del modo selección: entra en él con esa página marcada. Si además se
                        // arrastra, la página se mueve (reordenar sigue funcionando igual que antes).
                        val key = reorder.draggingKey
                        if (!selectionMode && key != null) {
                            selectionMode = true
                            selectedIds = listOf(key)
                        }
                    }
                },
                onDrag = { change, amount ->
                    if (reorder.draggingKey != null) {
                        change.consume()
                        reorder.pointer = change.position
                        reorder.drag(amount)
                    }
                },
                onDragEnd = {
                    if (reorder.draggingKey != null) {
                        reorder.end()
                        commitOrder(order.map { it.id })
                    }
                },
                onDragCancel = {
                    if (reorder.draggingKey != null) {
                        reorder.end()
                        commitOrder(order.map { it.id })
                    }
                },
            )
        },
        docMenuGroups = docMenuGroups,
        selectionMenuGroups = selectionMenuGroups,
        aiStatus = aiProgressLabel(aiDocStatuses.values),
        onCancelAi = { cancelAi() },
        onBack = onBack,
        pageThumbnail = { page, number, modifier -> PageThumbnail(container, docId, page, number, modifier) },
        // Tocar una hoja abre el visor con la imagen YA PROCESADA (la misma que se exporta), al instante y sin
        // reprocesar; desde allí "Editar" lleva al editor.
        onPageClick = { index ->
            if (selectionMode) order.getOrNull(index)?.let { selectedIds = PageSelection.toggle(selectedIds, it.id) }
            else viewerIndex = index
        },
        selectionMode = selectionMode,
        selectedIds = selectedIds.toSet(),
        onCancelSelection = { exitSelection() },
        onToggleSelectAll = { selectedIds = PageSelection.toggleAll(selectedIds, order.map { it.id }) },
        onDeleteSelected = {
            val pages = selectedPages()
            if (pages.isNotEmpty()) {
                deleteWithUndo(pages)
                exitSelection()
            }
        },
        onAddCamera = { d?.let { onAddPages(it.mode) } },
        onAddGallery = { openGallery() },
        onFilter = {
            when {
                order.isEmpty() -> toast("No hay páginas")
                selectionMode && selectedIds.isEmpty() -> toast("Selecciona alguna página")
                else -> {
                    filterTargets = if (selectionMode) selectedPages().map { it.id } else null
                    showApplyAll = true
                }
            }
        },
        onExport = {
            if (selectionMode) {
                val ids = selectedPages().map { it.id }
                if (ids.isEmpty()) toast("Selecciona alguna página") else onExport(ids.takeIf { it.size < order.size })
            } else {
                onExport(null)
            }
        },
        snackbar = snackbar,
    ) {
        viewerIndex?.let { start ->
            PageViewerDialog(
                container = container,
                docId = docId,
                pages = order.toList(),
                startIndex = start,
                onDismiss = { viewerIndex = null },
                onEdit = { p -> viewerIndex = null; reopenViewerPageId = p.id; onEditPage(p.id) },
            )
        }
        // Vuelta del editor: se reabre el visor en la página editada (ya con su procesado nuevo). Solo en la composición
        // NUEVA de esta pantalla (la que se crea al volver, con el valor restaurado), nunca en la que abrió el editor.
        val reopenAtEntry = remember { reopenViewerPageId }
        LaunchedEffect(reopenAtEntry, order.size) {
            val id = reopenAtEntry ?: return@LaunchedEffect
            if (reopenViewerPageId != id || order.isEmpty()) return@LaunchedEffect
            val i = order.indexOfFirst { it.id == id }
            reopenViewerPageId = null
            if (i >= 0) viewerIndex = i
        }

        ProgressHud(
            visible = importing,
            message = "Importando y mejorando…",
            progress = importProgress,
            onCancel = { importJob?.cancel() },
            modifier = Modifier.fillMaxSize(),
        )
        ProgressHud(
            visible = bulkProgress != null,
            message = bulkMessage,
            progress = bulkProgress,
            onCancel = { bulkJob?.cancel() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (renaming && d != null) {
        RenameDialog(
            initial = d.title,
            onDismiss = { renaming = false },
            onConfirm = { newTitle ->
                scope.launch { runCatching { repo.rename(docId, newTitle) }.onFailure { toast("No se pudo renombrar") } }
            },
        )
    }

    if (showApplyAll) {
        val targets = filterTargets?.let { ids -> PageSelection.inOrder(order, ids) }
        ApplyAllSheet(
            initial = (targets ?: order).firstOrNull()?.edits?.filter?.uiFilter ?: FilterType.DEFAULT,
            count = targets?.size,
            onDismiss = { showApplyAll = false },
            onApply = { f, lines -> showApplyAll = false; applyToAll(f, lines, targets ?: order.toList()) },
        )
    }

    if (showMerge) {
        val all by repo.documents.collectAsState()
        val others = all.filter { it.id != docId && it.pages.isNotEmpty() }
        MergePickerSheet(
            documents = others,
            onDismiss = { showMerge = false },
            onPick = { showMerge = false; mergeSource = it },
        )
    }
    mergeSource?.let { src ->
        ConfirmDialog(
            title = "¿Unir documentos?",
            message = "Las ${pagesLabel(src.pages.size)} de «${src.title}» se añadirán al final de este documento y «${src.title}» se eliminará.",
            confirmLabel = "Unir",
            onConfirm = { merge(src) },
            onDismiss = { mergeSource = null },
        )
    }
}

/** "4 páginas · Documento" (el texto se reconoce solo y se selecciona en el visor: no se anuncia). */
internal fun reviewSubtitle(pages: List<Page>, mode: ScanMode): String =
    listOf(pagesLabel(pages.size), mode.label).joinToString(" · ")

/**
 * Progreso de la lectura con IA de las páginas del documento ([statuses] = estados de la cola de ESTE documento):
 * "Leyendo con IA… 40 % · 3 en cola", "Esperando conexión…", "2 páginas en cola…"; null = nada en cola.
 */
internal fun aiProgressLabel(statuses: Collection<AiRecognitionQueue.PageStatus>): String? {
    if (statuses.isEmpty()) return null
    val n = statuses.size
    val running = statuses.filterIsInstance<AiRecognitionQueue.PageStatus.Running>().firstOrNull()
    return when {
        running != null -> "Leyendo con IA… ${running.percent} %" + if (n > 1) " · $n en cola" else ""
        statuses.all { it == AiRecognitionQueue.PageStatus.WaitingNetwork } -> "Esperando conexión para leer con IA"
        n == 1 -> "1 página en cola para leer con IA"
        else -> "$n páginas en cola para leer con IA"
    }
}

/** Guarda la selección de páginas (ids) en el estado guardable. */
internal val StringListSaver: Saver<List<String>, ArrayList<String>> = Saver(save = { ArrayList(it) }, restore = { it.toList() })

/**
 * Pantalla Documento SIN estado (maqueta Revision.dc.html): "‹ Documentos" + •••, título grande de 30 sp con
 * subtítulo, cuadrícula de páginas (solo el número debajo; tocar = visor) y barra flotante Añadir · Filtro + Exportar.
 * Mantener pulsada una página entra en el MODO SELECCIÓN ([selectionMode], estilo Fotos de iOS): círculos de check,
 * cabecera "Seleccionar todo · ••• · Cancelar" + "N seleccionadas" y barra Filtro · Eliminar + Exportar (N); mantener
 * pulsada y arrastrar = reordenar (en ambos modos). [aiStatus] = progreso de la lectura con IA (con [onCancelAi]).
 * [overlays] = visor, capas de progreso…
 */
@Composable
internal fun ReviewContent(
    title: String?,
    subtitle: String?,
    pages: List<Page>,
    loading: Boolean,
    showEmpty: Boolean,
    gridState: LazyGridState,
    draggingKey: String?,
    displacementFor: (String) -> Offset?,
    gridGestures: Modifier,
    docMenuGroups: List<List<MoreMenuItem>>,
    onBack: () -> Unit,
    pageThumbnail: @Composable (page: Page, number: Int, modifier: Modifier) -> Unit,
    onPageClick: (index: Int) -> Unit,
    onAddCamera: () -> Unit,
    onAddGallery: () -> Unit,
    onFilter: () -> Unit,
    onExport: () -> Unit,
    snackbar: SnackbarHostState,
    selectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onCancelSelection: () -> Unit = {},
    onToggleSelectAll: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    /** ••• de la cabecera del modo selección (vacío = sin botón). */
    selectionMenuGroups: List<List<MoreMenuItem>> = emptyList(),
    aiStatus: String? = null,
    onCancelAi: () -> Unit = {},
    overlays: @Composable BoxScope.() -> Unit = {},
) {
    val ui = MaterialTheme.ui
    var docMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val selectedCount = pages.count { it.id in selectedIds }
    val allSelected = pages.isNotEmpty() && selectedCount == pages.size
    val header: @Composable (Dp) -> Unit = { pad ->
        if (selectionMode) {
            SelectionHeader(
                title = title ?: "",
                count = selectedCount,
                allSelected = allSelected,
                onToggleAll = onToggleSelectAll,
                onCancel = onCancelSelection,
                horizontalPadding = pad,
                menuGroups = selectionMenuGroups,
            )
        } else LargeTitleHeader(
            title = title ?: "Cargando…",
            backLabel = "Documentos",
            onBack = onBack,
            subtitle = subtitle,
            titleStyle = MaterialTheme.typography.headlineLarge,
            horizontalPadding = pad,
        ) {
            Box {
                IconCircleButton(AppIcons.More, "Más opciones del documento", { docMenu = true }, size = 36.dp, iconSize = 18.dp, enabled = title != null)
                MoreMenu(expanded = docMenu, onDismissRequest = { docMenu = false }, groups = docMenuGroups, width = 280.dp)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(ui.groupedBackground)) {
        when {
            loading -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                header(20.dp)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ui.secondaryLabel, strokeWidth = 3.dp)
                }
            }
            showEmpty -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                header(20.dp)
                EmptyMessage(
                    title = "Este documento está vacío",
                    message = "Escanea páginas con la cámara o impórtalas desde la galería.",
                    icon = AppIcons.Scan,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryCapsuleButton("Galería", onClick = onAddGallery, icon = AppIcons.Gallery, tonal = true, height = 48.dp)
                        PrimaryCapsuleButton("Cámara", onClick = onAddCamera, icon = AppIcons.Camera, height = 48.dp)
                    }
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                // Sin contentPadding: los offsets del layout deben coincidir con las coordenadas del puntero
                // (reordenación). La barra de estado y los márgenes van como relleno del propio modificador.
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp)
                    .then(gridGestures),
            ) {
                item(key = HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(bottom = 2.dp)) {
                        // La rejilla ya tiene el margen de 20 dp.
                        header(0.dp)
                        Text(
                            when {
                                selectionMode && pages.size > 1 -> "Toca para marcar · mantén y arrastra para reordenar"
                                selectionMode -> "Toca para marcar o desmarcar"
                                pages.size > 1 -> "Mantén pulsada para seleccionar o reordenar"
                                else -> "Mantén pulsada para seleccionar"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = ui.tertiaryLabel,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        if (aiStatus != null) AiStatusRow(aiStatus, onCancelAi, Modifier.padding(top = 12.dp))
                    }
                }
                itemsIndexed(pages, key = { _, p -> p.id }, contentType = { _, _ -> "page" }) { index, page ->
                    val dragging = draggingKey == page.id
                    val lift by animateFloatAsState(if (dragging) 1.06f else 1f, tween(150), label = "lift")
                    val itemModifier = if (dragging) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer {
                                val off = displacementFor(page.id) ?: Offset.Zero
                                translationX = off.x
                                translationY = off.y
                                scaleX = lift
                                scaleY = lift
                            }
                    } else {
                        Modifier.animateItem().graphicsLayer { scaleX = lift; scaleY = lift }
                    }
                    PageCell(
                        page = page,
                        number = index + 1,
                        dragging = dragging,
                        selectionMode = selectionMode,
                        selected = page.id in selectedIds,
                        modifier = itemModifier,
                        thumbnail = { m -> pageThumbnail(page, index + 1, m) },
                        onClick = { onPageClick(index) },
                    )
                }
                item(key = FOOTER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(FloatingBarContentPadding + navBottom))
                }
            }
        }

        // ------------------------------------------------ barra flotante inferior
        // Cápsula de ancho completo: los botones de icono se reparten por igual en la zona izquierda y Exportar va a la
        // derecha (mismo criterio en el modo selección).
        if (!loading && selectionMode) {
            FloatingBar(Modifier.align(Alignment.BottomCenter), fillWidth = true) {
                BarItemsZone {
                    FloatingBarItem(AppIcons.Wand, "Filtro", onFilter, enabled = selectedCount > 0)
                    FloatingBarItem(AppIcons.Trash, "Eliminar", onDeleteSelected, enabled = selectedCount > 0)
                }
                PrimaryCapsuleButton(
                    if (selectedCount > 0) "Exportar ($selectedCount)" else "Exportar",
                    onClick = onExport,
                    icon = AppIcons.Share,
                    enabled = selectedCount > 0,
                    height = 48.dp,
                )
            }
        } else if (!loading) {
            FloatingBar(Modifier.align(Alignment.BottomCenter), fillWidth = true) {
                BarItemsZone {
                    Box {
                        FloatingBarItem(AppIcons.Plus, "Añadir", { addMenu = true })
                        MoreMenu(
                            expanded = addMenu,
                            onDismissRequest = { addMenu = false },
                            groups = listOf(
                                listOf(
                                    MoreMenuItem("Escanear con la cámara", AppIcons.Camera, onClick = onAddCamera),
                                    MoreMenuItem("Importar de la galería", AppIcons.Gallery, onClick = onAddGallery),
                                ),
                            ),
                        )
                    }
                    FloatingBarItem(AppIcons.Wand, "Filtro", onFilter)
                }
                PrimaryCapsuleButton("Exportar", onClick = onExport, icon = AppIcons.Share, enabled = pages.isNotEmpty(), height = 48.dp)
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp),
        ) { data -> IosSnackbar(data) }

        overlays()
    }
}

/** Zona izquierda de la barra flotante del Documento: ocupa el espacio libre y reparte sus botones por igual. */
@Composable
private fun RowScope.BarItemsZone(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.weight(1f).padding(end = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Fila de progreso de la lectura con IA bajo la cabecera: "Leyendo con IA… 40 %" · Cancelar. */
@Composable
private fun AiStatusRow(text: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val ui = MaterialTheme.ui
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ui.card)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = ui.accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = ui.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HeaderTextButton("Cancelar", onCancel, Modifier)
    }
}

/** Aviso inferior estilo iOS (cápsula oscura, acción en el acento). */
@Composable
internal fun IosSnackbar(data: SnackbarData) {
    val ui = MaterialTheme.ui
    Snackbar(
        snackbarData = data,
        modifier = Modifier.padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = if (ui.isDark) Color(0xFF2C2C2E) else Color(0xF21C1C1E),
        contentColor = Color.White,
        actionColor = if (ui.isDark) ui.accent else Color(0xFF9D97FF),
    )
}

/**
 * Cabecera del modo selección: "Seleccionar todo/Ninguna" · "Cancelar" y, debajo, el título y "N seleccionadas" (en vez del
 * documento (mismo tamaño que fuera del modo selección para que la rejilla no salte).
 */
@Composable
internal fun SelectionHeader(
    title: String,
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onCancel: () -> Unit,
    horizontalPadding: Dp,
    menuGroups: List<List<MoreMenuItem>> = emptyList(),
) {
    val ui = MaterialTheme.ui
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(start = (horizontalPadding - 10.dp).coerceAtLeast(0.dp), end = (horizontalPadding - 10.dp).coerceAtLeast(0.dp), top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderTextButton(if (allSelected) "Ninguna" else "Seleccionar todo", onToggleAll, Modifier)
            Spacer(Modifier.weight(1f))
            if (menuGroups.isNotEmpty()) {
                Box {
                    IconCircleButton(AppIcons.More, "Más opciones de las páginas seleccionadas", { menu = true }, size = 32.dp, iconSize = 16.dp)
                    MoreMenu(expanded = menu, onDismissRequest = { menu = false }, groups = menuGroups, width = 280.dp)
                }
                Spacer(Modifier.width(4.dp))
            }
            HeaderTextButton("Cancelar", onCancel, Modifier, bold = true)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = ui.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 4.dp),
        )
        // "N seleccionadas" en el lugar del subtítulo (en la fila de arriba no cabe sin recortarse).
        Text(
            PageSelection.title(count),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (count > 0) ui.accent else ui.secondaryLabel,
            maxLines = 1,
            modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 4.dp),
        )
    }
}

@Composable
private fun HeaderTextButton(label: String, onClick: () -> Unit, modifier: Modifier, bold: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 16.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = MaterialTheme.ui.accent,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** Círculo de check del modo selección (estilo Fotos de iOS). */
@Composable
internal fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val ui = MaterialTheme.ui
    Box(
        modifier
            .size(26.dp)
            .shadow(if (selected) 2.dp else 0.dp, CircleShape)
            .clip(CircleShape)
            .background(if (selected) ui.accent else Color.Black.copy(alpha = 0.22f))
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** Celda de página: hoja blanca (radio 10, sombra suave), check en modo selección y, debajo, el número de página. */
@Composable
private fun PageCell(
    page: Page,
    number: Int,
    dragging: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier,
    thumbnail: @Composable (Modifier) -> Unit,
    onClick: () -> Unit,
) {
    val ui = MaterialTheme.ui
    val perf = LocalPerf.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                // En gama baja sin sombra en reposo (trabajo extra de GPU al desplazar); al arrastrar sí.
                .shadow(if (dragging) 18.dp else if (perf.lowEnd) 0.dp else 3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(shape)
                .background(if (ui.isDark) Color(0xFF2C2C2E) else Color.White)
                .then(if (dragging || (selectionMode && selected)) Modifier.border(if (dragging) 2.dp else 3.dp, ui.accent, shape) else Modifier)
                .then(
                    if (selectionMode) {
                        Modifier
                            .semantics { this.selected = selected }
                            .clickable(onClickLabel = if (selected) "Desmarcar página $number" else "Marcar página $number", onClick = onClick)
                    } else {
                        Modifier.clickable(onClickLabel = "Ver página $number", onClick = onClick)
                    },
                ),
        ) {
            thumbnail(Modifier.fillMaxSize())
            if (selectionMode) {
                // Velo suave sobre las marcadas (como Fotos) y círculo de check arriba a la derecha.
                if (selected) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = if (ui.isDark) 0.08f else 0.18f)))
                SelectionCheck(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("$number", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = ui.secondaryLabel)
    }
}

/** Miniatura de una página (procesado actual, el mismo que el visor y la exportación). */
@Composable
internal fun PageThumbnail(container: AppContainer, docId: String, page: Page, number: Int, modifier: Modifier) {
    val repo = container.documents
    val context = LocalContext.current
    // Miniatura = reducción del procesado ACTUAL (misma imagen que el visor y la exportación). Se elige en segundo
    // plano; si falta o es de otra versión de las ediciones, se regenera desde el procesado (nunca se muestra el
    // original sin procesar mientras tanto, salvo que el procesado no se pueda generar).
    val model by produceState<VersionedFile?>(initialValue = null, page.id, page.thumbFile, page.processedFile, page.edits) {
        value = withContext(Dispatchers.IO) { repo.displayThumb(docId, page) }
        if (withContext(Dispatchers.IO) { repo.thumbFile(docId, page) } == null) {
            val ok = runCatching { repo.processedFile(docId, page) }.isSuccess
            value = withContext(Dispatchers.IO) {
                repo.displayThumb(docId, page) ?: if (!ok) VersionedFile.of(repo.originalFile(docId, page)) else null
            }
        } else if (runCatching { repo.upgradeSmallThumb(docId, page) }.getOrDefault(false)) {
            // Miniatura antigua (480 px) regenerada en el mismo archivo: nueva versión -> nueva clave de caché.
            value = withContext(Dispatchers.IO) { repo.displayThumb(docId, page) }
        }
    }
    // Miniatura nítida: Coil decodifica al tamaño REAL de la celda en píxeles, tomado de las restricciones del
    // AsyncImage. Clave de caché con la versión del archivo.
    val request = remember(model?.cacheKey) {
        model?.let {
            ImageRequest.Builder(context)
                .data(it.file)
                .memoryCacheKey(it.cacheKey)
                .precision(Precision.INEXACT)
                .build()
        }
    }
    AsyncImage(
        model = request,
        contentDescription = "Página $number",
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
        modifier = modifier,
    )
}

/** Hoja "Filtro para todas las páginas". [onApply] recibe el filtro y si quitar rayas (null = no tocar). */
@Composable
private fun ApplyAllSheet(initial: FilterType, count: Int? = null, onDismiss: () -> Unit, onApply: (FilterType, Boolean?) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var lines by remember { mutableStateOf(false) }
    IosSheet(onDismissRequest = onDismiss) { dismiss ->
        SheetHeader(
            title = if (count == null) "Filtro para todas" else "Filtro para ${pagesLabel(count)}",
            onCancel = dismiss,
            onDone = { onApply(selected, if (lines) true else null) },
            doneLabel = "Aplicar",
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 28.dp),
            verticalArrangement = GroupArrangement,
        ) {
            InsetGroup(separatorInset = SeparatorInsetPlain, footer = if (count == null) "Se aplica a todas las páginas del documento." else if (count == 1) "Se aplica a la página seleccionada." else "Se aplica a las $count páginas seleccionadas.") {
                for (f in FilterType.visible) {
                    item { CheckRow(title = f.label, subtitle = f.description, selected = f == selected, onClick = { selected = f }) }
                }
            }
            InsetGroup(separatorInset = SeparatorInsetPlain) {
                item { ToggleRow("Quitar también rayas y líneas sueltas", checked = lines, onCheckedChange = { lines = it }) }
            }
        }
    }
}

/** Hoja "Unir con…": lista de documentos con páginas. */
@Composable
private fun MergePickerSheet(documents: List<Document>, onDismiss: () -> Unit, onPick: (Document) -> Unit) {
    val ui = MaterialTheme.ui
    IosSheet(onDismissRequest = onDismiss) { dismiss ->
        SheetHeader(title = "Unir con…", onCancel = dismiss)
        if (documents.isEmpty()) {
            Text(
                "No hay otros documentos con páginas.",
                style = MaterialTheme.typography.bodyMedium,
                color = ui.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp, start = 20.dp, end = 20.dp),
            ) {
                listItems(documents, key = { it.id }) { doc ->
                    val first = documents.first() == doc
                    val last = documents.last() == doc
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = if (first) 12.dp else 0.dp, topEnd = if (first) 12.dp else 0.dp, bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp))
                            .background(ui.card),
                    ) {
                        NavigationRow(
                            title = doc.title,
                            subtitle = "${pagesLabel(doc.pages.size)} · ${doc.mode.label}",
                            icon = AppIcons.Document,
                            onClick = { onPick(doc) },
                        )
                        if (!last) HorizontalDivider(Modifier.padding(start = SeparatorInsetIcon), thickness = 0.5.dp, color = ui.separator)
                    }
                }
            }
        }
    }
}

private const val HEADER_KEY = "__header__"
private const val FOOTER_KEY = "__footer__"

/**
 * Reordenación por arrastre en la rejilla. Trabaja con claves (ids de página) para no depender de la
 * posición de cabeceras/pies. La rejilla no usa contentPadding, así que los offsets del layout coinciden
 * con las coordenadas del puntero.
 */
private class GridReorderState(
    private val gridState: LazyGridState,
    private val onMove: (fromKey: String, toKey: String) -> Unit,
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set
    /** Última posición del puntero (coordenadas de la rejilla), para el auto-scroll en los bordes. */
    var pointer: Offset = Offset.Zero
    private var initialOffset = Offset.Zero
    private var accumulated by mutableStateOf(Offset.Zero)

    private fun isPageKey(k: Any?) = k is String && k != HEADER_KEY && k != FOOTER_KEY

    fun start(pos: Offset): Boolean {
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            isPageKey(it.key) &&
                pos.x >= it.offset.x && pos.x <= it.offset.x + it.size.width &&
                pos.y >= it.offset.y && pos.y <= it.offset.y + it.size.height
        } ?: return false
        draggingKey = item.key as String
        initialOffset = Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
        accumulated = Offset.Zero
        return true
    }

    fun drag(delta: Offset) {
        val key = draggingKey ?: return
        accumulated += delta
        val items = gridState.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == key } ?: return
        val center = initialOffset + accumulated + Offset(current.size.width / 2f, current.size.height / 2f)
        val target = items.firstOrNull {
            isPageKey(it.key) && it.key != key &&
                center.x >= it.offset.x && center.x <= it.offset.x + it.size.width &&
                center.y >= it.offset.y && center.y <= it.offset.y + it.size.height
        } ?: return
        onMove(key, target.key as String)
    }

    /** Desplazamiento visual del elemento arrastrado respecto de su posición de layout actual. */
    fun displacementFor(key: String): Offset? {
        if (key != draggingKey) return null
        val current = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        return initialOffset + accumulated - Offset(current.offset.x.toFloat(), current.offset.y.toFloat())
    }

    fun end() {
        draggingKey = null
        accumulated = Offset.Zero
    }
}
