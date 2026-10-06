package com.scannerpromax.ui.camera

import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.scannerpromax.domain.ScanMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/** Relación de aspecto ISO ID-1 (85.6 x 54 mm). */
internal const val ID_CARD_RATIO = 85.6f / 54f

/**
 * Transformación del frame de cámara a la vista con escalado FILL_CENTER (igual que PreviewView):
 * escala uniforme que CUBRE la vista y centra; lo que sobra queda recortado.
 */
internal data class FrameMapping(val scale: Float, val offsetX: Float, val offsetY: Float, val frameW: Float, val frameH: Float) {
    fun toView(nx: Float, ny: Float) = Offset(offsetX + nx * frameW * scale, offsetY + ny * frameH * scale)

    /** Rectángulo de la vista -> rectángulo normalizado 0..1 en el frame. */
    fun viewRectToNormalized(r: Rect): RectF = RectF(
        ((r.left - offsetX) / (frameW * scale)).coerceIn(0f, 1f),
        ((r.top - offsetY) / (frameH * scale)).coerceIn(0f, 1f),
        ((r.right - offsetX) / (frameW * scale)).coerceIn(0f, 1f),
        ((r.bottom - offsetY) / (frameH * scale)).coerceIn(0f, 1f),
    )

    companion object {
        fun fillCenter(viewW: Float, viewH: Float, frameW: Float, frameH: Float): FrameMapping {
            val fw = max(1f, frameW); val fh = max(1f, frameH)
            val s = max(viewW / fw, viewH / fh)
            return FrameMapping(s, (viewW - fw * s) / 2f, (viewH - fh * s) / 2f, fw, fh)
        }
    }
}

