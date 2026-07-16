// JNI bridge over llama.cpp for Mink's on-device guardian.
//
// Implements the external funs declared in
// com.mink.guardian.llm.LlamaBridge. Everything the guardian runs stays on the
// device; this file only decodes tokens locally and never touches the network.
//
// It compiles against a vendored llama.cpp (see README.md). The API names here
// track a recent (2025) llama.cpp; if you pin an older tag, adjust the few
// symbols noted inline. When these sources are absent the app builds fine and
// falls back to the Kotlin rules engine.

#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "mink_llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        llama_backend_init();
        llama_log_set(
            [](ggml_log_level level, const char *text, void *) {
                if (level >= GGML_LOG_LEVEL_WARN) {
                    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", text);
                }
            },
            nullptr);
    });
}

// Per-session state behind the opaque jlong handle.
struct MinkSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_past = 0;
    int n_ctx = 0;
    // Bytes of a multi-byte UTF-8 char that split across token pieces.
    std::string utf8_pending;
};

// Length of the longest prefix of `s` that ends on a complete UTF-8 sequence.
size_t valid_utf8_prefix(const std::string &s) {
    size_t i = s.size();
    // Walk back over trailing continuation bytes (10xxxxxx).
    size_t cont = 0;
    while (i > 0 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) {
        --i;
        ++cont;
    }
    if (i == 0) return 0;  // all continuation bytes, incomplete
    unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    size_t need;
    if ((lead & 0x80) == 0x00) need = 1;
    else if ((lead & 0xE0) == 0xC0) need = 2;
    else if ((lead & 0xF0) == 0xE0) need = 3;
    else if ((lead & 0xF8) == 0xF0) need = 4;
    else return s.size();  // invalid lead, don't hold it back
    // If the final sequence is complete, keep everything; else hold it back.
    if (cont + 1 >= need) return s.size();
    return i - 1;
}

bool decode_tokens(MinkSession *s, const std::vector<llama_token> &toks) {
    if (toks.empty()) return true;
    const int n = static_cast<int>(toks.size());
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; ++i) {
        batch.token[i] = toks[i];
        batch.pos[i] = s->n_past + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n - 1) ? 1 : 0;
    }
    batch.n_tokens = n;
    const bool ok = llama_decode(s->ctx, batch) == 0;
    llama_batch_free(batch);
    if (ok) s->n_past += n;
    return ok;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mink_guardian_llm_LlamaBridge_nativeInit(
    JNIEnv *env, jobject /*thiz*/, jstring modelPath,
    jint nCtx, jint nThreads, jint nGpuLayers) {
    ensure_backend();

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    // llama_model_load_from_file is the current name; older tags use
    // llama_load_model_from_file.
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) {
        LOGW("failed to load model");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(nCtx);
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;

    // llama_init_from_model is current; older tags use
    // llama_new_context_with_model.
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGW("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *session = new MinkSession();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = nCtx;
    session->n_past = 0;

    LOGI("model loaded, n_ctx=%d threads=%d", nCtx, nThreads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jint JNICALL
Java_com_mink_guardian_llm_LlamaBridge_nativePrompt(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt) {
    auto *s = reinterpret_cast<MinkSession *>(handle);
    if (s == nullptr) return -1;

    const char *text = env->GetStringUTFChars(prompt, nullptr);
    if (text == nullptr) return -1;
    const int text_len = static_cast<int>(env->GetStringUTFLength(prompt));

    // Fresh prompt: clear the KV cache and position. The memory API replaced
    // llama_kv_self_clear (which itself replaced llama_kv_cache_clear).
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->n_past = 0;
    s->utf8_pending.clear();

    int needed = -llama_tokenize(s->vocab, text, text_len, nullptr, 0, true, true);
    if (needed <= 0) {
        env->ReleaseStringUTFChars(prompt, text);
        return -1;
    }
    std::vector<llama_token> toks(needed);
    llama_tokenize(s->vocab, text, text_len, toks.data(), needed, true, true);
    env->ReleaseStringUTFChars(prompt, text);

    // Keep the last (n_ctx - headroom) tokens if the prompt is oversized.
    const int headroom = 512;
    if (static_cast<int>(toks.size()) > s->n_ctx - headroom && s->n_ctx > headroom) {
        const int keep = s->n_ctx - headroom;
        toks.erase(toks.begin(), toks.end() - keep);
    }

    if (!decode_tokens(s, toks)) return -1;
    return static_cast<jint>(toks.size());
}

JNIEXPORT jstring JNICALL
Java_com_mink_guardian_llm_LlamaBridge_nativeSampleToken(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jfloat temperature, jfloat topP) {
    auto *s = reinterpret_cast<MinkSession *>(handle);
    if (s == nullptr) return env->NewStringUTF("");

    // Build a small sampler chain honouring the per-call temperature / top-p.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
    llama_sampler_free(smpl);

    if (llama_vocab_is_eog(s->vocab, tok)) {
        return env->NewStringUTF("");
    }
    if (s->n_past >= s->n_ctx) {
        return env->NewStringUTF("");  // context full, stop cleanly
    }

    char buf[512];
    const int len = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf), 0, true);
    if (len > 0) {
        s->utf8_pending.append(buf, len);
    }

    // Advance the context by the sampled token so the next call continues.
    std::vector<llama_token> one{tok};
    if (!decode_tokens(s, one)) {
        return env->NewStringUTF("");
    }

    // Emit only the complete-UTF-8 prefix; hold back a split multi-byte char.
    const size_t good = valid_utf8_prefix(s->utf8_pending);
    std::string out = s->utf8_pending.substr(0, good);
    s->utf8_pending.erase(0, good);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_mink_guardian_llm_LlamaBridge_nativeResetContext(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *s = reinterpret_cast<MinkSession *>(handle);
    if (s == nullptr) return;
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->n_past = 0;
    s->utf8_pending.clear();
}

JNIEXPORT void JNICALL
Java_com_mink_guardian_llm_LlamaBridge_nativeFree(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *s = reinterpret_cast<MinkSession *>(handle);
    if (s == nullptr) return;
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

}  // extern "C"
