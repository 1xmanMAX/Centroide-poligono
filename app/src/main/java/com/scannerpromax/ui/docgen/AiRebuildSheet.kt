package com.scannerpromax.ui.docgen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.scannerpromax.ai.cloud.CloudPricing
import com.scannerpromax.ai.cloud.CloudProviderId
import com.scannerpromax.ai.docgen.DocGenCost
import com.scannerpromax.ai.docgen.DocGenJobState
import com.scannerpromax.ai.docgen.DocGenMode
import com.scannerpromax.ai.docgen.DocGenModel
import com.scannerpromax.ai.docgen.DocGenSpec
import com.scannerpromax.ai.docgen.OutputFile
import com.scannerpromax.ai.docgen.OutputFormat
import com.scannerpromax.di.AppContainer
import com.scannerpromax.export.ExportSelection
import com.scannerpromax.ui.components.AlertAction
import com.scannerpromax.ui.components.AppIcons
import com.scannerpromax.ui.components.CheckRow
import com.scannerpromax.ui.components.InfoRow
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.IosAlert
import com.scannerpromax.ui.components.IosSheet
import com.scannerpromax.ui.components.NavigationRow
import com.scannerpromax.ui.components.PrimaryCapsuleButton
import com.scannerpromax.ui.components.SegmentedControl
import com.scannerpromax.ui.components.SeparatorInsetPlain
import com.scannerpromax.ui.components.SheetHeader
import com.scannerpromax.ui.components.safeFileName
import com.scannerpromax.ui.theme.ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Modelos que se ofrecen para un modo, según las claves guardadas (la de Claude sirve para Haiku y Sonnet). */
internal fun modelsFor(mode: DocGenMode, hasClaude: Boolean, hasDeepSeek: Boolean, qualityModel: DocGenModel): List<DocGenModel> = buildList {
    if (hasClaude) add(if (mode == DocGenMode.QUALITY) qualityModel else DocGenModel.CLAUDE_HAIKU)
    if (hasDeepSeek) add(DocGenModel.DEEPSEEK_FLASH)
}

internal fun priceLabel(m: DocGenModel): String {
    val r = CloudPricing.ratesForModel(m.id, 0, java.time.Instant.now())
    fun f(v: Double) = String.format(java.util.Locale("es", "ES"), "%.2f", v).replace(",00", "")
    return "$${f(r.inputPerM)} / $${f(r.outputPerM)} por millón de tokens" + if (m == DocGenModel.DEEPSEEK_FLASH) " (×2 en hora punta)" else ""
}

/**
 * Hoja "Reconstruir con IA" (estilo iOS): formato (Word / PDF / ambos), calidad (Rápida: Haiku o DeepSeek; Alta
 * calidad: Sonnet), páginas (todas o las seleccionadas), proveedor según las claves guardadas (sin clave -> enlace a
 * Ajustes › IA en la nube), coste estimado y aviso de privacidad. Al empezar muestra el progreso del trabajo (que
 * sigue en segundo plano aunque se cierre la hoja) y al terminar Abrir / Compartir y "Guardado en Descargas".
 */
