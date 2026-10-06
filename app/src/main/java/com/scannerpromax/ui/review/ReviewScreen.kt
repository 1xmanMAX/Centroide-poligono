package com.scannerpromax.ui.review

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoFixHigh
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.ScanMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

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
    val repo = container.documents

    val doc by remember(docId) { repo.observe(docId) }.collectAsState(initial = null)
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(docId) {
        val d = repo.get(docId)
        loaded = true
        if (d == null) {
            Toast.makeText(context, "El documento ya no existe", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    // Orden local (permite arrastrar sin esperar al disco).
    val order = remember { mutableStateListOf<Page>() }
    val gridState = rememberLazyGridState()
    val reorder = remember(gridState) {
        GridReorderState(gridState) { fromKey, toKey ->
            val from = order.indexOfFirst { it.id == fromKey }
            val to = order.indexOfFirst { it.id == toKey }
            if (from >= 0 && to >= 0 && from != to) order.add(to, order.removeAt(from))
        }
    }
    LaunchedEffect(doc?.pages) {
        val pages = doc?.pages ?: return@LaunchedEffect
        if (reorder.draggingKey == null) {
            order.clear()
            order.addAll(pages)
        }
    }

    var renaming by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Page?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var addMenu by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun commitOrder(ids: List<String>) {
        val current = doc?.pages?.map { it.id }
        if (current == ids) return
        scope.launch {
            try {
                repo.reorderPages(docId, ids)
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

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importJob = scope.launch {
            importing = true
            importProgress = 0f
            var ok = 0
            try {
                uris.forEachIndexed { i, uri ->
                    try {
                        ok += repo.addPagesFromUris(docId, listOf(uri)).size
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                    }
                    importProgress = (i + 1f) / uris.size
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

    BackHandler(enabled = importing) { importJob?.cancel() }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val d = doc

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------ barra superior
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = d != null) { renaming = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d?.title ?: "Cargando…",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Edit, "Renombrar", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d != null) {
                        val n = d.pages.size
                        Text(
                            "$n ${if (n == 1) "página" else "páginas"} · ${d.mode.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when {
                d == null || !loaded -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primary)
                }
                d.pages.isEmpty() && order.isEmpty() -> EmptyPages(
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
                                    if (reorder.start(pos)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    if (reorder.draggingKey != null) {
                                        change.consume()
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
                            onDelete = { toDelete = page },
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
                    Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
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
                    BarAction(Icons.AutoMirrored.Filled.TextSnippet, "Texto") {
                        order.firstOrNull()?.let { onOcr(it.id) } ?: toast("No hay páginas")
                    }
                    BarAction(Icons.Filled.AutoFixHigh, "Editar") {
                        order.firstOrNull()?.let { onEditPage(it.id) } ?: toast("No hay páginas")
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(
                        Modifier
                            .weight(1f)
                            .height(54.dp)
                            .shadow(10.dp, RoundedCornerShape(50), ambientColor = primary, spotColor = primary)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (order.isNotEmpty()) Brush.horizontalGradient(listOf(primary, secondary))
                                else Brush.horizontalGradient(listOf(Color.Gray, Color.Gray)),
                            )
                            .clickable(enabled = order.isNotEmpty(), onClick = onExport),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Exportar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }

        AnimatedVisibility(visible = importing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(32.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Importando y mejorando…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.width(220.dp).clip(RoundedCornerShape(50)),
                        color = primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { importJob?.cancel() }) { Text("Cancelar") }
                }
            }
        }
    }

    if (renaming && d != null) {
        RenameDialog(
            initial = d.title,
            onDismiss = { renaming = false },
            onConfirm = { newTitle ->
                renaming = false
                scope.launch { runCatching { repo.rename(docId, newTitle) }.onFailure { toast("No se pudo renombrar") } }
            },
        )
    }

    toDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("¿Eliminar esta página?") },
            text = { Text("La página se borrará del documento. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    order.removeAll { it.id == page.id }
                    scope.launch { runCatching { repo.deletePage(docId, page.id) }.onFailure { toast("No se pudo eliminar") } }
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
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
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
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
            .then(if (dragging) Modifier.border(BorderStroke(2.dp, Brush.linearGradient(listOf(primary, secondary))), shape) else Modifier)
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
                    .background(Brush.horizontalGradient(listOf(primary, secondary)))
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
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MoreVert, "Opciones", tint = Color.White, modifier = Modifier.size(18.dp))
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
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
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
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(Brush.linearGradient(listOf(primary, secondary))),
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
            OutlinedButton(onClick = onGallery) {
                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Galería")
            }
            OutlinedButton(onClick = onCamera) {
                Icon(Icons.Filled.AddAPhoto, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cámara")
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renombrar documento") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(120) },
                singleLine = true,
                label = { Text("Nombre") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
