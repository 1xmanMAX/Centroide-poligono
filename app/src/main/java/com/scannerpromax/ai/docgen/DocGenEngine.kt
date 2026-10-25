package com.scannerpromax.ai.docgen

import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.CloudUsageTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

data class DocGenRequest(
    val mode: DocGenMode,
    val formats: Set<OutputFormat>,
    /** Nombre de archivo sin extensión. */
    val filename: String,
    val title: String,
)

/** Límites del bucle (iteraciones por sesión, tokens totales) para que una respuesta rara no salga cara. */
data class DocGenLimits(
    val chunkPages: Int = 4,
    val baseIterations: Int = 6,
    val iterationsPerPage: Int = 8,
    val maxNudges: Int = 2,
    /** Tope de tokens (entrada + caché + salida) por página del trabajo. */
    val tokensPerPage: Long = 250_000,
    val minTotalTokens: Long = 400_000,
    val retryBackoffMs: LongArray = longArrayOf(2_000, 6_000, 20_000),
)

data class DocGenProgress(
    val fraction: Float,
    /** Página escaneada en curso (1..total). */
    val page: Int,
    val totalPages: Int,
    val message: String,
    val costUsd: Double,
)

data class OutputFile(val format: OutputFormat, val path: String, val sizeBytes: Long, val savedUri: String? = null)

class DocGenOutcome(
    val document: GenDocument,
    val files: List<OutputFile>,
    val usage: CloudUsageTokens,
    val costUsd: Double,
    val warnings: List<String>,
    /** Se cortó por un límite (tokens/iteraciones) y el documento puede estar incompleto. */
    val partial: Boolean,
    val servedModels: Set<String>,
)

class DocGenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * MOTOR de reconstrucción con IA (lógica pura, probada en JVM con proveedores falsos):
 *  - RÁPIDA: una llamada por página (structured outputs en Claude / json_object en DeepSeek) -> bloques -> documento.
 *  - CALIDAD: bucle agéntico PROPIO con herramientas ([DocTools]) por tandas de [DocGenLimits.chunkPages] páginas
 *    (el historial con las imágenes no crece sin límite); la IA puede leer el OCR detallado, corregir lo que la app
 *    le rechaza y termina con finish_document. Si no lo llama, se le recuerda; si se agota el presupuesto, se genera
 *    lo que haya (avisando).
 * Cancelable (corrutina). Los archivos los genera [finisher] UNA vez al final con los formatos que eligió el usuario.
 */
