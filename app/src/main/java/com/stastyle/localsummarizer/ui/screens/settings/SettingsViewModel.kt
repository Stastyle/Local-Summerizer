package com.stastyle.localsummarizer.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.localsummarizer.LocalSummarizerApp
import com.stastyle.localsummarizer.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val app: LocalSummarizerApp) : AndroidViewModel(app) {

    private val repository = app.container.settingsRepository

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun onWhisperModelPicked(uri: Uri) = persistModel(uri) { path, name ->
        repository.setWhisperModel(path, name)
    }

    fun onLlamaModelPicked(uri: Uri) = persistModel(uri) { path, name ->
        repository.setLlamaModel(path, name)
    }

    fun clearWhisperModel() {
        viewModelScope.launch { repository.setWhisperModel("", "") }
    }

    fun clearLlamaModel() {
        viewModelScope.launch { repository.setLlamaModel("", "") }
    }

    fun setMasterPrompt(prompt: String) {
        viewModelScope.launch { repository.setMasterPrompt(prompt) }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch { repository.setLanguage(language) }
    }

    fun setThreads(threads: Int) {
        viewModelScope.launch { repository.setThreads(threads) }
    }

    fun setContextSize(size: Int) {
        viewModelScope.launch { repository.setContextSize(size) }
    }

    fun setMaxTokens(tokens: Int) {
        viewModelScope.launch { repository.setMaxTokens(tokens) }
    }

    fun setTemperature(temperature: Float) {
        viewModelScope.launch { repository.setTemperature(temperature) }
    }

    private fun persistModel(uri: Uri, save: suspend (String, String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = withContext(Dispatchers.IO) { resolveDisplayName(uri) }
            save(uri.toString(), name)
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
        return uri.lastPathSegment ?: "model"
    }
}
