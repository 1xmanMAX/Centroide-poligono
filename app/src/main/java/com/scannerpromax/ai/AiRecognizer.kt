package com.scannerpromax.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import com.scannerpromax.ai.AiLayout.IRect
import com.scannerpromax.ai.parse.IaParse
import com.scannerpromax.ai.parse.IaText
import com.scannerpromax.ai.parse.LoopGuard
import com.scannerpromax.ai.parts.PartsPipeline
import com.scannerpromax.ai.parts.PartsReader
import com.scannerpromax.ai.parts.PpLayout
import com.scannerpromax.ai.parts.Rgb
import com.scannerpromax.domain.BlockType
import com.scannerpromax.domain.DocLayout
import com.scannerpromax.domain.LayoutBlock
import com.scannerpromax.domain.LayoutBox
import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Motor PRINCIPAL de reconocimiento con IA en el dispositivo (especificación del banco IA, fase 3):
 *
 *  - Lector de documentos (PaddleOCR-VL-1.5): "Spotting:" sobre la página procesada -> texto con su cuadrilátero
 *    ([OcrResult] con bloques/líneas/palabras en píxeles para la capa invisible del PDF y el resaltado) + bloques de
 *    párrafo ([DocLayout]). Regiones no textuales con el detector clásico ([PageRegions]): tablas con reglado ->
 *    recorte (orientación corregida, tablas anchas por bandas de columnas) -> "Table Recognition:" (OTSL); ejes de
 *    gráfico -> "Chart Recognition:"; zonas grandes sin texto con contenido -> FIGURE; líneas que parecen fórmulas ->
 *    "Formula Recognition:".
 *  - Asistente visual (Qwen3.5-0.8B): transcripción alineada con las líneas de ML Kit (Qwen no da posiciones
 *    fiables); además clasificación del tipo de documento ([classify]) y rescate de encuadre ([locateDocument]).
 *
 * No gestiona la carga del modelo (lo hace [AiRecognitionQueue], una vez para toda la cola). Nunca recicla los
 * bitmaps recibidos. Todas las coordenadas de salida están en píxeles de la imagen procesada COMPLETA
 * ([fullW] x [fullH]), aunque se reciba reducida.
 */
class AiRecognizer(private val llm: LlmEngine) {

    data class Progress(val fraction: Float, val label: String)

    class PageResult(val ocr: OcrResult, val layout: DocLayout, val warning: String? = null)

    /** Generación con su guardia de bucles. */
    private class Gen(val text: String, val looped: Boolean, val result: LlmResult)

    // ------------------------------------------------------------------------------------------ Paddle

    /**
     * Lee la página con PaddleOCR-VL (ya cargado). [page] = procesado reducido (lado <= ~2600 px) de una imagen de
     * [fullW] x [fullH]; [mlkit] = OCR de ML Kit de la misma página (o null) para validar la salida y orientar tablas.
     * Devuelve null si la salida es basura (el llamador conserva ML Kit).
     */
    suspend fun readWithPaddle(
        page: Bitmap,
        fullW: Int,
        fullH: Int,
        mlkit: OcrResult?,
        docType: String?,
        onProgress: (Progress) -> Unit,
    ): PageResult? {
        val model = ModelCatalog.DOCUMENT_READER
        val spotTask = model.task("spotting")!!
        val mlkitScaled = mlkit?.let { scaleOcr(it, fullW, fullH) }
        val mlChars = mlkitScaled?.let { AiLayout.letters(it.text) } ?: 0

        // 1) Texto con posición
        val spot = generate(model, spotTask, page, LoopGuard.Mode.SPOTTING, expectTokens = 900) { f ->
            onProgress(Progress(f * 0.7f, "Leyendo texto"))
        }
        val items = IaParse.parseSpotting(spot.text)
        val sp = AiLayout.spotToPage(items, fullW, fullH)
        val aiChars = AiLayout.letters(sp.ocr.text)
        var warning: String? = null
        val textBlocks: List<AiLayout.TextBlockPx>
        val ocr: OcrResult
        if (AiLayout.isGarbage(aiChars, mlChars, spot.looped) || sp.ocr.blocks.isEmpty()) {
            Log.w(TAG, "Spotting inservible (letras=$aiChars, mlkit=$mlChars, bucle=${spot.looped}); se prueba OCR: alineado")
            // 1b) Texto sin posiciones alineado a las líneas de ML Kit
            val plain = generate(model, model.task("ocr")!!, page, LoopGuard.Mode.TEXT, expectTokens = 600) { f ->
                onProgress(Progress(0.7f + f * 0.25f, "Leyendo texto (2.º intento)"))
            }
            val clean = IaText.cleanOcr(plain.text)
            if (AiLayout.isGarbage(AiLayout.letters(clean), mlChars, plain.looped)) return null
            ocr = AiLayout.alignToLines(mlkitScaled, clean) ?: return null
            val lay = AiLayout.fromOcr(ocr, docType, model.id)
            return PageResult(ocr, lay, "La IA no pudo situar el texto; se usaron las líneas del OCR básico")
        } else {
            ocr = sp.ocr
            textBlocks = sp.blocks
            if (spot.looped) warning = "La IA se repetía en parte de la página; se cortó"
        }

        // 2) Regiones no textuales sobre la página reducida
        val regions = ArrayList<LayoutBlock>()
        try {
            regions += readRegions(page, fullW, fullH, sp, mlkitScaled, onProgress)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Regiones (tablas/figuras/fórmulas) fallidas", t)
        }
        val layout = AiLayout.assemble(fullW, fullH, textBlocks, regions, docType, model.id)
        onProgress(Progress(1f, "Listo"))
        return PageResult(ocr, layout, warning)
    }

