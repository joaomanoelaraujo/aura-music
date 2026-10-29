package com.aura.desktop.library

import com.aura.desktop.TestMedia
import com.aura.music.data.media.LibraryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LibraryScannerTest {

    @get:Rule val temp = TemporaryFolder()

    private fun scanner() = LibraryScanner(ArtworkStore(temp.newFolder().toPath()))

    @Test
    fun readsTagsOfEveryFormatAndSkipsOtherFiles() {
        val files = scanner().scan(listOf(TestMedia.root), emptyMap()).associateBy { it.path.substringAfterLast(java.io.File.separatorChar) }
        assertEquals(setOf("01.mp3", "02.flac", "03.ogg", "04.m4a", "05.opus", "gravação sem tags.wav"), files.keys)
        val mp3 = files.getValue("01.mp3")
        assertEquals("Noite de Verão", mp3.title)
        assertEquals("Banda Exemplo", mp3.artist)
        assertEquals("Álbum de Teste", mp3.album)
        assertEquals(1, mp3.trackNumber)
        assertEquals(2024, mp3.year)
        assertTrue(mp3.hasEmbeddedCover)
        assertEquals("Água Clara", files.getValue("02.flac").title)
        assertEquals("Opus Teste", files.getValue("05.opus").title)
        assertEquals("Maré", files.getValue("04.m4a").title)
        assertTrue(files.getValue("04.m4a").hasEmbeddedCover)
        val wav = files.getValue("gravação sem tags.wav")
        assertNull(wav.title)
        assertEquals(2000.0, wav.durationMs.toDouble(), 50.0)
    }

    @Test
    fun buildsTheSharedLibraryIndex() {
        val tracks = scanner().scan(listOf(TestMedia.root), emptyMap()).map(LibraryMapper::toTrack)
        val index = LibraryIndex(tracks)
        assertEquals(6, index.tracks.size)
        val album = index.albums.single { it.title == "Álbum de Teste" }
        assertEquals(2, album.trackCount)
        assertEquals(listOf("Noite de Verão", "Água Clara"), index.albumTracks(album.id).map { it.title })
        // No tags: the file name becomes the title.
        assertTrue(index.tracks.any { it.title == "gravação sem tags" && !it.hasTags })
        assertTrue(index.folders.any { it.name == "Album" })
    }

    @Test
    fun keepsEmbeddedCoversPerAlbum() {
        val artDir = temp.newFolder("covers").toPath()
        val store = ArtworkStore(artDir)
        val tracks = LibraryScanner(store).scan(listOf(TestMedia.root), emptyMap()).map(LibraryMapper::toTrack)
        val withCover = tracks.first { it.title == "Noite de Verão" }
        assertNotNull(store.fileFor(withCover))
        // Same album and folder as a song that has a cover: shares it.
        assertNotNull(store.fileFor(tracks.first { it.title == "Estrada" }))
        // No album and no cover anywhere: placeholder.
        assertNull(store.fileFor(tracks.first { it.title == "Opus Teste" }))
        // One file per album, already shrunk.
        Files.list(artDir).use { assertEquals(2, it.count()) }
    }

    @Test
    fun unchangedFilesComeFromThePreviousScan() {
        val first = scanner().scan(listOf(TestMedia.root), emptyMap())
        val previous = first.associateBy { it.path }
        val second = scanner().scan(listOf(TestMedia.root), previous)
        second.forEach { assertSame(previous.getValue(it.path), it) }
    }

    @Test
    fun missingFoldersAreNotAnError() {
        assertTrue(scanner().scan(listOf(temp.root.toPath().resolve("nao-existe")), emptyMap()).isEmpty())
    }

    @Test
    fun idsAreStableAndDistinct() {
        assertEquals(LibraryMapper.trackId("C:\\Música\\a.mp3"), LibraryMapper.trackId("C:\\Música\\a.mp3"))
        assertFalse(LibraryMapper.trackId("C:\\Música\\a.mp3") == LibraryMapper.trackId("C:\\Música\\b.mp3"))
    }
}
