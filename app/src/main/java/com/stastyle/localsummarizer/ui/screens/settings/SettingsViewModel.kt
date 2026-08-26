package com.stastyle.localsummarizer.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.localsummarizer.LocalSummarizerApp
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.data.models.CatalogModel
import com.stastyle.localsummarizer.data.models.DownloadStatus
import com.stastyle.localsummarizer.data.models.DownloadedModel
import com.stastyle.localsummarizer.data.models.ModelCatalog
import com.stastyle.localsummarizer.data.models.ModelKind
import com.stastyle.localsummarizer.data.settings.AppSettings
import com.stastyle.localsummarizer.data.update.AppUpdater
import com.stastyle.localsummarizer.data.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(private val app: LocalSummarizerApp) : AndroidViewModel(app) {

    private val repository = app.container.settingsRepository
    private val store = app.container.modelStore
    private val downloader = app.container.modelDownloader
    private val updater = app.container.appUpdater

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Downloaded models, keyed by catalog id. */
    val downloadedModels: StateFlow<Map<String, DownloadedModel>> = store.downloaded

    private val _downloadStatus = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadStatus: StateFlow<Map<String, DownloadStatus>> = _downloadStatus

    private val _checkingUpdates = MutableStateFlow(false)
    val checkingUpdates: StateFlow<Boolean> = _checkingUpdates

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val pollers = mutableMapOf<String, Job>()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    init {
        viewModelScope.launch {
            store.load()
            // A download started earlier may have finished while the app was
            // closed; DownloadManager keeps going, so reconcile on open.
            store.pendingDownloads().forEach { (modelId, downloadId) ->
                ModelCatalog.byId(modelId)?.let { watchDownload(it, downloadId) }
            }
        }
    }

    fun startDownload(model: CatalogModel, allowMetered: Boolean = false) {
        if (_downloadStatus.value[model.id] is DownloadStatus.Running) return
        viewModelScope.launch {
            setStatus(model.id, DownloadStatus.Checking)
            if (!downloader.hasRoomFor(model.approxBytes)) {
                setStatus(model.id, DownloadStatus.Failed(app.getString(
                    R.string.download_no_space,
                )))
                return@launch
            }
            val probe = downloader.probe(model)
            val remote = probe.getOrElse { error ->
                setStatus(
                    model.id,
                    DownloadStatus.Failed(error.message ?: error.javaClass.simpleName),
                )
                return@launch
            }
            val downloadId = runCatching { downloader.enqueue(model, allowMetered) }
                .getOrElse { error ->
                    setStatus(
                        model.id,
                        DownloadStatus.Failed(error.message ?: error.javaClass.simpleName),
                    )
                    return@launch
                }
            store.markPending(model.id, downloadId)
            watchDownload(model, downloadId, remote.tag)
        }
    }

    fun cancelDownload(model: CatalogModel) {
        viewModelScope.launch {
            store.pendingDownloads()[model.id]?.let(downloader::cancel)
            store.clearPending(model.id)
            pollers.remove(model.id)?.cancel()
            setStatus(model.id, DownloadStatus.Idle)
        }
    }

    fun deleteDownload(model: CatalogModel) {
        viewModelScope.launch {
            val current = store.downloaded.value[model.id]
            store.forget(model.id)
            setStatus(model.id, DownloadStatus.Idle)
            // Clear the active selection if it pointed at the deleted file.
            val settingsNow = repository.current()
            if (current != null) {
                val uri = Uri.fromFile(File(current.path)).toString()
                if (settingsNow.whisperModelUri == uri) repository.setWhisperModel("", "")
                if (settingsNow.llamaModelUri == uri) repository.setLlamaModel("", "")
            }
        }
    }

    /** Re-selects an already downloaded model as the active one for its kind. */
    fun useDownloaded(model: CatalogModel) {
        val entry = store.downloaded.value[model.id] ?: return
        viewModelScope.launch { activate(model, entry.path) }
    }

    fun checkForUpdates() {
        if (_checkingUpdates.value) return
        viewModelScope.launch {
            _checkingUpdates.value = true
            try {
                val entries = store.downloaded.value.values.toList()
                if (entries.isEmpty()) {
                    _messages.emit(app.getString(
                        R.string.updates_nothing_downloaded,
                    ))
                    return@launch
                }
                var updates = 0
                var failures = 0
                for (entry in entries) {
                    val model = ModelCatalog.byId(entry.modelId) ?: continue
                    val remote = downloader.probe(model).getOrElse {
                        failures++
                        continue
                    }
                    val changed = when {
                        entry.remoteTag.isNotEmpty() && remote.tag.isNotEmpty() ->
                            entry.remoteTag != remote.tag
                        remote.sizeBytes > 0 -> entry.sizeBytes != remote.sizeBytes
                        else -> false
                    }
                    store.setUpdateAvailable(entry.modelId, changed)
                    if (changed) updates++
                }
                _messages.emit(
                    when {
                        updates > 0 -> app.getString(
                            R.string.updates_found, updates,
                        )
                        failures > 0 -> app.getString(
                            R.string.updates_check_failed,
                        )
                        else -> app.getString(
                            R.string.updates_up_to_date,
                        )
                    },
                )
            } finally {
                _checkingUpdates.value = false
            }
        }
    }

    private fun watchDownload(model: CatalogModel, downloadId: Long, remoteTag: String = "") {
        pollers.remove(model.id)?.cancel()
        pollers[model.id] = viewModelScope.launch {
            while (isActive) {
                val snapshot = downloader.query(downloadId)
                if (snapshot == null) {
                    // The entry vanished (cancelled, or cleared by the system).
                    store.clearPending(model.id)
                    setStatus(model.id, DownloadStatus.Idle)
                    return@launch
                }
                when {
                    snapshot.isSuccess -> {
                        finishDownload(model, snapshot.totalBytes, remoteTag)
                        return@launch
                    }
                    snapshot.isFailed -> {
                        store.clearPending(model.id)
                        setStatus(
                            model.id,
                            DownloadStatus.Failed(
                                app.getString(
                                    R.string.download_failed_reason,
                                    snapshot.reason,
                                ),
                            ),
                        )
                        return@launch
                    }
                    else -> setStatus(
                        model.id,
                        DownloadStatus.Running(
                            bytesDownloaded = snapshot.bytesDownloaded,
                            totalBytes = if (snapshot.totalBytes > 0) {
                                snapshot.totalBytes
                            } else {
                                model.approxBytes
                            },
                            waitingForWifi = snapshot.waitingForWifi,
                        ),
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun finishDownload(model: CatalogModel, totalBytes: Long, remoteTag: String) {
        val file = downloader.targetFile(model)
        if (!file.exists()) {
            store.clearPending(model.id)
            setStatus(model.id, DownloadStatus.Failed(app.getString(
                R.string.download_file_missing,
            )))
            return
        }
        store.record(
            DownloadedModel(
                modelId = model.id,
                fileName = model.fileName,
                path = file.absolutePath,
                sizeBytes = if (totalBytes > 0) totalBytes else file.length(),
                remoteTag = remoteTag,
                downloadedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        setStatus(model.id, DownloadStatus.Idle)
        activate(model, file.absolutePath)
        _messages.emit(app.getString(
            R.string.download_done, model.displayName,
        ))
    }

    private suspend fun activate(model: CatalogModel, path: String) {
        val uri = Uri.fromFile(File(path)).toString()
        if (model.kind == ModelKind.WHISPER) {
            repository.setWhisperModel(uri, model.fileName)
        } else {
            repository.setLlamaModel(uri, model.fileName)
        }
    }

    private fun setStatus(modelId: String, status: DownloadStatus) {
        _downloadStatus.value = _downloadStatus.value + (modelId to status)
    }

    override fun onCleared() {
        pollers.values.forEach { it.cancel() }
        pollers.clear()
        super.onCleared()
    }

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

    fun setGithubToken(token: String) {
        viewModelScope.launch { repository.setGithubToken(token) }
    }

    /** Asks the rolling release whether a newer build than this one exists. */
    fun checkForAppUpdate() {
        if (_updateState.value is UpdateState.Checking ||
            _updateState.value is UpdateState.Downloading
        ) {
            return
        }
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            val token = repository.current().githubToken
            val remote = updater.fetchRemoteVersion(token).getOrElse { error ->
                _updateState.value = UpdateState.Failed(
                    error.message ?: error.javaClass.simpleName,
                    needsAuth = error is AppUpdater.NeedsAuthException,
                )
                return@launch
            }
            _updateState.value = if (updater.isNewer(remote)) {
                UpdateState.Available(remote)
            } else {
                UpdateState.UpToDate
            }
        }
    }

    fun downloadUpdate() {
        val available = _updateState.value as? UpdateState.Available ?: return
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0)
            val token = repository.current().githubToken
            val file = updater.downloadApk(available.remote, token) { percent ->
                _updateState.value = UpdateState.Downloading(percent)
            }.getOrElse { error ->
                _updateState.value = UpdateState.Failed(
                    error.message ?: error.javaClass.simpleName,
                    needsAuth = error is AppUpdater.NeedsAuthException,
                )
                return@launch
            }
            _updateState.value = UpdateState.ReadyToInstall(file)
        }
    }

    fun installIntent() = (_updateState.value as? UpdateState.ReadyToInstall)
        ?.let { updater.installIntent(it.file) }

    fun releasePageIntent() = updater.releasePageIntent()

    fun dismissUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    private companion object {
        const val POLL_INTERVAL_MS = 700L
    }
}
