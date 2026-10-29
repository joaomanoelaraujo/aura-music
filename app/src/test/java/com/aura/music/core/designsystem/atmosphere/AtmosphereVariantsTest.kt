package com.aura.music.core.designsystem.atmosphere

import com.aura.music.core.model.HomeSection
import com.aura.music.core.model.HomeSectionPref
import com.aura.music.core.model.HomeSections
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphereVariantsTest {

    private fun randomPalette(random: Random): AtmosphereColors {
        val swatches = List(6) { Swatch(0xFF000000.toInt() or random.nextInt(0xFFFFFF), random.nextInt(1, 500)) }
        return AtmosphereColorMath.derive(swatches)
    }

    @Test
    fun `light variant keeps AA contrast for accent and its content`() {
        val random = Random(7)
        repeat(2_000) {
            val light = AtmosphereColorMath.lightVariant(randomPalette(random))
            assertTrue(AtmosphereColorMath.contrastRatio(light.accent, light.background) >= AtmosphereColorMath.MIN_ACCENT_CONTRAST)
            assertTrue(AtmosphereColorMath.contrastRatio(light.accent, light.onAccent) >= AtmosphereColorMath.MIN_ACCENT_CONTRAST)
            assertTrue(AtmosphereColorMath.relativeLuminance(light.background) > 0.8)
        }
    }

    @Test
    fun `partial intensity never drops the accent below AA`() {
        val random = Random(11)
        repeat(2_000) {
            val palette = randomPalette(random)
            val amount = random.nextFloat()
            val dark = AtmosphereColorMath.blend(AtmosphereColorMath.Neutral, palette, amount)
            assertTrue(AtmosphereColorMath.contrastRatio(dark.accent, dark.background) >= AtmosphereColorMath.MIN_ACCENT_CONTRAST)
            val light = AtmosphereColorMath.blend(AtmosphereColorMath.NeutralLight, AtmosphereColorMath.lightVariant(palette), amount)
            assertTrue(AtmosphereColorMath.contrastRatio(light.accent, light.background) >= AtmosphereColorMath.MIN_ACCENT_CONTRAST)
        }
    }

    @Test
    fun `blend endpoints return the inputs`() {
        val palette = randomPalette(Random(3))
        assertEquals(AtmosphereColorMath.Neutral, AtmosphereColorMath.blend(AtmosphereColorMath.Neutral, palette, 0f))
        assertEquals(palette, AtmosphereColorMath.blend(AtmosphereColorMath.Neutral, palette, 1f))
        assertEquals(AtmosphereColorMath.NeutralLight, AtmosphereColorMath.lightVariant(AtmosphereColorMath.Neutral))
    }

    @Test
    fun `home sections survive encoding, drop unknown entries and gain new ones`() {
        val custom = listOf(HomeSectionPref(HomeSection.TopArtists, true), HomeSectionPref(HomeSection.Continue, false))
        val decoded = HomeSections.decode(HomeSections.encode(custom) + ",Removed:1,garbage")
        assertEquals(custom, decoded.take(2))
        assertEquals(HomeSection.entries.toSet(), decoded.map { it.section }.toSet())
        assertEquals(HomeSections.Default, HomeSections.decode(null))
    }
}
