// Capa JNI fina sobre llm::Session (llm_session.cpp). Contrapartida Kotlin: com.scannerpromax.ai.LlmNative.
#include "llm_session.h"

#include "llama.h"

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <mutex>
#include <set>
#include <string>
#include <vector>

#define LOG_TAG "LlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Sesiones vivas: nativeCancel puede llegar desde otro hilo justo cuando otra llamada libera la sesión.
std::mutex g_live_mutex;
std::set<llm::Session *> g_live;

void log_cb(enum ggml_log_level level, const char * text, void *) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: return;  // DEBUG / CONT: demasiado ruido
    }
    __android_log_write(prio, "llama", text);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

void throw_state(JNIEnv * env, const std::string & msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

// UTF-8 estándar -> jstring (NewStringUTF espera "UTF-8 modificado" y rompe con emojis / caracteres de 4 bytes).
jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::u16string out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char) s[i];
        uint32_t cp;
        size_t len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        else { out.push_back(0xFFFD); i++; continue; }
        if (i + len > s.size()) { out.push_back(0xFFFD); break; }
        bool ok = true;
        for (size_t k = 1; k < len; ++k) {
            unsigned char cc = (unsigned char) s[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { out.push_back(0xFFFD); i++; continue; }
        i += len;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back((char16_t) (0xD800 + (cp >> 10)));
            out.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back((char16_t) cp);
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(out.data()), (jsize) out.size());
}

bool bitmap_to_rgb(JNIEnv * env, jobject bmp, llm::Image & out, std::string & err) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        err = "no se pudo leer el Bitmap";
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        err = "el Bitmap debe ser ARGB_8888";
        return false;
    }
    void * px = nullptr;
    if (AndroidBitmap_lockPixels(env, bmp, &px) != ANDROID_BITMAP_RESULT_SUCCESS || !px) {
        err = "no se pudieron bloquear los píxeles del Bitmap";
        return false;
    }
    out.width = (int) info.width;
    out.height = (int) info.height;
    out.rgb.resize((size_t) info.width * info.height * 3);
    uint8_t * dst = out.rgb.data();
    for (uint32_t y = 0; y < info.height; ++y) {
        const uint8_t * row = static_cast<const uint8_t *>(px) + (size_t) y * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            *dst++ = row[x * 4 + 0];
            *dst++ = row[x * 4 + 1];
            *dst++ = row[x * 4 + 2];
        }
    }
    AndroidBitmap_unlockPixels(env, bmp);
    return true;
}

llm::Session * as_session(jlong h) { return reinterpret_cast<llm::Session *>(h); }

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeInit(JNIEnv * env, jobject, jstring lib_dir) {
    llama_log_set(log_cb, nullptr);
    std::string info = llm::init_backend(jstr(env, lib_dir));
    LOGI("backend listo: %s", info.c_str());
    return to_jstring(env, info);
}

JNIEXPORT jlong JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeLoad(JNIEnv * env, jobject, jstring model, jstring mmproj, jint n_ctx,
                                               jint n_batch, jint n_threads, jint n_threads_batch, jboolean mmap,
                                               jint img_min_tokens, jint img_max_tokens) {
    llm::LoadParams p;
    p.model_path = jstr(env, model);
    p.mmproj_path = jstr(env, mmproj);
    p.n_ctx = n_ctx;
    p.n_batch = n_batch;
    p.n_threads = n_threads;
    p.n_threads_batch = n_threads_batch;
    p.use_mmap = mmap;
    p.image_min_tokens = img_min_tokens;
    p.image_max_tokens = img_max_tokens;
    std::string err;
    std::unique_ptr<llm::Session> s;
    try {
        s = llm::Session::load(p, err);
    } catch (const std::exception & e) {
        err = std::string("excepción al cargar: ") + e.what();
    }
    if (!s) {
        LOGE("%s", err.c_str());
        throw_state(env, err.empty() ? "no se pudo cargar el modelo" : err);
        return 0;
    }
    llm::Session * raw = s.release();
    {
        std::lock_guard<std::mutex> lk(g_live_mutex);
        g_live.insert(raw);
    }
    LOGI("modelo cargado: %s", raw->info().c_str());
    return reinterpret_cast<jlong>(raw);
}

JNIEXPORT jstring JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeInfo(JNIEnv * env, jobject, jlong h) {
    if (!h) return to_jstring(env, "");
    return to_jstring(env, as_session(h)->info());
}

JNIEXPORT jboolean JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeHasVision(JNIEnv *, jobject, jlong h) {
    return h && as_session(h)->has_vision();
}