    private suspend fun readRegions(
        page: Bitmap,
        fullW: Int,
        fullH: Int,
        sp: AiLayout.SpotPage,
        mlkit: OcrResult?,
        onProgress: (Progress) -> Unit,
    ): List<LayoutBlock> {
        val model = ModelCatalog.DOCUMENT_READER
        val raster = rasterOf(page, RASTER_SIDE)
        val rw = raster.w; val rh = raster.h
        val toFull = fullW.toFloat() / rw
        val toFullY = fullH.toFloat() / rh
        fun fullBox(r: IRect) = LayoutBox(r.x0 * toFull, r.y0 * toFullY, r.x1 * toFull, r.y1 * toFullY)
        val ruled = raster.ruledRegions()
        val out = ArrayList<LayoutBlock>()
        val ruledTables = ruled.filter { it.kind == PageRegions.Ruled.Kind.TABLE }
        // Tablas sin reglado: renglones del Spotting con varias celdas alineadas en columnas.
        val textTables = AiLayout.textGridTables(sp.items).map { tt ->
            fun x(v: Float) = (v / 1000f * rw).roundToInt().coerceIn(0, rw)
            fun y(v: Float) = (v / 1000f * rh).roundToInt().coerceIn(0, rh)
            PageRegions.Ruled(IRect(x(tt.box[0]), y(tt.box[1]), x(tt.box[2]), y(tt.box[3])), emptyList(), tt.colSeps.map { x(it) }, PageRegions.Ruled.Kind.TABLE)
        }.filter { t -> ruled.none { r -> t.rect.intersect(r.rect) > 0.3 * min(t.rect.area, r.rect.area) } }
        val tables = (ruledTables + textTables).take(MAX_TABLES)
        val axes = ruled.filter { it.kind == PageRegions.Ruled.Kind.CHART_AXES }.take(MAX_CHARTS)

        // Fórmulas candidatas (líneas del Spotting)
        val formulaItems = sp.items.filter { it.box != null && AiLayout.looksLikeFormula(it.text) }
            .filter { f -> tables.none { t -> inside01(f.box!!, t.rect, rw, rh) } }
            .take(MAX_FORMULAS)
        val steps = (tables.size + axes.size + formulaItems.size).coerceAtLeast(1)
        var step = 0
        fun stepProgress(label: String): (Float) -> Unit = { f ->
            onProgress(Progress(0.7f + 0.3f * ((step + f) / steps), label))
        }

        // Tablas
        for (t in tables) {
            val box = fullBox(t.rect)
            val cells = try { readTable(page, raster, t, sp, mlkit, fullW, fullH, stepProgress("Leyendo tabla")) } catch (e: LlmException) { Log.w(TAG, "tabla", e); null }
            step++
            out += if (cells != null) AiLayout.tableBlock(cells, box, emptyList())
            else LayoutBlock(type = BlockType.TABLE, box = box)
        }

        // Gráficos (ejes claros): región ampliada para incluir las etiquetas.
        for (a in axes) {
            val r = a.rect
            val ext = IRect(
                max(0, r.x0 - (r.w * 0.15f).roundToInt()), max(0, r.y0 - (r.h * 0.08f).roundToInt()),
                min(rw, r.x1 + (r.w * 0.05f).roundToInt()), min(rh, r.y1 + (r.h * 0.15f).roundToInt()),
            )
            val crop = cropScaled(page, ext, rw, rh)
            val chart = try {
                crop.use { generate(model, model.task("chart")!!, it, LoopGuard.Mode.TEXT, expectTokens = 150, onFraction = stepProgress("Leyendo gráfico")) }
            } catch (e: LlmException) { Log.w(TAG, "gráfico", e); null }
            step++
            val data = chart?.let { IaParse.parseChart(it.text) }?.takeIf { plausibleChart(it) }
            out += if (data != null) LayoutBlock(type = BlockType.CHART, box = fullBox(ext), chartData = listOf(data.header) + data.rows)
            else LayoutBlock(type = BlockType.FIGURE, box = fullBox(ext))
        }

        // Figuras: zonas grandes con contenido y sin texto (ni tabla ni gráfico).
        val cell = max(8, max(rw, rh) / 48)
        val (content, gw, gh) = raster.contentGrid(cell)
        val text = BooleanArray(gw * gh)
        for (it in sp.items) {
            val b = it.box ?: continue
            val pad = (b[3] - b[1]) * 0.5f
            val x0 = ((b[0] - pad) / 1000f * rw / cell).toInt().coerceIn(0, gw - 1)
            val x1 = ((b[2] + pad) / 1000f * rw / cell).toInt().coerceIn(0, gw - 1)
            val y0 = ((b[1] - pad) / 1000f * rh / cell).toInt().coerceIn(0, gh - 1)
            val y1 = ((b[3] + pad) / 1000f * rh / cell).toInt().coerceIn(0, gh - 1)
            for (y in y0..y1) for (x in x0..x1) text[y * gw + x] = true
        }
        val taken = (tables.map { it.rect } + axes.map { it.rect }).map { IRect(it.x0 / cell, it.y0 / cell, (it.x1 + cell - 1) / cell, (it.y1 + cell - 1) / cell) }
        for (f in AiLayout.figureRegions(content, text, gw, gh, taken).take(MAX_FIGURES)) {
            val r = IRect(f.x0 * cell, f.y0 * cell, min(rw, f.x1 * cell), min(rh, f.y1 * cell))
            out += LayoutBlock(type = BlockType.FIGURE, box = fullBox(r))
        }

        // Fórmulas
        for (it in formulaItems) {
            val b = it.box!!
            val hh = (b[3] - b[1])
            val r = IRect(
                ((b[0] - hh * 0.3f) / 1000f * rw).toInt().coerceIn(0, rw - 1), ((b[1] - hh * 0.4f) / 1000f * rh).toInt().coerceIn(0, rh - 1),
                ((b[2] + hh * 0.3f) / 1000f * rw).roundToInt().coerceIn(1, rw), ((b[3] + hh * 0.4f) / 1000f * rh).roundToInt().coerceIn(1, rh),
            )
            if (r.w < 4 || r.h < 4) { step++; continue }
            val crop = cropScaled(page, r, rw, rh)
            val g = try {
                crop.use { c -> generate(model, model.task("formula")!!, c, LoopGuard.Mode.TEXT, expectTokens = 60, onFraction = stepProgress("Leyendo fórmula")) }
            } catch (e: LlmException) { Log.w(TAG, "fórmula", e); null }
            step++
            val f = g?.takeIf { !it.looped }?.let { IaParse.parseFormula(it.text) } ?: continue
            if (f.latex.isBlank() || f.latex.length > 800) continue
            val px = it.quadPx(fullW, fullH)
            out += LayoutBlock(
                type = BlockType.FORMULA,
                box = LayoutBox(b[0] * fullW / 1000f, b[1] * fullH / 1000f, b[2] * fullW / 1000f, b[3] * fullH / 1000f),
                text = it.text,
                latex = f.latex + (f.tag?.let { t -> " \\tag{$t}" } ?: ""),
                lines = listOf(com.scannerpromax.domain.LayoutLine(it.text,
                    LayoutBox(b[0] * fullW / 1000f, b[1] * fullH / 1000f, b[2] * fullW / 1000f, b[3] * fullH / 1000f),
                    px?.takeIf { q -> q.size == 4 }?.flatMap { q -> listOf(q[0], q[1]) } ?: emptyList())),
            )
        }
        return out
    }

