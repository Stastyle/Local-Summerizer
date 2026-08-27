package com.stastyle.localsummarizer.pipeline

import com.stastyle.localsummarizer.data.settings.DEFAULT_MASTER_PROMPT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `chatMl matches the Qwen template exactly`() {
        val prompt = PromptBuilder.chatMl("SYS", "USER")
        assertEquals(
            "<|im_start|>system\nSYS<|im_end|>\n" +
                "<|im_start|>user\nUSER<|im_end|>\n" +
                "<|im_start|>assistant\n",
            prompt,
        )
    }

    @Test
    fun `transcript is introduced in Hebrew`() {
        val content = PromptBuilder.transcriptUserContent("שלום")
        assertTrue(content.startsWith("להלן תמליל הישיבה:"))
        assertTrue(content.contains("שלום"))
    }

    @Test
    fun `the language rule is the last thing before the model answers`() {
        // A rule stated only in the system prompt sits behind the whole
        // transcript by the time the first token is generated. Both user
        // turns repeat it in the slot closest to that token.
        assertTrue(PromptBuilder.transcriptUserContent("שלום").endsWith("ענה בעברית בלבד."))
        assertTrue(
            PromptBuilder.chunkUserContent("טקסט", index = 0, total = 2)
                .endsWith("ענה בעברית בלבד."),
        )
    }

    @Test
    fun `the prefill starts the summary inside the required skeleton`() {
        val prompt = PromptBuilder.chatMl("sys", "user", PromptBuilder.SUMMARY_PREFIX)
        assertTrue(prompt.endsWith("<|im_start|>assistant\n" + PromptBuilder.SUMMARY_PREFIX))
        // It is the first heading of the master prompt, so the model continues
        // the skeleton rather than choosing how to open.
        assertTrue(DEFAULT_MASTER_PROMPT.contains(PromptBuilder.SUMMARY_PREFIX.trim()))
        // Without a prefill the assistant turn is still left open as before.
        assertTrue(PromptBuilder.chatMl("sys", "user").endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `the master prompt does not offer a way to skip a section`() {
        // "write none" was being taken as the default: a real decision in the
        // transcript came back as an empty Decisions section.
        assertTrue(DEFAULT_MASTER_PROMPT.contains("השמט"))
        assertTrue(!DEFAULT_MASTER_PROMPT.contains("כתוב בו \"אין\""))
    }

    @Test
    fun `both system prompts open and close with the language rule`() {
        // Position matters more than wording: first line sets the frame,
        // last line is what the model saw most recently.
        for (prompt in listOf(DEFAULT_MASTER_PROMPT, PromptBuilder.CHUNK_SYSTEM_PROMPT)) {
            assertTrue(prompt.startsWith("שפת הפלט: עברית בלבד."))
            assertTrue(prompt.trimEnd().endsWith("בספרות או בסימני פיסוק."))
        }
    }

    @Test
    fun `merge keeps every partial summary and its order`() {
        val merged = PromptBuilder.mergeUserContent(listOf("ראשון", "שני", "שלישי"))
        assertTrue(merged.contains("ראשון"))
        assertTrue(merged.contains("שני"))
        assertTrue(merged.contains("שלישי"))
        assertTrue(merged.indexOf("ראשון") < merged.indexOf("שני"))
        assertTrue(merged.indexOf("שני") < merged.indexOf("שלישי"))
    }

    @Test
    fun `chunk content is numbered from one`() {
        val content = PromptBuilder.chunkUserContent("טקסט", index = 0, total = 3)
        assertTrue(content.contains("חלק 1 מתוך 3"))
    }
}
