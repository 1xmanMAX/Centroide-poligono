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
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.BrandCard
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.SpanishLocale
import com.scannerpromax.ui.theme.brand
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "IA en la nube", subtitle = "Opcional", onBack = onBack, scrollBehavior = scrollBehavior) },
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
                "Lee y reconstruye las páginas con un modelo de IA en internet usando TU propia clave de API. " +
                    "Suele leer mejor que el teléfono el manuscrito y las tablas. Pagas directamente al proveedor " +
                    "(fracciones de céntimo por página). Es una alternativa a los modelos del teléfono y al básico.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrandCard {
                Text("Motor actual", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(settings.recognitionEngine.label, style = MaterialTheme.typography.titleSmall)
            }

            CloudProviderId.entries.forEach { id ->
                ProviderCard(
                    id = id,
                    savedMasked = saved[id],
                    inUse = settings.recognitionEngine.cloud == id,
                    usage = usage[id] ?: CloudUsage(),
                    testing = testing[id] == true,
                    testResult = testResult[id],
                    onSave = { k ->
                        runCatching { keys.set(id, k) }.onFailure { testResult[id] = "No se pudo guardar la clave cifrada" }
                        keyVersion++
                        testResult.remove(id)
                        container.cloudUsage.setError(id, null)
                    },
                    onDelete = {
                        keys.remove(id)
                        keyVersion++
                        testResult.remove(id)
                        if (settings.recognitionEngine.cloud == id) update { it.copy(recognitionEngine = RecognitionEngine.BASIC) }
                    },
                    onTest = { test(id) },
                    onUse = { use(id) },
                    onStop = { update { it.copy(recognitionEngine = RecognitionEngine.BASIC) } },
                    onResetUsage = { container.cloudUsage.reset(id) },
                )
            }

            CloudSection("Opciones")
            BrandCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                CloudToggle(
                    icon = Icons.Rounded.Wifi,
                    title = "Solo con Wi-Fi",
                    subtitle = "Con datos móviles las páginas esperan en la cola hasta que haya Wi-Fi.",
                    checked = settings.cloudWifiOnly,
                    onChange = { v -> update { it.copy(cloudWifiOnly = v) } },
                )
            }
            BrandCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.brand.gradientStart, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Tus claves se guardan cifradas en este teléfono (Android Keystore), no se incluyen en las copias " +
                            "de seguridad y nunca se escriben en registros. Solo se envían al proveedor elegido. Sin conexión, " +
                            "las páginas esperan en la cola y se leen solas al volver la red. Si algo falla, la página se " +
                            "queda con el OCR básico.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    consentFor?.let { id ->
        AlertDialog(
            onDismissRequest = { consentFor = null },
            icon = { Icon(Icons.Rounded.Cloud, null) },
            title = { Text("Enviar páginas a ${id.company}") },
            text = {
                Text(
                    "Las páginas que se lean con este motor (la imagen completa de cada página) se enviarán a " +
                        "${id.company}, con servidores en ${id.serverLocation}, y se tratarán según su política de " +
                        "privacidad y sus condiciones de uso de la API. No lo uses con documentos que no quieras " +
                        "compartir con ese proveedor.\n\nEl coste lo cobra ${id.company} a tu cuenta.\n\n¿Aceptas?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = id
                    consentFor = null
                    update { it.withCloudConsent(target, true).copy(recognitionEngine = engineFor(target)) }
                }) { Text("Acepto") }
            },
            dismissButton = { TextButton(onClick = { consentFor = null }) { Text("Cancelar") } },
        )
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
    var draft by remember(id, savedMasked) { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    BrandCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CloudBadge(Icons.Rounded.Cloud)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(id.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Servidores en ${id.serverLocation} · ≈ ${usd(CloudPricing.typicalPageCost(id))} por página" +
                        if (id == CloudProviderId.DEEPSEEK) " (×2 en hora punta)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (savedMasked != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Clave guardada: $savedMasked", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Borrar clave") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = !testing) { Text(if (testing) "Probando…" else "Probar clave") }
                if (inUse) OutlinedButton(onClick = onStop) { Text("Dejar de usar") }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(8.dp))
            if (inUse) {
                Text("✓ En uso como motor de reconocimiento", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.brand.gradientStart)
            } else {
                GradientButton(text = "Usar como motor de reconocimiento", onClick = onUse, modifier = Modifier.fillMaxWidth())
            }
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.trim() },
                label = { Text("Clave de API de ${id.company}") },
                placeholder = { Text(id.keyHint) },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (show) "Ocultar" else "Mostrar")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Créala en ${id.keysUrl.removePrefix("https://")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            GradientButton(text = "Guardar clave", onClick = { onSave(draft); draft = "" }, enabled = draft.length >= 10, modifier = Modifier.fillMaxWidth())
        }

        usage.lastError?.let {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Uso acumulado
        Spacer(Modifier.height(12.dp))
        Text("USO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${usage.pages} páginas · coste estimado ${usd(usage.costUsd)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Tokens: ${usage.inputTokens + usage.cachedInputTokens} de entrada · ${usage.outputTokens} de salida",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (id == CloudProviderId.DEEPSEEK) {
            val peak = remember { CloudPricing.isDeepSeekPeak(Instant.now()) }
            Text(
                if (peak) "Ahora es hora punta en DeepSeek: precio ×2 (01–04 y 06–10 UTC, de lunes a viernes)."
                else "Ahora es horario normal en DeepSeek (punta, precio ×2: 01–04 y 06–10 UTC, de lunes a viernes).",
                style = MaterialTheme.typography.bodySmall,
                color = if (peak) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Estimación con los precios publicados; la factura real la da ${id.company}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (usage.requests > 0) TextButton(onClick = onResetUsage) { Text("Poner a cero") }
    }
}

private fun usd(v: Double): String = when {
    v == 0.0 -> "0 $"
    v < 0.01 -> String.format(SpanishLocale, "%.4f $", v)
    else -> String.format(SpanishLocale, "%.2f $", v)
}

@Composable
private fun CloudSection(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.brand.gradientStart,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}

@Composable
private fun CloudBadge(icon: ImageVector) {
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
private fun CloudToggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudBadge(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
