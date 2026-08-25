package com.stastyle.localsummarizer.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Destination for decoded 16 kHz mono float samples. */
interface PcmSink {
    fun add(value: Float)
}

/**
 * Streams samples to a raw little-endian float32 file. A one-hour meeting is
 * ~230MB of PCM, far past what the Java heap allows, so decoded audio never
 * lives in a FloatArray — the native side memory-maps it instead.
 */
class PcmFileSink(file: File, bufferSamples: Int = 1 shl 16) : PcmSink, Closeable {

    private val stream: OutputStream = BufferedOutputStream(file.outputStream(), 1 shl 16)
    private val buffer: ByteBuffer =
        ByteBuffer.allocate(bufferSamples * 4).order(ByteOrder.LITTLE_ENDIAN)

    var sampleCount: Long = 0
        private set

    override fun add(value: Float) {
        if (!buffer.hasRemaining()) flushBuffer()
        buffer.putFloat(value)
        sampleCount++
    }

    private fun flushBuffer() {
        stream.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    override fun close() {
        flushBuffer()
        stream.flush()
        stream.close()
    }
}

/** Growable in-memory buffer, used for short clips and tests. */
class FloatArrayBuilder(initialCapacity: Int = 1 shl 16) : PcmSink {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    override fun add(value: Float) {
        if (size == data.size) {
            data = data.copyOf(data.size * 2)
        }
        data[size++] = value
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)
}
