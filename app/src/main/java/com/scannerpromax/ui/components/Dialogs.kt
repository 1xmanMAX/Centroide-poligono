package com.scannerpromax.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue

/** Diálogo de confirmación. [destructive] pinta el botón de confirmar en rojo. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Aceptar",
    dismissLabel: String = "Cancelar",
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { vector -> @Composable { Icon(vector, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** Diálogo para renombrar: campo con el texto seleccionado y foco automático. */
@Composable
fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Renombrar documento",
    label: String = "Nombre",
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank()
    val submit = { if (valid) { onConfirm(value.text.trim()); onDismiss() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                isError = !valid,
                trailingIcon = {
                    if (value.text.isNotEmpty()) {
                        IconButton(onClick = { value = TextFieldValue("") }) { Icon(Icons.Rounded.Close, "Borrar") }
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = valid) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        shape = MaterialTheme.shapes.extraLarge,
    )
    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: IllegalStateException) { }
    }
}
