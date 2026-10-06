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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.camera.CaptureProcessor
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.ConfirmDialog
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.LoadingOverlay
import com.scannerpromax.ui.components.RenameDialog
import com.scannerpromax.ui.components.SoftButton
import com.scannerpromax.ui.components.pagesLabel
import com.scannerpromax.ui.theme.brand
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
import java.io.File

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
    onExport: () -> Unit,
    onOcr: (pageId: String) -> Unit,
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
    // Páginas eliminadas a la espera de "Deshacer" (no se muestran aunque el repositorio aún las tenga).
    val pendingDeletes = remember { mutableStateListOf<String>() }
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

    fun move(page: Page, delta: Int) {
        val i = order.indexOfFirst { it.id == page.id }
        val j = (i + delta).coerceIn(0, order.size - 1)
        if (i < 0 || i == j) return
        order.add(j, order.removeAt(i))
        commitOrder(order.map { it.id })
    }

    /** Borrado optimista con "Deshacer" en lugar de un diálogo de confirmación. */
    fun deleteWithUndo(page: Page) {
        if (page.id in pendingDeletes) return
        pendingDeletes.add(page.id)
        order.removeAll { it.id == page.id }
        scope.launch {
            var undone = false
            try {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar("Página eliminada", actionLabel = "Deshacer", duration = SnackbarDuration.Short)
                undone = r == SnackbarResult.ActionPerformed
            } finally {
                if (undone) {
                    pendingDeletes.remove(page.id)
                } else {
                    // Fuera del ciclo de vida de la pantalla: el borrado se completa aunque se salga.
                    reviewAppScope.launch {
                        try {
                            repo.deletePage(docId, page.id)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            Toast.makeText(context.applicationContext, "No se pudo eliminar la página", Toast.LENGTH_SHORT).show()
                        } finally {
                            pendingDeletes.remove(page.id)
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

    /** Aplica un filtro (y opcionalmente la limpieza de rayas) a todas las páginas, con progreso. */
    fun applyToAll(filter: FilterType, removeLines: Boolean?) {
        if (bulkJob != null) return
        val pages = order.toList()
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

    /** Página visible (la primera de la rejilla) para abrir el OCR o el editor desde la barra inferior. */
    fun visiblePage(): Page? {
        val key = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is String && it.key != HEADER_KEY && it.key != FOOTER_KEY }?.key
        return order.firstOrNull { it.id == key } ?: order.firstOrNull()
    }

    BackHandler(enabled = importing) { importJob?.cancel() }

    val brand = MaterialTheme.brand
    val d = doc

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------ barra superior
            AppTopBar(
                title = d?.title ?: "Cargando…",
                subtitle = d?.let { "${pagesLabel(order.size)} · ${it.mode.label}" },
                onBack = onBack,
                actions = {
                    IconButton(onClick = { renaming = true }, enabled = d != null) {
                        Icon(Icons.Filled.Edit, "Renombrar")
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }, enabled = d != null) {
                            Icon(Icons.Filled.MoreVert, "Más opciones")
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Filtro para todas las páginas") },
                                leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null) },
                                enabled = order.isNotEmpty(),
                                onClick = { moreMenu = false; showApplyAll = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Unir con otro documento…") },
                                leadingIcon = { Icon(Icons.Filled.CallMerge, null) },
                                onClick = { moreMenu = false; showMerge = true },
                            )
                        }
                    }
                },
            )

            when {
                d == null || !loaded -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = brand.gradientStart)
                }
                order.isEmpty() && pendingDeletes.isEmpty() -> EmptyPages(
                    modifier = Modifier.weight(1f),
                    onCamera = { onAddPages(d.mode) },
                    onGallery = { openGallery() },
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .pointerInput(reorder) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    reorder.pointer = pos
                                    if (reorder.start(pos)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                ) {
                    if (order.size > 1) {
                        item(key = HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.DragIndicator, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Mantén pulsada una página y arrástrala para reordenar",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(order, key = { it.id }) { page ->
                        val index = order.indexOf(page)
                        val dragging = reorder.draggingKey == page.id
                        val lift by animateFloatAsState(if (dragging) 1.06f else 1f, tween(150), label = "lift")
                        val itemModifier = if (dragging) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer {
                                    val off = reorder.displacementFor(page.id) ?: Offset.Zero
                                    translationX = off.x
                                    translationY = off.y
                                    scaleX = lift
                                    scaleY = lift
                                }
                        } else {
                            Modifier.animateItem().graphicsLayer { scaleX = lift; scaleY = lift }
                        }
                        PageCard(
                            container = container,
                            docId = docId,
                            page = page,
                            number = index + 1,
                            total = order.size,
                            dragging = dragging,
                            modifier = itemModifier,
                            onClick = { onEditPage(page.id) },
                            onOcr = { onOcr(page.id) },
                            onMoveLeft = { move(page, -1) },
                            onMoveRight = { move(page, +1) },
                            onMoveFirst = { move(page, -order.size) },
                            onMoveLast = { move(page, order.size) },
                            onDelete = { deleteWithUndo(page) },
                        )
                    }
                    item(key = FOOTER_KEY, span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
                }
            }

            // ------------------------------------------------ barra de acciones inferior
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        BarAction(Icons.Filled.Add, "Añadir") { addMenu = true }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Escanear con la cámara") },
                                leadingIcon = { Icon(Icons.Filled.AddAPhoto, null) },
                                onClick = { addMenu = false; d?.let { onAddPages(it.mode) } },
                            )
                            DropdownMenuItem(
                                text = { Text("Importar de la galería") },
                                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                                onClick = { addMenu = false; openGallery() },
                            )
                        }
                    }
                    // Abre el OCR en la página visible (allí se puede reconocer todo el documento).
                    BarAction(Icons.AutoMirrored.Filled.TextSnippet, "Texto") {
                        visiblePage()?.let { onOcr(it.id) } ?: toast("No hay páginas")
                    }
                    BarAction(Icons.Filled.AutoFixHigh, "Filtro") {
                        if (order.isEmpty()) toast("No hay páginas") else showApplyAll = true
                    }
                    Spacer(Modifier.width(10.dp))
                    GradientButton(
                        text = "Exportar",
                        onClick = onExport,
                        icon = Icons.Filled.PictureAsPdf,
                        enabled = order.isNotEmpty(),
                        height = 54.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp),
        )

        LoadingOverlay(
            visible = importing,
            message = "Importando y mejorando…",
            progress = importProgress,
            onCancel = { importJob?.cancel() },
            modifier = Modifier.fillMaxSize(),
        )
        LoadingOverlay(
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
        ApplyAllDialog(
            initial = order.firstOrNull()?.edits?.filter ?: FilterType.MAGIC,
            onDismiss = { showApplyAll = false },
            onApply = { f, lines -> showApplyAll = false; applyToAll(f, lines) },
        )
    }

    if (showMerge) {
        val all by repo.documents.collectAsState()
        val others = all.filter { it.id != docId && it.pages.isNotEmpty() }
        MergePickerDialog(
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
            icon = Icons.Filled.CallMerge,
            onConfirm = { merge(src) },
            onDismiss = { mergeSource = null },
        )
    }
}

