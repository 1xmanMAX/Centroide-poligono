package com.scannerpromax.ui.attach

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.integration.DeliveryPlan
import com.scannerpromax.integration.DeliveryPlanner
import com.scannerpromax.integration.OutputFormat
import com.scannerpromax.integration.RequestSource
import com.scannerpromax.integration.ScanRequest
import com.scannerpromax.ui.camera.CameraScreen
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.ConfirmDialog
import com.scannerpromax.ui.components.FloatingBar
import com.scannerpromax.ui.components.FloatingBarContentPadding
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.ProgressHud
import com.scannerpromax.ui.editor.EditorScreen
import com.scannerpromax.ui.review.PageThumbnail
import com.scannerpromax.ui.review.ReviewCallerMode
import com.scannerpromax.ui.review.ReviewScreen
import com.scannerpromax.ui.review.SelectionCheck
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private object AttachRoutes {
    const val CAMERA = "attach/camera?docId={docId}&mode={mode}"
    const val REVIEW = "attach/review/{docId}"
    const val EDITOR = "attach/editor/{docId}/{pageId}"
    const val DELIVER = "attach/deliver/{docId}"
    /** EDIT: pantalla de páginas del documento de la biblioteca que pidió la otra app. */
    const val EDIT = "attach/edit/{docId}"
    fun camera(docId: String? = null, mode: ScanMode? = null) = "attach/camera?docId=${docId ?: ""}&mode=${mode?.name ?: ""}"
    fun review(docId: String) = "attach/review/$docId"
    fun editor(docId: String, pageId: String) = "attach/editor/$docId/$pageId"
    fun deliver(docId: String) = "attach/deliver/$docId"
    fun edit(docId: String) = "attach/edit/$docId"
}

/**
 * Acciones del flujo "para otra app" que resuelve la actividad (resultado y cierre).
 *  - [onCancel]: RESULT_CANCELED (borra el escaneo si [docId] no es null).
 *  - [onAttach]: genera y entrega; [singleImagePage] = página elegida si solo se puede enviar una imagen.
 */
class AttachCallbacks(
    val onCancel: (docId: String?) -> Unit,
    val onAttach: suspend (docId: String, singleImagePage: Int, progress: (Float, String) -> Unit) -> Unit,
    /** EDIT: entrega exactamente [pageIds] (las visibles, en su orden). */
    val onAttachPages: suspend (docId: String, pageIds: List<String>, progress: (Float, String) -> Unit) -> Unit = { _, _, _ -> },
)

/**
 * Flujo para otra app. Pila de navegación propia (la de la app principal no se toca).
 *  - Directo ([ScanRequest.review] = false, por defecto): cámara del escáner → "Adjuntar (N)" (o la primera foto si
 *    solo se admite una) → "Adjuntando…" → resultado a la otra app. La miniatura del lote abre la revisión si se
 *    quiere retocar; si la entrega falla, también se cae a la revisión (no se pierde el escaneo).
 *  - Con revisión: cámara → revisión mínima (miniaturas, editor para recortar o cambiar el filtro, añadir páginas)
 *    → "Adjuntar a <app>".
 *  - Edición (acción EDIT, [ScanRequest.documentId]): pantalla de páginas del documento ya guardado (la misma que en la
 *    app: recortar/filtros en el editor, reordenar arrastrando, quitar, añadir con la cámara o la galería) →
 *    "Guardar en <app>".
 */
