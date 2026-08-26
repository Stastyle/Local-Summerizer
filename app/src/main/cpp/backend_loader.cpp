#include <jni.h>

#include <string>

#include "ggml-backend.h"
#include "jni_utils.h"

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
}

extern "C" JNIEXPORT jint JNICALL
Java_com_stastyle_localsummarizer_nativebridge_NativeLib_nativeBackendCount(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return (jint) ggml_backend_reg_count();
}
