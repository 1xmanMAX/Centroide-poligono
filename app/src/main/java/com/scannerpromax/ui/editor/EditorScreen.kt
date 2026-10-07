package com.scannerpromax.ui.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Deblur
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import com.scannerpromax.ui.review.zoomGestures
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.EraseMode
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Quad
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.LoadingOverlay
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class EditorTool(val label: String, val icon: ImageVector) {
    CROP("Recortar", Icons.Filled.Crop),
    FILTERS("Filtros", Icons.Filled.AutoAwesome),
    CLEAN("Limpiar", Icons.Filled.CleaningServices),
    ROTATE("Rotar", Icons.AutoMirrored.Filled.RotateRight),
}

/** Qué ajustes copiar al resto de páginas con "Aplicar a todas". */
private enum class BulkKind { FILTER, CLEAN }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    container: AppContainer,
    docId: String,
    pageId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Página en edición: se puede pasar a la anterior/siguiente sin salir del editor (se guarda al cambiar).
    var currentPageId by rememberSaveable(pageId) { mutableStateOf(pageId) }
    val doc by remember(docId) { container.documents.observe(docId) }.collectAsState(initial = null)
    val pages = doc?.pages.orEmpty()
    val pageIndex = pages.indexOfFirst { it.id == currentPageId }

    val session = remember(docId, currentPageId) { EditorSession(container, docId, currentPageId) }
    DisposableEffect(session) {
        // El OCR de fondo cede la CPU mientras se editan vistas previas (fluidez en gama baja).
        container.documents.pauseBackgroundOcr()
        session.load()
        onDispose {
            session.dispose()
            container.documents.resumeBackgroundOcr()
        }
    }

    var tool by rememberSaveable { mutableStateOf(EditorTool.FILTERS) }
    var erasing by remember { mutableStateOf(false) }
    var brushRadius by rememberSaveable { mutableFloatStateOf(0.018f) }
    var eraseMode by rememberSaveable { mutableStateOf(EraseMode.HEAL) }
    var showOriginal by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var quadOnEnterCrop by remember { mutableStateOf<Quad?>(null) }
    var bulkProgress by remember { mutableStateOf<Float?>(null) }
    var bulkMessage by remember { mutableStateOf("") }
    var bulkJob by remember { mutableStateOf<Job?>(null) }

    val brand = MaterialTheme.brand
    val perf = LocalPerf.current
    val primary = brand.gradientStart
    val secondary = brand.gradientEnd
    val errorColor = MaterialTheme.colorScheme.error

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // Al cargar una página (también al cambiar de página) se toma su recorte como referencia.
    LaunchedEffect(session, session.loading) {
        if (!session.loading) quadOnEnterCrop = session.edits.quad
    }

    // ¿El recorte actual se puede guardar? (convexo, o solo con las esquinas desordenadas: se reordenan)
    // derivedStateOf: esta pantalla solo se recompone cuando cambia la VALIDEZ, no con cada movimiento de un
    // slider (que cambia session.edits); así un slider solo recompone su panel.
    val quadValid by remember(session) {
        derivedStateOf {
            session.edits.quad?.let { q ->
                isConvexQuad(q.points().map { Offset(it.x, it.y) }) || EditorSession.reorderQuad(q) != null
            } ?: true
        }
    }

    /** Valida el recorte; si no es válido avisa, vuelve a Recortar y devuelve false. */
    fun ensureQuadValid(): Boolean = when (session.checkQuad()) {
        EditorSession.QuadCheck.OK -> true
        EditorSession.QuadCheck.FIXED -> { toast("Se reordenaron las esquinas del recorte"); true }
        EditorSession.QuadCheck.INVALID -> {
            toast("Las esquinas se cruzan: ajústalas o usa Auto-detectar")
            if (tool != EditorTool.CROP) { quadOnEnterCrop = session.edits.quad; tool = EditorTool.CROP }
            erasing = false
            false
        }
    }

    /** Si cambió el recorte, los trazos de borrado ya no coinciden con la imagen: se quitan. */
    fun dropStrokesIfCropChanged(notify: Boolean) {
        if (session.edits.quad != quadOnEnterCrop && session.edits.eraseStrokes.isNotEmpty()) {
            session.clearStrokes()
            if (notify) toast("Se reiniciaron los trazos de borrado al cambiar el recorte")
        }
    }

    fun selectTool(t: EditorTool) {
        if (t == tool) return
        if (tool == EditorTool.CROP) {
            if (!ensureQuadValid()) return
            dropStrokesIfCropChanged(notify = true)
            session.requestPreview()
            session.regenerateThumbs()
        }
        if (t == EditorTool.CROP) quadOnEnterCrop = session.edits.quad
        erasing = false
        tool = t
    }

    /** Guarda (si hay cambios) y después ejecuta [after]. */
    fun saveThen(after: () -> Unit) {
        if (session.saving || session.loading) return
        scope.launch {
            if (!ensureQuadValid()) return@launch
            if (tool == EditorTool.CROP) dropStrokesIfCropChanged(notify = false)
            if (!session.hasChanges || session.save()) after() else toast("No se pudo guardar la página")
        }
    }

    fun saveAndExit() = saveThen(onBack)

    fun goToPage(index: Int) {
        val target = pages.getOrNull(index) ?: return
        if (target.id == currentPageId) return
        saveThen {
            erasing = false
            showOriginal = false
            currentPageId = target.id
        }
    }

    /** Copia el filtro (o la limpieza) de esta página al resto del documento, con progreso. */
    fun applyToAll(kind: BulkKind) {
        if (bulkJob != null || session.saving) return
        bulkJob = scope.launch {
            try {
                if (!ensureQuadValid()) return@launch
                if (session.hasChanges && !session.save()) {
                    toast("No se pudo guardar la página")
                    return@launch
                }
                val src = session.edits
                val others = container.documents.get(docId)?.pages?.filter { it.id != session.pageId }.orEmpty()
                if (others.isEmpty()) {
                    toast("El documento solo tiene esta página")
                    return@launch
                }
                bulkProgress = 0f
                var failed = 0
                others.forEachIndexed { i, p ->
                    bulkMessage = "Aplicando a la página ${i + 1} de ${others.size}…"
                    val e = p.edits
                    val updated = when (kind) {
                        BulkKind.FILTER -> e.copy(filter = src.filter, adjustments = src.adjustments)
                        BulkKind.CLEAN -> e.copy(
                            autoRemoveLines = src.autoRemoveLines,
                            autoDenoise = src.autoDenoise,
                            autoDeskew = src.autoDeskew,
                            // Cambiar el enderezado mueve la geometría: los trazos de esa página dejarían de coincidir.
                            eraseStrokes = if (e.autoDeskew != src.autoDeskew) emptyList() else e.eraseStrokes,
                        )
                    }
                    if (updated != e) {
                        try {
                            container.documents.updateEdits(docId, p.id, updated)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Throwable) {
                            failed++
                        }
                    }
                    bulkProgress = (i + 1f) / others.size
                }
                toast(
                    if (failed == 0) "Aplicado a ${others.size + 1} páginas"
                    else "Aplicado con errores en $failed ${if (failed == 1) "página" else "páginas"}",
                )
            } finally {
                bulkProgress = null
                bulkJob = null
            }
        }
    }

    fun requestExit() {
        when {
            session.saving || bulkJob != null -> Unit
            erasing -> erasing = false
            session.hasChanges -> confirmExit = true
            else -> onBack()
        }
    }
    BackHandler { requestExit() }

    LaunchedEffect(session.loadError) {
        session.loadError?.let { toast(it); onBack() }
    }

    val canApplyAll = pages.size > 1
    val pageLabel = if (pages.size > 1 && pageIndex >= 0) "Página ${pageIndex + 1} de ${pages.size}" else null

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------ barra superior
            AppTopBar(
                title = if (erasing) "Borrado manual" else "Editar página",
                subtitle = if (erasing) "1 dedo: borrar · 2 dedos: zoom" else listOfNotNull(tool.label, pageLabel).joinToString(" · "),
                onBack = { requestExit() },
                actions = {
                    GradientButton(
                        text = "Guardar",
                        onClick = { saveAndExit() },
                        icon = Icons.Filled.Check,
                        enabled = !session.loading && quadValid && bulkJob == null,
                        loading = session.saving,
                        height = 40.dp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )

            // ------------------------------------------------ área de imagen
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                val src = session.sourceImage
                when {
                    session.loading || src == null -> CircularProgressIndicator(color = primary)
                    tool == EditorTool.CROP -> CropTool(
                        image = src,
                        quad = session.workingQuad(),
                        onQuadChange = { session.setWorkingQuad(it, refresh = false) },
                        primary = primary,
                        secondary = secondary,
                        error = errorColor,
                    )
                    erasing && session.preview != null -> EraseTool(
                        image = session.preview!!,
                        strokes = session.edits.eraseStrokes,
                        appliedCount = session.previewEdits?.eraseStrokes?.let { applied ->
                            val cur = session.edits.eraseStrokes
                            if (applied.size <= cur.size && cur.subList(0, applied.size) == applied) applied.size else 0
                        } ?: 0,
                        brushRadius = brushRadius,
                        mode = eraseMode,
                        onStroke = { session.addStroke(it) },
                        accent = brand.accent,
                    )
                    else -> PreviewImage(session, showOriginal)
                }

                // Navegación entre páginas (guarda automáticamente al cambiar)
                if (pages.size > 1 && pageIndex >= 0 && !erasing && !session.loading) {
                    PageSwitcher(
                        index = pageIndex,
                        count = pages.size,
                        enabled = !session.saving && bulkJob == null,
                        onPrev = { goToPage(pageIndex - 1) },
                        onNext = { goToPage(pageIndex + 1) },
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    )
                }

                // Indicador de procesamiento: en su propio ámbito de recomposición (cambia 2 veces por vista previa).
                ProcessingBadge(session, hidden = tool == EditorTool.CROP, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))

                // Antes / después: mantener pulsado
                if (!session.loading && tool != EditorTool.CROP && !erasing) {
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (showOriginal) primary else Color.Black.copy(alpha = 0.6f))
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    showOriginal = true
                                    tryAwaitRelease()
                                    showOriginal = false
                                })
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Compare, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (showOriginal) "Original" else "Mantén para ver el original", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // ------------------------------------------------ panel de herramienta
            // Altura mínima común + animateContentSize: la imagen no salta al cambiar de herramienta.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 150.dp)
                    .then(if (perf.reduceMotion) Modifier else Modifier.animateContentSize(tween(perf.duration(220)))),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnimatedContent(
                    targetState = if (erasing) null else tool,
                    transitionSpec = { fadeIn(tween(perf.duration(200))) togetherWith fadeOut(tween(perf.duration(120))) },
                    label = "toolPanel",
                ) { t ->
                    when (t) {
                        null -> ErasePanel(
                            brushRadius = brushRadius,
                            onBrushRadius = { brushRadius = it },
                            mode = eraseMode,
                            onMode = { eraseMode = it },
                            canUndo = session.edits.eraseStrokes.isNotEmpty(),
                            canRedo = session.canRedo,
                            onUndo = { session.undoStroke() },
                            onRedo = { session.redoStroke() },
                            onClear = { session.clearStrokes() },
                            onDone = { erasing = false },
                        )
                        EditorTool.CROP -> CropPanel(
                            detecting = session.detecting,
                            invalid = !quadValid,
                            onAuto = {
                                scope.launch {
                                    if (!session.autoDetect()) toast("No se detectaron bordes: ajusta las esquinas a mano")
                                }
                            },
                            onFull = { session.setWorkingQuad(null) },
                            onReset = { session.update(session.edits.copy(quad = session.savedEdits.quad), refresh = false) },
                        )
                        EditorTool.FILTERS -> FiltersPanel(session, onApplyAll = if (canApplyAll) ({ applyToAll(BulkKind.FILTER) }) else null)
                        EditorTool.CLEAN -> CleanPanel(
                            session,
                            onManual = {
                                if (session.preview == null) toast("Espera a que termine la vista previa") else erasing = true
                            },
                            onDeskew = { enabled ->
                                if (session.setAutoDeskew(enabled)) toast("Se quitaron los trazos de borrado: cambió el enderezado")
                            },
                            onApplyAll = if (canApplyAll) ({ applyToAll(BulkKind.CLEAN) }) else null,
                        )
                        EditorTool.ROTATE -> RotatePanel(
                            rotation = session.edits.rotation,
                            onLeft = { session.rotate(clockwise = false) },
                            onRight = { session.rotate(clockwise = true) },
                        )
                    }
                }
            }

            // ------------------------------------------------ pestañas
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                EditorTool.entries.forEach { t ->
                    ToolTab(t, selected = t == tool && !erasing, enabled = !session.loading) { selectTool(t) }
                }
            }
        }

        LoadingOverlay(
            visible = session.saving && bulkProgress == null,
            message = "Aplicando mejoras en alta calidad…",
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

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("¿Guardar los cambios?") },
            text = { Text("Has editado esta página. ¿Quieres guardar los cambios antes de salir?") },
            confirmButton = { TextButton(onClick = { confirmExit = false; saveAndExit() }) { Text("Guardar") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false; onBack() }) {
                        Text("Descartar", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmExit = false }) { Text("Seguir editando") }
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

