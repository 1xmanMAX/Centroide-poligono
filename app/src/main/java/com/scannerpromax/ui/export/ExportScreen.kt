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
import com.scannerpromax.data.VersionedFile
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
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.pdf.DocLayoutFiles
import com.scannerpromax.pdf.PdfPageInput
import com.scannerpromax.pdf.binaryHintFor
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.domain.Page
import com.scannerpromax.ui.components.AlertAction
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.ClearFieldButton
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.IosAlert
import com.scannerpromax.ui.components.IosTextField
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.OptionsSheet
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.ProgressHud
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.SheetHeader
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.components.pagesLabel
import com.scannerpromax.export.ExportSelection
import com.scannerpromax.ui.theme.GroupShape
import com.scannerpromax.ui.theme.SheetShape
import com.scannerpromax.ui.theme.ui
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
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

internal enum class ExportKind { PDF, IMAGES, TXT, DOCX }
private enum class ExportAction { SAVE, SHARE }

private data class Progress(val value: Float, val message: String)

/**
 * PDF generado y cuántas páginas quedaron sin texto reconocido (el OCR falló en ellas) o, en el PDF reconstruido,
 * sin reconocer con IA ([missingAi]).
 */
private class PdfBuild(val file: File, val missingText: Int, val missingAi: Int = 0)

private fun missingTextMessage(n: Int) =
    if (n == 1) "1 página sin texto reconocido (se exporta como imagen)"
    else "$n páginas sin texto reconocido (se exportan como imagen)"

private fun missingAiMessage(n: Int) =
    if (n == 1) "1 página sin reconocer con IA (se exporta como imagen)"
    else "$n páginas sin reconocer con IA (se exportan como imagen)"

