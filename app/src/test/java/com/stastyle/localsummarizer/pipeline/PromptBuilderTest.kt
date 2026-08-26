package com.stastyle.localsummarizer.pipeline

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
        assertTrue(content.endsWith("שלום"))
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
