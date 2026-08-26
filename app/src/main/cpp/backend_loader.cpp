#include <jni.h>

#include <string>

#include "ggml-backend.h"
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
