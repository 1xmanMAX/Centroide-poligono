package com.scannerpromax.imaging

import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Procesador del teléfono (SoC) y sus grupos de núcleos (prime / grandes / medios / pequeños), leídos de Build.SOC_* (API 31+),
 * Build.HARDWARE/BOARD, /proc/cpuinfo y /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq + cpu_capacity. Lógica pura
 * ([parse]) probada con datos públicos de Kirin, Exynos, Snapdragon, Dimensity/Helio, Tensor y Unisoc.
 *
 * Sirve para dimensionar los hilos: OpenCV (TBB, reparto dinámico: tolera núcleos lentos), ONNX Runtime (barrera por
 * operación: solo núcleos rápidos), pdfsqueeze y cuántas páginas se procesan a la vez ([PerfPolicy]).
 */
data class SocProfile(
    val vendor: Vendor,
    /** Nombre legible del chip («Snapdragon 8 Gen 2 (SM8550)», «Kirin 990», «Exynos 2100»...). */
    val name: String,
    /** Grupos de núcleos del más rápido al más lento. */
    val clusters: List<Cluster>,
) {
    enum class Vendor(val label: String) {
        QUALCOMM("Qualcomm Snapdragon"), SAMSUNG("Samsung Exynos"), HISILICON("Huawei Kirin"), MEDIATEK("MediaTek"),
        GOOGLE("Google Tensor"), UNISOC("Unisoc"), OTHER("otro"),
    }

    enum class Kind(val label: String) { PRIME("prime"), BIG("grandes"), MID("medios"), LITTLE("pequeños") }

    data class Cluster(val kind: Kind, val cpus: List<Int>, val maxFreqKhz: Long, val capacity: Int?, val part: String?) {
        val count: Int get() = cpus.size
    }

    val totalCores: Int get() = clusters.sumOf { it.count }.coerceAtLeast(1)

    /** Núcleos de rendimiento (prime + grandes + medios). En chips homogéneos (8 x A53) son todos. */
    val performanceCores: Int get() = clusters.filter { it.kind != Kind.LITTLE }.sumOf { it.count }.coerceAtLeast(1)

    /** Solo los más rápidos (prime + grandes). */
    val bigCores: Int get() = clusters.filter { it.kind == Kind.PRIME || it.kind == Kind.BIG }.sumOf { it.count }.coerceAtLeast(1)

    val littleCores: Int get() = clusters.filter { it.kind == Kind.LITTLE }.sumOf { it.count }

    /** ¿Todos los núcleos son iguales (sin big.LITTLE)? */
    val homogeneous: Boolean get() = clusters.size <= 1

    /** Núcleos rápidos de microarquitectura moderna (A76 o posterior / Mongoose M5): procesador de gama alta reciente. */
    val fastCoreClass: Int get() = clusters.firstOrNull()?.part?.let { partClass(it) } ?: 0

    /** Texto corto «1 prime 3,19 GHz + 4 medios 2,8 GHz + 3 pequeños 2,0 GHz». */
    fun clustersText(): String = clusters.joinToString(" + ") { c ->
        "${c.count} ${c.kind.label}" + if (c.maxFreqKhz > 0) " " + String.format(Locale("es"), "%.2f GHz", c.maxFreqKhz / 1e6) else ""
    }

    companion object {

        /** Perfil del equipo actual (Android; en la JVM de los tests devuelve lo que pueda leer). */
        fun detect(): SocProfile {
            val n = max(1, Runtime.getRuntime().availableProcessors())
            val (socMan, socModel) = try {
                if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER to android.os.Build.SOC_MODEL else null to null
            } catch (_: Throwable) { null to null }
            val hardware = runCatching { android.os.Build.HARDWARE }.getOrNull()
            val board = runCatching { android.os.Build.BOARD }.getOrNull()
            val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
            val possible = runCatching { File("/sys/devices/system/cpu/possible").readText().trim() }.getOrNull()
            val count = possible?.let { parseRange(it).maxOrNull()?.plus(1) } ?: n
            val freqs = HashMap<Int, Long>(); val caps = HashMap<Int, Int>()
            for (i in 0 until max(count, n)) {
                runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()?.let { freqs[i] = it }
                runCatching { File("/sys/devices/system/cpu/cpu$i/cpu_capacity").readText().trim().toInt() }.getOrNull()?.let { caps[i] = it }
            }
            return parse(socMan, socModel, hardware, board, cpuinfo, freqs, caps, max(count, n))
        }

        /** "0-3,5,7" -> [0,1,2,3,5,7]. */
        internal fun parseRange(s: String): List<Int> = s.split(',').flatMap { part ->
            val p = part.trim()
            if (p.isEmpty()) emptyList() else if ('-' in p) {
                val (a, b) = p.split('-').map { it.trim().toInt() }; (a..b).toList()
            } else listOf(p.toInt())
        }

        /**
         * Construye el perfil. [maxFreqKhz] y [capacity] por número de CPU (pueden faltar: SELinux en algunos Huawei/Xiaomi);
         * [cpuinfo] aporta la «CPU part» de cada núcleo (distingue A75 de A55 en los Unisoc con la misma frecuencia máxima) y la
         * línea «Hardware» de los chips antiguos.
         */
        fun parse(
            socManufacturer: String?, socModel: String?, hardware: String?, board: String?, cpuinfo: String,
            maxFreqKhz: Map<Int, Long>, capacity: Map<Int, Int>, cpuCount: Int,
        ): SocProfile {
            val parts = cpuParts(cpuinfo)
            val n = max(cpuCount, max((maxFreqKhz.keys.maxOrNull() ?: -1) + 1, (parts.keys.maxOrNull() ?: -1) + 1)).coerceAtLeast(1)
            val hwLine = Regex("(?im)^Hardware\\s*:\\s*(.+)$").find(cpuinfo)?.groupValues?.get(1)?.trim()
            val (vendor, name) = identify(socManufacturer, socModel, hardware, board, hwLine)
            // Puntuación de cada núcleo: capacidad del planificador si la hay (ya refleja la microarquitectura); si no,
            // frecuencia x clase de núcleo (A55 a 2 GHz no es «grande» aunque un A75 vaya a la misma frecuencia)
            data class C(val id: Int, val f: Long, val cap: Int?, val part: String?) {
                val score: Double get() = cap?.toDouble()?.times(1000) ?: (f.toDouble().coerceAtLeast(1.0) * (part?.let { partWeight(it) } ?: 1.0))
            }
            val cores = (0 until n).map { C(it, maxFreqKhz[it] ?: 0L, capacity[it], parts[it]) }
            // grupos: núcleos con la misma puntuación (±3 %) y la misma clase de núcleo
            val sorted = cores.sortedWith(compareByDescending<C> { it.score }.thenBy { it.id })
            val groups = ArrayList<MutableList<C>>()
            for (c in sorted) {
                val g = groups.lastOrNull()
                val same = g != null && kotlin.math.abs(g.first().score - c.score) <= 0.03 * g.first().score &&
                    (g.first().part == null || c.part == null || partClass(g.first().part!!) == partClass(c.part))
                if (same) g!!.add(c) else groups.add(arrayListOf(c))
            }
            // sin datos (ni frecuencias ni capacidades ni partes): un solo grupo
            val noData = cores.all { it.f == 0L && it.cap == null && it.part == null }
            val gs: List<List<C>> = if (noData) listOf(cores) else groups
            val kinds = classify(gs.map { g -> g.size to (g.first().part?.let { partClass(it) }) })
            val clusters = gs.mapIndexed { i, g ->
                Cluster(kinds[i], g.map { it.id }.sorted(), g.maxOf { it.f }, g.first().cap, g.first().part)
            }
            return SocProfile(vendor, name, clusters)
        }

        /**
         * Tipo de cada grupo (ordenados del más rápido al más lento): 1 grupo = todos «grandes» (homogéneo); 2 grupos =
         * grandes + pequeños; 3 o más = prime (si es de 1-2 núcleos) / grandes, medios..., y el último pequeños. Un grupo de
         * núcleos de clase «pequeña» (A53/A55/A510/A520) es siempre pequeño salvo en chips homogéneos.
         */
        internal fun classify(groups: List<Pair<Int, Int?>>): List<Kind> {
            if (groups.size <= 1) return List(groups.size) { Kind.BIG }
            val out = MutableList(groups.size) { Kind.MID }
            out[out.lastIndex] = Kind.LITTLE
            if (groups.size == 2) out[0] = Kind.BIG
            else {
                out[0] = if (groups[0].first <= 2) Kind.PRIME else Kind.BIG
                if (out[0] == Kind.PRIME && groups.size >= 4) out[1] = Kind.BIG
            }
            // clase pequeña explícita (partes de cpuinfo) -> pequeño, menos el primer grupo
            for (i in 1 until groups.size) if (groups[i].second == 1) out[i] = Kind.LITTLE
            return out
        }

        /** «CPU part» de cada procesador de /proc/cpuinfo (implementador:parte, p. ej. "0x41:0xd05"). */
        internal fun cpuParts(cpuinfo: String): Map<Int, String> {
            val out = HashMap<Int, String>()
            var cur = -1; var impl: String? = null
            for (raw in cpuinfo.lineSequence()) {
                val line = raw.trim()
                val k = line.substringBefore(':').trim().lowercase(); val v = line.substringAfter(':', "").trim().lowercase()
                when (k) {
                    "processor" -> { cur = v.toIntOrNull() ?: -1; impl = null }
                    "cpu implementer" -> impl = v
                    "cpu part" -> if (cur >= 0) out[cur] = "${impl ?: "0x41"}:$v"
                }
            }
            return out
        }

        /** Clase del núcleo: 1 = pequeño (en orden), 2 = medio/grande antiguo (A57-A75), 3 = grande moderno (A76+), 4 = X/prime. */
        internal fun partClass(part: String): Int {
            val p = part.substringAfter(':')
            val impl = part.substringBefore(':')
            return when (impl) {
                "0x41" -> when (p) {
                    "0xd03", "0xd04", "0xd05", "0xd46", "0xd80", "0xd01", "0xd02" -> 1
                    "0xd07", "0xd08", "0xd09", "0xd0a" -> 2
                    "0xd0b", "0xd0d", "0xd0e", "0xd41", "0xd47", "0xd4d", "0xd81", "0xd87" -> 3
                    "0xd44", "0xd48", "0xd4e", "0xd82", "0xd85" -> 4
                    else -> 2
                }
                "0x51" -> when (p) {   // Qualcomm Kryo (semi-custom)
                    "0x801", "0x803", "0x805" -> 1
                    "0x800", "0x802" -> 2
                    "0x804" -> 3
                    else -> 2
                }
                "0x53" -> if (p == "0x004" || p == "0x003") 3 else 2   // Samsung Mongoose M4/M5 (M3: 0x002)
                "0x48" -> if (p == "0xd40" || p == "0xd02") 3 else 2   // HiSilicon TaiShan (Kirin 9000s)
                else -> 2
            }
        }

        /** Peso por clase para ordenar núcleos sin cpu_capacity. */
        private fun partWeight(part: String): Double = when (partClass(part)) { 1 -> 0.55; 2 -> 0.85; 3 -> 1.0; else -> 1.15 }

        /** (fabricante, nombre legible) a partir de Build.SOC_*, HARDWARE, BOARD y la línea Hardware de cpuinfo. */
        internal fun identify(socMan: String?, socModel: String?, hardware: String?, board: String?, hwLine: String?): Pair<Vendor, String> {
            val all = listOfNotNull(socMan, socModel, hardware, board, hwLine).joinToString(" ").lowercase()
            val model = socModel?.takeIf { it.isNotBlank() && it != "unknown" }
            fun has(vararg k: String) = k.any { all.contains(it) }
            val vendor = when {
                has("qualcomm", "qcom", "snapdragon", "sm8", "sm7", "sm6", "sm4", "sdm", "msm", "kona", "lahaina", "taro", "kalama", "pineapple", "lito", "bengal", "holi") -> Vendor.QUALCOMM
                has("google", "gs101", "gs201", "zuma", "zumapro", "tensor") -> Vendor.GOOGLE
                has("samsung", "exynos", "s5e", "universal", "erd") -> Vendor.SAMSUNG
                has("hisilicon", "kirin", "hi36", "hi62") -> Vendor.HISILICON
                has("mediatek", "mt6", "mt8", "dimensity", "helio") -> Vendor.MEDIATEK
                has("unisoc", "spreadtrum", "sprd", "ums", "ud7", "sc98", "t606", "t616", "t618", "t610", "t612", "t700", "t760") -> Vendor.UNISOC
                else -> Vendor.OTHER
            }
            val code = (model ?: hwLine ?: hardware ?: board ?: "").trim()
            val friendly = friendlyName(vendor, code.lowercase())
            val name = when {
                friendly != null && code.isNotEmpty() && !friendly.contains(code, ignoreCase = true) -> "$friendly ($code)"
                friendly != null -> friendly
                code.isNotEmpty() -> "${vendor.label} $code".trim()
                else -> vendor.label
            }
            return vendor to name
        }

        /** Nombres comerciales de los chips más habituales (los demás se muestran con su código). */
        private fun friendlyName(v: Vendor, c: String): String? = when (v) {
            Vendor.QUALCOMM -> mapOf(
                "sm8650" to "Snapdragon 8 Gen 3", "sm8550" to "Snapdragon 8 Gen 2", "sm8475" to "Snapdragon 8+ Gen 1", "sm8450" to "Snapdragon 8 Gen 1",
                "sm8350" to "Snapdragon 888", "sm8250" to "Snapdragon 865", "sm8150" to "Snapdragon 855", "sdm845" to "Snapdragon 845",
                "sm8750" to "Snapdragon 8 Elite", "sm7325" to "Snapdragon 778G", "sm7250" to "Snapdragon 765G", "sm7150" to "Snapdragon 730",
                "sm6375" to "Snapdragon 695", "sm6225" to "Snapdragon 680", "sm6125" to "Snapdragon 665", "sm6115" to "Snapdragon 662",
                "sm4350" to "Snapdragon 480", "sm4250" to "Snapdragon 460", "sdm450" to "Snapdragon 450", "msm8953" to "Snapdragon 625",
                "kona" to "Snapdragon 865", "lahaina" to "Snapdragon 888", "taro" to "Snapdragon 8 Gen 1", "kalama" to "Snapdragon 8 Gen 2",
                "pineapple" to "Snapdragon 8 Gen 3", "bengal" to "Snapdragon 662/665/680",
            ).entries.firstOrNull { c.contains(it.key) }?.value
            Vendor.GOOGLE -> when {
                c.contains("zumapro") -> "Tensor G4"; c.contains("zuma") -> "Tensor G3"; c.contains("gs201") -> "Tensor G2"; c.contains("gs101") -> "Tensor G1"
                else -> null
            }
            Vendor.SAMSUNG -> Regex("(exynos|s5e)\\s*(\\d{3,4})").find(c)?.let { "Exynos " + it.groupValues[2].let { d -> mapOf("9925" to "2200", "9840" to "2100", "9830" to "990", "9820" to "980", "9815" to "1380", "8835" to "1380", "8825" to "1280", "9945" to "2400").getOrElse(d) { d } } }
            Vendor.HISILICON -> Regex("kirin\\s*(\\d{3,4}[a-z]*)").find(c)?.let { "Kirin " + it.groupValues[1].uppercase() }
            Vendor.MEDIATEK -> mapOf(
                "mt6985" to "Dimensity 9200", "mt6989" to "Dimensity 9300", "mt6983" to "Dimensity 9000", "mt6895" to "Dimensity 8100",
                "mt6893" to "Dimensity 1200", "mt6877" to "Dimensity 900", "mt6833" to "Dimensity 700", "mt6853" to "Dimensity 720",
                "mt6785" to "Helio G90T/G95", "mt6769" to "Helio G85/G88", "mt6768" to "Helio G85/P65", "mt6765" to "Helio P35/G35",
                "mt6762" to "Helio P22", "mt6781" to "Helio G96", "mt6789" to "Helio G99",
            ).entries.firstOrNull { c.contains(it.key) }?.value
            Vendor.UNISOC -> Regex("(t\\d{3})").find(c)?.let { "Unisoc " + it.groupValues[1].uppercase() }
            Vendor.OTHER -> null
        }

        /** Perfil mínimo (sin datos) para tests y respaldo. */
        fun generic(cores: Int): SocProfile = SocProfile(Vendor.OTHER, "desconocido", listOf(Cluster(Kind.BIG, (0 until max(1, cores)).toList(), 0L, null, null)))
    }
}

