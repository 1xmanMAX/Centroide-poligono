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
        // Banco IA (notes_ia_banco.md §2): LLM ya cuantizado a Q8_0 (Q4_K_M pierde calidad: %palabras 71,9 vs 77,7) y
        // proyector en F16 (el BF16 oficial es lento en ARM sin BF16). Repo comunitario con licencia Apache-2.0
        // declarada; tamaños y sha256 = X-Linked-Size / X-Linked-Etag de Hugging Face (verificados 2026-10-13).
        // Alternativa oficial: PaddlePaddle/PaddleOCR-VL-1.5-GGUF (BF16 935 768 992 B, sha 299051d5…328c) + cuantizar
        // en el móvil con [OnDeviceQuantization] (936 MB de descarga en vez de 498).
        files = listOf(
            ModelFile(
                kind = FileKind.LLM,
                fileName = "PaddleOCR-VL-1.5-Q8_0.gguf",
                url = hf("JamePeng2023/PaddleOCR-VL-1.5-GGUF", "PaddleOCR-VL-1.5-Q8_0.gguf"),
                sizeBytes = 498_315_840L,
                sha256 = "dfbcf5588cbd2182d3e7e6c5c0139f509c6dccc7fe4a8406eef361cfeec70922",
            ),
            ModelFile(
                kind = FileKind.MMPROJ,
                fileName = "mmproj-F16.gguf",
                url = hf("JamePeng2023/PaddleOCR-VL-1.5-GGUF", "mmproj-F16.gguf"),
                sizeBytes = 880_415_744L,
                sha256 = "68cf6bc6c4c54e3c177a080b37fdffe80b1193ae82c813fa1750d5172c311fca",
            ),
        ),
        recommendedRamMb = 4096,
        minRamMb = 3072,
        // Ya viene en Q8_0: no hace falta cuantizar en el móvil.
        onDeviceQuantization = null,
        defaults = GenerationDefaults(
            temperature = 0f,
            maxTokens = 2048,
            // Spotting necesita 6144 (≈1280 tokens de imagen + hasta 3000 de salida); el resto cabe en 4096. Se carga
            // una vez para toda la cola con el mayor.
            contextSize = 6144,
            // Paddle ignora image_max_tokens (usa image_max_pixels = 1 003 520 del GGUF): se reduce la imagen antes.
            maxImageTokens = 0,
            maxImageSide = 1600,
            maxImagePixels = PADDLE_MAX_PIXELS,
            repeatPenalty = 1.0f,
            useChatTemplate = true,
            enableThinking = false,
            specialTokens = true,
        ),
        tasks = listOf(
            ModelTask("ocr", "Texto", "OCR:", maxTokens = 2048),
            ModelTask("table", "Tabla", "Table Recognition:", maxTokens = 3000),
            ModelTask("formula", "Fórmula", "Formula Recognition:", maxTokens = 256),
            ModelTask("chart", "Gráfico", "Chart Recognition:", maxTokens = 512),
            ModelTask("seal", "Sello", "Seal Recognition:", maxTokens = 512),
            ModelTask("spotting", "Texto con posición", "Spotting:", maxTokens = 3000, maxImagePixels = PADDLE_SPOTTING_MAX_PIXELS),
        ),
    )

    /** Asistente visual: Qwen3.5-0.8B (Apache 2.0), multimodal general (híbrido Gated DeltaNet). */
    val VISUAL_ASSISTANT = AiModel(
        id = "qwen3.5-0.8b",
        name = "Asistente visual",
        technicalName = "Qwen3.5-0.8B (Q4_K_M)",
        license = "Apache 2.0",
        purpose = "Reconoce el tipo de documento para elegir la mejor mejora, rescata el encuadre cuando no se " +
            "detectan los bordes y puede leer el texto (tiques impresos).",
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
            // Su mmproj no trae image_max_pixels: sin límite una foto grande da ~4100 tokens de imagen.
            maxImageTokens = 1024,
            maxImageSide = 1280,
            repeatPenalty = 1.0f,
            useChatTemplate = true,
            enableThinking = false,
        ),
        tasks = listOf(
            ModelTask("ocr", "Transcribir", QWEN_OCR_PROMPT, maxTokens = 2048),
            ModelTask("classify", "Tipo de documento", QWEN_CLASSIFY_PROMPT, maxTokens = 8, maxImageSide = 512),
            ModelTask("docbox", "Localizar documento", QWEN_DOCBOX_PROMPT, maxTokens = 128, maxImageSide = 896, presencePenalty = 1.5f),
            ModelTask("describe", "Describir", "Describe brevemente qué tipo de documento es y qué contiene."),
            ModelTask("extract", "Extraer datos", "Extrae los datos clave del documento (nombres, fechas, importes, números de documento) en formato JSON."),
        ),
    )

    /**
     * Maquetación de páginas: PP-DocLayoutV3 (detector RT-DETR con orden de lectura, Apache 2.0) + PP-LCNet doc_ori
     * (orientación). No es un modelo de lenguaje: se ejecuta con ONNX Runtime / OpenCV DNN (incluidos en la app) en
     * ~1–3 s por página. Banco recon2: bloques DocLayNet recall 0,72 frente a 0,34 de la maquetación clásica; páginas
     * giradas 94/96. Variante fp32 oficial (la int8 total pierde 7 puntos de recall y la int8-MatMul no está publicada).
     */
    val PAGE_LAYOUT = AiModel(
        id = "pp-doclayoutv3",
        name = "Maquetación de páginas",
        technicalName = "PP-DocLayoutV3 + PP-LCNet doc_ori (ONNX)",
        license = "Apache 2.0",
        purpose = "Reconstruye el documento POR PARTES: localiza títulos, párrafos, tablas, gráficos, fórmulas, imágenes, " +
            "cabeceras y pies con su orden de lectura, y endereza las páginas giradas. Mejora mucho el PDF reconstruido " +
            "con el Lector de documentos y con la IA en la nube.",
        description = "Detector de maquetación de documentos (no lee texto): cada región va a la herramienta adecuada.",
        role = ModelRole.PAGE_LAYOUT,
        files = listOf(
            ModelFile(
                kind = FileKind.ONNX,
                fileName = LAYOUT_FILE,
                url = hf("PaddlePaddle/PP-DocLayoutV3_onnx", "inference.onnx"),
                sizeBytes = 130_502_049L,
                sha256 = "45bf71750b00739a41fc209f132eb104a4d6b5bb29483c9078164d8b87cf28ba",
            ),
            ModelFile(
                kind = FileKind.ONNX,
                fileName = ORIENTATION_FILE,
                url = hf("PaddlePaddle/PP-LCNet_x1_0_doc_ori_onnx", "inference.onnx"),
                sizeBytes = 6_788_069L,
                sha256 = "af9a0a4f317ff0709ce752067807f819cb15d883f8ecad89f28df1c6ee2d9c92",
            ),
        ),
        recommendedRamMb = 3072,
        minRamMb = 2048,
        onDeviceQuantization = null,
        defaults = GenerationDefaults(
            temperature = 0f, maxTokens = 0, contextSize = 0, maxImageTokens = 0, maxImageSide = 800,
            repeatPenalty = 1f, useChatTemplate = false, enableThinking = false,
        ),
        tasks = emptyList(),
    )

    const val LAYOUT_FILE = "pp_doclayoutv3.onnx"
    const val ORIENTATION_FILE = "pp_lcnet_doc_ori.onnx"

    val all: List<AiModel> = listOf(DOCUMENT_READER, VISUAL_ASSISTANT, PAGE_LAYOUT)

    /** Píxeles máximos de imagen para Paddle (image_max_pixels del GGUF = 1280 tokens). */
    const val PADDLE_MAX_PIXELS = 1_003_520
    /**
     * Spotting: el banco usó 1 605 632 (2048 tokens), pero mtmd limita Paddle a image_max_pixels del GGUF; se manda a
     * esta resolución y el modelo la reduce a ~1 MP internamente (las coordenadas son relativas 0..1000).
     */
    const val PADDLE_SPOTTING_MAX_PIXELS = 1_605_632

    /** Prompts EXACTOS del banco (ia_eval/prompts). */
    const val QWEN_CLASSIFY_PROMPT = "What kind of image is this? Choose exactly one category:\n" +
        "receipt, id_card, handwritten, whiteboard, screen, book, form, printed, no_document.\n" +
        "(receipt = shop ticket or invoice; id_card = ID, licence or bank card; handwritten = handwritten notes or letter; " +
        "whiteboard = whiteboard or blackboard; screen = photo of a computer or phone screen; book = open book or book page; " +
        "form = form or table to fill in; printed = other printed document or letter; no_document = no document in the photo)\n" +
        "Answer with the category name only."
    const val QWEN_DOCBOX_PROMPT = "Locate the document (sheet of paper, receipt, card or book page) in this photo. " +
        "Report its bbox coordinates in JSON format with \"bbox_2d\" and \"label\"."
    const val QWEN_OCR_PROMPT = "Read all the text in this image. Output only the text, line by line, in reading order."

    fun byId(id: String): AiModel? = all.firstOrNull { it.id == id }

    fun forRole(role: ModelRole): AiModel? = all.firstOrNull { it.role == role }

    fun hf(repo: String, file: String, revision: String = "main"): String =
        "https://huggingface.co/$repo/resolve/$revision/$file"
}

