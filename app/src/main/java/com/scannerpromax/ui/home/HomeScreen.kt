package com.scannerpromax.ui.home

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.CoPresent
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.components.ConfirmDialog
import com.scannerpromax.ui.components.DocumentActions
import com.scannerpromax.ui.components.DocumentCard
import com.scannerpromax.ui.components.DocumentListItem
import com.scannerpromax.ui.components.EmptyState
import com.scannerpromax.ui.components.GradientText
import com.scannerpromax.ui.components.LoadingOverlay
import com.scannerpromax.ui.components.PrimaryFab
import com.scannerpromax.ui.components.RenameDialog
import com.scannerpromax.ui.components.SectionHeader
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/** Acción rápida de la pantalla de inicio. */
@Immutable
private data class QuickAction(val title: String, val subtitle: String, val icon: ImageVector, val tint: Color, val onClick: () -> Unit)

@Composable
fun HomeScreen(
    container: AppContainer,
    onScan: (ScanMode) -> Unit,
    onOpenDocument: (String) -> Unit,
    onImportImages: (List<Uri>) -> Unit,
    onCompressPdf: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val brand = MaterialTheme.brand
    val snackbar = remember { SnackbarHostState() }

    val documents by container.documents.documents.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<Document?>(null) }
    var deleteTarget by remember { mutableStateOf<Document?>(null) }

    // Tarea larga en curso (compartir PDF / duplicar) con su progreso.
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableFloatStateOf(-1f) }
    var busyJob by remember { mutableStateOf<Job?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onImportImages(uris)
    }
    val pickImages: () -> Unit = {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No hay una galería disponible", Toast.LENGTH_SHORT).show()
        }
    }

    // Búsqueda por título y por texto reconocido (OCR) de las páginas.
    val filtered = remember(documents, query) {
        val q = query.trim()
        if (q.isEmpty()) documents
        else documents.filter { d ->
            d.title.contains(q, ignoreCase = true) || d.pages.any { it.ocrText?.contains(q, ignoreCase = true) == true }
        }
    }
    val totalPages = remember(documents) { documents.sumOf { it.pages.size } }

    val quickActions = remember(brand) {
        listOf(
            QuickAction("Documento", "Hojas y cartas", Icons.Rounded.Description, brand.gradientStart) { onScan(ScanMode.DOCUMENT) },
            QuickAction("Libro", "2 páginas", Icons.AutoMirrored.Rounded.MenuBook, brand.gradientStart) { onScan(ScanMode.BOOK) },
            QuickAction("DNI / Tarjeta", "Anverso y reverso", Icons.Rounded.Badge, brand.gradientEnd) { onScan(ScanMode.ID_CARD) },
            QuickAction("Recibo", "Tickets largos", Icons.AutoMirrored.Rounded.ReceiptLong, Color(0xFFFFB547)) { onScan(ScanMode.RECEIPT) },
            QuickAction("Pizarra", "Sin reflejos", Icons.Rounded.CoPresent, Color(0xFF4CC3FF)) { onScan(ScanMode.WHITEBOARD) },
            QuickAction("Foto", "Color, sin recorte", Icons.Rounded.PhotoCamera, Color(0xFF8BD450)) { onScan(ScanMode.PHOTO) },
            QuickAction("Galería", "Importar fotos", Icons.Rounded.PhotoLibrary, Color(0xFFFF8A4C)) { pickImages() },
            QuickAction("Comprimir", "Reducir PDF", Icons.Rounded.Compress, Color(0xFFFF5C9A)) { onCompressPdf() },
            QuickAction("Ajustes", "Filtro y calidad", Icons.Rounded.Settings, brand.gradientEnd) { onSettings() },
        )
    }

    fun runBusy(message: String, block: suspend (progress: (Float) -> Unit) -> Unit) {
        if (busyJob != null) return
        busyJob = scope.launch {
            busyMessage = message
            busyProgress = -1f
            try {
                block { p -> busyProgress = p }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                snackbar.showSnackbar(t.message ?: "Ocurrió un error")
            } finally {
                busyMessage = null
                busyJob = null
            }
        }
    }

    fun actionsFor(doc: Document) = DocumentActions(
        onRename = { renameTarget = doc },
        onDuplicate = {
            runBusy("Duplicando documento…") {
                container.documents.duplicate(doc.id)
                snackbar.showSnackbar("Documento duplicado")
            }
        },
        onSharePdf = if (doc.pages.isEmpty()) null else {
            {
                runBusy("Generando PDF…") { progress ->
                    val pdf = buildSharePdf(container, doc, progress)
                    val intent = container.imageExporter.shareChooser(listOf(pdf), "application/pdf", "Compartir PDF")
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        snackbar.showSnackbar("No hay apps para compartir")
                    }
                }
            }
        },
        onDelete = { deleteTarget = doc },
    )

    val gridState = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex < 3 } }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
            floatingActionButton = {
                PrimaryFab(
                    onClick = { onScan(ScanMode.DOCUMENT) },
                    expanded = fabExpanded,
                    // El halo pulsante solo en el primer uso (sin documentos): ahorra batería y no distrae.
                    pulse = documents.isEmpty() && !container.deviceTier.isLowRam,
                    modifier = Modifier.navigationBarsPadding(),
                )
            },
        ) { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner),
            ) {
                // Halo de marca detrás de la cabecera.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .background(brand.backdrop),
                )
                LazyVerticalGrid(
                    state = gridState,
                    columns = if (gridMode) GridCells.Adaptive(minSize = 150.dp) else GridCells.Fixed(1),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 128.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    fullItem("header") {
                        Header(
                            docCount = documents.size,
                            pageCount = totalPages,
                            lowRam = container.deviceTier.isLowRam,
                            onSettings = onSettings,
                        )
                    }
                    fullItem("search") {
                        SearchField(query = query, onQueryChange = { query = it }, onClear = { query = ""; focus.clearFocus() })
                    }
                    if (query.isBlank()) {
                        fullItem("hero") { HeroScanCard(onClick = { onScan(ScanMode.DOCUMENT) }) }
                        fullItem("quick") { QuickActionsGrid(quickActions) }
                    }
                    fullItem("recentHeader") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            SectionHeader(
                                title = if (query.isBlank()) "Recientes" else "Resultados",
                                subtitle = when {
                                    filtered.isEmpty() -> null
                                    filtered.size == 1 -> "1 documento"
                                    else -> "${filtered.size} documentos"
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (documents.isNotEmpty()) {
                                IconButton(onClick = { gridMode = !gridMode }) {
                                    Icon(
                                        if (gridMode) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                                        contentDescription = if (gridMode) "Ver como lista" else "Ver como cuadrícula",
                                    )
                                }
                            }
                        }
                    }
                    when {
                        documents.isEmpty() -> fullItem("empty") {
                            EmptyState(
                                title = "Tu primer escaneo te espera",
                                message = "Escanea documentos, libros, DNI o recibos. Los recortamos y mejoramos automáticamente para que se vean perfectos.",
                                // Sin botón propio: la tarjeta principal y el botón flotante ya invitan a escanear.
                                actionLabel = null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        filtered.isEmpty() -> fullItem("noResults") { NoResults(query) }
                        else -> items(filtered, key = { it.id }, contentType = { if (gridMode) "card" else "row" }) { doc ->
                            val thumb = remember(doc.id, doc.pages.firstOrNull()?.thumbFile) {
                                doc.pages.firstOrNull()?.thumbFile?.let { File(container.documents.docDir(doc.id), it) }
                            }
                            if (gridMode) {
                                DocumentCard(
                                    title = doc.title,
                                    pageCount = doc.pages.size,
                                    updatedAt = doc.updatedAt,
                                    thumbnail = thumb,
                                    mode = doc.mode,
                                    onClick = { onOpenDocument(doc.id) },
                                    actions = actionsFor(doc),
                                    modifier = Modifier.animateItem(),
                                )
                            } else {
                                DocumentListItem(
                                    title = doc.title,
                                    pageCount = doc.pages.size,
                                    updatedAt = doc.updatedAt,
                                    thumbnail = thumb,
                                    mode = doc.mode,
                                    onClick = { onOpenDocument(doc.id) },
                                    actions = actionsFor(doc),
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
        LoadingOverlay(
            visible = busyMessage != null,
            message = busyMessage.orEmpty(),
            progress = busyProgress.takeIf { it >= 0f },
            onCancel = { busyJob?.cancel() },
        )
    }

    renameTarget?.let { doc ->
        RenameDialog(
            initial = doc.title,
            onConfirm = { name -> scope.launch { runCatching { container.documents.rename(doc.id, name) } } },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { doc ->
        ConfirmDialog(
            title = "¿Eliminar documento?",
            message = "Se eliminará «${doc.title}» con sus ${doc.pages.size} página(s). Esta acción no se puede deshacer.",
            confirmLabel = "Eliminar",
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                scope.launch {
                    runCatching { container.documents.delete(doc.id) }
                        .onSuccess { snackbar.showSnackbar("Documento eliminado") }
                        .onFailure { snackbar.showSnackbar("No se pudo eliminar") }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

private fun LazyGridScope.fullItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

// ----------------------------------------------------------------------------------- secciones

@Composable
private fun Header(docCount: Int, pageCount: Int, lowRam: Boolean, onSettings: () -> Unit) {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Buenos días"
            in 12..19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradientText(
                    "ESCÁNER PRO MAX",
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                )
            }
            Surface(
                onClick = onSettings,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.brand.cardBorder),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Settings, contentDescription = "Ajustes") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (docCount) {
                    0 -> "Escanea, mejora y comparte en segundos"
                    1 -> "1 documento · $pageCount ${if (pageCount == 1) "página" else "páginas"}"
                    else -> "$docCount documentos · $pageCount páginas"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lowRam) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = PillShape, color = MaterialTheme.brand.accent.copy(alpha = 0.15f), contentColor = MaterialTheme.brand.accent) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bolt, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Modo ligero", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Buscar documentos o texto…") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, contentDescription = "Limpiar búsqueda") }
            }
        },
        singleLine = true,
        shape = PillShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Tarjeta principal con degradado: escanear documento. */
@Composable
private fun HeroScanCard(onClick: () -> Unit) {
    val brand = MaterialTheme.brand
    Surface(onClick = onClick, shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, contentColor = Color.White) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(brand.gradient),
        ) {
            // Círculos decorativos translúcidos.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 0.dp)
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent))),
            )
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mejora mágica + OCR", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Escanear documento", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Bordes automáticos, sin sombras y nitidez HD incluso con poca luz.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.88f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.DocumentScanner, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(18.dp),
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun QuickActionsGrid(actions: List<QuickAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { action -> QuickActionTile(action, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QuickActionTile(action: QuickAction, modifier: Modifier = Modifier) {
    val brand = MaterialTheme.brand
    Surface(
        onClick = action.onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = brand.card,
        border = BorderStroke(1.dp, brand.cardBorder),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(action.tint.copy(alpha = if (brand.isDark) 0.18f else 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(action.icon, contentDescription = null, tint = action.tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(action.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                action.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoResults(query: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Sin resultados", style = MaterialTheme.typography.titleMedium)
        Text(
            "No encontramos «${query.trim()}» en títulos ni en el texto reconocido.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
