package com.scannerpromax.ui.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.CompressionLevel
import com.scannerpromax.pdf.CompressionResult
import com.scannerpromax.ui.components.SpanishLocale
import com.scannerpromax.ui.components.formatBytes
import com.scannerpromax.ui.components.pagesLabel
import com.scannerpromax.ui.components.safeFileName
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.CheckRow
import com.scannerpromax.ui.components.EmptyMessage
import com.scannerpromax.ui.components.GroupArrangement
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.IconTile
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.MoreMenu
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.theme.GroupShape
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.ui
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Snackbar
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Datos básicos del PDF elegido. [pages] null si no se pudo contar; [locked] = protegido con contraseña. */
@Immutable
internal data class PdfInfo(val name: String, val size: Long, val pages: Int?, val locked: Boolean)

internal enum class CompressMode { LEVEL, TARGET }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CompressPdfScreen(container: AppContainer, initialUri: Uri?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var uri by rememberSaveable { mutableStateOf(initialUri) }
    var info by remember { mutableStateOf<PdfInfo?>(null) }
    var loadingInfo by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(CompressMode.LEVEL) }
    var level by rememberSaveable { mutableStateOf(CompressionLevel.RECOMMENDED) }
    var targetMb by rememberSaveable { mutableFloatStateOf(1f) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<CompressionResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Un PDF nuevo recibido desde otra app reemplaza al actual.
    LaunchedEffect(initialUri) {
        if (initialUri != null && initialUri != uri) {
            job?.cancel()
            uri = initialUri
        }
    }

    // Al cambiar de archivo: leer nombre, tamaño y nº de páginas; reiniciar el resultado.
    LaunchedEffect(uri) {
        result = null
        error = null
        info = null
        val u = uri ?: return@LaunchedEffect
        loadingInfo = true
        val loaded = withContext(Dispatchers.IO) { readPdfInfo(context, u) }
        loadingInfo = false
        info = loaded
        if (loaded == null) {
            error = "No se pudo leer el archivo seleccionado."
        } else if (loaded.size > 0) {
            // Objetivo inicial: la mitad del tamaño (redondeado), con un mínimo razonable.
            targetMb = (loaded.size / 2.0 / MB).toFloat().coerceAtLeast(0.1f).let { (it * 10).roundToInt() / 10f }.coerceAtLeast(0.1f)
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            // Permiso persistente: el Uri se restaura tras la muerte del proceso (rememberSaveable).
            runCatching {
                context.contentResolver.takePersistableUriPermission(picked, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            job?.cancel()
            uri = picked
        }
    }
    val openPicker: () -> Unit = {
        try {
            pickPdf.launch(arrayOf("application/pdf"))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("No hay un explorador de archivos disponible") }
        }
    }

    fun outputFile(): File {
        val base = safeFileName(info?.name?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "Documento")
        val dir = File(context.cacheDir, "compress_out").apply { mkdirs() }
        return File(dir, "${base}_comprimido.pdf")
    }

    fun startCompression() {
        val u = uri ?: return
        if (job != null) return
        result = null
        error = null
        job = scope.launch {
            progress = 0f
            try {
                val out = outputFile()
                val callback: (Float) -> Unit = { p -> progress = p }
                result = if (mode == CompressMode.LEVEL) {
                    container.pdfCompressor.compress(u, level, out, callback)
                } else {
                    container.pdfCompressor.compressToTarget(u, (targetMb * MB).toLong(), out, callback)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = t.message ?: "No se pudo comprimir el PDF."
            } finally {
                progress = null
                job = null
            }
        }
    }

    fun doSave() {
        val r = result ?: return
        if (saving) return
        saving = true
        scope.launch {
            try {
                container.imageExporter.savePdfToDownloads(r.output, r.output.name)
                snackbar.showSnackbar("Guardado en Descargas/EscanerProMax")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                snackbar.showSnackbar(t.message ?: "No se pudo guardar")
            } finally {
                saving = false
            }
        }
    }

    // En Android 9 o inferior hace falta permiso de almacenamiento para escribir en Descargas.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doSave() else scope.launch { snackbar.showSnackbar("Se necesita el permiso de almacenamiento para guardar") }
    }
    val save: () -> Unit = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else doSave()
    }
    val share: () -> Unit = {
        result?.let { r ->
            try {
                context.startActivity(container.imageExporter.shareChooser(listOf(r.output), "application/pdf", "Compartir PDF"))
            } catch (_: Throwable) {
                scope.launch { snackbar.showSnackbar("No hay apps para compartir") }
            }
        }
    }

    CompressContent(
        hasFile = uri != null,
        info = info,
        loadingInfo = loadingInfo,
        mode = mode,
        onModeChange = { mode = it },
        level = level,
        onLevelChange = { level = it },
        targetMb = targetMb,
        onTargetChange = { targetMb = it },
        progress = progress,
        result = result,
        error = error,
        saving = saving,
        busy = job != null,
        onBack = {
            job?.cancel()
            onBack()
        },
        onPick = openPicker,
        onCompress = { startCompression() },
        onCancel = { job?.cancel() },
        onSave = save,
        onShare = share,
        onAgain = { result = null },
        snackbar = snackbar,
    )
}

