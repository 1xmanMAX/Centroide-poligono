// Núcleo C++ del motor de IA (sin JNI): carga de modelo GGUF + proyector de visión (mtmd), plantilla de chat del
// propio GGUF, generación con imágenes, streaming, cancelación y cuantización. Lo usan llm_jni.cpp (Android) y la
// prueba de escritorio (tools/llm_host_test.cpp), así la lógica se valida con modelos reales fuera del móvil.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct mtmd_context;
struct common_chat_templates;

namespace llm {

struct LoadParams {
    std::string model_path;
    std::string mmproj_path;     // vacío = solo texto
    int  n_ctx           = 4096;
    int  n_batch         = 512;
    int  n_threads       = 4;
    int  n_threads_batch = 4;
    bool use_mmap        = true;
    int  image_min_tokens = 0;   // 0 = lo que diga el GGUF
    int  image_max_tokens = 0;   // 0 = lo que diga el GGUF (limitarlo = menos memoria/tiempo en gama baja)
};

struct Image {
    int width  = 0;
    int height = 0;
    std::vector<uint8_t> rgb;    // width*height*3, RGB entrelazado
};

struct GenParams {
    std::string prompt;
    std::string system;          // vacío = sin mensaje de sistema
    bool  use_chat_template = true;
    bool  enable_thinking   = false;
    int   max_tokens        = 512;
    float temperature       = 0.0f;  // <= 0 = codicioso (determinista)
    int   top_k             = 40;
    float top_p             = 0.95f;
    float min_p             = 0.05f;
    float repeat_penalty    = 1.0f;  // 1 = desactivado
    int   repeat_last_n     = 64;
    float presence_penalty  = 0.0f;  // 0 = desactivado (Qwen bbox_2d: 1.5 contra bucles de objetos)
    // true = detokenizar también los tokens de control (PaddleOCR-VL: <|LOC_n|> de Spotting son CONTROL y sin
    // esto las coordenadas desaparecen). El texto de los tokens de fin (eog) nunca se emite.
    bool  special           = false;
    uint32_t seed           = 0xFFFFFFFF;
    std::vector<std::string> stops;
};

enum StopReason : int {
    STOP_EOS = 0,
    STOP_MAX_TOKENS = 1,
    STOP_STRING = 2,
    STOP_CANCELLED = 3,
    STOP_CONTEXT_FULL = 4,
    STOP_CALLBACK = 5,
};

struct Stats {
    int    prompt_tokens = 0;   // incluye los tokens de imagen
    int    image_tokens  = 0;
    int    gen_tokens    = 0;
    double image_ms      = 0;   // codificar imágenes (CLIP/ViT) + decodificarlas en el LLM
    double prompt_ms     = 0;   // todo el prompt (texto + imágenes)
    double gen_ms        = 0;
    int    stop_reason   = STOP_EOS;
};

// Devuelve false para detener la generación.
using TextCallback = std::function<bool(const std::string & text_piece)>;

class Session {
public:
    static std::unique_ptr<Session> load(const LoadParams & p, std::string & err);
    ~Session();

    Session(const Session &) = delete;
    Session & operator=(const Session &) = delete;

    // No es reentrante: una generación a la vez por sesión (la capa Kotlin lo garantiza con un Mutex).
    // Devuelve true si terminó sin error (también si se canceló); el texto llega troceado por on_text en UTF-8
    // completo (nunca medio carácter).
    bool generate(const GenParams & g, const std::vector<Image> & images, const TextCallback & on_text,
                  Stats & stats, std::string & err);

    // Seguro desde cualquier hilo.
    void cancel() { cancel_.store(true); }

    bool has_vision() const { return mctx_ != nullptr; }
    std::string info() const;
    // Prompt final tras aplicar la plantilla (para depurar / tests).
    std::string format_prompt(const GenParams & g, int n_images, std::vector<std::string> * extra_stops) const;

private:
    Session() = default;
    static bool abort_cb(void * data);

    llama_model   * model_ = nullptr;
    llama_context * ctx_   = nullptr;
    mtmd_context  * mctx_  = nullptr;
    common_chat_templates * tmpls_ = nullptr;
    LoadParams params_;
    std::atomic<bool> cancel_{false};
};

// Inicializa los backends de ggml. lib_dir = carpeta con libggml-cpu-*.so (variantes dinámicas); puede ir vacía.
// Devuelve la descripción del sistema (características de CPU detectadas).
std::string init_backend(const std::string & lib_dir);

// Cuantiza un GGUF de texto (no el mmproj). ftype: valores de llama_ftype (7 = Q8_0, 15 = Q4_K_M).
// Devuelve "" si fue bien o el mensaje de error.
std::string quantize(const std::string & in_path, const std::string & out_path, int ftype, int n_threads);

// Utilidades puras (testeables sin modelo).
size_t utf8_complete_prefix(const std::string & s);
// Longitud del sufijo más largo de text que es prefijo (propio) de alguna parada.
size_t stop_holdback(const std::string & text, const std::vector<std::string> & stops);

} // namespace llm
