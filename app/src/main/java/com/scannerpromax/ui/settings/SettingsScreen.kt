package com.scannerpromax.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageSize
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.BrandCard
import com.scannerpromax.ui.components.GradientText
import com.scannerpromax.ui.theme.brand
import kotlinx.coroutines.launch

/** Qué selector (hoja inferior) está abierto. */
private enum class Picker { FILTER, PAGE_SIZE, QUALITY, PDF_TEXT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Flow recordado: SettingsRepository.settings devuelve un Flow nuevo en cada acceso y collectAsState
    // reiniciaba la recolección en cada recomposición.
    val settingsFlow = remember(container) { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    var picker by remember { mutableStateOf<Picker?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { runCatching { container.settings.update(transform) } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "Ajustes", onBack = onBack, scrollBehavior = scrollBehavior) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsGroup("Escaneo") {
                ValueRow(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = "Filtro por defecto",
                    value = settings.defaultFilter.label,
                    onClick = { picker = Picker.FILTER },
                )
                GroupDivider()
                SwitchRow(
                    icon = Icons.Rounded.CameraAlt,
                    title = "Captura automática",
                    subtitle = "Dispara sola cuando el documento está estable y enfocado",
                    checked = settings.autoCapture,
                    onCheckedChange = { v -> update { it.copy(autoCapture = v) } },
                )
                GroupDivider()
                SwitchRow(
                    icon = Icons.Rounded.CleaningServices,
                    title = "Quitar rayas automáticamente",
                    subtitle = "Elimina líneas de dobleces, bolígrafo y bordes en páginas nuevas",
                    checked = settings.autoRemoveLines,
                    onCheckedChange = { v -> update { it.copy(autoRemoveLines = v) } },
                )
            }

            SettingsGroup("Exportación") {
                ValueRow(
                    icon = Icons.Rounded.Description,
                    title = "Tamaño de página PDF",
                    value = settings.pdfPageSize.label,
                    onClick = { picker = Picker.PAGE_SIZE },
                )
                GroupDivider()
                ValueRow(
                    icon = Icons.Rounded.HighQuality,
                    title = "Calidad de exportación",
                    value = settings.exportQuality.label,
                    onClick = { picker = Picker.QUALITY },
                )
                GroupDivider()
                ValueRow(
                    icon = Icons.Rounded.TextFields,
                    title = "Texto en el PDF",
                    value = settings.pdfTextMode.label,
                    onClick = { picker = Picker.PDF_TEXT },
                )
            }

            SettingsGroup("Apariencia") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.Rounded.BrightnessAuto)
                        Spacer(Modifier.width(14.dp))
                        Text("Tema", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    val options = listOf<Triple<Boolean?, String, ImageVector>>(
                        Triple(null, "Sistema", Icons.Rounded.BrightnessAuto),
                        Triple(false, "Claro", Icons.Rounded.LightMode),
                        Triple(true, "Oscuro", Icons.Rounded.DarkMode),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, (value, label, icon) ->
                            SegmentedButton(
                                selected = settings.darkTheme == value,
                                onClick = { update { it.copy(darkTheme = value) } },
                                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                icon = { Icon(icon, null, Modifier.size(18.dp)) },
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                }
                GroupDivider()
                val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                SwitchRow(
                    icon = Icons.Rounded.Palette,
                    title = "Color dinámico",
                    subtitle = if (dynamicAvailable) "Usa los colores de tu fondo de pantalla (Material You)"
                    else "Disponible en Android 12 o superior",
                    checked = settings.dynamicColor && dynamicAvailable,
                    enabled = dynamicAvailable,
                    onCheckedChange = { v -> update { it.copy(dynamicColor = v) } },
                )
            }

            AboutCard(container, context)
        }
    }

