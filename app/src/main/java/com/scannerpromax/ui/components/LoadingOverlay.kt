package com.scannerpromax.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scannerpromax.ui.theme.brand

/**
 * Capa de carga a pantalla completa que bloquea toques. [progress] null = indeterminado; 0..1 = barra.
 */
@Composable
fun LoadingOverlay(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onCancel: (() -> Unit)? = null,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.Center,
        ) {
            run {
                BrandCard(Modifier.widthIn(min = 240.dp, max = 320.dp).padding(24.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (progress == null) {
                            CircularProgressIndicator(
                                Modifier.size(44.dp),
                                color = MaterialTheme.brand.gradientStart,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                strokeCap = StrokeCap.Round,
                            )
                        } else {
                            val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(250), label = "progress")
                            Text(
                                "${(animated * 100).toInt()} %",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.brand.gradientStart,
                            )
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { animated },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.small),
                                color = MaterialTheme.brand.gradientEnd,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                strokeCap = StrokeCap.Round,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        if (onCancel != null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onCancel) { Text("Cancelar") }
                        }
                    }
                }
            }
        }
    }
}
