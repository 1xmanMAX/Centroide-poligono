package com.scannerpromax.ui.album

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.album.Album
import com.scannerpromax.album.AlbumGeometry
import com.scannerpromax.album.AlbumHistory
import com.scannerpromax.album.AlbumOps
import com.scannerpromax.album.AlbumPage
import com.scannerpromax.album.AlbumRenderer
import com.scannerpromax.album.AlbumSessions
import com.scannerpromax.album.AlbumSlot
import com.scannerpromax.album.BackgroundKind
import com.scannerpromax.album.P
import com.scannerpromax.album.SlotPreset
import com.scannerpromax.album.StickerFit
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.ImageFormat
import com.scannerpromax.ui.components.AlertAction
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.ConfirmDialog
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.IosAlert
import com.scannerpromax.ui.components.IosSheet
import com.scannerpromax.ui.components.IosTextField
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.ProgressHud
import com.scannerpromax.ui.components.RenameDialog
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.components.SheetHeader
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Editor del álbum: la hoja con sus recuadros ([AlbumSheetView]), acciones del recuadro seleccionado o de la hoja,
 * tira de hojas abajo y deshacer/rehacer arriba. Cada cambio se guarda al momento (el almacén escribe en orden).
 * Los cromos que se pegan desde la cámara o el recorte llegan por el almacén y se adoptan como un paso más.
 */
