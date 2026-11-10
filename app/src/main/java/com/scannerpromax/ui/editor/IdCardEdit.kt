package com.scannerpromax.ui.editor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.scannerpromax.di.AppContainer
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.IdCardEdits
import com.scannerpromax.domain.IdCardSide
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.BitmapIO
import com.scannerpromax.imaging.IdCardComposer
import com.scannerpromax.imaging.IdCardSize
import com.scannerpromax.ui.camera.CameraEngine
import com.scannerpromax.ui.camera.CaptureProcessor
import com.scannerpromax.ui.camera.FlashSetting
import com.scannerpromax.ui.camera.FrameMapping
import com.scannerpromax.ui.camera.idCardGuideRect
import com.scannerpromax.ui.components.AlertAction
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.IosAlert
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.theme.ForcedDarkTheme
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ====================================================================================== piezas comunes

/** Mejoras que se ofrecen para cada cara del DNI (en este orden). */
internal val CardFilters: List<FilterType> = listOf(FilterType.VIVID, FilterType.ORIGINAL, FilterType.NATURAL, FilterType.BLACK_WHITE)

internal fun cardFilterLabel(f: FilterType): String = when (f) {
    FilterType.VIVID -> "Color mejorado"
    FilterType.ORIGINAL -> "Original"
    FilterType.NATURAL -> "Documento"
    FilterType.BLACK_WHITE -> "B/N"
    else -> f.label
}

/** Mejora visible de [side] con la común [common] (null = sin mejora = Original). */
internal fun shownFilter(side: IdCardSide, common: FilterType?): FilterType =
    side.effectiveFilter(common)?.takeIf { it in CardFilters } ?: FilterType.ORIGINAL

/** La cara con la mejora [f]; si coincide con la común, vuelve a «la común» (null: el hash no cambia). */
internal fun withFilter(side: IdCardSide, f: FilterType, common: FilterType?): IdCardSide {
    val commonShown = common?.takeIf { it != FilterType.ORIGINAL } ?: FilterType.ORIGINAL
    return side.copy(filter = if (f == commonShown) null else f)
}

/**
 * Cómo quedará la cara: rectificado de la vista [preview] con [quadPx] (píxeles de [preview]), giro, espejo y mejora.
 * Se recalcula (con una pequeña espera) cuando cambian.
 */
@Composable
internal fun CardSideResult(
    processor: CaptureProcessor,
    preview: Bitmap?,
    quadPx: Quad?,
    side: IdCardSide?,
    common: FilterType?,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf<Pair<Bitmap, ImageBitmap>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(preview, quadPx, side?.turn, side?.mirror, side?.effectiveFilter(common)) {
        if (preview == null || quadPx == null || side == null || preview.isRecycled) return@LaunchedEffect
        if (!isConvexQuad(quadPx.points().map { Offset(it.x, it.y) })) return@LaunchedEffect
        delay(120)
        busy = true
        try {
            val b = processor.previewCardSide(preview, quadPx, side, common)
            val old = shown
            shown = b to b.asImageBitmap()
            old?.first?.recycle()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        } finally {
            busy = false
        }
    }
    DisposableEffect(Unit) { onDispose { shown?.first?.recycle(); shown = null } }
    Box(modifier, contentAlignment = Alignment.Center) {
        val s = shown
        if (s != null) {
            Image(
                s.second,
                contentDescription = "Resultado de la cara",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
            )
        }
        if (busy || s == null) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
    }
}