/** Rectángulo guía de la tarjeta en coordenadas de la vista. */
internal fun idCardGuideRect(viewW: Float, viewH: Float): Rect {
    val w = viewW * 0.86f
    val h = w / ID_CARD_RATIO
    val cx = viewW / 2f
    val cy = viewH * 0.44f
    return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

/**
 * Estado del cuadrilátero suavizado. Las esquinas se interpolan exponencialmente hacia la última detección
 * en cada frame de pantalla (60 fps aunque el detector corra a 7 fps) => movimiento fluido sin tirones.
 */
@Stable
internal class SmoothedQuad {
    /** Esquinas normalizadas 0..1 (tl, tr, br, bl) como x0,y0,x1,y1... */
    val points = FloatArray(8)
    var frameW by mutableFloatStateOf(3f)
    var frameH by mutableFloatStateOf(4f)
    var alpha by mutableFloatStateOf(0f)
    /** Se incrementa cuando cambian los puntos: leerlo en la fase de dibujo invalida solo el Canvas. */
    var version by mutableIntStateOf(0)

    internal var target: FloatArray? = null
    internal var lastSeen = 0L
    /** Señal de nueva detección: el bucle de animación duerme mientras no hay nada que mover. */
    internal val signal = MutableStateFlow(0L)

    fun submit(det: LiveDetection?, nowMs: Long) {
        if (det == null) return
        val fw = det.frameWidth.toFloat(); val fh = det.frameHeight.toFloat()
        if (fw <= 0f || fh <= 0f) return
        val q = det.quad
        target = floatArrayOf(q.tl.x / fw, q.tl.y / fh, q.tr.x / fw, q.tr.y / fh, q.br.x / fw, q.br.y / fh, q.bl.x / fw, q.bl.y / fh)
        frameW = fw; frameH = fh
        lastSeen = nowMs
        signal.value = signal.value + 1
    }
}

@Composable
internal fun rememberSmoothedQuad(): SmoothedQuad {
    val state = remember { SmoothedQuad() }
    LaunchedEffect(state) {
        var last = 0L
        var idle = false
        while (isActive) {
            withFrameNanos { t ->
                val dtMs = if (last == 0L) 16f else ((t - last) / 1_000_000f).coerceIn(1f, 100f)
                last = t
                val tgt = state.target
                // Mantener visible un instante si se pierde la detección (evita parpadeos).
                val visible = tgt != null && (System.currentTimeMillis() - state.lastSeen) < HOLD_MS
                var changed = false
                if (visible && tgt != null) {
                    val p = state.points
                    var jump = 0f
                    for (i in 0 until 4) jump = max(jump, hypot(tgt[i * 2] - p[i * 2], tgt[i * 2 + 1] - p[i * 2 + 1]))
                    if (state.alpha < 0.05f || jump > 0.35f) {
                        tgt.copyInto(p)
                        changed = true
                    } else {
                        val k = 1f - exp(-dtMs / 85f)
                        for (i in 0 until 8) {
                            val d = tgt[i] - p[i]
                            if (kotlin.math.abs(d) > 0.0005f) { p[i] += d * k; changed = true }
                        }
                    }
                }
                val targetAlpha = if (visible) 1f else 0f
                if (kotlin.math.abs(state.alpha - targetAlpha) > 0.004f) {
                    val ka = 1f - exp(-dtMs / 140f)
                    state.alpha += (targetAlpha - state.alpha) * ka
                    changed = true
                } else if (state.alpha != targetAlpha) {
                    state.alpha = targetAlpha
                    changed = true
                }
                if (changed) state.version++
                idle = !changed
            }
            if (idle) {
                // Nada que animar: esperar a una nueva detección (o al fin del tiempo de espera para
                // desvanecer el quad) en lugar de redibujar a 60 fps. Ahorra batería y CPU en gama baja.
                val seen = state.signal.value
                withTimeoutOrNull(HOLD_MS + 30L) { state.signal.first { it != seen } }
                last = 0L
            }
        }
    }
    return state
}

private const val HOLD_MS = 450L

/**
 * Superposición: cuadrilátero detectado (relleno translúcido + borde con degradado de marca + esquinas),
 * guía de tarjeta (DNI) o de lomo (libro).
 */
@Composable
internal fun DetectionOverlay(
    quad: SmoothedQuad,
    mode: ScanMode,
    stableProgress: Float,
    primary: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        when (mode) {
            ScanMode.ID_CARD -> drawIdGuide(primary, secondary)
            ScanMode.BOOK -> drawBookGuide()
            else -> Unit
        }
        if (mode == ScanMode.PHOTO) return@Canvas
        @Suppress("UNUSED_VARIABLE") val v = quad.version // lectura en fase de dibujo
        val a = quad.alpha
        if (a <= 0.01f) return@Canvas
        val map = FrameMapping.fillCenter(size.width, size.height, quad.frameW, quad.frameH)
        val p = quad.points
        val pts = List(4) { i -> map.toView(p[i * 2], p[i * 2 + 1]) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
            close()
        }
        val locked = stableProgress > 0.02f
        val fillColor = if (locked) secondary else primary
        drawPath(path, fillColor.copy(alpha = 0.16f * a + 0.10f * stableProgress * a))
        val brush = Brush.linearGradient(listOf(primary.copy(alpha = a), secondary.copy(alpha = a)), start = pts[0], end = pts[2])
        drawPath(path, brush, style = Stroke(width = 3.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Esquinas tipo "L" para un aspecto premium
        val armLen = 18f * density
        for (i in 0 until 4) {
            val c = pts[i]
            val prev = pts[(i + 3) % 4]
            val next = pts[(i + 1) % 4]
            drawArm(c, prev, armLen, a)
            drawArm(c, next, armLen, a)
            drawCircle(Color.White.copy(alpha = a), radius = 4.5f * density, center = c)
            drawCircle(fillColor.copy(alpha = a), radius = 2.6f * density, center = c)
        }
    }
}

private fun DrawScope.drawArm(from: Offset, toward: Offset, len: Float, alpha: Float) {
    val dx = toward.x - from.x; val dy = toward.y - from.y
    val d = hypot(dx, dy)
    if (d < 1f) return
    val l = minOf(len, d / 3f)
    val end = Offset(from.x + dx / d * l, from.y + dy / d * l)
    drawLine(Color.White.copy(alpha = alpha), from, end, strokeWidth = 5f * density, cap = StrokeCap.Round)
}

private fun DrawScope.drawIdGuide(primary: Color, secondary: Color) {
    val r = idCardGuideRect(size.width, size.height)
    val corner = CornerRadius(16f * density)
    // Oscurece el exterior de la guía (relleno par-impar)
    val mask = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(r, corner))
    }
    drawPath(mask, Color.Black.copy(alpha = 0.42f))
    drawRoundRect(
        brush = Brush.linearGradient(listOf(primary, secondary), start = r.topLeft, end = r.bottomRight),
        topLeft = r.topLeft,
        size = Size(r.width, r.height),
        cornerRadius = corner,
        style = Stroke(width = 2.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f * density, 10f * density))),
    )
}

private fun DrawScope.drawBookGuide() {
    val x = size.width / 2f
    drawLine(
        Color.White.copy(alpha = 0.55f),
        Offset(x, size.height * 0.16f),
        Offset(x, size.height * 0.74f),
        strokeWidth = 2f * density,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f * density, 10f * density)),
    )
}

/** Anillo de enfoque animado en el punto tocado. */
@Composable
internal fun FocusRing(point: Offset?, key: Int, color: Color) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (point == null) return@LaunchedEffect
        anim.snapTo(0f)
        anim.animateTo(1f, tween(650))
    }
    if (point == null) return
    Canvas(Modifier.fillMaxSize()) {
        val t = anim.value
        if (t >= 1f) return@Canvas
        val radius = (46f - 14f * t) * density
        val alpha = if (t < 0.7f) 1f else (1f - (t - 0.7f) / 0.3f)
        drawCircle(color.copy(alpha = alpha), radius = radius, center = point, style = Stroke(width = 2.5f * density))
        drawCircle(Color.White.copy(alpha = alpha * 0.9f), radius = 3f * density, center = point)
    }
}
