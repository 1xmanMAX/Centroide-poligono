package com.scannerpromax.ui.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Edit
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

/** Gestos de zoom: pellizco (hasta [ZoomState.maxScale]), arrastre y doble toque. */
internal fun Modifier.zoomGestures(state: ZoomState): Modifier = this
    .onSizeChanged { state.viewport = it; state.clamp() }
    .pointerInput(state) { detectTapGestures(onDoubleTap = { state.toggle(it) }) }
    .pointerInput(state) {
        detectTransformGestures { centroid, pan, zoom, _ -> state.transform(centroid, pan, zoom) }
    }

/**
 * Visor a pantalla completa de las páginas procesadas, con zoom hasta 5x.
 * La imagen base se decodifica a la resolución de la pantalla (inSampleSize, sin cargar los 12-20 MP); al ampliar
 * más allá de esa resolución se decodifica SOLO la zona visible a resolución completa con [BitmapRegionDecoder]
 * (memoria acotada a ~2 pantallas), así el texto pequeño se ve nítido a cualquier zoom.
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
            val file by produceState<File?>(null, page.id, page.processedFile, page.edits) {
                value = null
                value = withContext(Dispatchers.IO) {
                    runCatching { repo.processedFile(docId, page) }.getOrNull()
                        ?: repo.originalFile(docId, page).takeIf { it.exists() }
                }
            }
            val f = file
            if (f == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                ZoomableFileImage(f, Modifier.fillMaxSize())
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cerrar", tint = Color.White) }
                Text(
                    "Página ${index + 1} de ${pages.size}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEdit(page) }) {
                    Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Editar", color = Color.White)
                }
            }
            if (pages.size > 1) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    NavButton(Icons.Filled.ChevronLeft, "Página anterior", index > 0) { index-- }
                    NavButton(Icons.Filled.ChevronRight, "Página siguiente", index < pages.size - 1) { index++ }
                }
            }
        }
    }
}

@Composable
private fun NavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (enabled) 0.6f else 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
        }
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
internal fun ZoomableFileImage(file: File, modifier: Modifier = Modifier) {
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

        Canvas(Modifier.fillMaxSize().zoomGestures(zoom)) {
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
