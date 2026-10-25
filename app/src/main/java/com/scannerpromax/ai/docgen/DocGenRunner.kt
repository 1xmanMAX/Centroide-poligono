package com.scannerpromax.ai.docgen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.scannerpromax.NativeLibs
import com.scannerpromax.ai.cloud.CloudKeyStore
import com.scannerpromax.ai.cloud.CloudPricing
import com.scannerpromax.ai.cloud.CloudUsageStore
import com.scannerpromax.data.DocumentRepository
import com.scannerpromax.export.ExportSelection
import com.scannerpromax.export.ImageExporter
import com.scannerpromax.pdf.PdfReconstructor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Lo que pidió el usuario en la hoja "Reconstruir con IA". */
data class DocGenSpec(
    val docId: String,
    /** null = todas las páginas. */
    val pageIds: List<String>?,
    val formats: Set<OutputFormat>,
    val mode: DocGenMode,
    val model: DocGenModel,
    val filename: String,
    val title: String,
)

/**
 * Ejecuta una reconstrucción en Android: prepara cada página (procesado reducido a lo que aprovecha el proveedor,
 * OCR guardado de la página —lo produce Indra— y maquetación si existe), lanza [DocGenEngine], genera los archivos
 * (Word con [DocxRenderer], PDF con [PdfDocRenderer]) en `cache/docgen/<trabajo>/` y los guarda en
 * Descargas/EscanerProMax con [ImageExporter.saveToDownloads]. Apunta tokens y coste en [CloudUsageStore].
 */
class DocGenRunner(
    private val context: Context,
    private val repo: DocumentRepository,
    private val keys: CloudKeyStore,
    private val usage: CloudUsageStore,
    private val exporter: ImageExporter,
) {
    suspend fun run(spec: DocGenSpec, jobId: String, onProgress: (DocGenProgress) -> Unit): DocGenOutcome {
        val doc = repo.get(spec.docId) ?: throw DocGenException("El documento ya no existe")
        val pages = ExportSelection.apply(doc, spec.pageIds).pages
        if (pages.isEmpty()) throw DocGenException("No hay páginas que reconstruir")
        val key = keys.get(spec.model.keyId) ?: throw DocGenException("Falta la clave de ${spec.model.keyId.company}: añádela en Ajustes › IA en la nube")
        val client: DocGenClient = when (spec.model.keyId) {
            com.scannerpromax.ai.cloud.CloudProviderId.DEEPSEEK -> DeepSeekDocClient(key)
            com.scannerpromax.ai.cloud.CloudProviderId.CLAUDE_HAIKU -> ClaudeDocClient(key, spec.model)
        }
        try {
            val files = ArrayList<File>()
            val inputs = ArrayList<PageInput>()
            for ((i, p) in pages.withIndex()) {
                coroutineContext.ensureActive()
                onProgress(DocGenProgress(0.02f * i / pages.size, i + 1, pages.size, "Preparando la página ${i + 1} de ${pages.size}…", 0.0))
                val file = repo.processedFile(spec.docId, p)
                files += file
                val ocr = runCatching { repo.ensureOcr(spec.docId, p.id) }.getOrNull()
                val layout = runCatching { repo.loadLayout(spec.docId, p.id) }.getOrNull()
                val (jpeg, w, h) = withContext(Dispatchers.Default) { encodePage(file, client.maxLongSide, client.maxPixels) }
                inputs += PageInput(i + 1, jpeg, w, h, PageText.lines(ocr), PageText.regions(layout))
            }
            val crops = AndroidCropSource(files)
            val dir = File(context.cacheDir, "docgen/$jobId").apply { mkdirs() }
            cleanupOld(File(context.cacheDir, "docgen"))
            val engine = DocGenEngine(
                client, inputs,
                DocGenRequest(spec.mode, spec.formats, spec.filename, spec.title),
                finisher = { gd, fin -> withContext(Dispatchers.IO) { writeFiles(gd, fin, crops, dir) } },
                onProgress = onProgress,
                onUsage = { model, u ->
                    val cost = CloudPricing.costForModel(model, u, Instant.now())
                    usage.recordCost(spec.model.keyId, u, cost, page = false)
                    cost
                },
            )
            return engine.run()
        } finally {
            client.close()
        }
    }

    private suspend fun writeFiles(gd: GenDocument, fin: FinishRequest, crops: CropSource, dir: File): List<OutputFile> {
        val out = ArrayList<OutputFile>()
        for (f in fin.formats.sortedBy { it.ordinal }) {
            coroutineContext.ensureActive()
            val file = File(dir, "${fin.filename}.${f.ext}")
            when (f) {
                OutputFormat.DOCX -> FileOutputStream(file).use { DocxRenderer.write(gd, crops, it) }
                OutputFormat.PDF -> {
                    NativeLibs.ensurePdfBox(context)
                    val pdf = PDDocument(com.scannerpromax.pdf.PdfSupport.tempMemorySetting(context))
                    try {
                        val metrics = PdfReconstructor(context).loadFonts(pdf)
                        PdfDocRenderer(pdf, metrics, crops).render(gd)
                        com.scannerpromax.pdf.PdfSupport.applyMetadata(pdf, gd.title)
                        pdf.save(file)
                    } finally {
                        pdf.close()
                    }
                }
            }
            val uri = runCatching { exporter.saveToDownloads(file, fin.filename, f.mime, f.ext).toString() }
                .onFailure { Log.w(TAG, "No se pudo guardar en Descargas", it) }.getOrNull()
            out += OutputFile(f, file.absolutePath, file.length(), uri)
        }
        return out
    }

    private fun cleanupOld(root: File) {
        val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        root.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
    }

    companion object {
        private const val TAG = "DocGenRunner"
        private const val JPEG_QUALITY = 88

        /** Procesado -> JPEG con el lado largo / píxeles que aprovecha el proveedor. */
        fun encodePage(file: File, maxLongSide: Int, maxPixels: Int): Triple<ByteArray, Int, Int> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val srcW = bounds.outWidth; val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) throw DocGenException("No se pudo leer la imagen de una página")
            var s = min(1.0, maxLongSide.toDouble() / max(srcW, srcH))
            if (srcW.toDouble() * srcH * s * s > maxPixels) s = sqrt(maxPixels.toDouble() / (srcW.toDouble() * srcH))
            val w = max(1, (srcW * s).roundToInt()); val h = max(1, (srcH * s).roundToInt())
            var sample = 1
            while (srcW / (sample * 2) >= w && srcH / (sample * 2) >= h) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw DocGenException("No se pudo leer la imagen de una página")
            val scaled = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
            try {
                val flat = flatten(scaled)
                val bos = ByteArrayOutputStream(w * h / 4)
                flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                if (flat !== scaled) flat.recycle()
                return Triple(bos.toByteArray(), w, h)
            } finally {
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
            }
        }

        /** Fondo blanco para PNG con transparencia (B/N) antes de pasar a JPEG. */
        fun flatten(src: Bitmap): Bitmap {
            if (!src.hasAlpha()) return src
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawColor(android.graphics.Color.WHITE)
            c.drawBitmap(src, 0f, 0f, null)
            return out
        }
    }
}

