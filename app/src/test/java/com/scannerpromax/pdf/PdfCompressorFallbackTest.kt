package com.scannerpromax.pdf

import com.scannerpromax.domain.CompressionLevel
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.ProgressCallback
import dev.pdfsqueeze.SqueezeAnalysis
import dev.pdfsqueeze.SqueezeEnvelope
import dev.pdfsqueeze.SqueezeImage
import dev.pdfsqueeze.SqueezeImageInfo
import dev.pdfsqueeze.SqueezeReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Cuándo [PdfCompressor] usa pdfsqueeze y cuándo el compresor de respaldo (motor y respaldo simulados). */
class PdfCompressorFallbackTest {
    private lateinit var dir: File
    private lateinit var source: File
    private lateinit var output: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("compress_test").toFile()
        source = File(dir, "in.pdf").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
        output = File(dir, "out.pdf")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private class FakeEngine(
        override val available: Boolean = true,
        val analyzeStatus: Int = SqueezeEnvelope.STATUS_OK,
        val memory: Long = 8L shl 30,
        val maxPixels: Long = 4_000_000,
        /** Tamaño de salida según el SSIM pedido; null = el motor falla. */
        val sizeFor: ((SqueezeParams) -> Long?) = { 40_000 },
    ) : SqueezeEngine {
        override val tier = DeviceTier(isLowRam = false, maxWorkingPixels = 20_000_000, cores = 8)
        val calls = ArrayList<Pair<SqueezeParams, Int>>()
        override fun availableMemory() = memory
        override suspend fun analyze(input: File) = SqueezeEnvelope(
            status = analyzeStatus,
            analysis = SqueezeAnalysis(
                fileBytes = input.length(), pages = 3,
                budget = mapOf("image/jpeg" to 90_000L, "fonts" to 10_000L),
                images = listOf(SqueezeImageInfo(width = 2000, height = (maxPixels / 2000).toInt(), bytes = 90_000)),
            ),
        )

        override suspend fun compress(input: File, output: File, params: SqueezeParams, threads: Int, progress: ((Float) -> Unit)?): SqueezeEnvelope {
            calls += params to threads
            progress?.invoke(1f)
            val size = sizeFor(params) ?: return SqueezeEnvelope(status = SqueezeEnvelope.STATUS_BAD_INPUT, message = "boom")
            if (size >= input.length()) return SqueezeEnvelope(status = 0, returnedOriginal = true, report = SqueezeReport(pages = 3, returnedOriginal = true))
            output.writeBytes(ByteArray(size.toInt()))
            return SqueezeEnvelope(
                status = 0, threads = threads,
                report = SqueezeReport(
                    pages = 3, verified = true, elapsedMs = 1234,
                    images = listOf(
                        SqueezeImage(width = 10, height = 10, beforeBytes = 100, afterBytes = 50, ssim = 0.98, action = "jpeg"),
                        SqueezeImage(width = 10, height = 10, beforeBytes = 100, afterBytes = 60, ssim = 0.95, action = "jpeg"),
                        SqueezeImage(width = 10, height = 10, beforeBytes = 100, afterBytes = 100, action = "keep"),
                    ),
                ),
            )
        }
    }

    private class FakeLegacy(val size: Long = 70_000) : FallbackPdfCompressor {
        var calls = 0
        override suspend fun compressFile(source: File, level: CompressionLevel, output: File, progress: ProgressCallback?): CompressionResult {
            calls++
            output.writeBytes(ByteArray(size.toInt()))
            return CompressionResult(output, source.length(), size, 3)
        }

        override suspend fun compressFileToTarget(source: File, targetBytes: Long, output: File, progress: ProgressCallback?): CompressionResult {
            calls++
            output.writeBytes(ByteArray(size.toInt()))
            return CompressionResult(output, source.length(), size, 3, rasterized = true)
        }
    }

    private fun compressor(engine: SqueezeEngine, legacy: FallbackPdfCompressor) =
        PdfCompressor(engine, legacy, { dir }, { error("sin Uri en el test") })

    @Test
    fun usesEngineWhenAvailable() = runBlocking {
        val engine = FakeEngine()
        val legacy = FakeLegacy()
        val r = compressor(engine, legacy).compressFile(source, CompressionLevel.RECOMMENDED, output)
        assertEquals(0, legacy.calls)
        assertEquals(1, engine.calls.size)
        assertEquals(SqueezePlan.forLevel(CompressionLevel.RECOMMENDED), engine.calls[0].first)
        assertEquals(4, engine.calls[0].second)
        assertEquals(40_000L, r.compressedBytes)
        assertEquals(40_000L, output.length())
        assertNull(r.fallbackReason)
        val e = r.engine!!
        assertEquals(0.965, e.ssimMean!!, 1e-9)
        assertEquals(0.95, e.ssimMin!!, 1e-9)
        assertEquals(2, e.imagesOptimized)
        assertEquals(3, e.imagesTotal)
        assertTrue(e.verified)
        assertEquals(3, r.pageCount)
        // Sin restos en la carpeta de trabajo.
        assertEquals(setOf("in.pdf", "out.pdf"), dir.list()!!.toSet())
    }

