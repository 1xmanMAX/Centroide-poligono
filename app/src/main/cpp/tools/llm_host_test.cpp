// Prueba de escritorio del núcleo llm::Session (la misma lógica que usa el JNI de Android).
// Compilar contra una build de llama.cpp del mismo commit (ver tools/build_host_test.sh):
//   llm_host_test -m modelo.gguf [--mmproj mmproj.gguf] [--image img.jpg]... -p "OCR:" [-c 4096] [-n 256]
//                 [-t 4] [--temp 0] [--max-image-tokens N] [--stop TEXTO] [--no-template] [--think] [--cancel-after N]
//                 [--quantize in.gguf out.gguf ftype]
#include "llm_session.h"

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

int main(int argc, char ** argv) {
    llm::LoadParams lp;
    llm::GenParams gp;
    std::vector<std::string> image_paths;
    int cancel_after = -1;
    bool show_prompt = false;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        if (a == "-m") lp.model_path = next();
        else if (a == "--mmproj") lp.mmproj_path = next();
        else if (a == "--image") image_paths.push_back(next());
        else if (a == "-p") gp.prompt = next();
        else if (a == "-s") gp.system = next();
        else if (a == "-c") lp.n_ctx = atoi(next().c_str());
        else if (a == "-b") lp.n_batch = atoi(next().c_str());
        else if (a == "-n") gp.max_tokens = atoi(next().c_str());
        else if (a == "-t") lp.n_threads = lp.n_threads_batch = atoi(next().c_str());
        else if (a == "--temp") gp.temperature = (float) atof(next().c_str());
        else if (a == "--repeat-penalty") gp.repeat_penalty = (float) atof(next().c_str());
        else if (a == "--max-image-tokens") lp.image_max_tokens = atoi(next().c_str());
        else if (a == "--stop") gp.stops.push_back(next());
        else if (a == "--no-template") gp.use_chat_template = false;
        else if (a == "--think") gp.enable_thinking = true;
        else if (a == "--no-mmap") lp.use_mmap = false;
        else if (a == "--cancel-after") cancel_after = atoi(next().c_str());
        else if (a == "--show-prompt") show_prompt = true;
        else if (a == "--quantize") {
            std::string in = next(), out = next();
            int ft = atoi(next().c_str());
            llm::init_backend("");
            std::string e = llm::quantize(in, out, ft, lp.n_threads);
            printf("quantize: %s\n", e.empty() ? "OK" : e.c_str());
            return e.empty() ? 0 : 1;
        } else {
            fprintf(stderr, "argumento desconocido: %s\n", a.c_str());
            return 2;
        }
    }

    // Pruebas de utilidades puras.
    {
        std::string s = "a\xC3";  // 'a' + primer byte de 'á'
        if (llm::utf8_complete_prefix(s) != 1) { fprintf(stderr, "FALLO utf8_complete_prefix\n"); return 3; }
        s += "\xA1";
        if (llm::utf8_complete_prefix(s) != 3) { fprintf(stderr, "FALLO utf8_complete_prefix 2\n"); return 3; }
        if (llm::stop_holdback("hola </", {"</s>"}) != 2) { fprintf(stderr, "FALLO stop_holdback\n"); return 3; }
        if (llm::stop_holdback("hola", {"</s>"}) != 0) { fprintf(stderr, "FALLO stop_holdback 2\n"); return 3; }
        printf("[utilidades OK]\n");
    }

    llama_log_set([](ggml_log_level lvl, const char * t, void *) { if (lvl >= GGML_LOG_LEVEL_WARN) fputs(t, stderr); }, nullptr);
    mtmd_helper_log_set([](ggml_log_level lvl, const char * t, void *) { if (lvl >= GGML_LOG_LEVEL_WARN) fputs(t, stderr); }, nullptr);
    printf("[sistema] %s\n", llm::init_backend("").c_str());

    std::string err;
    auto sess = llm::Session::load(lp, err);
    if (!sess) { fprintf(stderr, "ERROR carga: %s\n", err.c_str()); return 1; }
    printf("[modelo] %s\n", sess->info().c_str());

    std::vector<llm::Image> images;
    // Decodificación de imágenes con stb (vía mtmd_helper) -> RGB.
    for (const auto & p : image_paths) {
        mtmd_bitmap * bmp = nullptr;
        {
            auto wrap = mtmd_helper_bitmap_init_from_file(nullptr, p.c_str(), false, mtmd_helper_init_opt_default());
            bmp = wrap.bitmap;
        }
        if (!bmp) { fprintf(stderr, "no se pudo leer %s\n", p.c_str()); return 1; }
        llm::Image im;
        im.width = (int) mtmd_bitmap_get_nx(bmp);
        im.height = (int) mtmd_bitmap_get_ny(bmp);
        const unsigned char * d = mtmd_bitmap_get_data(bmp);
        im.rgb.assign(d, d + mtmd_bitmap_get_n_bytes(bmp));
        mtmd_bitmap_free(bmp);
        printf("[imagen] %s %dx%d\n", p.c_str(), im.width, im.height);
        images.push_back(std::move(im));
    }

    if (show_prompt) {
        printf("[prompt]\n%s\n[/prompt]\n", sess->format_prompt(gp, (int) images.size(), nullptr).c_str());
    }

    int pieces = 0;
    llm::Session * sp = sess.get();
    llm::Stats st;
    bool ok = sess->generate(gp, images, [&](const std::string & t) {
        fputs(t.c_str(), stdout);
        fflush(stdout);
        if (cancel_after >= 0 && ++pieces >= cancel_after) sp->cancel();
        return true;
    }, st, err);
    printf("\n");
    if (!ok) { fprintf(stderr, "ERROR generación: %s\n", err.c_str()); return 1; }
    printf("[stats] prompt=%d (imagen=%d) gen=%d imagen_ms=%.0f prompt_ms=%.0f gen_ms=%.0f tok/s=%.1f stop=%d\n",
           st.prompt_tokens, st.image_tokens, st.gen_tokens, st.image_ms, st.prompt_ms, st.gen_ms,
           st.gen_ms > 0 ? st.gen_tokens * 1000.0 / st.gen_ms : 0.0, st.stop_reason);
    return 0;
}
