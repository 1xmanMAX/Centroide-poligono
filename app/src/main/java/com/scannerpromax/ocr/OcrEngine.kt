package com.scannerpromax.ocr

import android.content.Context
import android.graphics.Bitmap
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.imaging.DeviceTier
import com.scannerpromax.ocr.indra.IndraEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * OCR de la app: **Indra** (ocr/indra, ONNX en el procesador) es el ÚNICO motor de reconocimiento. Sus pesos (~25 MB) y
 * ONNX Runtime (~13 MB) no van en el APK: se descargan solos en el primer arranque
 * ([com.scannerpromax.ocr.indra.IndraProvisioner]). Mientras no estén, [recognize] lanza [OcrUnavailableException] y la
 * página queda PENDIENTE de reconocer (el repositorio la vuelve a encolar al terminar la descarga), no "fallida".
 *
 * Indra hace su propio preproceso (gris, detección de líneas a 1024 px, enderezado, orientación, orden de lectura y
 * modelo de lenguaje para las líneas dudosas): aquí solo se serializa (un OCR a la vez en el proceso).
 * Coordenadas del resultado en píxeles del bitmap recibido.
 */
class OcrEngine(@Suppress("unused") private val context: Context) {

    constructor(context: Context, tier: DeviceTier) : this(context) {
        deviceTier = tier
    }

    @Volatile private var deviceTier: DeviceTier? = null

    /** Motor Indra (lo asigna el contenedor). null = sin OCR (tests, contenedor sin modelos). */
    @Volatile var indra: IndraEngine? = null

    /** Asigna el perfil del dispositivo (lo llama el repositorio si el motor se creó sin él). */
    fun setDeviceTier(tier: DeviceTier) {
        deviceTier = tier
    }

    /** ¿Está Indra descargado (y no falló al cargar)? */
    fun isReady(): Boolean = indra?.isReady() == true

    /**
     * Resultado ya calculado si el llamador cancela (compatibilidad con el guardado parcial: Indra hace una sola
     * pasada, así que solo se rellena al terminar).
     */
    class Partial {
        @Volatile var result: OcrResult? = null
    }

    suspend fun recognize(bitmap: Bitmap): OcrResult = recognize(bitmap, null)

    suspend fun recognize(bitmap: Bitmap, filter: FilterType?): OcrResult = recognize(bitmap, filter, false, null)

    /**
     * OCR de [bitmap] (no se recicla). [filter] y [lightweight] se aceptan por compatibilidad: Indra trabaja igual en
     * todos los casos (su detector reduce a 1024 px y relee los recortes de cada línea). Lanza
     * [OcrUnavailableException] si Indra aún no está descargado o no se pudo cargar, y [OcrFailedException] si falló al
     * leer. No se interrumpe a mitad (ONNX Runtime no es cancelable): la cancelación se comprueba al terminar.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun recognize(bitmap: Bitmap, filter: FilterType?, lightweight: Boolean, partial: Partial?): OcrResult =
        recognize(bitmap, bitmap.width, bitmap.height, partial)

    /**
     * OCR de [bitmap] con las cajas en px de una imagen de [outW]×[outH]: [bitmap] puede ser otra versión de la
     * misma página (la de solo geometría, sin el filtro que ve el usuario: [com.scannerpromax.ocr.indra.IndraPrep])
     * a otra escala; el resultado se expresa sobre la imagen procesada que se muestra.
     */
    suspend fun recognize(bitmap: Bitmap, outW: Int, outH: Int, partial: Partial? = null): OcrResult = Gate.lock.withLock {
        withContext(Dispatchers.Default) {
            require(!bitmap.isRecycled) { "Bitmap reciclado" }
            val engine = indra?.takeIf { it.isReady() }
                ?: throw OcrUnavailableException("El lector de texto (Indra) aún no está descargado")
            val r = engine.recognize(bitmap, outW, outH)
                ?: throw if (engine.isReady()) OcrFailedException("Indra no pudo leer la página")
                else OcrUnavailableException("No se pudo cargar el lector de texto (Indra)")
            partial?.result = r
            currentCoroutineContext().ensureActive()
            r
        }
    }

    /** Libera los modelos de Indra (se recargan solos al siguiente OCR). */
    fun close() {
        runCatching { indra?.close() }
    }

    /** Un OCR a la vez en todo el proceso: en gama baja dos reconocimientos simultáneos agotan RAM y CPU. */
    private object Gate {
        val lock = Mutex()
    }
}

/** El OCR no está disponible todavía (Indra descargándose o sin red): la página queda pendiente, no fallida. */
class OcrUnavailableException(message: String) : Exception(message)

/** Indra está cargado pero no pudo leer la página. */
class OcrFailedException(message: String) : Exception(message)
