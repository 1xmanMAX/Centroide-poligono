package com.scannerpromax.ui.album

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.album.AlbumOps
import com.scannerpromax.album.AlbumPage
import com.scannerpromax.album.AlbumStore
import com.scannerpromax.album.PaperSize
import com.scannerpromax.data.VersionedFile
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.Document
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.components.SheetHeader
import com.scannerpromax.ui.components.IosSheet
import com.scannerpromax.ui.components.Thumbnail
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Hoja elegida por el usuario, aún sin importar al álbum. */
internal sealed interface BgRequest {
    data class Blank(val paper: PaperSize, val landscape: Boolean) : BgRequest
    data class Image(val file: File, val label: String?) : BgRequest
    data class Pdf(val file: File, val index: Int, val label: String?) : BgRequest
    /** Página escaneada de la app (su procesado; se COPIA al álbum). */
    data class AppPage(val file: File, val label: String?) : BgRequest
}

/** Importa [req] al álbum [albumId] y devuelve la hoja nueva. */
internal suspend fun resolveBackground(container: AppContainer, albumId: String, req: BgRequest): AlbumPage = when (req) {
    is BgRequest.Blank -> AlbumStore.blankPage(req.paper, req.landscape)
    is BgRequest.Image -> AlbumOps.pageWith(container.albumImport.imageBackground(albumId, req.file, req.label))
    is BgRequest.AppPage -> AlbumOps.pageWith(container.albumImport.imageBackground(albumId, req.file, req.label))
    is BgRequest.Pdf -> AlbumOps.pageWith(container.albumImport.pdfBackground(albumId, req.file, req.index, req.label))
}

/**
 * Elegir la hoja del álbum: en blanco (A4/Carta/Oficio/A5, vertical u horizontal), imagen de la galería, página de un
 * PDF o página escaneada de la app. Debe estar SIEMPRE en la composición del llamador (lleva los lanzadores de
 * actividad); [open] la muestra.
 */
@Composable
internal fun BackgroundPicker(
    container: AppContainer,
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onPicked: (BgRequest) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pdfFile by remember { mutableStateOf<Pair<File, String?>?>(null) }
    var appPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                onPicked(BgRequest.Image(container.albumImport.copyToCache(uri, "jpg"), "Imagen"))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("No se pudo abrir la imagen")
            } finally {
                busy = false
            }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val f = container.albumImport.copyToCache(uri, "pdf")
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()
                container.albumImport.pdfPageCount(f)
                pdfFile = f to name
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("No se pudo abrir el PDF (¿está protegido con contraseña?)")
            } finally {
                busy = false
            }
        }
    }

    if (open) {
        var paper by remember { mutableIntStateOf(0) }
        var landscape by remember { mutableIntStateOf(0) }
        IosSheet(onDismissRequest = onDismiss) { dismiss ->
            SheetHeader(title = title, onCancel = dismiss)
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 28.dp),
            ) {
                InsetGroup(header = "Hoja en blanco") {
                    item {
                        Column(Modifier.padding(14.dp)) {
                            SegmentedControl(PaperSize.entries.map { it.label }, paper, { paper = it })
                            Spacer(Modifier.height(10.dp))
                            SegmentedControl(listOf("Vertical", "Horizontal"), landscape, { landscape = it })
                            Spacer(Modifier.height(12.dp))
                            PrimaryCapsuleButton(
                                "Usar hoja en blanco",
                                onClick = { onPicked(BgRequest.Blank(PaperSize.entries[paper], landscape == 1)); dismiss() },
                                modifier = Modifier.fillMaxWidth(),
                                height = 46.dp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                InsetGroup(header = "Hoja con fondo", footer = "La página de un PDF se exporta tal cual (vectorial), con los escaneos encima.") {
                    item {
                        NavigationRow("Imagen de la galería", icon = AppIcons.Gallery, onClick = {
                            try {
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } catch (_: ActivityNotFoundException) {
                                toast("No hay una galería disponible")
                            }
                            dismiss()
                        })
                    }
                    item {
                        NavigationRow("Página de un PDF", icon = AppIcons.Page, onClick = {
                            try {
                                pdfPicker.launch(arrayOf("application/pdf"))
                            } catch (_: ActivityNotFoundException) {
                                toast("No hay un selector de archivos disponible")
                            }
                            dismiss()
                        })
                    }
                    item {
                        NavigationRow("Página escaneada de la app", icon = AppIcons.Document, onClick = { appPicker = true; dismiss() })
                    }
                }
            }
        }
    }

    pdfFile?.let { (f, name) ->
        PdfPagePicker(container, f, onDismiss = { pdfFile = null }) { index ->
            pdfFile = null
            onPicked(BgRequest.Pdf(f, index, name))
        }
    }
    if (appPicker) {
        AppPagePicker(container, onDismiss = { appPicker = false }) { file, label ->
            appPicker = false
            onPicked(BgRequest.AppPage(file, label))
        }
    }
    if (busy) {
        Dialog(onDismissRequest = {}) { CircularProgressIndicator() }
    }
}

/** Cuadrícula de páginas del PDF (miniaturas perezosas) para elegir una. */
@Composable
private fun PdfPagePicker(container: AppContainer, pdf: File, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val count by produceState(-1, pdf) { value = runCatching { container.albumImport.pdfPageCount(pdf) }.getOrDefault(0) }
    FullDialog(title = "Elige una página", onDismiss = onDismiss) {
        if (count < 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count) { i ->
                    PdfThumb(container, pdf, i, onClick = { onPick(i) })
                }
            }
        }
    }
}

