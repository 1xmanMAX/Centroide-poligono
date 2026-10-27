package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decisiones puras de "Color natural" (sin OpenCV; probadas en JVM).
 */
internal object NaturalMath {
    /** Rampa lineal 0..1 entre [lo] y [hi]. */
    fun ramp(v: Double, lo: Double, hi: Double): Double = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)

    /** Factor de saturación según el croma CIE: lo casi neutro (tinta negra/gris, papel) pierde el tinte; el color, x[sat]. */
    fun chromaFactor(chroma: Double, sat: Double = NaturalColor.SATURATION): Double =
        ramp(chroma, NaturalColor.NEUTRAL_LO, NaturalColor.NEUTRAL_HI) * sat

    /** Ganancias del balance de blancos: el papel medido (por canal) pasa a blanco; acotadas (una página de color no es papel). */
    fun whiteGains(paper: DoubleArray): DoubleArray {
        val g = DoubleArray(3) { 255.0 / max(1.0, paper[it]) }
        val m = g.average()
        // dominante residual acotada a ±12 % alrededor de la media
        return DoubleArray(3) { (g[it]).coerceIn(m * 0.88, m * 1.12).coerceIn(0.9, 1.8) }
    }

    /** Croma desde el que un píxel es tinta de color: ~2.5 veces el croma del papel (ruido de color de la cámara), 5..12. */
    fun chromaLow(paperChromaP95: Double): Double = (2.5 * paperChromaP95).coerceIn(5.0, 12.0)

    /** Oscuridad relativa desde la que un trazo tenue (lápiz, tinta pálida) es candidato: >= 5σ del papel, >= 0.08. */
    fun faintLow(noise: Double): Double = max(0.08, 5.0 * noise / 255.0)

    /**
     * Refuerzo de la densidad de la tinta (Beer-Lambert: más concentración de la misma tinta, mismo matiz): exponente
     * k sobre la transmitancia, tal que la densidad óptica [od] de la luminancia llegue a [target]; 1 si ya es densa.
     */
    fun densityGain(od: Double, target: Double = NaturalColor.OD_TARGET, kMax: Double = NaturalColor.K_MAX): Double =
        if (od <= 1e-3) kMax else (target / od).coerceIn(1.0, kMax)

    /**
     * ¿Componente añadida (tenue) lo bastante fuerte? Oscuridad media >= [GHOST_K] x la de la tinta típica del B/N, o con
     * color propio (croma medio >= 0.8 x el p95 del croma del papel, >= 3). La transparencia del reverso y las manchas grises no pasan.
     */
    fun strongEnough(meanDark: Double, inkDark: Double, meanChroma: Double, paperChroma: Double): Boolean =
        meanDark >= NaturalColor.GHOST_K * inkDark || meanChroma >= max(3.0, 0.8 * paperChroma)

    /**
     * ¿Componente sin tinta en el B/N lo bastante clara como escritura? Color vivo (croma medio >= VIVID_K·cLo), oscura
     * (>= VIVID_KD·tF), o -si tiene tamaño de palabra y no está junto al borde (3 %)- tenue con algo de color propio
     * (resaltador, tinta pálida en sombra).
     */
    fun vivid(meanC: Double, meanD: Double, cLo: Double, tF: Double, paperC: Double, wordSized: Boolean): Boolean =
        meanC >= NaturalColor.VIVID_K * cLo || meanD >= NaturalColor.VIVID_KD * tF ||
            (wordSized && ((meanD >= 1.25 * tF && meanC >= max(3.0, 0.85 * paperC)) || meanC >= 1.1 * cLo))

    /**
     * ¿Relleno liso (banda de tabla, celda o recuadro sombreado)? A la escala de análisis: área >= 25 letras², caja llena
     * >= 8 % (bandas y columnas cruzadas forman un peine), grosor >= 1 altura de letra, contorno nítido (el anillo de fuera se aparta del papel <= 55 % de lo que se
     * aparta el relleno) y lejos del borde de la imagen.
     */
    fun isFlatFill(area: Int, fill: Double, thick: Double, lh: Double, devIn: Double, devOut: Double, border: Boolean): Boolean =
        !border && area >= 25 * lh * lh && fill >= 0.08 && thick >= lh && devOut <= 0.55 * devIn

    /**
     * ¿Color de fondo ajeno (madera, mesa de color) frente al papel? Otra CROMATICIDAD (r, g normalizados por la suma:
     * una sombra -aunque sea muy oscura- conserva la del papel; la madera no): distancia >= 0.025, y algo más oscuro que él
     * (un resaltador o una banda clara de color tienen la luminancia del papel o más).
     */
    fun isForeignColor(dRg: Double, lumRatio: Double): Boolean = dRg >= 0.025 && lumRatio < 0.93

    /**
     * ¿Trazo del B/N que es el canto de una hoja o el borde de la mesa? Entero en la franja del 5 % de un lado de la
     * imagen, largo (>= 4 letras) y fino (área / largo <= 0.6 letras).
     */
    fun borderStroke(x: Int, y: Int, w: Int, h: Int, area: Int, imgW: Int, imgH: Int, lh: Double): Boolean {
        val inBand = x + w <= 0.05 * imgW || x >= 0.95 * imgW || y + h <= 0.05 * imgH || y >= 0.95 * imgH
        val long = max(w, h).toDouble()
        return inBand && long >= 4 * lh && area / long <= 0.6 * lh
    }

    /**
     * ¿Tono de fondo ajeno (madera)? Un solo tono ([conc] = |Σ(a,b)| / Σ croma >= 0.75), croma medio <= 25 y con TEXTURA
     * (vetas: σ L* >= 8). Una sombra cálida sobre la hoja es lisa; una foto o ilustración (además vetada por los bloques
     * de imagen) tiene colores vivos.
     */
    fun foreignHue(conc: Double, meanChroma: Double, sdL: Double): Boolean = conc >= 0.75 && meanChroma <= 25.0 && sdL >= 8.0

    /**
     * ¿Fondo ajeno que se quita entero y no sólo lo añadido? Sin nada del B/N (mesa lisa), o madera con vetas marcadas
     * (σ L* >= 12) que el B/N llenó de manchas (>= 15 %); con escritura de densidad normal se conserva lo del B/N.
     */
    fun foreignIsHard(inkFrac: Double, sdL: Double): Boolean = inkFrac < 0.005 || (inkFrac >= 0.15 && sdL >= 12.0)

    /**
     * PUREZA media de la tinta (croma / (100 - L*), zona de ~1 letra) por debajo de la cual es NEUTRA (-> negro):
     * gris de lápiz o tinta negra ~0.03-0.06; color, aun claro o oscuro, >= ~0.2. Sube con el ruido de color del papel
     * ([cPaper] = p95 del croma del papel balanceado).
     */
    /** L* CIE (0..100) de un gris sRGB 0..1 (con la curva sRGB, como la conversión a Lab de OpenCV). */
    fun lightness(srgb: Double): Double {
        val c = srgb.coerceIn(0.0, 1.0)
        val y = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        return if (y > 0.008856) 116.0 * Math.cbrt(y) - 16.0 else 903.3 * y
    }

    /**
     * ¿Es el canto de la hoja principal? Escalón de color mediano >= [NaturalColor.SHEET_STEP] (ΔE) entre los dos lados
     * y de al menos la mitad en >= 75 % del lado ([stepFrac]),
     * papel del lado de dentro en >= 70 % del tramo y como mucho [NaturalColor.SHEET_LETTERS] letras del otro lado.
     */
    fun sheetEdgeOk(step: Double, stepFrac: Double, paperInFrac: Double, lettersBeyond: Int): Boolean =
        step >= NaturalColor.SHEET_STEP && stepFrac >= 0.75 && paperInFrac >= 0.7 && lettersBeyond <= NaturalColor.SHEET_LETTERS

    fun neutralLo(cPaper: Double): Double = max(0.07, 0.02 * cPaper)

    /** ¿La caja toca el borde de la imagen (1 px de margen)? */
    fun touchesBorder(x: Int, y: Int, w: Int, h: Int, imgW: Int, imgH: Int): Boolean =
        x <= 1 || y <= 1 || x + w >= imgW - 1 || y + h >= imgH - 1

    /** ¿La caja está en la franja del 3 % junto al borde de la imagen? */
    fun nearBorder(x: Int, y: Int, w: Int, h: Int, imgW: Int, imgH: Int, frac: Double = 0.03): Boolean {
        val mx = frac * imgW; val my = frac * imgH
        return x < mx || y < my || x + w > imgW - mx || y + h > imgH - my
    }

    /** Punto blanco tras el balance: el grano del papel (2.5σ) queda en blanco puro. */
    fun whitePoint(noise: Double): Double = (255.0 - 2.5 * noise).coerceIn(215.0, 250.0)

    /** Punto negro moderado: la tinta más oscura (percentil 3 %) baja ~un 30 %; nunca más que [MAX_BLACK]. */
    fun blackPoint(inkP3: Double?): Double = if (inkP3 == null) 0.0 else (inkP3 * 0.7).coerceIn(0.0, NaturalColor.MAX_BLACK)

    /**
     * Clase de una componente de croma (a la escala de análisis, [lh] = altura de letra a esa escala).
     * 0 = descartar (mota, rejilla/renglón largo y fino), 1 = trazo de color, 2 = figura/foto/logo (pasa entera).
     */
    fun colorComponentClass(w: Int, h: Int, area: Int, lh: Double, inkFrac: Double = 1.0, thickness: Double = 0.0, vivid: Boolean = true, colorful: Boolean = true): Int {
        val l = max(2.0, lh)
        if (area < max(3.0, 0.02 * l * l)) return 0
        val long = max(w, h).toDouble(); val short = min(w, h).toDouble()
        val fill = area.toDouble() / max(1, w * h)
        // Renglón / recta de la cuadrícula: largo y muy fino
        if (long > 6 * l && area / long < 0.14 * l) return 0
        // Retícula (cuadrícula entera conectada): enorme, casi vacía y sin tinta en el B/N (unas palabras fundidas sí la tienen)
        if (w > 6 * l && h > 6 * l && fill < 0.18 && inkFrac < 0.05) return 0
        // Mancha gruesa (figura, resaltador, banda impresa del cuaderno): sólo si el B/N vio contenido dentro (texto
        // resaltado, foto o logo en gris); una banda de color vacía (margen, cabecera del cuaderno) no es escritura
        // Grosor = 2 x máxima distancia al borde (una palabra con las letras fundidas sigue siendo fina; una banda no)
        val thick = thickness > 1.2 * l
        // Figura (foto, logo): gruesa, compacta y de color en conjunto (una tabla con bandas de color no lo es: sus letras
        // siguen el tratamiento de la tinta)
        if (colorful && thickness >= 2.5 * l && area >= 25 * l * l && fill >= 0.35 && short >= 3 * l) return if (inkFrac >= 0.04) 2 else 0
        if (thick && inkFrac < 0.02 && ((long > 8 * l && fill > 0.5) || !vivid)) return 0
        // Sin nada de tinta en el B/N, sólo si su color u oscuridad media es clara (resaltador, tinta de color): las motas
        // del papel, de una banda impresa o los agujeros de la espiral, débiles, no
        if (inkFrac < 0.01 && !vivid) return 0
        return 1
    }
}

/**
 * "Color natural": el resultado de "Blanco y negro" (fondo blanco puro, sólo la escritura) se usa como MÁSCARA
 * SUAVE sobre la foto normalizada (iluminación y sombras corregidas): lo negro deja pasar el color original de
 * la tinta y lo blanco queda blanco puro. Además:
 * - continuidad del trazo: cierre morfológico del alfa aceptado sólo donde la foto muestra tinta (gris o color) e
 *   histéresis (lo tenue pegado a un trazo firme entra) -> el texto no se ve fragmentado;
 * - tinta de color clara (resaltador, bolígrafo pálido, lápiz de color) que el B/N pierde: máscara por croma;
 * - procesado POR ZONAS: figuras/logos de color pasan enteros; los bloques de imagen (fotos, ilustraciones) reciben
 *   un tratamiento de foto desde la foto original ([photoZone]); los rellenos lisos (bandas y celdas de tabla,
 *   recuadros sombreados) pasan como fondo con su color y la tinta del B/N encima ([detectFills], [localFill]); el
 *   fondo ajeno (madera, mesa, cantos de hojas) se quita ([foreignMask], [edgeLines]);
 * - tinta NEUTRA (gris plomo por la cámara, desenfoque) -> negro profundo, clasificada por zonas de ~1 letra por su
 *   pureza (croma relativo a la oscuridad); tinta de COLOR: densidad Beer-Lambert y croma con tope relativo al suyo
 *   (mismo tono, sin saturar de más); balance de blancos con el papel y niveles moderados; el color de cada píxel de
 *   borde es el del centro del trazo (sin halo claro) y el alfa se re-afila.
 * Trabaja por teselas en 8 bits (memoria acotada; las teselas sin nada salen blancas sin cálculo), en paralelo.
 */
internal object NaturalColor {
    internal var log: ((String) -> Unit)? = null
    /** Depuración (escritorio): recibe el alfa final (8UC1) de cada composición. */
    internal var debug: ((String, Mat) -> Unit)? = null
    private var dbgA: Mat? = null
    internal var logComps = false
    /** Banco (escritorio): desactivar partes para medirlas (bits: 1 neutra->negro, 2 re-afilado, 4 rellenos, 8 ajeno, 16 foto, 32 tope de croma, 128 luz local, 256 canto de la hoja). */
    @JvmField internal var OFF = 0
    /** Depuración: croma local de la tinta de cada píxel activo (sólo escritorio). */
    internal var chromaStats: MutableList<Float>? = null

    /** "Blanco y negro" también une los trazos rotos ([bridge]) con la foto normalizada. */
    @JvmField internal var BRIDGE_BW = false
    /** Nivel de [bridge] en el B/N (1 = cierre, 2 = cierre + histéresis + trazos tenues). */
    @JvmField internal var BRIDGE_LEVEL = 2

    /**
     * Hilos para las teselas: 0 = automático (gama baja -2 GB o <= 4 núcleos-: 2; si no, núcleos - 1, máximo 4).
     * Cada tesela usa ~25 MB de intermedios.
     */
    @JvmField @Volatile var maxThreads = 0
    @JvmField @Volatile var lowEnd = false

    private fun threadCount(): Int {
        if (maxThreads > 0) return maxThreads
        val cores = Runtime.getRuntime().availableProcessors()
        return if (lowEnd || cores <= 4) min(2, cores) else min(4, cores - 1)
    }

    const val ANALYSIS_SIDE = 1000
    const val TILE = 1024
    const val SATURATION = 1.0
    /** Densidad óptica (luminancia) objetivo de la tinta tenue y refuerzo máximo ([NaturalMath.densityGain]). */
    const val OD_TARGET = 0.9
    const val K_MAX = 2.8
    /** Cobertura del alfa (ventana de ~1 letra) desde la que una zona es color macizo (resaltador, banda): sin refuerzo. */
    const val FILL_LO = 0.55
    const val FILL_HI = 0.8
    /** Tope del croma del realce respecto al croma propio del núcleo del trazo (x CHROMA_CAP + CHROMA_ADD). */
    const val CHROMA_CAP = 1.0
    const val CHROMA_ADD = 2.0
    /** Tinta neutra -> negro: factor de L* y ancho de la rampa de pureza (desde [NaturalMath.neutralLo]). */
    const val NEUTRAL_K = 0.4
    const val NEUTRAL_SPAN = 0.07
    const val NEUTRAL_LO = 4.0
    const val NEUTRAL_HI = 11.0
    const val MAX_BLACK = 70.0
    /** Luz local ([localLight]): celdas en el lado largo, dominante mínima de la luz (Δab del papel original) y k máximo. */
    const val LWB_CELLS = 24
    const val LWB_T0 = 5.0
    const val LWB_KMAX = 2.5
    const val LWB_MIN_CELLS = 8
    /** Canto de la hoja ([sheetOutside]): escalón mínimo (ΔE) y letras máximas del otro lado. */
    const val SHEET_STEP = 6.0
    const val SHEET_LETTERS = 3
    /** Croma (mediana) de lo de fuera del canto a partir del cual, si además es liso, es una banda impresa de color. */
    const val SHEET_OUT_CHROMA = 25.0
    /** Desviación típica mínima de L* fuera del canto (textura del fondo ajeno; una banda impresa es lisa). */
    const val SHEET_OUT_SD = 8.0
    /** Tinta neutra más clara que la típica (L* de la mediana + LO..HI): conserva su gris (rampa). */
    @JvmField var NEUTRAL_KEEP_LO = 15.0
    @JvmField var NEUTRAL_KEEP_HI = 30.0
    /** Relleno liso: ΔE mínimo frente al papel blanco y desviación típica máxima de L* (5x5) ([detectFills]). */
    const val FILL_DE = 7.0
    const val FILL_SD = 4.0
    /** Rampa relativa al máximo local del trazo ([relRamp]). */
    /** Componente sin tinta en el B/N: su croma u oscuridad media debe ser >= VIVID_K veces el umbral. */
    const val VIVID_K = 1.6
    const val VIVID_KD = 2.0
    /** Componente tenue: oscuridad media mínima relativa a la tinta típica del B/N ([NaturalMath.strongEnough]). */
    const val GHOST_K = 0.5
    @JvmField var REL_LO = 0.35
    @JvmField var REL_HI = 0.7
    /** En el B/N ([bridge]) la evidencia relativa es más exigente: la transparencia del reverso y las manchas (20-40 % de la
     * oscuridad del texto) no deben unirse a la escritura; un tramo tenue del mismo trazo sí (>= ~la mitad). */
    @JvmField var REL_LO_BW = 0.5
    @JvmField var REL_HI_BW = 0.8
    /** Rampa relativa de la evidencia en las zonas candidatas (tinta tenue o de color que el B/N perdió). */
    @JvmField var REL_LO_SUP = 0.25
    @JvmField var REL_HI_SUP = 0.6
    /** Alfa por debajo del cual el píxel es papel (blanco puro). */
    const val ALPHA_MIN = 0.05
    /** Zonas de imagen: saturación y nitidez (máscara de enfoque) del tratamiento de foto ([photoZone]). */
    const val PHOTO_SAT = 1.08
    const val PHOTO_SHARP = 0.5
    /** Re-afilado del alfa (cantidad y sigma de la máscara de enfoque). */
    const val ALPHA_SHARP = 0.6
    const val ALPHA_SHARP_SIGMA = 1.2

