package com.aura.music.core.designsystem.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.common.formatDuration
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Spacing

/**
 * Thin, calm progress line that grows a thumb only while it's being touched.
 * Seeking is committed on release so audio doesn't stutter during the drag.
 */
@Composable
fun AuraSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AuraTheme.colors.accent,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(0)
    val played = if (duration == 0L) 0f else (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val fraction = dragFraction ?: played
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentDuration by rememberUpdatedState(duration)
    val reduced = LocalReducedMotion.current
    val thumbRadius by animateDpAsState(
        if (dragFraction != null) 8.dp else 0.dp,
        Motion.tween(Motion.SHORT, reduced),
        label = "thumb",
    )
    val trackHeight by animateDpAsState(
        if (dragFraction != null) 6.dp else 4.dp,
        Motion.tween(Motion.SHORT, reduced),
        label = "track",
    )
    val inactive = AuraTheme.colors.textPrimary.copy(alpha = 0.14f)
    val description = stringResource(R.string.cd_seek_bar)
    val state = stringResource(R.string.seek_state, formatDuration((fraction * duration).toLong()), formatDuration(duration))

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics {
                    contentDescription = description
                    stateDescription = state
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        currentOnSeek((target.coerceIn(0f, 1f) * currentDuration).toLong())
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val target = (offset.x / size.width).coerceIn(0f, 1f)
                        currentOnSeek((target * currentDuration).toLong())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let { currentOnSeek((it * currentDuration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    )
                },
        ) {
            val height = trackHeight.toPx()
            val top = (size.height - height) / 2f
            val radius = CornerRadius(height / 2f)
            drawRoundRect(inactive, Offset(0f, top), Size(size.width, height), radius)
            drawRoundRect(accent, Offset(0f, top), Size(size.width * fraction, height), radius)
            if (thumbRadius > 0.dp) {
                drawCircle(accent, thumbRadius.toPx(), Offset(size.width * fraction, size.height / 2f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.x1)) {
            Text(
                formatDuration((fraction * duration).toLong()),
                style = AuraTheme.type.numeric,
                color = AuraTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(formatDuration(duration), style = AuraTheme.type.numeric, color = AuraTheme.colors.textSecondary)
        }
    }
}
