package com.scannerpromax.ui.album

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.album.AlbumGeometry
import com.scannerpromax.album.AlbumOps
import com.scannerpromax.album.AlbumPage
import com.scannerpromax.album.Box
import com.scannerpromax.album.Guide
import com.scannerpromax.album.P
import com.scannerpromax.album.StickerFit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Transformación de la vista: px por mm y origen de la hoja en la vista. */
private class SheetMapping(val s: Float, val ox: Float, val oy: Float) {
    fun toView(p: P) = Offset((ox + p.x * s).toFloat(), (oy + p.y * s).toFloat())
    fun toMm(o: Offset) = P(((o.x - ox) / s).toDouble(), ((o.y - oy) / s).toDouble())
}

private sealed interface Op {
    data object None : Op
    data class Draw(val start: P, val end: P) : Op
    data class Move(val slotId: String, val base: Box, val start: P) : Op
    data class Resize(val slotId: String, val base: Box, val corner: Int) : Op
}

/**
 * Hoja del álbum editable: dibujar recuadros arrastrando sobre una zona vacía, tocar para seleccionar, arrastrar para
 * mover, esquinas para redimensionar (proporcional salvo que el recuadro diga lo contrario), dos dedos para zoom y
 * desplazamiento. Imán a los bordes/centro de la hoja y a los otros recuadros con guías.
 *
 * Mientras dura un gesto la hoja se dibuja con su copia local; al soltar se entrega a [onCommit] (un paso de deshacer).
 * [background] = vista previa del fondo SIN girar; [stickers] = vistas previas por archivo de cromo.
 */
