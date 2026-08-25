package com.stastyle.localsummarizer.pipeline

import com.stastyle.localsummarizer.data.settings.AppSettings
import com.stastyle.localsummarizer.domain.PipelineState
import com.stastyle.localsummarizer.nativebridge.LlamaBridge

/**
 * Summarizes transcripts of any length. Short transcripts get a single pass
 * with the user's master prompt; transcripts that do not fit in the model
 * context are split on line boundaries, each part is summarized separately,
 * and a final pass merges the partial summaries.
 */
class HierarchicalSummarizer(
    private val handle: Long,
    private val settings: AppSettings,
    private val onState: (PipelineState) -> Unit,
    private val isCancelled: () -> Boolean,
) {

    fun summarize(transcript: String): String {
        val maxTokens = settings.maxTokens
        val inputBudget = inputTokenBudget(settings.masterPrompt, maxTokens)
        val transcriptTokens =
            LlamaBridge.tokenCount(handle, PromptBuilder.transcriptUserContent(transcript))

        if (transcriptTokens <= inputBudget) {
            return generate(
                systemPrompt = settings.masterPrompt,
                userContent = PromptBuilder.transcriptUserContent(transcript),
                maxTokens = maxTokens,
                chunkIndex = 0,
                chunkCount = 1,
            )
        }

        // hierarchical path
        val chunkBudget = inputTokenBudget(PromptBuilder.CHUNK_SYSTEM_PROMPT, CHUNK_SUMMARY_TOKENS)
        val chunks = splitByTokenBudget(transcript, chunkBudget)
        val partialSummaries = ArrayList<String>(chunks.size)
        chunks.forEachIndexed { index, chunk ->
            if (isCancelled()) return ""
            val partial = generate(
                systemPrompt = PromptBuilder.CHUNK_SYSTEM_PROMPT,
                userContent = PromptBuilder.chunkUserContent(chunk, index, chunks.size),
                maxTokens = CHUNK_SUMMARY_TOKENS,
                chunkIndex = index,
                chunkCount = chunks.size + 1,
            )
            partialSummaries += partial
        }
        if (isCancelled()) return ""

        return generate(
            systemPrompt = settings.masterPrompt,
            userContent = PromptBuilder.mergeUserContent(partialSummaries),
            maxTokens = maxTokens,
            chunkIndex = chunks.size,
            chunkCount = chunks.size + 1,
        )
    }

    private fun generate(
        systemPrompt: String,
        userContent: String,
        maxTokens: Int,
        chunkIndex: Int,
        chunkCount: Int,
    ): String {
        val partial = StringBuilder()
        onState(PipelineState.Summarizing(chunkIndex, chunkCount, ""))
        val result = LlamaBridge.generate(
            handle = handle,
            prompt = PromptBuilder.chatMl(systemPrompt, userContent),
            maxTokens = maxTokens,
            temperature = settings.temperature,
        ) { piece ->
            partial.append(piece)
            onState(PipelineState.Summarizing(chunkIndex, chunkCount, partial.toString()))
            !isCancelled()
        }
        return result.trim()
    }

    /** Input tokens available for the transcript within the model context. */
    private fun inputTokenBudget(systemPrompt: String, outputTokens: Int): Int {
        val overhead = LlamaBridge.tokenCount(
            handle, PromptBuilder.chatMl(systemPrompt, ""),
        )
        val budget = settings.contextSize - overhead - outputTokens - SAFETY_MARGIN_TOKENS
        return budget.coerceAtLeast(MIN_INPUT_BUDGET)
    }

    /**
     * Splits text on line boundaries so that each chunk stays within the given
     * token budget. Uses a chars-per-token estimate refined by a verification
     * pass with the real tokenizer.
     */
    private fun splitByTokenBudget(text: String, budget: Int): List<String> {
        val totalTokens = LlamaBridge.tokenCount(handle, text).coerceAtLeast(1)
        val charsPerToken = text.length.toDouble() / totalTokens
        val chunkCharBudget = (budget * charsPerToken * 0.85).toInt().coerceAtLeast(500)

        val lines = text.split('\n')
        val rawChunks = ArrayList<String>()
        val current = StringBuilder()
        for (line in lines) {
            if (current.isNotEmpty() && current.length + line.length + 1 > chunkCharBudget) {
                rawChunks += current.toString()
                current.setLength(0)
            }
            if (line.length > chunkCharBudget) {
                // pathological single line: hard-split by characters
                var start = 0
                while (start < line.length) {
                    val end = (start + chunkCharBudget).coerceAtMost(line.length)
                    rawChunks += line.substring(start, end)
                    start = end
                }
                continue
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) rawChunks += current.toString()

        // verify against the real tokenizer; halve anything still too big
        val verified = ArrayList<String>(rawChunks.size)
        for (chunk in rawChunks) {
            verified += fitChunk(chunk, budget)
        }
        return verified
    }

    private fun fitChunk(chunk: String, budget: Int): List<String> {
        if (LlamaBridge.tokenCount(handle, chunk) <= budget) return listOf(chunk)
        if (chunk.length < 200) return listOf(chunk.take(budget * 2))
        val middle = chunk.length / 2
        val splitAt = chunk.indexOf('\n', middle).takeIf { it in 1 until chunk.length - 1 }
            ?: middle
        val first = chunk.substring(0, splitAt)
        val second = chunk.substring(splitAt)
        return fitChunk(first, budget) + fitChunk(second, budget)
    }

    private companion object {
        const val CHUNK_SUMMARY_TOKENS = 700
        const val SAFETY_MARGIN_TOKENS = 64
        const val MIN_INPUT_BUDGET = 256
    }
}
