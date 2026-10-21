package com.scannerpromax.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.ai.AiModel
import com.scannerpromax.ai.LlmException
import com.scannerpromax.ai.LlmRequest
import com.scannerpromax.ai.LlmResult
import com.scannerpromax.ai.ModelCatalog
import com.scannerpromax.ai.ModelRole
import com.scannerpromax.ai.ModelState
import com.scannerpromax.ai.RecognitionEngine
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.ui.components.SpanishLocale
import com.scannerpromax.ui.components.formatBytes
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.GroupArrangement
import com.scannerpromax.ui.components.GroupRow
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.IosTextField
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.MoreMenu
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.components.SeparatorInsetIcon
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.theme.GroupShape
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.SystemBlue
import com.scannerpromax.ui.theme.SystemGray
import com.scannerpromax.ui.theme.SystemOrange
import com.scannerpromax.ui.theme.SystemPurple
import com.scannerpromax.ui.theme.SystemTeal
import com.scannerpromax.ui.theme.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ajustes > Modelos IA: descarga de modelos, motor principal y prueba en el dispositivo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = container.models
    val settingsFlow = remember(container) { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val states by manager.states.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<AiModel?>(null) }

    val ramMb = remember { manager.totalRamMb() }
    // Cargar la biblioteca nativa toca disco: fuera del hilo principal.
    val engineStatus by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            if (container.llm.isAvailable) "" else (container.llm.unavailableReason ?: "Motor de IA no disponible en este dispositivo")
        }
    }

    LaunchedEffect(Unit) { manager.refresh() }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { runCatching { container.settings.update(transform) } }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun download(model: AiModel) {
        askNotificationPermission()
        val quantize = settings.aiQuantizeOnDevice && model.onDeviceQuantization != null
        manager.enqueueDownload(model, settings.aiWifiOnly, quantize)?.let { message = it }
    }

    val missing = ModelCatalog.all.filter { states[it.id] !is ModelState.Ready }
    AiModelsContent(
        settings = settings,
        states = states,
        ramMb = ramMb,
        engineStatus = engineStatus,
        message = message,
        onDismissMessage = { message = null },
        cloudReady = { engine -> engine.cloud?.let { container.cloudKeys.has(it) && settings.cloudConsent(it) } },
        onUpdate = { update(it) },
        onBack = onBack,
        onDownloadAll = if (missing.size > 1) ({ missing.forEach { download(it) } }) else null,
        downloadAllLabel = (if (missing.size == 2) "Descargar ambos (" else "Descargar todos (") + "${formatBytes(missing.sumOf { it.totalDownloadBytes })})",
        onDownload = { download(it) },
        onCancel = { manager.cancelDownload(it) },
        onDelete = { confirmDelete = it },
        onQuantize = { model ->
            askNotificationPermission()
            manager.enqueueDownload(model, settings.aiWifiOnly, quantize = true)?.let { message = it }
        },
        testSection = { TestSection(container, states, available = engineStatus == "") },
    )

    confirmDelete?.let { model ->
        com.scannerpromax.ui.components.ConfirmDialog(
            title = "¿Eliminar ${model.name}?",
            message = "Se borrarán sus archivos (${formatBytes((states[model.id] as? ModelState.Ready)?.diskBytes ?: model.totalDownloadBytes)}). Podrás volver a descargarlo.",
            confirmLabel = "Eliminar",
            destructive = true,
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    manager.delete(model)
                    container.settings.update { cur ->
                        if (cur.recognitionEngine.role == model.role) cur.copy(recognitionEngine = RecognitionEngine.BASIC) else cur
                    }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/**
 * Modelos IA SIN estado: cabecera grande (••• = descargar todos), avisos, grupos de motor principal, descargas y
 * asistente visual, una tarjeta por modelo y la prueba en el dispositivo ([testSection]).
 */
@Composable
internal fun AiModelsContent(
    settings: AppSettings,
    states: Map<String, ModelState>,
    ramMb: Int,
    engineStatus: String?,
    message: String?,
    onDismissMessage: () -> Unit,
    cloudReady: (RecognitionEngine) -> Boolean?,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onDownloadAll: (() -> Unit)?,
    downloadAllLabel: String,
    onDownload: (AiModel) -> Unit,
    onCancel: (AiModel) -> Unit,
    onDelete: (AiModel) -> Unit,
    onQuantize: (AiModel) -> Unit,
    testSection: @Composable () -> Unit,
) {
    val ui = MaterialTheme.ui
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(ui.groupedBackground)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
        verticalArrangement = GroupArrangement,
    ) {
        LargeTitleHeader(title = "Modelos IA", backLabel = "Ajustes", onBack = onBack) {
            if (onDownloadAll != null) {
                Box {
                    IconCircleButton(AppIcons.More, "Más opciones", { menu = true }, size = 36.dp, iconSize = 18.dp)
                    MoreMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        groups = listOf(listOf(MoreMenuItem(downloadAllLabel, AppIcons.Download, onClick = onDownloadAll))),
                        width = 290.dp,
                    )
                }
            }
        }
        Text(
            "Modelos de inteligencia artificial que funcionan dentro del teléfono, sin internet una vez " +
                "descargados. Puedes descargar uno, otro o ambos, ahora o más tarde.",
            style = MaterialTheme.typography.bodyMedium,
            color = ui.secondaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 0.dp),
        )

        engineStatus?.takeIf { it.isNotEmpty() }?.let { WarningCard(it) }
        if (ramMb in 1 until 3072) {
            WarningCard(
                "Tu teléfono tiene ${String.format(SpanishLocale, "%.1f", ramMb / 1024f)} GB de RAM. Los modelos " +
                    "pueden ir lentos o cerrarse por falta de memoria. Recomendado: solo el Asistente visual " +
                    "(más ligero) o el reconocimiento Básico.",
            )
        }
        message?.let { WarningCard(it, onDismiss = onDismissMessage) }

        // ---------------------------------------------------------------- motor principal
        InsetGroup(header = "Motor de reconocimiento principal", separatorInset = SeparatorInsetPlain) {
            RecognitionEngine.entries.forEach { engine ->
                val model = engine.role?.let { ModelCatalog.forRole(it) }
                val cloudOk = cloudReady(engine)
                val ready = cloudOk ?: (model == null || states[model.id] is ModelState.Ready)
                item {
                    EngineRow(
                        engine = engine,
                        selected = settings.recognitionEngine == engine,
                        enabled = ready,
                        hint = when {
                            ready -> engine.description
                            engine.cloud != null -> "Configura tu clave en Ajustes → IA en la nube"
                            else -> "Descarga primero el modelo"
                        },
                        onSelect = { onUpdate { it.copy(recognitionEngine = engine) } },
                    )
                }
            }
        }

        // ---------------------------------------------------------------- opciones
        InsetGroup(header = "Descargas") {
            item {
                ToggleRow(
                    title = "Solo con Wi-Fi",
                    subtitle = "No usar datos móviles para descargar modelos",
                    checked = settings.aiWifiOnly,
                    onCheckedChange = { v -> onUpdate { it.copy(aiWifiOnly = v) } },
                    icon = Icons.Rounded.Wifi,
                    iconTint = SystemBlue,
                )
            }
            if (ModelCatalog.all.any { it.onDeviceQuantization != null }) {
                item {
                    ToggleRow(
                        title = "Optimizar el Lector tras descargar",
                        subtitle = "Lo cuantiza en el teléfono (Q8_0): casi la mitad de memoria y disco, con una " +
                            "pérdida de precisión mínima. Tarda uno o dos minutos.",
                        checked = settings.aiQuantizeOnDevice,
                        onCheckedChange = { v -> onUpdate { it.copy(aiQuantizeOnDevice = v) } },
                        icon = Icons.Rounded.Tune,
                        iconTint = SystemOrange,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- asistente visual
        InsetGroup(header = "Asistente visual (si está descargado)") {
            item {
                ToggleRow(
                    title = "Mejora según el tipo de documento",
                    subtitle = "Reconoce recibos, pizarras y fotos de pantalla y les aplica la mejora adecuada " +
                        "(solo si la página sigue con el filtro por defecto).",
                    checked = settings.aiAutoEnhance,
                    onCheckedChange = { v -> onUpdate { it.copy(aiAutoEnhance = v) } },
                    icon = Icons.Rounded.AutoFixHigh,
                    iconTint = SystemPurple,
                )
            }
            item {
                ToggleRow(
                    title = "Ayuda con el encuadre",
                    subtitle = "Si no se detectan los bordes de la hoja, la IA busca dónde está el documento y " +
                        "vuelve a recortarlo en segundo plano.",
                    checked = settings.aiFrameRescue,
                    onCheckedChange = { v -> onUpdate { it.copy(aiFrameRescue = v) } },
                    icon = Icons.Rounded.CropFree,
                    iconTint = SystemTeal,
                )
            }
        }

        // ---------------------------------------------------------------- tarjetas
        ModelCatalog.all.forEach { model ->
            ModelCard(
                model = model,
                state = states[model.id] ?: ModelState.NotDownloaded,
                ramMb = ramMb,
                canQuantize = model.onDeviceQuantization != null,
                onDownload = { onDownload(model) },
                onCancel = { onCancel(model) },
                onDelete = { onDelete(model) },
                onQuantize = { onQuantize(model) },
            )
        }

        // ---------------------------------------------------------------- prueba
        testSection()
    }
}

// ------------------------------------------------------------------------------------------------ tarjeta

@Composable
private fun ModelCard(
    model: AiModel,
    state: ModelState,
    ramMb: Int,
    canQuantize: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onQuantize: () -> Unit,
) {
    val ui = MaterialTheme.ui
    InsetGroup(separatorInset = SeparatorInsetIcon) {
        item {
            GroupRow(
                title = model.name,
                subtitle = "${model.technicalName} · ${model.license}",
                icon = when (model.role) {
                    ModelRole.DOCUMENT_READER -> Icons.Rounded.DocumentScanner
                    ModelRole.PAGE_LAYOUT -> Icons.Rounded.Dashboard
                    else -> Icons.Rounded.Visibility
                },
                iconTint = ui.accent,
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("PARA QUÉ SIRVE", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                Text(model.purpose, style = MaterialTheme.typography.bodyMedium, color = ui.label)
                Spacer(Modifier.height(8.dp))
                val lowRam = ramMb in 1 until model.minRamMb
                Text(
                    "Descarga: ${formatBytes(model.totalDownloadBytes)} · RAM recomendada: ${model.recommendedRamMb / 1024} GB" +
                        (if (lowRam) " (tu teléfono tiene menos)" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lowRam) ui.red else ui.secondaryLabel,
                )
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is ModelState.NotDownloaded -> CardButton("Descargar", onDownload, Modifier.fillMaxWidth(), icon = AppIcons.Download)
                    is ModelState.Paused -> {
                        ProgressLine(state.downloaded, state.total, "En pausa")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardButton("Reanudar", onDownload, Modifier.weight(1f), icon = AppIcons.Download)
                            CardButton("Eliminar", onDelete, Modifier.weight(1f), tonal = true, destructive = true)
                        }
                    }
                    is ModelState.Queued -> {
                        ProgressLine(state.downloaded, state.total, if (state.waitingForNetwork) "En cola (esperando red / Wi-Fi)" else "En cola")
                        CardButton("Cancelar", onCancel, Modifier.fillMaxWidth(), tonal = true)
                    }
                    is ModelState.Downloading -> {
                        val speed = if (state.bytesPerSecond > 0) " · ${formatBytes(state.bytesPerSecond)}/s" else ""
                        ProgressLine(state.downloaded, state.total, "Descargando$speed")
                        CardButton("Cancelar", onCancel, Modifier.fillMaxWidth(), tonal = true)
                    }
                    is ModelState.Verifying -> {
                        Text("Verificando ${state.fileName}…", style = MaterialTheme.typography.bodySmall, color = ui.label)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape), color = ui.accent, trackColor = ui.fill)
                        CardButton("Cancelar", onCancel, Modifier.fillMaxWidth(), tonal = true)
                    }
                    is ModelState.Quantizing -> {
                        Text("Optimizando el modelo en el teléfono…", style = MaterialTheme.typography.bodySmall, color = ui.label)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape), color = ui.accent, trackColor = ui.fill)
                    }
                    is ModelState.Ready -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Check, null, tint = ui.green, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Listo · ocupa ${formatBytes(state.diskBytes)}" + if (state.quantized) " · optimizado" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ui.label,
                            )
                        }
                        state.warning?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = ui.red)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canQuantize && !state.quantized) {
                                CardButton("Optimizar", onQuantize, Modifier.weight(1f), tonal = true)
                            }
                            CardButton("Eliminar", onDelete, Modifier.weight(1f), tonal = true, destructive = true)
                        }
                    }
                    is ModelState.Failed -> {
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = ui.red)
                        if (state.downloaded > 0) ProgressLine(state.downloaded, state.total, "Error")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardButton("Reintentar", onDownload, Modifier.weight(1f), icon = AppIcons.Download)
                            CardButton("Eliminar", onDelete, Modifier.weight(1f), tonal = true, destructive = true)
                        }
                    }
                }
            }
        }
    }
}

