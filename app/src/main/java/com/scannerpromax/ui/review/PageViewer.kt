package com.scannerpromax.ui.review

import androidx.compose.material3.MaterialTheme
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.ui
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import com.scannerpromax.ocr.LiveTextMapping
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Page
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.pdf.DocLayoutFiles
import com.scannerpromax.pdf.PdfReconstructor
import com.scannerpromax.pdf.binaryHintFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Estado de zoom/desplazamiento compartido por el visor de páginas y la vista previa del editor.
 * [offset] = desplazamiento del centro del contenido respecto al centro de la vista (px); [content] = tamaño del
 * contenido ajustado a la vista (escala 1).
 */
@Stable
internal class ZoomState(val maxScale: Float = 5f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    /** Tamaño de la vista y del contenido ajustado (no son estado observable: solo los usan los gestos). */
    var viewport: IntSize = IntSize.Zero
        internal set
    var content: Size = Size.Zero

    val zoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** Pellizco/arrastre: el punto bajo [centroid] queda fijo mientras cambia la escala. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val old = scale
        val new = (old * zoom).coerceIn(1f, maxScale)
        val c = Offset(viewport.width / 2f, viewport.height / 2f)
        val d = centroid - c - offset
        offset = centroid - c - d * (new / old) + pan
        scale = new
        clamp()
    }

    /** Doble toque: amplía 2.5x sobre el punto tocado, o vuelve a ajustar. */
    fun toggle(at: Offset) {
        if (zoomed) reset() else transform(at, Offset.Zero, 2.5f)
    }

    fun clamp() {
        val cw = (if (content.width > 0f) content.width else viewport.width.toFloat()) * scale
        val ch = (if (content.height > 0f) content.height else viewport.height.toFloat()) * scale
        val mx = max(0f, (cw - viewport.width) / 2f)
        val my = max(0f, (ch - viewport.height) / 2f)
        offset = Offset(offset.x.coerceIn(-mx, mx), offset.y.coerceIn(-my, my))
    }
}

/** Gestos de zoom: pellizco (hasta [ZoomState.maxScale]), arrastre y doble toque. [onTap] = toque simple (opcional). */
internal fun Modifier.zoomGestures(state: ZoomState, onTap: ((Offset) -> Unit)? = null): Modifier = this
    .onSizeChanged { state.viewport = it; state.clamp() }
    .pointerInput(state, onTap != null) { detectTapGestures(onDoubleTap = { state.toggle(it) }, onTap = onTap) }
    .pointerInput(state) {
        detectTransformGestures { centroid, pan, zoom, _ -> state.transform(centroid, pan, zoom) }
    }

/**
 * Visor a pantalla completa de las páginas procesadas, con zoom hasta 5x.
 * La imagen base se decodifica a la resolución de la pantalla (inSampleSize, sin cargar los 12-20 MP); al ampliar
 * más allá de esa resolución se decodifica SOLO la zona visible a resolución completa con [BitmapRegionDecoder]
 * (memoria acotada a ~2 pantallas), así el texto pequeño se ve nítido a cualquier zoom.
 *
 * "Texto en vivo": mantener pulsado sobre el texto selecciona la palabra (con dos tiradores y el menú Copiar ·
 * Seleccionar todo · Compartir). Las cajas son las del OCR guardado de la página; si aún no tiene, se reconoce al
 * abrirla ("Reconociendo texto…").
 *
 * Cromo estilo Fotos de iOS: arriba ✕ · (Reconstruido) · Editar; abajo un único paginador "‹ 2 / 10 ›".
 */
