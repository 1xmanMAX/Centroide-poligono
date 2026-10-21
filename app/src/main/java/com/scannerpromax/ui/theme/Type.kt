package com.scannerpromax.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.scannerpromax.R

/**
 * Inter (OFL 1.1, third_party/inter): 5 pesos estáticos reducidos a latín (~134 KB comprimidos en el APK).
 * Los caracteres fuera del subconjunto los dibuja la fuente del sistema.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

private val Base = Inter

/**
 * Jerarquía estilo iOS:
 *  - displaySmall = título grande (34 sp ExtraBold, -0,8) de Inicio y Ajustes.
 *  - headlineLarge = título de documento (30 sp ExtraBold, -0,6).
 *  - titleLarge = cabecera de sección ("Recientes", 20 sp Bold).
 *  - titleMedium = títulos de barra / botones (17 sp SemiBold).
 *  - titleSmall = nombre en tarjetas (15 sp SemiBold).
 *  - bodyLarge = filas de lista (16 sp); bodyMedium 15 sp; bodySmall = metadatos (13 sp).
 */
val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 54.sp, letterSpacing = (-1.2).sp),
    displayMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    bodyMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    bodySmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontFamily = Base, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    labelMedium = TextStyle(fontFamily = Base, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontFamily = Base, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
)
