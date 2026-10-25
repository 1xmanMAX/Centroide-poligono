package com.scannerpromax.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.scannerpromax.data.AppSettings
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageSize
import com.scannerpromax.ui.components.CheckRow
import com.scannerpromax.ui.components.DocumentActions
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.MenuScrim
import com.scannerpromax.ui.components.MoreMenuPanel
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.SheetHeader
import com.scannerpromax.ui.home.HomeContent
import com.scannerpromax.ui.home.homeMenuGroups
import com.scannerpromax.ui.settings.AboutInfo
import com.scannerpromax.ui.settings.SettingsContent
import com.scannerpromax.ui.settings.SettingsPage
import com.scannerpromax.ui.theme.EscanerTheme
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.PerfConfig
import com.scannerpromax.ui.theme.SheetShape
import com.scannerpromax.ui.theme.ui
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Capturas del rediseño estilo Apple (fase 1: Inicio y Ajustes) con datos falsos, en claro y oscuro.
 * NO corren en testDebugUnitTest normal (ver app/build.gradle.kts): solo con
 *
 *     ./gradlew testDebugUnitTest -Pscreenshots=/tmp/claude-0/bench/rediseno --tests '*screenshots*'
 *
 * Pantalla de iPhone 12–15 (390×844 dp, 3x). Robolectric no tiene barra de estado: se simula una franja de 47 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = Application::class)
class RedesignScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File = File(System.getProperty("screenshots.out") ?: "build/screenshots").apply { mkdirs() }

    // ------------------------------------------------------------------------------------- Inicio

    @Test fun inicio_cuadricula_claro() = shot("inicio_cuadricula_claro", dark = false) { Home(gridMode = true) }

    @Test fun inicio_cuadricula_oscuro() = shot("inicio_cuadricula_oscuro", dark = true) { Home(gridMode = true) }

    @Test fun inicio_lista_claro() = shot("inicio_lista_claro", dark = false) { Home(gridMode = false) }

    @Test fun inicio_lista_oscuro() = shot("inicio_lista_oscuro", dark = true) { Home(gridMode = false) }

    @Test fun inicio_menu_claro() = shot("inicio_menu_claro", dark = false) { HomeWithMenu() }

    @Test fun inicio_menu_oscuro() = shot("inicio_menu_oscuro", dark = true) { HomeWithMenu() }

    @Test fun inicio_vacio_claro() = shot("inicio_vacio_claro", dark = false) { Home(gridMode = true, docs = emptyList()) }

    @Test fun inicio_busqueda_sin_resultados_oscuro() =
        shot("inicio_busqueda_sin_resultados_oscuro", dark = true) { Home(gridMode = true, query = "factura luz", filtered = emptyList()) }

    // ------------------------------------------------------------------------------------- Ajustes

    @Test fun ajustes_claro() = shot("ajustes_claro", dark = false) { Settings(SettingsPage.MAIN) }

    @Test fun ajustes_oscuro() = shot("ajustes_oscuro", dark = true) { Settings(SettingsPage.MAIN) }

    @Test fun ajustes_exportacion_claro() = shot("ajustes_exportacion_claro", dark = false) { Settings(SettingsPage.EXPORT) }

    @Test fun ajustes_apariencia_oscuro() = shot("ajustes_apariencia_oscuro", dark = true) { Settings(SettingsPage.APPEARANCE) }

    @Test fun ajustes_acerca_de_claro() = shot("ajustes_acerca_de_claro", dark = false) { Settings(SettingsPage.ABOUT) }

    @Test fun ajustes_hoja_calidad_claro() = shot("ajustes_hoja_calidad_claro", dark = false) { SettingsWithSheet() }

    @Test fun ajustes_hoja_calidad_oscuro() = shot("ajustes_hoja_calidad_oscuro", dark = true) { SettingsWithSheet() }

    // ------------------------------------------------------------------------------------- piezas

    @Composable
    private fun Home(
        gridMode: Boolean,
        docs: List<Document> = fakeDocs,
        query: String = "",
        filtered: List<Document> = docs,
    ) {
        HomeContent(
            documents = docs,
            filtered = filtered,
            query = query,
            onQueryChange = {},
            gridMode = gridMode,
            onToggleGrid = {},
            actionsFor = { DocumentActions(onRename = {}, onDuplicate = {}, onSharePdf = {}, onDelete = {}) },
            thumbnail = { doc, modifier -> FakePage(doc.id.hashCode(), modifier) },
            onOpenDocument = {},
            onScan = {},
            onPickImages = {},
            onCompressPdf = {},
            onSettings = {},
            snackbar = remember { SnackbarHostState() },
        )
    }

    /** El menú ••• real es una ventana emergente (no sale en la captura de la raíz): se dibuja su panel encima. */
    @Composable
    private fun HomeWithMenu() {
        Box(Modifier.fillMaxSize()) {
            Home(gridMode = true)
            MenuScrim(visible = true)
            MoreMenuPanel(
                groups = homeMenuGroups(gridMode = true, onToggleGrid = {}, onPickImages = {}, onCompressPdf = {}, onSettings = {}),
                onItemClick = {},
                // Debajo del botón ••• (8 dp + 40 dp + 8 dp) y alineado a su borde derecho.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 58.dp, end = 20.dp),
            )
        }
    }

    @Composable
    private fun Settings(page: SettingsPage) {
        SettingsContent(
            settings = fakeSettings,
            about = fakeAbout,
            onUpdate = {},
            onBack = {},
            onOpenAiModels = {},
            onOpenCloudAi = {},
            initialPage = page,
        )
    }

    /** La hoja real (ModalBottomSheet) es otra ventana: se reproduce su contenido sobre Exportación con el velo. */
    @Composable
    private fun SettingsWithSheet() {
        val ui = MaterialTheme.ui
        Box(Modifier.fillMaxSize()) {
            Settings(SettingsPage.EXPORT)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(ui.groupedBackground, SheetShape)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 34.dp),
            ) {
                SheetHeader(title = "Calidad", onCancel = {})
                Spacer(Modifier.height(16.dp))
                InsetGroup(separatorInset = SeparatorInsetPlain) {
                    ExportQuality.entries.forEach { q ->
                        item { CheckRow(title = q.label, subtitle = q.hint, selected = q == ExportQuality.HIGH, onClick = {}) }
                    }
                }
            }
        }
    }

    /** Página de documento falsa (como en la maqueta: título gris, líneas y un bloque). */
    @Composable
    private fun FakePage(seed: Int, modifier: Modifier) {
        val ui = MaterialTheme.ui
        val title = if (ui.isDark) Color(0xFF636366) else Color(0xFFC7C7CC)
        val line = if (ui.isDark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)
        val block = if (ui.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
        Canvas(modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            val w = size.width
            val u = size.height / 22f
            fun bar(y: Float, frac: Float, h: Float, c: Color) =
                drawRoundRect(c, Offset(0f, y), Size(w * frac, h), CornerRadius(h / 2, h / 2))
            bar(0f, 0.70f, u * 1.1f, title)
            val fracs = listOf(0.92f, 0.86f, 0.90f, 0.60f)
            var y = u * 2.6f
            fracs.forEachIndexed { i, f -> bar(y, (f - ((seed shr i) and 3) * 0.04f).coerceAtLeast(0.3f), u * 0.7f, line); y += u * 1.6f }
            drawRoundRect(block, Offset(0f, y + u * 0.4f), Size(w, u * 3.6f), CornerRadius(u * 0.5f, u * 0.5f))
            y += u * 5f
            bar(y, 0.80f, u * 0.7f, line)
            bar(y + u * 1.6f, 0.66f, u * 0.7f, line)
        }
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        // La actividad de prueba trae barra de acciones: fuera (la app no la tiene).
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalPerf provides PerfConfig(reduceMotion = true)) {
                EscanerTheme(darkTheme = dark, dynamicColor = false) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.ui.groupedBackground)) {
                        // Barra de estado simulada (Robolectric no tiene).
                        Spacer(Modifier.fillMaxWidth().height(47.dp))
                        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
        // captureToImage() espera un redibujado que Robolectric no entrega: se dibuja la vista raíz en un Bitmap.
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        val now = System.currentTimeMillis()

        fun doc(id: String, title: String, pages: Int, ago: Long) = Document(
            id = id,
            title = title,
            createdAt = now - ago,
            updatedAt = now - ago,
            pages = List(pages) { Page(id = "$id-$it", originalFile = "p$it.jpg", width = 2480, height = 3508) },
        )

        val fakeDocs = listOf(
            doc("a", "Contrato de alquiler", 4, 2 * HOUR),
            doc("b", "Apuntes de cálculo", 12, DAY + 3 * HOUR),
            doc("c", "Boleta Plaza Vea", 1, DAY + 6 * HOUR),
            doc("d", "DNI", 2, 3 * DAY),
            doc("e", "Movimiento de almacén", 3, 8 * DAY),
            doc("f", "Pizarra reunión", 1, 11 * DAY),
            doc("g", "Factura de luz", 2, 15 * DAY),
            doc("h", "Receta médica", 1, 20 * DAY),
        )

        val fakeSettings = AppSettings(
            autoCapture = true,
            autoRemoveLines = true,
            pdfPageSize = PageSize.A4,
            exportQuality = ExportQuality.HIGH,
        )

        val fakeAbout = AboutInfo(
            version = "1.0.0",
            device = "Samsung SM-A155M · Android 14",
            performance = "Gama media/alta · máxima calidad",
            processor = "8 núcleos · 256 MB por app",
            workingResolution = "12,0 MP",
        )
    }
}
