package com.scannerpromax.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test fun idsUnicosYUnPorRol() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
        ModelRole.entries.forEach { assertNotNull(ModelCatalog.forRole(it)) }
    }

    @Test fun cadaModeloTieneLlmYMmproj() {
        for (m in ModelCatalog.all) {
            assertEquals(1, m.files.count { it.kind == FileKind.LLM })
            assertEquals(1, m.files.count { it.kind == FileKind.MMPROJ })
            assertTrue(m.totalDownloadBytes > 100_000_000)
            assertTrue(m.tasks.isNotEmpty())
            assertTrue(m.purpose.isNotBlank())
        }
    }

    @Test fun urlsDeHuggingFaceBienFormadas() {
        for (m in ModelCatalog.all) for (f in m.files) {
            assertTrue(f.url, f.url.matches(Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[^/]+/.+")))
            assertTrue(f.url.endsWith("/" + f.fileName))
            f.sha256?.let { assertTrue(it.matches(Regex("[0-9a-f]{64}"))) }
        }
    }

    @Test fun prefijosDePaddle() {
        val prompts = ModelCatalog.DOCUMENT_READER.tasks.map { it.prompt }
        listOf("OCR:", "Table Recognition:", "Chart Recognition:", "Formula Recognition:", "Seal Recognition:", "Spotting:")
            .forEach { assertTrue(it, it in prompts) }
        assertEquals(0f, ModelCatalog.DOCUMENT_READER.defaults.temperature)
    }

    @Test fun cuantizacionSoloDelLlm() {
        for (m in ModelCatalog.all) {
            val q = m.onDeviceQuantization ?: continue
            assertEquals(m.llmFile.fileName, q.sourceFileName)
            assertTrue(q.outputFileName != q.sourceFileName)
        }
    }

    @Test fun motoresDeReconocimiento() {
        assertEquals(null, RecognitionEngine.BASIC.role)
        assertEquals(ModelRole.DOCUMENT_READER, RecognitionEngine.DOCUMENT_READER.role)
        assertEquals(ModelRole.VISUAL_ASSISTANT, RecognitionEngine.VISUAL_ASSISTANT.role)
    }

    @Test fun hilosSegunNucleosGrandes() {
        // 1+3+4 (X, A7xx, A5xx): 4 grandes
        assertEquals(4, CpuInfo.threadsFor(8, listOf(2_000, 2_000, 2_000, 2_000, 2_800, 2_800, 2_800, 3_300).map { it * 1000L }))
        // 4+4 Cortex-A53 a 1,5/2,0 GHz: los 8 >= 75 % -> tope 6
        assertEquals(6, CpuInfo.threadsFor(8, listOf(1_500, 1_500, 1_500, 1_500, 2_000, 2_000, 2_000, 2_000).map { it * 1000L }))
        // 6+2: mínimo 2
        assertEquals(2, CpuInfo.threadsFor(8, listOf(1_800, 1_800, 1_800, 1_800, 1_800, 1_800, 2_600, 2_600).map { it * 1000L }))
        // sin datos de frecuencia
        assertEquals(4, CpuInfo.threadsFor(8, emptyList()))
        assertEquals(2, CpuInfo.threadsFor(4, emptyList()))
        assertEquals(1, CpuInfo.threadsFor(1, emptyList()))
    }
}
