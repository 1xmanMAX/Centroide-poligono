package com.scannerpromax.imaging

/**
 * Tiempos por etapa del procesado de una página (medición barata: dos System.nanoTime por etapa). La sesión vive en el hilo
 * que procesa la página ([begin] / [end]); sin sesión abierta [stage] no mide nada. La última página medida queda en [last]
 * para la línea de diagnóstico de Ajustes › Rendimiento y en el banco de rendimiento (PipelineBenchTest).
 *
 * Las etapas pueden anidarse (el tiempo de cada nombre es inclusivo) y repetirse (se suman). Los trabajos que se reparten
 * en otros hilos usan [current] para sumar en la sesión del hilo que los lanzó.
 */
object PerfTrace {

    class Session {
        /** Nombre -> nanosegundos acumulados (en orden de primera aparición). */
        val stages: MutableMap<String, Long> = java.util.Collections.synchronizedMap(LinkedHashMap())
        val startNs: Long = System.nanoTime()
        /** Informe de duración para la sesión de PerformanceHint ([DevicePerf.boost]); null = sin sesión. */
        @Volatile var hint: ((Long) -> Unit)? = null
        fun add(name: String, ns: Long) {
            synchronized(stages) { stages[name] = (stages[name] ?: 0L) + ns }
            hint?.invoke(ns)
        }
        fun totalMs(): Long = (System.nanoTime() - startNs) / 1_000_000
        fun snapshotMs(): Map<String, Long> = synchronized(stages) { stages.mapValues { it.value / 1_000_000 } }
    }

    private val tl = ThreadLocal<Session?>()

    /** Sesión del hilo actual (null = sin medir). */
    val current: Session? get() = tl.get()

    /** Última página medida: etapa -> ms (incluye "total"). */
    @Volatile var last: Map<String, Long> = emptyMap()
        private set

    /** Abre una sesión en este hilo (sustituye la que hubiera). */
    fun begin(): Session = Session().also { tl.set(it) }

    /** Abre una sesión solo si no hay ya una (la de fuera manda); devuelve la sesión propia o null. */
    fun beginIfNone(): Session? = if (tl.get() != null) null else begin()

    /** Cierra la sesión [s] de este hilo y la publica en [last]. */
    fun end(s: Session?) {
        if (s == null) return
        if (tl.get() === s) tl.set(null)
        val snap = LinkedHashMap(s.snapshotMs())
        snap["total"] = s.totalMs()
        last = snap
    }

    /** Usa [s] como sesión del hilo actual mientras corre [block] (trabajo repartido en otro hilo). */
    inline fun <T> within(s: Session?, block: () -> T): T {
        if (s == null) return block()
        val prev = currentInternal()
        setInternal(s)
        try { return block() } finally { setInternal(prev) }
    }

    @PublishedApi internal fun currentInternal(): Session? = tl.get()
    @PublishedApi internal fun setInternal(s: Session?) { tl.set(s) }

    /** Mide [block] como la etapa [name] de la sesión del hilo (si la hay). */
    inline fun <T> stage(name: String, block: () -> T): T {
        val s = currentInternal() ?: return block()
        val t0 = System.nanoTime()
        try { return block() } finally { s.add(name, System.nanoTime() - t0) }
    }

    /** Texto corto «etapa 123 ms · …» de la última página (las 8 etapas más largas). */
    fun summary(max: Int = 8): String {
        val l = last
        if (l.isEmpty()) return "sin medir todavía"
        val total = l["total"]
        val parts = l.filterKeys { it != "total" }.entries.sortedByDescending { it.value }.take(max)
            .joinToString(" · ") { "${it.key} ${it.value} ms" }
        return (total?.let { "total $it ms" } ?: "") + if (parts.isNotEmpty()) " — $parts" else ""
    }
}
