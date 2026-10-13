package com.scannerpromax.ai

/**
 * Catálogo de modelos de IA descargables (DATA-DRIVEN: cambiar una URL, un archivo o una cuantización es editar
 * solo este archivo; ModelManager, LlmEngine y la pantalla "Modelos IA" no saben nada de modelos concretos).
 *
 * URLs de Hugging Face: `https://huggingface.co/<repo>/resolve/main/<archivo>` (ver [hf]).
 * El sha256 es el "X-Linked-Etag" que devuelve Hugging Face para archivos LFS/Xet (= sha256 del archivo).
 */
object ModelCatalog {

    /** Lector de documentos: PaddleOCR-VL-1.5 (0,9B, Apache 2.0). Motor principal de reconocimiento. */
    val DOCUMENT_READER = AiModel(
        id = "paddleocr-vl-1.5",
        name = "Lector de documentos",
        technicalName = "PaddleOCR-VL-1.5 (0,9B)",
        license = "Apache 2.0",
        purpose = "Texto, tablas, fórmulas, gráficos y sellos con alta precisión; motor principal de reconocimiento.",
        description = "Modelo de visión especializado en documentos. Lee texto impreso y manuscrito, tablas, " +
            "fórmulas, gráficos y sellos, y puede devolver el texto con su posición en la página.",
        role = ModelRole.DOCUMENT_READER,
        files = listOf(
            ModelFile(
                kind = FileKind.LLM,
                fileName = "PaddleOCR-VL-1.5.gguf",
                url = hf("PaddlePaddle/PaddleOCR-VL-1.5-GGUF", "PaddleOCR-VL-1.5.gguf"),
                sizeBytes = 935_768_992L,
                sha256 = "299051d54faa065abc505cc39b8383ea338fd3020c775ea3e0ba514a7022328c",
            ),
            ModelFile(
                kind = FileKind.MMPROJ,
                fileName = "PaddleOCR-VL-1.5-mmproj.gguf",
                url = hf("PaddlePaddle/PaddleOCR-VL-1.5-GGUF", "PaddleOCR-VL-1.5-mmproj.gguf"),
                sizeBytes = 881_770_496L,
                sha256 = "e7f1a72400fba517046f90d964e2fa0f4dac7781ee3b1bc5d2022f5f8cecbd87",
            ),
        ),
        recommendedRamMb = 4096,
        minRamMb = 3072,
        // Los archivos oficiales vienen sin cuantizar: opcionalmente se cuantiza el LLM en el móvil tras la descarga
        // (el mmproj se deja tal cual: la visión es más sensible a la cuantización).
        onDeviceQuantization = OnDeviceQuantization(
            sourceFileName = "PaddleOCR-VL-1.5.gguf",
            outputFileName = "PaddleOCR-VL-1.5-Q8_0.gguf",
            ftype = QuantType.Q8_0,
        ),
        defaults = GenerationDefaults(
            temperature = 0f,
            maxTokens = 2048,
            contextSize = 6144,
            maxImageTokens = 0,
            maxImageSide = 1600,
            repeatPenalty = 1.0f,
            useChatTemplate = true,
            enableThinking = false,
        ),
        tasks = listOf(
            ModelTask("ocr", "Texto", "OCR:"),
            ModelTask("table", "Tabla", "Table Recognition:"),
            ModelTask("formula", "Fórmula", "Formula Recognition:"),
            ModelTask("chart", "Gráfico", "Chart Recognition:"),
            ModelTask("seal", "Sello", "Seal Recognition:"),
            ModelTask("spotting", "Texto con posición", "Spotting:"),
        ),
    )

