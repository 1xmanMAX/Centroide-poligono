package com.scannerpromax.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.ScanMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

// =================================================================================================
// Punto de entrada: permiso de cámara
// =================================================================================================

@Composable
fun CameraScreen(
    container: AppContainer,
    docId: String?,
    mode: ScanMode,
    onFinished: (docId: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            val activity = context.findActivity()
            permanentlyDenied = activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        }
    }

    // Al volver de los Ajustes del sistema se vuelve a comprobar.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = context.hasCameraPermission()
    }

    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) {
            askedOnce = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (granted) {
        CameraContent(container, docId, mode, onFinished, onBack)
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

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PermissionScreen(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.85f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, primary.copy(alpha = 0.18f), MaterialTheme.colorScheme.background),
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        RoundIconButton(Icons.Filled.Close, "Cerrar", onBack, Modifier.padding(12.dp), dark = false)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(150.dp)
                        .graphicsLayer { scaleX = glow; scaleY = glow; alpha = 0.35f }
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(primary, Color.Transparent))),
                )
                Box(
                    Modifier
                        .size(104.dp)
                        .shadow(18.dp, CircleShape, ambientColor = primary, spotColor = primary)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(primary, secondary))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CameraAlt, null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                "Activa la cámara para escanear",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "ESCÁNER PRO MAX necesita la cámara para detectar los bordes del documento en tiempo real y capturarlo con la máxima calidad.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            PermissionBullet(Icons.Filled.AutoAwesome, "Detección automática de bordes y mejora mágica")
            PermissionBullet(Icons.Filled.Lock, "Todo se procesa en tu teléfono: nada se sube a internet")
            PermissionBullet(Icons.Filled.TouchApp, "Toca la pantalla para enfocar y usa el flash con poca luz")
            Spacer(Modifier.height(32.dp))
            GradientPillButton(
                text = if (permanentlyDenied) "Abrir ajustes" else "Permitir cámara",
                icon = Icons.Filled.CameraAlt,
                onClick = if (permanentlyDenied) onOpenSettings else onRequest,
                modifier = Modifier.fillMaxWidth(),
            )
            if (permanentlyDenied) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "El permiso fue denegado. Actívalo en Ajustes > Permisos > Cámara.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionBullet(icon: ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// =================================================================================================
// Cámara
// =================================================================================================

