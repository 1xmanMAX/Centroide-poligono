package com.scannerpromax.imaging

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * Perfil del dispositivo para escalar el trabajo SIN sacrificar la resolución del escaneo.
 *
 * [DeviceTier.maxWorkingPixels] es el presupuesto de píxeles con el que se decodifica el ORIGINAL para generar el
 * procesado (el original en disco siempre conserva la resolución completa de la cámara):
 *  - Gama alta (≥ 7 GB de RAM y ≥ 6 núcleos, p. ej. Galaxy S24 Ultra): 20 MP. Una foto de 12 MP (4000x3000) o de
 *    16 MP se procesa ENTERA, sin reducir.
 *  - Gama alta "ligera" (4.5-7 GB, ≥ 6 núcleos): 16 MP.
 *  - Gama media (< 4.5 GB o ≤ 4 núcleos): 12.6 MP, justo por encima de los 12-12.5 MP de los sensores habituales
 *    (4000x3000, 4032x3024, 4080x3072): la foto completa cabe sin reescalar.
 *  - Gama baja ("isLowRam"): 8 MP, nunca menos para documentos (≈ 300 ppp en A4). Los Mats de OpenCV van en
 *    memoria nativa y desde Android 8 también los píxeles de los Bitmap, así que el límite real es la RAM del
 *    sistema, no el heap de Java. El repositorio reintenta el render con menos píxeles si aun así se queda sin
 *    memoria (red de seguridad; ver DocumentRepository.renderFromOriginal).
 *  - Android 7.x (píxeles del Bitmap en el heap de Java): se acota por el heap grande del proceso
 *    (android:largeHeap) con ~6 copias de 4 bytes/píxel en el peor momento; solo ahí puede quedar por debajo de
 *    8 MP (nunca de 4 MP).
 */
object DeviceProfiler {

    /** Mínimo para documentos en cualquier equipo con Android 8+. */
    const val MIN_DOCUMENT_PIXELS = 8_000_000

    fun profile(context: Context): DeviceTier {
        val cores = max(1, Runtime.getRuntime().availableProcessors())
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return DeviceTier(isLowRam = true, maxWorkingPixels = MIN_DOCUMENT_PIXELS, cores = cores)

        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        val memClassMb = am.memoryClass

        val isLowRam = am.isLowRamDevice || totalGb < 2.6 || memClassMb <= 128 ||
            (cores <= 4 && totalGb < 3.5)

        var maxPixels = when {
            isLowRam -> MIN_DOCUMENT_PIXELS
            totalGb < 4.5 || cores <= 4 -> 12_600_000
            totalGb < 7.0 || cores < 6 -> 16_000_000
            else -> 20_000_000
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Android 7.x: los píxeles del Bitmap viven en el heap de Java (largeHeap en el manifiesto).
            val heapBytes = max(memClassMb, am.largeMemoryClass).toLong() * 1024 * 1024
            val byHeap = (heapBytes / (4L * 6)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            maxPixels = min(maxPixels, max(4_000_000, byHeap))
        }
        return DeviceTier(isLowRam = isLowRam, maxWorkingPixels = maxPixels, cores = cores)
    }

    /** Lado largo de trabajo para vistas previas según el tier (la UI lo ajusta además a la pantalla). */
    fun previewSide(tier: DeviceTier): Int = if (tier.isLowRam) 960 else 1200

    /**
     * Lado largo de la vista previa del editor acorde a la pantalla: la imagen ocupa el ancho y ~60 % del alto,
     * así que basta con max(lado corto, 0.62 x lado largo) de la pantalla (p. ej. 1450 px en FHD+, 1930 px en
     * QHD+). Acotado para que el refinado siga siendo rápido: 900-1280 px en gama baja y 1200-2048 px en el resto.
     */
    fun previewSideForScreen(tier: DeviceTier, screenW: Int, screenH: Int): Int {
        val short = min(screenW, screenH)
        val long = max(screenW, screenH)
        val wanted = max(short, (long * 0.62f).toInt())
        return if (tier.isLowRam) wanted.coerceIn(900, 1280) else wanted.coerceIn(1200, 2048)
    }

    /** ¿Se pueden usar filtros costosos (fastNlMeans, bilateral grande)? */
    fun isHighEnd(tier: DeviceTier): Boolean = !tier.isLowRam && tier.cores >= 6 && tier.maxWorkingPixels >= 8_000_000
}
