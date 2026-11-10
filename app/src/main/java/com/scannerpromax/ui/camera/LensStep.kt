package com.scannerpromax.ui.camera

import com.scannerpromax.domain.ScanMode
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.imaging.HeavyWork
import com.scannerpromax.imaging.LensFix
import com.scannerpromax.imaging.SensorLens
import java.io.File

/**
 * «Corregir distorsión de la lente»: corrige la foto recién capturada (o fusionada) ANTES del flujo del modo (detección,
 * recorte, perspectiva), con la calibración de la cámara ([SensorLens]) o, si no la hay, con el autocalibrado del
 * documento. Corre con el permiso pesado único ([HeavyWork]); no bloquea la cámara.
 */
internal object LensStep {

    /** Opciones según el modo: el autocalibrado sólo en hojas planas (en libro la curvatura es de la hoja, no de la lente). */
    fun options(mode: ScanMode, tier: DeviceTier): LensFix.Options = LensFix.Options(
        geometry = true,
        selfCal = mode == ScanMode.DOCUMENT || mode == ScanMode.RECEIPT || mode == ScanMode.WHITEBOARD,
        chroma = true,
        maxPixels = maxPixels(tier),
    )

    /** Foto + salida en BGR (3 B/px cada una) dentro del presupuesto de memoria del equipo (ARGB, 4 B/px). */
    fun maxPixels(tier: DeviceTier): Int = (tier.maxWorkingPixels.toLong() * 4 / 3).coerceIn(4_000_000L, 20_000_000L).toInt()

    suspend fun apply(file: File, lens: SensorLens?, mode: ScanMode, enabled: Boolean, tier: DeviceTier): File {
        if (!enabled || !file.exists()) return file
        HeavyWork.run { LensFix.processFile(file, lens, options(mode, tier)) }
        return file
    }
}
