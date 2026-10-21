package com.scannerpromax.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.scannerpromax.data.VersionedFile
import com.scannerpromax.domain.ScanMode
import com.scannerpromax.ui.theme.CardShape
import com.scannerpromax.ui.theme.GroupShape
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.ui

/** Acciones del menú contextual de un documento. null = opción oculta. */
class DocumentActions(
    val onRename: (() -> Unit)? = null,
    val onDuplicate: (() -> Unit)? = null,
    val onSharePdf: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/** Grupos del menú contextual (Renombrar, Duplicar, Compartir PDF | Eliminar). */
private fun DocumentActions.menuGroups(): List<List<MoreMenuItem>> = listOf(
    listOfNotNull(
        onRename?.let { MoreMenuItem("Renombrar", AppIcons.Rename, onClick = it) },
        onDuplicate?.let { MoreMenuItem("Duplicar", AppIcons.Duplicate, onClick = it) },
        onSharePdf?.let { MoreMenuItem("Compartir PDF", AppIcons.Share, onClick = it) },
    ),
    listOfNotNull(onDelete?.let { MoreMenuItem("Eliminar", AppIcons.Trash, destructive = true, onClick = it) }),
)

/** Posición de una fila dentro del grupo de la vista de lista (para redondear solo los extremos). */
enum class ListPosition { Single, First, Middle, Last }

/**
 * Tarjeta de documento para la cuadrícula de Inicio (maqueta "Main"): miniatura en tarjeta cuadrada blanca con
 * insignia del nº de páginas, y debajo nombre (15 sp SemiBold) + fecha (13 sp gris) + "•••" pequeño.
 * Pulsación larga o "•••" = menú contextual (Renombrar, Duplicar, Compartir PDF, Eliminar).
 *
 * [thumbnail] dibuja la miniatura (por defecto [Thumbnail] con el archivo); las capturas de prueba pasan otra.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
    title: String,
    pageCount: Int,
    updatedAt: Long,
    thumbnail: VersionedFile?,
    mode: ScanMode,
    onClick: () -> Unit,
    actions: DocumentActions,
    modifier: Modifier = Modifier,
    menuInitiallyOpen: Boolean = false,
    thumbnailContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    val ui = MaterialTheme.ui
    val haptics = LocalHapticFeedback.current
    var menuOpen by rememberSaveable { mutableStateOf(menuInitiallyOpen) }
    Column(modifier) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(1.5.dp, CardShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                    .clip(CardShape)
                    .background(ui.card)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = "Abrir",
                        onLongClickLabel = "Más opciones",
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                        onClick = onClick,
                    ),
            ) {
                Box(Modifier.fillMaxSize().padding(10.dp)) {
                    if (thumbnailContent != null) thumbnailContent() else Thumbnail(thumbnail, mode, Modifier.fillMaxSize())
                }
                PageCountBadge(pageCount, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
            MoreMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, groups = actions.menuGroups())
        }
        Row(Modifier.padding(start = 2.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = ui.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(relativeTime(updatedAt), style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel, maxLines = 1)
            }
            SmallMoreButton { menuOpen = true }
        }
    }
}

/**
 * Fila de documento para la vista de lista: miniatura 44×56, nombre, "N páginas · fecha" y "•••".
 * Las filas consecutivas forman un grupo blanco (radio 12 solo en los extremos, separador con sangría).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentListItem(
    title: String,
    pageCount: Int,
    updatedAt: Long,
    thumbnail: VersionedFile?,
    mode: ScanMode,
    onClick: () -> Unit,
    actions: DocumentActions,
    modifier: Modifier = Modifier,
    position: ListPosition = ListPosition.Single,
    thumbnailContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    val ui = MaterialTheme.ui
    val haptics = LocalHapticFeedback.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val shape = when (position) {
        ListPosition.Single -> GroupShape
        ListPosition.First -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ListPosition.Middle -> RoundedCornerShape(0.dp)
        ListPosition.Last -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    }
    Box(modifier.clip(shape).background(ui.card)) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Abrir",
                    onLongClickLabel = "Más opciones",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onClick = onClick,
                )
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(44.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ui.groupedBackground),
            ) {
                // Celda de 44×56 dp: no hace falta decodificar más de ~200 px.
                if (thumbnailContent != null) thumbnailContent() else Thumbnail(thumbnail, mode, Modifier.fillMaxSize(), sizePx = 200)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = ui.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${pagesLabel(pageCount)} · ${relativeTime(updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ui.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                SmallMoreButton(size = 40) { menuOpen = true }
                MoreMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, groups = actions.menuGroups())
            }
        }
        if (position == ListPosition.First || position == ListPosition.Middle) {
            HorizontalDivider(
                Modifier.align(Alignment.BottomStart).padding(start = 68.dp),
                thickness = 0.5.dp,
                color = ui.separator,
            )
        }
    }
}

@Composable
private fun SmallMoreButton(size: Int = 32, onClick: () -> Unit) {
    IconCircleButton(
        icon = AppIcons.More,
        contentDescription = "Más opciones",
        onClick = onClick,
        size = size.dp,
        iconSize = 18.dp,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.ui.secondaryLabel,
        elevation = 0.dp,
    )
}

/** Insignia del nº de páginas (esquina inferior derecha de la tarjeta). */
@Composable
fun PageCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xB81C1C1E))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/**
 * Miniatura con Coil: decodificada a tamaño reducido (ahorro de memoria) y marcador mientras carga.
 *
 * Rendimiento: el marcador se dibuja DEBAJO y un AsyncImage normal encima (sin subcomposición). Tamaño de
 * decodificación según el tier ([LocalPerf]) y precisión INEXACT.
 *
 * La página se muestra ENTERA (ContentScale.Fit): lo que se ve es lo que se exporta, sin recortar. La clave de
 * caché lleva la versión del archivo ([VersionedFile.cacheKey]): si la miniatura se regenera, no se ve la vieja.
 */
@Composable
fun Thumbnail(file: VersionedFile?, mode: ScanMode, modifier: Modifier = Modifier, sizePx: Int = 0) {
    val context = LocalContext.current
    val perf = LocalPerf.current
    val ui = MaterialTheme.ui
    val side = if (sizePx > 0) sizePx else perf.thumbPx
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(AppIcons.Document, contentDescription = mode.shortLabel, modifier = Modifier.size(28.dp), tint = ui.tertiaryLabel)
        if (file != null) {
            val request = remember(file.cacheKey, side, perf.reduceMotion) {
                ImageRequest.Builder(context)
                    .data(file.file)
                    .memoryCacheKey(file.cacheKey)
                    .size(side)
                    .precision(Precision.INEXACT)
                    .crossfade(if (perf.reduceMotion) 0 else perf.duration(160))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
