package com.aura.music.data.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverParsingTest {

    private fun fixture(name: String) = javaClass.getResource("/discover/$name")!!.readText()

    @Test
    fun `links are classified without any network access`() {
        assertEquals(LinkKind.YouTube("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ"), LinkAnalyzer.analyze("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertTrue(LinkAnalyzer.analyze("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10") is LinkKind.YouTube)
        assertTrue(LinkAnalyzer.analyze("music.youtube.com/watch?v=dQw4w9WgXcQ") is LinkKind.YouTube)
        assertEquals(LinkKind.Spotify("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", "track", "4uLU6hMCjMI75M1A2tKUQC"), LinkAnalyzer.analyze("https://open.spotify.com/intl-pt/track/4uLU6hMCjMI75M1A2tKUQC?si=x"))
        assertTrue(LinkAnalyzer.analyze("spotify:album:1ATL5GLyefJaxhQzSPVrLX") is LinkKind.Spotify)
        assertEquals(LinkKind.ArchiveItem("GHS_20151212"), LinkAnalyzer.analyze("https://archive.org/details/GHS_20151212"))
        assertEquals(LinkKind.DirectFile("https://example.com/m%C3%BAsica.mp3", "música.mp3"), LinkAnalyzer.analyze("https://example.com/m%C3%BAsica.mp3"))
        assertTrue(LinkAnalyzer.analyze("https://example.com/page") is LinkKind.Unknown)
    }

    @Test
    fun `insecure and malformed links are rejected`() {
        assertEquals(LinkKind.Invalid(InvalidReason.InsecureHttp), LinkAnalyzer.analyze("http://example.com/a.mp3"))
        assertEquals(LinkKind.Invalid(InvalidReason.Unsupported), LinkAnalyzer.analyze("ftp://example.com/a.mp3"))
        assertEquals(LinkKind.Invalid(InvalidReason.Unsupported), LinkAnalyzer.analyze("https://youtube.com/watch?v=short"))
    }

    @Test
    fun `words are searched, links are analysed`() {
        assertFalse(LinkAnalyzer.looksLikeLink("henrique mendonça"))
        assertTrue(LinkAnalyzer.looksLikeLink("youtu.be/dQw4w9WgXcQ"))
        assertTrue(LinkAnalyzer.looksLikeLink("https://x.org/a.flac"))
    }

    @Test
    fun `search keeps only openly licensed results`() {
        val results = InternetArchive.parseSearch(fixture("ia_search.json"))
        assertEquals(3, results.size)
        assertTrue(results.all { it.license != null && it.source == SourceKind.InternetArchive && it.downloadable })
        assertEquals("CC BY-NC-ND 3.0", results.first().license)
    }

    @Test
    fun `item with a single original mp3 offers exactly that file`() {
        val detail = InternetArchive.parseItem("radio4all-115849", fixture("ia_item_mp3.json"))!!
        assertEquals(1, detail.tracks.size)
        val option = detail.tracks.single().options.single()
        assertEquals("MP3 VBR", option.formatLabel)
        assertTrue(option.original)
        assertEquals(69_603_317L, option.sizeBytes)
        assertTrue(option.url.startsWith("https://archive.org/download/radio4all-115849/07102023%20Chantil"))
    }

    @Test
    fun `album derived from wav originals groups every real format per track`() {
        val detail = InternetArchive.parseItem("GHS_20151212", fixture("ia_item_flac.json"))!!
        assertEquals("Domínio público", detail.item.license)
        val blackPeter = detail.tracks.first { it.title == "Black Peter" }
        assertEquals(13, blackPeter.trackNumber)
        assertEquals(listOf("WAV", "FLAC", "MP3 VBR"), blackPeter.options.map { it.formatLabel })
        assertTrue(blackPeter.options.first().original)
        assertEquals(1, detail.tracks.first().trackNumber)
    }

    @Test
    fun `items without audio produce no tracks`() {
        assertTrue(InternetArchive.parseItem("TrackOne_730", fixture("ia_item_no_audio.json"))!!.tracks.isEmpty())
    }

    @Test
    fun `license labels`() {
        assertEquals("CC BY-SA 4.0", InternetArchive.licenseLabel("https://creativecommons.org/licenses/by-sa/4.0/"))
        assertEquals("CC0", InternetArchive.licenseLabel("https://creativecommons.org/publicdomain/zero/1.0/"))
        assertNull(InternetArchive.licenseLabel("https://example.com/all-rights-reserved"))
    }

    @Test
    fun `content disposition file names`() {
        assertEquals("song.flac", Http.fileNameFromDisposition("attachment; filename=\"song.flac\""))
        assertEquals("música.mp3", Http.fileNameFromDisposition("attachment; filename*=UTF-8''música.mp3"))
    }
}
