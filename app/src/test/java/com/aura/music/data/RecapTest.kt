package com.aura.music.data

import com.aura.music.core.model.AudioKind
import com.aura.music.data.local.db.PlayEventEntity
import com.aura.music.data.local.db.SongAnalysisEntity
import com.aura.music.data.repository.RecapRepository
import com.aura.music.track
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 10).atZone(zone).toInstant().toEpochMilli()

    private val tracks = mapOf(
        1L to track(1, title = "A", artist = "Elis Regina", durationMs = 180_000),
        2L to track(2, title = "B", artist = "Elis Regina", durationMs = 240_000),
        3L to track(3, title = "C", artist = "Tom Zé", durationMs = 120_000),
        4L to track(4, title = "Áudio", artist = null, durationMs = 60_000).copy(kind = AudioKind.WhatsAppVoice),
    )

    @Test
    fun `counts, rankings, hours, busiest day and mood come out right`() {
        // 26/10/2026 is a Monday.
        val events = listOf(
            PlayEventEntity(trackId = 1, playedAt = at(26, 22)),
            PlayEventEntity(trackId = 1, playedAt = at(26, 22)),
            PlayEventEntity(trackId = 1, playedAt = at(27, 8)),
            PlayEventEntity(trackId = 2, playedAt = at(26, 22)),
            PlayEventEntity(trackId = 3, playedAt = at(28, 13)),
            PlayEventEntity(trackId = 4, playedAt = at(28, 13)), // a voice note: not music
            PlayEventEntity(trackId = 99, playedAt = at(28, 13)), // deleted file
        )
        val analyses = listOf(
            SongAnalysisEntity(1, "k", energy = 0.2f, loudnessDb = -14f, analyzedAt = 0),
            SongAnalysisEntity(3, "k", energy = 0.8f, loudnessDb = -10f, analyzedAt = 0),
        )
        val recap = RecapRepository.compute(events, tracks, analyses, zone)
        assertEquals(5, recap.plays)
        assertEquals((3 * 180 + 240 + 120) / 60L, recap.minutes)
        assertEquals(3, recap.songs)
        assertEquals(2, recap.artists)
        assertEquals(listOf(1L, 2L, 3L), recap.topTracks.map { it.track.id })
        assertEquals(3, recap.topTracks.first().plays)
        assertEquals(listOf("Elis Regina" to 4, "Tom Zé" to 1), recap.topArtists.map { it.name to it.plays })
        assertEquals(3, recap.byHour[22])
        assertEquals(1, recap.byHour[8])
        assertEquals("Monday", 1, recap.busiestDay)
        assertEquals(4, recap.analyzedPlays)
        assertEquals(0.75f, recap.calmShare, 0.001f)
        assertEquals(0.25f, recap.energyShare, 0.001f)
    }

    @Test
    fun `no plays means an empty recap`() {
        val recap = RecapRepository.compute(emptyList(), tracks, emptyList(), zone)
        assertTrue(recap.isEmpty)
        assertEquals(null, recap.busiestDay)
    }
}
