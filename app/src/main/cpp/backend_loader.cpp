#include <jni.h>

#include <cstddef>
#include <mutex>
#include <string>

#include "ggml-backend.h"
#include "ggml.h"
#include "llama.h"
#include "whisper.h"
#include "jni_utils.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "BackendLoader", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BackendLoader", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

namespace {

// Reading logcat needs a permission a sideloaded app cannot grant itself, so
// the engines' own log lines — which name the CPU variant that was loaded and
// why a model was rejected — are kept here for the in-app diagnostics screen.
constexpr size_t kLogBufferMax = 24 * 1024;

std::mutex  g_log_mutex;
std::string g_log_buffer;

void collect_log(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
#ifdef __ANDROID__
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                       : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                       : ANDROID_LOG_INFO;
    __android_log_print(priority, "ggml", "%s", text);
#else
    (void) level;
#endif
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_buffer += text;
    if (g_log_buffer.size() > kLogBufferMax) {
        g_log_buffer.erase(0, g_log_buffer.size() - kLogBufferMax);
    }
}

} // namespace

/**
 * ggml builds one CPU backend per ARM feature level and dlopens the best one
 * the device supports. Its default search path is derived from
 * /proc/self/exe, which on Android resolves to the zygote binary rather than
 * the APK's native library directory, and whisper.cpp never triggers the load
 * at all — so the app registers the backends explicitly at startup.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_stastyle_localsummarizer_nativebridge_NativeLib_nativeLoadBackends(
        JNIEnv * env, jobject /*thiz*/, jstring native_lib_dir) {
    // ggml appends to its registry without de-duplicating, and a failed load
    // leaves the Kotlin side willing to retry (the diagnostics screen does),
    // so guarantee the scan runs exactly once per process.
    static std::once_flag loaded_once;
    bool first = false;
    std::call_once(loaded_once, [&] { first = true; });
    if (!first) return;

    // All three funnel into ggml's logger; set them before anything can log.
    ggml_log_set(collect_log, nullptr);
    llama_log_set(collect_log, nullptr);
    whisper_log_set(collect_log, nullptr);

    const std::string dir = jstring_to_utf8(env, native_lib_dir);
    if (dir.empty()) {
        ggml_backend_load_all();
    } else {
        ggml_backend_load_all_from_path(dir.c_str());
    }
    const size_t devices = ggml_backend_dev_count();
    LOGI("searched %s: %zu backend(s), %zu device(s)",
         dir.empty() ? "(default paths)" : dir.c_str(), ggml_backend_reg_count(), devices);
    for (size_t i = 0; i < devices; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        LOGI("  device %zu: %s (%s)", i, ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
    }
    if (!ggml_cpu_backend_available()) {
        LOGE("no CPU backend registered — inference would abort");
    }
}

bool ggml_cpu_backend_available() {
    return ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) != nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_stastyle_localsummarizer_nativebridge_NativeLib_nativeBackendCount(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return (jint) ggml_backend_reg_count();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_stastyle_localsummarizer_nativebridge_NativeLib_nativeBackendReport(
        JNIEnv * env, jobject /*thiz*/) {
    std::string out = "registrations: " + std::to_string(ggml_backend_reg_count());
    const size_t devices = ggml_backend_dev_count();
    out += ", devices: " + std::to_string(devices);
    for (size_t i = 0; i < devices; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t free_mem = 0;
        size_t total_mem = 0;
        ggml_backend_dev_memory(dev, &free_mem, &total_mem);
        out += "\n  ";
        out += ggml_backend_dev_name(dev);
        out += " — ";
        out += ggml_backend_dev_description(dev);
        out += " (" + std::to_string(total_mem >> 20) + " MB total, "
             + std::to_string(free_mem >> 20) + " MB free)";
    }
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_stastyle_localsummarizer_nativebridge_NativeLib_nativeEngineLog(
        JNIEnv * env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return utf8_to_jstring(env, g_log_buffer);
}
