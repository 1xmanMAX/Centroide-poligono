package com.scannerpromax.imaging

import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.PhotoZone
import com.scannerpromax.domain.PhotoZones
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * «Imágenes de la página» a mano ([PageEdits.photoZones]): lógica pura (probada en la JVM, sin OpenCV).
 *
 * Marco de las coordenadas: NORMALIZADO 0..1 sobre el procesado FINAL (lo que ve el usuario, con el giro aplicado), como
 * los trazos de borrado. La tubería trabaja en el marco de la geometría ([PageRotation.geoRotation]): [toGeo] lleva las
 * zonas a él. Al girar la página se giran con ella ([rotate]); al cambiar el recorte se llevan al recorte nuevo por las
 * homografías de los dos cuadriláteros ([remapQuad]); al cambiar el enderezado (unos grados) se conservan tal cual.
 *
 * Resolución ([resolve]): las cajas AUTOMÁTICAS de [PhotoBoxRefine] (mismo marco) menos las descartadas, ninguna con
 * «Aquí no hay imágenes», más las zonas manuales de foto; las zonas manuales «como el resto» se quedan con la capa base
 * aunque caigan dentro de una foto (y una que tapa casi toda una caja automática la quita).
 */
object PhotoZoneMath {

    /** Lo que se pinta: fotos (filtro de foto natural) y zonas que se quedan con la capa base. Marco de [auto]. */
    class Resolved(val photos: List<PhotoZone>, val keep: List<PhotoZone>) {
        val isEmpty: Boolean get() = photos.isEmpty() && keep.isEmpty()
    }

    /** Mínimo lado de una zona (fracción): un toque sin arrastrar no crea nada. */
    const val MIN_SIDE = 0.02f

    fun zone(b: FloatArray): PhotoZone = PhotoZone(b[0], b[1], b[2], b[3])
    fun box(z: PhotoZone): FloatArray = floatArrayOf(z.x0, z.y0, z.x1, z.y1)

    /** null si no queda nada manual (así [PageEdits.photoZones] vuelve a null y el hash a lo de siempre). */
    fun normalized(z: PhotoZones?): PhotoZones? = z?.takeIf { !it.isEmpty }

    private fun area(z: PhotoZone) = max(0f, z.x1 - z.x0).toDouble() * max(0f, z.y1 - z.y0)

    private fun inter(a: PhotoZone, b: PhotoZone): Double {
        val iw = min(a.x1, b.x1) - max(a.x0, b.x0); val ih = min(a.y1, b.y1) - max(a.y0, b.y0)
        return if (iw <= 0 || ih <= 0) 0.0 else iw.toDouble() * ih
    }

    /** ¿La misma caja? IoU ≥ 0,5 o una dentro de la otra en ≥ 80 % (la detección se mueve unos píxeles entre renders). */
    fun same(a: PhotoZone, b: PhotoZone): Boolean {
        val i = inter(a, b); if (i <= 0) return false
        val aa = area(a); val ab = area(b)
        return i / (aa + ab - i) >= 0.5 || i >= 0.8 * min(aa, ab)
    }

    /** Fracción de [a] tapada por [b]. */
    fun covered(a: PhotoZone, b: PhotoZone): Double = inter(a, b) / max(1e-12, area(a))

