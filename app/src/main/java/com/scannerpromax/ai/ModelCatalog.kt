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
     * ONNX Runtime nativo (libonnxruntime.so, ~13 MB comprimido por ABI) FUERA del APK: se baja con la maquetación
     * desde el AAR oficial de Maven Central, solo la entrada de la ABI del proceso (petición HTTP Range sobre el ZIP,
     * ~12–15 MB en vez de los 55 MB del AAR). La API Java de ORT y su puente JNI pequeño (libonnxruntime4j_jni.so,
     * ~36 KB) siguen en el APK: en Android OnnxRuntime.init() hace System.loadLibrary("onnxruntime4j_jni") e ignora
     * "onnxruntime.native.path"; precargando antes libonnxruntime.so con System.load el enlazador resuelve la
     * dependencia por su soname (ver parts/OrtNative). La versión DEBE coincidir con la dependencia de Gradle
     * (onnxruntime-android) — lo comprueba ModelCatalogTest. Tamaños/sha256 verificados con curl el 2026-10-15
     * (AAR: 55 138 854 B, sha256 72683787…40a7, sha1 09e43472…091e = .sha1 de Maven Central).
     */
    val ORT_NATIVE = NativeLibs(
        version = ORT_VERSION,
        url = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION/" +
            "onnxruntime-android-$ORT_VERSION.aar",
        archiveSize = 55_138_854L,
        archiveSha256 = "72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7",
        libs = listOf(
            NativeLib("arm64-v8a", "libonnxruntime.so", 34_208_680L, 12_887_720L,
                "8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0"),
            NativeLib("armeabi-v7a", "libonnxruntime.so", 24_182_012L, 11_639_973L,
                "2a63f9a56b211511209dbf4b5bc143f18d0b365977dd7a0afd57d95c85479af6"),
            NativeLib("x86_64", "libonnxruntime.so", 40_827_944L, 14_993_057L,
                "068896846a7fe679cf780c1c532745286d9ef2e469f3dbde5bb9cf2c02095b18"),
        ),
    )

    /**
     * Maquetación de páginas: PP-DocLayoutV3 (detector RT-DETR con orden de lectura, Apache 2.0) + PP-LCNet doc_ori
     * (orientación). No es un modelo de lenguaje: se ejecuta con ONNX Runtime (su librería nativa se baja con el modelo: [ORT_NATIVE]) / OpenCV DNN en
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
        description = "Detector de maquetación de documentos (no lee texto): cada región va a la herramienta adecuada. " +
            "Incluye el motor ONNX Runtime para tu procesador (~13 MB).",
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
        nativeLibs = ORT_NATIVE,
    )

    /**
     * Lector rápido: Indra OCR de Datalab (github.com/datalab-to/indra, pesos v1.1.0 en datalab-to/indra-ocr), solo lo
     * necesario para latín (incluye manuscrito latino): detector de líneas, reconocedor CTC, orden de lectura +
     * bloques de maquetación, orientación y modelo de lenguaje n-grama para las líneas dudosas (≈25 MB; el paquete
     * completo son 76 MB con CJK/RTL/índicas). Se ejecuta con ONNX Runtime ([ORT_NATIVE], compartido con la
     * Maquetación: si ya está, no se baja otra vez). Banco (notes_indra.md, 54 imágenes): CER 27,7 / %palabras 76,2
     * frente a 53,8 / 46,5 de Tesseract (proxy de ML Kit) y 28,8 / 80,3 de PaddleOCR-VL Q8, a ~0,4 s por página en el
     * PC (Paddle ~110 s). Manuscrito peor que el Lector de documentos (GNHK 49,7 vs 72,4 % de palabras).
     * LICENCIA de los pesos: OpenRAIL-M modificada (gratis para uso personal/investigación/empresas < 20 M$ y sin
     * competir con la API de Datalab); una distribución comercial de la app necesitaría licencia de Datalab.
     * URLs fijadas al commit de la etiqueta v1.1.0 (sha256 = X-Linked-Etag/sha256 verificados el 2026-10-25).
     */
    val FAST_READER = AiModel(
        id = "indra-ocr",
        name = "Lector rápido Indra",
        technicalName = "Indra OCR v1.1 (ONNX, latín)",
        license = "Pesos OpenRAIL-M modificada (uso personal) · código Apache 2.0",
        purpose = "Sustituye al OCR básico: lee texto impreso (y manuscrito sencillo) en ~1 s por página, con líneas, " +
            "palabras, orden de lectura y bloques. Mucho más preciso que el básico incluido (ML Kit).",
        description = "OCR ligero en el procesador (sin GPU): detector de líneas, reconocedor y orden de lectura de " +
            "Datalab. Incluye el motor ONNX Runtime (~13 MB) si no lo descargaste con la Maquetación.",
        role = ModelRole.FAST_READER,
        files = listOf(
            indra("det.onnx", 389_948L, "f2bd4cba23e9c328cf7ec824634e423e191a2c6aacbbf35c62bd79dd70f62f59"),
            indra("rec.onnx", 5_042_161L, "475972a2710be4a3cbfeb2bc7f3624ffd563de07de42476fc7a290d4e1ece484"),
            indra("order.onnx", 2_973_138L, "a42db2e8b536ad3d84045a4b7f9b0a72667cf8be4aa02d63f7b38004719fb180"),
            indra("orient.onnx", 371_802L, "20eaf670f55a7cf9facfe1bdd6c7b8e40be75bbf4d48a3df43f66aa3bbd874fd"),
            indra("meta.json", 211_869L, "4ce4c94b46f5d644033c02e718322014ff4081270f385038c571912b56696339", FileKind.DATA),
            indra("lm.npz", 15_695_957L, "6c0fb618a363a0acdd05d4f5b0edd9e8146d2d990541b87f6130bbfe17cea58d", FileKind.DATA),
        ),
        recommendedRamMb = 2048,
        minRamMb = 1024,
        onDeviceQuantization = null,
        defaults = GenerationDefaults(
            temperature = 0f, maxTokens = 0, contextSize = 0, maxImageTokens = 0, maxImageSide = 0,
            repeatPenalty = 1f, useChatTemplate = false, enableThinking = false,
        ),
        tasks = emptyList(),
        nativeLibs = ORT_NATIVE,
    )

    /** Commit de la etiqueta v1.1.0 de datalab-to/indra-ocr (los archivos de una etiqueta no cambian). */
    const val INDRA_REVISION = "bd02167fdf2005a19962cc15188f8496a2c87d3b"

    private fun indra(file: String, size: Long, sha: String, kind: FileKind = FileKind.ONNX) =
        ModelFile(kind = kind, fileName = file, url = hf("datalab-to/indra-ocr", file, INDRA_REVISION), sizeBytes = size, sha256 = sha)

    const val ORT_VERSION = "1.31.0"

    const val LAYOUT_FILE = "pp_doclayoutv3.onnx"
    const val ORIENTATION_FILE = "pp_lcnet_doc_ori.onnx"

    val all: List<AiModel> = listOf(FAST_READER, DOCUMENT_READER, VISUAL_ASSISTANT, PAGE_LAYOUT)

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
    /** OCR rápido ONNX (Indra): sustituye a ML Kit en el reconocimiento Básico cuando está descargado. */
    FAST_READER,
}

