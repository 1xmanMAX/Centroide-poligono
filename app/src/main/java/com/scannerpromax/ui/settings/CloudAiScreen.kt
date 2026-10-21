package com.scannerpromax.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.ai.RecognitionEngine
import com.scannerpromax.ai.cloud.ClaudeHaikuProvider
import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.CloudKeyStore
import com.scannerpromax.ai.cloud.CloudPricing
import com.scannerpromax.ai.cloud.CloudProviderId
import com.scannerpromax.ai.cloud.CloudRecognizer
import com.scannerpromax.ai.cloud.CloudUsage
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.ui.components.SpanishLocale
import com.scannerpromax.ui.components.AlertAction
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.GroupArrangement
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.IconTile
import com.scannerpromax.ui.components.InfoRow
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.IosAlert
import com.scannerpromax.ui.components.IosTextField
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.MoreMenu
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.theme.SystemBlue
import com.scannerpromax.ui.theme.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Ajustes > IA en la nube (opcional): DeepSeek o Claude Haiku 5.5 con la clave de API del propio usuario, como
 * alternativa a los modelos del teléfono y al básico. Clave cifrada (Keystore), "Probar clave" (llamada gratuita),
 * aviso de privacidad con consentimiento explícito la primera vez, solo Wi-Fi y contador de uso/coste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAiScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val keys = container.cloudKeys
    val settingsFlow = remember(container) { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val usage by container.cloudUsage.usage.collectAsStateWithLifecycle()
    // Estado de la clave por proveedor (se relee tras guardar/borrar).
    var keyVersion by remember { mutableStateOf(0) }
    val saved = remember(keyVersion) { CloudProviderId.entries.associateWith { id -> keys.get(id)?.let { CloudKeyStore.masked(it) } } }
    val testResult = remember { mutableStateMapOf<CloudProviderId, String>() }
    val testing = remember { mutableStateMapOf<CloudProviderId, Boolean>() }
    var consentFor by remember { mutableStateOf<CloudProviderId?>(null) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { runCatching { container.settings.update(transform) } }
    }

    fun test(id: CloudProviderId) {
        val key = keys.get(id) ?: return
        testing[id] = true
        testResult.remove(id)
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                val p = CloudRecognizer.create(id, key)
                try {
                    p.testKey()
                    container.cloudUsage.setError(id, null)
                    "Clave válida ✓"
                } catch (e: CloudException) {
                    when (e) {
                        is CloudException.Auth -> "Clave rechazada (${e.status}). Revisa que esté bien copiada."
                        is CloudException.Billing -> "La clave es válida pero la cuenta no tiene saldo."
                        is CloudException.Network -> "Sin conexión con ${id.company}. Prueba más tarde."
                        is CloudException.RateLimited -> "Servicio saturado o límite de peticiones; prueba en un momento."
                        else -> "No se pudo comprobar: ${e.message}"
                    }
                } catch (t: Throwable) {
                    "No se pudo comprobar: ${t.javaClass.simpleName}"
                } finally {
                    (p as? ClaudeHaikuProvider)?.close()
                }
            }
            testResult[id] = msg
            testing[id] = false
        }
    }

    fun use(id: CloudProviderId) {
        if (!settings.cloudConsent(id)) { consentFor = id; return }
        update { it.copy(recognitionEngine = engineFor(id)) }
    }

    CloudAiContent(
        settings = settings,
        usage = usage,
        saved = saved,
        testing = testing,
        testResult = testResult,
        onBack = onBack,
        onWifiOnly = { v -> update { it.copy(cloudWifiOnly = v) } },
        onSave = { id, k ->
            runCatching { keys.set(id, k) }.onFailure { testResult[id] = "No se pudo guardar la clave cifrada" }
            keyVersion++
            testResult.remove(id)
            container.cloudUsage.setError(id, null)
        },
        onDelete = { id ->
            keys.remove(id)
            keyVersion++
            testResult.remove(id)
            if (settings.recognitionEngine.cloud == id) update { it.copy(recognitionEngine = RecognitionEngine.BASIC) }
        },
        onTest = { test(it) },
        onUse = { use(it) },
        onStop = { update { it.copy(recognitionEngine = RecognitionEngine.BASIC) } },
        onResetUsage = { container.cloudUsage.reset(it) },
    )

    consentFor?.let { id ->
        IosAlert(
            title = "Enviar páginas a ${id.company}",
            icon = AppIcons.Cloud,
            message = "Las páginas que se lean con este motor (la imagen completa de cada página) se enviarán a " +
                "${id.company}, con servidores en ${id.serverLocation}, y se tratarán según su política de " +
                "privacidad y sus condiciones de uso de la API. No lo uses con documentos que no quieras " +
                "compartir con ese proveedor.\n\nEl coste lo cobra ${id.company} a tu cuenta.\n\n¿Aceptas?",
            onDismiss = { consentFor = null },
            actions = listOf(
                AlertAction("Cancelar") { },
                AlertAction("Acepto", bold = true) {
                    val target = id
                    update { it.withCloudConsent(target, true).copy(recognitionEngine = engineFor(target)) }
                },
            ),
        )
    }
}

