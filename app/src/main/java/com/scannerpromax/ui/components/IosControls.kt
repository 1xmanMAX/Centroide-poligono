package com.scannerpromax.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.ui

// =============================================================================================
// Controles del rediseño estilo Apple. Solo usan los tokens de MaterialTheme.ui (theme/Theme.kt).
// =============================================================================================

/**
 * Cabecera con título grande (34 sp ExtraBold) estilo iOS.
 *
 * - Sin [onBack]: el título y las [actions] (p. ej. un [IconCircleButton] •••) comparten fila (Inicio).
 * - Con [onBack]: arriba "‹ [backLabel]" en el acento y las acciones a la derecha; debajo el título (Ajustes).
 *
 * No añade el relleno de la barra de estado: ponlo en el contenedor (p. ej. contentPadding de la lista).
 */
@Composable
fun LargeTitleHeader(
    title: String,
    modifier: Modifier = Modifier,
    backLabel: String = "Atrás",
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.displaySmall,
    horizontalPadding: Dp = 20.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val ui = MaterialTheme.ui
    Column(modifier.fillMaxWidth()) {
        if (onBack != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = (horizontalPadding - 10.dp).coerceAtLeast(0.dp), end = (horizontalPadding - 4.dp).coerceAtLeast(0.dp), top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(backLabel, onBack)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            Text(
                title,
                style = titleStyle,
                color = ui.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 4.dp),
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = titleStyle,
                    color = ui.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ui.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 4.dp),
            )
        }
    }
}

/** Botón atrás estilo iOS: "‹ Documentos" en el color de acento (17 sp). */
@Composable
fun BackButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.ui.accent) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = "Volver a $label", onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Back, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(2.dp))
        Text(label, color = color, fontSize = 17.sp, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}

/**
 * Botón redondo con icono (••• de Inicio: 40 dp, tarjeta blanca con sombra mínima).
 * [containerColor] = Color.Transparent para un botón sin fondo.
 */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    containerColor: Color = MaterialTheme.ui.card,
    contentColor: Color = MaterialTheme.ui.label,
    elevation: Dp = 1.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .then(if (elevation > 0.dp && containerColor.alpha > 0f) Modifier.shadow(elevation, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f)) else Modifier)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
    }
}

/**
 * Botón principal en cápsula (Escanear, Exportar, Compartir): acento, 17 sp SemiBold, 52 dp.
 * [tonal] = true → botón gris (relleno #E3E3E8, texto normal), p. ej. "Guardar".
 * [shape] permite el rectángulo redondeado de 14 dp de las hojas.
 */
@Composable
fun PrimaryCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    tonal: Boolean = false,
    height: Dp = 52.dp,
    shape: Shape = PillShape,
) {
    val ui = MaterialTheme.ui
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val perf = LocalPerf.current
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(perf.duration(120)), label = "capsuleScale")
    val bg = if (tonal) ui.fill else ui.accent
    val fg = if (tonal) ui.label else ui.onAccent
    val active = enabled && !loading
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(height)
            .clip(shape)
            .background(bg)
            .clickable(enabled = active, role = Role.Button, interactionSource = interaction, indication = ripple(), onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(start = if (icon != null || loading) 20.dp else 24.dp, end = if (icon != null || loading) 26.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Relleno inferior que necesita una lista para que su último elemento no quede bajo la [FloatingBar]. */
val FloatingBarContentPadding: Dp = 132.dp

/**
 * Barra flotante inferior: cápsula translúcida (blanco 86 % / #1C1C1E 90 %, sombra suave, borde de 0,5 dp) sobre
 * un degradado que desvanece el contenido. Colócala alineada abajo en un Box a pantalla completa:
 *
 *     FloatingBar(Modifier.align(Alignment.BottomCenter)) { IconCircleButton(...); PrimaryCapsuleButton(...) }
 *
 * Añade el relleno de la barra de navegación. [fillWidth] = cápsula de borde a borde (16 dp) y 64 dp de alto,
 * con el contenido repartido (barra de Documento: Añadir/Texto/Filtro + Exportar).
 * Sin desenfoque del fondo (Compose no tiene backdrop blur): el alfa alto y el degradado lo sustituyen.
 */
@Composable
fun FloatingBar(
    modifier: Modifier = Modifier,
    fadeColor: Color = MaterialTheme.ui.groupedBackground,
    fillWidth: Boolean = false,
    fadeHeight: Dp = 150.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val ui = MaterialTheme.ui
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        if (fadeColor.alpha > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to fadeColor.copy(alpha = 0f), 0.55f to fadeColor, 1f to fadeColor))
                    .navigationBarsPadding()
                    .height(fadeHeight - 16.dp),
            )
        }
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .then(if (fillWidth) Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(64.dp) else Modifier)
                .shadow(16.dp, PillShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(PillShape)
                .background(ui.bar)
                .border(0.5.dp, (if (ui.isDark) Color.White else Color.Black).copy(alpha = 0.07f), PillShape)
                .then(if (fillWidth) Modifier.padding(start = 14.dp, end = 8.dp) else Modifier.padding(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (fillWidth) Arrangement.SpaceBetween else Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** Botón vertical (icono 22 dp + etiqueta 11 sp) para una [FloatingBar] de ancho completo. */
@Composable
fun FloatingBarItem(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ui = MaterialTheme.ui
    Column(
        modifier
            .widthIn(min = 56.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ui.label, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = ui.label, fontSize = 11.sp, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * Campo de búsqueda iOS: 40 dp, relleno gris, radio 12, lupa y botón de borrar (círculo con aspa).
 * Al pulsar "Buscar" en el teclado o borrar, se quita el foco.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Buscar",
) {
    val ui = MaterialTheme.ui
    val focus = LocalFocusManager.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = ui.label)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(ui.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ui.fill)
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Search, contentDescription = null, tint = ui.secondaryLabel, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = textStyle, color = ui.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Borrar búsqueda") { onQueryChange(""); focus.clearFocus() }
                            .semantics { contentDescription = "Borrar búsqueda" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.ClearFilled, contentDescription = null, tint = ui.tertiaryLabel, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

/**
 * Control segmentado iOS: pista gris (radio 9, 2 dp de margen) y segmento seleccionado blanco (#636366 en oscuro)
 * con sombra, que se desliza. Texto 14 sp (SemiBold el seleccionado).
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = MaterialTheme.ui
    val perf = LocalPerf.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(ui.fill)
            .padding(2.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val segment = maxWidth / count
        val x by animateDpAsState(segment * selectedIndex.coerceIn(0, count - 1), tween(perf.duration(220)), label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(segment)
                .height(32.dp)
                .shadow(2.dp, RoundedCornerShape(7.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(7.dp))
                .background(if (ui.isDark) Color(0xFF636366) else Color.White),
        )
        Row(Modifier.fillMaxWidth().height(32.dp)) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = ui.label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Interruptor iOS (51×31 dp, verde #34C759, pulgar blanco de 27 dp con sombra).
 * Con [onCheckedChange] = null no es pulsable por sí mismo (la fila entera lo cambia, ver [ToggleRow]).
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ui = MaterialTheme.ui
    val perf = LocalPerf.current
    val track by animateColorAsState(if (checked) ui.green else ui.fill, tween(perf.duration(180)), label = "switchTrack")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, tween(perf.duration(180)), label = "switchThumb")
    Box(
        modifier
            .size(width = 51.dp, height = 31.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(PillShape)
            .background(track)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(27.dp)
                .shadow(2.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
