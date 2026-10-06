package com.scannerpromax.imaging

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * Perfil del dispositivo para escalar el trabajo: celulares de gama baja procesan a ~4 MP,
 * gama media a 8 MP y gama alta a 12 MP.
 */
object DeviceProfiler {

    fun profile(context: Context): DeviceTier {
        val cores = max(1, Runtime.getRuntime().availableProcessors())
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return DeviceTier(isLowRam = true, maxWorkingPixels = 4_000_000, cores = cores)

        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        val memClassMb = am.memoryClass

        val isLowRam = am.isLowRamDevice || totalGb < 2.6 || memClassMb <= 128 ||
            (cores <= 4 && totalGb < 3.5)

        var maxPixels = when {
            isLowRam -> 4_000_000
            totalGb < 4.5 || cores <= 4 -> 8_000_000
            else -> 12_000_000
        }
        // Android 7.x guarda los píxeles del Bitmap en el heap de Java: limitar según el heap disponible
        // (~6 copias de 4 bytes/píxel en el peor momento de la tubería).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val heapBytes = memClassMb.toLong() * 1024 * 1024
            val byHeap = (heapBytes / (4L * 6)).toInt()
            maxPixels = min(maxPixels, max(2_000_000, byHeap))
        }
        return DeviceTier(isLowRam = isLowRam, maxWorkingPixels = maxPixels, cores = cores)
    }

    /** Lado largo de trabajo para vistas previas según el tier. */
    fun previewSide(tier: DeviceTier): Int = if (tier.isLowRam) 960 else 1200

    /** ¿Se pueden usar filtros costosos (fastNlMeans, bilateral grande)? */
    fun isHighEnd(tier: DeviceTier): Boolean = !tier.isLowRam && tier.cores >= 6 && tier.maxWorkingPixels >= 8_000_000
}
