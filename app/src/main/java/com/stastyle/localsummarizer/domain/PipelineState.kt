package com.stastyle.localsummarizer.domain

sealed interface PipelineState {
    data object Idle : PipelineState
    data object Decoding : PipelineState
    data object LoadingWhisper : PipelineState
    data class Transcribing(val percent: Int, val partialText: String = "") : PipelineState
    data object LoadingLlama : PipelineState
    data class Summarizing(
        val chunkIndex: Int,
        val chunkCount: Int,
        val partialText: String = "",
        val transcript: String = "",
    ) : PipelineState
    data class Done(val transcript: String, val summary: String) : PipelineState
    data class Failed(val message: String) : PipelineState
    data object Cancelled : PipelineState

    val isRunning: Boolean
        get() = this is Decoding || this is LoadingWhisper || this is Transcribing ||
            this is LoadingLlama || this is Summarizing
}
