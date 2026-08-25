#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#include "whisper.h"
#include "jni_utils.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WhisperBridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WhisperBridge", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

static std::atomic<bool> g_whisper_cancel{false};

namespace {

struct CallbackContext {
    JNIEnv *  env;
    jobject   listener;
    jmethodID on_progress;
    jmethodID on_segment;
    bool      failed;
};

void progress_callback(whisper_context * /*ctx*/, whisper_state * /*state*/, int progress, void * user_data) {
    auto * cb = (CallbackContext *) user_data;
    if (cb == nullptr || cb->failed || cb->listener == nullptr) return;
    cb->env->CallVoidMethod(cb->listener, cb->on_progress, (jint) progress);
    if (cb->env->ExceptionCheck()) {
        cb->env->ExceptionClear();
        cb->failed = true;
        g_whisper_cancel = true;
    }
}

void new_segment_callback(whisper_context * ctx, whisper_state * /*state*/, int n_new, void * user_data) {
    auto * cb = (CallbackContext *) user_data;
    if (cb == nullptr || cb->failed || cb->listener == nullptr) return;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = n_segments - n_new; i < n_segments; ++i) {
        const char * text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        jstring jtext = utf8_to_jstring(cb->env, text, strlen(text));
        cb->env->CallVoidMethod(cb->listener, cb->on_segment, jtext);
        cb->env->DeleteLocalRef(jtext);
        if (cb->env->ExceptionCheck()) {
            cb->env->ExceptionClear();
            cb->failed = true;
            g_whisper_cancel = true;
            return;
        }
    }
}

bool abort_callback(void * /*user_data*/) {
    return g_whisper_cancel.load();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_stastyle_localsummarizer_nativebridge_WhisperBridge_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring model_path) {
    const std::string path = jstring_to_utf8(env, model_path);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("failed to load whisper model from %s", path.c_str());
    }
    return (jlong) (intptr_t) ctx;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_stastyle_localsummarizer_nativebridge_WhisperBridge_nativeTranscribeFile(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jstring pcm_path,
        jstring language, jint n_threads, jboolean translate, jobject listener) {
    auto * ctx = (whisper_context *) (intptr_t) handle;
    if (ctx == nullptr) {
        throw_runtime_exception(env, "whisper context is not initialized");
        return nullptr;
    }

    // Read raw little-endian float32 PCM. A long meeting is hundreds of MB, so
    // it is kept on the native heap rather than in a Java float[].
    const std::string path = jstring_to_utf8(env, pcm_path);
    std::vector<float> samples;
    {
        FILE * f = fopen(path.c_str(), "rb");
        if (f == nullptr) {
            throw_runtime_exception(env, "cannot open decoded audio: " + path);
            return nullptr;
        }
        fseek(f, 0, SEEK_END);
        const long bytes = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (bytes <= 0) {
            fclose(f);
            throw_runtime_exception(env, "decoded audio is empty");
            return nullptr;
        }
        try {
            samples.resize((size_t) bytes / sizeof(float));
        } catch (const std::bad_alloc &) {
            fclose(f);
            throw_runtime_exception(env, "not enough memory for the decoded audio");
            return nullptr;
        }
        const size_t read = fread(samples.data(), sizeof(float), samples.size(), f);
        fclose(f);
        samples.resize(read);
        if (samples.empty()) {
            throw_runtime_exception(env, "decoded audio is empty");
            return nullptr;
        }
    }

    g_whisper_cancel = false;

    CallbackContext cb{};
    cb.env = env;
    cb.listener = listener;
    cb.failed = false;
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        cb.on_progress = env->GetMethodID(cls, "onProgress", "(I)V");
        cb.on_segment = env->GetMethodID(cls, "onSegment", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (cb.on_progress == nullptr || cb.on_segment == nullptr) {
            throw_runtime_exception(env, "transcribe listener is missing callbacks");
            return nullptr;
        }
    }

    const std::string lang = jstring_to_utf8(env, language);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = translate == JNI_TRUE;
    wparams.language         = lang.c_str(); // "auto" enables auto-detection
    wparams.n_threads        = n_threads;
    wparams.no_context       = true;

    wparams.progress_callback           = progress_callback;
    wparams.progress_callback_user_data = &cb;
    wparams.new_segment_callback           = new_segment_callback;
    wparams.new_segment_callback_user_data = &cb;
    wparams.abort_callback           = abort_callback;
    wparams.abort_callback_user_data = nullptr;

    const int ret = whisper_full(ctx, wparams, samples.data(), (int) samples.size());
    if (ret != 0 && !g_whisper_cancel.load()) {
        throw_runtime_exception(env, "whisper_full failed with code " + std::to_string(ret));
        return nullptr;
    }

    std::string text;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char * segment = whisper_full_get_segment_text(ctx, i);
        if (segment == nullptr) continue;
        std::string s(segment);
        // segments come with a leading space; trim it and use line breaks
        if (!s.empty() && s.front() == ' ') s.erase(s.begin());
        if (!text.empty()) text.push_back('\n');
        text += s;
    }
    return utf8_to_jstring(env, text);
}

extern "C" JNIEXPORT void JNICALL
Java_com_stastyle_localsummarizer_nativebridge_WhisperBridge_nativeCancel(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_whisper_cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_stastyle_localsummarizer_nativebridge_WhisperBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * ctx = (whisper_context *) (intptr_t) handle;
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
