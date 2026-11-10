package com.scannerpromax.album

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Transformación afín 2D: x' = a·x + c·y + e ; y' = b·x + d·y + f (la misma convención que la matriz de PDF y la de
 * android.graphics.Matrix con setValues(a, c, e, b, d, f, 0, 0, 1)).
 */
data class Affine(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = 1.0,
    val e: Double = 0.0, val f: Double = 0.0,
) {
    fun mapX(x: Double, y: Double) = a * x + c * y + e
    fun mapY(x: Double, y: Double) = b * x + d * y + f
    fun map(p: P): P = P(mapX(p.x, p.y), mapY(p.x, p.y))

    /** Primero esta, luego [o]: o ∘ this. */
    fun then(o: Affine) = Affine(
        a = o.a * a + o.c * b, b = o.b * a + o.d * b,
        c = o.a * c + o.c * d, d = o.b * c + o.d * d,
        e = o.a * e + o.c * f + o.e, f = o.b * e + o.d * f + o.f,
    )

    fun translate(tx: Double, ty: Double) = then(Affine(e = tx, f = ty))
    fun scale(sx: Double, sy: Double = sx) = then(Affine(a = sx, d = sy))

    /** Giro horario (en coordenadas con la y hacia abajo) de [deg] grados alrededor del origen. */
    fun rotate(deg: Double): Affine {
        val r = Math.toRadians(deg)
        val cs = cos(r).let { if (abs(it) < 1e-12) 0.0 else it }
        val sn = sin(r).let { if (abs(it) < 1e-12) 0.0 else it }
        return then(Affine(a = cs, b = sn, c = -sn, d = cs))
    }

    fun inverse(): Affine {
        val det = a * d - b * c
        require(abs(det) > 1e-18) { "Transformación no invertible" }
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Affine(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    /** Valores para android.graphics.Matrix.setValues. */
    fun toMatrixValues(): FloatArray = floatArrayOf(a.toFloat(), c.toFloat(), e.toFloat(), b.toFloat(), d.toFloat(), f.toFloat(), 0f, 0f, 1f)
}

data class P(val x: Double, val y: Double)

/** Recuadro en mm (espacio físico de la hoja, y hacia abajo): centro, tamaño y giro horario en grados. */
data class Box(val cx: Double, val cy: Double, val w: Double, val h: Double, val angle: Double = 0.0) {
    /** Esquinas TL, TR, BR, BL (en el orden del rectángulo sin girar). */
    fun corners(): List<P> {
        val t = localToSheet()
        return listOf(P(-w / 2, -h / 2), P(w / 2, -h / 2), P(w / 2, h / 2), P(-w / 2, h / 2)).map { t.map(it) }
    }

    /** Del espacio local (centro del recuadro, sin girar) a la hoja. */
    fun localToSheet(): Affine = Affine().rotate(angle).translate(cx, cy)

    fun toLocal(p: P): P = localToSheet().inverse().map(p)

    fun contains(p: P, margin: Double = 0.0): Boolean {
        val l = toLocal(p)
        return abs(l.x) <= w / 2 + margin && abs(l.y) <= h / 2 + margin
    }

    /** Caja alineada que contiene el recuadro girado: (izq, arriba, der, abajo). */
    fun bounds(): DoubleArray {
        val c = corners()
        return doubleArrayOf(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })
    }
}

/** Línea guía de alineación (vertical en x = [pos] si [vertical], si no horizontal en y = [pos]), en mm. */
data class Guide(val vertical: Boolean, val pos: Double)

object AlbumGeometry {

    /** Tamaño mínimo de un recuadro (mm). */
    const val MIN_SIZE_MM = 5.0

    fun box(slot: AlbumSlot, page: AlbumPage): Box = box(slot, page.widthMm.toDouble(), page.heightMm.toDouble())

    fun box(slot: AlbumSlot, wMm: Double, hMm: Double) =
        Box(slot.cx * wMm, slot.cy * hMm, slot.w * wMm, slot.h * hMm, slot.angle.toDouble())

    /** Escribe [b] (mm) en [slot] con las coordenadas normalizadas de una hoja de [wMm]×[hMm]. */
    fun withBox(slot: AlbumSlot, b: Box, wMm: Double, hMm: Double): AlbumSlot = slot.copy(
        cx = (b.cx / wMm).toFloat(), cy = (b.cy / hMm).toFloat(),
        w = (b.w / wMm).toFloat(), h = (b.h / hMm).toFloat(),
        angle = normalizeAngle(b.angle).toFloat(),
    )

    fun withBox(slot: AlbumSlot, b: Box, page: AlbumPage) = withBox(slot, b, page.widthMm.toDouble(), page.heightMm.toDouble())

    /** Ángulo en (-180, 180]. */
    fun normalizeAngle(deg: Double): Double {
        var a = deg % 360.0
        if (a <= -180.0) a += 360.0
        if (a > 180.0) a -= 360.0
        return if (abs(a) < 1e-9) 0.0 else a
    }

    /** ¿El recuadro está alineado con la hoja (0/90/180/270)? */
    fun isAxisAligned(angle: Double, tol: Double = 0.01): Boolean {
        val m = ((angle % 90.0) + 90.0) % 90.0
        return m < tol || m > 90.0 - tol
    }

    // ------------------------------------------------------------------------------------- edición

    /** Recuadro nuevo dibujado arrastrando de [a] a [b] (mm), recortado a la hoja; null si es demasiado pequeño. */
    fun drawn(a: P, b: P, wMm: Double, hMm: Double, minSize: Double = MIN_SIZE_MM): Box? {
        val l = min(a.x, b.x).coerceIn(0.0, wMm); val r = max(a.x, b.x).coerceIn(0.0, wMm)
        val t = min(a.y, b.y).coerceIn(0.0, hMm); val bt = max(a.y, b.y).coerceIn(0.0, hMm)
        if (r - l < minSize || bt - t < minSize) return null
        return Box((l + r) / 2, (t + bt) / 2, r - l, bt - t)
    }

    fun moved(b: Box, dx: Double, dy: Double) = b.copy(cx = b.cx + dx, cy = b.cy + dy)

    /**
     * Redimensiona [b] arrastrando su esquina [corner] (0 TL, 1 TR, 2 BR, 3 BL) hasta [pointer] (mm, hoja); la esquina
     * opuesta queda fija. [proportional] = conserva la proporción (proyección sobre la diagonal). Funciona con el
     * recuadro girado (todo se calcula en su espacio local).
     */
    fun resized(b: Box, corner: Int, pointer: P, proportional: Boolean, minSize: Double = MIN_SIZE_MM): Box {
        val sx = if (corner == 1 || corner == 2) 1.0 else -1.0
        val sy = if (corner == 2 || corner == 3) 1.0 else -1.0
        val q = b.toLocal(pointer)
        val ax = -sx * b.w / 2; val ay = -sy * b.h / 2
        var nw = max(minSize, (q.x - ax) * sx)
        var nh = max(minSize, (q.y - ay) * sy)
        if (proportional) {
            // escala = proyección del vector (nw, nh) sobre la diagonal (w, h)
            val s0 = (nw * b.w + nh * b.h) / (b.w * b.w + b.h * b.h)
            val s = max(s0, minSize / min(b.w, b.h))
            nw = b.w * s; nh = b.h * s
        }
        val lcx = ax + sx * nw / 2; val lcy = ay + sy * nh / 2
        val c = b.localToSheet().map(P(lcx, lcy))
        return Box(c.x, c.y, nw, nh, b.angle)
    }

    /** Escala alrededor del centro manteniendo la proporción (pellizco o porcentaje). */
    fun scaled(b: Box, factor: Double, minSize: Double = MIN_SIZE_MM): Box {
        val f = max(factor, minSize / min(b.w, b.h))
        return b.copy(w = b.w * f, h = b.h * f)
    }

    fun rotated90(b: Box, clockwise: Boolean = true) = b.copy(angle = normalizeAngle(b.angle + if (clockwise) 90.0 else -90.0))

    /** Mantiene el centro del recuadro dentro de la hoja (nunca se pierde fuera de la vista). */
    fun keepInside(b: Box, wMm: Double, hMm: Double) = b.copy(cx = b.cx.coerceIn(0.0, wMm), cy = b.cy.coerceIn(0.0, hMm))

    /** Recuadro de [wMm]×[hMm] mm centrado en [center] y reducido si no cabe en la hoja (misma proporción). */
    fun sized(center: P, wMm: Double, hMm: Double, sheetW: Double, sheetH: Double, angle: Double = 0.0): Box {
        val k = min(1.0, min(sheetW * 0.96 / wMm, sheetH * 0.96 / hMm))
        return Box(center.x, center.y, wMm * k, hMm * k, angle)
    }

    // ------------------------------------------------------------------------------------- imán / guías

    /**
     * Imán al mover: alinea los bordes izquierdo/centro/derecho (y arriba/centro/abajo) de la caja del recuadro con los
     * bordes y el centro de la hoja y con los de los [others], si quedan a menos de [threshold] mm. Devuelve el recuadro
     * ajustado y las guías que se deben dibujar.
     */
    fun snapMove(b: Box, others: List<Box>, wMm: Double, hMm: Double, threshold: Double): Pair<Box, List<Guide>> {
        val bb = b.bounds()
        val xs = mutableListOf(0.0, wMm / 2, wMm)
        val ys = mutableListOf(0.0, hMm / 2, hMm)
        for (o in others) {
            val ob = o.bounds()
            xs += listOf(ob[0], (ob[0] + ob[2]) / 2, ob[2])
            ys += listOf(ob[1], (ob[1] + ob[3]) / 2, ob[3])
        }
        val (dx, gx) = bestSnap(doubleArrayOf(bb[0], (bb[0] + bb[2]) / 2, bb[2]), xs, threshold)
        val (dy, gy) = bestSnap(doubleArrayOf(bb[1], (bb[1] + bb[3]) / 2, bb[3]), ys, threshold)
        val guides = buildList {
            gx?.let { add(Guide(true, it)) }
            gy?.let { add(Guide(false, it)) }
        }
        return b.copy(cx = b.cx + dx, cy = b.cy + dy) to guides
    }

    /** Imán de un punto (esquina que se arrastra) a los bordes y centros de la hoja y de los [others]. */
    fun snapPoint(p: P, others: List<Box>, wMm: Double, hMm: Double, threshold: Double): Pair<P, List<Guide>> {
        val xs = mutableListOf(0.0, wMm / 2, wMm)
        val ys = mutableListOf(0.0, hMm / 2, hMm)
        for (o in others) {
            val ob = o.bounds()
            xs += listOf(ob[0], (ob[0] + ob[2]) / 2, ob[2])
            ys += listOf(ob[1], (ob[1] + ob[3]) / 2, ob[3])
        }
        val (dx, gx) = bestSnap(doubleArrayOf(p.x), xs, threshold)
        val (dy, gy) = bestSnap(doubleArrayOf(p.y), ys, threshold)
        val guides = buildList {
            gx?.let { add(Guide(true, it)) }
            gy?.let { add(Guide(false, it)) }
        }
        return P(p.x + dx, p.y + dy) to guides
    }

    /** Menor desplazamiento que lleva alguno de [own] a algún [targets] (≤ [threshold]); (0, null) si ninguno. */
    private fun bestSnap(own: DoubleArray, targets: List<Double>, threshold: Double): Pair<Double, Double?> {
        var best = Double.MAX_VALUE
        var bestTarget: Double? = null
        for (o in own) for (t in targets) {
            val d = t - o
            if (abs(d) <= threshold && abs(d) < abs(best)) { best = d; bestTarget = t }
        }
        return if (bestTarget == null) 0.0 to null else best to bestTarget
    }

    // ------------------------------------------------------------------------------------- hoja

    /**
     * Gira la hoja 90° (horario o antihorario) con todo lo que lleva: el fondo ([AlbumPage.rotation]), el tamaño (ancho
     * y alto se intercambian) y cada recuadro (centro y ángulo), como si se girara la hoja de papel.
     */
    fun rotatePage(page: AlbumPage, clockwise: Boolean = true): AlbumPage {
        val w = page.widthMm.toDouble(); val h = page.heightMm.toDouble()
        val slots = page.slots.map { s ->
            val b = box(s, w, h)
            val nb = if (clockwise) Box(h - b.cy, b.cx, b.w, b.h, b.angle + 90.0) else Box(b.cy, w - b.cx, b.w, b.h, b.angle - 90.0)
            withBox(s, nb, h, w)
        }
        val rot = ((page.rotation + if (clockwise) 90 else 270) % 360)
        return page.copy(widthMm = page.heightMm, heightMm = page.widthMm, rotation = rot, slots = slots)
    }

    /**
     * Tamaño de hoja (mm) para un fondo de [srcW]×[srcH] (sin girar): PDF con su tamaño real ([unitMm] = mm por
     * unidad, 25,4/72 para pt); imágenes con el lado largo de un A4 (297 mm) y su proporción.
     */
    fun sheetSizeFor(srcW: Double, srcH: Double, unitMm: Double? = null): Pair<Double, Double> {
        if (unitMm != null) return srcW * unitMm to srcH * unitMm
        val long = max(srcW, srcH)
        val k = 297.0 / long
        return srcW * k to srcH * k
    }

    /**
     * Del espacio del fondo SIN girar ([srcW]×[srcH], y hacia abajo) a la hoja visible ([wMm]×[hMm] mm): gira
     * [rotation] grados (horario) y escala para ocupar la hoja entera.
     */
    fun backgroundToSheet(srcW: Double, srcH: Double, rotation: Int, wMm: Double, hMm: Double): Affine {
        val r = ((rotation % 360) + 360) % 360
        val rw = if (r % 180 == 0) srcW else srcH
        val rh = if (r % 180 == 0) srcH else srcW
        return Affine().translate(-srcW / 2, -srcH / 2).rotate(r.toDouble()).scale(wMm / rw, hMm / rh).translate(wMm / 2, hMm / 2)
    }

    /**
     * Del cromo (píxeles [imgW]×[imgH], y hacia abajo) a la hoja (mm): girado [rotation] dentro del recuadro, escalado
     * para rellenarlo ([StickerFit.COVER]) o encajarlo ([StickerFit.FIT]) y centrado; luego el giro del recuadro.
     * Con COVER lo que sobra se recorta con el contorno del recuadro.
     */
    fun stickerToSheet(imgW: Double, imgH: Double, rotation: Int, fit: StickerFit, b: Box): Affine {
        val r = ((rotation % 360) + 360) % 360
        val rw = if (r % 180 == 0) imgW else imgH
        val rh = if (r % 180 == 0) imgH else imgW
        val k = if (fit == StickerFit.COVER) max(b.w / rw, b.h / rh) else min(b.w / rw, b.h / rh)
        return Affine().translate(-imgW / 2, -imgH / 2).rotate(r.toDouble()).scale(k).then(b.localToSheet())
    }

    /**
     * Proporción con la que rectificar un cromo: la estimada de la foto ([estimated] = ancho/alto) salvo que se parezca
     * (±[tol]) a la del recuadro ([slotRatio], en cualquiera de sus dos orientaciones), en cuyo caso se usa la del
     * recuadro (un DNI en un recuadro ID-1 queda exacto, sin bandas).
     */
    fun stickerRatio(estimated: Double, slotRatio: Double, tol: Double = 0.12): Double {
        for (r in doubleArrayOf(slotRatio, 1.0 / slotRatio)) {
            if (abs(estimated / r - 1.0) <= tol) return r
        }
        return estimated
    }
}