    when (picker) {
        Picker.FILTER -> ChoiceSheet(
            title = "Filtro por defecto",
            options = FilterType.entries,
            selected = settings.defaultFilter,
            label = { it.label },
            description = { filterDescription(it) },
            onSelect = { v -> update { it.copy(defaultFilter = v) } },
            onDismiss = { picker = null },
        )
        Picker.PAGE_SIZE -> ChoiceSheet(
            title = "Tamaño de página PDF",
            options = PageSize.entries,
            selected = settings.pdfPageSize,
            label = { it.label },
            description = { pageSizeDescription(it) },
            onSelect = { v -> update { it.copy(pdfPageSize = v) } },
            onDismiss = { picker = null },
        )
        Picker.QUALITY -> ChoiceSheet(
            title = "Calidad de exportación",
            options = ExportQuality.entries,
            selected = settings.exportQuality,
            label = { it.label },
            description = { "Hasta ${it.maxLongSide} px · JPEG ${it.jpegQuality} %" + qualityHint(it) },
            onSelect = { v -> update { it.copy(exportQuality = v) } },
            onDismiss = { picker = null },
        )
        Picker.PDF_TEXT -> ChoiceSheet(
            title = "Texto en el PDF",
            options = com.scannerpromax.domain.PdfTextMode.entries,
            selected = settings.pdfTextMode,
            label = { it.label },
            description = { it.description },
            onSelect = { v -> update { it.copy(pdfTextMode = v) } },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

// ------------------------------------------------------------------------------- piezas de UI

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.brand.gradientStart,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        BrandCard(contentPadding = PaddingValues(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.brand.cardBorder)
}

@Composable
private fun RowIcon(icon: ImageVector) {
    val brand = MaterialTheme.brand
    Box(
        Modifier
            .size(38.dp)
            .clip(MaterialTheme.shapes.small)
            .background(brand.gradientStart.copy(alpha = if (brand.isDark) 0.18f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = brand.gradientStart, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.brand.gradientStart,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun AboutCard(container: AppContainer, context: Context) {
    val tier = container.deviceTier
    val version = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Throwable) {
            "1.0"
        }
    }
    val memoryClass = remember {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 0
    }
    Column {
        Text(
            "ACERCA DE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.brand.gradientStart,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        BrandCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.brand.gradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    GradientText("ESCÁNER PRO MAX", style = MaterialTheme.typography.titleLarge)
                    Text("Versión $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            InfoLine(Icons.Rounded.PhoneAndroid, "Dispositivo", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            InfoLine(
                Icons.Rounded.Speed,
                "Rendimiento",
                if (tier.isLowRam) "Gama baja · modo ligero activado (procesado optimizado)" else "Gama media/alta · máxima calidad",
            )
            InfoLine(Icons.Rounded.Memory, "Procesador", "${tier.cores} núcleos · ${memoryClass} MB por app")
            InfoLine(
                Icons.Rounded.Tune,
                "Resolución de trabajo",
                String.format(com.scannerpromax.ui.components.SpanishLocale, "%.1f MP", tier.maxWorkingPixels / 1_000_000f),
            )
            InfoLine(Icons.Rounded.Info, "Privacidad", "Todo se procesa en tu teléfono, sin internet")
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Hoja inferior de selección única con descripción por opción. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.navigationBarsPadding()) {
            items(options, key = { it.toString() }) { option ->
                val isSelected = option == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            onSelect(option)
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.brand.gradientStart),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label(option), style = MaterialTheme.typography.titleSmall)
                        description(option)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun filterDescription(f: FilterType): String = when (f) {
    FilterType.AUTO -> "Detecta el tipo de documento y aplica la mejor mejora (recomendado)"
    FilterType.ORIGINAL -> "Sin cambios de color, solo recorte"
    FilterType.MAGIC -> "Fondo blanco, sin sombras y tinta nítida"
    FilterType.MAGIC_PRO -> "Máxima mejora: des-ruido fuerte y más detalle para cámaras modestas"
    FilterType.NO_SHADOW -> "Elimina sombras conservando los colores"
    FilterType.GRAYSCALE -> "Escala de grises limpia"
    FilterType.BLACK_WHITE -> "Blanco y negro de alto contraste para texto"
    FilterType.ECO_INK -> "B/N fino con fondo puro: ahorra tinta al imprimir"
    FilterType.LIGHTEN -> "Aclara fotos oscuras o con poca luz"
    FilterType.VIVID -> "Colores intensos para fotos y documentos a color"
    FilterType.WHITEBOARD -> "Pizarras: fondo blanco y trazos saturados sin reflejos"
}

private fun pageSizeDescription(p: PageSize): String = when (p) {
    PageSize.AUTO -> "Cada página toma el tamaño de su imagen"
    PageSize.A4 -> "210 × 297 mm"
    PageSize.LETTER -> "216 × 279 mm"
    PageSize.LEGAL -> "216 × 356 mm"
}

private fun qualityHint(q: ExportQuality): String = when (q) {
    ExportQuality.SMALL -> " · ideal para enviar por chat"
    ExportQuality.BALANCED -> " · buena calidad y tamaño moderado"
    ExportQuality.HIGH -> " · recomendada"
    ExportQuality.MAX -> " · para imprimir o archivar"
}
