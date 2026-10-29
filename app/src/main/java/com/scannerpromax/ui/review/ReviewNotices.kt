package com.scannerpromax.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.ai.jev.JevCapture
import com.scannerpromax.ai.jev.PageIssue
import com.scannerpromax.domain.Document
import com.scannerpromax.domain.Page
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.theme.ui

/**
 * Avisos discretos de la revisión (estilo de la fila de estado de la IA: tarjeta, una línea, acciones de texto):
 * páginas duplicadas del lote (ORB local, o Jev en la zona gris) y carpeta sugerida por "Mejorar con Jev".
 */
internal object ReviewNotices {

    /** Primer par (página duplicada, la que repite) con ambas aún en el documento: números 1..n y la página a borrar. */
    fun duplicate(pages: List<Page>): Triple<Int, Int, Page>? {
        for ((i, p) in pages.withIndex()) {
            val other = p.dupOf ?: continue
            val j = pages.indexOfFirst { it.id == other }
            if (j < 0 || j == i) continue
            return Triple(minOf(i, j) + 1, maxOf(i, j) + 1, p)
        }
        return null
    }

    /** Texto del aviso: «Las páginas 3 y 4 parecen la misma.» */
    fun duplicateText(a: Int, b: Int) = "Las páginas $a y $b parecen la misma."

    /** Carpeta sugerida pendiente (nombre para la interfaz) o null. */
    fun folder(d: Document?): String? =
        d?.takeIf { it.category == null }?.suggestedCategory?.takeIf { it.isNotEmpty() }?.let { JevCapture.folderLabel(it) }
}

@Composable
internal fun NoticeRow(
    icon: ImageVector,
    text: String,
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val ui = MaterialTheme.ui
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ui.card)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ui.secondaryLabel, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = ui.label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        for ((label, onClick) in actions) NoticeTextButton(label, onClick, bold = label == actions.first().first)
        if (onDismiss != null) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = "Descartar" },
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.Close, null, tint = ui.tertiaryLabel, modifier = Modifier.size(12.dp)) }
        }
    }
}

@Composable
private fun NoticeTextButton(label: String, onClick: () -> Unit, bold: Boolean) {
    val ui = MaterialTheme.ui
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = ui.accent,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** Los avisos de la revisión, apilados (vacío si no hay ninguno). */
@Composable
internal fun ReviewNoticeList(
    pages: List<Page>,
    doc: Document?,
    onDeleteDuplicate: (Page) -> Unit,
    onKeepDuplicate: (Page) -> Unit,
    onAcceptFolder: () -> Unit,
    onDismissFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dup = ReviewNotices.duplicate(pages)
    val folder = ReviewNotices.folder(doc)
    if (dup == null && folder == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dup != null) {
            val (a, b, page) = dup
            NoticeRow(
                AppIcons.Duplicate, ReviewNotices.duplicateText(a, b),
                listOf("Borrar una" to { onDeleteDuplicate(page) }, "Mantener" to { onKeepDuplicate(page) }),
            )
        }
        if (folder != null) {
            NoticeRow(AppIcons.Folder, "¿Mover a $folder?", listOf("Mover" to onAcceptFolder), onDismiss = onDismissFolder)
        }
    }
}

/** Marca de aviso de calidad sobre la miniatura (triángulo naranja en un círculo blanco). */
@Composable
internal fun IssueBadge(page: Page, modifier: Modifier = Modifier) {
    val text = PageIssue.sentence(page.issues.orEmpty()) ?: return
    val ui = MaterialTheme.ui
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f))
            .semantics { contentDescription = "$text: conviene reescanear" },
        contentAlignment = Alignment.Center,
    ) { Icon(AppIcons.Warning, null, tint = ui.orange, modifier = Modifier.size(15.dp)) }
}

/** Aviso del visor: «Reflejo · conviene reescanear» con «Reescanear» y ✕. */
@Composable
internal fun ViewerIssueBanner(codes: List<String>, onRetake: (() -> Unit)?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = PageIssue.sentence(codes) ?: return
    val ui = MaterialTheme.ui
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xE61C1C1E))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Warning, null, tint = ui.orange, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text("$text · conviene reescanear", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onRetake != null) {
            Text(
                "Reescanear", color = ui.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onRetake).padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = "Descartar aviso" },
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Close, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(11.dp)) }
    }
}

/** Hoja «Carpeta»: elegir (o quitar) la carpeta del documento. */
@Composable
internal fun FolderPickerSheet(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    com.scannerpromax.ui.components.IosSheet(onDismissRequest = onDismiss) { dismiss ->
        com.scannerpromax.ui.components.SheetHeader(title = "Carpeta", onCancel = dismiss)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 28.dp),
        ) {
            com.scannerpromax.ui.components.InsetGroup(separatorInset = com.scannerpromax.ui.components.SeparatorInsetPlain) {
                item { com.scannerpromax.ui.components.CheckRow(title = "Sin carpeta", selected = current == null, onClick = { onPick(null); dismiss() }) }
                for (k in JevCapture.FOLDERS.keys) {
                    val label = JevCapture.folderLabel(k) ?: continue
                    item { com.scannerpromax.ui.components.CheckRow(title = label, selected = current == k, onClick = { onPick(k); dismiss() }) }
                }
            }
        }
    }
}
