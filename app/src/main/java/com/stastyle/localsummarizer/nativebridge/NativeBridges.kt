package com.stastyle.localsummarizer.nativebridge

import android.content.Context

object NativeLib {
    @Volatile
    private var loaded = false

    /**
     * ggml ships one CPU backend per ARM feature level and picks the best one
     * the device supports at runtime. Its own search path is useless on
     * Android, so the APK's native library directory is passed in explicitly.
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("summarizer")
            nativeLoadBackends(context.applicationInfo.nativeLibraryDir)
            loaded = true
        }
    }

    val isLoaded: Boolean get() = loaded

    fun backendCount(): Int = nativeBackendCount()

    private external fun nativeLoadBackends(nativeLibDir: String)
    private external fun nativeBackendCount(): Int
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

    /** [pcmPath] holds raw little-endian float32 mono samples at 16 kHz. */
    fun transcribeFile(
        handle: Long,
        pcmPath: String,
        language: String,
        threads: Int,
        translate: Boolean = false,
        listener: Listener? = null,
    ): String = nativeTranscribeFile(handle, pcmPath, language, threads, translate, listener)

    /** Safe to call before the library is loaded; then there is nothing to stop. */
    fun cancel() {
        if (!NativeLib.isLoaded) return
        nativeCancel()
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
        listener: Listener?,
    ): String
    private external fun nativeCancel()
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

    fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        seed: Int = 42,
        listener: TokenListener? = null,
    ): String = nativeGenerate(handle, prompt, maxTokens, temperature, seed, listener)

    /** Safe to call before the library is loaded; then there is nothing to stop. */
    fun cancel() {
        if (!NativeLib.isLoaded) return
        nativeCancel()
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
        listener: TokenListener?,
    ): String
    private external fun nativeCancel()
    private external fun nativeFree(handle: Long)
}
