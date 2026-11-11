package com.scannerpromax.imaging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Reparto en paralelo ([Par]): orden, estado por hilo, anidado, errores y tareas solapadas. */
class ParTest {

    private val saved = Par.threads

    @After fun restore() { Par.threads = saved }

    @Test fun mapConservaElOrden() {
        Par.threads = 4
        val r = Par.map(100) { i -> Thread.sleep((i % 3).toLong()); i * i }
        assertEquals((0 until 100).map { it * it }, r)
    }

    @Test fun usaVariosHilos() {
        Par.threads = 4
        val names = ConcurrentHashMap.newKeySet<String>()
        Par.forEach(16) { _ -> names.add(Thread.currentThread().name); Thread.sleep(20) }
        assertTrue("hilos usados: $names", names.size >= 2)
    }

    @Test fun estadoPorHiloYCierre() {
        Par.threads = 3
        val created = AtomicInteger(); val closed = AtomicInteger()
        val sums = Par.map(30, { created.incrementAndGet(); IntArray(1) }, { closed.incrementAndGet() }) { st, i -> st[0] += i; i }
        assertEquals((0 until 30).toList(), sums)
        assertEquals(created.get(), closed.get())
        assertTrue(created.get() in 1..3)
    }

    @Test fun enSerieConUnHilo() {
        Par.threads = 1
        val me = Thread.currentThread()
        Par.forEach(10) { _ -> assertTrue(Thread.currentThread() === me) }
    }

    @Test fun anidadoEnSerieSinBloqueo() {
        Par.threads = 4
        val total = AtomicInteger()
        Par.forEach(8) { _ -> Par.forEach(8) { _ -> total.incrementAndGet() } }
        assertEquals(64, total.get())
    }

    @Test fun relanzaLaExcepcion() {
        Par.threads = 4
        try {
            Par.forEach(50) { i -> if (i == 17) throw IllegalStateException("x$i") }
            fail("sin excepción")
        } catch (e: IllegalStateException) {
            assertEquals("x17", e.message)
        }
    }

    @Test fun asyncSolapaYDevuelve() {
        Par.threads = 4
        val d = Par.async { Thread.sleep(50); 42 }
        assertEquals(42, d.await())
        Par.threads = 1
        var ran = false
        val lazy = Par.async { ran = true; 7 }
        assertEquals(false, ran)
        assertEquals(7, lazy.await())
    }

    @Test fun asyncPuedeRepartirSusBucles() {
        Par.threads = 4
        val names = ConcurrentHashMap.newKeySet<String>()
        Par.async { Par.forEach(16) { _ -> names.add(Thread.currentThread().name); Thread.sleep(10) } }.await()
        assertTrue("hilos usados dentro de async: $names", names.size >= 2)
    }

    @Test fun propagaLaSesionDeTiempos() {
        Par.threads = 4
        val s = PerfTrace.begin()
        try {
            Par.forEach(8) { _ -> PerfTrace.stage("x") { Thread.sleep(2) } }
            assertTrue((s.stages["x"] ?: 0L) > 0L)
        } finally { PerfTrace.end(s) }
        assertTrue(PerfTrace.last.containsKey("x"))
        assertTrue(PerfTrace.summary().contains("x"))
    }
}