/**
 * Imagen mostrada (vista previa u original). Lee [EditorSession.preview] en su propio ámbito.
 * Pellizcar / doble toque amplía hasta 5x; al soltar, la vista previa se rehace a más resolución
 * ([EditorSession.setZoomDetail]) para que el detalle se vea nítido y no un ampliado borroso.
 */
@Composable
private fun PreviewImage(session: EditorSession, showOriginal: Boolean) {
    val src = session.sourceImage ?: return
    val shown = if (showOriginal) src else (session.preview ?: src)
    val zoom = remember { com.scannerpromax.ui.review.ZoomState(maxScale = 5f) }
    LaunchedEffect(zoom.scale) {
        delay(250)
        session.setZoomDetail(zoom.scale)
    }
    DisposableEffect(Unit) { onDispose { session.setZoomDetail(1f) } }
    Box(
        Modifier
            .fillMaxSize()
            .padding(10.dp)
            .clipToBounds()
            .onSizeChanged { sz ->
                // Tamaño de la imagen ajustada (ContentScale.Fit) para limitar el desplazamiento al ampliar.
                val iw = shown.width.toFloat(); val ih = shown.height.toFloat()
                if (iw > 0f && ih > 0f && sz.width > 0 && sz.height > 0) {
                    val f = minOf(sz.width / iw, sz.height / ih)
                    zoom.content = androidx.compose.ui.geometry.Size(iw * f, ih * f)
                }
            }
            .zoomGestures(zoom),
    ) {
        Image(
            bitmap = shown,
            contentDescription = if (showOriginal) "Original" else "Vista previa",
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
        )
    }
}

