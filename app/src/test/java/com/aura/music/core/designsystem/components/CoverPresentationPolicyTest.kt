package com.aura.music.core.designsystem.components

import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPresentationPolicyTest {

    private val slot = 930

    @Test
    fun `high resolution square art fills the slot`() {
        val p = CoverPresentationPolicy.decide(1600, 1600, slot)
        assertEquals(CoverQuality.High, p.quality)
        assertEquals(ContentScale.Crop, p.fit)
        assertEquals(1f, p.insetFraction)
    }

    @Test
    fun `medium resolution still fills the slot with a single scaling step`() {
        val p = CoverPresentationPolicy.decide(600, 600, slot)
        assertEquals(CoverQuality.Medium, p.quality)
        assertEquals(1f, p.insetFraction)
    }

    @Test
    fun `low resolution art is shown smaller and never enlarged more than twice`() {
        val p = CoverPresentationPolicy.decide(300, 300, slot)
        assertEquals(CoverQuality.Low, p.quality)
        assertTrue(p.insetFraction < 1f)
        assertTrue("upscale ${p.insetFraction * slot / 300}", p.insetFraction * slot <= 300 * CoverPresentationPolicy.MAX_UPSCALE + 1)
    }

    @Test
    fun `tiny art keeps a dignified minimum presence`() {
        val p = CoverPresentationPolicy.decide(90, 90, slot)
        assertEquals(CoverPresentationPolicy.MIN_INSET_FRACTION, p.insetFraction)
    }

    @Test
    fun `non square art is fitted, not cropped or stretched`() {
        val p = CoverPresentationPolicy.decide(1400, 900, slot)
        assertEquals(ContentScale.Fit, p.fit)
        assertEquals(CoverQuality.High, p.quality)
    }

    @Test
    fun `nearly square art is treated as square`() {
        assertEquals(ContentScale.Crop, CoverPresentationPolicy.decide(1000, 980, slot).fit)
    }

    @Test
    fun `invalid sizes fall back to full presentation`() {
        assertEquals(CoverPresentation.Full, CoverPresentationPolicy.decide(0, 100, slot))
    }
}
