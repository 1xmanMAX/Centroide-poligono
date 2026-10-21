package com.scannerpromax.ui.home

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.data.VersionedFile
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.ConfirmDialog
import com.scannerpromax.ui.components.DocumentActions
import com.scannerpromax.ui.components.DocumentCard
import com.scannerpromax.ui.components.DocumentListItem
import com.scannerpromax.ui.components.FloatingBar
import com.scannerpromax.ui.components.FloatingBarContentPadding
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.ListPosition
import com.scannerpromax.ui.components.ProgressHud
import com.scannerpromax.ui.components.MenuScrim
import com.scannerpromax.ui.components.MoreMenu
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.RenameDialog
import com.scannerpromax.ui.components.SearchField
import com.scannerpromax.ui.components.Thumbnail
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.ui
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inicio (rediseño estilo Apple, maquetas "Main" y "Menu"): título grande "Documentos" con menú •••, buscador,
 * "Recientes" + recuento, cuadrícula de 2 columnas (o lista) y cápsula flotante Galería | Escanear.
 *
 * Los 6 modos de escaneo ya no están aquí: "Escanear" abre la cámara en modo Documento y el modo se cambia en el
 * selector de la propia cámara.
 */
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

    // Búsqueda por título y por texto reconocido (OCR) de las páginas. Buscar en el texto OCR de cientos de
    // páginas en cada tecla bloqueaba el hilo principal en gama baja: se hace en segundo plano con un pequeño
    // retardo (las pulsaciones rápidas cancelan la búsqueda anterior). Sin consulta, la lista va directa.
    var filtered by remember { mutableStateOf(documents) }
    LaunchedEffect(documents, query) {
        val q = query.trim()
        val result = if (q.isEmpty()) {
            documents
        } else {
            delay(SEARCH_DEBOUNCE_MS)
            withContext(Dispatchers.Default) {
                documents.filter { d ->
                    d.title.contains(q, ignoreCase = true) || d.pages.any { p ->
                        (p.ocrEditedText ?: p.ocrText)?.contains(q, ignoreCase = true) == true
                    }
                }
            }
        }
        filtered = result
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

    Box(Modifier.fillMaxSize()) {
        HomeContent(
            documents = documents,
            filtered = filtered,
            query = query,
            onQueryChange = { query = it },
            gridMode = gridMode,
            onToggleGrid = { gridMode = !gridMode },
            actionsFor = { doc -> actionsFor(doc) },
            thumbnail = { doc, modifier ->
                // Miniatura del procesado ACTUAL de la primera página (con su versión para la caché), en IO.
                val first = doc.pages.firstOrNull()
                val thumb by produceState<VersionedFile?>(null, doc.id, first?.id, first?.thumbFile, first?.processedFile, first?.edits) {
                    value = first?.let { p -> withContext(Dispatchers.IO) { container.documents.displayThumb(doc.id, p) } }
                }
                Thumbnail(thumb, doc.mode, modifier, sizePx = if (gridMode) 0 else 200)
            },
            onOpenDocument = onOpenDocument,
            onScan = { onScan(ScanMode.DOCUMENT) },
            onPickImages = pickImages,
            onCompressPdf = onCompressPdf,
            onSettings = onSettings,
            snackbar = snackbar,
        )
        ProgressHud(
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

/**
 * Contenido visual de Inicio, sin dependencias del contenedor (lo usan [HomeScreen] y las capturas de prueba).
 * [thumbnail] dibuja la miniatura de un documento ocupando el modifier recibido.
 */
@Composable
internal fun HomeContent(
    documents: List<Document>,
    filtered: List<Document>,
    query: String,
    onQueryChange: (String) -> Unit,
    gridMode: Boolean,
    onToggleGrid: () -> Unit,
    actionsFor: (Document) -> DocumentActions,
    thumbnail: @Composable (Document, Modifier) -> Unit,
    onOpenDocument: (String) -> Unit,
    onScan: () -> Unit,
    onPickImages: () -> Unit,
    onCompressPdf: () -> Unit,
    onSettings: () -> Unit,
    snackbar: SnackbarHostState,
    menuInitiallyOpen: Boolean = false,
) {
    val ui = MaterialTheme.ui
    val perf = LocalPerf.current
    var menuOpen by rememberSaveable { mutableStateOf(menuInitiallyOpen) }
    val gridState = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 } }
    val totalPages = remember(documents) { documents.sumOf { it.pages.size } }
    val searching = query.isNotBlank()

    val menuGroups = homeMenuGroups(gridMode, onToggleGrid, onPickImages, onCompressPdf, onSettings)

    Box(
        Modifier
            .fillMaxSize()
            .background(ui.groupedBackground),
    ) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(if (gridMode) 2 else 1),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = statusTop, bottom = FloatingBarContentPadding + navBottom),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            fullItem("header") {
                LargeTitleHeader(
                    title = "Documentos",
                    // El margen lateral ya lo da la lista.
                    horizontalPadding = 0.dp,
                    actions = {
                        Box {
                            IconCircleButton(AppIcons.More, contentDescription = "Más opciones", onClick = { menuOpen = true })
                            MoreMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, groups = menuGroups)
                        }
                    },
                )
            }
            fullItem("search") {
                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    placeholder = "Buscar documentos o texto",
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (documents.isNotEmpty()) {
                fullItem("recentHeader") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            if (searching) "Resultados" else "Recientes",
                            style = MaterialTheme.typography.titleLarge,
                            color = ui.label,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (searching) countLabel(filtered.size, "resultado", "resultados")
                            else countLabel(documents.size, "documento", "documentos") + " · " + countLabel(totalPages, "página", "páginas"),
                            style = MaterialTheme.typography.bodySmall,
                            color = ui.secondaryLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                        )
                    }
                }
            }
            when {
                documents.isEmpty() -> fullItem("empty") {
                    QuietMessage(
                        title = "Sin documentos",
                        message = "Pulsa Escanear para digitalizar tu primer documento o importa fotos desde la galería.",
                    )
                }
                filtered.isEmpty() -> fullItem("noResults") {
                    QuietMessage(
                        title = "Sin resultados",
                        message = "No encontramos «${query.trim()}» en los títulos ni en el texto reconocido.",
                    )
                }
                else -> itemsIndexed(filtered, key = { _, d -> d.id }, contentType = { _, _ -> if (gridMode) "card" else "row" }) { index, doc ->
                    // Misma instancia mientras el documento no cambie: la tarjeta se salta la recomposición.
                    val actions = remember(doc) { actionsFor(doc) }
                    // Sin animación de recolocación si el sistema tiene "Quitar animaciones".
                    val itemModifier = if (perf.reduceMotion) Modifier else Modifier.animateItem()
                    if (gridMode) {
                        DocumentCard(
                            title = doc.title,
                            pageCount = doc.pages.size,
                            updatedAt = doc.updatedAt,
                            thumbnail = null,
                            mode = doc.mode,
                            onClick = { onOpenDocument(doc.id) },
                            actions = actions,
                            modifier = itemModifier.padding(bottom = 18.dp),
                            thumbnailContent = { thumbnail(doc, Modifier.fillMaxSize()) },
                        )
                    } else {
                        val position = when {
                            filtered.size == 1 -> ListPosition.Single
                            index == 0 -> ListPosition.First
                            index == filtered.lastIndex -> ListPosition.Last
                            else -> ListPosition.Middle
                        }
                        DocumentListItem(
                            title = doc.title,
                            pageCount = doc.pages.size,
                            updatedAt = doc.updatedAt,
                            thumbnail = null,
                            mode = doc.mode,
                            onClick = { onOpenDocument(doc.id) },
                            actions = actions,
                            position = position,
                            modifier = itemModifier,
                            thumbnailContent = { thumbnail(doc, Modifier.fillMaxSize()) },
                        )
                    }
                }
            }
        }

        // Al desplazar, la barra de estado se cubre con el fondo para que el contenido no pase bajo los iconos.
        if (scrolled) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(ui.groupedBackground.copy(alpha = 0.94f)),
            )
        }

        FloatingBar(Modifier.align(Alignment.BottomCenter)) {
            IconCircleButton(
                icon = AppIcons.Gallery,
                contentDescription = "Importar de la galería",
                onClick = onPickImages,
                size = 52.dp,
                iconSize = 24.dp,
                containerColor = if (ui.isDark) ui.fill else ui.groupedBackground,
                elevation = 0.dp,
            )
            PrimaryCapsuleButton(text = "Escanear", icon = AppIcons.Scan, onClick = onScan)
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 92.dp),
        )

        MenuScrim(visible = menuOpen)
    }
}

