package com.scannerpromax.imaging

import com.scannerpromax.imaging.SocProfile.Kind
import com.scannerpromax.imaging.SocProfile.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detección del procesador y de sus grupos de núcleos con datos PÚBLICOS (fichas técnicas y volcados de /proc/cpuinfo y
 * /sys/.../cpuinfo_max_freq de cada chip), y el reparto de hilos que se deduce ([PerfPolicy]).
 */
class SocProfileTest {

    /** cpuinfo mínimo: un bloque por núcleo con implementador y parte (como el de arm64), y la línea Hardware opcional. */
    private fun cpuinfo(parts: List<String>, hardware: String? = null): String = buildString {
        parts.forEachIndexed { i, p ->
            val (impl, part) = p.split(':')
            append("processor\t: $i\nBogoMIPS\t: 38.40\nCPU implementer\t: $impl\nCPU architecture: 8\nCPU part\t: $part\n\n")
        }
        hardware?.let { append("Hardware\t: $it\n") }
    }

    /** Frecuencias por núcleo (kHz) a partir de grupos (número de núcleos, MHz), del cpu0 en adelante. */
    private fun freqs(vararg g: Pair<Int, Int>): Map<Int, Long> {
        val m = HashMap<Int, Long>(); var i = 0
        for ((n, mhz) in g) repeat(n) { m[i++] = mhz * 1000L }
        return m
    }

    private fun parts(vararg g: Pair<Int, String>): List<String> = g.flatMap { (n, p) -> List(n) { p } }

    private val A53 = "0x41:0xd03"; private val A55 = "0x41:0xd05"; private val A73 = "0x41:0xd09"; private val A75 = "0x41:0xd0a"
    private val A76 = "0x41:0xd0b"; private val A77 = "0x41:0xd0d"; private val A78 = "0x41:0xd41"; private val X1 = "0x41:0xd44"
    private val A510 = "0x41:0xd46"; private val A710 = "0x41:0xd47"; private val A715 = "0x41:0xd4d"; private val X3 = "0x41:0xd4e"
    private val M5 = "0x53:0x004"; private val KRYO_GOLD = "0x51:0x800"; private val KRYO_SILVER = "0x51:0x801"

    private fun kinds(p: SocProfile) = p.clusters.map { it.kind to it.count }

    @Test fun kirin990() {
        val p = SocProfile.parse(null, null, "kirin990", "TAS", cpuinfo(parts(4 to A55, 4 to A76), "Kirin990"),
            freqs(4 to 1950, 2 to 2090, 2 to 2860), emptyMap(), 8)
        assertEquals(Vendor.HISILICON, p.vendor)
        assertTrue(p.name, p.name.contains("Kirin 990"))
        assertEquals(listOf(Kind.PRIME to 2, Kind.MID to 2, Kind.LITTLE to 4), kinds(p))
        assertEquals(listOf(6, 7), p.clusters[0].cpus)
        assertEquals(4, p.performanceCores); assertEquals(2, p.bigCores)
    }

    @Test fun kirin9000() {
        val p = SocProfile.parse(null, null, "kirin9000", "NOH", cpuinfo(parts(4 to A55, 4 to A77)),
            freqs(4 to 2050, 3 to 2540, 1 to 3130), emptyMap(), 8)
        assertEquals(Vendor.HISILICON, p.vendor)
        assertTrue(p.name, p.name.contains("Kirin 9000"))
        assertEquals(listOf(Kind.PRIME to 1, Kind.MID to 3, Kind.LITTLE to 4), kinds(p))
        assertEquals(4, p.performanceCores)
    }

    @Test fun exynos2100() {
        val p = SocProfile.parse("Samsung", "s5e9840", "exynos2100", "universal2100", cpuinfo(parts(4 to A55, 3 to A78, 1 to X1)),
            freqs(4 to 2210, 3 to 2810, 1 to 2910), emptyMap(), 8)
        assertEquals(Vendor.SAMSUNG, p.vendor)
        assertTrue(p.name, p.name.contains("Exynos 2100"))
        // X1 y A78 casi a la misma frecuencia pero de distinta clase: prime aparte
        assertEquals(listOf(Kind.PRIME to 1, Kind.MID to 3, Kind.LITTLE to 4), kinds(p))
    }

    @Test fun exynos990() {
        val p = SocProfile.parse(null, null, "exynos990", "universal990", cpuinfo(parts(4 to A55, 2 to A76, 2 to M5)),
            freqs(4 to 2002, 2 to 2504, 2 to 2730), emptyMap(), 8)
        assertEquals(Vendor.SAMSUNG, p.vendor)
        assertTrue(p.name, p.name.contains("Exynos 990"))
        assertEquals(listOf(Kind.PRIME to 2, Kind.MID to 2, Kind.LITTLE to 4), kinds(p))
        assertEquals(4, p.performanceCores)
    }

