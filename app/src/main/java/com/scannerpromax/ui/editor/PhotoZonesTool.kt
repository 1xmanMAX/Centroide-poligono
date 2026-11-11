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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.scannerpromax.domain.PhotoZone
import com.scannerpromax.domain.Pt
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Qué es cada recuadro de «Imágenes de la página». */
internal enum class ZoneKind { AUTO, EXCLUDED, MANUAL }

/** Recuadro que se muestra: caja automática (aplicada o descartada) o zona a mano ([index] en la lista manual). */
internal data class ZoneItem(val kind: ZoneKind, val zone: PhotoZone, val index: Int)

/** Modo de los gestos de un dedo. */
internal enum class ZoneMode { SELECT, RECT, LASSO }

/**
 * «Imágenes de la página»: la página con las imágenes encima (recuadro + etiqueta). Un dedo: tocar = seleccionar; en la
 * seleccionada, arrastrar una esquina o un lado = ajustar, arrastrar dentro = mover. En [ZoneMode.RECT] se dibuja un
 * recuadro nuevo y en [ZoneMode.LASSO] un contorno libre. Dos dedos: zoom y desplazamiento. Coordenadas normalizadas
 * 0..1 sobre [image] (el procesado final, como las zonas guardadas).
 */
@Composable
internal fun PhotoZonesTool(
    image: ImageBitmap,
    items: List<ZoneItem>,
    selected: ZoneItem?,
    mode: ZoneMode,
    accent: Color,
    onSelect: (ZoneItem?) -> Unit,
    onChange: (ZoneItem, PhotoZone) -> Unit,
    onAdd: (PhotoZone) -> Unit,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // recuadro en curso (arrastre o dibujo) y contorno del lazo, normalizados
    var live by remember { mutableStateOf<PhotoZone?>(null) }
    val lasso = remember { mutableStateListOf<Offset>() }
    val measurer = rememberTextMeasurer()

    val rect = fitRect(boxSize, image.width, image.height, 0f)
    val currentRect by rememberUpdatedState(rect)
    val curItems by rememberUpdatedState(items)
    val curSel by rememberUpdatedState(selected)
    val curMode by rememberUpdatedState(mode)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onChange)
    val add by rememberUpdatedState(onAdd)

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .semantics { contentDescription = "Imágenes de la página: toca una imagen para elegirla" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val r = currentRect
                    val start = toNorm(down.position, size, zoom, pan, r) ?: Offset(-1f, -1f)
                    var transforming = false
                    var moved = false
                    // qué se arrastra: asa de la seleccionada (0..7: esquinas y lados), dentro (8) o nada (-1)
                    val sel = curSel
                    val tol = 26f * density / max(1f, r.width * zoom)
                    val handle = if (curMode == ZoneMode.SELECT && sel != null) hitHandle(sel.zone, start, tol) else -1
                    val orig = sel?.zone
                    lasso.clear()
                    if (curMode == ZoneMode.LASSO) lasso.add(start)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (!transforming) { transforming = true; live = null; lasso.clear() }
                            val zc = event.calculateZoom(); val pc = event.calculatePan(); val c = event.calculateCentroid(useCurrent = true)
                            val nz = (zoom * zc).coerceIn(1f, 6f)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            if (c != Offset.Unspecified) {
                                val rel = c - center
                                var p = rel - (rel - pan) * (nz / zoom) + pc
                                val mx = (nz - 1f) * size.width / 2f; val my = (nz - 1f) * size.height / 2f
                                pan = Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
                            }
                            zoom = nz
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (transforming) continue
                        val ch = pressed.first()
                        val n = toNorm(ch.position, size, zoom, pan, r) ?: continue
                        if (hypot(n.x - start.x, n.y - start.y) > 0.004f) moved = true
                        when (curMode) {
                            ZoneMode.RECT -> if (moved) live = PhotoZone(min(start.x, n.x), min(start.y, n.y), max(start.x, n.x), max(start.y, n.y))
                            ZoneMode.LASSO -> { val l = lasso.lastOrNull(); if (l == null || hypot(n.x - l.x, n.y - l.y) > 0.004f) lasso.add(n) }
                            ZoneMode.SELECT -> if (moved && orig != null && handle >= 0) live = drag(orig, handle, n.x - start.x, n.y - start.y)
                        }
                        ch.consume()
                    }
                    if (transforming) { live = null; lasso.clear(); return@awaitEachGesture }
                    when (curMode) {
                        ZoneMode.RECT -> live?.let { add(it) }
                        ZoneMode.LASSO -> com.scannerpromax.imaging.PhotoZoneMath.lasso(lasso.map { Pt(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) })?.let { add(it) }
                        ZoneMode.SELECT -> {
                            val l = live
                            if (moved && l != null && sel != null) change(sel, l)
                            else if (!moved) select(hitItem(curItems, start))
                        }
                    }
                    live = null; lasso.clear()
                }
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
        ) {
            if (rect.width <= 0f) return@Canvas
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            )
            val lw = 2.2f * density / zoom
            for (it in items) {
                val isSel = selected != null && it.kind == selected.kind && it.index == selected.index
                val z = if (isSel) live ?: it.zone else it.zone
                val (color, label) = when (it.kind) {
                    ZoneKind.AUTO -> Color(0xFF34C759) to "Imagen"
                    ZoneKind.EXCLUDED -> Color(0xFFB0B0B5) to "No es imagen"
                    ZoneKind.MANUAL -> if (it.zone.normal == true) Color(0xFFFF9F0A) to "Como el resto" else accent to "Zona de imagen"
                }
                drawZone(z, rect, color, if (isSel) lw * 1.6f else lw, dashed = it.kind == ZoneKind.EXCLUDED, fill = isSel)
                drawLabel(measurer, label, z, rect, color, zoom)
                if (isSel && mode == ZoneMode.SELECT) drawHandles(z, rect, color, zoom)
            }
            live?.takeIf { mode == ZoneMode.RECT }?.let { drawZone(it, rect, accent, lw * 1.6f, dashed = true, fill = true) }
            if (lasso.size > 1) {
                val p = Path().apply {
                    moveTo(rect.left + lasso[0].x * rect.width, rect.top + lasso[0].y * rect.height)
                    for (i in 1 until lasso.size) lineTo(rect.left + lasso[i].x * rect.width, rect.top + lasso[i].y * rect.height)
                }
                drawPath(p, accent, style = Stroke(width = lw * 1.4f))
            }
        }
    }
}

