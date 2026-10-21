package com.scannerpromax.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Tokens del diseño estilo Apple (rediseño 2026-10). Acceso: `MaterialTheme.ui`.
 * Los componentes nuevos (ui/components/Ios*.kt) usan SOLO estos tokens.
 */
@Immutable
data class UiColors(
    /** Fondo de las pantallas con listas agrupadas (#F2F2F7 / negro). */
    val groupedBackground: Color,
    /** Tarjetas y celdas de las listas agrupadas (blanco / #1C1C1E). */
    val card: Color,
    /** Superficie elevada sobre tarjetas (menús, segmento seleccionado en oscuro). */
    val elevated: Color,
    val label: Color,
    val secondaryLabel: Color,
    /** Chevrons, marcadores de posición. */
    val tertiaryLabel: Color,
    val separator: Color,
    /** Separador opaco (asa de las hojas, bordes de menú). */
    val opaqueSeparator: Color,
    /** Relleno de campos y botones grises (#E3E3E8 / #2C2C2E). */
    val fill: Color,
    /** Acento único (índigo, o el del sistema con Color dinámico). */
    val accent: Color,
    val onAccent: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    /** Fondo de las barras flotantes (cápsula translúcida). */
    val bar: Color,
    /** Banda gris entre grupos del menú •••. */
    val menuGroupGap: Color,
    /** Fondo del menú ••• (casi opaco). */
    val menu: Color,
    val shadow: Color,
    val isDark: Boolean,
)

private val LightUi = UiColors(
    groupedBackground = LightGroupedBackground,
    card = LightCard,
    elevated = LightCard,
    label = LightLabel,
    secondaryLabel = LightSecondaryLabel,
    tertiaryLabel = LightTertiaryLabel,
    separator = LightSeparator,
    opaqueSeparator = LightOpaqueSeparator,
    fill = LightFill,
    accent = AccentIndigo,
    onAccent = Color.White,
    green = SystemGreen,
    red = SystemRed,
    orange = SystemOrange,
    bar = Color.White.copy(alpha = 0.86f),
    menuGroupGap = Color(0x24787880),
    menu = Color(0xFAFAFAFC),
    shadow = Color.Black,
    isDark = false,
)

private val DarkUi = UiColors(
    groupedBackground = DarkGroupedBackground,
    card = DarkCard,
    elevated = DarkElevated,
    label = DarkLabel,
    secondaryLabel = DarkSecondaryLabel,
    tertiaryLabel = DarkTertiaryLabel,
    separator = DarkSeparator,
    opaqueSeparator = DarkOpaqueSeparator,
    fill = DarkFill,
    accent = AccentIndigoDark,
    onAccent = Color.White,
    green = Color(0xFF30D158),
    red = SystemRedDark,
    orange = Color(0xFFFF9F0A),
    bar = Color(0xFF1C1C1E).copy(alpha = 0.90f),
    menuGroupGap = Color(0x5C000000),
    menu = Color(0xFF2C2C2E),
    shadow = Color.Black,
    isDark = true,
)

val LocalUiColors = staticCompositionLocalOf { LightUi }

/** Tokens del diseño estilo Apple del tema actual. */
val MaterialTheme.ui: UiColors
    @Composable @ReadOnlyComposable get() = LocalUiColors.current

/**
 * Tema de la app.
 * @param darkTheme null = seguir el sistema.
 * @param dynamicColor Android 12+: el acento viene del sistema (Material You); los neutros no cambian.
 */
@Composable
fun EscanerTheme(darkTheme: Boolean? = null, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Color dinámico: SOLO el acento (y sus contenedores) viene del sistema; los neutros (fondo agrupado,
    // tarjetas, separadores) se mantienen para conservar el aspecto.
    val base = if (dark) DarkScheme else LightScheme
    val scheme: ColorScheme = remember(base, useDynamic, context) {
        if (useDynamic) {
        val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        base.copy(
            primary = dyn.primary,
            onPrimary = dyn.onPrimary,
            primaryContainer = dyn.primaryContainer,
            onPrimaryContainer = dyn.onPrimaryContainer,
            secondary = dyn.primary,
            onSecondary = dyn.onPrimary,
            tertiary = dyn.primary,
            onTertiary = dyn.onPrimary,
            tertiaryContainer = dyn.primaryContainer,
            onTertiaryContainer = dyn.onPrimaryContainer,
            inversePrimary = dyn.inversePrimary,
        )
        } else {
            base
        }
    }
    val ui = remember(scheme, dark) {
        val u = if (dark) DarkUi else LightUi
        u.copy(accent = scheme.primary, onAccent = scheme.onPrimary)
    }

    // Iconos de barras de sistema acordes al tema elegido en la app (no solo al del sistema).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalUiColors provides ui) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/**
 * Fuerza la apariencia oscura (tokens [UiColors] oscuros y esquema M3 oscuro) en una parte de la app, con el mismo
 * acento: cámara y editor (estilo app Fotos). Pone los iconos de la barra de estado en claro mientras está visible y
 * restaura el valor anterior al salir.
 */
@Composable
fun ForcedDarkTheme(content: @Composable () -> Unit) {
    val current = MaterialTheme.ui
    val accent = if (current.isDark || current.accent != AccentIndigo) current.accent else AccentIndigoDark
    val ui = remember(accent) { DarkUi.copy(accent = accent, onAccent = Color.White) }
    val scheme = remember(accent) { DarkScheme.copy(primary = accent, onPrimary = Color.White) }
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        DisposableEffect(Unit) {
            val window = context.findActivity()?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val prevStatus = controller?.isAppearanceLightStatusBars
            val prevNav = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                if (prevStatus != null) controller.isAppearanceLightStatusBars = prevStatus
                if (prevNav != null) controller.isAppearanceLightNavigationBars = prevNav
            }
        }
    }
    CompositionLocalProvider(LocalUiColors provides ui) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
