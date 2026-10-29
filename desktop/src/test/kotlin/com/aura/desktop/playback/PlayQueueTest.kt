package com.aura.desktop.playback

import com.aura.music.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PlayQueueTest {

    private fun track(id: Long) = Track(
        id = id, uri = "C:/Music/$id.mp3", title = "Song $id", artist = "A", artistId = 1, album = "B", albumId = 1,
        albumArtist = null, durationMs = 1000, trackNumber = id.toInt(), discNumber = 1, year = null, dateAddedSec = 0,
        genre = null, folder = "C:/Music/", displayName = "$id.mp3", mimeType = "audio/mpeg", sizeBytes = 1, bitrate = null,
    )

    private val songs = (1L..5L).map(::track)
    private fun PlayQueue.ids() = items.map { it.track.id }

    @Test
    fun playStartsAtTheChosenSong() {
        val q = PlayQueue().play(songs, 2)
        assertEquals(3L, q.current!!.track.id)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), q.ids())
    }

    @Test
    fun nextStopsAtTheEndUnlessRepeating() {
        val last = PlayQueue().play(songs, 4)
        assertNull(last.next(auto = true))
        assertEquals(1L, last.withRepeat(Repeat.All).next(auto = true)!!.current!!.track.id)
    }

    @Test
    fun repeatOneReplaysOnlyWhenTheSongEndsByItself() {
        val q = PlayQueue().play(songs, 1).withRepeat(Repeat.One)
        assertEquals(2L, q.next(auto = true)!!.current!!.track.id)
        assertEquals(3L, q.next(auto = false)!!.current!!.track.id)
    }

    @Test
    fun previousStaysAtTheFirstSongWithoutRepeat() {
        val q = PlayQueue().play(songs, 0)
        assertEquals(0, q.previous().index)
        assertEquals(4, q.withRepeat(Repeat.All).previous().index)
    }

    @Test
    fun shuffleKeepsTheCurrentSongFirstAndRestoresTheOrder() {
        val q = PlayQueue().play(songs, 2).withShuffle(true, Random(7))
        assertEquals(3L, q.current!!.track.id)
        assertEquals(0, q.index)
        assertEquals(songs.map { it.id }.toSet(), q.ids().toSet())
        val back = q.next(auto = false)!!.withShuffle(false)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), back.ids())
        assertEquals(back.current, q.next(auto = false)!!.current)
    }

    @Test
    fun songsAddedWhileShuffledSurviveTurningShuffleOff() {
        val q = PlayQueue().play(songs.take(3), 0).withShuffle(true, Random(1)).addLast(listOf(track(9)))
        assertEquals(listOf(1L, 2L, 3L, 9L), q.withShuffle(false).ids())
    }

    @Test
    fun playNextGoesRightAfterTheCurrentSong() {
        val q = PlayQueue().play(songs, 1).playNext(listOf(track(9), track(8)))
        assertEquals(listOf(1L, 2L, 9L, 8L, 3L, 4L, 5L), q.ids())
        assertEquals(2L, q.current!!.track.id)
    }

    @Test
    fun theSameSongCanBeQueuedTwice() {
        val q = PlayQueue().play(songs.take(1), 0).addLast(songs.take(1))
        assertEquals(2, q.items.map { it.uid }.distinct().size)
        assertEquals(1, q.removeAt(1).items.size)
        assertEquals(q.items[0].uid, q.removeAt(1).current!!.uid)
    }

    @Test
    fun removingKeepsTheRightCurrentSong() {
        val q = PlayQueue().play(songs, 2)
        assertEquals(3L, q.removeAt(0).current!!.track.id)
        assertEquals(4L, q.removeAt(2).current!!.track.id)
        assertEquals(3L, q.removeAt(4).current!!.track.id)
        assertEquals(-1, PlayQueue().play(songs.take(1), 0).removeAt(0).index)
    }

    @Test
    fun movingFollowsTheCurrentSong() {
        val q = PlayQueue().play(songs, 2)
        val moved = q.move(2, 4)
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), moved.ids())
        assertEquals(3L, moved.current!!.track.id)
        val before = q.move(4, 0)
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), before.ids())
        assertEquals(3L, before.current!!.track.id)
    }

    @Test
    fun clearUpcomingKeepsHistoryAndCurrent() {
        val q = PlayQueue().play(songs, 1).clearUpcoming()
        assertEquals(listOf(1L, 2L), q.ids())
        assertEquals(2L, q.current!!.track.id)
    }
}
