package com.aura.music.core.designsystem.atmosphere

import com.aura.music.core.designsystem.atmosphere.AtmosphereColorMath.contrastRatio
import com.aura.music.core.designsystem.atmosphere.AtmosphereColorMath.toHsl
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphereColorMathTest {

    private val textPrimary = 0xFFF5F5F7.toInt()

    @Test
    fun `no swatches falls back to the neutral AURA palette`() {
        assertEquals(AtmosphereColorMath.Neutral, AtmosphereColorMath.derive(emptyList()))
    }

    @Test
    fun `accent always meets AA contrast against the derived background`() {
        val random = Random(42)
        repeat(2_000) {
            val swatches = List(random.nextInt(1, 8)) { Swatch(0xFF000000.toInt() or random.nextInt(0xFFFFFF), random.nextInt(1, 5_000)) }
            val colors = AtmosphereColorMath.derive(swatches)
            assertTrue(
                "accent/background contrast too low for $swatches",
                contrastRatio(colors.accent, colors.background) >= AtmosphereColorMath.MIN_ACCENT_CONTRAST,
            )
            assertTrue(contrastRatio(colors.onAccent, colors.accent) >= AtmosphereColorMath.MIN_ACCENT_CONTRAST)
            assertTrue("primary text must stay legible", contrastRatio(textPrimary, colors.background) >= 12.0)
        }
    }

    @Test
    fun `vivid artwork is toned down rather than reproduced`() {
        val neonPink = 0xFFFF00CC.toInt()
        val colors = AtmosphereColorMath.derive(listOf(Swatch(neonPink, 1_000)))
        assertTrue(toHsl(colors.accent)[1] <= AtmosphereColorMath.MAX_ACCENT_SATURATION + 0.02f)
        assertTrue("background stays near-black", toHsl(colors.background)[2] <= AtmosphereColorMath.BACKGROUND_LIGHTNESS + 0.01f)
    }

    @Test
    fun `grayscale artwork yields a neutral accent`() {
        val colors = AtmosphereColorMath.derive(listOf(Swatch(0xFF808080.toInt(), 1_000), Swatch(0xFF202020.toInt(), 500)))
        assertTrue(toHsl(colors.accent)[1] < 0.02f)
    }

    @Test
    fun `seed prefers a colourful well-represented swatch over a dull dominant one`() {
        val dullGray = Swatch(0xFF3A3A3A.toInt(), 5_000)
        val warmOrange = Swatch(0xFFD9822B.toInt(), 3_000)
        assertEquals(warmOrange.rgb, AtmosphereColorMath.pickSeed(listOf(dullGray, warmOrange)))
    }

    @Test
    fun `hsl round trip is stable`() {
        val color = 0xFF4A7BC8.toInt()
        val (h, s, l) = toHsl(color).let { Triple(it[0], it[1], it[2]) }
        val back = AtmosphereColorMath.fromHsl(h, s, l)
        listOf(16, 8, 0).forEach { shift ->
            val delta = ((color shr shift) and 0xFF) - ((back shr shift) and 0xFF)
            assertTrue(kotlin.math.abs(delta) <= 1)
        }
    }
}
