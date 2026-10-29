package com.aura.desktop.audio

import com.aura.desktop.TestMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Accepts PCM as fast as it comes and keeps it, so tests run silently and quickly. */
class CapturingSink(override val sampleRate: Int = FfmpegDecoder.OUTPUT_RATE) : AudioSink {
    @Volatile var bytes = 0L
    @Volatile var started = false
    @Volatile var peak = 0

    override fun open() = Unit
    override fun start() { started = true }
    override fun stop() { started = false }
    override fun flush() = Unit
    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        var i = offset
        while (i < offset + length - 1) {
            val s = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            if (abs(s) > peak) peak = abs(s)
            i += 2
        }
        this.bytes += length
        return length
    }
    override fun queuedBytes() = 0
    override fun close() = Unit
}

class AudioEngineTest {

    @Test
    fun decodesEveryFormatToTheExpectedLength() {
        val expected = mapOf(
            "Album/01.mp3" to 3000L, "Album/02.flac" to 3000L, "03.ogg" to 3000L,
            "04.m4a" to 3000L, "05.opus" to 3000L, "gravação sem tags.wav" to 2000L,
        )
        for ((name, ms) in expected) {
            FfmpegDecoder(TestMedia.file(name).toString()).use { d ->
                var bytes = 0L
                while (true) bytes += (d.read() ?: break).size
                val decodedMs = bytes / FfmpegDecoder.FRAME_BYTES * 1000 / FfmpegDecoder.OUTPUT_RATE
                // Encoders pad a few ms (priming/frame size); anything near the real length is fine.
                assertTrue("$name decoded $decodedMs ms", abs(decodedMs - ms) <= 80)
                assertTrue("$name duration ${d.durationMs}", abs(d.durationMs - ms) <= 80)
            }
        }
    }

    @Test
    fun seekStartsAtTheRequestedSample() {
        FfmpegDecoder(TestMedia.file("Album/02.flac").toString()).use { d ->
            d.seek(1_250)
            var bytes = 0L
            while (true) bytes += (d.read() ?: break).size
            val remainingMs = bytes / FfmpegDecoder.FRAME_BYTES * 1000 / FfmpegDecoder.OUTPUT_RATE
            assertTrue("remaining $remainingMs", abs(remainingMs - 1_750) <= 25)
        }
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsFilesThatAreNotAudio() {
        FfmpegDecoder(TestMedia.file("notes.txt").toString()).close()
    }

    @Test
    fun playsToTheEndAndReports() {
        val sink = CapturingSink()
        AudioEngine(sink).use { engine ->
            val ended = CountDownLatch(1)
            engine.listener = object : AudioEngine.Listener {
                override fun onEnded(source: String) = ended.countDown()
                override fun onError(source: String, message: String) = Unit
            }
            engine.load(TestMedia.file("Album/01.mp3").toString())
            assertTrue(ended.await(10, TimeUnit.SECONDS))
            assertFalse(engine.state.value.isPlaying)
            val ms = sink.bytes / FfmpegDecoder.FRAME_BYTES * 1000 / sink.sampleRate
            assertTrue("played $ms ms", abs(ms - 3000) <= 80)
            assertTrue("audible signal", sink.peak > 1000)
        }
    }

    @Test
    fun volumeScalesTheSamples() {
        val sink = CapturingSink()
        AudioEngine(sink).use { engine ->
            val ended = CountDownLatch(1)
            engine.listener = object : AudioEngine.Listener {
                override fun onEnded(source: String) = ended.countDown()
                override fun onError(source: String, message: String) = Unit
            }
            engine.volume = 0f
            engine.load(TestMedia.file("03.ogg").toString())
            assertTrue(ended.await(10, TimeUnit.SECONDS))
            assertEquals(0, sink.peak)
        }
    }

    @Test
    fun reportsUnplayableFiles() {
        AudioEngine(CapturingSink()).use { engine ->
            val failed = CountDownLatch(1)
            engine.listener = object : AudioEngine.Listener {
                override fun onEnded(source: String) = Unit
                override fun onError(source: String, message: String) = failed.countDown()
            }
            engine.load(TestMedia.file("notes.txt").toString())
            assertTrue(failed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun loadPausedThenSeekKeepsPosition() {
        AudioEngine(CapturingSink()).use { engine ->
            engine.load(TestMedia.file("Album/02.flac").toString(), startMs = 0, play = false)
            engine.seekTo(2_000)
            // The UI reads the target immediately, before the playback thread applies it.
            assertEquals(2_000, engine.positionMs)
            Thread.sleep(300)
            assertEquals(2_000, engine.positionMs)
            assertFalse(engine.state.value.isPlaying)
            assertEquals(3_000, engine.state.value.durationMs.toDouble(), 80.0)
        }
    }

    private fun assertEquals(expected: Long, actual: Double, delta: Double) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual, delta)
}
