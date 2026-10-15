package com.scannerpromax.ai

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/**
 * Extrae UNA entrada de un ZIP remoto (p. ej. `jni/arm64-v8a/libonnxruntime.so` del AAR oficial de ONNX Runtime en
 * Maven Central) sin bajar el archivo entero: lee el directorio central del final del ZIP con peticiones HTTP Range,
 * baja solo los bytes comprimidos de esa entrada (reanudable, a `<destino>.zpart`) y los descomprime a `<destino>`
 * comprobando tamaño y sha256. Si el servidor no admite Range, recorre el ZIP completo en flujo ([extractFromStream])
 * y se queda solo con la entrada pedida (sin guardar el resto). Java puro: se prueba en la JVM.
 */
class ZipEntryFetcher(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    private val userAgent: String = "EscanerProMax/1.0",
) {
    /** Origen con lectura por rangos (HTTP Range en la app; archivo local en los tests). */
    interface RangeSource {
        /** Tamaño total del ZIP. */
        fun length(): Long
        /** Flujo de los bytes [from, toInclusive]. Lanza [RangeNotSupportedException] si el origen no admite rangos. */
        fun open(from: Long, toInclusive: Long): InputStream
        /** Flujo del ZIP completo (respaldo sin rangos). */
        fun openAll(): InputStream
    }

    class RangeNotSupportedException(message: String) : IOException(message)

    /** Posición de una entrada dentro del ZIP. */
    data class EntryLocation(val name: String, val method: Int, val compressedSize: Long, val size: Long, val dataOffset: Long)

    fun interface Progress {
        /** done/total = bytes comprimidos de la entrada ya bajados / totales. */
        fun onProgress(done: Long, total: Long)
    }

    /** Archivo con los bytes comprimidos a medio bajar. */
    fun partFile(dest: File): File = File(dest.parentFile, dest.name + ".zpart")

    /**
     * Deja en [dest] la entrada [entryName] de [source], verificada ([expectedSize] y [sha256] del contenido
     * descomprimido). Si ya está completa no hace nada. Reanuda lo bajado en `.zpart`.
     */
    fun fetch(
        source: RangeSource,
        entryName: String,
        dest: File,
        expectedSize: Long,
        sha256: String?,
        isCancelled: () -> Boolean = { false },
        progress: Progress = Progress { _, _ -> },
    ): File {
        dest.parentFile?.mkdirs()
        if (dest.isFile && (expectedSize <= 0 || dest.length() == expectedSize)) return dest
        val loc = try {
            locate(source, entryName)
        } catch (e: RangeNotSupportedException) {
            return fetchStreaming(source, entryName, dest, expectedSize, sha256, isCancelled, progress)
        }
        if (expectedSize > 0 && loc.size != expectedSize) {
            throw ResumableDownloader.VerificationException("$entryName mide ${loc.size} bytes en el archivo y se esperaban $expectedSize")
        }
        val part = partFile(dest)
        if (part.isFile && part.length() > loc.compressedSize) part.delete()
        var attemptsLeft = 3
        while (true) {
            if (isCancelled()) throw ResumableDownloader.CancelledException()
            val have = if (part.isFile) part.length() else 0L
            if (have == loc.compressedSize) break
            try {
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(have)
                    raf.seek(have)
                    source.open(loc.dataOffset + have, loc.dataOffset + loc.compressedSize - 1).use { input ->
                        val buf = ByteArray(BUFFER)
                        var done = have
                        var last = 0L
                        progress.onProgress(done, loc.compressedSize)
                        while (done < loc.compressedSize) {
                            if (isCancelled()) throw ResumableDownloader.CancelledException()
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), loc.compressedSize - done).toInt())
                            if (n < 0) throw IOException("conexión cortada a mitad de ${dest.name}")
                            raf.write(buf, 0, n)
                            done += n
                            if (done - last >= 1_000_000 || done == loc.compressedSize) {
                                last = done
                                progress.onProgress(done, loc.compressedSize)
                            }
                        }
                    }
                    raf.fd.sync()
                }
            } catch (e: ResumableDownloader.CancelledException) {
                throw e
            } catch (e: RangeNotSupportedException) {
                part.delete()
                return fetchStreaming(source, entryName, dest, expectedSize, sha256, isCancelled, progress)
            } catch (e: IOException) {
                if (--attemptsLeft <= 0) throw e
                Thread.sleep(1500)
            }
        }
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            part.inputStream().buffered(BUFFER).use { raw ->
                val input = when (loc.method) {
                    METHOD_STORED -> raw
                    METHOD_DEFLATED -> InflaterInputStream(raw, Inflater(true), BUFFER)
                    else -> throw IOException("método de compresión ${loc.method} no admitido")
                }
                tmp.outputStream().use { out -> copyVerified(input, out, expectedSize, sha256, entryName, isCancelled) }
            }
        } catch (e: ResumableDownloader.VerificationException) {
            tmp.delete(); part.delete()
            throw e
        } catch (e: java.util.zip.ZipException) {
            tmp.delete(); part.delete()
            throw ResumableDownloader.VerificationException("$entryName llegó dañado: ${e.message}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        commit(tmp, dest)
        part.delete()
        return dest
    }

    /** Respaldo sin Range: recorre el ZIP completo en flujo y guarda solo [entryName]. No es reanudable. */
    fun fetchStreaming(
        source: RangeSource,
        entryName: String,
        dest: File,
        expectedSize: Long,
        sha256: String?,
        isCancelled: () -> Boolean = { false },
        progress: Progress = Progress { _, _ -> },
    ): File {
        val total = runCatching { source.length() }.getOrDefault(-1L)
        source.openAll().use { extractFromStream(it, entryName, dest, expectedSize, sha256, isCancelled) { d -> progress.onProgress(d, total) } }
        return dest
    }

    /** Extrae [entryName] de un ZIP leído en flujo a [dest] (verificado). [onRead] recibe los bytes del ZIP leídos. */
    fun extractFromStream(
        zipStream: InputStream,
        entryName: String,
        dest: File,
        expectedSize: Long,
        sha256: String?,
        isCancelled: () -> Boolean = { false },
        onRead: (Long) -> Unit = {},
    ) {
        dest.parentFile?.mkdirs()
        val counting = CountingInputStream(zipStream, onRead)
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        ZipInputStream(counting.buffered(BUFFER)).use { zis ->
            while (true) {
                if (isCancelled()) throw ResumableDownloader.CancelledException()
                val e = zis.nextEntry ?: break
                if (e.name != entryName) continue
                try {
                    tmp.outputStream().use { out -> copyVerified(zis, out, expectedSize, sha256, entryName, isCancelled) }
                } catch (t: Throwable) {
                    tmp.delete(); throw t
                }
                commit(tmp, dest)
                return
            }
        }
        throw IOException("$entryName no está en el archivo descargado")
    }

    /** Busca [entryName] en el directorio central del ZIP (peticiones por rango: final del archivo + cabecera local). */
    fun locate(source: RangeSource, entryName: String): EntryLocation {
        val len = source.length()
        if (len < 22) throw IOException("archivo ZIP demasiado corto")
        // Fin del directorio central (EOCD): 22 bytes + comentario de hasta 65535.
        val tailLen = minOf(len, 22L + 65_535L)
        val tail = readRange(source, len - tailLen, tailLen.toInt())
        var eocd = -1
        for (i in tail.size - 22 downTo 0) {
            if (le32(tail, i) == 0x06054b50L) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("no es un ZIP válido (sin directorio central)")
        val cdSize = le32(tail, eocd + 12)
        val cdOffset = le32(tail, eocd + 16)
        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) throw IOException("ZIP64 no admitido")
        val cdInTail = cdOffset - (len - tailLen)
        val cd = if (cdInTail >= 0 && cdInTail + cdSize <= tail.size) {
            tail.copyOfRange(cdInTail.toInt(), (cdInTail + cdSize).toInt())
        } else {
            readRange(source, cdOffset, cdSize.toInt())
        }
        var p = 0
        while (p + 46 <= cd.size) {
            if (le32(cd, p) != 0x02014b50L) throw IOException("directorio central dañado")
            val method = le16(cd, p + 10)
            val comp = le32(cd, p + 20)
            val size = le32(cd, p + 24)
            val nameLen = le16(cd, p + 28)
            val extraLen = le16(cd, p + 30)
            val commentLen = le16(cd, p + 32)
            val localOff = le32(cd, p + 42)
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            if (name == entryName) {
                if (comp == 0xFFFFFFFFL || size == 0xFFFFFFFFL || localOff == 0xFFFFFFFFL) throw IOException("ZIP64 no admitido")
                val lh = readRange(source, localOff, 30)
                if (le32(lh, 0) != 0x04034b50L) throw IOException("cabecera local de $entryName dañada")
                val dataOffset = localOff + 30 + le16(lh, 26) + le16(lh, 28)
                if (dataOffset + comp > len) throw IOException("entrada $entryName fuera del archivo")
                return EntryLocation(name, method, comp, size, dataOffset)
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        throw IOException("$entryName no está en el archivo")
    }

    /** Origen HTTP (sigue redirecciones; exige 206 a las peticiones Range). */
    fun http(url: String): RangeSource = HttpRangeSource(url)

    private inner class HttpRangeSource(private val url: String) : RangeSource {
        @Volatile private var cachedLength = -1L

        override fun length(): Long {
            if (cachedLength >= 0) return cachedLength
            // Petición de 1 byte: Content-Range da el total y confirma que hay soporte de Range.
            val c = connect(url, "bytes=0-0")
            try {
                val code = c.responseCode
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    val total = ResumableDownloader.parseContentRangeTotal(c.getHeaderField("Content-Range"))
                        ?: throw RangeNotSupportedException("respuesta sin Content-Range")
                    cachedLength = total
                    return total
                }
                if (code == HttpURLConnection.HTTP_OK) {
                    c.getHeaderField("Content-Length")?.toLongOrNull()?.let { cachedLength = it }
                    throw RangeNotSupportedException("el servidor no admite descargas parciales")
                }
                throw IOException("HTTP $code al consultar $url")
            } finally {
                c.disconnect()
            }
        }

        override fun open(from: Long, toInclusive: Long): InputStream {
            val c = connect(url, "bytes=$from-$toInclusive")
            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                c.disconnect(); throw RangeNotSupportedException("el servidor no admite descargas parciales")
            }
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                c.disconnect(); throw IOException("HTTP $code al descargar")
            }
            val start = ResumableDownloader.parseContentRangeStart(c.getHeaderField("Content-Range"))
            if (start != from) {
                c.disconnect(); throw IOException("Content-Range inesperado: ${c.getHeaderField("Content-Range")}")
            }
            return object : FilterInputStream(c.inputStream) {
                override fun close() { try { super.close() } finally { c.disconnect() } }
            }
        }

        override fun openAll(): InputStream {
            val c = connect(url, null)
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                c.disconnect(); throw IOException("HTTP $code al descargar")
            }
            return object : FilterInputStream(c.inputStream) {
                override fun close() { try { super.close() } finally { c.disconnect() } }
            }
        }
    }

    private fun connect(url: String, range: String?): HttpURLConnection {
        var current = URL(url)
        repeat(8) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) c.setRequestProperty("Range", range)
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

    private class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var last = 0L
        private fun add(n: Long) {
            if (n <= 0) return
            count += n
            if (count - last >= 1_000_000) { last = count; onRead(count) }
        }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { add(it) }
    }

    companion object {
        private const val BUFFER = 256 * 1024
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, i: Int): Long = (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)

        private fun readRange(source: RangeSource, from: Long, len: Int): ByteArray {
            val out = ByteArray(len)
            source.open(from, from + len - 1).use { input ->
                var off = 0
                while (off < len) {
                    val n = input.read(out, off, len - off)
                    if (n < 0) throw IOException("respuesta incompleta del servidor")
                    off += n
                }
            }
            return out
        }

        private fun copyVerified(
            input: InputStream, out: OutputStream, expectedSize: Long, sha256: String?, name: String, isCancelled: () -> Boolean,
        ) {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                if (isCancelled()) throw ResumableDownloader.CancelledException()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                md.update(buf, 0, n)
                total += n
            }
            out.flush()
            if (expectedSize > 0 && total != expectedSize) {
                throw ResumableDownloader.VerificationException("$name: $total bytes en vez de $expectedSize")
            }
            if (!sha256.isNullOrBlank()) {
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(sha256, ignoreCase = true)) {
                    throw ResumableDownloader.VerificationException("$name llegó dañado (sha256 no coincide)")
                }
            }
        }

        private fun commit(tmp: File, dest: File) {
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("no se pudo guardar ${dest.name}")
        }
    }
}
