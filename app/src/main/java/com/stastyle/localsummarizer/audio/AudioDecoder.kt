package com.stastyle.localsummarizer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any standard audio container (M4A/AAC, MP3, WAV, OGG, …) into
 * 16 kHz mono float PCM in [-1, 1] — the input format whisper.cpp expects.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16000

    class DecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Decodes [uri] into [target] as raw little-endian float32 samples and
     * returns how many samples were written.
     */
    fun decodeToFile(
        context: Context,
        uri: Uri,
        target: File,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Long {
        val samples = PcmFileSink(target).use { sink ->
            try {
                decodeWithMediaCodec(context, uri, sink, onProgress, isCancelled)
            } catch (e: DecodeException) {
                // MediaExtractor rejects some WAV variants; try a manual RIFF
                // parse before giving up. The sink may hold a partial decode,
                // so restart it from scratch.
                sink.close()
                target.delete()
                return PcmFileSink(target).use { retrySink ->
                    if (!decodeWavFallback(context, uri, retrySink, isCancelled)) throw e
                    onProgress(100)
                    retrySink.sampleCount
                }
            }
            sink.sampleCount
        }
        if (samples == 0L) {
            target.delete()
            throw DecodeException("Decoded audio is empty")
        }
        return samples
    }

    private fun decodeWithMediaCodec(
        context: Context,
        uri: Uri,
        sink: PcmSink,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.declaredLength < 0) {
                    extractor.setDataSource(afd.fileDescriptor)
                } else {
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                }
            } ?: throw DecodeException("Cannot open audio file")

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw DecodeException("No audio track found in file")
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw DecodeException("No decoder available for $mime", e)
            }

            try {
                codec.configure(format, null, null, 0)
                codec.start()

                var srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                var resampler = LinearResampler(srcSampleRate, TARGET_SAMPLE_RATE)

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastProgress = -1

                while (!outputDone) {
                    if (isCancelled()) {
                        throw DecodeException("Cancelled")
                    }
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, sampleSize, extractor.sampleTime, 0,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            srcSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            resampler = LinearResampler(srcSampleRate, TARGET_SAMPLE_RATE)
                        }
                        outIndex >= 0 -> {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(outIndex)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val mono = toMonoFloats(buffer, pcmEncoding, channels)
                                resampler.process(mono, mono.size, sink)

                                if (durationUs > 0 && info.presentationTimeUs > 0) {
                                    val progress =
                                        (info.presentationTimeUs * 100 / durationUs).toInt()
                                            .coerceIn(0, 100)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }

            onProgress(100)
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("Failed to decode audio: ${e.message}", e)
        } finally {
            extractor.release()
        }
    }

    private fun toMonoFloats(buffer: ByteBuffer, pcmEncoding: Int, channels: Int): FloatArray {
        buffer.order(ByteOrder.nativeOrder())
        val ch = channels.coerceAtLeast(1)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                val frames = floats.remaining() / ch
                FloatArray(frames) { frame ->
                    var sum = 0f
                    for (c in 0 until ch) sum += floats.get(frame * ch + c)
                    sum / ch
                }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                val frames = shorts.remaining() / ch
                FloatArray(frames) { frame ->
                    var sum = 0f
                    for (c in 0 until ch) sum += shorts.get(frame * ch + c) / 32768f
                    sum / ch
                }
            }
        }
    }

    // ---------------------------------------------------------------- WAV --

    private fun decodeWavFallback(
        context: Context,
        uri: Uri,
        sink: PcmSink,
        isCancelled: () -> Boolean,
    ): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                decodeWavStream(stream, sink, isCancelled)
            } ?: false
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun decodeWavStream(
        stream: InputStream,
        sink: PcmSink,
        isCancelled: () -> Boolean,
    ): Boolean {
        val header = ByteArray(12)
        if (!readFully(stream, header, 12)) return false
        if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return false

        var audioFormat = 1
        var channels = 1
        var sampleRate = TARGET_SAMPLE_RATE
        var bitsPerSample = 16
        var fmtSeen = false

        val chunkHeader = ByteArray(8)
        while (true) {
            if (!readFully(stream, chunkHeader, 8)) return false
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

            if (chunkId == "fmt ") {
                val fmt = ByteArray(chunkSize.toInt().coerceAtMost(64))
                if (!readFully(stream, fmt, fmt.size)) return false
                skipFully(stream, chunkSize - fmt.size)
                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                audioFormat = bb.short.toInt() and 0xFFFF
                channels = (bb.short.toInt() and 0xFFFF).coerceAtLeast(1)
                sampleRate = bb.int
                bb.int // byte rate
                bb.short // block align
                bitsPerSample = bb.short.toInt() and 0xFFFF
                if (audioFormat == 0xFFFE && chunkSize >= 40) {
                    // WAVE_FORMAT_EXTENSIBLE: sub-format GUID starts at offset 24
                    audioFormat = ByteBuffer.wrap(fmt, 24, 2)
                        .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                }
                fmtSeen = true
            } else if (chunkId == "data") {
                if (!fmtSeen || sampleRate <= 0) return false
                return readWavData(
                    stream, chunkSize, audioFormat, channels, sampleRate,
                    bitsPerSample, sink, isCancelled,
                )
            } else {
                // chunks are word-aligned
                skipFully(stream, chunkSize + (chunkSize and 1L))
            }
        }
    }

    private fun readWavData(
        stream: InputStream,
        dataSize: Long,
        audioFormat: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        sink: PcmSink,
        isCancelled: () -> Boolean,
    ): Boolean {
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4) return false
        val frameSize = bytesPerSample * channels
        val resampler = LinearResampler(sampleRate, TARGET_SAMPLE_RATE)
        val chunk = ByteArray(64 * 1024 - (64 * 1024 % frameSize))
        var remaining = if (dataSize <= 0) Long.MAX_VALUE else dataSize

        while (remaining > 0) {
            if (isCancelled()) throw DecodeException("Cancelled")
            val toRead = minOf(chunk.size.toLong(), remaining).toInt()
            val read = stream.read(chunk, 0, toRead)
            if (read <= 0) break
            remaining -= read
            val frames = read / frameSize
            if (frames == 0) continue
            val bb = ByteBuffer.wrap(chunk, 0, frames * frameSize).order(ByteOrder.LITTLE_ENDIAN)
            val mono = FloatArray(frames)
            for (frame in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    sum += readWavSample(bb, audioFormat, bitsPerSample)
                }
                mono[frame] = sum / channels
            }
            resampler.process(mono, mono.size, sink)
        }
        return true
    }

    private fun readWavSample(bb: ByteBuffer, audioFormat: Int, bitsPerSample: Int): Float {
        return when {
            audioFormat == 3 && bitsPerSample == 32 -> bb.float
            bitsPerSample == 16 -> bb.short / 32768f
            bitsPerSample == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
            bitsPerSample == 24 -> {
                val b0 = bb.get().toInt() and 0xFF
                val b1 = bb.get().toInt() and 0xFF
                val b2 = bb.get().toInt()
                ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
            }
            bitsPerSample == 32 -> bb.int / 2147483648f
            else -> 0f
        }
    }

    private fun readFully(stream: InputStream, target: ByteArray, count: Int): Boolean {
        var offset = 0
        while (offset < count) {
            val read = stream.read(target, offset, count - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) throw EOFException()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}

/**
 * Streaming linear-interpolation resampler that keeps state across chunks, so
 * audio can be converted while it is being decoded without seams.
 */
class LinearResampler(srcRate: Int, dstRate: Int) {
    private val step = srcRate.toDouble() / dstRate
    private var pos = 0.0
    private var prev = 0f
    private var primed = false

    fun process(input: FloatArray, count: Int, out: PcmSink) {
        for (i in 0 until count) {
            val current = input[i]
            if (!primed) {
                primed = true
                prev = current
                continue
            }
            while (pos < 1.0) {
                out.add((prev + (current - prev) * pos).toFloat())
                pos += step
            }
            pos -= 1.0
            prev = current
        }
    }
}