/**
 * Comprimir PDF SIN estado: cabecera grande, archivo elegido (tocar ••• o "Cambiar" para otro), segmento
 * Por nivel | Tamaño objetivo con sus opciones en grupos, y el progreso / resultado en tarjetas.
 */
@Composable
internal fun CompressContent(
    hasFile: Boolean,
    info: PdfInfo?,
    loadingInfo: Boolean,
    mode: CompressMode,
    onModeChange: (CompressMode) -> Unit,
    level: CompressionLevel,
    onLevelChange: (CompressionLevel) -> Unit,
    targetMb: Float,
    onTargetChange: (Float) -> Unit,
    progress: Float?,
    result: CompressionResult?,
    error: String?,
    saving: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onCompress: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onAgain: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val ui = MaterialTheme.ui
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(ui.groupedBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            LargeTitleHeader(
                title = "Comprimir PDF",
                backLabel = "Documentos",
                onBack = onBack,
                subtitle = "Reduce el tamaño para enviar por correo o chat",
            ) {
                if (hasFile) {
                    Box {
                        IconCircleButton(AppIcons.More, "Más opciones", { menu = true }, size = 36.dp, iconSize = 18.dp)
                        MoreMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            groups = listOf(listOf(MoreMenuItem("Elegir otro PDF", AppIcons.Document, enabled = !busy, onClick = onPick))),
                        )
                    }
                }
            }
            if (!hasFile) {
                // El estado vacío ocupa el espacio libre y el botón va debajo: nunca se solapan.
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    EmptyMessage(
                        title = "Elige un PDF",
                        message = "Comprime cualquier PDF de tu teléfono manteniendo el texto legible. También puedes compartir un PDF desde otra app hacia ESCÁNER PRO MAX.",
                        icon = AppIcons.Compress,
                    )
                }
                PrimaryCapsuleButton(
                    text = "Elegir PDF",
                    onClick = onPick,
                    icon = AppIcons.Document,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(20.dp)
                        .fillMaxWidth(),
                )
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(top = 20.dp, bottom = 28.dp),
                    verticalArrangement = GroupArrangement,
                ) {
                    FileGroup(info = info, loading = loadingInfo, onChange = onPick, enabled = !busy)
                    val working = progress != null
                    AnimatedContent(
                        targetState = when {
                            working -> 1
                            result != null -> 2
                            else -> 0
                        },
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                        label = "compressState",
                    ) { state ->
                        when (state) {
                            1 -> ProgressCard(progress ?: 0f, onCancel = onCancel)
                            2 -> result?.let { r -> ResultCard(result = r, saving = saving, onSave = onSave, onShare = onShare, onAgain = onAgain) }
                            else -> Column(verticalArrangement = GroupArrangement) {
                                SegmentedControl(
                                    options = listOf("Por nivel", "Tamaño objetivo"),
                                    selectedIndex = mode.ordinal,
                                    onSelect = { onModeChange(CompressMode.entries[it]) },
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                                if (mode == CompressMode.LEVEL) {
                                    InsetGroup(header = "Nivel de compresión", separatorInset = SeparatorInsetPlain) {
                                        CompressionLevel.entries.forEach { l ->
                                            item { LevelRow(level = l, selected = l == level, onClick = { onLevelChange(l) }, originalBytes = info?.size ?: 0L) }
                                        }
                                    }
                                } else {
                                    TargetGroup(targetMb, onTargetChange, info?.size ?: 0L)
                                }
                                AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                    ErrorCard(error.orEmpty())
                                }
                                PrimaryCapsuleButton(
                                    text = "Comprimir",
                                    onClick = onCompress,
                                    icon = AppIcons.Compress,
                                    enabled = info != null && !info.locked && !loadingInfo,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)) { data ->
            Snackbar(
                snackbarData = data,
                modifier = Modifier.padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                containerColor = if (ui.isDark) Color(0xFF2C2C2E) else Color(0xF21C1C1E),
                contentColor = Color.White,
            )
        }
    }
}

// ------------------------------------------------------------------------------------ secciones

private val PdfRed = Color(0xFFFF3B30)

@Composable
private fun FileGroup(info: PdfInfo?, loading: Boolean, onChange: () -> Unit, enabled: Boolean) {
    val ui = MaterialTheme.ui
    InsetGroup(header = "Archivo") {
        item {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (info?.locked == true) AppIcons.Lock else AppIcons.Document, PdfRed)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                loading -> "Leyendo PDF…"
                                info != null -> info.name
                                else -> "Archivo no disponible"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = ui.label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (info != null) {
                            val parts = buildList {
                                if (info.size > 0) add(formatBytes(info.size))
                                info.pages?.let { add(pagesLabel(it)) }
                                if (info.locked) add("Protegido con contraseña")
                            }
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (info.locked) ui.red else ui.secondaryLabel)
                        }
                    }
                    Text(
                        "Cambiar",
                        color = ui.accent,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = enabled, role = Role.Button, onClick = onChange)
                            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp).clip(CircleShape), color = ui.accent, trackColor = ui.fill)
                }
            }
        }
    }
}

