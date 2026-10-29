package com.aura.music.feature.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingGeometryTest {

    @Test
    fun `controls always fit on a small phone`() {
        val height = 640.dp
        val geometry = NowPlayingGeometry.compute(width = 360.dp, height = height, topInset = 24.dp, bottomInset = 48.dp)
        val bottomOfControls = geometry.artworkTop + geometry.artworkSize + geometry.gapBelowArtwork +
            NowPlayingGeometry.TitleBlockHeight + NowPlayingGeometry.SeekHeight + NowPlayingGeometry.ControlsHeight +
            NowPlayingGeometry.UtilityHeight
        assertTrue("controls overflow: $bottomOfControls", bottomOfControls <= height)
        assertTrue(geometry.artworkSize >= 120.dp)
    }

    @Test
    fun `large font scale grows the title block and keeps controls on screen`() {
        val height = 780.dp
        val geometry = NowPlayingGeometry.compute(width = 360.dp, height = height, topInset = 24.dp, bottomInset = 48.dp, fontScale = 1.5f)
        assertTrue(geometry.titleBlockHeight > NowPlayingGeometry.TitleBlockHeight)
        val bottomOfControls = geometry.artworkTop + geometry.artworkSize + geometry.gapBelowArtwork +
            geometry.titleBlockHeight + NowPlayingGeometry.SeekHeight + NowPlayingGeometry.ControlsHeight +
            NowPlayingGeometry.UtilityHeight
        assertTrue("controls overflow: $bottomOfControls", bottomOfControls <= height)
    }

    @Test
    fun `artwork is width-bound on tall phones`() {
        val geometry = NowPlayingGeometry.compute(width = 411.dp, height = 914.dp, topInset = 32.dp, bottomInset = 24.dp)
        assertTrue(geometry.artworkSize == 411.dp - NowPlayingGeometry.HorizontalPadding * 2)
        assertTrue(geometry.gapAboveArtwork > 0.dp && geometry.gapBelowArtwork > 0.dp)
    }

    @Test
    fun `artwork shrinks instead of overflowing in landscape-like heights`() {
        val geometry = NowPlayingGeometry.compute(width = 800.dp, height = 560.dp, topInset = 0.dp, bottomInset = 0.dp)
        assertTrue(geometry.artworkSize < 800.dp - NowPlayingGeometry.HorizontalPadding * 2)
    }
}
