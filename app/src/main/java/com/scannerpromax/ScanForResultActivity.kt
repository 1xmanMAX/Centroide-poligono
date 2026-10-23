package com.scannerpromax

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.scannerpromax.data.AppSettings
import com.scannerpromax.integration.AttachExporter
import com.scannerpromax.integration.DeliveryPlanner
import com.scannerpromax.integration.IntentFactsReader
import com.scannerpromax.integration.ParseResult
import com.scannerpromax.integration.ScanRequest
import com.scannerpromax.integration.ScanRequestParser
import com.scannerpromax.integration.ScanResultIntent
import com.scannerpromax.ui.attach.AttachCallbacks
import com.scannerpromax.ui.attach.AttachNavHost
import com.scannerpromax.ui.theme.EscanerTheme
import com.scannerpromax.ui.theme.LocalPerf
import com.scannerpromax.ui.theme.PerfConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Escáner "para otra app" (integración avanzada): la abren IMAGE_CAPTURE ("Cámara"), GET_CONTENT ("Adjuntar") y la
 * acción propia [com.scannerpromax.integration.ScanContract.ACTION_SCAN]. Por defecto es directa: cámara del escáner →
 * "Adjuntar (N)" → genera el archivo, lo devuelve con setResult y se cierra, volviendo sola a la otra app. Con el extra
 * REVIEW (SCAN) o el ajuste "Revisar antes de adjuntar" (GET_CONTENT / IMAGE_CAPTURE) pasa antes por una revisión.
 *
 * Vive en la tarea de la app que la llama (startActivityForResult) y tiene taskAffinity vacía: nunca se mezcla con la
 * pila de [MainActivity] ni aparece aparte en Recientes. El estado (documento en curso, pantalla) se guarda con la
 * navegación y rememberSaveable, así que sobrevive a giros y a la muerte del proceso.
 */
class ScanForResultActivity : ComponentActivity() {

    /** true tras entregar el resultado: ignora cancelaciones tardías (p. ej. el borrado del documento temporal). */
    @Volatile private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as ScannerApp
        installSplashScreen().setKeepOnScreenCondition { !app.ready.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Por defecto, si el sistema cierra la actividad sin más, la otra app recibe "cancelado".
        setResult(Activity.RESULT_CANCELED)
        val facts = runCatching { IntentFactsReader.read(intent) }.getOrNull()
        if (facts == null) {
            finish()
            return
        }
        val canReturnResult = callingActivity != null
        val caller = callerPackage()
        val callerName = caller?.let { appLabel(it) }
        val container = app.container
        val perf = PerfConfig.from(this, container.deviceTier)

        setContent {
            val settingsFlow = remember { container.settings.settings }
            val initial = remember { app.initialSettings ?: AppSettings() }
            val settings = settingsFlow.collectAsStateWithLifecycle(initialValue = initial).value
            val ready by app.ready.collectAsStateWithLifecycle()
            // El análisis usa el formato por defecto de Ajustes: se espera a leerlos de verdad (no los iniciales).
            val parsed by produceState<ParseResult?>(initialValue = null, ready) {
                if (!ready) return@produceState
                val s = runCatching { container.settings.settings.first() }.getOrDefault(initial)
                value = ScanRequestParser.parse(facts, s.attachAnyFormat, packageName, reviewSetting = s.attachReview)
            }
            CompositionLocalProvider(LocalPerf provides perf) {
                EscanerTheme(darkTheme = settings.darkTheme, dynamicColor = settings.dynamicColor) {
                    when (val p = parsed) {
                        is ParseResult.Ok -> AttachNavHost(
                            container = container,
                            request = p.request,
                            // Nombre del sistema (callingPackage/referrer) o, si no hay, el extra CALLER_LABEL.
                            callerName = callerName ?: p.request.callerLabel,
                            canReturnResult = canReturnResult,
                            callbacks = AttachCallbacks(
                                onCancel = { docId -> cancel(docId) },
                                onAttach = { docId, single, progress -> attach(p.request, docId, single, progress) },
                            ),
                        )
                        is ParseResult.Rejected -> LaunchedEffect(p) {
                            Toast.makeText(this@ScanForResultActivity, p.message, Toast.LENGTH_LONG).show()
                            finish()
                        }
                        null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    }
                }
            }
        }
    }

    /** Cancelar: RESULT_CANCELED, se borra el escaneo a medias y se vuelve a la otra app. */
    private fun cancel(docId: String?) {
        if (delivered || isFinishing) return
        setResult(Activity.RESULT_CANCELED)
        finish()
        if (docId != null) deleteLater(docId)
    }

    private suspend fun attach(request: ScanRequest, docId: String, singleImagePage: Int, progress: (Float, String) -> Unit) {
        val app = application as ScannerApp
        val container = app.container
        val settings = runCatching { container.settings.settings.first() }.getOrDefault(AppSettings())
        val doc = container.documents.get(docId) ?: throw IllegalStateException("El escaneo ya no existe")
        val plan = DeliveryPlanner.plan(request, doc.pages.size, singleImagePage)
        val out = AttachExporter(applicationContext, container).deliver(request, plan, docId, settings, progress)
        val result = ScanResultIntent.build(
            plan = plan,
            uris = out.uris,
            pageCount = out.pageCount,
            text = out.text,
            thumbnail = out.thumbnail,
            label = request.title ?: doc.title,
        )
        // Sin nadie esperando el resultado (lanzada con startActivity), el escaneo se guarda siempre.
        val keep = settings.attachSaveCopy || callingActivity == null
        delivered = true
        setResult(Activity.RESULT_OK, result)
        finish()
        if (!keep) {
            deleteLater(docId)
        } else {
            request.title?.let { t ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { container.documents.rename(docId, t) } }
            }
            if (callingActivity == null) Toast.makeText(applicationContext, "Guardado en Documentos", Toast.LENGTH_SHORT).show()
        }
    }

    /** Borra el documento temporal fuera del ciclo de vida de la actividad (que ya se está cerrando). */
    private fun deleteLater(docId: String) {
        val container = (application as ScannerApp).container
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            withContext(NonCancellable) { runCatching { container.documents.delete(docId) } }
        }
    }

    /** Paquete de la app que pidió el escaneo: callingPackage (startActivityForResult) o el referrer. */
    private fun callerPackage(): String? {
        val fromCall = callingPackage
        val fromReferrer = runCatching { referrer }.getOrNull()
            ?.takeIf { it.scheme == "android-app" }?.host
        return listOfNotNull(fromCall, fromReferrer).firstOrNull { it.isNotBlank() && it != packageName && it != "android" }
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(info).toString().trim().take(40).takeIf { it.isNotEmpty() }
    }.getOrNull()

    override fun onStop() {
        super.onStop()
        val app = application as ScannerApp
        if (app.ready.value) {
            lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
                runCatching { app.container.documents.flushPendingWrites() }
            }
        }
    }
}
