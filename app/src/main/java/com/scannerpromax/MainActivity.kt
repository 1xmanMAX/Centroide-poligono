package com.scannerpromax

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.data.AppSettings
import com.scannerpromax.ui.components.CrashReportDialog
import com.scannerpromax.ui.navigation.AppNavHost
import com.scannerpromax.ui.theme.EscanerTheme
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.PerfConfig
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** PDF recibido de otra app (compartir / abrir con) para comprimir. */
    private val incomingPdf = MutableStateFlow<Uri?>(null)

    /** Imágenes compartidas desde la galería u otra app para escanearlas/mejorarlas. */
    private val incomingImages = MutableStateFlow<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as ScannerApp
        // El splash se mantiene hasta que el hilo de arranque cargó OpenCV/PdfBox y los documentos (sin bloquear
        // el hilo principal: la condición solo lee un StateFlow en cada frame).
        installSplashScreen().setKeepOnScreenCondition { !app.ready.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo en el primer arranque: si la actividad se recrea (p. ej. tras morir el proceso),
        // el intent original no debe volver a llevar al compresor ni reimportar imágenes.
        if (savedInstanceState == null) handleIntent(intent)
        val container = app.container
        val perf = PerfConfig.from(this, container.deviceTier)
        setContent {
            // Flow recordado: SettingsRepository.settings crea un Flow nuevo en cada acceso y collectAsState
            // reiniciaba la recolección (lectura de DataStore) en cada recomposición.
            val settingsFlow = remember { container.settings.settings }
            val initial = remember { app.initialSettings ?: AppSettings() }
            val settings = settingsFlow.collectAsStateWithLifecycle(initialValue = initial).value
            val ready by app.ready.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalPerf provides perf) {
                EscanerTheme(darkTheme = settings.darkTheme, dynamicColor = settings.dynamicColor) {
                    if (ready) {
                        AppNavHost(container = container, incomingPdf = incomingPdf, incomingImages = incomingImages)
                        CrashReportDialog()
                    } else {
                        // Detrás del splash: solo el fondo. La navegación (que usa OpenCV y los documentos) se
                        // compone cuando todo está listo, así el hilo principal nunca espera a la inicialización.
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Al pasar a segundo plano: escribir ya los doc.json con cambios diferidos (OCR, miniaturas) por si el
        // sistema mata el proceso.
        val app = application as ScannerApp
        if (app.ready.value) {
            lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
                runCatching { app.container.documents.flushPendingWrites() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val action = intent.action ?: return
        val uris: List<Uri> = when (action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra()).ifEmpty { listOfNotNull(intent.data) }
            Intent.ACTION_SEND_MULTIPLE -> intent.streamListExtra()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        // Muchos gestores de archivos lanzan VIEW solo con data (sin MIME): se resuelve con el ContentResolver.
        val types = uris.map { u -> (intent.type?.takeIf { it != "*/*" } ?: runCatching { contentResolver.getType(u) }.getOrNull()).orEmpty().lowercase() }
        val isPdf = { i: Int -> types[i].endsWith("pdf") || uris[i].lastPathSegment?.lowercase()?.endsWith(".pdf") == true }
        val isImage = { i: Int -> types[i].startsWith("image/") }

        val pdfIndex = uris.indices.firstOrNull(isPdf)
        val images = uris.indices.filter { isImage(it) && !isPdf(it) }.map { uris[it] }
        when {
            images.isNotEmpty() -> incomingImages.value = images
            pdfIndex != null -> incomingPdf.value = uris[pdfIndex]
            else -> return
        }
        // Intent consumido: no volver a procesarlo.
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun Intent.streamExtra(): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun Intent.streamListExtra(): List<Uri> = (
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        )?.filterNotNull().orEmpty()
}