/** DATA = otros archivos del modelo (meta.json, tablas del modelo de lenguaje). */
enum class FileKind { LLM, MMPROJ, ONNX, DATA }

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

/**
 * Librerías nativas dentro de un ZIP/AAR remoto ([url]): cada una es la entrada `jni/<abi>/<fileName>` y se guarda en
 * `<modelo>/lib/<abi>/<fileName>`. [NativeLib.sizeBytes]/[NativeLib.sha256] = archivo descomprimido;
 * [NativeLib.compressedBytes] = lo que se baja.
 */
data class NativeLibs(
    val version: String,
    val url: String,
    val archiveSize: Long,
    val archiveSha256: String,
    val libs: List<NativeLib>,
) {
    fun forAbi(abi: String?): List<NativeLib>? = libs.filter { it.abi == abi }.takeIf { it.isNotEmpty() }
    val abis: List<String> get() = libs.map { it.abi }.distinct()
}

data class NativeLib(
    val abi: String,
    val fileName: String,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val sha256: String,
) {
    val entryName: String get() = "jni/$abi/$fileName"
}

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
    /** Librerías nativas que se bajan con el modelo (solo las de la ABI del dispositivo), o null. */
    val nativeLibs: NativeLibs? = null,
) {
    /** Bytes a bajar en este dispositivo: archivos + librerías nativas comprimidas de su ABI ([NativeAbi.current]). */
    val totalDownloadBytes: Long get() = downloadBytes(NativeAbi.current)

    fun downloadBytes(abi: String?): Long =
        files.sumOf { it.sizeBytes.coerceAtLeast(0) } + (nativeLibs?.forAbi(abi)?.sumOf { it.compressedBytes } ?: 0L)
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
        "Básico rápido (Indra / ML Kit)",
        "Lector rápido Indra si está descargado (~1 s por página, mucho más preciso); si no, ML Kit incluido en la app.",
        null,
    ),
    ;

    val isLocalModel: Boolean get() = role != null
}
