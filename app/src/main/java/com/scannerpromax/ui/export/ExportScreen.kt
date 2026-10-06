package com.scannerpromax.ui.export

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.ImageFormat
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.pdf.PdfPageInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class ExportKind { PDF, IMAGES }
private enum class ExportAction { SAVE, SHARE }

private data class Progress(val value: Float, val message: String)

@Composable
fun ExportScreen(
    container: AppContainer,
    docId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by remember(docId) { container.documents.observe(docId) }.collectAsState(initial = null)

    var kind by rememberSaveable { mutableStateOf(ExportKind.PDF) }
    var pageSize by rememberSaveable { mutableStateOf(PageSize.AUTO) }
    var quality by rememberSaveable { mutableStateOf(ExportQuality.HIGH) }
    var imageFormat by rememberSaveable { mutableStateOf(ImageFormat.JPEG) }
    var searchable by rememberSaveable { mutableStateOf(true) }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var defaultsLoaded by rememberSaveable { mutableStateOf(false) }

    val progressFlow = remember { MutableStateFlow<Progress?>(null) }
    val progress by progressFlow.collectAsState()
    var job by remember { mutableStateOf<Job?>(null) }
    var pendingAction by remember { mutableStateOf<ExportAction?>(null) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    // PDF ya generado con las mismas opciones: compartir y guardar no lo regeneran.
    var cachedPdf by remember { mutableStateOf<Pair<String, File>?>(null) }

    LaunchedEffect(Unit) {
        if (!defaultsLoaded) {
            val s = container.settings.settings.first()
            pageSize = s.pdfPageSize
            quality = s.exportQuality
            searchable = s.searchablePdf
            defaultsLoaded = true
        }
    }
    LaunchedEffect(doc?.title) {
        val t = doc?.title ?: return@LaunchedEffect
        if (fileName.isBlank()) fileName = sanitize(t)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun pdfKey(d: Document): String =
        listOf(d.updatedAt, d.pages.joinToString { it.id + it.edits.hashCode() }, pageSize, quality, searchable, usePassword, password.hashCode(), fileName).joinToString("|")

    suspend fun buildPdf(d: Document): File {
        val key = pdfKey(d)
        cachedPdf?.let { (k, f) -> if (k == key && f.exists()) return f }
        val pages = d.pages
        val n = pages.size
        val files = pages.mapIndexed { i, p ->
            progressFlow.value = Progress(0.3f * i / n, "Preparando página ${i + 1} de $n…")
            container.documents.processedFile(docId, p)
        }
        val ocrs: List<OcrResult?> = if (searchable) {
            pages.mapIndexed { i, p ->
                progressFlow.value = Progress(0.3f + 0.35f * i / n, "Reconociendo texto ${i + 1} de $n…")
                container.documents.loadOcr(docId, p.id) ?: runOcr(container, docId, p.id, files[i])
            }
        } else List(n) { null }
        progressFlow.value = Progress(0.65f, "Creando PDF…")
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "${fileNameOr(fileName, d.title)}.pdf")
        val options = PdfOptions(
            pageSize = pageSize,
            quality = quality,
            searchable = searchable,
            password = password.takeIf { usePassword && it.isNotEmpty() },
        )
        container.pdfExporter.export(
            pages.indices.map { PdfPageInput(files[it], ocrs[it]) },
            options,
            out,
        ) { p -> progressFlow.value = Progress(0.65f + 0.35f * p.coerceIn(0f, 1f), "Creando PDF…") }
        cachedPdf = key to out
        return out
    }

    fun execute(action: ExportAction) {
        val d = doc ?: return
        if (d.pages.isEmpty()) {
            toast("El documento no tiene páginas")
            return
        }
        if (kind == ExportKind.PDF && usePassword && password.length < 4) {
            toast("La contraseña debe tener al menos 4 caracteres")
            return
        }
        job = scope.launch {
            progressFlow.value = Progress(0f, "Preparando…")
            try {
                val name = fileNameOr(fileName, d.title)
                when (kind) {
                    ExportKind.PDF -> {
                        val pdf = buildPdf(d)
                        if (action == ExportAction.SAVE) {
                            progressFlow.value = Progress(1f, "Guardando en Descargas…")
                            container.imageExporter.savePdfToDownloads(pdf, name)
                            lastSaved = "PDF guardado en Descargas/EscanerProMax"
                            toast("PDF guardado en Descargas")
                        } else {
                            context.startActivity(container.imageExporter.shareChooser(listOf(pdf), "application/pdf", "Compartir PDF"))
                        }
                    }
                    ExportKind.IMAGES -> {
                        val n = d.pages.size
                        val files = d.pages.mapIndexed { i, p ->
                            progressFlow.value = Progress(0.6f * i / n, "Preparando página ${i + 1} de $n…")
                            container.documents.processedFile(docId, p)
                        }
                        progressFlow.value = Progress(0.7f, "Codificando ${imageFormat.label} en alta definición…")
                        if (action == ExportAction.SAVE) {
                            container.imageExporter.saveToGallery(files, imageFormat, quality, name)
                            lastSaved = "${files.size} ${if (files.size == 1) "imagen guardada" else "imágenes guardadas"} en Galería (EscanerProMax)"
                            toast("Guardado en la Galería")
                        } else {
                            val out = container.imageExporter.exportToCache(files, imageFormat, quality, name)
                            context.startActivity(container.imageExporter.shareChooser(out, imageFormat.mime, "Compartir imágenes"))
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudo exportar: ${t.message ?: "error desconocido"}")
            } finally {
                progressFlow.value = null
                job = null
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val a = pendingAction
        pendingAction = null
        if (ok && a != null) execute(a) else if (!ok) toast("Se necesita permiso de almacenamiento para guardar")
    }

    fun start(action: ExportAction) {
        if (action == ExportAction.SAVE && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !context.hasWritePermission()) {
            pendingAction = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            execute(action)
        }
    }

    BackHandler { if (job != null) job?.cancel() else onBack() }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val d = doc

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (job != null) job?.cancel() else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                }
                Column(Modifier.weight(1f)) {
                    Text("Exportar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        d?.let { "${it.title} · ${it.pages.size} ${if (it.pages.size == 1) "página" else "páginas"}" } ?: "Cargando…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(bottom = 16.dp),
            ) {
                // Miniaturas de lo que se exporta
                if (d != null && d.pages.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        itemsIndexed(d.pages, key = { _, p -> p.id }) { i, p ->
                            Box(
                                Modifier
                                    .size(width = 74.dp, height = 100.dp)
                                    .shadow(4.dp, RoundedCornerShape(12.dp))
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            ) {
                                AsyncImage(
                                    model = container.documents.thumbFile(docId, p) ?: container.documents.originalFile(docId, p),
                                    contentDescription = "Página ${i + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Text(
                                    "${i + 1}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }

                SectionTitle("Formato")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormatCard(Icons.Filled.PictureAsPdf, "PDF", "Ideal para enviar e imprimir", kind == ExportKind.PDF, Modifier.weight(1f)) { kind = ExportKind.PDF }
                    FormatCard(Icons.Filled.Image, "Imágenes", "JPG, PNG o WEBP en HD", kind == ExportKind.IMAGES, Modifier.weight(1f)) { kind = ExportKind.IMAGES }
                }

                AnimatedVisibility(visible = kind == ExportKind.PDF, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        SectionTitle("Tamaño de página")
                        ChipRow {
                            PageSize.entries.forEach { s -> Pill(s.label, pageSize == s) { pageSize = s } }
                        }
                    }
                }
                AnimatedVisibility(visible = kind == ExportKind.IMAGES, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        SectionTitle("Tipo de imagen")
                        ChipRow {
                            ImageFormat.entries.forEach { f -> Pill(f.label, imageFormat == f) { imageFormat = f } }
                        }
                        Text(
                            when (imageFormat) {
                                ImageFormat.JPEG -> "JPG: compatible con todo, tamaño reducido."
                                ImageFormat.PNG -> "PNG: sin pérdida, máxima nitidez (archivos más grandes)."
                                ImageFormat.WEBP -> "WEBP: la mejor relación calidad/tamaño."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }

                SectionTitle("Calidad")
                ChipRow {
                    ExportQuality.entries.forEach { q -> Pill(q.label, quality == q) { quality = q } }
                }
                if (d != null && d.pages.isNotEmpty()) {
                    val est = estimateBytes(d, quality, if (kind == ExportKind.PDF) null else imageFormat, container.deviceTier.maxWorkingPixels)
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(primary, secondary))))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Tamaño estimado: ≈ ${formatBytes(est)} · hasta ${quality.maxLongSide} px",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnimatedVisibility(visible = kind == ExportKind.PDF, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        OptionSwitch(
                            Icons.Filled.TextFields,
                            "PDF con texto buscable (OCR)",
                            "Permite buscar y copiar el texto. Se reconoce en el teléfono.",
                            searchable,
                        ) { searchable = it }
                        OptionSwitch(
                            Icons.Filled.Lock,
                            "Proteger con contraseña",
                            "Pedirá la contraseña al abrir el PDF",
                            usePassword,
                        ) { usePassword = it }
                        AnimatedVisibility(visible = usePassword) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it.take(64) },
                                label = { Text("Contraseña") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            if (showPassword) "Ocultar" else "Mostrar",
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                            )
                        }
                    }
                }

                SectionTitle("Nombre del archivo")
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it.take(100) },
                    singleLine = true,
                    suffix = { Text(if (kind == ExportKind.PDF) ".pdf" else ".${imageFormat.ext}") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                AnimatedVisibility(visible = lastSaved != null) {
                    Row(
                        Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Text(lastSaved ?: "", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ------------------------------------------------ acciones
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val enabled = d != null && d.pages.isNotEmpty() && progress == null
                Row(
                    Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(50))
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                        .clickable(enabled = enabled) { start(ExportAction.SHARE) }
                        .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Compartir", fontWeight = FontWeight.SemiBold)
                }
                Row(
                    Modifier
                        .weight(1.3f)
                        .height(54.dp)
                        .shadow(10.dp, RoundedCornerShape(50), ambientColor = primary, spotColor = primary)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(primary, secondary)))
                        .clickable(enabled = enabled) { start(ExportAction.SAVE) }
                        .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Download, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (kind == ExportKind.PDF) "Guardar en Descargas" else "Guardar en Galería",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        // ------------------------------------------------ progreso
        val pr = progress
        AnimatedVisibility(visible = pr != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            val animated by animateFloatAsState(pr?.value ?: 1f, label = "exportProgress")
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(32.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(primary, secondary))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (kind == ExportKind.PDF) Icons.Filled.PictureAsPdf else Icons.Filled.Image, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(pr?.message ?: "", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.width(240.dp).height(6.dp).clip(RoundedCornerShape(50)),
                        color = primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${(animated * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { job?.cancel() }) { Text("Cancelar") }
                }
            }
        }
    }
}

/** OCR de una página procesada (sin OCR guardado) y lo guarda para la capa de texto del PDF. */
private suspend fun runOcr(container: AppContainer, docId: String, pageId: String, file: File): OcrResult? {
    return try {
        val maxPx = if (container.deviceTier.isLowRam) 6_000_000 else container.deviceTier.maxWorkingPixels
        val bmp = withContext(Dispatchers.Default) { BitmapIO.decode(file.absolutePath, maxPx) }
        try {
            val r = container.ocr.recognize(bmp)
            container.documents.saveOcr(docId, pageId, r)
            r
        } finally {
            bmp.recycle()
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        null // una página sin texto reconocido no impide exportar
    }
}

private fun Context.hasWritePermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun sanitize(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(100)

private fun fileNameOr(name: String, fallback: String): String =
    sanitize(name).removeSuffix(".pdf").ifBlank { sanitize(fallback) }.ifBlank { "Escaneo" }

/** Estimación aproximada del tamaño final (bytes) según calidad, formato y filtros de cada página. */
private fun estimateBytes(doc: Document, quality: ExportQuality, imageFormat: ImageFormat?, maxPixels: Int): Long {
    var total = 0.0
    for (p in doc.pages) {
        var px = p.width.toDouble() * p.height * (if (p.edits.quad != null) 0.8 else 1.0)
        px = min(px, maxPixels.toDouble())
        val aspect = if (p.height > 0) p.width.toDouble() / p.height else 0.75
        val longSide = sqrt(px * max(aspect, 1.0 / aspect))
        if (longSide > quality.maxLongSide) {
            val s = quality.maxLongSide / longSide
            px *= s * s
        }
        val binary = p.edits.filter == FilterType.BLACK_WHITE || p.edits.filter == FilterType.ECO_INK
        val bpp = when {
            imageFormat == ImageFormat.PNG -> if (binary) 0.12 else 1.1
            binary && imageFormat == null -> 0.035
            else -> when (quality) {
                ExportQuality.SMALL -> 0.07
                ExportQuality.BALANCED -> 0.12
                ExportQuality.HIGH -> 0.22
                ExportQuality.MAX -> 0.42
            } * (if (imageFormat == ImageFormat.WEBP) 0.7 else 1.0) * (if (binary) 0.6 else 1.0)
        }
        total += px * bpp
    }
    return (total + 2048 * doc.pages.size).toLong()
}

private fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024 -> String.format(Locale("es", "ES"), "%.1f MB", b / (1024.0 * 1024.0))
    b >= 1024 -> String.format(Locale("es", "ES"), "%d KB", b / 1024)
    else -> "$b B"
}

// =================================================================================================
// Componentes privados
// =================================================================================================

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val fg by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurface, label = "pillFg")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(listOf(primary, secondary)))
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
private fun FormatCard(icon: ImageVector, title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Brush.linearGradient(listOf(primary, secondary)) else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant)),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) Brush.linearGradient(listOf(primary, secondary))
                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHighest)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OptionSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}
