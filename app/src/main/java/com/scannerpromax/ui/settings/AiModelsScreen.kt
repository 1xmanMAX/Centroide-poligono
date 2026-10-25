package com.scannerpromax.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.ai.AiModel
import com.scannerpromax.ai.ModelCatalog
import com.scannerpromax.ai.ModelState
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.GroupArrangement
import com.scannerpromax.ui.components.GroupRow
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.SeparatorInsetIcon
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.components.formatBytes
import com.scannerpromax.ui.theme.GroupShape
import com.scannerpromax.ui.theme.SystemBlue
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.launch

/**
 * Ajustes › Reconocimiento: estado del lector de texto **Indra** (el único OCR; se descarga solo: aquí se ve el
 * progreso y se puede reinstalar o eliminar), la "Maquetación de páginas" opcional y "Descargas solo con Wi-Fi".
 */
@Composable
fun AiModelsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = container.models
    val settingsFlow = remember(container) { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val states by manager.states.collectAsStateWithLifecycle()
    val provisionMessage by container.indraProvisioner.message.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<AiModel?>(null) }

    LaunchedEffect(Unit) { manager.refresh() }

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
        manager.enqueueDownload(model, settings.aiWifiOnly)?.let { message = it }
    }

    /** Borra el modelo (liberando antes el motor que lo usa); [redownload] = reinstalar. */
    fun delete(model: AiModel, redownload: Boolean) {
        scope.launch {
            if (model == ModelCatalog.FAST_READER) runCatching { container.indraEngine.close() }
            manager.delete(model)
            if (model == ModelCatalog.FAST_READER) container.indraEngine.reset()
            if (redownload) download(model)
        }
    }

    AiModelsContent(
        settings = settings,
        states = states,
        message = message ?: provisionMessage,
        onDismissMessage = { message = null },
        onUpdate = { t -> scope.launch { runCatching { container.settings.update(t) } } },
        onBack = onBack,
        onDownload = { download(it) },
        onCancel = { manager.cancelDownload(it) },
        onReinstall = { delete(it, redownload = true) },
        onDelete = { confirmDelete = it },
    )

    confirmDelete?.let { model ->
        val indra = model == ModelCatalog.FAST_READER
        com.scannerpromax.ui.components.ConfirmDialog(
            title = "¿Eliminar ${model.name}?",
            message = "Se borrarán sus archivos (${formatBytes((states[model.id] as? ModelState.Ready)?.diskBytes ?: model.totalDownloadBytes)})." +
                if (indra) " Es el lector de texto de la app: se volverá a descargar solo la próxima vez que haga falta." else " Podrás volver a descargarlo.",
            confirmLabel = "Eliminar",
            destructive = true,
            onConfirm = {
                confirmDelete = null
                delete(model, redownload = false)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** Reconocimiento SIN estado: cabecera, aviso, tarjeta de Indra, tarjeta de la maquetación opcional y Wi-Fi. */
@Composable
internal fun AiModelsContent(
    settings: AppSettings,
    states: Map<String, ModelState>,
    message: String?,
    onDismissMessage: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onDownload: (AiModel) -> Unit,
    onCancel: (AiModel) -> Unit,
    onReinstall: (AiModel) -> Unit,
    onDelete: (AiModel) -> Unit,
) {
    val ui = MaterialTheme.ui
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
        LargeTitleHeader(title = "Reconocimiento", backLabel = "Ajustes", onBack = onBack)
        Text(
            "El texto de las páginas (Texto en vivo, búsqueda y PDF buscable) lo reconoce Indra dentro del teléfono, " +
                "sin internet, en segundo plano al añadir cada página. Se descarga solo la primera vez.",
            style = MaterialTheme.typography.bodyMedium,
            color = ui.secondaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        message?.let { WarningCard(it, onDismiss = onDismissMessage) }

        val fast = ModelCatalog.FAST_READER
        ModelCard(
            model = fast,
            icon = Icons.Rounded.Bolt,
            state = states[fast.id] ?: ModelState.NotDownloaded,
            required = true,
            onDownload = { onDownload(fast) },
            onCancel = { onCancel(fast) },
            onReinstall = { onReinstall(fast) },
            onDelete = { onDelete(fast) },
        )

        val layout = ModelCatalog.PAGE_LAYOUT
        Text(
            "OPCIONAL",
            style = MaterialTheme.typography.bodySmall,
            color = ui.secondaryLabel,
            modifier = Modifier.padding(horizontal = 36.dp),
        )
        ModelCard(
            model = layout,
            icon = Icons.Rounded.Dashboard,
            state = states[layout.id] ?: ModelState.NotDownloaded,
            required = false,
            onDownload = { onDownload(layout) },
            onCancel = { onCancel(layout) },
            onReinstall = { onReinstall(layout) },
            onDelete = { onDelete(layout) },
        )

        InsetGroup(
            header = "Descargas",
            footer = "Licencias: código de Indra Apache 2.0; pesos con licencia OpenRAIL-M modificada de Datalab " +
                "(uso personal). Maquetación PP-DocLayoutV3 / PP-LCNet: Apache 2.0. Motor ONNX Runtime: MIT.",
        ) {
            item {
                ToggleRow(
                    title = "Solo con Wi-Fi",
                    subtitle = "Sin Wi-Fi las descargas esperan; las páginas se reconocen solas cuando termine.",
                    checked = settings.aiWifiOnly,
                    onCheckedChange = { v -> onUpdate { it.copy(aiWifiOnly = v) } },
                    icon = Icons.Rounded.Wifi,
                    iconTint = SystemBlue,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ tarjeta

@Composable
private fun ModelCard(
    model: AiModel,
    icon: ImageVector,
    state: ModelState,
    required: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onReinstall: () -> Unit,
    onDelete: () -> Unit,
) {
    val ui = MaterialTheme.ui
    InsetGroup(separatorInset = SeparatorInsetIcon) {
        item {
            GroupRow(
                title = model.name,
                subtitle = "${model.technicalName} · ${model.license}",
                icon = icon,
                iconTint = ui.accent,
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(model.purpose, style = MaterialTheme.typography.bodyMedium, color = ui.label)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Descarga: ${formatBytes(model.totalDownloadBytes)}" + if (required) " · se descarga automáticamente" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = ui.secondaryLabel,
                )
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is ModelState.NotDownloaded -> CardButton(if (required) "Descargar ahora" else "Descargar", onDownload, Modifier.fillMaxWidth(), icon = AppIcons.Download)
                    is ModelState.Paused -> {
                        ProgressLine(state.downloaded, state.total, "En pausa")
                        CardButton("Reanudar", onDownload, Modifier.fillMaxWidth(), icon = AppIcons.Download)
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
                    }
                    is ModelState.Ready -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Check, null, tint = ui.green, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Descargado · ocupa ${formatBytes(state.diskBytes)}", style = MaterialTheme.typography.bodyMedium, color = ui.label)
                        }
                        state.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ui.red) }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardButton("Reinstalar", onReinstall, Modifier.weight(1f), tonal = true)
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
