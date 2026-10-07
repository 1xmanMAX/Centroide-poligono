package com.scannerpromax

import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageEdits
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTypeTest {
    @Test fun soloTresVisibles() {
        assertEquals(listOf(FilterType.BLACK_WHITE, FilterType.MAGIC, FilterType.ORIGINAL), FilterType.visible)
        assertEquals("Blanco y negro", FilterType.BLACK_WHITE.label)
        assertEquals("Texto resaltado", FilterType.MAGIC.label)
        assertEquals("Color original", FilterType.ORIGINAL.label)
        assertEquals(FilterType.MAGIC, FilterType.DEFAULT)
    }

    @Test fun todosLosAntiguosSeMuestranComoUnVisible() {
        for (f in FilterType.entries) assertTrue("$f", f.uiFilter in FilterType.visible)
        for (f in FilterType.visible) assertEquals(f, f.uiFilter)
        assertEquals(FilterType.MAGIC, FilterType.AUTO.uiFilter)
        assertEquals(FilterType.MAGIC, FilterType.MAGIC_PRO.uiFilter)
        assertEquals(FilterType.BLACK_WHITE, FilterType.GRAYSCALE.uiFilter)
        assertEquals(FilterType.BLACK_WHITE, FilterType.ECO_INK.uiFilter)
        assertEquals(FilterType.ORIGINAL, FilterType.VIVID.uiFilter)
    }

    @Test fun documentosGuardadosConFiltrosAntiguosSeLeen() {
        val json = Json { ignoreUnknownKeys = true }
        for (name in listOf("AUTO", "MAGIC_PRO", "NO_SHADOW", "GRAYSCALE", "ECO_INK", "LIGHTEN", "VIVID", "WHITEBOARD")) {
            val e = json.decodeFromString(PageEdits.serializer(), """{"filter":"$name"}""")
            assertEquals(name, e.filter.name)
        }
    }
}
