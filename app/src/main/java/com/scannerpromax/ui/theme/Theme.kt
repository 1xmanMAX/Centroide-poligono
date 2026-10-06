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
 * Colores extra de marca que Material3 no cubre (degradados, estados, bordes sutiles).
 * Acceso: `MaterialTheme.brand`.
 */
@Immutable
data class BrandColors(
    val gradientStart: Color,
    val gradientEnd: Color,
    val accent: Color,
    val glow: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Borde fino para tarjetas / superficies elevadas. */
    val cardBorder: Color,
    /** Fondo de tarjeta ligeramente elevado respecto al fondo. */
    val card: Color,
    val isDark: Boolean,
) {
    // Los degradados se crean UNA vez por tema (antes un getter creaba un Brush nuevo en cada acceso, en cada
    // recomposición de cada tarjeta/botón: basura y comparaciones de igualdad fallidas que impedían saltarse
    // recomposiciones).

    /** Degradado principal (violeta -> turquesa) en diagonal. */
    val gradient: Brush = Brush.linearGradient(listOf(gradientStart, gradientEnd))

    /** Degradado horizontal para textos / barras. */
    val horizontalGradient: Brush = Brush.horizontalGradient(listOf(gradientStart, gradientEnd))

    /** Halo suave para fondos de cabecera. */
    val backdrop: Brush = Brush.verticalGradient(
        listOf(gradientStart.copy(alpha = if (isDark) 0.22f else 0.12f), Color.Transparent),
    )
}

private val DarkBrand = BrandColors(
    gradientStart = BrandViolet,
    gradientEnd = BrandTeal,
    accent = BrandTeal,
    glow = BrandViolet.copy(alpha = 0.45f),
    success = SuccessGreen,
    warning = WarningAmber,
    danger = DangerRed,
    cardBorder = Color.White.copy(alpha = 0.07f),
    card = NightSurface,
    isDark = true,
)

private val LightBrand = BrandColors(
    gradientStart = BrandViolet,
    gradientEnd = BrandTealDeep,
    accent = BrandTealDeep,
    glow = BrandViolet.copy(alpha = 0.30f),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    danger = Color(0xFFE11D48),
    cardBorder = Color(0xFF12152A).copy(alpha = 0.07f),
    card = DaySurface,
    isDark = false,
)

val LocalBrandColors = staticCompositionLocalOf { DarkBrand }

/** Colores de marca del tema actual. */
val MaterialTheme.brand: BrandColors
    @Composable @ReadOnlyComposable get() = LocalBrandColors.current

/**
 * Tema de la app.
 * @param darkTheme null = seguir el sistema.
 * @param dynamicColor usa Material You (Android 12+); el degradado de marca se adapta a esos colores.
 */
@Composable
fun EscanerTheme(darkTheme: Boolean? = null, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val scheme: ColorScheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    val brand = remember(scheme, dark, useDynamic) {
        val base = if (dark) DarkBrand else LightBrand
        if (!useDynamic) base else base.copy(
            gradientStart = scheme.primary,
            gradientEnd = scheme.tertiary,
            accent = scheme.tertiary,
            glow = scheme.primary.copy(alpha = 0.4f),
            card = scheme.surfaceContainerLow,
        )
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

    CompositionLocalProvider(LocalBrandColors provides brand) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
