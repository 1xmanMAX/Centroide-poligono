package com.scannerpromax.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Parte numérica pura del modelo de sombras (sin OpenCV: probada en los tests de JVM).
 */
internal object ShadowMath {
    /** A partir de esta atenuación (luz / luz plena del entorno) empieza la sombra; a [FULL_ATT] es sombra plena. */
    const val START_ATT = 0.94
    const val FULL_ATT = 0.80
    /** Fracción máxima de la imagen en sombra plena: por encima no es una sombra sobre la hoja (escena entera). */
    const val MAX_COVERAGE = 0.55
    /** Exponente del refuerzo del color cálido en sombra: k = luz^-[WARM_GAMMA] (luz 0.55 -> x1.6). */
    const val WARM_GAMMA = 0.8
    /** Refuerzo máximo del color cálido en la sombra. */
    const val MAX_CHROMA_GAIN = 1.6
    /** Residuo de luz válido (papel en sombra): factor medio y dispersión entre canales; rampas suaves de *0 (peso 0) a * (peso 1). */
    const val RES_MIN0 = 0.58
    const val RES_MIN = 0.75
    const val RES_MAX = 1.30
    const val RES_MAX0 = 1.45
    const val RES_SPREAD = 0.12
    const val RES_SPREAD0 = 0.25
    /** Tinte de la sombra: cociente max/min entre los factores de luz por canal; hasta [TINT0] es sombra, desde [TINT1] es color. */
    const val TINT0 = 1.3
    const val TINT1 = 1.5
    /** Componentes de sombra: margen del borde (fracción del lado) y áreas mínimas (fracción de la imagen). */
    const val EDGE_MARGIN = 0.04
    const val MIN_EDGE_AREA = 0.005
    const val MIN_INNER_AREA = 0.12

    /** ¿Sombra proyectada? Toca el borde y no es diminuta, o es muy grande aunque esté dentro. */
    fun isCastShadow(areaFrac: Double, touchesEdge: Boolean): Boolean =
        (touchesEdge && areaFrac >= MIN_EDGE_AREA) || areaFrac >= MIN_INNER_AREA

    /** Textura del residuo (|r - Gauss| medio): papel liso hasta [TEX0]; con [TEX1] o más, foto o bloque con detalle. */
    const val TEX0 = 0.012
    const val TEX1 = 0.03

    /** Densidad local de papel (fracción de píxeles junto a la envolvente clara): foto con [DENS0] o menos, papel con [DENS1]. */
    const val DENS0 = 0.55
    const val DENS1 = 0.78
    /** Píxel de papel: todos los canales >= [PAPER_REL] de la envolvente clara ([PAPER_REL_DEEP] menos en el núcleo de una sombra). */
    const val PAPER_REL = 0.92
    const val PAPER_REL_DEEP = 0.10

    /** Desviación máxima del residuo de luz dentro de la sombra, lejos de la penumbra (allí se corrige el tinte). */
    const val INNER_DEV = 0.06
    /** Foto en color: croma (max - min de canales, niveles normalizados) > [PHOTO_CHROMA] en una fracción local [PHOTO0] (nada) .. [PHOTO1] (foto). */
    const val PHOTO_CHROMA = 40.0
    const val PHOTO0 = 0.25
    const val PHOTO1 = 0.45
    /** Desviación máxima del tinte del residuo (cociente canal / luz) fuera de la penumbra. */
    const val TINT_DEV = 0.05

    /** Núcleo de una sombra profunda: el límite inferior del residuo válido baja [DEEP] y el residuo de luz puede bajar hasta [DEEP_DEV]. */
    const val DEEP = 0.55
    const val DEEP_DEV = 0.85
    /** Tinta: canal mínimo < [INK_REL] de la envolvente; fracción local [INK0] (peso 0) .. [INK1] (peso 1) para el núcleo profundo. */
    const val INK_REL = 0.6
    const val INK0 = 0.02
    const val INK1 = 0.06
    /** Gradiente de log(envolvente clara) por píxel a 640 px: más = borde nítido impreso (figura, foto), menos = penumbra. */
    const val SHARP_GRAD = 0.33
    /** Área mínima (fracción de la imagen) de una zona profunda (residuo < [RES_MIN] en el núcleo) para tratarla como papel en sombra. */
    const val DEEP_AREA = 0.03

    /** Cota inferior del residuo final: 0.6 fuera del núcleo profundo, 0.15 en él. */
    fun residualFloor(deep: Double): Double = 0.6 - 0.45 * deep.coerceIn(0.0, 1.0)

    /** Ganancia extra máxima del croma (|Cr - 128|, |Cb - 128| <= [absMax]) sin saturar ningún canal (mismo tono). */
    fun chromaHeadroom(absMax: Double): Double = max(0.0, 127.0 / max(1.0, absMax) - 1.0)

    /** Fuerza de la sombra (0..1) para una atenuación [att] = luz / luz plena. */
    fun strength(att: Double): Double = ((START_ATT - att) / (START_ATT - FULL_ATT)).coerceIn(0.0, 1.0)

    /** Refuerzo del croma de la tinta cálida con luz relativa [light] (0..1). */
    fun warmGain(light: Double): Double = Math.pow(light.coerceIn(0.3, 1.0), -WARM_GAMMA).coerceIn(1.0, MAX_CHROMA_GAIN)

    /** Peso de papel (0..1) de un residuo de luz de media [mean] y dispersión entre canales [spread] (0: bloque oscuro o de color). */
    fun residualWeight(mean: Double, spread: Double, deep: Double = 0.0): Double {
        // (en el núcleo profundo -[deep] 0..1- el límite inferior baja [DEEP]: papel a 0.15-0.6 del fondo estimado)
        val lo = (mean + DEEP * deep - RES_MIN0) / (RES_MIN - RES_MIN0)
        val hi = (RES_MAX0 - mean) / (RES_MAX0 - RES_MAX)
        val sp = (RES_SPREAD0 - spread) / (RES_SPREAD0 - RES_SPREAD)
        return minOf(lo, hi, sp).coerceIn(0.0, 1.0)
    }

    /** σ del ruido a partir de la media de |Y - Gauss(Y, 1)| (ruido blanco gaussiano: E|r| ≈ 0.64·σ). */
    fun noiseSigma(meanAbsHighPass: Double): Double = 1.56 * meanAbsHighPass

    /** Peso del color para el refuerzo: |croma YCrCb| 4..10 -> 0..1 (el ruido de color del papel no se refuerza). */
    fun chromaWeight(absChroma: Double): Double = ((absChroma - 4.0) / 6.0).coerceIn(0.0, 1.0)
}

/** Gancho de depuración (banco/escritorio): recibe intermedios con nombre. Nulo en la app. */
internal object ShadowDebug {
    @Volatile var dump: ((String, Mat) -> Unit)? = null
    @Volatile var log: ((String) -> Unit)? = null
    /** Tiempos acumulados por clave (ms), sólo para el banco. */
    val times = java.util.concurrent.ConcurrentHashMap<String, Double>()
    inline fun <T> time(key: String, f: () -> T): T {
        if (log == null) return f()
        val t = System.nanoTime(); try { return f() } finally { times.merge(key, (System.nanoTime() - t) / 1e6, Double::plus) }
    }
}

/**
 * Mapa de SOMBRAS (mano, celular, pliegues, viñeteo) a baja resolución.
 * [strength] 32FC1 0..1: 1 = sombra plena, 0 = luz plena; incluye la penumbra (franja de transición) con un margen
 * suave. [atten] = factor de luz medio dentro de la sombra por canal (R, G, B; la sombra suele ser azulada:
 * atenúa menos el azul). [coverage] = fracción de la imagen en sombra (fuerza > 0.5).
 */
internal class ShadowMap(val strength: Mat, val coverage: Double, val atten: DoubleArray, val light: Mat, val entering: Mat) {
    fun release() { strength.release(); light.release(); entering.release() }