@Composable
private fun CameraContent(
    container: AppContainer,
    docId: String?,
    initialMode: ScanMode,
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val engine = remember { CameraEngine(context.applicationContext, container.pageProcessor.detector, container.deviceTier) }
    val processor = remember { CaptureProcessor(container) }
    // Ámbito propio con SupervisorJob: un fallo al procesar una página no cancela el resto.
    val workScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var flash by rememberSaveable { mutableStateOf(FlashSetting.OFF) }
    var currentDocId by rememberSaveable { mutableStateOf(docId) }
    val createdHere = docId == null
    val docMutex = remember { Mutex() }
    val jobs = remember { mutableListOf<Job>() }
    var pendingJobs by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var capturedThisSession by rememberSaveable { mutableIntStateOf(0) }
    var idFront by remember { mutableStateOf<Deferred<Result<Bitmap>>?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var stableProgress by remember { mutableFloatStateOf(0f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }
    val shutterFlash = remember { Animatable(0f) }
    val smoothed = rememberSmoothedQuad()

    val tooDark by engine.tooDark.collectAsState()
    val hasFlash by engine.hasFlash.collectAsState()
    val ready by engine.ready.collectAsState()
    val cameraError by engine.error.collectAsState()
    val doc by remember(currentDocId) {
        currentDocId?.let { container.documents.observe(it) } ?: flowOf<Document?>(null)
    }.collectAsState(initial = null)
    val pageCount = doc?.pages?.size ?: 0

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    DisposableEffect(engine) {
        engine.bind(lifecycleOwner, previewView, flash)
        onDispose {
            engine.release()
            workScope.cancel()
        }
    }
    LaunchedEffect(flash) { engine.setFlash(flash) }
    LaunchedEffect(mode) {
        engine.detectionEnabled = mode != ScanMode.PHOTO
        stableProgress = 0f
        if (mode != ScanMode.ID_CARD) idFront = null
    }

    suspend fun ensureDoc(m: ScanMode): String = docMutex.withLock {
        currentDocId ?: container.documents.create(m).id.also { currentDocId = it }
    }

    fun extraEdits(m: ScanMode) = PageEdits(
        quad = null,
        filter = settings.defaultFilter.takeIf { it != FilterType.ORIGINAL } ?: FilterType.MAGIC,
        autoRemoveLines = settings.autoRemoveLines,
        autoDeskew = m == ScanMode.BOOK,
    )

    /** Lanza un trabajo de procesamiento en segundo plano (no bloquea la cámara). */
    fun launchJob(block: suspend () -> Unit) {
        val job = workScope.launch {
            pendingJobs++
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                toast("No se pudo procesar: ${t.message ?: "error desconocido"}")
            } finally {
                pendingJobs--
            }
        }
        jobs += job
    }

    fun guideNormalized(): RectF? {
        if (viewSize.width <= 0 || viewSize.height <= 0) return null
        val vw = viewSize.width.toFloat(); val vh = viewSize.height.toFloat()
        val map = FrameMapping.fillCenter(vw, vh, smoothed.frameW, smoothed.frameH)
        return map.viewRectToNormalized(idCardGuideRect(vw, vh))
    }

    fun captureNow() {
        if (capturing || finishing || !ready) return
        capturing = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val m = mode
        val guide = if (m == ScanMode.ID_CARD) guideNormalized() else null
        workScope.launch {
            launch {
                shutterFlash.snapTo(0.8f)
                shutterFlash.animateTo(0f, tween(320))
            }
            val file = container.documents.newCaptureFile()
            try {
                engine.capture(file, previewView)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                capturing = false
                toast("No se pudo capturar la foto")
                return@launch
            }
            capturing = false
            capturedThisSession++
            engine.resetDetection()
            when (m) {
                ScanMode.BOOK -> launchJob { processor.addBook(ensureDoc(m), file, extraEdits(m)) }
                ScanMode.ID_CARD -> {
                    val front = idFront
                    if (front == null) {
                        idFront = workScope.async { runCatching { processor.cropCardSide(file, guide) } }
                    } else {
                        idFront = null
                        launchJob {
                            val f = front.await().getOrThrow()
                            var b: Bitmap? = null
                            try {
                                b = processor.cropCardSide(file, guide)
                                processor.addIdCard(ensureDoc(m), f, b, extraEdits(m))
                            } finally {
                                f.recycle(); b?.recycle()
                            }
                        }
                    }
                }
                else -> launchJob { processor.addStandard(ensureDoc(m), file) }
            }
        }
    }

    /** DNI: guardar solo el anverso. */
    fun skipBack() {
        val front = idFront ?: return
        idFront = null
        launchJob {
            val f = front.await().getOrThrow()
            try {
                processor.addIdCard(ensureDoc(ScanMode.ID_CARD), f, null, extraEdits(ScanMode.ID_CARD))
            } finally {
                f.recycle()
            }
        }
    }

    fun finish() {
        if (finishing) return
        finishing = true
        if (idFront != null) skipBack()
        workScope.launch {
            jobs.toList().joinAll()
            val id = currentDocId
            val hasPages = id != null && (container.documents.get(id)?.pages?.isNotEmpty() == true)
            finishing = false
            when {
                id != null && (hasPages || !createdHere) -> onFinished(id)
                id != null && createdHere -> {
                    runCatching { container.documents.delete(id) }
                    onBack()
                }
                else -> onBack()
            }
        }
    }

    fun discard() {
        val id = currentDocId
        val pending = jobs.toList()
        pending.forEach { it.cancel() }
        if (id != null && createdHere) {
            // Borrar cuando terminen de cancelarse los trabajos (fuera del ciclo de vida de la pantalla).
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                pending.joinAll()
                runCatching { container.documents.delete(id) }
            }
        }
        onBack()
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            capturedThisSession += uris.size
            val m = mode
            launchJob { container.documents.addPagesFromUris(ensureDoc(m), uris) }
        }
    }

    BackHandler {
        when {
            finishing -> Unit
            createdHere && (pageCount > 0 || pendingJobs > 0 || idFront != null) -> showDiscard = true
            !createdHere && capturedThisSession > 0 -> finish()
            else -> {
                if (createdHere && currentDocId != null) discard() else onBack()
            }
        }
    }

    // ---------------------------------------------------------------- autocaptura
    val autoCapture by rememberUpdatedState(settings.autoCapture)
    val modeState by rememberUpdatedState(mode)
    val busy by rememberUpdatedState(capturing || finishing || !ready)
    val framesNeeded = if (container.deviceTier.isLowRam || container.deviceTier.cores <= 4) 8 else 14
    LaunchedEffect(engine) {
        var prev: LiveDetection? = null
        var stable = 0
        var cooldownUntil = 0L
        engine.detection.collect { det ->
            smoothed.submit(det, System.currentTimeMillis())
            val now = SystemClock.elapsedRealtime()
            val eligible = det != null && autoCapture && modeState != ScanMode.PHOTO && !busy &&
                now >= cooldownUntil && det.confidence >= 0.55f && normalizedArea(det) >= 0.12f
            stable = if (!eligible) 0 else if (prev != null && maxCornerShift(prev!!, det!!) < 0.022f) stable + 1 else 1
            prev = det
            stableProgress = (stable.toFloat() / framesNeeded).coerceIn(0f, 1f)
            if (stable >= framesNeeded) {
                stable = 0
                stableProgress = 0f
                cooldownUntil = now + 2_500L
                captureNow()
            }
        }
    }

    // ---------------------------------------------------------------- UI
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        engine.focusAt(previewView, off.x, off.y)
                        focusPoint = off
                        focusKey++
                    }
                }
                .pointerInput(Unit) {
                    // Deslizar horizontalmente sobre la vista previa cambia de modo.
                    var total = 0f
                    val threshold = with(density) { 70.dp.toPx() }
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onHorizontalDrag = { change, amount -> total += amount; change.consume() },
                        onDragEnd = {
                            val modes = ScanMode.entries
                            val idx = modes.indexOf(modeState)
                            if (abs(total) > threshold) {
                                val next = if (total < 0) idx + 1 else idx - 1
                                if (next in modes.indices) mode = modes[next]
                            }
                        },
                    )
                },
        ) {
            DetectionOverlay(smoothed, mode, stableProgress, primary, secondary)
            FocusRing(focusPoint, focusKey, secondary)
        }

        if (shutterFlash.value > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = shutterFlash.value }.background(Color.White))
        }

        // ------------------------------------------------ barra superior
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.Filled.Close, "Cerrar", onClick = {
                when {
                    createdHere && (pageCount > 0 || pendingJobs > 0 || idFront != null) -> showDiscard = true
                    !createdHere && capturedThisSession > 0 -> finish()
                    else -> onBack()
                }
            })
            Spacer(Modifier.weight(1f))
            AutoCaptureToggle(
                enabled = settings.autoCapture,
                visible = mode != ScanMode.PHOTO,
                onToggle = {
                    val newValue = !settings.autoCapture
                    workScope.launch { container.settings.update { it.copy(autoCapture = newValue) } }
                    toast(if (newValue) "Captura automática activada" else "Captura automática desactivada")
                },
            )
            Spacer(Modifier.width(8.dp))
            if (hasFlash) {
                val icon = when (flash) {
                    FlashSetting.OFF -> Icons.Filled.FlashOff
                    FlashSetting.AUTO -> Icons.Filled.FlashAuto
                    FlashSetting.ON -> Icons.Filled.FlashOn
                    FlashSetting.TORCH -> Icons.Filled.FlashlightOn
                }
                RoundIconButton(
                    icon, flash.label,
                    highlighted = flash != FlashSetting.OFF,
                    onClick = {
                        flash = FlashSetting.entries[(flash.ordinal + 1) % FlashSetting.entries.size]
                        toast(flash.label)
                    },
                )
            }
        }

        // ------------------------------------------------ panel inferior
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f))))
                .navigationBarsPadding()
                .padding(bottom = 12.dp, top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hint = when {
                mode == ScanMode.ID_CARD && idFront != null -> "Paso 2 de 2 · Gira la tarjeta y encuadra el REVERSO"
                mode == ScanMode.ID_CARD -> "Paso 1 de 2 · Encuadra el ANVERSO dentro del marco"
                tooDark && hasFlash && flash == FlashSetting.OFF -> "Poca luz · toca aquí para encender la linterna"
                tooDark -> "Poca luz · busca una zona más iluminada"
                stableProgress > 0.05f -> "No te muevas… capturando"
                else -> modeHint(mode)
            }
            AnimatedContent(
                targetState = hint,
                transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(150)) },
                label = "hint",
            ) { text ->
                val isDarkHint = text.startsWith("Poca luz")
                HintPill(
                    text = text,
                    icon = if (isDarkHint) Icons.Filled.Lightbulb else null,
                    warning = isDarkHint,
                    onClick = if (isDarkHint && hasFlash) ({ flash = FlashSetting.TORCH }) else null,
                )
            }
            AnimatedVisibility(visible = mode == ScanMode.ID_CARD && idFront != null) {
                TextButton(onClick = { skipBack() }) {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Omitir reverso (solo una cara)", color = Color.White)
                }
            }
            Spacer(Modifier.height(10.dp))
            ModeSelector(selected = mode, onSelect = { mode = it })
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterStart) {
                    RoundIconButton(
                        Icons.Filled.PhotoLibrary, "Importar de la galería", size = 52.dp,
                        onClick = {
                            runCatching {
                                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }.onFailure { toast("No hay una galería disponible") }
                        },
                    )
                }
                ShutterButton(
                    enabled = ready && !capturing && !finishing,
                    progress = stableProgress,
                    busy = capturing,
                    onClick = { captureNow() },
                )
                Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterEnd) {
                    BatchThumbnail(
                        container = container,
                        doc = doc,
                        processing = pendingJobs > 0 || idFront != null,
                        onDone = { finish() },
                    )
                }
            }
        }

        if (cameraError != null) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.75f)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.CameraAlt, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(cameraError ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Puedes importar imágenes desde la galería.",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        AnimatedVisibility(
            visible = finishing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = primary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (pendingJobs > 0) "Mejorando $pendingJobs ${if (pendingJobs == 1) "captura" else "capturas"}…" else "Preparando documento…",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("¿Guardar el escaneo?") },
            text = { Text("Tienes páginas capturadas. Puedes guardarlas en un documento o descartarlas.") },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; finish() }) { Text("Guardar") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscard = false; discard() }) {
                        Text("Descartar", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showDiscard = false }) { Text("Seguir") }
                }
            },
        )
    }
}

