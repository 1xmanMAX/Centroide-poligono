package com.scannerpromax.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.ui

// =============================================================================================
// Diálogos y capa de progreso del rediseño estilo Apple (sustituyen a AlertDialog M3 y LoadingOverlay).
// =============================================================================================

/** Botón de un [IosAlert]: [bold] = acción preferida (SemiBold); [destructive] = rojo. */
class AlertAction(
    val label: String,
    val bold: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    /** Si es false, el diálogo NO llama a onDismiss tras ejecutar la acción (p. ej. validaciones). */
    val dismisses: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Alerta estilo iOS: tarjeta de 290 dp (radio 14), título 17 SemiBold y mensaje 13 centrados, [content] opcional
 * (campo de texto, lista…) y botones de ancho completo separados por líneas finas (en fila si son 2, apilados si son
 * más). Pulsar un botón ejecuta su acción y cierra (salvo [AlertAction.dismisses] = false).
 */
@Composable
fun IosAlert(
    title: String,
    onDismiss: () -> Unit,
    actions: List<AlertAction>,
    message: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.ui.accent,
    dismissOnClickOutside: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val ui = MaterialTheme.ui
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = dismissOnClickOutside, usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 36.dp)
                .widthIn(min = 270.dp, max = 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (ui.isDark) Color(0xFF2C2C2E) else Color(0xFFF7F7F9)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = ui.label, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = ui.label, textAlign = TextAlign.Center)
                }
                if (content != null) {
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth(), content = content)
                }
            }
            val sep = ui.opaqueSeparator.copy(alpha = if (ui.isDark) 0.7f else 0.6f)
            HorizontalDivider(thickness = 0.5.dp, color = sep)
            if (actions.size == 2) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    AlertButton(actions[0], onDismiss, Modifier.weight(1f))
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(sep))
                    AlertButton(actions[1], onDismiss, Modifier.weight(1f))
                }
            } else {
                actions.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = sep)
                    AlertButton(a, onDismiss, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AlertButton(action: AlertAction, onDismiss: () -> Unit, modifier: Modifier) {
    val ui = MaterialTheme.ui
    Box(
        modifier
            .heightIn(min = 46.dp)
            .clickable(enabled = action.enabled, role = Role.Button) {
                action.onClick()
                if (action.dismisses) onDismiss()
            }
            .alpha(if (action.enabled) 1f else 0.4f)
            .padding(horizontal = 8.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.label,
            color = if (action.destructive) ui.red else ui.accent,
            fontSize = 17.sp,
            fontWeight = if (action.bold) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Confirmación estilo iOS: "Cancelar" a la izquierda y la acción (en rojo si [destructive]) a la derecha. */
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
    IosAlert(
        title = title,
        message = message,
        icon = icon,
        iconTint = if (destructive) MaterialTheme.ui.red else MaterialTheme.ui.accent,
        onDismiss = onDismiss,
        actions = listOf(
            AlertAction(dismissLabel, onClick = {}),
            AlertAction(confirmLabel, bold = !destructive, destructive = destructive, onClick = onConfirm),
        ),
    )
}

/** Campo de texto iOS para alertas y formularios: relleno de tarjeta, radio 10, 44 dp. */
@Composable
fun IosTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ui = MaterialTheme.ui
    val style = MaterialTheme.typography.bodyLarge.copy(color = ui.label)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(ui.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ui.card)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style, color = ui.tertiaryLabel, maxLines = 1)
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}

/** Botón ⓧ gris para borrar el contenido de un [IosTextField]. */
@Composable
fun ClearFieldButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Borrar" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.ClearFilled, contentDescription = null, tint = MaterialTheme.ui.tertiaryLabel, modifier = Modifier.size(18.dp))
    }
}

/** Renombrar estilo iOS: alerta con campo (texto seleccionado, foco automático) y Cancelar | Guardar. */
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
    IosAlert(
        title = title,
        onDismiss = onDismiss,
        actions = listOf(
            AlertAction("Cancelar", onClick = {}),
            AlertAction("Guardar", bold = true, enabled = valid, dismisses = false, onClick = { submit() }),
        ),
    ) {
        IosTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = label,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailing = { if (value.text.isNotEmpty()) ClearFieldButton { value = TextFieldValue("") } },
            modifier = Modifier.focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: IllegalStateException) { }
    }
}

/**
 * Capa de progreso estilo iOS (HUD): velo oscuro que bloquea toques y tarjeta translúcida centrada con un
 * indicador (o porcentaje + barra si [progress] 0..1), el [message] y "Cancelar" opcional.
 */
@Composable
fun ProgressHud(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onCancel: (() -> Unit)? = null,
) {
    val perf = LocalPerf.current
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(perf.duration(160))), exit = fadeOut(tween(perf.duration(140))), modifier = modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.Center,
        ) {
            ProgressHudCard(message, progress, onCancel)
        }
    }
}

/** Tarjeta del [ProgressHud] (sin velo), reutilizable en capturas y en la cámara. */
@Composable
fun ProgressHudCard(message: String, progress: Float? = null, onCancel: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val ui = MaterialTheme.ui
    val perf = LocalPerf.current
    Column(
        modifier
            .padding(32.dp)
            .widthIn(min = 200.dp, max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (ui.isDark) Color(0xF22C2C2E) else Color(0xF7FFFFFF))
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = if (onCancel != null) 8.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (progress == null) {
            CircularProgressIndicator(Modifier.size(34.dp), color = ui.secondaryLabel, trackColor = Color.Transparent, strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
        } else {
            val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(perf.duration(250)), label = "hudProgress")
            Text("${(animated * 100).toInt()} %", style = MaterialTheme.typography.headlineSmall, color = ui.label)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = ui.accent,
                trackColor = ui.fill,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ui.label, textAlign = TextAlign.Center)
        if (onCancel != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Cancelar",
                color = ui.accent,
                fontSize = 17.sp,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Mensaje centrado para estados vacíos (icono gris grande, título y texto). Sustituye a EmptyState. */
@Composable
fun EmptyMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = AppIcons.Document,
    action: (@Composable () -> Unit)? = null,
) {
    val ui = MaterialTheme.ui
    Column(
        modifier.padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ui.tertiaryLabel, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = ui.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}
