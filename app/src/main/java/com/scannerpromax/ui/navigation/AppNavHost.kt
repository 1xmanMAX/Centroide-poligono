package com.scannerpromax.ui.navigation

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.camera.CameraScreen
import com.scannerpromax.ui.components.LoadingOverlay
import com.scannerpromax.ui.editor.EditorScreen
import com.scannerpromax.ui.export.ExportScreen
import com.scannerpromax.ui.home.HomeScreen
import com.scannerpromax.ui.ocr.OcrScreen
import com.scannerpromax.ui.review.ReviewScreen
import com.scannerpromax.ui.settings.SettingsScreen
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.tools.CompressPdfScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private const val ANIM_MS = 320

/**
 * Grafo de navegación de la app.
 *  - Home -> Cámara(modo) -> Revisión(doc) (la cámara se quita del backstack).
 *  - Revisión -> Editor / Exportar / OCR / Cámara (añadir páginas al mismo documento).
 *  - Importar imágenes desde Home: crea documento, importa con progreso y abre Revisión.
 *  - PDF recibido de otra app ([incomingPdf]) -> Comprimir PDF.
 */
@Composable
fun AppNavHost(
    container: AppContainer,
    incomingPdf: MutableStateFlow<Uri?>,
    incomingImages: MutableStateFlow<List<Uri>>? = null,
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    // Transiciones adaptativas: más cortas en gama baja (menos frames con dos pantallas componiéndose a la vez)
    // y ninguna si el sistema tiene "Quitar animaciones".
    val perf = LocalPerf.current
    val anim = perf.duration(ANIM_MS)
    val scope = rememberCoroutineScope()

    // Uri que recibe la pantalla de compresión (de otra app o null si se abre desde Home).
    var compressUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    // Estado de importación desde galería (overlay a pantalla completa sobre la navegación).
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importMessage by remember { mutableStateOf("") }
    var importJob by remember { mutableStateOf<Job?>(null) }

    // Sincroniza preferencias que el repositorio usa al crear páginas nuevas.
    LaunchedEffect(container) {
        container.settings.settings.collect { s ->
            container.documents.preferredFilter = s.defaultFilter
            container.documents.defaultAutoRemoveLines = s.autoRemoveLines
        }
    }

    // PDF compartido / abierto desde otra app -> compresor. Se copia en el acto a la caché propia:
    // el permiso temporal de lectura no sobrevive a la muerte del proceso, y cada copia tiene un Uri
    // nuevo, así que compartir dos veces el mismo PDF vuelve a abrirlo.
    LaunchedEffect(incomingPdf) {
        incomingPdf.filterNotNull().collect { uri ->
            incomingPdf.value = null
            compressUri = copyIncomingPdf(context, uri) ?: uri
            nav.navigate(Routes.COMPRESS) { launchSingleTop = true }
        }
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty() || importing) return
        importJob = scope.launch {
            importing = true
            importProgress = 0f
            var docId: String? = null
            try {
                val doc = container.documents.create(ScanMode.DOCUMENT)
                docId = doc.id
                var imported = 0
                uris.forEachIndexed { i, uri ->
                    importMessage = "Importando y mejorando ${i + 1} de ${uris.size}…"
                    try {
                        imported += container.documents.addPagesFromUris(doc.id, listOf(uri)).size
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Se omite la imagen problemática y se sigue con el resto.
                    }
                    importProgress = (i + 1f) / uris.size
                }
                if (imported == 0) {
                    container.documents.delete(doc.id)
                    Toast.makeText(context, "No se pudo importar ninguna imagen", Toast.LENGTH_LONG).show()
                } else {
                    if (imported < uris.size) {
                        Toast.makeText(context, "Se importaron $imported de ${uris.size} imágenes", Toast.LENGTH_LONG).show()
                    }
                    nav.navigate(Routes.review(doc.id)) { launchSingleTop = true }
                }
            } catch (e: CancellationException) {
                docId?.let { id ->
                    // Cancelado por el usuario: si no llegó a tener páginas, se elimina.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        val d = container.documents.get(id)
                        if (d != null && d.pages.isEmpty()) container.documents.delete(id)
                        else if (d != null) nav.navigate(Routes.review(id)) { launchSingleTop = true }
                    }
                }
                throw e
            } catch (t: Throwable) {
                Toast.makeText(context, "Error al importar: ${t.message ?: "desconocido"}", Toast.LENGTH_LONG).show()
            } finally {
                importing = false
                importJob = null
            }
        }
    }

    // Imágenes compartidas desde otra app -> documento nuevo con mejora automática.
    LaunchedEffect(incomingImages) {
        val flow = incomingImages ?: return@LaunchedEffect
        flow.collect { uris ->
            if (uris.isNotEmpty()) {
                flow.value = emptyList()
                importImages(uris)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { navEnter(anim) },
            exitTransition = { navExit(anim) },
            popEnterTransition = { navPopEnter(anim) },
            popExitTransition = { navPopExit(anim) },
        ) {
            composable(Routes.HOME) { entry ->
                HomeScreen(
                    container = container,
                    onScan = { mode -> entry.ifActive(nav) { nav.navigate(Routes.camera(mode)) } },
                    onOpenDocument = { id -> entry.ifActive(nav) { nav.navigate(Routes.review(id)) } },
                    onImportImages = { uris -> importImages(uris) },
                    onCompressPdf = {
                        entry.ifActive(nav) {
                            compressUri = null
                            nav.navigate(Routes.COMPRESS) { launchSingleTop = true }
                        }
                    },
                    onSettings = { entry.ifActive(nav) { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } } },
                )
            }

            composable(
                Routes.CAMERA,
                arguments = listOf(
                    navArgument("docId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mode") { type = NavType.StringType; defaultValue = ScanMode.DOCUMENT.name },
                ),
                // La vista previa de la cámara es costosa de escalar: en gama baja solo fundido.
                enterTransition = {
                    if (anim == 0) EnterTransition.None
                    else if (perf.lowEnd) fadeIn(tween(anim))
                    else fadeIn(tween(anim)) + scaleIn(tween(anim), initialScale = 0.96f)
                },
                exitTransition = { if (anim == 0) ExitTransition.None else fadeOut(tween(anim / 2)) },
                popEnterTransition = { if (anim == 0) EnterTransition.None else fadeIn(tween(anim)) },
                popExitTransition = {
                    if (anim == 0) ExitTransition.None
                    else if (perf.lowEnd) fadeOut(tween(anim / 2))
                    else fadeOut(tween(anim / 2)) + scaleOut(tween(anim), targetScale = 0.96f)
                },
            ) { entry ->
                val docId = entry.arguments?.getString("docId")?.takeIf { it.isNotBlank() }
                val modeName = entry.arguments?.getString("mode")
                val mode = ScanMode.entries.firstOrNull { it.name == modeName } ?: ScanMode.DOCUMENT
                CameraScreen(
                    container = container,
                    docId = docId,
                    mode = mode,
                    onFinished = { finishedId ->
                        // Solo si la cámara sigue siendo la pantalla actual (evita dobles navegaciones).
                        if (nav.currentBackStackEntry?.id == entry.id &&
                            entry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        ) nav.finishCamera(entry, finishedId)
                    },
                    onBack = { entry.ifActive(nav) { nav.popBackStack() } },
                )
            }

            composable(
                Routes.REVIEW,
                arguments = listOf(navArgument("docId") { type = NavType.StringType }),
            ) { entry ->
                val docId = entry.arguments?.getString("docId").orEmpty()
                ReviewScreen(
                    container = container,
                    docId = docId,
                    onBack = { entry.ifActive(nav) { nav.backTo(Routes.HOME) } },
                    onEditPage = { pageId -> entry.ifActive(nav) { nav.navigate(Routes.editor(docId, pageId)) } },
                    onAddPages = { mode -> entry.ifActive(nav) { nav.navigate(Routes.camera(mode, docId)) } },
                    onExport = { entry.ifActive(nav) { nav.navigate(Routes.export(docId)) } },
                    onOcr = { pageId -> entry.ifActive(nav) { nav.navigate(Routes.ocr(docId, pageId)) } },
                )
            }

            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("docId") { type = NavType.StringType },
                    navArgument("pageId") { type = NavType.StringType },
                ),
            ) { entry ->
                EditorScreen(
                    container = container,
                    docId = entry.arguments?.getString("docId").orEmpty(),
                    pageId = entry.arguments?.getString("pageId").orEmpty(),
                    onBack = { entry.ifActive(nav) { nav.popBackStack() } },
                )
            }

            composable(
                Routes.EXPORT,
                arguments = listOf(navArgument("docId") { type = NavType.StringType }),
            ) { entry ->
                ExportScreen(
                    container = container,
                    docId = entry.arguments?.getString("docId").orEmpty(),
                    onBack = { entry.ifActive(nav) { nav.popBackStack() } },
                )
            }

            composable(
                Routes.OCR,
                arguments = listOf(
                    navArgument("docId") { type = NavType.StringType },
                    navArgument("pageId") { type = NavType.StringType },
                ),
            ) { entry ->
                OcrScreen(
                    container = container,
                    docId = entry.arguments?.getString("docId").orEmpty(),
                    pageId = entry.arguments?.getString("pageId").orEmpty(),
                    onBack = { entry.ifActive(nav) { nav.popBackStack() } },
                )
            }

            composable(Routes.COMPRESS) { entry ->
                CompressPdfScreen(
                    container = container,
                    initialUri = compressUri,
                    onBack = { entry.ifActive(nav) { nav.backTo(Routes.HOME) } },
                )
            }

            composable(Routes.SETTINGS) { entry ->
                SettingsScreen(
                    container = container,
                    onBack = { entry.ifActive(nav) { nav.popBackStack() } },
                )
            }
        }

        LoadingOverlay(
            visible = importing,
            message = importMessage.ifEmpty { "Preparando importación…" },
            progress = importProgress,
            onCancel = { importJob?.cancel() },
        )
    }
}

