package com.scannerpromax.ai

/**
 * Catálogo de modelos descargables (DATA-DRIVEN: cambiar una URL o un archivo es editar solo este archivo;
 * ModelManager y la pantalla "Reconocimiento" no saben nada de modelos concretos). Todos se ejecutan con ONNX Runtime:
 * [FAST_READER] = Indra, el único OCR (obligatorio, se baja solo) y [PAGE_LAYOUT] = maquetación por partes (opcional).
 *
 * URLs de Hugging Face: `https://huggingface.co/<repo>/resolve/main/<archivo>` (ver [hf]).
 * El sha256 es el "X-Linked-Etag" que devuelve Hugging Face para archivos LFS/Xet (= sha256 del archivo).
 */
object ModelCatalog {

    /**
     * Maquetación de páginas: PP-DocLayoutV3 (detector RT-DETR con orden de lectura, Apache 2.0) + PP-LCNet doc_ori
     * (orientación). No es un modelo de lenguaje: se ejecuta con ONNX Runtime (motor incluido en la app; la librería nativa va en el AAB/APK, nunca se descarga) / OpenCV DNN en
     * ~1–3 s por página. Banco recon2: bloques DocLayNet recall 0,72 frente a 0,34 de la maquetación clásica; páginas
     * giradas 94/96. Variante fp32 oficial (la int8 total pierde 7 puntos de recall y la int8-MatMul no está publicada).
     */
    val PAGE_LAYOUT = AiModel(
        id = "pp-doclayoutv3",
        name = "Maquetación de páginas",
        technicalName = "PP-DocLayoutV3 + PP-LCNet doc_ori (ONNX)",
        license = "Apache 2.0",
        purpose = "Reconstruye el documento POR PARTES: localiza títulos, párrafos, tablas, gráficos, fórmulas, imágenes, " +
            "cabeceras y pies con su orden de lectura, y endereza las páginas giradas. Mejora el PDF reconstruido " +
            "(texto de Indra colocado región a región).",
        description = "Detector de maquetación de documentos (no lee texto): cada región va a la herramienta adecuada. " +
            "Solo se descargan los pesos (datos); el motor ONNX Runtime ya va incluido en la app.",
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
    )

    /**
     * Lector de texto (ÚNICO OCR de la app): Indra OCR de Datalab (github.com/datalab-to/indra, pesos v1.1.0 en datalab-to/indra-ocr), solo lo
     * necesario para latín (incluye manuscrito latino): detector de líneas, reconocedor CTC, orden de lectura +
     * bloques de maquetación, orientación y modelo de lenguaje n-grama para las líneas dudosas (≈25 MB; el paquete
     * completo son 76 MB con CJK/RTL/índicas). Se ejecuta con ONNX Runtime (incluido en la app; solo se
     * descargan los pesos). Banco (notes_indra.md, 54 imágenes): CER 27,7 / %palabras 76,2
     * frente a 53,8 / 46,5 de Tesseract (≈ ML Kit, el OCR anterior), a ~0,4 s por página en el PC. Se descarga sola en
     * el primer arranque (ocr/indra/IndraProvisioner).
     * LICENCIA de los pesos: OpenRAIL-M modificada (gratis para uso personal/investigación/empresas < 20 M$ y sin
     * competir con la API de Datalab); una distribución comercial de la app necesitaría licencia de Datalab.
     * URLs fijadas al commit de la etiqueta v1.1.0 (sha256 = X-Linked-Etag/sha256 verificados el 2026-10-25).
     */
    val FAST_READER = AiModel(
        id = "indra-ocr",
        name = "Lector de texto Indra",
        technicalName = "Indra OCR v1.1 (ONNX, latín)",
        license = "Pesos OpenRAIL-M modificada (uso personal) · código Apache 2.0",
        purpose = "El lector de texto de la app: reconoce texto impreso (y manuscrito sencillo) en ~1 s por página, con " +
            "líneas, palabras, orden de lectura y bloques. Hace posible el Texto en vivo, la búsqueda y el PDF buscable.",
        description = "OCR ligero en el procesador (sin GPU): detector de líneas, reconocedor y orden de lectura de " +
            "Datalab. Solo se descargan los pesos; el motor ONNX Runtime va incluido en la app.",
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
    )

    /** Commit de la etiqueta v1.1.0 de datalab-to/indra-ocr (los archivos de una etiqueta no cambian). */
    const val INDRA_REVISION = "bd02167fdf2005a19962cc15188f8496a2c87d3b"

    private fun indra(file: String, size: Long, sha: String, kind: FileKind = FileKind.ONNX) =
        ModelFile(kind = kind, fileName = file, url = hf("datalab-to/indra-ocr", file, INDRA_REVISION), sizeBytes = size, sha256 = sha)

    const val ORT_VERSION = "1.31.0"

    const val LAYOUT_FILE = "pp_doclayoutv3.onnx"
    const val ORIENTATION_FILE = "pp_lcnet_doc_ori.onnx"

    val all: List<AiModel> = listOf(FAST_READER, PAGE_LAYOUT)

    fun byId(id: String): AiModel? = all.firstOrNull { it.id == id }

    fun forRole(role: ModelRole): AiModel? = all.firstOrNull { it.role == role }

    fun hf(repo: String, file: String, revision: String = "main"): String =
        "https://huggingface.co/$repo/resolve/$revision/$file"
}

enum class ModelRole {
    /** Detector de maquetación + orientación (ONNX; no lee texto: usa las líneas de Indra). */
    PAGE_LAYOUT,
    /** OCR ONNX (Indra): el único lector de texto de la app. */
    FAST_READER,
}

/** DATA = otros archivos del modelo (meta.json, tablas del modelo de lenguaje). */
enum class FileKind { ONNX, DATA }

data class ModelFile(
    val kind: FileKind,
    val fileName: String,
    val url: String,
    /** Tamaño exacto esperado en bytes (se verifica al terminar). <= 0 = desconocido (no se verifica). */
    val sizeBytes: Long,
    /** sha256 en hexadecimal minúsculas, o null para no verificar el contenido. */
    val sha256: String? = null,
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
) {
    /**
     * Bytes a bajar: SOLO archivos de datos (pesos ONNX, tablas). Nunca código ejecutable: ONNX Runtime nativo va en el
     * AAB/APK (política de Google Play sobre descarga de código).
     */
    val totalDownloadBytes: Long get() = files.sumOf { it.sizeBytes.coerceAtLeast(0) }
}
