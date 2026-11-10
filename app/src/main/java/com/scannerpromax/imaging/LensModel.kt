package com.scannerpromax.imaging

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Modelos de DISTORSIÓN GEOMÉTRICA de la lente (lógica pura, sin OpenCV; probada en JVM).
 *
 * Todos los modelos responden a la misma pregunta: para el píxel (xu, yu) de la imagen CORREGIDA, ¿en qué punto (xd, yd)
 * de la foto original hay que muestrear? ([LensMap.source], lo que necesita un remap). [LensMap.undistort] es la inversa
 * (punto de la foto -> punto corregido): la usa el autocalibrado para enderezar segmentos detectados y el encaje del
 * lienzo para no perder contenido.
 *
 * - [Brown]: el de Camera2 (LENS_DISTORTION, API 28+; convención de OpenCV: coordenadas normalizadas por la focal,
 *   k1 k2 k3 radiales y p1 p2 tangenciales) ya trasladado a los píxeles de la foto (ver [SensorLens.toImage]).
 * - [Division]: modelo de división de un parámetro (Fitzgibbon), el del autocalibrado: xu = c + (xd − c) / (1 + k·r²),
 *   con r normalizado por la semidiagonal de la imagen; su inversa es cerrada.
 *
 * Además, [scale] (> 0) amplía/reduce la salida alrededor del centro para que NINGÚN contenido de la foto quede fuera
 * del lienzo (ver [fitScale]).
 */
internal sealed class LensMap {
    abstract val width: Int
    abstract val height: Int
    /** Escala de la salida (1 = misma focal); < 1 cuando la corrección empujaría contenido fuera del lienzo. */
    abstract val scale: Double

    /** Punto de la foto (distorsionada) que corresponde al píxel corregido (xu, yu). out[0..1]. */
    abstract fun source(xu: Double, yu: Double, out: DoubleArray)

    /** Inversa: punto corregido del punto (xd, yd) de la foto. out[0..1]. */
    abstract fun undistort(xd: Double, yd: Double, out: DoubleArray)

    abstract fun withScale(s: Double): LensMap

    /** Texto compacto (EXIF/registro). */
    abstract fun describe(): String

    /** Desplazamiento máximo (px) que produce la corrección en el lienzo (bordes y esquinas muestreados). */
    fun maxDisplacement(): Double {
        val o = DoubleArray(2)
        var m = 0.0
        forBorder(width, height, 24) { x, y ->
            source(x, y, o)
            m = max(m, hypot(o[0] - x, o[1] - y))
        }
        return m
    }

    /**
     * Escala que deja DENTRO del lienzo todo el contorno de la foto corregido (no se pierde nada del borde): con barril
     * (contenido comprimido en los bordes) la corrección lo expande y hay que reducir; con cojín se deja 1 (las esquinas
     * vacías se rellenan replicando el borde, son pocos píxeles).
     */
    fun fitScale(): Double {
        val base = withScale(1.0)
        val o = DoubleArray(2)
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        var s = 1.0
        forBorder(width, height, 32) { x, y ->
            base.undistort(x, y, o)
            val dx = abs(o[0] - cx); val dy = abs(o[1] - cy)
            if (dx > cx + 0.5) s = min(s, cx / dx)
            if (dy > cy + 0.5) s = min(s, cy / dy)
        }
        return s.coerceIn(0.85, 1.0)
    }

