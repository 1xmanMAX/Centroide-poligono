package com.scannerpromax.ai

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Servidor HTTP local con soporte de Range (o sin él) y redirección, como Hugging Face → CDN. */
class ResumableDownloaderTest {
    private lateinit var server: MiniServer
    private lateinit var dir: File
    private val data = Random(42).nextBytes(3_000_000)
    @Volatile private var supportRange = true
    private val requests = AtomicInteger()
    private val rangeStarts: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    private val sha: String get() = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val base get() = "http://127.0.0.1:${server.port}"

    @Before fun setUp() {
        dir = Files.createTempDirectory("dl").toFile()
        server = MiniServer { path, range, out ->
            when (path) {
                "/redirect" -> out.write("HTTP/1.1 302 Found\r\nLocation: /file\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                "/file" -> {
                    requests.incrementAndGet()
                    var start = 0
                    if (supportRange && range != null) {
                        start = Regex("bytes=(\\d+)-").find(range)!!.groupValues[1].toInt()
                        rangeStarts += start.toLong()
                        if (start >= data.size) {
                            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@MiniServer
                        }
                        out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${data.size - 1}/${data.size}\r\n" +
                            "Content-Length: ${data.size - start}\r\nConnection: close\r\n\r\n").toByteArray())
                    } else {
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    out.write(data, start, data.size - start)
                }
                else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
        }
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    @Test fun descargaCompletaConRedireccionYSha() {
        val dest = File(dir, "m.gguf")
        var last = 0L
        ResumableDownloader().download("$base/redirect", dest, data.size.toLong(), sha) { d, _ -> last = d }
        assertArrayEquals(data, dest.readBytes())
        assertEquals(data.size.toLong(), last)
        assertFalse(File(dir, "m.gguf.part").exists())
    }

    @Test fun reanudaDesdeElParcial() {
        val dest = File(dir, "m.gguf")
        File(dir, "m.gguf.part").writeBytes(data.copyOfRange(0, 1_000_000))
        ResumableDownloader().download("$base/file", dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
        assertEquals(listOf(1_000_000L), rangeStarts.toList())
    }

    @Test fun sinSoporteDeRangeEmpiezaDeCero() {
        supportRange = false
        val dest = File(dir, "m.gguf")
        File(dir, "m.gguf.part").writeBytes(ByteArray(500_000) { 7 })
        ResumableDownloader().download("$base/file", dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun cancelarConservaElParcialYLuegoReanuda() {
        val dest = File(dir, "m.gguf")
        var seen = 0L
        try {
            ResumableDownloader(bufferSize = 64 * 1024).download(
                "$base/file", dest, data.size.toLong(), sha,
                isCancelled = { seen > 1_000_000 },
            ) { d, _ -> seen = d }
            fail("debía cancelarse")
        } catch (_: ResumableDownloader.CancelledException) {
        }
        val part = File(dir, "m.gguf.part")
        assertTrue(part.exists() && part.length() in 1_000_000L until data.size.toLong())
        assertFalse(dest.exists())
        ResumableDownloader().download("$base/file", dest, data.size.toLong(), sha)
        assertArrayEquals(data, dest.readBytes())
        assertTrue(rangeStarts.last() >= 1_000_000L)
    }

    @Test fun shaIncorrectoBorraYFalla() {
        val dest = File(dir, "m.gguf")
        try {
            ResumableDownloader().download("$base/file", dest, data.size.toLong(), "0".repeat(64))
            fail("debía fallar")
        } catch (_: ResumableDownloader.VerificationException) {
        }
        assertFalse(dest.exists())
        assertFalse(File(dir, "m.gguf.part").exists())
    }

    @Test fun tamanoIncorrectoFalla() {
        val dest = File(dir, "m.gguf")
        try {
            ResumableDownloader().download("$base/file", dest, data.size.toLong() + 10, null)
            fail("debía fallar")
        } catch (_: ResumableDownloader.VerificationException) {
        }
        assertFalse(dest.exists())
    }

    @Test fun yaDescargadoNoPideNada() {
        val dest = File(dir, "m.gguf")
        dest.writeBytes(data)
        ResumableDownloader().download("$base/file", dest, data.size.toLong(), sha)
        assertEquals(0, requests.get())
    }

    @Test fun parseoDeContentRange() {
        assertEquals(100L, ResumableDownloader.parseContentRangeStart("bytes 100-199/1000"))
        assertEquals(1000L, ResumableDownloader.parseContentRangeTotal("bytes 100-199/1000"))
        assertEquals(null, ResumableDownloader.parseContentRangeTotal("bytes 100-199/*"))
        assertEquals(null, ResumableDownloader.parseContentRangeStart(null))
    }
}

/** Servidor HTTP/1.1 mínimo (una petición por conexión) para las pruebas: la JVM de Android no trae com.sun.net. */
class MiniServer(private val handler: (path: String, range: String?, out: OutputStream) -> Unit) : AutoCloseable {
    private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    private val thread = Thread {
        while (!socket.isClosed) {
            val c = try { socket.accept() } catch (_: Exception) { break }
            Thread {
                c.use { conn ->
                    try {
                        val input = conn.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val first = input.readLine() ?: return@use
                        var range: String? = null
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(":").trim()
                        }
                        val path = first.split(" ").getOrElse(1) { "/" }
                        val out = conn.getOutputStream().buffered()
                        handler(path, range, out)
                        out.flush()
                    } catch (_: Exception) {
                        // el cliente cortó (cancelación)
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        socket.close()
    }
}