@Composable
internal fun AlbumSheetView(
    page: AlbumPage,
    selectedId: String?,
    background: ImageBitmap?,
    stickers: Map<String, ImageBitmap>,
    accent: Color,
    onSelect: (String?) -> Unit,
    onCommit: (AlbumPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val handleRadius = with(density) { 26.dp.toPx() }
    val snapPx = with(density) { 9.dp.toPx() }
    val margin = with(density) { 18.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(page.id) { mutableFloatStateOf(1f) }
    var pan by remember(page.id) { mutableStateOf(Offset.Zero) }
    /** Copia local de la hoja durante un gesto (null = usar [page]). */
    var live by remember { mutableStateOf<AlbumPage?>(null) }
    var drawRect by remember { mutableStateOf<Pair<P, P>?>(null) }
    var guides by remember { mutableStateOf<List<Guide>>(emptyList()) }

    val pageState by rememberUpdatedState(page)
    val selectedState by rememberUpdatedState(selectedId)
    val onSelectState by rememberUpdatedState(onSelect)
    val onCommitState by rememberUpdatedState(onCommit)

    fun mapping(): SheetMapping {
        val p = pageState
        val vw = viewSize.width.toFloat(); val vh = viewSize.height.toFloat()
        if (vw <= 0f || vh <= 0f) return SheetMapping(1f, 0f, 0f)
        val fit = min((vw - 2 * margin) / p.widthMm, (vh - 2 * margin) / p.heightMm).coerceAtLeast(0.01f)
        val s = fit * zoom
        return SheetMapping(s, (vw - p.widthMm * s) / 2f + pan.x, (vh - p.heightMm * s) / 2f + pan.y)
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(page.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val base = pageState
                    val m0 = mapping()
                    val w = base.widthMm.toDouble(); val h = base.heightMm.toDouble()
                    val startMm = m0.toMm(down.position)
                    val sel = base.slots.firstOrNull { it.id == selectedState }
                    var op: Op = Op.None
                    if (sel != null) {
                        val corners = AlbumGeometry.box(sel, base).corners().map { m0.toView(it) }
                        val idx = corners.indices.minByOrNull { (corners[it] - down.position).getDistance() } ?: -1
                        if (idx >= 0 && (corners[idx] - down.position).getDistance() <= handleRadius) {
                            op = Op.Resize(sel.id, AlbumGeometry.box(sel, base), idx)
                        }
                    }
                    if (op == Op.None) {
                        // el recuadro de encima primero (último dibujado)
                        val hit = base.slots.lastOrNull { AlbumGeometry.box(it, base).contains(startMm) }
                        op = if (hit != null) Op.Move(hit.id, AlbumGeometry.box(hit, base), startMm) else Op.Draw(startMm, startMm)
                    }
                    var started = false
                    var multi = false
                    var total = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (!multi) { multi = true; live = null; drawRect = null; guides = emptyList() }
                            val z = event.calculateZoom()
                            val dPan = event.calculatePan()
                            val c = event.calculateCentroid(useCurrent = false)
                            val before = mapping()
                            val anchor = before.toMm(c)
                            val nz = (zoom * z).coerceIn(1f, 10f)
                            zoom = nz
                            val p = pageState
                            val vw = viewSize.width.toFloat(); val vh = viewSize.height.toFloat()
                            val fit = min((vw - 2 * margin) / p.widthMm, (vh - 2 * margin) / p.heightMm).coerceAtLeast(0.01f)
                            val s = fit * nz
                            pan = Offset(
                                (c.x + dPan.x - anchor.x * s - (vw - p.widthMm * s) / 2f).toFloat(),
                                (c.y + dPan.y - anchor.y * s - (vh - p.heightMm * s) / 2f).toFloat(),
                            )
                            if (nz <= 1.001f) pan = Offset.Zero
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (multi) continue
                        val ch = pressed.first()
                        total += ch.positionChange()
                        if (!started && total.getDistance() > touchSlop) started = true
                        if (!started) continue
                        ch.consume()
                        val m = mapping()
                        val pos = m.toMm(ch.position)
                        val thr = snapPx / m.s
                        when (val o = op) {
                            is Op.Draw -> {
                                val (end, g) = AlbumGeometry.snapPoint(pos, base.slots.map { AlbumGeometry.box(it, base) }, w, h, thr.toDouble())
                                drawRect = o.start to end
                                guides = g
                            }
                            is Op.Move -> {
                                if (selectedState != o.slotId) onSelectState(o.slotId)
                                val moved = AlbumGeometry.moved(o.base, pos.x - o.start.x, pos.y - o.start.y)
                                val others = base.slots.filter { it.id != o.slotId }.map { AlbumGeometry.box(it, base) }
                                val (snapped, g) = AlbumGeometry.snapMove(moved, others, w, h, thr.toDouble())
                                guides = g
                                val fin = AlbumGeometry.keepInside(snapped, w, h)
                                live = AlbumOps.updateSlot(listOf(base), base.id, o.slotId) { AlbumGeometry.withBox(it, fin, base) }.first()
                            }
                            is Op.Resize -> {
                                val slot = base.slots.first { it.id == o.slotId }
                                val others = base.slots.filter { it.id != o.slotId }.map { AlbumGeometry.box(it, base) }
                                val aligned = AlbumGeometry.isAxisAligned(o.base.angle)
                                val (pt, g) = if (aligned && !slot.keepAspect) AlbumGeometry.snapPoint(pos, others, w, h, thr.toDouble()) else pos to emptyList()
                                guides = g
                                val nb = AlbumGeometry.resized(o.base, o.corner, pt, proportional = slot.keepAspect)
                                live = AlbumOps.updateSlot(listOf(base), base.id, o.slotId) { AlbumGeometry.withBox(it, nb, base) }.first()
                            }
                            Op.None -> Unit
                        }
                    }
                    if (multi) return@awaitEachGesture
                    if (!started) {
                        // toque: seleccionar el recuadro tocado o quitar la selección
                        val hit = base.slots.lastOrNull { AlbumGeometry.box(it, base).contains(startMm) }
                        onSelectState(hit?.id)
                    } else {
                        when (val o = op) {
                            is Op.Draw -> {
                                val r = drawRect
                                drawRect = null
                                val nb = r?.let { AlbumGeometry.drawn(it.first, it.second, w, h) }
                                if (nb != null) {
                                    val (np, s) = AlbumOps.addSlot(base, nb)
                                    onCommitState(np)
                                    onSelectState(s.id)
                                }
                            }
                            is Op.Move, is Op.Resize -> live?.let { onCommitState(it) }
                            Op.None -> Unit
                        }
                    }
                    live = null
                    guides = emptyList()
                }
            },
    ) {
        if (viewSize.width == 0) return@Canvas
        val p = live ?: page
        val m = mapping()
        val sheetTl = m.toView(P(0.0, 0.0))
        val sheetSize = Size(p.widthMm * m.s, p.heightMm * m.s)
        // sombra suave y hoja
        drawRect(Color.Black.copy(alpha = 0.10f), sheetTl + Offset(0f, 3f), sheetSize)
        drawRect(Color.White, sheetTl, sheetSize)
        background?.let { drawBackground(it, p, m) }
        for (slot in p.slots) {
            val b = AlbumGeometry.box(slot, p)
            val c = m.toView(P(b.cx, b.cy))
            val bw = (b.w * m.s).toFloat(); val bh = (b.h * m.s).toFloat()
            val img = slot.sticker?.let { stickers[it.rendered] }
            withTransform({
                translate(c.x, c.y)
                rotate(b.angle.toFloat(), pivot = Offset.Zero)
            }) {
                if (img != null) {
                    val st = slot.sticker
                    clipRect(-bw / 2, -bh / 2, bw / 2, bh / 2) {
                        val r = ((st.rotation % 360) + 360) % 360
                        val rw = if (r % 180 == 0) img.width else img.height
                        val rh = if (r % 180 == 0) img.height else img.width
                        val k = if (st.fit == StickerFit.COVER) max(bw / rw, bh / rh) else min(bw / rw, bh / rh)
                        val dw = (img.width * k).roundToInt().coerceAtLeast(1)
                        val dh = (img.height * k).roundToInt().coerceAtLeast(1)
                        rotate(r.toFloat(), pivot = Offset.Zero) {
                            drawImage(img, dstOffset = IntOffset(-dw / 2, -dh / 2), dstSize = IntSize(dw, dh), filterQuality = FilterQuality.Medium)
                        }
                    }
                } else {
                    drawRect(accent.copy(alpha = 0.10f), Offset(-bw / 2, -bh / 2), Size(bw, bh))
                    drawRect(
                        accent.copy(alpha = 0.75f), Offset(-bw / 2, -bh / 2), Size(bw, bh),
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
                    )
                    val label = slot.label ?: "Escanear"
                    if (bw > 56.dp.toPx() && bh > 22.dp.toPx()) {
                        val layout = textMeasurer.measure(label, TextStyle(color = accent, fontSize = 12.sp))
                        if (layout.size.width < bw - 8) {
                            drawText(layout, topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f))
                        }
                    }
                }
            }
        }
        // la selección encima de todos los recuadros (sus asas no quedan tapadas)
        p.slots.firstOrNull { it.id == selectedId }?.let { drawSelection(AlbumGeometry.box(it, p), m, accent) }
        drawRect?.let { (a, b) ->
            val tl = m.toView(P(min(a.x, b.x), min(a.y, b.y)))
            val br = m.toView(P(max(a.x, b.x), max(a.y, b.y)))
            drawRect(accent.copy(alpha = 0.14f), tl, Size(br.x - tl.x, br.y - tl.y))
            drawRect(accent, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = 2.dp.toPx()))
            val wmm = abs(a.x - b.x); val hmm = abs(a.y - b.y)
            val layout = textMeasurer.measure("%.0f × %.0f mm".format(wmm, hmm), TextStyle(color = accent, fontSize = 12.sp))
            drawText(layout, topLeft = Offset(tl.x, (tl.y - layout.size.height - 4f).coerceAtLeast(0f)))
        }
        val magenta = Color(0xFFE5398F)
        for (g in guides) {
            if (g.vertical) {
                val x = m.toView(P(g.pos, 0.0)).x
                drawLine(magenta, Offset(x, sheetTl.y), Offset(x, sheetTl.y + sheetSize.height), strokeWidth = 1.dp.toPx())
            } else {
                val y = m.toView(P(0.0, g.pos)).y
                drawLine(magenta, Offset(sheetTl.x, y), Offset(sheetTl.x + sheetSize.width, y), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

/** Fondo (vista previa SIN girar) girado y escalado a la hoja. */
private fun DrawScope.drawBackground(img: ImageBitmap, p: AlbumPage, m: SheetMapping) {
    val r = ((p.rotation % 360) + 360) % 360
    val sw = p.widthMm * m.s; val sh = p.heightMm * m.s
    // tamaño del fondo SIN girar en la vista
    val uw = if (r % 180 == 0) sw else sh
    val uh = if (r % 180 == 0) sh else sw
    val c = m.toView(P(p.widthMm / 2.0, p.heightMm / 2.0))
    translate(c.x, c.y) {
        rotate(r.toFloat(), pivot = Offset.Zero) {
            drawImage(
                img,
                dstOffset = IntOffset((-uw / 2).roundToInt(), (-uh / 2).roundToInt()),
                dstSize = IntSize(uw.roundToInt().coerceAtLeast(1), uh.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

private fun DrawScope.drawSelection(b: Box, m: SheetMapping, accent: Color) {
    val c = b.corners().map { m.toView(it) }
    val path = Path().apply {
        moveTo(c[0].x, c[0].y)
        for (k in 1..3) lineTo(c[k].x, c[k].y)
        close()
    }
    drawPath(path, Color.White, style = Stroke(width = 4.dp.toPx()))
    drawPath(path, accent, style = Stroke(width = 2.dp.toPx()))
    for (pt in c) {
        drawCircle(Color.White, radius = 9.dp.toPx(), center = pt)
        drawCircle(accent, radius = 6.5.dp.toPx(), center = pt)
    }
}
