package com.aura.music.playback

import com.aura.music.data.analysis.IntensityAnalyzer
import com.aura.music.playback.service.VolumePlan
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumePlanTest {

    @Test
    fun `loud songs are turned down to the target, quiet ones are left alone`() {
        assertEquals(1f, VolumePlan.normalization(null), 0f)
        assertEquals("quieter than the target", 1f, VolumePlan.normalization(-22f), 0f)
        val loud = VolumePlan.normalization(-8f)
        assertEquals("8 dB down", -8.0, 20 * log10(loud.toDouble()), 0.01)
        val extreme = VolumePlan.normalization(10f)
        assertEquals("never more than 12 dB down", -12.0, 20 * log10(extreme.toDouble()), 0.01)
    }

    @Test
    fun `transitions fade out before the next song and in after it, never otherwise`() {
        val fade = 6_000L
        val duration = 200_000L
        assertEquals("no fade setting", 1f, VolumePlan.transition(199_000, duration, 0, fadeIn = true, hasNext = true), 0f)
        assertEquals("middle of the song", 1f, VolumePlan.transition(100_000, duration, fade, fadeIn = true, hasNext = true), 0f)
        assertEquals("last song of the queue ends normally", 1f, VolumePlan.transition(199_000, duration, fade, fadeIn = false, hasNext = false), 0f)
        val ending = (0..6).map { VolumePlan.transition(duration - fade + it * 1_000L, duration, fade, fadeIn = false, hasNext = true) }
        assertTrue("falls steadily: $ending", ending.zipWithNext().all { (a, b) -> b < a } && ending.last() == 0f)
        val starting = (0..6).map { VolumePlan.transition(it * 1_000L, duration, fade, fadeIn = true, hasNext = true) }
        assertTrue("rises steadily: $starting", starting.zipWithNext().all { (a, b) -> b > a } && starting.last() == 1f)
        assertEquals("picked by the user: no fade in", 1f, VolumePlan.transition(500, duration, fade, fadeIn = false, hasNext = true), 0f)
        // Equal power: halfway through, each side is at ~71 %, not 50 %.
        assertEquals(0.707f, VolumePlan.transition(3_000, duration, fade, fadeIn = true, hasNext = true), 0.01f)
    }

    @Test
    fun `the analysis measures how loud a song plays`() {
        fun loudness(amplitude: Double): Float {
            val rate = 22_050
            val analyzer = IntensityAnalyzer(rate)
            analyzer.feed(FloatArray(rate * 10) { (amplitude * sin(2 * PI * 440 * it / rate)).toFloat() })
            return analyzer.finish().loudnessDb
        }
        // A sine at amplitude A has an RMS of A/√2: -3 dB relative to its peak.
        assertEquals(-3.0f, loudness(1.0), 0.3f)
        assertEquals(-23.0f, loudness(0.1), 0.3f)
    }
}
