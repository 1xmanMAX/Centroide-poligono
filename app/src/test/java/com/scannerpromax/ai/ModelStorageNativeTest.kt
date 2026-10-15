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

/** Modelo con librerías nativas (como la maquetación con ONNX Runtime): listo solo con las .so de SU ABI. */
class ModelStorageNativeTest {
    private lateinit var root: File

    private val model = AiModel(
        id = "maq", name = "Maquetación", technicalName = "t", license = "Apache 2.0", purpose = "p", description = "d",
        role = ModelRole.PAGE_LAYOUT,
        files = listOf(ModelFile(FileKind.ONNX, "m.onnx", ModelCatalog.hf("a/b", "inference.onnx"), 1000)),
        recommendedRamMb = 1, minRamMb = 1, onDeviceQuantization = null,
        defaults = ModelCatalog.PAGE_LAYOUT.defaults, tasks = emptyList(),
        nativeLibs = NativeLibs(
            version = "1.0", url = "https://example.org/x.aar", archiveSize = 9999, archiveSha256 = "0".repeat(64),
            libs = listOf(
                NativeLib("arm64-v8a", "libonnxruntime.so", 300, 120, "1".repeat(64)),
                NativeLib("armeabi-v7a", "libonnxruntime.so", 200, 90, "2".repeat(64)),
            ),
        ),
    )

    @Before fun setUp() { root = Files.createTempDirectory("models-native").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun listoSoloConLasLibreriasDeSuAbi() {
        val s = ModelStorage(root, "arm64-v8a")
        assertEquals(1120, s.totalBytes(model))
        assertEquals(1120, s.remainingBytes(model))
        assertEquals(300, s.nativeUnpackedRemaining(model))
        s.dir(model).mkdirs()
        File(s.dir(model), "m.onnx").writeBytes(ByteArray(1000))
        assertFalse("sin la .so no está listo", s.isReady(model))
        assertEquals(1000, s.downloadedBytes(model))
        val so = s.nativeLibFile(model, s.nativeLibs(model)!!.single())
        assertEquals(File(root, "maq/lib/arm64-v8a/libonnxruntime.so"), so)
        assertEquals(so.parentFile, s.nativeLibDir(model))
        so.parentFile!!.mkdirs()
        File(so.parentFile, so.name + ".zpart").writeBytes(ByteArray(50))
        assertTrue(s.hasPartial(model))
        assertEquals(1050, s.downloadedBytes(model))
        assertFalse(s.isReady(model))
        so.writeBytes(ByteArray(299))  // tamaño incorrecto
        assertFalse(s.isReady(model))
        so.writeBytes(ByteArray(300))
        File(so.parentFile, so.name + ".zpart").delete()
        assertTrue(s.isReady(model))
        assertEquals(1120, s.downloadedBytes(model))
        assertEquals(0, s.remainingBytes(model))
        assertEquals(0, s.nativeUnpackedRemaining(model))
        assertEquals(1300, s.diskUsage(model))  // cuenta también lib/<abi>/
        // La misma carpeta vista desde un proceso de 32 bits no está lista: necesita la .so de armeabi-v7a.
        val s32 = ModelStorage(root, "armeabi-v7a")
        assertFalse(s32.isReady(model))
        assertEquals(1090, s32.totalBytes(model))
        // Borrar el modelo borra también sus librerías.
        assertTrue(s.delete(model))
        assertFalse(so.exists())
    }

    @Test fun abiNoSoportadaNuncaEstaLista() {
        val s = ModelStorage(root, "riscv64")
        assertNull(s.nativeLibs(model))
        s.dir(model).mkdirs()
        File(s.dir(model), "m.onnx").writeBytes(ByteArray(1000))
        assertFalse(s.isReady(model))
        assertFalse(ModelStorage(root, null).isReady(model))
    }

    @Test fun modeloSinLibreriasNoCambia() {
        val plain = model.copy(nativeLibs = null)
        val s = ModelStorage(root, null)
        assertEquals(emptyList<NativeLib>(), s.nativeLibs(plain))
        s.dir(plain).mkdirs()
        File(s.dir(plain), "m.onnx").writeBytes(ByteArray(1000))
        assertTrue(s.isReady(plain))
        assertEquals(1000, s.totalBytes(plain))
    }

    @Test fun abiDelProceso() {
        val a64 = listOf("arm64-v8a")
        val a32 = listOf("armeabi-v7a", "armeabi")
        val all = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        assertEquals("arm64-v8a", NativeAbi.choose(true, a64, a32, all))
        // APK armeabi-v7a en un teléfono arm64: proceso de 32 bits → .so de 32 bits (no SUPPORTED_ABIS[0]).
        assertEquals("armeabi-v7a", NativeAbi.choose(false, a64, a32, all))
        assertEquals("arm64-v8a", NativeAbi.choose(null, a64, a32, all))
        assertEquals("armeabi-v7a", NativeAbi.choose(false, emptyList(), listOf("armeabi-v7a"), listOf("armeabi-v7a")))
    }
}