@Composable
internal fun AlbumEditorScreen(
    container: AppContainer,
    albumId: String,
    onBack: () -> Unit,
    onScanSlot: (pageId: String, slotId: String) -> Unit,
    onCropSlot: (pageId: String, slotId: String, newSource: String?) -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    val context = LocalContext.current
    val ui = MaterialTheme.ui
    val scope = rememberCoroutineScope()
    val store = container.albums

    var album by remember { mutableStateOf<Album?>(null) }
    var history by remember { mutableStateOf<AlbumHistory?>(null) }
    var pages by remember { mutableStateOf<List<AlbumPage>>(emptyList()) }
    var historyTick by remember { mutableIntStateOf(0) }
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val recentlySent = remember { ArrayDeque<List<AlbumPage>>() }

    // carga + adopción de cambios hechos fuera (cámara, recorte)
    LaunchedEffect(albumId) {
        store.observe(albumId).collect { a ->
            if (a == null) {
                if (album != null) { AlbumSessions.forget(albumId); onBack() }
                return@collect
            }
            album = a
            val h = history
            if (h == null) {
                // el historial de esta sesión de edición (si se vuelve de la cámara) o uno nuevo
                val kept = AlbumSessions.get(albumId)
                val nh = kept ?: AlbumHistory(a.pages).also { AlbumSessions.put(albumId, it) }
                if (nh.current != a.pages) nh.push(a.pages)
                history = nh
                pages = a.pages
                historyTick++
            } else if (a.pages != pages && recentlySent.none { it == a.pages }) {
                h.push(a.pages)
                pages = a.pages
                historyTick++
            }
        }
    }
    // salir del editor: se olvida el deshacer y se borran los archivos que ya nadie usa
    val exit: () -> Unit = {
        AlbumSessions.forget(albumId)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { store.compact(albumId) } }
        onBack()
    }

    fun commit(next: List<AlbumPage>) {
        val h = history ?: return
        if (!h.push(next)) return
        pages = next
        historyTick++
        recentlySent.addLast(next)
        while (recentlySent.size > 12) recentlySent.removeFirst()
        store.enqueue(albumId) { it.copy(pages = next) }
    }

    fun restore(p: List<AlbumPage>?) {
        p ?: return
        pages = p
        historyTick++
        recentlySent.addLast(p)
        while (recentlySent.size > 12) recentlySent.removeFirst()
        store.enqueue(albumId) { it.copy(pages = p) }
        if (selectedId != null && p.none { pg -> pg.slots.any { it.id == selectedId } }) selectedId = null
    }

    val a = album
    val page = pages.getOrNull(pageIndex.coerceIn(0, max(0, pages.lastIndex)))
    LaunchedEffect(pages.size) { if (pageIndex > pages.lastIndex) pageIndex = max(0, pages.lastIndex) }
    val selected = page?.slots?.firstOrNull { it.id == selectedId }

    // ---------------------------------------------------------------- vistas previas (resolución de pantalla)
    val bgFile = page?.background?.let { bg -> (bg.preview ?: bg.file)?.takeIf { bg.kind != BackgroundKind.BLANK } }
    val bgImage by produceState<ImageBitmap?>(null, albumId, bgFile) {
        value = bgFile?.let { rel ->
            withContext(Dispatchers.IO) {
                runCatching { AlbumRenderer.decodeSampled(store.file(albumId, rel), 1600)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val stickerCache = remember { mutableStateMapOf<String, ImageBitmap>() }
    val wanted = page?.slots?.mapNotNull { it.sticker?.rendered }.orEmpty()
    LaunchedEffect(albumId, wanted) {
        for (rel in wanted) {
            if (stickerCache.containsKey(rel)) continue
            val img = withContext(Dispatchers.IO) {
                runCatching { AlbumRenderer.decodeSampled(store.file(albumId, rel), 900)?.asImageBitmap() }.getOrNull()
            } ?: continue
            stickerCache[rel] = img
        }
        // límite de memoria: sólo los de esta hoja si hay muchos
        if (stickerCache.size > 40) stickerCache.keys.filter { it !in wanted }.forEach { stickerCache.remove(it) }
    }

    // ---------------------------------------------------------------- diálogos y tareas
    var showRename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSize by remember { mutableStateOf<String?>(null) } // id del recuadro o "" = nuevo
    var showAngle by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var bgPickerFor by remember { mutableStateOf<String?>(null) } // "new" = hoja nueva, "bg" = cambiar fondo (hoja visible)
    var bgMode by remember { mutableStateOf("new") } // se conserva mientras la galería o el PDF están abiertos
    var busy by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableFloatStateOf(-1f) }
    var busyJob by remember { mutableStateOf<Job?>(null) }

    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_SHORT).show()

    fun runBusy(msg: String, block: suspend () -> Unit) {
        if (busyJob != null) return
        busyJob = scope.launch {
            busy = msg; busyProgress = -1f
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast(t.message ?: "Ocurrió un error")
            } finally {
                busy = null; busyJob = null
            }
        }
    }

    var gallerySlot by remember { mutableStateOf<Pair<String, String>?>(null) }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = gallerySlot
        gallerySlot = null
        if (uri != null && target != null) runBusy("Abriendo la imagen…") {
            val (f, rel) = store.newFile(albumId, "src", target.second, "jpg")
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("No se pudo abrir la imagen")
                input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            }
            onCropSlot(target.first, target.second, rel)
        }
    }

    BackHandler { if (selectedId != null) selectedId = null else exit() }

    Box(Modifier.fillMaxSize().background(ui.groupedBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---------------------------------------------------------- barra superior
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(AppIcons.Back, "Atrás", onClick = exit)
                Text(
                    a?.title ?: "Álbum",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ui.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).clickable { showRename = true },
                )
                @Suppress("UNUSED_EXPRESSION") historyTick
                IconCircleButton(AppIcons.Undo, "Deshacer", onClick = { restore(history?.undo()) }, enabled = history?.canUndo == true)
                Spacer(Modifier.width(8.dp))
                IconCircleButton(AppIcons.Redo, "Rehacer", onClick = { restore(history?.redo()) }, enabled = history?.canRedo == true)
                Spacer(Modifier.width(8.dp))
                IconCircleButton(AppIcons.Export, "Exportar", onClick = { showExport = true }, containerColor = ui.accent, contentColor = ui.onAccent, enabled = pages.isNotEmpty())
            }

            // ---------------------------------------------------------- hoja
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (page != null) {
                    AlbumSheetView(
                        page = page,
                        selectedId = selectedId,
                        background = bgImage,
                        stickers = stickerCache,
                        accent = ui.accent,
                        onSelect = { selectedId = it },
                        onCommit = { np -> commit(AlbumOps.updatePage(pages, np.id) { np }) },
                    )
                    if (page.slots.isEmpty()) {
                        Text(
                            "Arrastra sobre la hoja para dibujar un recuadro.\nDos dedos: zoom y desplazar.",
                            color = ui.secondaryLabel,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(10.dp)).background(ui.card.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // ---------------------------------------------------------- acciones
            if (page != null) {
                val pid = page.id
                if (selected != null) {
                    val sid = selected.id
                    val st = selected.sticker
                    fun slotOp(f: (AlbumSlot) -> AlbumSlot) = commit(AlbumOps.updateSlot(pages, pid, sid, f))
                    ActionRow {
                        Action(AppIcons.Camera, if (st == null) "Escanear" else "Reescanear", highlight = true) { onScanSlot(pid, sid) }
                        Action(AppIcons.Gallery, "Galería") {
                            gallerySlot = pid to sid
                            try {
                                galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } catch (_: ActivityNotFoundException) {
                                gallerySlot = null; toast("No hay una galería disponible")
                            }
                        }
                        if (st != null) {
                            Action(AppIcons.Crop, "Recortar") { onCropSlot(pid, sid, null) }
                            Action(AppIcons.Resolution, if (st.fit == StickerFit.COVER) "Encajar" else "Rellenar") {
                                slotOp { s -> s.copy(sticker = s.sticker?.copy(fit = if (st.fit == StickerFit.COVER) StickerFit.FIT else StickerFit.COVER)) }
                            }
                            Action(AppIcons.RotateRight, "Girar foto") { slotOp { s -> s.copy(sticker = s.sticker?.copy(rotation = (st.rotation + 90) % 360)) } }
                            Action(AppIcons.Close, "Quitar foto") { slotOp { it.copy(sticker = null) } }
                        }
                        Action(AppIcons.Rotate, "Girar 90°") {
                            slotOp { s -> AlbumGeometry.withBox(s, AlbumGeometry.rotated90(AlbumGeometry.box(s, page)), page) }
                        }
                        Action(AppIcons.Speed, "Ángulo") { showAngle = true }
                        Action(AppIcons.Resolution, "Medidas") { showSize = sid }
                        Action(AppIcons.Lock, if (selected.keepAspect) "Proporcional" else "Libre", highlight = selected.keepAspect) {
                            slotOp { it.copy(keepAspect = !it.keepAspect) }
                        }
                        Action(AppIcons.Duplicate, "Duplicar") {
                            AlbumOps.duplicateSlot(page, sid)?.let { (np, s) -> commit(AlbumOps.updatePage(pages, pid) { np }); selectedId = s.id }
                        }
                        Action(AppIcons.Trash, "Eliminar", destructive = true) {
                            commit(AlbumOps.updatePage(pages, pid) { p -> p.copy(slots = p.slots.filter { it.id != sid }) })
                            selectedId = null
                        }
                    }
                } else {
                    ActionRow {
                        Action(AppIcons.Plus, "Recuadro", highlight = true) { showSize = "" }
                        Action(AppIcons.RotateRight, "Girar hoja") { commit(AlbumOps.updatePage(pages, pid) { AlbumGeometry.rotatePage(it, true) }) }
                        Action(AppIcons.Gallery, "Fondo") { bgMode = "bg"; bgPickerFor = "bg" }
                        Action(AppIcons.Page, "Hoja nueva") { bgMode = "new"; bgPickerFor = "new" }
                        if (pageIndex > 0) Action(AppIcons.Back, "Mover") {
                            commit(AlbumOps.movePage(pages, pid, -1)); pageIndex--
                        }
                        if (pageIndex < pages.lastIndex) Action(AppIcons.Chevron, "Mover") {
                            commit(AlbumOps.movePage(pages, pid, 1)); pageIndex++
                        }
                        if (pages.size > 1) Action(AppIcons.Trash, "Borrar hoja", destructive = true) { confirmDeletePage = true }
                    }
                }
            }

            // ---------------------------------------------------------- tira de hojas
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                    PageThumb(container, albumId, p, i + 1, i == pageIndex) { pageIndex = i; selectedId = null }
                }
                item(key = "add") {
                    Box(
                        Modifier.size(56.dp, 76.dp).clip(RoundedCornerShape(8.dp)).background(ui.card).clickable { bgMode = "new"; bgPickerFor = "new" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AppIcons.Plus, contentDescription = "Añadir hoja", tint = ui.accent) }
                }
            }
        }

        ProgressHud(visible = busy != null, message = busy.orEmpty(), progress = busyProgress.takeIf { it >= 0f }, onCancel = { busyJob?.cancel() })
    }

    // ---------------------------------------------------------------- diálogos
    if (showRename && a != null) {
        RenameDialog(
            initial = a.title,
            title = "Renombrar álbum",
            onConfirm = { t -> scope.launch { store.rename(albumId, t) } },
            onDismiss = { showRename = false },
        )
    }

    showSize?.let { target ->
        val p = page
        if (p != null) {
            val slot = p.slots.firstOrNull { it.id == target }
            SlotSizeDialog(
                initial = slot?.let { AlbumGeometry.box(it, p) }?.let { it.w to it.h },
                keepAspect = slot?.keepAspect ?: true,
                onDismiss = { showSize = null },
                onApply = { wMm, hMm ->
                    if (slot != null) {
                        val b = AlbumGeometry.box(slot, p)
                        commit(AlbumOps.updateSlot(pages, p.id, slot.id) { AlbumGeometry.withBox(it, b.copy(w = wMm, h = hMm), p) })
                    } else {
                        val b = AlbumGeometry.sized(P(p.widthMm / 2.0, p.heightMm / 2.0), wMm, hMm, p.widthMm.toDouble(), p.heightMm.toDouble())
                        val (np, s) = AlbumOps.addSlot(p, b)
                        commit(AlbumOps.updatePage(pages, p.id) { np })
                        selectedId = s.id
                    }
                },
            )
        }
    }

    if (showAngle && selected != null && page != null) {
        val b0 = AlbumGeometry.box(selected, page)
        var angle by remember(selected.id) { mutableFloatStateOf(b0.angle.toFloat()) }
        IosAlert(
            title = "Ángulo del recuadro",
            onDismiss = { showAngle = false },
            actions = listOf(
                AlertAction("Cancelar") {},
                AlertAction("Aplicar", bold = true) {
                    commit(AlbumOps.updateSlot(pages, page.id, selected.id) { AlbumGeometry.withBox(it, b0.copy(angle = angle.toDouble()), page) })
                },
            ),
        ) {
            Text("%.1f°".format(angle), color = MaterialTheme.ui.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Slider(value = angle, onValueChange = { angle = (it * 2).toInt() / 2f }, valueRange = -180f..180f, modifier = Modifier.padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(-90f, -1f, 0f, 1f, 90f).forEach { v ->
                    Text(
                        if (v == 0f) "0°" else if (kotlin.math.abs(v) == 1f) (if (v > 0) "+1°" else "−1°") else "%+.0f°".format(v),
                        color = MaterialTheme.ui.accent,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            angle = if (v == 0f) 0f else AlbumGeometry.normalizeAngle(angle + v.toDouble()).toFloat()
                        }.padding(8.dp),
                    )
                }
            }
        }
    }

    if (confirmDeletePage && page != null) {
        ConfirmDialog(
            title = "¿Borrar esta hoja?",
            message = "Se quitará la hoja ${pageIndex + 1} con sus recuadros. Puedes deshacerlo.",
            confirmLabel = "Borrar",
            destructive = true,
            onConfirm = { commit(pages.filter { it.id != page.id }); selectedId = null },
            onDismiss = { confirmDeletePage = false },
        )
    }

    BackgroundPicker(
        container = container,
        open = bgPickerFor != null,
        title = if (bgPickerFor == "bg") "Fondo de la hoja" else "Hoja nueva",
        onDismiss = { bgPickerFor = null },
        onPicked = { req ->
            val mode = bgMode
            bgPickerFor = null
            runBusy("Preparando la hoja…") {
                val np = resolveBackground(container, albumId, req)
                val cur = pages.getOrNull(pageIndex)
                if (mode == "bg" && cur != null) {
                    commit(AlbumOps.updatePage(pages, cur.id) { AlbumOps.replaceBackground(it, np.background, np.widthMm.toDouble(), np.heightMm.toDouble()) })
                } else {
                    val at = if (pages.isEmpty()) 0 else pageIndex + 1
                    commit(pages.toMutableList().apply { add(at, np) })
                    pageIndex = at
                    selectedId = null
                }
            }
        },
    )

    if (showExport && a != null) {
        IosSheet(onDismissRequest = { showExport = false }) { dismiss ->
            SheetHeader(title = "Exportar álbum", onCancel = dismiss)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 28.dp)) {
                val latest = { a.copy(pages = pages) }
                InsetGroup(header = "PDF", footer = "Las hojas con fondo PDF conservan la página original (texto y vectores); los escaneos van encima.") {
                    item {
                        NavigationRow("Compartir PDF", icon = AppIcons.Share, onClick = {
                            dismiss()
                            runBusy("Generando PDF…") {
                                val f = container.albumExporter.exportPdf(latest()) { busyProgress = it }
                                startShare(context, container, listOf(f), "application/pdf")
                            }
                        })
                    }
                    item {
                        NavigationRow("Guardar PDF en Descargas", icon = AppIcons.Download, onClick = {
                            dismiss()
                            runBusy("Generando PDF…") {
                                val f = container.albumExporter.exportPdf(latest()) { busyProgress = it }
                                container.imageExporter.savePdfToDownloads(f, a.title)
                                toast("PDF guardado en Descargas")
                            }
                        })
                    }
                }
                Spacer(Modifier.height(18.dp))
                InsetGroup(header = "Imágenes (JPG, 300 ppp)") {
                    item {
                        NavigationRow("Compartir imágenes", icon = AppIcons.Share, onClick = {
                            dismiss()
                            runBusy("Generando imágenes…") {
                                val files = container.albumExporter.exportImages(latest()) { busyProgress = it }
                                startShare(context, container, files, "image/jpeg")
                            }
                        })
                    }
                    item {
                        NavigationRow("Guardar en la galería", icon = AppIcons.Gallery, onClick = {
                            dismiss()
                            runBusy("Generando imágenes…") {
                                val files = container.albumExporter.exportImages(latest()) { busyProgress = it }
                                container.imageExporter.saveToGallery(files, ImageFormat.JPEG, ExportQuality.MAX, a.title)
                                toast("Imágenes guardadas en la galería")
                            }
                        })
                    }
                }
                Spacer(Modifier.height(18.dp))
                InsetGroup(footer = "Crea un documento con estas hojas en tu lista de Documentos (para unirlo, pasarle OCR o exportarlo como cualquier escaneo).") {
                    item {
                        NavigationRow("Crear documento en la app", icon = AppIcons.Document, onClick = {
                            dismiss()
                            runBusy("Creando el documento…") {
                                val id = container.albumExporter.createDocument(latest(), container.documents) { busyProgress = it }
                                onOpenDocument(id)
                            }
                        })
                    }
                }
            }
        }
    }
}

