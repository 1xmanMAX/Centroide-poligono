package com.scannerpromax.ui.navigation

// ARCHIVO DE CONTRATO: el agente de UI conecta aquí las pantallas reales.

import android.net.Uri
import androidx.compose.runtime.Composable
import com.scannerpromax.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun AppNavHost(container: AppContainer, incomingPdf: MutableStateFlow<Uri?>) {
    TODO()
}
