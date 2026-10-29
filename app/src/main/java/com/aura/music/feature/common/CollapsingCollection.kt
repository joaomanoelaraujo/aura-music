package com.aura.music.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.core.designsystem.atmosphere.Atmosphere
import com.aura.music.core.designsystem.atmosphere.LocalGradientIntensity
import com.aura.music.core.designsystem.atmosphere.atmosphereBackground
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.ui.LocalBottomInset

/**
 * Scaffold for album, artist, playlist and collection screens: a hero that scrolls away
 * with gentle parallax while the top bar fades in a solid background and the title.
 */
@Composable
fun CollapsingCollection(
    title: String,
    atmosphere: Atmosphere,
    onBack: () -> Unit,
    hero: @Composable (heroSize: Dp) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val collapseDistance = with(density) { 220.dp.toPx() }
    val collapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().atmosphereBackground(atmosphere, glowCenterFraction = 0.12f, glowStrength = LocalGradientIntensity.current)) {
        val heroSize = minOf(maxWidth * 0.64f, 280.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x6),
        ) {
            item(key = "hero") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Spacer(Modifier.height(56.dp + Spacing.x2))
                    Box(
                        Modifier.graphicsLayer {
                            val offset = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f
                            translationY = offset * 0.45f
                            val scale = 1f - 0.12f * collapse
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - collapse
                        },
                    ) { hero(heroSize) }
                    Spacer(Modifier.height(Spacing.x6))
                }
            }
            content()
        }
        AuraTopBar(
            title = title,
            onBack = onBack,
            titleAlpha = ((collapse - 0.6f) / 0.4f).coerceIn(0f, 1f),
            background = atmosphere.background.copy(alpha = (collapse * 0.96f).coerceIn(0f, 0.96f)),
            actions = actions,
        )
    }
}
