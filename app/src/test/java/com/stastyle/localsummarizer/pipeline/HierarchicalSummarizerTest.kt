package com.stastyle.localsummarizer.pipeline

import com.stastyle.localsummarizer.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the chunking arithmetic that decides whether a long meeting gets
 * summarized at all. A real tokenizer is not needed — only a consistent one.
 */
class HierarchicalSummarizerTest {

    /** Roughly one token per four characters, like a real BPE on prose. */
    private class FakeEngine(
        private val summaryTokens: Int = 120,
    ) : SummarizationEngine {
        val prompts = ArrayList<String>()

        override fun tokenCount(text: String): Int = (text.length + 3) / 4

        override fun generate(
            prompt: String,
            maxTokens: Int,
            temperature: Float,
            onPiece: (String) -> Boolean,
        ): String {
            prompts += prompt
            // Produce a summary of a realistic size for the requested budget.
            val words = minOf(maxTokens, summaryTokens)
            return (1..words).joinToString(" ") { "מילה$it" }
        }
    }

    private fun settings(contextSize: Int = 4096, maxTokens: Int = 1500) = AppSettings(
        whisperModelUri = "x",
        llamaModelUri = "y",
        contextSize = contextSize,
        maxTokens = maxTokens,
    )

    private fun transcript(lines: Int) =
        (1..lines).joinToString("\n") { "דובר $it: זהו משפט לדוגמה בתמליל הישיבה עם מספיק תוכן." }

    private fun summarizer(engine: SummarizationEngine, settings: AppSettings) =
        HierarchicalSummarizer(
            engine = engine,
            settings = settings,
            onState = {},
            isCancelled = { false },
        )

    @Test
    fun `a short transcript is summarized in one pass`() {
        val engine = FakeEngine()
        summarizer(engine, settings()).summarize(transcript(5))
        assertEquals(1, engine.prompts.size)
    }

    @Test
    fun `every chunk prompt fits the context`() {
        val engine = FakeEngine()
        val config = settings()
        summarizer(engine, config).summarize(transcript(600))
        assertTrue("expected chunking", engine.prompts.size > 1)
        for (prompt in engine.prompts) {
            assertTrue(
                "prompt of ${engine.tokenCount(prompt)} tokens exceeds ${config.contextSize}",
                engine.tokenCount(prompt) < config.contextSize,
            )
        }
    }

    @Test
    fun `a very long meeting folds its partials until the merge fits`() {
        // Small context and large partial summaries: the naive single merge
        // would not fit, so the fold-to-fit pass has to kick in.
        val engine = FakeEngine(summaryTokens = 700)
        val config = settings(contextSize = 2048, maxTokens = 400)
        summarizer(engine, config).summarize(transcript(2000))

        for (prompt in engine.prompts) {
            assertTrue(
                "merge prompt of ${engine.tokenCount(prompt)} tokens exceeds ${config.contextSize}",
                engine.tokenCount(prompt) < config.contextSize,
            )
        }
    }

    @Test
    fun `a transcript with no line breaks is still split`() {
        val engine = FakeEngine()
        val config = settings()
        val oneLine = "מילה ".repeat(20000)
        summarizer(engine, config).summarize(oneLine)
        assertTrue("expected chunking", engine.prompts.size > 1)
        for (prompt in engine.prompts) {
            assertTrue(engine.tokenCount(prompt) < config.contextSize)
        }
    }

    @Test
    fun `cancellation stops before issuing every chunk`() {
        val engine = FakeEngine()
        var calls = 0
        val summary = HierarchicalSummarizer(
            engine = engine,
            settings = settings(),
            onState = {},
            isCancelled = { ++calls > 3 },
        ).summarize(transcript(600))
        assertEquals("", summary)
    }
}