@Composable
private fun ProcessingBadge(session: EditorSession, hidden: Boolean, modifier: Modifier = Modifier) {
    // Nombre completo: evita el AnimatedVisibility de ColumnScope.
    androidx.compose.animation.AnimatedVisibility(
        visible = session.previewBusy && !hidden,
        enter = fadeIn(), exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Procesando", color = Color.White, fontSize = 12.sp)
        }
    }
}

/** Píldora "‹ 2 / 5 ›" para cambiar de página dentro del editor. */
@Composable
private fun PageSwitcher(index: Int, count: Int, enabled: Boolean, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val prevOk = enabled && index > 0
        val nextOk = enabled && index < count - 1
        Box(
            Modifier.size(44.dp).clickable(enabled = prevOk, role = Role.Button, onClickLabel = "Página anterior", onClick = onPrev),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Página anterior", tint = Color.White.copy(alpha = if (prevOk) 1f else 0.35f))
        }
        Text("${index + 1} / $count", color = Color.White, style = MaterialTheme.typography.labelLarge)
        Box(
            Modifier.size(44.dp).clickable(enabled = nextOk, role = Role.Button, onClickLabel = "Página siguiente", onClick = onNext),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Página siguiente", tint = Color.White.copy(alpha = if (nextOk) 1f else 0.35f))
        }
    }
}

