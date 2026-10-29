package com.aura.desktop.playback

import com.aura.desktop.TestMedia
import com.aura.desktop.audio.AudioEngine
import com.aura.desktop.audio.RealtimeSink
import com.aura.desktop.library.LibraryMapper
import com.aura.desktop.library.ScannedFile
import com.aura.music.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** Real decoding into a silent sink that plays at 4x speed: checks the queue and the engine work together. */
class PlayerControllerTest {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }
    private val dispatcher = thread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val engine = AudioEngine(RealtimeSink(speed = 4.0))
    private val player = PlayerController(engine, scope, dispatcher, initialVolume = 1f)

    @After
    fun tearDown() {
        scope.cancel()
        engine.close()
        thread.shutdownNow()
    }

    private fun track(name: String): Track =
        LibraryMapper.toTrack(ScannedFile(path = TestMedia.file(name).toString(), sizeBytes = 1, modifiedMs = 0, title = name))

    private fun onMain(block: () -> Unit) = thread.submit(block).get()

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out; state=${player.state.value}" }
            Thread.sleep(20)
        }
    }

    @Test
    fun playsTheQueueInOrderAndStopsAtTheEnd() {
        val songs = listOf(track("Album/01.mp3"), track("03.ogg"))
        val seen = mutableListOf<String>()
        onMain { player.playTracks(songs, 0) }
        waitFor { player.state.value.isPlaying }
        seen += player.state.value.current!!.title
        waitFor { player.state.value.queue.index == 1 }
        seen += player.state.value.current!!.title
        // After the last song the player stops instead of looping.
        waitFor { !player.state.value.isPlaying && engine.state.value.source == songs[1].uri && engine.positionMs == 0L }
        assertEquals(listOf("Album/01.mp3", "03.ogg"), seen)
        assertEquals(1, player.state.value.queue.index)
    }

    @Test
    fun skipsFilesThatCannotBePlayed() {
        val songs = listOf(track("notes.txt"), track("04.m4a"))
        onMain { player.playTracks(songs, 0) }
        waitFor { player.state.value.queue.index == 1 && player.state.value.isPlaying }
        assertNotNull(player.state.value.notice)
        assertTrue(player.state.value.notice!!.contains("notes.txt"))
    }

    @Test
    fun aQueueOfBrokenFilesDoesNotLoopForever() {
        onMain { player.playTracks(listOf(track("notes.txt"), track("notes.txt")), 0) }
        Thread.sleep(1_500)
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun repeatOneReplaysTheSong() {
        onMain {
            player.cycleRepeat()
            player.cycleRepeat()
        }
        assertEquals(Repeat.One, player.state.value.queue.repeat)
        val songs = listOf(track("gravação sem tags.wav"), track("03.ogg"))
        onMain { player.playTracks(songs, 0) }
        waitFor { player.state.value.isPlaying }
        // At 4x the 2 s file ends every ~0.5 s: it must keep coming back to the first song.
        Thread.sleep(2_000)
        assertTrue(player.state.value.isPlaying)
        assertEquals(0, player.state.value.queue.index)
    }

    @Test
    fun restoreLoadsPausedAtThePosition() {
        val songs = listOf(track("Album/01.mp3"), track("Album/02.flac"))
        onMain { player.restore(songs, 1, 1_500, shuffle = true, repeat = Repeat.All) }
        waitFor { engine.state.value.source == songs[1].uri }
        Thread.sleep(200)
        assertFalse(player.state.value.isPlaying)
        assertEquals(1, player.state.value.queue.index)
        assertTrue(player.state.value.queue.shuffle)
        assertEquals(Repeat.All, player.state.value.queue.repeat)
        assertEquals(1_500L, engine.positionMs)
    }

    @Test
    fun pauseAndResume() {
        onMain { player.playTracks(listOf(track("Album/02.flac")), 0) }
        waitFor { player.state.value.isPlaying }
        onMain { player.togglePlayPause() }
        waitFor { !player.state.value.isPlaying }
        onMain { player.togglePlayPause() }
        waitFor { player.state.value.isPlaying || engine.state.value.source == null }
    }
}
