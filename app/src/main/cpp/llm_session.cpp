#include "llm_session.h"

#include "chat.h"
#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <exception>
#include <mutex>

namespace llm {

namespace {

using clock_type = std::chrono::steady_clock;

double ms_since(clock_type::time_point t0) {
    return std::chrono::duration<double, std::milli>(clock_type::now() - t0).count();
}

std::once_flag g_backend_once;
std::string g_system_info;

std::string token_to_piece(const llama_vocab * vocab, llama_token tok, bool special) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, special);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab, tok, big.data(), (int) big.size(), 0, special);
        if (n < 0) return {};
        big.resize(n);
        return big;
    }
    return std::string(buf, n);
}

} // namespace

// ------------------------------------------------------------------------------------------------ utilidades

size_t utf8_complete_prefix(const std::string & s) {
    // Recorre hacia atrás como mucho 4 bytes buscando el inicio de la última secuencia.
    const size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {  // byte inicial (o ASCII)
            size_t need;
            if (c < 0x80) need = 1;
            else if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            else return n;  // byte inválido: no retener nada
            return (n - (i - 1) >= need) ? n : (i - 1);
        }
        --i;
        ++back;
    }
    return n;  // demasiados bytes de continuación seguidos: inválido, no retener
}

size_t stop_holdback(const std::string & text, const std::vector<std::string> & stops) {
    size_t best = 0;
    for (const auto & s : stops) {
        if (s.empty()) continue;
        const size_t max_len = std::min(text.size(), s.size() - 1);
        for (size_t len = max_len; len > best; --len) {
            if (text.compare(text.size() - len, len, s, 0, len) == 0) {
                best = len;
                break;
            }
        }
    }
    return best;
}

// ------------------------------------------------------------------------------------------------ backend

std::string init_backend(const std::string & lib_dir) {
    std::call_once(g_backend_once, [&]() {
        llama_backend_init();
#if defined(LLM_BACKEND_DL) && LLM_BACKEND_DL
        // Variantes del backend CPU (libggml-cpu-android_*.so): ggml puntúa cada una según el procesador y carga
        // la mejor compatible.
        if (!lib_dir.empty()) ggml_backend_load_all_from_path(lib_dir.c_str());
        if (ggml_backend_dev_count() == 0) ggml_backend_load_all();
#else
        (void) lib_dir;
#endif
        const char * si = llama_print_system_info();
        g_system_info = si ? si : "";
    });
    return g_system_info;
}

std::string quantize(const std::string & in_path, const std::string & out_path, int ftype, int n_threads) {
    llama_model_quantize_params qp = llama_model_quantize_default_params();
    qp.ftype = (llama_ftype) ftype;
    qp.nthread = n_threads;
    qp.allow_requantize = false;
    qp.quantize_output_tensor = true;
    // Poca RAM en móviles: trozos de 64 MB como mucho en memoria.
    qp.max_buf_size = 64u * 1024u * 1024u;
    try {
        uint32_t r = llama_model_quantize(in_path.c_str(), out_path.c_str(), &qp);
        if (r != 0) return "llama_model_quantize devolvió " + std::to_string(r);
    } catch (const std::exception & e) {
        return std::string("error al cuantizar: ") + e.what();
    }
    return {};
}

// ------------------------------------------------------------------------------------------------ sesión

bool Session::abort_cb(void * data) {
    return static_cast<Session *>(data)->cancel_.load();
}

