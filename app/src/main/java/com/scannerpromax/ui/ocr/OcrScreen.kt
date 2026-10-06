package com.scannerpromax.ui.ocr

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

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
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

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

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun recognize(file: File) {
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
                container.documents.saveOcr(docId, pageId, r)
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

    LaunchedEffect(docId, pageId) {
        running = true
        try {
            title = container.documents.get(docId)?.title ?: "Texto"
            val page = container.documents.getPage(docId, pageId) ?: throw IOException("Página no encontrada")
            status = "Preparando imagen…"
            val file = container.documents.processedFile(docId, page)
            processed = file
            val displayPx = if (container.deviceTier.isLowRam) 2_000_000 else 4_000_000
            val bmp = withContext(Dispatchers.Default) { BitmapIO.decode(file.absolutePath, displayPx) }
            displayBitmap = bmp
            image = bmp.asImageBitmap()
            val saved = container.documents.loadOcr(docId, pageId)
            if (saved != null) {
                result = saved
                text = saved.text
                running = false
            } else {
                running = false
                recognize(file)
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

    fun shareText() {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Compartir texto")) }
    }

    fun shareTxt() {
        if (text.isBlank()) return
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifBlank { "Texto" }.take(80)
                    File(dir, "$safe.txt").apply { writeText(text, Charsets.UTF_8) }
                }
                context.startActivity(container.imageExporter.shareChooser(listOf(file), "text/plain", "Guardar o compartir .txt"))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudo crear el archivo .txt")
            }
        }
    }

    fun saveEdited() {
        val r = result ?: return
        scope.launch {
            runCatching { container.documents.saveOcr(docId, pageId, r.copy(text = text)) }
                .onSuccess { result = r.copy(text = text); toast("Texto guardado") }
                .onFailure { toast("No se pudo guardar el texto") }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                Column(Modifier.weight(1f)) {
                    Text("Texto reconocido", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(enabled = !running && processed != null, onClick = { processed?.let { recognize(it) } }) {
                    Icon(Icons.Filled.Refresh, "Volver a reconocer")
                }
            }

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

            // ------------------------------------------------ texto editable
            val words = remember(text) { text.split(Regex("\\s+")).count { it.isNotBlank() } }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.TextFields, null, tint = primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Texto completo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "$words palabras · ${text.length} caracteres",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (running) "Reconociendo…" else "No se encontró texto. Prueba el filtro B/N o Mágico Pro y vuelve a reconocer.") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier
                    .weight(0.54f)
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
            ) {
                val hasText = text.isNotBlank()
                OcrAction(Icons.Filled.ContentCopy, "Copiar", hasText, Modifier.weight(1f)) {
                    clipboard.setText(AnnotatedString(text))
                    toast("Texto copiado")
                }
                OcrAction(Icons.Filled.Share, "Compartir", hasText, Modifier.weight(1f)) { shareText() }
                OcrAction(Icons.AutoMirrored.Filled.TextSnippet, ".TXT", hasText, Modifier.weight(1f)) { shareTxt() }
                val edited = result != null && text != result?.text
                Row(
                    Modifier
                        .weight(1.2f)
                        .height(52.dp)
                        .shadow(if (edited) 8.dp else 0.dp, RoundedCornerShape(16.dp), ambientColor = primary, spotColor = primary)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (edited) Brush.horizontalGradient(listOf(primary, secondary))
                            else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerHigh)),
                        )
                        .clickable(enabled = edited) { saveEdited() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = if (edited) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(Icons.Filled.Save, null, tint = fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Guardar", color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
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
