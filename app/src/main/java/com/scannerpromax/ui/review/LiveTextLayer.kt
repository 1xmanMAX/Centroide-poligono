package com.scannerpromax.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.ocr.LiveText
import com.scannerpromax.ocr.LiveTextMapping
import com.scannerpromax.ocr.OcrText
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.ui
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Estado del reconocimiento de la página abierta en el visor. */
internal enum class LiveTextStatus { IDLE, LOADING, READY, NONE, FAILED }

/**
 * "Texto en vivo" de una página del visor: palabras del OCR guardado (orden de lectura) y rango seleccionado.
 * Un estado por página: al pasar de página se crea otro (la selección no se arrastra).
 */
@Stable
internal class LiveTextState {
    var status by mutableStateOf(LiveTextStatus.IDLE)
    var result by mutableStateOf<OcrResult?>(null)
        private set
    var words by mutableStateOf<List<LiveText.Word>>(emptyList())
        private set
    var range by mutableStateOf<LiveText.Range?>(null)
    /** true mientras el dedo amplía la selección (pulsación larga + arrastre o un tirador): se oculta el menú. */
    var dragging by mutableStateOf(false)

    fun load(r: OcrResult) {
        result = r
        words = LiveText.words(r)
        status = if (words.isEmpty()) LiveTextStatus.NONE else LiveTextStatus.READY
        range = null
    }

    fun selectAll() {
        range = LiveText.selectAll(words)
    }

    /**
     * Texto seleccionado. Con TODO seleccionado y texto corregido por el usuario se copia el corregido (las cajas
     * son las del reconocimiento, pero el usuario espera su corrección).
     */
    fun selectedText(): String {
        val r = range ?: return ""
        val res = result
        if (res?.editedText != null && r == LiveText.selectAll(words)) return OcrText.plainText(res)
        return LiveText.text(words, r)
    }
}

/**
 * Pulsación larga sobre una palabra = la selecciona; si se sigue arrastrando, la selección se amplía hasta la
 * palabra bajo el dedo (en orden de lectura). Debe ir DENTRO (después) de los gestos de zoom para recibir los eventos
 * antes que ellos: tras la pulsación larga consume el arrastre y el zoom/paneo no se mueve.
 */
internal fun Modifier.liveTextGestures(
    live: LiveTextState,
    mapping: () -> LiveTextMapping?,
    onLongPressWithoutText: () -> Unit,
    onSelected: () -> Unit,
): Modifier = pointerInput(live) {
    var anchor = -1
    detectDragGesturesAfterLongPress(
        onDragStart = { pos ->
            anchor = -1
            val m = mapping()
            if (live.status != LiveTextStatus.READY || m == null) {
                onLongPressWithoutText()
                return@detectDragGesturesAfterLongPress
            }
            val tol = 12.dp.toPx() / m.pxPerOcr.coerceAtLeast(1e-3f)
            val i = LiveText.hitTest(live.words, m.toOcrX(pos.x), m.toOcrY(pos.y), tol)
            if (i == null) {
                live.range = null
                return@detectDragGesturesAfterLongPress
            }
            anchor = i
            live.range = LiveText.Range(i, i)
            live.dragging = true
            onSelected()
        },
        onDrag = { change, _ ->
            if (anchor < 0) return@detectDragGesturesAfterLongPress
            val m = mapping() ?: return@detectDragGesturesAfterLongPress
            val j = LiveText.nearest(live.words, m.toOcrX(change.position.x), m.toOcrY(change.position.y)) ?: return@detectDragGesturesAfterLongPress
            live.range = LiveText.between(anchor, j)
        },
        onDragEnd = { live.dragging = false },
        onDragCancel = { live.dragging = false },
    )
}

/**
 * Marca discreta de las lecturas DUDOSAS (líneas o palabras de confianza baja: manuscrito difícil, campos de
 * formulario): subrayado punteado naranja translúcido bajo cada palabra. Siguen siendo seleccionables y copiables.
 */
@Composable
internal fun BoxScope.LiveTextDoubtMarks(live: LiveTextState, mapping: () -> LiveTextMapping?) {
    if (live.status != LiveTextStatus.READY) return
    val boxes = remember(live.words) { LiveText.doubtfulBoxes(live.words) }
    if (boxes.isEmpty()) return
    val m = mapping() ?: return
    val color = MaterialTheme.ui.orange.copy(alpha = 0.75f)
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 1.5.dp.toPx()
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
        for (b in boxes) {
            val r = m.toView(b)
            val y = r.bottom + stroke
            drawLine(color, Offset(r.left, y), Offset(r.right, y), strokeWidth = stroke, pathEffect = dash)
        }
    }
}

/**
 * Capa de selección sobre la imagen (mismo tamaño que la vista del visor): resaltado por líneas, dos tiradores
 * arrastrables y el menú flotante Copiar · Seleccionar todo · Compartir. [mapping] da las coordenadas actuales
 * (cambian con el zoom y el desplazamiento).
 */
