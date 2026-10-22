package com.scannerpromax.ui.review

import com.scannerpromax.domain.Page

/**
 * Modo selección de páginas del Documento (lógica pura, probada en JVM). La selección es una lista de ids; el orden
 * en que se marcan no importa: al exportar, filtrar o eliminar se usa siempre el orden del documento.
 */
internal object PageSelection {

    /** Marca o desmarca [id]. */
    fun toggle(selected: List<String>, id: String): List<String> =
        if (id in selected) selected - id else selected + id

    /** "Seleccionar todo" si falta alguna; "Ninguna" si ya están todas. */
    fun toggleAll(selected: List<String>, all: List<String>): List<String> =
        if (allSelected(selected, all)) emptyList() else all

    fun allSelected(selected: List<String>, all: List<String>): Boolean =
        all.isNotEmpty() && selected.toSet().containsAll(all)

    /** Quita de la selección las páginas que ya no existen (borradas, documento fusionado…). */
    fun prune(selected: List<String>, existing: List<String>): List<String> {
        val set = existing.toHashSet()
        return if (selected.all { it in set }) selected else selected.filter { it in set }
    }

    /** Páginas seleccionadas en el orden del documento. */
    fun inOrder(pages: List<Page>, selected: Collection<String>): List<Page> {
        if (selected.isEmpty()) return emptyList()
        val set = selected.toHashSet()
        return pages.filter { it.id in set }
    }

    /** Cabecera: "Selecciona páginas", "1 seleccionada", "3 seleccionadas". */
    fun title(count: Int): String = when (count) {
        0 -> "Selecciona páginas"
        1 -> "1 seleccionada"
        else -> "$count seleccionadas"
    }
}
