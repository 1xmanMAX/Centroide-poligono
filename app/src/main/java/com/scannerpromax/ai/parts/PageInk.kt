package com.scannerpromax.ai.parts

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Imagen RGB en memoria (ARGB por píxel, fila a fila). Lógica pura (JVM): la app la llena desde un Bitmap. */
class Rgb(val w: Int, val h: Int, val px: IntArray) {
    init { require(px.size >= w * h) { "píxeles insuficientes" } }

    /** Luminancia como cv2.COLOR_RGB2GRAY (0,299 R + 0,587 G + 0,114 B, redondeada). */
    fun gray(): IntArray = IntArray(w * h) { i -> lum(px[i]) }

    /** Saturación HSV de OpenCV (0..255): (max − min) / max · 255. */
    fun saturation(): IntArray = IntArray(w * h) { i ->
        val p = px[i]
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx == 0) 0 else ((mx - mn) * 255f / mx).roundToInt()
    }

    /** Valor HSV (max de R, G, B). */
    fun value(): IntArray = IntArray(w * h) { i ->
        val p = px[i]
        max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF))
    }

    /** Reducción por área (como cv2.INTER_AREA) a [nw] x [nh]. */
    fun resizeArea(nw: Int, nh: Int): Rgb {
        if (nw == w && nh == h) return this
        val r = PageInk.resizeAreaChannel(IntArray(w * h) { (px[it] shr 16) and 0xFF }, w, h, nw, nh)
        val g = PageInk.resizeAreaChannel(IntArray(w * h) { (px[it] shr 8) and 0xFF }, w, h, nw, nh)
        val b = PageInk.resizeAreaChannel(IntArray(w * h) { px[it] and 0xFF }, w, h, nw, nh)
        return Rgb(nw, nh, IntArray(nw * nh) { i -> (0xFF shl 24) or (r[i] shl 16) or (g[i] shl 8) or b[i] })
    }

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Rgb {
        val a = x0.coerceIn(0, w); val b = y0.coerceIn(0, h)
        val c = x1.coerceIn(a, w); val d = y1.coerceIn(b, h)
        val cw = max(1, c - a); val ch = max(1, d - b)
        val out = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val sx = min(w - 1, a + x); val sy = min(h - 1, b + y)
            out[y * cw + x] = px[sy * w + sx]
        }
        return Rgb(cw, ch, out)
    }

    companion object {
        fun lum(p: Int): Int {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            return ((r * 299 + g * 587 + b * 114 + 500) / 1000)
        }
    }
}

/** Caja en píxeles (flotante): izquierda, arriba, derecha, abajo. */
typealias Box = FloatArray

/**
 * Análisis de TINTA de la página (puerto fiel de tools/recon2_new.py del banco recon2): rayas de formulario y bordes
 * (morfología), fondos de color lisos, máscara de tinta sin rayas largas, dibujos/trazos sin texto (flechas,
 * estrellas, llaves, firmas) que se reproducen como recorte SOLO-TINTA, zonas con tinta que el detector no cubrió
 * (manuscrito) y color de tinta de una línea. Lógica pura (JVM), sin OpenCV.
 */
object PageInk {

    // ------------------------------------------------------------------------------------------ utilidades

    /** Percentil [q] (0..100) de valores 0..255 con interpolación lineal (como numpy). */
    fun percentile(v: IntArray, q: Double): Double {
        if (v.isEmpty()) return 255.0
        val hist = IntArray(256)
        for (x in v) hist[x.coerceIn(0, 255)]++
        val pos = q / 100.0 * (v.size - 1)
        val lo = kotlin.math.floor(pos).toLong(); val frac = pos - lo
        fun at(k: Long): Int { var acc = 0L; for (i in 0..255) { acc += hist[i]; if (acc > k) return i }; return 255 }
        val a = at(lo); val b = if (frac > 0) at(lo + 1) else a
        return a + (b - a) * frac
    }