@Composable
internal fun BoxScope.LiveTextLayer(
    live: LiveTextState,
    mapping: () -> LiveTextMapping?,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val range = live.range ?: return
    val m = mapping() ?: return
    val words = live.words
    if (words.isEmpty()) return
    val ui = MaterialTheme.ui
    val accent = ui.accent
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val rects = remember(words, range) { LiveText.lineRects(words, range) }
    if (rects.isEmpty()) return
    val viewRects = rects.map { m.toView(it) }
    val first = viewRects.first()
    val last = viewRects.last()
    val knob = with(density) { 6.dp.toPx() }
    val bar = with(density) { 2.dp.toPx() }

    Canvas(Modifier.fillMaxSize()) {
        val pad = 1.5.dp.toPx()
        for (r in viewRects) {
            drawRoundRect(
                color = accent.copy(alpha = 0.30f),
                topLeft = Offset(r.left - pad, r.top - pad),
                size = Size(r.right - r.left + 2 * pad, r.bottom - r.top + 2 * pad),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        // Tirador de inicio: barra a la izquierda de la primera palabra con el círculo ARRIBA; fin: a la derecha de
        // la última con el círculo ABAJO (como iOS).
        drawRect(accent, Offset(first.left - pad - bar, first.top - pad), Size(bar, first.bottom - first.top + 2 * pad))
        drawCircle(accent, knob, Offset(first.left - pad - bar / 2, first.top - pad - knob * 0.8f))
        drawRect(accent, Offset(last.right + pad, last.top - pad), Size(bar, last.bottom - last.top + 2 * pad))
        drawCircle(accent, knob, Offset(last.right + pad + bar / 2, last.bottom + pad + knob * 0.8f))
    }

    // Zonas táctiles de 44 dp sobre cada círculo. El punto que sigue al dedo es el del texto (borde de la palabra a
    // media altura), no el del círculo, para que no salte a la línea de arriba/abajo.
    val startAnchor = Offset(first.left, (first.top + first.bottom) / 2f)
    val endAnchor = Offset(last.right, (last.top + last.bottom) / 2f)
    val startKnob = Offset(first.left, first.top - knob)
    val endKnob = Offset(last.right, last.bottom + knob)
    SelectionHandle(LiveText.Handle.START, startKnob, startAnchor, live, mapping) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    SelectionHandle(LiveText.Handle.END, endKnob, endAnchor, live, mapping) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    if (!live.dragging) {
        val top = viewRects.minOf { it.top }
        val bottom = viewRects.maxOf { it.bottom }
        val left = viewRects.minOf { it.left }
        val right = viewRects.maxOf { it.right }
        LiveTextMenu(
            anchorTop = top,
            anchorBottom = bottom,
            centerX = (left + right) / 2f,
            onCopy = { onCopy(live.selectedText()) },
            onSelectAll = { live.selectAll() },
            onShare = { onShare(live.selectedText()) },
            allSelected = range == LiveText.selectAll(words),
        )
    }
}

@Composable
private fun SelectionHandle(
    handle: LiveText.Handle,
    knob: Offset,
    anchor: Offset,
    live: LiveTextState,
    mapping: () -> LiveTextMapping?,
    onStep: () -> Unit,
) {
    val density = LocalDensity.current
    val half = with(density) { 22.dp.toPx() }
    val currentAnchor by rememberUpdatedState(anchor)
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier
            .offset { IntOffset((knob.x - half).roundToInt(), (knob.y - half).roundToInt()) }
            .size(44.dp)
            .semantics { contentDescription = if (handle == LiveText.Handle.START) "Inicio de la selección" else "Fin de la selección" }
            .pointerInput(handle) {
                var current = handle
                var pos = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        current = handle
                        pos = currentAnchor
                        live.dragging = true
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        pos += amount
                        val m = mapping() ?: return@detectDragGestures
                        val r = live.range ?: return@detectDragGestures
                        val target = LiveText.nearest(live.words, m.toOcrX(pos.x), m.toOcrY(pos.y)) ?: return@detectDragGestures
                        val (nr, nh) = LiveText.dragHandle(r, current, target)
                        if (nr != r) step()
                        live.range = nr
                        current = nh
                    },
                    onDragEnd = { live.dragging = false },
                    onDragCancel = { live.dragging = false },
                )
            },
    )
}

/**
 * Menú flotante estilo iOS (cápsula oscura): Copiar · Seleccionar todo · Compartir, encima de la selección (o debajo
 * si no cabe), centrado y sin salirse de la vista.
 */
@Composable
internal fun BoxScope.LiveTextMenu(
    anchorTop: Float,
    anchorBottom: Float,
    centerX: Float,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    allSelected: Boolean = false,
) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var parent by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.matchParentSize().onSizeChanged { parent = it })
    val gap = with(density) { 14.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    // Zona reservada arriba para la barra del visor.
    val topSafe = with(density) { 96.dp.toPx() }
    val above = anchorTop - gap - size.height
    val y = if (above >= topSafe || anchorBottom + gap + size.height > parent.height - margin) max(margin, above) else anchorBottom + gap
    val x = (centerX - size.width / 2f).coerceIn(margin, max(margin, parent.width - size.width - margin))
    Row(
        Modifier
            .offset { IntOffset(x.roundToInt(), min(y, (parent.height - size.height - margin).coerceAtLeast(0f)).roundToInt()) }
            .onSizeChanged { size = it }
            .shadow(8.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF22C2C2E)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuAction("Copiar", onCopy)
        MenuDivider()
        if (!allSelected) {
            MenuAction("Seleccionar todo", onSelectAll)
            MenuDivider()
        }
        MenuAction("Compartir", onShare)
    }
}

@Composable
private fun MenuAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun MenuDivider() {
    Box(Modifier.width(0.5.dp).height(22.dp).background(Color.White.copy(alpha = 0.28f)))
}

/** Aviso inferior discreto del visor: "Reconociendo texto…" o la pista de Texto en vivo. */
@Composable
internal fun LiveTextHint(text: String, loading: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(PillShape)
            .background(Color(0xE61C1C1E))
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}
