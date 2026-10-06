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
fun AppNavHost(container: AppContainer, incomingPdf: MutableStateFlow<Uri?>) {
    val nav = rememberNavController()
    val context = LocalContext.current
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

    // PDF compartido / abierto desde otra app -> compresor.
    LaunchedEffect(incomingPdf) {
        incomingPdf.filterNotNull().collect { uri ->
            compressUri = uri
            incomingPdf.value = null
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

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { navEnter() },
            exitTransition = { navExit() },
            popEnterTransition = { navPopEnter() },
            popExitTransition = { navPopExit() },
        ) {
            composable(Routes.HOME) { entry ->
                HomeScreen(
                    container = container,
                    onScan = { mode -> entry.ifResumed { nav.navigate(Routes.camera(mode)) } },
                    onOpenDocument = { id -> entry.ifResumed { nav.navigate(Routes.review(id)) } },
                    onImportImages = { uris -> importImages(uris) },
                    onCompressPdf = {
                        entry.ifResumed {
                            compressUri = null
                            nav.navigate(Routes.COMPRESS) { launchSingleTop = true }
                        }
                    },
                    onSettings = { entry.ifResumed { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } } },
                )
            }

            composable(
                Routes.CAMERA,
                arguments = listOf(
                    navArgument("docId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mode") { type = NavType.StringType; defaultValue = ScanMode.DOCUMENT.name },
                ),
                enterTransition = { fadeIn(tween(ANIM_MS)) + scaleIn(tween(ANIM_MS), initialScale = 0.96f) },
                exitTransition = { fadeOut(tween(ANIM_MS / 2)) },
                popEnterTransition = { fadeIn(tween(ANIM_MS)) },
                popExitTransition = { fadeOut(tween(ANIM_MS / 2)) + scaleOut(tween(ANIM_MS), targetScale = 0.96f) },
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
                    onBack = { entry.ifResumed { nav.popBackStack() } },
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
                    onBack = { entry.ifResumed { nav.backTo(Routes.HOME) } },
                    onEditPage = { pageId -> entry.ifResumed { nav.navigate(Routes.editor(docId, pageId)) } },
                    onAddPages = { mode -> entry.ifResumed { nav.navigate(Routes.camera(mode, docId)) } },
                    onExport = { entry.ifResumed { nav.navigate(Routes.export(docId)) } },
                    onOcr = { pageId -> entry.ifResumed { nav.navigate(Routes.ocr(docId, pageId)) } },
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
                    onBack = { entry.ifResumed { nav.popBackStack() } },
                )
            }

            composable(
                Routes.EXPORT,
                arguments = listOf(navArgument("docId") { type = NavType.StringType }),
            ) { entry ->
                ExportScreen(
                    container = container,
                    docId = entry.arguments?.getString("docId").orEmpty(),
                    onBack = { entry.ifResumed { nav.popBackStack() } },
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
                    onBack = { entry.ifResumed { nav.popBackStack() } },
                )
            }

            composable(Routes.COMPRESS) { entry ->
                CompressPdfScreen(
                    container = container,
                    initialUri = compressUri,
                    onBack = { entry.ifResumed { nav.backTo(Routes.HOME) } },
                )
            }

            composable(Routes.SETTINGS) { entry ->
                SettingsScreen(
                    container = container,
                    onBack = { entry.ifResumed { nav.popBackStack() } },
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

/** Ejecuta la acción solo si la pantalla está activa (evita navegaciones dobles por toques rápidos). */
private inline fun NavBackStackEntry.ifResumed(action: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) action()
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

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnter(): EnterTransition =
    slideInHorizontally(tween(ANIM_MS, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(ANIM_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navExit(): ExitTransition =
    slideOutHorizontally(tween(ANIM_MS, easing = FastOutSlowInEasing)) { -it / 8 } + fadeOut(tween(ANIM_MS / 2))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopEnter(): EnterTransition =
    slideInHorizontally(tween(ANIM_MS, easing = FastOutSlowInEasing)) { -it / 8 } + fadeIn(tween(ANIM_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExit(): ExitTransition =
    slideOutHorizontally(tween(ANIM_MS, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(ANIM_MS / 2))
