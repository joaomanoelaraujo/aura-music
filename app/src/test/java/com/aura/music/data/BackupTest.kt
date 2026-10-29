package com.aura.music.data

import com.aura.music.data.backup.BackupCodec
import com.aura.music.data.backup.BackupFavorite
import com.aura.music.data.backup.BackupFile
import com.aura.music.data.backup.BackupLyrics
import com.aura.music.data.backup.BackupPlay
import com.aura.music.data.backup.BackupPlaylist
import com.aura.music.data.backup.SongResolver
import com.aura.music.data.backup.SongTable
import com.aura.music.data.local.PreferenceValues
import com.aura.music.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    private val oldPhone = listOf(
        track(11, title = "Águas de Março", artist = "Elis Regina", durationMs = 212_000),
        track(12, title = "Sinal Fechado", artist = "Paulinho da Viola", durationMs = 180_000),
        track(13, title = "Só no velho", artist = "Ninguém", durationMs = 100_000),
    )

    private fun backup(): BackupFile {
        val table = SongTable()
        val ids = oldPhone.map(table::indexOf)
        // Mentioning a song twice keeps one entry.
        table.indexOf(oldPhone[0])
        return BackupFile(
            createdAt = 1,
            playlists = listOf(BackupPlaylist("Domingo", 5, ids)),
            favorites = listOf(BackupFavorite(ids[1], 9)),
            lyrics = listOf(BackupLyrics(ids[0], "[00:01.00] É pau, é pedra", offsetMs = 300)),
            plays = listOf(BackupPlay(ids[0], 1_000), BackupPlay(ids[2], 2_000)),
            settings = PreferenceValues.encode(mapOf("theme_mode" to "Dark", "transition_seconds" to 6, "online_lyrics" to false, "atmosphere_intensity" to 0.8f, "excluded_folders" to setOf("B/", "A/"))),
        ).copy(songs = table.songs)
    }

    @Test
    fun `a backup survives the file and keeps each song once`() {
        val original = backup()
        assertEquals(3, original.songs.size)
        val back = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, back)
    }

    @Test
    fun `songs are found again on another phone by tags and duration, not by id`() {
        val newPhone = listOf(
            track(501, title = "Sinal fechado", artist = "Paulinho Da Viola", durationMs = 181_000),
            track(502, title = "Aguas de Marco", artist = "Elis Regina", durationMs = 212_500),
        )
        val resolver = SongResolver(backup(), newPhone)
        assertEquals(502L, resolver.track(0)?.id)
        assertEquals(501L, resolver.track(1)?.id)
        assertNull("not on the new phone", resolver.track(2))
        assertEquals(1, resolver.missing)
    }

    @Test
    fun `settings keep their types`() {
        val values = PreferenceValues.decode(backup().settings)
        assertEquals("Dark", values["theme_mode"])
        assertEquals(6, values["transition_seconds"])
        assertEquals(false, values["online_lyrics"])
        assertEquals(0.8f, values["atmosphere_intensity"])
        assertEquals(setOf("A/", "B/"), values["excluded_folders"])
    }

    @Test
    fun `other files and newer formats are refused`() {
        assertNull(BackupCodec.decode("not json"))
        assertNull(BackupCodec.decode("""{"some":"other app"}"""))
        assertNull(BackupCodec.decode("""{"format":99,"createdAt":1}"""))
    }
}
