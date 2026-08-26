#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "jni_utils.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LlamaBridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaBridge", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

static std::atomic<bool> g_llama_cancel{false};

namespace {

struct LlamaHandle {
    llama_model *        model;
    llama_context *      ctx;
    const llama_vocab *  vocab;
    int                  n_ctx;
    int                  n_batch;
};

void ensure_backend_initialized() {
    static std::once_flag flag;
    std::call_once(flag, [] { llama_backend_init(); });
}

bool abort_callback(void * /*user_data*/) {
    return g_llama_cancel.load();
}

std::vector<llama_token> tokenize_text(const llama_vocab * vocab, const std::string & text) {
    const int32_t needed = -llama_tokenize(
        vocab, text.c_str(), (int32_t) text.size(), nullptr, 0,
        /*add_special=*/true, /*parse_special=*/true);
    if (needed <= 0) {
        return {};
    }
    std::vector<llama_token> tokens((size_t) needed);
    const int32_t written = llama_tokenize(
        vocab, text.c_str(), (int32_t) text.size(), tokens.data(), needed,
        /*add_special=*/true, /*parse_special=*/true);
    if (written < 0) {
        return {};
    }
    tokens.resize((size_t) written);
    return tokens;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_stastyle_localsummarizer_nativebridge_LlamaBridge_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring model_path, jint n_ctx, jint n_threads) {
    ensure_backend_initialized();

    const std::string path = jstring_to_utf8(env, model_path);

    llama_model_params mparams = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load llama model from %s", path.c_str());
        return 0;
    }

    const int n_batch = 1024;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_batch         = (uint32_t) n_batch;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.abort_callback      = abort_callback;
    cparams.abort_callback_data = nullptr;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("failed to create llama context (n_ctx=%d)", (int) n_ctx);
        llama_model_free(model);
        return 0;
    }

    auto * handle = new LlamaHandle{
        model,
        ctx,
        llama_model_get_vocab(model),
        (int) llama_n_ctx(ctx),
        // llama clamps the batch to the context for causal models, and
        // exceeding the effective value aborts inside llama_decode.
        (int) llama_n_batch(ctx),
    };
    return (jlong) (intptr_t) handle;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_stastyle_localsummarizer_nativebridge_LlamaBridge_nativeTokenCount(
        JNIEnv * env, jobject /*thiz*/, jlong handle_ptr, jstring text) {
    auto * handle = (LlamaHandle *) (intptr_t) handle_ptr;
    if (handle == nullptr) return -1;
    const std::string str = jstring_to_utf8(env, text);
    const int32_t needed = -llama_tokenize(
        handle->vocab, str.c_str(), (int32_t) str.size(), nullptr, 0, true, true);
    return (jint) std::max(0, (int) needed);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_stastyle_localsummarizer_nativebridge_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject /*thiz*/, jlong handle_ptr, jstring prompt,
        jint max_tokens, jfloat temperature, jint seed, jobject listener) {
    auto * handle = (LlamaHandle *) (intptr_t) handle_ptr;
    if (handle == nullptr) {
        throw_runtime_exception(env, "llama context is not initialized");
        return nullptr;
    }

    jmethodID on_token = nullptr;
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cls);
        if (on_token == nullptr) {
            throw_runtime_exception(env, "token listener is missing onToken");
            return nullptr;
        }
    }

    llama_context * ctx = handle->ctx;
    const llama_vocab * vocab = handle->vocab;

    // fresh generation: drop any previous KV cache contents
    llama_memory_clear(llama_get_memory(ctx), true);

    const std::string prompt_str = jstring_to_utf8(env, prompt);
    std::vector<llama_token> tokens = tokenize_text(vocab, prompt_str);
    if (tokens.empty()) {
        throw_runtime_exception(env, "failed to tokenize prompt");
        return nullptr;
    }

    const int n_ctx = handle->n_ctx;
    if ((int) tokens.size() + 16 >= n_ctx) {
        throw_runtime_exception(
            env,
            "prompt too long: " + std::to_string(tokens.size()) +
            " tokens with context of " + std::to_string(n_ctx));
        return nullptr;
    }
    const int n_gen = std::min((int) max_tokens, n_ctx - (int) tokens.size() - 8);

    // evaluate the prompt in n_batch-sized chunks
    for (size_t i = 0; i < tokens.size(); i += (size_t) handle->n_batch) {
        if (g_llama_cancel.load()) {
            return utf8_to_jstring(env, "");
        }
        const int n_eval = (int) std::min((size_t) handle->n_batch, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval);
        if (llama_decode(ctx, batch) != 0) {
            if (g_llama_cancel.load()) {
                return utf8_to_jstring(env, "");
            }
            throw_runtime_exception(env, "llama_decode failed during prompt evaluation");
            return nullptr;
        }
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t) seed));
    }

    std::string result;
    std::string pending; // bytes not yet forming complete UTF-8 characters
    bool stopped_by_listener = false;

    for (int i = 0; i < n_gen; ++i) {
        if (g_llama_cancel.load()) break;

        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        char buf[256];
        int32_t len = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
        if (len < 0) {
            // Rare tokens can exceed the stack buffer; retry with the size the
            // call asked for rather than dropping the piece.
            std::string large((size_t) -len, '\0');
            len = llama_token_to_piece(vocab, token, large.data(), -len, 0, false);
            if (len > 0) pending.append(large.data(), (size_t) len);
        } else if (len > 0) {
            pending.append(buf, (size_t) len);
        }
        if (len > 0) {
            size_t valid = utf8_valid_prefix_len(pending.data(), pending.size());
            if (valid == 0 && pending.size() >= 4) {
                // A byte that can never start a valid sequence would otherwise
                // block the buffer forever and silently truncate the output.
                pending.replace(0, 1, "\xEF\xBF\xBD");
                valid = utf8_valid_prefix_len(pending.data(), pending.size());
            }
            if (valid > 0) {
                const std::string piece = pending.substr(0, valid);
                pending.erase(0, valid);
                result += piece;
                if (listener != nullptr) {
                    jstring jpiece = utf8_to_jstring(env, piece);
                    const jboolean keep_going =
                        env->CallBooleanMethod(listener, on_token, jpiece);
                    env->DeleteLocalRef(jpiece);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        stopped_by_listener = true;
                    } else if (keep_going == JNI_FALSE) {
                        stopped_by_listener = true;
                    }
                    if (stopped_by_listener) break;
                }
            }
        }

        if (i + 1 < n_gen) {
            llama_batch batch = llama_batch_get_one(&token, 1);
            if (llama_decode(ctx, batch) != 0) break;
        }
    }

    llama_sampler_free(sampler);
    return utf8_to_jstring(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_stastyle_localsummarizer_nativebridge_LlamaBridge_nativeCancel(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_llama_cancel = true;
}

// Cleared only when a new run starts, so a cancel that arrives while the model
// is still loading is not lost.
extern "C" JNIEXPORT void JNICALL
Java_com_stastyle_localsummarizer_nativebridge_LlamaBridge_nativeResetCancel(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_llama_cancel = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_stastyle_localsummarizer_nativebridge_LlamaBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle_ptr) {
    auto * handle = (LlamaHandle *) (intptr_t) handle_ptr;
    if (handle == nullptr) return;
    if (handle->ctx != nullptr) llama_free(handle->ctx);
    if (handle->model != nullptr) llama_model_free(handle->model);
    delete handle;
}
