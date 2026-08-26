package com.stastyle.localsummarizer.nativebridge

import android.content.Context
import android.os.Build
import android.util.Log
import com.stastyle.localsummarizer.BuildConfig
import com.stastyle.localsummarizer.R
import java.io.File
import java.util.zip.ZipFile

object NativeLib {
    private const val TAG = "NativeLib"
    private const val CPU_BACKEND_PREFIX = "libggml-cpu-"

    @Volatile
    private var loaded = false

    // Distinct from [loaded]: the .so is in the process but the backends may
    // have failed to register. That is exactly when the diagnostics matter, so
    // they must still be readable.
    @Volatile
    private var libraryLoaded = false

    /**
     * ggml ships one CPU backend per ARM feature level and picks the best one
     * the device supports at runtime. It finds them by listing a directory,
     * and its own default search path resolves to the zygote on Android, so
     * the app has to hand it a directory that really holds the files.
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("summarizer")
            libraryLoaded = true
            val searchDir = backendDirectory(context)
            nativeLoadBackends(searchDir)
            val registered = nativeBackendCount()
            Log.i(TAG, "ggml registered $registered backend(s) from $searchDir")
            // Without a CPU backend, whisper hits GGML_ASSERT on a null device
            // and aborts the process. Fail with a readable message instead.
            check(registered > 0) { context.getString(R.string.error_no_cpu_backend) }
            loaded = true
        }
    }

    val isLoaded: Boolean get() = loaded

    fun backendCount(): Int = nativeBackendCount()

    /** Registered backends and devices, for the in-app diagnostics report. */
    fun backendReport(): String =
        if (libraryLoaded) nativeBackendReport() else "native library not loaded"

    /**
     * The tail of ggml/llama/whisper's own log. A sideloaded app cannot be
     * granted READ_LOGS, so this is the only way the user can hand over what
     * the engines said.
     */
    fun engineLog(): String = if (libraryLoaded) nativeEngineLog() else ""

    /**
     * Normally the installer extracts the libraries and [ApplicationInfo
     * .nativeLibraryDir] is exactly what ggml wants. If packaging ever leaves
     * them compressed inside the APK that directory comes up empty, so unpack
     * the CPU variants once into private storage and scan that instead.
     */
    private fun backendDirectory(context: Context): String {
        val installed = File(context.applicationInfo.nativeLibraryDir)
        if (installed.hasCpuBackends()) return installed.absolutePath
        Log.w(TAG, "$installed holds no ggml CPU backends; unpacking them from the APK")
        return stageBackendsFromApk(context)?.absolutePath ?: installed.absolutePath
    }

    private fun File.hasCpuBackends(): Boolean =
        isDirectory && listFiles { file -> file.isCpuBackend() }?.isNotEmpty() == true

    private fun File.isCpuBackend(): Boolean =
        name.startsWith(CPU_BACKEND_PREFIX) && name.endsWith(".so")

    private fun stageBackendsFromApk(context: Context): File? = runCatching {
        // Stamped with the build so an app update never reuses stale backends.
        val root = File(context.noBackupFilesDir, "ggml-backends")
        val target = File(root, BuildConfig.GIT_SHA.ifBlank { BuildConfig.VERSION_NAME })
        if (target.hasCpuBackends()) return@runCatching target
        root.deleteRecursively()
        target.mkdirs()

        val archives = buildList {
            add(context.applicationInfo.sourceDir)
            context.applicationInfo.splitSourceDirs?.let { addAll(it) }
        }
        // Only the ABI the process actually runs; the others would fail dlopen.
        for (abi in Build.SUPPORTED_ABIS) {
            var extracted = 0
            for (archive in archives) {
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("lib/$abi/") }
                        .filter { File(it.name).isCpuBackend() }
                        .forEach { entry ->
                            val out = File(target, File(entry.name).name)
                            zip.getInputStream(entry).use { input ->
                                out.outputStream().use { output -> input.copyTo(output) }
                            }
                            extracted++
                        }
                }
            }
            if (extracted > 0) {
                Log.i(TAG, "unpacked $extracted ggml CPU backend(s) for $abi into $target")
                return@runCatching target
            }
        }
        null
    }.onFailure { Log.w(TAG, "could not unpack ggml backends from the APK", it) }.getOrNull()

    private external fun nativeLoadBackends(nativeLibDir: String)
    private external fun nativeBackendCount(): Int
    private external fun nativeBackendReport(): String
    private external fun nativeEngineLog(): String
}

