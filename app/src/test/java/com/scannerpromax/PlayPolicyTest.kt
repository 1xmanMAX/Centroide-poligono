package com.scannerpromax

import com.scannerpromax.ai.jev.JevSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reglas de Google Play que no deben romperse sin querer (manifiesto, firma, consentimiento). */
class PlayPolicyTest {
    private fun read(vararg candidates: String) = candidates.map(::File).first { it.isFile }.readText()
    private val manifest by lazy { read("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml") }
    // Los tests JVM corren con el módulo app como directorio de trabajo.
    private val gradle by lazy { read("build.gradle.kts", "app/build.gradle.kts") }

    @Test fun permisosMinimos() {
        for (p in listOf("QUERY_ALL_PACKAGES", "READ_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO",
            "MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES", "ACCESS_FINE_LOCATION", "RECORD_AUDIO")) {
            assertFalse(p, manifest.contains("android.permission.$p"))
        }
        // WRITE_EXTERNAL_STORAGE solo hasta Android 9 (exportar a Descargas en API 24-28).
        assertTrue(Regex("""WRITE_EXTERNAL_STORAGE"\s+android:maxSdkVersion="28"""").containsMatchIn(manifest))
    }

    @Test fun servicioEnPrimerPlanoConTipo() {
        assertTrue(manifest.contains("FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
    }

    @Test fun sinTelemetriaDeOnnxRuntime() {
        val i = manifest.indexOf("ai.onnxruntime.TelemetryInitializer")
        assertTrue(i > 0)
        assertTrue(manifest.substring(i, i + 300).contains("tools:node=\"remove\""))
    }

    @Test fun releaseNuncaConClaveDeDebug() {
        assertFalse(gradle.contains("signingConfigs.getByName(\"debug\")"))
        assertTrue(gradle.contains("UPLOAD_STORE_FILE"))
        assertTrue(Regex("""targetSdk = (\d+)""").find(gradle)!!.groupValues[1].toInt() >= 36)
    }

    @Test fun jevSoloConConsentimiento() {
        assertFalse(JevSettings(enabled = true, consent = false).on)
        assertFalse(JevSettings(enabled = false, consent = true).on)
        assertTrue(JevSettings(enabled = true, consent = true).on)
    }
}