/** Exportación con PDF de texto opcional ([PdfTextMode]) y la opción "Exportar en PDF con el texto reconocido". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    container: AppContainer,
    docId: String,
    onBack: () -> Unit,
    /** Solo estas páginas (modo selección del Documento), en el orden del documento; null = todas. */
    pageIds: List<String>? = null,
    /** Abre Ajustes › IA en la nube (hoja "Reconstruir con IA" sin clave). */
    onOpenCloudAi: () -> Unit = {},
) {
    val context = LocalContext.current
    var showAiRebuild by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fullDoc by remember(docId) { container.documents.observe(docId) }.collectAsState(initial = null)
    // Todo lo de esta pantalla (PDF, imágenes, texto, Word, estimaciones) trabaja con el subconjunto elegido.
    val doc = remember(fullDoc, pageIds) { fullDoc?.let { ExportSelection.apply(it, pageIds) } }
    val totalPages = fullDoc?.pages?.size ?: 0

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
    // Páginas con maquetación de la IA (DocLayout) para el PDF reconstruido; -1 = aún sin contar.
    var aiPages by remember { mutableIntStateOf(-1) }

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
    var cachedMissingAi by remember { mutableIntStateOf(0) }
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
        if (doc != null) missingOcr = container.documents.pagesMissingOcr(docId, doc.pages.map { it.id })
        doc?.let { dd ->
            aiPages = withContext(Dispatchers.IO) {
                dd.pages.count { p -> container.documents.processedFileIfReady(docId, p)?.let { DocLayoutFiles.exists(it) } == true }
            }
        }
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
            d.pages.joinToString { it.id + it.edits.hashCode() + "/" + it.processedFile + "/" + it.ocrEditedText.hashCode() },
            pageSize, quality, textMode, textPlacement, usePassword, password.hashCode(), fileName,
            // PDF reconstruido: si cambia cuántas páginas tienen maquetación de la IA, se rehace.
            if (textMode == PdfTextMode.RECONSTRUIDO) aiPages else 0,
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
        cachedPdf?.let { (k, f) -> if (k == key && f.exists()) return PdfBuild(f, 0, cachedMissingAi) }
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
        // PDF reconstruido: maquetación de la IA guardada junto a cada procesado (las que falten van como imagen).
        val layouts: List<DocLayout?> = if (mode == PdfTextMode.RECONSTRUIDO) {
            withContext(Dispatchers.IO) { files.map { DocLayoutFiles.read(it) } }
        } else {
            List(n) { null }
        }
        val missingAi = if (mode == PdfTextMode.RECONSTRUIDO) layouts.count { it == null } else 0
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
            pages.indices.map { PdfPageInput(files[it], ocrs[it], binaryHint = binaryHintFor(pages[it].edits.filter), layout = layouts[it]) },
            options,
            out,
        ) { p -> progressFlow.value = Progress(0.65f + 0.35f * p.coerceIn(0f, 1f), "Generando PDF…") }
        cachedPdf = if (missing == 0) key to out else null
        cachedMissingAi = missingAi
        return PdfBuild(out, missing, missingAi)
    }

    fun execute(action: ExportAction) {
        val d = doc ?: return
        if (d.pages.isEmpty()) {
            toast(if (pageIds != null) "Las páginas seleccionadas ya no existen" else "El documento no tiene páginas")
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
                            toast(
                                when {
                                    built.missingText > 0 -> "PDF guardado. ${missingTextMessage(built.missingText)}"
                                    built.missingAi > 0 -> "PDF guardado. ${missingAiMessage(built.missingAi)}"
                                    else -> "PDF guardado en Descargas"
                                },
                            )
                        } else {
                            if (built.missingText > 0) toast(missingTextMessage(built.missingText))
                            else if (built.missingAi > 0) toast(missingAiMessage(built.missingAi))
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
    val estimate: Long? = d?.takeIf { it.pages.isNotEmpty() }?.let { dd ->
        remember(dd, quality, kind, imageFormat, textMode, textPlacement, aiPages) {
            when (kind) {
                ExportKind.PDF -> estimatePdfBytes(dd, quality, textMode, container.deviceTier.maxWorkingPixels, aiPages)
                ExportKind.IMAGES -> estimateBytes(dd, quality, imageFormat, container.deviceTier.maxWorkingPixels)
                ExportKind.TXT -> estimateTextChars(dd) + 64L
                ExportKind.DOCX -> estimateTextChars(dd) / 3 + 3_000L
            }
        }
    }

    ExportSheetContent(
        title = d?.title,
        pages = d?.pages.orEmpty(),
        totalPages = if (pageIds != null) totalPages else null,
        kind = kind,
        onKind = { kind = it },
        textMode = textMode,
        onTextMode = { textMode = it },
        textPlacement = textPlacement,
        onTextPlacement = { textPlacement = it },
        quality = quality,
        onQuality = { quality = it },
        pageSize = pageSize,
        onPageSize = { pageSize = it },
        imageFormat = imageFormat,
        onImageFormat = { imageFormat = it },
        usePassword = usePassword,
        onUsePassword = { usePassword = it },
        password = password,
        onPassword = { password = it.take(64) },
        showPassword = showPassword,
        onShowPassword = { showPassword = it },
        fileName = fileName,
        onFileName = { fileName = it.take(100) },
        estimateBytes = estimate,
        aiPages = aiPages,
        missingOcr = missingOcr,
        lastSaved = lastSaved,
        canOpenSaved = savedUris.isNotEmpty(),
        onOpenSaved = { openSaved() },
        onShareSaved = { shareSaved() },
        ocrWarningCount = ocrWarning?.first,
        onRetry = { ocrWarning?.second?.let { start(it) } },
        busy = progress != null,
        onCancel = { if (job != null) job?.cancel() else onBack() },
        onSave = { start(ExportAction.SAVE) },
        onShare = { start(ExportAction.SHARE) },
        thumbnail = { p, i, m -> ExportThumb(container, docId, p, i, m) },
        onAiRebuild = { showAiRebuild = true },
    ) {
        // ------------------------------------------------ progreso
        val pr = progress
        ProgressHud(
            visible = pr != null,
            message = pr?.message ?: "",
            progress = pr?.value,
            onCancel = { job?.cancel() },
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (showAiRebuild) {
        com.scannerpromax.ui.docgen.AiRebuildSheet(
            container = container,
            docId = docId,
            pageIds = pageIds,
            onDismiss = { showAiRebuild = false },
            onOpenCloudSettings = onOpenCloudAi,
        )
    }
}

/** Miniatura de una página (procesado ACTUAL, lo mismo que se exporta), con su versión en la clave de caché. */
@Composable
private fun ExportThumb(container: AppContainer, docId: String, p: Page, index: Int, modifier: Modifier) {
    val context = LocalContext.current
    val perf = LocalPerf.current
    // Archivo resuelto en IO (nada de File.exists() en el hilo principal en cada recomposición).
    // Falso positivo de lint: el valor sí se asigna (tras withContext).
    @SuppressLint("ProduceStateDoesNotAssignValue")
    val model by produceState<VersionedFile?>(initialValue = null, p.id, p.thumbFile, p.processedFile, p.edits) {
        val f = withContext(Dispatchers.IO) { container.documents.displayThumb(docId, p) }
        value = f
    }
    // Decodificación acotada al tamaño de celda.
    val request = remember(model?.cacheKey, perf.thumbPx) {
        model?.let {
            ImageRequest.Builder(context).data(it.file).memoryCacheKey(it.cacheKey)
                .size(perf.thumbPx).precision(Precision.INEXACT).build()
        }
    }
    if (request != null) {
        AsyncImage(model = request, contentDescription = "Página ${index + 1}", contentScale = ContentScale.Fit, modifier = modifier)
    }
}

internal val ExportKindLabels = listOf("PDF", "Imágenes", "Texto", "Word")

/** Etiqueta corta de cada modo de texto del PDF (valor de la fila "Texto en el PDF"). */
private fun textModeShort(m: PdfTextMode): String = when (m) {
    PdfTextMode.SOLO_IMAGEN -> "Solo imagen"
    PdfTextMode.BUSCABLE -> "Buscable"
    PdfTextMode.BUSCABLE_CON_TEXTO -> "Buscable + texto"
    PdfTextMode.SOLO_TEXTO -> "Solo texto"
    PdfTextMode.RECONSTRUIDO -> "Reconstruido"
}

private fun imageFormatDescription(f: ImageFormat): String = when (f) {
    ImageFormat.JPEG -> "Compatible con todo, tamaño reducido."
    ImageFormat.PNG -> "Sin pérdida, máxima nitidez (archivos más grandes)."
    ImageFormat.WEBP -> "La mejor relación calidad/tamaño."
}

private enum class ExportSheetPicker { TEXT_MODE, PLACEMENT, QUALITY, PAGE_SIZE, IMAGE_FORMAT }

/**
 * Exportar como hoja (maqueta Exportar.dc.html), SIN estado: asa + Cancelar · Exportar, miniaturas, segmento
 * PDF/Imágenes/Texto/Word, grupo con Texto en el PDF, Calidad (+ tamaño estimado) y Nombre; grupo con Proteger con
 * contraseña y Más opciones; tarjetas de resultado; abajo Guardar (tonal) | Compartir (acento).
 */
@Composable
internal fun ExportSheetContent(
    title: String?,
    pages: List<Page>,
    /** Con selección de páginas: total del documento ("3 de 10 páginas"); null = se exportan todas. */
    totalPages: Int? = null,
    kind: ExportKind,
    onKind: (ExportKind) -> Unit,
    textMode: PdfTextMode,
    onTextMode: (PdfTextMode) -> Unit,
    textPlacement: TextPlacement,
    onTextPlacement: (TextPlacement) -> Unit,
    quality: ExportQuality,
    onQuality: (ExportQuality) -> Unit,
    pageSize: PageSize,
    onPageSize: (PageSize) -> Unit,
    imageFormat: ImageFormat,
    onImageFormat: (ImageFormat) -> Unit,
    usePassword: Boolean,
    onUsePassword: (Boolean) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    showPassword: Boolean,
    onShowPassword: (Boolean) -> Unit,
    fileName: String,
    onFileName: (String) -> Unit,
    estimateBytes: Long?,
    aiPages: Int,
    missingOcr: Int,
    lastSaved: String?,
    canOpenSaved: Boolean,
    onOpenSaved: () -> Unit,
    onShareSaved: () -> Unit,
    ocrWarningCount: Int?,
    onRetry: () -> Unit,
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    thumbnail: @Composable (Page, Int, Modifier) -> Unit,
    /** "Reconstruir con IA" (Word/PDF editable con IA en la nube); null = sin la fila. */
    onAiRebuild: (() -> Unit)? = null,
    overlays: @Composable BoxScope.() -> Unit = {},
) {
    val ui = MaterialTheme.ui
    val isPdf = kind == ExportKind.PDF
    val isText = kind == ExportKind.TXT || kind == ExportKind.DOCX
    val showQuality = kind == ExportKind.IMAGES || (isPdf && textMode.hasImages)
    var picker by remember { mutableStateOf<ExportSheetPicker?>(null) }
    var editingName by remember { mutableStateOf(false) }
    val ext = when (kind) {
        ExportKind.PDF -> ".pdf"
        ExportKind.IMAGES -> ".${imageFormat.ext}"
        ExportKind.TXT -> ".txt"
        ExportKind.DOCX -> ".docx"
    }

    // Fondo atenuado detrás de la hoja (simula la pantalla anterior) y la hoja con esquinas de 14 dp.
    Box(Modifier.fillMaxSize().background(if (ui.isDark) Color(0xFF1C1C1E) else Color(0xFF1C1C1E))) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 10.dp)
                .clip(SheetShape)
                .background(ui.groupedBackground),
        ) {
            SheetHeader(title = "Exportar", onCancel = onCancel)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // imePadding antes del scroll: reduce el área visible para que el campo enfocado quede a la vista.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                // Miniaturas + nombre del documento
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shown = pages.take(3)
                    shown.forEachIndexed { i, p ->
                        Box(
                            Modifier
                                .padding(end = 10.dp)
                                .size(width = 54.dp, height = 72.dp)
                                .shadow(2.dp, RoundedCornerShape(6.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (ui.isDark) Color(0xFF2C2C2E) else Color.White),
                        ) {
                            thumbnail(p, i, Modifier.fillMaxSize())
                            if (i == shown.lastIndex && pages.size > shown.size) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                    Text("+${pages.size - shown.size}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(title ?: "Cargando…", style = MaterialTheme.typography.titleSmall, color = ui.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (totalPages != null) ExportSelection.label(pages.size, totalPages) else pagesLabel(pages.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (totalPages != null && pages.size != totalPages) ui.accent else ui.secondaryLabel,
                        )
                    }
                }

                SegmentedControl(
                    options = ExportKindLabels,
                    selectedIndex = kind.ordinal,
                    onSelect = { onKind(ExportKind.entries[it]) },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
                )

                // ------------------------------------------------ grupo principal
                val notes = buildList {
                    if (estimateBytes != null) {
                        add("Tamaño estimado: ≈ ${formatBytes(estimateBytes)}" + if (showQuality) " · ${quality.resolutionLabel}" else "")
                    }
                    if (showQuality) add(quality.hint)
                    if (isPdf && textMode == PdfTextMode.RECONSTRUIDO && aiPages >= 0 && pages.isNotEmpty()) {
                        val total = pages.size
                        add(
                            when {
                                aiPages == 0 -> "Ninguna página está reconocida con IA todavía: se exportarán como imagen. " +
                                    "Reconócelas con IA para obtener el texto real con la misma disposición."
                                aiPages < total -> "${missingAiMessage(total - aiPages).replaceFirstChar { it.uppercase() }}. " +
                                    "Las otras $aiPages se reconstruyen con texto real."
                                else -> "Todas las páginas se reconstruyen con texto real, tablas y la misma disposición."
                            },
                        )
                    }
                    if (isPdf && textMode.needsOcr && missingOcr > 0) {
                        add(
                            if (missingOcr == 1) "Se reconocerá el texto de 1 página automáticamente al exportar."
                            else "Se reconocerá el texto de $missingOcr páginas automáticamente al exportar.",
                        )
                    }
                    if (isText) {
                        add(
                            "Incluye el texto reconocido de todas las páginas (con tus correcciones). " +
                                if (missingOcr > 0) "Las $missingOcr páginas sin reconocer se procesarán ahora." else "Todo el texto está listo.",
                        )
                    }
                }
                InsetGroup(
                    modifier = Modifier.padding(top = 20.dp),
                    separatorInset = SeparatorInsetPlain,
                    footer = notes.joinToString("\n").ifEmpty { null },
                ) {
                    if (isPdf) {
                        item { NavigationRow("Texto en el PDF", { picker = ExportSheetPicker.TEXT_MODE }, value = textModeShort(textMode)) }
                        if (textMode == PdfTextMode.BUSCABLE_CON_TEXTO) {
                            item { NavigationRow("Páginas de texto", { picker = ExportSheetPicker.PLACEMENT }, value = textPlacement.label) }
                        }
                    }
                    if (showQuality) {
                        item { NavigationRow("Calidad", { picker = ExportSheetPicker.QUALITY }, value = quality.label) }
                    }
                    item {
                        NavigationRow("Nombre", { editingName = true }, value = (fileName.ifBlank { title ?: "" }) + ext)
                    }
                }

                // ------------------------------------------------ reconstruir con IA (Word / PDF editable)
                if (onAiRebuild != null && (isPdf || kind == ExportKind.DOCX)) {
                    InsetGroup(
                        modifier = Modifier.padding(top = 22.dp),
                        footer = "La IA en la nube vuelve a escribir el documento: mismo texto, títulos, tablas reales e imágenes.",
                    ) {
                        item { NavigationRow("Reconstruir con IA", onAiRebuild, icon = AppIcons.Sparkles, iconTint = ui.accent, enabled = !busy) }
                    }
                }

                // ------------------------------------------------ contraseña y más opciones
                val showMore = (isPdf && textMode.hasImages) || kind == ExportKind.IMAGES
                if (isPdf || showMore) {
                    InsetGroup(
                        modifier = Modifier.padding(top = 22.dp),
                        separatorInset = SeparatorInsetPlain,
                        footer = if (isPdf && usePassword) "Pedirá la contraseña al abrir el PDF (mínimo 4 caracteres)." else null,
                    ) {
                        if (isPdf) {
                            item { ToggleRow("Proteger con contraseña", usePassword, onUsePassword) }
                            if (usePassword) {
                                item {
                                    PasswordRow(password, onPassword, showPassword, onShowPassword)
                                }
                            }
                        }
                        if (showMore) {
                            item {
                                NavigationRow(
                                    "Más opciones",
                                    {
                                        picker = if (isPdf) ExportSheetPicker.PAGE_SIZE else ExportSheetPicker.IMAGE_FORMAT
                                    },
                                    value = if (isPdf) "Tamaño: ${pageSize.label}" else "Formato: ${imageFormat.label}",
                                )
                            }
                        }
                    }
                }

                // ------------------------------------------------ tarjetas de resultado
                if (lastSaved != null) {
                    ResultCard(
                        icon = AppIcons.Check,
                        tint = ui.green,
                        text = lastSaved,
                    ) {
                        if (canOpenSaved) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryCapsuleButton("Abrir", onClick = onOpenSaved, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f))
                                PrimaryCapsuleButton("Compartir", onClick = onShareSaved, icon = AppIcons.Share, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (ocrWarningCount != null) {
                    ResultCard(icon = AppIcons.Info, tint = ui.orange, text = missingTextMessage(ocrWarningCount)) {
                        PrimaryCapsuleButton("Reintentar", onClick = onRetry, enabled = !busy, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // ------------------------------------------------ Guardar | Compartir
            val enabled = pages.isNotEmpty() && !busy
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // El destino (Descargas / Galería) se indica en la tarjeta de éxito.
                PrimaryCapsuleButton("Guardar", onClick = onSave, enabled = enabled, tonal = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                PrimaryCapsuleButton("Compartir", onClick = onShare, enabled = enabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
            }
        }
        overlays()
    }

    when (picker) {
        ExportSheetPicker.TEXT_MODE -> OptionsSheet(
            title = "Texto en el PDF",
            options = PdfTextMode.entries,
            selected = textMode,
            label = { it.label },
            description = { textModeDescription(it) },
            onSelect = onTextMode,
            onDismiss = { picker = null },
        )
        ExportSheetPicker.PLACEMENT -> OptionsSheet(
            title = "Páginas de texto reconocido",
            options = TextPlacement.entries,
            selected = textPlacement,
            label = { it.label },
            onSelect = onTextPlacement,
            onDismiss = { picker = null },
        )
        ExportSheetPicker.QUALITY -> OptionsSheet(
            title = "Calidad",
            options = ExportQuality.entries,
            selected = quality,
            label = { it.label },
            description = { it.hint },
            footer = "La app guarda el escaneo completo: solo se reduce al exportar.",
            onSelect = onQuality,
            onDismiss = { picker = null },
        )
        ExportSheetPicker.PAGE_SIZE -> OptionsSheet(
            title = "Tamaño de página",
            options = PageSize.entries,
            selected = pageSize,
            label = { it.label },
            onSelect = onPageSize,
            onDismiss = { picker = null },
        )
        ExportSheetPicker.IMAGE_FORMAT -> OptionsSheet(
            title = "Tipo de imagen",
            options = ImageFormat.entries,
            selected = imageFormat,
            label = { it.label },
            description = { imageFormatDescription(it) },
            onSelect = onImageFormat,
            onDismiss = { picker = null },
        )
        null -> Unit
    }
    if (editingName) {
        FileNameDialog(initial = fileName.ifBlank { title ?: "" }, ext = ext, onConfirm = onFileName, onDismiss = { editingName = false })
    }
}

/** Fila de contraseña dentro del grupo (campo seguro con botón mostrar/ocultar). */
@Composable
private fun PasswordRow(password: String, onPassword: (String) -> Unit, showPassword: Boolean, onShowPassword: (Boolean) -> Unit) {
    val ui = MaterialTheme.ui
    var value by remember { mutableStateOf(TextFieldValue(password)) }
    if (value.text != password) value = TextFieldValue(password, TextRange(password.length))
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Lock, null, tint = ui.secondaryLabel, modifier = Modifier.size(18.dp))
        IosTextField(
            value = value,
            onValueChange = { value = it; onPassword(it.text) },
            placeholder = "Contraseña",
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
            trailing = {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = if (showPassword) "Ocultar" else "Mostrar") { onShowPassword(!showPassword) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        if (showPassword) "Ocultar" else "Mostrar",
                        tint = ui.secondaryLabel,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
    }
}

/** Alerta para el nombre del archivo (la extensión se muestra debajo). */
@Composable
private fun FileNameDialog(initial: String, ext: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    IosAlert(
        title = "Nombre del archivo",
        message = "Se guardará como «${value.text.ifBlank { "…" }}$ext».",
        onDismiss = onDismiss,
        actions = listOf(
            AlertAction("Cancelar") { },
            AlertAction("Aceptar", bold = true) { onConfirm(value.text.trim()) },
        ),
    ) {
        IosTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = "Nombre",
            trailing = { if (value.text.isNotEmpty()) ClearFieldButton { value = TextFieldValue("") } },
        )
    }
}

/** Tarjeta de resultado (guardado / aviso) en el estilo de los grupos: icono de color, texto y acciones. */
@Composable
private fun ResultCard(icon: ImageVector, tint: Color, text: String, actions: @Composable () -> Unit) {
    val ui = MaterialTheme.ui
    Column(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 22.dp)
            .fillMaxWidth()
            .clip(GroupShape)
            .background(ui.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = ui.label)
        }
        Spacer(Modifier.height(12.dp))
        actions()
    }
}

private fun textModeIcon(m: PdfTextMode): ImageVector = when (m) {
    PdfTextMode.SOLO_IMAGEN -> Icons.Filled.Image
    PdfTextMode.BUSCABLE -> Icons.Filled.Search
    PdfTextMode.BUSCABLE_CON_TEXTO -> Icons.Filled.FindInPage
    PdfTextMode.SOLO_TEXTO -> Icons.Filled.TextFields
    PdfTextMode.RECONSTRUIDO -> Icons.Filled.AutoAwesome
}

private fun textModeDescription(m: PdfTextMode): String = when (m) {
    PdfTextMode.SOLO_IMAGEN -> "Las páginas tal como se ven, sin texto."
    PdfTextMode.BUSCABLE -> "Se ve igual, pero puedes buscar, seleccionar y copiar el texto."
    PdfTextMode.BUSCABLE_CON_TEXTO -> "Buscable y, además, páginas con el texto reconocido para leerlo o imprimirlo."
    PdfTextMode.SOLO_TEXTO -> "Solo el texto reconocido, maquetado. Muy liviano."
    PdfTextMode.RECONSTRUIDO -> "PDF reconstruido (texto real, misma disposición): títulos, tablas y gráficos como en el " +
        "original. Solo páginas reconocidas con IA; el resto, como imagen."
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
private fun estimatePdfBytes(doc: Document, quality: ExportQuality, mode: PdfTextMode, maxPixels: Int, aiPages: Int = 0): Long {
    val chars = estimateTextChars(doc)
    if (mode == PdfTextMode.RECONSTRUIDO) {
        // Páginas reconstruidas: texto vectorial + fuentes + recortes (~15 % de la imagen); el resto, imagen entera.
        val n = doc.pages.size.coerceAtLeast(1)
        val ai = aiPages.coerceIn(0, n)
        val allImages = estimateBytes(doc, quality, null, maxPixels)
        val perPage = allImages / n
        return perPage * (n - ai) + (perPage * 0.15).toLong() * ai + (chars * 4.5).toLong() * ai / n + 90_000L + 2_000L
    }
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

