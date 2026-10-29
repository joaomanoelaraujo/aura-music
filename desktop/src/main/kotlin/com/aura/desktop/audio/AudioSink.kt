package com.aura.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Where decoded PCM (S16LE stereo at [sampleRate]) goes. Behind an interface so tests stay silent. */
interface AudioSink {
    val sampleRate: Int

    fun open()

    fun start()

    fun stop()

    /** Drops everything written but not yet heard. */
    fun flush()

    /** Blocks until [length] bytes are accepted (or the sink is flushed/stopped from another thread). */
    fun write(bytes: ByteArray, offset: Int, length: Int): Int

    /** Bytes written but not heard yet. */
    fun queuedBytes(): Int

    fun close()
}

/** The system's default output through Java Sound (DirectSound/WASAPI mixer on Windows). */
class JavaSoundSink(override val sampleRate: Int = FfmpegDecoder.OUTPUT_RATE) : AudioSink {
    private val format = AudioFormat(sampleRate.toFloat(), 16, FfmpegDecoder.CHANNELS, true, false)
    private var line: SourceDataLine? = null

    override fun open() {
        if (line != null) return
        line = AudioSystem.getSourceDataLine(format).apply {
            // ~250 ms of buffer: enough to ride out GC pauses, small enough for snappy pause/seek.
            open(format, sampleRate / 4 * FfmpegDecoder.FRAME_BYTES)
        }
    }

    override fun start() { line?.start() }

    override fun stop() { line?.stop() }

    override fun flush() { line?.flush() }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int = line?.write(bytes, offset, length) ?: 0

    override fun queuedBytes(): Int = line?.let { it.bufferSize - it.available() } ?: 0

    override fun close() {
        line?.close()
        line = null
    }
}
