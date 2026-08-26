package com.stastyle.localsummarizer

import android.app.Application
import android.content.Context
import com.stastyle.localsummarizer.data.history.HistoryRepository
import com.stastyle.localsummarizer.data.models.ModelDownloader
import com.stastyle.localsummarizer.data.models.ModelStore
import com.stastyle.localsummarizer.data.settings.SettingsRepository
import com.stastyle.localsummarizer.data.update.AppUpdater
import com.stastyle.localsummarizer.diagnostics.RunLog

class LocalSummarizerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Before anything can start a run: a breadcrumb left over from the
        // last process means that process died mid-run, and a native abort
        // leaves no other evidence a sideloaded app can read.
        RunLog.captureFromPreviousProcess(this)
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context.applicationContext)
    val historyRepository = HistoryRepository(context.applicationContext)
    val modelStore = ModelStore(context.applicationContext)
    val modelDownloader = ModelDownloader(context.applicationContext)
    val appUpdater = AppUpdater(context.applicationContext)
}

fun Context.appContainer(): AppContainer =
    (applicationContext as LocalSummarizerApp).container
