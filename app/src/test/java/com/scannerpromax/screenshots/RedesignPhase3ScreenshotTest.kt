package com.scannerpromax.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.ImageFormat
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfTextMode
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.domain.TextPlacement
import com.scannerpromax.ocr.LiveText
import com.scannerpromax.ocr.LiveTextMapping
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.export.ExportKind
import com.scannerpromax.ui.export.ExportSheetContent
import com.scannerpromax.ui.review.LiveTextHint
import com.scannerpromax.ui.review.LiveTextLayer
import com.scannerpromax.ui.review.LiveTextState
import com.scannerpromax.ui.review.ReviewContent
import com.scannerpromax.ui.review.ViewerPager
import com.scannerpromax.ui.review.ViewerTopBar
import com.scannerpromax.ui.review.ViewerHintAbovePager
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
 * Capturas de la fase 3 (Texto en vivo en el visor y modo selección de páginas). Solo con -Pscreenshots:
 * ./gradlew testDebugUnitTest -Pscreenshots=/tmp/claude-0/bench/rediseno --tests '*RedesignPhase3*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = Application::class)
class RedesignPhase3ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File = File(System.getProperty("screenshots.out") ?: "build/screenshots").apply { mkdirs() }

    // f4_: Documento sin ••• por página ni "Texto reconocido", barra repartida, visor con un único paginador.
    @Test fun documento_claro() = shot("f4_documento_claro", dark = false) { Review(selection = false) }

    @Test fun documento_oscuro() = shot("f4_documento_oscuro", dark = true) { Review(selection = false) }

    @Test fun documento_leyendo_ia() = shot("f4_documento_leyendo_ia_claro", dark = false) { Review(selection = false, ai = true) }

    @Test fun seleccion_claro() = shot("f4_documento_seleccion_claro", dark = false) { Review(selection = true) }

    @Test fun seleccion_oscuro() = shot("f4_documento_seleccion_oscuro", dark = true) { Review(selection = true) }

    @Test fun visor_texto() = shot("f4_visor_texto_seleccionado", dark = true, statusColor = Color.Black) { Viewer(zoom = 1f) }

    @Test fun visor_texto_zoom() = shot("f4_visor_texto_zoom", dark = true, statusColor = Color.Black) { Viewer(zoom = 2.2f) }

    @Test fun visor_reconociendo() = shot("f4_visor_reconociendo", dark = true, statusColor = Color.Black) { Viewer(zoom = 1f, recognizing = true) }

    @Test fun visor_reconstruido_pista() = shot("f4_visor_pista_reconstruido", dark = true, statusColor = Color.Black) { Viewer(zoom = 1f, hint = true, rebuilt = true) }

    @Test fun exportar_subconjunto() = shot("f3_exportar_3_de_10", dark = false, statusColor = Color(0xFF1C1C1E)) { Export() }

    // ------------------------------------------------------------------------------------- Documento

    private val reviewPages = List(6) { i ->
        Page(
            id = "p$i", originalFile = "p$i.jpg", width = 2480, height = 3508,
            edits = PageEdits(filter = if (i == 2) FilterType.BLACK_WHITE else FilterType.MAGIC),
            ocrText = "texto",
        )
    }

    @Composable
    private fun Review(selection: Boolean, ai: Boolean = false) {
        ReviewContent(
            title = "Examen parcial 2025-20",
            subtitle = reviewSubtitle(reviewPages, ScanMode.DOCUMENT),
            pages = reviewPages,
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
            selectionMode = selection,
            selectedIds = if (selection) setOf("p0", "p1", "p3") else emptySet(),
            selectionMenuGroups = listOf(listOf(MoreMenuItem("Volver a leer con IA (3)", AppIcons.Sparkles) {})),
            aiStatus = if (ai) "Leyendo con IA… 40 % · 6 páginas pendientes" else null,
        )
    }

    // ------------------------------------------------------------------------------------- Visor

    /** Escaneo falso con texto real dibujado y su OcrResult (cajas exactas, OCR a media resolución). */
    private fun fakeScan(): Pair<Bitmap, OcrResult> {
        val w = 1240; val h = 1754
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val ocrScale = 0.5f
        val blocks = ArrayList<OcrBlock>()
        var y = 200f
        fun block(lines: List<String>, size: Float, bold: Boolean, gapAfter: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(28, 28, 30)
                textSize = size
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.SERIF
            }
            val fm = paint.fontMetrics
            val space = paint.measureText(" ")
            val ocrLines = ArrayList<OcrLine>()
            for (text in lines) {
                var x = 110f
                val words = ArrayList<OcrWord>()
                for (word in text.split(' ')) {
                    val ww = paint.measureText(word)
                    c.drawText(word, x, y, paint)
                    words += OcrWord(word, OcrRect(x * ocrScale, (y + fm.ascent) * ocrScale, (x + ww) * ocrScale, (y + fm.descent) * ocrScale))
                    x += ww + space
                }
                val lb = OcrRect(words.first().box.left, words.first().box.top, words.last().box.right, words.first().box.bottom)
                ocrLines += OcrLine(text, lb, words)
                y += size * 1.45f
            }
            val bb = OcrRect(ocrLines.minOf { it.box.left }, ocrLines.first().box.top, ocrLines.maxOf { it.box.right }, ocrLines.last().box.bottom)
            blocks += OcrBlock(lines.joinToString("\n"), bb, ocrLines)
            y += gapAfter
        }
        block(listOf("CONTRATO DE ARRENDAMIENTO"), 44f, true, 30f)
        block(
            listOf(
                "En Lima, a 21 de octubre de 2026, reunidos de una parte",
                "D. Juan Pérez, con DNI 12345678, en calidad de arrendador,",
                "y de otra parte Dña. María López, con DNI 87654321, en",
                "calidad de arrendataria, acuerdan celebrar el presente",
                "contrato de arrendamiento con las siguientes cláusulas:",
            ),
            34f, false, 40f,
        )
        block(
            listOf(
                "PRIMERA.- Objeto. El arrendador cede en arrendamiento",
                "la vivienda situada en Av. Arequipa 1234, Lima, con",
                "todos sus servicios en funcionamiento.",
            ),
            34f, false, 40f,
        )
        block(
            listOf(
                "SEGUNDA.- Plazo. El contrato tendrá una duración de un",
                "año a partir de la fecha de su firma, prorrogable por",
                "acuerdo expreso de ambas partes.",
            ),
            34f, false, 0f,
        )
        val ocr = OcrResult(
            text = blocks.joinToString("\n\n") { it.text },
            imageWidth = (w * ocrScale).toInt(),
            imageHeight = (h * ocrScale).toInt(),
            blocks = blocks,
        )
        return bmp to ocr
    }

    @Composable
    private fun Viewer(zoom: Float, recognizing: Boolean = false, hint: Boolean = false, rebuilt: Boolean = false) {
        val scan = remember { fakeScan() }
        val (bmp, ocr) = scan
        val live = remember {
            LiveTextState().apply {
                load(ocr)
                if (!recognizing && !hint) {
                    // "D. Juan Pérez, con DNI 12345678," (palabras 6..12 en orden de lectura del bloque 2).
                    val ws = words
                    val a = ws.indexOfFirst { it.text == "Juan" }
                    val b = ws.indexOfFirst { it.text == "12345678," }
                    range = LiveText.Range(a, b)
                }
            }
        }
        val image = remember { bmp.asImageBitmap() }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val vw = constraints.maxWidth.toFloat()
                val vh = constraints.maxHeight.toFloat()
                // Centro de la ampliación: hacia el principio del párrafo seleccionado.
                val offX = if (zoom > 1f) vw * 0.55f else 0f
                val offY = if (zoom > 1f) vh * 0.42f else 0f
                Image(
                    image, null,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom
                        translationX = offX; translationY = offY
                    },
                    contentScale = ContentScale.Fit,
                )
                if (!recognizing) {
                    LiveTextLayer(
                        live,
                        { LiveTextMapping(vw, vh, bmp.width.toFloat(), bmp.height.toFloat(), ocr.imageWidth.toFloat(), ocr.imageHeight.toFloat(), zoom, offX, offY) },
                        {}, {},
                    )
                }
            }
            ViewerTopBar(rebuiltLabel = if (rebuilt) "Reconstruido" else null, onToggleRebuilt = {}, onClose = {}, onEdit = {})
            if (recognizing || hint) {
                LiveTextHint(
                    if (recognizing) "Reconociendo texto…" else "Mantén pulsado el texto para seleccionarlo",
                    loading = recognizing,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = ViewerHintAbovePager),
                )
            }
            ViewerPager(1, 10, {}, {}, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp))
        }
    }

    // ------------------------------------------------------------------------------------- Exportar

    @Composable
    private fun Export() {
        ExportSheetContent(
            title = "Examen parcial 2025-20",
            pages = reviewPages.take(3),
            totalPages = 10,
            kind = ExportKind.PDF, onKind = {},
            textMode = PdfTextMode.BUSCABLE, onTextMode = {},
            textPlacement = TextPlacement.AFTER_EACH_PAGE, onTextPlacement = {},
            quality = ExportQuality.BALANCED, onQuality = {},
            pageSize = PageSize.A4, onPageSize = {},
            imageFormat = ImageFormat.JPEG, onImageFormat = {},
            usePassword = false, onUsePassword = {},
            password = "", onPassword = {},
            showPassword = false, onShowPassword = {},
            fileName = "Examen parcial 2025-20", onFileName = {},
            estimateBytes = 940_000L,
            aiPages = 0,
            missingOcr = 0,
            lastSaved = null,
            canOpenSaved = false,
            onOpenSaved = {}, onShareSaved = {},
            ocrWarningCount = null, onRetry = {},
            busy = false,
            onCancel = {}, onSave = {}, onShare = {},
            thumbnail = { _, i, m -> FakePage(i + 2, m.padding(horizontal = 6.dp, vertical = 8.dp), dark = MaterialTheme.ui.isDark) },
        )
    }

    // ------------------------------------------------------------------------------------- piezas

    @Composable
    private fun FakePage(seed: Int, modifier: Modifier, dark: Boolean) {
        val title = if (dark) Color(0xFF636366) else Color(0xFFC7C7CC)
        val line = if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)
        val block = if (dark) Color(0xFF3A3A3C) else Color(0xFFF2F2F7)
        Canvas(modifier) {
            val w = size.width
            val u = size.height / 22f
            fun bar(y: Float, frac: Float, h: Float, c: Color) =
                drawRoundRect(c, Offset(0f, y), Size(w * frac, h), CornerRadius(h / 2, h / 2))
            bar(0f, 0.62f, u * 1.0f, title)
            val fracs = listOf(0.94f, 0.88f, 0.92f, 0.66f, 0.90f, 0.82f)
            var y = u * 2.4f
            fracs.forEachIndexed { i, f -> bar(y, (f - ((seed shr i) and 3) * 0.04f).coerceAtLeast(0.3f), u * 0.5f, line); y += u * 1.25f }
            drawRoundRect(block, Offset(0f, y + u * 0.4f), Size(w, u * 3.6f), CornerRadius(u * 0.4f, u * 0.4f))
            y += u * 5f
            listOf(0.86f, 0.92f, 0.58f, 0.8f).forEach { f -> bar(y, f, u * 0.5f, line); y += u * 1.25f }
        }
    }

    private fun shot(name: String, dark: Boolean, statusColor: Color? = null, content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalPerf provides PerfConfig(reduceMotion = true)) {
                EscanerTheme(darkTheme = dark, dynamicColor = false) {
                    Column(Modifier.fillMaxSize().background(statusColor ?: MaterialTheme.ui.groupedBackground)) {
                        // Barra de estado simulada (Robolectric no tiene).
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
