package com.scannerpromax.ui.ocr

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.ui.components.AppTopBar
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.SoftButton
import com.scannerpromax.ui.theme.brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

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
    var processed by remember { mutableStateOf<File?>(null) }
    var result by remember { mutableStateOf<OcrResult?>(null) }
    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Preparando imagen…") }
    var selected by remember { mutableStateOf<OcrLine?>(null) }
    var ocrJob by remember { mutableStateOf<Job?>(null) }
    var title by remember { mutableStateOf("Texto") }
    var docText by remember { mutableStateOf("") }
    var docProgress by remember { mutableStateOf<Float?>(null) }
    var docJob by remember { mutableStateOf<Job?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val edited = !wholeDoc && result != null && text != result?.text
    val shownText = if (wholeDoc) docText else text

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun recognize(file: File, forPage: String) {
        ocrJob?.cancel()
        ocrJob = scope.launch {
            running = true
            status = "Reconociendo texto…"
            selected = null
            try {
                val maxPx = if (container.deviceTier.isLowRam) 6_000_000 else container.deviceTier.maxWorkingPixels
                val bmp = withContext(Dispatchers.Default) { BitmapIO.decode(file.absolutePath, maxPx) }
                val r = try {
                    container.ocr.recognize(bmp)
                } finally {
                    bmp.recycle()
                }
                container.documents.saveOcr(docId, forPage, r)
                result = r
                text = r.text
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
            processed = file
            val displayPx = if (container.deviceTier.isLowRam) 2_000_000 else 4_000_000
            val bmp = withContext(Dispatchers.Default) { BitmapIO.decode(file.absolutePath, displayPx) }
            // La imagen anterior se libera al sustituirla (cambio de página).
            val old = displayBitmap
            displayBitmap = bmp
            image = bmp.asImageBitmap()
            old?.recycle()
            val saved = container.documents.loadOcr(docId, currentPageId)
            if (saved != null) {
                result = saved
                text = saved.text
                running = false
            } else {
                running = false
                recognize(file, currentPageId)
            }
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

    /** Reconoce (o carga el OCR guardado de) todas las páginas y las concatena con separadores. */
    fun runWholeDocument() {
        if (docJob != null) return
        docJob = scope.launch {
            docProgress = 0f
            try {
                val list = container.documents.get(docId)?.pages.orEmpty()
                val sb = StringBuilder()
                list.forEachIndexed { i, p ->
                    status = "Reconociendo página ${i + 1} de ${list.size}…"
                    val r = try {
                        container.documents.loadOcr(docId, p.id) ?: container.documents.ensureOcr(docId, p.id)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        null
                    }
                    if (list.size > 1) sb.append("— Página ${i + 1} —\n")
                    sb.append(r?.text?.trim().orEmpty().ifEmpty { "(sin texto)" })
                    if (i < list.size - 1) sb.append("\n\n")
                    docProgress = (i + 1f) / list.size.coerceAtLeast(1)
                }
                docText = sb.toString()
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

    fun shareTxt() {
        if (shownText.isBlank()) return
        val content = shownText
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifBlank { "Texto" }.take(80)
                    File(dir, "$safe.txt").apply { writeText(content, Charsets.UTF_8) }
                }
                context.startActivity(container.imageExporter.shareChooser(listOf(file), "text/plain", "Guardar o compartir .txt"))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudo crear el archivo .txt")
            }
        }
    }

    fun saveEdited(then: (() -> Unit)? = null) {
        val r = result ?: return
        val forPage = currentPageId
        val newText = text
        scope.launch {
            runCatching { container.documents.saveOcr(docId, forPage, r.copy(text = newText)) }
                .onSuccess {
                    result = r.copy(text = newText)
                    // La capa buscable del PDF se coloca línea a línea con el reconocimiento original.
                    toast("Texto guardado (la búsqueda en el PDF usa el reconocimiento original)")
                    then?.invoke()
                }
                .onFailure { toast("No se pudo guardar el texto") }
        }
    }

    /** Ejecuta [action] o, si hay texto corregido sin guardar, pregunta antes. */
    fun guardEdits(action: () -> Unit) {
        if (edited) pendingAction = action else action()
    }

    BackHandler(enabled = edited) { guardEdits(onBack) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AppTopBar(
                title = if (wholeDoc) "Texto del documento" else "Texto reconocido",
                subtitle = title,
                onBack = { guardEdits(onBack) },
                actions = {
                    if (!wholeDoc) {
                        IconButton(enabled = !running && processed != null, onClick = { processed?.let { recognize(it, currentPageId) } }) {
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
                // ------------------------------------------------ imagen con cajas de líneas
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

                // Línea seleccionada
                AnimatedVisibility(
                    visible = selected != null,
                    enter = slideInVertically { -it / 2 } + fadeIn(),
                    exit = slideOutVertically { -it / 2 } + fadeOut(),
                ) {
                    val line = selected
                    Row(
                        Modifier
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.horizontalGradient(listOf(primary.copy(alpha = 0.18f), secondary.copy(alpha = 0.18f))))
                            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            line?.text ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = {
                            line?.let { clipboard.setText(AnnotatedString(it.text)); toast("Línea copiada") }
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
            val words = remember(shownText) { shownText.split(Regex("\\s+")).count { it.isNotBlank() } }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.TextFields, null, tint = primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (wholeDoc) "Todas las páginas" else "Texto completo",
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
            OutlinedTextField(
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hasText = shownText.isNotBlank()
                OcrAction(Icons.Filled.ContentCopy, "Copiar", hasText, Modifier.weight(1f)) {
                    clipboard.setText(AnnotatedString(shownText))
                    toast("Texto copiado")
                }
                OcrAction(Icons.Filled.Share, "Compartir", hasText, Modifier.weight(1f)) { shareText() }
                OcrAction(Icons.AutoMirrored.Filled.TextSnippet, ".TXT", hasText, Modifier.weight(1f)) { shareTxt() }
                if (!wholeDoc) {
                    if (edited) {
                        GradientButton("Guardar", onClick = { saveEdited() }, icon = Icons.Filled.Save, height = 52.dp, modifier = Modifier.weight(1.3f))
                    } else {
                        SoftButton("Guardar", onClick = {}, icon = Icons.Filled.Save, enabled = false, height = 52.dp, modifier = Modifier.weight(1.3f))
                    }
                }
            }
        }
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
                        text = result?.text.orEmpty()
                        action()
                    }) { Text("Descartar", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { pendingAction = null }) { Text("Seguir editando") }
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

/** Imagen procesada con las líneas reconocidas resaltadas; tocar una línea la selecciona. */
@Composable
private fun OcrImage(
    image: ImageBitmap,
    result: OcrResult?,
    selected: OcrLine?,
    onSelect: (OcrLine?) -> Unit,
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
                    val tol = 6f / s
                    val hit = r.blocks.asSequence().flatMap { it.lines.asSequence() }.firstOrNull { l ->
                        ix >= l.box.left - tol && ix <= l.box.right + tol && iy >= l.box.top - tol && iy <= l.box.bottom + tol
                    }
                    select(hit)
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
            for (b in r.blocks) for (l in b.lines) {
                val tl = Offset(rect.left + l.box.left * sx, rect.top + l.box.top * sy)
                val sz = Size((l.box.right - l.box.left) * sx, (l.box.bottom - l.box.top) * sy)
                val isSel = selected != null && l.box == selected.box && l.text == selected.text
                drawRoundRect(
                    color = if (isSel) secondary.copy(alpha = 0.38f) else primary.copy(alpha = 0.14f),
                    topLeft = tl, size = sz, cornerRadius = corner,
                )
                drawRoundRect(
                    color = if (isSel) secondary else primary.copy(alpha = 0.55f),
                    topLeft = tl, size = sz, cornerRadius = corner,
                    style = Stroke(width = (if (isSel) 2f else 1f) * density),
                )
            }
        }
        if (result != null && result.blocks.isNotEmpty() && selected == null) {
            Text(
                "Toca una línea para seleccionarla",
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
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun fit(container: IntSize, w: Int, h: Int, pad: Float): Rect {
    if (container.width <= 0 || container.height <= 0 || w <= 0 || h <= 0) return Rect.Zero
    val s = min((container.width - 2 * pad) / w, (container.height - 2 * pad) / h)
    val dw = w * s; val dh = h * s
    val l = (container.width - dw) / 2f; val t = (container.height - dh) / 2f
    return Rect(l, t, l + dw, t + dh)
}
