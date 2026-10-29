package com.aura.desktop.lyrics

import com.aura.desktop.TestMedia
import com.aura.desktop.library.LibraryMapper
import com.aura.desktop.library.ScannedFile
import com.aura.music.core.model.Track
import com.aura.music.data.lyrics.online.LrclibRecord
import com.aura.music.data.lyrics.online.LyricsQuery
import com.aura.music.data.lyrics.online.LyricsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Invented lyrics only; the fake source answers like LRCLIB without touching the network. */
class LyricsServiceTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeSource(var record: LrclibRecord? = null, var fail: Boolean = false) : LyricsSource {
        var calls = 0
        override suspend fun get(query: LyricsQuery): LrclibRecord? = answer()
        override suspend fun search(text: String): List<LrclibRecord> = listOfNotNull(answer())
        override suspend fun searchTitle(title: String, artist: String?): List<LrclibRecord> = listOfNotNull(answer())
        private fun answer(): LrclibRecord? {
            calls++
            if (fail) throw IOException("offline")
            return record
        }
    }

    private fun track(path: Path, title: String = "Canção Inventada", artist: String? = "Banda Exemplo", durationMs: Long = 3000): Track =
        LibraryMapper.toTrack(ScannedFile(path = path.toString(), sizeBytes = 1, modifiedMs = 0, title = title, artist = artist, durationMs = durationMs))

    private fun copyOf(name: String, folder: Path = temp.newFolder().toPath()): Path =
        Files.copy(TestMedia.file(name), folder.resolve(Paths.get(name).fileName.toString()))

    @Test
    fun readsLyricsEmbeddedInTheFile() = runBlocking {
        val service = LyricsService(temp.newFolder().toPath(), FakeSource())
        val file = Paths.get(requireNotNull(javaClass.getResource("/lyrics-media/com-letra.ogg")).toURI())
        val loaded = service.local(track(file))!!
        assertEquals(LyricsOrigin.Embedded, loaded.origin)
        assertTrue(loaded.lyrics.synced)
        assertEquals(listOf("Primeira linha inventada", "Segunda linha do teste"), loaded.lyrics.lines.map { it.text })
        assertEquals(500L, loaded.lyrics.lines.first().timeMs)
    }

    @Test
    fun aSidecarLrcBeatsTheFileAndAChosenOneBeatsBoth() = runBlocking {
        val audio = copyOf("03.ogg")
        Files.writeString(audio.resolveSibling("03.lrc"), "[00:01.00]Linha do arquivo ao lado\n")
        val service = LyricsService(temp.newFolder().toPath(), FakeSource())
        val t = track(audio)
        assertEquals(LyricsOrigin.Sidecar, service.local(t)!!.origin)

        val mine = temp.newFile("minha.lrc").toPath().also { Files.writeString(it, "[00:00.20]Minha versão inventada\n") }
        assertTrue(service.import(t, mine))
        val chosen = service.local(t)!!
        assertEquals(LyricsOrigin.Custom, chosen.origin)
        assertEquals("Minha versão inventada", chosen.lyrics.lines.single().text)

        service.removeSaved(t)
        assertEquals(LyricsOrigin.Sidecar, service.local(t)!!.origin)
    }

    @Test
    fun importRejectsFilesWithoutLyrics() = runBlocking {
        val service = LyricsService(temp.newFolder().toPath(), FakeSource())
        val empty = temp.newFile("vazio.lrc").toPath().also { Files.writeString(it, "\n\n") }
        assertEquals(false, service.import(track(copyOf("03.ogg")), empty))
    }

    @Test
    fun onlineLyricsAreSavedForOfflineUse() = runBlocking {
        val source = FakeSource(LrclibRecord(id = 7, trackName = "Canção Inventada", artistName = "Banda Exemplo", duration = 3.0, syncedLyrics = "[00:00.10]Verso inventado\n"))
        val dir = temp.newFolder().toPath()
        val t = track(copyOf("03.ogg"))
        assertNull(LyricsService(dir, source).local(t))
        val fetched = LyricsService(dir, source).fetchOnline(t)!!
        assertEquals(LyricsOrigin.Online, fetched.origin)
        // A new service (the app restarted, offline): still there, no network needed.
        source.fail = true
        val again = LyricsService(dir, source).local(t)!!
        assertEquals("Verso inventado", again.lyrics.lines.single().text)
    }

    @Test
    fun aMissIsNotAskedAgainForAWhileButOfflineIsRetried() = runBlocking {
        val source = FakeSource(record = null)
        var now = 1_000L
        val service = LyricsService(temp.newFolder().toPath(), source, now = { now })
        val t = track(copyOf("03.ogg"))
        assertNull(service.fetchOnline(t))
        val calls = source.calls
        assertNull(service.fetchOnline(t))
        assertEquals(calls, source.calls)
        now += LyricsService.MISS_TTL_MS + 1
        service.fetchOnline(t)
        assertTrue(source.calls > calls)

        // Being offline is not a "not found": the next attempt asks again.
        val offline = FakeSource(fail = true)
        val other = LyricsService(temp.newFolder().toPath(), offline)
        assertNull(other.fetchOnline(t))
        val before = offline.calls
        other.fetchOnline(t)
        assertTrue(offline.calls > before)
    }
}
