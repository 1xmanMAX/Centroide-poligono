package com.scannerpromax.ui.theme

// ARCHIVO DE CONTRATO: el agente de UI reemplaza esto con el tema completo (Color.kt, Type.kt, Shape...).

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun EscanerTheme(darkTheme: Boolean? = null, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}