/**
 * IA en la nube SIN estado: cabecera grande, motor actual, una tarjeta agrupada por proveedor (clave, uso; acciones
 * secundarias en •••) y la opción Solo con Wi-Fi con el aviso de privacidad.
 */
@Composable
internal fun CloudAiContent(
    settings: AppSettings,
    usage: Map<CloudProviderId, CloudUsage>,
    saved: Map<CloudProviderId, String?>,
    testing: Map<CloudProviderId, Boolean>,
    testResult: Map<CloudProviderId, String>,
    onBack: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onSave: (CloudProviderId, String) -> Unit,
    onDelete: (CloudProviderId) -> Unit,
    onTest: (CloudProviderId) -> Unit,
    onUse: (CloudProviderId) -> Unit,
    onStop: () -> Unit,
    onResetUsage: (CloudProviderId) -> Unit,
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
        LargeTitleHeader(title = "IA en la nube", backLabel = "Ajustes", onBack = onBack, subtitle = "Opcional")
        Text(
            "Lee y reconstruye las páginas con un modelo de IA en internet usando TU propia clave de API. " +
                "Suele leer mejor que el teléfono el manuscrito y las tablas. Pagas directamente al proveedor " +
                "(fracciones de céntimo por página). Es una alternativa a los modelos del teléfono y al básico.",
            style = MaterialTheme.typography.bodyMedium,
            color = ui.secondaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        InsetGroup(separatorInset = SeparatorInsetPlain) {
            item { InfoRow("Motor actual", settings.recognitionEngine.label, stacked = true) }
        }

        CloudProviderId.entries.forEach { id ->
            ProviderCard(
                id = id,
                savedMasked = saved[id],
                inUse = settings.recognitionEngine.cloud == id,
                usage = usage[id] ?: CloudUsage(),
                testing = testing[id] == true,
                testResult = testResult[id],
                onSave = { onSave(id, it) },
                onDelete = { onDelete(id) },
                onTest = { onTest(id) },
                onUse = { onUse(id) },
                onStop = onStop,
                onResetUsage = { onResetUsage(id) },
            )
        }

        InsetGroup(
            header = "Opciones",
            footer = "Tus claves se guardan cifradas en este teléfono (Android Keystore), no se incluyen en las copias " +
                "de seguridad y nunca se escriben en registros. Solo se envían al proveedor elegido. Sin conexión, " +
                "las páginas esperan en la cola y se leen solas al volver la red. Si algo falla, la página se " +
                "queda con el OCR básico.",
        ) {
            item {
                ToggleRow(
                    title = "Solo con Wi-Fi",
                    subtitle = "Con datos móviles las páginas esperan en la cola hasta que haya Wi-Fi.",
                    checked = settings.cloudWifiOnly,
                    onCheckedChange = onWifiOnly,
                    icon = Icons.Rounded.Wifi,
                    iconTint = SystemBlue,
                )
            }
        }
    }
}

private fun engineFor(id: CloudProviderId): RecognitionEngine = RecognitionEngine.entries.first { it.cloud == id }

