package com.stastyle.localsummarizer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LinearResamplerTest {

    private class Collector : PcmSink {
        val samples = ArrayList<Float>()
        override fun add(value: Float) {
            samples.add(value)
        }
    }

    private fun tone(rate: Int, seconds: Double, freq: Double) =
        FloatArray((rate * seconds).toInt()) { sin(2 * PI * freq * it / rate).toFloat() }

    /** Feeds audio in irregular chunks, the way MediaCodec delivers it. */
    private fun resample(input: FloatArray, srcRate: Int): List<Float> {
        val out = Collector()
        val resampler = LinearResampler(srcRate, AudioDecoder.TARGET_SAMPLE_RATE)
        var offset = 0
        while (offset < input.size) {
            val n = minOf(1023, input.size - offset)
            resampler.process(input.copyOfRange(offset, offset + n), n, out)
            offset += n
        }
        return out.samples
    }

    @Test
    fun `one second of any common rate produces one second at 16 kHz`() {
        for (srcRate in intArrayOf(48000, 44100, 32000, 22050, 16000, 8000)) {
            val produced = resample(tone(srcRate, 1.0, 440.0), srcRate).size
            assertTrue(
                "$srcRate Hz produced $produced samples",
                abs(produced - AudioDecoder.TARGET_SAMPLE_RATE) <= 3,
            )
        }
    }

    @Test
    fun `speech-band tone keeps its amplitude`() {
        val peak = resample(tone(48000, 0.5, 440.0), 48000).maxOf { abs(it) }
        assertTrue("peak was $peak", peak > 0.85f)
    }

    @Test
    fun `content above the Nyquist limit is attenuated instead of aliased into speech`() {
        val peak = resample(tone(48000, 0.5, 15000.0), 48000).maxOf { abs(it) }
        assertTrue("15 kHz leaked through at $peak", peak < 0.5f)
    }

    @Test
    fun `chunk boundaries do not change the output`() {
        val input = tone(44100, 0.3, 1000.0)
        val oneShot = Collector()
        LinearResampler(44100, AudioDecoder.TARGET_SAMPLE_RATE)
            .process(input, input.size, oneShot)
        val chunked = resample(input, 44100)
        assertEquals(oneShot.samples.size, chunked.size)
        for (i in chunked.indices) {
            assertEquals(oneShot.samples[i], chunked[i], 1e-6f)
        }
    }
}