    class Inputs(
        val bw: Mat,                 // 8UC1 resultado B/N (255 papel), tamaño de salida
        val nrm: Mat,                // 8UC3 RGB normalizado (papel ≈ PAPER_LEVEL), mismo tamaño
        val letterHeight: Double,    // px de salida
        val strokeHalf: Double,      // semiancho típico del trazo (px de salida), 0 = desconocido
        val images: List<Rect> = emptyList(),      // bloques de imagen (pasan enteros, en color)
        val allowed: List<Rect>? = null,           // zonas donde puede haber color (null = toda la página)
        val exclude: Mat? = null,    // 8UC1 (cualquier tamaño): 255 = no es tinta (huella de la rejilla, fondo ajeno)
        val exclude2: Mat? = null,
        val excludeForeign: Boolean = false,       // [exclude] es fondo ajeno (mesa): lo pegado a él cuenta como borde   // ídem (p. ej. zonas en blanco de la segmentación, a resolución de trabajo)
        /** 0 = "Color natural"; 1 = sólo cierre de roturas (para el B/N); 2 = cierre + histéresis + trazos tenues junto a tinta del B/N. */
        val bridge: Int = 0,
        val gridColor: DoubleArray? = null,        // RGB normalizado de la cuadrícula clara (su tono no es tinta), o null
        val orig: Mat? = null,       // RGB 8UC3 de la foto SIN normalizar (cualquier tamaño, misma geometría): fondo ajeno ([foreignMask])
        /** Zona de escritura del cuaderno (8UC1, 255 = dentro; cualquier tamaño, misma geometría) o null: fuera de ella (marco,
         * margen y bandas impresas, tapa) no se añade color ni figuras, sólo queda la tinta del B/N. */
        val writing: Mat? = null,
        /** Fotos de la página ([com.scannerpromax.domain.PageEdits.photoMode]): 0 auto, 1 recortadas como rectángulos enteros
         * con su color ([photoRects]), 2 sin trato de foto (se procesan como el resto de la página). */
        val photoMode: Int = 0,
    ) { val bridgeOnly: Boolean get() = bridge > 0 }

    private class Global(
        val gain: DoubleArray, val black: Double, val white: Double, val t0: Double,
        val support: Mat, val figures: Mat, val scale: Double, val cLo: Double, val tF: Double,
        /** Rellenos lisos (8U, 255 = relleno) y su color (8UC3) a la escala de análisis, o null. */
        val fills: Mat? = null, val fillCol: Mat? = null, val fillT: Double = FILL_DE,
        val cPaper: Double = 3.0,
        /** Blanco del papel (RGB) en la foto original, o null. */
        val origWhite: DoubleArray? = null,
        /** Zonas de foto (8U, bloques de imagen) a la escala de análisis. */
        val photos: Mat? = null,
        /** Fondo ajeno (8U, 255) a la escala de análisis, o null. */
        val foreign: Mat? = null,
        /** Zona ajena con escritura (banda impresa, sombra de color): sin lo añadido, con la tinta del B/N; o null. */
        val foreignSoft: Mat? = null,
        /** L* (0..100) de la tinta típica del B/N (mediana), o 100 si no hay: la tinta neutra mucho más clara que ella
         * (transparencia del reverso, manchas) conserva su gris en vez de pasar a negro. */
        val inkL: Double = 100.0,
        /** Luz local ([localLight]): desplazamiento (a, b) de Lab a restar de la tinta (32FC2) en una rejilla de celdas de
         * [lwCell] px de la escala de análisis, o null. */
        val lw: Mat? = null,
        val lwCell: Double = 1.0,
    ) {
        /** Ganancia de la luminancia (balance de blancos aplicado a la oscuridad sin copia balanceada). */
        val lumGain: Double get() = 0.299 * gain[0] + 0.587 * gain[1] + 0.114 * gain[2]
        fun release() { support.release(); figures.release(); fills?.release(); fillCol?.release(); foreign?.release(); foreignSoft?.release(); photos?.release(); lw?.release() }
    }

    /** Cajas normalizadas 0..1 (izq., arriba, dcha., abajo) de la maquetación -> rectángulos en px de una salida [w] x [h]. */
    fun layoutRects(boxes: List<FloatArray>, w: Int, h: Int): List<Rect> = boxes.mapNotNull { b ->
        val x0 = (b[0] * w).roundToInt().coerceIn(0, w); val y0 = (b[1] * h).roundToInt().coerceIn(0, h)
        val x1 = (b[2] * w).roundToInt().coerceIn(0, w); val y1 = (b[3] * h).roundToInt().coerceIn(0, h)
        if (x1 - x0 < 2 || y1 - y0 < 2) null else Rect(x0, y0, x1 - x0, y1 - y0)
    }

    /** Compone "Color natural" (8UC3 nuevo). */
    fun compose(inp: Inputs): Mat = MatBag().use { bag ->
        val W = inp.bw.cols(); val H = inp.bw.rows()
        require(inp.nrm.cols() == W && inp.nrm.rows() == H) { "tamaños distintos" }
        val t0 = System.nanoTime()
        NcProf.start(); val g = analyzeGlobal(inp, bag)
        try {
            val out = Mat(H, W, if (inp.bridgeOnly) CvType.CV_8UC1 else CvType.CV_8UC3, Scalar.all(255.0))
            val lh = max(4.0, inp.letterHeight)
            val sh = if (inp.strokeHalf > 0) inp.strokeHalf else max(1.0, lh * 0.06)
            val r = (sh + 1.0).roundToInt().coerceIn(1, 6)                  // ventana del color del centro del trazo
            val kc = Cv.odd((2.0 * sh + 1.0).roundToInt().coerceIn(3, 11))   // cierre de huecos / roturas
            val kn = Cv.odd(min((0.25 * lh).roundToInt(), (3.0 * sh + 1.0).roundToInt()).coerceIn(3, 13))   // vecindad de la histéresis (~1.5 anchos de trazo)
            val kl = Cv.odd((0.6 * lh).roundToInt().coerceIn(5, 31))         // máximo local (contraste relativo al trazo)
            val pad = kc + kn + kl + r + 4
            val sw = g.support.cols(); val shh = g.support.rows()
            val supB = ByteArray(sw * shh); g.support.get(0, 0, supB)
            val figB = ByteArray(sw * shh); g.figures.get(0, 0, figB)
            g.fills?.let { f -> val fb = ByteArray(sw * shh); f.get(0, 0, fb); for (i in fb.indices) if (fb[i].toInt() != 0) figB[i] = -1 }   // (para saltar teselas: también los rellenos)
            var tiles = 0; var skipped = 0
            if (debug != null && !inp.bridgeOnly) dbgA = Mat(H, W, CvType.CV_8UC1, Scalar(0.0))
            val work = ArrayList<Rect>()
            var y = 0
            while (y < H) {
                val y1 = min(H, y + TILE)
                var x = 0
                while (x < W) {
                    val x1 = min(W, x + TILE)
                    tiles++
                    // Tesela sin tinta en el B/N, sin color y sin figura -> blanca (ya lo es)
                    val bwT = inp.bw.submat(y, y1, x, x1)
                    val mn = Core.minMaxLoc(bwT).minVal; bwT.release()
                    val any = mn < 250 || anyIn(supB, sw, shh, g.scale, x, y, x1, y1) || anyIn(figB, sw, shh, g.scale, x, y, x1, y1)
                    if (any) work.add(Rect(x, y, x1 - x, y1 - y)) else skipped++
                    x = x1
                }
                y = y1
            }
            // Teselas independientes (escriben en zonas disjuntas de [out]) -> en paralelo, con un tope de hilos
            val nT = min(work.size, threadCount())
            if (nT <= 1) for (t in work) tile(inp, g, out, t, pad, r, kc, kn, kl, lh)
            else {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(nT) { rn -> Thread(rn, "natural-color").apply { isDaemon = true } }
                try {
                    val fs = work.map { t -> pool.submit { tile(inp, g, out, t, pad, r, kc, kn, kl, lh) } }
                    for (f in fs) try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                } finally { pool.shutdownNow() }
            }
            dbgA?.let { debug?.invoke("alpha", it); it.release(); dbgA = null }
            log?.invoke("compose ${W}x$H tiles=$tiles vacías=$skipped gain=%.2f,%.2f,%.2f black=%.0f white=%.0f t0=%.3f %.0f ms".format(
                g.gain[0], g.gain[1], g.gain[2], g.black, g.white, g.t0, (System.nanoTime() - t0) / 1e6))
            out
        } finally { g.release() }
    }

