package com.stastyle.localsummarizer.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.stastyle.localsummarizer.LocalSummarizerApp
import com.stastyle.localsummarizer.ui.screens.history.HistoryViewModel
import com.stastyle.localsummarizer.ui.screens.main.MainViewModel
import com.stastyle.localsummarizer.ui.screens.settings.SettingsViewModel

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer { MainViewModel(app()) }
        initializer { SettingsViewModel(app()) }
        initializer { HistoryViewModel(app()) }
    }
}

private fun CreationExtras.app(): LocalSummarizerApp =
    this[AndroidViewModelFactory.APPLICATION_KEY] as LocalSummarizerApp
