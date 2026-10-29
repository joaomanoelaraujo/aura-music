package com.aura.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.aura.music.playback.eq.KaraokeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeTest {
    private val rate = 44_100

    /** Centre "voice" at 600 Hz, a "guitar" at 1.3 kHz on the left only, a "bass" at 70 Hz in the centre. */
    private fun mix(voice: Double, guitar: Double, bass: Double): ShortArray {
        val frames = rate
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val v = voice * sin(2 * PI * 600 * t)
            val g = guitar * sin(2 * PI * 1_300 * t)
            val b = bass * sin(2 * PI * 70 * t)
            out[2 * i] = ((v + g + b) * 32_767).toInt().toShort()
            out[2 * i + 1] = ((v + b) * 32_767).toInt().toShort()
        }
        return out
    }

    private fun run(processor: KaraokeAudioProcessor, input: ShortArray): ShortArray {
        val buffer = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach(buffer::putShort)
        buffer.flip()
        processor.queueInput(buffer)
        val out = processor.output
        return ShortArray(out.remaining() / 2) { out.short }
    }

    /**
     * Amplitude of [hz] in one channel (left, right) or their average (both), after the effect has
     * faded in (second half). Side sound is out of phase between channels, so it's heard per channel.
     */
    private fun level(samples: ShortArray, hz: Double, channel: String = "both"): Double {
        var re = 0.0
        var im = 0.0
        val frames = samples.size / 2
        for (i in frames / 2 until frames) {
            val x = when (channel) {
                "left" -> samples[2 * i].toDouble()
                "right" -> samples[2 * i + 1].toDouble()
                else -> (samples[2 * i] + samples[2 * i + 1]) / 2.0
            } / 32_767
            re += x * cos(2 * PI * hz * i / rate)
            im += x * sin(2 * PI * hz * i / rate)
        }
        return 2 * sqrt(re * re + im * im) / (frames / 2)
    }

    private fun processor(on: Boolean) = KaraokeAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush()
        enabled = on
    }

    @Test
    fun `the centred voice goes away while the side instrument and the bass stay`() {
        val input = mix(voice = 0.4, guitar = 0.2, bass = 0.3)
        val output = run(processor(on = true), input)
        for (side in listOf("left", "right")) {
            val voiceBefore = level(input, 600.0, side)
            val voiceAfter = level(output, 600.0, side)
            assertTrue("$side voice $voiceBefore -> $voiceAfter", voiceAfter < voiceBefore * 0.05)
        }
        // The left-only guitar comes out at half level in each ear (it's spread to both sides).
        val guitar = level(input, 1_300.0, "left")
        assertTrue("guitar kept: ${level(output, 1_300.0, "left")}", level(output, 1_300.0, "left") > guitar * 0.4)
        assertTrue("bass kept: ${level(output, 70.0)}", level(output, 70.0) > level(input, 70.0) * 0.6)
    }

    @Test
    fun `off it leaves the audio untouched, and mono audio is never altered`() {
        val input = mix(0.4, 0.2, 0.3)
        assertArrayEquals(input, run(processor(on = false), input))
        val mono = KaraokeAudioProcessor().apply {
            configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
            flush()
            enabled = true
        }
        val samples = ShortArray(1_000) { (it * 7).toShort() }
        assertArrayEquals(samples, run(mono, samples))
        assertTrue(abs(level(input, 600.0) - 0.4) < 0.02)
    }
}
