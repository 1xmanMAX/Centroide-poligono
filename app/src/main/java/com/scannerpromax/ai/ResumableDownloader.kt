package com.scannerpromax.ai

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Descarga reanudable de un archivo (HTTP Range) a `<destino>.part`, con verificación de tamaño y sha256 y
 * renombrado atómico al terminar. Java puro (sin Android): se prueba en la JVM con un servidor local.
 *
 * - Si existe `.part`, pide `Range: bytes=<n>-`; si el servidor responde 200 (sin soporte de Range) empieza de cero.
 * - Sigue redirecciones (Hugging Face redirige al CDN), también entre http/https.
 * - [isCancelled] se consulta en cada bloque: cancelar deja el `.part` para reanudar más tarde.
 */
class ResumableDownloader(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    private val userAgent: String = "EscanerProMax/1.0",
    private val bufferSize: Int = 256 * 1024,
) {

    class CancelledException : IOException("descarga cancelada")
    class VerificationException(message: String) : IOException(message)

    fun interface Progress {
        /** downloaded = bytes del archivo ya en disco (incluido lo reanudado); total = tamaño total o -1. */
        fun onProgress(downloaded: Long, total: Long)
    }

    /** Archivo parcial asociado a [dest]. */
    fun partFile(dest: File): File = File(dest.parentFile, dest.name + ".part")

    /**
     * Descarga [url] en [dest]. Si [dest] ya existe con el tamaño esperado (y sha256 si se da) no hace nada.
     * Lanza [CancelledException], [VerificationException] o IOException.
     */
    fun download(
        url: String,
        dest: File,
        expectedSize: Long = -1,
        sha256: String? = null,
        isCancelled: () -> Boolean = { false },
        progress: Progress = Progress { _, _ -> },
    ): File {
        dest.parentFile?.mkdirs()
        if (dest.exists() && (expectedSize <= 0 || dest.length() == expectedSize)) {
            progress.onProgress(dest.length(), dest.length())
            return dest
        }
        val part = partFile(dest)
        if (expectedSize > 0 && part.exists() && part.length() > expectedSize) part.delete()

        var attemptsLeft = 3
        while (true) {
            if (isCancelled()) throw CancelledException()
            if (expectedSize > 0 && part.exists() && part.length() == expectedSize) break
            try {
                transfer(url, part, expectedSize, isCancelled, progress)
                break
            } catch (e: CancelledException) {
                throw e
            } catch (e: VerificationException) {
                throw e
            } catch (e: IOException) {
                // Cortes de red: reintenta unas pocas veces reanudando desde lo que haya en disco.
                if (--attemptsLeft <= 0) throw e
                Thread.sleep(1500)
            }
        }

        if (expectedSize > 0 && part.length() != expectedSize) {
            val got = part.length()
            if (got > expectedSize) part.delete()
            throw VerificationException("tamaño incorrecto: $got bytes en vez de $expectedSize")
        }
        if (!sha256.isNullOrBlank()) {
            val actual = sha256Of(part, isCancelled)
            if (!actual.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw VerificationException("el archivo descargado está dañado (sha256 no coincide)")
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("no se pudo renombrar ${part.name}")
        return dest
    }

    private fun transfer(
        url: String,
        part: File,
        expectedSize: Long,
        isCancelled: () -> Boolean,
        progress: Progress,
    ) {
        var start = if (part.exists()) part.length() else 0L
        val conn = open(url, start)
        try {
            val code = conn.responseCode
            when {
                code == 416 -> {
                    // Rango no satisfacible: o ya está completo, o el parcial no vale.
                    if (expectedSize > 0 && start == expectedSize) return
                    part.delete()
                    throw IOException("el servidor rechazó la reanudación (416)")
                }
                code == HttpURLConnection.HTTP_PARTIAL -> {
                    val cr = conn.getHeaderField("Content-Range")
                    val crStart = parseContentRangeStart(cr)
                    if (crStart != null && crStart != start) {
                        throw IOException("Content-Range inesperado: $cr")
                    }
                }
                code == HttpURLConnection.HTTP_OK -> {
                    // Sin soporte de Range (o archivo nuevo): se empieza de cero.
                    start = 0L
                }
                else -> throw IOException("HTTP $code al descargar")
            }
            val bodyLen = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = when {
                expectedSize > 0 -> expectedSize
                bodyLen >= 0 -> start + bodyLen
                else -> parseContentRangeTotal(conn.getHeaderField("Content-Range")) ?: -1L
            }
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(start)
                raf.seek(start)
                conn.inputStream.use { input -> copy(input, raf, start, total, isCancelled, progress) }
                raf.fd.sync()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun copy(
        input: InputStream,
        raf: RandomAccessFile,
        start: Long,
        total: Long,
        isCancelled: () -> Boolean,
        progress: Progress,
    ) {
        val buf = ByteArray(bufferSize)
        var done = start
        var lastReport = 0L
        progress.onProgress(done, total)
        while (true) {
            if (isCancelled()) throw CancelledException()
            val n = input.read(buf)
            if (n < 0) break
            raf.write(buf, 0, n)
            done += n
            if (done - lastReport >= 1_000_000 || (total > 0 && done >= total)) {
                lastReport = done
                progress.onProgress(done, total)
            }
        }
        progress.onProgress(done, total)
    }

    private fun open(url: String, start: Long): HttpURLConnection {
        var current = URL(url)
        repeat(8) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept-Encoding", "identity")
            if (start > 0) c.setRequestProperty("Range", "bytes=$start-")
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirección sin Location")
                c.disconnect()
                current = URL(current, loc)
            } else {
                return c
            }
        }
        throw IOException("demasiadas redirecciones")
    }

    companion object {
        fun parseContentRangeStart(header: String?): Long? {
            // "bytes 100-199/1000"
            val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(header ?: return null) ?: return null
            return m.groupValues[1].toLongOrNull()
        }

        fun parseContentRangeTotal(header: String?): Long? {
            val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(header ?: return null) ?: return null
            return m.groupValues[3].toLongOrNull()
        }

        fun sha256Of(file: File, isCancelled: () -> Boolean = { false }): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 20).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