    /** ¿Está [p] dentro de la zona? (recuadro y, si lo tiene, contorno libre). */
    fun contains(z: PhotoZone, p: Pt): Boolean {
        if (p.x < z.x0 || p.x > z.x1 || p.y < z.y0 || p.y > z.y1) return false
        val poly = z.poly ?: return true
        if (poly.size < 3) return true
        var c = false; var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    /** Cajas automáticas que se aplican (sin las descartadas ni las tapadas por una zona «como el resto»). */
    fun activeAuto(auto: List<FloatArray>, z: PhotoZones?): List<PhotoZone> {
        if (z?.none == true) return emptyList()
        val keep = z?.manual.orEmpty().filter { it.normal == true }
        val manualPhotos = z?.manual.orEmpty().filter { it.normal != true }
        return auto.map(::zone).filter { a ->
            z?.excluded.orEmpty().none { same(it, a) } && keep.none { covered(a, it) >= 0.9 } &&
                // (una zona manual que es la misma foto ajustada a mano la sustituye)
                manualPhotos.none { same(it, a) }
        }
    }

    fun resolve(auto: List<FloatArray>, z: PhotoZones?): Resolved {
        val manual = z?.manual.orEmpty().filter { it.width >= MIN_SIDE * 0.5f && it.height >= MIN_SIDE * 0.5f }
        return Resolved(activeAuto(auto, z) + manual.filter { it.normal != true }, manual.filter { it.normal == true })
    }

    // ------------------------------------------------------------------------------------------- ediciones del usuario

    /** «No es una imagen» sobre la caja automática [a]. */
    fun excludeAuto(z: PhotoZones?, a: PhotoZone): PhotoZones? {
        val cur = z ?: PhotoZones()
        if (cur.excluded.any { same(it, a) }) return normalized(cur)
        return normalized(cur.copy(excluded = cur.excluded + a.copy(poly = null, normal = null)))
    }

    /** Vuelve a aceptar la caja automática [a]. */
    fun restoreAuto(z: PhotoZones?, a: PhotoZone): PhotoZones? =
        z?.let { normalized(it.copy(excluded = it.excluded.filterNot { e -> same(e, a) })) }

    /** Caja automática [a] ajustada a mano a [to]: se descarta la automática y queda la manual. */
    fun adjustAuto(z: PhotoZones?, a: PhotoZone, to: PhotoZone): PhotoZones? =
        addManual(excludeAuto(z, a), to)

    fun addManual(z: PhotoZones?, m: PhotoZone): PhotoZones? {
        val cur = z ?: PhotoZones()
        val c = clamp(m)
        if (c.width < MIN_SIDE || c.height < MIN_SIDE) return normalized(cur)
        return normalized(cur.copy(manual = cur.manual + c))
    }

    fun replaceManual(z: PhotoZones?, index: Int, m: PhotoZone): PhotoZones? {
        val cur = z ?: return null
        if (index !in cur.manual.indices) return normalized(cur)
        return normalized(cur.copy(manual = cur.manual.toMutableList().also { it[index] = clamp(m) }))
    }

    fun removeManual(z: PhotoZones?, index: Int): PhotoZones? {
        val cur = z ?: return null
        if (index !in cur.manual.indices) return normalized(cur)
        return normalized(cur.copy(manual = cur.manual.filterIndexed { i, _ -> i != index }))
    }

    /** «Aquí no hay imágenes»: ninguna automática y ninguna manual. */
    fun noPhotos(): PhotoZones = PhotoZones(none = true)

    /** Recuadro dentro de 0..1, ordenado; el contorno libre, recortado al recuadro. */
    fun clamp(m: PhotoZone): PhotoZone {
        val x0 = min(m.x0, m.x1).coerceIn(0f, 1f); val x1 = max(m.x0, m.x1).coerceIn(0f, 1f)
        val y0 = min(m.y0, m.y1).coerceIn(0f, 1f); val y1 = max(m.y0, m.y1).coerceIn(0f, 1f)
        return m.copy(x0 = x0, y0 = y0, x1 = x1, y1 = y1, poly = m.poly?.map { Pt(it.x.coerceIn(x0, x1), it.y.coerceIn(y0, y1)) })
    }

    /** Zona de un lazo (puntos normalizados): su recuadro y el contorno; null si es demasiado pequeño. */
    fun lasso(points: List<Pt>, normal: Boolean? = null): PhotoZone? {
        if (points.size < 3) return null
        val z = clamp(PhotoZone(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y }, simplify(points), normal))
        return z.takeIf { it.width >= MIN_SIDE && it.height >= MIN_SIDE }
    }