    /**
     * Modelo de Brown–Conrady en píxeles de la imagen. (cx, cy, fx, fy) en px de la foto; coeficientes de Camera2.
     * [legacy] = coordenadas normalizadas a 1 en el borde más alejado del centro (LENS_RADIAL_DISTORTION, API 23–27)
     * en lugar de por la focal.
     */
    class Brown(
        override val width: Int,
        override val height: Int,
        val fx: Double, val fy: Double, val cx: Double, val cy: Double,
        val k1: Double, val k2: Double, val k3: Double, val p1: Double, val p2: Double,
        val legacy: Boolean = false,
        override val scale: Double = 1.0,
        /** Normalización (px): la focal (API 28+) o, en la convención legada, la mayor distancia del centro al borde. */
        val nx: Double = fx,
        val ny: Double = fy,
    ) : LensMap() {
        private val ox = (width - 1) / 2.0
        private val oy = (height - 1) / 2.0

        override fun source(xu: Double, yu: Double, out: DoubleArray) {
            // La salida escalada alrededor del centro del lienzo: primero se deshace la escala.
            val x0 = ox + (xu - ox) / scale
            val y0 = oy + (yu - oy) / scale
            val x = (x0 - cx) / nx; val y = (y0 - cy) / ny
            val r2 = x * x + y * y
            val rad = 1 + r2 * (k1 + r2 * (k2 + r2 * k3))
            val xd = x * rad + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
            val yd = y * rad + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
            out[0] = cx + xd * nx; out[1] = cy + yd * ny
        }

        override fun undistort(xd: Double, yd: Double, out: DoubleArray) {
            // Iteración de punto fijo (la de cv::undistortPoints): converge en pocas vueltas con distorsiones de móvil.
            val tx = (xd - cx) / nx; val ty = (yd - cy) / ny
            var x = tx; var y = ty
            repeat(12) {
                val r2 = x * x + y * y
                val rad = 1 + r2 * (k1 + r2 * (k2 + r2 * k3))
                val dx = 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
                val dy = p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
                x = (tx - dx) / rad; y = (ty - dy) / rad
            }
            val x0 = cx + x * nx; val y0 = cy + y * ny
            out[0] = ox + (x0 - ox) * scale; out[1] = oy + (y0 - oy) * scale
        }

        override fun withScale(s: Double) = Brown(width, height, fx, fy, cx, cy, k1, k2, k3, p1, p2, legacy, s, nx, ny)

        override fun describe() = String.format(
            Locale.ROOT, "brown%s f=%.1f,%.1f c=%.1f,%.1f k=%.5f,%.5f,%.5f p=%.5f,%.5f s=%.4f",
            if (legacy) "-legacy" else "", fx, fy, cx, cy, k1, k2, k3, p1, p2, scale,
        )
    }

    /** Modelo de división de un parámetro alrededor de (cx, cy); r normalizado por la semidiagonal ([norm]). */
    class Division(
        override val width: Int,
        override val height: Int,
        val k: Double,
        val cx: Double = (width - 1) / 2.0,
        val cy: Double = (height - 1) / 2.0,
        override val scale: Double = 1.0,
    ) : LensMap() {
        val norm = hypot(width / 2.0, height / 2.0)
        private val ox = (width - 1) / 2.0
        private val oy = (height - 1) / 2.0

        override fun source(xu: Double, yu: Double, out: DoubleArray) {
            val x0 = ox + (xu - ox) / scale; val y0 = oy + (yu - oy) / scale
            val ux = (x0 - cx) / norm; val uy = (y0 - cy) / norm
            val ru = sqrt(ux * ux + uy * uy)
            // ru = rd / (1 + k rd²) -> k ru rd² − rd + ru = 0 -> rd = (1 − sqrt(1 − 4 k ru²)) / (2 k ru)
            val f = if (ru < 1e-9 || abs(k) < 1e-12) 1.0 else {
                val disc = 1 - 4 * k * ru * ru
                if (disc <= 0) 2.0 else (1 - sqrt(disc)) / (2 * k * ru * ru)
            }
            out[0] = cx + ux * f * norm; out[1] = cy + uy * f * norm
        }

        override fun undistort(xd: Double, yd: Double, out: DoubleArray) {
            val dx = (xd - cx) / norm; val dy = (yd - cy) / norm
            val d = 1 + k * (dx * dx + dy * dy)
            val x0 = cx + dx / d * norm; val y0 = cy + dy / d * norm
            out[0] = ox + (x0 - ox) * scale; out[1] = oy + (y0 - oy) * scale
        }

        override fun withScale(s: Double) = Division(width, height, k, cx, cy, s)

        override fun describe() = String.format(Locale.ROOT, "div k=%.5f c=%.1f,%.1f s=%.4f", k, cx, cy, scale)
    }

