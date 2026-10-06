package com.scannerpromax.ui.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Deblur
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.EraseMode
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Quad
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class EditorTool(val label: String, val icon: ImageVector) {
    CROP("Recortar", Icons.Filled.Crop),
    FILTERS("Filtros", Icons.Filled.AutoAwesome),
    CLEAN("Limpiar", Icons.Filled.CleaningServices),
    ROTATE("Rotar", Icons.AutoMirrored.Filled.RotateRight),
}

@Composable
fun EditorScreen(
    container: AppContainer,
    docId: String,
    pageId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(docId, pageId) { EditorSession(container, docId, pageId) }
    DisposableEffect(session) {
        session.load()
        onDispose { session.dispose() }
    }

    var tool by rememberSaveable { mutableStateOf(EditorTool.FILTERS) }
    var erasing by remember { mutableStateOf(false) }
    var brushRadius by rememberSaveable { mutableFloatStateOf(0.018f) }
    var eraseMode by rememberSaveable { mutableStateOf(EraseMode.HEAL) }
    var showOriginal by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var quadOnEnterCrop by remember { mutableStateOf<Quad?>(null) }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val errorColor = MaterialTheme.colorScheme.error

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun selectTool(t: EditorTool) {
        if (t == tool) return
        if (tool == EditorTool.CROP) {
            // Al salir de Recortar: si cambió el recorte, los trazos de borrado ya no coinciden.
            if (session.edits.quad != quadOnEnterCrop && session.edits.eraseStrokes.isNotEmpty()) {
                session.clearStrokes()
                toast("Se reiniciaron los trazos de borrado al cambiar el recorte")
            }
            session.requestPreview()
            session.regenerateThumbs()
        }
        if (t == EditorTool.CROP) quadOnEnterCrop = session.edits.quad
        erasing = false
        tool = t
    }

    fun saveAndExit() {
        scope.launch {
            if (tool == EditorTool.CROP && session.edits.quad != quadOnEnterCrop && session.edits.eraseStrokes.isNotEmpty()) {
                session.clearStrokes()
            }
            if (session.save()) onBack() else toast("No se pudo guardar la página")
        }
    }

    fun requestExit() {
        when {
            session.saving -> Unit
            erasing -> erasing = false
            session.hasChanges -> confirmExit = true
            else -> onBack()
        }
    }
    BackHandler { requestExit() }

    LaunchedEffect(session.loadError) {
        session.loadError?.let { toast(it); onBack() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------ barra superior
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { requestExit() }) { Icon(Icons.Filled.Close, "Cerrar") }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (erasing) "Borrado manual" else "Editar página",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (erasing) "1 dedo: borrar · 2 dedos: zoom" else tool.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier
                        .height(42.dp)
                        .shadow(8.dp, RoundedCornerShape(50), ambientColor = primary, spotColor = primary)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(primary, secondary)))
                        .clickable(enabled = !session.loading && !session.saving) { saveAndExit() }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Guardar", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
            }

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
                val preview = session.preview
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
                    erasing && preview != null -> EraseTool(
                        image = preview,
                        strokes = session.edits.eraseStrokes,
                        appliedCount = session.previewEdits?.eraseStrokes?.let { applied ->
                            val cur = session.edits.eraseStrokes
                            if (applied.size <= cur.size && cur.subList(0, applied.size) == applied) applied.size else 0
                        } ?: 0,
                        brushRadius = brushRadius,
                        mode = eraseMode,
                        onStroke = { session.addStroke(it) },
                        accent = MaterialTheme.colorScheme.tertiary,
                    )
                    else -> {
                        val shown = if (showOriginal) src else (preview ?: src)
                        Image(
                            bitmap = shown,
                            contentDescription = if (showOriginal) "Original" else "Vista previa",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                        )
                    }
                }

                // Indicador de procesamiento
                androidx.compose.animation.AnimatedVisibility(
                    visible = session.previewBusy && tool != EditorTool.CROP,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
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

                // Antes / después: mantener pulsado
                if (!session.loading && tool != EditorTool.CROP && !erasing) {
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
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
                        Text(if (showOriginal) "Original" else "Mantén: antes", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // ------------------------------------------------ panel de herramienta
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                AnimatedContent(
                    targetState = if (erasing) null else tool,
                    transitionSpec = { (fadeIn(tween(200)) + slideInVertically { it / 4 }) togetherWith fadeOut(tween(120)) },
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
                            onAuto = {
                                scope.launch {
                                    if (!session.autoDetect()) toast("No se detectaron bordes: ajusta las esquinas a mano")
                                }
                            },
                            onFull = { session.setWorkingQuad(null) },
                            onReset = { session.update(session.edits.copy(quad = session.savedEdits.quad), refresh = false) },
                        )
                        EditorTool.FILTERS -> FiltersPanel(session)
                        EditorTool.CLEAN -> CleanPanel(session, onManual = {
                            if (session.preview == null) toast("Espera a que termine la vista previa") else erasing = true
                        })
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

        // Guardando
        AnimatedVisibility(visible = session.saving, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Aplicando mejoras en alta calidad…", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("¿Guardar los cambios?") },
            text = { Text("Has editado esta página. ¿Quieres guardar los cambios antes de salir?") },
            confirmButton = { TextButton(onClick = { confirmExit = false; saveAndExit() }) { Text("Guardar") } },
            dismissButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) {
                    Text("Descartar", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

// =================================================================================================
// Paneles
// =================================================================================================

@Composable
private fun ToolTab(tool: EditorTool, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val fg by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabFg")
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.background(Brush.linearGradient(listOf(primary, secondary))) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
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
            else Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun CropPanel(detecting: Boolean, onAuto: () -> Unit, onFull: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            "Arrastra las esquinas o los lados. La lupa te ayuda a ajustar con precisión.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(Icons.Filled.AutoAwesome, "Auto-detectar", Modifier.weight(1f), busy = detecting, onClick = onAuto)
            PanelButton(Icons.Filled.CropFree, "Página completa", Modifier.weight(1f), onClick = onFull)
            PanelButton(Icons.Filled.Refresh, "Restablecer", Modifier.weight(1f), onClick = onReset)
        }
    }
}

@Composable
private fun FiltersPanel(session: EditorSession) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val edits = session.edits
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(FilterType.entries, key = { it.name }) { f ->
                val selected = edits.filter == f
                val thumb = session.filterThumbs[f]
                Column(
                    Modifier.width(72.dp).clickable { session.setFilter(f) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 68.dp, height = 88.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .then(
                                if (selected) Modifier.border(2.5.dp, Brush.linearGradient(listOf(primary, secondary)), RoundedCornerShape(14.dp))
                                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                            )
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumb != null) {
                            Image(thumb, f.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(11.dp)))
                        } else {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        if (selected) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(primary, secondary))),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        f.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val adj = edits.adjustments
        Column(Modifier.padding(horizontal = 16.dp)) {
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
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
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
private fun CleanPanel(session: EditorSession, onManual: () -> Unit) {
    val e = session.edits
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        CleanSwitch(Icons.Filled.LayersClear, "Quitar rayas automáticamente", "Dobleces, marcas de bolígrafo y bordes", e.autoRemoveLines) {
            session.update(e.copy(autoRemoveLines = it))
        }
        CleanSwitch(Icons.Filled.Grain, "Quitar ruido", "Puntos, granulado y suciedad", e.autoDenoise) {
            session.update(e.copy(autoDenoise = it))
        }
        CleanSwitch(Icons.Filled.Straighten, "Enderezar texto", "Corrige unos grados de inclinación", e.autoDeskew) {
            session.update(e.copy(autoDeskew = it))
            session.regenerateThumbs()
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(primary.copy(alpha = 0.16f), secondary.copy(alpha = 0.16f))))
                .border(1.dp, Brush.horizontalGradient(listOf(primary, secondary)), RoundedCornerShape(16.dp))
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
                )
            }
        }
    }
}

@Composable
private fun CleanSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
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
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeChoice(Icons.Filled.Healing, "Reparar (inteligente)", mode == EraseMode.HEAL, Modifier.weight(1f)) { onMode(EraseMode.HEAL) }
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
                Box(Modifier.size(d).clip(CircleShape).background(Brush.linearGradient(listOf(primary, secondary))))
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
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(listOf(primary, secondary)))
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
