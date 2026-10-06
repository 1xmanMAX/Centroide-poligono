package com.scannerpromax.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.brand
import java.io.File

/** Degradado inferior de las tarjetas: constante (no se crea un Brush por tarjeta y recomposición). */
private val BottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))

/** Acciones del menú contextual de un documento. null = opción oculta. */
class DocumentActions(
    val onRename: (() -> Unit)? = null,
    val onDuplicate: (() -> Unit)? = null,
    val onSharePdf: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/**
 * Tarjeta de documento para rejilla: miniatura vertical (Coil, tamaño limitado), título, nº de páginas,
 * fecha relativa, insignia del modo y menú (renombrar, duplicar, compartir PDF, eliminar).
 */
@Composable
fun DocumentCard(
    title: String,
    pageCount: Int,
    updatedAt: Long,
    thumbnail: File?,
    mode: ScanMode,
    onClick: () -> Unit,
    actions: DocumentActions,
    modifier: Modifier = Modifier,
) {
    val brand = MaterialTheme.brand
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = brand.card,
        border = BorderStroke(1.dp, brand.cardBorder),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .padding(8.dp)
                    .clip(MaterialTheme.shapes.medium),
            ) {
                Thumbnail(thumbnail, mode, Modifier.fillMaxSize())
                // Degradado inferior para legibilidad de las insignias.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(BottomScrim),
                )
                Badge(
                    text = pagesLabel(pageCount),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
                ModeBadge(mode, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            Row(
                Modifier.padding(start = 14.dp, end = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        relativeTime(updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                DocumentMenu(actions)
            }
        }
    }
}

/** Variante en fila (vista de lista): miniatura pequeña a la izquierda. */
@Composable
fun DocumentListItem(
    title: String,
    pageCount: Int,
    updatedAt: Long,
    thumbnail: File?,
    mode: ScanMode,
    onClick: () -> Unit,
    actions: DocumentActions,
    modifier: Modifier = Modifier,
) {
    val brand = MaterialTheme.brand
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = brand.card,
        border = BorderStroke(1.dp, brand.cardBorder),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(56.dp)
                    .height(72.dp)
                    .clip(MaterialTheme.shapes.small),
            ) {
                // Celda de 56×72 dp: no hace falta decodificar más de ~200 px.
                Thumbnail(thumbnail, mode, Modifier.fillMaxSize(), sizePx = 200)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(mode.icon, null, Modifier.size(14.dp), tint = brand.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${pagesLabel(pageCount)} · ${relativeTime(updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DocumentMenu(actions)
        }
    }
}

/**
 * Miniatura con Coil: decodificada a tamaño reducido (ahorro de memoria) y marcador mientras carga.
 *
 * Rendimiento: antes era un SubcomposeAsyncImage (una subcomposición por celda, lo más caro al desplazar una
 * rejilla). Ahora el marcador se dibuja DEBAJO y un AsyncImage normal encima: sin subcomposición y sin
 * recomponer al terminar de cargar. Tamaño de decodificación según el tier ([LocalPerf]) y precisión INEXACT
 * (solo submuestreo potencia de 2, sin un reescalado extra en CPU).
 */
@Composable
fun Thumbnail(file: File?, mode: ScanMode, modifier: Modifier = Modifier, sizePx: Int = 0) {
    val context = LocalContext.current
    val perf = LocalPerf.current
    val side = if (sizePx > 0) sizePx else perf.thumbPx
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(mode.icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        if (file != null) {
            val request = remember(file.path, side, perf.reduceMotion) {
                ImageRequest.Builder(context)
                    .data(file)
                    .size(side)
                    .precision(Precision.INEXACT)
                    .crossfade(if (perf.reduceMotion) 0 else perf.duration(160))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = PillShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun ModeBadge(mode: ScanMode, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(28.dp)
            .clip(PillShape)
            .background(MaterialTheme.brand.gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(mode.icon, contentDescription = mode.label, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DocumentMenu(actions: DocumentActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Más opciones")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.onRename?.let { action ->
                DropdownMenuItem(
                    text = { Text("Renombrar") },
                    leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                    onClick = { open = false; action() },
                )
            }
            actions.onDuplicate?.let { action ->
                DropdownMenuItem(
                    text = { Text("Duplicar") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { open = false; action() },
                )
            }
            actions.onSharePdf?.let { action ->
                DropdownMenuItem(
                    text = { Text("Compartir PDF") },
                    leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) },
                    onClick = { open = false; action() },
                )
            }
            actions.onDelete?.let { action ->
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}