    companion object {
        /** Recorre el contorno del lienzo con [n] muestras por lado (incluidas las esquinas). */
        inline fun forBorder(w: Int, h: Int, n: Int, f: (Double, Double) -> Unit) {
            val xm = w - 1.0; val ym = h - 1.0
            for (i in 0..n) {
                val t = i.toDouble() / n
                f(t * xm, 0.0); f(t * xm, ym); f(0.0, t * ym); f(xm, t * ym)
            }
        }
    }
}

/**
 * Calibración de la lente tal como la da Camera2 (en el sistema de coordenadas del SENSOR) y su traslado a la foto.
 *
 * - [arrayW]x[arrayH]: tamaño del sistema de coordenadas de la calibración intrínseca (preCorrectionActiveArraySize en
 *   API 28+; activeArraySize antes).
 * - [intrinsics]: [fx, fy, cx, cy, s] (LENS_INTRINSIC_CALIBRATION) en ese sistema.
 * - [distortion]: [k1, k2, k3, p1, p2] (LENS_DISTORTION) o, si [legacy], los 6 de LENS_RADIAL_DISTORTION
 *   [κ0, κ1, κ2, κ3, κ4, κ5] (κ0 ≈ 1).
 * - [crop]: [left, top, width, height] de la zona del sensor que cubre la foto ANTES del recorte de aspecto (zoom digital:
 *   SCALER_CROP_REGION; null = todo el array).
 */
