package com.stastyle.localsummarizer.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import com.stastyle.localsummarizer.BuildConfig
import com.stastyle.localsummarizer.appContainer
import com.stastyle.localsummarizer.data.settings.AppSettings
import com.stastyle.localsummarizer.nativebridge.NativeLib
import com.stastyle.localsummarizer.pipeline.CpuTopology
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

    /**
     * Call once per process, before any run can start. A leftover breadcrumb
     * belongs to a process that died, so it is promoted to the outcome record
     * — on disk, not in memory: the app crashed six times in a row once, and
     * an in-memory finding was discarded by the very next restart.
     */
    fun captureFromPreviousProcess(context: Context) {
        val breadcrumb = breadcrumbFile(context)
        runCatching {
            if (!breadcrumb.exists()) return@runCatching
            val parts = breadcrumb.readLines()
            val stage = parts.getOrNull(0).orEmpty()
            val at = parts.getOrNull(1)?.toLongOrNull() ?: System.currentTimeMillis()
            outcomeFile(context).writeText("process died during \"$stage\"\n$at")
        }
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

    suspend fun report(context: Context): String {
        // Which models and which decode settings produced a bad result is the
        // first thing anyone needs to know, and the report is the only place
        // the user can read it back.
        val settings = runCatching { context.appContainer().settingsRepository.current() }
            .getOrNull()
        return build(context, settings)
    }

    private fun build(context: Context, settings: AppSettings?): String = buildString {
        appendLine("Local Summarizer diagnostics")
        appendLine("build:    ${BuildConfig.GIT_SHA.take(12).ifBlank { "unknown" }} " +
            "(${BuildConfig.VERSION_NAME}, ${if (BuildConfig.DEBUG) "debug" else "release"})")
        appendLine("device:   ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android:  ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("abis:     ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("page size: ${pageSize()}")
        appendLine("cpu cores: ${Runtime.getRuntime().availableProcessors()}")
        appendLine()

        if (settings == null) {
            appendLine("settings: unreadable")
        } else {
            appendLine("transcription model: ${settings.whisperModelName.ifBlank { "not set" }}")
            appendLine("summarization model: ${settings.llamaModelName.ifBlank { "not set" }}")
            val threads = if (settings.threads > 0) {
                "${settings.threads} (set)"
            } else {
                "${CpuTopology.inferenceThreads()} (auto, from allowed cores)"
            }
            appendLine("language: ${settings.language}   threads: $threads")
            // Which cores the process may use right now. A backgrounded app is
            // commonly confined to the little cluster, and that — not the
            // model — can dominate how long a run takes.
            appendLine(CpuTopology.describe() + thermalNow(context))
            appendLine("beam size: ${settings.beamSize}" +
                "   carry context: ${settings.transcriptionContext}")
            appendLine("glossary: ${settings.transcriptionPrompt.take(120)}")
            appendLine("llm ctx: ${settings.contextSize}   max tokens: ${settings.maxTokens}" +
                "   temperature: ${settings.temperature}")
        }
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

        // The system's own record of why previous processes died — the only
        // native crash trace reachable without adb.
        appendLine("process exits:")
        appendLine(ExitReasons.summary(context))
        appendLine()

        val javaCrashes = runCatching { CrashLog.recent(context) }.getOrDefault("")
        if (javaCrashes.isBlank()) {
            appendLine("java crashes: none recorded")
        } else {
            appendLine("java crashes (newest first):")
            appendLine(javaCrashes)
        }
        appendLine()

        val engineLog = runCatching { NativeLib.engineLog() }.getOrDefault("")
        if (engineLog.isBlank()) {
            appendLine("engine log: (empty — no model has been loaded yet)")
        } else {
            appendLine("engine log (most recent ${engineLog.length} chars):")
            appendLine(engineLog.trimEnd())
        }
    }

    /**
     * Read here as well as during a run: the report is taken after the fact,
     * when the phone has cooled, so this is the baseline the run's own peak
     * should be compared against.
     */
    private fun thermalNow(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        val status = runCatching {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        }.getOrNull() ?: return ""
        return "  (thermal now: $status)"
    }

    private fun pageSize(): String = runCatching {
        "${Os.sysconf(OsConstants._SC_PAGESIZE) / 1024} KB"
    }.getOrDefault("unknown")
}