@Composable
internal fun PageViewerDialog(
    container: AppContainer,
    docId: String,
    pages: List<Page>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onEdit: (Page) -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, max(0, pages.size - 1))) }
    val page = pages.getOrNull(index)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (page == null) {
                LaunchedEffect(Unit) { onDismiss() }
                return@Box
            }
            val repo = container.documents
            // El procesado ACTUAL tal cual (la misma imagen que la miniatura y que se exporta): sin reprocesar. Solo si
            // aún no existe (página recién capturada en cola, o archivo perdido) se genera, con aviso.
            var rendering by remember(page.id) { mutableStateOf(false) }
            val file by produceState<File?>(null, page.id, page.processedFile, page.edits) {
                val ready = withContext(Dispatchers.IO) { repo.processedFileIfReady(docId, page) }
                if (ready != null) {
                    value = ready
                    return@produceState
                }
                value = null
                rendering = true
                try {
                    value = runCatching { repo.processedFile(docId, page) }.getOrNull()
                        ?: withContext(Dispatchers.IO) { repo.originalFile(docId, page).takeIf { it.exists() } }
                } finally {
                    rendering = false
                }
            }
            val f = file

            // ---- Texto en vivo: OCR guardado de la página (o reconocerla ahora si aún no lo tiene).
            val context = LocalContext.current
            val clipboard = LocalClipboardManager.current
            val live = remember(page.id, f?.path) { LiveTextState() }
            LaunchedEffect(live, page.ocrFile) {
                if (f == null) return@LaunchedEffect
                val saved = repo.loadOcr(docId, page.id)
                if (saved != null) {
                    if (live.result != saved) live.load(saved)
                    return@LaunchedEffect
                }
                live.status = LiveTextStatus.LOADING
                val r = try {
                    repo.ensureOcr(docId, page.id)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    null
                }
                if (r != null) live.load(r) else live.status = LiveTextStatus.FAILED
            }
            BackHandler(enabled = live.range != null) { live.range = null }
            // Pista "Mantén pulsado el texto…" unos segundos la primera vez que hay texto en esta apertura del visor.
            var hintShown by remember { mutableStateOf(false) }
            var showHint by remember { mutableStateOf(false) }
            LaunchedEffect(live.status) {
                if (live.status == LiveTextStatus.READY && !hintShown) {
                    hintShown = true
                    showHint = true
                    delay(3500)
                    showHint = false
                }
            }
            fun copy(text: String) {
                if (text.isBlank()) return
                clipboard.setText(AnnotatedString(text))
                // Android 13+ ya muestra su propia confirmación al copiar.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Texto copiado", Toast.LENGTH_SHORT).show()
                live.range = null
            }
            fun share(text: String) {
                if (text.isBlank()) return
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching { context.startActivity(Intent.createChooser(intent, "Compartir texto")) }
                live.range = null
            }

            // "Ver reconstruido": la página que daría el PDF reconstruido (DocLayout de la IA) para compararla con el
            // escaneo. Solo si la página está reconocida con IA.
            val appContext = LocalContext.current.applicationContext
            val reconstructor = remember { PdfReconstructor(appContext) }
            var showRebuilt by remember { mutableStateOf(false) }
            val hasLayout by produceState(false, f) {
                value = f != null && withContext(Dispatchers.IO) { DocLayoutFiles.exists(f) }
            }
            var rebuildError by remember(f, showRebuilt) { mutableStateOf<String?>(null) }
            val rebuilt by produceState<File?>(null, f, showRebuilt, hasLayout) {
                value = null
                val src = f ?: return@produceState
                if (!showRebuilt || !hasLayout) return@produceState
                value = runCatching {
                    withContext(Dispatchers.IO) {
                        val layoutFile = DocLayoutFiles.fileFor(src)
                        val out = File(appContext.cacheDir, "reconstruido/${src.nameWithoutExtension}_${layoutFile.lastModified()}.png")
                        if (out.isFile && out.length() > 0L) return@withContext out
                        out.parentFile?.listFiles()?.filter { it.name.startsWith(src.nameWithoutExtension + "_") }?.forEach { it.delete() }
                        val (w, h) = com.scannerpromax.export.ImageCodec.bounds(src)
                        val layout = DocLayoutFiles.read(src, w, h) ?: throw java.io.IOException("La maquetación de la IA es de otra versión de la página")
                        reconstructor.renderPreview(layout, src, binaryHintFor(page.edits.filter), out)
                    }
                }.onFailure { rebuildError = it.message ?: "No se pudo reconstruir la página" }.getOrNull()
            }
            if (showRebuilt && hasLayout && f != null) {
                val r = rebuilt
                if (r != null) {
                    androidx.compose.runtime.key(r.path) { ZoomableFileImage(r, Modifier.fillMaxSize().background(Color.White)) }
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (rebuildError == null) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Text("Reconstruyendo la página…", color = Color.White)
                        } else {
                            Text(rebuildError ?: "", color = Color.White, modifier = Modifier.padding(24.dp))
                        }
                    }
                }
            } else if (f == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    if (rendering) {
                        Spacer(Modifier.height(12.dp))
                        Text("Procesando la página…", color = Color.White)
                    }
                }
            } else {
                // key: al cambiar el archivo (página editada) se rehace el estado de zoom y la decodificación.
                androidx.compose.runtime.key(f.path) {
                    ZoomableFileImage(
                        f,
                        Modifier.fillMaxSize(),
                        live = live,
                        onCopy = ::copy,
                        onShare = ::share,
                        onLongPressWithoutText = {
                            val msg = when (live.status) {
                                LiveTextStatus.LOADING, LiveTextStatus.IDLE -> "Reconociendo texto…"
                                LiveTextStatus.NONE -> "No se encontró texto en esta página"
                                else -> "No se pudo reconocer el texto"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }

            ViewerTopBar(
                rebuiltLabel = if (hasLayout) (if (showRebuilt) "Escaneo" else "Reconstruido") else null,
                onToggleRebuilt = { showRebuilt = !showRebuilt },
                onClose = onDismiss,
                onEdit = { onEdit(page) },
            )
            val hintText = when {
                showRebuilt || live.range != null -> null
                live.status == LiveTextStatus.LOADING -> "Reconociendo texto…"
                showHint -> "Mantén pulsado el texto para seleccionarlo"
                else -> null
            }
            if (hintText != null) {
                LiveTextHint(
                    hintText,
                    loading = live.status == LiveTextStatus.LOADING,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = if (pages.size > 1) ViewerHintAbovePager else 22.dp),
                )
            }
            if (pages.size > 1) {
                ViewerPager(
                    index = index,
                    total = pages.size,
                    onPrev = { index-- },
                    onNext = { index++ },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
                )
            }
        }
    }
}

