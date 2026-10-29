package com.aura.music.together

import com.aura.music.playback.stream.GrowingFiles
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GrowingFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `readers wait for bytes that haven't arrived and see the end once complete`() {
        val file = temp.root.resolve("song")
        GrowingFiles.expect(file, 10)
        GrowingFiles.received(file, 4)
        assertEquals(4, GrowingFiles.awaitAvailable(file, 0, 1_000))
        val waited = AtomicReference<Long>()
        val reader = thread { waited.set(GrowingFiles.awaitAvailable(file, 6, 5_000)) }
        Thread.sleep(200)
        assertTrue("still waiting for byte 6", reader.isAlive)
        GrowingFiles.received(file, 10)
        reader.join(2_000)
        assertEquals(4L, waited.get())

        GrowingFiles.partOf(file).writeBytes(ByteArray(10))
        GrowingFiles.partOf(file).renameTo(file)
        GrowingFiles.complete(file)
        assertEquals(0, GrowingFiles.awaitAvailable(file, 10, 1_000))
    }

    @Test
    fun `a failed or cancelled transfer never leaves the player hanging`() {
        val file = temp.root.resolve("song")
        GrowingFiles.expect(file, 10)
        val failure = AtomicReference<Throwable>()
        val reader = thread { runCatching { GrowingFiles.awaitAvailable(file, 0, 30_000) }.onFailure(failure::set) }
        Thread.sleep(100)
        GrowingFiles.fail(file)
        reader.join(2_000)
        assertTrue(failure.get() is IOException)

        val other = temp.root.resolve("other")
        GrowingFiles.expect(other, 10)
        val cancelled = AtomicReference<Throwable>()
        val loader = thread { runCatching { GrowingFiles.awaitAvailable(other, 0, 30_000) }.onFailure(cancelled::set) }
        Thread.sleep(100)
        loader.interrupt()
        loader.join(2_000)
        assertTrue(cancelled.get() is InterruptedIOException)
    }

    @Test
    fun `a stalled transfer times out`() {
        val file = temp.root.resolve("song")
        GrowingFiles.expect(file, 10)
        val started = System.currentTimeMillis()
        val failure = runCatching { GrowingFiles.awaitAvailable(file, 0, 300) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }
}
