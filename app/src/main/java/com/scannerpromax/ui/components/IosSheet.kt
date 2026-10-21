package com.scannerpromax.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.ui.theme.SheetShape
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.launch

/**
 * Cabecera de hoja iOS: asa gris (36×5 dp) y fila "Cancelar · Título · Listo".
 * [onCancel]/[onDone] null = se deja el hueco (el título queda centrado).
 */
@Composable
fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    cancelLabel: String = "Cancelar",
    onDone: (() -> Unit)? = null,
    doneLabel: String = "Listo",
) {
    val ui = MaterialTheme.ui
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ui.opaqueSeparator),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (onCancel != null) SheetTextButton(cancelLabel, onCancel, bold = false)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ui.label,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (onDone != null) SheetTextButton(doneLabel, onDone, bold = true)
            }
        }
    }
}

@Composable
private fun SheetTextButton(label: String, onClick: () -> Unit, bold: Boolean) {
    Text(
        label,
        color = MaterialTheme.ui.accent,
        fontSize = 17.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
    )
}

/**
 * Hoja inferior iOS: fondo agrupado gris, esquinas de 14 dp, sin asa de Material (la pone [SheetHeader]).
 * [content] recibe `dismiss`, que cierra con animación y luego llama a [onDismissRequest].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
        shape = SheetShape,
        containerColor = MaterialTheme.ui.groupedBackground,
        contentColor = MaterialTheme.ui.label,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = null,
    ) {
        content(dismiss)
    }
}

/**
 * Hoja de opciones de selección única (filtro por defecto, tamaño de página, calidad, texto en PDF…):
 * [SheetHeader] con "Cancelar" y un grupo de [CheckRow] (con su descripción). Elegir una opción la guarda y cierra.
 */
@Composable
fun <T> OptionsSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: (T) -> String? = { null },
    footer: String? = null,
) {
    IosSheet(onDismissRequest = onDismiss) { dismiss ->
        SheetHeader(title = title, onCancel = dismiss)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 28.dp),
        ) {
            InsetGroup(footer = footer, separatorInset = SeparatorInsetPlain) {
                options.forEach { option ->
                    item {
                        CheckRow(
                            title = label(option),
                            subtitle = description(option),
                            selected = option == selected,
                            onClick = {
                                onSelect(option)
                                dismiss()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
