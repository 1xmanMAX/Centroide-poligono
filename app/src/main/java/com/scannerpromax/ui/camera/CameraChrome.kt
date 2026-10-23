package com.scannerpromax.ui.camera

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.GroupRow
import com.scannerpromax.ui.components.MoreMenu
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.ForcedDarkTheme
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.SystemBlue
import com.scannerpromax.ui.theme.SystemGreen
import com.scannerpromax.ui.theme.SystemOrange
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

// =================================================================================================
// Cámara estilo iPhone (rediseño fase 2): piezas visuales SIN estado. La lógica vive en CameraScreen.kt.
// =================================================================================================

/** Amarillo de la cámara de iOS (modo y zoom activos). */
internal val CameraYellow = Color(0xFFFFD60A)
private val Glass = Color.White.copy(alpha = 0.14f)
private val CameraTopBarHeight = 60.dp
private val CameraControlsHeight = 206.dp

/** Orden de los modos en el selector de texto (y al deslizar sobre la vista previa). */
internal val CameraModeOrder = listOf(
    ScanMode.BOOK, ScanMode.DOCUMENT, ScanMode.ID_CARD, ScanMode.RECEIPT, ScanMode.WHITEBOARD, ScanMode.PHOTO,
)

internal fun cameraModeLabel(mode: ScanMode) = when (mode) {
    ScanMode.DOCUMENT -> "DOCUMENTO"
    ScanMode.BOOK -> "LIBRO"
    ScanMode.ID_CARD -> "DNI"
    ScanMode.RECEIPT -> "RECIBO"
    ScanMode.WHITEBOARD -> "PIZARRA"
    ScanMode.PHOTO -> "FOTO"
}

internal fun zoomLabel(r: Float): String =
    if (abs(r - r.toInt()) < 0.05f) "${r.toInt()}×" else if (r < 1f) ".%d".format(((r * 10f) + 0.5f).toInt()) else "%.1f×".format(r)

/**
 * Disposición de la cámara (maqueta Camara.dc.html): barra superior sobre negro, vista previa 3:4 y debajo zoom,
 * modos y la fila galería | disparador | lote. Si la pantalla es demasiado baja, la vista previa ocupa todo y la
 * barra y los controles se superponen con degradados.
 */
@Composable
internal fun CameraScaffold(
    topBar: @Composable () -> Unit,
    preview: @Composable BoxScope.() -> Unit,
    hintArea: @Composable () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    overlays: @Composable BoxScope.() -> Unit = {},
) = ForcedDarkTheme {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val availableH = maxHeight - topInset - bottomInset - CameraTopBarHeight - CameraControlsHeight
        val stacked = availableH >= maxWidth * 0.95f
        val previewH: Dp = if (stacked) min(maxWidth * 4f / 3f, availableH) else maxHeight
        val previewW: Dp = if (stacked) min(maxWidth, previewH * 3f / 4f) else maxWidth

        if (stacked) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(topInset))
                Box(Modifier.fillMaxWidth().height(CameraTopBarHeight), contentAlignment = Alignment.Center) { topBar() }
                Box(Modifier.fillMaxWidth().height(previewH), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(previewW).height(previewH).clip(RoundedCornerShape(4.dp))) {
                        preview()
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 12.dp, end = 12.dp)) { hintArea() }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomInset),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    content = controls,
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) { preview() }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.88f))))
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp, top = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                hintArea()
                Spacer(Modifier.height(10.dp))
                controls()
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .statusBarsPadding()
                    .height(CameraTopBarHeight),
                contentAlignment = Alignment.Center,
            ) { topBar() }
        }
        overlays()
    }
}

/** Botón redondo translúcido de la cámara (40 dp, blanco 14 %). [highlighted] = relleno amarillo (activo). */
@Composable
internal fun CameraGlassButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    iconSize: Dp = 20.dp,
) {
    IconCircleButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = 40.dp,
        iconSize = iconSize,
        containerColor = if (highlighted) CameraYellow else Glass,
        contentColor = if (highlighted) Color.Black else Color.White,
        elevation = 0.dp,
    )
}

