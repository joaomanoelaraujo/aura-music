package com.aura.music.core.model

import com.aura.music.core.common.formatDuration
import com.aura.music.core.common.totalMinutes
import com.aura.music.track
import org.junit.Assert.assertEquals
import org.junit.Test

class SortingTest {

    private val tracks = listOf(
        track(1, title = "beta", durationMs = 200_000, dateAddedSec = 30),
        track(2, title = "Alpha", durationMs = 100_000, dateAddedSec = 10),
        track(3, title = "Ábaco", durationMs = 300_000, dateAddedSec = 20),
    )

    @Test
    fun `title sort is case and accent insensitive`() {
        assertEquals(listOf(3L, 2L, 1L), tracks.applySort(SortSpec(TrackSort.Title, true)).map { it.id })
    }

    @Test
    fun `descending duration puts longest first`() {
        assertEquals(listOf(3L, 1L, 2L), tracks.applySort(SortSpec(TrackSort.Duration, false)).map { it.id })
    }

    @Test
    fun `date added ascending is oldest first`() {
        assertEquals(listOf(2L, 3L, 1L), tracks.applySort(SortSpec(TrackSort.DateAdded, true)).map { it.id })
    }

    @Test
    fun `missing names sort after named ones`() {
        assertEquals(-1, compareText("a", null))
        assertEquals(1, compareText("", "a"))
        assertEquals(0, compareText(null, " "))
    }

    @Test
    fun `durations are formatted for display`() {
        assertEquals("0:00", formatDuration(-5))
        assertEquals("3:07", formatDuration(187_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals(42, totalMinutes(42 * 60_000L + 10_000))
    }
}
