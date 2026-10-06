package com.scannerpromax.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Rectángulo donde se dibuja una imagen ajustada (FIT) dentro de un contenedor con margen. */
internal fun fitRect(container: IntSize, imgW: Int, imgH: Int, padding: Float): Rect {
    if (container.width <= 0 || container.height <= 0 || imgW <= 0 || imgH <= 0) return Rect.Zero
    val aw = container.width - 2 * padding
    val ah = container.height - 2 * padding
    val s = min(aw / imgW, ah / imgH)
    val w = imgW * s; val h = imgH * s
    val l = (container.width - w) / 2f
    val t = (container.height - h) / 2f
    return Rect(l, t, l + w, t + h)
}

/** ¿Cuadrilátero convexo y no degenerado? */
internal fun isConvexQuad(p: List<Offset>): Boolean {
    if (p.size != 4) return false
    var sign = 0
    for (i in 0 until 4) {
        val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (kotlin.math.abs(cross) < 1e-3f) return false
        val s = if (cross > 0) 1 else -1
        if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
}

/**
 * Herramienta de recorte: imagen original con 4 esquinas arrastrables + puntos medios de cada lado,
 * lupa ampliada mientras se arrastra (precisión al píxel) y aviso visual si el cuadrilátero no es válido.
 *
 * Rendimiento: durante el arrastre las esquinas viven en un estado LOCAL que solo se lee en la fase de
 * dibujo (Canvas), así cada movimiento del dedo solo redibuja (sin recomponer esta herramienta ni la pantalla
 * del editor). [onQuadChange] se llama al soltar. Las rutas (Path) se reutilizan entre frames y la lupa dibuja
 * una región del ImageBitmap ya cargado (no crea bitmaps por frame).
 *
 * [quad] en coordenadas de [image] (null = página completa). [onQuadChange] recibe coordenadas de [image].
 */
@Composable
internal fun CropTool(
    image: ImageBitmap,
    quad: Quad?,
    onQuadChange: (Quad) -> Unit,
    primary: Color,
    secondary: Color,
    error: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pad = with(density) { 26.dp.toPx() }
    val touchRadius = with(density) { 40.dp.toPx() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableIntStateOf(-1) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    /** Esquinas durante el arrastre (null = usar [quad]). Solo se lee al dibujar y en los gestos. */
    var dragPts by remember { mutableStateOf<List<Pt>?>(null) }
    // Rutas reutilizadas en cada frame (sin asignaciones en el bucle de dibujo).
    val quadPath = remember { Path() }
    val maskPath = remember { Path() }
    val loupeClip = remember { Path() }

    val w = image.width.toFloat()
    val h = image.height.toFloat()
    val pts: List<Pt> = (quad ?: Quad.full(image.width, image.height)).points()
    val currentPts by rememberUpdatedState(pts)
    val onChange by rememberUpdatedState(onQuadChange)
    fun livePts(): List<Pt> = dragPts ?: currentPts
    fun commitDrag() {
        dragPts?.let { onChange(Quad.of(it)) }
        dragPts = null
        active = -1
    }

    val rect = fitRect(boxSize, image.width, image.height, pad)
    val scale = if (w > 0f) rect.width / w else 1f
    val currentRect by rememberUpdatedState(rect)
    val currentScale by rememberUpdatedState(scale)

    fun toView(p: Pt) = Offset(currentRect.left + p.x * currentScale, currentRect.top + p.y * currentScale)

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(image) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val p = livePts()
                        // 0..3 esquinas, 4..7 puntos medios (lado i -> i+1). Las esquinas tienen prioridad.
                        val candidates = ArrayList<Pair<Int, Float>>(8)
                        for (i in 0 until 4) candidates += i to (toView(p[i]) - pos).getDistance()
                        for (i in 0 until 4) {
                            val a = toView(p[i]); val b = toView(p[(i + 1) % 4])
                            candidates += (4 + i) to (Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f) - pos).getDistance() * 1.15f
                        }
                        val best = candidates.minByOrNull { it.second }
                        active = if (best != null && best.second <= touchRadius * 1.3f) best.first else -1
                        finger = pos
                    },
                    onDrag = { change, amount ->
                        val idx = active
                        if (idx < 0) return@detectDragGestures
                        change.consume()
                        finger = change.position
                        val s = currentScale.coerceAtLeast(1e-4f)
                        val dx = amount.x / s
                        val dy = amount.y / s
                        val p = livePts().toMutableList()
                        if (idx < 4) {
                            p[idx] = Pt((p[idx].x + dx).coerceIn(0f, w), (p[idx].y + dy).coerceIn(0f, h))
                        } else {
                            // Lado: se desplaza en la dirección normal al lado (los extremos se mueven juntos)
                            val i = idx - 4
                            val j = (i + 1) % 4
                            val ex = p[j].x - p[i].x; val ey = p[j].y - p[i].y
                            val len = hypot(ex, ey).coerceAtLeast(1e-3f)
                            val nx = -ey / len; val ny = ex / len
                            val dn = dx * nx + dy * ny
                            p[i] = Pt((p[i].x + dn * nx).coerceIn(0f, w), (p[i].y + dn * ny).coerceIn(0f, h))
                            p[j] = Pt((p[j].x + dn * nx).coerceIn(0f, w), (p[j].y + dn * ny).coerceIn(0f, h))
                        }
                        dragPts = p
                    },
                    onDragEnd = { commitDrag() },
                    onDragCancel = { commitDrag() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (rect.width <= 0f) return@Canvas
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            )
            val v = livePts().map { toView(it) }
            val valid = isConvexQuad(v)
            val path = quadPath.apply {
                reset()
                moveTo(v[0].x, v[0].y)
                for (i in 1 until 4) lineTo(v[i].x, v[i].y)
                close()
            }
            // Oscurece lo que queda fuera del recorte
            val mask = maskPath.apply {
                reset()
                fillType = PathFillType.EvenOdd
                addRect(rect)
                addPath(path)
            }
            drawPath(mask, Color.Black.copy(alpha = 0.5f))
            val strokeBrush = if (valid) Brush.linearGradient(listOf(primary, secondary), v[0], v[2])
            else Brush.linearGradient(listOf(error, error))
            drawPath(path, strokeBrush, style = Stroke(width = 2.5f * density.density, join = StrokeJoin.Round))
            drawGrid(v)

            // Puntos medios (píldoras orientadas con el lado)
            for (i in 0 until 4) {
                val a = v[i]; val b = v[(i + 1) % 4]
                val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                val isActive = active == 4 + i
                val pw = (if (isActive) 30f else 24f) * density.density
                val ph = 8f * density.density
                rotate(angle, pivot = mid) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(mid.x - pw / 2f, mid.y - ph / 2f),
                        size = Size(pw, ph),
                        cornerRadius = CornerRadius(ph / 2f),
                    )
                    drawRoundRect(
                        color = if (valid) primary else error,
                        topLeft = Offset(mid.x - pw / 2f, mid.y - ph / 2f),
                        size = Size(pw, ph),
                        cornerRadius = CornerRadius(ph / 2f),
                        style = Stroke(width = 1.5f * density.density),
                    )
                }
            }
            // Esquinas
            for (i in 0 until 4) {
                val isActive = active == i
                val r = (if (isActive) 15f else 11f) * density.density
                drawCircle(Color.Black.copy(alpha = 0.25f), radius = r + 3f * density.density, center = v[i])
                drawCircle(Color.White, radius = r, center = v[i])
                drawCircle(if (valid) primary else error, radius = r * 0.55f, center = v[i])
            }

            // Lupa
            val idx = active
            if (idx >= 0) {
                val target: Offset = if (idx < 4) v[idx] else {
                    val a = v[idx - 4]; val b = v[(idx - 3) % 4]
                    Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                }
                drawLoupe(image, rect, scale, target, finger, if (valid) primary else error, clip = loupeClip)
            }
        }
    }
}

