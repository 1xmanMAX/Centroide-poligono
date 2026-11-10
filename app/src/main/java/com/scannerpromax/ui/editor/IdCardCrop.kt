package com.scannerpromax.ui.editor

import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.IdCardEdits
import com.scannerpromax.domain.IdCardSide
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.ui.camera.CaptureProcessor
import com.scannerpromax.ui.camera.CardDraft
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.theme.ForcedDarkTheme
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ¿Se puede usar el recorte [q] (convexo y no degenerado)? */
private fun validQuad(q: Quad): Boolean = isConvexQuad(q.points().map { Offset(it.x, it.y) })

/**
 * Tras capturar una cara del DNI: la foto con la tarjeta detectada y sus 4 esquinas arrastrables (la misma
 * herramienta de recorte del editor). [draft] null = aún detectando. [onAccept] recibe el recorte NORMALIZADO y si el
 * usuario lo tocó; [onRetake] descarta la foto y vuelve a la cámara.
 */
@Composable
internal fun IdCardConfirmOverlay(
    draft: CardDraft?,
    back: Boolean,
    onAccept: (Quad, Boolean) -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = draft?.preview
    val image: ImageBitmap? = remember(preview) { preview?.takeIf { !it.isRecycled }?.asImageBitmap() }
    var quad by remember(draft) {
        mutableStateOf(if (draft != null && preview != null) CaptureProcessor.denormalize(draft.quad, preview.width, preview.height) else null)
    }
    var touched by remember(draft) { mutableStateOf(false) }
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryCapsuleButton("Repetir", onClick = onRetake, tonal = true, modifier = Modifier.weight(1f))
                    PrimaryCapsuleButton(
                        "Aceptar",
                        enabled = q != null && preview != null && validQuad(q),
                        onClick = {
                            if (q != null && preview != null) onAccept(CaptureProcessor.normalize(q, preview.width, preview.height), touched)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Corregir el recorte de un DNI ya guardado (Revisión › Editar › Recortar): anverso y reverso sobre sus fotos
 * ORIGINALES, con las esquinas guardadas (las manuales se respetan: sólo «Auto» vuelve a detectar). «Listo»
 * recompone la hoja.
 */
@Composable
internal fun IdCardCropScreen(container: AppContainer, docId: String, pageId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = remember { CaptureProcessor(container) }
    var card by remember { mutableStateOf<IdCardEdits?>(null) }
    val previews = remember { arrayOfNulls<Bitmap>(2) }
    var images by remember { mutableStateOf<List<ImageBitmap?>>(listOf(null, null)) }
    var side by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(docId, pageId) {
        val page = container.documents.get(docId)?.pages?.firstOrNull { it.id == pageId }
        val c = page?.edits?.idCard
        if (c == null) { loadError = true; return@LaunchedEffect }
        val loaded = withContext(Dispatchers.IO) {
            listOf(false, true).map { back ->
                val f = container.documents.idSourceFile(docId, pageId, back)
                if ((back && c.back == null) || !f.exists()) null else runCatching { BitmapIO.decodeThumbnail(f.absolutePath, 1600) }.getOrNull()
            }
        }
        if (loaded[0] == null) { loaded.forEach { it?.recycle() }; loadError = true; return@LaunchedEffect }
        loaded.forEachIndexed { i, b -> previews[i] = b }
        images = loaded.map { it?.asImageBitmap() }
        card = c
    }
    LaunchedEffect(loadError) {
        if (loadError) { toast("No se encontraron las fotos originales de la tarjeta"); onBack() }
    }
    DisposableEffect(Unit) { onDispose { previews.forEach { it?.recycle() } } }

    fun sideOf(c: IdCardEdits, i: Int): IdCardSide? = if (i == 0) c.front else c.back
    fun withSide(c: IdCardEdits, i: Int, s: IdCardSide): IdCardEdits = if (i == 0) c.copy(front = s) else c.copy(back = s)

    fun save() {
        val c = card ?: return
        if (saving) return
        if (listOfNotNull(c.front, c.back).any { !validQuad(it.quad) }) { toast("Las esquinas se cruzan: ajústalas"); return }
        saving = true
        scope.launch {
            try {
                processor.recomposeIdCard(docId, pageId, c)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("No se pudo recomponer la hoja")
            } finally {
                saving = false
            }
        }
    }

    fun autoDetect() {
        val c = card ?: return
        if (detecting) return
        detecting = true
        val i = side
        scope.launch {
            try {
                val f = container.documents.idSourceFile(docId, pageId, back = i == 1)
                val d = processor.detectCardSide(f, null, withPreview = false)
                if (d.confidence <= 0f) toast("No se detectaron los bordes: ajusta las esquinas a mano")
                else card = withSide(c, i, IdCardSide(d.quad, null))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("No se pudo detectar la tarjeta")
            } finally {
                detecting = false
            }
        }
    }

    BackHandler(enabled = !saving) { onBack() }
    ForcedDarkTheme {
        Box(Modifier.fillMaxSize().background(EditorBackground)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val c = card
                Text(
                    "Recortar DNI",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                if (c?.back != null) {
                    SegmentedControl(
                        options = listOf("Anverso", "Reverso"),
                        selectedIndex = side,
                        onSelect = { side = it },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    val img = images.getOrNull(side)
                    val s = c?.let { sideOf(it, side) }
                    if (c == null || img == null || s == null) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 3.dp)
                    } else {
                        CropTool(
                            image = img,
                            quad = CaptureProcessor.denormalize(s.quad, img.width, img.height),
                            onQuadChange = { q ->
                                card = withSide(c, side, IdCardSide(CaptureProcessor.normalize(q, img.width, img.height), manual = true))
                            },
                            primary = MaterialTheme.ui.accent,
                            secondary = MaterialTheme.ui.accent,
                            error = MaterialTheme.ui.red,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryCapsuleButton("Cancelar", onClick = { if (!saving) onBack() }, tonal = true, modifier = Modifier.weight(1f))
                    PrimaryCapsuleButton("Auto", onClick = { autoDetect() }, tonal = true, loading = detecting, enabled = c != null && !saving, modifier = Modifier.weight(1f))
                    PrimaryCapsuleButton("Listo", onClick = { save() }, loading = saving, enabled = c != null, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}
