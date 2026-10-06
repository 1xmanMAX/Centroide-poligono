package com.scannerpromax.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.scannerpromax.domain.EraseMode
import com.scannerpromax.domain.EraseStroke
import com.scannerpromax.domain.Pt
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Borrado manual de rayas/manchas: se dibuja con UN dedo; con DOS dedos se hace zoom (pellizco) y se desplaza.
 * Los trazos se guardan normalizados 0..1 sobre la imagen (misma geometría que la vista previa) y el radio
 * normalizado respecto del ancho.
 *
 * [appliedCount] = cuántos trazos ya están reflejados en [image]; el resto se dibuja como superposición
 * mientras se recalcula la vista previa.
 */
@Composable
internal fun EraseTool(
    image: ImageBitmap,
    strokes: List<EraseStroke>,
    appliedCount: Int,
    brushRadius: Float,
    mode: EraseMode,
    onStroke: (EraseStroke) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val current = remember { mutableStateListOf<Offset>() }
    var cursor by remember { mutableStateOf<Offset?>(null) }

    val rect = fitRect(boxSize, image.width, image.height, 0f)
    val currentRect by rememberUpdatedState(rect)
    val radius by rememberUpdatedState(brushRadius)
    val eraseMode by rememberUpdatedState(mode)
    val strokeCallback by rememberUpdatedState(onStroke)

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(image) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var transforming = false
                    current.clear()
                    toNormalized(down.position, size, zoom, pan, currentRect)?.let { current.add(it) }
                    cursor = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (!transforming) {
                                // Al poner el segundo dedo se descarta el trazo: era un gesto de zoom.
                                transforming = true
                                current.clear()
                                cursor = null
                            }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newZoom = (zoom * zoomChange).coerceIn(1f, 6f)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            if (centroid != Offset.Unspecified) {
                                val rel = centroid - center
                                // Mantiene fijo el punto bajo los dedos
                                var p = rel - (rel - pan) * (newZoom / zoom) + panChange
                                val maxX = (newZoom - 1f) * size.width / 2f
                                val maxY = (newZoom - 1f) * size.height / 2f
                                p = Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                                pan = p
                            }
                            zoom = newZoom
                            event.changes.forEach { it.consume() }
                        } else if (!transforming) {
                            val ch = pressed.first()
                            val n = toNormalized(ch.position, size, zoom, pan, currentRect)
                            if (n != null) {
                                val last = current.lastOrNull()
                                // Submuestreo: ignora movimientos de menos de ~0.3 % del ancho
                                if (last == null || hypot(n.x - last.x, n.y - last.y) > 0.003f) current.add(n)
                            }
                            cursor = ch.position
                            ch.consume()
                        }
                    }
                    if (!transforming && current.isNotEmpty()) {
                        val pts = current.map { Pt(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                        strokeCallback(EraseStroke(points = pts, radius = radius, mode = eraseMode))
                    }
                    current.clear()
                    cursor = null
                }
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            if (rect.width <= 0f) return@Canvas
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            )
            for (i in appliedCount.coerceAtLeast(0) until strokes.size) {
                val s = strokes[i]
                drawStroke(s.points.map { Offset(it.x, it.y) }, s.radius, s.mode, rect, accent)
            }
            if (current.isNotEmpty()) drawStroke(current, brushRadius, mode, rect, accent)
        }
        // Cursor del pincel (en coordenadas de pantalla, tamaño real con el zoom)
        Canvas(Modifier.fillMaxSize()) {
            val c = cursor ?: return@Canvas
            val r = brushRadius * rect.width * zoom
            drawCircle(Color.White, radius = r, center = c, style = Stroke(width = 2f * density))
            drawCircle(accent, radius = r + 1.5f * density, center = c, style = Stroke(width = 1f * density))
        }
    }
}

private fun DrawScope.drawStroke(points: List<Offset>, radius: Float, mode: EraseMode, rect: Rect, accent: Color) {
    if (points.isEmpty()) return
    val color = when (mode) {
        EraseMode.HEAL -> accent.copy(alpha = 0.5f)
        EraseMode.WHITE -> Color.White.copy(alpha = 0.92f)
    }
    val width = (radius * 2f * rect.width).coerceAtLeast(1f)
    val pts = points.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
    if (pts.size == 1) {
        drawCircle(color, radius = width / 2f, center = pts[0])
        return
    }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Pantalla -> coordenadas normalizadas de la imagen, deshaciendo zoom/desplazamiento (origen en el centro). */
private fun toNormalized(p: Offset, size: IntSize, zoom: Float, pan: Offset, rect: Rect): Offset? {
    if (rect.width <= 0f || rect.height <= 0f) return null
    val center = Offset(size.width / 2f, size.height / 2f)
    val q = center + (p - center - pan) / zoom
    val nx = (q.x - rect.left) / rect.width
    val ny = (q.y - rect.top) / rect.height
    // Pequeña tolerancia fuera del borde (para poder borrar justo en el margen)
    if (nx < -0.05f || nx > 1.05f || ny < -0.05f || ny > 1.05f) return null
    return Offset(nx, ny)
}
