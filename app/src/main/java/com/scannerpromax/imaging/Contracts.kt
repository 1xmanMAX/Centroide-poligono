package com.scannerpromax.imaging

import android.graphics.Bitmap
import com.scannerpromax.domain.Quad

/** Resultado de detección de documento. [confidence] 0..1. Coordenadas en píxeles de la imagen analizada. */
data class DetectionResult(val quad: Quad, val confidence: Float, val frameWidth: Int, val frameHeight: Int)

/** Evaluación de calidad de captura para guiar al usuario. */
data class QualityReport(
    val sharpness: Float,     // 0..1 (varianza del Laplaciano normalizada)
    val brightness: Float,    // 0..1
    val glare: Float,         // 0..1 fracción de píxeles quemados
    val isBlurry: Boolean,
    val isTooDark: Boolean,
    val hasGlare: Boolean,
)

/** Capacidades del dispositivo, para escalar el trabajo en celulares de bajos recursos. */
data class DeviceTier(val isLowRam: Boolean, val maxWorkingPixels: Int, val cores: Int)

/** Resultado de dividir un libro abierto en dos páginas. */
data class BookSplit(val left: Bitmap, val right: Bitmap, val gutterX: Int)

typealias ProgressCallback = (Float) -> Unit
