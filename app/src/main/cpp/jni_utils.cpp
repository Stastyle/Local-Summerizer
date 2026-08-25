#include "jni_utils.h"

#include <cstdint>
#include <vector>

std::string jstring_to_utf8(JNIEnv * env, jstring str) {
    if (str == nullptr) {
        return {};
    }
    const jsize len = env->GetStringLength(str);
    const jchar * chars = env->GetStringChars(str, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string out;
    out.reserve((size_t) len * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }

        if (cp < 0x80) {
            out.push_back((char) cp);
        } else if (cp < 0x800) {
            out.push_back((char) (0xC0 | (cp >> 6)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char) (0xE0 | (cp >> 12)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char) (0xF0 | (cp >> 18)));
            out.push_back((char) (0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(str, chars);
    return out;
}

// Decodes one UTF-8 sequence starting at data[0]; returns its byte length and
// writes the code point, or returns 0 when the sequence is invalid, or -1 when
// the sequence is a valid prefix that is merely incomplete.
static int decode_utf8(const uint8_t * data, size_t len, uint32_t * cp) {
    if (len == 0) return -1;
    const uint8_t b0 = data[0];
    int need;
    uint32_t value;
    if (b0 < 0x80) {
        *cp = b0;
        return 1;
    } else if ((b0 & 0xE0) == 0xC0) {
        need = 1; value = b0 & 0x1F;
    } else if ((b0 & 0xF0) == 0xE0) {
        need = 2; value = b0 & 0x0F;
    } else if ((b0 & 0xF8) == 0xF0) {
        need = 3; value = b0 & 0x07;
    } else {
        return 0;
    }
    if ((size_t) need > len - 1) {
        // check the continuation bytes we do have; if they are valid so far,
        // report "incomplete" so the caller can wait for more bytes
        for (size_t i = 1; i < len; ++i) {
            if ((data[i] & 0xC0) != 0x80) return 0;
        }
        return -1;
    }
    for (int i = 1; i <= need; ++i) {
        if ((data[i] & 0xC0) != 0x80) return 0;
        value = (value << 6) | (data[i] & 0x3F);
    }
    // reject overlong encodings, surrogates and out-of-range values
    if (need == 1 && value < 0x80) return 0;
    if (need == 2 && value < 0x800) return 0;
    if (need == 3 && value < 0x10000) return 0;
    if (value >= 0xD800 && value <= 0xDFFF) return 0;
    if (value > 0x10FFFF) return 0;
    *cp = value;
    return need + 1;
}

size_t utf8_valid_prefix_len(const char * data, size_t len) {
    const uint8_t * bytes = (const uint8_t *) data;
    size_t pos = 0;
    while (pos < len) {
        uint32_t cp;
        const int step = decode_utf8(bytes + pos, len - pos, &cp);
        if (step <= 0) break;
        pos += (size_t) step;
    }
    return pos;
}

jstring utf8_to_jstring(JNIEnv * env, const char * data, size_t len) {
    std::vector<jchar> out;
    out.reserve(len);
    const uint8_t * bytes = (const uint8_t *) data;
    size_t pos = 0;
    while (pos < len) {
        uint32_t cp;
        int step = decode_utf8(bytes + pos, len - pos, &cp);
        if (step == 0 || step == -1) {
            cp = 0xFFFD;
            step = 1;
        }
        pos += (size_t) step;
        if (cp < 0x10000) {
            out.push_back((jchar) cp);
        } else {
            cp -= 0x10000;
            out.push_back((jchar) (0xD800 + (cp >> 10)));
            out.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(out.data(), (jsize) out.size());
}

jstring utf8_to_jstring(JNIEnv * env, const std::string & str) {
    return utf8_to_jstring(env, str.data(), str.size());
}

void throw_runtime_exception(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}