/** Botón compacto tipo chip para acciones secundarias de los paneles. */
@Composable
private fun ChipAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val brand = MaterialTheme.brand
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) brand.gradientStart.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (selected) brand.gradientStart.copy(alpha = 0.5f) else brand.cardBorder, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = brand.gradientStart, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// =================================================================================================
// Paneles
// =================================================================================================

@Composable
private fun ToolTab(tool: EditorTool, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.brand.gradientStart
    val secondary = MaterialTheme.brand.gradientEnd
    val fg by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabFg")
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.background(MaterialTheme.brand.gradient) else Modifier)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tool.icon, tool.label, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(tool.label, color = fg, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun PanelButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(icon, null, tint = MaterialTheme.brand.gradientStart)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CropPanel(detecting: Boolean, invalid: Boolean, onAuto: () -> Unit, onFull: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (invalid) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Las esquinas se cruzan: ajústalas o usa Auto-detectar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        } else {
            Text(
                "Arrastra las esquinas o los lados. La lupa te ayuda a ajustar con precisión.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(Icons.Filled.AutoAwesome, "Auto-detectar", Modifier.weight(1f), busy = detecting, onClick = onAuto)
            PanelButton(Icons.Filled.CropFree, "Página completa", Modifier.weight(1f), onClick = onFull)
            PanelButton(Icons.Filled.Refresh, "Restablecer", Modifier.weight(1f), onClick = onReset)
        }
    }
}