std::unique_ptr<Session> Session::load(const LoadParams & p, std::string & err) {
    std::unique_ptr<Session> s(new Session());
    s->params_ = p;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = p.use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    s->model_ = llama_model_load_from_file(p.model_path.c_str(), mp);
    if (!s->model_) {
        err = "no se pudo cargar el modelo: " + p.model_path;
        return nullptr;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::max(512, p.n_ctx);
    cp.n_batch = (uint32_t) std::max(32, p.n_batch);
    cp.n_ubatch = cp.n_batch;
    cp.n_seq_max = 1;
    cp.n_threads = std::max(1, p.n_threads);
    cp.n_threads_batch = std::max(1, p.n_threads_batch);
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.no_perf = true;
    cp.abort_callback = &Session::abort_cb;
    cp.abort_callback_data = s.get();
    s->ctx_ = llama_init_from_model(s->model_, cp);
    if (!s->ctx_) {
        err = "no se pudo crear el contexto (¿memoria insuficiente? n_ctx=" + std::to_string(cp.n_ctx) + ")";
        return nullptr;
    }

    if (!p.mmproj_path.empty()) {
        mtmd_context_params mpp = mtmd_context_params_default();
        mpp.use_gpu = false;
        mpp.print_timings = false;
        mpp.n_threads = std::max(1, p.n_threads_batch);
        mpp.warmup = false;
        mpp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        if (p.image_min_tokens > 0) mpp.image_min_tokens = p.image_min_tokens;
        if (p.image_max_tokens > 0) mpp.image_max_tokens = p.image_max_tokens;
        s->mctx_ = mtmd_init_from_file(p.mmproj_path.c_str(), s->model_, mpp);
        if (!s->mctx_) {
            err = "no se pudo cargar el proyector de visión: " + p.mmproj_path;
            return nullptr;
        }
    }

    try {
        auto t = common_chat_templates_init(s->model_, /*override*/ "");
        s->tmpls_ = t.release();
    } catch (const std::exception & e) {
        s->tmpls_ = nullptr;  // se usará llama_chat_apply_template o el prompt en crudo
    }
    return s;
}

Session::~Session() {
    if (tmpls_) common_chat_templates_free(tmpls_);
    if (mctx_) mtmd_free(mctx_);
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
}

std::string Session::info() const {
    char desc[256] = {0};
    llama_model_desc(model_, desc, sizeof(desc));
    std::string r = desc;
    r += " | params=" + std::to_string(llama_model_n_params(model_));
    r += " | size=" + std::to_string(llama_model_size(model_));
    r += " | n_ctx=" + std::to_string(llama_n_ctx(ctx_));
    r += " | n_ctx_train=" + std::to_string(llama_model_n_ctx_train(model_));
    r += std::string(" | vision=") + (mctx_ && mtmd_support_vision(mctx_) ? "si" : "no");
    r += std::string(" | jinja=") + (tmpls_ ? "si" : "no");
    return r;
}

std::string Session::format_prompt(const GenParams & g, int n_images, std::vector<std::string> * extra_stops) const {
    std::string user;
    // El marcador de imagen va delante del texto: es lo que esperan PaddleOCR-VL ("<imagen>OCR:") y Qwen-VL.
    for (int i = 0; i < n_images; ++i) user += mtmd_default_marker();
    user += g.prompt;
    if (!g.use_chat_template) return user;

    if (tmpls_) {
        try {
            common_chat_templates_inputs in;
            if (!g.system.empty()) {
                common_chat_msg sys;
                sys.role = "system";
                sys.content = g.system;
                in.messages.push_back(sys);
            }
            common_chat_msg msg;
            msg.role = "user";
            msg.content = user;
            in.messages.push_back(msg);
            in.add_generation_prompt = true;
            in.use_jinja = true;
            in.enable_thinking = g.enable_thinking;
            auto out = common_chat_templates_apply(tmpls_, in);
            if (extra_stops) {
                for (const auto & s : out.additional_stops) extra_stops->push_back(s);
            }
            return out.prompt;
        } catch (const std::exception &) {
            // cae a la plantilla integrada
        }
    }

    const char * tmpl = llama_model_chat_template(model_, nullptr);
    std::vector<llama_chat_message> msgs;
    if (!g.system.empty()) msgs.push_back({"system", g.system.c_str()});
    msgs.push_back({"user", user.c_str()});
    std::vector<char> buf(user.size() * 2 + g.system.size() * 2 + 512);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    if (n > 0) return std::string(buf.data(), n);
    return user;  // sin plantilla conocida: prompt en crudo
}

bool Session::generate(const GenParams & g, const std::vector<Image> & images, const TextCallback & on_text,
                       Stats & stats, std::string & err) {
    stats = Stats{};
    cancel_.store(false);
    const llama_vocab * vocab = llama_model_get_vocab(model_);

    if (!images.empty() && !mctx_) {
        err = "este modelo no tiene proyector de visión cargado";
        return false;
    }

    std::vector<std::string> stops = g.stops;
    const std::string prompt = format_prompt(g, (int) images.size(), &stops);
    stops.erase(std::remove_if(stops.begin(), stops.end(), [](const std::string & s) { return s.empty(); }), stops.end());

    // Conversación nueva en cada llamada.
    llama_memory_clear(llama_get_memory(ctx_), true);

    auto t_prompt = clock_type::now();
    llama_pos n_past = 0;
    const int n_ctx = (int) llama_n_ctx(ctx_);
    const int n_batch = std::max(32, params_.n_batch);

    if (mctx_) {
        std::vector<mtmd::bitmap> bitmaps;
        bitmaps.reserve(images.size());
        for (const auto & im : images) {
            if (im.width <= 0 || im.height <= 0 || im.rgb.size() < (size_t) im.width * im.height * 3) {
                err = "imagen inválida";
                return false;
            }
            bitmaps.emplace_back(mtmd_bitmap_init((uint32_t) im.width, (uint32_t) im.height, im.rgb.data()));
        }
        std::vector<const mtmd_bitmap *> bptr;
        for (auto & b : bitmaps) bptr.push_back(b.ptr.get());

        mtmd_input_text txt;
        txt.text = prompt.c_str();
        txt.text_len = prompt.size();
        txt.add_special = true;
        txt.parse_special = true;
        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        int32_t r = mtmd_tokenize(mctx_, chunks.ptr.get(), &txt, bptr.data(), bptr.size());
        if (r != 0) {
            err = "no se pudo tokenizar el prompt con imagen (código " + std::to_string(r) + ")";
            return false;
        }
        const size_t total = mtmd_helper_get_n_tokens(chunks.ptr.get());
        if ((int) mtmd_helper_get_n_pos(chunks.ptr.get()) + 8 >= n_ctx) {
            err = "el prompt (" + std::to_string(total) + " tokens con la imagen) no cabe en el contexto de " +
                  std::to_string(n_ctx) + "; reduce la resolución de imagen o aumenta el contexto";
            return false;
        }
        stats.prompt_tokens = (int) total;
        const size_t n_chunks = mtmd_input_chunks_size(chunks.ptr.get());
        for (size_t i = 0; i < n_chunks; ++i) {
            if (cancel_.load()) {
                stats.stop_reason = STOP_CANCELLED;
                stats.prompt_ms = ms_since(t_prompt);
                return true;
            }
            const mtmd_input_chunk * ch = mtmd_input_chunks_get(chunks.ptr.get(), i);
            const bool is_img = mtmd_input_chunk_get_type(ch) != MTMD_INPUT_CHUNK_TYPE_TEXT;
            auto t0 = clock_type::now();
            llama_pos new_past = n_past;
            r = mtmd_helper_eval_chunk_single(mctx_, ctx_, ch, n_past, 0, n_batch, i == n_chunks - 1, &new_past);
            if (r != 0) {
                if (cancel_.load()) {
                    stats.stop_reason = STOP_CANCELLED;
                    stats.prompt_ms = ms_since(t_prompt);
                    return true;
                }
                err = std::string("falló la evaluación del ") + (is_img ? "bloque de imagen" : "texto") +
                      " (código " + std::to_string(r) + ")";
                return false;
            }
            n_past = new_past;
            if (is_img) {
                stats.image_ms += ms_since(t0);
                stats.image_tokens += (int) mtmd_input_chunk_get_n_tokens(ch);
            }
        }
    } else {
        const bool add_special = true;
        int n = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, add_special, true);
        std::vector<llama_token> toks(std::max(n, 0));
        if (n <= 0 || llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), n, add_special, true) < 0) {
            err = "no se pudo tokenizar el prompt";
            return false;
        }
        if (n + 8 >= n_ctx) {
            err = "el prompt (" + std::to_string(n) + " tokens) no cabe en el contexto de " + std::to_string(n_ctx);
            return false;
        }
        stats.prompt_tokens = n;
        for (int i = 0; i < n; i += n_batch) {
            int cnt = std::min(n_batch, n - i);
            llama_batch b = llama_batch_get_one(toks.data() + i, cnt);
            int r = llama_decode(ctx_, b);
            if (r != 0) {
                if (cancel_.load()) {
                    stats.stop_reason = STOP_CANCELLED;
                    stats.prompt_ms = ms_since(t_prompt);
                    return true;
                }
                err = "falló la evaluación del prompt (código " + std::to_string(r) + ")";
                return false;
            }
        }
        n_past = n;
    }
    stats.prompt_ms = ms_since(t_prompt);

    // Muestreador: penalización de repetición (útil contra bucles en OCR) -> codicioso o top-k/top-p/min-p/temp.
    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    scp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(scp);
    const bool use_repeat = g.repeat_penalty > 0.0f && g.repeat_penalty != 1.0f;
    if (use_repeat || g.presence_penalty != 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab),
            g.repeat_last_n > 0 ? g.repeat_last_n : 64, use_repeat ? g.repeat_penalty : 1.0f, 0.0f, g.presence_penalty));
    }
    if (g.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (g.top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(g.top_k));
        if (g.top_p > 0.0f && g.top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(g.top_p, 1));
        if (g.min_p > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(g.min_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(g.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(g.seed));
    }

    llama_batch batch = llama_batch_init(1, 0, 1);
    std::string text;      // todo lo generado
    size_t emitted = 0;    // bytes de text ya enviados
    auto t_gen = clock_type::now();
    stats.stop_reason = STOP_MAX_TOKENS;
    bool stopped_by_string = false;

    auto flush = [&](bool final) -> bool {
        size_t limit = utf8_complete_prefix(text);
        if (!final) {
            size_t hold = stop_holdback(text.substr(0, limit), stops);
            limit = limit >= hold ? limit - hold : 0;
        } else {
            limit = text.size();
        }
        if (limit > emitted) {
            std::string piece = text.substr(emitted, limit - emitted);
            emitted = limit;
            if (on_text && !on_text(piece)) return false;
        }
        return true;
    };

    for (int i = 0; i < g.max_tokens; ++i) {
        if (cancel_.load()) { stats.stop_reason = STOP_CANCELLED; break; }
        if (n_past >= n_ctx - 1) { stats.stop_reason = STOP_CONTEXT_FULL; break; }

        llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab, tok)) { stats.stop_reason = STOP_EOS; break; }
        stats.gen_tokens++;

        const size_t before = text.size();
        text += token_to_piece(vocab, tok, g.special);

        // ¿Apareció alguna cadena de parada? (se busca solo cerca del final)
        size_t cut = std::string::npos;
        for (const auto & s : stops) {
            size_t from = before >= s.size() ? before - s.size() + 1 : 0;
            size_t p = text.find(s, from);
            if (p != std::string::npos && p < cut) cut = p;
        }
        if (cut != std::string::npos) {
            text.resize(std::max(cut, emitted));
            stats.stop_reason = STOP_STRING;
            stopped_by_string = true;
            break;
        }
        if (!flush(false)) { stats.stop_reason = STOP_CALLBACK; break; }

        batch.n_tokens = 1;
        batch.token[0] = tok;
        batch.pos[0] = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        int r = llama_decode(ctx_, batch);
        n_past++;
        if (r != 0) {
            if (cancel_.load()) { stats.stop_reason = STOP_CANCELLED; break; }
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            err = "falló la decodificación (código " + std::to_string(r) + ")";
            return false;
        }
    }
    (void) stopped_by_string;
    if (stats.stop_reason != STOP_CALLBACK) flush(true);
    stats.gen_ms = ms_since(t_gen);

    llama_batch_free(batch);
    llama_sampler_free(smpl);
    return true;
}

} // namespace llm
