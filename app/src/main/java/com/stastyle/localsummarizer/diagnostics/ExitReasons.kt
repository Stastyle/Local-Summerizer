package com.stastyle.localsummarizer.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why previous processes of this app died, straight from the system.
 *
 * This is the one route to a native crash trace that needs no adb, no
 * permission and no computer: from API 30 the system keeps the last several
 * exit records per app, and from API 31 a native crash carries the tombstone
 * itself — abort message and backtrace included.
 *
 * The records live in a global circular buffer shared with every other app, so
 * a trace can be gone by the time anyone looks. Missing evidence is reported
 * as missing rather than as "no crash".
 */
object ExitReasons {

    private const val MAX_RECORDS = 6
    private const val MAX_TRACE_CHARS = 6000
    private const val MIN_PRINTABLE_RUN = 6

    fun summary(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { records(context) }
                .getOrElse { "unavailable — ${it.javaClass.simpleName}: ${it.message}" }
        } else {
            "unavailable below Android 11"
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun records(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
            ?: return "ActivityManager unavailable"
        val records = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
        if (records.isEmpty()) return "no records (this app has not died yet)"

        return buildString {
            records.forEachIndexed { index, info ->
                if (index > 0) appendLine()
                append("  ${timestamp(info.timestamp)}  ${reasonName(info.reason)}")
                append("  status=${info.status}")
                info.description?.takeIf { it.isNotBlank() }?.let { append("  \"$it\"") }
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    appendLine()
                    val trace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        nativeTrace(info)
                    } else {
                        "(the tombstone itself needs Android 12+)"
                    }
                    append(trace.prependIndent("    "))
                }
            }
        }
    }

    /**
     * The tombstone arrives as a protobuf. Rather than depend on the schema —
     * which changes between releases — pull the printable strings out of it:
     * that is where the abort message, the signal and the backtrace symbols
     * live, and it degrades gracefully if the format shifts.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun nativeTrace(info: ApplicationExitInfo): String {
        val bytes = runCatching { info.traceInputStream?.use { it.readBytes() } }
            .getOrElse { return "(tombstone unreadable — ${it.javaClass.simpleName})" }
            ?: return "(tombstone already evicted from the system's shared buffer)"

        val text = printableRuns(bytes)
        if (text.isBlank()) return "(tombstone held no readable text, ${bytes.size} bytes)"
        return if (text.length > MAX_TRACE_CHARS) {
            text.take(MAX_TRACE_CHARS) + "\n… truncated (${text.length} chars total)"
        } else {
            text
        }
    }

    private fun printableRuns(bytes: ByteArray): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= MIN_PRINTABLE_RUN) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(run)
            }
            run.setLength(0)
        }
        for (byte in bytes) {
            val c = byte.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
        }
        flush()
        return out.toString()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH (native)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        else -> "reason $reason"
    }

    private fun timestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
}
