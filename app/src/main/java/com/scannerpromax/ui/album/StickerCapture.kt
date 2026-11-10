package com.scannerpromax.ui.album

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scannerpromax.album.AlbumGeometry
import com.scannerpromax.album.AlbumOps
import com.scannerpromax.album.AlbumSlot
import com.scannerpromax.album.StickerMaker
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.ui.camera.CameraEngine
import com.scannerpromax.ui.camera.CaptureProcessor
import com.scannerpromax.ui.camera.FlashSetting
import com.scannerpromax.ui.camera.FrameMapping
import com.scannerpromax.ui.camera.PermissionScreen
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.editor.CropTool
import com.scannerpromax.ui.editor.EditorBackground
import com.scannerpromax.ui.editor.isConvexQuad
import com.scannerpromax.ui.theme.ForcedDarkTheme
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Proporción (ancho/alto) con la que se ve el recuadro: si está girado ~90° se usa la inversa. */
private fun visualRatio(slot: AlbumSlot, wMm: Double, hMm: Double): Double {
    val b = AlbumGeometry.box(slot, wMm, hMm)
    val turned = abs(abs(AlbumGeometry.normalizeAngle(b.angle)) - 90.0) < 45.0
    return if (turned) b.h / b.w else b.w / b.h
}

/**
 * Cámara para «pegar» un cromo en su recuadro: guía con la proporción del recuadro y el contorno detectado en vivo.
 * Tras la foto, la pantalla de recorte ([StickerCropPanel]) con la detección automática (color de tarjeta + detector
 * de documentos) y las esquinas arrastrables; «Pegar» lo deja en el recuadro.
 */
@Composable
internal fun AlbumCameraScreen(container: AppContainer, albumId: String, pageId: String, slotId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            val a = context.findActivity()
            permanentlyDenied = a != null && !a.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) { if (!granted && !asked) { asked = true; launcher.launch(Manifest.permission.CAMERA) } }
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (activity != null && previous != null) activity.requestedOrientation = previous }
    }
    if (granted) {
        CameraContent(container, albumId, pageId, slotId, onBack)
    } else {
        PermissionScreen(
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
            onBack = onBack,
        )
    }
}

