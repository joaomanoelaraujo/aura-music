package com.aura.music.data.media

import com.aura.music.core.model.AudioKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioClassifierTest {

    private fun signals(
        path: String = "Music/",
        name: String = "song.mp3",
        mime: String? = "audio/mpeg",
        durationMs: Long = 210_000,
        title: String? = "Song",
        artist: String? = null,
        album: String? = null,
        year: Int? = null,
        trackNumber: Int = 0,
        bitrate: Int? = null,
        genre: String? = null,
        isMusic: Boolean = true,
        isRecording: Boolean = false,
        isPodcast: Boolean = false,
        isSystemSound: Boolean = false,
    ) = AudioSignals(
        relativePath = path,
        displayName = name,
        mimeType = mime,
        durationMs = durationMs,
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        bitrate = bitrate,
        isMusic = isMusic,
        isPodcast = isPodcast,
        isRecording = isRecording,
        isSystemSound = isSystemSound,
    )

    private val waAudio = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/"
    private val waVoice = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202544/"

    @Test
    fun `whatsapp voice notes are voice messages even when MediaStore flags them as music`() {
        val kind = AudioClassifier.classify(signals(path = waVoice, name = "PTT-20250101-WA0003.opus", mime = "audio/ogg", durationMs = 9_000, isMusic = true))
        assertEquals(AudioKind.WhatsAppVoice, kind)
    }

    @Test
    fun `long voice notes stay voice messages - duration alone never decides`() {
        val kind = AudioClassifier.classify(signals(path = waVoice, name = "PTT-20250101-WA0010.opus", mime = "audio/ogg", durationMs = 12 * 60_000))
        assertEquals(AudioKind.WhatsAppVoice, kind)
    }

    @Test
    fun `a tagged song forwarded through whatsapp is still music`() {
        val kind = AudioClassifier.classify(
            signals(
                path = waAudio, name = "AUD-20250101-WA0001.mp3", artist = "Henrique Mendonça", album = "Ao Vivo",
                year = 2019, trackNumber = 3, bitrate = 320_000, title = "Saudade",
            ),
        )
        assertEquals(AudioKind.Music, kind)
    }

    @Test
    fun `an untagged audio file received on whatsapp is a whatsapp audio`() {
        val kind = AudioClassifier.classify(signals(path = waAudio, name = "AUD-20250101-WA0002.m4a", mime = "audio/mp4", title = "AUD-20250101-WA0002"))
        assertEquals(AudioKind.WhatsAppAudio, kind)
    }

    @Test
    fun `untagged opus in the whatsapp audio folder is treated as voice`() {
        val kind = AudioClassifier.classify(signals(path = waAudio, name = "AUD-20250101-WA0004.opus", mime = "audio/ogg", title = "AUD-20250101-WA0004"))
        assertEquals(AudioKind.WhatsAppVoice, kind)
    }

    @Test
    fun `phone recordings are recordings`() {
        assertEquals(AudioKind.Recording, AudioClassifier.classify(signals(path = "Recordings/", name = "Recording_001.m4a", mime = "audio/mp4")))
        assertEquals(AudioKind.Recording, AudioClassifier.classify(signals(path = "Music/", name = "voz 12.amr", mime = "audio/amr")))
        assertEquals(AudioKind.Recording, AudioClassifier.classify(signals(path = "Download/", name = "x.m4a", isRecording = true, isMusic = false)))
    }

    @Test
    fun `a short tagged song in the music folder is still music`() {
        val kind = AudioClassifier.classify(signals(durationMs = 18_000, artist = "Vinheta", album = "Abertura", trackNumber = 1))
        assertEquals(AudioKind.Music, kind)
    }

    @Test
    fun `a tagged album that lives in a recordings folder is music`() {
        val kind = AudioClassifier.classify(
            signals(path = "Recordings/", name = "01 Intro.mp3", artist = "Banda", album = "Disco", year = 2001, trackNumber = 1, bitrate = 256_000, genre = "Rock", title = "Intro"),
        )
        assertEquals(AudioKind.Music, kind)
    }

    @Test
    fun `podcasts are separated`() {
        assertEquals(AudioKind.Podcast, AudioClassifier.classify(signals(path = "Podcasts/", isPodcast = true)))
        assertEquals(AudioKind.Podcast, AudioClassifier.classify(signals(genre = "Podcast")))
    }

    @Test
    fun `ringtones and notification sounds are dropped`() {
        assertNull(AudioClassifier.classify(signals(path = "Notifications/", isMusic = false, isSystemSound = true)))
    }

    @Test
    fun `non music files without hints are other audio`() {
        assertEquals(AudioKind.Other, AudioClassifier.classify(signals(path = "Download/", name = "aula.mp3", isMusic = false, title = "aula")))
    }

    @Test
    fun `music folder names that merely start with call are not recordings`() {
        val kind = AudioClassifier.classify(signals(path = "Music/Call of the Wild/", artist = "X", album = "Call of the Wild"))
        assertEquals(AudioKind.Music, kind)
    }

    @Test
    fun `untagged files report no tags`() {
        assertEquals(false, AudioClassifier.hasTags(signals(path = "Music/Mix/", album = "Mix")))
        assertEquals(true, AudioClassifier.hasTags(signals(artist = "A")))
    }
}
