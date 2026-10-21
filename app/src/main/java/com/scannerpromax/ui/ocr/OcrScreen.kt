package com.scannerpromax.ui.ocr

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.AutoAwesome
import com.scannerpromax.ai.AiRecognitionQueue
import com.scannerpromax.ai.RecognitionEngine
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.domain.PdfOptions
import com.scannerpromax.domain.PdfTextMode
import com.scannerpromax.export.TextFormat
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.ocr.OcrText
import com.scannerpromax.pdf.PdfPageInput
import com.scannerpromax.pdf.binaryHintFor
import com.scannerpromax.ui.components.AlertAction
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.IconTile
import com.scannerpromax.ui.components.IosAlert
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.MoreMenu
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.ProgressHud
import com.scannerpromax.ui.theme.GroupShape
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.ui
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/** Línea tocada en la imagen y, si el OCR trae palabras, la palabra concreta. */
internal data class Selection(val line: OcrLine, val word: OcrWord?)

private enum class PdfAction { SHARE, SAVE }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(
    container: AppContainer,
    docId: String,
    pageId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.ui.accent
    val secondary = MaterialTheme.ui.accent

    val doc by remember(docId) { container.documents.observe(docId) }.collectAsState(initial = null)
    val pages = doc?.pages.orEmpty()
    // Página mostrada (se puede cambiar con los chips) y modo "Todo el documento".
    var currentPageId by rememberSaveable(pageId) { mutableStateOf(pageId) }
    var wholeDoc by rememberSaveable { mutableStateOf(false) }

    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<OcrResult?>(null) }
    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Preparando imagen…") }
    var selected by remember { mutableStateOf<Selection?>(null) }
    var ocrJob by remember { mutableStateOf<Job?>(null) }
    var title by remember { mutableStateOf("Texto") }
    var docText by remember { mutableStateOf("") }
    // Texto por página del modo "Todo el documento" (se muestra en una lista virtualizada, no en un único campo).
    var docPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var docProgress by remember { mutableStateOf<Float?>(null) }
    var docJob by remember { mutableStateOf<Job?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmRecognize by remember { mutableStateOf(false) }
    var askPdfAction by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf<Pair<Float, String>?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var pendingSaveTxt by remember { mutableStateOf<(() -> Unit)?>(null) }

    // ---- Lectura con IA (motor principal si el usuario eligió un modelo): estado de la cola para esta página
    val aiStatusMap by container.aiQueue.status.collectAsState()
    val aiStatus = aiStatusMap["$docId/$currentPageId"]
    val engine by remember { container.settings.settings }.collectAsState(initial = null)
    val aiEngineOn = engine?.recognitionEngine?.let { it != RecognitionEngine.BASIC } == true
    var readByAi by remember { mutableStateOf(false) }

    val baseline = result?.displayText
    val edited = !wholeDoc && result != null && text != baseline
    val shownText = if (wholeDoc) docText else text

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // Reconocer el documento entero o exportar puede tardar minutos en gama baja: la pantalla no se apaga mientras.
    val view = LocalView.current
    val busy = exportJob != null || docJob != null
    DisposableEffect(busy) {
        if (busy) view.keepScreenOn = true
        onDispose { if (busy) view.keepScreenOn = false }
    }

    fun recognize(forPage: String) {
        ocrJob?.cancel()
        ocrJob = scope.launch {
            running = true
            status = "Reconociendo texto…"
            selected = null
            try {
                val r = container.documents.recognizePage(docId, forPage, keepEditedText = false)
                result = r
                text = r.displayText
                docText = "" // el texto del documento completo queda desactualizado
                docPages = emptyList()
                if (r.text.isBlank()) toast("No se encontró texto en esta página")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudo reconocer el texto: ${t.message ?: "error"}")
            } finally {
                running = false
            }
        }
    }

    LaunchedEffect(docId, currentPageId) {
        ocrJob?.cancel()
        running = true
        selected = null
        result = null
        text = ""
        try {
            title = container.documents.get(docId)?.title ?: "Texto"
            val page = container.documents.getPage(docId, currentPageId) ?: throw IOException("Página no encontrada")
            status = "Preparando imagen…"
            val file = container.documents.processedFile(docId, page)
            val displayPx = if (container.deviceTier.isLowRam) 2_000_000 else 4_000_000
            val bmp = withContext(Dispatchers.Default) { BitmapIO.decode(file.absolutePath, displayPx) }
            // La imagen anterior se libera al sustituirla (cambio de página).
            val old = displayBitmap
            displayBitmap = bmp
            image = bmp.asImageBitmap()
            old?.recycle()
            status = "Reconociendo texto…"
            // OCR guardado o, si falta, reconocimiento (comparte cola y candado con el OCR de fondo).
            val saved = container.documents.ensureOcr(docId, currentPageId)
            if (saved != null) {
                result = saved
                text = saved.displayText
            } else {
                toast("No se pudo reconocer el texto de esta página")
            }
            readByAi = container.documents.hasAiLayout(docId, currentPageId)
            running = false
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            running = false
            toast(t.message ?: "No se pudo abrir la página")
            onBack()
        }
    }
    DisposableEffect(Unit) {
        onDispose { displayBitmap?.recycle() }
    }

    // Al terminar la lectura con IA de esta página se muestra su resultado (si el usuario no está editando).
    var lastAiStatus by remember { mutableStateOf<AiRecognitionQueue.PageStatus?>(null) }
    LaunchedEffect(aiStatus) {
        val was = lastAiStatus
        lastAiStatus = aiStatus
        if (was != null && aiStatus == null) {
            val ai = container.documents.hasAiLayout(docId, currentPageId)
            readByAi = ai
            if (ai && !edited) {
                container.documents.loadOcr(docId, currentPageId)?.let { r ->
                    result = r
                    text = r.displayText
                    selected = null
                    docText = ""
                    docPages = emptyList()
                }
            }
        }
    }

    fun readWithAi() {
        scope.launch {
            container.documents.flushPendingWrites()
            val err = container.aiQueue.reread(docId, currentPageId)
            if (err != null) toast(err) else toast("En cola: la página se leerá con IA en segundo plano")
        }
    }

    /** Reconoce (o carga el OCR guardado de) todas las páginas, de una en una, y las concatena con separadores. */
    fun runWholeDocument() {
        if (docJob != null) return
        docJob = scope.launch {
            docProgress = 0f
            try {
                val results = container.documents.ensureOcrAll(docId) { done, total, recognizing ->
                    if (recognizing) status = "Reconociendo página ${done + 1} de $total…"
                    docProgress = done.toFloat() / total.coerceAtLeast(1)
                }
                val missing = results.count { it == null }
                // Concatenar/limpiar decenas de páginas fuera del hilo principal.
                val (all, parts) = withContext(Dispatchers.Default) {
                    OcrText.documentText(results) to results.mapIndexed { i, r ->
                        val body = OcrText.plainText(r).ifEmpty { if (r == null) "(no se pudo reconocer)" else "(sin texto)" }
                        if (results.size > 1) "— Página ${i + 1} —\n$body" else body
                    }
                }
                docText = all
                docPages = parts
                if (missing > 0) toast(if (missing == 1) "1 página sin texto reconocido" else "$missing páginas sin texto reconocido")
            } finally {
                docProgress = null
                docJob = null
            }
        }
    }

    fun shareText() {
        if (shownText.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shownText)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Compartir texto")) }
    }

    /** Guarda el texto mostrado como .txt en Descargas/EscanerProMax (con opción de abrir/compartir por el sistema). */
    fun saveTxt() {
        if (shownText.isBlank()) return
        val content = shownText
        scope.launch {
            try {
                val name = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifBlank { "Texto" }.take(80)
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(dir, "$name.txt").apply {
                        outputStream().use { os ->
                            os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                            os.write(content.replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                container.imageExporter.saveToDownloads(file, name, TextFormat.TXT.mime, TextFormat.TXT.ext)
                toast("Guardado en Descargas/EscanerProMax")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudo guardar el .txt")
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val a = pendingSaveTxt
        pendingSaveTxt = null
        if (ok && a != null) a() else if (!ok) toast("Se necesita permiso de almacenamiento para guardar")
    }

    fun withStorage(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (granted) action() else {
            pendingSaveTxt = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** PDF del documento con imagen buscable + páginas de texto reconocido (el OCR que falte se hace aquí). */
    fun exportPdfWithText(action: PdfAction) {
        if (exportJob != null) return
        exportJob = scope.launch {
            exportProgress = 0f to "Preparando…"
            try {
                val d = container.documents.get(docId) ?: throw IOException("Documento no encontrado")
                val n = d.pages.size
                val files = d.pages.mapIndexed { i, p ->
                    exportProgress = 0.25f * i / n to "Preparando página ${i + 1}/$n…"
                    container.documents.processedFile(docId, p)
                }
                val ocrs = container.documents.ensureOcrAll(docId, d.pages.map { it.id }) { done, total, recognizing ->
                    exportProgress = (0.25f + 0.4f * done / total.coerceAtLeast(1)) to
                        (if (recognizing) "Reconociendo texto ${done + 1}/$total…" else "Texto listo $done/$total")
                }
                val settings = container.settings.settings.first()
                exportProgress = 0.65f to "Generando PDF…"
                val safe = d.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifBlank { "Escaneo" }.take(100)
                val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, "$safe.pdf")
                val options = PdfOptions(
                    pageSize = settings.pdfPageSize,
                    quality = settings.exportQuality,
                    textMode = PdfTextMode.BUSCABLE_CON_TEXTO,
                )
                // Páginas cuyo OCR falló: van como imagen sin capa de texto. Se avisa (no se finge un PDF buscable).
                val missing = ocrs.count { it == null }
                val missingMsg = if (missing == 1) "1 página sin texto reconocido (se exporta como imagen)"
                else "$missing páginas sin texto reconocido (se exportan como imagen)"
                container.pdfExporter.export(
                    d.pages.indices.map { PdfPageInput(files[it], ocrs[it], binaryHint = binaryHintFor(d.pages[it].edits.filter)) },
                    options,
                    out,
                ) { p ->
                    exportProgress = (0.65f + 0.35f * p.coerceIn(0f, 1f)) to "Generando PDF…"
                }
                if (action == PdfAction.SAVE) {
                    exportProgress = 1f to "Guardando en Descargas…"
                    container.imageExporter.savePdfToDownloads(out, safe)
                    if (missing > 0) {
                        Toast.makeText(context, "PDF guardado. $missingMsg. Vuelve a intentarlo para reconocerlas.", Toast.LENGTH_LONG).show()
                    } else {
                        toast("PDF con texto guardado en Descargas")
                    }
                } else {
                    if (missing > 0) Toast.makeText(context, missingMsg, Toast.LENGTH_LONG).show()
                    context.startActivity(container.imageExporter.shareChooser(listOf(out), "application/pdf", "Compartir PDF"))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudo crear el PDF: ${t.message ?: "error"}")
            } finally {
                exportProgress = null
                exportJob = null
            }
        }
    }

    fun saveEdited(then: (() -> Unit)? = null) {
        val r = result ?: return
        val forPage = currentPageId
        val newText = text
        scope.launch {
            runCatching { container.documents.saveEditedText(docId, forPage, newText) }
                .onSuccess {
                    result = r.copy(editedText = newText.takeIf { it.isNotBlank() && it != r.text })
                    text = result?.displayText ?: newText
                    docText = ""
                    docPages = emptyList()
                    toast("Texto guardado: se usará en el PDF, .txt y .docx")
                    then?.invoke()
                }
                .onFailure { toast("No se pudo guardar el texto") }
        }
    }

    /** Ejecuta [action] o, si hay texto corregido sin guardar, pregunta antes. */
    fun guardEdits(action: () -> Unit) {
        if (edited) pendingAction = action else action()
    }

    BackHandler(enabled = edited || exportJob != null) {
        if (exportJob != null) exportJob?.cancel() else guardEdits(onBack)
    }

    // Contador fuera del hilo principal y con espera mientras se escribe (sin Regex ni listas intermedias).
    // Falso positivo de lint: el valor sí se asigna (tras withContext).
    @SuppressLint("ProduceStateDoesNotAssignValue")
    val words by produceState(initialValue = 0, shownText) {
        if (!wholeDoc) delay(250)
        val n = withContext(Dispatchers.Default) { countWords(shownText) }
        value = n
    }
    val hasText = shownText.isNotBlank()
    val menuGroups = listOf(
        listOfNotNull(
            if (!wholeDoc) {
                MoreMenuItem("Volver a reconocer", AppIcons.Rotate, enabled = !running && image != null) {
                    if (result?.editedText != null || edited) confirmRecognize = true else recognize(currentPageId)
                }
            } else null,
            if (!wholeDoc && (aiEngineOn || aiStatus != null)) {
                MoreMenuItem(if (readByAi) "Volver a leer con IA" else "Leer con IA", AppIcons.Sparkles, enabled = !running && image != null && aiStatus == null) {
                    guardEdits { readWithAi() }
                }
            } else null,
        ),
        listOf(
            MoreMenuItem("Guardar .txt", AppIcons.Download, enabled = hasText) { withStorage { saveTxt() } },
            MoreMenuItem("PDF con texto…", AppIcons.Document, enabled = pages.isNotEmpty() && exportJob == null) { guardEdits { askPdfAction = true } },
        ),
    )

    OcrContent(
        docTitle = title,
        wholeDoc = wholeDoc,
        pageCount = pages.size,
        currentPageIndex = pages.indexOfFirst { it.id == currentPageId },
        onSelectAll = {
            guardEdits {
                wholeDoc = true
                if (docText.isEmpty()) runWholeDocument()
            }
        },
        onSelectPage = { i ->
            pages.getOrNull(i)?.let { p ->
                guardEdits {
                    wholeDoc = false
                    currentPageId = p.id
                }
            }
        },
        imageContent = {
            val img = image
            if (img == null) {
                CircularProgressIndicator(color = MaterialTheme.ui.secondaryLabel, strokeWidth = 3.dp)
            } else {
                OcrImage(image = img, result = result, selected = selected, onSelect = { selected = it }, primary = primary, secondary = secondary)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = running,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(status, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.width(180.dp).clip(RoundedCornerShape(50)), color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
                }
            }
        },
        aiRow = if (aiEngineOn || aiStatus != null) {
            {
                AiReadRow(
                    status = aiStatus,
                    readByAi = readByAi,
                    enabled = !running && image != null,
                    accent = primary,
                    onRead = { guardEdits { readWithAi() } },
                    onCancel = { container.aiQueue.cancel(docId, currentPageId) },
                )
            }
        } else null,
        selection = selected,
        onCopyWord = { w -> clipboard.setText(AnnotatedString(w)); toast("Palabra copiada") },
        onCopyLine = { l -> clipboard.setText(AnnotatedString(l)); toast("Línea copiada") },
        onCloseSelection = { selected = null },
        docProgress = docProgress,
        status = status,
        onCancelDoc = { docJob?.cancel() },
        textLabel = when {
            wholeDoc -> "Todas las páginas"
            result?.editedText != null -> "Texto corregido"
            else -> "Texto completo"
        },
        counts = "$words palabras · ${shownText.length} caracteres",
        text = shownText,
        onTextChange = { if (!wholeDoc) text = it },
        placeholder = when {
            wholeDoc && docProgress != null -> "Reconociendo el documento…"
            running -> "Reconociendo…"
            else -> "No se encontró texto. Prueba el filtro «Blanco y negro» o «Texto resaltado» y vuelve a reconocer."
        },
        supporting = if (!wholeDoc && result != null) {
            if (edited) "Cambios sin guardar" else "Puedes corregir el texto: se usará en el PDF buscable y en las exportaciones"
        } else null,
        docPages = docPages,
        menuGroups = menuGroups,
        onBack = { guardEdits(onBack) },
        hasText = hasText,
        onCopy = {
            clipboard.setText(AnnotatedString(shownText))
            toast("Texto copiado")
        },
        onShare = { shareText() },
        showSave = !wholeDoc,
        saveEnabled = edited,
        onSave = { saveEdited() },
    ) {
        val ep = exportProgress
        ProgressHud(
            visible = ep != null,
            message = ep?.second ?: "",
            progress = ep?.first,
            onCancel = { exportJob?.cancel() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    pendingAction?.let { action ->
        IosAlert(
            title = "¿Guardar el texto corregido?",
            message = "Has corregido el texto de esta página. Si no lo guardas, se perderán los cambios.",
            onDismiss = { pendingAction = null },
            actions = listOf(
                AlertAction("Guardar", bold = true) { saveEdited(then = action) },
                AlertAction("Descartar", destructive = true) {
                    text = result?.displayText.orEmpty()
                    action()
                },
                AlertAction("Seguir editando") { },
            ),
        )
    }

    if (confirmRecognize) {
        IosAlert(
            title = "¿Volver a reconocer?",
            message = "Se descartarán tus correcciones de esta página y se reconocerá el texto de nuevo.",
            onDismiss = { confirmRecognize = false },
            actions = listOf(
                AlertAction("Cancelar") { },
                AlertAction("Reconocer", bold = true) { recognize(currentPageId) },
            ),
        )
    }

    if (askPdfAction) {
        IosAlert(
            title = "Exportar PDF con texto",
            message = "PDF buscable de todo el documento con páginas del texto reconocido" +
                (if (pages.size > 1) " tras cada página." else ".") + " Se usarán tus correcciones.",
            onDismiss = { askPdfAction = false },
            actions = listOf(
                AlertAction("Guardar en Descargas", bold = true) { withStorage { exportPdfWithText(PdfAction.SAVE) } },
                AlertAction("Compartir") { exportPdfWithText(PdfAction.SHARE) },
                AlertAction("Cancelar") { },
            ),
        )
    }
}

/**
 * Texto reconocido SIN estado: cabecera grande con ••• (volver a reconocer, leer con IA, .txt, PDF con texto),
 * selector Todo · 1 · 2…, imagen con las líneas, lectura con IA, selección, texto editable y abajo
 * Copiar · Compartir · Guardar.
 */
@Composable
internal fun OcrContent(
    docTitle: String,
    wholeDoc: Boolean,
    pageCount: Int,
    currentPageIndex: Int,
    onSelectAll: () -> Unit,
    onSelectPage: (Int) -> Unit,
    imageContent: @Composable BoxScope.() -> Unit,
    aiRow: (@Composable () -> Unit)?,
    selection: Selection?,
    onCopyWord: (String) -> Unit,
    onCopyLine: (String) -> Unit,
    onCloseSelection: () -> Unit,
    docProgress: Float?,
    status: String,
    onCancelDoc: () -> Unit,
    textLabel: String,
    counts: String,
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    supporting: String?,
    docPages: List<String>,
    menuGroups: List<List<MoreMenuItem>>,
    onBack: () -> Unit,
    hasText: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    showSave: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    overlays: @Composable BoxScope.() -> Unit = {},
) {
    val ui = MaterialTheme.ui
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(ui.groupedBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            LargeTitleHeader(
                title = if (wholeDoc) "Texto del documento" else "Texto reconocido",
                backLabel = "Documento",
                onBack = onBack,
                subtitle = docTitle,
                titleStyle = MaterialTheme.typography.headlineLarge,
            ) {
                Box {
                    IconCircleButton(AppIcons.More, "Más opciones del texto", { menu = true }, size = 36.dp, iconSize = 18.dp)
                    MoreMenu(expanded = menu, onDismissRequest = { menu = false }, groups = menuGroups, width = 260.dp)
                }
            }

            // ------------------------------------------------ selector Todo · página
            if (pageCount > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    item(key = "__all__") { PagePill("Todo el documento", wholeDoc, onSelectAll) }
                    items(pageCount, key = { it }) { i -> PagePill("Pág. ${i + 1}", !wholeDoc && i == currentPageIndex) { onSelectPage(i) } }
                }
            }

            if (!wholeDoc) {
                // ------------------------------------------------ imagen con cajas de líneas / palabras
                Box(
                    Modifier
                        .padding(top = 12.dp)
                        .weight(0.46f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(GroupShape)
                        .background(ui.card),
                    contentAlignment = Alignment.Center,
                    content = imageContent,
                )
                aiRow?.invoke()
                // Palabra / línea seleccionada
                AnimatedVisibility(
                    visible = selection != null,
                    enter = slideInVertically { -it / 2 } + fadeIn(),
                    exit = slideOutVertically { -it / 2 } + fadeOut(),
                ) {
                    val sel = selection
                    Row(
                        Modifier
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                            .fillMaxWidth()
                            .clip(GroupShape)
                            .background(ui.card)
                            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            sel?.word?.let {
                                Text(it.text, style = MaterialTheme.typography.titleSmall, color = ui.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(sel?.line?.text ?: "", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        sel?.word?.let { w -> SmallTextButton("Palabra") { onCopyWord(w.text) } }
                        IconCircleButton(AppIcons.Copy, "Copiar línea", { sel?.let { onCopyLine(it.line.text) } }, size = 36.dp, iconSize = 18.dp, containerColor = Color.Transparent, contentColor = ui.accent, elevation = 0.dp)
                        IconCircleButton(AppIcons.Close, "Cerrar", onCloseSelection, size = 36.dp, iconSize = 16.dp, containerColor = Color.Transparent, contentColor = ui.secondaryLabel, elevation = 0.dp)
                    }
                }
            } else {
                // ------------------------------------------------ progreso del documento completo
                AnimatedVisibility(visible = docProgress != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status, style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel, modifier = Modifier.weight(1f))
                            SmallTextButton("Cancelar", onCancelDoc)
                        }
                        LinearProgressIndicator(
                            progress = { docProgress ?: 0f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                            color = ui.accent,
                            trackColor = ui.fill,
                            drawStopIndicator = {},
                        )
                    }
                }
            }

            // ------------------------------------------------ texto (editable por página)
            Row(
                Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(textLabel.uppercase(), style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.weight(1f))
                Text(counts, style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
            }
            Box(
                Modifier
                    .weight(if (wholeDoc) 1f else 0.54f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(GroupShape)
                    .background(ui.card),
            ) {
                if (wholeDoc) {
                    // Documento completo: una entrada por página en una lista virtualizada (un único TextField con
                    // 100 mil caracteres se maqueta y pinta entero en el hilo principal: jank o casi ANR en gama baja).
                    if (docPages.isEmpty()) {
                        Text(
                            if (docProgress != null) "Reconociendo el documento…" else "No se encontró texto.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ui.secondaryLabel,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(docPages, contentType = { _, _ -> "page" }) { _, pageText ->
                                SelectionContainer {
                                    Text(pageText, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp), color = ui.label)
                                }
                            }
                        }
                    }
                } else {
                    val style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp, color = ui.label)
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = style,
                        cursorBrush = SolidColor(ui.accent),
                        modifier = Modifier.fillMaxSize(),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                                if (text.isEmpty()) Text(placeholder, style = style, color = ui.tertiaryLabel)
                                inner()
                            }
                        },
                    )
                }
            }
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saveEnabled) ui.orange else ui.secondaryLabel,
                    modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 6.dp),
                )
            }

            // ------------------------------------------------ Copiar · Compartir · Guardar
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryCapsuleButton("Copiar", onClick = onCopy, icon = AppIcons.Copy, enabled = hasText, tonal = true, height = 48.dp, modifier = Modifier.weight(1f))
                PrimaryCapsuleButton("Compartir", onClick = onShare, icon = AppIcons.Share, enabled = hasText, tonal = true, height = 48.dp, modifier = Modifier.weight(1f))
                if (showSave) {
                    PrimaryCapsuleButton("Guardar", onClick = onSave, enabled = saveEnabled, height = 48.dp, modifier = Modifier.weight(1f))
                }
            }
        }
        overlays()
    }
}

@Composable
private fun PagePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val ui = MaterialTheme.ui
    Text(
        label,
        color = if (selected) ui.groupedBackground else ui.label,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(PillShape)
            .background(if (selected) ui.label else ui.fill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SmallTextButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = MaterialTheme.ui.accent,
        fontSize = 15.sp,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * Imagen procesada con las líneas reconocidas resaltadas; tocar selecciona la palabra (si el OCR la trae) y su línea.
 * Solo se dibujan las cajas de línea (y la palabra elegida): con cientos de palabras dibujarlas todas costaría frames
 * en gama baja.
 */
@Composable
private fun OcrImage(
    image: ImageBitmap,
    result: OcrResult?,
    selected: Selection?,
    onSelect: (Selection?) -> Unit,
    primary: Color,
    secondary: Color,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val rect = fit(boxSize, image.width, image.height, 10f)
    val currentRect by rememberUpdatedState(rect)
    val currentResult by rememberUpdatedState(result)
    val select by rememberUpdatedState(onSelect)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val r = currentResult ?: return@detectTapGestures
                    val rc = currentRect
                    if (r.imageWidth <= 0 || rc.width <= 0f) return@detectTapGestures
                    val s = rc.width / r.imageWidth
                    val sy = rc.height / r.imageHeight.coerceAtLeast(1)
                    val ix = (pos.x - rc.left) / s
                    val iy = (pos.y - rc.top) / sy
                    val tol = 8f / s
                    fun hit(b: OcrRect) = ix >= b.left - tol && ix <= b.right + tol && iy >= b.top - tol && iy <= b.bottom + tol
                    val line = r.blocks.asSequence().flatMap { it.lines.asSequence() }.firstOrNull { hit(it.box) }
                    if (line == null) {
                        select(null)
                        return@detectTapGestures
                    }
                    // Palabra exacta o, si el toque cae entre dos, la más cercana en horizontal.
                    val word = line.words.firstOrNull { ix >= it.box.left && ix <= it.box.right }
                        ?: line.words.minByOrNull { w -> min(kotlin.math.abs(ix - w.box.left), kotlin.math.abs(ix - w.box.right)) }
                    select(Selection(line, word))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (rect.width <= 0f) return@Canvas
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            )
            val r = result ?: return@Canvas
            if (r.imageWidth <= 0 || r.imageHeight <= 0) return@Canvas
            val sx = rect.width / r.imageWidth
            val sy = rect.height / r.imageHeight
            val corner = CornerRadius(3f * density)
            fun tl(b: OcrRect) = Offset(rect.left + b.left * sx, rect.top + b.top * sy)
            fun sz(b: OcrRect) = Size((b.right - b.left) * sx, (b.bottom - b.top) * sy)
            val thin = Stroke(width = 1f * density)
            for (b in r.blocks) for (l in b.lines) {
                val isSel = selected != null && l.box == selected.line.box && l.text == selected.line.text
                drawRoundRect(
                    color = if (isSel) secondary.copy(alpha = 0.22f) else primary.copy(alpha = 0.12f),
                    topLeft = tl(l.box), size = sz(l.box), cornerRadius = corner,
                )
                drawRoundRect(
                    color = if (isSel) secondary.copy(alpha = 0.8f) else primary.copy(alpha = 0.5f),
                    topLeft = tl(l.box), size = sz(l.box), cornerRadius = corner,
                    style = thin,
                )
            }
            selected?.word?.let { w ->
                drawRoundRect(color = secondary.copy(alpha = 0.45f), topLeft = tl(w.box), size = sz(w.box), cornerRadius = corner)
                drawRoundRect(
                    color = secondary, topLeft = tl(w.box), size = sz(w.box), cornerRadius = corner,
                    style = Stroke(width = 2f * density),
                )
            }
        }
        if (result != null && result.blocks.isNotEmpty() && selected == null) {
            Text(
                "Toca una palabra para seleccionarla",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}


private fun fit(container: IntSize, w: Int, h: Int, pad: Float): Rect {
    if (container.width <= 0 || container.height <= 0 || w <= 0 || h <= 0) return Rect.Zero
    val s = min((container.width - 2 * pad) / w, (container.height - 2 * pad) / h)
    val dw = w * s; val dh = h * s
    val l = (container.width - dw) / 2f; val t = (container.height - dh) / 2f
    return Rect(l, t, l + dw, t + dh)
}

/** Cuenta palabras recorriendo los caracteres (sin Regex ni lista intermedia). */
private fun countWords(text: String): Int {
    var n = 0
    var inWord = false
    for (c in text) {
        if (c.isWhitespace()) inWord = false
        else if (!inWord) { inWord = true; n++ }
    }
    return n
}



/** Fila de la lectura con IA: "Leyendo con IA… 40 %" (cancelable), "En cola" o el botón "Volver a leer con IA". */
@Composable
private fun AiReadRow(
    status: AiRecognitionQueue.PageStatus?,
    readByAi: Boolean,
    enabled: Boolean,
    accent: Color,
    onRead: () -> Unit,
    onCancel: () -> Unit,
) {
    val ui = MaterialTheme.ui
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(GroupShape)
            .background(ui.card)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(AppIcons.Sparkles, accent)
        Spacer(Modifier.width(10.dp))
        when (status) {
            is AiRecognitionQueue.PageStatus.Running -> {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text("Leyendo con IA… ${status.percent} % · ${status.label}", style = MaterialTheme.typography.bodySmall, color = ui.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = accent, trackColor = ui.fill, drawStopIndicator = {})
                }
                SmallTextButton("Cancelar", onCancel)
            }
            AiRecognitionQueue.PageStatus.Queued -> {
                Text("En cola para leer con IA", style = MaterialTheme.typography.bodySmall, color = ui.label, modifier = Modifier.weight(1f))
                SmallTextButton("Cancelar", onCancel)
            }
            AiRecognitionQueue.PageStatus.WaitingNetwork -> {
                Text("Esperando conexión para leer con IA en la nube", style = MaterialTheme.typography.bodySmall, color = ui.label, modifier = Modifier.weight(1f))
                SmallTextButton("Cancelar", onCancel)
            }
            null -> {
                Text(
                    if (readByAi) "Texto leído con IA" else "Texto del OCR básico",
                    style = MaterialTheme.typography.bodySmall,
                    color = ui.secondaryLabel,
                    modifier = Modifier.weight(1f),
                )
                if (enabled) SmallTextButton(if (readByAi) "Volver a leer" else "Leer con IA", onRead)
            }
        }
    }
}
