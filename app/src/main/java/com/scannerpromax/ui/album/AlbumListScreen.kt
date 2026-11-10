package com.scannerpromax.ui.album

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.album.Album
import com.scannerpromax.album.AlbumRenderer
import com.scannerpromax.di.AppContainer
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.ConfirmDialog
import com.scannerpromax.ui.components.IconCircleButton
import com.scannerpromax.ui.components.LargeTitleHeader
import com.scannerpromax.ui.components.MoreMenu
import com.scannerpromax.ui.components.MoreMenuItem
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.ProgressHud
import com.scannerpromax.ui.components.RenameDialog
import com.scannerpromax.ui.components.relativeTime
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * «Álbum / Diseño libre»: lista de álbumes y «Nuevo álbum» (elige la primera hoja: en blanco, imagen, página de PDF
 * o página escaneada). Cada álbum se abre en [AlbumEditorScreen].
 */
@Composable
internal fun AlbumListScreen(container: AppContainer, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val ui = MaterialTheme.ui
    val scope = rememberCoroutineScope()
    val albums by container.albums.all.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Album?>(null) }
    var deleteTarget by remember { mutableStateOf<Album?>(null) }

    BackHandler { onBack() }

    fun create(req: BgRequest) {
        if (busy) return
        busy = true
        scope.launch {
            var id: String? = null
            try {
                val a = container.albums.create("Álbum ${albums.size + 1}")
                id = a.id
                val page = resolveBackground(container, a.id, req)
                container.albums.update(a.id) { it.copy(pages = listOf(page)) }
                onOpen(a.id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                id?.let { container.albums.delete(it) }
                Toast.makeText(context, "No se pudo crear el álbum: ${t.message ?: "error"}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(ui.groupedBackground)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = bottom + 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                LargeTitleHeader(
                    title = "Álbumes",
                    subtitle = "Diseño libre: dibuja recuadros en una hoja y pega tus escaneos",
                    onBack = onBack,
                    backLabel = "Documentos",
                    horizontalPadding = 0.dp,
                )
            }
            if (albums.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(AppIcons.Grid, contentDescription = null, tint = ui.tertiaryLabel, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Sin álbumes", style = MaterialTheme.typography.titleLarge, color = ui.label)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Como un álbum de cromos: la hoja es el álbum y los recuadros, donde se pegan. Escanea cada cromo y queda pegado en su sitio.",
                            style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            items(albums, key = { it.id }) { a ->
                AlbumRow(container, a, onClick = { onOpen(a.id) }, onRename = { renameTarget = a }, onDuplicate = {
                    scope.launch { container.albums.duplicate(a.id) }
                }, onDelete = { deleteTarget = a })
            }
        }
        PrimaryCapsuleButton(
            "Nuevo álbum",
            icon = AppIcons.Plus,
            onClick = { picker = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 24.dp),
        )
        ProgressHud(visible = busy, message = "Preparando el álbum…")
    }

    BackgroundPicker(container, open = picker, title = "Primera hoja", onDismiss = { picker = false }, onPicked = { picker = false; create(it) })

    renameTarget?.let { a ->
        RenameDialog(initial = a.title, title = "Renombrar álbum", onConfirm = { t -> scope.launch { container.albums.rename(a.id, t) } }, onDismiss = { renameTarget = null })
    }
    deleteTarget?.let { a ->
        ConfirmDialog(
            title = "¿Eliminar álbum?",
            message = "Se eliminará «${a.title}» con sus ${a.pages.size} hoja(s) y los cromos pegados. No se puede deshacer.",
            confirmLabel = "Eliminar",
            destructive = true,
            onConfirm = { scope.launch { container.albums.delete(a.id) } },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun AlbumRow(container: AppContainer, a: Album, onClick: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val ui = MaterialTheme.ui
    var menu by remember { mutableStateOf(false) }
    val first = a.pages.firstOrNull()
    val bmp by produceState<Bitmap?>(null, a.id, first) {
        value = first?.let { page ->
            withContext(Dispatchers.Default) {
                runCatching {
                    val dir = container.albums.dir(a.id)
                    AlbumRenderer.render(page, dir, 200.0 / max(page.widthMm, page.heightMm), { w, h ->
                        (page.background.preview ?: page.background.file)?.let { AlbumRenderer.decodeSampled(File(dir, it), max(w, h)) }
                    }, drawEmptySlots = true)
                }.getOrNull()
            }
        }
    }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ui.card).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp, 84.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).border(0.5.dp, ui.separator, RoundedCornerShape(6.dp))) {
            img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, color = ui.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val slots = a.pages.sumOf { it.slots.size }
            val filled = a.pages.sumOf { p -> p.slots.count { it.sticker != null } }
            Text(
                (if (a.pages.size == 1) "1 hoja" else "${a.pages.size} hojas") + " · $filled/$slots cromos · " + relativeTime(a.updatedAt),
                style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconCircleButton(AppIcons.More, "Más opciones", onClick = { menu = true }, size = 34.dp, elevation = 0.dp)
            MoreMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                groups = listOf(
                    listOf(MoreMenuItem("Renombrar", AppIcons.Rename, onClick = onRename), MoreMenuItem("Duplicar", AppIcons.Duplicate, onClick = onDuplicate)),
                    listOf(MoreMenuItem("Eliminar", AppIcons.Trash, destructive = true, onClick = onDelete)),
                ),
            )
        }
    }
}
