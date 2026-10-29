package com.aura.music.data.lyrics

import com.aura.music.data.lyrics.online.LrclibClient
import com.aura.music.data.lyrics.online.LrclibRecord
import com.aura.music.data.lyrics.online.LyricsMatcher
import com.aura.music.data.lyrics.online.LyricsQuery
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OnlineLyricsTest {

    private val song = LyricsQuery("Águas de Março", "Elis Regina", "Elis & Tom", 212_000)

    private fun record(
        title: String = "Águas De Março",
        artist: String = "Elis Regina",
        seconds: Double = 214.0,
        synced: String? = "[00:01.00] É pau, é pedra",
        plain: String? = "É pau, é pedra",
    ) = LrclibRecord(1, title, artist, "Elis & Tom", seconds, false, plain, synced)

    @Test
    fun `the same song is recognized despite accents, case and release details`() {
        assertTrue(LyricsMatcher.isSameSong(song, record()))
        assertTrue(LyricsMatcher.isSameSong(song, record(title = "Aguas de Marco (Remastered 2004)")))
        assertTrue(LyricsMatcher.isSameSong(song, record(title = "Águas de Março - Ao Vivo")))
        assertTrue("artist credited with others", LyricsMatcher.isSameSong(song, record(artist = "Elis Regina & Tom Jobim")))
    }

    @Test
    fun `other songs, other artists and other durations are rejected`() {
        assertFalse(LyricsMatcher.isSameSong(song, record(title = "Como Nossos Pais")))
        assertFalse(LyricsMatcher.isSameSong(song, record(artist = "Tom Zé")))
        assertFalse("a 2:40 edit is another recording", LyricsMatcher.isSameSong(song, record(seconds = 161.0)))
        assertFalse("nothing to show", LyricsMatcher.isSameSong(song, record(synced = null, plain = null)))
    }

    @Test
    fun `songs without an artist tag still match by title and duration`() {
        val untagged = song.copy(artist = null)
        assertTrue(LyricsMatcher.isSameSong(untagged, record()))
        assertFalse(LyricsMatcher.isSameSong(untagged, record(seconds = 180.0)))
    }

    @Test
    fun `among matches the synced one closest in duration wins`() {
        val plainOnly = record(seconds = 212.0, synced = null).copy(id = 2)
        val syncedFar = record(seconds = 214.5).copy(id = 3)
        val syncedNear = record(seconds = 212.4).copy(id = 4)
        val wrong = record(title = "Outra").copy(id = 5)
        assertEquals(4L, LyricsMatcher.best(song, listOf(plainOnly, syncedFar, wrong, syncedNear))?.id)
        assertEquals("synced beats a closer plain one", 3L, LyricsMatcher.best(song, listOf(plainOnly, syncedFar))?.id)
        assertNull(LyricsMatcher.best(song, listOf(wrong)))
    }

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    /** A stand-in for LRCLIB with one song, answering like the real service. */
    private fun fakeLrclib(): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val elis = """{"id":7,"trackName":"Águas De Março","artistName":"Elis Regina","albumName":"Elis & Tom","duration":214.0,
            |"instrumental":false,"plainLyrics":"É pau","syncedLyrics":"[00:01.00] É pau","extra":"ignored"}""".trimMargin()
        http.createContext("/api/get-cached") { exchange ->
            val params = exchange.requestURI.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val known = params["track_name"] == "Águas De Março" && params["artist_name"] == "Elis Regina"
            val body = (if (known) elis else """{"code":404,"name":"TrackNotFound"}""").toByteArray()
            assertNotNull("identifies itself", exchange.requestHeaders.getFirst("User-Agent"))
            exchange.sendResponseHeaders(if (known) 200 else 404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.createContext("/api/search") { exchange ->
            val body = "[$elis]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    @Test
    fun `the client reads records, treats unknown songs as null and searches`() = runBlocking {
        val client = LrclibClient(baseUrl = fakeLrclib())
        val found = client.get(song.copy(title = "Águas De Março"))
        assertEquals(7L, found?.id)
        assertEquals("[00:01.00] É pau", found?.bestText)
        assertNull(client.get(song.copy(title = "Inexistente")))
        assertEquals(listOf(7L), client.search("Elis Regina Águas de Março").map { it.id })
    }

    /** Against the real LRCLIB; runs only with AURA_LRCLIB_LIVE set (needs internet). */
    @Test
    fun `live lrclib finds a well known song`() = runBlocking {
        assumeTrue(System.getenv("AURA_LRCLIB_LIVE") != null)
        val client = LrclibClient()
        val record = client.get(song) ?: LyricsMatcher.best(song, client.search("Elis Regina Águas de Março"))
        assertNotNull(record)
        assertTrue(LyricsMatcher.isSameSong(song, record!!))
        assertFalse(LrcParser.parse(record.bestText!!).isEmpty)
    }
}
