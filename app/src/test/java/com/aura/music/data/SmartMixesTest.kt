package com.aura.music.data

import com.aura.music.data.local.db.SongAnalysisEntity
import com.aura.music.data.local.db.TrackPlayStats
import com.aura.music.data.repository.MixKind
import com.aura.music.data.repository.SmartMixes
import com.aura.music.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMixesTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 400 * day
    private val music = (1L..20L).map { track(it) }

    /** Track n has energy n/20: 1..6 calm, 13..20 energetic. Track 7 couldn't be analyzed. */
    private val analyses = (1L..20L).map { id ->
        SongAnalysisEntity(id, "k", energy = id / 20f, loudnessDb = -12f, analyzedAt = 0, failed = id == 7L)
    }

    private val stats = listOf(
        TrackPlayStats(1, plays = 9, lastPlayed = now - day),
        TrackPlayStats(2, plays = 4, lastPlayed = now - 100 * day),
        TrackPlayStats(3, plays = 1, lastPlayed = now - day),
        TrackPlayStats(4, plays = 6, lastPlayed = now - 90 * day),
        TrackPlayStats(99, plays = 50, lastPlayed = now - 200 * day), // no longer in the library
    )

    private fun ids(kind: MixKind, at: Long = now) = SmartMixes.select(kind, music, analyses, stats, at).map { it.id }

    @Test
    fun `mood mixes follow each song's energy and skip songs that couldn't be analyzed`() {
        assertEquals((1L..6L).toSet(), ids(MixKind.Calm).toSet())
        assertEquals((13L..20L).toSet(), ids(MixKind.Energy).toSet())
        assertTrue(7L !in ids(MixKind.Calm))
    }

    @Test
    fun `mood mixes are reshuffled daily but stable within a day`() {
        val today = ids(MixKind.Energy)
        assertEquals(today, ids(MixKind.Energy, now + 3_600_000))
        val week = (1..7).map { ids(MixKind.Energy, now + it * day) }
        assertTrue("some other day starts differently", week.any { it != today })
    }

    @Test
    fun `history mixes rank by plays and only offer what's still in the library`() {
        assertEquals("played once doesn't count", listOf(1L, 4L, 2L), ids(MixKind.MostPlayed))
        assertEquals("often played, not heard for 60+ days", listOf(4L, 2L), ids(MixKind.Rediscover))
    }
}