private fun normalizedArea(det: LiveDetection): Float {
    val p = det.quad.points()
    var s = 0f
    for (i in p.indices) {
        val a = p[i]; val b = p[(i + 1) % p.size]
        s += a.x * b.y - b.x * a.y
    }
    val total = det.frameWidth.toFloat() * det.frameHeight
    return if (total <= 0f) 0f else abs(s) / 2f / total
}

/** Desplazamiento máximo de esquinas entre dos detecciones, relativo a la diagonal del frame. */
private fun maxCornerShift(a: LiveDetection, b: LiveDetection): Float {
    if (a.frameWidth != b.frameWidth || a.frameHeight != b.frameHeight) return 1f
    val diag = hypot(b.frameWidth.toFloat(), b.frameHeight.toFloat()).coerceAtLeast(1f)
    val pa = a.quad.points(); val pb = b.quad.points()
    var m = 0f
    for (i in 0 until 4) m = max(m, hypot(pa[i].x - pb[i].x, pa[i].y - pb[i].y))
    return m / diag
}

private fun modeHint(mode: ScanMode) = when (mode) {
    ScanMode.DOCUMENT -> "Encuadra el documento: los bordes se detectan solos"
    ScanMode.BOOK -> "Alinea el lomo del libro con la línea central"
    ScanMode.ID_CARD -> "Encuadra la tarjeta dentro del marco"
    ScanMode.RECEIPT -> "Acerca el recibo para leer bien la letra pequeña"
    ScanMode.WHITEBOARD -> "Colócate de frente a la pizarra"
    ScanMode.PHOTO -> "Foto a color, sin recorte automático"
}

