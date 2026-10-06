package com.scannerpromax.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scannerpromax.ui.theme.brand

/**
 * Estado vacío ilustrado (dibujado con Canvas: hojas apiladas + línea de escaneo animada).
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    illustrationSize: Dp = 180.dp,
) {
    Column(
        modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScanIllustration(Modifier.size(illustrationSize))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            GradientButton(text = actionLabel, onClick = onAction, icon = Icons.Rounded.DocumentScanner)
        }
    }
}

/** Ilustración vectorial: dos hojas inclinadas, renglones de texto y un haz de escaneo que sube y baja. */
@Composable
fun ScanIllustration(modifier: Modifier = Modifier, animated: Boolean = true) {
    val brand = MaterialTheme.brand
    val paper = MaterialTheme.colorScheme.surfaceContainerHighest
    val paperFront = if (brand.isDark) Color(0xFF242C45) else Color.White
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val start = brand.gradientStart
    val end = brand.gradientEnd

    val t by if (animated) {
        rememberInfiniteTransition(label = "scanLine").animateFloat(
            initialValue = 0.12f,
            targetValue = 0.88f,
            animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "y",
        )
    } else androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Halo de fondo.
        drawCircle(
            brush = Brush.radialGradient(listOf(start.copy(alpha = 0.28f), Color.Transparent), center = center, radius = w * 0.55f),
            radius = w * 0.55f,
        )
        val pw = w * 0.52f
        val ph = h * 0.66f
        val topLeft = Offset((w - pw) / 2f, (h - ph) / 2f)
        val corner = CornerRadius(w * 0.05f)

        rotate(-10f) {
            drawRoundRect(paper, topLeft = topLeft + Offset(-w * 0.06f, h * 0.02f), size = Size(pw, ph), cornerRadius = corner)
        }
        rotate(6f) {
            drawRoundRect(paperFront, topLeft = topLeft, size = Size(pw, ph), cornerRadius = corner)
            drawRoundRect(
                brush = Brush.linearGradient(listOf(start, end), start = topLeft, end = topLeft + Offset(pw, ph)),
                topLeft = topLeft, size = Size(pw, ph), cornerRadius = corner,
                style = Stroke(width = w * 0.012f),
            )
            drawTextLines(topLeft, pw, ph, line)
            // Haz de escaneo.
            val y = topLeft.y + ph * t
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, end.copy(alpha = 0.35f)), startY = y - ph * 0.18f, endY = y,
                ),
                topLeft = Offset(topLeft.x, y - ph * 0.18f), size = Size(pw, ph * 0.18f),
            )
            drawLine(
                brush = Brush.horizontalGradient(listOf(start, end), startX = topLeft.x, endX = topLeft.x + pw),
                start = Offset(topLeft.x - w * 0.04f, y), end = Offset(topLeft.x + pw + w * 0.04f, y),
                strokeWidth = w * 0.018f, cap = StrokeCap.Round,
            )
        }
        // Esquinas de "visor".
        val m = w * 0.1f
        val l = w * 0.12f
        val sw = w * 0.022f
        val c = start.copy(alpha = 0.9f)
        listOf(
            Triple(Offset(m, m), Offset(1f, 0f), Offset(0f, 1f)),
            Triple(Offset(w - m, m), Offset(-1f, 0f), Offset(0f, 1f)),
            Triple(Offset(m, h - m), Offset(1f, 0f), Offset(0f, -1f)),
            Triple(Offset(w - m, h - m), Offset(-1f, 0f), Offset(0f, -1f)),
        ).forEach { (p, dx, dy) ->
            drawLine(c, p, p + dx * l, strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, p, p + dy * l, strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawTextLines(topLeft: Offset, pw: Float, ph: Float, color: Color) {
    val padX = pw * 0.14f
    val stroke = ph * 0.035f
    val widths = floatArrayOf(0.55f, 0.72f, 0.66f, 0.72f, 0.4f, 0.7f, 0.6f)
    var y = topLeft.y + ph * 0.18f
    for (fw in widths) {
        drawLine(
            color, Offset(topLeft.x + padX, y), Offset(topLeft.x + padX + (pw - 2 * padX) * fw / 0.72f, y),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
        y += ph * 0.105f
    }
}
