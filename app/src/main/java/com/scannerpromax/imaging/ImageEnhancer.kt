package com.scannerpromax.imaging

import android.content.Context
import android.graphics.Bitmap
import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.FilterType
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Filtros de mejora "casi mágicos".
 *
 * Base común de los filtros de documento: estimación del fondo a baja resolución (papel + iluminación,
 * robusta a fotos, bloques de color y SOMBRAS DURAS de la mano/celular, afinada con filtro guiado) y división
 * -> quita sombras, viñeteo y degradados y equilibra el blanco con los píxeles de papel.
 * Después: niveles adaptativos al ruido medido (punto blanco = papel - 2σ, así el ruido del papel desaparece),
 * gamma para dar cuerpo a la tinta, saturación selectiva (sólo píxeles con color real) y máscara de enfoque.
 * Des-ruido: filtro guiado de He (O(N), por bandas) sobre la luminancia + des-ruido cromático a media
 * resolución, en todos los tiers (NLM era ~50x más lento; un bilateral de calidad similar, ~3x).
 * MAGIC_PRO añade super-resolución x2 real (FSRCNN, [SuperResolution]) para fuentes de baja resolución.
 * AUTO ("Auto inteligente") clasifica el contenido con estadísticas baratas a 256 px ([analyzeContent]) y
 * elige/combina el procesamiento: texto, texto con color, foto, recibo térmico, pizarra, poca luz o pantalla
 * (con eliminación de muaré).
 */
object ImageEnhancer {

    /** Opciones internas: gama del equipo, modo rápido (vista previa) y límite de píxeles. */
    internal data class Options(val highEnd: Boolean, val fast: Boolean, val maxPixels: Int, val lowEnd: Boolean = !highEnd) {
        companion object {
            fun default(): Options {
                val maxMem = Runtime.getRuntime().maxMemory()
                val cores = Runtime.getRuntime().availableProcessors()
                val high = maxMem >= 384L * 1024 * 1024 && cores >= 6
                return Options(highEnd = high, fast = false, maxPixels = if (maxMem >= 384L * 1024 * 1024) 8_000_000 else 4_000_000,
                    lowEnd = maxMem < 256L * 1024 * 1024 || cores <= 4)
            }
            fun of(tier: DeviceTier, fast: Boolean) =
                Options(DeviceProfiler.isHighEnd(tier), fast, tier.maxWorkingPixels, lowEnd = tier.isLowRam || tier.cores <= 4)
        }
    }

    /** Tipo de contenido detectado por el modo AUTO. */
    enum class ContentKind(val label: String) {
        TEXT("Texto"),
        TEXT_COLOR("Texto con color"),
        PHOTO("Foto"),
        RECEIPT("Recibo térmico"),
        WHITEBOARD("Pizarra"),
        LOW_LIGHT("Poca luz"),
        SCREEN("Foto de pantalla"),
    }

    /**
     * Resultado de [analyzeContent]. [recommended] es el filtro CONCRETO más parecido a lo que haría AUTO
     * (recibo -> GRAYSCALE, pantalla -> MAGIC/VIVID: el realce específico de recibos y el anti-muaré sólo los
     * aplica AUTO). [paperFraction], [colorFraction], [inkFraction] 0..1; [paperLevel] 0..255.
     */
    data class ContentAnalysis(
        val kind: ContentKind,
        val recommended: FilterType,
        val lowLight: Boolean,
        val moire: Boolean,
        val paperFraction: Float,
        val colorFraction: Float,
        val inkFraction: Float,
        val paperLevel: Float,
    )

    /** Guarda el contexto de la aplicación (necesario para cargar el modelo de super-resolución de assets). */
    fun init(context: Context) = SuperResolution.init(context)

    fun apply(src: Bitmap, filter: FilterType, adjustments: Adjustments = Adjustments()): Bitmap =
        applyBitmap(src, filter, adjustments, Options.default())

    /** Variante con perfil del dispositivo: [fast] = vista previa (sin des-ruido costoso ni super-resolución). */
    fun apply(src: Bitmap, filter: FilterType, adjustments: Adjustments, tier: DeviceTier, fast: Boolean): Bitmap {
        Cv.configureThreads(tier)
        return applyBitmap(src, filter, adjustments, Options.of(tier, fast))
    }

    /**
     * Filtro recomendado para [bitmap] (clasificación barata a 256 px, ~5-30 ms). Pensado para que data/
     * (DocumentRepository) elija el filtro inicial de una página nueva, p. ej. `filter = recommendFilter(bmp)`.
     * Alternativa preferible para documentos: asignar directamente [FilterType.AUTO], que se resuelve en cada
     * render e incluye además el realce de recibos térmicos y el anti-muaré de fotos de pantalla.
     * Devuelve siempre un filtro concreto (nunca AUTO).
     */
    fun recommendFilter(bitmap: Bitmap): FilterType = analyzeContent(bitmap).recommended

