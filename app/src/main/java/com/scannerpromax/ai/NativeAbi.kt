package com.scannerpromax.ai

/**
 * ABI de las librerías nativas que carga ESTE proceso (la del APK instalado), para bajar las de ONNX Runtime que
 * le sirven. No basta con Build.SUPPORTED_ABIS[0]: un teléfono arm64 con el APK armeabi-v7a corre en 32 bits y
 * solo puede cargar librerías de 32 bits (el puente JNI de ORT del APK es de esa ABI).
 */
object NativeAbi {
    /** Para tests: fija la ABI (null = detectar). */
    @Volatile var override: String? = null

    private val detected: String? by lazy { runCatching { detect() }.getOrNull() }

    /** ABI del proceso, o null si no se puede saber (JVM de los tests). */
    val current: String? get() = override ?: detected

    private fun detect(): String? {
        val is64 = android.os.Process.is64Bit()
        return choose(
            is64 = is64,
            abis64 = android.os.Build.SUPPORTED_64_BIT_ABIS?.toList().orEmpty(),
            abis32 = android.os.Build.SUPPORTED_32_BIT_ABIS?.toList().orEmpty(),
            all = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )
    }

    /** Primera ABI del dispositivo con el mismo número de bits que el proceso (o la primera, si no se sabe). */
    fun choose(is64: Boolean?, abis64: List<String>, abis32: List<String>, all: List<String>): String? = when (is64) {
        true -> abis64.firstOrNull()
        false -> abis32.firstOrNull()
        null -> null
    } ?: all.firstOrNull()
}