/** Distancia desde abajo de la pista del visor cuando hay paginador (18 dp + 52 dp del paginador + 14 dp de aire). */
internal val ViewerHintAbovePager = 84.dp

/**
 * Barra superior del visor sobre un degradado (estilo Fotos de iOS): ✕ a la izquierda y, a la derecha,
 * "Reconstruido"/"Escaneo" (solo si la página tiene maquetación de la IA) y "Editar". El número de página va solo
 * abajo, en el paginador.
 */
@Composable
internal fun ViewerTopBar(
    rebuiltLabel: String?,
    onToggleRebuilt: () -> Unit,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircleButton(
            AppIcons.Close, "Cerrar", onClose,
            size = 36.dp, iconSize = 16.dp, containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White, elevation = 0.dp,
        )
        Spacer(Modifier.weight(1f))
        if (rebuiltLabel != null) {
            ViewerTextButton(rebuiltLabel, onToggleRebuilt)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            "Editar",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .clip(PillShape)
                .background(MaterialTheme.ui.accent)
                .clickable(role = Role.Button, onClick = onEdit)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Paginador inferior del visor: ‹ "2 / 10" ›. */
@Composable
internal fun ViewerPager(index: Int, total: Int, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(PillShape)
            .background(Color(0xE61C1C1E))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavButton(AppIcons.Back, "Página anterior", index > 0, onPrev)
        Text(
            "${index + 1} / $total",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        NavButton(AppIcons.Chevron, "Página siguiente", index < total - 1, onNext)
    }
}

@Composable
private fun ViewerTextButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 15.sp,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun NavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
    }
}

