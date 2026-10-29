package com.scannerpromax.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.scannerpromax.ai.jev.PageIssue
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.DocumentActions
import com.scannerpromax.ui.home.HomeContent
import com.scannerpromax.ui.home.homeFolders
import com.scannerpromax.ui.review.ReviewContent
import com.scannerpromax.ui.review.ReviewNoticeList
import com.scannerpromax.ui.review.ViewerIssueBanner
import com.scannerpromax.ui.review.ViewerTopBar
import com.scannerpromax.ui.review.reviewSubtitle
import com.scannerpromax.ui.theme.EscanerTheme
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.PerfConfig
import com.scannerpromax.ui.theme.ui
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Capturas de los avisos de "Mejorar con Jev" (duplicados, carpeta sugerida, calidad, carpetas en Inicio). Solo con
 * -Pscreenshots: ./gradlew testDebugUnitTest -Pscreenshots=DIR --tests '*JevIdeasScreenshot*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = Application::class)
class JevIdeasScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File = File(System.getProperty("screenshots.out") ?: "build/screenshots").apply { mkdirs() }

    private val pages = List(5) { i ->
        Page(
            id = "p$i", originalFile = "p$i.jpg", width = 2480, height = 3508,
            issues = if (i == 1) listOf(PageIssue.GLARE) else null,
            dupOf = if (i == 3) "p2" else null,
        )
    }
    private val doc = Document("d", "Escaneo 29 oct", 0, 0, pages = pages, suggestedCategory = "factura")

    @Test fun documento_avisos_claro() = shot("jev_documento_avisos_claro", dark = false) { Review() }

    @Test fun documento_avisos_oscuro() = shot("jev_documento_avisos_oscuro", dark = true) { Review() }

    @Test fun visor_aviso_calidad() = shot("jev_visor_aviso_calidad", dark = true, statusColor = Color.Black) { Viewer() }

    @Test fun inicio_carpetas_claro() = shot("jev_inicio_carpetas_claro", dark = false) { Home() }

    @Composable
    private fun Review() {
        ReviewContent(
            title = doc.title,
            subtitle = reviewSubtitle(pages, ScanMode.DOCUMENT, "contrato"),
            pages = pages,
            loading = false,
            showEmpty = false,
            gridState = rememberLazyGridState(),
            draggingKey = null,
            displacementFor = { null },
            gridGestures = Modifier,
            docMenuGroups = listOf(listOf(MoreMenuItem("Renombrar", AppIcons.Rename) {})),
            onBack = {},
            pageThumbnail = { _, n, m -> FakePage(n * 3, m.padding(horizontal = 12.dp, vertical = 14.dp), dark = MaterialTheme.ui.isDark) },
            onPageClick = {},
            onAddCamera = {}, onAddGallery = {}, onFilter = {}, onExport = {},
            snackbar = remember { SnackbarHostState() },
            notices = { ReviewNoticeList(pages, doc, {}, {}, {}, {}) },
        )
    }

    @Composable
    private fun Viewer() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FakePage(7, Modifier.align(Alignment.Center).fillMaxWidth().height(520.dp).padding(28.dp).background(Color.White).padding(24.dp), dark = false)
            ViewerTopBar(rebuiltLabel = null, onToggleRebuilt = {}, onClose = {}, onEdit = {}, onRetake = {})
            ViewerIssueBanner(
                listOf(PageIssue.GLARE, PageIssue.SHADOW), onRetake = {}, onDismiss = {},
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp, start = 16.dp, end = 16.dp),
            )
        }
    }

    @Composable
    private fun Home() {
        val docs = listOf(
            Document("1", "Factura luz octubre", 0, 0, category = "factura", pages = pages.take(1)),
            Document("2", "Factura agua", 0, 0, category = "factura", pages = pages.take(2)),
            Document("3", "Contrato alquiler", 0, 0, category = "contrato", pages = pages.take(3)),
            Document("4", "Apuntes química", 0, 0, category = "apuntes", pages = pages.take(1)),
        )
        HomeContent(
            documents = docs, filtered = docs.take(2), query = "", onQueryChange = {}, gridMode = true, onToggleGrid = {},
            actionsFor = { DocumentActions(onRename = {}, onDuplicate = {}, onSharePdf = {}, onDelete = {}) },
            thumbnail = { d, m -> FakePage(d.id.hashCode(), m.padding(12.dp), dark = false) },
            onOpenDocument = {}, onScan = {}, onPickImages = {}, onCompressPdf = {}, onSettings = {},
            snackbar = remember { SnackbarHostState() },
            folders = homeFolders(docs), folder = "factura",
        )
    }

    @Composable
    private fun FakePage(seed: Int, modifier: Modifier, dark: Boolean) {
        val line = if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)
        val title = if (dark) Color(0xFF636366) else Color(0xFFC7C7CC)
        Canvas(modifier) {
            val w = size.width; val u = size.height / 22f
            fun bar(y: Float, frac: Float, h: Float, c: Color) = drawRoundRect(c, Offset(0f, y), Size(w * frac, h), CornerRadius(h / 2, h / 2))
            bar(0f, 0.62f, u, title)
            var y = u * 2.4f
            repeat(12) { i -> bar(y, (0.94f - ((seed shr (i % 6)) and 3) * 0.06f).coerceAtLeast(0.3f), u * 0.5f, line); y += u * 1.25f }
        }
    }

    private fun shot(name: String, dark: Boolean, statusColor: Color? = null, content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalPerf provides PerfConfig(reduceMotion = true)) {
                EscanerTheme(darkTheme = dark, dynamicColor = false) {
                    Column(Modifier.fillMaxSize().background(statusColor ?: MaterialTheme.ui.groupedBackground)) {
                        Spacer(Modifier.fillMaxWidth().height(47.dp))
                        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