    /** Menos puntos (como mucho ~64): el JSON de las ediciones no crece con cada píxel del dedo. */
    fun simplify(points: List<Pt>, max: Int = 64): List<Pt> {
        if (points.size <= max) return points
        val step = points.size.toDouble() / max
        return List(max) { i -> points[(i * step).toInt().coerceAtMost(points.size - 1)] }
    }

    // ------------------------------------------------------------------------------------------- marcos

    fun rotatePt(p: Pt, q: Int): Pt = PageRotation.rotatePt(p, q)

    fun rotateZone(z: PhotoZone, q: Int): PhotoZone {
        val b = PageRotation.rotateBoxNorm(box(z), q)
        return z.copy(x0 = b[0], y0 = b[1], x1 = b[2], y1 = b[3], poly = z.poly?.map { rotatePt(it, q) })
    }

    /** Zonas giradas [q] cuartos de vuelta en sentido horario (al girar la página). */
    fun rotate(z: PhotoZones?, q: Int): PhotoZones? {
        val k = ((q % 4) + 4) % 4
        if (z == null || k == 0) return z
        return z.copy(manual = z.manual.map { rotateZone(it, k) }, excluded = z.excluded.map { rotateZone(it, k) })
    }

    /** ¿Las mismas zonas? (con tolerancia: cuatro giros acumulan redondeos de float). */
    fun sameZones(a: PhotoZones?, b: PhotoZones?): Boolean {
        val x = normalized(a); val y = normalized(b)
        if (x == null || y == null) return x == null && y == null
        if (x.none != y.none || x.manual.size != y.manual.size || x.excluded.size != y.excluded.size) return false
        fun eq(p: PhotoZone, q: PhotoZone) = p.normal == q.normal && abs(p.x0 - q.x0) <= 2e-3f && abs(p.y0 - q.y0) <= 2e-3f &&
            abs(p.x1 - q.x1) <= 2e-3f && abs(p.y1 - q.y1) <= 2e-3f && (p.poly?.size ?: 0) == (q.poly?.size ?: 0)
        return x.manual.indices.all { eq(x.manual[it], y.manual[it]) } && x.excluded.indices.all { eq(x.excluded[it], y.excluded[it]) }
    }

    /** Del marco FINAL al de la geometría (giro inverso del giro final [delta]). */
    fun toGeo(z: PhotoZones?, delta: Int): PhotoZones? = rotate(z, (4 - PageRotation.norm(delta) / 90) % 4)

    /** Cajas normalizadas (x0, y0, x1, y1) del marco de la geometría al FINAL. */
    fun boxesToFinal(boxes: List<FloatArray>, delta: Int): List<FloatArray> {
        val q = PageRotation.norm(delta) / 90
        return if (q == 0) boxes else boxes.map { PageRotation.rotateBoxNorm(it, q) }
    }

