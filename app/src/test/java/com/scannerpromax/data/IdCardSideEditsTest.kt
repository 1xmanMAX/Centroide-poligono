package com.scannerpromax.data

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.IdCardEdits
import com.scannerpromax.domain.IdCardSide
import com.scannerpromax.domain.PageEdits
import com.scannerpromax.domain.Pt
import com.scannerpromax.domain.Quad
import com.scannerpromax.imaging.CardOrientation
import com.scannerpromax.imaging.PageRotation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Edición de cada cara del DNI por separado: giro, espejo y mejora por cara guardados en [IdCardSide] (null en las
 * páginas antiguas: mismo hash), caché de caras renderizadas que solo cambia para la cara editada, y hojas de DNI fuera
 * del giro automático de páginas.
 */
class IdCardSideEditsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val q = Quad(Pt(0.1f, 0.2f), Pt(0.9f, 0.18f), Pt(0.92f, 0.8f), Pt(0.08f, 0.82f))
    private val q2 = Quad(Pt(0.12f, 0.2f), Pt(0.9f, 0.18f), Pt(0.92f, 0.8f), Pt(0.08f, 0.82f))

    @Test fun paginaAntigua_mismoJsonYMismoHash() {
        // JSON tal como lo guardaba la versión anterior (sin giro/espejo/mejora por cara)
        val old = """{"quad":null,"rotation":0,"filter":"ORIGINAL","idCard":{"front":{"quad":{"tl":{"x":0.1,"y":0.2},"tr":{"x":0.9,"y":0.18},"br":{"x":0.92,"y":0.8},"bl":{"x":0.08,"y":0.82}},"manual":true},"enhance":"VIVID"}}"""
        val e = json.decodeFromString(PageEdits.serializer(), old)
        assertNull(e.idCard!!.front.rotation); assertNull(e.idCard!!.front.mirror); assertNull(e.idCard!!.front.filter)
        val again = json.encodeToString(PageEdits.serializer(), e)
        assertFalse(again.contains("mirror")); assertFalse(again.contains("\"rotation\":null"))
        assertEquals(PageFiles.editsHash(e), PageFiles.editsHash(json.decodeFromString(PageEdits.serializer(), again)))
    }

    @Test fun giroEspejoYMejoraPorCara_seGuardanYVuelven() {
        val card = IdCardEdits(
            IdCardSide(q, manual = true).rotated(90).mirrored().copy(filter = FilterType.BLACK_WHITE),
            IdCardSide(q2).rotated(180),
            FilterType.VIVID,
        )
        val e = PageEdits(filter = FilterType.ORIGINAL, idCard = card)
        val back = json.decodeFromString(PageEdits.serializer(), json.encodeToString(PageEdits.serializer(), e))
        assertEquals(e, back)
        assertEquals(90, back.idCard!!.front.turn)
        assertEquals(true, back.idCard!!.front.mirror)
        assertEquals(FilterType.BLACK_WHITE, back.idCard!!.front.effectiveFilter(card.enhance))
        assertEquals(FilterType.VIVID, back.idCard!!.back!!.effectiveFilter(card.enhance))
        // cambia la geometría de la página (otro procesado)
        assertNotEquals(PageFiles.processedName("p", e), PageFiles.processedName("p", e.copy(idCard = card.copy(back = card.back!!.rotated(180)))))
    }

    @Test fun girosVuelvenANull() {
        val s = IdCardSide(q)
        assertEquals(s, s.rotated(90).rotated(270))
        assertEquals(s, s.rotated(180).rotated(180))
        assertEquals(270, s.rotated(-90).turn)
        assertEquals(s, s.mirrored().mirrored())
        assertNull(IdCardSide(q, filter = FilterType.ORIGINAL).effectiveFilter(FilterType.VIVID))
        assertNull(IdCardSide(q).effectiveFilter(null))
    }

    @Test fun claveDeCara_soloCambiaConSuPropiaEdicion() {
        val front = IdCardSide(q, manual = true)
        val back = IdCardSide(q2)
        val fpF = "100-aa"; val fpB = "200-bb"
        val k0 = PageFiles.idSideKey(front, FilterType.VIVID, fpF)
        val k1 = PageFiles.idSideKey(back, FilterType.VIVID, fpB)
        assertNotEquals(k0, k1)
        // editar el anverso (recorte, giro, espejo o su mejora) cambia SU clave y no la del reverso
        for (edited in listOf(front.copy(quad = q2), front.rotated(90), front.mirrored(), front.copy(filter = FilterType.NATURAL))) {
            assertNotEquals(k0, PageFiles.idSideKey(edited, FilterType.VIVID, fpF))
        }
        assertEquals(k1, PageFiles.idSideKey(back, FilterType.VIVID, fpB))
        // la marca «manual» no cambia la imagen
        assertEquals(k0, PageFiles.idSideKey(front.copy(manual = null), FilterType.VIVID, fpF))
        // la mejora explícita igual a la común da la misma imagen
        assertEquals(k0, PageFiles.idSideKey(front.copy(filter = FilterType.VIVID), FilterType.VIVID, fpF))
        // otra foto (cara reemplazada) -> otra clave
        assertNotEquals(k0, PageFiles.idSideKey(front, FilterType.VIVID, "101-ab"))
        // cambiar la mejora común solo afecta a las caras que la heredan
        val own = front.copy(filter = FilterType.BLACK_WHITE)
        assertEquals(PageFiles.idSideKey(own, FilterType.VIVID, fpF), PageFiles.idSideKey(own, null, fpF))
        assertNotEquals(PageFiles.idSideKey(back, FilterType.VIVID, fpB), PageFiles.idSideKey(back, null, fpB))
    }

    @Test fun cacheDeCaras_nombresDeLaPagina() {
        val k = PageFiles.idSideKey(IdCardSide(q), null, "1-a")
        val n = PageFiles.idRenderName("abc", k)
        // empieza por el id de la página: se borra con ella (deletePageFiles)
        assertTrue(n.startsWith("original/abc_"))
        assertTrue(n.substringAfter("original/").startsWith(PageFiles.idRenderPrefix("abc")))
        assertNotEquals(PageFiles.idSourceName("abc", false), n)
        assertNotEquals(PageFiles.idSourceName("abc", true), n)
    }

    @Test fun huellaDelContenido() {
        val a = tmp.newFile("a.jpg").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        val b = tmp.newFile("b.jpg").apply { writeBytes(a.readBytes()) }
        val c = tmp.newFile("c.jpg").apply { writeBytes(a.readBytes().also { it[10] = 99 }) }
        assertEquals(PageFiles.fingerprint(a), PageFiles.fingerprint(b))
        assertNotEquals(PageFiles.fingerprint(a), PageFiles.fingerprint(c))
        // mover el archivo (instalarlo en el documento) no cambia la huella
        val moved = java.io.File(tmp.root, "moved.jpg"); val before = PageFiles.fingerprint(a)
        assertTrue(a.renameTo(moved)); assertEquals(before, PageFiles.fingerprint(moved))
    }

    @Test fun hojaDeDni_noSeGiraAutomaticamente() {
        val card = IdCardEdits(IdCardSide(q), IdCardSide(q2), FilterType.VIVID)
        assertTrue(PageRotation.autoOrientAllowed(PageEdits()))
        assertFalse(PageRotation.autoOrientAllowed(PageEdits(idCard = card)))
        assertFalse(PageRotation.autoOrientAllowed(PageEdits(rotation = 90)))
        assertFalse(PageRotation.autoOrientAllowed(PageEdits(geoRotation = 0)))
    }

    @Test fun esquinas_giroYSentido() {
        val tl = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 6.0, 0.0, 6.0)
        // 90° horario: la nueva esquina superior izquierda es la inferior izquierda
        assertArrayEquals(doubleArrayOf(0.0, 6.0, 0.0, 0.0, 10.0, 0.0, 10.0, 6.0), CardOrientation.shift(tl, 90), 0.0)
        assertArrayEquals(doubleArrayOf(10.0, 6.0, 0.0, 6.0, 0.0, 0.0, 10.0, 0.0), CardOrientation.shift(tl, 180), 0.0)
        assertArrayEquals(tl, CardOrientation.shift(CardOrientation.shift(tl, 90), 270), 0.0)
        // sentido antihorario (esquinas «cruzadas» a mano) -> horario con la misma esquina inicial (sin espejo)
        val ccw = doubleArrayOf(0.0, 0.0, 0.0, 6.0, 10.0, 6.0, 10.0, 0.0)
        assertArrayEquals(tl, CardOrientation.clockwise(ccw), 0.0)
        assertTrue(CardOrientation.clockwise(tl) === tl)
    }
}
