package com.scannerpromax.ai

import java.io.File

/** Hilos recomendados para ONNX Runtime (Indra, maquetación): núcleos "grandes" según la frecuencia máxima de cada CPU (big.LITTLE). */
object CpuInfo {
    fun recommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until cores).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        return threadsFor(cores, freqs)
    }

    /** Lógica pura (testeable): núcleos con frecuencia >= 75 % de la máxima, entre 2 y 6. */
    fun threadsFor(cores: Int, maxFreqs: List<Long>): Int {
        if (cores <= 2) return cores.coerceAtLeast(1)
        if (maxFreqs.size < cores || maxFreqs.all { it <= 0 }) return (cores / 2).coerceIn(2, 4)
        val top = maxFreqs.max()
        val big = maxFreqs.count { it >= top * 0.75 }
        return big.coerceIn(2, minOf(6, cores))
    }
}