@Composable
private fun LevelRow(level: CompressionLevel, selected: Boolean, onClick: () -> Unit, originalBytes: Long) {
    val (description, ratio) = when (level) {
        CompressionLevel.LIGHT -> "Casi sin pérdida visible. Ideal para imprimir." to 0.6
        CompressionLevel.RECOMMENDED -> "El mejor equilibrio entre calidad y tamaño." to 0.4
        CompressionLevel.STRONG -> "Archivo muy ligero, texto aún legible." to 0.25
        CompressionLevel.EXTREME -> "Blanco y negro, tamaño mínimo para enviar rápido." to 0.12
    }
    CheckRow(
        title = level.label + if (level == CompressionLevel.RECOMMENDED) " · recomendado" else "",
        subtitle = description + "\n${level.dpi} ppp · calidad ${level.jpegQuality} %" +
            if (originalBytes > 0) " · aprox. ${formatBytes((originalBytes * ratio).toLong())}" else "",
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun TargetGroup(targetMb: Float, onChange: (Float) -> Unit, originalBytes: Long) {
    val ui = MaterialTheme.ui
    val originalMb = (originalBytes / MB).toFloat()
    val maxMb = max(0.2f, originalMb * 0.95f)
    val presets = remember(originalMb) { listOf(0.5f, 1f, 2f, 5f, 10f).filter { it < originalMb } }
    InsetGroup(header = "Tamaño máximo deseado", footer = "Buscamos la mejor calidad posible que quepa en ese tamaño.") {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format(SpanishLocale, if (targetMb < 10) "%.1f" else "%.0f", targetMb),
                        style = MaterialTheme.typography.displaySmall,
                        color = ui.label,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("MB", style = MaterialTheme.typography.titleMedium, color = ui.secondaryLabel, modifier = Modifier.padding(bottom = 6.dp))
                    Spacer(Modifier.weight(1f))
                    if (originalBytes > 0) {
                        Text("Original: ${formatBytes(originalBytes)}", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                Slider(
                    value = targetMb.coerceIn(0.1f, maxMb),
                    onValueChange = { onChange(((it * 10).roundToInt() / 10f).coerceAtLeast(0.1f)) },
                    valueRange = 0.1f..maxMb,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ui.accent, inactiveTrackColor = ui.fill),
                )
                if (presets.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { p ->
                            val sel = targetMb == p
                            Text(
                                if (p < 1f) "500 KB" else "${p.toInt()} MB",
                                color = if (sel) ui.onAccent else ui.label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(PillShape)
                                    .background(if (sel) ui.accent else ui.fill)
                                    .selectable(selected = sel, role = Role.RadioButton) { onChange(p) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: Float, onCancel: () -> Unit) {
    val ui = MaterialTheme.ui
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "compressProgress")
    InsetGroup {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp, start = 20.dp, end = 20.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(animated * 100).roundToInt()} %", style = MaterialTheme.typography.displayMedium, color = ui.label)
                Text("Comprimiendo páginas…", style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = ui.accent,
                    trackColor = ui.fill,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Text(
                    "Cancelar",
                    color = ui.accent,
                    fontSize = 17.sp,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: CompressionResult,
    saving: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onAgain: () -> Unit,
) {
    val ui = MaterialTheme.ui
    val percent = remember(result) { Animatable(0f) }
    LaunchedEffect(result) {
        percent.animateTo(result.savedPercent.toFloat(), tween(1100, easing = FastOutSlowInEasing))
    }
    val ratio = if (result.originalBytes > 0) result.compressedBytes.toFloat() / result.originalBytes else 1f
    val barRatio by animateFloatAsState(ratio.coerceIn(0.02f, 1f), tween(1100, easing = FastOutSlowInEasing), label = "bar")

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InsetGroup {
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(if (result.keptOriginal) ui.orange else ui.green),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.height(10.dp))
                    if (result.keptOriginal) {
                        Text("Ya estaba optimizado", style = MaterialTheme.typography.titleLarge, color = ui.label)
                        Text(
                            "No fue posible reducirlo más sin perder calidad; se conserva el original.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ui.secondaryLabel,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text("−${percent.value.roundToInt()} %", style = MaterialTheme.typography.displayLarge, color = ui.label)
                        Text("de tamaño ahorrado", style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel)
                        if (result.rasterized) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Las páginas se convirtieron en imágenes: el texto ya no se podrá seleccionar ni buscar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ui.orange,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    SizeBar("Antes", result.originalBytes, 1f, ui.tertiaryLabel)
                    Spacer(Modifier.height(10.dp))
                    SizeBar("Después", result.compressedBytes, barRatio, ui.accent)
                    Spacer(Modifier.height(8.dp))
                    Text(pagesLabel(result.pageCount), style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                }
            }
        }
        PrimaryCapsuleButton(
            text = "Guardar en Descargas",
            onClick = onSave,
            icon = AppIcons.Download,
            loading = saving,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryCapsuleButton("Compartir", onShare, Modifier.weight(1f), icon = AppIcons.Share, tonal = true)
            PrimaryCapsuleButton("Otra vez", onAgain, Modifier.weight(1f), icon = AppIcons.Rotate, tonal = true)
        }
    }
}

@Composable
private fun SizeBar(label: String, bytes: Long, fraction: Float, color: Color) {
    val ui = MaterialTheme.ui
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.weight(1f))
            Text(formatBytes(bytes), style = MaterialTheme.typography.labelLarge, color = ui.label)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(ui.fill),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    val ui = MaterialTheme.ui
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(GroupShape)
            .background(ui.card)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(ui.red), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Info, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ui.label)
    }
}

// ------------------------------------------------------------------------------------ utilidades

private const val MB = 1024.0 * 1024.0

/** Lee nombre, tamaño y nº de páginas del PDF (en IO). Devuelve null si no se puede abrir. */
private fun readPdfInfo(context: Context, uri: Uri): PdfInfo? {
    val resolver = context.contentResolver
    var name: String? = null
    var size = -1L
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
    } catch (_: Throwable) { }
    if (name == null) name = uri.lastPathSegment?.substringAfterLast('/') ?: "documento.pdf"

    var pages: Int? = null
    var locked = false
    try {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            if (size <= 0) size = pfd.statSize
            try {
                PdfRenderer(pfd).use { pages = it.pageCount }
            } catch (_: SecurityException) {
                locked = true
            } catch (_: Throwable) {
                // Descriptor sin "seek" o PDF raro: el compresor lo copiará a caché igualmente.
            }
        } ?: return null
    } catch (_: Throwable) {
        if (size <= 0) return null
    }
    return PdfInfo(name ?: "documento.pdf", size, pages, locked)
}
