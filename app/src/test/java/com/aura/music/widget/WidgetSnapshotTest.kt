package com.aura.music.widget

import com.aura.music.playback.service.PlaybackPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetSnapshotTest {

    private val base = WidgetSnapshot(
        hasMedia = true,
        title = "Maré Lenta",
        artist = "Lumen Quartet",
        isPlaying = true,
        albumId = 7,
        trackUri = "content://media/external/audio/media/1",
        positionMs = 30_000,
        durationMs = 120_000,
        capturedAt = 1_000_000,
    )

    @Test
    fun `progress advances with wall clock while playing`() {
        assertEquals(0.5f, base.progressAt(now = 1_000_000 + 30_000), 0.001f)
    }

    @Test
    fun `progress stays put while paused`() {
        assertEquals(0.25f, base.copy(isPlaying = false).progressAt(now = 9_999_999), 0.001f)
    }

    @Test
    fun `progress is clamped and safe without duration`() {
        assertEquals(1f, base.progressAt(now = 99_000_000), 0.001f)
        assertEquals(0f, base.copy(durationMs = 0).progressAt(now = 1_000_000), 0.001f)
    }

    @Test
    fun `empty state has no artwork`() {
        assertNull(WidgetSnapshot.Empty.artwork)
    }

    @Test
    fun `sessions saved at the end of a track resume from its start`() {
        assertEquals(0L, PlaybackPersistence.resumePosition(durationMs = 61_000, savedPositionMs = 60_999))
        assertEquals(30_000L, PlaybackPersistence.resumePosition(durationMs = 61_000, savedPositionMs = 30_000))
        assertEquals(5_000L, PlaybackPersistence.resumePosition(durationMs = 0, savedPositionMs = 5_000))
    }
}
