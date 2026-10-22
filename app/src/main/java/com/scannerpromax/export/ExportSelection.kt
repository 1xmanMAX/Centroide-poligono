package com.scannerpromax.export

import com.scannerpromax.domain.Document
import com.scannerpromax.domain.Page
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Subconjunto de páginas a exportar (lógica pura, probada en JVM). La pantalla Documento, en modo selección, abre
 * Exportar con `export/{docId}?pages=id1,id2`; aquí se codifica/decodifica ese argumento y se filtran las páginas
 * SIEMPRE en el orden del documento (no en el orden en que se marcaron).
 */
object ExportSelection {

    /** Valor del argumento `pages` (ids separados por comas, cada uno codificado); null = todas. */
    fun encode(pageIds: Collection<String>?): String? {
        if (pageIds.isNullOrEmpty()) return null
        return pageIds.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
    }

    /** Ids del argumento `pages`; null si falta o está vacío (= todas las páginas). */
    fun decode(arg: String?): List<String>? {
        if (arg.isNullOrBlank()) return null
        val ids = arg.split(',').map { URLDecoder.decode(it, "UTF-8") }.filter { it.isNotEmpty() }.distinct()
        return ids.ifEmpty { null }
    }

    /** Páginas de [pages] cuyo id está en [pageIds], en el orden del documento; [pageIds] null = todas. */
    fun pages(pages: List<Page>, pageIds: Collection<String>?): List<Page> {
        if (pageIds == null) return pages
        val set = pageIds.toHashSet()
        return pages.filter { it.id in set }
    }

    /** Copia del documento con solo las páginas elegidas (ver [pages]). */
    fun apply(doc: Document, pageIds: Collection<String>?): Document =
        if (pageIds == null) doc else doc.copy(pages = pages(doc.pages, pageIds))

    /** "3 de 10 páginas" (con selección) o "10 páginas". */
    fun label(selected: Int, total: Int): String {
        val unit = if (total == 1) "página" else "páginas"
        return if (selected == total) "$total $unit" else "$selected de $total $unit"
    }
}