@Composable
private fun CameraContent(container: AppContainer, albumId: String, pageId: String, slotId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember { CameraEngine(context.applicationContext, container.pageProcessor.detector, container.deviceTier) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var ratio by remember { mutableStateOf(1.5) }
    var slotLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(albumId, pageId, slotId) {
        val page = container.albums.get(albumId)?.pages?.firstOrNull { it.id == pageId }
        val slot = page?.slots?.firstOrNull { it.id == slotId }
        if (page == null || slot == null) { onBack(); return@LaunchedEffect }
        ratio = visualRatio(slot, page.widthMm.toDouble(), page.heightMm.toDouble())
        slotLabel = slot.label
    }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<Pair<String, RectF?>?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val detection by engine.detection.collectAsState()

    DisposableEffect(engine) {
        engine.bind(lifecycleOwner, previewView, FlashSetting.OFF)
        container.documents.pauseBackgroundOcr()
        onDispose {
            container.documents.resumeBackgroundOcr()
            engine.release()
        }
    }
    LaunchedEffect(Unit) {
        container.settings.settings.collect { s -> engine.lensCorrection = s.lensCorrection }
    }
    LaunchedEffect(torch) { engine.setFlash(if (torch) FlashSetting.TORCH else FlashSetting.OFF) }
    LaunchedEffect(captured) { engine.detectionEnabled = captured == null }

    fun guideRect(): Rect {
        val vw = viewSize.width.toFloat(); val vh = viewSize.height.toFloat()
        var w = vw * 0.84f
        var h = (w / ratio).toFloat()
        val maxH = vh * 0.62f
        if (h > maxH) { h = maxH; w = (h * ratio).toFloat() }
        val cx = vw / 2f; val cy = vh * 0.45f
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    fun shoot() {
        if (capturing || viewSize.width == 0) return
        capturing = true
        scope.launch {
            val tmp = container.documents.newCaptureFile()
            try {
                engine.capture(tmp, previewView)
                val (dst, rel) = container.albums.newFile(albumId, "src", slotId, "jpg")
                withContext(Dispatchers.IO) { if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() } }
                // la guía en coordenadas normalizadas de la foto (la vista previa recorta la foto como FILL_CENTER)
                val (pw, ph) = withContext(Dispatchers.IO) { BitmapIO.decodeBounds(dst.absolutePath) }
                val guide = if (pw > 0 && ph > 0) {
                    FrameMapping.fillCenter(viewSize.width.toFloat(), viewSize.height.toFloat(), pw.toFloat(), ph.toFloat()).viewRectToNormalized(guideRect())
                } else null
                captured = rel to guide
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Toast.makeText(context, "No se pudo hacer la foto", Toast.LENGTH_SHORT).show()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { tmp.delete() }
                capturing = false
            }
        }
    }

    val cap = captured
    BackHandler(enabled = cap == null) { onBack() }
    ForcedDarkTheme {
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                if (viewSize.width == 0) return@Canvas
                val g = guideRect()
                // oscurece fuera de la guía
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(g, androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())))
                }
                drawPath(path, Color.Black.copy(alpha = 0.45f))
                drawRoundRect(Color.White, g.topLeft, g.size, androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()), style = Stroke(2.dp.toPx()))
                detection?.let { d ->
                    if (d.confidence > 0.3f && d.frameWidth > 0 && d.frameHeight > 0) {
                        val map = FrameMapping.fillCenter(size.width, size.height, d.frameWidth.toFloat(), d.frameHeight.toFloat())
                        val pts = d.quad.points().map { map.toView(it.x / d.frameWidth, it.y / d.frameHeight) }
                        val q = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            for (k in 1..3) lineTo(pts[k].x, pts[k].y)
                            close()
                        }
                        drawPath(q, Color(0xFF34C759).copy(alpha = 0.18f))
                        drawPath(q, Color(0xFF34C759), style = Stroke(3.dp.toPx()))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CircleIcon(AppIcons.Close, "Cerrar", onBack)
                    Spacer(Modifier.weight(1f))
                    CircleIcon(AppIcons.Bolt, if (torch) "Apagar linterna" else "Linterna", { torch = !torch }, active = torch)
                }
                Text(
                    (slotLabel?.let { "$it · " } ?: "") + "Encuadra el cromo dentro del marco",
                    color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)) {
                Box(
                    Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                        .background(if (capturing) Color.White.copy(alpha = 0.5f) else Color.White)
                        .clickable(enabled = !capturing) { shoot() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
                }
            }
            // tras la foto: recorte encima de la cámara (la vista previa sigue enlazada para «Repetir»)
            if (cap != null) {
                StickerCropPanel(
                    container, albumId, pageId, slotId, cap.first, isNew = true, guide = cap.second,
                    onCancel = {
                        val rel = cap.first
                        captured = null
                        scope.launch(Dispatchers.IO) { runCatching { container.albums.file(albumId, rel).delete() } }
                    },
                    cancelLabel = "Repetir",
                    onDone = onBack,
                )
            }
        }
    }
}

@Composable
private fun CircleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, active: Boolean = false) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) Color(0xFFFFD60A) else Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * Recorte del cromo: foto con el contorno detectado (o el guardado) y sus 4 esquinas arrastrables, mejora (color
 * mejorado, original, documento, B/N) y «Pegar». [isNew] = foto nueva ([source] recién guardada): se detecta; si no,
 * se edita el recorte del cromo ya pegado. Al pegar se guarda el cromo en el álbum y se llama a [onDone].
 */
