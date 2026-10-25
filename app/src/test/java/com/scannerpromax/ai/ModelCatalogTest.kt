package com.scannerpromax.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelCatalogTest {
    @Test fun idsUnicosYUnPorRol() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
        ModelRole.entries.forEach { assertNotNull(ModelCatalog.forRole(it)) }
    }

    @Test fun soloModelosOnnxIndraYMaquetacion() {
        // Sin LLM locales (PaddleOCR-VL / Qwen) ni llama.cpp: Indra (obligatorio) + maquetación (opcional).
        assertEquals(listOf(ModelCatalog.FAST_READER, ModelCatalog.PAGE_LAYOUT), ModelCatalog.all)
        for (m in ModelCatalog.all) assertTrue(m.files.all { it.kind == FileKind.ONNX || it.kind == FileKind.DATA })
    }

    @Test fun urlsDeHuggingFaceBienFormadas() {
        for (m in ModelCatalog.all) for (f in m.files) {
            assertTrue(f.url, f.url.matches(Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[^/]+/.+")))
            // (los ONNX de la maquetación se llaman inference.onnx en sus repos: se guardan con nombre propio)
            if (f.kind != FileKind.ONNX) assertTrue(f.url.endsWith("/" + f.fileName))
            f.sha256?.let { assertTrue(it.matches(Regex("[0-9a-f]{64}"))) }
        }
    }

    @Test fun maquetacionDePaginasEsOnnx() {
        val m = ModelCatalog.PAGE_LAYOUT
        assertEquals(2, m.files.count { it.kind == FileKind.ONNX })
        assertEquals(setOf(ModelCatalog.LAYOUT_FILE, ModelCatalog.ORIENTATION_FILE), m.files.map { it.fileName }.toSet())
        assertTrue(m.totalDownloadBytes in 130_000_000L..140_000_000L)
    }

    @Test fun onnxRuntimeNativoSeBajaConLaMaquetacion() {
        val n = ModelCatalog.PAGE_LAYOUT.nativeLibs!!
        // Los dos modelos ONNX (Maquetación e Indra) la traen, y la comparten (se copia si ya está).
        assertTrue(ModelCatalog.all.filter { it !== ModelCatalog.PAGE_LAYOUT && it !== ModelCatalog.FAST_READER }.all { it.nativeLibs == null })
        assertTrue(ModelCatalog.FAST_READER.nativeLibs === n)
        assertEquals(
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${n.version}/onnxruntime-android-${n.version}.aar",
            n.url,
        )
        // Las ABIs que empaqueta la app (splits): sin x86 de 32 bits.
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86_64"), n.abis.toSet())
        for (l in n.libs) {
            assertEquals("libonnxruntime.so", l.fileName)
            assertEquals("jni/${l.abi}/libonnxruntime.so", l.entryName)
            assertTrue(l.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(l.compressedBytes in 10_000_000L until l.sizeBytes)
        }
        assertTrue(n.archiveSha256.matches(Regex("[0-9a-f]{64}")))
        // Con la ABI conocida, la descarga incluye la .so comprimida de esa ABI (~13 MB en arm64).
        assertEquals(130_502_049L + 6_788_069L + 12_887_720L, ModelCatalog.PAGE_LAYOUT.downloadBytes("arm64-v8a"))
        assertEquals(130_502_049L + 6_788_069L, ModelCatalog.PAGE_LAYOUT.downloadBytes(null))
    }

    /** La .so descargada DEBE ser de la misma versión que la API Java/JNI de ORT empaquetada (dependencia Gradle). */
    @Test fun versionDeOrtIgualQueLaDependencia() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText()
        val dep = Regex("""implementation\("com\.microsoft\.onnxruntime:onnxruntime-android:([^"]+)"\)""").find(gradle)!!.groupValues[1]
        assertEquals(dep, ModelCatalog.ORT_VERSION)
        assertEquals(dep, ModelCatalog.ORT_NATIVE.version)
        assertTrue("libonnxruntime.so debe quedar fuera del APK", gradle.contains("\"lib/*/libonnxruntime.so\""))
    }

    @Test fun lectorRapidoIndraSoloLatin() {
        val m = ModelCatalog.FAST_READER
        assertEquals(com.scannerpromax.ocr.indra.IndraOcr.FILES.toSet(), m.files.map { it.fileName }.toSet())
        // ~25 MB (sin CJK/RTL/world ni matemáticas) + ORT
        assertEquals(24_684_875L, m.downloadBytes(null))
        for (f in m.files) assertTrue(f.url, f.url.contains("/datalab-to/indra-ocr/resolve/${ModelCatalog.INDRA_REVISION}/"))
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