@Composable
private fun ProviderCard(
    id: CloudProviderId,
    savedMasked: String?,
    inUse: Boolean,
    usage: CloudUsage,
    testing: Boolean,
    testResult: String?,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onUse: () -> Unit,
    onStop: () -> Unit,
    onResetUsage: () -> Unit,
) {
    val ui = MaterialTheme.ui
    var draft by remember(id, savedMasked) { mutableStateOf(TextFieldValue("")) }
    var show by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    InsetGroup(separatorInset = SeparatorInsetPlain) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(AppIcons.Cloud, ui.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(id.label, style = MaterialTheme.typography.titleSmall, color = ui.label)
                    Text(
                        "Servidores en ${id.serverLocation} · ≈ ${usd(CloudPricing.typicalPageCost(id))} por página" +
                            if (id == CloudProviderId.DEEPSEEK) " (×2 en hora punta)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ui.secondaryLabel,
                    )
                }
                Box {
                    IconCircleButton(AppIcons.More, "Más opciones de ${id.company}", { menu = true }, size = 36.dp, iconSize = 18.dp, containerColor = Color.Transparent, elevation = 0.dp)
                    MoreMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        groups = listOf(
                            listOfNotNull(
                                MoreMenuItem(if (testing) "Probando…" else "Probar clave", AppIcons.Check, enabled = savedMasked != null && !testing, onClick = onTest),
                                if (inUse) MoreMenuItem("Dejar de usar", AppIcons.Close, onClick = onStop) else null,
                                MoreMenuItem("Poner a cero el uso", AppIcons.Rotate, enabled = usage.requests > 0, onClick = onResetUsage),
                            ),
                            listOf(MoreMenuItem("Borrar clave", AppIcons.Trash, destructive = true, enabled = savedMasked != null, onClick = onDelete)),
                        ),
                    )
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (savedMasked != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, null, modifier = Modifier.size(18.dp), tint = ui.secondaryLabel)
                        Spacer(Modifier.width(8.dp))
                        Text("Clave guardada: $savedMasked", style = MaterialTheme.typography.bodyMedium, color = ui.label, modifier = Modifier.weight(1f))
                        Text(
                            if (testing) "Probando…" else "Probar",
                            color = ui.accent,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !testing, role = Role.Button, onClick = onTest)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(12.dp))
                    if (inUse) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Check, null, tint = ui.green, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("En uso como motor de reconocimiento", style = MaterialTheme.typography.titleSmall, color = ui.label)
                        }
                    } else {
                        CardButton("Usar como motor", onUse, Modifier.fillMaxWidth())
                    }
                } else {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ui.groupedBackground)) {
                        IosTextField(
                            value = draft,
                            onValueChange = { draft = it.copy(text = it.text.trim()) },
                            placeholder = "Clave de API · ${id.keyHint}",
                            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            trailing = {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { show = !show },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (show) "Ocultar" else "Mostrar",
                                        tint = ui.secondaryLabel,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                    }
                    Text(
                        "Créala en ${id.keysUrl.removePrefix("https://")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ui.secondaryLabel,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    CardButton("Guardar clave", { onSave(draft.text); draft = TextFieldValue("") }, Modifier.fillMaxWidth(), enabled = draft.text.length >= 10)
                }
            }
        }
        usage.lastError?.let { err ->
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(ui.red), contentAlignment = Alignment.Center) {
                        Text("!", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(err, style = MaterialTheme.typography.bodySmall, color = ui.label)
                }
            }
        }
        // Uso acumulado
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("USO", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                Text("${usage.pages} páginas · coste estimado ${usd(usage.costUsd)}", style = MaterialTheme.typography.bodyMedium, color = ui.label)
                Text(
                    "Tokens: ${usage.inputTokens + usage.cachedInputTokens} de entrada · ${usage.outputTokens} de salida",
                    style = MaterialTheme.typography.bodySmall,
                    color = ui.secondaryLabel,
                )
                if (id == CloudProviderId.DEEPSEEK) {
                    val peak = remember { CloudPricing.isDeepSeekPeak(Instant.now()) }
                    Text(
                        if (peak) "Ahora es hora punta en DeepSeek: precio ×2 (01–04 y 06–10 UTC, de lunes a viernes)."
                        else "Ahora es horario normal en DeepSeek (punta, precio ×2: 01–04 y 06–10 UTC, de lunes a viernes).",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (peak) ui.red else ui.secondaryLabel,
                    )
                }
                Text(
                    "Estimación con los precios publicados; la factura real la da ${id.company}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ui.secondaryLabel,
                )
            }
        }
    }
}

private fun usd(v: Double): String = when {
    v == 0.0 -> "0 $"
    v < 0.01 -> String.format(SpanishLocale, "%.4f $", v)
    else -> String.format(SpanishLocale, "%.2f $", v)
}
