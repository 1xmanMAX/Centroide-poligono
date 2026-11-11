package com.scannerpromax.imaging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Reparto en paralelo de bucles del procesado (franjas, teselas, zonas, líneas candidatas) sobre un grupo de hilos propio
 * de prioridad alta. El resultado es IDÉNTICO al del bucle en serie: cada índice se calcula por separado y quien llama
 * combina los resultados en el orden original (ninguna suma en coma flotante cambia de orden).
 *
 * - [threads]: hilos a usar (el hilo que llama también trabaja). Lo fija [PerfPolicy.bandThreads]; 1 = en serie.
 * - Anidado: un bucle paralelo dentro de otro corre en serie en el hilo del trabajador (sin bloqueos ni sobre-suscripción).
 *   Las tareas de [async] sí pueden repartir sus propios bucles.
 * - Las sesiones de [PerfTrace] se propagan a los trabajadores.
 * - Prioridad: [priority] (nice de Android) en cada trabajador; se reaplica al cambiar ([applyPriority]).
 */
object Par {

    /** Hilos para los bucles (incluido el que llama). */
    @Volatile var threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    /** Prioridad de Android (android.os.Process.THREAD_PRIORITY_*) de los trabajadores. */
    @Volatile var priority: Int = PerfPolicy.PRIO_DISPLAY
        private set

    private val inWorker = ThreadLocal<Boolean>()
    private val tids = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val created = AtomicInteger()

    private val pool: ThreadPoolExecutor by lazy {
        (Executors.newCachedThreadPool { r ->
            Thread({
                myTid()?.let { tids[it] = true; setPrio(it, priority) }
                r.run()
            }, "imaging-par-${created.incrementAndGet()}").apply { isDaemon = true }
        } as ThreadPoolExecutor).apply { setKeepAliveTime(30, TimeUnit.SECONDS) }
    }

    /** ¿Estamos ya dentro de un trabajador (bucle anidado)? */
    val nested: Boolean get() = inWorker.get() == true

    /** Cambia la prioridad de los trabajadores vivos y de los futuros. */
    fun applyPriority(p: Int) {
        priority = p
        for (t in tids.keys) setPrio(t, p)
    }

    /** Ids de hilo (tid de Linux) de los trabajadores, para la sesión de PerformanceHint. */
    fun workerTids(): IntArray = tids.keys.toIntArray()

    private fun myTid(): Int? = try { android.os.Process.myTid() } catch (_: Throwable) { null }
    private fun setPrio(tid: Int, p: Int) { try { android.os.Process.setThreadPriority(tid, p) } catch (_: Throwable) { } }

    /**
     * Ejecuta [body] para cada índice de 0 until [n] repartido entre los hilos; [state] crea el estado propio de cada hilo
     * (búferes) y [close] lo libera. Lanza la primera excepción de cualquier índice (los demás se abandonan).
     */
    fun <S> forEach(n: Int, state: () -> S, close: (S) -> Unit = {}, body: (S, Int) -> Unit) {
        if (n <= 0) return
        val t = if (nested) 1 else threads.coerceAtMost(n)
        if (t <= 1) {
            val s = state()
            try { for (i in 0 until n) body(s, i) } finally { close(s) }
            return
        }
        val next = AtomicInteger(0)
        val err = AtomicReference<Throwable?>(null)
        val session = PerfTrace.current
        val work = Runnable {
            PerfTrace.within(session) {
                val s = try { state() } catch (e: Throwable) { err.compareAndSet(null, e); return@within }
                try {
                    while (err.get() == null) {
                        val i = next.getAndIncrement()
                        if (i >= n) break
                        body(s, i)
                    }
                } catch (e: Throwable) {
                    err.compareAndSet(null, e)
                } finally {
                    try { close(s) } catch (_: Throwable) { }
                }
            }
        }
        val helpers = t - 1
        val done = CountDownLatch(helpers)
        repeat(helpers) {
            pool.execute {
                inWorker.set(true)
                try { work.run() } finally { inWorker.set(false); done.countDown() }
            }
        }
        val wasWorker = inWorker.get()
        inWorker.set(true)   // el que llama también es trabajador mientras dura el bucle (anidados en serie)
        try { work.run() } finally { inWorker.set(wasWorker) }
        done.await()
        err.get()?.let { throw it }
    }

    /** [forEach] sin estado. */
    fun forEach(n: Int, body: (Int) -> Unit) = forEach(n, { Unit }, {}) { _, i -> body(i) }

    /** Resultados de [f] para 0 until [n], en orden. */
    fun <T> map(n: Int, f: (Int) -> T): List<T> {
        @Suppress("UNCHECKED_CAST")
        val out = arrayOfNulls<Any?>(n)
        forEach(n) { i -> out[i] = f(i) }
        @Suppress("UNCHECKED_CAST")
        return out.toList() as List<T>
    }

    /** [map] con estado por hilo. */
    fun <S, T> map(n: Int, state: () -> S, close: (S) -> Unit, f: (S, Int) -> T): List<T> {
        val out = arrayOfNulls<Any?>(n)
        forEach(n, state, close) { s, i -> out[i] = f(s, i) }
        @Suppress("UNCHECKED_CAST")
        return out.toList() as List<T>
    }

    /**
     * Lanza [task] en otro hilo y devuelve una espera: para solapar dos etapas independientes (p. ej. el análisis de las
     * tablas mientras se renderiza la página). En serie ([threads] = 1 o anidado) se ejecuta al pedir el resultado.
     */
    fun <T> async(task: () -> T): Deferred<T> {
        if (threads <= 1 || nested) return Deferred.lazy(task)
        val d = Deferred<T>()
        val session = PerfTrace.current
        pool.execute { try { d.complete(PerfTrace.within(session) { task() }) } catch (e: Throwable) { d.fail(e) } }
        return d
    }

    class Deferred<T> internal constructor() {
        private val latch = CountDownLatch(1)
        @Volatile private var value: Any? = null
        @Volatile private var error: Throwable? = null
        private var lazyTask: (() -> T)? = null
        internal fun complete(v: T) { value = v; latch.countDown() }
        internal fun fail(e: Throwable) { error = e; latch.countDown() }

        /** Espera el resultado (relanza la excepción de la tarea). */
        @Suppress("UNCHECKED_CAST")
        fun await(): T {
            lazyTask?.let { t -> lazyTask = null; return t().also { complete(it) } }
            latch.await()
            error?.let { throw it }
            return value as T
        }

        /** Espera sin relanzar (para liberar recursos en un finally). */
        fun join() { if (lazyTask == null) latch.await() }

        companion object {
            internal fun <T> lazy(task: () -> T): Deferred<T> = Deferred<T>().also { it.lazyTask = task }
        }
    }
}