/** Segmentado oscuro "Auto | Manual" (captura automática). */
@Composable
internal fun AutoManualToggle(auto: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(PillShape)
            .background(Glass)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(true to "Auto", false to "Manual").forEach { (value, label) ->
            val selected = value == auto
            Text(
                label,
                color = if (selected) Color.Black else Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(PillShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable(role = Role.Tab) { if (!selected) onSelect(value) }
                    .semantics {
                        this.selected = selected
                        stateDescription = if (value) "Captura automática" else "Captura manual"
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

internal fun flashIcon(flash: FlashSetting): ImageVector = when (flash) {
    FlashSetting.OFF -> Icons.Filled.FlashOff
    FlashSetting.AUTO -> Icons.Filled.FlashAuto
    FlashSetting.ON -> Icons.Filled.FlashOn
    FlashSetting.TORCH -> Icons.Filled.FlashlightOn
}

/**
 * Barra superior: Cerrar · Auto/Manual · flash y •••. [lowLightActive] pinta una luna amarilla junto a •••
 * (ráfaga de poca luz / anti-reflejos activa). [menuGroups] = opciones del menú •••.
 */
@Composable
internal fun CameraTopBar(
    onClose: () -> Unit,
    showAutoToggle: Boolean,
    autoCapture: Boolean,
    onAutoCapture: (Boolean) -> Unit,
    hasFlash: Boolean,
    flash: FlashSetting,
    onFlash: () -> Unit,
    lowLightActive: Boolean,
    menuGroups: List<List<MoreMenuItem>>,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        CameraGlassButton(AppIcons.Close, "Cerrar", onClose, Modifier.align(Alignment.CenterStart), iconSize = 18.dp)
        if (showAutoToggle) {
            AutoManualToggle(autoCapture, onAutoCapture, Modifier.align(Alignment.Center))
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lowLightActive) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(CameraYellow).semantics { contentDescription = "Poca luz / anti-reflejos activo" },
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.Moon, null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
            }
            if (hasFlash) {
                CameraGlassButton(flashIcon(flash), flash.label, onFlash, highlighted = flash != FlashSetting.OFF)
            }
            Box {
                CameraGlassButton(AppIcons.More, "Más opciones de cámara", { menuOpen = true }, iconSize = 18.dp)
                MoreMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, groups = menuGroups, width = 270.dp)
            }
        }
    }
}

/** Zoom en pastillas redondas (32 dp): el activo en amarillo; si se pellizcó a otro valor, la más cercana lo muestra. */
@Composable
internal fun ZoomPills(ratio: Float, min: Float, max: Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    val presets = buildList {
        if (min < 0.95f) add(min)
        add(1f)
        if (max >= 1.99f) add(2f)
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { p ->
            val nearest = presets.minByOrNull { abs(it - ratio) } == p
            val selected = nearest && abs(ratio - p) < 0.05f
            val label = if (nearest && !selected) zoomLabel(ratio) else if (nearest) zoomLabel(p) else zoomLabel(p).removeSuffix("×")
            Box(
                Modifier
                    .size(if (nearest) 36.dp else 32.dp)
                    .clip(CircleShape)
                    .background(Glass)
                    .clickable(role = Role.Button, onClickLabel = "Zoom ${zoomLabel(p)}") { onSelect(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (nearest) CameraYellow else Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (nearest) FontWeight.Bold else FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Selector de modo de texto horizontal deslizable (LIBRO · DOCUMENTO · DNI · RECIBO · PIZARRA · FOTO), el activo en
 * amarillo y centrado. [enabled] = false lo atenúa (anverso de DNI pendiente: el cambio guarda una cara).
 */
@Composable
internal fun ModeTextSelector(selected: ScanMode, onSelect: (ScanMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = CameraModeOrder
    val state = rememberLazyListState()
    val perf = LocalPerf.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val side = (maxWidth / 2 - 48.dp).coerceAtLeast(0.dp)
        LaunchedEffect(selected) {
            val idx = modes.indexOf(selected).coerceAtLeast(0)
            fun centerDelta(): Float? {
                val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx } ?: return null
                val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
                return (info.offset + info.size / 2 - center).toFloat()
            }
            val d = centerDelta()
            if (d == null) {
                state.scrollToItem(idx)
                withFrameNanos { }
                centerDelta()?.let { state.scrollBy(it) }
            } else if (perf.duration(250) > 0) {
                state.animateScrollBy(d, tween(perf.duration(250)))
            } else {
                state.scrollBy(d)
            }
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(modes, key = { _, m -> m.name }) { _, m ->
                val isSel = m == selected
                val color by animateColorAsState(if (isSel) CameraYellow else Color.White.copy(alpha = 0.6f), tween(perf.duration(180)), label = "modeColor")
                Text(
                    cameraModeLabel(m),
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Tab, onClickLabel = "Modo ${m.label}") { onSelect(m) }
                        .semantics { this.selected = isSel; contentDescription = "Modo ${m.label}" }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Disparador iOS: aro blanco de 4 dp y círculo blanco; el progreso de la autocaptura dibuja un arco amarillo. */
@Composable
internal fun ShutterButton(enabled: Boolean, progress: () -> Float, busy: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "shutter",
    )
    // El progreso se anima y se lee SÓLO en la fase de dibujo (sin recomponer el botón ni la pantalla).
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { progress() }.collectLatest { animProgress.animateTo(it, tween(180)) }
    }
    val inner by animateFloatAsState(if (busy) 0.6f else 1f, tween(160), label = "inner")
    Box(
        Modifier
            .size(76.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.6f }
            .semantics { contentDescription = "Capturar"; role = Role.Button }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val r = size.minDimension / 2f - stroke / 2f
            drawCircle(Color.White, radius = r, style = Stroke(width = stroke))
            val ap = animProgress.value
            if (ap > 0.001f) {
                drawArc(
                    color = CameraYellow,
                    startAngle = -90f,
                    sweepAngle = 360f * ap,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier
                .size(60.dp)
                .graphicsLayer { scaleX = inner; scaleY = inner }
                .clip(CircleShape)
                .background(Color.White),
        )
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color(0xFF8E8E93), strokeWidth = 3.dp)
    }
}

/** Botón de la galería (48 dp, borde blanco, radio 10). */
@Composable
internal fun GalleryButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .background(Color(0xFF3A3A3C))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Importar de la galería" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Gallery, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * Lote: cápsula "[miniatura con N] Listo" que termina y abre el documento. Visible con páginas o capturas en curso.
 * [thumbnail] dibuja la última página dentro del cuadrado de 36 dp.
 */
@Composable
internal fun BatchButton(
    count: Int,
    processing: Boolean,
    onDone: () -> Unit,
    /** Texto del botón: "Listo" (revisar) o "Adjuntar (N)" en el modo directo para otra app. */
    label: String = "Listo",
    /** Si no es null, tocar la miniatura abre el lote (revisión/retoques) en vez de terminar. */
    onThumbnail: (() -> Unit)? = null,
    thumbnail: @Composable BoxScope.() -> Unit,
) {
    val visible = count > 0 || processing
    val pagesText = "$count ${if (count == 1) "página" else "páginas"}"
    AnimatedVisibility(visible = visible, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        Row(
            Modifier
                .height(48.dp)
                .clip(PillShape)
                .background(Glass)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onDone)
                .semantics { contentDescription = if (label == "Listo") "Terminar y revisar, $pagesText" else "$label, $pagesText" }
                .padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF4F1EA))
                    .then(
                        if (onThumbnail != null) {
                            Modifier.clickable(role = Role.Button, onClickLabel = "Revisar y retocar páginas", onClick = onThumbnail)
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                thumbnail()
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.Center) {
                    if (processing) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("$count", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

/** Fila inferior: galería | disparador | lote (anchos fijos a los lados para centrar el disparador). */
@Composable
internal fun CameraBottomRow(gallery: @Composable () -> Unit, shutter: @Composable () -> Unit, batch: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { gallery() }
        shutter()
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { batch() }
    }
}

/** Píldora de ayuda sobre la vista previa: negra translúcida, o amarilla si es un aviso (poca luz, borrosa, reflejo). */
@Composable
internal fun HintPill(text: String, icon: ImageVector?, warning: Boolean, onClick: (() -> Unit)?) {
    val bg = if (warning) CameraYellow else Color.Black.copy(alpha = 0.55f)
    val fg = if (warning) Color.Black else Color.White
    Row(
        Modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** DNI: "Omitir reverso (solo una cara)" como cápsula translúcida. */
@Composable
internal fun SkipBackButton(onClick: () -> Unit) {
    Text(
        "Omitir reverso (solo una cara)",
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(top = 8.dp)
            .heightIn(min = 40.dp)
            .clip(PillShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Aviso de resolución baja (la foto sale pequeña en un equipo de gama alta): tocar = detalles. */
@Composable
internal fun ResolutionWarningChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(PillShape)
            .background(CameraYellow)
            .clickable(onClickLabel = "Ver detalles de la resolución", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Resolution, null, tint = Color.Black, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text("Resolución baja · $label", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
    }
}

/** Tarjeta de error de la cámara (no se pudo abrir). */
@Composable
internal fun CameraErrorCard(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE61C1C1E))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Camera, null, tint = Color.White, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "Puedes usar la cámara del teléfono (menú •••) o importar imágenes desde la galería.",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Destello de obturador: el alfa se lee en la capa gráfica (no recompone la pantalla durante la animación). */
@Composable
internal fun ShutterFlashOverlay(alpha: () -> Float) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
            .background(Color.White),
    )
}

// =================================================================================================
// Permiso de cámara
// =================================================================================================

@Composable
internal fun PermissionScreen(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ui = MaterialTheme.ui
    Box(
        Modifier
            .fillMaxSize()
            .background(ui.groupedBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 64.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)).background(ui.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.Camera, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(24.dp))
            Text(
                "Activa la cámara para escanear",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = ui.label,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "ESCÁNER PRO MAX necesita la cámara para detectar los bordes del documento en tiempo real y capturarlo con la máxima calidad.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = ui.secondaryLabel,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Spacer(Modifier.height(26.dp))
            InsetGroup {
                item { GroupRow("Detección automática de bordes y mejora mágica", icon = Icons.Filled.AutoAwesome, iconTint = SystemOrange) }
                item { GroupRow("Todo se procesa en tu teléfono: nada se sube a internet", icon = Icons.Filled.Lock, iconTint = SystemGreen) }
                item { GroupRow("Toca la pantalla para enfocar y usa el flash con poca luz", icon = Icons.Filled.TouchApp, iconTint = SystemBlue) }
            }
            Spacer(Modifier.height(28.dp))
            PrimaryCapsuleButton(
                text = if (permanentlyDenied) "Abrir ajustes" else "Permitir cámara",
                icon = AppIcons.Camera,
                onClick = if (permanentlyDenied) onOpenSettings else onRequest,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            if (permanentlyDenied) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "El permiso fue denegado. Actívalo en Ajustes > Permisos > Cámara.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = ui.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }
        }
        IconCircleButton(AppIcons.Close, "Cerrar", onBack, Modifier.padding(12.dp), iconSize = 18.dp)
    }
}