/**
 * JNI bridge to whisper.cpp. All heavy calls are blocking — invoke from a
 * background dispatcher only.
 */
object WhisperBridge {

    interface Listener {
        fun onProgress(percent: Int)
        fun onSegment(text: String)
    }

    fun load(context: Context, modelPath: String): Long {
        NativeLib.ensureLoaded(context)
        return nativeInit(modelPath)
    }

    /**
     * [pcmPath] holds raw little-endian float32 mono samples at 16 kHz.
     *
     * [beamSize] above 1 selects beam search over greedy decoding.
     * [useContext] conditions each window on the text before it.
     * [initialPrompt] is a glossary carried into every window.
     */
    fun transcribeFile(
        handle: Long,
        pcmPath: String,
        language: String,
        threads: Int,
        translate: Boolean = false,
        beamSize: Int = 5,
        useContext: Boolean = true,
        initialPrompt: String = "",
        listener: Listener? = null,
    ): String = nativeTranscribeFile(
        handle, pcmPath, language, threads, translate, beamSize, useContext,
        initialPrompt, listener,
    )

    /** Safe to call before the library is loaded; then there is nothing to stop. */
    fun cancel() {
        if (!NativeLib.isLoaded) return
        nativeCancel()
    }

    fun resetCancel() {
        if (!NativeLib.isLoaded) return
        nativeResetCancel()
    }

    fun free(handle: Long) {
        if (handle != 0L) nativeFree(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribeFile(
        handle: Long,
        pcmPath: String,
        language: String,
        threads: Int,
        translate: Boolean,
        beamSize: Int,
        useContext: Boolean,
        initialPrompt: String,
        listener: Listener?,
    ): String
    private external fun nativeCancel()
    private external fun nativeResetCancel()
    private external fun nativeFree(handle: Long)
}

/**
 * JNI bridge to llama.cpp. All heavy calls are blocking — invoke from a
 * background dispatcher only.
 */
object LlamaBridge {

    fun interface TokenListener {
        /** Receives a decoded UTF-8 piece; return false to stop generation. */
        fun onToken(piece: String): Boolean
    }

    fun load(context: Context, modelPath: String, contextSize: Int, threads: Int): Long {
        NativeLib.ensureLoaded(context)
        return nativeInit(modelPath, contextSize, threads)
    }

    fun tokenCount(handle: Long, text: String): Int = nativeTokenCount(handle, text)

    /**
     * [hebrewOnly] biases every token that is not Hebrew, a digit or
     * punctuation out of reach, so the model cannot code-switch mid-sentence.
     */
    fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        seed: Int = 42,
        hebrewOnly: Boolean = true,
        listener: TokenListener? = null,
    ): String = nativeGenerate(
        handle, prompt, maxTokens, temperature, seed, hebrewOnly, listener,
    )

    /** Safe to call before the library is loaded; then there is nothing to stop. */
    fun cancel() {
        if (!NativeLib.isLoaded) return
        nativeCancel()
    }

    fun resetCancel() {
        if (!NativeLib.isLoaded) return
        nativeResetCancel()
    }

    fun free(handle: Long) {
        if (handle != 0L) nativeFree(handle)
    }

    private external fun nativeInit(modelPath: String, contextSize: Int, threads: Int): Long
    private external fun nativeTokenCount(handle: Long, text: String): Int
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
        hebrewOnly: Boolean,
        listener: TokenListener?,
    ): String
    private external fun nativeCancel()
    private external fun nativeResetCancel()
    private external fun nativeFree(handle: Long)
}