private fun DrawScope.drawZone(z: PhotoZone, r: Rect, color: Color, width: Float, dashed: Boolean, fill: Boolean) {
    val tl = Offset(r.left + z.x0 * r.width, r.top + z.y0 * r.height)
    val sz = Size((z.x1 - z.x0) * r.width, (z.y1 - z.y0) * r.height)
    val poly = z.poly?.takeIf { it.size >= 3 }
    if (fill) drawRect(color.copy(alpha = 0.16f), tl, sz)
    if (poly != null) {
        val p = Path().apply {
            moveTo(r.left + poly[0].x * r.width, r.top + poly[0].y * r.height)
            for (i in 1 until poly.size) lineTo(r.left + poly[i].x * r.width, r.top + poly[i].y * r.height)
            close()
        }
        drawPath(p, color, style = Stroke(width = width))
        drawRect(color.copy(alpha = 0.5f), tl, sz, style = Stroke(width = width * 0.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
    } else {
        drawRect(Color.Black.copy(alpha = 0.35f), tl, sz, style = Stroke(width = width * 2f))
        drawRect(color, tl, sz, style = Stroke(width = width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
    }
}

private fun DrawScope.drawLabel(measurer: androidx.compose.ui.text.TextMeasurer, label: String, z: PhotoZone, r: Rect, color: Color, zoom: Float) {
    val res = measurer.measure(label, TextStyle(color = Color.White, fontSize = (11f / zoom).sp, fontWeight = FontWeight.SemiBold))
    val x = r.left + z.x0 * r.width + 2f
    val y = (r.top + z.y0 * r.height - res.size.height - 4f / zoom).coerceAtLeast(r.top + 1f)
    val pad = 4f / zoom
    drawRoundRect(color.copy(alpha = 0.92f), Offset(x - pad, y - pad / 2), Size(res.size.width + 2 * pad, res.size.height + pad), CornerRadius(6f / zoom))
    drawText(res, topLeft = Offset(x, y))
}

private fun DrawScope.drawHandles(z: PhotoZone, r: Rect, color: Color, zoom: Float) {
    val rad = 7f * density / zoom
    for ((hx, hy) in handlePoints(z)) {
        val c = Offset(r.left + hx * r.width, r.top + hy * r.height)
        drawCircle(Color.White, rad, c)
        drawCircle(color, rad, c, style = Stroke(width = 2f * density / zoom))
    }
}

/** Esquinas (0..3: arriba-izq., arriba-der., abajo-der., abajo-izq.) y centros de los lados (4..7: arriba, der., abajo, izq.). */
private fun handlePoints(z: PhotoZone): List<Pair<Float, Float>> {
    val cx = (z.x0 + z.x1) / 2; val cy = (z.y0 + z.y1) / 2
    return listOf(z.x0 to z.y0, z.x1 to z.y0, z.x1 to z.y1, z.x0 to z.y1, cx to z.y0, z.x1 to cy, cx to z.y1, z.x0 to cy)
}

/** Asa bajo [p] (tolerancia [tol] normalizada) o 8 si cae dentro de la zona; -1 = nada. */
private fun hitHandle(z: PhotoZone, p: Offset, tol: Float): Int {
    val hs = handlePoints(z)
    var best = -1; var bd = Float.MAX_VALUE
    for ((i, h) in hs.withIndex()) { val d = hypot(p.x - h.first, p.y - h.second); if (d < tol && d < bd) { bd = d; best = i } }
    if (best >= 0) return best
    return if (p.x in z.x0..z.x1 && p.y in z.y0..z.y1) 8 else -1
}

/** Zona [z] con el asa [h] desplazada (dx, dy); 8 = mover entera (sin salirse de la página). */
internal fun drag(z: PhotoZone, h: Int, dx: Float, dy: Float): PhotoZone {
    val m = 0.02f
    var x0 = z.x0; var y0 = z.y0; var x1 = z.x1; var y1 = z.y1
    when (h) {
        8 -> {
            val ddx = dx.coerceIn(-z.x0, 1f - z.x1); val ddy = dy.coerceIn(-z.y0, 1f - z.y1)
            return z.copy(x0 = z.x0 + ddx, y0 = z.y0 + ddy, x1 = z.x1 + ddx, y1 = z.y1 + ddy, poly = z.poly?.map { Pt(it.x + ddx, it.y + ddy) })
        }
        0 -> { x0 += dx; y0 += dy }
        1 -> { x1 += dx; y0 += dy }
        2 -> { x1 += dx; y1 += dy }
        3 -> { x0 += dx; y1 += dy }
        4 -> y0 += dy
        5 -> x1 += dx
        6 -> y1 += dy
        7 -> x0 += dx
    }
    x0 = x0.coerceIn(0f, z.x1 - m).coerceAtMost(x1 - m); y0 = y0.coerceIn(0f, z.y1 - m).coerceAtMost(y1 - m)
    x1 = x1.coerceIn(x0 + m, 1f); y1 = y1.coerceIn(y0 + m, 1f)
    // (el contorno libre se escala con el recuadro)
    val poly = z.poly?.map { p ->
        Pt(x0 + (p.x - z.x0) / max(1e-4f, z.x1 - z.x0) * (x1 - x0), y0 + (p.y - z.y0) / max(1e-4f, z.y1 - z.y0) * (y1 - y0))
    }
    return z.copy(x0 = x0, y0 = y0, x1 = x1, y1 = y1, poly = poly)
}

/** Recuadro bajo [p]: primero las zonas a mano, el más pequeño que lo contiene. */
private fun hitItem(items: List<ZoneItem>, p: Offset): ZoneItem? =
    items.filter { p.x in it.zone.x0..it.zone.x1 && p.y in it.zone.y0..it.zone.y1 }
        .minWithOrNull(compareBy<ZoneItem>({ if (it.kind == ZoneKind.MANUAL) 0 else 1 }, { abs((it.zone.x1 - it.zone.x0) * (it.zone.y1 - it.zone.y0)) }))

/** Pantalla -> normalizado de la imagen (deshaciendo zoom y desplazamiento, origen en el centro). */
private fun toNorm(p: Offset, size: IntSize, zoom: Float, pan: Offset, rect: Rect): Offset? {
    if (rect.width <= 0f || rect.height <= 0f) return null
    val center = Offset(size.width / 2f, size.height / 2f)
    val q = center + (p - center - pan) / zoom
    val nx = (q.x - rect.left) / rect.width; val ny = (q.y - rect.top) / rect.height
    if (nx < -0.05f || nx > 1.05f || ny < -0.05f || ny > 1.05f) return null
    return Offset(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
}