private fun DrawScope.drawGrid(v: List<Offset>) {
    // Líneas a tercios dentro del cuadrilátero (ayudan a alinear)
    val c = Color.White.copy(alpha = 0.35f)
    for (k in 1..2) {
        val t = k / 3f
        val a = lerp(v[0], v[1], t); val b = lerp(v[3], v[2], t)
        drawLine(c, a, b, strokeWidth = 1f * density)
        val d = lerp(v[0], v[3], t); val e = lerp(v[1], v[2], t)
        drawLine(c, d, e, strokeWidth = 1f * density)
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** Lupa circular con aumento ×2.6 sobre el punto que se arrastra; se coloca lejos del dedo. */
internal fun DrawScope.drawLoupe(
    image: ImageBitmap,
    rect: Rect,
    scale: Float,
    target: Offset,
    finger: Offset,
    accent: Color,
    /** Radio del pincel en px de pantalla (0 = sin anillo). Se dibuja ampliado dentro de la lupa. */
    brushRadiusPx: Float = 0f,
    /** Path reutilizable para el recorte circular (evita crear uno por frame). */
    clip: Path? = null,
) {
    val radius = 58f * density
    val margin = 16f * density
    val zoom = 2.6f
    // Esquina superior opuesta al dedo
    val left = finger.x > size.width / 2f
    val cx = if (left) margin + radius else size.width - margin - radius
    var cy = margin + radius
    if (finger.y < cy + radius + margin && ((left && finger.x < cx + radius * 2) || (!left && finger.x > cx - radius * 2))) {
        cy = size.height - margin - radius
    }
    val center = Offset(cx, cy)

    // Punto en píxeles de imagen y tamaño de la región fuente
    val ix = (target.x - rect.left) / scale
    val iy = (target.y - rect.top) / scale
    val srcHalf = radius / (scale * zoom)
    var sl = ix - srcHalf; var st = iy - srcHalf
    var sr = ix + srcHalf; var sb = iy + srcHalf
    // Destino proporcional, recortado a los límites de la imagen
    val k = radius / srcHalf
    var dl = center.x - radius; var dt = center.y - radius
    var dr = center.x + radius; var db = center.y + radius
    if (sl < 0f) { dl += -sl * k; sl = 0f }
    if (st < 0f) { dt += -st * k; st = 0f }
    if (sr > image.width) { dr -= (sr - image.width) * k; sr = image.width.toFloat() }
    if (sb > image.height) { db -= (sb - image.height) * k; sb = image.height.toFloat() }

    val circle = (clip ?: Path()).apply {
        reset()
        addOval(Rect(center, radius))
    }
    drawCircle(Color.Black.copy(alpha = 0.35f), radius = radius + 4f * density, center = center + Offset(0f, 2f * density))
    clipPath(circle) {
        drawRect(Color(0xFF101010), topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2))
        val sw = (sr - sl).roundToInt(); val sh = (sb - st).roundToInt()
        val dw = (dr - dl).roundToInt(); val dh = (db - dt).roundToInt()
        if (sw > 0 && sh > 0 && dw > 0 && dh > 0) {
            drawImage(
                image = image,
                srcOffset = IntOffset(sl.roundToInt(), st.roundToInt()),
                srcSize = IntSize(min(sw, image.width - sl.roundToInt()), min(sh, image.height - st.roundToInt())),
                dstOffset = IntOffset(dl.roundToInt(), dt.roundToInt()),
                dstSize = IntSize(dw, dh),
            )
        }
        val arm = 12f * density
        drawLine(accent, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = 2f * density, cap = StrokeCap.Round)
        drawLine(accent, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = 2f * density, cap = StrokeCap.Round)
        if (brushRadiusPx > 0f) {
            drawCircle(Color.White, radius = brushRadiusPx * zoom, center = center, style = Stroke(width = 1.5f * density))
        }
    }
    drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 3f * density))
    drawCircle(accent, radius = radius + 1.5f * density, center = center, style = Stroke(width = 1.5f * density))
}