enum class ModelRole {
    DOCUMENT_READER, VISUAL_ASSISTANT,
    /** Detector de maquetación + orientación (ONNX, no es un LLM; no es un motor de reconocimiento por sí solo). */
    PAGE_LAYOUT,
}

enum class FileKind { LLM, MMPROJ, ONNX }

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
    /** Máximo de píxeles (ancho·alto) de la imagen (0 = solo [maxImageSide]). */
    val maxImagePixels: Int = 0,
    /** Detokenizar con special=true (Paddle: coordenadas <|LOC_n|> de Spotting). */
    val specialTokens: Boolean = false,
)

/**
 * Tarea predefinida (prompt) para la prueba y para la tubería, con sus parámetros del banco (0 = los del modelo).
 */
data class ModelTask(
    val id: String,
    val label: String,
    val prompt: String,
    val maxTokens: Int = 0,
    val maxImageSide: Int = 0,
    val maxImagePixels: Int = 0,
    val presencePenalty: Float = 0f,
)

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
    /** ¿Es un modelo de lenguaje (llama.cpp)? Los ONNX (maquetación) no se cargan en LlmEngine ni se prueban con prompts. */
    val isLlm: Boolean get() = files.any { it.kind == FileKind.LLM }
    val mmprojFile: ModelFile? get() = files.firstOrNull { it.kind == FileKind.MMPROJ }

    fun task(id: String): ModelTask? = tasks.firstOrNull { it.id == id }
}

