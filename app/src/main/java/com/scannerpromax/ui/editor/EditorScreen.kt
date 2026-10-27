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
import androidx.compose.material.icons.automirrored.filled.Notes
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
import androidx.compose.material.icons.filled.GridOn
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
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.components.AlertAction
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.IosAlert
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.ProgressHud
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.theme.ForcedDarkTheme
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.ui
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class EditorTool(val label: String, val icon: ImageVector) {
    CROP("Recortar", AppIcons.Crop),
    FILTERS("Filtros", AppIcons.Filters),
    CLEAN("Limpiar", AppIcons.Clean),
    ROTATE("Rotar", AppIcons.Rotate),
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

    val perf = LocalPerf.current
    val primary = MaterialTheme.ui.accent
    val secondary = MaterialTheme.ui.accent
    val errorColor = MaterialTheme.ui.red

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
                        BulkKind.FILTER -> e.copy(filter = src.filter, adjustments = src.adjustments, photoMode = src.photoMode)
                        BulkKind.CLEAN -> e.copy(
                            autoRemoveLines = src.autoRemoveLines,
                            autoDenoise = src.autoDenoise,
                            autoDeskew = src.autoDeskew,
                            autoDewarp = src.autoDewarp,
                            keepRuling = src.keepRuling,
                            photoMode = src.photoMode,
                            // Cambiar el enderezado mueve la geometría: los trazos de esa página dejarían de coincidir.
                            eraseStrokes = if (e.autoDeskew != src.autoDeskew || e.autoDewarp != src.autoDewarp) emptyList() else e.eraseStrokes,
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

    ForcedDarkTheme {
    Box(Modifier.fillMaxSize().background(EditorBackground)) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------ barra superior: Cancelar | ‹ Página X de N › | Listo
            EditorTopBar(
                title = when {
                    erasing -> "Borrado manual"
                    pageLabel != null -> pageLabel
                    else -> "Editar página"
                },
                canPrev = !erasing && !session.loading && !session.saving && bulkJob == null && pageIndex > 0,
                canNext = !erasing && !session.loading && !session.saving && bulkJob == null && pageIndex >= 0 && pageIndex < pages.size - 1,
                showPager = pages.size > 1 && pageIndex >= 0 && !erasing,
                onPrev = { goToPage(pageIndex - 1) },
                onNext = { goToPage(pageIndex + 1) },
                onCancel = { requestExit() },
                onDone = { saveAndExit() },
                doneEnabled = !session.loading && quadValid && bulkJob == null,
                saving = session.saving,
            )

            // ------------------------------------------------ vista previa centrada
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val src = session.sourceImage
                when {
                    // Mientras se abre la imagen de trabajo ya se ve el procesado guardado (resultado final, al instante).
                    (session.loading || src == null) && session.preview != null && tool != EditorTool.CROP ->
                        PreviewImage(session, showOriginal = false)
                    session.loading || src == null -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 3.dp)
                    tool == EditorTool.CROP -> CropTool(
                        image = src,
                        quad = session.workingQuad(),
                        onQuadChange = { session.setWorkingQuad(it, refresh = false, manual = true) },
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
                        accent = primary,
                    )
                    else -> PreviewImage(session, showOriginal)
                }

                // Indicador de procesamiento: en su propio ámbito de recomposición (cambia 2 veces por vista previa).
                ProcessingBadge(session, hidden = tool == EditorTool.CROP, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            }

            // Antes / después: mantener pulsado. En borrado manual, la ayuda de gestos.
            Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
                when {
                    erasing -> Text("1 dedo: borrar · 2 dedos: zoom", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, style = MaterialTheme.typography.bodySmall)
                    !session.loading && tool != EditorTool.CROP -> HoldForOriginal(showOriginal) { showOriginal = it }
                }
            }

            // ------------------------------------------------ panel de la herramienta (encima de las herramientas)
            // Altura mínima común + animateContentSize: la imagen no salta al cambiar de herramienta.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .heightIn(min = 132.dp)
                    .then(if (perf.reduceMotion) Modifier else Modifier.animateContentSize(tween(perf.duration(220)))),
                contentAlignment = Alignment.Center,
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
                            onReset = { session.update(session.edits.copy(quad = session.savedEdits.quad, manualQuad = session.savedEdits.manualQuad), refresh = false) },
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
                            onDewarp = { enabled ->
                                if (session.setAutoDewarp(enabled)) toast("Se quitaron los trazos de borrado: cambió el enderezado")
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

            // ------------------------------------------------ 4 herramientas
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EditorTool.entries.forEach { t ->
                    ToolTab(t, selected = t == tool && !erasing, enabled = !session.loading) { selectTool(t) }
                }
            }
        }

        ProgressHud(
            visible = session.saving && bulkProgress == null,
            message = if (session.previewFinal && session.previewEdits == session.edits) "Guardando…" else "Procesando la página en alta calidad…",
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

    if (confirmExit) {
        IosAlert(
            title = "¿Guardar los cambios?",
            message = "Has editado esta página. ¿Quieres guardar los cambios antes de salir?",
            onDismiss = { confirmExit = false },
            actions = listOf(
                AlertAction("Guardar", bold = true) { saveAndExit() },
                AlertAction("Descartar", destructive = true) { onBack() },
                AlertAction("Seguir editando") { },
            ),
        )
    }
    }
}

