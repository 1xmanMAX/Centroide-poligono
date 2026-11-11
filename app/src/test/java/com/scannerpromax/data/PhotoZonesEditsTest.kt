package com.scannerpromax.data

import com.scannerpromax.domain.Adjustments
import com.scannerpromax.domain.EraseStroke
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.PhotoZone
import com.scannerpromax.domain.PhotoZones
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.PageRotation
import com.scannerpromax.imaging.PhotoZoneMath
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * «Imágenes de la página» a mano ([PageEdits.photoZones], [PhotoZoneMath]): persistencia con el hash de siempre en las
 * páginas antiguas, resolución automático + manual, giros sin reprocesar, cambio de recorte y la clave de las capas
 * para recomponer sin reprocesar.
 */
class PhotoZonesEditsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun z(x0: Float, y0: Float, x1: Float, y1: Float, normal: Boolean? = null, poly: List<Pt>? = null) = PhotoZone(x0, y0, x1, y1, poly, normal)

    // ------------------------------------------------------------------------------------------- persistencia

    @Test fun paginaAntigua_mismoJsonYMismoHash() {
        val old = """{"quad":null,"rotation":90,"filter":"NATURAL","photoMode":2}"""
        val e = json.decodeFromString(PageEdits.serializer(), old)
        assertNull(e.photoZones)
        val again = json.encodeToString(PageEdits.serializer(), e)
        assertFalse(again.contains("photoZones"))
        assertEquals(PageFiles.editsHash(e), PageFiles.editsHash(json.decodeFromString(PageEdits.serializer(), again)))
        // y el hash de unas ediciones sin zonas no depende de que exista el campo
        assertEquals(PageFiles.editsHash(PageEdits()), PageFiles.editsHash(json.decodeFromString(PageEdits.serializer(), "{}")))
    }

    @Test fun zonas_seGuardanYVuelven_yCambianElHash() {
        val zs = PhotoZones(manual = listOf(z(0.1f, 0.2f, 0.5f, 0.6f), z(0.6f, 0.1f, 0.9f, 0.3f, normal = true, poly = listOf(Pt(0.6f, 0.1f), Pt(0.9f, 0.2f), Pt(0.7f, 0.3f)))),
            excluded = listOf(z(0.0f, 0.7f, 1f, 1f)))
        val e = PageEdits(photoZones = zs)
        val back = json.decodeFromString(PageEdits.serializer(), json.encodeToString(PageEdits.serializer(), e))
        assertEquals(e, back)
        assertNotEquals(PageFiles.editsHash(PageEdits()), PageFiles.editsHash(e))
        val none = PageEdits(photoZones = PhotoZoneMath.noPhotos())
        assertTrue(json.encodeToString(PageEdits.serializer(), none).contains("\"none\":true"))
        // vacío -> null (vuelve el hash de siempre)
        assertNull(PhotoZoneMath.normalized(PhotoZones()))
        assertNull(PhotoZoneMath.removeManual(PhotoZones(manual = listOf(z(0.1f, 0.1f, 0.3f, 0.3f))), 0))
        assertNull(PhotoZoneMath.restoreAuto(PhotoZones(excluded = listOf(z(0.1f, 0.1f, 0.3f, 0.3f))), z(0.11f, 0.1f, 0.3f, 0.31f)))
    }

    // ------------------------------------------------------------------------------------------- resolución

    private val autoA = floatArrayOf(0.1f, 0.1f, 0.5f, 0.4f)   // foto de verdad
    private val autoB = floatArrayOf(0.0f, 0.75f, 1.0f, 1.0f)  // zona oscura tomada por foto

    @Test fun automatico_sinZonas_todasLasCajas() {
        val r = PhotoZoneMath.resolve(listOf(autoA, autoB), null)
        assertEquals(2, r.photos.size); assertTrue(r.keep.isEmpty())
    }

    @Test fun noEsUnaImagen_quitaSoloEsaCaja_aunqueLaDeteccionSeMuevaUnosPixeles() {
        val zs = PhotoZoneMath.excludeAuto(null, PhotoZoneMath.zone(autoB))
        // render siguiente: la misma zona oscura con el recuadro 1 % movido
        val moved = floatArrayOf(0.01f, 0.76f, 0.99f, 1.0f)
        val r = PhotoZoneMath.resolve(listOf(autoA, moved), zs)
        assertEquals(1, r.photos.size)
        assertEquals(0.1f, r.photos[0].x0, 1e-6f)
    }

    @Test fun aquiNoHayImagenes_ningunaAutomatica_peroLasManualesSi() {
        assertTrue(PhotoZoneMath.resolve(listOf(autoA, autoB), PhotoZoneMath.noPhotos()).isEmpty)
        val withManual = PhotoZoneMath.addManual(PhotoZoneMath.noPhotos(), z(0.2f, 0.2f, 0.3f, 0.3f))
        val r = PhotoZoneMath.resolve(listOf(autoA, autoB), withManual)
        assertEquals(1, r.photos.size); assertEquals(0.2f, r.photos[0].x0, 1e-6f)
    }

    @Test fun subseleccion_yZonaComoElResto() {
        // sólo una parte de la caja automática: la ajustada sustituye a la automática
        val adj = PhotoZoneMath.adjustAuto(null, PhotoZoneMath.zone(autoA), z(0.2f, 0.15f, 0.4f, 0.35f))
        val r = PhotoZoneMath.resolve(listOf(autoA, autoB), adj)
        assertEquals(2, r.photos.size)
        assertTrue(r.photos.any { abs(it.x0 - 0.2f) < 1e-6f && abs(it.x1 - 0.4f) < 1e-6f })
        assertTrue(r.photos.none { abs(it.x0 - 0.1f) < 1e-6f })
        // «como el resto» dentro de una foto: la foto sigue, esa zona se queda con la capa base
        val keep = PhotoZoneMath.addManual(null, z(0.2f, 0.2f, 0.3f, 0.3f, normal = true))
        val r2 = PhotoZoneMath.resolve(listOf(autoA), keep)
        assertEquals(1, r2.photos.size); assertEquals(1, r2.keep.size)
        // «como el resto» que tapa toda la caja: la quita
        val r3 = PhotoZoneMath.resolve(listOf(autoA), PhotoZoneMath.addManual(null, z(0.05f, 0.05f, 0.55f, 0.45f, normal = true)))
        assertTrue(r3.photos.isEmpty())
    }

    @Test fun zonaDiminuta_yLazo() {
        assertNull(PhotoZoneMath.addManual(null, z(0.5f, 0.5f, 0.505f, 0.6f)))
        val l = PhotoZoneMath.lasso(listOf(Pt(0.2f, 0.2f), Pt(0.6f, 0.25f), Pt(0.5f, 0.7f), Pt(0.25f, 0.6f)))
        assertNotNull(l)
        assertEquals(0.2f, l!!.x0, 1e-6f); assertEquals(0.7f, l.y1, 1e-6f)
        assertTrue(PhotoZoneMath.contains(l, Pt(0.4f, 0.4f)))
        assertFalse(PhotoZoneMath.contains(l, Pt(0.58f, 0.65f)))  // dentro del recuadro, fuera del contorno
        assertEquals(64, PhotoZoneMath.simplify(List(500) { Pt(it / 500f, 0.5f) }).size)
    }

    // ------------------------------------------------------------------------------------------- giros y recorte

    @Test fun girar_giraLasZonas_y4GirosVuelvenAlPrincipio() {
        val zs = PhotoZones(manual = listOf(z(0.1f, 0.2f, 0.4f, 0.3f, poly = listOf(Pt(0.1f, 0.2f), Pt(0.4f, 0.2f), Pt(0.2f, 0.3f)))), excluded = listOf(z(0f, 0.8f, 1f, 1f)))
        var e = PageEdits(photoZones = zs)
        val r1 = PageRotation.withRotation(e, 90, 0.7f)
        // (0.1, 0.2)-(0.4, 0.3) girado 90° horario: x' = 1 - y, y' = x
        val m = r1.photoZones!!.manual[0]
        assertEquals(0.7f, m.x0, 1e-6f); assertEquals(0.1f, m.y0, 1e-6f); assertEquals(0.8f, m.x1, 1e-6f); assertEquals(0.4f, m.y1, 1e-6f)
        // girar = no reprocesar (sólo cambia el giro y las zonas giradas con él)
        assertTrue(PageRotation.rotationOnly(e, r1, 0.7f))
        repeat(4) { e = PageRotation.withRotation(e, e.rotation + 90, 0.7f) }
        assertTrue(PhotoZoneMath.sameZones(zs, e.photoZones))
        // al marco de la geometría (giro final de 90): vuelve al de partida
        assertTrue(PhotoZoneMath.sameZones(zs, PhotoZoneMath.toGeo(r1.photoZones, PageRotation.delta(r1))))
        assertTrue(PhotoZoneMath.sameZones(zs, PageRotation.geoEdits(r1, 700, 1000).photoZones))
    }

    @Test fun cambiarElRecorte_llevaLasZonasAlRecorteNuevo() {
        val w = 1000; val h = 2000
        val q1 = Quad(Pt(100f, 200f), Pt(900f, 200f), Pt(900f, 1800f), Pt(100f, 1800f))
        val q2 = Quad(Pt(0f, 0f), Pt(1000f, 0f), Pt(1000f, 2000f), Pt(0f, 2000f))
        // zona que cubre el recorte entero -> en la página entera, de (100,200) a (900,1800)
        val zs = PhotoZones(manual = listOf(z(0f, 0f, 1f, 1f)))
        val out = PhotoZoneMath.remapQuad(zs, PageEdits(quad = q1), PageEdits(quad = q2), w, h)!!.manual[0]
        assertEquals(0.1f, out.x0, 1e-4f); assertEquals(0.1f, out.y0, 1e-4f); assertEquals(0.9f, out.x1, 1e-4f); assertEquals(0.9f, out.y1, 1e-4f)
        // con giro de 90 en las dos: la zona (marco final) sigue en su sitio relativo
        val a = PageEdits(quad = q1, rotation = 90); val b = PageEdits(quad = q2, rotation = 90)
        val zr = PhotoZones(manual = listOf(z(0f, 0f, 0.5f, 1f)))  // mitad izquierda del final = mitad inferior del recorte
        val o2 = PhotoZoneMath.remapQuad(zr, a, b, w, h)!!.manual[0]
        assertEquals(0.1f, o2.x0, 1e-4f); assertEquals(0.5f, o2.x1, 1e-4f); assertEquals(0.1f, o2.y0, 1e-4f); assertEquals(0.9f, o2.y1, 1e-4f)
        // zona que queda fuera del recorte nuevo: se quita
        val small = Quad(Pt(100f, 200f), Pt(500f, 200f), Pt(500f, 1000f), Pt(100f, 1000f))
        assertNull(PhotoZoneMath.remapQuad(PhotoZones(manual = listOf(z(0.8f, 0.8f, 1f, 1f))), PageEdits(quad = q2), PageEdits(quad = small), w, h))
        // homografía de un cuadrilátero en perspectiva: ida y vuelta
        val qp = Quad(Pt(120f, 80f), Pt(870f, 140f), Pt(930f, 1900f), Pt(60f, 1700f))
        val hq = PhotoZoneMath.unitToQuad(qp)!!; val inv = PhotoZoneMath.invert(hq)!!
        for ((u, v) in listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0, 0.3 to 0.7)) {
            val p = PhotoZoneMath.apply(hq, u, v); val back = PhotoZoneMath.apply(inv, p[0], p[1])
            assertEquals(u, back[0], 1e-9); assertEquals(v, back[1], 1e-9)
        }
        assertEquals(870.0, PhotoZoneMath.apply(hq, 1.0, 0.0)[0], 1e-6); assertEquals(1900.0, PhotoZoneMath.apply(hq, 1.0, 1.0)[1], 1e-6)
    }

    // ------------------------------------------------------------------------------------------- recomponer

    @Test fun soloZonas_recomponibleYMismaClaveDeCapas() {
        val e0 = PageEdits(filter = FilterType.NATURAL)
        val e1 = e0.copy(photoZones = PhotoZoneMath.noPhotos())
        assertTrue(PhotoZoneMath.zonesOnly(e0, e1))
        assertFalse(PhotoZoneMath.zonesOnly(e0, e0))
        assertFalse(PhotoZoneMath.zonesOnly(e0, e1.copy(filter = FilterType.BLACK_WHITE)))
        assertEquals(PageFiles.photoLayersPrefix("p1", e0), PageFiles.photoLayersPrefix("p1", e1))
        assertNotEquals(PageFiles.photoLayersPrefix("p1", e0), PageFiles.photoLayersPrefix("p1", e0.copy(rotation = 90)))
        assertTrue(PageFiles.photoBaseName("p1", e0).endsWith(".base.jpg"))
        assertTrue(PageFiles.photoBaseName("p1", e0.copy(filter = FilterType.BLACK_WHITE)).endsWith(".base.png"))
        // lo que va DESPUÉS de la capa de fotos impide recomponer
        assertTrue(PhotoZoneMath.recomposable(e1))
        assertFalse(PhotoZoneMath.recomposable(e1.copy(adjustments = Adjustments(brightness = 0.2f))))
        assertFalse(PhotoZoneMath.recomposable(e1.copy(autoRemoveLines = true)))
        assertFalse(PhotoZoneMath.recomposable(e1.copy(eraseStrokes = listOf(EraseStroke(listOf(Pt(0.1f, 0.1f)), 0.01f)))))
        // plan: borrador listo > giro > recomponer > render
        assertEquals(PageFiles.EditPlan.RECOMPOSE, PageFiles.editPlan(draftReady = false, rotationOnly = false, layersReady = true))
        assertEquals(PageFiles.EditPlan.REUSE, PageFiles.editPlan(draftReady = true, rotationOnly = false, layersReady = true))
        assertEquals(PageFiles.EditPlan.RENDER, PageFiles.editPlan(draftReady = false, rotationOnly = false))
        // el archivo de cajas automáticas
        val info = PageFiles.PhotoInfo(listOf(listOf(0.1f, 0.2f, 0.3f, 0.4f)), 800, 1000, listOf(240.0, 238.0, 230.0))
        assertEquals(info, PageFiles.decodePhotoInfo(PageFiles.encodePhotoInfo(info)))
        assertNull(PageFiles.decodePhotoInfo("no es json"))
    }
}
