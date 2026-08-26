package com.stastyle.localsummarizer.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the stack trace of an uncaught Java exception.
 *
 * The system's own ApplicationExitInfo records that a process died of
 * REASON_CRASH, but the trace it can hand back covers ANRs and native
 * crashes — a Java crash leaves nothing but the word "crash". Without this
 * the report can say the app died and not why.
 *
 * Written synchronously before the default handler runs, because that handler
 * kills the process and never returns.
 */
object CrashLog {

    private const val FILE = "java-crashes.txt"
    private const val MAX_BYTES = 32 * 1024
    private const val SEPARATOR = "\n--- ---- ---- ---- ---\n"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appContext, thread, throwable) }
            // Chain, never swallow: the system still needs to log the crash,
            // file its ApplicationExitInfo record and show the dialog.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val trace = StringWriter()
        // use{} closes and therefore flushes the writer before it is read.
        PrintWriter(trace).use { throwable.printStackTrace(it) }
        val entry = buildString {
            append(timestamp(System.currentTimeMillis()))
            append("  thread=").append(thread.name)
            appendLine()
            append(trace.toString().trimEnd())
        }
        val file = file(context)
        // Newest first, so a truncated report still shows the latest crash.
        val previous = runCatching { file.readText() }.getOrDefault("")
        val combined = (entry + SEPARATOR + previous).let {
            if (it.length > MAX_BYTES) it.take(MAX_BYTES) else it
        }
        file.writeText(combined)
    }

    /** The most recent crashes, newest first; empty when there are none. */
    fun recent(context: Context): String =
        runCatching { file(context).readText().trimEnd() }.getOrDefault("")

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE)

    private fun timestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
}