@Composable
private fun PdfThumb(container: AppContainer, pdf: File, index: Int, onClick: () -> Unit) {
    val ui = MaterialTheme.ui
    val bmp by produceState<Bitmap?>(null, pdf, index) {
        value = runCatching { container.albumImport.pdfThumbnail(pdf, index, 320) }.getOrNull()
    }
    DisposableEffect(bmp) { val b = bmp; onDispose { b?.recycle() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)).background(ui.card),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null && !b.isRecycled) {
                Image(remember(b) { b.asImageBitmap() }, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        Text("Página ${index + 1}", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Documentos de la app y sus páginas para usar una como fondo. */
@Composable
private fun AppPagePicker(container: AppContainer, onDismiss: () -> Unit, onPick: (File, String) -> Unit) {
    val ui = MaterialTheme.ui
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by container.documents.documents.collectAsStateWithLifecycle()
    var doc by remember { mutableStateOf<Document?>(null) }
    FullDialog(title = doc?.title ?: "Elige un documento", onDismiss = { if (doc != null) doc = null else onDismiss() }) {
        val d = doc
        if (d == null) {
            val withPages = docs.filter { it.pages.isNotEmpty() }
            if (withPages.isEmpty()) {
                Text("No hay documentos escaneados", color = ui.secondaryLabel, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(withPages, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { doc = item }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val first = item.pages.first()
                        val thumb by produceState<VersionedFile?>(null, item.id, first.id) {
                            value = withContext(Dispatchers.IO) { container.documents.displayThumb(item.id, first) }
                        }
                        Thumbnail(thumb, item.mode, Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(6.dp)).background(ui.card), sizePx = 160)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(item.title, color = ui.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (item.pages.size == 1) "1 página" else "${item.pages.size} páginas", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(d.pages, key = { _, p -> p.id }) { i, p ->
                    val thumb by produceState<VersionedFile?>(null, d.id, p.id) {
                        value = withContext(Dispatchers.IO) { container.documents.displayThumb(d.id, p) }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                        scope.launch {
                            try {
                                onPick(container.documents.processedFile(d.id, p), "${d.title} · pág. ${i + 1}")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                Toast.makeText(context, "No se pudo abrir la página", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Thumbnail(thumb, d.mode, Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)).background(ui.card), sizePx = 320)
                        Text("Página ${i + 1}", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** Diálogo a pantalla completa con cabecera «Cancelar · título». */
@Composable
internal fun FullDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.ui.groupedBackground).statusBarsPadding().navigationBarsPadding()) {
            SheetHeader(title = title, onCancel = onDismiss)
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