private fun modeShortLabel(mode: ScanMode) = when (mode) {
    ScanMode.DOCUMENT -> "Documento"
    ScanMode.BOOK -> "Libro"
    ScanMode.ID_CARD -> "DNI"
    ScanMode.RECEIPT -> "Recibo"
    ScanMode.WHITEBOARD -> "Pizarra"
    ScanMode.PHOTO -> "Foto"
}

private fun modeIcon(mode: ScanMode): ImageVector = when (mode) {
    ScanMode.DOCUMENT -> Icons.Filled.Description
    ScanMode.BOOK -> Icons.AutoMirrored.Filled.MenuBook
    ScanMode.ID_CARD -> Icons.Filled.Badge
    ScanMode.RECEIPT -> Icons.AutoMirrored.Filled.ReceiptLong
    ScanMode.WHITEBOARD -> Icons.Filled.Slideshow
    ScanMode.PHOTO -> Icons.Filled.Photo
}

// =================================================================================================
// Componentes privados
// =================================================================================================

@Composable
private fun ModeSelector(selected: ScanMode, onSelect: (ScanMode) -> Unit) {
    val modes = ScanMode.entries
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        listState.animateScrollToItem(max(0, modes.indexOf(selected) - 1))
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(modes, key = { it.name }) { m ->
            ModePill(m, m == selected) { onSelect(m) }
        }
    }
}

