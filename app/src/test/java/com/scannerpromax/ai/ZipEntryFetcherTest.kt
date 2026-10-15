package com.scannerpromax.ai

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Extracción de una entrada de un AAR (zip de prueba con la misma estructura jni/ABI/libX.so) por rangos o en flujo. */
class ZipEntryFetcherTest {
    private lateinit var dir: File
    private lateinit var zip: ByteArray
    private val fetcher = ZipEntryFetcher()

    // "librería" medio compresible (como un .so real) y otras entradas alrededor.
    private val arm64 = ByteArray(2_000_000).also { b ->
        val r = Random(7)
        for (i in b.indices) b[i] = if (i % 3 == 0) r.nextInt(256).toByte() else (i / 1000).toByte()
    }
    private val v7a = Random(8).nextBytes(700_000)
    private val stored = Random(9).nextBytes(50_000)
    private val big = Random(10).nextBytes(3_000_000)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        dir = Files.createTempDirectory("zipfetch").toFile()
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            put("AndroidManifest.xml", "<manifest/>".toByteArray())
            put("classes.jar", big)
            put("jni/arm64-v8a/libonnxruntime.so", arm64)
            put("jni/arm64-v8a/libonnxruntime4j_jni.so", ByteArray(1000) { 1 })
            put("jni/armeabi-v7a/libonnxruntime.so", v7a)
            val e = ZipEntry("jni/x86_64/libonnxruntime.so").apply {
                method = ZipEntry.STORED; size = stored.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(stored) }.value
            }
            z.putNextEntry(e); z.write(stored); z.closeEntry()
            z.setComment("comentario de prueba")
        }
        zip = bos.toByteArray()
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** Origen en memoria que cuenta los bytes servidos; [ranges]=false simula un servidor sin Range. */
    private inner class MemSource(val ranges: Boolean = true, val failAfter: Long = Long.MAX_VALUE) : ZipEntryFetcher.RangeSource {
        val served = AtomicLong()
        override fun length() = zip.size.toLong()
        override fun open(from: Long, toInclusive: Long): InputStream {
            if (!ranges) throw ZipEntryFetcher.RangeNotSupportedException("sin rangos")
            val bytes = zip.copyOfRange(from.toInt(), (toInclusive + 1).toInt())
            return object : InputStream() {
                var i = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (i >= bytes.size) return -1
                    if (served.get() >= failAfter) throw IOException("corte de red simulado")
                    val n = minOf(len, bytes.size - i, 64 * 1024)
                    System.arraycopy(bytes, i, b, off, n); i += n; served.addAndGet(n.toLong())
                    return n
                }
            }
        }
        override fun openAll(): InputStream { served.addAndGet(zip.size.toLong()); return zip.inputStream() }
    }

    @Test fun extraeSoloLaEntradaDeLaAbiPorRangos() {
        val src = MemSource()
        val dest = File(dir, "lib/arm64-v8a/libonnxruntime.so")
        fetcher.fetch(src, "jni/arm64-v8a/libonnxruntime.so", dest, arm64.size.toLong(), sha(arm64))
        assertArrayEquals(arm64, dest.readBytes())
        assertFalse(fetcher.partFile(dest).exists())
        assertFalse(File(dest.parentFile, dest.name + ".tmp").exists())
        // Solo baja lo comprimido de esa entrada + el final del ZIP: nada de classes.jar ni de las otras ABIs.
        val loc = fetcher.locate(MemSource(), "jni/arm64-v8a/libonnxruntime.so")
        assertTrue(loc.compressedSize < arm64.size)
        assertTrue("servidos ${src.served.get()} de ${zip.size}", src.served.get() < loc.compressedSize + 70_000)
        assertTrue(src.served.get() < zip.size / 2)
        // Ya completa: no vuelve a bajar nada.
        val src2 = MemSource()
        fetcher.fetch(src2, "jni/arm64-v8a/libonnxruntime.so", dest, arm64.size.toLong(), sha(arm64))
        assertEquals(0, src2.served.get())
    }

    @Test fun entradaSinComprimir() {
        val dest = File(dir, "x86_64/libonnxruntime.so")
        fetcher.fetch(MemSource(), "jni/x86_64/libonnxruntime.so", dest, stored.size.toLong(), sha(stored))
        assertArrayEquals(stored, dest.readBytes())
    }

    @Test fun reanudaTrasCorteDeRed() {
        val dest = File(dir, "v7a/libonnxruntime.so")
        val loc = fetcher.locate(MemSource(), "jni/armeabi-v7a/libonnxruntime.so")
        // 1er intento: la red se corta tras ~300 KB (y en los reintentos internos también) → queda .zpart.
        val cut = MemSource(failAfter = 300_000)
        try {
            fetcher.fetch(cut, "jni/armeabi-v7a/libonnxruntime.so", dest, v7a.size.toLong(), sha(v7a))
            fail("debía fallar")
        } catch (e: IOException) { /* esperado */ }
        val part = fetcher.partFile(dest)
        assertTrue(part.isFile && part.length() in 1 until loc.compressedSize)
        val had = part.length()
        // 2º intento: solo pide lo que falta.
        val src = MemSource()
        fetcher.fetch(src, "jni/armeabi-v7a/libonnxruntime.so", dest, v7a.size.toLong(), sha(v7a))
        assertArrayEquals(v7a, dest.readBytes())
        assertTrue(src.served.get() <= loc.compressedSize - had + 70_000)
    }

    @Test fun shaIncorrectoNoDejaArchivo() {
        val dest = File(dir, "a/libonnxruntime.so")
        try {
            fetcher.fetch(MemSource(), "jni/arm64-v8a/libonnxruntime.so", dest, arm64.size.toLong(), "0".repeat(64))
            fail("debía fallar")
        } catch (e: ResumableDownloader.VerificationException) { /* esperado */ }
        assertFalse(dest.exists())
        assertFalse(fetcher.partFile(dest).exists())
        // Tamaño esperado distinto al del ZIP: se rechaza antes de bajar.
        try {
            fetcher.fetch(MemSource(), "jni/arm64-v8a/libonnxruntime.so", dest, 123L, null)
            fail("debía fallar")
        } catch (e: ResumableDownloader.VerificationException) { /* esperado */ }
    }

    @Test fun entradaInexistente() {
        try {
            fetcher.fetch(MemSource(), "jni/riscv64/libonnxruntime.so", File(dir, "r.so"), 10, null)
            fail("debía fallar")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("riscv64"))
        }
    }

    @Test fun sinRangosRecorreElZipEnFlujo() {
        val src = MemSource(ranges = false)
        val dest = File(dir, "s/libonnxruntime.so")
        fetcher.fetch(src, "jni/armeabi-v7a/libonnxruntime.so", dest, v7a.size.toLong(), sha(v7a))
        assertArrayEquals(v7a, dest.readBytes())
        // El AAR completo no se guarda en disco: solo la .so.
        assertEquals(listOf("libonnxruntime.so"), dest.parentFile!!.list()!!.toList())
    }

    /**
     * Contra el AAR REAL de Maven Central (solo con -PortReal=DIR; necesita red): baja por rango la .so de cada ABI
     * del catálogo y comprueba tamaño y sha256 del catálogo (y que se baja ~lo comprimido, no los 55 MB del AAR).
     */
    @Test fun aarRealDeMavenCentral() {
        val out = System.getProperty("ort.real")
        org.junit.Assume.assumeTrue("sin -PortReal", !out.isNullOrBlank())
        val pkg = ModelCatalog.ORT_NATIVE
        for (lib in pkg.libs) {
            val dest = File(out, "lib/${lib.abi}/${lib.fileName}")
            dest.delete()
            val t0 = System.nanoTime()
            var last = 0L
            fetcher.fetch(fetcher.http(pkg.url), lib.entryName, dest, lib.sizeBytes, lib.sha256) { d, _ -> last = d }
            assertEquals(lib.sizeBytes, dest.length())
            assertEquals(lib.compressedBytes, last)
            println("ORT real ${lib.abi}: ${dest.length()} B (bajados $last B comprimidos) en ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        assertEquals(pkg.archiveSize, fetcher.http(pkg.url).length())
    }

    @Test fun porHttpConRangosYSinRangos() {
        var supportRange = true
        val ranges = mutableListOf<String>()
        val server = MiniServer { path, range, out ->
            if (path != "/ort.aar") {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@MiniServer
            }
            val m = range?.let { Regex("bytes=(\\d+)-(\\d+)").find(it) }
            if (supportRange && m != null) {
                synchronized(ranges) { ranges += range }
                val a = m.groupValues[1].toInt(); val b = minOf(m.groupValues[2].toInt(), zip.size - 1)
                out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $a-$b/${zip.size}\r\n" +
                    "Content-Length: ${b - a + 1}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(zip, a, b - a + 1)
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${zip.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(zip)
            }
        }
        server.use {
            val url = "http://127.0.0.1:${server.port}/ort.aar"
            val dest = File(dir, "h/libonnxruntime.so")
            fetcher.fetch(fetcher.http(url), "jni/arm64-v8a/libonnxruntime.so", dest, arm64.size.toLong(), sha(arm64))
            assertArrayEquals(arm64, dest.readBytes())
            assertTrue(ranges.size in 3..6)  // tamaño, final del ZIP, cabecera local, datos
            supportRange = false
            val dest2 = File(dir, "h2/libonnxruntime.so")
            fetcher.fetch(fetcher.http(url), "jni/armeabi-v7a/libonnxruntime.so", dest2, v7a.size.toLong(), sha(v7a))
            assertArrayEquals(v7a, dest2.readBytes())
        }
    }
}
