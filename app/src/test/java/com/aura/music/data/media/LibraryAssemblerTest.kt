package com.aura.music.data.media

import com.aura.music.core.model.AudioKind
import com.aura.music.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAssemblerTest {

    private fun scanned(id: Long, folder: String, name: String, artist: String? = null, album: String? = null, mime: String = "audio/mpeg", isMusic: Boolean = true): ScannedAudio {
        val t = track(id, title = name.substringBeforeLast('.'), artist = artist, album = album, folder = folder).copy(displayName = name, mimeType = mime)
        return ScannedAudio(
            t,
            AudioSignals(folder, name, mime, t.durationMs, t.title, artist, album, null, null, null, 0, null, isMusic),
        )
    }

    private val song = scanned(1, "Music/", "a.mp3", artist = "Artist", album = "Album")
    private val voice = scanned(2, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/", "PTT-20250101-WA0001.opus", mime = "audio/ogg")
    private val untagged = scanned(3, "Music/Mix/", "b.mp3", album = "Mix")
    private val recording = scanned(4, "Recordings/", "Recording_1.m4a", mime = "audio/mp4")
    private val all = listOf(song, voice, untagged, recording)

    @Test
    fun `music and non-music audio end up in separate lists`() {
        val index = LibraryAssembler.assemble(all, LibraryRules())
        assertEquals(setOf(1L, 3L), index.tracks.map { it.id }.toSet())
        assertEquals(setOf(2L, 4L), index.audios.map { it.id }.toSet())
        assertEquals(AudioKind.WhatsAppVoice, index.track(2)?.kind)
    }

    @Test
    fun `per-file override beats folder rule which beats automatic classification`() {
        val rules = LibraryRules(
            overrides = mapOf(4L to ClassificationOverride(AudioKind.Music, hidden = false)),
            folderRules = listOf(FolderRule("Android/media/com.whatsapp/", AudioKind.Music, hidden = false), FolderRule("Recordings/", AudioKind.Other, false)),
        )
        val index = LibraryAssembler.assemble(all, rules)
        assertTrue(index.tracks.any { it.id == 2L })
        assertTrue(index.tracks.any { it.id == 4L })
    }

    @Test
    fun `hidden files are listed for restore but still resolvable`() {
        val index = LibraryAssembler.assemble(all, LibraryRules(overrides = mapOf(1L to ClassificationOverride(null, hidden = true))))
        assertTrue(index.tracks.none { it.id == 1L })
        assertEquals(listOf(1L), index.hidden.map { it.id })
        assertNotNull(index.track(1))
    }

    @Test
    fun `excluded folders and their subfolders are skipped`() {
        val index = LibraryAssembler.assemble(all, LibraryRules(excludedFolders = setOf("Music/")))
        assertTrue(index.tracks.none { it.id == 1L || it.id == 3L })
    }

    @Test
    fun `untagged music can be moved out of the library`() {
        val index = LibraryAssembler.assemble(all, LibraryRules(showUntagged = false))
        assertTrue(index.tracks.none { it.id == 3L })
        assertEquals(AudioKind.Other, index.audios.first { it.id == 3L }.kind)
    }

    @Test
    fun `whatsapp audio can be hidden entirely without touching music`() {
        val index = LibraryAssembler.assemble(all, LibraryRules(hideWhatsApp = true))
        assertTrue(index.audios.none { it.kind.isWhatsApp })
        assertTrue(index.tracks.any { it.id == 1L })
    }
}
