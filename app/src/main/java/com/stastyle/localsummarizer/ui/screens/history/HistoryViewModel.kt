package com.stastyle.localsummarizer.ui.screens.history

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.localsummarizer.LocalSummarizerApp
import com.stastyle.localsummarizer.data.history.MeetingRecord
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(app: LocalSummarizerApp) : AndroidViewModel(app) {

    private val repository = app.container.historyRepository

    val records: StateFlow<List<MeetingRecord>> = repository.records

    init {
        viewModelScope.launch { repository.reload() }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
