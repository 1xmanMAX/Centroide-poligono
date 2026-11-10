package com.scannerpromax.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AlbumGeometryTest {

    private val eps = 1e-6

    private fun near(a: P, b: P, tol: Double = 1e-6) = abs(a.x - b.x) < tol && abs(a.y - b.y) < tol

    @Test
    fun affineComposeAndInverse() {
        val m = Affine().translate(-3.0, 4.0).rotate(30.0).scale(2.0, 0.5).translate(10.0, -7.0)
        val p = P(1.25, -2.5)
        assertTrue(near(p, m.inverse().map(m.map(p))))
        // giro horario de 90° con la y hacia abajo: derecha -> abajo
        assertTrue(near(Affine().rotate(90.0).map(P(1.0, 0.0)), P(0.0, 1.0)))
    }

    @Test
    fun cornersAndContainsWithRotation() {
        val b = Box(50.0, 40.0, 20.0, 10.0, 90.0)
        val bb = b.bounds()
        assertEquals(45.0, bb[0], eps); assertEquals(55.0, bb[2], eps)
        assertEquals(30.0, bb[1], eps); assertEquals(50.0, bb[3], eps)
        // TL del rectángulo local queda arriba a la derecha tras girar 90° horario
        assertTrue(near(b.corners()[0], P(55.0, 30.0)))
        assertTrue(b.contains(P(50.0, 48.0)))
        assertFalse(b.contains(P(58.0, 40.0)))
    }

    @Test
    fun proportionalResizeKeepsAspectAndAnchor() {
        val b = Box(100.0, 100.0, 80.0, 40.0)
        val r = AlbumGeometry.resized(b, 2, P(160.0, 130.0), proportional = true)
        assertEquals(2.0, r.w / r.h, 1e-9)
        assertTrue(near(r.corners()[0], b.corners()[0]))
        assertTrue(r.w > b.w)
        // achicar proporcionalmente desde TL: BR queda fijo
        val s = AlbumGeometry.resized(b, 0, P(100.0, 100.0), proportional = true)
        assertEquals(2.0, s.w / s.h, 1e-9)
        assertTrue(near(s.corners()[2], b.corners()[2]))
        assertTrue(s.w < b.w)
    }

    @Test
    fun proportionalResizeOnRotatedBox() {
        val b = Box(100.0, 100.0, 60.0, 30.0, 33.0)
        val opposite = b.corners()[3] // BL fijo al arrastrar TR
        // TR escalado ×1,4 respecto a BL, en el espacio local girado
        val target = b.localToSheet().map(P(-30.0 + 60.0 * 1.4, 15.0 - 30.0 * 1.4))
        val r = AlbumGeometry.resized(b, 1, target, proportional = true)
        assertEquals(2.0, r.w / r.h, 1e-9)
        assertEquals(84.0, r.w, 1e-6)
        assertTrue(near(r.corners()[3], opposite))
        assertEquals(33.0, r.angle, eps)
    }

    @Test
    fun freeResizeAndMinimum() {
        val b = Box(50.0, 50.0, 40.0, 20.0)
        val r = AlbumGeometry.resized(b, 2, P(80.0, 100.0), proportional = false)
        assertEquals(50.0, r.w, eps); assertEquals(60.0, r.h, eps)
        assertTrue(near(r.corners()[0], P(30.0, 40.0)))
        // cruzar la esquina opuesta deja el tamaño mínimo, sin invertirse
        val tiny = AlbumGeometry.resized(b, 2, P(0.0, 0.0), proportional = false)
        assertEquals(AlbumGeometry.MIN_SIZE_MM, tiny.w, eps)
        assertEquals(AlbumGeometry.MIN_SIZE_MM, tiny.h, eps)
        val tinyP = AlbumGeometry.resized(b, 2, P(0.0, 0.0), proportional = true)
        assertEquals(2.0, tinyP.w / tinyP.h, 1e-9)
        assertEquals(AlbumGeometry.MIN_SIZE_MM, tinyP.h, 1e-9)
    }

    @Test
    fun normalizedCoordinatesRoundTrip() {
        val page = AlbumPage("p", 210f, 297f)
        val b = Box(70.0, 120.0, 85.6, 54.0, -12.5)
        val s = AlbumGeometry.withBox(AlbumSlot("s", 0f, 0f, 0f, 0f), b, page)
        assertEquals(70.0 / 210, s.cx.toDouble(), 1e-6)
        assertEquals(54.0 / 297, s.h.toDouble(), 1e-6)
        val back = AlbumGeometry.box(s, page)
        assertEquals(b.w, back.w, 1e-4); assertEquals(b.h, back.h, 1e-4)
        assertEquals(b.cx, back.cx, 1e-4); assertEquals(b.angle, back.angle, 1e-4)
        assertEquals(170.0, AlbumGeometry.normalizeAngle(-190.0), eps)
        assertEquals(180.0, AlbumGeometry.normalizeAngle(-180.0), eps)
        assertTrue(AlbumGeometry.isAxisAligned(270.0))
        assertFalse(AlbumGeometry.isAxisAligned(10.0))
    }

    @Test
    fun rotatePageMovesSlotsWithTheSheet() {
        val page = AlbumPage("p", 210f, 297f, slots = listOf(
            AlbumGeometry.withBox(AlbumSlot("a", 0f, 0f, 0f, 0f), Box(30.0, 40.0, 50.0, 20.0), 210.0, 297.0),
        ))
        val r = AlbumGeometry.rotatePage(page, clockwise = true)
        assertEquals(297f, r.widthMm); assertEquals(210f, r.heightMm); assertEquals(90, r.rotation)
        val b = AlbumGeometry.box(r.slots[0], r)
        // (x, y) -> (H - y, x): el recuadro de arriba a la izquierda pasa arriba a la derecha
        assertEquals(297.0 - 40.0, b.cx, 1e-3); assertEquals(30.0, b.cy, 1e-3)
        assertEquals(50.0, b.w, 1e-3); assertEquals(20.0, b.h, 1e-3); assertEquals(90.0, b.angle, 1e-3)
        var q = page
        repeat(4) { q = AlbumGeometry.rotatePage(q) }
        assertEquals(0, q.rotation)
        val b0 = AlbumGeometry.box(page.slots[0], page); val b4 = AlbumGeometry.box(q.slots[0], q)
        assertEquals(b0.cx, b4.cx, 1e-3); assertEquals(b0.cy, b4.cy, 1e-3); assertEquals(0.0, b4.angle, 1e-3)
        val back = AlbumGeometry.rotatePage(r, clockwise = false)
        val bb = AlbumGeometry.box(back.slots[0], back)
        assertEquals(30.0, bb.cx, 1e-3); assertEquals(40.0, bb.cy, 1e-3); assertEquals(0, back.rotation)
    }

    @Test
    fun snapMoveToSheetAndOtherSlots() {
        val other = Box(100.0, 100.0, 40.0, 40.0) // x = 80..120
        // borde izquierdo a 1,5 mm del canto de la hoja
        val (s1, g1) = AlbumGeometry.snapMove(Box(16.5, 60.0, 30.0, 10.0), listOf(other), 210.0, 297.0, 2.0)
        assertEquals(15.0, s1.cx, eps)
        assertTrue(g1.any { it.vertical && it.pos == 0.0 })
        // borde derecho junto al izquierdo del otro recuadro (80)
        val (s2, g2) = AlbumGeometry.snapMove(Box(64.0, 200.0, 30.0, 10.0), listOf(other), 210.0, 297.0, 2.0)
        assertEquals(65.0, s2.cx, eps)
        assertTrue(g2.any { it.vertical && abs(it.pos - 80.0) < eps })
        // lejos de todo: sin cambios ni guías
        val far = Box(41.2, 50.0, 30.0, 10.0)
        val (s3, g3) = AlbumGeometry.snapMove(far, emptyList(), 210.0, 297.0, 0.5)
        assertEquals(far, s3)
        assertTrue(g3.isEmpty())
        // centro de la hoja
        val (s4, _) = AlbumGeometry.snapMove(Box(104.0, 148.0, 10.0, 10.0), emptyList(), 210.0, 297.0, 2.0)
        assertEquals(105.0, s4.cx, eps); assertEquals(148.5, s4.cy, eps)
        val (p, gp) = AlbumGeometry.snapPoint(P(119.0, 3.0), listOf(other), 210.0, 297.0, 1.5)
        assertEquals(120.0, p.x, eps); assertEquals(3.0, p.y, eps)
        assertEquals(1, gp.size)
    }

    @Test
    fun drawnBoxIsClippedAndRejectsTiny() {
        val b = AlbumGeometry.drawn(P(200.0, 10.0), P(250.0, 40.0), 210.0, 297.0)
        assertNotNull(b)
        assertEquals(10.0, b!!.w, eps)
        assertNull(AlbumGeometry.drawn(P(10.0, 10.0), P(12.0, 40.0), 210.0, 297.0))
    }

    @Test
    fun stickerPlacementCoverAndFit() {
        val slot = Box(50.0, 50.0, 40.0, 20.0)
        val cover = AlbumGeometry.stickerToSheet(100.0, 100.0, 0, StickerFit.COVER, slot)
        assertTrue(near(cover.map(P(0.0, 0.0)), P(30.0, 30.0)))
        assertTrue(near(cover.map(P(100.0, 100.0)), P(70.0, 70.0)))
        val fit = AlbumGeometry.stickerToSheet(100.0, 100.0, 0, StickerFit.FIT, slot)
        assertTrue(near(fit.map(P(0.0, 0.0)), P(40.0, 40.0)))
        // imagen vertical girada 90° en un recuadro horizontal de su misma proporción: lo llena exacto
        val rot = AlbumGeometry.stickerToSheet(50.0, 100.0, 90, StickerFit.COVER, slot)
        val pts = listOf(P(0.0, 0.0), P(50.0, 0.0), P(50.0, 100.0), P(0.0, 100.0)).map { rot.map(it) }
        assertEquals(30.0, pts.minOf { it.x }, 1e-6); assertEquals(70.0, pts.maxOf { it.x }, 1e-6)
        assertEquals(40.0, pts.minOf { it.y }, 1e-6); assertEquals(60.0, pts.maxOf { it.y }, 1e-6)
        assertTrue(near(pts[0], P(70.0, 40.0)))
    }

    @Test
    fun backgroundMappingCoversSheet() {
        val m = AlbumGeometry.backgroundToSheet(2000.0, 1000.0, 90, 100.0, 200.0)
        assertTrue(near(m.map(P(0.0, 0.0)), P(100.0, 0.0)))
        assertTrue(near(m.map(P(2000.0, 1000.0)), P(0.0, 200.0)))
        val id = AlbumGeometry.backgroundToSheet(300.0, 400.0, 0, 30.0, 40.0)
        assertTrue(near(id.map(P(300.0, 400.0)), P(30.0, 40.0)))
    }

    @Test
    fun stickerRatioSnapsToSlot() {
        assertEquals(85.6 / 54.0, AlbumGeometry.stickerRatio(1.52, 85.6 / 54.0), eps)
        assertEquals(54.0 / 85.6, AlbumGeometry.stickerRatio(0.66, 85.6 / 54.0), eps)
        assertEquals(1.0, AlbumGeometry.stickerRatio(1.0, 85.6 / 54.0), eps)
    }

    @Test
    fun historyUndoRedo() {
        val p0 = listOf(AlbumPage("a", 210f, 297f))
        val h = AlbumHistory(p0)
        assertFalse(h.canUndo)
        val p1 = listOf(AlbumGeometry.rotatePage(p0[0]))
        assertTrue(h.push(p1))
        assertFalse(h.push(p1))
        assertEquals(p0, h.undo())
        assertEquals(p1, h.redo())
        h.undo()
        h.push(listOf(p0[0].copy(widthMm = 100f)))
        assertFalse(h.canRedo)
    }

    @Test
    fun opsDuplicateMoveAndReplaceBackground() {
        val (page, s) = AlbumOps.addSlot(AlbumPage("p", 210f, 297f), Box(50.0, 50.0, 85.6, 54.0))
        val (p2, copy) = AlbumOps.duplicateSlot(page, s.id)!!
        assertEquals(2, p2.slots.size)
        assertEquals(56.0, AlbumGeometry.box(copy, p2).cx, 1e-3)
        val pages = listOf(p2, AlbumPage("q", 100f, 100f))
        assertEquals(listOf("q", "p"), AlbumOps.movePage(pages, "p", 1).map { it.id })
        // fondo nuevo más pequeño: los recuadros conservan la proporción física
        val r = AlbumOps.replaceBackground(p2, SheetBackground(), 105.0, 148.5)
        val b = AlbumGeometry.box(r.slots[0], r)
        assertEquals(85.6 / 54.0, b.w / b.h, 1e-4)
        assertEquals(42.8, b.w, 1e-3)
    }
}