@Composable
internal fun StickerCropPanel(
    container: AppContainer,
    albumId: String,
    pageId: String,
    slotId: String,
    source: String?,
    isNew: Boolean,
    guide: RectF?,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    cancelLabel: String = "Cancelar",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = remember { CaptureProcessor(container) }
    val maker = container.stickerMaker
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val image: ImageBitmap? = remember(preview) { preview?.takeIf { !it.isRecycled }?.asImageBitmap() }
    var quad by remember { mutableStateOf<Quad?>(null) }
    var src by remember { mutableStateOf(source) }
    var filterIndex by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }

    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_SHORT).show()

    LaunchedEffect(albumId, slotId, source) {
        try {
            if (isNew && source != null) {
                val d = processor.detectCardSide(container.albums.file(albumId, source), guide, withPreview = true)
                val p = d.preview ?: throw java.io.IOException("Sin vista previa")
                preview = p
                quad = CaptureProcessor.denormalize(d.quad, p.width, p.height)
                if (d.confidence <= 0f) toast("No se encontraron los bordes: ajusta las esquinas")
            } else {
                val slot = container.albums.get(albumId)?.pages?.firstOrNull { it.id == pageId }?.slots?.firstOrNull { it.id == slotId }
                val st = slot?.sticker ?: throw java.io.IOException("El recuadro no tiene foto")
                src = st.source
                filterIndex = maker.filters.indexOf(st.filter).coerceAtLeast(0)
                manual = st.manualQuad
                val p = withContext(Dispatchers.IO) { BitmapIO.decodeThumbnail(container.albums.file(albumId, st.source).absolutePath, 1600) }
                preview = p
                quad = CaptureProcessor.denormalize(st.quad, p.width, p.height)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            toast("No se pudo abrir la foto")
            onCancel()
        }
    }
    DisposableEffect(Unit) { onDispose { preview?.recycle() } }

    fun autoDetect() {
        val s = src ?: return
        val p = preview ?: return
        if (detecting) return
        detecting = true
        scope.launch {
            try {
                val d = processor.detectCardSide(container.albums.file(albumId, s), null, withPreview = false)
                if (d.confidence <= 0f) toast("No se detectaron los bordes: ajusta las esquinas a mano")
                else { quad = CaptureProcessor.denormalize(d.quad, p.width, p.height); manual = false }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("No se pudo detectar")
            } finally {
                detecting = false
            }
        }
    }

    fun paste() {
        val s = src ?: return
        val p = preview ?: return
        val q = quad ?: return
        if (saving) return
        if (!isConvexQuad(q.points().map { Offset(it.x, it.y) })) { toast("Las esquinas se cruzan: ajústalas"); return }
        saving = true
        scope.launch {
            try {
                val album = container.albums.get(albumId) ?: throw java.io.IOException("El álbum ya no existe")
                val page = album.pages.firstOrNull { it.id == pageId } ?: throw java.io.IOException("La hoja ya no existe")
                val slot = page.slots.firstOrNull { it.id == slotId } ?: throw java.io.IOException("El recuadro ya no existe")
                val box = AlbumGeometry.box(slot, page)
                val qn = CaptureProcessor.normalize(q, p.width, p.height)
                val base = slot.sticker?.takeIf { !isNew }
                var st = maker.make(albumId, slotId, s, qn, maker.filters[filterIndex], box.w to box.h, base, manual)
                if (isNew) {
                    // foto nueva: si su orientación no coincide con la del recuadro, se gira 90° para llenarlo
                    val slotLandscape = box.w >= box.h
                    val stLandscape = st.renderedW >= st.renderedH
                    st = st.copy(rotation = if (slotLandscape != stLandscape && abs(box.w / box.h - 1.0) > 0.08) 90 else 0, fit = slot.sticker?.fit ?: st.fit)
                }
                container.albums.updateQueued(albumId) { a ->
                    a.copy(pages = AlbumOps.updateSlot(a.pages, pageId, slotId) { it.copy(sticker = st) })
                }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast(t.message ?: "No se pudo pegar el cromo")
            } finally {
                saving = false
            }
        }
    }

    BackHandler(enabled = !saving) { onCancel() }
    ForcedDarkTheme {
        Box(Modifier.fillMaxSize().background(EditorBackground)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Text(
                    "Ajusta las esquinas del cromo",
                    color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    val q = quad
                    if (image == null || q == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("Buscando los bordes…", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        }
                    } else {
                        CropTool(
                            image = image,
                            quad = q,
                            onQuadChange = { quad = it; manual = true },
                            primary = MaterialTheme.ui.accent,
                            secondary = MaterialTheme.ui.accent,
                            error = MaterialTheme.ui.red,
                        )
                    }
                }
                SegmentedControl(
                    options = maker.filters.map { StickerMaker.filterLabel(it) },
                    selectedIndex = filterIndex,
                    onSelect = { filterIndex = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryCapsuleButton(cancelLabel, onClick = { if (!saving) onCancel() }, tonal = true, modifier = Modifier.weight(1f))
                    PrimaryCapsuleButton("Auto", onClick = { autoDetect() }, tonal = true, loading = detecting, enabled = image != null && !saving, modifier = Modifier.weight(1f))
                    PrimaryCapsuleButton("Pegar", onClick = { paste() }, loading = saving, enabled = image != null, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Recortar de nuevo un cromo ya pegado, o recortar una imagen nueva de la galería ([source] != null). */
@Composable
internal fun StickerCropScreen(container: AppContainer, albumId: String, pageId: String, slotId: String, source: String?, onBack: () -> Unit) {
    StickerCropPanel(
        container, albumId, pageId, slotId, source, isNew = source != null, guide = null,
        onCancel = {
            if (source != null) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { container.albums.file(albumId, source).delete() } }
            onBack()
        },
        onDone = onBack,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