class DocGenEngine(
    private val client: DocGenClient,
    private val pages: List<PageInput>,
    private val request: DocGenRequest,
    private val finisher: suspend (GenDocument, FinishRequest) -> List<OutputFile>,
    private val onProgress: (DocGenProgress) -> Unit = {},
    /** Cada respuesta facturada: modelo que respondió, tokens y su coste en USD. */
    private val onUsage: (String, CloudUsageTokens) -> Double = { _, _ -> 0.0 },
    private val limits: DocGenLimits = DocGenLimits(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val total = pages.size
    private var usage = CloudUsageTokens(0, 0, 0)
    private var cost = 0.0
    private val served = LinkedHashSet<String>()
    private val tokenBudget = max(limits.minTotalTokens, limits.tokensPerPage * total)
    private var partial = false

    val executor = DocToolExecutor(total, { p -> PageText.json(pages[p - 1]) }, strict = request.mode == DocGenMode.QUALITY)

    private val name = client.model.short

    suspend fun run(): DocGenOutcome {
        require(pages.isNotEmpty()) { "sin páginas" }
        executor.setTitleIfMissing(request.title)
        when (request.mode) {
            DocGenMode.FAST -> runFast()
            DocGenMode.QUALITY -> runQuality()
        }
        if (executor.blockCount == 0) throw DocGenException("La IA no devolvió contenido para el documento")
        progress(0.96f, total, "Generando ${request.formats.sortedBy { it.ordinal }.joinToString(" y ") { it.label }}…")
        val doc = executor.document()
        val files = finisher(doc, FinishRequest(request.formats, DocToolExecutor.sanitizeFileName(request.filename), doc.title ?: request.title))
        progress(1f, total, "Listo")
        return DocGenOutcome(doc, files, usage, cost, executor.warnings.toList(), partial, served)
    }

    // ------------------------------------------------------------------ RÁPIDA

    private suspend fun runFast() {
        for ((i, p) in pages.withIndex()) {
            progress(i.toFloat() / total * 0.92f, p.number, "Página ${p.number} de $total · $name leyendo…")
            val reply = withRetries { client.structured(DocPrompts.FAST_SYSTEM, DocPrompts.fastUser(p, total, request.title), DocPrompts.FAST_SCHEMA) }
            account(reply.servedModel, reply.usage)
            val parsed = FastPage.parse(reply.text)
            if (parsed == null) {
                executor.warnings += "página ${p.number}: respuesta ilegible de la IA; se usa el texto del OCR"
                fallbackText(p)
            } else {
                if (parsed.second || reply.truncated) executor.warnings += "página ${p.number}: la respuesta se cortó; puede faltar el final"
                val before = executor.blockCount
                FastPage.apply(parsed.first, executor, p.number, first = i == 0)
                if (executor.blockCount == before) fallbackText(p)
            }
            if (i < pages.lastIndex && executor.document().blocks.lastOrNull() !is DocBlock.PageBreak) {
                executor.execute(DocTools.PAGE_BREAK, "{}")
            }
        }
    }

    /** Página sin respuesta útil: se conserva el texto del OCR como párrafos (mejor que una página vacía). */
    private fun fallbackText(p: PageInput) {
        if (p.lines.isEmpty()) return
        executor.currentPage = p.number
        executor.execute(
            DocTools.ADD_PARAGRAPH,
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "text" to kotlinx.serialization.json.JsonPrimitive(p.plainText.take(20_000)),
                    "source_page" to kotlinx.serialization.json.JsonPrimitive(p.number),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ CALIDAD

    private suspend fun runQuality() {
        val chunks = pages.chunked(limits.chunkPages.coerceAtLeast(1))
        for ((ci, chunk) in chunks.withIndex()) {
            val previous = if (ci == 0) null else summaryOfEnd()
            val session = client.startSession(
                DocPrompts.AGENT_SYSTEM,
                DocTools.ALL,
                DocPrompts.agentUser(chunk, total, request.title, request.formats, request.filename, previous),
            )
            val first = chunk.first().number; val last = chunk.last().number
            val maxIter = limits.baseIterations + limits.iterationsPerPage * chunk.size
            var nudges = 0
            var finished = false
            var iter = 0
            while (!finished) {
                if (iter >= maxIter) {
                    executor.warnings += "páginas $first–$last: se alcanzó el máximo de pasos de la IA"
                    partial = true
                    break
                }
                if (usage.inputTokens + usage.cachedInputTokens + usage.outputTokens > tokenBudget) {
                    executor.warnings += "se alcanzó el límite de tokens del trabajo; el documento puede estar incompleto"
                    partial = true
                    return
                }
                iter++
                val cur = executor.currentPage.coerceIn(first, last)
                progress(((cur - 1) + 0.5f * (cur - first + 1) / chunk.size) / total * 0.92f, cur, "Página $cur de $total · $name escribiendo…")
                val turn = withRetries { session.next() }
                account(turn.servedModel, turn.usage)
                when {
                    turn.stop == StopKind.REFUSAL -> throw CloudException.Refused(turn.refusalCategory)
                    turn.calls.isNotEmpty() -> {
                        val results = ArrayList<ToolOutcome>(turn.calls.size)
                        for (c in turn.calls) {
                            val r = if (finished) ToolExec("""{"ok":true,"note":"ignored after finish_document"}""", false) else executor.execute(c.name, c.argsJson)
                            if (r.finish != null) finished = true
                            results += ToolOutcome(c.id, r.content, r.isError)
                        }
                        if (!finished) {
                            val follow = if (turn.stop == StopKind.MAX_TOKENS) {
                                "Your previous answer hit the output token limit. Resend any call that failed, and keep each call smaller (split long tables/text into several calls)."
                            } else null
                            session.addToolResults(results, follow)
                        }
                    }
                    else -> {
                        if (nudges >= limits.maxNudges) {
                            executor.warnings += "páginas $first–$last: la IA terminó sin finish_document"
                            break
                        }
                        nudges++
                        session.addUserText(DocPrompts.nudge(request.formats, request.filename))
                    }
                }
            }
            progress(last.toFloat() / total * 0.92f, last, "Página $last de $total lista")
        }
    }

    /** Final del documento hasta ahora (para que la siguiente tanda continúe sin repetir). */
    private fun summaryOfEnd(): String {
        val blocks = executor.document().blocks.filter { it !is DocBlock.PageBreak }.takeLast(2)
        if (blocks.isEmpty()) return "(nothing yet)"
        return blocks.joinToString(" / ") { b ->
            val kind = when (b) {
                is DocBlock.Heading -> "heading"; is DocBlock.Paragraph -> "paragraph"; is DocBlock.ListBlock -> "list"
                is DocBlock.Table -> "table"; is DocBlock.Image -> "image"; is DocBlock.Formula -> "formula"; is DocBlock.PageBreak -> "page break"
            }
            val t = b.plainText().replace('\n', ' ')
            "$kind \"" + (if (t.length > 160) "…" + t.takeLast(160) else t) + "\""
        }
    }

    // ------------------------------------------------------------------ comunes

    private fun account(model: String, u: CloudUsageTokens) {
        usage = CloudUsageTokens(usage.inputTokens + u.inputTokens, usage.outputTokens + u.outputTokens, usage.cachedInputTokens + u.cachedInputTokens)
        cost += onUsage(model.ifBlank { client.model.id }, u)
        if (model.isNotBlank()) served += model
    }

    private var lastFraction = 0f

    private fun progress(f: Float, page: Int, msg: String) {
        lastFraction = min(1f, max(lastFraction, f))
        onProgress(DocGenProgress(lastFraction, page.coerceIn(1, total), total, msg, cost))
    }

    /** Reintentos con espera para 429/5xx/red (respeta Retry-After). Lo demás sale al momento. */
    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudException) {
                if (!e.retryable || attempt >= limits.retryBackoffMs.size) throw e
                val wait = max(limits.retryBackoffMs[attempt], (e as? CloudException.RateLimited)?.retryAfterMs ?: 0L).coerceAtMost(60_000)
                onProgress(DocGenProgress(lastFraction, executor.currentPage.coerceIn(1, total), total, "Reintentando (${attempt + 1}) · ${e.message}", cost))
                sleep(wait)
                attempt++
            }
        }
    }
}
