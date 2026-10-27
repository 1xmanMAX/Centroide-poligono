package com.scannerpromax.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.scannerpromax.ai.ModelCatalog
import com.scannerpromax.ai.ModelState
import com.scannerpromax.ai.cloud.CloudProviderId
import com.scannerpromax.ai.cloud.CloudUsage
import com.scannerpromax.data.AppSettings
import com.scannerpromax.domain.CompressionLevel
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.ImageFormat
import com.scannerpromax.domain.Page
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfTextMode
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.domain.TextPlacement
import com.scannerpromax.pdf.CompressionResult
import com.scannerpromax.ui.camera.BatchButton
import com.scannerpromax.ui.camera.CameraBottomRow
import com.scannerpromax.ui.camera.CameraScaffold
import com.scannerpromax.ui.camera.CameraTopBar
import com.scannerpromax.ui.camera.FlashSetting
import com.scannerpromax.ui.camera.GalleryButton
import com.scannerpromax.ui.camera.HintPill
import com.scannerpromax.ui.camera.ModeTextSelector
import com.scannerpromax.ui.camera.PermissionScreen
import com.scannerpromax.ui.camera.ShutterButton
import com.scannerpromax.ui.camera.SkipBackButton
import com.scannerpromax.ui.camera.ZoomPills
import com.scannerpromax.ui.components.AlertAction
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.MenuScrim
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.MoreMenuPanel
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.ProgressHudCard
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.editor.EditorBackground
import com.scannerpromax.ui.editor.EditorTool
import com.scannerpromax.ui.editor.EditorTopBar
import com.scannerpromax.ui.editor.FilterThumb
import com.scannerpromax.ui.editor.HoldForOriginal
import com.scannerpromax.ui.editor.TextAction
import com.scannerpromax.ui.editor.ToolTab
import com.scannerpromax.ui.export.ExportKind
import com.scannerpromax.ui.export.ExportSheetContent
import com.scannerpromax.ui.review.ReviewContent
import com.scannerpromax.ui.review.reviewSubtitle
import com.scannerpromax.ui.settings.AiModelsContent
import com.scannerpromax.ui.settings.CloudAiContent
import com.scannerpromax.ui.theme.EscanerTheme
import com.scannerpromax.ui.theme.ForcedDarkTheme
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.PerfConfig
import com.scannerpromax.ui.theme.ui
import com.scannerpromax.ui.tools.CompressContent
import com.scannerpromax.ui.tools.CompressMode
import com.scannerpromax.ui.tools.PdfInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Capturas del rediseño estilo Apple, fase 2 (Cámara, Documento, Editor, Exportar, OCR, Comprimir, Modelos IA y Nube),
 * con datos falsos y sin cámara real (fondo simulado). Solo con -Pscreenshots (ver RedesignScreenshotTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = Application::class)
