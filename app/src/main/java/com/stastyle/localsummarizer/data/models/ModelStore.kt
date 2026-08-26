package com.stastyle.localsummarizer.data.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the app knows about a model it fetched: where it landed, and enough of
 * the server's answer to tell later whether the remote file has changed.
 */
@Serializable
data class DownloadedModel(
    val modelId: String,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    /** ETag when the server sends one, otherwise Last-Modified, otherwise "". */
    val remoteTag: String = "",
    val downloadedAtEpochMs: Long = 0,
    /** Set once an update check finds the remote file differs. */
    val updateAvailable: Boolean = false,
)

@Serializable
private data class StoreContents(
    val downloaded: Map<String, DownloadedModel> = emptyMap(),
    /** modelId -> DownloadManager id, for downloads still in flight. */
    val pending: Map<String, Long> = emptyMap(),
)

class ModelStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(context.filesDir, "downloaded-models.json")

    private val _downloaded = MutableStateFlow<Map<String, DownloadedModel>>(emptyMap())
    val downloaded: StateFlow<Map<String, DownloadedModel>> = _downloaded

    private var pending: Map<String, Long> = emptyMap()

    suspend fun load() = withContext(Dispatchers.IO) {
        val contents = runCatching {
            if (file.exists()) json.decodeFromString<StoreContents>(file.readText()) else null
        }.getOrNull() ?: StoreContents()
        // Drop entries whose file the user deleted from outside the app.
        val present = contents.downloaded.filterValues { File(it.path).exists() }
        pending = contents.pending
        _downloaded.value = present
        if (present.size != contents.downloaded.size) persist()
    }

    fun pendingDownloads(): Map<String, Long> = pending

    suspend fun markPending(modelId: String, downloadId: Long) = withContext(Dispatchers.IO) {
        pending = pending + (modelId to downloadId)
        persist()
    }

    suspend fun clearPending(modelId: String) = withContext(Dispatchers.IO) {
        pending = pending - modelId
        persist()
    }

    suspend fun record(model: DownloadedModel) = withContext(Dispatchers.IO) {
        _downloaded.value = _downloaded.value + (model.modelId to model)
        pending = pending - model.modelId
        persist()
    }

    suspend fun setUpdateAvailable(modelId: String, available: Boolean) =
        withContext(Dispatchers.IO) {
            val current = _downloaded.value[modelId] ?: return@withContext
            if (current.updateAvailable == available) return@withContext
            _downloaded.value =
                _downloaded.value + (modelId to current.copy(updateAvailable = available))
            persist()
        }

    suspend fun forget(modelId: String) = withContext(Dispatchers.IO) {
        _downloaded.value[modelId]?.let { File(it.path).delete() }
        _downloaded.value = _downloaded.value - modelId
        pending = pending - modelId
        persist()
    }

    private fun persist() {
        runCatching {
            file.writeText(
                json.encodeToString(
                    StoreContents.serializer(),
                    StoreContents(_downloaded.value, pending),
                ),
            )
        }
    }
}
