package com.aura.music.feature.player

import com.aura.music.feature.player.CoverSwipe.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSwipeTest {

    private val width = 900f
    private val fling = 1800f

    private fun decide(offset: Float, velocity: Float = 0f, hasPrevious: Boolean = true, hasNext: Boolean = true) =
        CoverSwipe.decide(offset, velocity, width, fling, hasPrevious, hasNext)

    @Test
    fun `dragging left past the threshold goes to the next track`() {
        assertEquals(Outcome.Next, decide(-width * 0.35f))
    }

    @Test
    fun `dragging right past the threshold goes to the previous track`() {
        assertEquals(Outcome.Previous, decide(width * 0.35f))
    }

    @Test
    fun `short slow drags spring back`() {
        assertEquals(Outcome.Cancel, decide(-width * 0.15f))
        assertEquals(Outcome.Cancel, decide(width * 0.2f))
    }

    @Test
    fun `a quick fling commits even with a short distance`() {
        assertEquals(Outcome.Next, decide(-width * 0.1f, velocity = -2500f))
    }

    @Test
    fun `a twitch is never a song change`() {
        assertEquals(Outcome.Cancel, decide(-width * 0.02f, velocity = -4000f))
    }

    @Test
    fun `flinging back towards the centre cancels`() {
        assertEquals(Outcome.Cancel, decide(-width * 0.5f, velocity = 2500f))
    }

    @Test
    fun `nothing happens when there is no track on that side`() {
        assertEquals(Outcome.Cancel, decide(-width * 0.5f, hasNext = false))
        assertEquals(Outcome.Cancel, decide(width * 0.5f, hasPrevious = false))
    }
}
