package com.aura.music.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.Atmosphere
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.PlayPauseButton
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.Track
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Collapsed player row. The artwork itself is drawn by [PlayerSheet] so it can morph into the
 * full-size cover; this row only reserves its space.
 */
@Composable
fun MiniPlayerContent(
    track: Track,
    isPlaying: Boolean,
    progressFraction: Float,
    atmosphere: Atmosphere,
    swipeEnabled: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val expandLabel = stringResource(R.string.cd_expand_player)

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizes.miniPlayerHeight - 2.dp)
                .clickable(role = Role.Button, onClickLabel = expandLabel, onClick = onExpand)
                .then(
                    if (!swipeEnabled) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val value = offset.value
                                    if (abs(value) > threshold) if (value < 0) onNext() else onPrevious()
                                    scope.launch { offset.animateTo(0f) }
                                },
                                onDragCancel = { scope.launch { offset.animateTo(0f) } },
                            ) { change, amount ->
                                change.consume()
                                scope.launch { offset.snapTo(offset.value + amount * 0.6f) }
                            }
                        }
                    },
                )
                .padding(start = Spacing.x2, end = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(48.dp + Spacing.x3))
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer {
                        translationX = offset.value
                        alpha = 1f - (abs(offset.value) / (threshold * 2.5f)).coerceIn(0f, 0.7f)
                    },
            ) {
                Text(track.title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    artistLabel(track.artist),
                    style = AuraTheme.type.bodySmall,
                    color = AuraTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlayPauseButton(
                isPlaying = isPlaying,
                onClick = onTogglePlay,
                size = 40.dp,
                container = Color.Transparent,
                content = AuraTheme.colors.textPrimary,
            )
            AuraIconButton(AuraIcons.Next, stringResource(R.string.cd_next), onNext, iconSize = 22.dp)
        }
        MiniProgress(progressFraction, atmosphere.accent)
    }
}

@Composable
private fun MiniProgress(fraction: Float, color: Color) {
    val track = AuraTheme.colors.textPrimary.copy(alpha = 0.08f)
    Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = Spacing.x4)) {
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            val y = size.height / 2f
            drawLine(track, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), size.height, StrokeCap.Round)
            if (fraction > 0f) {
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * fraction, y), size.height, StrokeCap.Round)
            }
        }
    }
}