@Composable
private fun FiltersPanel(session: EditorSession, onApplyAll: (() -> Unit)?) {
    val primary = MaterialTheme.brand.gradientStart
    val edits = session.edits
    // Solo el filtro (estable entre movimientos de slider): las tarjetas no se recomponen al ajustar.
    // Los filtros antiguos de páginas guardadas se muestran como su equivalente visible.
    val selectedFilter = edits.filter.uiFilter
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (f in FilterType.visible) {
                val selected = selectedFilter == f
                val thumb = session.filterThumbs[f]
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.RadioButton) { if (edits.filter != f) session.setFilter(f) }
                        .semantics { this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(112.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .then(
                                if (selected) Modifier.border(2.5.dp, MaterialTheme.brand.gradient, RoundedCornerShape(16.dp))
                                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                            )
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumb != null) {
                            Image(thumb, f.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(13.dp)))
                        } else {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        if (selected) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                    .background(MaterialTheme.brand.gradient),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        f.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val adj = edits.adjustments
        // Los ajustes finos van plegados por defecto: en pantallas pequeñas la imagen necesita el espacio.
        var showFine by rememberSaveable { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChipAction(
                icon = if (showFine) Icons.Filled.ExpandLess else Icons.Filled.Tune,
                label = if (adj != Adjustments()) "Ajustes finos •" else "Ajustes finos",
                onClick = { showFine = !showFine },
                selected = showFine,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onApplyAll != null) {
                ChipAction(Icons.Filled.DoneAll, "Aplicar a todas", onApplyAll, Modifier.weight(1f, fill = false))
            }
        }
        AnimatedVisibility(visible = showFine, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AdjustSlider(Icons.Filled.BrightnessMedium, "Brillo", adj.brightness) { session.setAdjustments(adj.copy(brightness = it)) }
                AdjustSlider(Icons.Filled.Contrast, "Contraste", adj.contrast) { session.setAdjustments(adj.copy(contrast = it)) }
                AdjustSlider(Icons.Filled.Deblur, "Nitidez", adj.sharpness) { session.setAdjustments(adj.copy(sharpness = it)) }
                if (adj != Adjustments()) {
                    TextButton(onClick = { session.setAdjustments(Adjustments()) }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restablecer ajustes")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdjustSlider(icon: ImageVector, label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(78.dp))
        Slider(
            value = value,
            onValueChange = { v -> onChange(if (kotlin.math.abs(v) < 0.04f) 0f else v) },
            valueRange = -1f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.brand.gradientStart,
                activeTrackColor = MaterialTheme.brand.gradientStart,
            ),
        )
        Text(
            "${if (value > 0) "+" else ""}${(value * 100).roundToInt()}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun CleanPanel(session: EditorSession, onManual: () -> Unit, onDeskew: (Boolean) -> Unit, onApplyAll: (() -> Unit)?) {
    val e = session.edits
    val primary = MaterialTheme.brand.gradientStart
    val secondary = MaterialTheme.brand.gradientEnd
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        CleanSwitch(Icons.Filled.LayersClear, "Quitar rayas automáticamente", "Dobleces, marcas de bolígrafo y bordes", e.autoRemoveLines) {
            session.update(e.copy(autoRemoveLines = it))
        }
        CleanSwitch(Icons.Filled.Grain, "Quitar ruido", "Puntos, granulado y suciedad", e.autoDenoise) {
            session.update(e.copy(autoDenoise = it))
        }
        CleanSwitch(Icons.Filled.Straighten, "Enderezar texto", "Corrige unos grados de inclinación", e.autoDeskew) {
            onDeskew(it)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(primary.copy(alpha = 0.16f), secondary.copy(alpha = 0.16f))))
                .border(1.dp, MaterialTheme.brand.horizontalGradient, RoundedCornerShape(16.dp))
                .clickable(onClick = onManual)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Brush, null, tint = primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Borrado manual", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (e.eraseStrokes.isEmpty()) "Quita a mano rayas o manchas que queden"
                    else "${e.eraseStrokes.size} ${if (e.eraseStrokes.size == 1) "trazo aplicado" else "trazos aplicados"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onApplyAll != null) {
            Spacer(Modifier.width(8.dp))
            ChipAction(Icons.Filled.DoneAll, "A todas", onApplyAll, Modifier.height(52.dp))
        }
        }
    }
}

