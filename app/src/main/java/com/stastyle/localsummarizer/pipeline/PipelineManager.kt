package com.stastyle.localsummarizer.pipeline

import android.content.Context
import android.net.Uri
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.data.settings.AppSettings
import com.stastyle.localsummarizer.domain.PipelineState
import com.stastyle.localsummarizer.nativebridge.LlamaBridge
import com.stastyle.localsummarizer.nativebridge.WhisperBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the processing pipeline state, shared between the
 * UI and the foreground service that runs the actual work.
 */
object PipelineManager {

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state

    @Volatile
    var cancelRequested: Boolean = false
        private set

    fun update(state: PipelineState) {
        _state.value = state
    }

    fun reset() {
        cancelRequested = false
        _state.value = PipelineState.Idle
    }

    fun requestCancel() {
        cancelRequested = true
        // Interrupt native inference too; it may be mid-call for minutes.
        runCatching { WhisperBridge.cancel() }
        runCatching { LlamaBridge.cancel() }
    }

    /**
     * Validates configuration and starts the processing service.
     * Returns null on success, or a user-facing error message.
     */
    fun start(context: Context, audioUri: Uri, audioName: String, settings: AppSettings): String? {
        if (state.value.isRunning) return null
        if (!settings.hasWhisperModel) return context.getString(R.string.error_no_whisper_model)
        if (!settings.hasLlamaModel) return context.getString(R.string.error_no_llama_model)
        cancelRequested = false
        // The native flags live for the process, not the run.
        runCatching { WhisperBridge.resetCancel() }
        runCatching { LlamaBridge.resetCancel() }
        _state.value = PipelineState.Decoding
        return try {
            ProcessingService.start(context, audioUri, audioName)
            null
        } catch (e: Exception) {
            _state.value = PipelineState.Failed(e.message ?: e.javaClass.simpleName)
            e.message
        }
    }
}
