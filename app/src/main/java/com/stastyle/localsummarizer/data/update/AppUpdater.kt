package com.stastyle.localsummarizer.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.stastyle.localsummarizer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** The sidecar CI publishes next to the APK. */
@Serializable
data class RemoteVersion(
    val commit: String = "",
    val branch: String = "",
    val builtAt: String = "",
    val apk: String = "app-release.apk",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val remote: RemoteVersion) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class ReadyToInstall(val file: File) : UpdateState
    data class Failed(val message: String, val needsAuth: Boolean = false) : UpdateState
}

/**
 * Checks the rolling GitHub release for a build newer than this one and
 * installs it.
 *
 * The repository is private, so both the version sidecar and the APK need a
 * token. Without one the caller is expected to fall back to opening the
 * release page in a browser, where the user's own GitHub session applies.
 */
class AppUpdater(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val versionUrl = "${BuildConfig.UPDATE_BASE_URL}/version.json"

    suspend fun fetchRemoteVersion(token: String): Result<RemoteVersion> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = get(versionUrl, token).use { it.stream.readBytes().decodeToString() }
                json.decodeFromString<RemoteVersion>(body)
            }
        }

    fun isNewer(remote: RemoteVersion): Boolean {
        val installed = BuildConfig.GIT_SHA
        // An unknown local commit (built outside git) can't be compared, so
        // treat anything remote as newer rather than claiming to be current.
        if (installed.isBlank()) return remote.commit.isNotBlank()
        return remote.commit.isNotBlank() && !remote.commit.equals(installed, ignoreCase = true)
    }

    suspend fun downloadApk(
        remote: RemoteVersion,
        token: String,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(context.cacheDir, "update-${remote.commit.take(12)}.apk")
            val partial = File(target.parentFile, "${target.name}.part")
            partial.delete()

            get("${BuildConfig.UPDATE_BASE_URL}/${remote.apk}", token).use { response ->
                val total = response.contentLength
                partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = response.stream.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (partial.length() < MIN_APK_BYTES) {
                partial.delete()
                throw IOException("The downloaded file is too small to be an APK")
            }
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IOException("Could not store the downloaded update")
            }
            // Only the newest update is worth keeping around.
            context.cacheDir.listFiles { f ->
                f.name.startsWith("update-") && f.name.endsWith(".apk") && f != target
            }?.forEach { it.delete() }
            target
        }
    }

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun releasePageIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.RELEASE_PAGE_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private class Response(
        val stream: java.io.InputStream,
        val contentLength: Long,
    ) : java.io.Closeable {
        override fun close() = stream.close()
    }

    private fun get(url: String, token: String): Response {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/octet-stream")
                // GitHub redirects asset downloads to a pre-signed CDN URL that
                // rejects a second credential, so the token goes to github.com
                // only and is dropped on the hop off-site.
                if (token.isNotBlank() && current.host.endsWith("github.com")) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = connection.responseCode
            when {
                code in 200..299 -> return Response(
                    connection.inputStream,
                    connection.contentLengthLong,
                )
                code in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null) throw IOException("Redirect without a target")
                    current = URL(current, location)
                }
                code == 401 || code == 403 || code == 404 -> {
                    connection.disconnect()
                    throw NeedsAuthException(
                        "GitHub answered $code. The repository is private, so an access " +
                            "token is required — or open the release page in a browser.",
                    )
                }
                else -> {
                    connection.disconnect()
                    throw IOException("GitHub answered $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    class NeedsAuthException(message: String) : IOException(message)

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MIN_APK_BYTES = 1_000_000L
    }
}
