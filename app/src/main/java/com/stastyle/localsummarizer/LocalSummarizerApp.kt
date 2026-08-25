package com.stastyle.localsummarizer

import android.app.Application
import android.content.Context
import com.stastyle.localsummarizer.data.history.HistoryRepository
import com.stastyle.localsummarizer.data.settings.SettingsRepository

class LocalSummarizerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context.applicationContext)
    val historyRepository = HistoryRepository(context.applicationContext)
}

fun Context.appContainer(): AppContainer =
    (applicationContext as LocalSummarizerApp).container
