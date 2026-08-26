#include <jni.h>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "ggml-backend.h"
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
    for (int i = std::max(0, n_segments - n_new); i < n_segments; ++i) {
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

// Unmaps the decoded PCM on every exit path, including the JNI error returns.
struct MappedPcm {
    void * addr;
    size_t size;
    ~MappedPcm() { if (addr != nullptr && addr != MAP_FAILED) munmap(addr, size); }
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_stastyle_localsummarizer_nativebridge_WhisperBridge_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring model_path) {
    // whisper_model_load reaches ggml_backend_dev_backend_reg(nullptr) when no
    // CPU device is registered, and that is a GGML_ASSERT — an abort, not a
    // return value. Refuse here so the failure is catchable in Kotlin.
    if (!ggml_cpu_backend_available()) {
        throw_runtime_exception(env, "no ggml CPU backend is registered");
        return 0;
    }
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

    // Map the decoded PCM instead of copying it: an hour of 16kHz mono float
    // is ~220MB, and whisper needs its own mel buffer on top of the model.
    const std::string path = jstring_to_utf8(env, pcm_path);
    const int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        throw_runtime_exception(env, "cannot open decoded audio: " + path);
        return nullptr;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < (off_t) sizeof(float)) {
        close(fd);
        throw_runtime_exception(env, "decoded audio is empty");
        return nullptr;
    }
    const size_t mapped_bytes = (size_t) st.st_size;
    void * mapped = mmap(nullptr, mapped_bytes, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) {
        throw_runtime_exception(env, "cannot map decoded audio");
        return nullptr;
    }
    const auto * samples_data = (const float *) mapped;
    const size_t n_samples = mapped_bytes / sizeof(float);
    MappedPcm pcm_guard{mapped, mapped_bytes};

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

    const int ret = whisper_full(ctx, wparams, samples_data, (int) n_samples);
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

// Cleared only when a new run starts, so a cancel that arrives while the model
// is still loading is not lost.
extern "C" JNIEXPORT void JNICALL
Java_com_stastyle_localsummarizer_nativebridge_WhisperBridge_nativeResetCancel(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_whisper_cancel = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_stastyle_localsummarizer_nativebridge_WhisperBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * ctx = (whisper_context *) (intptr_t) handle;
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