internal data class SensorLens(
    val arrayW: Int,
    val arrayH: Int,
    val intrinsics: FloatArray,
    val distortion: FloatArray,
    val legacy: Boolean = false,
    val crop: IntArray? = null,
    /** El ISP ya corrige la distorsión en la foto (DISTORTION_CORRECTION_MODE != OFF aplicado): no se repite. */
    val ispCorrected: Boolean = false,
    /** El ISP corrige la aberración cromática en alta calidad: no se estima en software. */
    val ispChromaCorrected: Boolean = false,
    /** Datos del resultado de la propia foto (fiables); false = de las características (se comprueban con la foto). */
    val trusted: Boolean = true,
    /** Origen (registro): "resultado", "características". */
    val source: String = "",
) {
    /**
     * Calibración en los píxeles de una foto [imgW]x[imgH] tal como está en el archivo (SIN aplicar la orientación
     * EXIF). La foto es la zona [crop] del sensor recortada al aspecto de salida (centrada, como hace el HAL) y escalada.
     * Si la foto viene GIRADA respecto del sensor (alto/ancho cambiados) se usa el centro de la imagen y sólo los
     * términos radiales (la dirección del giro no se conoce; el centro óptico de un móvil está a pocos píxeles del centro).
     * Devuelve null si los datos no son coherentes.
     */
    fun toImage(imgW: Int, imgH: Int): LensMap? {
        if (intrinsics.size < 4 || imgW < 16 || imgH < 16 || arrayW <= 0 || arrayH <= 0) return null
        val k = coefficients() ?: return null
        val fx0 = intrinsics[0].toDouble(); val fy0 = intrinsics[1].toDouble()
        val cx0 = intrinsics[2].toDouble(); val cy0 = intrinsics[3].toDouble()
        if (!legacy && (fx0 <= 0 || fy0 <= 0)) return null
        val c = crop?.takeIf { it.size >= 4 && it[2] > 0 && it[3] > 0 } ?: intArrayOf(0, 0, arrayW, arrayH)
        val sensorLandscape = c[2] >= c[3]
        val imgLandscape = imgW >= imgH
        val rotated = sensorLandscape != imgLandscape && abs(c[2] - c[3]) > 0.02 * max(c[2], c[3])
        val (ow, oh) = if (rotated) imgH to imgW else imgW to imgH
        // Recorte de aspecto centrado dentro de la zona [crop]
        val aspOut = ow.toDouble() / oh
        val aspCrop = c[2].toDouble() / c[3]
        var rw = c[2].toDouble(); var rh = c[3].toDouble()
        if (aspOut > aspCrop) rh = rw / aspOut else rw = rh * aspOut
        val left = c[0] + (c[2] - rw) / 2; val top = c[1] + (c[3] - rh) / 2
        val sx = ow / rw; val sy = oh / rh
        if (abs(sx / sy - 1) > 0.03) return null
        // Píxel del sensor (x) -> píxel de la foto: (x − left) · s − 0.5 + 0.5 (centros de píxel)
        var cx = (cx0 - left) * sx; var cy = (cy0 - top) * sy
        var fx = fx0 * sx; var fy = fy0 * sy
        // Centro óptico fuera de lo razonable (calibración de otra cámara física, recorte desconocido): no fiable
        if (cx < ow * 0.3 || cx > ow * 0.7 || cy < oh * 0.3 || cy > oh * 0.7) return null
        var p1 = k[3]; var p2 = k[4]
        if (rotated) {
            cx = (imgW - 1) / 2.0; cy = (imgH - 1) / 2.0
            val t = fx; fx = fy; fy = t
            p1 = 0.0; p2 = 0.0
        }
        if (legacy) {
            // Convención legada: x e y normalizadas a 1 en el borde más alejado del centro óptico (de la zona que se ve)
            var nx = max(cx0 - left, left + rw - cx0) * sx
            var ny = max(cy0 - top, top + rh - cy0) * sy
            if (rotated) { val t = nx; nx = ny; ny = t }
            return LensMap.Brown(imgW, imgH, fx, fy, cx, cy, k[0], k[1], k[2], p1, p2, legacy = true, nx = nx, ny = ny)
        }
        return LensMap.Brown(imgW, imgH, fx, fy, cx, cy, k[0], k[1], k[2], p1, p2)
    }

    /** [k1, k2, k3, p1, p2] o null si no hay o son absurdos. */
    fun coefficients(): DoubleArray? {
        val d = distortion
        val k = if (legacy) {
            if (d.size < 6) return null
            val k0 = d[0].toDouble().takeIf { abs(it) > 1e-6 } ?: 1.0
            // x_c = x_i (κ0 + κ1 r² + …): se normaliza por κ0 (escala global, no distorsión)
            doubleArrayOf(d[1] / k0, d[2] / k0, d[3] / k0, d[4] / k0, d[5] / k0)
        } else {
            if (d.size < 5) return null
            doubleArrayOf(d[0].toDouble(), d[1].toDouble(), d[2].toDouble(), d[3].toDouble(), d[4].toDouble())
        }
        if (k.any { it.isNaN() || it.isInfinite() }) return null
        if (abs(k[0]) > 1.0 || abs(k[1]) > 2.0 || abs(k[2]) > 4.0 || abs(k[3]) > 0.05 || abs(k[4]) > 0.05) return null
        return k
    }

    /** Texto compacto para EXIF y registro. */
    fun describe(): String = buildString {
        append(if (legacy) "radial" else "distortion")
        append(String.format(Locale.ROOT, " a=%dx%d", arrayW, arrayH))
        append(" i=").append(intrinsics.joinToString(",") { String.format(Locale.ROOT, "%.2f", it) })
        append(" d=").append(distortion.joinToString(",") { String.format(Locale.ROOT, "%.6f", it) })
        crop?.let { append(" crop=").append(it.joinToString(",")) }
        if (ispCorrected) append(" isp")
        if (ispChromaCorrected) append(" ispca")
        if (!trusted) append(" sin-verificar")
        if (source.isNotEmpty()) append(" src=").append(source)
    }

    override fun equals(other: Any?): Boolean = other is SensorLens && describe() == other.describe()
    override fun hashCode(): Int = describe().hashCode()
}