/**
 * Recortes de las páginas para las imágenes y fórmulas del documento: se decodifica SOLO la región pedida del
 * procesado a resolución completa (BitmapRegionDecoder), reducida a ≤ 2000 px, en JPEG. Caché por (página, caja).
 */
class AndroidCropSource(private val files: List<File>) : CropSource {
    private val cache = HashMap<String, CropImage?>()

    override fun crop(page: Int, box: Box1000): CropImage? = synchronized(cache) {
        cache.getOrPut("$page:${box.toList()}") { load(page, box) }
    }

    private fun load(page: Int, box: Box1000): CropImage? = try {
        loadOrNull(page, box)
    } catch (t: Throwable) {
        Log.w("AndroidCropSource", "recorte fallido", t)
        null
    }

    private fun loadOrNull(page: Int, box: Box1000): CropImage? {
        val f = files.getOrNull(page - 1) ?: return null
        @Suppress("DEPRECATION")
        val dec = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(f.absolutePath) else BitmapRegionDecoder.newInstance(f.absolutePath, false)
        try {
            val w = dec!!.width; val h = dec.height
            val r = Rect(box.x0 * w / 1000, box.y0 * h / 1000, (box.x1 * w + 999) / 1000, (box.y1 * h + 999) / 1000)
            r.intersect(0, 0, w, h)
            if (r.width() < 2 || r.height() < 2) return null
            var sample = 1
            while (max(r.width(), r.height()) / (sample * 2) >= MAX_SIDE) sample *= 2
            val bmp = dec.decodeRegion(r, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val flat = DocGenRunner.flatten(bmp)
            val bos = ByteArrayOutputStream()
            flat.compress(Bitmap.CompressFormat.JPEG, 88, bos)
            val out = CropImage(bos.toByteArray(), flat.width, flat.height)
            if (flat !== bmp) flat.recycle()
            bmp.recycle()
            return out
        } finally {
            dec?.recycle()
        }
    }

    companion object { private const val MAX_SIDE = 2000 }
}
