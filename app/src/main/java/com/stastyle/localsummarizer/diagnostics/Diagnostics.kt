package com.stastyle.localsummarizer.diagnostics

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.stastyle.localsummarizer.BuildConfig
import com.stastyle.localsummarizer.nativebridge.NativeLib
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records how far a run got, so a crash that kills the process still leaves a
 * trace. A sideloaded app cannot read logcat — READ_LOGS is not grantable to
 * it — so this is the only account of a native abort the user can hand over.
 *
 * The breadcrumb file is written before each risky step and removed when the
 * run ends. Anything still there when a new process starts belongs to a
 * process that died, which is exactly the case that leaves no other evidence.
 */
object RunLog {
    private const val BREADCRUMB = "run-stage.txt"
    private const val OUTCOME = "run-outcome.txt"

    @Volatile
    private var startupFinding: String? = null

    /** Call once per process, before any run can start. */
    fun captureFromPreviousProcess(context: Context) {
        val breadcrumb = breadcrumbFile(context)
        startupFinding = runCatching {
            if (!breadcrumb.exists()) return@runCatching null
            val parts = breadcrumb.readLines()
            val stage = parts.getOrNull(0).orEmpty()
            val at = parts.getOrNull(1)?.toLongOrNull()
            "process died during \"$stage\"${at?.let { " at ${timestamp(it)}" }.orEmpty()}"
        }.getOrNull()
        runCatching { breadcrumb.delete() }
    }

    fun enter(context: Context, stage: String) {
        runCatching {
            breadcrumbFile(context).writeText("$stage\n${System.currentTimeMillis()}")
        }
    }

    fun finished(context: Context, outcome: String) {
        runCatching { breadcrumbFile(context).delete() }
        runCatching {
            outcomeFile(context).writeText("$outcome\n${System.currentTimeMillis()}")
        }
    }

    /** What the previous run ended with, whether it crashed or reported. */
    fun lastOutcome(context: Context): String {
        startupFinding?.let { return it }
        val recorded = runCatching {
            val parts = outcomeFile(context).readLines()
            val at = parts.getOrNull(1)?.toLongOrNull()
            parts.getOrNull(0)?.let { "$it${at?.let { t -> " at ${timestamp(t)}" }.orEmpty()}" }
        }.getOrNull()
        return recorded ?: "no run recorded yet"
    }

    private fun breadcrumbFile(context: Context) = File(context.noBackupFilesDir, BREADCRUMB)
    private fun outcomeFile(context: Context) = File(context.noBackupFilesDir, OUTCOME)

    private fun timestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
}

/**
 * A copyable snapshot of everything needed to diagnose a failure remotely.
 * Deliberately contains no transcript, summary or file content — only build,
 * device and engine state.
 */
object Diagnostics {

    fun report(context: Context): String = buildString {
        appendLine("Local Summarizer diagnostics")
        appendLine("build:    ${BuildConfig.GIT_SHA.take(12).ifBlank { "unknown" }} " +
            "(${BuildConfig.VERSION_NAME}, ${if (BuildConfig.DEBUG) "debug" else "release"})")
        appendLine("device:   ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android:  ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("abis:     ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("page size: ${pageSize()}")
        appendLine("cpu cores: ${Runtime.getRuntime().availableProcessors()}")
        appendLine()

        val libDir = File(context.applicationInfo.nativeLibraryDir)
        appendLine("native lib dir: ${libDir.absolutePath}")
        val entries = libDir.listFiles()?.map { it.name }?.sorted()
        if (entries == null) {
            appendLine("  UNREADABLE — the libraries were not extracted on install")
        } else {
            val cpuVariants = entries.filter { it.startsWith("libggml-cpu-") }
            appendLine("  ${entries.size} file(s), ${cpuVariants.size} ggml CPU variant(s)")
            cpuVariants.forEach { appendLine("    $it") }
        }
        appendLine()

        appendLine("ggml:")
        // Report the backend state even when registration failed — that is
        // precisely the case worth reporting.
        runCatching { NativeLib.ensureLoaded(context) }.exceptionOrNull()?.let {
            appendLine("  LOAD FAILED — ${it.javaClass.simpleName}: ${it.message}")
        }
        appendLine("  " + runCatching { NativeLib.backendReport() }
            .getOrElse { "no report — ${it.javaClass.simpleName}: ${it.message}" })
        appendLine()

        appendLine("last run: ${RunLog.lastOutcome(context)}")
        appendLine()

        val engineLog = runCatching { NativeLib.engineLog() }.getOrDefault("")
        if (engineLog.isBlank()) {
            appendLine("engine log: (empty — no model has been loaded yet)")
        } else {
            appendLine("engine log (most recent ${engineLog.length} chars):")
            appendLine(engineLog.trimEnd())
        }
    }

    private fun pageSize(): String = runCatching {
        "${Os.sysconf(OsConstants._SC_PAGESIZE) / 1024} KB"
    }.getOrDefault("unknown")
}