/** Botón de tarjeta (44 dp, radio 10): acento o gris; [destructive] = texto rojo sobre gris. */
@Composable
internal fun CardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tonal: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    if (destructive) {
        val ui = MaterialTheme.ui
        Box(
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ui.fill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = ui.red, style = MaterialTheme.typography.titleSmall)
        }
    } else {
        PrimaryCapsuleButton(text, onClick, modifier, icon = icon, tonal = tonal, enabled = enabled, height = 44.dp, shape = RoundedCornerShape(10.dp))
    }
}

@Composable
private fun ProgressLine(done: Long, total: Long, label: String) {
    val ui = MaterialTheme.ui
    val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Text(
        "$label · ${formatBytes(done)} de ${formatBytes(total)} (${(frac * 100).toInt()} %)",
        style = MaterialTheme.typography.bodySmall,
        color = ui.label,
    )
    LinearProgressIndicator(
        progress = { frac },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(CircleShape),
        color = ui.accent,
        trackColor = ui.fill,
        drawStopIndicator = {},
    )
}

// ------------------------------------------------------------------------------------------------ prueba

@Composable
private fun TestSection(container: AppContainer, states: Map<String, ModelState>, available: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = MaterialTheme.ui
    val readyModels = ModelCatalog.all.filter { it.isLlm && states[it.id] is ModelState.Ready }
    var modelId by remember { mutableStateOf<String?>(null) }
    val model = readyModels.firstOrNull { it.id == modelId } ?: readyModels.firstOrNull()
    var taskPrompt by remember(model?.id) { mutableStateOf(model?.tasks?.firstOrNull()?.prompt ?: "") }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var imageLabel by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) { onDispose { running?.cancel() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            image = withContext(Dispatchers.IO) { decodeUri(context, uri, 2048) }
            imageLabel = "Imagen de la galería"
        }
    }

    InsetGroup(header = "Probar modelo") {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (!available) {
                    Text("El motor de IA no está disponible en este dispositivo.", style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel)
                    return@Column
                }
                if (model == null) {
                    Text("Descarga un modelo para probarlo aquí.", style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel)
                    return@Column
                }
                if (readyModels.size > 1) {
                    SegmentedControl(
                        options = readyModels.map { it.name },
                        selectedIndex = readyModels.indexOf(model).coerceAtLeast(0),
                        onSelect = { modelId = readyModels[it].id },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.tasks.forEach { t -> ChoicePill(t.label, taskPrompt == t.prompt) { taskPrompt = t.prompt } }
                }
                Spacer(Modifier.height(10.dp))
                Text("INSTRUCCIÓN (PROMPT)", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                Spacer(Modifier.height(4.dp))
                var promptValue by remember(model.id) { mutableStateOf(TextFieldValue(taskPrompt)) }
                if (promptValue.text != taskPrompt) promptValue = TextFieldValue(taskPrompt, TextRange(taskPrompt.length))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ui.groupedBackground)) {
                    IosTextField(
                        value = promptValue,
                        onValueChange = { promptValue = it; taskPrompt = it.text },
                        placeholder = "Instrucción",
                        singleLine = false,
                        modifier = Modifier.heightIn(max = 120.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButton("Galería", {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, Modifier.weight(1f), icon = AppIcons.Gallery, tonal = true)
                    CardButton("Última página", {
                        scope.launch {
                            val f = latestScannedPage(container)
                            if (f == null) status = "No hay páginas escaneadas" else {
                                image = withContext(Dispatchers.IO) { runCatching { BitmapIO.decodeThumbnail(f.absolutePath, 2048) }.getOrNull() }
                                imageLabel = "Última página escaneada"
                            }
                        }
                    }, Modifier.weight(1f), icon = AppIcons.Document, tonal = true)
                }
                image?.let { bmp ->
                    Spacer(Modifier.height(8.dp))
                    Text("$imageLabel · ${bmp.width}×${bmp.height}", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (running == null) {
                    CardButton(
                        text = "Probar modelo",
                        icon = Icons.Rounded.PlayArrow,
                        enabled = taskPrompt.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            output = ""
                            status = "Cargando modelo…"
                            val img = image
                            running = scope.launch {
                                try {
                                    val r = runTest(container, model, taskPrompt, img,
                                        onLoaded = { status = "Procesando la imagen…" },
                                    ) { piece ->
                                        // Llega desde el hilo de inferencia: pasar al principal.
                                        scope.launch { output += piece; status = "Generando…" }
                                    }
                                    status = statsLine(r)
                                } catch (e: LlmException) {
                                    status = "Error: ${e.message}"
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    status = "Detenido"
                                    throw e
                                } finally {
                                    running = null
                                }
                            }
                        },
                    )
                } else {
                    CardButton("Detener", { running?.cancel(); container.llm.cancel() }, Modifier.fillMaxWidth(), tonal = true, destructive = true)
                }
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                }
                if (output.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            output,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = ui.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ui.groupedBackground)
                                .padding(10.dp),
                        )
                    }
                }
            }
        }
        item {
            NavigationRow(
                title = "Liberar memoria del modelo",
                icon = Icons.Rounded.Memory,
                iconTint = SystemGray,
                onClick = { scope.launch { container.llm.unload(); status = "Memoria liberada" } },
            )
        }
    }
}