    /** Asistente visual: Qwen3.5-0.8B (Apache 2.0), multimodal general (híbrido Gated DeltaNet). */
    val VISUAL_ASSISTANT = AiModel(
        id = "qwen3.5-0.8b",
        name = "Asistente visual",
        technicalName = "Qwen3.5-0.8B (Q4_K_M)",
        license = "Apache 2.0",
        purpose = "Encuadre y desdoblado guiado, maquetación para reconstruir el PDF, reescritura, extracción de " +
            "datos (DNI, boletas), mejora del documento.",
        description = "Modelo de visión y lenguaje general: entiende la página, sigue instrucciones en español, " +
            "reescribe y extrae datos estructurados.",
        role = ModelRole.VISUAL_ASSISTANT,
        files = listOf(
            ModelFile(
                kind = FileKind.LLM,
                fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
                url = hf("unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf"),
                sizeBytes = 532_517_120L,
                sha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
            ),
            ModelFile(
                kind = FileKind.MMPROJ,
                fileName = "mmproj-F16.gguf",
                url = hf("unsloth/Qwen3.5-0.8B-GGUF", "mmproj-F16.gguf"),
                sizeBytes = 204_987_232L,
                sha256 = "56e4c6cfe73b0c82e3e82bc518d7591997e61d81f723fc41a586f4fa69ea2453",
            ),
        ),
        recommendedRamMb = 3072,
        minRamMb = 2048,
        onDeviceQuantization = null,
        defaults = GenerationDefaults(
            temperature = 0f,
            maxTokens = 1024,
            contextSize = 4096,
            maxImageTokens = 1024,
            maxImageSide = 1280,
            repeatPenalty = 1.05f,
            useChatTemplate = true,
            enableThinking = false,
        ),
        tasks = listOf(
            ModelTask("transcribe", "Transcribir", "Transcribe todo el texto de la imagen, respetando los saltos de línea."),
            ModelTask("describe", "Describir", "Describe brevemente qué tipo de documento es y qué contiene."),
            ModelTask("extract", "Extraer datos", "Extrae los datos clave del documento (nombres, fechas, importes, números de documento) en formato JSON."),
        ),
    )

    val all: List<AiModel> = listOf(DOCUMENT_READER, VISUAL_ASSISTANT)

    fun byId(id: String): AiModel? = all.firstOrNull { it.id == id }

    fun forRole(role: ModelRole): AiModel? = all.firstOrNull { it.role == role }

    fun hf(repo: String, file: String, revision: String = "main"): String =
        "https://huggingface.co/$repo/resolve/$revision/$file"
}

enum class ModelRole { DOCUMENT_READER, VISUAL_ASSISTANT }

enum class FileKind { LLM, MMPROJ }

/** Valores de llama_ftype de llama.cpp. */
enum class QuantType(val ftype: Int, val label: String) {
    Q8_0(7, "Q8_0"),
    Q6_K(18, "Q6_K"),
    Q4_K_M(15, "Q4_K_M"),
}

data class ModelFile(
    val kind: FileKind,
    val fileName: String,
    val url: String,
    /** Tamaño exacto esperado en bytes (se verifica al terminar). <= 0 = desconocido (no se verifica). */
    val sizeBytes: Long,
    /** sha256 en hexadecimal minúsculas, o null para no verificar el contenido. */
    val sha256: String? = null,
)

data class OnDeviceQuantization(
    val sourceFileName: String,
    val outputFileName: String,
    val ftype: QuantType,
)

data class GenerationDefaults(
    val temperature: Float,
    val maxTokens: Int,
    val contextSize: Int,
    /** Límite de tokens por imagen (0 = lo que diga el GGUF). */
    val maxImageTokens: Int,
    /** Lado mayor máximo de la imagen antes de pasarla al modelo (px). */
    val maxImageSide: Int,
    val repeatPenalty: Float,
    val useChatTemplate: Boolean,
    val enableThinking: Boolean,
)

/** Tarea predefinida (prompt) para la prueba y para la tubería. */
data class ModelTask(val id: String, val label: String, val prompt: String)

data class AiModel(
    val id: String,
    val name: String,
    val technicalName: String,
    val license: String,
    val purpose: String,
    val description: String,
    val role: ModelRole,
    val files: List<ModelFile>,
    val recommendedRamMb: Int,
    val minRamMb: Int,
    val onDeviceQuantization: OnDeviceQuantization?,
    val defaults: GenerationDefaults,
    val tasks: List<ModelTask>,
) {
    val totalDownloadBytes: Long get() = files.sumOf { it.sizeBytes.coerceAtLeast(0) }
    val llmFile: ModelFile get() = files.first { it.kind == FileKind.LLM }
    val mmprojFile: ModelFile? get() = files.firstOrNull { it.kind == FileKind.MMPROJ }
}

/** Motor de reconocimiento principal elegido por el usuario (Ajustes > Modelos IA). */
enum class RecognitionEngine(val label: String, val description: String, val role: ModelRole?) {
    DOCUMENT_READER(
        "Lector de documentos (PaddleOCR-VL)",
        "Máxima precisión en texto, tablas y fórmulas. Requiere descargar el modelo.",
        ModelRole.DOCUMENT_READER,
    ),
    VISUAL_ASSISTANT(
        "Asistente visual (Qwen)",
        "Entiende y reescribe la página. Requiere descargar el modelo.",
        ModelRole.VISUAL_ASSISTANT,
    ),
    BASIC(
        "Básico sin descarga (ML Kit)",
        "Reconocimiento rápido incluido en la app, menos preciso.",
        null,
    ),
}
