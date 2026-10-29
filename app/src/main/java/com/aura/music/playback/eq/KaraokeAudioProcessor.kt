package com.aura.music.playback.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Karaoke: in most studio recordings the lead vocal sits in the centre, identical in both channels,
 * so subtracting one channel from the other removes it. Bass and kick drum also sit in the centre,
 * so the lows (below ~[BASS_HZ]) are added back from the original. Works on stereo only; how well
 * depends on the mix (reverb and doubled vocals survive).
 */
@UnstableApi
class KaraokeAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled = false

    // Two cascaded Butterworth low-pass biquads (24 dB/octave) keep the bass and nothing above it.
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private val state = FloatArray(4)
    /** Fades the effect in and out instead of switching abruptly (0 = off, 1 = full). */
    private var mix = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        val w = 2 * PI * BASS_HZ / inputAudioFormat.sampleRate
        val alpha = sin(w) / (2 * BUTTERWORTH_Q)
        val c = cos(w)
        val a0 = 1 + alpha
        b0 = ((1 - c) / 2 / a0).toFloat()
        b1 = ((1 - c) / a0).toFloat()
        b2 = b0
        a1 = (-2 * c / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
        return inputAudioFormat
    }

    override fun onFlush() {
        state.fill(0f)
    }

    private fun lowPass(x: Float): Float {
        var v = x
        for (stage in 0..1) {
            val i = stage * 2
            val y = b0 * v + state[i]
            state[i] = b1 * v - a1 * y + state[i + 1]
            state[i + 1] = b2 * v - a2 * y
            v = y
        }
        return v
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val stereo = inputAudioFormat.channelCount == 2
        if (!stereo || (!enabled && mix == 0f)) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val target = if (enabled) 1f else 0f
        val step = 1f / (inputAudioFormat.sampleRate * FADE_SECONDS)
        while (input.remaining() >= (if (float) 8 else 4)) {
            val l = if (float) input.getFloat() else input.getShort() / 32_768f
            val r = if (float) input.getFloat() else input.getShort() / 32_768f
            val side = (l - r) / 2
            val bass = lowPass((l + r) / 2)
            mix = if (mix < target) minOf(target, mix + step) else maxOf(target, mix - step)
            val outL = l + mix * ((bass + side) - l)
            val outR = r + mix * ((bass - side) - r)
            if (float) {
                output.putFloat(outL)
                output.putFloat(outR)
            } else {
                output.putShort((outL * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
                output.putShort((outR * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
            }
        }
        output.flip()
    }

    private companion object {
        const val BASS_HZ = 160.0
        const val FADE_SECONDS = 0.4f
        const val BUTTERWORTH_Q = 0.7071
    }
}
