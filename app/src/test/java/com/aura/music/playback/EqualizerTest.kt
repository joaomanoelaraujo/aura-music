package com.aura.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.aura.music.playback.eq.EqBands
import com.aura.music.playback.eq.EqPreset
import com.aura.music.playback.eq.EqualizerAudioProcessor
import com.aura.music.playback.eq.EqualizerDsp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerTest {
    private val rate = 44_100

    /** Peak level of a stereo sine at [hz] after the equalizer, ignoring the filters' warm-up. */
    private fun levelAt(hz: Int, gains: FloatArray, amplitude: Float = 0.1f): Float {
        val dsp = EqualizerDsp(rate, 2).apply { setGains(gains, enabled = true) }
        val frames = rate
        val samples = FloatArray(frames * 2) { i -> (amplitude * sin(2 * PI * hz * (i / 2) / rate)).toFloat() }
        dsp.process(samples, samples.size)
        return samples.drop(samples.size / 2).maxOf { abs(it) }
    }

    private fun db(ratio: Float) = 20 * log10(ratio)

    @Test
    fun `each band boosts its own frequency and leaves far ones alone`() {
        for ((band, hz) in EqBands.centersHz.withIndex()) {
            val gains = FloatArray(EqBands.count).also { it[band] = 9f }
            val boosted = levelAt(hz, gains)
            val farHz = if (hz < 1_000) 8_000 else 125
            val far = levelAt(farHz, gains)
            // Headroom lowers everything equally, so compare the band with a far frequency.
            val relative = db(boosted / far)
            assertTrue("$hz Hz band: +${"%.1f".format(relative)} dB relative to $farHz Hz", relative in 7.5f..10.5f)
        }
    }

    @Test
    fun `cuts work like boosts`() {
        val gains = FloatArray(EqBands.count).also { it[5] = -12f }
        val relative = db(levelAt(1_000, gains) / levelAt(125, gains))
        assertTrue("1 kHz cut: $relative dB", relative in -13f..-10.5f)
    }

    @Test
    fun `boosting everything never clips`() {
        val gains = FloatArray(EqBands.count) { 12f }
        for (hz in listOf(31, 250, 1_000, 8_000)) {
            val peak = levelAt(hz, gains, amplitude = 0.99f)
            assertTrue("$hz Hz peak $peak", peak <= 1f)
        }
    }

    @Test
    fun `presets keep the sound in range`() {
        for (preset in EqPreset.entries) {
            for (hz in listOf(62, 500, 4_000)) {
                val peak = levelAt(hz, preset.gainsDb, amplitude = 0.99f)
                assertTrue("${preset.name} at $hz Hz peak $peak", peak <= 1f)
            }
        }
    }

    private fun pcm16(values: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder()).apply { values.forEach(::putShort); flip() }

    private fun drain(processor: EqualizerAudioProcessor): ShortArray {
        val out = processor.output
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun `the player's processor passes audio untouched when off or flat, and changes it when on`() {
        val processor = EqualizerAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ShortArray(4_096) { i -> (8_000 * sin(2 * PI * 1_000 * (i / 2) / rate)).toInt().toShort() }

        processor.queueInput(pcm16(input))
        assertArrayEquals("off: bit-exact", input, drain(processor))

        processor.update(FloatArray(EqBands.count), enabled = true)
        processor.queueInput(pcm16(input))
        assertArrayEquals("flat: bit-exact", input, drain(processor))

        processor.update(FloatArray(EqBands.count).also { it[5] = 12f }, enabled = true)
        processor.queueInput(pcm16(input))
        val boosted = drain(processor)
        assertEquals(input.size, boosted.size)
        assertTrue("1 kHz louder", boosted.drop(2_000).maxOf { abs(it.toInt()) } > input.maxOf { abs(it.toInt()) })
    }

    @Test
    fun `formats it can't process are left to the player`() {
        val processor = EqualizerAudioProcessor()
        val failed = runCatching { processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_24BIT)) }.exceptionOrNull()
        assertTrue(failed is AudioProcessor.UnhandledAudioFormatException)
    }
}
