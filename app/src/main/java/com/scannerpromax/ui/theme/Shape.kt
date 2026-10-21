package com.scannerpromax.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Radios estilo iOS: celdas y grupos 10–12, tarjetas 14, hojas 14 (arriba), cápsulas 26+. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** Grupos de listas agrupadas (InsetGroup). */
val GroupShape = RoundedCornerShape(12.dp)

/** Tarjetas de documento. */
val CardShape = RoundedCornerShape(14.dp)

/** Hojas inferiores (solo esquinas superiores). */
val SheetShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)

/** Forma tipo "píldora"/cápsula para chips y botones principales. */
val PillShape = RoundedCornerShape(percent = 50)
