package com.scannerpromax.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.ui
import kotlin.math.max

private val MenuShadowPad = 12.dp

/** Opción de un [MoreMenu]: texto a la izquierda, icono a la derecha; [destructive] en rojo. */
@Immutable
class MoreMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Menú emergente estilo iOS (•••): tarjeta de 250 dp casi opaca, radio 14, sombra amplia; filas de 46 dp con el
 * texto a la izquierda y el icono a la derecha; los [groups] se separan con una banda gris de 7 dp.
 *
 * Se usa como DropdownMenu: dentro del mismo contenedor que el botón que lo abre (se ancla a sus límites, alineado
 * a la derecha y debajo; si no cabe, encima). Pulsar una opción cierra el menú y luego ejecuta su acción.
 * Para oscurecer la pantalla detrás (como la maqueta), pinta un [MenuScrim] en la raíz de la pantalla.
 */
@Composable
fun MoreMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    groups: List<List<MoreMenuItem>>,
    width: Dp = 250.dp,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState) return
    val density = LocalDensity.current
    val perf = LocalPerf.current
    val provider = remember(density) {
        with(density) { MenuPositionProvider(marginPx = 8.dp.roundToPx(), gapPx = 8.dp.roundToPx(), padPx = MenuShadowPad.roundToPx()) }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(tween(perf.duration(140))) +
                scaleIn(tween(perf.duration(180)), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(perf.duration(120))),
        ) {
            MoreMenuPanel(
                groups = groups,
                width = width,
                onItemClick = { item ->
                    onDismissRequest()
                    item.onClick()
                },
                // Margen para que la sombra no quede recortada por la ventana emergente.
                modifier = Modifier.padding(MenuShadowPad),
            )
        }
    }
}

/** Contenido visual del [MoreMenu] (sin ventana emergente): útil para vistas previas y capturas. */
@Composable
fun MoreMenuPanel(
    groups: List<List<MoreMenuItem>>,
    onItemClick: (MoreMenuItem) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 250.dp,
) {
    val ui = MaterialTheme.ui
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .width(width)
            .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .background(ui.menu)
            .verticalScroll(rememberScrollState()),
    ) {
        val visible = groups.filter { it.isNotEmpty() }
        visible.forEachIndexed { g, group ->
            if (g > 0) Spacer(Modifier.fillMaxWidth().height(7.dp).background(ui.menuGroupGap))
            group.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = ui.opaqueSeparator.copy(alpha = if (ui.isDark) 0.6f else 0.75f))
                MenuRow(item) { onItemClick(item) }
            }
        }
    }
}

@Composable
private fun MenuRow(item: MoreMenuItem, onClick: () -> Unit) {
    val ui = MaterialTheme.ui
    val color = if (item.destructive) ui.red else ui.label
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable(enabled = item.enabled, role = Role.Button, onClick = onClick)
            .alpha(if (item.enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.icon != null) {
            Spacer(Modifier.width(12.dp))
            Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Velo gris (negro 18 %) a pantalla completa mientras un [MoreMenu] está abierto. Ponlo en la raíz de la pantalla,
 * encima del contenido. No intercepta toques (el menú emergente ya cierra al tocar fuera).
 */
@Composable
fun MenuScrim(visible: Boolean, modifier: Modifier = Modifier) {
    val perf = LocalPerf.current
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(tween(perf.duration(160))), exit = fadeOut(tween(perf.duration(140)))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f)),
        )
    }
}

/**
 * Alinea la tarjeta del menú al borde derecho del ancla, debajo; si no cabe, encima; siempre dentro de la ventana.
 * [padPx] = margen transparente alrededor de la tarjeta (para la sombra), que se descuenta al alinear.
 */
private class MenuPositionProvider(private val marginPx: Int, private val gapPx: Int, private val padPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val minX = marginPx - padPx
        val maxX = max(minX, windowSize.width - marginPx - w + padPx)
        val x = (anchorBounds.right + padPx - w).coerceIn(minX, maxX)
        val below = anchorBounds.bottom + gapPx - padPx
        val above = anchorBounds.top - gapPx - h + padPx
        val y = when {
            below + h - padPx <= windowSize.height - marginPx -> below
            above + padPx >= marginPx -> above
            else -> max(marginPx - padPx, windowSize.height - marginPx - h + padPx)
        }
        return IntOffset(x, y)
    }
}