private fun startShare(context: android.content.Context, container: AppContainer, files: List<File>, mime: String) {
    try {
        context.startActivity(container.imageExporter.shareChooser(files, mime, "Compartir"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No hay apps para compartir", Toast.LENGTH_SHORT).show()
    }
}

// -------------------------------------------------------------------------------------- piezas

@Composable
private fun ActionRow(content: @Composable ActionRowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActionRowScope.content()
    }
}

internal object ActionRowScope

@Composable
private fun ActionRowScope.Action(icon: ImageVector, label: String, highlight: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) {
    val ui = MaterialTheme.ui
    val tint = when {
        destructive -> ui.red
        highlight -> ui.accent
        else -> ui.label
    }
    Column(
        Modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(if (highlight) ui.accent.copy(alpha = 0.14f) else ui.card), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(label, fontSize = 11.sp, color = tint, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Miniatura de una hoja (renderizada en segundo plano a ~180 px con las vistas previas). */
@Composable
private fun PageThumb(container: AppContainer, albumId: String, page: AlbumPage, number: Int, selected: Boolean, onClick: () -> Unit) {
    val ui = MaterialTheme.ui
    val bmp by produceState<Bitmap?>(null, page) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val k = 180.0 / max(page.widthMm, page.heightMm)
                val dir = container.albums.dir(albumId)
                AlbumRenderer.render(page, dir, k, { w, h ->
                    val rel = page.background.preview ?: page.background.file
                    rel?.let { AlbumRenderer.decodeSampled(File(dir, it), max(w, h)) }
                }, drawEmptySlots = true)
            }.getOrNull()
        }
    }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    val ratio = page.widthMm / page.heightMm
    val h = 76.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .height(h)
                .width(h * ratio)
                .clip(RoundedCornerShape(6.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .border(if (selected) 2.5.dp else 0.5.dp, if (selected) ui.accent else ui.separator, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick),
        ) {
            img?.let { Image(it, contentDescription = "Hoja $number", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text("$number", fontSize = 11.sp, color = if (selected) ui.accent else ui.secondaryLabel)
    }
}

/** Medidas exactas en mm (con proporción enlazada) y medidas habituales. */
@Composable
private fun SlotSizeDialog(initial: Pair<Double, Double>?, keepAspect: Boolean, onDismiss: () -> Unit, onApply: (Double, Double) -> Unit) {
    val ui = MaterialTheme.ui
    fun fmt(v: Double) = if (kotlin.math.abs(v - Math.round(v)) < 0.05) "%.0f".format(v) else "%.1f".format(v)
    var wText by remember { mutableStateOf(TextFieldValue(initial?.first?.let(::fmt) ?: "85.6")) }
    var hText by remember { mutableStateOf(TextFieldValue(initial?.second?.let(::fmt) ?: "54")) }
    var linked by remember { mutableStateOf(initial != null && keepAspect) }
    val ratio = initial?.let { it.first / it.second }
    fun parse(t: TextFieldValue) = t.text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= AlbumGeometry.MIN_SIZE_MM && it <= 2000 }
    val presets = remember { SlotPreset.entries }
    var orient by remember { mutableIntStateOf(0) }
    IosAlert(
        title = if (initial == null) "Nuevo recuadro" else "Medidas del recuadro",
        onDismiss = onDismiss,
        actions = listOf(
            AlertAction("Cancelar") {},
            AlertAction("Aplicar", bold = true, enabled = parse(wText) != null && parse(hText) != null) {
                val w = parse(wText); val h = parse(hText)
                if (w != null && h != null) onApply(w, h)
            },
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IosTextField(
                value = wText,
                onValueChange = { v ->
                    wText = v
                    val w = parse(v)
                    if (linked && ratio != null && w != null) hText = TextFieldValue(fmt(w / ratio))
                },
                placeholder = "Ancho (mm)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Text(" × ", color = ui.secondaryLabel)
            IosTextField(
                value = hText,
                onValueChange = { v ->
                    hText = v
                    val h = parse(v)
                    if (linked && ratio != null && h != null) wText = TextFieldValue(fmt(h * ratio))
                },
                placeholder = "Alto (mm)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        Text("Ancho × alto en milímetros", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.padding(top = 4.dp))
        if (ratio != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { linked = !linked }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Lock, contentDescription = null, tint = if (linked) ui.accent else ui.tertiaryLabel, modifier = Modifier.size(16.dp))
                Text(if (linked) "Proporción enlazada" else "Proporción libre", color = if (linked) ui.accent else ui.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        SegmentedControl(listOf("Como se indica", "Girado 90°"), orient, { orient = it })
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth()) {
            presets.forEach { p ->
                Text(
                    p.label,
                    color = ui.accent,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                        val (w, h) = if (orient == 0) p.widthMm to p.heightMm else p.heightMm to p.widthMm
                        linked = false
                        wText = TextFieldValue(fmt(w.toDouble())); hText = TextFieldValue(fmt(h.toDouble()))
                    }.padding(vertical = 7.dp, horizontal = 4.dp),
                )
            }
        }
    }
}
