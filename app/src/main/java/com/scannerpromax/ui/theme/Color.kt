package com.scannerpromax.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------------------------
// Paleta estilo Apple (rediseño 2026-10): fondo agrupado gris claro, tarjetas blancas, UN acento índigo y
// grises de sistema. Sin degradados ni halos. Modo oscuro: negro puro + tarjetas #1C1C1E.
// ---------------------------------------------------------------------------------------------

// Acento
val AccentIndigo = Color(0xFF4F46E5)
val AccentIndigoDark = Color(0xFF7C74FF)

// Claro
val LightGroupedBackground = Color(0xFFF2F2F7)
val LightCard = Color(0xFFFFFFFF)
val LightLabel = Color(0xFF1C1C1E)
val LightSecondaryLabel = Color(0xFF6E6E73)
val LightTertiaryLabel = Color(0xFFAEAEB2)
val LightSeparator = Color(0xFFE5E5EA)
val LightOpaqueSeparator = Color(0xFFC7C7CC)
val LightFill = Color(0xFFE3E3E8)
val LightElevated = Color(0xFFEFEFF4)

// Oscuro
val DarkGroupedBackground = Color(0xFF000000)
val DarkCard = Color(0xFF1C1C1E)
val DarkLabel = Color(0xFFFFFFFF)
val DarkSecondaryLabel = Color(0x99EBEBF5) // #EBEBF5 al 60 %
val DarkTertiaryLabel = Color(0x4DEBEBF5) // #EBEBF5 al 30 %
val DarkSeparator = Color(0xFF38383A)
val DarkOpaqueSeparator = Color(0xFF48484A)
val DarkFill = Color(0xFF2C2C2E)
val DarkElevated = Color(0xFF2C2C2E)

// Colores de sistema (iconos de Ajustes, estados)
val SystemGreen = Color(0xFF34C759)
val SystemRed = Color(0xFFFF3B30)
val SystemRedDark = Color(0xFFFF453A)
val SystemOrange = Color(0xFFFF9500)
val SystemBlue = Color(0xFF007AFF)
val SystemPurple = Color(0xFFAF52DE)
val SystemPink = Color(0xFFFF2D55)
val SystemIndigo = Color(0xFF5856D6)
val SystemTeal = Color(0xFF30B0C7)
val SystemGray = Color(0xFF8E8E93)

// Compatibilidad con pantallas aún no rediseñadas.
val SuccessGreen = SystemGreen
val WarningAmber = Color(0xFFFF9F0A)
val DangerRed = SystemRed

internal val LightScheme = lightColorScheme(
    primary = AccentIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E5FB),
    onPrimaryContainer = Color(0xFF2B2591),
    secondary = AccentIndigo,
    onSecondary = Color.White,
    secondaryContainer = LightFill,
    onSecondaryContainer = LightLabel,
    tertiary = AccentIndigo,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E5FB),
    onTertiaryContainer = Color(0xFF2B2591),
    error = SystemRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E3),
    onErrorContainer = Color(0xFF5F0B05),
    background = LightGroupedBackground,
    onBackground = LightLabel,
    surface = LightGroupedBackground,
    onSurface = LightLabel,
    surfaceVariant = LightFill,
    onSurfaceVariant = LightSecondaryLabel,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color.White,
    inversePrimary = AccentIndigoDark,
    outline = LightOpaqueSeparator,
    outlineVariant = LightSeparator,
    scrim = Color.Black,
    surfaceBright = LightCard,
    surfaceDim = LightFill,
    surfaceContainerLowest = LightCard,
    surfaceContainerLow = LightCard,
    surfaceContainer = LightCard,
    surfaceContainerHigh = LightElevated,
    surfaceContainerHighest = LightFill,
)

internal val DarkScheme = darkColorScheme(
    primary = AccentIndigoDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E2A6B),
    onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = AccentIndigoDark,
    onSecondary = Color.White,
    secondaryContainer = DarkFill,
    onSecondaryContainer = DarkLabel,
    tertiary = AccentIndigoDark,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF2E2A6B),
    onTertiaryContainer = Color(0xFFE2E0FF),
    error = SystemRedDark,
    onError = Color.White,
    errorContainer = Color(0xFF5C1210),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DarkGroupedBackground,
    onBackground = DarkLabel,
    surface = DarkGroupedBackground,
    onSurface = DarkLabel,
    surfaceVariant = DarkFill,
    onSurfaceVariant = DarkSecondaryLabel,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = LightLabel,
    inversePrimary = AccentIndigo,
    outline = DarkOpaqueSeparator,
    outlineVariant = DarkSeparator,
    scrim = Color.Black,
    surfaceBright = Color(0xFF3A3A3C),
    surfaceDim = DarkGroupedBackground,
    surfaceContainerLowest = DarkCard,
    surfaceContainerLow = DarkCard,
    surfaceContainer = DarkElevated,
    surfaceContainerHigh = DarkElevated,
    surfaceContainerHighest = Color(0xFF3A3A3C),
)
