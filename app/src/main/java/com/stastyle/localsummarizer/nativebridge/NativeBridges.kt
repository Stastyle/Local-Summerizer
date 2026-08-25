package com.stastyle.localsummarizer.nativebridge

object NativeLib {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("summarizer")
                    loaded = true
                }
            }
        }
    }
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

    fun load(modelPath: String): Long {
        NativeLib.ensureLoaded()
        return nativeInit(modelPath)
    }

    fun transcribe(
        handle: Long,
        pcm: FloatArray,
        language: String,
        threads: Int,
        translate: Boolean = false,
        listener: Listener? = null,
    ): String = nativeTranscribe(handle, pcm, language, threads, translate, listener)

    fun cancel() {
        NativeLib.ensureLoaded()
        nativeCancel()
    }

    fun free(handle: Long) {
        if (handle != 0L) nativeFree(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
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

    fun load(modelPath: String, contextSize: Int, threads: Int): Long {
        NativeLib.ensureLoaded()
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

    fun cancel() {
        NativeLib.ensureLoaded()
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