@Composable
fun AiRebuildSheet(
    container: AppContainer,
    docId: String,
    pageIds: List<String>?,
    onDismiss: () -> Unit,
    onOpenCloudSettings: () -> Unit,
) {
    val context = LocalContext.current
    val ui = MaterialTheme.ui
    val prefs = container.docGenPrefs
    val jobs by container.docGenJobs.states.collectAsState()
    val appSettings by container.settings.settings.collectAsState(initial = null)
    val fullDoc by remember(docId) { container.documents.observe(docId) }.collectAsState(initial = null)
    val hasClaude = remember { container.cloudKeys.has(CloudProviderId.CLAUDE_HAIKU) }
    val hasDeepSeek = remember { container.cloudKeys.has(CloudProviderId.DEEPSEEK) }

    var formats by remember { mutableStateOf(prefs.formats) }
    var mode by remember { mutableStateOf(prefs.mode) }
    var onlySelected by remember { mutableStateOf(pageIds != null) }
    val available = modelsFor(mode, hasClaude, hasDeepSeek, prefs.qualityModel)
    var model by remember(mode) {
        mutableStateOf((if (mode == DocGenMode.FAST) prefs.fastModel else prefs.qualityModel).takeIf { it in available } ?: available.firstOrNull())
    }
    var consentFor by remember { mutableStateOf<DocGenModel?>(null) }
    var watchJob by remember { mutableStateOf(container.docGenJobs.latestFor(docId)?.takeIf { it.active }?.jobId) }
    val job = watchJob?.let { jobs[it] }

    val selection = if (onlySelected) pageIds else null
    val doc = fullDoc?.let { ExportSelection.apply(it, selection) }
    val totalPages = fullDoc?.pages?.size ?: 0
    val count = doc?.pages?.size ?: 0

    var pageInfo by remember { mutableStateOf<List<DocGenCost.PageInfo>?>(null) }
    LaunchedEffect(doc?.pages?.map { it.id }) {
        val d = doc ?: return@LaunchedEffect
        pageInfo = withContext(Dispatchers.IO) {
            d.pages.map { p ->
                val f = container.documents.processedFileIfReady(docId, p)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (f != null) BitmapFactory.decodeFile(f.absolutePath, o)
                val ocr = runCatching { container.documents.loadOcr(docId, p.id) }.getOrNull()
                DocGenCost.PageInfo(
                    if (o.outWidth > 0) o.outWidth else p.width, if (o.outHeight > 0) o.outHeight else p.height,
                    ocr?.text?.length ?: 1_800, ocr?.blocks?.sumOf { it.lines.size } ?: 40,
                )
            }
        }
    }
    val estimate = remember(pageInfo, model, mode) { model?.let { m -> pageInfo?.let { DocGenCost.estimate(m, mode, it) } } }

    fun start(m: DocGenModel) {
        val d = doc ?: return
        prefs.formats = formats; prefs.mode = mode
        if (mode == DocGenMode.FAST) prefs.fastModel = m
        val title = d.title.ifBlank { "Documento" }
        val spec = DocGenSpec(docId, selection, formats, mode, m, safeFileName(title, "Documento"), title)
        watchJob = container.docGenJobs.start(spec)
    }

    IosSheet(onDismissRequest = onDismiss) { dismiss ->
        SheetHeader(title = "Reconstruir con IA", onCancel = if (job?.active == true) null else dismiss, onDone = if (job?.active == true) dismiss else null, doneLabel = "Ocultar")
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when {
                job != null && job.status != DocGenJobState.Status.CANCELLED -> JobView(
                    container, job,
                    onCancel = { container.docGenJobs.cancel(job.jobId) },
                    onAgain = { container.docGenJobs.dismiss(job.jobId); watchJob = null },
                    onOpenCloudSettings = { dismiss(); onOpenCloudSettings() },
                )
                !hasClaude && !hasDeepSeek -> {
                    Text(
                        "Para reconstruir el documento como Word o PDF editable con IA necesitas una clave de API de " +
                            "Anthropic (Claude) o de DeepSeek. Se guarda cifrada en el teléfono y pagas directamente al proveedor.",
                        style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    InsetGroup(separatorInset = SeparatorInsetPlain) {
                        item { NavigationRow("Configurar en Ajustes › IA en la nube", { dismiss(); onOpenCloudSettings() }, icon = AppIcons.Cloud) }
                    }
                }
                else -> {
                    Text(
                        "La IA lee cada página (imagen + texto reconocido) y la vuelve a escribir como documento editable: " +
                            "mismo texto, títulos, listas, tablas reales, imágenes recortadas y fórmulas.",
                        style = MaterialTheme.typography.bodyMedium, color = ui.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("Formato")
                        val fmtIdx = when (formats) { setOf(OutputFormat.DOCX) -> 0; setOf(OutputFormat.PDF) -> 1; else -> 2 }
                        SegmentedControl(listOf("Word", "PDF", "Ambos"), fmtIdx, { i ->
                            formats = when (i) { 0 -> setOf(OutputFormat.DOCX); 1 -> setOf(OutputFormat.PDF); else -> setOf(OutputFormat.DOCX, OutputFormat.PDF) }
                        })
                        Label("Calidad")
                        SegmentedControl(DocGenMode.entries.map { it.label }, mode.ordinal, { mode = DocGenMode.entries[it] })
                    }
                    InsetGroup(
                        header = "Modelo",
                        separatorInset = SeparatorInsetPlain,
                        footer = if (mode == DocGenMode.FAST) "Rápida: una lectura por página con la respuesta ya estructurada."
                        else "Alta calidad: la IA trabaja con herramientas, revisa lo que añade y completa el documento (más lento y caro).",
                    ) {
                        available.forEach { m ->
                            item { CheckRow(m.label, selected = m == model, onClick = { model = m }, subtitle = priceLabel(m)) }
                        }
                        if (mode == DocGenMode.QUALITY && !hasClaude) {
                            item { NavigationRow("Alta calidad con Claude Sonnet: añade una clave de Anthropic", { dismiss(); onOpenCloudSettings() }) }
                        }
                    }
                    InsetGroup(separatorInset = SeparatorInsetPlain) {
                        if (pageIds != null) {
                            item { CheckRow("Páginas seleccionadas (${pageIds.size})", selected = onlySelected, onClick = { onlySelected = true }) }
                            item { CheckRow("Todas las páginas ($totalPages)", selected = !onlySelected, onClick = { onlySelected = false }) }
                        } else {
                            item { InfoRow("Páginas", if (count == 1) "1 página" else "$count páginas") }
                        }
                        item {
                            InfoRow(
                                "Coste estimado",
                                estimate?.let { DocGenCost.formatRange(it) } ?: "Calculando…",
                            )
                        }
                    }
                    val m = model
                    Text(
                        (m?.let { "Las páginas (imagen y texto) se envían a ${it.keyId.company} (servidores en ${it.keyId.serverLocation}). " } ?: "") +
                            "El archivo se guarda en Descargas/EscanerProMax. Puedes salir de la pantalla: seguirá en segundo plano.",
                        style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 36.dp),
                    )
                    PrimaryCapsuleButton(
                        "Reconstruir",
                        onClick = {
                            if (m == null) return@PrimaryCapsuleButton
                            // Consentimiento: el de esta hoja o el "Activar" del proveedor en Ajustes › IA en la nube.
                            if (!prefs.consented(m) && appSettings?.cloudConsent(m.keyId) != true) consentFor = m else start(m)
                        },
                        icon = AppIcons.Sparkles,
                        enabled = m != null && count > 0,
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }

    consentFor?.let { m ->
        IosAlert(
            title = "Enviar páginas a ${m.keyId.company}",
            icon = AppIcons.Cloud,
            message = "Para reconstruir el documento se enviarán las páginas elegidas (imagen y texto reconocido) a " +
                "${m.keyId.company}, con servidores en ${m.keyId.serverLocation}, según su política de privacidad. " +
                "El coste lo cobra ${m.keyId.company} a tu cuenta. ¿Aceptas?",
            onDismiss = { consentFor = null },
            actions = listOf(
                AlertAction("Cancelar") { },
                AlertAction("Acepto", bold = true) { prefs.setConsent(m); start(m) },
            ),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ui.secondaryLabel, modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun JobView(
    container: AppContainer,
    job: DocGenJobState,
    onCancel: () -> Unit,
    onAgain: () -> Unit,
    onOpenCloudSettings: () -> Unit,
) {
    val ui = MaterialTheme.ui
    val context = LocalContext.current
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ui.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (job.status) {
            DocGenJobState.Status.QUEUED, DocGenJobState.Status.RUNNING -> {
                val p = job.progress
                Text(p?.message ?: "Preparando…", style = MaterialTheme.typography.bodyLarge, color = ui.label)
                LinearProgressIndicator(progress = { p?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${job.spec.model.label} · ${job.spec.mode.label}" + if (job.costUsd > 0) " · gastado ${DocGenCost.format(job.costUsd)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel,
                )
                Text("Puedes cerrar esta hoja: verás el progreso en la notificación.", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                PrimaryCapsuleButton("Cancelar", onClick = onCancel, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
            }
            DocGenJobState.Status.DONE -> {
                Text("Documento listo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ui.label)
                val saved = job.files.any { it.savedUri != null }
                Text(
                    (if (saved) "Guardado en Descargas/EscanerProMax. " else "") + "Coste real: ${DocGenCost.format(job.costUsd)}.",
                    style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel,
                )
                if (job.partial || job.warnings.isNotEmpty()) {
                    Text(
                        (if (job.partial) "Puede estar incompleto. " else "") + job.warnings.take(3).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = ui.orange,
                    )
                }
                job.files.forEach { f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryCapsuleButton("Abrir ${f.format.label}", onClick = { open(context, f) }, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f))
                        PrimaryCapsuleButton(
                            "Compartir", icon = AppIcons.Share, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f),
                            onClick = {
                                runCatching { context.startActivity(container.imageExporter.shareChooser(listOf(File(f.path)), f.format.mime, "Compartir ${f.format.label}")) }
                            },
                        )
                    }
                }
                PrimaryCapsuleButton("Reconstruir otra vez", onClick = onAgain, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
            }
            DocGenJobState.Status.FAILED -> {
                Text("No se pudo reconstruir", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ui.label)
                Text(job.error ?: "Error desconocido", style = MaterialTheme.typography.bodyMedium, color = ui.red)
                if (job.costUsd > 0) Text("Gastado: ${DocGenCost.format(job.costUsd)}", style = MaterialTheme.typography.bodySmall, color = ui.secondaryLabel)
                if (job.accountError) {
                    PrimaryCapsuleButton("Abrir Ajustes › IA en la nube", onClick = onOpenCloudSettings, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                }
                PrimaryCapsuleButton("Volver a intentarlo", onClick = onAgain, tonal = true, height = 40.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
            }
            DocGenJobState.Status.CANCELLED -> Unit
        }
    }
    Spacer(Modifier.height(4.dp))
}

private fun open(context: Context, f: OutputFile) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(f.path))
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.format.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, "Abrir con").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No hay ninguna app para abrir ${f.format.label}", Toast.LENGTH_SHORT).show()
    } catch (_: IllegalArgumentException) {
        Toast.makeText(context, "El archivo ya no está disponible; búscalo en Descargas", Toast.LENGTH_SHORT).show()
    }
}