private suspend fun runTest(
    container: AppContainer,
    model: AiModel,
    prompt: String,
    image: Bitmap?,
    onLoaded: () -> Unit,
    onToken: (String) -> Unit,
): LlmResult {
    val manager = container.models
    val cfg = manager.loadConfig(model) ?: throw LlmException("El modelo no está descargado")
    val t0 = System.currentTimeMillis()
    container.llm.load(cfg)
    val loadMs = System.currentTimeMillis() - t0
    withContext(Dispatchers.Main) { onLoaded() }
    val d = model.defaults
    val task = model.tasks.firstOrNull { it.prompt == prompt }
    val r = container.llm.generate(
        LlmRequest(
            prompt = prompt,
            images = listOfNotNull(image),
            maxTokens = task?.maxTokens?.takeIf { it > 0 } ?: d.maxTokens,
            temperature = d.temperature,
            repeatPenalty = d.repeatPenalty,
            presencePenalty = task?.presencePenalty ?: 0f,
            special = d.specialTokens,
            useChatTemplate = d.useChatTemplate,
            enableThinking = d.enableThinking,
            maxImageSide = task?.maxImageSide?.takeIf { it > 0 } ?: d.maxImageSide,
            maxImagePixels = task?.maxImagePixels?.takeIf { it > 0 } ?: d.maxImagePixels,
        ),
        onToken,
    )
    lastLoadMs = loadMs
    return r
}