    /** Reducción por área de un canal (pesos por solape fraccionario, como cv2.INTER_AREA al reducir). */
    fun resizeAreaChannel(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val sx = w.toDouble() / nw; val sy = h.toDouble() / nh
        // pasada horizontal
        val tmp = DoubleArray(nw * h)
        for (x in 0 until nw) {
            val fx0 = x * sx; val fx1 = (x + 1) * sx
            var ix = kotlin.math.floor(fx0).toInt()
            while (ix < fx1 && ix < w) {
                val wgt = min(fx1, ix + 1.0) - max(fx0, ix.toDouble())
                if (wgt > 0) for (y in 0 until h) tmp[y * nw + x] += src[y * w + ix] * wgt
                ix++
            }
            for (y in 0 until h) tmp[y * nw + x] /= sx
        }
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val fy0 = y * sy; val fy1 = (y + 1) * sy
            val acc = DoubleArray(nw)
            var iy = kotlin.math.floor(fy0).toInt()
            while (iy < fy1 && iy < h) {
                val wgt = min(fy1, iy + 1.0) - max(fy0, iy.toDouble())
                if (wgt > 0) for (x in 0 until nw) acc[x] += tmp[iy * nw + x] * wgt
                iy++
            }
            for (x in 0 until nw) out[y * nw + x] = (acc[x] / sy).roundToInt().coerceIn(0, 255)
        }
        return out
    }

    /** Píxeles que pertenecen a un tramo horizontal (o vertical) de 1 de longitud >= [len] (= apertura con línea). */
    fun longRuns(m: BooleanArray, w: Int, h: Int, len: Int, horizontal: Boolean): BooleanArray {
        val out = BooleanArray(w * h)
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            var start = -1
            for (k in 0..inner) {
                val on = k < inner && m[if (horizontal) o * w + k else k * w + o]
                if (on) { if (start < 0) start = k } else if (start >= 0) {
                    if (k - start >= len) for (t in start until k) out[if (horizontal) o * w + t else t * w + o] = true
                    start = -1
                }
            }
        }
        return out
    }

    /** Dilatación rectangular [kw] x [kh] (centrada; lo de fuera de la imagen no cuenta). */
    fun dilateRect(m: BooleanArray, w: Int, h: Int, kw: Int, kh: Int): BooleanArray = minMaxRect(m, w, h, kw, kh, dilate = true)

    fun erodeRect(m: BooleanArray, w: Int, h: Int, kw: Int, kh: Int): BooleanArray = minMaxRect(m, w, h, kw, kh, dilate = false)

    private fun minMaxRect(m: BooleanArray, w: Int, h: Int, kw: Int, kh: Int, dilate: Boolean): BooleanArray {
        val ax = kw / 2; val ay = kh / 2
        // horizontal con sumas acumuladas por fila
        val t = BooleanArray(w * h)
        val pre = IntArray(w + 1)
        for (y in 0 until h) {
            for (x in 0 until w) pre[x + 1] = pre[x] + if (m[y * w + x]) 1 else 0
            for (x in 0 until w) {
                val a = max(0, x - ax); val b = min(w - 1, x - ax + kw - 1)
                val n = pre[b + 1] - pre[a]
                t[y * w + x] = if (dilate) n > 0 else n == b - a + 1
            }
        }
        val out = BooleanArray(w * h)
        val pc = IntArray(h + 1)
        for (x in 0 until w) {
            for (y in 0 until h) pc[y + 1] = pc[y] + if (t[y * w + x]) 1 else 0
            for (y in 0 until h) {
                val a = max(0, y - ay); val b = min(h - 1, y - ay + kh - 1)
                val n = pc[b + 1] - pc[a]
                out[y * w + x] = if (dilate) n > 0 else n == b - a + 1
            }
        }
        return out
    }

    /** Dilatación con un disco de diámetro [k] (≈ elipse de OpenCV k x k). */
    fun dilateDisk(m: BooleanArray, w: Int, h: Int, k: Int): BooleanArray {
        val r = (k - 1) / 2.0
        val out = BooleanArray(w * h)
        val pre = IntArray(w + 1)
        val half = IntArray(2 * k + 1)
        val ry = k / 2
        for (dy in -ry..ry) half[dy + ry] = if (r <= 0) 0 else sqrt(max(0.0, r * r - dy.toDouble() * dy)).roundToInt()
        for (y in 0 until h) {
            for (x in 0 until w) pre[x + 1] = pre[x] + if (m[y * w + x]) 1 else 0
            if (pre[w] == 0) continue
            for (dy in -ry..ry) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                val hw = half[dy + ry]
                for (x in 0 until w) {
                    if (out[yy * w + x]) continue
                    val a = max(0, x - hw); val b = min(w - 1, x + hw)
                    if (pre[b + 1] - pre[a] > 0) out[yy * w + x] = true
                }
            }
        }
        return out
    }

    /** Componente conexa (8-vecinos): caja [x, y, ancho, alto] y área. */
    class Comp(val label: Int, val x: Int, val y: Int, val w: Int, val h: Int, val area: Int)

    /** Etiquetado 8-conexo: (etiquetas 1..n por píxel, 0 = fondo; componentes). */
    fun components(m: BooleanArray, w: Int, h: Int): Pair<IntArray, List<Comp>> {
        val lab = IntArray(w * h)
        val out = ArrayList<Comp>()
        val stack = IntArray(w * h)
        var n = 0
        for (s in 0 until w * h) {
            if (!m[s] || lab[s] != 0) continue
            n++
            var sp = 0; stack[sp++] = s; lab[s] = n
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var area = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                area++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val q = yy * w + xx
                    if (m[q] && lab[q] == 0) { lab[q] = n; stack[sp++] = q }
                }
            }
            out += Comp(n, x0, y0, x1 - x0 + 1, y1 - y0 + 1, area)
        }
        return lab to out
    }

    /** Fracción del área de [a] dentro de [b] (cajas x0,y0,x1,y1). */
    fun insideFrac(a: Box, b: Box): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        return ix * iy / max(1e-6f, (a[2] - a[0]) * (a[3] - a[1]))
    }

    // ------------------------------------------------------------------------------------------ rayas y fondos

    /**
     * Rayas largas horizontales/verticales oscuras (líneas de formulario, recuadros, subrayados, bordes de tabla):
     * [x0, y0, x1, y1, grosor] en px. Las que caen (> 50 %) en [exclude] (figuras, gráficos, fórmulas) se ignoran.
     */
    fun detectRules(img: Rgb, exclude: List<Box>, minFrac: Float = 0.12f): List<FloatArray> {
        val w = img.w; val h = img.h
        val gray = img.gray()
        val paper = percentile(gray, 90.0)
        val thr = min(150.0, paper - 70)
        val ink = BooleanArray(w * h) { gray[it] < thr }
        val out = ArrayList<FloatArray>()
        for (horiz in listOf(true, false)) {
            val len = max(25, ((if (horiz) w else h) * minFrac).toInt())
            val m = longRuns(ink, w, h, len, horiz)
            val (_, comps) = components(m, w, h)
            for (c in comps) {
                val th = if (horiz) c.h else c.w
                if (th > 8) continue  // macizo (no es una raya)
                val seg = if (horiz) floatArrayOf(c.x.toFloat(), c.y + c.h / 2f, (c.x + c.w).toFloat(), c.y + c.h / 2f, max(1, th).toFloat())
                else floatArrayOf(c.x + c.w / 2f, c.y.toFloat(), c.x + c.w / 2f, (c.y + c.h).toFloat(), max(1, th).toFloat())
                val bb = floatArrayOf(c.x.toFloat(), c.y.toFloat(), (c.x + c.w).toFloat(), (c.y + c.h).toFloat())
                if (exclude.any { insideFrac(bb, it) > 0.5f }) continue
                // (app) rayas pegadas al canto de la foto (borde del móvil/mesa, sombra del papel): no son del documento
                val edge = 0.02f * max(w, h)
                if (if (horiz) (seg[1] < edge || seg[1] > h - edge) else (seg[0] < edge || seg[0] > w - edge)) continue
                out += seg
            }
        }
        return out
    }

    /** Fondo de color liso: caja (px) y color ARGB. */
    class Fill(val box: Box, val argb: Int)

    /** Fondos de color lisos (bandas de tabla, recuadros sombreados): zonas claras con tono, rectangulares. */
    fun detectFills(img: Rgb, minFrac: Float = 0.002f): List<Fill> {
        val s = 500.0 / max(img.w, img.h)
        val sw = max(1, (img.w * s).roundToInt()); val sh = max(1, (img.h * s).roundToInt())
        val sm = img.resizeArea(sw, sh)
        val sat = sm.saturation(); val v = sm.value()
        var m = BooleanArray(sw * sh) { sat[it] > 22 && v[it] > 150 }
        m = erodeRect(dilateRect(m, sw, sh, 5, 3), sw, sh, 5, 3)   // cierre 5x3
        m = dilateRect(erodeRect(m, sw, sh, 9, 3), sw, sh, 9, 3)   // apertura 9x3
        val (lab, comps) = components(m, sw, sh)
        val out = ArrayList<Fill>()
        for (c in comps) {
            if (c.w * c.h < minFrac * sw * sh || c.area < 0.8f * c.w * c.h) continue
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (y in c.y until c.y + c.h) for (x in c.x until c.x + c.w) {
                val i = y * sw + x
                if (lab[i] != c.label) continue
                val p = sm.px[i]
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
            }
            fun med(l: ArrayList<Int>): Int { l.sort(); val n = l.size; return if (n % 2 == 1) l[n / 2] else (l[n / 2 - 1] + l[n / 2]) / 2 }
            val argb = (0xFF shl 24) or (med(rs) shl 16) or (med(gs) shl 8) or med(bs)
            out += Fill(floatArrayOf((c.x / s).toFloat(), (c.y / s).toFloat(), ((c.x + c.w) / s).toFloat(), ((c.y + c.h) / s).toFloat()), argb)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ tinta

    /**
     * Máscara de tinta de la página SIN rayas largas (cuadrícula, renglones, reglado: van aparte). [lineLen] = longitud
     * a partir de la cual un tramo recto se toma por raya (por defecto ancho/25, mín. 25 px).
     */
    fun inkMask(img: Rgb, lineLen: Int = max(25, img.w / 25), paperLevel: Double? = null): BooleanArray {
        val w = img.w; val h = img.h
        val g = img.gray(); val sat = img.saturation()
        val paper = paperLevel ?: percentile(g, 90.0)
        val ink = BooleanArray(w * h) { g[it] < paper - 70 || (sat[it] > 90 && g[it] < paper - 35) }
        val hr = longRuns(ink, w, h, lineLen, true)
        val vr = longRuns(ink, w, h, lineLen, false)
        for (i in ink.indices) if (hr[i] || vr[i]) ink[i] = false
        return ink
    }

    /**
     * Trazos que no son texto ni regiones (dibujos, flechas, estrellas, llaves, firmas sueltas) → cajas para recortes
     * SOLO-TINTA. [lineBoxes] = líneas de texto (se borran con un margen del 15 % de su alto); [taken] = regiones ya
     * dibujadas (figuras, tablas...). Se descartan fondos/mesas pegados al canto (> 1 % del área) y texturas (tela,
     * madera: > 6 % del área con > 20 % de tinta).
     */
    fun drawings(img: Rgb, lineBoxes: List<Box>, taken: List<Box>, minSide: Float = 0.012f, ink0: BooleanArray? = null): List<Box> {
        val w = img.w; val h = img.h
        val ink = ink0 ?: inkMask(img)
        val free = ink.copyOf()
        fun clear(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) free[y * w + x] = false
        }
        for (b in lineBoxes) {
            val ph = 0.15f * (b[3] - b[1])
            clear(max(0f, b[0] - ph).toInt(), max(0f, b[1] - ph).toInt(), (b[2] + ph).toInt() + 1, (b[3] + ph).toInt() + 1)
        }
        for (b in taken) clear(b[0].toInt() - 2, b[1].toInt() - 2, b[2].toInt() + 3, b[3].toInt() + 3)
        val k = max(3, (0.012f * max(h, w)).toInt())
        val d = dilateDisk(free, w, h, k)
        val (lab, comps) = components(d, w, h)
        val out = ArrayList<Box>()
        val big = max(h, w)
        for (c in comps) {
            if (max(c.w, c.h) < minSide * big) continue
            var inkn = 0
            for (y in c.y until c.y + c.h) for (x in c.x until c.x + c.w) {
                val i = y * w + x
                if (lab[i] == c.label && free[i]) inkn++
            }
            if (inkn < 60) continue
            val area = c.w.toFloat() * c.h / (w.toFloat() * h)
            val m = 0.01f * big
            val border = (if (c.x <= m) 1 else 0) + (if (c.y <= m) 1 else 0) + (if (c.x + c.w >= w - m) 1 else 0) + (if (c.y + c.h >= h - m) 1 else 0)
            if (border > 0 && area > 0.01f) continue            // fondo/mesa/borde de la foto pegado al canto
            // (app) trozos de madera/tela pegados al canto: densos o con color de fondo, no trazos de bolígrafo
            if (border > 0 && (inkn > 0.12f * c.w * c.h || area > 0.002f)) continue
            if (area > 0.06f && inkn > 0.2f * c.w * c.h) continue // textura (tela, madera), no trazos
            out += floatArrayOf(c.x.toFloat(), c.y.toFloat(), (c.x + c.w).toFloat(), (c.y + c.h).toFloat())
        }
        return out
    }

    /**
     * Recorte SOLO-TINTA: los píxeles que no son tinta (dilatada 3x3) pasan a blanco. [px] se modifica in situ.
     * [lineLen] y [paperLevel] = los de la página (no los del recorte) para que la decisión sea la misma.
     */
    fun inkOnly(px: IntArray, w: Int, h: Int, lineLen: Int, paperLevel: Double? = null) {
        val img = Rgb(w, h, px)
        val keep = dilateRect(inkMask(img, lineLen, paperLevel), w, h, 3, 3)
        for (i in 0 until w * h) if (!keep[i]) px[i] = -1
    }

    /**
     * Zonas con tinta fuera de las regiones [taken] (manuscrito que el detector no ve): bandas horizontales (las muy
     * altas, > 30 % de la página, se trocean para que el Spotting no haga bucle).
     */
    fun uncoveredZones(img: Rgb, taken: List<Box>, minArea: Float = 0.004f): List<Box> {
        val h = img.h; val w = img.w
        val s = 600.0 / max(h, w)
        val sw = max(1, (w * s).roundToInt()); val sh = max(1, (h * s).roundToInt())
        val small = img.resizeArea(sw, sh)
        val g = small.gray(); val sat = small.saturation()
        val paper = percentile(g, 90.0)
        val ink = BooleanArray(sw * sh) { g[it] < paper - 80 || (sat[it] > 90 && g[it] < paper - 40) }
        val hr = longRuns(ink, sw, sh, 25, true); val vr = longRuns(ink, sw, sh, 25, false)
        for (i in ink.indices) if (hr[i] || vr[i]) ink[i] = false
        for (t in taken) {
            val x0 = (t[0] * s).toInt(); val y0 = (t[1] * s).toInt(); val x1 = (t[2] * s).toInt(); val y1 = (t[3] * s).toInt()
            for (y in max(0, y0 - 2) until min(sh, y1 + 2)) for (x in max(0, x0 - 2) until min(sw, x1 + 2)) ink[y * sw + x] = false
        }
        val dil = dilateRect(ink, sw, sh, 15, 5)
        val (_, comps) = components(dil, sw, sh)
        val zones = ArrayList<Box>()
        for (c in comps) {
            if (c.w * c.h < minArea * sw * sh || c.h < 6) continue
            zones += floatArrayOf((c.x / s).toFloat(), (c.y / s).toFloat(), ((c.x + c.w) / s).toFloat(), ((c.y + c.h) / s).toFloat())
        }
        zones.sortWith(compareBy({ it[1] }, { it[0] }))
        val merged = ArrayList<Box>()
        for (b in zones) {
            val m = merged.lastOrNull()
            if (m != null && b[1] - m[3] < 0.02f * h && min(b[2], m[2]) - max(b[0], m[0]) > -0.05f * w) {
                merged[merged.size - 1] = floatArrayOf(min(m[0], b[0]), min(m[1], b[1]), max(m[2], b[2]), max(m[3], b[3]))
            } else merged += b
        }
        val res = ArrayList<Box>()
        for (b in merged) {
            val n = max(1, kotlin.math.ceil((b[3] - b[1]) / (0.3f * h)).toInt())
            for (k in 0 until n) res += floatArrayOf(b[0], b[1] + k * (b[3] - b[1]) / n, b[2], b[1] + (k + 1) * (b[3] - b[1]) / n)
        }
        return res
    }

    /**
     * Color de tinta de una línea (cuadrilátero x0,y0,…,x3,y3 en px): media del 4 % más oscuro, aclarada por el borde
     * suavizado (·0,85). null = negro/gris oscuro (sin color).
     */
    fun inkColor(img: Rgb, quad: FloatArray): Int? {
        val xs = floatArrayOf(quad[0], quad[2], quad[4], quad[6]); val ys = floatArrayOf(quad[1], quad[3], quad[5], quad[7])
        val x0 = max(0, xs.min().toInt()); val x1 = min(img.w - 1, xs.max().toInt())
        val y0 = max(0, ys.min().toInt()); val y1 = min(img.h - 1, ys.max().toInt())
        if (x1 < x0 || y1 < y0) return null
        val n = (x1 - x0 + 1) * (y1 - y0 + 1)
        val sums = IntArray(n); val idx = IntArray(n)
        var k = 0
        for (y in y0..y1) for (x in x0..x1) {
            val p = img.px[y * img.w + x]
            sums[k] = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF); idx[k] = y * img.w + x; k++
        }
        val take = max(1, n / 25)
        val order = (0 until n).sortedBy { sums[it] }.take(take)
        var r = 0.0; var g = 0.0; var b = 0.0
        for (o in order) { val p = img.px[idx[o]]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
        val ri = (r / take * 0.85).toInt(); val gi = (g / take * 0.85).toInt(); val bi = (b / take * 0.85).toInt()
        if (max(ri, max(gi, bi)) - min(ri, min(gi, bi)) < 28 && max(ri, max(gi, bi)) < 110) return null
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