/** Diálogo "Filtro para todas las páginas". [onApply] recibe el filtro y si quitar rayas (null = no tocar). */
@Composable
private fun ApplyAllDialog(initial: FilterType, onDismiss: () -> Unit, onApply: (FilterType, Boolean?) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var lines by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AutoFixHigh, null) },
        title = { Text("Filtro para todas las páginas") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    listItems(FilterType.entries, key = { it.name }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(selected = f == selected, role = Role.RadioButton) { selected = f }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = f == selected, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(f.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Checkbox) { lines = !lines }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = lines, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Quitar también rayas y líneas sueltas", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(selected, if (lines) true else null) }) { Text("Aplicar a todas") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** Lista de documentos para "Unir con…". */
@Composable
private fun MergePickerDialog(documents: List<Document>, onDismiss: () -> Unit, onPick: (Document) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CallMerge, null) },
        title = { Text("Unir con…") },
        text = {
            if (documents.isEmpty()) {
                Text("No hay otros documentos con páginas.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    listItems(documents, key = { it.id }) { doc ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(doc) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(doc.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${pagesLabel(doc.pages.size)} · ${doc.mode.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        shape = MaterialTheme.shapes.extraLarge,
    )
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

@Composable
private fun PageCard(
    container: AppContainer,
    docId: String,
    page: Page,
    number: Int,
    total: Int,
    dragging: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onOcr: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onMoveFirst: () -> Unit,
    onMoveLast: () -> Unit,
    onDelete: () -> Unit,
) {
    val repo = container.documents
    val brand = MaterialTheme.brand
    var menu by remember { mutableStateOf(false) }

    // Miniatura: si falta (p. ej. tras cambiar ediciones) se regenera en segundo plano.
    val thumb: File? = repo.thumbFile(docId, page)
    LaunchedEffect(page.id, page.thumbFile, page.edits) {
        if (repo.thumbFile(docId, page) == null) runCatching { repo.processedFile(docId, page) }
    }
    val model: Any = thumb
        ?: page.processedFile?.let { File(repo.docDir(docId), it) }?.takeIf { it.exists() }
        ?: repo.originalFile(docId, page)

    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .shadow(if (dragging) 18.dp else 2.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (dragging) Modifier.border(BorderStroke(2.dp, brand.gradient), shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .padding(8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            AsyncImage(
                model = model,
                contentDescription = "Página $number",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(brand.horizontalGradient)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text("$number", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (page.ocrFile != null || page.ocrText != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text("OCR", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                // Área táctil de 48 dp con el círculo visual de 32 dp dentro.
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Opciones de la página") { menu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MoreVert, "Opciones", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Editar y mejorar") },
                        leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null) },
                        onClick = { menu = false; onClick() },
                    )
                    DropdownMenuItem(
                        text = { Text("Reconocer texto (OCR)") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.TextSnippet, null) },
                        onClick = { menu = false; onOcr() },
                    )
                    if (total > 1) {
                        HorizontalDivider()
                        if (number > 1) {
                            DropdownMenuItem(
                                text = { Text("Mover antes") },
                                leadingIcon = { Icon(Icons.Filled.ChevronLeft, null) },
                                onClick = { menu = false; onMoveLeft() },
                            )
                            DropdownMenuItem(
                                text = { Text("Mover al principio") },
                                leadingIcon = { Icon(Icons.Filled.ChevronLeft, null) },
                                onClick = { menu = false; onMoveFirst() },
                            )
                        }
                        if (number < total) {
                            DropdownMenuItem(
                                text = { Text("Mover después") },
                                leadingIcon = { Icon(Icons.Filled.ChevronRight, null) },
                                onClick = { menu = false; onMoveRight() },
                            )
                            DropdownMenuItem(
                                text = { Text("Mover al final") },
                                leadingIcon = { Icon(Icons.Filled.ChevronRight, null) },
                                onClick = { menu = false; onMoveLast() },
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Eliminar página", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                page.edits.filter.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Página $number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyPages(modifier: Modifier, onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.brand.gradient),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.DocumentScanner, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(20.dp))
        Text("Este documento está vacío", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Escanea páginas con la cámara o impórtalas desde la galería.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftButton("Galería", onClick = onGallery, icon = Icons.Filled.PhotoLibrary, height = 48.dp)
            GradientButton("Cámara", onClick = onCamera, icon = Icons.Filled.AddAPhoto, height = 48.dp)
        }
    }
}
