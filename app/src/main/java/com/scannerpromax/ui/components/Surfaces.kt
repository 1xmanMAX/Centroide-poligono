package com.scannerpromax.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scannerpromax.ui.theme.brand

/**
 * Tarjeta base de la app: fondo tonal elevado + borde sutil. Si [onClick] no es null es pulsable.
 * Úsala para agrupar contenido (ajustes, resultados, información).
 */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val brand = MaterialTheme.brand
    val border = BorderStroke(1.dp, brand.cardBorder)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = brand.card, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = brand.card, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** Texto pintado con el degradado de marca (ideal para títulos / la marca). */
@Composable
fun GradientText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    brush: Brush? = null,
    maxLines: Int = 1,
) {
    val b = brush ?: MaterialTheme.brand.horizontalGradient
    val styled = remember(style, b) { style.merge(TextStyle(brush = b)) }
    Text(text = text, modifier = modifier, style = styled, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