    @Test fun snapdragon865() {
        val p = SocProfile.parse("QTI", "SM8250", "qcom", "kona", cpuinfo(parts(4 to A55, 4 to A77)),
            freqs(4 to 1804, 3 to 2419, 1 to 2841), emptyMap(), 8)
        assertEquals(Vendor.QUALCOMM, p.vendor)
        assertEquals("Snapdragon 865 (SM8250)", p.name)
        assertEquals(listOf(Kind.PRIME to 1, Kind.MID to 3, Kind.LITTLE to 4), kinds(p))
    }

    @Test fun snapdragon8Gen2_a715yA710SonUnMismoGrupo() {
        val p = SocProfile.parse("QTI", "SM8550", "qcom", "kalama", cpuinfo(parts(3 to A510, 2 to A715, 2 to A710, 1 to X3)),
            freqs(3 to 2016, 4 to 2803, 1 to 3187), emptyMap(), 8)
        assertEquals("Snapdragon 8 Gen 2 (SM8550)", p.name)
        assertEquals(listOf(Kind.PRIME to 1, Kind.MID to 4, Kind.LITTLE to 3), kinds(p))
        assertEquals(5, p.performanceCores)
    }

    @Test fun snapdragon680() {
        val p = SocProfile.parse("QTI", "SM6225", "qcom", "bengal", cpuinfo(parts(4 to KRYO_SILVER, 4 to KRYO_GOLD)),
            freqs(4 to 1900, 4 to 2400), emptyMap(), 8)
        assertEquals("Snapdragon 680 (SM6225)", p.name)
        assertEquals(listOf(Kind.BIG to 4, Kind.LITTLE to 4), kinds(p))
        assertEquals(4, p.performanceCores)
    }

    @Test fun dimensity9200() {
        val p = SocProfile.parse("Mediatek", "MT6985", "mt6985", "k6985v1_64", cpuinfo(parts(4 to A510, 3 to A715, 1 to X3)),
            freqs(4 to 1800, 3 to 2850, 1 to 3050), emptyMap(), 8)
        assertEquals(Vendor.MEDIATEK, p.vendor)
        assertEquals("Dimensity 9200 (MT6985)", p.name)
        assertEquals(listOf(Kind.PRIME to 1, Kind.MID to 3, Kind.LITTLE to 4), kinds(p))
    }

    @Test fun helioG85_sinSocModel() {
        val p = SocProfile.parse(null, null, "mt6768", "k69v1_64", cpuinfo(parts(6 to A55, 2 to A75), "MT6769V/CZ"),
            freqs(6 to 1800, 2 to 2000), emptyMap(), 8)
        assertEquals(Vendor.MEDIATEK, p.vendor)
        assertTrue(p.name, p.name.contains("Helio G85"))
        assertEquals(listOf(Kind.BIG to 2, Kind.LITTLE to 6), kinds(p))
        assertEquals(2, p.performanceCores)
    }

    @Test fun tensorG2_conCapacidades() {
        val caps = HashMap<Int, Int>().apply { (0..3).forEach { put(it, 160) }; put(4, 640); put(5, 640); put(6, 1024); put(7, 1024) }
        val p = SocProfile.parse("Google", "GS201", "gs201", "cloudripper", cpuinfo(parts(4 to A55, 2 to A78, 2 to X1)),
            freqs(4 to 1803, 2 to 2348, 2 to 2850), caps, 8)
        assertEquals(Vendor.GOOGLE, p.vendor)
        assertEquals("Tensor G2 (GS201)", p.name)
        assertEquals(listOf(Kind.PRIME to 2, Kind.MID to 2, Kind.LITTLE to 4), kinds(p))
        assertEquals(1024, p.clusters[0].capacity)
    }

    @Test fun unisocT606_mismaFrecuenciaDistintoNucleo() {
        val p = SocProfile.parse(null, null, "ums9230", "s9863a1h10", cpuinfo(parts(6 to A55, 2 to A75), "Unisoc T606"),
            freqs(6 to 1600, 2 to 1600), emptyMap(), 8)
        assertEquals(Vendor.UNISOC, p.vendor)
        assertTrue(p.name, p.name.contains("T606"))
        assertEquals(listOf(Kind.BIG to 2, Kind.LITTLE to 6), kinds(p))
        assertEquals(listOf(6, 7), p.clusters[0].cpus)
    }

    @Test fun sinFrecuencias_porSelinux_usaLasPartes() {
        val p = SocProfile.parse(null, null, "kirin990", "", cpuinfo(parts(4 to A55, 4 to A76)), emptyMap(), emptyMap(), 8)
        assertEquals(listOf(Kind.BIG to 4, Kind.LITTLE to 4), kinds(p))
    }

