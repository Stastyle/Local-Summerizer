package com.stastyle.localsummarizer.pipeline

/**
 * Qwen ChatML prompt formatting, plus the Hebrew instructions used for
 * hierarchical (chunked) summarization of long meetings.
 */
object PromptBuilder {

    fun chatMl(systemPrompt: String, userContent: String): String = buildString {
        append("<|im_start|>system\n")
        append(systemPrompt)
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append(userContent)
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    fun transcriptUserContent(transcript: String): String =
        "להלן תמליל הישיבה:\n$transcript"

    const val CHUNK_SYSTEM_PROMPT: String =
        "אתה עוזר שמסכם קטע אחד מתוך תמליל ארוך של ישיבה. " +
            "סכם את הקטע בעברית באופן תמציתי אך שמור על כל המידע המהותי: " +
            "נושאים שנדונו, עמדות שהובעו, החלטות, משימות, שמות, מספרים ותאריכים. " +
            "אל תמציא פרטים ואל תוסיף הקדמות או סיכומי-על — רק את תוכן הקטע. " +
            "כתוב אך ורק בעברית — אל תשתמש באנגלית או בשפה אחרת, גם לא במילה בודדת."

    fun chunkUserContent(chunk: String, index: Int, total: Int): String =
        "זהו חלק ${index + 1} מתוך $total של תמליל הישיבה:\n$chunk"

    fun mergeUserContent(partialSummaries: List<String>): String = buildString {
        append("להלן סיכומי ביניים של חלקי ישיבה ארוכה, לפי הסדר. ")
        append("אחד אותם לסיכום כולל אחד של הישיבה במלואה:\n\n")
        partialSummaries.forEachIndexed { index, summary ->
            append("--- חלק ${index + 1} ---\n")
            append(summary.trim())
            append("\n\n")
        }
    }
}