    /**
     * Rectángulos de foto a la escala de análisis ([Inputs.photoMode] = 1): lo que no es papel (más oscuro que él o con
     * color), sin los trazos finos del texto (apertura de ~0,6 renglón), cerrado y agrupado; se acepta una zona de al
     * menos 5x5 renglones cubierta en su mayor parte (un párrafo, una tabla o una banda de título no lo están).
     */
    internal fun photoRects(lum: Mat, chroma: Mat, paper: Mat, foreign: Mat?, lhS: Double, cLo: Double, bag: MatBag): List<Rect> {
        val w = lum.cols(); val h = lum.rows()
        val pl = if (Core.countNonZero(paper) > 0.05 * lum.total()) Cv.percentile(Cv.histogram(lum, paper), 0.5).toDouble() else 235.0
        val np = bag.mat(); Imgproc.threshold(lum, np, pl - 28.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val cm = bag.mat(); Imgproc.threshold(chroma, cm, 3.0 * cLo, 255.0, Imgproc.THRESH_BINARY); cm.convertTo(cm, CvType.CV_8U)
        Core.bitwise_or(np, cm, np)
        foreign?.let { f -> val nf = bag.mat(); Core.bitwise_not(f, nf); Core.bitwise_and(np, nf, np) }
        val ko = Cv.odd(max(3, (0.6 * lhS).roundToInt()))
        Imgproc.morphologyEx(np, np, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_RECT, ko))
        val solid = bag.mat(); np.copyTo(solid)
        Imgproc.morphologyEx(np, np, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, Cv.odd(max(3, (1.2 * lhS).roundToInt()))))
        val labels = bag.mat(); val stats = bag.mat(); val cent = bag.mat()
        val n = Imgproc.connectedComponentsWithStats(np, labels, stats, cent, 8, CvType.CV_32S)
        val out = ArrayList<Rect>()
        val minSide = 5.0 * lhS
        for (i in 1 until n) {
            val x = stats.get(i, 0)[0].toInt(); val y = stats.get(i, 1)[0].toInt()
            val rw = stats.get(i, 2)[0].toInt(); val rh = stats.get(i, 3)[0].toInt()
            if (rw < minSide || rh < minSide || rw * rh > 0.9 * w * h) continue
            val r = Rect(x, y, rw, rh)
            val sub = solid.submat(r); val cover = Core.countNonZero(sub).toDouble() / r.area(); sub.release()
            if (cover < 0.5) continue
            val m = (0.3 * lhS).roundToInt()
            out += Cv.clampRect(Rect(x - m, y - m, rw + 2 * m, rh + 2 * m), w, h)
        }
        return out
    }

    /** ¿Hay algún píxel activo de la máscara a baja resolución dentro del rectángulo de salida? */
    private fun anyIn(b: ByteArray, w: Int, h: Int, s: Double, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        val a0 = (x0 * s).toInt().coerceIn(0, w - 1); val a1 = (x1 * s).toInt().coerceIn(a0, w - 1)
        val b0 = (y0 * s).toInt().coerceIn(0, h - 1); val b1 = (y1 * s).toInt().coerceIn(b0, h - 1)
        for (yy in b0..b1) { val o = yy * w; for (xx in a0..a1) if (b[o + xx].toInt() != 0) return true }
        return false
    }

    /** Estadística global a la escala de análisis: balance de blancos, puntos negro/blanco, máscaras de color y figuras. */
    private fun analyzeGlobal(inp: Inputs, bag: MatBag): Global {
        // Una sola reducción de la página a resolución completa: a 2000 px (candidatos) y de ahí a 1000 (estadística)
        // (por mitades exactas -media 2x2, la reducción más rápida- y el resto por áreas desde la imagen ya pequeña)
        val big = bag.mat()
        if (max(inp.nrm.cols(), inp.nrm.rows()) > 2.6 * ANALYSIS_SIDE) {
            halve(inp.nrm, big)
            Cv.downscale(big, big, 2 * ANALYSIS_SIDE)
        } else Cv.downscale(inp.nrm, big, 2 * ANALYSIS_SIDE)
        val ls = max(inp.nrm.cols(), inp.nrm.rows())
        val s = if (ls <= ANALYSIS_SIDE) 1.0 else ANALYSIS_SIDE.toDouble() / ls
        val sm = bag.mat(); Imgproc.resize(big, sm, Size(max(1.0, (inp.nrm.cols() * s).roundToInt().toDouble()), max(1.0, (inp.nrm.rows() * s).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
        val bws = bag.mat(); areaDown(inp.bw, bws, sm.size(), bag)
        val sw = sm.cols(); val shh = sm.rows()
        // Papel: blanco en el B/N y poco croma
        val ch = ArrayList<Mat>(3); Core.split(sm, ch); ch.forEach { bag.add(it) }
        val mx = bag.mat(); Core.max(ch[0], ch[1], mx); Core.max(mx, ch[2], mx)
        val mn = bag.mat(); Core.min(ch[0], ch[1], mn); Core.min(mn, ch[2], mn)
        val chr = bag.mat(); Core.subtract(mx, mn, chr)
        val paper = bag.mat(); val t = bag.mat()
        Imgproc.threshold(bws, paper, 252.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.threshold(chr, t, 30.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Core.bitwise_and(paper, t, paper)
        for (exM in listOf(inp.exclude, inp.exclude2)) excludeMask(exM, sm.size(), bag)?.let { ex -> Core.bitwise_not(ex, t); Core.bitwise_and(paper, t, paper) }
        val enough = Core.countNonZero(paper) > 0.05 * sm.total()
        val pc = DoubleArray(3) { c -> if (enough) Cv.percentile(Cv.histogram(ch[c], paper), 0.6).toDouble() else Cv.PAPER_LEVEL }
        // Blanco del papel en la foto ORIGINAL (para las zonas de imagen, que se tratan desde ella: la normalización por
        // el fondo blanquea el cielo o las zonas claras de una foto)
        val origWhite: DoubleArray? = if (inp.orig == null || !enough) null else {
            val o = bag.mat(); areaDown(inp.orig, o, sm.size(), bag)
            val oc = ArrayList<Mat>(3); Core.split(o, oc); oc.forEach { bag.add(it) }
            DoubleArray(3) { c -> max(60.0, Cv.percentile(Cv.histogram(oc[c], paper), 0.6).toDouble()) }
        }
        NcProf.mark("g.paper")
        val gain = NaturalMath.whiteGains(pc)
        // Imagen balanceada (8 bits) a la escala de análisis
        val smw = bag.mat(); Core.multiply(sm, Scalar(gain[0], gain[1], gain[2]), smw)
        val lum = bag.add(Cv.gray(smw))
        val noise = if (enough) Cv.paperStats(Cv.histogram(lum, paper)).first else 3.0
        val white = NaturalMath.whitePoint(noise)
        val ink = bag.mat(); Imgproc.threshold(bws, ink, 100.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val nInk = Core.countNonZero(ink)
        val black = NaturalMath.blackPoint(if (nInk > 50) Cv.percentile(Cv.histogram(lum, ink), 0.03).toDouble() else null)
        val inkL = if (nInk > 50) NaturalMath.lightness(Cv.percentile(Cv.histogram(lum, ink), 0.5) / 255.0) else 100.0
        val t0 = max(0.07, 3.0 * noise / 255.0)
        // Croma del papel (ruido de color de la cámara): umbral de la tinta de color relativo a él
        val chroma = chromaOf(smw, bag)
        val c8 = bag.mat(); chroma.convertTo(c8, CvType.CV_8U, 4.0)
        val cPaper = if (enough) Cv.percentile(Cv.histogram(c8, paper), 0.95) / 4.0 else 3.0
        NcProf.mark("g.stats")
        val cLo = NaturalMath.chromaLow(cPaper)
        val tF = NaturalMath.faintLow(noise)
        // Candidatos a escritura que el B/N pudo perder (tenue o de color): sobre la imagen ERODIDA a doble escala
        // (los trazos finos no se diluyen con el papel al reducir)
        val er = bag.mat()
        run {
            Imgproc.erode(big, big, Cv.kernel(Imgproc.MORPH_RECT, 3))
            Imgproc.resize(big, er, sm.size(), 0.0, 0.0, Imgproc.INTER_AREA)
            Core.multiply(er, Scalar(gain[0], gain[1], gain[2]), er)
        }
        NcProf.mark("g.erode")
        val cEr0 = chromaOf(er, bag)
        val lhS = max(2.0, inp.letterHeight * s)
        // Fondo ajeno (madera, mesa, carcasa, cantos): ni tinta, ni color, ni figuras, ni rellenos
        val softF = arrayOfNulls<Mat>(1)
        // (propio: madera/mesa por color + cantos; se quita en la composición. El fondo ajeno del render impreso
        // -[Inputs.exclude] con excludeForeign- sólo veta componentes, como antes: puede cubrir una ilustración que sus
        // bloques de imagen sí conservan)
        var own: Mat? = if (inp.bridge == 0 && inp.orig != null && OFF and 8 == 0) foreignMask(inp.orig, sm.size(), bws, lhS, bag, softF,
            inp.images.map { Rect((it.x * s).toInt(), (it.y * s).toInt(), max(1, (it.width * s).roundToInt()), max(1, (it.height * s).roundToInt())) }) else null
        edgeLines(bws, cEr0, lhS, cLo, bag)?.let { el -> own = own?.also { Core.bitwise_or(it, el, it); el.release() } ?: el }
        // Fuera del canto de la hoja principal (hojas de debajo, funda del móvil, mesa): blanco
        if (inp.bridge == 0 && inp.orig != null && OFF and 256 == 0) sheetOutsideHalf(inp.orig, bws, sm.size(), lhS, bag)?.let { so -> own = own?.also { Core.bitwise_or(it, so, it); so.release() } ?: so }
        val foreignAll2 = own
        val softAll: Mat? = softF[0]?.let { sf -> bag.add(sf); if (foreignAll2 != null) bag.mat().also { Core.bitwise_or(foreignAll2, sf, it) } else sf } ?: foreignAll2
        val exF = if (inp.excludeForeign) excludeMask(inp.exclude, sm.size(), bag) else null
        val noExtras: Mat? = if (exF != null && softAll != null) bag.mat().also { Core.bitwise_or(softAll, exF, it) } else exF ?: softAll
        NcProf.mark("g.foreign")
        // Rellenos lisos de color o gris (bandas de tabla, celdas sombreadas): pasan enteros con su color
        // (no en cuadernos: las bandas impresas del cuaderno -cabecera, pie- no son contenido)
        val fl = if (inp.bridge != 0 || inp.gridColor != null || OFF and 4 != 0) null else detectFills(smw, lhS, noise, noExtras, bag)
        NcProf.mark("g.fills")
        // Balance de blancos LOCAL (sombra cálida, luz de color en parte de la hoja): la tinta en la zona conserva su tono
        val lw: Mat? = if (inp.bridge != 0 || inp.orig == null || OFF and 128 != 0) null else {
            val np = bag.mat()
            if (fl != null && noExtras != null) Core.bitwise_or(fl.first, noExtras, np) else (fl?.first ?: noExtras)?.copyTo(np)
            localLight(smw, inp.orig, bws, if (np.empty()) null else np, bag)
        }
        val cEr = if (lw == null) cEr0 else {
            val c = bag.mat(); Imgproc.resize(lw, c, sm.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            shiftLab(er, c, bag)
            chromaOf(er, bag)
        }
        NcProf.mark("g.lwb")
        val cm = bag.mat()
        Imgproc.threshold(cEr, cm, cLo, 255.0, Imgproc.THRESH_BINARY); cm.convertTo(cm, CvType.CV_8U)
        val lumEr = bag.add(Cv.gray(er))
        Imgproc.threshold(lumEr, t, 255.0 * (1 - tF), 255.0, Imgproc.THRESH_BINARY_INV)
        Core.bitwise_or(cm, t, cm)
        // Tono de la cuadrícula clara: no es escritura
        inp.gridColor?.let { gc ->
            val erf = bag.mat(); er.convertTo(erf, CvType.CV_32F)
            gridLike(erf, gc, gain, bag)?.let { gl ->
                val g8 = bag.mat(); Imgproc.threshold(gl, g8, 0.5, 255.0, Imgproc.THRESH_BINARY); g8.convertTo(g8, CvType.CV_8U)
                Core.bitwise_and(cm, g8, cm)
            }
        }
        for (exM in listOf(inp.exclude, inp.exclude2)) excludeMask(exM, sm.size(), bag)?.let { ex -> Core.bitwise_not(ex, t); Core.bitwise_and(cm, t, cm) }
        inp.allowed?.let { rs ->
            val al = bag.add(Mat.zeros(sm.size(), CvType.CV_8U))
            for (r in rs) Imgproc.rectangle(al, org.opencv.core.Point(r.x * s, r.y * s), org.opencv.core.Point((r.x + r.width) * s, (r.y + r.height) * s), Scalar(255.0), -1)
            Core.bitwise_and(cm, al, cm)
        }
        NcProf.mark("g.cand")
        val support = Mat.zeros(sm.size(), CvType.CV_8U)
        val figures = Mat.zeros(sm.size(), CvType.CV_8U)
        // (zonas de FOTO: sólo los bloques de imagen de la maquetación -fotos, ilustraciones-; una figura deducida de una
        // componente de color -un párrafo con resaltador, un logo- sigue el tratamiento de la tinta con su color)
        val photos = Mat.zeros(sm.size(), CvType.CV_8U)
        if (inp.bridge != 1) {
            val labels = bag.mat(); val stats = bag.mat(); val cent = bag.mat()
            val n = Imgproc.connectedComponentsWithStats(cm, labels, stats, cent, 8, CvType.CV_32S)
            if (n > 1) {
                val st = IntArray(n * 5); stats.get(0, 0, st)
                val lb = IntArray(sw * shh); labels.get(0, 0, lb)
                // Tinta del B/N dentro de cada componente
                val inkM = bag.mat(); Imgproc.threshold(bws, inkM, 200.0, 255.0, Imgproc.THRESH_BINARY_INV)
                val inkB = ByteArray(sw * shh); inkM.get(0, 0, inkB)
                val inkN = IntArray(n); for (i in lb.indices) if (inkB[i].toInt() != 0) inkN[lb[i]]++
                // Grosor máximo de cada componente (transformada de distancia)
                val dt = bag.mat(); Imgproc.distanceTransform(cm, dt, Imgproc.DIST_L2, 3)
                val dtA = FloatArray(sw * shh); dt.get(0, 0, dtA)
                val thk = DoubleArray(n); for (i in lb.indices) { val v = 2.0 * dtA[i]; if (v > thk[lb[i]]) thk[lb[i]] = v }
                // Pegado al fondo ajeno (mesa, madera que el recorte dejó dentro): como si tocara el borde
                val foreign = BooleanArray(n)
                // Fracción de cada componente dentro de la zona de escritura del cuaderno
                val inZone = inp.writing?.takeIf { !it.empty() }?.let { wz ->
                    val zm = bag.mat(); Imgproc.resize(wz, zm, sm.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
                    val zb = ByteArray(sw * shh); zm.get(0, 0, zb)
                    val cnt = IntArray(n); for (i in lb.indices) if (zb[i].toInt() != 0) cnt[lb[i]]++
                    DoubleArray(n) { cnt[it].toDouble() / max(1, st[it * 5 + 4]) }
                }
                noExtras?.let { fa ->
                    val fm = bag.mat(); Imgproc.dilate(fa, fm, Cv.kernel(Imgproc.MORPH_RECT, 5))
                    val fb = ByteArray(sw * shh); fm.get(0, 0, fb)
                    for (i in lb.indices) if (fb[i].toInt() != 0) foreign[lb[i]] = true
                }
                // Croma y oscuridad medias de cada componente
                val cA = FloatArray(sw * shh); cEr.get(0, 0, cA)
                val lA = ByteArray(sw * shh); lumEr.get(0, 0, lA)
                val sC = DoubleArray(n); val sD = DoubleArray(n)
                for (i in lb.indices) { val l = lb[i]; sC[l] += cA[i]; sD[l] += 1.0 - (lA[i].toInt() and 0xFF) / 255.0 }
                // Oscuridad típica de la tinta del B/N: lo gris y mucho más claro que ella (transparencia del reverso, manchas)
                // no se añade; la tinta de color clara sí (croma)
                val inkD = if (nInk > 50) 1.0 - Cv.percentile(Cv.histogram(lumEr, ink), 0.5) / 255.0 else 0.0
                // (lo que toca el borde de la imagen es mesa, madera o el canto: el B/N ya decidió qué contenido hay allí)
                val cls = IntArray(n) {
                    val x = st[it * 5]; val y = st[it * 5 + 1]; val w = st[it * 5 + 2]; val h = st[it * 5 + 3]; val ar = st[it * 5 + 4]
                    val inkF = inkN[it].toDouble() / max(1, ar)
                    if (it == 0 || foreign[it] || NaturalMath.touchesBorder(x, y, w, h, sw, shh)) 0
                    else {
                        val c = NaturalMath.colorComponentClass(w, h, ar, lhS, inkF, thk[it],
                            vivid = NaturalMath.vivid(sC[it] / max(1, ar), sD[it] / max(1, ar), cLo, tF, cPaper, max(w, h) < 8 * lhS && !NaturalMath.nearBorder(x, y, w, h, sw, shh, 0.03)),
                            colorful = sC[it] / max(1, ar) >= 2.0 * cLo)
                        // Junto al borde (restos del canto, agujeros, mesa, madera): sólo trazos finos con tinta en el B/N
                        // (salvo tinta de color clara de tamaño de palabra -un título en naranja tenue arriba-: la madera, la
                        // mesa y los cantos ya los quita el fondo ajeno)
                        if (NaturalMath.nearBorder(x, y, w, h, sw, shh) && ((inkF < 0.01 && !(sC[it] / max(1, ar) >= NaturalColor.VIVID_K * cLo && max(w, h) < 8 * lhS)) ||
                                ((c == 2 || thk[it] > 1.2 * lhS) && inkF < 0.05))) 0
                        // (B/N: sólo lo que continúa tinta que el B/N ya tiene; ni color suelto ni figuras)
                        else if (inp.bridge == 2 && (c == 2 || inkF < 0.1)) 0
                        // (cuaderno: fuera de la zona de escritura -margen, marco, bandas impresas, tapa- sólo la tinta del B/N)
                        else if (inZone != null && inZone[it] < 0.5 && inkF < 0.5) 0
                        else if (c == 1 && inkF < 0.5 && !NaturalMath.strongEnough(sD[it] / max(1, ar), inkD, sC[it] / max(1, ar), cPaper)) 0
                        else c
                    }
                }
        NcProf.mark("g.comps")
                val sb = ByteArray(sw * shh); val fb = ByteArray(sw * shh)
                if (logComps) for (it in 1 until n) if (st[it * 5 + 4] > 15) log?.invoke("comp cls=${cls[it]} ${st[it*5]},${st[it*5+1]} ${st[it*5+2]}x${st[it*5+3]} a=${st[it*5+4]} ink=%.3f thk=%.1f C=%.1f D=%.3f lh=%.1f".format(inkN[it].toDouble()/st[it*5+4], thk[it], sC[it]/st[it*5+4], sD[it]/st[it*5+4], lhS))
                for (i in lb.indices) { when (cls[lb[i]]) { 1 -> sb[i] = -1; 2 -> fb[i] = -1 } }
                support.put(0, 0, sb); figures.put(0, 0, fb)
                // Trazos de color: algo de margen (bordes del trazo); figuras: margen y huecos rellenos
                Imgproc.dilate(support, support, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd((0.3 * lhS).roundToInt().coerceIn(3, 9))))
                if (Core.countNonZero(figures) > 0) {
                    Imgproc.dilate(figures, figures, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd((0.5 * lhS).roundToInt().coerceIn(3, 15))))
                    fillHoles(figures, bag)
                }
            }
            if (!inp.bridgeOnly && inp.photoMode == 2) figures.setTo(Scalar(0.0))
            if (!inp.bridgeOnly && inp.photoMode != 2) for (r in inp.images) {
                // (un bloque de imagen que es sobre todo fondo ajeno -la mesa o los cantos en una esquina- no es una figura)
                val rr = Cv.clampRect(Rect((r.x * s).toInt(), (r.y * s).toInt(), max(1, (r.width * s).roundToInt()), max(1, (r.height * s).roundToInt())), sw, shh)
                if (rr.width <= 0 || rr.height <= 0) continue
                if (softAll != null) { val sub = softAll.submat(rr); val fr = Core.countNonZero(sub).toDouble() / rr.area(); sub.release(); if (fr > 0.3) continue }
                Imgproc.rectangle(figures, org.opencv.core.Point(r.x * s, r.y * s), org.opencv.core.Point((r.x + r.width) * s - 1, (r.y + r.height) * s - 1), Scalar(255.0), -1)
                Imgproc.rectangle(photos, org.opencv.core.Point(r.x * s, r.y * s), org.opencv.core.Point((r.x + r.width) * s - 1, (r.y + r.height) * s - 1), Scalar(255.0), -1)
            }
            // Fotos recortadas: cada zona de foto (masa de tonos medios o color, no trazos) pasa ENTERA como rectángulo
            if (!inp.bridgeOnly && inp.photoMode == 1) for (r in photoRects(lum, chroma, paper, noExtras, lhS, cLo, bag)) {
                Imgproc.rectangle(figures, r.tl(), org.opencv.core.Point(r.x + r.width - 1.0, r.y + r.height - 1.0), Scalar(255.0), -1)
                Imgproc.rectangle(photos, r.tl(), org.opencv.core.Point(r.x + r.width - 1.0, r.y + r.height - 1.0), Scalar(255.0), -1)
            }
            // (la foto se pinta desde el original: nunca sobre el fondo ajeno -fuera de la hoja, mesa-)
            foreignAll2?.let { f -> val nf = bag.mat(); Core.bitwise_not(f, nf); Core.bitwise_and(photos, nf, photos); Core.bitwise_and(figures, nf, figures) }
        }
        NcProf.mark("g.masks")
        log?.invoke("global cPaper=%.1f cLo=%.1f tF=%.3f paper=%.0f,%.0f,%.0f noise=%.1f ink=$nInk inkFrac=%.3f color=%.4f fig=%.4f".format(cPaper, cLo, tF, pc[0], pc[1], pc[2], noise,
            nInk.toDouble() / sm.total(), Core.countNonZero(support).toDouble() / sm.total(), Core.countNonZero(figures).toDouble() / sm.total()))
        debug?.let { d -> d("cand", cm); d("support", support); d("figures", figures); val bgr = Mat(); Imgproc.cvtColor(smw, bgr, Imgproc.COLOR_RGB2BGR); d("smw", bgr); bgr.release() }
        // (una figura -foto, logo- no es un relleno liso: pasa con su propio color)
        fl?.let { (f, _) -> val nf = bag.mat(); Core.bitwise_not(figures, nf); Core.bitwise_and(f, nf, f) }
        NcProf.mark("g.figfill")
        debug?.let { d -> fl?.let { d("fills", it.first) }; foreignAll2?.let { d("foreign", it) }; softF[0]?.let { d("foreign_soft", it) } }
        return Global(gain, black, white, t0, support, figures, s, cLo, tF, fl?.first, fl?.second, max(FILL_DE, 2.5 * noise), cPaper, origWhite, photos, foreignAll2,
            softF[0]?.clone(), inkL = inkL, lw = lw?.clone(), lwCell = lwCellOf(sw, shh).toDouble())
    }

    /** Lado (px de la escala de análisis) de las celdas de [localLight]. */
    private fun lwCellOf(w: Int, h: Int): Int = max(8, max(w, h) / LWB_CELLS)

    /**
     * Balance de blancos LOCAL de la tinta (sombra cálida, luz de color sobre parte de la hoja). La división por el fondo deja
     * el papel neutro pero NO la tinta: con la luz tenue de la sombra la tinta oscura conserva la dominante de la luz (velo,
     * respuesta de la cámara) y la tinta negra o azul sale marrón. Por celdas (~[LWB_CELLS] en el lado largo): color del papel
     * en la foto ORIGINAL (mediana de los píxeles blancos en el B/N, fuera de [notPaper]) -> dominante de la luz T respecto al
     * papel de toda la página; dominante de la tinta del B/N en la imagen normalizada [smw]. La parte de la dominante de la
     * tinta en la dirección de T es k·T con un único k para toda la página (mediana de las celdas con luz de color: la tinta de
     * color de unas pocas celdas no lo cambia). Devuelve el desplazamiento (a, b) de Lab a RESTAR del color de la tinta
     * (32FC2, rejilla; mediana 3x3: una banda de color no es la luz) o null si la luz es uniforme.
     */
    private fun localLight(smw: Mat, orig: Mat, bws: Mat, notPaper: Mat?, bag: MatBag): Mat? {
        val w = smw.cols(); val h = smw.rows(); val n = w * h
        val cell = lwCellOf(w, h)
        val gw = (w + cell - 1) / cell; val gh = (h + cell - 1) / cell; val gn = gw * gh
        if (gw < 3 || gh < 3) return null
        val o = bag.mat(); areaDown(orig, o, smw.size(), bag)
        val labO = bag.mat(); Imgproc.cvtColor(o, labO, Imgproc.COLOR_RGB2Lab)
        val labS = bag.mat(); Imgproc.cvtColor(smw, labS, Imgproc.COLOR_RGB2Lab)
        val lo = ByteArray(n * 3); labO.get(0, 0, lo)
        val ls = ByteArray(n * 3); labS.get(0, 0, ls)
        val bb = ByteArray(n); bws.get(0, 0, bb)
        val np = notPaper?.let { m -> ByteArray(n).also { m.get(0, 0, it) } }
        // medianas por histograma (a y b de 8 bits)
        val pA = FloatArray(gn); val pB = FloatArray(gn); val pOk = BooleanArray(gn)
        val iA = FloatArray(gn); val iB = FloatArray(gn); val iOk = BooleanArray(gn)
        val hp = IntArray(512); val hi = IntArray(512); val gp = IntArray(512)
        fun median(hh: IntArray, off: Int, cnt: Int): Float { var acc = 0; var v = 0; while (v < 255 && acc + hh[off + v] < (cnt + 1) / 2) { acc += hh[off + v]; v++ }; return v - 128f }
        var gCnt = 0
        for (cy in 0 until gh) for (cx in 0 until gw) {
            java.util.Arrays.fill(hp, 0); java.util.Arrays.fill(hi, 0); var cp = 0; var ci = 0
            val y1 = min(h, (cy + 1) * cell); val x1 = min(w, (cx + 1) * cell)
            for (y in cy * cell until y1) for (x in cx * cell until x1) {
                val i = y * w + x; val v = bb[i].toInt() and 0xFF
                if (v >= 252) {
                    if (np != null && np[i].toInt() != 0) continue
                    hp[lo[i * 3 + 1].toInt() and 0xFF]++; hp[256 + (lo[i * 3 + 2].toInt() and 0xFF)]++; cp++
                } else if (v < 128) {
                    hi[ls[i * 3 + 1].toInt() and 0xFF]++; hi[256 + (ls[i * 3 + 2].toInt() and 0xFF)]++; ci++
                }
            }
            val k = cy * gw + cx
            if (cp >= 0.2 * (y1 - cy * cell) * (x1 - cx * cell)) {
                pOk[k] = true; pA[k] = median(hp, 0, cp); pB[k] = median(hp, 256, cp)
                for (q in 0 until 512) gp[q] += hp[q]; gCnt += cp
            }
            if (ci >= 20) { iOk[k] = true; iA[k] = median(hi, 0, ci); iB[k] = median(hi, 256, ci) }
        }
        if (gCnt < 0.05 * n) return null
        val gA = median(gp, 0, gCnt); val gB = median(gp, 256, gCnt)
        // k de la página: proyección de la dominante de la tinta sobre la de la luz, en las celdas con luz de color
        val ks = ArrayList<Double>()
        var tinted = 0
        for (k in 0 until gn) {
            if (!pOk[k]) continue
            val ta = (pA[k] - gA).toDouble(); val tb = (pB[k] - gB).toDouble(); val t2 = ta * ta + tb * tb
            if (t2 < LWB_T0 * LWB_T0) continue
            tinted++
            if (iOk[k]) ks.add((iA[k] * ta + iB[k] * tb) / t2)
        }
        if (ks.size < LWB_MIN_CELLS) { if (tinted > 0) log?.invoke("luz local: $tinted celdas con luz de color, sin tinta para medirla"); return null }
        ks.sort()
        val kk = ks[ks.size / 2].coerceIn(0.0, LWB_KMAX)
        log?.invoke("luz local: celdas ${gw}x$gh, con luz de color $tinted, k=%.2f (n=${ks.size})".format(kk))
        if (kk < 0.2) return null
        // desplazamiento por celda (celdas sin papel: el de sus vecinas)
        val sh = FloatArray(gn * 2); val ok = pOk.copyOf()
        for (k in 0 until gn) if (ok[k]) {
            val ta = (pA[k] - gA).toDouble(); val tb = (pB[k] - gB).toDouble()
            val r = NaturalMath.ramp(kotlin.math.sqrt(ta * ta + tb * tb), LWB_T0 * 0.6, LWB_T0)
            sh[k * 2] = (kk * ta * r).toFloat(); sh[k * 2 + 1] = (kk * tb * r).toFloat()
        }
        var nOk = ok.count { it }
        while (nOk < gn) {
            val add = ArrayList<Int>()
            for (k in 0 until gn) {
                if (ok[k]) continue
                val cx = k % gw; val cy = k / gw; var c = 0; var sa = 0f; var sb = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val x = cx + dx; val y = cy + dy
                    if (x < 0 || y < 0 || x >= gw || y >= gh || !ok[y * gw + x]) continue
                    val j = y * gw + x; sa += sh[j * 2]; sb += sh[j * 2 + 1]; c++
                }
                if (c > 0) { sh[k * 2] = sa / c; sh[k * 2 + 1] = sb / c; add.add(k) }
            }
            if (add.isEmpty()) break
            for (k in add) ok[k] = true
            nOk += add.size
        }
        val out = bag.add(Mat(gh, gw, CvType.CV_32FC2)); out.put(0, 0, sh)
        // (mediana 3x3: una banda o una mancha de color de una celda no es la luz; suavizado)
        run {
            val ch = ArrayList<Mat>(2); Core.split(out, ch)
            for (c in ch) { bag.add(c); val m = bag.mat(); c.convertTo(m, CvType.CV_8U, 2.0, 128.0); Imgproc.medianBlur(m, m, 3); m.convertTo(c, CvType.CV_32F, 0.5, -64.0) }
            Core.merge(ch, out)
        }
        Imgproc.GaussianBlur(out, out, Size(3.0, 3.0), 0.0, 0.0, Core.BORDER_REPLICATE)
        debug?.let { d -> val ch = ArrayList<Mat>(2); Core.split(out, ch); val v = bag.mat(); ch[1].convertTo(v, CvType.CV_8U, 4.0, 128.0); Imgproc.resize(v, v, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST); d("light_b", v); ch.forEach { it.release() } }
        return out
    }

    /** Resta el desplazamiento de Lab [shift] (32FC2, mismo tamaño) al color RGB 8UC3 [rgb] (en el sitio). */
    private fun shiftLab(rgb: Mat, shift: Mat, bag: MatBag) {
        val f = bag.mat(); rgb.convertTo(f, CvType.CV_32F, 1.0 / 255)
        val lab = bag.mat(); Imgproc.cvtColor(f, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>(3); Core.split(lab, ch); ch.forEach { bag.add(it) }
        val sc = ArrayList<Mat>(2); Core.split(shift, sc); sc.forEach { bag.add(it) }
        Core.subtract(ch[1], sc[0], ch[1]); Core.subtract(ch[2], sc[1], ch[2])
        Core.merge(ch, lab); Imgproc.cvtColor(lab, f, Imgproc.COLOR_Lab2RGB)
        f.convertTo(rgb, CvType.CV_8U, 255.0)
    }

    /**
     * Rellenos LISOS de color o gris (bandas de una tabla, celdas y recuadros sombreados) a la escala de análisis: el
     * fondo sin trazos (cierre que borra lo fino y oscuro + mediana) se aparta del papel blanco (ΔE >= [fillT]), es
     * uniforme, ocupa un área de varias letras, tiene grosor de banda, no toca el borde ni el fondo ajeno, y su
     * contorno es NÍTIDO (el papel de alrededor es papel: una sombra o un degradado de la iluminación no lo es).
     * Devuelve la máscara (255 = relleno) y el color del fondo sin trazos, o null si no hay ninguno.
     */
    private fun detectFills(smw: Mat, lhS: Double, noise: Double, notAllowed: Mat?, bag: MatBag): Pair<Mat, Mat>? {
        val w = smw.cols(); val h = smw.rows(); val n = w * h
        val k = Cv.odd(max(3, (0.7 * lhS).roundToInt()).coerceAtMost(15))
        val bgE = bag.mat(); Imgproc.morphologyEx(smw, bgE, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, k))
        Imgproc.medianBlur(bgE, bgE, 5)
        val lab = bag.mat(); Imgproc.cvtColor(bgE, lab, Imgproc.COLOR_RGB2Lab)
        val lb = ByteArray(n * 3); lab.get(0, 0, lb)
        val dev = FloatArray(n); val lv = FloatArray(n)
        for (i in 0 until n) {
            val l = (lb[i * 3].toInt() and 0xFF) * (100f / 255f)
            val a = (lb[i * 3 + 1].toInt() and 0xFF) - 128f; val b = (lb[i * 3 + 2].toInt() and 0xFF) - 128f
            lv[i] = l
            dev[i] = kotlin.math.sqrt((100f - l) * (100f - l) + a * a + b * b)
        }
        val t = max(FILL_DE, 2.5 * noise).toFloat()
        // Uniformidad: desviación típica local de L* (5x5)
        val lm = bag.add(Mat(h, w, CvType.CV_32F)); lm.put(0, 0, lv)
        val m1 = bag.mat(); Imgproc.boxFilter(lm, m1, -1, Size(5.0, 5.0))
        val m2 = bag.mat(); Core.multiply(lm, lm, m2); Imgproc.boxFilter(m2, m2, -1, Size(5.0, 5.0))
        val sd = FloatArray(n); run { val v = bag.mat(); Core.multiply(m1, m1, v); Core.subtract(m2, v, v); v.get(0, 0, sd) }
        val mb = ByteArray(n)
        for (i in 0 until n) if (dev[i] >= t && sd[i] <= FILL_SD * FILL_SD) mb[i] = -1
        val mask = bag.add(Mat(h, w, CvType.CV_8U)); mask.put(0, 0, mb)
        notAllowed?.let { na -> val inv = bag.mat(); Core.bitwise_not(na, inv); Core.bitwise_and(mask, inv, mask) }
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val labels = bag.mat(); val stats = bag.mat(); val cent = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(mask, labels, stats, cent, 4, CvType.CV_32S)
        if (nc <= 1) return null
        val st = IntArray(nc * 5); stats.get(0, 0, st)
        val lab32 = IntArray(n); labels.get(0, 0, lab32)
        // Grosor (2 x máxima distancia al borde), media de ΔE dentro (núcleo) y en el anillo de 2-4 px de fuera
        val dt = bag.mat(); Imgproc.distanceTransform(mask, dt, Imgproc.DIST_L2, 3)
        val dtA = FloatArray(n); dt.get(0, 0, dtA)
        val thk = FloatArray(nc); val sIn = DoubleArray(nc); val nIn = IntArray(nc)
        for (i in 0 until n) {
            val l = lab32[i]; if (l == 0) continue
            if (2 * dtA[i] > thk[l]) thk[l] = 2 * dtA[i]
            if (dtA[i] >= 2f) { sIn[l] += dev[i]; nIn[l]++ }
        }
        val labF = bag.mat(); labels.convertTo(labF, CvType.CV_32F)
        val near = bag.mat(); Imgproc.dilate(labF, near, Cv.kernel(Imgproc.MORPH_RECT, 11))
        val close1 = bag.mat(); Imgproc.dilate(mask, close1, Cv.kernel(Imgproc.MORPH_RECT, 5))
        val nearA = FloatArray(n); near.get(0, 0, nearA)
        val c1 = ByteArray(n); close1.get(0, 0, c1)
        val sOut = DoubleArray(nc); val nOut = IntArray(nc)
        for (i in 0 until n) {
            if (c1[i].toInt() != 0) continue
            val l = nearA[i].toInt(); if (l <= 0 || l >= nc) continue
            sOut[l] += dev[i]; nOut[l]++
        }
        val keep = BooleanArray(nc)
        for (c in 1 until nc) {
            val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bh = st[c * 5 + 3]; val ar = st[c * 5 + 4]
            val fill = ar.toDouble() / max(1, bw * bh)
            val dIn = if (nIn[c] > 0) sIn[c] / nIn[c] else 0.0
            val dOut = if (nOut[c] > 0) sOut[c] / nOut[c] else dIn
            keep[c] = NaturalMath.isFlatFill(ar, fill, thk[c].toDouble(), lhS, dIn, dOut, NaturalMath.touchesBorder(x, y, bw, bh, w, h) || NaturalMath.nearBorder(x, y, bw, bh, w, h, 0.02))
            if (logComps && ar > 4 * lhS * lhS) log?.invoke("fill keep=${keep[c]} $x,$y ${bw}x$bh a=$ar fill=%.2f thk=%.1f dIn=%.1f dOut=%.1f lh=%.1f".format(fill, thk[c], dIn, dOut, lhS))
        }
        if (keep.none { it }) return null
        val fb = ByteArray(n)
        for (i in 0 until n) { val l = lab32[i]; if (l != 0 && keep[l]) fb[i] = -1 }
        val fills = Mat(h, w, CvType.CV_8U); fills.put(0, 0, fb)
        // Letras y trazos dentro del relleno (huecos pequeños de la máscara) también son relleno; luego 1-2 px de margen
        // (el contorno exacto se afina a resolución completa en cada tesela)
        fillSmallHoles(fills, 8.0 * lhS * lhS, bag)
        // Color de cada píxel del relleno: el fondo sin trazos (cierre + mediana; un peine de bandas grises y columnas
        // naranjas conserva el color de cada parte), algo suavizado
        val fillCol = Mat(); Imgproc.blur(bgE, fillCol, Size(3.0, 3.0))
        Imgproc.dilate(fills, fills, Cv.kernel(Imgproc.MORPH_RECT, 3))
        return fills to fillCol
    }

    /** Rellena los huecos de [m] de área <= [maxArea] (letras dentro de una banda de color). */
    private fun fillSmallHoles(m: Mat, maxArea: Double, bag: MatBag) {
        val cs = ArrayList<MatOfPoint>(); val hier = bag.mat()
        Imgproc.findContours(m.clone().also { bag.add(it) }, cs, hier, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
        if (cs.isEmpty()) return
        val hr = IntArray(cs.size * 4); hier.get(0, 0, hr)
        for (i in cs.indices) if (hr[i * 4 + 3] >= 0 && Imgproc.contourArea(cs[i]) <= maxArea) Imgproc.drawContours(m, cs, i, Scalar(255.0), -1)
        cs.forEach { it.release() }
    }

    /**
     * Fondo AJENO que el recorte dejó dentro (madera, mesa, el canto o la carcasa de un móvil), a la escala de análisis,
     * sobre la foto ORIGINAL (la normalizada iguala la madera al papel): regiones pegadas al borde de la imagen cuyo
     * color se aparta del papel en CROMATICIDAD ([NaturalMath.isForeignColor]: madera, mesa de color; una sombra sobre
     * el papel, aunque sea muy oscura, conserva la del papel), gruesas (>= 3 letras). null si no hay.
     */
    private fun foreignMask(orig: Mat, size: Size, bws: Mat, lhS: Double, bag: MatBag, softOut: Array<Mat?>, images: List<Rect>): Mat? {
        val w = size.width.toInt(); val h = size.height.toInt(); val n = w * h
        val o = bag.mat(); areaDown(orig, o, size, bag)
        Imgproc.medianBlur(o, o, 5)
        val px = ByteArray(n * 3); o.get(0, 0, px)
        val bwB = ByteArray(n); bws.get(0, 0, bwB)
        // Papel: blanco en el B/N, en el 60 % central (cromaticidad y luminancia medias)
        var sr = 0.0; var sg = 0.0; var sl = 0.0; var cnt = 0
        for (y in (0.2 * h).toInt() until (0.8 * h).toInt()) for (x in (0.2 * w).toInt() until (0.8 * w).toInt()) {
            val i = y * w + x
            if ((bwB[i].toInt() and 0xFF) < 250) continue
            val r = (px[i * 3].toInt() and 0xFF) + 1.0; val g = (px[i * 3 + 1].toInt() and 0xFF) + 1.0; val b = (px[i * 3 + 2].toInt() and 0xFF) + 1.0
            val sum = r + g + b
            sr += r / sum; sg += g / sum; sl += sum; cnt++
        }
        if (cnt < 0.05 * n) { log?.invoke("ajeno: poco papel"); return null }
        val pr = sr / cnt; val pg = sg / cnt; val pl = sl / cnt
        val cb = ByteArray(n)
        for (i in 0 until n) {
            val r = (px[i * 3].toInt() and 0xFF) + 1.0; val g = (px[i * 3 + 1].toInt() and 0xFF) + 1.0; val b = (px[i * 3 + 2].toInt() and 0xFF) + 1.0
            val sum = r + g + b
            val dr = r / sum - pr; val dg = g / sum - pg
            if (NaturalMath.isForeignColor(kotlin.math.sqrt(dr * dr + dg * dg), sum / pl)) cb[i] = -1
        }
        val cand = bag.add(Mat(h, w, CvType.CV_8U)); cand.put(0, 0, cb)
        Imgproc.morphologyEx(cand, cand, Imgproc.MORPH_OPEN, Cv.kernel(Imgproc.MORPH_RECT, 5))
        debug?.let { d -> d("foreign_cand", cand); val bgr = Mat(); Imgproc.cvtColor(o, bgr, Imgproc.COLOR_RGB2BGR); d("orig_small", bgr); bgr.release() }
        val labels = bag.mat(); val stats = bag.mat(); val cent = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(cand, labels, stats, cent, 8, CvType.CV_32S)
        val st = IntArray(max(1, nc) * 5); if (nc > 0) stats.get(0, 0, st)
        // (pegadas al borde de la imagen -0.5 %-: un resaltador o una banda de color de una página con márgenes estrechos no)
        // y GRUESAS (>= 3 letras: un resaltador o una banda de color de la página son finos)
        val la = IntArray(n); if (nc > 1) labels.get(0, 0, la)
        val thk = FloatArray(max(1, nc))
        run { val dt = bag.mat(); Imgproc.distanceTransform(cand, dt, Imgproc.DIST_L2, 3); val d = FloatArray(n); dt.get(0, 0, d); for (i in 0 until n) if (2 * d[i] > thk[la[i]]) thk[la[i]] = 2 * d[i] }
        val keep = BooleanArray(nc) { c -> c > 0 && st[c * 5 + 4] >= 0.004 * n && thk[c] >= 3 * lhS && NaturalMath.nearBorder(st[c * 5], st[c * 5 + 1], st[c * 5 + 2], st[c * 5 + 3], w, h, 0.005) }
        // Con escritura dentro (tinta del B/N de densidad de texto: 0.5-15 %) es una banda impresa o una sombra de color
        // sobre la hoja: sólo se quita lo añadido (color, figuras, rellenos), no la tinta del B/N ([soft]); la madera o la
        // mesa (vetas: tinta muy densa, o nada) se quitan enteras
        val sdLc = DoubleArray(nc)
        // Madera / mesa: UN tono (concentración del tono >= 0.75) y croma moderado; una ilustración o una foto de color
        // pegada al borde (libro infantil, revista) tiene muchos tonos o mucho croma -> no es fondo ajeno
        run {
            val lab = bag.mat(); Imgproc.cvtColor(o, lab, Imgproc.COLOR_RGB2Lab)
            val lb = ByteArray(n * 3); lab.get(0, 0, lb)
            val sa = DoubleArray(nc); val sb2 = DoubleArray(nc); val sc = DoubleArray(nc); val sl = DoubleArray(nc); val sl2 = DoubleArray(nc)
            val inImg = IntArray(nc)
            val imgM = ByteArray(n)
            for (r in images) for (yy in max(0, r.y) until min(h, r.y + r.height)) for (xx in max(0, r.x) until min(w, r.x + r.width)) imgM[yy * w + xx] = 1
            for (i in 0 until n) {
                val l = la[i]; if (!keep[l]) continue
                val L = (lb[i * 3].toInt() and 0xFF) * 100.0 / 255
                val a = (lb[i * 3 + 1].toInt() and 0xFF) - 128.0; val b = (lb[i * 3 + 2].toInt() and 0xFF) - 128.0
                sa[l] += a; sb2[l] += b; sc[l] += kotlin.math.sqrt(a * a + b * b); sl[l] += L; sl2[l] += L * L
                if (imgM[i].toInt() != 0) inImg[l]++
            }
            for (c in 1 until nc) if (keep[c]) {
                val ar = max(1, st[c * 5 + 4]).toDouble()
                sdLc[c] = kotlin.math.sqrt(max(0.0, sl2[c] / ar - (sl[c] / ar) * (sl[c] / ar)))
                val conc = kotlin.math.sqrt(sa[c] * sa[c] + sb2[c] * sb2[c]) / max(1e-6, sc[c])
                val sdL = kotlin.math.sqrt(max(0.0, sl2[c] / ar - (sl[c] / ar) * (sl[c] / ar)))
                // (un bloque de imagen -foto, ilustración- pegado al borde no es fondo ajeno)
                if (logComps) log?.invoke("ajeno comp ${st[c * 5]},${st[c * 5 + 1]} ${st[c * 5 + 2]}x${st[c * 5 + 3]} conc=%.2f C=%.1f sdL=%.1f img=%.2f".format(conc, sc[c] / ar, sdL, inImg[c] / ar))
                if (!NaturalMath.foreignHue(conc, sc[c] / ar, sdL) || inImg[c] >= 0.1 * ar) keep[c] = false
            }
        }
        // (tinta del B/N a esta escala: < 200 -el texto fino se aclara al reducir-)
        val inkN = IntArray(nc); for (i in 0 until n) if (keep[la[i]] && (bwB[i].toInt() and 0xFF) < 200) inkN[la[i]]++
        val hard = BooleanArray(nc) { c -> keep[c] && NaturalMath.foreignIsHard(inkN[c].toDouble() / max(1, st[c * 5 + 4]), sdLc[c]) }
        val fb = ByteArray(n); val sb = ByteArray(n); var any = false; var anyS = false
        for (i in 0 until n) { val l = la[i]; if (hard[l]) { fb[i] = -1; any = true } else if (keep[l]) { sb[i] = -1; anyS = true } }
        if (anyS) { softOut[0] = Mat(h, w, CvType.CV_8U).also { it.put(0, 0, sb); Imgproc.dilate(it, it, Cv.kernel(Imgproc.MORPH_RECT, 5)) } }
        if (!any) { log?.invoke("ajeno: nada (candidatos %.3f, comps $nc) orig=${orig.cols()}x${orig.rows()}x${orig.channels()}".format(Core.countNonZero(cand).toDouble() / n)); return null }
        val f = Mat(h, w, CvType.CV_8U); f.put(0, 0, fb)
        if (Core.countNonZero(f) > 0.5 * n) { log?.invoke("ajeno: más de media imagen, se ignora"); f.release(); return null }   // (más de media imagen: no es un resto del recorte)
        Imgproc.dilate(f, f, Cv.kernel(Imgproc.MORPH_RECT, 5))
        log?.invoke("ajeno papel r=%.3f g=%.3f frac=%.3f".format(pr, pg, Core.countNonZero(f).toDouble() / n))
        return f
    }

    /**
     * Cantos de las hojas de debajo y bordes de la mesa que quedan algo DENTRO de la imagen (hasta el 12 % del borde):
     * trazos del B/N muy largos (>= 20 % del lado), finos, casi neutros (una línea de margen roja o una recta de color
     * no), o largos y finos en la franja del 5 % ([NaturalMath.borderStroke]), y enteros FUERA de la mancha del texto
     * (percentiles 0.5-99.5 % de las letras -componentes pequeñas- con dos letras de margen). null si no hay.
     */
    private fun edgeLines(bws: Mat, chroma: Mat, lhS: Double, cLo: Double, bag: MatBag): Mat? {
        val w = bws.cols(); val h = bws.rows(); val n = w * h
        val ink = bag.mat(); Imgproc.threshold(bws, ink, 128.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val lab = bag.mat(); val st0 = bag.mat(); val c0 = bag.mat()
        val m = Imgproc.connectedComponentsWithStats(ink, lab, st0, c0, 8, CvType.CV_32S)
        if (m <= 2) return null
        val st = IntArray(m * 5); st0.get(0, 0, st)
        // mancha del texto: extremos (1-99 %) de las componentes pequeñas (letras)
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (c in 1 until m) if (max(st[c * 5 + 2], st[c * 5 + 3]) <= 3 * lhS && st[c * 5 + 4] >= 3) { xs.add(st[c * 5] + st[c * 5 + 2] / 2); ys.add(st[c * 5 + 1] + st[c * 5 + 3] / 2) }
        if (xs.size < 30) return null
        xs.sort(); ys.sort()
        val tx0 = xs[(0.005 * xs.size).toInt()] - 2 * lhS; val tx1 = xs[(0.995 * xs.size).toInt().coerceAtMost(xs.size - 1)] + 2 * lhS
        val ty0 = ys[(0.005 * ys.size).toInt()] - 2 * lhS; val ty1 = ys[(0.995 * ys.size).toInt().coerceAtMost(ys.size - 1)] + 2 * lhS
        val la = IntArray(n); lab.get(0, 0, la)
        val ch = FloatArray(n); chroma.get(0, 0, ch)
        val sc = DoubleArray(m); for (i in 0 until n) if (la[i] != 0) sc[la[i]] += ch[i]
        val drop = BooleanArray(m)
        for (c in 1 until m) {
            val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bh = st[c * 5 + 3]; val ar = st[c * 5 + 4]
            val long = max(bw, bh).toDouble()
            val outside = x + bw < tx0 || x > tx1 || y + bh < ty0 || y > ty1
            if (!outside) continue
            // en la franja del 5 %: trazo largo y fino (cantos, borde de la mesa o de la carcasa)
            if (NaturalMath.borderStroke(x, y, bw, bh, ar, w, h, lhS)) { drop[c] = true; continue }
            if (long < 0.2 * min(w, h) || ar / long > 0.8 * lhS || sc[c] / ar >= cLo) continue
            if (NaturalMath.nearBorder(x, y, bw, bh, w, h, 0.12)) drop[c] = true
        }
        if (drop.none { it }) return null
        val b = ByteArray(n); for (i in 0 until n) if (drop[la[i]]) b[i] = -1
        val f = Mat(h, w, CvType.CV_8U); f.put(0, 0, b)
        Imgproc.dilate(f, f, Cv.kernel(Imgproc.MORPH_RECT, 5))
        log?.invoke("cantos: ${drop.count { it }} trazos (texto x %.0f-%.0f y %.0f-%.0f)".format(tx0, tx1, ty0, ty1))
        return f
    }

    /** [sheetOutside] a media escala de análisis (las rectas de los cantos no necesitan más; ~4 veces menos trabajo). */
    private fun sheetOutsideHalf(orig: Mat, bws: Mat, size: Size, lhS: Double, bag: MatBag): Mat? {
        if (max(size.width, size.height) < 600) return sheetOutside(orig, bws, size, lhS, bag)
        val s2 = Size(max(1.0, (size.width / 2).roundToInt().toDouble()), max(1.0, (size.height / 2).roundToInt().toDouble()))
        val b2 = bag.mat(); Imgproc.resize(bws, b2, s2, 0.0, 0.0, Imgproc.INTER_AREA)
        val m = sheetOutside(orig, b2, s2, lhS / 2, bag) ?: return null
        val up = Mat(); Imgproc.resize(m, up, size, 0.0, 0.0, Imgproc.INTER_NEAREST); m.release()
        return up
    }

    /** Recta del canto de la hoja: x = a + b·y (lado vertical) o y = a + b·x (horizontal) en px de análisis. */
    internal data class SheetEdge(val side: Int, val a: Double, val b: Double)

    /**
     * Zona FUERA de la hoja principal (8U, 255) a la escala de análisis, o null: el recorte dejó dentro cantos de las hojas de
     * debajo, la funda del móvil o la mesa, que el B/N y el color conservaban como franjas beis o marrones. Por cada lado,
     * rectas largas (>= 30 % del lado) casi paralelas a él (<= 12°) en la banda exterior (20 %) de los bordes de la foto
     * ORIGINAL; vale la más interior que es el CANTO de una hoja ([NaturalMath.sheetEdgeOk]): un escalón de color (ΔE
     * mediano >= [SHEET_STEP] entre los dos lados a ~0.6 letras; una raya impresa tiene papel a ambos lados), papel del lado
     * de dentro y sin escritura del otro lado (<= [SHEET_LETTERS] componentes de tamaño de letra del B/N: el texto cerca del
     * margen no se come). Lo que queda entre esa recta y el borde de la imagen sale blanco.
     */
    private fun sheetOutside(orig: Mat, bws: Mat, size: Size, lhS: Double, bag: MatBag): Mat? {
        val w = size.width.toInt(); val h = size.height.toInt(); val n = w * h
        val o = bag.mat(); areaDown(orig, o, size, bag)
        Imgproc.GaussianBlur(o, o, Size(0.0, 0.0), 1.2)
        val g = bag.add(Cv.gray(o))
        val ga = ByteArray(n); g.get(0, 0, ga)
        val ed = bag.mat(); Imgproc.Canny(g, ed, 12.0, 36.0)
        val lines = bag.mat()
        val minLen = 0.3 * min(w, h)
        Imgproc.HoughLinesP(ed, lines, 1.0, Math.PI / 180, max(40, (0.15 * min(w, h)).roundToInt()), minLen, 0.02 * max(w, h))
        if (lines.rows() == 0) return null
        val lab = bag.mat(); Imgproc.cvtColor(o, lab, Imgproc.COLOR_RGB2Lab)
        val lb = ByteArray(n * 3); lab.get(0, 0, lb)
        val bb = ByteArray(n); bws.get(0, 0, bb)
        // color (a, b) del papel de la página: mediana de los blancos del B/N del 60 % central
        val pab = run {
            val ha = IntArray(256); val hb = IntArray(256); val hl = IntArray(256); var c = 0
            for (y in (0.2 * h).toInt() until (0.8 * h).toInt()) for (x in (0.2 * w).toInt() until (0.8 * w).toInt()) {
                val i = y * w + x; if ((bb[i].toInt() and 0xFF) < 250) continue
                hl[lb[i * 3].toInt() and 0xFF]++; ha[lb[i * 3 + 1].toInt() and 0xFF]++; hb[lb[i * 3 + 2].toInt() and 0xFF]++; c++
            }
            if (c < 100) return null
            fun med(hh: IntArray): Double { var acc = 0; var v = 0; while (v < 255 && acc + hh[v] < (c + 1) / 2) { acc += hh[v]; v++ }; return v.toDouble() }
            doubleArrayOf(med(ha), med(hb), med(hl))
        }
        // (del color del papel en a, b y no mucho más oscuro que él -una sombra sí, la funda negra del móvil no-)
        fun paperAb(i: Int, tol: Double): Boolean = (lb[i * 3].toInt() and 0xFF) >= 0.35 * pab[2] &&
            kotlin.math.hypot((lb[i * 3 + 1].toInt() and 0xFF) - pab[0], (lb[i * 3 + 2].toInt() and 0xFF) - pab[1]) < tol
        // letras del B/N (centros) para "sin escritura del otro lado"
        val ink = bag.mat(); Imgproc.threshold(bws, ink, 128.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val cl = bag.mat(); val cs = bag.mat(); val cc = bag.mat()
        val nc = Imgproc.connectedComponentsWithStats(ink, cl, cs, cc, 8, CvType.CV_32S)
        val st = IntArray(max(1, nc) * 5); if (nc > 0) cs.get(0, 0, st)
        val letters = ArrayList<DoubleArray>()
        for (c in 1 until nc) {
            val bw0 = st[c * 5 + 2]; val bh0 = st[c * 5 + 3]; val ar = st[c * 5 + 4]
            val mx = max(bw0, bh0)
            // (letras y palabras de cualquier cuerpo -pie de página pequeño, titular grande, palabra manuscrita enlazada-; no
            // las rayas largas y finas: cantos, bordes de la mesa o de la funda)
            if (mx < 0.15 * lhS || mx > 6.0 * lhS || ar < 4 || ar > 0.7 * bw0 * bh0) continue
            // (lo pegado al borde de la imagen es resto del recorte, no escritura)
            val bm = 3
            if (st[c * 5] <= bm || st[c * 5 + 1] <= bm || st[c * 5] + bw0 >= w - bm || st[c * 5 + 1] + bh0 >= h - bm) continue
            if (mx > 3.0 * lhS && min(bw0, bh0) < 0.4 * lhS) continue
            letters.add(doubleArrayOf(st[c * 5] + bw0 / 2.0, st[c * 5 + 1] + bh0 / 2.0, mx.toDouble()))
        }
        val seg = IntArray(lines.rows() * 4); lines.get(0, 0, seg)
        val best = arrayOfNulls<SheetEdge>(4); val bestD = DoubleArray(4) { -1.0 }
        val d = max(3.0, 0.6 * lhS)
        for (q in 0 until lines.rows()) {
            val x1 = seg[q * 4].toDouble(); val y1 = seg[q * 4 + 1].toDouble(); val x2 = seg[q * 4 + 2].toDouble(); val y2 = seg[q * 4 + 3].toDouble()
            val vert = kotlin.math.abs(x2 - x1) <= 0.21 * kotlin.math.abs(y2 - y1)
            val hor = kotlin.math.abs(y2 - y1) <= 0.21 * kotlin.math.abs(x2 - x1)
            if (!vert && !hor) continue
            // lado: 0 arriba, 1 derecha, 2 abajo, 3 izquierda (como [ImageEnhancer.Options.sheetSides])
            val side = when {
                vert && max(x1, x2) < 0.2 * w -> 3
                vert && min(x1, x2) > 0.8 * w -> 1
                hor && max(y1, y2) < 0.2 * h -> 0
                hor && min(y1, y2) > 0.8 * h -> 2
                else -> continue
            }
            val e0 = if (vert) SheetEdge(side, x1 - y1 * (x2 - x1) / (y2 - y1), (x2 - x1) / (y2 - y1))
                else SheetEdge(side, y1 - x1 * (y2 - y1) / (x2 - x1), (y2 - y1) / (x2 - x1))
            // (el tramo de Hough puede unir trozos de cantos distintos: recta reajustada al máximo del gradiente a lo largo del lado)
            val e = refineEdge(e0, vert, ga, w, h, if (vert) kotlin.math.abs(y2 - y1) else kotlin.math.abs(x2 - x1)) ?: continue
            // profundidad (distancia media al borde de la imagen): la más interior que valga
            // profundidad de la recta REAJUSTADA (en sus dos extremos, dentro de la banda exterior del 25 %)
            val len = if (vert) h.toDouble() else w.toDouble(); val dim = if (vert) w.toDouble() else h.toDouble()
            val dA = if (side == 0 || side == 3) e.a else dim - e.a; val dB = if (side == 0 || side == 3) e.a + e.b * len else dim - (e.a + e.b * len)
            if (min(dA, dB) < -0.1 * dim || max(dA, dB) > 0.25 * dim) continue
            val depth = (dA + dB) / 2
            if (depth <= bestD[side]) continue
            // escalón y papel dentro a lo largo de TODA la recta (una recta que sólo en parte es un canto -el borde de la funda- y
            // luego cruza la hoja no vale)
            val t0 = 0.0; val t1 = (if (vert) h else w) - 1.0
            val inw = if (side == 0 || side == 3) 1.0 else -1.0   // sentido hacia dentro de la hoja
            val des = ArrayList<Double>(); val dv3 = ArrayList<DoubleArray>(); val cOut = ArrayList<Double>(); val lOut = ArrayList<Double>(); val abOut = ArrayList<Double>(); var sL = 0.0; var sL2 = 0.0; var paperIn = 0; var tot = 0
            var t = t0
            while (t <= t1) {
                val c = e.a + e.b * t
                val pi = if (vert) intArrayOf((c + inw * d).roundToInt(), t.roundToInt()) else intArrayOf(t.roundToInt(), (c + inw * d).roundToInt())
                val po = if (vert) intArrayOf((c - inw * d).roundToInt(), t.roundToInt()) else intArrayOf(t.roundToInt(), (c - inw * d).roundToInt())
                t += 3.0
                if (pi[0] !in 0 until w || pi[1] !in 0 until h || po[0] !in 0 until w || po[1] !in 0 until h) continue
                val ii = pi[1] * w + pi[0]; val io = po[1] * w + po[0]
                var s2 = 0.0
                val v3 = DoubleArray(3)
                for (k in 0 until 3) { val dv = ((lb[ii * 3 + k].toInt() and 0xFF) - (lb[io * 3 + k].toInt() and 0xFF)) * (if (k == 0) 100.0 / 255 else 1.0); v3[k] = dv; s2 += dv * dv }
                des.add(kotlin.math.sqrt(s2)); dv3.add(v3); tot++
                cOut.add(kotlin.math.hypot((lb[io * 3 + 1].toInt() and 0xFF) - 128.0, (lb[io * 3 + 2].toInt() and 0xFF) - 128.0))
                val lo = (lb[io * 3].toInt() and 0xFF) * 100.0 / 255; sL += lo; sL2 += lo * lo; lOut.add(lo)
                abOut.add(kotlin.math.hypot((lb[io * 3 + 1].toInt() and 0xFF) - pab[0], (lb[io * 3 + 2].toInt() and 0xFF) - pab[1]))
                if ((bb[ii].toInt() and 0xFF) >= 200 && paperAb(ii, 8.0)) paperIn++
            }
            if (tot < max(10.0, 0.5 * (t1 - t0) / 3.0)) continue
            des.sort()
            val step = des[des.size / 2]
            // (y escalón en casi todo el lado: una recta que cruza la hoja tiene papel a los dos lados en ese tramo)
            // (del MISMO signo: el escalón de un canto es siempre el mismo -de papel a mesa u hoja de debajo-; una línea de
            // texto o una mancha cruzan la recta con saltos de signo y tamaño variables)
            val med = DoubleArray(3) { k -> dv3.map { it[k] }.sorted()[dv3.size / 2] }
            val mn = kotlin.math.sqrt(med.sumOf { it * it })
            val stepFrac = if (mn < 1e-6) 0.0 else dv3.count { v -> (v[0] * med[0] + v[1] * med[1] + v[2] * med[2]) / mn >= 0.5 * SHEET_STEP }.toDouble() / dv3.size
            // escritura del otro lado (entre la recta y el borde de la imagen) sobre un fondo del MISMO COLOR que el papel de
            // la página (a, b: aunque esté en sombra, más oscuro); las astillas de la madera o los restos de la funda tienen otro
            val abStep = dv3.map { kotlin.math.hypot(it[1], it[2]) }.sorted()[dv3.size / 2]
            var beyond = 0
            for (p in letters) {
                val c = e.a + e.b * (if (vert) p[1] else p[0])
                val v = if (vert) p[0] else p[1]
                if ((v - c) * inw >= -0.3 * lhS) continue
                // fondo de la letra: píxeles blancos del B/N alrededor (a la mitad de su tamaño + 0.3 letras)
                var near = 0; var fr = 0
                val rr = 0.5 * p[2] + 0.3 * lhS
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to -1, 1 to -1, -1 to 1)) {
                    val x = (p[0] + dx * rr).roundToInt(); val y = (p[1] + dy * rr).roundToInt()
                    if (x !in 0 until w || y !in 0 until h) continue
                    val i = y * w + x
                    if ((bb[i].toInt() and 0xFF) < 200) continue
                    fr++; if (paperAb(i, max(6.0, 0.5 * abStep))) near++
                }
                if (fr > 0 && near * 2 >= fr) { beyond++; if (logComps) log?.invoke("  letra fuera %.0f,%.0f tam %.0f".format(p[0], p[1], p[2])) }
            }
            // (una banda impresa de color vivo y LISA -pie o cabecera de la página- no es fondo ajeno; la madera tiene vetas)
            val chrOut = cOut.sorted()[cOut.size / 2]
            val sdOut = kotlin.math.sqrt(max(0.0, sL2 / tot - (sL / tot) * (sL / tot)))
            // (y fuera hay FONDO AJENO: con textura -madera, objetos, cantos- y más oscuro que el papel o de otro color; una banda
            // impresa lisa -pie de foto negro, cabecera de color- o el margen de la página no)
            val lMed = lOut.sorted()[lOut.size / 2]; val abMed = abOut.sorted()[abOut.size / 2]
            val foreignOut = sdOut >= SHEET_OUT_SD && (lMed < 0.75 * pab[2] * 100.0 / 255 || abMed >= 8.0)
            val ok = NaturalMath.sheetEdgeOk(step, stepFrac, paperIn.toDouble() / tot, beyond) && foreignOut && !(chrOut > SHEET_OUT_CHROMA && sdOut < 8.0)
            if (logComps) log?.invoke("canto lado $side prof=%.0f escalón=%.1f (%.2f) papel=%.2f croma fuera=%.0f σL=%.1f L=%.0f Δab=%.0f letras fuera=$beyond -> $ok".format(depth, step, stepFrac, paperIn.toDouble() / tot, chrOut, sdOut, lMed, abMed))
            if (ok) { best[side] = e; bestD[side] = depth }
        }
        if (best.all { it == null }) return null
        val m = Mat.zeros(h, w, CvType.CV_8U)
        val mg = 1.0   // (1 px hacia dentro: la propia línea del canto)
        for (side in 0..3) {
            val e = best[side] ?: continue
            val pts = when (side) {
                3 -> listOf(0.0 to 0.0, e.a + mg to 0.0, e.a + e.b * h + mg to h.toDouble(), 0.0 to h.toDouble())
                1 -> listOf(w.toDouble() to 0.0, e.a - mg to 0.0, e.a + e.b * h - mg to h.toDouble(), w.toDouble() to h.toDouble())
                0 -> listOf(0.0 to 0.0, 0.0 to e.a + mg, w.toDouble() to e.a + e.b * w + mg, w.toDouble() to 0.0)
                else -> listOf(0.0 to h.toDouble(), 0.0 to e.a - mg, w.toDouble() to e.a + e.b * w - mg, w.toDouble() to h.toDouble())
            }
            val mp = MatOfPoint(*pts.map { org.opencv.core.Point(it.first, it.second) }.toTypedArray())
            Imgproc.fillPoly(m, listOf(mp), Scalar(255.0)); mp.release()
        }
        log?.invoke("canto de la hoja: lados " + (0..3).filter { best[it] != null } + " fuera %.3f".format(Core.countNonZero(m).toDouble() / n))
        debug?.let { d -> val v = bag.mat(); Imgproc.cvtColor(o, v, Imgproc.COLOR_RGB2BGR); v.setTo(Scalar(0.0, 0.0, 255.0), m); d("sheet", v) }
        return m
    }

    /**
     * Reajusta la recta [e] del canto: a lo largo de todo el lado, el máximo del gradiente perpendicular (gris [g]) a +-6 px
     * de la recta; mínimos cuadrados sobre esos puntos y otra vez sólo con los que quedan a <= 2 px. null si el canto no
     * sigue la recta en al menos el 80 % de la longitud del tramo detectado (una recta que une trozos de cantos distintos y cruza la hoja).
     */
    private fun refineEdge(e: SheetEdge, vert: Boolean, g: ByteArray, w: Int, h: Int, segLen: Double): SheetEdge? {
        val len = if (vert) h else w
        val ts = ArrayList<Double>(); val cs = ArrayList<Double>()
        var t = 0
        fun gv(x: Int, y: Int) = g[y * w + x].toInt() and 0xFF
        while (t < len) {
            val c0 = e.a + e.b * t
            var best = 0; var bu = 0
            for (u in -6..6) {
                val c = (c0 + u).roundToInt()
                val gr = if (vert) { if (c - 1 < 0 || c + 1 >= w) continue; kotlin.math.abs(gv(c + 1, t) - gv(c - 1, t)) }
                    else { if (c - 1 < 0 || c + 1 >= h) continue; kotlin.math.abs(gv(t, c + 1) - gv(t, c - 1)) }
                if (gr > best) { best = gr; bu = u }
            }
            if (best >= 6) { ts.add(t.toDouble()); cs.add((c0 + bu).roundToInt().toDouble()) }
            t += 2
        }
        val need = 0.8 * segLen / 2   // (puntos cada 2 px)
        if (ts.size < need) return null
        fun fit(idx: List<Int>): Pair<Double, Double>? {
            if (idx.size < 10) return null
            var st = 0.0; var sc = 0.0; var stt = 0.0; var stc = 0.0
            for (i in idx) { st += ts[i]; sc += cs[i]; stt += ts[i] * ts[i]; stc += ts[i] * cs[i] }
            val m = idx.size.toDouble(); val den = m * stt - st * st
            if (kotlin.math.abs(den) < 1e-9) return null
            val b = (m * stc - st * sc) / den
            return (sc - b * st) / m to b
        }
        var ab = fit(ts.indices.toList()) ?: return null
        var nIn = 0
        repeat(2) {
            val inl = ts.indices.filter { kotlin.math.abs(cs[it] - (ab.first + ab.second * ts[it])) <= 2.0 }
            nIn = inl.size
            ab = fit(inl) ?: return null
        }
        if (logComps) log?.invoke("canto reajuste lado ${e.side} a=%.0f b=%.3f -> a=%.0f b=%.3f puntos=${ts.size} dentro=$nIn de ${len / 2}".format(e.a, e.b, ab.first, ab.second))
        if (kotlin.math.abs(ab.second) > 0.21 || nIn < need) return null
        return SheetEdge(e.side, ab.first, ab.second)
    }

    /** Mitad exacta (media 2x2): el bilineal a escala 1/2 da la misma media y es varias veces más rápido que INTER_AREA. */
    private fun halve(src: Mat, dst: Mat) =
        Imgproc.resize(src, dst, Size(max(1, src.cols() / 2).toDouble(), max(1, src.rows() / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

    /** Reducción por áreas a [size]: por mitades exactas mientras sobre más del doble ([halve]) y el resto por áreas. */
    private fun areaDown(src: Mat, dst: Mat, size: Size, bag: MatBag) {
        var cur = src
        while (cur.cols() >= 2 * size.width && cur.rows() >= 2 * size.height && (cur.cols() / 2 > size.width || cur.cols() % 2 == 0)) {
            val h = bag.mat(); halve(cur, h); cur = h
            if (cur.cols() == size.width.toInt() && cur.rows() == size.height.toInt()) { cur.copyTo(dst); return }
        }
        Imgproc.resize(cur, dst, size, 0.0, 0.0, Imgproc.INTER_AREA)
    }

    private fun excludeMask(ex: Mat?, size: Size, bag: MatBag): Mat? {
        if (ex == null || ex.empty()) return null
        val m = bag.mat(); areaDown(ex, m, size, bag)
        Imgproc.threshold(m, m, 20.0, 255.0, Imgproc.THRESH_BINARY)
        return m
    }

    private fun fillHoles(m: Mat, bag: MatBag) {
        val cs = ArrayList<MatOfPoint>(); val hier = bag.mat()
        Imgproc.findContours(m.clone().also { bag.add(it) }, cs, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        Imgproc.drawContours(m, cs, -1, Scalar(255.0), -1)
        cs.forEach { it.release() }
    }

    /** Croma CIE (32FC1) de un RGB 8UC3 o 32FC3 (0..255). */
    private fun chromaOf(rgb: Mat, bag: MatBag): Mat {
        val f = bag.mat()
        rgb.convertTo(f, CvType.CV_32F, 1.0 / 255.0)
        Core.min(f, Scalar.all(1.0), f)
        val lab = bag.mat(); Imgproc.cvtColor(f, lab, Imgproc.COLOR_RGB2Lab)
        val a = bag.mat(); val b = bag.mat()
        Core.extractChannel(lab, a, 1); Core.extractChannel(lab, b, 2)
        val c = Mat(); Core.magnitude(a, b, c)
        return bag.add(c)
    }

    /** Rampa 0..1 en coma flotante (en el sitio). */
    private fun rampF(m: Mat, lo: Double, hi: Double) {
        Core.subtract(m, Scalar(lo), m); Core.multiply(m, Scalar(1.0 / (hi - lo)), m)
        Imgproc.threshold(m, m, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(m, m, 0.0, 0.0, Imgproc.THRESH_TOZERO)
    }

    /**
     * Evidencia de tinta RELATIVA al trazo del entorno: rampa entre max(suelo, REL_LO·máx local) y REL_HI·máx local.
     * Un trazo tenue llega a 1 en su centro igual que uno oscuro, y el desenfoque de su borde (aura) no cuenta.
     */
    private fun relRamp(x: Mat, floor: Double, kl: Int, dst: Mat, bag: MatBag, relLo: Double = REL_LO, relHi: Double = REL_HI, localMax: Mat? = null) {
        val mx = localMax ?: bag.mat().also { Imgproc.dilate(x, it, Cv.kernel(Imgproc.MORPH_ELLIPSE, kl)) }
        val lo = bag.mat(); mx.convertTo(lo, -1, relLo); Core.max(lo, Scalar(floor), lo)
        val hi = bag.mat(); mx.convertTo(hi, -1, relHi); Core.max(hi, lo, hi); Core.add(hi, Scalar(floor * 0.25 + 1e-4), hi)
        Core.subtract(x, lo, dst); Core.subtract(hi, lo, hi); Core.divide(dst, hi, dst)
        Imgproc.threshold(dst, dst, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(dst, dst, 0.0, 0.0, Imgproc.THRESH_TOZERO)
    }

    /**
     * 1 - "parece cuadrícula" (32F): desviación respecto al papel con el mismo matiz que la de la rejilla (coseno >= 0.95)
     * y no mucho más intensa (hasta 2-3 veces: también las bandas y marcos impresos del mismo color). null si la rejilla es casi neutra (no se distingue por el tono).
     */
    private fun gridLike(nw: Mat, gc: DoubleArray, gain: DoubleArray, bag: MatBag): Mat? {
        val gv = DoubleArray(3) { 255.0 - gc[it] * gain[it] }
        val gn = kotlin.math.sqrt(gv.sumOf { it * it })
        if (gn < 8.0 || (gv.maxOrNull()!! - gv.minOrNull()!!) < 0.25 * gn) return null
        val d = bag.mat(); nw.convertTo(d, -1, -1.0, 255.0)
        val dot = bag.mat(); Core.transform(d, dot, Mat(1, 3, CvType.CV_32F).also { bag.add(it); it.put(0, 0, gv[0] / gn, gv[1] / gn, gv[2] / gn) })
        val sq = bag.mat(); Core.multiply(d, d, sq)
        val nrm = bag.mat(); Core.transform(sq, nrm, Mat(1, 3, CvType.CV_32F).also { bag.add(it); it.put(0, 0, 1.0, 1.0, 1.0) }); Core.sqrt(nrm, nrm)
        // coseno -> rampa 0.9..0.97; intensidad -> rampa inversa 2..3 veces la de la rejilla
        val cs = bag.mat(); Core.add(nrm, Scalar(1e-3), sq); Core.divide(dot, sq, cs)
        rampF(cs, 0.90, 0.97)
        val it = bag.mat(); nrm.convertTo(it, -1, -1.0 / gn, 3.0)   // 1 en <= 2·gn, 0 en >= 3·gn (bandas impresas del mismo azul)
        Imgproc.threshold(it, it, 1.0, 1.0, Imgproc.THRESH_TRUNC); Imgproc.threshold(it, it, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        Core.multiply(cs, it, cs)
        cs.convertTo(cs, -1, -1.0, 1.0)
        return cs
    }

    /** Máscara de baja resolución -> 0..1 (32F) en la zona [outer] de salida. */
    private fun upMask(lo: Mat, s: Double, outer: Rect, bag: MatBag): Mat {
        val m = bag.mat()
        val x0 = outer.x * s; val y0 = outer.y * s
        // remapeo afín: salida (u,v) -> baja resolución (x0 + u·s, y0 + v·s)
        val aff = Mat(2, 3, CvType.CV_64F); bag.add(aff)
        aff.put(0, 0, s, 0.0, x0 - 0.5 + 0.5 * s, 0.0, s, y0 - 0.5 + 0.5 * s)
        Imgproc.warpAffine(lo, m, aff, Size(outer.width.toDouble(), outer.height.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
        m.convertTo(m, CvType.CV_32F, 1.0 / 255.0)
        return m
    }

    /** Rampa relativa en 8 bits (255 = 1): (x - lo) / (hi - lo) con lo = max(suelo, relLo·máx), hi = max(relHi·máx, lo) + suelo/4. */
    private fun relRamp8(x: Mat, floor255: Double, mx: Mat, relLo: Double, relHi: Double, dst: Mat, bag: MatBag) {
        val lo = bag.mat(); mx.convertTo(lo, -1, relLo); Core.max(lo, Scalar(floor255), lo)
        val hi = bag.mat(); mx.convertTo(hi, -1, relHi); Core.max(hi, lo, hi); Core.add(hi, Scalar(max(1.0, floor255 * 0.25)), hi)
        Core.subtract(hi, lo, hi)
        Core.subtract(x, lo, lo)                 // (saturado: < lo -> 0)
        Core.divide(lo, hi, dst, 255.0)          // (saturado: > hi -> 255)
    }

    /**
     * Máscara de baja resolución -> 8 bits (0..255) en la zona [outer] de salida; null si allí está vacía. Se amplía
     * sólo el recorte de la máscara que cubre la tesela (bilineal, alineado con su rejilla).
     */
    private fun upMask8(lo: Mat, s: Double, outer: Rect, bag: MatBag, check: Boolean = true): Mat? {
        val x0 = kotlin.math.floor(outer.x * s).toInt().coerceIn(0, lo.cols() - 1); val y0 = kotlin.math.floor(outer.y * s).toInt().coerceIn(0, lo.rows() - 1)
        val x1 = (kotlin.math.ceil((outer.x + outer.width) * s).toInt() + 1).coerceIn(x0 + 1, lo.cols()); val y1 = (kotlin.math.ceil((outer.y + outer.height) * s).toInt() + 1).coerceIn(y0 + 1, lo.rows())
        val roi = bag.add(lo.submat(y0, y1, x0, x1))
        if (check && Core.countNonZero(roi) == 0) return null
        // zona de salida que cubre el recorte: [x0/s, x1/s) (resize alinea los centros de celda como el remapeo afín)
        val fx0 = (x0 / s).roundToInt(); val fy0 = (y0 / s).roundToInt()
        val fw = max(1, (x1 / s).roundToInt() - fx0); val fh = max(1, (y1 / s).roundToInt() - fy0)
        val big = bag.mat(); Imgproc.resize(roi, big, Size(fw.toDouble(), fh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val cx = (outer.x - fx0).coerceIn(0, fw - 1); val cy = (outer.y - fy0).coerceIn(0, fh - 1)
        val cw = min(outer.width, fw - cx); val chh = min(outer.height, fh - cy)
        if (cw == outer.width && chh == outer.height) return bag.add(big.submat(cy, cy + chh, cx, cx + cw))
        // (borde de la imagen: se replica)
        val m = bag.mat(); Core.copyMakeBorder(big.submat(cy, cy + chh, cx, cx + cw).also { bag.add(it) }, m, 0, outer.height - chh, 0, outer.width - cw, Core.BORDER_REPLICATE)
        return m
    }

    /** Peso del color del centro del trazo: (oscuridad o croma)^3, por nivel de 8 bits. */
    private val weightLut = FloatArray(256) { val v = it / 255f; v * v * v }

    /**
     * Sumas ponderadas del color a media resolución en una sola pasada (sin intermedios a resolución completa): cada
     * celda 2x2 aporta peso·color balanceado (peso = max(oscuridad, croma/120)^3, el croma de la celda) -> [num]
     * (32FC3) y [den] (32FC1), como la reducción por áreas de los productos a resolución completa (el trazo fino no
     * se diluye con el papel al bajar de resolución).
     */
    private fun weightedSums(rgb: Mat, dk: Mat, chH: Mat, gain: DoubleArray, need: ByteArray, num: Mat, den: Mat) {
        val w = rgb.cols(); val h = rgb.rows(); val hw = num.cols(); val hh = num.rows()
        val px = ByteArray(w * h * 3); val dkA = ByteArray(w * h)
        if (rgb.isContinuous) rgb.get(0, 0, px) else { val c = rgb.clone(); c.get(0, 0, px); c.release() }
        dk.get(0, 0, dkA)
        val ch = FloatArray(hw * hh); chH.get(0, 0, ch)
        val nA = FloatArray(hw * hh * 3); val dA = FloatArray(hw * hh)
        val g0 = gain[0].toFloat(); val g1 = gain[1].toFloat(); val g2 = gain[2].toFloat()
        val lut = weightLut
        val k = 255f / 120f
        for (hy in 0 until hh) {
            val y0 = 2 * hy; val yN = min(h, y0 + 2)
            for (hx in 0 until hw) {
                val hi = hy * hw + hx
                if (need[hi].toInt() == 0) continue
                val cw = min(255, (ch[hi] * k).toInt())
                val x0 = 2 * hx; val xN = min(w, x0 + 2)
                var sw = 0f; var s0 = 0f; var s1 = 0f; var s2 = 0f
                for (y in y0 until yN) {
                    val row = y * w
                    for (x in x0 until xN) {
                        val d = dkA[row + x].toInt() and 0xFF
                        val wt = lut[if (d > cw) d else cw]
                        val p = (row + x) * 3
                        sw += wt
                        s0 += wt * (px[p].toInt() and 0xFF); s1 += wt * (px[p + 1].toInt() and 0xFF); s2 += wt * (px[p + 2].toInt() and 0xFF)
                    }
                }
                dA[hi] = sw; nA[hi * 3] = s0 * g0; nA[hi * 3 + 1] = s1 * g1; nA[hi * 3 + 2] = s2 * g2
            }
        }
        num.put(0, 0, nA); den.put(0, 0, dA)
    }

    /**
     * Una tesela [inner] (con [pad] de contexto) -> escribe en [out]. Mapas de evidencia y alfa en 8 bits (255 = 1:
     * morfología y aritmética vectorizadas, ~4 veces menos memoria que en coma flotante); croma, tono de la
     * rejilla y color a media resolución; composición final en 8 bits.
     */
    private fun tile(inp: Inputs, g: Global, out: Mat, inner: Rect, pad: Int, r: Int, kc: Int, kn: Int, kl: Int, lhT: Double) = MatBag().use { bag ->
        val W = inp.bw.cols(); val H = inp.bw.rows()
        val ox0 = max(0, inner.x - pad); val oy0 = max(0, inner.y - pad)
        val ox1 = min(W, inner.x + inner.width + pad); val oy1 = min(H, inner.y + inner.height + pad)
        val outer = Rect(ox0, oy0, ox1 - ox0, oy1 - oy0)
        val crop = Rect(inner.x - ox0, inner.y - oy0, inner.width, inner.height)
        NcProf.start()
        // (sin copia balanceada a resolución completa: el balance de blancos es lineal por canal y se aplica a la
        // luminancia, a la media resolución y a las sumas ponderadas del color)
        val nrmT = bag.add(inp.nrm.submat(outer))
        // Oscuridad (luminancia balanceada) relativa al papel blanco; algo suavizada (el grano no parte el trazo tenue)
        val dk = bag.mat(); Imgproc.cvtColor(nrmT, dk, Imgproc.COLOR_RGB2GRAY); dk.convertTo(dk, -1, -g.lumGain, 255.0)
        val dks = bag.mat(); Imgproc.GaussianBlur(dk, dks, Size(0.0, 0.0), 1.0)
        NcProf.mark("dark")
        val rLo = if (inp.bridgeOnly) REL_LO_BW else REL_LO; val rHi = if (inp.bridgeOnly) REL_HI_BW else REL_HI
        val mxD = bag.mat()
        if (kl < 9) Imgproc.dilate(dks, mxD, Cv.kernel(Imgproc.MORPH_ELLIPSE, kl))
        else {
            // máximo local a media resolución (máximo 2x2 -> dilatación de radio mitad -> bilineal): ~4 veces menos trabajo
            val m2 = bag.mat(); Imgproc.dilate(dks, m2, Cv.kernel(Imgproc.MORPH_RECT, 2), org.opencv.core.Point(0.0, 0.0))
            val mh = bag.mat(); Imgproc.resize(m2, mh, Size(((dks.cols() + 1) / 2).toDouble(), ((dks.rows() + 1) / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            Imgproc.dilate(mh, mh, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd(kl / 2)))
            Imgproc.resize(mh, mxD, dks.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.max(mxD, dks, mxD)
        }
        NcProf.mark("localmax")
        val e = bag.mat(); relRamp8(dks, 255.0 * g.t0, mxD, rLo, rHi, e, bag)
        // Trazo tenue (lápiz, tinta pálida) dentro de las zonas candidatas (rampa más permisiva)
        val ef = bag.mat(); relRamp8(dks, 255.0 * g.tF, mxD, if (inp.bridgeOnly) rLo else REL_LO_SUP, if (inp.bridgeOnly) rHi else REL_HI_SUP, ef, bag)
        NcProf.mark("ramps")
        // Croma a media resolución (Lab de 8 bits) -> evidencia de color (también a media resolución) y peso del color
        val hs = Size(max(1.0, (outer.width / 2.0).roundToInt().toDouble()), max(1.0, (outer.height / 2.0).roundToInt().toDouble()))
        val nbG = bag.mat(); areaDown(nrmT, nbG, hs, bag); Core.multiply(nbG, Scalar(g.gain[0], g.gain[1], g.gain[2]), nbG)
        // Balance de blancos local (sombra cálida): corrección por canal a media resolución -> color de la tinta y evidencia
        // de color (los rellenos lisos conservan el balance global: su color es suyo, no de la luz)
        val shH: Mat? = g.lw?.let { lw ->
            val m = bag.mat()
            val k = 2.0 * g.scale / g.lwCell
            val aff = bag.add(Mat(2, 3, CvType.CV_64F)); aff.put(0, 0, k, 0.0, (outer.x + 1.0) * g.scale / g.lwCell - 0.5, 0.0, k, (outer.y + 1.0) * g.scale / g.lwCell - 0.5)
            Imgproc.warpAffine(lw, m, aff, hs, Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
            // (tesela con luz neutra: nada que restar)
            val mm = Core.minMaxLoc(m.reshape(1)); if (max(kotlin.math.abs(mm.minVal), kotlin.math.abs(mm.maxVal)) < 1.0) null else m
        }
        val nbH = if (shH == null) nbG else bag.mat().also { nb -> nbG.copyTo(nb); shiftLab(nb, shH, bag) }
        val chH = bag.mat(); val labH = bag.mat()
        run {
            Imgproc.cvtColor(nbH, labH, Imgproc.COLOR_RGB2Lab)
            val a = bag.mat(); val b = bag.mat()
            Core.extractChannel(labH, a, 1); Core.extractChannel(labH, b, 2)
            a.convertTo(a, CvType.CV_32F, 1.0, -128.0); b.convertTo(b, CvType.CV_32F, 1.0, -128.0)
            Core.magnitude(a, b, chH)
        }
        val ecH = bag.mat()
        run {
            val c8 = bag.mat(); chH.convertTo(c8, CvType.CV_8U, 2.0)            // croma en medias unidades
            val mxC = bag.mat(); Imgproc.dilate(c8, mxC, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.odd(max(3, kl / 2))))
            relRamp8(c8, 2.0 * g.cLo, mxC, REL_LO_SUP, REL_HI_SUP, ecH, bag)
        }
        // Tono de la cuadrícula clara (mismo matiz que ella y poco más oscuro): ni color ni trazo tenue (a media resolución)
        var gl8: Mat? = null
        inp.gridColor?.let { gc ->
            val nwH = bag.mat(); nbH.convertTo(nwH, CvType.CV_32F)
            gridLike(nwH, gc, g.gain, bag)?.let { gl ->
                val gH = bag.mat(); gl.convertTo(gH, CvType.CV_8U, 255.0)
                Core.multiply(ecH, gH, ecH, 1.0 / 255)
                gl8 = bag.mat().also { Imgproc.resize(gH, it, outer.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) }
            }
        }
        val ec = bag.mat(); Imgproc.resize(ecH, ec, outer.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        gl8?.let { Core.multiply(ef, it, ef, 1.0 / 255) }
        NcProf.mark("chroma")
        // Huella de la rejilla / fondo ajeno: ni tinta ni color
        for (exM in listOf(inp.exclude, inp.exclude2)) {
            if (exM == null || exM.empty()) continue
            val ex = if (exM.cols() == W && exM.rows() == H) bag.add(exM.submat(outer)).let { m -> if (Core.countNonZero(m) == 0) null else bag.mat().also { Core.bitwise_not(m, it) } }
                else upMask8(exM, exM.cols().toDouble() / W, outer, bag)?.also { Core.bitwise_not(it, it) }
            if (ex == null) continue
            Core.multiply(e, ex, e, 1.0 / 255); Core.multiply(ec, ex, ec, 1.0 / 255); Core.multiply(ef, ex, ef, 1.0 / 255)
        }
        NcProf.mark("exclude")
        // Rellenos lisos (bandas, celdas sombreadas): su color pasa entero como FONDO y encima sólo la tinta del B/N
        // (ni continuidad, ni histéresis, ni candidatos: el propio relleno no es tinta -> sin agujero blanco ni halo)
        var fm: Mat? = null; var fillUp: Mat? = null
        if (!inp.bridgeOnly && g.fills != null) upMask8(g.fills, g.scale, outer, bag)?.let { cand ->
            // contorno afinado: lo que no es papel (ΔE >= la mitad del umbral) a media resolución
            val np = bag.mat()
            run {
                val l = bag.mat(); Core.extractChannel(labH, l, 0); l.convertTo(l, CvType.CV_32F, -100.0 / 255.0, 100.0)
                val d = bag.mat(); Core.multiply(l, l, d); val c2 = bag.mat(); Core.multiply(chH, chH, c2); Core.add(d, c2, d); Core.sqrt(d, d)
                Imgproc.threshold(d, d, 0.5 * g.fillT, 255.0, Imgproc.THRESH_BINARY); d.convertTo(np, CvType.CV_8U)
            }
            val npF = bag.mat(); Imgproc.resize(np, npF, outer.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.threshold(cand, cand, 127.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(npF, npF, 127.0, 255.0, Imgproc.THRESH_BINARY)
            Core.min(cand, npF, cand)
            Imgproc.GaussianBlur(cand, cand, Size(0.0, 0.0), 0.8)
            fm = cand
            val inv = bag.mat(); Core.bitwise_not(cand, inv)
            Core.multiply(e, inv, e, 1.0 / 255); Core.multiply(ec, inv, ec, 1.0 / 255); Core.multiply(ef, inv, ef, 1.0 / 255)
        }
        // Alfa del B/N y continuidad del trazo
        val aBW = bag.mat(); Core.bitwise_not(inp.bw.submat(outer).also { bag.add(it) }, aBW)
        fm?.let { f -> fillUp = localFill(f, aBW, nbG, g, outer, hs, bag) }
        val ev = if (inp.bridgeOnly) e else bag.mat().also { Core.max(e, ec, it) }
        val closeA = bag.mat(); Imgproc.morphologyEx(aBW, closeA, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_ELLIPSE, kc))
        Core.min(closeA, ev, closeA)
        val a = bag.mat(); Core.max(aBW, closeA, a)
        // Histéresis: lo tenue pegado a un trazo firme (~1.5 anchos); zonas candidatas (trazos tenues o de color que el
        // B/N perdió) y figuras enteras
        if (inp.bridge == 2 || !inp.bridgeOnly) {
            val strong = bag.mat(); Imgproc.threshold(aBW, strong, 127.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.dilate(strong, strong, Cv.kernel(Imgproc.MORPH_ELLIPSE, kn))
            Core.min(strong, ev, strong); Core.max(a, strong, a)
            upMask8(g.support, g.scale, outer, bag)?.let { sup ->
                if (inp.bridgeOnly) Core.multiply(sup, ef, sup, 1.0 / 255)
                else { val evs = bag.mat(); Core.max(ef, ec, evs); Core.multiply(sup, evs, sup, 1.0 / 255) }
                Core.max(a, sup, a)
            }
        }
        val fig = if (inp.bridgeOnly) null else upMask8(g.figures, g.scale, outer, bag)
        if (fig != null) Core.max(a, fig, a)
        if (!inp.bridgeOnly && g.foreignSoft != null) upMask8(g.foreignSoft, g.scale, outer, bag)?.let { f ->
            Core.bitwise_not(f, f)
            Core.multiply(a, f, a, 1.0 / 255); Core.max(a, aBW, a)
            fm?.let { m -> Core.multiply(m, f, m, 1.0 / 255) }
        }
        // Fondo ajeno: fuera (también lo que el B/N dejó: madera, cantos, lo de fuera de la hoja; después de la zona ajena
        // "blanda", que devuelve la tinta del B/N)
        if (!inp.bridgeOnly && g.foreign != null) upMask8(g.foreign, g.scale, outer, bag)?.let { f ->
            Core.bitwise_not(f, f)
            Core.multiply(a, f, a, 1.0 / 255)
            fm?.let { m -> Core.multiply(m, f, m, 1.0 / 255) }
        }
        if (!inp.bridgeOnly && OFF and 2 == 0) {
            // Borde nítido: re-afilado suave del alfa (máscara de enfoque; la evidencia relativa sigue el desenfoque de la
            // foto y dejaba el contorno de logos y trazos de color blando)
            val bl = bag.mat(); Imgproc.GaussianBlur(a, bl, Size(0.0, 0.0), ALPHA_SHARP_SIGMA)
            Core.addWeighted(a, 1.0 + ALPHA_SHARP, bl, -ALPHA_SHARP, 0.0, a)
        }
        Imgproc.threshold(a, a, ALPHA_MIN * 255.0, 255.0, Imgproc.THRESH_TOZERO)
        NcProf.mark("alpha")

        if (inp.bridgeOnly) {
            // Sólo continuidad (para "Blanco y negro"): B/N = 255 - alfa
            Core.bitwise_not(a, a)
            val dst = out.submat(inner); a.submat(crop).copyTo(dst); dst.release()
            return@use
        }
        dbgA?.let { d -> val dd = d.submat(inner); a.submat(crop).copyTo(dd); dd.release() }
        if (Core.countNonZero(a) == 0) { writeOut(null, a, fm, fillUp, outer, inner, crop, out, bag); return@use }   // (sin tinta: blanca o el relleno)
        // El COLOR se calcula a media resolución (varía despacio dentro del trazo; el borde nítido lo da el alfa a
        // resolución completa)
        val figH = if (fig != null) bag.mat().also { areaDown(fig, it, hs, bag) } else null
        val hw = hs.width.toInt(); val hh = hs.height.toInt(); val hn = hw * hh
        // Sólo los píxeles de media resolución que la composición usa (alfa > 0 y su vecindad de interpolación): el
        // realce es puntual y se calcula sobre la lista compacta (~10-30 % de la tesela)
        val aH = bag.mat(); areaDown(a, aH, hs, bag)
        val act = bag.mat(); Imgproc.dilate(aH, act, Cv.kernel(Imgproc.MORPH_RECT, 3))
        val rh = max(1, (r + 1) / 2)
        val ks = Size((2 * rh + 1).toDouble(), (2 * rh + 1).toDouble())
        // Color del centro del trazo: media ponderada por oscuridad^3 (o croma) en una ventana pequeña (el borde, mezcla
        // de tinta y papel, toma el color del núcleo). Sumas de peso·color por celdas 2x2 de resolución completa (el
        // trazo fino no se diluye con el papel al bajar a media resolución), sólo donde la ventana toca la zona activa
        val num = bag.add(Mat(hh, hw, CvType.CV_32FC3)); val wH = bag.add(Mat(hh, hw, CvType.CV_32F))
        run {
            val need = bag.mat(); Imgproc.dilate(act, need, Cv.kernel(Imgproc.MORPH_RECT, 2 * rh + 1))
            val needB = ByteArray(hn); need.get(0, 0, needB)
            weightedSums(nrmT, dk, chH, g.gain, needB, num, wH)
        }
        NcProf.mark("weights")
        // (sumas de la ventana, sin normalizar: num/den es el mismo cociente)
        Imgproc.boxFilter(num, num, CvType.CV_32F, ks, org.opencv.core.Point(-1.0, -1.0), false)
        Imgproc.boxFilter(wH, wH, CvType.CV_32F, ks, org.opencv.core.Point(-1.0, -1.0), false)
        // Zona de color maciza = el alfa cubre casi toda una ventana de ~1 altura de letra (un trazo cubre < ~50 %)
        val fill = bag.mat()
        run { val kw = Cv.odd((lhT / 2.0).roundToInt().coerceIn(3, 41)); Imgproc.boxFilter(aH, fill, CvType.CV_32F, Size(kw.toDouble(), kw.toDouble())) }
        NcProf.mark("c.box")
        val actB = ByteArray(hn); act.get(0, 0, actB)
        var n = 0; for (v in actB) if (v.toInt() != 0) n++
        if (n == 0) { writeOut(null, a, fm, fillUp, outer, inner, crop, out, bag); return@use }
        val numA = FloatArray(hn * 3); num.get(0, 0, numA)
        val denA = FloatArray(hn); wH.get(0, 0, denA)
        val fillA = FloatArray(hn); fill.get(0, 0, fillA)
        val figA = figH?.let { ByteArray(hn).also { b -> it.get(0, 0, b) } }
        val idx = IntArray(n); val colC = FloatArray(n * 3); val noKC = FloatArray(n); val figC = FloatArray(n)
        val eps = 1e-4f   // (ventana sin peso: el color propio)
        val nbAll = ByteArray(hn * 3).also { nbH.get(0, 0, it) }
        var j = 0
        for (i in 0 until hn) {
            if (actB[i].toInt() == 0) continue
            idx[j] = i
            val d = denA[i] + eps
            var f = 0f
            for (c in 0 until 3) {
                val own = (nbAll[i * 3 + c].toInt() and 0xFF).toFloat()
                var v = (numA[i * 3 + c] + eps * own) / d
                if (figA != null) { f = (figA[i].toInt() and 0xFF) / 255f; v += (own - v) * f }
                colC[j * 3 + c] = v
            }
            // (sin refuerzo de densidad en figuras ni en zonas de color gruesas: un resaltador o una banda no son tinta tenue)
            val fl = ((fillA[i] / 255f - FILL_LO.toFloat()) / (FILL_HI - FILL_LO).toFloat()).coerceIn(0f, 1f)
            noKC[j] = max(f, fl); figC[j] = f
            j++
        }
        // Luz local de color (sombra cálida): la dominante que la luz deja en la tinta se resta (la tinta negra o azul no
        // sale marrón); antes de clasificarla en neutra o de color
        if (shH != null) {
            val shA = FloatArray(hn * 2); shH.get(0, 0, shA)
            val cm0 = bag.add(Mat(n, 1, CvType.CV_32FC3)); cm0.put(0, 0, colC)
            Core.multiply(cm0, Scalar.all(1.0 / 255), cm0); Core.min(cm0, Scalar.all(1.0), cm0)
            val lb = bag.mat(); Imgproc.cvtColor(cm0, lb, Imgproc.COLOR_RGB2Lab)
            val la = FloatArray(n * 3); lb.get(0, 0, la)
            for (k in 0 until n) { val i = idx[k]; la[k * 3 + 1] -= shA[i * 2] * (1f - figC[k]); la[k * 3 + 2] -= shA[i * 2 + 1] * (1f - figC[k]) }
            lb.put(0, 0, la); Imgproc.cvtColor(lb, cm0, Imgproc.COLOR_Lab2RGB)
            cm0.get(0, 0, colC); for (k in 0 until n * 3) colC[k] = (colC[k] * 255f).coerceIn(0f, 255f)
        }
        // Tinta NEUTRA o DE COLOR, por zonas de ~1 letra (no por píxel: el borde de un trazo negro tiene franjas de
        // color de la lente y el desenfoque): croma medio del color del núcleo de los trazos, ponderado por el alfa
        val tC = FloatArray(n); val l0C = FloatArray(n)
        run {
            val colM0 = bag.add(Mat(n, 1, CvType.CV_32FC3)); colM0.put(0, 0, colC)
            Core.multiply(colM0, Scalar.all(1.0 / 255), colM0); Core.min(colM0, Scalar.all(1.0), colM0)
            val lab0 = bag.mat(); Imgproc.cvtColor(colM0, lab0, Imgproc.COLOR_RGB2Lab)
            val lb0 = FloatArray(n * 3); lab0.get(0, 0, lb0)
            val aHA = ByteArray(hn); aH.get(0, 0, aHA)
            val cS = FloatArray(hn); val wS = FloatArray(hn)
            for (k in 0 until n) {
                // pureza: croma relativo a la oscuridad (un color claro tiene poco croma absoluto pero mucho relativo)
                val c0 = kotlin.math.sqrt(lb0[k * 3 + 1] * lb0[k * 3 + 1] + lb0[k * 3 + 2] * lb0[k * 3 + 2]) / max(8f, 100f - lb0[k * 3])
                tC[k] = c0
                val wa = (aHA[idx[k]].toInt() and 0xFF) / 255f
                cS[idx[k]] = c0 * wa; wS[idx[k]] = wa
            }
            val cM = bag.add(Mat(hh, hw, CvType.CV_32F)); cM.put(0, 0, cS)
            val wM = bag.add(Mat(hh, hw, CvType.CV_32F)); wM.put(0, 0, wS)
            val kw = Cv.odd((lhT / 2.0).roundToInt().coerceIn(3, 41)).toDouble()
            Imgproc.boxFilter(cM, cM, -1, Size(kw, kw), org.opencv.core.Point(-1.0, -1.0), false)
            Imgproc.boxFilter(wM, wM, -1, Size(kw, kw), org.opencv.core.Point(-1.0, -1.0), false)
            cM.get(0, 0, cS); wM.get(0, 0, wS)
            val n0 = NaturalMath.neutralLo(g.cPaper)   // (pureza)
            for (k in 0 until n) {
                val i = idx[k]
                val cl = if (wS[i] > 1e-3f) cS[i] / wS[i] else tC[k]
                // t = 1 color, 0 neutra; las figuras conservan su color
                // (sólo trazos: en zonas macizas -sombreado gris de una figura, relleno- el gris no se vuelve negro)
                tC[k] = if (OFF and 1 != 0) 1f else max(noKC[k], NaturalMath.ramp(cl.toDouble(), n0, n0 + NEUTRAL_SPAN).toFloat())
                if (chromaStats != null && aHA[i].toInt() and 0xFF > 200) chromaStats!!.add(cl)
            }
            // (croma propio del núcleo, para el tope del realce)
            for (k in 0 until n) { figC[k] = kotlin.math.sqrt(lb0[k * 3 + 1] * lb0[k * 3 + 1] + lb0[k * 3 + 2] * lb0[k * 3 + 2]); l0C[k] = lb0[k * 3] }
            // (tinta neutra mucho más clara que la típica -transparencia del reverso, manchas, motas grises-: conserva su
            // gris; pasarla a negro la volvía tinta y engordaba las motas -DIBCO-)
            if (OFF and 1 == 0) for (k in 0 until n) tC[k] = max(tC[k], NaturalMath.ramp(l0C[k].toDouble(), g.inkL + NEUTRAL_KEEP_LO, g.inkL + NEUTRAL_KEEP_HI).toFloat())
        }
        NcProf.mark("c.gather"); NcProf.add("c.n", n.toDouble() / hn * 100)
        val colM = bag.add(Mat(n, 1, CvType.CV_32FC3)); colM.put(0, 0, colC)
        val noKM = bag.add(Mat(n, 1, CvType.CV_32F)); noKM.put(0, 0, noKC)
        val tM = bag.add(Mat(n, 1, CvType.CV_32F)); tM.put(0, 0, tC)
        val c0M = bag.add(Mat(n, 1, CvType.CV_32F)); c0M.put(0, 0, figC)
        val l0M = bag.add(Mat(n, 1, CvType.CV_32F)); l0M.put(0, 0, l0C)
        enhance(colM, noKM, tM, c0M, l0M, g, bag)
        NcProf.mark("c.enh")
        colM.get(0, 0, colC)
        val c8A = ByteArray(hn * 3) { -1 }
        for (k in 0 until n) { val i = idx[k]; for (c in 0 until 3) c8A[i * 3 + c] = (colC[k * 3 + c] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
        val c8 = bag.add(Mat(hh, hw, CvType.CV_8UC3)); c8.put(0, 0, c8A)
        NcProf.mark("color")
        writeOut(c8, a, fm, fillUp, outer, inner, crop, out, bag)
        if (fig != null && OFF and 16 == 0 && g.photos != null) upMask8(g.photos, g.scale, outer, bag)?.let { ph -> photoZone(ph, nrmT, inp.orig, g, outer, inner, crop, out, bag) }
        NcProf.mark("compose")
    }

    /**
     * Color del relleno liso en la tesela: media local (ventana de ~7 px a media resolución) del color balanceado de
     * los píxeles del relleno que no son tinta (el color propio de cada banda o columna, sin el de las letras), con el
     * el punto blanco de la página; donde no hay bastantes, el color del fondo sin trazos de la escala de análisis.
     */
    private fun localFill(fm: Mat, aBW: Mat, nbH: Mat, g: Global, outer: Rect, hs: Size, bag: MatBag): Mat {
        val fH = bag.mat(); areaDown(fm, fH, hs, bag)
        val iH = bag.mat(); areaDown(aBW, iH, hs, bag)
        // peso: relleno y no tinta (con 1 px de margen alrededor de la tinta: su borde mezcla tinta y relleno)
        Imgproc.dilate(iH, iH, Cv.kernel(Imgproc.MORPH_RECT, 3)); Core.bitwise_not(iH, iH)
        val m = bag.mat(); Core.multiply(fH, iH, m, 1.0 / 255); Imgproc.threshold(m, m, 200.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.erode(m, m, Cv.kernel(Imgproc.MORPH_RECT, 3))   // (sin el borde del relleno, mezclado con el papel)
        val mf = bag.mat(); m.convertTo(mf, CvType.CV_32F, 1.0 / 255)
        val nf = bag.mat(); nbH.convertTo(nf, CvType.CV_32F)
        val m3 = bag.mat(); Core.merge(listOf(mf, mf, mf), m3); Core.multiply(nf, m3, nf)
        val k = Size(9.0, 9.0)
        Imgproc.boxFilter(nf, nf, -1, k, org.opencv.core.Point(-1.0, -1.0), false)
        Imgproc.boxFilter(mf, mf, -1, k, org.opencv.core.Point(-1.0, -1.0), false)
        val ok = bag.mat(); Imgproc.threshold(mf, ok, 3.0, 255.0, Imgproc.THRESH_BINARY); ok.convertTo(ok, CvType.CV_8U)
        Core.max(mf, Scalar(1e-3), mf); Core.merge(listOf(mf, mf, mf), m3); Core.divide(nf, m3, nf)
        // (con el punto blanco de la página: su tono natural; con los niveles completos de la tinta los resaltadores y
        // bandas salían demasiado oscuros -banco: más tinta espuria y menos bolígrafo conservado-)
        val c8 = bag.mat(); nf.convertTo(c8, CvType.CV_8UC3, 255.0 / g.white)
        // (sin muestras propias: el color de la escala de análisis)
        val lo = upMask8(g.fillCol!!, g.scale, outer, bag, check = false)!!
        val loH = bag.mat(); areaDown(lo, loH, hs, bag); loH.convertTo(loH, -1, 255.0 / g.white)
        val nok = bag.mat(); Core.bitwise_not(ok, nok); loH.copyTo(c8, nok)
        val up = bag.mat(); Imgproc.resize(c8, up, outer.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        return up
    }

    /**
     * Zonas de IMAGEN (foto, logo, gráfico, ilustración): tratamiento de foto a resolución completa en vez del de la
     * tinta -sin binarizar ni blanquear su interior-: el color de la foto ORIGINAL con el balance de blancos del papel
     * (la normalización por el fondo blanquearía el cielo o las zonas claras), contraste suave, saturación x[PHOTO_SAT], des-ruido leve (bilateral) y nitidez moderada (máscara de enfoque). Se mezcla
     * con lo ya compuesto según la máscara de la figura (borde suave, sin costura).
     */
    private fun photoZone(fig: Mat, nrmT: Mat, orig: Mat?, g: Global, outer: Rect, inner: Rect, crop: Rect, out: Mat, bag: MatBag) {
        // sólo el rectángulo de la figura dentro de la parte útil de la tesela
        val fc = bag.add(fig.submat(crop))
        val pts = bag.mat(); Core.findNonZero(fc, pts)
        if (pts.empty()) return
        val bb = Imgproc.boundingRect(pts)
        val pad = 4
        val ox = crop.x + bb.x; val oy = crop.y + bb.y
        val x0 = max(0, ox - pad); val y0 = max(0, oy - pad)
        val x1 = min(outer.width, ox + bb.width + pad); val y1 = min(outer.height, oy + bb.height + pad)
        val p = bag.mat()
        val ow = g.origWhite
        if (orig != null && ow != null) {
            // desde la foto ORIGINAL (misma geometría, otra escala), con el balance de blancos del papel
            val sx = orig.cols().toDouble() / out.cols(); val sy = orig.rows().toDouble() / out.rows()
            val r = Cv.clampRect(Rect(((outer.x + x0) * sx).toInt(), ((outer.y + y0) * sy).toInt(), max(1, ((x1 - x0) * sx).roundToInt()), max(1, ((y1 - y0) * sy).roundToInt())), orig.cols(), orig.rows())
            if (r.width <= 0 || r.height <= 0) return
            Imgproc.resize(orig.submat(r).also { bag.add(it) }, p, Size((x1 - x0).toDouble(), (y1 - y0).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.multiply(p, Scalar(255.0 / ow[0], 255.0 / ow[1], 255.0 / ow[2]), p)
        } else {
            val src = bag.add(nrmT.submat(y0, y1, x0, x1))
            Core.multiply(src, Scalar(g.gain[0] * 255.0 / g.white, g.gain[1] * 255.0 / g.white, g.gain[2] * 255.0 / g.white), p)
        }
        val dn = bag.mat(); Imgproc.bilateralFilter(p, dn, 5, 18.0, 3.0)
        // contraste suave (una foto no es tinta: sus sombras no se aplastan): estiramiento leve de 3 % por abajo
        dn.convertTo(dn, -1, 255.0 / (255.0 - 8.0), -8.0 * 255.0 / (255.0 - 8.0))
        // saturación suave en HSV
        val hsv = bag.mat(); Imgproc.cvtColor(dn, hsv, Imgproc.COLOR_RGB2HSV)
        val sCh = bag.mat(); Core.extractChannel(hsv, sCh, 1); sCh.convertTo(sCh, -1, PHOTO_SAT); Core.insertChannel(sCh, hsv, 1)
        Imgproc.cvtColor(hsv, dn, Imgproc.COLOR_HSV2RGB)
        // nitidez moderada
        val bl = bag.mat(); Imgproc.GaussianBlur(dn, bl, Size(0.0, 0.0), 1.2)
        Core.addWeighted(dn, 1.0 + PHOTO_SHARP, bl, -PHOTO_SHARP, 0.0, dn)
        // mezcla con lo compuesto (máscara de la figura, suavizada)
        val f = bag.mat(); Imgproc.GaussianBlur(fig.submat(y0, y1, x0, x1).also { bag.add(it) }, f, Size(0.0, 0.0), 1.0)
        // zona de salida = intersección con [inner]
        val ix0 = max(x0, crop.x); val iy0 = max(y0, crop.y); val ix1 = min(x1, crop.x + crop.width); val iy1 = min(y1, crop.y + crop.height)
        if (ix1 <= ix0 || iy1 <= iy0) return
        val dst = bag.add(out.submat(inner.y + (iy0 - crop.y), inner.y + (iy1 - crop.y), inner.x + (ix0 - crop.x), inner.x + (ix1 - crop.x)))
        val ph = bag.add(dn.submat(iy0 - y0, iy1 - y0, ix0 - x0, ix1 - x0))
        val fm = bag.add(f.submat(iy0 - y0, iy1 - y0, ix0 - x0, ix1 - x0))
        val f3 = bag.mat(); Core.merge(listOf(fm, fm, fm), f3)
        val t1 = bag.mat(); Core.multiply(ph, f3, t1, 1.0 / 255)
        val inv = bag.mat(); Core.bitwise_not(f3, inv)
        val t2 = bag.mat(); Core.multiply(dst, inv, t2, 1.0 / 255)
        Core.add(t1, t2, dst)
    }

    /**
     * Composición en 8 bits de la tinta (color [c8] a media resolución, alfa [a]) sobre el fondo: blanco, o el relleno
     * liso [fillUp] donde [fm] (out = fondo·(1 - a) + tinta·a).
     */
    private fun writeOut(c8: Mat?, a: Mat, fm: Mat?, fillUp: Mat?, outer: Rect, inner: Rect, crop: Rect, out: Mat, bag: MatBag) {
        val res: Mat
        if (fm == null || fillUp == null) {
            if (c8 == null) return
            res = bag.mat(); Imgproc.resize(c8, res, outer.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.bitwise_not(res, res)
            val a3 = bag.mat(); Core.merge(listOf(a, a, a), a3)
            Core.multiply(res, a3, res, 1.0 / 255)
            Core.bitwise_not(res, res)
        } else {
            val base = bag.mat(); Core.bitwise_not(fillUp, base)
            val f3 = bag.mat(); Core.merge(listOf(fm, fm, fm), f3)
            Core.multiply(base, f3, base, 1.0 / 255); Core.bitwise_not(base, base)
            if (c8 == null) res = base
            else {
                val cu = bag.mat(); Imgproc.resize(c8, cu, outer.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                val a3 = bag.mat(); Core.merge(listOf(a, a, a), a3)
                Core.multiply(cu, a3, cu, 1.0 / 255)
                Core.bitwise_not(a3, a3); Core.multiply(base, a3, base, 1.0 / 255)
                res = bag.mat(); Core.add(base, cu, res)
            }
        }
        val dst = out.submat(inner); res.submat(crop).copyTo(dst); dst.release()
    }

    /**
     * Realce del color sobre una lista compacta de píxeles ([col] N×1 RGB 0..255 en coma flotante -> 0..1 en el sitio):
     * densidad Beer-Lambert de la tinta tenue (salvo donde [noK] = 1: figuras, zonas macizas), niveles negro/blanco y
     * saturación en Lab (lo casi neutro sin dominante).
     */
    private fun enhance(col: Mat, noK: Mat, t: Mat, c0: Mat, l0: Mat, g: Global, bag: MatBag) {
        run {
            val lc = bag.mat(); Core.transform(col, lc, Mat(1, 3, CvType.CV_32F).also { bag.add(it); it.put(0, 0, 0.299 / 255, 0.587 / 255, 0.114 / 255) })
            Core.max(lc, Scalar(1e-3), lc); Core.min(lc, Scalar(1.0), lc)
            Core.log(lc, lc); lc.convertTo(lc, -1, -1.0)          // densidad óptica
            Core.max(lc, Scalar(1e-3), lc)
            val k = bag.mat(); Core.divide(OD_TARGET, lc, k)
            Imgproc.threshold(k, k, K_MAX, K_MAX, Imgproc.THRESH_TRUNC); Core.max(k, Scalar(1.0), k)
            run { val nf = bag.mat(); noK.convertTo(nf, -1, -1.0, 1.0); Core.subtract(k, Scalar(1.0), k); Core.multiply(k, nf, k); Core.add(k, Scalar(1.0), k) }
            val k3 = bag.mat(); Core.merge(listOf(k, k, k), k3)
            Core.max(col, Scalar.all(0.255), col); Core.multiply(col, Scalar.all(1.0 / 255), col); Core.min(col, Scalar.all(1.0), col)
            Core.log(col, col); Core.multiply(col, k3, col); Core.exp(col, col); Core.multiply(col, Scalar.all(255.0), col)
        }
        // Realce: niveles (negro/blanco) iguales en los tres canales y saturación en Lab (lo casi neutro, sin dominante)
        Core.subtract(col, Scalar.all(g.black), col)
        Core.multiply(col, Scalar.all(1.0 / (g.white - g.black)), col)
        Core.min(col, Scalar.all(1.0), col); Core.max(col, Scalar.all(0.0), col)
        val lab = bag.mat(); Imgproc.cvtColor(col, lab, Imgproc.COLOR_RGB2Lab)
        val L = bag.mat(); val A = bag.mat(); val B = bag.mat()
        Core.extractChannel(lab, L, 0); Core.extractChannel(lab, A, 1); Core.extractChannel(lab, B, 2)
        val c = bag.mat(); Core.magnitude(A, B, c)
        // Color: realce moderado del croma, con tope respecto al propio (CHROMA_CAP·C0 + CHROMA_ADD): la densidad y los
        // niveles oscurecen la tinta tenue pero no la saturan de más; mismo tono (a y b escalan igual)
        run {
            val cap = bag.mat(); c0.convertTo(cap, -1, CHROMA_CAP, CHROMA_ADD); if (OFF and 32 != 0) cap.setTo(Scalar(1e4))
            // (la tinta que se oscurece -más concentración, Beer-Lambert- gana croma en proporción: misma pureza, sin
            // volverse gris; hasta x1.6)
            val r = bag.mat(); L.convertTo(r, -1, -1.0, 100.0)
            val d0 = bag.mat(); l0.convertTo(d0, -1, -1.0, 100.0); Core.max(d0, Scalar(5.0), d0)
            Core.divide(r, d0, r); Core.max(r, Scalar(1.0), r); Imgproc.threshold(r, r, 1.6, 1.6, Imgproc.THRESH_TRUNC)
            Core.multiply(cap, r, cap)
            val cc = bag.mat(); Core.max(c, Scalar(1e-3), cc)
            val k = bag.mat(); Core.divide(cap, cc, k); Imgproc.threshold(k, k, 1.0, 1.0, Imgproc.THRESH_TRUNC)
            Core.multiply(A, k, A); Core.multiply(B, k, B)
        }
        // Neutra (gris plomo de la cámara, desenfoque, lente sucia): NEGRO profundo y sin dominante
        //   L = t·L + (1 - t)·NEUTRAL_K·L, a,b = t·(a,b)  (proporcional: un trazo gris oscuro queda casi negro y una mota
        //   tenue no se vuelve un punto negro)
        run {
            val lN = bag.mat(); L.convertTo(lN, -1, NEUTRAL_K)
            val d = bag.mat(); Core.subtract(L, lN, d); Core.multiply(d, t, d); Core.add(lN, d, L)
            Core.multiply(A, t, A); Core.multiply(B, t, B)
        }
        Core.merge(listOf(L, A, B), lab)
        Imgproc.cvtColor(lab, col, Imgproc.COLOR_Lab2RGB)
    }

    /**
     * Continuidad del trazo para "Blanco y negro" (en el sitio): cierre del alfa sólo donde la foto normalizada
     * muestra tinta. [nrm] RGB normalizado del mismo tamaño que [bw].
     */
    fun bridge(bw: Mat, nrm: Mat, letterHeight: Double, strokeHalf: Double, exclude: Mat? = null, keep: List<Rect> = emptyList(), level: Int = BRIDGE_LEVEL, exclude2: Mat? = null, gridColor: DoubleArray? = null) {
        if (level <= 0) return
        val r = compose(Inputs(bw, nrm, letterHeight, strokeHalf, exclude = exclude, exclude2 = exclude2, gridColor = gridColor, bridge = level))
        try {
            // (los bloques de imagen en gris del B/N quedan como estaban)
            for (k in keep) { val c = Cv.clampRect(k, bw.cols(), bw.rows()); if (c.width > 0 && c.height > 0) { val s = r.submat(c); s.setTo(Scalar(255.0)); s.release() } }
            Core.min(bw, r, bw)
        } finally { r.release() }
    }
}

/** Perfil por etapas de [NaturalColor] (sólo escritorio / banco: [on] = false en la app, coste nulo). */
internal object NcProf {
    @JvmField var on = false
    val times = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val t = ThreadLocal.withInitial { 0L }
    fun start() { if (on) t.set(System.nanoTime()) }
    fun add(k: String, v: Double) { if (on) times.merge(k, v, Double::plus) }
    fun mark(k: String) { if (!on) return; val n = System.nanoTime(); times.merge(k, (n - t.get()) / 1e6, Double::plus); t.set(n) }
}
