package com.stastyle.localsummarizer.pipeline

import com.stastyle.localsummarizer.data.settings.AppSettings
import com.stastyle.localsummarizer.domain.PipelineState

/**
 * Summarizes transcripts of any length. Short transcripts get a single pass
 * with the user's master prompt; transcripts that do not fit in the model
 * context are split on line boundaries, each part is summarized separately,
 * and a final pass merges the partial summaries.
 */
class HierarchicalSummarizer(
    private val engine: SummarizationEngine,
    private val settings: AppSettings,
    private val onState: (PipelineState) -> Unit,
    private val isCancelled: () -> Boolean,
) {

    private var transcriptForUi: String = ""

    fun summarize(transcript: String): String {
        transcriptForUi = transcript
        val maxTokens = settings.maxTokens
        val inputBudget = inputTokenBudget(settings.masterPrompt, maxTokens)
        val transcriptTokens =
            engine.tokenCount(PromptBuilder.transcriptUserContent(transcript))

        if (transcriptTokens <= inputBudget) {
            return generate(
                systemPrompt = settings.masterPrompt,
                userContent = PromptBuilder.transcriptUserContent(transcript),
                maxTokens = maxTokens,
                chunkIndex = 0,
                chunkCount = 1,
                assistantPrefix = PromptBuilder.SUMMARY_PREFIX,
            )
        }

        // hierarchical path
        val partialTokens = partialSummaryTokens()
        val chunkBudget = inputTokenBudget(PromptBuilder.CHUNK_SYSTEM_PROMPT, partialTokens)
        val chunks = splitByTokenBudget(transcript, chunkBudget)
        val partialSummaries = ArrayList<String>(chunks.size)
        chunks.forEachIndexed { index, chunk ->
            if (isCancelled()) return ""
            val partial = generate(
                systemPrompt = PromptBuilder.CHUNK_SYSTEM_PROMPT,
                userContent = PromptBuilder.chunkUserContent(chunk, index, chunks.size),
                maxTokens = partialTokens,
                chunkIndex = index,
                chunkCount = chunks.size + 1,
            )
            partialSummaries += partial
        }
        if (isCancelled()) return ""

        val merged = foldToFit(partialSummaries, inputBudget, partialTokens)
        if (isCancelled()) return ""

        return generate(
            systemPrompt = settings.masterPrompt,
            userContent = PromptBuilder.mergeUserContent(trimToFit(merged, inputBudget)),
            maxTokens = maxTokens,
            chunkIndex = chunks.size,
            chunkCount = chunks.size + 1,
            assistantPrefix = PromptBuilder.SUMMARY_PREFIX,
        )
    }

    /**
     * Intermediate summaries have to be small enough that several of them fit
     * in one merge prompt, so their size follows the context rather than being
     * a fixed number that overflows a small one.
     */
    private fun partialSummaryTokens(): Int =
        (settings.contextSize / 8).coerceIn(MIN_PARTIAL_TOKENS, MAX_PARTIAL_TOKENS)

    /**
     * A very long meeting produces more partial summaries than fit in one
     * merge prompt, so they are combined into intermediate summaries until the
     * final merge fits. Groups are sized against the budget rather than folded
     * blindly in pairs, because a pair of large partials can itself overflow
     * the context.
     */
    private fun foldToFit(partials: List<String>, mergeBudget: Int, partialTokens: Int): List<String> {
        var current = partials
        var rounds = 0
        while (current.size > 1 && !fits(current, mergeBudget)) {
            if (isCancelled()) return current
            if (++rounds > MAX_FOLD_ROUNDS) break

            val foldBudget = inputTokenBudget(PromptBuilder.CHUNK_SYSTEM_PROMPT, partialTokens)
            val groups = groupWithinBudget(current, foldBudget)
            if (groups.size >= current.size) break // cannot combine any further

            current = groups.map { group ->
                if (isCancelled()) return current
                if (group.size == 1) {
                    group[0]
                } else {
                    generate(
                        systemPrompt = PromptBuilder.CHUNK_SYSTEM_PROMPT,
                        userContent = PromptBuilder.mergeUserContent(group),
                        maxTokens = partialTokens,
                        chunkIndex = 0,
                        chunkCount = 1,
                    )
                }
            }
        }
        return current
    }

    private fun fits(partials: List<String>, budget: Int): Boolean =
        engine.tokenCount(PromptBuilder.mergeUserContent(partials)) <= budget

    /** Greedily packs consecutive partials into groups that fit [budget]. */
    private fun groupWithinBudget(partials: List<String>, budget: Int): List<List<String>> {
        val groups = ArrayList<List<String>>()
        var group = ArrayList<String>()
        for (partial in partials) {
            if (group.isNotEmpty() && !fits(group + partial, budget)) {
                groups += group
                group = arrayListOf(partial)
            } else {
                group.add(partial)
            }
        }
        if (group.isNotEmpty()) groups += group
        return groups
    }

    /**
     * Last resort when folding cannot shrink the merge any further: keep the
     * partials that fit and truncate the next one, so a very long meeting
     * still yields a summary instead of an error.
     */
    private fun trimToFit(partials: List<String>, budget: Int): List<String> {
        if (partials.isEmpty() || fits(partials, budget)) return partials
        val kept = ArrayList<String>()
        for (partial in partials) {
            if (fits(kept + partial, budget)) {
                kept += partial
            } else {
                val remaining = budget - engine.tokenCount(PromptBuilder.mergeUserContent(kept))
                if (remaining > MIN_TRUNCATED_TOKENS) {
                    val charsPerToken = (partial.length.toDouble() /
                        engine.tokenCount(partial).coerceAtLeast(1))
                    val take = ((remaining - MIN_TRUNCATED_TOKENS) * charsPerToken).toInt()
                    if (take > 0) kept += partial.take(take.coerceAtMost(partial.length))
                }
                break
            }
        }
        return kept.ifEmpty { listOf(partials.first().take(budget)) }
    }

    private fun generate(
        systemPrompt: String,
        userContent: String,
        maxTokens: Int,
        chunkIndex: Int,
        chunkCount: Int,
        assistantPrefix: String = "",
    ): String {
        val partial = StringBuilder()
        onState(
            PipelineState.Summarizing(chunkIndex, chunkCount, assistantPrefix, transcriptForUi),
        )
        val result = engine.generate(
            prompt = PromptBuilder.chatMl(systemPrompt, userContent, assistantPrefix),
            maxTokens = maxTokens,
            temperature = settings.temperature,
        ) { piece ->
            partial.append(piece)
            onState(
                PipelineState.Summarizing(
                    chunkIndex, chunkCount, assistantPrefix + partial, transcriptForUi,
                ),
            )
            !isCancelled()
        }
        // The prefill was prompt, so it is not in the model's output.
        return (assistantPrefix + result).trim()
    }

    /** Input tokens available for the transcript within the model context. */
    private fun inputTokenBudget(systemPrompt: String, outputTokens: Int): Int {
        val overhead = engine.tokenCount(PromptBuilder.chatMl(systemPrompt, ""))
        val budget = settings.contextSize - overhead - outputTokens - SAFETY_MARGIN_TOKENS
        return budget.coerceAtLeast(MIN_INPUT_BUDGET)
    }

    /**
     * Splits text on line boundaries so that each chunk stays within the given
     * token budget. Uses a chars-per-token estimate refined by a verification
     * pass with the real tokenizer.
     */
    private fun splitByTokenBudget(text: String, budget: Int): List<String> {
        val totalTokens = engine.tokenCount(text).coerceAtLeast(1)
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
        if (engine.tokenCount(chunk) <= budget) return listOf(chunk)
        if (chunk.length < 200) return listOf(chunk.take(budget * 2))
        val middle = chunk.length / 2
        val splitAt = chunk.indexOf('\n', middle).takeIf { it in 1 until chunk.length - 1 }
            ?: middle
        val first = chunk.substring(0, splitAt)
        val second = chunk.substring(splitAt)
        return fitChunk(first, budget) + fitChunk(second, budget)
    }

    private companion object {
        const val MIN_PARTIAL_TOKENS = 200
        const val MAX_PARTIAL_TOKENS = 700
        const val SAFETY_MARGIN_TOKENS = 64
        const val MIN_INPUT_BUDGET = 256
        const val MIN_TRUNCATED_TOKENS = 32
        const val MAX_FOLD_ROUNDS = 8
    }
}