@Volatile private var lastLoadMs = 0L

private fun statsLine(r: LlmResult): String = String.format(
    SpanishLocale,
    "Carga %.1f s · imagen %.1f s (%d tokens) · prompt %.1f s (%.0f tok/s) · generación %d tokens en %.1f s = %.1f tok/s · total %.1f s",
    lastLoadMs / 1000.0, r.imageMs / 1000.0, r.imageTokens, r.promptMs / 1000.0, r.promptTokensPerSecond,
    r.generatedTokens, r.generationMs / 1000.0, r.tokensPerSecond, r.totalMs / 1000.0,
) + if (r.stopReason.name == "MAX_TOKENS") " · cortado por límite de tokens" else ""

private suspend fun latestScannedPage(container: AppContainer): File? = withContext(Dispatchers.IO) {
    val docs = container.documents.documents.value.sortedByDescending { it.updatedAt }
    for (doc in docs) {
        val page = doc.pages.lastOrNull() ?: continue
        val f = container.documents.processedFileIfReady(doc.id, page) ?: container.documents.originalFile(doc.id, page)
        if (f.isFile) return@withContext f
    }
    null
}

private fun decodeUri(context: android.content.Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()

// ------------------------------------------------------------------------------------------------ piezas

/** Aviso en tarjeta de grupo: icono naranja, texto y "Entendido" opcional. */
@Composable
internal fun WarningCard(text: String, onDismiss: (() -> Unit)? = null) {
    val ui = MaterialTheme.ui
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(GroupShape)
            .background(ui.card)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (onDismiss != null) 2.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(ui.orange), contentAlignment = Alignment.Center) {
                Text("!", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = ui.label, modifier = Modifier.weight(1f))
        }
        if (onDismiss != null) {
            Text(
                "Entendido",
                color = ui.accent,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}

/** Píldora de opción (tareas de prueba). */
@Composable
internal fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val ui = MaterialTheme.ui
    Text(
        label,
        color = if (selected) ui.onAccent else ui.label,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier
            .clip(PillShape)
            .background(if (selected) ui.accent else ui.fill)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Fila de motor: título + descripción y ✓ si es el elegido; desactivada si falta el modelo o la clave. */
@Composable
private fun EngineRow(engine: RecognitionEngine, selected: Boolean, enabled: Boolean, hint: String, onSelect: () -> Unit) {
    val ui = MaterialTheme.ui
    GroupRow(
        title = engine.label,
        subtitle = hint,
        enabled = enabled,
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        trailing = {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(AppIcons.Check, contentDescription = "Seleccionado", tint = ui.accent, modifier = Modifier.size(20.dp))
            }
        },
    )
}