/** Botón pequeño con icono y texto para las herramientas de una cara. */
@Composable
internal fun CardToolButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean = false, loading: Boolean = false) {
    val ui = MaterialTheme.ui
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) ui.accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f))
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, color = Color.White.copy(alpha = if (enabled) 0.95f else 0.4f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Herramientas de una cara: giros, espejo y mejora; [extra] añade botones al principio (Auto, Reemplazar…).
 * [onSide] recibe la cara cambiada.
 */
@Composable
internal fun CardSideTools(
    side: IdCardSide,
    common: FilterType?,
    onSide: (IdCardSide) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            extra()
            CardToolButton("↺ 90°", onClick = { onSide(side.rotated(270)) })
            CardToolButton("↻ 90°", onClick = { onSide(side.rotated(90)) })
            CardToolButton("180°", onClick = { onSide(side.rotated(180)) })
            CardToolButton("Espejo", onClick = { onSide(side.mirrored()) }, active = side.mirror == true)
        }
        Spacer(Modifier.height(8.dp))
        SegmentedControl(
            options = CardFilters.map { cardFilterLabel(it) },
            selectedIndex = CardFilters.indexOf(shownFilter(side, common)).coerceAtLeast(0),
            onSelect = { i -> onSide(withFilter(side, CardFilters[i], common)) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// ====================================================================================== editor del DNI

/** Una cara en edición: su foto ([file]; [temp] = nueva, aún no guardada en el documento), su vista y sus ediciones. */
private class SideSlot(val file: File, val temp: Boolean, val preview: Bitmap, val image: ImageBitmap, val side: IdCardSide) {
    fun with(s: IdCardSide) = SideSlot(file, temp, preview, image, s)
}

/**
 * «Editar DNI» (Revisión › Editar, o «Editar anverso/reverso»): cada cara por SEPARADO sobre su foto ORIGINAL —
 * recorte (4 esquinas), Auto, giros, espejo, mejora, reemplazar SOLO esa cara (cámara o galería) — y la hoja con las dos
 * caras (tocar una la abre), intercambiar anverso y reverso, quitar o añadir el reverso. «Listo» recompone la hoja: solo
 * se vuelven a procesar las caras que cambiaron (las demás salen de la caché, ver CaptureProcessor.recomposeIdCard).
 * [initialSide]: 0 anverso, 1 reverso, -1 la hoja.
 */
@Composable
internal fun IdCardCropScreen(container: AppContainer, docId: String, pageId: String, initialSide: Int = 0, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = remember { CaptureProcessor(container) }
    var original by remember { mutableStateOf<IdCardEdits?>(null) }
    var slots by remember { mutableStateOf<List<SideSlot?>>(listOf(null, null)) }
    /** Todas las fotos/vistas creadas en esta pantalla (se liberan/borran al salir si no se guardaron). */
    val created = remember { ArrayList<SideSlot>() }
    // pestaña: 0 = hoja, 1 = anverso, 2 = reverso
    var tab by remember { mutableIntStateOf(if (initialSide < 0) 0 else initialSide.coerceIn(0, 1) + 1) }
    var saving by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<Int?>(null) }
    var askReplace by remember { mutableStateOf<Int?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IdCardSize.DEFAULT) }
    var saved by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(docId, pageId) {
        size = runCatching { container.settings.settings.first().idCardSize }.getOrDefault(IdCardSize.DEFAULT)
        val page = container.documents.get(docId)?.pages?.firstOrNull { it.id == pageId }
        val c = page?.edits?.idCard
        if (c == null) { loadError = true; return@LaunchedEffect }
        val loaded = withContext(Dispatchers.IO) {
            listOf(false, true).map { back ->
                val f = container.documents.idSourceFile(docId, pageId, back)
                val s = if (back) c.back else c.front
                if (s == null || !f.exists()) null else runCatching {
                    val b = BitmapIO.decodeThumbnail(f.absolutePath, 1600)
                    SideSlot(f, false, b, b.asImageBitmap(), s)
                }.getOrNull()
            }
        }
        if (loaded[0] == null) { loaded.forEach { it?.preview?.recycle() }; loadError = true; return@LaunchedEffect }
        loaded.filterNotNull().forEach { created += it }
        slots = loaded
        original = c
        if (tab == 2 && loaded[1] == null) tab = 1
    }
    LaunchedEffect(loadError) {
        if (loadError) { toast("No se encontraron las fotos originales de la tarjeta"); onBack() }
    }
    DisposableEffect(Unit) {
        onDispose {
            val keep = if (saved) slots.filterNotNull().map { it.file.absolutePath }.toSet() else emptySet()
            created.forEach { s ->
                s.preview.recycle()
                if (s.temp && s.file.absolutePath !in keep) s.file.delete()
            }
        }
    }

    val common = original?.enhance
    val sideIndex = tab - 1
    val hasChanges = original != null && (
        slots[0]?.side != original?.front || slots[1]?.side != original?.back ||
            slots.any { it?.temp == true } || (slots[1] == null) != (original?.back == null) ||
            slots[0]?.file != container.documents.idSourceFile(docId, pageId, false)
        )

    fun setSide(i: Int, s: IdCardSide) { val l = slots.toMutableList(); l[i] = l[i]?.with(s); slots = l }

    fun save() {
        val c0 = original ?: return
        val f = slots[0] ?: return
        if (saving) return
        if (slots.filterNotNull().any { !isConvexQuad(CaptureProcessor.denormalize(it.side.quad, it.preview.width, it.preview.height).points().map { p -> Offset(p.x, p.y) }) }) {
            toast("Las esquinas se cruzan: ajústalas"); return
        }
        if (!hasChanges) { onBack(); return }
        saving = true
        scope.launch {
            try {
                val b = slots[1]
                val card = IdCardEdits(f.side, b?.side, c0.enhance)
                processor.recomposeIdCard(docId, pageId, card, listOf(f.file, b?.file))
                saved = true
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

    fun autoDetect(i: Int) {
        val s = slots[i] ?: return
        if (detecting) return
        detecting = true
        scope.launch {
            try {
                val d = processor.detectCardSide(s.file, null, withPreview = false)
                if (d.confidence <= 0f) toast("No se detectaron los bordes: ajusta las esquinas a mano")
                // Auto: recorte y orientación nuevos (el giro extra se reinicia); la mejora elegida se conserva
                else setSide(i, IdCardSide(d.quad, null, filter = s.side.filter))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast("No se pudo detectar la tarjeta")
            } finally {
                detecting = false
            }
        }
    }

    /** Nueva foto [file] (temporal) para la cara [i]: se detecta la tarjeta y sustituye SOLO esa cara. */
    fun useNewPhoto(i: Int, file: File, guide: android.graphics.RectF?, deviceUp: Int) {
        detecting = true
        scope.launch {
            try {
                val d = processor.detectCardSide(file, guide, withPreview = true, deviceUp = deviceUp)
                val p = d.preview ?: withContext(Dispatchers.IO) { BitmapIO.decodeThumbnail(file.absolutePath, 1600) }
                val old = slots.getOrNull(i)?.side
                val slot = SideSlot(file, true, p, p.asImageBitmap(), IdCardSide(d.quad, null, filter = old?.filter))
                created += slot
                val l = slots.toMutableList(); l[i] = slot; slots = l
                tab = i + 1
                if (d.confidence <= 0f) toast("No se detectaron los bordes: ajusta las esquinas a mano")
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                throw e
            } catch (t: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                toast("No se pudo abrir la foto")
            } finally {
                detecting = false
            }
        }
    }

    var galleryTarget by remember { mutableStateOf<Int?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val i = galleryTarget
        galleryTarget = null
        if (uri != null && i != null) scope.launch {
            val f = runCatching { processor.copyToCaptureFile(uri) }.getOrNull()
            if (f == null) toast("No se pudo abrir la imagen") else useNewPhoto(i, f, null, -1)
        }
    }

    fun requestExit() {
        when {
            saving -> Unit
            hasChanges -> confirmExit = true
            else -> onBack()
        }
    }

    BackHandler(enabled = !saving && replacing == null) { requestExit() }
    ForcedDarkTheme {
        Box(Modifier.fillMaxSize().background(EditorBackground)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Text(
                    "Editar DNI",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                SegmentedControl(
                    options = if (slots[1] != null) listOf("Hoja", "Anverso", "Reverso") else listOf("Hoja", "Anverso"),
                    selectedIndex = tab.coerceAtMost(if (slots[1] != null) 2 else 1),
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                val slot = if (sideIndex >= 0) slots.getOrNull(sideIndex) else null
                if (tab == 0) {
                    // ---------------------------------------------------------------- la hoja: tocar una cara la abre
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        SheetPreview(processor, slots, common, size, onTap = { i -> tab = i + 1 })
                    }
                    Text(
                        "Toca una cara para editarla por separado",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CardToolButton("Editar anverso", onClick = { tab = 1 })
                        if (slots[1] != null) {
                            CardToolButton("Editar reverso", onClick = { tab = 2 })
                            CardToolButton("⇅ Intercambiar", onClick = { slots = listOf(slots[1], slots[0]) })
                            CardToolButton("Quitar reverso", onClick = { slots = listOf(slots[0], null) })
                        } else {
                            CardToolButton("Añadir reverso", onClick = { askReplace = 1 }, loading = detecting)
                        }
                    }
                } else if (slot != null) {
                    // ---------------------------------------------------------------- una cara sola
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        CropTool(
                            image = slot.image,
                            quad = CaptureProcessor.denormalize(slot.side.quad, slot.image.width, slot.image.height),
                            onQuadChange = { q ->
                                setSide(sideIndex, slot.side.copy(quad = CaptureProcessor.normalize(q, slot.image.width, slot.image.height), manual = true))
                            },
                            primary = MaterialTheme.ui.accent,
                            secondary = MaterialTheme.ui.accent,
                            error = MaterialTheme.ui.red,
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardSideResult(
                            processor, slot.preview,
                            CaptureProcessor.denormalize(slot.side.quad, slot.preview.width, slot.preview.height),
                            slot.side, common,
                            Modifier.height(84.dp).width(134.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            (if (sideIndex == 0) "Anverso" else "Reverso") + " · así quedará en la hoja",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                        )
                    }
                    CardSideTools(
                        slot.side, common, onSide = { setSide(sideIndex, it) },
                        extra = {
                            CardToolButton("Auto", onClick = { autoDetect(sideIndex) }, loading = detecting, enabled = !saving)
                            CardToolButton("Reemplazar", onClick = { askReplace = sideIndex }, enabled = !saving && !detecting)
                        },
                    )
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 3.dp)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryCapsuleButton("Cancelar", onClick = { requestExit() }, tonal = true, modifier = Modifier.weight(1f))
                    PrimaryCapsuleButton("Listo", onClick = { save() }, loading = saving, enabled = original != null && !detecting, modifier = Modifier.weight(1f))
                }
            }
            replacing?.let { i ->
                IdSideCamera(
                    container = container,
                    back = i == 1,
                    onShot = { f, guide, up -> replacing = null; useNewPhoto(i, f, guide, up) },
                    onCancel = { replacing = null },
                )
            }
        }
        askReplace?.let { i ->
            IosAlert(
                title = if (i == 0) "Reemplazar el anverso" else if (slots[1] == null) "Añadir el reverso" else "Reemplazar el reverso",
                message = "Solo cambia esta cara; la otra se queda como está.",
                onDismiss = { askReplace = null },
                actions = listOf(
                    AlertAction("Hacer una foto", bold = true) { replacing = i },
                    AlertAction("Elegir de la galería") {
                        galleryTarget = i
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    AlertAction("Cancelar") { },
                ),
            )
        }
        if (confirmExit) {
            IosAlert(
                title = "¿Guardar los cambios?",
                message = "Has editado la tarjeta. ¿Quieres recomponer la hoja antes de salir?",
                onDismiss = { confirmExit = false },
                actions = listOf(
                    AlertAction("Guardar", bold = true) { save() },
                    AlertAction("Descartar", destructive = true) { onBack() },
                    AlertAction("Seguir editando") { },
                ),
            )
        }
    }
}

/** Vista de la hoja A4 con las caras (como IdCardComposer.layout); tocar una llama a [onTap] con su índice. */
@Composable
private fun SheetPreview(processor: CaptureProcessor, slots: List<SideSlot?>, common: FilterType?, size: IdCardSize, onTap: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(IdCardComposer.PAGE_W.toFloat() / IdCardComposer.PAGE_H)) {
        val k = maxWidth / IdCardComposer.PAGE_W.toFloat()
        val present = slots.withIndex().filter { it.value != null }
        val dims = present.map { (_, s) ->
            val portrait = s!!.side.turn % 180 != 0
            if (portrait) 54 to 86 else 86 to 54
        }
        val rects = IdCardComposer.layout(dims, size.scale)
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color.White))
        present.forEachIndexed { j, (i, s) ->
            val r = rects[j]
            Box(
                Modifier
                    .offset(x = k * r[0].toFloat(), y = k * r[1].toFloat())
                    .size(width = k * (r[2] - r[0]).toFloat(), height = k * (r[3] - r[1]).toFloat())
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color.Black.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) { onTap(i) },
            ) {
                CardSideResult(
                    processor, s!!.preview,
                    CaptureProcessor.denormalize(s.side.quad, s.preview.width, s.preview.height),
                    s.side, common, Modifier.fillMaxSize(),
                )
                Text(
                    if (i == 0) "Anverso" else "Reverso",
                    color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ====================================================================================== cámara de UNA cara

/**
 * Cámara para volver a fotografiar SOLO una cara: marco ID-1, linterna y disparo. [onShot] recibe la foto (temporal),
 * el marco normalizado y el giro que pedía la posición del móvil (-1 = desconocido).
 */
@Composable
internal fun IdSideCamera(container: AppContainer, back: Boolean, onShot: (File, android.graphics.RectF?, Int) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) { Toast.makeText(context, "Sin permiso de cámara: elige la foto de la galería", Toast.LENGTH_LONG).show(); onCancel() }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (activity != null && previous != null) activity.requestedOrientation = previous }
    }
    BackHandler { onCancel() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) IdSideCameraContent(container, back, onShot, onCancel)
    }
}

@Composable
private fun IdSideCameraContent(container: AppContainer, back: Boolean, onShot: (File, android.graphics.RectF?, Int) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember { CameraEngine(context.applicationContext, container.pageProcessor.detector, container.deviceTier) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val deviceUp = rememberDeviceUp()
    DisposableEffect(engine) {
        engine.bind(lifecycleOwner, previewView, FlashSetting.OFF)
        container.documents.pauseBackgroundOcr()
        onDispose {
            container.documents.resumeBackgroundOcr()
            engine.release()
        }
    }
    LaunchedEffect(torch) { engine.setFlash(if (torch) FlashSetting.TORCH else FlashSetting.OFF) }

    fun shoot() {
        if (capturing || viewSize.width == 0) return
        capturing = true
        scope.launch {
            val tmp = container.documents.newCaptureFile()
            try {
                engine.capture(tmp, previewView)
                val (pw, ph) = withContext(Dispatchers.IO) { BitmapIO.decodeBounds(tmp.absolutePath) }
                val guide = if (pw > 0 && ph > 0) {
                    FrameMapping.fillCenter(viewSize.width.toFloat(), viewSize.height.toFloat(), pw.toFloat(), ph.toFloat())
                        .viewRectToNormalized(idCardGuideRect(viewSize.width.toFloat(), viewSize.height.toFloat()))
                } else null
                onShot(tmp, guide, deviceUp())
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { tmp.delete() }
                throw e
            } catch (t: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { tmp.delete() }
                Toast.makeText(context, "No se pudo hacer la foto", Toast.LENGTH_SHORT).show()
            } finally {
                capturing = false
            }
        }
    }

    ForcedDarkTheme {
        Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val density = LocalDensity.current
            Canvas(Modifier.fillMaxSize()) {
                if (viewSize.width == 0) return@Canvas
                val g = idCardGuideRect(size.width, size.height)
                val r = with(density) { 14.dp.toPx() }
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(g, CornerRadius(r)))
                }
                drawPath(path, Color.Black.copy(alpha = 0.45f))
                drawRoundRect(Color.White, g.topLeft, g.size, CornerRadius(r), style = Stroke(with(density) { 2.dp.toPx() }))
            }
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CameraCircle(AppIcons.Close, "Cerrar", onCancel)
                    Spacer(Modifier.weight(1f))
                    CameraCircle(AppIcons.Bolt, if (torch) "Apagar linterna" else "Linterna", { torch = !torch }, active = torch)
                }
                Text(
                    if (back) "Encuadra el REVERSO dentro del marco" else "Encuadra el ANVERSO dentro del marco",
                    color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)) {
                Box(
                    Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                        .background(if (capturing) Color.White.copy(alpha = 0.5f) else Color.White)
                        .clickable(enabled = !capturing, role = Role.Button) { shoot() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
                }
            }
        }
    }
}

@Composable
private fun CameraCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, active: Boolean = false) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) Color.White else Color.Black.copy(alpha = 0.45f)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * Giro horario (0/90/180/270) que endereza una foto hecha ahora con la cámara bloqueada en vertical, según la posición
 * del móvil (acelerómetro); -1 si el móvil está casi plano (desconocido). Solo desempata la orientación de la cara.
 */
@Composable
internal fun rememberDeviceUp(): () -> Int {
    val context = LocalContext.current
    val state = remember { intArrayOf(-1) }
    DisposableEffect(context) {
        val l = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                state[0] = if (orientation == ORIENTATION_UNKNOWN) -1 else ((orientation + 45) / 90 % 4) * 90
            }
        }
        if (l.canDetectOrientation()) l.enable()
        onDispose { l.disable() }
    }
    return { state[0] }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
