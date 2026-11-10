package com.scannerpromax.ui.album

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.scannerpromax.album.AlbumGeometry
import com.scannerpromax.album.AlbumOps
import com.scannerpromax.album.AlbumPage
import com.scannerpromax.album.Box as SlotBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Gestos de la hoja del álbum: dibujar, seleccionar, mover y redimensionar en proporción. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h600dp-mdpi", application = Application::class)
class AlbumSheetViewTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var page by mutableStateOf(AlbumPage("p", 200f, 300f))
    private var selected by mutableStateOf<String?>(null)
    private var commits = 0

    private fun setUp() {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            // 400×600 px (mdpi) con margen de 18 px: la hoja de 200×300 mm queda a 1,88 px/mm
            Box(Modifier.fillMaxSize().background(Color.LightGray).testTag("sheet")) {
                AlbumSheetView(page, selected, null, emptyMap(), Color.Blue, onSelect = { selected = it }, onCommit = { page = it; commits++ })
            }
        }
        compose.waitForIdle()
    }

    /** mm de la hoja -> px de la vista (misma cuenta que la vista, sin zoom). */
    private fun px(xMm: Double, yMm: Double): Offset {
        val s = minOf((400f - 36f) / 200f, (600f - 36f) / 300f)
        val ox = (400f - 200f * s) / 2f; val oy = (600f - 300f * s) / 2f
        return Offset((ox + xMm * s).toFloat(), (oy + yMm * s).toFloat())
    }

    @Test
    fun drawSelectMoveAndResizeProportionally() {
        setUp()
        // dibujar de (20, 30) a (90, 80) mm (lejos de las guías del imán)
        compose.onNodeWithTag("sheet").performTouchInput {
            down(px(20.0, 30.0)); moveTo(px(60.0, 55.0)); moveTo(px(90.0, 80.0)); up()
        }
        compose.waitForIdle()
        assertEquals(1, page.slots.size)
        val s = page.slots[0]
        assertEquals(s.id, selected)
        val b = AlbumGeometry.box(s, page)
        assertEquals(70.0, b.w, 1.5); assertEquals(50.0, b.h, 1.5)

        // tocar fuera quita la selección; tocar dentro la recupera
        compose.onNodeWithTag("sheet").performTouchInput { click(px(150.0, 250.0)) }
        compose.waitForIdle()
        assertEquals(null, selected)
        compose.onNodeWithTag("sheet").performTouchInput { click(px(55.0, 55.0)) }
        compose.waitForIdle()
        assertEquals(s.id, selected)

        // mover el centro de (55, 55) a (90, 200)
        compose.onNodeWithTag("sheet").performTouchInput {
            down(px(55.0, 55.0)); moveTo(px(70.0, 120.0)); moveTo(px(90.0, 200.0)); up()
        }
        compose.waitForIdle()
        val m = AlbumGeometry.box(page.slots[0], page)
        assertEquals(90.0, m.cx, 1.5); assertEquals(200.0, m.cy, 1.5)
        assertEquals(b.w, m.w, 0.01)

        // esquina inferior derecha hacia fuera: crece en proporción y la esquina opuesta no se mueve
        val br = m.corners()[2]; val tl = m.corners()[0]
        compose.onNodeWithTag("sheet").performTouchInput {
            down(px(br.x, br.y)); moveTo(px(br.x + 10, br.y + 2)); moveTo(px(br.x + 20, br.y + 5)); up()
        }
        compose.waitForIdle()
        val r = AlbumGeometry.box(page.slots[0], page)
        assertEquals(b.w / b.h, r.w / r.h, 1e-3)
        assertTrue(r.w > m.w + 5)
        assertEquals(tl.x, r.corners()[0].x, 0.01); assertEquals(tl.y, r.corners()[0].y, 0.01)
        assertEquals(3, commits)

        // captura para revisar a ojo
        val (p2, _) = AlbumOps.addSlot(page, SlotBox(150.0, 240.0, 54.0, 85.6, 12.0))
        compose.runOnUiThread { page = p2 }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.let { File(it, "album_hoja.png").outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
    }
}
