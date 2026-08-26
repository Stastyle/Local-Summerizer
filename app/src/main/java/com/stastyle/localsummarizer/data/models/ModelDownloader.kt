package com.stastyle.localsummarizer.data.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Live state of one catalog entry, as the Settings screen sees it. */
sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data object Checking : DownloadStatus
    data class Running(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val waitingForWifi: Boolean,
    ) : DownloadStatus {
        val percent: Int
            get() = if (totalBytes > 0) {
                ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
    }
    data class Failed(val message: String) : DownloadStatus
}

/** What a HEAD request told us about the remote file. */
data class RemoteInfo(val sizeBytes: Long, val tag: String)

/**
 * Fetches catalog models through the system DownloadManager, which survives
 * the app being backgrounded and resumes across network drops — both of which
 * matter for a multi-gigabyte file.
 */
class ModelDownloader(private val context: Context) {

    private val manager: DownloadManager?
        get() = context.getSystemService(DownloadManager::class.java)

    fun targetFile(model: CatalogModel): File =
        File(File(context.getExternalFilesDir(null), MODELS_DIR), model.fileName)

    /**
     * Asks the server about the file before committing to a download, so a
     * catalog entry that has gone stale fails with something the user can act
     * on instead of a broken download.
     */
    suspend fun probe(model: CatalogModel): Result<RemoteInfo> = withContext(Dispatchers.IO) {
        runCatching {
            var url = URL(model.url)
            repeat(MAX_REDIRECTS) {
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    // HuggingFace always redirects to a CDN, and following it by
                    // hand is the only way to read the final headers reliably.
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 20_000
                }
                try {
                    val code = connection.responseCode
                    when {
                        code in 200..299 -> {
                            val size = connection.getHeaderField("Content-Length")
                                ?.toLongOrNull() ?: -1L
                            val tag = (
                                connection.getHeaderField("x-linked-etag")
                                    ?: connection.getHeaderField("ETag")
                                    ?: connection.getHeaderField("Last-Modified")
                                    ?: ""
                                ).trim('"')
                            return@runCatching RemoteInfo(size, tag)
                        }
                        code in 300..399 -> {
                            val location = connection.getHeaderField("Location")
                                ?: throw IOException("Redirect without a target")
                            url = URL(url, location)
                        }
                        code == 404 -> throw IOException(
                            "File not found (404) at ${model.url} — " +
                                "the catalog entry is probably out of date.",
                        )
                        else -> throw IOException("Server answered $code for ${model.url}")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Too many redirects for ${model.url}")
        }
    }

    /** Enqueues the download and returns the DownloadManager id. */
    fun enqueue(model: CatalogModel, allowMetered: Boolean): Long {
        val downloadManager = manager
            ?: throw IllegalStateException(
                "The system download manager is unavailable — it may be disabled in Settings.",
            )
        val target = targetFile(model)
        target.parentFile?.mkdirs()
        // A stale partial file would otherwise make DownloadManager fail.
        target.delete()

        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle(model.displayName)
            .setDescription(model.fileName)
            .setDestinationInExternalFilesDir(context, MODELS_DIR, model.fileName)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setAllowedOverMetered(allowMetered)
            .setAllowedOverRoaming(false)
        return downloadManager.enqueue(request)
    }

    fun cancel(downloadId: Long) {
        runCatching { manager?.remove(downloadId) }
    }

    /** Current DownloadManager state, or null once the entry is gone. */
    fun query(downloadId: Long): DownloadSnapshot? {
        val cursor = manager?.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            fun col(name: String) = it.getColumnIndex(name).takeIf { i -> i >= 0 }
            val status = col(DownloadManager.COLUMN_STATUS)?.let(it::getInt) ?: return null
            val reason = col(DownloadManager.COLUMN_REASON)?.let(it::getInt) ?: 0
            val soFar = col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                ?.let(it::getLong) ?: 0L
            val total = col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)?.let(it::getLong) ?: -1L
            val localUri = col(DownloadManager.COLUMN_LOCAL_URI)?.let(it::getString)
            return DownloadSnapshot(status, reason, soFar, total, localUri)
        }
    }

    data class DownloadSnapshot(
        val status: Int,
        val reason: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val localUri: String?,
    ) {
        val isRunning: Boolean
            get() = status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PENDING ||
                status == DownloadManager.STATUS_PAUSED

        val waitingForWifi: Boolean
            get() = status == DownloadManager.STATUS_PAUSED &&
                reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI

        val isSuccess: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
        val isFailed: Boolean get() = status == DownloadManager.STATUS_FAILED
    }

    fun hasRoomFor(bytes: Long): Boolean {
        val dir = File(context.getExternalFilesDir(null), MODELS_DIR)
        dir.mkdirs()
        val usable = dir.usableSpace
        return usable <= 0 || usable > bytes + HEADROOM_BYTES
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val MAX_REDIRECTS = 5
        const val HEADROOM_BYTES = 256L * 1024 * 1024
    }
}
