package com.scannerpromax.ocr.indra

import android.graphics.Bitmap
import android.util.Log
import com.scannerpromax.ai.ModelCatalog
import com.scannerpromax.ai.ModelManager
import com.scannerpromax.ai.parts.OrtNative
import com.scannerpromax.domain.OcrResult
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 Indra, el ÚNICO OCR de la app: carga perezosa de [IndraOcr] desde la descarga ([ModelCatalog.FAST_READER], que se
 * baja sola: [IndraProvisioner]) y conversión Bitmap → gris → [OcrResult]. Si el modelo no está o algo falla devuelve
 * null y el llamador ([com.scannerpromax.ocr.OcrEngine]) deja la página pendiente.
 */
class IndraEngine(private val modelsProvider: () -> ModelManager, private val threads: Int) : Closeable {

    private val models: ModelManager get() = modelsProvider()

    @Volatile private var ocr: IndraOcr? = null
    @Volatile private var failed = false

    fun isReady(): Boolean = !failed && models.isReady(ModelCatalog.FAST_READER)

    @Synchronized
    private fun engine(): IndraOcr? {
        ocr?.let { return it }
        if (!isReady()) return null
        val model = ModelCatalog.FAST_READER
        // ONNX Runtime nativo: el del propio modelo (o el de la Maquetación, si se cargó antes en este proceso).
        if (!OrtNative.ensureLoaded(models.storage.nativeLibDir(model)) &&
            !OrtNative.ensureLoaded(models.storage.nativeLibDir(ModelCatalog.PAGE_LAYOUT))
        ) {
            Log.w(TAG, "ONNX Runtime no disponible: ${OrtNative.lastError}")
            return null
        }
        return try {
            val t0 = System.currentTimeMillis()
            IndraOcr(models.storage.dir(model), threads, APP_ORIENT_MIN).also {
                ocr = it
                Log.i(TAG, "Indra cargado en ${System.currentTimeMillis() - t0} ms (LM: ${it.hasLm})")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "no se pudo cargar Indra", t)
            failed = true
            null
        }
    }

    /** OCR de [bitmap] (no se recicla); null si no está disponible o falla. Coordenadas en px de [bitmap]. */
    @Synchronized
    fun recognize(bitmap: Bitmap): OcrResult? {
        val e = engine() ?: return null
        return try {
            val (gray, scale) = toGray(bitmap, MAX_SIDE)
            val t0 = System.currentTimeMillis()
            val page = e.run(gray)
            Log.i(TAG, "Indra ${gray.w}x${gray.h}: ${page.lines.size} líneas, ${page.blocks.size} bloques, giro ${page.rotation}, " +
                "inclinación ${"%.1f".format(page.skew)}, ${System.currentTimeMillis() - t0} ms ${page.timings}")
            IndraResult.toOcrResult(page, bitmap.width, bitmap.height, scale)
        } catch (t: Throwable) {
            Log.w(TAG, "Indra falló", t)
            null
        }
    }

    /** Tras borrar/volver a descargar el modelo: olvida un fallo de carga anterior y libera lo cargado. */
    @Synchronized
    fun reset() {
        failed = false
        close()
    }

    /** Libera modelos y LM (~60 MB); se recargan solos al siguiente OCR. Espera a que termine uno en curso. */
    @Synchronized
    override fun close() {
        runCatching { ocr?.close() }
        ocr = null
    }

    companion object {
        private const val TAG = "IndraEngine"
        /** Lado largo máximo (px) que recibe Indra: el detector trabaja a 1024 y los recortes no ganan más allá. */
        const val MAX_SIDE = 4000
        /**
         * Umbral de giro de orient.onnx en la app: 0,9 en vez de 0,95 (Python). Banco (132 págs, scripts/orient_thr.py):
         * a 0,9 giran 4 páginas, todas bien (añade la tabla1 del usuario, p = 0,948); a 0,8 ya entra un giro erróneo.
         */
        const val APP_ORIENT_MIN = 0.9f

        /** Bitmap → gris (luminancia BT.601 en punto fijo, como cv2.COLOR_BGR2GRAY); (gris, px originales por px). */
        fun toGray(src: Bitmap, maxSide: Int): Pair<Gray, Float> {
            val s = min(1f, maxSide.toFloat() / max(src.width, src.height))
            val w = max(1, (src.width * s).roundToInt()); val h = max(1, (src.height * s).roundToInt())
            val b = if (s < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
            try {
                val out = ByteArray(w * h)
                val row = IntArray(w)
                for (y in 0 until h) {
                    b.getPixels(row, 0, w, 0, y, w, 1)
                    for (x in 0 until w) out[y * w + x] = luma(row[x]).toByte()
                }
                return Gray(w, h, out) to (src.width.toFloat() / w)
            } finally {
                if (b !== src) b.recycle()
            }
        }

        fun luma(argb: Int): Int {
            val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val bl = argb and 0xFF
            return (r * 4899 + g * 9617 + bl * 1868 + 8192) shr 14
        }
    }
}