// ------------------------------------------------------------------------------------- utilidades

/**
 * Ejecuta la acción solo si esta entrada es la pantalla actual y está al menos en STARTED.
 * Evita navegaciones dobles por toques rápidos sin bloquear los toques durante la animación de entrada
 * (en gama baja la transición tarda y exigir RESUMED hacía que la app pareciera no responder).
 */
private inline fun NavBackStackEntry.ifActive(nav: NavHostController, action: () -> Unit) {
    if (nav.currentBackStackEntry?.id == id && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action()
}

/** Copia un PDF recibido de otra app a la caché propia. Devuelve un Uri de archivo o null si falla. */
private suspend fun copyIncomingPdf(context: android.content.Context, uri: Uri): Uri? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var name: String? = null
            runCatching {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
                }
            }
            val clean = (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "documento.pdf")
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .let { if (it.lowercase().endsWith(".pdf")) it else "$it.pdf" }
            val dir = java.io.File(context.cacheDir, "incoming/${System.currentTimeMillis()}").apply { mkdirs() }
            val out = java.io.File(dir, clean)
            val input = resolver.openInputStream(uri) ?: return@withContext null
            input.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            if (out.length() <= 0L) { out.delete(); null } else Uri.fromFile(out)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

/**
 * Al terminar la cámara: si se añadían páginas a un documento cuya revisión está justo detrás,
 * simplemente vuelve; si no, abre la revisión quitando la cámara del backstack.
 */
