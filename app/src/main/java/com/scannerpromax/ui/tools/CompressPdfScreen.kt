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
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.BrandCard
import com.scannerpromax.ui.components.EmptyState
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.SoftButton
import com.scannerpromax.ui.components.SpanishLocale
import com.scannerpromax.ui.components.formatBytes
import com.scannerpromax.ui.components.pagesLabel
import com.scannerpromax.ui.components.safeFileName
import com.scannerpromax.ui.theme.brand
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
private data class PdfInfo(val name: String, val size: Long, val pages: Int?, val locked: Boolean)

private enum class CompressMode { LEVEL, TARGET }

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "Comprimir PDF",
                subtitle = "Reduce el tamaño para enviar por correo o chat",
                onBack = {
                    job?.cancel()
                    onBack()
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        val current = info
        if (uri == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "Elige un PDF",
                    message = "Comprime cualquier PDF de tu teléfono manteniendo el texto legible. También puedes compartir un PDF desde otra app hacia ESCÁNER PRO MAX.",
                    actionLabel = null,
                )
                GradientButton(
                    text = "Elegir PDF",
                    onClick = openPicker,
                    icon = Icons.Rounded.FileOpen,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(20.dp)
                        .fillMaxWidth(),
                )
            }
        } else Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FileCard(info = current, loading = loadingInfo, onChange = openPicker, enabled = job == null)

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
                    1 -> ProgressCard(progress ?: 0f, onCancel = { job?.cancel() })
                    2 -> result?.let { r ->
                        ResultCard(
                            result = r,
                            saving = saving,
                            onSave = save,
                            onShare = share,
                            onAgain = { result = null },
                        )
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OptionsSection(
                            mode = mode,
                            onModeChange = { mode = it },
                            level = level,
                            onLevelChange = { level = it },
                            targetMb = targetMb,
                            onTargetChange = { targetMb = it },
                            originalBytes = current?.size ?: 0L,
                        )
                        AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            ErrorCard(error.orEmpty())
                        }
                        GradientButton(
                            text = "Comprimir",
                            onClick = { startCompression() },
                            icon = Icons.Rounded.Compress,
                            enabled = current != null && !current.locked && !loadingInfo,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ secciones

@Composable
private fun FileCard(info: PdfInfo?, loading: Boolean, onChange: () -> Unit, enabled: Boolean) {
    val brand = MaterialTheme.brand
    BrandCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color(0xFFFF4D67).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (info?.locked == true) Icons.Rounded.Lock else Icons.Rounded.PictureAsPdf,
                    null,
                    tint = Color(0xFFFF4D67),
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        loading -> "Leyendo PDF…"
                        info != null -> info.name
                        else -> "Archivo no disponible"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info != null) {
                    val parts = buildList {
                        if (info.size > 0) add(formatBytes(info.size))
                        info.pages?.let { add(pagesLabel(it)) }
                        if (info.locked) add("Protegido con contraseña")
                    }
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (info.locked) brand.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onChange, enabled = enabled) {
                Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cambiar")
            }
        }
        if (loading) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = brand.gradientStart)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSection(
    mode: CompressMode,
    onModeChange: (CompressMode) -> Unit,
    level: CompressionLevel,
    onLevelChange: (CompressionLevel) -> Unit,
    targetMb: Float,
    onTargetChange: (Float) -> Unit,
    originalBytes: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == CompressMode.LEVEL,
                onClick = { onModeChange(CompressMode.LEVEL) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("Por nivel") },
            )
            SegmentedButton(
                selected = mode == CompressMode.TARGET,
                onClick = { onModeChange(CompressMode.TARGET) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text("Tamaño objetivo") },
            )
        }
        if (mode == CompressMode.LEVEL) {
            CompressionLevel.entries.forEach { l ->
                LevelCard(level = l, selected = l == level, onClick = { onLevelChange(l) }, originalBytes = originalBytes)
            }
        } else {
            TargetCard(targetMb, onTargetChange, originalBytes)
        }
    }
}

