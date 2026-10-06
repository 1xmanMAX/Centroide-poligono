package com.scannerpromax.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scannerpromax.ui.theme.PillShape
import com.scannerpromax.ui.theme.brand

/**
 * Botón principal con degradado de marca, ligera escala al pulsar y estado de carga.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    brush: Brush? = null,
    height: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "btnScale")
    val active = enabled && !loading
    val bg = brush ?: MaterialTheme.brand.horizontalGradient

    Surface(
        onClick = onClick,
        enabled = active,
        interactionSource = interaction,
        shape = PillShape,
        color = Color.Transparent,
        contentColor = Color.White,
        modifier = modifier
            .defaultMinSize(minHeight = height)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Box(
            Modifier
                .alpha(if (enabled) 1f else 0.45f)
                .background(bg)
                .padding(horizontal = 24.dp)
                .defaultMinSize(minHeight = height),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                } else if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Botón secundario tonal (fondo suave, borde fino). Para acciones que acompañan a [GradientButton].
 */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.brand.cardBorder),
        modifier = modifier.defaultMinSize(minHeight = height),
    ) {
        Row(
            Modifier
                .alpha(if (enabled) 1f else 0.45f)
                .padding(horizontal = 20.dp)
                .defaultMinSize(minHeight = height),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * FAB grande de escaneo con degradado, halo pulsante sutil y texto que se contrae al hacer scroll.
 * @param expanded muestra el texto junto al icono.
 * @param pulse desactívalo en gama baja si se quiere ahorrar batería (la animación es ligera).
 */
@Composable
fun PrimaryFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "Escanear",
    icon: ImageVector = Icons.Rounded.DocumentScanner,
    expanded: Boolean = true,
    pulse: Boolean = true,
) {
    val brand = MaterialTheme.brand
    val glow = brand.glow
    val transition = rememberInfiniteTransition(label = "fabPulse")
    val pulseProgress by if (pulse) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
            label = "halo",
        )
    } else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(140, easing = FastOutSlowInEasing), label = "fabScale")

    Box(modifier, contentAlignment = Alignment.Center) {
        // Halo: se dibuja detrás sin afectar al layout.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    if (pulse) {
                        val p = pulseProgress
                        val grow = 1f + p * 0.35f
                        val r = size.height / 2f
                        drawRoundRect(
                            color = glow.copy(alpha = glow.alpha * (1f - p)),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                -size.width * (grow - 1f) / 2f, -size.height * (grow - 1f) / 2f,
                            ),
                            size = androidx.compose.ui.geometry.Size(size.width * grow, size.height * grow),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * grow, r * grow),
                        )
                    }
                },
        )
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = PillShape,
            color = Color.Transparent,
            contentColor = Color.White,
            shadowElevation = 10.dp,
            modifier = Modifier.scale(scale),
        ) {
            Row(
                Modifier
                    .clip(PillShape)
                    .background(brand.gradient)
                    .height(64.dp)
                    .padding(horizontal = if (expanded) 24.dp else 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = text, modifier = Modifier.size(26.dp))
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Contenido de relleno estándar para botones de pantalla completa. */
val ButtonRowPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