/**
 * Opciones del menú ••• de Inicio (maqueta "Menu"), en grupos separados por la banda gris.
 * "Seleccionar" y "Ordenar" de la maqueta NO se muestran: la app no tiene selección múltiple ni orden configurable
 * (siempre por fecha de modificación) y el rediseño no añade funciones.
 */
internal fun homeMenuGroups(
    gridMode: Boolean,
    onToggleGrid: () -> Unit,
    onPickImages: () -> Unit,
    onCompressPdf: () -> Unit,
    onSettings: () -> Unit,
): List<List<MoreMenuItem>> = listOf(
    listOf(
        if (gridMode) MoreMenuItem("Ver como lista", AppIcons.List, onClick = onToggleGrid)
        else MoreMenuItem("Ver como cuadrícula", AppIcons.Grid, onClick = onToggleGrid),
    ),
    listOf(
        MoreMenuItem("Importar fotos", AppIcons.ImportPhotos, onClick = onPickImages),
        MoreMenuItem("Comprimir PDF", AppIcons.Compress, onClick = onCompressPdf),
    ),
    listOf(MoreMenuItem("Ajustes", AppIcons.Settings, onClick = onSettings)),
)

/** Estado vacío sobrio: título y una línea gris centrados, sin ilustraciones. */
@Composable
private fun QuietMessage(title: String, message: String) {
    val ui = MaterialTheme.ui
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 72.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Document, contentDescription = null, tint = ui.tertiaryLabel, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = ui.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel, textAlign = TextAlign.Center)
    }
}

private fun countLabel(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

private const val SEARCH_DEBOUNCE_MS = 150L

private fun LazyGridScope.fullItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}
