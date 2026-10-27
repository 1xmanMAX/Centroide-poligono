package com.scannerpromax.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scannerpromax.ui.theme.GroupShape
import com.scannerpromax.ui.theme.ui

// =============================================================================================
// Listas agrupadas estilo iOS ("inset grouped"): grupos blancos de radio 12 sobre el fondo gris, con cabecera
// en versalitas grises y pie explicativo opcional. Filas de 48 dp con icono en cuadrado de color de 30 dp.
// =============================================================================================

/** Contenido de un [InsetGroup]: cada [item] es una fila; los separadores se dibujan solos entre filas. */
class InsetGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/** Sangría del separador para filas con icono (12 + 30 + 12 dp). Sin icono usa [SeparatorInsetPlain]. */
val SeparatorInsetIcon: Dp = 54.dp
val SeparatorInsetPlain: Dp = 16.dp

/**
 * Grupo de una lista agrupada iOS. Márgenes laterales de 20 dp (ajústalos con [horizontalPadding]).
 *
 *     InsetGroup(header = "Escaneo", footer = "…") {
 *         item { NavigationRow("Filtro por defecto", value = "Color natural", icon = AppIcons.FilterHalf, iconTint = …) { … } }
 *         item { ToggleRow("Captura automática", checked, { … }, icon = AppIcons.Bolt, iconTint = SystemOrange) }
 *     }
 */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    separatorInset: Dp = SeparatorInsetIcon,
    horizontalPadding: Dp = 20.dp,
    content: InsetGroupScope.() -> Unit,
) {
    val ui = MaterialTheme.ui
    val scope = InsetGroupScope().apply(content)
    Column(modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = ui.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .background(ui.card),
        ) {
            scope.items.forEachIndexed { i, row ->
                row()
                if (i < scope.items.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = separatorInset), thickness = 0.5.dp, color = ui.separator)
                }
            }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = ui.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** Icono blanco en un cuadrado de color de 30 dp (radio 7), como en Ajustes de iOS. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * Fila genérica de un [InsetGroup]: [icon] opcional (en [IconTile]), título (16 sp) con [subtitle] opcional,
 * [value] gris a la derecha, [trailing] (interruptor, check…) y chevron si [chevron].
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.ui.accent,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.ui.label,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ui = MaterialTheme.ui
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = if (icon != null) 12.dp else 16.dp, end = 14.dp, top = if (subtitle != null) 9.dp else 0.dp, bottom = if (subtitle != null) 9.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon, iconTint, Modifier.alpha(if (enabled) 1f else 0.5f))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.4f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = ui.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Icon(AppIcons.Chevron, contentDescription = null, tint = ui.tertiaryLabel, modifier = Modifier.size(14.dp))
        }
    }
}

/** Fila que abre otra pantalla u hoja: valor gris + chevron. */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.ui.accent,
    value: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    GroupRow(
        title = title,
        modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        icon = icon,
        iconTint = iconTint,
        subtitle = subtitle,
        value = value,
        chevron = true,
        enabled = enabled,
    )
}

/** Fila con interruptor iOS: toda la fila lo cambia. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.ui.accent,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    GroupRow(
        title = title,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        icon = icon,
        iconTint = iconTint,
        subtitle = subtitle,
        enabled = enabled,
        trailing = { IosSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** Fila de selección única con marca ✓ en el acento a la derecha (listas de opciones). */
@Composable
fun CheckRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.ui.accent,
) {
    val ui = MaterialTheme.ui
    GroupRow(
        title = title,
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        icon = icon,
        iconTint = iconTint,
        subtitle = subtitle,
        trailing = {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(AppIcons.Check, contentDescription = "Seleccionado", tint = ui.accent, modifier = Modifier.size(20.dp))
            }
        },
    )
}

/** Fila informativa (solo lectura): título a la izquierda y valor gris a la derecha, o debajo si es largo. */
@Composable
fun InfoRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.ui.accent,
    stacked: Boolean = false,
) {
    if (stacked) {
        GroupRow(title = title, modifier = modifier, icon = icon, iconTint = iconTint, subtitle = value)
    } else {
        GroupRow(title = title, modifier = modifier, icon = icon, iconTint = iconTint, value = value)
    }
}

/** Separación vertical estándar entre grupos (22 dp en la maqueta). */
@Composable
fun GroupSpacer(height: Dp = 22.dp) {
    Spacer(Modifier.padding(top = height))
}

/** Conveniencia: columna con grupos separados 22 dp. */
val GroupArrangement: Arrangement.Vertical = Arrangement.spacedBy(22.dp)
