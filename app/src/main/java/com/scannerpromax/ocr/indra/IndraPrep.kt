package com.scannerpromax.ocr.indra

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Página preparada para Indra (lo que ve el OCR, no el usuario): gris de la página SOLO con geometría (o del filtro
 * "Color original") con el fondo normalizado (papel → blanco, sin sombras ni degradados) y un enfoque suave.
 *
 * Indra lee "tinta" = 1 − gris/255: un papel gris o una sombra son tinta tenue para el detector y el reconocedor.
 * Banco (54 imágenes con verdad, tubería real de la app, notes_indra_afinado.md; CER / WER / %palabras):
 *  - filtro Texto resaltado tal cual 29,6 / 43,6 / 75,3; Blanco y negro 30,0 / 45,5 / 70,4; solo geometría 26,9 / 41,6 / 74,0
 *  - solo geometría + [prepare] 27,0 / 40,7 / 76,2 (español impreso MERIT 73,9 → 91,9 % palabras; GNHK 48,4 → 52,0)
 * Probado y descartado: CLAHE, gamma, niveles/umbral suave, ampliar/reducir a una altura de línea objetivo, quitar la
 * cuadrícula (iguales o peores).
 */
object IndraPrep {
    /** Lado (px) al que se estima el papel. */
    const val BG_SIDE = 512
    /** Diámetro del cierre que borra la tinta al estimar el papel (fracción del lado reducido). */
    const val BG_KERNEL = 1.0 / 30
    const val SHARP_AMOUNT = 0.6
    const val SHARP_SIGMA = 1.0

    /** Gris preparado (nuevo; [g] no cambia). */
    fun prepare(g: Gray): Gray {
        val src = g.toMat()
        try {
            val norm = normalizeBackground(src)
            val out = unsharp(norm)
            norm.release()
            return Gray.fromMat(out).also { out.release() }
        } finally {
            src.release()
        }
    }

    /** gris / papel × 255 (papel = cierre morfológico + mediana + suavizado a [BG_SIDE] px). */
    fun normalizeBackground(src: Mat): Mat {
        val w = src.cols(); val h = src.rows()
        val s = BG_SIDE.toDouble() / max(w, h)
        val small = Mat()
        if (s < 1) Imgproc.resize(src, small, Size(max(1, (w * s).roundToInt()).toDouble(), max(1, (h * s).roundToInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        else src.copyTo(small)
        val k = max(5, (max(small.cols(), small.rows()) * BG_KERNEL).toInt() or 1)
        val bg = Mat()
        val se = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.morphologyEx(small, bg, Imgproc.MORPH_CLOSE, se)
        se.release(); small.release()
        Imgproc.medianBlur(bg, bg, 5)
        Imgproc.GaussianBlur(bg, bg, Size(0.0, 0.0), k / 3.0)
        val full = Mat()
        if (s < 1) Imgproc.resize(bg, full, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR) else bg.copyTo(full)
        bg.release()
        val num = Mat(); val den = Mat()
        src.convertTo(num, CvType.CV_32F)
        full.convertTo(den, CvType.CV_32F)
        full.release()
        Core.max(den, org.opencv.core.Scalar(1.0), den)
        val q = Mat()
        Core.divide(num, den, q, 255.0)
        num.release(); den.release()
        val out = Mat()
        q.convertTo(out, CvType.CV_8U) // satura a [0, 255]
        q.release()
        return out
    }

    /** Máscara de enfoque: (1 + a)·g − a·desenfoque(g). */
    fun unsharp(src: Mat, amount: Double = SHARP_AMOUNT, sigma: Double = SHARP_SIGMA): Mat {
        val b = Mat()
        Imgproc.GaussianBlur(src, b, Size(0.0, 0.0), sigma)
        val out = Mat()
        Core.addWeighted(src, 1 + amount, b, -amount, 0.0, out)
        b.release()
        return out
    }
}