/**
 * Motor de reconocimiento principal elegido por el usuario (Ajustes > Modelos IA / IA en la nube). [role] = modelo
 * local descargable; [cloud] = proveedor en la nube con la clave del usuario; ambos null = Básico (ML Kit).
 */
enum class RecognitionEngine(
    val label: String,
    val description: String,
    val role: ModelRole?,
    val cloud: com.scannerpromax.ai.cloud.CloudProviderId? = null,
) {
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
    NUBE_DEEPSEEK(
        "IA en la nube: DeepSeek",
        "Envía cada página a DeepSeek (servidores en China) con tu clave de API. Requiere internet.",
        null,
        com.scannerpromax.ai.cloud.CloudProviderId.DEEPSEEK,
    ),
    NUBE_CLAUDE_HAIKU(
        "IA en la nube: Claude Haiku 5.5",
        "Envía cada página a Anthropic (servidores en EE. UU.) con tu clave de API. Requiere internet.",
        null,
        com.scannerpromax.ai.cloud.CloudProviderId.CLAUDE_HAIKU,
    ),
    BASIC(
        "Básico sin descarga (ML Kit)",
        "Reconocimiento rápido incluido en la app, menos preciso.",
        null,
    ),
    ;

    val isLocalModel: Boolean get() = role != null
}
