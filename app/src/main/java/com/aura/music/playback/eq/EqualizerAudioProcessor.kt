package com.aura.music.playback.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs [EqualizerDsp] inside the player's audio pipeline, so the equalizer works on every phone
 * (it doesn't depend on the device's audio effects) and the same way in an AURA Together jam.
 * Settings can change from any thread; they are picked up on the audio thread.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    private class Params(val gainsDb: FloatArray, val enabled: Boolean)

    @Volatile private var params = Params(FloatArray(EqBands.count), enabled = false)
    private var applied: Params? = null
    private var dsp: EqualizerDsp? = null
    private var scratch = FloatArray(0)

    fun update(gainsDb: FloatArray, enabled: Boolean) {
        params = Params(gainsDb.copyOf(), enabled)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val format = inputAudioFormat
        dsp = EqualizerDsp(format.sampleRate, format.channelCount)
        applied = null
    }

    override fun onReset() {
        dsp = null
        applied = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val processor = dsp
        val current = params
        if (processor != null && current !== applied) {
            processor.setGains(current.gainsDb, current.enabled)
            applied = current
        }
        if (processor == null || !processor.active) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val samples = if (float) size / 4 else size / 2
        if (scratch.size < samples) scratch = FloatArray(samples)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) {
            for (i in 0 until samples) scratch[i] = input.getFloat()
        } else {
            for (i in 0 until samples) scratch[i] = input.getShort() / 32_768f
        }
        processor.process(scratch, samples)
        if (float) {
            for (i in 0 until samples) output.putFloat(scratch[i])
        } else {
            for (i in 0 until samples) output.putShort((scratch[i] * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
        }
        output.flip()
    }
}
