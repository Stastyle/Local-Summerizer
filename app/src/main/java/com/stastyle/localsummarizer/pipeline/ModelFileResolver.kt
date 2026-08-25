package com.stastyle.localsummarizer.pipeline

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File

/**
 * Turns a SAF content:// URI into a filesystem path the native engines can
 * open. Preferred route is a /proc/self/fd path over an open descriptor (no
 * copying, mmap works); providers that only expose pipes fall back to a
 * one-time copy into app storage.
 */
class ResolvedModel(
    val path: String,
    private val pfd: ParcelFileDescriptor?,
) : Closeable {
    override fun close() {
        pfd?.close()
    }
}

object ModelFileResolver {

    fun resolve(
        context: Context,
        uriString: String,
        displayName: String,
        isCancelled: () -> Boolean = { false },
    ): ResolvedModel {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IllegalArgumentException("Invalid file URI")
            return ResolvedModel(path, null)
        }

        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            if (isSeekable(pfd)) {
                return ResolvedModel("/proc/self/fd/${pfd.fd}", pfd)
            }
            pfd.close()
        }

        // Fall back to copying the model into app storage (kept for reuse).
        val copied = copyToLocal(context, uri, displayName, isCancelled)
        return ResolvedModel(copied.absolutePath, null)
    }

    private fun isSeekable(pfd: ParcelFileDescriptor): Boolean {
        return try {
            Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copyToLocal(
        context: Context,
        uri: Uri,
        displayName: String,
        isCancelled: () -> Boolean,
    ): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._\\-֐-׿]"), "_")
            .ifBlank { "model.bin" }
        val target = File(dir, "${uri.toString().hashCode().toUInt()}-$safeName")

        val expectedSize = context.contentResolver
            .openAssetFileDescriptor(uri, "r")?.use { it.declaredLength } ?: -1L
        if (target.exists() && expectedSize > 0 && target.length() == expectedSize) {
            return target
        }

        val tmp = File(dir, "${target.name}.part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    if (isCancelled()) {
                        tmp.delete()
                        throw InterruptedException("Cancelled")
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalStateException("Cannot open model file")
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("Cannot store model copy")
        }
        return target
    }
}
