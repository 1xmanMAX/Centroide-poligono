package com.scannerpromax.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = FontFamily.Default

/** Jerarquía tipográfica: titulares compactos y gruesos, cuerpo legible, etiquetas en semi-negrita. */
val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.Black, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 48.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.75).sp),
    headlineLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.05.sp),
    bodyLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)