    /** Fuerza de las sombras que ENTRAN desde el borde de la imagen (el objeto que la proyecta está fuera: mano, móvil), remuestreada a [size]. */
    fun enteringAt(size: Size): Mat {
        val m = Mat(); Imgproc.resize(entering, m, size, 0.0, 0.0, Imgproc.INTER_LINEAR); return m
    }

    /** Fuerza remuestreada a [size] (32FC1 nuevo). */
    fun strengthAt(size: Size): Mat {
        val m = Mat(); Imgproc.resize(strength, m, size, 0.0, 0.0, Imgproc.INTER_LINEAR); return m
    }
}

/**
 * Sombras tratadas POR ZONA. Modelo: la sombra multiplica la luz que recibe el papel por un factor (por canal) que
 * varía suavemente dentro de ella y cambia deprisa en el borde (penumbra estrecha). La normalización global por el
 * fondo ([Cv.estimateBackground]) ya divide por ese factor, pero el fondo se estima a baja resolución y deja en la
 * penumbra un escalón/halo y dentro de la sombra un tinte residual (la luz de la sombra es más azul). Aquí:
 *  1. [analyze]: la sombra se detecta EN EL FONDO (papel sin tinta) como la zona cuya luminancia cae claramente por
 *     debajo de la envolvente superior del entorno (luz plena) -> fuerza 0..1, ensanchada para cubrir la penumbra.
 *     La tinta y los objetos no cuentan: el fondo ya los ha quitado y rellenado.
 *  2. [relight]: SÓLO en esa zona se re-estima el factor de luz residual a resolución media y POR CANAL sobre la
 *     imagen ya normalizada (cierre morfológico: quita la tinta sin desplazar el borde de la sombra + mediana);
 *     los valores que no son papel (bloques oscuros o de color: factor < 0.72 o con mucho croma) se rellenan con el
 *     entorno. El fondo se multiplica por ese residuo ponderado por la fuerza de la sombra: el papel en sombra y en
 *     la penumbra queda al mismo nivel y con el mismo blanco que el iluminado; fuera de la sombra nada cambia.
 *  3. [gain]/[noiseScale]: los renders adaptan umbrales, des-ruido y contraste de la tinta a la zona (dentro de la
 *     sombra la tinta tiene menos contraste y más ruido).
 */
internal object Shadows {

    private const val START_ATT = ShadowMath.START_ATT
    private const val FULL_ATT = ShadowMath.FULL_ATT

