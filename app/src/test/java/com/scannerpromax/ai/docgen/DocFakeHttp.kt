package com.scannerpromax.ai.docgen

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Servidor HTTP/1.1 local mínimo para pruebas (sockets, como ResumableDownloaderTest: el classpath de Android no
 * expone com.sun.net.httpserver). Responde en orden las respuestas encoladas y guarda las peticiones.
 */
class DocFakeHttp : AutoCloseable {
    class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
    class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val replies = ConcurrentLinkedQueue<Reply>()
    val requests: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    @Volatile private var open = true

    init {
        thread(isDaemon = true, name = "FakeHttp") {
            while (open) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun handle(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        while (true) {
            val requestLine = readLine(input) ?: return
            if (requestLine.isEmpty()) continue
            val (method, target) = requestLine.split(" ").let { it[0] to it[1] }
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val l = readLine(input) ?: return
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(len)
            var read = 0
            while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
            requests += Seen(method, target.substringBefore('?'), headers, String(body, Charsets.UTF_8))
            val r = replies.poll() ?: Reply(500, """{"error":{"message":"sin respuesta preparada"}}""")
            val bytes = r.body.toByteArray()
            val sb = StringBuilder("HTTP/1.1 ${r.status} X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            r.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            val out = s.getOutputStream()
            out.write(sb.toString().toByteArray())
            out.write(bytes)
            out.flush()
            return
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (c == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            buf.write(c)
        }
    }

    val base: String get() = "http://127.0.0.1:${server.localPort}"

    fun enqueue(status: Int, body: String, headers: Map<String, String> = emptyMap()) = replies.add(Reply(status, body, headers))

    override fun close() { open = false; runCatching { server.close() } }
}
