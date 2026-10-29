package com.aura.music.feature.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.ui.PlayerSheetController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Continuous 0..1 expansion of the player: 0 is the mini-player, 1 the full Now Playing screen.
 * Every visual property of the sheet is interpolated from this single value.
 */
@Stable
class PlayerSheetState(
    private val scope: CoroutineScope,
    private val reducedMotion: () -> Boolean,
) : PlayerSheetController {

    var progress by mutableFloatStateOf(0f)
        private set

    private var settleJob: Job? = null

    internal fun snapTo(value: Float) {
        settleJob?.cancel()
        progress = value.coerceIn(0f, 1f)
    }

    val isExpanded: Boolean get() = progress > 0.5f

    override fun expand() = settle(target = 1f, velocity = 0f)

    override fun collapse() = settle(target = 0f, velocity = 0f)

    fun dragBy(deltaFraction: Float) {
        settleJob?.cancel()
        progress = (progress + deltaFraction).coerceIn(0f, 1f)
    }

    /**
     * Settles to the nearest state, honouring fling direction.
     * [velocity] is in progress units per second; positive means expanding.
     */
    fun release(velocity: Float) {
        val target = when {
            velocity > FLING_THRESHOLD -> 1f
            velocity < -FLING_THRESHOLD -> 0f
            else -> if (progress > 0.5f) 1f else 0f
        }
        settle(target, velocity)
    }

    private fun settle(target: Float, velocity: Float) {
        settleJob?.cancel()
        if (reducedMotion()) {
            progress = target
            return
        }
        settleJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f),
            ) { value, _ -> progress = value.coerceIn(0f, 1f) } // a fling can overshoot the spring target
        }
    }

    private companion object {
        const val FLING_THRESHOLD = 1.2f
    }
}

/** Survives configuration changes (rotation, font scale) by remembering whether the player was open. */
@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    return rememberSaveable(
        scope,
        saver = Saver(
            save = { it.isExpanded },
            restore = { expanded -> PlayerSheetState(scope) { reduced }.apply { snapTo(if (expanded) 1f else 0f) } },
        ),
    ) { PlayerSheetState(scope) { reduced } }
}

/**
 * Pre-computed layout of the full player for a given screen, so the artwork can morph
 * between mini and full positions without waiting for a measure pass.
 */
data class NowPlayingGeometry(
    val artworkSize: Dp,
    val artworkTop: Dp,
    val gapAboveArtwork: Dp,
    val gapBelowArtwork: Dp,
    /** Grows with the user's font scale so large text never collides with the seek bar. */
    val titleBlockHeight: Dp = TitleBlockHeight,
) {
    companion object {
        val HorizontalPadding = 28.dp
        val HeaderHeight = 64.dp
        val TitleBlockHeight = 72.dp
        val SeekHeight = 52.dp
        val ControlsHeight = 88.dp
        val UtilityHeight = 52.dp
        private val FixedBelowTitle = SeekHeight + ControlsHeight + UtilityHeight + 28.dp
        private val MinArtwork = 120.dp
        private val MinGap = 12.dp
        private const val MAX_TITLE_SCALE = 1.6f

        fun compute(width: Dp, height: Dp, topInset: Dp, bottomInset: Dp, fontScale: Float = 1f): NowPlayingGeometry {
            val titleBlock = TitleBlockHeight * fontScale.coerceIn(1f, MAX_TITLE_SCALE)
            val available = height - topInset - HeaderHeight - titleBlock - FixedBelowTitle - bottomInset - 16.dp
            val maxByWidth = width - HorizontalPadding * 2
            val size = minOf(maxByWidth, available - MinGap * 2).coerceAtLeast(MinArtwork)
            val free = (available - size).coerceAtLeast(MinGap * 2)
            val above = lerp(MinGap, free - MinGap, 0.42f)
            return NowPlayingGeometry(
                artworkSize = size,
                artworkTop = topInset + HeaderHeight + above,
                gapAboveArtwork = above,
                gapBelowArtwork = free - above,
                titleBlockHeight = titleBlock,
            )
        }
    }
}
