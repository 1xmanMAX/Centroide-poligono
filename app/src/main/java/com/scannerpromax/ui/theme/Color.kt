package com.scannerpromax.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------------------------
// Paleta de marca ESCÁNER PRO MAX: violeta eléctrico + turquesa sobre azul noche profundo.
// ---------------------------------------------------------------------------------------------

val BrandViolet = Color(0xFF6C5CFF)
val BrandVioletLight = Color(0xFF9D92FF)
val BrandVioletDeep = Color(0xFF4B3BE0)
val BrandTeal = Color(0xFF00D1B2)
val BrandTealLight = Color(0xFF5CF2D9)
val BrandTealDeep = Color(0xFF00A88F)
val BrandPink = Color(0xFFFF5C9A)
val BrandAmber = Color(0xFFFFB547)

val NightBg = Color(0xFF0B0F1A)
val NightSurface = Color(0xFF121726)
val NightSurface2 = Color(0xFF181E30)
val NightSurface3 = Color(0xFF1F263B)
val NightSurface4 = Color(0xFF283049)
val NightOutline = Color(0xFF2E3650)
val NightOutlineVariant = Color(0xFF232A40)
val NightOnSurface = Color(0xFFE9ECF8)
val NightOnSurfaceVariant = Color(0xFFA3AAC4)

val DayBg = Color(0xFFF6F7FC)
val DaySurface = Color(0xFFFFFFFF)
val DaySurface2 = Color(0xFFF0F1F9)
val DaySurface3 = Color(0xFFE9EBF6)
val DaySurface4 = Color(0xFFE1E4F2)
val DayOutline = Color(0xFFCBD0E3)
val DayOutlineVariant = Color(0xFFE2E5F1)
val DayOnSurface = Color(0xFF12152A)
val DayOnSurfaceVariant = Color(0xFF5B6180)

val SuccessGreen = Color(0xFF22C55E)
val WarningAmber = Color(0xFFF59E0B)
val DangerRed = Color(0xFFFF4D67)

internal val DarkScheme = darkColorScheme(
    primary = BrandVioletLight,
    onPrimary = Color(0xFF1A0E73),
    primaryContainer = Color(0xFF3B2FB8),
    onPrimaryContainer = Color(0xFFE5E1FF),
    secondary = BrandTeal,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF00524A),
    onSecondaryContainer = Color(0xFF8FFBE6),
    tertiary = BrandPink,
    onTertiary = Color(0xFF5C0A2E),
    tertiaryContainer = Color(0xFF7A1745),
    onTertiaryContainer = Color(0xFFFFD9E4),
    error = DangerRed,
    onError = Color(0xFF4A0010),
    errorContainer = Color(0xFF6B0F22),
    onErrorContainer = Color(0xFFFFDADF),
    background = NightBg,
    onBackground = NightOnSurface,
    surface = NightBg,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurface3,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceTint = BrandViolet,
    inverseSurface = NightOnSurface,
    inverseOnSurface = NightBg,
    inversePrimary = BrandViolet,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    scrim = Color.Black,
    surfaceBright = NightSurface4,
    surfaceDim = NightBg,
    surfaceContainerLowest = Color(0xFF080B14),
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface2,
    surfaceContainerHigh = NightSurface3,
    surfaceContainerHighest = NightSurface4,
)

internal val LightScheme = lightColorScheme(
    primary = BrandViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E2FF),
    onPrimaryContainer = Color(0xFF1E1180),
    secondary = BrandTealDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC6F7EE),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Color(0xFFE0447F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E4),
    onTertiaryContainer = Color(0xFF5C0A2E),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFE0E6),
    onErrorContainer = Color(0xFF5F0016),
    background = DayBg,
    onBackground = DayOnSurface,
    surface = DayBg,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurface3,
    onSurfaceVariant = DayOnSurfaceVariant,
    surfaceTint = BrandViolet,
    inverseSurface = Color(0xFF1C2033),
    inverseOnSurface = Color(0xFFF1F2FA),
    inversePrimary = BrandVioletLight,
    outline = DayOutline,
    outlineVariant = DayOutlineVariant,
    scrim = Color.Black,
    surfaceBright = DaySurface,
    surfaceDim = DaySurface4,
    surfaceContainerLowest = DaySurface,
    surfaceContainerLow = DaySurface,
    surfaceContainer = DaySurface2,
    surfaceContainerHigh = DaySurface3,
    surfaceContainerHighest = DaySurface4,
)