    @Test
    fun fallsBackWhenLibraryMissing() = runBlocking {
        val legacy = FakeLegacy()
        val r = compressor(FakeEngine(available = false), legacy).compressFile(source, CompressionLevel.LIGHT, output)
        assertEquals(1, legacy.calls)
        assertEquals(PdfCompressor.REASON_NO_LIBRARY, r.fallbackReason)
        assertNull(r.engine)
    }

    @Test
    fun fallsBackWhenEncrypted() = runBlocking {
        val legacy = FakeLegacy()
        val engine = FakeEngine(analyzeStatus = SqueezeEnvelope.STATUS_ENCRYPTED)
        val r = compressor(engine, legacy).compressFile(source, CompressionLevel.LIGHT, output)
        assertEquals(1, legacy.calls)
        assertEquals(0, engine.calls.size)
        assertEquals(PdfCompressor.REASON_ENCRYPTED, r.fallbackReason)
    }

    @Test
    fun fallsBackWhenEngineFails() = runBlocking {
        val legacy = FakeLegacy()
        val r = compressor(FakeEngine(sizeFor = { null }), legacy).compressFile(source, CompressionLevel.STRONG, output)
        assertEquals(1, legacy.calls)
        assertEquals(PdfCompressor.REASON_ENGINE_FAILED, r.fallbackReason)
    }

    @Test
    fun fallsBackWhenNotEnoughMemory() = runBlocking {
        val legacy = FakeLegacy()
        val engine = FakeEngine(memory = 100L shl 20, maxPixels = 40_000_000)
        val r = compressor(engine, legacy).compressFile(source, CompressionLevel.STRONG, output)
        assertEquals(0, engine.calls.size)
        assertEquals(PdfCompressor.REASON_MEMORY, r.fallbackReason)
    }

    @Test
    fun reducesThreadsUnderMemoryPressure() = runBlocking {
        // 12 MP × 28 B ≈ 336 MB por hilo; 1,6 GB libres → presupuesto 800 MB → 2 hilos.
        val engine = FakeEngine(memory = 1_600L shl 20, maxPixels = 12_000_000)
        compressor(engine, FakeLegacy()).compressFile(source, CompressionLevel.STRONG, output)
        assertEquals(2, engine.calls.single().second)
    }

    @Test
    fun neverBiggerThanInput() = runBlocking {
        val r = compressor(FakeEngine(sizeFor = { 500_000 }), FakeLegacy()).compressFile(source, CompressionLevel.LIGHT, output)
        assertTrue(r.keptOriginal)
        assertEquals(source.length(), r.compressedBytes)
        assertEquals(source.length(), output.length())
        assertFalse(r.rasterized)
    }

    @Test
    fun targetSearchKeepsTextWhenReachable() = runBlocking {
        val legacy = FakeLegacy()
        // Tamaño crece con el SSIM pedido.
        val engine = FakeEngine(sizeFor = { p -> (10_000 + (p.minSsim - 0.84) / 0.13 * 80_000).toLong() })
        val r = compressor(engine, legacy).compressFileToTarget(source, 50_000, output)
        assertEquals(0, legacy.calls)
        assertTrue(engine.calls.size in 1..SqueezePlan.MAX_PASSES)
        assertTrue(r.compressedBytes <= 50_000)
        assertFalse(r.rasterized)
        assertNotNull(r.engine)
        assertEquals(setOf("in.pdf", "out.pdf"), dir.list()!!.toSet())
    }

    @Test
    fun targetUnreachableUsesRasterFallback() = runBlocking {
        val legacy = FakeLegacy(size = 9_000)
        val engine = FakeEngine(sizeFor = { 30_000 })
        val r = compressor(engine, legacy).compressFileToTarget(source, 10_000, output)
        assertEquals(1, legacy.calls)
        assertTrue(r.rasterized)
        assertEquals(PdfCompressor.REASON_TARGET, r.fallbackReason)
        assertEquals(9_000L, output.length())
    }

    @Test
    fun targetUnreachableKeepsTextIfRasterIsNotSmaller() = runBlocking {
        val legacy = FakeLegacy(size = 60_000)
        val engine = FakeEngine(sizeFor = { 30_000 })
        val r = compressor(engine, legacy).compressFileToTarget(source, 10_000, output)
        assertFalse(r.rasterized)
        assertEquals(30_000L, r.compressedBytes)
    }
}
