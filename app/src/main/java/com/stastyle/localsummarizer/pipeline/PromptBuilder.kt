package com.stastyle.localsummarizer.pipeline

/**
 * Qwen ChatML prompt formatting, plus the Hebrew instructions used for
 * hierarchical (chunked) summarization of long meetings.
 */
object PromptBuilder {

    /**
     * [assistantPrefix] is put after the assistant turn opens, so generation
     * continues it rather than deciding how to begin. Starting the model
     * inside the required shape is worth more than asking it to produce that
     * shape — it is prompt, not output, so callers prepend it to the result.
     */
    fun chatMl(
        systemPrompt: String,
        userContent: String,
        assistantPrefix: String = "",
    ): String = buildString {
        append("<|im_start|>system\n")
        append(systemPrompt)
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append(userContent)
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
        append(assistantPrefix)
    }

    /** First heading of the master-prompt skeleton, used as the prefill. */
    const val SUMMARY_PREFIX: String = "## תקציר מנהלים\n"

    private const val LANGUAGE_REMINDER = "\n\nענה בעברית בלבד."

    fun transcriptUserContent(transcript: String): String =
        "להלן תמליל הישיבה:\n$transcript$LANGUAGE_REMINDER"

    const val CHUNK_SYSTEM_PROMPT: String =
        "שפת הפלט: עברית בלבד.\n\n" +
            "אתה עוזר שמסכם קטע אחד מתוך תמליל ארוך של ישיבה. " +
            "סכם את הקטע בעברית באופן תמציתי אך שמור על כל המידע המהותי: " +
            "נושאים שנדונו, עמדות שהובעו, החלטות, משימות, שמות, מספרים ותאריכים. " +
            "אל תמציא פרטים ואל תוסיף הקדמות, כותרות או סיכומי-על — רק את תוכן הקטע.\n" +
            "התמליל הופק בזיהוי דיבור אוטומטי ועלולות להיות בו מילים משובשות; " +
            "הסתמך על ההקשר ואל תנחש שמות שאינך בטוח בהם.\n\n" +
            "כלל מחייב: כל תו בתשובה חייב להיות בעברית, בספרות או בסימני פיסוק."

    fun chunkUserContent(chunk: String, index: Int, total: Int): String =
        "זהו חלק ${index + 1} מתוך $total של תמליל הישיבה:\n$chunk$LANGUAGE_REMINDER"

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
