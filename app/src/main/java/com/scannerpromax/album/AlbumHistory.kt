package com.scannerpromax.album

/**
 * Deshacer / rehacer del editor de álbumes: instantáneas de las hojas (datos inmutables, baratas). Los arrastres
 * continuos se guardan UNA vez al soltar (quien llama hace [push] sólo al terminar el gesto).
 */
class AlbumHistory(initial: List<AlbumPage>, private val limit: Int = 80) {
    private val past = ArrayDeque<List<AlbumPage>>()
    private val future = ArrayDeque<List<AlbumPage>>()

    var current: List<AlbumPage> = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Nuevo estado (si es distinto del actual). Borra lo que se podía rehacer. */
    fun push(next: List<AlbumPage>): Boolean {
        if (next == current) return false
        past.addLast(current)
        while (past.size > limit) past.removeFirst()
        future.clear()
        current = next
        return true
    }

    fun undo(): List<AlbumPage>? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(current)
        current = prev
        return prev
    }

    fun redo(): List<AlbumPage>? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        current = next
        return next
    }
}

/**
 * Historial de deshacer de cada álbum abierto, en memoria del proceso: sobrevive a ir a la cámara y volver (la pantalla
 * del editor sale de la composición). Se olvida al salir del editor con «Atrás».
 */
object AlbumSessions {
    private val map = HashMap<String, AlbumHistory>()

    @Synchronized fun get(id: String): AlbumHistory? = map[id]
    @Synchronized fun put(id: String, h: AlbumHistory) { map[id] = h }
    @Synchronized fun forget(id: String) { map.remove(id) }
}

/** Operaciones puras sobre las hojas (las usa el editor; probadas en la JVM). */
object AlbumOps {

    fun updatePage(pages: List<AlbumPage>, pageId: String, f: (AlbumPage) -> AlbumPage) = pages.map { if (it.id == pageId) f(it) else it }

    fun updateSlot(pages: List<AlbumPage>, pageId: String, slotId: String, f: (AlbumSlot) -> AlbumSlot) =
        updatePage(pages, pageId) { p -> p.copy(slots = p.slots.map { if (it.id == slotId) f(it) else it }) }

    fun addSlot(page: AlbumPage, box: Box, label: String? = null): Pair<AlbumPage, AlbumSlot> {
        val s = AlbumGeometry.withBox(AlbumSlot(AlbumStore.newId(), 0f, 0f, 0f, 0f, label = label), box, page)
        return page.copy(slots = page.slots + s) to s
    }

    /** Copia del recuadro (sin el cromo) desplazada 6 mm, dentro de la hoja. */
    fun duplicateSlot(page: AlbumPage, slotId: String): Pair<AlbumPage, AlbumSlot>? {
        val s = page.slots.firstOrNull { it.id == slotId } ?: return null
        val b = AlbumGeometry.box(s, page)
        val nb = AlbumGeometry.keepInside(AlbumGeometry.moved(b, 6.0, 6.0), page.widthMm.toDouble(), page.heightMm.toDouble())
        val copy = AlbumGeometry.withBox(s.copy(id = AlbumStore.newId(), sticker = null), nb, page)
        return page.copy(slots = page.slots + copy) to copy
    }

    fun movePage(pages: List<AlbumPage>, pageId: String, delta: Int): List<AlbumPage> {
        val i = pages.indexOfFirst { it.id == pageId }
        val j = i + delta
        if (i < 0 || j !in pages.indices) return pages
        return pages.toMutableList().apply { add(j, removeAt(i)) }
    }

    /**
     * Cambia el fondo de [page] por [bg] con tamaño [wMm]×[hMm]: los recuadros conservan su posición relativa y su
     * tamaño físico (reducido por igual si la hoja nueva es más pequeña).
     */
    fun replaceBackground(page: AlbumPage, bg: SheetBackground, wMm: Double, hMm: Double): AlbumPage {
        val ow = page.widthMm.toDouble(); val oh = page.heightMm.toDouble()
        val k = minOf(1.0, wMm / ow, hMm / oh)
        val slots = page.slots.map { s ->
            val b = AlbumGeometry.box(s, ow, oh)
            AlbumGeometry.withBox(s, Box(s.cx * wMm, s.cy * hMm, b.w * k, b.h * k, b.angle), wMm, hMm)
        }
        return page.copy(widthMm = wMm.toFloat(), heightMm = hMm.toFloat(), background = bg, rotation = 0, slots = slots)
    }

    /** Hoja nueva con el fondo [bg] (tamaño real si es un PDF; lado largo de A4 si es una imagen). */
    fun pageWith(bg: SheetBackground): AlbumPage {
        val unit = if (bg.kind == BackgroundKind.PDF_PAGE) 25.4 / 72.0 else null
        val (w, h) = AlbumGeometry.sheetSizeFor(bg.srcWidth.toDouble(), bg.srcHeight.toDouble(), unit)
        return AlbumPage(AlbumStore.newId(), w.toFloat(), h.toFloat(), bg)
    }
}