    /** Clasificación del contenido de [bitmap] (lo que usa AUTO). Thread-safe; no modifica [bitmap]. */
    fun analyzeContent(bitmap: Bitmap): ContentAnalysis {
        // Reducción sin aliasing a ~1024 px: suficiente para el muaré (crops) y barata
        val (rgba, _) = Cv.toRgbaScaled(bitmap, 1024)
        val rgb = Mat()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            return analyzeMat(rgb)
        } finally {
            rgba.release(); rgb.release()
        }
    }

    /** Resuelve AUTO al filtro concreto equivalente (para decisiones de la tubería, p. ej. limpieza). */
    internal fun effectiveFilter(filter: FilterType, analysis: ContentAnalysis?): FilterType =
        if (filter == FilterType.AUTO) analysis?.recommended ?: FilterType.MAGIC else filter

    /**
     * true si [filter] se renderiza con la segmentación en recuadros ([TextRegions]): la salida ya es blanco puro
     * fuera de la escritura y no necesita la limpieza de motas posterior.
     */
    internal fun isSegmented(filter: FilterType, analysis: ContentAnalysis?): Boolean = when (filter) {
        FilterType.MAGIC, FilterType.MAGIC_PRO, FilterType.NO_SHADOW, FilterType.LIGHTEN,
        FilterType.BLACK_WHITE, FilterType.GRAYSCALE, FilterType.ECO_INK -> true
        FilterType.AUTO -> analysis?.kind.let { it == null || it == ContentKind.TEXT || it == ContentKind.TEXT_COLOR || it == ContentKind.LOW_LIGHT }
        else -> false
    }

    private fun applyBitmap(src: Bitmap, filter: FilterType, adj: Adjustments, opt: Options): Bitmap {
        val rgb = Cv.toRgb(src)
        val out = try { applyMat(rgb, filter, adj, opt) } finally { rgb.release() }
        val bmp = Cv.toBitmap(out)
        out.release()
        return bmp
    }

    /**
     * Aplica el filtro sobre un Mat RGB 8UC3 (no se modifica). Devuelve Mat nuevo (8UC3 o 8UC1).
     * [analysis]: clasificación ya calculada para AUTO (si es null y el filtro es AUTO, se calcula aquí).
     */
    internal fun applyMat(rgb: Mat, filter: FilterType, adj: Adjustments, opt: Options, analysis: ContentAnalysis? = null): Mat {
        val src = if (rgb.channels() == 3) rgb else Cv.ensureRgb(rgb)
        try {
            val docFilter = filter != FilterType.ORIGINAL && filter != FilterType.VIVID
            val out = when {
                // Imagen casi negra (tapa puesta, escena a oscuras): no hay papel que normalizar; la división por
                // el fondo sólo produciría gris moteado. Sólo niveles con ganancia acotada.
                docFilter && Cv.isNearlyBlack(src) -> nearlyBlack(src, filter)
                else -> applyFilter(src, filter, opt, analysis)
            }
            applyAdjustments(out, adj)
            return out
        } finally {
            if (src !== rgb) src.release()
        }
    }

    /** Niveles suaves (ganancia <= x4) para imágenes casi negras. Devuelve Mat nuevo. */
    private fun nearlyBlack(rgb: Mat, filter: FilterType): Mat {
        val gray = filter == FilterType.GRAYSCALE || filter == FilterType.BLACK_WHITE || filter == FilterType.ECO_INK
        val out = if (gray) Cv.gray(rgb) else rgb.clone()
        val g = if (gray) out else Cv.gray(out)
        val hist = try { Cv.histogram(g) } finally { if (g !== out) g.release() }
        val black = Cv.percentile(hist, 0.005).toDouble()
        val white = max(Cv.percentile(hist, 0.995).toDouble(), black + 64.0)
        Cv.applyLut(out, Cv.levelsLut(black, white, 1.0), out)
        return out
    }

    private fun applyFilter(src: Mat, filter: FilterType, opt: Options, analysis: ContentAnalysis?): Mat =
        when (filter) {
            FilterType.AUTO -> auto(src, opt, analysis ?: analyzeMat(src))
            FilterType.ORIGINAL -> original(src)
            // "Texto resaltado" y "Blanco y negro" (y los filtros antiguos que la interfaz muestra como ellos):
            // segmentación de la escritura en recuadros ([TextRegions])
            FilterType.MAGIC, FilterType.MAGIC_PRO, FilterType.NO_SHADOW, FilterType.LIGHTEN ->
                segmented(src, opt, TextRegions.Style.COLOR)
            FilterType.BLACK_WHITE, FilterType.GRAYSCALE, FilterType.ECO_INK ->
                segmented(src, opt, TextRegions.Style.BLACK_WHITE)
            FilterType.VIVID -> vivid(src, opt)
            FilterType.WHITEBOARD -> whiteboard(src, opt)
        }

    // =====================================================================================
    // AUTO: clasificación del contenido
    // =====================================================================================

    /**
     * Estadísticas a 256 px (INTER_AREA, casi idénticas en vista previa y render final):
     * papel = luminancia relativa al fondo local (dilatación + mediana) > 200 y poco saturado; tinta < 150;
     * color = saturación > 70 con brillo; color de la tinta; nivel del papel (poca luz); tinta más oscura
     * (percentil 0.4 %: recibo desvaído); relación de aspecto (tique). Muaré: picos aislados en el espectro de
     * la CROMINANCIA (a, b) de recortes de 256x256 sobre la imagen reducida a <= 1024 px (escala fija: la vista
     * previa y el render final deciden igual; el texto sobre papel es neutro y no produce picos; la rejilla de
     * sub-píxeles RGB de una pantalla sí). La tinta más oscura se mide a ~768 px.
     */
    internal fun analyzeMat(rgb: Mat): ContentAnalysis = MatBag().use { bag ->
        val sm = bag.mat()
        Cv.downscale(rgb, sm, 256)
        val g = bag.add(Cv.gray(sm))
        val hsv = bag.mat(); Imgproc.cvtColor(sm, hsv, Imgproc.COLOR_RGB2HSV)
        val s = bag.mat(); val v = bag.mat()
        Core.extractChannel(hsv, s, 1); Core.extractChannel(hsv, v, 2)
        val side = max(g.cols(), g.rows())
        val k = Cv.oddAtLeast(side * 0.03, 3)
        val d = bag.mat()
        Imgproc.dilate(g, d, Cv.kernel(Imgproc.MORPH_ELLIPSE, k))
        Imgproc.medianBlur(d, d, k)
        Core.max(d, Scalar(1.0), d)
        val n = bag.mat(); Core.divide(g, d, n, 230.0)
        val total = max(1.0, g.total().toDouble())
        val t = bag.mat(); val t2 = bag.mat()
        fun frac(m: Mat) = Core.countNonZero(m) / total

        Core.compare(n, Scalar(200.0), t, Core.CMP_GT); Core.compare(s, Scalar(50.0), t2, Core.CMP_LT)
        Core.bitwise_and(t, t2, t); val paper = frac(t)
        Core.compare(n, Scalar(150.0), t, Core.CMP_LT); val ink = frac(t)
        val colorMask = bag.mat()
        Core.compare(s, Scalar(70.0), colorMask, Core.CMP_GT); Core.compare(v, Scalar(50.0), t2, Core.CMP_GT)
        Core.bitwise_and(colorMask, t2, colorMask); val color = frac(colorMask)
        Core.compare(n, Scalar(190.0), t, Core.CMP_LT)
        val inkAny = Core.countNonZero(t)
        Core.bitwise_and(t, colorMask, t2)
        val inkColor = Core.countNonZero(t2).toDouble() / max(1, inkAny)
        val dHist = Cv.histogram(d)
        val bgLevel = Cv.percentile(dHist, 0.5).toDouble()
        val bgHigh = Cv.percentile(dHist, 0.9).toDouble()
        val aspect = max(g.cols(), g.rows()).toDouble() / max(1, min(g.cols(), g.rows()))
        // Tinta a ~768 px: a 256 px un trazo normal de 0.3 mm ocupa < 1/2 píxel, queda gris claro y el texto
        // negro fino se confundía con un recibo desvaído. inkLevel = percentil 0.4 %; inkP5 = percentil 5 % de
        // los píxeles de tinta (toda la tinta desvaída); inkFrac = fracción de tinta a esa escala.
        val (inkLevel, inkP5, inkFrac) = inkStats(rgb, d, bag)
        // Muaré SIEMPRE a la misma escala (lado largo <= 1024): la vista previa (960-1200 px), el render final
        // (3000+ px) y analyzeContent (1024 px) ven el periodo de la rejilla en la misma banda -> misma decisión.
        val moire = run {
            val long = max(rgb.cols(), rgb.rows())
            if (long <= MOIRE_SIDE) moireScore(rgb) else {
                val ms = bag.mat(); Cv.downscale(rgb, ms, MOIRE_SIDE); moireScore(ms)
            }
        } > MOIRE_THRESHOLD
        // Evidencia de recibo además de la tinta clara: tique alargado, o tinta desvaída en TODA la página con
        // suficiente texto (una página casi en blanco con una firma no es un recibo). Umbrales de tinta ajustados
        // a 768 px (allí la tinta se mide más oscura que a 256: el trazo ya se resuelve).
        val receiptShape = aspect >= 1.8 || (paper > 0.6 && inkFrac > 0.01 && inkP5 > 110)

        // Poca luz = escena oscura en general (no media página en sombra: eso lo resuelve el modelo de fondo)
        val lowLight = bgLevel < 90 && bgHigh < 130
        val kind = when {
            moire -> ContentKind.SCREEN
            paper < 0.30 -> ContentKind.PHOTO
            !lowLight && color < 0.02 && receiptShape &&
                (inkLevel > 130 || (aspect >= 2.2 && inkLevel > 100)) -> ContentKind.RECEIPT
            inkColor > 0.45 && ink < 0.08 && paper > 0.6 -> ContentKind.WHITEBOARD
            lowLight -> ContentKind.LOW_LIGHT
            color > 0.004 || inkColor > 0.03 -> ContentKind.TEXT_COLOR
            else -> ContentKind.TEXT
        }
        val rec = when (kind) {
            ContentKind.SCREEN -> if (paper < 0.30) FilterType.VIVID else FilterType.MAGIC
            ContentKind.PHOTO -> FilterType.VIVID
            ContentKind.RECEIPT -> FilterType.GRAYSCALE
            ContentKind.WHITEBOARD -> FilterType.WHITEBOARD
            ContentKind.LOW_LIGHT -> FilterType.MAGIC_PRO
            ContentKind.TEXT, ContentKind.TEXT_COLOR -> FilterType.MAGIC
        }
        ContentAnalysis(kind, rec, lowLight, moire, paper.toFloat(), color.toFloat(), ink.toFloat(), bgLevel.toFloat())
    }

    private const val MOIRE_THRESHOLD = 15.0
    private const val MOIRE_SIDE = 1024
    private const val INK_SIDE = 768

    /**
     * (percentil 0.4 % de la luminancia normalizada, percentil 5 % de la tinta, fracción de tinta) a ~768 px.
     * [bg256] = fondo local ya calculado a 256 px (se amplía). Si la imagen es menor, a su resolución.
     */
    private fun inkStats(rgb: Mat, bg256: Mat, bag: MatBag): Triple<Int, Int, Double> {
        val sm = bag.mat(); Cv.downscale(rgb, sm, INK_SIDE)
        val g = bag.add(Cv.gray(sm))
        val bg = bag.mat(); Imgproc.resize(bg256, bg, g.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.max(bg, Scalar(1.0), bg)
        val n = bag.mat(); Core.divide(g, bg, n, 230.0)
        val hist = Cv.histogram(n)
        val inkLevel = Cv.percentile(hist, 0.004)
        val inkHist = DoubleArray(256) { if (it < 190) hist[it] else 0.0 }
        val inkCount = inkHist.sum()
        val inkP5 = if (inkCount > 0) Cv.percentile(inkHist, 0.05) else 255
        return Triple(inkLevel, inkP5, inkCount / max(1.0, g.total().toDouble()))
    }

    /**
     * Pico espectral de la crominancia: max / percentil 99 en la banda de periodos ~2.3..14 px, sobre 3 recortes
     * 256x256 (centro y diagonales) y los canales a y b de Lab. Texto/fotos: 2-6; foto de pantalla: 40+.
     */
    private fun moireScore(rgb: Mat): Double = MatBag().use { bag ->
        val n = 256
        val w = rgb.cols(); val h = rgb.rows()
        if (w < n + 8 || h < n + 8) return@use 0.0
        val win = bag.add(hanning(n))
        val radius = bag.add(radiusMap(n))
        val band = bag.mat()
        val lo = bag.mat(); Core.compare(radius, Scalar(n / 14.0), lo, Core.CMP_GT)
        Core.compare(radius, Scalar(n / 2.3), band, Core.CMP_LT)
        Core.bitwise_and(band, lo, band)
        val bandCount = Core.countNonZero(band)
        val vals = FloatArray(n * n)
        val lab = bag.mat(); val ch = bag.mat(); val f = bag.mat(); val spec = bag.mat()
        val planes = ArrayList<Mat>(2); val mag = bag.mat()
        var best = 0.0
        for ((cx, cy) in listOf(w / 2 to h / 2, w / 3 to h / 3, 2 * w / 3 to 2 * h / 3)) {
            val x0 = (cx - n / 2).coerceIn(0, w - n); val y0 = (cy - n / 2).coerceIn(0, h - n)
            val crop = rgb.submat(Rect(x0, y0, n, n))
            Imgproc.cvtColor(crop, lab, Imgproc.COLOR_RGB2Lab)
            crop.release()
            for (c in 1..2) {
                Core.extractChannel(lab, ch, c)
                ch.convertTo(f, CvType.CV_32F)
                Core.subtract(f, Core.mean(f), f)
                Core.multiply(f, win, f)
                Core.dft(f, spec, Core.DFT_COMPLEX_OUTPUT)
                planes.clear(); Core.split(spec, planes)
                Core.magnitude(planes[0], planes[1], mag)
                for (pl in planes) pl.release()
                // Sólo la banda (el radio se calcula con frecuencias "envueltas": no hace falta fftshift)
                mag.setTo(Scalar(0.0), notMask(band, bag))
                mag.get(0, 0, vals)
                val mx = Core.minMaxLoc(mag).maxVal
                // Percentil 99 de la banda
                var cnt = 0
                val arr = FloatArray(bandCount)
                for (x in vals) if (x > 0f && cnt < bandCount) arr[cnt++] = x
                if (cnt < 16) continue
                java.util.Arrays.sort(arr, 0, cnt)
                val p99 = arr[((cnt - 1) * 0.99).toInt()].toDouble() + 1e-6
                best = max(best, mx / p99)
            }
        }
        best
    }

    private fun notMask(m: Mat, bag: MatBag): Mat { val r = bag.mat(); Core.bitwise_not(m, r); return r }

    private fun hanning(n: Int): Mat {
        val w = DoubleArray(n) { 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / (n - 1)) }
        val a = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) a[y * n + x] = (w[y] * w[x]).toFloat()
        return Mat(n, n, CvType.CV_32F).also { it.put(0, 0, a) }
    }

    /** Radio de frecuencia de cada bin de la DFT (sin desplazar: índices > n/2 son negativos). */
    private fun radiusMap(n: Int): Mat {
        val a = FloatArray(n * n)
        for (y in 0 until n) {
            val fy = if (y <= n / 2) y else y - n
            for (x in 0 until n) {
                val fx = if (x <= n / 2) x else x - n
                a[y * n + x] = sqrt((fx * fx + fy * fy).toDouble()).toFloat()
            }
        }
        return Mat(n, n, CvType.CV_32F).also { it.put(0, 0, a) }
    }

    /** AUTO: procesa según la clase detectada (con anti-muaré previo si es una foto de pantalla). */
    private fun auto(rgb: Mat, opt: Options, a: ContentAnalysis): Mat {
        var base = rgb
        if (a.moire) base = rgb.clone().also { descreen(it) }
        try {
            return when (a.kind) {
                ContentKind.PHOTO -> vivid(base, opt)
                ContentKind.RECEIPT -> receipt(base, opt)
                ContentKind.WHITEBOARD -> whiteboard(base, opt)
                ContentKind.SCREEN -> if (a.paperFraction < 0.30f) vivid(base, opt) else magic(base, opt, pro = false)
                ContentKind.LOW_LIGHT, ContentKind.TEXT, ContentKind.TEXT_COLOR -> segmented(base, opt, TextRegions.Style.COLOR)
            }
        } finally {
            if (base !== rgb) base.release()
        }
    }

    // =====================================================================================
    // Filtros
    // =====================================================================================

    /** Lado largo máximo de la entrada para aplicar super-resolución x2 en MAGIC_PRO. */
    private const val SR_MAX_SIDE = 1600

    private fun magic(rgb: Mat, opt: Options, pro: Boolean): Mat = MatBag().use { bag ->
        val (rel0, lvl) = Cv.estimatePaperNoiseLevel(rgb)
        var den = bag.add(denoiseFull(rgb, rel0, opt, strong = pro, absScale = lvl))
        // Super-resolución real (FSRCNN x2 o Lanczos + realce dirigido por bordes) para fuentes pequeñas.
        // Sólo en el render final; en gama baja hasta ~1 MP de entrada (presupuesto de tiempo).
        var superRes = false
        if (pro && !opt.fast && max(den.cols(), den.rows()) < SR_MAX_SIDE &&
            den.total() <= (if (opt.lowEnd) 900_000L else 2_000_000L) && den.total() * 4 <= opt.maxPixels.toLong()
        ) {
            val small = den
            den = bag.add(SuperResolution.upscale2x(small, lowEnd = !opt.highEnd, maxPixels = opt.maxPixels))
            small.release()
            superRes = true
        }
        val bg = bag.add(background(den, opt))
        val bgLevel = Core.mean(bg).`val`.let { (it[0] + it[1] + it[2]) / 3.0 }
        val n = Mat()
        // Poca luz: división en coma flotante (evita posterización por la gran ganancia)
        if (bgLevel < 80) Cv.divideByBackgroundSmooth(den, bg, n, if (opt.fast) 0.0 else 0.7)
        else Cv.divideByBackground(den, bg, n)

        val g = bag.add(Cv.gray(n))
        val hist = Cv.histogram(g)
        val (rel, pm) = Cv.paperStats(hist)
        val black = min(Cv.percentile(hist, 0.005), 110) * 0.9
        val white = (pm - 2.5 * rel).coerceIn(170.0, 250.0)
        // Gamma adaptativa: fotos oscuras -> tinta con menos contraste -> más gamma
        val gamma = when {
            pro && bgLevel < 90 -> 1.5
            pro -> 1.35
            else -> 1.3
        }
        Cv.applyLut(n, Cv.levelsLut(black, white, gamma), n)
        boostSaturation(n, if (pro) 1.35 else 1.3, lowCut = true)
        val long = max(n.cols(), n.rows())
        if (pro) {
            // Realce consciente de bordes; tras la super-resolución el detalle ya es real: realce más suave.
            // El umbral se adapta al ruido medido: el grano del papel no se amplifica.
            val k = if (superRes) 0.6 else 1.0
            Cv.unsharp(n, max(0.8, long / 2500.0), 0.55 * k)
            edgeAwareSharpen(n, max(1.0, long / 1800.0), 0.6 * k, threshold = max(6.0, rel * 2.5))
        } else {
            Cv.unsharp(n, max(0.8, long / 2500.0), 0.5)
        }
        n
    }

    /**
     * "Texto resaltado" (COLOR) y "Blanco y negro": la escritura se segmenta en recuadros ([TextRegions]); fuera,
     * blanco puro; dentro, sólo la tinta (bordes suaves, color reforzado o negro); fotos y bloques de color se
     * conservan con una mejora suave. Antes: super-resolución x2 si la fuente es pequeña (sólo render final,
     * con presupuesto por gama). La salida tiene el tamaño de la entrada (x2 con super-resolución): las estimaciones se hacen a resolución reducida y se aplican a la completa.
     */
    private fun segmented(rgb: Mat, opt: Options, style: TextRegions.Style): Mat = MatBag().use { bag ->
        var base = rgb
        // Sin anti-muaré: la cuadrícula azul de un cuaderno es periódica y cromática y el detector de muaré la
        // confundía con una pantalla (el des-tramado emborronaba la escritura). La segmentación ya deja el fondo
        // en blanco puro, así que el muaré de una foto de pantalla tampoco llega a la salida.
        if (!opt.fast && max(base.cols(), base.rows()) < SR_MAX_SIDE &&
            base.total() <= (if (opt.lowEnd) 900_000L else 2_000_000L) && base.total() * 4 <= opt.maxPixels.toLong()
        ) {
            base = bag.add(SuperResolution.upscale2x(base, lowEnd = !opt.highEnd, maxPixels = opt.maxPixels))
        }
        TextRegions.render(base, style, opt.fast)
    }

    /**
     * "Color original": colores fieles a la foto (sin blanquear ni saturar), sólo una máscara de enfoque
     * suave con umbral (el grano del papel y el ruido no se refuerzan).
     */
    private fun original(rgb: Mat): Mat {
        val out = rgb.clone()
        val long = max(out.cols(), out.rows())
        edgeAwareSharpen(out, max(0.8, long / 2500.0), 0.3, threshold = 4.0)
        return out
    }

    /**
     * Recibo térmico desvaído: gris, fondo normalizado, estiramiento fuerte usando la tinta más oscura como
     * negro, contraste LOCAL (CLAHE) y nuevo punto blanco medido tras el CLAHE (el grano del papel no vuelve).
     */
    private fun receipt(rgb: Mat, opt: Options): Mat = MatBag().use { bag ->
        val g = bag.add(Cv.gray(rgb))
        val (rel0, lvl) = Cv.estimatePaperNoiseLevel(g)
        // eps en niveles reales (la imagen aún no está normalizada): σ_abs = rel·nivelPapel/230
        if (rel0 > 2.5) Cv.guidedDenoiseLuma(g, rel0 * 1.2 * lvl, opt.fast)
        val bg = bag.add(background(g, opt))
        val n = Mat()
        Cv.divideByBackground(g, bg, n)
        val hist = Cv.histogram(n)
        val (rel, pm) = Cv.paperStats(hist)
        val white = (pm - 2.5 * rel).coerceIn(150.0, 250.0)
        val ink = Cv.percentile(hist, 0.004).toDouble()
        val black = min(ink * 0.92, white - 40.0).coerceAtLeast(0.0)
        Cv.applyLut(n, Cv.levelsLut(black, white, 1.5), n)
        Imgproc.createCLAHE(2.5, Size(8.0, 8.0)).apply(n, n)
        val (rel2, pm2) = Cv.paperStats(Cv.histogram(n))
        Cv.applyLut(n, Cv.levelsLut(0.0, (pm2 - 2.0 * rel2).coerceIn(160.0, 245.0), 1.2), n)
        Cv.unsharp(n, max(0.8, max(n.cols(), n.rows()) / 2000.0), 0.6)
        n
    }

    /**
     * Anti-muaré para fotos de pantallas (in-place, RGB): el muaré de la rejilla de sub-píxeles es sobre todo
     * CROMÁTICO -> a y b (Lab) se filtran con fuerza a 1/4 de resolución (mediana + Gauss); la luminancia recibe
     * un paso bajo a la escala de la rejilla y un realce a escala de letra (el texto recupera el contorno).
     */
    private fun descreen(rgb: Mat) = MatBag().use { bag ->
        val w = rgb.cols(); val h = rgb.rows()
        val lab = bag.mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val q = Size(max(1.0, w / 4.0), max(1.0, h / 4.0))
        val c = bag.mat(); val cs = bag.mat()
        for (ch in 1..2) {
            Core.extractChannel(lab, c, ch)
            Imgproc.resize(c, cs, q, 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.medianBlur(cs, cs, 5)
            Imgproc.GaussianBlur(cs, cs, Size(0.0, 0.0), 1.5)
            Imgproc.resize(cs, c, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.insertChannel(c, lab, ch)
        }
        val l = bag.mat(); Core.extractChannel(lab, l, 0)
        val sig = max(0.8, max(w, h) / 1800.0)
        Imgproc.GaussianBlur(l, l, Size(0.0, 0.0), sig)
        Cv.unsharp(l, sig * 2.5, 0.6)
        Core.insertChannel(l, lab, 0)
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
    }

    /**
     * Máscara de enfoque con umbral: sólo se refuerzan los píxeles cuyo detalle (|img - blur| en luminancia)
     * supera [threshold]; el papel liso y el ruido fino quedan intactos. In-place.
     */
    private fun edgeAwareSharpen(img: Mat, sigma: Double, amount: Double, threshold: Double) {
        if (amount <= 0) return
        MatBag().use { bag ->
            val blur = bag.mat(); Imgproc.GaussianBlur(img, blur, Size(0.0, 0.0), sigma)
            val diff = bag.mat(); Core.absdiff(img, blur, diff)
            val dg = if (diff.channels() == 1) diff else bag.add(Cv.gray(diff))
            val flat = bag.mat(); Imgproc.threshold(dg, flat, threshold, 255.0, Imgproc.THRESH_BINARY_INV)
            // Transición suave entre zonas realzadas y lisas (sin bordes duros en la máscara)
            Imgproc.dilate(flat, flat, Cv.kernel(Imgproc.MORPH_RECT, 3))
            val orig = bag.add(img.clone())
            Core.addWeighted(img, 1.0 + amount, blur, -amount, 0.0, img)
            orig.copyTo(img, flat)
        }
    }

    /** Lado de trabajo de la estimación de fondo: más fino en gama alta (sombras duras con menos halo). */
    private fun bgSide(opt: Options): Int = if (opt.highEnd && !opt.fast) 384 else 256

    /**
     * Fondo (papel + iluminación) con afinado guiado de los bordes de sombra: a 512 px en el render final y a
     * 320 px en la vista previa (barato y visualmente casi igual, así la vista previa anticipa el resultado).
     */
    private fun background(img: Mat, opt: Options): Mat =
        Cv.estimateBackground(img, bgSide(opt), refine = true, refineSide = if (opt.fast) 320 else 512)

    private fun vivid(rgb: Mat, opt: Options): Mat {
        val (rel0, lvl) = Cv.estimatePaperNoiseLevel(rgb)
        val out = if (rel0 > 4) denoiseColor(rgb, rel0, opt, strong = false, absScale = lvl) else rgb.clone()
        MatBag().use { bag ->
            // Gamma adaptativa para fotos oscuras
            val g = bag.add(Cv.gray(out))
            val mean = Core.mean(g).`val`[0]
            if (mean in 1.0..115.0) {
                val gamma = (ln(0.5) / ln(mean / 255.0)).coerceIn(0.5, 1.0)
                Cv.applyLut(out, Cv.lut { 255.0 * (it / 255.0).pow(gamma) }, out)
            }
            // Balance de blancos "gray world" suave (50%)
            val m = Core.mean(out).`val`
            val avg = (m[0] + m[1] + m[2]) / 3.0
            if (m[0] > 1 && m[1] > 1 && m[2] > 1) {
                val sc = DoubleArray(3) { (1.0 + 0.5 * (avg / m[it] - 1.0)).coerceIn(0.8, 1.25) }
                Core.multiply(out, Scalar(sc[0], sc[1], sc[2]), out)
            }
            // CLAHE en L (Lab)
            val lab = bag.mat(); Imgproc.cvtColor(out, lab, Imgproc.COLOR_RGB2Lab)
            val l = bag.mat(); Core.extractChannel(lab, l, 0)
            Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(l, l)
            Core.insertChannel(l, lab, 0)
            Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        }
        boostSaturation(out, 1.35, lowCut = false)
        Cv.unsharp(out, max(0.8, max(out.cols(), out.rows()) / 2500.0), 0.35)
        return out
    }

    private fun whiteboard(rgb: Mat, opt: Options): Mat = MatBag().use { bag ->
        val (rel0, lvl) = Cv.estimatePaperNoiseLevel(rgb)
        val den = bag.add(denoiseFull(rgb, rel0, opt, strong = false, absScale = lvl))
        val bg = bag.add(background(den, opt))
        val n = Mat()
        Cv.divideByBackground(den, bg, n)
        val g = bag.add(Cv.gray(n))
        val hist = Cv.histogram(g)
        val (rel, pm) = Cv.paperStats(hist)
        val black = Cv.percentile(hist, 0.01) * 0.8
        val white = (pm - 3.0 * rel).coerceIn(160.0, 235.0)
        Cv.applyLut(n, Cv.levelsLut(black, white, 1.6), n)
        // Fondo blanco puro (sin tinte) y marcadores muy saturados
        val hsv = bag.mat(); Imgproc.cvtColor(n, hsv, Imgproc.COLOR_RGB2HSV)
        val s = bag.mat(); Core.extractChannel(hsv, s, 1)
        Cv.applyLut(s, Cv.lut { if (it < 25) 0.0 else min(255.0, it * 1.8) }, s)
        Core.insertChannel(s, hsv, 1)
        Imgproc.cvtColor(hsv, n, Imgproc.COLOR_HSV2RGB)
        Cv.unsharp(n, max(0.8, max(n.cols(), n.rows()) / 2500.0), 0.6)
        n
    }

    // =====================================================================================
    // Bloques comunes
    // =====================================================================================

    /**
     * Des-ruido según el ruido relativo medido [rel]: filtro guiado sobre la luminancia en todos los tiers
     * (O(N), por bandas; ~10x más rápido que NLM y más fiel en los bordes de las letras que un bilateral).
     * Vista previa: a, b a media resolución y sólo si hay ruido apreciable. Devuelve Mat nuevo.
     */
    private fun denoiseColor(rgb: Mat, rel: Double, opt: Options, strong: Boolean, absScale: Double = 1.0): Mat {
        val out = rgb.clone()
        lumaSigma(rel, opt, strong)?.let { Cv.guidedDenoiseLuma(out, it * absScale, opt.fast) }
        return out
    }

    /** Sigma efectiva del des-ruido de luminancia o null si no hace falta. */
    private fun lumaSigma(rel: Double, opt: Options, strong: Boolean): Double? = when {
        opt.fast -> if (rel > 4) rel else null
        rel <= 2.5 && !strong -> null
        else -> if (strong) rel * 1.3 else rel
    }

    private fun chromaWanted(rel: Double, opt: Options) = !(opt.fast && rel <= 6) && rel > 2.0

    /**
     * Des-ruido completo en UNA sola conversión de color (RGB -> YCrCb -> RGB): filtro guiado en Y y des-ruido
     * cromático (mediana + Gauss a media resolución) en Cr/Cb. Antes eran dos ida-y-vuelta (YCrCb y Lab):
     * 4 conversiones a resolución completa menos. Devuelve Mat nuevo.
     */
    private fun denoiseFull(rgb: Mat, rel: Double, opt: Options, strong: Boolean, absScale: Double = 1.0): Mat {
        // Las decisiones (¿des-ruidar?) usan el ruido RELATIVO al papel; el eps del filtro guiado, que trabaja
        // sobre la imagen SIN normalizar, usa el ABSOLUTO (rel·[absScale]): con papel a 60 el relativo es ~4x
        // mayor y alisaba los trazos finos justo en las fotos con poca luz.
        val sig = lumaSigma(rel, opt, strong)?.let { it * absScale }
        val chroma = chromaWanted(rel, opt)
        if (sig == null && !chroma) return rgb.clone()
        return MatBag().use { bag ->
            val long = max(rgb.cols(), rgb.rows())
            val ycc = bag.mat(); Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
            val c = bag.mat()
            if (sig != null) {
                Core.extractChannel(ycc, c, 0)
                val r = (long / 1400.0).roundToInt().coerceIn(1, 3)
                val eps = (2.2 * max(2.0, sig)).pow(2)
                Cv.guidedSelf(c, c, r, eps, if (opt.fast && long > 600) 2 else 1)
                Core.insertChannel(c, ycc, 0)
            }
            if (chroma) {
                val half = Size(max(1.0, rgb.cols() / 2.0), max(1.0, rgb.rows() / 2.0))
                val cs = bag.mat()
                for (ch in 1..2) {
                    Core.extractChannel(ycc, c, ch)
                    Imgproc.resize(c, cs, half, 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.medianBlur(cs, cs, 5)
                    Imgproc.GaussianBlur(cs, cs, Size(3.0, 3.0), 0.0)
                    Imgproc.resize(cs, c, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                    Core.insertChannel(c, ycc, ch)
                }
            }
            val out = Mat()
            Imgproc.cvtColor(ycc, out, Imgproc.COLOR_YCrCb2RGB)
            out
        }
    }

    /**
     * Saturación: con [lowCut] los píxeles casi grises (ruido cromático del papel) se desaturan
     * y sólo se realza el color real (sellos, firmas, subrayados).
     */
    private fun boostSaturation(rgb: Mat, boost: Double, lowCut: Boolean) {
        MatBag().use { bag ->
            val hsv = bag.mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val s = bag.mat(); Core.extractChannel(hsv, s, 1)
            val lut = Cv.lut { v ->
                if (!lowCut) min(255.0, v * boost)
                else when {
                    v < 18 -> v * 0.35
                    v < 40 -> { val t = (v - 18) / 22.0; v * (0.35 + t * (boost - 0.35)) }
                    else -> min(255.0, v * boost)
                }
            }
            Cv.applyLut(s, lut, s)
            Core.insertChannel(s, hsv, 1)
            Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        }
    }

    /** Elimina componentes de tinta (negro sobre blanco) con área < [minArea]. In-place. */
    internal fun removeSmallInk(bin: Mat, minArea: Double) {
        val inv = Mat()
        Core.bitwise_not(bin, inv)
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(inv, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        inv.release(); hier.release()
        if (contours.size in 1..400_000) {
            val small = contours.filter {
                val r = Imgproc.boundingRect(it)
                r.width * r.height <= minArea * 2 && Imgproc.contourArea(it) <= minArea
            }
            if (small.isNotEmpty()) Imgproc.drawContours(bin, small, -1, Scalar(255.0), -1)
        }
        for (c in contours) c.release()
    }

    /** Brillo/contraste (LUT) y nitidez finales. In-place. */
    internal fun applyAdjustments(img: Mat, adj: Adjustments) {
        val b = adj.brightness.coerceIn(-1f, 1f).toDouble()
        val c = adj.contrast.coerceIn(-1f, 1f).toDouble()
        val s = adj.sharpness.coerceIn(-1f, 1f).toDouble()
        if (b != 0.0 || c != 0.0) {
            val alpha = if (c >= 0) 1.0 + c else 1.0 + c * 0.7
            val beta = b * 70.0
            Cv.applyLut(img, Cv.lut { (it - 128.0) * alpha + 128.0 + beta }, img)
        }
        val long = max(img.cols(), img.rows())
        if (s > 0) Cv.unsharp(img, max(1.0, long / 2000.0), s * 1.5)
        else if (s < 0) Imgproc.GaussianBlur(img, img, Size(0.0, 0.0), -s * 2.0 * max(1.0, long / 2000.0))
    }
}
