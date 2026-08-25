package com.stastyle.localsummarizer.ui.screens.main

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.localsummarizer.LocalSummarizerApp
import com.stastyle.localsummarizer.data.history.MeetingRecord
import com.stastyle.localsummarizer.data.settings.AppSettings
import com.stastyle.localsummarizer.export.Exporter
import com.stastyle.localsummarizer.domain.PipelineState
import com.stastyle.localsummarizer.pipeline.PipelineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectedAudio(val uri: Uri, val displayName: String)

class MainViewModel(private val app: LocalSummarizerApp) : AndroidViewModel(app) {

    private val settingsRepository = app.container.settingsRepository

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val pipelineState: StateFlow<PipelineState> = PipelineManager.state

    private val _selectedAudio = MutableStateFlow<SelectedAudio?>(null)
    val selectedAudio: StateFlow<SelectedAudio?> = _selectedAudio

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun onAudioPicked(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = withContext(Dispatchers.IO) { resolveDisplayName(uri) }
            _selectedAudio.value = SelectedAudio(uri, name)
            if (!pipelineState.value.isRunning) PipelineManager.reset()
        }
    }

    fun run() {
        val audio = _selectedAudio.value ?: return
        viewModelScope.launch {
            val error = PipelineManager.start(
                context = app,
                audioUri = audio.uri,
                audioName = audio.displayName,
                settings = settingsRepository.settings.first(),
            )
            if (error != null) _messages.emit(error)
        }
    }

    fun cancel() {
        PipelineManager.requestCancel()
    }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    /** Builds the export payload for the current results, or null if none. */
    fun currentRecord(): MeetingRecord? {
        val state = pipelineState.value
        val (transcript, summary) = when (state) {
            is PipelineState.Done -> state.transcript to state.summary
            else -> return null
        }
        return MeetingRecord(
            createdAtEpochMs = System.currentTimeMillis(),
            audioFileName = _selectedAudio.value?.displayName ?: "meeting",
            transcript = transcript,
            summary = summary,
        )
    }

    fun exportTo(uri: Uri, asMarkdown: Boolean, successMessage: String) {
        val record = currentRecord() ?: return
        viewModelScope.launch {
            val content = if (asMarkdown) {
                Exporter.buildMarkdown(record)
            } else {
                Exporter.buildPlainText(record)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { Exporter.writeTo(app, uri, content) }
            }
            _messages.emit(
                result.fold(
                    onSuccess = { successMessage },
                    onFailure = { it.message ?: it.javaClass.simpleName },
                ),
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) return cursor.getString(index) ?: ""
                    }
                }
        }
        return uri.lastPathSegment ?: "audio"
    }
}
