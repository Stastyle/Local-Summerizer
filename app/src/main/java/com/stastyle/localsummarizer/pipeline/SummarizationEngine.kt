package com.stastyle.localsummarizer.pipeline

import com.stastyle.localsummarizer.nativebridge.LlamaBridge

/**
 * The model operations [HierarchicalSummarizer] needs. Extracted from the JNI
 * bridge so the chunking arithmetic — which decides summary quality on long
 * meetings — can be exercised without a real model.
 */
interface SummarizationEngine {
    fun tokenCount(text: String): Int

    /** [onPiece] receives streamed text and returns false to stop early. */
    fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        onPiece: (String) -> Boolean,
    ): String
}

class LlamaEngine(private val handle: Long) : SummarizationEngine {

    override fun tokenCount(text: String): Int = LlamaBridge.tokenCount(handle, text)

    override fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        onPiece: (String) -> Boolean,
    ): String = LlamaBridge.generate(
        handle = handle,
        prompt = prompt,
        maxTokens = maxTokens,
        temperature = temperature,
        listener = { piece -> onPiece(piece) },
    )
}
