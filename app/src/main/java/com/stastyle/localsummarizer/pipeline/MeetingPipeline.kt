package com.stastyle.localsummarizer.pipeline

import android.content.Context
import android.net.Uri
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.appContainer
import com.stastyle.localsummarizer.audio.AudioDecoder
import com.stastyle.localsummarizer.data.history.MeetingRecord
import com.stastyle.localsummarizer.data.settings.AppSettings
import com.stastyle.localsummarizer.domain.PipelineState
import com.stastyle.localsummarizer.nativebridge.LlamaBridge
import com.stastyle.localsummarizer.nativebridge.WhisperBridge

/**
 * Runs the full offline pipeline sequentially, keeping at most one model in
 * memory at a time:
 *
 *   decode audio -> load whisper -> transcribe -> FREE whisper
 *   -> load llama -> summarize (chunked if needed) -> FREE llama
 */
class MeetingPipeline(
    private val context: Context,
    private val settings: AppSettings,
    private val audioUri: Uri,
    private val audioName: String,
) {

    private val manager = PipelineManager

    private fun cancelled(): Boolean = manager.cancelRequested

    /** Returns the final state that was reported to [PipelineManager]. */
    suspend fun run(): PipelineState {
        val startedAt = System.currentTimeMillis()
        var transcript = ""
        try {
            // 1. decode audio to 16kHz mono PCM
            manager.update(PipelineState.Decoding)
            val pcm = AudioDecoder.decode(
                context = context,
                uri = audioUri,
                onProgress = { /* stage is indeterminate in the UI */ },
                isCancelled = ::cancelled,
            )
            if (cancelled()) return finish(PipelineState.Cancelled)

            // 2. whisper: load -> transcribe -> free
            transcript = transcribe(pcm)
            if (cancelled()) return finish(PipelineState.Cancelled)
            if (transcript.isBlank()) {
                return finish(fail(R.string.error_empty_transcript))
            }

            // 3. llama: load -> summarize -> free
            val summary = summarize(transcript)
            if (cancelled()) return finish(PipelineState.Cancelled)

            val record = MeetingRecord(
                createdAtEpochMs = startedAt,
                audioFileName = audioName,
                transcript = transcript,
                summary = summary,
                processingTimeMs = System.currentTimeMillis() - startedAt,
                whisperModelName = settings.whisperModelName,
                llamaModelName = settings.llamaModelName,
            )
            runCatching { context.appContainer().historyRepository.save(record) }

            return finish(PipelineState.Done(transcript, summary))
        } catch (e: InterruptedException) {
            return finish(PipelineState.Cancelled)
        } catch (e: Exception) {
            if (cancelled()) return finish(PipelineState.Cancelled)
            val message = e.message ?: e.javaClass.simpleName
            return finish(PipelineState.Failed(message))
        }
    }

    private fun finish(state: PipelineState): PipelineState {
        manager.update(state)
        return state
    }

    private fun fail(messageRes: Int): PipelineState =
        PipelineState.Failed(context.getString(messageRes))

    private fun transcribe(pcm: FloatArray): String {
        manager.update(PipelineState.LoadingWhisper)
        val resolved = ModelFileResolver.resolve(
            context, settings.whisperModelUri, settings.whisperModelName, ::cancelled,
        )
        var handle = 0L
        try {
            handle = WhisperBridge.load(resolved.path)
            if (handle == 0L) {
                throw IllegalStateException(context.getString(R.string.error_whisper_load))
            }
            if (cancelled()) return ""

            val partial = StringBuilder()
            val listener = object : WhisperBridge.Listener {
                override fun onProgress(percent: Int) {
                    manager.update(
                        PipelineState.Transcribing(percent.coerceIn(0, 100), partial.toString()),
                    )
                }

                override fun onSegment(text: String) {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        if (partial.isNotEmpty()) partial.append('\n')
                        partial.append(trimmed)
                    }
                    val current = manager.state.value
                    val percent = (current as? PipelineState.Transcribing)?.percent ?: 0
                    manager.update(PipelineState.Transcribing(percent, partial.toString()))
                }
            }

            manager.update(PipelineState.Transcribing(0, ""))
            return WhisperBridge.transcribe(
                handle = handle,
                pcm = pcm,
                language = settings.language,
                threads = effectiveThreads(),
                translate = false,
                listener = listener,
            )
        } finally {
            if (handle != 0L) WhisperBridge.free(handle)
            resolved.close()
        }
    }

    private fun summarize(transcript: String): String {
        manager.update(PipelineState.LoadingLlama)
        val resolved = ModelFileResolver.resolve(
            context, settings.llamaModelUri, settings.llamaModelName, ::cancelled,
        )
        var handle = 0L
        try {
            handle = LlamaBridge.load(
                modelPath = resolved.path,
                contextSize = settings.contextSize,
                threads = effectiveThreads(),
            )
            if (handle == 0L) {
                throw IllegalStateException(context.getString(R.string.error_llama_load))
            }
            if (cancelled()) return ""

            val summarizer = HierarchicalSummarizer(
                handle = handle,
                settings = settings,
                onState = manager::update,
                isCancelled = ::cancelled,
            )
            return summarizer.summarize(transcript)
        } finally {
            if (handle != 0L) LlamaBridge.free(handle)
            resolved.close()
        }
    }

    private fun effectiveThreads(): Int {
        if (settings.threads > 0) return settings.threads
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 2).coerceIn(2, 8)
    }
}
