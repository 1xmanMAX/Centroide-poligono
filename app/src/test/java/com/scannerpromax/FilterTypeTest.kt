package com.scannerpromax

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTypeTest {
    @Test fun tresVisibles() {
        assertEquals(listOf(FilterType.BLACK_WHITE, FilterType.NATURAL, FilterType.ORIGINAL), FilterType.visible)
        assertEquals("Color natural", FilterType.NATURAL.label)
        assertEquals("Blanco y negro", FilterType.BLACK_WHITE.label)
        assertEquals("Color original", FilterType.ORIGINAL.label)
        assertEquals(FilterType.NATURAL, FilterType.DEFAULT)
        assertEquals(FilterType.NATURAL, PageEdits().filter)
    }

    @Test fun todosLosAntiguosSeMuestranComoUnVisible() {
        for (f in FilterType.entries) assertTrue("$f", f.uiFilter in FilterType.visible)
        for (f in FilterType.visible) assertEquals(f, f.uiFilter)
        assertEquals(FilterType.NATURAL, FilterType.MAGIC.uiFilter)
        assertEquals(FilterType.NATURAL, FilterType.AUTO.uiFilter)
        assertEquals(FilterType.NATURAL, FilterType.MAGIC_PRO.uiFilter)
        assertEquals(FilterType.BLACK_WHITE, FilterType.GRAYSCALE.uiFilter)
        assertEquals(FilterType.BLACK_WHITE, FilterType.ECO_INK.uiFilter)
        assertEquals(FilterType.ORIGINAL, FilterType.VIVID.uiFilter)
    }

    @Test fun textoResaltadoSeMigraAColorNatural() {
        assertEquals(FilterType.NATURAL, FilterType.MAGIC.migrated)
        // el resto no cambia (su hash de procesado tampoco)
        for (f in FilterType.entries) if (f != FilterType.MAGIC) assertEquals(f, f.migrated)
    }

    @Test fun documentosGuardadosConFiltrosAntiguosSeLeen() {
        val json = Json { ignoreUnknownKeys = true }
        for (name in listOf("AUTO", "MAGIC_PRO", "NO_SHADOW", "GRAYSCALE", "ECO_INK", "LIGHTEN", "VIVID", "WHITEBOARD")) {
            val e = json.decodeFromString(PageEdits.serializer(), """{"filter":"$name"}""")
            assertEquals(name, e.filter.name)
        }
    }
}