/** Imagen base (a resolución de pantalla) + dimensiones reales del archivo. */
private class BaseImage(val image: ImageBitmap, val fullW: Int, val fullH: Int, val regionOk: Boolean)

/** Zona visible decodificada a alta resolución (coordenadas de la imagen completa). */
private class Detail(val image: ImageBitmap, val rect: Rect)

/**
 * Imagen de un archivo con zoom nítido. Ver [PageViewerDialog]. El archivo procesado no lleva rotación EXIF; si
 * algún archivo la tuviera se muestra con [BitmapIO] (EXIF aplicado) y sin decodificación por regiones.
 */
@Composable
internal fun ZoomableFileImage(
    file: File,
    modifier: Modifier = Modifier,
    live: LiveTextState? = null,
    onCopy: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onLongPressWithoutText: () -> Unit = {},
) {
    val zoom = remember(file) { ZoomState(maxScale = 5f) }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(0.dp))) {
        val density = LocalDensity.current
        val vw = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val vh = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)

        val base by produceState<BaseImage?>(null, file, vw, vh) {
            value = withContext(Dispatchers.IO) { runCatching { decodeBase(file, vw, vh) }.onFailure { Log.w(TAG, "No se pudo abrir $file", it) }.getOrNull() }
        }
        val decoderLock = remember(file) { Mutex() }
        val decoder = remember(file) { arrayOfNulls<BitmapRegionDecoder>(1) }
        DisposableEffect(file) {
            onDispose { runCatching { decoder[0]?.recycle() }; decoder[0] = null }
        }
        var detail by remember(file) { mutableStateOf<Detail?>(null) }

        val b = base
        if (b == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            return@BoxWithConstraints
        }
        val fit = min(vw.toFloat() / b.fullW, vh.toFloat() / b.fullH)
        zoom.content = Size(b.fullW * fit, b.fullH * fit)

        // Al dejar de mover: si el zoom pide más detalle que la imagen base, decodificar la zona visible.
        LaunchedEffect(b, vw, vh) {
            snapshotFlow { zoom.scale to zoom.offset }.collectLatest { (s, off) ->
                val ds = fit * s // píxeles de pantalla por píxel de imagen
                val baseRes = b.image.width.toFloat() / b.fullW
                if (!b.regionOk || ds <= baseRes * 1.15f) {
                    detail = null
                    return@collectLatest
                }
                delay(140)
                val left = vw / 2f + off.x - b.fullW * ds / 2f
                val top = vh / 2f + off.y - b.fullH * ds / 2f
                val x0 = floor(max(0f, -left / ds)).toInt()
                val y0 = floor(max(0f, -top / ds)).toInt()
                val x1 = min(b.fullW.toFloat(), (vw - left) / ds).roundToInt()
                val y1 = min(b.fullH.toFloat(), (vh - top) / ds).roundToInt()
                if (x1 - x0 < 2 || y1 - y0 < 2) return@collectLatest
                val cur = detail
                var sample = 1
                while (sample * 2 <= 1f / ds) sample *= 2
                if (cur != null && cur.rect.left <= x0 && cur.rect.top <= y0 && cur.rect.right >= x1 && cur.rect.bottom >= y1 &&
                    cur.image.width >= (cur.rect.width() / sample) * 0.95f
                ) return@collectLatest
                // Margen de 1/4 de pantalla alrededor: pequeños arrastres no obligan a redecodificar.
                val mx = ((x1 - x0) / 4); val my = ((y1 - y0) / 4)
                val r = Rect(max(0, x0 - mx), max(0, y0 - my), min(b.fullW, x1 + mx), min(b.fullH, y1 + my))
                val bmp = withContext(Dispatchers.IO) {
                    decoderLock.withLock {
                        try {
                            val dec = decoder[0] ?: newRegionDecoder(file)?.also { decoder[0] = it } ?: return@withLock null
                            dec.decodeRegion(r, BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            })
                        } catch (t: Throwable) {
                            Log.w(TAG, "No se pudo decodificar la región", t)
                            null
                        }
                    }
                } ?: return@collectLatest
                detail = Detail(bmp.asImageBitmap(), r)
            }
        }

        // Texto en vivo: coordenadas OCR <-> vista con el zoom/desplazamiento ACTUAL (se leen al usarlas).
        val geometry = rememberUpdatedState(Triple(vw, vh, b))
        val mapping: () -> LiveTextMapping? = remember(zoom, live) {
            {
                val r = live?.result
                val (w, h, bb) = geometry.value
                if (r == null || r.imageWidth <= 0 || r.imageHeight <= 0) null
                else LiveTextMapping(
                    w.toFloat(), h.toFloat(), bb.fullW.toFloat(), bb.fullH.toFloat(),
                    r.imageWidth.toFloat(), r.imageHeight.toFloat(), zoom.scale, zoom.offset.x, zoom.offset.y,
                )
            }
        }
        val haptics = LocalHapticFeedback.current
        val gestures = if (live == null) Modifier.zoomGestures(zoom) else Modifier
            .zoomGestures(zoom, onTap = { live.range = null })
            .liveTextGestures(
                live,
                mapping,
                onLongPressWithoutText = onLongPressWithoutText,
                onSelected = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            )

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val ds = fit * zoom.scale
            val w = b.fullW * ds; val h = b.fullH * ds
            val left = size.width / 2f + zoom.offset.x - w / 2f
            val top = size.height / 2f + zoom.offset.y - h / 2f
            drawImage(
                b.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(b.image.width, b.image.height),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.High,
            )
            val d = detail
            if (d != null && zoom.scale > 1.01f) {
                val dl = left + d.rect.left * ds
                val dt = top + d.rect.top * ds
                drawImage(
                    d.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(d.image.width, d.image.height),
                    dstOffset = IntOffset(dl.roundToInt(), dt.roundToInt()),
                    dstSize = IntSize((d.rect.width() * ds).roundToInt().coerceAtLeast(1), (d.rect.height() * ds).roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        if (live != null) LiveTextLayer(live, mapping, onCopy, onShare)
    }
}

/**
 * Decodifica la base con inSampleSize (potencia de 2) de forma que mida AL MENOS lo que ocupa en pantalla
 * (entre 1x y 2x la pantalla): nítida a 1x y hasta ~2x de zoom sin regiones. Con mipmaps (reducción sin aliasing).
 */
private fun decodeBase(file: File, vw: Int, vh: Int): BaseImage {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, o)
    val w = o.outWidth; val h = o.outHeight
    require(w > 0 && h > 0) { "Imagen ilegible" }
    val orientation = try {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }
    if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
        val bmp = BitmapIO.decode(file.absolutePath, vw * vh * 4)
        bmp.setHasMipMap(true)
        return BaseImage(bmp.asImageBitmap(), bmp.width, bmp.height, regionOk = false)
    }
    val fit = min(vw.toFloat() / w, vh.toFloat() / h)
    var sample = 1
    while (fit * sample * 2 <= 1f && (w / (sample * 2)) * (h / (sample * 2)) > 0) sample *= 2
    var bmp: Bitmap? = null
    var s = sample
    while (bmp == null) {
        try {
            bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = s
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw IllegalStateException("No se pudo decodificar")
        } catch (oom: OutOfMemoryError) {
            if (s >= 64) throw oom
            s *= 2
        }
    }
    bmp.setHasMipMap(true)
    return BaseImage(bmp.asImageBitmap(), w, h, regionOk = true)
}

@Suppress("DEPRECATION")
private fun newRegionDecoder(file: File): BitmapRegionDecoder? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(file.absolutePath)
    else BitmapRegionDecoder.newInstance(file.absolutePath, false)
} catch (t: Throwable) {
    Log.w(TAG, "Sin decodificación por regiones para $file", t)
    null
}

private const val TAG = "PageViewer"
