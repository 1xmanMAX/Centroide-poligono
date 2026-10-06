package com.scannerpromax.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.scannerpromax.imaging.DeviceTier

/**
 * Perfil de fluidez de la interfaz. Se calcula una vez al abrir la actividad y se expone con [LocalPerf].
 *
 * - [lowEnd]: gama baja (poca RAM o ≤4 núcleos). Se simplifican efectos caros (halos infinitos, sombras grandes,
 *   fundidos a pantalla completa) y se usan miniaturas más pequeñas.
 * - [reduceMotion]: el usuario activó "Quitar animaciones" (ANIMATOR_DURATION_SCALE = 0): sin animaciones.
 */
@Immutable
data class PerfConfig(
    val lowEnd: Boolean = false,
    val reduceMotion: Boolean = false,
    /** Lado (px) al que se decodifican las miniaturas de las listas. */
    val thumbPx: Int = 360,
) {
    /** Efectos decorativos (animaciones infinitas, halos, sombras grandes): solo en gama media/alta. */
    val richEffects: Boolean get() = !lowEnd && !reduceMotion

    /** Duración adaptada: 0 sin animaciones, ~70 % en gama baja (menos frames en los que puede haber jank). */
    fun duration(ms: Int): Int = when {
        reduceMotion -> 0
        lowEnd -> (ms * 0.7f).toInt()
        else -> ms
    }

    companion object {
        fun from(context: Context, tier: DeviceTier): PerfConfig {
            val lowEnd = tier.isLowRam || tier.cores <= 4
            val scale = runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
            return PerfConfig(
                lowEnd = lowEnd,
                reduceMotion = scale == 0f,
                thumbPx = if (lowEnd) 280 else 360,
            )
        }
    }
}

val LocalPerf = staticCompositionLocalOf { PerfConfig() }
