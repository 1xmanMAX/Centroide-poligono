package com.scannerpromax.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelStorageTest {
    private lateinit var root: File
    private lateinit var storage: ModelStorage

    private val model = AiModel(
        id = "prueba", name = "Prueba", technicalName = "t", license = "Apache 2.0", purpose = "p", description = "d",
        role = ModelRole.FAST_READER,
        files = listOf(
            ModelFile(FileKind.ONNX, "llm.gguf", ModelCatalog.hf("a/b", "llm.gguf"), 1000),
            ModelFile(FileKind.DATA, "mm.gguf", ModelCatalog.hf("a/b", "mm.gguf"), 500),
        ),
        recommendedRamMb = 1, minRamMb = 1,
    )

    @Before fun setUp() {
        root = Files.createTempDirectory("models").toFile()
        storage = ModelStorage(root)
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(name: String, size: Int) {
        storage.dir(model).mkdirs()
        File(storage.dir(model), name).writeBytes(ByteArray(size))
    }

    @Test fun estados() {
        assertFalse(storage.isReady(model))
        assertEquals(1500, storage.remainingBytes(model))
        write("llm.gguf.part", 400)
        assertTrue(storage.hasPartial(model))
        assertEquals(400, storage.downloadedBytes(model))
        write("llm.gguf", 1000)
        File(storage.dir(model), "llm.gguf.part").delete()
        write("mm.gguf", 499) // tamaño incorrecto: no cuenta como completo
        assertFalse(storage.isReady(model))
        write("mm.gguf", 500)
        assertTrue(storage.isReady(model))
        assertEquals(0, storage.remainingBytes(model))
        assertEquals("llm.gguf", storage.readyFile(model, "llm.gguf")!!.name)
    }

    @Test fun borrar() {
        write("llm.gguf", 1000)
        assertTrue(storage.delete(model))
        assertFalse(storage.dir(model).exists())
        assertNull(storage.readyFile(model, "llm.gguf"))
    }
}