JNIEXPORT jstring JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeFormatPrompt(JNIEnv * env, jobject, jlong h, jstring prompt, jstring system,
                                                       jint n_images, jboolean use_template, jboolean thinking) {
    if (!h) return to_jstring(env, "");
    llm::GenParams g;
    g.prompt = jstr(env, prompt);
    g.system = jstr(env, system);
    g.use_chat_template = use_template;
    g.enable_thinking = thinking;
    return to_jstring(env, as_session(h)->format_prompt(g, n_images, nullptr));
}

/**
 * Devuelve [promptTokens, imageTokens, genTokens, imageMs, promptMs, genMs, stopReason].
 * callback: objeto con `boolean onText(String)` (false = parar).
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeGenerate(JNIEnv * env, jobject, jlong h, jstring prompt, jstring system,
                                                   jobjectArray images, jboolean use_template, jboolean thinking,
                                                   jint max_tokens, jfloat temperature, jint top_k, jfloat top_p,
                                                   jfloat min_p, jfloat repeat_penalty, jint repeat_last_n,
                                                   jfloat presence_penalty, jboolean special,
                                                   jint seed, jobjectArray stops, jobject callback) {
    if (!h) {
        throw_state(env, "modelo no cargado");
        return nullptr;
    }
    llm::GenParams g;
    g.prompt = jstr(env, prompt);
    g.system = jstr(env, system);
    g.use_chat_template = use_template;
    g.enable_thinking = thinking;
    g.max_tokens = max_tokens;
    g.temperature = temperature;
    g.top_k = top_k;
    g.top_p = top_p;
    g.min_p = min_p;
    g.repeat_penalty = repeat_penalty;
    g.repeat_last_n = repeat_last_n;
    g.presence_penalty = presence_penalty;
    g.special = special == JNI_TRUE;
    g.seed = seed < 0 ? 0xFFFFFFFFu : (uint32_t) seed;
    if (stops) {
        jsize n = env->GetArrayLength(stops);
        for (jsize i = 0; i < n; ++i) {
            auto s = (jstring) env->GetObjectArrayElement(stops, i);
            g.stops.push_back(jstr(env, s));
            env->DeleteLocalRef(s);
        }
    }

    std::vector<llm::Image> imgs;
    if (images) {
        jsize n = env->GetArrayLength(images);
        imgs.resize(n);
        for (jsize i = 0; i < n; ++i) {
            jobject b = env->GetObjectArrayElement(images, i);
            std::string err;
            bool ok = b && bitmap_to_rgb(env, b, imgs[i], err);
            if (b) env->DeleteLocalRef(b);
            if (!ok) {
                throw_state(env, err.empty() ? "imagen nula" : err);
                return nullptr;
            }
        }
    }

    jmethodID on_text = nullptr;
    if (callback) {
        jclass cb_cls = env->GetObjectClass(callback);
        on_text = env->GetMethodID(cb_cls, "onText", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cb_cls);
        if (!on_text) return nullptr;  // NoSuchMethodError pendiente
    }

    llm::Stats st;
    std::string err;
    bool java_exception = false;
    auto cb = [&](const std::string & piece) -> bool {
        if (!callback || !on_text) return true;
        jstring js = to_jstring(env, piece);
        jboolean keep = env->CallBooleanMethod(callback, on_text, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) {
            java_exception = true;
            return false;
        }
        return keep == JNI_TRUE;
    };
    bool ok;
    try {
        ok = as_session(h)->generate(g, imgs, cb, st, err);
    } catch (const std::exception & e) {
        ok = false;
        err = std::string("excepción en la generación: ") + e.what();
    }
    if (java_exception) return nullptr;  // se propaga la excepción de Kotlin
    if (!ok) {
        throw_state(env, err);
        return nullptr;
    }
    jdouble vals[7] = {(double) st.prompt_tokens, (double) st.image_tokens, (double) st.gen_tokens, st.image_ms,
                       st.prompt_ms, st.gen_ms, (double) st.stop_reason};
    jdoubleArray arr = env->NewDoubleArray(7);
    env->SetDoubleArrayRegion(arr, 0, 7, vals);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeCancel(JNIEnv *, jobject, jlong h) {
    std::lock_guard<std::mutex> lk(g_live_mutex);
    auto * s = as_session(h);
    if (g_live.count(s)) s->cancel();
}

JNIEXPORT void JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeFree(JNIEnv *, jobject, jlong h) {
    auto * s = as_session(h);
    {
        std::lock_guard<std::mutex> lk(g_live_mutex);
        if (!g_live.erase(s)) return;
    }
    delete s;
}

JNIEXPORT jstring JNICALL
Java_com_scannerpromax_ai_LlmNative_nativeQuantize(JNIEnv * env, jobject, jstring in, jstring out, jint ftype,
                                                   jint n_threads) {
    std::string err = llm::quantize(jstr(env, in), jstr(env, out), ftype, n_threads);
    if (err.empty()) return nullptr;
    return to_jstring(env, err);
}

} // extern "C"