private fun NavHostController.finishCamera(cameraEntry: NavBackStackEntry, docId: String) {
    val previous = previousBackStackEntry
    val previousIsSameReview = previous != null && previous.destination.route == Routes.REVIEW &&
        previous.arguments?.getString("docId") == docId
    if (previousIsSameReview) {
        popBackStack()
    } else {
        navigate(Routes.review(docId)) {
            popUpTo(cameraEntry.destination.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/** Vuelve hasta [route] si está en el backstack; si no (p. ej. entrada desde otra app), navega a ella. */
private fun NavHostController.backTo(route: String) {
    if (!popBackStack(route, inclusive = false)) {
        navigate(route) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnter(ms: Int): EnterTransition =
    if (ms == 0) EnterTransition.None
    else slideInHorizontally(tween(ms, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(ms))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navExit(ms: Int): ExitTransition =
    if (ms == 0) ExitTransition.None
    else slideOutHorizontally(tween(ms, easing = FastOutSlowInEasing)) { -it / 8 } + fadeOut(tween(ms / 2))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopEnter(ms: Int): EnterTransition =
    if (ms == 0) EnterTransition.None
    else slideInHorizontally(tween(ms, easing = FastOutSlowInEasing)) { -it / 8 } + fadeIn(tween(ms))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExit(ms: Int): ExitTransition =
    if (ms == 0) ExitTransition.None
    else slideOutHorizontally(tween(ms, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(ms / 2))
