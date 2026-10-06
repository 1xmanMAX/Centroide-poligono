package com.scannerpromax

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerpromax.data.AppSettings
import com.scannerpromax.ui.navigation.AppNavHost
import com.scannerpromax.ui.theme.EscanerTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** PDF recibido de otra app (compartir / abrir con) para comprimir. */
    private val incomingPdf = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val container = (application as ScannerApp).container
        setContent {
            val settings = container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings()).value
            EscanerTheme(darkTheme = settings.darkTheme, dynamicColor = settings.dynamicColor) {
                AppNavHost(container = container, incomingPdf = incomingPdf)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null && intent.type == "application/pdf") incomingPdf.value = uri
    }
}
