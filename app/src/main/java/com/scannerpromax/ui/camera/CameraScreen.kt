package com.scannerpromax.ui.camera

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.filled.BlurOn
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
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStarted
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.scannerpromax.data.AppSettings
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.Cv
import com.scannerpromax.imaging.HeavyWork
import com.scannerpromax.imaging.MultiFrameFusion
import com.scannerpromax.ui.components.GradientButton
import com.scannerpromax.ui.components.LoadingOverlay
import com.scannerpromax.ui.theme.brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Ámbito de PROCESO para el procesamiento de capturas: si la pantalla se destruye (recreación de la
 * actividad, salir con trabajos pendientes) las fotos ya hechas terminan de procesarse y se guardan en
 * su documento en vez de perderse. Solo "Descartar" cancela estos trabajos de forma explícita.
 */
private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    // La cámara trabaja en vertical (como todos los escáneres): la vista previa, la guía del DNI y el
    // cuadrilátero se calculan para esa orientación. Se restaura la orientación al salir.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (activity != null && previous != null) activity.requestedOrientation = previous
        }
    }

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
    val brand = MaterialTheme.brand
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.85f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(brand.backdrop)
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
                        .background(Brush.radialGradient(listOf(brand.gradientStart, Color.Transparent))),
                )
                Box(
                    Modifier
                        .size(104.dp)
                        .shadow(18.dp, CircleShape, ambientColor = brand.glow, spotColor = brand.glow)
                        .clip(CircleShape)
                        .background(brand.gradient),
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
            GradientButton(
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

/** Alto reservado para los controles bajo la vista previa (modos + obturador). */
private val CONTROLS_HEIGHT = 178.dp

/**
 * Modo poca luz / anti-reflejos (ráfaga + fusión multi-cuadro). AUTO: se activa solo cuando el análisis en vivo
 * detecta oscuridad o reflejos.
 */
private enum class LowLightSetting(val label: String) {
    AUTO("Poca luz / anti-reflejos: automático"),
    ON("Poca luz / anti-reflejos: activado"),
    OFF("Poca luz / anti-reflejos: desactivado"),
}

@Composable
private fun CameraContent(
    container: AppContainer,
    docId: String?,
    initialMode: ScanMode,
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val brand = MaterialTheme.brand
    val gradStart = brand.gradientStart
    val gradEnd = brand.gradientEnd

    val settingsFlow = remember(container) { container.settings.settings }
    val settings by settingsFlow.collectAsState(initial = AppSettings())
    val engine = remember { CameraEngine(context.applicationContext, container.pageProcessor.detector, container.deviceTier) }
    val processor = remember { CaptureProcessor(container) }
    // Ámbito de la pantalla (animaciones, captura). El procesamiento va en [captureScope].
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
    var lowLight by rememberSaveable { mutableStateOf(LowLightSetting.AUTO) }
    /** Ráfaga en curso: fotos hechas / total (0 = sin ráfaga). */
    var burstShot by remember { mutableIntStateOf(0) }
    var burstTotal by remember { mutableIntStateOf(0) }
    var burstForGlare by remember { mutableStateOf(false) }
    var focusKey by remember { mutableIntStateOf(0) }
    val shutterFlash = remember { Animatable(0f) }
    val smoothed = rememberSmoothedQuad()

    val quality by engine.quality.collectAsState()
    val tooDark = quality.tooDark
    val hasFlash by engine.hasFlash.collectAsState()
    val ready by engine.ready.collectAsState()
    val cameraError by engine.error.collectAsState()
    val doc by remember(currentDocId) {
        currentDocId?.let { container.documents.observe(it) } ?: flowOf<Document?>(null)
    }.collectAsState(initial = null)
    val pageCount = doc?.pages?.size ?: 0

    fun toast(msg: String) = Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()

    /** Recicla el bitmap de un anverso pendiente que ya no se va a usar. */
    fun recycleFront(d: Deferred<Result<Bitmap>>?) {
        d ?: return
        captureScope.launch { runCatching { d.await().getOrNull()?.recycle() } }
    }

    DisposableEffect(engine) {
        engine.bind(lifecycleOwner, previewView, flash)
        // Gama baja: menos hilos de OpenCV con la cámara abierta. Se aplica desde el hilo de baja prioridad para
        // que los hilos del pool (si se recrean) hereden esa prioridad.
        val tier = container.deviceTier
        HeavyWork.post { Cv.setCameraActive(true, tier) }
        // Sin OCR de fondo con la cámara abierta: vista previa, análisis y ráfagas tienen la CPU.
        container.documents.pauseBackgroundOcr()
        onDispose {
            container.documents.resumeBackgroundOcr()
            HeavyWork.post { Cv.setCameraActive(false, tier) }
            engine.release()
            workScope.cancel()
            // Los trabajos de procesamiento NO se cancelan: terminan en segundo plano y guardan sus páginas.
            recycleFront(idFront)
            idFront = null
        }
    }
    LaunchedEffect(flash) { engine.setFlash(flash) }
    // Con capturas mejorándose en segundo plano, el análisis en vivo cede CPU (la UI sigue fluida en gama baja).
    LaunchedEffect(pendingJobs) { engine.backgroundBusy = pendingJobs > 0 }
    LaunchedEffect(mode) {
        engine.detectionEnabled = mode != ScanMode.PHOTO
        stableProgress = 0f
    }

    suspend fun ensureDoc(m: ScanMode): String = docMutex.withLock {
        currentDocId ?: container.documents.create(m).id.also { currentDocId = it }
    }

    fun extraEdits(m: ScanMode) = CaptureProcessor.composedEdits(m, settings)
    fun cardFilter(): FilterType? = CaptureProcessor.cardFilter(settings)

    /** Lanza un trabajo de procesamiento en segundo plano (no bloquea la cámara y sobrevive a la pantalla). */
    fun launchJob(block: suspend () -> Unit) {
        val job = captureScope.launch {
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
        job.invokeOnCompletion { captureScope.launch { jobs.remove(job) } }
    }

    fun guideNormalized(): RectF? {
        if (viewSize.width <= 0 || viewSize.height <= 0) return null
        val vw = viewSize.width.toFloat(); val vh = viewSize.height.toFloat()
        val map = FrameMapping.fillCenter(vw, vh, smoothed.frameW, smoothed.frameH)
        return map.viewRectToNormalized(idCardGuideRect(vw, vh))
    }

    /** Centro del cuadrilátero suavizado en coordenadas de la vista (para enfocar antes de la autocaptura). */
    fun quadCenterInView(): Offset? {
        if (viewSize.width <= 0 || viewSize.height <= 0 || smoothed.alpha < 0.3f) return null
        val map = FrameMapping.fillCenter(viewSize.width.toFloat(), viewSize.height.toFloat(), smoothed.frameW, smoothed.frameH)
        val p = smoothed.points
        val cx = (p[0] + p[2] + p[4] + p[6]) / 4f
        val cy = (p[1] + p[3] + p[5] + p[7]) / 4f
        return map.toView(cx, cy)
    }

    /** ¿Va a disparar el flash en la próxima foto? (ON siempre; AUTO, con poca luz). */
    fun flashWillFire(): Boolean = hasFlash && (flash == FlashSetting.ON || (flash == FlashSetting.AUTO && quality.tooDark))

    /**
     * ¿Usar ráfaga + fusión? Manual (activado/desactivado) o automático si hay poca luz o reflejos.
     * Nunca si el flash va a disparar: cada foto llevaría su predisparo (1-2 s en gama baja) y el reflejo del
     * flash está fijo respecto a la cámara, así que el anti-reflejos no puede quitarlo. Con la linterna
     * encendida tampoco se usa el anti-reflejos (mismo motivo).
     */
    fun burstWanted(): Boolean {
        if (flashWillFire()) return false
        val torchGlareOnly = hasFlash && flash == FlashSetting.TORCH && !quality.tooDark
        return when (lowLight) {
            LowLightSetting.ON -> !torchGlareOnly || quality.tooDark
            LowLightSetting.OFF -> false
            LowLightSetting.AUTO -> quality.tooDark || (quality.glare && mode != ScanMode.PHOTO && !torchGlareOnly)
        }
    }

    fun captureNow(auto: Boolean = false) {
        if (capturing || finishing || !ready) return
        capturing = true
        val m = mode
        val guide = if (m == ScanMode.ID_CARD) guideNormalized() else null
        val focusTarget = if (auto) quadCenterInView() else null
        val burst = burstWanted()
        val glareMode = quality.glare && !quality.tooDark
        workScope.launch {
            // Autocaptura: enfocar en el documento y esperar al AF (las cámaras baratas "cazan" el foco).
            if (focusTarget != null) engine.focusAndWait(previewView, focusTarget.x, focusTarget.y)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            launch {
                shutterFlash.snapTo(0.8f)
                shutterFlash.animateTo(0f, tween(320))
            }
            val count = if (burst) MultiFrameFusion.recommendedFrameCount(container.deviceTier) else 1
            val files = List(count) { container.documents.newCaptureFile() }
            val shots: List<File> = try {
                if (count == 1) {
                    engine.capture(files[0], previewView)
                    files
                } else {
                    burstForGlare = glareMode
                    burstShot = 0
                    burstTotal = count
                    engine.captureBurst(files, previewView) { n -> burstShot = n }
                }
            } catch (c: CancellationException) {
                files.forEach { it.delete() }
                burstTotal = 0
                throw c
            } catch (t: Throwable) {
                files.forEach { it.delete() }
                capturing = false
                burstTotal = 0
                toast("No se pudo capturar la foto")
                return@launch
            }
            burstTotal = 0
            capturing = false
            capturedThisSession++
            engine.resetDetection()
            // La fusión (si hay ráfaga) corre en el trabajo de fondo, antes del flujo normal de cada modo.
            suspend fun shotFile(): File = processor.mergeBurst(shots, removeGlare = true)
            when (m) {
                ScanMode.BOOK -> launchJob { processor.addBook(ensureDoc(m), shotFile(), extraEdits(m)) }
                ScanMode.ID_CARD -> {
                    val front = idFront
                    val filter = cardFilter()
                    if (front == null) {
                        idFront = captureScope.async { runCatching { processor.cropCardSide(shotFile(), guide, filter) } }
                    } else {
                        idFront = null
                        launchJob {
                            val f = front.await().getOrThrow()
                            var b: Bitmap? = null
                            try {
                                b = processor.cropCardSide(shotFile(), guide, filter)
                                processor.addIdCard(ensureDoc(m), f, b, extraEdits(m))
                            } finally {
                                f.recycle(); b?.recycle()
                            }
                        }
                    }
                }
                else -> launchJob { processor.addStandard(ensureDoc(m), shotFile(), m) }
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

    /** Cambia de modo. Si hay un anverso de DNI pendiente, se guarda como una sola cara (no se pierde). */
    fun changeMode(m: ScanMode) {
        if (m == mode) return
        if (idFront != null) {
            skipBack()
            toast("Anverso guardado como tarjeta de una cara")
        }
        mode = m
    }

    fun finish() {
        if (finishing) return
        finishing = true
        if (idFront != null) skipBack()
        workScope.launch {
            jobs.toList().joinAll()
            val id = currentDocId
            val hasPages = id != null && (container.documents.get(id)?.pages?.isNotEmpty() == true)
            if (id != null && !hasPages && createdHere) runCatching { container.documents.delete(id) }
            // Si la app pasó a segundo plano mientras se procesaba, se espera a volver antes de navegar
            // (antes la navegación se descartaba y la cámara quedaba abierta con el documento a medias).
            lifecycleOwner.lifecycle.withStarted {
                finishing = false
                if (id != null && (hasPages || !createdHere)) onFinished(id) else onBack()
            }
        }
    }

    fun discard() {
        val id = currentDocId
        val pending = jobs.toList()
        pending.forEach { it.cancel() }
        recycleFront(idFront)
        idFront = null
        if (id != null && createdHere) {
            // Borrar cuando terminen de cancelarse los trabajos (fuera del ciclo de vida de la pantalla).
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                pending.joinAll()
                runCatching { container.documents.delete(id) }
            }
        }
        onBack()
    }

    /** Lógica única de cierre (botón Cerrar y gesto/botón Atrás del sistema). */
    fun requestClose() {
        when {
            finishing -> Unit
            createdHere && (pageCount > 0 || pendingJobs > 0 || idFront != null) -> showDiscard = true
            !createdHere && (capturedThisSession > 0 || pendingJobs > 0) -> finish()
            createdHere && currentDocId != null -> discard() // documento vacío (p. ej. falló la 1.ª captura)
            else -> onBack()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        capturedThisSession += uris.size
        val m = mode
        when (m) {
            // Libro: cada foto de la galería se divide en dos páginas, igual que con la cámara.
            ScanMode.BOOK -> uris.forEach { uri ->
                launchJob { processor.addBook(ensureDoc(m), processor.copyToCaptureFile(uri), extraEdits(m)) }
            }
            // DNI: las imágenes se toman de dos en dos (anverso + reverso); si sobra una, va sola.
            ScanMode.ID_CARD -> {
                if (idFront != null) skipBack()
                val filter = cardFilter()
                uris.chunked(2).forEach { pair ->
                    launchJob {
                        var f: Bitmap? = null
                        var b: Bitmap? = null
                        try {
                            f = processor.cropCardSide(processor.copyToCaptureFile(pair[0]), null, filter)
                            b = pair.getOrNull(1)?.let { processor.cropCardSide(processor.copyToCaptureFile(it), null, filter) }
                            processor.addIdCard(ensureDoc(m), f, b, extraEdits(m))
                        } finally {
                            f?.recycle(); b?.recycle()
                        }
                    }
                }
            }
            else -> launchJob { container.documents.addPagesFromUris(ensureDoc(m), uris, mode = m) }
        }
    }

    BackHandler { requestClose() }

    // ---------------------------------------------------------------- autocaptura
    val autoCapture by rememberUpdatedState(settings.autoCapture)
    val modeState by rememberUpdatedState(mode)
    val busy by rememberUpdatedState(capturing || finishing || !ready)
    val blurryState by rememberUpdatedState(quality.blurry)
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
            // Con imagen borrosa no se completa la cuenta: se espera a que el usuario sujete firme.
            if (blurryState && stable >= framesNeeded - 1) stable = framesNeeded - 1
            stableProgress = (stable.toFloat() / framesNeeded).coerceIn(0f, 1f)
            if (stable >= framesNeeded) {
                stable = 0
                stableProgress = 0f
                cooldownUntil = now + 2_500L
                captureNow(auto = true)
            }
        }
    }

    // ---------------------------------------------------------------- UI
    // stableProgress cambia en cada detección (5-7 Hz): el cuerpo sólo lee este booleano derivado, así la
    // pantalla entera recompone únicamente cuando cambia el texto del aviso (no varias veces por segundo).
    val holdingStill by remember { derivedStateOf { stableProgress > 0.05f } }
    val hint = when {
        burstTotal > 0 && burstForGlare -> "Anti-reflejos · foto ${max(1, burstShot)} de $burstTotal · inclina un poco el móvil"
        burstTotal > 0 -> "Poca luz · foto ${max(1, burstShot)} de $burstTotal · no te muevas"
        mode == ScanMode.ID_CARD && idFront != null -> "Paso 2 de 2 · Gira la tarjeta y encuadra el REVERSO"
        mode == ScanMode.ID_CARD -> "Paso 1 de 2 · Encuadra el ANVERSO dentro del marco"
        tooDark && lowLight != LowLightSetting.OFF && !flashWillFire() -> "Poca luz · se tomarán varias fotos y se fusionarán"
        tooDark && hasFlash && flash == FlashSetting.OFF -> "Poca luz · toca aquí para encender la linterna"
        tooDark -> "Poca luz · busca una zona más iluminada"
        mode != ScanMode.PHOTO && quality.blurry && holdingStill -> "Imagen borrosa · sujeta firme el móvil"
        mode != ScanMode.PHOTO && quality.glare && lowLight != LowLightSetting.OFF && burstWanted() -> "Reflejo · se corregirá con varias fotos (anti-reflejos)"
        mode != ScanMode.PHOTO && quality.glare -> "Reflejo · inclina un poco el móvil"
        holdingStill -> "No te muevas… capturando"
        else -> modeHint(mode)
    }

    val hintArea: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                targetState = hint,
                transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(150)) },
                label = "hint",
            ) { text ->
                val isDarkHint = text.startsWith("Poca luz") && burstTotal == 0
                val isWarn = isDarkHint || text.startsWith("Imagen borrosa") || text.startsWith("Reflejo")
                HintPill(
                    text = text,
                    icon = when {
                        isDarkHint -> Icons.Filled.Lightbulb
                        text.startsWith("Imagen borrosa") -> Icons.Filled.BlurOn
                        text.startsWith("Reflejo") -> Icons.Filled.WbSunny
                        else -> null
                    },
                    warning = isWarn,
                    onClick = if (isDarkHint && hasFlash) ({ flash = FlashSetting.TORCH }) else null,
                )
            }
            AnimatedVisibility(visible = mode == ScanMode.ID_CARD && idFront != null) {
                TextButton(onClick = { skipBack() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Omitir reverso (solo una cara)", color = Color.White)
                }
            }
        }
    }

    val controls: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ModeSelector(selected = mode, onSelect = { changeMode(it) })
            Spacer(Modifier.height(14.dp))
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
                    progress = { stableProgress },
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
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Vista previa 3:4 (lo que se ve = lo que se captura) arriba y los controles en la franja de abajo.
        // Si la pantalla es demasiado baja, la vista previa ocupa todo y los controles van superpuestos.
        val availableH = maxHeight - topInset - bottomInset - CONTROLS_HEIGHT
        val stacked = availableH >= maxWidth * 0.95f
        val previewH: Dp = if (stacked) min(maxWidth * 4f / 3f, availableH) else maxHeight
        val previewW: Dp = if (stacked) min(maxWidth, previewH * 3f / 4f) else maxWidth

        Column(Modifier.fillMaxSize()) {
            if (stacked) Spacer(Modifier.height(topInset))
            Box(Modifier.fillMaxWidth().height(previewH), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier
                        .width(previewW)
                        .height(previewH)
                        .clip(if (stacked) RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp) else RoundedCornerShape(0.dp)),
                ) {
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
                                // Deslizar horizontalmente sobre la vista previa cambia de modo
                                // (desactivado mientras hay un anverso de DNI pendiente).
                                var total = 0f
                                val threshold = with(density) { 70.dp.toPx() }
                                detectHorizontalDragGestures(
                                    onDragStart = { total = 0f },
                                    onHorizontalDrag = { change, amount -> total += amount; change.consume() },
                                    onDragEnd = {
                                        val modes = ScanMode.entries
                                        val idx = modes.indexOf(modeState)
                                        if (abs(total) > threshold && idFront == null) {
                                            val next = if (total < 0) idx + 1 else idx - 1
                                            if (next in modes.indices) changeMode(modes[next])
                                        }
                                    },
                                )
                            },
                    ) {
                        DetectionOverlay(smoothed, mode, { stableProgress }, gradStart, gradEnd)
                        FocusRing(focusPoint, focusKey, gradEnd)
                    }
                    ShutterFlashOverlay { shutterFlash.value }
                    if (stacked) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 12.dp, end = 12.dp)) { hintArea() }
                    }
                }
            }
            if (stacked) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomInset),
                    contentAlignment = Alignment.Center,
                ) { controls() }
            }
        }

        if (!stacked) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f))))
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp, top = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                hintArea()
                Spacer(Modifier.height(10.dp))
                controls()
            }
        }

        // ------------------------------------------------ barra superior (sobre la vista previa)
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.Filled.Close, "Cerrar", onClick = { requestClose() })
            Spacer(Modifier.weight(1f))
            LowLightToggle(
                setting = lowLight,
                active = burstWanted(),
                onClick = {
                    lowLight = LowLightSetting.entries[(lowLight.ordinal + 1) % LowLightSetting.entries.size]
                    toast(lowLight.label)
                },
            )
            Spacer(Modifier.width(8.dp))
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

        LoadingOverlay(
            visible = finishing,
            message = if (pendingJobs > 0) "Mejorando $pendingJobs ${if (pendingJobs == 1) "captura" else "capturas"}…" else "Preparando documento…",
            modifier = Modifier.fillMaxSize(),
        )
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
            shape = MaterialTheme.shapes.extraLarge,
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
    val brandGradient = MaterialTheme.brand.horizontalGradient
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "pill")
    val textColor by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.78f), label = "pillText")
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .then(
                if (selected) Modifier.background(brandGradient)
                else Modifier.background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), shape),
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { stateDescription = if (selected) "Seleccionado" else "" }
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(modeIcon(mode), null, tint = textColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(modeShortLabel(mode), color = textColor, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, progress: () -> Float, busy: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.brand.gradientStart
    val secondary = MaterialTheme.brand.gradientEnd
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "shutter",
    )
    // El progreso se anima y se lee SÓLO en la fase de dibujo (sin recomponer el botón ni la pantalla).
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { progress() }.collectLatest { animProgress.animateTo(it, tween(180)) }
    }
    val inner by animateFloatAsState(if (busy) 0.55f else 1f, tween(160), label = "inner")
    Box(
        Modifier
            .size(86.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.6f }
            .semantics { contentDescription = "Capturar"; role = Role.Button }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
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
            val ap = animProgress.value
            if (ap > 0.001f) {
                val pr = r
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * ap,
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
                        .clickable(onClickLabel = "Terminar y revisar", onClick = onDone),
                    contentAlignment = Alignment.Center,
                ) {
                    if (last != null && doc != null) {
                        // El archivo se resuelve en IO y sólo cuando cambia la página (thumbFile consulta el disco).
                        val ctx = LocalContext.current
                        val file by produceState<File?>(null, doc.id, last.id, last.thumbFile, last.processedFile) {
                            value = withContext(Dispatchers.IO) {
                                container.documents.thumbFile(doc.id, last) ?: container.documents.originalFile(doc.id, last)
                            }
                        }
                        val thumbPx = with(LocalDensity.current) { 54.dp.roundToPx() }.coerceAtMost(160)
                        val request = remember(file, thumbPx) {
                            file?.let {
                                ImageRequest.Builder(ctx).data(it).size(thumbPx).precision(Precision.INEXACT).build()
                            }
                        }
                        if (request != null) AsyncImage(
                            model = request,
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
                            .offset(x = 6.dp, y = (-6).dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.brand.gradient)
                            .semantics { contentDescription = "$count páginas" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .clickable(role = Role.Button, onClick = onDone)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Listo", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Destello de obturador: el alfa se lee en la capa gráfica (no recompone la pantalla durante la animación). */
@Composable
private fun ShutterFlashOverlay(alpha: () -> Float) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
            .background(Color.White),
    )
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
    val brandGradient = MaterialTheme.brand.horizontalGradient
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (enabled) Modifier.background(brandGradient)
                else Modifier.background(Color.Black.copy(alpha = 0.45f)),
            )
            .semantics { stateDescription = if (enabled) "Captura automática activada" else "Captura manual" }
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (enabled) "Auto" else "Manual", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Interruptor del modo poca luz / anti-reflejos: resaltado si la ráfaga está activa; "A" = automático. */
@Composable
private fun LowLightToggle(setting: LowLightSetting, active: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        RoundIconButton(
            Icons.Filled.NightsStay, setting.label,
            highlighted = active,
            onClick = onClick,
            modifier = Modifier.graphicsLayer { alpha = if (setting == LowLightSetting.OFF) 0.6f else 1f },
        )
        if (setting == LowLightSetting.AUTO) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    highlighted: Boolean = false,
    dark: Boolean = true,
) {
    val bg = when {
        highlighted -> MaterialTheme.brand.gradientStart
        dark -> Color.Black.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (dark || highlighted) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}
