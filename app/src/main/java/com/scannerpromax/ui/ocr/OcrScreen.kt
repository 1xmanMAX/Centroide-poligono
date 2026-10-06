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
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.LoadingOverlay
import com.scannerpromax.ui.components.SoftButton
import com.scannerpromax.ui.theme.brand
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
private data class Selection(val line: OcrLine, val word: OcrWord?)

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
    val brand = MaterialTheme.brand
    val primary = brand.gradientStart
    val secondary = brand.gradientEnd

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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AppTopBar(
                title = if (wholeDoc) "Texto del documento" else "Texto reconocido",
                subtitle = title,
                onBack = { guardEdits(onBack) },
                actions = {
                    if (!wholeDoc) {
                        IconButton(
                            enabled = !running && image != null,
                            onClick = {
                                if (result?.editedText != null || edited) confirmRecognize = true else recognize(currentPageId)
                            },
                        ) {
                            Icon(Icons.Filled.Refresh, "Volver a reconocer")
                        }
                    }
                },
            )

            // ------------------------------------------------ selector de página / todo el documento
            if (pages.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    item(key = "__all__") {
                        FilterChip(
                            selected = wholeDoc,
                            onClick = {
                                guardEdits {
                                    wholeDoc = true
                                    if (docText.isEmpty()) runWholeDocument()
                                }
                            },
                            label = { Text("Todo el documento") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(18.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primary.copy(alpha = 0.18f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                    itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                        FilterChip(
                            selected = !wholeDoc && p.id == currentPageId,
                            onClick = {
                                guardEdits {
                                    wholeDoc = false
                                    currentPageId = p.id
                                }
                            },
                            label = { Text("Pág. ${i + 1}") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primary.copy(alpha = 0.18f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }

            if (!wholeDoc) {
                // ------------------------------------------------ imagen con cajas de líneas / palabras
                Box(
                    Modifier
                        .weight(0.46f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) {
                    val img = image
                    if (img == null) {
                        CircularProgressIndicator(color = primary)
                    } else {
                        OcrImage(
                            image = img,
                            result = result,
                            selected = selected,
                            onSelect = { selected = it },
                            primary = primary,
                            secondary = secondary,
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = running,
                        enter = fadeIn(), exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    ) {
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(status, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(Modifier.width(180.dp).clip(RoundedCornerShape(50)), color = secondary)
                        }
                    }
                }

                // Palabra / línea seleccionada
                AnimatedVisibility(
                    visible = selected != null,
                    enter = slideInVertically { -it / 2 } + fadeIn(),
                    exit = slideOutVertically { -it / 2 } + fadeOut(),
                ) {
                    val sel = selected
                    Row(
                        Modifier
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.horizontalGradient(listOf(primary.copy(alpha = 0.18f), secondary.copy(alpha = 0.18f))))
                            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            sel?.word?.let {
                                Text(it.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                sel?.line?.text ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        sel?.word?.let { w ->
                            TextButton(onClick = { clipboard.setText(AnnotatedString(w.text)); toast("Palabra copiada") }) {
                                Text("Palabra", color = primary)
                            }
                        }
                        IconButton(onClick = {
                            sel?.let { clipboard.setText(AnnotatedString(it.line.text)); toast("Línea copiada") }
                        }) { Icon(Icons.Filled.ContentCopy, "Copiar línea", tint = primary) }
                        IconButton(onClick = { selected = null }) { Icon(Icons.Filled.Close, "Cerrar") }
                    }
                }
            } else {
                // ------------------------------------------------ progreso del documento completo
                AnimatedVisibility(visible = docProgress != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { docProgress ?: 0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                            color = secondary,
                        )
                        TextButton(onClick = { docJob?.cancel() }, modifier = Modifier.align(Alignment.End)) { Text("Cancelar") }
                    }
                }
            }

            // ------------------------------------------------ texto (editable por página)
            // Contador fuera del hilo principal y con espera mientras se escribe (sin Regex ni listas intermedias).
            // Falso positivo de lint: el valor sí se asigna (tras withContext).
            @SuppressLint("ProduceStateDoesNotAssignValue")
            val words by produceState(initialValue = 0, shownText) {
                if (!wholeDoc) delay(250)
                val n = withContext(Dispatchers.Default) { countWords(shownText) }
                value = n
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.TextFields, null, tint = primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        wholeDoc -> "Todas las páginas"
                        result?.editedText != null -> "Texto corregido"
                        else -> "Texto completo"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$words palabras · ${shownText.length} caracteres",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (wholeDoc) {
                // Documento completo: una entrada por página en una lista virtualizada (un único TextField con
                // 100 mil caracteres se maqueta y pinta entero en el hilo principal: jank o casi ANR en gama baja).
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    if (docPages.isEmpty()) {
                        Text(
                            if (docProgress != null) "Reconociendo el documento…" else "No se encontró texto.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    Text(pageText, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp))
                                }
                            }
                        }
                    }
                }
            } else OutlinedTextField(
                value = shownText,
                onValueChange = { if (!wholeDoc) text = it },
                readOnly = wholeDoc,
                placeholder = {
                    Text(
                        when {
                            wholeDoc && docProgress != null -> "Reconociendo el documento…"
                            running -> "Reconociendo…"
                            else -> "No se encontró texto. Prueba el filtro B/N o Mágico Pro y vuelve a reconocer."
                        },
                    )
                },
                supportingText = if (!wholeDoc && result != null) {
                    { Text(if (edited) "Cambios sin guardar" else "Puedes corregir el texto: se usará en el PDF buscable y en las exportaciones") }
                } else null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier
                    .weight(if (wholeDoc) 1f else 0.54f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )

            // ------------------------------------------------ acciones
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hasText = shownText.isNotBlank()
                OcrAction(Icons.Filled.ContentCopy, "Copiar", hasText, Modifier.weight(1f)) {
                    clipboard.setText(AnnotatedString(shownText))
                    toast("Texto copiado")
                }
                OcrAction(Icons.Filled.Share, "Compartir", hasText, Modifier.weight(1f)) { shareText() }
                OcrAction(Icons.AutoMirrored.Filled.TextSnippet, "Guardar .txt", hasText, Modifier.weight(1f)) { withStorage { saveTxt() } }
                OcrAction(Icons.Filled.PictureAsPdf, "PDF + texto", pages.isNotEmpty() && exportJob == null, Modifier.weight(1f)) {
                    guardEdits { askPdfAction = true }
                }
                if (!wholeDoc) {
                    if (edited) {
                        GradientButton("Guardar", onClick = { saveEdited() }, icon = Icons.Filled.Save, height = 52.dp, modifier = Modifier.weight(1.25f))
                    } else {
                        SoftButton("Guardar", onClick = {}, icon = Icons.Filled.Save, enabled = false, height = 52.dp, modifier = Modifier.weight(1.25f))
                    }
                }
            }
        }

        val ep = exportProgress
        LoadingOverlay(
            visible = ep != null,
            message = ep?.second ?: "",
            progress = ep?.first,
            onCancel = { exportJob?.cancel() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("¿Guardar el texto corregido?") },
            text = { Text("Has corregido el texto de esta página. Si no lo guardas, se perderán los cambios.") },
            confirmButton = {
                TextButton(onClick = { pendingAction = null; saveEdited(then = action) }) { Text("Guardar") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingAction = null
                        text = result?.displayText.orEmpty()
                        action()
                    }) { Text("Descartar", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { pendingAction = null }) { Text("Seguir editando") }
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
        )
    }

    if (confirmRecognize) {
        AlertDialog(
            onDismissRequest = { confirmRecognize = false },
            title = { Text("¿Volver a reconocer?") },
            text = { Text("Se descartarán tus correcciones de esta página y se reconocerá el texto de nuevo.") },
            confirmButton = {
                TextButton(onClick = { confirmRecognize = false; recognize(currentPageId) }) { Text("Reconocer") }
            },
            dismissButton = { TextButton(onClick = { confirmRecognize = false }) { Text("Cancelar") } },
            shape = MaterialTheme.shapes.extraLarge,
        )
    }

    if (askPdfAction) {
        AlertDialog(
            onDismissRequest = { askPdfAction = false },
            icon = { Icon(Icons.Filled.PictureAsPdf, null, tint = primary) },
            title = { Text("Exportar PDF con texto") },
            text = {
                Text(
                    "PDF buscable de todo el documento con páginas del texto reconocido" +
                        (if (pages.size > 1) " tras cada página." else ".") + " Se usarán tus correcciones.",
                )
            },
            confirmButton = {
                TextButton(onClick = { askPdfAction = false; withStorage { exportPdfWithText(PdfAction.SAVE) } }) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { askPdfAction = false; exportPdfWithText(PdfAction.SHARE) }) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Compartir")
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
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

@Composable
private fun OcrAction(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