    /** Recorte (con orientación corregida) y lectura OTSL de una tabla; por bandas de columnas si es ancha. */
    private suspend fun readTable(
        page: Bitmap,
        raster: PageRegions,
        t: PageRegions.Ruled,
        sp: AiLayout.SpotPage,
        mlkit: OcrResult?,
        fullW: Int,
        fullH: Int,
        progress: (Float) -> Unit,
    ): IaParse.OtslTable? {
        val model = ModelCatalog.DOCUMENT_READER
        val rw = raster.w; val rh = raster.h
        val r = t.rect
        val pad = max(2, (min(r.w, r.h) * 0.015f).roundToInt())
        val rect = IRect(max(0, r.x0 - pad), max(0, r.y0 - pad), min(rw, r.x1 + pad), min(rh, r.y1 + pad))
        // (las tablas sin reglado salen de renglones horizontales del Spotting: ya están derechas)
        val rotation = if (t.rows.isEmpty()) 0 else tableRotation(rect, rw, rh, sp, mlkit, fullW, fullH)
        val crop0 = cropScaled(page, rect, rw, rh)
        val crop = if (rotation != 0) rotate(crop0, rotation).also { crop0.recycle() } else crop0
        try {
            // Rectas verticales (columnas) en coordenadas del recorte ya girado, a la escala del recorte.
            val sx = crop.width.toFloat() / (if (rotation % 180 == 0) rect.w else rect.h)
            val colsRaster: List<Int> = when (rotation) {
                90 -> t.rows.map { rect.y1 - it }.sorted()      // horario: lo de abajo pasa a la izquierda
                270 -> t.rows.map { it - rect.y0 }.sorted()
                else -> t.cols.map { it - rect.x0 }
            }
            val cols = colsRaster.map { (it * sx).roundToInt().coerceIn(0, crop.width) }
            val nCols = (cols.size - 1).coerceAtLeast(1)
            val bands = if (cols.size >= 3) AiLayout.columnBands(nCols) else listOf(0 until nCols)
            suspend fun readBands(bs: List<IntRange>): IaParse.OtslTable? {
                val parts = ArrayList<IaParse.OtslTable>()
                for ((i, b) in bs.withIndex()) {
                    val img = if (bs.size == 1) crop else bandImage(crop, cols, b, keyColumn = i > 0)
                    val g = try {
                        generate(model, model.task("table")!!, img, LoopGuard.Mode.OTSL, expectTokens = 600) { f -> progress((i + f) / bs.size) }
                    } finally {
                        if (img !== crop) img.recycle()
                    }
                    if (g.looped) return null
                    parts += IaParse.parseOtsl(g.text)
                }
                return AiLayout.mergeBands(parts)
            }
            var table = readBands(bands)
            if (table == null && nCols > 5 && cols.size >= 3) table = readBands(AiLayout.columnBands(nCols, maxCols = 5))
            return table?.takeIf { it.nRows >= 2 && it.nCols >= 2 }
        } finally {
            crop.recycle()
        }
    }