/** Modo de rendimiento elegido en Ajustes. */
enum class PerfMode(val label: String) {
    MAX("Máximo"), BALANCED("Equilibrado"), SAVER("Ahorro de batería");
    companion object { fun of(name: String?): PerfMode = entries.firstOrNull { it.name == name } ?: MAX }
}

/**
 * Política de hilos y prioridades a partir de [SocProfile], el modo de Ajustes y la memoria. Lógica pura (tests JVM).
 *  - OpenCV (TBB, reparto dinámico con robo de trabajo): Máximo = TODOS los núcleos (los pequeños también restan trabajo);
 *    Equilibrado = núcleos de rendimiento; Ahorro = la mitad de los de rendimiento.
 *  - ONNX Runtime: hilos intra-op = núcleos rápidos (prime + grandes + medios), inter-op 1; con núcleos pequeños en la
 *    misma barrera, el más lento marca el paso de cada operación. Chips homogéneos: hasta 4.
 *  - Páginas a la vez: 2 si hay memoria de sobra para dos renders completos (y núcleos de rendimiento suficientes).
 */
data class PerfPolicy(
    val mode: PerfMode,
    val cvThreads: Int,
    val ortThreads: Int,
    val squeezeThreads: Int,
    val parallelPages: Int,
    /** Prioridad (nice de Android) de los hilos de procesado mientras el usuario espera. */
    val workerPriority: Int,
    /** Hilos propios para repartir franjas/teselas por píxel (además de OpenCV). */
    val bandThreads: Int,
) {
    companion object {
        /** android.os.Process.THREAD_PRIORITY_* (constantes copiadas: lógica pura sin Android). */
        const val PRIO_DISPLAY = -4
        const val PRIO_FOREGROUND = -2
        const val PRIO_DEFAULT = 0
        const val PRIO_BACKGROUND = 10

        /** Pico de memoria nativa estimado de un render completo, por píxel de trabajo (Mats RGB/RGBA/float intermedios). */
        const val RENDER_BYTES_PER_PIXEL = 34L

        /**
         * [availBytes] = memoria libre del sistema (ActivityManager.MemoryInfo.availMem); [totalBytes] = RAM total;
         * [workingPixels] = presupuesto de píxeles del render ([DeviceTier.maxWorkingPixels]); [lowRam] = isLowRamDevice.
         */
        fun of(soc: SocProfile, mode: PerfMode, availBytes: Long, totalBytes: Long, workingPixels: Int, lowRam: Boolean): PerfPolicy {
            val total = soc.totalCores
            val perf = soc.performanceCores
            val fast = if (soc.homogeneous) min(total, 4) else perf
            val cv = when (mode) {
                PerfMode.MAX -> total
                PerfMode.BALANCED -> if (soc.homogeneous) max(1, total - 1) else max(2, perf).coerceAtMost(total)
                PerfMode.SAVER -> max(1, perf / 2)
            }
            val ort = when (mode) {
                PerfMode.MAX -> fast.coerceIn(1, 8)
                PerfMode.BALANCED -> (if (soc.homogeneous) min(total, 2) else max(min(2, perf), soc.bigCores)).coerceIn(1, 4)
                PerfMode.SAVER -> 1
            }
            val peak = workingPixels.toLong() * RENDER_BYTES_PER_PIXEL
            val gb = totalBytes / (1024.0 * 1024 * 1024)
            // dos renders a la vez solo con memoria libre de sobra (2 picos + 600 MB de margen para la app y la cámara)
            val roomForTwo = !lowRam && availBytes > 2 * peak + 600L * 1024 * 1024 && gb >= 5.5
            val pages = when (mode) {
                PerfMode.MAX -> if (roomForTwo && total >= 6) 2 else 1
                PerfMode.BALANCED -> if (roomForTwo && total >= 8 && gb >= 7.5) 2 else 1
                PerfMode.SAVER -> 1
            }
            val squeeze = when (mode) {
                PerfMode.MAX -> fast.coerceIn(1, 6)
                PerfMode.BALANCED -> max(1, fast / 2).coerceAtMost(4)
                PerfMode.SAVER -> 1
            }.let { if (lowRam) min(it, 2) else it }
            val prio = when (mode) {
                PerfMode.MAX -> PRIO_DISPLAY
                PerfMode.BALANCED -> PRIO_FOREGROUND
                PerfMode.SAVER -> PRIO_BACKGROUND
            }
            val bands = when (mode) {
                PerfMode.MAX -> total
                PerfMode.BALANCED -> max(2, perf).coerceAtMost(total)
                PerfMode.SAVER -> 1
            }
            return PerfPolicy(mode, cv.coerceAtLeast(1), ort, squeeze, pages, prio, bands.coerceAtLeast(1))
        }
    }
}
