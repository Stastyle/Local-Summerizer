// Host test for the Hebrew logit-bias allow-list.
//
// This predicate decides which vocabulary tokens the summarizer is allowed to
// emit. A false negative is severe and silent: reject Hebrew pieces and the
// model can produce nothing at all. Build and run with
//
//   g++ -std=c++17 -I app/src/main/cpp -o /tmp/hebfilter \
//       scripts/test_hebrew_filter.cpp app/src/main/cpp/hebrew_filter.cpp && /tmp/hebfilter

#include "hebrew_filter.h"

#include <cstdio>
#include <vector>

namespace {

struct Case {
    const char * text;
    bool         allowed;
    const char * why;
};

} // namespace

int main() {
    const std::vector<Case> cases = {
        // Must pass: everything a Hebrew Markdown summary is made of.
        { "שלום", true, "Hebrew word" },
        { " ישיבה", true, "Hebrew with the leading space BPE keeps" },
        { "כוננות", true, "Hebrew" },
        { "החלטות", true, "Hebrew" },
        { "״", true, "gershayim U+05F4, used by every Hebrew acronym" },
        { "׳", true, "geresh U+05F3" },
        { "\n", true, "newline" },
        { "\n\n", true, "paragraph break" },
        { "## ", true, "Markdown heading marker" },
        { "- ", true, "list bullet" },
        { "1500", true, "digits" },
        { ".", true, "period" },
        { ":", true, "colon" },
        { "—", true, "em dash U+2014" },
        { "…", true, "ellipsis U+2026" },
        { "A", true, "lone ASCII letter, so acronyms stay spellable" },
        { "-", true, "hyphen, for model numbers like F-16" },
        { "16", true, "digits, same" },

        // Must be blocked: the exact leaks observed on the device, plus the
        // scripts a deny-list would have missed.
        { "queens", false, "English word seen mid-Hebrew-sentence" },
        { " the", false, "English function word" },
        { "and", false, "English" },
        { "Notification", false, "English, seen in a Hebrew summary" },
        { "最少", false, "Chinese, seen in a Hebrew summary" },
        { "مرحبا", false, "Arabic: the nearest neighbour script" },
        { "Привет", false, "Cyrillic" },
        { "こんにちは", false, "Japanese" },
        { "안녕", false, "Hangul" },
        { "café", false, "accented Latin" },
        { "Ελλάδα", false, "Greek" },
        { "😀", false, "emoji, outside the BMP" },
    };

    int failures = 0;
    for (const Case & c : cases) {
        const bool got = is_hebrew_safe(c.text);
        if (got != c.allowed) {
            ++failures;
            printf("FAIL  %-14s allowed=%-3s want=%-3s  %s\n",
                   c.text, got ? "yes" : "no", c.allowed ? "yes" : "no", c.why);
        }
    }

    printf("%zu cases, %d failures\n", cases.size(), failures);
    return failures == 0 ? 0 : 1;
}