    /**
     * Giro (0/90/270) que deja horizontal el texto de una tabla: ángulo de las líneas de ML Kit dentro de la región
     * (horario positivo) o, si no hay, forma de las cajas del Spotting (muchas más altas que anchas = texto vertical;
     * se gira a derechas, el caso real del banco).
     */
    private fun tableRotation(rect: IRect, rw: Int, rh: Int, sp: AiLayout.SpotPage, mlkit: OcrResult?, fullW: Int, fullH: Int): Int {
        val fx = fullW.toFloat() / rw; val fy = fullH.toFloat() / rh
        val region = OcrRect(rect.x0 * fx, rect.y0 * fy, rect.x1 * fx, rect.y1 * fy)
        val ml = mlkit?.blocks?.flatMap { it.lines }?.filter { l -> inside(l.box, region) && l.text.length >= 3 }.orEmpty()
        if (ml.size >= 3) {
            val down = ml.count { it.angle > 60f }
            val up = ml.count { it.angle < -60f }
            if (down > ml.size / 2) return 270
            if (up > ml.size / 2) return 90
            return 0
        }
        val words = sp.items.filter { it.text.trim().length >= 4 }.mapNotNull { it.box }.filter { b ->
            inside(OcrRect(b[0] * fullW / 1000f, b[1] * fullH / 1000f, b[2] * fullW / 1000f, b[3] * fullH / 1000f), region)
        }
        if (words.size >= 3) {
            val tall = words.count { b -> (b[3] - b[1]) * fullH > 1.3f * (b[2] - b[0]) * fullW }
            if (tall > words.size * 0.6f) return 90
        }
        return 0
    }

