package com.scannerpromax.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.jev.JevEnhancer
import com.scannerpromax.ai.jev.JevSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.IconTile
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.IosTextField
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.SpanishLocale
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes › IA en la nube › "Jev (TypeSafe)": clave (Keystore), "Probar clave" y el interruptor "Mejorar con Jev"
 * (fotos, tablas, OCR dudoso) con el coste estimado. Apagado e inactivo sin clave. Las páginas se procesan igual si no
 * hay red o Jev falla.
 */
@Composable
fun JevSettingsGroup(container: AppContainer) {
    val jev = container.jev
    val scope = rememberCoroutineScope()
    val s by jev.settings.collectAsStateWithLifecycle()
    var keyVersion by remember { mutableStateOf(0) }
    val masked = remember(keyVersion) { jev.maskedKey() }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    JevCard(
        settings = s,
        savedMasked = masked,
        testing = testing,
        testResult = testResult,
        onSave = { k -> runCatching { jev.setKey(k) }.onFailure { testResult = "No se pudo guardar la clave cifrada" }; keyVersion++ },
        onDelete = { jev.removeKey(); keyVersion++; testResult = null },
        onTest = {
            testing = true; testResult = null
            scope.launch {
                testResult = withContext(Dispatchers.IO) {
                    try {
                        jev.testKey(); "Clave válida ✓"
                    } catch (e: CloudException) {
                        when (e) {
                            is CloudException.Auth -> "Clave rechazada (${e.status}). Revisa que esté bien copiada."
                            is CloudException.Network -> "Sin conexión con TypeSafe. Prueba más tarde."
                            is CloudException.RateLimited -> "Servicio saturado o límite de peticiones; prueba en un momento."
                            else -> "No se pudo comprobar: ${e.message}"
                        }
                    } catch (t: Throwable) {
                        "No se pudo comprobar: ${t.javaClass.simpleName}"
                    }
                }
                testing = false
            }
        },
        onChange = { f -> jev.update(f) },
    )
}

@Composable
internal fun JevCard(
    settings: JevSettings,
    savedMasked: String?,
    testing: Boolean,
    testResult: String?,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onChange: ((JevSettings) -> JevSettings) -> Unit,
) {
    val ui = MaterialTheme.ui
    var draft by remember(savedMasked) { mutableStateOf(TextFieldValue("")) }
    var show by remember { mutableStateOf(false) }
    val hasKey = savedMasked != null
    InsetGroup(
        separatorInset = SeparatorInsetPlain,
        footer = "Jev localiza en una versión ligera de cada página (≈ 1568 px, sin el original) las fotos, para darles " +
            "el filtro de foto sin trocearlas, y las tablas, para reconstruir sus líneas donde la luz las quemó. Se " +
            "pregunta una vez por página y se guarda. Sin red o si falla, la página sale como siempre. La verificación " +
            "del texto dudoso envía recortes de palabras; en nuestras pruebas apenas cambia el resultado.",
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(AppIcons.Cloud, ui.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Jev (TypeSafe)", style = MaterialTheme.typography.titleSmall, color = ui.label)
                    Text(
                        "Servidores en EE. UU. · ≈ ${usdJev(JevEnhancer.typicalPageCost(settings.ocr))} por página",
                        style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel,
                    )
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (hasKey) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, null, modifier = Modifier.size(18.dp), tint = ui.secondaryLabel)
                        Spacer(Modifier.width(8.dp))
                        Text("Clave guardada: $savedMasked", style = MaterialTheme.typography.bodyMedium, color = ui.label, modifier = Modifier.weight(1f))
                        Text(
                            if (testing) "Probando…" else "Probar", color = ui.accent, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !testing, role = Role.Button, onClick = onTest)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        Text(
                            "Borrar", color = ui.red, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onDelete)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.padding(top = 4.dp)) }
                } else {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ui.groupedBackground)) {
                        IosTextField(
                            value = draft,
                            onValueChange = { draft = it.copy(text = it.text.trim()) },
                            placeholder = "Clave de API de TypeSafe",
                            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            trailing = {
                                Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { show = !show }, contentAlignment = Alignment.Center) {
                                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Ocultar" else "Mostrar",
                                        tint = ui.secondaryLabel, modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                    Text("Créala en console.typesafe.ai", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    CardButton("Guardar clave", { onSave(draft.text); draft = TextFieldValue("") }, Modifier.fillMaxWidth(), enabled = draft.text.length >= 10)
                }
            }
        }
        item {
            ToggleRow(
                title = "Mejorar con Jev",
                subtitle = if (hasKey) "Fotos y tablas de las páginas nuevas o reprocesadas." else "Guarda antes una clave.",
                checked = settings.enabled && hasKey,
                enabled = hasKey,
                onCheckedChange = { v -> onChange { it.copy(enabled = v) } },
            )
        }
        if (settings.enabled && hasKey) {
            item { ToggleRow(title = "Fotos", subtitle = "Rectángulos precisos para el filtro de foto (Color natural).", checked = settings.photos, onCheckedChange = { v -> onChange { it.copy(photos = v) } }) }
            item { ToggleRow(title = "Tablas", subtitle = "Líneas reconstruidas dentro de la tabla, también donde la luz las borró.", checked = settings.tables, onCheckedChange = { v -> onChange { it.copy(tables = v) } }) }
            item { ToggleRow(title = "Texto dudoso (OCR)", subtitle = "Verifica con el recorte las palabras que Indra leyó con poca confianza.", checked = settings.ocr, onCheckedChange = { v -> onChange { it.copy(ocr = v) } }) }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("USO", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                Text("${settings.pages} páginas · coste estimado ${usdJev(settings.costUsd)}", style = MaterialTheme.typography.bodyMedium, color = ui.label)
                Text("Tokens de entrada: ${settings.inputTokens} (la salida es gratis) · ${settings.requests} peticiones", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                settings.lastError?.let { Text("Último error: $it", style = MaterialTheme.typography.bodySmall, color = ui.red) }
            }
        }
    }
}

private fun usdJev(v: Double): String = when {
    v == 0.0 -> "0 $"
    v < 0.01 -> String.format(SpanishLocale, "%.5f $", v)
    else -> String.format(SpanishLocale, "%.2f $", v)
}