@Composable
fun AttachNavHost(
    container: AppContainer,
    request: ScanRequest,
    callerName: String?,
    canReturnResult: Boolean,
    callbacks: AttachCallbacks,
) {
    val nav = rememberNavController()
    val perf = LocalPerf.current
    val anim = perf.duration(260)

    // Igual que la app principal: el repositorio usa el filtro y la limpieza de Ajustes en las páginas nuevas.
    LaunchedEffect(container) {
        container.settings.settings.collect { s ->
            container.documents.preferredFilter = s.defaultFilter
            container.documents.defaultAutoRemoveLines = s.autoRemoveLines
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.ui.groupedBackground)) {
        NavHost(
            navController = nav,
            startDestination = if (request.source == RequestSource.EDIT && request.documentId != null) AttachRoutes.EDIT else AttachRoutes.CAMERA,
            enterTransition = { if (anim == 0) androidx.compose.animation.EnterTransition.None else fadeIn(tween(anim)) },
            exitTransition = { if (anim == 0) androidx.compose.animation.ExitTransition.None else fadeOut(tween(anim / 2)) },
            popEnterTransition = { if (anim == 0) androidx.compose.animation.EnterTransition.None else fadeIn(tween(anim)) },
            popExitTransition = { if (anim == 0) androidx.compose.animation.ExitTransition.None else fadeOut(tween(anim / 2)) },
        ) {
            composable(
                AttachRoutes.CAMERA,
                arguments = listOf(
                    navArgument("docId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mode") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val docId = entry.arguments?.getString("docId")?.takeIf { it.isNotBlank() }
                val pageMode = entry.arguments?.getString("mode")?.let { m -> ScanMode.entries.firstOrNull { it.name == m } }
                // Directo solo en la primera cámara; al añadir páginas desde la revisión se vuelve a ella con "Listo".
                val direct = !request.review && docId == null
                CameraScreen(
                    container = container,
                    docId = docId,
                    mode = pageMode ?: request.mode,
                    singleShot = !request.multiPage && docId == null,
                    doneLabel = if (direct) directDoneLabel(canReturnResult) else null,
                    onOpenBatch = if (direct) {
                        { finishedId ->
                            entry.ifActive(nav) {
                                nav.navigate(AttachRoutes.review(finishedId)) {
                                    popUpTo(entry.destination.id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    } else null,
                    onFinished = { finishedId ->
                        if (nav.currentBackStackEntry?.id == entry.id && entry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            if (direct) {
                                nav.navigate(AttachRoutes.deliver(finishedId)) {
                                    popUpTo(entry.destination.id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else if (docId != null && nav.previousBackStackEntry?.destination?.route.let { it == AttachRoutes.REVIEW || it == AttachRoutes.EDIT }) {
                                nav.popBackStack()
                            } else {
                                nav.navigate(AttachRoutes.review(finishedId)) {
                                    popUpTo(entry.destination.id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    },
                    onBack = {
                        entry.ifActive(nav) {
                            // Añadiendo páginas: vuelve a la revisión. Primera cámara: cancelar = volver a la otra app.
                            // (popBackStack() sobre el destino inicial lo quitaría y dejaría la pantalla vacía.)
                            if (nav.previousBackStackEntry != null) nav.popBackStack() else callbacks.onCancel(null)
                        }
                    },
                )
            }

            composable(
                AttachRoutes.REVIEW,
                arguments = listOf(navArgument("docId") { type = NavType.StringType }),
            ) { entry ->
                val docId = entry.arguments?.getString("docId").orEmpty()
                AttachReviewScreen(
                    container = container,
                    docId = docId,
                    request = request,
                    callerName = callerName,
                    canReturnResult = canReturnResult,
                    onCancel = { callbacks.onCancel(docId) },
                    onEditPage = { pageId -> entry.ifActive(nav) { nav.navigate(AttachRoutes.editor(docId, pageId)) } },
                    onAddPages = { entry.ifActive(nav) { nav.navigate(AttachRoutes.camera(docId)) } },
                    onAttach = callbacks.onAttach,
                )
            }

            composable(
                AttachRoutes.EDIT,
                arguments = listOf(navArgument("docId") { type = NavType.StringType; defaultValue = request.documentId.orEmpty() }),
            ) { entry ->
                val docId = entry.arguments?.getString("docId").orEmpty()
                AttachEditScreen(
                    container = container,
                    docId = docId,
                    callerName = callerName,
                    canReturnResult = canReturnResult,
                    onCancel = { callbacks.onCancel(docId) },
                    onEditPage = { pageId -> entry.ifActive(nav) { nav.navigate(AttachRoutes.editor(docId, pageId)) } },
                    onAddPages = { mode -> entry.ifActive(nav) { nav.navigate(AttachRoutes.camera(docId, mode)) } },
                    onAttachPages = callbacks.onAttachPages,
                )
            }

            composable(
                AttachRoutes.DELIVER,
                arguments = listOf(navArgument("docId") { type = NavType.StringType }),
            ) { entry ->
                val docId = entry.arguments?.getString("docId").orEmpty()
                AttachDeliverScreen(
                    container = container,
                    docId = docId,
                    request = request,
                    callerName = callerName,
                    canReturnResult = canReturnResult,
                    onAttach = callbacks.onAttach,
                    onCancel = { callbacks.onCancel(docId) },
                    onFallbackToReview = {
                        entry.ifActive(nav) {
                            nav.navigate(AttachRoutes.review(docId)) {
                                popUpTo(entry.destination.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }

            composable(
                AttachRoutes.EDITOR,
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
        }
    }
}

private inline fun NavBackStackEntry.ifActive(nav: NavHostController, action: () -> Unit) {
    if (nav.currentBackStackEntry?.id == id && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action()
}

/** Texto del botón de terminar de la cámara en el modo directo (se le añade " (N)"). */
internal fun directDoneLabel(canReturnResult: Boolean): String = if (canReturnResult) "Adjuntar" else "Guardar"

/** Mensaje del indicador mientras se genera y entrega el archivo en el modo directo. */
internal fun deliveringMessage(callerName: String?, canReturnResult: Boolean): String = when {
    !canReturnResult -> "Guardando…"
    callerName.isNullOrBlank() -> "Adjuntando…"
    else -> "Adjuntando a $callerName…"
}

/**
 * Aplica a las páginas el filtro pedido por la otra app (extra "filter"), salvo a las de [skip] (ya aplicadas: así
 * un cambio posterior del usuario en el editor no se pisa). Devuelve los id de página tratados.
 */
internal suspend fun applyRequestedFilter(container: AppContainer, docId: String, request: ScanRequest, skip: Collection<String> = emptyList()): List<String> {
    val f = request.filter ?: return emptyList()
    val d = container.documents.get(docId) ?: return emptyList()
    if (d.mode == ScanMode.ID_CARD) return emptyList()
    val done = ArrayList<String>()
    for (p in d.pages) {
        if (p.id in skip) continue
        if (p.edits.filter != f) runCatching { container.documents.updateEdits(docId, p.id, p.edits.copy(filter = f)) }
        done += p.id
    }
    return done
}

/**
 * Modo directo: sin pantalla intermedia. Genera el archivo y lo entrega en cuanto se abre, con un indicador breve
 * "Adjuntando…". Cancelar el indicador (o Atrás) o un error llevan a la revisión, sin perder las páginas.
 */
@Composable
private fun AttachDeliverScreen(
    container: AppContainer,
    docId: String,
    request: ScanRequest,
    callerName: String?,
    canReturnResult: Boolean,
    onAttach: suspend (docId: String, singleImagePage: Int, progress: (Float, String) -> Unit) -> Unit,
    onCancel: () -> Unit,
    onFallbackToReview: () -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(docId) {
        job = scope.launch {
            try {
                val doc = container.documents.get(docId)
                if (doc == null || doc.pages.isEmpty()) {
                    onCancel()
                    return@launch
                }
                applyRequestedFilter(container, docId, request)
                onAttach(docId, 0) { p, _ -> progress = p }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.widget.Toast.makeText(context, "No se pudo adjuntar: ${t.message ?: "error"}", android.widget.Toast.LENGTH_LONG).show()
                onFallbackToReview()
            }
        }
    }
    fun cancelToReview() {
        job?.cancel()
        onFallbackToReview()
    }
    BackHandler { cancelToReview() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ProgressHud(
            visible = true,
            message = deliveringMessage(callerName, canReturnResult),
            progress = progress,
            onCancel = { cancelToReview() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** EDIT: botón principal de la pantalla de páginas. */
internal fun editSaveLabel(callerName: String?, canReturnResult: Boolean): String = when {
    !canReturnResult -> "Listo"
    callerName.isNullOrBlank() -> "Guardar y volver"
    else -> "Guardar en $callerName"
}

/**
 * Huella de las páginas (orden + ediciones) para saber si el usuario cambió algo antes de salir sin guardar. Usa
 * toString (estable entre procesos, a diferencia del hashCode de los enum) para poder guardarla con rememberSaveable.
 */
internal fun pagesFingerprint(pages: List<com.scannerpromax.domain.Page>): String =
    pages.joinToString("|") { it.id + ":" + it.edits.toString().hashCode() }

/**
 * EDIT: la pantalla Documento de la app ([ReviewScreen] con [ReviewCallerMode]) para el documento que pidió la otra
 * app. "Guardar en <app>" entrega las páginas visibles; "‹ Cancelar"/Atrás vuelve sin actualizar (si hubo cambios,
 * se confirma: quedan guardados en el escáner, pero la otra app sigue con el archivo anterior).
 */
@Composable
private fun AttachEditScreen(
    container: AppContainer,
    docId: String,
    callerName: String?,
    canReturnResult: Boolean,
    onCancel: () -> Unit,
    onEditPage: (String) -> Unit,
    onAddPages: (ScanMode) -> Unit,
    onAttachPages: suspend (docId: String, pageIds: List<String>, progress: (Float, String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by remember(docId) { container.documents.observe(docId) }.collectAsStateWithLifecycle(initialValue = null)
    /** Huella al abrir (sobrevive a ir al editor/cámara y a la recreación). */
    var initialPrint by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(doc) { if (initialPrint == null) doc?.let { initialPrint = pagesFingerprint(it.pages) } }
    var confirmExit by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    fun exit() {
        if (working) return
        val d = doc
        if (canReturnResult && d != null && initialPrint != null && pagesFingerprint(d.pages) != initialPrint) confirmExit = true
        else onCancel()
    }

    fun save(pageIds: List<String>) {
        if (working || pageIds.isEmpty()) return
        job = scope.launch {
            working = true
            progress = 0f
            message = "Preparando…"
            try {
                onAttachPages(docId, pageIds) { p, m -> progress = p; message = m }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.widget.Toast.makeText(context, "No se pudo preparar el archivo: ${t.message ?: "error"}", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                working = false
                job = null
            }
        }
    }

    BackHandler(enabled = !working) { exit() }
    Box(Modifier.fillMaxSize()) {
        ReviewScreen(
            container = container,
            docId = docId,
            onBack = { exit() },
            onEditPage = { if (!working) onEditPage(it) },
            onAddPages = { mode -> if (!working) onAddPages(mode) },
            onExport = { ids -> save(ids.orEmpty()) },
            callerMode = ReviewCallerMode(backLabel = "Cancelar", primaryLabel = editSaveLabel(callerName, canReturnResult)),
        )
        ProgressHud(
            visible = working,
            message = message.ifEmpty { if (canReturnResult) deliveringMessage(callerName, true) else "Guardando…" },
            progress = progress,
            onCancel = { job?.cancel() },
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (confirmExit) {
        ConfirmDialog(
            title = "¿Salir sin actualizar?",
            message = "Los cambios quedan guardados en el escáner, pero ${callerName?.takeIf { it.isNotBlank() } ?: "la otra app"} " +
                "seguirá con el archivo anterior.",
            confirmLabel = "Salir",
            dismissLabel = "Seguir editando",
            destructive = true,
            onConfirm = { confirmExit = false; onCancel() },
            onDismiss = { confirmExit = false },
        )
    }
}

/** Texto del botón principal. */
internal fun attachButtonLabel(callerName: String?, canReturnResult: Boolean): String = when {
    !canReturnResult -> "Guardar en mis documentos"
    callerName.isNullOrBlank() -> "Adjuntar"
    else -> "Adjuntar a $callerName"
}

/** Resumen de lo que se va a entregar ("PDF · 3 páginas", "3 imágenes JPG"…). */
internal fun attachSummary(plan: DeliveryPlan, totalPages: Int): String {
    val fmt = plan.format.label
    return when {
        plan.format == OutputFormat.PDF -> "PDF · " + if (plan.pages.size == 1) "1 página" else "${plan.pages.size} páginas"
        plan.filePerPage -> if (plan.pages.size == 1) "1 imagen $fmt" else "${plan.pages.size} imágenes $fmt"
        totalPages > 1 -> "1 imagen $fmt · la app solo acepta una: toca el círculo para elegir cuál"
        else -> "1 imagen $fmt"
    }
}

/**
 * Revisión mínima estilo iOS: miniaturas (tocar = editor: recortar, girar, filtro), añadir páginas si se permiten,
 * quitar páginas y el botón principal "Adjuntar a <app>".
 */
@Composable
private fun AttachReviewScreen(
    container: AppContainer,
    docId: String,
    request: ScanRequest,
    callerName: String?,
    canReturnResult: Boolean,
    onCancel: () -> Unit,
    onEditPage: (String) -> Unit,
    onAddPages: () -> Unit,
    onAttach: suspend (docId: String, singleImagePage: Int, progress: (Float, String) -> Unit) -> Unit,
) {
    val ui = MaterialTheme.ui
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by remember(docId) { container.documents.observe(docId) }.collectAsStateWithLifecycle(initialValue = null)
    var loadedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(doc) { if (doc != null) loadedOnce = true }
    val pages = doc?.pages.orEmpty()
    var singlePage by rememberSaveable { mutableIntStateOf(0) }
    var confirmCancel by remember { mutableStateOf(false) }
    var deletingPage by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    /** Páginas a las que ya se aplicó el filtro pedido por la otra app (sobrevive a la recreación). */
    var filtered by rememberSaveable { mutableStateOf(listOf<String>()) }

    // Si el escaneo desaparece (borrado desde la app principal), se cancela.
    LaunchedEffect(doc, loadedOnce) {
        if (loadedOnce && (doc == null || doc?.pages?.isEmpty() == true)) onCancel()
    }
    // Filtro pedido por la otra app (extra "filter"): se aplica a cada página nueva una vez.
    LaunchedEffect(pages.map { it.id }) {
        if (request.filter == null || doc == null) return@LaunchedEffect
        val done = applyRequestedFilter(container, docId, request, skip = filtered)
        if (done.isNotEmpty()) filtered = filtered + done
    }

    val plan = if (pages.isNotEmpty()) DeliveryPlanner.plan(request, pages.size, singlePage) else null
    val chooseOne = plan != null && plan.pages.size == 1 && pages.size > 1

    fun attach() {
        if (working || pages.isEmpty()) return
        job = scope.launch {
            working = true
            progress = 0f
            message = "Preparando…"
            try {
                onAttach(docId, singlePage.coerceIn(0, pages.size - 1)) { p, m -> progress = p; message = m }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.widget.Toast.makeText(context, "No se pudo preparar el archivo: ${t.message ?: "error"}", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                working = false
                job = null
            }
        }
    }

    BackHandler(enabled = !working) { confirmCancel = true }

    Box(Modifier.fillMaxSize().background(ui.groupedBackground)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = FloatingBarContentPadding + 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LargeTitleHeader(
                    title = if (canReturnResult && !callerName.isNullOrBlank()) "Para $callerName" else "Revisar",
                    backLabel = "Cancelar",
                    onBack = { if (!working) confirmCancel = true },
                    subtitle = plan?.let { attachSummary(it, pages.size) },
                    titleStyle = MaterialTheme.typography.headlineLarge,
                    horizontalPadding = 4.dp,
                )
            }
            itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                AttachPageCell(
                    number = index + 1,
                    chooseOne = chooseOne,
                    chosen = chooseOne && index == singlePage.coerceIn(0, pages.size - 1),
                    canDelete = pages.size > 1,
                    onClick = { if (!working) onEditPage(page.id) },
                    onChoose = { singlePage = index },
                    onDelete = { deletingPage = page.id },
                ) { m -> PageThumbnail(container, docId, page, index + 1, m) }
            }
            if (request.multiPage) {
                item(key = "add") {
                    AddPageCell(onClick = { if (!working) onAddPages() })
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Toca una página para recortarla, girarla o cambiar el filtro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ui.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }

        FloatingBar(Modifier.align(Alignment.BottomCenter)) {
            PrimaryCapsuleButton(
                text = attachButtonLabel(callerName, canReturnResult),
                onClick = { attach() },
                icon = if (canReturnResult) AppIcons.Share else AppIcons.Download,
                enabled = pages.isNotEmpty() && !working,
                loading = working,
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            )
        }

        ProgressHud(visible = working, message = message.ifEmpty { "Preparando…" }, progress = progress, onCancel = { job?.cancel() })
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "¿Descartar el escaneo?",
            message = if (canReturnResult && !callerName.isNullOrBlank()) "Volverás a $callerName sin adjuntar nada." else "Se perderán las páginas escaneadas.",
            confirmLabel = "Descartar",
            destructive = true,
            onConfirm = { confirmCancel = false; onCancel() },
            onDismiss = { confirmCancel = false },
        )
    }
    deletingPage?.let { id ->
        ConfirmDialog(
            title = "¿Quitar esta página?",
            message = "No se incluirá en el archivo.",
            confirmLabel = "Quitar",
            destructive = true,
            onConfirm = {
                deletingPage = null
                scope.launch {
                    runCatching { container.documents.deletePage(docId, id) }
                    singlePage = 0
                }
            },
            onDismiss = { deletingPage = null },
        )
    }
}

@Composable
private fun AttachPageCell(
    number: Int,
    chooseOne: Boolean,
    chosen: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onChoose: () -> Unit,
    onDelete: () -> Unit,
    thumbnail: @Composable (Modifier) -> Unit,
) {
    val ui = MaterialTheme.ui
    val shape = RoundedCornerShape(10.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(shape)
                .background(if (ui.isDark) Color(0xFF2C2C2E) else Color.White)
                .then(if (chosen) Modifier.border(3.dp, ui.accent, shape) else Modifier.border(0.5.dp, ui.separator, shape))
                .clickable(onClickLabel = "Editar página $number", onClick = onClick),
        ) {
            thumbnail(Modifier.fillMaxSize())
            if (chooseOne) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = "Adjuntar la página $number", onClick = onChoose)
                        .padding(4.dp),
                ) { SelectionCheck(chosen) }
            }
            if (canDelete) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClickLabel = "Quitar página $number", onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Close, contentDescription = "Quitar página $number", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("$number", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = ui.secondaryLabel)
    }
}

@Composable
private fun AddPageCell(onClick: () -> Unit) {
    val ui = MaterialTheme.ui
    val shape = RoundedCornerShape(10.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(shape)
                .background(ui.fill)
                .clickable(onClickLabel = "Añadir página", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(AppIcons.Plus, contentDescription = null, tint = ui.accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(6.dp))
                Text("Añadir página", style = MaterialTheme.typography.bodyMedium, color = ui.accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(" ", style = MaterialTheme.typography.bodySmall)
    }
}
