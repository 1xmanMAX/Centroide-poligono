package com.scannerpromax.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.IdCardSide
import com.scannerpromax.domain.Quad
import com.scannerpromax.ui.camera.CaptureProcessor
import com.scannerpromax.ui.camera.CardDraft
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.theme.ForcedDarkTheme
import com.scannerpromax.ui.theme.ui

/** ¿Se puede usar el recorte [q] (convexo y no degenerado)? */
internal fun validQuad(q: Quad): Boolean = isConvexQuad(q.points().map { Offset(it.x, it.y) })

/**
 * Tras capturar una cara del DNI: la foto con la tarjeta detectada y sus 4 esquinas arrastrables (la misma herramienta
 * de recorte del editor), cómo quedará la cara y, para ESTA cara, giros, espejo y mejora ([common] = la mejora común de
 * la tarjeta). [draft] null = aún detectando. [onAccept] recibe la cara (recorte NORMALIZADO, marca manual, giro, espejo,
 * mejora); [onRetake] descarta la foto y vuelve a la cámara.
 */
@Composable
internal fun IdCardConfirmOverlay(
    processor: CaptureProcessor,
    draft: CardDraft?,
    back: Boolean,
    common: FilterType?,
    onAccept: (IdCardSide) -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = draft?.preview
    val image: ImageBitmap? = remember(preview) { preview?.takeIf { !it.isRecycled }?.asImageBitmap() }
    var quad by remember(draft) {
        mutableStateOf(if (draft != null && preview != null) CaptureProcessor.denormalize(draft.quad, preview.width, preview.height) else null)
    }
    var touched by remember(draft) { mutableStateOf(false) }
    var side by remember(draft) { mutableStateOf(IdCardSide(draft?.quad ?: Quad.full(1, 1))) }
    BackHandler { onRetake() }
    ForcedDarkTheme {
        Box(modifier.fillMaxSize().background(EditorBackground)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Text(
                    if (back) "Reverso · ajusta las esquinas si hace falta" else "Anverso · ajusta las esquinas si hace falta",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    val q = quad
                    if (image == null || q == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("Buscando los bordes de la tarjeta…", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        }
                    } else {
                        CropTool(
                            image = image,
                            quad = q,
                            onQuadChange = { quad = it; touched = true },
                            primary = MaterialTheme.ui.accent,
                            secondary = MaterialTheme.ui.accent,
                            error = MaterialTheme.ui.red,
                        )
                    }
                }
                val q = quad
                if (image != null && q != null && preview != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardSideResult(processor, preview, q, side, common, Modifier.height(76.dp).width(120.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Así quedará en la hoja", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                    CardSideTools(side, common, onSide = { side = it })
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryCapsuleButton("Repetir", onClick = onRetake, tonal = true, modifier = Modifier.weight(1f))
                    PrimaryCapsuleButton(
                        "Aceptar",
                        enabled = q != null && preview != null && validQuad(q),
                        onClick = {
                            if (q != null && preview != null) {
                                onAccept(side.copy(quad = CaptureProcessor.normalize(q, preview.width, preview.height), manual = touched.takeIf { it }))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
