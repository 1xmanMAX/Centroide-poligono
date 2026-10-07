package com.scannerpromax.ui.export

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.ImageFormat
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.domain.PdfTextMode
import com.scannerpromax.domain.TextPlacement
import com.scannerpromax.export.TextExporter
import com.scannerpromax.export.TextFormat
import com.scannerpromax.pdf.PdfPageInput
import com.scannerpromax.pdf.binaryHintFor
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.LoadingOverlay
import com.scannerpromax.ui.components.SoftButton
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class ExportKind { PDF, IMAGES, TXT, DOCX }
private enum class ExportAction { SAVE, SHARE }

private data class Progress(val value: Float, val message: String)

/** PDF generado y cuántas páginas quedaron sin texto reconocido (el OCR falló en ellas). */
private class PdfBuild(val file: File, val missingText: Int)

private fun missingTextMessage(n: Int) =
    if (n == 1) "1 página sin texto reconocido (se exporta como imagen)"
    else "$n páginas sin texto reconocido (se exportan como imagen)"

/** Exportación con PDF de texto opcional ([PdfTextMode]) y la opción "Exportar en PDF con el texto reconocido". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    var textMode by rememberSaveable { mutableStateOf(PdfTextMode.BUSCABLE) }
    var textPlacement by rememberSaveable { mutableStateOf(TextPlacement.AFTER_EACH_PAGE) }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var defaultsLoaded by rememberSaveable { mutableStateOf(false) }
    var missingOcr by remember { mutableIntStateOf(0) }

    val progressFlow = remember { MutableStateFlow<Progress?>(null) }
    val progress by progressFlow.collectAsState()
    var job by remember { mutableStateOf<Job?>(null) }
    var pendingAction by remember { mutableStateOf<ExportAction?>(null) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    // Lo último guardado (Uris de MediaStore) para ofrecer "Abrir" y "Compartir" en la tarjeta de éxito.
    var savedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var savedMime by remember { mutableStateOf("application/pdf") }
    var seenDoc by remember { mutableStateOf(false) }
    // PDF ya generado con las mismas opciones: compartir y guardar no lo regeneran.
    var cachedPdf by remember { mutableStateOf<Pair<String, File>?>(null) }
    // Páginas cuyo OCR falló en la última exportación (el PDF no es buscable en ellas) y acción para reintentar.
    var ocrWarning by remember { mutableStateOf<Pair<Int, ExportAction>?>(null) }
    val view = LocalView.current
    // Exportar con OCR en gama baja puede tardar minutos: la pantalla no se apaga mientras haya un trabajo en curso
    // (si se apagara, el usuario creería que se colgó o saldría y se cancelaría la exportación).
    val working = job != null
    DisposableEffect(working) {
        if (working) view.keepScreenOn = true
        onDispose { if (working) view.keepScreenOn = false }
    }
    val textExporter = remember { TextExporter(context.applicationContext) }

    LaunchedEffect(Unit) {
        if (!defaultsLoaded) {
            val s = container.settings.settings.first()
            pageSize = s.pdfPageSize
            quality = s.exportQuality
            textMode = s.pdfTextMode
            defaultsLoaded = true
        }
    }
    // Documento borrado o fusionado mientras se exporta / al entrar: volver en vez de "Cargando…" eterno.
    LaunchedEffect(docId) {
        if (container.documents.get(docId) == null) {
            Toast.makeText(context, "El documento ya no existe", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
    LaunchedEffect(doc) {
        if (doc != null) seenDoc = true
        else if (seenDoc) {
            job?.cancel()
            Toast.makeText(context, "El documento ya no existe", Toast.LENGTH_SHORT).show()
            onBack()
        }
        // Páginas cuyo texto aún hay que reconocer (se hace solo al exportar). Disco en IO, fuera del hilo principal.
        if (doc != null) missingOcr = container.documents.pagesMissingOcr(docId)
    }
    LaunchedEffect(doc?.title) {
        val t = doc?.title ?: return@LaunchedEffect
        if (fileName.isBlank()) fileName = sanitize(t)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun pdfKey(d: Document): String =
        // Sin updatedAt: guardar el OCR que faltaba actualiza el documento y obligaba a regenerar el PDF
        // al pulsar "Compartir" después de "Guardar". Las ediciones de cada página y el texto corregido sí invalidan.
        listOf(
            d.pages.joinToString { it.id + it.edits.hashCode() + "/" + it.ocrEditedText.hashCode() },
            pageSize, quality, textMode, textPlacement, usePassword, password.hashCode(), fileName,
        ).joinToString("|")

    /** OCR de todas las páginas (lo que falte se reconoce de una en una, con progreso y cancelable). */
    suspend fun recognizeAll(d: Document, from: Float, to: Float): List<OcrResult?> {
        val n = d.pages.size
        return container.documents.ensureOcrAll(docId, d.pages.map { it.id }) { done, total, recognizing ->
            val msg = if (recognizing) "Reconociendo texto ${done + 1}/$total…" else "Texto listo $done/$total"
            progressFlow.value = Progress(from + (to - from) * done / n.coerceAtLeast(1), msg)
        }.also { missingOcr = 0 }
    }

    /** null = no hay nada que exportar (solo texto y no se reconoció texto en ninguna página; ya se avisó). */
    suspend fun buildPdf(d: Document): PdfBuild? {
        val key = pdfKey(d)
        cachedPdf?.let { (k, f) -> if (k == key && f.exists()) return PdfBuild(f, 0) }
        val pages = d.pages
        val n = pages.size
        val mode = textMode
        val files = pages.mapIndexed { i, p ->
            progressFlow.value = Progress(0.25f * i / n, "Preparando página ${i + 1}/$n…")
            container.documents.processedFile(docId, p)
        }
        val ocrs: List<OcrResult?> = if (mode.needsOcr) recognizeAll(d, 0.25f, 0.65f) else List(n) { null }
        // ensureOcr devuelve null si el reconocimiento falló (sin memoria, error de ML Kit...): esas páginas no
        // llevan capa de texto. Se avisa y el PDF no se reutiliza para que "Reintentar"/"Compartir" lo rehaga.
        val missing = if (mode.needsOcr) ocrs.count { it == null } else 0
        if (mode == PdfTextMode.SOLO_TEXTO && ocrs.all { it == null || it.displayText.isBlank() }) {
            toast("No se encontró texto en el documento")
            return null
        }
        progressFlow.value = Progress(0.65f, "Generando PDF…")
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "${fileNameOr(fileName, d.title)}.pdf")
        val options = PdfOptions(
            pageSize = pageSize,
            quality = quality,
            searchable = mode == PdfTextMode.BUSCABLE || mode == PdfTextMode.BUSCABLE_CON_TEXTO,
            password = password.takeIf { usePassword && it.isNotEmpty() },
            textMode = mode,
            textPlacement = textPlacement,
        )
        container.pdfExporter.export(
            pages.indices.map { PdfPageInput(files[it], ocrs[it], binaryHint = binaryHintFor(pages[it].edits.filter)) },
            options,
            out,
        ) { p -> progressFlow.value = Progress(0.65f + 0.35f * p.coerceIn(0f, 1f), "Generando PDF…") }
        cachedPdf = if (missing == 0) key to out else null
        return PdfBuild(out, missing)
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
        ocrWarning = null
        job = scope.launch {
            progressFlow.value = Progress(0f, "Preparando…")
            try {
                val name = fileNameOr(fileName, d.title)
                when (kind) {
                    ExportKind.PDF -> {
                        val built = buildPdf(d) ?: return@launch
                        val pdf = built.file
                        if (built.missingText > 0) ocrWarning = built.missingText to action
                        if (action == ExportAction.SAVE) {
                            progressFlow.value = Progress(1f, "Guardando en Descargas…")
                            val uri = container.imageExporter.savePdfToDownloads(pdf, name)
                            savedUris = listOf(uri)
                            savedMime = "application/pdf"
                            lastSaved = "PDF guardado en Descargas/EscanerProMax"
                            toast(if (built.missingText > 0) "PDF guardado. ${missingTextMessage(built.missingText)}" else "PDF guardado en Descargas")
                        } else {
                            if (built.missingText > 0) toast(missingTextMessage(built.missingText))
                            context.startActivity(container.imageExporter.shareChooser(listOf(pdf), "application/pdf", "Compartir PDF"))
                        }
                    }
                    ExportKind.IMAGES -> {
                        val n = d.pages.size
                        val files = d.pages.mapIndexed { i, p ->
                            progressFlow.value = Progress(0.6f * i / n, "Preparando página ${i + 1}/$n…")
                            container.documents.processedFile(docId, p)
                        }
                        progressFlow.value = Progress(0.7f, "Codificando ${imageFormat.label} en alta definición…")
                        if (action == ExportAction.SAVE) {
                            savedUris = container.imageExporter.saveToGallery(files, imageFormat, quality, name)
                            savedMime = imageFormat.mime
                            lastSaved = "${files.size} ${if (files.size == 1) "imagen guardada" else "imágenes guardadas"} en Galería (EscanerProMax)"
                            toast("Guardado en la Galería")
                        } else {
                            val out = container.imageExporter.exportToCache(files, imageFormat, quality, name)
                            context.startActivity(container.imageExporter.shareChooser(out, imageFormat.mime, "Compartir imágenes"))
                        }
                    }
                    ExportKind.TXT, ExportKind.DOCX -> {
                        val format = if (kind == ExportKind.TXT) TextFormat.TXT else TextFormat.DOCX
                        val ocrs = recognizeAll(d, 0f, 0.85f)
                        if (ocrs.all { it == null || it.displayText.isBlank() }) {
                            toast("No se encontró texto en el documento")
                            return@launch
                        }
                        ocrs.count { it == null }.takeIf { it > 0 }?.let { ocrWarning = it to action }
                        progressFlow.value = Progress(0.9f, if (format == TextFormat.DOCX) "Generando documento Word…" else "Generando texto…")
                        val file = textExporter.export(d.title, ocrs, format, name)
                        if (action == ExportAction.SAVE) {
                            progressFlow.value = Progress(1f, "Guardando en Descargas…")
                            val uri = container.imageExporter.saveToDownloads(file, name, format.mime, format.ext)
                            savedUris = listOf(uri)
                            savedMime = format.mime
                            lastSaved = "${format.label} guardado en Descargas/EscanerProMax"
                            toast("Guardado en Descargas")
                        } else {
                            context.startActivity(container.imageExporter.shareChooser(listOf(file), format.mime, "Compartir texto"))
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

    fun openSaved() {
        val uri = savedUris.firstOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, savedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Abrir con"))
        } catch (_: Throwable) {
            toast("No hay ninguna app para abrir este archivo")
        }
    }

    fun shareSaved() {
        val uris = savedUris
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }.apply {
            type = savedMime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Compartir"))
        } catch (_: Throwable) {
            toast("No hay apps para compartir")
        }
    }

    val d = doc
    val isPdf = kind == ExportKind.PDF
    val isText = kind == ExportKind.TXT || kind == ExportKind.DOCX
    val showQuality = kind == ExportKind.IMAGES || (isPdf && textMode.hasImages)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "Exportar",
                subtitle = d?.let { "${it.title} · ${it.pages.size} ${if (it.pages.size == 1) "página" else "páginas"}" } ?: "Cargando…",
                onBack = { if (job != null) job?.cancel() else onBack() },
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // imePadding antes del scroll: reduce el área visible para que el campo enfocado quede a la vista.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                // Miniaturas de lo que se exporta
                if (d != null && d.pages.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        itemsIndexed(d.pages, key = { _, p -> p.id }, contentType = { _, _ -> "thumb" }) { i, p ->
                            val perf = LocalPerf.current
                            // Archivo resuelto en IO (nada de File.exists() en el hilo principal en cada recomposición).
                            // Falso positivo de lint: el valor sí se asigna (tras withContext).
                            @SuppressLint("ProduceStateDoesNotAssignValue")
                            val model by produceState<File?>(initialValue = null, p.id, p.thumbFile, p.processedFile) {
                                val f = withContext(Dispatchers.IO) {
                                    container.documents.thumbFile(docId, p)
                                        ?: p.processedFile?.let { File(container.documents.docDir(docId), it) }?.takeIf { it.exists() }
                                        ?: container.documents.originalFile(docId, p)
                                }
                                value = f
                            }
                            // Decodificación acotada al tamaño de celda aunque el modelo sea el original de 8-12 MP.
                            val request = remember(model, perf.thumbPx) {
                                model?.let {
                                    ImageRequest.Builder(context).data(it).size(perf.thumbPx).precision(Precision.INEXACT).build()
                                }
                            }
                            Box(
                                Modifier
                                    .size(width = 74.dp, height = 100.dp)
                                    .shadow(if (perf.lowEnd) 0.dp else 4.dp, RoundedCornerShape(12.dp))
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            ) {
                                if (request != null) {
                                    AsyncImage(
                                        model = request,
                                        contentDescription = "Página ${i + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
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
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormatCard(Icons.Filled.PictureAsPdf, "PDF", "Imagen, texto buscable o ambos", isPdf, Modifier.weight(1f)) { kind = ExportKind.PDF }
                        FormatCard(Icons.Filled.Image, "Imágenes", "JPG, PNG o WEBP en HD", kind == ExportKind.IMAGES, Modifier.weight(1f)) { kind = ExportKind.IMAGES }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormatCard(Icons.AutoMirrored.Filled.TextSnippet, "Texto (.txt)", "Texto reconocido de todas las páginas", kind == ExportKind.TXT, Modifier.weight(1f)) { kind = ExportKind.TXT }
                        FormatCard(Icons.Filled.Description, "Word (.docx)", "Editable, un título por página", kind == ExportKind.DOCX, Modifier.weight(1f)) { kind = ExportKind.DOCX }
                    }
                }

                // ------------------------------------------------ PDF: texto
                AnimatedVisibility(visible = isPdf, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        SectionTitle("Texto en el PDF")
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PdfTextMode.entries.forEach { m ->
                                TextModeCard(
                                    icon = textModeIcon(m),
                                    title = m.label,
                                    description = textModeDescription(m),
                                    selected = textMode == m,
                                ) { textMode = m }
                            }
                        }
                        AnimatedVisibility(visible = textMode == PdfTextMode.BUSCABLE_CON_TEXTO) {
                            Column {
                                Text(
                                    "Páginas de texto reconocido",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
                                )
                                ChipRow {
                                    TextPlacement.entries.forEach { t -> Pill(t.label, textPlacement == t) { textPlacement = t } }
                                }
                            }
                        }
                        if (textMode.needsOcr && missingOcr > 0) {
                            InfoLine(
                                Icons.Filled.AutoAwesome,
                                if (missingOcr == 1) "Se reconocerá el texto de 1 página automáticamente al exportar."
                                else "Se reconocerá el texto de $missingOcr páginas automáticamente al exportar.",
                            )
                        }
                        AnimatedVisibility(visible = textMode.hasImages) {
                            Column {
                                SectionTitle("Tamaño de página")
                                ChipRow {
                                    PageSize.entries.forEach { s -> Pill(s.label, pageSize == s) { pageSize = s } }
                                }
                            }
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
                AnimatedVisibility(visible = isText, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        InfoLine(
                            Icons.Filled.TextFields,
                            "Incluye el texto reconocido de todas las páginas (con tus correcciones). " +
                                if (missingOcr > 0) "Las $missingOcr páginas sin reconocer se procesarán ahora." else "Todo el texto está listo.",
                        )
                    }
                }

                if (showQuality) {
                    SectionTitle("Calidad")
                    ChipRow {
                        ExportQuality.entries.forEach { q -> Pill(q.label, quality == q) { quality = q } }
                    }
                    // Qué hace cada calidad: la app guarda el escaneo completo y SOLO aquí se reduce.
                    Text(
                        quality.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
                    )
                }
                if (d != null && d.pages.isNotEmpty()) {
                    val est = remember(d, quality, kind, imageFormat, textMode, textPlacement) {
                        when (kind) {
                            ExportKind.PDF -> estimatePdfBytes(d, quality, textMode, container.deviceTier.maxWorkingPixels)
                            ExportKind.IMAGES -> estimateBytes(d, quality, imageFormat, container.deviceTier.maxWorkingPixels)
                            ExportKind.TXT -> estimateTextChars(d) + 64L
                            ExportKind.DOCX -> estimateTextChars(d) / 3 + 3_000L
                        }
                    }
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.brand.gradient))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Tamaño estimado: ≈ ${formatBytes(est)}" + if (showQuality) " · ${quality.resolutionLabel}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnimatedVisibility(visible = isPdf, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
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
                    suffix = {
                        Text(
                            when (kind) {
                                ExportKind.PDF -> ".pdf"
                                ExportKind.IMAGES -> ".${imageFormat.ext}"
                                ExportKind.TXT -> ".txt"
                                ExportKind.DOCX -> ".docx"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                AnimatedVisibility(visible = lastSaved != null) {
                    Column(
                        Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.brand.success.copy(alpha = 0.12f))
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.brand.success)
                            Spacer(Modifier.width(10.dp))
                            Text(lastSaved ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (savedUris.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SoftButton("Abrir", onClick = { openSaved() }, icon = Icons.Filled.Visibility, height = 44.dp, modifier = Modifier.weight(1f))
                                SoftButton("Compartir", onClick = { shareSaved() }, icon = Icons.Filled.Share, height = 44.dp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                ocrWarning?.let { (count, action) ->
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FindInPage, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(10.dp))
                            Text(missingTextMessage(count), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        SoftButton(
                            "Reintentar",
                            onClick = { start(action) },
                            icon = Icons.Filled.AutoAwesome,
                            enabled = progress == null,
                            height = 44.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                SoftButton(
                    text = "Compartir",
                    onClick = { start(ExportAction.SHARE) },
                    icon = Icons.Filled.Share,
                    enabled = enabled,
                    height = 54.dp,
                    modifier = Modifier.weight(1f),
                )
                // Texto corto (no cabe "Guardar en Descargas" a 360 dp o con letra grande); el destino
                // se indica en la tarjeta de éxito.
                GradientButton(
                    text = when (kind) {
                        ExportKind.PDF -> "Guardar PDF"
                        ExportKind.TXT -> "Guardar .txt"
                        ExportKind.DOCX -> "Guardar .docx"
                        ExportKind.IMAGES -> "Guardar"
                    },
                    onClick = { start(ExportAction.SAVE) },
                    icon = Icons.Filled.Download,
                    enabled = enabled,
                    height = 54.dp,
                    modifier = Modifier.weight(1.3f),
                )
            }
        }

        // ------------------------------------------------ progreso
        val pr = progress
        LoadingOverlay(
            visible = pr != null,
            message = pr?.message ?: "",
            progress = pr?.value,
            onCancel = { job?.cancel() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun textModeIcon(m: PdfTextMode): ImageVector = when (m) {
    PdfTextMode.SOLO_IMAGEN -> Icons.Filled.Image
    PdfTextMode.BUSCABLE -> Icons.Filled.Search
    PdfTextMode.BUSCABLE_CON_TEXTO -> Icons.Filled.FindInPage
    PdfTextMode.SOLO_TEXTO -> Icons.Filled.TextFields
}

private fun textModeDescription(m: PdfTextMode): String = when (m) {
    PdfTextMode.SOLO_IMAGEN -> "Las páginas tal como se ven, sin texto."
    PdfTextMode.BUSCABLE -> "Se ve igual, pero puedes buscar, seleccionar y copiar el texto."
    PdfTextMode.BUSCABLE_CON_TEXTO -> "Buscable y, además, páginas con el texto reconocido para leerlo o imprimirlo."
    PdfTextMode.SOLO_TEXTO -> "Solo el texto reconocido, maquetado. Muy liviano."
}

private fun Context.hasWritePermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun sanitize(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(100)

private fun fileNameOr(name: String, fallback: String): String =
    sanitize(name).removeSuffix(".pdf").removeSuffix(".txt").removeSuffix(".docx")
        .ifBlank { sanitize(fallback) }.ifBlank { "Escaneo" }

/** Caracteres de texto estimados (texto OCR conocido o ~1800 por página sin reconocer). */
private fun estimateTextChars(doc: Document): Long =
    doc.pages.sumOf { p -> (p.ocrEditedText ?: p.ocrText)?.length?.toLong() ?: 1_800L }

/** PDF: imágenes (si el modo las lleva) + capa de texto + páginas de texto + fuente incrustada. */
private fun estimatePdfBytes(doc: Document, quality: ExportQuality, mode: PdfTextMode, maxPixels: Int): Long {
    val chars = estimateTextChars(doc)
    val images = if (mode.hasImages) estimateBytes(doc, quality, null, maxPixels) else 0L
    val font = if (mode.needsOcr) 45_000L else 0L
    val layer = if (mode == PdfTextMode.BUSCABLE || mode == PdfTextMode.BUSCABLE_CON_TEXTO) (chars * 3.5).toLong() else 0L
    val textPages = if (mode == PdfTextMode.BUSCABLE_CON_TEXTO || mode == PdfTextMode.SOLO_TEXTO) (chars * 0.9).toLong() + 1_500L * doc.pages.size else 0L
    return images + font + layer + textPages + 2_000L
}

/** Estimación aproximada del tamaño final (bytes) según calidad, formato y filtros de cada página. */
private fun estimateBytes(doc: Document, quality: ExportQuality, imageFormat: ImageFormat?, maxPixels: Int): Long {
    var total = 0.0
    for (p in doc.pages) {
        var px = p.width.toDouble() * p.height * (if (p.edits.quad != null) 0.8 else 1.0)
        px = min(px, maxPixels.toDouble())
        val aspect = if (p.height > 0) p.width.toDouble() / p.height else 0.75
        val longSide = sqrt(px * max(aspect, 1.0 / aspect))
        if (!quality.isFullResolution && longSide > quality.maxLongSide) {
            val s = quality.maxLongSide / longSide
            px *= s * s
        }
        val binary = p.edits.filter == FilterType.BLACK_WHITE || p.edits.filter == FilterType.ECO_INK
        val bpp = when {
            imageFormat == ImageFormat.PNG -> if (binary) 0.12 else 1.1
            // WEBP en "Máxima (HD)" va sin pérdida.
            imageFormat == ImageFormat.WEBP && quality.isFullResolution -> if (binary) 0.1 else 0.9
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
private fun InfoLine(icon: ImageVector, text: String) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.brand.gradientStart.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.brand.gradientStart, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
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
    val fg by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurface, label = "pillFg")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.brand.horizontalGradient)
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
    }
}

/** Tarjeta de modo de texto del PDF: icono, título, descripción y selección única (accesible como radio). */
@Composable
private fun TextModeCard(icon: ImageVector, title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.brand.gradientStart
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.brand.gradient else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant)),
                shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) MaterialTheme.brand.gradient
                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHighest)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = primary))
    }
}

@Composable
private fun FormatCard(icon: ImageVector, title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.brand.gradientStart
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.brand.gradient else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant)),
                shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) MaterialTheme.brand.gradient
                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHighest)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
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
        Icon(icon, null, tint = MaterialTheme.brand.gradientStart)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.brand.gradientStart))
    }
}
