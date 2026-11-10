package com.scannerpromax.album

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Persistencia de los álbumes: `<root>/<id>/album.json` (escritura atómica: temporal + rename) y los archivos que
 * nombran los modelos en `bg/` (fondos), `src/` (fotos originales de los cromos) y `stk/` (cromos rectificados).
 * Independiente de Android (sólo java.io) para probarla en la JVM; la importación de imágenes y PDF está en
 * [AlbumImport].
 *
 * Los archivos que el álbum ya no nombra se borran con [compact] (al salir del editor y al arrancar): mientras se
 * edita, deshacer puede volver a necesitar un cromo anterior.
 */
class AlbumStore(private val root: File, startScope: CoroutineScope? = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {

    private val mutex = Mutex()
    private val albums = LinkedHashMap<String, Album>()
    private val _all = MutableStateFlow<List<Album>>(emptyList())
    private val loaded = CompletableDeferred<Unit>()

    init {
        if (startScope == null) {
            loadAllBlocking()
        } else {
            startScope.launch {
                try {
                    loadAllBlocking()
                    albums.keys.toList().forEach { runCatching { compact(it) } }
                } finally {
                    loaded.complete(Unit)
                }
            }
        }
    }

    /** Álbumes, el más reciente primero. */
    val all: StateFlow<List<Album>> get() = _all.asStateFlow()

    fun observe(id: String): Flow<Album?> = _all.map { l -> l.firstOrNull { it.id == id } }.distinctUntilChanged()

    suspend fun get(id: String): Album? {
        loaded.await()
        return mutex.withLock { albums[id] }
    }

    fun dir(id: String): File = File(root, id)

    fun file(id: String, rel: String): File = File(dir(id), rel)

    suspend fun create(title: String, firstPage: AlbumPage? = null): Album {
        loaded.await()
        val now = System.currentTimeMillis()
        val album = Album(newId(), title.trim().ifEmpty { "Álbum" }, now, now, listOfNotNull(firstPage))
        mutex.withLock {
            withContext(Dispatchers.IO) {
                SUBDIRS.forEach { File(dir(album.id), it).mkdirs() }
                write(album)
            }
            albums[album.id] = album
            publish()
        }
        return album
    }

    /** Cambia el álbum y lo guarda (no hace nada si ya no existe). Devuelve el álbum guardado. */
    suspend fun update(id: String, transform: (Album) -> Album): Album? {
        loaded.await()
        return mutex.withLock {
            val cur = albums[id] ?: return@withLock null
            val next = transform(cur)
            if (next == cur) return@withLock cur
            val saved = next.copy(id = cur.id, updatedAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { write(saved) }
            albums[id] = saved
            publish()
            saved
        }
    }

    suspend fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim().ifEmpty { it.title }) }

    suspend fun delete(id: String) {
        loaded.await()
        mutex.withLock {
            albums.remove(id)
            publish()
            withContext(Dispatchers.IO) { dir(id).deleteRecursively() }
        }
    }

    suspend fun duplicate(id: String): Album? {
        loaded.await()
        return mutex.withLock {
            val cur = albums[id] ?: return@withLock null
            val now = System.currentTimeMillis()
            val copy = cur.copy(id = newId(), title = cur.title + " (copia)", createdAt = now, updatedAt = now)
            withContext(Dispatchers.IO) {
                dir(cur.id).copyRecursively(dir(copy.id), overwrite = true)
                write(copy)
            }
            albums[copy.id] = copy
            publish()
            copy
        }
    }

    /** Archivo nuevo (aún no existe) en la subcarpeta [sub] del álbum: `bg`, `src` o `stk`. */
    fun newFile(id: String, sub: String, prefix: String, ext: String): Pair<File, String> {
        require(sub in SUBDIRS)
        val name = "$sub/${prefix}_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.$ext"
        val f = File(dir(id), name)
        f.parentFile?.mkdirs()
        return f to name
    }

    /** Borra los archivos de `bg/`, `src/` y `stk/` que el álbum ya no nombra. Devuelve cuántos borró. */
    suspend fun compact(id: String): Int {
        val album = mutex.withLock { albums[id] } ?: return 0
        val used = referencedFiles(album)
        return withContext(Dispatchers.IO) {
            var n = 0
            for (sub in SUBDIRS) {
                File(dir(id), sub).listFiles()?.forEach { f ->
                    // margen de 10 min: archivos recién creados que aún no están en el álbum (captura en curso)
                    if ("$sub/${f.name}" !in used && System.currentTimeMillis() - f.lastModified() > 10 * 60_000L) {
                        if (f.delete()) n++
                    }
                }
            }
            n
        }
    }

    private fun publish() {
        _all.value = albums.values.sortedByDescending { it.updatedAt }
    }

    private fun write(album: Album) {
        val d = dir(album.id)
        d.mkdirs()
        val target = File(d, ALBUM_JSON)
        val tmp = File(d, "$ALBUM_JSON.tmp")
        tmp.outputStream().use { out ->
            out.write(json.encodeToString(Album.serializer(), album).toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("No se pudo guardar el álbum")
        }
    }

    private fun loadAllBlocking() {
        root.mkdirs()
        root.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val a = read(d)
            if (a != null) albums[a.id] = a
        }
        publish()
        if (!loaded.isCompleted) loaded.complete(Unit)
    }

    private fun read(d: File): Album? {
        for (f in listOf(File(d, ALBUM_JSON), File(d, "$ALBUM_JSON.tmp"))) {
            if (!f.isFile) continue
            try {
                return json.decodeFromString(Album.serializer(), f.readText()).copy(id = d.name)
            } catch (t: Throwable) {
                runCatching { Log.w(TAG, "album.json ilegible en $d", t) }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AlbumStore"
        const val ALBUM_JSON = "album.json"
        val SUBDIRS = listOf("bg", "src", "stk")

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        /** Archivos (relativos) que usa el álbum. */
        fun referencedFiles(a: Album): Set<String> = buildSet {
            for (p in a.pages) {
                p.background.file?.let(::add)
                p.background.preview?.let(::add)
                for (s in p.slots) s.sticker?.let { add(it.source); add(it.rendered) }
            }
        }

        /** Hoja en blanco de [paper] (vertical u horizontal). */
        fun blankPage(paper: PaperSize, landscape: Boolean): AlbumPage = AlbumPage(
            id = newId(),
            widthMm = if (landscape) paper.heightMm else paper.widthMm,
            heightMm = if (landscape) paper.widthMm else paper.heightMm,
        )
    }
}