/** Fondo del editor (casi negro, como Fotos). */
internal val EditorBackground = Color(0xFF0A0A0A)
private val PanelFill = Color.White.copy(alpha = 0.10f)
private val Dim = Color.White.copy(alpha = 0.6f)

/** Barra superior del editor: "Cancelar" · "‹ Página X de N ›" · cápsula "Listo". */
@Composable
internal fun EditorTopBar(
    title: String,
    canPrev: Boolean,
    canNext: Boolean,
    showPager: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    doneEnabled: Boolean,
    saving: Boolean,
) {
    val ui = MaterialTheme.ui
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            "Cancelar",
            color = Color.White,
            fontSize = 17.sp,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (showPager) PagerArrow(AppIcons.Back, "Página anterior", canPrev, onPrev)
            Text(
                title,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showPager) PagerArrow(AppIcons.Chevron, "Página siguiente", canNext, onNext)
        }
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .height(34.dp)
                .clip(PillShape)
                .background(ui.accent)
                .clickable(enabled = doneEnabled && !saving, role = Role.Button, onClick = onDone)
                .graphicsLayer { alpha = if (doneEnabled) 1f else 0.45f }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text("Listo", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun PagerArrow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.25f), modifier = Modifier.size(16.dp))
    }
}

/** "Mantén pulsado para ver el original": mientras se pulsa se muestra la foto sin procesar. */
@Composable
internal fun HoldForOriginal(showing: Boolean, onShow: (Boolean) -> Unit) {
    Text(
        if (showing) "Original" else "Mantén pulsado para ver el original",
        color = if (showing) Color.White else Color.White.copy(alpha = 0.55f),
        fontSize = 12.sp,
        fontWeight = if (showing) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .clip(PillShape)
            .background(if (showing) PanelFill else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onShow(true)
                    tryAwaitRelease()
                    onShow(false)
                })
            }
            .semantics { contentDescription = "Mantén pulsado para ver el original" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Imagen mostrada (vista previa u original). Lee [EditorSession.preview] en su propio ámbito.
 * Pellizcar / doble toque amplía hasta 5x; al soltar, la vista previa se rehace a más resolución
 * ([EditorSession.setZoomDetail]) para que el detalle se vea nítido y no un ampliado borroso.
 */
@Composable
private fun PreviewImage(session: EditorSession, showOriginal: Boolean) {
    val src = session.sourceImage
    val shown = (if (showOriginal) src else null) ?: session.preview ?: src ?: return
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

/**
 * Estado de lo que se ve: "Procesando" (vista previa en curso), "Calculando resultado final" (render final real en
 * segundo plano), "Resultado final" (lo mostrado ES el procesado que se guarda/exporta) o "Vista previa" (aproximada:
 * el resultado final se calcula al guardar).
 */
@Composable
private fun ProcessingBadge(session: EditorSession, hidden: Boolean, modifier: Modifier = Modifier) {
    val busy = session.previewBusy || session.finalizing
    val text = when {
        session.previewBusy -> "Procesando"
        session.finalizing -> "Calculando resultado final"
        session.previewFinal -> "Resultado final"
        else -> if (session.backgroundFinal) "Vista previa" else "Vista previa · el resultado final se calcula al guardar"
    }
    // Nombre completo: evita el AnimatedVisibility de ColumnScope.
    androidx.compose.animation.AnimatedVisibility(
        visible = !hidden && session.preview != null,
        enter = fadeIn(), exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            } else if (session.previewFinal) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// =================================================================================================
// Paneles (oscuros, estilo Fotos)
// =================================================================================================

/** Herramienta de la barra inferior: icono 24 + etiqueta 11; la activa en blanco, el resto al 60 %. */
@Composable
internal fun ToolTab(tool: EditorTool, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val fg by animateColorAsState(if (selected) Color.White else Dim, label = "tabFg")
    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tool.icon, null, tint = fg, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(5.dp))
        Text(tool.label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
    }
}

/** Botón de panel: icono + etiqueta sobre relleno blanco 10 %, radio 14. */
@Composable
private fun PanelButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PanelFill)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Acción de texto discreta ("Ajustes finos", "Aplicar a todas"). */
@Composable
internal fun TextAction(label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    Text(
        label,
        color = if (highlighted) Color.White else Color.White.copy(alpha = 0.7f),
        fontSize = 13.sp,
        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

@Composable
private fun CropPanel(detecting: Boolean, invalid: Boolean, onAuto: () -> Unit, onFull: () -> Unit, onReset: () -> Unit) {
    val ui = MaterialTheme.ui
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (invalid) "Las esquinas se cruzan: ajústalas o usa Auto-detectar"
            else "Arrastra las esquinas o los lados. La lupa te ayuda a ajustar con precisión.",
            color = if (invalid) ui.red else Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = if (invalid) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(AppIcons.Sparkles, "Auto-detectar", Modifier.weight(1f), busy = detecting, onClick = onAuto)
            PanelButton(AppIcons.Resolution, "Página completa", Modifier.weight(1f), onClick = onFull)
            PanelButton(AppIcons.Rotate, "Restablecer", Modifier.weight(1f), onClick = onReset)
        }
    }
}

@Composable
private fun FiltersPanel(session: EditorSession, onApplyAll: (() -> Unit)?) {
    val edits = session.edits
    // Solo el filtro (estable entre movimientos de slider): las miniaturas no se recomponen al ajustar.
    // Los filtros antiguos de páginas guardadas se muestran como su equivalente visible.
    val selectedFilter = edits.filter.uiFilter
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // (tres filtros: Blanco y negro, Color natural, Color original)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (f in FilterType.visible) {
                val selected = selectedFilter == f
                FilterThumb(
                    label = f.label,
                    thumb = session.filterThumbs[f],
                    selected = selected,
                    onClick = { if (edits.filter != f) session.setFilter(f) },
                )
            }
        }
        // Fotos de la página (sólo "Color natural"): recortarlas enteras con su color o tratarlas como el resto
        if (selectedFilter == FilterType.NATURAL) {
            val mode = edits.photoMode ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text("Fotos:", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                for ((m, label) in listOf(0 to "Auto", 1 to "Recortar", 2 to "Como el resto")) {
                    val sel = mode == m
                    Text(
                        label,
                        color = if (sel) Color.Black else Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (sel) Color.White else Color.White.copy(alpha = 0.12f))
                            .selectable(selected = sel, role = Role.RadioButton) { if (!sel) session.update(edits.copy(photoMode = m.takeIf { it != 0 })) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        val adj = edits.adjustments
        // Los ajustes finos van plegados por defecto: en pantallas pequeñas la imagen necesita el espacio.
        var showFine by rememberSaveable { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            TextAction(if (adj != Adjustments()) "Ajustes finos •" else "Ajustes finos", { showFine = !showFine }, highlighted = showFine)
            if (onApplyAll != null) {
                Text("·", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                TextAction("Aplicar a todas", onApplyAll)
            }
        }
        AnimatedVisibility(visible = showFine, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                AdjustSlider("Brillo", adj.brightness) { session.setAdjustments(adj.copy(brightness = it)) }
                AdjustSlider("Contraste", adj.contrast) { session.setAdjustments(adj.copy(contrast = it)) }
                AdjustSlider("Nitidez", adj.sharpness) { session.setAdjustments(adj.copy(sharpness = it)) }
                if (adj != Adjustments()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextAction("Restablecer ajustes", { session.setAdjustments(Adjustments()) })
                    }
                }
            }
        }
    }
}

/** Miniatura de filtro (72×92, radio 10) con aro blanco si está elegida y su nombre debajo. */
@Composable
internal fun FilterThumb(label: String, thumb: ImageBitmap?, selected: Boolean, onClick: () -> Unit, placeholder: @Composable () -> Unit = {}) {
    Column(
        Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 72.dp, height = 92.dp)
                .border(2.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(thumb, label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                placeholder()
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = if (selected) Color.White else Dim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(78.dp))
        DarkSlider(
            value = value,
            onValueChange = { v -> onChange(if (kotlin.math.abs(v) < 0.04f) 0f else v) },
            valueRange = -1f..1f,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${if (value > 0) "+" else ""}${(value * 100).roundToInt()}",
            color = Dim,
            fontSize = 12.sp,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** Deslizador estilo iOS sobre fondo oscuro: pulgar blanco, pista activa en el acento. */
@Composable
private fun DarkSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = MaterialTheme.ui.accent,
            inactiveTrackColor = Color.White.copy(alpha = 0.2f),
        ),
    )
}

@Composable
private fun CleanPanel(
    session: EditorSession,
    onManual: () -> Unit,
    onDeskew: (Boolean) -> Unit,
    onDewarp: (Boolean) -> Unit,
    onApplyAll: (() -> Unit)?,
) {
    val e = session.edits
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InsetGroup(separatorInset = SeparatorInsetPlain, horizontalPadding = 16.dp) {
            item {
                ToggleRow("Quitar rayas automáticamente", e.autoRemoveLines, { session.update(e.copy(autoRemoveLines = it)) }, subtitle = "Dobleces, marcas de bolígrafo y bordes")
            }
            item {
                ToggleRow("Quitar ruido", e.autoDenoise, { session.update(e.copy(autoDenoise = it)) }, subtitle = "Puntos, granulado y suciedad")
            }
            item { ToggleRow("Enderezar texto", e.autoDeskew, onDeskew, subtitle = "Corrige unos grados de inclinación") }
            item {
                ToggleRow("Enderezar hoja curvada", e.autoDewarp, onDewarp, subtitle = "Aplana hojas dobladas usando las líneas de tablas y cuadrículas")
            }
            item {
                ToggleRow(
                    "Conservar cuadrícula / renglones", e.keepRuling, { session.update(e.copy(keepRuling = it)) },
                    subtitle = "Las líneas del cuaderno salen finas y claras; desactívalo para quitarlas",
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        InsetGroup(horizontalPadding = 16.dp) {
            item {
                NavigationRow(
                    title = "Borrado manual",
                    subtitle = if (e.eraseStrokes.isEmpty()) "Quita a mano rayas o manchas que queden"
                    else "${e.eraseStrokes.size} ${if (e.eraseStrokes.size == 1) "trazo aplicado" else "trazos aplicados"}",
                    icon = AppIcons.Eraser,
                    iconTint = MaterialTheme.ui.accent,
                    onClick = onManual,
                )
            }
        }
        if (onApplyAll != null) TextAction("Aplicar limpieza a todas las páginas", onApplyAll)
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        SegmentedControl(
            options = listOf("Reparar", "Pintar blanco"),
            selectedIndex = if (mode == EraseMode.HEAL) 0 else 1,
            onSelect = { onMode(if (it == 0) EraseMode.HEAL else EraseMode.WHITE) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Pincel", color = Color.White, fontSize = 13.sp, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
            DarkSlider(value = brushRadius, onValueChange = onBrushRadius, valueRange = 0.004f..0.06f, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val d = (8f + (brushRadius - 0.004f) / (0.06f - 0.004f) * 28f).dp
                Box(Modifier.size(d).clip(CircleShape).border(1.5.dp, Color.White, CircleShape))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(AppIcons.Undo, "Deshacer", Modifier.weight(1f), enabled = canUndo, onClick = onUndo)
            PanelButton(AppIcons.Redo, "Rehacer", Modifier.weight(1f), enabled = canRedo, onClick = onRedo)
            PanelButton(AppIcons.Trash, "Borrar todo", Modifier.weight(1f), enabled = canUndo, onClick = onClear)
            PanelButton(AppIcons.Check, "Listo", Modifier.weight(1f), onClick = onDone)
        }
    }
}

@Composable
private fun RotatePanel(rotation: Int, onLeft: () -> Unit, onRight: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Rotación: $rotation°",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(AppIcons.Rotate, "Girar a la izquierda", Modifier.weight(1f), onClick = onLeft)
            PanelButton(AppIcons.RotateRight, "Girar a la derecha", Modifier.weight(1f), onClick = onRight)
        }
    }
}