    /** Imagen de una banda de columnas [b] (más la columna clave a la izquierda si [keyColumn]). */
    private fun bandImage(crop: Bitmap, cols: List<Int>, b: IntRange, keyColumn: Boolean): Bitmap {
        val x0 = cols[b.first.coerceIn(0, cols.size - 1)]
        val x1 = cols[(b.last + 1).coerceIn(0, cols.size - 1)]
        val kx0 = cols[0]; val kx1 = cols[1.coerceAtMost(cols.size - 1)]
        val kw = if (keyColumn) (kx1 - kx0).coerceAtLeast(0) else 0
        val bw = (x1 - x0).coerceAtLeast(1)
        val out = Bitmap.createBitmap(kw + bw, crop.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        if (kw > 0) c.drawBitmap(crop, android.graphics.Rect(kx0, 0, kx1, crop.height), android.graphics.Rect(0, 0, kw, crop.height), null)
        c.drawBitmap(crop, android.graphics.Rect(x0, 0, x1, crop.height), android.graphics.Rect(kw, 0, kw + bw, crop.height), null)
        return out
    }

    private fun plausibleChart(c: IaParse.Chart): Boolean {
        if (c.header.size < 2 || c.rows.isEmpty() || c.rows.size > 60) return false
        val data = c.rows.flatMap { it.drop(1) }
        if (data.isEmpty()) return false
        val nums = data.count { IaParse.toNumber(it) != null }
        return nums >= data.size * 0.5f
    }

    // ------------------------------------------------------------------------------------------ por partes

    /**
     * Reconstrucción POR PARTES (banco recon2; [PartsPipeline]): regiones de PP-DocLayoutV3 ([regions], en px de
     * [analysis]) → Spotting de página o por región → tabla/gráfico/fórmula por recorte → dibujos, rayas, fondos y
     * estilo. [analysis] = página procesada (ya derecha) reducida a ≤ 1600 px, que es también lo que ve el modelo.
     * Coordenadas de salida en [fullW] x [fullH]. null si no sale texto utilizable (el llamador prueba lo de siempre).
     */
    suspend fun readByParts(
        analysis: Rgb,
        regions: List<PpLayout.Region>,
        fullW: Int,
        fullH: Int,
        mlkit: OcrResult?,
        docType: String?,
        rotation: Int,
        onProgress: (Progress) -> Unit,
    ): PageResult? {
        val model = ModelCatalog.DOCUMENT_READER
        val reader = PaddlePartsReader(analysis, model) { f, label -> onProgress(Progress(f, label)) }
        val res = PartsPipeline(reader).run(
            PartsPipeline.Input(analysis, regions, docType, model.id + "+" + ModelCatalog.PAGE_LAYOUT.id, rotation),
        ) { f, label -> reader.base = f; onProgress(Progress(f, label)) }
        val layout = PartsPipeline.scaleLayout(res.layout, fullW, fullH)
        val ocr = PartsPipeline.ocrOf(layout)
        val mlChars = mlkit?.let { AiLayout.letters(it.text) } ?: 0
        if (AiLayout.isGarbage(AiLayout.letters(ocr.text), mlChars, looped = false)) return null
        Log.i(TAG, "por partes: modo=${res.meta.mode} regiones=${res.meta.regions} libres=${res.meta.freeBlocks} rayas=${res.meta.rules} " +
            "fondos=${res.meta.fills} dibujos=${res.meta.drawings} llamadas=${res.meta.calls}")
        val warning = if (res.meta.pageLooped) "La IA se repetía al leer la página entera; se leyó por partes" else null
        return PageResult(ocr, layout, warning)
    }

    /** [PartsReader] con PaddleOCR-VL (ya cargado): recortes de la imagen analizada. */
    private inner class PaddlePartsReader(
        private val img: Rgb,
        private val model: AiModel,
        private val progress: (Float, String) -> Unit,
    ) : PartsReader {
        @Volatile var base = 0f

        private fun bitmapOf(b: FloatArray?): Bitmap {
            val c = if (b == null) img else img.crop(b[0].toInt(), b[1].toInt(), kotlin.math.ceil(b[2]).toInt(), kotlin.math.ceil(b[3]).toInt())
            return Bitmap.createBitmap(c.px, c.w, c.h, Bitmap.Config.ARGB_8888)
        }

        private suspend fun run(taskId: String, bmp: Bitmap, mode: LoopGuard.Mode, expect: Int, maxTokens: Int?, label: String): PartsReader.Out = try {
            val g = generate(model, model.task(taskId)!!, bmp, mode, expect, maxTokens) { f -> progress(base, "$label ${(f * 100).toInt()} %") }
            PartsReader.Out(g.text, g.looped || g.result.stopReason == StopReason.MAX_TOKENS)
        } finally {
            bmp.recycle()
        }

        override suspend fun spotting(crop: FloatArray?, maxTokens: Int): PartsReader.Out =
            run("spotting", bitmapOf(crop), LoopGuard.Mode.SPOTTING, if (crop == null) 900 else maxTokens / 3, maxTokens, "Leyendo texto")

        override suspend fun table(pieces: List<PartsReader.Piece>): PartsReader.Out {
            val parts = pieces.map { bitmapOf(it.src) }
            val w = pieces.indices.maxOf { (pieces[it].dstX + parts[it].width).toInt() }.coerceAtLeast(1)
            val h = parts.maxOf { it.height }.coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            parts.forEachIndexed { i, p -> c.drawBitmap(p, pieces[i].dstX, 0f, null); p.recycle() }
            return run("table", out, LoopGuard.Mode.OTSL, 600, null, "Leyendo tabla")
        }

        override suspend fun chart(crop: FloatArray): PartsReader.Out = run("chart", bitmapOf(crop), LoopGuard.Mode.TEXT, 150, null, "Leyendo gráfico")

        override suspend fun formula(crop: FloatArray): PartsReader.Out = run("formula", bitmapOf(crop), LoopGuard.Mode.TEXT, 60, null, "Leyendo fórmula")
    }

    // ------------------------------------------------------------------------------------------ Qwen

    /**
     * Transcripción con Qwen (ya cargado) alineada con las líneas de [mlkit] (posiciones). null si no hay base o la
     * salida es basura.
     */
    suspend fun readWithQwen(
        page: Bitmap,
        fullW: Int,
        fullH: Int,
        mlkit: OcrResult?,
        docType: String?,
        onProgress: (Progress) -> Unit,
    ): PageResult? {
        val model = ModelCatalog.VISUAL_ASSISTANT
        val base = mlkit?.let { scaleOcr(it, fullW, fullH) }
        val g = generate(model, model.task("ocr")!!, page, LoopGuard.Mode.TEXT, expectTokens = 600) { f -> onProgress(Progress(f, "Leyendo texto")) }
        val clean = IaText.cleanOcr(g.text)
        val mlChars = base?.let { AiLayout.letters(it.text) } ?: 0
        if (AiLayout.isGarbage(AiLayout.letters(clean), mlChars, g.looped)) return null
        val ocr = AiLayout.alignToLines(base, clean)
            ?: OcrResult(text = clean, imageWidth = fullW, imageHeight = fullH, blocks = emptyList())
        onProgress(Progress(1f, "Listo"))
        return PageResult(ocr, AiLayout.fromOcr(ocr, docType, model.id), if (base == null) "Texto sin posiciones (no hubo OCR básico para situarlo)" else null)
    }

    /** Tipo de documento con Qwen (ya cargado), pregunta cerrada a 512 px. Una de [IaText.DOC_CLASSES]. */
    suspend fun classify(image: Bitmap): String {
        val model = ModelCatalog.VISUAL_ASSISTANT
        val g = generate(model, model.task("classify")!!, image, LoopGuard.Mode.TEXT, expectTokens = 4) {}
        return IaText.parseClass(g.text)
    }

    /**
     * Caja del documento en una FOTO (Qwen bbox_2d, presence_penalty 1.5): rectángulo normalizado 0..1
     * (izq., arr., der., abajo) o null si no da una caja útil (vacía, degenerada o la imagen entera).
     */
    suspend fun locateDocument(photo: Bitmap): FloatArray? {
        val model = ModelCatalog.VISUAL_ASSISTANT
        val g = generate(model, model.task("docbox")!!, photo, LoopGuard.Mode.TEXT, expectTokens = 40) {}
        return pickDocumentBox(IaParse.parseLayoutJson(g.text))
    }

    // ------------------------------------------------------------------------------------------ generación

    private suspend fun generate(
        model: AiModel,
        task: ModelTask,
        image: Bitmap,
        mode: LoopGuard.Mode,
        expectTokens: Int,
        maxTokensOverride: Int? = null,
        onFraction: (Float) -> Unit,
    ): Gen = coroutineScope {
        val d = model.defaults
        val guard = LoopGuard(mode)
        val maxSide = if (task.maxImageSide > 0) task.maxImageSide else max(d.maxImageSide, 2048)
        val maxPx = if (task.maxImagePixels > 0) task.maxImagePixels else d.maxImagePixels
        val s = LlmEngine.fitScale(image.width, image.height, maxSide, maxPx)
        val mp = image.width * s * image.height * s / 1e6f
        val expectPromptMs = max(1500f, mp * msPerMp(model.id))
        val t0 = System.currentTimeMillis()
        var tokens = 0
        val ticker = launch {
            while (true) {
                val el = (System.currentTimeMillis() - t0).toFloat()
                val f = if (tokens == 0) 0.5f * (1f - exp(-el / expectPromptMs)) else 0.5f + 0.5f * min(1f, tokens.toFloat() / expectTokens)
                onFraction(f.coerceIn(0f, 0.99f))
                delay(500)
            }
        }
        try {
            val r = llm.generate(
                LlmRequest(
                    prompt = task.prompt,
                    images = listOf(image),
                    maxTokens = maxTokensOverride ?: if (task.maxTokens > 0) task.maxTokens else d.maxTokens,
                    temperature = d.temperature,
                    repeatPenalty = d.repeatPenalty,
                    presencePenalty = task.presencePenalty,
                    special = d.specialTokens,
                    useChatTemplate = d.useChatTemplate,
                    enableThinking = d.enableThinking,
                    maxImageSide = maxSide,
                    maxImagePixels = maxPx,
                ),
            ) { piece ->
                tokens++
                if (!guard.feed(piece)) llm.cancel()
            }
            if (r.imageMs > 0 && mp > 0.05f) learnMsPerMp(model.id, r.promptMs / mp)
            val looped = guard.looped || (r.stopReason == StopReason.MAX_TOKENS && guard.check(r.text))
            Log.i(TAG, "${model.id}/${task.id}: img=${r.imageTokens} tok gen=${r.generatedTokens} prompt=${r.promptMs} ms gen=${r.generationMs} ms stop=${r.stopReason} bucle=$looped")
            Gen(r.text, looped, r)
        } finally {
            ticker.cancel()
        }
    }

    companion object {
        private const val TAG = "AiRecognizer"
        private const val RASTER_SIDE = 1200
        private const val MAX_TABLES = 4
        private const val MAX_CHARTS = 3
        private const val MAX_FIGURES = 8
        private const val MAX_FORMULAS = 8

        /** Tiempo de prefill por megapíxel aprendido en este móvil (estimación de progreso). */
        private val msPerMpLearned = java.util.concurrent.ConcurrentHashMap<String, Float>()
        private fun msPerMp(id: String) = msPerMpLearned[id] ?: 60_000f
        private fun learnMsPerMp(id: String, v: Float) {
            val old = msPerMpLearned[id]
            msPerMpLearned[id] = if (old == null) v else old * 0.6f + v * 0.4f
        }

        /** Caja útil del documento entre las de Qwen (la mayor entre el 5 % y el 97 % del área). 0..1. */
        fun pickDocumentBox(boxes: List<IaParse.LayoutBox>): FloatArray? = boxes
            .map { it.bbox }
            .filter { b -> val a = (b[2] - b[0]) * (b[3] - b[1]) / 1e6f; a in 0.05f..0.97f }
            .maxByOrNull { (it[2] - it[0]) * (it[3] - it[1]) }
            ?.let { b -> floatArrayOf(b[0] / 1000f, b[1] / 1000f, b[2] / 1000f, b[3] / 1000f) }

        /** Reescala un OCR a otras dimensiones de la misma imagen. */
        fun scaleOcr(o: OcrResult, w: Int, h: Int): OcrResult {
            if (o.imageWidth == w && o.imageHeight == h || o.imageWidth <= 0 || o.imageHeight <= 0) return o
            val sx = w.toFloat() / o.imageWidth; val sy = h.toFloat() / o.imageHeight
            fun r(b: OcrRect) = OcrRect(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)
            return o.copy(
                imageWidth = w, imageHeight = h,
                blocks = o.blocks.map { b ->
                    OcrBlock(b.text, r(b.box), b.lines.map { l -> OcrLine(l.text, r(l.box), l.words.map { OcrWord(it.text, r(it.box), it.confidence) }, l.angle, l.confidence) })
                },
            )
        }

        private fun inside(a: OcrRect, b: OcrRect): Boolean {
            val cx = (a.left + a.right) / 2; val cy = (a.top + a.bottom) / 2
            return cx >= b.left && cx <= b.right && cy >= b.top && cy <= b.bottom
        }

        private fun inside01(b: FloatArray, r: IRect, rw: Int, rh: Int): Boolean {
            val cx = (b[0] + b[2]) / 2000f * rw; val cy = (b[1] + b[3]) / 2000f * rh
            return cx >= r.x0 && cx <= r.x1 && cy >= r.y0 && cy <= r.y1
        }

        /** Raster gris + croma de [bmp] reducido a lado <= [side]. */
        fun rasterOf(bmp: Bitmap, side: Int): PageRegions {
            val s = min(1f, side.toFloat() / max(bmp.width, bmp.height))
            val w = max(1, (bmp.width * s).roundToInt()); val h = max(1, (bmp.height * s).roundToInt())
            val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
            try {
                val px = IntArray(w * h)
                small.getPixels(px, 0, w, 0, 0, w, h)
                val g = ByteArray(w * h); val c = ByteArray(w * h)
                for (i in px.indices) {
                    val p = px[i]
                    val r = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val b = p and 0xFF
                    g[i] = ((r * 299 + gg * 587 + b * 114) / 1000).toByte()
                    c[i] = (max(r, max(gg, b)) - min(r, min(gg, b))).toByte()
                }
                return PageRegions(w, h, g, c)
            } finally {
                if (small !== bmp) small.recycle()
            }
        }

        /** Recorte de [page] de la región [r] expresada en un raster [rw] x [rh]. Siempre un bitmap nuevo. */
        fun cropScaled(page: Bitmap, r: IRect, rw: Int, rh: Int): Bitmap {
            val sx = page.width.toFloat() / rw; val sy = page.height.toFloat() / rh
            val x0 = (r.x0 * sx).toInt().coerceIn(0, page.width - 1)
            val y0 = (r.y0 * sy).toInt().coerceIn(0, page.height - 1)
            val x1 = (r.x1 * sx).roundToInt().coerceIn(x0 + 1, page.width)
            val y1 = (r.y1 * sy).roundToInt().coerceIn(y0 + 1, page.height)
            val out = Bitmap.createBitmap(page, x0, y0, x1 - x0, y1 - y0)
            return if (out === page) page.copy(Bitmap.Config.ARGB_8888, false) else out
        }

        fun rotate(src: Bitmap, degrees: Int): Bitmap {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }

        private inline fun <T> Bitmap.use(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }
    }
}
