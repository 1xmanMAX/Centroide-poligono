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
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.BrandCard
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.SoftButton
import com.scannerpromax.ui.components.SpanishLocale
import com.scannerpromax.ui.components.formatBytes
import com.scannerpromax.ui.theme.brand
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "Modelos IA", onBack = onBack, scrollBehavior = scrollBehavior) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Modelos de inteligencia artificial que funcionan dentro del teléfono, sin internet una vez " +
                    "descargados. Puedes descargar uno, otro o ambos, ahora o más tarde.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            engineStatus?.takeIf { it.isNotEmpty() }?.let { WarningCard(it) }
            if (ramMb in 1 until 3072) {
                WarningCard(
                    "Tu teléfono tiene ${String.format(SpanishLocale, "%.1f", ramMb / 1024f)} GB de RAM. Los modelos " +
                        "pueden ir lentos o cerrarse por falta de memoria. Recomendado: solo el Asistente visual " +
                        "(más ligero) o el reconocimiento Básico.",
                )
            }
            message?.let { WarningCard(it, onDismiss = { message = null }) }

            // ---------------------------------------------------------------- motor principal
            SectionTitle("Motor de reconocimiento principal")
            BrandCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                RecognitionEngine.entries.forEach { engine ->
                    val model = engine.role?.let { ModelCatalog.forRole(it) }
                    val cloudOk = engine.cloud?.let { container.cloudKeys.has(it) && settings.cloudConsent(it) }
                    val ready = cloudOk ?: (model == null || states[model.id] is ModelState.Ready)
                    EngineRow(
                        engine = engine,
                        selected = settings.recognitionEngine == engine,
                        enabled = ready,
                        hint = when {
                            ready -> engine.description
                            engine.cloud != null -> "Configura tu clave en Ajustes → IA en la nube"
                            else -> "Descarga primero el modelo"
                        },
                        onSelect = { update { it.copy(recognitionEngine = engine) } },
                    )
                }
            }

            // ---------------------------------------------------------------- opciones
            SectionTitle("Descargas")
            BrandCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(
                    icon = Icons.Rounded.Wifi,
                    title = "Solo con Wi-Fi",
                    subtitle = "No usar datos móviles para descargar modelos",
                    checked = settings.aiWifiOnly,
                    onChange = { v -> update { it.copy(aiWifiOnly = v) } },
                )
                if (ModelCatalog.all.any { it.onDeviceQuantization != null }) {
                    ToggleRow(
                        icon = Icons.Rounded.Tune,
                        title = "Optimizar el Lector tras descargar",
                        subtitle = "Lo cuantiza en el teléfono (Q8_0): casi la mitad de memoria y disco, con una " +
                            "pérdida de precisión mínima. Tarda uno o dos minutos.",
                        checked = settings.aiQuantizeOnDevice,
                        onChange = { v -> update { it.copy(aiQuantizeOnDevice = v) } },
                    )
                }
            }

            // ---------------------------------------------------------------- asistente visual
            SectionTitle("Asistente visual (si está descargado)")
            BrandCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = "Mejora según el tipo de documento",
                    subtitle = "Reconoce recibos, pizarras y fotos de pantalla y les aplica la mejora adecuada " +
                        "(solo si la página sigue con el filtro por defecto).",
                    checked = settings.aiAutoEnhance,
                    onChange = { v -> update { it.copy(aiAutoEnhance = v) } },
                )
                ToggleRow(
                    icon = Icons.Rounded.CropFree,
                    title = "Ayuda con el encuadre",
                    subtitle = "Si no se detectan los bordes de la hoja, la IA busca dónde está el documento y " +
                        "vuelve a recortarlo en segundo plano.",
                    checked = settings.aiFrameRescue,
                    onChange = { v -> update { it.copy(aiFrameRescue = v) } },
                )
            }

            val missing = ModelCatalog.all.filter { states[it.id] !is ModelState.Ready }
            if (missing.size > 1) {
                GradientButton(
                    text = "Descargar ambos (${formatBytes(missing.sumOf { it.totalDownloadBytes })})",
                    onClick = { missing.forEach { download(it) } },
                    icon = Icons.Rounded.CloudDownload,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------------------------------------------------------------- tarjetas
            ModelCatalog.all.forEach { model ->
                ModelCard(
                    model = model,
                    state = states[model.id] ?: ModelState.NotDownloaded,
                    ramMb = ramMb,
                    canQuantize = model.onDeviceQuantization != null,
                    onDownload = { download(model) },
                    onCancel = { manager.cancelDownload(model) },
                    onDelete = { confirmDelete = model },
                    onQuantize = {
                        askNotificationPermission()
                        manager.enqueueDownload(model, settings.aiWifiOnly, quantize = true)?.let { message = it }
                    },
                )
            }

            // ---------------------------------------------------------------- prueba
            TestSection(container, states, available = engineStatus == "")
        }
    }

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
    val brand = MaterialTheme.brand
    BrandCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (model.role == ModelRole.DOCUMENT_READER) Icons.Rounded.DocumentScanner else Icons.Rounded.Visibility)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${model.technicalName} · ${model.license}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Para qué sirve", style = MaterialTheme.typography.labelMedium, color = brand.gradientStart)
        Text(model.purpose, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        val lowRam = ramMb in 1 until model.minRamMb
        Text(
            "Descarga: ${formatBytes(model.totalDownloadBytes)} · RAM recomendada: ${model.recommendedRamMb / 1024} GB" +
                (if (lowRam) " (tu teléfono tiene menos)" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = if (lowRam) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        when (state) {
            is ModelState.NotDownloaded -> GradientButton("Descargar", onDownload, Modifier.fillMaxWidth(), icon = Icons.Rounded.CloudDownload, height = 48.dp)
            is ModelState.Paused -> {
                ProgressLine(state.downloaded, state.total, "En pausa")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton("Reanudar", onDownload, Modifier.weight(1f), icon = Icons.Rounded.CloudDownload, height = 48.dp)
                    SoftButton("Eliminar", onDelete, Modifier.weight(1f), icon = Icons.Rounded.Delete, height = 48.dp)
                }
            }
            is ModelState.Queued -> {
                ProgressLine(state.downloaded, state.total, if (state.waitingForNetwork) "En cola (esperando red / Wi-Fi)" else "En cola")
                SoftButton("Cancelar", onCancel, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, height = 48.dp)
            }
            is ModelState.Downloading -> {
                val speed = if (state.bytesPerSecond > 0) " · ${formatBytes(state.bytesPerSecond)}/s" else ""
                ProgressLine(state.downloaded, state.total, "Descargando$speed")
                SoftButton("Cancelar", onCancel, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, height = 48.dp)
            }
            is ModelState.Verifying -> {
                Text("Verificando ${state.fileName}…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                SoftButton("Cancelar", onCancel, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, height = 48.dp)
            }
            is ModelState.Quantizing -> {
                Text("Optimizando el modelo en el teléfono…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            is ModelState.Ready -> {
                Text(
                    "Listo · ocupa ${formatBytes(state.diskBytes)}" + if (state.quantized) " · optimizado" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = brand.accent,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canQuantize && !state.quantized) {
                        SoftButton("Optimizar", onQuantize, Modifier.weight(1f), icon = Icons.Rounded.Tune, height = 48.dp)
                    }
                    SoftButton("Eliminar", onDelete, Modifier.weight(1f), icon = Icons.Rounded.Delete, height = 48.dp)
                }
            }
            is ModelState.Failed -> {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.downloaded > 0) ProgressLine(state.downloaded, state.total, "Error")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton("Reintentar", onDownload, Modifier.weight(1f), icon = Icons.Rounded.CloudDownload, height = 48.dp)
                    SoftButton("Eliminar", onDelete, Modifier.weight(1f), icon = Icons.Rounded.Delete, height = 48.dp)
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(done: Long, total: Long, label: String) {
    val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Text(
        "$label · ${formatBytes(done)} de ${formatBytes(total)} (${(frac * 100).toInt()} %)",
        style = MaterialTheme.typography.bodySmall,
    )
    LinearProgressIndicator(
        progress = { frac },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.brand.gradientStart,
    )
}

// ------------------------------------------------------------------------------------------------ prueba

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestSection(container: AppContainer, states: Map<String, ModelState>, available: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val readyModels = ModelCatalog.all.filter { states[it.id] is ModelState.Ready }
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

    SectionTitle("Probar modelo")
    BrandCard {
        if (!available) {
            Text("El motor de IA no está disponible en este dispositivo.", style = MaterialTheme.typography.bodyMedium)
            return@BrandCard
        }
        if (model == null) {
            Text("Descarga un modelo para probarlo aquí.", style = MaterialTheme.typography.bodyMedium)
            return@BrandCard
        }
        if (readyModels.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                readyModels.forEach { m ->
                    FilterChip(selected = m.id == model.id, onClick = { modelId = m.id }, label = { Text(m.name) })
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.tasks.forEach { t ->
                FilterChip(selected = taskPrompt == t.prompt, onClick = { taskPrompt = t.prompt }, label = { Text(t.label) })
            }
        }
        OutlinedTextField(
            value = taskPrompt,
            onValueChange = { taskPrompt = it },
            label = { Text("Instrucción (prompt)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Galería")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val f = latestScannedPage(container)
                    if (f == null) status = "No hay páginas escaneadas" else {
                        image = withContext(Dispatchers.IO) { runCatching { BitmapIO.decodeThumbnail(f.absolutePath, 2048) }.getOrNull() }
                        imageLabel = "Última página escaneada"
                    }
                }
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Última página")
            }
        }
        image?.let { bmp ->
            Spacer(Modifier.height(8.dp))
            Text("$imageLabel · ${bmp.width}×${bmp.height}", style = MaterialTheme.typography.bodySmall)
            Image(
                bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (running == null) {
            GradientButton(
                text = "Probar modelo",
                icon = Icons.Rounded.PlayArrow,
                enabled = taskPrompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                height = 48.dp,
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
            SoftButton("Detener", { running?.cancel(); container.llm.cancel() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, height = 48.dp)
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.accent)
        }
        if (output.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { scope.launch { container.llm.unload(); status = "Memoria liberada" } }) {
            Icon(Icons.Rounded.Memory, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Liberar memoria del modelo")
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.brand.gradientStart,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}

@Composable
private fun IconBadge(icon: ImageVector) {
    val brand = MaterialTheme.brand
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(42.dp)
            .clip(MaterialTheme.shapes.small)
            .background(brand.gradientStart.copy(alpha = if (brand.isDark) 0.18f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = brand.gradientStart, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun WarningCard(text: String, onDismiss: (() -> Unit)? = null) {
    BrandCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        if (onDismiss != null) {
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Entendido") }
        }
    }
}

@Composable
private fun EngineRow(engine: RecognitionEngine, selected: Boolean, enabled: Boolean, hint: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.brand.gradientStart),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                engine.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
