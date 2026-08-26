#include "hebrew_filter.h"

#include <cstdint>

bool is_hebrew_safe(const std::string & text) {
    size_t i = 0;
    size_t latin_letters = 0;
    while (i < text.size()) {
        const unsigned char c = (unsigned char) text[i];
        uint32_t cp = 0;
        size_t width = 1;
        if (c < 0x80) { cp = c; width = 1; }
        else if ((c & 0xE0) == 0xC0 && i + 1 < text.size()) {
            cp = ((uint32_t) (c & 0x1F) << 6) | (uint32_t) (text[i + 1] & 0x3F);
            width = 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < text.size()) {
            cp = ((uint32_t) (c & 0x0F) << 12) |
                 ((uint32_t) (text[i + 1] & 0x3F) << 6) | (uint32_t) (text[i + 2] & 0x3F);
            width = 3;
        } else {
            return false; // 4-byte planes hold emoji and CJK extensions
        }
        i += width;

        const bool hebrew = (cp >= 0x0590 && cp <= 0x05FF) ||   // Hebrew block
                            (cp >= 0xFB1D && cp <= 0xFB4F);     // presentation forms
        const bool digit = cp >= '0' && cp <= '9';
        const bool ascii_letter = (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
        // Everything printable-ASCII that is not a letter, plus the general
        // punctuation block: quotes, dashes, bullets, the Markdown markers.
        const bool punctuation = (cp < 0x80 && !ascii_letter && !digit) ||
                                 (cp >= 0x2010 && cp <= 0x205F);
        if (ascii_letter) {
            // One letter per token: "GPS" and "F-16" are still spellable
            // letter by letter, while "the" and "queens" are not.
            if (++latin_letters > 1) return false;
            continue;
        }
        if (!hebrew && !digit && !punctuation) return false;
    }
    return true;
}
