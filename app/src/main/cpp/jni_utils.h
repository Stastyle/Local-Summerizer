#pragma once

#include <jni.h>
#include <cstddef>
#include <string>

// UTF-16 (jstring) -> real UTF-8 (handles surrogate pairs, unlike modified UTF-8).
std::string jstring_to_utf8(JNIEnv * env, jstring str);

// Real UTF-8 -> jstring via UTF-16. Invalid byte sequences are replaced with
// U+FFFD instead of crashing the JVM the way NewStringUTF would.
jstring utf8_to_jstring(JNIEnv * env, const char * data, size_t len);
jstring utf8_to_jstring(JNIEnv * env, const std::string & str);

// Length of the longest prefix of data that is complete, valid UTF-8. Used to
// stream token pieces to Java without splitting multi-byte characters.
size_t utf8_valid_prefix_len(const char * data, size_t len);

void throw_runtime_exception(JNIEnv * env, const std::string & message);

// True once ggml has a CPU device registered. Nearly every ggml entry point
// asserts on a null device rather than returning an error, so callers check
// this first and raise a Java exception instead of aborting the process.
bool ggml_cpu_backend_available();
