package com.scannerpromax.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.ai.RecognitionEngine
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfTextMode
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.CheckRow
import com.scannerpromax.ui.components.InfoRow
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.OptionsSheet
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.SpanishLocale
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.SystemBlue
import com.scannerpromax.ui.theme.SystemGray
import com.scannerpromax.ui.theme.SystemGreen
import com.scannerpromax.ui.theme.SystemIndigo
import com.scannerpromax.ui.theme.SystemOrange
import com.scannerpromax.ui.theme.SystemPink
import com.scannerpromax.ui.theme.SystemPurple
import com.scannerpromax.ui.theme.SystemTeal
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.launch

/** Qué selector (hoja inferior) está abierto. */
private enum class Picker { FILTER, PAGE_SIZE, QUALITY, PDF_TEXT }

/** Subpantallas de Ajustes (navegación interna, sin rutas nuevas). */
internal enum class SettingsPage { MAIN, EXPORT, APPEARANCE, INTEGRATION, ABOUT }

/** Datos del dispositivo para "Acerca de" (se calculan fuera para poder usar datos falsos en las capturas). */
@Immutable
internal data class AboutInfo(
    val version: String,
    val device: String,
    val performance: String,
    val processor: String,
    val workingResolution: String,
)

/**
 * Ajustes (rediseño estilo Apple, maqueta "Ajustes"): lista agrupada con iconos de color.
 *  - ESCANEO: Filtro por defecto (hoja), Captura automática, Quitar rayas sueltas, Cámara del teléfono.
 *  - INTELIGENCIA ARTIFICIAL: Reconocimiento y modelos (pantalla Modelos IA), IA en la nube.
 *  - GENERAL: Exportación (subpantalla: tamaño, calidad, texto en PDF), Apariencia (tema + color dinámico),
 *    Integración con otras apps (formato si piden cualquier archivo, guardar copia), Acerca de.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenAiModels: () -> Unit = {},
    onOpenCloudAi: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Flow recordado: SettingsRepository.settings devuelve un Flow nuevo en cada acceso y collectAsState
    // reiniciaba la recolección en cada recomposición.
    val settingsFlow = remember(container) { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val about = remember(container) { aboutInfo(container, context) }

    SettingsContent(
        settings = settings,
        about = about,
        onUpdate = { transform -> scope.launch { runCatching { container.settings.update(transform) } } },
        onBack = onBack,
        onOpenAiModels = onOpenAiModels,
        onOpenCloudAi = onOpenCloudAi,
    )
}

/** Contenido de Ajustes sin el contenedor (lo usan [SettingsScreen] y las capturas de prueba). */
@Composable
internal fun SettingsContent(
    settings: AppSettings,
    about: AboutInfo,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenAiModels: () -> Unit,
    onOpenCloudAi: () -> Unit,
    initialPage: SettingsPage = SettingsPage.MAIN,
) {
    val ui = MaterialTheme.ui
    val perf = LocalPerf.current
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val d = perf.duration(280)
            if (targetState != SettingsPage.MAIN) {
                (slideInHorizontally(tween(d)) { it } + fadeIn(tween(d))) togetherWith (slideOutHorizontally(tween(d)) { -it / 4 } + fadeOut(tween(d)))
            } else {
                (slideInHorizontally(tween(d)) { -it / 4 } + fadeIn(tween(d))) togetherWith (slideOutHorizontally(tween(d)) { it } + fadeOut(tween(d)))
            }
        },
        label = "settingsPage",
        modifier = Modifier
            .fillMaxSize()
            .background(ui.groupedBackground),
    ) { current ->
        when (current) {
            SettingsPage.MAIN -> SettingsPageScaffold(title = "Ajustes", backLabel = "Documentos", onBack = onBack) {
                InsetGroup(
                    header = "Escaneo",
                    footer = "La captura automática dispara sola con el documento estable y enfocado. Quitar rayas sueltas " +
                        "limpia dobleces, bolígrafo y bordes en las páginas nuevas. La cámara del teléfono abre la app de " +
                        "cámara del sistema (máxima resolución, HDR y nitidez del fabricante) y procesa la foto al volver.",
                ) {
                    item {
                        NavigationRow(
                            "Filtro por defecto",
                            icon = AppIcons.FilterHalf,
                            iconTint = ui.accent,
                            value = settings.defaultFilter.uiFilter.label,
                            onClick = { picker = Picker.FILTER },
                        )
                    }
                    item {
                        ToggleRow(
                            "Captura automática",
                            icon = AppIcons.Bolt,
                            iconTint = SystemOrange,
                            checked = settings.autoCapture,
                            onCheckedChange = { v -> onUpdate { it.copy(autoCapture = v) } },
                        )
                    }
                    item {
                        ToggleRow(
                            "Quitar rayas sueltas",
                            icon = AppIcons.Eraser,
                            iconTint = SystemPurple,
                            checked = settings.autoRemoveLines,
                            onCheckedChange = { v -> onUpdate { it.copy(autoRemoveLines = v) } },
                        )
                    }
                    item {
                        ToggleRow(
                            "Usar la cámara del teléfono",
                            icon = AppIcons.Camera,
                            iconTint = SystemGray,
                            checked = settings.useSystemCamera,
                            onCheckedChange = { v -> onUpdate { it.copy(useSystemCamera = v) } },
                        )
                    }
                }
                InsetGroup(header = "Inteligencia artificial") {
                    item {
                        NavigationRow(
                            "Reconocimiento",
                            icon = AppIcons.Text,
                            iconTint = SystemBlue,
                            value = engineShortLabel(settings.recognitionEngine),
                            onClick = onOpenAiModels,
                        )
                    }
                    item {
                        NavigationRow(
                            "IA en la nube",
                            icon = AppIcons.Cloud,
                            iconTint = SystemTeal,
                            value = if (settings.recognitionEngine.cloud != null) "Activa" else "Opcional",
                            onClick = onOpenCloudAi,
                        )
                    }
                }
                InsetGroup(header = "General") {
                    item {
                        NavigationRow(
                            "Exportación",
                            icon = AppIcons.Export,
                            iconTint = SystemPink,
                            value = "PDF · " + settings.pdfPageSize.label,
                            onClick = { page = SettingsPage.EXPORT },
                        )
                    }
                    item {
                        NavigationRow(
                            "Integración con otras apps",
                            icon = AppIcons.Share,
                            iconTint = SystemTeal,
                            value = settings.attachAnyFormat.name.let { if (it == "PDF") "PDF" else "JPG" },
                            onClick = { page = SettingsPage.INTEGRATION },
                        )
                    }
                    item {
                        NavigationRow(
                            "Apariencia",
                            icon = AppIcons.Moon,
                            iconTint = SystemIndigo,
                            value = themeLabel(settings.darkTheme),
                            onClick = { page = SettingsPage.APPEARANCE },
                        )
                    }
                    item {
                        NavigationRow(
                            "Acerca de",
                            icon = AppIcons.Info,
                            iconTint = SystemGray,
                            onClick = { page = SettingsPage.ABOUT },
                        )
                    }
                }
            }

            SettingsPage.EXPORT -> SettingsPageScaffold(title = "Exportación", backLabel = "Ajustes", onBack = { page = SettingsPage.MAIN }) {
                InsetGroup(
                    header = "PDF",
                    footer = "Se aplican al exportar y al compartir PDF desde Inicio. La app guarda siempre el escaneo a " +
                        "resolución completa: la calidad solo reduce el archivo exportado.",
                ) {
                    item {
                        NavigationRow(
                            "Tamaño de página",
                            icon = AppIcons.Page,
                            iconTint = SystemBlue,
                            value = settings.pdfPageSize.label,
                            onClick = { picker = Picker.PAGE_SIZE },
                        )
                    }
                    item {
                        NavigationRow(
                            "Calidad",
                            icon = AppIcons.Quality,
                            iconTint = SystemOrange,
                            value = settings.exportQuality.label,
                            onClick = { picker = Picker.QUALITY },
                        )
                    }
                    item {
                        NavigationRow(
                            "Texto en el PDF",
                            icon = AppIcons.Text,
                            iconTint = SystemGreen,
                            value = settings.pdfTextMode.label.substringBefore(" ("),
                            onClick = { picker = Picker.PDF_TEXT },
                        )
                    }
                }
            }

            SettingsPage.APPEARANCE -> SettingsPageScaffold(title = "Apariencia", backLabel = "Ajustes", onBack = { page = SettingsPage.MAIN }) {
                InsetGroup(header = "Tema", separatorInset = SeparatorInsetPlain) {
                    listOf<Pair<Boolean?, String>>(null to "Sistema", false to "Claro", true to "Oscuro").forEach { (value, label) ->
                        item {
                            CheckRow(
                                title = label,
                                selected = settings.darkTheme == value,
                                onClick = { onUpdate { it.copy(darkTheme = value) } },
                            )
                        }
                    }
                }
                val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                InsetGroup(
                    footer = if (dynamicAvailable) "Toma el color de acento de tu fondo de pantalla (Material You)."
                    else "Disponible en Android 12 o superior.",
                ) {
                    item {
                        ToggleRow(
                            "Color dinámico",
                            icon = AppIcons.Palette,
                            iconTint = SystemIndigo,
                            checked = settings.dynamicColor && dynamicAvailable,
                            enabled = dynamicAvailable,
                            onCheckedChange = { v -> onUpdate { it.copy(dynamicColor = v) } },
                        )
                    }
                }
            }

            SettingsPage.INTEGRATION -> SettingsPageScaffold(title = "Integración", backLabel = "Ajustes", onBack = { page = SettingsPage.MAIN }) {
                InsetGroup(
                    header = "Cómo se usa",
                    separatorInset = SeparatorInsetPlain,
                    footer = "En cualquier app con «Adjuntar», «Subir archivo» o «Cámara» (correo, chats, formularios, " +
                        "PixPin…), elige «ESCÁNER PRO MAX» en la lista. Se abre directamente la cámara del escáner; " +
                        "revisa las páginas y pulsa «Adjuntar a…»: el documento vuelve solo a esa app. " +
                        "Si la app pide imágenes se envían como JPG; si pide PDF, un único PDF con todas las páginas.",
                ) {
                    item { InfoRow("1. En la otra app", "Adjuntar o Cámara", icon = AppIcons.Share, iconTint = SystemTeal) }
                    item { InfoRow("2. Elige el escáner", "ESCÁNER PRO MAX", icon = AppIcons.Scan, iconTint = SystemBlue) }
                    item { InfoRow("3. Escanea y adjunta", "Vuelve sola", icon = AppIcons.Check, iconTint = SystemGreen) }
                }
                InsetGroup(
                    header = "Si la app acepta cualquier archivo",
                    separatorInset = SeparatorInsetPlain,
                    footer = "Cuando la app no indica el tipo (cualquier archivo), se envía en este formato.",
                ) {
                    com.scannerpromax.integration.AnyFormatDefault.entries.forEach { f ->
                        item {
                            CheckRow(
                                title = f.label,
                                selected = settings.attachAnyFormat == f,
                                onClick = { onUpdate { it.copy(attachAnyFormat = f) } },
                            )
                        }
                    }
                }
                InsetGroup(
                    footer = "El escaneo queda también en Documentos, aunque se haya enviado a otra app. Las apps que usan " +
                        "la acción del escáner (p. ej. PixPin) lo guardan siempre, para poder pulsar «Editar» más tarde. " +
                        "Calidad y tamaño de página: los de Exportación.",
                ) {
                    item {
                        ToggleRow(
                            "Guardar también una copia en mis documentos",
                            icon = AppIcons.Document,
                            iconTint = SystemOrange,
                            checked = settings.attachSaveCopy,
                            onCheckedChange = { v -> onUpdate { it.copy(attachSaveCopy = v) } },
                        )
                    }
                }
                InsetGroup(
                    footer = "Desactivado: al pulsar «Adjuntar» en la cámara (o tras la foto, si la app pide una sola) el " +
                        "archivo vuelve a la otra app al instante. Activado: antes se muestra la revisión para recortar, " +
                        "girar o quitar páginas. Las apps que usan la acción del escáner lo eligen ellas (extra REVIEW).",
                ) {
                    item {
                        ToggleRow(
                            "Revisar antes de adjuntar",
                            icon = AppIcons.Crop,
                            iconTint = SystemBlue,
                            checked = settings.attachReview,
                            onCheckedChange = { v -> onUpdate { it.copy(attachReview = v) } },
                        )
                    }
                }
            }

            SettingsPage.ABOUT -> SettingsPageScaffold(title = "Acerca de", backLabel = "Ajustes", onBack = { page = SettingsPage.MAIN }) {
                InsetGroup(header = "ESCÁNER PRO MAX") {
                    item { InfoRow("Versión", about.version, icon = AppIcons.Info, iconTint = SystemGray) }
                }
                InsetGroup(
                    header = "Este dispositivo",
                    footer = "Privacidad: todo se procesa en tu teléfono, sin internet (salvo la IA en la nube, solo si la activas).",
                ) {
                    item { InfoRow("Dispositivo", about.device, icon = AppIcons.Phone, iconTint = SystemBlue, stacked = true) }
                    item { InfoRow("Rendimiento", about.performance, icon = AppIcons.Speed, iconTint = SystemOrange, stacked = true) }
                    item { InfoRow("Procesador", about.processor, icon = AppIcons.Chip, iconTint = SystemGreen, stacked = true) }
                    item { InfoRow("Resolución de trabajo", about.workingResolution, icon = AppIcons.Resolution, iconTint = SystemPurple) }
                }
            }
        }
    }

    when (picker) {
        Picker.FILTER -> OptionsSheet(
            title = "Filtro por defecto",
            options = FilterType.visible,
            selected = settings.defaultFilter.uiFilter,
            label = { it.label },
            description = { it.description },
            onSelect = { v -> onUpdate { it.copy(defaultFilter = v) } },
            onDismiss = { picker = null },
        )
        Picker.PAGE_SIZE -> OptionsSheet(
            title = "Tamaño de página",
            options = PageSize.entries,
            selected = settings.pdfPageSize,
            label = { it.label },
            description = { pageSizeDescription(it) },
            onSelect = { v -> onUpdate { it.copy(pdfPageSize = v) } },
            onDismiss = { picker = null },
        )
        Picker.QUALITY -> OptionsSheet(
            title = "Calidad",
            options = ExportQuality.entries,
            selected = settings.exportQuality,
            label = { it.label },
            description = { it.resolutionLabel.replaceFirstChar { c -> c.uppercase() } + (if (it.isFullResolution) "" else " · JPEG ${it.jpegQuality} %") + qualityHint(it) },
            onSelect = { v -> onUpdate { it.copy(exportQuality = v) } },
            onDismiss = { picker = null },
        )
        Picker.PDF_TEXT -> OptionsSheet(
            title = "Texto en el PDF",
            options = PdfTextMode.entries,
            selected = settings.pdfTextMode,
            label = { it.label },
            description = { it.description },
            onSelect = { v -> onUpdate { it.copy(pdfTextMode = v) } },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

/** Página de Ajustes: "‹ atrás", título grande y grupos separados 22 dp, con desplazamiento. */
@Composable
private fun SettingsPageScaffold(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.ui.groupedBackground)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        LargeTitleHeader(title = title, backLabel = backLabel, onBack = onBack)
        content()
        Spacer(Modifier.height(0.dp))
    }
}

private fun themeLabel(dark: Boolean?): String = when (dark) {
    null -> "Sistema"
    false -> "Claro"
    true -> "Oscuro"
}

private fun engineShortLabel(e: RecognitionEngine): String = when (e) {
    RecognitionEngine.DOCUMENT_READER -> "Lector de documentos"
    RecognitionEngine.VISUAL_ASSISTANT -> "Asistente visual"
    RecognitionEngine.NUBE_DEEPSEEK -> "Nube · DeepSeek"
    RecognitionEngine.NUBE_CLAUDE_HAIKU -> "Nube · Haiku"
    RecognitionEngine.BASIC -> "Básico"
    else -> e.label
}

private fun aboutInfo(container: AppContainer, context: Context): AboutInfo {
    val tier = container.deviceTier
    val version = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (_: Throwable) {
        "1.0"
    }
    val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 0
    return AboutInfo(
        version = version,
        device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        performance = if (tier.isLowRam) "Gama baja · modo ligero activado (procesado optimizado)" else "Gama media/alta · máxima calidad",
        processor = "${tier.cores} núcleos · $memoryClass MB por app",
        workingResolution = String.format(SpanishLocale, "%.1f MP", tier.maxWorkingPixels / 1_000_000f),
    )
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