@Composable
private fun CleanSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.brand.gradientStart, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.brand.gradientStart),
        )
    }
}

@Composable
private fun ErasePanel(
    brushRadius: Float,
    onBrushRadius: (Float) -> Unit,
    mode: EraseMode,
    onMode: (EraseMode) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    val primary = MaterialTheme.brand.gradientStart
    val secondary = MaterialTheme.brand.gradientEnd
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeChoice(Icons.Filled.Healing, "Reparar", mode == EraseMode.HEAL, Modifier.weight(1f)) { onMode(EraseMode.HEAL) }
            ModeChoice(Icons.Filled.FormatPaint, "Pintar blanco", mode == EraseMode.WHITE, Modifier.weight(1f)) { onMode(EraseMode.WHITE) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Pincel", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
            Slider(
                value = brushRadius,
                onValueChange = onBrushRadius,
                valueRange = 0.004f..0.06f,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val d = (8f + (brushRadius - 0.004f) / (0.06f - 0.004f) * 28f).dp
                Box(Modifier.size(d).clip(CircleShape).background(MaterialTheme.brand.gradient))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(Icons.AutoMirrored.Filled.Undo, "Deshacer", Modifier.weight(1f), enabled = canUndo, onClick = onUndo)
            PanelButton(Icons.AutoMirrored.Filled.Redo, "Rehacer", Modifier.weight(1f), enabled = canRedo, onClick = onRedo)
            PanelButton(Icons.Filled.Delete, "Borrar todo", Modifier.weight(1f), enabled = canUndo, onClick = onClear)
            PanelButton(Icons.Filled.Check, "Listo", Modifier.weight(1f), onClick = onDone)
        }
    }
}

@Composable
private fun ModeChoice(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.brand.gradientStart
    val secondary = MaterialTheme.brand.gradientEnd
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) Modifier.background(MaterialTheme.brand.horizontalGradient)
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RotatePanel(rotation: Int, onLeft: () -> Unit, onRight: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Rotación: $rotation°",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(Icons.AutoMirrored.Filled.RotateLeft, "Girar a la izquierda", Modifier.weight(1f), onClick = onLeft)
            PanelButton(Icons.AutoMirrored.Filled.RotateRight, "Girar a la derecha", Modifier.weight(1f), onClick = onRight)
        }
    }
}