    /**
     * Zonas de [from] llevadas a las ediciones [to] cuando cambia el RECORTE (o el giro de la geometría): marco final de
     * [from] -> sin el giro -> unidad del recorte -> píxeles del original (homografía del cuadrilátero) -> unidad del
     * recorte nuevo -> con el giro de [to]. [w] x [h] = original (para el recorte nulo = foto entera). Lo que cae fuera del
     * recorte nuevo se recorta; una zona que queda casi sin área se quita. El enderezado fino y la hoja curvada no se
     * modelan (unos píxeles).
     */
    fun remapQuad(z: PhotoZones?, from: PageEdits, to: PageEdits, w: Int, h: Int): PhotoZones? {
        if (z == null) return null
        if (from.quad == to.quad && PageRotation.norm(from.rotation) == PageRotation.norm(to.rotation)) return z
        val qa = from.quad ?: Quad.full(w, h); val qb = to.quad ?: Quad.full(w, h)
        val ha = unitToQuad(qa) ?: return null
        val hb = unitToQuad(qb)?.let { invert(it) } ?: return null
        val ra = PageRotation.norm(from.rotation) / 90; val rb = PageRotation.norm(to.rotation) / 90
        fun map(p: Pt): Pt {
            val u = rotatePt(p, (4 - ra) % 4)
            val img = apply(ha, u.x.toDouble(), u.y.toDouble())
            val v = apply(hb, img[0], img[1])
            return rotatePt(Pt(v[0].toFloat(), v[1].toFloat()), rb)
        }
        fun mapZone(m: PhotoZone): PhotoZone? {
            // las 4 esquinas (la perspectiva no conserva los rectángulos: su recuadro)
            val c = listOf(Pt(m.x0, m.y0), Pt(m.x1, m.y0), Pt(m.x1, m.y1), Pt(m.x0, m.y1)).map(::map)
            val poly = m.poly?.map(::map)
            val r = clamp(PhotoZone(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y }, poly, m.normal))
            return r.takeIf { it.width >= MIN_SIDE * 0.5f && it.height >= MIN_SIDE * 0.5f }
        }
        return normalized(z.copy(manual = z.manual.mapNotNull(::mapZone), excluded = z.excluded.mapNotNull(::mapZone)))
    }

    /** Homografía (3x3 por filas) que lleva el cuadrado unidad (0,0),(1,0),(1,1),(0,1) a tl, tr, br, bl de [q]. */
    fun unitToQuad(q: Quad): DoubleArray? {
        val x0 = q.tl.x.toDouble(); val y0 = q.tl.y.toDouble(); val x1 = q.tr.x.toDouble(); val y1 = q.tr.y.toDouble()
        val x2 = q.br.x.toDouble(); val y2 = q.br.y.toDouble(); val x3 = q.bl.x.toDouble(); val y3 = q.bl.y.toDouble()
        val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
        val sx = x0 - x1 + x2 - x3; val sy = y0 - y1 + y2 - y3
        val den = dx1 * dy2 - dx2 * dy1
        if (abs(den) < 1e-12) return null
        val g = (sx * dy2 - dx2 * sy) / den; val hh = (dx1 * sy - sx * dy1) / den
        return doubleArrayOf(x1 - x0 + g * x1, x3 - x0 + hh * x3, x0, y1 - y0 + g * y1, y3 - y0 + hh * y3, y0, g, hh, 1.0)
    }

    fun apply(m: DoubleArray, x: Double, y: Double): DoubleArray {
        val wz = m[6] * x + m[7] * y + m[8]
        return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / wz, (m[3] * x + m[4] * y + m[5]) / wz)
    }

    fun invert(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12) return null
        return doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, (f * g - d * i) / det, (a * i - c * g) / det,
            (c * d - a * f) / det, (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det)
    }

    /**
     * ¿[b] es [a] cambiando SÓLO las imágenes a mano ([PageEdits.photoZones])? Entonces el procesado nuevo es el anterior
     * con otra capa de fotos: se recompone sin reprocesar la página ([PhotoLayer], caché de las capas).
     */
    fun zonesOnly(a: PageEdits, b: PageEdits): Boolean = a != b && a.copy(photoZones = null) == b.copy(photoZones = null)

    /**
     * ¿Se puede recomponer la capa de fotos sobre la capa base guardada? Sólo si nada se aplica DESPUÉS de la capa de fotos
     * en la tubería (ajustes finos, quitar rayas, borrado manual): con ellos, render completo.
     */
    fun recomposable(e: PageEdits): Boolean =
        e.adjustments == com.scannerpromax.domain.Adjustments() && e.eraseStrokes.isEmpty() && !e.autoRemoveLines

    /** Clave de la caché de capas: todo menos las zonas a mano. */
    fun layersKey(e: PageEdits): PageEdits = e.copy(photoZones = null)
}
