package com.aura.music.data

import com.aura.music.data.media.AudioSignals
import com.aura.music.data.media.LibraryAssembler
import com.aura.music.data.media.ScannedAudio
import com.aura.music.data.media.LibraryRules
import com.aura.music.data.media.MetadataOverride
import com.aura.music.data.metadata.MbArtistCredit
import com.aura.music.data.metadata.MbRelease
import com.aura.music.data.metadata.MbReleaseGroup
import com.aura.music.data.metadata.MetadataMatcher
import com.aura.music.data.metadata.MusicBrainzClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MetadataTest {
    private fun release(title: String, artist: String, score: Int = 100, id: String = "r1") =
        MbRelease(id, title, score, listOf(MbArtistCredit(artist)), MbReleaseGroup("g1"))

    @Test
    fun `an album is matched only when title and artist clearly fit`() {
        val found = listOf(release("Elis & Tom (Remastered)", "Elis Regina & Tom Jobim"))
        assertNotNull(MetadataMatcher.album("Elis & Tom", "Elis Regina", found))
        assertNull("other album", MetadataMatcher.album("Falso Brilhante", "Elis Regina", found))
        assertNull("other artist", MetadataMatcher.album("Elis & Tom", "Caetano Veloso", found))
        assertNull("weak score", MetadataMatcher.album("Elis & Tom", "Elis Regina", listOf(release("Elis & Tom", "Elis Regina", score = 60))))
        assertNotNull("no artist tag", MetadataMatcher.album("Elis & Tom", null, found))
    }

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `the client reads MusicBrainz answers and follows the cover to the release group`() = runBlocking {
        val cover = ByteArray(5_000) { it.toByte() }
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/ws/2/release") { ex ->
            assertTrue("identifies itself", ex.requestHeaders.getFirst("User-Agent").startsWith("AURA Music"))
            val body = """{"releases":[{"id":"r1","score":100,"title":"Elis & Tom","artist-credit":[{"name":"Elis Regina","joinphrase":" & "},{"name":"Tom Jobim"}],"release-group":{"id":"g1"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        http.createContext("/ws/2/recording") { ex ->
            val body = """{"recordings":[{"id":"x","score":98,"title":"Sinal fechado","length":182346,"artist-credit":[{"name":"Paulinho da Viola"}],"releases":[{"id":"r9","title":"Foi um rio que passou"}]}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        // The release itself has no cover; its release group has.
        http.createContext("/release/r1/front-500") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        http.createContext("/release-group/g1/front-500") { ex -> ex.sendResponseHeaders(200, cover.size.toLong()); ex.responseBody.use { it.write(cover) } }
        http.start()
        server = http
        val base = "http://127.0.0.1:${http.address.port}"
        val client = MusicBrainzClient(baseUrl = base, coversUrl = base)

        val releases = client.searchReleases("Elis & Tom", "Elis Regina")
        assertEquals("Elis Regina & Tom Jobim", releases.single().artist)
        assertArrayEquals(cover, client.frontCover(MetadataMatcher.album("Elis & Tom", "Elis Regina", releases)!!))
        val recording = client.searchRecordings("Paulinho da Viola Sinal Fechado").single()
        assertEquals("Sinal fechado" to 182_346L, recording.title to recording.length)
        assertEquals("Foi um rio que passou", recording.releases.single().title)
    }

    @Test
    fun `corrected details replace the file's tags in the library`() {
        val t = com.aura.music.track(7, title = "01 - elis - aguas", artist = null, album = "Elis & Tom", folder = "Music/")
        val scanned = ScannedAudio(t, AudioSignals("Music/", "01 - elis - aguas.mp3", "audio/mpeg", t.durationMs, t.title, null, "Elis & Tom", null, null, null, 0, null, true))
        val index = LibraryAssembler.assemble(listOf(scanned), LibraryRules(metadata = mapOf(7L to MetadataOverride("Águas de Março", "Elis Regina"))))
        val track = index.tracks.single()
        assertEquals("Águas de Março", track.title)
        assertEquals("Elis Regina", track.artist)
    }

    /** Against the real MusicBrainz and Cover Art Archive; runs only with AURA_MUSICBRAINZ_LIVE set. */
    @Test
    fun `live musicbrainz finds a known album cover`() = runBlocking {
        assumeTrue(System.getenv("AURA_MUSICBRAINZ_LIVE") != null)
        val client = MusicBrainzClient()
        val release = MetadataMatcher.album("Elis & Tom", "Elis Regina", client.searchReleases("Elis & Tom", "Elis Regina"))
        assertNotNull(release)
        val bytes = client.frontCover(release!!)
        assertTrue("got a JPEG", bytes != null && bytes.size > 10_000 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
    }
}
