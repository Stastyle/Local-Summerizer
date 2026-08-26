#pragma once

#include <string>

/**
 * True when every character of [text] is Hebrew, a digit, punctuation,
 * whitespace, or a single ASCII letter.
 *
 * Used to build a logit-bias allow-list that keeps a small multilingual model
 * from code-switching mid-Hebrew-sentence. Getting this wrong in the other
 * direction is severe — rejecting Hebrew pieces would suppress the output
 * entirely — so it is covered by scripts/test_hebrew_filter.cpp.
 */
bool is_hebrew_safe(const std::string & text);
