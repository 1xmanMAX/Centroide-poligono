package com.scannerpromax.pdf

import com.scannerpromax.domain.CompressionLevel
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.imaging.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqueezePlanTest {

    @Test
    fun levelsMapToProfiles() {
        assertEquals(SqueezeParams("balanced", 0.97, 200f), SqueezePlan.forLevel(CompressionLevel.LIGHT))
        assertEquals(SqueezeParams("small", 0.94, 150f), SqueezePlan.forLevel(CompressionLevel.RECOMMENDED))
        assertEquals(SqueezeParams("small", 0.91, 120f), SqueezePlan.forLevel(CompressionLevel.STRONG))
        assertEquals(SqueezeParams("extreme", 0.86, 96f), SqueezePlan.forLevel(CompressionLevel.EXTREME))
        // Más fuerte = SSIM y dpi que nunca suben.
        val all = CompressionLevel.entries.map(SqueezePlan::forLevel)
        all.zipWithNext().forEach { (a, b) ->
            assertTrue(b.minSsim <= a.minSsim)
            assertTrue(b.maxDpi!! <= a.maxDpi!!)
        }
    }

    @Test
    fun exportKeepsResolutionAndMaxIsLossless() {
        val hi = SqueezePlan.forExport(ExportQuality.HIGH)
        assertNull(hi.maxDpi)
        assertTrue(hi.minSsim >= 0.97)
        assertTrue(hi.allowLossy)
        val max = SqueezePlan.forExport(ExportQuality.MAX)
        assertEquals("lossless", max.profile)
        assertFalse(max.allowLossy)
        assertNull(max.maxDpi)
    }

    @Test
    fun jsonHasOverridesAndNoZopfli() {
        val j = SqueezePlan.forLevel(CompressionLevel.RECOMMENDED).toJson()
        assertTrue(j, j.contains("\"profile\":\"small\""))
        assertTrue(j, j.contains("\"min_ssim\":0.940"))
        assertTrue(j, j.contains("\"max_dpi\":150.0"))
        assertTrue(j, j.contains("\"zopfli_iterations\":0"))
        assertTrue(j, j.contains("\"no_regression\":true"))
        assertTrue(SqueezePlan.forExport(ExportQuality.HIGH).toJson().contains("\"max_dpi\":null"))
        // Sin coma decimal aunque el sistema esté en español.
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("es", "ES"))
            assertTrue(SqueezePlan.forLevel(CompressionLevel.STRONG).toJson().contains("0.910"))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test
    fun qualityScaleIsMonotonic() {
        var prev = SqueezePlan.forQuality(0f)
        assertEquals("extreme", prev.profile)
        assertEquals("balanced", SqueezePlan.forQuality(1f).profile)
        for (i in 1..20) {
            val p = SqueezePlan.forQuality(i / 20f)
            assertTrue(p.minSsim >= prev.minSsim)
            assertTrue(p.maxDpi!! >= prev.maxDpi!!)
            prev = p
        }
    }

    @Test
    fun initialQualityFromAnalysis() {
        // Sin imágenes: la calidad no cambia nada.
        assertEquals(1f, SqueezePlan.initialQuality(1_000_000, 0, 500_000), 0f)
        // Objetivo inalcanzable (menos que lo que no es imagen) → mínimo.
        assertEquals(0f, SqueezePlan.initialQuality(10_000_000, 2_000_000, 1_000_000), 0f)
        val mild = SqueezePlan.initialQuality(10_000_000, 9_000_000, 8_000_000)
        val hard = SqueezePlan.initialQuality(10_000_000, 9_000_000, 2_000_000)
        assertTrue(mild > hard)
    }

    @Test
    fun targetSearchConvergesWithinFivePasses() {
        // Tamaño simulado: crece con s.
        fun size(s: Float) = (200_000 + 1_800_000 * s * s).toLong()
        val target = 1_000_000L
        val search = SqueezePlan.TargetSearch(target, 0.9f)
        while (true) {
            val s = search.next ?: break
            search.record(s, size(s))
        }
        assertTrue(search.passes <= SqueezePlan.MAX_PASSES)
        val best = search.bestFit
        assertTrue(best != null)
        assertTrue(search.bestFitSize <= target)
    }

    @Test
    fun targetSearchStopsWhenEvenMinimumIsTooBig() {
        val search = SqueezePlan.TargetSearch(100_000, 0.5f)
        while (true) {
            val s = search.next ?: break
            search.record(s, 5_000_000)
        }
        assertNull(search.bestFit)
        assertTrue(search.passes <= SqueezePlan.MAX_PASSES)
    }

    @Test
    fun threadsByTierAndMemory() {
        assertEquals(1, SqueezePlan.threadsFor(DeviceTier(isLowRam = true, maxWorkingPixels = 8_000_000, cores = 8)))
        assertEquals(2, SqueezePlan.threadsFor(DeviceTier(isLowRam = false, maxWorkingPixels = 12_600_000, cores = 4)))
        assertEquals(4, SqueezePlan.threadsFor(DeviceTier(isLowRam = false, maxWorkingPixels = 20_000_000, cores = 8)))
        assertEquals(3, SqueezePlan.threadsFor(DeviceTier(isLowRam = false, maxWorkingPixels = 16_000_000, cores = 6)))
        // 12 MP × 28 B × 4 hilos ≈ 1,3 GB: con 2 GB libres (1 GB de presupuesto) bajan los hilos.
        val px = 12_000_000L
        assertEquals(4, SqueezePlan.fitThreads(4, px, 1_000_000, 8L shl 30))
        val t = SqueezePlan.fitThreads(4, px, 1_000_000, 2L shl 30)
        assertTrue(t in 1..3)
        assertEquals(0, SqueezePlan.fitThreads(2, px, 1_000_000, 200L shl 20))
    }
}
