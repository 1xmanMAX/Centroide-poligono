package com.scannerpromax.export

import android.content.Context
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.ocr.OcrText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Formatos de exportación del texto reconocido. */
enum class TextFormat(val label: String, val ext: String, val mime: String) {
    TXT("Texto (.txt)", "txt", "text/plain"),
    DOCX("Documento Word (.docx)", "docx", DocxWriter.MIME),
}

/**
 * Exporta el texto reconocido (corregido por el usuario si lo hay) de un documento a .txt (UTF-8 con BOM para que
 * el Bloc de notas de Windows muestre bien las tildes) o .docx (un título por página y sus párrafos).
 * Escribe en la caché (carpeta "exports"); para guardar en Descargas usar [ImageExporter.saveToDownloads].
 */
class TextExporter(private val context: Context) {

    suspend fun export(title: String, pages: List<OcrResult?>, format: TextFormat, baseName: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safe = baseName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "Texto" }.take(100)
            val out = File(dir, "$safe.${format.ext}")
            val tmp = File(dir, "$safe.${format.ext}.part")
            try {
                tmp.outputStream().buffered(64 * 1024).use { os ->
                    when (format) {
                        TextFormat.TXT -> {
                            os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                            os.write(plainText(pages).replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
                        }
                        TextFormat.DOCX -> DocxWriter.write(title, sections(pages), os)
                    }
                }
                if (!tmp.renameTo(out)) {
                    out.delete()
                    if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            out
        }

    companion object {
        /** Texto plano de todas las páginas con separadores (si hay más de una). */
        fun plainText(pages: List<OcrResult?>): String = OcrText.documentText(pages)

        /** Secciones del .docx: "Página N" + párrafos reflujados (guiones de fin de línea unidos). */
        fun sections(pages: List<OcrResult?>): List<DocxWriter.Section> = pages.mapIndexed { i, r ->
            val paras = OcrText.paragraphs(r)
            DocxWriter.Section(
                title = if (pages.size > 1) "Página ${i + 1}" else null,
                paragraphs = paras.ifEmpty { listOf("(sin texto)") },
            )
        }
    }
}