@Composable
private fun ModePill(mode: ScanMode, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "pill")
    val textColor by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.78f), label = "pillText")
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(listOf(primary, secondary)))
                else Modifier.background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), shape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(modeIcon(mode), null, tint = textColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(modeShortLabel(mode), color = textColor, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, progress: Float, busy: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "shutter",
    )
    val animProgress by animateFloatAsState(progress, tween(180), label = "autoProgress")
    val inner by animateFloatAsState(if (busy) 0.55f else 1f, tween(160), label = "inner")
    Box(
        Modifier
            .size(86.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.6f }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val r = size.minDimension / 2f - stroke
            drawCircle(
                brush = Brush.sweepGradient(listOf(primary, secondary, primary)),
                radius = r,
                style = Stroke(width = stroke),
            )
            if (animProgress > 0.001f) {
                val pr = r
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * animProgress,
                    useCenter = false,
                    topLeft = Offset(center.x - pr, center.y - pr),
                    size = Size(pr * 2, pr * 2),
                    style = Stroke(width = stroke * 1.6f, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier
                .size(66.dp)
                .graphicsLayer { scaleX = inner; scaleY = inner }
                .clip(CircleShape)
                .background(Color.White),
        )
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = primary, strokeWidth = 3.dp)
    }
}

@Composable
private fun BatchThumbnail(container: AppContainer, doc: Document?, processing: Boolean, onDone: () -> Unit) {
    val last = doc?.pages?.lastOrNull()
    val count = doc?.pages?.size ?: 0
    val visible = count > 0 || processing
    AnimatedVisibility(visible = visible, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                        .background(Color.DarkGray)
                        .clickable(onClick = onDone),
                    contentAlignment = Alignment.Center,
                ) {
                    if (last != null && doc != null) {
                        val file: File = container.documents.thumbFile(doc.id, last) ?: container.documents.originalFile(doc.id, last)
                        AsyncImage(
                            model = file,
                            contentDescription = "Última página",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (processing) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
                        }
                    }
                }
                if (count > 0) {
                    Box(
                        Modifier
                            .padding(0.dp)
                            .graphicsLayer { translationX = 14f; translationY = -14f }
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .clickable(onClick = onDone)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Listo", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun HintPill(text: String, icon: ImageVector?, warning: Boolean, onClick: (() -> Unit)?) {
    val bg = if (warning) Color(0xFFFFB547).copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.55f)
    val fg = if (warning) Color.Black else Color.White
    Row(
        Modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AutoCaptureToggle(enabled: Boolean, visible: Boolean, onToggle: () -> Unit) {
    if (!visible) return
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (enabled) Modifier.background(Brush.horizontalGradient(listOf(primary, secondary)))
                else Modifier.background(Color.Black.copy(alpha = 0.45f)),
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (enabled) "Auto" else "Manual", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    highlighted: Boolean = false,
    dark: Boolean = true,
) {
    val bg = when {
        highlighted -> MaterialTheme.colorScheme.primary
        dark -> Color.Black.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (dark || highlighted) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun GradientPillButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Row(
        modifier
            .height(56.dp)
            .shadow(12.dp, RoundedCornerShape(50), ambientColor = primary, spotColor = primary)
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(primary, secondary)))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
