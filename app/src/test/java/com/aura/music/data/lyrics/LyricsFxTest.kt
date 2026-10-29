package com.aura.music.data.lyrics

import com.aura.music.data.analysis.IntensityAnalyzer
import com.aura.music.data.analysis.IntensityProfile
import com.aura.music.data.analysis.LineMood
import com.aura.music.data.analysis.LyricMoods
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFxTest {
    private val rate = 22_050

    /** Feeds [seconds] of audio produced by [sample] (time in s → value) in 4096-sample chunks. */
    private fun analyze(seconds: Int, sample: (Double) -> Float): IntensityProfile {
        val analyzer = IntensityAnalyzer(rate)
        val total = seconds * rate
        val chunk = FloatArray(4096)
        var done = 0
        while (done < total) {
            val n = minOf(chunk.size, total - done)
            for (i in 0 until n) chunk[i] = sample((done + i).toDouble() / rate)
            analyzer.feed(chunk, n)
            done += n
        }
        return analyzer.finish()
    }

    /** A soft ballad: a quiet, slowly swelling melody, no drums. */
    private fun ballad() = analyze(40) { t ->
        val swell = 0.6 + 0.4 * sin(2 * PI * t / 20)
        (0.05 * swell * (sin(2 * PI * 220 * t) + 0.5 * sin(2 * PI * 330 * t))).toFloat()
    }

    /** A rock song: 20 s of quiet verse, then a loud, rough chorus with a kick every 0.5 s. */
    private fun rock(): IntensityProfile {
        val noise = Random(7)
        return analyze(40) { t ->
            if (t < 20) {
                (0.04 * sin(2 * PI * 196 * t)).toFloat()
            } else {
                val sinceKick = t % 0.5
                val kick = 0.6 * exp(-sinceKick * 40) * sin(2 * PI * 60 * sinceKick)
                val guitar = 0.25 * (noise.nextDouble() * 2 - 1)
                (kick + guitar).toFloat().coerceIn(-1f, 1f)
            }
        }
    }

    @Test
    fun `a ballad is calm as a whole and never reaches a peak`() {
        val profile = ballad()
        assertTrue("energy ${profile.energy}", profile.energy < 0.35f)
        for (start in 0 until 40_000 step 4_000) {
            val mood = LyricMoods.of(profile, start.toLong(), start + 4_000L)
            assertTrue("line at $start ms was $mood", mood == LineMood.Calm || mood == LineMood.Flow)
        }
        assertTrue("no drums, no hits: ${profile.onsetsMs.size}", profile.onsetsMs.size < 10)
    }

    @Test
    fun `a rock song is energetic, calm in the verse and peaks in the chorus`() {
        val profile = rock()
        assertTrue("energy ${profile.energy}", profile.energy > 0.55f)
        val verse = LyricMoods.of(profile, 4_000, 8_000)
        val chorus = LyricMoods.of(profile, 28_000, 32_000)
        assertTrue("verse was $verse", verse == LineMood.Calm || verse == LineMood.Flow)
        assertEquals("energy ${profile.energy}, chorus level ${profile.mean(28_000, 32_000)}", LineMood.Peak, chorus)
    }

    @Test
    fun `hits are found on the kicks and pulse right after them`() {
        val profile = rock()
        val inChorus = profile.onsetsMs.filter { it in 21_000..39_000 }
        // 36 kicks between 21 s and 39 s; most must be found, near the right time.
        assertTrue("found ${inChorus.size} hits", inChorus.size in 28..40)
        val offBeat = inChorus.count { ms -> val phase = ms % 500; phase in 80..420 }
        assertTrue("$offBeat hits off the beat", offBeat <= 3)
        val kick = inChorus[5].toLong()
        assertTrue("on the hit: ${profile.pulseAt(kick + 20)}", profile.pulseAt(kick + 20) > 0.4f)
        assertTrue("faded: ${profile.pulseAt(kick + 450)}", profile.pulseAt(kick + 450) < 0.1f)
        assertEquals(0f, profile.pulseAt(5_000), 0.05f)
    }

    @Test
    fun `profiles survive being stored`() {
        val profile = rock()
        val bytes = ByteArrayOutputStream().also(profile::write).toByteArray()
        val back = IntensityProfile.read(ByteArrayInputStream(bytes))
        assertNotNull(back)
        assertEquals(profile.energy, back!!.energy, 1e-6f)
        assertArrayEquals(profile.onsetsMs, back.onsetsMs)
        assertEquals(profile.levels.size, back.levels.size)
        assertTrue(profile.levels.indices.all { abs(profile.levels[it] - back.levels[it]) < 0.005f })
        assertEquals(null, IntensityProfile.read(ByteArrayInputStream(byteArrayOf(9, 9, 9))))
    }
}
