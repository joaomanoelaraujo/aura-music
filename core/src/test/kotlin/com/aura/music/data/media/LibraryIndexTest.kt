package com.aura.music.data.media

import com.aura.music.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexTest {

    private val index = LibraryIndex(
        listOf(
            track(1, title = "Intro", albumId = 10, album = "First", trackNumber = 1, discNumber = 1, genre = "Jazz"),
            track(2, title = "Bonus", albumId = 10, album = "First", trackNumber = 1, discNumber = 2, genre = "Jazz"),
            track(3, title = "Closer", albumId = 10, album = "First", trackNumber = 2, discNumber = 1),
            track(4, title = "Solo", artist = "Other", artistId = 2, albumId = 20, album = "Second", year = 2024, dateAddedSec = 999, folder = "Downloads/"),
            track(5, title = "Feature", artist = "Guest", artistId = 3, albumId = 10, album = "First", albumArtist = "Artist", trackNumber = 3),
        ),
    )

    @Test
    fun `album tracks follow disc then track number`() {
        assertEquals(listOf(1L, 3L, 5L, 2L), index.albumTracks(10).map { it.id })
    }

    @Test
    fun `album summary aggregates its tracks and prefers the album artist`() {
        val album = index.album(10)!!
        assertEquals(4, album.trackCount)
        assertEquals(4 * 180_000L, album.durationMs)
        assertEquals("Artist", album.artist)
    }

    @Test
    fun `artists count their distinct albums`() {
        val artist = index.artist(1)!!
        assertEquals(3, artist.trackCount)
        assertEquals(1, artist.albumCount)
        assertEquals(listOf(10L), index.artistAlbums(1).map { it.id })
    }

    @Test
    fun `genres ignore tracks without genre metadata`() {
        assertEquals(listOf("Jazz"), index.genres.map { it.name })
        assertEquals(2, index.genreTracks("Jazz").size)
    }

    @Test
    fun `folders group by relative path with a readable name`() {
        assertEquals(setOf("Music", "Downloads"), index.folders.map { it.name }.toSet())
        assertEquals(listOf(4L), index.folderTracks("Downloads/").map { it.id })
    }

    @Test
    fun `recently added albums are ordered by newest file`() {
        assertEquals(20L, index.recentlyAddedAlbums(1).single().id)
    }

    @Test
    fun `lookups by missing ids are safe`() {
        assertNull(index.track(404))
        assertTrue(index.tracks(listOf(404, 1)).map { it.id } == listOf(1L))
        assertTrue(index.albumTracks(404).isEmpty())
    }

    @Test
    fun `folder name falls back to the path`() {
        assertEquals("Music", LibraryIndex.folderName("Music/"))
        assertEquals("Albums", LibraryIndex.folderName("Music/Albums/"))
        assertEquals("", LibraryIndex.folderName(""))
    }
}
