package com.aura.desktop.audio

/**
 * A silent sink that behaves like a sound card: it "plays" [speed] times faster than real time,
 * holds a limited buffer (writes block while it's full), and stops consuming while stopped.
 */
class RealtimeSink(
    override val sampleRate: Int = FfmpegDecoder.OUTPUT_RATE,
    private val speed: Double = 4.0,
    bufferMs: Int = 250,
) : AudioSink {
    private val lock = Object()
    private val capacity = sampleRate.toLong() * FfmpegDecoder.FRAME_BYTES * bufferMs / 1000
    private var queued = 0L
    private var running = false
    private var lastTick = System.nanoTime()
    @Volatile var playedBytes = 0L

    private fun consume() {
        val now = System.nanoTime()
        if (running) {
            val bytes = ((now - lastTick) / 1e9 * sampleRate * FfmpegDecoder.FRAME_BYTES * speed).toLong()
            val used = minOf(bytes, queued)
            queued -= used
            playedBytes += used
        }
        lastTick = now
    }

    override fun open() = Unit

    override fun start() = synchronized(lock) { consume(); running = true; lock.notifyAll() }

    override fun stop() = synchronized(lock) { consume(); running = false }

    override fun flush() = synchronized(lock) { queued = 0; lock.notifyAll() }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            while (true) {
                consume()
                if (queued + length <= capacity) {
                    queued += length
                    return length
                }
                lock.wait(5)
            }
        }
    }

    override fun queuedBytes(): Int = synchronized(lock) { consume(); queued.toInt() }

    override fun close() = Unit
}