    /**
     * Mapa de sombras a partir del fondo a baja resolución [bgSmall] (8UC1/8UC3, papel + iluminación, sin tinta).
     * Null si no hay sombra apreciable.
     */
    fun analyze(bgSmall: Mat): ShadowMap? = MatBag().use { bag ->
        val w = bgSmall.cols(); val h = bgSmall.rows(); val side = max(w, h)
        if (min(w, h) < 16) return null
        val g8 = if (bgSmall.channels() == 1) bgSmall else bag.add(Cv.gray(bgSmall))
        val l = bag.mat(); g8.convertTo(l, CvType.CV_32F)
        // Envolvente superior (luz plena del entorno): máximo en ~30 % del lado y media del mismo tamaño
        val big = Cv.oddAtLeast(side * 0.30, 3)
        val lit = bag.mat()
        Imgproc.dilate(l, lit, Cv.kernel(Imgproc.MORPH_RECT, big))
        Imgproc.blur(lit, lit, Size(big.toDouble(), big.toDouble()))
        Core.max(lit, Scalar(8.0), lit)
        val a = bag.mat(); Core.divide(l, lit, a)
        // fuerza = (START - a)/(START - FULL) en [0, 1]
        val s = Mat(); a.convertTo(s, CvType.CV_32F, -1.0 / (START_ATT - FULL_ATT), START_ATT / (START_ATT - FULL_ATT))
        Core.min(s, Scalar(1.0), s); Core.max(s, Scalar(0.0), s)
        // Una sombra apenas tiñe el papel (luz algo más azul: factor por canal parecido); una foto o un bloque de color
        // que el fondo siguió (cielo azul, foto a sangre) cambia mucho el cociente entre canales respecto del papel
        // iluminado -> no es sombra (cociente max/min de los factores por canal TINT0 -> peso 1, TINT1 -> peso 0)
        if (bgSmall.channels() >= 3) {
            val ch = ArrayList<Mat>(3); Core.split(bgSmall, ch)
            val fmax = bag.mat(); val fmin = bag.mat()
            for ((i, c) in ch.withIndex()) {
                val cf = bag.mat(); c.convertTo(cf, CvType.CV_32F); c.release()
                val lc = bag.mat(); Imgproc.dilate(cf, lc, Cv.kernel(Imgproc.MORPH_RECT, big)); Imgproc.blur(lc, lc, Size(big.toDouble(), big.toDouble()))
                Core.max(lc, Scalar(8.0), lc); Core.divide(cf, lc, cf)
                if (i == 0) { cf.copyTo(fmax); cf.copyTo(fmin) } else { Core.max(fmax, cf, fmax); Core.min(fmin, cf, fmin) }
            }
            Core.max(fmin, Scalar(0.02), fmin)
            val tint = bag.mat(); Core.divide(fmax, fmin, tint)
            tint.convertTo(tint, -1, -1.0 / (ShadowMath.TINT1 - ShadowMath.TINT0), ShadowMath.TINT1 / (ShadowMath.TINT1 - ShadowMath.TINT0))
            Core.min(tint, Scalar(1.0), tint); Core.max(tint, Scalar(0.0), tint)
            Core.multiply(s, tint, s)
        }
        val entering = Mat()
        keepCastShadows(s, entering, bag)
        val core = bag.mat(); Core.compare(s, Scalar(0.5), core, Core.CMP_GT)
        val coverage = Core.countNonZero(core).toDouble() / (w.toDouble() * h)
        val maxS = Core.minMaxLoc(s).maxVal
        // Sin sombra apreciable, o "sombra" en más de la mitad de la imagen: no es una sombra sobre la hoja sino la
        // luz de toda la escena (foto sin documento, mesa, fondo mal estimado) -> nada que tratar por zona
        if (maxS < 0.35 || coverage < 0.003 || coverage > ShadowMath.MAX_COVERAGE) { s.release(); entering.release(); return null }
        // Factor de luz por canal dentro de la sombra (relativo a la zona iluminada)
        val litM = bag.mat(); Core.compare(s, Scalar(0.02), litM, Core.CMP_LT)
        val atten = DoubleArray(3) { 1.0 }
        if (Core.countNonZero(litM) > 0.05 * w * h && Core.countNonZero(core) > 0) {
            val mi = Core.mean(bgSmall, core).`val`; val mo = Core.mean(bgSmall, litM).`val`
            for (c in 0 until min(3, bgSmall.channels())) atten[c] = mi[c] / max(1.0, mo[c])
            if (bgSmall.channels() == 1) { atten[1] = atten[0]; atten[2] = atten[0] }
        }
        // Margen: el fondo de baja resolución emborrona la penumbra -> se ensancha la zona (~4 % del lado) y se suaviza
        Imgproc.dilate(s, s, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.oddAtLeast(side * 0.04, 3)))
        Imgproc.GaussianBlur(s, s, Size(0.0, 0.0), max(1.0, side * 0.015))
        Imgproc.dilate(entering, entering, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.oddAtLeast(side * 0.04, 3)))
        Imgproc.GaussianBlur(entering, entering, Size(0.0, 0.0), max(1.0, side * 0.015))
        ShadowDebug.log?.invoke("sombra cobertura=%.3f max=%.2f aten=%.2f/%.2f/%.2f".format(coverage, maxS, atten[0], atten[1], atten[2]))
        ShadowDebug.dump?.invoke("shadow_strength", bag.mat().also { s.convertTo(it, CvType.CV_8U, 255.0) })
        // Luz relativa (0.3..1) suavizada: guía el refuerzo del color de la tinta en sombra ([restoreChroma])
        val light = Mat(); Core.max(a, Scalar(0.3), light); Core.min(light, Scalar(1.0), light)
        Imgproc.GaussianBlur(light, light, Size(0.0, 0.0), max(1.0, side * 0.01))
        ShadowMap(s, coverage, atten, light, entering)
    }

    /**
     * Sólo SOMBRAS PROYECTADAS: una zona oscura del fondo que es pequeña y está dentro de la hoja (recuadro gris,
     * cabecera sombreada, texto emborronado, mancha) tiene la misma forma de luminancia que una sombra, pero una sombra
     * de mano, celular o pliegue entra desde fuera de la hoja o es muy grande. Se conservan (en [s], in-place) las
     * componentes de la zona (fuerza > 0.3) que tocan el borde de la imagen (margen [ShadowMath.EDGE_MARGIN] del lado)
     * con área >= [ShadowMath.MIN_EDGE_AREA], o con área >= [ShadowMath.MIN_INNER_AREA] aunque no lo toquen.
     */
    private fun keepCastShadows(s: Mat, entering: Mat, bag: MatBag) {
        val w = s.cols(); val h = s.rows(); val side = max(w, h)
        s.copyTo(entering)
        val m = bag.mat(); Core.compare(s, Scalar(0.3), m, Core.CMP_GT)
        if (Core.countNonZero(m) == 0) return
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val n = Imgproc.connectedComponentsWithStats(m, labels, stats, cents, 8, CvType.CV_32S)
        val st = IntArray(n * 5); stats.get(0, 0, st)
        val mg = (ShadowMath.EDGE_MARGIN * side).roundToInt()
        val keep = BooleanArray(n); val enter = BooleanArray(n)
        var dropped = false; var notEntering = false
        for (c in 1 until n) {
            val x = st[c * 5]; val y = st[c * 5 + 1]; val bw = st[c * 5 + 2]; val bh = st[c * 5 + 3]; val a = st[c * 5 + 4]
            val frac = a.toDouble() / (w.toDouble() * h)
            val edge = x <= mg || y <= mg || x + bw >= w - mg || y + bh >= h - mg
            keep[c] = ShadowMath.isCastShadow(frac, edge)
            enter[c] = edge && frac >= ShadowMath.MIN_EDGE_AREA
            if (!keep[c]) dropped = true
            if (!enter[c]) notEntering = true
        }
        if (!dropped && !notEntering) return
        val lab = IntArray(w * h); labels.get(0, 0, lab)
        fun restrict(sel: BooleanArray, dst: Mat) {
            val kb = ByteArray(w * h)
            for (i in lab.indices) if (lab[i] > 0 && sel[lab[i]]) kb[i] = -1
            val km = bag.mat(); km.create(h, w, CvType.CV_8UC1); km.put(0, 0, kb)
            // (con margen: la cola tenue de la penumbra de una sombra conservada sigue entrando)
            Imgproc.dilate(km, km, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.oddAtLeast(side * 0.03, 3)))
            val drop = bag.mat(); Core.bitwise_not(km, drop)
            dst.setTo(Scalar(0.0), drop)
        }
        if (dropped) restrict(keep, s)
        if (notEntering) restrict(enter, entering)
    }

    /**
     * Re-iluminación por zona: devuelve el fondo [bgMid] (8U, 1 o 3 canales, cualquier resolución) corregido en la
     * sombra de [map] (mismo tamaño que [bgMid]); el residuo se mide a [side] px de lado largo. Ver la cabecera.
     */
    fun relight(img: Mat, bgMid: Mat, map: ShadowMap, side: Int): Mat = MatBag().use { bag ->
        val mi = bag.mat(); Cv.downscale(img, mi, max(64, side))
        val ms = max(mi.cols(), mi.rows())
        val b = bag.mat(); Imgproc.resize(bgMid, b, mi.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val nm0 = bag.mat(); Cv.divideByBackground(mi, b, nm0)
        // Núcleo de una sombra que ENTRA desde el borde (fuerza 0.3 -> 0, 0.7 -> 1): el fondo de baja resolución no llega
        // al fondo de una sombra profunda de bordes duros (móvil, mano a pleno sol): allí el papel queda a 0.15-0.7 del
        // nivel y es papel, no un bloque oscuro; además su grano (amplificado por la división) aparta más píxeles de papel
        // de la envolvente. (Sólo las que entran desde fuera: una zona oscura grande DENTRO de la hoja suele ser una figura.)
        val core = bag.add(map.enteringAt(mi.size()))
        core.convertTo(core, -1, 1.0 / 0.4, -0.3 / 0.4); Core.min(core, Scalar(1.0), core); Core.max(core, Scalar(0.0), core)
        // (y lejos de bordes NÍTIDOS del papel sin tinta: el borde de una sombra es una rampa de varios px -penumbra-,
        // el de una figura oscura impresa o una foto, un escalón de 1-2 px -> cerca de él no hay núcleo profundo)
        val notSharp = sharpEdgeZone(mi, ms, bag).also { Core.subtract(Mat.ones(it.size(), CvType.CV_32F).also { o -> bag.add(o) }, it, it) }
        Core.multiply(core, notSharp, core)
        // Papel de la imagen normalizada: el cierre quita la tinta (trazos y letras < ~1.6 % del lado) sin desplazar el
        // borde de la sombra (un escalón o una rampa no cambian con un cierre); la mediana quita el grano restante
        val nm = bag.mat()
        Imgproc.morphologyEx(nm0, nm, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, Cv.oddAtLeast(ms * 0.016, 3)))
        Imgproc.medianBlur(nm, nm, 5)
        val r = bag.mat()
        val density = bag.mat()   // fracción local de píxeles de papel (ver abajo)
        val inkFrac = bag.mat()   // fracción local de píxeles de tinta (ver abajo)
        run {
            // El cierre toma la envolvente SUPERIOR del grano (sesgo ~ +1σ, mayor en la sombra, donde el ruido crece
            // con la ganancia: el papel en sombra quedaba gris). Nivel sin sesgo = media local de los píxeles de papel
            // (los que no están claramente por debajo de la envolvente), con un radio pequeño (~0.4 % del lado)
            val ef = bag.mat(); nm.convertTo(ef, CvType.CV_32F)
            val raw = bag.mat(); nm0.convertTo(raw, CvType.CV_32F)
            // Papel = TODOS los canales cerca de la envolvente (la tinta amarilla sólo oscurece el azul: con la
            // luminancia contaba como papel y el azul del papel salía bajo -> la estrella amarilla se apagaba)
            val ratio = bag.mat(); Core.max(ef, Scalar.all(1.0), ratio); Core.divide(raw, ratio, ratio)
            val rmin = if (ratio.channels() == 1) ratio else bag.mat().also { m ->
                val rc = ArrayList<Mat>(3); Core.split(ratio, rc)
                Core.min(rc[0], rc[1], m); Core.min(m, rc[2], m); for (c in rc) c.release()
            }
            // (umbral de papel ShadowMath.PAPER_REL, más bajo en el núcleo de la sombra, donde el grano es mayor)
            // (bajada = 2.5·σ del grano medido en el papel -mediana local de |r - Gauss(r)|, que la tinta apenas mueve-,
            // hasta PAPER_REL_DEEP, sólo en el núcleo: un dibujo gris en una sombra suave no tiene grano y no baja)
            val pthr = bag.mat()
            run {
                val bl = bag.mat(); Imgproc.GaussianBlur(rmin, bl, Size(0.0, 0.0), 1.0)
                val hp = bag.mat(); Core.absdiff(rmin, bl, hp); Core.min(hp, Scalar(0.1), hp)
                val h8 = bag.mat(); hp.convertTo(h8, CvType.CV_8U, 2550.0); Imgproc.medianBlur(h8, h8, 5)
                h8.convertTo(pthr, CvType.CV_32F, 1.0 / 2550.0)
                Imgproc.GaussianBlur(pthr, pthr, Size(0.0, 0.0), max(1.0, ms * 0.015))
                Core.multiply(pthr, Scalar(2.5 * 1.25), pthr); Core.min(pthr, Scalar(ShadowMath.PAPER_REL_DEEP), pthr)
                // (zona profunda grande según la envolvente -el cierre, sesgado al alza, basta para decidirlo-)
                val eg = bag.mat(); if (ef.channels() == 1) ef.copyTo(eg) else Imgproc.cvtColor(ef, eg, Imgproc.COLOR_RGB2GRAY)
                Core.multiply(eg, Scalar(1.0 / Cv.PAPER_LEVEL), eg)
                Core.multiply(pthr, core, pthr); Core.multiply(pthr, largeDeep(eg, core, ms, bag), pthr); Core.subtract(Mat.ones(pthr.size(), CvType.CV_32F).also { bag.add(it) }.also { Core.multiply(it, Scalar(ShadowMath.PAPER_REL), it) }, pthr, pthr)
            }
            val pmask = bag.mat(); Core.compare(rmin, pthr, pmask, Core.CMP_GE)
            val pf = bag.mat(); pmask.convertTo(pf, CvType.CV_32F, 1.0 / 255.0)
            Imgproc.GaussianBlur(pf, density, Size(0.0, 0.0), max(1.0, ms * 0.015))
            // Tinta (píxeles muy por debajo de la envolvente): el papel en sombra profunda conserva su texto, más oscuro
            // que él; un bloque negro impreso (fondo de una figura) no tiene nada más oscuro dentro
            val im = bag.mat(); Core.compare(rmin, Scalar(ShadowMath.INK_REL), im, Core.CMP_LT)
            im.convertTo(inkFrac, CvType.CV_32F, 1.0 / 255.0)
            Imgproc.GaussianBlur(inkFrac, inkFrac, Size(0.0, 0.0), max(1.0, ms * 0.015))
            val sig = max(1.0, ms * 0.004)
            val den = bag.mat(); Imgproc.GaussianBlur(pf, den, Size(0.0, 0.0), sig)
            val pC = bag.mat(); if (raw.channels() == 3) Core.merge(listOf(pf, pf, pf), pC) else pf.copyTo(pC)
            val num = bag.mat(); Core.multiply(raw, pC, num); Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), sig)
            val low = bag.mat(); Core.compare(den, Scalar(0.15), low, Core.CMP_LT)
            Core.max(den, Scalar(1e-3), den)
            val dC = bag.mat(); if (raw.channels() == 3) Core.merge(listOf(den, den, den), dC) else den.copyTo(dC)
            Core.divide(num, dC, r)
            ef.copyTo(r, low)
            r.convertTo(r, CvType.CV_32F, 1.0 / Cv.PAPER_LEVEL)
        }
        val ch = r.channels()
        // Validez SUAVE del papel (0..1): factor razonable y casi neutro (los bloques oscuros o de color no son papel en
        // sombra). Con un umbral duro el borde del umbral dibujaba una mancha nueva donde el residuo lo cruzaba.
        val chans = ArrayList<Mat>(3); Core.split(r, chans); for (c in chans) bag.add(c)
        val mx = bag.mat(); val mn = bag.mat(); val mean = bag.mat()
        chans[0].copyTo(mx); chans[0].copyTo(mn); chans[0].copyTo(mean)
        for (i in 1 until ch) { Core.max(mx, chans[i], mx); Core.min(mn, chans[i], mn); Core.add(mean, chans[i], mean) }
        Core.multiply(mean, Scalar(1.0 / ch), mean)
        Core.subtract(mx, mn, mx)
        val vf = bag.mat(); val t = bag.mat()
        // (en el núcleo de la sombra el límite inferior del residuo válido baja [ShadowMath.DEEP])
        // (sólo con tinta dentro: fracción INK0 -> peso 0, INK1 -> peso 1)
        val coreInk = bag.mat(); inkFrac.convertTo(coreInk, -1, 1.0 / (ShadowMath.INK1 - ShadowMath.INK0), -ShadowMath.INK0 / (ShadowMath.INK1 - ShadowMath.INK0))
        Core.min(coreInk, Scalar(1.0), coreInk); Core.max(coreInk, Scalar(0.0), coreInk); Core.multiply(coreInk, core, coreInk)
        // (y sólo en zonas profundas GRANDES: ver [largeDeep])
        Core.multiply(coreInk, largeDeep(mean, coreInk, ms, bag), coreInk)
        val meanLo = bag.mat(); Core.scaleAdd(coreInk, ShadowMath.DEEP, mean, meanLo)
        // (rampas de [ShadowMath.residualWeight])
        meanLo.convertTo(vf, -1, 1.0 / (ShadowMath.RES_MIN - ShadowMath.RES_MIN0), -ShadowMath.RES_MIN0 / (ShadowMath.RES_MIN - ShadowMath.RES_MIN0))
        mean.convertTo(t, -1, -1.0 / (ShadowMath.RES_MAX0 - ShadowMath.RES_MAX), ShadowMath.RES_MAX0 / (ShadowMath.RES_MAX0 - ShadowMath.RES_MAX)); Core.min(vf, t, vf)
        mx.convertTo(t, -1, -1.0 / (ShadowMath.RES_SPREAD0 - ShadowMath.RES_SPREAD), ShadowMath.RES_SPREAD0 / (ShadowMath.RES_SPREAD0 - ShadowMath.RES_SPREAD)); Core.min(vf, t, vf)
        Core.min(vf, Scalar(1.0), vf); Core.max(vf, Scalar(0.0), vf)
        // Densidad de papel: en el papel en sombra (aun con texto denso) la mayoría de los píxeles están junto a la
        // envolvente clara; en una foto o un bloque oscuro con detalle, no -> peso 0 (la foto oscura de una revista que
        // tocaba el margen se tomaba por sombra y se lavaba)
        density.convertTo(t, -1, 1.0 / (ShadowMath.DENS1 - ShadowMath.DENS0), -ShadowMath.DENS0 / (ShadowMath.DENS1 - ShadowMath.DENS0))
        Core.min(t, Scalar(1.0), t); Core.max(t, Scalar(0.0), t); Core.min(vf, t, vf)
        ShadowDebug.dump?.invoke("shadow_vdens", bag.mat().also { t.convertTo(it, CvType.CV_8U, 255.0) })
        // Textura: el papel en sombra (y la penumbra, una rampa suave) es liso a escala de pocos píxeles; una foto o un
        // bloque con detalle no lo es -> peso 0 (antes se "re-iluminaba" la foto oscura de una revista y se lavaba)
        run {
            val bl = bag.mat(); Imgproc.GaussianBlur(mean, bl, Size(0.0, 0.0), max(1.0, ms * 0.004))
            val hf = bag.mat(); Core.absdiff(mean, bl, hf)
            Imgproc.GaussianBlur(hf, hf, Size(0.0, 0.0), max(1.0, ms * 0.008))
            hf.convertTo(t, -1, -1.0 / (ShadowMath.TEX1 - ShadowMath.TEX0), ShadowMath.TEX1 / (ShadowMath.TEX1 - ShadowMath.TEX0))
            Core.min(t, Scalar(1.0), t); Core.max(t, Scalar(0.0), t)
            Core.min(vf, t, vf)
        }
        ShadowDebug.dump?.invoke("shadow_valid", bag.mat().also { vf.convertTo(it, CvType.CV_8U, 255.0) })
        // (el residuo de luz sólo baja de más donde el núcleo profundo tiene papel válido, ensanchado un poco)
        val deep = bag.mat(); Imgproc.dilate(coreInk, deep, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.oddAtLeast(ms * 0.02, 3)))
        Imgproc.GaussianBlur(deep, deep, Size(0.0, 0.0), max(1.0, ms * 0.01))
        ShadowDebug.dump?.invoke("shadow_core", bag.mat().also { deep.convertTo(it, CvType.CV_8U, 255.0) })
        ShadowDebug.dump?.invoke("shadow_ink", bag.mat().also { inkFrac.convertTo(it, CvType.CV_8U, 255.0) })
        // Fotos en color: muchos píxeles con color saturado juntos (la escritura de color, aun densa, son trazos finos:
        // poca fracción) -> no son papel (peso 0) y en ellas el residuo no pasa de la cota interior ni en la "penumbra"
        // (los degradados de un cielo se tomaban por borde de sombra y la foto se lavaba)
        val photo = bag.mat()
        if (nm0.channels() == 3) {
            val c3 = ArrayList<Mat>(3); Core.split(nm0, c3); for (c in c3) bag.add(c)
            val cmx = bag.mat(); val cmn = bag.mat(); Core.max(c3[0], c3[1], cmx); Core.max(cmx, c3[2], cmx)
            Core.min(c3[0], c3[1], cmn); Core.min(cmn, c3[2], cmn); Core.subtract(cmx, cmn, cmx)
            val sm = bag.mat(); Core.compare(cmx, Scalar(ShadowMath.PHOTO_CHROMA), sm, Core.CMP_GT)
            sm.convertTo(photo, CvType.CV_32F, 1.0 / 255.0)
            Imgproc.GaussianBlur(photo, photo, Size(0.0, 0.0), max(1.0, ms * 0.02))
            photo.convertTo(photo, -1, 1.0 / (ShadowMath.PHOTO1 - ShadowMath.PHOTO0), -ShadowMath.PHOTO0 / (ShadowMath.PHOTO1 - ShadowMath.PHOTO0))
            Core.min(photo, Scalar(1.0), photo); Core.max(photo, Scalar(0.0), photo)
            val np = bag.mat(); Core.subtract(Mat.ones(photo.size(), CvType.CV_32F).also { bag.add(it) }, photo, np)
            Core.multiply(vf, np, vf)
        }
        // Relleno: media del residuo ponderada por la validez (convolución normalizada a dos escalas; sin soporte -> 1)
        // y mezcla r' = r·v + relleno·(1 - v)
        val vC = bag.mat(); if (ch == 3) Core.merge(listOf(vf, vf, vf), vC) else vf.copyTo(vC)
        val fill = bag.mat(); fill.create(r.size(), r.type()); fill.setTo(Scalar.all(1.0))
        val num = bag.mat(); val den = bag.mat(); val denC = bag.mat(); val f1 = bag.mat(); val sup = bag.mat(); val supC = bag.mat()
        for (sig in doubleArrayOf(ms * 0.08, ms * 0.02)) {     // grande primero, la pequeña afina donde hay soporte
            Core.multiply(r, vC, num); Cv.blurLarge(num, num, sig)
            Cv.blurLarge(vf, den, sig)
            // soporte: 0 con den < 0.02, 1 con den > 0.1
            den.convertTo(sup, -1, 1.0 / 0.08, -0.02 / 0.08); Core.min(sup, Scalar(1.0), sup); Core.max(sup, Scalar(0.0), sup)
            Core.max(den, Scalar(1e-4), den)
            if (ch == 3) { Core.merge(listOf(den, den, den), denC); Core.merge(listOf(sup, sup, sup), supC) } else { den.copyTo(denC); sup.copyTo(supC) }
            Core.divide(num, denC, f1)
            // fill = fill + (f1 - fill)·soporte
            Core.subtract(f1, fill, f1); Core.multiply(f1, supC, f1); Core.add(fill, f1, fill)
        }
        // r = fill + (r - fill)·v
        Core.subtract(r, fill, r); Core.multiply(r, vC, r); Core.add(r, fill, r)
        Imgproc.GaussianBlur(r, r, Size(0.0, 0.0), max(1.0, ms * 0.003))
        limitToPenumbra(r, b, ms, deep, photo, notSharp, bag)
        // Sólo en la zona de sombra: r' = 1 + (r - 1)·fuerza
        val sw = bag.add(map.strengthAt(mi.size()))
        // (zonas de sombra débil -fuerza < 0.15: viñeteo, el canto de color de un cuaderno- sin re-iluminar: el residuo allí
        // es ruido de estimación y movía de clase los bordes oscuros de la hoja)
        sw.convertTo(sw, -1, 1.0 / 0.45, -0.15 / 0.45); Core.min(sw, Scalar(1.0), sw); Core.max(sw, Scalar(0.0), sw)
        val swC = bag.mat(); if (ch == 3) Core.merge(listOf(sw, sw, sw), swC) else sw.copyTo(swC)
        Core.subtract(r, Scalar.all(1.0), r); Core.multiply(r, swC, r); Core.add(r, Scalar.all(1.0), r)
        // (acotado a [0.6, 1.4]; en el núcleo de una sombra que entra desde el borde, hasta 0.15)
        Core.min(r, Scalar.all(1.4), r)
        run {
            val lo = bag.mat(); deep.convertTo(lo, -1, -(0.6 - 0.15), 0.6)
            val loC = bag.mat(); if (ch == 3) Core.merge(listOf(lo, lo, lo), loC) else lo.copyTo(loC)
            Core.max(r, loC, r)
        }
        // (a la resolución de [bgMid]: fuera de la sombra el fondo afinado queda idéntico)
        val rr = if (r.size() == bgMid.size()) r else bag.mat().also { Imgproc.resize(r, it, bgMid.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) }
        val bf = bag.mat(); bgMid.convertTo(bf, CvType.CV_32F)
        Core.multiply(bf, rr, bf)
        val out = Mat(); bf.convertTo(out, bgMid.type())
        Core.max(out, Scalar.all(1.0), out)
        ShadowDebug.dump?.invoke("shadow_residual", bag.mat().also { r.convertTo(it, CvType.CV_8U, 128.0) })
        out
    }

    /**
     * Zonas profundas GRANDES (32FC1 0..1): componentes con residuo [res] (32FC1) < [ShadowMath.RES_MIN] y [gate] > 0.5
     * de al menos [ShadowMath.DEEP_AREA] de la imagen, ensanchadas ~4 % del lado. El papel de una sombra profunda es una
     * zona extensa; un dibujo gris o un bloque dentro de una sombra suave son manchas pequeñas.
     */
    private fun largeDeep(res: Mat, gate: Mat, ms: Int, bag: MatBag): Mat {
        val out = bag.mat(); out.create(res.size(), CvType.CV_32F); out.setTo(Scalar(0.0))
        val cand = bag.mat(); Core.compare(res, Scalar(ShadowMath.RES_MIN), cand, Core.CMP_LT)
        val cm = bag.mat(); Core.compare(gate, Scalar(0.5), cm, Core.CMP_GT); Core.bitwise_and(cand, cm, cand)
        val minA = ShadowMath.DEEP_AREA * cand.total()
        if (Core.countNonZero(cand) < minA) return out
        val labels = bag.mat(); val stats = bag.mat(); val cents = bag.mat()
        val n = Imgproc.connectedComponentsWithStats(cand, labels, stats, cents, 8, CvType.CV_32S)
        val st = IntArray(n * 5); stats.get(0, 0, st)
        val keep = BooleanArray(n) { c -> c > 0 && st[c * 5 + 4] >= minA }
        if (keep.none { it }) return out
        val lab = IntArray(cand.total().toInt()); labels.get(0, 0, lab)
        val kb = ByteArray(lab.size); for (i in lab.indices) if (keep[lab[i]]) kb[i] = -1
        val km = bag.mat(); km.create(cand.rows(), cand.cols(), CvType.CV_8UC1); km.put(0, 0, kb)
        // (ensanchada: el papel de la zona incluye el que rodea el texto, y la transición con la penumbra)
        Imgproc.dilate(km, km, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.oddAtLeast(ms * 0.04, 3)))
        km.convertTo(out, CvType.CV_32F, 1.0 / 255.0)
        Imgproc.GaussianBlur(out, out, Size(0.0, 0.0), max(1.0, ms * 0.01))
        return out
    }

    /**
     * Zona (32FC1 0..1, tamaño de [mi]) junto a bordes NÍTIDOS de la envolvente clara de [mi] (cierre: sin la tinta):
     * gradiente de log(envolvente) por encima de [ShadowMath.SHARP_GRAD] (a 640 px de lado; la penumbra de una sombra
     * proyectada queda por debajo, el canto de una figura o foto impresa por encima), ensanchada ~6 % del lado.
     */
    private fun sharpEdgeZone(mi: Mat, ms: Int, bag: MatBag): Mat {
        val g8 = if (mi.channels() == 1) mi else bag.add(Cv.gray(mi))
        val e = bag.mat(); Imgproc.morphologyEx(g8, e, Imgproc.MORPH_CLOSE, Cv.kernel(Imgproc.MORPH_RECT, Cv.oddAtLeast(ms * 0.016, 3)))
        Imgproc.medianBlur(e, e, 5)
        val l = bag.mat(); e.convertTo(l, CvType.CV_32F); Core.max(l, Scalar(1.0), l); Core.log(l, l)
        val gx = bag.mat(); val gy = bag.mat()
        Imgproc.Sobel(l, gx, CvType.CV_32F, 1, 0, 3, 0.125, 0.0); Imgproc.Sobel(l, gy, CvType.CV_32F, 0, 1, 3, 0.125, 0.0)
        val gm = bag.mat(); Core.magnitude(gx, gy, gm)
        val t = ShadowMath.SHARP_GRAD * 640.0 / ms
        val z = Mat(); gm.convertTo(z, -1, 1.0 / (0.3 * t), -0.85 / 0.3)   // rampa 0.85·t -> 0, 1.15·t -> 1
        Core.min(z, Scalar(1.0), z); Core.max(z, Scalar(0.0), z)
        Imgproc.dilate(z, z, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.oddAtLeast(ms * 0.06, 3)))
        Imgproc.GaussianBlur(z, z, Size(0.0, 0.0), max(1.0, ms * 0.02))
        return bag.add(z)
    }

    /**
     * El residuo de LUZ (media de los canales) sólo se aplica entero donde el fondo de baja resolución cambia deprisa
     * (penumbra: allí el fondo emborronado deja el escalón y el halo); dentro de la zona se acota a ±[ShadowMath.INNER_DEV]
     * (un recuadro gris liso, un sombreado o una zona emborronada dentro de la sombra no son papel en sombra y se
     * aplanaban a blanco). El TINTE (cociente entre canales) se corrige siempre. [r] 32F (1 o 3 canales, in-place),
     * [b] = fondo de baja resolución remuestreado al tamaño de [r].
     */
    private fun limitToPenumbra(r: Mat, b: Mat, ms: Int, core: Mat, photo: Mat, notSharp: Mat, bag: MatBag) {
        val ch = r.channels()
        val g = bag.mat(); if (b.channels() == 1) b.convertTo(g, CvType.CV_32F) else { val g8 = bag.add(Cv.gray(b)); g8.convertTo(g, CvType.CV_32F) }
        Core.max(g, Scalar(1.0), g); Core.log(g, g)
        Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), max(1.0, ms * 0.004))
        val gx = bag.mat(); val gy = bag.mat()
        Imgproc.Sobel(g, gx, CvType.CV_32F, 1, 0, 3, 0.125, 0.0); Imgproc.Sobel(g, gy, CvType.CV_32F, 0, 1, 3, 0.125, 0.0)
        val gm = bag.mat(); Core.magnitude(gx, gy, gm)
        // gradiente por píxel de log(fondo) a ~640 px: penumbra 0.02-0.04, viñeteo < 0.002 -> banda 0.004..0.012
        val band = bag.mat(); gm.convertTo(band, -1, 1.0 / 0.008, -0.004 / 0.008); Core.min(band, Scalar(1.0), band); Core.max(band, Scalar(0.0), band)
        Imgproc.dilate(band, band, Cv.kernel(Imgproc.MORPH_ELLIPSE, Cv.oddAtLeast(ms * 0.03, 3)))
        Imgproc.GaussianBlur(band, band, Size(0.0, 0.0), max(1.0, ms * 0.01))
        // (junto a un borde nítido -canto de una figura o foto impresa- no hay penumbra: el gradiente del fondo es la figura)
        Core.multiply(band, notSharp, band)
        if (!photo.empty()) { val np = bag.mat(); Core.subtract(Mat.ones(photo.size(), CvType.CV_32F).also { bag.add(it) }, photo, np); Core.multiply(band, np, band) }
        // desviación máxima del residuo de luz: INNER_DEV dentro, ilimitada (0.6) en la penumbra
        val dev = bag.mat(); band.convertTo(dev, -1, 0.6 - ShadowMath.INNER_DEV, ShadowMath.INNER_DEV)
        val chans = ArrayList<Mat>(3); Core.split(r, chans); for (c in chans) bag.add(c)
        val l = bag.mat(); chans[0].copyTo(l); for (i in 1 until ch) Core.add(l, chans[i], l); Core.multiply(l, Scalar(1.0 / ch), l)
        Core.max(l, Scalar(1e-3), l)
        val lc = bag.mat(); Core.subtract(l, Scalar(1.0), lc)
        Core.min(lc, dev, lc)
        // hacia abajo, en el núcleo de la sombra, hasta [ShadowMath.DEEP_DEV] (el fondo no siguió la sombra profunda)
        val devDn = bag.mat(); Core.multiply(core, Scalar(ShadowMath.DEEP_DEV), devDn); Core.max(devDn, dev, devDn)
        Core.multiply(devDn, Scalar(-1.0), devDn); Core.max(lc, devDn, lc); Core.add(lc, Scalar(1.0), lc)
        // TINTE (r_c / l) acotado a ±TINT_DEV fuera de la penumbra (allí el fondo emborronado deja franjas de color;
        // dentro, la luz de la sombra apenas tiñe: un tinte mayor es una foto o un bloque de color que pasó por papel
        // -una nube clara en una foto de cielo- y teñía la foto entera), ±0.25 en ella; r_c = tinte_acotado · l_acotada
        if (ch > 1) {
            val dT = bag.mat(); band.convertTo(dT, -1, 0.25 - ShadowMath.TINT_DEV, ShadowMath.TINT_DEV)
            val hiT = bag.mat(); Core.add(dT, Scalar(1.0), hiT)
            val loT = bag.mat(); Core.subtract(Mat.ones(dT.size(), dT.type()).also { bag.add(it) }, dT, loT)
            for (c in chans) { Core.divide(c, l, c); Core.min(c, hiT, c); Core.max(c, loT, c); Core.multiply(c, lc, c) }
        } else {
            val k = bag.mat(); Core.divide(lc, l, k)
            for (c in chans) Core.multiply(c, k, c)
        }
        Core.merge(chans, r)
        ShadowDebug.dump?.invoke("shadow_band", bag.mat().also { band.convertTo(it, CvType.CV_8U, 255.0) })
    }

    /**
     * Ganancia EFECTIVA del ruido por zona, medida: la división por el fondo amplifica el ruido del papel en la sombra,
     * pero menos de lo que dice la ganancia (el sensor tiene ruido de lectura fijo, el ISP ya des-ruidó la zona
     * oscura, la compresión JPEG lo aplana). Se mide el ruido del papel en la imagen normalizada [n] (canal máximo,
     * |imagen - Gauss(1)| en los píxeles de papel, lejos de la tinta) por bloques a la resolución de [gain] (32FC1,
     * ganancia papel/fondo), relativo al de la zona iluminada (ganancia ~1). Devuelve un campo 32FC1 del tamaño de
     * [gain] con el factor de ruido elevado a 1/[exp] (así `campo^exp` = factor medido), acotado entre 1 y la
     * ganancia (nunca más estricto que antes). Con poca sombra (ganancia < 1.15 en todas partes) devuelve una copia
     * de [gain].
     */
    fun effectiveGain(n: Mat, gain: Mat, pm: Double, exp: Double): Mat = MatBag().use { bag ->
        val gmax = Core.minMaxLoc(gain).maxVal
        if (gmax < 1.15 || n.empty()) return gain.clone()
        // Media resolución (~1600 px): el cociente de ruidos entre zonas no cambia al promediar
        val sm = bag.mat(); Cv.downscale(n, sm, 1600)
        val v = if (sm.channels() == 1) sm else bag.mat().also { m ->
            val ch = ArrayList<Mat>(3); Core.split(sm, ch)
            Core.max(ch[0], ch[1], m); Core.max(m, ch[2], m); for (c in ch) c.release()
        }
        val bl = bag.mat(); Imgproc.GaussianBlur(v, bl, Size(0.0, 0.0), 1.0)
        val hp = bag.mat(); Core.absdiff(v, bl, hp)
        // Papel: claro (>= 88 % del nivel del papel) y a >= 2 px de cualquier cosa más oscura (bordes de la tinta)
        val paper = bag.mat(); Core.compare(v, Scalar(0.88 * pm), paper, Core.CMP_GE)
        Imgproc.erode(paper, paper, Cv.kernel(Imgproc.MORPH_RECT, 5))
        val hf = bag.mat(); hp.convertTo(hf, CvType.CV_32F)
        val pf = bag.mat(); paper.convertTo(pf, CvType.CV_32F, 1.0 / 255.0)
        Core.multiply(hf, pf, hf)
        val gs = gain.size()
        val num = bag.mat(); val den = bag.mat()
        Imgproc.resize(hf, num, gs, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(pf, den, gs, 0.0, 0.0, Imgproc.INTER_AREA)
        // Suavizado (bloques de ~3 % del lado) y cociente con soporte suficiente
        val sig = max(1.0, max(gs.width, gs.height) * 0.02)
        Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), sig); Imgproc.GaussianBlur(den, den, Size(0.0, 0.0), sig)
        val ok = bag.mat(); Core.compare(den, Scalar(0.05), ok, Core.CMP_GT)
        Core.max(den, Scalar(1e-4), den)
        val noise = bag.mat(); Core.divide(num, den, noise)
        // Referencia: ruido de la zona bien iluminada (ganancia <= 1.1 respecto del papel más claro)
        val gmin = max(1.0, Core.minMaxLoc(gain).minVal)
        if (gmax < 1.15 * gmin) return gain.clone()
        val litM = bag.mat(); Core.compare(gain, Scalar(1.1 * gmin), litM, Core.CMP_LE); Core.bitwise_and(litM, ok, litM)
        if (Core.countNonZero(litM) < 0.05 * gain.total()) return gain.clone()
        val nb = FloatArray(gain.total().toInt()); noise.get(0, 0, nb)
        val lb = ByteArray(nb.size); litM.get(0, 0, lb)
        val litVals = nb.indices.filter { lb[it] != 0.toByte() }.map { nb[it] }.sorted()
        val ref = max(0.3, litVals[litVals.size / 2].toDouble())
        // factor medido = ruido / ruido de la zona iluminada, acotado [1, ganancia^exp]; sin soporte -> ganancia^exp
        // (la zona iluminada conserva su factor de antes: ganancia mínima^exp)
        val f = bag.mat(); Core.multiply(noise, Scalar(Math.pow(gmin, exp) / ref), f)
        val gp = bag.mat(); Core.pow(gain, exp, gp)
        gp.copyTo(f, notMask(ok, bag))
        Core.min(f, gp, f); Core.max(f, Scalar(1.0), f)
        Imgproc.GaussianBlur(f, f, Size(0.0, 0.0), sig)
        val out = Mat(); Core.pow(f, 1.0 / exp, out)
        ShadowDebug.dump?.let { d -> d("noise_meas", bag.mat().also { f.convertTo(it, CvType.CV_8U, 50.0) }); d("noise_gainp", bag.mat().also { gp.convertTo(it, CvType.CV_8U, 50.0) }) }
        ShadowDebug.log?.invoke("ruido efectivo: ref=%.2f max ganancia^e=%.2f max medido=%.2f".format(ref, Core.minMaxLoc(gp).maxVal, Core.minMaxLoc(f).maxVal))
        out
    }

    private fun notMask(m: Mat, bag: MatBag): Mat { val r = bag.mat(); Core.bitwise_not(m, r); return r }

    private const val WARM_GAMMA = ShadowMath.WARM_GAMMA
    private const val MAX_CHROMA_GAIN = ShadowMath.MAX_CHROMA_GAIN

    /**
     * Tratamiento POR ZONA de la imagen normalizada [n] (in-place, RGB 8UC3 o gris 8UC1; son las filas [y0].. de una
     * imagen de [fullH] filas; papel ~[Cv.PAPER_LEVEL]). Sólo dentro de la sombra (mezcla por su fuerza; fuera no cambia):
     *  - [denoise]: el ruido del papel crece con la ganancia de la división y el render lo convertía en grano. Ruido
     *    medido en el papel de la zona (|Y - Gauss(1)| lejos de la tinta, fuerza > 0.6; con [sigmaMemo] se mide una sola
     *    vez para todas las franjas) -> filtro guiado auto-guiado de la luminancia con eps = (2.2·σ)² (alisa el grano,
     *    conserva el borde de las letras) y suavizado leve del color.
     *  - [chroma]: la luz de una sombra es la del cielo y los rebotes, azulada y pobre en rojo/verde, y el ISP del móvil
     *    desatura lo oscuro -> tras normalizar, la tinta CÁLIDA (amarillo, naranja, rojo, marrón) sale apagada en la
     *    sombra (hoja de colores del usuario: amarillez de la estrella 56-60 en luz, 22-35 en sombra); la fría (azul,
     *    morado) no. El croma (Cr, Cb) de los píxeles cálidos (Cb < 128) se multiplica por k = luz^-[WARM_GAMMA]
     *    (acotado a [MAX_CHROMA_GAIN]), sólo con color claro (|croma| > ~6: el ruido de color del papel no se refuerza).
     * Una sola conversión YCrCb, sólo en el rectángulo de la franja con sombra y en coma flotante de un canal.
     */
    /** Ruido mínimo del papel en la sombra (σ a resolución completa, niveles normalizados) para des-ruidar la zona. */
    private const val MIN_ZONE_SIGMA = 3.0

    /** ¿Hay tinta cálida (Cb < 122) en la zona de sombra ([s] 8U: fuerza > 0.3)? Mirado a 1/4 de resolución (barato). */
    private fun hasWarmInk(ycc: Mat, s: Mat, bag: MatBag): Boolean {
        val q = Size(max(1.0, ycc.cols() / 4.0), max(1.0, ycc.rows() / 4.0))
        val c = bag.mat(); Core.extractChannel(ycc, c, 2)
        val cq = bag.mat(); Imgproc.resize(c, cq, q, 0.0, 0.0, Imgproc.INTER_AREA)
        val sq = bag.mat(); Imgproc.resize(s, sq, q, 0.0, 0.0, Imgproc.INTER_AREA)
        val m = bag.mat(); Core.compare(cq, Scalar(122.0), m, Core.CMP_LT)
        val z = bag.mat(); Core.compare(sq, Scalar(77.0), z, Core.CMP_GT); Core.bitwise_and(m, z, m)
        return Core.countNonZero(m) >= max(20.0, 0.0005 * q.width * q.height)
    }

    fun treatZone(n: Mat, map: ShadowMap, y0: Int = 0, fullH: Int = n.rows(), denoise: Boolean = true, chroma: Boolean = true, sigmaMemo: DoubleArray? = null) {
        if (n.empty() || (!denoise && !chroma)) return
        val color = n.channels() == 3
        if (!denoise && !color) return
        if (denoise && !chroma && sigmaMemo != null && sigmaMemo[0] < 0) return
        MatBag().use { bag ->
            val sAll = field8Rows(map.strength, 255.0, n.cols(), n.rows(), y0, fullH, bag)
            // Rectángulo con sombra (fuerza > ~0.02)
            val nz = bag.mat(); Core.compare(sAll, Scalar(5.0), nz, Core.CMP_GT)
            val box = Imgproc.boundingRect(nz)
            if (box.width <= 0 || box.height <= 0) return
            val s = sAll.submat(box).also { bag.add(it) }
            val roi = n.submat(box).also { bag.add(it) }
            val ycc = bag.mat(); val yy = bag.mat()
            if (color) { Imgproc.cvtColor(roi, ycc, Imgproc.COLOR_RGB2YCrCb); Core.extractChannel(ycc, yy, 0) } else roi.copyTo(yy)
            val sInv = bag.mat(); Core.bitwise_not(s, sInv)
            val t1 = bag.mat(); val t2 = bag.mat()
            var changed = false
            if (denoise && !(sigmaMemo != null && sigmaMemo[0] < 0)) run {
                val sigma = if (sigmaMemo != null && sigmaMemo[0] > 0) sigmaMemo[0] else {
                    val bl = bag.mat(); Imgproc.GaussianBlur(yy, bl, Size(0.0, 0.0), 1.0)
                    val hp = bag.mat(); Core.absdiff(yy, bl, hp)
                    val zone = bag.mat(); Core.compare(s, Scalar(153.0), zone, Core.CMP_GT)
                    val pap = bag.mat(); Core.compare(yy, Scalar(0.85 * Cv.PAPER_LEVEL), pap, Core.CMP_GE)
                    Imgproc.erode(pap, pap, Cv.kernel(Imgproc.MORPH_RECT, 5))
                    Core.bitwise_and(zone, pap, zone)
                    if (Core.countNonZero(zone) < 2000) return@run
                    ShadowMath.noiseSigma(Core.mean(hp, zone).`val`[0]).also { sigmaMemo?.set(0, if (it < MIN_ZONE_SIGMA) -1.0 else it) }
                }
                // (ruido bajo: el papel de la sombra ya queda limpio con los niveles; no compensa el coste)
                if (sigma < MIN_ZONE_SIGMA) return@run
                val long = max(n.cols(), fullH)
                val r = (long / 1400.0).roundToInt().coerceIn(1, 3)
                val yd = bag.mat(); Cv.guidedSelf(yy, yd, r, (2.2 * sigma).pow(2), if (long > 2000) 2 else 1)
                // y' = (y·(255 - s) + yd·s) / 255  (8 bits)
                Core.multiply(yy, sInv, t1, 1.0 / 255.0); Core.multiply(yd, s, t2, 1.0 / 255.0); Core.add(t1, t2, yy)
                changed = true
                ShadowDebug.log?.invoke("des-ruido sombra filas $y0 σ=%.1f".format(sigma))
            }
            if (!color) { if (changed) yy.copyTo(roi); return@use }
            if (changed) Core.insertChannel(yy, ycc, 0)
            // Croma de la tinta cálida: c' = c + (c - 128)·(k - 1)·peso, k - 1 = (min(MAX, luz^-γ) - 1)·fuerza
            if (chroma && hasWarmInk(ycc, s, bag)) {
                val span = MAX_CHROMA_GAIN - 1.0
                // (k - 1)/span en 8 bits, a baja resolución y luego a la franja
                val kLow = bag.mat(); Core.pow(map.light, -WARM_GAMMA, kLow); Core.min(kLow, Scalar(MAX_CHROMA_GAIN), kLow)
                Core.subtract(kLow, Scalar(1.0), kLow)
                val k8 = field8Rows(kLow, 255.0 / span, n.cols(), n.rows(), y0, fullH, bag).submat(box).also { bag.add(it) }
                val w = bag.mat(); Core.multiply(k8, s, w, 1.0 / 255.0)
                val cr = bag.mat(); val cb = bag.mat()
                Core.extractChannel(ycc, cr, 1); Core.extractChannel(ycc, cb, 2)
                // peso del color: |croma| = max(|Cr - 128|, |Cb - 128|) en rampa 4..10; sólo cálidos (Cb < 128)
                Core.absdiff(cr, Scalar(128.0), t1); Core.absdiff(cb, Scalar(128.0), t2); Core.max(t1, t2, t1)
                Core.LUT(t1, chromaRamp, t1)
                Core.compare(cb, Scalar(128.0), t2, Core.CMP_GE); t1.setTo(Scalar(0.0), t2)
                Core.multiply(w, t1, w, 1.0 / 255.0)
                if (Core.countNonZero(w) > 0) {
                    // ganancia g = w·span/255, acotada para que NINGÚN canal de croma se sature (si sólo se satura Cr, el
                    // rojo vivo giraba a naranja/amarillo): g <= 127/max(|Cr-128|, |Cb-128|) - 1 -> mismo tono
                    val g = bag.mat(); w.convertTo(g, CvType.CV_32F, span / 255.0)
                    val dr = bag.mat(); val db = bag.mat()
                    cr.convertTo(dr, CvType.CV_32F, 1.0, -128.0); cb.convertTo(db, CvType.CV_32F, 1.0, -128.0)
                    val am = bag.mat(); val tb = bag.mat()
                    Core.absdiff(dr, Scalar(0.0), am); Core.absdiff(db, Scalar(0.0), tb); Core.max(am, tb, am); Core.max(am, Scalar(1.0), am)
                    Core.divide(127.0, am, am); Core.subtract(am, Scalar(1.0), am); Core.max(am, Scalar(0.0), am)
                    Core.min(g, am, g); Core.add(g, Scalar(1.0), g)
                    for ((i, pair) in listOf(1 to (dr to cr), 2 to (db to cb))) {
                        val (d, c) = pair
                        Core.multiply(d, g, d)
                        d.convertTo(c, CvType.CV_8U, 1.0, 128.0)
                        Core.insertChannel(c, ycc, i)
                    }
                    changed = true
                }
            }
            if (!changed) return@use
            Imgproc.cvtColor(ycc, roi, Imgproc.COLOR_YCrCb2RGB)
        }
    }

    /** Rampa del peso del color: |croma YCrCb| 4..10 -> 0..255. */
    private val chromaRamp: Mat by lazy { Cv.lut { v -> ShadowMath.chromaWeight(v.toDouble()) * 255.0 } }

    /** Campo [field] (32FC1, baja resolución) x [scale] en 8 bits, remuestreado a las filas [y0].. de una imagen de [fullH] x [w]. */
    private fun field8Rows(field: Mat, scale: Double, w: Int, rows: Int, y0: Int, fullH: Int, bag: MatBag): Mat {
        val sr = field.rows(); val sc = field.cols()
        val a = y0.toDouble() / fullH * sr; val b = (y0 + rows).toDouble() / fullH * sr
        val ia = kotlin.math.floor(a).toInt().coerceIn(0, sr - 1); val ib = kotlin.math.ceil(b).toInt().coerceIn(ia + 1, sr)
        val sub = field.submat(ia, ib, 0, sc)
        val sub8 = bag.mat(); sub.convertTo(sub8, CvType.CV_8U, scale); sub.release()
        val tall = ((ib - ia).toDouble() / sr * fullH).roundToInt().coerceAtLeast(1)
        val sFull = bag.mat(); Imgproc.resize(sub8, sFull, Size(w.toDouble(), tall.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val off = (y0 - ia.toDouble() / sr * fullH).roundToInt().coerceIn(0, max(0, sFull.rows() - 1))
        val take = min(rows, sFull.rows() - off)
        if (take >= rows) return sFull.submat(off, off + rows, 0, w).also { bag.add(it) }
        val out = bag.mat(); out.create(rows, w, CvType.CV_8UC1)
        if (take > 0) { val src = sFull.submat(off, off + take, 0, w); val d = out.submat(0, take, 0, w); src.copyTo(d); src.release(); d.release() }
        val last = sFull.submat(sFull.rows() - 1, sFull.rows(), 0, w)
        for (r in max(0, take) until rows) { val d = out.submat(r, r + 1, 0, w); last.copyTo(d); d.release() }
        last.release()
        return out
    }
}