class RedesignPhase2ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File = File(System.getProperty("screenshots.out") ?: "build/screenshots").apply { mkdirs() }

    // ------------------------------------------------------------------------------------- Cámara

    @Test fun camara() = shot("f2_camara", dark = false, statusColor = Color.Black) { Camera(menu = false) }

    @Test fun camara_menu() = shot("f2_camara_menu", dark = false, statusColor = Color.Black) { Camera(menu = true) }

    @Test fun camara_dni() = shot("f2_camara_dni_reverso", dark = false, statusColor = Color.Black) { Camera(menu = false, idBack = true) }

    @Test fun camara_permiso_claro() = shot("f2_camara_permiso_claro", dark = false) { Permission() }

    @Test fun camara_permiso_oscuro() = shot("f2_camara_permiso_oscuro", dark = true) { Permission() }

    // ------------------------------------------------------------------------------------- Documento

    @Test fun documento_claro() = shot("f2_documento_claro", dark = false) { Review() }

    @Test fun documento_oscuro() = shot("f2_documento_oscuro", dark = true) { Review() }


    // ------------------------------------------------------------------------------------- Editor

    @Test fun editor_filtros() = shot("f2_editor_filtros", dark = false, statusColor = EditorBackground) { Editor(EditorTool.FILTERS) }

    @Test fun editor_limpiar() = shot("f2_editor_limpiar", dark = false, statusColor = EditorBackground) { Editor(EditorTool.CLEAN) }

    // ------------------------------------------------------------------------------------- Exportar

    @Test fun exportar_claro() = shot("f2_exportar_claro", dark = false, statusColor = Color(0xFF1C1C1E)) { Export(ExportKind.PDF) }

    @Test fun exportar_oscuro() = shot("f2_exportar_oscuro", dark = true, statusColor = Color(0xFF1C1C1E)) { Export(ExportKind.PDF) }

    @Test fun exportar_imagenes_resultado_claro() =
        shot("f2_exportar_imagenes_resultado_claro", dark = false, statusColor = Color(0xFF1C1C1E)) { Export(ExportKind.IMAGES, saved = true) }

    // ------------------------------------------------------------------------------------- Comprimir

    @Test fun comprimir_claro() = shot("f2_comprimir_claro", dark = false) { Compress(result = false) }

    @Test fun comprimir_resultado_oscuro() = shot("f2_comprimir_resultado_oscuro", dark = true) { Compress(result = true) }

    @Test fun comprimir_vacio_claro() = shot("f2_comprimir_vacio_claro", dark = false) { Compress(result = false, hasFile = false) }

    // ------------------------------------------------------------------------------------- IA

    @Test fun modelos_ia_claro() = shot("f2_modelos_ia_claro", dark = false) { AiModels() }

    @Test fun modelos_ia_oscuro() = shot("f2_modelos_ia_oscuro", dark = true) { AiModels() }

    @Test fun nube_claro() = shot("f2_nube_claro", dark = false) { Cloud() }

    @Test fun nube_oscuro() = shot("f2_nube_oscuro", dark = true) { Cloud() }

    // ------------------------------------------------------------------------------------- piezas

    /** Vista previa simulada: fondo cálido desenfocado y una hoja inclinada con su contorno detectado. */
    @Composable
    private fun FakeCameraPreview() {
        val accent = MaterialTheme.ui.accent
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF5A5650), Color(0xFF2B2A28)))),
        ) {
            Box(Modifier.align(Alignment.Center).rotate(-4f).size(width = 250.dp, height = 350.dp).background(Color(0xFFF4F1EA), RoundedCornerShape(3.dp))) {
                FakePage(7, Modifier.fillMaxSize().padding(20.dp), dark = false)
            }
            Canvas(Modifier.align(Alignment.Center).rotate(-4f).size(width = 258.dp, height = 360.dp)) {
                drawRoundRect(accent.copy(alpha = 0.12f), cornerRadius = CornerRadius(6.dp.toPx()))
                drawRoundRect(accent, style = Stroke(3.dp.toPx()), cornerRadius = CornerRadius(6.dp.toPx()))
            }
        }
    }

    private val cameraMenu = listOf(
        listOf(
            MoreMenuItem("Poca luz: automático", AppIcons.Check) {},
            MoreMenuItem("Poca luz: activado") {},
            MoreMenuItem("Poca luz: desactivado") {},
        ),
        listOf(MoreMenuItem("Cámara del teléfono", AppIcons.Phone) {}),
        listOf(MoreMenuItem("Resolución: 12 MP · HDR", AppIcons.Resolution) {}),
    )

    @Composable
    private fun Camera(menu: Boolean, idBack: Boolean = false) {
        CameraScaffold(
            topBar = {
                CameraTopBar(
                    onClose = {}, showAutoToggle = true, autoCapture = true, onAutoCapture = {},
                    hasFlash = true, flash = FlashSetting.AUTO, onFlash = {}, lowLightActive = false, menuGroups = cameraMenu,
                )
            },
            preview = { FakeCameraPreview() },
            hintArea = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HintPill(
                        if (idBack) "Paso 2 de 2 · Gira la tarjeta y encuadra el REVERSO" else "No te muevas… capturando",
                        icon = null, warning = false, onClick = null,
                    )
                    if (idBack) SkipBackButton {}
                }
            },
            controls = {
                Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) { ZoomPills(1f, 0.5f, 8f, {}) }
                Spacer(Modifier.height(6.dp))
                ModeTextSelector(if (idBack) ScanMode.ID_CARD else ScanMode.DOCUMENT, {})
                Spacer(Modifier.height(8.dp))
                CameraBottomRow(
                    gallery = { GalleryButton {} },
                    shutter = { ShutterButton(enabled = true, progress = { 0.6f }, busy = false, onClick = {}) },
                    batch = { BatchButton(count = 3, processing = false, onDone = {}) { FakePage(3, Modifier.fillMaxSize().padding(4.dp), dark = false) } },
                )
            },
            overlays = {
                if (menu) {
                    MenuScrim(visible = true)
                    MoreMenuPanel(cameraMenu, {}, Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 12.dp), width = 270.dp)
                }
            },
        )
    }

    @Composable
    private fun Permission() {
        PermissionScreen(permanentlyDenied = false, onRequest = {}, onOpenSettings = {}, onBack = {})
    }

    private val reviewPages = List(5) { i ->
        Page(
            id = "p$i", originalFile = "p$i.jpg", width = 2480, height = 3508,
            edits = PageEdits(filter = if (i == 2) FilterType.BLACK_WHITE else FilterType.NATURAL),
            ocrText = if (i < 3) "texto" else null,
        )
    }

    @Composable
    private fun Review(menuOpen: Boolean = false) {
        ReviewContent(
            title = "Contrato de alquiler",
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
            pageThumbnail = { p, n, m -> FakePage(n * 3, m.padding(horizontal = 12.dp, vertical = 14.dp), dark = MaterialTheme.ui.isDark) },
            onPageClick = {},
            onAddCamera = {}, onAddGallery = {}, onFilter = {}, onExport = {},
            snackbar = remember { SnackbarHostState() },
        )
    }

    @Composable
    private fun Editor(tool: EditorTool) = ForcedDarkTheme {
        Column(Modifier.fillMaxSize().background(EditorBackground)) {
            EditorTopBar(
                title = "Página 2 de 4", canPrev = true, canNext = true, showPager = true, onPrev = {}, onNext = {},
                onCancel = {}, onDone = {}, doneEnabled = true, saving = false,
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 44.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 290.dp, height = 410.dp).background(Color.White, RoundedCornerShape(4.dp))) {
                    FakePage(5, Modifier.fillMaxSize().padding(24.dp), dark = false, ink = true)
                }
            }
            Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) { HoldForOriginal(false) {} }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                if (tool == EditorTool.FILTERS) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            FilterThumb("Blanco y negro", null, false, {}) { FakeThumb(Color.White, Color.Black) }
                            FilterThumb("Color natural", null, true, {}) { FakeThumb(Color.White, Color(0xFF1C1C1E)) }
                            FilterThumb("Color original", null, false, {}) { FakeThumb(Color(0xFFF1ECE2), Color(0xFF5B5348)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextAction("Ajustes finos", {})
                            Text("·", color = Color.White.copy(alpha = 0.5f))
                            TextAction("Aplicar a todas", {})
                        }
                    }
                } else {
                    Column(Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                        InsetGroup(separatorInset = SeparatorInsetPlain, horizontalPadding = 16.dp) {
                            item { ToggleRow("Quitar rayas automáticamente", true, {}, subtitle = "Dobleces, marcas de bolígrafo y bordes") }
                            item { ToggleRow("Quitar ruido", false, {}, subtitle = "Puntos, granulado y suciedad") }
                            item { ToggleRow("Enderezar texto", true, {}, subtitle = "Corrige unos grados de inclinación") }
                            item { ToggleRow("Enderezar hoja curvada", false, {}, subtitle = "Aplana hojas dobladas usando las líneas de tablas y cuadrículas") }
                        }
                        Spacer(Modifier.height(10.dp))
                        InsetGroup(horizontalPadding = 16.dp) {
                            item { NavigationRow("Borrado manual", {}, subtitle = "Quita a mano rayas o manchas que queden", icon = AppIcons.Eraser, iconTint = MaterialTheme.ui.accent) }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EditorTool.entries.forEach { t -> ToolTab(t, selected = t == tool, enabled = true) {} }
            }
        }
    }

    @Composable
    private fun FakeThumb(bg: Color, ink: Color) {
        Box(Modifier.fillMaxSize().background(bg)) { FakePage(1, Modifier.fillMaxSize().padding(8.dp), dark = false, color = ink) }
    }

    @Composable
    private fun Export(kind: ExportKind, saved: Boolean = false) {
        ExportSheetContent(
            title = "Contrato de alquiler",
            pages = reviewPages.take(4),
            kind = kind, onKind = {},
            textMode = PdfTextMode.BUSCABLE, onTextMode = {},
            textPlacement = TextPlacement.AFTER_EACH_PAGE, onTextPlacement = {},
            quality = ExportQuality.BALANCED, onQuality = {},
            pageSize = PageSize.A4, onPageSize = {},
            imageFormat = ImageFormat.JPEG, onImageFormat = {},
            usePassword = false, onUsePassword = {},
            password = "", onPassword = {},
            showPassword = false, onShowPassword = {},
            fileName = "Contrato de alquiler", onFileName = {},
            estimateBytes = 1_260_000L,
            aiPages = 0,
            missingOcr = 0,
            lastSaved = if (saved) "4 imágenes guardadas en Galería (EscanerProMax)" else null,
            canOpenSaved = saved,
            onOpenSaved = {}, onShareSaved = {},
            ocrWarningCount = null, onRetry = {},
            busy = false,
            onCancel = {}, onSave = {}, onShare = {},
            thumbnail = { _, i, m -> FakePage(i + 2, m.padding(horizontal = 6.dp, vertical = 8.dp), dark = MaterialTheme.ui.isDark) },
        )
    }

    @Composable
    private fun Compress(result: Boolean, hasFile: Boolean = true) {
        CompressContent(
            hasFile = hasFile,
            info = PdfInfo("Tesis_final_v3.pdf", 18_400_000L, 64, false),
            loadingInfo = false,
            mode = CompressMode.LEVEL, onModeChange = {},
            level = CompressionLevel.RECOMMENDED, onLevelChange = {},
            targetMb = 5f, onTargetChange = {},
            progress = null,
            result = if (result) CompressionResult(File("x.pdf"), 18_400_000L, 5_100_000L, 64) else null,
            error = null,
            saving = false,
            busy = false,
            onBack = {}, onPick = {}, onCompress = {}, onCancel = {}, onSave = {}, onShare = {}, onAgain = {},
            snackbar = remember { SnackbarHostState() },
        )
    }

    @Composable
    private fun AiModels() {
        val states = mapOf<String, ModelState>(
            ModelCatalog.FAST_READER.id to ModelState.Downloading(12_400_000L, 37_500_000L, 1_800_000L, "lm.npz"),
            ModelCatalog.PAGE_LAYOUT.id to ModelState.NotDownloaded,
        )
        AiModelsContent(
            settings = AppSettings(),
            states = states,
            message = null, onDismissMessage = {},
            onUpdate = {}, onBack = {},
            onDownload = {}, onCancel = {}, onReinstall = {}, onDelete = {},
        )
    }

    @Composable
    private fun Cloud() {
        val first = CloudProviderId.entries.first()
        CloudAiContent(
            settings = AppSettings(),
            usage = mapOf(first to CloudUsage(pages = 42, requests = 42, inputTokens = 180_000, outputTokens = 61_000, costUsd = 0.31)),
            saved = mapOf(first to "sk-…a91F"),
            testing = emptyMap(),
            testResult = mapOf(first to "Clave válida ✓"),
            onBack = {}, onWifiOnly = {}, onSave = { _, _ -> }, onDelete = {}, onTest = {}, onUse = {}, onStop = {}, onResetUsage = {},
        )
    }

    /** Página falsa (título, líneas y un bloque). [ink] = texto oscuro sobre papel. */
    @Composable
    private fun FakePage(seed: Int, modifier: Modifier, dark: Boolean, ink: Boolean = false, color: Color? = null) {
        val title = color ?: if (ink) Color(0xFF1C1C1E) else if (dark) Color(0xFF636366) else Color(0xFFC7C7CC)
        val line = color?.copy(alpha = 0.7f) ?: if (ink) Color(0xFF3A3A3C) else if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)
        val block = if (ink) Color(0xFFEFEFF4) else if (dark) Color(0xFF3A3A3C) else Color(0xFFF2F2F7)
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
