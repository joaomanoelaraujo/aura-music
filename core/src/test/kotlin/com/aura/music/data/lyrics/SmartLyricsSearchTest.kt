package com.aura.music.data.lyrics

import com.aura.music.data.lyrics.online.LrclibClient
import com.aura.music.data.lyrics.online.LrclibRecord
import com.aura.music.data.lyrics.online.LyricsLookup
import com.aura.music.data.lyrics.online.LyricsMatcher
import com.aura.music.data.lyrics.online.LyricsQuery
import com.aura.music.data.lyrics.online.LyricsSource
import com.aura.music.data.lyrics.online.SongGuess
import com.aura.music.data.lyrics.online.SongNames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SmartLyricsSearchTest {

    @Test
    fun `upload noise is removed from titles, meaningful parts are kept`() {
        assertEquals("Tempo Perdido", SongNames.clean("Tempo Perdido [OFFICIAL VIDEO]"))
        assertEquals("Tempo Perdido", SongNames.clean("Tempo Perdido (Clipe Oficial) [HD]"))
        assertEquals("Tempo Perdido", SongNames.clean("Tempo Perdido (Lyric Video)"))
        assertEquals("Tempo Perdido", SongNames.clean("Tempo_Perdido_(Audio).mp3"))
        assertEquals("Tempo Perdido", SongNames.clean("Tempo Perdido - Official Music Video"))
        assertEquals("Faroeste Caboclo", SongNames.clean("Faroeste Caboclo (Remastered 2015)"))
        assertEquals("Bohemian Rhapsody", SongNames.clean("Bohemian Rhapsody ft. Someone"))
        assertEquals("kept: part of the name", "Pais e Filhos (Parte 2)", SongNames.clean("Pais e Filhos (Parte 2)"))
        assertEquals("kept: a different recording", "Tempo Perdido (Ao Vivo)", SongNames.clean("Tempo Perdido (Ao Vivo)"))
        assertEquals("Queen", SongNames.cleanArtist("QueenVEVO"))
        assertEquals("Legião Urbana", SongNames.cleanArtist("Legião Urbana - Topic"))
        assertNull(SongNames.cleanArtist("<unknown>"))
    }

    @Test
    fun `titles with the band in them are read both ways, guided by the artist tag`() {
        val noTag = SongNames.guesses("TEMPO PERDIDO - LEGIÃO URBANA [OFFICIAL VIDEO]", null)
        assertTrue(SongGuess("LEGIÃO URBANA", "TEMPO PERDIDO") in noTag)
        assertTrue(SongGuess("TEMPO PERDIDO", "LEGIÃO URBANA") in noTag)

        val tagged = SongNames.guesses("Tempo Perdido - Legião Urbana (Official Video)", "Legião Urbana")
        assertEquals(SongGuess("Tempo Perdido", "Legião Urbana"), tagged.first())

        val numbered = SongNames.guesses("03 - Queen - Bohemian Rhapsody (Official Video Remastered)", "<unknown>")
        assertEquals(SongGuess("Bohemian Rhapsody", "Queen"), numbered.first())

        assertEquals(SongGuess("Ainda é Cedo", "Legião Urbana"), SongNames.guesses("Ainda é Cedo", "Legião Urbana").first())
    }

    private fun record(title: String, artist: String, seconds: Double, id: Long = 1) =
        LrclibRecord(id, title, artist, null, seconds, false, "letra", "[00:01.00] letra")

    @Test
    fun `titles match by their words, not their exact text`() {
        assertTrue(LyricsMatcher.sameTitle("Tempo Perdido", "TEMPO PERDIDO (Official Video)"))
        assertTrue(LyricsMatcher.sameTitle("Bohemian Rhapsody", "Bohemian Rhapsody - Remastered 2011"))
        assertTrue(LyricsMatcher.sameTitle("Aguas de Marco", "Águas de Março"))
        assertFalse(LyricsMatcher.sameTitle("Tempo Perdido", "Tempo"))
        assertFalse(LyricsMatcher.sameTitle("Love", "Love Story"))
        assertFalse(LyricsMatcher.sameTitle("Tempo Perdido", "Perdido no Tempo Errado de Novo"))
    }

    @Test
    fun `without an artist only a matching duration is trusted`() {
        val query = LyricsQuery("Tempo Perdido", null, null, 302_000)
        assertTrue(LyricsMatcher.isSameSong(query, record("Tempo Perdido", "Legião Urbana", 302.1)))
        assertFalse(LyricsMatcher.isSameSong(query, record("Tempo Perdido", "Legião Urbana", 236.8)))
        assertFalse(LyricsMatcher.isSameSong(query.copy(durationMs = 0), record("Tempo Perdido", "Legião Urbana", 302.0)))
    }

    /** A stand-in for LRCLIB that only answers the right question, and counts the questions. */
    private class FakeLrclib(private val records: List<LrclibRecord>) : LyricsSource {
        var requests = 0
        override suspend fun get(query: LyricsQuery): LrclibRecord? {
            requests++
            return records.firstOrNull { it.trackName == query.title && it.artistName == query.artist }
        }

        override suspend fun search(text: String): List<LrclibRecord> {
            requests++
            val words = text.lowercase().split(' ')
            return records.filter { r -> words.all { w -> "${r.artistName} ${r.trackName}".lowercase().contains(w) } }
        }

        override suspend fun searchTitle(title: String, artist: String?): List<LrclibRecord> {
            requests++
            return records.filter { it.trackName.equals(title, ignoreCase = true) }
        }
    }

    private val catalog = listOf(
        record("Tempo Perdido", "Legião Urbana", 302.1, id = 10),
        record("Tempo Perdido", "Legião Urbana", 236.8, id = 11),
        record("Bohemian Rhapsody", "Queen", 355.0, id = 20),
    )

    @Test
    fun `messy names are found by trying the likely readings`() = runBlocking {
        val lrclib = FakeLrclib(catalog)
        // "SONG - BAND [OFFICIAL VIDEO]" with no artist tag.
        val found = LyricsLookup.find("TEMPO PERDIDO - LEGIÃO URBANA [OFFICIAL VIDEO]", null, null, 302_500, lrclib)
        assertEquals(10L, found?.id)
        assertTrue("a handful of requests at most: ${lrclib.requests}", lrclib.requests <= 7)

        assertEquals(20L, LyricsLookup.find("03 - Queen - Bohemian Rhapsody (Official Video Remastered)", "<unknown>", null, 354_000, FakeLrclib(catalog))?.id)
        assertEquals("title only, saved by the duration", 10L, LyricsLookup.find("Tempo Perdido (Lyric Video)", null, null, 301_900, FakeLrclib(catalog))?.id)
        assertNull("wrong duration: better nothing than another song", LyricsLookup.find("Tempo Perdido", null, null, 200_000, FakeLrclib(catalog)))
    }

    /** Against the real LRCLIB; runs only with AURA_LRCLIB_LIVE set (needs internet). */
    @Test
    fun `live lrclib finds songs with messy names`() = runBlocking {
        assumeTrue(System.getenv("AURA_LRCLIB_LIVE") != null)
        val client = LrclibClient()
        val cases = listOf(
            Triple("TEMPO PERDIDO - LEGIÃO URBANA [OFFICIAL VIDEO]", null, 302_000L),
            Triple("Queen - Bohemian Rhapsody (Official Video Remastered)", "QueenVEVO", 355_000L),
            Triple("Elis Regina - Águas de Março (Áudio Oficial)", null, 215_000L),
        )
        for ((title, artist, duration) in cases) {
            val found = LyricsLookup.find(title, artist, null, duration, client)
            assertNotNull("not found: $title", found)
            assertTrue("$title -> ${found!!.trackName}", found.syncedLyrics != null || found.plainLyrics != null)
        }
    }
}
