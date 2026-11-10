package com.scannerpromax.album

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Render de una hoja con recuadros (píxeles reales con los gráficos nativos de Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class AlbumRendererTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun saveJpeg(dir: File, rel: String, w: Int, h: Int, draw: (Canvas) -> Unit): String {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b))
        val f = File(dir, rel).apply { parentFile!!.mkdirs() }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        b.recycle()
        return rel
    }

    private fun close(c: Int, r: Int, g: Int, b: Int, tol: Int = 40) =
        abs(Color.red(c) - r) < tol && abs(Color.green(c) - g) < tol && abs(Color.blue(c) - b) < tol

    @Test
    fun stickersFillTheirSlotsAndNothingElse() {
        val dir = tmp.newFolder("a")
        // cromo: mitad izquierda roja, mitad derecha azul (para ver el giro)
        val rel = saveJpeg(dir, "stk/s.jpg", 200, 100) { c ->
            c.drawColor(Color.RED)
            c.drawRect(100f, 0f, 200f, 100f, Paint().apply { color = Color.BLUE })
        }
        val st = Sticker("src/x.jpg", com.scannerpromax.domain.Quad.full(1, 1), rel, 200, 100)
        var page = AlbumPage("p", 100f, 100f)
        page = AlbumOps.addSlot(page, Box(30.0, 30.0, 40.0, 20.0)).first
        page = AlbumOps.addSlot(page, Box(70.0, 75.0, 40.0, 20.0, 90.0)).first // girado: 20 × 40 en la hoja
        page = page.copy(slots = page.slots.map { it.copy(sticker = st) })
        val out = AlbumRenderer.render(page, dir, 4.0, { _, _ -> null })
        try {
            assertEquals(400, out.width); assertEquals(400, out.height)
            // recuadro 1 (10..50 × 20..40 mm): rojo a la izquierda, azul a la derecha
            assertTrue(close(out.getPixel(15 * 4, 30 * 4), 255, 0, 0))
            assertTrue(close(out.getPixel(45 * 4, 30 * 4), 0, 0, 255))
            // fuera de los recuadros: blanco
            assertTrue(close(out.getPixel(5 * 4, 5 * 4), 255, 255, 255))
            assertTrue(close(out.getPixel(30 * 4, 45 * 4), 255, 255, 255))
            // recuadro 2 girado 90° horario (60..80 × 55..95): la mitad izquierda del cromo queda ARRIBA
            assertTrue(close(out.getPixel(70 * 4, 60 * 4), 255, 0, 0))
            assertTrue(close(out.getPixel(70 * 4, 90 * 4), 0, 0, 255))
            assertTrue(close(out.getPixel(85 * 4, 75 * 4), 255, 255, 255))
        } finally {
            out.recycle()
        }
    }

    @Test
    fun fitLeavesMarginsAndInnerRotation() {
        val dir = tmp.newFolder("b")
        val rel = saveJpeg(dir, "stk/q.jpg", 100, 100) { it.drawColor(Color.GREEN) }
        val st = Sticker("src/x.jpg", com.scannerpromax.domain.Quad.full(1, 1), rel, 100, 100, fit = StickerFit.FIT, rotation = 90)
        val page = AlbumOps.addSlot(AlbumPage("p", 100f, 100f), Box(50.0, 50.0, 60.0, 20.0)).first
            .let { p -> p.copy(slots = p.slots.map { it.copy(sticker = st) }) }
        val out = AlbumRenderer.render(page, dir, 4.0, { _, _ -> null })
        try {
            assertTrue(close(out.getPixel(50 * 4, 50 * 4), 0, 255, 0))
            // FIT de un cuadrado en 60×20: 20×20 en el centro; a 25 mm del centro queda el blanco de la hoja
            assertTrue(close(out.getPixel(25 * 4, 50 * 4), 255, 255, 255))
        } finally {
            out.recycle()
        }
    }

    @Test
    fun backgroundIsRotatedWithThePage() {
        val dir = tmp.newFolder("c")
        // fondo vertical 100×200: arriba amarillo, abajo negro
        val bg = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.YELLOW)
                drawRect(0f, 100f, 100f, 200f, Paint().apply { color = Color.BLACK })
            }
        }
        val page = AlbumGeometry.rotatePage(AlbumPage("p", 50f, 100f, SheetBackground(BackgroundKind.IMAGE, "bg/x.jpg", null, 100f, 200f)))
        assertEquals(100f, page.widthMm)
        var asked: Pair<Int, Int>? = null
        val out = AlbumRenderer.render(page, dir, 2.0, { w, h -> asked = w to h; bg })
        try {
            // pide el fondo SIN girar al tamaño que ocupa: 100×200 px
            assertEquals(100 to 200, asked)
            assertEquals(200, out.width); assertEquals(100, out.height)
            // girado 90° horario: lo de arriba (amarillo) pasa a la derecha
            assertTrue(close(out.getPixel(180, 50), 255, 255, 0))
            assertTrue(close(out.getPixel(20, 50), 0, 0, 0))
        } finally {
            out.recycle()
        }
    }
}