    @Test fun sinDatos_unSoloGrupo() {
        val p = SocProfile.parse(null, null, null, null, "", emptyMap(), emptyMap(), 4)
        assertEquals(listOf(Kind.BIG to 4), kinds(p))
        assertTrue(p.homogeneous)
        assertEquals(4, p.performanceCores)
    }

    @Test fun ochoA53Homogeneo() {
        val p = SocProfile.parse(null, null, "mt6765", "", cpuinfo(parts(8 to A53)), freqs(8 to 2300), emptyMap(), 8)
        assertTrue(p.homogeneous)
        assertEquals(8, p.performanceCores)
    }

    @Test fun rangos() {
        assertEquals(listOf(0, 1, 2, 3, 5, 7), SocProfile.parseRange("0-3,5,7"))
        assertEquals(listOf(0), SocProfile.parseRange("0"))
    }

    // ------------------------------------------------------------------------------------------- política de hilos

    private val GB = 1L shl 30

    @Test fun politica_snapdragon8Gen2_maximo() {
        val p = SocProfile.parse("QTI", "SM8550", null, null, cpuinfo(parts(3 to A510, 2 to A715, 2 to A710, 1 to X3)),
            freqs(3 to 2016, 4 to 2803, 1 to 3187), emptyMap(), 8)
        val pol = PerfPolicy.of(p, PerfMode.MAX, 6 * GB, 12 * GB, 20_000_000, false)
        assertEquals(8, pol.cvThreads)          // TBB reparte: todos
        assertEquals(5, pol.ortThreads)         // prime + medios (sin los A510 en la barrera)
        assertEquals(2, pol.parallelPages)      // memoria de sobra
        assertEquals(PerfPolicy.PRIO_DISPLAY, pol.workerPriority)
        val bal = PerfPolicy.of(p, PerfMode.BALANCED, 6 * GB, 12 * GB, 20_000_000, false)
        assertEquals(5, bal.cvThreads); assertEquals(2, bal.ortThreads); assertEquals(PerfPolicy.PRIO_FOREGROUND, bal.workerPriority)
        val saver = PerfPolicy.of(p, PerfMode.SAVER, 6 * GB, 12 * GB, 20_000_000, false)
        assertEquals(2, saver.cvThreads); assertEquals(1, saver.ortThreads); assertEquals(1, saver.parallelPages)
        assertEquals(PerfPolicy.PRIO_BACKGROUND, saver.workerPriority)
    }

    @Test fun politica_helioG85_gamaBaja_unaPagina() {
        val p = SocProfile.parse(null, null, "mt6768", null, cpuinfo(parts(6 to A55, 2 to A75)), freqs(6 to 1800, 2 to 2000), emptyMap(), 8)
        val pol = PerfPolicy.of(p, PerfMode.MAX, 1 * GB, 3 * GB, 8_000_000, true)
        assertEquals(8, pol.cvThreads)
        assertEquals(2, pol.ortThreads)
        assertEquals(1, pol.parallelPages)       // poca RAM: nunca dos renders
        assertTrue(pol.squeezeThreads <= 2)
    }

    @Test fun politica_homogeneo4Nucleos() {
        val p = SocProfile.parse(null, null, null, null, cpuinfo(parts(4 to A53)), freqs(4 to 1400), emptyMap(), 4)
        val pol = PerfPolicy.of(p, PerfMode.MAX, 1 * GB, 2 * GB, 8_000_000, true)
        assertEquals(4, pol.cvThreads); assertEquals(4, pol.ortThreads); assertEquals(1, pol.parallelPages)
        val bal = PerfPolicy.of(p, PerfMode.BALANCED, 1 * GB, 2 * GB, 8_000_000, true)
        assertEquals(3, bal.cvThreads)
    }

    @Test fun politica_dosPaginasSoloConMemoria() {
        val p = SocProfile.parse("QTI", "SM8250", null, null, cpuinfo(parts(4 to A55, 4 to A77)), freqs(4 to 1804, 3 to 2419, 1 to 2841), emptyMap(), 8)
        // 20 MP x 34 B = 680 MB por render: con 1,5 GB libres no caben dos
        assertEquals(1, PerfPolicy.of(p, PerfMode.MAX, (1.5 * GB).toLong(), 8 * GB, 20_000_000, false).parallelPages)
        assertEquals(2, PerfPolicy.of(p, PerfMode.MAX, 3 * GB, 8 * GB, 20_000_000, false).parallelPages)
        assertEquals(1, PerfPolicy.of(p, PerfMode.MAX, 3 * GB, 4 * GB, 20_000_000, false).parallelPages)
    }

    @Test fun modoPorNombre() {
        assertEquals(PerfMode.MAX, PerfMode.of(null))
        assertEquals(PerfMode.SAVER, PerfMode.of("SAVER"))
        assertEquals(PerfMode.MAX, PerfMode.of("raro"))
    }
}