@Composable
private fun LevelCard(level: CompressionLevel, selected: Boolean, onClick: () -> Unit, originalBytes: Long) {
    val brand = MaterialTheme.brand
    val (description, ratio) = when (level) {
        CompressionLevel.LIGHT -> "Casi sin pérdida visible. Ideal para imprimir." to 0.6
        CompressionLevel.RECOMMENDED -> "El mejor equilibrio entre calidad y tamaño." to 0.4
        CompressionLevel.STRONG -> "Archivo muy ligero, texto aún legible." to 0.25
        CompressionLevel.EXTREME -> "Blanco y negro, tamaño mínimo para enviar rápido." to 0.12
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) brand.gradientStart.copy(alpha = if (brand.isDark) 0.16f else 0.08f) else brand.card,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) brand.gradientStart else brand.cardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = brand.gradientStart))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(level.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    if (level == CompressionLevel.RECOMMENDED) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "RECOMENDADO",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(brand.horizontalGradient)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${level.dpi} ppp · calidad ${level.jpegQuality} %" +
                        if (originalBytes > 0) " · aprox. ${formatBytes((originalBytes * ratio).toLong())}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = brand.accent,
                )
            }
        }
    }
}

@Composable
private fun TargetCard(targetMb: Float, onChange: (Float) -> Unit, originalBytes: Long) {
    val brand = MaterialTheme.brand
    val originalMb = (originalBytes / MB).toFloat()
    val maxMb = max(0.2f, originalMb * 0.95f)
    val presets = remember(originalMb) { listOf(0.5f, 1f, 2f, 5f, 10f).filter { it < originalMb } }
    BrandCard {
        Text("Tamaño máximo deseado", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(SpanishLocale, if (targetMb < 10) "%.1f" else "%.0f", targetMb),
                style = MaterialTheme.typography.displaySmall,
                color = brand.gradientStart,
            )
            Spacer(Modifier.width(6.dp))
            Text("MB", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            if (originalBytes > 0) {
                Text(
                    "Original: ${formatBytes(originalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        Slider(
            value = targetMb.coerceIn(0.1f, maxMb),
            onValueChange = { onChange(((it * 10).roundToInt() / 10f).coerceAtLeast(0.1f)) },
            valueRange = 0.1f..maxMb,
            colors = SliderDefaults.colors(thumbColor = brand.gradientStart, activeTrackColor = brand.gradientStart),
        )
        if (presets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    FilterChip(
                        selected = targetMb == p,
                        onClick = { onChange(p) },
                        label = { Text(if (p < 1f) "500 KB" else "${p.toInt()} MB") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = brand.gradientStart.copy(alpha = 0.2f),
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Buscamos la mejor calidad posible que quepa en ese tamaño.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressCard(progress: Float, onCancel: () -> Unit) {
    val brand = MaterialTheme.brand
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "compressProgress")
    BrandCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Text("${(animated * 100).roundToInt()} %", style = MaterialTheme.typography.displayMedium, color = brand.gradientStart)
            Spacer(Modifier.height(4.dp))
            Text("Comprimiendo páginas…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = brand.gradientEnd,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text("Cancelar") }
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
    val brand = MaterialTheme.brand
    val percent = remember(result) { Animatable(0f) }
    LaunchedEffect(result) {
        percent.animateTo(result.savedPercent.toFloat(), tween(1100, easing = FastOutSlowInEasing))
    }
    val ratio = if (result.originalBytes > 0) result.compressedBytes.toFloat() / result.originalBytes else 1f
    val barRatio by animateFloatAsState(ratio.coerceIn(0.02f, 1f), tween(1100, easing = FastOutSlowInEasing), label = "bar")

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BrandCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    null,
                    tint = if (result.keptOriginal) brand.warning else brand.success,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (result.keptOriginal) {
                    Text("Ya estaba optimizado", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "No fue posible reducirlo más sin perder calidad; se conserva el original.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "−${percent.value.roundToInt()} %",
                        style = MaterialTheme.typography.displayLarge.merge(
                            androidx.compose.ui.text.TextStyle(brush = brand.horizontalGradient),
                        ),
                    )
                    Text("de tamaño ahorrado", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(20.dp))
                SizeBar("Antes", result.originalBytes, 1f, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(10.dp))
                SizeBar("Después", result.compressedBytes, barRatio, brand.gradientEnd)
                Spacer(Modifier.height(8.dp))
                Text(
                    pagesLabel(result.pageCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GradientButton(
            text = "Guardar en Descargas",
            onClick = onSave,
            icon = Icons.Rounded.Download,
            loading = saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftButton("Compartir", onShare, Modifier.weight(1f), icon = Icons.Rounded.Share)
            SoftButton("Otra vez", onAgain, Modifier.weight(1f), icon = Icons.Rounded.Refresh)
        }
    }
}

@Composable
private fun SizeBar(label: String, bytes: Long, fraction: Float, color: Color) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(formatBytes(bytes), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
    val brand = MaterialTheme.brand
    Surface(
        shape = MaterialTheme.shapes.large,
        color = brand.danger.copy(alpha = 0.12f),
        contentColor = brand.danger,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
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
