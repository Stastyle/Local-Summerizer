package com.stastyle.localsummarizer.pipeline

import android.content.Context
import android.net.Uri
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.appContainer
import com.stastyle.localsummarizer.audio.AudioDecoder
import com.stastyle.localsummarizer.data.history.MeetingRecord
import com.stastyle.localsummarizer.data.settings.AppSettings
import com.stastyle.localsummarizer.diagnostics.RunLog
import com.stastyle.localsummarizer.domain.PipelineState
import com.stastyle.localsummarizer.nativebridge.LlamaBridge
import com.stastyle.localsummarizer.nativebridge.WhisperBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

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
        val pcmFile = File(context.cacheDir, "decoded-${startedAt}.pcm")
        try {
            // 1. decode audio to a 16kHz mono PCM scratch file (a long meeting
            //    is hundreds of MB — far past the Java heap limit)
            manager.update(PipelineState.Decoding)
            RunLog.enter(context, "decoding audio")
            AudioDecoder.decodeToFile(
                context = context,
                uri = audioUri,
                target = pcmFile,
                onProgress = { /* stage is indeterminate in the UI */ },
                isCancelled = ::cancelled,
            )
            if (cancelled()) return finish(PipelineState.Cancelled)

            // 2. whisper: load -> transcribe -> free
            transcript = transcribe(pcmFile)
            if (cancelled()) return finish(PipelineState.Cancelled)
            if (transcript.isBlank()) {
                return finish(fail(R.string.error_empty_transcript))
            }

            // 3. llama: load -> summarize -> free
            val summary = summarize(transcript)
            if (cancelled()) {
                // A cancelled summary still leaves a usable transcript.
                saveRecord(startedAt, transcript, summary)
                return finish(PipelineState.Cancelled)
            }

            val record = MeetingRecord(
                createdAtEpochMs = startedAt,
                audioFileName = audioName,
                transcript = transcript,
                summary = summary,
                processingTimeMs = System.currentTimeMillis() - startedAt,
                whisperModelName = settings.whisperModelName,
                llamaModelName = settings.llamaModelName,
            )
            // The service may be stopping (user cancel, Android 15 FGS cap);
            // a finished result must still reach History.
            withContext(NonCancellable) {
                runCatching { context.appContainer().historyRepository.save(record) }
            }

            return finish(PipelineState.Done(transcript, summary))
        } catch (e: InterruptedException) {
            saveRecord(startedAt, transcript, "")
            return finish(PipelineState.Cancelled)
        } catch (e: CancellationException) {
            // A cancelled scope is a stop, never a failure.
            saveRecord(startedAt, transcript, "")
            return finish(PipelineState.Cancelled)
        } catch (e: Throwable) {
            // Throwable, not Exception: a native library that fails to load
            // raises UnsatisfiedLinkError, and a model too large for the
            // device raises OutOfMemoryError. Both are Errors, and letting one
            // escape a coroutine kills the process instead of showing why.
            if (cancelled()) {
                saveRecord(startedAt, transcript, "")
                return finish(PipelineState.Cancelled)
            }
            val message = e.message ?: e.javaClass.simpleName
            return finish(PipelineState.Failed(message))
        } finally {
            pcmFile.delete()
        }
    }

    /** Persists whatever the run produced; no-ops when there is no transcript. */
    private suspend fun saveRecord(startedAt: Long, transcript: String, summary: String) {
        if (transcript.isBlank()) return
        withContext(NonCancellable) {
            runCatching {
                context.appContainer().historyRepository.save(
                    MeetingRecord(
                        createdAtEpochMs = startedAt,
                        audioFileName = audioName,
                        transcript = transcript,
                        summary = summary,
                        processingTimeMs = System.currentTimeMillis() - startedAt,
                        whisperModelName = settings.whisperModelName,
                        llamaModelName = settings.llamaModelName,
                    ),
                )
            }
        }
    }

    private fun finish(state: PipelineState): PipelineState {
        RunLog.finished(
            context,
            when (state) {
                is PipelineState.Done -> "completed"
                is PipelineState.Failed -> "failed: ${state.message}"
                PipelineState.Cancelled -> "cancelled"
                else -> state.javaClass.simpleName
            },
        )
        manager.update(state)
        return state
    }

    private fun fail(messageRes: Int): PipelineState =
        PipelineState.Failed(context.getString(messageRes))

    private fun transcribe(pcmFile: File): String {
        manager.update(PipelineState.LoadingWhisper)
        RunLog.enter(context, "loading whisper model ${settings.whisperModelName}")
        val resolved = ModelFileResolver.resolve(
            context, settings.whisperModelUri, settings.whisperModelName, ::cancelled,
        )
        var handle = 0L
        try {
            handle = WhisperBridge.load(context, resolved.path)
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
            RunLog.enter(context, "transcribing")
            return WhisperBridge.transcribeFile(
                handle = handle,
                pcmPath = pcmFile.absolutePath,
                language = settings.language,
                threads = effectiveThreads(),
                translate = false,
                beamSize = settings.beamSize,
                useContext = settings.transcriptionContext,
                initialPrompt = settings.transcriptionPrompt,
                listener = listener,
            )
        } finally {
            if (handle != 0L) WhisperBridge.free(handle)
            resolved.close()
        }
    }

    private fun summarize(transcript: String): String {
        manager.update(PipelineState.LoadingLlama)
        RunLog.enter(context, "loading summarization model ${settings.llamaModelName}")
        val resolved = ModelFileResolver.resolve(
            context, settings.llamaModelUri, settings.llamaModelName, ::cancelled,
        )
        var handle = 0L
        try {
            handle = LlamaBridge.load(
                context = context,
                modelPath = resolved.path,
                contextSize = settings.contextSize,
                threads = effectiveThreads(),
            )
            if (handle == 0L) {
                throw IllegalStateException(context.getString(R.string.error_llama_load))
            }
            if (cancelled()) return ""

            RunLog.enter(context, "summarizing")
            val summarizer = HierarchicalSummarizer(
                engine = LlamaEngine(handle),
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
